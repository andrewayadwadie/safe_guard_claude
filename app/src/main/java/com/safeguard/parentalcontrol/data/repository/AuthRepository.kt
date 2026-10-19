package com.safeguard.parentalcontrol.data.repository

import com.safeguard.parentalcontrol.data.model.*
import com.safeguard.parentalcontrol.data.remote.ApiService
import com.safeguard.parentalcontrol.data.remote.NetworkResult
import com.safeguard.parentalcontrol.data.remote.safeApiCall
import com.safeguard.parentalcontrol.util.PreferencesManager
import com.safeguard.parentalcontrol.util.TokenManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.Response
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for authentication operations
 */
@Singleton
class AuthRepository @Inject constructor(
    private val apiService: ApiService,
    private val tokenManager: TokenManager,
    private val preferencesManager: PreferencesManager,
    private val alertRepository: AlertRepository
) {
    /**
     * Register a new user
     */
    suspend fun register(
        email: String,
        password: String,
        fullName: String,
        role: UserRole
    ): NetworkResult<User> = withContext(Dispatchers.IO) {
        val request = RegisterRequest(
            email = email,
            password = password,
            fullName = fullName,
            role = role
        )

        val result = safeApiCall { apiService.register(request) }

        result.onSuccess { response ->
            // Save tokens
            tokenManager.saveTokens(response.accessToken, response.refreshToken)

            // Save user info
            preferencesManager.saveUserInfo(
                userId = response.user.id,
                email = response.user.email,
                role = response.user.role.name.lowercase(),
                // Captured here so a child device can name whose violation it is, offline
                // and without an extra request on the detection path.
                fullName = response.user.fullName
            )

            // Don't log email (PII) - just log success
            Timber.d("User registered successfully")
        }

        result.map { it.user }
    }

    /**
     * Login existing user
     */
    suspend fun login(
        email: String,
        password: String
    ): NetworkResult<User> = withContext(Dispatchers.IO) {
        val request = LoginRequest(email = email, password = password)

        val result = safeApiCall { apiService.login(request) }

        result.onSuccess { response ->
            // Save tokens
            tokenManager.saveTokens(response.accessToken, response.refreshToken)

            // Save user info
            preferencesManager.saveUserInfo(
                userId = response.user.id,
                email = response.user.email,
                role = response.user.role.name.lowercase(),
                // Captured here so a child device can name whose violation it is, offline
                // and without an extra request on the detection path.
                fullName = response.user.fullName
            )

            // Don't log email (PII) - just log success
            Timber.d("User logged in successfully")
        }

        result.map { it.user }
    }

    /**
     * Authenticate or register user via Google Sign-In
     *
     * @param idToken Google ID token from Credential Manager
     * @param role Required for new user registration (null for existing users)
     */
    suspend fun googleSignIn(
        idToken: String,
        role: UserRole? = null
    ): NetworkResult<User> = withContext(Dispatchers.IO) {
        val request = GoogleAuthRequest(
            idToken = idToken,
            role = role
        )

        val result = safeApiCall { apiService.googleAuth(request) }

        result.onSuccess { response ->
            // Save tokens
            tokenManager.saveTokens(response.accessToken, response.refreshToken)

            // Save user info
            preferencesManager.saveUserInfo(
                userId = response.user.id,
                email = response.user.email,
                role = response.user.role.name.lowercase(),
                // Captured here so a child device can name whose violation it is, offline
                // and without an extra request on the detection path.
                fullName = response.user.fullName
            )

            // Don't log email (PII) - just log success
            Timber.d("Google Sign-In successful")
        }

        result.map { it.user }
    }

    /**
     * Refresh access token
     */
    suspend fun refreshToken(): NetworkResult<TokenResponse> = withContext(Dispatchers.IO) {
        val refreshToken = tokenManager.getRefreshToken()
            ?: return@withContext NetworkResult.Error("No refresh token available")

        val request = RefreshTokenRequest(refreshToken = refreshToken)

        val result = safeApiCall { apiService.refreshToken(request) }

        result.onSuccess { response ->
            // Save new tokens
            tokenManager.saveTokens(response.accessToken, response.refreshToken)
            Timber.d("Token refreshed successfully")
        }

        result
    }

    /**
     * Logout user
     */
    suspend fun logout(): NetworkResult<Unit> = withContext(Dispatchers.IO) {
        // Clear the push registration FIRST, while the session is still valid. This is an
        // authenticated call: running it after the tokens are gone would 401 and leave a live
        // token registered against the account on a device nobody is signed in on — meaning
        // family violation alerts would keep arriving there. Best-effort: a failure here must
        // never prevent the user from signing out.
        clearFcmToken()

        val result = safeApiCall { apiService.logout() }

        // Clear local data regardless of API result
        tokenManager.clearTokens()
        preferencesManager.clearAll()

        // Alerts held for later delivery belong to the account that produced them. Drop them
        // so they can never be sent under whoever signs in on this device next.
        alertRepository.clearPendingAlerts()

        Timber.d("User logged out")

        result.map { }
    }

    /**
     * Get current user info
     */
    suspend fun getCurrentUser(): NetworkResult<User> = withContext(Dispatchers.IO) {
        safeApiCall { apiService.getCurrentUser() }
    }

    /**
     * Proves the signed-in parent's identity to the server before a child's account is deleted:
     * `DELETE family/child` takes no credential, so the password is checked through the existing
     * login endpoint. The server must authenticate the SAME user that is signed in here.
     *
     * A missing stored email or a different user returns [DeletionError.CODE_ACCOUNT_MISMATCH],
     * never 401: this endpoint bypasses AuthInterceptor, so no redirect to Login would happen, and
     * 401 already means "wrong password" here. Neither the password nor the email is logged.
     */
    suspend fun reauthenticateWithPassword(password: String): NetworkResult<Unit> = withContext(Dispatchers.IO) {
        val email = preferencesManager.userEmail
            ?: return@withContext NetworkResult.Error("no session", DeletionError.CODE_ACCOUNT_MISMATCH)
        reauthenticate { apiService.login(LoginRequest(email = email, password = password)) }
    }

    /** Same as [reauthenticateWithPassword] for a Google account: a fresh Google ID token. */
    suspend fun reauthenticateWithGoogle(idToken: String): NetworkResult<Unit> = withContext(Dispatchers.IO) {
        reauthenticate { apiService.googleAuth(GoogleAuthRequest(idToken = idToken, role = null)) }
    }

    private suspend fun reauthenticate(call: suspend () -> Response<TokenResponse>): NetworkResult<Unit> {
        val result = safeApiCall(call)
        Timber.tag(DELETION_TAG).i("reauth code=%s", (result as? NetworkResult.Error)?.code ?: "2xx")
        return when (result) {
            is NetworkResult.Success -> {
                if (result.data.user.id == preferencesManager.userId) {
                    // The server rotates the tokens on login, exactly as AuthRepository.login handles it.
                    tokenManager.saveTokens(result.data.accessToken, result.data.refreshToken)
                    NetworkResult.Success(Unit)
                } else {
                    NetworkResult.Error("account mismatch", DeletionError.CODE_ACCOUNT_MISMATCH)
                }
            }
            is NetworkResult.Error -> NetworkResult.Error(result.message, result.code)
            NetworkResult.Loading -> NetworkResult.Error("unexpected state", null)
        }
    }

    /**
     * Permanently delete the signed-in account. Success is any 2xx, whatever the body.
     * Only the status is logged: never the request body, the password, or any token.
     */
    suspend fun deleteAccount(request: DeleteAccountRequest): NetworkResult<Unit> = withContext(Dispatchers.IO) {
        Timber.tag(DELETION_TAG).i("self delete start")
        val raw = safeApiCall { apiService.deleteAccount(request) }
        Timber.tag(DELETION_TAG).i("self delete code=%s", (raw as? NetworkResult.Error)?.code ?: "2xx")
        raw.treatEmpty2xxAsSuccess()
    }

    // ==================== Push token registration ====================

    /**
     * Publish this device's push token so the backend can reach the signed-in user.
     *
     * The token value is never logged and never persisted locally — it is fetched fresh from
     * Firebase each time it is published.
     */
    suspend fun publishFcmToken(token: String): NetworkResult<MessageResponse> = withContext(Dispatchers.IO) {
        val result = safeApiCall { apiService.updateMyFcmToken(FcmTokenUpdateRequest(token)) }

        result.onSuccess { Timber.d("Push token published") }
        result.onError { message, code -> Timber.w("Failed to publish push token (code=$code): $message") }

        result
    }

    /**
     * Clear this device's registered push token. Must run while the session is still valid.
     */
    suspend fun clearFcmToken(): NetworkResult<MessageResponse> = withContext(Dispatchers.IO) {
        val result = safeApiCall { apiService.updateMyFcmToken(FcmTokenUpdateRequest(null)) }

        result.onSuccess { Timber.d("Push token cleared") }
        result.onError { message, code -> Timber.w("Failed to clear push token (code=$code): $message") }

        result
    }

    /**
     * Change user password
     */
    suspend fun changePassword(
        currentPassword: String,
        newPassword: String
    ): NetworkResult<MessageResponse> = withContext(Dispatchers.IO) {
        val request = ChangePasswordRequest(
            currentPassword = currentPassword,
            newPassword = newPassword
        )
        safeApiCall { apiService.changePassword(request) }
    }

    /**
     * Request a password reset code for the given email.
     * Backend returns a neutral message regardless of whether the account exists.
     */
    suspend fun forgotPassword(email: String): NetworkResult<MessageResponse> = withContext(Dispatchers.IO) {
        val request = ForgotPasswordRequest(email = email)
        // Do not log email (PII); do not log the code/response body.
        safeApiCall { apiService.forgotPassword(request) }
    }

    /**
     * Redeem a reset code and set a new password.
     * Neither the code nor the new password is logged.
     */
    suspend fun resetPassword(
        email: String,
        code: String,
        newPassword: String
    ): NetworkResult<MessageResponse> = withContext(Dispatchers.IO) {
        val request = ResetPasswordRequest(
            email = email,
            code = code,
            newPassword = newPassword
        )
        safeApiCall { apiService.resetPassword(request) }
    }

    /**
     * Check if user is logged in
     */
    fun isLoggedIn(): Boolean {
        return tokenManager.isLoggedIn() && preferencesManager.isLoggedIn
    }

    /**
     * Check if token needs refresh
     */
    fun needsTokenRefresh(): Boolean {
        return tokenManager.isAccessTokenExpired() && !tokenManager.isRefreshTokenExpired()
    }

    /**
     * Get user role
     */
    fun getUserRole(): String? {
        return preferencesManager.userRole
    }

    /**
     * Check if user is parent
     */
    fun isParent(): Boolean {
        return preferencesManager.isParent
    }

    /**
     * Check if user is child
     */
    fun isChild(): Boolean {
        return preferencesManager.isChild
    }

    private companion object {
        const val DELETION_TAG = "AccountDeletion"
    }
}
