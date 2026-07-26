# Phase 0 Research: End-to-End Violation Alert Delivery

**Feature**: 011-violation-alert-delivery | **Date**: 2026-07-25 | **Plan**: [plan.md](plan.md)

All Technical Context unknowns are resolved here. No `NEEDS CLARIFICATION` markers remain.

---

## R1 — Pending-alert queue storage

**Decision**: Room 2.6.1 — a new single-entity database `safeguard.db` with table `pending_alerts`, wrapped by a `@Singleton PendingAlertStore` in `data/local/`.

**Rationale**:
- The queue has **four concurrent producers** — `TextMonitoringAccessibilityService`, `MediaFileObserver`, `ImageScanWorker`, `ContentFilterVpnService` (via `TamperAlertWorker`) — and one concurrent consumer, `PendingAlertWorker`. Row-level inserts plus a per-row `DELETE` on confirmed acceptance are exactly the transaction semantics needed.
- Ordering (`ORDER BY created_at ASC`, FR-008) and the cap (FR-010) are one `LIMIT`/`OFFSET` query each, not application-level list surgery.
- Room 2.6.1 (`room-runtime`, `room-ktx`, `room-compiler` via `ksp`) is **already declared** in `app/build.gradle:164-166` with the `ksp` plugin applied — currently with zero `@Entity`/`@Dao` in the tree. Using it adds no dependency and violates no version lock.
- Survives process death and reboot, which a purely in-memory retry loop does not.

**Alternatives considered**:
- *DataStore Preferences JSON blob* (the codebase's dominant local-storage idiom, e.g. `AlertRepository`'s `alertCooldownStore`) — **rejected**: every enqueue is a read-modify-write of the whole list. Under concurrent producers this loses alerts, which is the exact bug this feature exists to fix. Also forces full-list rewrite per delivery.
- *One file per pending alert in `filesDir`* — **rejected**: manual ordering, manual cap enforcement, no atomicity across the read/delete pair, and a partial write on storage-full leaves a corrupt entry.
- *EncryptedSharedPreferences* — **rejected**: wrong tool (small scalars), and the queue holds no secrets, only already-transmittable metadata.

**Consequence**: `DatabaseModule` is the first `di` module to provide a database. Schema export stays off (`room.schemaLocation` not configured) since there is exactly one version and no migration path yet.

---

## R2 — Retry / backoff mechanism

**Decision**: `PendingAlertWorker` — `@HiltWorker` + `CoroutineWorker` + `@AssistedInject`, enqueued as **unique** one-shot work (`ExistingWorkPolicy.KEEP`) with `Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED)` and `BackoffPolicy.EXPONENTIAL, 10s`. It returns `Result.retry()` while entries remain undelivered, `Result.success()` when the queue drains.

**Trigger points**:
1. Immediately after `AlertRepository.createAlert()` enqueues a failed alert
2. `MonitoringService.onCreate()` (FR-012)
3. Connectivity return — handled **natively** by the `CONNECTED` constraint; WorkManager wakes the worker itself

**Rationale**: WorkManager already survives process death and reboot, already backs off exponentially, and already gates on connectivity — three of the four requirements (FR-008, FR-009, FR-012) for free. It is also the established idiom here (`SyncWorker`, `ImageScanWorker`, `TamperAlertWorker`, `ProtectionMonitorWorker`) and mandated by enforcement rule 8.

**Alternatives considered**:
- *`NetworkMonitor.isConnected` `callbackFlow` collected in `MonitoringService`* — **rejected**: dies with the process, no persistence, would need hand-rolled backoff, and puts scheduling logic in a Service (rule 7). `NetworkMonitor` stays used for UI state only.
- *Periodic worker every 15 min* — **rejected**: worst-case 15-minute latency violates SC-002 (2 min).

**Note**: `enqueueUniqueWork` with `KEEP` means a burst of failures does not stack workers; the single running drain picks up newly-enqueued rows on its next pass.

---

## R3 — Where alert enrichment is injected

**Decision**: inside `AlertRepository.createAlert()`, merging enrichment into the `metadata` map **before** the caller's map, so caller-supplied keys win:

```
metadata = buildMap {
    putAll(enrichmentFields)   // child_name, device_name, device_db_id, occurred_at, app_version
    callerMetadata?.let { putAll(it) }   // caller wins (FR-003)
}
```

**Rationale**: `createAlert()` is already the single funnel — `createContentBlockAlert`, `createScreenTimeLimitAlert`, `createInappropriateImageAlert`, `createInappropriateTextAlert`, and `createAppBlockedAlert` all delegate to it, and `TamperAlertWorker` calls it directly. One edit satisfies FR-001 *and* FR-002 ("uniformly to all alert types") with no call-site changes, and FR-003's precedence rule falls out of map ordering.

