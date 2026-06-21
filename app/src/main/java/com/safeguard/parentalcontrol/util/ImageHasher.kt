package com.safeguard.parentalcontrol.util

import android.util.LruCache
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Privacy-focused image hasher for alert deduplication and tracking scanned images.
 *
 * Purpose:
 * - Prevent duplicate alerts for the same inappropriate image
 * - Track which images have been scanned to avoid re-scanning
 * - Ensure no actual image content is stored or transmitted
 * - Use cryptographic hashing for privacy protection
 *
 * Security:
 * - Uses SHA-256 for strong collision resistance
 * - Hashes are stored only in memory (LRU cache) for session
 * - Persistent storage uses DataStore for cross-session tracking
 * - No actual image data is ever persisted
 *
 * Usage:
 * - Before analyzing an image, check if it was already scanned
 * - Before creating an alert, check if similar content was recently flagged
 * - This reduces alert spam and unnecessary re-scanning
 */
@Singleton
class ImageHasher @Inject constructor() {

    companion object {
        private const val TAG = "ImageHasher"

        // Maximum number of hashes to keep in memory
        private const val MAX_CACHE_SIZE = 1000

        // Time window for considering content as "recent" (1 hour for images)
        private const val RECENT_THRESHOLD_MS = 60 * 60 * 1000L

        // Hash algorithm
        private const val HASH_ALGORITHM = "SHA-256"

        // Buffer size for file reading (8KB)
        private const val BUFFER_SIZE = 8192
    }

    // LRU cache: hash -> timestamp of when it was first scanned
    private val scannedHashes = LruCache<String, Long>(MAX_CACHE_SIZE)

    // Separate cache for flagged (inappropriate) images
    private val flaggedHashes = LruCache<String, Long>(MAX_CACHE_SIZE)

    // Lock for thread-safe access
    private val lock = Any()

