# Data Model: Forgot Password

**Feature**: 009-forgot-password | **Date**: 2026-07-16

All data is transient — nothing persisted to Room, DataStore, or SharedPreferences.

## Request Models (`data/model/Models.kt`)

### ForgotPasswordRequest

| Field | Type | JSON key | Validation (client-side, before send) |
|-------|------|----------|----------------------------------------|
| email | String | `email` | Trimmed; non-blank; matches `Patterns.EMAIL_ADDRESS` |

### ResetPasswordRequest

| Field | Type | JSON key | Validation (client-side, before send) |
|-------|------|----------|----------------------------------------|
| email | String | `email` | Carried from step 1 (already validated) |
| code | String | `code` | Exactly 6 digits |
| newPassword | String | `new_password` | ≥ 8 characters; equals confirm field |

## Response Models

Reuses existing `MessageResponse(message: String, success: Boolean = true)` — no new response types. Error bodies parsed by shared `parseErrorMessage` (`detail` as string for 400/429; `detail` as array → first `msg` for 422, per research R4).

## UI State (`presentation/forgotpassword/ForgotPasswordUiState.kt`)

```
data class ForgotPasswordUiState(
    val step: ForgotPasswordStep = ForgotPasswordStep.EMAIL,   // EMAIL | RESET
    val email: String = "",                // trimmed value submitted in step 1
    val isLoading: Boolean = false,        // request in flight (blocks duplicate submit)
    val error: String? = null,             // snackbar-level error (network / server)
    val emailFieldError: String? = null,   // inline: invalid format / blank
    val codeFieldError: String? = null,    // inline: incomplete code / server 400
    val passwordFieldError: String? = null,// inline: too short / mismatch / server 422
    val cooldownSeconds: Int = 0,          // resend disabled while > 0
    val codeSent: Boolean = false,         // step-1 success → navigate to step 2
    val resetSuccess: Boolean = false      // step-2 success → pop to Login + snackbar
)
```

### State transitions

```
EMAIL step:
  submit(email)
    ├─ invalid format → emailFieldError (no network)
    ├─ valid → isLoading → POST forgot-password
    │     ├─ Success        → codeSent=true, cooldownSeconds=60, step=RESET
    │     ├─ Error 429      → error = rate-limit string, stay on EMAIL
    │     └─ Error other    → error = message, stay on EMAIL

RESET step:
  resend()  [enabled only when cooldownSeconds == 0]
    └─ POST forgot-password → same handling; cooldownSeconds=60 on any attempt
  submit(code, newPassword, confirm)
    ├─ code ≠ 6 digits           → codeFieldError (no network)
    ├─ newPassword < 8 chars     → passwordFieldError (no network)
    ├─ newPassword ≠ confirm     → passwordFieldError (no network)
    ├─ valid → isLoading → POST reset-password
    │     ├─ Success        → resetSuccess=true  (NavGraph: set result flag, popBackStack to Login)
    │     ├─ Error 400      → codeFieldError = "Invalid or expired reset code" (localized)
    │     ├─ Error 422      → passwordFieldError = parsed msg
    │     └─ Error other    → error = message
  back → step=EMAIL, email preserved, cooldown keeps ticking

Cooldown: set to 60 after every send attempt; coroutine decrements 1/sec to 0.
```

## Lifecycle / retention

| Datum | Held where | Lifetime | Notes |
|-------|-----------|----------|-------|
| Email | ForgotPasswordViewModel (graph-scoped) | Until flow exits nav back stack | Never in route args (research R1) |
| Reset code | Compose field state → request body only | Seconds | Never logged, never in UiState after submit |
| New password | Compose field state → request body only | Seconds | Never logged, never stored |
| Success flag | `savedStateHandle` of Login back-stack entry | Until consumed by LoginScreen | Boolean only, no PII |