**Alternatives considered**:
- *Enrich at each of the five helpers* — **rejected**: five duplicated blocks, and any future alert type silently misses enrichment.
- *Enrich server-side* — **rejected**: the backend is not modified in this repo, and the child device is the only party that knows the local device name and the true occurrence time.

**`occurred_at`**: `System.currentTimeMillis()` captured in `createAlert()` (epoch millis, absolute — FR-001). Several helpers already write a `"timestamp"` key; that key is left untouched to preserve existing behaviour, and `occurred_at` is the new canonical field.

---

## R4 — Child display name capture

**Decision**: add `userFullName` to `PreferencesManager`, written by `PreferencesManager.saveUserInfo(...)` — which `AuthRepository.login()`, `register()`, and `googleSignIn()` already call with the full `User` object in scope (`response.user.fullName`).

**Rationale**: FR-004 asks for capture "at sign-in or registration if not already available"; all three auth paths converge on one existing write, so this is a two-line change with no new network call and no auth-behaviour change (spec Assumptions).

**Alternatives considered**:
- *Fetch `GET /auth/me` on demand when building an alert* — **rejected**: a network call on the detection hot path, and it fails offline exactly when the queue matters most.

**Degradation** (Edge Case): if `userFullName` is absent (account created before this ships and not yet re-authenticated), `child_name` is omitted and `ViolationNotifier` falls back to the device name, so the notification never renders a dangling separator.

---

## R5 — Parent push-token registration & startup backfill

**Decision**: `PushTokenSyncWorker` (`@HiltWorker`) with two modes, plus a direct (non-worker) clear on logout.

| Mode | Path | Trigger |
|---|---|---|
| REGISTER, role = parent | `AuthRepository.publishFcmToken(token)` → `PUT auth/me/fcm-token {"fcm_token": "<token>"}` | login/register/Google success; every `MainActivity` start with an authenticated session; `onNewToken` |
| REGISTER, role = child | `DeviceRepository.updateFcmToken(token)` → `PUT devices/{id}` | `onNewToken`; `POST /devices` carries the token at registration time |
| CLEAR | `AuthRepository.clearFcmToken()` → `PUT auth/me/fcm-token {"fcm_token": null}` | logout, **before** `tokenManager.clearTokens()` |

The token itself is fetched with `FirebaseMessaging.getInstance().token` wrapped in `suspendCancellableCoroutine` + `addOnCompleteListener`.

**Rationale**:
- This is the **missing link** in the current build: `FirebaseMessaging.getInstance().token` has **zero call sites** in `app/src/main/java`. `DeviceSetupViewModel:64` calls `deviceRepository.registerDevice(deviceName)` with no token, and `SafeGuardFirebaseMessagingService.onNewToken()` routes to `DeviceRepository.updateFcmToken()`, which short-circuits with `Error("Device not registered")` on any parent device because parents never `POST /devices`. No parent token reaches the backend today, so no violation push can be targeted.
- A worker gives retry-on-failure and offline tolerance for free, and can be enqueued identically from an Activity, a ViewModel, and a Service — satisfying rule 7 (no logic in the FCM service) and rule 4 (worker → repository → `safeApiCall{}` → `ApiService`).
- **Startup backfill** (FR-029) is required beyond the user's directive, which covers only fresh logins: parents already signed in when this ships would otherwise stay unreachable until they sign out and back in. Enqueueing on every `MainActivity` start is cheap (unique work, `KEEP`) and makes the backfill automatic.
- `suspendCancellableCoroutine` avoids adding `kotlinx-coroutines-play-services` for `await()` — the constitution forbids unjustified new dependencies.

**Logout ordering** — the clear is an **authenticated** call. Deferring it to a worker would run it after `tokenManager.clearTokens()` and `preferencesManager.clearAll()`, producing a 401 and leaving a live token registered against the account on a device that is no longer signed in. `AuthRepository.logout()` therefore: (1) best-effort `clearFcmToken()`, (2) `apiService.logout()`, (3) clear local state — with steps 1 and 2 both tolerant of failure so logout never blocks.

**Alternatives considered**:
- *Register from `MainActivity` directly* — **rejected**: business logic in an Activity, no retry, no offline handling.
- *Register only at login (directive as literally written)* — **rejected**: fails FR-029's backfill clause; see above.
- *Reuse `PUT /devices/{id}` for parents too* — **rejected**: parents have no device record; that is the current broken behaviour.

---

## R6 — Notification deep link: one-shot consumption and login resume

