package com.safeguard.parentalcontrol.presentation.auth

import com.safeguard.parentalcontrol.BuildConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Edit 2 / FR-020: guards that the Google OAuth Web Client ID is a real, configured
 * value — not the placeholder. A wrong/empty client ID silently breaks Google Sign-In.
 */
class GoogleWebClientIdTest {

    private val clientId = BuildConfig.GOOGLE_WEB_CLIENT_ID

    @Test
    fun webClientId_isNotBlank() {
        assertTrue("GOOGLE_WEB_CLIENT_ID must not be blank", clientId.isNotBlank())
    }

    @Test
    fun webClientId_isNotPlaceholder() {
        assertFalse(
            "GOOGLE_WEB_CLIENT_ID must not be the placeholder",
            clientId.contains("YOUR_WEB_CLIENT_ID", ignoreCase = true)
        )
    }

    @Test
    fun webClientId_hasGoogleOAuthFormat() {
        assertTrue(
            "GOOGLE_WEB_CLIENT_ID must end with .apps.googleusercontent.com",
            clientId.endsWith(".apps.googleusercontent.com")
        )
    }
}
