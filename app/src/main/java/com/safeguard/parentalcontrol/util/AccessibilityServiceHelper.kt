package com.safeguard.parentalcontrol.util

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import com.safeguard.parentalcontrol.service.TextMonitoringAccessibilityService
import timber.log.Timber

/**
 * Helper utility for managing accessibility service permissions.
 *
 * The accessibility service is required for text monitoring functionality.
 * This helper provides methods to check service status and guide users to enable it.
 *
 * Security Note:
 * - Accessibility services require explicit user consent
 * - Android shows a system confirmation dialog before enabling
 * - The service cannot be enabled programmatically (by design)
 */
object AccessibilityServiceHelper {

    private const val TAG = "AccessibilityHelper"

    /**
     * Check if our accessibility service is enabled.
     *
     * Uses multiple detection methods for reliability:
     * 1. Check Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES (most reliable)
     * 2. Fallback to AccessibilityManager API
     *
     * @param context Application context
     * @return true if the service is enabled and running
     */
    fun isServiceEnabled(context: Context): Boolean {
        // Method 1: Check Settings.Secure (most reliable)
        val serviceComponentName = ComponentName(context, TextMonitoringAccessibilityService::class.java)
        val expectedServiceString = serviceComponentName.flattenToString()

        try {
            val enabledServicesString = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )

            if (!enabledServicesString.isNullOrBlank()) {
                // The setting contains colon-separated service component names
                val enabledServices = enabledServicesString.split(":")
                val isEnabledViaSettings = enabledServices.any { serviceString ->
                    // Compare the component names (handles various formats)
                    serviceString == expectedServiceString ||
                            serviceString.contains(serviceComponentName.className) ||
                            ComponentName.unflattenFromString(serviceString)?.let { component ->
                                component.packageName == serviceComponentName.packageName &&
                                        component.className == serviceComponentName.className
                            } == true
                }

                if (isEnabledViaSettings) {
                    Timber.d("$TAG: Accessibility service enabled (via Settings.Secure)")
                    return true
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "$TAG: Error checking Settings.Secure for accessibility")
        }

        // Method 2: Fallback to AccessibilityManager API
        val accessibilityManager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        if (accessibilityManager == null) {
            Timber.w("$TAG: AccessibilityManager is null")
            return false
        }

        // Check all feedback types, not just FEEDBACK_GENERIC
        val feedbackTypes = listOf(
            AccessibilityServiceInfo.FEEDBACK_GENERIC,
            AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        )

        for (feedbackType in feedbackTypes) {
            val enabledServices = accessibilityManager.getEnabledAccessibilityServiceList(feedbackType)

            val isEnabled = enabledServices.any { serviceInfo ->
                val componentName = ComponentName.unflattenFromString(serviceInfo.id)
                componentName?.packageName == serviceComponentName.packageName &&
                        componentName.className == serviceComponentName.className
            }

            if (isEnabled) {
                Timber.d("$TAG: Accessibility service enabled (via AccessibilityManager, feedbackType=$feedbackType)")
                return true
            }
        }

        Timber.d("$TAG: Accessibility service NOT enabled")
        return false
    }

    /**
     * Check if the service has ever been instantiated by Android.
     * This helps diagnose if Android is actually starting the service.
     */
    fun wasServiceEverCreated(): Boolean {
        return TextMonitoringAccessibilityService.serviceEverCreated
    }

    // --- Bypass / automation tool detection ----------------------------------
    //
    // Accessibility access lets an app read every screen and inject taps/keys. A
    // child can weaponise that to defeat enforcement: auto-tap the lock dialog
    // buttons, or remap the hardware keys. We flag known automation / auto-clicker
    // / macro tools so the parent is told when one is granted accessibility on the
    // child's device. Matching is by exact package OR a keyword in the package name
    // (the keyword catches the many auto-clicker clones on the store).

