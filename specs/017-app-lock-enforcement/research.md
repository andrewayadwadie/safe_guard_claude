# Phase 0 Research: Parent App Lock Reliably Enforced on the Child Device

**Feature**: 017-app-lock-enforcement | **Date**: 2026-10-18
**Source of truth for file:line claims**: `C:\Users\Dell\.claude\plans\pasted-content-id-48a3-context-elegant-floyd.md`
(every reference below was re-read in the working tree, not taken from the diagnosis on trust)

---

## R1. Save granularity: per-field delta PUT, not whole-row PUT

**Decision**: each control sends a `PUT screen-time-rules/{deviceId}` carrying **only the field it
changed** (plus `is_active` when the change adds a lock, per FR-008b). The request body is not built
from the whole screen state.

**Rationale**:
- `ScreenTimeRuleUpdateRequest` (`Models.kt:680-709`) is all-nullable and Gson is configured without
  `serializeNulls` (`NetworkModule.kt:39-42`), so a null field is omitted from the wire. The type was
  designed for partial updates, and `ScreenTimeLimitsViewModel.kt:363-365` states the contract in a
  comment: *"Backend only updates fields that are explicitly provided (not null)"*. The
  `dailyLimit = 0` workaround on `:366-370` exists because of that behaviour.
- FR-003 forbids a save from resubmitting another control's value, and FR-003a requires two parents
  editing different controls to both succeed. A whole-row PUT breaks both: parent A's save carries
  parent A's stale copy of parent B's field and overwrites it.
- No new repository method is needed. `updateScreenTimeRules` (`ScreenTimeRulesRepository.kt:147-192`)
  already takes every field as a defaulted nullable, so calling it with one named argument *is* a
  delta save.

**Conflict with the requested approach, stated plainly**: the planning brief asked for *"The PUT body
is always built from the full current rules state so no control is clobbered."* That is the opposite
mechanism and it is rejected, because the clarification session already settled this (Q5 → per-field
saves, "each control saves only its own field and never carries other controls' values", recorded in
`spec.md` Clarifications and FR-003/FR-003a). A full-state body does not prevent clobbering — it is
the thing that causes it, since the clobbering value is the *sender's own stale copy* of a field it
never edited. The brief's goal ("no control is clobbered") is what the delta achieves.

**Dependency this creates**: assumption **A7** — a save carrying only the changed field leaves every
other stored field untouched. Asserted by the client's own comment, never verified against the live
backend. Promoted to a **blocker** verified at the Phase 1 checkpoint, because if the backend instead
replaces the row, a delta save silently wipes bedtime/limits. The checkpoint test for it is
concrete: set a daily limit and a bedtime, then toggle a lock, then re-read the row and confirm the
limit and bedtime survived.

**Alternatives considered**:
- *Whole-row PUT*: rejected above. Would also make FR-007 (explicit `[]`) pointless, since a
  whole-row body always carries every key.
- *Per-app endpoints* `POST .../blocked-app?package_name=` and `DELETE .../blocked-app/{pkg}`
  (`ApiService.kt:392-405`), wired to `ScreenTimeRulesRepository.addBlockedApp` /
  `removeBlockedApp` (`:254-289`): see R9. Rejected as the primary path.

---

## R2. Child-side durable store: DataStore Preferences + Gson, mirroring `word_lists`

**Decision**: persist the rule set as a single Gson-serialised JSON string in a Preferences DataStore
named `screen_time_rules`, owned by `ScreenTimeRulesRepository`.

**Rationale**:
- Exact precedent in the repo for the same shape of data: `CustomWordRepository.kt:25,40-49` keeps a
  server-fetched, locally-cached policy object as a Gson JSON string under
  `preferencesDataStore(name = "word_lists")`, with a version and a last-sync key. The rule set is
  the same thing — one small server-owned document, read whole, written whole, never queried by
  field.
