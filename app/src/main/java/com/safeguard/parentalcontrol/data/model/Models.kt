package com.safeguard.parentalcontrol.data.model

import android.os.Parcelable
import com.google.gson.annotations.SerializedName
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.RawValue
import java.util.Date

/**
 * User role enum
 */
enum class UserRole {
    @SerializedName("parent")
    PARENT,

    @SerializedName("child")
    CHILD
}

/**
 * Device status enum
 */
enum class DeviceStatus {
    @SerializedName("active")
    ACTIVE,

    @SerializedName("inactive")
    INACTIVE,

    @SerializedName("suspended")
    SUSPENDED
}

/**
 * Alert type enum
 */
enum class AlertType {
    @SerializedName("content_block")
    CONTENT_BLOCK,

    @SerializedName("screen_time_limit")
    SCREEN_TIME_LIMIT,

    @SerializedName("app_blocked")
    APP_BLOCKED,

    @SerializedName("inappropriate_image")
    INAPPROPRIATE_IMAGE,

    @SerializedName("inappropriate_text")
    INAPPROPRIATE_TEXT,

    @SerializedName("screenshot_captured")
    SCREENSHOT_CAPTURED,

    @SerializedName("device_admin_disabled")
    DEVICE_ADMIN_DISABLED
}

/**
 * Alert severity enum
 */
enum class AlertSeverity {
    @SerializedName("low")
    LOW,

    @SerializedName("medium")
    MEDIUM,

    @SerializedName("high")
    HIGH,

    @SerializedName("critical")
    CRITICAL
}

/**
 * Authentication provider enum
 */
enum class AuthProvider {
    @SerializedName("local")
    LOCAL,

    @SerializedName("google")
    GOOGLE
}

// ========== Request Models ==========

data class RegisterRequest(
    val email: String,
    val password: String,
    @SerializedName("full_name")
    val fullName: String,
    val role: UserRole
)

data class LoginRequest(
    val email: String,
    val password: String
)

data class RefreshTokenRequest(
    @SerializedName("refresh_token")
    val refreshToken: String
)

/**
 * Google OAuth authentication request
 */
data class GoogleAuthRequest(
    @SerializedName("id_token")
    val idToken: String,
    val role: UserRole? = null  // Required for new user registration
)

data class ChangePasswordRequest(
    @SerializedName("current_password")
    val currentPassword: String,
    @SerializedName("new_password")
    val newPassword: String
)

data class DeviceRegisterRequest(
    @SerializedName("device_id")
    val deviceId: String,
    @SerializedName("device_name")
    val deviceName: String,
    @SerializedName("device_model")
    val deviceModel: String?,
    @SerializedName("android_version")
    val androidVersion: String?,
    @SerializedName("app_version")
    val appVersion: String?,
    @SerializedName("device_info")
    val deviceInfo: Map<String, String>?,
    @SerializedName("fcm_token")
    val fcmToken: String?
)

data class DeviceUpdateRequest(
    @SerializedName("device_name")
    val deviceName: String? = null,
    @SerializedName("fcm_token")
    val fcmToken: String? = null,
    @SerializedName("app_version")
    val appVersion: String? = null
)

data class ScreenTimeUpdate(
    val date: Date,
    @SerializedName("total_screen_time")
    val totalScreenTime: Int,
    @SerializedName("unlocks_count")
    val unlocksCount: Int
)

data class AppUsageUpdate(
    @SerializedName("package_name")
    val packageName: String,
    @SerializedName("app_name")
    val appName: String?,
    val date: Date,
    @SerializedName("usage_time")
    val usageTime: Int,
    @SerializedName("last_used")
    val lastUsed: Date?
)

data class AppUsageBatch(
    val apps: List<AppUsageUpdate>,
    // Device's current UTC offset in minutes (e.g. 180 for UTC+3). Lets the backend
    // evaluate "today" in this device's local timezone so daily usage / top-apps roll
    // over at the child's local midnight, not the server's.
    @SerializedName("utc_offset_minutes")
    val utcOffsetMinutes: Int? = null
)

data class AlertCreate(
    @SerializedName("device_id")
    val deviceId: Int,
    @SerializedName("alert_type")
    val alertType: AlertType,
    val severity: AlertSeverity,
    val title: String,
    val message: String,
    val metadata: Map<String, Any>?,
    @SerializedName("evidence_data")
    val evidenceData: String?
)

