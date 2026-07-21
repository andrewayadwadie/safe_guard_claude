# Feature Specification: Forgot Password

**Feature Branch**: `009-forgot-password`

**Created**: 2026-07-16

**Status**: Draft

**Input**: User description: "create forget password feature in this project by using endpoint api/v1/auth/forgot-password POST (body: email; success: generic 'reset code sent' message; 429: rate limit exceeded) then /api/v1/auth/reset-password POST (body: email, code, new_password; success: 'Password has been reset successfully'; 400: invalid or expired reset code; 422: password too short — min 8 characters) for redeem code, check sent code is correct, then return to current screen. Create UI matching app theme and design system and make sure everything works."

## Clarifications

### Session 2026-07-16

- Q: Should the resend-code button have a client-side cooldown timer? → A: Yes — 60-second countdown after each send; resend button disabled and shows remaining seconds.
- Q: How should the 6-digit reset code be entered? → A: Six separate OTP-style digit boxes with auto-advance and paste support.
- Q: After a successful password reset, how should the user return to the login screen? → A: Navigate to the login screen immediately and show the success confirmation as a snackbar there.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Request a password reset code (Priority: P1)

A parent who has forgotten their password taps "Forgot Password?" on the login screen, enters the email address associated with their account, and submits the request. The app confirms that, if an account exists for that email, a reset code has been sent — without revealing whether the account actually exists.

**Why this priority**: Without the ability to request a reset code, a locked-out parent permanently loses access to their account and to their child's safety controls. This is the entry point of the entire recovery flow.

**Independent Test**: Can be fully tested by tapping "Forgot Password?" on the login screen, entering an email, and observing the confirmation message. Delivers value as the first recoverable step even before the code-entry screen exists.

**Acceptance Scenarios**:

1. **Given** the login screen is displayed, **When** the user taps "Forgot Password?", **Then** a screen appears asking for their email address.
2. **Given** the email entry screen, **When** the user submits a validly formatted email, **Then** a loading indicator is shown while the request is in flight and, on success, the app shows the neutral confirmation ("If an account exists for that email, a password reset code has been sent") and advances to the code-entry step.
3. **Given** the email entry screen, **When** the user submits an empty or malformed email, **Then** an inline validation error is shown and no request is sent.
4. **Given** the user has requested codes too many times, **When** the service responds with a rate-limit rejection, **Then** the app shows a clear "too many attempts — try again later" message and the user remains on the email screen.
5. **Given** the device has no connectivity, **When** the user submits, **Then** a network error message is shown and the user can retry.

---

### User Story 2 - Redeem the code and set a new password (Priority: P1)

After receiving the reset code by email, the parent enters the code together with a new password. If the code is valid and the password meets the rules, the password is changed, a success confirmation is shown, and the user is returned to the login screen to sign in with the new password.

**Why this priority**: Completes account recovery; User Story 1 alone does not restore access. Both P1 stories together form the MVP.

**Independent Test**: With a known valid reset code, can be tested by entering the code and a new password on the reset screen and verifying the success message and return to login.

**Acceptance Scenarios**:

1. **Given** the code-entry screen (email carried over from the previous step), **When** the user enters a valid code and a new password meeting the minimum length (8 characters), **Then** the password is reset, the user is returned immediately to the login screen, and a success snackbar ("Password has been reset successfully") is shown there.
2. **Given** the code-entry screen, **When** the user enters an incorrect or expired code, **Then** the app shows "Invalid or expired reset code" and lets the user correct the code or request a new one.
3. **Given** the code-entry screen, **When** the user enters a password shorter than 8 characters, **Then** an inline validation error ("must be at least 8 characters") is shown — caught locally before submission where possible, and also handled gracefully if the server rejects it.
4. **Given** the new-password fields, **When** the user types, **Then** the password is masked with a show/hide toggle, and a confirm-password field must match before submission is allowed.
5. **Given** a successful reset, **When** the user signs in with the new password, **Then** login succeeds.

---

### User Story 3 - Resend code and graceful recovery (Priority: P2)

A parent whose code never arrived (or expired) can request a new code from the code-entry screen without restarting the whole flow, and can navigate back to correct a mistyped email.

**Why this priority**: Improves completion rate of the recovery flow but the flow is usable without it (user can restart from login).

**Independent Test**: From the code-entry screen, tap "Resend code" and verify a new request is issued with the same email and the confirmation message reappears; tap back and verify return to the email step with the email preserved.

**Acceptance Scenarios**:

1. **Given** the code-entry screen, **When** the user taps "Resend code" (available only after the 60-second cooldown elapses), **Then** a new reset request is sent for the same email, the user stays on the code-entry screen, and the cooldown restarts.
2. **Given** a code was just sent, **When** the user views the resend action during the following 60 seconds, **Then** it is disabled and displays the remaining seconds.
3. **Given** the server still rejects with a rate limit, **When** the user resends after the cooldown, **Then** the rate-limit message is shown and the cooldown restarts.
4. **Given** the code-entry screen, **When** the user navigates back, **Then** they return to the email screen with their previously entered email preserved.