- Room in this project holds exactly one entity, `PendingAlertEntity`
  (`SafeGuardDatabase.kt:13-17`), at `version = 1` with no migrations. Adding an entity forces a
  schema version bump and a migration path on a database that has never migrated, for a single row
  that is never joined or queried relationally. That is strictly more risk for no benefit.
- `DataStore.edit {}` is atomic and transactional, which is what FR-016 asks for (no partially
  written rule set readable). A Room upsert would give the same guarantee, at the migration cost
  above.
- DataStore 1.0.0 is already a locked, present dependency (constitution: Platform constraints).

**Alternatives considered**:
- *Room entity + DAO + migration*: rejected per above. Would be the right call if rules were ever
  queried per-field or per-device at scale; one device's one rule row is neither.
- *`PreferencesManager` (EncryptedSharedPreferences)*: rejected. It is the token/flag store;
  `TokenManager` owns secrets there and the rule set is not a secret. Mixing a JSON document into
  the flag store muddies ownership and gains nothing.

---

## R3. "A failed GET never clears it" — 404 is not a failure

**Decision**: distinguish three outcomes of the child's `GET screen-time-rules/{deviceId}`:
- **200** → overwrite the persisted copy and the in-memory cache.
- **404** → authoritative "no rules exist for this device". Clear both. This is the parent having
  deleted the rules, and continuing to enforce a deleted policy would be a bug.
- **anything else** (network failure, timeout, 5xx, 401 after refresh fails) → keep the persisted
  copy and the in-memory cache untouched, log under `AppLock` at warning.

**Rationale**: the planning brief says "a failed GET never clears it", and FR-015 says loss of
connectivity must never unlock an app. Today `getScreenTimeRules` maps 404 to
`_cachedRules.value = null` (`ScreenTimeRulesRepository.kt:68-74`) and leaves the cache intact on
other errors (`:75-79`) — so the existing split is already correct in-memory; persistence must copy
that split rather than treat every non-200 the same. Collapsing 404 into "keep" would make a deleted
policy un-deletable; collapsing it into "clear" for all errors would unlock on flaky Wi-Fi.

**Risk accepted**: if the backend wrongly returns 404 for an auth or routing problem, the child
unlocks. Mitigated by assumption A3 (the child is authorised for its own device) being a blocker,
and by logging the clear decision loudly under `AppLock` so the logcat shows which branch ran.

---

## R4. Push → refresh: a new service action, no rules in the payload

**Decision**: add `MonitoringService.ACTION_REFRESH_RULES`. The FCM handler starts the service with
it; the service force-refreshes rules through `ScreenTimeRulesRepository.getCachedRules(forceRefresh = true)`
— the same call the periodic loop makes — and then immediately runs one enforcement pass.

**Rationale**:
- `onStartCommand` already dispatches on action and already has this exact shape for
  `ACTION_SYNC_NOW → triggerImmediateSync()` (`MonitoringService.kt:214-227`, constants at
  `:1314-1315`). Adding a third action is additive and matches the existing idiom.
- Constitution rule 7 (services hold no business logic) is satisfied: the service calls a repository;
  the repository does the fetch and the persistence.
- A high-priority data-only FCM message grants a temporary background-start exemption, so starting
  an already-running foreground service from `onMessageReceived` is permitted on Android 12+.
- FR-010 forbids applying rules from the payload. The push is a doorbell: `{"type":"rules_changed"}`
  and nothing else is trusted.

**Replaces**: the `"lock" -> Timber.d("Lock command received - not implemented in v1")` no-op at
`SafeGuardFirebaseMessagingService.kt:132-135`. FR-009 demands one path, so the dead `lock` command
is removed rather than left as a second, non-functioning one.

**Also fixed here**: `handleDataMessage`'s `else` branch logs an unrecognised type
(`:112-117`) but under the `ALERT_PIPE` tag. FR-011 is already satisfied in substance; the
rules-changed branch adds its own `AppLock` entry so the lock chain is readable in one filter.

---

