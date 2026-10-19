# Implementation Plan: Remove the DATA_SYNC Foreground Service Declaration

**Branch**: `fix/fgs-remove-data-sync` | **Date**: 2026-10-05 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/015-remove-datasync-fgs-permission/spec.md`

## Summary

Google Play Console blocks the release review with a foreground-service declaration form that asks
about `FOREGROUND_SERVICE_DATA_SYNC`. The Phase 0 audit established that the service-category
migration this feature was expected to perform **already shipped** with feature
`013-play-store-release-readiness`: `MonitoringService` is already `specialUse`, already declares
a parental-control subtype property, and already passes `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` on
API ≥ 34. Feature 013's task `T035` verified the permission was unused and deliberately left it
declared, recording removal as out of scope at the time.

What remains is a single orphaned `<uses-permission>` line that maps to no component. The
manifest-merger report proves it has exactly one origin — `app/src/main/AndroidManifest.xml:21` —
and that no dependency contributes it. That one line is the entire reason the store console still
asks the question.

**Technical approach**: delete the line; add the missing `try`/`catch` around
`MonitoringService.startForeground` to match the pattern `ContentFilterVpnService` already uses;
add a release-blocking Gradle guard so a future dependency upgrade cannot silently reintroduce the
permission; prove removal from the shipped bundle's merged manifest; verify no regression on
device against a recorded evidence matrix. No service-category migration work is performed,
because there is none left to perform.

## Technical Context

**Language/Version**: Kotlin 2.0.21 (KSP), JDK 21, `jvmTarget = 17`

**Primary Dependencies**: Jetpack Compose (BOM 2024.09.00), Hilt 2.51.1, WorkManager 2.9.0,
Room 2.6.1, Retrofit 2.9.0 / OkHttp 4.12.0, Firebase BOM 32.7.0, TFLite 2.16.1, Timber 5.0.1.
**No dependency is added, removed or upgraded by this feature.**

**Storage**: N/A — this feature stores nothing. Room and `EncryptedSharedPreferences` are
untouched.

**Testing**: No new automated tests (spec FR-013 — the migration under test already shipped with
013, so new tests would verify prior work). Verification is: a release-blocking Gradle guard
(SC-009), merged-manifest inspection (SC-001), clean builds (SC-002), and a manual evidence
matrix for the device criteria (FR-012, SC-003 → SC-007).

**Target Platform**: Android, `minSdk 26`, `targetSdk 36`, `compileSdk 36`. Release artifact is a
signed AAB; `keystore.properties` and `secrets.properties` are present locally.

**Project Type**: Native Android mobile app, single Gradle module (`:app`). `settings.gradle`
includes `:app` only — there is no `:eval` module and no `build-logic`/`buildSrc`.

**Performance Goals**: N/A. No code on any hot path changes. The enforcement loop, image
monitoring and sync scheduling are untouched.

**Constraints**: `MonitoringService` and `ContentFilterVpnService` must remain continuously
runnable with no platform runtime cap — the reason `specialUse` was chosen. Boot and restart
paths must keep working on API 34/35/36 with no `ForegroundServiceStartNotAllowedException` and no
`SecurityException`. No regression on API 26–33, where service types do not exist.

**Scale/Scope**: One manifest line deleted. One `try`/`catch` added to one method. One Gradle
verification task added. Two documentation updates. Net source change is under 40 lines.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-checked after Phase 1 design.*

### Twelve SpecKit Enforcement Rules

| # | Rule | Status |
| --- | --- | --- |
| 1 | Feature sub-package under `presentation/<feature>/` with ViewModel/UiState/Screen | **N/A** — no UI, no screen, no presentation code |
| 2 | Every ViewModel `@HiltViewModel` | **N/A** — no ViewModel touched |
| 3 | Every repository `@Singleton` | **N/A** — no repository touched |
| 4 | ViewModel → Repository → `safeApiCall{}` → `ApiService` | **N/A** — no network call |
| 5 | Tokens only in `TokenManager` | **PASS** — no token access anywhere in this feature |
| 6 | Special permissions via settings routing, never `requestPermissions()` | **PASS** — no permission request flow changes. The permission removed is a normal install-time permission, not one of the four special permissions |
| 7 | Services contain zero business logic, delegate to injected repositories | **PASS** — the added `try`/`catch` is a failure-path guard, not business logic |
| 8 | Workers are `@HiltWorker` + `CoroutineWorker` + `@AssistedInject` | **N/A** — no worker modified. `SyncWorker` is read during the audit only |
| 9 | `NetworkResult<T>` is the only repository return type | **N/A** |
| 10 | UI errors via `Snackbar` + `clearError()` | **N/A** — no UI |
| 11 | ProGuard rules updated for every new Service, Receiver or Worker | **PASS** — none is added, so no rule change is required or permitted |
| 12 | `aaptOptions { noCompress "tflite" }` remains in `app/build.gradle` | **PASS** — the Gradle guard appends a verification task and must not disturb this block. Verified as a Phase 1 checkpoint |

### Core Principles

| Principle | Status |
| --- | --- |
| **I. Child Safety & Privacy First** (NON-NEGOTIABLE) | **PASS, and advanced.** No new collection, storage or transmission — the spec's Data Flow note records this explicitly. The feature *strengthens* Principle I: the permission being deleted is the vestige of a service category whose six-hour platform cap was precisely the silent-end-of-protection failure mode this principle exists to prevent |
| **II. MVVM + Clean Architecture** (NON-NEGOTIABLE) | **PASS** — no layer boundary is crossed or added |
| **III. Two-Role Architecture** | **PASS** — enforcement stays on the child device; no parent/child boundary change |
| **IV. Native Services & Permission Hygiene** | **PASS, and directly satisfied.** The principle states: *"Every new high-risk permission added to the manifest MUST map to a named enforcement feature in its spec. Unmapped permissions MUST be removed."* This permission maps to nothing. This feature is that removal. The principle also already records `MonitoringService` as `TYPE_SPECIAL_USE`, matching the shipped state |
| **V. Defense-in-Depth & Secrets Hygiene** | **PASS** — no secret, no pin, no network config, no signing change |

### Workflow Gates

| Gate | Status |
| --- | --- |
| Data-flow note required for any spec touching monitored data or OS permissions (Principle I) | **PASS** — added to the spec during clarification (`## Data Flow & Permission Justification`) |
| Permission justification required (Principles IV/V) | **PASS** — permission disposition table in the same spec section |
| Spec-driven flow: constitution → specify → plan → tasks → implement | **PASS** — `/speckit-specify` and `/speckit-clarify` completed; this is `/speckit-plan` |
| Testing priorities: service restart resilience is a named priority area | **Deliberate deviation** — see Complexity Tracking row 2 |

