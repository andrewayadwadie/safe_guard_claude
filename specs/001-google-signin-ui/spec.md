# Feature Specification: Google Sign-In UI Integration

**Feature Branch**: `001-google-signin-ui`

**Created**: 2026-06-23

**Status**: Draft — Amended 2026-06-24 (Edit 2: persistent button + auth verification + backend integration)

**Input**: Wire existing Google Sign-In ViewModel/backend infrastructure to the UI — add Google button to Login and Register screens, implement role-selection dialog for new Google users.

---

## User Scenarios & Testing *(mandatory)*

### User Story 1 — Existing User Signs In via Google (Priority: P1)

A returning parent or child user opens the app, taps "Continue with Google" on the login screen, selects their Google account, and is immediately signed in and routed to the appropriate home screen — without entering a password.

**Why this priority**: This is the core happy-path for returning Google users. It unblocks all other Google Sign-In flows and provides immediate, measurable user value.

**Independent Test**: Open the login screen with Google Sign-In enabled on the backend. Tap "Continue with Google", pick an account that already exists on the backend, and verify the user lands on the correct home screen.

**Acceptance Scenarios**:

1. **Given** the login screen is open and `isGoogleSignInEnabled` is `true`, **When** the user taps "Continue with Google", **Then** the button shows a loading indicator and the system Google account picker appears.
2. **Given** the Google account picker is shown, **When** the user selects an account that already exists on the backend, **Then** the user is authenticated and navigated to the correct home screen with no role dialog shown.
3. **Given** the login screen is open, **When** the backend feature flag returns `false`, **Then** the "Continue with Google" button is hidden entirely.

---

### User Story 2 — New User Registers via Google and Selects Role (Priority: P1)

A brand-new user taps "Continue with Google" on either screen, signs in with a Google account that has no existing SafeGuard account, and is prompted to choose whether they are a Parent or Child before their account is created.

**Why this priority**: Without role selection, new users cannot be created via Google — the backend rejects the request. This dialog is a required gate for new Google Sign-In users.

**Independent Test**: Use a Google account with no existing SafeGuard account. After Google authentication completes, verify the role-selection dialog appears, selecting "Parent" completes registration and routes to the parent home screen.

**Acceptance Scenarios**:

1. **Given** a new user completes Google authentication, **When** the backend signals role is required, **Then** a role-selection dialog appears with "Parent" and "Child" card options.
2. **Given** the role dialog is open, **When** the user taps a role card, **Then** that card is visually highlighted (primary color border + tinted background) and the "Continue" button becomes enabled.
3. **Given** a role is selected, **When** the user taps "Continue", **Then** the account is created with the selected role and the user is routed to the correct home screen.
4. **Given** the role dialog is open, **When** the user taps "Cancel", **Then** the dialog closes, the Google sign-in loading state resets, and the user returns to the auth screen — no account is created.
5. **Given** the role dialog is open, **When** the user taps outside the dialog, **Then** nothing happens — the dialog does not dismiss (user must make a choice or cancel explicitly).

---

### User Story 3 — User Cancels or Has No Google Accounts (Priority: P2)

A user starts the Google Sign-In flow but cancels the account picker, or their device has no Google accounts configured. The app handles these cases gracefully without crashing or showing a confusing error.

**Why this priority**: Error paths must not break the auth screen. A cancelled Google flow should simply return the user to a clean state.

**Independent Test**: Tap "Continue with Google" then dismiss the account picker. Verify the loading indicator disappears and the login form is still usable.

**Acceptance Scenarios**:

1. **Given** the Google account picker is shown, **When** the user presses back/cancels, **Then** the loading indicator disappears and the auth screen returns to its normal interactive state with no error message.
2. **Given** the device has no Google accounts configured, **When** the user taps "Continue with Google", **Then** the picker fails gracefully and the auth screen returns to its normal state (no crash, no error snackbar required).

---

### User Story 4 — Google Button Stays Visible (Priority: P1) *(Edit 2)*

A user opens the login (or register) screen. The "Continue with Google" button is present immediately and **stays present** for the whole time the screen is open. It does not flash and then disappear a few seconds later.

**Why this priority**: Current bug — button shows on first render (default flag `true`) then the async server status check returns `false` (endpoint missing/unreachable/disabled) and the button vanishes after ~3 seconds. Result: users cannot use Google Sign-In at all. P1 because it blocks the entire feature.

**Root cause**: `AuthViewModel.checkGoogleOAuthStatus()` calls `AuthRepository.isGoogleOAuthEnabled()`, which returns `false` on any failure/non-200/exception (`AuthRepository.kt:218-230`). That `false` overwrites the `isGoogleSignInEnabled = true` default (`AuthViewModel.kt:35,90`), hiding the button.

