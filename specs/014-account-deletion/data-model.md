# Data Model: Permanent Account Deletion (014)

No new server entities. All the models below live on the client.

## 1. `DeleteAccountRequest` (network, `data/model/Models.kt`)

| Field | JSON | Type | Rule |
|---|---|---|---|
| password | `password` | `String?` | Set for a password account. Sent exactly as typed, with no trimming. |
| confirmation | `confirmation` | `String?` | Set for a Google account. Always the constant `"DELETE"`. |

Invariant: exactly one field is non-null (factories `forPassword(p)` and `forGoogle()`). Nulls are omitted because the Gson instance has no `serializeNulls`.

## 2. `DeletionError` (`data/repository/DeletionError.kt`)

```kotlin
sealed class DeletionError {
    data object WrongPassword : DeletionError()     // 400 (self-delete); 400/401 from re-auth login
    data object SupervisedChild : DeletionError()   // 403
    data object ChildNotFound : DeletionError()     // 404, DELETE family/child only
    data object SessionOver : DeletionError()       // 401 on authenticated calls (these go through AuthInterceptor)
    data object AccountMismatch : DeletionError()   // re-auth: no stored email, or the server authenticated a different user
                                                    // (client sentinel CODE_ACCOUNT_MISMATCH = -1001; never a 401, because the re-auth endpoints bypass the interceptor)
    data class RateLimited(val retryAfterSeconds: Long?) : DeletionError() // 429
    data object Network : DeletionError()           // NetworkResult.Error with code == null
    data class Unknown(val code: Int) : DeletionError()
    companion object {
        fun fromSelfDelete(error: NetworkResult.Error): DeletionError
        fun fromChildDelete(error: NetworkResult.Error): DeletionError
        fun fromReauth(error: NetworkResult.Error): DeletionError
    }
}
```

- These are pure functions keyed on `code`, so they can be unit-tested.
- **Only `WrongPassword` maps to wrong-password wording.**
- Repository functions still return `NetworkResult<Unit>` (constitution rule 9). The mapping happens in the coordinator.
- `retryAfterSeconds` needs the `Retry-After` header, which `NetworkResult.Error` does not carry. It is `null` for now, and the message falls back to "try again later" (Q3).

## 3. `AccountDeletionState` (persisted marker, `util/AccountDeletionState.kt`)

This is a plain `SharedPreferences` file, `account_deletion_state`. It holds **no PII**, and every write uses `commit()` so it survives the process being killed. `PreferencesManager.clearAll()` does not touch it.

| Key | Type | Meaning |
|---|---|---|
| `pending_since` | Long (epoch ms), 0 means none | Set just **before** `DELETE /auth/account` is sent (FR-016a). |
| `pending_role_parent` | Boolean | The role at request time. Used only for the recovery branch (RD-3). |
| `pending_had_linked_parent` | Boolean | The cached `hasLinkedParent` at request time. It is sourced from the server. |
| `teardown_in_progress` | Boolean | Set in teardown step 1 and cleared in step 6. |
| `notice_pending` | Boolean | Set in teardown step 6 when the reason is ACCOUNT_DELETED. The Login screen reads it to show "Account deleted". |
| `notice_child_uninstall` | Boolean | Set together with `notice_pending` on a child device. Shows the FR-022a uninstall note. |

State transitions:

```text
idle ──(user confirms self-delete)──▶ pending
pending ──(2xx)──────────────────────▶ tearingDown ──▶ done(notice) ──(Login shows & user dismisses / signs in)──▶ idle
pending ──(400/403/429/other HTTP)───▶ idle            (definite server answer: marker cleared, stays signed in)
pending ──(network error, no code)───▶ pending         (marker KEPT: the response may have been lost; UI says "nothing was deleted, retry")
pending ──(401)──────────────────────▶ recovery branch below, run in-process (a lost success looks exactly like this)
pending ──(process death)──▶ [next launch] GET auth/me:
        200 ─▶ idle
        401 & (rolePARENT || !hadLinkedParent) ─▶ tearingDown(reason=SESSION_GONE_AFTER_MARKER) ─▶ idle (no "deleted" notice)
        401 & hadLinkedParent ─▶ idle (protection untouched; FR-026/027)
        network error ─▶ pending (retry next launch; nothing stopped)
tearingDown ──(process death)──▶ [next launch] resume teardown with the same reason
```

