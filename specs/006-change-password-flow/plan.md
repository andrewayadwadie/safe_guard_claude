# Implementation Plan: Change Password Flow

**Branch**: `006-change-password-flow` | **Date**: 2026-07-06 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/006-change-password-flow/spec.md`

## Summary

Wire the inert "Change Password" Settings row to a new `ChangePasswordScreen` (current + new password fields, client validation, submit) backed by a new `ChangePasswordViewModel` that calls the existing `AuthRepository.changePassword()`. On success pop back to Settings (session stays valid, per clarification); on error surface the message via Snackbar. Adds one nav route; touches only two existing files (`SettingsScreen.kt`, `NavGraph.kt`) plus two new files.

## Technical Context

**Language/Version**: Kotlin (JDK 21 toolchain, `jvmTarget 17`), Jetpack Compose (BOM 2023.10.01, Material 3)

**Primary Dependencies**: Hilt 2.51.1 (`@HiltViewModel`, `hiltViewModel()`), Navigation Compose 2.7.6, existing `AuthRepository` → `safeApiCall{}` → `ApiService.changePassword` (`POST auth/change-password`), Timber 5.0.1

**Storage**: None — password pair is transient request state only; nothing persisted

**Testing**: Build checkpoint `./gradlew assembleDebug` (no test tasks requested); manual quickstart scenarios

**Target Platform**: Android `minSdk 26` / `targetSdk 35`

**Project Type**: Mobile app (single Android module, package-by-feature)

**Performance Goals**: Standard interactive UI; single network call per submit; no jank concerns

**Constraints**: Reuse-only UI (design-system components `HarisTextField`, `HarisPrimaryButton`, Scaffold+TopAppBar, SnackbarHost — same as `LoginScreen.kt`); exactly 2 password fields; no strings.xml extraction; guardrails forbid touching ApiService/AuthRepository/Models/DI/services/workers

**Scale/Scope**: 2 new files (~200 LOC), 2 edited files (~15 LOC delta), 1 new nav route

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| # | Rule | Status | Notes |
|---|------|--------|-------|
| 1 | Feature sub-package with ViewModel/UiState/Screen | ⚠️ PASS (justified) | Files placed in `presentation/settings/` (change-password is a Settings sub-flow) with `ChangePasswordUiState` co-located in the ViewModel file — matches codebase-wide precedent (`SettingsUiState` in `SettingsViewModel.kt`, `TextMonitoringSettingsScreen` in `presentation/settings/`) and the explicit user brief. See Complexity Tracking. |
| 2 | `@HiltViewModel`, no manual instantiation | ✅ PASS | `ChangePasswordViewModel` is `@HiltViewModel`; screen gets it via `hiltViewModel()` |
| 3 | Repositories `@Singleton` | ✅ PASS | Reuses existing `@Singleton AuthRepository`; none created |
| 4 | ViewModel → Repository → `safeApiCall{}` → `ApiService` | ✅ PASS | `AuthRepository.changePassword()` (AuthRepository.kt:169) already follows the chain; ViewModel calls it directly |
| 5 | No tokens in ViewModels/Screens | ✅ PASS | Passwords are user input, not tokens; no `TokenManager` access |
| 6 | Special permissions via settings routing | ✅ PASS | N/A — no permissions involved |
| 7 | Services contain zero business logic | ✅ PASS | N/A — no services touched |
| 8 | Workers `@HiltWorker` + `CoroutineWorker` | ✅ PASS | N/A — no workers |
| 9 | `NetworkResult<T>` only from repositories | ✅ PASS | `changePassword` returns `NetworkResult<MessageResponse>`; ViewModel `when`s over Success/Error/Loading |
| 10 | Errors via Snackbar + `LaunchedEffect(uiState.error)` + `clearError()` | ✅ PASS | Exact pattern planned, mirrors `LoginScreen.kt:62-67` |
| 11 | ProGuard for new Service/Receiver/Worker | ✅ PASS | N/A — none added |
| 12 | `noCompress "tflite"` untouched | ✅ PASS | build.gradle not modified |

**Principle I (privacy data-flow note)**: Feature transmits current+new password over the existing TLS-pinned channel to `POST auth/change-password`, exactly as the existing login flow transmits credentials. Nothing stored on device; no child-monitoring data involved. **Principle IV**: no permissions touched. Gate PASS.

## Project Structure

### Documentation (this feature)

```text
specs/006-change-password-flow/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/
│   └── ui-contract.md   # Phase 1 output
└── tasks.md             # Phase 2 output (/speckit-tasks)
```

### Source Code (repository root)

```text
app/src/main/java/com/safeguard/parentalcontrol/
├── presentation/
│   ├── settings/
│   │   ├── SettingsScreen.kt            # MODIFY: onNavigateToChangePassword param + row onClick, drop "Coming soon"
│   │   ├── ChangePasswordViewModel.kt   # NEW: @HiltViewModel + ChangePasswordUiState + changePassword()/clearError()
│   │   └── ChangePasswordScreen.kt      # NEW: Scaffold+TopAppBar, 2 HarisTextFields, submit button, LaunchedEffects
│   ├── navigation/
│   │   └── NavGraph.kt                  # MODIFY: Screen.ChangePassword route + composable + import + wiring
│   ├── auth/LoginScreen.kt              # REFERENCE ONLY: field/toggle/snackbar/loading patterns
│   └── designsystem/                    # REFERENCE ONLY: HarisTextField, HarisPrimaryButton
├── data/
│   ├── repository/AuthRepository.kt     # REUSED as-is: changePassword() at line 169
│   ├── remote/ApiService.kt             # REUSED as-is: changePassword at line 39
│   └── model/Models.kt                  # REUSED as-is: ChangePasswordRequest (117), MessageResponse (341)
```

**Structure Decision**: Single Android module, package-by-feature. New screen+ViewModel live in `presentation/settings/` as a Settings sub-flow (precedent: `TextMonitoringSettingsScreen.kt` already lives there). Only four source files touched/created.

## Implementation Design

### ChangePasswordViewModel.kt (NEW)

```kotlin
data class ChangePasswordUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val isSuccess: Boolean = false
)

