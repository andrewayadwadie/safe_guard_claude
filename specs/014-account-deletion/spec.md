# Feature Specification: Permanent Account Deletion

**Feature Branch**: `014-account-deletion`

**Created**: 2026-09-24

**Status**: Draft

**Input**: User description: "Permanent account deletion and parent-initiated child account deletion (Google Play data-deletion requirement). Google Play requires any app that lets users create an account to also let them delete that account and its data from inside the app. Haris has no deletion flow today; this is the last hard blocker before Play Store submission. The backend capability already exists and is verified in production; this feature is the in-app experience for it."

## Clarifications

### Session 2026-09-24

- Q: Does Google Sign-In ship in the release build, and how does the app know whether the signed-in account has a password? → A (resolved from the codebase, no user question needed): Yes. The release build guard refuses to build without a real Google web client ID, so Google Sign-In ships. The account profile the server returns already states the sign-in method (password-based or Google). Google accounts confirm deletion by typing the exact word `DELETE` instead of a password.
- Q: When a child device's session ends because the account was deleted remotely, what should the device do? → A: Option C. Stop protection only when the server positively confirms the account was deleted. Any other session loss keeps today's behaviour (return to login, protection keeps running). If the backend cannot tell deletion apart from other session loss, fall back to today's behaviour.
- Q: What must a parent provide to delete a child's account, given that a confirmed deletion now switches off protection on the child's device? → A: Option B. The parent types the child's name AND re-enters their own password (Google parents type `DELETE`). An unlocked parent phone alone must not be enough to delete a child's account. *Amended during planning (2026-09-24): the child-deletion endpoint takes no credential, and the server cannot check a typed `DELETE` for this action, so a Google parent instead confirms with a fresh Google sign-in to the same account, which the server does check (plan research RD-1). Password parents are unchanged.*
- Q: After a child device's account is deleted, how should the app handle the special permissions that remain granted? → A: Option B. The login screen shown after deletion adds a short note that protection is off and the app can now be uninstalled, with a button that opens the app's system settings page. The app does not trigger an uninstall itself.
- Q: What happens if the app is killed after the deletion request is sent but before local cleanup finishes? → A: Option B. The app records "deletion in progress" before sending. On the next launch it asks the server. If the server confirms the account was deleted, the app finishes the full cleanup, including stopping protection on a child device and showing the success note. Otherwise it clears the marker and continues as normal. *Amended during planning (2026-09-24): the backend answers with the same 401 for a deleted account as for any other lost session, so "confirms deleted" cannot be detected. The fallback is a marker plus a 401: the app runs the full cleanup with neutral "session ended" wording (never "Account deleted", and no uninstall note), but only on a parent device or a child the server last reported as unlinked. On a linked child it keeps today's behaviour and protection keeps running (plan research RD-3). This is revisited if backend question Q6 provides a distinct signal.*

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Parent deletes their own account (Priority: P1)

From Settings, a parent opens "Delete account". A screen lists in plain words what will be permanently deleted: their account, every linked child's account, and all devices, screen-time history, app-usage history, alerts, rules, filters and family links. The number of linked children is shown when known. The parent re-enters their password (or types `DELETE` for a Google account), then confirms a second time in a final step that names the action as irreversible. The account is deleted, the app returns to the login screen in the same state as a fresh install, and a message confirms the account was deleted.

**Why this priority**: This is the Google Play requirement itself. Without it the app cannot be submitted.

**Independent Test**: Sign in as a parent with one linked child, delete the account from Settings, and verify the app lands on login with a success message. The parent and child credentials must then both fail to sign in, and a cold restart must still show login with no prior data.

**Acceptance Scenarios**:

