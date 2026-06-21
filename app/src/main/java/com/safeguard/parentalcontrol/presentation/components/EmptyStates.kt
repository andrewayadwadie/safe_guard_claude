package com.safeguard.parentalcontrol.presentation.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.safeguard.parentalcontrol.presentation.theme.SafeGuardDimens
import com.safeguard.parentalcontrol.presentation.theme.SemanticColors

// ============================================================================
// EMPTY STATES - For when there's no data to display
// ============================================================================

/**
 * Generic empty state component with icon, title, description, and optional action
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
    animate: Boolean = true
) {
    val infiniteTransition = rememberInfiniteTransition(label = "empty_state")
    val iconAlpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "icon_alpha"
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(SafeGuardDimens.spacingXxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier
                .size(SafeGuardDimens.iconSizeHero)
                .alpha(if (animate) iconAlpha else 0.6f),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
        )

        Spacer(modifier = Modifier.height(SafeGuardDimens.spacingXl))

        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(SafeGuardDimens.spacingSm))

        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (actionLabel != null && onAction != null) {
            Spacer(modifier = Modifier.height(SafeGuardDimens.spacingXl))

            Button(
                onClick = onAction,
                modifier = Modifier.height(SafeGuardDimens.buttonHeightMedium)
            ) {
                Text(actionLabel)
            }

            if (secondaryActionLabel != null && onSecondaryAction != null) {
                Spacer(modifier = Modifier.height(SafeGuardDimens.spacingSm))

                TextButton(onClick = onSecondaryAction) {
                    Text(secondaryActionLabel)
                }
            }
        }
    }
}

/**
 * Empty state for no devices registered
 */
@Composable
fun EmptyDevicesState(
    modifier: Modifier = Modifier,
    onAddDevice: (() -> Unit)? = null
) {
    EmptyState(
        icon = Icons.Outlined.Devices,
        title = "No Devices Yet",
        description = "Add a child's device to start monitoring their activity and keeping them safe online.",
        actionLabel = if (onAddDevice != null) "Add Device" else null,
        onAction = onAddDevice,
        modifier = modifier
    )
}

/**
 * Empty state for no alerts
 */
@Composable
fun EmptyAlertsState(
    modifier: Modifier = Modifier,
    filterApplied: Boolean = false
) {
    EmptyState(
        icon = Icons.Outlined.NotificationsNone,
        title = if (filterApplied) "No Matching Alerts" else "All Clear!",
        description = if (filterApplied) {
            "No alerts match your current filter. Try adjusting the filter or check back later."
        } else {
            "There are no alerts to show. Your child's online activity looks safe!"
        },
        modifier = modifier
    )
}

/**
 * Empty state for no app usage data
 */
@Composable
fun EmptyAppUsageState(
    modifier: Modifier = Modifier,
    hasPermission: Boolean = true
) {
    EmptyState(
        icon = Icons.Outlined.Apps,
        title = if (hasPermission) "No Usage Data" else "Permission Required",
        description = if (hasPermission) {
            "No app usage data available yet. Check back later to see which apps are being used."
        } else {
            "Grant usage access permission to see app usage statistics."
        },
        modifier = modifier
    )
}

/**
 * Empty state for no children linked
 */
@Composable
fun EmptyChildrenState(
    modifier: Modifier = Modifier,
    onLinkChild: (() -> Unit)? = null
) {
    EmptyState(
        icon = Icons.Outlined.FamilyRestroom,
        title = "No Children Linked",
        description = "Link your child's account to start monitoring their device activity and online safety.",
        actionLabel = if (onLinkChild != null) "Link Child Account" else null,
        onAction = onLinkChild,
        modifier = modifier
    )
}

/**
 * Empty state for no screen time data
 */
