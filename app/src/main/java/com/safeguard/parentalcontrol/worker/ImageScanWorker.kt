package com.safeguard.parentalcontrol.worker

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.safeguard.parentalcontrol.data.repository.AlertRepository
import com.safeguard.parentalcontrol.ml.ContentClassifier
import com.safeguard.parentalcontrol.util.Constants
import com.safeguard.parentalcontrol.util.ImageBlurManager
import com.safeguard.parentalcontrol.util.ImageHasher
import com.safeguard.parentalcontrol.util.PreferencesManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker for periodic image scanning via MediaStore.
 *
 * This complements MediaFileObserver by:
 * - Catching images that FileObserver might miss (e.g., during app restart)
 * - Scanning images added since the last scan
 * - Providing catch-up scanning for newly installed app
 *
 * Runs every 30 minutes (configurable) and only on child devices.
 * Uses MediaStore to query for recently added images.
 */
@HiltWorker
class ImageScanWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val contentClassifier: ContentClassifier,
    private val alertRepository: AlertRepository,
    private val imageHasher: ImageHasher,
    private val imageBlurManager: ImageBlurManager,
    private val preferencesManager: PreferencesManager
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "ImageScanWorker"
        private const val WORK_NAME = "image_scan_work"

        // Maximum number of images to scan per run (for battery)
        private const val MAX_IMAGES_PER_SCAN = 50

        // Minimum file size to consider
        private const val MIN_FILE_SIZE_BYTES = 1024L // 1KB

        // Maximum file size to analyze
        private const val MAX_FILE_SIZE_BYTES = 50 * 1024 * 1024L // 50MB

        /**
         * Enqueue periodic image scanning.
         */
        fun enqueue(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true) // Don't scan when battery is low
                .build()

            val request = PeriodicWorkRequestBuilder<ImageScanWorker>(
                Constants.IMAGE_SCAN_INTERVAL_MINUTES, TimeUnit.MINUTES,
                5, TimeUnit.MINUTES // Flex interval
            )
                .setConstraints(constraints)
                .addTag(Constants.WORK_TAG_IMAGE_SCAN)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request
                )

            Timber.d("$TAG: Enqueued periodic work (every ${Constants.IMAGE_SCAN_INTERVAL_MINUTES} minutes)")
        }

        /**
         * Cancel image scanning.
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Timber.d("$TAG: Cancelled periodic work")
        }
    }

    override suspend fun doWork(): Result {
        Timber.d("$TAG: Starting image scan")

        // Only scan on child devices
        if (preferencesManager.isParent) {
            Timber.d("$TAG: Skipping for parent device")
            return Result.success()
        }

        // Check if device is registered
        if (!preferencesManager.isDeviceRegistered) {
            Timber.d("$TAG: Device not registered, skipping")
            return Result.success()
        }

        try {
            // Safety net: if Maximum Protection is ON, blur any images that were flagged
            // copy-only while it was OFF but not yet retro-blurred (e.g. process died right
            // after the toggle). Idempotent — only unblurred backups are touched.
            if (preferencesManager.isMaximumProtectionEnabled) {
                val retroBlurred = imageBlurManager.applyBlurToUnblurredBackups()
                if (retroBlurred > 0) Timber.i("$TAG: Retroactively blurred $retroBlurred image(s)")
            }

            val lastScanTime = preferencesManager.lastImageScanTime
            val scanStartTime = System.currentTimeMillis()

            Timber.d("$TAG: Last scan time: $lastScanTime, current: $scanStartTime")

            // Query MediaStore for images added since last scan
            val newImages = queryRecentImages(lastScanTime)

            Timber.i("$TAG: Found ${newImages.size} images to scan")

            var scannedCount = 0
            var flaggedCount = 0

            for (imageInfo in newImages.take(MAX_IMAGES_PER_SCAN)) {
                try {
                    // Check if already scanned
                    if (!imageHasher.shouldProcessImage(imageInfo.path)) {
                        Timber.d("$TAG: Already scanned: ${imageInfo.path}")
                        continue
                    }

                    // Analyze the image
                    val result = contentClassifier.analyzeImage(imageInfo.path)
                    scannedCount++

                    if (result.isFlagged) {
                        Timber.w("$TAG: FLAGGED: ${imageInfo.path} (${result.categories})")

                        // Check if alert already sent
                        if (imageHasher.shouldSendAlert(imageInfo.path)) {
                            val primaryCategory = result.categories.firstOrNull() ?: "nsfw"

                            // Read Maximum Protection fresh for THIS violation, then either
                            // blur (backup + replace gallery) or back up copy-only.
                            val maximumProtection = preferencesManager.isMaximumProtectionEnabled
                            val blurResult = if (maximumProtection) {
                                imageBlurManager.blurImage(
                                    imagePath = imageInfo.path,
                                    category = primaryCategory,
                                    confidence = result.confidence
                                )
                            } else {
                                imageBlurManager.backupOnly(
                                    imagePath = imageInfo.path,
                                    category = primaryCategory,
                                    confidence = result.confidence
                                )
                            }

                            when (blurResult) {
                                is ImageBlurManager.BlurResult.Success -> {
                                    Timber.i("$TAG: Image blurred. Backup ID: ${blurResult.backupId}")
                                }
                                is ImageBlurManager.BlurResult.AlreadyBlurred -> {
                                    Timber.d("$TAG: Image already blurred: ${blurResult.backupId}")
                                }
                                is ImageBlurManager.BlurResult.Error -> {
                                    Timber.e("$TAG: Blur failed: ${blurResult.message}")
                                }
                            }

                            // Send alert to parent (metadata only)
                            alertRepository.createInappropriateImageAlert(
                                category = primaryCategory,
                                confidence = result.confidence,
                                sourceApp = imageInfo.folderName,
                                evidenceBase64 = null
                            )
                            flaggedCount++
                        }
                    }
                } catch (e: Exception) {
                    Timber.e(e, "$TAG: Error scanning image: ${imageInfo.path}")
                }
            }

            // Update last scan time
            preferencesManager.lastImageScanTime = scanStartTime

            // Cleanup old hash entries
            imageHasher.cleanupOldEntries()

            Timber.i("$TAG: Scan complete. Scanned: $scannedCount, Flagged: $flaggedCount")

            return Result.success()
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Error during image scan")
            return Result.retry()
        }
    }

    /**
     * Query MediaStore for images added since the given timestamp.
     */
    private fun queryRecentImages(sinceTimestamp: Long): List<ImageInfo> {
        val images = mutableListOf<ImageInfo>()

        // Convert timestamp to seconds (MediaStore uses seconds)
        val sinceSeconds = sinceTimestamp / 1000

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATA,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME
        )

        val selection = "${MediaStore.Images.Media.DATE_ADDED} > ? AND ${MediaStore.Images.Media.SIZE} > ? AND ${MediaStore.Images.Media.SIZE} < ?"
        val selectionArgs = arrayOf(
            sinceSeconds.toString(),
            MIN_FILE_SIZE_BYTES.toString(),
            MAX_FILE_SIZE_BYTES.toString()
        )

        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        try {
            applicationContext.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
                val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                val bucketColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)

                while (cursor.moveToNext() && images.size < MAX_IMAGES_PER_SCAN * 2) {
                    val id = cursor.getLong(idColumn)
                    val path = cursor.getString(dataColumn) ?: continue
                    val dateAdded = cursor.getLong(dateColumn)
                    val size = cursor.getLong(sizeColumn)
                    val bucket = cursor.getString(bucketColumn) ?: "Unknown"

                    // Build content URI
                    val contentUri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        id
                    )

                    images.add(ImageInfo(
                        id = id,
                        path = path,
                        uri = contentUri.toString(),
                        dateAdded = dateAdded,
                        size = size,
                        folderName = bucket
                    ))
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Error querying MediaStore")
        }

        return images
    }

    /**
     * Information about an image from MediaStore.
     */
    private data class ImageInfo(
        val id: Long,
        val path: String,
        val uri: String,
        val dateAdded: Long,
        val size: Long,
        val folderName: String
    )
}
