# Phase 0 Research: Haris Design-System Restyle

All Technical Context unknowns resolved below. Each item: Decision → Rationale → Alternatives considered.

## R1. Bilingual downloadable fonts (Inter + Cairo) vs bundled fonts

- **Decision**: Add `androidx.compose.ui:ui-text-google-fonts` (BOM-managed) and load `Inter` (Latin) and `Cairo` (Arabic/RTL) via `GoogleFont` + a `GoogleFont.Provider` (authority `com.google.android.gms.fonts`, package `com.google.android.gms`), with `res/values/font_certs.xml` holding `com_google_android_gms_fonts_certs`. Each family declares weights 400/600/700/800. A per-weight `FontFamily` fallback chain ensures graceful degradation to the platform font if the provider is unavailable.
- **Rationale**: The brand mandates Inter + Cairo with bilingual/RTL support; downloadable fonts avoid shipping font binaries and keep APK size down. The GMS provider is present on the target install base (app already depends on Google Play services for Firebase/Sign-In per constitution Principle V).
- **Alternatives considered**:
  - *Keep bundled `.ttf`* — rejected: brand requires Inter/Cairo specifically; bundling four weights × two families inflates APK and the prompt explicitly says "no binaries".
  - *Bundle Inter/Cairo as `.ttf`* — rejected for the same APK/no-binaries reason; downloadable is the brand-specified path.
- **Bundled-font cleanup**: The existing `res/font/*.ttf` (Bricolage/Hanken) become unused once `Theme.kt` references Inter/Cairo. Decision: **leave the files in place but remove all code references**, deferring physical deletion to a follow-up cleanup to minimize diff risk during the build-green-per-step constraint. Removing them is safe but not required for correctness.

## R2. Font selection by locale / layout direction

- **Decision**: `@Composable fun rememberAppFontFamily(): FontFamily` returns `Cairo` when `LocalLayoutDirection.current == LayoutDirection.Rtl` **or** the current locale language is Arabic (`ar`), otherwise `Inter`. The Material `Typography` is built on Inter as the default `AppFontFamily`; Cairo is applied where RTL/Arabic is detected.
- **Rationale**: Layout-direction is the most reliable runtime signal in Compose; adding the locale check covers Arabic content rendered inside an LTR container. Keeps a single source of truth (`rememberAppFontFamily`).
- **Alternatives considered**: Static per-locale `Typography` resource — rejected: Compose typography is code-defined here, and a runtime composable selector is simpler and testable via previews.

## R3. "Dark is default" mechanism (no new state)

