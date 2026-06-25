package com.safeguard.parentalcontrol.presentation.theme

import android.app.Activity
import android.os.Build
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.safeguard.parentalcontrol.R

// ============================================================================
// SAFEGUARD BRAND FONTS — "Warm Hearth" (see DESIGN.md)
// Bundled OFL fonts. Drop the .ttf files in res/font/ (see FONTS.md):
//   Display:  Bricolage Grotesque (SemiBold 600, Bold 700)
//   Text/UI:  Hanken Grotesk      (Regular 400, Medium 500, SemiBold 600, Bold 700)
// The No-Roboto Rule: FontFamily.Default must not appear in the product.
// ============================================================================

val BricolageGrotesque = FontFamily(
    Font(R.font.bricolage_grotesque_semibold, FontWeight.SemiBold),
    Font(R.font.bricolage_grotesque_bold, FontWeight.Bold)
)

val HankenGrotesk = FontFamily(
    Font(R.font.hanken_grotesk_regular, FontWeight.Normal),
    Font(R.font.hanken_grotesk_medium, FontWeight.Medium),
    Font(R.font.hanken_grotesk_semibold, FontWeight.SemiBold),
    Font(R.font.hanken_grotesk_bold, FontWeight.Bold)
)

// ============================================================================
// SAFEGUARD BRAND COLORS — "Warm Hearth" palette (see DESIGN.md)
// Public token names are preserved so existing call sites keep compiling;
// only the values change. Deep Pine anchor + reserved Ember Amber warmth on
// warm-tinted Linen neutrals. No textbook blue, no pure black/white.
// ============================================================================

// Primary Brand Colors — Deep Pine family (was textbook blue)
val SafeGuardBlue = Color(0xFF2D5A4C)        // Deep Pine — calm, safe, capable anchor
val SafeGuardBlueDark = Color(0xFF224A3E)    // Pine Strong — pressed / deep header fills
val SafeGuardBlueLight = Color(0xFF8FCBB8)   // Pine Light — dark-mode primary, mint-sage
val SafeGuardBlueVeryLight = Color(0xFFCDE5DC) // Pine Container — soft sage tint

// Secondary Colors — Meadow family (positive / healthy / online)
val SafeGuardGreen = Color(0xFF4F9E6A)       // Meadow — success, online, within-limit
val SafeGuardGreenLight = Color(0xFF79BB90)  // light meadow — dark-mode secondary
val SafeGuardGreenDark = Color(0xFF3C855B)

// Accent/Tertiary Colors — Ember Amber family (reserved hearth warmth)
val SafeGuardOrange = Color(0xFFE5A84C)      // Ember Amber — warmth, welcome, accent
val SafeGuardOrangeLight = Color(0xFFEFC07E) // light ember — dark-mode tertiary
val SafeGuardOrangeDark = Color(0xFFD9912F)  // Ember Strong

// Alert Colors — Signal Coral family (was fire-engine red)
val SafeGuardRed = Color(0xFFC24A3A)         // Signal Coral — serious, warm, reserved
val SafeGuardRedLight = Color(0xFFD5786B)    // light coral — dark-mode error
val SafeGuardRedDark = Color(0xFFA93B2E)

// Neutral Colors — Warm Linen → Bark ramp (every neutral tinted warm; no #000/#fff)
val SafeGuardGray50 = Color(0xFFF7F3EC)      // Warm Linen — app background
val SafeGuardGray100 = Color(0xFFEFE9DE)     // Oat — lowered surfaces
val SafeGuardGray200 = Color(0xFFE7E0D3)
val SafeGuardGray300 = Color(0xFFDED6C8)     // Sand — borders, dividers
val SafeGuardGray400 = Color(0xFFC7BEB0)     // offline / muted
val SafeGuardGray500 = Color(0xFF9C9286)
val SafeGuardGray600 = Color(0xFF6B6359)     // Stone — secondary text
val SafeGuardGray700 = Color(0xFF544E45)
val SafeGuardGray800 = Color(0xFF3C372F)
val SafeGuardGray900 = Color(0xFF2A2622)     // Bark — primary text, warm near-black

