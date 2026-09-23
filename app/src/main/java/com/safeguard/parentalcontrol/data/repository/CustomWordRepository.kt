package com.safeguard.parentalcontrol.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.safeguard.parentalcontrol.data.model.*
import com.safeguard.parentalcontrol.data.remote.ApiService
import com.safeguard.parentalcontrol.data.remote.NetworkResult
import com.safeguard.parentalcontrol.data.remote.safeApiCall
import com.safeguard.parentalcontrol.ml.CustomBlacklistWord
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

private val Context.wordListDataStore by preferencesDataStore(name = "word_lists")

/**
 * Repository for managing custom word lists.
 *
 * Features:
 * - Syncs word lists from backend
 * - Caches locally for offline access
 * - Provides word lists to ContentClassifier
 */
@Singleton
class CustomWordRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val apiService: ApiService,
    private val deviceRepository: DeviceRepository
) {
    private val gson = Gson()

    companion object {
        private val KEY_WHITELIST = stringPreferencesKey("whitelist")
        private val KEY_BLACKLIST = stringPreferencesKey("blacklist")
        private val KEY_VERSION = intPreferencesKey("version")
        private val KEY_LAST_SYNC = stringPreferencesKey("last_sync")

        // Sync interval (1 hour)
        private const val SYNC_INTERVAL_MS = 60 * 60 * 1000L
    }

    // ==================== Parent Functions (for managing word lists) ====================

    /**
     * Get all word lists (parent only)
     */
    suspend fun getWordLists(): NetworkResult<CustomWordListResponse> = withContext(Dispatchers.IO) {
        safeApiCall { apiService.getWordLists() }
    }

    /**
     * Add a word to whitelist or blacklist (parent only)
     */
    suspend fun addWord(
        word: String,
        listType: WordListType,
        category: String? = null,
        caseSensitive: Boolean = false,
        wholeWordOnly: Boolean = true
    ): NetworkResult<CustomWordResponse> = withContext(Dispatchers.IO) {
        val request = CustomWordCreateRequest(
            word = word,
            listType = listType,
            category = category,
            caseSensitive = caseSensitive,
            wholeWordOnly = wholeWordOnly
        )
        safeApiCall { apiService.addWord(request) }
    }

    /**
     * Add multiple words at once (parent only)
     */
    suspend fun addWordsBulk(
        words: List<CustomWordCreateRequest>
    ): NetworkResult<List<CustomWordResponse>> = withContext(Dispatchers.IO) {
        val request = CustomWordBulkCreateRequest(words = words)
        safeApiCall { apiService.addWordsBulk(request) }
    }

    /**
     * Update a word entry (parent only)
     */
    suspend fun updateWord(
        wordId: Int,
        word: String? = null,
        listType: WordListType? = null,
        category: String? = null,
        caseSensitive: Boolean? = null,
        wholeWordOnly: Boolean? = null,
        isActive: Boolean? = null
    ): NetworkResult<CustomWordResponse> = withContext(Dispatchers.IO) {
        val request = CustomWordUpdateRequest(
            word = word,
            listType = listType,
            category = category,
            caseSensitive = caseSensitive,
            wholeWordOnly = wholeWordOnly,
            isActive = isActive
        )
        safeApiCall { apiService.updateWord(wordId, request) }
    }

    /**
     * Delete a word entry (parent only)
     */
    suspend fun deleteWord(wordId: Int): NetworkResult<MessageResponse> = withContext(Dispatchers.IO) {
        safeApiCall { apiService.deleteWord(wordId) }
    }

    /**
     * Clear all words from a list (parent only)
     */
    suspend fun clearWordList(listType: WordListType? = null): NetworkResult<MessageResponse> = withContext(Dispatchers.IO) {
        val typeStr = listType?.name?.lowercase()
        safeApiCall { apiService.clearWordList(typeStr) }
    }

    // ==================== Device Functions (for syncing and using word lists) ====================

    /**
     * Sync word lists from backend.
     * Called periodically or when app starts.
     */
    suspend fun syncWordLists(): Boolean = withContext(Dispatchers.IO) {
        // The backend-issued device credential, not the local device UUID — `X-Device-Token`
        // authenticates the device record and rejects the UUID.
        val deviceToken = deviceRepository.ensureDeviceToken() ?: return@withContext false

        try {
            val result = safeApiCall { apiService.syncWordLists(deviceToken) }

            result.onSuccess { response ->
                // Save to local cache
                saveToCache(response)
                Timber.d("Word lists synced: ${response.whitelist.size} whitelist, ${response.blacklist.size} blacklist")
                return@withContext true
            }

            result.onError { message, _ ->
                Timber.w("Failed to sync word lists: $message")
            }

            false
        } catch (e: Exception) {
            Timber.e(e, "Error syncing word lists")
            false
        }
    }

    /**
     * Check if sync is needed based on last sync time.
     */
    suspend fun shouldSync(): Boolean {
        val lastSyncStr = context.wordListDataStore.data.first()[KEY_LAST_SYNC] ?: return true
        val lastSync = lastSyncStr.toLongOrNull() ?: return true
        return System.currentTimeMillis() - lastSync > SYNC_INTERVAL_MS
    }

    /**
     * Get cached whitelist words.
     */
    suspend fun getWhitelist(): List<String> = withContext(Dispatchers.IO) {
        try {
            val json = context.wordListDataStore.data.first()[KEY_WHITELIST] ?: return@withContext emptyList()
            val type = object : TypeToken<List<String>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            Timber.e(e, "Error reading whitelist from cache")
            emptyList()
        }
    }

    /**
     * Get cached blacklist words as CustomBlacklistWord objects.
     */
    suspend fun getBlacklist(): List<CustomBlacklistWord> = withContext(Dispatchers.IO) {
        try {
            val json = context.wordListDataStore.data.first()[KEY_BLACKLIST] ?: return@withContext emptyList()
            val type = object : TypeToken<List<BlacklistWordInfo>>() {}.type
            val blacklistInfo: List<BlacklistWordInfo> = gson.fromJson(json, type) ?: emptyList()

            // Convert to CustomBlacklistWord for TextPatternMatcher
            blacklistInfo.map {
                CustomBlacklistWord(
                    word = it.word,
                    category = it.category,
                    caseSensitive = it.caseSensitive,
                    wholeWordOnly = it.wholeWordOnly
                )
            }
        } catch (e: Exception) {
            Timber.e(e, "Error reading blacklist from cache")
            emptyList()
        }
    }

    /**
     * Get cached version number.
     */
    suspend fun getCachedVersion(): Int {
        return context.wordListDataStore.data.first()[KEY_VERSION] ?: 0
    }

    /**
     * Observe whitelist changes.
     */
    fun observeWhitelist(): Flow<List<String>> {
        return context.wordListDataStore.data.map { prefs ->
            try {
                val json = prefs[KEY_WHITELIST] ?: return@map emptyList()
                val type = object : TypeToken<List<String>>() {}.type
                gson.fromJson(json, type) ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    /**
     * Observe blacklist changes.
     */
    fun observeBlacklist(): Flow<List<CustomBlacklistWord>> {
        return context.wordListDataStore.data.map { prefs ->
            try {
                val json = prefs[KEY_BLACKLIST] ?: return@map emptyList()
                val type = object : TypeToken<List<BlacklistWordInfo>>() {}.type
                val blacklistInfo: List<BlacklistWordInfo> = gson.fromJson(json, type) ?: emptyList()

                blacklistInfo.map {
                    CustomBlacklistWord(
                        word = it.word,
                        category = it.category,
                        caseSensitive = it.caseSensitive,
                        wholeWordOnly = it.wholeWordOnly
                    )
                }
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    /**
     * Save sync response to local cache.
     */
    private suspend fun saveToCache(response: CustomWordSyncResponse) {
        context.wordListDataStore.edit { prefs ->
            prefs[KEY_WHITELIST] = gson.toJson(response.whitelist)
            prefs[KEY_BLACKLIST] = gson.toJson(response.blacklist)
            prefs[KEY_VERSION] = response.version
            prefs[KEY_LAST_SYNC] = System.currentTimeMillis().toString()
        }
    }

    /**
     * Clear local cache.
     */
    suspend fun clearCache() {
        context.wordListDataStore.edit { prefs ->
            prefs.clear()
        }
    }
}
