package com.safeguard.parentalcontrol.util

import android.content.Context
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Round-trip and legacy-compatibility tests for [ImageBlurManager]'s metadata sidecar,
 * focused on the [ImageBlurManager.ImageMetadata.blurApplied] field introduced with the
 * Maximum Protection feature (010).
 *
 * Only [ImageBlurManager.saveMetadata]/[ImageBlurManager.loadMetadata] are exercised; both
 * take an explicit [File], and [ImageBlurManager]'s backup directory is lazy, so a relaxed
 * mock [Context] never needs a real Android runtime.
 */
class ImageBlurMetadataTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val context = mockk<Context>(relaxed = true)
    private val manager = ImageBlurManager(context)

    private fun sampleMetadata(blurApplied: Boolean) = ImageBlurManager.ImageMetadata(
        backupId = "abc123def456",
        originalPath = "/storage/emulated/0/Download/flagged.jpg",
        category = "nsfw",
        confidence = 0.87f,
        timestamp = 1_700_000_000_000L,
        originalSize = 204_800L,
        originalName = "flagged.jpg",
        blurApplied = blurApplied
    )

    @Test
    fun `round-trip preserves blurApplied true`() {
        val file = tempFolder.newFile("blurred.meta")
        val original = sampleMetadata(blurApplied = true)

        manager.saveMetadata(file, original)
        val loaded = manager.loadMetadata(file)

        assertEquals(original, loaded)
        assertEquals(true, loaded?.blurApplied)
    }

    @Test
    fun `round-trip preserves blurApplied false`() {
        val file = tempFolder.newFile("copyonly.meta")
        val original = sampleMetadata(blurApplied = false)

        manager.saveMetadata(file, original)
        val loaded = manager.loadMetadata(file)

        assertEquals(original, loaded)
        assertEquals(false, loaded?.blurApplied)
    }

    @Test
    fun `legacy metadata without blurApplied key parses as true`() {
        // Simulate a metadata file written before the blurApplied field existed.
        val file = tempFolder.newFile("legacy.meta")
        file.writeText(
            buildString {
                appendLine("backupId=abc123def456")
                appendLine("originalPath=/storage/emulated/0/Download/flagged.jpg")
                appendLine("category=nsfw")
                appendLine("confidence=0.87")
                appendLine("timestamp=1700000000000")
                appendLine("originalSize=204800")
                appendLine("originalName=flagged.jpg")
            }
        )

        val loaded = manager.loadMetadata(file)

        assertTrue("Legacy files predate copy-only mode and must read as blurred", loaded?.blurApplied == true)
    }

    @Test
    fun `serialized metadata contains blurApplied line`() {
        val file = tempFolder.newFile("check.meta")
        manager.saveMetadata(file, sampleMetadata(blurApplied = false))

        assertTrue(file.readLines().any { it == "blurApplied=false" })
    }
}
