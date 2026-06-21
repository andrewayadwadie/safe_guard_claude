package com.safeguard.parentalcontrol.presentation.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.safeguard.parentalcontrol.presentation.theme.*

// ============================================================================
// ANIMATED STAT DISPLAY - For dashboard statistics
// ============================================================================

/**
 * Animated statistic display with count-up animation
 */
@Composable
fun AnimatedStatDisplay(
    value: Int,
    label: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconColor: Color = MaterialTheme.colorScheme.primary,
    formatValue: (Int) -> String = { it.toString() },
    animationDuration: Int = SafeGuardAnimations.ANIMATION_DURATION_LONG
) {
    var animatedValue by remember { mutableIntStateOf(0) }

    LaunchedEffect(value) {
        animate(
            initialValue = animatedValue.toFloat(),
            targetValue = value.toFloat(),
            animationSpec = tween(
                durationMillis = animationDuration,
                easing = FastOutSlowInEasing
            )
        ) { animValue, _ ->
            animatedValue = animValue.toInt()
        }
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(SafeGuardDimens.iconSizeMd),
                tint = iconColor
            )
            Spacer(modifier = Modifier.height(SafeGuardDimens.spacingXs))
        }

        Text(
            text = formatValue(animatedValue),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = iconColor
        )

        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ============================================================================
// PRESS-SCALE CARD - Card with scale animation on press
// ============================================================================

/**
 * Card that scales down slightly when pressed for tactile feedback
 */
@Composable
fun PressableCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    scaleOnPress: Float = 0.97f,
    shape: RoundedCornerShape = RoundedCornerShape(16.dp),
    containerColor: Color = MaterialTheme.colorScheme.surface,
    elevation: Dp = SafeGuardDimens.elevationSm,
    contentDescription: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed && enabled) scaleOnPress else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessHigh
        ),
        label = "card_scale"
    )

    val animatedElevation by animateDpAsState(
        targetValue = if (isPressed && enabled) elevation / 2 else elevation,
        animationSpec = tween(durationMillis = 100),
        label = "card_elevation"
    )

    Card(
        onClick = {
            if (enabled) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            }
        },
        modifier = modifier
            .scale(scale)
            .semantics {
                contentDescription?.let { this.contentDescription = it }
                role = Role.Button
            },
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = animatedElevation),
        interactionSource = interactionSource,
        enabled = enabled,
        content = content
    )
}

// ============================================================================
// GRADIENT HERO CARD - For prominent dashboard sections
// ============================================================================

/**
 * Hero card with gradient background for prominent display
 */
@Composable
fun GradientHeroCard(
    gradientColors: List<Color>,
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(24.dp),
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                brush = Brush.linearGradient(colors = gradientColors)
            )
            .shadow(
                elevation = SafeGuardDimens.elevationMd,
                shape = shape,
                clip = false
            ),
        content = content
    )
}

// ============================================================================
// QUICK ACTION BUTTON - Rounded button for quick actions
// ============================================================================

/**
 * Circular quick action button with icon
 */
@Composable
fun QuickActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    size: Dp = 56.dp,
    showLabel: Boolean = true
) {
    val haptic = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.9f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessHigh),
        label = "button_scale"
    )

    Column(
        modifier = modifier
            .semantics {
                contentDescription = label
                role = Role.Button
            },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            },
            modifier = Modifier
                .size(size)
                .scale(scale),
            shape = CircleShape,
            color = containerColor,
            enabled = enabled,
            interactionSource = interactionSource,
            shadowElevation = if (isPressed) 0.dp else 4.dp
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(size * 0.45f),
                    tint = contentColor
                )
            }
        }

        if (showLabel) {
            Spacer(modifier = Modifier.height(SafeGuardDimens.spacingSm))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ============================================================================
// ANIMATED VISIBILITY CARD - Card that animates in/out
// ============================================================================

/**
 * Card with entrance/exit animation
 */
@Composable
fun AnimatedCard(
    visible: Boolean,
    modifier: Modifier = Modifier,
    enterTransition: EnterTransition = fadeIn() + slideInVertically { -it / 4 },
    exitTransition: ExitTransition = fadeOut() + slideOutVertically { -it / 4 },
    content: @Composable () -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = enterTransition,
        exit = exitTransition
    ) {
        content()
    }
}

// ============================================================================
// PROGRESS INDICATOR WITH LABEL - Enhanced progress display
// ============================================================================

/**
 * Linear progress indicator with animated label
 */
@Composable
fun LabeledProgressBar(
    progress: Float,
    label: String,
    modifier: Modifier = Modifier,
    progressColor: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    showPercentage: Boolean = true,
    animate: Boolean = true
) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = if (animate) {
            tween(durationMillis = SafeGuardAnimations.ANIMATION_DURATION_MEDIUM)
        } else {
            snap()
        },
        label = "progress"
    )

    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (showPercentage) {
                Text(
                    text = "${(animatedProgress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = progressColor
                )
            }
        }

        Spacer(modifier = Modifier.height(SafeGuardDimens.spacingXs))

        LinearProgressIndicator(
            progress = animatedProgress,
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp)),
            color = progressColor,
            trackColor = trackColor
        )
    }
}

// ============================================================================
// PULSING INDICATOR - For online/active status
// ============================================================================

/**
 * Pulsing dot indicator for online status
 */
@Composable
fun PulsingIndicator(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 12.dp,
    isPulsing: Boolean = true
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")

    val alpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isPulsing) 0.3f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )

    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isPulsing) 1.2f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )

    Box(
        modifier = modifier
            .size(size)
            .scale(scale)
            .clip(CircleShape)
            .background(color.copy(alpha = alpha))
    )
}

