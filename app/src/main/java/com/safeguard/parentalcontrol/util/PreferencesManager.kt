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

    // Signed-in user's display name, captured at login/registration. On a child device this
    // is the name attached to every reported alert so the parent's notification can say who
    // triggered it. Null on accounts that signed in before this was captured — callers must
    // degrade gracefully rather than render an empty name. Cleared on logout by clearAll().
    var userFullName: String?
        get() = encryptedPrefs.getString(Constants.KEY_USER_FULL_NAME, null)
        set(value) = encryptedPrefs.edit().putString(Constants.KEY_USER_FULL_NAME, value).apply()

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

    // Cached "this child account has at least one linked parent" state, refreshed from
    // GET /family/parents at service start. The monitoring gate reads this cached value
    // so it is deterministic at startup and works offline. Defaults to false so a fresh
    // (never-confirmed) install stays gated. Cleared on logout by clearAll().
    var hasLinkedParent: Boolean
        get() = encryptedPrefs.getBoolean(Constants.KEY_HAS_LINKED_PARENT, false)
        set(value) = encryptedPrefs.edit().putBoolean(Constants.KEY_HAS_LINKED_PARENT, value).apply()

    // Parent acknowledged the in-app monitoring disclosure during setup. Until this is
    // true, no monitoring service may run (see shouldRunMonitoring). Cleared on logout
    // by clearAll(), so a new account on this device must consent again.
    var monitoringConsentGranted: Boolean
        get() = encryptedPrefs.getBoolean(Constants.KEY_MONITORING_CONSENT_GRANTED, false)
        set(value) = encryptedPrefs.edit().putBoolean(Constants.KEY_MONITORING_CONSENT_GRANTED, value).apply()

    /**
     * Single source of truth for whether monitoring services may run on this device.
     * Requires a logged-in, registered device whose parent has consented to monitoring.
     * Every auto-start path (boot, restart, sync, app open) and every monitoring service
     * gates on this, so monitoring can never begin before the disclosure is acknowledged.
     */
    val shouldRunMonitoring: Boolean
        get() = isLoggedIn && isDeviceRegistered && monitoringConsentGranted

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

    // Maximum Protection: when true, detected image violations are blurred in the gallery in
    // addition to being backed up for parent review. Default false = copy-only mode. Changeable
    // only after parent-PIN verification (enforced at the UI layer). Read fresh on every
    // violation so a change takes effect on the next image processed. Cleared on logout by
    // clearAll(), so a fresh login defaults back to OFF.
    var isMaximumProtectionEnabled: Boolean
        get() = encryptedPrefs.getBoolean(Constants.KEY_MAXIMUM_PROTECTION_ENABLED, false)
        set(value) = encryptedPrefs.edit().putBoolean(Constants.KEY_MAXIMUM_PROTECTION_ENABLED, value).apply()

    // Parent dismissed the "notifications are disabled" banner. Informational only — the
    // banner never blocks any functionality. Cleared on logout by clearAll().
    var notificationBannerDismissed: Boolean
        get() = encryptedPrefs.getBoolean(Constants.KEY_NOTIFICATION_BANNER_DISMISSED, false)
        set(value) = encryptedPrefs.edit().putBoolean(Constants.KEY_NOTIFICATION_BANNER_DISMISSED, value).apply()

    /**
     * Store user information after login
     */
    fun saveUserInfo(userId: Int, email: String, role: String, fullName: String? = null) {
        this.userId = userId
        this.userEmail = email
        this.userRole = role
        // Only overwrite a stored name when the caller actually has one, so a partial
        // sign-in path can never blank out a previously captured name.
        if (!fullName.isNullOrBlank()) {
            this.userFullName = fullName
        }
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
     * One-time migration for the monitoring-consent gate.
     *
     * Devices set up before the consent flag existed are already monitoring; defaulting
     * them to "no consent" on upgrade would silently stop monitoring and leave a child
     * unmonitored with no one aware. Grandfather any already-registered device the first
     * time this runs (their parent consented under the prior setup flow). Fresh setups
     * have no stored flag AND are not yet registered, so they still go through the
     * consent screen, which sets the flag explicitly.
     */
    fun migrateMonitoringConsentIfNeeded() {
        if (!encryptedPrefs.contains(Constants.KEY_MONITORING_CONSENT_GRANTED) && isDeviceRegistered) {
            monitoringConsentGranted = true
        }
    }

    // ==================== Parent Review PIN ====================
    //
    // A local PIN, set by the parent on the child device, that gates the on-device
    // flagged-photo / flagged-text review screens. Verified entirely on-device (works
    // offline) so the child can never open the review surfaces. The PIN itself is never
    // stored in clear text: we keep a random salt + SHA-256(salt || pin) and compare in
    // constant time. EncryptedSharedPreferences also encrypts these values at rest.

    /** True once a parent review PIN has been set on this device. */
    val hasParentPin: Boolean
        get() = encryptedPrefs.contains(Constants.KEY_PARENT_PIN_HASH)

    /** Set (or replace) the parent review PIN. */
    fun setParentPin(pin: String) {
        val salt = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        val hash = hashPin(pin, salt)
        encryptedPrefs.edit()
            .putString(Constants.KEY_PARENT_PIN_SALT, encodeB64(salt))
            .putString(Constants.KEY_PARENT_PIN_HASH, encodeB64(hash))
            .apply()
    }

    /** Verify a candidate PIN against the stored salted hash in constant time. */
    fun verifyParentPin(pin: String): Boolean {
        val saltB64 = encryptedPrefs.getString(Constants.KEY_PARENT_PIN_SALT, null) ?: return false
        val expected = encryptedPrefs.getString(Constants.KEY_PARENT_PIN_HASH, null) ?: return false
        val actual = encodeB64(hashPin(pin, decodeB64(saltB64)))
        // MessageDigest.isEqual is constant-time on Android (no early-exit on mismatch).
        return java.security.MessageDigest.isEqual(
            expected.toByteArray(Charsets.UTF_8),
            actual.toByteArray(Charsets.UTF_8)
        )
    }

    private fun hashPin(pin: String, salt: ByteArray): ByteArray {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        md.update(salt)
        return md.digest(pin.toByteArray(Charsets.UTF_8))
    }

    private fun encodeB64(bytes: ByteArray): String =
        android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)

    private fun decodeB64(value: String): ByteArray =
        android.util.Base64.decode(value, android.util.Base64.NO_WRAP)

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
     * Get this device's stable ID, deriving and persisting one if absent.
     *
     * Derived deterministically from Settings.Secure.ANDROID_ID so that a
     * RE-INSTALL — which wipes these encrypted prefs and so the persisted id —
     * reproduces the SAME device_id. The backend then reuses the existing device
     * record instead of creating a duplicate (it previously registered a second
     * row per reinstall). ANDROID_ID is stable per signing-key+user and survives
     * uninstall; it changes only on factory reset (a genuinely new-device state).
     *
     * Falls back to a random UUID when ANDROID_ID is unavailable or is the known
     * buggy constant some devices return, so registration never fails. An id
     * already persisted from a prior version is kept as-is (no forced rotation).
     */
    fun getOrCreateDeviceId(): String {
        var id = deviceId
        if (id == null) {
            id = stableDeviceIdFromAndroidId() ?: java.util.UUID.randomUUID().toString()
            deviceId = id
        }
        return id
    }

    // ANDROID_ID is the sanctioned stable per-app/device identifier for exactly
    // this purpose (a durable install id). It is namespaced + hashed below and
    // never transmitted raw, so the HardwareIds advisory does not apply.
    @android.annotation.SuppressLint("HardwareIds")
    private fun stableDeviceIdFromAndroidId(): String? {
        val androidId = try {
            android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID
            )
        } catch (e: Exception) {
            null
        }
        // "9774d56d682e549c" is a well-known non-unique ANDROID_ID some devices
        // emit; treat it as unusable so we don't collide distinct phones.
        if (androidId.isNullOrBlank() || androidId == "9774d56d682e549c") return null
        // Namespace before hashing so we never transmit the raw ANDROID_ID and so
        // the result is a UUID, matching the device_id format the backend expects.
        return java.util.UUID.nameUUIDFromBytes("safeguard-device:$androidId".toByteArray()).toString()
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
