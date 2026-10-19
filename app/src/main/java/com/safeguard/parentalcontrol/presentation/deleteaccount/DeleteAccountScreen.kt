package com.safeguard.parentalcontrol.presentation.deleteaccount

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.safeguard.parentalcontrol.R
import com.safeguard.parentalcontrol.presentation.designsystem.mirrorInRtl
import com.safeguard.parentalcontrol.presentation.theme.rememberScreenWidth
import com.safeguard.parentalcontrol.presentation.theme.responsiveContentWidth

/**
 * Permanent account deletion for the signed-in user. Consequences are listed before the credential
 * step, the destructive action uses the error colour and is never a filled primary button, and a
 * second confirmation is required before anything is sent.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeleteAccountScreen(
    onNavigateBack: () -> Unit,
    onDeleted: () -> Unit,
    viewModel: DeleteAccountViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val retryLabel = stringResource(R.string.delete_account_retry)

    // Input and back navigation are blocked while the request runs (single flight).
    BackHandler(enabled = uiState.isLoading) { }

    LaunchedEffect(uiState.deleted) {
        if (uiState.deleted) onDeleted()
    }

    LaunchedEffect(uiState.error) {
        val message = uiState.error ?: return@LaunchedEffect
        val retryable = uiState.errorRetryable
        val result = snackbarHostState.showSnackbar(
            message = message,
            actionLabel = if (retryable) retryLabel else null,
            duration = if (retryable) SnackbarDuration.Indefinite else SnackbarDuration.Long
        )
        if (result == SnackbarResult.ActionPerformed) {
            viewModel.retry()
        }
        viewModel.clearError()
    }

    if (uiState.showFinalConfirm) {
        AlertDialog(
            onDismissRequest = viewModel::dismissFinalConfirm,
            icon = {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text(stringResource(R.string.delete_account_final_title)) },
            text = { Text(stringResource(R.string.delete_account_final_message)) },
            confirmButton = {
                TextButton(onClick = viewModel::onFinalConfirm) {
                    Text(
                        text = stringResource(R.string.delete_account_final_confirm),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissFinalConfirm) {
                    Text(stringResource(R.string.delete_account_cancel))
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.delete_account_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack, enabled = !uiState.isLoading) {
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
        val screenWidth = rememberScreenWidth()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .responsiveContentWidth(screenWidth)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (uiState.isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            Text(
                text = stringResource(R.string.delete_account_intro),
                style = MaterialTheme.typography.bodyLarge
            )

            ConsequencesCard(uiState)

            CredentialSection(
                uiState = uiState,
                onCredentialChange = viewModel::onCredentialChange,
                onToggleVisibility = viewModel::toggleVisibility,
                onSwitchMode = viewModel::switchMode
            )

            DeleteButton(
                enabled = uiState.canSubmit,
                isLoading = uiState.isLoading,
                onClick = viewModel::onDeleteClicked
            )

            uiState.blockingNotice?.let { notice ->
                Text(
                    text = notice,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun ConsequencesCard(uiState: DeleteAccountUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.delete_account_consequences_heading),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )

            ConsequenceRow(stringResource(R.string.delete_account_consequence_account))

            when (uiState.role) {
                DeleteRole.PARENT -> {
                    val count = uiState.linkedChildCount
                    if (count == null) {
                        ConsequenceRow(stringResource(R.string.delete_account_consequence_children_unknown))
                    } else if (count > 0) {
                        ConsequenceRow(
                            pluralStringResource(R.plurals.delete_account_consequence_children_n, count, count)
                        )
                    }
                    ConsequenceRow(stringResource(R.string.delete_account_consequence_data))
                }
                DeleteRole.CHILD -> {
                    ConsequenceRow(stringResource(R.string.delete_account_consequence_device_data))
                    if (uiState.hasReviewOriginals) {
                        ConsequenceRow(stringResource(R.string.delete_account_consequence_blurred_photos))
                    }
                }
            }
        }
    }
}

@Composable
private fun ConsequenceRow(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            imageVector = Icons.Default.Close,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer
        )
    }
}

@Composable
private fun CredentialSection(
    uiState: DeleteAccountUiState,
    onCredentialChange: (String) -> Unit,
    onToggleVisibility: () -> Unit,
    onSwitchMode: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (uiState.mode) {
            ConfirmMode.PASSWORD -> OutlinedTextField(
                value = uiState.credential,
                onValueChange = onCredentialChange,
                modifier = Modifier.fillMaxWidth(),
                enabled = !uiState.isLoading,
                singleLine = true,
                label = { Text(stringResource(R.string.delete_account_password_label)) },
                visualTransformation = if (uiState.passwordVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                trailingIcon = {
                    IconButton(onClick = onToggleVisibility) {
                        Icon(
                            imageVector = if (uiState.passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = stringResource(
                                if (uiState.passwordVisible) R.string.auth_hide_password else R.string.auth_show_password
                            )
                        )
                    }
                },
                isError = uiState.credentialError != null,
                supportingText = uiState.credentialError?.let { { Text(it) } }
            )
            ConfirmMode.GOOGLE_DELETE -> OutlinedTextField(
                value = uiState.credential,
                onValueChange = onCredentialChange,
                modifier = Modifier.fillMaxWidth(),
                enabled = !uiState.isLoading,
                singleLine = true,
                label = { Text(stringResource(R.string.delete_account_confirm_word_label)) },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    keyboardType = KeyboardType.Ascii,
                    autoCorrectEnabled = false
                ),
                isError = uiState.credentialError != null,
                supportingText = uiState.credentialError?.let { { Text(it) } }
            )
        }

        TextButton(onClick = onSwitchMode, enabled = !uiState.isLoading) {
            Text(
                stringResource(
                    if (uiState.mode == ConfirmMode.PASSWORD) {
                        R.string.delete_account_switch_to_google
                    } else {
                        R.string.delete_account_switch_to_password
                    }
                )
            )
        }
    }
}

/** Outlined, error-coloured and never pre-focused: this is not the primary action of the screen. */
@Composable
private fun DeleteButton(enabled: Boolean, isLoading: Boolean, onClick: () -> Unit) {
    val error = MaterialTheme.colorScheme.error
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = error),
        border = BorderStroke(1.dp, if (enabled) error else error.copy(alpha = 0.38f))
    ) {
        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = error)
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(stringResource(R.string.delete_account_button))
    }
}
