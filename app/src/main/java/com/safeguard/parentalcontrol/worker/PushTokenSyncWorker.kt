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
import com.safeguard.parentalcontrol.data.remote.NetworkResult
import com.safeguard.parentalcontrol.data.repository.AuthRepository
import com.safeguard.parentalcontrol.data.repository.DeviceRepository
import com.safeguard.parentalcontrol.util.Constants
import com.safeguard.parentalcontrol.util.FcmTokenProvider
import com.safeguard.parentalcontrol.util.PreferencesManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Publishes this device's push token so the backend can reach it.
 *
 * Owns the role routing, which is why the messaging service can stay logic-free:
 * - **Parent** devices have no device record, so their token goes on the account itself
 *   (`PUT /auth/me/fcm-token`). Without this a parent is simply unreachable and no violation
 *   push can ever be delivered.
 * - **Child** devices already have a device record, so their token goes there
 *   (`PUT /devices/{id}`), alongside the token supplied at registration.
 *
 * Runs as work rather than an inline call so it retries on failure, waits for connectivity,
 * and can be triggered identically from an Activity, a ViewModel, or the messaging service.
 *
 * The logout clear is deliberately NOT handled here: it must run before the session is torn
 * down, so `AuthRepository.logout()` issues it directly.
 */
@HiltWorker
class PushTokenSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val authRepository: AuthRepository,
    private val deviceRepository: DeviceRepository,
    private val preferencesManager: PreferencesManager,
    private val fcmTokenProvider: FcmTokenProvider
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        if (!preferencesManager.isLoggedIn) {
            Timber.d("$TAG: no session; nothing to publish")
            return Result.success()
        }

        val token = fcmTokenProvider.currentToken()
        if (token == null) {
            Timber.d("$TAG: no FCM token available yet; will retry")
            return Result.retry()
        }

        val result = when {
            preferencesManager.isParent -> authRepository.publishFcmToken(token)
            preferencesManager.deviceDbId != -1 -> deviceRepository.updateFcmToken(token)
            else -> {
                // Child device that has not registered yet: registration itself carries the
                // token, so there is nothing to do and nothing to retry.
                Timber.d("$TAG: child device not registered yet; token ships with registration")
                return Result.success()
            }
        }

        return when (result) {
            is NetworkResult.Success -> {
                Timber.d("$TAG: push token synced")
                Result.success()
            }
            is NetworkResult.Error -> {
                // A 4xx will not start succeeding on retry; anything else might.
                val code = result.code
                if (code != null && code in 400..499) {
                    Timber.w("$TAG: push token rejected (code=$code)")
                    Result.failure()
                } else {
                    Result.retry()
                }
            }
            is NetworkResult.Loading -> Result.retry()
        }
    }

    companion object {
        private const val TAG = "PushTokenSyncWorker"
        private const val BACKOFF_SECONDS = 10L

        /**
         * Publish the current token. Unique with KEEP, so overlapping triggers (app start,
         * sign-in, token rotation) collapse into one run.
         */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<PushTokenSyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                .addTag(Constants.WORK_PUSH_TOKEN_SYNC)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                Constants.WORK_PUSH_TOKEN_SYNC,
                ExistingWorkPolicy.KEEP,
                request
            )
        }
    }
}
