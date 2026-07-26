package com.safeguard.parentalcontrol.presentation.auth

import android.util.Patterns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import com.safeguard.parentalcontrol.R
import com.safeguard.parentalcontrol.data.model.User
import com.safeguard.parentalcontrol.data.model.UserRole
import com.safeguard.parentalcontrol.data.remote.NetworkResult
import com.safeguard.parentalcontrol.data.repository.AuthRepository
import com.safeguard.parentalcontrol.data.repository.DeviceRepository
import com.safeguard.parentalcontrol.util.AnalyticsHelper
import com.safeguard.parentalcontrol.util.GoogleSignInManager
import com.safeguard.parentalcontrol.util.GoogleSignInResult
import com.safeguard.parentalcontrol.util.LocaleHelper
import com.safeguard.parentalcontrol.worker.PushTokenSyncScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI State for authentication screens
 */
data class AuthUiState(
    val isLoading: Boolean = false,
    val isLoggedIn: Boolean = false,
    val user: User? = null,
    val error: String? = null,
    val isDeviceRegistered: Boolean = false,
    val validationErrors: ValidationErrors = ValidationErrors(),
    // Google Sign-In state
    val isGoogleSignInLoading: Boolean = false,
    val needsRoleSelection: Boolean = false,
    val pendingGoogleIdToken: String? = null
)

/**
 * Validation errors for form fields
 */
data class ValidationErrors(
    val emailError: String? = null,
    val passwordError: String? = null,
    val confirmPasswordError: String? = null,
    val fullNameError: String? = null
) {
    fun hasErrors(): Boolean =
        emailError != null || passwordError != null ||
        confirmPasswordError != null || fullNameError != null
}

/**
 * ViewModel for authentication (login/register) screens
 * Includes client-side input validation for better UX
 */
