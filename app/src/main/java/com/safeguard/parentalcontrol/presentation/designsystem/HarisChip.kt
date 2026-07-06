package com.safeguard.parentalcontrol.presentation.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.safeguard.parentalcontrol.presentation.theme.PillShape
import com.safeguard.parentalcontrol.presentation.theme.SafeGuardDimens
import com.safeguard.parentalcontrol.presentation.theme.SafeGuardShapes
import com.safeguard.parentalcontrol.presentation.theme.SafeGuardTheme

enum class HarisChipVariant { Pill, Filter }

/** Selectable chip. Pill (default) or small-radius Filter variant. */
@Composable
fun HarisChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: HarisChipVariant = HarisChipVariant.Pill,
    leadingIcon: (@Composable () -> Unit)? = null,
) {
    val shape = if (variant == HarisChipVariant.Pill) PillShape else SafeGuardShapes.extraSmall
    FilterChip(
        selected = selected,
        onClick = onClick,
        modifier = modifier,
        shape = shape,
        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
        leadingIcon = leadingIcon,
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
        )
    )
}

@Composable
private fun ChipPreviewContent() {
    Surface(color = MaterialTheme.colorScheme.background) {
        var sel by remember { mutableStateOf("Apps") }
        Row(
            Modifier.padding(SafeGuardDimens.screenPadding),
            horizontalArrangement = Arrangement.spacedBy(SafeGuardDimens.stackSm)
        ) {
            listOf("Apps", "Websites", "Location").forEach { f ->
                HarisChip(f, selected = sel == f, onClick = { sel = f }, variant = HarisChipVariant.Filter)
            }
        }
    }
}

@Preview(name = "Chip · Light")
@Composable
private fun HarisChipLightPreview() {
    SafeGuardTheme(darkTheme = false) { ChipPreviewContent() }
}

@Preview(name = "Chip · Dark")
@Composable
private fun HarisChipDarkPreview() {
    SafeGuardTheme(darkTheme = true) { ChipPreviewContent() }
}
