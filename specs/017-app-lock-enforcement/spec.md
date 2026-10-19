# Feature Specification: Parent App Lock Reliably Enforced on the Child Device

**Feature Branch**: `017-app-lock-enforcement`

**Created**: 2026-10-18

**Status**: Draft

**Input**: User description: "Fix: App locks and screen-time controls set by the parent are never applied on the child device. Five defects found in a completed code diagnosis: (1) parent toggle only changes local UI state, nothing saved or sent, the only save path is an unusable floating button; (2) unlocking the last locked app omits the field instead of sending an explicit empty list, so the server never learns; (3) child rules live in memory only, nothing is enforced after reboot/process death or while offline; (4) the push 'lock' command is a no-op, the child only learns through ~90s polling; (5) missing Usage Access or overlay permission on the child makes enforcement silently do nothing. Auto-save on every switch change with rollback and error on failure, no save button. Child must persist rules locally and enforce before any network call. One documented delivery path with push-triggered refresh plus polling fallback. Loud, parent-visible permission failures. All hops log under tag AppLock."

## Context

Testing found that locking a social media app from the parent account has no effect on the child
device, and that the failure is silent on both sides. A completed end-to-end code diagnosis
(`C:\Users\Dell\.claude\plans\pasted-content-id-48a3-context-elegant-floyd.md`) traced the full
parent → backend → child → enforcement chain and found one root cause plus four further defects
that each reproduce the same silent symptom under different conditions. This feature resolves all
five. The enforcement mechanics themselves (foreground-app detection, lock screen presentation,
app identity matching) were verified correct and are not redesigned.

## Clarifications

### Session 2026-10-18

- Q: The backend rules-changed push (A5) does not exist today. What ships? → A: Build the client
  receive path now and shorten the periodic refresh from 90s to 30s. The periodic refresh stays
  authoritative; the push only accelerates it. Nothing in this feature blocks on the backend
  delivering A5.
- Q: How does the parent learn a child device cannot enforce? → A: Reuse the existing
  protection-monitor alert path, which already checks usage-access and overlay on the child every
  15 minutes and alerts the parent. This feature adds explicit checks at monitoring-service start
  and on every rules update, feeding the same alert channel. No new backend field, no inline
  always-visible parent banner, and the child onboarding flow is not changed.
- Q: Which capabilities count as required for enforcement? → A: Usage access is hard-required —
  without it nothing can be detected, so log at error and alert the parent. The overlay capability
  is degraded rather than required, because the existing notification fallback still presents the
  lock without it: log at warning, and alert only when that fallback is also unavailable. Battery
  optimisation stays with the existing protection monitor and is not re-scoped here.
- Q: The rule set has a master "active" flag that gates all blocking on the child. What does the
  client do with it? → A: Never rely on the backend's default for it. A change that adds a lock
  transmits the active state explicitly alongside the locked-app list. The screen already has a
  master enforcement switch, so that switch becomes one of the auto-saving controls and remains the
  parent's authority: a lock change asserts active only when the master switch is already on, and
  never flips a switch the parent deliberately turned off. While it is off, the screen must make
  clear that locks are not being enforced.
- Q: Two parents or two sessions edit the same child device at once. Who wins? → A: Each control
  saves only its own field and never carries other controls' values, so two parents editing
  different controls both succeed. Two parents editing the same control resolve as last write wins,
  which is accepted. No version checking and no new backend support; the partial-update behaviour
  this relies on is recorded as assumption A7.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A lock the parent sets is actually saved (Priority: P1)

A parent opens the Screen Time Limits screen for a child device and turns on the lock for an app.
The change is saved the moment it is made. There is no separate save step to forget, and the
parent can leave the screen immediately without losing the change.

**Why this priority**: This is the root cause. Today nothing is sent at all, so every other hop in
the chain is unreachable. Without this, no other story can deliver value.

**Independent Test**: Toggle a lock on the parent device, close the screen, reopen it from a cold
start, and confirm the lock is still on. No other change is needed for this to be testable.

**Acceptance Scenarios**:

1. **Given** a child device with no locked apps, **When** the parent turns on the lock for an app,
   **Then** the change is sent to the server without any further action by the parent.
