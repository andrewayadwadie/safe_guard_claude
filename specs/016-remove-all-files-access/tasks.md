---

description: "Task list for 016-remove-all-files-access"
---

# Tasks: Replace All-Files Access with Parent-Granted Folder Access

**Input**: Design documents from `/specs/016-remove-all-files-access/`

**Prerequisites**: [plan.md](./plan.md), [spec.md](./spec.md), [research.md](./research.md), [data-model.md](./data-model.md), [contracts/media-write-access.md](./contracts/media-write-access.md), [contracts/build-guard.md](./contracts/build-guard.md), [quickstart.md](./quickstart.md)

**Tests**: **Yes — four automated test tasks.** The spec's Assumptions request automated coverage for the grant-state logic and the write-failure paths, which are new decision logic rather than untestable platform glue. The project has **no Robolectric**, so no test may touch a real framework class — see [contracts/media-write-access.md](./contracts/media-write-access.md) §Testability. Everything device-facing is verified against [quickstart.md](./quickstart.md), five of whose scenarios are merge gates.

**Organization**: Grouped by user story so each is independently deliverable and verifiable.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story the task serves (US1, US2, US3, US4)
- Exact file paths included in every task

## Path Conventions

Single-module Android app. `settings.gradle` includes `:app` only.

- Manifest: `app/src/main/AndroidManifest.xml`
- Utilities: `app/src/main/java/com/safeguard/parentalcontrol/util/`
- Services: `app/src/main/java/com/safeguard/parentalcontrol/service/`
- Presentation: `app/src/main/java/com/safeguard/parentalcontrol/presentation/`
- Unit tests: `app/src/test/java/com/safeguard/parentalcontrol/util/`
- Module build file: `app/build.gradle`
- Release docs: `docs/`

## Source footprint (the whole change)

| File | Reason |
| --- | --- |
| `app/src/main/AndroidManifest.xml` | Delete `MANAGE_EXTERNAL_STORAGE` |
| `app/src/main/java/com/safeguard/parentalcontrol/util/MediaWriteAccess.kt` | **NEW** — grant state + document write/delete seam |
| `app/src/main/java/com/safeguard/parentalcontrol/util/ImageBlurManager.kt` | Route three write call sites through the seam; add the failure ordering and the not-blurred reason |
| `app/src/main/java/com/safeguard/parentalcontrol/util/PreferencesManager.kt` | Download-notice flag, plus the completed-grant-targets set that lets REVOKED be told from NOT_REQUESTED |
| `app/src/main/java/com/safeguard/parentalcontrol/util/ImageProtectionStatus.kt` | **NEW, not foreseen** — the parent-facing status as pure functions, so it is testable without Robolectric |
| `app/src/main/java/com/safeguard/parentalcontrol/util/Constants.kt` | Key for that boolean |
| `app/src/main/java/com/safeguard/parentalcontrol/service/MediaFileObserver.kt` | Media-database observer trigger on API 30+ |
| `app/src/main/java/com/safeguard/parentalcontrol/presentation/setup/PermissionsSetupViewModel.kt` | Replace the all-files check; grant state; UiState |
| `app/src/main/java/com/safeguard/parentalcontrol/presentation/setup/PermissionsSetupScreen.kt` | Runtime photo request; folder-picker flow |
| `app/src/main/java/com/safeguard/parentalcontrol/presentation/settings/SettingsViewModel.kt` | Maximum Protection gates on grants |
| `app/src/main/java/com/safeguard/parentalcontrol/presentation/settings/SettingsScreen.kt` | **Not foreseen** — surfaces the gate; without it the new state existed but nothing showed it |
| `app/src/main/java/com/safeguard/parentalcontrol/worker/ImageScanWorker.kt` | **Not foreseen** — its log line claimed an image was blurred regardless of whether it was |
| `app/src/main/java/com/safeguard/parentalcontrol/presentation/imagereview/ImageReviewViewModel.kt` | Needs-access messaging for restore/delete |
| `app/build.gradle` | Extend the merged-manifest guard; versionCode 10 → 11 |
| `docs/release-checklist.md` | Delete the all-files justification |
| `docs/release-notes-1.2.2.md` | **NEW** |
| `app/src/main/res/values*/strings.xml` | Copy for the grant flow, the partial-grant explanation, the Download notice |

