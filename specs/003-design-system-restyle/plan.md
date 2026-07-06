# Implementation Plan: Haris Design-System Restyle

**Branch**: `003-design-system-restyle` | **Date**: 2026-06-26 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/003-design-system-restyle/spec.md`

## Summary

Re-skin the entire SafeGuard Android UI with the new **Haris** brand design system derived from the new logo (petrol/teal protective ring + gold family mark). The work replaces the prior "Warm Hearth" pine palette and bundled-font typography with: a recolored Material 3 light+dark color scheme (dark following the system preference as the canonical design), bilingual **Inter** (Latin) + **Cairo** (Arabic/RTL) downloadable fonts, a new shape/spacing/responsive token set, recomposition-safe motion utilities, a new `presentation/designsystem/` package of reusable `Haris*` slot-API components, and a screen-by-screen visual sweep of all 15 Compose screens plus the lock screen.

The change is strictly confined to the **presentation/theme layer** and `res/` resources. No ViewModel, repository, use case, DI module, service, worker, receiver, networking, `ApiService`, or navigation route/state/action is modified. Package name and existing public class/composable names are preserved (a `HarisTheme` typealias is added). The project stays buildable at every step (`./gradlew assembleDebug`).

## Technical Context

**Language/Version**: Kotlin (JDK 21 toolchain, `jvmTarget = 17`), Compose Compiler plugin (Kotlin 2.x `org.jetbrains.kotlin.plugin.compose`)

**Primary Dependencies**: Jetpack Compose (BOM `2023.10.01`, locked), Material 3 `1.1.2`, Navigation Compose `2.7.6`, Hilt `2.51.1`, Coil `2.5.0`. **New (BOM-managed, no version bump to the locked BOM):** `androidx.compose.ui:ui-text-google-fonts`, `androidx.compose.material3:material3-window-size-class`.

**Storage**: N/A — this feature persists nothing. No DataStore/Room/SharedPreferences changes. (Theme follows system preference; no theme state stored.)

**Testing**: `./gradlew assembleDebug` (must pass after each step) + `./gradlew lintDebug`; `@Preview` composables (light + dark) per component and per screen as the primary visual verification surface; existing Compose UI test harness (`ui-test-junit4`) unaffected.

**Target Platform**: Android `minSdk 26`, `targetSdk 35`, `compileSdk 35`.

**Project Type**: Native Android mobile app (single module `app/`), MVVM + clean architecture, package-by-feature.

**Performance Goals**: 60 fps; motion implemented via deferred reads (`graphicsLayer { }`, `offset { }` lambdas) so animation never triggers per-frame layout recomposition.

**Constraints**: Presentation/theme + `res/` only; no hardcoded color literals in screens/components (tokens only); WCAG AA body-text contrast in both themes; correct RTL with Cairo; downloadable fonts must degrade gracefully when unavailable; locked dependency BOM/versions must not change (additive BOM-managed artifacts only).

**Scale/Scope**: 1 theme file recolor + new theme files (Typography/Shapes/Responsive/Motion split or in-place), ~9 new `Haris*` design-system components, 15 Compose screens + `LockScreenActivity` content swept, XML resource updates (`colors.xml`, `themes.xml`, new `values-night/themes.xml`, `font_certs.xml`, font provider).

### Key context discovered (supersedes assumptions in the source prompt)

- **Fonts are NOT `FontFamily.Default`.** The app currently ships **bundled** OFL fonts (`Bricolage Grotesque`, `Hanken Grotesk`) in `res/font/` under a "No-Roboto Rule". This plan replaces them with downloadable `Inter`/`Cairo`; the bundled `.ttf` files become unused and may be left in place or removed (see research.md).
- **A prior design system already exists** ("Warm Hearth" pine/green). `Theme.kt` already defines `SafeGuardShapes`, `SafeGuardDimens`, `SafeGuardAnimations`, `SemanticColors`, and the helper functions (`getScreenTimeColor`, `getAlertSeverityColor`, `getDeviceStatusColor`, `getAlertSeverityContainerColor`, `getScreenTimeGradient`). These are recolored/extended in place, names preserved.
- **`darkTheme` is hardcoded `false`** and ignores the system. Per clarification it becomes `isSystemInDarkTheme()`.
- **Color schemes lack `surfaceContainer*` tiers** today; they are added per the Haris spec.
- **`material3` is pinned to `1.1.2`** explicitly (not BOM-resolved). `material3-window-size-class` will be added at a matching `1.1.2` to stay consistent.
- **No `values-night/` directory exists**; it is created.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

This is a presentation-only re-skin. Evaluation against the twelve SpecKit Enforcement Rules and the five principles:

| Rule / Principle | Impact | Status |
|---|---|---|
| I. Child Safety & Privacy First | Collects/stores/transmits **no** data. Data-flow note: *nothing collected, nothing transmitted, no retention.* | ✅ PASS |
| II. MVVM + Clean Architecture | UI stays dumb; no business logic added to composables; `collectAsStateWithLifecycle()` preserved; no layer boundaries crossed. | ✅ PASS |
| III. Two-Role Architecture | No enforcement/role logic touched. Parent + child (lock screen) UI restyled visually only. | ✅ PASS |
| IV. Native Services & Permission Hygiene | **Rule 6 preserved**: `PermissionsSetupScreen` keeps routing dangerous permissions to system settings (explicit spec edge case). No `requestPermissions()` introduced. No manifest permission added. | ✅ PASS |
| V. Security & Secrets Hygiene | No secrets, tokens, or network code touched. Rule 5 (only `TokenManager` reads tokens) unaffected. | ✅ PASS |
| Rule 1 (feature package layout) | New `presentation/designsystem/` holds logic-free UI; no ViewModel/UiState needed (not a feature screen). | ✅ PASS |
| Rule 10 (errors via Snackbar) | Restyle does not convert errors to dialogs. Settings **logout confirmation** dialog is a user-action confirmation, not error display — allowed. | ✅ PASS |
| Rule 12 (`noCompress "tflite"`) | `build.gradle` edited only to add two BOM-managed UI artifacts; `aaptOptions` untouched. | ✅ PASS |
| Locked dependency versions | BOM `2023.10.01` unchanged; only additive Compose UI artifacts at versions consistent with the pinned set. Documented, not a bump. | ✅ PASS |

**Gate result: PASS — no violations. Complexity Tracking not required.**

## Project Structure

### Documentation (this feature)

```text
specs/003-design-system-restyle/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output (design-token + component model)
├── quickstart.md        # Phase 1 output (build/verify guide)
├── contracts/           # Phase 1 output (component slot-API + token contracts)
│   ├── design-tokens.md
│   └── components.md
└── tasks.md             # Phase 2 output (/speckit-tasks — NOT created here)
```

### Source Code (repository root)

```text
app/src/main/java/com/safeguard/parentalcontrol/presentation/
├── theme/
│   ├── Theme.kt             # recolor schemes + brand vals Haris*, darkTheme=isSystemInDarkTheme(),
│   │                        #   add surfaceContainer* tiers, HarisTheme typealias, Inter/Cairo,
│   │                        #   rememberAppFontFamily(), recolored SemanticColors + helpers
│   ├── Type.kt              # (new or in Theme.kt) Inter/Cairo GoogleFont families + type ramp
│   ├── Shape.kt             # (new or in Theme.kt) Shapes + PillShape + BottomSheetShape
│   ├── Responsive.kt        # NEW — ScreenWidth enum, rememberScreenWidth(),
│   │                        #   responsiveContentWidth(), responsiveScreenPadding()
│   └── Motion.kt            # NEW — shimmerEffect(), fadeScaleIn(), staggered entrance, press-scale
├── designsystem/            # NEW PACKAGE — Haris* slot-API components (logic-free, tokens only)
│   ├── HarisButtons.kt      # HarisPrimaryButton / HarisSecondaryButton / HarisGhostButton
│   ├── HarisTextField.kt
│   ├── HarisCard.kt
│   ├── HarisChip.kt
│   ├── HarisSwitch.kt
│   ├── HarisListItem.kt
│   └── HarisGradientHeader.kt
├── components/              # existing components recolored to tokens / migrated to designsystem
├── auth/ dashboard/ devices/ alerts/ settings/ setup/ lockscreen/
│   children/ blacklist/ devicesetup/ imagereview/ screentimelimits/ wordlist/
│                            # all screens swept: tokens, shapes, dimens, components, previews

app/src/main/res/
├── values/colors.xml        # primary/primary_dark/primary_light/accent + severity_* → Haris
├── values/themes.xml        # Theme.SafeGuard: statusBarColor @color/surface, windowBackground @color/background
├── values-night/themes.xml  # NEW — dark surface #0F1719, windowLightStatusBar=false
├── values/font_certs.xml    # NEW — com_google_android_gms_fonts_certs
└── font/                    # bundled .ttf now unused (Inter/Cairo are downloadable); see research.md
```

**Structure Decision**: Single-module Android app, package-by-feature under `presentation/`. Theme work stays in `presentation/theme/` (recolor `Theme.kt` in place; optionally split Typography/Shape into sibling files; add `Responsive.kt` and `Motion.kt`). New `Haris*` components go in a brand-new `presentation/designsystem/` package (per clarification), and screens migrate their usages to them. No new feature module, no navigation change.

## Complexity Tracking

> Constitution Check passed with no violations. No entries required.
