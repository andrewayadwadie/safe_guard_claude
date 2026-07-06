package com.safeguard.parentalcontrol.presentation.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.safeguard.parentalcontrol.R

// ============================================================================
// HARIS BRAND COLORS — petrol/teal protective ring + gold family mark.
// Public token names preserved where call sites depend on them; brand vals
// renamed SafeGuard* -> Haris*. Depth comes from tonal surface stepping, not
// heavy shadows. DARK is the canonical brand design.
// ============================================================================

// Primary brand — Petrol/Teal family
val HarisPetrol = Color(0xFF168BB6)       // primary (light)
val HarisPetrolDark = Color(0xFF0E6A86)   // gradient start, pressed/deep fills
val HarisPetrolLight = Color(0xFFA4DFF4)  // primary (dark)

// Tertiary brand — Aqua family
val HarisAqua = Color(0xFF2799A5)         // tertiary (light), gradient end
val HarisAquaLight = Color(0xFFACE5EC)    // tertiary (dark)

// Secondary/accent brand — Gold family (solid accent only — never in gradient)
val HarisGold = Color(0xFFC8941E)         // solid gold accent
val HarisGoldLight = Color(0xFFF1DCA7)    // secondary (dark)
val HarisGoldDark = Color(0xFFAF861D)     // secondary (light)

// Brand gradient stops (petrol -> aqua). Gold never appears here.
val GradientStart = Color(0xFF0E6A86)
val GradientEnd = Color(0xFF2799A5)

// ============================================================================
// LIGHT THEME COLOR SCHEME
// ============================================================================

private val LightColorScheme = lightColorScheme(
    primary = HarisPetrol,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFC9ECF8),
    onPrimaryContainer = Color(0xFF072A36),
    inversePrimary = Color(0xFFA4DFF4),

    secondary = HarisGoldDark,
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF6EACA),
    onSecondaryContainer = Color(0xFF352809),

    tertiary = HarisAqua,
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFCDF0F3),
    onTertiaryContainer = Color(0xFF0C2E32),

    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),

    background = Color(0xFFFAFCFC),
    onBackground = Color(0xFF1C2022),

    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1C2022),
    surfaceVariant = Color(0xFFDCE3E5),
    onSurfaceVariant = Color(0xFF3C4E53),
    surfaceTint = HarisPetrol,
    inverseSurface = Color(0xFF2E3538),
    inverseOnSurface = Color(0xFFF1F3F4),
    outline = Color(0xFF6B8C94),
    outlineVariant = Color(0xFFC4D1D4),
    scrim = Color(0xFF000000)
)

// ============================================================================
// DARK THEME COLOR SCHEME — canonical brand design
// ============================================================================

private val DarkColorScheme = darkColorScheme(
    primary = HarisPetrolLight,
    onPrimary = Color(0xFF0B465B),
    primaryContainer = Color(0xFF10617F),
    onPrimaryContainer = Color(0xFFC9ECF8),
    inversePrimary = Color(0xFF168BB6),

    secondary = HarisGoldLight,
    onSecondary = Color(0xFF58430E),
    secondaryContainer = Color(0xFF7B5E14),
    onSecondaryContainer = Color(0xFFF6EACA),

    tertiary = HarisAquaLight,
    onTertiary = Color(0xFF134C53),
    tertiaryContainer = Color(0xFF1B6B74),
    onTertiaryContainer = Color(0xFFCDF0F3),

    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),

    background = Color(0xFF0F1719),
    onBackground = Color(0xFFDDE1E3),

    surface = Color(0xFF0F1719),
    onSurface = Color(0xFFDDE1E3),
    surfaceVariant = Color(0xFF3C4E53),
    onSurfaceVariant = Color(0xFFC4D1D4),
    surfaceTint = HarisPetrolLight,
    inverseSurface = Color(0xFFDDE1E3),
    inverseOnSurface = Color(0xFF2E3538),
    outline = Color(0xFF8FA7AE),
    outlineVariant = Color(0xFF3C4E53),
    scrim = Color(0xFF000000)
)

// ============================================================================
// HARIS TONAL SURFACE TIERS
// Material 3 1.1.2 predates the surfaceContainer* roles, so the brand's tonal
// stepping is provided here and exposed via [LocalHarisColors] / [harisColors].
// ============================================================================

