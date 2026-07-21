# Implementation Plan: Maximum Protection

**Branch**: `010-maximum-protection` | **Date**: 2026-07-19 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/010-maximum-protection/spec.md`

## Summary

Add a PIN-gated "Maximum Protection" toggle to the child-device settings that controls whether detected image violations are blurred in the gallery (ON = blur + backup copy, current behavior) or only copied to the parent-review backup folder (OFF = copy-only, new default). The flag is stored in the existing `PreferencesManager` (EncryptedSharedPreferences), read fresh on every violation in both detection paths (`MediaFileObserver` real-time + `ImageScanWorker` periodic), and turning the toggle ON retroactively blurs images that were flagged copy-only while it was OFF. Parent alerts are sent identically in both modes. Everything reuses existing components: `ParentPinDialog` for the gate, `ImageBlurManager` for blur/backup, `SettingsScreen`/`SettingsViewModel` for the UI.

## Technical Context

**Language/Version**: Kotlin (JDK 21 toolchain, `jvmTarget 17`), Jetpack Compose (BOM 2023.10.01, compiler ext 1.5.14)

**Primary Dependencies**: Hilt 2.51.1, WorkManager 2.9.0 (+hilt-work 1.1.0), Coroutines 1.7.3, EncryptedSharedPreferences (androidx.security.crypto), Timber 5.0.1

**Storage**: `PreferencesManager` (EncryptedSharedPreferences, `safeguard_prefs`) for the flag; app-private `filesDir/image_backup/` for original copies + `.meta` sidecar files (existing `ImageBlurManager` scheme)

**Testing**: JUnit unit tests (metadata parse/serialize, mode-branch logic); manual on-device validation via quickstart.md

**Target Platform**: Android `minSdk 26` / `targetSdk 35`, child device role only

**Project Type**: Native Android mobile app (single `app/` module, package-by-feature MVVM)

**Performance Goals**: Flag read = one encrypted-prefs boolean get per violation (sub-millisecond, already on background threads). Retroactive blur pass bounded by pending-review count; runs on `Dispatchers.IO`.

**Constraints**: Fully offline-capable (flag + blur + backup all on-device); no backend/API contract changes; no new Android permissions; no new Service/Receiver/Worker (no ProGuard changes)

**Scale/Scope**: 1 new pref key, 1 toggle row in existing settings screen, 2 call-site branches, 1 metadata field, 1 retroactive pass, ~8 files touched

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| # | Rule | Status | Notes |
|---|------|--------|-------|
| 1 | Feature sub-package with ViewModel/UiState/Screen | ⚠️ Justified | No new screen — one toggle row inside existing `presentation/settings/`. See Complexity Tracking. |
| 2 | `@HiltViewModel` only | ✅ | Reuses `SettingsViewModel` + `ParentPinViewModel` (both already `@HiltViewModel`). |
| 3 | Repositories `@Singleton` | ✅ | No new repository. `ImageBlurManager`/`PreferencesManager` already `@Singleton`. |
| 4 | ViewModel → Repository → `safeApiCall{}` → `ApiService` | ✅ | No new network calls; alert path untouched. |
| 5 | Tokens only in `TokenManager` | ✅ | Not touched. |
| 6 | Special permissions via settings routing | ✅ | No new permissions. |
| 7 | Services contain zero business logic | ✅ | `MediaFileObserver` gains a one-line pref read + branch, mirroring its existing delegation pattern; logic itself lives in `ImageBlurManager`. |
| 8 | Workers `@HiltWorker` + `CoroutineWorker` | ✅ | `ImageScanWorker` already compliant; only its flag branch changes. |
| 9 | `NetworkResult<T>` from repositories | ✅ | No repository signature changes. |
| 10 | Errors via Snackbar + `clearError()` | ✅ | Settings screen already has the Snackbar/`LaunchedEffect(uiState.error)` plumbing; reused. |
| 11 | ProGuard updated for new Service/Receiver/Worker | ✅ | None added. |
| 12 | `noCompress "tflite"` intact | ✅ | Build files untouched. |

**Principle I (privacy)**: nothing new collected; one local boolean; image copies stay in the existing app-private folder; alerts remain metadata-only. Data-flow note present in spec.
**Principle III (two-role)**: toggle + enforcement live entirely on the child device; works offline.
**Principle IV**: no new permissions, no service lifecycle changes.

**Gate result: PASS** (one justified deviation, tracked below).

## Project Structure

### Documentation (this feature)

```text
specs/010-maximum-protection/
├── plan.md              # This file
├── spec.md              # Feature spec (clarified)
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/
│   └── internal-contracts.md   # Phase 1 output (no external API — internal component contracts)
├── checklists/
│   └── requirements.md
└── tasks.md             # Phase 2 output (/speckit-tasks — NOT created by /speckit-plan)
```

### Source Code (repository root)

```text
app/src/main/java/com/safeguard/parentalcontrol/
├── util/
│   ├── Constants.kt                 # MODIFY: add KEY_MAXIMUM_PROTECTION_ENABLED
│   ├── PreferencesManager.kt        # MODIFY: add isMaximumProtectionEnabled (get/set, default false)
│   └── ImageBlurManager.kt          # MODIFY: metadata blurApplied field; backupOnly(); applyBlurToUnblurredBackups()
├── worker/
│   └── ImageScanWorker.kt           # MODIFY: read flag fresh per violation → blurImage vs backupOnly; retro-blur safety net
├── service/
│   └── MediaFileObserver.kt         # MODIFY: read flag fresh per violation → blurImage vs backupOnly
└── presentation/
    ├── components/
    │   └── ParentPinDialog.kt       # REUSE (no change)
    └── settings/
        ├── SettingsScreen.kt        # MODIFY: Maximum Protection toggle row + PIN dialog wiring
        └── SettingsViewModel.kt     # MODIFY: uiState field + setMaximumProtection(); retro-blur trigger on enable

