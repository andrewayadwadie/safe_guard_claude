---

description: "Task list for 015-remove-datasync-fgs-permission"
---

# Tasks: Remove the DATA_SYNC Foreground Service Declaration

**Input**: Design documents from `/specs/015-remove-datasync-fgs-permission/`

**Prerequisites**: [plan.md](./plan.md), [spec.md](./spec.md), [research.md](./research.md), [contracts/build-guard.md](./contracts/build-guard.md), [quickstart.md](./quickstart.md)

**Tests**: **No automated test tasks.** Spec FR-013 forbids new automated coverage for the service-category migration, which shipped with feature `013-play-store-release-readiness`. Verification is a release-blocking build guard (SC-009), merged-manifest inspection (SC-001), clean builds (SC-002), and a manual evidence matrix (FR-012, SC-003 → SC-007).

**Organization**: Grouped by user story so each is independently deliverable and verifiable.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story the task serves (US1, US2, US3)
- Exact file paths included in every task

## Path Conventions

Single-module Android app. `settings.gradle` includes `:app` only.

- Manifest: `app/src/main/AndroidManifest.xml`
- Services: `app/src/main/java/com/safeguard/parentalcontrol/service/`
- Module build file: `app/build.gradle`
- Release docs: `docs/release-checklist.md`

## Source footprint (the whole change)

| File | Reason |
| --- | --- |
| `app/src/main/AndroidManifest.xml` | Delete the orphaned `FOREGROUND_SERVICE_DATA_SYNC` permission (one line) |
| `app/src/main/java/com/safeguard/parentalcontrol/service/MonitoringService.kt` | Add the missing `try`/`catch` around `startForeground` |
| `app/build.gradle` | Add the release-blocking merged-manifest guard |
| `docs/release-checklist.md` | Record the removal; close feature 013's deferral |

**Frozen — must NOT appear in the final diff**: `ContentFilterVpnService.kt`, `BootReceiver.kt`, `BootServiceStartWorker.kt`, `ServiceRestartReceiver.kt`, `TamperDetectionReceiver.kt`, `SyncWorker.kt`, any DI module, any ML class, `proguard-rules.pro`, `network_security_config.xml`, `.specify/memory/constitution.md`, `.kilo/**`.

---

## Phase 1: Setup (Baseline Capture)

**Purpose**: create the working branch and capture the "before" state. Two success criteria are comparative — SC-002 is "same or fewer warnings" and FR-006 is "notification unchanged" — so neither can be judged without a baseline taken first.

- [X] T001 Create and switch to branch `fix/fgs-remove-data-sync` from the current branch; make no commit yet (plan.md Phase 3 defers committing until Phase 3 passes)
- [X] T002 [P] Capture the pre-change build warning baseline: run `./gradlew clean assembleDebug` and `./gradlew bundleRelease`, save the full warning set to `specs/015-remove-datasync-fgs-permission/baseline-warnings.txt`; this is the comparison basis for SC-002
- [X] T003 [P] Capture the pre-change merged manifest to `specs/015-remove-datasync-fgs-permission/baseline-merged-manifest.xml` by copying `app/build/intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml`; confirm it contains `FOREGROUND_SERVICE_DATA_SYNC` at the app-manifest origin, so the removal is provable as a delta
- [ ] T004 [P] Capture the pre-change monitoring notification: install the debug build on Target A, screenshot the notification shade, and record channel, importance and dismissibility in `specs/015-remove-datasync-fgs-permission/quickstart.md` step 4.7; this is the comparison basis for FR-006 **[BLOCKED: needs a paired child device for the notification baseline.]**

**Checkpoint**: branch exists, baselines recorded. Any later claim of "unchanged" is now falsifiable.

---

## Phase 2: Foundational (Source Changes — Blocking Prerequisites)

**Purpose**: the two source edits. Every user story verifies the final binary, so no story work can begin until both are in place.