@androidx.compose.runtime.Immutable
data class HarisColors(
    val surfaceContainerLowest: Color,
    val surfaceContainerLow: Color,
    val surfaceContainer: Color,
    val surfaceContainerHigh: Color,
    val surfaceContainerHighest: Color,
    val surfaceBright: Color,
    val surfaceDim: Color
)

private val LightHarisColors = HarisColors(
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF5F9FA),
    surfaceContainer = Color(0xFFF0F5F7),
    surfaceContainerHigh = Color(0xFFE8F0F3),
    surfaceContainerHighest = Color(0xFFE2EBEE),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFDDE5E8)
)

private val DarkHarisColors = HarisColors(
    surfaceContainerLowest = Color(0xFF0A1012),
    surfaceContainerLow = Color(0xFF161F22),
    surfaceContainer = Color(0xFF1A2427),
    surfaceContainerHigh = Color(0xFF242F33),
    surfaceContainerHighest = Color(0xFF2E3A3E),
    surfaceBright = Color(0xFF343B3E),
    surfaceDim = Color(0xFF0F1719)
)

val LocalHarisColors = androidx.compose.runtime.staticCompositionLocalOf { LightHarisColors }

/** Brand tonal surface tiers for the active theme. */
val harisColors: HarisColors
    @Composable
    @androidx.compose.runtime.ReadOnlyComposable
    get() = LocalHarisColors.current

// ============================================================================
// TYPOGRAPHY — Inter (Latin) + Cairo (Arabic/RTL) via Google downloadable fonts
// The No-Roboto Rule: FontFamily.Default must not appear in the product.
// ============================================================================

private val googleFontProvider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs
)

private val InterGoogle = GoogleFont("Inter")
private val CairoGoogle = GoogleFont("Cairo")

val Inter = FontFamily(
    Font(InterGoogle, googleFontProvider, FontWeight.Normal),    // 400
    Font(InterGoogle, googleFontProvider, FontWeight.SemiBold),  // 600
    Font(InterGoogle, googleFontProvider, FontWeight.Bold),      // 700
    Font(InterGoogle, googleFontProvider, FontWeight.ExtraBold)  // 800
)

val Cairo = FontFamily(
    Font(CairoGoogle, googleFontProvider, FontWeight.Normal),    // 400
    Font(CairoGoogle, googleFontProvider, FontWeight.SemiBold),  // 600
    Font(CairoGoogle, googleFontProvider, FontWeight.Bold),      // 700
    Font(CairoGoogle, googleFontProvider, FontWeight.ExtraBold)  // 800
)

/** Default app family (Latin). RTL/Arabic surfaces use [Cairo] via [rememberAppFontFamily]. */
val AppFontFamily = Inter

/**
 * Returns [Cairo] when the layout direction is RTL or the active locale is Arabic,
 * otherwise [Inter]. Use to override the family for locale-specific text.
 */
@Composable
fun rememberAppFontFamily(): FontFamily {
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val isArabic = Locale.current.language.equals("ar", ignoreCase = true)
    return if (isRtl || isArabic) Cairo else Inter
}

