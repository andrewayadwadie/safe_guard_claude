# Research: Change Password Flow

**Feature**: 006-change-password-flow | **Date**: 2026-07-06

No NEEDS CLARIFICATION markers remained in Technical Context. Decisions below resolve pattern/reuse choices against the actual codebase.

## R1 — API call chain

- **Decision**: ViewModel → `AuthRepository.changePassword(currentPassword, newPassword)` → `safeApiCall { apiService.changePassword(request) }`. Nothing new in data layer.
- **Rationale**: Chain already exists end-to-end: `AuthRepository.kt:169-178`, `ApiService.kt:39` (`POST auth/change-password`), `ChangePasswordRequest` (`Models.kt:117`, snake_case `@SerializedName`), `MessageResponse` (`Models.kt:341`). Returns `NetworkResult<MessageResponse>` — constitution Rule 9 satisfied for free. Runs on `Dispatchers.IO` via `withContext` already.
- **Alternatives considered**: Calling `ApiService` from ViewModel — violates Rule 4; rejected.

## R2 — Text field component

- **Decision**: `HarisTextField` from `presentation/designsystem/`, NOT raw Material3 `OutlinedTextField`.
- **Rationale**: User brief said "OutlinedTextField ... matching LoginScreen styling". Post-003-restyle, `LoginScreen.kt` actually uses `HarisTextField` (LoginScreen.kt:33, 152, 165) — the design-system wrapper. Spec FR-007 requires matching the sign-in screen; matching means using the same component. Same param surface: label, placeholder, leadingIcon, trailingIcon, visualTransformation, keyboardOptions/Actions.
- **Alternatives considered**: Raw `OutlinedTextField` — would visually diverge from every other form field in the app; rejected.

## R3 — Password visibility toggle pattern

- **Decision**: Per-field `remember { mutableStateOf(false) }` visibility flag; trailing `IconButton` swapping `Icons.Default.Visibility` / `VisibilityOff`; `PasswordVisualTransformation()` when hidden, `VisualTransformation.None` when shown.
- **Rationale**: Verbatim pattern at `LoginScreen.kt:171-179`. Spec FR-002 requires individual toggles → two independent flags.
- **Alternatives considered**: Shared single toggle for both fields — fails FR-002 "individual show/hide toggle"; rejected.

## R4 — Submit button + loading state

- **Decision**: `HarisPrimaryButton` with `enabled = !uiState.isLoading && current.isNotBlank() && new.isNotBlank()` and `leadingIcon = { CircularProgressIndicator(18.dp, strokeWidth 2.dp) }` when loading.
- **Rationale**: Verbatim pattern at `LoginScreen.kt:192-200`. Disabled-while-loading satisfies FR-004 duplicate-submission guard.
- **Alternatives considered**: Overlay/full-screen loading — heavier than existing convention; rejected.

## R5 — Error + success side effects

- **Decision**: `LaunchedEffect(uiState.error)` → `snackbarHostState.showSnackbar(...)` → `viewModel.clearError()`. Separate `LaunchedEffect(uiState.isSuccess)` → `onNavigateBack()`.
- **Rationale**: Constitution Rule 10 mandates Snackbar + clearError exactly; `LoginScreen.kt:62-67` is the house pattern. Success-effect keyed on `isSuccess` fires once; navigation pop is idempotent. Clarification locked "stay signed in, return to Settings" → plain `popBackStack`, no logout logic.
- **Alternatives considered**: AlertDialog for errors — prohibited by Rule 10. Forced re-login on success — rejected by clarification session 2026-07-06.

## R6 — Client validation placement

- **Decision**: Validation in ViewModel `changePassword()` before any coroutine launch: blank check → length ≥ 8 → new ≠ current. Sets `uiState.error`, returns early — no network call.
- **Rationale**: FR-003 requires blocking pre-network. ViewModel placement keeps Composable logic-free (constitution II) and makes rules unit-testable. Error surfaces through the same Snackbar channel as server errors — one feedback path.
- **Alternatives considered**: Inline field `isError`/supportingText — richer UX but introduces per-field error state not requested by spec; Snackbar path is the established convention; rejected for scope.

## R7 — Navigation wiring

- **Decision**: `data object ChangePassword : Screen("change_password")`; navigate from Settings composable lambda; `composable(Screen.ChangePassword.route)` block with `onNavigateBack = { navController.popBackStack() }`. Default-value param `onNavigateToChangePassword: () -> Unit = {}` on `SettingsScreen`.
- **Rationale**: Identical shape to every argless route in `NavGraph.kt` (e.g., `WordList`, `TextMonitoringSettings` — NavGraph.kt:83-92, 379-390). Default `{}` keeps existing `SettingsScreen` call sites/previews compiling.
- **Alternatives considered**: Nav arguments for result passing — unnecessary; success is fully handled inside the new screen; rejected.
