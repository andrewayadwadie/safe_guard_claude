package com.safeguard.parentalcontrol.presentation.designsystem

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.safeguard.parentalcontrol.presentation.theme.SafeGuardDimens
import com.safeguard.parentalcontrol.presentation.theme.SafeGuardTheme

/** List row: min 56dp height, divider inset 16dp from both edges. */
@Composable
fun HarisListItem(
    headline: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    showDivider: Boolean = false,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
                .heightIn(min = SafeGuardDimens.listItemMinHeight)
                .padding(horizontal = SafeGuardDimens.gutter, vertical = SafeGuardDimens.stackSm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SafeGuardDimens.stackMd)
        ) {
            if (leading != null) leading()
            Column(Modifier.weight(1f)) {
                Text(headline, style = MaterialTheme.typography.titleMedium)
                if (supporting != null) {
                    Text(
                        supporting,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (trailing != null) trailing()
        }
        if (showDivider) {
            Divider(
                modifier = Modifier.padding(horizontal = SafeGuardDimens.listSeparatorInset),
                color = MaterialTheme.colorScheme.outlineVariant
            )
        }
    }
}

@Composable
private fun ListItemPreviewContent() {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxWidth().padding(vertical = SafeGuardDimens.stackMd)) {
            HarisListItem("Living room tablet", supporting = "Online · 2 alerts", onClick = {}, showDivider = true)
            HarisListItem("Alex's phone", supporting = "Offline", onClick = {}, showDivider = true)
            HarisListItem("Kitchen display", supporting = "Suspended", onClick = {})
        }
    }
}

@Preview(name = "ListItem · Light")
@Composable
private fun HarisListItemLightPreview() {
    SafeGuardTheme(darkTheme = false) { ListItemPreviewContent() }
}

@Preview(name = "ListItem · Dark")
@Composable
private fun HarisListItemDarkPreview() {
    SafeGuardTheme(darkTheme = true) { ListItemPreviewContent() }
}
