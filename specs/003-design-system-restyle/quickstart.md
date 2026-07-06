# Quickstart: Verify the Haris Design-System Restyle

Validation/run guide. Implementation details live in `tasks.md` (Phase 2) and the code.

## Prerequisites
- JDK 21, Android SDK (`compileSdk 35`), repo cloned, local secrets present (`google-services.json`, `local.properties`) — unchanged by this feature.
- Build from repo root with the Gradle wrapper.

## Build after every step (hard rule)
```bash
./gradlew assembleDebug
```
Must succeed after each of STEP 1–7. Final verification also runs lint:
```bash
./gradlew lintDebug
```

## Per-step acceptance

| Step | What to confirm |
|---|---|
| 1 — Colors | `Theme.kt` brand vals renamed `Haris*`; both schemes use the Haris values incl. `surfaceContainer*` tiers; `SemanticColors` + helpers recolored; `colors.xml`/`themes.xml` updated; `values-night/themes.xml` created. `assembleDebug` green. |
| 2 — Typography | `ui-text-google-fonts` added; `font_certs.xml` + `GoogleFont.Provider` present; `Inter`/`Cairo` families + 15-style `Typography`; `rememberAppFontFamily()`; type-ramp `@Preview` (light+dark). |
| 3 — Shapes/spacing/responsive | `PillShape` + `BottomSheetShape`; new `SafeGuardDimens` tokens (existing kept); `material3-window-size-class` added; `Responsive.kt` with `ScreenWidth`, `rememberScreenWidth()`, `responsiveContentWidth`, `responsiveScreenPadding`; sample card `@Preview` at 360.dp + 840.dp. |
| 4 — Motion | `Motion.kt`: `shimmerEffect()`, `fadeScaleIn{}`, staggered entrance, press-scale; shimmer + fadeScaleIn `@Preview`. |
| 5 — Components | `presentation/designsystem/` has all 9 `Haris*` components, each with light+dark `@Preview` + realistic data. |
| 6 — Screen sweep | All 15 screens + lock screen restyled to tokens/components; each Screen has light+dark `@Preview`; 20.dp padding, 56.dp tap targets, `contentDescription` coverage, RTL-correct. |
| 7 — Verify | `CHANGED_FILES.md` grouped (theme/resources/components/screens); AA contrast confirmed both themes; no stray `Color(0xFF`/`#` in presentation; `assembleDebug` + `lintDebug` green. |

## End-to-end visual validation

1. **Dark default**: run on a device/emulator set to **dark** mode → app renders dark Haris theme. Set device to **light** → app renders light scheme, every screen legible. (Follows system; no in-app toggle.)
2. **Brand sweep**: navigate auth → dashboard → devices → alerts → settings → permissions setup → lock screen, plus children/blacklist/devicesetup/imagereview/screentimelimits/wordlist/text-monitoring. Confirm petrol-teal primary, gold accent, gradient header on dashboard summary, pill buttons, 16.dp cards, tonal-stepped surfaces.
3. **RTL/bilingual**: switch device to **Arabic** → text renders in **Cairo**, layout mirrors (start/end insets, alignment, list separators). Latin locale → **Inter**.
4. **Loading/motion**: trigger devices/alerts loading → shimmer skeletons; dashboard cards fade-scale in on first appearance; lock screen shows only a calm fade.
5. **Permissions behavior preserved**: on `PermissionsSetupScreen`, tapping a dangerous-permission rationale still opens the **system settings** screen (not an in-app dialog).
6. **Alerts rendering preserved**: alerts list keeps stacked-count rendering; severity colors + filter chips restyled.

## Integrity checks (must hold)
```bash
# No hardcoded colors left in the presentation layer
grep -rn "Color(0xFF" app/src/main/java/com/safeguard/parentalcontrol/presentation || echo "clean"
```
- Confirm **unmodified**: ViewModels, repositories, use cases, DI modules, services, workers, receivers, networking, `ApiService`, navigation routes/state/actions.
- Confirm package name `com.safeguard.parentalcontrol` and existing class/composable names unchanged; only additive `HarisTheme` alias + new `Haris*` symbols.
- `aaptOptions { noCompress "tflite" }` still present in `app/build.gradle`; Compose BOM still `2023.10.01`.

## Expected outcome
All 15 screens + lock screen present the Haris brand in light and dark, Inter/Cairo bilingual with correct RTL, AA-contrast body text, responsive on large widths, with zero behavioral/navigation regressions and a green `assembleDebug` + `lintDebug`.
