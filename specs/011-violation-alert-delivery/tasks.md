---

description: "Task list for End-to-End Violation Alert Delivery"
---

# Tasks: End-to-End Violation Alert Delivery

**Input**: Design documents from `/specs/011-violation-alert-delivery/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md), [contracts/](contracts/), [quickstart.md](quickstart.md)

**Tests**: TDD was not requested. Unit tests are included only where [plan.md](plan.md) §Technical Context and the constitution's testing priorities name them explicitly (queue cap/ordering, suppression-vs-failure classification, enrichment precedence, label fallback, time formatting, role guard). They are written alongside, not before, implementation.

**Organization**: Tasks are grouped by user story so each story can be implemented, tested, and shipped independently.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependency on an incomplete task)
- **[Story]**: US1–US5, mapping to the user stories in [spec.md](spec.md)
- All paths are repo-relative. Package root is `app/src/main/java/com/safeguard/parentalcontrol/`.

## ⚠️ Read before sequencing

Two facts from [research.md](research.md) change the naive priority order:

1. **US5 is a hidden precondition, not a nice-to-have.** `FirebaseMessaging.getInstance().token` has **zero call sites** in the codebase today, so no parent push token ever reaches the backend and no violation push can be routed. The spec ranks US5 as P3 on the assumption that "the common path already works" — it does not. US2, US3, and US4 are implementable in isolation but **cannot be observed end-to-end** until US5 ships. Recommended delivery order is **US1 → US5 → US2 → US3 → US4** (see [Implementation Strategy](#implementation-strategy)).
2. **Backend Coordination items 5 and 6 are still open** ([contracts/backend-contract.md](contracts/backend-contract.md)). T019 gates the US2 enrichment work on confirming them.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Constants, ProGuard, and the shared preference fields that more than one story writes to. Grouped here so two stories never edit the same file concurrently.

- [X] T001 Add feature constants in `app/src/main/java/com/safeguard/parentalcontrol/util/Constants.kt`: pref keys `KEY_USER_FULL_NAME`, `KEY_NOTIFICATION_BANNER_DISMISSED`; deep-link intent extras `EXTRA_NAV_TARGET`, `EXTRA_DEVICE_ID`, `EXTRA_DEVICE_NAME` and the `NAV_TARGET_ALERTS` value; unique work names `WORK_PENDING_ALERT_DELIVERY`, `WORK_PUSH_TOKEN_SYNC`
- [X] T002 Add `userFullName: String?` (FR-004, used by US2) and `notificationBannerDismissed: Boolean` (FR-032, used by US5) to `app/src/main/java/com/safeguard/parentalcontrol/util/PreferencesManager.kt`, both cleared by the existing `clearAll()`
- [X] T003 [P] Add ProGuard keeps for `com.safeguard.parentalcontrol.worker.PendingAlertWorker`, `com.safeguard.parentalcontrol.worker.PushTokenSyncWorker`, and `com.safeguard.parentalcontrol.data.local.**` in `app/proguard-rules.pro` (Constitution enforcement rule 11)

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Give parent-side push handling a business-logic home before three stories start writing into it.

**⚠️ CRITICAL**: US2, US3, and US4 all extend `ViolationNotifier`. It must exist first or those phases collide in `SafeGuardFirebaseMessagingService.kt`. US1 is unaffected and may start in parallel.

- [X] T004 Create `app/src/main/java/com/safeguard/parentalcontrol/util/ViolationNotifier.kt` as a `@Singleton` with `fun handleViolationPush(data: Map<String, String>)`, moving the existing severity→priority mapping and `NotificationCompat` building **verbatim** out of `SafeGuardFirebaseMessagingService` — behaviour must be byte-identical to the current build at this checkpoint (SC-009)
- [X] T005 Refactor `app/src/main/java/com/safeguard/parentalcontrol/service/SafeGuardFirebaseMessagingService.kt` so `handleAlertMessage()` is a single delegation to the injected `violationNotifier`, removing the alert-notification building and the alert branch of `getNextNotificationId()` from the service (Constitution enforcement rule 7)

**Checkpoint**: Push handling behaves exactly as before, but now lives behind one testable `@Singleton`.

---

## Phase 3: User Story 1 - No violation is ever silently lost (Priority: P1) 🎯 MVP

**Goal**: A violation detected without usable network is held on the child device and delivered automatically once connectivity returns — never discarded, never duplicated into the suppression paths.

**Independent Test**: Child device in airplane mode → trigger a text violation → confirm it is held locally. Restore network → confirm it appears in the parent's alert list with no further interaction. Fully testable on the child device alone; needs no parent-side work.

### Implementation for User Story 1

- [X] T006 [P] [US1] Create `app/src/main/java/com/safeguard/parentalcontrol/data/local/PendingAlertEntity.kt` — `@Entity(tableName = "pending_alerts")` with `id`, `clientAlertUuid`, `payloadJson`, `deviceToken`, `createdAt` (indexed), `attemptCount`, `lastAttemptAt`, per [data-model.md §1](data-model.md)
- [X] T007 [P] [US1] Create `app/src/main/java/com/safeguard/parentalcontrol/data/local/PendingAlertDao.kt` — `insert`, `oldestFirst(limit)` ordered `created_at ASC`, `deleteById`, `count`, `deleteOldest(n)`, `clear`, `recordAttempt`
- [X] T008 [US1] Create `app/src/main/java/com/safeguard/parentalcontrol/data/local/SafeGuardDatabase.kt` — `@Database(entities = [PendingAlertEntity::class], version = 1, exportSchema = false)` named `safeguard.db` (depends on T006, T007)
- [X] T009 [US1] Create `app/src/main/java/com/safeguard/parentalcontrol/di/DatabaseModule.kt` — `@Module @InstallIn(SingletonComponent::class)` providing `SafeGuardDatabase` and `PendingAlertDao` as `@Singleton`
- [X] T010 [US1] Create `app/src/main/java/com/safeguard/parentalcontrol/data/local/PendingAlertStore.kt` — `@Singleton` DAO wrapper on `Dispatchers.IO` implementing the contract in [contracts/internal-contracts.md](contracts/internal-contracts.md); `enqueue()` trims to the **100-row cap by deleting the oldest rows inside the same transaction as the insert** (FR-010)
- [X] T011 [US1] In `app/src/main/java/com/safeguard/parentalcontrol/data/repository/AlertRepository.kt`, add the retryable-vs-suppression classifier from [data-model.md §1](data-model.md): enqueue on network/IO failure and HTTP 5xx only; never on `"Device not registered"`, dedup, cooldown, daily cap, or any other 4xx (FR-006, FR-007)
- [X] T012 [US1] In the same file, enqueue via `PendingAlertStore` on a retryable failure and schedule `PendingAlertWorker`, while returning the **unchanged** `NetworkResult<Alert>` the caller saw before this feature (FR-013)
- [X] T013 [US1] Add `suspend fun flushPendingAlerts(): FlushOutcome` to `AlertRepository.kt` — deliver oldest-first, delete a row **only** after `NetworkResult.Success`, submit with the row's stored `X-Device-Token` (FR-008)
- [X] T014 [US1] Add `suspend fun clearPendingAlerts()` to `AlertRepository.kt` delegating to `PendingAlertStore.clear()`
- [X] T015 [US1] Create `app/src/main/java/com/safeguard/parentalcontrol/worker/PendingAlertWorker.kt` — `@HiltWorker` + `CoroutineWorker` + `@AssistedInject`, unique work `KEEP`, `NetworkType.CONNECTED`, `BackoffPolicy.EXPONENTIAL` 10 s, `Result.retry()` while entries remain (FR-008, FR-009; Constitution rule 8)
- [X] T016 [US1] Enqueue `PendingAlertWorker.enqueueImmediate(this)` from `onCreate()` in `app/src/main/java/com/safeguard/parentalcontrol/service/MonitoringService.kt` — one delegation line, no logic in the service (FR-012, Constitution rule 7)
- [X] T017 [US1] In `app/src/main/java/com/safeguard/parentalcontrol/data/repository/AuthRepository.kt`, call `alertRepository.clearPendingAlerts()` in the `logout()` teardown so held alerts never ship under a different account (FR-011)
- [X] T018 [P] [US1] Add unit tests in `app/src/test/java/com/safeguard/parentalcontrol/data/local/PendingAlertStoreTest.kt` (cap-100 oldest-out eviction, oldest-first ordering) and `app/src/test/java/com/safeguard/parentalcontrol/data/repository/AlertRetryClassifierTest.kt` (each suppression string and HTTP class maps to the right enqueue/discard decision)

**Checkpoint**: US1 is fully functional and independently shippable. Run quickstart Story 1 (1.1–1.9), including the 50-violation offline run for SC-001.

---

## Phase 4: User Story 2 - The parent understands the alert without opening the app (Priority: P1)

**Goal**: Every alert carries child name, device name, device record id, occurrence time, and app version; the resulting notification names the child, device, localized violation type, and time in EN and AR, and a repeat push updates rather than stacks.

**Independent Test**: Background the parent app, cause a violation on a child device, confirm the notification reads `{child name} — {device name} · {violation type} · {time}` in both English and Arabic. Requires US5 to be in place for the push to be routed at all.

### Implementation for User Story 2

- [X] T019 [US2] **Gate**: confirm Backend Coordination items 5 (idempotency / client-generated dedup id) and 6 (final `metadata` key names) with the backend developer; record the outcome in [contracts/backend-contract.md](contracts/backend-contract.md) before any task below is implemented
- [X] T020 [US2] Capture the child display name — pass `fullName` through `PreferencesManager.saveUserInfo(...)` in `app/src/main/java/com/safeguard/parentalcontrol/util/PreferencesManager.kt` and supply `response.user.fullName` from `login()`, `register()`, and `googleSignIn()` in `app/src/main/java/com/safeguard/parentalcontrol/data/repository/AuthRepository.kt` (FR-004)
- [X] T021 [US2] Merge the five enrichment keys into `metadata` inside `createAlert()` in `app/src/main/java/com/safeguard/parentalcontrol/data/repository/AlertRepository.kt` — enrichment first, caller map last so the caller's value wins; `occurred_at` = `System.currentTimeMillis()` epoch millis; `app_version` = `BuildConfig.VERSION_NAME` (FR-001, FR-002, FR-003)
- [X] T022 [US2] Verify no alert helper's title, message, or severity mapping changed as a side effect of T021 in the same file (FR-005, SC-009)
- [X] T023 [P] [US2] Add `violation_type_inappropriate_text`, `_inappropriate_image`, `_content_block`, `_screen_time_limit`, `_app_blocked`, `_device_admin_disabled`, `violation_type_unknown`, and the notification-body separator format string to `app/src/main/res/values/strings.xml` (FR-016, FR-017)
- [X] T024 [P] [US2] Add the Arabic counterparts of every string from T023 to `app/src/main/res/values-ar/strings.xml` (SC-007)
- [X] T025 [US2] In `app/src/main/java/com/safeguard/parentalcontrol/util/ViolationNotifier.kt`, parse the push into the `ViolationPush` shape and resolve the localized type label via `LocaleHelper.localizedContext(context)`, with `violation_type_unknown` as the `else` branch — a raw internal type string must never surface (FR-016, FR-017)
- [X] T026 [US2] In the same file, format `occurred_at` with `DateUtils.isToday()` → short time, otherwise short date + short time, both locale-aware; omit the time segment entirely when the value is missing or malformed (FR-018, Edge Cases)
- [X] T027 [US2] In the same file, compose the title/body from the identity segments (dropping any missing segment **and** its separator), apply `NotificationCompat.BigTextStyle` (FR-019), keep the existing severity→priority mapping unchanged (FR-021), and post with notification id `alert_id.hashCode()` so re-delivery updates in place instead of stacking (FR-020, SC-006)
- [X] T028 [P] [US2] Add unit tests in `app/src/test/java/com/safeguard/parentalcontrol/data/repository/AlertEnrichmentTest.kt` (caller value wins over enrichment; all five keys present for every alert type) and `app/src/test/java/com/safeguard/parentalcontrol/util/ViolationNotifierFormatTest.kt` (unknown type → generic label; today vs earlier-day formatting; missing timestamp → segment omitted)

**Checkpoint**: quickstart Story 2 (2.1–2.10) passes, EN and AR.

---

## Phase 5: User Story 3 - Tapping the notification lands on the alert (Priority: P2)

**Goal**: A tap opens the Alerts screen for the originating device from both cold start and a running app, exactly once, surviving a login detour, refreshing on resume, and falling back gracefully when the target device is gone — all without a new navigation destination.

**Independent Test**: Tap a violation notification from a cold start and from a backgrounded app; both land on the Alerts screen showing the alert. Rotate — no re-navigation.

### Implementation for User Story 3

- [X] T029 [P] [US3] Create `app/src/main/java/com/safeguard/parentalcontrol/util/DeepLinkHolder.kt` — `@Singleton` with `pending: StateFlow<PendingDeepLink?>`, `post()`, `consume()`, and the `PendingDeepLink(deviceId: Int?, deviceName: String?)` type ([data-model.md §4](data-model.md))
- [X] T030 [US3] In `app/src/main/java/com/safeguard/parentalcontrol/util/ViolationNotifier.kt`, attach a `PendingIntent` to `MainActivity` carrying `EXTRA_NAV_TARGET`/`EXTRA_DEVICE_ID`/`EXTRA_DEVICE_NAME`, using `FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_CLEAR_TOP` — **replacing the current `FLAG_ACTIVITY_CLEAR_TASK`**, which restarts a running app and violates FR-023
- [X] T031 [US3] In `app/src/main/java/com/safeguard/parentalcontrol/presentation/MainActivity.kt`, read the deep-link extras in `onCreate()` and add `override fun onNewIntent(intent: Intent)` that calls `setIntent(intent)` then posts to `DeepLinkHolder` — required because the manifest already declares `android:launchMode="singleTop"` (FR-023)
- [X] T032 [US3] In `app/src/main/java/com/safeguard/parentalcontrol/presentation/navigation/NavGraph.kt`, collect `DeepLinkHolder.pending` and navigate to the existing `Screen.Alerts.createRoute(deviceId, deviceName)`, calling `consume()` in the same effect so rotation cannot re-navigate (FR-024, FR-028)
- [X] T033 [US3] In the same file, check `DeepLinkHolder` inside the Login screen's `onLoginSuccess` callback and continue to Alerts instead of Dashboard when a deep link is pending (FR-026)
- [X] T034 [US3] In `app/src/main/java/com/safeguard/parentalcontrol/presentation/alerts/AlertsViewModel.kt`, validate a deep-linked `deviceId` against the parent's device list and reset `deviceId`/`deviceName` to `null` when the device is unlinked or absent, loading the unfiltered list instead of a broken or empty target (FR-027)
- [X] T035 [US3] Add `fun onResume()` to `AlertsViewModel.kt` that re-runs `loadAlerts()` so the list is current after a notification tap and on every return to the screen (FR-022, FR-025)
- [X] T036 [US3] Hook `onResume()` from a lifecycle-resume effect in `app/src/main/java/com/safeguard/parentalcontrol/presentation/alerts/AlertsScreen.kt`, keeping the existing `collectAsStateWithLifecycle()` and Snackbar error plumbing untouched (Constitution rules 2 and 10)

**Checkpoint**: quickstart Story 3 (3.1–3.9) passes, including the expired-session and unlinked-device cases.

---

## Phase 6: User Story 4 - Only parents are notified (Priority: P2)

**Goal**: A violation push that reaches a child device is silently discarded — no notification, no sound.

**Independent Test**: Send the same violation push to a signed-in child device; confirm no notification appears and no sound plays, with only a log entry.

### Implementation for User Story 4

- [X] T037 [US4] Make the role guard the **first statement** of `handleViolationPush()` in `app/src/main/java/com/safeguard/parentalcontrol/util/ViolationNotifier.kt`: when `preferencesManager.isParent` is false, `Timber.d(...)` and return before any parsing, notification building, or work enqueue (FR-014)
- [X] T038 [US4] Confirm the guard also covers the foreground path in `app/src/main/java/com/safeguard/parentalcontrol/service/SafeGuardFirebaseMessagingService.kt` — the `message.notification?.let { showNotification(...) }` branch must not display a violation on a child device even if a non-data push arrives
- [X] T039 [P] [US4] Add a unit test in `app/src/test/java/com/safeguard/parentalcontrol/util/ViolationNotifierRoleGuardTest.kt` asserting zero `NotificationManager` interactions for a child role and normal display for a parent role (SC-005)

**Checkpoint**: quickstart Story 4 (4.1–4.2) passes.

---

## Phase 7: User Story 5 - The parent device is actually reachable (Priority: P3 — but see the sequencing warning above)

**Goal**: Parent devices publish their FCM token at sign-in, on every startup with an authenticated session, and on token rotation; clear it at sign-out; child devices carry their token at registration and keep it current. Notification permission is requested, and a non-blocking banner explains the consequence when notifications are off.

**Independent Test**: Sign in fresh on a parent device with notifications denied; confirm a non-blocking prompt appears and, after granting, violation notifications arrive. Verify server-side that the token is stored against the account.

### Implementation for User Story 5

- [X] T040 [P] [US5] Add `FcmTokenUpdateRequest(@SerializedName("fcm_token") val fcmToken: String?)` to `app/src/main/java/com/safeguard/parentalcontrol/data/model/Models.kt`
- [X] T041 [US5] Add `@PUT("auth/me/fcm-token") suspend fun updateMyFcmToken(@Body request: FcmTokenUpdateRequest): Response<MessageResponse>` to `app/src/main/java/com/safeguard/parentalcontrol/data/remote/ApiService.kt` ([contracts/backend-contract.md §1](contracts/backend-contract.md))
- [X] T042 [US5] Add `publishFcmToken(token: String)` and `clearFcmToken()` to `app/src/main/java/com/safeguard/parentalcontrol/data/repository/AuthRepository.kt`, both returning `NetworkResult<MessageResponse>` through `safeApiCall{}` (Constitution rules 4 and 9); never log the token value
- [X] T043 [US5] **Order the logout teardown** in `AuthRepository.logout()`: `clearFcmToken()` → `apiService.logout()` → `tokenManager.clearTokens()` → `preferencesManager.clearAll()` → `clearPendingAlerts()`. The clear is an authenticated call; running it after the tokens are cleared returns 401 and strands a live token on a signed-out device. Both network steps stay best-effort so logout never blocks (FR-030)
- [X] T044 [US5] Create `app/src/main/java/com/safeguard/parentalcontrol/worker/PushTokenSyncWorker.kt` — `@HiltWorker` + `CoroutineWorker` + `@AssistedInject`, unique work `KEEP`, `CONNECTED`, exponential backoff; fetch the token via `FirebaseMessaging.getInstance().token` wrapped in `suspendCancellableCoroutine` (**no new dependency** — see [research.md R5](research.md)); route parent → `AuthRepository.publishFcmToken()`, child → `DeviceRepository.updateFcmToken()`; no-op when signed out
- [X] T045 [US5] Enqueue `PushTokenSyncWorker` after successful login, register, and Google sign-in in `app/src/main/java/com/safeguard/parentalcontrol/presentation/auth/AuthViewModel.kt` (FR-029)
- [X] T046 [US5] Enqueue `PushTokenSyncWorker` from `onCreate()` in `app/src/main/java/com/safeguard/parentalcontrol/presentation/MainActivity.kt` whenever `tokenManager.isLoggedIn()` — this is the backfill that makes parents who were already signed in before this ships reachable without re-login (FR-029)
- [X] T047 [US5] Replace the direct `deviceRepository.updateFcmToken(token)` call in `onNewToken()` with a `PushTokenSyncWorker` enqueue in `app/src/main/java/com/safeguard/parentalcontrol/service/SafeGuardFirebaseMessagingService.kt`, so role routing lives in the worker and the service keeps zero business logic (Constitution rule 7)
- [X] T048 [US5] Fetch the FCM token before calling `registerDevice(...)` in `app/src/main/java/com/safeguard/parentalcontrol/presentation/devicesetup/DeviceSetupViewModel.kt` so `POST /devices` carries a non-null `fcm_token` — it currently always sends `null` ([contracts/backend-contract.md §2](contracts/backend-contract.md))
- [X] T049 [US5] Add `notificationsEnabled` (from `NotificationManagerCompat.areNotificationsEnabled()`, refreshed on resume) and `notificationBannerDismissed` with a `dismissNotificationBanner()` action to `app/src/main/java/com/safeguard/parentalcontrol/presentation/dashboard/DashboardViewModel.kt`
- [X] T050 [US5] Request `POST_NOTIFICATIONS` on API 33+ for the parent role via `ActivityResultContracts.RequestPermission()` in `app/src/main/java/com/safeguard/parentalcontrol/presentation/dashboard/DashboardScreen.kt`, mirroring the existing usage in `PermissionsSetupScreen.kt`; the permission is already declared in the manifest (FR-031)
- [X] T051 [US5] Render a non-blocking, dismissible banner in `DashboardScreen.kt` when notifications are disabled and the banner is not dismissed, with a route to system notification settings — it must block no other functionality and must not be a Snackbar error (FR-032); add its EN/AR strings to `app/src/main/res/values/strings.xml` and `app/src/main/res/values-ar/strings.xml`

**Checkpoint**: quickstart Story 5 (5.1–5.8) passes, including the already-signed-in backfill and the pre-teardown logout clear.

---

## Phase 8: Polish & Cross-Cutting Concerns

- [X] T052 Run the regression checks R1–R5 in [quickstart.md](quickstart.md) — unchanged alert titles/messages/severity, unchanged dedup/cooldown/daily-cap outcomes, child `sync` push still triggers `SyncWorker`, alerts still visible with push fully disabled (Principle III), and detection/VPN/screen-time/boot behaviour untouched (SC-009)
- [X] T053 [P] Verify no FCM token, auth token, or monitored content appears in any log statement added by this feature across `util/ViolationNotifier.kt`, `worker/PushTokenSyncWorker.kt`, `worker/PendingAlertWorker.kt`, and `data/repository/AuthRepository.kt` (Principle I, Principle V)
- [X] T054 [P] Confirm `app/build.gradle` is unchanged apart from nothing — Room 2.6.1 and `ksp` are already declared, `aaptOptions { noCompress "tflite" }` is still present, and no dependency version moved (Constitution rule 12, Platform Constraints)
- [X] T055 Run `./gradlew testDebugUnitTest` and fix failures
- [ ] T056 Run `./gradlew assembleDebug`, install on a paired parent/child device set, and execute the full [quickstart.md](quickstart.md) validation for all five stories
- [X] T057 Update [contracts/backend-contract.md](contracts/backend-contract.md) to close items 5 and 6 with the backend developer's final answers, and align the client key names if they differ from the proposal

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: no dependencies — start immediately
- **Foundational (Phase 2)**: depends on Setup — **blocks US2, US3, US4**; does not block US1
- **US1 (Phase 3)**: depends on Setup only — can run fully in parallel with Phase 2
- **US2 (Phase 4)**: depends on Phase 2, and T019 gates the rest of the phase on the backend answers
- **US3 (Phase 5)**: depends on Phase 2; T030 edits the same file as US2's T025–T027
- **US4 (Phase 6)**: depends on Phase 2; smallest phase, safe to fold into US2 or US3 delivery
- **US5 (Phase 7)**: depends on Setup only — **independent of Phase 2** and can run in parallel with US1
- **Polish (Phase 8)**: depends on every story that is being shipped

### User Story Dependencies

- **US1 (P1)**: no dependency on any other story. Child-side only. The true MVP.
- **US2 (P1)**: needs Phase 2. Observable end-to-end only once **US5** ships (no token → no push).
- **US3 (P2)**: needs Phase 2 and something to tap, i.e. US2. Observable only once US5 ships.
- **US4 (P2)**: needs Phase 2. Independently testable by pushing directly to a child device.
- **US5 (P3)**: no dependency on any other story, and is the precondition for observing US2/US3/US4.

### File-Conflict Notes (do not parallelize these)

- `data/repository/AlertRepository.kt` — T011–T014 (US1) and T021–T022 (US2)
- `data/repository/AuthRepository.kt` — T017 (US1), T020 (US2), T042–T043 (US5)
- `util/ViolationNotifier.kt` — T004 (Phase 2), T025–T027 (US2), T030 (US3), T037 (US4)
- `presentation/MainActivity.kt` — T031 (US3), T046 (US5)
- `presentation/navigation/NavGraph.kt` — T032, T033 (US3)
- `res/values/strings.xml` and `res/values-ar/strings.xml` — T023–T024 (US2), T051 (US5)
- `service/SafeGuardFirebaseMessagingService.kt` — T005 (Phase 2), T038 (US4), T047 (US5)

### Parallel Opportunities

- T003 runs alongside T001–T002
- T006 and T007 are independent files; T029 is independent of everything in its phase
- T018, T028, T039 (unit tests) run alongside their phase's implementation
- T023 and T024 (EN and AR strings) run together
- T040 is independent of the rest of Phase 7's opening tasks
- **Whole-phase parallelism**: US1 (Phase 3) and US5 (Phase 7) touch almost disjoint files — one developer per phase is the highest-value split, and it also unblocks end-to-end observation soonest

---

## Parallel Example: User Story 1

```bash
# Independent new files — launch together:
Task: "Create PendingAlertEntity in app/src/main/java/com/safeguard/parentalcontrol/data/local/PendingAlertEntity.kt"
Task: "Create PendingAlertDao in app/src/main/java/com/safeguard/parentalcontrol/data/local/PendingAlertDao.kt"

