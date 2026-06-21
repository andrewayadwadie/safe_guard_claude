package com.safeguard.parentalcontrol.presentation.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.safeguard.parentalcontrol.presentation.theme.*

// ============================================================================
// CHILD-FRIENDLY SCREEN TIME DISPLAY
// ============================================================================

/**
 * Fun, animated screen time display for children with friendly visual indicators
 */
@Composable
fun ChildScreenTimeCard(
    usedSeconds: Int,
    limitSeconds: Int?,
    unlockCount: Int,
    modifier: Modifier = Modifier
) {
    val usedMinutes = usedSeconds / 60
    val limitMinutes = limitSeconds?.let { it / 60 }

    val progress = if (limitMinutes != null && limitMinutes > 0) {
        (usedMinutes.toFloat() / limitMinutes).coerceIn(0f, 1f)
    } else {
        0f
    }

    val remainingMinutes = limitMinutes?.let { (it - usedMinutes).coerceAtLeast(0) }

    // Determine mood based on remaining time
    val (moodIcon, moodColor, moodText) = when {
        progress >= 0.9f -> Triple(Icons.Default.SentimentDissatisfied, SemanticColors.screenTimeExceeded, "Almost done for today!")
        progress >= 0.7f -> Triple(Icons.Default.SentimentNeutral, SemanticColors.screenTimeWarning, "Time is running low")
        else -> Triple(Icons.Default.SentimentSatisfied, SemanticColors.childSuccess, "You have lots of time!")
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = SemanticColors.childBackground
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = SafeGuardDimens.elevationMd)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(SafeGuardDimens.paddingCard),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Fun header with animated icon
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                AnimatedMoodIcon(
                    icon = moodIcon,
                    color = moodColor
                )
                Spacer(modifier = Modifier.width(SafeGuardDimens.spacingMd))
                Text(
                    text = "My Screen Time",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = SemanticColors.childPrimary
                )
            }

            Spacer(modifier = Modifier.height(SafeGuardDimens.spacingLg))

            // Fun circular progress with gradient
            FunCircularProgress(
                progress = progress,
                usedMinutes = usedMinutes,
                limitMinutes = limitMinutes,
                size = 160.dp
            )

            Spacer(modifier = Modifier.height(SafeGuardDimens.spacingMd))

            // Mood message
            Text(
                text = moodText,
                style = MaterialTheme.typography.bodyLarge,
                color = moodColor,
                fontWeight = FontWeight.Medium
            )

            Spacer(modifier = Modifier.height(SafeGuardDimens.spacingLg))

            // Stats row with fun icons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                FunStatItem(
                    icon = Icons.Default.PhoneAndroid,
                    value = unlockCount.toString(),
                    label = "Phone Opens",
                    color = SemanticColors.childSecondary
                )

                remainingMinutes?.let {
                    FunStatItem(
                        icon = Icons.Default.Timelapse,
                        value = formatFriendlyTime(it),
                        label = "Time Left",
                        color = moodColor
                    )
                }
            }
        }
    }
}

/**
 * Animated mood icon with bounce effect
 */
@Composable
private fun AnimatedMoodIcon(
    icon: ImageVector,
    color: Color
) {
    val infiniteTransition = rememberInfiniteTransition(label = "mood")

    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "mood_scale"
    )

    Icon(
        imageVector = icon,
        contentDescription = null,
        modifier = Modifier
            .size(32.dp)
            .scale(scale),
        tint = color
    )
}

/**
 * Fun circular progress with gradient and child-friendly styling
 */