## R5. One log tag: `AppLockPipe`, mirroring `AlertPipe`

**Decision**: create `util/AppLockPipe.kt` with `TAG = "AppLock"` and `d/i/w/e` helpers, modelled
line-for-line on `util/AlertPipe.kt`.

**Rationale**: `AlertPipe` exists for exactly this reason and documents it — the violation path
crosses six classes, so it logs through one tag and reads as one stream (`adb logcat -s ALERT_PIPE:V`).
The lock chain crosses at least seven: the parent ViewModel, the repository, the FCM service,
`MonitoringService`, the persistence layer, `LockOverlayController`, and the protection check. Same
problem, same solution, and the privacy contract in `AlertPipe`'s header (metadata only, never raw
content, never full tokens) transfers verbatim and satisfies FR-025.

**Alternatives considered**: reusing `ALERT_PIPE` — rejected, it would interleave two unrelated
pipelines in one filter and break the existing alert-QA workflow. Per-class `Timber` tags — rejected,
that is the status quo FR-024 is written against.

---

## R6. Parent visibility reuses the protection-alert channel as-is

**Decision**: report a missing enforcement capability through
`AlertRepository.createAlert(alertType = AlertType.DEVICE_ADMIN_DISABLED, severity, title, message, metadata)`,
the same call `ProtectionMonitorWorker.alertParentProtectionDisabled` already makes
(`ProtectionMonitorWorker.kt:223-240`), with `ProtectionType.USAGE_STATS` / `ProtectionType.OVERLAY`
supplying the copy.

**Rationale**:
- The channel exists, is live, and already covers both permissions this feature needs: the worker is
  scheduled every 15 minutes from `SafeGuardApplication.kt:71`, `BootReceiver.kt:176` and
  `ServiceRestartReceiver.kt:86`, with a 15-minute critical / 1-hour normal cooldown and a `Mutex`
  guarding the check-then-record race (`ProtectionMonitorWorker.kt:62-87`).
- `ProtectionType` already encodes Q3's split exactly: `USAGE_STATS(isCritical = true)` and
  `OVERLAY(isCritical = false)` (`ProtectionStatusHelper.kt:89-100`). FR-020a/FR-020b need no new
  severity model.
- `createAlert` queues to `pending_alerts` and flushes later, so a capability lost while offline
  still reaches the parent.
- `ProtectionStatusHelper.isUsageStatsEnabled` / `isOverlayEnabled` (`:275`, `:302`) are the
  single source of truth per constitution Principle IV, so the new checks call them rather than
  re-implementing the appops query.

**What this feature adds**: two extra trigger points feeding the same channel — at monitoring-service
start and on every rules update (FR-020) — because a 15-minute cycle is too slow to tell a parent who
is setting a lock right now that it will not take effect.

---

## R7. Rapid toggles: per-field coalescing, newest wins

**Decision**: in `ScreenTimeLimitsViewModel`, keep a `MutableMap<String, Job>` keyed by field name.
A change to a field cancels that field's in-flight debounce job and starts a new one (250 ms), then
performs the save under a `Mutex` so at most one PUT is in flight at a time across the screen. Each
field records the value it last confirmed, for rollback.

**Rationale**:
- FR-006 requires convergence and forbids an out-of-order response winning. Cancel-and-replace per
  field gives convergence by construction: only the newest job for a field ever issues a request.
  The `Mutex` makes response order equal request order, so a late response cannot overwrite a newer
  value.
- Debouncing per field rather than globally means toggling a lock does not delay a bedtime save.
- 250 ms is below the threshold where a switch feels unresponsive and above the interval of a human
  double-tap, so an on/off/on burst collapses into one request.
- `Mutex` is already the project's idiom for exactly this class of race:
  `ScreenTimeRulesRepository.cacheMutex` (`:43`) and `ProtectionMonitorWorker.alertSlotMutex`
  (`:87`), the latter with a comment naming it a TOCTOU fix.

