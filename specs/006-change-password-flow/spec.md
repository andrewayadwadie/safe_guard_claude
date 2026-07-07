# Feature Specification: Change Password Flow

**Feature Branch**: `006-change-password-flow`

**Created**: 2026-07-06

**Status**: Draft

**Input**: User description: "Implement the Change Password flow. The Change Password row in Settings currently does nothing; wire it to a dedicated screen with current-password and new-password fields, submit to the change-password service, pop back on success, show an error on failure."

## Clarifications

### Session 2026-07-06

- Q: After a successful password change, what happens to the signed-in session? → A: Stay signed in; just return to Settings with the current session/token still valid (no forced sign-out or re-login).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Change my account password (Priority: P1)

A signed-in user wants to update their account password from within the app. They open Settings, tap the "Change Password" row, land on a dedicated screen, enter their current password and a new password, and submit. On success they are returned to Settings; on failure they see a clear reason and can try again.

**Why this priority**: This is the entire feature. The Change Password row already exists in Settings but is inert ("Coming soon"); users currently have no in-app way to rotate their password, which is a basic account-security expectation. Without it there is no value delivered.

**Independent Test**: With a logged-in account, tap "Change Password" in Settings → screen opens → enter valid current + new password → submit → verify the user is returned to Settings and the new password works on next sign-in. Fully testable on its own; no other stories required.

**Acceptance Scenarios**:

1. **Given** a signed-in user on the Settings screen, **When** they tap "Change Password", **Then** a Change Password screen opens with a Current Password field and a New Password field.
2. **Given** the Change Password screen with a correct current password and a valid new password entered, **When** the user submits, **Then** the password is changed and the user is returned to the previous screen.
3. **Given** the Change Password screen, **When** the user submits an incorrect current password (rejected by the service), **Then** an error message is shown and the user remains on the screen to retry.
4. **Given** the Change Password screen, **When** a submission is in progress, **Then** the submit control shows a busy state and cannot be triggered again until the request completes.
5. **Given** the Change Password screen, **When** the user taps the back control, **Then** they return to Settings without any password change.

### Edge Cases

- **Blank field(s)**: Submitting with an empty current or new password is blocked with a validation message; no request is sent.
- **New password too short**: A new password shorter than the minimum length is rejected client-side with a message; no request is sent.
- **New password equals current**: Submitting a new password identical to the current password is rejected client-side with a message; no request is sent.
- **Server rejection**: The service rejects the change (e.g., wrong current password, policy violation); the returned reason is surfaced to the user and the screen stays open.
- **Password visibility**: Each field is masked by default and can be individually toggled to reveal/hide its contents.
- **Successful change**: On success the user is returned automatically; no manual dismissal needed.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The Settings "Change Password" row MUST be actionable and navigate the user to a dedicated Change Password screen when tapped. Its previous "Coming soon" indicator MUST be removed.
- **FR-002**: The Change Password screen MUST present exactly two inputs — Current Password and New Password — each masked by default with an individual show/hide toggle, and MUST provide a back control that returns to Settings without making changes.
- **FR-003**: The system MUST validate, before contacting the service, that both fields are non-empty, that the new password meets the minimum length of 8 characters, and that the new password differs from the current password; failing validation MUST show a message and MUST NOT send a request.
- **FR-004**: On submit with valid input, the system MUST send the current and new password to the change-password service and MUST reflect an in-progress state that prevents duplicate submissions until the request completes.
- **FR-005**: On a successful change, the system MUST return the user to the previous screen (Settings) automatically and keep the user signed in — the current session/token remains valid; no forced sign-out or re-login is triggered by this flow.
- **FR-006**: On a failed change, the system MUST display the failure reason returned by the service using the app's existing feedback mechanism and MUST keep the user on the Change Password screen so they can retry.
- **FR-007**: The screen MUST visually match the app's existing design system (consistent with the sign-in screen's field, icon, and button styling); no new visual widget styles are introduced.

### Key Entities *(include if feature involves data)*

- **Password change request**: The pair of values a user submits — their current password and desired new password. Transient; not persisted in the app beyond the request.
- **Change result**: The outcome of a submission — either success (return to Settings) or a failure carrying a human-readable reason to display.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A signed-in user can complete a password change — from tapping the Settings row to returning to Settings — in under 30 seconds with valid inputs.
- **SC-002**: 100% of invalid client-side inputs (blank field, new password under 8 characters, new password equal to current) are caught before any service request and produce a clear message.
- **SC-003**: Every service-side failure results in a visible reason shown to the user, with the user retained on the screen (0% silent failures).
- **SC-004**: On success the user is returned to Settings automatically without any extra tap, and the changed password is valid for the next sign-in.

## Assumptions

- The change-password service (and its request/response handling) already exists and is reused as-is; this feature only adds the screen, its logic, and navigation wiring.
- Both account roles that reach Settings may use this flow; the row is not role-restricted.
- Minimum new-password length is 8 characters (client-side gate); any stricter server policy is enforced by the service and surfaced as a failure reason.
- The current session/account context needed to authorize the change is already available; no re-authentication step beyond entering the current password is required.
- Feedback for failures uses the app's existing transient-message (snackbar) pattern; success needs no confirmation message because the automatic return to Settings is the confirmation.
- Password reset via email / forgotten-password flow is out of scope; this feature covers only changing a known password while signed in.
