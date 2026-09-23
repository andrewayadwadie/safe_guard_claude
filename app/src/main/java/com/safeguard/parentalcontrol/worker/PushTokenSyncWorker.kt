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
import com.safeguard.parentalcontrol.util.AlertPipe
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
            AlertPipe.d("TOKEN SKIPPED no session")
            return Result.success()
        }

        val token = fcmTokenProvider.currentToken()
        if (token == null) {
            Timber.d("$TAG: no FCM token available yet; will retry")
            AlertPipe.w("TOKEN UNAVAILABLE Firebase returned no token; will retry")
            return Result.retry()
        }

        val role = if (preferencesManager.isParent) "parent" else "child"
        AlertPipe.i("TOKEN OBTAINED role=$role token=${AlertPipe.fingerprint(token)}")

        // Publication is idempotent but not free: this worker runs on every app start, so an
        // unchanged token would otherwise cost a network round trip per launch. The cache key
        // covers the destination as well as the token, so a child that registers its device
        // (deviceDbId -1 -> real id) or a role change republishes instead of being skipped.
        val publicationKey = publicationKey(role, token)
        if (preferencesManager.publishedFcmTokenHash == publicationKey) {
            AlertPipe.d("TOKEN SKIPPED unchanged since last accepted publication role=$role")
            return Result.success()
        }

        val endpoint: String
        val result = when {
            preferencesManager.isParent -> {
                endpoint = "PUT auth/me/fcm-token"
                AlertPipe.i("TOKEN UPLOAD attempt endpoint=$endpoint role=parent")
                authRepository.publishFcmToken(token)
            }
            preferencesManager.deviceDbId != -1 -> {
                endpoint = "PUT devices/${preferencesManager.deviceDbId}"
                AlertPipe.i("TOKEN UPLOAD attempt endpoint=$endpoint role=child")
                deviceRepository.updateFcmToken(token)
            }
            else -> {
                // Child device that has not registered yet: registration itself carries the
                // token, so there is nothing to do and nothing to retry.
                Timber.d("$TAG: child device not registered yet; token ships with registration")
                AlertPipe.d("TOKEN SKIPPED child not registered; token ships with device registration")
                return Result.success()
            }
        }

        return when (result) {
            is NetworkResult.Success -> {
                Timber.d("$TAG: push token synced")
                AlertPipe.i("TOKEN UPLOADED status=200 endpoint=$endpoint role=$role")
                // Recorded only once the backend has accepted it, so a failed publication is
                // always retried rather than being cached as done.
                preferencesManager.publishedFcmTokenHash = publicationKey
                Result.success()
            }
            is NetworkResult.Error -> {
                preferencesManager.publishedFcmTokenHash = null
                // A 4xx will not start succeeding on retry; anything else might.
                val code = result.code
                if (code != null && code in 400..499) {
                    Timber.w("$TAG: push token rejected (code=$code)")
                    AlertPipe.w("TOKEN UPLOAD REJECTED status=$code endpoint=$endpoint body=${result.message} -> giving up")
                    Result.failure()
                } else {
                    AlertPipe.w("TOKEN UPLOAD FAILED status=${code ?: "no-response"} endpoint=$endpoint body=${result.message} -> retry with backoff")
                    Result.retry()
                }
            }
            is NetworkResult.Loading -> Result.retry()
        }
    }

    /**
     * Identity of a successful publication: which token, sent where. Comparing the whole tuple
     * (not just the token) is what makes the skip safe — the same token published against a
     * different destination is a different publication.
     */
    private fun publicationKey(role: String, token: String): String =
        "$role:${preferencesManager.deviceDbId}:${AlertPipe.digest(token)}"

    companion object {
        private const val TAG = "PushTokenSyncWorker"
        private const val BACKOFF_SECONDS = 10L

        /**
         * Publish the current token. Unique with KEEP by default, so overlapping routine
         * triggers (app start, sign-in, token rotation) collapse into one run.
         *
         * @param replaceExisting supersede an in-flight run. Used after device registration,
         *   where an already-running pass may have read `deviceDbId` while it was still -1 and
         *   would otherwise cache a publication made against the wrong destination.
         */
        fun enqueue(context: Context, replaceExisting: Boolean = false) {
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
                if (replaceExisting) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                request
            )
        }
    }
}
