# Feature Specification: Arabic & English Localization with RTL Support

**Feature Branch**: `008-arabic-english-localization`

**Created**: 2026-07-07

**Status**: Draft

**Input**: User description: "make app multi language arabic and english and make toggle in setting screen and when toggle to arabic app display in RTL Direction and reverse in english, convert every text in app to arabic and english files and call text from this file"

## Clarifications

### Session 2026-07-07

- Q: In Arabic mode, which digit style should numbers use (screen-time minutes, dates, counts, percentages)? → A: Western digits (0-9) everywhere, in both languages.
- Q: What form should the language control in Settings take? → A: Settings row showing the current language; tapping opens a dialog with radio choices (English / العربية).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Switch App Language from Settings (Priority: P1)

A parent (or the person operating the app on a child device) opens the Settings screen, finds a language option, and switches between Arabic and English. The entire app interface immediately changes to the chosen language. When Arabic is selected, the whole layout flips to right-to-left (text right-aligned, navigation drawers/back arrows/list ordering mirrored); when English is selected, the layout is left-to-right.

**Why this priority**: This is the core deliverable — without the toggle and the language/direction switch, no other localization work is visible to the user. The app targets Arabic-speaking families (Haris brand, harisfamily.com), so Arabic support directly serves the primary market.

**Independent Test**: Open Settings, switch language to Arabic, verify Settings screen itself re-renders in Arabic with RTL layout; switch back to English, verify LTR English rendering. Delivers value even if only Settings and navigation chrome are translated at this point.

**Acceptance Scenarios**:

1. **Given** app is displayed in English, **When** user selects Arabic in the Settings language option, **Then** all visible text changes to Arabic and the layout direction becomes right-to-left without reinstalling or clearing data.
2. **Given** app is displayed in Arabic (RTL), **When** user selects English, **Then** all visible text changes to English and the layout direction becomes left-to-right.
3. **Given** user has selected Arabic, **When** user force-closes and reopens the app, **Then** the app opens in Arabic with RTL layout (choice persists).
4. **Given** user is on any screen other than Settings when the language changes, **When** they navigate through the app, **Then** every screen reflects the newly selected language and direction.

---

### User Story 2 - Every Screen Fully Translated (Priority: P2)

A user browsing any part of the app — login, consent, dashboard, child list, device setup, alerts, screen-time, content filtering, settings, change password, link-parent, splash — sees every label, button, title, message, and error in the selected language. No screen shows leftover English text while in Arabic mode (or vice versa).

**Why this priority**: Partial translation is worse than none for trust — a child-safety app must read as professionally built. This story is the bulk of the work: moving every hardcoded string into per-language text resources and referencing them from the interface.

**Independent Test**: With Arabic selected, walk through every screen of the app and confirm zero English strings appear in app-authored text (user-generated content such as child names and app names stay as entered). Repeat in English mode confirming zero Arabic remnants.

**Acceptance Scenarios**:

1. **Given** Arabic is selected, **When** user visits each screen of the app, **Then** all app-authored text (titles, buttons, labels, hints, errors, empty states, dialogs) renders in Arabic.
2. **Given** Arabic is selected, **When** a dynamic message containing values is shown (e.g., "Haris was disabled on {device name}", screen-time minutes, expiry times), **Then** the sentence structure is correct Arabic with the value inserted in the right position, not word-for-word substitution.
3. **Given** English is selected, **When** user visits each screen, **Then** all app-authored text renders in English exactly as today (no regressions).

---

### User Story 3 - Notifications & Background Alerts Localized (Priority: P3)

Alerts and notifications produced while the app is in the background (tamper alerts, protection-monitor warnings, monitoring-active notice) appear in the language the user selected in Settings.

**Why this priority**: Notifications are a major touchpoint for parents but are generated outside normal screens; they can ship after the visible interface is fully localized.

**Independent Test**: Select Arabic, trigger a tamper/monitoring notification, verify its title and body are Arabic. Switch to English, repeat, verify English.

**Acceptance Scenarios**:

1. **Given** Arabic is selected, **When** a background alert or persistent monitoring notification is posted, **Then** its title and body are in Arabic.
2. **Given** the language is changed, **When** the next notification is posted, **Then** it uses the newly selected language.

---