**Frozen — must NOT appear in the final diff**: `ml/**` (any classifier or model class), `ImageHasher.kt`, `TextHasher.kt`, `ProtectionStatusHelper.kt`, `AlertPipe.kt`, `ViolationNotifier.kt`, any repository, any `di/` module, `proguard-rules.pro`, `network_security_config.xml`, `MonitoringService.kt`, `ContentFilterVpnService.kt`, `TextMonitoringAccessibilityService.kt`, `.specify/memory/constitution.md`, `.kilo/**`, `app/build/**`.

> `ProtectionStatusHelper.kt` is frozen deliberately — see the plan's Structure Decision. Image access is not one of the five constitutional protection layers, and promoting it would ripple into the dashboard and the tamper-alert path for no requirement in this spec.

---

## Phase 1: Setup

**Purpose**: branch hygiene, and capture the "before" measurements that three merge gates compare against. Baselines taken after the code changes are worthless.

- [X] T001 Cut branch `016-remove-all-files-access` from **`fix/fgs-remove-data-sync`**, not from `main`, and confirm with `git log --oneline -1`.
      **Corrected during execution.** The task originally said to branch from `main` so feature 015's
      unmerged work would not ride along. That was wrong: `git rev-list --left-right --count
      main...fix/fgs-remove-data-sync` returns `0 11`, and `git show main:app/build.gradle` contains
      no `FOREGROUND_SERVICE_DATA_SYNC` guard. Branching from `main` would have discarded the guard
      that T041 extends, reverted `versionCode` to 9 under T045's 10 → 11 bump, and reintroduced the
      very permission feature 015 removed. 016 depends on 015, so it stacks on it.
- [X] T002 Create `specs/016-remove-all-files-access/evidence/` and record the device inventory in `specs/016-remove-all-files-access/evidence/devices.md`: every available device/emulator, its API level, and which rungs of the [quickstart.md](./quickstart.md) matrix cannot be covered
- [X] T003 [P] Record the pre-change permission state in `specs/016-remove-all-files-access/evidence/baseline-manifest.md`: `grep -c MANAGE_EXTERNAL_STORAGE app/build/intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml` after `./gradlew bundleRelease`. This is the "before" half of SC-001
- [ ] T004 [P] Baseline for SC-003 — on the **current** build, on an API 30+ device, run [quickstart.md](./quickstart.md) scenario 2 and record watch-to-blurred-gallery latency in `specs/016-remove-all-files-access/evidence/baseline-latency.md`. The new build must be no slower, so this number must exist before any code changes **[BLOCKED: child-device flows need a `POST /devices` registration against the live backend plus in-app consent — carried over from feature 015 T023; re-check whether the backend is reachable before marking blocked]**
- [ ] T005 [P] Baseline for SC-004 — on the **current** build, on an API 26–29 device, attempt blur, restore and delete, and record that all three fail (Audit Finding 2) in `specs/016-remove-all-files-access/evidence/baseline-legacy-writes.md`. The before-and-after pair is the evidence, not the after alone **[BLOCKED: no API 26–33 emulator image available locally — both AVDs reported API 37 during feature 015 T029; re-check for a newly installed image or a physical device]**

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: two device experiments whose outcomes decide whether the feature is viable at all and how one piece of it must be written.

**⚠️ CRITICAL**: T006 gates everything. The permission must not be deleted before it passes — a build with the permission gone and the read path broken is a build where child protection is silently off, and it looks fine from the UI.