**Independent Test**: Open login screen with the status endpoint unreachable or returning a non-200. Verify the Google button is visible at first render and remains visible after 10 seconds (no disappearance).

**Acceptance Scenarios**:

1. **Given** the login screen is open, **When** the screen first renders, **Then** the "Continue with Google" button is visible without delay.
2. **Given** the status endpoint is unreachable, returns non-200, or throws, **When** the status check completes, **Then** the button remains visible (failure MUST NOT hide the button).
3. **Given** the screen has been open for 10+ seconds, **When** no user action is taken, **Then** the button is still visible (no flash-then-hide).
4. **Given** the register screen is open, **When** any of scenarios 1–3 apply, **Then** the same persistent-visibility behavior holds.

---

### User Story 5 — Verify Firebase OAuth & User Persistence (Priority: P1) *(Edit 2)*

An engineer runs a verification pass confirming the Google OAuth chain is wired correctly end-to-end: the Firebase/Google OAuth Web Client ID is valid, the Google ID token is obtained and exchanged with the backend, authentication completes, and the resulting user is actually persisted (visible in the Firebase console / backend user store).

**Why this priority**: Without proof the OAuth config and persistence work, "sign-in success" in the UI may be hollow (token accepted but no user saved). P1 — gates release.

**Independent Test**: Run the verification checklist in `quickstart.md` (Edit 2 section). Confirm: (a) `GOOGLE_WEB_CLIENT_ID` is the real OAuth client-type-3 ID, (b) token exchange returns 200, (c) the test Google account appears as a row in the backend `users` table after first sign-in (FastAPI DB is source of truth; Firebase only verifies the token).

**Acceptance Scenarios**:

1. **Given** a built app, **When** `BuildConfig.GOOGLE_WEB_CLIENT_ID` is inspected, **Then** it is a non-empty, non-placeholder Web Client ID matching the Firebase project's OAuth client (type 3).
2. **Given** a valid Google account, **When** the user completes Google Sign-In, **Then** the backend token-exchange endpoint returns 200 with a valid session/JWT.
3. **Given** a first-time Google user completes sign-in (with role selected), **When** the backend `users` table is inspected (source of truth; Firebase only verifies the token), **Then** a user record exists with the Google identity (email, `google_sub`, provider = google) and selected role.
4. **Given** the same Google user signs in a second time, **When** the backend processes the token, **Then** no duplicate user is created — the existing record is reused.

---

### Edge Cases

- What happens when the backend is unreachable during Google token exchange? → Loading state resets, existing error-snackbar mechanism displays the error.
- *(Edit 2)* What if the status endpoint never responds or returns `false`? → Button MUST stay visible regardless (FR-017). Visibility no longer depends on the status flag.
- *(Edit 2)* What if Google auth succeeds but the backend fails to persist the user? → Treated as sign-in failure: loading resets, generic snackbar shown, no partial/orphan session.
- What if the user rapidly double-taps the Google button? → Button disabled during loading prevents duplicate requests.
- What if the user rotates the screen while the role dialog is open? → Dialog must survive configuration change (ViewModel state persists the `needsRoleSelection` flag).
- What if `isGoogleSignInEnabled` changes from `false` to `true` after the screen is open? → Button appears on next recomposition (StateFlow drives visibility).

