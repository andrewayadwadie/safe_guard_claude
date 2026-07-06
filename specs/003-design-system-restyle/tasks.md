---
description: "Task list for Haris Design-System Restyle"
---

# Tasks: Haris Design-System Restyle

**Input**: Design documents from `/specs/003-design-system-restyle/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/

**Tests**: No automated test tasks — the spec requests none. Verification is per-component/per-screen `@Preview` (light + dark), `./gradlew assembleDebug` after every step, and `./gradlew lintDebug` at the end. Treat each `@Preview` requirement as the task's built-in visual test.

**Organization**: A shared design-system foundation (Phases 1–2) underpins every screen. User-story phases then realize the spec's prioritized lenses (P1 brand sweep → P2 dark default → P3 typography/RTL → P3 a11y → P4 responsive).

**Base path**: `app/src/main/java/com/safeguard/parentalcontrol/presentation/` (abbreviated `…/presentation/` below). Resources under `app/src/main/res/`.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependency on incomplete tasks)
- **[Story]**: US1–US5 maps to spec user stories. Setup/Foundational/Polish carry no story label.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Add dependencies and font-provider resources needed by the design system.

- [x] T001 Add BOM-managed deps `androidx.compose.ui:ui-text-google-fonts` and `androidx.compose.material3:material3-window-size-class:1.1.2` to `app/build.gradle` (Compose BOM `2023.10.01` unchanged; `aaptOptions { noCompress "tflite" }` left intact)
- [x] T002 [P] Create `app/src/main/res/values/font_certs.xml` with the `com_google_android_gms_fonts_certs` certificate array

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Build the entire Haris design-system core (colors, typography, shapes, dimens, responsive, motion, components). Realizes spec STEP 1–5.

**⚠️ CRITICAL**: No screen sweep (US1+) can begin until this phase is complete and `assembleDebug` is green.

### Color system (STEP 1) — all in `…/presentation/theme/Theme.kt`

- [x] T003 Rename brand vals `SafeGuard*` → `Haris*` (`HarisPetrol`, `HarisPetrolDark` #0E6A86, `HarisPetrolLight` #A4DFF4, `HarisAqua` #2799A5, `HarisAquaLight` #ACE5EC, `HarisGold` #C8941E, `HarisGoldLight` #F1DCA7, `HarisGoldDark` #AF861D, `GradientStart` #0E6A86, `GradientEnd` #2799A5) and update all in-file references in `…/presentation/theme/Theme.kt`
- [x] T004 Replace `LightColorScheme` (`lightColorScheme`) and `DarkColorScheme` (`darkColorScheme`) with the Haris values incl. `surfaceContainerLowest/Low/Container/High/Highest`, `surfaceBright`, `surfaceDim`, `surfaceTint` (values per data-model.md) in `…/presentation/theme/Theme.kt` (depends on T003)
- [x] T005 Add `typealias HarisTheme = SafeGuardTheme` and keep the `SafeGuardTheme` composable name unchanged in `…/presentation/theme/Theme.kt`
- [x] T006 Recolor `SemanticColors` (success #2E9E8F, warning #C8941E, info #168BB6, `gradientPrimary` listOf(#0E6A86,#2799A5), status online/suspended/offline #2E9E8F/#C8941E/#6B8C94, 4-tier severity low/medium/high/critical + containers) and keep helper fns `getScreenTimeColor`/`getAlertSeverityColor`/`getDeviceStatusColor`/`getAlertSeverityContainerColor`/`getScreenTimeGradient` (values only change) in `…/presentation/theme/Theme.kt` (depends on T003)
- [x] T007 [P] Update `app/src/main/res/values/colors.xml`: `primary #168BB6`, `primary_dark #0E6A86`, `primary_light #A4DFF4`, `accent #C8941E`, `severity_low #2E9E8F`, `severity_medium #C8941E`, `severity_high #E8833A`, `severity_critical #D2483F`
- [x] T008 [P] Update `app/src/main/res/values/themes.xml` `Theme.SafeGuard`: `statusBarColor @color/surface`, `windowBackground @color/background`

### Typography (STEP 2) — `…/presentation/theme/Theme.kt`