// ============================================================================
// STAGGERED LIST ITEM - For animated list entries
// ============================================================================

/**
 * List item that animates in with stagger effect
 */
@Composable
fun StaggeredListItem(
    index: Int,
    visible: Boolean,
    modifier: Modifier = Modifier,
    staggerDelay: Int = SafeGuardAnimations.STAGGER_DELAY_MS,
    content: @Composable () -> Unit
) {
    val delayMillis = index * staggerDelay

    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(
            animationSpec = tween(
                durationMillis = SafeGuardAnimations.ANIMATION_DURATION_MEDIUM,
                delayMillis = delayMillis
            )
        ) + slideInVertically(
            animationSpec = tween(
                durationMillis = SafeGuardAnimations.ANIMATION_DURATION_MEDIUM,
                delayMillis = delayMillis
            ),
            initialOffsetY = { it / 2 }
        ),
        exit = fadeOut(
            animationSpec = tween(durationMillis = SafeGuardAnimations.ANIMATION_DURATION_SHORT)
        )
    ) {
        content()
    }
}

// ============================================================================
// TOOLTIP WRAPPER - Accessibility-friendly tooltip
// ============================================================================

/**
 * Composable with tooltip on long press
 */
@Composable
fun WithTooltip(
    tooltipText: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    var showTooltip by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current

    Box(
        modifier = modifier
            .pointerInput(Unit) {
                detectTapGestures(
                    onLongPress = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        showTooltip = true
                    },
                    onPress = {
                        awaitRelease()
                        showTooltip = false
                    }
                )
            }
    ) {
        content()

        AnimatedVisibility(
            visible = showTooltip,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut()
        ) {
            Surface(
                modifier = Modifier
                    .padding(top = 48.dp)
                    .widthIn(max = 200.dp),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.inverseSurface,
                shadowElevation = 4.dp
            ) {
                Text(
                    text = tooltipText,
                    modifier = Modifier.padding(
                        horizontal = 12.dp,
                        vertical = 8.dp
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.inverseOnSurface
                )
            }
        }
    }
}

// ============================================================================
// COUNTDOWN DISPLAY - For time remaining
// ============================================================================

/**
 * Animated countdown display
 */
@Composable
fun CountdownDisplay(
    remainingSeconds: Int,
    modifier: Modifier = Modifier,
    warningThresholdMinutes: Int = 30,
    criticalThresholdMinutes: Int = 10,
    showIcon: Boolean = true
) {
    val hours = remainingSeconds / 3600
    val minutes = (remainingSeconds % 3600) / 60
    val seconds = remainingSeconds % 60

    val color = when {
        remainingSeconds <= criticalThresholdMinutes * 60 -> SemanticColors.screenTimeExceeded
        remainingSeconds <= warningThresholdMinutes * 60 -> SemanticColors.screenTimeWarning
        else -> SemanticColors.screenTimeNormal
    }

    val isLow = remainingSeconds <= criticalThresholdMinutes * 60

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (showIcon) {
            if (isLow) {
                PulsingIndicator(
                    color = color,
                    size = 10.dp,
                    isPulsing = true
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Timer,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = color
                )
            }
            Spacer(modifier = Modifier.width(SafeGuardDimens.spacingSm))
        }

        Text(
            text = when {
                hours > 0 -> String.format("%d:%02d:%02d", hours, minutes, seconds)
                else -> String.format("%02d:%02d", minutes, seconds)
            },
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}

// ============================================================================
// SEGMENTED CONTROL - iOS-style segmented button
// ============================================================================

/**
 * Segmented control for switching between options
 */
@Composable
fun SegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelectionChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier.padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            options.forEachIndexed { index, option ->
                val isSelected = index == selectedIndex

                Surface(
                    onClick = {
                        if (!isSelected) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSelectionChange(index)
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .semantics {
                            contentDescription = "$option, ${if (isSelected) "selected" else "not selected"}"
                            role = Role.Tab
                        },
                    shape = RoundedCornerShape(8.dp),
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.surface
                    } else {
                        Color.Transparent
                    },
                    shadowElevation = if (isSelected) 2.dp else 0.dp
                ) {
                    Text(
                        text = option,
                        modifier = Modifier.padding(
                            horizontal = 16.dp,
                            vertical = 10.dp
                        ),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

// ============================================================================
// ANIMATED ICON BUTTON - Button with scale animation
// ============================================================================

/**
 * Icon button with press animation
 */
@Composable
fun AnimatedIconButton(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentDescription: String? = null,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    val haptic = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.85f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessHigh),
        label = "icon_scale"
    )

    IconButton(
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onClick()
        },
        modifier = modifier
            .scale(scale)
            .semantics {
                contentDescription?.let { this.contentDescription = it }
            },
        enabled = enabled,
        interactionSource = interactionSource
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) tint else tint.copy(alpha = 0.38f)
        )
    }
}

// ============================================================================
// BADGE ICON - Icon with badge overlay
// ============================================================================

/**
 * Icon with badge for notifications/counts
 */
@Composable
fun BadgeIcon(
    icon: ImageVector,
    badgeCount: Int,
    modifier: Modifier = Modifier,
    iconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    badgeColor: Color = SemanticColors.error,
    showBadge: Boolean = badgeCount > 0,
    maxBadgeCount: Int = 99
) {
    Box(modifier = modifier) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint
        )

        if (showBadge) {
            Badge(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 4.dp, y = (-4).dp),
                containerColor = badgeColor
            ) {
                Text(
                    text = if (badgeCount > maxBadgeCount) "$maxBadgeCount+" else badgeCount.toString(),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}