**⚠️ CRITICAL**: no user story verification is valid until this phase completes — verifying an intermediate binary proves nothing about what ships.

- [X] T005 Delete line 21 of `app/src/main/AndroidManifest.xml`: `<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />`. Change nothing else in the file. Specifically: do **not** touch `foregroundServiceType="specialUse"` (line 116, already correct), do **not** touch the `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` value (lines 118–120, developer confirmed the shipped wording stays), do **not** add `tools:node="remove"` (the merger report proves a single app-manifest origin), and do **not** delete the explanatory XML comment at lines 115–118 (it is the human guardrail against this regression). Satisfies FR-002
- [X] T006 Wrap the existing `startForeground` branches in `app/src/main/java/com/safeguard/parentalcontrol/service/MonitoringService.kt` (lines ~231–239) in `try`/`catch`, mirroring `ContentFilterVpnService.kt:267–282` exactly. On failure: `Timber.e(e, "Failed to start foreground service (API ${Build.VERSION.SDK_INT})")` then `return START_NOT_STICKY`. Keep both API branches byte-identical in behaviour; keep the comment at lines 228–230. Do **not** change notification content, channel, the enforcement loop, image monitoring, sync scheduling, `onTaskRemoved`, `onTimeout` or `onDestroy`. Introduce no new pattern — the reference implementation already exists in the repo
- [X] T007 Checkpoint: `./gradlew assembleDebug` passes with no new foreground-service warning versus `baseline-warnings.txt`
- [X] T008 Verify the frozen set is untouched: `git diff --stat` lists only `app/src/main/AndroidManifest.xml` and `MonitoringService.kt` at this point, and `git diff app/src/main/AndroidManifest.xml` shows exactly one deleted line

**Checkpoint**: source changes complete and compiling. User story verification can begin.

---

## Phase 3: User Story 1 - The developer gets a clean store declaration (Priority: P1) 🎯 MVP

**Goal**: the shipped bundle carries no data-transfer foreground-service declaration, a future dependency upgrade cannot silently reintroduce one, and the release documentation records the change.

**Independent Test**: build the release bundle and inspect its merged manifest — zero `FOREGROUND_SERVICE_DATA_SYNC`, zero `dataSync` service type, both services still `specialUse` with their subtype properties. Then reinsert the permission and confirm the build fails. No device required.

### Implementation for User Story 1