data class AlertUpdateRequest(
    @SerializedName("is_read")
    val isRead: Boolean? = null,
    @SerializedName("is_dismissed")
    val isDismissed: Boolean? = null
)

// ========== Response Models ==========

@Parcelize
data class User(
    val id: Int,
    val email: String,
    @SerializedName("full_name")
    val fullName: String,
    val role: UserRole,
    @SerializedName("is_active")
    val isActive: Boolean,
    @SerializedName("created_at")
    val createdAt: Date,
    @SerializedName("auth_provider")
    val authProvider: AuthProvider = AuthProvider.LOCAL,
    @SerializedName("profile_picture_url")
    val profilePictureUrl: String? = null
) : Parcelable

data class TokenResponse(
    @SerializedName("access_token")
    val accessToken: String,
    @SerializedName("refresh_token")
    val refreshToken: String,
    @SerializedName("token_type")
    val tokenType: String,
    val user: User
)

@Parcelize
data class Device(
    val id: Int,
    @SerializedName("user_id")
    val userId: Int,
    @SerializedName("device_id")
    val deviceId: String,
    @SerializedName("device_name")
    val deviceName: String,
    @SerializedName("device_model")
    val deviceModel: String?,
    @SerializedName("android_version")
    val androidVersion: String?,
    @SerializedName("app_version")
    val appVersion: String?,
    val status: DeviceStatus,
    @SerializedName("last_sync")
    val lastSync: Date?,
    @SerializedName("created_at")
    val createdAt: Date
) : Parcelable {
    companion object {
        /** Threshold for considering a device as "online" - 15 minutes */
        private const val ONLINE_THRESHOLD_MS = 15 * 60 * 1000L
    }

    /**
     * Returns true if the device has synced within the last 15 minutes.
     * A device is considered "online" if it has communicated with the server recently.
     */
    val isOnline: Boolean
        get() = lastSync?.let { syncTime ->
            val diffMs = System.currentTimeMillis() - syncTime.time
            diffMs < ONLINE_THRESHOLD_MS
        } ?: false
}

data class ScreenTimeLog(
    val id: Int,
    @SerializedName("device_id")
    val deviceId: Int,
    val date: Date,
    @SerializedName("total_screen_time")
    val totalScreenTime: Int,
    @SerializedName("unlocks_count")
    val unlocksCount: Int,
    @SerializedName("created_at")
    val createdAt: Date
)

data class ScreenTimeStats(
    @SerializedName("total_screen_time")
    val totalScreenTime: Int,
    @SerializedName("daily_average")
    val dailyAverage: Float,
    @SerializedName("unlocks_count")
    val unlocksCount: Int,
    @SerializedName("date_range")
    val dateRange: String
)

data class AppUsageLog(
    val id: Int,
    @SerializedName("device_id")
    val deviceId: Int,
    @SerializedName("package_name")
    val packageName: String,
    @SerializedName("app_name")
    val appName: String?,
    val date: Date,
    @SerializedName("usage_time")
    val usageTime: Int,
    @SerializedName("last_used")
    val lastUsed: Date?
)

@Parcelize
data class Alert(
    val id: Int,
    @SerializedName("user_id")
    val userId: Int,
    @SerializedName("device_id")
    val deviceId: Int,
    @SerializedName("alert_type")
    val alertType: AlertType,
    val severity: AlertSeverity,
    val title: String,
    val message: String,
    val metadata: Map<String, @RawValue Any>?,
    @SerializedName("is_read")
    val isRead: Boolean,
    @SerializedName("is_dismissed")
    val isDismissed: Boolean,
    @SerializedName("created_at")
    val createdAt: Date,
    @SerializedName("updated_at")
    val updatedAt: Date? = null,
    /** Number of times this alert has occurred (for alert stacking) */
    @SerializedName("occurrence_count")
    val occurrenceCount: Int = 1
) : Parcelable

data class AlertStats(
    @SerializedName("total_alerts")
    val totalAlerts: Int,
    @SerializedName("unread_count")
    val unreadCount: Int,
    @SerializedName("by_severity")
    val bySeverity: Map<String, Int>,
    @SerializedName("by_type")
    val byType: Map<String, Int>
)

data class MessageResponse(
    val message: String,
    val success: Boolean = true
)

data class ErrorResponse(
    val detail: String,
    @SerializedName("error_code")
    val errorCode: String?
)

data class HealthResponse(
    val status: String,
    val version: String,
    val database: String,
    val redis: String,
    val timestamp: Date
)

// ========== Content Filter Models ==========

