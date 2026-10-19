# Contract: Account Deletion REST API (client view)

**Verified against production on 2026-09-20** (supplied with the plan input). This feature makes **no backend changes**. Base URL is `BuildConfig.API_BASE_URL` (…`/api/v1/`).

## 1. `DELETE /auth/account` — delete own account

| | |
|---|---|
| Auth | `Authorization: Bearer <access JWT>`, added by `AuthInterceptor`. The path is **not** in `isAuthEndpoint`, so the normal 401→refresh logic applies. |
| Body (password account) | `{"password": "<current password>"}` |
| Body (OAuth / Google account) | `{"confirmation": "DELETE"}`, with this exact casing |
| Content-Type | `application/json` |

Retrofit declaration (a plain `@DELETE` cannot carry a `@Body`):

```kotlin
@HTTP(method = "DELETE", path = "auth/account", hasBody = true)
suspend fun deleteAccount(@Body request: DeleteAccountRequest): Response<Unit>
```

`DeleteAccountRequest(password: String? = null, confirmation: String? = null)`. The app's `Gson` (`NetworkModule.provideGson`) has **no** `serializeNulls()`, so the field left null is omitted. Exactly one field is set.

| Status | Meaning | Client mapping (`DeletionError`) | What the user sees |
|---|---|---|---|
| 2xx (any body, including empty or 204) | Deleted. The token is now dead. | `Success` | Teardown, then Login with "Account deleted" |
| 400 | Missing or wrong password, or wrong confirmation | `WrongPassword` | Inline error on the field. The **only** case that says "wrong password". |
| 401 | Session over (expired, revoked, or the account is already gone) | `SessionOver` | Local state cleared (the interceptor already did this), Login with "session ended" |
| 403 | Supervised child. The body's `detail` mentions "parent". | `SupervisedChild` | "Only your parent can delete this account. Please ask them." Stays signed in. |
| 429 | Rate-limited | `RateLimited` | "Too many attempts. Please wait and try again." **Never** "wrong password". |
| exception, no code | Network or timeout | `Network` | "Nothing was deleted. Check your connection and try again." + Retry |
| anything else | | `Unknown(code)` | "Something went wrong. Nothing was deleted." + Retry |

The mapping uses the **status code only**, never the `detail` text, which may change or be localized (Q5). A 401 on this call has already gone through the interceptor, which tried a refresh and then cleared the tokens.

## 2. `DELETE /family/child/{childId}` — parent deletes one linked child's account

| | |
|---|---|
| Auth | Bearer JWT (parent) |
| Body | none |
| `childId` | **`FamilyLink.childId`** (the child's user id, `child_id`). **Never `FamilyLink.id`**: that is the link id and returns 404. |

```kotlin
@DELETE("family/child/{childId}")
suspend fun deleteChildAccount(@Path("childId") childId: Int): Response<Unit>
```

| Status | Mapping | What the user sees |
|---|---|---|
| 2xx | `Success` | The child is removed from the list by `childId`. Snackbar: "%s's account was deleted". |
| 404 | `ChildNotFound` | The list reloads. Snackbar: "%s is no longer linked to your account". |
| 401 | `SessionOver` | Session-over flow |
| 429 | `RateLimited` | Rate-limit copy |
| exception | `Network` | "Nothing was deleted" + Retry. The child stays in the list. |
| other | `Unknown(code)` | Generic, nothing deleted |

**Not to be confused with** `DELETE /family/link/{childId}` (the existing `removeFamilyLink`), which **only unlinks** and keeps the child's account. That is the "Stop monitoring (account kept)" action.

### 2a. Parent re-authentication before `DELETE /family/child` (spec FR-009, research RD-1)

The endpoint takes no credential, so the client first proves the parent's identity through an **existing** server check:

| Parent account | Server check | Pass | Fail |
|---|---|---|---|
| Password | `POST /auth/login {email: stored userEmail, password}` (an auth endpoint the interceptor skips) | 200 **and** `user.id == stored userId`. The rotated tokens are saved through `TokenManager`, just as `AuthRepository.login` does today. | 400 or 401 map to `WrongPassword` (inline). 429 maps to `RateLimited`. Exception maps to `Network`. A 200 with a different `user.id` aborts and maps to `SessionOver`. |
| Google | Fresh `GoogleSignInManager.signIn(context)`, then `POST /oauth/google {id_token}` | 200 and `user.id == stored userId` | Cancelled: nothing happens and the dialog stays open. A different account or an error aborts with a message and nothing is deleted. |

The `DELETE` is sent only after a pass. Each call counts toward the server's login rate limit.

## 3. `GET {root}/account-deletion` — public page

The page needs no authentication and is already live. `root` is `API_BASE_URL` with the trailing `/api/v1` removed, for example `https://bw.noor.net:8090/account-deletion`. The app only opens it with `Intent.ACTION_VIEW` and builds nothing.

## 4. `GET /auth/me` — used by this feature

The app calls this in two places:
- **When the delete screen opens**, to read `auth_provider` and pre-select password or DELETE mode. If the call fails, password mode is the default, and the manual "Signed in with Google?" switch is always available.
- **When a launch finds a leftover deletion marker**. 200 means the account still exists. 401 means the session is gone (research RD-3).

---

## Questions for the backend developer

| # | Question | Why the client needs it | Current client assumption |
|---|---|---|---|
| Q1 | What does a 200 from `DELETE /auth/account` and from `DELETE /family/child/{id}` contain: a `MessageResponse`, an empty body, or is it a 204? | The success handling | Any 2xx counts as success, whatever the body (D2) |
| Q2 | How can the client tell that an account has no password? Does `GET /auth/me` always return `auth_provider`? What does it report for a local account that later linked Google? Could `has_password: bool` be added? | Choosing between the password and DELETE modes | Uses `auth_provider` when present, plus a manual switch (research R2) |
| Q3 | Is `DELETE /auth/account` itself rate-limited? What is the window, and is there a `Retry-After` header? Does `DELETE /family/child` share the login limiter? | The retry wording | Shows the wait time from `Retry-After` when present, otherwise "try again later" |
| Q4 | After a child (or a parent with children) is deleted, does the server send any FCM message to the child's device, or does the device only find out through a 401? | Detecting remote deletion (spec FR-025) | Only a 401 arrives |
| Q5 | Is the error body for 400/403 always `{"detail": "<string>"}`? Is `detail` localized? | Mapping is by status only | Status code only |
| Q6 | **New.** Can the server return a response that clearly means "this account was deleted" and is different from an expired or revoked session? For example, a 401 with `{"detail": "...", "error_code": "account_deleted"}`, or a 410 Gone on `/auth/refresh` and `/auth/me`. `ErrorResponse.errorCode` already exists in the model. | Spec FR-025: stop protection on a child device only when the server confirms deletion. Today every case returns 401, so FR-025 falls back to leaving protection running (research RD-2). | Not implemented, recorded as a known limitation |
| Q7 | **New.** Could `DELETE /family/child/{id}` accept `{"password": ...}` / `{"confirmation": ...}` as `/auth/account` does? | Would replace the separate login step (RD-1) with one atomic, server-checked call | Separate login step |
