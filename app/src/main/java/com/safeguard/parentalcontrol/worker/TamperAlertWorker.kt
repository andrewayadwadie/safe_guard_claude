package com.safeguard.parentalcontrol.worker

import android.content.Context
import android.os.Build
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.safeguard.parentalcontrol.data.model.AlertSeverity
import com.safeguard.parentalcontrol.data.model.AlertType
import com.safeguard.parentalcontrol.data.remote.NetworkResult
import com.safeguard.parentalcontrol.data.repository.AlertRepository
import com.safeguard.parentalcontrol.receiver.TamperDetectionReceiver
import com.safeguard.parentalcontrol.util.PreferencesManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber

/**
 * WorkManager worker for sending tamper alerts to the parent.
 *
 * This worker is triggered by TamperDetectionReceiver when tamper attempts
 * are detected (app data cleared, app disabled, etc.).
 *
 * Sends a CRITICAL alert to the backend which:
 * 1. Stores the alert in the database
 * 2. Sends a push notification to the parent's device
 */
@HiltWorker
class TamperAlertWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val alertRepository: AlertRepository,
    private val preferencesManager: PreferencesManager
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "TamperAlertWorker"
    }

    override suspend fun doWork(): Result {
        Timber.d("$TAG: Starting tamper alert work")

        // Get tamper details from input data
        val tamperType = inputData.getString(TamperDetectionReceiver.KEY_TAMPER_TYPE)
            ?: return Result.failure()
        val tamperDetails = inputData.getString(TamperDetectionReceiver.KEY_TAMPER_DETAILS)
            ?: "Unknown tamper attempt"

        // Skip if not a child device
        if (preferencesManager.isParent) {
            Timber.d("$TAG: Skipping for parent device")
            return Result.success()
        }

        // Skip if device not registered
        if (!preferencesManager.isDeviceRegistered) {
            Timber.d("$TAG: Device not registered, skipping")
            return Result.failure()
        }

        // Create alert based on tamper type
        val (title, message, severity) = when (tamperType) {
            TamperDetectionReceiver.TAMPER_TYPE_DATA_CLEARED -> Triple(
                "App Data Cleared",
                "Haris data was cleared on ${preferencesManager.deviceName ?: Build.MODEL}. " +
                    "All monitoring settings and history have been lost. " +
                    "The child may be attempting to bypass parental controls.",
                AlertSeverity.CRITICAL
            )
            TamperDetectionReceiver.TAMPER_TYPE_APP_DISABLED -> Triple(
                "Haris Disabled",
                "Haris was disabled on ${preferencesManager.deviceName ?: Build.MODEL}. " +
                    "Monitoring is no longer active. Please re-enable the app.",
                AlertSeverity.CRITICAL
            )
            TamperDetectionReceiver.TAMPER_TYPE_FORCE_STOPPED -> Triple(
                "Haris Force Stopped",
                "Haris was force stopped on ${preferencesManager.deviceName ?: Build.MODEL}. " +
                    "Monitoring has been interrupted.",
                AlertSeverity.HIGH
            )
            "vpn_disconnected" -> Triple(
                "VPN Content Filter Disabled",
                "The VPN content filter was disconnected on ${preferencesManager.deviceName ?: Build.MODEL}. " +
                    "Web content filtering is no longer protecting this device. " +
                    "Please reconnect the VPN in the Haris app.",
                AlertSeverity.HIGH
            )
            "foreign_vpn" -> Triple(
                "Another VPN App Detected",
                "A third-party VPN app is active on ${preferencesManager.deviceName ?: Build.MODEL}. " +
                    "Android allows only one VPN at a time, so SafeGuard's content filter cannot run " +
                    "while it is on and web filtering is bypassed. Check the device for a VPN app " +
                    "(e.g. ProtonVPN) and remove it or turn it off.",
                AlertSeverity.HIGH
            )
            "service_stopped" -> Triple(
                "Monitoring Service Stopped",
                "The Haris monitoring service was stopped on ${preferencesManager.deviceName ?: Build.MODEL}. " +
                    "Screen time tracking and app monitoring are interrupted.",
                AlertSeverity.HIGH
            )
            else -> Triple(
                "Tamper Attempt Detected",
                "A tamper attempt was detected on ${preferencesManager.deviceName ?: Build.MODEL}: $tamperDetails",
                AlertSeverity.HIGH
            )
        }

        // Build metadata
        val metadata = mapOf(
            "tamper_type" to tamperType,
            "tamper_details" to tamperDetails,
            "device_name" to (preferencesManager.deviceName ?: "Unknown"),
            "manufacturer" to Build.MANUFACTURER,
            "model" to Build.MODEL,
            "android_version" to Build.VERSION.RELEASE,
            "timestamp" to System.currentTimeMillis()
        )

        // Send alert
        val result = alertRepository.createAlert(
            alertType = AlertType.DEVICE_ADMIN_DISABLED, // Using existing alert type for tamper
            severity = severity,
            title = title,
            message = message,
            metadata = metadata
        )

        return when (result) {
            is NetworkResult.Success -> {
                Timber.w("$TAG: Tamper alert sent successfully: $title")
                Result.success()
            }
            is NetworkResult.Error -> {
                Timber.e("$TAG: Failed to send tamper alert: ${result.message}")
                // Retry if network issue
                if (runAttemptCount < 3) {
                    Result.retry()
                } else {
                    Result.failure()
                }
            }
            is NetworkResult.Loading -> {
                Result.retry()
            }
        }
    }
}
