package com.safeguard.parentalcontrol.util

import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Reads this installation's current FCM registration token.
 *
 * Wraps the Play Services `Task` by hand rather than pulling in
 * `kotlinx-coroutines-play-services` for `await()`, so this adds no dependency.
 *
 * The token is never stored locally and never logged — it is fetched fresh whenever it needs
 * to be published.
 */
@Singleton
class FcmTokenProvider @Inject constructor() {

    /**
     * @return the current token, or null when Firebase cannot supply one (no Play Services,
     *   no network on first fetch, misconfigured google-services.json). Callers treat null as
     *   "try again later" rather than an error.
     */
    suspend fun currentToken(): String? = suspendCancellableCoroutine { continuation ->
        FirebaseMessaging.getInstance().token
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    continuation.resume(task.result?.takeIf { it.isNotBlank() })
                } else {
                    Timber.w(task.exception, "Could not obtain an FCM token")
                    continuation.resume(null)
                }
            }
    }
}