## 4. `DeletionState` (in-memory, `AccountDeletionCoordinator`)

```kotlin
sealed interface DeletionState {
    data object Idle : DeletionState
    data object InFlight : DeletionState
    data object Succeeded : DeletionState              // self: teardown done; child: removed
    data class Failed(val error: DeletionError) : DeletionState
}
```

- `selfState: StateFlow<DeletionState>`, plus `childStates: StateFlow<Map<Int /*childId*/, DeletionState>>`.
- Single flight: while a target is `InFlight`, another request for the same target returns immediately without a network call (FR-015).
- `acknowledge(target)` resets `Failed` and `Succeeded` back to `Idle` once the UI has consumed them.
- `attachSelf()`, called by every new `DeleteAccountViewModel`, resets any terminal self-state to `Idle` unless it is `InFlight`. A stale `Succeeded` must never navigate a user who signed in again in the same process.

## 5. `DeleteAccountUiState` (`presentation/deleteaccount/DeleteAccountUiState.kt`)

| Field | Type | Notes |
|---|---|---|
| role | `Role` (PARENT / CHILD) | From `PreferencesManager`. Used **only** for the wording of the consequences list, never to allow or block the request (FR-013). |
| linkedChildCount | `Int?` | Parent only, from `getLinkedChildren()`. `null` gives wording without a number. |
| hasReviewOriginals | `Boolean` | Child only. `imageBlurManager.getPendingReviews().isNotEmpty()` (FR-021). |
| mode | `ConfirmMode` (PASSWORD / GOOGLE_DELETE) | Pre-selected from `auth/me` `authProvider`. The user can switch it manually (research R2). |
| credential | `String` | Kept in ViewModel memory only. **Never** in `SavedStateHandle`, never logged, never in `toString()` (the `data class` overrides `toString` to redact it). |
| passwordVisible | `Boolean` | |
| credentialError | `String?` | Inline error for `WrongPassword` only. |
| showFinalConfirm | `Boolean` | The second-step `AlertDialog` (FR-008). |
| isLoading | `Boolean` | Mirrors the coordinator's `InFlight`. Blocks input and back navigation. |
| error | `String?` | Snackbar message (constitution rule 10). Includes SessionOver: "session ended". It never says "Account deleted". |
| errorRetryable | `Boolean` | For Network and Unknown: the Snackbar gets a "Retry" action. |
| blockingNotice | `String?` | The supervised or rate-limited explanation shown under the button. |
| deleted | `Boolean` | Triggers navigation to Login. |

Derived: `canSubmit = !isLoading && (mode == PASSWORD ? credential.isNotEmpty() : credential == "DELETE")`. Matching is case-sensitive. FR-007 is enforced in the ViewModel, not just in the UI.

## 6. `ChildrenUiState` additions (`presentation/children/ChildrenViewModel.kt`)

| Field | Type | Notes |
|---|---|---|
| deleteTarget | `FamilyLink?` | The child whose delete dialog is open. |
| deleteNameInput | `String` | Must equal `deleteTarget.childName` exactly (no trim or case-folding; Arabic compared as-is). |
| deleteCredential | `String` | The parent's password (password parents). In memory only. |
| parentIsGoogle | `Boolean` | From `auth/me`. Google parents see a "Confirm with Google" button instead of a password field. |
| deleteCredentialError | `String?` | Inline `WrongPassword` error. |
| deleteDialogNotice | `String?` | RateLimited, Network or Unknown message, shown inside the open dialog (a Snackbar behind the scrim would be hidden). |
| deletingChildId | `Int?` | Taken from the coordinator's `childStates`. Disables the dialog buttons. |

Derived: `canDelete = deleteNameInput == target.childName && (parentIsGoogle || deleteCredential.isNotEmpty()) && deletingChildId == null`.

## 7. `AuthEvent` addition (`util/TokenManager.kt`)

`object AccountDeleted : AuthEvent()`. It is emitted only by `TokenManager.clearTokensDueToAccountDeletion()`. `MainActivity` handles it with `navigateToLogin` and **no** "session ended" toast. The Login screen shows the confirmation from `AccountDeletionState.notice_pending`, which survives a cold start.