@Composable
fun EmptyScreenTimeState(
    modifier: Modifier = Modifier
) {
    EmptyState(
        icon = Icons.Outlined.Timer,
        title = "No Screen Time Data",
        description = "Screen time tracking hasn't recorded any data yet. It will update as the device is used.",
        modifier = modifier
    )
}

/**
 * Empty state for word lists
 */
@Composable
fun EmptyWordListState(
    modifier: Modifier = Modifier,
    listType: String = "words",
    onAddWord: (() -> Unit)? = null
) {
    EmptyState(
        icon = Icons.Outlined.TextFields,
        title = "No Custom ${listType.replaceFirstChar { it.uppercase() }}",
        description = "Add custom words to monitor for in text messages and content. These help identify concerning content.",
        actionLabel = if (onAddWord != null) "Add Word" else null,
        onAction = onAddWord,
        modifier = modifier
    )
}

// ============================================================================
// ERROR STATES - For when something goes wrong
// ============================================================================

/**
 * Generic error state with retry option
 */
@Composable
fun ErrorState(
    title: String = "Something Went Wrong",
    message: String = "We couldn't load the data. Please try again.",
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(SafeGuardDimens.spacingXxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Outlined.ErrorOutline,
            contentDescription = null,
            modifier = Modifier.size(SafeGuardDimens.iconSizeHero),
            tint = SemanticColors.error.copy(alpha = 0.7f)
        )

        Spacer(modifier = Modifier.height(SafeGuardDimens.spacingXl))

        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(SafeGuardDimens.spacingSm))

        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (onRetry != null) {
            Spacer(modifier = Modifier.height(SafeGuardDimens.spacingXl))

            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(SafeGuardDimens.spacingSm))
                Text("Try Again")
            }
        }
    }
}

/**
 * Network error state
 */
@Composable
fun NetworkErrorState(
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null
) {
    ErrorState(
        title = "No Connection",
        message = "Please check your internet connection and try again.",
        modifier = modifier,
        onRetry = onRetry
    )
}

/**
 * Permission denied state
 */
@Composable
fun PermissionDeniedState(
    permissionName: String,
    modifier: Modifier = Modifier,
    onRequestPermission: (() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(SafeGuardDimens.spacingXxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Outlined.Lock,
            contentDescription = null,
            modifier = Modifier.size(SafeGuardDimens.iconSizeHero),
            tint = SemanticColors.warning.copy(alpha = 0.7f)
        )

        Spacer(modifier = Modifier.height(SafeGuardDimens.spacingXl))

        Text(
            text = "Permission Required",
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(SafeGuardDimens.spacingSm))

        Text(
            text = "SafeGuard needs $permissionName permission to work properly. Please grant access in settings.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (onRequestPermission != null) {
            Spacer(modifier = Modifier.height(SafeGuardDimens.spacingXl))

            Button(
                onClick = onRequestPermission,
                colors = ButtonDefaults.buttonColors(
                    containerColor = SemanticColors.warning
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(SafeGuardDimens.spacingSm))
                Text("Open Settings")
            }
        }
    }
}

// ============================================================================
// INLINE EMPTY/ERROR STATES - For smaller areas within screens
// ============================================================================

/**
 * Inline empty state for sections within a screen
 */
@Composable
fun InlineEmptyState(
    icon: ImageVector,
    message: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(SafeGuardDimens.paddingCard),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(SafeGuardDimens.iconSizeMd),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
            Spacer(modifier = Modifier.width(SafeGuardDimens.spacingMd))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Inline error state with retry
 */
@Composable
fun InlineErrorState(
    message: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = SemanticColors.errorContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(SafeGuardDimens.paddingCard),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                modifier = Modifier.size(SafeGuardDimens.iconSizeMd),
                tint = SemanticColors.error
            )
            Spacer(modifier = Modifier.width(SafeGuardDimens.spacingMd))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f)
            )
            if (onRetry != null) {
                TextButton(onClick = onRetry) {
                    Text("Retry")
                }
            }
        }
    }
}