app/src/main/res/
├── values/strings.xml               # MODIFY: settings_maximum_protection* strings (English)
└── values-ar/strings.xml            # MODIFY: same strings (Arabic)
```

**Structure Decision**: Extend the existing package-by-feature layout in the single `app/` module. No new packages, screens, services, workers, or DI modules — the feature is a preference + a branch inside two existing enforcement paths + one settings row.

## Design Decisions (from research.md)

1. **Flag storage**: `Constants.KEY_MAXIMUM_PROTECTION_ENABLED = "maximum_protection_enabled"`; typed property `PreferencesManager.isMaximumProtectionEnabled` defaulting to `false`. Cleared by existing `clearAll()` on logout (spec edge case satisfied for free).
2. **Fresh read per violation**: each call site reads `preferencesManager.isMaximumProtectionEnabled` inside the per-image violation branch (never hoisted above the loop in `ImageScanWorker`, never cached in a field). Satisfies FR-005/SC-004.
3. **Mode branch lives at the two call sites** (`MediaFileObserver.analyzeImage`, `ImageScanWorker.doWork`), matching the existing duplicated-flow style of those files; the heavy lifting stays in `ImageBlurManager`.
4. **Copy-only path**: new `ImageBlurManager.backupOnly(imagePath, category, confidence): BlurResult` — identical to `blurImage()` steps 1–3 (backup + metadata) but skips blur/replace/MediaStore steps; writes `blurApplied=false` in metadata. Returns the same `BlurResult` sealed type (`AlreadyBlurred` reused as "backup already exists" so call-site alert dedup behavior is unchanged).
5. **Metadata versioning**: `ImageMetadata` gains `blurApplied: Boolean`. `loadMetadata()` defaults a missing key to `true` — every legacy entry was written by the always-blur flow, so legacy files remain correct without migration.
6. **Retroactive blur (FR-013/014)**: new `ImageBlurManager.applyBlurToUnblurredBackups(): Int` — iterates `getPendingReviews()`, for each entry with `blurApplied == false`: create blurred bitmap from `originalPath`, replace the gallery file, refresh MediaStore, rewrite metadata with `blurApplied=true`. Missing/moved originals are skipped without error (spec edge case). No new backup copy, no alert. Triggered (a) immediately from `SettingsViewModel.setMaximumProtection(true)` on `Dispatchers.IO`, and (b) as a safety net at the start of each `ImageScanWorker` run when the flag is ON.
7. **Toggle UI**: new `SettingsToggleItem` row inside the existing child-only "Parent Review" section of `SettingsScreen` (PIN-protected parental controls belong together). Tap does NOT flip state: it records the desired value in a `pendingMaxProtectionChange` state var and shows `ParentPinDialog` (with `ParentPinViewModel` via `hiltViewModel()`, same pattern as `ParentPinGate`). `onSuccess` → `viewModel.setMaximumProtection(desired)`; dismiss/wrong PIN → pending cleared, toggle unchanged (FR-002/003). First-time PIN creation handled by the dialog's existing create mode.
8. **Alert behavior unchanged per call site** (FR-008): both call sites keep their existing alert invocation structure; only the blur call swaps between `blurImage()`/`backupOnly()` based on the flag.
9. **Review UI**: no visual distinction between copy-only and blurred entries (deferred clarification resolved as "no distinction"). `restoreOriginal()` on a copy-only entry copies identical bytes over the original (harmless) and clears the entry; `deleteImage()` removes gallery original + backup as today (FR-010).
10. **ON→OFF**: no code path — absence of any restore trigger implements FR-015.

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|--------------------------------------|
| Rule 1: no new `presentation/maximumprotection/` sub-package | Feature is a single toggle row + dialog inside the existing settings surface; it has no screen, navigation entry, or standalone UiState | A dedicated package with its own ViewModel/UiState/Screen for one Switch would fragment the settings UI, duplicate `SettingsViewModel` state plumbing, and add a navigation route with nothing to navigate to |

## Post-Design Constitution Re-Check

Re-evaluated after Phase 1 artifacts: no new violations introduced. The single Rule-1 deviation stands justified; all other rules remain ✅ as tabled above.
