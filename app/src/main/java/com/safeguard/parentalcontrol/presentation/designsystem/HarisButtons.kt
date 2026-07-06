package com.safeguard.parentalcontrol.presentation.designsystem

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.safeguard.parentalcontrol.presentation.theme.PillShape
import com.safeguard.parentalcontrol.presentation.theme.SafeGuardDimens
import com.safeguard.parentalcontrol.presentation.theme.SafeGuardTheme
import com.safeguard.parentalcontrol.presentation.theme.harisColors
import com.safeguard.parentalcontrol.presentation.theme.pressScale

/** Pill primary CTA: primary/onPrimary, 56dp, labelLarge, press-scale, disabled support. */
@Composable
fun HarisPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: (@Composable () -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Button(
        onClick = onClick,
        modifier = modifier.height(SafeGuardDimens.buttonHeightLarge).pressScale { pressed },
        enabled = enabled,
        shape = PillShape,
        interactionSource = interaction,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        ),
        contentPadding = PaddingValues(horizontal = SafeGuardDimens.stackLg)
    ) {
        if (leadingIcon != null) {
            leadingIcon()
        }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** Pill secondary: high tonal surface container, secondary text. */
@Composable
fun HarisSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(SafeGuardDimens.buttonHeightLarge),
        enabled = enabled,
        shape = PillShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = harisColors.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.secondary
        ),
        contentPadding = PaddingValues(horizontal = SafeGuardDimens.stackLg)
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** Transparent ghost button, tertiary text. */
@Composable
fun HarisGhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier.height(SafeGuardDimens.buttonHeightLarge),
        enabled = enabled,
        shape = PillShape,
        colors = ButtonDefaults.textButtonColors(
            contentColor = MaterialTheme.colorScheme.tertiary
        )
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun ButtonsPreviewContent() {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(SafeGuardDimens.screenPadding),
            verticalArrangement = Arrangement.spacedBy(SafeGuardDimens.stackMd)
        ) {
            HarisPrimaryButton("Continue", {}, Modifier.fillMaxWidth())
            HarisPrimaryButton("Disabled", {}, Modifier.fillMaxWidth(), enabled = false)
            HarisSecondaryButton("Maybe later", {}, Modifier.fillMaxWidth())
            Row { HarisGhostButton("Skip", {}) }
        }
    }
}

@Preview(name = "Buttons · Light")
@Composable
private fun HarisButtonsLightPreview() {
    SafeGuardTheme(darkTheme = false) { ButtonsPreviewContent() }
}

@Preview(name = "Buttons · Dark")
@Composable
private fun HarisButtonsDarkPreview() {
    SafeGuardTheme(darkTheme = true) { ButtonsPreviewContent() }
}
