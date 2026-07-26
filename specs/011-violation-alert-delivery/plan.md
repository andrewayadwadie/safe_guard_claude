# Implementation Plan: End-to-End Violation Alert Delivery

**Branch**: `011-violation-alert-delivery` | **Date**: 2026-07-25 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/011-violation-alert-delivery/spec.md`

## Summary

Close the three gaps between "a violation was detected on the child device" and "the parent is looking at it":

1. **Durable delivery** — a Room-backed, ordered, 100-entry pending-alert queue on the child device. `AlertRepository.createAlert()` enqueues on connectivity/server failure (never on suppression), and a `@HiltWorker` `PendingAlertWorker` with a `CONNECTED` constraint and exponential backoff drains it oldest-first, deleting each row only on confirmed acceptance.
2. **Alert identity enrichment** — `createAlert()` is the single funnel every alert helper already routes through, so child name / device name / device db id / `occurred_at` / app version are merged into `metadata` there once, with caller-supplied values winning.
3. **Parent-side push handling** — data-only pushes are handled by a new `@Singleton ViolationNotifier` (role guard, localized EN/AR type labels, locale-aware time, stable notification id per `alert_id` so re-delivery updates instead of stacking, deep-link `PendingIntent`). `SafeGuardFirebaseMessagingService` keeps zero business logic. Tapping routes into the **existing** Alerts route via a `@Singleton DeepLinkHolder` consumed once, surviving a login detour and falling back to the unfiltered Alerts screen when the target device is gone.

Plus the enabling precondition this feature actually depends on: **push-token registration, which does not exist in the codebase today.** `FirebaseMessaging.getInstance().token` has zero call sites; `DeviceSetupViewModel` calls `registerDevice(deviceName)` without a token; `SafeGuardFirebaseMessagingService.onNewToken()` calls `DeviceRepository.updateFcmToken()`, which returns `Error("Device not registered")` on every parent device because parents never `POST /devices`. Result: no parent token is ever stored server-side, so no violation push can ever be routed. This plan implements the registration contract the user specified (see [Push-Token Registration Directive](#push-token-registration-directive)).

## Technical Context

**Language/Version**: Kotlin (JDK 21 toolchain, `jvmTarget 17`), Jetpack Compose (BOM 2023.10.01, compiler ext 1.5.14)

**Primary Dependencies**: Hilt 2.51.1, Retrofit 2.9.0 / OkHttp 4.12.0, **Room 2.6.1 (declared in `app/build.gradle` with `ksp` — currently zero `@Entity`/`@Dao` in the source tree; this feature is its first use)**, WorkManager 2.9.0 + hilt-work 1.1.0, Firebase BOM 32.7.0 (messaging), Coroutines 1.7.3, DataStore 1.0.0, Timber 5.0.1. **No new dependencies.**

**Storage**:
- Pending alert queue → **Room** (`safeguard.db`, table `pending_alerts`), app-private
- Child display name, banner-dismissed flag → `PreferencesManager` (EncryptedSharedPreferences, `safeguard_prefs`)
- Cooldown/dedup/daily-cap counters → existing `alert_cooldown` DataStore (untouched)
- Tokens → `TokenManager` only (untouched)

**Testing**: JUnit unit tests (queue cap/ordering, suppression-vs-failure classification, enrichment precedence, violation-type label fallback, today-vs-earlier time format); manual on-device validation per [quickstart.md](quickstart.md)

**Target Platform**: Android `minSdk 26`, `targetSdk 34`, `compileSdk 34` (per `app/build.gradle`; `versionName 1.1.4`). Both roles — child device produces, parent device consumes.

**Project Type**: Native Android mobile app, single `app/` module, package-by-feature MVVM

**Performance Goals**: SC-002 — queued alerts reach the parent within 2 min of connectivity return (WorkManager `CONNECTED` wake-up + 10 s initial backoff comfortably inside). SC-008 — detection→notification p95 < 30 s on a healthy network (one extra `metadata` merge on the existing `POST /alerts` call; no added round-trip). Queue writes are single-row Room inserts off the main thread.

**Constraints**: No backend code in this repo — backend contract changes are coordination items, not tasks. No new Android permissions (`POST_NOTIFICATIONS` already in the manifest). No new navigation destinations. No change to detection, suppression, alert titles/messages, or severity mapping (FR-005, SC-009). Push stays an accelerant, never a control path (Principle III).

**Scale/Scope**: 1 new Room DB (1 entity, 1 DAO), 2 new workers, 2 new `@Singleton` helpers, 1 new API endpoint binding, ~14 modified files, ~20 new string resources × 2 locales.

## Push-Token Registration Directive

Supplied by the user with this planning request; treated as binding contract, and reconciled with spec Backend Coordination item 7.

**Parent app**
1. Login → get FCM token from Firebase → `PUT /auth/me/fcm-token` with `{"fcm_token": "..."}`
2. Repeat on every Firebase `onNewToken`
3. On logout send `{"fcm_token": null}` to clear it

**Child app**
1. Send `fcm_token` in `POST /devices` at registration
2. Keep it current via `PUT /devices/{id}` on `onNewToken`

**Server-side (no client work)**
- Child device reports an alert → backend looks up linked parents → pushes to their stored tokens → parent phone gets the notification
- Parent changes a rule/filter → backend pushes a silent `sync` command to the child device → child re-fetches rules instantly

Two client-side notes this creates, resolved in [research.md](research.md):

- **R5** — spec FR-029 also requires registration **on every app startup with an authenticated parent session**, so parents already signed in before this ships become reachable without re-login. The directive's step 1 covers only fresh logins; the plan implements both.
- **R6** — the logout clear (`{"fcm_token": null}`) is an authenticated call, so it MUST be issued **before** `tokenManager.clearTokens()`, not deferred to a worker. Ordering is explicit in `AuthRepository.logout()`.

The existing child `sync`-command path (`SafeGuardFirebaseMessagingService.handleCommandMessage` → `SyncWorker.enqueueImmediate`) already implements the server-side bullet 2 and needs no change.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-checked after Phase 1 design — see [Post-Design Re-Check](#post-design-constitution-re-check).*

| # | Rule | Status | Notes |
|---|------|--------|-------|
| 1 | Feature sub-package with `[Feature]ViewModel`/`UiState`/`Screen` | ⚠️ Justified | No new screen — FR-028 forbids new destinations. Changes land in the existing `presentation/alerts/` and `presentation/dashboard/` sub-packages. See Complexity Tracking. |
| 2 | `@HiltViewModel` only, no manual instantiation | ✅ | Reuses `AlertsViewModel`, `DashboardViewModel`, `AuthViewModel`, `DeviceSetupViewModel`. |
| 3 | Repositories `@Singleton` | ✅ | `AlertRepository`, `AuthRepository`, `DeviceRepository` already `@Singleton`. New `PendingAlertStore`, `ViolationNotifier`, `DeepLinkHolder` are `@Singleton` too. |
| 4 | ViewModel → Repository → `safeApiCall{}` → `ApiService` | ✅ | New `PUT auth/me/fcm-token` goes `AuthViewModel`/worker → `AuthRepository` → `safeApiCall{}` → `ApiService`. No layer skipped. |
| 5 | Tokens only in `TokenManager` | ✅ | FCM registration tokens are **not** auth tokens and are never persisted locally; auth tokens stay in `TokenManager`. No ViewModel/Screen touches them. |
| 6 | Special permissions via settings routing | ✅ | `POST_NOTIFICATIONS` is an ordinary runtime permission (not one of the four special ones) → `ActivityResultContracts.RequestPermission()` is correct, matching `PermissionsSetupScreen`'s existing usage. |
| 7 | Services contain zero business logic | ✅ | `SafeGuardFirebaseMessagingService` reduces to routing: `violationNotifier.handleViolationPush(data)` / `pushTokenSyncWorker` enqueue. `MonitoringService` gains one `PendingAlertWorker.enqueueImmediate(context)` line. |
| 8 | Workers `@HiltWorker` + `CoroutineWorker` + `@AssistedInject` | ✅ | `PendingAlertWorker` and `PushTokenSyncWorker` both follow the `SyncWorker` template. |
| 9 | `NetworkResult<T>` from repository functions | ✅ | `AlertRepository.createAlert()` keeps its `NetworkResult<Alert>` signature (FR-013 — callers observe no change); new `AuthRepository.publishFcmToken()` returns `NetworkResult<MessageResponse>`. |
| 10 | Errors via `Snackbar` + `clearError()` | ✅ | Alerts/Dashboard already have the `LaunchedEffect(uiState.error)` + Snackbar plumbing. The notifications-disabled banner is an informational inline banner, not an error surface. |
| 11 | ProGuard updated for new Service/Receiver/Worker | ✅ | Explicit keeps added for both new workers and `data.local.**` (Room). |
| 12 | `noCompress "tflite"` intact | ✅ | Build file untouched apart from nothing — Room is already declared. |

**Principle I (privacy)**: no new data category. The queue stores the *same* `AlertCreate` payload that is already transmitted (category/severity/title/message/metadata) — never monitored text, never image bytes. Existing sanitization (`sanitizeReason`, hash-only dedup) is unchanged. Queue is app-private, capped at 100, cleared on sign-out. Data-flow note present in spec.

**Principle III (two-role)**: enforcement untouched; the queue makes the child *more* independent of connectivity, not less. Push remains an accelerant — every queued alert still lands in the backend store and is visible when the parent opens the app, push or no push.

**Principle IV**: no new permission in the manifest; `POST_NOTIFICATIONS` maps to the named feature "violation alerts on parent devices". Both new workers use `CONNECTED` constraints and unique work.

**Principle V**: FCM tokens are transported over the existing pinned TLS `ApiService`; `AuthInterceptor` supplies the bearer. No token is logged (`Timber.d("FCM token refreshed")` stays value-free).

**Gate result: PASS** (one justified deviation, tracked below).

## Project Structure

### Documentation (this feature)

```text
specs/011-violation-alert-delivery/
├── plan.md              # This file
├── spec.md              # Feature spec (clarified 2026-07-25)
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/
│   ├── backend-contract.md     # Phase 1 output — REST + FCM payloads (cross-team)
│   └── internal-contracts.md   # Phase 1 output — component contracts inside the app
├── checklists/
│   └── requirements.md
└── tasks.md             # Phase 2 output (/speckit-tasks — NOT created by /speckit-plan)
```

### Source Code (repository root)

```text
app/src/main/java/com/safeguard/parentalcontrol/
├── data/
│   ├── local/                            # NEW package (first Room usage in the repo)
│   │   ├── SafeGuardDatabase.kt          # NEW: @Database(entities=[PendingAlertEntity], version=1)
│   │   ├── PendingAlertEntity.kt         # NEW: queued AlertCreate + attempt bookkeeping
│   │   ├── PendingAlertDao.kt            # NEW: insert / oldestFirst / delete / count / trimToCap / clear
│   │   └── PendingAlertStore.kt          # NEW: @Singleton DAO wrapper — cap enforcement, JSON (de)serialize
│   ├── model/
│   │   └── Models.kt                     # MODIFY: + FcmTokenUpdateRequest(fcm_token: String?)
│   ├── remote/
│   │   └── ApiService.kt                 # MODIFY: + @PUT("auth/me/fcm-token") updateMyFcmToken(...)
│   └── repository/
│       ├── AlertRepository.kt            # MODIFY: enrichment merge + enqueue-on-failure + flushPending()
│       ├── AuthRepository.kt             # MODIFY: publishFcmToken()/clearFcmToken(); capture fullName; clear-before-logout ordering
│       └── DeviceRepository.kt           # (unchanged API; updateFcmToken() reused for the child path)
├── di/
│   └── DatabaseModule.kt                 # NEW: provides SafeGuardDatabase + PendingAlertDao
├── presentation/
│   ├── MainActivity.kt                   # MODIFY: onNewIntent + deep-link extras → DeepLinkHolder; startup token sync
│   ├── navigation/
│   │   └── NavGraph.kt                   # MODIFY: consume DeepLinkHolder; resume deep link after login (FR-026)
│   ├── alerts/
│   │   ├── AlertsViewModel.kt            # MODIFY: refresh-on-resume, unlinked-device fallback (FR-025/FR-027)
│   │   └── AlertsScreen.kt               # MODIFY: lifecycle-resume hook
│   └── dashboard/
│       ├── DashboardViewModel.kt         # MODIFY: notifications-enabled state + dismiss (FR-032)
│       └── DashboardScreen.kt            # MODIFY: POST_NOTIFICATIONS request + dismissible banner (FR-031/FR-032)
├── service/
│   ├── SafeGuardFirebaseMessagingService.kt  # MODIFY: role-aware onNewToken; delegate alerts to ViolationNotifier
│   └── MonitoringService.kt              # MODIFY: enqueue PendingAlertWorker at service start (FR-012)
├── util/
│   ├── ViolationNotifier.kt              # NEW: @Singleton role guard + localized build + stable-id notify
│   ├── DeepLinkHolder.kt                 # NEW: @Singleton one-shot pending-navigation holder
│   ├── PreferencesManager.kt             # MODIFY: userFullName; notificationBannerDismissed
│   └── Constants.kt                      # MODIFY: new pref keys, deep-link intent extras
└── worker/
    ├── PendingAlertWorker.kt             # NEW: @HiltWorker drain of the pending queue
    └── PushTokenSyncWorker.kt            # NEW: @HiltWorker fetch-and-publish FCM token by role

