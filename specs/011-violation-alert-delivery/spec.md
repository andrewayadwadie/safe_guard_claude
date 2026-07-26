# Feature Specification: End-to-End Violation Alert Delivery

**Feature Branch**: `011-violation-alert-delivery`

**Created**: 2026-07-22

**Status**: Draft

**Input**: User description: "End-to-end violation alert delivery (child detection → FCM → parent notification → parent alert list). Close three gaps: alerts lost on network failure; alert payload lacks child name, device name and event timestamp; parent-side push handling has no deep link, no parent/child guard, no list refresh."

## Overview

Detection on the child device already works. What is missing is everything that happens **after** a violation is detected: the alert can be silently lost, it does not carry enough identity to be meaningful, and the parent is not reliably told about it or taken to it.

This feature makes a detected violation reliably reach the parent, arrive with enough context to be understood at a glance, and be one tap away from the full record.

## Clarifications

### Session 2026-07-25

- Q: For parents already signed in before this feature ships, when should push-token registration run, and via what mechanism? → A: On every app startup for an already-authenticated parent session (not only at sign-in), via `PUT /auth/me/fcm-token` with body `{"fcm_token": "<token>"}`; on logout the same endpoint is called with an empty `fcm_token` to clear it.
- Q: What happens when a parent taps a violation notification but their session has expired / they are not logged in? → A: Route through login; after successful authentication, continue to the Alerts screen for that device — the deep-link intent is preserved, not discarded.
- Q: What happens when a notification points to a child device that has since been unlinked/removed from the family? → A: Fall back to the parent's default Alerts/device-list screen rather than a broken or empty target; no new navigation destination is introduced.

## Data Flow & Privacy Note *(Constitution Principle I)*

**What is collected**: alert metadata only — violation category, severity, the child's display name, the child's device name, the child's device record identifier, the moment the violation occurred, and the app version that reported it.

**What is NOT collected**: no monitored text, no image bytes, no screenshots, no URLs beyond what existing alert types already report. This feature adds **no new categories of monitored data**. It only attaches identity and timing to alerts that are already being sent.

**Where it goes**: child device → backend alert store → parent devices in the same family. Identical destination to today's alerts.

**Local retention**: undelivered alerts are held on the child device only until delivery succeeds, capped at 100 pending entries (oldest dropped on overflow), and cleared on sign-out.

**Retention on backend**: unchanged — governed by the existing alert retention policy.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - No violation is ever silently lost (Priority: P1)

A child triggers a violation while the device has no usable network — airplane mode, dead spot, captive portal, or the backend is briefly down. Today that alert vanishes and the parent never learns about it. After this change, the alert is held on the child device and delivered automatically as soon as connectivity returns, without the child or parent doing anything.

**Why this priority**: A monitoring product that loses violations when the network blinks is not trustworthy. Every other improvement in this feature is cosmetic if the alert never arrives. This alone is a shippable slice.

**Independent Test**: Put the child device in airplane mode, trigger a text violation, confirm the alert is held locally. Restore network, confirm the alert appears in the parent's alert list without further interaction.

**Acceptance Scenarios**:

1. **Given** the child device has no network, **When** a violation is detected, **Then** the alert is stored locally and is not discarded.
2. **Given** alerts are held locally, **When** network connectivity returns, **Then** every held alert is delivered oldest-first and removed from local storage on confirmed delivery.
3. **Given** the backend responds with a server-side failure, **When** the alert is submitted, **Then** it is retained locally and retried later with increasing delay between attempts.
4. **Given** an alert was suppressed by an existing rule (repeat-content dedup, cooldown window, daily cap, or device not registered), **When** that suppression fires, **Then** the alert is **not** held locally — suppression is a decision, not a failure.
5. **Given** more than 100 alerts are held, **When** a new alert is added, **Then** the oldest held alert is dropped and the newest is kept.
6. **Given** held alerts exist, **When** the child device's monitoring service starts, **Then** delivery is attempted once at startup.