**Initial gate verdict: PASS.** Two deviations are justified below rather than left silent.

## Project Structure

### Documentation (this feature)

```text
specs/015-remove-datasync-fgs-permission/
├── spec.md                      # Feature spec (with Clarifications + Data Flow note)
├── plan.md                      # This file
├── research.md                  # Phase 0 audit + decisions + STOP GATE verdict
├── quickstart.md                # Phase 4 runtime verification guide
├── contracts/
│   └── build-guard.md           # Contract for the release-blocking manifest guard
├── checklists/
│   └── requirements.md          # Spec quality checklist (16/16)
└── tasks.md                     # Phase 2 output (/speckit-tasks — NOT created here)
```

No `data-model.md` is produced. This feature has no entities, no persistence and no state
transitions; the spec's Key Entities section describes manifest declarations and existing
services, not a data model. Generating one would be filler.

### Source Code (repository root)

```text
app/
├── build.gradle                                  # MODIFY: add release-blocking manifest guard
└── src/main/
    ├── AndroidManifest.xml                       # MODIFY: delete line 21 (DATA_SYNC permission)
    └── java/com/safeguard/parentalcontrol/
        ├── service/
        │   ├── MonitoringService.kt              # MODIFY: try/catch around startForeground
        │   └── ContentFilterVpnService.kt        # READ ONLY — reference pattern, frozen by FR-007
        ├── receiver/
        │   ├── BootReceiver.kt                   # READ ONLY — frozen, verified not modified
        │   ├── BootServiceStartWorker.kt         # READ ONLY — frozen
        │   └── ServiceRestartReceiver.kt         # READ ONLY — frozen
        └── worker/
            └── SyncWorker.kt                     # READ ONLY — audited, not modified

docs/
└── release-checklist.md                          # MODIFY: record the permission removal (FR-011)
```

