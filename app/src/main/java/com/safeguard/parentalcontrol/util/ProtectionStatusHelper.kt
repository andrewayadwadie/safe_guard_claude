package com.safeguard.parentalcontrol.util

import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.Context
import android.net.VpnService
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import timber.log.Timber

/**
 * Represents the current protection status of the child's device.
 * Each permission/service that is disabled reduces the protection level.
 */
data class ProtectionStatus(
    val accessibilityEnabled: Boolean = false,
    val usageStatsEnabled: Boolean = false,
    val overlayEnabled: Boolean = false,
    val notificationsEnabled: Boolean = false,
    val batteryOptimizationDisabled: Boolean = false,
    val vpnConnected: Boolean = false,  // Optional - only if VPN filtering is enabled
    val autoStartEnabled: Boolean = true // Detected by checking if boot receiver ran
) {
    /**
     * Get list of disabled protections.
     */
    fun getDisabledProtections(): List<ProtectionType> {
        val disabled = mutableListOf<ProtectionType>()
        if (!accessibilityEnabled) disabled.add(ProtectionType.ACCESSIBILITY)
        if (!usageStatsEnabled) disabled.add(ProtectionType.USAGE_STATS)
        if (!overlayEnabled) disabled.add(ProtectionType.OVERLAY)
        if (!notificationsEnabled) disabled.add(ProtectionType.NOTIFICATIONS)
        if (!batteryOptimizationDisabled) disabled.add(ProtectionType.BATTERY_OPTIMIZATION)
        if (!autoStartEnabled) disabled.add(ProtectionType.AUTO_START)
        return disabled
    }

    /**
     * Check if all critical protections are enabled.
     * Critical = Accessibility + Usage Stats + Battery Optimization + Auto Start
     */
    fun areCriticalProtectionsEnabled(): Boolean {
        return accessibilityEnabled && usageStatsEnabled && batteryOptimizationDisabled && autoStartEnabled
    }

    /**
     * Check if all protections are enabled.
     */
    fun areAllProtectionsEnabled(): Boolean {
        return accessibilityEnabled && usageStatsEnabled && overlayEnabled &&
                notificationsEnabled && batteryOptimizationDisabled && autoStartEnabled
    }

    /**
     * Get protection level as percentage (0-100).
     */
    fun getProtectionLevel(): Int {
        var enabled = 0
        val total = 6 // Total number of permissions checked

        if (accessibilityEnabled) enabled++
        if (usageStatsEnabled) enabled++
        if (overlayEnabled) enabled++
        if (notificationsEnabled) enabled++
        if (batteryOptimizationDisabled) enabled++
        if (autoStartEnabled) enabled++

        return (enabled * 100) / total
    }
}

/**
 * Types of protection that can be monitored.
 */
enum class ProtectionType(
    val displayName: String,
    val alertTitle: String,
    val alertMessage: String,
    val isCritical: Boolean
) {
    ACCESSIBILITY(
        displayName = "Text Monitoring",
        alertTitle = "Text Monitoring Disabled",
        alertMessage = "Text monitoring protection has been disabled. Your child's messages are no longer being monitored for inappropriate content.",
        isCritical = true
    ),
    USAGE_STATS(
        displayName = "Screen Time Tracking",
        alertTitle = "Screen Time Tracking Disabled",
        alertMessage = "Screen time tracking has been disabled. Daily limits and app usage monitoring are no longer active.",
        isCritical = true
    ),
    OVERLAY(
        displayName = "Lock Screen",
        alertTitle = "Lock Screen Permission Disabled",
        alertMessage = "Lock screen permission has been revoked. Screen time limits cannot be enforced with a lock screen.",
        isCritical = false
    ),
    NOTIFICATIONS(
        displayName = "Notifications",
        alertTitle = "Notifications Disabled",
        alertMessage = "Notification permission has been revoked. Alerts may not be delivered properly.",
        isCritical = false
    ),
    BATTERY_OPTIMIZATION(
        displayName = "Background Running",
        alertTitle = "Battery Optimization Enabled",
        alertMessage = "Battery optimization is enabled for SafeGuard. The app may be killed by the system, disabling all protections.",
        isCritical = true
    ),
    VPN_SERVICE(
        displayName = "Content Filtering",
        alertTitle = "Content Filtering Disabled",
        alertMessage = "VPN content filtering has been disconnected. Web content is no longer being filtered.",
        isCritical = false
    ),
    APP_KILLED(
        displayName = "App Running",
        alertTitle = "SafeGuard App Stopped",
        alertMessage = "The SafeGuard app has been stopped or force-closed. All protections are disabled until the app is restarted.",
        isCritical = true
    ),
    AUTO_START(
        displayName = "Auto-Start",
        alertTitle = "Auto-Start Disabled",
        alertMessage = "SafeGuard did not start automatically after the device was restarted. Auto-start may be disabled in device settings. Without auto-start, protections won't be active after a reboot until someone manually opens the app.",
        isCritical = true
    )
}