1. **Given** a signed-in parent on the Delete account screen, **When** they enter the correct password and confirm twice, **Then** the account and all linked children are deleted, the app shows the login screen, and a success confirmation is displayed.
2. **Given** a signed-in parent on the Delete account screen, **When** they enter a wrong password and confirm, **Then** an inline error appears on the password field, nothing is deleted, they stay signed in, and they can correct the password and retry.
3. **Given** the password field is empty (or, for a Google account, the text is not exactly `DELETE`), **When** the parent looks at the confirm action, **Then** it is disabled.
4. **Given** the parent has made too many recent attempts and the server is rate-limiting them, **When** they confirm, **Then** the message tells them to wait and try again later. It never says "wrong password" and the password field is not marked as wrong.
5. **Given** the device has no network or the request times out, **When** the parent confirms, **Then** the message states that nothing was deleted and offers a retry. The entered password is kept so retrying needs no retyping.
6. **Given** the parent's session has already expired or been revoked, **When** they confirm, **Then** the app treats the session as over: local state is cleared and the parent is taken to login with a "session ended, please sign in again" message. It never shows "account deleted".
7. **Given** the parent taps confirm twice quickly, rotates the phone, or leaves the screen while the request is in flight, **When** the request completes, **Then** exactly one deletion request was sent, and if the server reported success the local cleanup and the return to login still complete.
8. **Given** a deletion has succeeded, **When** the app is force-stopped and cold-started, **Then** it opens on login with no trace of the previous user: no pre-filled email, no cached names, no cached children, no alerts.
9. **Given** a Google Sign-In parent, **When** they open Delete account, **Then** the confirmation field asks them to type `DELETE` instead of a password, and the rest of the flow behaves as in scenarios 1 to 8.

---

### User Story 2 - Parent deletes a linked child's account (Priority: P1)

From the Children screen, a parent opens a child's actions and chooses "Delete account permanently". This is shown apart from, and styled differently from, the existing unlink action, which is renamed so it clearly reads as "Stop monitoring (account kept)". The confirmation names the child, states that the deletion is permanent and cannot be undone, and explains the difference from stopping monitoring. To enable the final action the parent must type the child's name exactly as shown and re-enter their own password (a Google parent instead confirms with a fresh Google sign-in to the same account, because the server cannot check a typed `DELETE` for this action). Without the password, a child who picked up the parent's unlocked phone could delete their own account and escape supervision. On success, the child disappears from the list and a confirmation appears.

**Why this priority**: A parent is the only person allowed to delete a supervised child's account (see US4). Without this, a supervised child's data could only be removed by deleting the whole family, which Play reviewers and parents would reasonably consider inadequate.

**Independent Test**: Sign in as a parent with two linked children, delete one child's account, and verify that only that child disappears from the list. The deleted child's credentials must then fail to sign in, and the other child must be unaffected.

**Acceptance Scenarios**:

1. **Given** a parent viewing a linked child's actions, **When** they look at the options, **Then** "Stop monitoring (account kept)" and "Delete account permanently" appear as separate actions. The delete action uses the error colour, sits apart from the other action, and is never the default or primary one.
2. **Given** the delete-child confirmation is open, **When** the typed name does not exactly match the child's displayed name, or the parent's password field is empty, **Then** the delete action stays disabled. A Google parent is asked to confirm with Google, and cancelling that sign-in deletes nothing.
3. **Given** the typed name matches and the parent's credential is correct, **When** the parent confirms, **Then** the child's account and all of its data are deleted, the child disappears from the list, and a confirmation names the deleted child.
4. **Given** the child was already unlinked or deleted elsewhere, **When** the parent confirms, **Then** the list refreshes and an informative message says this child is no longer linked to their account. It is not shown as a generic failure.
5. **Given** the device has no network or the request times out, **When** the parent confirms, **Then** the message states that nothing was deleted and offers a retry, and the child remains in the list.
6. **Given** the parent double-taps confirm or rotates the screen mid-request, **When** the request completes, **Then** exactly one deletion request was sent for that child.
7. **Given** the parent's session has expired, **When** they confirm, **Then** it is handled as "session over" (as in US1 scenario 6), and no success message is shown.
8. **Given** the typed name matches but the parent's password is wrong, **When** the parent confirms, **Then** an inline error appears on the password field, nothing is deleted, the child stays in the list, and the parent can retry. A rate-limit response is handled as in US1 scenario 4 and never shown as "wrong password".

---

### User Story 3 - Unlinked child deletes their own account (Priority: P2)

A child account that is not paired to any parent opens "Delete account" from Settings. The flow matches US1, but the wording suits a child and does not mention linked children. After success, all protection and monitoring on the device stops cleanly, without tamper or "service stopped" alerts and without restarting itself. The device returns to login in a fresh-install state.