- [ ] T006 **GATE** — prove research [R-004](./research.md) on hardware per [quickstart.md](./quickstart.md) scenario 1, on API 30, 33 and 36: revoke all-files access (`adb shell appops set com.safeguard.parentalcontrol MANAGE_EXTERNAL_STORAGE deny`), grant only the narrow photo permission (`adb shell pm grant … READ_MEDIA_IMAGES`), push a flagged test image, and confirm the classifier decodes it, the app-private backup appears and one parent alert is raised. Record logcat plus the `appops` output in `specs/016-remove-all-files-access/evidence/api30-verification.md` and siblings. **If this fails, stop and revisit [R-004](./research.md) — do not proceed to T013**
      **PARTIAL (2026-10-07).** Audit Finding 1 was confirmed on hardware and the target permission state applied — see `evidence/api37-verification.md`. The gate itself is NOT closed: the only available rung is API 37, the installed APK is Studio-instrumented and ANRs on launch, and no flagged test fixture was available, so detect-backup-alert was never observed. **T013 was implemented ahead of this gate** — recorded here because the task said not to. The code change is what fixes the gap the gate guards against, so the risk moves to the submission rather than the commit: the AAB must not be submitted until this passes.
- [ ] T007 Probe the one open documentation question, research [R-006](./research.md), per [quickstart.md](./quickstart.md) scenario 3 on an API 34+ device: revoke photo access, request it, choose **Select photos**, and record in `specs/016-remove-all-files-access/evidence/api34-verification.md` whether the platform reports the narrow permission as *granted* for backward compatibility. The answer decides whether T010 needs a capability probe behind the permission check or can trust it. **PARTIAL (2026-10-07):** the signal is confirmed available — the platform tracks `READ_MEDIA_VISUAL_USER_SELECTED` for this package although the app does not declare it — and T010 reads it. The UI behaviour on a real partial selection was not driven. See `evidence/api37-verification.md`

**Checkpoint**: the read path is proven and the partial-grant behaviour is known. User story work can begin.

---

## Phase 3: User Story 1 — Ship and keep protecting without all-files access (Priority: P1) 🎯 MVP

**Goal**: the store blocker is gone and detection, backup and alerting all still work on the narrow photo permission alone.

**Independent Test**: install the build, grant photos through the real setup flow, save a flagged test image to each watched folder, confirm one parent alert and one private backup per image, on each available API rung — with all-files access not held.

**Why this is a viable release on its own**: Maximum Protection ships **off** (`PreferencesManager.kt:162`), so a default install's behaviour is unchanged by this story. T018 keeps it honest for the minority who have it on.

### Tests for User Story 1

- [X] T008 [P] [US1] Unit test the image-protection status resolution in `app/src/test/java/com/safeguard/parentalcontrol/util/ImageProtectionStatusTest.kt`: `FULL`/`PARTIAL`/`DENIED` reading × setting on/off → the status and remedy from [data-model.md](./data-model.md) entity 4. Pure Kotlin over supplied inputs — no framework class, no Robolectric

### Implementation for User Story 1

