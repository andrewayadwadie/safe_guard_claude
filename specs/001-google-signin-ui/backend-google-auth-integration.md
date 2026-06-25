# Backend Integration — Save / Register Google Sign-In Users

**Feature**: 001-google-signin-ui (Edit 2)
**Audience**: Backend engineer (FastAPI)
**Goal**: Accept a Google ID token from the Android app, verify it, and **create or fetch** the user so they are persisted (visible in the backend DB / Firebase console).

The Android client sends **only** the Google `id_token` (+ optional `role`). It never sends email/name — the backend extracts identity from the verified token.

---

## 0. How the client calls the backend

The app already has Retrofit bindings (`ApiService.kt`):

| Endpoint | Method | Body | Returns |
|----------|--------|------|---------|
| `auth/register` | POST | `RegisterRequest` | `TokenResponse` |
| `auth/login` | POST | `LoginRequest` | `TokenResponse` |
| `oauth/google/status` | GET | — | `{ "enabled": bool }` |
| `oauth/google` | POST | `GoogleAuthRequest { id_token, role? }` | `TokenResponse` |

**Recommended**: keep Google auth on the dedicated **`POST /oauth/google`** endpoint (already wired). It is the cleanest separation. Section 2 covers that.

If you specifically want to **fold Google handling into the existing `login` and `register` endpoints**, Section 3 covers that. Pick ONE approach — do not do both for the same flow.

---

## 1. Prerequisites (one-time)

1. **Web Client ID (OAuth client type 3)** — from Firebase console → Project settings → your project, or Google Cloud Console → APIs & Services → Credentials → OAuth 2.0 Client IDs → **Web client**. Copy the full `...apps.googleusercontent.com` ID.
   - Backend uses it as the **audience** when verifying tokens.
   - The same value must be in the app's `BuildConfig.GOOGLE_WEB_CLIENT_ID` (FR-020).
2. Install verification lib:
   ```bash
   pip install google-auth
   ```
3. Add env var:
   ```env
   GOOGLE_WEB_CLIENT_ID=1234567890-abcdef.apps.googleusercontent.com
   ```

---

## 2. Recommended — `POST /oauth/google`

### 2.1 Verify the ID token

```python
# auth/google.py
from google.oauth2 import id_token as google_id_token
from google.auth.transport import requests as google_requests
from fastapi import HTTPException
import os

GOOGLE_WEB_CLIENT_ID = os.environ["GOOGLE_WEB_CLIENT_ID"]

def verify_google_id_token(token: str) -> dict:
    """Returns the verified Google identity claims, or raises 401."""
    try:
        claims = google_id_token.verify_oauth2_token(
            token,
            google_requests.Request(),
            audience=GOOGLE_WEB_CLIENT_ID,   # must match the Web Client ID
        )
    except ValueError:
        raise HTTPException(status_code=401, detail="invalid_google_token")

    # Hardened checks
    if claims.get("iss") not in ("accounts.google.com", "https://accounts.google.com"):
        raise HTTPException(status_code=401, detail="invalid_issuer")
    if not claims.get("email_verified", False):
        raise HTTPException(status_code=401, detail="email_not_verified")
    return claims
    # claims: sub (stable Google user id), email, name, picture, ...
```

### 2.2 Upsert the user (create or fetch — no duplicates, FR-022)

```python
# Pseudo-SQLAlchemy. Lookup by google_sub first, then email.
def get_or_create_google_user(db, claims: dict, role: str | None):
    google_sub = claims["sub"]
    email = claims["email"].lower()
    full_name = claims.get("name", "")

    # 1. Existing Google user → log in (role ignored)
    user = db.query(User).filter(User.google_sub == google_sub).first()
    if user:
        return user, False  # (user, created)

    # 2. Email already registered with local auth → LINK Google, log in
    user = db.query(User).filter(User.email == email).first()
    if user:
        user.google_sub = google_sub
        user.auth_provider = "google"   # or keep "local"+linked flag
        db.commit()
        return user, False

    # 3. Brand-new user → role REQUIRED
    if role not in ("parent", "child"):
        raise HTTPException(status_code=422, detail="role_required")

    user = User(
        email=email,
        full_name=full_name,
        role=role,
        google_sub=google_sub,
        auth_provider="google",
        password_hash=None,          # no local password for Google-only users
    )
    db.add(user)
    db.commit()
    db.refresh(user)
    return user, True
```

### 2.3 Endpoint

```python
@router.post("/oauth/google", response_model=TokenResponse)
def google_auth(req: GoogleAuthRequest, db: Session = Depends(get_db)):
    claims = verify_google_id_token(req.id_token)
    user, _created = get_or_create_google_user(db, claims, req.role)
    access, refresh = issue_tokens(user)   # same JWT issuance as login/register
    return TokenResponse(
        access_token=access,
        refresh_token=refresh,
        token_type="bearer",
        user=user,
    )
```

