package com.safeguard.parentalcontrol.presentation.auth

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.safeguard.parentalcontrol.R
import com.safeguard.parentalcontrol.data.model.UserRole
import com.safeguard.parentalcontrol.presentation.auth.components.GoogleSignInButton
import com.safeguard.parentalcontrol.presentation.auth.components.RoleSelectionDialog
import com.safeguard.parentalcontrol.presentation.designsystem.HarisPrimaryButton
import com.safeguard.parentalcontrol.presentation.designsystem.mirrorInRtl
import com.safeguard.parentalcontrol.presentation.designsystem.HarisTextField
import com.safeguard.parentalcontrol.presentation.theme.SafeGuardDimens
import com.safeguard.parentalcontrol.presentation.theme.SafeGuardTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegisterScreen(
    onNavigateBack: () -> Unit,
    onRegisterSuccess: () -> Unit,
    onNeedDeviceSetup: () -> Unit = {},
    viewModel: AuthViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var fullName by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var selectedRole by remember { mutableStateOf(UserRole.PARENT) }

    LaunchedEffect(uiState.isLoggedIn) {
        if (uiState.isLoggedIn) {
            if (selectedRole == UserRole.CHILD) onNeedDeviceSetup() else onRegisterSuccess()
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(uiState.error) {
        uiState.error?.let { error ->
            snackbarHostState.showSnackbar(message = error, duration = SnackbarDuration.Short)
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.register_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                            modifier = Modifier.mirrorInRtl()
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        RegisterContent(
            modifier = Modifier.padding(padding),
            fullName = fullName, onFullNameChange = { fullName = it },
            email = email, onEmailChange = { email = it },
            password = password, onPasswordChange = { password = it },
            confirmPassword = confirmPassword, onConfirmPasswordChange = { confirmPassword = it },
            passwordVisible = passwordVisible, onTogglePassword = { passwordVisible = !passwordVisible },
            selectedRole = selectedRole, onRoleChange = { selectedRole = it },
            isLoading = uiState.isLoading,
            isGoogleLoading = uiState.isGoogleSignInLoading,
            onRegister = { viewModel.register(email, password, fullName, selectedRole) },
            onGoogleSignIn = { viewModel.signInWithGoogle(context) }
        )
    }

    if (uiState.needsRoleSelection) {
        RoleSelectionDialog(
            onRoleSelected = { viewModel.completeGoogleRegistration(it) },
            onDismiss = { viewModel.cancelGoogleRoleSelection() }
        )
    }
}

@Composable
private fun RegisterContent(
    fullName: String, onFullNameChange: (String) -> Unit,
    email: String, onEmailChange: (String) -> Unit,
    password: String, onPasswordChange: (String) -> Unit,
    confirmPassword: String, onConfirmPasswordChange: (String) -> Unit,
    passwordVisible: Boolean, onTogglePassword: () -> Unit,
    selectedRole: UserRole, onRoleChange: (UserRole) -> Unit,
    isLoading: Boolean,
    isGoogleLoading: Boolean,
    onRegister: () -> Unit,
    onGoogleSignIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = SafeGuardDimens.screenPadding)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(SafeGuardDimens.stackMd))

        Text(text = stringResource(R.string.auth_i_am_a), style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth())

        Spacer(modifier = Modifier.height(SafeGuardDimens.stackSm))

        Row(
            modifier = Modifier.fillMaxWidth().selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(SafeGuardDimens.gutter)
        ) {
            RoleOption(
                title = stringResource(R.string.role_parent),
                description = stringResource(R.string.role_parent_description),
                icon = Icons.Default.SupervisorAccount,
                selected = selectedRole == UserRole.PARENT,
                onClick = { onRoleChange(UserRole.PARENT) },
                modifier = Modifier.weight(1f)
            )
            RoleOption(
                title = stringResource(R.string.role_child),
                description = stringResource(R.string.role_child_description),
                icon = Icons.Default.ChildCare,
                selected = selectedRole == UserRole.CHILD,
                onClick = { onRoleChange(UserRole.CHILD) },
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(SafeGuardDimens.stackLg))

        HarisTextField(
            value = fullName, onValueChange = onFullNameChange, label = stringResource(R.string.full_name_label),
            placeholder = stringResource(R.string.auth_name_placeholder),
            leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(SafeGuardDimens.stackMd))

        HarisTextField(
            value = email, onValueChange = onEmailChange, label = stringResource(R.string.email_label),
            placeholder = stringResource(R.string.auth_email_placeholder),
            leadingIcon = { Icon(Icons.Default.Email, contentDescription = null) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(SafeGuardDimens.stackMd))

        HarisTextField(
            value = password, onValueChange = onPasswordChange, label = stringResource(R.string.password_label),
            placeholder = stringResource(R.string.auth_password_min_placeholder),
            leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
            errorText = if (password.isNotEmpty() && password.length < 8) stringResource(R.string.auth_password_too_short) else null,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(SafeGuardDimens.stackMd))

        HarisTextField(
            value = confirmPassword, onValueChange = onConfirmPasswordChange, label = stringResource(R.string.confirm_password_label),
            placeholder = stringResource(R.string.auth_confirm_password_placeholder),
            leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
            errorText = if (confirmPassword.isNotEmpty() && password != confirmPassword) stringResource(R.string.auth_passwords_mismatch) else null,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(SafeGuardDimens.stackLg))

        val isValid = fullName.isNotBlank() && email.isNotBlank() &&
                password.length >= 8 && password == confirmPassword

        HarisPrimaryButton(
            text = stringResource(R.string.register_title),
            onClick = onRegister,
            enabled = !isLoading && isValid,
            modifier = Modifier.fillMaxWidth(),
            leadingIcon = if (isLoading) {
                { CircularProgressIndicator(Modifier.size(18.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp) }
            } else null
        )

        Spacer(modifier = Modifier.height(SafeGuardDimens.stackMd))

        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Divider(modifier = Modifier.weight(1f))
            Text("  ${stringResource(R.string.auth_or_divider)}  ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Divider(modifier = Modifier.weight(1f))
        }

        Spacer(modifier = Modifier.height(SafeGuardDimens.stackMd))

        GoogleSignInButton(
            onClick = onGoogleSignIn,
            isLoading = isGoogleLoading,
            enabled = !isLoading && !isGoogleLoading
        )

        Spacer(modifier = Modifier.height(SafeGuardDimens.stackMd))

        Text(
            text = stringResource(R.string.auth_terms_privacy_notice),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = SafeGuardDimens.gutter)
        )

        Spacer(modifier = Modifier.height(SafeGuardDimens.stackLg))
    }
}

@Composable
private fun RoleOption(
    title: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surface
        ),
        border = if (selected) CardDefaults.outlinedCardBorder() else null
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(SafeGuardDimens.gutter),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(SafeGuardDimens.iconSizeLg),
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(SafeGuardDimens.stackSm))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun RegisterPreviewContent() {
    RegisterContent(
        fullName = "Sara Ali", onFullNameChange = {},
        email = "sara@haris.app", onEmailChange = {},
        password = "secret12", onPasswordChange = {},
        confirmPassword = "secret12", onConfirmPasswordChange = {},
        passwordVisible = false, onTogglePassword = {},
        selectedRole = UserRole.PARENT, onRoleChange = {},
        isLoading = false, isGoogleLoading = false,
        onRegister = {}, onGoogleSignIn = {}
    )
}

@Preview(name = "Register · Light", showBackground = true)
@Composable
private fun RegisterScreenLightPreview() {
    SafeGuardTheme(darkTheme = false) { Surface { RegisterPreviewContent() } }
}

@Preview(name = "Register · Dark", showBackground = true)
@Composable
private fun RegisterScreenDarkPreview() {
    SafeGuardTheme(darkTheme = true) { Surface { RegisterPreviewContent() } }
}
