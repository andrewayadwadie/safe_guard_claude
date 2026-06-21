package com.safeguard.parentalcontrol.presentation.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.safeguard.parentalcontrol.presentation.theme.SafeGuardDimens

// ============================================================================
// LOADING STATES - Skeleton Loaders and Progress Indicators
// ============================================================================

/**
 * Full screen loading indicator with optional message
 */
@Composable
fun FullScreenLoading(
    modifier: Modifier = Modifier,
    message: String? = null
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(SafeGuardDimens.progressSizeMd),
                strokeWidth = 3.dp
            )
            message?.let {
                Spacer(modifier = Modifier.height(SafeGuardDimens.spacingLg))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Inline loading indicator for buttons and small areas
 */
@Composable
fun InlineLoading(
    modifier: Modifier = Modifier,
    size: Dp = SafeGuardDimens.progressSizeSm,
    strokeWidth: Dp = 2.dp,
    color: Color = MaterialTheme.colorScheme.primary
) {
    CircularProgressIndicator(
        modifier = modifier.size(size),
        strokeWidth = strokeWidth,
        color = color
    )
}

/**
 * Shimmer effect brush for skeleton loaders
 */
@Composable
fun shimmerBrush(
    targetValue: Float = 1000f,
    showShimmer: Boolean = true
): Brush {
    return if (showShimmer) {
        val shimmerColors = listOf(
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        )

        val transition = rememberInfiniteTransition(label = "shimmer")
        val translateAnimation by transition.animateFloat(
            initialValue = 0f,
            targetValue = targetValue,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = 1000,
                    easing = FastOutSlowInEasing
                ),
                repeatMode = RepeatMode.Restart
            ),
            label = "shimmer_translate"
        )

        Brush.linearGradient(
            colors = shimmerColors,
            start = Offset(translateAnimation - 200f, translateAnimation - 200f),
            end = Offset(translateAnimation, translateAnimation)
        )
    } else {
        Brush.linearGradient(
            colors = listOf(Color.Transparent, Color.Transparent)
        )
    }
}

/**
 * Skeleton box for loading placeholders
 */
@Composable
fun SkeletonBox(
    modifier: Modifier = Modifier,
    width: Dp = 100.dp,
    height: Dp = 20.dp,
    shape: RoundedCornerShape = RoundedCornerShape(4.dp)
) {
    Box(
        modifier = modifier
            .width(width)
            .height(height)
            .clip(shape)
            .background(shimmerBrush())
    )
}

/**
 * Skeleton circle for avatar/icon placeholders
 */
@Composable
fun SkeletonCircle(
    modifier: Modifier = Modifier,
    size: Dp = 40.dp
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(shimmerBrush())
    )
}

/**
 * Skeleton card for dashboard card loading state
 */
@Composable
fun SkeletonCard(
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(SafeGuardDimens.paddingCard)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                SkeletonCircle(size = 40.dp)
                Spacer(modifier = Modifier.width(SafeGuardDimens.spacingMd))
                Column {
                    SkeletonBox(width = 120.dp, height = 16.dp)
                    Spacer(modifier = Modifier.height(SafeGuardDimens.spacingSm))
                    SkeletonBox(width = 80.dp, height = 12.dp)
                }
            }
            Spacer(modifier = Modifier.height(SafeGuardDimens.spacingLg))
            SkeletonBox(
                width = Dp.Unspecified,
                height = 48.dp,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * Skeleton list item for app usage and alert items
 */
@Composable
fun SkeletonListItem(
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(SafeGuardDimens.paddingCardSmall),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SkeletonCircle(size = 40.dp)
            Spacer(modifier = Modifier.width(SafeGuardDimens.spacingMd))
            Column(modifier = Modifier.weight(1f)) {
                SkeletonBox(width = 140.dp, height = 16.dp)
                Spacer(modifier = Modifier.height(SafeGuardDimens.spacingXs))
                SkeletonBox(width = 100.dp, height = 12.dp)
            }
            SkeletonBox(width = 50.dp, height = 16.dp)
        }
    }
}

/**
 * Skeleton screen time card
 */
@Composable
fun SkeletonScreenTimeCard(
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(SafeGuardDimens.paddingCard),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            SkeletonBox(width = 120.dp, height = 16.dp)
            Spacer(modifier = Modifier.height(SafeGuardDimens.spacingLg))
            SkeletonCircle(size = 120.dp)
            Spacer(modifier = Modifier.height(SafeGuardDimens.spacingLg))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    SkeletonBox(width = 60.dp, height = 24.dp)
                    Spacer(modifier = Modifier.height(SafeGuardDimens.spacingXs))
                    SkeletonBox(width = 40.dp, height = 12.dp)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    SkeletonBox(width = 60.dp, height = 24.dp)
                    Spacer(modifier = Modifier.height(SafeGuardDimens.spacingXs))
                    SkeletonBox(width = 40.dp, height = 12.dp)
                }
            }
        }
    }
}

/**
 * Skeleton dashboard with multiple cards
 */
@Composable
fun SkeletonDashboard(
    modifier: Modifier = Modifier,
    isParent: Boolean = false
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(SafeGuardDimens.paddingScreen),
        verticalArrangement = Arrangement.spacedBy(SafeGuardDimens.spacingLg)
    ) {
        // Device selector skeleton for parents
        if (isParent) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(SafeGuardDimens.spacingSm)
            ) {
                repeat(3) {
                    SkeletonBox(
                        width = 100.dp,
                        height = 36.dp,
                        shape = RoundedCornerShape(18.dp)
                    )
                }
            }
        }

        // Screen time card skeleton
        SkeletonScreenTimeCard()

        // Section header skeleton
        SkeletonBox(width = 100.dp, height = 20.dp)

        // List items skeleton
        repeat(3) {
            SkeletonListItem()
        }
    }
}

/**
 * Pulsating loading dots
 */
@Composable
fun LoadingDots(
    modifier: Modifier = Modifier,
    dotSize: Dp = 8.dp,
    dotColor: Color = MaterialTheme.colorScheme.primary
) {
    val infiniteTransition = rememberInfiniteTransition(label = "dots")

    val dot1Alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(500),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot1"
    )

    val dot2Alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, delayMillis = 150),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot2"
    )

    val dot3Alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, delayMillis = 300),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot3"
    )

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(dotSize)
                .clip(CircleShape)
                .background(dotColor.copy(alpha = dot1Alpha))
        )
        Box(
            modifier = Modifier
                .size(dotSize)
                .clip(CircleShape)
                .background(dotColor.copy(alpha = dot2Alpha))
        )
        Box(
            modifier = Modifier
                .size(dotSize)
                .clip(CircleShape)
                .background(dotColor.copy(alpha = dot3Alpha))
        )
    }
}