data class ContentFilter(
    val id: Int,
    @SerializedName("device_id")
    val deviceId: Int,
    @SerializedName("block_adult")
    val blockAdult: Boolean,
    @SerializedName("block_violence")
    val blockViolence: Boolean,
    @SerializedName("block_gambling")
    val blockGambling: Boolean,
    @SerializedName("block_drugs")
    val blockDrugs: Boolean,
    @SerializedName("block_social_media")
    val blockSocialMedia: Boolean,
    @SerializedName("blocked_domains")
    val blockedDomains: List<String>?,
    @SerializedName("allowed_domains")
    val allowedDomains: List<String>?,
    @SerializedName("is_active")
    val isActive: Boolean,
    @SerializedName("created_at")
    val createdAt: Date
)

data class ContentFilterUpdate(
    @SerializedName("block_adult")
    val blockAdult: Boolean? = null,
    @SerializedName("block_violence")
    val blockViolence: Boolean? = null,
    @SerializedName("block_gambling")
    val blockGambling: Boolean? = null,
    @SerializedName("block_drugs")
    val blockDrugs: Boolean? = null,
    @SerializedName("block_social_media")
    val blockSocialMedia: Boolean? = null,
    @SerializedName("blocked_domains")
    val blockedDomains: List<String>? = null,
    @SerializedName("allowed_domains")
    val allowedDomains: List<String>? = null,
    @SerializedName("is_active")
    val isActive: Boolean? = null
)

data class BlacklistResponse(
    @SerializedName("blocked_domains")
    val blockedDomains: List<String>,
    @SerializedName("updated_at")
    val updatedAt: Date
)

data class BlacklistDomainRequest(
    val domain: String
)

// ========== Custom Word List Models ==========

/**
 * Word list type enum
 */
enum class WordListType {
    @SerializedName("whitelist")
    WHITELIST,

    @SerializedName("blacklist")
    BLACKLIST
}

/**
 * Request to create a custom word
 */
data class CustomWordCreateRequest(
    val word: String,
    @SerializedName("list_type")
    val listType: WordListType,
    val category: String? = null,
    @SerializedName("case_sensitive")
    val caseSensitive: Boolean = false,
    @SerializedName("whole_word_only")
    val wholeWordOnly: Boolean = true
)

/**
 * Request to update a custom word
 */
data class CustomWordUpdateRequest(
    val word: String? = null,
    @SerializedName("list_type")
    val listType: WordListType? = null,
    val category: String? = null,
    @SerializedName("case_sensitive")
    val caseSensitive: Boolean? = null,
    @SerializedName("whole_word_only")
    val wholeWordOnly: Boolean? = null,
    @SerializedName("is_active")
    val isActive: Boolean? = null
)

/**
 * Request to bulk create words
 */
data class CustomWordBulkCreateRequest(
    val words: List<CustomWordCreateRequest>
)

/**
 * Response for a custom word entry
 */
data class CustomWordResponse(
    val id: Int,
    @SerializedName("user_id")
    val userId: Int,
    val word: String,
    @SerializedName("list_type")
    val listType: WordListType,
    val category: String?,
    @SerializedName("case_sensitive")
    val caseSensitive: Boolean,
    @SerializedName("whole_word_only")
    val wholeWordOnly: Boolean,
    @SerializedName("is_active")
    val isActive: Boolean,
    @SerializedName("created_at")
    val createdAt: Date,
    @SerializedName("updated_at")
    val updatedAt: Date?
)

/**
 * Response containing both whitelist and blacklist
 */
data class CustomWordListResponse(
    val whitelist: List<CustomWordResponse>,
    val blacklist: List<CustomWordResponse>,
    @SerializedName("last_updated")
    val lastUpdated: Date?
)

/**
 * Blacklist word info for sync (lightweight format)
 */
data class BlacklistWordInfo(
    val word: String,
    val category: String,
    @SerializedName("case_sensitive")
    val caseSensitive: Boolean,
    @SerializedName("whole_word_only")
    val wholeWordOnly: Boolean
)

/**
 * Response for device sync - lightweight format
 */
data class CustomWordSyncResponse(
    val whitelist: List<String>,
    val blacklist: List<BlacklistWordInfo>,
    val version: Int
)

// ========== Family Link Models ==========

/**
 * Request to create a family link by redeeming a pairing code shown on the
 * child's device. Linking by email was removed: knowing an email must not be
 * enough to monitor a child.
 */