// Gradient Colors — within-hue pine depth (was blue → light blue)
val GradientStart = Color(0xFF2D5A4C)        // Deep Pine
val GradientEnd = Color(0xFF3F7666)          // mid pine

// ============================================================================
// LIGHT THEME COLOR SCHEME
// ============================================================================

private val LightColorScheme = lightColorScheme(
    // Primary — Deep Pine
    primary = SafeGuardBlue,
    onPrimary = Color(0xFFFCFAF5),               // Cream
    primaryContainer = SafeGuardBlueVeryLight,   // Pine Container
    onPrimaryContainer = Color(0xFF15291F),      // deep pine
    inversePrimary = SafeGuardBlueLight,

    // Secondary — Meadow (dark on-content: Meadow is mid-tone, white fails AA)
    secondary = SafeGuardGreen,
    onSecondary = Color(0xFF14331F),
    secondaryContainer = Color(0xFFD7EBDD),
    onSecondaryContainer = Color(0xFF163826),

    // Tertiary — Ember Amber (warm accent; needs dark text)
    tertiary = SafeGuardOrange,
    onTertiary = Color(0xFF2A2622),              // Bark
    tertiaryContainer = Color(0xFFFBE6C8),
    onTertiaryContainer = Color(0xFF5C3D10),

    // Error — Signal Coral
    error = SafeGuardRed,
    onError = Color(0xFFFCFAF5),
    errorContainer = Color(0xFFF6D8D1),
    onErrorContainer = Color(0xFF451511),

    // Background — Warm Linen
    background = Color(0xFFF7F3EC),
    onBackground = Color(0xFF2A2622),            // Bark

    // Surface — Cream
    surface = Color(0xFFFCFAF5),
    onSurface = Color(0xFF2A2622),
    surfaceVariant = Color(0xFFEFE9DE),          // Oat
    onSurfaceVariant = Color(0xFF6B6359),        // Stone
    surfaceTint = SafeGuardBlue,
    inverseSurface = Color(0xFF2A2622),
    inverseOnSurface = Color(0xFFF7F3EC),

    // Outline — warm
    outline = Color(0xFF9C9286),
    outlineVariant = Color(0xFFDED6C8),          // Sand

    // Scrim — warm dark (not pure black)
    scrim = Color(0xFF2A2622)
)

// ============================================================================
// DARK THEME COLOR SCHEME
// ============================================================================

private val DarkColorScheme = darkColorScheme(
    // Primary — Pine Light on warm char
    primary = SafeGuardBlueLight,
    onPrimary = Color(0xFF15291F),
    primaryContainer = SafeGuardBlueDark,        // Pine Strong
    onPrimaryContainer = SafeGuardBlueVeryLight, // Pine Container
    inversePrimary = SafeGuardBlue,

    // Secondary — light Meadow
    secondary = SafeGuardGreenLight,
    onSecondary = Color(0xFF163826),
    secondaryContainer = Color(0xFF2C6B49),
    onSecondaryContainer = Color(0xFFD7EBDD),

    // Tertiary — light Ember
    tertiary = SafeGuardOrangeLight,
    onTertiary = Color(0xFF5C3D10),
    tertiaryContainer = Color(0xFFA8721F),
    onTertiaryContainer = Color(0xFFFBE6C8),

    // Error — light Coral
    error = SafeGuardRedLight,
    onError = Color(0xFF451511),
    errorContainer = SafeGuardRedDark,
    onErrorContainer = Color(0xFFF6D8D1),

    // Background — Warm Char (not pure black)
    background = Color(0xFF1E1B18),
    onBackground = Color(0xFFEDE7DD),            // Linen Light

    // Surface — elevation by lighter warm surfaces, not heavier shadow
    surface = Color(0xFF1E1B18),
    onSurface = Color(0xFFEDE7DD),
    surfaceVariant = Color(0xFF28241F),          // Umber
    onSurfaceVariant = Color(0xFFC7BEB0),
    surfaceTint = SafeGuardBlueLight,
    inverseSurface = Color(0xFFEDE7DD),
    inverseOnSurface = Color(0xFF2A2622),

    // Outline — warm
    outline = Color(0xFF9C9286),
    outlineVariant = Color(0xFF3C372F),

    // Scrim — deep warm
    scrim = Color(0xFF14110F)
)

