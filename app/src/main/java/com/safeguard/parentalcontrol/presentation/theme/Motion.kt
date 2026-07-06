package com.safeguard.parentalcontrol.presentation.theme

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

// ============================================================================
// MOTION — recomposition-safe. Animated values are read inside graphicsLayer
// / draw lambdas (deferred to draw phase), never as composition-time reads.
// ============================================================================

/**
 * Skeleton shimmer for loading placeholders. The animated offset is consumed
 * inside [drawWithCache]'s draw lambda, so the sweep does not recompose.
 */
fun Modifier.shimmerEffect(): Modifier = composed {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100, easing = androidx.compose.animation.core.LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer-progress"
    )
    val base = harisColors.surfaceContainerHighest
    val highlight = harisColors.surfaceContainerHigh
    drawWithCache {
        val widthPx = size.width
        val sweep = widthPx * 2
        val start = -sweep + (progress * (sweep * 2))
        val brush = Brush.linearGradient(
            colors = listOf(base, highlight, base),
            start = Offset(start, 0f),
            end = Offset(start + sweep, 0f)
        )
        onDrawBehind { drawRect(brush) }
    }
}

/**
 * Fade + subtle scale entrance. [visible] is a deferred lambda so the boolean
 * is read lazily; the animated alpha/scale are applied inside [graphicsLayer].
 */
fun Modifier.fadeScaleIn(visible: () -> Boolean): Modifier = composed {
    val target = if (visible()) 1f else 0f
    val a by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(SafeGuardAnimations.ANIMATION_DURATION_MEDIUM, easing = SafeGuardAnimations.EaseOutQuart),
        label = "fadeScaleIn"
    )
    graphicsLayer {
        alpha = a
        val scale = 0.96f + 0.04f * a
        scaleX = scale
        scaleY = scale
    }
}

/**
 * Staggered list-entrance offset for item at [index]. Returns a [fadeScaleIn]
 * keyed to a per-item delay of [SafeGuardAnimations.STAGGER_DELAY_MS].
 */
fun Modifier.staggeredEntrance(index: Int, visible: () -> Boolean): Modifier = composed {
    val delayMs = index * SafeGuardAnimations.STAGGER_DELAY_MS
    val target = if (visible()) 1f else 0f
    val a by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(
            durationMillis = SafeGuardAnimations.ANIMATION_DURATION_MEDIUM,
            delayMillis = delayMs,
            easing = SafeGuardAnimations.EaseOutQuart
        ),
        label = "staggered-$index"
    )
    graphicsLayer {
        alpha = a
        val scale = 0.96f + 0.04f * a
        scaleX = scale
        scaleY = scale
    }
}

/** Subtle press-scale for primary buttons; animated scale read in graphicsLayer. */
fun Modifier.pressScale(pressed: () -> Boolean): Modifier = composed {
    val a by animateFloatAsState(
        targetValue = if (pressed()) 0.96f else 1f,
        animationSpec = tween(SafeGuardAnimations.ANIMATION_DURATION_SHORT, easing = SafeGuardAnimations.EaseOutQuart),
        label = "pressScale"
    )
    graphicsLayer {
        scaleX = a
        scaleY = a
    }
}

@Preview(name = "Motion · Shimmer + fadeScaleIn", showBackground = true)
@Composable
private fun MotionPreview() {
    SafeGuardTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(SafeGuardDimens.screenPadding),
                verticalArrangement = Arrangement.spacedBy(SafeGuardDimens.stackMd)
            ) {
                Text("Shimmer skeleton", style = MaterialTheme.typography.labelMedium)
                Spacer(
                    Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .clip(SafeGuardShapes.large)
                        .shimmerEffect()
                )
                Text(
                    "fadeScaleIn target",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .fadeScaleIn { true }
                        .background(MaterialTheme.colorScheme.primaryContainer, SafeGuardShapes.medium)
                        .padding(SafeGuardDimens.gutter)
                        .size(width = 200.dp, height = 48.dp)
                )
            }
        }
    }
}
