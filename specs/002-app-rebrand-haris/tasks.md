---

description: "Task list for App Rebrand to Haris"
---

# Tasks: App Rebrand to "Haris"

**Input**: Design documents from `specs/002-app-rebrand-haris/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/brand-assets.md, quickstart.md

**Tests**: NOT requested. This is a static-asset/string rebrand; verification is visual per `quickstart.md`. No automated test tasks generated.

**Organization**: Tasks grouped by user story (P1 launcher+name, P2 splash, P3 in-app/OS/store).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no incomplete dependencies)
- All paths relative to repo root `C:\Users\Dell\Documents\safeguard-android-NOOR\`

## Path Conventions

- Android module: `app/src/main/`
- Generation tooling (scratch, not committed to app): `specs/002-app-rebrand-haris/tooling/`
- Store deliverable: `store/`
- Source master: `C:\Users\Dell\Downloads\ui_system_design\logo.png`

---

## Phase 1: Setup (Shared Tooling)

**Purpose**: Reproducible asset-generation tooling (no ImageMagick/Inkscape; use .NET System.Drawing via PowerShell - research R1).

- [X] T001 [P] Create output dirs: `app/src/main/res/mipmap-mdpi/`, `mipmap-hdpi/`, `mipmap-xhdpi/`, `mipmap-xxhdpi/`, `mipmap-xxxhdpi/`, and `store/`
- [X] T002 Write PowerShell generation script `specs/002-app-rebrand-haris/tooling/generate-assets.ps1` using `System.Drawing` (HighQualityBicubic): downscale-to-size, inset-mark-on-transparent (safe-zone %), sample-dominant-color, luminance-threshold-silhouette - sourcing the logo (research R1-R4)

**Checkpoint**: Generation script runnable; produces files on demand.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Brand color token consumed by BOTH the icon background (US1) and splash background (US2).

**CRITICAL**: Blocks US1 and US2.

- [X] T003 Run script to compute dominant color of source logo; record hex value (research R4) -> `#084868`
- [X] T004 Add `<color name="brand_icon_background">#084868</color>` to `app/src/main/res/values/colors.xml`

**Checkpoint**: `@color/brand_icon_background` available.

---

## Phase 3: User Story 1 - Launcher icon & name (Priority: P1) [MVP]

**Goal**: Home screen / app drawer shows the new logo launcher icon (adaptive + themed) and the label "Haris".

**Independent Test**: Install build; app drawer shows new icon + label "Haris"; icon un-clipped under round/squircle/rounded-square masks; legible at mdpi (quickstart S1, S3 / AC-2, AC-3).

### Implementation for User Story 1

- [X] T005 [P] [US1] Generate adaptive foreground PNGs (mark inset ~66% on transparent) at 108/162/216/324/432 px into `app/src/main/res/mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher_foreground.png` (research R2)
- [X] T006 [P] [US1] Replace `app/src/main/res/drawable/ic_launcher_background.xml` with a solid `@color/brand_icon_background` vector drawable
- [X] T007 [P] [US1] Generate monochrome silhouette `app/src/main/res/drawable/ic_launcher_monochrome.png` via luminance/alpha threshold, safe-zone inset (research R3, FR-011)
- [X] T008 [US1] Update `ic_launcher.xml` and `ic_launcher_round.xml` to reference `@drawable/ic_launcher_background`, `@mipmap/ic_launcher_foreground`, and add `<monochrome android:drawable="@drawable/ic_launcher_monochrome"/>`
- [X] T009 [US1] Set `<string name="app_name">Haris</string>` in `app/src/main/res/values/strings.xml` (FR-001)
- [X] T010 [US1] Build `:app:assembleDebug` - PASS (resources merged, no missing-density warnings). On-device visual validate (S1/S3) -> see T025 note.

**Checkpoint**: Launcher icon + name fully rebranded; build-verified (MVP).

---

## Phase 4: User Story 2 - Splash on launch (Priority: P2)

**Goal**: Cold start shows the new logo on the brand background, then transitions into the app.

**Independent Test**: Cold-start app; splash shows `splash_logo` on `@color/brand_icon_background`; legible light + dark; no white flash / double splash (quickstart S2 / AC-4).

### Implementation for User Story 2