2. **Given** the parent has just toggled a lock, **When** the parent immediately navigates back,
   **Then** the change is already persisted and no "unsaved changes" state exists.
3. **Given** the parent reopens the screen after the app was closed, **When** the screen loads,
   **Then** the controls reflect the state stored on the server, not a local draft.
4. **Given** the screen has several controls set, **When** the parent toggles one of them,
   **Then** none of the other controls change value as a side effect.

---

### User Story 2 - The parent can trust what the switch shows (Priority: P1)

Every switch on the screen reflects the server-confirmed state. While a change is being saved the
switch shows that it is in progress. If the save fails, the switch returns to its previous
position and the parent is told, in plain language, that the change was not applied.

**Why this priority**: A control that lies is worse than a control that is missing. A parent who
believes a lock is active when it is not has been given false assurance about their child's
safety — the exact failure this feature exists to eliminate.

**Independent Test**: Put the parent device in airplane mode, toggle a lock, and confirm the switch
reverts and an error is shown rather than the toggle appearing to succeed.

**Acceptance Scenarios**:

1. **Given** the parent toggles a switch, **When** the save is in flight, **Then** the screen
   indicates the change is being saved.
2. **Given** the parent device has no connectivity, **When** the parent toggles a switch,
   **Then** the switch returns to its previous position and a clear error message is shown.
3. **Given** a save failed and the error was shown, **When** the parent retries with connectivity
   restored, **Then** the change saves and the switch stays in the new position.
4. **Given** the parent flips the same switch several times quickly, **When** the activity settles,
   **Then** the state stored on the server equals the state shown on the screen, with no lost or
   out-of-order updates.

---

### User Story 3 - A locked app cannot be opened on the child device (Priority: P1)

When an app is locked, the child cannot use it. If the child is already inside the app when the
lock arrives, they are taken out of it.

**Why this priority**: This is the product promise. Saving a rule that is never enforced delivers
nothing.

**Independent Test**: With a lock saved for an app installed on the child device, open that app on
the child and confirm it is blocked, then repeat while already inside the app.

**Acceptance Scenarios**:

1. **Given** an app is locked, **When** the child opens it from the launcher, **Then** the child is
   prevented from using it and is shown the lock screen.
2. **Given** an app is locked, **When** the child opens it from recents or by tapping one of its
   notifications, **Then** the child is prevented from using it.
3. **Given** the child is actively using an app, **When** a lock for that app reaches the device,
   **Then** the child is removed from the app without having to close and reopen it.
4. **Given** an app is not locked, **When** the child opens it, **Then** nothing blocks them.

---

### User Story 4 - A lock survives reboot, app kill and loss of network (Priority: P1)

Once a lock has reached the child device, it stays in force. Restarting the phone, the system
killing the app to save battery, or turning off mobile data and Wi-Fi do not unlock anything.

**Why this priority**: Rules that evaporate on reboot or offline give the child a trivial,
discoverable bypass, and the parent is never told protection lapsed. The governing project
principle is that enforcement must not depend on connectivity.

**Independent Test**: Save a lock, confirm it is enforced, then put the child device in airplane
mode and reboot it. The lock must still be enforced before any network call succeeds.

**Acceptance Scenarios**:

1. **Given** a lock is in force and the child device is rebooted with no network available,
   **When** the child opens the locked app, **Then** it is still blocked.
2. **Given** a lock is in force, **When** the system kills the app and it is restarted,
   **Then** the lock is enforced from the first check, without waiting for a server response.
3. **Given** the child device has been offline for a long period, **When** the child opens a locked
   app, **Then** it is blocked.
4. **Given** the child device regains connectivity after being offline, **When** rules changed while
   it was offline, **Then** the device applies the newer rules and discards the stale ones.

---

### User Story 5 - Unlocking works, including the last locked app (Priority: P1)

When the parent unlocks an app the child can use it again. This holds for the final remaining
locked app exactly as it does for any other.

**Why this priority**: Today a parent can remove the last lock and the child stays blocked
indefinitely with no way to recover from the parent app. An unlock that cannot be undone is a
support incident and a trust failure in the opposite direction.

**Independent Test**: Lock exactly one app, confirm it is blocked on the child, unlock it, confirm
the child can open it again.

