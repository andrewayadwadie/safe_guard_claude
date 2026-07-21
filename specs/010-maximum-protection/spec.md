# Feature Specification: Maximum Protection

**Feature Branch**: `010-maximum-protection`

**Created**: 2026-07-19

**Status**: Draft

**Input**: User description: "Implement a 'Maximum Protection' feature that controls the gallery blur behavior. Add a Maximum Protection toggle in the child account settings on the child device, gated by the parent PIN. Save the setting locally (default FALSE). In the image-violation flow, read the setting fresh each time: TRUE → blur the image in the gallery and save a backup copy of the original in the parent-review folder (existing behavior); FALSE → do not blur, only save the copy of the violated image in the folder."

## Clarifications

### Session 2026-07-19

- Q: When Maximum Protection is OFF and a violation is detected (copy-only mode), should the app still send the metadata alert to the parent? → A: Yes — the metadata alert is sent in both modes; only the blur step is conditional.
- Q: When Maximum Protection is turned ON, what happens to images already flagged earlier while it was OFF (copied to the review area but left unblurred in the gallery)? → A: Blur them retroactively — after enabling, previously flagged-but-unblurred images are blurred no later than the next scan cycle, reusing the existing copy (no duplicates) and without sending duplicate alerts.
- Q: When Maximum Protection is turned OFF, should previously blurred images be automatically restored (unblurred) in the gallery? → A: No — blurred images stay blurred; restoration remains a deliberate per-image parent action through the existing review flow.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Parent controls Maximum Protection from child settings (Priority: P1)

A parent, holding the child's device, opens the child account settings and sees a "Maximum Protection" toggle. When anyone attempts to switch it ON or OFF, the app first demands the parent PIN. Only a correct PIN applies the change; a wrong PIN or a cancelled prompt leaves the setting exactly as it was.

**Why this priority**: This is the control surface of the whole feature. Without a PIN-gated toggle, either the feature cannot be turned on at all, or the child could weaken/strengthen their own protection — defeating the guardianship model.

**Independent Test**: Can be fully tested on a child device by opening settings, attempting to flip the toggle with a wrong PIN, a cancelled prompt, and a correct PIN, and observing the toggle state after each attempt. Delivers a working, tamper-resistant parental control even before the enforcement flow consumes it.

**Acceptance Scenarios**:

1. **Given** a child device with the setting OFF, **When** the toggle is tapped and the correct parent PIN is entered, **Then** the toggle switches ON and the value is stored on the device.
2. **Given** a child device with the setting ON, **When** the toggle is tapped and the correct parent PIN is entered, **Then** the toggle switches OFF and the value is stored on the device.
3. **Given** a child device with the setting OFF, **When** the toggle is tapped and a wrong PIN is entered or the PIN prompt is dismissed, **Then** the setting remains OFF and the toggle visually stays OFF.
4. **Given** a device where no parent PIN exists yet, **When** the toggle is tapped, **Then** the existing first-time PIN creation flow runs before any change can be applied.

---

### User Story 2 - Violation handled with Maximum Protection ON (Priority: P2)

With Maximum Protection ON, when an inappropriate image is detected on the child device, the app behaves exactly as it does today: the image in the gallery is replaced with a heavily blurred version, and the untouched original is saved into the app's protected review area so the parent can later review, restore, or delete it.

**Why this priority**: This preserves the strongest protection mode — the child cannot see the flagged content, and the parent keeps full review ability. It reuses proven behavior, so risk is low, but it depends on the toggle (US1) existing.

**Independent Test**: Can be tested by setting the stored value ON, placing a known-flagged image on the device, letting detection run, and verifying the gallery copy is blurred while the original copy exists in the review area.

**Acceptance Scenarios**:

1. **Given** Maximum Protection is ON, **When** an image violation is detected, **Then** the gallery image is blurred and a copy of the original is saved to the parent review area, and the parent alert is sent as it is today.
2. **Given** Maximum Protection is ON and an image was already blurred earlier, **When** the same image is encountered again, **Then** it is not processed twice (existing duplicate protection is preserved).
3. **Given** an image was flagged while Maximum Protection was OFF (copy saved, gallery image untouched), **When** a parent turns Maximum Protection ON, **Then** that gallery image is blurred no later than the next scan cycle, the existing review-area copy is reused (no duplicate copy), and no duplicate parent alert is sent.