**Critical contract** (matches the client in `oauth-google.md`):
- New user with **no `role`** → respond **HTTP 422** `{"detail": "role_required"}`. The app detects this and shows the role dialog, then re-calls with `role`.
- Any other failure → non-2xx; the app shows the generic snackbar.
- If persistence (`db.commit`) fails → return 5xx; the app treats it as sign-in failure (FR-023). **Never** return 200 without a saved user.

---

## 3. Alternative — edit `login` and `register` endpoints

Only if you must route Google through the existing endpoints. Add an optional `id_token` field; when present, branch to Google handling.

### 3.1 Extend request models

```python
class LoginRequest(BaseModel):
    email: str | None = None
    password: str | None = None
    id_token: str | None = None          # NEW: Google ID token

class RegisterRequest(BaseModel):
    email: str | None = None
    password: str | None = None
    full_name: str | None = None
    role: str | None = None
    id_token: str | None = None          # NEW: Google ID token
```
> If you add `id_token` to the Android DTOs too, update `data/model/Models.kt` (`LoginRequest`, `RegisterRequest`). Otherwise leave the app on `/oauth/google` and ignore this section.

### 3.2 `POST /auth/login`

```python
@router.post("/auth/login", response_model=TokenResponse)
def login(req: LoginRequest, db: Session = Depends(get_db)):
    if req.id_token:                                  # Google path
        claims = verify_google_id_token(req.id_token)
        user, created = get_or_create_google_user(db, claims, role=None)
        if created:
            # New Google user reached login without a role → signal role required
            raise HTTPException(status_code=422, detail="role_required")
        return _token_response(user)

    # ... existing email/password path unchanged ...
```

### 3.3 `POST /auth/register`

```python
@router.post("/auth/register", response_model=TokenResponse)
def register(req: RegisterRequest, db: Session = Depends(get_db)):
    if req.id_token:                                  # Google path
        claims = verify_google_id_token(req.id_token)
        user, _ = get_or_create_google_user(db, claims, role=req.role)
        return _token_response(user)

    # ... existing email/password path unchanged ...
```

Same 422-on-missing-role and never-200-without-save rules from Section 2.3 apply.

---

## 4. Database migration

Add to the `users` table:

| Column | Type | Notes |
|--------|------|-------|
| `google_sub` | string, nullable, **unique** | Stable Google user id (`sub` claim). Primary lookup key. |
| `auth_provider` | string | `"local"` \| `"google"` (or a join/link flag). |
| `password_hash` | make **nullable** | Google-only users have no local password. |

```sql
ALTER TABLE users ADD COLUMN google_sub VARCHAR UNIQUE;
ALTER TABLE users ADD COLUMN auth_provider VARCHAR DEFAULT 'local';
ALTER TABLE users ALTER COLUMN password_hash DROP NOT NULL;
```

---

## 5. Status endpoint — no longer used by the app

As of Edit 2 the app **removed** the `GET /oauth/google/status` call (FR-018): the button is always visible (FR-017). The endpoint may remain deployed but is unused by the client. No action required.

---

## 6. Verification checklist (FR-021, US5)

Run after deploy:

1. **Client ID** — `GOOGLE_WEB_CLIENT_ID` (backend) == `BuildConfig.GOOGLE_WEB_CLIENT_ID` (app), both real, non-placeholder.
2. **Token exchange** — sign in with a fresh Google account in the app → backend `POST /oauth/google` (or login/register) returns **200** with `access_token` + `user`.
3. **Persistence** — the FastAPI `users` table is the source of truth (Firebase only verifies the token; this backend is **not** on Firebase Auth, so the console Users list stays empty by design). Query DB: `SELECT id,email,role,google_sub,auth_provider FROM users WHERE email='<test>';` → exactly **one** row with `auth_provider='google'` and the chosen role.
4. **No duplicates** — sign out, sign in again with the same account → still **one** row (FR-022).
5. **Role gate** — new account without role → backend returns **422 `role_required`**, app shows role dialog, re-call with role → 200 + saved user.
6. **Failure safety** — simulate a DB failure → backend returns 5xx, app shows generic snackbar, **no** orphan/partial user row.

All six must pass for release sign-off (SC-009, SC-010).

---

## 7. Security notes

- Verify **every** token server-side against Google's public keys with audience = Web Client ID. Never trust client-supplied email/name.
- `id_token` must never be logged or forwarded to analytics.
- Reject tokens where `email_verified` is false or `iss` is not Google.
- Issue your own session JWT — do not pass the Google token to other services.
