# Internal Component Contracts: End-to-End Violation Alert Delivery

**Feature**: 011-violation-alert-delivery | **Date**: 2026-07-25 | **Plan**: [plan.md](plan.md)

In-app contracts between the components this feature adds or changes. Signatures are the intended shape, not final code.

---

## `PendingAlertStore` — `data/local/`, `@Singleton`

Wraps `PendingAlertDao`. The **only** type allowed to touch the DAO.

```kotlin
suspend fun enqueue(payload: AlertCreate, deviceToken: String)
suspend fun oldestFirst(limit: Int = 20): List<PendingAlertEntity>
suspend fun delete(id: Long)
suspend fun recordAttempt(id: Long)
suspend fun count(): Int
suspend fun clear()
```

**Invariants**
- `enqueue` trims to the 100-row cap by deleting the **oldest** rows first, inside the same transaction as the insert.
- `oldestFirst` orders by `created_at ASC`.
- All functions run on `Dispatchers.IO`.
- Never called from a Service, Receiver, or Worker directly — always via `AlertRepository`.
- `clear()` is invoked from the sign-out path.

---

## `AlertRepository` — changed contract

```kotlin
suspend fun createAlert(
    alertType: AlertType,
    severity: AlertSeverity,
    title: String,
    message: String,
    metadata: Map<String, Any>? = null,
    evidenceData: String? = null
): NetworkResult<Alert>          // signature UNCHANGED (FR-013)

suspend fun flushPendingAlerts(): FlushOutcome   // NEW — called only by PendingAlertWorker
suspend fun clearPendingAlerts()                 // NEW — called on sign-out
```

**Behaviour added to `createAlert`**
1. Merge the five enrichment keys into `metadata`, caller keys winning (FR-001/002/003).
2. On a retryable failure — network/IO exception or HTTP 5xx — enqueue the payload and schedule `PendingAlertWorker` (FR-006).
3. On suppression, "device not registered", or any other 4xx — do **not** enqueue (FR-007).
4. Return the same `NetworkResult` the caller would have seen before this feature (FR-013). Detection and enforcement observe no change.

**`FlushOutcome`**: `data class FlushOutcome(val delivered: Int, val remaining: Int)`. `remaining > 0` ⇒ the worker returns `Result.retry()`.

---

## `PendingAlertWorker` — `worker/`, `@HiltWorker`

```kotlin
@HiltWorker
class PendingAlertWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val alertRepository: AlertRepository
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result   // success when drained, retry while entries remain

    companion object {
        fun enqueueImmediate(context: Context)   // unique work, KEEP, CONNECTED, EXPONENTIAL 10s
    }
}
```

**Contract**
- Unique work name `pending_alert_delivery`, `ExistingWorkPolicy.KEEP` — a failure burst does not stack workers.
- `Constraints`: `NetworkType.CONNECTED`. WorkManager itself provides the "deliver when connectivity returns" trigger (FR-008).
- `BackoffPolicy.EXPONENTIAL`, 10 s initial (FR-009 — increasing delays, never tight-looping).
- Delivers oldest-first; deletes a row **only** after a `NetworkResult.Success` (FR-008).
- Contains no business logic beyond calling `alertRepository.flushPendingAlerts()` and mapping the outcome to a `Result`.

**Enqueue points**: `AlertRepository` on retryable failure; `MonitoringService.onCreate()` (FR-012).

---

## `PushTokenSyncWorker` — `worker/`, `@HiltWorker`

```kotlin
@HiltWorker
class PushTokenSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val authRepository: AuthRepository,
    private val deviceRepository: DeviceRepository,
    private val preferencesManager: PreferencesManager
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result

    companion object {
        fun enqueue(context: Context)   // unique work, KEEP, CONNECTED, EXPONENTIAL 10s
    }
}
```

**Contract**
- Fetches the current token via `FirebaseMessaging.getInstance().token` wrapped in `suspendCancellableCoroutine` (no new dependency — see research R5).
- Routes by role from `PreferencesManager`:
  - parent → `authRepository.publishFcmToken(token)`
  - child → `deviceRepository.updateFcmToken(token)`
- No-ops (returns `Result.success()`) when not signed in.
- Returns `Result.retry()` on a network failure; `Result.failure()` on a non-retryable error.
- **Never** handles the logout clear — that is a direct, ordered call (below).
- The token value is never logged and never persisted locally.

**Enqueue points**: `AuthViewModel` after login/register/Google success; `MainActivity.onCreate()` when a session exists (FR-029 backfill); `SafeGuardFirebaseMessagingService.onNewToken()`.

---

## `AuthRepository` — changed contract

```kotlin
suspend fun publishFcmToken(token: String): NetworkResult<MessageResponse>   // NEW
suspend fun clearFcmToken(): NetworkResult<MessageResponse>                  // NEW — sends fcm_token = null
suspend fun logout(): NetworkResult<Unit>                                     // CHANGED — ordering
```

**`logout()` ordering — mandatory**

```text
1. clearFcmToken()      // authenticated; best-effort, failure must not block logout
2. apiService.logout()  // existing
3. tokenManager.clearTokens()
4. preferencesManager.clearAll()
5. alertRepository.clearPendingAlerts()   // FR-011
```

