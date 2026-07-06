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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

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
            Text(if (hasPin) "Enter parent PIN" else "Create a parent PIN")
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = if (hasPin) {
                        "Enter your PIN to review what was flagged on this device."
                    } else {
                        "Set a $MIN_PIN_LENGTH–$MAX_PIN_LENGTH digit PIN. You'll enter it to review flagged photos and text on this device. Keep it private from your child."
                    }
                )
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = digitsOnly(it); error = null },
                    label = { Text("PIN") },
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
                        label = { Text("Confirm PIN") },
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
            TextButton(onClick = {
                if (hasPin) {
                    if (onVerify(pin)) onSuccess() else error = "Incorrect PIN"
                } else {
                    when {
                        pin.length < MIN_PIN_LENGTH -> error = "PIN must be at least $MIN_PIN_LENGTH digits"
                        pin != confirm -> error = "PINs don't match"
                        else -> {
                            onCreate(pin)
                            onSuccess()
                        }
                    }
                }
            }) {
                Text(if (hasPin) "Unlock" else "Set PIN")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
