# Contract: AccountTeardownManager (internal)

`@Singleton class AccountTeardownManager @Inject constructor(...)` is used **only** by the deletion flow (`AccountDeletionCoordinator`). The logout flow is **not** migrated in this feature; migrating it is a follow-up.

## Entry point

```kotlin
suspend fun teardown(reason: TeardownReason)   // runs inside withContext(NonCancellable + Dispatchers.IO)

enum class TeardownReason {
    ACCOUNT_DELETED,        // server returned 2xx: emit AccountDeleted, raise post-deletion notice
    SESSION_GONE_AFTER_MARKER // research RD-3: full wipe, but NO "deleted" claim (TokenRevoked already emitted)
}
```

- **Idempotent**: calling it twice is harmless. Every step tolerates "already done".
- **Never throws**: each step is wrapped in its own `runCatching`. A failed step is logged and the next step still runs, because a partial wipe beats an aborted one.
- **Not cancellable**: once the server has confirmed the deletion, a cleared ViewModel or leaving the screen cannot stop it (D4).

## Steps (strict order)

| # | Step | Implementation (existing APIs only) | Why it comes here |
|---|---|---|---|
| 1 | Mark teardown | `AccountDeletionState.markTeardownInProgress()` (`commit()`) | If the process dies from here on, the next launch finishes the job (FR-016a). |
| 1b | Switch content filtering off | `preferencesManager.isContentFilteringEnabled = false` | VPN `onDestroy` checks this flag before sending the `vpn_disconnected` tamper alert, and ProtectionMonitorWorker checks it before looking at the VPN (research R6). |
| 2 | Cancel background work and the restart alarm | `WorkManager.getInstance(ctx).cancelAllWork()`; `alarmManager.cancel(PendingIntent.getService(ctx, 1, Intent(ctx, MonitoringService::class.java), FLAG_NO_CREATE or FLAG_IMMUTABLE))` when not null | Nothing may reach the network with a dead token, which would cause a `TokenRevoked` toast, and nothing may restart protection. |
| 3 | Stop services | `ctx.startService(MonitoringService.getStopIntent(ctx))`; `ctx.startService(Intent(ctx, ContentFilterVpnService::class.java).setAction(ContentFilterVpnService.ACTION_STOP))`. Only when running (`MonitoringService.isRunning`, `ContentFilterVpnService.isVpnRunning(ctx)`), and each call in `runCatching` (a background start restriction must not abort the teardown). | The existing stop actions are used. No changes are made inside either service. |
| 4 | Wipe local data | `okHttpCache.evictAll()`; `alertRepository.clearPendingAlerts()` (Room); `alertRepository.clearCooldowns()` (new); `customWordRepository.clearCache()`; `screenTimeRulesRepository.clearCache()`; `imageHasher.clearCache()`; `textHasher.clearCache()`; delete `File(filesDir, "image_backup")` recursively; `NotificationManagerCompat.from(ctx).cancelAll()`; best-effort `FirebaseMessaging.getInstance().deleteToken()` and `firebaseAnalytics.resetAnalyticsData()` | Everything belonging to the user except the prefs and tokens that later steps need. |
| 5 | Clear preferences, raise the notice, then tokens (**last**) | `preferencesManager.clearAll()`; then `AccountDeletionState.raiseNotice(showNotice, childDevice)` (the Login screen reads these flags the moment it is created, and the next step navigates to it); then `tokenManager.clearTokensDueToAccountDeletion()`, which clears `secure_token_prefs` and emits `AuthEvent.AccountDeleted` **only** when `reason == ACCOUNT_DELETED` (for `SESSION_GONE_AFTER_MARKER` the tokens are already cleared and `TokenRevoked` has already been emitted, so no event is sent) | Clearing prefs switches off every service gate (`shouldRunMonitoring`, `isDeviceRegistered`, `monitoringConsentGranted`). Tokens go last so nothing that ran earlier can hit a 401. The notice is raised BEFORE the token clear because that clear emits `AccountDeleted`, which navigates to Login immediately. |
| 6 | Finalize | `WorkManager.cancelAllWork()` again (catches a tamper alert that raced into the queue during step 3); `AccountDeletionState.completeTeardown(showNotice = reason == ACCOUNT_DELETED, childDevice = wasChild)` clears the marker and, for a child device after a confirmed deletion, sets the FR-022a uninstall note | Safety net, then durable completion. |

## What is kept (by design)

- `filesDir/models` (ML models, the same for every user)
- `locale_prefs` (the device language)
- The Android-granted special permissions (accessibility, usage access, overlay, VPN consent). The app cannot revoke these; the FR-022a note sends the user to the app's system settings page.
- Blurred images already in the gallery (the originals are deleted, so they can no longer be restored; see FR-021)

## Logging (D7)

- Tag `AccountDeletion`, with lines such as `Timber.tag("AccountDeletion").i("teardown step=%s ok", step)` and `.w("teardown step=%s failed: %s", step, e.javaClass.simpleName)`.
- Never log the password, email, tokens, user id, child name, or device token.

## Testability

Every Android side effect sits behind `DeviceTeardownOps` (a `@Singleton @Inject` class that wraps WorkManager, AlarmManager, the service intents, notifications, files, FCM and Analytics). A unit test replaces it and the repositories with fakes that record the order of calls, then asserts:
- the step order;
- that tokens are cleared last;
- that `AccountDeleted` is emitted only for `ACCOUNT_DELETED`;
- that a step which throws does not stop the steps after it.
