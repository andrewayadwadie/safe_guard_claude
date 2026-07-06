package com.safeguard.parentalcontrol.ml.download

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

/**
 * JVM unit tests for the Context-free download core. A fake transport writes a controlled
 * payload (whose real SHA-256 we compute here) so integrity, atomic install, idempotency, and
 * the metered gate are all exercised without Android or the network.
 */
class ModelDownloaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val payload = "pretend-this-is-a-tflite-model".toByteArray()
    private val payloadSha = sha256(payload)

    private fun artifact(sha: String = payloadSha, size: Long = payload.size.toLong()) =
        mapOf(
            ModelId.EN to ModelArtifact(
                id = ModelId.EN, fileName = "en.tflite", urlPath = "models/en.tflite",
                sizeBytes = size, sha256 = sha,
            ),
        )

    private class FakeTransport(private val bytes: ByteArray) : ModelTransport {
        var calls = 0
        override suspend fun get(url: String, dest: File, onProgress: (Long, Long) -> Unit) {
            calls++
            dest.writeBytes(bytes)
            onProgress(bytes.size.toLong(), bytes.size.toLong())
        }
    }

    private fun downloader(
        transport: ModelTransport,
        unmetered: Boolean = true,
        artifacts: Map<ModelId, ModelArtifact> = artifact(),
    ) = ModelDownloader(
        modelsDir = File(tmp.root, "models"),
        baseUrl = "https://example.test/",
        transport = transport,
        network = { unmetered },
        artifacts = artifacts,
    )

    @Test
    fun downloadsVerifiesAndInstalls() = runTest {
        val transport = FakeTransport(payload)
        val dl = downloader(transport)

        assertFalse(dl.isReady(ModelId.EN))
        val result = dl.ensure(ModelId.EN)

        assertTrue(result.isSuccess)
        assertTrue(dl.isReady(ModelId.EN))
        assertEquals(payload.size.toLong(), dl.modelFile(ModelId.EN).length())
        assertEquals(ModelStatus.Ready, dl.status(ModelId.EN).value)
        assertEquals(1, transport.calls)
        // no leftover temp file
        assertFalse(File(tmp.root, "models/en.tflite.tmp").exists())
    }

    @Test
    fun alreadyPresentIsNoopAndDoesNotHitTransport() = runTest {
        val transport = FakeTransport(payload)
        val dl = downloader(transport)
        File(tmp.root, "models").mkdirs()
        File(tmp.root, "models/en.tflite").writeBytes(payload)  // pre-installed, correct size

        val result = dl.ensure(ModelId.EN)

        assertTrue(result.isSuccess)
        assertEquals(0, transport.calls)
        assertEquals(ModelStatus.Ready, dl.status(ModelId.EN).value)
    }

    @Test
    fun integrityMismatchRejectsAndLeavesNoFile() = runTest {
        // transport delivers the right bytes, but the manifest pins a different hash
        val dl = downloader(FakeTransport(payload), artifacts = artifact(sha = "deadbeef".repeat(8)))

        val result = dl.ensure(ModelId.EN)

        assertTrue(result.isFailure)
        assertFalse(dl.isReady(ModelId.EN))
        assertFalse(dl.modelFile(ModelId.EN).exists())
        assertFalse(File(tmp.root, "models/en.tflite.tmp").exists())
        assertTrue(dl.status(ModelId.EN).value is ModelStatus.Failed)
    }

    @Test
    fun meteredConnectionDefersWhenUnmeteredRequired() = runTest {
        val transport = FakeTransport(payload)
        val dl = downloader(transport, unmetered = false)

        val result = dl.ensure(ModelId.EN, requireUnmetered = true)

        assertTrue(result.isFailure)
        assertEquals(0, transport.calls)
        assertFalse(dl.isReady(ModelId.EN))
    }

    @Test
    fun meteredConnectionAllowedWhenNotRequired() = runTest {
        val transport = FakeTransport(payload)
        val dl = downloader(transport, unmetered = false)

        val result = dl.ensure(ModelId.EN, requireUnmetered = false)

        assertTrue(result.isSuccess)
        assertTrue(dl.isReady(ModelId.EN))
    }

    @Test
    fun transportErrorSurfacesAsFailure() = runTest {
        val failing = ModelTransport { _, _, _ -> throw IOException("HTTP 503") }
        val dl = downloader(failing)

        val result = dl.ensure(ModelId.EN)

        assertTrue(result.isFailure)
        assertFalse(dl.modelFile(ModelId.EN).exists())
        assertTrue(dl.status(ModelId.EN).value is ModelStatus.Failed)
    }

    @Test
    fun concurrentEnsureDownloadsOnlyOnce() = runTest {
        val gate = CompletableDeferred<Unit>()
        val transport = object : ModelTransport {
            val calls = AtomicInteger(0)
            override suspend fun get(url: String, dest: File, onProgress: (Long, Long) -> Unit) {
                calls.incrementAndGet()
                gate.await()                       // park the first download mid-flight
                dest.writeBytes(payload)
                onProgress(payload.size.toLong(), payload.size.toLong())
            }
        }
        val dl = downloader(transport)

        val first = launch(Dispatchers.IO) { dl.ensure(ModelId.EN) }
        // wait (bounded) until the first download has registered in-flight and is at the gate
        var waited = 0
        while (transport.calls.get() == 0 && waited < 400) { Thread.sleep(5); waited++ }

        val second = dl.ensure(ModelId.EN)         // should bail: already in progress
        assertTrue(second.isFailure)
        assertEquals(1, transport.calls.get())

        gate.complete(Unit)
        first.join()
        assertTrue(dl.isReady(ModelId.EN))
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
