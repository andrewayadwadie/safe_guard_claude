# Contract: `rules_changed` push (new backend capability, A5)

**Feature**: 017-app-lock-enforcement | **Date**: 2026-10-18
**Client handler**: `SafeGuardFirebaseMessagingService.handleDataMessage` (`:91-118`)
**Status**: ❓ **does not exist today**. The client side ships in phase 3 regardless; the backend side
is new work. Non-blocking — without it, delivery falls back to the 30 s periodic refresh (FR-012a),
and only SC-002's 10-second case is lost.

---

## 1. What the backend sends

After a `PUT /screen-time-rules/{device_id}` succeeds, send one **data-only, high-priority** FCM
message to the child device registered for that `device_id`.

```json
{
  "to": "<child device FCM token>",
  "priority": "high",
  "data": {
    "type": "rules_changed"
  }
}
```

Hard requirements:

- **Data-only.** No `notification` block. A `notification` payload is handled by the system when the
  app is backgrounded and `onMessageReceived` may not run, which would silently lose the trigger.
- **`priority: high`.** Normal priority is deferrable in Doze, which defeats the ≤10 s target and
  also forfeits the temporary background-start allowance the client needs to poke its service.
- **No rule content.** FR-010. The client ignores every key except `type`. Sending the list would
  create a second, unauthenticated source of truth for a child-safety control.
- **Per-device.** Addressed to the token of the device whose rules changed, not a topic. Sending it
  to a topic would make every child in the family refresh on any parent edit.

Optional, logged but never acted on:

```json
{ "data": { "type": "rules_changed", "device_id": "42" } }
```

If `device_id` is present the client logs it for correlation only; it does not select which device's
rules to fetch. The child always fetches its own `deviceDbId`.

---

## 2. What the client does with it

```
handleDataMessage(data)
  type == "rules_changed"
    → AppLock.i("PUSH rules_changed keys=[…] -> refresh")
    → start MonitoringService with ACTION_REFRESH_RULES
        → ScreenTimeRulesRepository.getCachedRules(deviceDbId, forceRefresh = true)
            → GET /screen-time-rules/{deviceDbId}      (see screen-time-rules-api.md §2)
            → 200: overwrite in-memory cache + persisted copy
        → checkAndEnforceLimits()                       (immediate, does not wait for the tick)
```

The push is a doorbell. Everything authoritative arrives over the authenticated REST read.

**Idempotent.** Duplicate or replayed pushes cause an extra `GET` and an extra enforcement pass,
both harmless. No de-duplication is implemented, deliberately — a dropped push is worse than a
doubled one.

**Unauthenticated in effect.** Anyone able to deliver an FCM message to the device can make it
refresh its rules from the server. The worst outcome is one extra authenticated `GET`, so no
signature or nonce is required. This is the reason the payload must never be trusted.

---

## 3. What is removed from the client

The `"lock"` command branch at `SafeGuardFirebaseMessagingService.kt:132-135`:

```kotlin
"lock" -> {
    // Device lock command (v2 feature)
    Timber.d("Lock command received - not implemented in v1")
}
```

Deleted. FR-009 requires exactly one delivery path; a logged no-op is a second path that looks
implemented from the backend's side. If the backend currently sends `command=lock` expecting it to
work, **it does nothing today** and must switch to `type=rules_changed`.

`command=sync` (`:127-131` → `SyncWorker.enqueueImmediate`) and `type=sync` (`:143+`) are **kept**
unchanged — they are a working general sync path used by other features, and this feature's
guardrails forbid touching them.

---

## 4. Fallbacks that remain in place

Delivery must not depend on this push (FR-012). All four existing paths stay:

| Path | Where | Cadence |
|---|---|---|
| Enforcement-loop refresh | `MonitoringService.kt:583` | 30 s after this feature (was 90 s) |
| Loop start / service start | `MonitoringService.kt:568` | on start |
| Network regained | `MonitoringService.kt:178-209` | on connectivity change |
| `performInitialSync` | `MonitoringService.kt:1244` | on service init |
| `SyncWorker` | `SyncWorker.kt:131-154` | every 5 min |

---

## 5. Questions for the backend developer

| # | Question | Blocking? |
|---|---|---|
| A5.1 | Can the backend send a data-only high-priority FCM message to a specific child device after a successful rules `PUT`? | **No** — feature degrades to 30 s polling |
| A5.2 | Does the backend already store a usable FCM token per child device? (`DeviceUpdateRequest.fcmToken`, `Models.kt:185-186`, and `PushTokenSyncWorker` suggest yes) | No |
| A5.3 | Is anything currently sending `command=lock` or any other rules-related push? If so it is being dropped today. | No, but answer before phase 3 |
| A5.4 | Should the push fire on every successful `PUT`, or only when `blocked_apps` / `is_active` actually changed? Either is fine for the client; per-change is kinder to battery. | No |