/**
 * Helper utility to check the protection status of a child's device.
 *
 * This centralizes all permission/service checks in one place for:
 * - Protection monitoring worker
 * - Permissions setup screen
 * - Dashboard status display
 */
object ProtectionStatusHelper {

    private const val TAG = "ProtectionStatus"

    // Preference keys for auto-start detection
    private const val PREFS_KEY_LAST_BOOT_TIME = "protection_last_boot_time"
    private const val PREFS_KEY_BOOT_RECEIVER_RAN = "protection_boot_receiver_ran"

    /**
     * Get the current protection status of the device.
     */
    fun getProtectionStatus(context: Context, checkVpn: Boolean = false): ProtectionStatus {
        return ProtectionStatus(
            accessibilityEnabled = isAccessibilityEnabled(context),
            usageStatsEnabled = isUsageStatsEnabled(context),
            overlayEnabled = isOverlayEnabled(context),
            notificationsEnabled = isNotificationsEnabled(context),
            batteryOptimizationDisabled = isBatteryOptimizationDisabled(context),
            vpnConnected = if (checkVpn) isVpnConnected(context) else true,
            autoStartEnabled = isAutoStartWorking(context)
        )
    }

    /**
     * Called by BootReceiver when the app starts via boot.
     * This records that auto-start is working.
     */
    fun recordBootReceiverExecution(context: Context) {
        val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        val currentBootTime = getDeviceBootTime()

        prefs.edit()
            .putLong(PREFS_KEY_LAST_BOOT_TIME, currentBootTime)
            .putBoolean(PREFS_KEY_BOOT_RECEIVER_RAN, true)
            .apply()

        Timber.d("$TAG: Boot receiver execution recorded. Boot time: $currentBootTime")
    }

    /**
     * Called when app is opened manually (not via boot).
     * Checks if the device has been rebooted since last boot receiver execution.
     */
    fun checkAutoStartOnManualLaunch(context: Context) {
        val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        val lastRecordedBootTime = prefs.getLong(PREFS_KEY_LAST_BOOT_TIME, 0)
        val currentBootTime = getDeviceBootTime()

        // If boot times don't match, device was rebooted
        // But if boot receiver ran for this boot, we already recorded it
        if (lastRecordedBootTime != currentBootTime) {
            // Device rebooted but boot receiver didn't run (or hasn't run yet)
            // Mark that boot receiver hasn't run for this boot cycle
            prefs.edit()
                .putBoolean(PREFS_KEY_BOOT_RECEIVER_RAN, false)
                .apply()

            Timber.w("$TAG: Device rebooted but boot receiver hasn't run. " +
                    "Last boot: $lastRecordedBootTime, Current boot: $currentBootTime")
        }
    }

    /**
     * Check if auto-start is working.
     *
     * Logic:
     * - If the device has been rebooted since we last recorded a boot receiver execution,
     *   and the boot receiver hasn't run for the current boot, auto-start is likely disabled.
     * - We give a grace period after boot before flagging (boot receiver might be delayed).
     */
    fun isAutoStartWorking(context: Context): Boolean {
        val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        val lastRecordedBootTime = prefs.getLong(PREFS_KEY_LAST_BOOT_TIME, 0)
        val bootReceiverRan = prefs.getBoolean(PREFS_KEY_BOOT_RECEIVER_RAN, true)
        val currentBootTime = getDeviceBootTime()

        // If this is the first time (no recorded boot time), assume it's working
        if (lastRecordedBootTime == 0L) {
            return true
        }

        // If boot times match, boot receiver ran for this boot cycle
        if (lastRecordedBootTime == currentBootTime) {
            return true
        }

        // Boot times don't match - device was rebooted
        // Check if boot receiver has run for this boot cycle
        if (!bootReceiverRan) {
            // Give a 5-minute grace period after boot before flagging
            val timeSinceBoot = SystemClock.elapsedRealtime()
            val gracePeriodMs = 5 * 60 * 1000L // 5 minutes

            if (timeSinceBoot > gracePeriodMs) {
                // Boot receiver should have run by now but didn't
                Timber.w("$TAG: Auto-start appears to be disabled. " +
                        "Device booted ${timeSinceBoot / 1000}s ago but boot receiver hasn't run.")
                return false
            }
        }

        return true
    }

