---
description: "Task list for 014 Permanent Account Deletion"
---

# Tasks: Permanent Account Deletion

**Input**: Design documents from `specs/014-account-deletion/`: plan.md, spec.md, research.md, data-model.md, contracts/account-deletion.md, contracts/teardown.md, quickstart.md

**Tests**: Included. The plan brief (phase P6) explicitly requests unit tests for status-code mapping, the teardown order, single-flight behaviour and the ViewModel rules. Write each test before its implementation task and confirm that it fails first.

**Organization**: Tasks are grouped by user story so each can be built and verified on its own. Stories US3 and US4 extend the screen built in US1 (see Dependencies).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (a different file, and no dependency on an unfinished task)
- **[Story]**: US1–US5 from spec.md

## Path Conventions

- Main source: `app/src/main/java/com/safeguard/parentalcontrol/` (written below as `…/`)
- Unit tests: `app/src/test/java/com/safeguard/parentalcontrol/` (written below as `test/…/`)
- Resources: `app/src/main/res/`

## Global rules for every task (from the plan, the constitution and the guardrails)

- **Do not modify**:
  - `ml/*`
  - the internals of `ContentFilterVpnService`, `MonitoringService` and `TextMonitoringAccessibilityService`
  - any receiver
  - any worker's logic
  - `AuthInterceptor`
  - `di/*`
  - pinning and `network_security_config`
  - existing routes
  - the logout flow
  - `ImageBlurManager`
  - `safeApiCall`
- **No new libraries.**
- **Logging**: use `Timber.tag("AccountDeletion")` only. Never log the password, confirmation text, email, tokens, user or child ids, or names.
- **UI errors**: show a Snackbar through `LaunchedEffect(uiState.error)` and then call `clearError()`. A wrong password is shown inline on its field. Use an `AlertDialog` only for confirmation.
- **Text**: every user-visible string lives in `res/values/strings.xml` **and** `res/values-ar/strings.xml`.
- **Checkpoint**: run `./gradlew assembleDebug` at the end of every phase.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: add every string key in one pass, because both strings files are shared by all stories and later tasks can then run in parallel without editing them.

- [X] T001 Add all new English strings to `app/src/main/res/values/strings.xml`:
  - `settings_delete_account` = "Delete account"
  - `settings_delete_account_desc` = "Permanently delete your account and data"
  - `settings_deletion_help` = "How account deletion works"
  - `settings_deletion_help_desc` = "Opens our public account-deletion page"
  - `settings_deletion_help_no_browser` = "Couldn't open the page. Visit: %1$s"
  - `delete_account_title`
  - `delete_account_intro` = "Deleting your account is immediate and permanent. It cannot be undone."
  - `delete_account_consequence_account`
  - `delete_account_consequence_children_n` (a `<plurals>` resource) and `delete_account_consequence_children_unknown` = "All of your linked children's accounts"
  - `delete_account_consequence_data` = "All devices, screen-time and app-usage history, alerts, rules, filters and family links"
  - `delete_account_consequence_device_data` = "This device's data and history"
  - `delete_account_consequence_blurred_photos` = "Photos already blurred in your gallery will stay blurred and can no longer be restored"
  - `delete_account_password_label`
  - `delete_account_confirm_word_label` = "Type DELETE to confirm"
  - `delete_account_switch_to_google` = "Signed in with Google? Type DELETE instead"
  - `delete_account_switch_to_password` = "Use my password instead"
  - `delete_account_button` = "Delete account permanently"
  - `delete_account_final_title` = "Delete your account?"
  - `delete_account_final_message` = "This is your last chance. Your account and data will be deleted immediately and cannot be recovered."
  - `delete_account_final_confirm` = "Delete permanently"
  - `delete_account_cancel` = "Cancel"
  - `delete_account_error_wrong_password` = "That password is incorrect."
  - `delete_account_error_wrong_confirmation` = "That confirmation wasn't accepted. Nothing was deleted."
  - `delete_account_error_supervised` = "Only your parent can delete this account. Please ask them."
  - `delete_account_error_rate_limited` = "Too many attempts. Please wait a while and try again."
  - `delete_account_error_rate_limited_seconds` = "Too many attempts. Please try again in %1$d seconds."
  - `delete_account_error_network` = "Nothing was deleted. Check your connection and try again."
  - `delete_account_error_unknown` = "Something went wrong. Nothing was deleted."
  - `delete_account_retry` = "Retry"
  - `login_account_deleted` = "Your account was deleted."
  - `login_account_deleted_by_parent` (kept for future use by FR-025)
  - `login_uninstall_note_title` = "Protection is off"
  - `login_uninstall_note_body` = "Your account is gone. You can now uninstall Haris or turn off its permissions."
  - `login_uninstall_note_button` = "Open app settings"
  - `login_session_ended` (reuses the existing session-ended meaning)
  - `children_delete_action` = "Delete account permanently"
  - `children_delete_title` = "Permanently delete %1$s's account?"
  - `children_delete_message` = "This deletes %1$s's account and all of their data immediately. It cannot be undone. To only stop monitoring and keep the account, use \"Stop monitoring\" instead."
  - `children_delete_type_name_label` = "Type %1$s to confirm"
  - `children_delete_password_label` = "Your password"
  - `children_delete_confirm_google` = "Confirm with Google"
  - `children_delete_google_mismatch` = "That Google account doesn't match yours. Nothing was deleted."
  - `children_delete_account_mismatch` = "We couldn't confirm it's you. Please sign in again. Nothing was deleted."
  - `children_delete_success` = "%1$s's account was deleted."
  - `children_delete_not_linked` = "%1$s is no longer linked to your account."
  - Rewrite the existing `children_remove` to "Stop monitoring (account kept)", `children_remove_title` to "Stop monitoring %1$s?", and `children_remove_message` to "%1$s's account will be kept. You will no longer see or manage their devices. To erase the account instead, use \"Delete account permanently\"."
- [X] T002 Add the Arabic translation of **every** key from T001, including the plural forms (zero/one/two/few/many/other) for `delete_account_consequence_children_n`, and the rewritten `children_remove*` keys, to `app/src/main/res/values-ar/strings.xml`. `DELETE` stays in Latin letters inside the Arabic strings, because the server expects the literal word.
- [X] T003 Checkpoint: `./gradlew assembleDebug` succeeds with the new resources.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: the network contract, status-code mapping, persisted marker, ordered teardown and application-scoped coordinator. Every story depends on these.

**⚠️ CRITICAL**: no user-story work starts until this phase is complete.

### Tests (write first; they must fail)

