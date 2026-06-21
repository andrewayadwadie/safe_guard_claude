package com.safeguard.parentalcontrol.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.safeguard.parentalcontrol.service.ContentFilterVpnService
import com.safeguard.parentalcontrol.service.MonitoringService
import com.safeguard.parentalcontrol.util.Constants
import com.safeguard.parentalcontrol.worker.ProtectionMonitorWorker
import com.safeguard.parentalcontrol.worker.SyncWorker
import timber.log.Timber

/**
 * Broadcast receiver that restarts monitoring services when the device wakes up.
 *
 * This is especially important for Samsung devices running Android 16+ with
 * aggressive battery optimization that kills background services during sleep.
 *
 * Events we listen for:
 * - ACTION_USER_PRESENT: User unlocked the device (most reliable)
 * - ACTION_SCREEN_ON: Screen turned on
 *
 * When these events occur, we check if our services are running and restart them if needed.
 */
class ServiceRestartReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_USER_PRESENT -> {
                Timber.d("ServiceRestartReceiver: User present - checking services")
                checkAndRestartServices(context)
            }
            Intent.ACTION_SCREEN_ON -> {
                Timber.d("ServiceRestartReceiver: Screen on - checking services")
                // Use a slight delay for screen on to avoid race conditions
                checkAndRestartServices(context)
            }
        }
    }

    private fun checkAndRestartServices(context: Context) {
        // Check if user is logged in and device is registered
        val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        val isLoggedIn = prefs.getBoolean(Constants.KEY_IS_LOGGED_IN, false)
        val isDeviceRegistered = prefs.getBoolean(Constants.KEY_IS_DEVICE_REGISTERED, false)
        val isParent = prefs.getString(Constants.KEY_USER_ROLE, null) == "parent"

        if (!isLoggedIn || !isDeviceRegistered) {
            Timber.d("ServiceRestartReceiver: User not logged in or device not registered")
            return
        }

        // Parents don't need monitoring services
        if (isParent) {
            Timber.d("ServiceRestartReceiver: Parent device - skipping service restart")
            return
        }

        try {
            // Restart monitoring service if not running
            if (!MonitoringService.isRunning) {
                Timber.w("ServiceRestartReceiver: MonitoringService not running - restarting")
                val serviceIntent = Intent(context, MonitoringService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }

            // Check VPN service if content filtering is enabled
            val contentFilteringEnabled = prefs.getBoolean(Constants.KEY_CONTENT_FILTERING_ENABLED, false)
            if (contentFilteringEnabled && !ContentFilterVpnService.isVpnRunning(context)) {
                Timber.w("ServiceRestartReceiver: VPN service not running but content filtering enabled - restarting")
                val vpnIntent = Intent(context, ContentFilterVpnService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(vpnIntent)
                } else {
                    context.startService(vpnIntent)
                }
            }

            // Ensure workers are running
            SyncWorker.enqueue(context)
            ProtectionMonitorWorker.enqueue(context)

        } catch (e: Exception) {
            Timber.e(e, "ServiceRestartReceiver: Failed to restart services")
        }
    }
}