**Structure Decision**: single-module Android app, existing layout, no new directories and no new
files in `app/`. The three modified paths are the complete source footprint. Everything else named
above is read-only and is listed so that a reviewer can confirm it was *not* touched.

## Phase 0 — Dependency audit (COMPLETE)

Full findings, search coverage and the audit table are in [research.md](./research.md).

**Result summary:**

| Search | Finding |
| --- | --- |
| `FOREGROUND_SERVICE_DATA_SYNC` | 1 hit — `app/src/main/AndroidManifest.xml:21` |
| `dataSync` | 8 hits, **all prose** (comments, KDoc, constitution history, release notes). Zero live declarations |
| `FOREGROUND_SERVICE_TYPE_DATA_SYNC` | 0 hits |
| `startForeground(` | 4 call sites, 2 services, all `specialUse` or untyped pre-API-34 |
| `ForegroundInfo` / `getForegroundInfo` / `setForeground(Async)` | **0 occurrences.** No worker declares foreground info at all |
| `MonitoringService` start sites | 11, all already targeting a `specialUse` service |
| Merged release manifest | permission present, single origin: the app manifest |
| Manifest-merger report | `ADDED from app/src/main/AndroidManifest.xml:21:5-87` — **no library contributes it** |
| `androidx.work:work-runtime:2.9.0` | declares base `FOREGROUND_SERVICE` only; its `SystemForegroundService` merges with **no** `foregroundServiceType` |

### STOP GATE: PASS

The gate permits proceeding only if findings are confined to (a) the `<uses-permission>` line and
(b) `MonitoringService`'s `foregroundServiceType="dataSync"`. Finding (a) is present exactly as
expected. Finding (b) **does not exist** — the attribute is already `specialUse`. Findings are a
strict subset of the allowance. No worker uses the type, no library injects the permission, no
overlay or module manifest declares it.

**No approval exception was required.** Two developer decisions were nonetheless raised and
answered, because both touch explicit instructions:

| Decision | Answer |
| --- | --- |
| Subtype wording — keep the shipped string, or replace it with the shorter string supplied in the plan request? | **Keep the shipped string.** It is longer and names more justification surface for the store reviewer. The spec places purpose-string wording out of scope |
| The FR-010 guard requires editing `app/build.gradle`, which the guardrails list as do-not-modify | **Approved.** Verification task only — no dependency added, removed or upgraded |

Two pre-existing issues were found, recorded, and left alone because guardrails forbid touching
them: `SyncWorker`'s expedited-work path has no `getForegroundInfo` override (can fail on API
26–30), and `scheduleServiceRestart()` arms `PendingIntent.getService` rather than
`getForegroundService`. Neither is a data-transfer dependency; neither is affected by the removal.

## Phase 1 — Manifest change

**File modified: `app/src/main/AndroidManifest.xml` only.**

1. Delete line 21:
   `<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />`
2. **Skip** the original step 2 (`foregroundServiceType="specialUse"`) — already present at line
   116. No edit.
3. **Skip** the original step 3 (add `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`) — already present at
   lines 118–120, and the developer confirmed the shipped wording stays. No edit.
4. Keep `FOREGROUND_SERVICE` (line 20) and `FOREGROUND_SERVICE_SPECIAL_USE` (line 22). Keep
   `stopWithTask="false"` and every other attribute on every service byte-for-byte.
5. **Skip** the conditional `tools:node="remove"` — the merger report proves a single origin in
   the app's own manifest, so there is no library node to suppress. Adding the directive would be
   dead configuration implying a library problem that does not exist.
6. Leave the explanatory XML comment at lines 115–118 in place. It records *why* the type is
   `specialUse`, which is the guardrail against a future regression.

**Net effect: one line deleted.**

**Checkpoint**: `./gradlew assembleDebug` passes.

## Phase 2 — Service code

**File modified: `MonitoringService.kt` only.**