- [X] T004 [P] Write `test/…/data/repository/DeletionErrorTest.kt`:
  - `fromSelfDelete` maps 400 to WrongPassword, 401 to SessionOver, 403 to SupervisedChild, 429 to `RateLimited(null)`, `code == null` to Network, and 500 to `Unknown(500)`.
  - `fromChildDelete` maps 404 to ChildNotFound, 401 to SessionOver, 429 to RateLimited, null to Network, and **400 to `Unknown(400)`** (never WrongPassword).
  - `fromReauth` maps both 400 and 401 to WrongPassword, `DeletionError.CODE_ACCOUNT_MISMATCH` to **AccountMismatch** (never WrongPassword or SessionOver), 429 to RateLimited, and null to Network.
  - Only WrongPassword is ever produced for 400 on the self-delete path.
- [X] T005 [P] Write `test/…/data/repository/AuthRepositoryDeleteAccountTest.kt` (MockK `ApiService`):
  - A 200 with a body, an empty 200, and a 204 (whose `Response<Unit>.body()` is null) all return `NetworkResult.Success(Unit)`.
  - 400 returns `Error(code=400)`, and an `IOException` returns `Error(code=null)`.
  - The request serialized with the app's Gson (`GsonBuilder().registerTypeAdapter(Date::class.java, FlexibleDateAdapter()).create()`) is exactly `{"password":"x"}` for `forPassword("x")` and exactly `{"confirmation":"DELETE"}` for `forGoogle()`.
- [X] T006 [P] Write `test/…/data/account/AccountTeardownManagerTest.kt` with fakes for `DeviceTeardownOps`, the repositories, `PreferencesManager`, `TokenManager` and `AccountDeletionState` that record calls into one ordered list. Assert:
  - The order matches `contracts/teardown.md`: marker, then `isContentFilteringEnabled=false`, then cancelAllWork + cancel alarm, then stop services, then wipe (cache, Room, cooldowns, word lists, rules, hashers, image_backup, notifications, FCM, analytics), then `preferencesManager.clearAll`, then the token clear (last of the wipe), then a final cancelAllWork, then completeTeardown.
  - `AuthEvent.AccountDeleted` is emitted only for `TeardownReason.ACCOUNT_DELETED`.
  - A step that throws does not stop the steps after it.
  - A second call is harmless.
  - Cancelling the calling coroutine midway still completes every step (`NonCancellable`).

### Implementation

- [X] T007 [P] Add `data class DeleteAccountRequest(val password: String? = null, val confirmation: String? = null)` with a companion `forPassword(password: String)` and `forGoogle()` (the latter sets `confirmation = "DELETE"`) to `…/data/model/Models.kt`. Override `toString()` to redact the password.
- [X] T008 [P] Add two methods to `…/data/remote/ApiService.kt`:
  - `@HTTP(method = "DELETE", path = "auth/account", hasBody = true) suspend fun deleteAccount(@Body request: DeleteAccountRequest): Response<Unit>`
  - `@DELETE("family/child/{childId}") suspend fun deleteChildAccount(@Path("childId") childId: Int): Response<Unit>`
  - Add KDoc warning that `childId` is `FamilyLink.childId`, never `FamilyLink.id`, and that this is different from `removeFamilyLink`.
- [X] T009 [P] Create `…/data/repository/DeletionError.kt`, the sealed class from data-model §2, with the three pure mappers `fromSelfDelete`, `fromChildDelete` and `fromReauth` that take a `NetworkResult.Error`. The mapping uses the status code only, never the message.
  - Include `data object AccountMismatch`.
  - Include `const val CODE_ACCOUNT_MISMATCH = -1001`. This is a client-side sentinel that no HTTP status can collide with. Re-auth uses it when there is no stored session, or when the server authenticates a different user than the one signed in.
- [X] T010 Add `suspend fun deleteAccount(request: DeleteAccountRequest): NetworkResult<Unit>` to `…/data/repository/AuthRepository.kt`:
  - Run on `withContext(Dispatchers.IO)`, calling `safeApiCall { apiService.deleteAccount(request) }`.
  - Remap an `Error` whose `code in 200..299` to `Success(Unit)`, then `map { }`.
  - Log `start` and `code=… ` without the body.
  - Do not touch `logout()`.
  - Depends on T007 and T008.
- [X] T011 [P] Add `suspend fun clearCooldowns()` to `…/data/repository/AlertRepository.kt`. It runs `context.alertCooldownStore.edit { it.clear() }`, and nothing else changes.
- [X] T012 [P] In `…/util/TokenManager.kt`:
  - Add `object AccountDeleted : AuthEvent()`.
  - Add `fun clearTokensDueToAccountDeletion(emit: Boolean)`. It clears `encryptedPrefs`, logs "Tokens cleared (account deleted)", and emits `AuthEvent.AccountDeleted` only when `emit` is true.
- [X] T013 [P] Create `…/util/AccountDeletionState.kt`:
  - A `@Singleton` class with an `@Inject constructor(@ApplicationContext context)`, over a plain `SharedPreferences("account_deletion_state")`. Every write uses `commit()`.
  - Holds the keys from data-model §3.
  - API:
    - `markPending(roleWasParent: Boolean, hadLinkedParent: Boolean)`
    - `pending(): PendingMarker?`
    - `clearPending()`
    - `markTeardownInProgress(reason: TeardownReason)`
    - `teardownInProgress(): TeardownReason?`
    - `completeTeardown(showNotice: Boolean, childDevice: Boolean)`, which clears the pending and teardown keys and sets the notice flags
    - `noticePending(): Boolean`
    - `childUninstallNotice(): Boolean`
    - `dismissNotice()`
  - Stores no PII.
- [X] T014 [P] Create `…/data/account/DeviceTeardownOps.kt`, a `@Singleton` with an `@Inject constructor(@ApplicationContext context, okHttpCache: okhttp3.Cache, firebaseAnalytics: FirebaseAnalytics)`. Each method is a thin wrapper that never throws (wrap in `runCatching` and log the step result):
  - `cancelAllWork()`: `WorkManager.getInstance(ctx).cancelAllWork()`
  - `cancelMonitoringRestartAlarm()`: build `PendingIntent.getService(ctx, 1, Intent(ctx, MonitoringService::class.java), FLAG_NO_CREATE or FLAG_IMMUTABLE)` and pass it to `AlarmManager.cancel` when not null
  - `stopMonitoringService()`: `ctx.startService(MonitoringService.getStopIntent(ctx))` when `MonitoringService.isRunning`
  - `stopVpn()`: `startService` with `ContentFilterVpnService.ACTION_STOP` when `ContentFilterVpnService.isVpnRunning(ctx)`
  - `evictHttpCache()`
  - `deleteImageBackups()`: `File(ctx.filesDir, "image_backup").deleteRecursively()`
  - `cancelAllNotifications()`: `NotificationManagerCompat.from(ctx).cancelAll()`
  - `deleteFcmToken()`: `FirebaseMessaging.getInstance().deleteToken()`, fire-and-forget
  - `resetAnalytics()`: `firebaseAnalytics.resetAnalyticsData()`
