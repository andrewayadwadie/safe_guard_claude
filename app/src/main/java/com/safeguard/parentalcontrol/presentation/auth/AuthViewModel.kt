package com.safeguard.parentalcontrol.presentation.auth

import android.util.Patterns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import com.safeguard.parentalcontrol.data.model.User
import com.safeguard.parentalcontrol.data.model.UserRole
import com.safeguard.parentalcontrol.data.remote.NetworkResult
import com.safeguard.parentalcontrol.data.repository.AuthRepository
import com.safeguard.parentalcontrol.data.repository.DeviceRepository
import com.safeguard.parentalcontrol.util.GoogleSignInManager
import com.safeguard.parentalcontrol.util.GoogleSignInResult
import dagger.hilt.android.lifecycle.HiltViewModel
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
    val isGoogleSignInEnabled: Boolean = false,  // Server-side flag
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
    private val googleSignInManager: GoogleSignInManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

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

        // Check if Google OAuth is enabled on server
        checkGoogleOAuthStatus()
    }

    /**
     * Check if Google OAuth is enabled on the server
     */
    private fun checkGoogleOAuthStatus() {
        viewModelScope.launch {
            val isEnabled = authRepository.isGoogleOAuthEnabled()
            _uiState.update { it.copy(isGoogleSignInEnabled = isEnabled) }
        }
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
            _uiState.update { it.copy(error = "Device name is required") }
            return
        }

        if (deviceName.length < 2) {
            _uiState.update { it.copy(error = "Device name is too short") }
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
            _uiState.update {
                it.copy(isGoogleSignInLoading = true, error = null)
            }

            when (val result = googleSignInManager.signIn(activityContext)) {
                is GoogleSignInResult.Success -> {
                    // Try to authenticate with backend (for existing users)
                    authenticateWithGoogle(result.idToken, null)
                }

                is GoogleSignInResult.Cancelled -> {
                    _uiState.update {
                        it.copy(isGoogleSignInLoading = false)
                    }
                }

                is GoogleSignInResult.NoAccounts -> {
                    _uiState.update {
                        it.copy(
                            isGoogleSignInLoading = false,
                            error = "No Google accounts found on this device"
                        )
                    }
                }

                is GoogleSignInResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isGoogleSignInLoading = false,
                            error = result.message
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
                    // Check if error is due to missing role (new user)
                    if (result.message.contains("Role is required", ignoreCase = true)) {
                        _uiState.update {
                            it.copy(
                                isGoogleSignInLoading = false,
                                needsRoleSelection = true,
                                pendingGoogleIdToken = idToken
                            )
                        }
                    } else {
                        _uiState.update {
                            it.copy(
                                isGoogleSignInLoading = false,
                                error = result.message
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
                it.copy(error = "Google sign-in session expired. Please try again.")
            }
            return
        }

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
                pendingGoogleIdToken = null
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
            emailError = "Email is required"
        } else if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            emailError = "Invalid email format"
        }

        // Password validation
        if (password.isBlank()) {
            passwordError = "Password is required"
        } else if (password.length < MIN_PASSWORD_LENGTH) {
            passwordError = "Password must be at least $MIN_PASSWORD_LENGTH characters"
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
                passwordError = "Password must contain at least one uppercase letter"
            } else if (!password.containsLowerCase()) {
                passwordError = "Password must contain at least one lowercase letter"
            } else if (!password.containsDigit()) {
                passwordError = "Password must contain at least one number"
            }
        }

        // Confirm password validation
        if (confirmPassword.isBlank()) {
            confirmPasswordError = "Please confirm your password"
        } else if (password != confirmPassword) {
            confirmPasswordError = "Passwords do not match"
        }

        // Full name validation
        if (fullName.isBlank()) {
            fullNameError = "Full name is required"
        } else if (fullName.trim().length < MIN_NAME_LENGTH) {
            fullNameError = "Full name must be at least $MIN_NAME_LENGTH characters"
        } else if (fullName.trim().length > MAX_NAME_LENGTH) {
            fullNameError = "Full name is too long (max $MAX_NAME_LENGTH characters)"
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