---

### User Story 3 - Violation handled with Maximum Protection OFF (Priority: P3)

With Maximum Protection OFF (the default), when an inappropriate image is detected, the app does NOT alter the image the child sees. It only saves a copy of the violated image into the app's protected review area, so the parent still has the evidence, and sends the parent alert as usual.

**Why this priority**: This is the new, softer default mode. It matters for families who want awareness without visibly altering the child's gallery, but it is the least disruptive path and builds on the same detection flow.

**Independent Test**: Can be tested on a fresh device (value unset → defaults OFF) by placing a known-flagged image, letting detection run, and verifying the gallery image is unchanged while a copy exists in the review area.

**Acceptance Scenarios**:

1. **Given** Maximum Protection is OFF (or never set), **When** an image violation is detected, **Then** the gallery image remains visually unchanged, a copy of the original is saved to the parent review area, and the parent alert is sent as it is today.
2. **Given** Maximum Protection is OFF, **When** the parent later reviews saved copies, **Then** the review list shows the entry and the parent's existing review actions still work.

---

### Edge Cases

- **Setting changed mid-scan**: the value MUST be read fresh for every violation processed. If the parent flips the toggle while a batch scan is running, images processed after the change follow the new value.
- **No parent PIN set yet**: the existing PIN-creation path runs first; the toggle change only applies after a PIN exists and is verified.
- **Copy-only entries in parent review**: for violations captured while OFF, the original in the gallery was never replaced. Parent review actions must not corrupt anything: "restore" results in the original remaining intact in the gallery, and "delete" still removes the image from the device and the review area.
- **Mode changed OFF→ON (retroactive blur)**: an image recorded in the review area while OFF (copy saved, gallery original untouched) MUST be blurred retroactively once Maximum Protection is enabled — no later than the next scan cycle. The existing copy is reused (no duplicate copy) and no duplicate parent alert is sent. If the flagged file was deleted or moved in the meantime, the retroactive blur for that file is skipped without error and the review-area entry is left as-is.
- **Mode changed ON→OFF**: already-blurred images stay blurred. Unblurring remains exclusively a parent decision through the existing review (restore) flow — turning the setting OFF never mass-restores content.
- **Logout / account switch**: device-local settings are cleared on logout today; after re-login the setting is unset and therefore OFF, the safe default per this feature's definition.
- **Detection while device offline**: the setting is stored on the device, so both modes work fully offline, consistent with the child agent's offline enforcement guarantees.
- **Behavior change for existing installs**: today every violation is blurred unconditionally. After this feature ships with default OFF, blurring stops until a parent enables Maximum Protection. This is the explicit product decision in the feature description and must be understood by stakeholders before release.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The child account settings on the child device MUST display a "Maximum Protection" toggle that reflects the currently stored value.
- **FR-002**: Every attempt to change the toggle — in either direction (ON→OFF and OFF→ON) — MUST require successful parent PIN verification, using the existing parent PIN mechanism, before the change is applied.
- **FR-003**: If PIN verification fails or is cancelled, the system MUST NOT change the stored value and the toggle MUST remain in (or return to) its previous visual state.
- **FR-004**: The setting MUST be persisted on the device using the app's existing secure local settings storage, and MUST default to OFF when no value has been stored.
- **FR-005**: Every time an image violation is processed, the system MUST read the current stored value at that moment (no caching of the value across violations or across scan runs).
- **FR-006**: When the value is ON, a detected violation MUST be handled exactly as today: the gallery image is replaced with the blurred version AND a copy of the original is saved to the existing parent review area.
- **FR-007**: When the value is OFF, a detected violation MUST result in ONLY a copy of the original image being saved to the existing parent review area; the image the child sees MUST NOT be blurred, replaced, or otherwise visibly altered.
- **FR-008**: The parent alert for a detected violation MUST continue to be sent in both modes, unchanged from current behavior (metadata only, no image content).
- **FR-009**: The mode check MUST apply to every path that processes image violations (real-time detection and periodic catch-up scanning), so both paths behave identically for the same value.
- **FR-010**: The parent's existing review capabilities over saved copies (list, review, restore, delete) MUST keep functioning for entries created in either mode, without data loss or corruption.
- **FR-011**: The setting MUST be evaluated entirely on the device and MUST NOT depend on network connectivity or backend availability.
- **FR-012**: All existing protections MUST remain intact: duplicate-alert prevention, review-area storage location, and the PIN gate on parent review screens.
- **FR-013**: When Maximum Protection changes from OFF to ON, the system MUST retroactively blur every image previously flagged while OFF (copy exists in the review area, gallery original unaltered) — no later than the next scan cycle after the change; blurring immediately upon enabling is also acceptable.
- **FR-014**: Retroactive blurring MUST NOT create duplicate review-area copies and MUST NOT send duplicate parent alerts for the affected images.
- **FR-015**: Changing Maximum Protection from ON to OFF MUST NOT unblur or restore any previously blurred image; restoration remains available only through the existing parent review flow.

