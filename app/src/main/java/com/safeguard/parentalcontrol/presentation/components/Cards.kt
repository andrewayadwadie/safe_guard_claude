package com.safeguard.parentalcontrol.presentation.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.safeguard.parentalcontrol.presentation.theme.*

// ============================================================================
// DASHBOARD CARDS - Modern card designs for dashboard
// ============================================================================

/**
 * Hero card with gradient background for screen time display
 */
@Composable
fun ScreenTimeHeroCard(
    usedSeconds: Int,
    limitSeconds: Int?,
    unlockCount: Int,
    modifier: Modifier = Modifier,
    onManageLimits: (() -> Unit)? = null,
    isParent: Boolean = false
) {
    val usedMinutes = usedSeconds / 60
    val limitMinutes = limitSeconds?.let { it / 60 }

    val progress = if (limitMinutes != null && limitMinutes > 0) {
        (usedMinutes.toFloat() / limitMinutes).coerceIn(0f, 1f)
    } else {
        0f
    }

    val statusColor = when {
        progress >= 1f -> SemanticColors.screenTimeExceeded
        progress >= 0.8f -> SemanticColors.screenTimeWarning
        else -> SemanticColors.screenTimeNormal
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = SafeGuardDimens.elevationSm
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(SafeGuardDimens.paddingCard),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Today's Screen Time",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(SafeGuardDimens.spacingLg))

            // Circular progress
            CircularScreenTimeProgress(
                usedMinutes = usedMinutes,
                limitMinutes = limitMinutes,
                size = 140.dp,
                strokeWidth = 14.dp
            )

            Spacer(modifier = Modifier.height(SafeGuardDimens.spacingLg))

            // Stats row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItem(
                    icon = Icons.Default.LockOpen,
                    value = unlockCount.toString(),
                    label = "Unlocks",
                    color = MaterialTheme.colorScheme.primary
                )

                if (limitMinutes != null) {
                    StatItem(
                        icon = Icons.Default.Timer,
                        value = formatMinutesToTime(limitMinutes - usedMinutes.coerceAtMost(limitMinutes)),
                        label = "Remaining",
                        color = statusColor
                    )
                }
            }

            // Manage limits button for parents
            if (isParent && onManageLimits != null) {
                Spacer(modifier = Modifier.height(SafeGuardDimens.spacingLg))

                OutlinedButton(
                    onClick = onManageLimits,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(SafeGuardDimens.spacingSm))
                    Text("Manage Limits")
                }
            }
        }
    }
}

@Composable
private fun StatItem(
    icon: ImageVector,
    value: String,
    label: String,
    color: Color
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = color
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = color
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Quick action card with icon and arrow
 */
@Composable
fun QuickActionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    iconBackgroundColor: Color = MaterialTheme.colorScheme.primaryContainer,
    trailingContent: @Composable (() -> Unit)? = null
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = SafeGuardDimens.elevationSm
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(SafeGuardDimens.paddingCard),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon container
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(iconBackgroundColor),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(SafeGuardDimens.spacingMd))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (trailingContent != null) {
                trailingContent()
            } else {
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Status card showing device or service status
 */
@Composable
fun StatusCard(
    title: String,
    status: String,
    isActive: Boolean,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    showToggle: Boolean = false,
    onToggle: ((Boolean) -> Unit)? = null,
    isLoading: Boolean = false
) {
    val cardModifier = if (onClick != null) {
        modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    } else {
        modifier.fillMaxWidth()
    }

    Card(
        modifier = cardModifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) {
                SemanticColors.successContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(SafeGuardDimens.paddingCard),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Status indicator
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(
                        if (isActive) SemanticColors.success else SemanticColors.statusOffline
                    )
            )

            Spacer(modifier = Modifier.width(SafeGuardDimens.spacingMd))

            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = if (isActive) SemanticColors.success else MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.width(SafeGuardDimens.spacingMd))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (showToggle && onToggle != null) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Switch(
                        checked = isActive,
                        onCheckedChange = onToggle,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = SemanticColors.success,
                            checkedTrackColor = SemanticColors.successContainer
                        )
                    )
                }
            }
        }
    }
}

/**
 * Alert card with severity indicator
 */
@Composable
fun AlertCard(
    title: String,
    message: String,
    timestamp: String,
    severity: AlertSeverityLevel,
    isRead: Boolean,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null
) {
    val severityColor = when (severity) {
        AlertSeverityLevel.CRITICAL -> SemanticColors.severityCritical
        AlertSeverityLevel.HIGH -> SemanticColors.severityHigh
        AlertSeverityLevel.MEDIUM -> SemanticColors.severityMedium
        AlertSeverityLevel.LOW -> SemanticColors.severityLow
    }

    val severityIcon = when (severity) {
        AlertSeverityLevel.CRITICAL -> Icons.Default.Error
        AlertSeverityLevel.HIGH -> Icons.Default.Warning
        AlertSeverityLevel.MEDIUM -> Icons.Default.Info
        AlertSeverityLevel.LOW -> Icons.Default.CheckCircle
    }

    Card(
        onClick = onClick ?: {},
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (!isRead) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        border = if (!isRead) {
            BorderStroke(1.dp, severityColor.copy(alpha = 0.3f))
        } else null
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(SafeGuardDimens.paddingCard),
            verticalAlignment = Alignment.Top
        ) {
            // Severity icon with background
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(severityColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = severityIcon,
                    contentDescription = null,
                    tint = severityColor,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(SafeGuardDimens.spacingMd))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (!isRead) FontWeight.SemiBold else FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f, fill = false),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!isRead) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(SafeGuardDimens.spacingSm))

                Text(
                    text = timestamp,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }

            if (onDismiss != null) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Dismiss",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