**Why this priority**: Play requires every account holder to be able to delete their account. Unlinked child accounts are rare (most child accounts are paired), so this ranks below the parent flows.

**Independent Test**: Register a child account without pairing it, enable protection, delete the account, and verify that protection is fully off, no tamper alert was produced, nothing restarts after reboot, and the app shows login.

**Acceptance Scenarios**:

1. **Given** a signed-in unlinked child, **When** they open Delete account, **Then** the consequences list covers their account, this device's data and history, and does not mention linked children.
2. **Given** an unlinked child enters the correct password and confirms twice, **When** the server reports success, **Then** monitoring, content filtering and background work all stop, no tamper or "service stopped" alert is produced, the app shows login with a success message, and after a device reboot no protection component starts again. The login screen also shows the "protection is off, you can uninstall the app" note with a button that opens the app's system settings page.
3. **Given** an unlinked child, **When** a wrong password, rate limit, network failure or expired session occurs, **Then** the behaviour matches US1 scenarios 2 to 6, and protection keeps running unchanged.

---

### User Story 4 - Supervised child attempts to delete their own account (Priority: P2)

A child account paired to a parent opens "Delete account". The server refuses the request because the account is supervised. The child sees a friendly message explaining that only their parent can delete this account and that they should ask their parent. Nothing is deleted, the child stays signed in, and protection keeps running.

**Why this priority**: Letting a supervised child delete their own account would be a way out of supervision. The refusal must be correct even though the entry point is visible.

**Independent Test**: Sign in as a paired child, attempt deletion with the correct password, and verify the "ask your parent" message appears, the child stays signed in, and protection is unchanged.

**Acceptance Scenarios**:

1. **Given** a signed-in child who is paired to a parent, **When** they submit the deletion with the correct password, **Then** the "only your parent can delete this account, please ask them" message is shown, nothing is deleted, they stay signed in, and protection keeps running.
2. **Given** the locally stored information says the child is unlinked but the server says the child is supervised, **When** they submit, **Then** the server's answer wins and the "ask your parent" message is shown.
3. **Given** the locally stored information says the child is supervised but the server says the child is unlinked, **When** they submit with the correct password, **Then** the deletion proceeds as in US3.

---

### User Story 5 - Anyone can learn how deletion works (Priority: P3)

Settings contains a "How account deletion works" link that opens the public web page registered in Play Console. The page needs no login.

**Why this priority**: This is useful for transparency and matches the Play Console listing, but the in-app flows above satisfy the core requirement.

**Independent Test**: From Settings, tap the link and verify the public deletion page opens in the browser.

**Acceptance Scenarios**:

1. **Given** any signed-in user on Settings, **When** they tap "How account deletion works", **Then** the public deletion page opens outside the app.
2. **Given** no browser is available to open the link, **When** the user taps it, **Then** a message explains that the page could not be opened and shows the address so the user can visit it another way.

---

### Edge Cases

- **Google Sign-In accounts** have no password. They confirm by typing the exact word `DELETE`, matched case-sensitively. If the server rejects this confirmation (for example, if the account later gained a password), the inline error says the confirmation was not accepted, and nothing is deleted.
- **Remote invalidation on a child device**: when a parent deletes their own account (and with it every child) or deletes one child's account, the child's device session becomes invalid without anyone touching that device. The child device stops protection only when the server positively confirms that the account was deleted. Every other kind of session loss (expired or revoked session, cleared app data, failed refresh, network errors) keeps today's behaviour: the device returns to login and protection keeps running. A child cannot cause a server-confirmed deletion of a supervised account, so this does not open an escape route. If the backend cannot tell "account deleted" apart from other session loss, the device falls back to today's behaviour (protection keeps running), and planning must record this as a known limitation.
- **Intentional self-deletion on a child device (US3)** must stop monitoring, content filtering and background work without sending tamper or "service stopped" alerts and without auto-restarting. This includes restarts after a reboot, after unlocking the device, and after the screen turns on.
- **Locally blurred images on a child device**: the original copies kept in the app's private review area belong to the deleted account and are removed on deletion. Images already blurred in the device gallery stay blurred, and after deletion they cannot be restored because the originals are gone. When such originals exist, the self-deletion warning on a child device must say so. Changing the blur behaviour itself is out of scope.
- **Leftover system permissions on a child device**: the app cannot revoke its own special permissions (accessibility, usage access, overlay, VPN approval), so they stay granted after deletion. The services stay inert (FR-020, FR-025), and the post-deletion login screen tells the user that the app can be uninstalled (FR-022a).
- **Deleting while a push notification or alert arrives**: notifications that arrive after the deletion starts must not show the deleted user's content, and notifications already displayed for that user are removed.
- **Server succeeded but the response was lost** (for example, the connection dropped after the server acted): the in-progress marker is kept on a network error. A retry then reports that the session is no longer valid, which is handled as "session over" and runs the same cleanup as FR-016a, with neutral wording. The app never shows the retry as a failure that implies the account still exists, and never claims "Account deleted" without a confirmed success response.
- **App killed mid-deletion** (force stop, dead battery, OS reclaim) after the request was sent: on the next launch the app checks with the server and either finishes the cleanup or resumes normally (FR-016a). A linked child cannot use the in-progress marker to switch protection off: the fallback cleanup stops protection only where monitoring is already off because the server last reported the child as unlinked.
- **Deletion from a child device whose parent deleted the child moments earlier**: this is handled as "session over".
- **Arabic/RTL**: the consequences list, the confirmation fields and the error messages lay out correctly right to left. The child's typed name is compared exactly as displayed, including Arabic characters.

