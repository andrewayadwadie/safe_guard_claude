# Quickstart: Validate Google Sign-In UI

Validation guide for the feature. Assumes backend reachable and `GET /oauth/google/status` returns `{"enabled": true}`.

## Prerequisites

- `app/google-services.json` present (gitignored).
- `BuildConfig.GOOGLE_WEB_CLIENT_ID` = `216776621097-p63ekcnr94bfcuoufehgpsvnl6b2g990.apps.googleusercontent.com` (FR-011).
- Device/emulator with at least one Google account (for the happy path) and the ability to remove accounts (for the no-accounts path).
- The Web Client ID's OAuth consent + SHA-1 of the debug keystore registered in the Google Cloud project (required for Credential Manager to return a token).

## Build & install

```bash
./gradlew :app:assembleDebug
./gradlew :app:installDebug
```

## Manual validation scenarios

| # | Maps to | Steps | Expected |
|---|---|---|---|
| 1 | US1 / FR-001,003,012 | Open Login → tap "Continue with Google" → pick an **existing** account | Spinner shows; account picker appears; lands on correct home screen; NO role dialog |
| 2 | US1 / FR-001 | Backend flag `false` (or unreachable) on Login load | Google button + OR divider NOT rendered |
| 3 | US2 / FR-006,007,008 | Use a **new** Google account → after auth, role dialog appears | Two cards (Parent/Child); "Continue" disabled until a card tapped; tapping a card highlights it (primary border + tint) |
| 4 | US2 / FR-008 | In dialog, tap a role then "Continue" | Account created with that role; routed to correct home (Child → device setup) |
| 5 | US2 / FR-009 | In dialog, tap outside / press back | Dialog stays open (no dismiss) |
| 6 | US2 / FR-010 | In dialog, tap "Cancel" | Dialog closes; no account created; button interactive again; no spinner stuck |
| 7 | US3 / FR-004 | Tap Google → dismiss the account picker | Spinner clears; form usable; no error snackbar |
| 8 | US3 | Remove all Google accounts → tap Google | Graceful return to idle; no crash |
| 9 | FR-002 | Open Register → scroll to below "Create Account" | OR divider + "Continue with Google" present (flag on); same flow as Login |
| 10 | FR-016 | Force a backend 5xx during token exchange | Snackbar shows exactly "Sign-in failed. Please try again." (no codes) |
| 11 | FR-015 | Enable TalkBack → focus Google button, then role cards | Button announces "Google ... Continue with Google"; cards announce "Parent — I manage my child's device" / "Child — My device is monitored" |
| 12 | FR-014/SC-007 | Run flow with Firebase DebugView enabled | Events `google_signin_tapped`, then `google_signin_success` OR `google_signin_failed`, and `google_signin_role_selected` (new user) appear; no email/idToken in params |

## Automated checks

- **ViewModel unit (Turbine):** `cancelGoogleRoleSelection()` sets `needsRoleSelection=false`, `pendingGoogleIdToken=null`, `isGoogleSignInLoading=false` (FR-010). Role-required error → `needsRoleSelection=true` + token stored. Generic-error mapping (FR-016). Analytics helper invoked per transition (mock `AnalyticsHelper`).
- **Compose UI test:** button hidden when `isGoogleSignInEnabled=false`; dialog "Continue" disabled until selection; outside tap does not dismiss.

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:connectedDebugAndroidTest
```

## Edit 2 — Persistent button + OAuth verification (US4, US5)

### Persistent button (US4 / FR-017–FR-019)

| # | Maps to | Action | Expected |
|---|---------|--------|----------|
| 13 | US4 / FR-017 | Open Login with status endpoint unreachable (airplane mode or backend down) | Google button visible at first render |
| 14 | US4 / FR-017 | Wait 10s on the same screen | Button **still visible** (no flash-then-hide) |
| 15 | US4 / FR-018 | Inspect network trace on screen load | No `GET /oauth/google/status` call is made (status check removed); button visible regardless |
| 16 | US4 | Repeat 13–15 on Register screen | Same persistent behavior |

### OAuth + persistence verification (US5 / FR-020–FR-023)

| # | Maps to | Action | Expected |
|---|---------|--------|----------|
| 17 | FR-020 | Inspect `BuildConfig.GOOGLE_WEB_CLIENT_ID` | Real OAuth client-type-3 ID (`...apps.googleusercontent.com`), not placeholder |
| 18 | FR-021 | Sign in with a fresh Google account | `POST /oauth/google` returns 200 + access_token + user |
| 19 | FR-021/SC-009 | Inspect backend `users` table (source of truth; Firebase only verifies token, console Users empty by design) | Exactly one user row with the Google identity + chosen role |
| 20 | FR-022 | Sign out, sign in again same account | Still one row — no duplicate |
| 21 | FR-023 | Force backend persistence failure | Sign-in treated as failure: spinner clears, generic snackbar, no orphan session |

> Full backend procedure: [backend-google-auth-integration.md](./backend-google-auth-integration.md) §6.

### Edit 2 automated checks

- **Compose UI test (regression guard, FR-019):** assert Google button is displayed on first composition AND still displayed after `isGoogleSignInEnabled` resolves to `false`.
- **ViewModel unit:** status check returning `false`/throwing does NOT clear button visibility state.

## Done = all 12 original scenarios + scenarios 13–21 pass + unit/UI tests green. See [data-model.md](./data-model.md) for the state machine, [contracts/oauth-google.md](./contracts/oauth-google.md) for the API, and [backend-google-auth-integration.md](./backend-google-auth-integration.md) for backend endpoint edits.
