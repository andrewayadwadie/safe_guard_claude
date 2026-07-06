package com.safeguard.parentalcontrol.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.safeguard.parentalcontrol.util.Constants
import com.safeguard.parentalcontrol.util.PreferencesManager
import com.safeguard.parentalcontrol.worker.TamperAlertWorker
import timber.log.Timber

/**
 * BroadcastReceiver for detecting tamper attempts on the Haris app.
 *
 * Monitors for:
 * - App data being cleared (ACTION_PACKAGE_DATA_CLEARED)
 * - App being disabled/re-enabled (ACTION_PACKAGE_CHANGED)
 * - App being replaced/updated (ACTION_MY_PACKAGE_REPLACED)
 *
 * When tamper is detected, immediately sends an alert to the parent via WorkManager.
 * This provides real-time detection compared to the periodic ProtectionMonitorWorker.
 *
 * Note: Cannot detect app uninstallation from within the app itself (app is gone).
 * The backend should detect missing heartbeats for uninstall detection.
 */
class TamperDetectionReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "TamperDetectionReceiver"

        // Tamper types for categorization
        const val TAMPER_TYPE_DATA_CLEARED = "data_cleared"
        const val TAMPER_TYPE_APP_DISABLED = "app_disabled"
        const val TAMPER_TYPE_FORCE_STOPPED = "force_stopped"
        const val TAMPER_TYPE_APP_REPLACED = "app_replaced"

        // Work data keys
        const val KEY_TAMPER_TYPE = "tamper_type"
        const val KEY_TAMPER_DETAILS = "tamper_details"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return

        Timber.d("$TAG: Received broadcast: $action")

        // Check if this is a child device (only child devices need tamper detection)
        val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        val isParent = prefs.getBoolean(Constants.PREF_IS_PARENT, false)
        val isLoggedIn = prefs.getString(Constants.PREF_ACCESS_TOKEN, null) != null

        if (isParent || !isLoggedIn) {
            Timber.d("$TAG: Skipping tamper detection (isParent=$isParent, isLoggedIn=$isLoggedIn)")
            return
        }

        when (action) {
            Intent.ACTION_PACKAGE_DATA_CLEARED -> {
                // App data was cleared - this is a serious tamper attempt
                val packageName = intent.data?.schemeSpecificPart
                if (packageName == context.packageName) {
                    Timber.w("$TAG: APP DATA CLEARED - Tamper detected!")
                    enqueueTamperAlert(
                        context = context,
                        tamperType = TAMPER_TYPE_DATA_CLEARED,
                        details = "User cleared app data, all settings and monitoring history lost"
                    )
                }
            }

            Intent.ACTION_PACKAGE_CHANGED -> {
                // App was enabled/disabled or components changed
                val packageName = intent.data?.schemeSpecificPart
                if (packageName == context.packageName) {
                    // Check if app was disabled
                    val pm = context.packageManager
                    try {
                        val appInfo = pm.getApplicationInfo(context.packageName, 0)
                        if (!appInfo.enabled) {
                            Timber.w("$TAG: APP DISABLED - Tamper detected!")
                            enqueueTamperAlert(
                                context = context,
                                tamperType = TAMPER_TYPE_APP_DISABLED,
                                details = "Haris app was disabled by user"
                            )
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "$TAG: Error checking app state")
                    }
                }
            }

            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                // App was updated/replaced - not necessarily tampering, but worth logging
                Timber.i("$TAG: App was updated/replaced")
                // Restart services after update
                restartServicesAfterUpdate(context)
            }

            // Note: ACTION_PACKAGE_REMOVED cannot be received by the app being removed
            // The backend must detect missing heartbeats to know if app was uninstalled
        }
    }

    /**
     * Enqueue a WorkManager task to send tamper alert to parent.
     * Uses WorkManager because we may not have network immediately.
     */
    private fun enqueueTamperAlert(context: Context, tamperType: String, details: String) {
        Timber.w("$TAG: Enqueueing tamper alert: type=$tamperType")

        val workData = workDataOf(
            KEY_TAMPER_TYPE to tamperType,
            KEY_TAMPER_DETAILS to details
        )

        val workRequest = OneTimeWorkRequestBuilder<TamperAlertWorker>()
            .setInputData(workData)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .addTag(Constants.WORK_TAG_TAMPER_ALERT)
            .build()

        WorkManager.getInstance(context).enqueue(workRequest)

        Timber.i("$TAG: Tamper alert work enqueued")
    }

    /**
     * Restart monitoring services after app update.
     */
    private fun restartServicesAfterUpdate(context: Context) {
        try {
            // Use the BootServiceStartWorker to restart services
            val workRequest = OneTimeWorkRequestBuilder<com.safeguard.parentalcontrol.receiver.BootServiceStartWorker>()
                .addTag("restart_after_update")
                .build()

            WorkManager.getInstance(context).enqueue(workRequest)

            Timber.i("$TAG: Services restart scheduled after update")
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Error scheduling service restart")
        }
    }
}
