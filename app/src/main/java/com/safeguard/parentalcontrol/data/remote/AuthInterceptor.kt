package com.safeguard.parentalcontrol.data.remote

import com.safeguard.parentalcontrol.data.model.RefreshTokenRequest
import com.safeguard.parentalcontrol.util.TokenManager
import okhttp3.Interceptor
import okhttp3.Response
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * OkHttp interceptor that adds JWT access token to requests
 * and handles automatic token refresh on 401 responses
 *
 * Uses Provider<ApiService> to avoid circular dependency with NetworkModule
 *
 * Thread-safety:
 * - Uses AtomicBoolean for thread-safe refresh state management
 * - Uses synchronized blocks for token refresh coordination
 */
@Singleton
class AuthInterceptor @Inject constructor(
    private val tokenManager: TokenManager,
    private val apiServiceProvider: Provider<ApiService>
) : Interceptor {

    // AtomicBoolean for thread-safe check-and-set operations
    private val isRefreshing = AtomicBoolean(false)

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        // Skip auth for login/register/refresh endpoints
        val path = originalRequest.url.encodedPath
        if (isAuthEndpoint(path)) {
            return chain.proceed(originalRequest)
        }

        // Get current access token
        var accessToken = tokenManager.getAccessToken()

        // Proactively refresh if token is expired (before making the request)
        if (accessToken != null && tokenManager.isAccessTokenExpired() && !tokenManager.isRefreshTokenExpired()) {
            synchronized(this) {
                // Double-check after acquiring lock (another thread may have refreshed)
                if (tokenManager.isAccessTokenExpired()) {
                    Timber.d("Access token expired, proactively refreshing...")
                    if (refreshTokenSync()) {
                        accessToken = tokenManager.getAccessToken()
                        Timber.d("Token refreshed proactively")
                    }
                }
            }
        }

        // Add access token to request
        val authenticatedRequest = if (accessToken != null) {
            originalRequest.newBuilder()
                .header("Authorization", "Bearer $accessToken")
                .build()
        } else {
            originalRequest
        }

        val response = chain.proceed(authenticatedRequest)

        // Handle 401 Unauthorized - try to refresh token
        if (response.code == 401 && !isRefreshEndpoint(path)) {
            Timber.d("Received 401, attempting token refresh")

            synchronized(this) {
                // Check if token was already refreshed by another thread
                val currentToken = tokenManager.getAccessToken()
                if (currentToken != null && currentToken != accessToken) {
                    // Token was refreshed by another thread, retry with new token
                    Timber.d("Token was refreshed by another thread, retrying request")
                    response.close()
                    return chain.proceed(
                        originalRequest.newBuilder()
                            .header("Authorization", "Bearer $currentToken")
                            .build()
                    )
                }

                // Attempt token refresh
                if (!tokenManager.isRefreshTokenExpired()) {
                    if (refreshTokenSync()) {
                        val newToken = tokenManager.getAccessToken()
                        if (newToken != null) {
                            Timber.d("Token refreshed successfully, retrying request")
                            response.close()
                            return chain.proceed(
                                originalRequest.newBuilder()
                                    .header("Authorization", "Bearer $newToken")
                                    .build()
                            )
                        }
                    } else {
                        Timber.w("Token refresh failed (likely revoked), user will need to re-login")
                        // Clear tokens with revocation event - triggers UI redirect to login
                        tokenManager.clearTokensDueToRevocation()
                    }
                } else {
                    Timber.w("Refresh token expired, user needs to re-login")
                    // Clear tokens with expiry event - triggers UI redirect to login
                    tokenManager.clearTokensDueToExpiry()
                }
            }
        }

        return response
    }

    /**
     * Synchronously refresh the access token using the refresh token
     * Returns true if refresh was successful, false otherwise
     *
     * Thread-safe: Uses compareAndSet for atomic check-and-set to prevent
     * multiple concurrent refresh attempts
     */
    private fun refreshTokenSync(): Boolean {
        // Atomic check-and-set: only proceeds if isRefreshing was false and is now true
        if (!isRefreshing.compareAndSet(false, true)) {
            Timber.d("Token refresh already in progress")
            return false
        }

        val refreshToken = tokenManager.getRefreshToken()
        if (refreshToken == null) {
            Timber.w("No refresh token available")
            isRefreshing.set(false)
            return false
        }

        return try {
            Timber.d("Starting token refresh...")

            val response = apiServiceProvider.get()
                .refreshTokenSync(RefreshTokenRequest(refreshToken))
                .execute()

            if (response.isSuccessful && response.body() != null) {
                val tokenResponse = response.body()!!
                tokenManager.saveTokens(tokenResponse.accessToken, tokenResponse.refreshToken)
                Timber.d("Token refresh successful")
                true
            } else {
                Timber.e("Token refresh failed with code: ${response.code()}")
                false
            }
        } catch (e: Exception) {
            Timber.e(e, "Token refresh failed with exception")
            false
        } finally {
            isRefreshing.set(false)
        }
    }

    private fun isAuthEndpoint(path: String): Boolean {
        return path.contains("/auth/login") ||
                path.contains("/auth/register") ||
                path.contains("/auth/refresh") ||
                path.contains("/oauth/google/status") ||
                path.contains("/oauth/google") ||
                path.contains("/health")
    }

    private fun isRefreshEndpoint(path: String): Boolean {
        return path.contains("/auth/refresh")
    }
}
