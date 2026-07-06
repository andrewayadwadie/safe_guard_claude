package com.safeguard.parentalcontrol.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.safeguard.parentalcontrol.data.model.*
import com.safeguard.parentalcontrol.data.remote.ApiService
import com.safeguard.parentalcontrol.data.remote.NetworkResult
import com.safeguard.parentalcontrol.data.remote.safeApiCall
import com.safeguard.parentalcontrol.util.PreferencesManager
import com.safeguard.parentalcontrol.util.TextHasher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

// DataStore for alert cooldown tracking
private val Context.alertCooldownStore by preferencesDataStore(name = "alert_cooldown")

/**
 * Repository for alert operations.
 *
 * Features:
 * - Creates and manages alerts for inappropriate content detection
 * - Implements per-category cooldown to prevent alert spam
 * - Uses text hashing for deduplication (no plaintext storage)
 * - Supports daily alert limits per category
 *
 * Privacy:
 * - Never includes actual text content in alerts
 * - Only category, app info, and metadata are transmitted
 * - Uses SHA-256 hashing for content deduplication
 */
@Singleton
class AlertRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val apiService: ApiService,
    private val preferencesManager: PreferencesManager,
    private val textHasher: TextHasher
) {
    companion object {
        private const val TAG = "AlertRepository"

        // Cooldown periods per category (in milliseconds)
        private val CATEGORY_COOLDOWN_MS = mapOf(
            "self_harm" to 60_000L,        // 1 minute - critical, needs quick alerts
            "predator_grooming" to 60_000L, // 1 minute - critical safety concern
            "violence" to 120_000L,         // 2 minutes
            "sexual" to 120_000L,           // 2 minutes
            "bullying" to 180_000L,         // 3 minutes
            "profanity" to 300_000L,        // 5 minutes - less critical
            "drugs" to 180_000L,            // 3 minutes
            "custom" to 180_000L            // 3 minutes for custom blacklist
        )

        // Daily alert limits per category
        private val DAILY_ALERT_LIMITS = mapOf(
            "self_harm" to 20,       // Higher limit for critical categories
            "predator_grooming" to 20,
            "violence" to 15,
            "sexual" to 15,
            "bullying" to 15,
            "profanity" to 10,       // Lower limit for less critical
            "drugs" to 15,
            "custom" to 15
        )

        // Default values
        private const val DEFAULT_COOLDOWN_MS = 180_000L // 3 minutes
        private const val DEFAULT_DAILY_LIMIT = 15
    }
    /**
     * Create a new alert (from device)
     */
    suspend fun createAlert(
        alertType: AlertType,
        severity: AlertSeverity,
        title: String,
        message: String,
        metadata: Map<String, Any>? = null,
        evidenceData: String? = null
    ): NetworkResult<Alert> = withContext(Dispatchers.IO) {
        val deviceId = preferencesManager.deviceDbId
        val deviceToken = preferencesManager.deviceId

        if (deviceId == -1 || deviceToken == null) {
            return@withContext NetworkResult.Error("Device not registered")
        }

        val request = AlertCreate(
            deviceId = deviceId,
            alertType = alertType,
            severity = severity,
            title = title,
            message = message,
            metadata = metadata,
            evidenceData = evidenceData
        )

        val result = safeApiCall { apiService.createAlert(request, deviceToken) }

        result.onSuccess {
            Timber.d("Alert created: $title (${alertType.name})")
        }

        result
    }

    /**
     * Get alerts with optional filters (for parent)
     */
    suspend fun getAlerts(
        deviceId: Int? = null,
        alertType: AlertType? = null,
        severity: AlertSeverity? = null,
        isRead: Boolean? = null,
        isDismissed: Boolean? = null,
        days: Int = 30,
        limit: Int = 50
    ): NetworkResult<List<Alert>> = withContext(Dispatchers.IO) {
        safeApiCall {
            apiService.getAlerts(
                deviceId = deviceId,
                alertType = alertType?.name?.lowercase(),
                severity = severity?.name?.lowercase(),
                isRead = isRead,
                isDismissed = isDismissed,
                days = days,
                limit = limit
            )
        }
    }

    /**
     * Get alert statistics
     */
    suspend fun getAlertStats(
        deviceId: Int? = null,
        days: Int = 30
    ): NetworkResult<AlertStats> = withContext(Dispatchers.IO) {
        safeApiCall { apiService.getAlertStats(deviceId, days) }
    }

    /**
     * Get specific alert
     */
    suspend fun getAlert(alertId: Int): NetworkResult<Alert> = withContext(Dispatchers.IO) {
        safeApiCall { apiService.getAlert(alertId) }
    }

    /**
     * Mark alert as read
     */
    suspend fun markAsRead(alertId: Int): NetworkResult<Alert> = withContext(Dispatchers.IO) {
        val request = AlertUpdateRequest(isRead = true)
        safeApiCall { apiService.updateAlert(alertId, request) }
    }

    /**
     * Dismiss alert
     */
    suspend fun dismissAlert(alertId: Int): NetworkResult<Alert> = withContext(Dispatchers.IO) {
        val request = AlertUpdateRequest(isDismissed = true)
        safeApiCall { apiService.updateAlert(alertId, request) }
    }

    /**
     * Mark all alerts as read
     */
    suspend fun markAllAsRead(deviceId: Int? = null): NetworkResult<MessageResponse> = withContext(Dispatchers.IO) {
        safeApiCall { apiService.markAllAlertsRead(deviceId) }
    }

    /**
     * Delete alert
     */
    suspend fun deleteAlert(alertId: Int): NetworkResult<MessageResponse> = withContext(Dispatchers.IO) {
        safeApiCall { apiService.deleteAlert(alertId) }
    }

    // ========== Helper functions for creating specific alert types ==========

    /**
     * Create content block alert
     */
    suspend fun createContentBlockAlert(
        domain: String,
        reason: String
    ): NetworkResult<Alert> {
        return createAlert(
            alertType = AlertType.CONTENT_BLOCK,
            severity = AlertSeverity.MEDIUM,
            title = "Content Blocked",
            message = "Blocked access to $domain",
            metadata = mapOf(
                "domain" to domain,
                "reason" to reason,
                "timestamp" to System.currentTimeMillis()
            )
        )
    }

    /**
     * Create screen time limit alert
     */
    suspend fun createScreenTimeLimitAlert(
        limitSeconds: Int,
        usageSeconds: Int
    ): NetworkResult<Alert> {
        return createAlert(
            alertType = AlertType.SCREEN_TIME_LIMIT,
            severity = AlertSeverity.MEDIUM,
            title = "Screen Time Limit Reached",
            message = "Daily screen time limit of ${limitSeconds / 3600} hours has been reached",
            metadata = mapOf(
                "limit" to limitSeconds,
                "usage" to usageSeconds,
                "timestamp" to System.currentTimeMillis()
            )
        )
    }

    /**
     * Create inappropriate image alert
     */
    suspend fun createInappropriateImageAlert(
        category: String,
        confidence: Float,
        sourceApp: String? = null,
        evidenceBase64: String? = null
    ): NetworkResult<Alert> {
        return createAlert(
            alertType = AlertType.INAPPROPRIATE_IMAGE,
            severity = mapImageConfidenceToSeverity(confidence),
            title = "Inappropriate Image Detected",
            message = "NSFW content detected with ${(confidence * 100).toInt()}% confidence",
            metadata = mapOf(
                "category" to category,
                "confidence" to confidence,
                "source_app" to (sourceApp ?: "unknown"),
                "timestamp" to System.currentTimeMillis()
            ),
            evidenceData = evidenceBase64
        )
    }

    /**
     * Create inappropriate text alert with enhanced features.
     *
     * Features:
     * - Category-based severity mapping
     * - Per-category cooldown to prevent spam
     * - Daily alert limits per category
     * - Text deduplication via hashing
     * - Privacy-safe (no actual text content transmitted)
     *
     * @param appPackage Package name of the app where text was detected
     * @param appName Human-readable app name
     * @param reason Brief description of why content was flagged
     * @param categories List of detected content categories (e.g., "self_harm", "violence")
     * @param confidence Detection confidence (0.0 to 1.0)
     * @param textForDedup Optional text for deduplication (hashed, never stored or transmitted)
     */
    suspend fun createInappropriateTextAlert(
        appPackage: String,
        appName: String?,
        reason: String,
        categories: List<String> = emptyList(),
        confidence: Float = 0.0f,
        textForDedup: String? = null,
        severityLabel: String? = null
    ): NetworkResult<Alert> = withContext(Dispatchers.IO) {
        val primaryCategory = categories.firstOrNull() ?: "unknown"

        // Check deduplication first (most efficient filter)
        if (textForDedup != null && !textHasher.shouldProcessContent(textForDedup, appPackage)) {
            Timber.d("$TAG: Skipping duplicate content alert for $appPackage (category: $primaryCategory)")
            return@withContext NetworkResult.Error("Duplicate content - alert skipped")
        }

        // Check cooldown for this category
        if (!checkCooldown(primaryCategory)) {
            Timber.d("$TAG: Cooldown active for category $primaryCategory")
            return@withContext NetworkResult.Error("Cooldown active for category: $primaryCategory")
        }

        // Check daily limit for this category
        if (!checkDailyLimit(primaryCategory)) {
            Timber.w("$TAG: Daily limit reached for category $primaryCategory")
            return@withContext NetworkResult.Error("Daily limit reached for category: $primaryCategory")
        }

        // Severity: prefer the gating-provided label (Stage-2 AI path); otherwise derive
        // from the primary category (Stage-1 regex path, custom blacklist, or fallback).
        val severity = severityLabel
            ?.let { runCatching { AlertSeverity.valueOf(it.uppercase()) }.getOrNull() }
            ?: mapCategoryToSeverity(primaryCategory)

        // Create privacy-safe metadata (NO actual text content)
        val metadata = buildMap<String, Any> {
            put("package_name", appPackage)
            put("app_name", appName ?: "Unknown")
            put("categories", categories)
            put("primary_category", primaryCategory)
            put("confidence", confidence)
            put("reason", sanitizeReason(reason)) // Sanitize to remove any potential text snippets
            put("timestamp", System.currentTimeMillis())
            put("detection_method", if (confidence > 0.5f) "ml_model" else "pattern_match")
        }

        // Create human-readable title and message based on category
        val (title, message) = getCategoryAlertContent(primaryCategory, appName ?: appPackage)

        Timber.i("$TAG: Creating text alert - app=$appPackage, category=$primaryCategory, severity=${severity.name}, confidence=$confidence")

        // Record this alert for cooldown and limit tracking
        recordAlert(primaryCategory)

        createAlert(
            alertType = AlertType.INAPPROPRIATE_TEXT,
            severity = severity,
            title = title,
            message = message,
            metadata = metadata
        )
    }

    /**
     * Map NSFW image-detection confidence to alert severity (ISSUE-025).
     * Previously every image alert was hardcoded CRITICAL, so a low-confidence
     * detection (e.g. 0.45) escalated to a "critical" parent notification. Tie
     * severity to confidence so only high-confidence detections are critical.
     */
    private fun mapImageConfidenceToSeverity(confidence: Float): AlertSeverity {
        return when {
            confidence >= 0.85f -> AlertSeverity.CRITICAL
            confidence >= 0.60f -> AlertSeverity.HIGH
            else -> AlertSeverity.MEDIUM
        }
    }

    /**
     * Map content category to alert severity.
     * Critical categories get CRITICAL severity, others get HIGH or MEDIUM.
     */
    private fun mapCategoryToSeverity(category: String): AlertSeverity {
        return when (category.lowercase()) {
            "self_harm" -> AlertSeverity.CRITICAL // Immediate parent notification
            "predator_grooming" -> AlertSeverity.CRITICAL
            "violence" -> AlertSeverity.HIGH
            "sexual" -> AlertSeverity.HIGH
            "bullying" -> AlertSeverity.HIGH
            "drugs" -> AlertSeverity.MEDIUM
            "profanity" -> AlertSeverity.MEDIUM
            "custom" -> AlertSeverity.HIGH // Custom blacklist words are important
            else -> AlertSeverity.MEDIUM
        }
    }

    /**
     * Get category-specific alert title and message.
     * Provides context without exposing actual content.
     */
    private fun getCategoryAlertContent(category: String, appName: String): Pair<String, String> {
        return when (category.lowercase()) {
            "self_harm" -> Pair(
                "Self-Harm Content Detected",
                "Content related to self-harm or suicide was detected in $appName. " +
                    "Please check on your child and consider having a supportive conversation."
            )
            "predator_grooming" -> Pair(
                "Suspicious Contact Pattern Detected",
                "Potentially concerning communication patterns were detected in $appName. " +
                    "This may indicate inappropriate contact attempts."
            )
            "violence" -> Pair(
                "Violent Content Detected",
                "Content containing violence or threats was detected in $appName."
            )
            "sexual" -> Pair(
                "Sexual Content Detected",
                "Sexually explicit or inappropriate content was detected in $appName."
            )
            "bullying" -> Pair(
                "Cyberbullying Detected",
                "Content related to bullying or harassment was detected in $appName."
            )
            "drugs" -> Pair(
                "Drug-Related Content Detected",
                "Content related to drugs or substance abuse was detected in $appName."
            )
            "profanity" -> Pair(
                "Inappropriate Language Detected",
                "Profane or inappropriate language was detected in $appName."
            )
            "custom" -> Pair(
                "Blacklisted Word Detected",
                "A word from your custom blacklist was detected in $appName."
            )
            else -> Pair(
                "Inappropriate Text Detected",
                "Potentially inappropriate content was detected in $appName."
            )
        }
    }

    /**
     * Sanitize reason string to ensure no actual text content is included.
     * Only allows category descriptions, not text snippets.
     */
    private fun sanitizeReason(reason: String): String {
        // If reason contains "detected:" it might have text content, strip it
        val colonIndex = reason.indexOf("detected:")
        return if (colonIndex > 0) {
            reason.substring(0, colonIndex + "detected".length)
        } else {
            // Limit reason length and remove any potential text snippets
            reason.take(100)
        }
    }

    /**
     * Check if cooldown has passed for this category.
     */
    private suspend fun checkCooldown(category: String): Boolean {
        val cooldownKey = longPreferencesKey("cooldown_$category")
        val lastAlertTime = context.alertCooldownStore.data.first()[cooldownKey] ?: 0L
        val cooldownMs = CATEGORY_COOLDOWN_MS[category] ?: DEFAULT_COOLDOWN_MS
        val now = System.currentTimeMillis()

        val cooldownPassed = (now - lastAlertTime) >= cooldownMs

        if (!cooldownPassed) {
            val remainingSeconds = (cooldownMs - (now - lastAlertTime)) / 1000
            Timber.d("$TAG: Cooldown check for $category: ${remainingSeconds}s remaining")
        }

        return cooldownPassed
    }

    /**
     * Check if daily limit has been reached for this category.
     */
    private suspend fun checkDailyLimit(category: String): Boolean {
        val today = getTodayKey()
        val countKey = intPreferencesKey("count_${category}_$today")
        val currentCount = context.alertCooldownStore.data.first()[countKey] ?: 0
        val limit = DAILY_ALERT_LIMITS[category] ?: DEFAULT_DAILY_LIMIT

        val withinLimit = currentCount < limit

        if (!withinLimit) {
            Timber.w("$TAG: Daily limit reached for $category: $currentCount/$limit")
        }

        return withinLimit
    }

    /**
     * Record that an alert was sent for tracking cooldown and limits.
     */
    private suspend fun recordAlert(category: String) {
        val now = System.currentTimeMillis()
        val today = getTodayKey()
        val cooldownKey = longPreferencesKey("cooldown_$category")
        val countKey = intPreferencesKey("count_${category}_$today")

        context.alertCooldownStore.edit { prefs ->
            prefs[cooldownKey] = now
            prefs[countKey] = (prefs[countKey] ?: 0) + 1
        }

        Timber.d("$TAG: Recorded alert for category $category")
    }

    /**
     * Get a key representing today's date for daily limit tracking.
     */
    private fun getTodayKey(): String {
        val calendar = Calendar.getInstance()
        return "${calendar.get(Calendar.YEAR)}_${calendar.get(Calendar.DAY_OF_YEAR)}"
    }

    /**
     * Reset daily counters (call at midnight or on app start for a new day).
     */
    suspend fun resetDailyCountersIfNeeded() {
        val today = getTodayKey()
        val lastResetKey = stringPreferencesKey("last_reset_day")
        val lastReset = context.alertCooldownStore.data.first()[lastResetKey]

        if (lastReset != today) {
            Timber.d("$TAG: Resetting daily alert counters (new day)")
            context.alertCooldownStore.edit { prefs ->
                // Remove old count keys (they'll be stale anyway)
                prefs.asMap().keys
                    .filterIsInstance<androidx.datastore.preferences.core.Preferences.Key<*>>()
                    .filter { it.name.startsWith("count_") && !it.name.endsWith(today) }
                    .forEach { prefs.remove(it) }

                prefs[lastResetKey] = today
            }
        }
    }

    /**
     * Get current alert statistics for a category.
     */
    suspend fun getCategoryAlertStats(category: String): CategoryAlertStats {
        val today = getTodayKey()
        val countKey = intPreferencesKey("count_${category}_$today")
        val cooldownKey = longPreferencesKey("cooldown_$category")

        val prefs = context.alertCooldownStore.data.first()
        val count = prefs[countKey] ?: 0
        val lastAlert = prefs[cooldownKey] ?: 0L
        val limit = DAILY_ALERT_LIMITS[category] ?: DEFAULT_DAILY_LIMIT
        val cooldownMs = CATEGORY_COOLDOWN_MS[category] ?: DEFAULT_COOLDOWN_MS

        return CategoryAlertStats(
            category = category,
            alertsToday = count,
            dailyLimit = limit,
            lastAlertTime = lastAlert,
            cooldownMs = cooldownMs
        )
    }

    /**
     * Create app blocked alert
     */
    suspend fun createAppBlockedAlert(
        packageName: String,
        appName: String?
    ): NetworkResult<Alert> {
        return createAlert(
            alertType = AlertType.APP_BLOCKED,
            severity = AlertSeverity.LOW,
            title = "App Blocked",
            message = "${appName ?: packageName} was blocked",
            metadata = mapOf(
                "package_name" to packageName,
                "app_name" to (appName ?: "Unknown"),
                "timestamp" to System.currentTimeMillis()
            )
        )
    }
}

/**
 * Statistics for alerts in a specific category.
 */
data class CategoryAlertStats(
    val category: String,
    val alertsToday: Int,
    val dailyLimit: Int,
    val lastAlertTime: Long,
    val cooldownMs: Long
) {
    /**
     * Check if more alerts can be sent today.
     */
    val canSendMore: Boolean
        get() = alertsToday < dailyLimit

    /**
     * Check if cooldown has passed.
     */
    fun isCooldownPassed(): Boolean {
        return System.currentTimeMillis() - lastAlertTime >= cooldownMs
    }

    /**
     * Get remaining cooldown time in seconds.
     */
    fun getRemainingCooldownSeconds(): Long {
        val elapsed = System.currentTimeMillis() - lastAlertTime
        val remaining = cooldownMs - elapsed
        return if (remaining > 0) remaining / 1000 else 0
    }

    /**
     * Get remaining alerts for today.
     */
    val remainingAlerts: Int
        get() = maxOf(0, dailyLimit - alertsToday)
}