private val SafeGuardTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = AppFontFamily, fontWeight = FontWeight.Bold,
        fontSize = 30.sp, lineHeight = 38.sp, letterSpacing = (-0.25).sp
    ),
    displayMedium = TextStyle(
        fontFamily = AppFontFamily, fontWeight = FontWeight.Bold,
        fontSize = 28.sp, lineHeight = 36.sp, letterSpacing = (-0.25).sp
    ),
    displaySmall = TextStyle(
        fontFamily = AppFontFamily, fontWeight = FontWeight.Bold,
        fontSize = 26.sp, lineHeight = 34.sp, letterSpacing = 0.sp
    ),

    headlineLarge = TextStyle(
        fontFamily = AppFontFamily, fontWeight = FontWeight.Bold,
        fontSize = 24.sp, lineHeight = 32.sp, letterSpacing = 0.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = AppFontFamily, fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp, lineHeight = 28.sp, letterSpacing = 0.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = AppFontFamily, fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp, lineHeight = 26.sp, letterSpacing = 0.sp
    ),

    titleLarge = TextStyle(
        fontFamily = AppFontFamily, fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp, lineHeight = 24.sp, letterSpacing = 0.sp
    ),
    titleMedium = TextStyle(
        fontFamily = AppFontFamily, fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.15.sp
    ),
    titleSmall = TextStyle(
        fontFamily = AppFontFamily, fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp
    ),

    bodyLarge = TextStyle(
        fontFamily = AppFontFamily, fontWeight = FontWeight.Normal,
        fontSize = 15.sp, lineHeight = 22.sp, letterSpacing = 0.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = AppFontFamily, fontWeight = FontWeight.Normal,
        fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.sp
    ),
    bodySmall = TextStyle(
        fontFamily = AppFontFamily, fontWeight = FontWeight.Normal,
        fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.2.sp
    ),

    labelLarge = TextStyle(
        fontFamily = AppFontFamily, fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.1.sp
    ),
    labelMedium = TextStyle(
        fontFamily = AppFontFamily, fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp
    ),
    labelSmall = TextStyle(
        fontFamily = AppFontFamily, fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp
    )
)

/** Returns a copy of this type scale with every style rebased on [family]. */
private fun Typography.withFamily(family: FontFamily): Typography = copy(
    displayLarge = displayLarge.copy(fontFamily = family),
    displayMedium = displayMedium.copy(fontFamily = family),
    displaySmall = displaySmall.copy(fontFamily = family),
    headlineLarge = headlineLarge.copy(fontFamily = family),
    headlineMedium = headlineMedium.copy(fontFamily = family),
    headlineSmall = headlineSmall.copy(fontFamily = family),
    titleLarge = titleLarge.copy(fontFamily = family),
    titleMedium = titleMedium.copy(fontFamily = family),
    titleSmall = titleSmall.copy(fontFamily = family),
    bodyLarge = bodyLarge.copy(fontFamily = family),
    bodyMedium = bodyMedium.copy(fontFamily = family),
    bodySmall = bodySmall.copy(fontFamily = family),
    labelLarge = labelLarge.copy(fontFamily = family),
    labelMedium = labelMedium.copy(fontFamily = family),
    labelSmall = labelSmall.copy(fontFamily = family)
)

// ============================================================================
// SHAPES
// ============================================================================

val SafeGuardShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

/** Fully rounded pill — primary/secondary/ghost buttons and pill chips. */
val PillShape = RoundedCornerShape(percent = 50)

/** Top-only rounded sheet — bottom sheets (20dp top corners). */
val BottomSheetShape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)

// ============================================================================
// CUSTOM DIMENSIONS
// ============================================================================

object SafeGuardDimens {
    // Brand spacing rhythm (new Haris tokens)
    val screenPadding = 20.dp
    val gutter = 16.dp
    val stackSm = 8.dp
    val stackMd = 16.dp
    val stackLg = 24.dp
    val listItemMinHeight = 56.dp
    val listSeparatorInset = 16.dp

    // Spacing (existing — kept)
    val spacingXxs = 2.dp
    val spacingXs = 4.dp
    val spacingSm = 8.dp
    val spacingMd = 12.dp
    val spacingLg = 16.dp
    val spacingXl = 24.dp
    val spacingXxl = 32.dp
    val spacingXxxl = 48.dp

    // Padding (existing — kept)
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
// ANIMATION SPECS (kept — call sites depend on them; extended in Motion.kt)
// ============================================================================

object SafeGuardAnimations {
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

    val EaseOutQuart = CubicBezierEasing(0.165f, 0.84f, 0.44f, 1f)
    val EaseInOutSoft = CubicBezierEasing(0.45f, 0f, 0.2f, 1f)

    const val ANIMATION_DURATION_INSTANT = 100
    const val ANIMATION_DURATION_SHORT = 160
    const val ANIMATION_DURATION_MEDIUM = 240
    const val ANIMATION_DURATION_LONG = 360
    const val ANIMATION_DURATION_EXTRA_LONG = 500