- [X] T015 Create `…/data/account/AccountTeardownManager.kt`:
  - A `@Singleton` with `enum class TeardownReason { ACCOUNT_DELETED, SESSION_GONE_AFTER_MARKER }`.
  - `suspend fun teardown(reason: TeardownReason)` runs inside `withContext(NonCancellable + Dispatchers.IO)`, in exactly the step order of `contracts/teardown.md`, with each step in its own `runCatching` and logged as `teardown step=<name> ok|failed`.
  - The wipe step calls `alertRepository.clearPendingAlerts()`, `alertRepository.clearCooldowns()`, `customWordRepository.clearCache()`, `screenTimeRulesRepository.clearCache()`, `imageHasher.clearCache()`, `textHasher.clearCache()` and the `DeviceTeardownOps` wipes.
  - The last steps are `preferencesManager.clearAll()`, then `tokenManager.clearTokensDueToAccountDeletion(emit = reason == ACCOUNT_DELETED)`, then `ops.cancelAllWork()`, then `accountDeletionState.completeTeardown(showNotice = reason == ACCOUNT_DELETED, childDevice = <role captured before the wipe> == child)`.
  - Log the total duration as `teardown done in <ms>ms`, which quickstart V5 checks against the 2 s goal.
  - It must make T006 pass. Depends on T011–T014.
- [X] T016 Create `…/data/account/AccountDeletionCoordinator.kt`, a `@Singleton` with `@Inject constructor(authRepository, teardownManager, accountDeletionState, preferencesManager)`:
  - Owns `private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)` and a `Mutex`.
  - Exposes `val selfState: StateFlow<DeletionState>` (data-model §4, including the `DeletionState` sealed interface declared in this file).
  - `fun deleteOwnAccount(request: DeleteAccountRequest)`:
    - Returns immediately when `selfState.value is InFlight` (single flight).
    - Otherwise sets InFlight, then `accountDeletionState.markPending(preferencesManager.isParent, preferencesManager.hasLinkedParent)`, then launches in `scope`: `authRepository.deleteAccount`.
    - Success: `teardownManager.teardown(ACCOUNT_DELETED)` → `Succeeded`.
    - Error with a definite server answer (400, 403, 429, or another non-401 HTTP code): `clearPending()` → `Failed(DeletionError.fromSelfDelete(e))`.
    - Error with **`code == null`** (network or timeout): **keep the marker**. The server may have acted and the response may have been lost. The state is `Failed(Network)`.
    - Error with **401 while a marker is present**: the session is already gone, which may be a lost success. Run the in-process recovery `recoverFromMarker(reason401 = true)`, which uses the same branch logic as T024. The state is `Failed(SessionOver)`, and the user **never** sees "Account deleted".
    - Log `failed code=<code> mapped=<Name>`.
  - `fun acknowledgeSelf()` resets Failed or Succeeded to Idle.
  - `fun attachSelf()` is called by every new `DeleteAccountViewModel` in `init`. It resets any terminal state (`Succeeded` or `Failed`) to `Idle` unless the state is `InFlight`. A stale `Succeeded` from an earlier deletion in the same process must never navigate a newly signed-in user to Login.
  - Depends on T009, T010, T013, T015.
- [X] T017 Handle `AuthEvent.AccountDeleted` in `SafeGuardApp` in `…/presentation/MainActivity.kt`. Log it, call `navigateToLogin(navController)`, and show **no** Toast. The Login screen shows the confirmation (US1). No other branch changes.
- [X] T018 Checkpoint: `./gradlew assembleDebug` and `./gradlew testDebugUnitTest --tests "*DeletionError*" --tests "*AuthRepositoryDeleteAccount*" --tests "*AccountTeardownManager*"` are green.

**Checkpoint**: the deletion engine is ready. It has no UI yet.

---

## Phase 3: User Story 1 - Parent deletes their own account (Priority: P1) 🎯 MVP

**Goal**: from Settings, a parent sees the consequences (including linked children), enters their password (or `DELETE` for Google), confirms twice, the account is deleted, and they land on Login with "Your account was deleted" and a fresh-install state.

**Independent Test**: quickstart V1, V2, V5, V5b, V7, V8, V9 and V10 on a throwaway parent with one child.

### Tests for User Story 1 (write first; they must fail)

- [X] T019 [P] [US1] Write `test/…/data/account/AccountDeletionCoordinatorTest.kt` (fake `AuthRepository`, fake teardown manager, in-memory `AccountDeletionState`, `StandardTestDispatcher`):
  - Two `deleteOwnAccount` calls while the first is in flight make exactly **one** repository call.
  - 2xx: the marker is set **before** the call, teardown runs with `ACCOUNT_DELETED`, and the state is `Succeeded`.
  - 400: the marker is cleared and the state is `Failed(WrongPassword)`.
  - 429: `Failed(RateLimited)`.
  - Cancelling the collector does not cancel the job.
  - A network error (`code == null`) **keeps** the marker. A following retry that gets a 401 runs the recovery path (teardown `SESSION_GONE_AFTER_MARKER` for a parent), and the state is `Failed(SessionOver)`, never `Succeeded`.
  - After `Succeeded`, `attachSelf()` resets the state to `Idle`. A second `attachSelf()` while `InFlight` leaves it `InFlight`.
  - `resumePendingIfAny` (T024) branches:
    - marker + `getCurrentUser` 200: marker cleared, no teardown
    - marker + 401 + role parent: teardown `SESSION_GONE_AFTER_MARKER`
    - marker + 401 + `hadLinkedParent=false` child: teardown `SESSION_GONE_AFTER_MARKER`
    - marker + 401 + `hadLinkedParent=true`: **no teardown**, marker cleared
    - marker + network error: marker kept, no teardown
    - `teardownInProgress` set: teardown resumed with the same reason