**Acceptance Scenarios**:

1. **Given** two apps are locked, **When** the parent unlocks one, **Then** the child can use that
   app and the other stays blocked.
2. **Given** exactly one app is locked, **When** the parent unlocks it, **Then** the "no apps
   locked" state is sent to the server and the child can use the app again.
3. **Given** the last app was unlocked, **When** the parent reopens the screen, **Then** no app
   shows as locked.
4. **Given** a lock screen is currently shown on the child, **When** that app is unlocked,
   **Then** the lock screen is dismissed without the child having to restart the device.

---

### User Story 6 - A lock reaches an online child device within seconds (Priority: P2)

When the child device is online, a lock the parent sets takes effect within seconds rather than
minutes.

**Why this priority**: Correctness (stories 1-5) outranks speed, but a parent acting on something
happening right now expects the lock to take hold immediately. A delay of a minute or more is
experienced as the feature not working.

**Independent Test**: With the child device online and in use, save a lock on the parent and
measure the time until the child is blocked.

**Acceptance Scenarios**:

1. **Given** the child device is online and the immediate notification is delivered, **When** the
   parent's change is saved, **Then** the child enforces the new rules within 10 seconds.
1a. **Given** the child device is online but no immediate notification is delivered, **When** the
   parent's change is saved, **Then** the child enforces the new rules within 35 seconds via the
   periodic refresh.
2. **Given** the child device is asleep or temporarily unreachable, **When** it becomes reachable
   again, **Then** it picks up the change through the regular periodic refresh with no parent
   action.
3. **Given** the immediate notification is not delivered at all, **When** the periodic refresh runs,
   **Then** the change is still applied. Immediate delivery is an accelerator, never the only path.

---

### User Story 7 - The parent is told when the child device cannot enforce (Priority: P2)

If the child device is missing a permission that enforcement requires, the parent is told that
protection is not active, instead of being left to believe their child is protected.

**Why this priority**: This is the silent-failure class that produced the original bug report. It
does not block the fix, but without it the same "it just does nothing" experience can recur on any
device where setup was skipped.

**Independent Test**: Revoke usage access on the child device and confirm the parent receives an
alert that enforcement is inactive on that device.

**Acceptance Scenarios**:

1. **Given** the child device is missing a permission required for enforcement, **When** the
   monitoring service starts or rules are updated, **Then** the parent is alerted that enforcement
   is not active and which capability is missing.
2. **Given** the child device is missing a required permission, **When** enforcement runs,
   **Then** the condition is recorded in the diagnostic log rather than passing silently.
3. **Given** the missing permission is granted on the child device, **When** the next check runs,
   **Then** no further alert is raised, with no manual reset on either device.
4. **Given** the child device is already alerting about a missing permission, **When** the condition
   persists, **Then** the parent is not spammed — the existing alert cooldown applies.

---

### Edge Cases

- The parent toggles a lock while the parent device is offline → the control reverts and an error is
  shown; nothing is left half-saved.
- The parent turns the master enforcement switch off and then locks an app → the lock is saved, the
  master switch stays off, and the screen states that nothing is being enforced.
- The master enforcement switch is off on the child's stored rules → no app is blocked even if the
  locked-app list is non-empty, and this is logged as "policy inactive", distinct from "no rules"
  and from "nothing locked".
- The parent changes several different controls within a second of each other → each change
  persists; a save for one control never resurrects a stale value for another.
- The parent flips the same switch on, off, on rapidly → the final server state matches the final
  on-screen state; an out-of-order response never wins over a newer one.
- Two parents edit different controls for the same child at the same moment → both changes survive.
- Two parents edit the same control at the same moment → the later save wins and neither parent is
  prompted; the losing parent's screen corrects itself on its next load of the rule set.
- The child device receives rules with "no apps locked" → any active lock screen is dismissed.
- The child device has never successfully reached the server since install → nothing is enforced,
  and this is logged as "no rules available", not treated as "nothing is locked".
- Stored rules and freshly fetched rules disagree → the freshly fetched rules win and replace the
  stored copy.
- The child device is rebooted while a lock screen was showing → the lock is re-applied from the
  stored rules the next time the child opens the app.