    const val STAGGER_DELAY_MS = 50
}

// ============================================================================
// SEMANTIC COLORS — recolored to the Haris brand
// ============================================================================

object SemanticColors {
    // Status / meaning
    val success = Color(0xFF2E9E8F)
    val successContainer = Color(0xFFCDEFE8)
    val successOnContainer = Color(0xFF06302A)
    val warning = HarisGold                       // #C8941E solid gold accent
    val warningContainer = Color(0xFFF6EACA)
    val warningOnContainer = Color(0xFF4A3608)
    val error = Color(0xFFD2483F)
    val errorContainer = Color(0xFFFBD9D5)
    val errorOnContainer = Color(0xFF410E0A)
    val info = HarisPetrol                         // #168BB6
    val infoContainer = Color(0xFFC9ECF8)
    val infoOnContainer = Color(0xFF072A36)

    // Alert severity — calm -> loud
    val severityCritical = Color(0xFFD2483F)
    val severityCriticalContainer = Color(0xFFFBD9D5)
    val severityHigh = Color(0xFFE8833A)
    val severityHighContainer = Color(0xFFFBE0CC)
    val severityMedium = HarisGold                 // #C8941E
    val severityMediumContainer = Color(0xFFF6EACA)
    val severityLow = Color(0xFF2E9E8F)
    val severityLowContainer = Color(0xFFCDEFE8)

    // Device status
    val statusOnline = Color(0xFF2E9E8F)
    val statusOffline = Color(0xFF6B8C94)
    val statusSuspended = HarisGold                // #C8941E

    // Screen time
    val screenTimeNormal = Color(0xFF2E9E8F)
    val screenTimeWarning = HarisGold
    val screenTimeExceeded = Color(0xFFD2483F)

    // Progress
    val progressBackground = Color(0xFFC4D1D4)
    val progressTrack = Color(0xFFC9ECF8)

    // Child surfaces — brand dialect
    val childPrimary = HarisPetrol
    val childSecondary = HarisAqua
    val childAccent = HarisGold
    val childBackground = Color(0xFFC9ECF8)
    val childSuccess = Color(0xFF2E9E8F)
    val childWarning = Color(0xFFF1DCA7)

    // Leaderboard rank medals (decorative)
    val rankGold = HarisGold
    val rankSilver = Color(0xFFC0C0C0)
    val rankBronze = Color(0xFFCD7F32)

    // Gradient presets — petrol -> aqua (gold never in gradient)
    val gradientPrimary = listOf(GradientStart, GradientEnd)
    val gradientSuccess = listOf(Color(0xFF2E9E8F), Color(0xFF5FC0B3))
    val gradientWarning = listOf(Color(0xFFC8941E), Color(0xFFE0B45A))
    val gradientError = listOf(Color(0xFFD2483F), Color(0xFFE2776F))
    val gradientChild = listOf(GradientStart, GradientEnd)
}

// ============================================================================
// MAIN THEME COMPOSABLE
// ============================================================================

@Composable
fun SafeGuardTheme(
    // Dark is the canonical brand design; the app follows the device system
    // preference. No persisted theme state / in-app toggle.
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false, // Disabled for consistent branding
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
            // view.context is an Activity when the theme hosts a screen, but it is the
            // Application when the theme is hosted in a system overlay window (the lock
            // overlay - see LockOverlayController). Skip status-bar tinting when there is
            // no Activity rather than crashing on the cast; the overlay is fullscreen and
            // draws its own background, so the system bars are irrelevant there.
            val window = (view.context.findActivity())?.window ?: return@SideEffect
            // Use surface color for status bar for a cleaner look
            window.statusBarColor = colorScheme.surface.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    val harisColorTiers = if (darkTheme) DarkHarisColors else LightHarisColors

    // Locale-aware family: Cairo for Arabic/RTL, Inter otherwise (bilingual support).
    val family = rememberAppFontFamily()
    val typography = remember(family) { SafeGuardTypography.withFamily(family) }

    androidx.compose.runtime.CompositionLocalProvider(LocalHarisColors provides harisColorTiers) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = typography,
            shapes = SafeGuardShapes,
            content = content
        )
    }
}

