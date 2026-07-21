package com.safeguard.parentalcontrol.presentation.forgotpassword

/**
 * Which step of the recovery flow the user is on.
 */
enum class ForgotPasswordStep {
    /** Enter email, request a reset code. */
    EMAIL,

    /** Enter the received code and a new password. */
    RESET
}

/**
 * UI state for the two-step forgot-password flow. A single state object is shared
 * across both steps because the email and the resend cooldown must survive the
 * step transition and back-navigation.
 *
 * The reset code and passwords are held only in the screen's local field state and
 * the outgoing request body — never in this UiState — so they are never logged or
 * retained beyond the request.
 */
data class ForgotPasswordUiState(
    val step: ForgotPasswordStep = ForgotPasswordStep.EMAIL,
    /** Trimmed email submitted in step 1; reused for reset + resend. */
    val email: String = "",
    /** A request is in flight — blocks duplicate submissions. */
    val isLoading: Boolean = false,
    /** Snackbar-level error (network / unexpected server errors). */
    val error: String? = null,
    /** Inline error under the email field. */
    val emailFieldError: String? = null,
    /** Inline error under the code field (incomplete code / server 400). */
    val codeFieldError: String? = null,
    /** Inline error under the password fields (too short / mismatch / server 422). */
    val passwordFieldError: String? = null,
    /** Resend is disabled while this is > 0; label shows the remaining seconds. */
    val cooldownSeconds: Int = 0,
    /** Step-1 success signal — navigate to the reset step. */
    val codeSent: Boolean = false,
    /** Step-2 success signal — pop to Login and show the success snackbar there. */
    val resetSuccess: Boolean = false
)
