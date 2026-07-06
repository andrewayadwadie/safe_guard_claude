package com.safeguard.parentalcontrol.presentation.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.safeguard.parentalcontrol.presentation.theme.SafeGuardDimens
import com.safeguard.parentalcontrol.presentation.theme.SafeGuardTheme

/** Switch with brand-primary (teal) track/thumb when on. */
@Composable
fun HarisSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
            checkedTrackColor = MaterialTheme.colorScheme.primary,
            checkedBorderColor = MaterialTheme.colorScheme.primary
        )
    )
}

@Composable
private fun SwitchPreviewContent() {
    Surface(color = MaterialTheme.colorScheme.background) {
        var on by remember { mutableStateOf(true) }
        var off by remember { mutableStateOf(false) }
        Row(
            Modifier.padding(SafeGuardDimens.screenPadding),
            horizontalArrangement = Arrangement.spacedBy(SafeGuardDimens.stackLg)
        ) {
            Text("On", style = MaterialTheme.typography.bodyMedium)
            HarisSwitch(on, { on = it })
            Text("Off", style = MaterialTheme.typography.bodyMedium)
            HarisSwitch(off, { off = it })
        }
    }
}

@Preview(name = "Switch · Light")
@Composable
private fun HarisSwitchLightPreview() {
    SafeGuardTheme(darkTheme = false) { SwitchPreviewContent() }
}

@Preview(name = "Switch · Dark")
@Composable
private fun HarisSwitchDarkPreview() {
    SafeGuardTheme(darkTheme = true) { SwitchPreviewContent() }
}
