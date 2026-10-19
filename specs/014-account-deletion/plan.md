# Implementation Plan: Permanent Account Deletion

**Branch**: `014-account-deletion` | **Date**: 2026-09-24 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/014-account-deletion/spec.md`, plus the plan brief supplied with `/speckit-plan` (tech context, API contract verified in production on 2026-09-20, design decisions D1 to D7, phases P1 to P6, guardrails).

## Summary

This feature adds in-app, permanent account deletion. It is the last Google Play data-deletion blocker. It covers three flows:

- a parent or unlinked child deleting their own account (`DELETE /auth/account` with a password or `DELETE`);
- a parent deleting one linked child (`DELETE /family/child/{childId}`, after the parent re-authenticates);
- a supervised child's refusal (server 403).

On a confirmed deletion, a single ordered, non-cancellable teardown stops work, alarms and services **before** it clears data and tokens. This order means no tamper alert, "service stopped" alert, restart or misleading "session ended" toast can happen.

Research changed four parts of the brief:

1. **No service or receiver edits are needed.** Every tamper and restart path is already driven by preferences that the teardown controls (research R6).
2. **Child deletion needs parent re-authentication through `POST auth/login`** (Google parents use a fresh Google sign-in), because the endpoint takes no body (research RD-1).
3. **Remote-deletion detection on child devices (FR-025) cannot be built.** The server returns the same 401 for a deleted account as for any other session loss, so the spec's fallback applies and it is recorded as a known limitation plus backend question Q6 (research RD-2).
4. **Errors use a Snackbar, not a banner** (constitution rule 10; research RD-4).

## Technical Context

**Language/Version**: Kotlin 2.0.21 (KSP), JDK 21, `jvmTarget` 17

**Primary Dependencies**: Jetpack Compose (BOM 2024.09.00) + Material 3, Hilt 2.51.1, Retrofit 2.9.0, OkHttp 4.12.0, Gson, EncryptedSharedPreferences (`TokenManager`, `PreferencesManager`), WorkManager 2.9.0, Navigation Compose 2.7.6, Room 2.6.1, DataStore 1.0.0, Firebase (Messaging, Analytics, Credential Manager Google Sign-In), Timber. **No new libraries.**

**Storage**: EncryptedSharedPreferences (`safeguard_prefs`, `secure_token_prefs`), Room `safeguard.db` (pending alerts only), DataStore (`word_lists`, `alert_cooldown`), OkHttp disk cache `cacheDir/http_cache`, `filesDir/image_backup`. New: a plain prefs file `account_deletion_state` holding the no-PII marker.

**Testing**: JUnit 4 + MockK + kotlinx-coroutines-test (`MainDispatcherRule` exists), `./gradlew testDebugUnitTest`. Manual device verification follows [quickstart.md](quickstart.md).

**Target Platform**: Android, minSdk 26, targetSdk/compileSdk 36

**Project Type**: mobile app (single Android module `app/`)

**Performance Goals**: Teardown finishes in under 2 s on a mid-range device, excluding network. The deletion request keeps the default OkHttp timeouts.

**Constraints**:
- Exactly one request per target (FR-015).
- Teardown cannot be cancelled once the server confirms (FR-016).
- No PII in logs (D7).
- The password never goes into `SavedStateHandle` or the logs.
- English and Arabic, right-to-left.
- Checkpoint `./gradlew assembleDebug` after every phase.

**Scale/Scope**: 1 new screen, 1 new dialog, 2 endpoints, 10 new source files, 6 new test files, and 16 modified files (see below).

## Constitution Check

*Gate: must pass before Phase 0 research. Re-checked after Phase 1 design.*

| Rule / Principle | Status | Evidence |
|---|---|---|
| I. Child safety & privacy | ✅ | There is a data and privacy note in the spec. The credential is sent once and never stored. The review originals are wiped. No new data is collected. The supervised-child escape routes are closed: server 403, parent re-authentication, and a marker that can never stop protection on its own (RD-3). |
| II. MVVM + Clean Architecture | ✅ | `presentation/deleteaccount/` has a Screen, a `@HiltViewModel` and a `data class` UiState, with StateFlow + `update{}` + `collectAsStateWithLifecycle`. |
| III. Two-role architecture | ✅ | Parent code holds no enforcement logic. Child enforcement is untouched except for the deliberate stop on the child's own confirmed deletion. |
| IV. Services & permissions | ✅ | **Zero** service, receiver or worker logic changes (research R6). No new manifest components or permissions. |
| V. Security & secrets | ✅ | Only `TokenManager` touches tokens (a new `clearTokensDueToAccountDeletion()`). Re-auth tokens are saved through `TokenManager`. `AuthInterceptor`, pinning and `network_security_config` are unchanged. |
| Rule 1 feature sub-package | ✅ | `presentation/deleteaccount/{DeleteAccountScreen,DeleteAccountViewModel,DeleteAccountUiState}.kt`. Child deletion extends the existing `presentation/children/`. |
| Rule 2 `@HiltViewModel` | ✅ | |
| Rule 3 `@Singleton` repositories | ✅ | The new coordinator and teardown manager are `@Singleton`. |
| Rule 4 VM → Repository → `safeApiCall` → ApiService | ✅ (see Complexity Tracking) | The coordinator sits between the ViewModel and the repositories. No layer is skipped; one application-scoped layer is added. |
| Rule 5 tokens only in TokenManager | ✅ | |
| Rule 6 special permissions | N/A | |
| Rule 7 services contain no logic | ✅ | Unchanged |
| Rule 8 workers | ✅ | Only cancelled |
| Rule 9 `NetworkResult<T>` only | ✅ | Repositories return `NetworkResult<Unit>`. `DeletionError` is mapped **outside** the repositories. |
| Rule 10 Snackbar errors | ✅ | Research RD-4. The `AlertDialog` is used for confirmation only. |
| Rule 11 ProGuard | ✅ | No new Service, Receiver or Worker. `DeleteAccountRequest` is in `data.model`, which is already covered by `-keep class …data.model.**`. |
| Rule 12 `noCompress "tflite"` | ✅ | `build.gradle` is untouched. |

**Post-design re-check (after Phase 1)**: passes. The one addition to justify is the coordinator layer (below).

## Project Structure

### Documentation (this feature)

```text
specs/014-account-deletion/
├── spec.md
├── plan.md                    # this file
├── research.md                # R1–R10 + RD-1..RD-5
├── data-model.md
├── quickstart.md              # V1–V13 manual verification
├── contracts/
│   ├── account-deletion.md    # REST contract + backend questions Q1–Q7
│   └── teardown.md            # AccountTeardownManager step contract
├── checklists/requirements.md
└── tasks.md                   # /speckit-tasks (not created here)
```

### Source Code

Root: `app/src/main/java/com/safeguard/parentalcontrol/`

**Files to CREATE**

| File | Purpose |
|---|---|
| `data/repository/DeletionError.kt` | Sealed class + pure code→error mappers `fromSelfDelete`, `fromChildDelete`, `fromReauth` (D3) |
| `data/account/AccountDeletionCoordinator.kt` | `@Singleton`. Application-scoped `SupervisorJob` scope, single flight per target (`Mutex`), `selfState` / `childStates` StateFlows, the marker write before sending, launch recovery (`resumePendingIfAny()`, RD-3), parent re-authentication then child deletion (RD-1) |
| `data/account/AccountTeardownManager.kt` | `@Singleton`. The ordered, idempotent `teardown(reason)` inside `NonCancellable` ([contracts/teardown.md](contracts/teardown.md)) |
| `data/account/DeviceTeardownOps.kt` | `@Singleton`. A thin wrapper over WorkManager, AlarmManager, the service stop intents, `NotificationManagerCompat`, file deletion, FCM `deleteToken`, Analytics reset. Exists so the teardown order can be tested with fakes. |
| `util/AccountDeletionState.kt` | `@Singleton`. The plain `account_deletion_state` prefs (with `commit()`): pending marker, teardown flag, post-deletion notice flags |
| `util/AccountDeletionLinks.kt` | `publicDeletionPageUrl(baseUrl = BuildConfig.API_BASE_URL)`: strips `/api/v1`, appends `/account-deletion` (R9) |
| `presentation/deleteaccount/DeleteAccountUiState.kt` | Data model §5 |
| `presentation/deleteaccount/DeleteAccountViewModel.kt` | Loads role, `auth/me` provider, child count, review originals; credential in memory; final-confirm dialog; observes the coordinator |
| `presentation/deleteaccount/DeleteAccountScreen.kt` | Consequences list, credential field + visibility toggle, mode switch link, destructive (error-colour, non-primary) button, second-step `AlertDialog`, `BackHandler` while loading, Snackbar |
| `presentation/children/DeleteChildAccountDialog.kt` | Type-name field + parent password (or a "Confirm with Google" button) + the difference-from-unlinking explanation |
| `app/src/test/.../data/repository/DeletionErrorTest.kt`, `…/AuthRepositoryDeleteAccountTest.kt`, `…/data/account/AccountTeardownManagerTest.kt`, `…/data/account/AccountDeletionCoordinatorTest.kt`, `…/presentation/deleteaccount/DeleteAccountViewModelTest.kt`, `…/util/AccountDeletionLinksTest.kt` | P6 |

**Files to MODIFY**

| File | Change |
|---|---|
| `data/model/Models.kt` | Add `DeleteAccountRequest` with `forPassword()` / `forGoogle()` |
| `data/remote/ApiService.kt` | Add `@HTTP(method="DELETE", path="auth/account", hasBody=true) deleteAccount(...)` returning `Response<Unit>`, and `@DELETE("family/child/{childId}") deleteChildAccount(...)` returning `Response<Unit>` |
| `data/repository/AuthRepository.kt` | Add `deleteAccount(request)`, `reauthenticateWithPassword(password)` and `reauthenticateWithGoogle(idToken)`. All return `NetworkResult<Unit>` and remap a 2xx-empty "error" to success (D2). Re-auth checks `user.id == preferencesManager.userId`. `logout()` is unchanged. |
| `data/repository/FamilyRepository.kt` | Add `deleteChildAccount(childId)` returning `NetworkResult<Unit>` (the 2xx-empty remap) |
| `data/repository/AlertRepository.kt` | Add `clearCooldowns()` (clears the `alert_cooldown` DataStore), 3 lines |
| `util/TokenManager.kt` | Add `AuthEvent.AccountDeleted` and `clearTokensDueToAccountDeletion(emit: Boolean)` |
| `presentation/MainActivity.kt` | Handle `AccountDeleted` (`navigateToLogin`, no toast). When recovery is pending, run the suspending `resumePendingIfAny()` in `lifecycleScope` and call `startMonitoringServiceIfNeeded()` only after it finishes. Retry recovery when `NetworkMonitor` reports that connectivity is back. |
| `presentation/navigation/NavGraph.kt` | **One** new route `Screen.DeleteAccount("delete_account")`; Settings gets `onNavigateToDeleteAccount`; `DeleteAccount.onDeleted` navigates to Login with `popUpTo(0)` |
| `presentation/settings/SettingsScreen.kt` | Account section: a "Delete account" item **below** Log Out, in the error colour. About section: a "How account deletion works" item that opens the URL with `ACTION_VIEW`, with a Snackbar fallback |
| `presentation/children/ChildrenScreen.kt` | `ChildCard`: separate "Delete account permanently" entry (error colour, separated from Remove); the Remove wording is updated |
| `presentation/children/ChildrenViewModel.kt` | Delete-dialog state (data model §6); calls the coordinator; on success removes by `childId`; on `ChildNotFound` reloads + shows a message |
| `presentation/auth/LoginScreen.kt` | Shows the "Account deleted" Snackbar and, on a child device, the uninstall note card with an "Open app settings" button (`ACTION_APPLICATION_DETAILS_SETTINGS`), both read from `AccountDeletionState` and dismissed on tap or on a successful sign-in |
| `presentation/auth/AuthViewModel.kt` | Adds a `deletionNotice` field to the **existing** `AuthUiState`, not a second flow (Principle II), and dismisses it (reads `AccountDeletionState`) |
| `presentation/dashboard/DashboardScreen.kt` (*conditional*) | Only if quickstart V4d shows a stale child: refresh when NavGraph sets a `children_changed` flag through `savedStateHandle` (FR-023). No route changes. |
| `res/values/strings.xml`, `res/values-ar/strings.xml` | New `delete_account_*` / `children_delete_*` keys; rewritten `children_remove_*` |

**Explicitly NOT modified (guardrails)**:
- `ml/*`
- The internals of `ContentFilterVpnService`, `MonitoringService`, `TextMonitoringAccessibilityService` and every receiver
- All worker logic
- `AuthInterceptor`
- `NetworkModule` pinning and `network_security_config`
- DI modules (every new class uses an `@Inject` constructor; `okhttp3.Cache` and `FirebaseAnalytics` are already bindings)
- Existing routes
- The logout flow
- `ImageBlurManager`

**Structure Decision**: the single Android app module, package-by-feature. The new `data/account/` package holds the deletion orchestration. It is not a repository (it owns no API surface); it coordinates the existing repositories and platform teardown.

## Phases (checkpoint `./gradlew assembleDebug` after each)

| Phase | Scope | Exit check |
|---|---|---|
| **P1 Data layer** | `DeleteAccountRequest`, the ApiService methods, `AuthRepository.deleteAccount` + re-auth, `FamilyRepository.deleteChildAccount`, `DeletionError` + mappers, `AlertRepository.clearCooldowns` | Builds. `DeletionErrorTest` and `AuthRepositoryDeleteAccountTest` are green. |
| **P2 Teardown** | `AccountDeletionState`, `DeviceTeardownOps`, `AccountTeardownManager`, `AccountDeletionCoordinator` (self-deletion + recovery), `AuthEvent.AccountDeleted`, `MainActivity` handling + `resumePendingIfAny` | Builds. The teardown order test and the coordinator single-flight / recovery tests are green. |
| **P3 Delete-account screen** | UiState, ViewModel, Screen, `Screen.DeleteAccount` route, the Settings entry, the Login notice | Builds. ViewModel tests are green. Quickstart V1, V2, V5, V7 and V8 pass on a device. |
| **P4 Children** | `DeleteChildAccountDialog`, ChildrenViewModel/Screen, the coordinator's child path (re-auth then DELETE), the unlink wording | Builds. Quickstart V4, V4b, V4c and V13 pass. |
| **P5 Strings & link** | All English + Arabic strings, `AccountDeletionLinks`, the About link | Builds. `AccountDeletionLinksTest` is green. Quickstart V11 and V12 pass. |
| **P6 Tests** | Mapping for 400/401/403/404/429/network/unknown/2xx-empty; teardown order and failure tolerance with fakes; coordinator single flight, marker and recovery branches (RD-3); ViewModel `canSubmit`, the WrongPassword-only-inline rule, and that 429 never produces wrong-password text | `./gradlew testDebugUnitTest` is green. Full quickstart V1 to V13 on throwaway accounts. |

## Key design notes (details in research and contracts)

- **D1**: `@HTTP(method="DELETE", hasBody=true)`. Gson has no `serializeNulls`, so the null field is omitted (checked in `NetworkModule.provideGson`).
- **D2**: success is any 2xx. A 2xx with an empty body (and every 204) comes back from `safeApiCall` as `Error(code in 200..299)` and is remapped to `Success(Unit)` inside the repository. `safeApiCall` is not changed.
- **D3**: `DeletionError`, keyed on the status code only. Only `WrongPassword` produces wrong-password wording.
- **D4**: teardown order is: marker, filtering pref off, cancel work and alarm, stop services, wipe, prefs, tokens (last), final cancel. It runs in `NonCancellable`. No service-side flag is needed (research R6).
- **D5**: `AuthEvent.AccountDeleted` leads to Login with no toast. The confirmation itself comes from the durable notice flag, so it survives a cold start.
- **D6**: UI as in the brief, except that a Snackbar replaces the banner (RD-4). Child deletion adds parent re-auth (RD-1).
- **D7**: `Timber.tag("AccountDeletion")` logs start, `code=… mapped=…` and each teardown step. It never logs the credential, email, tokens, names or ids.

## Known limitations & follow-ups

1. **FR-025 (stop protection when the server confirms a remote deletion) is not implemented.** The server returns an indistinguishable 401, so the child device keeps today's behaviour (the spec's fallback). The fix is backend Q6.
2. The pre-existing `SyncWorker` runs without a login gate on every cold start and may cause a "Session expired" toast after deletion, as it does after logout today (research R6-1). This is a follow-up outside the worker guardrail.
3. The pre-existing `TamperDetectionReceiver` and `ServiceRestartReceiver` read the encrypted prefs file as plain prefs, so both are effectively inert (research R6-2). This is a follow-up for features 004 / 010.
4. Migrating logout to `AccountTeardownManager` (logout currently leaves `MonitoringService` running) is a follow-up.
5. The `MainActivity` session toasts are hard-coded English. This is a follow-up.

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|---|---|---|
| An extra `@Singleton AccountDeletionCoordinator` layer between ViewModels and repositories (rule 4 lists VM → Repository directly) | FR-015 and FR-016 require the request and teardown to outlive the ViewModel and to be single-flight across rotation and navigation. `viewModelScope` is cancelled when the user leaves the screen, and there is no application-scope binding (adding one would touch DI modules, which the guardrails forbid). | Calling the repository from `viewModelScope`: a success followed by leaving the screen would cancel the teardown, leaving a deleted account with live local data. Putting the scope and state inside `AuthRepository`: this mixes a UI-facing StateFlow and platform teardown into a network repository and breaks rule 9's "NetworkResult only" shape. |
