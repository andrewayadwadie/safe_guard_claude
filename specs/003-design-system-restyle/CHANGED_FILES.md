# Changed Files — Haris Design-System Restyle

Grouped by area. Only the presentation/theme layer and `res/` resources were touched.

## Theme
- `app/src/main/java/com/safeguard/parentalcontrol/presentation/theme/Theme.kt`
  — brand vals renamed `SafeGuard*` → `Haris*`; Light/Dark `ColorScheme` recolored to Haris;
  `HarisColors` tonal surface tiers + `LocalHarisColors`/`harisColors` (M3 1.1.2 has no
  `surfaceContainer*`); Inter/Cairo Google downloadable fonts + `GoogleFont.Provider`;
  `rememberAppFontFamily()`; 15-style `SafeGuardTypography` on Inter; `SafeGuardShapes` +
  `PillShape` + `BottomSheetShape`; new `SafeGuardDimens` tokens; `SemanticColors` recolored
  (+ rank medals); helper fns retained; `SafeGuardTheme(darkTheme = isSystemInDarkTheme())`;
  additive `HarisTheme`; type-ramp light/dark `@Preview`.
- `app/src/main/java/com/safeguard/parentalcontrol/presentation/theme/Responsive.kt` (NEW)
  — `ScreenWidth`, `rememberScreenWidth()`, `responsiveContentWidth()`,
  `responsiveScreenPadding()`, 360/840dp previews.
- `app/src/main/java/com/safeguard/parentalcontrol/presentation/theme/Motion.kt` (NEW)
  — `shimmerEffect()`, `fadeScaleIn()`, `staggeredEntrance()`, `pressScale()` (all
  recomposition-safe via `graphicsLayer`/draw lambdas); shimmer + fadeScaleIn preview.

## Resources
- `app/src/main/res/values/colors.xml` — primary/primary_dark/primary_light/accent +
  severity_* recolored to Haris.
- `app/src/main/res/values/themes.xml` — `Theme.SafeGuard` statusBarColor `@color/surface`,
  windowBackground `@color/background`, light status-bar icons.
- `app/src/main/res/values-night/themes.xml` (NEW) — dark brand chrome `#0F1719`,
  `windowLightStatusBar=false`.
- `app/src/main/res/values/font_certs.xml` (NEW) — GMS Google Fonts provider certs.
- `app/build.gradle` — added `ui-text-google-fonts` (BOM-managed) and
  `material3-window-size-class:1.1.2` (BOM `2023.10.01` unchanged; `noCompress "tflite"` intact).

## Components (new design system)
- `presentation/designsystem/HarisButtons.kt` (NEW) — Primary/Secondary/Ghost, pill, press-scale.
- `presentation/designsystem/HarisTextField.kt` (NEW) — filled, label-above, helper/error slots.
- `presentation/designsystem/HarisCard.kt` (NEW) — 16dp, status slot, dark border.
- `presentation/designsystem/HarisChip.kt` (NEW) — pill + filter variant.
- `presentation/designsystem/HarisSwitch.kt` (NEW) — primary-teal on-state.
- `presentation/designsystem/HarisListItem.kt` (NEW) — 56dp min, 16dp separator inset.
- `presentation/designsystem/HarisGradientHeader.kt` (NEW) — petrol→aqua banner.
  (Each component ships light + dark `@Preview` with sample data.)

## Components (existing, recolored to tokens)
- `presentation/components/Cards.kt` — offline dot → `SemanticColors.statusOffline`.
- `presentation/components/CircularProgressDisplay.kt` — default colors → `HarisPetrol`/
  `SemanticColors.progressBackground`.
- `presentation/components/ChildFriendlyComponents.kt` — rank medals → `SemanticColors.rank*`.

## Screens (hardcoded colors replaced with tokens)
- `presentation/dashboard/DashboardScreen.kt` — logo gradient → `gradientPrimary`; rank medals → tokens.
- `presentation/devices/DevicesScreen.kt` — all status colors → `SemanticColors.status*`/`success`.
- `presentation/alerts/AlertsScreen.kt` — severity icon colors + filter chips → `SemanticColors.severity*`.
- `presentation/imagereview/ImageReviewScreen.kt` — category colors → `SemanticColors.severity*`.

All other Compose screens (auth, settings, setup, lockscreen, children, blacklist, devicesetup,
screentimelimits, wordlist, text-monitoring) contained **no** hardcoded colors and adopt the Haris
brand automatically through the recolored `MaterialTheme` tokens.

## Not modified (verified)
ViewModels, repositories, use cases, DI modules, services, workers, receivers, networking,
`ApiService`, navigation routes/state/actions. Package name `com.safeguard.parentalcontrol` and
all existing class/composable names unchanged (`HarisTheme` is additive).
