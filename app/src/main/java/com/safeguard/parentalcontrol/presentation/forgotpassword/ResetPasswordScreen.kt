package com.safeguard.parentalcontrol.presentation.forgotpassword

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.safeguard.parentalcontrol.R
import com.safeguard.parentalcontrol.presentation.designsystem.HarisPrimaryButton
import com.safeguard.parentalcontrol.presentation.designsystem.HarisTextField
import com.safeguard.parentalcontrol.presentation.designsystem.mirrorInRtl
import com.safeguard.parentalcontrol.presentation.forgotpassword.components.OtpCodeInput
import com.safeguard.parentalcontrol.presentation.theme.SafeGuardDimens

/**
 * Step 2 of the recovery flow: enter the 6-digit code and a new password.
 * On success ([ForgotPasswordUiState.resetSuccess]) navigation pops back to Login,
 * where the success snackbar is shown.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResetPasswordScreen(
    viewModel: ForgotPasswordViewModel,
    onBackToEmail: () -> Unit,
    onResetSuccess: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val focusManager = LocalFocusManager.current

    var code by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var newVisible by remember { mutableStateOf(false) }
    var confirmVisible by remember { mutableStateOf(false) }

    // Back returns to the email step (email preserved) rather than leaving the flow.
    val goBack: () -> Unit = {
        viewModel.backToEmailStep()
        onBackToEmail()
    }
    BackHandler(onBack = goBack)

    LaunchedEffect(uiState.resetSuccess) {
        if (uiState.resetSuccess) {
            onResetSuccess()
        }
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let { error ->
            snackbarHostState.showSnackbar(message = error, duration = SnackbarDuration.Short)
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.forgotpw_reset_title)) },
                navigationIcon = {
                    IconButton(onClick = goBack) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                            modifier = Modifier.mirrorInRtl()
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(SafeGuardDimens.stackLg)
        ) {
            Text(
                text = stringResource(R.string.forgotpw_reset_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(SafeGuardDimens.stackLg))

            Text(
                text = stringResource(R.string.forgotpw_code_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(SafeGuardDimens.spacingXs))
            OtpCodeInput(
                value = code,
                onValueChange = {
                    code = it
                    if (uiState.codeFieldError != null) viewModel.clearCodeFieldError()
                },
                isError = uiState.codeFieldError != null,
                modifier = Modifier.fillMaxWidth()
            )
            uiState.codeFieldError?.let {
                Spacer(modifier = Modifier.height(SafeGuardDimens.spacingXs))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(modifier = Modifier.height(SafeGuardDimens.spacingXs))

            // Resend row: disabled with a countdown while the cooldown is active.
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = { viewModel.resendCode() },
                    enabled = uiState.cooldownSeconds == 0 && !uiState.isLoading
                ) {
                    Text(
                        text = if (uiState.cooldownSeconds > 0) {
                            stringResource(R.string.forgotpw_resend_countdown, uiState.cooldownSeconds)
                        } else {
                            stringResource(R.string.forgotpw_resend)
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(SafeGuardDimens.stackMd))

            HarisTextField(
                value = newPassword,
                onValueChange = {
                    newPassword = it
                    if (uiState.passwordFieldError != null) viewModel.clearPasswordFieldError()
                },
                label = stringResource(R.string.forgotpw_new_password_label),
                placeholder = stringResource(R.string.forgotpw_new_password_placeholder),
                leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                trailingIcon = {
                    IconButton(onClick = { newVisible = !newVisible }) {
                        Icon(
                            if (newVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (newVisible) stringResource(R.string.auth_hide_password) else stringResource(R.string.auth_show_password)
                        )
                    }
                },
                visualTransformation = if (newVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(SafeGuardDimens.stackMd))

            HarisTextField(
                value = confirmPassword,
                onValueChange = {
                    confirmPassword = it
                    if (uiState.passwordFieldError != null) viewModel.clearPasswordFieldError()
                },
                label = stringResource(R.string.forgotpw_confirm_password_label),
                placeholder = stringResource(R.string.forgotpw_confirm_password_placeholder),
                errorText = uiState.passwordFieldError,
                leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                trailingIcon = {
                    IconButton(onClick = { confirmVisible = !confirmVisible }) {
                        Icon(
                            if (confirmVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (confirmVisible) stringResource(R.string.auth_hide_password) else stringResource(R.string.auth_show_password)
                        )
                    }
                },
                visualTransformation = if (confirmVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = {
                        focusManager.clearFocus()
                        viewModel.submitReset(code, newPassword, confirmPassword)
                    }
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(SafeGuardDimens.stackLg))

            HarisPrimaryButton(
                text = stringResource(R.string.forgotpw_reset_button),
                onClick = {
                    focusManager.clearFocus()
                    viewModel.submitReset(code, newPassword, confirmPassword)
                },
                enabled = !uiState.isLoading && code.isNotBlank() && newPassword.isNotBlank() && confirmPassword.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = if (uiState.isLoading) {
                    { CircularProgressIndicator(Modifier.size(18.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp) }
                } else null
            )
        }
    }
}
