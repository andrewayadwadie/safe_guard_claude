package com.safeguard.parentalcontrol.presentation.theme

import android.app.Activity
import android.os.Build
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

// ============================================================================
// SAFEGUARD BRAND COLORS
// ============================================================================

// Primary Brand Colors
val SafeGuardBlue = Color(0xFF1976D2)
val SafeGuardBlueDark = Color(0xFF1565C0)
val SafeGuardBlueLight = Color(0xFF42A5F5)
val SafeGuardBlueVeryLight = Color(0xFFE3F2FD)

// Secondary Colors
val SafeGuardGreen = Color(0xFF4CAF50)
val SafeGuardGreenLight = Color(0xFF81C784)
val SafeGuardGreenDark = Color(0xFF388E3C)

// Accent/Tertiary Colors
val SafeGuardOrange = Color(0xFFFF9800)
val SafeGuardOrangeLight = Color(0xFFFFB74D)
val SafeGuardOrangeDark = Color(0xFFF57C00)

// Alert Colors
val SafeGuardRed = Color(0xFFF44336)
val SafeGuardRedLight = Color(0xFFEF9A9A)
val SafeGuardRedDark = Color(0xFFD32F2F)

// Neutral Colors
val SafeGuardGray50 = Color(0xFFFAFAFA)
val SafeGuardGray100 = Color(0xFFF5F5F5)
val SafeGuardGray200 = Color(0xFFEEEEEE)
val SafeGuardGray300 = Color(0xFFE0E0E0)
val SafeGuardGray400 = Color(0xFFBDBDBD)
val SafeGuardGray500 = Color(0xFF9E9E9E)
val SafeGuardGray600 = Color(0xFF757575)
val SafeGuardGray700 = Color(0xFF616161)
val SafeGuardGray800 = Color(0xFF424242)
val SafeGuardGray900 = Color(0xFF212121)

// Gradient Colors (for special UI elements)
val GradientStart = Color(0xFF1976D2)
val GradientEnd = Color(0xFF42A5F5)

// ============================================================================
// LIGHT THEME COLOR SCHEME
// ============================================================================

private val LightColorScheme = lightColorScheme(
    // Primary
    primary = SafeGuardBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD1E4FF),
    onPrimaryContainer = Color(0xFF001D36),
    inversePrimary = SafeGuardBlueLight,

    // Secondary
    secondary = SafeGuardGreen,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFB8F5B0),
    onSecondaryContainer = Color(0xFF002204),

    // Tertiary
    tertiary = SafeGuardOrange,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDDB5),
    onTertiaryContainer = Color(0xFF2A1700),

    // Error
    error = SafeGuardRed,
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),

    // Background
    background = Color(0xFFFDFCFF),
    onBackground = Color(0xFF1A1C1E),

    // Surface
    surface = Color.White,
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFF0F4F8),
    onSurfaceVariant = Color(0xFF43474E),
    surfaceTint = SafeGuardBlue,
    inverseSurface = Color(0xFF2F3033),
    inverseOnSurface = Color(0xFFF1F0F4),

    // Outline
    outline = Color(0xFF73777F),
    outlineVariant = Color(0xFFC3C6CF),

    // Scrim
    scrim = Color(0xFF000000)
)

// ============================================================================
// DARK THEME COLOR SCHEME
// ============================================================================

private val DarkColorScheme = darkColorScheme(
    // Primary
    primary = SafeGuardBlueLight,
    onPrimary = Color(0xFF003258),
    primaryContainer = SafeGuardBlueDark,
    onPrimaryContainer = Color(0xFFD1E4FF),
    inversePrimary = SafeGuardBlue,

    // Secondary
    secondary = SafeGuardGreenLight,
    onSecondary = Color(0xFF003A0A),
    secondaryContainer = SafeGuardGreenDark,
    onSecondaryContainer = Color(0xFFB8F5B0),

    // Tertiary
    tertiary = SafeGuardOrangeLight,
    onTertiary = Color(0xFF452B00),
    tertiaryContainer = SafeGuardOrangeDark,
    onTertiaryContainer = Color(0xFFFFDDB5),

    // Error
    error = SafeGuardRedLight,
    onError = Color(0xFF690005),
    errorContainer = SafeGuardRedDark,
    onErrorContainer = Color(0xFFFFDAD6),

    // Background
    background = Color(0xFF1A1C1E),
    onBackground = Color(0xFFE3E2E6),

    // Surface
    surface = Color(0xFF1A1C1E),
    onSurface = Color(0xFFE3E2E6),
    surfaceVariant = Color(0xFF2B2F33),
    onSurfaceVariant = Color(0xFFC3C6CF),
    surfaceTint = SafeGuardBlueLight,
    inverseSurface = Color(0xFFE3E2E6),
    inverseOnSurface = Color(0xFF2F3033),

    // Outline
    outline = Color(0xFF8D9199),
    outlineVariant = Color(0xFF43474E),

    // Scrim
    scrim = Color(0xFF000000)
)