1. The API-level branch at lines 231–239 is already correct — `FOREGROUND_SERVICE_TYPE_SPECIAL_USE`
   on API ≥ 34, two-argument call below. **No change to either branch.**
2. Wrap both branches in `try`/`catch`, mirroring `ContentFilterVpnService.kt:267–282` exactly.
   On failure: `Timber.e` with the exception **and** the API level (fail loud — never silent),
   then `return START_NOT_STICKY`.
3. Do not change notification content, channel, the enforcement loop, image monitoring, sync
   scheduling, `onTaskRemoved`, `onTimeout`, or `onDestroy`.
4. Preserve the existing comment at lines 228–230 explaining the `specialUse` choice.

Shape (mirrors the VPN service; no new pattern):

```
try {
    if (API >= UPSIDE_DOWN_CAKE) startForeground(id, notification, TYPE_SPECIAL_USE)
    else                         startForeground(id, notification)
} catch (e: Exception) {
    Timber.e(e, "Failed to start foreground service (API ${Build.VERSION.SDK_INT})")
    return START_NOT_STICKY
}
```

**Deviation note**: spec FR-009 freezes lifecycle, restart strategy and enforcement-loop timing.
This changes none of them on the success path. It changes the *failure* path from an uncaught
exception to a logged, explicit `START_NOT_STICKY`. Recorded in Complexity Tracking rather than
applied silently.

**Checkpoint**: `./gradlew assembleDebug` passes.

## Phase 3 — Build guard and build verification

### 3a. Release-blocking Gradle guard (FR-010)

**File modified: `app/build.gradle`.** Contract: [contracts/build-guard.md](./contracts/build-guard.md).

Hook the manifest-processing task for both variants and fail the build if the merged manifest
contains the data-transfer permission or a `dataSync` service type:

```
tasks.matching { it.name ==~ /process(Debug|Release)MainManifest/ }.configureEach {
    doLast { /* read merged manifest; collect violations; throw GradleException */ }
}
```

Design notes:

- **Why not `preReleaseBuild`** (where the certificate-pin guard lives): the merged manifest does
  not exist that early. The manifest-processing task is the earliest point where the artefact
  under test is real, and `bundleRelease` and `assembleRelease` both depend on it, so the guard
  cannot be skipped while producing a release artifact.
- **Why both variants**: catching it on `debug` too gives the developer the failure during normal
  development rather than at release time.
- **Comment-safe matching**: the manifest contains the literal word `dataSync` inside an
  explanatory XML comment. The guard must match the *permission name* and the
  `foregroundServiceType` **attribute value**, never a bare substring, or it will fail on its own
  documentation. This is the single most likely implementation mistake and is called out in the
  contract.
- **Must not disturb** the `aaptOptions { noCompress "tflite" }` block (enforcement rule 12) or
  the existing `preReleaseBuild` guard.
- Failure message follows the existing guard's house style and points at
  `specs/015-remove-datasync-fgs-permission/contracts/build-guard.md`.

### 3b. Verification sequence

1. `./gradlew clean assembleDebug` → passes, no new foreground-service warning.
2. `./gradlew bundleRelease` → passes; the new guard runs and stays silent.
3. Inspect the shipped bundle's merged manifest:
   `bundletool dump manifest --bundle app/build/outputs/bundle/release/app-release.aab`
   Expected: zero matches for `FOREGROUND_SERVICE_DATA_SYNC`; zero `foregroundServiceType`
   values containing `dataSync`; `MonitoringService` and `ContentFilterVpnService` both
   `specialUse`, each with its subtype property.
   `bundletool` is not on `PATH` in this environment — the fallback is `aapt2 dump xmltree` from
   SDK build-tools 36.0.0 against the release APK, plus the text merged manifest at
   `app/build/intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml`.
   Both are documented in the quickstart.
4. Negative test for the guard (SC-009): temporarily reinsert the permission, confirm
   `bundleRelease` **fails** with the guard's message naming the permission, then revert and
   confirm it passes.
5. Repo-wide grep for `FOREGROUND_SERVICE_DATA_SYNC`, a live `dataSync` attribute value, and
   `FOREGROUND_SERVICE_TYPE_DATA_SYNC`. Expected: zero matches outside `build/` directories,
   `specs/`, `.kilo/` and explanatory prose.

