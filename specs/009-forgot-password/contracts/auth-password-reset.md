# API Contract: Password Reset Endpoints

**Feature**: 009-forgot-password | **Backend**: FastAPI `https://bw.noor.net:8090/api/v1` (NOT modified — client conforms)

Base path already applied by Retrofit base URL; `ApiService` paths are relative (`auth/...`), matching existing `auth/login`, `auth/change-password` style.

Both endpoints are **unauthenticated** — no `Authorization` header required or attached.

---

## 1. Request reset code

```
POST auth/forgot-password
Content-Type: application/json
```

### Request body

```json
{
  "email": "user@example.com"
}
```

### Responses

**200 OK** (returned whether or not the account exists — anti-enumeration):

```json
{
  "message": "If an account exists for that email, a password reset code has been sent.",
  "success": true
}
```

**429 Too Many Requests**:

```json
{
  "detail": "Rate limit exceeded"
}
```

### Client mapping

| Server response | `NetworkResult` | UI behavior |
|---|---|---|
| 200 | `Success(MessageResponse)` | Localized confirmation; advance to reset step; start 60s cooldown |
| 429 | `Error(msg, 429)` | Localized rate-limit snackbar; stay on current step |
| Network failure | `Error(msg, null)` | safeApiCall's connectivity message; retry allowed |

---

## 2. Redeem code & set new password

```
POST auth/reset-password
Content-Type: application/json
```

### Request body

```json
{
  "email": "user@example.com",
  "code": "955545",
  "new_password": "Test@1234"
}
```

Constraints: `code` — 6-digit numeric string; `new_password` — min 8 characters (server-enforced via 422).

### Responses

**200 OK**:

```json
{
  "message": "Password has been reset successfully",
  "success": true
}
```

**400 Bad Request**:

```json
{
  "detail": "Invalid or expired reset code"
}
```

**422 Unprocessable Entity** (FastAPI validation shape — `detail` is an ARRAY):

```json
{
  "detail": [
    {
      "type": "string_too_short",
      "loc": ["body", "new_password"],
      "msg": "String should have at least 8 characters"
    }
  ]
}
```

### Client mapping

| Server response | `NetworkResult` | UI behavior |
|---|---|---|
| 200 | `Success(MessageResponse)` | Pop to Login; success snackbar there |
| 400 | `Error(msg, 400)` | Inline error on code field (localized "invalid or expired code") |
| 422 | `Error(first detail[].msg, 422)` | Inline error on password field |
| Network failure | `Error(msg, null)` | Snackbar; retry allowed |

### Parser requirement

`parseErrorMessage` (NetworkResult.kt:134) currently assumes `detail` is a string. MUST be extended: when `detail` is a JSON array, return first element's `msg` (fallback: generic message). String-`detail` behavior unchanged. See research R4.

---

## ApiService additions

```kotlin
@POST("auth/forgot-password")
suspend fun forgotPassword(@Body request: ForgotPasswordRequest): Response<MessageResponse>

@POST("auth/reset-password")
suspend fun resetPassword(@Body request: ResetPasswordRequest): Response<MessageResponse>
```