// ============================================================================
// TYPOGRAPHY
// ============================================================================

// Display & Headline use Bricolage Grotesque; Title/Body/Label use Hanken Grotesk.
// Sizes and line-heights are unchanged from the prior scale to avoid layout
// regressions; only the families (and tightened display tracking) change.
private val SafeGuardTypography = Typography(
    // Display styles - Bricolage Grotesque, for large, impactful text
    displayLarge = TextStyle(
        fontFamily = BricolageGrotesque,
        fontWeight = FontWeight.Bold,
        fontSize = 57.sp,
        lineHeight = 64.sp,
        letterSpacing = (-0.5).sp
    ),
    displayMedium = TextStyle(
        fontFamily = BricolageGrotesque,
        fontWeight = FontWeight.Bold,
        fontSize = 45.sp,
        lineHeight = 52.sp,
        letterSpacing = (-0.25).sp
    ),
    displaySmall = TextStyle(
        fontFamily = BricolageGrotesque,
        fontWeight = FontWeight.SemiBold,
        fontSize = 36.sp,
        lineHeight = 44.sp,
        letterSpacing = 0.sp
    ),

    // Headline styles - Bricolage Grotesque, for section headers
    headlineLarge = TextStyle(
        fontFamily = BricolageGrotesque,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.25).sp
    ),
    headlineMedium = TextStyle(
        fontFamily = BricolageGrotesque,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
        letterSpacing = 0.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = BricolageGrotesque,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = 0.sp
    ),

    // Title styles - Hanken Grotesk, for card titles, dialog titles
    titleLarge = TextStyle(
        fontFamily = HankenGrotesk,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),
    titleMedium = TextStyle(
        fontFamily = HankenGrotesk,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp
    ),
    titleSmall = TextStyle(
        fontFamily = HankenGrotesk,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),

    // Body styles - Hanken Grotesk, for main content
    bodyLarge = TextStyle(
        fontFamily = HankenGrotesk,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = HankenGrotesk,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp
    ),
    bodySmall = TextStyle(
        fontFamily = HankenGrotesk,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.2.sp
    ),

    // Label styles - Hanken Grotesk, for buttons, chips, tabs
    labelLarge = TextStyle(
        fontFamily = HankenGrotesk,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.4.sp
    ),
    labelMedium = TextStyle(
        fontFamily = HankenGrotesk,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp
    ),
    labelSmall = TextStyle(
        fontFamily = HankenGrotesk,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp
    )
)

// ============================================================================
// SHAPES
// ============================================================================

// Warm Hearth uses generous, soft rounding (DESIGN.md rounded scale).
val SafeGuardShapes = Shapes(
    // Extra small - for chips, small badges
    extraSmall = RoundedCornerShape(6.dp),
    // Small - for buttons, text fields
    small = RoundedCornerShape(10.dp),
    // Medium - for cards, dialogs
    medium = RoundedCornerShape(14.dp),
    // Large - for bottom sheets, large cards
    large = RoundedCornerShape(20.dp),
    // Extra large - for full screen dialogs / child surfaces
    extraLarge = RoundedCornerShape(28.dp)
)

// ============================================================================
// CUSTOM DIMENSIONS
// ============================================================================

object SafeGuardDimens {
    // Spacing
    val spacingXxs = 2.dp
    val spacingXs = 4.dp
    val spacingSm = 8.dp
    val spacingMd = 12.dp
    val spacingLg = 16.dp
    val spacingXl = 24.dp
    val spacingXxl = 32.dp
    val spacingXxxl = 48.dp

    // Padding
    val paddingScreen = 16.dp
    val paddingCard = 16.dp
    val paddingCardSmall = 12.dp

    // Icon sizes
    val iconSizeXs = 16.dp
    val iconSizeSm = 20.dp
    val iconSizeMd = 24.dp
    val iconSizeLg = 32.dp
    val iconSizeXl = 48.dp
    val iconSizeXxl = 64.dp
    val iconSizeHero = 80.dp

