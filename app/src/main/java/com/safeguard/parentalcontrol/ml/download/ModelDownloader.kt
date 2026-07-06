package com.safeguard.parentalcontrol.ml.download

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Downloads the on-device toxicity models on demand (scope §5.6), into the directory the
 * {@code TFLiteTextClassifier} loads from. Language-conditional: EN is fetched first (every
 * locale needs it), AR is fetched lazily the first time Arabic-script text appears — so an
 * English-only user never pays the 166 MB MARBERT download.
 *
 * This core is deliberately Context-free so it unit-tests on the JVM (see ModelDownloaderTest):
 * the network transport and the metered-connection check are injected seams. The Android/Hilt
 * wiring (OkHttp transport, ConnectivityManager check, filesDir) lives in ModelDownloadModule.
 *
 * Install is atomic and integrity-checked: stream to `<name>.tmp`, verify SHA-256 against the
 * pinned hash, then rename into place. A failed/corrupt download never leaves a half file the
 * classifier could mmap into garbage — readiness requires the exact expected size, and the
 * classifier degrades to regex-only until [ensure] reports Ready.
 */
class ModelDownloader(
    private val modelsDir: File,
    private val baseUrl: String,
    private val transport: ModelTransport,
    private val network: NetworkConditions,
    private val artifacts: Map<ModelId, ModelArtifact> = ARTIFACTS,
) {

    private val statuses: Map<ModelId, MutableStateFlow<ModelStatus>> =
        ModelId.values().associateWith { MutableStateFlow<ModelStatus>(ModelStatus.Absent) }

    // Dedup: fire-and-forget triggers can call ensure() many times in quick succession (e.g. a
    // burst of Arabic messages). Only one download per model may be in flight at a time.
    private val inFlight = ConcurrentHashMap.newKeySet<ModelId>()

    /** Observe a model's lifecycle (Absent → Downloading(fraction) → Ready | Failed). */
    fun status(id: ModelId): StateFlow<ModelStatus> = statuses.getValue(id).asStateFlow()

    fun modelFile(id: ModelId): File = File(modelsDir, artifacts.getValue(id).fileName)

    /** Present and the exact expected size — cheap check, no full re-hash on the hot path. */
    fun isReady(id: ModelId): Boolean {
        val f = modelFile(id)
        return f.exists() && f.length() == artifacts.getValue(id).sizeBytes
    }

    /**
     * Ensure [id] is downloaded and verified. No-op (Ready) if already present. Returns the
     * model file on success, or a Failure describing why (metered connection when [requireUnmetered],
     * HTTP/IO error, or integrity mismatch). Safe to call repeatedly and concurrently-ish (the
     * atomic rename is the commit point).
     */
    suspend fun ensure(id: ModelId, requireUnmetered: Boolean = true): Result<File> {
        val artifact = artifacts.getValue(id)
        val dest = modelFile(id)
        val flow = statuses.getValue(id)

        if (isReady(id)) {
            flow.value = ModelStatus.Ready
            return Result.success(dest)
        }
        if (requireUnmetered && !network.isUnmetered()) {
            return Result.failure(ModelDownloadException("metered connection; deferred"))
        }
        if (!inFlight.add(id)) {
            return Result.failure(ModelDownloadException("download already in progress for $id"))
        }

        return try {
            withContext(Dispatchers.IO) {
                modelsDir.mkdirs()
                val tmp = File(modelsDir, "${artifact.fileName}.tmp")
                tmp.delete()
                flow.value = ModelStatus.Downloading(0f)
                try {
                    transport.get("${baseUrl.trimEnd('/')}/${artifact.urlPath}", tmp) { read, total ->
                        if (total > 0) flow.value = ModelStatus.Downloading((read.toFloat() / total).coerceIn(0f, 1f))
                    }
                    val digest = sha256(tmp)
                    if (!digest.equals(artifact.sha256, ignoreCase = true)) {
                        tmp.delete()
                        flow.value = ModelStatus.Failed("integrity check failed")
                        return@withContext Result.failure(ModelDownloadException("sha256 mismatch for ${artifact.fileName}"))
                    }
                    if (tmp.length() != artifact.sizeBytes) {
                        tmp.delete()
                        flow.value = ModelStatus.Failed("size mismatch")
                        return@withContext Result.failure(ModelDownloadException("size mismatch for ${artifact.fileName}"))
                    }
                    // Atomic commit. renameTo can fail across mount points — fall back to copy.
                    if (!tmp.renameTo(dest)) {
                        tmp.copyTo(dest, overwrite = true)
                        tmp.delete()
                    }
                    flow.value = ModelStatus.Ready
                    Result.success(dest)
                } catch (e: Exception) {
                    tmp.delete()
                    flow.value = ModelStatus.Failed(e.message ?: "download failed")
                    Result.failure(e)
                }
            }
        } finally {
            inFlight.remove(id)
        }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        // Pinned manifest. SHA-256 + sizes are the real values of the converted INT8 artifacts.
        // urlPath is relative to baseUrl; the backend blob endpoint (scope §5.6) must serve these.
        // TODO(§5.6): replace this hardcoded manifest with a signed remote manifest + versioning.
        val ARTIFACTS: Map<ModelId, ModelArtifact> = mapOf(
            ModelId.EN to ModelArtifact(
                id = ModelId.EN,
                fileName = "toxicbert_en_int8.tflite",
                urlPath = "models/toxicbert_en_int8.tflite",
                sizeBytes = 112_174_496L,
                sha256 = "fdbb91b03f48fd24f6967573f2d413c2784c0cfc54d91e337bf1f81599ceba11",
            ),
            ModelId.AR to ModelArtifact(
                id = ModelId.AR,
                fileName = "marbert_ar_int8.tflite",
                urlPath = "models/marbert_ar_int8.tflite",
                sizeBytes = 166_364_192L,
                sha256 = "ec64837e1b699d695f29dd1d42efe8bc2da7c308d3ce6f9a276f65804b8d6d89",
            ),
        )
    }
}

enum class ModelId { EN, AR }

data class ModelArtifact(
    val id: ModelId,
    val fileName: String,
    val urlPath: String,
    val sizeBytes: Long,
    val sha256: String,
)

sealed interface ModelStatus {
    data object Absent : ModelStatus
    data class Downloading(val fraction: Float) : ModelStatus
    data object Ready : ModelStatus
    data class Failed(val reason: String) : ModelStatus
}

class ModelDownloadException(message: String) : Exception(message)

/** Streams [url] into [dest], reporting (bytesRead, totalBytes) as it goes. */
fun interface ModelTransport {
    suspend fun get(url: String, dest: File, onProgress: (read: Long, total: Long) -> Unit)
}

/** Whether the active network is unmetered (Wi-Fi-ish) — gates large downloads off cellular. */
fun interface NetworkConditions {
    fun isUnmetered(): Boolean
}