    /**
     * Get the device boot time as a unique identifier for this boot cycle.
     * Uses System.currentTimeMillis() - SystemClock.elapsedRealtime() to get boot timestamp.
     */
    private fun getDeviceBootTime(): Long {
        return System.currentTimeMillis() - SystemClock.elapsedRealtime()
    }

    /**
     * Reset auto-start tracking (e.g., after user enables auto-start).
     */
    fun resetAutoStartTracking(context: Context) {
        val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .remove(PREFS_KEY_LAST_BOOT_TIME)
            .remove(PREFS_KEY_BOOT_RECEIVER_RAN)
            .apply()
        Timber.d("$TAG: Auto-start tracking reset")
    }

    /**
     * Check if accessibility service is enabled.
     */
    fun isAccessibilityEnabled(context: Context): Boolean {
        return AccessibilityServiceHelper.isServiceEnabled(context)
    }

    /**
     * Check if usage stats permission is granted.
     */
    fun isUsageStatsEnabled(context: Context): Boolean {
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName
                )
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Error checking usage stats permission")
            false
        }
    }

    /**
     * Check if overlay permission is granted.
     */
    fun isOverlayEnabled(context: Context): Boolean {
        return Settings.canDrawOverlays(context)
    }

    /**
     * Check if notification permission is granted.
     */
    fun isNotificationsEnabled(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE)
                    as NotificationManager
            notificationManager.areNotificationsEnabled()
        } else {
            true // Before Android 13, notifications are enabled by default
        }
    }

    /**
     * Check if battery optimization is disabled for this app.
     */
    fun isBatteryOptimizationDisabled(context: Context): Boolean {
        return try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            powerManager.isIgnoringBatteryOptimizations(context.packageName)
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Error checking battery optimization")
            false
        }
    }

    /**
     * Check if VPN is connected (for content filtering).
     * This checks if our VPN service has an active connection.
     */
    fun isVpnConnected(context: Context): Boolean {
        return try {
            // Check if VPN is prepared for our app
            val intent = VpnService.prepare(context)
            // If prepare() returns null, VPN is already prepared/connected
            intent == null
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Error checking VPN status")
            false
        }
    }

    /**
     * Compare two protection statuses and return newly disabled protections.
     *
     * @param previous The previous protection status (or null if first check)
     * @param current The current protection status
     * @return List of protections that were enabled before but are now disabled
     */
    fun getNewlyDisabledProtections(
        previous: ProtectionStatus?,
        current: ProtectionStatus
    ): List<ProtectionType> {
        if (previous == null) {
            // First check - return all currently disabled as "newly disabled"
            return current.getDisabledProtections()
        }

        val newlyDisabled = mutableListOf<ProtectionType>()

        // Check each protection type
        if (previous.accessibilityEnabled && !current.accessibilityEnabled) {
            newlyDisabled.add(ProtectionType.ACCESSIBILITY)
        }
        if (previous.usageStatsEnabled && !current.usageStatsEnabled) {
            newlyDisabled.add(ProtectionType.USAGE_STATS)
        }
        if (previous.overlayEnabled && !current.overlayEnabled) {
            newlyDisabled.add(ProtectionType.OVERLAY)
        }
        if (previous.notificationsEnabled && !current.notificationsEnabled) {
            newlyDisabled.add(ProtectionType.NOTIFICATIONS)
        }
        if (previous.batteryOptimizationDisabled && !current.batteryOptimizationDisabled) {
            newlyDisabled.add(ProtectionType.BATTERY_OPTIMIZATION)
        }
        if (previous.vpnConnected && !current.vpnConnected) {
            newlyDisabled.add(ProtectionType.VPN_SERVICE)
        }
        if (previous.autoStartEnabled && !current.autoStartEnabled) {
            newlyDisabled.add(ProtectionType.AUTO_START)
        }

        return newlyDisabled
    }

    /**
     * Get a summary message for multiple disabled protections.
     */
    fun getDisabledProtectionsSummary(disabled: List<ProtectionType>): String {
        if (disabled.isEmpty()) return "All protections are active"

        val names = disabled.map { it.displayName }
        return when (names.size) {
            1 -> "${names[0]} has been disabled"
            2 -> "${names[0]} and ${names[1]} have been disabled"
            else -> {
                val last = names.last()
                val others = names.dropLast(1).joinToString(", ")
                "$others, and $last have been disabled"
            }
        }
    }
}
