---

description: "Task list for 017-app-lock-enforcement"
---

# Tasks: Parent App Lock Reliably Enforced on the Child Device

**Input**: Design documents from `/specs/017-app-lock-enforcement/`

**Prerequisites**: [plan.md](./plan.md), [spec.md](./spec.md), [research.md](./research.md),
[data-model.md](./data-model.md), [contracts/](./contracts/), [quickstart.md](./quickstart.md)

**Tests**: Included. SC-010 explicitly requires automated coverage of the lock decision, the
emptied-list transmission, and rule restore after restart — there is no test anywhere in the
repository covering app blocking today.

**Organization**: grouped by user story. The grouping is crossed by one hard gate: **Phase 6 is a
hardware checkpoint** that must be reported before any child-side work begins, because three
blocking backend assumptions (A1, A4, A7) can only be settled on real devices and a negative result
changes the design of phases 7–10.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on an incomplete task)
- **[Story]**: the user story the task serves (US1–US7 from spec.md)

## Path Conventions

Mobile, single module. Production code under
`app/src/main/java/com/safeguard/parentalcontrol/`, tests under
`app/src/test/java/com/safeguard/parentalcontrol/`. Paths below are repo-relative and abbreviated
after the first use of each directory.

**Parallelism is genuinely limited here.** Nine of the production changes land in three files
(`ScreenTimeLimitsViewModel.kt`, `ScreenTimeLimitsScreen.kt`, `MonitoringService.kt`), so most tasks
are sequential by file contention, not by logic. `[P]` is marked only where it genuinely holds —
mainly the new test files.

---

## Phase 1: Setup

**Purpose**: branch, baseline, and the one piece of shared infrastructure every later phase logs
through.

- [X] T001 Create branch `017-app-lock-enforcement` off `016-remove-all-files-access` (**revised at execution time**: `main` is 14 commits behind `016`, and `016..main` is empty, so branching off `main` would have discarded the account-deletion, Play-release-readiness and permission-removal work that every `file:line` reference in this feature was read against. Branched off the current tip instead; 016 must still merge to `main` independently)
- [X] T002 Record a baseline: run `./gradlew assembleDebug` and note that `ml` `WordPieceTokenizerTest` failures are a pre-existing Windows CRLF artefact, not a regression from this feature
- [X] T003 [P] Create `app/src/main/java/com/safeguard/parentalcontrol/util/AppLockPipe.kt` with `TAG = "AppLock"` and `d/i/w/e` helpers, modelled on `util/AlertPipe.kt` including its privacy header (metadata only; never raw monitored content, never a full token) — satisfies FR-024/FR-025 and is used by every phase from here on

**Checkpoint**: `AppLockPipe` compiles; nothing else has changed behaviour.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: the per-field save machinery every parent-side story needs. Research decision R7.

**⚠️ CRITICAL**: no user story work can begin until this phase is complete.

