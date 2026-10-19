package com.safeguard.parentalcontrol.util

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * What happens when a write to the child's gallery cannot be completed.
 *
 * Feature `016-remove-all-files-access`, FR-016 and FR-018. This is the behaviour that stands between
 * a failed blur and a destroyed photo, so the invariant under test is blunt: **a failure never
 * discards the private backup.** As long as the backup survives, the original is recoverable; once it
 * is gone, nothing is.
 *
 * Scope. There is no Robolectric in this project, so the paths that decode or compress a bitmap
 * cannot run here — the blur-and-write sequence itself is verified on a device, quickstart scenario 6.
 * What *is* covered here is every branch that only touches files and the write seam: restoring an
 * approved original, deleting a rejected image, and the pre-flight check that tells the parent what is
 * missing before they tap rather than after.
 */
class ImageBlurWriteFailureTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val context = mockk<Context>(relaxed = true)
    private val mediaWriteAccess = mockk<MediaWriteAccess>(relaxed = true)
    private lateinit var manager: ImageBlurManager
    private lateinit var backupDir: File

    private val originalPath = "/storage/emulated/0/DCIM/Camera/flagged.jpg"
    private val backupId = "abc123def456"

    @Before
    fun setUp() {
        val filesDir = tempFolder.newFolder("files")
        every { context.filesDir } returns filesDir
        manager = ImageBlurManager(context, mediaWriteAccess)
        backupDir = File(filesDir, "image_backup").apply { mkdirs() }
    }

    /** Lay down a backup plus its metadata, as a real violation would have. */
    private fun givenBackup(blurApplied: Boolean = true, reason: String? = null): File {
        val backupFile = File(backupDir, "$backupId.jpg").apply { writeText("ORIGINAL-BYTES") }
        manager.saveMetadata(
            File(backupDir, "$backupId.meta"),
            ImageBlurManager.ImageMetadata(
                backupId = backupId,
                originalPath = originalPath,
                category = "nsfw",
                confidence = 0.9f,
                timestamp = 1_700_000_000_000L,
                originalSize = 14L,
                originalName = "flagged.jpg",
                blurApplied = blurApplied,
                notBlurredReason = reason
            )
        )
        return backupFile
    }

    private fun metadataFile() = File(backupDir, "$backupId.meta")

    // -----------------------------------------------------------------------------------
    // restoreOriginal — the parent approved the image
    // -----------------------------------------------------------------------------------

    @Test
    fun `a failed restore keeps the backup so the original stays recoverable`() {
        val backupFile = givenBackup()
        every { mediaWriteAccess.write(any(), any()) } returns
            MediaWriteAccess.WriteResult.Failed("No space left on device")

        val restored = manager.restoreOriginal(backupId)

        assertFalse("A failed write must not report success", restored)
        assertTrue("The backup is the only copy of the original — it must survive", backupFile.exists())
        assertTrue("Its metadata must survive with it", metadataFile().exists())
    }

    @Test
    fun `a restore blocked by missing folder access keeps the backup`() {
        val backupFile = givenBackup()
        every { mediaWriteAccess.write(any(), any()) } returns
            MediaWriteAccess.WriteResult.NoAccess(MediaWriteAccess.GrantState.REVOKED)

        assertFalse(manager.restoreOriginal(backupId))
        assertTrue(backupFile.exists())
    }

    @Test
    fun `a verification failure counts as a failure, not a success`() {
        // The stream closed without throwing but the bytes are not there. Reporting success here
        // would drop the backup and leave the child's gallery holding a damaged file.
        val backupFile = givenBackup()
        every { mediaWriteAccess.write(any(), any()) } returns
            MediaWriteAccess.WriteResult.VerificationFailed(expectedBytes = 14L, actualBytes = 0L)

        assertFalse(manager.restoreOriginal(backupId))
        assertTrue(backupFile.exists())
    }

    @Test
    fun `a successful restore clears the backup`() {
        val backupFile = givenBackup()
        every { mediaWriteAccess.write(any(), any()) } returns MediaWriteAccess.WriteResult.Success

        assertTrue(manager.restoreOriginal(backupId))
        assertFalse("Only a verified restore may drop the backup", backupFile.exists())
        assertFalse(metadataFile().exists())
    }

    @Test
    fun `approving a copy-only backup needs no write at all`() {
        // Nothing was ever done to the gallery image, so there is nothing to put back — approval just
        // drops the private copy. Requiring folder access here would block the parent for no reason.
        val backupFile = givenBackup(blurApplied = false)
        every { mediaWriteAccess.write(any(), any()) } returns
            MediaWriteAccess.WriteResult.NoAccess(MediaWriteAccess.GrantState.NOT_REQUESTED)

        assertTrue(manager.restoreOriginal(backupId))
        assertFalse(backupFile.exists())
    }

    @Test
    fun `restoring an unknown backup reports failure`() {
        assertFalse(manager.restoreOriginal("no-such-backup"))
    }

    // -----------------------------------------------------------------------------------
    // deleteImage — the parent rejected the image
    // -----------------------------------------------------------------------------------

    @Test
    fun `a failed delete keeps the backup so the parent can try again`() {
        val backupFile = givenBackup()
        every { mediaWriteAccess.deleteDocument(any()) } returns
            MediaWriteAccess.WriteResult.Failed("The system refused to delete the image")

        assertFalse(manager.deleteImage(backupId))
        assertTrue(
            "Dropping the backup while the image is still on the device would leave the child's " +
                "copy in place with no way to act on it",
            backupFile.exists()
        )
    }

    @Test
    fun `a delete blocked by missing folder access keeps the backup`() {
        val backupFile = givenBackup()
        every { mediaWriteAccess.deleteDocument(any()) } returns
            MediaWriteAccess.WriteResult.NoAccess(MediaWriteAccess.GrantState.NOT_REQUESTED)

        assertFalse(manager.deleteImage(backupId))
        assertTrue(backupFile.exists())
    }

    @Test
    fun `a successful delete clears both files`() {
        val backupFile = givenBackup()
        every { mediaWriteAccess.deleteDocument(any()) } returns MediaWriteAccess.WriteResult.Success

        assertTrue(manager.deleteImage(backupId))
        assertFalse(backupFile.exists())
        assertFalse(metadataFile().exists())
    }

    // -----------------------------------------------------------------------------------
    // reviewBlockedReason — tell the parent before they tap, not after
    // -----------------------------------------------------------------------------------

    @Test
    fun `no reason is given when folder access is held`() {
        givenBackup()
        every { mediaWriteAccess.grantStateForPath(originalPath) } returns
            MediaWriteAccess.GrantState.GRANTED

        assertNull(manager.reviewBlockedReason(backupId, ImageBlurManager.ReviewAction.APPROVE))
        assertNull(manager.reviewBlockedReason(backupId, ImageBlurManager.ReviewAction.REJECT))
    }

    @Test
    fun `a revoked grant is named as revoked, not as never granted`() {
        givenBackup()
        every { mediaWriteAccess.grantStateForPath(originalPath) } returns
            MediaWriteAccess.GrantState.REVOKED

        assertEquals(
            ImageBlurManager.REASON_FOLDER_ACCESS_REVOKED,
            manager.reviewBlockedReason(backupId, ImageBlurManager.ReviewAction.REJECT)
        )
    }

    @Test
    fun `an ungrantable folder is named as such so the parent is not sent to grant it`() {
        givenBackup()
        every { mediaWriteAccess.grantStateForPath(originalPath) } returns
            MediaWriteAccess.GrantState.UNGRANTABLE

        assertEquals(
            ImageBlurManager.REASON_FOLDER_NOT_GRANTABLE,
            manager.reviewBlockedReason(backupId, ImageBlurManager.ReviewAction.REJECT)
        )
    }

    @Test
    fun `approving a copy-only backup is never blocked`() {
        givenBackup(blurApplied = false)
        every { mediaWriteAccess.grantStateForPath(originalPath) } returns
            MediaWriteAccess.GrantState.NOT_REQUESTED

        assertNull(
            "Approval of a copy-only backup writes nothing",
            manager.reviewBlockedReason(backupId, ImageBlurManager.ReviewAction.APPROVE)
        )
        assertEquals(
            "Rejection still has to remove the image from the device",
            ImageBlurManager.REASON_NO_FOLDER_ACCESS,
            manager.reviewBlockedReason(backupId, ImageBlurManager.ReviewAction.REJECT)
        )
    }
}
