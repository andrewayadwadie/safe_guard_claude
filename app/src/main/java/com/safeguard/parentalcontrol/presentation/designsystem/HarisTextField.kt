package com.safeguard.parentalcontrol.presentation.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.safeguard.parentalcontrol.presentation.theme.SafeGuardDimens
import com.safeguard.parentalcontrol.presentation.theme.SafeGuardShapes
import com.safeguard.parentalcontrol.presentation.theme.SafeGuardTheme
import com.safeguard.parentalcontrol.presentation.theme.harisColors

/**
 * Filled text field: surfaceContainerHigh fill, 1dp outlineVariant, 12dp radius,
 * label rendered above the field, helper + error slots below (error supersedes helper).
 */
@Composable
fun HarisTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    helperText: String? = null,
    errorText: String? = null,
    isError: Boolean = errorText != null,
    singleLine: Boolean = true,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(SafeGuardDimens.spacingXs)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            isError = isError,
            singleLine = singleLine,
            placeholder = placeholder?.let { { Text(it) } },
            leadingIcon = leadingIcon,
            trailingIcon = trailingIcon,
            visualTransformation = visualTransformation,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            shape = SafeGuardShapes.medium,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = harisColors.surfaceContainerHigh,
                unfocusedContainerColor = harisColors.surfaceContainerHigh,
                errorContainerColor = harisColors.surfaceContainerHigh,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
            )
        )
        val support = errorText ?: helperText
        if (support != null) {
            Text(
                text = support,
                style = MaterialTheme.typography.bodySmall,
                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TextFieldPreviewContent() {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(SafeGuardDimens.screenPadding),
            verticalArrangement = Arrangement.spacedBy(SafeGuardDimens.stackMd)
        ) {
            var email by remember { mutableStateOf("parent@haris.app") }
            HarisTextField(email, { email = it }, label = "Email", helperText = "We never share this.")
            var pw by remember { mutableStateOf("123") }
            HarisTextField(pw, { pw = it }, label = "Password", errorText = "At least 8 characters")
        }
    }
}

@Preview(name = "TextField · Light")
@Composable
private fun HarisTextFieldLightPreview() {
    SafeGuardTheme(darkTheme = false) { TextFieldPreviewContent() }
}

@Preview(name = "TextField · Dark")
@Composable
private fun HarisTextFieldDarkPreview() {
    SafeGuardTheme(darkTheme = true) { TextFieldPreviewContent() }
}
