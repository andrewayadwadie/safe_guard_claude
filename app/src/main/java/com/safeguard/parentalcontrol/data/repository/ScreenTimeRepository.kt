package com.safeguard.parentalcontrol.data.repository

import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import com.safeguard.parentalcontrol.data.model.*
import com.safeguard.parentalcontrol.data.remote.ApiService
import com.safeguard.parentalcontrol.data.remote.NetworkResult
import com.safeguard.parentalcontrol.data.remote.safeApiCall
import com.safeguard.parentalcontrol.util.PreferencesManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for screen time tracking operations
 */
@Singleton
class ScreenTimeRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val apiService: ApiService,
    private val preferencesManager: PreferencesManager
) {
    private val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

    /**
     * Update screen time for today
     */
    suspend fun updateScreenTime(
        totalScreenTime: Int,
        unlocksCount: Int
    ): NetworkResult<ScreenTimeLog> = withContext(Dispatchers.IO) {
        val deviceId = preferencesManager.deviceDbId
        if (deviceId == -1) {
            return@withContext NetworkResult.Error("Device not registered")
        }

        val today = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.time

        val request = ScreenTimeUpdate(
            date = today,
            totalScreenTime = totalScreenTime,
            unlocksCount = unlocksCount
        )

        val result = safeApiCall { apiService.updateScreenTime(deviceId, request) }

        result.onSuccess {
            Timber.d("Screen time updated: $totalScreenTime seconds, $unlocksCount unlocks")
        }

        result
    }

    /**
     * Sync screen time from device usage stats
     * IMPORTANT: Only CHILD devices should call this method
     */
    suspend fun syncScreenTimeFromDevice(): NetworkResult<ScreenTimeLog> = withContext(Dispatchers.IO) {
        // Safety check: Only child devices should sync screen time
        if (preferencesManager.isParent) {
            Timber.w("syncScreenTimeFromDevice called on PARENT device - ignoring")
            return@withContext NetworkResult.Error("Parent devices should not sync screen time")
        }

        val (totalTime, unlockCount) = getTodayScreenTimeData()
        Timber.d("Syncing screen time: totalTime=${totalTime}s, unlocks=$unlockCount")
        updateScreenTime(totalTime, unlockCount)
    }

    /**
     * Get screen time logs for a device
     */
    suspend fun getScreenTimeLogs(
        deviceId: Int,
        days: Int = 7
    ): NetworkResult<List<ScreenTimeLog>> = withContext(Dispatchers.IO) {
        safeApiCall { apiService.getScreenTimeLogs(deviceId, days) }
    }

    /**
     * Get screen time statistics
     */
    suspend fun getScreenTimeStats(
        deviceId: Int,
        days: Int = 30
    ): NetworkResult<ScreenTimeStats> = withContext(Dispatchers.IO) {
        safeApiCall { apiService.getScreenTimeStats(deviceId, days) }
    }

    /**
     * Update app usage batch
     */
    suspend fun updateAppUsage(
        apps: List<AppUsageUpdate>
    ): NetworkResult<MessageResponse> = withContext(Dispatchers.IO) {
        val deviceId = preferencesManager.deviceDbId
        if (deviceId == -1) {
            return@withContext NetworkResult.Error("Device not registered")
        }

        Timber.d("Syncing ${apps.size} apps usage to backend for device $deviceId")
        apps.take(3).forEach { app ->
            Timber.d("  - ${app.packageName}: ${app.usageTime}s, date=${app.date}, lastUsed=${app.lastUsed}")
        }

        // Report the device's current UTC offset so the backend rolls "today" over at
        // this device's local midnight (not the server's timezone).
        val offsetMinutes = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60000
        val request = AppUsageBatch(apps = apps, utcOffsetMinutes = offsetMinutes)
        val result = safeApiCall { apiService.updateAppUsage(deviceId, request) }

        result.onSuccess {
            Timber.d("App usage updated successfully: ${apps.size} apps")
        }.onError { message, code ->
            Timber.e("App usage sync failed: code=$code, message=$message")
        }

        result
    }

    /**
     * Sync app usage from device
     * IMPORTANT: Only CHILD devices should call this method
     */
    suspend fun syncAppUsageFromDevice(): NetworkResult<MessageResponse> = withContext(Dispatchers.IO) {
        // Safety check: Only child devices should sync app usage
        if (preferencesManager.isParent) {
            Timber.w("syncAppUsageFromDevice called on PARENT device - ignoring")
            return@withContext NetworkResult.Error("Parent devices should not sync app usage")
        }

        val apps = getTodayAppUsageData()
        if (apps.isEmpty()) {
            Timber.d("No app usage to sync")
            return@withContext NetworkResult.Success(MessageResponse("No app usage to sync"))
        }
        Timber.d("Syncing ${apps.size} apps usage")
        updateAppUsage(apps)
    }

    /**
     * Get app usage logs
     */
    suspend fun getAppUsageLogs(
        deviceId: Int,
        days: Int = 7,
        packageName: String? = null
    ): NetworkResult<List<AppUsageLog>> = withContext(Dispatchers.IO) {
        safeApiCall { apiService.getAppUsageLogs(deviceId, days, packageName) }
    }

    /**
     * Get today's app usage
     */
    suspend fun getTodayAppUsage(deviceId: Int): NetworkResult<List<AppUsageLog>> = withContext(Dispatchers.IO) {
        safeApiCall { apiService.getTodayAppUsage(deviceId) }
    }

    /**
     * Get today's total screen time in seconds
     * This is a convenience method for enforcement checks
     */
    fun getTodayScreenTimeSeconds(): Int {
        return getTodayScreenTimeData().first
    }

    /**
     * Get today's screen time data from UsageStatsManager
     * Returns (0, 0) if permission is not granted to prevent crashes
     *
     * Uses event-based calculation for accurate results across day boundaries.
     * INTERVAL_DAILY buckets don't always align with midnight, so we use
     * queryEvents() to calculate exact foreground time since midnight.
     *
     * IMPORTANT: This calculates DEVICE screen-on time, not sum of all app foreground times.
     * When multiple apps have overlapping foreground times, we only count the time once.
     * This prevents the issue where screen time shows 28+ hours due to overlapping app usage.
     */
    fun getTodayScreenTimeData(): Pair<Int, Int> {
        // Check permission before accessing UsageStatsManager
        if (!hasUsageStatsPermission()) {
            Timber.w("Usage stats permission not granted, returning zero data")
            return Pair(0, 0)
        }

        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startTime = calendar.timeInMillis
        val endTime = System.currentTimeMillis()

        return try {
            // Use event-based calculation for accurate today's screen time
            var totalTimeMs = 0L
            var unlockCount = 0

            // Track which apps are currently in foreground (screen is ON when any app is in foreground)
            val appsInForeground = mutableSetOf<String>()
            // Track when the screen became active (first app went to foreground)
            var screenOnTime: Long? = null

            try {
                val events = usageStatsManager.queryEvents(startTime, endTime)
                val event = android.app.usage.UsageEvents.Event()

                while (events.hasNextEvent()) {
                    events.getNextEvent(event)

                    when (event.eventType) {
                        // App moved to foreground
                        android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED,
                        android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                            // If this is the first app going to foreground, screen is now ON
                            if (appsInForeground.isEmpty() && screenOnTime == null) {
                                screenOnTime = maxOf(event.timeStamp, startTime)
                            }
                            appsInForeground.add(event.packageName)
                        }
                        // App moved to background
                        android.app.usage.UsageEvents.Event.ACTIVITY_PAUSED,
                        android.app.usage.UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                            appsInForeground.remove(event.packageName)
                            // If no apps in foreground, screen is now OFF
                            if (appsInForeground.isEmpty() && screenOnTime != null) {
                                totalTimeMs += event.timeStamp - screenOnTime
                                screenOnTime = null
                            }
                        }
                        // Screen unlock
                        android.app.usage.UsageEvents.Event.SCREEN_INTERACTIVE -> {
                            unlockCount++
                        }
                    }
                }

                // Add time if screen is still ON (apps still in foreground)
                if (screenOnTime != null) {
                    totalTimeMs += endTime - screenOnTime
                }

            } catch (e: Exception) {
                Timber.e(e, "Error calculating screen time from events, falling back to stats")
                // Fallback: Use the longest single app's foreground time as approximation
                // This is less accurate but won't over-count
                val stats = usageStatsManager.queryUsageStats(
                    UsageStatsManager.INTERVAL_BEST,
                    startTime,
                    endTime
                ) ?: emptyList()

                // Take the max foreground time of any single app as a lower bound estimate
                // This avoids the over-counting issue with summing
                totalTimeMs = stats
                    .filter { it.lastTimeUsed >= startTime }
                    .maxOfOrNull { stat ->
                        if (stat.firstTimeStamp >= startTime) {
                            stat.totalTimeInForeground
                        } else {
                            // Stat spans multiple days - use lastTimeUsed ratio heuristic
                            val statDuration = stat.lastTimeStamp - stat.firstTimeStamp
                            if (statDuration > 0) {
                                val todayPortion = (endTime - startTime).toDouble() / statDuration
                                (stat.totalTimeInForeground * todayPortion.coerceAtMost(1.0)).toLong()
                            } else {
                                stat.totalTimeInForeground
                            }
                        }
                    } ?: 0L
            }

            val totalTimeSec = (totalTimeMs / 1000).toInt()
            // Cap at 24 hours (86400 seconds) as a safety check
            val cappedTimeSec = totalTimeSec.coerceAtMost(86400)
            if (totalTimeSec > 86400) {
                Timber.w("Screen time calculation exceeded 24 hours (${totalTimeSec}s), capping to 86400s")
            }
            Timber.d("Today's screen time: ${cappedTimeSec}s, unlocks: $unlockCount (since midnight)")
            Pair(cappedTimeSec, unlockCount)
        } catch (e: SecurityException) {
            Timber.e(e, "Security exception accessing usage stats - permission may have been revoked")
            Pair(0, 0)
        } catch (e: Exception) {
            Timber.e(e, "Error getting screen time data")
            Pair(0, 0)
        }
    }

    /**
     * Get today's app usage data from UsageStatsManager
     * Returns empty list if permission is not granted to prevent crashes
     *
     * Uses event-based calculation for accurate results across day boundaries.
     */
    fun getTodayAppUsageData(): List<AppUsageUpdate> {
        // Check permission before accessing UsageStatsManager
        if (!hasUsageStatsPermission()) {
            Timber.w("Usage stats permission not granted, returning empty app usage data")
            return emptyList()
        }

        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startTime = calendar.timeInMillis
        val endTime = System.currentTimeMillis()
        val today = calendar.time

        return try {
            // Use event-based calculation for accurate per-app screen time
            val packageUsageMs = mutableMapOf<String, Long>()
            val packageLastUsed = mutableMapOf<String, Long>()
            val packageForegroundStart = mutableMapOf<String, Long>()

            try {
                val events = usageStatsManager.queryEvents(startTime, endTime)
                val event = android.app.usage.UsageEvents.Event()

                while (events.hasNextEvent()) {
                    events.getNextEvent(event)

                    when (event.eventType) {
                        // App moved to foreground
                        android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED,
                        android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                            packageForegroundStart[event.packageName] = event.timeStamp
                            // Update last used time
                            packageLastUsed[event.packageName] = event.timeStamp
                        }
                        // App moved to background
                        android.app.usage.UsageEvents.Event.ACTIVITY_PAUSED,
                        android.app.usage.UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                            val foregroundStart = packageForegroundStart[event.packageName]
                            if (foregroundStart != null && foregroundStart >= startTime) {
                                val duration = event.timeStamp - foregroundStart
                                packageUsageMs[event.packageName] =
                                    (packageUsageMs[event.packageName] ?: 0L) + duration
                            }
                            packageForegroundStart.remove(event.packageName)
                            // Update last used time
                            packageLastUsed[event.packageName] = event.timeStamp
                        }
                    }
                }

                // Add time for apps still in foreground
                packageForegroundStart.forEach { (packageName, foregroundStart) ->
                    if (foregroundStart >= startTime) {
                        val duration = endTime - foregroundStart
                        packageUsageMs[packageName] =
                            (packageUsageMs[packageName] ?: 0L) + duration
                        packageLastUsed[packageName] = endTime
                    }
                }

                // Convert to AppUsageUpdate list
                packageUsageMs
                    .filter { it.value > 0 }
                    .map { (packageName, usageMs) ->
                        val appName = getAppName(packageName)
                        val lastUsedMs = packageLastUsed[packageName] ?: 0L

                        AppUsageUpdate(
                            packageName = packageName,
                            appName = appName,
                            date = today,
                            usageTime = (usageMs / 1000).toInt(),
                            lastUsed = if (lastUsedMs > 0) Date(lastUsedMs) else null
                        )
                    }
                    .sortedByDescending { it.usageTime }

            } catch (e: Exception) {
                Timber.e(e, "Error calculating app usage from events, falling back to stats")
                // Fallback to stats-based calculation with filtering
                val stats = usageStatsManager.queryUsageStats(
                    UsageStatsManager.INTERVAL_BEST,
                    startTime,
                    endTime
                ) ?: emptyList()

                // Only include apps actually used today (lastTimeUsed >= startTime)
                stats
                    .filter { it.totalTimeInForeground > 0 && it.lastTimeUsed >= startTime }
                    .groupBy { it.packageName }
                    .map { (packageName, statsList) ->
                        val lastUsedMs = statsList.maxOfOrNull { it.lastTimeUsed } ?: 0L
                        val appName = getAppName(packageName)

                        // Estimate today's usage for stats that span multiple days
                        val totalUsageMs = statsList.maxOfOrNull { stat ->
                            if (stat.firstTimeStamp >= startTime) {
                                stat.totalTimeInForeground
                            } else {
                                // Estimate today's portion
                                val statDuration = stat.lastTimeStamp - stat.firstTimeStamp
                                if (statDuration > 0) {
                                    val todayPortion = (endTime - startTime).toDouble() / statDuration
                                    (stat.totalTimeInForeground * todayPortion.coerceAtMost(1.0)).toLong()
                                } else {
                                    stat.totalTimeInForeground
                                }
                            }
                        } ?: 0L

                        AppUsageUpdate(
                            packageName = packageName,
                            appName = appName,
                            date = today,
                            usageTime = (totalUsageMs / 1000).toInt(),
                            lastUsed = if (lastUsedMs > 0) Date(lastUsedMs) else null
                        )
                    }
                    .sortedByDescending { it.usageTime }
            }
        } catch (e: SecurityException) {
            Timber.e(e, "Security exception accessing usage stats - permission may have been revoked")
            emptyList()
        } catch (e: Exception) {
            Timber.e(e, "Error getting app usage data")
            emptyList()
        }
    }

    /**
     * Get app name from package name
     */
    private fun getAppName(packageName: String): String? {
        return try {
            val applicationInfo = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(applicationInfo).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    /**
     * Check if usage stats permission is granted
     *
     * Uses AppOpsManager.checkOpNoThrow (or unsafeCheckOpNoThrow on API 29+)
     * for a reliable permission check. This is the correct method for checking
     * permissions, unlike noteOpNoThrow which is meant for recording operations.
     *
     * Returns false if:
     * - Permission is not granted
     * - Security exception occurs (permission revoked at runtime)
     * - Any other error occurs during the check
     */
    fun hasUsageStatsPermission(): Boolean {
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
            val mode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(),
                    context.packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(),
                    context.packageName
                )
            }
            val isAllowed = mode == android.app.AppOpsManager.MODE_ALLOWED
            Timber.d("Usage stats permission check: mode=$mode, allowed=$isAllowed")
            isAllowed
        } catch (e: SecurityException) {
            Timber.w(e, "Usage stats permission check failed - permission not granted or revoked")
            false
        } catch (e: Exception) {
            Timber.e(e, "Unexpected error checking usage stats permission")
            // Fallback to query-based check
            try {
                val calendar = Calendar.getInstance()
                calendar.add(Calendar.DAY_OF_YEAR, -1)
                val stats = usageStatsManager.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY,
                    calendar.timeInMillis,
                    System.currentTimeMillis()
                )
                // If we get here without exception and get results, permission is granted
                stats != null && stats.isNotEmpty()
            } catch (e2: SecurityException) {
                false
            }
        }
    }
}
