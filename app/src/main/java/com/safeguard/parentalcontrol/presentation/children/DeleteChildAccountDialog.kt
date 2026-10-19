package com.safeguard.parentalcontrol.presentation.children

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.safeguard.parentalcontrol.R
import com.safeguard.parentalcontrol.data.model.FamilyLink

/**
 * Confirmation for permanently deleting a linked child's ACCOUNT. It is deliberately heavier than
 * the "Stop monitoring" dialog: it names the child, explains the difference from unlinking, and
 * needs the child's exact name plus the parent's own re-authentication before it can be confirmed.
 *
 * Stateless: everything comes in as values and goes out as lambdas. Google parents confirm with a
 * fresh Google sign-in instead of a password, and the confirm button then opens the account chooser.
 */
@Composable
fun DeleteChildAccountDialog(
    child: FamilyLink,
    nameInput: String,
    credential: String,
    parentIsGoogle: Boolean,
    credentialError: String?,
    notice: String?,
    isDeleting: Boolean,
    canDelete: Boolean,
    onNameChange: (String) -> Unit,
    onCredentialChange: (String) -> Unit,
    onConfirmPassword: () -> Unit,
    onConfirmGoogle: () -> Unit,
    onDismiss: () -> Unit
) {
    var passwordVisible by remember { mutableStateOf(false) }
    val error = MaterialTheme.colorScheme.error

    AlertDialog(
        onDismissRequest = { if (!isDeleting) onDismiss() },
        icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = error) },
        title = { Text(stringResource(R.string.children_delete_title, child.childName)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.children_delete_message, child.childName),
                    style = MaterialTheme.typography.bodyMedium
                )

                OutlinedTextField(
                    value = nameInput,
                    onValueChange = onNameChange,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isDeleting,
                    singleLine = true,
                    label = { Text(stringResource(R.string.children_delete_type_name_label, child.childName)) },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Text
                    )
                )

                if (!parentIsGoogle) {
                    OutlinedTextField(
                        value = credential,
                        onValueChange = onCredentialChange,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isDeleting,
                        singleLine = true,
                        label = { Text(stringResource(R.string.children_delete_password_label)) },
                        visualTransformation = if (passwordVisible) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = stringResource(
                                        if (passwordVisible) R.string.auth_hide_password else R.string.auth_show_password
                                    )
                                )
                            }
                        },
                        isError = credentialError != null,
                        supportingText = credentialError?.let { { Text(it) } }
                    )
                }

                notice?.let {
                    Text(text = it, style = MaterialTheme.typography.bodySmall, color = error)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = if (parentIsGoogle) onConfirmGoogle else onConfirmPassword,
                enabled = canDelete,
                colors = ButtonDefaults.textButtonColors(contentColor = error)
            ) {
                if (isDeleting) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = error)
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    stringResource(
                        if (parentIsGoogle) R.string.children_delete_confirm_google else R.string.children_delete_action
                    )
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isDeleting) {
                Text(stringResource(R.string.delete_account_cancel))
            }
        }
    )
}
