# Research: Forgot Password

**Feature**: 009-forgot-password | **Date**: 2026-07-16

No NEEDS CLARIFICATION markers remained in Technical Context. Research below records the codebase-pattern decisions that ground Phase 1 design.

## R1. Navigation shape for the two-step flow

**Decision**: One nested navigation graph `forgot_password_flow` containing two destinations (`forgot_password_email`, `forgot_password_reset`), with `ForgotPasswordViewModel` scoped to the nested graph via `hiltViewModel(remember(backStackEntry) { navController.getBackStackEntry("forgot_password_flow") })`.

**Rationale**: Email and cooldown state must survive the step-1 → step-2 transition and back-navigation (spec US3: back preserves email). Graph-scoped ViewModel is the standard Navigation-Compose 2.7.x mechanism; avoids passing email as a nav argument (PII in route strings / logs).

**Alternatives considered**:
- Nav argument (`forgot_password_reset/{email}`) — rejected: email lands in route string, visible in nav logs; cooldown timer state still needs a shared holder.
- Single destination with internal step state — viable, but two destinations give free back-stack behavior for back-to-email (US3-4) and match the existing one-screen-per-destination convention in `NavGraph.kt`.

## R2. Returning the success signal to Login

**Decision**: On reset success, set `previousBackStackEntry.savedStateHandle["password_reset_success"] = true`, then `popBackStack()` to Login. `LoginScreen` observes the flag and shows the success snackbar (`forgotpw_success`), then clears the flag.

**Rationale**: Clarification #3 chose auto-navigate + snackbar on Login. `savedStateHandle` result-passing is the canonical Navigation-Compose pattern for "return a result to previous screen"; survives process death; no global state.

**Alternatives considered**:
- Shared singleton event bus — rejected: overkill, hidden coupling.
- Snackbar on reset screen then delayed pop — rejected by clarification (user chose immediate navigation).

## R3. 60-second resend cooldown implementation

**Decision**: `cooldownSeconds: Int` in `ForgotPasswordUiState`, driven by a `viewModelScope` coroutine: after each successful (or attempted) send, set 60 and decrement each second via `delay(1_000)` loop; cancel/restart job on resend. Resend button disabled while `cooldownSeconds > 0`, label shows remaining seconds.

**Rationale**: ViewModel survives step navigation (graph-scoped), so timer keeps ticking across steps and rotations. No WorkManager/Handler needed for a UI-scoped timer.

**Alternatives considered**: `CountDownTimer` — Android framework class, harder to test than a coroutine loop; rejected.

## R4. 422 validation-error parsing

**Decision**: Extend `parseErrorMessage` in `NetworkResult.kt`: if `detail` is a JSON array (FastAPI validation shape), extract the first element's `msg` field; fall back to generic message. Keep existing string-`detail` path (400/429 use it).

**Rationale**: Current implementation does `json.optString("detail", ...)` which returns the raw array text for 422 responses — user would see `[{"type":"string_too_short",...}]`. Backend contract (user-supplied) shows 422 body: `{"detail": [{"type": "string_too_short", "loc": ["body","new_password"], "msg": "String should have at least 8 characters"}]}`. Client-side validation (min 8 chars) makes 422 rare, but defense-in-depth requires graceful handling (spec US2-3, SC-002).

**Alternatives considered**: Per-call error parsing in repository — rejected: fix belongs in the shared parser so every endpoint benefits; no behavior change for string-`detail` responses.

## R5. Unauthenticated endpoints vs AuthInterceptor

**Decision**: No interceptor change expected — verify during implementation that `AuthInterceptor` skips token attachment/refresh for `auth/forgot-password` and `auth/reset-password` the same way it already handles `auth/login` / `auth/register` (no stored token when signed out ⇒ nothing attached). Add these paths to any existing no-auth path list if one exists.

**Rationale**: Flow runs signed-out; there is no token to attach. Same precondition as login/register which already work unauthenticated.

## R6. OTP input component

**Decision**: New `OtpCodeInput` composable in `presentation/forgotpassword/components/`: single invisible `BasicTextField` holding up to 6 digits, rendered as 6 `Box`es styled with Haris design-system colors (border/focus states matching `HarisTextField` look). Filter to digits, auto-advance is inherent (single field), paste of 6-digit string fills all. Wrap row in `CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr)` so digit order stays LTR under Arabic RTL (spec edge case).

**Rationale**: Single-backing-field pattern avoids 6-field focus juggling and gets paste support free; standard Compose OTP approach on Compose BOM 2023.10.01 (no first-party OTP component exists there).

**Alternatives considered**: Six `TextField`s with focus chaining — fragile backspace/paste handling; rejected.

## R7. String resources & localization

**Decision**: Add `forgotpw_*` keys to `values/strings.xml` (EN) and `values-ar/strings.xml` (AR); plural-free; cooldown label uses positional format `%1$d`. Server-provided messages (success/error `detail`) shown as received for network errors; the two known success messages get localized client-side strings instead (anti-enumeration confirmation and reset success) since server returns English only.

**Rationale**: Matches feature 008 localization architecture (all UI strings in both locales, `LocaleHelper.localizedContext` used in ViewModels — precedent: `ChangePasswordViewModel.getString`).

## R8. Repository & model shape

**Decision**:
- `Models.kt`: `ForgotPasswordRequest(val email: String)`, `ResetPasswordRequest(val email: String, val code: String, @SerializedName("new_password") val newPassword: String)`. Responses reuse existing `MessageResponse(message, success)`.
- `AuthRepository`: `suspend fun forgotPassword(email: String): NetworkResult<MessageResponse>` and `suspend fun resetPassword(email: String, code: String, newPassword: String): NetworkResult<MessageResponse>`, both `withContext(Dispatchers.IO) { safeApiCall { ... } }` — identical shape to `changePassword` (AuthRepository.kt:169).

**Rationale**: Exact match to backend contract and existing conventions; snake_case handled per-field via `@SerializedName` as elsewhere in `Models.kt`.