    private val AUTOMATION_TOOL_PACKAGES = setOf(
        "io.github.sds100.keymapper",                         // Key Mapper
        "com.truedevelopersstudio.automatictap.autoclicker",  // Auto Clicker (True Developers)
        "com.arlosoft.macrodroid",                            // MacroDroid
        "net.dinglisch.android.taskerm",                      // Tasker
        "com.llamalab.automate"                               // Automate
    )

    private val AUTOMATION_TOOL_KEYWORDS = listOf(
        "autoclick", "auto.click", "auto_click", "clicker",
        "keymapper", "key.mapper", "macrodroid", "macro",
        "tasker", "automate", "autoinput", "autotap", "auto.tap", "autotouch"
    )

    /**
     * Pure predicate: does this package look like an automation / input-injection
     * tool that could bypass enforcement? Exposed for unit testing.
     */
    fun isAutomationToolPackage(packageName: String): Boolean {
        val pkg = packageName.lowercase()
        return pkg in AUTOMATION_TOOL_PACKAGES ||
                AUTOMATION_TOOL_KEYWORDS.any { pkg.contains(it) }
    }

    /**
     * Enumerate enabled accessibility services that are NOT ours and look like
     * automation / input-injection tools (lock-screen bypass risk). Returns their
     * raw service ids ("package/class"). Empty when none are detected.
     */
    fun getEnabledAutomationToolServices(context: Context): List<String> {
        val ourPackage = context.packageName
        val enabled = try {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
        } catch (e: Exception) {
            Timber.w(e, "$TAG: Error reading enabled accessibility services for bypass-tool scan")
            null
        } ?: return emptyList()

        if (enabled.isBlank()) return emptyList()

        return enabled.split(":")
            .mapNotNull { it.trim().takeIf(String::isNotEmpty) }
            .filter { serviceId ->
                val pkg = ComponentName.unflattenFromString(serviceId)?.packageName
                    ?: serviceId.substringBefore("/")
                pkg != ourPackage && isAutomationToolPackage(pkg)
            }
            .distinct()
    }

    /**
     * Get detailed diagnostic information about the accessibility service state.
     * Useful for debugging when the service appears enabled but isn't working.
     */
    fun getDiagnosticInfo(context: Context): String {
        val sb = StringBuilder()

        // Check Settings.Secure
        val enabledServicesString = try {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: "(empty)"
        } catch (e: Exception) {
            "(error: ${e.message})"
        }
        sb.appendLine("Enabled services in Settings: $enabledServicesString")

        // Check our service component name
        val serviceComponentName = ComponentName(context, TextMonitoringAccessibilityService::class.java)
        sb.appendLine("Our service component: ${serviceComponentName.flattenToString()}")

        // Check if enabled via settings
        val isEnabledInSettings = enabledServicesString.contains(serviceComponentName.className) ||
                enabledServicesString.contains(serviceComponentName.flattenToString())
        sb.appendLine("Enabled in Settings.Secure: $isEnabledInSettings")

        // Check AccessibilityManager
        val accessibilityManager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        if (accessibilityManager != null) {
            val enabledServices = accessibilityManager.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK
            )
            sb.appendLine("AccessibilityManager enabled services count: ${enabledServices.size}")
            enabledServices.forEach { info ->
                sb.appendLine("  - ${info.id}")
            }

            val isOurServiceListed = enabledServices.any { info ->
                info.id.contains(serviceComponentName.className)
            }
            sb.appendLine("Our service in AccessibilityManager list: $isOurServiceListed")
        } else {
            sb.appendLine("AccessibilityManager: null")
        }

        // Check if service class was ever instantiated
        sb.appendLine("Service ever created (static flag): ${wasServiceEverCreated()}")

        // Check battery optimization status
        val isIgnoringBatteryOpt = isIgnoringBatteryOptimizations(context)
        sb.appendLine("Ignoring battery optimizations: $isIgnoringBatteryOpt")

