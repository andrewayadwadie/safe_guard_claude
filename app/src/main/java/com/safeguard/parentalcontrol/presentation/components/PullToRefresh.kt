package com.safeguard.parentalcontrol.presentation.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.safeguard.parentalcontrol.R
import com.safeguard.parentalcontrol.presentation.theme.SafeGuardDimens
import kotlinx.coroutines.launch

// ============================================================================
// PULL TO REFRESH - Simple implementation without experimental APIs
// ============================================================================

/**
 * Simple pull-to-refresh container
 * Uses a manual nested scroll approach compatible with all Material 3 versions
 */
@Composable
fun SafeGuardPullToRefresh(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    val refreshTriggerDistance = 80.dp
    val refreshTriggerPx = with(LocalDensity.current) { refreshTriggerDistance.toPx() }

    var pullOffset by remember { mutableFloatStateOf(0f) }
    val coroutineScope = rememberCoroutineScope()

    // Reset pull offset when refresh completes
    LaunchedEffect(isRefreshing) {
        if (!isRefreshing) {
            pullOffset = 0f
        }
    }

    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: NestedScrollSource): androidx.compose.ui.geometry.Offset {
                // When scrolling up while pulled down, reduce the pull offset
                if (pullOffset > 0 && available.y < 0) {
                    val consumed = available.y.coerceAtLeast(-pullOffset)
                    pullOffset += consumed
                    return androidx.compose.ui.geometry.Offset(0f, consumed)
                }
                return androidx.compose.ui.geometry.Offset.Zero
            }

            override fun onPostScroll(
                consumed: androidx.compose.ui.geometry.Offset,
                available: androidx.compose.ui.geometry.Offset,
                source: NestedScrollSource
            ): androidx.compose.ui.geometry.Offset {
                // When at top and pulling down, increase pull offset
                if (enabled && available.y > 0 && !isRefreshing) {
                    pullOffset = (pullOffset + available.y * 0.5f).coerceAtMost(refreshTriggerPx * 1.5f)
                    return androidx.compose.ui.geometry.Offset(0f, available.y)
                }
                return androidx.compose.ui.geometry.Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                // When fling ends, check if we should trigger refresh
                if (pullOffset >= refreshTriggerPx && !isRefreshing) {
                    onRefresh()
                } else if (!isRefreshing) {
                    coroutineScope.launch {
                        pullOffset = 0f
                    }
                }
                return Velocity.Zero
            }
        }
    }

    Box(
        modifier = modifier.nestedScroll(nestedScrollConnection)
    ) {
        // Content
        content()

        // Pull indicator
        val showIndicator = isRefreshing || pullOffset > 0
        if (showIndicator) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = SafeGuardDimens.spacingMd),
                contentAlignment = Alignment.TopCenter
            ) {
                Surface(
                    modifier = Modifier.size(40.dp),
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shadowElevation = 4.dp
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (isRefreshing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            val progress = (pullOffset / refreshTriggerPx).coerceIn(0f, 1f)
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.components_cd_pull_refresh),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .size(24.dp)
                                    .graphicsLayer {
                                        rotationZ = progress * 180f
                                        alpha = progress
                                    }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Refresh indicator that can be used standalone
 */
@Composable
fun RefreshingIndicator(
    isRefreshing: Boolean,
    modifier: Modifier = Modifier
) {
    if (isRefreshing) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .padding(SafeGuardDimens.spacingMd),
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(SafeGuardDimens.progressSizeSm),
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(SafeGuardDimens.spacingMd))
                Text(
                    text = stringResource(R.string.components_refreshing),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Rotating refresh icon
 */
@Composable
fun AnimatedRefreshIcon(
    isRefreshing: Boolean,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "refresh_rotation")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    Icon(
        imageVector = Icons.Default.Refresh,
        contentDescription = if (isRefreshing) "Refreshing" else "Refresh",
        modifier = modifier.graphicsLayer {
            rotationZ = if (isRefreshing) rotation else 0f
        }
    )
}

/**
 * Last updated timestamp display
 */
@Composable
fun LastUpdatedText(
    timestamp: String?,
    modifier: Modifier = Modifier,
    prefix: String = "Last updated:"
) {
    timestamp?.let {
        Text(
            text = "$prefix $it",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = modifier
        )
    }
}
