package com.safeguard.parentalcontrol.util

/**
 * Application-wide constants
 */
object Constants {
    // Preferences Keys
    const val PREFS_NAME = "safeguard_prefs"
    const val KEY_ACCESS_TOKEN = "access_token"
    const val KEY_REFRESH_TOKEN = "refresh_token"
    const val KEY_USER_ID = "user_id"
    const val KEY_USER_EMAIL = "user_email"
    const val KEY_USER_ROLE = "user_role"
    const val KEY_DEVICE_ID = "device_id"
    const val KEY_DEVICE_DB_ID = "device_db_id"
    const val KEY_DEVICE_NAME = "device_name"
    const val KEY_IS_LOGGED_IN = "is_logged_in"
    const val KEY_IS_DEVICE_REGISTERED = "is_device_registered"
    const val KEY_LAST_SYNC_TIME = "last_sync_time"
    const val KEY_LAST_IMAGE_SCAN_TIME = "last_image_scan_time"
    const val KEY_CONTENT_FILTERING_ENABLED = "content_filtering_enabled"

    // Sync Intervals (milliseconds)
    const val SYNC_INTERVAL_MINUTES = 5L // Reduced for testing (production: 15L)
    const val SYNC_INTERVAL_MS = SYNC_INTERVAL_MINUTES * 60 * 1000
    const val IMAGE_SCAN_INTERVAL_MINUTES = 30L

    // Rate Limiting
    const val MAX_REQUESTS_PER_MINUTE = 60
    const val MAX_DEVICE_REQUESTS_PER_MINUTE = 100

    // Token Configuration
    const val ACCESS_TOKEN_EXPIRY_BUFFER_MS = 5 * 60 * 1000 // Refresh 5 minutes before expiry

    // Notification IDs
    const val NOTIFICATION_ID_MONITORING_SERVICE = 1001
    const val NOTIFICATION_ID_ALERT = 2000
    const val NOTIFICATION_ID_SCREEN_TIME = 3000
    const val NOTIFICATION_ID_SYNC = 4000
    const val NOTIFICATION_ID_LIMIT_EXCEEDED = 5000
    const val NOTIFICATION_ID_LIMIT_WARNING = 5001
    const val NOTIFICATION_ID_BEDTIME = 5002
    const val NOTIFICATION_ID_APP_BLOCKED = 5003
    const val NOTIFICATION_ID_ACCESSIBILITY_DISABLED = 5004

    // Request Codes
    const val REQUEST_CODE_USAGE_ACCESS = 100
    const val REQUEST_CODE_ACCESSIBILITY_SERVICE = 101
    const val REQUEST_CODE_VPN_SERVICE = 102
    const val REQUEST_CODE_NOTIFICATION_PERMISSION = 103
    const val REQUEST_CODE_STORAGE_PERMISSION = 104

    // WorkManager Tags
    const val WORK_TAG_SYNC = "sync_work"
    const val WORK_TAG_SCREEN_TIME = "screen_time_work"
    const val WORK_TAG_IMAGE_SCAN = "image_scan_work"
    const val WORK_TAG_ACCESSIBILITY_MONITOR = "accessibility_monitor_work"
    const val WORK_TAG_TAMPER_ALERT = "tamper_alert_work"

    // Protection Monitor Interval (reduced from 15 to 5 for faster detection)
    const val ACCESSIBILITY_MONITOR_INTERVAL_MINUTES = 5L

    // Preference keys for tamper detection (used in BroadcastReceiver without Hilt)
    const val PREF_IS_PARENT = "is_parent"
    const val PREF_ACCESS_TOKEN = "access_token"

    // Database
    const val DATABASE_NAME = "safeguard_database"
    const val DATABASE_VERSION = 1

    // Network
    const val CONNECT_TIMEOUT_SECONDS = 30L
    const val READ_TIMEOUT_SECONDS = 30L
    const val WRITE_TIMEOUT_SECONDS = 30L

    // Security - Certificate Pinning
    // IMPORTANT: Replace with actual certificate hashes before production deployment
    const val API_HOST = "api.safeguard.app"
    const val CERTIFICATE_PIN_PRIMARY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
    const val CERTIFICATE_PIN_BACKUP = "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB="

    // DNS Cache
    const val DNS_CACHE_SIZE = 500
    const val DNS_CACHE_TTL_MS = 5 * 60 * 1000L // 5 minutes

    // Screen Time
    const val DEFAULT_DAILY_LIMIT_HOURS = 2
    const val DEFAULT_DAILY_LIMIT_SECONDS = DEFAULT_DAILY_LIMIT_HOURS * 60 * 60

    // Image Analysis
    const val NSFW_CONFIDENCE_THRESHOLD = 0.6f
    const val IMAGE_SIZE_FOR_ANALYSIS = 224

    // VPN
    const val VPN_MTU = 1500
    const val VPN_ADDRESS = "10.0.0.2"
    const val VPN_ROUTE = "0.0.0.0"
    const val VPN_DNS_PRIMARY = "8.8.8.8"
    const val VPN_DNS_SECONDARY = "8.8.4.4"
}

/**
 * Alert types matching backend enum
 */
object AlertTypes {
    const val CONTENT_BLOCK = "content_block"
    const val SCREEN_TIME_LIMIT = "screen_time_limit"
    const val APP_BLOCKED = "app_blocked"
    const val INAPPROPRIATE_IMAGE = "inappropriate_image"
    const val INAPPROPRIATE_TEXT = "inappropriate_text"
    const val SCREENSHOT_CAPTURED = "screenshot_captured"
    const val DEVICE_ADMIN_DISABLED = "device_admin_disabled"
}

/**
 * Alert severity levels matching backend enum
 */
object AlertSeverity {
    const val LOW = "low"
    const val MEDIUM = "medium"
    const val HIGH = "high"
    const val CRITICAL = "critical"
}

/**
 * Device status matching backend enum
 */
object DeviceStatus {
    const val ACTIVE = "active"
    const val INACTIVE = "inactive"
    const val SUSPENDED = "suspended"
}

/**
 * User roles matching backend enum
 */
object UserRoles {
    const val PARENT = "parent"
    const val CHILD = "child"
}
