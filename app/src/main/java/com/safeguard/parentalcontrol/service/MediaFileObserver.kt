package com.safeguard.parentalcontrol.service

import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import com.safeguard.parentalcontrol.data.repository.AlertRepository
import com.safeguard.parentalcontrol.ml.ContentClassifier
import com.safeguard.parentalcontrol.util.ImageBlurManager
import com.safeguard.parentalcontrol.util.ImageHasher
import com.safeguard.parentalcontrol.util.PreferencesManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Watches media directories for new images and triggers NSFW analysis.
 *
 * Monitors:
 * - DCIM/Camera - Photos taken with camera
 * - Download - Downloaded files
 * - Pictures/Screenshots - Screenshots (device-dependent)
 * - Pictures - General pictures folder
 *
 * When a new image is detected:
 * 1. Wait for file write to complete (debounce)
 * 2. Check if already scanned (via ImageHasher)
 * 3. Analyze with ContentClassifier
 * 4. Create alert if inappropriate
 *
 * Uses Android's FileObserver API for real-time detection.
 */
@Singleton
class MediaFileObserver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val contentClassifier: ContentClassifier,
    private val alertRepository: AlertRepository,
    private val imageHasher: ImageHasher,
    private val imageBlurManager: ImageBlurManager,
    private val preferencesManager: PreferencesManager
) {
    companion object {
        private const val TAG = "MediaFileObserver"

        // Debounce delay to wait for file write to complete
        private const val DEBOUNCE_DELAY_MS = 1500L

        // Minimum file size to consider (skip empty/corrupt files)
        private const val MIN_FILE_SIZE_BYTES = 1024L // 1KB

        // Maximum file size to analyze (skip very large files for performance)
        private const val MAX_FILE_SIZE_BYTES = 50 * 1024 * 1024L // 50MB

        // Supported image extensions
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")
    }

    private val fileObservers = mutableListOf<FileObserver>()
    private var mediaStoreObserver: ContentObserver? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Track pending files to avoid duplicate processing
    private val pendingFiles = mutableSetOf<String>()
    private val pendingLock = Any()

    // Track last processed image ID to avoid duplicates
    private var lastProcessedImageId: Long = 0

    // Flag to track if observers are active
    @Volatile
    private var isWatching = false

    /**
     * Start watching media directories for new images.
     * Call this from MonitoringService.onCreate() or onStartCommand().
     *
     * Uses two approaches:
     * 1. ContentObserver on MediaStore (reliable on Android 10+)
     * 2. FileObserver on directories (fallback for direct file access)
     */
    fun startWatching() {
        if (isWatching) {
            Timber.d("$TAG: Already watching, skipping")
            return
        }

        Timber.i("$TAG: Starting media observers")

        // Initialize last processed ID to current max
        initializeLastProcessedId()

        // PRIMARY: Register ContentObserver on MediaStore (works on Android 10+)
        registerMediaStoreObserver()

        // FALLBACK: Also use FileObserver for direct file access
        startFileObservers()

        isWatching = true
        Timber.i("$TAG: Media monitoring started (ContentObserver + ${fileObservers.size} FileObservers)")
    }

    /**
     * Initialize last processed ID to avoid scanning old images on startup.
     */
    private fun initializeLastProcessedId() {
        try {
            val projection = arrayOf(MediaStore.Images.Media._ID)
            val sortOrder = "${MediaStore.Images.Media._ID} DESC"

            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                sortOrder
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    lastProcessedImageId = cursor.getLong(0)
                    Timber.d("$TAG: Initialized lastProcessedImageId=$lastProcessedImageId")
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to initialize last processed ID")
        }
    }

    /**
     * Register ContentObserver on MediaStore to detect new images.
     * This is the primary method for Android 10+ with scoped storage.
     */
    private fun registerMediaStoreObserver() {
        try {
            val handler = Handler(Looper.getMainLooper())

            mediaStoreObserver = object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    Timber.d("$TAG: MediaStore changed: $uri")
                    scope.launch {
                        checkForNewImages()
                    }
                }
            }

            context.contentResolver.registerContentObserver(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                true, // notifyForDescendants
                mediaStoreObserver!!
            )

            Timber.i("$TAG: Registered MediaStore ContentObserver")
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to register MediaStore observer")
        }
    }

    /**
     * Check MediaStore for new images since last check.
     */
    private suspend fun checkForNewImages() {
        try {
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DATA,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.SIZE
            )

            // Filter by ID and exclude trashed files on Android 11+
            val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                "${MediaStore.Images.Media._ID} > ? AND ${MediaStore.Images.Media.IS_TRASHED} = 0"
            } else {
                "${MediaStore.Images.Media._ID} > ?"
            }
            val selectionArgs = arrayOf(lastProcessedImageId.toString())
            val sortOrder = "${MediaStore.Images.Media._ID} ASC"

            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val filePath = cursor.getString(dataColumn)
                    val fileName = cursor.getString(nameColumn)
                    val fileSize = cursor.getLong(sizeColumn)

                    Timber.i("$TAG: NEW IMAGE DETECTED via MediaStore: $fileName (id=$id)")

                    // Update last processed ID
                    if (id > lastProcessedImageId) {
                        lastProcessedImageId = id
                    }

                    // Validate and schedule analysis
                    if (filePath != null && isImageFile(fileName)) {
                        if (fileSize in MIN_FILE_SIZE_BYTES..MAX_FILE_SIZE_BYTES) {
                            val sourceFolder = getSourceFolder(filePath)
                            scheduleAnalysis(filePath, sourceFolder)
                        } else {
                            Timber.d("$TAG: Skipping image (size=$fileSize): $fileName")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Error checking for new images")
        }
    }

    /**
     * Extract source folder name from file path.
     */
    private fun getSourceFolder(filePath: String): String {
        return try {
            File(filePath).parentFile?.name ?: "Unknown"
        } catch (e: Exception) {
            "Unknown"
        }
    }

    /**
     * Start FileObserver instances for directories (fallback method).
     */
    private fun startFileObservers() {
        val dirsToWatch = getMediaDirectories()

        for (dir in dirsToWatch) {
            if (dir.exists() && dir.isDirectory) {
                try {
                    val observer = createFileObserver(dir)
                    observer.startWatching()
                    fileObservers.add(observer)
                    Timber.d("$TAG: FileObserver watching: ${dir.absolutePath}")
                } catch (e: Exception) {
                    Timber.e(e, "$TAG: Failed to watch directory: ${dir.absolutePath}")
                }
            } else {
                Timber.d("$TAG: Directory doesn't exist: ${dir.absolutePath}")
            }
        }
    }

    /**
     * Stop all observers.
     * Call this from MonitoringService.onDestroy().
     */
    fun stopWatching() {
        if (!isWatching) return

        Timber.i("$TAG: Stopping media observers")

        // Unregister MediaStore observer
        mediaStoreObserver?.let {
            try {
                context.contentResolver.unregisterContentObserver(it)
                Timber.d("$TAG: Unregistered MediaStore observer")
            } catch (e: Exception) {
                Timber.e(e, "$TAG: Error unregistering MediaStore observer")
            }
        }
        mediaStoreObserver = null

        // Stop file observers
        for (observer in fileObservers) {
            try {
                observer.stopWatching()
            } catch (e: Exception) {
                Timber.e(e, "$TAG: Error stopping FileObserver")
            }
        }
        fileObservers.clear()

        synchronized(pendingLock) {
            pendingFiles.clear()
        }

        isWatching = false
        Timber.i("$TAG: Stopped all media observers")
    }

    /**
     * Get list of media directories to watch.
     */
    private fun getMediaDirectories(): List<File> {
        val dirs = mutableListOf<File>()

        // DCIM/Camera - main camera folder
        val dcim = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
        dirs.add(File(dcim, "Camera"))
        dirs.add(dcim) // Also watch DCIM root for other camera apps

        // Downloads
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        dirs.add(downloads)

        // Pictures (including Screenshots on some devices)
        val pictures = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        dirs.add(pictures)
        dirs.add(File(pictures, "Screenshots"))

        // Screenshots folder (varies by device)
        dirs.add(File(dcim, "Screenshots"))

        // Some devices put screenshots here
        val screenshots = File(Environment.getExternalStorageDirectory(), "Screenshots")
        if (screenshots.exists()) {
            dirs.add(screenshots)
        }

        return dirs.filter { it.exists() || it.parentFile?.exists() == true }
    }

    /**
     * Create a FileObserver for a directory.
     */
    private fun createFileObserver(directory: File): FileObserver {
        // Watch for: CREATE (new file), CLOSE_WRITE (file finished writing), MOVED_TO (file moved into dir)
        val mask = FileObserver.CREATE or FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO

        return object : FileObserver(directory.absolutePath, mask) {
            override fun onEvent(event: Int, path: String?) {
                if (path == null) return

                val fullPath = File(directory, path).absolutePath

                // Only process image files
                if (!isImageFile(path)) return

                when (event) {
                    CREATE -> {
                        // File created, wait for write to complete
                        Timber.d("$TAG: File created: $fullPath")
                    }
                    CLOSE_WRITE, MOVED_TO -> {
                        // File is ready to process
                        Timber.d("$TAG: File ready: $fullPath (event=${eventName(event)})")
                        scheduleAnalysis(fullPath, directory.name)
                    }
                }
            }
        }
    }

    /**
     * Schedule analysis of a new image file.
     * Uses debouncing to handle rapid events and ensure file is fully written.
     */
    private fun scheduleAnalysis(filePath: String, sourceFolder: String) {
        // Check if already pending
        synchronized(pendingLock) {
            if (pendingFiles.contains(filePath)) {
                Timber.d("$TAG: File already pending: $filePath")
                return
            }
            pendingFiles.add(filePath)
        }

        scope.launch {
            try {
                // Debounce - wait for file to be fully written
                delay(DEBOUNCE_DELAY_MS)

                // Validate file
                val file = File(filePath)
                if (!file.exists()) {
                    Timber.d("$TAG: File no longer exists: $filePath")
                    return@launch
                }

                if (file.length() < MIN_FILE_SIZE_BYTES) {
                    Timber.d("$TAG: File too small (${file.length()} bytes): $filePath")
                    return@launch
                }

                if (file.length() > MAX_FILE_SIZE_BYTES) {
                    Timber.w("$TAG: File too large (${file.length()} bytes), skipping: $filePath")
                    return@launch
                }

                // Check if already scanned
                if (!imageHasher.shouldProcessImage(filePath)) {
                    Timber.d("$TAG: Image already scanned, skipping: $filePath")
                    return@launch
                }

                // Analyze the image
                Timber.i("$TAG: Analyzing new image: $filePath")
                analyzeImage(filePath, sourceFolder)

            } catch (e: Exception) {
                Timber.e(e, "$TAG: Error analyzing image: $filePath")
            } finally {
                synchronized(pendingLock) {
                    pendingFiles.remove(filePath)
                }
            }
        }
    }

    /**
     * Analyze an image for inappropriate content.
     * If inappropriate, blurs the image and sends alert to parent.
     */
    private suspend fun analyzeImage(filePath: String, sourceFolder: String) {
        try {
            val result = contentClassifier.analyzeImage(filePath)

            if (result.isFlagged) {
                Timber.w("$TAG: INAPPROPRIATE IMAGE DETECTED: $filePath")
                Timber.w("$TAG: Categories: ${result.categories}, Confidence: ${result.confidence}")

                // Check if we should process this image (not already flagged)
                if (imageHasher.shouldSendAlert(filePath)) {
                    val primaryCategory = result.categories.firstOrNull() ?: "nsfw"

                    // Step 1: Read Maximum Protection fresh for THIS violation, then either
                    // blur (backup + replace gallery) or back up copy-only (gallery untouched).
                    val maximumProtection = preferencesManager.isMaximumProtectionEnabled
                    val blurResult = if (maximumProtection) {
                        imageBlurManager.blurImage(
                            imagePath = filePath,
                            category = primaryCategory,
                            confidence = result.confidence
                        )
                    } else {
                        imageBlurManager.backupOnly(
                            imagePath = filePath,
                            category = primaryCategory,
                            confidence = result.confidence
                        )
                    }

                    when (blurResult) {
                        is ImageBlurManager.BlurResult.Success -> {
                            Timber.i("$TAG: Image blurred successfully. Backup ID: ${blurResult.backupId}")

                            // Step 2: Send alert to parent (metadata only, no image)
                            alertRepository.createInappropriateImageAlert(
                                category = primaryCategory,
                                confidence = result.confidence,
                                sourceApp = sourceFolder,
                                evidenceBase64 = null // No image data - Play Store compliant
                            )

                            Timber.i("$TAG: Alert sent for blurred image in $sourceFolder")
                        }
                        is ImageBlurManager.BlurResult.AlreadyBlurred -> {
                            Timber.d("$TAG: Image already blurred, skipping: ${blurResult.backupId}")
                        }
                        is ImageBlurManager.BlurResult.Error -> {
                            Timber.e("$TAG: Failed to blur image: ${blurResult.message}")
                            // Still send alert even if blur fails
                            alertRepository.createInappropriateImageAlert(
                                category = primaryCategory,
                                confidence = result.confidence,
                                sourceApp = sourceFolder,
                                evidenceBase64 = null
                            )
                        }
                    }
                } else {
                    Timber.d("$TAG: Alert already sent for this image, skipping")
                }
            } else {
                Timber.d("$TAG: Image is safe: $filePath (confidence=${result.confidence})")
            }
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Error analyzing image: $filePath")
        }
    }

    /**
     * Check if a file is an image based on extension.
     * Also filters out trashed/hidden files.
     */
    private fun isImageFile(fileName: String): Boolean {
        // Skip hidden files (includes .trashed-* files in Android's recycle bin)
        if (fileName.startsWith(".")) {
            Timber.d("$TAG: Skipping hidden/trashed file: $fileName")
            return false
        }
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return extension in IMAGE_EXTENSIONS
    }

    /**
     * Get event name for logging.
     */
    private fun eventName(event: Int): String {
        return when (event) {
            FileObserver.CREATE -> "CREATE"
            FileObserver.CLOSE_WRITE -> "CLOSE_WRITE"
            FileObserver.MOVED_TO -> "MOVED_TO"
            else -> "UNKNOWN($event)"
        }
    }

    /**
     * Get status information for debugging.
     */
    fun getStatus(): MediaObserverStatus {
        return MediaObserverStatus(
            isWatching = isWatching,
            mediaStoreObserverActive = mediaStoreObserver != null,
            fileObserverCount = fileObservers.size,
            pendingCount = synchronized(pendingLock) { pendingFiles.size },
            lastProcessedId = lastProcessedImageId,
            hashCacheStats = imageHasher.getCacheStats()
        )
    }

    /**
     * Status information for debugging.
     */
    data class MediaObserverStatus(
        val isWatching: Boolean,
        val mediaStoreObserverActive: Boolean,
        val fileObserverCount: Int,
        val pendingCount: Int,
        val lastProcessedId: Long,
        val hashCacheStats: ImageHasher.CacheStats
    )
}