---

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: ~~The login screen MUST display a "Continue with Google" button only when the backend feature flag `isGoogleSignInEnabled` is `true`.~~ **SUPERSEDED by FR-017 (Edit 2)** — the login screen MUST always display the button.
- **FR-002**: ~~The register screen MUST display a "Continue with Google" button only when the backend feature flag `isGoogleSignInEnabled` is `true`.~~ **SUPERSEDED by FR-017 (Edit 2)** — the register screen MUST always display the button.
- **FR-003**: The Google Sign-In button MUST show a circular loading indicator in place of the icon and label while a sign-in attempt is in progress.
- **FR-004**: The Google Sign-In button MUST be disabled whenever the standard email/password sign-in or the Google sign-in is already in progress.
- **FR-005**: Both screens MUST display an "OR" divider (text flanked by horizontal lines) between the primary action button and the Google Sign-In button.
- **FR-006**: When Google authentication completes for a new user with no existing account, the app MUST show a role-selection dialog before completing registration.
- **FR-007**: The role-selection dialog MUST offer exactly two roles: Parent (with shield iconography and "I manage my child's device" subtitle) and Child (with child iconography and "My device is monitored" subtitle).
- **FR-008**: The role-selection dialog's "Continue" button MUST remain disabled until the user explicitly selects a role card.
- **FR-009**: The role-selection dialog MUST NOT dismiss when the user taps outside it.
- **FR-010**: Tapping "Cancel" on the role-selection dialog MUST reset all Google sign-in loading state and return the user to the auth screen without creating any account.
- **FR-011**: The `BuildConfig.GOOGLE_WEB_CLIENT_ID` field MUST be populated with the actual Web Client ID (OAuth client type 3) from the project's Firebase configuration.
- **FR-012**: Successful Google sign-in (existing or new user) MUST follow the same post-authentication navigation paths as email/password sign-in — no new navigation logic required.
- **FR-013**: The Google Sign-In button visual style MUST use an outlined/surface style (not filled primary) with a Google "G" icon on the left, full width, 50dp height, 12dp corner radius.
- **FR-014**: The app MUST emit analytics events at these points: (1) user taps "Continue with Google", (2) Google sign-in succeeds, (3) Google sign-in is cancelled or fails, (4) user selects a role in the role-selection dialog.
- **FR-015**: The Google "G" icon within the button MUST have an accessibility content description of "Google" for screen readers. Role-selection dialog cards MUST expose their title and subtitle as a single merged semantic label to TalkBack (e.g., "Parent — I manage my child's device").
- **FR-016**: All Google Sign-In backend failures (network error, 5xx, invalid token) MUST surface to the user as a single snackbar message: "Sign-in failed. Please try again." No backend error code or technical detail may be shown.

#### Edit 2 — Persistent Button, Verification, Backend Integration

- **FR-017**: The "Continue with Google" button MUST be visible at all times on both Login and Register screens, independent of the backend OAuth status flag. The button MUST NOT be hidden as a result of the status check returning `false`, a non-200 response, or an exception. (Replaces flag-gated visibility from FR-001/FR-002.)
- **FR-018**: The OAuth status check MUST be removed entirely — delete `AuthViewModel.checkGoogleOAuthStatus()`, the `AuthRepository.isGoogleOAuthEnabled()` call site, and the `isGoogleSignInEnabled` visibility gating. No network call gates the button. (The `GET /oauth/google/status` endpoint and its Retrofit binding may remain unused for now.)
- **FR-019**: An automated UI test MUST assert the Google button is present on first render AND still present after the status check resolves to `false`/failure (guards the disappearing-button regression).
- **FR-020**: A verification procedure MUST confirm `BuildConfig.GOOGLE_WEB_CLIENT_ID` is a real, non-placeholder OAuth Web Client ID (client type 3) from the Firebase project.
- **FR-021**: Verification is hybrid. Automated tests MUST cover button persistence (FR-019) and the client-ID assertion (FR-020). A documented manual checklist (quickstart Edit 2 scenarios 17–21) MUST confirm the live chain that needs a real Google account + backend: ID token obtained → token exchange returns 200 → session/JWT issued → user persisted in the backend `users` table → no duplicate on repeat (FastAPI DB is source of truth; Firebase only verifies the token).
- **FR-022**: Repeat Google sign-in by the same account MUST NOT create a duplicate user record — backend MUST upsert/lookup by Google identity (provider + email/sub).
- **FR-023**: If Google auth succeeds but backend persistence fails, the app MUST treat it as a sign-in failure (reset loading, show generic snackbar, no partial session).
- **FR-024**: Backend integration instructions MUST be documented in `specs/001-google-signin-ui/backend-google-auth-integration.md`, describing the changes to the login and register API endpoints needed to accept a Google ID token, verify it, and create or fetch the user.
- **FR-025**: Google authentication MUST be accepted on BOTH the dedicated `POST /oauth/google` endpoint (primary, used by the current client) AND, as a fallback, on `auth/login` and `auth/register` via an optional `id_token` field. All three paths MUST share one token-verification + create-or-fetch routine so behavior (verification, role gate, no-duplicate, persistence) is identical regardless of entry point.

### Key Entities

- **GoogleSignInButton**: Reusable UI component, parameterized by `onClick`, `isLoading`, `enabled`. Used on both Login and Register screens.
- **RoleSelectionDialog**: Modal UI component, parameterized by `onRoleSelected(UserRole)` and `onDismiss`. Shows exclusively for new Google users.
- **AuthUiState**: Existing ViewModel state — drives button visibility (`isGoogleSignInEnabled`), loading state (`isGoogleSignInLoading`), and dialog visibility (`needsRoleSelection`).
- **UserRole**: Existing domain enum — `PARENT` / `CHILD`. Passed to ViewModel on role confirmation.

