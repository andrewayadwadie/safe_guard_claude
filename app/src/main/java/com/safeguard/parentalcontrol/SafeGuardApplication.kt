package com.safeguard.parentalcontrol

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.safeguard.parentalcontrol.util.PreferencesManager
import com.safeguard.parentalcontrol.util.ProtectionStatusHelper
import com.safeguard.parentalcontrol.worker.ProtectionMonitorWorker
import com.safeguard.parentalcontrol.worker.SyncWorker
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
import javax.inject.Inject

/**
 * Haris Application class
 * Initializes Hilt dependency injection, WorkManager, and logging
 */
@HiltAndroidApp
class SafeGuardApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var preferencesManager: PreferencesManager

    override fun onCreate() {
        super.onCreate()

        // Initialize Timber logging
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        // Grandfather already-registered devices into the monitoring-consent gate before
        // any service start path can evaluate it (see migrateMonitoringConsentIfNeeded).
        preferencesManager.migrateMonitoringConsentIfNeeded()

        // Create notification channels
        createNotificationChannels()

        // Start periodic workers
        startWorkers()

        Timber.d("Haris Application initialized")
    }

    /**
     * Start background workers for monitoring and sync.
     */
    private fun startWorkers() {
        // Check if app was started manually (not via boot receiver)
        // This helps detect if auto-start is disabled
        ProtectionStatusHelper.checkAutoStartOnManualLaunch(this)

        // Start sync worker for data synchronization
        SyncWorker.enqueue(this)

        // Start protection monitor worker to detect if ANY protection is disabled
        // This monitors: Accessibility, Usage Stats, Overlay, Notifications, Battery Optimization, Auto-Start
        // If any protection is disabled, parent is alerted (not the child)
        ProtectionMonitorWorker.enqueue(this)

        // Also do an immediate check on app start
        ProtectionMonitorWorker.checkNow(this)

        Timber.d("Background workers started")
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) android.util.Log.DEBUG else android.util.Log.ERROR)
            .build()

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)

            // Monitoring Service Channel (low importance - persistent notification)
            val monitoringChannel = NotificationChannel(
                CHANNEL_MONITORING_SERVICE,
                "Monitoring Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when Haris is actively monitoring"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(monitoringChannel)

            // Alerts Channel (high importance)
            val alertsChannel = NotificationChannel(
                CHANNEL_ALERTS,
                "Security Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Important security and monitoring alerts"
                enableVibration(true)
                enableLights(true)
            }
            notificationManager.createNotificationChannel(alertsChannel)

            // Screen Time Channel (default importance)
            val screenTimeChannel = NotificationChannel(
                CHANNEL_SCREEN_TIME,
                "Screen Time",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Screen time limit notifications"
            }
            notificationManager.createNotificationChannel(screenTimeChannel)

            // Sync Channel (low importance)
            val syncChannel = NotificationChannel(
                CHANNEL_SYNC,
                "Data Sync",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background data synchronization"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(syncChannel)

            // Lock Screen Channel (max importance for full-screen intent)
            val lockScreenChannel = NotificationChannel(
                CHANNEL_LOCK_SCREEN,
                "Lock Screen",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Screen time limit enforcement"
                enableVibration(true)
                enableLights(true)
                setBypassDnd(true) // Bypass Do Not Disturb
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
            notificationManager.createNotificationChannel(lockScreenChannel)

            Timber.d("Notification channels created")
        }
    }

    companion object {
        const val CHANNEL_MONITORING_SERVICE = "monitoring_service"
        const val CHANNEL_ALERTS = "alerts"
        const val CHANNEL_SCREEN_TIME = "screen_time"
        const val CHANNEL_SYNC = "sync"
        const val CHANNEL_LOCK_SCREEN = "lock_screen"
    }
}