## Requirements *(mandatory)*

### Functional Requirements

**Entry points and navigation**

- **FR-001**: Settings MUST show a "Delete account" entry to every signed-in user (parent, unlinked child, supervised child). The entry MUST be reachable so that a reviewer can complete deletion in 3 taps or fewer from opening Settings, plus the credential entry.
- **FR-002**: Each linked child on the Children screen MUST offer a "Delete account permanently" action that is separate from the stop-monitoring action.
- **FR-003**: The existing child "Remove" action MUST be relabelled, together with its confirmation text, so it clearly means "Stop monitoring (account kept)". Its behaviour MUST NOT change.
- **FR-004**: Settings MUST include a "How account deletion works" link that opens the public deletion web page.

**Consequences and confirmation**

- **FR-005**: Before any credential entry, the self-deletion screen MUST list the consequences in plain language. For a parent: their account, every linked child's account, and all devices, screen-time history, app-usage history, alerts, rules, filters and family links. For a child: their account and this device's data and history, with no mention of linked children. The list MUST state that deletion is immediate, permanent and cannot be undone.
- **FR-006**: Self-deletion MUST require re-authentication. Password accounts enter their current password. Google Sign-In accounts type the exact word `DELETE`. The app MUST determine which one applies from the sign-in method the server reports for the account.
- **FR-007**: The confirm action MUST stay disabled while the credential field is empty (password accounts) or not exactly `DELETE` (Google accounts).
- **FR-008**: Self-deletion MUST include a second, final confirmation step after the credential entry, before the request is sent.
- **FR-009**: Child-account deletion by a parent MUST require both of the following before the delete action is enabled: the parent types the child's displayed name exactly, and the parent re-authenticates with their own password, checked by the server. A Google parent instead completes a fresh Google sign-in to the same account, also checked by the server; a typed `DELETE` cannot be checked by the server for this action (plan research RD-1). The wrong-credential and rate-limit outcomes of FR-011 apply to this flow too. The confirmation MUST name the child, state that the deletion is irreversible, and explain how it differs from stopping monitoring.
- **FR-010**: Every destructive action MUST use the error colour and MUST NEVER be the default, primary or pre-focused button.

**Outcome handling**