    // Button heights
    val buttonHeightSmall = 36.dp
    val buttonHeightMedium = 48.dp
    val buttonHeightLarge = 56.dp

    // Card elevations
    val elevationNone = 0.dp
    val elevationSm = 2.dp
    val elevationMd = 4.dp
    val elevationLg = 8.dp

    // Minimum touch target (accessibility)
    val minTouchTarget = 48.dp

    // Avatar sizes
    val avatarSizeSm = 32.dp
    val avatarSizeMd = 40.dp
    val avatarSizeLg = 56.dp
    val avatarSizeXl = 80.dp

    // Progress indicator sizes
    val progressSizeSm = 24.dp
    val progressSizeMd = 48.dp
    val progressSizeLg = 80.dp
    val progressSizeXl = 120.dp
}

// ============================================================================
// ANIMATION SPECS
// ============================================================================

object SafeGuardAnimations {
    // Warm Hearth motion: calm ease-out, no bounce, no elastic, no overshoot.
    // The springs below are kept (call sites depend on them) but de-bounced.
    val defaultSpring = spring<Float>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow
    )

    val quickSpring = spring<Float>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMedium
    )

    val slowSpring = spring<Float>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessVeryLow
    )

    val gentleSpring = spring<Float>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessLow
    )

    val snappySpring = spring<Float>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessHigh
    )

    // Preferred easings for tween-based animation (DESIGN.md motion tokens).
    val EaseOutQuart = CubicBezierEasing(0.165f, 0.84f, 0.44f, 1f)   // entrances, state changes
    val EaseInOutSoft = CubicBezierEasing(0.45f, 0f, 0.2f, 1f)       // morph / travel

    // Durations stay snappy: nothing entering exceeds ~360ms; exits stay shorter.
    const val ANIMATION_DURATION_INSTANT = 100
    const val ANIMATION_DURATION_SHORT = 160
    const val ANIMATION_DURATION_MEDIUM = 240
    const val ANIMATION_DURATION_LONG = 360
    const val ANIMATION_DURATION_EXTRA_LONG = 500

    // Stagger delay for list item animations
    const val STAGGER_DELAY_MS = 50
}

// ============================================================================
// SEMANTIC COLORS (for specific use cases)
// ============================================================================

object SemanticColors {
    // Status colors — reserved, warm (DESIGN.md status palette)
    val success = SafeGuardGreen              // Meadow
    val successContainer = Color(0xFFD7EBDD)
    val successOnContainer = Color(0xFF163826)
    val warning = Color(0xFFCC6B2C)           // Amber Signal (more orange than the Ember accent)
    val warningContainer = Color(0xFFF8E0C9)
    val warningOnContainer = Color(0xFF4A2208)
    val error = SafeGuardRed                  // Signal Coral
    val errorContainer = Color(0xFFF6D8D1)
    val errorOnContainer = Color(0xFF451511)
    val info = SafeGuardBlue                  // Deep Pine
    val infoContainer = SafeGuardBlueVeryLight
    val infoOnContainer = Color(0xFF15291F)

    // Alert severity colors: critical→Coral, high→Amber Signal, medium→Pine, low→Meadow
    val severityCritical = Color(0xFFC24A3A)
    val severityCriticalContainer = Color(0xFFF6D8D1)
    val severityHigh = Color(0xFFCC6B2C)
    val severityHighContainer = Color(0xFFF8E0C9)
    val severityMedium = Color(0xFF2D5A4C)
    val severityMediumContainer = Color(0xFFCDE5DC)
    val severityLow = Color(0xFF4F9E6A)
    val severityLowContainer = Color(0xFFD7EBDD)

    // Device status colors
    val statusOnline = SafeGuardGreen         // Meadow
    val statusOffline = SafeGuardGray400      // warm muted
    val statusSuspended = Color(0xFFCC6B2C)   // Amber Signal

    // Screen time colors
    val screenTimeNormal = SafeGuardGreen          // Meadow
    val screenTimeWarning = Color(0xFFCC6B2C)      // Amber Signal
    val screenTimeExceeded = SafeGuardRed          // Signal Coral

    // Progress colors
    val progressBackground = Color(0xFFDED6C8)     // Sand
    val progressTrack = SafeGuardBlueVeryLight     // Pine Container

