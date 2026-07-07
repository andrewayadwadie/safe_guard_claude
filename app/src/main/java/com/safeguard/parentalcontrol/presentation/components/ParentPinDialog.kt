package com.safeguard.parentalcontrol.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.safeguard.parentalcontrol.R

private const val MIN_PIN_LENGTH = 4
private const val MAX_PIN_LENGTH = 8

/**
 * Gate dialog for the on-device parent-review surfaces.
 *
 * If no PIN exists yet ([hasPin] == false) it runs in *create* mode (enter + confirm),
 * otherwise in *verify* mode. On success it invokes [onSuccess]; the caller then performs
 * the protected navigation. Verification is delegated to [onVerify] (constant-time, in
 * PreferencesManager) and PIN creation to [onCreate].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParentPinDialog(
    hasPin: Boolean,
    onVerify: (String) -> Boolean,
    onCreate: (String) -> Unit,
    onSuccess: () -> Unit,
    onDismiss: () -> Unit
) {
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    fun digitsOnly(s: String) = s.filter { it.isDigit() }.take(MAX_PIN_LENGTH)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (hasPin) stringResource(R.string.components_pin_enter_title) else stringResource(R.string.components_pin_create_title))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = if (hasPin) {
                        stringResource(R.string.components_pin_enter_desc)
                    } else {
                        stringResource(R.string.components_pin_create_desc, MIN_PIN_LENGTH, MAX_PIN_LENGTH)
                    }
                )
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = digitsOnly(it); error = null },
                    label = { Text(stringResource(R.string.components_pin_label)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    isError = error != null,
                    modifier = Modifier.fillMaxWidth()
                )
                if (!hasPin) {
                    OutlinedTextField(
                        value = confirm,
                        onValueChange = { confirm = digitsOnly(it); error = null },
                        label = { Text(stringResource(R.string.components_pin_confirm_label)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        isError = error != null,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                error?.let { Text(it, color = androidx.compose.material3.MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            val incorrectPinError = stringResource(R.string.components_pin_error_incorrect)
            val minLengthError = stringResource(R.string.components_pin_error_min_length, MIN_PIN_LENGTH)
            val mismatchError = stringResource(R.string.components_pin_error_mismatch)
            TextButton(onClick = {
                if (hasPin) {
                    if (onVerify(pin)) onSuccess() else error = incorrectPinError
                } else {
                    when {
                        pin.length < MIN_PIN_LENGTH -> error = minLengthError
                        pin != confirm -> error = mismatchError
                        else -> {
                            onCreate(pin)
                            onSuccess()
                        }
                    }
                }
            }) {
                Text(if (hasPin) stringResource(R.string.components_pin_unlock) else stringResource(R.string.components_pin_set))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}
