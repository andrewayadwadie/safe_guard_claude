# Contract: Google OAuth Endpoints (existing — documented, not modified)

The client conforms to the deployed FastAPI contract. **No backend changes** in this feature. Retrofit bindings already exist in `data/remote/ApiService.kt`.

## GET /oauth/google/status

Public (no auth). Called on every login/register screen load to decide button visibility.

**Response 200**
```json
{ "enabled": true }
```

Maps to `GoogleOAuthStatus`. Client reads `.enabled`; any failure → `false` (button hidden). Drives FR-001/FR-002.

## POST /oauth/google

**Request body** (`GoogleAuthRequest`)
```json
{
  "id_token": "eyJhbGci...",
  "role": "parent"
}
```
- `id_token` (string, required) — Google ID token from Credential Manager.
- `role` (string `"parent"`|`"child"`, optional) — sent ONLY for a new user after role selection; `null`/omitted on the first attempt.

**Response 200** (`TokenResponse`)
```json
{
  "access_token": "...",
  "refresh_token": "...",
  "token_type": "bearer",
  "user": { "id": 1, "email": "user@gmail.com", "full_name": "John Doe", "role": "parent" }
}
```

**Backend behavior (3 cases)**
1. User exists w/ this Google ID → log in (role ignored).
2. Email exists w/ local auth → link Google, log in (role ignored).
3. New user, no account → `role` REQUIRED. If missing → error signaling role required.

**New-user / role-required error**
- Spec note: HTTP 422 `{ "detail": "role_required" }`.
- Client detection (R1): normalize `NetworkResult.Error.message` and match `rolerequired` (covers both `"role_required"` and `"Role is required"`). On match → `needsRoleSelection = true`, store `pendingGoogleIdToken`. Verify exact shape against the Postman collection at implementation time.

**Other errors** → generic UI text "Sign-in failed. Please try again." (FR-016); raw backend message never shown.

## Security notes
- Client sends only `id_token` (+ optional role). Never sends Google email/name — backend verifies the token with Google's public keys and extracts identity (Principle I).
- `id_token` flows device → backend only; never logged, never sent to analytics (Principle V).
- Web Client ID (`BuildConfig.GOOGLE_WEB_CLIENT_ID`) is a public OAuth identifier, required by Credential Manager.
