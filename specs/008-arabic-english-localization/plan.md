# Implementation Plan: Arabic & English Localization with RTL Support

**Branch**: `008-arabic-english-localization` | **Date**: 2026-07-07 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/008-arabic-english-localization/spec.md`

## Summary

Make the entire Haris app bilingual (English/Arabic) with a language selector in Settings (row + radio dialog). Arabic renders full RTL; English LTR. Every hardcoded user-facing string moves to `res/values/strings.xml` (EN) + `res/values-ar/strings.xml` (AR) and is referenced via `stringResource()` in Compose and localized-context `getString()` in workers/services. Language switching is dependency-free: `SharedPreferences`-backed `LocaleHelper` + `attachBaseContext` wrapping + `Activity.recreate()`, with API 33+ system sync. Western digits enforced app-wide via `ar-u-nu-latn` locale tag. Full decisions in [research.md](./research.md).

## Technical Context

**Language/Version**: Kotlin (JDK 21 toolchain, `jvmTarget 17`), Jetpack Compose (BOM 2023.10.01 / Compose 1.5.4)

**Primary Dependencies**: No new dependencies. Uses platform `Configuration`/`createConfigurationContext`, existing Hilt 2.51.1, existing `SharedPreferences`. Deliberately NOT adding `androidx.appcompat` (see research R1).

**Storage**: `SharedPreferences` key `app_language` ∈ {`en`, `ar`} (raw synchronous read required in `attachBaseContext`, pre-Hilt). No backend/Room changes.

**Testing**: Unit tests for `LocaleHelper` (default resolution, tag mapping); `tools/check_strings_parity.py` for EN/AR key parity (SC-006); manual bilingual walkthrough per [quickstart.md](./quickstart.md); `./gradlew assembleDebug` gate.

**Target Platform**: Android `minSdk 26` / `targetSdk 35`; RTL via `android:supportsRtl="true"` (already present)

**Project Type**: Mobile app (native Android, existing single-module `app/`)

**Performance Goals**: Language switch visible < 2 s (single `Activity.recreate()`); no startup regression (locale wrap is one SharedPreferences read)

**Constraints**: Locked dependency versions (no Compose BOM bump ⇒ no `Icons.AutoMirrored`; manual `Modifier.mirrorInRtl()` helper instead); Western digits everywhere (`ar-u-nu-latn`); brand logo never mirrors; `splash_logo.png` untouched

**Scale/Scope**: 20 `*Screen.kt` files + dialogs/components + 2 workers + service notifications; estimated 350–450 string keys × 2 languages

## Constitution Check

*GATE: evaluated against SafeGuard Constitution v2.0.0 — all twelve enforcement rules.*

| # | Rule | Status | Note |
|---|------|--------|------|
| 1 | Feature sub-package under `presentation/<feature>/` | ✅ PASS (n/a-adapted) | No new screen; language UI lives in existing `presentation/settings/` (row + dialog + ViewModel state). `LocaleHelper` goes to `util/` alongside `Constants`/`PreferencesManager`. |
| 2 | `@HiltViewModel` only | ✅ PASS | Extends existing `SettingsViewModel`; no manual instantiation. |
| 3 | Repositories `@Singleton` | ✅ PASS | No new repository. |
| 4 | ViewModel → Repository → `safeApiCall{}` → `ApiService` | ✅ PASS | No network calls added. |
| 5 | Tokens only in `TokenManager` | ✅ PASS | Untouched. |
| 6 | Special permissions via settings routing | ✅ PASS | No permissions touched. |
| 7 | Services contain zero business logic | ✅ PASS | Services only swap hardcoded literals for localized `getString`. |
| 8 | Workers `@HiltWorker` + `CoroutineWorker` | ✅ PASS | Existing workers modified strings-only. |
| 9 | `NetworkResult<T>` from repositories | ✅ PASS | Untouched. |
| 10 | Errors via Snackbar, no `AlertDialog` for errors | ✅ PASS | Language picker `AlertDialog` is a selection dialog, not error display — compliant. Error strings themselves get localized. |
| 11 | ProGuard updated for new Service/Receiver/Worker | ✅ PASS | None added. |
| 12 | `noCompress "tflite"` intact | ✅ PASS | Build file untouched except nothing. |

**Principle I (privacy)**: No new data collected. Language preference is a device-local UI setting; never transmitted. Alert text sent to backend changes language but not content scope. PASS.

**Post-Phase-1 re-check**: design artifacts introduce no violations. PASS.

## Project Structure

### Documentation (this feature)

```text
specs/008-arabic-english-localization/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/
│   └── locale-contract.md
└── tasks.md             # Phase 2 (/speckit-tasks)
```

### Source Code (repository root)

```text
app/src/main/
├── java/com/safeguard/parentalcontrol/
│   ├── util/
│   │   └── LocaleHelper.kt                      # NEW — pref read/write, wrap(), localizedContext(), API33 sync
│   ├── presentation/
│   │   ├── MainActivity.kt                      # MODIFY — attachBaseContext(LocaleHelper.wrap(newBase))
│   │   ├── designsystem/
│   │   │   └── MirrorInRtl.kt                   # NEW — Modifier.mirrorInRtl() for directional icons
│   │   ├── settings/
│   │   │   ├── SettingsScreen.kt                # MODIFY — Language row + radio dialog + recreate()
│   │   │   ├── SettingsViewModel.kt             # MODIFY — language state + setLanguage()
│   │   │   └── LegalDocScreen.kt                # MODIFY — ?lang= pass-through
│   │   └── <all 20 feature screens + components> # MODIFY — literals → stringResource()
│   ├── worker/
│   │   ├── TamperAlertWorker.kt                 # MODIFY — localized getString for titles/messages
│   │   └── ProtectionMonitorWorker.kt           # MODIFY — same
│   └── service/ (+ receivers posting notifications) # MODIFY — notification text/channel names localized
└── res/
    ├── values/strings.xml                       # MODIFY — full English catalog (~350–450 keys)
    └── values-ar/strings.xml                    # NEW — complete Arabic translations

tools/
└── check_strings_parity.py                      # NEW — EN/AR key + placeholder parity gate
```

**Structure Decision**: Single existing Android module; localization is a cross-cutting sweep over `presentation/`, `worker/`, `service/` plus one new util and one new res folder. No architectural changes.

## Complexity Tracking

No constitution violations. Table intentionally empty.
