package com.safeguard.parentalcontrol.presentation.forgotpassword

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.safeguard.parentalcontrol.R
import com.safeguard.parentalcontrol.data.remote.NetworkResult
import com.safeguard.parentalcontrol.data.repository.AuthRepository
import com.safeguard.parentalcontrol.util.LocaleHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives the two-step forgot-password flow. A single ViewModel instance is scoped to
 * the flow's nested navigation graph so the email and resend cooldown survive the
 * step transition and back-navigation.
 *
 * All validation happens client-side first; only valid input hits the network. The
 * reset code and new password are passed straight to the repository and never stored
 * in UiState or logged.
 */
@HiltViewModel
class ForgotPasswordViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(ForgotPasswordUiState())
    val uiState: StateFlow<ForgotPasswordUiState> = _uiState.asStateFlow()

    private var cooldownJob: Job? = null

    private companion object {
        const val CODE_LENGTH = 6
        const val MIN_PASSWORD_LENGTH = 8
        const val RESEND_COOLDOWN_SECONDS = 60
        const val RATE_LIMIT_CODE = 429
        const val INVALID_CODE_CODE = 400
        const val VALIDATION_ERROR_CODE = 422
        // Deliberately simple, framework-free email check so the ViewModel stays unit-testable
        // without Robolectric. The backend performs authoritative validation.
        val EMAIL_REGEX = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")
    }

    private fun getString(resId: Int, vararg args: Any): String =
        LocaleHelper.localizedContext(context).getString(resId, *args)

    fun onEmailChange(value: String) {
        _uiState.update { it.copy(email = value, emailFieldError = null) }
    }

    /**
     * Step 1: validate email locally, then request a reset code.
     */
    fun submitEmail() {
        val email = _uiState.value.email.trim()
        if (email.isBlank() || !EMAIL_REGEX.matches(email)) {
            _uiState.update { it.copy(emailFieldError = getString(R.string.forgotpw_error_email_invalid)) }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null, emailFieldError = null, email = email) }
            when (val result = authRepository.forgotPassword(email)) {
                is NetworkResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            step = ForgotPasswordStep.RESET,
                            codeSent = true
                        )
                    }
                    startCooldown()
                }
                is NetworkResult.Error -> _uiState.update {
                    it.copy(isLoading = false, error = mapSendError(result))
                }
                else -> {}
            }
        }
    }

    /**
     * Consumed by navigation once it has moved to the reset step.
     */
    fun onCodeSentHandled() {
        _uiState.update { it.copy(codeSent = false) }
    }

    /**
     * Resend the code for the stored email. No-op while the cooldown is active.
     */
    fun resendCode() {
        if (_uiState.value.cooldownSeconds > 0) return
        val email = _uiState.value.email
        if (email.isBlank()) return

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            val result = authRepository.forgotPassword(email)
            _uiState.update {
                if (result is NetworkResult.Error) {
                    it.copy(isLoading = false, error = mapSendError(result))
                } else {
                    it.copy(isLoading = false)
                }
            }
            // Restart the cooldown on every attempt (success or rate-limit).
            startCooldown()
        }
    }

    /**
     * Step 2: validate the code and new password locally, then reset the password.
     */
    fun submitReset(code: String, newPassword: String, confirmPassword: String) {
        if (code.length != CODE_LENGTH || !code.all { it.isDigit() }) {
            _uiState.update { it.copy(codeFieldError = getString(R.string.forgotpw_error_code_incomplete)) }
            return
        }
        if (newPassword.length < MIN_PASSWORD_LENGTH) {
            _uiState.update { it.copy(passwordFieldError = getString(R.string.forgotpw_error_password_too_short)) }
            return
        }
        if (newPassword != confirmPassword) {
            _uiState.update { it.copy(passwordFieldError = getString(R.string.forgotpw_error_password_mismatch)) }
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(isLoading = true, error = null, codeFieldError = null, passwordFieldError = null)
            }
            when (val result = authRepository.resetPassword(_uiState.value.email, code, newPassword)) {
                is NetworkResult.Success -> _uiState.update {
                    it.copy(isLoading = false, resetSuccess = true)
                }
                is NetworkResult.Error -> _uiState.update {
                    when (result.code) {
                        INVALID_CODE_CODE -> it.copy(
                            isLoading = false,
                            codeFieldError = getString(R.string.forgotpw_error_code_invalid)
                        )
                        // 422 (and any other validation failure) surfaces the parsed
                        // server message on the password field.
                        VALIDATION_ERROR_CODE -> it.copy(isLoading = false, passwordFieldError = result.message)
                        else -> it.copy(isLoading = false, error = result.message)
                    }
                }
                else -> {}
            }
        }
    }

    /**
     * Return to the email step, preserving the entered email. The cooldown keeps ticking.
     */
    fun backToEmailStep() {
        _uiState.update {
            it.copy(
                step = ForgotPasswordStep.EMAIL,
                codeFieldError = null,
                passwordFieldError = null,
                error = null
            )
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun clearCodeFieldError() {
        _uiState.update { it.copy(codeFieldError = null) }
    }

    fun clearPasswordFieldError() {
        _uiState.update { it.copy(passwordFieldError = null) }
    }

    private fun mapSendError(result: NetworkResult.Error): String =
        if (result.code == RATE_LIMIT_CODE) {
            getString(R.string.forgotpw_error_rate_limited)
        } else {
            result.message
        }

    private fun startCooldown() {
        cooldownJob?.cancel()
        // Set the initial value synchronously so callers observe the full window immediately.
        _uiState.update { it.copy(cooldownSeconds = RESEND_COOLDOWN_SECONDS) }
        cooldownJob = viewModelScope.launch {
            while (_uiState.value.cooldownSeconds > 0) {
                delay(1_000)
                _uiState.update { it.copy(cooldownSeconds = it.cooldownSeconds - 1) }
            }
        }
    }
}
