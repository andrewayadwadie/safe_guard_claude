package com.safeguard.parentalcontrol.presentation.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import com.safeguard.parentalcontrol.presentation.theme.SafeGuardDimens
import com.safeguard.parentalcontrol.presentation.theme.SafeGuardShapes
import com.safeguard.parentalcontrol.presentation.theme.SafeGuardTheme
import com.safeguard.parentalcontrol.presentation.theme.SemanticColors

/** Brand-gradient banner (petrol -> aqua) for dashboard/summary surfaces. */
@Composable
fun HarisGradientHeader(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(SafeGuardShapes.large)
            .background(Brush.linearGradient(SemanticColors.gradientPrimary))
            .padding(SafeGuardDimens.stackLg),
        content = content
    )
}

@Composable
private fun GradientHeaderPreviewContent() {
    Surface(color = MaterialTheme.colorScheme.background) {
        HarisGradientHeader(Modifier.padding(SafeGuardDimens.screenPadding)) {
            Column {
                Text(
                    "Today",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White
                )
                Text(
                    "3h 12m screen time",
                    style = MaterialTheme.typography.displaySmall,
                    color = Color.White
                )
            }
        }
    }
}

@Preview(name = "GradientHeader · Light")
@Composable
private fun HarisGradientHeaderLightPreview() {
    SafeGuardTheme(darkTheme = false) { GradientHeaderPreviewContent() }
}

@Preview(name = "GradientHeader · Dark")
@Composable
private fun HarisGradientHeaderDarkPreview() {
    SafeGuardTheme(darkTheme = true) { GradientHeaderPreviewContent() }
}