// ============================================================================
// TYPOGRAPHY
// ============================================================================

private val SafeGuardTypography = Typography(
    // Display styles - for large, impactful text
    displayLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 57.sp,
        lineHeight = 64.sp,
        letterSpacing = (-0.25).sp
    ),
    displayMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 45.sp,
        lineHeight = 52.sp,
        letterSpacing = 0.sp
    ),
    displaySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 36.sp,
        lineHeight = 44.sp,
        letterSpacing = 0.sp
    ),

    // Headline styles - for section headers
    headlineLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = 0.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
        letterSpacing = 0.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = 0.sp
    ),

    // Title styles - for card titles, dialog titles
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),

    // Body styles - for main content
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.25.sp
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp
    ),

    // Label styles - for buttons, chips, tabs
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    )
)

// ============================================================================
// SHAPES
// ============================================================================

val SafeGuardShapes = Shapes(
    // Extra small - for chips, small badges
    extraSmall = RoundedCornerShape(4.dp),
    // Small - for buttons, text fields
    small = RoundedCornerShape(8.dp),
    // Medium - for cards, dialogs
    medium = RoundedCornerShape(12.dp),
    // Large - for bottom sheets, large cards
    large = RoundedCornerShape(16.dp),
    // Extra large - for full screen dialogs
    extraLarge = RoundedCornerShape(24.dp)
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
    val defaultSpring = spring<Float>(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessLow
    )

    val quickSpring = spring<Float>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMedium
    )

    val slowSpring = spring<Float>(
        dampingRatio = Spring.DampingRatioLowBouncy,
        stiffness = Spring.StiffnessVeryLow
    )

    val gentleSpring = spring<Float>(
        dampingRatio = Spring.DampingRatioHighBouncy,
        stiffness = Spring.StiffnessLow
    )

    val snappySpring = spring<Float>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessHigh
    )

    const val ANIMATION_DURATION_INSTANT = 100
    const val ANIMATION_DURATION_SHORT = 150
    const val ANIMATION_DURATION_MEDIUM = 300
    const val ANIMATION_DURATION_LONG = 500
    const val ANIMATION_DURATION_EXTRA_LONG = 800

    // Stagger delay for list item animations
    const val STAGGER_DELAY_MS = 50
}

// ============================================================================
// SEMANTIC COLORS (for specific use cases)
// ============================================================================

object SemanticColors {
    // Status colors
    val success = SafeGuardGreen
    val successContainer = Color(0xFFE8F5E9)
    val successOnContainer = Color(0xFF1B5E20)
    val warning = SafeGuardOrange
    val warningContainer = Color(0xFFFFF3E0)
    val warningOnContainer = Color(0xFFE65100)
    val error = SafeGuardRed
    val errorContainer = Color(0xFFFFEBEE)
    val errorOnContainer = Color(0xFFB71C1C)
    val info = SafeGuardBlue
    val infoContainer = Color(0xFFE3F2FD)
    val infoOnContainer = Color(0xFF0D47A1)

    // Alert severity colors
    val severityCritical = Color(0xFFD32F2F)
    val severityCriticalContainer = Color(0xFFFFCDD2)
    val severityHigh = Color(0xFFF57C00)
    val severityHighContainer = Color(0xFFFFE0B2)
    val severityMedium = Color(0xFF1976D2)
    val severityMediumContainer = Color(0xFFBBDEFB)
    val severityLow = Color(0xFF388E3C)
    val severityLowContainer = Color(0xFFC8E6C9)

    // Device status colors
    val statusOnline = SafeGuardGreen
    val statusOffline = SafeGuardGray400
    val statusSuspended = SafeGuardOrange

    // Screen time colors
    val screenTimeNormal = SafeGuardGreen
    val screenTimeWarning = SafeGuardOrange
    val screenTimeExceeded = SafeGuardRed

    // Progress colors
    val progressBackground = Color(0xFFE0E0E0)
    val progressTrack = SafeGuardBlueVeryLight

    // Child-friendly colors
    val childPrimary = Color(0xFF7C4DFF) // Purple
    val childSecondary = Color(0xFF00BCD4) // Cyan
    val childAccent = Color(0xFFFFD54F) // Amber
    val childBackground = Color(0xFFF3E5F5) // Light purple
    val childSuccess = Color(0xFF69F0AE) // Light green
    val childWarning = Color(0xFFFFAB40) // Light orange

    // Gradient presets
    val gradientPrimary = listOf(SafeGuardBlue, SafeGuardBlueLight)
    val gradientSuccess = listOf(SafeGuardGreen, SafeGuardGreenLight)
    val gradientWarning = listOf(SafeGuardOrange, SafeGuardOrangeLight)
    val gradientError = listOf(SafeGuardRed, SafeGuardRedLight)
    val gradientChild = listOf(Color(0xFF7C4DFF), Color(0xFF00BCD4))
}

// ============================================================================
// MAIN THEME COMPOSABLE
// ============================================================================

@Composable
fun SafeGuardTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
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