- [X] T009 [US1] Add the release-blocking manifest guard to `app/build.gradle` per `specs/015-remove-datasync-fgs-permission/contracts/build-guard.md`: hook `tasks.matching { it.name ==~ /process(Debug|Release)MainManifest/ }.configureEach { doLast { ... } }`, resolve the merged manifest from the task's own output directory (never a hard-coded path), and throw `GradleException` on violation. Place it beside the existing `preReleaseBuild` secrets guard and follow that guard's message style. Satisfies FR-010
- [X] T010 [US1] Implement the guard's matching rule **structurally**, not by substring: compare the `android:name` attribute value of `uses-permission` elements for equality against `android.permission.FOREGROUND_SERVICE_DATA_SYNC`, and split each `android:foregroundServiceType` attribute value on `|` to compare tokens. A bare `text.contains('dataSync')` **will fail the build on the manifest's own explanatory comment** — this is the single most likely implementation mistake. Use `XmlSlurper` or attribute-assignment matching after stripping XML comments
- [X] T011 [US1] Make the guard fail — not pass — when the merged manifest file cannot be found. A guard that silently skips when its input is absent reports safety it never checked
- [X] T012 [US1] Add the guard's failure message to `app/build.gradle`: headline, bullet per violation, a pointer to `app/build/outputs/logs/manifest-merger-<variant>-report.txt` (the only place that names a library origin), the one-line consequence (≈6h/24h cap on API 35+, cannot start from `BOOT_COMPLETED`), and a reference to `specs/015-remove-datasync-fgs-permission/contracts/build-guard.md`. Emit nothing on the success path
- [X] T013 [US1] Verify `app/build.gradle` constraints hold: `git diff app/build.gradle | grep -i "noCompress\|implementation\|libs\."` is empty — proving `aaptOptions { noCompress "tflite" }` is byte-identical (enforcement rule 12) and no dependency was added, removed or upgraded (the basis on which editing this guardrailed file was approved). Confirm the existing `preReleaseBuild` guard is unmodified
- [X] T014 [US1] Run `./gradlew clean assembleDebug` — passes, guard silent, warning set no worse than `baseline-warnings.txt` (SC-002)
- [X] T015 [US1] Run `./gradlew bundleRelease` — passes, new guard silent, existing secrets guard silent (SC-002)
- [X] T016 [US1] Inspect the shipped bundle per `quickstart.md` step 3.3a: `bundletool dump manifest --bundle app/build/outputs/bundle/release/app-release.aab`. Expect zero `FOREGROUND_SERVICE_DATA_SYNC`, every `foregroundServiceType` exactly `specialUse` (two of them), two `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` properties, and `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE` both still present. Satisfies SC-001 and FR-003
- [X] T017 [US1] If `bundletool` is unavailable (it is not on `PATH` in this environment), use the `quickstart.md` step 3.3b fallback: the text merged manifest **plus** `aapt2 dump xmltree` against the release APK from SDK build-tools 36.0.0. Record both — the text intermediate alone is not sufficient evidence for SC-001, because it is not the shipped artefact
- [X] T018 [US1] Guard negative test, acceptance steps 1–5 of `contracts/build-guard.md`: reinsert the permission → `bundleRelease` **fails** with the guard's message naming it; revert → passes; set a service's type to `dataSync|specialUse` → `assembleDebug` **fails** naming the service and the combined value; revert → passes. Satisfies SC-009
- [X] T019 [US1] Guard self-regression test, acceptance step 6 of `contracts/build-guard.md`: with the real manifest, confirm the explanatory XML comment containing the word `dataSync` is still present **and** the build passes. This proves structural matching. Do not delete the comment to make this easier
- [X] T020 [P] [US1] Repo-wide grep per `quickstart.md` step 3.5: zero hits for `FOREGROUND_SERVICE_DATA_SYNC`, `FOREGROUND_SERVICE_TYPE_DATA_SYNC`, and `foregroundServiceType="[^"]*dataSync`, excluding `build/`, `specs/`, `.kilo/`, `.git/`, `.gradle/`. Explanatory prose hits for a bare `dataSync` are **expected to remain** in the manifest, `MonitoringService.kt`, `ContentFilterVpnService.kt`, the constitution and the release checklist. Satisfies FR-004
- [X] T021 [P] [US1] Update `docs/release-checklist.md`: record that `FOREGROUND_SERVICE_DATA_SYNC` is removed, that the store console declaration is special-use only, and that feature 013's deliberate deferral (task `T035`, FR-027) is now closed. Satisfies FR-011
- [X] T022 [US1] Confirm **no constitution amendment** is made. `.specify/memory/constitution.md` already states `TYPE_SPECIAL_USE` as current (line 141) and records the migration in its v2.1.0 history. Nothing there misstates the shipped state, so nothing there may be rewritten. Verify the file is absent from `git diff --stat`

**Checkpoint**: the release blocker is cleared and guarded. US1 is independently verifiable with no device. **This is the MVP** — it is the whole reason the feature exists.

---

## Phase 4: User Story 2 - Protection on a supervised device survives the change (Priority: P2)

**Goal**: prove the change caused no regression in steady-state enforcement, on both a modern and a legacy platform target.

**Independent Test**: on a supervised device, confirm the running service reports the special-use category, a screen-time limit still raises the lock screen while the app is backgrounded, and the notification is unchanged. Repeat the applicable subset on API 26–33.

