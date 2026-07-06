package com.safeguard.parentalcontrol.presentation.splash

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.safeguard.parentalcontrol.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Minimal animated splash shown as the first screen on cold launch.
 *
 * Timeline (~1.2s): the logo eases in while scaling up slightly (450ms), holds
 * (300ms), then eases out (450ms), after which [onFinished] fires so navigation
 * can move to the real start screen. Easing curves keep the motion smooth.
 */
@Composable
fun SplashScreen(onFinished: () -> Unit) {
    val alpha = remember { Animatable(0f) }
    val scale = remember { Animatable(0.9f) }

    LaunchedEffect(Unit) {
        // Fade + gentle scale in.
        launch { scale.animateTo(1f, tween(durationMillis = 450, easing = FastOutSlowInEasing)) }
        alpha.animateTo(1f, tween(durationMillis = 450, easing = LinearOutSlowInEasing))
        delay(300)
        alpha.animateTo(0f, tween(durationMillis = 450, easing = FastOutSlowInEasing))
        onFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(id = R.drawable.splash_logo),
            contentDescription = null,
            modifier = Modifier
                .size(160.dp)
                .scale(scale.value)
                .alpha(alpha.value)
        )
    }
}
