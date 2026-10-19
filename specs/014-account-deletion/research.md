# Research: Permanent Account Deletion (014)

**Date**: 2026-09-24. Every item below was checked against the actual code on branch `014-account-deletion`, not against the reference snapshot. The **Divergence** lines record where the code differs from what the plan input assumed.

Paths are relative to `app/src/main/java/com/safeguard/parentalcontrol/` unless they start with `app/` or `specs/`.

---

## R1. Family endpoints and linking

- `ApiService.getLinkedChildren()` is `GET family/children` and returns `Response<List<FamilyLink>>` (`data/remote/ApiService.kt:302`). Confirmed.
- `FamilyLink.childId` is `@SerializedName("child_id") val childId: Int` (`data/model/Models.kt:580`). `FamilyLink.id` is the link id. Confirmed.
- **Linking uses a pairing code, not an email.** The child calls `POST family/pairing-code` and shows the code; the parent redeems it with `POST family/link {pairing_code}` (`FamilyRepository.addChild`).
- The existing "Remove" button calls `familyRepository.removeChild(child.childId)`, which sends `DELETE family/link/{childId}`. It already passes the correct id. Relabelling it is copy-only.
- There is also `GET family/parents` (child side), which drives the cached `PreferencesManager.hasLinkedParent` flag. It is set to false only when the server confirms an empty list; network errors keep the cached value.

**Decision**: `DELETE family/child/{childId}` always takes `FamilyLink.childId`. Remove and Delete stay separate actions in `ChildCard`.

## R2. Google Sign-In and password detection

- Google Sign-In **ships in release**. `GoogleSignInManager` (Credential Manager) plus `POST oauth/google`, and `app/build.gradle:174` fails a release build whose `GOOGLE_WEB_CLIENT_ID` is still a placeholder.
- The `User` model already has `@SerializedName("auth_provider") val authProvider: AuthProvider = AuthProvider.LOCAL`, with values `local` and `google` (`Models.kt:80`, `:234`). No `has_password` or `google_id` field exists.
- **Risk**: the model defaults to `LOCAL`. If `GET auth/me` does not actually send `auth_provider`, every Google-only user would look like a password user and would get a 400 on every attempt. Whether the server sends it is unverified (backend question Q2).
- The comment on `oauth/google` says it will "Link Google account" to an existing local email account. A linked account may therefore have both a password and Google, and it is unknown which `auth_provider` value it reports.

**Decision**: the `{"confirmation":"DELETE"}` path is **in scope**.
- The delete screen reads `authProvider` from a fresh `GET auth/me` when it opens. `GOOGLE` pre-selects the "type DELETE" mode.
- A visible secondary link, "Signed in with Google? Type DELETE instead", switches modes manually, and a matching link switches back. This makes the flow correct even if the server omits the field or reports it wrongly.
- The mode only changes which body is sent. The server stays the only judge: a wrong mode returns 400, which is WrongPassword and shown inline.

**Alternatives rejected**:
- Trusting `authProvider` alone: this breaks Google users if the field is missing.
- Always showing both fields: this is confusing, and the server accepts only one.

## R3. AuthInterceptor 401 path

Traced in `data/remote/AuthInterceptor.kt`:

1. `auth/login`, `auth/register`, `auth/refresh`, `forgot`, `reset`, `oauth/google` and `/health` skip auth entirely. These endpoints never trigger a refresh or a revocation.
2. Any other request that gets a 401:
   - If another thread already refreshed the token, the request is retried.
   - Otherwise, if the refresh token has not expired, the interceptor calls `refreshTokenSync()`:
     - Refresh succeeds: the request is retried with the new token.
     - Refresh fails: `tokenManager.clearTokensDueToRevocation()` runs, which emits `AuthEvent.TokenRevoked`.
   - If the refresh token has expired: `clearTokensDueToExpiry()` runs, which emits `AuthEvent.SessionExpired`.
3. `MainActivity.SafeGuardApp` collects `authEvents`. It shows a hard-coded **English** `Toast` ("Your session was ended…" or "Session expired…") and calls `navigateToLogin`, which does `popUpTo(0)`.
4. `clearTokensDueTo*` clears **only the token store**. `PreferencesManager` keeps `isLoggedIn`, `isDeviceRegistered` and `monitoringConsentGranted`, so `shouldRunMonitoring` stays true and **protection keeps running**. This is the "today's behaviour" that spec FR-026 preserves.

