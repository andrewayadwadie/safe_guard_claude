package com.safeguard.parentalcontrol.presentation.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

/**
 * Flips directional icons (back/forward/chevron/send arrows) horizontally in RTL layouts.
 * Compose BOM is locked below 1.6.0 so `Icons.AutoMirrored.*` is unavailable (see research.md R3).
 * Never apply to brand logos or status icons (shield/warning) — those must not mirror.
 */
@Composable
fun Modifier.mirrorInRtl(): Modifier {
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    return if (isRtl) this.graphicsLayer(scaleX = -1f) else this
}
