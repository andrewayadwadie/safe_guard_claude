package com.safeguard.parentalcontrol.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.safeguard.parentalcontrol.R
import com.safeguard.parentalcontrol.SafeGuardApplication
import com.safeguard.parentalcontrol.data.repository.DeviceRepository
import com.safeguard.parentalcontrol.presentation.MainActivity
import com.safeguard.parentalcontrol.util.Constants
import com.safeguard.parentalcontrol.worker.SyncWorker
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import timber.log.Timber
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

/**
 * Firebase Cloud Messaging service for receiving push notifications
 *
 * Improvements:
 * - Fixed notification ID overflow issue
 * - Proper CoroutineScope lifecycle management
 * - Uses WorkManager for sync operations
 */
@AndroidEntryPoint
class SafeGuardFirebaseMessagingService : FirebaseMessagingService() {

    @Inject
    lateinit var deviceRepository: DeviceRepository

    // Proper CoroutineScope with lifecycle management
    private var serviceJob: Job? = null
    private val serviceScope: CoroutineScope
        get() = CoroutineScope(Dispatchers.IO + (serviceJob ?: SupervisorJob().also { serviceJob = it }))

    // Thread-safe notification ID counter to prevent overflow
    private val notificationIdCounter = AtomicInteger(0)

    override fun onCreate() {
        super.onCreate()
        serviceJob = SupervisorJob()
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Timber.d("FCM token refreshed")

        // Update token on server
        serviceScope.launch {
            try {
                deviceRepository.updateFcmToken(token)
                Timber.d("FCM token updated on server")
            } catch (e: Exception) {
                Timber.e(e, "Failed to update FCM token")
            }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        Timber.d("FCM message received from: ${message.from}")

        // Handle data payload
        if (message.data.isNotEmpty()) {
            handleDataMessage(message.data)
        }

        // Handle notification payload
        message.notification?.let {
            showNotification(
                title = it.title ?: "SafeGuard",
                body = it.body ?: ""
            )
        }
    }

    private fun handleDataMessage(data: Map<String, String>) {
        val type = data["type"] ?: return

        when (type) {
            "alert" -> handleAlertMessage(data)
            "command" -> handleCommandMessage(data)
            "sync" -> handleSyncMessage()
            else -> Timber.d("Unknown message type: $type")
        }
    }

    private fun handleAlertMessage(data: Map<String, String>) {
        val title = data["title"] ?: "Alert"
        val body = data["body"] ?: ""
        val severity = data["severity"] ?: "medium"

        // Show notification with appropriate priority
        val priority = when (severity) {
            "critical" -> NotificationCompat.PRIORITY_MAX
            "high" -> NotificationCompat.PRIORITY_HIGH
            else -> NotificationCompat.PRIORITY_DEFAULT
        }

        showNotification(title, body, priority)
    }

    private fun handleCommandMessage(data: Map<String, String>) {
        val command = data["command"] ?: return

        when (command) {
            "sync" -> {
                // Trigger immediate sync via WorkManager
                SyncWorker.enqueueImmediate(this)
            }
            "lock" -> {
                // Device lock command (v2 feature)
                Timber.d("Lock command received - not implemented in v1")
            }
            "locate" -> {
                // Device location command
                Timber.d("Locate command received")
            }
            else -> Timber.d("Unknown command: $command")
        }
    }

    private fun handleSyncMessage() {
        // Trigger background sync via WorkManager
        SyncWorker.enqueueImmediate(this)
    }

    /**
     * Get next notification ID with proper overflow handling
     * Cycles through a safe range to prevent integer overflow
     */
    private fun getNextNotificationId(): Int {
        val id = notificationIdCounter.incrementAndGet()
        // Reset if approaching max value (leave headroom)
        if (id > MAX_NOTIFICATION_ID) {
            notificationIdCounter.set(0)
            return Constants.NOTIFICATION_ID_ALERT
        }
        return Constants.NOTIFICATION_ID_ALERT + id
    }

    private fun showNotification(
        title: String,
        body: String,
        priority: Int = NotificationCompat.PRIORITY_DEFAULT
    ) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, SafeGuardApplication.CHANNEL_ALERTS)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentIntent(pendingIntent)
            .setPriority(priority)
            .setAutoCancel(true)
            .build()

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(getNextNotificationId(), notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob?.cancel()
        serviceJob = null
        Timber.d("SafeGuardFirebaseMessagingService destroyed")
    }

    companion object {
        // Maximum notification ID to prevent overflow
        // Leaves headroom below Int.MAX_VALUE
        private const val MAX_NOTIFICATION_ID = 100000
    }
}
