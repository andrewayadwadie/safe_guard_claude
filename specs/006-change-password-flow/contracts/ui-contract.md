# UI Contract: Change Password Flow

**Feature**: 006-change-password-flow | **Date**: 2026-07-06

Backend contract pre-exists (`POST auth/change-password`, ApiService.kt:39) and is NOT modified. Contracts below cover the new UI surface and nav wiring.

## ViewModel contract

### `ChangePasswordViewModel.changePassword(currentPassword: String, newPassword: String)`

| Aspect | Contract |
|---|---|
| Visibility | `public`, called from Composable submit action |
| Pre-network validation | In order: both non-blank → new length ≥ 8 → new ≠ current. First failure sets `uiState.error` and returns without any repository call |
| Happy path | Sets `isLoading=true, error=null`; calls `authRepository.changePassword(current, new)`; `Success` → `isLoading=false, isSuccess=true`; `Error` → `isLoading=false, error=result.message`; `Loading` branch ignored |
| Threading | `viewModelScope.launch`; repository already does `withContext(Dispatchers.IO)` |
| Reentrancy | UI disables submit while `isLoading`; duplicate calls during flight are prevented at the screen level (FR-004) |

### `ChangePasswordViewModel.clearError()`

Sets `error = null` via `_uiState.update`. Called by screen after Snackbar display (constitution Rule 10).

## Screen contract

### `ChangePasswordScreen(onNavigateBack: () -> Unit, viewModel: ChangePasswordViewModel = hiltViewModel())`

| Aspect | Contract |
|---|---|
| Container | `Scaffold` + `TopAppBar(title = "Change Password", navigationIcon = ArrowBack → onNavigateBack)` + `SnackbarHost(snackbarHostState)` |
| Field 1 | `HarisTextField` — label "Current Password", `Icons.Default.Lock` leading, Visibility/VisibilityOff trailing toggle, `PasswordVisualTransformation` when hidden, `KeyboardType.Password`, `ImeAction.Next` |
| Field 2 | Same as Field 1 but label "New Password", independent toggle, `ImeAction.Done` |
| Submit | `HarisPrimaryButton(text = "Change Password")`; `enabled = !isLoading && current.isNotBlank() && new.isNotBlank()`; `CircularProgressIndicator` (18.dp) as leadingIcon while loading |
| Success effect | `LaunchedEffect(uiState.isSuccess)` → if true, `onNavigateBack()` — fires once (FR-005) |
| Error effect | `LaunchedEffect(uiState.error)` → `snackbarHostState.showSnackbar(error)` → `viewModel.clearError()` (FR-006, Rule 10) |
| State collection | `collectAsStateWithLifecycle()` only |
| Field count | Exactly 2 password inputs; no confirm-password field (guardrail) |

## SettingsScreen contract (modified)

| Aspect | Contract |
|---|---|
| New param | `onNavigateToChangePassword: () -> Unit = {}` (default keeps existing call sites/previews valid) |
| Row change | "Change Password" `SettingsItem`: `onClick = onNavigateToChangePassword`; `trailing` ("Coming soon") REMOVED. Icon/title/subtitle unchanged |
| Blast radius | No other row, section, param, or observer touched |

## NavGraph contract (modified)

| Aspect | Contract |
|---|---|
| Route | `data object ChangePassword : Screen("change_password")` |
| Settings wiring | `onNavigateToChangePassword = { navController.navigate(Screen.ChangePassword.route) }` inside existing `composable(Screen.Settings.route)` block |
| Destination | `composable(Screen.ChangePassword.route) { ChangePasswordScreen(onNavigateBack = { navController.popBackStack() }) }` |
| Import | `com.safeguard.parentalcontrol.presentation.settings.ChangePasswordScreen` |

## Non-goals (contract boundaries)

- No changes to `ApiService`, `AuthRepository`, `Models.kt`, DI modules, services, workers, receivers
- No forced sign-out / re-login on success (clarification 2026-07-06)
- No per-field inline error states; single Snackbar channel
- No strings.xml extraction
- No new design-system widgets