**Evidence rule (FR-012)**: every task below records a log line or `dumpsys` excerpt, the API level, and the device or emulator identity into the evidence matrix in `quickstart.md`. A row with no evidence counts as unverified.

### Implementation for User Story 2

- [ ] T023 [US2] Install the debug build on Target A (API 35 or 36), log in as child, complete pairing, grant the five protection permissions, and **accept the monitoring disclosure**. `MonitoringService` has a consent gate that calls `startForeground` then stops itself if the disclosure is not accepted — check this before suspecting the change. Per `quickstart.md` step 4.1 **[BLOCKED: pairing requires a POST /devices registration on the live backend plus in-app consent.]**
- [ ] T024 [US2] Run `quickstart.md` step 4.2 on Target A: `adb shell dumpsys activity services com.safeguard.parentalcontrol`. Expect `MonitoringService` with `isForeground=true` and the special-use type bit `0x40000000`, and **no** `dataSync` bit `0x00000001`. Record the verbatim lines. Satisfies SC-003 **[BLOCKED by T023.]**
- [ ] T025 [US2] Run `quickstart.md` step 4.3 on Target A: toggle protection off and on, then check logcat. Expect `MonitoringService started`. Expect **absent**: `ForegroundServiceStartNotAllowedException`, `SecurityException`, `MissingForegroundServiceTypeException`, `InvalidForegroundServiceTypeException`, `Failed to start foreground service`. The last string is the new T006 `catch` — if it appears, the foreground start genuinely failed; investigate, do not dismiss. Satisfies FR-008 (app-open path) **[BLOCKED by T023.]**
- [ ] T026 [US2] Run `quickstart.md` step 4.6 on Target A **three times**: set a short screen-time limit from the paired parent device, background the app, wait for the limit, confirm `LockScreenActivity` appears. Record all three. Satisfies SC-005 (3 of 3) **[BLOCKED by T023.]**
- [ ] T027 [P] [US2] Run `quickstart.md` step 4.7 on Target A: compare the notification shade against the T004 baseline screenshot. Text, icon, channel, importance and non-dismissibility must be identical — including the existing active-protection wording, since rebranding is out of scope. Satisfies FR-006 **[BLOCKED by T023 and T004.]**
- [ ] T028 [US2] Run `quickstart.md` step 4.8 on Target A: with protection running past the ≈6h/24h window, confirm `isForeground=true`, enforcement still fires (repeat T026 once), and **no** `onTimeout` log line. `MonitoringService.onTimeout()` is a defensive handler that should never fire for a special-use service — if it does, that is a real finding. State in the evidence whether platform accounting or a wall-clock run was used. Satisfies SC-007 **[BLOCKED by T023.]**
- [ ] T029 [US2] Run `quickstart.md` step 4.9 on Target B (API 26–33): repeat steps 4.2, 4.3, 4.6 and 4.7. Expect everything working, zero crashes, no permission failure, and **no service type reported** — types do not exist before API 34, and that absence is the correct result. Removing the permission must be a complete no-op here, which is the entire point of this target. Satisfies SC-006 **[BLOCKED: no API 26-33 emulator image available locally (both AVDs report API 37).]**

**Checkpoint**: steady-state protection verified unchanged on both targets. US1 and US2 both hold independently.

---

## Phase 5: User Story 3 - Monitoring recovers from reboot and from being killed (Priority: P3)

**Goal**: prove the recovery paths — where a wrong service category fails loudest — still work.

**Independent Test**: reboot a supervised device and confirm both services come back; swipe the app from recents and confirm monitoring returns on the existing schedule.

### Implementation for User Story 3

