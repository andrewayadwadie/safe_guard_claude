package com.safeguard.parentalcontrol.worker

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.safeguard.parentalcontrol.data.remote.NetworkResult
import com.safeguard.parentalcontrol.data.repository.ContentFilterRepository
import com.safeguard.parentalcontrol.data.repository.CustomWordRepository
import com.safeguard.parentalcontrol.data.repository.DeviceRepository
import com.safeguard.parentalcontrol.data.repository.ScreenTimeRepository
import com.safeguard.parentalcontrol.data.repository.ScreenTimeRulesRepository
import com.safeguard.parentalcontrol.R
import com.safeguard.parentalcontrol.service.ContentFilterVpnService
import com.safeguard.parentalcontrol.util.Constants
import com.safeguard.parentalcontrol.util.LocaleHelper
import com.safeguard.parentalcontrol.util.PreferencesManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker for periodic data synchronization
 *
 * Benefits over polling:
 * - Respects battery optimization (Doze mode)
 * - Intelligent scheduling based on constraints
 * - Survives app restarts
 * - Handles failures with exponential backoff
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val screenTimeRepository: ScreenTimeRepository,
    private val deviceRepository: DeviceRepository,
    private val screenTimeRulesRepository: ScreenTimeRulesRepository,
    private val customWordRepository: CustomWordRepository,
    private val contentFilterRepository: ContentFilterRepository,
    private val preferencesManager: PreferencesManager
) : CoroutineWorker(appContext, workerParams) {

    private fun getString(resId: Int, vararg args: Any): String =
        LocaleHelper.localizedContext(applicationContext).getString(resId, *args)

    override suspend fun doWork(): Result {
        Timber.d("SyncWorker starting (attempt ${runAttemptCount + 1})")

        val isParent = preferencesManager.isParent
        Timber.d("SyncWorker: isParent=$isParent, userRole=${preferencesManager.userRole}")

        return try {
            // Only child devices should sync their screen time and app usage
            if (!isParent) {
                // Sync screen time data (CHILD ONLY)
                syncScreenTime()

                // Sync app usage data (CHILD ONLY)
                syncAppUsage()

                // Sync screen time rules for enforcement (CHILD ONLY)
                syncScreenTimeRules()

                // Sync custom word lists for text monitoring (CHILD ONLY)
                syncCustomWordLists()

                // Sync content filter settings and control VPN (CHILD ONLY)
                // This allows parent to remotely enable/disable content filtering
                syncContentFilter()

                // Check and enforce limits (CHILD ONLY)
                checkAndEnforceLimits()
            } else {
                Timber.d("SyncWorker: Skipping screen time/app usage sync for PARENT device")
            }

            // Sync device status (both parent and child can sync device heartbeat)
            syncDevice()

            Timber.d("SyncWorker completed successfully")
            Result.success()
        } catch (e: Exception) {
            Timber.e(e, "SyncWorker failed")

            // Retry up to 3 times with exponential backoff
            if (runAttemptCount < MAX_RETRY_ATTEMPTS) {
                Timber.d("SyncWorker will retry (attempt ${runAttemptCount + 1}/$MAX_RETRY_ATTEMPTS)")
                Result.retry()
            } else {
                Timber.e("SyncWorker failed after $MAX_RETRY_ATTEMPTS attempts")
                Result.failure(
                    workDataOf("error" to e.message)
                )
            }
        }
    }

    private suspend fun syncScreenTime() {
        val result = screenTimeRepository.syncScreenTimeFromDevice()
        result.onSuccess {
            Timber.d("Screen time synced")
        }.onError { message, _ ->
            Timber.w("Screen time sync failed: $message")
            throw Exception(message)
        }
    }

    private suspend fun syncAppUsage() {
        val result = screenTimeRepository.syncAppUsageFromDevice()
        result.onSuccess {
            Timber.d("App usage synced")
        }.onError { message, _ ->
            Timber.w("App usage sync failed: $message")
            // Don't throw - app usage sync failure shouldn't fail the whole worker
        }
    }

    private suspend fun syncDevice() {
        try {
            deviceRepository.syncDevice()
            Timber.d("Device synced")
        } catch (e: Exception) {
            Timber.w(e, "Device sync failed")
            // Don't throw - device sync failure shouldn't fail the whole worker
        }
    }

    private suspend fun syncScreenTimeRules() {
        try {
            // Get the current device ID from preferences or device repository
            val deviceId = deviceRepository.getCurrentDeviceId()
            val isParent = preferencesManager.isParent
            Timber.d("syncScreenTimeRules: deviceId=$deviceId, isParent=$isParent")

            if (deviceId > 0) {
                val rules = screenTimeRulesRepository.getCachedRules(deviceId, forceRefresh = true)
                if (rules != null) {
                    Timber.d("Screen time rules synced for device $deviceId: " +
                        "isActive=${rules.isActive}, dailyLimit=${rules.dailyLimit}s, " +
                        "bedtimeEnabled=${rules.bedtimeEnabled}, bedtime=${rules.bedtimeStart}-${rules.bedtimeEnd}")
                } else {
                    Timber.w("Screen time rules sync returned null for device $deviceId")
                }
            } else {
                Timber.w("Cannot sync rules - device ID is $deviceId (not registered)")
            }
        } catch (e: Exception) {
            Timber.w(e, "Screen time rules sync failed")
            // Don't throw - rules sync failure shouldn't fail the whole worker
        }
    }

    /**
     * Sync custom word lists for text monitoring.
     * This ensures the ContentClassifier has the latest parent-defined blacklist/whitelist words.
     */
    private suspend fun syncCustomWordLists() {
        try {
            // Only sync if needed (based on last sync time)
            if (!customWordRepository.shouldSync()) {
                Timber.d("Word list sync not needed (cached data is fresh)")
                return
            }

            val success = customWordRepository.syncWordLists()
            if (success) {
                Timber.d("Custom word lists synced successfully")
            } else {
                Timber.w("Custom word lists sync returned false (network issue or no data)")
            }
        } catch (e: Exception) {
            Timber.w(e, "Custom word lists sync failed")
            // Don't throw - word list sync failure shouldn't fail the whole worker
            // The ContentClassifier will continue using cached word lists
        }
    }

    /**
     * Sync content filter settings from backend and control VPN accordingly.
     * This allows the parent to remotely enable/disable content filtering on the child's device.
     *
     * The parent controls this setting via their dashboard, and the child device
     * syncs and automatically starts/stops the VPN based on the parent's choice.
     */
    private suspend fun syncContentFilter() {
        try {
            val deviceId = deviceRepository.getCurrentDeviceId()
            if (deviceId <= 0) {
                Timber.d("syncContentFilter: Device not registered, skipping")
                return
            }

            when (val result = contentFilterRepository.getContentFilter(deviceId)) {
                is NetworkResult.Success -> {
                    val filter = result.data
                    val shouldBeEnabled = filter.isActive
                    val currentlyEnabled = preferencesManager.isContentFilteringEnabled
                    val vpnRunning = ContentFilterVpnService.isVpnRunning(applicationContext)

                    Timber.d("syncContentFilter: backend isActive=$shouldBeEnabled, " +
                            "localPref=$currentlyEnabled, vpnRunning=$vpnRunning")

                    // Update local preference to match backend
                    if (currentlyEnabled != shouldBeEnabled) {
                        preferencesManager.isContentFilteringEnabled = shouldBeEnabled
                        Timber.d("syncContentFilter: Updated local preference to $shouldBeEnabled")
                    }

                    // Control VPN based on backend setting
                    if (shouldBeEnabled && !vpnRunning) {
                        // Parent wants filtering enabled, but VPN is not running
                        // Check if VPN permission is granted before trying to start
                        if (hasVpnPermission()) {
                            Timber.d("syncContentFilter: Starting VPN (parent enabled filtering)")
                            startVpnService()
                        } else {
                            Timber.d("syncContentFilter: VPN permission not granted, skipping auto-start")
                            // User needs to grant VPN permission via PermissionsSetupScreen
                        }
                    } else if (!shouldBeEnabled && vpnRunning) {
                        // Parent wants filtering disabled, but VPN is running
                        Timber.d("syncContentFilter: Stopping VPN (parent disabled filtering)")
                        stopVpnService()
                    }
                }
                is NetworkResult.Error -> {
                    if (result.code == 404) {
                        // No content filter configured - default to enabled for safety
                        Timber.d("syncContentFilter: No filter configured (404), defaulting to enabled")
                        if (!preferencesManager.isContentFilteringEnabled) {
                            preferencesManager.isContentFilteringEnabled = true
                        }
                        // Only start VPN if permission is granted
                        if (!ContentFilterVpnService.isVpnRunning(applicationContext) && hasVpnPermission()) {
                            startVpnService()
                        } else if (!hasVpnPermission()) {
                            Timber.d("syncContentFilter: VPN permission not granted, skipping auto-start")
                        }
                    } else {
                        Timber.w("syncContentFilter: Failed to fetch filter: ${result.message}")
                    }
                }
                else -> {}
            }
        } catch (e: Exception) {
            Timber.w(e, "syncContentFilter failed")
            // Don't throw - content filter sync failure shouldn't fail the whole worker
        }
    }

    /**
     * Check if VPN permission is already granted
     * VpnService.prepare() returns null if permission is granted
     */
    private fun hasVpnPermission(): Boolean {
        return try {
            VpnService.prepare(applicationContext) == null
        } catch (e: Exception) {
            Timber.w(e, "Error checking VPN permission")
            false
        }
    }

    /**
     * Start the VPN content filtering service
     */
    private fun startVpnService() {
        try {
            val intent = Intent(applicationContext, ContentFilterVpnService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                applicationContext.startForegroundService(intent)
            } else {
                applicationContext.startService(intent)
            }
            Timber.d("VPN service start command sent")
        } catch (e: Exception) {
            Timber.e(e, "Failed to start VPN service")
        }
    }

    /**
     * Stop the VPN content filtering service
     */
    private fun stopVpnService() {
        try {
            val intent = Intent(applicationContext, ContentFilterVpnService::class.java).apply {
                action = ContentFilterVpnService.ACTION_STOP
            }
            applicationContext.startService(intent)
            Timber.d("VPN service stop command sent")
        } catch (e: Exception) {
            Timber.e(e, "Failed to stop VPN service")
        }
    }

    private suspend fun checkAndEnforceLimits() {
        try {
            // Get today's total screen time
            val todayScreenTime = screenTimeRepository.getTodayScreenTimeSeconds()

            // Check if daily limit is exceeded
            if (screenTimeRulesRepository.isDailyLimitExceeded(todayScreenTime)) {
                Timber.w("Daily screen time limit exceeded: ${todayScreenTime}s")
                // Only send notification once per day to prevent spam
                if (!preferencesManager.hasNotifiedLimitExceededToday()) {
                    sendLimitExceededNotification(
                        title = getString(R.string.notif_sync_limit_reached_title),
                        message = getString(R.string.notif_sync_limit_reached_msg)
                    )
                    preferencesManager.setNotifiedLimitExceededToday()
                }
            } else {
                // Check if approaching limit (80% warning)
                val dailyLimit = screenTimeRulesRepository.getDailyLimit()
                if (dailyLimit != null && todayScreenTime >= (dailyLimit * 0.8).toInt()) {
                    val remaining = screenTimeRulesRepository.getRemainingDailyTime(todayScreenTime)
                    if (remaining != null && remaining > 0) {
                        val remainingMinutes = remaining / 60
                        Timber.d("Approaching daily limit. Remaining: ${remainingMinutes}m")
                        // Only send warning notification once per day
                        if (!preferencesManager.hasNotifiedLimitWarningToday()) {
                            sendLimitWarningNotification(
                                title = getString(R.string.notif_sync_limit_warning_title),
                                message = getString(R.string.notif_sync_limit_warning_msg, remainingMinutes)
                            )
                            preferencesManager.setNotifiedLimitWarningToday()
                        }
                    }
                }
            }

            // Check bedtime
            if (screenTimeRulesRepository.isInBedtime()) {
                Timber.w("Currently in bedtime hours")
                // Only send bedtime notification once per day
                if (!preferencesManager.hasNotifiedBedtimeToday()) {
                    sendLimitExceededNotification(
                        title = getString(R.string.notif_sync_bedtime_title),
                        message = getString(R.string.notif_sync_bedtime_msg)
                    )
                    preferencesManager.setNotifiedBedtimeToday()
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Limit enforcement check failed")
        }
    }

    private fun sendLimitExceededNotification(title: String, message: String) {
        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager

        val notification = androidx.core.app.NotificationCompat.Builder(
            applicationContext,
            com.safeguard.parentalcontrol.SafeGuardApplication.CHANNEL_ALERTS
        )
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(com.safeguard.parentalcontrol.R.drawable.ic_shield)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(Constants.NOTIFICATION_ID_LIMIT_EXCEEDED, notification)
    }

    private fun sendLimitWarningNotification(title: String, message: String) {
        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager

        val notification = androidx.core.app.NotificationCompat.Builder(
            applicationContext,
            com.safeguard.parentalcontrol.SafeGuardApplication.CHANNEL_ALERTS
        )
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(com.safeguard.parentalcontrol.R.drawable.ic_shield)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(Constants.NOTIFICATION_ID_LIMIT_WARNING, notification)
    }

    companion object {
        private const val WORK_NAME = "sync_work"
        private const val WORK_NAME_IMMEDIATE = "sync_work_immediate"
        private const val MAX_RETRY_ATTEMPTS = 3

        /**
         * Enqueue periodic sync work
         * Uses battery-friendly constraints and flexible timing
         * Uses KEEP policy to avoid re-scheduling if already running
         */
        fun enqueue(context: Context) {
            // IMPORTANT: Do NOT use setRequiresBatteryNotLow(true) for parental control apps
            // This would prevent sync when battery is below 15-20%, which is unacceptable
            // for a parental monitoring app that needs to maintain sync at all times.
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                // Removed: .setRequiresBatteryNotLow(true) - prevents sync on low battery
                .setRequiresStorageNotLow(true) // Don't sync if storage is critically low
                .build()

            val request = PeriodicWorkRequestBuilder<SyncWorker>(
                Constants.SYNC_INTERVAL_MINUTES, TimeUnit.MINUTES,
                5, TimeUnit.MINUTES // flex interval for battery optimization
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .addTag(Constants.WORK_TAG_SYNC)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP, // Don't replace existing work
                    request
                )

            Timber.d("SyncWorker enqueued (periodic)")
        }

        /**
         * Enqueue immediate one-time sync
         * Use for manual refresh or important updates
         * Uses unique work name to prevent duplicate immediate syncs (fixes JobScheduler "buggy app" warning)
         */
        fun enqueueImmediate(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .addTag(Constants.WORK_TAG_SYNC)
                .build()

            // Use enqueueUniqueWork to prevent duplicate immediate syncs
            // REPLACE policy ensures we don't queue multiple immediate syncs
            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    WORK_NAME_IMMEDIATE,
                    ExistingWorkPolicy.REPLACE,
                    request
                )

            Timber.d("Immediate SyncWorker enqueued (unique)")
        }

        /**
         * Cancel all sync work
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context)
                .cancelUniqueWork(WORK_NAME)
            WorkManager.getInstance(context)
                .cancelUniqueWork(WORK_NAME_IMMEDIATE)
            Timber.d("SyncWorker cancelled")
        }
    }
}