@Composable
private fun FunCircularProgress(
    progress: Float,
    usedMinutes: Int,
    limitMinutes: Int?,
    size: Dp
) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(1000, easing = FastOutSlowInEasing),
        label = "progress"
    )

    val gradientColors = when {
        progress >= 0.9f -> SemanticColors.gradientError
        progress >= 0.7f -> SemanticColors.gradientWarning
        else -> SemanticColors.gradientChild
    }

    Box(
        modifier = Modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = 16.dp.toPx()
            val radius = (this.size.minDimension - strokeWidth) / 2
            val center = Offset(this.size.width / 2, this.size.height / 2)

            // Background track with fun dotted effect
            drawCircle(
                color = Color.White.copy(alpha = 0.5f),
                radius = radius,
                center = center,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )

            // Progress arc with gradient
            val sweepAngle = animatedProgress * 360f
            drawArc(
                brush = Brush.sweepGradient(
                    colors = gradientColors + gradientColors.first(),
                    center = center
                ),
                startAngle = -90f,
                sweepAngle = sweepAngle,
                useCenter = false,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = Size(radius * 2, radius * 2),
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
        }

        // Center content with large, friendly numbers
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = formatFriendlyTime(usedMinutes),
                style = MaterialTheme.typography.headlineLarge.copy(fontSize = 28.sp),
                fontWeight = FontWeight.Bold,
                color = SemanticColors.childPrimary
            )
            limitMinutes?.let {
                Text(
                    text = "of ${formatFriendlyTime(it)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Fun stat item with animated icon
 */
@Composable
private fun FunStatItem(
    icon: ImageVector,
    value: String,
    label: String,
    color: Color
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = color
            )
        }
        Spacer(modifier = Modifier.height(SafeGuardDimens.spacingXs))
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = color
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ============================================================================
// PERMISSION STATUS INDICATOR FOR CHILDREN
// ============================================================================

/**
 * Child-friendly permission status display
 */
@Composable
fun ChildPermissionStatus(
    allGranted: Boolean,
    grantedCount: Int,
    totalCount: Int,
    modifier: Modifier = Modifier,
    onSetupClick: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "status")

    val iconRotation by infiniteTransition.animateFloat(
        initialValue = -5f,
        targetValue = 5f,
        animationSpec = infiniteRepeatable(
            animation = tween(500),
            repeatMode = RepeatMode.Reverse
        ),
        label = "rotation"
    )

    Card(
        onClick = if (!allGranted) onSetupClick else { {} },
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (allGranted) {
                SemanticColors.successContainer
            } else {
                SemanticColors.warningContainer
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(SafeGuardDimens.paddingCard),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Animated status icon
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(
                        if (allGranted) SemanticColors.success.copy(alpha = 0.2f)
                        else SemanticColors.warning.copy(alpha = 0.2f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (allGranted) Icons.Default.Shield else Icons.Default.Warning,
                    contentDescription = null,
                    modifier = Modifier
                        .size(32.dp)
                        .rotate(if (allGranted) 0f else iconRotation),
                    tint = if (allGranted) SemanticColors.success else SemanticColors.warning
                )
            }

            Spacer(modifier = Modifier.width(SafeGuardDimens.spacingMd))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (allGranted) "All Set!" else "Setup Needed",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (allGranted) SemanticColors.success else SemanticColors.warning
                )
                Text(
                    text = if (allGranted) {
                        "Your parent can see your activity"
                    } else {
                        "$grantedCount of $totalCount permissions ready"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (!allGranted) {
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = "Setup",
                    tint = SemanticColors.warning
                )
            }
        }
    }
}

// ============================================================================
// FUN APP USAGE DISPLAY FOR CHILDREN
// ============================================================================

/**
 * Child-friendly app usage card with colorful styling
 */
@Composable
fun ChildAppUsageCard(
    appName: String,
    usageMinutes: Int,
    rank: Int,
    modifier: Modifier = Modifier
) {
    val rankColors = listOf(
        SemanticColors.childAccent, // Gold for #1
        Color(0xFFC0C0C0),          // Silver for #2
        Color(0xFFCD7F32),          // Bronze for #3
        SemanticColors.childSecondary,
        SemanticColors.childPrimary
    )

    val color = rankColors.getOrElse(rank - 1) { SemanticColors.childPrimary }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
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
            // Rank badge
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "#$rank",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = color
                )
            }

            Spacer(modifier = Modifier.width(SafeGuardDimens.spacingMd))

            // App info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = appName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
                Text(
                    text = formatFriendlyTime(usageMinutes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Time indicator
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(color.copy(alpha = 0.1f))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "${usageMinutes}m",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = color
                )
            }
        }
    }
}

// ============================================================================
// ENCOURAGEMENT BANNER
// ============================================================================

/**
 * Positive encouragement banner for children
 */
@Composable
fun EncouragementBanner(
    message: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Default.Stars
) {
    val infiniteTransition = rememberInfiniteTransition(label = "encouragement")

    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "banner_scale"
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = SemanticColors.childAccent.copy(alpha = 0.2f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(SafeGuardDimens.paddingCard),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = SemanticColors.childAccent
            )
            Spacer(modifier = Modifier.width(SafeGuardDimens.spacingMd))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

// ============================================================================
// HELPER FUNCTIONS
// ============================================================================

/**
 * Format time in a child-friendly way
 */
private fun formatFriendlyTime(minutes: Int): String {
    val hours = minutes / 60
    val mins = minutes % 60

    return when {
        hours > 0 && mins > 0 -> "${hours}h ${mins}m"
        hours > 0 -> "${hours}h"
        mins > 0 -> "${mins}m"
        else -> "0m"
    }
}