**Decision**: `@Singleton DeepLinkHolder` exposing `MutableStateFlow<PendingDeepLink?>` with `post()` and `consume()`.

- `ViolationNotifier` builds a `PendingIntent` targeting `MainActivity` with extras `nav_target="alerts"`, `device_id`, `device_name`, flagged `FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_CLEAR_TOP` (**not** the current `CLEAR_TASK`, which restarts a running app and violates FR-023's "without restarting").
- `MainActivity` is already `android:launchMode="singleTop"` in the manifest, so a running instance receives the tap via `onNewIntent()` — this override must be added and must call `setIntent(intent)` before posting to the holder.
- `NavGraph` collects the holder; on a non-null value it navigates to `Screen.Alerts.createRoute(deviceId, deviceName)` and calls `consume()` in the same effect.
- **Rotation** (FR-024): the holder is emptied at navigation time, so an Activity/Composable recreation finds `null` and does not re-navigate.
- **Expired session** (FR-026): if `tokenManager.isLoggedIn()` is false when the intent arrives, the holder keeps the value and the app proceeds to Login as normal; `NavGraph`'s existing `onLoginSuccess` callback checks the holder and continues to Alerts instead of Dashboard. Because the holder is `@Singleton` (not `savedStateHandle`), it survives the whole login detour.

**Rationale**: satisfies FR-023/024/026 without a new destination (FR-028) and without `savedStateHandle` gymnastics across a `popUpTo(0)` login navigation that destroys the back stack.

**Alternatives considered**:
- *Android App Links / `<nav-deep-link>` in the manifest* — **rejected**: introduces a navigation destination and a URI surface the spec does not want, and does not survive the login detour.
- *Pass the target through `Intent` extras only and read them in Compose* — **rejected**: the intent persists on the Activity, so rotation re-reads it and re-navigates (FR-024 failure).

**Unlinked target device** (FR-027): handled in `AlertsViewModel`. When a deep link supplies a `deviceId`, the ViewModel validates it against the parent's device list; if the device is gone (404 / absent), it resets `deviceId`/`deviceName` to `null`, which the existing screen already renders as the unfiltered alert list. No new destination, no error state.

---

## R7 — Notification identity and de-duplication

**Decision**: notification id = `alert_id.hashCode()` (stable per alert), replacing the `AtomicInteger` counter for violation notifications. Channel stays `SafeGuardApplication.CHANNEL_ALERTS`.

**Rationale**: FR-020 requires a repeat push of the same alert to *update* rather than stack. `NotificationManager.notify(id, …)` with a stable id does exactly that. The existing `getNextNotificationId()` counter guarantees the opposite — a fresh id per delivery — which is the current duplicate-stack bug. The counter remains for non-alert notifications.

**Severity mapping** (FR-021): the existing `critical → PRIORITY_MAX`, `high → PRIORITY_HIGH`, else `PRIORITY_DEFAULT` mapping moves verbatim into `ViolationNotifier` — no behaviour change (SC-009).

**Expandable body** (FR-019): `NotificationCompat.BigTextStyle().bigText(body)` so long child/device names are fully readable when expanded while the collapsed line stays platform-truncated.

**Alternatives considered**:
- *Notification groups/summaries* — **rejected**: explicitly out of scope in the spec.

---

## R8 — Localization and time formatting

**Decision**:
- Violation-type labels are string resources `violation_type_inappropriate_text|inappropriate_image|content_block|screen_time_limit|app_blocked|device_admin_disabled` plus `violation_type_unknown`, added to `values/strings.xml` and `values-ar/strings.xml` (FR-016/FR-017).
- Lookup is an explicit `when` over the pushed `alert_type` string with `violation_type_unknown` as the `else` branch — an unrecognized type never surfaces a raw internal identifier.
- Strings are resolved through `LocaleHelper.localizedContext(context)`, matching `AlertRepository` and `AlertsViewModel`, so the in-app language override wins over the system locale.
- Time: `android.text.format.DateUtils.isToday(occurredAt)` → `DateFormat.getTimeInstance(SHORT, locale)`; otherwise `DateFormat.getDateInstance(SHORT, locale)` + short time (FR-018).
- Missing/malformed `occurred_at` → the time segment is omitted entirely (Edge Case: never show an epoch number or "1970").

**Rationale**: reuses the feature-008 localization machinery already in the codebase; `DateUtils`/`DateFormat` render Arabic numerals and ordering per locale with no extra dependency.

**Alternatives considered**:
- *Format the display string on the backend* — **rejected**: the backend does not know the parent device's locale or in-app language override.
- *`SimpleDateFormat` with a hardcoded pattern* — **rejected**: not locale-correct for Arabic (SC-007).