# Then, after the store and classifier land:
Task: "Unit tests in app/src/test/java/com/safeguard/parentalcontrol/data/local/PendingAlertStoreTest.kt"
Task: "Unit tests in app/src/test/java/com/safeguard/parentalcontrol/data/repository/AlertRetryClassifierTest.kt"
```

## Parallel Example: Two developers

```bash
# Developer A — child side, no parent device needed:
Phase 1 → Phase 3 (US1: durable delivery)

# Developer B — parent reachability, unblocks everything downstream:
Phase 1 → Phase 7 (US5: push-token registration + notification permission)

# Both converge, then Phase 2 → US2 → US3 → US4
```

---

## Implementation Strategy

### MVP (User Story 1 only)

1. Phase 1: Setup
2. Phase 3: US1 — durable delivery
3. **STOP and VALIDATE**: quickstart Story 1, including the 50-violation offline run (SC-001)
4. Shippable on its own: no violation is silently lost, even though the notification is still the old bare one

US1 is genuinely independent — it needs no parent-side work, no backend confirmation, and no push at all.

### Recommended delivery order

**US1 → US5 → US2 → US3 → US4**, not strict spec priority. US5 is ranked P3 in the spec on the assumption that parent devices are already registered for push; [research.md R5](research.md) shows they never have been. Until US5 ships, US2/US3/US4 are code that cannot be demonstrated. Shipping US5 second turns the remaining three into observable increments.

### Incremental delivery

1. Setup → US1 → validate → ship (no alert is lost)
2. US5 → validate → ship (parents are reachable; existing bare notifications start arriving)
3. Phase 2 + US2 → validate → ship (notifications become meaningful, EN/AR)
4. US3 → validate → ship (one tap to the record)
5. US4 → validate → ship (children never see violation notifications)

Each step adds value without breaking the previous one.

---

## Notes

- `[P]` = different files, no dependency on an incomplete task
- Backend Coordination items 5 and 6 gate **T019 only**; every other task can proceed while they are open
- The constitution's twelve enforcement rules are checked per task where relevant; the single justified deviation (no new `presentation/<feature>/` package, because FR-028 forbids new destinations) is recorded in [plan.md](plan.md) §Complexity Tracking
- Commit after each task or logical group; stop at any checkpoint to validate a story independently
