package com.safeguard.parentalcontrol.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.safeguard.parentalcontrol.data.repository.AlertRepository
import com.safeguard.parentalcontrol.util.Constants
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Delivers alerts that were held on this device because the network or the backend was
 * unavailable when they were detected.
 *
 * The CONNECTED constraint is the "deliver when connectivity returns" trigger — WorkManager
 * wakes this itself, and the work survives process death and reboot, which an in-process
 * listener would not.
 */
@HiltWorker
class PendingAlertWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val alertRepository: AlertRepository
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val outcome = alertRepository.flushPendingAlerts()

        return if (outcome.remaining > 0) {
            // Still undelivered entries: back off and try again rather than spinning.
            Timber.d("$TAG: ${outcome.remaining} alert(s) still pending; retrying later")
            Result.retry()
        } else {
            Result.success()
        }
    }

    companion object {
        private const val TAG = "PendingAlertWorker"
        private const val BACKOFF_SECONDS = 10L

        /**
         * Attempt delivery as soon as the device has a network.
         *
         * Unique with KEEP so a burst of failures schedules one drain, not one per alert;
         * the running pass picks up anything enqueued while it works.
         */
        fun enqueueImmediate(context: Context) {
            val request = OneTimeWorkRequestBuilder<PendingAlertWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                .addTag(Constants.WORK_PENDING_ALERT_DELIVERY)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                Constants.WORK_PENDING_ALERT_DELIVERY,
                ExistingWorkPolicy.KEEP,
                request
            )
            Timber.d("$TAG: pending alert delivery enqueued")
        }
    }
}
