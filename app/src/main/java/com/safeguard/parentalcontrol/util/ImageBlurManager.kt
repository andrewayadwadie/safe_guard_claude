package com.safeguard.parentalcontrol.util

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicBlur
import androidx.annotation.RequiresApi
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages blurring of inappropriate images detected on child devices.
 *
 * Play Store Compliant Approach:
 * - Blurs inappropriate images locally (no transmission)
 * - Backs up originals to app-private storage
 * - Parent can physically review on child's device
 * - Restore or delete based on parent decision
 *
 * Flow:
 * 1. Inappropriate image detected by ContentClassifier
 * 2. Original backed up to app-private directory
 * 3. Original replaced with heavily blurred version
 * 4. Alert sent to parent (metadata only, no image)
 * 5. Parent physically checks device and approves/rejects
 * 6. On approve: restore original. On reject: delete both.
 */
@Singleton
class ImageBlurManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mediaWriteAccess: MediaWriteAccess
) {
    companion object {
        private const val TAG = "ImageBlurManager"

        // Why an in-place blur did not happen. Stored verbatim in the metadata file and mapped to
        // parent-facing copy at the UI layer, so the wording can change without rewriting history.
        const val REASON_NO_FOLDER_ACCESS = "no_folder_access"
        const val REASON_FOLDER_ACCESS_REVOKED = "folder_access_revoked"
        const val REASON_FOLDER_NOT_GRANTABLE = "folder_not_grantable"
        const val REASON_IMAGE_MISSING = "image_missing"
        const val REASON_WRITE_FAILED = "write_failed"
        const val REASON_BLUR_FAILED = "blur_failed"

        // Backup directory name (in app-private storage)
        private const val BACKUP_DIR = "image_backup"

        // Blur radius (max is 25 for RenderScript)
        private const val BLUR_RADIUS = 25f

        // Number of blur passes for extra blur effect
        private const val BLUR_PASSES = 4

        // Downscale factor before blur (makes blur more intense + faster)
        private const val DOWNSCALE_FACTOR = 8

        // Metadata file extension
        private const val METADATA_EXT = ".meta"

        // Banner stamped onto the blurred image so the parent can tell why it looks like this
        private const val BANNER_TEXT = "Protected by Haris"
    }

    // Directory for storing original backups
    private val backupDir: File by lazy {
        File(context.filesDir, BACKUP_DIR).apply {
            if (!exists()) mkdirs()
        }
    }

    /**
     * Blur an inappropriate image and backup the original.
     *
     * @param imagePath Path to the inappropriate image
     * @param category Detection category (e.g., "nsfw", "nudity")
     * @param confidence Detection confidence (0.0 - 1.0)
     * @return BlurResult with status and backup info
     */
    fun blurImage(imagePath: String, category: String, confidence: Float): BlurResult {
        Timber.i("$TAG: Blurring image: $imagePath (category=$category, confidence=$confidence)")

        val originalFile = File(imagePath)
        if (!originalFile.exists() || !originalFile.canRead()) {
            Timber.e("$TAG: Cannot read original file: $imagePath")
            return BlurResult.Error("Cannot read original file")
        }

        try {
            // Step 1: Generate unique backup ID based on file hash
            val backupId = generateBackupId(imagePath)
            val backupFile = File(backupDir, "$backupId.jpg")
            val metadataFile = File(backupDir, "$backupId$METADATA_EXT")

            // Check if already blurred (backup exists)
            if (backupFile.exists()) {
                Timber.w("$TAG: Image already blurred (backup exists): $backupId")
                return BlurResult.AlreadyBlurred(backupId)
            }

            // Step 2: Backup original to app-private storage.
            originalFile.copyTo(backupFile, overwrite = true)

            // Step 3: VERIFY the backup before anything touches the gallery file.
            //
            // Replacing an image opens its document for writing, which truncates it first, so a
            // failure part-way through would leave a truncated file where the child's photo was.
            // The backup is the only recovery source at that moment, so an unverified backup is not
            // a backup. Nothing below this point can destroy the original.
            if (!isBackupIntact(backupFile, originalFile)) {
                backupFile.delete()
                Timber.e("$TAG: Backup verification failed, refusing to touch the gallery image")
                return BlurResult.Error("Backup verification failed")
            }
            Timber.d("$TAG: Original backed up and verified: ${backupFile.absolutePath}")

            // Steps 4-6: blur, write through the parent-granted folder access, verify the result.
            // Returns null on success, or the reason the gallery image was left alone. On a failure
            // that had already opened the document, the original is restored from the backup.
            val notBlurredReason = writeBlurredToGallery(imagePath, backupFile)

            // Step 7: record what actually happened. blurApplied is true only for a verified write,
            // so the parent is never told an image was blurred when it was not.
            val metadata = ImageMetadata(
                backupId = backupId,
                originalPath = imagePath,
                category = category,
                confidence = confidence,
                timestamp = System.currentTimeMillis(),
                originalSize = originalFile.length(),
                originalName = originalFile.name,
                blurApplied = notBlurredReason == null,
                notBlurredReason = notBlurredReason
            )
            saveMetadata(metadataFile, metadata)

            if (notBlurredReason == null) {
                // Update MediaStore so the gallery thumbnail shows the blurred version.
                refreshMediaStore(imagePath)
                Timber.i("$TAG: Successfully blurred image. Backup ID: $backupId")
            } else {
                // The violation is still backed up and must still reach the parent — the alert is
                // what protects the child. Only the in-place blur was unavailable.
                Timber.w("$TAG: Backed up without blurring ($notBlurredReason). Backup ID: $backupId")
            }

            return BlurResult.Success(backupId, metadata)

        } catch (e: Exception) {
            Timber.e(e, "$TAG: Error blurring image: $imagePath")
            return BlurResult.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * Back up the original WITHOUT altering the gallery image (Maximum Protection OFF).
     *
     * Performs the same first steps as [blurImage] — reserve a backup id, copy the original
     * into app-private storage, and write metadata — but records blurApplied=false and does
     * NOT blur, replace, or touch MediaStore. The gallery image the child sees is left exactly
     * as it was. Shares [blurImage]'s "backup already exists" guard so re-detections of the
     * same image never create duplicate copies.
     *
     * @return BlurResult with status and backup info (same sealed type as [blurImage]).
     */
    fun backupOnly(imagePath: String, category: String, confidence: Float): BlurResult {
        Timber.i("$TAG: Backing up (copy-only) image: $imagePath (category=$category, confidence=$confidence)")

        val originalFile = File(imagePath)
        if (!originalFile.exists() || !originalFile.canRead()) {
            Timber.e("$TAG: Cannot read original file: $imagePath")
            return BlurResult.Error("Cannot read original file")
        }

        try {
            val backupId = generateBackupId(imagePath)
            val backupFile = File(backupDir, "$backupId.jpg")
            val metadataFile = File(backupDir, "$backupId$METADATA_EXT")

            // A backup already exists (from either mode) — do not duplicate.
            if (backupFile.exists()) {
                Timber.w("$TAG: Image already backed up: $backupId")
                return BlurResult.AlreadyBlurred(backupId)
            }

            // Backup original to app-private storage.
            originalFile.copyTo(backupFile, overwrite = true)
            Timber.d("$TAG: Original backed up (copy-only) to: ${backupFile.absolutePath}")

            // Save metadata marking that no blur was applied.
            val metadata = ImageMetadata(
                backupId = backupId,
                originalPath = imagePath,
                category = category,
                confidence = confidence,
                timestamp = System.currentTimeMillis(),
                originalSize = originalFile.length(),
                originalName = originalFile.name,
                blurApplied = false
            )
            saveMetadata(metadataFile, metadata)

            Timber.i("$TAG: Copy-only backup complete. Backup ID: $backupId")
            return BlurResult.Success(backupId, metadata)

        } catch (e: Exception) {
            Timber.e(e, "$TAG: Error backing up image: $imagePath")
            return BlurResult.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * Retroactively blur every backup that was recorded copy-only (blurApplied=false).
     *
     * Called when Maximum Protection is turned ON: images flagged while it was OFF still have
     * their untouched original in the gallery, so blur them now. Idempotent — only entries with
     * blurApplied=false are touched, so it is safe to call from multiple triggers. Reuses the
     * existing backup (never creates a new copy) and sends no alerts. If an original was deleted
     * or moved in the meantime, that entry is skipped without error.
     *
     * @return the number of images newly blurred.
     */
    fun applyBlurToUnblurredBackups(): Int {
        var blurredCount = 0

        for (metadata in getPendingReviews()) {
            if (metadata.blurApplied) continue

            val originalFile = File(metadata.originalPath)
            if (!originalFile.exists() || !originalFile.canRead()) {
                Timber.w("$TAG: Retro-blur skip (original missing/unreadable): ${metadata.originalPath}")
                continue
            }

            try {
                val backupFile = File(backupDir, "${metadata.backupId}.jpg")
                val metadataFile = File(backupDir, "${metadata.backupId}$METADATA_EXT")

                // The existing backup is the recovery source, so it must still be intact before the
                // gallery file is opened for writing. If it is not, leave the image alone.
                if (!backupFile.exists() || backupFile.length() <= 0L) {
                    Timber.w("$TAG: Retro-blur skip (backup missing): ${metadata.originalPath}")
                    continue
                }

                val notBlurredReason = writeBlurredToGallery(metadata.originalPath, backupFile)
                if (notBlurredReason != null) {
                    // Record why, so the parent sees a reason instead of a silently unblurred image,
                    // and so the next run does not retry a folder that cannot be written at all.
                    saveMetadata(metadataFile, metadata.copy(notBlurredReason = notBlurredReason))
                    Timber.w("$TAG: Retro-blur could not write ($notBlurredReason): ${metadata.originalPath}")
                    continue
                }

                refreshMediaStore(metadata.originalPath)

                // Mark this backup as now-blurred so it is not processed again.
                saveMetadata(metadataFile, metadata.copy(blurApplied = true, notBlurredReason = null))

                blurredCount++
                Timber.i("$TAG: Retroactively blurred: ${metadata.originalPath}")
            } catch (e: Exception) {
                Timber.e(e, "$TAG: Error retro-blurring: ${metadata.originalPath}")
            }
        }

        if (blurredCount > 0) Timber.i("$TAG: Retroactive blur complete. Blurred $blurredCount image(s)")
        return blurredCount
    }

    /**
     * Restore original image from backup (called when parent approves).
     *
     * @param backupId The backup ID returned from blurImage()
     * @return true if restored successfully
     */
    fun restoreOriginal(backupId: String): Boolean {
        Timber.i("$TAG: Restoring original for backup: $backupId")

        val backupFile = File(backupDir, "$backupId.jpg")
        val metadataFile = File(backupDir, "$backupId$METADATA_EXT")

        if (!backupFile.exists()) {
            Timber.e("$TAG: Backup file not found: $backupId")
            return false
        }

        val metadata = loadMetadata(metadataFile)
        if (metadata == null) {
            Timber.e("$TAG: Metadata not found for: $backupId")
            return false
        }

        // A copy-only backup never altered the gallery image, so there is nothing to write back —
        // approving it just means dropping the private copy.
        if (!metadata.blurApplied) {
            backupFile.delete()
            metadataFile.delete()
            Timber.i("$TAG: Copy-only backup discarded on approval: ${metadata.originalPath}")
            return true
        }

        return try {
            // Write the original back through the parent-granted folder access.
            val result = mediaWriteAccess.write(metadata.originalPath) { out ->
                backupFile.inputStream().use { input -> input.copyTo(out) }
            }

            if (result !is MediaWriteAccess.WriteResult.Success) {
                // Keep the backup. Losing it here would make the original unrecoverable, which is a
                // far worse outcome than asking the parent to restore folder access and try again.
                Timber.e("$TAG: Could not restore original ($result), keeping the backup: $backupId")
                return false
            }

            // Only now is it safe to drop the backup.
            backupFile.delete()
            metadataFile.delete()

            // Refresh MediaStore
            refreshMediaStore(metadata.originalPath)

            Timber.i("$TAG: Original restored successfully: ${metadata.originalPath}")
            true
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Error restoring original: $backupId")
            false
        }
    }

    /** A parent decision on a reviewed image. */
    enum class ReviewAction { APPROVE, REJECT }

    /**
     * Why [action] cannot be carried out on [backupId] right now, or null when it can.
     *
     * Lets the review screen tell the parent what to fix *before* they tap, instead of failing after.
     * Approving a copy-only backup needs no folder access at all — the gallery image was never
     * altered, so approval just drops the private copy — whereas rejecting always has to remove the
     * image from the device.
     */
    fun reviewBlockedReason(backupId: String, action: ReviewAction): String? {
        val metadata = getMetadata(backupId) ?: return null

        if (action == ReviewAction.APPROVE && !metadata.blurApplied) return null

        return when (mediaWriteAccess.grantStateForPath(metadata.originalPath)) {
            MediaWriteAccess.GrantState.GRANTED -> null
            MediaWriteAccess.GrantState.REVOKED -> REASON_FOLDER_ACCESS_REVOKED
            MediaWriteAccess.GrantState.UNGRANTABLE -> REASON_FOLDER_NOT_GRANTABLE
            MediaWriteAccess.GrantState.NOT_REQUESTED -> REASON_NO_FOLDER_ACCESS
        }
    }

    /**
     * Delete both blurred and original (called when parent rejects).
     *
     * @param backupId The backup ID returned from blurImage()
     * @return true if deleted successfully
     */
    fun deleteImage(backupId: String): Boolean {
        Timber.i("$TAG: Deleting image for backup: $backupId")

        val backupFile = File(backupDir, "$backupId.jpg")
        val metadataFile = File(backupDir, "$backupId$METADATA_EXT")

        val metadata = loadMetadata(metadataFile)

        return try {
            // Remove the image from the device through the parent-granted folder access. An image
            // that has already gone counts as success — the desired end state holds.
            metadata?.let {
                val result = mediaWriteAccess.deleteDocument(it.originalPath)
                if (result !is MediaWriteAccess.WriteResult.Success) {
                    // Keep both files. Dropping the backup while the image is still on the device
                    // would leave the child's copy in place with no way to act on it.
                    Timber.e("$TAG: Could not delete the device image ($result), keeping the backup: $backupId")
                    return false
                }
                removeFromMediaStore(it.originalPath)
            }

            // Delete backup
            backupFile.delete()
            metadataFile.delete()

            Timber.i("$TAG: Image deleted successfully: $backupId")
            true
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Error deleting image: $backupId")
            false
        }
    }

    /**
     * Get list of all pending reviews (blurred images waiting for parent decision).
     */
    fun getPendingReviews(): List<ImageMetadata> {
        val metadataFiles = backupDir.listFiles { file ->
            file.name.endsWith(METADATA_EXT)
        } ?: return emptyList()

        return metadataFiles.mapNotNull { loadMetadata(it) }
            .sortedByDescending { it.timestamp }
    }

    /**
     * Get metadata for a specific backup.
     */
    fun getMetadata(backupId: String): ImageMetadata? {
        val metadataFile = File(backupDir, "$backupId$METADATA_EXT")
        return loadMetadata(metadataFile)
    }

    /**
     * Check if an image has already been handled as a violation (i.e. a backup exists).
     *
     * Note: since copy-only mode was introduced this means "a backup exists" — it no longer
     * implies the gallery file itself is blurred. Callers use it as an "already processed"
     * guard, which remains correct.
     */
    fun isImageBlurred(imagePath: String): Boolean {
        val backupId = generateBackupId(imagePath)
        return File(backupDir, "$backupId.jpg").exists()
    }

    /**
     * Get the backup ID for an image path.
     */
    fun getBackupId(imagePath: String): String {
        return generateBackupId(imagePath)
    }

    /**
     * Create a heavily blurred bitmap from an image file.
     * Uses multiple blur passes and downscaling for maximum blur effect.
     */
    @Suppress("DEPRECATION")
    private fun createBlurredBitmap(imagePath: String): Bitmap? {
        return try {
            // Load and downscale the image first
            val options = BitmapFactory.Options().apply {
                inSampleSize = DOWNSCALE_FACTOR
            }
            var bitmap = BitmapFactory.decodeFile(imagePath, options)
                ?: return null

            // Ensure bitmap is mutable and in correct format
            bitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)

            // Apply multiple blur passes for intense blur
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                // Use RenderScript for older devices
                bitmap = applyRenderScriptBlur(bitmap)
            } else {
                // Use toolkit blur for Android 12+
                bitmap = applyStackBlur(bitmap)
            }

            // Stamp the "Protected by Hareth" banner over the blur
            drawBanner(bitmap)

            bitmap
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Error creating blurred bitmap")
            null
        }
    }

    /**
     * Draw the "Protected by Hareth" banner across the bottom of the blurred bitmap.
     * Sizes are relative to the bitmap width so the banner reads at any scale.
     */
    private fun drawBanner(bitmap: Bitmap) {
        val canvas = Canvas(bitmap)
        val width = bitmap.width.toFloat()
        val height = bitmap.height.toFloat()

        val textSize = width * 0.06f
        val barHeight = textSize * 1.8f
        val barTop = height - barHeight

        // Translucent bar so the text stays legible over any blurred background
        val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(150, 0, 0, 0)
        }
        canvas.drawRect(0f, barTop, width, height, barPaint)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            this.textSize = textSize
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
            setShadowLayer(textSize * 0.12f, 0f, 0f, Color.BLACK)
        }
        // Vertically center the text within the bar
        val baseline = barTop + barHeight / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(BANNER_TEXT, width / 2f, baseline, textPaint)
    }

    /**
     * Apply blur using RenderScript (for Android < 12).
     */
    @Suppress("DEPRECATION")
    private fun applyRenderScriptBlur(input: Bitmap): Bitmap {
        var bitmap = input

        try {
            val rs = RenderScript.create(context)

            repeat(BLUR_PASSES) {
                val inputAlloc = Allocation.createFromBitmap(rs, bitmap)
                val outputAlloc = Allocation.createTyped(rs, inputAlloc.type)

                val blurScript = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs))
                blurScript.setRadius(BLUR_RADIUS)
                blurScript.setInput(inputAlloc)
                blurScript.forEach(outputAlloc)

                outputAlloc.copyTo(bitmap)

                inputAlloc.destroy()
                outputAlloc.destroy()
                blurScript.destroy()
            }

            rs.destroy()
        } catch (e: Exception) {
            Timber.e(e, "$TAG: RenderScript blur failed, using fallback")
            bitmap = applyStackBlur(bitmap)
        }

        return bitmap
    }

    /**
     * Apply stack blur (fallback for Android 12+ where RenderScript is deprecated).
     * This is a CPU-based blur algorithm.
     */
    private fun applyStackBlur(source: Bitmap): Bitmap {
        val radius = 25
        var bitmap = source

        repeat(BLUR_PASSES) {
            bitmap = stackBlur(bitmap, radius)
        }

        return bitmap
    }

    /**
     * Stack blur implementation (CPU-based).
     */
    private fun stackBlur(sentBitmap: Bitmap, radius: Int): Bitmap {
        val bitmap = sentBitmap.copy(sentBitmap.config ?: Bitmap.Config.ARGB_8888, true)

        val w = bitmap.width
        val h = bitmap.height

        val pix = IntArray(w * h)
        bitmap.getPixels(pix, 0, w, 0, 0, w, h)

        val wm = w - 1
        val hm = h - 1
        val wh = w * h
        val div = radius + radius + 1

        val r = IntArray(wh)
        val g = IntArray(wh)
        val b = IntArray(wh)
        var rsum: Int
        var gsum: Int
        var bsum: Int
        var x: Int
        var y: Int
        var i: Int
        var p: Int
        var yp: Int
        var yi: Int
        var yw: Int
        val vmin = IntArray(maxOf(w, h))

        var divsum = (div + 1) shr 1
        divsum *= divsum
        val dv = IntArray(256 * divsum)
        i = 0
        while (i < 256 * divsum) {
            dv[i] = i / divsum
            i++
        }

        yi = 0
        yw = 0

        val stack = Array(div) { IntArray(3) }
        var stackpointer: Int
        var stackstart: Int
        var sir: IntArray
        var rbs: Int
        val r1 = radius + 1
        var routsum: Int
        var goutsum: Int
        var boutsum: Int
        var rinsum: Int
        var ginsum: Int
        var binsum: Int

        y = 0
        while (y < h) {
            bsum = 0
            gsum = 0
            rsum = 0
            boutsum = 0
            goutsum = 0
            routsum = 0
            binsum = 0
            ginsum = 0
            rinsum = 0
            i = -radius
            while (i <= radius) {
                p = pix[yi + minOf(wm, maxOf(i, 0))]
                sir = stack[i + radius]
                sir[0] = (p and 0xff0000) shr 16
                sir[1] = (p and 0x00ff00) shr 8
                sir[2] = p and 0x0000ff
                rbs = r1 - kotlin.math.abs(i)
                rsum += sir[0] * rbs
                gsum += sir[1] * rbs
                bsum += sir[2] * rbs
                if (i > 0) {
                    rinsum += sir[0]
                    ginsum += sir[1]
                    binsum += sir[2]
                } else {
                    routsum += sir[0]
                    goutsum += sir[1]
                    boutsum += sir[2]
                }
                i++
            }
            stackpointer = radius

            x = 0
            while (x < w) {
                r[yi] = dv[rsum]
                g[yi] = dv[gsum]
                b[yi] = dv[bsum]

                rsum -= routsum
                gsum -= goutsum
                bsum -= boutsum

                stackstart = stackpointer - radius + div
                sir = stack[stackstart % div]

                routsum -= sir[0]
                goutsum -= sir[1]
                boutsum -= sir[2]

                if (y == 0) {
                    vmin[x] = minOf(x + radius + 1, wm)
                }
                p = pix[yw + vmin[x]]

                sir[0] = (p and 0xff0000) shr 16
                sir[1] = (p and 0x00ff00) shr 8
                sir[2] = p and 0x0000ff

                rinsum += sir[0]
                ginsum += sir[1]
                binsum += sir[2]

                rsum += rinsum
                gsum += ginsum
                bsum += binsum

                stackpointer = (stackpointer + 1) % div
                sir = stack[stackpointer % div]

                routsum += sir[0]
                goutsum += sir[1]
                boutsum += sir[2]

                rinsum -= sir[0]
                ginsum -= sir[1]
                binsum -= sir[2]

                yi++
                x++
            }
            yw += w
            y++
        }

        x = 0
        while (x < w) {
            bsum = 0
            gsum = 0
            rsum = 0
            boutsum = 0
            goutsum = 0
            routsum = 0
            binsum = 0
            ginsum = 0
            rinsum = 0
            yp = -radius * w
            i = -radius
            while (i <= radius) {
                yi = maxOf(0, yp) + x

                sir = stack[i + radius]

                sir[0] = r[yi]
                sir[1] = g[yi]
                sir[2] = b[yi]

                rbs = r1 - kotlin.math.abs(i)

                rsum += r[yi] * rbs
                gsum += g[yi] * rbs
                bsum += b[yi] * rbs

                if (i > 0) {
                    rinsum += sir[0]
                    ginsum += sir[1]
                    binsum += sir[2]
                } else {
                    routsum += sir[0]
                    goutsum += sir[1]
                    boutsum += sir[2]
                }

                if (i < hm) {
                    yp += w
                }
                i++
            }
            yi = x
            stackpointer = radius
            y = 0
            while (y < h) {
                pix[yi] = (0xff000000.toInt() and pix[yi]) or (dv[rsum] shl 16) or (dv[gsum] shl 8) or dv[bsum]

                rsum -= routsum
                gsum -= goutsum
                bsum -= boutsum

                stackstart = stackpointer - radius + div
                sir = stack[stackstart % div]

                routsum -= sir[0]
                goutsum -= sir[1]
                boutsum -= sir[2]

                if (x == 0) {
                    vmin[y] = minOf(y + r1, hm) * w
                }
                p = x + vmin[y]

                sir[0] = r[p]
                sir[1] = g[p]
                sir[2] = b[p]

                rinsum += sir[0]
                ginsum += sir[1]
                binsum += sir[2]

                rsum += rinsum
                gsum += ginsum
                bsum += binsum

                stackpointer = (stackpointer + 1) % div
                sir = stack[stackpointer]

                routsum += sir[0]
                goutsum += sir[1]
                boutsum += sir[2]

                rinsum -= sir[0]
                ginsum -= sir[1]
                binsum -= sir[2]

                yi += w
                y++
            }
            x++
        }

        bitmap.setPixels(pix, 0, w, 0, 0, w, h)
        return bitmap
    }

    /**
     * Blur the image at [imagePath] and write it over the gallery copy through the parent-granted
     * folder access.
     *
     * @param backupFile the already-verified recovery source, used if the write fails after the
     *   document has been opened (and therefore truncated).
     * @return null when the gallery copy now holds a verified blurred image, otherwise the reason it
     *   does not. A non-null return always means the original is still intact.
     */
    private fun writeBlurredToGallery(imagePath: String, backupFile: File): String? {
        // Nothing is opened, and so nothing can be damaged, unless access is actually held.
        when (val access = mediaWriteAccess.grantStateForPath(imagePath)) {
            MediaWriteAccess.GrantState.GRANTED -> Unit
            MediaWriteAccess.GrantState.REVOKED -> return REASON_FOLDER_ACCESS_REVOKED
            MediaWriteAccess.GrantState.UNGRANTABLE -> return REASON_FOLDER_NOT_GRANTABLE
            MediaWriteAccess.GrantState.NOT_REQUESTED -> {
                Timber.d("$TAG: No folder access for $imagePath ($access)")
                return REASON_NO_FOLDER_ACCESS
            }
        }

        val blurredBitmap = createBlurredBitmap(imagePath)
        if (blurredBitmap == null) {
            Timber.e("$TAG: Failed to create blurred bitmap")
            return REASON_BLUR_FAILED
        }

        val result = try {
            mediaWriteAccess.write(imagePath) { out ->
                blurredBitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
        } finally {
            blurredBitmap.recycle()
        }

        return when (result) {
            is MediaWriteAccess.WriteResult.Success -> null

            // These two never opened the document, so the original was never touched.
            is MediaWriteAccess.WriteResult.NoAccess -> when (result.state) {
                MediaWriteAccess.GrantState.REVOKED -> REASON_FOLDER_ACCESS_REVOKED
                MediaWriteAccess.GrantState.UNGRANTABLE -> REASON_FOLDER_NOT_GRANTABLE
                else -> REASON_NO_FOLDER_ACCESS
            }
            is MediaWriteAccess.WriteResult.DocumentMissing -> REASON_IMAGE_MISSING

            // These two may have truncated the gallery file. Put the original back.
            is MediaWriteAccess.WriteResult.Failed -> {
                Timber.e("$TAG: Write failed (${result.message}), restoring the original")
                restoreFromBackup(imagePath, backupFile)
                REASON_WRITE_FAILED
            }
            is MediaWriteAccess.WriteResult.VerificationFailed -> {
                Timber.e(
                    "$TAG: Write verification failed (wrote ${result.expectedBytes}, " +
                        "found ${result.actualBytes}), restoring the original"
                )
                restoreFromBackup(imagePath, backupFile)
                REASON_WRITE_FAILED
            }
        }
    }

    /**
     * Put the original bytes back over a gallery file whose write failed part-way through.
     *
     * If this also fails the original is not lost — the verified backup still holds it and the parent
     * can restore from the review screen — but it is logged at error level because the child is left
     * looking at a damaged file until they do.
     */
    private fun restoreFromBackup(imagePath: String, backupFile: File) {
        val result = mediaWriteAccess.write(imagePath) { out ->
            backupFile.inputStream().use { input -> input.copyTo(out) }
        }
        if (result is MediaWriteAccess.WriteResult.Success) {
            refreshMediaStore(imagePath)
            Timber.i("$TAG: Original restored after a failed write: $imagePath")
        } else {
            Timber.e("$TAG: Could not restore the original after a failed write ($result). " +
                "The backup still holds it: ${backupFile.name}")
        }
    }

    /**
     * Whether a freshly written backup is a usable recovery source.
     *
     * Size comparison rather than a hash: this runs on every violation, the copy was made moments
     * ago from the same file, and the failure being guarded against is a truncated or empty copy.
     */
    private fun isBackupIntact(backupFile: File, originalFile: File): Boolean =
        backupFile.exists() &&
            backupFile.length() > 0L &&
            backupFile.length() == originalFile.length()

    /**
     * Refresh MediaStore so the gallery thumbnail matches the bytes on disk.
     *
     * Failures are swallowed deliberately. This is a cosmetic follow-up to an operation that has
     * already succeeded, and it is called from the success path of blurring, restoring and the
     * retroactive pass. Letting it throw would propagate into those callers' catch blocks and report
     * a completed restore as a failure — telling the parent their approval did not work when the
     * original is already back in place.
     */
    private fun refreshMediaStore(imagePath: String) {
        try {
            MediaScannerConnection.scanFile(
                context,
                arrayOf(imagePath),
                arrayOf("image/jpeg"),
                null
            )
        } catch (e: Exception) {
            Timber.w(e, "$TAG: Could not refresh the gallery for $imagePath; the file itself is correct")
        }
    }

    /**
     * Remove image from MediaStore.
     */
    private fun removeFromMediaStore(imagePath: String) {
        try {
            context.contentResolver.delete(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                "${MediaStore.Images.Media.DATA} = ?",
                arrayOf(imagePath)
            )
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Error removing from MediaStore")
        }
    }

    /**
     * Generate a unique backup ID based on file path hash.
     */
    private fun generateBackupId(imagePath: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(imagePath.toByteArray())
        return hash.take(16).joinToString("") { "%02x".format(it) }
    }

    /**
     * Save metadata to file.
     */
    @androidx.annotation.VisibleForTesting
    internal fun saveMetadata(file: File, metadata: ImageMetadata) {
        file.writeText(buildString {
            appendLine("backupId=${metadata.backupId}")
            appendLine("originalPath=${metadata.originalPath}")
            appendLine("category=${metadata.category}")
            appendLine("confidence=${metadata.confidence}")
            appendLine("timestamp=${metadata.timestamp}")
            appendLine("originalSize=${metadata.originalSize}")
            appendLine("originalName=${metadata.originalName}")
            appendLine("blurApplied=${metadata.blurApplied}")
            // Written only when there is a reason, so a deliberately copy-only backup stays
            // distinguishable from one that wanted blurring and could not get it.
            metadata.notBlurredReason?.let { appendLine("notBlurredReason=$it") }
        })
    }

    /**
     * Load metadata from file.
     */
    @androidx.annotation.VisibleForTesting
    internal fun loadMetadata(file: File): ImageMetadata? {
        if (!file.exists()) return null

        return try {
            val lines = file.readLines()
            val map = lines.associate {
                val (key, value) = it.split("=", limit = 2)
                key to value
            }

            ImageMetadata(
                backupId = map["backupId"] ?: return null,
                originalPath = map["originalPath"] ?: return null,
                category = map["category"] ?: "unknown",
                confidence = map["confidence"]?.toFloatOrNull() ?: 0f,
                timestamp = map["timestamp"]?.toLongOrNull() ?: 0L,
                originalSize = map["originalSize"]?.toLongOrNull() ?: 0L,
                originalName = map["originalName"] ?: "",
                // Missing key ⇒ legacy file from the always-blur era ⇒ treat as blurred.
                blurApplied = map["blurApplied"]?.toBooleanStrictOrNull() ?: true,
                // Absent on every metadata file written before feature 016, which reads as "no
                // reason recorded" rather than as an error. Those backups stay fully usable.
                notBlurredReason = map["notBlurredReason"]
            )
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Error loading metadata")
            null
        }
    }

    /**
     * Metadata for a backed-up violation image.
     *
     * [blurApplied] distinguishes the two protection modes: true = the gallery original was
     * replaced with a blurred version (Maximum Protection ON); false = copy-only, the gallery
     * original is untouched (Maximum Protection OFF). Legacy metadata files (written before
     * copy-only existed) have no such field and are parsed as true — they were always blurred.
     *
     * [notBlurredReason] separates the two ways [blurApplied] can be false. Null means copy-only was
     * the intent — Maximum Protection was off, and nothing went wrong. A reason code means blurring
     * was wanted and could not happen: no folder access, access revoked, a folder the platform will
     * not grant such as `Download`, the image gone, or a failed write. It exists so the parent is
     * given an explanation instead of an image that is quietly still visible.
     */
    data class ImageMetadata(
        val backupId: String,
        val originalPath: String,
        val category: String,
        val confidence: Float,
        val timestamp: Long,
        val originalSize: Long,
        val originalName: String,
        val blurApplied: Boolean = true,
        val notBlurredReason: String? = null
    )

    /**
     * Result of a violation-processing operation ([blurImage] or [backupOnly]).
     */
    sealed class BlurResult {
        data class Success(val backupId: String, val metadata: ImageMetadata) : BlurResult()
        /** A backup already exists for this image (from either mode); nothing was written. */
        data class AlreadyBlurred(val backupId: String) : BlurResult()
        data class Error(val message: String) : BlurResult()
    }
}