data class FamilyLinkCreateRequest(
    @SerializedName("pairing_code")
    val pairingCode: String
)

/**
 * Pairing code a child device generates for a parent to enter.
 */
data class PairingCodeResponse(
    val code: String,
    @SerializedName("expires_at")
    val expiresAt: Date
)

/**
 * Response for family link operations
 */
@Parcelize
data class FamilyLink(
    val id: Int,
    @SerializedName("parent_id")
    val parentId: Int,
    @SerializedName("child_id")
    val childId: Int,
    @SerializedName("child_email")
    val childEmail: String,
    @SerializedName("child_name")
    val childName: String,
    @SerializedName("created_at")
    val createdAt: Date
) : Parcelable

/**
 * A parent account linked to this child (child-side view of the family link).
 * All fields are nullable by design: the pairing gate only checks list emptiness,
 * so a schema mismatch must degrade to nulls, never a parse crash.
 */
// TODO(backend): confirm exact field names for GET /family/parents.
data class LinkedParent(
    val id: Int?,
    @SerializedName("parent_id")
    val parentId: Int?,
    @SerializedName("parent_email")
    val parentEmail: String?,
    @SerializedName("parent_name")
    val parentName: String?,
    @SerializedName("created_at")
    val createdAt: Date?
)

/**
 * Response containing list of linked children with their devices
 */
data class LinkedChild(
    val id: Int,
    val email: String,
    @SerializedName("full_name")
    val fullName: String,
    @SerializedName("device_count")
    val deviceCount: Int = 0,
    @SerializedName("created_at")
    val createdAt: Date
)

// ========== Screen Time Rules Models ==========

/**
 * Request to create screen time rules for a device
 */
data class ScreenTimeRuleCreateRequest(
    @SerializedName("device_id")
    val deviceId: Int,
    @SerializedName("daily_limit")
    val dailyLimit: Int? = null,
    @SerializedName("bedtime_enabled")
    val bedtimeEnabled: Boolean = false,
    @SerializedName("bedtime_start")
    val bedtimeStart: String? = null,
    @SerializedName("bedtime_end")
    val bedtimeEnd: String? = null,
    // Study time
    @SerializedName("study_time_enabled")
    val studyTimeEnabled: Boolean = false,
    @SerializedName("study_time_start")
    val studyTimeStart: String? = null,
    @SerializedName("study_time_end")
    val studyTimeEnd: String? = null,
    @SerializedName("study_time_allowed_apps")
    val studyTimeAllowedApps: List<String>? = null,
    // Remote device lock
    @SerializedName("is_device_locked")
    val isDeviceLocked: Boolean = false,
    @SerializedName("device_locked_message")
    val deviceLockedMessage: String? = null,
    @SerializedName("app_limits")
    val appLimits: Map<String, Int>? = null,
    @SerializedName("blocked_apps")
    val blockedApps: List<String>? = null
)

/**
 * Request to update screen time rules
 */
data class ScreenTimeRuleUpdateRequest(
    @SerializedName("daily_limit")
    val dailyLimit: Int? = null,
    @SerializedName("bedtime_enabled")
    val bedtimeEnabled: Boolean? = null,
    @SerializedName("bedtime_start")
    val bedtimeStart: String? = null,
    @SerializedName("bedtime_end")
    val bedtimeEnd: String? = null,
    // Study time
    @SerializedName("study_time_enabled")
    val studyTimeEnabled: Boolean? = null,
    @SerializedName("study_time_start")
    val studyTimeStart: String? = null,
    @SerializedName("study_time_end")
    val studyTimeEnd: String? = null,
    @SerializedName("study_time_allowed_apps")
    val studyTimeAllowedApps: List<String>? = null,
    // Remote device lock
    @SerializedName("is_device_locked")
    val isDeviceLocked: Boolean? = null,
    @SerializedName("device_locked_message")
    val deviceLockedMessage: String? = null,
    @SerializedName("app_limits")
    val appLimits: Map<String, Int>? = null,
    @SerializedName("blocked_apps")
    val blockedApps: List<String>? = null,
    @SerializedName("is_active")
    val isActive: Boolean? = null
)

/**
 * Response containing screen time rules for a device
 */
