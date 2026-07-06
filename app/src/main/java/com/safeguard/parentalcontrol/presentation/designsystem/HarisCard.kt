package com.safeguard.parentalcontrol.presentation.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.safeguard.parentalcontrol.presentation.theme.SafeGuardDimens
import com.safeguard.parentalcontrol.presentation.theme.SafeGuardShapes
import com.safeguard.parentalcontrol.presentation.theme.SafeGuardTheme
import com.safeguard.parentalcontrol.presentation.theme.SemanticColors
import com.safeguard.parentalcontrol.presentation.theme.harisColors

enum class HarisCardStatus { None, Safe, Warning }

/**
 * 16dp card on surfaceContainer with a 1dp outlineVariant border in dark theme.
 * Optional top-right status dot: Safe = success, Warning = warning.
 */
@Composable
fun HarisCard(
    modifier: Modifier = Modifier,
    status: HarisCardStatus = HarisCardStatus.None,
    content: @Composable ColumnScope.() -> Unit,
) {
    val border: BorderStroke? = if (isSystemInDarkTheme()) {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    } else null

    Card(
        modifier = modifier,
        shape = SafeGuardShapes.large,
        colors = CardDefaults.cardColors(containerColor = harisColors.surfaceContainer),
        border = border
    ) {
        Box(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(SafeGuardDimens.gutter), content = content)
            if (status != HarisCardStatus.None) {
                val dot: Color = when (status) {
                    HarisCardStatus.Safe -> SemanticColors.success
                    HarisCardStatus.Warning -> SemanticColors.warning
                    HarisCardStatus.None -> Color.Transparent
                }
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(SafeGuardDimens.gutter)
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(dot)
                )
            }
        }
    }
}

@Composable
private fun CardPreviewContent() {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxWidth().padding(SafeGuardDimens.screenPadding),
            verticalArrangement = Arrangement.spacedBy(SafeGuardDimens.stackMd)
        ) {
            HarisCard(Modifier.fillMaxWidth(), status = HarisCardStatus.Safe) {
                Text("Living room tablet", style = MaterialTheme.typography.titleMedium)
                Text("All policies enforced", style = MaterialTheme.typography.bodyMedium)
            }
            HarisCard(Modifier.fillMaxWidth(), status = HarisCardStatus.Warning) {
                Text("Alex's phone", style = MaterialTheme.typography.titleMedium)
                Text("Screen-time limit exceeded", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Preview(name = "Card · Light")
@Composable
private fun HarisCardLightPreview() {
    SafeGuardTheme(darkTheme = false) { CardPreviewContent() }
}

@Preview(name = "Card · Dark")
@Composable
private fun HarisCardDarkPreview() {
    SafeGuardTheme(darkTheme = true) { CardPreviewContent() }
}