    /**
     * Compute SHA-256 hash of an image file.
     *
     * @param filePath Path to the image file
     * @return SHA-256 hash of the file content, or null if file cannot be read
     */
    fun hashFile(filePath: String): String? {
        return try {
            val file = File(filePath)
            if (!file.exists() || !file.canRead()) {
                Timber.w("$TAG: Cannot read file: $filePath")
                return null
            }

            val digest = MessageDigest.getInstance(HASH_ALGORITHM)
            FileInputStream(file).use { fis ->
                val buffer = ByteArray(BUFFER_SIZE)
                var bytesRead: Int
                while (fis.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }

            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Error hashing file: $filePath")
            null
        }
    }

    /**
     * Check if an image file was already scanned.
     *
     * @param filePath Path to the image file
     * @return true if the image was scanned within the recent threshold
     */
    fun isAlreadyScanned(filePath: String): Boolean {
        val hash = hashFile(filePath) ?: return false
        val now = System.currentTimeMillis()

        synchronized(lock) {
            val lastScanned = scannedHashes.get(hash) ?: return false
            val isRecent = (now - lastScanned) < RECENT_THRESHOLD_MS

            if (isRecent) {
                Timber.d("$TAG: Image already scanned (${(now - lastScanned) / 1000}s ago): ${hash.take(16)}...")
            }

            return isRecent
        }
    }

    /**
     * Check if an image was already flagged as inappropriate.
     *
     * @param filePath Path to the image file
     * @return true if the image was flagged within the recent threshold
     */
    fun isAlreadyFlagged(filePath: String): Boolean {
        val hash = hashFile(filePath) ?: return false
        val now = System.currentTimeMillis()

        synchronized(lock) {
            val lastFlagged = flaggedHashes.get(hash) ?: return false
            val isRecent = (now - lastFlagged) < RECENT_THRESHOLD_MS

            if (isRecent) {
                Timber.d("$TAG: Image already flagged (${(now - lastFlagged) / 1000}s ago): ${hash.take(16)}...")
            }

            return isRecent
        }
    }

    /**
     * Mark an image as scanned.
     *
     * @param filePath Path to the image file
     * @return The hash of the file, or null if hashing failed
     */
    fun markAsScanned(filePath: String): String? {
        val hash = hashFile(filePath) ?: return null
        val now = System.currentTimeMillis()

        synchronized(lock) {
            scannedHashes.put(hash, now)
            Timber.d("$TAG: Marked image as scanned: ${hash.take(16)}...")
        }

        return hash
    }

    /**
     * Mark an image as flagged (inappropriate).
     *
     * @param filePath Path to the image file
     * @return The hash of the file, or null if hashing failed
     */
    fun markAsFlagged(filePath: String): String? {
        val hash = hashFile(filePath) ?: return null
        val now = System.currentTimeMillis()

        synchronized(lock) {
            flaggedHashes.put(hash, now)
            scannedHashes.put(hash, now) // Also mark as scanned
            Timber.d("$TAG: Marked image as flagged: ${hash.take(16)}...")
        }

        return hash
    }

    /**
     * Mark an image as flagged using its pre-computed hash.
     *
     * @param hash The SHA-256 hash of the image
     */
    fun markAsFlaggedByHash(hash: String) {
        val now = System.currentTimeMillis()

        synchronized(lock) {
            flaggedHashes.put(hash, now)
            scannedHashes.put(hash, now)
            Timber.d("$TAG: Marked hash as flagged: ${hash.take(16)}...")
        }
    }

    /**
     * Check and mark in one operation.
     * Returns true if the image should be processed (not already scanned).
     *
     * @param filePath Path to the image file
     * @return true if this is a new image that should be analyzed
     */
    fun shouldProcessImage(filePath: String): Boolean {
        if (isAlreadyScanned(filePath)) {
            return false
        }
        markAsScanned(filePath)
        return true
    }

    /**
     * Check if we should send an alert for this image.
     * Returns true if the image wasn't already flagged.
     *
     * @param filePath Path to the image file
     * @return true if an alert should be sent
     */
    fun shouldSendAlert(filePath: String): Boolean {
        if (isAlreadyFlagged(filePath)) {
            return false
        }
        markAsFlagged(filePath)
        return true
    }

    /**
     * Clear old entries from both caches.
     */
    fun cleanupOldEntries() {
        val now = System.currentTimeMillis()
        val threshold = now - RECENT_THRESHOLD_MS

        synchronized(lock) {
            var removed = 0

            // Cleanup scanned cache
            val scannedSnapshot = scannedHashes.snapshot()
            scannedSnapshot.forEach { (hash, timestamp) ->
                if (timestamp < threshold) {
                    scannedHashes.remove(hash)
                    removed++
                }
            }

            // Cleanup flagged cache
            val flaggedSnapshot = flaggedHashes.snapshot()
            flaggedSnapshot.forEach { (hash, timestamp) ->
                if (timestamp < threshold) {
                    flaggedHashes.remove(hash)
                    removed++
                }
            }

            if (removed > 0) {
                Timber.d("$TAG: Cleaned up $removed old hash entries")
            }
        }
    }

    /**
     * Clear all cached hashes.
     */
    fun clearCache() {
        synchronized(lock) {
            val scannedSize = scannedHashes.size()
            val flaggedSize = flaggedHashes.size()
            scannedHashes.evictAll()
            flaggedHashes.evictAll()
            Timber.d("$TAG: Cleared $scannedSize scanned + $flaggedSize flagged hash entries")
        }
    }

    /**
     * Get current cache statistics for debugging.
     */
    fun getCacheStats(): CacheStats {
        synchronized(lock) {
            return CacheStats(
                scannedSize = scannedHashes.size(),
                flaggedSize = flaggedHashes.size(),
                maxSize = MAX_CACHE_SIZE,
                scannedHitCount = scannedHashes.hitCount(),
                scannedMissCount = scannedHashes.missCount()
            )
        }
    }

    /**
     * Cache statistics for monitoring.
     */
    data class CacheStats(
        val scannedSize: Int,
        val flaggedSize: Int,
        val maxSize: Int,
        val scannedHitCount: Int,
        val scannedMissCount: Int
    ) {
        val hitRate: Float
            get() = if (scannedHitCount + scannedMissCount > 0) {
                scannedHitCount.toFloat() / (scannedHitCount + scannedMissCount)
            } else {
                0f
            }
    }
}
