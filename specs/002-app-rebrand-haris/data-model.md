# Phase 1 Data Model: App Rebrand to "Haris"

No runtime/persisted data. "Entities" here are the static brand artifacts and string values produced/changed by this feature. Source of truth for every asset is the single **Source Logo**.

## Entity: Source Logo

| Attribute | Value |
|-----------|-------|
| Path | `C:\Users\Dell\Downloads\ui_system_design\logo.png` |
| Dimensions | 1024 × 1024 |
| Pixel format | 32bpp ARGB, **opaque** (no usable alpha) |
| Shape | Square, full-bleed |
| Role | Master from which all derived assets are generated |

## Entity: App Display Name

| Attribute | Value |
|-----------|-------|
| Resource | `@string/app_name` |
| New value | `Haris` (exact, capitalised proper noun — FR-001) |
| Manifest binding | `android:label="@string/app_name"` (unchanged binding) |

## Entity: Brand Color Token

| Attribute | Value |
|-----------|-------|
| Resource | `@color/brand_icon_background` (new in `colors.xml`) |
| Value | Dominant color sampled from Source Logo (R4); reuse `@color/primary` if near-identical |
| Used by | Adaptive icon background drawable, splash window background |

## Entity: Brand Asset Set (derived deliverables)

| Asset | File(s) | Size / Format | Requirement |
|-------|---------|---------------|-------------|
| Adaptive icon descriptor | `mipmap-anydpi-v26/ic_launcher.xml`, `ic_launcher_round.xml` | XML: foreground + background + monochrome | FR-002, FR-003 |
| Launcher foreground | `mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher_foreground.png` | 108,162,216,324,432 px; mark inset ~70% on transparent | FR-002, FR-003, FR-009 |
| Icon background | `drawable/ic_launcher_background.xml` | Vector, solid `@color/brand_icon_background` | FR-003 |
| Monochrome layer | `drawable/ic_launcher_monochrome.xml` (or `.png`) | Single-color silhouette, alpha-masked, safe-zone inset | FR-011 |
| Splash logo | `drawable/splash_logo.png` | Mark within inner ~2/3 of splash icon area | FR-004 |
| In-app brand logo | `drawable/ic_brand_logo.png` | Sized for branded screens (e.g. auth header) | FR-005 |
| Store icon | `store/ic_store_512.png` | 512 × 512, full-bleed | FR-006 |

### Validation rules (apply to every derived asset)

- MUST preserve source aspect ratio — no stretch/distortion (FR-009).
- Launcher/splash/monochrome marks MUST stay within their context's safe zone — no mask clipping (FR-003, SC-002).
- MUST be a downscale of the 1024 master — no upscaling artifacts (SC-002).
- Launcher/splash/in-app/store MUST be visually recognizable as one brand (SC-005).
- Smallest launcher size MUST remain legible (SC-003).

## Entity: User-facing String Set (rename — FR-007)

All in `app/src/main/res/values/strings.xml`. Replace product name "SafeGuard" → "Haris":

| Resource | Before | After |
|----------|--------|-------|
| `app_name` | `SafeGuard` | `Haris` |
| `notification_monitoring_title` | `SafeGuard Active` | `Haris Active` |
| `permission_usage_stats_message` | `SafeGuard needs access to app usage data…` | `Haris needs access to app usage data…` |
| `permission_accessibility_message` | `SafeGuard needs accessibility access…` | `Haris needs accessibility access…` |
| `permission_vpn_message` | `SafeGuard needs VPN permission…` | `Haris needs VPN permission…` |
| `accessibility_service_description` | `SafeGuard monitors text content…` | `Haris monitors text content…` |

### Out of scope (NOT changed — FR-010)

- Application id / package: `com.safeguard.parentalcontrol`
- Theme style id `Theme.SafeGuard`, class names, internal identifiers (non-user-facing)
- Functional/utility icons (`ic_google`, severity icons, etc.)
