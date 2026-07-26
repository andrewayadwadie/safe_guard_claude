package com.safeguard.parentalcontrol.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.safeguard.parentalcontrol.R
import com.safeguard.parentalcontrol.SafeGuardApplication
import com.safeguard.parentalcontrol.presentation.MainActivity
import com.safeguard.parentalcontrol.util.Constants
import com.safeguard.parentalcontrol.util.PreferencesManager
import com.safeguard.parentalcontrol.util.ViolationNotifier
import com.safeguard.parentalcontrol.worker.PushTokenSyncScheduler
import com.safeguard.parentalcontrol.worker.SyncWorker
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

/**
 * Firebase Cloud Messaging service for receiving push notifications.
 *
 * Holds no business logic: every message type is routed to an injected collaborator
 * (Constitution enforcement rule 7). Violation alerts go to [ViolationNotifier], token
 * refreshes to [PushTokenSyncWorker], sync commands to [SyncWorker].
 */
@AndroidEntryPoint
class SafeGuardFirebaseMessagingService : FirebaseMessagingService() {

    @Inject
    lateinit var violationNotifier: ViolationNotifier

    @Inject
    lateinit var preferencesManager: PreferencesManager

    @Inject
    lateinit var pushTokenSyncScheduler: PushTokenSyncScheduler

    // Thread-safe notification ID counter to prevent overflow
    private val notificationIdCounter = AtomicInteger(0)

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Timber.d("FCM token refreshed")

        // Publish the new token for whichever role is signed in. The worker owns the role
        // routing (parent -> PUT /auth/me/fcm-token, child -> PUT /devices/{id}) and gives
        // us retry + offline handling for free.
        pushTokenSyncScheduler.schedule()
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        Timber.d("FCM message received from: ${message.from}")

        // Handle data payload
        if (message.data.isNotEmpty()) {
            handleDataMessage(message.data)
        }

        // Handle notification payload. Violation pushes are data-only by contract, so this
        // branch only fires for other server-sent notifications — but guard the role anyway
        // so a stray notification-block violation can never surface on a child device.
        message.notification?.let {
            if (!preferencesManager.isParent && message.data["type"] == "alert") {
                Timber.d("Notification-block alert received on a non-parent device; discarded")
                return@let
            }
            showNotification(
                title = it.title ?: "Haris",
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
        violationNotifier.handleViolationPush(data)
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

    companion object {
        // Maximum notification ID to prevent overflow
        // Leaves headroom below Int.MAX_VALUE
        private const val MAX_NOTIFICATION_ID = 100000
    }
}
