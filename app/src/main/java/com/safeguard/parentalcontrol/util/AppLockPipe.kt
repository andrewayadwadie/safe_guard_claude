package com.safeguard.parentalcontrol.util

import timber.log.Timber

/**
 * One logcat tag for the whole app-lock path, parent screen to child enforcement.
 *
 * A lock crosses the parent ViewModel, the rules repository, the backend, the messaging
 * service, the monitoring service and the lock presenter — six classes on two devices. When
 * a lock silently fails to arrive, the question is always "which hop dropped it", and that
 * question is unanswerable if every hop logs under its own tag. Everything on the path logs
 * through here instead, so the whole chain reads as two streams:
 *
 * ```
 * adb -s <PARENT> logcat -s AppLock:V
 * adb -s <CHILD>  logcat -s AppLock:V
 * ```
 *
 * Privacy: metadata only. Permitted vocabulary is package names, HTTP status codes, field
 * names, collection sizes and timings. Bearer tokens, the device token, the parent's email
 * address and any monitored content must never reach these functions — the same contract
 * [AlertPipe] carries, and the reason [NetworkModule]'s logging interceptor redacts
 * `X-Device-Token`.
 */
object AppLockPipe {

    const val TAG = "AppLock"

    fun d(message: String) = Timber.tag(TAG).d(message)

    fun i(message: String) = Timber.tag(TAG).i(message)

    fun w(message: String) = Timber.tag(TAG).w(message)

    fun e(message: String) = Timber.tag(TAG).e(message)

    fun e(throwable: Throwable?, message: String) = Timber.tag(TAG).e(throwable, message)
}