- **Decision**: `SafeGuardTheme(darkTheme: Boolean = isSystemInDarkTheme(), dynamicColor: Boolean = false, ...)`. Dark scheme is the canonical brand design; the app honors the device system setting. No DataStore/SharedPreferences/in-app toggle added.
- **Rationale**: Clarification chose "follow system, dark canonical". `isSystemInDarkTheme()` is stateless and satisfies the hard rule against new state/ViewModel/DataStore. Dynamic color stays `false` for consistent branding.
- **Alternatives considered**: Force dark always (rejected — ignores user's light preference); hardcode `true` default (rejected — same problem, and breaks light-device users). Both contradicted the clarified answer.

## R4. `surfaceContainer*` tonal tiers on M3 1.1.2

- **Decision**: Set `surfaceContainerLowest/Low/Container/High/Highest`, `surfaceBright`, `surfaceDim` (and `surfaceTint`) explicitly in both `lightColorScheme(...)` and `darkColorScheme(...)` using the Haris values. These parameters exist in Material 3 `1.1.2`'s `ColorScheme`.
- **Rationale**: Depth-via-tonal-stepping is a core brand rule; the pinned M3 version supports the parameters, so no upgrade is needed.
- **Alternatives considered**: Upgrade M3 for newer surface APIs — rejected: versions are locked; 1.1.2 already exposes the needed tokens.

## R5. Responsive width via WindowSizeClass

- **Decision**: Add `androidx.compose.material3:material3-window-size-class:1.1.2` (consistent with the pinned `material3:1.1.2`). Create `presentation/theme/Responsive.kt` with `enum ScreenWidth { Compact, Medium, Expanded }`, `@Composable fun rememberScreenWidth()` derived from `calculateWindowSizeClass`/`currentWindowDpSize`, `Modifier.responsiveContentWidth(width)` (caps content at 600.dp and centers on Medium/Expanded), and `responsiveScreenPadding(width)` → 20/24/32.dp.
- **Rationale**: Window-size-class is the standard Compose responsive primitive and matches the brand's compact/medium/expanded tiers. Pinning to 1.1.2 keeps it lockstep with `material3`.
- **Alternatives considered**: Manual `BoxWithConstraints` width thresholds — viable but reinvents the standard; window-size-class is the idiomatic choice and the prompt names it.

## R6. Recomposition-safe motion

- **Decision**: `presentation/theme/Motion.kt` provides: `Modifier.shimmerEffect()` (skeleton loading via an infinite-transition brush offset read inside `drawWithCache`/`graphicsLayer`), `Modifier.fadeScaleIn(visible: () -> Boolean)` using `animateFloatAsState` + `graphicsLayer { alpha = a; scaleX = scaleY = 0.96f + 0.04f*a }` (deferred lambda read), a staggered list-entrance helper (50ms step, reusing `SafeGuardAnimations.STAGGER_DELAY_MS`), and a primary-button press-scale via `graphicsLayer`. Existing `SafeGuardAnimations` spring specs are kept and extended.
- **Rationale**: Reading animated floats inside `graphicsLayer`/draw lambdas defers them to the draw phase, avoiding per-frame recomposition/relayout — the explicit performance constraint. Reuses existing animation tokens to avoid duplication.
- **Alternatives considered**: Animating `Modifier.alpha(value)` / layout size directly — rejected: recomposes every frame, violates the hard rule.

## R7. Component package strategy

- **Decision**: New `presentation/designsystem/` package for all `Haris*` slot-API components. Existing `presentation/components/` (`Buttons.kt`, `Cards.kt`, `EnhancedComponents.kt`, etc.) are recolored to tokens where still referenced and progressively superseded as screens migrate to `Haris*` equivalents.
- **Rationale**: Clarification chose a dedicated package for a clean design-system boundary. Keeping legacy `components/` compiling during migration preserves the build-green-per-step rule.
- **Alternatives considered**: Extend `components/` in place — rejected by clarification (mixes old/new in one package).

## R8. XML system-chrome alignment + dark resource variant

- **Decision**: Update `res/values/colors.xml` (`primary #168BB6`, `primary_dark #0E6A86`, `primary_light #A4DFF4`, `accent #C8941E`; `severity_low/medium/high/critical`). Update `res/values/themes.xml` `Theme.SafeGuard` → `statusBarColor @color/surface`, `windowBackground @color/background`. Create `res/values-night/themes.xml` with dark surface `#0F1719` and `windowLightStatusBar=false`. The Compose `SideEffect` in `Theme.kt` continues to set the runtime status-bar color from `colorScheme.surface`; the XML handles pre-Compose window chrome (splash/launch, `LockScreenActivity` window).
- **Rationale**: System chrome must match the brand in both themes before Compose draws; the night-qualified resource is the standard way to theme the dark window.
- **Alternatives considered**: Compose-only chrome — rejected: the launch window and Activity background render before Compose composition.

## R9. Build-green-per-step sequencing

- **Decision**: Implement in the prompt's STEP order (colors → typography → shapes/responsive → motion → components → screen sweep → verify). After each step run `./gradlew assembleDebug`; the final step also runs `./gradlew lintDebug`. The `HarisTheme = SafeGuardTheme` typealias is added early so new code can reference the brand name without renaming.
- **Rationale**: Each step is independently compilable; recoloring tokens first means downstream components/screens consume stable tokens. Matches the spec's incremental-buildability requirement (FR-020).
- **Alternatives considered**: Big-bang single commit — rejected: violates the per-step build-success rule and makes regressions hard to localize.

## R10. WCAG AA contrast verification

- **Decision**: Treat the spec's provided light/dark token pairings as AA-targeted; during the verify step, compute contrast ratios for body-text-on-surface pairings in both themes and snap any failing pairing to the nearest accessible on-token (no off-palette colors introduced). Decorative graphics get `contentDescription = null`; actionable icons/images get `stringResource`-backed descriptions.
- **Rationale**: AA is the stated bar (FR-017/SC-004). Snapping to existing on-tokens keeps the palette closed.
- **Alternatives considered**: Introduce new corrective colors — rejected: would breach the "tokens only / no stray hex" rule.

## Open questions

None. All Technical Context items resolved; no `NEEDS CLARIFICATION` remain.