    // Child surfaces: warm dialect (no purple/cyan) — Clay + Meadow + Ember
    val childPrimary = Color(0xFFC7613F)   // Warm Clay
    val childSecondary = Color(0xFF4F9E6A) // Meadow
    val childAccent = Color(0xFFE5A84C)    // Ember Amber
    val childBackground = Color(0xFFF7DCCF) // Clay Container
    val childSuccess = Color(0xFF79BB90)   // light Meadow
    val childWarning = Color(0xFFEFC07E)   // light Ember

    // Gradient presets — within-hue, warm (no blue→light-blue)
    val gradientPrimary = listOf(SafeGuardBlue, Color(0xFF3F7666))      // pine depth
    val gradientSuccess = listOf(SafeGuardGreen, SafeGuardGreenLight)   // meadow
    val gradientWarning = listOf(Color(0xFFCC6B2C), Color(0xFFDD8F5A))  // amber signal
    val gradientError = listOf(SafeGuardRed, SafeGuardRedLight)         // coral
    val gradientChild = listOf(Color(0xFFC7613F), Color(0xFFE5A84C))    // clay → ember
}

// ============================================================================
// MAIN THEME COMPOSABLE
// ============================================================================

@Composable
fun SafeGuardTheme(
    // Dark mode is disabled: the app ships light-only (Warm Hearth) until the dark
    // palette is properly tuned. Pinned to false so it ignores the system setting.
    darkTheme: Boolean = false,
    dynamicColor: Boolean = false, // Disabled by default for consistent branding
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // Use surface color for status bar for a cleaner look
            window.statusBarColor = colorScheme.surface.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = SafeGuardTypography,
        shapes = SafeGuardShapes,
        content = content
    )
}

// ============================================================================
// EXTENSION FUNCTIONS FOR THEME
// ============================================================================

/**
 * Get the appropriate color for screen time progress
 */
fun getScreenTimeColor(usedMinutes: Int, limitMinutes: Int?): Color {
    if (limitMinutes == null) return SemanticColors.screenTimeNormal

    val percentage = (usedMinutes.toFloat() / limitMinutes) * 100
    return when {
        percentage >= 100 -> SemanticColors.screenTimeExceeded
        percentage >= 80 -> SemanticColors.screenTimeWarning
        else -> SemanticColors.screenTimeNormal
    }
}

/**
 * Get color for alert severity
 */
fun getAlertSeverityColor(severity: String): Color {
    return when (severity.uppercase()) {
        "CRITICAL" -> SemanticColors.severityCritical
        "HIGH" -> SemanticColors.severityHigh
        "MEDIUM" -> SemanticColors.severityMedium
        "LOW" -> SemanticColors.severityLow
        else -> SemanticColors.info
    }
}

/**
 * Get color for device status
 */
fun getDeviceStatusColor(status: String): Color {
    return when (status.uppercase()) {
        "ACTIVE" -> SemanticColors.statusOnline
        "SUSPENDED" -> SemanticColors.statusSuspended
        "INACTIVE" -> SemanticColors.statusOffline
        else -> SemanticColors.statusOffline
    }
}

/**
 * Get container color for alert severity
 */
fun getAlertSeverityContainerColor(severity: String): Color {
    return when (severity.uppercase()) {
        "CRITICAL" -> SemanticColors.severityCriticalContainer
        "HIGH" -> SemanticColors.severityHighContainer
        "MEDIUM" -> SemanticColors.severityMediumContainer
        "LOW" -> SemanticColors.severityLowContainer
        else -> SemanticColors.infoContainer
    }
}

/**
 * Get gradient colors for screen time based on usage percentage
 */
fun getScreenTimeGradient(usedMinutes: Int, limitMinutes: Int?): List<Color> {
    if (limitMinutes == null) return SemanticColors.gradientSuccess

    val percentage = (usedMinutes.toFloat() / limitMinutes) * 100
    return when {
        percentage >= 100 -> SemanticColors.gradientError
        percentage >= 80 -> SemanticColors.gradientWarning
        else -> SemanticColors.gradientSuccess
    }
}
