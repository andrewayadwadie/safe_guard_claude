# Phase 1 Data Model: End-to-End Violation Alert Delivery

**Feature**: 011-violation-alert-delivery | **Date**: 2026-07-25 | **Plan**: [plan.md](plan.md)

Entities are derived from spec §Key Entities. Field names below are the *client-side* names; the wire-format key names are in [contracts/backend-contract.md](contracts/backend-contract.md).

---

## 1. `PendingAlertEntity` (new — Room, child device only)

Table `pending_alerts` in `safeguard.db` (version 1). Holds an alert whose submission failed for a retryable reason.

| Column | Type | Constraint | Purpose |
|---|---|---|---|
| `id` | `Long` | PK, autogenerate | Row identity |
| `client_alert_uuid` | `String` | not null, unique | Client-generated dedup id, reserved for Backend Coordination item 5. Generated at enqueue; not transmitted until the backend confirms it wants it. |
| `payload_json` | `String` | not null | The exact `AlertCreate` (already enriched) serialized with the app's Gson instance |
| `device_token` | `String` | not null | `X-Device-Token` header value captured at enqueue, so a queued alert is submitted under the identity that produced it |
| `created_at` | `Long` | not null, indexed | Epoch millis of enqueue. Delivery order and cap eviction both key on this. |
| `attempt_count` | `Int` | not null, default 0 | Bookkeeping/diagnostics; backoff itself is WorkManager's |
| `last_attempt_at` | `Long?` | nullable | Diagnostics |

**Invariants**
- Ordered oldest-first on read (`ORDER BY created_at ASC`) — FR-008.
- Hard cap 100 rows; on insert past the cap the **oldest** row is deleted — FR-010, spec Assumptions.
- Rows are deleted **only** on confirmed acceptance (HTTP 2xx) — FR-008.
- Whole table is cleared on sign-out — FR-011, Edge Case "held alerts belong to a previous account".
- `payload_json` contains no monitored text and no image bytes — it is byte-identical to what `POST /alerts` already sends (Principle I).

**Lifecycle**

```text
                 suppressed (dedup / cooldown / daily cap / device not registered)
                        │
                        └──────────────► DISCARDED   (never enqueued — FR-007)
detected alert
       │
       └── createAlert() ── POST /alerts ──┬── 2xx ────────────► DELIVERED (not enqueued)
                                           │
                                           ├── network failure ─┐
                                           └── 5xx ─────────────┴──► QUEUED
                                                                       │
                                        PendingAlertWorker (CONNECTED) │
                                                                       ▼
                                              ┌──────────────► RETRYING ──► 2xx ──► DELETED
                                              │                   │
                                              └── still failing ──┘  (exponential backoff)

  QUEUED ── cap exceeded by a newer alert ──► EVICTED (oldest-out)
  QUEUED ── sign-out ──────────────────────► CLEARED
```

**Classification rule (FR-006 vs FR-007)** — the deciding question is *"was this a decision or a failure?"*

| Outcome of `createAlert()` | Enqueue? |
|---|---|
| `Error("Device not registered")` | No — rejection, not failure |
| `Error("Duplicate content - alert skipped")` | No — suppression |
| `Error("Cooldown active for category: …")` | No — suppression |
| `Error("Daily limit reached for category: …")` | No — suppression |
| Network/IO exception surfaced by `safeApiCall{}` | **Yes** |
| HTTP 5xx | **Yes** |
| HTTP 4xx other than the above | No — the payload will never be accepted; retrying is pointless |

Note that the three suppression paths return **before** `createAlert()` is reached (they short-circuit inside `createInappropriateTextAlert`), so the classifier only has to recognize the "device not registered" and HTTP-status cases. The suppression semantics of FR-005/SC-009 are therefore preserved by construction.

---

## 2. `Alert` — enriched metadata (existing entity, additive change)

`AlertCreate`/`Alert` keep their existing fields (`deviceId`, `alertType`, `severity`, `title`, `message`, `metadata`, `evidenceData`). This feature adds five keys **inside the existing `metadata` map** — no new top-level fields, no schema change on `Alert`.

