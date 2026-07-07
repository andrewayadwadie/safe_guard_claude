# Feature Specification: Notifications Permission Row in Settings

**Feature Branch**: `005-notifications-settings-row`

**Created**: 2026-07-06

**Status**: Draft

**Input**: User description: "Add a Notifications permission row in the Settings screen. Tapping it opens this app's system notification settings. On return to the app, re-check and reflect ON/OFF state in the UI."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - See notification permission state and open system settings (Priority: P1)

A user (guardian configuring the device) opens the Settings screen and sees a "Notifications" row that shows whether the app's notifications are currently ON or OFF. Tapping the row takes them to the operating system's notification settings for this app, where they can toggle the permission. When they return to the app, the row automatically reflects the new state without any manual refresh.

**Why this priority**: This is the entire feature. Without it there is no visible notification-permission control in Settings. It is a single, self-contained slice that delivers the full value.

**Independent Test**: Open Settings with notifications disabled → row shows "Off — tap to enable" with an "Off" indicator. Tap the row → the system notification settings screen for this app opens. Enable notifications there, press back → row now shows "On" with an "On" indicator, without reopening the app.

**Acceptance Scenarios**:

1. **Given** notifications are disabled for the app, **When** the user opens the Settings screen, **Then** the Notifications row shows subtitle "Off — tap to enable" and a trailing "Off" indicator styled as a warning/error color.
2. **Given** notifications are enabled for the app, **When** the user opens the Settings screen, **Then** the Notifications row shows subtitle "On" and a trailing "On" indicator styled in a success/green color.
3. **Given** the user is on the Settings screen, **When** they tap the Notifications row, **Then** the system notification settings screen for this specific app opens.
4. **Given** the user changed the notification permission in system settings, **When** they return to the Settings screen (app resumes), **Then** the row re-checks and displays the updated ON/OFF state automatically.

---

### Edge Cases

- **System settings cannot be opened**: If the device cannot launch the app-specific notification settings screen (missing activity / OEM variation), the failure is caught and logged; the app does not crash and stays on the Settings screen.
- **State changed while app backgrounded**: If the user toggles the permission and returns, the resume re-check reflects the current state; no stale value is shown.
- **State unchanged on return**: Returning without changing anything re-checks and shows the same, correct state (idempotent refresh).

## Clarifications

### Session 2026-07-06

- Q: Which Settings section should the new "Notifications" permission row live in? → A: The existing "Notifications" section, next to the current "Push Notifications" row (no new section).

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The Settings screen MUST display a "Notifications" row inside the existing "Notifications" settings section (alongside the current "Push Notifications" row), using the existing settings-row visual pattern (leading icon, title, subtitle, trailing indicator). No new settings section is created.
- **FR-002**: The row MUST reflect the current notification-permission state: subtitle "On" when enabled, "Off — tap to enable" when disabled.
- **FR-003**: The row MUST show a trailing text indicator reading "On" (success/green styling) when enabled and "Off" (warning/error styling) when disabled.
- **FR-004**: Tapping the row MUST open the operating system's notification settings screen scoped to this app.
- **FR-005**: On returning to the Settings screen (app resume), the app MUST re-check the current notification-permission state and update the row to match.
- **FR-006**: The notification-permission state MUST be determined using the app's existing protection-status check; the feature MUST NOT introduce a new permission-request prompt flow.
- **FR-007**: If opening the system notification settings fails, the app MUST handle the failure gracefully (no crash) and record the error in logs.

### Key Entities

- **Notification permission state**: A boolean-style indicator of whether the app is currently allowed to post notifications, surfaced in the Settings UI state and refreshed on resume.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 100% of the time, opening the Settings screen displays a Notifications row whose state matches the device's actual notification-permission setting.
- **SC-002**: Tapping the row lands the user on this app's system notification settings screen in a single tap.
- **SC-003**: After changing the permission in system settings and returning, the row shows the correct updated state within one screen resume, with no manual refresh required.
- **SC-004**: A failure to open system settings never results in an app crash (0 crashes attributable to this action).

## Assumptions

- The existing protection-status check already exposes a reliable "notifications enabled" signal for the app; this feature reuses it rather than computing state independently.
- The existing settings-row and settings-section UI components are sufficient to render the new row without new shared components.
- The existing screen-resume observer on the Settings screen is the correct place to trigger the state re-check; no new lifecycle observer is added.
- User-facing strings ("Notifications", "On", "Off — tap to enable") are acceptable inline for this task; localization/string extraction is out of scope.
- Scope is limited to the Settings presentation layer only. No changes to the manifest, backend/API, repositories, dependency graph, workers, receivers, services, ML pipeline, navigation routes, or networking.