enum class AlertSeverityLevel {
    CRITICAL, HIGH, MEDIUM, LOW
}

/**
 * App usage card for top apps list
 */
@Composable
fun AppUsageCard(
    appName: String,
    packageName: String,
    usageMinutes: Int,
    modifier: Modifier = Modifier,
    appIcon: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    showProgress: Boolean = false,
    progressPercentage: Float = 0f
) {
    Card(
        onClick = onClick ?: {},
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 1.dp
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(SafeGuardDimens.paddingCardSmall),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // App icon or placeholder
            if (appIcon != null) {
                appIcon()
            } else {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = appName.take(1).uppercase(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            Spacer(modifier = Modifier.width(SafeGuardDimens.spacingMd))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = appName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (showProgress) {
                    Spacer(modifier = Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = progressPercentage,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.primaryContainer
                    )
                }
            }

            Spacer(modifier = Modifier.width(SafeGuardDimens.spacingMd))

            Text(
                text = formatMinutesToTime(usageMinutes),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/**
 * Device card for parent's device list
 */
@Composable
fun DeviceCard(
    deviceName: String,
    status: DeviceStatusType,
    lastSeen: String,
    modifier: Modifier = Modifier,
    isSelected: Boolean = false,
    onClick: () -> Unit,
    onMoreClick: (() -> Unit)? = null
) {
    val statusColor = when (status) {
        DeviceStatusType.ACTIVE -> SemanticColors.statusOnline
        DeviceStatusType.SUSPENDED -> SemanticColors.statusSuspended
        DeviceStatusType.INACTIVE -> SemanticColors.statusOffline
    }

    val statusText = when (status) {
        DeviceStatusType.ACTIVE -> "Online"
        DeviceStatusType.SUSPENDED -> "Suspended"
        DeviceStatusType.INACTIVE -> "Offline"
    }

    Card(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        border = if (isSelected) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else null
    ) {
        Column(
            modifier = Modifier.padding(SafeGuardDimens.paddingCard)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.PhoneAndroid,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = if (isSelected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )

                Spacer(modifier = Modifier.width(SafeGuardDimens.spacingMd))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = deviceName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(statusColor)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.bodySmall,
                            color = statusColor
                        )
                    }
                }

                if (onMoreClick != null) {
                    IconButton(onClick = onMoreClick) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "More options"
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(SafeGuardDimens.spacingSm))

            Text(
                text = "Last seen: $lastSeen",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

enum class DeviceStatusType {
    ACTIVE, SUSPENDED, INACTIVE
}

/**
 * Info banner card for important messages
 */
@Composable
fun InfoBanner(
    message: String,
    modifier: Modifier = Modifier,
    type: BannerType = BannerType.INFO,
    icon: ImageVector? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null
) {
    val (backgroundColor, contentColor, defaultIcon) = when (type) {
        BannerType.INFO -> Triple(
            SemanticColors.infoContainer,
            MaterialTheme.colorScheme.onSurface,
            Icons.Default.Info
        )
        BannerType.WARNING -> Triple(
            SemanticColors.warningContainer,
            MaterialTheme.colorScheme.onSurface,
            Icons.Default.Warning
        )
        BannerType.ERROR -> Triple(
            SemanticColors.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
            Icons.Default.Error
        )
        BannerType.SUCCESS -> Triple(
            SemanticColors.successContainer,
            MaterialTheme.colorScheme.onSurface,
            Icons.Default.CheckCircle
        )
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = backgroundColor
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(SafeGuardDimens.paddingCardSmall),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon ?: defaultIcon,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = contentColor
            )

            Spacer(modifier = Modifier.width(SafeGuardDimens.spacingMd))

            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = contentColor,
                modifier = Modifier.weight(1f)
            )

            if (actionLabel != null && onAction != null) {
                TextButton(
                    onClick = onAction,
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Text(
                        text = actionLabel,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }

            if (onDismiss != null) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Dismiss",
                        modifier = Modifier.size(18.dp),
                        tint = contentColor
                    )
                }
            }
        }
    }
}

enum class BannerType {
    INFO, WARNING, ERROR, SUCCESS
}

/**
 * Section header with optional action
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) {
                Text(
                    text = actionLabel,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}