---

### User Story 2 - The parent understands the alert without opening the app (Priority: P1)

A parent's phone shows a violation notification. Instead of a bare "Alert", it names the child, the device, what kind of violation it was, and when it happened — in the parent's language.

**Why this priority**: A parent with two children and three devices cannot act on an anonymous alert. Identity and timing are what make the alert actionable, and they must be attached at the source (the child device) for every alert type.

**Independent Test**: With the parent app backgrounded, cause a violation on a child device and confirm the resulting notification reads as "{child name} — {device name} · {violation type} · {time}" in both English and Arabic.

**Acceptance Scenarios**:

1. **Given** any alert type is created on a child device, **When** it is submitted, **Then** it carries the child's display name, device name, device record id, the moment the violation occurred, and the reporting app version.
2. **Given** an alert already supplies one of those fields itself, **When** the alert is assembled, **Then** the alert's own value wins over the automatically added one.
3. **Given** the parent app is backgrounded and a violation push arrives, **When** the notification is shown, **Then** it names the child, the device, the localized violation type, and the time of the violation.
4. **Given** the violation happened today, **When** the notification is shown, **Then** only a short time is displayed; **Given** it happened on an earlier day, **Then** a short date is shown alongside the time.
5. **Given** the device language is Arabic, **When** the notification is shown, **Then** all labels and the date/time are rendered in Arabic per the device locale.
6. **Given** the same alert is pushed more than once, **When** the notifications are shown, **Then** the parent sees a single notification, not duplicates.
7. **Given** a critical-severity violation, **When** the notification is shown, **Then** it is delivered with the highest urgency treatment (sound/vibration), consistent with the existing severity handling.

---

### User Story 3 - Tapping the notification lands on the alert (Priority: P2)

A parent taps the notification and is taken straight to the Alerts screen, already refreshed, with the new alert visible and marked unread.

**Why this priority**: Without this, the parent must find the alert manually and may see a stale list. It is high value but depends on Story 2 having something to tap.

**Independent Test**: Tap a violation notification from a cold start and from a backgrounded app; confirm both land on the Alerts screen showing the alert.

**Acceptance Scenarios**:

1. **Given** the parent app is closed, **When** the notification is tapped, **Then** the app opens directly on the Alerts screen for the relevant child device.
2. **Given** the parent app is already running, **When** the notification is tapped, **Then** the running app navigates to the Alerts screen without restarting.
3. **Given** the parent has landed on the Alerts screen via a notification, **When** the screen appears, **Then** the list reflects the newly delivered alert and it is shown as unread.
4. **Given** the parent has arrived at the Alerts screen, **When** the device is rotated or the screen is otherwise recreated, **Then** the app does not re-navigate to Alerts a second time.
5. **Given** the parent returns to the Alerts screen from another screen, **When** the screen resumes, **Then** the list refreshes.
6. **Given** the parent's session has expired or they are signed out, **When** they tap a violation notification, **Then** they are routed through login first, and on successful authentication the app continues to the Alerts screen for that device.
7. **Given** the notification's target child device has since been unlinked/removed from the family, **When** the parent taps it, **Then** the app falls back to the parent's default Alerts/device-list screen instead of a broken or empty target.

---

### User Story 4 - Only parents are notified (Priority: P2)

A child device that receives the same violation push must never display it. Showing a violation notification to the monitored child defeats the purpose and teaches evasion.

**Why this priority**: A correctness and product-integrity requirement, cheap to implement, but it depends on push handling being in place.

**Independent Test**: Send the same violation push to a signed-in child device and confirm no notification appears and no notification sound plays.

**Acceptance Scenarios**:

1. **Given** the signed-in account role is not parent, **When** a violation push arrives, **Then** no notification is displayed and the event is recorded in logs only.
2. **Given** the signed-in account role is parent, **When** a violation push arrives, **Then** the notification is displayed normally.

---

### User Story 5 - The parent device is actually reachable (Priority: P3)

