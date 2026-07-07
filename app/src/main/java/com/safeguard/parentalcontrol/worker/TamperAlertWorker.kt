package com.safeguard.parentalcontrol.worker

import android.content.Context
import android.os.Build
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.safeguard.parentalcontrol.data.model.AlertSeverity
import com.safeguard.parentalcontrol.data.model.AlertType
import com.safeguard.parentalcontrol.R
import com.safeguard.parentalcontrol.data.remote.NetworkResult
import com.safeguard.parentalcontrol.data.repository.AlertRepository
import com.safeguard.parentalcontrol.receiver.TamperDetectionReceiver
import com.safeguard.parentalcontrol.util.LocaleHelper
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

    private fun getString(resId: Int, vararg args: Any): String =
        LocaleHelper.localizedContext(applicationContext).getString(resId, *args)

    override suspend fun doWork(): Result {
        Timber.d("$TAG: Starting tamper alert work")

        // Get tamper details from input data
        val tamperType = inputData.getString(TamperDetectionReceiver.KEY_TAMPER_TYPE)
            ?: return Result.failure()
        val tamperDetails = inputData.getString(TamperDetectionReceiver.KEY_TAMPER_DETAILS)
            ?: getString(R.string.tamper_unknown_attempt)

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
        val deviceLabel = preferencesManager.deviceName ?: Build.MODEL
        val (title, message, severity) = when (tamperType) {
            TamperDetectionReceiver.TAMPER_TYPE_DATA_CLEARED -> Triple(
                getString(R.string.tamper_data_cleared_title),
                getString(R.string.tamper_data_cleared_msg, deviceLabel),
                AlertSeverity.CRITICAL
            )
            TamperDetectionReceiver.TAMPER_TYPE_APP_DISABLED -> Triple(
                getString(R.string.tamper_app_disabled_title),
                getString(R.string.tamper_app_disabled_msg, deviceLabel),
                AlertSeverity.CRITICAL
            )
            TamperDetectionReceiver.TAMPER_TYPE_FORCE_STOPPED -> Triple(
                getString(R.string.tamper_force_stopped_title),
                getString(R.string.tamper_force_stopped_msg, deviceLabel),
                AlertSeverity.HIGH
            )
            "vpn_disconnected" -> Triple(
                getString(R.string.tamper_vpn_disconnected_title),
                getString(R.string.tamper_vpn_disconnected_msg, deviceLabel),
                AlertSeverity.HIGH
            )
            "foreign_vpn" -> Triple(
                getString(R.string.tamper_foreign_vpn_title),
                getString(R.string.tamper_foreign_vpn_msg, deviceLabel),
                AlertSeverity.HIGH
            )
            "service_stopped" -> Triple(
                getString(R.string.tamper_service_stopped_title),
                getString(R.string.tamper_service_stopped_msg, deviceLabel),
                AlertSeverity.HIGH
            )
            else -> Triple(
                getString(R.string.tamper_generic_title),
                getString(R.string.tamper_generic_msg, deviceLabel, tamperDetails),
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