- **FR-011**: The app MUST distinguish, and show different messages for, the following outcomes: success; wrong password or rejected confirmation (shown inline on the field); supervised child refused ("ask your parent"); rate-limited ("wait and try again", never "wrong password"); child no longer linked (list refresh plus informative message); session over; and network failure or timeout ("nothing was deleted", with retry).
- **FR-012**: The app MUST NOT show success unless the server confirmed the deletion, and MUST NOT show a failure when the server confirmed it.
- **FR-013**: The decision whether a child may delete themselves MUST come from the server's response. The locally stored role or link state MUST NOT be used to allow or block the request.
- **FR-014**: A session that has already expired or been revoked when deletion is attempted MUST be treated as "session over": local state is cleared, the user is taken to login, and the message says to sign in again, not that the account was deleted.
- **FR-015**: At most one deletion request MUST be in flight per target at any time. Repeated taps, screen rotation and leaving the screen MUST NOT send another one.
- **FR-016**: Once a deletion request has been sent, its outcome MUST be processed even if the user leaves the screen. If the server reports success, the full local cleanup MUST complete.
- **FR-016a**: Before a self-deletion request is sent, the app MUST record that a deletion is in progress, and it MUST survive the app being killed. On the next launch while this marker is present, the app MUST ask the server for the account's state before doing anything else. If the server confirms the account was deleted, the app MUST finish the full cleanup (FR-018 to FR-022a). If the server shows the account still exists, the app MUST clear the marker and continue normally. While the server cannot be reached, the app MUST keep the marker, MUST NOT stop protection, and MUST try again on later launches or when connectivity returns. A network error on the deletion request itself MUST also keep the marker. The marker alone MUST NEVER stop protection (FR-027).
  - **Fallback while the backend cannot confirm a deletion** (it returns the same 401 for a deleted account as for any other lost session; backend question Q6): a marker plus a 401 MUST be treated as "session gone". The app runs the full local cleanup (FR-018, FR-019) and shows the neutral "session ended" message. It MUST NOT show "Account deleted" or the uninstall note (FR-012). Protection may be stopped only on a parent device, or on a child the server last reported as having no linked parent. On a linked child the app MUST keep today's behaviour, and protection keeps running (FR-026, FR-027).
- **FR-017**: On wrong-password, rate-limit and network outcomes, the user MUST stay signed in and the app MUST remain fully usable. On a network outcome, the entered credential MUST be kept for retry.

**Post-deletion device state (self-deletion)**

- **FR-018**: After a successful self-deletion, nothing belonging to the deleted user may remain on the device. This covers credentials and session data, cached profile and device information, cached server responses, local databases, remembered email or login hints, scheduled background work, running services, pending and displayed notifications, and the push registration.
- **FR-019**: After a successful self-deletion, a cold start of the app MUST land on login in the same state as a fresh install.
- **FR-020**: On a child device, a successful self-deletion MUST stop monitoring, content filtering, text monitoring, image scanning and all background enforcement work. It MUST NOT produce tamper or "service stopped" alerts, and no protection component may restart afterwards, including after a reboot, a device unlock or the screen turning on.
- **FR-021**: On a child device, successful self-deletion MUST remove the original image copies held in the app's private review area. When such copies exist, the pre-deletion warning MUST state that images already blurred in the gallery will stay blurred and can no longer be restored.
- **FR-022**: The login screen shown after success MUST display a confirmation that the account was deleted.
- **FR-022a**: On a child device, after a successful self-deletion (FR-020) or a server-confirmed remote deletion (FR-025), the login screen MUST also show a short note that protection is off and the app can now be uninstalled, with a button that opens the app's system settings page, where the user can uninstall or revoke permissions. The app MUST NOT trigger an uninstall itself. This note MUST NOT appear after any other kind of session loss (FR-026), and it MUST NOT appear on a later cold start once the user dismisses it or signs in again.

**Parent-side child deletion**

- **FR-023**: After a successful child deletion, the deleted child MUST be removed from the parent's Children list and from every place where the parent's app shows that child's data (dashboard, alerts, rules), without requiring an app restart.
- **FR-024**: A parent deleting a child MUST remain signed in, and their own data and other children MUST be unaffected.

**Remote invalidation**

- **FR-025**: When the server positively confirms that a child device's account was deleted (by the parent deleting their own account or deleting that child), the child device MUST stop all protection and background enforcement, clear the local user footprint as in FR-018, and show login with a message that the account was deleted by the parent. As with FR-020, it MUST NOT produce tamper or "service stopped" alerts and MUST NOT restart protection.
- **FR-026**: Any other loss of session on a child device (expired or revoked session, failed refresh, cleared app data, network errors, or a server response that does not positively confirm deletion) MUST NOT stop protection. The device keeps today's behaviour: it returns to login and protection keeps running.
- **FR-027**: The signal that triggers FR-025 MUST come only from the server. Nothing the child can do on the device (local data, settings, time changes, going offline) may produce it.