Steps 1 and 2 are both best-effort; local state is cleared regardless, preserving today's behaviour. Step 5 guarantees held alerts never ship under a different account.

`saveUserInfo(...)` also records `fullName` so `child_name` is available offline (FR-004).

---

## `ViolationNotifier` — `util/`, `@Singleton`

```kotlin
fun handleViolationPush(data: Map<String, String>)
```

**Contract**
- **Role guard first** (FR-014): if `preferencesManager.isParent` is false → `Timber.d(...)` and return. Nothing is displayed, no sound plays, no work is enqueued.
- Parses the push into the `ViolationPush` shape, degrading per [data-model.md §3](../data-model.md) for every missing or malformed field.
- Resolves the violation-type label from string resources via `LocaleHelper.localizedContext(context)`; unknown types → `violation_type_unknown` (FR-016/017).
- Formats `occurred_at` locale-aware: short time if today, short date + time otherwise; omitted entirely if missing/malformed (FR-018).
- Builds on channel `SafeGuardApplication.CHANNEL_ALERTS` with `BigTextStyle` (FR-019) and the existing severity→priority mapping, unchanged (FR-021).
- Posts with notification id `alert_id.hashCode()` so a repeat push **updates** the existing notification (FR-020).
- Attaches a `PendingIntent` to `MainActivity` (`FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_CLEAR_TOP`, `PendingIntent.FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE`) carrying `EXTRA_NAV_TARGET`, `EXTRA_DEVICE_ID`, `EXTRA_DEVICE_NAME`.

---

## `SafeGuardFirebaseMessagingService` — changed contract

Retains **zero business logic** (Constitution rule 7):

```kotlin
override fun onNewToken(token: String) {
    PushTokenSyncWorker.enqueue(this)      // role routing lives in the worker/repositories
}

private fun handleAlertMessage(data: Map<String, String>) {
    violationNotifier.handleViolationPush(data)
}
```

- Severity mapping, notification construction, and the notification-id counter for alerts move out into `ViolationNotifier`.
- `handleCommandMessage` / `handleSyncMessage` (child `sync` path) are unchanged.
- The `message.notification?.let { showNotification(...) }` branch keeps working for any non-violation push, but violation pushes are data-only by contract so they never take it.

---

## `DeepLinkHolder` — `util/`, `@Singleton`

```kotlin
val pending: StateFlow<PendingDeepLink?>
fun post(link: PendingDeepLink)
fun consume()
```

**Contract**
- `post()` is called from `MainActivity.onCreate` / `onNewIntent` when the intent carries `EXTRA_NAV_TARGET == "alerts"`.
- `NavGraph` observes `pending`; on a non-null value it navigates to `Screen.Alerts.createRoute(deviceId, deviceName)` and calls `consume()` in the same effect → rotation cannot re-navigate (FR-024).
- If no session exists at post time, the value is **retained**; `onLoginSuccess` in `NavGraph` checks the holder and continues to Alerts instead of Dashboard (FR-026).
- Process-scoped, never persisted.

---

## `MainActivity` — changed contract

- Adds `override fun onNewIntent(intent: Intent)` → `setIntent(intent)` then post to `DeepLinkHolder`. Required because the manifest already declares `android:launchMode="singleTop"`, so a running app receives the tap here rather than in `onCreate` (FR-023 "without restarting").
- Enqueues `PushTokenSyncWorker` when `tokenManager.isLoggedIn()` (FR-029 backfill).
- Keeps its existing `startMonitoringServiceIfNeeded()` behaviour.

---

## `AlertsViewModel` — changed contract

```kotlin
fun setDevice(deviceId: Int?, deviceName: String?)   // CHANGED: validates the device still exists
fun onResume()                                        // NEW: refresh on every resume (FR-025)
```

- **Unlinked-device fallback** (FR-027): when a deep link supplies a `deviceId` that is not in the parent's device list (404/absent), the ViewModel resets `deviceId`/`deviceName` to `null` and loads the unfiltered list. No new destination, no error surface.
- `onResume()` re-runs `loadAlerts()` so the list is current after a notification tap and on every return to the screen (FR-022/FR-025). `AlertsScreen` hooks it with a lifecycle-resume effect.
- Errors continue to surface via the existing `uiState.error` → `Snackbar` → `clearError()` path (rule 10).

---

## `DashboardViewModel` / `DashboardScreen` — changed contract

- `uiState` gains `notificationsEnabled: Boolean` (from `NotificationManagerCompat.areNotificationsEnabled()`, refreshed on resume) and `notificationBannerDismissed: Boolean` (persisted).
- Parent role + API 33+ + permission not granted → request `POST_NOTIFICATIONS` via `ActivityResultContracts.RequestPermission()`, matching the existing usage in `PermissionsSetupScreen` (FR-031). This is an ordinary runtime permission, not one of the four special permissions, so settings-routing (rule 6) does not apply.
- Notifications disabled and banner not dismissed → show a **non-blocking, dismissible** inline banner with a route to system notification settings (FR-032). It blocks no functionality and is not an error Snackbar.
