# Contract: Theme & Resource Tokens

The stable surface other code depends on. Public Kotlin symbol names and XML resource names are the contract; values come from [data-model.md](../data-model.md).

## Compose theme entry point
```kotlin
@Composable
fun SafeGuardTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),   // was hardcoded false
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
)

typealias HarisTheme = SafeGuardTheme   // additive brand alias; SafeGuardTheme name preserved
```
- Selects `DarkColorScheme` when `darkTheme`, else `LightColorScheme`. Dynamic color stays off.
- `SideEffect` sets `window.statusBarColor = colorScheme.surface.toArgb()` and `isAppearanceLightStatusBars = !darkTheme` (unchanged mechanism).

## Typography contract
```kotlin
val Inter: FontFamily            // GoogleFont, weights 400/600/700/800
val Cairo: FontFamily            // GoogleFont, weights 400/600/700/800
val AppFontFamily: FontFamily    // = Inter

@Composable fun rememberAppFontFamily(): FontFamily   // Cairo for RTL/Arabic, else Inter

private val SafeGuardTypography: Typography            // all 15 M3 styles, Inter base
```

## Shape contract
```kotlin
val SafeGuardShapes: Shapes      // xs4 / sm8 / md12 / lg16 / xl24
val PillShape: RoundedCornerShape          // percent = 50
val BottomSheetShape: RoundedCornerShape   // topStart/topEnd = 20.dp
```

## Dimension contract
```kotlin
object SafeGuardDimens {
    // ADDED
    val screenPadding: Dp        // 20.dp
    val gutter: Dp               // 16.dp
    val stackSm: Dp; val stackMd: Dp; val stackLg: Dp   // 8 / 16 / 24
    val listItemMinHeight: Dp    // 56.dp
    val listSeparatorInset: Dp   // 16.dp
    // EXISTING icon/avatar/button-height/progress/elevation tokens — kept
}
```

## Responsive contract
```kotlin
enum class ScreenWidth { Compact, Medium, Expanded }
@Composable fun rememberScreenWidth(): ScreenWidth
fun Modifier.responsiveContentWidth(width: ScreenWidth): Modifier      // cap 600.dp, center on Medium/Expanded
fun responsiveScreenPadding(width: ScreenWidth): Dp                    // 20 / 24 / 32
```

## Motion contract
```kotlin
fun Modifier.shimmerEffect(): Modifier
fun Modifier.fadeScaleIn(visible: () -> Boolean): Modifier   // graphicsLayer { alpha; scale 0.96→1.0 }
// staggered list entrance helper (50ms step) + primary-button press-scale
object SafeGuardAnimations { /* existing spring specs kept + extended */ }
```

## Semantic color contract
```kotlin
object SemanticColors {
    val success; val warning; val info; val gradientPrimary
    val statusOnline; val statusSuspended; val statusOffline
    val severityLow/Medium/High/Critical + *Container
    // existing property names preserved; only values change
}
fun getScreenTimeColor(used: Int, limit: Int?): Color
fun getAlertSeverityColor(severity: String): Color
fun getDeviceStatusColor(status: String): Color
fun getAlertSeverityContainerColor(severity: String): Color
fun getScreenTimeGradient(used: Int, limit: Int?): List<Color>
```

## XML resource contract (`res/`)
| Resource | Keys |
|---|---|
| `values/colors.xml` | `primary #168BB6`, `primary_dark #0E6A86`, `primary_light #A4DFF4`, `accent #C8941E`, `severity_low #2E9E8F`, `severity_medium #C8941E`, `severity_high #E8833A`, `severity_critical #D2483F` |
| `values/themes.xml` | `Theme.SafeGuard`: `statusBarColor @color/surface`, `windowBackground @color/background` |
| `values-night/themes.xml` (NEW) | dark surface `#0F1719`, `windowLightStatusBar = false` |
| `values/font_certs.xml` (NEW) | `com_google_android_gms_fonts_certs` array |

## Gradle additive deps (BOM `2023.10.01` unchanged)
```gradle
implementation 'androidx.compose.ui:ui-text-google-fonts'
implementation 'androidx.compose.material3:material3-window-size-class:1.1.2'
```