/** Brand alias for [SafeGuardTheme]. Names are kept stable; this is additive. */
@Composable
fun HarisTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) = SafeGuardTheme(darkTheme = darkTheme, dynamicColor = dynamicColor, content = content)
/**
 * Walk the [Context] wrapper chain to find the hosting [Activity], or null if there is none
 * (e.g. when the theme is hosted in a system overlay window whose context is the Application).
 */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

// ============================================================================
// EXTENSION FUNCTIONS FOR THEME (helper fns kept — only referenced values change)
// ============================================================================

/** Get the appropriate color for screen time progress. */
fun getScreenTimeColor(usedMinutes: Int, limitMinutes: Int?): Color {
    if (limitMinutes == null) return SemanticColors.screenTimeNormal

    val percentage = (usedMinutes.toFloat() / limitMinutes) * 100
    return when {
        percentage >= 100 -> SemanticColors.screenTimeExceeded
        percentage >= 80 -> SemanticColors.screenTimeWarning
        else -> SemanticColors.screenTimeNormal
    }
}

/** Get color for alert severity. */
fun getAlertSeverityColor(severity: String): Color {
    return when (severity.uppercase()) {
        "CRITICAL" -> SemanticColors.severityCritical
        "HIGH" -> SemanticColors.severityHigh
        "MEDIUM" -> SemanticColors.severityMedium
        "LOW" -> SemanticColors.severityLow
        else -> SemanticColors.info
    }
}

/** Get color for device status. */
fun getDeviceStatusColor(status: String): Color {
    return when (status.uppercase()) {
        "ACTIVE" -> SemanticColors.statusOnline
        "SUSPENDED" -> SemanticColors.statusSuspended
        "INACTIVE" -> SemanticColors.statusOffline
        else -> SemanticColors.statusOffline
    }
}

/** Get container color for alert severity. */
fun getAlertSeverityContainerColor(severity: String): Color {
    return when (severity.uppercase()) {
        "CRITICAL" -> SemanticColors.severityCriticalContainer
        "HIGH" -> SemanticColors.severityHighContainer
        "MEDIUM" -> SemanticColors.severityMediumContainer
        "LOW" -> SemanticColors.severityLowContainer
        else -> SemanticColors.infoContainer
    }
}

/** Get gradient colors for screen time based on usage percentage. */
fun getScreenTimeGradient(usedMinutes: Int, limitMinutes: Int?): List<Color> {
    if (limitMinutes == null) return SemanticColors.gradientSuccess

    val percentage = (usedMinutes.toFloat() / limitMinutes) * 100
    return when {
        percentage >= 100 -> SemanticColors.gradientError
        percentage >= 80 -> SemanticColors.gradientWarning
        else -> SemanticColors.gradientSuccess
    }
}

// ============================================================================
// TYPE RAMP PREVIEW
// ============================================================================

@Composable
private fun TypeRamp() {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(SafeGuardDimens.screenPadding),
            verticalArrangement = Arrangement.spacedBy(SafeGuardDimens.stackSm)
        ) {
            val t = MaterialTheme.typography
            Text("displayLarge", style = t.displayLarge)
            Text("displayMedium", style = t.displayMedium)
            Text("displaySmall", style = t.displaySmall)
            Text("headlineLarge", style = t.headlineLarge)
            Text("headlineMedium", style = t.headlineMedium)
            Text("headlineSmall", style = t.headlineSmall)
            Text("titleLarge", style = t.titleLarge)
            Text("titleMedium", style = t.titleMedium)
            Text("titleSmall", style = t.titleSmall)
            Text("bodyLarge", style = t.bodyLarge)
            Text("bodyMedium", style = t.bodyMedium)
            Text("bodySmall", style = t.bodySmall)
            Text("labelLarge", style = t.labelLarge)
            Text("labelMedium", style = t.labelMedium)
            Text("labelSmall", style = t.labelSmall)
        }
    }
}

@Preview(name = "Type ramp · Light", showBackground = true)
@Composable
private fun TypeRampLightPreview() {
    SafeGuardTheme(darkTheme = false) { TypeRamp() }
}

@Preview(name = "Type ramp · Dark", showBackground = true)
@Composable
private fun TypeRampDarkPreview() {
    SafeGuardTheme(darkTheme = true) { TypeRamp() }
}
