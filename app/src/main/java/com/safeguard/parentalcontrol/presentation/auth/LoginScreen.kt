package com.safeguard.parentalcontrol.presentation.auth

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.safeguard.parentalcontrol.R
import com.safeguard.parentalcontrol.presentation.auth.components.GoogleSignInButton
import com.safeguard.parentalcontrol.presentation.auth.components.RoleSelectionDialog
import com.safeguard.parentalcontrol.presentation.designsystem.HarisGradientHeader
import com.safeguard.parentalcontrol.presentation.designsystem.HarisLogo
import com.safeguard.parentalcontrol.presentation.designsystem.HarisPrimaryButton
import com.safeguard.parentalcontrol.presentation.designsystem.HarisTextField
import com.safeguard.parentalcontrol.presentation.theme.SafeGuardDimens
import com.safeguard.parentalcontrol.presentation.theme.SafeGuardTheme

@Composable
fun LoginScreen(
    onNavigateToRegister: () -> Unit,
    onLoginSuccess: () -> Unit,
    onNeedDeviceSetup: () -> Unit = {},
    viewModel: AuthViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.isLoggedIn, uiState.isDeviceRegistered) {
        if (uiState.isLoggedIn) {
            if (viewModel.isChild() && !uiState.isDeviceRegistered) {
                onNeedDeviceSetup()
            } else {
                onLoginSuccess()
            }
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(uiState.error) {
        uiState.error?.let { error ->
            snackbarHostState.showSnackbar(message = error, duration = SnackbarDuration.Short)
            viewModel.clearError()
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        LoginContent(
            modifier = Modifier.padding(padding),
            email = email,
            onEmailChange = { email = it },
            password = password,
            onPasswordChange = { password = it },
            passwordVisible = passwordVisible,
            onTogglePassword = { passwordVisible = !passwordVisible },
            isLoading = uiState.isLoading,
            isGoogleLoading = uiState.isGoogleSignInLoading,
            onLogin = { viewModel.login(email, password) },
            onGoogleSignIn = { viewModel.signInWithGoogle(context) },
            onNavigateToRegister = onNavigateToRegister
        )
    }

    if (uiState.needsRoleSelection) {
        RoleSelectionDialog(
            onRoleSelected = { viewModel.completeGoogleRegistration(it) },
            onDismiss = { viewModel.cancelGoogleRoleSelection() }
        )
    }
}

/** Stateless content — renders state, forwards actions. Previewable without Hilt. */
@Composable
private fun LoginContent(
    email: String,
    onEmailChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    passwordVisible: Boolean,
    onTogglePassword: () -> Unit,
    isLoading: Boolean,
    isGoogleLoading: Boolean,
    onLogin: () -> Unit,
    onGoogleSignIn: () -> Unit,
    onNavigateToRegister: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = SafeGuardDimens.screenPadding)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Spacer(modifier = Modifier.height(SafeGuardDimens.stackLg))

        HarisGradientHeader(modifier = Modifier.padding(vertical = SafeGuardDimens.stackLg)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                HarisLogo(size = 72.dp)
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.displaySmall,
                    color = Color.White
                )
                Text(
                    text = stringResource(R.string.auth_tagline),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White
                )
            }
        }

        Spacer(modifier = Modifier.height(SafeGuardDimens.stackLg))

        Text(
            text = stringResource(R.string.login_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = stringResource(R.string.login_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(SafeGuardDimens.stackLg))

        HarisTextField(
            value = email,
            onValueChange = onEmailChange,
            label = stringResource(R.string.email_label),
            placeholder = stringResource(R.string.auth_email_placeholder),
            leadingIcon = { Icon(Icons.Default.Email, contentDescription = null) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(SafeGuardDimens.stackMd))

        HarisTextField(
            value = password,
            onValueChange = onPasswordChange,
            label = stringResource(R.string.password_label),
            placeholder = stringResource(R.string.auth_password_placeholder),
            leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
            trailingIcon = {
                IconButton(onClick = onTogglePassword) {
                    Icon(
                        if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (passwordVisible) {
                            stringResource(R.string.auth_hide_password)
                        } else {
                            stringResource(R.string.auth_show_password)
                        }
                    )
                }
            },
            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = {
                    focusManager.clearFocus()
                    if (email.isNotBlank() && password.isNotBlank()) onLogin()
                }
            ),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(SafeGuardDimens.stackLg))

        HarisPrimaryButton(
            text = stringResource(R.string.sign_in),
            onClick = onLogin,
            enabled = !isLoading && !isGoogleLoading && email.isNotBlank() && password.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
            leadingIcon = if (isLoading) {
                { CircularProgressIndicator(Modifier.size(18.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp) }
            } else null
        )

        Spacer(modifier = Modifier.height(SafeGuardDimens.stackMd))

        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Divider(modifier = Modifier.weight(1f))
            Text(
                text = "  ${stringResource(R.string.auth_or_divider)}  ",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Divider(modifier = Modifier.weight(1f))
        }

        Spacer(modifier = Modifier.height(SafeGuardDimens.stackMd))

        GoogleSignInButton(
            onClick = onGoogleSignIn,
            isLoading = isGoogleLoading,
            enabled = !isLoading && !isGoogleLoading
        )

        Spacer(modifier = Modifier.height(SafeGuardDimens.stackMd))

        Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.dont_have_account),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = onNavigateToRegister) { Text(stringResource(R.string.sign_up)) }
        }

        Spacer(modifier = Modifier.height(SafeGuardDimens.stackLg))
    }
}

@Composable
private fun LoginPreviewContent() {
    LoginContent(
        email = "parent@haris.app",
        onEmailChange = {},
        password = "secret123",
        onPasswordChange = {},
        passwordVisible = false,
        onTogglePassword = {},
        isLoading = false,
        isGoogleLoading = false,
        onLogin = {},
        onGoogleSignIn = {},
        onNavigateToRegister = {}
    )
}

@Preview(name = "Login · Light", showBackground = true)
@Composable
private fun LoginScreenLightPreview() {
    SafeGuardTheme(darkTheme = false) { Surface { LoginPreviewContent() } }
}

@Preview(name = "Login · Dark", showBackground = true)
@Composable
private fun LoginScreenDarkPreview() {
    SafeGuardTheme(darkTheme = true) { Surface { LoginPreviewContent() } }
}