        // Device info
        sb.appendLine("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        sb.appendLine("Android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")

        return sb.toString()
    }

    /**
     * Open system accessibility settings so the user can enable our service.
     *
     * @param context Context for starting the activity
     */
    fun openAccessibilitySettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            Timber.d("$TAG: Opened accessibility settings")
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to open accessibility settings")
            // Fallback to main settings if specific settings fail
            openMainSettings(context)
        }
    }

    /**
     * Fallback to open main settings if accessibility settings cannot be opened directly.
     */
    private fun openMainSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            Timber.d("$TAG: Opened main settings as fallback")
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to open any settings")
        }
    }

    /**
     * Get a user-friendly description of why the accessibility service is needed.
     */
    fun getServiceDescription(): String {
        return "Text monitoring requires the accessibility service to detect inappropriate " +
                "content in messages and apps. This helps protect your child from harmful " +
                "content including cyberbullying, inappropriate language, and predatory behavior."
    }

    /**
     * Get step-by-step instructions for enabling the service.
     */
    fun getEnableInstructions(): List<String> {
        return listOf(
            "1. Tap 'Open Settings' to go to Accessibility settings",
            "2. Find 'Haris' or 'Installed services'",
            "3. Tap on 'Haris Text Monitoring'",
            "4. Toggle the switch to enable the service",
            "5. Confirm when prompted by the system"
        )
    }

    /**
     * Check all permission states for text monitoring.
     *
     * @param context Application context
     * @return PermissionState with all relevant permission statuses
     */
    fun getTextMonitoringPermissionState(context: Context): TextMonitoringPermissionState {
        return TextMonitoringPermissionState(
            accessibilityEnabled = isServiceEnabled(context),
            overlayEnabled = Settings.canDrawOverlays(context)
        )
    }

    /**
     * Get the device manufacturer for OEM-specific instructions.
     */
    fun getDeviceManufacturer(): String {
        return android.os.Build.MANUFACTURER.lowercase()
    }

    /**
     * Check if this is a device from a manufacturer known for aggressive battery optimization.
     * These manufacturers often kill accessibility services more aggressively.
     */
    fun isAggressiveBatteryOptimizationDevice(): Boolean {
        val manufacturer = getDeviceManufacturer()
        return manufacturer in listOf(
            "xiaomi", "redmi", "poco",
            "huawei", "honor",
            "oppo", "realme", "oneplus",
            "vivo", "iqoo",
            "samsung",
            "meizu",
            "asus",
            "nokia",
            "lenovo"
        )
    }

    /**
     * Get OEM-specific instructions for keeping the app running in background.
     * Different manufacturers have different settings locations.
     */
    fun getOemBatteryOptimizationInstructions(): List<String> {
        val manufacturer = getDeviceManufacturer()

        return when {
            manufacturer in listOf("xiaomi", "redmi", "poco") -> listOf(
                "1. Go to Settings > Apps > Manage apps",
                "2. Find 'Haris' and tap it",
                "3. Tap 'Battery saver' or 'Power saver'",
                "4. Select 'No restrictions'",
                "5. Also enable 'Autostart' permission",
                "6. In MIUI Security app, add Haris to 'Autostart' list"
            )
            manufacturer in listOf("huawei", "honor") -> listOf(
                "1. Go to Settings > Apps > Apps",
                "2. Find 'Haris' and tap it",
                "3. Tap 'Battery' > 'App launch'",
                "4. Disable 'Manage automatically'",
                "5. Enable all three options: Auto-launch, Secondary launch, Run in background",
                "6. Also check Settings > Battery > Launch for the same options"
            )
            manufacturer in listOf("oppo", "realme", "oneplus") -> listOf(
                "1. Go to Settings > Battery",
                "2. Tap 'Battery optimization'",
                "3. Find 'Haris' and select 'Don't optimize'",
                "4. Also go to Settings > Apps > Haris",
                "5. Enable 'Allow auto-launch' and 'Allow background activity'"
            )
            manufacturer in listOf("vivo", "iqoo") -> listOf(
                "1. Go to Settings > Battery",
                "2. Tap 'Background power consumption management'",
                "3. Find 'Haris' and allow background running",
                "4. Also check 'High background power consumption' settings"
            )
            manufacturer == "samsung" -> listOf(
                "1. Go to Settings > Apps > Haris",
                "2. Tap 'Battery' and select 'Unrestricted'",
                "3. Go to Settings > Device care > Battery",
                "4. Tap menu (3 dots) > Settings",
                "5. Disable 'Put unused apps to sleep'",
                "6. Add Haris to 'Apps that won't be put to sleep'"
            )
            manufacturer == "asus" -> listOf(
                "1. Go to Settings > Battery",
                "2. Tap 'PowerMaster' or 'Auto-start Manager'",
                "3. Enable auto-start for 'Haris'",
                "4. In Battery optimization, select 'Not optimized' for Haris"
            )
            manufacturer == "nokia" -> listOf(
                "1. Go to Settings > Apps > Haris",
                "2. Tap 'Battery' and select 'Don't optimize'",
                "3. Enable 'Allow background activity'"
            )
            else -> listOf(
                "1. Go to Settings > Apps > Haris",
                "2. Tap 'Battery' and select 'Unrestricted' or 'Don't optimize'",
                "3. Look for 'Autostart' or 'Background activity' options and enable them",
                "4. Check your phone's battery settings for app-specific optimizations"
            )
        }
    }

    /**
     * Open the battery optimization settings.
     * This is where users can disable battery optimization for the app.
     */
    fun openBatteryOptimizationSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            Timber.d("$TAG: Opened battery optimization settings")
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to open battery optimization settings")
            // Try to open app-specific battery settings
            openAppBatterySettings(context)
        }
    }

    /**
     * Open app-specific battery settings if available.
     */
    private fun openAppBatterySettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.parse("package:${context.packageName}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            Timber.d("$TAG: Opened app details settings")
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to open app settings")
            openMainSettings(context)
        }
    }

    /**
     * Request to ignore battery optimizations for this app.
     * This shows a system dialog asking the user to allow the app to run unrestricted.
     *
     * IMPORTANT: This requires REQUEST_IGNORE_BATTERY_OPTIMIZATIONS permission.
     */
    @android.annotation.SuppressLint("BatteryLife")
    fun requestIgnoreBatteryOptimizations(context: Context) {
        try {
            val packageName = context.packageName
            val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager

            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = android.net.Uri.parse("package:$packageName")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                Timber.d("$TAG: Requested to ignore battery optimizations")
            } else {
                Timber.d("$TAG: Already ignoring battery optimizations")
            }
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to request ignore battery optimizations")
            // Fall back to opening the settings manually
            openBatteryOptimizationSettings(context)
        }
    }

    /**
     * Check if the app is ignoring battery optimizations.
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            pm.isIgnoringBatteryOptimizations(context.packageName)
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to check battery optimization status")
            false
        }
    }
}

/**
 * Data class representing the permission state for text monitoring.
 */
data class TextMonitoringPermissionState(
    val accessibilityEnabled: Boolean,
    val overlayEnabled: Boolean
) {
    /**
     * Check if all required permissions are granted.
     */
    val allPermissionsGranted: Boolean
        get() = accessibilityEnabled

    /**
     * Get a list of missing permissions.
     */
    val missingPermissions: List<String>
        get() = buildList {
            if (!accessibilityEnabled) add("Accessibility Service")
        }

    /**
     * Get a human-readable status message.
     */
    fun getStatusMessage(): String {
        return when {
            allPermissionsGranted -> "Text monitoring is fully enabled"
            !accessibilityEnabled -> "Accessibility service needs to be enabled for text monitoring"
            else -> "Some permissions are missing"
        }
    }
}
