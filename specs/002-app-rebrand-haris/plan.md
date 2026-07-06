# Implementation Plan: App Rebrand to "Haris"

**Branch**: `002-app-rebrand-haris` | **Date**: 2026-06-26 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/002-app-rebrand-haris/spec.md`

## Summary

Rebrand the existing Android app from "SafeGuard" to "Haris": rename the user-facing display name and replace all brand artwork (launcher icon + adaptive foreground/background, themed monochrome layer, startup splash logo, in-app brand logo, and a 512px store icon) — all derived from a single 1024×1024 source logo (`C:\Users\Dell\Downloads\ui_system_design\logo.png`). Presentation-only: no change to the application id `com.safeguard.parentalcontrol`, so existing installs update in place. Raster assets are generated from the source PNG using .NET `System.Drawing` driven from PowerShell (no ImageMagick/Inkscape present). A startup splash is added via `androidx.core:core-splashscreen` for consistent behavior down to the project's `minSdk 26`.

## Technical Context

**Language/Version**: Kotlin (Jetpack Compose, Material3); Groovy Gradle DSL

**Primary Dependencies**: AndroidX (core-ktx 1.12.0, activity-compose 1.8.2), Compose BOM 2023.10.01, Hilt 2.51.1, navigation-compose 2.7.6. **Adds** `androidx.core:core-splashscreen:1.0.1`

**Storage**: N/A (no data-layer change; brand assets are static resources)

**Testing**: Manual/visual verification per `quickstart.md` (asset rendering, mask shapes, themes, in-place update). Existing JUnit/Compose test infra unchanged; no new unit tests warranted for static assets.

**Target Platform**: Android, `minSdk 26`, `compileSdk 34`, `targetSdk 34`

**Project Type**: Mobile app (single Android module `:app`)

**Performance Goals**: No runtime perf target; splash MUST not add perceptible cold-start delay beyond the existing window. Asset file sizes kept reasonable (density PNGs only at required sizes).

**Constraints**: Source logo is opaque, square, 1024×1024 (no alpha) → monochrome themed layer requires a derived silhouette (see research). Application id MUST remain `com.safeguard.parentalcontrol` (FR-010). Mark MUST stay within adaptive-icon safe zone (no mask clipping).

**Scale/Scope**: ~1 module, ~6 string edits, ~15 generated raster files (5 densities × {launcher foreground, round foreground} + splash + in-app logo) + 1 store icon + manifest/theme/gradle edits. No new screens.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Assessment | Status |
|-----------|------------|--------|
| I. Child Safety & Privacy First | No data collected, stored, or transmitted. Purely visual/string assets. No monitoring surface touched. | PASS |
| II. MVVM + Clean Architecture | No architectural change. Only resources, manifest, theme, gradle, and a one-line `installSplashScreen()` call in the existing launcher `MainActivity`. No new ViewModel/repository. | PASS |
| III. Two-Role Architecture (Parent/Child) | Branding is role-agnostic; both roles see the same name/icon. No role logic changed. | PASS |
| IV. Native Services & Permission Hygiene | No new permissions, services, or background work. Splash uses a standard AndroidX library. | PASS |
| V. Defense-in-Depth Security & Secrets | No secrets, network, or auth change. | PASS |

**Result (initial)**: PASS — no violations. Complexity Tracking not required.

**Result (post-Phase 1 re-check)**: PASS — design introduces only static resources, one starting theme, and one `installSplashScreen()` call. No principle affected.

## Project Structure

### Documentation (this feature)

```text
specs/002-app-rebrand-haris/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output (asset inventory + mappings)
├── quickstart.md        # Phase 1 output (visual validation guide)
├── contracts/
│   └── brand-assets.md  # Phase 1 output (asset contract: names, sizes, formats)
└── tasks.md             # Phase 2 output (/speckit-tasks — NOT created here)
```

### Source Code (repository root)

```text
app/src/main/
├── AndroidManifest.xml                     # android:label, splash theme (no app id change)
├── res/
│   ├── values/
│   │   ├── strings.xml                     # app_name + 5 user-facing "SafeGuard" strings → "Haris"
│   │   ├── colors.xml                      # + brand_icon_background (sampled dominant color)
│   │   └── themes.xml                      # + Theme.Haris.Starting (splash)
│   ├── mipmap-anydpi-v26/
│   │   ├── ic_launcher.xml                 # adaptive: foreground + background + monochrome
│   │   └── ic_launcher_round.xml           # same, round
│   ├── mipmap-mdpi/ … mipmap-xxxhdpi/      # NEW raster: ic_launcher_foreground.png per density
│   ├── drawable/
│   │   ├── ic_launcher_background.xml       # solid brand_icon_background (replaces current)
│   │   ├── ic_launcher_monochrome.xml       # derived silhouette (themed icons, FR-011)
│   │   ├── ic_brand_logo.png                # in-app brand logo (FR-005)
│   │   └── splash_logo.png                  # splash icon (FR-004)
│   └── ...
└── java/com/safeguard/parentalcontrol/presentation/MainActivity.kt  # installSplashScreen()

app/build.gradle                            # + androidx.core:core-splashscreen:1.0.1

store/
└── ic_store_512.png                        # NEW high-res store/marketing icon (FR-006), repo deliverable
```

**Structure Decision**: Single existing Android module `:app`. The launcher icon is currently a pure-vector adaptive icon (white shield). It is replaced by raster foreground PNGs (5 densities) generated from the source logo, a solid-color background drawable, and a monochrome layer. App name and the five secondary user-facing strings are updated in `strings.xml`. Splash is added via `core-splashscreen` + a starting theme, wired in the existing `MainActivity` (the `LAUNCHER` activity). The store icon lives outside `app/` as a marketing deliverable under `store/`.

## Complexity Tracking

> No constitution violations. Section intentionally empty.
