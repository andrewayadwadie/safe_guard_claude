package com.safeguard.parentalcontrol.receiver

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.safeguard.parentalcontrol.service.ContentFilterVpnService
import com.safeguard.parentalcontrol.service.MonitoringService
import com.safeguard.parentalcontrol.util.PreferencesManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber

/**
 * Worker to start monitoring service after boot
 * Used as fallback when direct service start fails from BootReceiver
 */
@HiltWorker
class BootServiceStartWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val preferencesManager: PreferencesManager
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Timber.d("BootServiceStartWorker running")

        return try {
            // Verify user is still logged in
            if (!preferencesManager.isLoggedIn || !preferencesManager.isDeviceRegistered) {
                Timber.d("User not logged in or device not registered - skipping service start")
                return Result.success()
            }

            // Start the monitoring service
            val monitoringIntent = Intent(appContext, MonitoringService::class.java)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appContext.startForegroundService(monitoringIntent)
            } else {
                appContext.startService(monitoringIntent)
            }

            Timber.d("Monitoring service started via WorkManager")

            // Also start VPN content filtering if enabled
            if (preferencesManager.isContentFilteringEnabled) {
                try {
                    val vpnIntent = Intent(appContext, ContentFilterVpnService::class.java)

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        appContext.startForegroundService(vpnIntent)
                    } else {
                        appContext.startService(vpnIntent)
                    }

                    Timber.d("VPN content filter service started via WorkManager")
                } catch (e: Exception) {
                    Timber.e(e, "Failed to start VPN service in worker")
                    // Don't fail the whole worker, monitoring service is more important
                }
            }

            Result.success()
        } catch (e: Exception) {
            Timber.e(e, "Failed to start monitoring service in worker")
            // Don't retry - if it fails here, something is fundamentally wrong
            Result.failure()
        }
    }
}
