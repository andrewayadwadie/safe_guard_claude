# Implementation Plan: Parent App Lock Reliably Enforced on the Child Device

**Branch**: `017-app-lock-enforcement` (not yet created — see Structure Decision) | **Date**: 2026-10-18 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/017-app-lock-enforcement/spec.md`
**Diagnosis (source of truth for file:line claims)**: `C:\Users\Dell\.claude\plans\pasted-content-id-48a3-context-elegant-floyd.md`

## Summary

A parent-set app lock never reaches the child. The chain breaks at the first hop and has four more
defects behind it. The fix, in one sentence per defect:

1. **Root cause** — every control on the Screen Time Limits screen mutates `_uiState` and stops
   (`ScreenTimeLimitsViewModel.kt:211-351`); the only network write is an unlabelled FAB
   (`ScreenTimeLimitsScreen.kt:127-133`). Each control gets a per-field delta save, debounced and
   serialised, with rollback and a Snackbar on failure. The FAB goes.
2. **Emptied lists vanish** — `.ifEmpty { null }` (`:394-396`) plus Gson null-omission means
   unlocking the *last* app sends no `blocked_apps` key at all. Send `[]`.
3. **Child rules are in-memory only** — `_cachedRules` is a plain `StateFlow`
   (`ScreenTimeRulesRepository.kt:35`), so reboot or an OEM process kill leaves the child enforcing
   nothing until the first successful fetch. Persist to a DataStore and load it before any network
   call.
4. **Push cannot deliver** — `command=lock` is a logged no-op
   (`SafeGuardFirebaseMessagingService.kt:132-135`). Replace it with a `type=rules_changed` doorbell
   that force-refreshes through the same repository path the loop uses, then re-evaluates
   immediately. Keep every existing fallback.
5. **Silent death without permissions** — `getForegroundApp()` has no permission check and returns
   null with one `Timber.w` (`MonitoringService.kt:847-885`). Check usage access and overlay at
   service start and on every rules update; alert the parent through the protection-alert channel
   that already exists for exactly this.

Plus a sixth defect the clarification session surfaced: the **master enforcement switch**
(`ScreenTimeLimitsScreen.kt:475`, `setRulesActive` at `ScreenTimeLimitsViewModel.kt:249-252`) has the
identical never-saves bug, and the child gates all blocking on it (`Models.kt:789`,
`MonitoringService.kt:678`).

Every hop logs through one new tag, `AppLock`, modelled on the existing `AlertPipe`.

**Answer to the standing question** — *after pressing the FAB, does the lock reach the child today?*
Yes, the client chain is complete and correct once `saveRules()` runs: `PUT` → child `GET` every 90 s
→ `blockedApps.contains(foregroundApp)` → overlay. Worst case ~105 s. It is conditional on four
unverified backend behaviours (A1, A3, A4, A6) and it is lost on child process death. That is why
phase 1 ends in a hardware checkpoint before anything else is built.

## Technical Context

**Language/Version**: Kotlin 2.0.21, JDK 21, `jvmTarget = 17`

**Primary Dependencies**: Jetpack Compose (BOM 2024.09.00), Hilt 2.51.1, Retrofit 2.9.0 + Gson,
OkHttp 4.12.0, DataStore Preferences 1.0.0, WorkManager 2.9.0, Firebase BOM 32.7.0, Timber 5.0.1 —
all already present and version-locked by the constitution. **No new dependency.**

**Storage**: new Preferences DataStore `screen_time_rules` (child-side rule cache). Room
(`SafeGuardDatabase`, `pending_alerts` only) is **not** touched — no schema bump, no migration. See
`research.md` R2.

**Testing**: JUnit + MockK under `app/src/test`. Three new unit test areas (SC-010): the lock
decision, the emptied-list serialisation, and rule restore after restart. There is **no** existing
test anywhere covering app blocking.

**Target Platform**: Android, `minSdk 26`, `targetSdk`/`compileSdk 36`

**Project Type**: Mobile app, two roles in one APK (parent client / child agent)

**Performance Goals**: lock applied ≤10 s with push, ≤35 s without (SC-002). Enforcement tick stays
3 s active / 15 s idle (`MonitoringService.kt:98-99`). Rules refresh 90 s → 30 s.

**Constraints**: child enforcement must work with networking fully off and immediately after boot,
before any network call (FR-014/FR-015). One in-flight write per screen. No new permission, no new
data collected, no new backend endpoint.

**Scale/Scope**: one rule row per child device; blocked lists in practice <30 packages. Four phases,
~9 files modified, ~5 created.

## Constitution Check

*GATE: must pass before Phase 0. Re-checked after Phase 1 design — result at the end of this section.*

| # | Rule | Verdict | Note |
|---|---|---|---|
| 1 | Feature sub-package with ViewModel/UiState/Screen | ✅ Pass | `presentation/screentimelimits/` already exists and is kept; `UiState` lives in the ViewModel file as it does today. |
| 2 | `@HiltViewModel`, never manual | ✅ Pass | `ScreenTimeLimitsViewModel` is already annotated; no new ViewModel. |
| 3 | Repositories `@Singleton` | ✅ Pass | `ScreenTimeRulesRepository` is already `@Singleton` (`:29-32`); persistence is added inside it, not in a new scope. |
| 4 | ViewModel → Repository → `safeApiCall` → `ApiService`, no layer skipped | ✅ Pass | Per-field saves call `updateScreenTimeRules`, which already wraps `safeApiCall` (`:181`). |
| 5 | Tokens only in `TokenManager` | ✅ Pass | Nothing in this feature reads a token. New logs are forbidden from printing one (FR-025). |
| 6 | Special permissions via settings routing, never `requestPermissions()` | ✅ Pass | The feature only *reads* permission state via `ProtectionStatusHelper`; it requests nothing. |
| 7 | Services hold zero business logic | ✅ Pass | `MonitoringService.ACTION_REFRESH_RULES` and the FCM branch both delegate to `ScreenTimeRulesRepository`. Persistence lives in the repository. |
| 8 | Workers `@HiltWorker` + `CoroutineWorker` + `@AssistedInject` | ✅ Pass | No new worker. `ProtectionMonitorWorker` is reused unchanged. |
| 9 | `NetworkResult<T>` is the only repository return type | ✅ Pass | Save path returns `NetworkResult<ScreenTimeRule>` as today. The new persistence functions are local-only and return `Unit`/`ScreenTimeRule?`, which is not a network outcome. |
| 10 | Errors as Snackbar via `LaunchedEffect(uiState.error)` + `clearError()` | ✅ Pass | Already wired (`ScreenTimeLimitsScreen.kt:78-93`); rollback sets `error` through the same field. |
| 11 | ProGuard rules updated for every new Service/Receiver/Worker | ✅ Pass (n/a) | None added. |
| 12 | `aaptOptions { noCompress "tflite" }` stays | ✅ Pass | `app/build.gradle` untouched. |

**Principle checks**

- **I. Child Safety & Privacy First** — nothing new is collected. The persisted rule set is
  parent-authored policy (package names, times, limits): no monitored content, no raw text, no
  image bytes, no credentials. Plaintext DataStore is therefore appropriate and
  `EncryptedSharedPreferences` is not needed. Retention: overwritten on each successful fetch,
  removed on `404`, removed with app data. Nothing leaves the device that does not leave it today.
- **II. MVVM discipline** — all state updates stay `_uiState.update { it.copy(...) }`; no Composable
  gains a repository call.
- **III. Two-role separation** — this feature *strengthens* it. FR-014/FR-015 make the child enforce
  from local policy with the backend unreachable, which is the principle's own requirement, and
  FR-012 keeps push as an accelerant rather than a control path.
- **IV. Services & permission hygiene** — `ProtectionStatusHelper` remains the single source of
  truth for permission state. `MonitoringService` stays `TYPE_SPECIAL_USE`; `onTimeout()` untouched.
- **V. Security & secrets** — no change to auth, pinning, or token handling. New `AppLock` logging
  inherits `AlertPipe`'s privacy contract: metadata only.

**Re-check after Phase 1 design**: ✅ no new violations. The one judgement call worth naming is that
the new per-field save machinery (debounce map + confirmed-value snapshot) adds state to a
ViewModel that is already the largest on the screen; recorded in Complexity Tracking with the
simpler alternative and why it fails.

## Project Structure

### Documentation (this feature)

```text
specs/017-app-lock-enforcement/
├── plan.md                              # This file
├── spec.md                              # Feature specification (5 clarifications integrated)
├── research.md                          # Phase 0: 10 decisions, with the rejected alternatives
├── data-model.md                        # Phase 1: new DataStore entity + UiState additions
├── quickstart.md                        # Phase 1: on-device verification guide (a–j)
├── contracts/
│   ├── screen-time-rules-api.md         # PUT/GET contract, exact JSON, A1-A7 marked
│   └── push-rules-changed.md            # New data-only push (A5), client handling
├── checklists/
│   └── requirements.md                  # Spec quality checklist, 16/16
└── tasks.md                             # Phase 2 output — NOT created by /speckit-plan
```

### Source Code (repository root)

```text
app/src/main/java/com/safeguard/parentalcontrol/
├── presentation/screentimelimits/
│   ├── ScreenTimeLimitsViewModel.kt     # MODIFY  per-field saves, rollback, debounce; delete saveRules
│   └── ScreenTimeLimitsScreen.kt        # MODIFY  remove FAB, per-row progress, dead code
├── data/
│   ├── repository/ScreenTimeRulesRepository.kt  # MODIFY  persist/restore rules, 404 vs failure split
│   └── local/ScreenTimeRulesStore.kt    # CREATE  DataStore "screen_time_rules" + Gson
├── service/
│   ├── MonitoringService.kt             # MODIFY  load-before-network, ACTION_REFRESH_RULES, perm checks, 30s
│   └── SafeGuardFirebaseMessagingService.kt     # MODIFY  rules_changed branch; delete lock no-op
├── util/
│   └── AppLockPipe.kt                   # CREATE  single "AppLock" log tag, mirrors AlertPipe
└── (untouched: ml/**, service/ContentFilterVpnService.kt, service/TextMonitoringAccessibilityService.kt,
   util/ImageBlurManager.kt, data/remote/AuthInterceptor.kt, di/**, navigation/**)

app/src/test/java/com/safeguard/parentalcontrol/
├── screentimelimits/ScreenTimeLimitsSaveTest.kt # CREATE  per-field delta, empty list, rollback, coalescing
├── data/ScreenTimeRulesPersistenceTest.kt       # CREATE  persist/restore, 404 clears, error keeps
└── enforcement/AppBlockDecisionTest.kt          # CREATE  isAppBlocked matrix incl. isActive=false
```

**Structure Decision**: existing package-by-feature layout, no new module and no new package. One new
file in `data/local/` (alongside the existing Room artefacts) and one in `util/` (alongside
`AlertPipe.kt`), both placed to match the convention of the file they are modelled on.

**Branch note**: the working tree is on `016-remove-all-files-access` and clean. No `before_plan`
git hook is installed, so no branch was cut. Create `017-app-lock-enforcement` off `main` before
implementing — the 016 feature is a separate Play-release blocker and must not be entangled with
this one.

---

## Files to CREATE vs MODIFY, per phase

### Phase 1 — Parent auto-save + empty-list fix + FAB removal

Delivers FR-001 to FR-008c, FR-024 (parent hops), US1, US2, US5.

**CREATE**

| File | Why |
|---|---|
| `util/AppLockPipe.kt` | One `AppLock` tag for the whole chain, mirroring `util/AlertPipe.kt` including its privacy header. Needed in phase 1 because the checkpoint reads the parent-side log. |
| `app/src/test/.../screentimelimits/ScreenTimeLimitsSaveTest.kt` | Per-field delta body, emptied list serialises as `[]`, rollback restores the confirmed value, rapid toggles coalesce to the newest. |

**MODIFY**

| File | Change | Guardrail justification |
|---|---|---|
| `presentation/screentimelimits/ScreenTimeLimitsViewModel.kt` | Each `setX`/`addX`/`removeX` (`:211-351`) performs an optimistic update then schedules a per-field save. Add `savingFields`, `confirmedState`, `RuleField` enum (see `data-model.md` §4). Add a `MutableMap<RuleField, Job>` + `Mutex` (`research.md` R7). Delete `saveRules()` (`:356-420`) and the `.ifEmpty { null }` coercions. Lock-add also sends `isActive = true` when the master switch is on (FR-008b). Keep the existing `PARENT CONFIG` logs, re-routed through `AppLockPipe`. | In scope: this is the root cause. |
| `presentation/screentimelimits/ScreenTimeLimitsScreen.kt` | Remove the `floatingActionButton` block (`:127-133`). Per-row progress from `savingFields`. Delete dead `rememberInstalledApps` (`:1055-1062`) — it enumerates the *parent's* installed apps and has no caller. Master switch (`:475`) wired to the saving path like any other control. | In scope. Dead-code deletion justified: unreachable, and it would be a privacy smell if it ever gained a caller. |
| `data/repository/ScreenTimeRulesRepository.kt` | No signature change. `updateScreenTimeRules` gains `AppLockPipe` entries for request field names and HTTP outcome. | Minimal touch; the method already supports delta saves (`research.md` R1). |

**Not modified, deliberately**: `Models.kt` — `ScreenTimeRuleUpdateRequest` is already correct for
`[]`; the bug was the caller coercing to null. `ApiService.kt` — no endpoint change.

**Ends with**: `./gradlew assembleDebug`, then **MANDATORY CHECKPOINT** (below). Phase 2 does not
start until the user reports the result.

### Phase 2 — Child local persistence + load-before-network

Delivers FR-013 to FR-016, FR-019, US4.

**CREATE**

| File | Why |
|---|---|
| `data/local/ScreenTimeRulesStore.kt` | Preferences DataStore `screen_time_rules`, keys `rules_json` / `rules_device_id` / `rules_fetched_at`, Gson (de)serialisation, atomic `edit {}`. Mirrors `CustomWordRepository`'s `word_lists` usage (`research.md` R2, `data-model.md` §3). |
| `app/src/test/.../data/ScreenTimeRulesPersistenceTest.kt` | 200 overwrites; 404 clears; network error keeps; device-id mismatch discards. |

**MODIFY**

| File | Change | Guardrail justification |
|---|---|---|
| `data/repository/ScreenTimeRulesRepository.kt` | Inject `ScreenTimeRulesStore`. `getScreenTimeRules` writes through on 200, clears on 404, leaves the copy alone on any other outcome (`:56-85`). Add `restorePersistedRules(deviceId)` that seeds `_cachedRules` from the store. | In scope: defect 3. |
| `service/MonitoringService.kt` | Call `restorePersistedRules` **before** the first `getCachedRules` in the loop-start block (`:555-578`). Make the three no-rules log sites (`:671-675`) distinguish no-rules / nothing-locked / policy-inactive (FR-019). | Enforcement tick logic itself unchanged — only the source of the rules it reads, which is the brief's stated allowance. |
| `di/` | Nothing. `ScreenTimeRulesStore` is `@Singleton @Inject constructor(@ApplicationContext context)`, so Hilt resolves it without a module entry. | Hilt modules stay additive-only; here they need no change at all. |

**Ends with**: `./gradlew assembleDebug` + quickstart scenario (g) — reboot with networking off.

### Phase 3 — Push-triggered refresh + immediate re-evaluation

Delivers FR-009 to FR-012a, US6.

**CREATE**: none.

**MODIFY**

| File | Change | Guardrail justification |
|---|---|---|
| `service/SafeGuardFirebaseMessagingService.kt` | Add `"rules_changed"` to the `when (type)` in `handleDataMessage` (`:91-118`) → start `MonitoringService` with `ACTION_REFRESH_RULES`, logged via `AppLockPipe`. Delete the `"lock"` no-op (`:132-135`). | FR-009 requires a single path; the no-op is a phantom second one. `command=sync` and `type=sync` untouched. |
| `service/MonitoringService.kt` | New `ACTION_REFRESH_RULES` constant and `onStartCommand` branch (`:214-227`, `:1314-1315`) → force-refresh then one immediate `checkAndEnforceLimits()`. `rulesRefreshIntervalMs` 90 s → 30 s (`:583`) and its stale `"no push yet"` comment updated. Immediate re-evaluation also after the network-regain and initial-sync refreshes (`:178-209`, `:1244`). | Additive action, existing idiom. The single enforcement-logic touch is one extra call to an idempotent function (`research.md` R10). |

**Not modified**: `AndroidManifest.xml` — the FCM service is already declared (`:197-204`) and no new
component is introduced.

**Ends with**: `./gradlew assembleDebug` + quickstart (f). If the backend has not shipped A5, (f) is
verified by forcing a refresh instead, and the push branch is proven with a hand-crafted FCM message.

### Phase 4 — Permission checks + parent visibility + `AppLock` across all hops

Delivers FR-020 to FR-025, US7, SC-008.

**CREATE**

| File | Why |
|---|---|
| `app/src/test/.../enforcement/AppBlockDecisionTest.kt` | `isAppBlocked` matrix: locked+active, locked+inactive, unlocked, empty list, null list. Closes the SC-010 gap on the decision itself. |

**MODIFY**

| File | Change | Guardrail justification |
|---|---|---|
| `service/MonitoringService.kt` | At service start and after every rules update, check `ProtectionStatusHelper.isUsageStatsEnabled` / `isOverlayEnabled`. Missing usage access → `AppLockPipe.e` + parent alert (hard-required, FR-020a). Missing overlay → `AppLockPipe.w`, keep the notification fallback, alert only when notifications are also unavailable (FR-020b). Add the permission-state check to `getForegroundApp()` (`:847-885`) so a null return names its cause instead of `Timber.w("…any method")`. | Defect 5. Reuses the existing helper per Principle IV. |
| `data/repository/ScreenTimeRulesRepository.kt` | Inject `AlertRepository` to raise the capability alert from the repository rather than the service (constitution rule 7). | Keeps business logic out of the service. |
| all touched files | `AppLockPipe` entries at each hop: parent save → HTTP outcome → push receipt → fetch → persist → permission check → block decision → lock shown → lock dismissed (FR-024). | Logging only. |

**Ends with**: `./gradlew assembleDebug` + full quickstart (a–j), including (i) the ML-regression
check and (j) a Xiaomi/Infinix-class device.

---

## Backend developer section

The backend (FastAPI) is a separate repository and was not read. Exact request/response JSON lives in
[`contracts/screen-time-rules-api.md`](./contracts/screen-time-rules-api.md) and
[`contracts/push-rules-changed.md`](./contracts/push-rules-changed.md). Summary:

| # | Assumption / question | Blocker? | Consequence if false |
|---|---|---|---|
| **A1** | `PUT /screen-time-rules/{id}` persists `blocked_apps`, the child's `GET` returns it, and `"blocked_apps": []` **clears** the list rather than being rejected or ignored | **BLOCKER** | The root-cause fix saves nothing, or the last unlock is unrepresentable. Phase 1 checkpoint tests both. |
| **A3** | A **child** account may `GET /screen-time-rules/{its own device_id}` with the normal bearer token and no `X-Device-Token` | **BLOCKER** | The child never receives rules; every hop still logs success. |
| **A4** | `device_id` in `screen-time-rules` is the same integer the parent reads from `GET /devices` and the child stores as `deviceDbId` | **BLOCKER** | Parent writes one id space, child reads another. Silent on both sides — the exact reported symptom. |
| **A6** | `PUT` upserts when no rules row exists (the client never calls `POST`, `createScreenTimeRules` has zero call sites) | **BLOCKER** | A device's first-ever lock 404s and phase 1 must add a create-then-update path. |
| **A7** | A `PUT` carrying only the changed field leaves every other stored field untouched | **BLOCKER** | A delta save wipes bedtime/limits. Asserted by `ScreenTimeLimitsViewModel.kt:363-365`, never verified. |
| **A2** | Default `is_active` on a newly created row | No (FR-008b always sends it explicitly) | Rows created before this feature may be silently inactive. |
| **A5** | Backend sends a data-only high-priority `{"type":"rules_changed"}` push after a successful `PUT` | No | SC-002's 10 s case is lost; 30 s polling remains the floor. |
| **Q6** | Do `POST .../blocked-app` and `DELETE .../blocked-app/{pkg}` exist and work? | No | Not used (`research.md` R9); if they work they are a later optimisation. |
| **Q7** | Is `404` on `GET` ever returned for an auth or routing problem rather than genuinely "no rules"? | No, but important | A spurious `404` clears the persisted copy and unlocks the child. |
| **Q8** | Is anything currently sending `command=lock`? | No | It is being silently dropped today (`:132-135`). |

**Ask order**: A1, A4, A7 first — those three are what the phase 1 checkpoint measures, and two of
them can be answered from the logcat alone.

---

## Manual verification on real devices

Full procedure with exact commands and expected output: [`quickstart.md`](./quickstart.md).
Coverage map:

| Scenario | Proves | Phase |
|---|---|---|
| (a) toggle lock, no button press → `PUT` succeeds | FR-001, FR-002, A1 | 1 |
| (b) toggle with parent network off → switch reverts, Snackbar | FR-005 | 1 |
| (c) rapid on/off/on → server equals screen; other controls intact | FR-006, A7 | 1 |
| (d) unlock the **last** locked app → child can open it | FR-007, A1 | 1 |
| (e) child app closed → lock → open → blocked | US3, A3, A4 | 1→2 |
| (f) child inside the app → lock → removed ≤10 s | SC-002, R10 | 3 |
| (g) reboot child with networking off → still blocked | FR-014, FR-015 | 2 |
| (h) revoke usage access → loud `AppLock` log + parent alerted | FR-020a, FR-022 | 4 |
| (i) regression: text-monitoring alerts still arrive | guardrail: ML path untouched | 4 |
| (j) repeat (e)–(g) on Xiaomi/Infinix with default battery settings | OEM process-kill resilience | 4 |

### MANDATORY CHECKPOINT — end of phase 1

Implementation **stops** after phase 1 builds. The user runs scenarios (a)–(e) on real parent and
child devices and reports back. Phase 2 does not begin until then, and if the checkpoint shows the
backend does not behave as A1/A4/A7 assume, this plan's backend section and phases 2–4 are revised
first. The commands and the decision table for interpreting the output are in `quickstart.md` §2.

---

## Complexity Tracking

| Violation | Why needed | Simpler alternative rejected because |
|---|---|---|
| Per-field debounce + in-flight bookkeeping in `ScreenTimeLimitsViewModel` (a `Job` map, a `Mutex`, a confirmed-value snapshot, a `RuleField` enum) added to an already-large ViewModel | FR-006 forbids lost or out-of-order updates and FR-005 needs a rollback target per control. Auto-save makes a race that a single Save button never had. | *Save immediately with no debounce*: a dragged slider fires a request per value (FR-026). *One global in-flight flag*: one control's save blocks another's, violating FR-003. *No rollback snapshot, re-read from server on failure*: a failed save would need a successful `GET` to repair the UI, which is precisely what is unavailable when the save failed. |
| `AppLockPipe` adds a second project-wide log tag object next to `AlertPipe` | FR-024 requires the whole chain in one filter; the chain crosses seven classes. | *Reuse `ALERT_PIPE`*: interleaves two unrelated pipelines and breaks the existing alert-QA filter. *Per-class Timber tags*: the status quo FR-024 is written against. |