**Localization**

- **FR-028**: All new and changed text MUST be available in English and Arabic, with correct right-to-left layout in Arabic.

### Key Entities

- **Account**: a parent or child user. Attributes relevant here: role, sign-in method (password or Google), and supervision state, which the server holds as the source of truth.
- **Family link**: the pairing between a parent and a child. It determines whether a child is supervised, and it is removed by both stop monitoring and deletion.
- **Deletion request**: a single attempt to delete either one's own account or one linked child's account. It carries the requester's confirmation credential (for both self-deletion and child deletion), has one outcome from the set in FR-011, and at most one is in flight per target.
- **Local user footprint**: everything on the device tied to the signed-in user: session, cached profile and device data, cached responses, local databases, scheduled work, running services, notifications, push registration, and review-area image originals.

### Data & Privacy Note *(Constitution Principle I)*

- **Collected**: nothing new. The password or the `DELETE` confirmation is sent once to the server to authorize the deletion, and is never stored or logged on the device.
- **Where it goes**: the deletion request goes to the existing backend. Server-side deletion is immediate and permanent. No soft delete, grace period or export exists.
- **On-device retention**: zero after a successful self-deletion (FR-018). Gallery images that were already blurred stay on the device's shared storage as they are; they belong to the device, not the app.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A first-time reviewer can find and complete account deletion from Settings in 3 taps or fewer, plus the credential entry.
- **SC-002**: Across all tested outcomes (success, wrong credential, supervised refusal, rate limit, child no longer linked, session over, network failure and timeout), zero cases show success after a failed deletion or failure after a successful one.
- **SC-003**: After a successful self-deletion, 100% of cold starts show login with no prior user data (no pre-filled email, names, children, alerts or settings). This includes runs where the app was killed after the request was sent (or the response was lost), once the server has been reachable once. In those runs the login screen shows the neutral "session ended" message instead of "Account deleted" (FR-016a fallback). On a linked child device, SC-003 applies only once the backend can confirm a deletion (Q6); until then protection keeps running there by design.
- **SC-004**: 100% of rate-limit responses produce a "wait and try again" message and none produce a "wrong password" message.
- **SC-005**: Zero tamper or "service stopped" alerts are generated as a consequence of an intentional deletion.
- **SC-006**: In testing with repeated taps, rotation and leaving the screen mid-request, exactly one deletion request is sent per attempt in 100% of runs.
- **SC-007**: In usability testing, no participant confuses "Stop monitoring (account kept)" with "Delete account permanently". Every participant correctly says which one keeps the child's account.
- **SC-008**: After an unlinked child's self-deletion, no protection component is running on the device, including after a reboot.
- **SC-009**: In tamper testing (clearing app data, expiring or revoking the session, going offline, changing the device clock), protection on a supervised child device stays on in 100% of attempts. It turns off only after a parent-initiated deletion that the server confirms.

## Assumptions

- The backend deletion capability is live and unchanged. It deletes a parent together with every linked child, refuses self-deletion for supervised children, rate-limits repeated attempts, accepts `DELETE` as the confirmation for Google accounts, and returns responses that distinguish the outcomes listed in FR-011. The exact response shapes are verified during planning against the executable API reference.
- The public "How account deletion works" page is already live. Its address is supplied through the app's existing configuration for public web links, not hard-coded per build.
- The number of linked children shown on the parent consequences screen comes from data the parent's app already has. If it is unavailable, the screen still states that all linked children will be deleted, without a number.
- The existing logout flow, unlink behaviour and blur behaviour are reused unchanged. Only wording changes for unlink.
- Password reset, data export, undo and grace periods are out of scope.
- The backend's child-deletion capability takes **no** credential (verified during planning). The app therefore re-authenticates the parent through existing server checks before deleting: password parents through sign-in with their stored email, and Google parents through a fresh Google sign-in to the same account. The password is never checked on the device only. Backend question Q7 asks whether the endpoint could accept a credential directly.
- Whether the backend returns a response that clearly means "account deleted", distinct from "session expired or revoked", is verified during planning. If it does not, FR-025 falls back to today's behaviour, and this is recorded as a known limitation without changing the backend.
- The rate-limit wait duration is shown when the server provides it. Otherwise the message says "try again later" without a number.