**What happens if a background request fires after the account is deleted**:
- Every authenticated endpoint returns 401 for a deleted user, and the refresh also fails, so the interceptor emits `TokenRevoked` and the user sees the "session ended" toast. This is the exact misleading toast the design must avoid (V5).
- `authEvents` is a `MutableSharedFlow(extraBufferCapacity = 1)` with **no replay**. An event emitted while no Activity is collecting is lost.

**Decisions**:
- Teardown cancels all work and stops all services **before** clearing tokens (D4), so nothing in flight can race into a `TokenRevoked`.
- Add `AuthEvent.AccountDeleted` and `TokenManager.clearTokensDueToAccountDeletion()`.
- Navigation after success does not rely on the event alone. The delete screen navigates on its own success state, `MainActivity` navigates on the event, `launchSingleTop` makes the two idempotent, and a cold start goes Splash → Login because `isLoggedIn` is false.

**Divergence**: the toasts in `MainActivity` are hard-coded English strings. The new "account deleted" confirmation goes through string resources in English and Arabic. The existing toasts are left alone (logout refactor is out of scope).

## R4. Old 404 handling

`grep 404` finds only resource-specific meanings: "no screen-time rules" (`ScreenTimeRulesRepository:65`, `SyncWorker:230`) and "no content filter yet" (`DashboardViewModel` ×4). **No code treats 404 as "user gone" or "session over".** Nothing needs removing.

**Decision**: 404 means `ChildNotFound` **only** on `DELETE family/child/{id}`. Session over is 401 everywhere.

## R5. Inventory of everything alive per session

