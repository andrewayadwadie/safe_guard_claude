package com.safeguard.parentalcontrol.util

import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics
import com.safeguard.parentalcontrol.data.model.UserRole
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AnalyticsHelper @Inject constructor(
    private val firebaseAnalytics: FirebaseAnalytics
) {
    fun logGoogleSignInTapped() {
        firebaseAnalytics.logEvent("google_signin_tapped", null)
    }

    fun logGoogleSignInSuccess() {
        firebaseAnalytics.logEvent("google_signin_success", null)
    }

    fun logGoogleSignInFailed(reason: String) {
        val bundle = Bundle().apply { putString("reason", reason) }
        firebaseAnalytics.logEvent("google_signin_failed", bundle)
    }

    fun logGoogleRoleSelected(role: UserRole) {
        val bundle = Bundle().apply { putString("role", role.name.lowercase()) }
        firebaseAnalytics.logEvent("google_signin_role_selected", bundle)
    }
}