app/src/main/res/
├── values/strings.xml                    # MODIFY: violation_type_*, notif banner, generic fallback
└── values-ar/strings.xml                 # MODIFY: Arabic counterparts

app/proguard-rules.pro                    # MODIFY: keeps for both new workers + data.local.**
```

**Structure Decision**: Existing single-module, package-by-feature MVVM layout is kept exactly. The only new package is `data/local/`, which is where Room belongs under the constitution's Data-layer rule; every other addition slots into an existing package (`util/`, `worker/`, `di/`). No new `presentation/<feature>/` sub-package is created, because FR-028 explicitly forbids new navigation destinations.

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| Enforcement rule 1 — no new `presentation/<feature>/` sub-package | The feature adds no screen. FR-023/FR-028 require reusing the existing `Screen.Alerts` route; the parent-reachability banner is a row inside the existing Dashboard. | Creating an `presentation/violationalerts/` package with a ViewModel/Screen would introduce a destination the spec forbids and duplicate `AlertsViewModel`. |
| First Room database in a codebase that stores everything else in DataStore/EncryptedSharedPreferences | The queue has concurrent producers (`TextMonitoringAccessibilityService`, `MediaFileObserver`, `ImageScanWorker`, `ContentFilterVpnService`) and a concurrent consumer (`PendingAlertWorker`). It needs ordered reads, per-row atomic delete-on-success, and a hard cap. | A DataStore JSON blob rewrites the whole list per alert under read-modify-write races → lost alerts, which is precisely the bug this feature exists to fix. Room 2.6.1 + ksp is already declared in `app/build.gradle`, so this adds no dependency. See research.md R1. |

## Phase 0 — Research

**Output**: [research.md](research.md) — 8 decisions (queue storage, retry mechanism, enrichment injection point, child-name capture, push-token registration & logout ordering, deep-link one-shot + login resume, notification dedup identity, localization/time formatting). No `NEEDS CLARIFICATION` markers remain in Technical Context.

## Phase 1 — Design & Contracts

**Outputs**:
- [data-model.md](data-model.md) — `PendingAlertEntity` schema, enriched `AlertCreate.metadata` shape, `ViolationPush` parsed shape, `PendingDeepLink`, state transitions for a queued alert.
- [contracts/backend-contract.md](contracts/backend-contract.md) — the cross-team REST + FCM contract, including the two items still **open** with the backend developer.
- [contracts/internal-contracts.md](contracts/internal-contracts.md) — in-app component contracts (store, workers, notifier, deep-link holder) with their invariants.
- [quickstart.md](quickstart.md) — runnable validation for all five user stories.
- Agent context updated: `CLAUDE.md` SPECKIT block now points at this plan.

## Post-Design Constitution Re-Check

Re-evaluated after the Phase 1 artifacts were written. No new violations introduced by the design:

- Room lives in `data/local/` behind `PendingAlertStore` (`@Singleton`); `AlertRepository` remains the only caller — Data-layer rule holds, no DAO reaches a Service or Receiver.
- Both new workers are `@HiltWorker` + `CoroutineWorker` + `@AssistedInject`, enqueued as unique work with `CONNECTED` constraints (rule 8, Principle IV).
- `SafeGuardFirebaseMessagingService` ends up with *less* logic than today — severity mapping and notification building move into `ViolationNotifier` (rule 7).
- The new endpoint keeps the mandated call chain; `NetworkResult<T>` is preserved everywhere (rules 4, 9).
- ProGuard keeps added for the two new workers and `data.local.**` (rule 11); `noCompress "tflite"` untouched (rule 12).
- Privacy unchanged: the queue persists only the already-transmitted alert payload, capped and sign-out-cleared (Principle I).

**Gate result: PASS** — same single justified deviation as pre-design.

## Open Coordination Items (non-blocking for `/speckit-tasks`)

Carried from spec Backend Coordination; both affect only key naming, and the client aligns to whatever is finalized:

- **Item 5 (open)** — idempotency: does the backend want a client-generated dedup id on `POST /alerts`? A retried queued alert may be double-accepted after an ambiguous timeout. `PendingAlertEntity` reserves a `clientAlertUuid` column so adding the header/field later is a one-line change.
- **Item 6 (open)** — final payload key names for `child_name` / `device_name` / `device_db_id` / `occurred_at` / `app_version`.

Confirm both with the backend developer before implementing the enrichment tasks.
