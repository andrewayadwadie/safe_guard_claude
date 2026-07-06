# Quickstart: Validate the "Haris" Rebrand

Visual/functional validation guide. Proves the rebrand end-to-end. Asset names, sizes, and acceptance IDs come from [contracts/brand-assets.md](./contracts/brand-assets.md); do not restate them here.

## Prerequisites

- Android Studio / `./gradlew` toolchain; `compileSdk 34`.
- A device or emulator (one API < 31 and one API 33+ to cover splash backport and themed icons).
- Source logo present at `C:\Users\Dell\Downloads\ui_system_design\logo.png`.
- All brand assets generated and placed per the contract (done in implementation/tasks phase).

## Build & install

```bash
# from repo root
./gradlew :app:assembleDebug
./gradlew :app:installDebug
```

Expected: build succeeds with no missing-density warnings (Build contract §4).

## Scenario 1 — Launcher icon & name (P1 / AC-1, AC-2, AC-3)

1. Open the app drawer / home screen.
2. **Expect**: label reads `Haris`; icon shows the new logo on the solid brand background.
3. Change the launcher icon shape (Settings → Home app → icon shape, or use multiple launchers) to round, squircle, rounded-square.
4. **Expect**: mark stays centered, un-clipped, undistorted at every shape (AC-2).
5. View on a low-density (mdpi/hdpi) device or shrink icon size.
6. **Expect**: mark still recognizable (AC-3).

## Scenario 2 — Splash on launch (P2 / AC-4)

1. Cold-start `Haris` (swipe app from recents first, then tap icon).
2. **Expect**: splash shows `splash_logo` centered on `@color/brand_icon_background`, then transitions into the app — no white flash, no second splash.
3. Toggle system Dark theme; repeat cold start.
4. **Expect**: logo remains legible in both themes (AC-4).

## Scenario 3 — Themed (monochrome) icon (FR-011 / AC-7)

1. On Android **13+**: enable Settings → Wallpaper & style → Themed icons.
2. **Expect**: Haris icon renders as the monochrome silhouette tinted to the wallpaper palette.
3. On a device **< API 33**: themed icons unavailable.
4. **Expect**: standard adaptive icon shown unchanged (no crash, no blank).

## Scenario 4 — In-app & OS surfaces (P3 / AC-1, AC-5)

1. Launch app; open any branded screen (e.g. sign-in/header).
2. **Expect**: `ic_brand_logo` shown; any visible product name reads `Haris`.
3. Open the recent-apps switcher.
4. **Expect**: task title reads `Haris`.
5. Open Settings → Apps → Haris.
6. **Expect**: app entry name + icon are the new brand.
7. Trigger a monitoring notification (or inspect `notification_monitoring_title`).
8. **Expect**: title reads `Haris Active`; no "SafeGuard" appears on any user-facing string (AC-1).

## Scenario 5 — In-place update (AC-6)

1. Install the previous "SafeGuard" build (same application id `com.safeguard.parentalcontrol`).
2. Install the rebuilt "Haris" build over it (`installDebug`, no uninstall).
3. **Expect**: exactly one app on the device (no duplicate); it launches and shows the `Haris` identity (AC-6).

## Scenario 6 — Brand consistency (AC-5)

1. Place launcher icon, splash logo, in-app logo, and `store/ic_store_512.png` side by side.
2. **Expect**: all read as the same brand — consistent mark, color, proportions.

## Sign-off

Feature passes when AC-1 … AC-7 in the asset contract all hold across the scenarios above.
