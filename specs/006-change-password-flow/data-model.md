# Data Model: Change Password Flow

**Feature**: 006-change-password-flow | **Date**: 2026-07-06

No persistence. All state is transient UI/request state.

## ChangePasswordUiState (NEW — co-located in ChangePasswordViewModel.kt)

| Field | Type | Default | Meaning |
|---|---|---|---|
| `isLoading` | `Boolean` | `false` | Request in flight; disables submit (FR-004) |
| `error` | `String?` | `null` | Validation or server failure message; consumed by Snackbar then cleared (FR-003/FR-006) |
| `isSuccess` | `Boolean` | `false` | Change accepted; triggers one-shot navigation back (FR-005) |

### State transitions

```text
Idle (false, null, false)
  │ submit, validation fails
  ├────────────► Error (false, msg, false) ──clearError()──► Idle
  │ submit, validation passes
  ▼
Loading (true, null, false)
  ├── NetworkResult.Success ──► Success (false, null, true) ──LaunchedEffect──► popBackStack
  └── NetworkResult.Error   ──► Error (false, msg, false) ──clearError()──► Idle (retry allowed)
```

- All updates via `_uiState.update { it.copy(...) }` (constitution II).
- `isSuccess` never resets — screen is left immediately; ViewModel dies with back stack entry.

## Screen-local state (Composable `remember`, not in UiState)

| Var | Type | Purpose |
|---|---|---|
| `currentPassword` | `String` | Field 1 input |
| `newPassword` | `String` | Field 2 input |
| `currentVisible` | `Boolean` | Field 1 mask toggle (FR-002) |
| `newVisible` | `Boolean` | Field 2 mask toggle (FR-002) |

Raw passwords stay in Composable/ViewModel parameters only; never logged, never persisted (spec: transient request entity).

## Reused wire models (unchanged)

| Model | Location | Shape |
|---|---|---|
| `ChangePasswordRequest` | `Models.kt:117` | `{ current_password, new_password }` |
| `MessageResponse` | `Models.kt:341` | `{ message }` |
| `NetworkResult<T>` | `data/remote/` | `Success(data) / Error(message) / Loading` |

## Validation rules (FR-003, enforced in ViewModel pre-network)

1. `currentPassword.isBlank() || newPassword.isBlank()` → "Both fields are required"
2. `newPassword.length < 8` → "New password must be at least 8 characters"
3. `newPassword == currentPassword` → "New password must be different from current password"

Order matters: cheapest first; first failure wins; no request sent on any failure.