### Key Entities

- **Maximum Protection Setting**: a single device-local ON/OFF value on the child device; default OFF; readable at any time by the violation flow; changeable only after parent PIN verification.
- **Violation Copy**: the preserved original of a flagged image stored in the app's protected review area, together with its existing descriptive details (category, confidence, time, original location). Created in both modes; the only difference between modes is whether the gallery image is also blurred.
- **Parent PIN**: the existing device-local parent credential; this feature adds a new consumer of it (the toggle) but does not change how it is created, stored, or verified.

## Privacy & Data Flow *(constitution Principle I)*

- **What is collected**: nothing new. The feature adds one boolean preference stored in the app's existing encrypted local settings.
- **Where data goes**: original-image copies continue to be stored ONLY in the app-private on-device review folder, exactly as today. No image bytes leave the device in either mode. Parent alerts remain metadata-only and unchanged.
- **Retention**: unchanged — copies live in the review area until the parent restores or deletes them via the existing review flow.
- **Permissions**: no new Android permissions are required; the feature only branches existing on-device behavior.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 100% of toggle-change attempts without a correct parent PIN (wrong PIN or cancelled prompt) leave the stored value and the visible toggle unchanged.
- **SC-002**: With Maximum Protection ON, 100% of newly detected violations result in a blurred gallery image plus a recoverable original in the parent review area — indistinguishable from current behavior.
- **SC-003**: With Maximum Protection OFF, 100% of newly detected violations leave the gallery image visually unchanged while a copy appears in the parent review area.
- **SC-004**: A change to the setting takes effect on the very next violation processed after the change — with zero violations handled under a stale value.
- **SC-005**: All pre-existing flows (detection, alerting, parent review, restore, delete) complete with the same outcomes as before the feature for the ON mode; zero regressions observed in regression testing.
- **SC-006**: After Maximum Protection is enabled, 100% of images previously flagged while it was OFF become blurred in the gallery no later than the completion of the next scan cycle, with zero duplicate copies and zero duplicate alerts.

## Assumptions

- "Only save the copy, nothing else" (OFF mode) means: no modification of the child-visible image. The existing parent alert is still sent in both modes (confirmed in Clarifications, Session 2026-07-19).
- The toggle is shown only in the child account settings on the child device (the role that runs enforcement); parent-side app screens are out of scope.
- The existing parent PIN experience — including first-time PIN creation when none exists — is reused as-is; this feature adds no new PIN UI.
- Default OFF is an intentional behavior change for existing installs (which currently always blur), taken directly from the feature description.
- The existing review-area storage location and entry details are reused unchanged. Duplicate-alert protection is preserved; duplicate-processing tracking is extended only as far as needed to let the retroactive blur pass revisit images flagged while OFF (confirmed in Clarifications, Session 2026-07-19).
- Localization follows the app's existing bilingual (Arabic/English) pattern for any new user-facing text.