| Key | Type | Source | Notes |
|---|---|---|---|
| `child_name` | `String` | `PreferencesManager.userFullName` | Omitted when unknown; notifier falls back to device name |
| `device_name` | `String` | `PreferencesManager.deviceName` | Set by `saveDeviceInfo()` at registration |
| `device_db_id` | `Int` | `PreferencesManager.deviceDbId` | Backend device record id |
| `occurred_at` | `Long` | `System.currentTimeMillis()` at alert creation | Epoch millis, absolute (FR-001) |
| `app_version` | `String` | `BuildConfig.VERSION_NAME` | Reporting app version |

**Precedence** (FR-003): caller-supplied `metadata` is merged **last**, so an alert that already carries one of these keys keeps its own value.

**Unchanged**: existing metadata keys (`package_name`, `app_name`, `categories`, `primary_category`, `confidence`, `reason`, `timestamp`, `detection_method`, `domain`, `limit`, `usage`) and all title/message/severity behaviour (FR-005).

---

## 3. `ViolationPush` (new — parsed shape, parent device only)

Not persisted. The parsed form of a data-only FCM message with `type="alert"`.

| Field | Type | Required | Behaviour when missing/invalid |
|---|---|---|---|
| `alertId` | `String` | yes | Without it there is no stable notification id and no deep-link target → fall back to the counter id and an unfiltered Alerts deep link |
| `alertType` | `String` | no | → `violation_type_unknown` generic label (FR-017) |
| `severity` | `String` | no | → `PRIORITY_DEFAULT` |
| `childName` | `String` | no | Segment omitted; no dangling separator |
| `deviceName` | `String` | no | Segment omitted |
| `deviceDbId` | `Int` | no | Deep link goes to the unfiltered Alerts screen |
| `occurredAt` | `Long` | no | Time segment omitted entirely (never "1970") |
| `title` | `String` | no | Falls back to the localized generic violation title |
| `body` | `String` | no | Composed from the identity segments |

**Derived display**: `"{childName} — {deviceName} · {localized type} · {formatted time}"`, with any missing segment (and its separator) dropped.

---

## 4. `PendingDeepLink` (new — in-memory, parent device only)

Held by `@Singleton DeepLinkHolder` as `MutableStateFlow<PendingDeepLink?>`.

| Field | Type | Notes |
|---|---|---|
| `deviceId` | `Int?` | `null` → unfiltered Alerts screen |
| `deviceName` | `String?` | Display only; URL-encoded by `Screen.Alerts.createRoute` |

**Invariants**
- Written by `MainActivity.onCreate`/`onNewIntent` from notification intent extras.
- Read exactly once: `consume()` clears it in the same effect that navigates (FR-024 — rotation finds `null`).
- Survives the login detour because the holder is process-scoped, not back-stack-scoped (FR-026).
- Not persisted across process death — a cold-start tap re-delivers the intent anyway.

---

## 5. `Child Display Name` (new field on an existing store)

`PreferencesManager.userFullName: String?` in `safeguard_prefs` (EncryptedSharedPreferences).

- Written by `saveUserInfo(userId, email, role, fullName)` on login / register / Google sign-in (FR-004).
- Cleared by `clearAll()` at sign-out.
- Read only by `AlertRepository` when enriching.

---

## 6. Parent reachability state (new fields on existing stores)

| Field | Store | Purpose |
|---|---|---|
| `notificationBannerDismissed` | `PreferencesManager` | The parent dismissed the notifications-disabled banner; it stays dismissed and blocks nothing (FR-032) |
| *(none — token is not persisted)* | — | The FCM registration token is fetched fresh from `FirebaseMessaging` at each sync and published; it is never stored locally and never logged |

---

## Entity relationships

```text
PreferencesManager ──┐
 (child_name,        │
  device_name,       ├──► AlertRepository.createAlert()
  device_db_id)      │        │
BuildConfig ─────────┘        │ enriched AlertCreate
                              │
                    ┌─────────┴──────────┐
                    │                    │
              POST /alerts          PendingAlertStore ──► PendingAlertWorker ──► POST /alerts
                    │                (Room, cap 100)
                    ▼
              backend alert store
                    │  (server-side fan-out to linked parents' stored FCM tokens)
                    ▼
              ViolationPush ──► ViolationNotifier ──► notification ──► DeepLinkHolder ──► Alerts screen
```