| Item | Where | Start / restart conditions | How teardown ends it |
|---|---|---|---|
| `MonitoringService` (FGS specialUse, `START_STICKY`) | `service/MonitoringService.kt` | Started by `MainActivity.onCreate`, `BootReceiver`, `BootServiceStartWorker`, `ServiceRestartReceiver`, and its own AlarmManager restart. `onStartCommand` stops itself when `!shouldRunMonitoring`. | `startService(getStopIntent())` sends `ACTION_STOP`, which cancels SyncWorker, removes the foreground notification and calls `stopSelf`. `onDestroy` sends **no** alert. |
| AlarmManager restart | `MonitoringService.scheduleServiceRestart()`: `PendingIntent.getService(requestCode=1, Intent(MonitoringService), FLAG_ONE_SHOT or FLAG_IMMUTABLE)`. Scheduled **only** from `onTaskRemoved` and `onTimeout`. | Swipe-away or FGS timeout. | Cancel with an equal PendingIntent (same component, requestCode 1, `FLAG_NO_CREATE or FLAG_IMMUTABLE`) passed to `alarmManager.cancel(pi)`. Even if an alarm is missed, the restarted service stops itself because `shouldRunMonitoring` is false. |
| WorkManager fallback restart | `BootServiceStartWorker`, untagged, enqueued from `scheduleServiceRestart`'s catch branch. Also tagged `boot_service_start` and `restart_after_update`. | | `WorkManager.cancelAllWork()`. The worker is a no-op anyway once `isLoggedIn` is false. |
| `ContentFilterVpnService` | `service/ContentFilterVpnService.kt` | Started when `isContentFilteringEnabled`. Its `onStartCommand` has the same `shouldRunMonitoring` gate. | Set `preferencesManager.isContentFilteringEnabled = false` **first**, then send the existing `ACTION_STOP`. This is exactly what `SettingsViewModel.disableContentFiltering()` does today. |
| `TextMonitoringAccessibilityService` | OS-bound; the app cannot unbind it. | Always bound while the user leaves it enabled in system settings. | Inert once prefs are cleared: `onAccessibilityEvent` returns early when `!monitoringConsentGranted` (`:269`). This is why FR-022a points the user to the app's system settings page. |
| `SyncWorker` | Unique periodic `sync_work`, unique one-off `sync_work_immediate`, tag `sync_work` | `SafeGuardApplication.startWorkers()` on **every process start**, plus MonitoringService, BootReceiver, the FCM service and ServiceRestartReceiver. | `cancelAllWork()`. Re-enqueued on the next cold start (see R6 risk). |
| `ProtectionMonitorWorker` | Unique periodic `protection_monitor_work` + `checkNow` one-off, tag `accessibility_monitor_work` | App start, boot, restart receiver | `cancelAllWork()`. `doWork` returns early when `!isDeviceRegistered`. |
| `ImageScanWorker` | Unique periodic `image_scan_work`, tag `image_scan_work` | MonitoringService (paired child) | `cancelAllWork()` |
| `PendingAlertWorker` | Unique work, tag `pending_alert_delivery` | MonitoringService.onCreate | `cancelAllWork()`. The Room queue is cleared too. |
| `PushTokenSyncWorker` | Unique work, tag `push_token_sync` | `MainActivity` when logged in | `cancelAllWork()`. `doWork` returns when `!isLoggedIn`. |
| `TamperAlertWorker` | One-off, tag `tamper_alert_work`, `CONNECTED` constraint | VPN `onDestroy`/`onRevoke`, MonitoringService `onTaskRemoved`, TamperDetectionReceiver | Kept from being enqueued by clearing the pref first (R6). `cancelAllWork()` runs again as the **last** step as a safety net. |
| `TamperDetectionReceiver` | Manifest receiver | Reads **plain** `getSharedPreferences(PREFS_NAME)` keys `is_parent` / `access_token`, which never exist because that file is encrypted, so it always skips. | Nothing to do (pre-existing defect, documented below). |
| `BootReceiver` | Manifest | `shouldRunMonitoring` | False after teardown. |
| `ServiceRestartReceiver` | Manifest (USER_PRESENT / SCREEN_ON) | Plain-prefs `is_logged_in` read, which is always false for the same reason, so it is effectively inert. | Nothing to do. |
| `ProtectionStatusHelper` state | Plain keys inside the `safeguard_prefs` file | | `PreferencesManager.clearAll()` clears the whole file, including the plain keys. |
| DevicePolicyManager / device admin | **None used.** | | n/a |
| Room DB `safeguard.db` | Only table: `PendingAlertEntity`, the alert queue, which holds device tokens | | `alertRepository.clearPendingAlerts()` (existing). |
| OkHttp cache | `cacheDir/http_cache`, a `@Singleton okhttp3.Cache` from `NetworkModule` | | Inject `Cache` (already a binding) and call `evictAll()`. |
| DataStore `word_lists` | `CustomWordRepository` | | `customWordRepository.clearCache()` (existing). |
| DataStore `alert_cooldown` | `AlertRepository` | | New `alertRepository.clearCooldowns()`, a 3-line addition. |
| In-memory caches | `ScreenTimeRulesRepository.clearCache()`, `ImageHasher.clearCache()`, `TextHasher.clearCache()` | | Call the existing methods. |
| `FlaggedTextStore` (raw flagged phrases) | Lives **inside** `PreferencesManager`'s encrypted file | | `preferencesManager.clearAll()` |
| Parent PIN, device id / token, consent, `hasLinkedParent`, FCM hash | `PreferencesManager` | | `clearAll()` |
| Tokens | `secure_token_prefs` (TokenManager) | | `clearTokensDueToAccountDeletion()`, **last** |
| Image review originals | `filesDir/image_backup/*.jpg` + `.meta` | | Delete the directory recursively (see R10). |
| ML models | `filesDir/models` | Not user data; the same for every user. | **Kept**, so a fresh sign-in does not re-download them. |
| `locale_prefs` | `LocaleHelper` | A device language preference, set before login. | **Kept**. A fresh install asks for it again, but it is not user data. |
| Notifications | FGS 1001 / 1002, lock 2001, alert 2000+n, screen-time 3000–5004, violation notifier | | Stop the services, then `NotificationManagerCompat.from(ctx).cancelAll()`. |
| FCM token | `FirebaseMessaging` (not stored; only its hash is in prefs) | | Best-effort `FirebaseMessaging.getInstance().deleteToken()`. The server-side registration died with the account. |
| Firebase Analytics | Only anonymous events, no `setUserId` anywhere | | Best-effort `FirebaseAnalytics.resetAnalyticsData()` (bound in `AppModule`), so the install gets a new app-instance id. |

## R6. Tamper paths when services stop on purpose

| Path | When it fires | Deliberate stop during teardown |
|---|---|---|
| VPN `onDestroy`, which sends `vpn_disconnected` | `isContentFilteringEnabled && !isParent && !revokedByForeignVpn` | **Suppressed** by setting `isContentFilteringEnabled = false` before `ACTION_STOP`. |
| VPN `onRevoke`, which sends `foreign_vpn` | Another VPN takes the slot | Not triggered by our own `ACTION_STOP`. |
| VPN `onTimeout` restart | FGS timeout | Not triggered. |
| MonitoringService `onTaskRemoved`, which sends `service_stopped` and a restart alarm | Only when the task is swiped | Not triggered by `ACTION_STOP`. If the user swipes during teardown, the alarm is cancelled in step 2 and the restart is refused by the gate. The alert itself **could** be enqueued, then cancelled by the final `cancelAllWork()`. |
| MonitoringService `onTimeout` restart | FGS timeout | Not triggered. |
| ProtectionMonitorWorker "protection disabled" alerts | Runs while registered | Cancelled. If already running, VPN is excluded (`checkVpn` reads the pref, which is now false). It returns early once `isDeviceRegistered` is false. |
| TamperDetectionReceiver | Data cleared / app disabled | Inert (see R5). |

