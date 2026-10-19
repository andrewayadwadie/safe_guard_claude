# Phase 1 Data Model: Parent App Lock Reliably Enforced on the Child Device

**Feature**: 017-app-lock-enforcement | **Date**: 2026-10-18

No backend schema changes. One new local store, one new local entity, two UI-state additions. Every
existing model is reused unchanged.

---

## 1. `ScreenTimeRule` — unchanged, reused

Defined at `Models.kt:715-753`. Already carries everything enforcement needs, and the blocking gate
already exists:

```kotlin
// Models.kt:788-790
fun isAppBlocked(packageName: String): Boolean {
    return isActive && blockedApps?.contains(packageName) == true
}
```

**No change.** The `isActive &&` conjunction is the master-switch gate from FR-008c/FR-019 and is
correct as written. `blockedApps` stays `List<String>?` — nullable is meaningful on the *read* side
(absent vs empty from the server); FR-007 is about the *write* side only.

---

## 2. `ScreenTimeRuleUpdateRequest` — unchanged, used differently

Defined at `Models.kt:680-709`. All fields nullable with `= null` defaults; Gson omits nulls
(`NetworkModule.kt:39-42`).

**No change to the class.** What changes is the caller: today `saveRules` fills every field; after
this feature each save fills exactly one (plus `isActive` when adding a lock). The `.ifEmpty { null }`
coercions at `ScreenTimeLimitsViewModel.kt:394-396` are deleted, so an emptied list serialises as
`[]` rather than vanishing.

| Field | Wire key | Omitted (null) means | Explicit value needed to clear |
|---|---|---|---|
| `blockedApps` | `blocked_apps` | leave unchanged | `[]` |
| `appLimits` | `app_limits` | leave unchanged | `{}` |
| `studyTimeAllowedApps` | `study_time_allowed_apps` | leave unchanged | `[]` |
| `dailyLimit` | `daily_limit` | leave unchanged | `0` (already handled, `:366-370`) |
| `deviceLockedMessage` | `device_locked_message` | leave unchanged | `""` |

The three list/map rows are the FR-007 defect. The `dailyLimit` row is the existing workaround that
proves the semantics.

---

## 3. NEW — `PersistedScreenTimeRules` (child-local)

**Store**: Preferences DataStore named `screen_time_rules`, owned by `ScreenTimeRulesRepository`.
Mirrors `CustomWordRepository`'s `word_lists` store (`CustomWordRepository.kt:25,42-49`).

**Keys**:

| Key | Type | Holds |
|---|---|---|
| `rules_json` | `String` | Gson serialisation of `ScreenTimeRule`, or absent when no rules exist |
| `rules_device_id` | `Int` | the `deviceId` the stored rules belong to |
| `rules_fetched_at` | `Long` | epoch ms of the successful fetch that wrote this copy |

**Why `rules_device_id` is stored**: the persisted copy must never be enforced against the wrong
device. If the stored id does not match `deviceRepository.getCurrentDeviceId()` at load time — a
re-paired or re-registered device — the copy is discarded rather than applied, and the discard is
logged under `AppLock`.

**Why `rules_fetched_at` is stored**: observability only (FR-024). The child never expires rules on
age; FR-015 forbids it, and the device clock is untrusted (spec edge case). It exists so a logcat
line can say how stale the enforced copy is.

**Write rule** (FR-016, R3): one atomic `edit {}` per outcome.

| Fetch outcome | `rules_json` | `rules_device_id` / `rules_fetched_at` |
|---|---|---|
| 200 with rules | overwritten | overwritten |
| 404 (no rules server-side) | removed | removed |
| network error / 5xx / auth failure | **untouched** | **untouched** |

**Read rule** (FR-014): `MonitoringService` start loads the persisted copy into `_cachedRules`
**before** issuing any network call, so the first `checkAndEnforceLimits` pass enforces offline.

**Lifecycle / state transitions**:

```
  (install)
      │
      ▼
  NO_RULES ──── 200 ───────────▶ PERSISTED ──── 200 ─────────▶ PERSISTED (replaced)
      │                              │
      │◀───────── 404 ───────────────┤
      │                              │
      └── net error ──▶ NO_RULES     └── net error ──▶ PERSISTED (kept, enforced)
                                     │
                                     └── device id mismatch at load ──▶ NO_RULES (discarded)
```

`NO_RULES` and `PERSISTED`-with-empty-`blockedApps` and `PERSISTED`-with-`isActive=false` are three
distinct states and must log distinctly (FR-019).

**Privacy** (Principle I): the rule set is parent-authored policy — package names, times, limits. It
contains no monitored content, no credentials and no tokens, so plaintext DataStore is appropriate
and `EncryptedSharedPreferences` is not required. Nothing new is collected from the child and nothing
new leaves the device.

---

## 4. NEW — `ScreenTimeLimitsUiState` additions (parent)

Existing state at `ScreenTimeLimitsViewModel.kt:32-57`. Three fields added; nothing removed except
the save trigger's reason to exist.

| Field | Type | Purpose |
|---|---|---|
| `savingFields` | `Set<String>` | which controls have a save in flight → FR-004 progress indication |
| `confirmedState` | `ConfirmedRuleValues` | last server-confirmed value per control → FR-005 rollback target |
| `enforcementWarning` | `String?` | set when a save succeeds for a device known to lack a capability → FR-022 / US7 scenario 4 |

`savingFields` is keyed by a small `enum class RuleField { DAILY_LIMIT, BEDTIME, STUDY_TIME, STUDY_TIME_APPS, DEVICE_LOCK, APP_LIMITS, BLOCKED_APPS, ACTIVE }`
so the UI can show per-row progress and the debounce map in R7 can key off the same enum.

`isLoading` keeps its current meaning (initial screen load). It is **not** reused for per-field
saves — FR-003 requires one control's save not to visually block another.

`error` and `clearError()` stay exactly as they are: constitution rule 10 requires Snackbar-only
error surfacing via `LaunchedEffect(uiState.error)`, already wired at
`ScreenTimeLimitsScreen.kt:78-93`.

`successMessage` loses its only producer. A toast per toggle is noise when every toggle saves, so
confirmation is carried by the switch settling into its confirmed position, not by a message. The
field and its string resource are left in place for `deleteRules`, which still uses it.

---

## 5. `EnforcementCapability` — derived, not stored

No new entity. FR-020a/FR-020b map onto existing values:

| Capability | Source of truth | Level | On absence |
|---|---|---|---|
| Usage access | `ProtectionStatusHelper.isUsageStatsEnabled` (`:275`) | hard-required | `AppLock` error + parent alert via `ProtectionType.USAGE_STATS` (`isCritical = true`) |
| Overlay | `ProtectionStatusHelper.isOverlayEnabled` (`:302`) | degraded | `AppLock` warning; present lock via notification fallback (`MonitoringService.kt:1162-1187`) |
| Notifications | `ProtectionStatusHelper.isNotificationsEnabled` (`:309`) | degraded, only matters when overlay is absent | parent alert via `ProtectionType.OVERLAY` only when overlay is **also** absent |

The alert itself is an existing `Alert` with `AlertType.DEVICE_ADMIN_DISABLED`
(`ProtectionMonitorWorker.kt:228`) — no new alert type, so no backend enum change.

---

## 6. Push payload — not a model

`{"type":"rules_changed"}` is read as a `Map<String, String>` in
`SafeGuardFirebaseMessagingService.handleDataMessage` (`:91-118`). No data class, because FR-010
forbids trusting any field in it. Only the `type` key is read; everything else is ignored and logged
as a key list, never as values. Full contract in `contracts/push-rules-changed.md`.