---

### Edge Cases

- Email with surrounding whitespace or mixed case is trimmed/normalized before submission.
- App is backgrounded or the process is interrupted mid-flow: returning to the app does not crash; user can restart the flow safely.
- Server returns an unexpected error (5xx, malformed body): a generic "something went wrong, try again" message is shown; raw server details are never displayed.
- User submits while a request is already in flight: duplicate submissions are prevented (button disabled during loading).
- Code entry: only digits accepted in the six OTP boxes; pasting a 6-digit code fills all boxes; in Arabic (RTL) locale the code boxes still read left-to-right as a numeric sequence.
- Reset succeeds but the user force-closes before returning to login: on next launch the user can simply log in with the new password (no dangling state).
- Arabic locale: all new screens render correctly in RTL with Arabic strings, consistent with the app's localization support.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The login screen MUST offer a "Forgot Password?" entry point that starts the recovery flow.
- **FR-002**: The recovery flow MUST collect the user's email address and validate its format locally before submitting.
- **FR-003**: On submission, the system MUST request a reset code for the given email and display the service's neutral confirmation without revealing whether an account exists (anti-enumeration).
- **FR-004**: When the reset-code request is rate-limited, the app MUST show a distinct "too many attempts" message and keep the user on the current step.
- **FR-005**: The flow MUST present a code-redemption step where the user enters the received code into six separate OTP-style digit boxes (auto-advance between digits, paste of a full code supported) and a new password (with confirmation field and show/hide toggle).
- **FR-006**: The app MUST validate locally that the new password is at least 8 characters and that both password fields match, before submitting.
- **FR-007**: On submission of code + new password, the system MUST attempt the reset and, on success, immediately return the user to the login screen and display the success confirmation ("Password has been reset successfully") as a transient snackbar on the login screen.
- **FR-008**: When the service rejects the code as invalid or expired, the app MUST show that specific error and allow retry or resend.
- **FR-009**: When the service rejects the password as too short (or otherwise invalid), the app MUST surface the validation message inline on the password field.
- **FR-010**: The user MUST be able to request a new code (resend) from the code-redemption step without re-entering their email; the resend action is disabled for 60 seconds after each send and displays the remaining cooldown time.
- **FR-011**: All requests MUST show a loading state, block duplicate submissions, and handle network failures with a retryable error message.
- **FR-012**: All new screens MUST follow the app's existing visual design system (colors, typography, spacing, buttons, text fields) and support both English and Arabic with correct RTL layout.
- **FR-013**: The flow MUST never store or log the reset code or new password beyond what is needed to perform the request.

### Key Entities

- **Password Reset Request**: the email address for which a reset was requested; transient, exists only for the duration of the flow.
- **Reset Code**: short numeric code (6 digits) delivered by email; entered by the user, validated by the service; never persisted by the app.
- **New Password**: user-chosen secret, minimum 8 characters; transmitted once for the reset, never stored locally.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A user who knows their email can complete the full recovery flow (request code → enter code → set new password → back at login) in under 2 minutes, excluding email delivery time.
- **SC-002**: 100% of the defined error cases (rate limit, invalid/expired code, short password, network failure) produce a human-readable message — no raw server payloads or crashes.
- **SC-003**: After a successful reset, signing in with the new password succeeds on the first attempt.
- **SC-004**: All new screens render correctly in both English (LTR) and Arabic (RTL) with no truncated or overlapping text.
- **SC-005**: An account's existence is never disclosed by the flow — the confirmation message is identical for registered and unregistered emails.

## Assumptions

- The backend endpoints already exist and behave as documented by the user: `POST api/v1/auth/forgot-password` (body: `email`; 200 success with neutral message; 429 `{"detail": "Rate limit exceeded"}`) and `POST /api/v1/auth/reset-password` (body: `email`, `code`, `new_password`; 200 success; 400 `{"detail": "Invalid or expired reset code"}`; 422 validation detail array, e.g. `string_too_short` for passwords under 8 characters). No backend changes are in scope.
- The reset code is a 6-digit numeric code (per the provided example `955545`) delivered via email by the backend; email delivery itself is out of scope.
- The flow applies to the parent account's email/password login; Google Sign-In accounts recover access through Google, not this flow.
- "Return to current screen" means returning to the login screen after a successful reset, since the flow is entered from login while signed out.
- Password policy enforced client-side is minimum 8 characters (matching the server's 422 rule); no additional complexity rules are required unless the server rejects them.
- Both requests are unauthenticated (user is signed out); no auth token is attached.
- New user-facing strings will be provided in both English and Arabic, consistent with the existing localization feature (008).
