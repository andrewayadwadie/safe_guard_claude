package com.safeguard.parentalcontrol.util

import android.util.LruCache
import timber.log.Timber
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Privacy-focused text hasher for alert deduplication.
 *
 * Purpose:
 * - Prevent duplicate alerts for the same inappropriate content
 * - Ensure no actual text content is stored or transmitted
 * - Use cryptographic hashing for privacy protection
 *
 * Security:
 * - Uses SHA-256 for strong collision resistance
 * - Hashes are stored only in memory (LRU cache)
 * - Cache is cleared when app is terminated
 * - No plaintext content is ever persisted
 *
 * Usage:
 * - Before creating an alert, check if similar content was recently flagged
 * - This reduces alert spam for repeated content (e.g., child typing same word multiple times)
 */
@Singleton
class TextHasher @Inject constructor() {

    companion object {
        private const val TAG = "TextHasher"

        // Maximum number of hashes to keep in memory
        private const val MAX_CACHE_SIZE = 500

        // Time window for considering content as "recent" (5 minutes).
        // Public so the alert path can report the exact window when it suppresses a duplicate.
        const val RECENT_WINDOW_MS = 5 * 60 * 1000L

        // Hash algorithm
        private const val HASH_ALGORITHM = "SHA-256"
    }

    // LRU cache: hash -> timestamp of when it was first seen
    private val recentHashes = LruCache<String, Long>(MAX_CACHE_SIZE)

    // Lock for thread-safe access
    private val lock = Any()

    /**
     * Hash text content using SHA-256.
     *
     * The hash includes the package name to differentiate between
     * same content in different apps.
     *
     * @param text The text to hash
     * @param packageName The app package where the text was found
     * @return SHA-256 hash of the content
     */
    fun hashContent(text: String, packageName: String): String {
        val combined = "$packageName:${normalizeText(text)}"
        return sha256(combined)
    }

    /**
     * Check if this content was recently flagged.
     *
     * @param text The text to check
     * @param packageName The app package
     * @return true if similar content was flagged within the recent threshold
     */
    fun isRecentlyFlagged(text: String, packageName: String): Boolean {
        val hash = hashContent(text, packageName)
        val now = System.currentTimeMillis()

        synchronized(lock) {
            val lastSeen = recentHashes.get(hash) ?: return false
            val isRecent = (now - lastSeen) < RECENT_WINDOW_MS

            if (isRecent) {
                Timber.d("$TAG: Content hash $hash was recently flagged (${(now - lastSeen) / 1000}s ago)")
            }

            return isRecent
        }
    }

    /**
     * Mark content as flagged by recording its hash.
     *
     * @param text The flagged text
     * @param packageName The app package
     */
    fun markAsFlagged(text: String, packageName: String) {
        val hash = hashContent(text, packageName)
        val now = System.currentTimeMillis()

        synchronized(lock) {
            recentHashes.put(hash, now)
            Timber.d("$TAG: Marked content hash as flagged: ${hash.take(16)}...")
        }
    }

    /**
     * Check and mark in one operation.
     * Returns true if the content should be processed (not recently flagged).
     *
     * @param text The text to check
     * @param packageName The app package
     * @return true if this is new content that should generate an alert
     */
    fun shouldProcessContent(text: String, packageName: String): Boolean {
        if (isRecentlyFlagged(text, packageName)) {
            return false
        }
        markAsFlagged(text, packageName)
        return true
    }

    /**
     * Clear old entries from the cache.
     * This is called periodically to free memory.
     */
    fun cleanupOldEntries() {
        val now = System.currentTimeMillis()
        val threshold = now - RECENT_WINDOW_MS

        synchronized(lock) {
            // Get all entries and filter out old ones
            val snapshot = recentHashes.snapshot()
            var removed = 0

            snapshot.forEach { (hash, timestamp) ->
                if (timestamp < threshold) {
                    recentHashes.remove(hash)
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
            val size = recentHashes.size()
            recentHashes.evictAll()
            Timber.d("$TAG: Cleared $size hash entries from cache")
        }
    }

    /**
     * Get current cache statistics for debugging.
     */
    fun getCacheStats(): CacheStats {
        synchronized(lock) {
            return CacheStats(
                size = recentHashes.size(),
                maxSize = MAX_CACHE_SIZE,
                hitCount = recentHashes.hitCount(),
                missCount = recentHashes.missCount()
            )
        }
    }

    /**
     * Normalize text for consistent hashing.
     * - Lowercase
     * - Trim whitespace
     * - Collapse multiple spaces
     */
    private fun normalizeText(text: String): String {
        return text.lowercase()
            .trim()
            .replace(Regex("\\s+"), " ")
    }

    /**
     * Compute SHA-256 hash of a string.
     */
    private fun sha256(input: String): String {
        return try {
            val digest = MessageDigest.getInstance(HASH_ALGORITHM)
            val hashBytes = digest.digest(input.toByteArray(Charsets.UTF_8))
            hashBytes.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to compute SHA-256 hash")
            // Fallback to simple hashCode if SHA-256 fails (shouldn't happen)
            input.hashCode().toString()
        }
    }

    /**
     * Cache statistics for monitoring.
     */
    data class CacheStats(
        val size: Int,
        val maxSize: Int,
        val hitCount: Int,
        val missCount: Int
    ) {
        val hitRate: Float
            get() = if (hitCount + missCount > 0) {
                hitCount.toFloat() / (hitCount + missCount)
            } else {
                0f
            }
    }
}
