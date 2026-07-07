package com.safeguard.parentalcontrol.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.safeguard.parentalcontrol.R
import com.safeguard.parentalcontrol.data.model.AlertSeverity
import com.safeguard.parentalcontrol.data.model.AlertType
import com.safeguard.parentalcontrol.data.repository.AlertRepository
import com.safeguard.parentalcontrol.service.ContentFilterVpnService
import com.safeguard.parentalcontrol.util.AccessibilityServiceHelper
import com.safeguard.parentalcontrol.util.Constants
import com.safeguard.parentalcontrol.util.LocaleHelper
import com.safeguard.parentalcontrol.util.PreferencesManager
import com.safeguard.parentalcontrol.util.ProtectionStatus
import com.safeguard.parentalcontrol.util.ProtectionStatusHelper
import com.safeguard.parentalcontrol.util.ProtectionType
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker for monitoring ALL protection statuses on the child's device.
 *
 * This worker periodically checks if any protection has been disabled and
 * ALERTS THE PARENT (not the child) if any protection is revoked.
 *
 * Protections monitored:
 * 1. Accessibility Service - Text monitoring
 * 2. Usage Stats Permission - Screen time tracking
 * 3. Overlay Permission - Lock screen display
 * 4. Notification Permission - Alert delivery
 * 5. Battery Optimization - Prevents app from being killed
 * 6. VPN Service - Content filtering (if enabled)
 *
 * Why alert the parent, not the child:
 * - The child should NOT be able to disable monitoring themselves
 * - If the child disables any protection, the parent needs to know immediately
 * - If Android kills the app/service, the parent needs to physically fix it
 * - This prevents the child from circumventing parental controls
 *
 * The alert is sent to the backend which:
 * 1. Stores it in the alerts table
 * 2. Sends a push notification to the parent's device via FCM
 * 3. Parent sees the alert in their dashboard
 */