A parent device must be registered for push and must be permitted to show notifications, otherwise every improvement above is invisible.

**Why this priority**: This is an enabling precondition rather than a visible feature; it is listed last because the common path (parent device already registered and permitted) already works for most installs.

**Independent Test**: Sign in fresh on a parent device with notifications denied; confirm a non-blocking prompt appears, and after granting, violation notifications arrive.

**Acceptance Scenarios**:

1. **Given** a parent signs in, **When** sign-in completes, **Then** the parent device is registered and its push token is published to the backend.
2. **Given** the push token is reissued by the system, **When** that happens, **Then** the new token replaces the old one for that parent device with the backend.
3. **Given** a parent was already signed in before this feature shipped, **When** they next open the app, **Then** the push token is (re)registered on that app startup, without requiring sign-out/sign-in.
4. **Given** a parent signs out, **When** sign-out completes, **Then** the parent device's registered push token is cleared with the backend.
5. **Given** the parent device runs a platform version that requires explicit notification permission, **When** the parent first reaches the app after sign-in, **Then** permission is requested.
6. **Given** notifications are disabled for the app, **When** the parent opens the app, **Then** a non-blocking banner informs them that violation alerts cannot be shown and offers a route to enable them.
7. **Given** the parent dismisses that banner, **When** they continue using the app, **Then** they are not blocked from any functionality.

---

### Edge Cases

- **No child display name stored** (e.g. account created before this feature): the alert is still sent, with the name field empty or falling back to the device name; the notification degrades gracefully rather than showing an empty separator.
- **Unknown violation type** in a push: the notification shows a generic localized "Violation" label rather than the raw internal type string or an empty gap.
- **Missing or malformed timestamp** in a push: the notification omits the time segment rather than showing an epoch number or "1970".
- **Held alerts belong to a previous account**: on sign-out, held alerts are cleared so they are never delivered under a different account.
- **Delivery succeeds but the response is ambiguous** (timeout after the server accepted): the alert may be resubmitted; the backend is expected to tolerate this (see Backend Coordination).
- **Push arrives while the parent is already viewing the Alerts screen**: the list refreshes; the parent is not navigated away or re-navigated.
- **Notification tapped with an expired/no session**: the parent is routed through login first; on success, navigation continues to the Alerts screen for that device rather than dropping the deep link.
- **Notification targets a child device unlinked/removed since the push was sent**: the app falls back to the parent's default Alerts/device-list screen instead of a broken or empty target.
- **Storage full or unwritable** on the child device: alert delivery still attempts live submission; failure to persist is logged and does not crash monitoring.
- **Very long child or device names**: the notification body is truncated by the platform but the expanded notification shows the full text.
- **A burst of violations offline** (e.g. 200 while in airplane mode): the newest 100 survive; existing cooldown and daily-cap rules already limit realistic burst volume well below this.

## Requirements *(mandatory)*

### Functional Requirements

**Alert identity enrichment**

- **FR-001**: Every alert created on a child device MUST carry the child's display name, the child's device name, the child's device record identifier, the moment the violation occurred (as an absolute timestamp), and the version of the app that reported it.
- **FR-002**: These fields MUST be applied uniformly to all alert types, not only text and image violations.
- **FR-003**: Where an individual alert already supplies one of these fields, the alert's own value MUST take precedence.
- **FR-004**: The child's display name MUST be captured and stored at sign-in or registration if it is not already available.
- **FR-005**: Existing suppression behaviour — repeat-content dedup, per-type cooldown windows, and daily caps — MUST remain unchanged in effect and wording. Alert titles, message text, and severity mapping MUST NOT change.

**Durable delivery**