**How logout avoids them today**: `SettingsViewModel.logout()` switches content filtering off first, then `AuthRepository.logout()` clears prefs. It does **not** stop `MonitoringService`; the service keeps running until something re-evaluates the gate. Logout stays unchanged (out of scope).

**Decision**: **no service or receiver needs a new flag check.** Every tamper and restart path is already driven by preferences the teardown controls, so step 1 of the plan input's D4 ("tamper and restart paths check a flag") is met by ordering: filtering pref off → cancel alarm and work → stop services → wipe → final `cancelAllWork()`. The persisted deletion marker (data-model §3) still exists; it is used for recovery, not read by services. This keeps the guardrail "Services/receivers: only the minimal flag check needed", with the minimum being zero. Quickstart V6 must verify it.

**Pre-existing risks found (documented, not fixed; the guardrails forbid worker and service changes)**:
1. `SafeGuardApplication.startWorkers()` enqueues `SyncWorker` and `ProtectionMonitorWorker` on **every** process start, even when signed out. `SyncWorker.doWork` has no login gate. If a child-role SyncWorker run reaches an authenticated endpoint with no tokens, the interceptor emits `SessionExpired` and a "Session expired" toast may appear on the login screen after a cold start. The same thing already happens after logout. Quickstart V5 checks for it, and if seen it is logged as a follow-up.
2. `TamperDetectionReceiver` and `ServiceRestartReceiver` read the encrypted prefs file with a plain `SharedPreferences`, so their gates always read defaults. The receiver never alerts and the restart receiver never restarts. The outcome is harmless here but it is a real defect for feature 004 / 010; it is recorded for a follow-up.

## R7. Success body shape

Unknown until the backend answers (Q1). Both endpoints may return `MessageResponse`, an empty 200, or a 204. `safeApiCall` turns a 2xx with a null body (always the case for a 204) into `Error("Empty response body", code = 2xx)`.

Retrofit 2.9's built-in converters handle `Response<Unit>`, but `body()` is still `null` on 204/205.

**Decision (D2)**: the ApiService methods return `Response<Unit>`. The repository wraps `safeApiCall` and remaps `Error` with `code in 200..299` to `Success(Unit)`. `safeApiCall` is unchanged, and success depends only on the status code.

## R8. Strings

- `res/values-ar/strings.xml` exists (751 strings; English has 766, and the gap is not ours).
- Convention: `stringResource(R.string.x)` in Composables, and `context.getString` through a `getString` helper in ViewModels (see `ChildrenViewModel.getString`).
- Exception: `MainActivity`'s hard-coded English toasts (R3).
- Existing keys `children_remove_title`, `children_remove_message` and `children_remove` are rewritten in both languages. New keys use the prefixes `delete_account_*` and `children_delete_*`.

## R9. Base URL and the public page URL

- `API_BASE_URL` is set per build type from `secrets.properties`: `API_BASE_URL_RELEASE` for release, `API_BASE_URL_DEBUG` for debug (`app/build.gradle:95`, `:100`).
- The release guard requires `https://` and no placeholder.

**Decision**: the URL is derived at runtime, with no new BuildConfig field. Take `BuildConfig.API_BASE_URL`, strip a trailing `/`, remove a trailing `/api/v1`, then append `/account-deletion`. For example, `https://bw.noor.net:8090/api/v1/` becomes `https://bw.noor.net:8090/account-deletion`. It opens with `ACTION_VIEW`, and `ActivityNotFoundException` shows a Snackbar containing the URL (spec US5-2).

Note: the page in a debug build points at the debug server, where it may not exist. That is acceptable; the Play Console URL is the release one.

## R10. ImageBlurManager originals

- Originals and `.meta` files live in **app-private** `filesDir/image_backup/` (`util/ImageBlurManager.kt:73`). The files in the gallery are blurred in place.
- `ImageBlurManager.deleteImage(backupId)` must **not** be used for teardown, because it also deletes the gallery image ("On reject: delete both").

