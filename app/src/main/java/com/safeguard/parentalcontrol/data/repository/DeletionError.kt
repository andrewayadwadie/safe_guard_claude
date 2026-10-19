package com.safeguard.parentalcontrol.data.repository

import com.safeguard.parentalcontrol.data.remote.NetworkResult

/**
 * `safeApiCall` turns a 2xx with a null body (always the case for a 204) into
 * `Error("Empty response body", 2xx)`. For a deletion, success depends on the status code only,
 * so remap that case here instead of changing `safeApiCall` for everyone.
 */
internal fun NetworkResult<Unit>.treatEmpty2xxAsSuccess(): NetworkResult<Unit> =
    if (this is NetworkResult.Error && code != null && code in 200..299) NetworkResult.Success(Unit) else this

/**
 * Typed outcome of a failed account-deletion step. Repositories keep returning
 * `NetworkResult<Unit>`; the mapping to this type happens outside them (constitution rule 9).
 *
 * The mappers key on the HTTP status code only, never on the message text, which may change or
 * be localized. Only [WrongPassword] may ever produce "wrong password" wording.
 */
sealed class DeletionError {
    /** 400 on self-delete, or 400/401 from the re-authentication login. */
    data object WrongPassword : DeletionError()

    /** 403: a supervised child may not delete itself. */
    data object SupervisedChild : DeletionError()

    /** 404 on `DELETE family/child/{id}` only. */
    data object ChildNotFound : DeletionError()

    /** 401 on an authenticated call, which has already been through the AuthInterceptor. */
    data object SessionOver : DeletionError()

    /**
     * Re-authentication found no stored session, or the server authenticated a different user
     * than the one signed in. Uses a client-only sentinel code because the re-auth endpoints
     * bypass the interceptor and a 401 there already means "wrong password".
     */
    data object AccountMismatch : DeletionError()

    /** 429. [retryAfterSeconds] stays null until the Retry-After header is exposed (backend Q3). */
    data class RateLimited(val retryAfterSeconds: Long?) : DeletionError()

    /** No HTTP code: offline, timeout or a connection failure. */
    data object Network : DeletionError()

    data class Unknown(val code: Int) : DeletionError()

    companion object {
        /** Client-side sentinel that no HTTP status can collide with. */
        const val CODE_ACCOUNT_MISMATCH = -1001

        fun fromSelfDelete(error: NetworkResult.Error): DeletionError = when (val code = error.code) {
            400 -> WrongPassword
            401 -> SessionOver
            403 -> SupervisedChild
            429 -> RateLimited(null)
            null -> Network
            else -> Unknown(code)
        }

        fun fromChildDelete(error: NetworkResult.Error): DeletionError = when (val code = error.code) {
            401 -> SessionOver
            404 -> ChildNotFound
            429 -> RateLimited(null)
            null -> Network
            else -> Unknown(code)
        }

        fun fromReauth(error: NetworkResult.Error): DeletionError = when (val code = error.code) {
            400, 401 -> WrongPassword
            CODE_ACCOUNT_MISMATCH -> AccountMismatch
            429 -> RateLimited(null)
            null -> Network
            else -> Unknown(code)
        }
    }
}