@HiltWorker
class ProtectionMonitorWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val alertRepository: AlertRepository,
    private val preferencesManager: PreferencesManager
) : CoroutineWorker(appContext, workerParams) {

    private fun getString(resId: Int, vararg args: Any): String =
        LocaleHelper.localizedContext(applicationContext).getString(resId, *args)

    companion object {
        private const val WORK_NAME = "protection_monitor_work"

        // Preference keys for tracking last alert times per protection type
        private const val PREFS_PREFIX_LAST_ALERT = "protection_alert_last_time_"
        private const val PREFS_KEY_LAST_STATUS = "protection_last_status"

        // Cooldowns to prevent alert spam
        private const val CRITICAL_ALERT_COOLDOWN_MS = 15 * 60 * 1000L  // 15 min for critical
        private const val NORMAL_ALERT_COOLDOWN_MS = 60 * 60 * 1000L   // 1 hour for non-critical

        // State-based tamper signals (bypass tool present, monitoring persistently
        // off) re-alert on this longer cooldown so the parent keeps being reminded
        // while the device stays compromised, without nagging every 15 minutes.
        private const val TAMPER_REALERT_COOLDOWN_MS = 6 * 60 * 60 * 1000L  // 6 hours
        private const val TAMPER_KEY_BYPASS_TOOL = "tamper_bypass_tool"
        private const val TAMPER_KEY_MONITORING_OFF = "tamper_monitoring_off"
        private const val TAMPER_KEY_FOREIGN_VPN = "tamper_foreign_vpn"

        // Serializes the per-protection check-then-record alert gate across
        // concurrent worker runs. The periodic run and a checkNow() one-time run
        // share the same tag and can overlap; without this lock both could pass
        // shouldSendAlert() for the same protection before either calls
        // saveLastAlertTime(), double-firing a protection-disabled alert to the
        // parent (a TOCTOU race). Sibling of ISSUE-002, which fixed the same race
        // class in AlertRepository via an atomic DataStore edit; WorkManager runs
        // workers in-process so a single process-wide Mutex closes it here.
        private val alertSlotMutex = Mutex()

        /**
         * Enqueue periodic protection monitoring work.
         * Checks every 15 minutes.
         */
        fun enqueue(context: Context) {
            val request = PeriodicWorkRequestBuilder<ProtectionMonitorWorker>(
                Constants.ACCESSIBILITY_MONITOR_INTERVAL_MINUTES, TimeUnit.MINUTES,
                5, TimeUnit.MINUTES // flex interval
            )
                .addTag(Constants.WORK_TAG_ACCESSIBILITY_MONITOR)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request
                )

            Timber.d("ProtectionMonitorWorker enqueued (every ${Constants.ACCESSIBILITY_MONITOR_INTERVAL_MINUTES} minutes)")
        }

        /**
         * Enqueue immediate one-time check.
         * Use this after app launch, boot, or when user returns to the app.
         */
        fun checkNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<ProtectionMonitorWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .addTag(Constants.WORK_TAG_ACCESSIBILITY_MONITOR)
                .build()

            WorkManager.getInstance(context)
                .enqueue(request)

            Timber.d("ProtectionMonitorWorker immediate check enqueued")
        }

        /**
         * Cancel all protection monitor work.
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context)
                .cancelUniqueWork(WORK_NAME)
            Timber.d("ProtectionMonitorWorker cancelled")
        }
    }

    override suspend fun doWork(): Result {
        Timber.d("ProtectionMonitorWorker starting")

        // Only CHILD devices need protection monitoring
        if (preferencesManager.isParent) {
            Timber.d("ProtectionMonitorWorker: Skipping for PARENT device")
            return Result.success()
        }

        // Check if device is registered
        if (!preferencesManager.isDeviceRegistered) {
            Timber.d("ProtectionMonitorWorker: Device not registered, skipping")
            return Result.success()
        }

        // Get current protection status
        val checkVpn = preferencesManager.isContentFilteringEnabled
        val currentStatus = ProtectionStatusHelper.getProtectionStatus(applicationContext, checkVpn)

        Timber.d("ProtectionMonitorWorker: Current status - " +
                "accessibility=${currentStatus.accessibilityEnabled}, " +
                "usageStats=${currentStatus.usageStatsEnabled}, " +
                "overlay=${currentStatus.overlayEnabled}, " +
                "notifications=${currentStatus.notificationsEnabled}, " +
                "batteryOpt=${currentStatus.batteryOptimizationDisabled}, " +
                "autoStart=${currentStatus.autoStartEnabled}")

        // Get previous status to detect changes
        val previousStatus = loadPreviousStatus()

        // Find newly disabled protections
        val newlyDisabled = ProtectionStatusHelper.getNewlyDisabledProtections(previousStatus, currentStatus)

        // On first run (no previous status), don't alert for all disabled protections
        // This prevents false alerts when the app is first installed and permissions
        // are being set up. Only alert after we have a baseline.
        if (previousStatus == null) {
            Timber.d("ProtectionMonitorWorker: First run - establishing baseline, no alerts sent")
            saveCurrentStatus(currentStatus)
            return Result.success()
        }

        // Alert only for NEWLY disabled protections (changed from enabled to disabled).
        // The check (shouldSendAlert) and record (saveLastAlertTime, inside
        // alertParentProtectionDisabled's success path) are serialized per iteration
        // by alertSlotMutex so a concurrent worker run can't pass the same gate
        // before this one records it. See alertSlotMutex above.
        for (protectionType in newlyDisabled) {
            alertSlotMutex.withLock {
                if (shouldSendAlert(protectionType)) {
                    alertParentProtectionDisabled(protectionType)
                }
            }
        }

        // Save current status for next comparison
        saveCurrentStatus(currentStatus)

        // State-based tamper detection (runs after the transition checks above).
        // These adversarial signals must fire even when the bad state already
        // existed at the baseline - a bypass tool installed before SafeGuard, or
        // accessibility switched off before the first worker run - which the
        // enabled->disabled transition model above cannot see. They re-alert on a
        // long cooldown while the device stays compromised.
        checkTamperSignals(currentStatus, newlyDisabled)

        // Log summary
        val currentlyDisabledList = currentStatus.getDisabledProtections()
        if (currentlyDisabledList.isEmpty()) {
            Timber.d("ProtectionMonitorWorker: All protections enabled - device is protected")
        } else {
            val disabledNames = currentlyDisabledList.map { it.displayName }
            Timber.w("ProtectionMonitorWorker: PROTECTIONS DISABLED: $disabledNames")
        }

        return Result.success()
    }

    /**
     * Alert the parent that a specific protection has been disabled.
     */
    private suspend fun alertParentProtectionDisabled(protectionType: ProtectionType) {
        try {
            val deviceName = preferencesManager.deviceName ?: android.os.Build.MODEL

            val result = alertRepository.createAlert(
                alertType = AlertType.DEVICE_ADMIN_DISABLED,
                severity = if (protectionType.isCritical) AlertSeverity.HIGH else AlertSeverity.MEDIUM,
                title = protectionType.alertTitle,
                message = "${protectionType.alertMessage}\n\nDevice: $deviceName",
                metadata = mapOf(
                    "protection_type" to protectionType.name,
                    "protection_name" to protectionType.displayName,
                    "is_critical" to protectionType.isCritical,
                    "device_name" to deviceName,
                    "manufacturer" to android.os.Build.MANUFACTURER,
                    "model" to android.os.Build.MODEL
                )
            )

            result.onSuccess {
                Timber.w("ProtectionMonitorWorker: ALERT SENT - ${protectionType.displayName} disabled")
                saveLastAlertTime(protectionType)
            }.onError { message, _ ->
                Timber.e("ProtectionMonitorWorker: Failed to send alert: $message")
            }
        } catch (e: Exception) {
            Timber.e(e, "ProtectionMonitorWorker: Error sending alert for ${protectionType.name}")
        }
    }

    /**
     * State-based tamper detection. Unlike the transition-based permission checks,
     * these fire whenever the device is currently in a tampered state and re-alert
     * on a long cooldown (TAMPER_REALERT_COOLDOWN_MS).
     */
    private suspend fun checkTamperSignals(
        currentStatus: ProtectionStatus,
        newlyDisabled: List<ProtectionType>
    ) {
        // 1. A foreign automation / auto-clicker tool holds accessibility access.
        //    Those can auto-tap the lock dialog or remap hardware keys to bypass it.
        val automationTools = AccessibilityServiceHelper.getEnabledAutomationToolServices(applicationContext)
        if (automationTools.isNotEmpty()) {
            alertSlotMutex.withLock {
                if (passesCooldown(TAMPER_KEY_BYPASS_TOOL, TAMPER_REALERT_COOLDOWN_MS)) {
                    alertBypassToolDetected(automationTools)
                }
            }
        } else {
            clearAlert(TAMPER_KEY_BYPASS_TOOL)
        }

        // 2. SafeGuard's own text monitoring is currently OFF and staying off. The
        //    transition path already covers the moment it is switched off; this is
        //    the persistent reminder for "off before baseline / still off hours
        //    later". Skip if the transition path already alerted ACCESSIBILITY this
        //    run so we don't double-fire.
        val monitoringOff = !currentStatus.accessibilityEnabled &&
                !newlyDisabled.contains(ProtectionType.ACCESSIBILITY)
        if (monitoringOff) {
            alertSlotMutex.withLock {
                if (passesCooldown(TAMPER_KEY_MONITORING_OFF, TAMPER_REALERT_COOLDOWN_MS)) {
                    alertMonitoringPersistentlyOff()
                }
            }
        } else if (currentStatus.accessibilityEnabled) {
            clearAlert(TAMPER_KEY_MONITORING_OFF)
        }

        // 3. A foreign VPN app (e.g. ProtonVPN) holds the single VPN slot while content
        //    filtering is supposed to be on and OUR tunnel is NOT established. Android
        //    allows one VPN at a time, so the child's web traffic bypasses our DNS
        //    filter. onRevoke() catches the moment of takeover; this catches the case it
        //    missed (foreign VPN already up before our service started, or a restart).
        val foreignVpnActive = preferencesManager.isContentFilteringEnabled &&
                ContentFilterVpnService.isVpnRunning(applicationContext) &&
                !ContentFilterVpnService.isActive
        if (foreignVpnActive) {
            alertSlotMutex.withLock {
                if (passesCooldown(TAMPER_KEY_FOREIGN_VPN, TAMPER_REALERT_COOLDOWN_MS)) {
                    alertForeignVpnDetected()
                }
            }
        } else {
            clearAlert(TAMPER_KEY_FOREIGN_VPN)
        }
    }

    /**
     * Alert the parent that a third-party VPN app is holding the VPN slot, so SafeGuard's
     * content filter cannot run and web filtering is bypassed.
     */
    private suspend fun alertForeignVpnDetected() {
        try {
            val deviceName = preferencesManager.deviceName ?: android.os.Build.MODEL

            val result = alertRepository.createAlert(
                alertType = AlertType.DEVICE_ADMIN_DISABLED,
                severity = AlertSeverity.HIGH,
                title = getString(R.string.tamper_foreign_vpn_title),
                message = getString(R.string.tamper_foreign_vpn_msg, deviceName),
                metadata = mapOf(
                    "protection_type" to "FOREIGN_VPN",
                    "tamper_type" to "foreign_vpn",
                    "device_name" to deviceName,
                    "manufacturer" to android.os.Build.MANUFACTURER,
                    "model" to android.os.Build.MODEL
                )
            )

            result.onSuccess {
                Timber.w("ProtectionMonitorWorker: TAMPER ALERT SENT - foreign VPN active")
                recordAlert(TAMPER_KEY_FOREIGN_VPN)
            }.onError { message, _ ->
                Timber.e("ProtectionMonitorWorker: Failed to send foreign-VPN alert: $message")
            }
        } catch (e: Exception) {
            Timber.e(e, "ProtectionMonitorWorker: Error sending foreign-VPN alert")
        }
    }

    /**
     * Alert the parent that an automation / auto-clicker tool with accessibility
     * access (lock-screen bypass risk) was detected on the child's device.
     */
    private suspend fun alertBypassToolDetected(toolServiceIds: List<String>) {
        try {
            val deviceName = preferencesManager.deviceName ?: android.os.Build.MODEL
            val toolNames = toolServiceIds
                .map { it.substringBefore("/") }
                .distinct()
                .joinToString(", ")

            val result = alertRepository.createAlert(
                alertType = AlertType.DEVICE_ADMIN_DISABLED,
                severity = AlertSeverity.HIGH,
                title = getString(R.string.protection_bypass_tool_title),
                message = getString(R.string.protection_bypass_tool_msg, deviceName, toolNames),
                metadata = mapOf(
                    "protection_type" to "BYPASS_TOOL",
                    "tamper_type" to "bypass_tool",
                    "services" to toolServiceIds.joinToString(","),
                    "device_name" to deviceName,
                    "manufacturer" to android.os.Build.MANUFACTURER,
                    "model" to android.os.Build.MODEL
                )
            )

            result.onSuccess {
                Timber.w("ProtectionMonitorWorker: TAMPER ALERT SENT - bypass tools: $toolServiceIds")
                recordAlert(TAMPER_KEY_BYPASS_TOOL)
            }.onError { message, _ ->
                Timber.e("ProtectionMonitorWorker: Failed to send bypass-tool alert: $message")
            }
        } catch (e: Exception) {
            Timber.e(e, "ProtectionMonitorWorker: Error sending bypass-tool alert")
        }
    }

    /**
     * Alert the parent that SafeGuard's text-monitoring accessibility service is
     * currently disabled (and has stayed disabled past the transition window).
     */
    private suspend fun alertMonitoringPersistentlyOff() {
        try {
            val deviceName = preferencesManager.deviceName ?: android.os.Build.MODEL

            val result = alertRepository.createAlert(
                alertType = AlertType.DEVICE_ADMIN_DISABLED,
                severity = AlertSeverity.HIGH,
                title = getString(R.string.protection_monitoring_off_title),
                message = getString(R.string.protection_monitoring_off_msg, deviceName),
                metadata = mapOf(
                    "protection_type" to "MONITORING_OFF",
                    "tamper_type" to "monitoring_off",
                    "device_name" to deviceName,
                    "manufacturer" to android.os.Build.MANUFACTURER,
                    "model" to android.os.Build.MODEL
                )
            )

            result.onSuccess {
                Timber.w("ProtectionMonitorWorker: TAMPER ALERT SENT - monitoring persistently off")
                recordAlert(TAMPER_KEY_MONITORING_OFF)
            }.onError { message, _ ->
                Timber.e("ProtectionMonitorWorker: Failed to send monitoring-off alert: $message")
            }
        } catch (e: Exception) {
            Timber.e(e, "ProtectionMonitorWorker: Error sending monitoring-off alert")
        }
    }

    /**
     * Check if enough time has passed since the last alert for this protection type.
     */
    private fun shouldSendAlert(protectionType: ProtectionType): Boolean {
        val cooldown = if (protectionType.isCritical) {
            CRITICAL_ALERT_COOLDOWN_MS
        } else {
            NORMAL_ALERT_COOLDOWN_MS
        }
        return passesCooldown(protectionType.name, cooldown)
    }

    /**
     * Save the current time as the last alert time for this protection type.
     */
    private fun saveLastAlertTime(protectionType: ProtectionType) = recordAlert(protectionType.name)

    /**
     * Clear the last alert time for a protection type (when it's re-enabled).
     */
    private fun clearLastAlertTime(protectionType: ProtectionType) = clearAlert(protectionType.name)

    /**
     * Generic cooldown gate keyed by an arbitrary alert key. Shared by the
     * per-protection alerts and the state-based tamper alerts.
     */
    private fun passesCooldown(key: String, cooldownMs: Long): Boolean {
        val prefs = applicationContext.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        val lastAlertTime = prefs.getLong(PREFS_PREFIX_LAST_ALERT + key, 0)
        return (System.currentTimeMillis() - lastAlertTime) > cooldownMs
    }

    private fun recordAlert(key: String) {
        applicationContext.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(PREFS_PREFIX_LAST_ALERT + key, System.currentTimeMillis())
            .apply()
    }

    private fun clearAlert(key: String) {
        applicationContext.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(PREFS_PREFIX_LAST_ALERT + key)
            .apply()
    }

    /**
     * Load the previous protection status from preferences.
     */
    private fun loadPreviousStatus(): ProtectionStatus? {
        val prefs = applicationContext.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        val statusString = prefs.getString(PREFS_KEY_LAST_STATUS, null) ?: return null

        return try {
            // Parse status string: "accessibility,usageStats,overlay,notifications,batteryOpt,vpn,autoStart"
            val parts = statusString.split(",")
            if (parts.size >= 5) {
                ProtectionStatus(
                    accessibilityEnabled = parts[0] == "1",
                    usageStatsEnabled = parts[1] == "1",
                    overlayEnabled = parts[2] == "1",
                    notificationsEnabled = parts[3] == "1",
                    batteryOptimizationDisabled = parts[4] == "1",
                    vpnConnected = parts.getOrNull(5) == "1",
                    autoStartEnabled = parts.getOrNull(6) != "0" // Default to true if not present
                )
            } else null
        } catch (e: Exception) {
            Timber.e(e, "Error loading previous status")
            null
        }
    }

    /**
     * Save the current protection status to preferences.
     */
    private fun saveCurrentStatus(status: ProtectionStatus) {
        val prefs = applicationContext.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)

        // Convert to string: "1,0,1,1,0,1,1" for enabled/disabled states
        val statusString = listOf(
            if (status.accessibilityEnabled) "1" else "0",
            if (status.usageStatsEnabled) "1" else "0",
            if (status.overlayEnabled) "1" else "0",
            if (status.notificationsEnabled) "1" else "0",
            if (status.batteryOptimizationDisabled) "1" else "0",
            if (status.vpnConnected) "1" else "0",
            if (status.autoStartEnabled) "1" else "0"
        ).joinToString(",")

        prefs.edit()
            .putString(PREFS_KEY_LAST_STATUS, statusString)
            .apply()

        // Clear alert times for re-enabled protections
        if (status.accessibilityEnabled) clearLastAlertTime(ProtectionType.ACCESSIBILITY)
        if (status.usageStatsEnabled) clearLastAlertTime(ProtectionType.USAGE_STATS)
        if (status.overlayEnabled) clearLastAlertTime(ProtectionType.OVERLAY)
        if (status.notificationsEnabled) clearLastAlertTime(ProtectionType.NOTIFICATIONS)
        if (status.batteryOptimizationDisabled) clearLastAlertTime(ProtectionType.BATTERY_OPTIMIZATION)
        if (status.vpnConnected) clearLastAlertTime(ProtectionType.VPN_SERVICE)
        if (status.autoStartEnabled) clearLastAlertTime(ProtectionType.AUTO_START)
    }
}