- [ ] T030 [US3] Run `quickstart.md` step 4.4 on Target A **three times**: `adb reboot`, wait for `sys.boot_completed=1`, then confirm via `dumpsys` that `MonitoringService` is running and foreground, and `ContentFilterVpnService` too if content filtering was enabled beforehand. Confirm `adb logcat -b all -d` shows no start-not-allowed and no security exception. Satisfies SC-004 (3 of 3) and FR-008 (boot path, via `BootReceiver` / `BootServiceStartWorker` — neither modified) **[BLOCKED by T023.]**
- [ ] T031 [US3] Run `quickstart.md` step 4.5 on Target A: swipe the app out of recents, then confirm `Monitoring service restart scheduled via AlarmManager` in logcat, the service back up after the restart delay, and the tamper-alert path behaving as before. **Timing must be unchanged** — the restart delay is frozen by FR-009. Satisfies FR-009 and FR-008 (restart path, via `ServiceRestartReceiver` — not modified) **[BLOCKED by T023.]**
- [ ] T032 [P] [US3] Repeat the reboot check (step 4.4) once on Target B (API 26–33): both services return, zero crashes. Extends SC-006 to the recovery path **[BLOCKED by T023 and T029.]**

**Checkpoint**: all three user stories independently verified.

---

## Phase 6: Polish & Cross-Cutting Concerns

- [ ] T033 Fill in the FR-012 evidence matrix in `specs/015-remove-datasync-fgs-permission/quickstart.md` completely: every row carries result, API level, device or emulator identity, and concrete evidence. Any blank row means that criterion is unverified and the completion gate is not met **[PARTIAL: matrix filled for every criterion verifiable without a paired device; blocked rows documented with the reason.]**
- [X] T034 Final frozen-file audit per `quickstart.md` step 3.6: `git diff --stat` lists **exactly four** paths — `app/src/main/AndroidManifest.xml`, `MonitoringService.kt`, `app/build.gradle`, `docs/release-checklist.md`. Confirm absent: `ContentFilterVpnService.kt`, `BootReceiver.kt`, `BootServiceStartWorker.kt`, `ServiceRestartReceiver.kt`, `TamperDetectionReceiver.kt`, `SyncWorker.kt`, every DI module, every ML class, `proguard-rules.pro`, `network_security_config.xml`, the constitution, and `.kilo/**`
- [X] T035 [P] Delete the temporary baseline artefacts `baseline-warnings.txt` and `baseline-merged-manifest.xml` from `specs/015-remove-datasync-fgs-permission/`, or fold their relevant conclusions into the evidence matrix. They served Phase 1 comparison and should not ship as feature documentation
- [ ] T036 Confirm the completion gate from `plan.md`: SC-001, SC-002 and SC-009 pass, and the FR-012 matrix is complete. SC-008 is explicitly **non-blocking** **[PARTIAL: SC-001, SC-002 and SC-009 PASS. Gate not fully met because the FR-012 matrix has blocked rows.]**
- [X] T037 Commit on branch `fix/fgs-remove-data-sync` with an English message. Commit only after Phase 3 (US1) passes — `plan.md` Phase 3 defers committing until then
- [X] T038 Write the final summary listing every changed file with a one-line reason each, as required by the plan input's Deliverables section
- [X] T039 Hand SC-008 to the developer as post-merge confirmation: after the new bundle is uploaded and processed, the store console's foreground-service declaration form presents only the Special use section. This task is **not a merge gate** — it depends on an upload this feature does not perform. Filling in the form content remains the developer's manual step

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: no dependencies. T002/T003 must run **before** T005/T006, or the comparative criteria SC-002 and FR-006 become unprovable
- **Foundational (Phase 2)**: depends on Setup. **BLOCKS all user stories** — verifying a binary that lacks T005/T006 proves nothing about what ships
- **US1 (Phase 3)**: depends on Foundational. No device needed
- **US2 (Phase 4)**: depends on Foundational. Independent of US1 — a device can be verified before the build guard exists
- **US3 (Phase 5)**: depends on Foundational. Independent of US1 and US2
- **Polish (Phase 6)**: T033/T034/T036 depend on all desired stories; T037 depends on US1 passing

### User Story Dependencies