@Parcelize
data class ScreenTimeRule(
    val id: Int,
    @SerializedName("device_id")
    val deviceId: Int,
    @SerializedName("daily_limit")
    val dailyLimit: Int?,
    @SerializedName("bedtime_enabled")
    val bedtimeEnabled: Boolean,
    @SerializedName("bedtime_start")
    val bedtimeStart: String?,
    @SerializedName("bedtime_end")
    val bedtimeEnd: String?,
    // Study time fields
    @SerializedName("study_time_enabled")
    val studyTimeEnabled: Boolean = false,
    @SerializedName("study_time_start")
    val studyTimeStart: String? = null,
    @SerializedName("study_time_end")
    val studyTimeEnd: String? = null,
    @SerializedName("study_time_allowed_apps")
    val studyTimeAllowedApps: List<String>? = null,
    // Remote device lock fields
    @SerializedName("is_device_locked")
    val isDeviceLocked: Boolean = false,
    @SerializedName("device_locked_message")
    val deviceLockedMessage: String? = null,
    @SerializedName("device_locked_at")
    val deviceLockedAt: Date? = null,
    @SerializedName("app_limits")
    val appLimits: Map<String, @RawValue Int>?,
    @SerializedName("blocked_apps")
    val blockedApps: List<String>?,
    @SerializedName("is_active")
    val isActive: Boolean,
    @SerializedName("created_at")
    val createdAt: Date,
    @SerializedName("updated_at")
    val updatedAt: Date?
) : Parcelable {

    /**
     * Check if daily screen time limit is exceeded
     * @param usedTimeSeconds total screen time used today in seconds
     * @return true if limit is set (and > 0) and exceeded
     * Note: dailyLimit of 0 or null means "no limit"
     */
    fun isDailyLimitExceeded(usedTimeSeconds: Int): Boolean {
        // Treat null or 0 as "no limit"
        val limit = dailyLimit ?: return false
        if (limit <= 0) return false
        return isActive && usedTimeSeconds >= limit
    }

    /**
     * Check if app-specific time limit is exceeded
     * @param packageName the app package name
     * @param usedTimeSeconds time used for this app today in seconds
     * @return true if app has a limit (> 0) and it's exceeded
     * Note: app limit of 0 or null means "no limit"
     */
    fun isAppLimitExceeded(packageName: String, usedTimeSeconds: Int): Boolean {
        if (!isActive) return false
        val limit = appLimits?.get(packageName) ?: return false
        // Treat 0 as "no limit"
        if (limit <= 0) return false
        return usedTimeSeconds >= limit
    }

    /**
     * Check if an app is completely blocked
     * @param packageName the app package name
     * @return true if app is in the blocked list
     */
    fun isAppBlocked(packageName: String): Boolean {
        return isActive && blockedApps?.contains(packageName) == true
    }

    /**
     * Check if current time is within bedtime hours
     * @param currentTimeHHMM current time in HH:MM format (24-hour)
     * @return true if bedtime is enabled and current time is within bedtime range
     */
    fun isInBedtime(currentTimeHHMM: String): Boolean {
        if (!isActive || !bedtimeEnabled || bedtimeStart == null || bedtimeEnd == null) {
            return false
        }

        val current = timeToMinutes(currentTimeHHMM)
        val start = timeToMinutes(bedtimeStart)
        val end = timeToMinutes(bedtimeEnd)

        return if (start <= end) {
            // Normal range (e.g., 09:00 - 17:00)
            current >= start && current < end
        } else {
            // Overnight range (e.g., 22:00 - 06:00)
            current >= start || current < end
        }
    }

    /**
     * Check if current time is within study time hours
     * @param currentTimeHHMM current time in HH:MM format (24-hour)
     * @return true if study time is enabled and current time is within study time range
     */
    fun isInStudyTime(currentTimeHHMM: String): Boolean {
        if (!isActive || !studyTimeEnabled || studyTimeStart == null || studyTimeEnd == null) {
            return false
        }

        val current = timeToMinutes(currentTimeHHMM)
        val start = timeToMinutes(studyTimeStart)
        val end = timeToMinutes(studyTimeEnd)

        return if (start <= end) {
            // Normal range (e.g., 14:00 - 17:00)
            current >= start && current < end
        } else {
            // Overnight range (unlikely for study time, but supported)
            current >= start || current < end
        }
    }

    /**
     * Check if an app is allowed during study time
     * @param packageName the app package name
     * @return true if app is in the study time allowed apps list
     */
    fun isAllowedDuringStudyTime(packageName: String): Boolean {
        if (!studyTimeEnabled) return true
        // If no allowed apps specified, block all apps during study time
        val allowedApps = studyTimeAllowedApps ?: return false
        if (allowedApps.isEmpty()) return false
        return allowedApps.contains(packageName)
    }

    /**
     * Get remaining daily time in seconds
     * @param usedTimeSeconds time already used today
     * @return remaining time in seconds, or null if no limit set (null or 0 means no limit)
     */
    fun getRemainingDailyTime(usedTimeSeconds: Int): Int? {
        val limit = dailyLimit ?: return null
        if (limit <= 0) return null // 0 means no limit
        return (limit - usedTimeSeconds).coerceAtLeast(0)
    }

    /**
     * Get remaining time for a specific app in seconds
     * @param packageName the app package name
     * @param usedTimeSeconds time already used for this app
     * @return remaining time in seconds, or null if no limit for this app (null or 0 means no limit)
     */
    fun getRemainingAppTime(packageName: String, usedTimeSeconds: Int): Int? {
        val limit = appLimits?.get(packageName) ?: return null
        if (limit <= 0) return null // 0 means no limit
        return (limit - usedTimeSeconds).coerceAtLeast(0)
    }

    private fun timeToMinutes(timeHHMM: String): Int {
        val parts = timeHHMM.split(":")
        if (parts.size != 2) return 0
        val hours = parts[0].toIntOrNull() ?: 0
        val minutes = parts[1].toIntOrNull() ?: 0
        return hours * 60 + minutes
    }
}

