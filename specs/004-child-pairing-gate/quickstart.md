# Quickstart Validation: Child Pairing Gate

**Feature**: 004-child-pairing-gate

## Prerequisites

- Android device/emulator (minSdk 26+), debug build installed, logged in as a **child** account with device registered and monitoring consent granted (the pairing gate sits *after* the existing consent gate).
- Reachable backend (`https://bw.noor.net:8090/api/v1`) with `GET /family/parents` live — see [contracts/family-parents-api.md](./contracts/family-parents-api.md).
- Logcat filtered: `adb logcat -s Timber | grep -iE "paired|parent link|ENFORCEMENT|MediaFileObserver|refreshLinkedParentStatus"` (or Android Studio Logcat with the same terms).

## Build gates

```powershell
./gradlew assembleDebug   # must pass after Phase 1 (data path) and again after Phase 2 (gate)
```

## Scenario 1 — Fresh unpaired child stays gated (US1 / SC-001)

1. Fresh install (or logout → login) as a child with **no** linked parent.
2. Let `MonitoringService` start (open the app).
3. **Expected**: log `Child NOT yet paired -> monitoring gated; refreshing parent link`; then `refreshLinkedParentStatus: parents=0, paired=false`. NO `=== ENFORCEMENT LOOP STARTED ===`, NO `Started MediaFileObserver`.

## Scenario 2 — Pairing confirmed at runtime starts monitoring (US1 / SC-004)

1. From Scenario 1's gated state, have a parent redeem the child's pairing code (existing flow).
2. Restart the service (kill app + reopen, or reboot) — or catch the refresh on the next gated start.
3. **Expected**: `refreshLinkedParentStatus: parents=1, paired=true` → `Parent link confirmed at runtime -> starting monitoring now` → `=== ENFORCEMENT LOOP STARTED ===` and `Started MediaFileObserver`.

## Scenario 3 — Paired device works offline (US2 / SC-002, SC-003)

1. With `hasLinkedParent` cached true (Scenario 2 done), enable airplane mode.
2. Force-stop the app; reopen so the service restarts.
3. **Expected**: `Child is paired -> starting enforcement + image monitoring` immediately — no network call on the gate path. Flag untouched by any failing background calls.

## Scenario 4 — Confirmed unpair gates next start (US3 / SC-005)

1. Parent unlinks the child (existing remove-link flow); backend now returns `200 []`.
2. Trigger a refresh from a gated start (e.g., after logout/login, flag is false → refresh runs) and observe `refreshLinkedParentStatus: parents=0, paired=false`.
3. **Expected**: monitoring not started; subsequent starts log the gated message.
   > Note: per locked design, an already-running paired device does not re-poll at start; unpair takes effect via a start where the flag is false or after logout. See plan.md Design Notes.

## Pass criteria

All four scenarios produce exactly the expected log lines; `assembleDebug` green at both checkpoints; no changes outside the six scoped files (`git diff --stat` to confirm).
