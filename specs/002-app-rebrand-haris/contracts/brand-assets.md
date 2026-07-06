# Brand Asset Contract: "Haris"

The "interface" this feature exposes is the set of OS- and user-facing brand surfaces. This contract fixes the exact resource names, formats, and acceptance conditions every implementer/verifier relies on. Tasks and quickstart reference this file rather than restating it.

## 1. Naming contract

| Surface | Contract |
|---------|----------|
| Launcher label, recent-apps, settings | Renders exactly `Haris` |
| In-app branded surfaces | Show `Haris` and `@drawable/ic_brand_logo` |
| Application id | UNCHANGED `com.safeguard.parentalcontrol` |

## 2. Resource contract (must exist, named exactly)

| Resource id | Type | Location | Required content |
|-------------|------|----------|------------------|
| `@string/app_name` | string | `values/strings.xml` | `Haris` |
| `@color/brand_icon_background` | color | `values/colors.xml` | sampled dominant color (hex) |
| `@mipmap/ic_launcher` | adaptive icon | `mipmap-anydpi-v26/` | `<foreground>` + `<background>` + `<monochrome>` |
| `@mipmap/ic_launcher_round` | adaptive icon | `mipmap-anydpi-v26/` | same three layers |
| `ic_launcher_foreground` | png ×5 | `mipmap-{mdpi…xxxhdpi}/` | logo mark, transparent surround, safe-zone inset |
| `@drawable/ic_launcher_background` | vector | `drawable/` | solid `@color/brand_icon_background` |
| `@drawable/ic_launcher_monochrome` | drawable | `drawable/` | single-color alpha silhouette |
| `@drawable/splash_logo` | png | `drawable/` | logo mark for splash icon |
| `@drawable/ic_brand_logo` | png | `drawable/` | in-app brand logo |
| `@style/Theme.Haris.Starting` | style | `values/themes.xml` | parent `Theme.SplashScreen`; bg + animated icon + post theme |
| `store/ic_store_512.png` | png file | `store/` | 512×512 full-bleed |

## 3. Manifest / theme contract

- `<application android:label="@string/app_name" android:icon="@mipmap/ic_launcher" android:roundIcon="@mipmap/ic_launcher_round">` — bindings unchanged.
- Launcher activity `.presentation.MainActivity` uses `android:theme="@style/Theme.Haris.Starting"`.
- `Theme.Haris.Starting` sets `postSplashScreenTheme=@style/Theme.SafeGuard`.
- `MainActivity.onCreate` calls `installSplashScreen()` before `super.onCreate(...)`.

## 4. Build contract

- `app/build.gradle` adds `implementation 'androidx.core:core-splashscreen:1.0.1'`.
- Project builds (`./gradlew :app:assembleDebug`) with no new warnings about missing density buckets.

## 5. Acceptance conditions (maps to Success Criteria)

| ID | Condition |
|----|-----------|
| AC-1 (SC-001) | `grep "SafeGuard" values*/strings.xml` returns no product-name occurrence; every user-facing surface reads `Haris`. |
| AC-2 (SC-002) | Launcher icon renders un-clipped, undistorted, no upscaling under round / squircle / rounded-square masks at all densities. |
| AC-3 (SC-003) | Icon mark recognizable at smallest launcher size (mdpi) on a low-density device. |
| AC-4 (SC-004) | Splash logo visible/legible on light and dark system backgrounds. |
| AC-5 (SC-005) | Launcher, splash, in-app, and store icon read as the same brand side-by-side. |
| AC-6 (SC-006) | Updating an existing `com.safeguard.parentalcontrol` install yields one app (no duplicate), launches showing `Haris`. |
| AC-7 (FR-011) | On Android 13+ themed-icons mode, monochrome layer tints to wallpaper; on < API 33, standard adaptive icon shows unchanged. |
