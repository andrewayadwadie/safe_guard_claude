package com.safeguard.parentalcontrol.util

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Encrypted preferences manager for storing app settings and user data
 *
 * Performance optimization:
 * - Uses lazy initialization to avoid blocking main thread during app startup
 * - EncryptedSharedPreferences initialization can take 50-200ms
 */
@Singleton
class PreferencesManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /**
     * Lazy-initialized MasterKey to avoid blocking main thread on app startup
     */
    private val masterKey by lazy {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    /**
     * Lazy-initialized EncryptedSharedPreferences
     * Will be initialized on first access, typically after splash screen
     */
    private val encryptedPrefs by lazy {
        EncryptedSharedPreferences.create(
            context,
            Constants.PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    // User Information
    var userId: Int
        get() = encryptedPrefs.getInt(Constants.KEY_USER_ID, -1)
        set(value) = encryptedPrefs.edit().putInt(Constants.KEY_USER_ID, value).apply()

    var userEmail: String?
        get() = encryptedPrefs.getString(Constants.KEY_USER_EMAIL, null)
        set(value) = encryptedPrefs.edit().putString(Constants.KEY_USER_EMAIL, value).apply()

    var userRole: String?
        get() = encryptedPrefs.getString(Constants.KEY_USER_ROLE, null)
        set(value) = encryptedPrefs.edit().putString(Constants.KEY_USER_ROLE, value).apply()

    val isParent: Boolean
        get() = userRole == UserRoles.PARENT

    val isChild: Boolean
        get() = userRole == UserRoles.CHILD

    // Device Information
    var deviceId: String?
        get() = encryptedPrefs.getString(Constants.KEY_DEVICE_ID, null)
        set(value) = encryptedPrefs.edit().putString(Constants.KEY_DEVICE_ID, value).apply()

    var deviceDbId: Int
        get() = encryptedPrefs.getInt(Constants.KEY_DEVICE_DB_ID, -1)
        set(value) = encryptedPrefs.edit().putInt(Constants.KEY_DEVICE_DB_ID, value).apply()

    var deviceName: String?
        get() = encryptedPrefs.getString(Constants.KEY_DEVICE_NAME, null)
        set(value) = encryptedPrefs.edit().putString(Constants.KEY_DEVICE_NAME, value).apply()

    var isDeviceRegistered: Boolean
        get() = encryptedPrefs.getBoolean(Constants.KEY_IS_DEVICE_REGISTERED, false)
        set(value) = encryptedPrefs.edit().putBoolean(Constants.KEY_IS_DEVICE_REGISTERED, value).apply()

    // Login State
    var isLoggedIn: Boolean
        get() = encryptedPrefs.getBoolean(Constants.KEY_IS_LOGGED_IN, false)
        set(value) = encryptedPrefs.edit().putBoolean(Constants.KEY_IS_LOGGED_IN, value).apply()

    // Sync Times
    var lastSyncTime: Long
        get() = encryptedPrefs.getLong(Constants.KEY_LAST_SYNC_TIME, 0)
        set(value) = encryptedPrefs.edit().putLong(Constants.KEY_LAST_SYNC_TIME, value).apply()

    var lastImageScanTime: Long
        get() = encryptedPrefs.getLong(Constants.KEY_LAST_IMAGE_SCAN_TIME, 0)
        set(value) = encryptedPrefs.edit().putLong(Constants.KEY_LAST_IMAGE_SCAN_TIME, value).apply()

    // Content Filtering (VPN)
    var isContentFilteringEnabled: Boolean
        get() = encryptedPrefs.getBoolean(Constants.KEY_CONTENT_FILTERING_ENABLED, false)
        set(value) = encryptedPrefs.edit().putBoolean(Constants.KEY_CONTENT_FILTERING_ENABLED, value).apply()

    /**
     * Store user information after login
     */
    fun saveUserInfo(userId: Int, email: String, role: String) {
        this.userId = userId
        this.userEmail = email
        this.userRole = role
        this.isLoggedIn = true
    }

    /**
     * Store device information after registration
     * Note: Content filtering is controlled by parent via backend, not locally
     */
    fun saveDeviceInfo(deviceId: String, deviceDbId: Int, deviceName: String? = null) {
        this.deviceId = deviceId
        this.deviceDbId = deviceDbId
        this.deviceName = deviceName ?: android.os.Build.MODEL
        this.isDeviceRegistered = true
    }

    /**
     * Check if sync is needed based on interval
     */
    fun isSyncNeeded(): Boolean {
        val now = System.currentTimeMillis()
        return (now - lastSyncTime) >= Constants.SYNC_INTERVAL_MS
    }

    /**
     * Check if image scan is needed based on interval
     */
    fun isImageScanNeeded(): Boolean {
        val now = System.currentTimeMillis()
        return (now - lastImageScanTime) >= (Constants.IMAGE_SCAN_INTERVAL_MINUTES * 60 * 1000)
    }

    /**
     * Update last sync time to now
     */
    fun updateLastSyncTime() {
        lastSyncTime = System.currentTimeMillis()
    }

    /**
     * Update last image scan time to now
     */
    fun updateLastImageScanTime() {
        lastImageScanTime = System.currentTimeMillis()
    }

    /**
     * Clear all user data on logout
     */
    fun clearAll() {
        encryptedPrefs.edit().clear().apply()
    }

    /**
     * Generate unique device ID if not exists
     */
    fun getOrCreateDeviceId(): String {
        var id = deviceId
        if (id == null) {
            id = java.util.UUID.randomUUID().toString()
            deviceId = id
        }
        return id
    }

    // ==================== Notification Spam Prevention ====================

    private val KEY_LIMIT_EXCEEDED_NOTIFIED_DATE = "limit_exceeded_notified_date"
    private val KEY_LIMIT_WARNING_NOTIFIED_DATE = "limit_warning_notified_date"
    private val KEY_BEDTIME_NOTIFIED_DATE = "bedtime_notified_date"

    /**
     * Get today's date as a string for comparison
     */
    private fun getTodayDateString(): String {
        val format = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
        return format.format(java.util.Date())
    }

    /**
     * Check if limit exceeded notification was already sent today
     */
    fun hasNotifiedLimitExceededToday(): Boolean {
        val lastNotifiedDate = encryptedPrefs.getString(KEY_LIMIT_EXCEEDED_NOTIFIED_DATE, null)
        return lastNotifiedDate == getTodayDateString()
    }

    /**
     * Mark that limit exceeded notification was sent today
     */
    fun setNotifiedLimitExceededToday() {
        encryptedPrefs.edit()
            .putString(KEY_LIMIT_EXCEEDED_NOTIFIED_DATE, getTodayDateString())
            .apply()
    }

    /**
     * Check if limit warning notification was already sent today
     */
    fun hasNotifiedLimitWarningToday(): Boolean {
        val lastNotifiedDate = encryptedPrefs.getString(KEY_LIMIT_WARNING_NOTIFIED_DATE, null)
        return lastNotifiedDate == getTodayDateString()
    }

    /**
     * Mark that limit warning notification was sent today
     */
    fun setNotifiedLimitWarningToday() {
        encryptedPrefs.edit()
            .putString(KEY_LIMIT_WARNING_NOTIFIED_DATE, getTodayDateString())
            .apply()
    }

    /**
     * Check if bedtime notification was already sent today
     */
    fun hasNotifiedBedtimeToday(): Boolean {
        val lastNotifiedDate = encryptedPrefs.getString(KEY_BEDTIME_NOTIFIED_DATE, null)
        return lastNotifiedDate == getTodayDateString()
    }

    /**
     * Mark that bedtime notification was sent today
     */
    fun setNotifiedBedtimeToday() {
        encryptedPrefs.edit()
            .putString(KEY_BEDTIME_NOTIFIED_DATE, getTodayDateString())
            .apply()
    }

    /**
     * Reset all notification flags (e.g., at midnight)
     */
    fun resetNotificationFlags() {
        encryptedPrefs.edit()
            .remove(KEY_LIMIT_EXCEEDED_NOTIFIED_DATE)
            .remove(KEY_LIMIT_WARNING_NOTIFIED_DATE)
            .remove(KEY_BEDTIME_NOTIFIED_DATE)
            .apply()
    }

    // ==================== Generic Preference Methods ====================

    /**
     * Get a boolean preference value.
     * @param key The preference key
     * @param defaultValue The default value if key doesn't exist
     */
    fun getBoolean(key: String, defaultValue: Boolean = false): Boolean {
        return encryptedPrefs.getBoolean(key, defaultValue)
    }

    /**
     * Store a boolean preference value.
     * @param key The preference key
     * @param value The value to store
     */
    fun putBoolean(key: String, value: Boolean) {
        encryptedPrefs.edit().putBoolean(key, value).apply()
    }

    /**
     * Get a string preference value.
     * @param key The preference key
     * @param defaultValue The default value if key doesn't exist
     */
    fun getString(key: String, defaultValue: String? = null): String? {
        return encryptedPrefs.getString(key, defaultValue)
    }

    /**
     * Store a string preference value.
     * @param key The preference key
     * @param value The value to store
     */
    fun putString(key: String, value: String?) {
        encryptedPrefs.edit().putString(key, value).apply()
    }

    /**
     * Get an integer preference value.
     * @param key The preference key
     * @param defaultValue The default value if key doesn't exist
     */
    fun getInt(key: String, defaultValue: Int = 0): Int {
        return encryptedPrefs.getInt(key, defaultValue)
    }

    /**
     * Store an integer preference value.
     * @param key The preference key
     * @param value The value to store
     */
    fun putInt(key: String, value: Int) {
        encryptedPrefs.edit().putInt(key, value).apply()
    }

    /**
     * Get a long preference value.
     * @param key The preference key
     * @param defaultValue The default value if key doesn't exist
     */
    fun getLong(key: String, defaultValue: Long = 0L): Long {
        return encryptedPrefs.getLong(key, defaultValue)
    }

    /**
     * Store a long preference value.
     * @param key The preference key
     * @param value The value to store
     */
    fun putLong(key: String, value: Long) {
        encryptedPrefs.edit().putLong(key, value).apply()
    }

    /**
     * Remove a preference value.
     * @param key The preference key to remove
     */
    fun remove(key: String) {
        encryptedPrefs.edit().remove(key).apply()
    }
}
