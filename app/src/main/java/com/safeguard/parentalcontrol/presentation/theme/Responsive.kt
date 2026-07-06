package com.safeguard.parentalcontrol.presentation.theme

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Responsive width buckets, aligned with Material window size classes. */
enum class ScreenWidth { Compact, Medium, Expanded }

/** Maximum readable content width on Medium/Expanded screens. */
private val MaxContentWidth = 600.dp

/** Derive the current [ScreenWidth] from the available width in dp. */
@Composable
fun rememberScreenWidth(): ScreenWidth {
    val widthDp = LocalConfiguration.current.screenWidthDp
    return when {
        widthDp < 600 -> ScreenWidth.Compact
        widthDp < 840 -> ScreenWidth.Medium
        else -> ScreenWidth.Expanded
    }
}

/**
 * Caps content at [MaxContentWidth] and centers it on Medium/Expanded widths;
 * full width on Compact.
 */
fun Modifier.responsiveContentWidth(width: ScreenWidth): Modifier = when (width) {
    ScreenWidth.Compact -> this.fillMaxWidth()
    ScreenWidth.Medium, ScreenWidth.Expanded ->
        this.fillMaxWidth()
            .wrapContentWidth(Alignment.CenterHorizontally)
            .widthIn(max = MaxContentWidth)
}

/** Screen padding scaled by width bucket: 20 / 24 / 32 dp. */
fun responsiveScreenPadding(width: ScreenWidth): Dp = when (width) {
    ScreenWidth.Compact -> 20.dp
    ScreenWidth.Medium -> 24.dp
    ScreenWidth.Expanded -> 32.dp
}

@Composable
private fun SampleResponsiveCard() {
    Surface(color = harisColors.surfaceContainer, shape = SafeGuardShapes.large) {
        Column(modifier = Modifier.fillMaxWidth().padding(SafeGuardDimens.gutter)) {
            Text("Responsive card", style = MaterialTheme.typography.titleMedium)
            Text(
                "Capped at 600dp and centered on Medium/Expanded widths.",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Preview(name = "Responsive · 360dp", widthDp = 360, showBackground = true)
@Composable
private fun ResponsiveCard360Preview() {
    SafeGuardTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.responsiveContentWidth(ScreenWidth.Compact)) {
                SampleResponsiveCard()
            }
        }
    }
}

@Preview(name = "Responsive · 840dp", widthDp = 840, showBackground = true)
@Composable
private fun ResponsiveCard840Preview() {
    SafeGuardTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.responsiveContentWidth(ScreenWidth.Expanded)) {
                SampleResponsiveCard()
            }
        }
    }
}
