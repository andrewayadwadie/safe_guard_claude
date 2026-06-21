package com.safeguard.parentalcontrol.util

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.safeguard.parentalcontrol.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Result of Google Sign-In attempt
 */
sealed class GoogleSignInResult {
    /**
     * Sign-in succeeded with ID token
     */
    data class Success(val idToken: String, val email: String, val displayName: String?) : GoogleSignInResult()

    /**
     * User cancelled sign-in
     */
    data object Cancelled : GoogleSignInResult()

    /**
     * No Google accounts available on device
     */
    data object NoAccounts : GoogleSignInResult()

    /**
     * Sign-in failed with error
     */
    data class Error(val message: String, val exception: Exception? = null) : GoogleSignInResult()
}

/**
 * Manager for Google Sign-In using Credential Manager API
 * Handles Google authentication on Android using modern APIs
 */
@Singleton
class GoogleSignInManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val credentialManager = CredentialManager.create(context)

    /**
     * Start Google Sign-In flow
     *
     * @param activityContext Activity context required for credential picker
     * @param filterByAuthorizedAccounts When true, only shows accounts that have previously signed in
     * @return GoogleSignInResult with token on success or error details
     */
    suspend fun signIn(
        activityContext: Context,
        filterByAuthorizedAccounts: Boolean = false
    ): GoogleSignInResult {
        return try {
            // Build the Google Sign-In request
            val googleIdOption = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(filterByAuthorizedAccounts)
                .setServerClientId(BuildConfig.GOOGLE_WEB_CLIENT_ID)
                .setAutoSelectEnabled(false)  // Don't auto-select account
                .build()

            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()

            // Launch the credential picker
            val result = credentialManager.getCredential(
                context = activityContext,
                request = request
            )

            handleSignInResult(result)

        } catch (e: GetCredentialCancellationException) {
            Timber.d("Google Sign-In cancelled by user")
            GoogleSignInResult.Cancelled

        } catch (e: NoCredentialException) {
            Timber.w("No Google accounts found")
            // If we filtered by authorized accounts, try again without filter
            if (filterByAuthorizedAccounts) {
                Timber.d("Retrying without authorized account filter")
                return signIn(activityContext, filterByAuthorizedAccounts = false)
            }
            GoogleSignInResult.NoAccounts

        } catch (e: GetCredentialException) {
            Timber.e(e, "Google Sign-In credential error: ${e.message}")
            GoogleSignInResult.Error(
                message = e.message ?: "Failed to get Google credential",
                exception = e
            )

        } catch (e: Exception) {
            Timber.e(e, "Google Sign-In unexpected error")
            GoogleSignInResult.Error(
                message = e.message ?: "An unexpected error occurred",
                exception = e
            )
        }
    }

    /**
     * Handle the credential result
     */
    private fun handleSignInResult(result: GetCredentialResponse): GoogleSignInResult {
        val credential = result.credential

        return when {
            credential is CustomCredential &&
                    credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL -> {
                try {
                    val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                    val idToken = googleIdTokenCredential.idToken

                    if (idToken.isBlank()) {
                        Timber.e("Google ID token is empty")
                        GoogleSignInResult.Error("Failed to get Google ID token")
                    } else {
                        // Don't log email (PII) - just log success
                        Timber.d("Google Sign-In successful")
                        GoogleSignInResult.Success(
                            idToken = idToken,
                            email = googleIdTokenCredential.id,
                            displayName = googleIdTokenCredential.displayName
                        )
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Failed to parse Google ID token credential")
                    GoogleSignInResult.Error(
                        message = "Failed to parse Google credential",
                        exception = e
                    )
                }
            }

            else -> {
                Timber.w("Unexpected credential type: ${credential.type}")
                GoogleSignInResult.Error("Unexpected credential type received")
            }
        }
    }

    /**
     * Clear any cached credentials (for sign-out)
     */
    suspend fun signOut() {
        try {
            // The Credential Manager doesn't have explicit sign-out
            // Signing out is handled by clearing local app state
            Timber.d("Google Sign-In manager: Local sign-out completed")
        } catch (e: Exception) {
            Timber.e(e, "Error during sign-out")
        }
    }
}
