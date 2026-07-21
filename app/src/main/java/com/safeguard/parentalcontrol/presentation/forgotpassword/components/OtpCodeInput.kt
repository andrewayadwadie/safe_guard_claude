package com.safeguard.parentalcontrol.presentation.forgotpassword.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalLayoutDirection
import com.safeguard.parentalcontrol.presentation.theme.SafeGuardDimens
import com.safeguard.parentalcontrol.presentation.theme.SafeGuardShapes
import com.safeguard.parentalcontrol.presentation.theme.SafeGuardTheme

/**
 * Six-box OTP entry backed by a single hidden [BasicTextField]. Auto-advance is
 * inherent (one field), pasting a 6-digit string fills every box, and non-digits are
 * filtered out. The boxes are forced to render left-to-right even under an Arabic RTL
 * layout so the numeric sequence reads naturally.
 *
 * @param value current code (0..[length] digits)
 * @param onValueChange invoked with the digits-only, length-capped value
 */
@Composable
fun OtpCodeInput(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    length: Int = 6,
    isError: Boolean = false,
) {
    BasicTextField(
        value = value,
        onValueChange = { raw ->
            val digits = raw.filter { it.isDigit() }.take(length)
            onValueChange(digits)
        },
        modifier = modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        decorationBox = {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(SafeGuardDimens.spacingXs)
                ) {
                    repeat(length) { index ->
                        val char = value.getOrNull(index)?.toString() ?: ""
                        val borderColor = when {
                            isError -> MaterialTheme.colorScheme.error
                            char.isNotEmpty() -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.outlineVariant
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp)
                                .border(1.dp, borderColor, SafeGuardShapes.medium)
                                .padding(SafeGuardDimens.spacingXs),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = char,
                                style = MaterialTheme.typography.titleLarge,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    )
}

@Composable
private fun OtpPreviewContent() {
    Surface(color = MaterialTheme.colorScheme.background) {
        Row(modifier = Modifier.fillMaxWidth().padding(SafeGuardDimens.screenPadding)) {
            var code by remember { mutableStateOf("1234") }
            OtpCodeInput(value = code, onValueChange = { code = it })
        }
    }
}

@Preview(name = "OTP · Light")
@Composable
private fun OtpCodeInputLightPreview() {
    SafeGuardTheme(darkTheme = false) { OtpPreviewContent() }
}

@Preview(name = "OTP · Dark")
@Composable
private fun OtpCodeInputDarkPreview() {
    SafeGuardTheme(darkTheme = true) { OtpPreviewContent() }
}
