package com.safeguard.parentalcontrol.presentation.alerts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.safeguard.parentalcontrol.data.model.Alert
import com.safeguard.parentalcontrol.data.model.AlertSeverity
import com.safeguard.parentalcontrol.data.model.AlertType
import com.safeguard.parentalcontrol.presentation.theme.SafeGuardTheme
import com.safeguard.parentalcontrol.presentation.theme.SemanticColors
import com.safeguard.parentalcontrol.util.formatAsDateTime
import com.safeguard.parentalcontrol.util.formatAsRelative
import java.util.Date
import java.util.Locale

/**
 * Everything the backend returned about one alert.
 *
 * The list deliberately shows only title, message, type and time — enough to triage. This is
 * where a parent goes when triage is not enough: every field of the record, including the whole
 * `metadata` map, which is where the evidence about a violation actually lives.
 *
 * Read-only. Acting on an alert (mark read, dismiss, delete) stays on the card's overflow menu.
 */
@Composable
fun AlertDetailDialog(
    alert: Alert,
    onDismiss: () -> Unit
) {
    val (icon, iconTint) = severityVisuals(alert.severity)

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint
            )
        },
        title = { Text(alert.title) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                DetailRow("Message", alert.message)
                DetailRow("Alert ID", alert.id.toString())
                DetailRow("Type", humanize(alert.alertType.name))
                DetailRow("Severity", humanize(alert.severity.name))
                DetailRow("Device ID", alert.deviceId.toString())
                DetailRow("User ID", alert.userId.toString())
                DetailRow("Occurrences", alert.occurrenceCount.toString())
                DetailRow("Read", if (alert.isRead) "Yes" else "No")
                DetailRow("Dismissed", if (alert.isDismissed) "Yes" else "No")
                DetailRow(
                    label = "Created",
                    value = "${alert.createdAt.formatAsDateTime()} (${alert.createdAt.formatAsRelative()})"
                )
                DetailRow("Updated", alert.updatedAt?.formatAsDateTime() ?: EMPTY_VALUE)

                Divider(modifier = Modifier.padding(vertical = 4.dp))

                Text(
                    text = "Details",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )

                val metadata = alert.metadata
                if (metadata.isNullOrEmpty()) {
                    Text(
                        text = "No additional details",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    metadata.forEach { (key, value) ->
                        DetailRow(humanize(key), formatMetadataValue(value))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        // Long-pressable so a parent can copy evidence out — an alert message or a package
        // name is often what they want to paste somewhere else.
        SelectionContainer {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/** Shown wherever the backend returned nothing for a field. */
private const val EMPTY_VALUE = "—"

/**
 * The severity mapping the alert cards already use, kept identical so the dialog cannot drift
 * from the list it opened out of.
 */
private fun severityVisuals(severity: AlertSeverity): Pair<ImageVector, Color> = when (severity) {
    AlertSeverity.CRITICAL -> Icons.Default.Error to SemanticColors.severityCritical
    AlertSeverity.HIGH -> Icons.Default.Warning to SemanticColors.severityHigh
    AlertSeverity.MEDIUM -> Icons.Default.Info to SemanticColors.severityMedium
    AlertSeverity.LOW -> Icons.Default.CheckCircle to SemanticColors.severityLow
}

/** `INAPPROPRIATE_TEXT` / `package_name` -> `Inappropriate Text` / `Package Name`. */
private fun humanize(raw: String): String =
    raw.lowercase().split('_').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

/**
 * Render a metadata value the way a person reads it.
 *
 * Gson deserializes `Map<String, Any>` with every JSON number as a [Double], so the raw values
 * are `53.0` for a device id and `0.8700000047683716` for a confidence of 0.87. Whole numbers
 * therefore render as integers and fractions round to two places; nested lists and maps are
 * flattened rather than exposing `[Ljava.lang.Object;@...`.
 */
private fun formatMetadataValue(value: Any?): String = when (value) {
    null -> EMPTY_VALUE
    is Double -> formatDouble(value)
    is Float -> formatDouble(value.toDouble())
    is List<*> -> value.joinToString(", ") { formatMetadataValue(it) }
    is Map<*, *> -> value.entries.joinToString(", ") { "${it.key}: ${formatMetadataValue(it.value)}" }
    else -> value.toString()
}

private fun formatDouble(value: Double): String {
    val isWholeNumber = value % 1.0 == 0.0 &&
        value >= Long.MIN_VALUE.toDouble() &&
        value <= Long.MAX_VALUE.toDouble()
    // Locale.US, not the default: these are identifiers and scores, and an Arabic UI locale
    // would otherwise render them in Arabic-Indic digits inside an otherwise English dialog.
    return if (isWholeNumber) value.toLong().toString() else String.format(Locale.US, "%.2f", value)
}

private fun previewAlert() = Alert(
    id = 53,
    userId = 16,
    deviceId = 12,
    alertType = AlertType.INAPPROPRIATE_TEXT,
    severity = AlertSeverity.HIGH,
    title = "Inappropriate content detected",
    message = "Flagged text was found in a browser tab.",
    metadata = mapOf(
        "package_name" to "com.android.chrome",
        "confidence" to 0.8700000047683716,
        "device_db_id" to 53.0
    ),
    isRead = true,
    isDismissed = false,
    createdAt = Date(),
    updatedAt = null,
    occurrenceCount = 3
)

@Preview(name = "Alert detail · Light", showBackground = true)
@Composable
private fun AlertDetailDialogLightPreview() {
    SafeGuardTheme(darkTheme = false) { AlertDetailDialog(previewAlert()) {} }
}

@Preview(name = "Alert detail · Dark", showBackground = true)
@Composable
private fun AlertDetailDialogDarkPreview() {
    SafeGuardTheme(darkTheme = true) { AlertDetailDialog(previewAlert()) {} }
}