- [X] T020 [P] [US1] Write `test/…/presentation/deleteaccount/DeleteAccountViewModelTest.kt` (MockK coordinator with a real `MutableStateFlow`, `MainDispatcherRule`):
  - `canSubmit` is false for an empty password. In GOOGLE mode it is false for `"delete"` and `"DELETE "`, and true only for `"DELETE"`.
  - Requesting the final confirmation shows `showFinalConfirm` and does **not** call the coordinator. Only `onFinalConfirm()` calls it, exactly once.
  - `Failed(WrongPassword)` sets `credentialError` and leaves `error` null.
  - `Failed(RateLimited)` sets `error` to the rate-limit text and `credentialError` stays null. The text never contains the wrong-password string.
  - `Failed(Network)` keeps the credential and sets `errorRetryable = true`.
  - `Succeeded` sets `deleted = true`.
  - `toString()` of the UiState does not contain the credential.

### Implementation for User Story 1

- [X] T021 [P] [US1] Create `…/presentation/deleteaccount/DeleteAccountUiState.kt`:
  - `data class DeleteAccountUiState` with the fields of data-model §5, plus `enum class ConfirmMode { PASSWORD, GOOGLE_DELETE }`.
  - A computed `canSubmit`.
  - An overridden `toString()` that redacts `credential`.