---

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A returning Google user completes sign-in from tapping the button to landing on the home screen in under 10 seconds on a standard mobile connection.
- **SC-002**: A new Google user completes registration (including role selection) in under 60 seconds.
- **SC-003**: The "Continue with Google" button is invisible on devices or configurations where the backend flag is `false` — 0% false-positive button appearances.
- **SC-004**: Google Sign-In flow cancellation returns the user to a fully interactive auth screen 100% of the time — no stuck loading states.
- **SC-005**: The role-selection dialog appears on 100% of new-Google-user flows where the backend signals role is required.
- **SC-006**: Zero regressions in email/password sign-in or registration flows after Google button addition.
- **SC-007**: Analytics events for Google Sign-In (tap, success, failure, role selected) are captured in 100% of qualifying flows — no silent event loss.
- **SC-008**: *(Edit 2)* The Google button remains visible for 100% of the time the auth screen is open, including when the status endpoint is unreachable or returns disabled — 0% disappearance.
- **SC-009**: *(Edit 2)* 100% of first-time Google sign-ins result in exactly one persisted user record in the backend `users` table (source of truth) within 10 seconds; repeat sign-ins create 0 duplicates.
- **SC-010**: *(Edit 2)* The OAuth verification checklist passes end-to-end (valid client ID, 200 token exchange, user persisted) before release sign-off.

---

## Clarifications

### Session 2026-06-24 (Edit 2 — Clarify)

- Q: Backend approach — edit login/register endpoints, dedicated /oauth/google, or both? → A: Both — `/oauth/google` primary; `auth/login` and `auth/register` also accept an optional `id_token` as fallback (FR-024, FR-025).
- Q: User store / "saved in Firebase console" — FastAPI DB, Firebase Auth, or dual? → A: FastAPI `users` table is source of truth; Firebase only verifies the Google ID token. Backend is not on Firebase Auth, so the console Users list is not the verification surface — verify persistence via the DB (FR-021, SC-009).
- Q: OAuth status check — keep for telemetry or remove? → A: Remove entirely. Delete `checkGoogleOAuthStatus()` and the `getGoogleOAuthStatus()` call; drop the `isGoogleSignInEnabled` gating (FR-017, FR-018).
- Q: Verification — automated, manual, or hybrid? → A: Hybrid. Automate button-persistence (FR-019) + client-ID assertion (FR-020); manual checklist for live token-exchange + DB persistence + no-duplicate (FR-021, FR-022).

### Session 2026-06-24 (Edit 2)

- Q: Button shows then disappears after ~3s — desired behavior? → A: Button MUST always be visible; status-flag gating removed (FR-017/FR-018).
- Q: What counts as "auth done correctly"? → A: Valid client ID + 200 token exchange + session issued + user persisted and visible in Firebase console, with no duplicate on repeat sign-in (US5, FR-020–FR-023).
- Q: Where do backend endpoint instructions live? → A: Dedicated `backend-google-auth-integration.md` describing login/register endpoint edits (FR-024).

### Session 2026-06-23

- Q: Should Google Sign-In events be tracked for analytics? → A: Track key conversion events — button tap, sign-in success, sign-in cancelled/failed, role selected.
- Q: Accessibility requirements for Google button and role-selection cards? → A: Minimal — G icon has content description "Google"; role cards announce title + subtitle to TalkBack.
- Q: Error message shown when Google sign-in fails due to backend error? → A: Single generic message "Sign-in failed. Please try again." for all Google-specific backend failures.

---

## Assumptions

- Google Sign-In feature flag is already fetched by the ViewModel on screen load via `GET /oauth/google/status` — no new API calls needed for the UI layer.
- The existing post-authentication `LaunchedEffect` in both screens correctly handles navigation for all auth methods including Google — no new navigation destinations or graph changes are required.
- `UserRole` enum already exists in the domain layer with at least `PARENT` and `CHILD` values.
- `SafeGuardTheme` `MaterialTheme.colorScheme` tokens are sufficient to style both the `GoogleSignInButton` and `RoleSelectionDialog` cards without custom colors.
- The `pendingGoogleIdToken` lifetime is scoped to the ViewModel — if the process is killed while the dialog is open, the user simply restarts the Google flow.
- No child-monitored data is collected, transmitted, or stored in this feature. This feature operates exclusively on parent/child account authentication metadata (role, ID token). No Principle I implications.
- The role-selection dialog is applicable to both Login and Register screens because either screen may be the entry point for a first-time Google user.