- [x] T009 Add `GoogleFont.Provider` (authority `com.google.android.gms.fonts`, package `com.google.android.gms`) and `val Inter`/`val Cairo` `FontFamily(Font(GoogleFont(...), provider, weight))` for weights 400/600/700/800, with `val AppFontFamily = Inter`, in `…/presentation/theme/Theme.kt` (depends on T001, T002)
- [x] T010 Rebuild `SafeGuardTypography` on Inter defining all 15 M3 styles to the brand ramp (displayLarge 30/38/700, headlineLarge 24/32/700, headlineMedium 20/28/600, titleLarge 17/24/600, titleMedium 16/24/600, bodyLarge 15/22/400, bodyMedium 14/20/400, bodySmall 13/18/400, labelLarge 16/24/600, labelMedium 12/16/600, labelSmall 11/16/600; remaining filled proportionally) in `…/presentation/theme/Theme.kt` (depends on T009)
- [x] T011 Add `@Composable fun rememberAppFontFamily(): FontFamily` returning Cairo when `LocalLayoutDirection == Rtl` or locale is Arabic, else Inter, in `…/presentation/theme/Theme.kt` (depends on T009)
- [x] T012 Add a "type ramp" `@Preview` (one Text per style) in light + dark in `…/presentation/theme/Theme.kt`

### Shapes, dimens, responsive, motion (STEP 3–4)

- [x] T013 Update `SafeGuardShapes` (xs 4 / sm 8 / md 12 / lg 16 / xl 24) and add `val PillShape = RoundedCornerShape(percent = 50)` + `val BottomSheetShape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)` in `…/presentation/theme/Theme.kt`
- [x] T014 Add new `SafeGuardDimens` tokens (`screenPadding` 20, `gutter` 16, `stackSm` 8, `stackMd` 16, `stackLg` 24, `listItemMinHeight` 56, `listSeparatorInset` 16) keeping all existing icon/avatar/button-height/progress/elevation tokens, in `…/presentation/theme/Theme.kt`
- [x] T015 [P] Create `…/presentation/theme/Responsive.kt`: `enum ScreenWidth { Compact, Medium, Expanded }`, `@Composable fun rememberScreenWidth()`, `Modifier.responsiveContentWidth(width)` (cap 600.dp, center on Medium/Expanded), `responsiveScreenPadding(width)` → 20/24/32.dp; add a sample card `@Preview` at 360.dp and 840.dp
- [x] T016 [P] Create `…/presentation/theme/Motion.kt`: extend `SafeGuardAnimations`, `Modifier.shimmerEffect()`, `Modifier.fadeScaleIn(visible: () -> Boolean)` (animateFloatAsState + `graphicsLayer { alpha = a; scaleX = scaleY = 0.96f + 0.04f*a }`), staggered list-entrance helper (50ms via graphicsLayer), primary-button press-scale; add a `@Preview` of shimmer + fadeScaleIn

### Design-system components (STEP 5) — new package `…/presentation/designsystem/`