- A locked app is uninstalled from the child device → the stored rule is harmless and no error
  surfaces on either side.
- An app the child has never opened is not offered in the parent's app picker (known existing
  limitation, recorded under Assumptions).
- The parent locks an app whose lightweight variant is also installed → only the selected app is
  blocked; the variant stays usable until the parent locks it too, and it becomes selectable once
  the child has used it.
- The parent drags a duration slider across many values before releasing → one save occurs, for the
  released value, not one per intermediate value.
- The parent opens a time picker and dismisses it without confirming → nothing is saved and the
  control keeps its last confirmed value.
- The child device clock is wrong → stored rules stay valid; freshness is decided by what the server
  returns, not by the child's local clock.
- The child device has usage access but no overlay capability → the locked app is still blocked and
  presented through the notification fallback; a warning is logged and no parent alert is raised.
- The child device has neither the overlay capability nor a working notification fallback → nothing
  can be presented to the child, so the parent is alerted even though detection still works.

## Requirements *(mandatory)*

### Functional Requirements

**Saving a change from the parent**

- **FR-001**: Each control on the Screen Time Limits screen MUST persist its own change at the
  moment the change is made. No explicit save action may be required of the parent.
- **FR-002**: The screen MUST NOT present a save button or floating save control.
- **FR-003**: Changing one control MUST NOT alter, reset, or resubmit the value of any other control
  on the screen. A save MUST carry only the field that changed, never the screen's whole set of
  values.
- **FR-003a**: Two parents editing different controls on the same child device MUST both succeed,
  neither overwriting the other's change with a stale value. Two parents editing the same control
  resolve as last write wins; no conflict prompt is shown.
- **FR-004**: While a change is being saved, the screen MUST indicate that the change is in
  progress.
- **FR-005**: When a save fails for any reason, the control MUST return to its last confirmed value
  and the parent MUST be shown a clear, non-technical error message.
- **FR-006**: Repeated rapid changes to the same control MUST converge: the value stored on the
  server once activity settles MUST equal the value shown on the screen. A response arriving out of
  order MUST NOT overwrite a newer value.
- **FR-007**: Removing the last locked app MUST be transmitted as an explicit "no apps locked"
  value. Omitting the field is prohibited — for the locked-app list and for every other list- or
  map-valued control on the screen.
- **FR-008**: The parent MUST be able to leave the screen at any time without losing a change and
  without being prompted about unsaved work.
- **FR-008a**: The master enforcement switch is one of the controls covered by FR-001 through
  FR-006: it persists its own change immediately, shows progress, and reverts with an error on
  failure.
- **FR-008b**: A change that adds a lock MUST transmit the rule set's active state explicitly
  rather than relying on a stored default. It MUST assert "active" only when the master enforcement
  switch is already on, and MUST NOT turn the master switch back on if the parent has turned it off.
- **FR-008c**: While the master enforcement switch is off, the screen MUST make clear that no locks
  are being enforced, so a parent cannot read individual locked apps as active protection.

**Delivering a change to the child**

- **FR-009**: There MUST be exactly one documented delivery path by which rule changes reach the
  child device: an immediate notification that triggers a refresh, plus a periodic refresh as the
  fallback. Any other partially implemented delivery mechanism MUST be either completed into this
  path or removed.
- **FR-010**: The immediate rules-changed notification MUST carry no rule content. It only tells the
  child to refresh; rule content is always fetched by the child from the server.
- **FR-011**: A notification the child does not recognise MUST be recorded in the diagnostic log
  rather than discarded without trace.
- **FR-012**: Delivery MUST NOT depend on the immediate notification. If it is never delivered, the
  periodic refresh MUST still apply the change.
- **FR-012a**: The periodic refresh interval MUST be no longer than 30 seconds, so the worst-case
  delivery time stays bounded even when the immediate notification is never sent. The child's
  ability to receive and act on the notification MUST be implemented in this feature regardless of
  whether the backend sends one yet.

**Enforcing on the child**

- **FR-013**: The child device MUST store the most recently received rules durably, so they survive
  reboot, the app being killed, and app updates.
- **FR-014**: After restart, the child MUST begin enforcing from the stored rules before any network
  call is attempted or completed.