### 3c. Documentation (FR-011)

- `docs/release-checklist.md`: record that `FOREGROUND_SERVICE_DATA_SYNC` is now removed, and that
  the store console declaration is special-use only.
- Note that feature 013's deliberate deferral (task `T035`, FR-027) is closed.
- **No constitution amendment.** The constitution already states `TYPE_SPECIAL_USE` as current and
  records the migration in its v2.1.0 history. Nothing there misstates the shipped state, so
  nothing there may be rewritten.

**Commit only after Phase 3 passes**, on branch `fix/fgs-remove-data-sync`, message in English.

## Phase 4 — Runtime verification

Steps, exact commands and expected output: [quickstart.md](./quickstart.md).

Per FR-012, every device-facing criterion is verified manually and recorded with concrete
evidence — a logcat line, a `dumpsys` excerpt, the API level, and the device or emulator
identity. A criterion with no recorded evidence counts as unverified. Matrix covers SC-003
through SC-007 on an API 35/36 target and on an API 26–33 target.

## Completion gate

| Criterion | Blocking? | Evidence |
| --- | --- | --- |
| SC-001 merged manifest clean | Yes | `bundletool`/`aapt2` dump |
| SC-002 both builds pass, no new FGS warning | Yes | build output |
| SC-009 guard fails on reinsertion, passes clean | Yes | two build runs |
| SC-003 → SC-007 device behaviour | Yes | completed FR-012 evidence matrix |
| SC-008 store console shows special-use only | **No** | post-merge, developer confirms after upload |

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| Editing `app/build.gradle`, which the plan input's guardrails list as do-not-modify | FR-010 requires a guard that cannot be skipped while producing a release artifact. A dependency upgrade can inject a permission into the merged manifest with **zero** source change, so the thing being guarded against is by nature invisible to review. The constitution already establishes this exact release-blocking build-guard pattern for certificate pinning | A manual checklist step is skippable, and a silent regression is exactly what a skippable check misses. A CI-only step still lets a non-compliant bundle be produced locally. A unit or instrumented test cannot see the merged manifest of a release build. **Developer explicitly approved.** The guardrail's concern is dependency changes; this adds a verification task and changes no dependency |
| `try`/`catch` added to `MonitoringService.startForeground`, adjacent to FR-009's freeze on service lifecycle, and the constitution names service restart resilience an automated-test priority while FR-013 adds no tests | Requested in the plan input, and it closes a real asymmetry: `ContentFilterVpnService` survives a foreground-start failure with a loud log, while `MonitoringService` would propagate the exception. The reference pattern already exists in the codebase, so no new pattern is introduced. FR-013 correctly declines new tests for the *already-shipped* category migration, and manual evidence (FR-012) covers the boot and restart paths that matter | Leaving it as-is was rejected — the developer asked for it and the gap is real. Adding a retry or a restart on failure was rejected — that would change restart strategy and genuinely violate FR-009. Writing new instrumented tests for it was rejected — they would exercise a failure path that requires an injected platform exception, for a 40-line change, against FR-013 |

## Post-Design Constitution Re-Check

Re-evaluated after Phase 1–4 design was fixed:

- **Rules 1–6, 8–10**: still N/A or PASS; the design adds no screen, ViewModel, repository,
  network call, token access, worker or UI.
- **Rule 7** (services hold no business logic): PASS — the `try`/`catch` is a failure-path guard.
- **Rule 11** (ProGuard for new Service/Receiver/Worker): PASS — none added; a Phase 3 check
  confirms `proguard-rules.pro` is unmodified.
- **Rule 12** (`noCompress "tflite"`): PASS — explicit Phase 1 checkpoint that the Gradle edit
  leaves the `aaptOptions` block untouched.
- **Principles I, III, IV, V**: PASS. Principle IV is directly satisfied — an unmapped permission
  is removed, which the principle mandates.
- **Principle II**: PASS — no architectural boundary touched.
- **Both deviations** are recorded above with developer approval, not absorbed silently.

**Post-design verdict: PASS.** Ready for `/speckit-tasks`.