- [X] T011 [P] [US2] Add `implementation 'androidx.core:core-splashscreen:1.0.1'` to `app/build.gradle` (research R5)
- [X] T012 [P] [US2] Generate `app/src/main/res/drawable/splash_logo.png` (mark on transparent) from source (research R5)
- [X] T013 [US2] Add `Theme.Haris.Starting` (parent `Theme.SplashScreen`) to `themes.xml`: `windowSplashScreenBackground=@color/brand_icon_background`, `windowSplashScreenAnimatedIcon=@drawable/splash_logo`, `postSplashScreenTheme=@style/Theme.SafeGuard`
- [X] T014 [US2] Set `android:theme="@style/Theme.Haris.Starting"` on `.presentation.MainActivity` in `AndroidManifest.xml`
- [X] T015 [US2] Call `installSplashScreen()` at top of `onCreate()` before `super.onCreate(...)` in `MainActivity.kt`
- [X] T016 [US2] Build PASS (compile + manifest merge). On-device visual validate (S2) -> see T025 note.

**Checkpoint**: Splash rebranded; build-verified.

---

## Phase 5: User Story 3 - In-app, OS surfaces & store (Priority: P3)

**Goal**: In-app branded screens, OS surfaces, and the store icon all show "Haris" + new logo; no "SafeGuard" remains on any user-facing surface.

**Independent Test**: Navigate branded screens, recents, settings, trigger notification; every product name reads "Haris"; store icon consistent (quickstart S4, S6 / AC-1, AC-5).

### Implementation for User Story 3

- [X] T017 [P] [US3] Generate `app/src/main/res/drawable/ic_brand_logo.png` from source (research R7, FR-005)
- [X] T018 [US3] Replace brand-mark usage on user-facing surfaces with `@drawable/ic_brand_logo` (LoginScreen header logo + "Haris"); left utility icons untouched. Extended FR-007 sweep: 46 hardcoded user-facing "SafeGuard" literals -> "Haris" across presentation/services/util/worker (class names, package, log tags preserved)
- [X] T019 [US3] Replace remaining user-facing "SafeGuard" -> "Haris" in `strings.xml` (notification_monitoring_title -> "Haris Active", 3 permission messages, accessibility description) (FR-007)
- [X] T020 [P] [US3] Generate `store/ic_store_512.png` at 512x512 full-bleed from the 1024 master (research R8, FR-006)
- [X] T021 [US3] Build PASS (full APK assembled). On-device visual validate (S4, S6) -> see T025 note.

**Checkpoint**: All three stories implemented; build-verified.

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Final verification spanning stories; confirm in-place update and provenance.

- [X] T022 Verify SC-001: zero user-facing "SafeGuard" in `strings.xml` and in presentation UI literals (AC-1) - CLEAN
- [ ] T023 Validate quickstart Scenario 5 in-place update (install prior "SafeGuard" build then "Haris" over it -> one app, no duplicate, launches as Haris) (AC-6, FR-010) - **DEVICE REQUIRED, not runnable in this environment**
- [X] T024 [P] Add `specs/002-app-rebrand-haris/tooling/README.md` documenting regeneration (source, invocation, sampled color)
- [ ] T025 Full `quickstart.md` on-device pass (AC-1..AC-7) and sign-off - **DEVICE/EMULATOR REQUIRED, not runnable in this environment**

---

## Verification status (this environment)

- **Build**: `:app:assembleDebug` -> **BUILD SUCCESSFUL** (`app/build/outputs/apk/debug/app-debug.apk`). Compile, resource merge, manifest merge, packaging all pass.
- Build requires `app/google-services.json` (gitignored Firebase secret, absent here). A temporary dummy was used only to compile/package, then deleted. The real file must be present for the user's own builds.
- **Not executed (needs a device/emulator)**: on-device visual scenarios (icon masks, themed icon, splash light/dark, recents/settings/notification surfaces) and the in-place-update test (T023, T025). Code/resources are build-verified; these confirm runtime appearance only.

---

## Dependencies & Execution Order

- **Setup (P1)** -> **Foundational (P2, color token)** -> US1/US2 (need token); US3 needs only the generation script.
- Within stories: T008 after T005+T006+T007; T013 after T012+T004; T014 after T013; T015 after T011; T018 after T017; T019 after T009 (same `strings.xml`).

## Notes

- Application id `com.safeguard.parentalcontrol`, theme id `Theme.SafeGuard`, all class/identifier names UNCHANGED (FR-010).
- All raster assets are downscales of the 1024 master - never upscaled.
- Old `drawable/ic_launcher_foreground.xml` (vector shield) retained: still referenced as the VPN notification small icon; different resource namespace from `@mipmap/ic_launcher_foreground`, no conflict.
