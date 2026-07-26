# Backend Contract: End-to-End Violation Alert Delivery

**Feature**: 011-violation-alert-delivery | **Date**: 2026-07-25 | **Plan**: [plan.md](plan.md)

The backend (FastAPI, `https://bw.noor.net:8090/api/v1`) is **not modified in this repository**. This document is the cross-team contract the Android client codes against. Items marked *(open)* must be confirmed with the backend developer before the corresponding client task is implemented.

---

## 1. `PUT /auth/me/fcm-token` — parent push-token registration *(confirmed)*

Registers, refreshes, or clears the push token for the **authenticated user's current device**. Authenticated with the normal bearer token (`AuthInterceptor` supplies it).

**Request**

```json
{ "fcm_token": "cV3n…:APA91b…" }
```

**Clear (logout)**

```json
{ "fcm_token": null }
```

**Response**: `200` with the standard `MessageResponse` shape.

**Retrofit binding** (`ApiService`):

```kotlin
@PUT("auth/me/fcm-token")
suspend fun updateMyFcmToken(@Body request: FcmTokenUpdateRequest): Response<MessageResponse>
```

```kotlin
data class FcmTokenUpdateRequest(
    @SerializedName("fcm_token")
    val fcmToken: String?
)
```

**Client call sites**

| When | Value sent |
|---|---|
| Login / register / Google sign-in success (parent) | current FCM token |
| Every `MainActivity` start with an authenticated parent session | current FCM token |
| Firebase `onNewToken` (parent) | new token |
| Logout — issued **before** local tokens are cleared | `null` |

> The logout clear is an authenticated call. If it is deferred until after `TokenManager.clearTokens()` it returns 401 and leaves a live token registered to an account on a signed-out device.

---

## 2. `POST /devices` — child device registration *(existing endpoint, client fix)*

Already accepts `fcm_token` (`DeviceRegisterRequest.fcmToken`). The client currently sends `null` because `DeviceSetupViewModel` never fetches a token. **Client change only**: fetch the FCM token before calling, and pass it.

```json
{
  "device_id": "<local uuid>",
  "device_name": "Ali's Pixel",
  "device_model": "Google Pixel 7",
  "android_version": "14",
  "app_version": "1.1.4",
  "device_info": { "manufacturer": "Google", "model": "Pixel 7", "build": "…", "sdk_version": "34" },
  "fcm_token": "cV3n…:APA91b…"
}
```

---

## 3. `PUT /devices/{deviceId}` — child token refresh *(existing endpoint, existing binding)*

Used on `onNewToken` for the **child** role only. `DeviceRepository.updateFcmToken(token)` already implements this; it short-circuits with `Error("Device not registered")` when `deviceDbId == -1`, which is correct for parents (they have no device record) and is why parents need §1.

```json
{ "fcm_token": "cV3n…:APA91b…" }
```

---

## 4. `POST /alerts` — enriched alert payload

Unchanged endpoint, unchanged top-level shape, unchanged auth (`X-Device-Token` header). The five identity/timing fields are added **inside the existing `metadata` object**.

```json
{
  "device_id": 42,
  "alert_type": "inappropriate_text",
  "severity": "high",
  "title": "…",
  "message": "…",
  "metadata": {
    "child_name": "Ali",
    "device_name": "Ali's Pixel",
    "device_db_id": 42,
    "occurred_at": 1785000000000,
    "app_version": "1.1.4",

    "package_name": "com.whatsapp",
    "app_name": "WhatsApp",
    "primary_category": "bullying",
    "confidence": 0.81,
    "reason": "…",
    "detection_method": "ml_model"
  },
  "evidence_data": null
}
```

- `occurred_at` is **epoch milliseconds**, absolute.
- No monitored text and no image bytes are ever added by this feature (Constitution Principle I).
- Existing `metadata` keys are untouched; a caller that already sets one of the five new keys keeps its own value.

**Key names** *(open — Backend Coordination item 6)*: `child_name`, `device_name`, `device_db_id`, `occurred_at`, `app_version` are the client's proposal. The client will align to whatever the backend finalizes.

**Idempotency** *(open — Backend Coordination item 5)*: a queued alert may be resubmitted after an ambiguous timeout (server accepted, client never saw the response). The backend must tolerate this, or specify a dedup id. `PendingAlertEntity.client_alert_uuid` is already generated and reserved for that purpose, so adopting a dedup id later is a one-field change.

---

## 5. Violation push — FCM message shape

Backend → parent devices. Must be **data-only** (no `notification` block) so the client controls display in both foreground and background, and so the role guard (FR-014) can suppress it on child devices.

```json
{
  "data": {
    "type": "alert",
    "alert_id": "9182",
    "alert_type": "inappropriate_text",
    "severity": "critical",
    "child_name": "Ali",
    "device_name": "Ali's Pixel",
    "device_db_id": "42",
    "occurred_at": "1785000000000",
    "title": "…",
    "body": "…"
  },
  "android": { "priority": "high", "ttl": "86400s" }
}
```

**Requirements**

| Attribute | Requirement | Spec ref |
|---|---|---|
| Targeting | **All parent devices linked to the child's family**, not a single token | Backend Coordination 2 |
| Payload | Data-only; no `notification` block | Backend Coordination 3 |
| Priority | `high` | Backend Coordination 4 |
| TTL | ≥ 24 h, so a briefly-offline parent still receives it | Backend Coordination 4 |
| `alert_id` | Stable per alert — the client derives the notification id from it so re-delivery **updates** instead of stacking | FR-020 |
| `device_db_id` | Enables the deep link to open the Alerts screen filtered to the originating device | FR-023 |

All `data` values arrive as strings; the client parses `alert_id`, `device_db_id`, and `occurred_at` defensively and degrades per [data-model.md §3](../data-model.md) when a field is missing or malformed.

---

## 6. Sync command push *(existing, no client change)*

Backend → child device when a parent changes a rule or filter.

```json
{ "data": { "type": "command", "command": "sync" } }
```

Already handled: `SafeGuardFirebaseMessagingService.handleCommandMessage()` → `SyncWorker.enqueueImmediate()`. The equivalent `{"type": "sync"}` form is also already handled. Listed here only to record that the user-specified server-side behaviour ("parent changes a rule → silent sync command → child re-fetches instantly") needs **no client work**.

---

## Open items summary

| # | Item | Blocks |
|---|---|---|
| 5 | Idempotency / client-generated dedup id on `POST /alerts` | Enrichment + queue-drain tasks (safe to build without it; adding it later is one field) |
| 6 | Final `metadata` key names | Enrichment tasks + `ViolationNotifier` parsing |

Neither blocks `/speckit-tasks`.