/**
 * Request to add/update a single app limit
 */
data class AppLimitRequest(
    @SerializedName("package_name")
    val packageName: String,
    @SerializedName("limit_seconds")
    val limitSeconds: Int
)

// ========== Social Media Platform Models ==========

/**
 * Predefined social media platforms with their associated domains
 */
enum class SocialMediaPlatform(
    val displayName: String,
    val domains: List<String>
) {
    FACEBOOK(
        displayName = "Facebook",
        domains = listOf("facebook.com", "fb.com", "fbcdn.net", "fbsbx.com", "facebook.net", "messenger.com", "m.me")
    ),
    INSTAGRAM(
        displayName = "Instagram",
        domains = listOf("instagram.com", "cdninstagram.com", "ig.me")
    ),
    TWITTER(
        displayName = "X (Twitter)",
        domains = listOf("twitter.com", "x.com", "twimg.com", "t.co", "tweetdeck.com")
    ),
    TIKTOK(
        displayName = "TikTok",
        domains = listOf("tiktok.com", "tiktokcdn.com", "tiktokv.com", "musical.ly", "byteoversea.com", "ibytedtos.com", "tiktokcdn-us.com")
    ),
    SNAPCHAT(
        displayName = "Snapchat",
        domains = listOf("snapchat.com", "snap.com", "snapkit.com", "sc-cdn.net", "snapads.com")
    ),
    YOUTUBE(
        displayName = "YouTube",
        domains = listOf("youtube.com", "youtu.be", "ytimg.com", "googlevideo.com", "yt.be")
    ),
    REDDIT(
        displayName = "Reddit",
        domains = listOf("reddit.com", "redd.it", "redditstatic.com", "redditmedia.com")
    ),
    DISCORD(
        displayName = "Discord",
        domains = listOf("discord.com", "discord.gg", "discordapp.com", "discordapp.net")
    ),
    WHATSAPP(
        displayName = "WhatsApp",
        domains = listOf("whatsapp.com", "whatsapp.net", "wa.me")
    ),
    TELEGRAM(
        displayName = "Telegram",
        domains = listOf("telegram.org", "telegram.me", "t.me")
    ),
    PINTEREST(
        displayName = "Pinterest",
        domains = listOf("pinterest.com", "pinimg.com")
    ),
    LINKEDIN(
        displayName = "LinkedIn",
        domains = listOf("linkedin.com", "licdn.com")
    );

    companion object {
        /**
         * Get all domains for a list of platforms
         */
        fun getDomainsForPlatforms(platforms: List<SocialMediaPlatform>): List<String> {
            return platforms.flatMap { it.domains }
        }

        /**
         * Determine which platforms are blocked based on blocked domains list
         */
        fun getBlockedPlatforms(blockedDomains: List<String>?): Set<SocialMediaPlatform> {
            if (blockedDomains.isNullOrEmpty()) return emptySet()
            val blockedSet = blockedDomains.map { it.lowercase() }.toSet()
            return values().filter { platform ->
                // A platform is blocked if any of its domains are in the blocked list
                platform.domains.any { domain -> blockedSet.contains(domain.lowercase()) }
            }.toSet()
        }
    }
}
