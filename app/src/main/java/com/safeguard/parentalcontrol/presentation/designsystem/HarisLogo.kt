package com.safeguard.parentalcontrol.presentation.designsystem

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.safeguard.parentalcontrol.R
import com.safeguard.parentalcontrol.presentation.theme.SafeGuardTheme

/**
 * Haris brand mark (the full-color logo from res/drawable/splash_logo.png).
 *
 * Use anywhere the app previously showed a generic shield as its "logo":
 * headers, dialogs, empty states. Rendered as-is — no tint, no clipping,
 * no background — so the artwork always matches the brand.
 */
@Composable
fun HarisLogo(
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
) {
    Image(
        painter = painterResource(id = R.drawable.splash_logo),
        contentDescription = stringResource(R.string.components_cd_haris_logo),
        contentScale = ContentScale.Fit,
        modifier = modifier.size(size)
    )
}

@Preview(name = "HarisLogo · Light", showBackground = true)
@Composable
private fun HarisLogoLightPreview() {
    SafeGuardTheme(darkTheme = false) { HarisLogo(size = 80.dp) }
}

@Preview(name = "HarisLogo · Dark", showBackground = true)
@Composable
private fun HarisLogoDarkPreview() {
    SafeGuardTheme(darkTheme = true) { HarisLogo(size = 80.dp) }
}