**Decision**:
- Teardown deletes `File(context.filesDir, "image_backup")` recursively without calling `ImageBlurManager`, which stays unmodified. Gallery images stay blurred and can no longer be restored (spec edge case, FR-021).
- The pre-deletion warning checks `imageBlurManager.getPendingReviews().isNotEmpty()`, which is read-only.

---

## Resolved design decisions beyond the plan input

### RD-1. Parent re-authentication for child deletion (spec FR-009)

`DELETE family/child/{id}` takes **no body**, so it cannot carry the parent's credential. The spec forbids checking the password on the device only. The existing server checks are:

- **Password parents**: `POST auth/login {email: preferencesManager.userEmail, password}`.
  - 200 with the same `user.id` as `preferencesManager.userId`: verified. Save the rotated tokens through `TokenManager`, exactly as `AuthRepository.login` does.
  - 400 or 401: `WrongPassword`. `auth/login` is excluded from the interceptor, so it never causes a revocation.
  - 429: `RateLimited`.
  - Anything else: map the normal way.
- **Google parents**: typing `DELETE` cannot be checked by the server. Use a fresh Google account chooser instead: `GoogleSignInManager.signIn(context)` followed by `POST oauth/google {id_token}`. The returned `user.id` must equal the current `userId`; otherwise abort and treat it as `SessionOver`. This is real server-side re-authentication.
- **Spec impact**: FR-009's "(or types `DELETE` for a Google account)" is replaced, for **child deletion only**, by "confirms with a fresh Google sign-in". The spec is amended to match. Self-deletion keeps `DELETE`, which the server itself checks.

**Rejected alternatives**:
- `POST auth/change-password` with the same password: side effects (it may revoke sessions or be rejected as the same password).
- A local-only comparison: forbidden by the spec.

### RD-2. Remote invalidation on a child device (spec FR-025 to FR-027) → known limitation

The server returns 401 for a deleted user on **every** authenticated endpoint, and the refresh fails in the same way, so the client **cannot** tell "account deleted" apart from "session revoked or expired". The spec's fallback applies: the child device keeps today's behaviour (tokens cleared, login shown, protection keeps running).

FR-025 is therefore **not implemented** in this feature. It is recorded as a known limitation, and backend question Q6 asks for a distinguishable signal. Nothing that can be forged on the device is used.

### RD-3. Interrupted self-deletion (spec FR-016a) under an indistinguishable 401

A persisted marker `{startedAt, roleWasParent, hadLinkedParent}` is written with `commit()` before the request is sent. On the next launch, if the marker exists:

- `GET auth/me` returns 200: the account is alive, so clear the marker and continue.
- It returns 401: the session is gone. The interceptor has already cleared the tokens and emitted `TokenRevoked`.
  - If the marker says `roleWasParent || !hadLinkedParent`, run the **full teardown**, but show the standard neutral "session ended" copy, **never** "account deleted" (spec SC-002). Stopping protection here is safe:
    - A parent device has no protection.
    - An unlinked child's monitoring is already gated off by `hasLinkedParent == false`, and that flag becomes false only when the server confirms the child has no parent.
  - If the marker says `hadLinkedParent == true`: keep today's behaviour (protection keeps running) and clear the marker (FR-026, FR-027). A supervised child's deletion request can only ever get a 403, so this branch is essentially only reached by tampering.
- Network error: keep the marker, touch nothing, and retry on the next launch. The marker alone never stops protection.

### RD-4. Error display (constitution rule 10 versus the plan input's "banner")

The constitution requires UI errors to be shown as a Snackbar through `LaunchedEffect(uiState.error)`. The plan input asked for a banner.

**Decision**: a 400 is shown **inline** on the credential field (field validation state, not an error surface). 403, 429, network and unknown errors use a **Snackbar**. The network case uses `SnackbarDuration.Indefinite` with a "Retry" action. 403 and 429 use `SnackbarDuration.Long`, and their message also stays as supporting text under the button until the next attempt. The confirmation `AlertDialog` is allowed, because the rule forbids dialogs only for **errors**. No Complexity Tracking entry is needed.

### RD-5. App-scoped single flight

For FR-015 and FR-016, the request and the teardown must outlive the ViewModel. `viewModelScope` is cancelled on navigation away, and there is no application-scope binding in `di/`.

**Decision**: a new `@Singleton AccountDeletionCoordinator` owns `CoroutineScope(SupervisorJob() + Dispatchers.IO)`. It keeps at most one job per target in a `Mutex`-guarded map and exposes `StateFlow<DeletionState>`. ViewModels call it and observe it. No DI module changes are needed (`@Inject constructor`).