@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val deviceRepository: DeviceRepository,
    private val googleSignInManager: GoogleSignInManager,
    private val analyticsHelper: AnalyticsHelper,
    private val pushTokenSyncScheduler: PushTokenSyncScheduler,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    /** Resolves a string in the user's chosen app language, independent of the device system locale. */
    private fun getString(resId: Int, vararg args: Any): String =
        LocaleHelper.localizedContext(context).getString(resId, *args)

    /**
     * Make this device reachable for push as soon as a session exists. A parent that never
     * publishes a token simply never receives a violation notification.
     */
    private fun publishPushToken() {
        pushTokenSyncScheduler.schedule()
    }

    init {
        // Check if already logged in
        if (authRepository.isLoggedIn()) {
            _uiState.update {
                it.copy(
                    isLoggedIn = true,
                    isDeviceRegistered = deviceRepository.isDeviceRegistered()
                )
            }
        }

        // Google Sign-In button is always shown (FR-017). No server status gating —
        // the previous checkGoogleOAuthStatus() call was removed because a false/unreachable
        // status hid the button ~3s after render (FR-018, Edit 2).
    }

    /**
     * Login with email and password
     * Validates input before making network request
     */
    fun login(email: String, password: String) {
        // Validate input first
        val validationErrors = validateLoginInput(email, password)
        if (validationErrors.hasErrors()) {
            _uiState.update { it.copy(validationErrors = validationErrors) }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null, validationErrors = ValidationErrors()) }

            // Normalize email
            val normalizedEmail = email.trim().lowercase()

            when (val result = authRepository.login(normalizedEmail, password)) {
                is NetworkResult.Success -> {
                    publishPushToken()
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isLoggedIn = true,
                            user = result.data,
                            isDeviceRegistered = deviceRepository.isDeviceRegistered()
                        )
                    }
                }
                is NetworkResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = result.message
                        )
                    }
                }
                is NetworkResult.Loading -> {
                    // Already handled
                }
            }
        }
    }

    /**
     * Register new user
     * Validates all input before making network request
     */
    fun register(
        email: String,
        password: String,
        confirmPassword: String,
        fullName: String,
        role: UserRole
    ) {
        // Validate input first
        val validationErrors = validateRegisterInput(email, password, confirmPassword, fullName)
        if (validationErrors.hasErrors()) {
            _uiState.update { it.copy(validationErrors = validationErrors) }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null, validationErrors = ValidationErrors()) }

            // Normalize inputs
            val normalizedEmail = email.trim().lowercase()
            val normalizedFullName = fullName.trim()

            when (val result = authRepository.register(normalizedEmail, password, normalizedFullName, role)) {
                is NetworkResult.Success -> {
                    publishPushToken()
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isLoggedIn = true,
                            user = result.data,
                            isDeviceRegistered = false
                        )
                    }
                }
                is NetworkResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = result.message
                        )
                    }
                }
                is NetworkResult.Loading -> {
                    // Already handled
                }
            }
        }
    }

    /**
     * Legacy register method for backward compatibility
     */
    fun register(
        email: String,
        password: String,
        fullName: String,
        role: UserRole
    ) {
        register(email, password, password, fullName, role)
    }

    /**
     * Register device (for child accounts)
     */
    fun registerDevice(deviceName: String, fcmToken: String? = null) {
        // Validate device name
        if (deviceName.isBlank()) {
            _uiState.update { it.copy(error = getString(R.string.auth_error_device_name_required)) }
            return
        }

        if (deviceName.length < 2) {
            _uiState.update { it.copy(error = getString(R.string.auth_error_device_name_too_short)) }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            when (val result = deviceRepository.registerDevice(deviceName.trim(), fcmToken)) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isDeviceRegistered = true
                        )
                    }
                }
                is NetworkResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = result.message
                        )
                    }
                }
                is NetworkResult.Loading -> {
                    // Already handled
                }
            }
        }
    }

    /**
     * Logout current user
     */
    fun logout() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            authRepository.logout()

            _uiState.update {
                AuthUiState(isLoggedIn = false, isLoading = false)
            }
        }
    }

    // ==================== Google Sign-In Methods ====================

    /**
     * Initiate Google Sign-In flow
     *
     * @param activityContext Activity context required for credential picker
     */
    fun signInWithGoogle(activityContext: Context) {
        viewModelScope.launch {
            _uiState.update { it.copy(isGoogleSignInLoading = true, error = null) }
            analyticsHelper.logGoogleSignInTapped()

            when (val result = googleSignInManager.signIn(activityContext)) {
                is GoogleSignInResult.Success -> {
                    authenticateWithGoogle(result.idToken, null)
                }

                is GoogleSignInResult.Cancelled -> {
                    analyticsHelper.logGoogleSignInFailed("cancelled")
                    _uiState.update { it.copy(isGoogleSignInLoading = false) }
                }

                is GoogleSignInResult.NoAccounts -> {
                    analyticsHelper.logGoogleSignInFailed("cancelled")
                    _uiState.update {
                        it.copy(
                            isGoogleSignInLoading = false,
                            error = getString(R.string.auth_error_google_no_accounts)
                        )
                    }
                }

                is GoogleSignInResult.Error -> {
                    analyticsHelper.logGoogleSignInFailed("error")
                    _uiState.update {
                        it.copy(
                            isGoogleSignInLoading = false,
                            error = getString(R.string.auth_error_google_signin_failed)
                        )
                    }
                }
            }
        }
    }

    /**
     * Authenticate with backend using Google ID token
     *
     * @param idToken Google ID token
     * @param role Required for new user registration
     */
    private fun authenticateWithGoogle(idToken: String, role: UserRole?) {
        viewModelScope.launch {
            when (val result = authRepository.googleSignIn(idToken, role)) {
                is NetworkResult.Success -> {
                    analyticsHelper.logGoogleSignInSuccess()
                    publishPushToken()
                    _uiState.update {
                        it.copy(
                            isGoogleSignInLoading = false,
                            isLoggedIn = true,
                            user = result.data,
                            isDeviceRegistered = deviceRepository.isDeviceRegistered(),
                            needsRoleSelection = false,
                            pendingGoogleIdToken = null
                        )
                    }
                }

                is NetworkResult.Error -> {
                    // Normalize to tolerate both machine code ("role_required") and
                    // human phrase ("Role is required"); match both key tokens.
                    val normalized = result.message.lowercase().replace(Regex("[^a-z]"), "")
                    if (normalized.contains("rolerequired") ||
                        (normalized.contains("role") && normalized.contains("required"))
                    ) {
                        _uiState.update {
                            it.copy(
                                isGoogleSignInLoading = false,
                                needsRoleSelection = true,
                                pendingGoogleIdToken = idToken
                            )
                        }
                    } else {
                        analyticsHelper.logGoogleSignInFailed("error")
                        _uiState.update {
                            it.copy(
                                isGoogleSignInLoading = false,
                                error = getString(R.string.auth_error_google_signin_failed)
                            )
                        }
                    }
                }

                is NetworkResult.Loading -> {
                    // Already handled
                }
            }
        }
    }

    /**
     * Complete Google registration with selected role
     * Called after user selects role for new account
     *
     * @param role Selected role (parent or child)
     */
    fun completeGoogleRegistration(role: UserRole) {
        val idToken = _uiState.value.pendingGoogleIdToken
        if (idToken == null) {
            _uiState.update {
                it.copy(error = getString(R.string.auth_error_google_session_expired))
            }
            return
        }

        analyticsHelper.logGoogleRoleSelected(role)
        _uiState.update { it.copy(isGoogleSignInLoading = true) }
        authenticateWithGoogle(idToken, role)
    }

    /**
     * Cancel role selection for Google Sign-In
     */
    fun cancelGoogleRoleSelection() {
        _uiState.update {
            it.copy(
                needsRoleSelection = false,
                pendingGoogleIdToken = null,
                isGoogleSignInLoading = false
            )
        }
    }

    /**
     * Clear error message
     */
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    /**
     * Clear validation errors
     */
    fun clearValidationErrors() {
        _uiState.update { it.copy(validationErrors = ValidationErrors()) }
    }

    /**
     * Check if user is parent
     */
    fun isParent(): Boolean = authRepository.isParent()

    /**
     * Check if user is child
     */
    fun isChild(): Boolean = authRepository.isChild()

    // ==================== Validation Methods ====================

    /**
     * Validate login input
     */
    private fun validateLoginInput(email: String, password: String): ValidationErrors {
        var emailError: String? = null
        var passwordError: String? = null

        // Email validation
        if (email.isBlank()) {
            emailError = getString(R.string.auth_error_email_required)
        } else if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            emailError = getString(R.string.auth_error_email_invalid)
        }

        // Password validation
        if (password.isBlank()) {
            passwordError = getString(R.string.auth_error_password_required)
        } else if (password.length < MIN_PASSWORD_LENGTH) {
            passwordError = getString(R.string.auth_error_password_min_length, MIN_PASSWORD_LENGTH)
        }

        return ValidationErrors(emailError = emailError, passwordError = passwordError)
    }

    /**
     * Validate registration input
     */
    private fun validateRegisterInput(
        email: String,
        password: String,
        confirmPassword: String,
        fullName: String
    ): ValidationErrors {
        // Start with login validation
        val loginErrors = validateLoginInput(email, password)
        var passwordError = loginErrors.passwordError
        var confirmPasswordError: String? = null
        var fullNameError: String? = null

        // Additional password validation for registration
        if (passwordError == null) {
            if (!password.containsUpperCase()) {
                passwordError = getString(R.string.auth_error_password_uppercase)
            } else if (!password.containsLowerCase()) {
                passwordError = getString(R.string.auth_error_password_lowercase)
            } else if (!password.containsDigit()) {
                passwordError = getString(R.string.auth_error_password_digit)
            }
        }

        // Confirm password validation
        if (confirmPassword.isBlank()) {
            confirmPasswordError = getString(R.string.auth_error_confirm_password_required)
        } else if (password != confirmPassword) {
            confirmPasswordError = getString(R.string.auth_passwords_mismatch)
        }

        // Full name validation
        if (fullName.isBlank()) {
            fullNameError = getString(R.string.auth_error_fullname_required)
        } else if (fullName.trim().length < MIN_NAME_LENGTH) {
            fullNameError = getString(R.string.auth_error_fullname_min_length, MIN_NAME_LENGTH)
        } else if (fullName.trim().length > MAX_NAME_LENGTH) {
            fullNameError = getString(R.string.auth_error_fullname_max_length, MAX_NAME_LENGTH)
        }

        return ValidationErrors(
            emailError = loginErrors.emailError,
            passwordError = passwordError,
            confirmPasswordError = confirmPasswordError,
            fullNameError = fullNameError
        )
    }

    // String extension functions for password validation
    private fun String.containsUpperCase(): Boolean = any { it.isUpperCase() }
    private fun String.containsLowerCase(): Boolean = any { it.isLowerCase() }
    private fun String.containsDigit(): Boolean = any { it.isDigit() }

    companion object {
        const val MIN_PASSWORD_LENGTH = 8
        const val MIN_NAME_LENGTH = 2
        const val MAX_NAME_LENGTH = 100
    }
}
