package com.safeguard.parentalcontrol.presentation.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.safeguard.parentalcontrol.presentation.theme.*

// ============================================================================
// CIRCULAR PROGRESS DISPLAY - For screen time visualization
// ============================================================================

/**
 * Beautiful circular progress indicator for screen time display
 * Shows used time vs limit with animated progress and gradient colors
 */
@Composable
fun CircularScreenTimeProgress(
    usedMinutes: Int,
    limitMinutes: Int?,
    modifier: Modifier = Modifier,
    size: Dp = SafeGuardDimens.progressSizeXl,
    strokeWidth: Dp = 12.dp,
    animate: Boolean = true
) {
    val progress = if (limitMinutes != null && limitMinutes > 0) {
        (usedMinutes.toFloat() / limitMinutes).coerceIn(0f, 1.5f)
    } else {
        0f
    }

    // Animated progress value
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = if (animate) {
            tween(durationMillis = 1000, easing = FastOutSlowInEasing)
        } else {
            snap()
        },
        label = "progress"
    )

    // Color based on progress
    val progressColor = remember(progress) {
        when {
            progress >= 1f -> SemanticColors.screenTimeExceeded
            progress >= 0.8f -> SemanticColors.screenTimeWarning
            else -> SemanticColors.screenTimeNormal
        }
    }

    val backgroundColor = MaterialTheme.colorScheme.surfaceVariant

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        // Background track
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidthPx = strokeWidth.toPx()
            val radius = (this.size.minDimension - strokeWidthPx) / 2
            val center = Offset(this.size.width / 2, this.size.height / 2)

            // Background circle
            drawCircle(
                color = backgroundColor,
                radius = radius,
                center = center,
                style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round)
            )

            // Progress arc (only if there's a limit)
            if (limitMinutes != null && limitMinutes > 0) {
                val sweepAngle = animatedProgress * 360f

                drawArc(
                    color = progressColor,
                    startAngle = -90f,
                    sweepAngle = sweepAngle.coerceAtMost(360f),
                    useCenter = false,
                    topLeft = Offset(
                        center.x - radius,
                        center.y - radius
                    ),
                    size = Size(radius * 2, radius * 2),
                    style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round)
                )

                // Draw overflow indicator if exceeded
                if (animatedProgress > 1f) {
                    val overflowSweep = (animatedProgress - 1f) * 360f
                    drawArc(
                        color = SemanticColors.screenTimeExceeded.copy(alpha = 0.3f),
                        startAngle = -90f,
                        sweepAngle = overflowSweep.coerceAtMost(180f),
                        useCenter = false,
                        topLeft = Offset(
                            center.x - radius,
                            center.y - radius
                        ),
                        size = Size(radius * 2, radius * 2),
                        style = Stroke(width = strokeWidthPx / 2, cap = StrokeCap.Round)
                    )
                }
            }
        }

        // Center content
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = formatMinutesToTime(usedMinutes),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = progressColor
            )
            if (limitMinutes != null) {
                Text(
                    text = "of ${formatMinutesToTime(limitMinutes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    text = "No limit",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Gradient circular progress for a more premium look
 * Optimized with remember for expensive computations
 */
@Composable
fun GradientCircularProgress(
    progress: Float,
    modifier: Modifier = Modifier,
    size: Dp = SafeGuardDimens.progressSizeLg,
    strokeWidth: Dp = 10.dp,
    gradientColors: List<Color> = listOf(HarisPetrol, HarisPetrolLight),
    backgroundColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    animate: Boolean = true,
    showGlow: Boolean = true,
    centerContent: @Composable () -> Unit = {}
) {
    // Memoize the target progress to avoid unnecessary recomposition
    val targetProgress by remember(progress) {
        derivedStateOf { progress.coerceIn(0f, 1f) }
    }

    val animatedProgress by animateFloatAsState(
        targetValue = targetProgress,
        animationSpec = if (animate) {
            spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessLow
            )
        } else {
            snap()
        },
        label = "gradient_progress"
    )

    // Glow animation for high progress
    val infiniteTransition = rememberInfiniteTransition(label = "glow")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow_alpha"
    )

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidthPx = strokeWidth.toPx()
            val radius = (this.size.minDimension - strokeWidthPx) / 2
            val center = Offset(this.size.width / 2, this.size.height / 2)

            // Background circle with slight shadow effect
            drawCircle(
                color = backgroundColor,
                radius = radius,
                center = center,
                style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round)
            )

            // Glow effect for progress > 80%
            if (showGlow && animatedProgress > 0.8f) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            gradientColors.first().copy(alpha = glowAlpha * 0.3f),
                            Color.Transparent
                        ),
                        center = center,
                        radius = radius * 1.2f
                    ),
                    radius = radius * 1.1f,
                    center = center
                )
            }

            // Gradient progress arc
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
                style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round)
            )

            // Progress cap highlight
            if (animatedProgress > 0.02f) {
                val capAngle = Math.toRadians((-90f + sweepAngle).toDouble())
                val capX = center.x + (radius * kotlin.math.cos(capAngle)).toFloat()
                val capY = center.y + (radius * kotlin.math.sin(capAngle)).toFloat()

                drawCircle(
                    color = Color.White.copy(alpha = 0.5f),
                    radius = strokeWidthPx / 4,
                    center = Offset(capX, capY)
                )
            }
        }

        centerContent()
    }
}

