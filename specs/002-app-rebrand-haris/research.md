# Phase 0 Research: App Rebrand to "Haris"

All Technical Context unknowns resolved below. No `NEEDS CLARIFICATION` remain.

## R1. Raster asset generation tooling

- **Decision**: Generate all PNG assets from the 1024×1024 source with **.NET `System.Drawing` driven by PowerShell** (`System.Drawing.Bitmap` + `Graphics` with `HighQualityBicubic` interpolation). Provide one reusable generation script under the feature's scratch/tooling area; commit only the produced PNGs.
- **Rationale**: `magick`/`inkscape` are NOT installed; the `convert.exe` on PATH is the Windows filesystem tool, not ImageMagick. `System.Drawing` is present (verified) and handles high-quality downscale of an opaque PNG to all required densities and the 512 store icon.
- **Alternatives considered**:
  - Android Studio *Image Asset Studio* — produces correct densities + adaptive layers but is GUI/manual, not scriptable/reproducible here.
  - Vectorize logo to a `VectorDrawable` — source is a raster logo (photographic/complex fill likely); auto-trace quality unreliable. Rejected.
  - Install ImageMagick — avoids adding a toolchain dependency for a one-off asset gen. Rejected in favor of already-present `System.Drawing`.

## R2. Adaptive launcher icon composition

- **Decision**: Adaptive icon = **foreground** (logo mark scaled to the inner safe zone, ~66–70% of the 108dp canvas, centered, on transparent) + **background** (solid brand color drawable). Keep `ic_launcher.xml` / `ic_launcher_round.xml` in `mipmap-anydpi-v26`. Foreground delivered as density PNGs (mdpi→xxxhdpi) at 108dp; background stays a vector solid-color drawable.
- **Rationale**: Source is a full-bleed opaque square; placing it directly as foreground would let masks (circle/squircle) clip the mark. Insetting into the 72dp safe zone guarantees no clipping under any mask (SC-002). Solid-color background (R4) matches the chosen clarification.
- **Density target px (108dp canvas)**: mdpi 108, hdpi 162, xhdpi 216, xxhdpi 324, xxxhdpi 432. Mark drawn centered at ~70% width.
- **Alternatives considered**: full-bleed foreground (risk of clipping, rejected); single legacy square PNG only (loses adaptive masking + themed support, rejected).

## R3. Themed (monochrome) icon — FR-011

- **Decision**: Provide a **monochrome layer** referenced via `<monochrome>` in the adaptive icon. Because the source is opaque (no alpha), derive a **silhouette**: alpha-from-luminance threshold of the mark region → single flat-colored (system-tinted) shape, inset to the same safe zone. Produce as a small PNG (or hand-simplified vector if the silhouette is clean). Manual visual review required before commit.
- **Rationale**: Android 13+ themed icons require a single-color, alpha-masked drawable. An opaque square has no usable alpha, so a derived silhouette is mandatory. Devices < API 33 ignore `<monochrome>` and fall back to foreground/background unchanged (FR-011).
- **Risk/Mitigation**: Auto-threshold may produce a muddy silhouette for a detailed logo. Mitigation: review output; if poor, fall back to a simplified emblem of the logo (or the existing shield glyph re-colored) as the monochrome mark. Quality is judged by SC-003 legibility, not pixel-fidelity.
- **Alternatives considered**: omit monochrome (rejected — clarification chose to include); reuse current white shield (acceptable fallback only).

## R4. Background / splash color (sampled dominant) — clarification

- **Decision**: Compute the **dominant color** of the source logo (modal/average of opaque pixels via `System.Drawing` pixel scan, excluding near-white/near-black extremes) and store it as `@color/brand_icon_background` in `colors.xml`. Use it for the adaptive background drawable and the splash window background.
- **Rationale**: Matches Q2 clarification (solid brand color sampled from logo). Centralizing as a named color keeps icon + splash consistent (SC-005) and easy to tweak.
- **Note**: Existing `@color/primary` is `#1976D2`. The sampled value is computed at implementation time; if it lands near `primary`, reuse `primary` to avoid a near-duplicate token.

## R5. Splash screen mechanism

- **Decision**: Add **`androidx.core:core-splashscreen:1.0.1`**. Define `Theme.Haris.Starting` (parent `Theme.SplashScreen`) with `windowSplashScreenBackground = @color/brand_icon_background` and `windowSplashScreenAnimatedIcon = @drawable/splash_logo`, set `postSplashScreenTheme = @style/Theme.SafeGuard`. Apply the starting theme to the launcher activity in the manifest and call `installSplashScreen()` at the top of `MainActivity.onCreate()` before `super.onCreate()`.
- **Rationale**: `minSdk 26` < 31, so the platform SplashScreen API (Android 12+) is not universally available; the AndroidX backport gives one consistent splash from API 23+ (FR-004). Library is the Google-recommended path.
- **Splash icon sizing**: windowed splash icon is masked to a circle; keep the logo mark within the inner ~2/3 of the 240–288dp icon area to avoid clipping (mirrors R2 safe-zone reasoning).
- **Alternatives considered**: custom Compose splash composable (extra first-frame/flash, more code, rejected); platform-only SplashScreen with no backport (inconsistent pre-12 behavior, rejected); `windowBackground` image hack (deprecated pattern, rejected).

## R6. Renaming scope — FR-001 / FR-007

- **Decision**: Change `@string/app_name` to **"Haris"** and update the five other user-facing strings containing "SafeGuard" (`notification_monitoring_title` → "Haris Active", and the three permission messages + accessibility description → swap "SafeGuard" for "Haris"). **Do NOT** change the application id, package name `com.safeguard.parentalcontrol`, theme style id `Theme.SafeGuard`, class names, or internal identifiers (FR-010 — keeps in-place update; non-user-facing).
- **Rationale**: FR-007 targets user-visible occurrences only. Renaming the application id would create a separate install (violates FR-010, SC-006). Internal `SafeGuard*` identifiers are invisible to users and risky/noisy to rename.
- **Verification**: `grep -rn "SafeGuard" app/src/main/res/values*/strings.xml` must return zero user-facing product-name hits post-change (SC-001).

## R7. In-app brand logo — FR-005

- **Decision**: Add `@drawable/ic_brand_logo` (PNG derived from source) and reference it on existing branded surfaces (e.g. the auth/sign-in header). Replace any current shield-as-brand usage on user-facing branded screens with the new logo. Keep functional/utility icons (e.g. `ic_google`, severity icons) unchanged.
- **Rationale**: FR-005/SC-005 require an in-app logo visually consistent with launcher/splash/store, all from the same master.
- **Scope note**: Exact screens that show the brand mark are enumerated during `/speckit-tasks` by grepping current `ic_shield`/logo references on user-facing composables; only branding usages are swapped.

## R8. Store icon — FR-006

- **Decision**: Produce `store/ic_store_512.png` at **512×512** (Play Store listing spec), full-bleed from the 1024 master (downscale). Committed as a repo deliverable outside `app/` (not bundled in the APK).
- **Rationale**: 1024 master downscales cleanly to 512 with no upscaling artifacts (SC-002). Listing icon is full-bleed (no adaptive safe-zone needed).
