package com.safeguard.parentalcontrol.util

import timber.log.Timber

/**
 * One logcat tag for the whole violation-alert delivery path.
 *
 * The pipeline crosses a detection service, a repository, two workers, the messaging service
 * and a notifier — six classes, so six Timber tags, so six logcat filters to hold at once
 * while a QA scenario runs. Everything on the path logs through here instead, and the whole
 * child-to-parent flow reads as one stream:
 *
 * ```
 * adb logcat -s ALERT_PIPE:V
 * ```
 *
 * Privacy: nothing here may carry monitored content. Callers pass metadata only — categories,
 * confidence, package names, HTTP status, metadata *keys*. Raw flagged text and full push or
 * auth tokens must never reach these functions. [fingerprint] exists so a token can be
 * correlated across devices without being disclosed.
 */
object AlertPipe {

    const val TAG = "ALERT_PIPE"

    fun d(message: String) = Timber.tag(TAG).d(message)

    fun i(message: String) = Timber.tag(TAG).i(message)

    fun w(message: String) = Timber.tag(TAG).w(message)

    fun e(throwable: Throwable?, message: String) = Timber.tag(TAG).e(throwable, message)

    /**
     * Enough of a token to tell "the parent registered token X and the push went to token X"
     * apart from "those are two different tokens", and not enough to send anything with it.
     * FCM tokens run ~160+ characters, so a 12-character prefix identifies without enabling.
     */
    fun fingerprint(token: String): String = "${token.take(12)}…(len=${token.length})"

    /**
     * Stable digest of a token, for answering "is this the same token as last time?" without
     * retaining the token. Used for the published-token cache, never logged in full.
     */
    fun digest(token: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(token.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