/**
 * Compact circular progress for inline displays
 */
@Composable
fun CompactCircularProgress(
    progress: Float,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    strokeWidth: Dp = 4.dp,
    color: Color = MaterialTheme.colorScheme.primary,
    backgroundColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    showPercentage: Boolean = true
) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(500, easing = FastOutSlowInEasing),
        label = "compact_progress"
    )

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidthPx = strokeWidth.toPx()
            val radius = (this.size.minDimension - strokeWidthPx) / 2
            val center = Offset(this.size.width / 2, this.size.height / 2)

            drawCircle(
                color = backgroundColor,
                radius = radius,
                center = center,
                style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round)
            )

            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = animatedProgress * 360f,
                useCenter = false,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = Size(radius * 2, radius * 2),
                style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round)
            )
        }

        if (showPercentage) {
            Text(
                text = "${(animatedProgress * 100).toInt()}%",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = color
            )
        }
    }
}

/**
 * Semi-circular progress (180 degrees) - good for gauges
 */
@Composable
fun SemiCircularProgress(
    progress: Float,
    modifier: Modifier = Modifier,
    size: Dp = SafeGuardDimens.progressSizeLg,
    strokeWidth: Dp = 12.dp,
    progressColor: Color = HarisPetrol,
    backgroundColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    animate: Boolean = true,
    label: String? = null,
    value: String? = null
) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = if (animate) {
            tween(durationMillis = 1000, easing = FastOutSlowInEasing)
        } else {
            snap()
        },
        label = "semi_progress"
    )

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .width(size)
                .height(size / 2 + strokeWidth),
            contentAlignment = Alignment.BottomCenter
        ) {
            Canvas(
                modifier = Modifier
                    .width(size)
                    .height(size / 2 + strokeWidth)
            ) {
                val strokeWidthPx = strokeWidth.toPx()
                val radius = (this.size.width - strokeWidthPx) / 2
                val centerY = this.size.height

                // Background arc
                drawArc(
                    color = backgroundColor,
                    startAngle = 180f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(strokeWidthPx / 2, centerY - radius),
                    size = Size(radius * 2, radius * 2),
                    style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round)
                )

                // Progress arc
                val sweepAngle = animatedProgress * 180f
                drawArc(
                    color = progressColor,
                    startAngle = 180f,
                    sweepAngle = sweepAngle,
                    useCenter = false,
                    topLeft = Offset(strokeWidthPx / 2, centerY - radius),
                    size = Size(radius * 2, radius * 2),
                    style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round)
                )
            }

            // Value display at bottom
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                value?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = progressColor
                    )
                }
            }
        }

        label?.let {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Multiple ring progress indicator for showing multiple metrics
 */
@Composable
fun MultiRingProgress(
    rings: List<RingData>,
    modifier: Modifier = Modifier,
    size: Dp = SafeGuardDimens.progressSizeXl,
    strokeWidth: Dp = 8.dp,
    ringSpacing: Dp = 12.dp,
    animate: Boolean = true,
    centerContent: @Composable () -> Unit = {}
) {
    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        rings.forEachIndexed { index, ring ->
            val ringOffset = (strokeWidth + ringSpacing) * index
            val ringSize = size - (ringOffset * 2)

            if (ringSize.value > 0) {
                val animatedProgress by animateFloatAsState(
                    targetValue = ring.progress.coerceIn(0f, 1f),
                    animationSpec = if (animate) {
                        tween(
                            durationMillis = 1000,
                            delayMillis = index * 100,
                            easing = FastOutSlowInEasing
                        )
                    } else {
                        snap()
                    },
                    label = "ring_$index"
                )

                Canvas(
                    modifier = Modifier
                        .size(ringSize)
                        .align(Alignment.Center)
                ) {
                    val strokeWidthPx = strokeWidth.toPx()
                    val radius = (this.size.minDimension - strokeWidthPx) / 2
                    val center = Offset(this.size.width / 2, this.size.height / 2)

                    // Background
                    drawCircle(
                        color = ring.backgroundColor,
                        radius = radius,
                        center = center,
                        style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round)
                    )

                    // Progress
                    drawArc(
                        color = ring.color,
                        startAngle = -90f,
                        sweepAngle = animatedProgress * 360f,
                        useCenter = false,
                        topLeft = Offset(center.x - radius, center.y - radius),
                        size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2),
                        style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round)
                    )
                }
            }
        }

        centerContent()
    }
}

data class RingData(
    val progress: Float,
    val color: Color,
    val backgroundColor: Color = SemanticColors.progressBackground.copy(alpha = 0.3f),
    val label: String? = null
)

// ============================================================================
// HELPER FUNCTIONS
// ============================================================================

/**
 * Format minutes to human readable time string
 */
fun formatMinutesToTime(minutes: Int): String {
    val hours = minutes / 60
    val mins = minutes % 60

    return when {
        hours > 0 && mins > 0 -> "${hours}h ${mins}m"
        hours > 0 -> "${hours}h"
        else -> "${mins}m"
    }
}

/**
 * Format seconds to human readable time string
 */
fun formatSecondsToTime(seconds: Int): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60

    return when {
        hours > 0 && minutes > 0 -> "${hours}h ${minutes}m"
        hours > 0 -> "${hours}h"
        minutes > 0 -> "${minutes}m"
        else -> "<1m"
    }
}