@HiltViewModel
class ChangePasswordViewModel @Inject constructor(
    private val authRepository: AuthRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(ChangePasswordUiState())
    val uiState: StateFlow<ChangePasswordUiState> = _uiState.asStateFlow()

    fun changePassword(currentPassword: String, newPassword: String) {
        // Client validation gate (FR-003): blank fields, min length 8, new != current
        if (currentPassword.isBlank() || newPassword.isBlank()) {
            _uiState.update { it.copy(error = "Both fields are required") }; return
        }
        if (newPassword.length < 8) {
            _uiState.update { it.copy(error = "New password must be at least 8 characters") }; return
        }
        if (newPassword == currentPassword) {
            _uiState.update { it.copy(error = "New password must be different from current password") }; return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            when (val result = authRepository.changePassword(currentPassword, newPassword)) {
                is NetworkResult.Success -> _uiState.update { it.copy(isLoading = false, isSuccess = true) }
                is NetworkResult.Error -> _uiState.update { it.copy(isLoading = false, error = result.message) }
                else -> {} // Loading ignored
            }
        }
    }

    fun clearError() { _uiState.update { it.copy(error = null) } }
}
```

### ChangePasswordScreen.kt (NEW)

- Signature: `ChangePasswordScreen(onNavigateBack: () -> Unit, viewModel: ChangePasswordViewModel = hiltViewModel())`
- `Scaffold` + `TopAppBar(title = "Change Password", navigationIcon = back arrow → onNavigateBack)` + `SnackbarHost`
- Two local `remember { mutableStateOf("") }` fields + two visibility booleans
- Two `HarisTextField`s styled exactly like `LoginScreen.kt:165-186`: `Icons.Default.Lock` leading, `Visibility/VisibilityOff` trailing toggle, `PasswordVisualTransformation` when hidden, `KeyboardType.Password`; first field `ImeAction.Next`, second `ImeAction.Done`
- `HarisPrimaryButton(text = "Change Password", enabled = !isLoading && both non-blank, leadingIcon = CircularProgressIndicator when loading)` — mirrors `LoginScreen.kt:192-200`
- `LaunchedEffect(uiState.isSuccess) { if (it) onNavigateBack() }` (FR-005)
- `LaunchedEffect(uiState.error) { showSnackbar; viewModel.clearError() }` (Rule 10, FR-006)
- `collectAsStateWithLifecycle()` for state (Rule: constitution II)

### SettingsScreen.kt (MODIFY — surgical)

- Add param `onNavigateToChangePassword: () -> Unit = {}` after `onNavigateToTextReview`
- "Change Password" `SettingsItem` (lines ~304-316): `onClick = onNavigateToChangePassword`, DELETE the `trailing = { Text("Coming soon"...) }` block. Nothing else in the file changes.

### NavGraph.kt (MODIFY)

- `data object ChangePassword : Screen("change_password")` after `Settings`
- In `composable(Screen.Settings.route)` block: add `onNavigateToChangePassword = { navController.navigate(Screen.ChangePassword.route) }`
- New `composable(Screen.ChangePassword.route) { ChangePasswordScreen(onNavigateBack = { navController.popBackStack() }) }` after the Settings block
- Add import `com.safeguard.parentalcontrol.presentation.settings.ChangePasswordScreen`

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| Rule 1 partial: no dedicated `presentation/changepassword/` sub-package; `ChangePasswordUiState` co-located in ViewModel file | Change-password is a child flow of Settings, and the user brief explicitly mandates `presentation/settings/` placement. Codebase-wide precedent: every UiState lives inside its ViewModel file (e.g., `SettingsUiState` in `SettingsViewModel.kt`), and `TextMonitoringSettingsScreen` already lives in `presentation/settings/` | A new one-screen sub-package would diverge from both the brief and existing convention, making the tree inconsistent for zero architectural gain |