**Alternatives considered**:
- *No debounce, serialise only*: every intermediate toggle hits the network. Rejected for FR-026
  (a dragged slider would fire a request per pixel) and for battery.
- *`StateFlow.debounce` collector per field*: equivalent behaviour, more machinery, and it would make
  the "which value did the server confirm" bookkeeping for rollback harder to read.

---

## R8. Refresh interval 90 s → 30 s

**Decision**: `rulesRefreshIntervalMs` drops from `90 * 1000L` to `30 * 1000L`
(`MonitoringService.kt:583`).

**Rationale**: FR-012a and the Q1 clarification. Worst-case delivery without a push becomes
30 s refresh + one loop tick (3 s active / 15 s idle) ≈ 35 s, which is what SC-002's no-push case
states. The comment on that line still reads *"no push yet"* and is updated.

**Cost, stated**: three times the request volume on the rules endpoint and three times the wakeups.
Accepted because the request is small, the loop is already awake on its own 3–15 s cadence (so the
refresh adds a network call, not a wakeup), and the alternative is a parent-visible two-minute lag on
a child-safety control. Revisit if the backend ships A5, after which the interval is pure fallback
and could go back up.

---

## R9. The per-app blocked-app endpoints stay unused

**Decision**: do not use `ScreenTimeRulesRepository.addBlockedApp` / `removeBlockedApp` (`:254-289`)
or their endpoints (`ApiService.kt:392-405`). They remain dead code for now; removing them is not in
this feature's scope.

**Rationale** (the brief asked these be reused "where they fit" — they do not fit):
- Zero call sites means zero production traffic has ever hit `POST .../blocked-app` or
  `DELETE .../blocked-app/{pkg}`. Their existence in `ApiService` proves a client author expected
  them, not that the backend serves them. Building the root-cause fix on two never-exercised routes
  adds two unverified backend dependencies to a feature that already has seven.
- They would be a **second write path** for the same state, which FR-009 exists to prevent. The
  delta PUT already handles every other control; routing locks through a different verb and URL
  means two failure modes, two rollback behaviours and two things for the backend to keep
  consistent.
- They do not reduce work: `updateScreenTimeRules(blockedApps = …)` is already a delta save (R1),
  and sending the full list — including `[]` — is what FR-007 requires anyway.

**If the backend confirms them later**: they become a worthwhile optimisation, because add/remove by
package is immune to the same-field last-write-wins case in FR-003a (two parents locking two
different apps would both survive). Recorded as non-blocking backend question Q6.

---

## R10. Immediate re-evaluation after any rules change

**Decision**: after a successful refresh that changes the rule set — from push, from the periodic
loop, from network-regain, or from `performInitialSync` — run one enforcement pass immediately
instead of waiting for the next tick.

**Rationale**: US3 scenario 3 and SC-002. With a 3–15 s tick the tick is not the bottleneck, but
"locked while the child is inside the app" is the scenario a parent actually watches, and the idle
15 s path is enough to make a ≤10 s target fail on its own. The pass is the existing
`checkAndEnforceLimits()` (`MonitoringService.kt:648-806`), called once more — not a new mechanism,
and it is idempotent (it re-reads `cachedRules.value` and the foreground app, and dismisses the lock
screen when nothing matches).

**Guardrail note**: this is the only change to the enforcement tick beyond reading persisted rules.
`checkAndEnforceLimits`'s decision logic, `LockOverlayController`, and the lock-screen presentation
are untouched — the diagnosis verified them correct (hops 5, 6, 6c).

---

## Unknowns remaining

None blocking Phase 1 design. Every "NEEDS CLARIFICATION" from the spec was resolved in the
clarification session; the open items are all backend-contract confirmations, which are listed as
A1–A7 in `spec.md` and carried into `plan.md`'s backend section with blocker/non-blocker marks. They
gate **implementation of phases 2–4**, not design, and the Phase 1 checkpoint is where they are
answered on real hardware.