- [x] T017 [P] Create `HarisButtons.kt` with `HarisPrimaryButton` (PillShape, primary/onPrimary, 56dp, labelLarge, press-scale, disabled), `HarisSecondaryButton` (surfaceContainerHigh, secondary/tertiary text, pill), `HarisGhostButton` (transparent, tertiary text) + light/dark `@Preview` in `…/presentation/designsystem/HarisButtons.kt`
- [x] T018 [P] Create `HarisTextField` (filled surfaceContainerHigh, 1dp outlineVariant, 12dp radius, label above field, helper + error slots) + light/dark `@Preview` in `…/presentation/designsystem/HarisTextField.kt`
- [x] T019 [P] Create `HarisCard` (16dp radius, surfaceContainer + 1dp outlineVariant in dark, top-right status slot Safe=success / Warning=warning|error) + light/dark `@Preview` in `…/presentation/designsystem/HarisCard.kt`
- [x] T020 [P] Create `HarisChip` (pill default; sm-radius `Filter` variant) + light/dark `@Preview` in `…/presentation/designsystem/HarisChip.kt`
- [x] T021 [P] Create `HarisSwitch` (primary teal when on) + light/dark `@Preview` in `…/presentation/designsystem/HarisSwitch.kt`
- [x] T022 [P] Create `HarisListItem` (min 56dp, separator inset 16dp from edges) + light/dark `@Preview` in `…/presentation/designsystem/HarisListItem.kt`
- [x] T023 [P] Create `HarisGradientHeader` (brand gradient #0E6A86→#2799A5 banner) + light/dark `@Preview` in `…/presentation/designsystem/HarisGradientHeader.kt`
- [x] T024 [P] Recolor existing `…/presentation/components/*.kt` (Buttons, Cards, ChildFriendlyComponents, CircularProgressDisplay, EmptyStates, EnhancedComponents, LoadingStates, PullToRefresh) to theme tokens — remove every hardcoded color literal
- [x] T025 Run `./gradlew assembleDebug` — foundation must compile green (depends on all of Phase 2)

**Checkpoint**: Full Haris design system available (tokens + components). Screen sweeps can begin.

---

## Phase 3: User Story 1 - Consistent Haris brand identity across the whole app (Priority: P1) 🎯 MVP

**Goal**: Every Compose screen + lock screen presents the Haris brand (petrol-teal primary, gold accent, gradient header, pill buttons, 16dp cards, tonal surfaces) using only tokens/components, with no behavior/navigation change.

**Independent Test**: Launch and navigate every screen; confirm Haris styling everywhere and no legacy/hardcoded colors; actions and navigation behave exactly as before.

> Each task replaces hardcoded colors/sizes with tokens, `SafeGuardDimens`, new shapes, and Step-5 components; applies 20dp screen padding, 16/24dp vertical rhythm, 56dp tap targets; adds light+dark `@Preview` if missing. State/actions/navigation/ViewModels unchanged.

- [x] T026 [P] [US1] Sweep `…/presentation/auth/LoginScreen.kt` (HarisGradientHeader, pill buttons, HarisTextField, role-selection cards using primaryContainer when selected) + light/dark `@Preview`
- [x] T027 [P] [US1] Sweep `…/presentation/auth/RegisterScreen.kt` (same auth treatment) + light/dark `@Preview`
- [x] T028 [P] [US1] Sweep `…/presentation/dashboard/DashboardScreen.kt` (screen-time summary as gradient header + display type, Top Apps, Recent Alerts) + light/dark `@Preview`
- [x] T029 [P] [US1] Sweep `…/presentation/devices/DevicesScreen.kt` (HarisListItem + status colors) + light/dark `@Preview`
- [x] T030 [P] [US1] Sweep `…/presentation/alerts/AlertsScreen.kt` (severity colors + filter chips; KEEP stacked-count rendering) + light/dark `@Preview`
- [x] T031 [P] [US1] Sweep `…/presentation/settings/SettingsScreen.kt` (rows + logout confirmation dialog restyled) + light/dark `@Preview`
- [x] T032 [P] [US1] Sweep `…/presentation/settings/TextMonitoringSettingsScreen.kt` + light/dark `@Preview`
- [x] T033 [P] [US1] Sweep `…/presentation/setup/PermissionsSetupScreen.kt` (rationale cards restyled; KEEP "open system settings" behavior for dangerous permissions — do NOT convert to in-app dialogs) + light/dark `@Preview`
- [x] T034 [P] [US1] Sweep `…/presentation/lockscreen/LockScreenActivity.kt` content (calm high-contrast brand surface, minimal motion) + light/dark `@Preview`
- [x] T035 [P] [US1] Sweep `…/presentation/children/ChildrenScreen.kt` + light/dark `@Preview`
- [x] T036 [P] [US1] Sweep `…/presentation/blacklist/BlacklistScreen.kt` + light/dark `@Preview`
- [x] T037 [P] [US1] Sweep `…/presentation/devicesetup/DeviceSetupScreen.kt` + light/dark `@Preview`
- [x] T038 [P] [US1] Sweep `…/presentation/imagereview/ImageReviewScreen.kt` + light/dark `@Preview`
- [x] T039 [P] [US1] Sweep `…/presentation/screentimelimits/ScreenTimeLimitsScreen.kt` + light/dark `@Preview`
- [x] T040 [P] [US1] Sweep `…/presentation/wordlist/WordListScreen.kt` + light/dark `@Preview`
- [x] T041 [US1] Apply motion tastefully (dashboard cards `fadeScaleIn` on first appearance; devices/alerts shimmer skeletons while loading; lock screen calm fade only) across the swept screens, then run `./gradlew assembleDebug`

**Checkpoint**: All screens render the Haris brand in the default theme; MVP shippable.

---

## Phase 4: User Story 2 - Dark theme as default, full light parity (Priority: P2)

**Goal**: App follows the device dark/light preference with dark as the canonical brand design; both schemes render correctly everywhere, including system chrome.

**Independent Test**: Device in dark → app dark; device in light → every screen legible in light; status bar/window match the active theme.

- [x] T042 [US2] Change `SafeGuardTheme(darkTheme: Boolean = isSystemInDarkTheme(), …)` (was hardcoded `false`); keep `dynamicColor = false`; keep the `SideEffect` status-bar wiring, in `…/presentation/theme/Theme.kt`
- [x] T043 [US2] Create `app/src/main/res/values-night/themes.xml` with dark surface `#0F1719` and `windowLightStatusBar = false`
- [x] T044 [US2] Dual-theme verification pass: review every screen's light+dark `@Preview` and run on light + dark devices; fix any mis-mapped/unreadable color to the correct token; then `./gradlew assembleDebug`

**Checkpoint**: Dark-default + light parity verified across all screens.

---

## Phase 5: User Story 3 - Bilingual typography and correct RTL (Priority: P3)

**Goal**: Inter for Latin, Cairo for Arabic/RTL, with fully mirrored right-to-left layout.

**Independent Test**: English locale → Inter everywhere; Arabic locale → Cairo + mirrored layout (start/end insets, alignment, list separators).

- [x] T045 [US3] Ensure text rendering uses `rememberAppFontFamily()` where locale-specific family is needed; confirm Inter applied across the type scale in Latin locale (`…/presentation/` screens + components)
- [x] T046 [US3] RTL-correctness pass across all swept screens/components: replace any left/right with start/end, verify directional padding/insets and alignment mirror correctly
- [x] T047 [US3] Arabic-locale verification: confirm Cairo renders and layout mirrors on every screen; fix offenders; then `./gradlew assembleDebug`

**Checkpoint**: Bilingual + RTL correct.

---

## Phase 6: User Story 4 - Accessible, legible interface (Priority: P3)

**Goal**: WCAG AA body-text contrast in both themes; meaningful contentDescriptions on actionable icons/images, none on decorative.

**Independent Test**: Contrast check passes on body text both themes; screen reader announces actionable icons and skips decorative graphics.

- [x] T048 [US4] contentDescription coverage sweep across `…/presentation/` screens + `…/presentation/designsystem/` + `…/presentation/components/`: `stringResource`-backed descriptions on actionable icons/images, `null` on decorative graphics
- [x] T049 [US4] WCAG AA contrast audit of body-text-on-surface pairings in both themes; snap any failing pairing to the nearest accessible on-token (no off-palette colors); then `./gradlew assembleDebug`

**Checkpoint**: Accessibility gate met.

---

## Phase 7: User Story 5 - Comfortable layout on phones and larger screens (Priority: P4)

**Goal**: Compact = full-width + standard padding; Medium/Expanded = content capped at 600dp, centered, larger padding.

**Independent Test**: Render content screens at narrow and wide widths; large widths cap and center content rather than stretch.

- [x] T050 [US5] Apply `Modifier.responsiveContentWidth(rememberScreenWidth())` and `responsiveScreenPadding(...)` to content screens (dashboard, devices, alerts, settings, children, blacklist, screentimelimits, wordlist, imagereview) in `…/presentation/`
- [x] T051 [US5] Add/confirm responsive `@Preview` at 360dp and 840dp on a representative content screen; verify cap+center on large width; then `./gradlew assembleDebug`

**Checkpoint**: Responsive behavior verified.

---

## Phase 8: Polish & Cross-Cutting Concerns (STEP 7 — Verify)

- [x] T052 [P] Grep the presentation layer for `Color(0xFF` and stray `#` literals; replace any remaining with tokens (`app/src/main/java/com/safeguard/parentalcontrol/presentation/`)
- [x] T053 [P] Remove now-unused bundled-font references (Bricolage/Hanken) from code; confirm `FontFamily.Default` does not appear in the product (bundled `.ttf` files may remain in `res/font/` but must be unreferenced)
- [x] T054 Output `CHANGED_FILES.md` grouped by theme / resources / components / screens
- [x] T055 Confirm NONE modified: ViewModels, repositories, use cases, DI modules, services, workers, receivers, networking, `ApiService`, navigation routes/state/actions; package name `com.safeguard.parentalcontrol` and existing class/composable names unchanged
- [x] T056 Run `./gradlew assembleDebug` and `./gradlew lintDebug`; fix any new warnings introduced by this work
- [x] T057 Run the `quickstart.md` end-to-end validation (dark default, brand sweep, RTL/bilingual, motion, permissions-settings preserved, alerts stacked-count preserved)

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: no dependencies — start immediately.
- **Foundational (Phase 2)**: depends on Setup — BLOCKS all user stories. Must end green (T025).
- **US1 (Phase 3)**: depends on Foundational. The MVP.
- **US2 (Phase 4)**: depends on Foundational; best after US1 so dual-theme verification covers swept screens.
- **US3 (Phase 5)**, **US4 (Phase 6)**, **US5 (Phase 7)**: each depends on Foundational; in practice run after US1 since they revisit the swept screen files (same-file passes → sequential, not parallel across these phases).
- **Polish (Phase 8)**: after all desired stories.

### User Story Dependencies

- US1 → independent (only needs foundation).
- US2/US3/US4/US5 → each independently testable, but operate over the US1-swept screen files; sequence them after US1 to avoid same-file churn. No story depends on another story's *logic*.

### Within Foundational

- T003 → T004, T006 (brand vals before schemes/semantics).
- T001 + T002 → T009 → T010, T011 (deps/certs before fonts before typography/selector).
- T017–T024 are independent files ([P]); T025 gates the whole phase.

### Parallel Opportunities

- **Phase 1**: T002 ∥ T001.
- **Phase 2**: T007 ∥ T008 (XML) run alongside Theme.kt edits; T015, T016 (Responsive/Motion) and T017–T024 (each designsystem file + components recolor) all ∥ — different files.
- **Phase 3 (US1)**: T026–T040 are all ∥ — one file each. T041 (motion application) after them.

---

## Parallel Example: Foundational components (Phase 2)

```bash
# All new design-system components are different files — build in parallel:
Task: "Create HarisButtons.kt …/presentation/designsystem/HarisButtons.kt"
Task: "Create HarisTextField.kt …/presentation/designsystem/HarisTextField.kt"
Task: "Create HarisCard.kt …/presentation/designsystem/HarisCard.kt"
Task: "Create HarisChip.kt …/presentation/designsystem/HarisChip.kt"
Task: "Create HarisSwitch.kt …/presentation/designsystem/HarisSwitch.kt"
Task: "Create HarisListItem.kt …/presentation/designsystem/HarisListItem.kt"
Task: "Create HarisGradientHeader.kt …/presentation/designsystem/HarisGradientHeader.kt"
```

## Parallel Example: US1 screen sweep (Phase 3)

```bash
# 15 screen files, no cross-dependencies — sweep in parallel:
Task: "Sweep LoginScreen.kt"   ;  Task: "Sweep RegisterScreen.kt"
Task: "Sweep DashboardScreen.kt" ; Task: "Sweep DevicesScreen.kt"
Task: "Sweep AlertsScreen.kt"  ;  Task: "Sweep SettingsScreen.kt"
# … through WordListScreen.kt
```

---

## Implementation Strategy

### MVP First (User Story 1)

1. Phase 1 Setup → 2. Phase 2 Foundational (design system, ends green) → 3. Phase 3 US1 brand sweep → **STOP & VALIDATE**: every screen on-brand in the default theme, zero behavior change. Shippable MVP.

### Incremental Delivery

Foundation → US1 (brand) → US2 (dark/light parity) → US3 (typography/RTL) → US4 (a11y AA) → US5 (responsive) → Polish. Each phase ends with `assembleDebug` green and adds value without breaking the prior.

### Notes

- `[P]` = different files, no incomplete-task dependency.
- `assembleDebug` must pass after every STEP/phase (spec FR-020); `lintDebug` at the end (T056).
- No automated tests requested — `@Preview` (light+dark) + build + manual sweep are the verification surface.
- Hard rule: presentation/theme + `res/` only. Never touch ViewModels/repos/use cases/DI/services/workers/receivers/networking/ApiService/navigation.
- Commit after each task or logical group.