- **US1 (P1)**: starts after Phase 2. No dependency on US2 or US3
- **US2 (P2)**: starts after Phase 2. No dependency on US1 or US3
- **US3 (P3)**: starts after Phase 2. No dependency on US1 or US2

All three stories are genuinely independent: US1 is build-only, US2 and US3 are device-only and exercise disjoint behaviour (steady state versus recovery).

### Within Each Story

- **US1**: guard implementation (T009 → T010 → T011 → T012) is sequential — same file, same block. T013 after them. Builds (T014, T015) before inspection (T016/T017) before the negative tests (T018, T019). T020/T021 are independent of the guard work
- **US2**: T023 (install and pair) gates everything else. T024–T028 on Target A; T029 on Target B
- **US3**: T030 and T031 both need a paired device from T023. T032 needs Target B paired

### Parallel Opportunities

- **Phase 1**: T002, T003, T004 in parallel
- **Phase 3**: T020 and T021 in parallel with the T009–T019 guard chain (different files)
- **Phase 4 / Phase 5**: with two devices, Target A work (T024–T028, T030, T031) and Target B work (T029, T032) run in parallel
- **Across stories**: once Phase 2 lands, one person can run US1 on a workstation while another runs US2 and US3 on devices — US1 touches no device and US2/US3 touch no build file
- **Phase 6**: T035 in parallel with T033/T034

---

## Parallel Example: Phase 1 Setup

```bash
# Capture all three baselines together:
Task: "Capture pre-change build warning baseline to baseline-warnings.txt"
Task: "Capture pre-change merged manifest to baseline-merged-manifest.xml"
Task: "Capture pre-change monitoring notification screenshot on Target A"
```

## Parallel Example: Phase 3 User Story 1

```bash
# The guard chain is sequential (same file), but these run alongside it:
Task: "Repo-wide grep for DATA_SYNC / dataSync attribute values"
Task: "Update docs/release-checklist.md to record the removal"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Phase 1: Setup — capture baselines **first**
2. Phase 2: Foundational — delete the line, add the `try`/`catch` (CRITICAL, blocks all stories)
3. Phase 3: US1 — guard, builds, bundle inspection, negative tests, docs
4. **STOP and VALIDATE**: SC-001, SC-002, SC-009 pass
5. The release blocker is cleared at this point. US2 and US3 are regression assurance, not new capability

### Incremental Delivery

1. Setup + Foundational → source change complete, compiling
2. US1 → release blocker cleared and guarded → **MVP**
3. US2 → steady-state protection proven unchanged on both targets
4. US3 → recovery paths proven unchanged
5. Polish → evidence matrix complete, frozen-file audit, commit

### Parallel Team Strategy

1. Complete Setup + Foundational together — nothing else is valid until T005/T006 land
2. Then split:
   - Developer A: US1 (workstation only, no device)
   - Developer B: US2 + US3 on Target A
   - Developer C: US2 + US3 subset on Target B
3. Reconvene for Phase 6

---

## Notes

- **No test tasks by design.** FR-013 forbids new automated coverage for a migration that already shipped with feature 013. Recurrence is prevented by the T009–T012 build guard, not by tests
- **The one trap worth repeating**: a substring search for `dataSync` in the guard will fail the build on the manifest's own explanatory comment at lines 115–118. Match structurally. T019 is the regression test for exactly this
- **Two pre-existing issues are deliberately out of scope**, recorded in `research.md`: `SyncWorker.kt:441` uses `setExpedited` with no `getForegroundInfo` override (can fail on API 26–30), and `MonitoringService.scheduleServiceRestart()` arms `PendingIntent.getService` rather than `getForegroundService`. Neither is a data-transfer dependency, neither is affected by this removal, and both sit behind guardrails. Do not fix them here
- `[P]` tasks touch different files with no incomplete dependencies
- Commit after US1 passes, then per logical group
- Stop at any checkpoint to validate a story independently