### Edge Cases

- Device system language is Arabic on first launch before any choice: app SHOULD start in Arabic; any other system language starts in English.
- User-generated content (child name, device name, app names, URLs, pairing codes) is never translated — displayed as stored, embedded correctly inside RTL sentences without breaking direction (bidirectional text handling).
- Numbers, dates, and times in Arabic mode: Western digits (0-9) are used everywhere in both languages — counts, minutes, percentages, dates, times, and pairing codes; date/time wording and ordering follow the selected language but digits never switch to Eastern Arabic numerals.
- Directional icons (back arrows, chevrons, "next" indicators) mirror in RTL; non-directional icons (shield, logo, warning) do not mirror. Brand logo never mirrors.
- Long Arabic or English strings must not truncate or overlap on small screens — layouts adapt to text length in both languages.
- Legal document screens (Privacy Policy / Terms) already support both languages inside their own pages; the in-app screen SHOULD open them in the currently selected app language when possible.
- Mid-flow language switch (e.g., during device setup): current screen re-renders in the new language without losing entered data or setup progress.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: App MUST support two display languages: Arabic and English, covering every user-facing text element.
- **FR-002**: Settings screen MUST contain a language row showing the currently selected language ("English" / "العربية"); tapping it opens a dialog with radio choices for the two languages.
- **FR-003**: Selecting a language MUST apply immediately across the whole app (no reinstall, no data loss); the visible screen updates without requiring the user to manually restart.
- **FR-004**: When Arabic is selected, the app MUST render in right-to-left direction (text alignment, component ordering, navigation gestures/icons mirrored); when English is selected, left-to-right.
- **FR-005**: Every app-authored string MUST live in centralized per-language text resource files (one set for English, one for Arabic); no user-facing text may be hardcoded in screen/component/notification code.
- **FR-006**: The selected language MUST persist across app restarts, device reboots, and app updates.
- **FR-007**: On first launch with no saved choice, the app MUST default to Arabic if the device system language is Arabic, otherwise English.
- **FR-008**: Dynamic strings (device names, child names, counts, times, percentages) MUST use positional placeholders so translated sentences read naturally in both languages.
- **FR-009**: Notifications, background alerts, and any text produced by background processes MUST use the selected app language.
- **FR-010**: User-generated content MUST be displayed verbatim (untranslated) and remain readable when embedded in RTL text.
- **FR-011**: All screens MUST remain fully usable in both languages — no clipped, overlapping, or off-screen text or controls in either direction.

### Key Entities

- **Language Preference**: The user's selected display language (Arabic or English); stored on-device; single value per device; default derived from system language on first launch.
- **Text Resource Set**: The complete catalog of app-authored strings, maintained in exactly two parallel language files (English as the source set, Arabic as the translated set); every user-facing string has an entry in both.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 100% of app-authored user-facing strings render in the selected language — a full walkthrough of all screens in Arabic mode surfaces zero English app-authored strings, and vice versa.
- **SC-002**: Language switch takes effect on the visible screen within 2 seconds of selection.
- **SC-003**: In Arabic mode, 100% of screens lay out right-to-left with correctly mirrored directional controls; in English mode, 100% left-to-right.
- **SC-004**: Language choice survives app restart and device reboot in 100% of cases.
- **SC-005**: No screen in either language shows truncated, overlapping, or unreadable text during the full-app walkthrough.
- **SC-006**: English and Arabic resource files contain the same set of entries (zero missing translations) verified by an automated parity check.

## Assumptions

- Only two languages are in scope: Arabic (Modern Standard Arabic) and English. Additional languages are out of scope for this feature but the approach must not preclude adding more later.
- The language toggle controls the app's display language only; it does not change backend data, monitoring behavior, or the child/parent role logic.
- Arabic translations will be produced as part of this feature (no external translation vendor); tone matches the existing Haris brand voice used on the harisfamily.com bilingual sites.
- Pairing codes, URLs, email addresses, and technical identifiers remain in Latin script in both languages; all digits app-wide are Western (0-9) per clarification.
- The existing legal-document websites bundled in the app already handle their own language rendering; this feature only needs to pass the preferred language to them where supported.
- Per-user language sync across devices (parent phone vs child device) is out of scope — the preference is per device.