- [X] T004 Add `enum class RuleField { DAILY_LIMIT, BEDTIME, STUDY_TIME, STUDY_TIME_APPS, DEVICE_LOCK, APP_LIMITS, BLOCKED_APPS, ACTIVE }` and extend `ScreenTimeLimitsUiState` with `savingFields: Set<RuleField>`, `confirmedState: ConfirmedRuleValues` and `enforcementWarning: String?` in `presentation/screentimelimits/ScreenTimeLimitsViewModel.kt` (per data-model.md §4; keep `isLoading` meaning initial screen load only, so one control's save never blocks another per FR-003)
- [X] T005 Add the save scaffolding to `ScreenTimeLimitsViewModel.kt`: a `MutableMap<RuleField, Job>` for per-field debounce (250 ms, cancel-and-replace), a `Mutex` so at most one `PUT` is in flight for the screen, and a private `saveField(field, mutate, request)` helper that performs the optimistic update, awaits the result, then either confirms or rolls back (FR-004, FR-005, FR-006; `Mutex` matches the existing idiom at `ScreenTimeRulesRepository.kt:43` and `ProtectionMonitorWorker.kt:87`)
- [X] T006 Add `AppLockPipe` entries to `data/repository/ScreenTimeRulesRepository.kt` `updateScreenTimeRules` (`:147-192`): log the field names present in the request, the list sizes, and the HTTP outcome — no signature change, and no token, email or monitored content in the message

**Checkpoint**: builds; `saveField` is unused so far, so behaviour is unchanged.

---

## Phase 3: User Story 1 — A lock the parent sets is actually saved (Priority: P1)

**Goal**: every control on the Screen Time Limits screen persists its own change at the moment it is
made, with no save button anywhere.

**Independent test**: toggle a lock, force-stop the parent app, reopen the screen — the lock is still
on, and no save action was ever taken.

**This is the root cause.** Nothing else in the feature can be verified until it lands.

### Tests for US1

- [X] T007 [P] [US1] Create `app/src/test/java/com/safeguard/parentalcontrol/screentimelimits/ScreenTimeLimitsSaveTest.kt` with a test asserting a lock toggle produces a request carrying **only** `blocked_apps` (plus `is_active`), and that `daily_limit`, `bedtime_*` and `app_limits` are absent from the body (FR-003, research.md R1)
- [X] T008 [P] [US1] Add a test to the same file asserting that three rapid toggles of one control issue exactly one request, for the settled value, and that a late response for a superseded value does not overwrite the newer one (FR-006)

### Implementation for US1

- [X] T009 [US1] Rewrite `addBlockedApp` / `removeBlockedApp` (`ScreenTimeLimitsViewModel.kt:331-351`) to route through `saveField(RuleField.BLOCKED_APPS, …)`, calling `screenTimeRulesRepository.updateScreenTimeRules(deviceId, blockedApps = newList)` — the full list for that one field, never the whole rule state
- [X] T010 [US1] Rewrite `setRulesActive` (`:249-252`) to save through `saveField(RuleField.ACTIVE, …)`, and make the `BLOCKED_APPS` add path also send `isActive = true` **only when the master switch is already on**, never flipping a switch the parent turned off (FR-008a, FR-008b)
- [X] T011 [US1] Rewrite the remaining boolean controls to save on change: `setDailyLimitEnabled` (`:211-213`), `setBedtimeEnabled` (`:228-230`), `setStudyTimeEnabled` (`:255-257`), `setDeviceLocked` (`:301-303`) — each sending only its own field, with the existing "send 0 to clear" convention preserved for a disabled daily limit (`:363-370`)
- [X] T012 [US1] Rewrite the non-switch controls to save on edit-finish, not on every intermediate value: `setDailyLimit` (`:216-224`), `setBedtimeStart`/`setBedtimeEnd` (`:233-247`), `setStudyTimeStart`/`setStudyTimeEnd` (`:260-274`), `setDeviceLockedMessage` (`:306-310`) — FR-026, so a slider saves on release and a picker on confirm; requires the matching `onValueChangeFinished` / confirm-callback wiring in `presentation/screentimelimits/ScreenTimeLimitsScreen.kt`
- [X] T013 [US1] Rewrite the remaining collection controls to save on change: `addAppLimit`/`removeAppLimit` (`:314-328`) and `addStudyTimeAllowedApp`/`removeStudyTimeAllowedApp` (`:277-295`)
- [X] T014 [US1] Delete `saveRules()` (`:353-420`) in its entirety, re-routing its `PARENT CONFIG` log lines (`:372-381`) through `AppLockPipe` at the per-field save sites so the parent hop stays observable
- [X] T015 [US1] Remove the `floatingActionButton` block from `ScreenTimeLimitsScreen.kt` (`:127-133`) and the now-unused `Icons.Default.Save` import (FR-002)
- [X] T016 [US1] Delete the dead `rememberInstalledApps` helper from `ScreenTimeLimitsScreen.kt` (`:1055-1062`) — it has no caller and would enumerate the **parent's** installed apps, which is a privacy smell if it ever gained one
- [X] T017 [US1] Run `./gradlew assembleDebug` and `./gradlew testDebugUnitTest --tests "*ScreenTimeLimitsSaveTest*"`

**Checkpoint**: a toggle reaches the network with no button press. US1 is independently demonstrable.

---

## Phase 4: User Story 2 — The parent can trust what the switch shows (Priority: P1)

**Goal**: the switch reflects server-confirmed state; a failed save reverts it and says so.

**Independent test**: put the parent device in airplane mode, toggle a lock, watch the switch return
to its previous position with a Snackbar.

**Depends on**: Phase 3 (shares the `saveField` call sites).

### Tests for US2

- [X] T018 [P] [US2] Add a test to `ScreenTimeLimitsSaveTest.kt`: on a failed save the control returns to its last server-confirmed value and `uiState.error` is set (FR-005)

### Implementation for US2

- [X] T019 [US2] Surface per-control progress in `ScreenTimeLimitsScreen.kt` from `uiState.savingFields` — a row whose field is saving shows an in-progress affordance; `isLoading` keeps driving only the initial full-screen load (FR-004)
- [X] T020 [US2] Complete the rollback branch of `saveField` in `ScreenTimeLimitsViewModel.kt`: restore the field from `confirmedState`, set `error` from `NetworkResult.Error.message`, and update `confirmedState` on success — errors surface through the existing `LaunchedEffect(uiState.error)` Snackbar at `ScreenTimeLimitsScreen.kt:78-93` with `clearError()` after display, per constitution rule 10
- [X] T021 [US2] Make the master-switch card in `ScreenTimeLimitsScreen.kt` (`:431-480`) state plainly that no locks are enforced while it is off, so a parent cannot read a populated Blocked Apps list as active protection (FR-008c)
- [X] T022 [US2] Run `./gradlew assembleDebug`, then quickstart scenario (b) — build green; scenario (b) runs with the rest of the on-device set at T026, since it needs the paired hardware

**Checkpoint**: no state on screen can disagree with the server without the parent being told.

---

## Phase 5: User Story 5 — Unlocking works, including the last locked app (Priority: P1)

**Goal**: removing the final locked app is transmitted as an explicit empty list.

**Independent test**: lock exactly one app, unlock it, confirm the request body contains
`"blocked_apps": []`.

**Depends on**: Phase 3.

### Tests for US5

- [X] T023 [P] [US5] Add a test to `ScreenTimeLimitsSaveTest.kt` asserting that an emptied list serialises as `"blocked_apps": []` and that the key is present in the body — the same assertion for `app_limits` (`{}`) and `study_time_allowed_apps` (`[]`) (FR-007, data-model.md §2)

### Implementation for US5

- [X] T024 [US5] Delete the `.ifEmpty { null }` coercions that survived the `saveRules` removal: `studyTimeAllowedApps`, `appLimits`, `blockedApps` and `deviceLockedMessage` (formerly `ScreenTimeLimitsViewModel.kt:394-396`, `:393`) — an emptied collection must be sent, not omitted, because Gson omits nulls (`NetworkModule.kt:39-42`) and an absent key means "leave unchanged"
- [X] T025 [US5] Run `./gradlew assembleDebug` and `./gradlew testDebugUnitTest --tests "*ScreenTimeLimitsSaveTest*"` — 10/10 tests pass, build green

**Checkpoint**: all three P1 parent-side stories are complete. Plan phase 1 is done.

---

## Phase 6: MANDATORY HARDWARE CHECKPOINT

**Purpose**: settle blocking backend assumptions A1, A4 and A7 on real devices before any child-side
code is written. A negative result changes the design of phases 7–10, so building them first would
be building on a guess.

**⚠️ STOP. Implementation does not continue until the user reports the result.**

- [ ] T026 Run quickstart scenarios (a) through (e) on a real parent device and a real paired child device, following [quickstart.md](./quickstart.md) §2, and report the observed `AppLock` and OkHttp output for each
- [ ] T027 Interpret the result against the decision tables in quickstart.md §2 and, if any of A1 / A4 / A6 / A7 proves false, revise the backend section of [plan.md](./plan.md) and the affected phases **before** proceeding — specifically: A7 false means the per-field delta design in research.md R1 must change; A1 false means the empty-array convention in FR-007 needs a different clearing mechanism; A4 false means nothing downstream can be verified at all

**Gate**: phases 7–10 begin only after the user reports (a)–(e).

---

## Phase 7: User Story 4 — A lock survives reboot, app kill and loss of network (Priority: P1)

**Goal**: the child enforces from a durable local copy, before any network call, with networking off.

**Independent test**: with a lock confirmed working, disable Wi-Fi and mobile data on the child,
reboot it, and open the locked app — it must still be blocked, and `RESTORE` must appear in the log
before any `FETCH`.

**Depends on**: Phase 6 (an unverified backend makes this untestable end-to-end).

### Tests for US4

- [ ] T028 [P] [US4] Create `app/src/test/java/com/safeguard/parentalcontrol/data/ScreenTimeRulesPersistenceTest.kt` covering the four write outcomes from data-model.md §3: a 200 overwrites the stored copy, a 404 removes it, a network error or 5xx leaves it untouched, and a stored copy whose `rules_device_id` does not match the current device is discarded at load (FR-013, FR-015, FR-016, research.md R3)

### Implementation for US4

- [ ] T029 [US4] Create `data/local/ScreenTimeRulesStore.kt`: `@Singleton`, `@Inject constructor(@ApplicationContext context)`, a Preferences DataStore named `screen_time_rules` with keys `rules_json` / `rules_device_id` / `rules_fetched_at`, Gson (de)serialisation of `ScreenTimeRule`, and atomic single-`edit {}` writes — modelled on `CustomWordRepository.kt:25,42-49` (research.md R2). No Hilt module entry is needed and none is added
- [ ] T030 [US4] Wire write-through in `ScreenTimeRulesRepository.getScreenTimeRules` (`:46-85`): 200 → overwrite store and `_cachedRules`; 404 → clear both (authoritative "no rules exist"); any other outcome → leave both untouched and log under `AppLock` at warning. Each branch logs which one it took, so the logcat shows the decision rather than the symptom
- [ ] T031 [US4] Add `restorePersistedRules(deviceId): ScreenTimeRule?` to `ScreenTimeRulesRepository`: read the store, discard on device-id mismatch, otherwise seed `_cachedRules` and log the age of the restored copy. Returns a local value, not a `NetworkResult`, because no network call is involved (constitution rule 9 applies to network outcomes)
- [ ] T032 [US4] Call `restorePersistedRules` in `service/MonitoringService.kt` **before** the first `getCachedRules` in the loop-start block (`:555-578`), so the first `checkAndEnforceLimits` pass enforces from the stored copy with networking off (FR-014). The enforcement tick's decision logic is not touched — only the source of the rules it reads
- [ ] T033 [US4] Run `./gradlew assembleDebug` and `./gradlew testDebugUnitTest --tests "*ScreenTimeRulesPersistenceTest*"`, then quickstart scenario (g) including its negative case (cleared app data must log `RESTORE no persisted rules` and enforce nothing)

**Checkpoint**: cutting the network can no longer unlock an app. Plan phase 2 is done.

---

## Phase 8: User Story 3 — A locked app cannot be opened on the child device (Priority: P1)

**Goal**: the block decision is correct and observable. Mostly verification plus distinguishable
logging — the diagnosis verified the enforcement mechanics themselves correct (hops 5, 6, 6c), so
this story adds coverage and clarity, not new mechanism.

**Independent test**: with a lock saved, open the app on the child from the launcher, from recents,
and from a notification tap — blocked all three ways.

**Depends on**: Phase 6; benefits from Phase 7 but does not require it.

### Tests for US3

- [ ] T034 [P] [US3] Create `app/src/test/java/com/safeguard/parentalcontrol/enforcement/AppBlockDecisionTest.kt` covering the `ScreenTimeRule.isAppBlocked` matrix (`Models.kt:788-790`): locked + active, locked + **inactive**, not locked, empty list, null list (FR-019, SC-010)

### Implementation for US3

- [ ] T035 [US3] Make the three no-block states distinguishable in `service/MonitoringService.kt` (`:670-680`): no rules at all, rules that lock nothing, and rules whose master switch is off must each log a different `AppLock` line — today the first two are indistinguishable and the third reads like the first (FR-019)
- [ ] T036 [US3] Add `AppLock` entries at the block decision and both lock-screen transitions in `MonitoringService.kt`: the `blockedApps.contains(foregroundApp)` outcome (`:774-787`) with the foreground package and list size, the presentation path actually taken by `showLockScreen` (overlay vs notification fallback, `:1094-1187`), and the dismissal (FR-018, FR-024). `LockOverlayController` itself is not modified
- [ ] T037 [US3] Run `./gradlew assembleDebug` and `./gradlew testDebugUnitTest --tests "*AppBlockDecisionTest*"`, then quickstart scenario (e) including the recents and notification-tap launches

**Checkpoint**: the whole block decision is readable in one log filter and covered by tests.

---

## Phase 9: User Story 6 — A lock reaches an online child device within seconds (Priority: P2)

**Goal**: one documented delivery path — a content-free push that triggers a refresh, with the
periodic refresh as the authoritative fallback.

**Independent test**: with the child online and inside the app, lock it from the parent and time the
removal; separately, prove the push branch with a hand-crafted FCM message.

**Depends on**: Phase 7 (a refresh that cannot persist its result is half a feature).

### Implementation for US6

- [ ] T038 [US6] Add `ACTION_REFRESH_RULES` to `service/MonitoringService.kt` (constant beside `ACTION_SYNC_NOW` at `:1314-1315`, branch in `onStartCommand` at `:214-227`) that force-refreshes through `screenTimeRulesRepository.getCachedRules(deviceDbId, forceRefresh = true)` — the same call the periodic loop makes — then runs one `checkAndEnforceLimits()` pass (research.md R4; the service delegates, holding no business logic, per constitution rule 7)
- [ ] T039 [US6] In `service/SafeGuardFirebaseMessagingService.kt`, add a `"rules_changed"` branch to `handleDataMessage` (`:91-118`) that starts `MonitoringService` with `ACTION_REFRESH_RULES` and logs via `AppLockPipe`, reading **only** the `type` key and never applying rules from the payload (FR-010); and delete the `"lock"` no-op (`:132-135`) because FR-009 permits exactly one delivery path and a logged no-op is a phantom second one. `command=sync` (`:127-131`) and `type=sync` (`:143+`) stay untouched
- [ ] T040 [US6] Run one immediate enforcement pass after every refresh that changes the rule set — push, periodic loop, network-regain (`MonitoringService.kt:178-209`) and `performInitialSync` (`:1244`) — instead of waiting for the next 3–15 s tick (research.md R10; `checkAndEnforceLimits` is idempotent, so this is one extra call, not new mechanism)
- [ ] T041 [US6] Change `rulesRefreshIntervalMs` from `90 * 1000L` to `30 * 1000L` (`MonitoringService.kt:583`) and replace its stale `"no push yet"` comment with the FR-012a rationale and the accepted battery cost (research.md R8)
- [ ] T042 [US6] Run `./gradlew assembleDebug`, then quickstart scenario (f) — including the hand-crafted FCM message, since the backend has not shipped A5 and the periodic path alone bounds delivery at ~35 s rather than 10 s

**Checkpoint**: one delivery path, documented, with four fallbacks intact. Plan phase 3 is done.

---

## Phase 10: User Story 7 — The parent is told when the child device cannot enforce (Priority: P2)

**Goal**: a missing enforcement capability is loud in the log and reaches the parent. Never a silent
bail.

**Independent test**: revoke usage access on the child, confirm an `AppLock` error line and a parent
alert; then revoke only the overlay permission and confirm the app is still blocked via the
notification fallback with no alert raised.

**Depends on**: Phase 8 (the checks hang off the enforcement path).

### Implementation for US7

- [ ] T043 [US7] Check capability state at monitoring-service start and after every rules update in `service/MonitoringService.kt`, using `ProtectionStatusHelper.isUsageStatsEnabled` (`:275`) and `isOverlayEnabled` (`:302`) — the constitution's single source of truth for protection status, never a re-implemented appops query (FR-020)
- [ ] T044 [US7] Treat usage access as hard-required: absence logs `AppLockPipe.e` and raises a parent alert through `AlertRepository.createAlert(alertType = AlertType.DEVICE_ADMIN_DISABLED, …)` with `ProtectionType.USAGE_STATS` supplying the copy — the same call `ProtectionMonitorWorker.kt:223-240` already makes, reusing its 15-minute cooldown and offline queueing. Raise it from `data/repository/ScreenTimeRulesRepository.kt` (inject `AlertRepository`) rather than from the service, so the service keeps no business logic (FR-020a, FR-022, research.md R6)
- [ ] T045 [US7] Treat the overlay capability as degraded, not required: absence logs `AppLockPipe.w`, the lock is still presented through the existing notification fallback (`MonitoringService.kt:1162-1187`), and a parent alert is raised **only** when that fallback is also unavailable — notifications blocked (`ProtectionStatusHelper.isNotificationsEnabled`, `:309`) or full-screen presentation not permitted on Android 14+ (FR-020b). `ProtectionType.OVERLAY` is already `isCritical = false`, so no new severity model is introduced
- [ ] T046 [US7] Make `getForegroundApp()` name its own failure in `MonitoringService.kt` (`:847-885`): check the usage-access permission first and, when it is missing, log that cause under `AppLock` instead of the current undifferentiated `Timber.w("…Could not detect foreground app via any method")` at `:880-882` (FR-021)
- [ ] T047 [US7] Run `./gradlew assembleDebug`, then quickstart scenario (h) including the degraded overlay case

**Checkpoint**: every failure mode in the chain is either visible to the parent or loud in the log.
Plan phase 4 is done.

---

## Phase 11: Polish & Cross-Cutting Concerns

- [ ] T048 Audit `AppLock` coverage end to end against FR-024: parent save → HTTP outcome → push receipt → fetch → persist → capability check → block decision → lock shown → lock dismissed. Every hop must appear, with no gap larger than one step, in a single `adb logcat -s AppLock:V` stream (SC-008)
- [ ] T049 Audit every new log statement for FR-025: no bearer token, no `X-Device-Token`, no parent email, no monitored content. Package names, HTTP status codes, field names and collection sizes are the permitted vocabulary, matching the privacy contract inherited from `AlertPipe`
- [ ] T050 Audit the diff against the plan's guardrails and justify each touched file: `ml/**`, `service/ContentFilterVpnService.kt`, `service/TextMonitoringAccessibilityService.kt`, `util/ImageBlurManager.kt`, `data/remote/AuthInterceptor.kt`, `di/**` and `navigation/**` must show **zero** changes
- [ ] T051 Run the full unit suite: `./gradlew testDebugUnitTest`, confirming the three new test classes pass and that the only failures are the pre-existing `WordPieceTokenizerTest` CRLF artefacts from T002
- [ ] T052 Run the complete quickstart (a)–(j) on real hardware, including (i) the ML/VPN/image regression check and (j) the Xiaomi or Infinix-class device with default battery settings, and record the results against the SC-001…SC-010 targets in spec.md

---

## Dependencies & Execution Order

**Phase order**: 1 → 2 → 3 → 4 → 5 → **6 (gate)** → 7 → 8 → 9 → 10 → 11

**Story dependency graph** (honest: these stories are not all independent):

```
Setup (T001-T003)
   └─▶ Foundational (T004-T006)
          └─▶ US1 — auto-save  (T007-T017)        ◀── the root cause; everything else needs it
                 ├─▶ US2 — trust the switch (T018-T022)   shares the saveField call sites
                 └─▶ US5 — unlock the last  (T023-T025)   same request-building code
                        └─▶ ══ CHECKPOINT (T026-T027) ══
                               ├─▶ US4 — survives reboot/offline (T028-T033)
                               ├─▶ US3 — locked app blocked      (T034-T037)
                               │      └─▶ US7 — parent told      (T043-T047)
                               └─▶ US6 — within seconds          (T038-T042)   needs US4
                                      └─▶ Polish (T048-T052)
```

**Why the stories are not independent**: US1 is the root cause. US2 and US5 are refinements of the
same save path and cannot be demonstrated before it exists — a rollback needs a save to fail, and an
empty array needs a request to put it in. US4, US3, US6 and US7 are all child-side and cannot be
verified end to end until the checkpoint confirms the parent's write actually reaches the child. The
template's ideal of fully independent story slices does not hold for a broken chain; pretending
otherwise would produce tasks that cannot be tested when they are written.

**File contention** (sequential by necessity, not by logic):

- `ScreenTimeLimitsViewModel.kt`: T004, T005, T009–T014, T020, T024
- `ScreenTimeLimitsScreen.kt`: T012, T015, T016, T019, T021
- `MonitoringService.kt`: T032, T035, T036, T038, T040, T041, T043, T045, T046
- `ScreenTimeRulesRepository.kt`: T006, T030, T031, T044

## Parallel Opportunities

Genuine `[P]` work, all of it tests in separate new files:

- T007, T008 together (both new in `ScreenTimeLimitsSaveTest.kt`, written before T009–T014)
- T018, T023 against the same new test file, once T005 exists
- T028 (`ScreenTimeRulesPersistenceTest.kt`) and T034 (`AppBlockDecisionTest.kt`) are independent of each other and of all production tasks in their phases
- T003 (`AppLockPipe.kt`) is parallel with T001–T002

Example: after the checkpoint, one developer takes T028–T033 (persistence) while another takes
T034–T037 (block decision + logging) — different files, no shared state.

## Implementation Strategy

### MVP

Phases 1 → 2 → 3 (T001–T017). That alone fixes the reported bug: a parent toggle reaches the
backend. Everything after it is correctness under failure — rollback, offline, latency, visibility —
which the spec rightly treats as P1 too, but which cannot be reached before the toggle saves.

### Incremental delivery

1. Phases 1–3 → the lock is saved → demo
2. Phases 4–5 → the UI cannot lie and the last unlock works → demo. **Plan phase 1 complete**
3. **Checkpoint** → the backend contract is settled on hardware before more is built
4. Phase 7 → offline and reboot hold → demo
5. Phases 8–9 → coverage, logging, seconds not minutes → demo
6. Phase 10 → the parent learns about inert devices → demo
7. Phase 11 → audits and the full on-device matrix

### Deliberately not parallelised

Three files carry nine production tasks each at most, and two of them (`ScreenTimeLimitsViewModel.kt`,
`MonitoringService.kt`) are among the largest in the repo. Splitting them across developers would
cost more in merge conflicts than it saves.

## Notes

- `[P]` = different files, no dependency on an incomplete task
- Every phase ends with `./gradlew assembleDebug`, per the plan's phase definition
- Phase 6 is a hard stop, not a suggestion: three blocking backend assumptions are unverifiable from
  this repository and a negative result changes the design of four later phases
- Commit per task or per logical group; the branch from T001 keeps this feature separate from
  016-remove-all-files-access
