# Research: Arabic & English Localization with RTL Support

**Feature**: 008-arabic-english-localization | **Date**: 2026-07-07

## R1. In-app language switching mechanism

**Decision**: Dependency-free per-app locale via `attachBaseContext` wrapping + `Activity.recreate()`, backed by a raw `SharedPreferences` key. On API 33+, additionally sync the choice to the system via `LocaleManager.setApplicationLocales()` so the OS per-app language setting matches.

**Rationale**:
- The canonical `AppCompatDelegate.setApplicationLocales()` backport requires `androidx.appcompat` + converting `MainActivity` from `ComponentActivity` to `AppCompatActivity` (plus a `Theme.AppCompat`-descendant theme). The app is pure Compose with no appcompat dependency; the constitution locks dependency versions and adding appcompat solely for locale storage is unjustified risk (theme enforcement at runtime, splash-theme interaction).
- `attachBaseContext(LocaleHelper.wrap(newBase))` + `createConfigurationContext` works uniformly on API 26–35, covers Activities AND gives us an explicit path for background code (workers/services) via the same helper.
- Language preference must be readable **synchronously** inside `attachBaseContext` (runs before Hilt injection) → raw `SharedPreferences` read in a static helper. DataStore is async — unsuitable here.

**Alternatives considered**:
- `AppCompatDelegate.setApplicationLocales` + `autoStoreLocales`: rejected — new dependency, activity/theme migration, and it does NOT localize background `Context` on API < 33 anyway (workers would still need a manual helper).
- Compose-only recomposition with a `CompositionLocal` for strings: rejected — bypasses Android resource system, breaks notifications, plurals, and future `values-xx` additions.

## R2. String externalization

**Decision**: Standard Android resources — full English catalog in `app/src/main/res/values/strings.xml`, Arabic in `app/src/main/res/values-ar/strings.xml`. Compose reads via `stringResource(R.string.key)`; non-Compose code (workers, services, notifications) via `LocaleHelper.localizedContext(appContext).getString(...)`.

**Rationale**: Matches user request ("arabic and english files and call text from this file"), is the platform-native mechanism (resource qualifier `values-ar` auto-selected by configuration locale), supports format args (`%1$s`) and plurals, and scales to more languages by adding folders.

**Naming convention**: `feature_element_purpose` (e.g., `settings_language_title`, `consent_agree_button`, `tamper_alert_data_cleared_title`). Shared strings: `common_*` (e.g., `common_cancel`, `common_back`).

**Scale found in codebase**: 20 `*Screen.kt` files, none currently use `stringResource`; plus dialogs/components, 2 workers, notification builders in services, `MonitoringService` notice. Existing `strings.xml` has ~25 entries. Estimated catalog after sweep: 350–450 keys.

**Alternatives considered**: Custom JSON/YAML string files loaded at runtime — rejected: reinvents resource qualifiers, loses plural/format support, breaks system-driven locale resolution for notifications.

## R3. RTL layout direction

**Decision**: Rely on the platform: `android:supportsRtl="true"` is already set in the manifest; when the configuration locale is `ar`, Compose's `setContent` provides `LocalLayoutDirection = Rtl` automatically. Row/Column/Arrangement/padding-start-end all mirror for free (codebase already uses start/end-relative Compose modifiers).

**Directional icon mirroring**: Compose BOM is locked at 2023.10.01 (Compose 1.5.4) — `Icons.AutoMirrored.*` shipped in 1.6.0 and is NOT available. Provide a small `Modifier.mirrorInRtl()` helper (`scaleX = -1` when `LocalLayoutDirection == Rtl`) applied only to directional icons (`ArrowBack`, `ArrowForward`, `Link`-style chevrons, `Send`-style). Brand logo (`HarisLogo`) and status icons (shield, warning) are never mirrored.

**Alternatives considered**: Upgrading Compose BOM to ≥ 2024.x for AutoMirrored icons — rejected: constitution locks Compose BOM 2023.10.01; version bump out of scope for a localization feature.

## R4. Western digits in Arabic mode (clarified requirement)

**Decision**: Use locale tag `ar` for resources but ensure Western digits by:
1. Applying configuration locale as `Locale.forLanguageTag("ar-u-nu-latn")` — the `nu-latn` Unicode extension forces Latin (Western 0-9) digits in all `NumberFormat`/`DateFormat`/`String.format` output while still resolving `values-ar` resources (resource matching ignores the extension).
2. Rule for code: any `String.format`/`SimpleDateFormat` that renders user-visible numbers uses the current app locale (never hardcoded `Locale.getDefault()` category confusion).

**Rationale**: Single mechanism, no per-call-site digit conversion, satisfies clarification "Western digits everywhere".

**Alternatives considered**: Formatting all numbers with `Locale.US` explicitly — rejected as primary (scattered, easy to miss call sites), but remains the fallback rule where a formatter is constructed manually (e.g., `LinkParentScreen` expiry `SimpleDateFormat`).

## R5. Language selector UI (clarified requirement)

**Decision**: New "Language" row in existing `SettingsScreen` section list showing current value ("English" / "العربية"); tap opens a Material3 `AlertDialog` with two radio options. Selection: ViewModel persists pref → calls locale sync → `Activity.recreate()` (obtained via `LocalActivity`/context cast in screen callback). Settings screen re-opens in new language in-place.

**Note on constitution rule 10**: `AlertDialog` is prohibited *for error display* only; a selection dialog is compliant and matches the clarified choice.

## R6. Background text (notifications, workers) localization

**Decision**: `LocaleHelper.localizedContext(context)` returns a `createConfigurationContext`-wrapped context with the stored language; `TamperAlertWorker`, `ProtectionMonitorWorker`, `MonitoringService` notification builder, and any `NotificationChannel` names fetch strings through it. Alert title/message sent to backend (`POST /alerts`) are also built from these localized resources — parent sees alerts in the child device's configured language (per-device preference, per spec assumption).

## R7. First-launch default

**Decision**: No stored pref → `LocaleHelper` returns `Locale.getDefault().language == "ar" ? "ar" : "en"` and persists nothing until the user explicitly chooses (system-follow until first explicit choice; after that the explicit choice wins forever). Satisfies FR-007.

## R8. Translation parity check (SC-006)

**Decision**: Python script `tools/check_strings_parity.py` comparing key sets of `values/strings.xml` vs `values-ar/strings.xml` (and `%` placeholder counts per key); exits non-zero on mismatch. Same pattern as the 007 landing-site i18n parity check.

## R9. Legal docs language pass-through

**Decision**: The bundled legal websites already contain their own language toggle with JS i18n. `LegalDocScreen` appends `?lang=ar|en` (current app language) to the `file:///android_asset/...` URL; the site JS already reads stored/URL language where supported. Best-effort (spec says SHOULD).
