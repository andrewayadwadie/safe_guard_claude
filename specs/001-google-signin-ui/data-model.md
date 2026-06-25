# Phase 1 Data Model: Google Sign-In UI Integration

No database entities, no Room tables, no new network models. This feature is UI + state wiring. "Entities" below are UI state and component contracts.

## Existing state (reused, no schema change)

### `AuthUiState` — `presentation/auth/AuthViewModel.kt`

Fields relevant to this feature (all already present):

| Field | Type | Drives |
|---|---|---|
| `isGoogleSignInEnabled` | `Boolean` | ~~Button visibility (FR-001/002)~~ **REMOVED in Edit 2** — see Edit-2 note below; button now unconditional (FR-017/018) |
| `isGoogleSignInLoading` | `Boolean` | Button spinner + disabled state (FR-003/004) |
| `needsRoleSelection` | `Boolean` | Dialog visibility (FR-006) |
| `pendingGoogleIdToken` | `String?` | Held across role selection; cleared on cancel (FR-010) |
| `error` | `String?` | Generic error Snackbar (FR-016) via existing `LaunchedEffect(uiState.error)` |

**No fields added.** Existing structure is sufficient.

## Existing domain enum (reused)

### `UserRole` — `data/model/Models.kt`

```kotlin
enum class UserRole {
    @SerializedName("parent") PARENT,
    @SerializedName("child")  CHILD
}
```

Serializes to `"parent"` / `"child"` for the backend contract. Passed to `completeGoogleRegistration(role)`.

## Existing network contract (reused)

### `GoogleAuthRequest` — `data/model/Models.kt`

```kotlin
data class GoogleAuthRequest(
    @SerializedName("id_token") val idToken: String,
    val role: UserRole? = null   // omitted/null for existing users
)
```

Matches the backend body `{ "id_token": ..., "role": "parent" | null }`. No change.

## New component contracts (UI)

### `GoogleSignInButton` (composable)

```kotlin
@Composable
fun GoogleSignInButton(
    onClick: () -> Unit,
    isLoading: Boolean = false,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
)
```

| Rule | Source |
|---|---|
| Outlined, full width, 50.dp height, 12.dp radius, `ic_google` image left, label "Continue with Google" | FR-013 |
| `CircularProgressIndicator` replaces icon+label when `isLoading` | FR-003 |
| Visually + functionally disabled when `!enabled` | FR-004 |
| G icon `contentDescription = "Google"` | FR-015 |

### `RoleSelectionDialog` (composable)

```kotlin
@Composable
fun RoleSelectionDialog(
    onRoleSelected: (UserRole) -> Unit,
    onDismiss: () -> Unit
)
```

Internal state: `selectedRole: UserRole?` (none preselected).

| Rule | Source |
|---|---|
| Two role cards: Parent (shield, "I manage my child's device"), Child (child icon, "My device is monitored") | FR-007 |
| Selected card → primary border + tinted background; tap selects, does NOT commit | FR-007/008 |
| "Continue" enabled only when a role is selected → fires `onRoleSelected(selectedRole)` | FR-008 |
| "Cancel" → `onDismiss` | FR-010 |
| No dismiss on outside tap / back press | FR-009 |
| Cards expose merged title+subtitle semantics to TalkBack | FR-015 |

## New infrastructure contract

### `AnalyticsHelper` (`util/`, `@Singleton`, Hilt-injected)

```kotlin
@Singleton
class AnalyticsHelper @Inject constructor(/* FirebaseAnalytics */) {
    fun logGoogleSignInTapped()
    fun logGoogleSignInSuccess()
    fun logGoogleSignInFailed(reason: String)   // "cancelled" | "error"
    fun logGoogleRoleSelected(role: UserRole)
}
```

Events: `google_signin_tapped`, `google_signin_success`, `google_signin_failed` (param `reason`), `google_signin_role_selected` (param `role`). **No PII** — never logs idToken, email, or name (FR-014, Principle I/V).

## State transitions (Google flow)

```
[idle]
  │ tap button → logGoogleSignInTapped(); isGoogleSignInLoading = true
  ▼
[picker shown] ──cancel──► isGoogleSignInLoading=false; logGoogleSignInFailed("cancelled") → [idle]
  │ account chosen → idToken obtained → POST /oauth/google (role=null)
  ▼
[backend resp]
  ├─ success (existing/linked) → isLoggedIn=true; logGoogleSignInSuccess() → [navigated]
  ├─ role required → isGoogleSignInLoading=false; needsRoleSelection=true; pendingGoogleIdToken=idToken → [dialog]
  └─ other error → isGoogleSignInLoading=false; error="Sign-in failed. Please try again."; logGoogleSignInFailed("error") → [idle+snackbar]

[dialog]
  ├─ Continue(role) → logGoogleRoleSelected(role); completeGoogleRegistration(role) → POST /oauth/google (role) → [backend resp]
  └─ Cancel → needsRoleSelection=false; pendingGoogleIdToken=null; isGoogleSignInLoading=false → [idle]
```

---

## Edit 2 state change (2026-06-24)

### `AuthUiState` — field removed

- **`isGoogleSignInEnabled: Boolean`** → **REMOVED**. It gated button visibility and was the disappearing-button bug (status check flipped it to `false`). Button visibility is now unconditional — no state input drives it (FR-017/FR-018).
- `checkGoogleOAuthStatus()` and its `init` call are deleted; `isGoogleSignInLoading`, `needsRoleSelection`, `pendingGoogleIdToken`, `error` are unchanged.

### Button visibility (was: state-driven → now: constant)

```text
LoginScreen / RegisterScreen:
  // before: if (uiState.isGoogleSignInEnabled) { GoogleSignInButton(...) }
  // after:  GoogleSignInButton(...)   // always rendered
```

No new entities. Backend persistence shape (FastAPI `users` table: + `google_sub` unique, `auth_provider`, nullable `password_hash`) is documented in [backend-google-auth-integration.md](./backend-google-auth-integration.md) §4 — that table lives in the separate FastAPI repo, not this module.