- [X] T009 [US1] In `app/src/main/java/com/safeguard/parentalcontrol/presentation/setup/PermissionsSetupScreen.kt`, replace the `RequestMediaPermission` branch (currently lines 95–120, which route Android 11+ to `ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION`) with a real runtime request: `READ_MEDIA_IMAGES` on API 33+, `READ_EXTERNAL_STORAGE` below. Delete both all-files settings intents and the fallback. This is Audit Finding 1's fix and satisfies FR-003
- [X] T010 [US1] In `app/src/main/java/com/safeguard/parentalcontrol/presentation/setup/PermissionsSetupViewModel.kt`, replace `hasMediaPermission()`'s `Environment.isExternalStorageManager()` check (line 351) with a narrow-permission check, and add partial-grant handling per the T007 outcome. Satisfies FR-005, FR-006
- [X] T011 [US1] Add the denied and partial states to the setup UiState and screen copy in `PermissionsSetupViewModel.kt`, `PermissionsSetupScreen.kt` and `app/src/main/res/values/strings.xml` (plus the Arabic `values-ar`): plain-language reason, a retry action, and for partial the explanation that a hand-picked selection cannot contain images that arrive later. Errors via the existing `LaunchedEffect` snackbar path with `clearError()` (enforcement rule 10). Satisfies FR-005, FR-019
- [X] T012 [US1] In `app/src/main/java/com/safeguard/parentalcontrol/service/MediaFileObserver.kt`, add a media-database `ContentObserver` as the real-time trigger on API 30+ and keep the existing `FileObserver` registration below 30 (research [R-005](./research.md)). The observer must hand the downstream path the same inputs it gets today plus the relative path and display name that [R-003](./research.md) needs. **Do not touch anything downstream of the trigger** — the classifier, the two-stage pipeline, the thresholds, the duplicate-hash suppression and the alert call are all frozen (enforcement rule 7). Satisfies FR-004
- [X] T013 [US1] Delete the `MANAGE_EXTERNAL_STORAGE` `<uses-permission>` from `app/src/main/AndroidManifest.xml` (lines 45–51, including the `tools:ignore="ScopedStorage"` attribute), and leave a comment stating it must not return and why, pointing at `specs/016-remove-all-files-access/`. **Blocked by T006.** Satisfies FR-001
- [X] T014 [US1] Run `./gradlew bundleRelease` and confirm `grep -c MANAGE_EXTERNAL_STORAGE app/build/intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml` returns 0 (grep exits 1 — that is the pass). Compare against T003. Record in `specs/016-remove-all-files-access/evidence/manifest-after.md`. Satisfies SC-001
- [X] T015 [US1] Make the blur path fail honestly while no write mechanism exists yet: in `app/src/main/java/com/safeguard/parentalcontrol/util/ImageBlurManager.kt`, `replaceWithBlurred` must return a failure rather than a silent logged error, and `blurImage` must not report `Success` when the gallery file was not replaced. Without this, shipping US1 alone would tell a Maximum Protection parent an image was blurred when it was not. Satisfies FR-016, SC-007
- [ ] T016 [US1] Device verification per [quickstart.md](./quickstart.md) scenario 1 on the **new** build, using the real setup flow rather than `adb pm grant`: grant photos, place flagged images in `DCIM/Camera`, `Pictures` and `Download`, confirm one alert and one backup each. Record per rung in `specs/016-remove-all-files-access/evidence/`. Satisfies SC-002 **[BLOCKED by T004's blocker if the backend is unreachable]**
- [ ] T017 [US1] Device verification per [quickstart.md](./quickstart.md) scenario 3 on API 34+: choosing **Select photos** shows image protection as not active with its explanation, and the only remedy is to ask again for full access. Satisfies FR-005
- [ ] T018 [US1] **GATE** — device verification per [quickstart.md](./quickstart.md) scenario 2, latency half only: compare the new observer-triggered detection latency against the T004 baseline. Must be no slower. Satisfies SC-003 (trigger half; the gallery-refresh half is T031) **[BLOCKED by T004]**

**Checkpoint**: shippable. The store blocker is gone, default-install behaviour is unchanged, and no parent is told an image was blurred when it was not.

---

## Phase 4: User Story 2 — A one-time folder grant restores in-place blurring (Priority: P2)

**Goal**: the parent grants the picture folders once, and flagged images in them are blurred automatically with no further prompts — including on Android 8–10, where this has never worked.

**Independent Test**: with US1 in place, turn on Maximum Protection, complete the folder grant for the camera and pictures folders, save a flagged image to each, confirm the gallery thumbnail shows blurred, then reboot and repeat without any new prompt.

### Tests for User Story 2

- [X] T019 [P] [US2] Unit test grant-state resolution in `app/src/test/java/com/safeguard/parentalcontrol/util/MediaWriteAccessGrantStateTest.kt`: granted, missing, revoked, ancestor match (`DCIM` covers `DCIM/Screenshots`), descendant non-match (`DCIM/Camera` does not cover `DCIM/Screenshots`), a tree resolving to the storage root treated as no grant, and `Download` reported `UNGRANTABLE` distinctly from `NOT_REQUESTED`. Held-tree list supplied as an input per [contracts/media-write-access.md](./contracts/media-write-access.md) §Testability
- [X] T020 [P] [US2] Unit test the path mapping in `app/src/test/java/com/safeguard/parentalcontrol/util/MediaWriteAccessDocumentIdTest.kt`: relative path → document identifier, including segments and file names needing escaping, and a path no grant covers failing cleanly rather than throwing. Satisfies contract C-4
- [X] T021 [P] [US2] Unit test the failure ordering in `app/src/test/java/com/safeguard/parentalcontrol/util/ImageBlurWriteFailureTest.kt`: with the seam faked via mockk, a failure at resolve, at write and at verify each leaves the original restored from the backup, records a not-blurred reason, and reports failure. Satisfies FR-018 — this is the test that stands between a bug and a destroyed photo

### Implementation for User Story 2

- [X] T022 [US2] Create `app/src/main/java/com/safeguard/parentalcontrol/util/MediaWriteAccess.kt` implementing C-1 … C-6 of [contracts/media-write-access.md](./contracts/media-write-access.md): `@Singleton` with an `@Inject` constructor and **no** Hilt module entry (matching `ImageBlurManager`), platform document-contract APIs only with **no new dependency** (research [R-007](./research.md)), **no cached state** ([data-model.md](./data-model.md) entity 1), document identifiers built arithmetically and never by walking the tree ([R-003](./research.md)), and no activity required at the write site ([R-002](./research.md))
- [X] T023 [US2] Add the optional not-blurred reason to the backup metadata and to `BlurResult` in `app/src/main/java/com/safeguard/parentalcontrol/util/ImageBlurManager.kt`, keeping the existing on-disk metadata format readable so backups written by the all-files-access version still load ([data-model.md](./data-model.md) entity 3). Extend `app/src/test/java/com/safeguard/parentalcontrol/util/ImageBlurMetadataTest.kt` for the absent-reason case
- [X] T024 [US2] Route `replaceWithBlurred` through the seam in `ImageBlurManager.kt` and implement the six-step ordering from [contracts/media-write-access.md](./contracts/media-write-access.md): back up, **verify the backup**, resolve, write, **verify the write**, and on any failure restore from the backup and record the reason. `Blurred = true` only after a verified write. Satisfies FR-012, FR-016, FR-018
- [X] T025 [US2] Add the folder-grant flow to `PermissionsSetupViewModel.kt` and `PermissionsSetupScreen.kt`: one picker per target, pre-pointed at the target folder, each explained in plain language before it opens, launched through an activity-result contract. Accept a cancelled picker or an unexpected folder without losing grants already held, and leave missing targets re-requestable without restarting setup. Satisfies FR-007, FR-008, FR-009, FR-011
- [X] T026 [US2] Offer the `ROOT_SCREENSHOTS` target only on devices where that folder exists, evaluated when the flow runs rather than cached from first run, and never block setup completion on it. Satisfies FR-010
- [X] T027 [US2] In `app/src/main/java/com/safeguard/parentalcontrol/presentation/settings/SettingsViewModel.kt`, gate the Maximum Protection toggle on folder access: turning it on with no grant explains the dependency and starts the grant flow rather than enabling a setting that does nothing. Keep `applyBlurToUnblurredBackups()` (line 122) working, now reporting per-image reasons for anything it could not blur. Satisfies FR-020
- [X] T028 [US2] Add the one-time Download notice: a boolean in `app/src/main/java/com/safeguard/parentalcontrol/util/PreferencesManager.kt` with its key in `Constants.kt`, shown once and then suppressed, with copy in `values/strings.xml` and `values-ar/strings.xml`. Not repeated on every alert. Satisfies FR-022
- [X] T029 [US2] Surface revoked folder access: detection and alerting continue uninterrupted, the status shows blurring inactive with `REVOKED` as the distinct reason, and the parent is prompted to restore access. Satisfies FR-021
- [X] T030 [US2] Run `./gradlew testDebugUnitTest` and confirm T019–T021 and the extended T023 test pass, with no new failures beyond the two known `WordPieceTokenizerTest` line-ending artefacts
- [ ] T031 [US2] **GATE** — device verification per [quickstart.md](./quickstart.md) scenario 2, gallery half: with the camera folder granted, a flagged image's **gallery thumbnail** shows blurred, not merely the bytes on disk. If the existing media-scan refresh proves unreliable, apply the [R-009](./research.md) fallback. Satisfies SC-003 **[BLOCKED by T004]**
- [ ] T032 [US2] Device verification per [quickstart.md](./quickstart.md) scenario 4: grants survive three reboots and one app update with zero re-prompts; revocation degrades to detection-only with a reason; reinstall loses grants as expected. Satisfies FR-009, FR-021, SC-006
- [ ] T033 [US2] Device verification per [quickstart.md](./quickstart.md) scenario 5: an image in an ungranted watched folder, an image in `Download`, and an image in an unwatched folder each behave as the table says — and no flagged image is ever reported as blurred when it was not. Satisfies FR-016, FR-022, SC-007
- [ ] T034 [US2] **GATE** — device verification per [quickstart.md](./quickstart.md) scenario 6: induce a write failure three ways (volume full, grant revoked mid-write, original deleted mid-write) and confirm the original image is intact and decodable afterwards in every case, never truncated or zero-byte. Record file size and a successful decode per case. Satisfies FR-018
- [ ] T035 [US2] **GATE** — device verification per [quickstart.md](./quickstart.md) scenario 7 on API 26–29: blur, restore and delete all succeed where the T005 baseline recorded them failing. Satisfies SC-004 **[BLOCKED by T005's blocker — no API 26–33 image available locally as of feature 015]**

**Checkpoint**: Maximum Protection works again on Android 11+ and for the first time on Android 8–10, and the one capability given up (`Download`) is visible to the parent rather than hidden.

---

## Phase 5: User Story 3 — Parent review actions work through the granted folders (Priority: P2)

**Goal**: approve restores the original to the gallery; reject removes the gallery copy and the backup. A blur the parent cannot undo is a bug, not protection.

**Independent Test**: blur two test images via US2, approve one and reject the other, and confirm the gallery result and the backup state for each.

**Dependency**: needs T022 (the seam) from US2. Everything else in this phase is independent of US2's UI work.

### Implementation for User Story 3

- [X] T036 [P] [US3] Route `restoreOriginal` (`ImageBlurManager.kt:275`) through the seam, with the same failure reporting as T024 and no backup deleted until the restore is verified. Satisfies FR-013
- [X] T037 [P] [US3] Route `deleteImage` (`ImageBlurManager.kt:319`) through the seam, treating an already-absent document as success since the desired end state holds (contract C-6). Satisfies FR-014
- [X] T038 [US3] In `app/src/main/java/com/safeguard/parentalcontrol/presentation/imagereview/ImageReviewViewModel.kt`, handle the no-access case for both actions (lines 114 and 146): say plainly that folder access is needed, offer to re-request it, and lose no backup in the meantime. Snackbar via the existing error path. Satisfies FR-019, US3 scenario 3
- [X] T039 [US3] Confirm backups written by the all-files-access version remain restorable and deletable once folder access is granted, with no metadata migration — exercised by loading a pre-change backup directory. Satisfies FR-023
- [ ] T040 [US3] Device verification: approve one blurred image and reject another, then repeat both with folder access revoked. Then run [quickstart.md](./quickstart.md) scenario 9 — install the current release build, blur an image, install the new build over it **without clearing data**, and confirm every backup survives and the previously blurred image is still restorable. Satisfies FR-023, FR-024

**Checkpoint**: the full detect → blur → review → restore-or-delete cycle works end to end.

---

## Phase 6: User Story 4 — The permission cannot silently return (Priority: P3)

**Goal**: a dependency that contributes the permission into the merged manifest fails the build, not the store submission.

**Independent Test**: re-add the permission in a scratch commit, confirm the release build fails with an actionable message, remove it, confirm the build passes.

### Implementation for User Story 4

- [X] T041 [US4] Extend the existing foreground-service guard in `app/build.gradle` (the `process(Debug|Release)MainManifest` block at lines 204–295) into a banned-permission set per [contracts/build-guard.md](./contracts/build-guard.md) G-1: add `android.permission.MANAGE_EXTERNAL_STORAGE` alongside `FOREGROUND_SERVICE_DATA_SYNC`, keep value-equality comparison and comment stripping, and add the policy reason to the failure message (G-4). **Do not add a second task** — one guard, one place to read. **Must not disturb** the `aaptOptions { noCompress "tflite" }` block (enforcement rule 12, G-5)
- [X] T042 [US4] Run acceptance tests AT-1 … AT-6 from [contracts/build-guard.md](./contracts/build-guard.md) and record the results in `specs/016-remove-all-files-access/evidence/build-guard.md`. AT-3 (a comment mentioning the permission must not fail the build) and AT-4 (feature 015's check still fires) are the regression tests for this extension. Satisfies FR-025, FR-026, SC-009

**Checkpoint**: the removal is durable.

---

## Phase 7: Polish & Cross-Cutting Concerns

- [X] T043 [P] In `docs/release-checklist.md`, delete the `MANAGE_EXTERNAL_STORAGE — All files access` justification section (line 373 onward) and record in its place that the permission is removed, that no declaration is made, and that the build guard enforces it. Also close the deferral noted in `docs/release-notes-1.2.1.md` line 247. Satisfies FR-028
- [X] T044 [P] Write `docs/release-notes-1.2.2.md` for versionCode 11: the permission removal, the runtime photo permission the app now actually requests, the folder-grant step parents will see, the Android 8–10 fix, and the `Download` limitation stated plainly
- [X] T045 Bump `versionCode` 10 → 11 and `versionName` to `1.2.2` in `app/build.gradle` (lines 58–59)
- [ ] T046 Device verification per [quickstart.md](./quickstart.md) scenario 10: capture network traffic across a full detect → blur → review cycle and confirm only category, confidence and alert metadata leave the device — no image bytes, no file paths. The alert payload is unchanged by this feature, so any difference from the current build is a defect. Satisfies SC-010, Principle I
- [X] T047 Run the full gate set end to end: `./gradlew testDebugUnitTest` then `./gradlew bundleRelease`, then [quickstart.md](./quickstart.md) scenario 8 in full including the SC-009 negative test. Confirm only the two known `WordPieceTokenizerTest` line-ending failures
- [X] T048 Complete `specs/016-remove-all-files-access/evidence/`: one file per API rung, each entry recording scenario number, device, API level, command, observed output and pass/fail. Any scenario no available device can run is annotated **device-blocked** with its reason — never marked passed
- [X] T049 Verify the frozen list with `git diff --name-only main...HEAD` and confirm no file from the Source footprint's frozen set appears, in particular `ProtectionStatusHelper.kt`, any `di/` module, `proguard-rules.pro` and anything under `ml/`

---

## Dependencies & Execution Order

### Phase dependencies

- **Phase 1 Setup**: no dependencies. **Must finish before any code changes** — T003, T004 and T005 are the "before" halves of SC-001, SC-003 and SC-004 and cannot be captured afterwards.
- **Phase 2 Foundational**: depends on Phase 1. **T006 blocks T013 absolutely**, and gates the feature.
- **Phase 3 (US1)**: depends on Phase 2. Independently shippable.
- **Phase 4 (US2)**: depends on US1 for the runtime photo permission; the seam itself (T022) depends on nothing but Phase 2.
- **Phase 5 (US3)**: depends on T022 only. Can run in parallel with the rest of US2.
- **Phase 6 (US4)**: depends on T013 (there must be a removal to guard). Otherwise independent of everything.
- **Phase 7 Polish**: depends on all shipped stories.

### Critical path

```text
T001 → T002 → T004/T005 (baselines) → T006 GATE → T009…T012 → T013 → T014
                                                      ↓
                                     T022 → T024 → T031/T034 GATES
                                                      ↓
                                              T041 → T042 → T047
```

### Story dependencies

- **US1 (P1)**: after Phase 2. No dependency on any other story. **This is the MVP.**
- **US2 (P2)**: after US1's T009/T010 (the runtime request) exist. T022 and its tests can start earlier.
- **US3 (P2)**: after T022. Does not wait for the rest of US2.
- **US4 (P3)**: after T013.

### Parallel opportunities

- **Phase 1**: T003, T004, T005 — three different devices, three different evidence files.
- **Phase 4 tests**: T019, T020, T021 — three new test files, no shared state.
- **Phase 5**: T036 and T037 — different methods, both behind the same finished seam.
- **Phase 7**: T043 and T044 — different documentation files.
- **Across stories**: once T022 lands, one developer can take US3 (T036–T040) while another finishes US2's UI work (T025–T029).

---

## Parallel Example: User Story 2 tests

```bash
# Three independent test files, no shared fixtures:
Task: "Unit test grant-state resolution in app/src/test/java/com/safeguard/parentalcontrol/util/MediaWriteAccessGrantStateTest.kt"
Task: "Unit test path mapping in app/src/test/java/com/safeguard/parentalcontrol/util/MediaWriteAccessDocumentIdTest.kt"
Task: "Unit test the failure ordering in app/src/test/java/com/safeguard/parentalcontrol/util/ImageBlurWriteFailureTest.kt"
```

---

## Implementation Strategy

### MVP first (User Story 1 only)

1. Phase 1 Setup — capture the baselines while the old build still exists.
2. Phase 2 Foundational — **T006 must pass.**
3. Phase 3 US1 — the store blocker is gone, detection intact, Maximum Protection fails honestly.
4. **STOP and VALIDATE**: scenario 1 on every available rung.
5. Shippable. Maximum Protection ships off by default, so a default install behaves exactly as today.

### Incremental delivery

1. Setup + Foundational → the read path is proven.
2. US1 → ship. Store review unblocked.
3. US2 → Maximum Protection works again, and newly works on Android 8–10.
4. US3 → the review loop closes.
5. US4 → the removal becomes permanent.
6. Polish → docs, version bump, privacy capture, evidence.

US4 can be pulled forward to immediately after T013 if the team would rather have the guard in place before the UI work begins; nothing in US2 or US3 depends on it either way.

---

## Notes

- `[P]` tasks touch different files with no incomplete dependencies.
- **Do not reorder T006 before T013.** Deleting the permission with an unproven read path produces a build whose protection is off while the UI reports it on — the exact silent failure Principle I exists to prevent.
- **Do not take the T004 and T005 baselines after changing code.** Three merge gates compare against them.
- Device-blocked tasks are annotated with their reason, carried over from feature 015 where applicable. Re-check each blocker before accepting it: a newly installed emulator image or a reachable backend changes the answer.
- The two `WordPieceTokenizerTest` failures on this machine are a line-ending artefact, not a regression.
- Commit after each task or logical group. Stop at any checkpoint to validate a story independently.