- **FR-006**: When submission of an alert fails because of a connectivity or server-side failure, the system MUST retain the alert on the child device for later delivery.
- **FR-007**: The system MUST NOT retain alerts that were intentionally suppressed (dedup, cooldown, daily cap) or that were rejected because the device is not registered.
- **FR-008**: Retained alerts MUST be delivered automatically once connectivity is available, in the order they were created, and removed from local storage only on confirmed acceptance.
- **FR-009**: Retry MUST use increasing delays between attempts rather than tight looping.
- **FR-010**: Local retention MUST be capped at 100 alerts, discarding the oldest when the cap is exceeded.
- **FR-011**: Retained alerts MUST be cleared on sign-out so they are never sent under a different account.
- **FR-012**: A delivery attempt MUST also be triggered when the child device's monitoring service starts.
- **FR-013**: Failure to deliver MUST NOT change what the caller observes — detection and enforcement behave exactly as before.

**Parent notification**

- **FR-014**: A violation push MUST only produce a visible notification on a device signed in with a parent account. On any other role the event MUST be logged and discarded silently.
- **FR-015**: The notification MUST present the child name, device name, localized violation type, and violation time.
- **FR-016**: Violation type labels MUST be localized in English and Arabic, covering at minimum: inappropriate text, inappropriate image, blocked content, screen-time limit, blocked app, and device-admin disabled.
- **FR-017**: An unrecognized violation type MUST fall back to a generic localized label.
- **FR-018**: The violation time MUST be formatted for the device locale — short time only if the violation occurred today, with a short date prepended otherwise.
- **FR-019**: The notification MUST be expandable to show the full text when truncated.
- **FR-020**: Repeat delivery of the same alert MUST update the existing notification rather than create a duplicate.
- **FR-021**: Existing severity-to-urgency behaviour MUST be preserved, with critical-severity alerts receiving full alerting treatment (sound and vibration).
- **FR-022**: After a violation notification is posted, the parent's alert data MUST be refreshed so the list is current when opened.

**Deep link and list freshness**

- **FR-023**: Tapping a violation notification MUST open the Alerts screen for the device the violation came from, from both a cold start and a running app.
- **FR-024**: The navigation instruction MUST be consumed once, so screen recreation (e.g. rotation) does not re-navigate.
- **FR-025**: The Alerts screen MUST refresh its contents whenever it resumes.
- **FR-026**: If the session is expired or the parent is not signed in when the notification is tapped, the app MUST route through login first, then continue to the Alerts screen for that device on successful authentication — the deep-link target MUST NOT be dropped.
- **FR-027**: If the notification's target child device has been unlinked/removed from the family by the time it is tapped, the app MUST fall back to the parent's default Alerts/device-list screen rather than a broken or empty target.
- **FR-028**: No new navigation destinations may be introduced; existing routes MUST be reused.

**Parent reachability**

- **FR-029**: A parent device MUST register/refresh its push token with the backend at sign-in, on every app startup while an authenticated parent session exists (so parents already signed in before this feature shipped become reachable without re-login), and whenever the platform reissues the token. See Backend Coordination item 7 for the exact contract.
- **FR-030**: On sign-out, the parent device MUST clear its registered push token with the backend. See Backend Coordination item 7.
- **FR-031**: On platform versions that require it, notification permission MUST be declared and requested on parent devices.
- **FR-032**: If notifications are disabled, the parent MUST see a non-blocking, dismissible in-app banner explaining that violation alerts cannot be delivered, with a route to enable them. This MUST NOT block any other functionality.

### Key Entities