- [X] T022 [US1] Create `…/presentation/deleteaccount/DeleteAccountViewModel.kt`, a `@HiltViewModel` injecting `AccountDeletionCoordinator`, `AuthRepository`, `FamilyRepository`, `PreferencesManager`, `ImageBlurManager` and `@ApplicationContext`:
  - `init`:
    - Call `coordinator.attachSelf()` first, which clears any stale terminal state.
    - Set `role` from `preferencesManager.isParent` (wording only).
    - Launch `authRepository.getCurrentUser()`; when `authProvider == GOOGLE`, set `mode = GOOGLE_DELETE`. Failure keeps PASSWORD.
    - Parent: `familyRepository.getLinkedChildren()` gives `linkedChildCount`. Failure leaves it null.
    - Child: `imageBlurManager.getPendingReviews().isNotEmpty()` on IO gives `hasReviewOriginals`.
    - Collect `coordinator.selfState` and map it with `DeletionError`: WrongPassword goes to `credentialError`, choosing wrong_password or wrong_confirmation by mode. SupervisedChild, RateLimited, Network and Unknown go to `error` + `blockingNotice`, with `errorRetryable` for Network and Unknown. SessionOver goes to `error` with `login_session_ended` and **no** navigation from this screen (the interceptor's `TokenRevoked` handles that). InFlight goes to `isLoading`. Succeeded goes to `deleted`. Call `coordinator.acknowledgeSelf()` after mapping any Failed **and** after mapping Succeeded.
  - Functions:
    - `onCredentialChange` also clears `credentialError`.
    - `toggleVisibility`
    - `switchMode` also clears the credential.
    - `onDeleteClicked` does nothing unless `canSubmit`; otherwise it sets `showFinalConfirm`.
    - `onFinalConfirm` builds `DeleteAccountRequest.forPassword` or `forGoogle` and calls `coordinator.deleteOwnAccount`.
    - `dismissFinalConfirm`, `retry` and `clearError`.
  - The credential never goes into `SavedStateHandle`. No logging.
  - Must make T020 pass.
- [X] T023 [US1] Create `…/presentation/deleteaccount/DeleteAccountScreen.kt` (Compose, `collectAsStateWithLifecycle`):
  - A top bar with back.
  - The consequences list: intro, account, then the parent children line (plural or unknown), then data.
  - A password or DELETE field with a visibility toggle (password mode only) and `isError`/`supportingText` from `credentialError`.
  - A `TextButton` mode-switch link.
  - The destructive button: `OutlinedButton` with error-colour content and border, **not** a filled primary button, never pre-focused, enabled by `canSubmit`.
  - `blockingNotice` shown as supporting text under the button.
  - The final-confirm `AlertDialog`: the confirm `TextButton` uses the error colour and the dismiss button is Cancel. Neither is a default action.
  - `BackHandler(enabled = uiState.isLoading) {}` blocks back while loading, and the fields are disabled while loading.
  - `LaunchedEffect(uiState.error)` shows a Snackbar. It uses `Indefinite` with a "Retry" action calling `retry()` when `errorRetryable`, and `Long` otherwise, then calls `clearError()`.
  - `LaunchedEffect(uiState.deleted)` calls `onDeleted()`.
  - Right-to-left safe: no hard-coded start or end padding assumptions, and `Icons.AutoMirrored` where it applies.
- [X] T024 [US1] Add `suspend fun resumePendingIfAny()` to `…/data/account/AccountDeletionCoordinator.kt`. It is **suspending** so its caller can wait for it (FR-016a: "before doing anything else"). Also add `fun hasPendingRecovery(): Boolean`, which is true when a marker or a teardown flag is present. The branch logic is shared with T016's in-process `recoverFromMarker`. It follows the research RD-3 / data-model §3 recovery branches:
  - If `teardownInProgress()` is set, resume `teardown(sameReason)`.
  - Else, if `pending()` is set, call `authRepository.getCurrentUser()`:
    - Success: `clearPending()`.
    - `Error(401)`: when `roleWasParent || !hadLinkedParent`, run `teardown(SESSION_GONE_AFTER_MARKER)`; otherwise `clearPending()` only.
    - `Error(null)`: keep the marker.
  - Runs on `Dispatchers.IO`, wrapped in `withContext(NonCancellable)` once a teardown has started. Log every branch.
  - Must make the T019 recovery cases pass.
- [X] T025 [US1] In `…/presentation/MainActivity.kt`, inject `AccountDeletionCoordinator`. In `onCreate`:
  - If `coordinator.hasPendingRecovery()` is true, run `lifecycleScope.launch { coordinator.resumePendingIfAny(); startMonitoringServiceIfNeeded() }` and do **not** call `startMonitoringServiceIfNeeded()` synchronously. The gate is then evaluated only after recovery has finished.
  - Otherwise, keep the existing synchronous call unchanged.
  - Also register a one-shot retry: when `resumePendingIfAny()` leaves the marker in place because of a network error, watch the injected `NetworkMonitor` (`util/NetworkMonitor.kt`, which already exists) and call `resumePendingIfAny()` again once connectivity returns while the Activity is alive (FR-016a, "when connectivity returns").
- [X] T026 [US1] In `…/presentation/navigation/NavGraph.kt`:
  - Add `data object DeleteAccount : Screen("delete_account")`.
  - Add `composable(Screen.DeleteAccount.route) { DeleteAccountScreen(onNavigateBack = { navController.popBackStack() }, onDeleted = { navController.navigate(Screen.Login.route) { popUpTo(0) { inclusive = true }; launchSingleTop = true } }) }`.
  - Pass `onNavigateToDeleteAccount = { navController.navigate(Screen.DeleteAccount.route) }` to `SettingsScreen`.
  - No existing route changes.
- [X] T027 [US1] In `…/presentation/settings/SettingsScreen.kt`:
  - Add the `onNavigateToDeleteAccount: () -> Unit` parameter, also passed through in the preview.
  - In the Account section, add a `Divider` and a `SettingsItem(icon = Icons.Default.DeleteForever, title = settings_delete_account, subtitle = settings_delete_account_desc, iconTint = error, titleColor = error, onClick = onNavigateToDeleteAccount)` **below** Log Out.
  - Settings → Delete account → Delete → confirm must be ≤ 3 taps (SC-001).
- [X] T028 [P] [US1] Add to `…/presentation/auth/AuthViewModel.kt`:
  - Inject `AccountDeletionState`.
  - Add the field `deletionNotice: DeletionNotice? = null` (`data class DeletionNotice(val showUninstallNote: Boolean)`) to the **existing** `AuthUiState`. Do **not** add a second public flow (constitution Principle II allows one `StateFlow<UiState>` per ViewModel). Read the value once in `init` with `_uiState.update { it.copy(deletionNotice = …) }`.
  - `fun dismissDeletionNotice()` calls `accountDeletionState.dismissNotice()` and runs `_uiState.update { it.copy(deletionNotice = null) }`.
  - Call `dismissDeletionNotice()` after any successful sign-in (email or Google).
- [X] T029 [US1] In `…/presentation/auth/LoginScreen.kt`:
  - Read `uiState.deletionNotice` from the existing `uiState` collection. Do not add a new collector.
  - When it is non-null, show a Snackbar with `login_account_deleted` once (via `LaunchedEffect`).
  - When `showUninstallNote` is true, render a dismissible card above the form: `login_uninstall_note_title`/`body`, plus a button that opens `Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))` wrapped in `runCatching`, and a close icon calling `dismissDeletionNotice()`.
  - The card stays across cold starts until it is dismissed or the user signs in (FR-022a).
  - Existing parameters are unchanged.
- [X] T030 [US1] Checkpoint: `./gradlew assembleDebug` and `./gradlew testDebugUnitTest --tests "*AccountDeletionCoordinator*" --tests "*DeleteAccountViewModel*"` are green. Then run quickstart V1, V2, V5, V5b, V7, V8, V9 and V10 on throwaway accounts.
  - **Status**: build and unit tests are green. The on-device quickstart runs (V1, V2, V5, V5b, V7, V8, V9, V10) need throwaway accounts and a device, so they are deferred to T057 and NOT yet done.

**Checkpoint**: US1 alone meets the Play self-deletion requirement for parents.

---

## Phase 4: User Story 2 - Parent deletes a linked child's account (Priority: P1)

**Goal**: from Children, "Delete account permanently" is separate from "Stop monitoring". It requires the exact child name and the parent's re-authentication (the password through `auth/login`, or a fresh Google sign-in), and the child disappears on success.

**Independent Test**: quickstart V4, V4b, V4c and V13 with a parent who has two throwaway children.

### Tests for User Story 2 (write first; they must fail)

- [X] T031 [P] [US2] Extend `test/…/data/account/AccountDeletionCoordinatorTest.kt` with the child path:
  - A wrong password on re-auth (login 401) gives `Failed(WrongPassword)` and **no** delete call.
  - A re-auth user-id mismatch (`Error(code = CODE_ACCOUNT_MISMATCH)`) gives `Failed(AccountMismatch)` and no delete call.
  - Re-auth OK + delete 2xx gives `Succeeded`.
  - Re-auth OK + 404 gives `Failed(ChildNotFound)`.
  - Two calls for the same `childId` while in flight give one re-auth and one delete.
  - Calls for different children are independent.
  - A child deletion **never** calls teardown.
- [X] T032 [P] [US2] Extend `test/…/data/repository/AuthRepositoryDeleteAccountTest.kt`:
  - `reauthenticateWithPassword` sends `LoginRequest(storedEmail, password)`. On 200 with the matching id it saves the tokens through `TokenManager` and returns Success. On a mismatched id, or when no email is stored, it returns `Error(code = DeletionError.CODE_ACCOUNT_MISMATCH)` and does **not** save the tokens.
  - `FamilyRepository.deleteChildAccount` 204 returns Success.

### Implementation for User Story 2

- [X] T033 [P] [US2] Add `suspend fun deleteChildAccount(childId: Int): NetworkResult<Unit>` to `…/data/repository/FamilyRepository.kt`. It runs on IO, calls `safeApiCall { apiService.deleteChildAccount(childId) }`, remaps an Error with a 2xx code to Success, logs `child delete code=…` without the id, and ends with `map { }`.
- [X] T034 [US2] Add to `…/data/repository/AuthRepository.kt`:
  - `suspend fun reauthenticateWithPassword(password: String): NetworkResult<Unit>`, run with `withContext(Dispatchers.IO)`:
    - Email is `preferencesManager.userEmail`; when it is null, return `Error("no session", DeletionError.CODE_ACCOUNT_MISMATCH)`.
    - Call `safeApiCall { apiService.login(LoginRequest(email, password)) }`.
    - On Success, if `user.id == preferencesManager.userId`, save the tokens through `tokenManager.saveTokens`; otherwise return `Error("account mismatch", DeletionError.CODE_ACCOUNT_MISMATCH)`.
    - Never return 401 for a mismatch. The re-auth endpoints bypass `AuthInterceptor` (`isAuthEndpoint`), so no redirect to Login would happen, and 401 already means a wrong password here.
    - Do **not** call `saveUserInfo` on a mismatch.
  - `suspend fun reauthenticateWithGoogle(idToken: String): NetworkResult<Unit>`: the same (on `Dispatchers.IO`, with the same mismatch sentinel), using `apiService.googleAuth(GoogleAuthRequest(idToken, null))`.
- [X] T035 [US2] Add the child path to `…/data/account/AccountDeletionCoordinator.kt`:
  - `val childStates: StateFlow<Map<Int, DeletionState>>`
  - `fun deleteChildAccount(childId: Int, reauth: ChildReauth)`, where `sealed interface ChildReauth { data class Password(val value: String); data class Google(val idToken: String) }`:
    - Single flight per `childId`.
    - In `scope`: run the re-auth. On failure, map it with `DeletionError.fromReauth`. On success, call `familyRepository.deleteChildAccount(childId)` and map a failure with `fromChildDelete`.
    - No teardown and no marker.
  - `fun acknowledgeChild(childId)`
  - Inject `FamilyRepository`. Must make T031 pass.
- [X] T036 [US2] Add to `…/presentation/children/ChildrenViewModel.kt`:
  - Inject `AccountDeletionCoordinator`, `AuthRepository` and `GoogleSignInManager`.
  - Add the fields from data-model §6 to `ChildrenUiState`.
  - In `init`, set `parentIsGoogle` from `getCurrentUser().authProvider == GOOGLE`.
  - Functions:
    - `openDeleteDialog(link)`
    - `closeDeleteDialog()`, which clears the name, credential and error
    - `onDeleteNameChange`
    - `onDeleteCredentialChange`
    - `confirmDeletePassword()` calls the coordinator with `ChildReauth.Password`
    - `confirmDeleteWithGoogle(activityContext: Context)`: `googleSignInManager.signIn(activityContext)`. On Success, call the coordinator with `ChildReauth.Google`. On Cancelled, do nothing and keep the dialog open. On Error, set `error`.
  - Collect `coordinator.childStates`:
    - `Succeeded`: remove the child from `children` **by `childId`**, close the dialog, and set `successMessage = children_delete_success(name)`.
    - `ChildNotFound`: close the dialog, call `loadChildren()`, and set `successMessage = children_delete_not_linked(name)`.
    - `WrongPassword`: set `deleteCredentialError`.
    - `AccountMismatch`: close the dialog, and set `error` to `children_delete_google_mismatch` for a Google parent or `children_delete_account_mismatch` for a password parent. Nothing was deleted.
    - `SessionOver` (a 401 from `DELETE family/child`, which does go through the interceptor): close the dialog. The interceptor's `TokenRevoked` routes to Login.
    - `RateLimited`, `Network` and `Unknown`: keep the dialog open and put the message in a new `deleteDialogNotice: String?` field, which the dialog renders as supporting text above its buttons. A Snackbar behind the dialog's scrim would be hidden.
    - Call `acknowledgeChild` after handling any of these.
  - `canDelete` follows data-model §6. Credentials are never logged.
- [X] T037 [P] [US2] Create `…/presentation/children/DeleteChildAccountDialog.kt`, an `AlertDialog` with:
  - a warning icon in the error colour;
  - the title `children_delete_title(name)` and the message `children_delete_message(name)`, which explains the difference from Stop monitoring;
  - an `OutlinedTextField` labelled `children_delete_type_name_label(name)` (no autocorrect or capitalisation, exact match, works with Arabic);
  - a password field with a visibility toggle, or a "Confirm with Google" `OutlinedButton` when `parentIsGoogle`;
  - inline `deleteCredentialError`;
  - `deleteDialogNotice` as error-coloured supporting text above the buttons;
  - a confirm `TextButton` in the error colour, enabled by `canDelete`, showing a small progress indicator while `deletingChildId == child.childId`;
  - a Cancel button that is disabled while deleting.
  - It is stateless and takes lambdas.
- [X] T038 [US2] In `…/presentation/children/ChildrenScreen.kt` `ChildCard`:
  - Keep the existing Remove icon and dialog. They now use the rewritten `children_remove*` strings (Stop monitoring, account kept).
  - Add a separate overflow `IconButton(Icons.Default.MoreVert)` with a `DropdownMenu` item `children_delete_action` in the error colour and with an error-coloured `DeleteForever` icon. Tapping it calls `viewModel.openDeleteDialog(child)`.
  - Render `DeleteChildAccountDialog` at screen level when `uiState.deleteTarget != null`, passing `LocalContext.current` for the Google path.
  - The two actions must be visually distinct and never side by side with the same style (FR-002, FR-010).
- [X] T039 [US2] Checkpoint: `./gradlew assembleDebug` and `./gradlew testDebugUnitTest --tests "*AccountDeletionCoordinator*" --tests "*AuthRepositoryDeleteAccount*"` are green. Then run quickstart V4, V4b, V4c, V4d and V13.
  - **Status**: build and unit tests are green, and the Dashboard refresh IS implemented (the Dashboard does not reload a parent's devices on resume, so V4d would have failed without it). The on-device runs (V4, V4b, V4c, V4d, V13) need throwaway accounts and are deferred to T057 and NOT yet done.
  - FR-023 check (V4d): after a child is deleted, open Dashboard, Alerts, Devices and Screen-time limits **without restarting the app**. None may show the deleted child or its devices.
  - Those ViewModels load in `init`, and the Children screen is reached from the Dashboard, so the Dashboard is still on the back stack. If the Dashboard still shows the child after returning from Children, make it refresh by setting a `children_changed` flag through `navController.previousBackStackEntry?.savedStateHandle` in `…/presentation/navigation/NavGraph.kt`. `DashboardScreen` reads that flag and calls its existing refresh. The edit must stay inside NavGraph and DashboardScreen, and no route may change.

**Checkpoint**: US1 and US2 together cover every P1 requirement.

---

## Phase 5: User Story 3 - Unlinked child deletes their own account (Priority: P2)

**Goal**: an unlinked child uses the same screen with child-appropriate wording and no mention of children. After success all protection stops without any tamper or "service stopped" alert and never restarts, and Login shows the uninstall note.

**Independent Test**: quickstart V6 on a throwaway unlinked child with content filtering on and flagged images.

**Depends on**: US1, because it reuses DeleteAccountScreen, the ViewModel and the coordinator.

### Tests for User Story 3

- [X] T040 [P] [US3] Extend `test/…/presentation/deleteaccount/DeleteAccountViewModelTest.kt`:
  - With role CHILD, `linkedChildCount` stays null and `getLinkedChildren` is never called.
  - `hasReviewOriginals` follows `ImageBlurManager.getPendingReviews()`.
- [X] T041 [P] [US3] Extend `test/…/data/account/AccountTeardownManagerTest.kt`: when the role captured before the wipe is child, `completeTeardown(showNotice = true, childDevice = true)`, and `isContentFilteringEnabled = false` is recorded **before** `stopVpn`.

### Implementation for User Story 3

- [X] T042 [US3] In `…/presentation/deleteaccount/DeleteAccountScreen.kt`, render the child variant of the consequences list when `role == CHILD`: intro, account, `delete_account_consequence_device_data`, and `delete_account_consequence_blurred_photos` only when `hasReviewOriginals`. **No** children line. Use child-friendly wording through the same keys.
- [X] T043 [US3] Confirm in `…/data/account/AccountTeardownManager.kt` that the role (`preferencesManager.isChild`) is captured into a local variable **before** `preferencesManager.clearAll()` and passed to `completeTeardown(childDevice = …)`. Also add the missing log line `teardown role=child|parent` (the role word only).
- [X] T044 [US3] Checkpoint: `./gradlew assembleDebug` plus the tests. Run quickstart V6:
  - **Status**: build and unit tests are green (US3 code paths: child wording, role capture, filtering-off-before-VPN-stop, uninstall note). The on-device V6 run needs a throwaway unlinked child and is deferred to T057; it is NOT yet done.
  - no VPN icon and no monitoring notification;
  - `adb shell dumpsys activity services com.safeguard.parentalcontrol` is empty for 1 minute and after a reboot;
  - no `vpn_disconnected` or `service_stopped` alert on the backend;
  - no `files/image_backup`;
  - the Login uninstall note opens the app details settings.

---

## Phase 6: User Story 4 - Supervised child attempts to delete their own account (Priority: P2)

**Goal**: a paired child submits, the server returns 403, the child sees "Only your parent can delete this account. Please ask them.", nothing is deleted, and the child stays signed in with protection running. The server decides, not the local role (FR-013).

**Independent Test**: quickstart V3.

**Depends on**: US1.

### Tests for User Story 4

- [X] T045 [P] [US4] Extend `test/…/presentation/deleteaccount/DeleteAccountViewModelTest.kt`:
  - `Failed(SupervisedChild)` sets `error` and `blockingNotice` to `delete_account_error_supervised`, leaves `credentialError` null and `deleted` false, and never mentions a wrong password.
  - The ViewModel **never** blocks submission because of `preferencesManager.hasLinkedParent` or the role. A child with `hasLinkedParent=true` can still submit, and the server decides.
- [X] T046 [P] [US4] Extend `test/…/data/account/AccountDeletionCoordinatorTest.kt`: a 403 clears the marker, gives `Failed(SupervisedChild)`, and teardown is **not** called.

### Implementation for User Story 4

- [X] T047 [US4] Verify, and fix if needed, in `…/presentation/deleteaccount/DeleteAccountViewModel.kt` and `DeleteAccountScreen.kt` that:
  - **Status**: verified. The SupervisedChild message is a Long Snackbar with no action (`errorRetryable=false`), it also stays as `blockingNotice` until `onCredentialChange`, `canSubmit` does not depend on either, and the only read of `hasLinkedParent` in the deletion code is the coordinator writing the recovery marker. The Settings item has no role condition.
  - the SupervisedChild message is shown as a Long Snackbar with no Retry;
  - it stays as `blockingNotice` under the button until the credential changes;
  - the submit button stays enabled, so the child can still see the flow;
  - no code path reads `hasLinkedParent` to hide the Settings entry or disable submission (FR-001, FR-013).
- [X] T048 [US4] Checkpoint: `./gradlew assembleDebug` plus the tests. Run quickstart V3. Postman "Sanity: account still alive" for the child returns 200, and the monitoring notification is still present.
  - **Status**: build and unit tests are green (the 403 path, the VM refusal message, and the rule that the local role never blocks submit). The on-device V3 run is deferred to T057; it is NOT yet done.

---

## Phase 7: User Story 5 - Anyone can learn how deletion works (Priority: P3)

**Goal**: Settings → About → "How account deletion works" opens the public `{root}/account-deletion` page in a browser.

**Independent Test**: quickstart V12.

### Tests for User Story 5

- [X] T049 [P] [US5] Write `test/…/util/AccountDeletionLinksTest.kt`. Each case uses `publicDeletionPageUrl(base)`:
  - `https://bw.noor.net:8090/api/v1/` → `https://bw.noor.net:8090/account-deletion`
  - `https://bw.noor.net:8090/api/v1` → the same
  - `https://h/x/api/v1/` → `https://h/x/account-deletion`
  - `https://h/` (no `/api/v1`) → `https://h/account-deletion`

### Implementation for User Story 5

- [X] T050 [P] [US5] Create `…/util/AccountDeletionLinks.kt` containing `object AccountDeletionLinks { fun publicDeletionPageUrl(baseUrl: String = BuildConfig.API_BASE_URL): String }`, which trims trailing `/`, removes a trailing `/api/v1`, and appends `/account-deletion`. Must make T049 pass.
- [X] T051 [US5] In the About section of `…/presentation/settings/SettingsScreen.kt`, add a `Divider` and a `SettingsItem(icon = Icons.AutoMirrored.Filled.HelpOutline, title = settings_deletion_help, subtitle = settings_deletion_help_desc)`. Its `onClick` runs `context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AccountDeletionLinks.publicDeletionPageUrl())))` inside a try block; on `ActivityNotFoundException`, show a Snackbar with `settings_deletion_help_no_browser(url)` through the existing `snackbarHostState`. The entry is visible to every signed-in role.
- [X] T052 [US5] Checkpoint: `./gradlew assembleDebug` plus `--tests "*AccountDeletionLinks*"`. Run quickstart V12.
  - **Status**: build and `AccountDeletionLinksTest` are green. The on-device V12 run (browser opens the page, Snackbar fallback) is deferred to T057; it is NOT yet done.

---

## Phase 8: Polish & Cross-Cutting Concerns

- [X] T053 [P] Grep `app/src/main/java` for `Timber` lines added by this feature. Confirm that none of them interpolates a password, confirmation, email, token, user or child id, or name, and that they all use the `AccountDeletion` tag (D7).
  - **Status**: audited: every Timber line added by this feature uses the `AccountDeletion` tag and none interpolates a password, confirmation, email, token, id or name.
- [X] T054 [P] Grep `…/presentation/deleteaccount/`, `…/presentation/children/DeleteChildAccountDialog.kt` and the edited parts of `SettingsScreen.kt`, `ChildrenScreen.kt` and `LoginScreen.kt` for hard-coded user-visible strings. Every one must come from `R.string`, with a matching key in `values-ar/strings.xml` (compare the key sets for the `delete_account_`, `children_delete_`, `children_remove`, `settings_delete`, `settings_deletion` and `login_` prefixes).
  - **Status**: English and Arabic key sets match for every prefix (`delete_account_` 26/26, `children_delete_` 10/10, `children_remove` 3/3, `children_cd_` 1/1, `settings_delete` 2/2, `settings_deletion` 3/3, `login_account` 2/2, `login_uninstall` 4/4, `login_session` 1/1). No hard-coded user-visible text in the new UI files or ViewModels.
- [X] T055 [P] Confirm that no guardrail file changed: run the command below; its output must be empty. The branch is based on 013, so a diff against `main` would include 013's own service edits.
  - **Status**: the guardrail diff is empty, both committed (`013-play-store-release-readiness...HEAD`) and in the working tree (also `app/build.gradle`, `proguard-rules.pro` and `AndroidManifest.xml` are untouched).

  ```sh
  git diff --stat 013-play-store-release-readiness...HEAD -- app/src/main/java/com/safeguard/parentalcontrol/{ml,service,receiver,worker,di}/ app/src/main/java/com/safeguard/parentalcontrol/data/remote/AuthInterceptor.kt app/src/main/java/com/safeguard/parentalcontrol/data/remote/NetworkResult.kt app/src/main/java/com/safeguard/parentalcontrol/util/ImageBlurManager.kt app/src/main/res/xml/
  ```
- [ ] T056 Run the full `./gradlew testDebugUnitTest` and `./gradlew assembleDebug`. Both must be green.
  - **Status**: `assembleDebug` is green. `testDebugUnitTest` ran 199 tests: 197 pass, 2 fail (`WordPieceTokenizerTest` english and arabic). Those are NOT caused by this feature: `ml/` and its test resources have no diff against HEAD, and `git ls-files --eol` shows `en_vocab.txt`/`ar_vocab.txt` are LF in the index but CRLF in this working tree (`core.autocrlf=true`), which corrupts the vocab. Left open until that is resolved.
- [ ] T057 Run the full quickstart V1–V14 on throwaway accounts only. That includes V4d (FR-023) and the V14 tamper matrix (SC-009). Record the results, plus the observations for V4 (the child device after a remote deletion) and V5 (whether a SyncWorker toast appears), in a new "Verification results" section of `specs/014-account-deletion/quickstart.md`.
- [X] T058 [P] Append the plan's "Known limitations & follow-ups" (FR-025 needs backend Q6, the SyncWorker gate, the receivers reading encrypted prefs as plain prefs, migrating logout to the teardown manager, and the English-only MainActivity toasts), together with the backend questions Q1–Q7, to the release notes or tracker. Mark the spec checklist `specs/014-account-deletion/checklists/requirements.md` as implementation-complete.
  - **Status**: the limitations, follow-ups and Q1 to Q7 were appended as section 12 of `docs/release-checklist.md`, and `checklists/requirements.md` carries an implementation-status note.

---

## Dependencies & Execution Order

### Phase dependencies

- **Setup (Phase 1)**: none. Its strings unblock all UI tasks.
- **Foundational (Phase 2)**: depends on Setup, and blocks every story.
- **US1 (Phase 3)**: depends on Foundational. This is the MVP.
- **US2 (Phase 4)**: depends on Foundational only (it does not need US1's screen). It can run in parallel with US1, except that T035 and T024 both edit `AccountDeletionCoordinator.kt`, so those two must be sequenced.
- **US3 (Phase 5)** and **US4 (Phase 6)**: depend on US1 (the shared screen and ViewModel).
- **US5 (Phase 7)**: depends on Setup only. It can run any time after Phase 1, but T051 and T027 both edit `SettingsScreen.kt`, so those two must be sequenced.
- **Polish (Phase 8)**: after every story you intend to ship.

### Within each story

Tests fail first, then models and state, then the coordinator or repository, then the ViewModel, then the Screen and navigation, then the checkpoint.

### Shared-file sequencing (not [P] with each other)

| File | Tasks |
|---|---|
| `AccountDeletionCoordinator.kt` | T016 → T024 → T035 |
| `AuthRepository.kt` | T010 → T034 |
| `MainActivity.kt` | T017 → T025 |
| `SettingsScreen.kt` | T027 → T051 |
| `DeleteAccountViewModel.kt` / `DeleteAccountScreen.kt` | T022/T023 → T042 → T047 |
| `AccountTeardownManager.kt` | T015 → T043 |
| `DeleteAccountViewModelTest.kt` | T020 → T040 → T045 |
| `AccountDeletionCoordinatorTest.kt` | T019 → T031 → T046 |

### Parallel opportunities

- Phase 2: T004, T005 and T006 (tests), then T007, T008, T009, T011, T012, T013 and T014 (different files).
- US1: T019, T020 and T021 together, and T028 in parallel with T022/T023.
- US2: T031, T032, T033 and T037 together.
- US5: T049 and T050 can start right after Phase 1.
- Polish: T053, T054, T055 and T058.

## Parallel Example: Phase 2 (Foundational)

```text
Task: "T004 DeletionErrorTest in app/src/test/.../data/repository/DeletionErrorTest.kt"
Task: "T005 AuthRepositoryDeleteAccountTest in app/src/test/.../data/repository/AuthRepositoryDeleteAccountTest.kt"
Task: "T006 AccountTeardownManagerTest in app/src/test/.../data/account/AccountTeardownManagerTest.kt"
# then
Task: "T007 DeleteAccountRequest in .../data/model/Models.kt"
Task: "T008 ApiService methods in .../data/remote/ApiService.kt"
Task: "T009 DeletionError in .../data/repository/DeletionError.kt"
Task: "T013 AccountDeletionState in .../util/AccountDeletionState.kt"
Task: "T014 DeviceTeardownOps in .../data/account/DeviceTeardownOps.kt"
```

## Parallel Example: User Story 2

```text
Task: "T031 child-path tests in AccountDeletionCoordinatorTest.kt"
Task: "T033 FamilyRepository.deleteChildAccount"
Task: "T037 DeleteChildAccountDialog.kt"
```

## Implementation Strategy

### MVP first (US1 only)

1. Phase 1 (strings), then Phase 2 (engine), then Phase 3 (US1).
2. **Stop and validate** with quickstart V1, V2, V5, V7, V8, V9 and V10. A parent can delete their own account, which is enough to answer the Play Data Safety deletion question.

### Incremental delivery

1. MVP (US1).
2. US2, the other P1: child deletion plus the unlink wording.
3. US3 and US4 (P2): child-device self-deletion and the refusal.
4. US5 (P3): the help link.
5. Polish (Phase 8), then the full quickstart.

### Release gate

Play submission needs **US1 + US3 + US4** (every account holder can delete, or is correctly refused) plus **US5** (the link Play Console expects). US2 is a P1 product requirement and ships in the same release.

## Notes

- [P] means a different file and no dependency on an unfinished task.
- Every task names its file. Where a task says "verify", fix any gap in that same file.
- Commit after each checkpoint task (T003, T018, T030, T039, T044, T048, T052, T056).
- **Never** test against real family accounts. Use Postman folder 0 throwaway accounts only.