- **FR-015**: The child MUST keep enforcing the stored rules for as long as it has no connectivity.
  Loss of connectivity MUST NEVER unlock an app.
- **FR-016**: When fresh rules are received they MUST replace the stored rules atomically; a
  partially written rule set MUST NOT be readable.
- **FR-017**: A locked app MUST be blocked regardless of how it is launched, including from the
  launcher, from recents, and by tapping one of its notifications.
- **FR-018**: When an app becomes unlocked, any lock screen currently shown for it MUST be dismissed
  without further child action.
- **FR-019**: Three states MUST be distinguishable, in behaviour and in the log: no stored rules at
  all, stored rules that lock nothing, and stored rules whose master enforcement switch is off.

**Permissions and visibility**

- **FR-020**: The child MUST check whether it holds the capabilities enforcement requires when the
  monitoring service starts and again whenever rules are updated. A missing capability found by
  either check MUST be reported to the parent through the same alert channel the existing periodic
  protection monitor already uses, subject to that channel's existing anti-spam cooldown.
- **FR-020a**: Usage access is hard-required: without it the child cannot detect which app is in
  the foreground and enforcement is impossible. Its absence MUST be logged at error level and MUST
  raise a parent alert stating that locks are not being enforced.
- **FR-020b**: The overlay capability is degraded, not required: when it is absent the child MUST
  still present the lock through the existing notification fallback, and MUST log the degradation at
  warning level. A parent alert MUST be raised only when the fallback is also unavailable — for
  example notifications are blocked or the full-screen presentation is not permitted — because only
  then can nothing be presented to the child.
- **FR-021**: A missing enforcement capability MUST be recorded in the diagnostic log at warning
  level or higher, and MUST NEVER result in enforcement returning quietly with no trace.
- **FR-022**: The parent MUST be told, per child device, that enforcement is not active and which
  capability is missing, delivered as an alert through the existing protection-alert channel. A new
  parent-facing always-on indicator is not required.
- **FR-023**: When the missing capability is granted on the child device, the alerting MUST stop by
  itself, with no manual reset on either device.
- **FR-024**: Every step of the chain — parent save, delivery, child receipt, storage, permission
  check, block decision, lock screen shown and dismissed — MUST emit a diagnostic log entry under a
  single shared tag, so the whole chain can be followed in one filtered log stream.
- **FR-025**: Diagnostic log entries MUST NOT contain credentials, device tokens, or account
  identifiers such as email addresses.

**Non-switch controls and app identity**

- **FR-026**: Non-switch controls on the same screen (duration sliders, time-range pickers) MUST
  save automatically when the parent finishes editing the value — a slider when the parent releases
  it, a picker when the parent confirms it. They are subject to FR-003 through FR-006 exactly as
  switches are. A control still being dragged or still open MUST NOT save on every intermediate
  value.
- **FR-027**: Locking an app MUST block only the exact app the parent selected. Lightweight or
  alternative variants published by the same vendor are separate apps and MUST be locked separately
  by the parent. No vendor-to-variant mapping is maintained by the system.

### Key Entities

- **Rule set**: the complete policy for one child device — which apps are locked, per-app and daily
  limits, the allowed-apps list, the schedule, and whether the policy is active at all. Owned by the
  server, edited by the parent, mirrored on the child.
- **Locked app list**: the set of apps the child may not use. Its empty state is meaningful and must
  be representable and transmittable, distinct from "not specified".
- **Stored rule copy (child)**: the child device's durable local copy of the rule set; the authority
  for enforcement whenever the server cannot be reached.
- **Enforcement capability status**: per child device, whether the device currently holds what it
  needs to detect and block app usage, and what is missing if not. It distinguishes two levels:
  hard-required (absent → enforcement impossible) and degraded (absent → enforcement still works
  through a fallback, with reduced presentation).
- **Rules-changed notification**: a content-free signal to a child device that its rule set has
  changed and should be refreshed now.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 100% of lock changes made by a parent with working connectivity are stored on the
  server with no additional action by the parent.
- **SC-002**: A lock set by the parent blocks the app on an online, awake child device within
  10 seconds in at least 95% of trials when the immediate notification is delivered, and within
  35 seconds in 100% of trials when it is not delivered at all.