- **Alert**: a record of a detected violation. Existing attributes (type, severity, title, message, device) are unchanged; this feature adds identity and timing context: child name, device name, device record id, occurrence time, reporting app version.
- **Pending Alert Queue**: an ordered, bounded, on-device holding area for alerts awaiting delivery. Attributes: ordered list of undelivered alerts, capacity 100, oldest-out on overflow, cleared on sign-out.
- **Violation Push**: the message that informs a parent device of a new alert. Attributes: alert id, violation type, severity, child name, device name, occurrence time, title, body.
- **Child Display Name**: the monitored user's human-readable name, captured at sign-in/registration and used to identify whose violation it is.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 100% of violations detected while the child device is offline are delivered to the parent once connectivity is restored — zero silent losses across a 50-violation offline test.
- **SC-002**: Violations detected while offline appear in the parent's alert list within 2 minutes of the child device regaining connectivity.
- **SC-003**: 100% of parent-facing violation notifications identify the child, the device, the violation type, and the time — no notification reads as a bare "Alert".
- **SC-004**: A parent can go from seeing a notification to viewing the full alert record in a single tap, in under 5 seconds.
- **SC-005**: Zero violation notifications are displayed on child-role devices.
- **SC-006**: Re-delivery of the same alert produces exactly one notification, never a duplicate stack.
- **SC-007**: All parent-facing notification text renders correctly in both English and Arabic, with locale-appropriate date and time formatting.
- **SC-008**: Detection-to-parent-notification latency on a healthy network stays under 30 seconds at the 95th percentile.
- **SC-009**: Enabling durable delivery introduces no measurable regression in detection behaviour — existing cooldown, dedup, and daily-cap outcomes are byte-identical to before.

## Assumptions

- **Detection is out of scope.** The ML pipeline, image hashing, text hashing, and blur management are untouched. This feature begins at the moment an alert has been created and ends when the parent has read it.
- **The parent's alert list already exists** and is reachable through existing navigation; this feature reuses those routes rather than adding any.
- **The backend already accepts alerts** at the existing endpoint and already sends violation pushes; only the payload contents and targeting are being coordinated (see below). No backend changes are made in this repository.
- **Push is an accelerant, not a control path** (Constitution Principle III). If a push never arrives, the alert is still delivered to the backend and still appears when the parent opens the app.
- **Cap of 100 pending alerts** is chosen as a reasonable default: existing cooldown and daily-cap rules make realistic offline bursts far smaller, and an unbounded local store on a child device is itself a risk.
- **Arabic and English are the supported languages**, consistent with the existing localization work (feature 008).
- **No new third-party dependencies** are introduced; existing local-storage, background-work, and push mechanisms are reused.
- **A child display name is not currently stored** and will be captured during the existing sign-in/registration write, with no other change to authentication behaviour.
- **Existing violation categories are sufficient**; no new alert types or severities are introduced.

## Out of Scope

- Any change to detection, classification, or enforcement logic.
- Any change to VPN/DNS filtering, screen-time enforcement, tamper detection, or boot handling.
- New alert types, new severities, or changes to existing wording of alert titles and messages.
- Backend implementation work (tracked separately — see Backend Coordination).
- Notification grouping/summarization across multiple children, alert snoozing, or per-type notification preferences.
- Marking alerts read from the notification itself (without opening the app).

## Backend Coordination

The following must be confirmed with the backend developer. Items 4 and 5 are open questions that may change the client implementation.

1. **Alert payload**: the client will attach `child_name`, `device_name`, `device_db_id`, `occurred_at` (epoch millis), and `app_version` to alert metadata.
2. **Push targeting**: violation pushes must go to **all parent devices linked to the child's family**, not a single token.
3. **Push shape**: pushes must be **data-only** (no notification block) so the client controls display in both foreground and background. Required keys: `type="alert"`, `alert_id`, `alert_type`, `severity`, `child_name`, `device_name`, `occurred_at`, `title`, `body`.
4. **Delivery guarantees**: high priority, TTL ≥ 24h, so alerts survive a briefly offline parent.
5. **Idempotency** *(open)*: alert submission must tolerate retried submissions. If the backend prefers a client-generated dedup id, the client will add one — **confirm before it is added**.
6. **Key names** *(open)*: the exact key names above must be confirmed; the client will align to whatever is finalized.
7. **Parent push-token registration** *(confirmed)*: `PUT /auth/me/fcm-token` with body `{"fcm_token": "<token>"}` registers/refreshes a parent device's token; the same endpoint with an empty `fcm_token` clears it on logout. See FR-029/FR-030.
