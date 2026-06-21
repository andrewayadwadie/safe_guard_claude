package com.safeguard.parentalcontrol.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.safeguard.parentalcontrol.service.ContentFilterVpnService
import com.safeguard.parentalcontrol.service.MonitoringService
import com.safeguard.parentalcontrol.util.Constants
import com.safeguard.parentalcontrol.util.PreferencesManager
import com.safeguard.parentalcontrol.util.ProtectionStatusHelper
import com.safeguard.parentalcontrol.worker.ProtectionMonitorWorker
import com.safeguard.parentalcontrol.worker.SyncWorker
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber
import javax.inject.Inject

/**
 * Broadcast receiver to start monitoring service on device boot
 *
 * Note: On cold boot, Hilt injection might not be fully initialized when onReceive is called.
 * We handle this by:
 * 1. Using goAsync() to extend processing time
 * 2. Catching UninitializedPropertyAccessException for injection failures
 * 3. Falling back to direct SharedPreferences access if Hilt fails
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject
    lateinit var preferencesManager: PreferencesManager

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON") {
            return
        }

        Timber.d("Boot completed - checking if should start monitoring")

        // CRITICAL: Record that boot receiver ran successfully
        // This is used to detect if auto-start is working
        ProtectionStatusHelper.recordBootReceiverExecution(context)

        // Use goAsync to prevent ANR on slow operations
        val pendingResult = goAsync()

        try {
            // Check if we should start the monitoring service
            val shouldStart = try {
                // Try using injected PreferencesManager first
                preferencesManager.isLoggedIn && preferencesManager.isDeviceRegistered
            } catch (e: UninitializedPropertyAccessException) {
                // Hilt injection not ready - fall back to direct SharedPreferences access
                Timber.w("Hilt injection not ready, falling back to direct SharedPreferences")
                checkLoginStatusDirect(context)
            }

            if (shouldStart) {
                startMonitoringService(context)

                // Also start VPN content filtering if it was enabled
                val contentFilteringEnabled = try {
                    preferencesManager.isContentFilteringEnabled
                } catch (e: UninitializedPropertyAccessException) {
                    checkContentFilteringDirect(context)
                }

                if (contentFilteringEnabled) {
                    startContentFilterVpnService(context)
                }

                // Start background workers for sync and accessibility monitoring
                startBackgroundWorkers(context)
            } else {
                Timber.d("User not logged in or device not registered - skipping service start")
            }
        } catch (e: Exception) {
            Timber.e(e, "Error in BootReceiver")
        } finally {
            // Always finish the async operation
            pendingResult.finish()
        }
    }

    /**
     * Direct SharedPreferences access as fallback when Hilt injection fails
     */
    private fun checkLoginStatusDirect(context: Context): Boolean {
        return try {
            val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            val isLoggedIn = prefs.getBoolean(Constants.KEY_IS_LOGGED_IN, false)
            val isDeviceRegistered = prefs.getBoolean(Constants.KEY_IS_DEVICE_REGISTERED, false)
            isLoggedIn && isDeviceRegistered
        } catch (e: Exception) {
            Timber.e(e, "Error reading SharedPreferences directly")
            false
        }
    }

    private fun startMonitoringService(context: Context) {
        try {
            val serviceIntent = Intent(context, MonitoringService::class.java)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }

            Timber.d("Monitoring service started after boot")
        } catch (e: IllegalStateException) {
            // On Android 8+, we might get IllegalStateException if app is in background
            // Fall back to scheduling immediate work
            Timber.w(e, "Cannot start foreground service immediately, scheduling via WorkManager")
            scheduleServiceStart(context)
        } catch (e: Exception) {
            Timber.e(e, "Failed to start monitoring service after boot")
        }
    }

    /**
     * Schedule service start via WorkManager as fallback
     * This handles edge cases where direct service start fails
     */
    private fun scheduleServiceStart(context: Context) {
        try {
            val workRequest = OneTimeWorkRequestBuilder<BootServiceStartWorker>()
                .addTag("boot_service_start")
                .build()

            WorkManager.getInstance(context).enqueue(workRequest)
            Timber.d("Scheduled monitoring service start via WorkManager")
        } catch (e: Exception) {
            Timber.e(e, "Failed to schedule service start via WorkManager")
        }
    }

    /**
     * Direct SharedPreferences access for content filtering status
     */
    private fun checkContentFilteringDirect(context: Context): Boolean {
        return try {
            val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            prefs.getBoolean(Constants.KEY_CONTENT_FILTERING_ENABLED, false)
        } catch (e: Exception) {
            Timber.e(e, "Error reading content filtering status directly")
            false
        }
    }

    /**
     * Start background workers for sync and protection monitoring.
     * CRITICAL: The protection monitor worker checks if ANY protection has been
     * disabled and alerts the PARENT (not the child) to re-enable it.
     *
     * Protections monitored:
     * - Accessibility Service (text monitoring)
     * - Usage Stats (screen time tracking)
     * - Overlay (lock screen)
     * - Notifications
     * - Battery Optimization
     * - VPN Service (if enabled)
     */
    private fun startBackgroundWorkers(context: Context) {
        try {
            // Start sync worker
            SyncWorker.enqueue(context)

            // Start protection monitor worker - this is CRITICAL
            // Monitors ALL protections and alerts parent if any are disabled
            ProtectionMonitorWorker.enqueue(context)

            // Do an immediate check for all protection statuses
            ProtectionMonitorWorker.checkNow(context)

            Timber.d("Background workers started after boot")
        } catch (e: Exception) {
            Timber.e(e, "Failed to start background workers after boot")
        }
    }

    /**
     * Start the VPN content filter service
     */
    private fun startContentFilterVpnService(context: Context) {
        try {
            val serviceIntent = Intent(context, ContentFilterVpnService::class.java)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }

            Timber.d("VPN content filter service started after boot")
        } catch (e: IllegalStateException) {
            Timber.w(e, "Cannot start VPN service immediately after boot")
            // VPN service requires VPN permission which was already granted
            // If it fails here, user will need to re-enable in settings
        } catch (e: Exception) {
            Timber.e(e, "Failed to start VPN content filter service after boot")
        }
    }
}
