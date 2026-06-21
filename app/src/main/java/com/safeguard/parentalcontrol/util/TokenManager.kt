package com.safeguard.parentalcontrol.util

import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.json.JSONObject
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Secure token manager using EncryptedSharedPreferences
 * Handles JWT access and refresh tokens
 *
 * Performance optimization:
 * - Uses lazy initialization to move EncryptedSharedPreferences creation off main thread
 * - First access may be slow, but won't block app startup
 */
/**
 * Authentication failure event - emitted when tokens are invalidated
 */
sealed class AuthEvent {
    object SessionExpired : AuthEvent()
    object TokenRevoked : AuthEvent()
    object ManualLogout : AuthEvent()
}

@Singleton
class TokenManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /**
     * SharedFlow for authentication events
     * UI should observe this to handle session expiry and redirect to login
     */
    private val _authEvents = MutableSharedFlow<AuthEvent>(extraBufferCapacity = 1)
    val authEvents: SharedFlow<AuthEvent> = _authEvents.asSharedFlow()

    /**
     * Lazy-initialized MasterKey to avoid blocking main thread on app startup
     * EncryptedSharedPreferences initialization can be slow (50-200ms)
     */
    private val masterKey by lazy {
        try {
            MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
        } catch (e: Exception) {
            Timber.e(e, "Failed to create MasterKey, clearing corrupted keystore")
            // Clear potentially corrupted prefs and retry
            context.getSharedPreferences("secure_token_prefs", Context.MODE_PRIVATE)
                .edit().clear().apply()
            MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
        }
    }

    /**
     * Lazy-initialized EncryptedSharedPreferences
     * Will be initialized on first token access, typically after splash screen
     *
     * Falls back to regular SharedPreferences if encryption fails (keystore issues)
     */
    private val encryptedPrefs by lazy {
        try {
            EncryptedSharedPreferences.create(
                context,
                "secure_token_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Timber.e(e, "EncryptedSharedPreferences failed, clearing and retrying")
            // Clear corrupted prefs file and retry
            try {
                context.deleteSharedPreferences("secure_token_prefs")
            } catch (deleteError: Exception) {
                Timber.w(deleteError, "Could not delete corrupted prefs")
            }
            // Retry creation
            EncryptedSharedPreferences.create(
                context,
                "secure_token_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }
    }

    /**
     * Store tokens after login/registration
     */
    fun saveTokens(accessToken: String, refreshToken: String) {
        encryptedPrefs.edit().apply {
            putString(KEY_ACCESS_TOKEN, accessToken)
            putString(KEY_REFRESH_TOKEN, refreshToken)
            apply()
        }
        Timber.d("Tokens saved securely")
    }

    /**
     * Get current access token
     */
    fun getAccessToken(): String? {
        return encryptedPrefs.getString(KEY_ACCESS_TOKEN, null)
    }

    /**
     * Get current refresh token
     */
    fun getRefreshToken(): String? {
        return encryptedPrefs.getString(KEY_REFRESH_TOKEN, null)
    }

    /**
     * Check if access token is expired or about to expire
     */
    fun isAccessTokenExpired(): Boolean {
        val token = getAccessToken() ?: return true
        return try {
            val payload = decodeJwtPayload(token)
            val exp = payload.getLong("exp") * 1000 // Convert to milliseconds
            val now = System.currentTimeMillis()

            // Consider expired if within buffer time
            now >= (exp - Constants.ACCESS_TOKEN_EXPIRY_BUFFER_MS)
        } catch (e: Exception) {
            Timber.e(e, "Error checking token expiry")
            true
        }
    }

    /**
     * Check if refresh token is expired
     */
    fun isRefreshTokenExpired(): Boolean {
        val token = getRefreshToken() ?: return true
        return try {
            val payload = decodeJwtPayload(token)
            val exp = payload.getLong("exp") * 1000
            System.currentTimeMillis() >= exp
        } catch (e: Exception) {
            Timber.e(e, "Error checking refresh token expiry")
            true
        }
    }

    /**
     * Get user ID from access token
     * Note: sub claim is a string per JWT spec, converted to Int
     */
    fun getUserIdFromToken(): Int? {
        val token = getAccessToken() ?: return null
        return try {
            val payload = decodeJwtPayload(token)
            // sub claim is a string per JWT spec
            payload.getString("sub").toIntOrNull()
        } catch (e: Exception) {
            Timber.e(e, "Error extracting user ID from token")
            null
        }
    }

    /**
     * Check if user is logged in (has valid tokens)
     */
    fun isLoggedIn(): Boolean {
        return getAccessToken() != null && !isRefreshTokenExpired()
    }

    /**
     * Clear all tokens on logout (manual logout by user)
     */
    fun clearTokens() {
        encryptedPrefs.edit().clear().apply()
        Timber.d("Tokens cleared (manual logout)")
        _authEvents.tryEmit(AuthEvent.ManualLogout)
    }

    /**
     * Clear tokens due to session expiry (refresh token expired)
     * Emits SessionExpired event to trigger UI redirect to login
     */
    fun clearTokensDueToExpiry() {
        encryptedPrefs.edit().clear().apply()
        Timber.d("Tokens cleared (session expired)")
        _authEvents.tryEmit(AuthEvent.SessionExpired)
    }

    /**
     * Clear tokens because they were revoked by the server
     * Emits TokenRevoked event to trigger UI redirect to login
     */
    fun clearTokensDueToRevocation() {
        encryptedPrefs.edit().clear().apply()
        Timber.d("Tokens cleared (token revoked)")
        _authEvents.tryEmit(AuthEvent.TokenRevoked)
    }

    /**
     * Decode JWT payload (middle part)
     */
    private fun decodeJwtPayload(token: String): JSONObject {
        val parts = token.split(".")
        if (parts.size != 3) {
            throw IllegalArgumentException("Invalid JWT token format")
        }

        val payload = parts[1]
        val decodedBytes = Base64.decode(payload, Base64.URL_SAFE or Base64.NO_WRAP)
        val decodedString = String(decodedBytes, Charsets.UTF_8)

        return JSONObject(decodedString)
    }

    companion object {
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
    }
}
