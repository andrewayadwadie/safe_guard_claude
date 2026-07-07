# Tasks: Change Password Flow

**Input**: Design documents from `/specs/006-change-password-flow/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/ui-contract.md, quickstart.md

**Tests**: Not requested. Build checkpoint (`./gradlew assembleDebug`) is the only automated gate.

**Organization**: Single user story (US1, P1). No setup or foundational phase — data layer (`AuthRepository.changePassword`, `ApiService`, models, `NetworkResult`) and design system (`HarisTextField`, `HarisPrimaryButton`) pre-exist and are reused as-is.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to
- Include exact file paths in descriptions

## Path Conventions

Single Android module. Paths relative to repo root; source prefix: `app/src/main/java/com/safeguard/parentalcontrol/`.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: None required — no dependencies, DI, manifest, or resource changes.

*(No tasks.)*

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: None required — `AuthRepository.changePassword()` (data/repository/AuthRepository.kt:169), `ChangePasswordRequest`/`MessageResponse` (data/model/Models.kt:117/341), `HarisTextField`/`HarisPrimaryButton` (presentation/designsystem/) all pre-exist.

*(No tasks.)*

---

## Phase 3: User Story 1 - Change my account password (Priority: P1) 🎯 MVP

**Goal**: Settings "Change Password" row opens a dedicated screen; user enters current + new password; client validation gates bad input; submit calls existing API; success pops back to Settings (session intact); failures surface via Snackbar with retry.

**Independent Test**: Tap "Change Password" in Settings → screen opens → enter valid current + new password → submit → returned to Settings still signed in → new password works on next sign-in. (Full script: quickstart.md S1–S5.)

### Implementation for User Story 1

- [X] T001 [P] [US1] Create `app/src/main/java/com/safeguard/parentalcontrol/presentation/settings/ChangePasswordViewModel.kt`: `ChangePasswordUiState(isLoading=false, error=null, isSuccess=false)` data class + `@HiltViewModel class ChangePasswordViewModel @Inject constructor(private val authRepository: AuthRepository)`. `fun changePassword(currentPassword, newPassword)`: pre-network validation in order — both non-blank ("Both fields are required"), new length ≥ 8 ("New password must be at least 8 characters"), new ≠ current ("New password must be different from current password") — each failure sets `error` via `_uiState.update` and returns; then `viewModelScope.launch` → `isLoading=true, error=null` → `when(authRepository.changePassword(...))`: `Success` → `isLoading=false, isSuccess=true`; `Error` → `isLoading=false, error=result.message`; `else -> {}`. Plus `fun clearError()`. Private `MutableStateFlow` + public `asStateFlow()`. (Contract: contracts/ui-contract.md § ViewModel contract; state: data-model.md)

- [X] T002 [P] [US1] Modify `app/src/main/java/com/safeguard/parentalcontrol/presentation/settings/SettingsScreen.kt`: add param `onNavigateToChangePassword: () -> Unit = {}` after `onNavigateToTextReview` (line ~46); in the "Change Password" `SettingsItem` (lines ~304-316) set `onClick = onNavigateToChangePassword` and DELETE the entire `trailing = { Text("Coming soon"...) }` block. Touch NOTHING else in the file. (Contract: contracts/ui-contract.md § SettingsScreen contract)

- [X] T003 [US1] Create `app/src/main/java/com/safeguard/parentalcontrol/presentation/settings/ChangePasswordScreen.kt`: `@Composable fun ChangePasswordScreen(onNavigateBack: () -> Unit, viewModel: ChangePasswordViewModel = hiltViewModel())`. `Scaffold` + `TopAppBar(title = "Change Password", navigationIcon = ArrowBack IconButton → onNavigateBack)` + `SnackbarHost`. Four `remember` vars: `currentPassword`, `newPassword`, `currentVisible`, `newVisible`. Two `HarisTextField`s copying LoginScreen.kt:165-186 pattern — labels "Current Password"/"New Password", `Icons.Default.Lock` leading, `Visibility/VisibilityOff` `IconButton` trailing toggle (independent per field), `PasswordVisualTransformation()` when hidden, `KeyboardType.Password`, first `ImeAction.Next`/second `ImeAction.Done`. `HarisPrimaryButton(text = "Change Password", enabled = !uiState.isLoading && both non-blank, leadingIcon = CircularProgressIndicator(18.dp, strokeWidth 2.dp) when loading, onClick = viewModel.changePassword(current, new))` per LoginScreen.kt:192-200. `LaunchedEffect(uiState.isSuccess) { if (uiState.isSuccess) onNavigateBack() }`. `LaunchedEffect(uiState.error) { error → snackbarHostState.showSnackbar(error); viewModel.clearError() }`. Collect with `collectAsStateWithLifecycle()`. Exactly 2 password fields — no confirm field. (Contract: contracts/ui-contract.md § Screen contract; depends on T001)

- [X] T004 [US1] Modify `app/src/main/java/com/safeguard/parentalcontrol/presentation/navigation/NavGraph.kt`: add `data object ChangePassword : Screen("change_password")` after `data object Settings` (line ~45); inside `composable(Screen.Settings.route)` block (line ~264) add `onNavigateToChangePassword = { navController.navigate(Screen.ChangePassword.route) }`; after the Settings composable block add `composable(Screen.ChangePassword.route) { ChangePasswordScreen(onNavigateBack = { navController.popBackStack() }) }`; add import `com.safeguard.parentalcontrol.presentation.settings.ChangePasswordScreen`. (Contract: contracts/ui-contract.md § NavGraph contract; depends on T002 + T003)

**Checkpoint**: Row navigates, validation blocks, submit works, success pops back, errors snackbar — US1 fully functional.

---

## Phase 4: Polish & Cross-Cutting Concerns

**Purpose**: Verify build cleanliness and scope discipline.

- [X] T005 Run `./gradlew assembleDebug` from repo root — MUST pass clean (build checkpoint from plan.md). NOTE: initial build failed on pre-existing `ContentFilterVpnService.kt:229` compile error inherited from stale local main; resolved by merging `origin/main` (which contains the fix) into this branch — after merge, BUILD SUCCESSFUL.
- [X] T006 Verify scope guardrails: `git diff --name-only origin/main...HEAD` app-source delta is ONLY `SettingsScreen.kt` + `NavGraph.kt` modified and `ChangePasswordViewModel.kt` + `ChangePasswordScreen.kt` created; NO changes to ApiService, AuthRepository, Models, DI, manifest, services, workers, receivers (quickstart.md S6)
- [ ] T007 Execute quickstart.md validation scenarios S1–S5 on device/emulator with backend reachable; confirm S6 regression guard (other Settings rows + Login screen unaffected) — **NOT RUN**: no `adb`/device available in this environment. Manual verification required before merge.

---

## Dependencies & Execution Order

### Phase Dependencies

- Phases 1–2: empty — start directly at Phase 3
- **Phase 3 (US1)**: no prerequisites
- **Phase 4 (Polish)**: depends on Phase 3 complete

### Task Dependencies

```text
T001 (ViewModel) ──┐
                   ├─▶ T003 (Screen; references ViewModel) ──┐
T002 (Settings param) ───────────────────────────────────────┼─▶ T004 (NavGraph wiring)
                                                             │
T004 ─▶ T005 (build) ─▶ T006 (scope check) ─▶ T007 (device validation)
```

### Parallel Opportunities

- **T001 ∥ T002**: different files, no shared symbols — can run together
- T003 waits on T001 (imports `ChangePasswordViewModel`); T004 waits on T002 (param must exist) + T003 (import must resolve)

---

## Implementation Strategy

Single-story MVP. T001+T002 in parallel → T003 → T004 → validate T005–T007. One coherent increment (~215 LOC total); no partial-delivery split at this size.

---

## Notes

- Inline strings only; no `strings.xml` extraction (guardrail)
- `HarisTextField`/`HarisPrimaryButton`, NOT raw `OutlinedTextField` (research.md R2)
- No forced sign-out on success — session stays valid (spec clarification 2026-07-06)
- Constitution: Rule 1 deviation pre-justified in plan.md Complexity Tracking
- Commit after T005 passes clean