- **SC-003**: After the child device is rebooted with all networking disabled, a previously applied
  lock blocks the app on the first attempt to open it, in 100% of trials.
- **SC-004**: Unlocking the last remaining locked app restores the child's access in 100% of trials.
- **SC-005**: A failed save never leaves the parent's screen showing a state that differs from the
  server: in 100% of injected-failure trials the control reverts and an error is shown.
- **SC-006**: Across 20 consecutive rapid toggles of a single control, the final stored state
  matches the final displayed state in 100% of runs.
- **SC-007**: When a child device lacks a required enforcement capability, the parent is alerted
  within one protection-monitor cycle (15 minutes) in 100% of trials, and immediately when the
  monitoring service starts or rules are updated, subject to the existing alert cooldown.
- **SC-008**: The complete chain from the parent's change to the child's block decision can be
  followed end to end in a single filtered diagnostic log, with no gap larger than one step.
- **SC-009**: Zero occurrences, in any tested scenario, of an app being usable on the child while
  the parent's screen shows it as locked.
- **SC-010**: Automated tests cover the lock decision, the empty-list transmission, and restoration
  of rules after restart — areas with no test coverage today.

## Assumptions

- The backend behaviour below is assumed and MUST be confirmed with the backend developer before
  planning concludes. These are blockers, not details:
  - **A1**: Saving a rule set persists the locked-app list, and the child's fetch returns it.
  - **A2**: An inactive rule set disables all blocking on the child regardless of the locked-app
    list. The row's default active state is unknown; FR-008b removes the dependency on it by always
    transmitting the state explicitly, so A2 is no longer a blocker — only a thing to confirm.
  - **A3**: A child device is authorised to fetch the rule set for its own device identifier.
  - **A4**: The device identifier the parent writes against is the same identifier the child uses to
    fetch.
  - **A5**: On a successful save, the backend sends a content-free, high-priority "rules changed"
    notification to the child device. This does not exist today and is new backend work. This
    feature does NOT depend on it: the child's receive path is built regardless, and the 30-second
    periodic refresh (FR-012a) bounds delivery without it. If the backend never ships A5, the only
    consequence is that SC-002's 10-second case does not apply.
  - **A6**: Saving a rule set creates it if it does not already exist, rather than rejecting it.
  - **A7**: A save carrying only the changed field leaves every other field of the stored rule set
    untouched. The client's existing code comments assert this is how the backend behaves; FR-003
    and FR-003a depend on it, and FR-007 exists because an omitted field cannot then mean "clear".
- A periodic child-side protection monitor already exists, already checks usage-access and overlay,
  and already alerts the parent when either is revoked, on a 15-minute cycle with anti-spam
  cooldowns. This feature reuses it rather than building a parallel reporting path.
- The existing enforcement mechanics are correct and are not redesigned: foreground-app detection,
  the lock screen itself, and app identity matching were all verified working.
- The parent's app picker continues to list only apps the child has actually used, because it is
  built from reported usage. Replacing it with a full installed-app inventory is out of scope and is
  recorded as a known limitation.
- The parent device is expected to be online while editing rules. Offline editing with deferred sync
  on the parent side is out of scope; offline is handled by reverting and reporting.
- "Within seconds" for an online child device assumes the device is awake and not in a deep
  power-saving state. Aggressive manufacturer battery management may delay delivery to the
  periodic-refresh fallback, which is accepted.
- Existing protections are preserved untouched: the on-device ML pipeline, VPN/DNS filtering, image
  blurring, authentication and token handling, and the app's navigation routes.

## Out of Scope

- New lock types, schedules, or policy concepts beyond those that already exist.
- Any vendor-to-variant app mapping (FR-027 decides against it).
- Any redesign of the Screen Time Limits screen beyond removing the save control and adding
  saving/error/permission-warning state.
- An always-visible parent-facing indicator of child enforcement capability, and any new backend
  field or endpoint to support one (FR-022 uses the existing alert channel instead).
- Changes to the child onboarding permission flow, including the "skip for now" path.
- Website and DNS blocking.
- The ML classification pipeline.
- Replacing the usage-derived app picker with a full installed-app inventory on the child.
- Backend implementation. The backend is a separate repository; this feature defines what it must
  provide.
