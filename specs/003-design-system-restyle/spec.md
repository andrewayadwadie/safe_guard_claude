# Feature Specification: Haris Design-System Restyle

**Feature Branch**: `003-design-system-restyle`

**Created**: 2026-06-26

**Status**: Draft

**Input**: User description: "Restyle the existing native Android app with a brand-new design system derived from the new Haris logo (petrol/teal-blue protective ring around a gold family mark). Apply a recolored Material 3 theme, bilingual Inter/Cairo typography, new shapes/spacing/responsive rules, recomposition-safe motion, a set of reusable Haris* design-system components, and a screen-by-screen visual sweep — all confined to the presentation/theme layer and `res/` resources, with dark as the default theme, without changing any behavior, navigation, or non-presentation code."

## Clarifications

### Session 2026-06-26

- Q: Restyle scope — only the 7 named screens, or every Compose screen in the app? → A: All screens app-wide; the named list is illustrative. Every Compose screen plus the lock screen is restyled (including blacklist, children, devicesetup, imagereview, screentimelimits, TextMonitoringSettings, wordlist).
- Q: How should "dark is the default theme" behave at runtime, given no new state/ViewModel/DataStore may be added? → A: Follow the system setting (theme defaults to the device dark/light preference) with the dark scheme treated as the canonical design; no new persisted theme state is introduced.
- Q: Where should the new Haris* slot-API components live? → A: A new `presentation/designsystem/` package; existing screen usages migrate to the new components.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Consistent Haris brand identity across the whole app (Priority: P1)

A returning user opens the app and every screen — sign-in, dashboard, devices, alerts, settings, permissions setup, lock screen — now presents the new Haris visual identity: the petrol/teal primary color, gold accent, aqua tertiary, brand gradient banner on summary surfaces, pill-shaped buttons, rounded cards, and tonal-stepped surfaces. The product looks like one coherent, modern brand rather than a default template.

**Why this priority**: The unified visual identity is the entire point of the feature. A single screen left in the old style breaks the brand promise. This story alone — recolored theme applied everywhere — delivers a recognizable, shippable rebrand even before motion polish or responsive tuning.

**Independent Test**: Launch the app and navigate every primary screen; confirm all colors, shapes, and brand elements match the Haris design system and no screen retains the previous styling or hardcoded colors.

**Acceptance Scenarios**:

1. **Given** the app is launched, **When** the user views any screen, **Then** all colors are drawn from the Haris brand palette (primary petrol-teal, gold accent, aqua tertiary) and no legacy or arbitrary hardcoded colors appear.
2. **Given** a screen with action buttons, **When** the user views the primary call-to-action, **Then** it is a pill-shaped button in the brand primary color with on-primary text.
3. **Given** the dashboard summary, **When** it renders, **Then** a brand-gradient header banner (petrol → aqua) is shown.
4. **Given** any card or list surface, **When** it renders, **Then** depth is conveyed through tonal surface stepping rather than heavy drop shadows.

---

### User Story 2 - Dark theme as the default, with full light-theme parity (Priority: P2)

A user on a fresh install sees the app in its dark, high-contrast brand theme by default. A user who switches their device (or the app) to light mode sees the same brand identity rendered in the light palette with equally legible, correctly contrasted surfaces.

**Why this priority**: Dark-as-default is an explicit brand decision and the most common first impression. Both themes must be complete and legible, but light-theme parity builds on the same token set established for dark, so it follows the core restyle. The theme follows the device's system dark/light preference (no new in-app theme toggle or persisted theme state is added); the dark scheme is the canonical brand design.

**Independent Test**: Set the device to dark mode and confirm the app renders dark; set the device to light mode and confirm every screen renders its light counterpart with no missing or mis-mapped colors.

**Acceptance Scenarios**:

1. **Given** a device set to dark mode, **When** the app launches, **Then** it renders in the dark Haris theme.
2. **Given** the system theme is set to light, **When** the user navigates the app, **Then** every screen and component renders correct light-palette colors with no unreadable or default-colored elements.
3. **Given** the device theme preference changes, **When** the app re-renders, **Then** it follows the new system preference without requiring an in-app toggle.
3. **Given** either theme, **When** system chrome (status bar, window background) is shown, **Then** it matches the active theme's brand surface colors.

---

### User Story 3 - Bilingual typography and correct right-to-left layout (Priority: P3)

An English-speaking user sees text rendered in the Inter typeface; an Arabic-speaking user sees text rendered in the Cairo typeface with the entire layout mirrored correctly for right-to-left reading.

**Why this priority**: Typography and RTL correctness are essential for a polished bilingual product, but the app is already legible and recognizably rebranded with the default font before this refinement lands.

**Independent Test**: View the app in an English locale and confirm Inter is applied across the type scale; switch to an Arabic locale and confirm Cairo is applied and the layout mirrors (icons, alignment, list insets) correctly.

**Acceptance Scenarios**:

1. **Given** a Latin/English locale, **When** any text renders, **Then** it uses the Inter typeface across all text styles.
2. **Given** an Arabic locale or right-to-left layout direction, **When** any text renders, **Then** it uses the Cairo typeface.
3. **Given** an Arabic locale, **When** any screen renders, **Then** layout, alignment, and directional spacing are correctly mirrored for right-to-left reading.

---

### User Story 4 - Accessible, legible interface in both themes (Priority: P3)

A user with low vision, or one using a screen reader, can read all body text comfortably and hear meaningful descriptions of interactive icons and images.

**Why this priority**: Accessibility is a quality gate the brand must meet, layered on top of the color and typography work once the token set is stable.

**Independent Test**: Run a contrast check on body text against its background in both themes; enable a screen reader and traverse each screen confirming meaningful descriptions on actionable icons/images and silence on purely decorative ones.

**Acceptance Scenarios**:

1. **Given** either theme, **When** body text is shown on its surface, **Then** the text/background contrast meets WCAG AA.
2. **Given** a screen reader is active, **When** the user focuses an actionable icon or image, **Then** a meaningful spoken description is announced.
3. **Given** a screen reader is active, **When** the user reaches a purely decorative graphic, **Then** it is skipped (no description).

---

### User Story 5 - Comfortable layout on phones and larger screens (Priority: P4)

A user on a compact phone sees full-width content with comfortable padding; a user on a tablet or large/foldable screen sees content capped to a readable width and centered rather than stretched edge to edge.

**Why this priority**: Responsive behavior improves the experience on larger devices but the majority of users are on compact phones where the base layout already works, so it ranks last.

**Independent Test**: View a content screen at a narrow width and a wide width; confirm padding increases on larger widths and content is capped and centered rather than stretched.

**Acceptance Scenarios**:

1. **Given** a compact-width screen, **When** a content screen renders, **Then** it uses the standard screen padding and full available width.
2. **Given** a medium- or expanded-width screen, **When** a content screen renders, **Then** content is capped to a readable maximum width, centered, and uses larger screen padding.

---

### Edge Cases

- **Behavioral regression**: The restyle must not alter any state, action, navigation route, or data flow. If any screen's behavior changes (e.g., a button no longer triggers its action, a permission flow stops opening system settings), the change is a defect.
- **Permissions setup**: Dangerous-permission rationale cards MUST continue to open the system settings screen — they must not be converted into in-app dialogs during the restyle.
- **Alerts list**: The existing stacked/grouped alert-count rendering MUST be preserved while severity colors and filter chips are restyled.
- **Lock screen**: The lock screen must remain calm and high-contrast with minimal motion; brand motion effects applied elsewhere must not be introduced here.
- **Animations and recomposition**: Motion must be implemented so it does not cause per-frame layout recomposition (animated via deferred/graphics-layer reads, not by animating layout or opacity values that recompose every frame).
- **Font availability**: If a brand font cannot be retrieved on a device, text must fall back gracefully to a legible system font without breaking layout.
- **Long text / large font scale**: Buttons, cards, and list items must remain usable when text is long or the system font scale is enlarged.
- **Missing previews**: Any screen that previously lacked a design preview must gain light and dark previews.

## Requirements *(mandatory)*

### Functional Requirements

#### Brand color system
- **FR-001**: The app MUST define and apply a single Haris brand color system covering both a light and a dark color scheme using the specified brand palette (petrol-teal primary, gold secondary/accent, aqua tertiary) and the full set of Material surface, container, outline, and on-color tokens.
- **FR-001a**: The active theme MUST follow the device's system dark/light preference, with the dark scheme as the canonical design; no new in-app theme toggle or persisted theme state may be introduced.
- **FR-002**: All colors used anywhere in the presentation layer MUST come from the shared theme tokens or design-system color objects; no hardcoded color literals may appear inside screens or components.
- **FR-003**: Semantic colors (success, warning, info) and status colors (online, suspended, offline) MUST map to the brand palette, and the existing color-helper functions MUST be retained with only their referenced values updated.
- **FR-004**: Alert severity MUST be expressed on a four-tier calm-to-loud scale (low, medium, high, critical), each with its own accent and container color drawn from the brand palette.
- **FR-005**: A brand gradient (petrol → aqua) MUST be available as a preset for summary/banner surfaces; the gold accent MUST be used only as a solid accent and never within the gradient.
- **FR-006**: Non-Compose system chrome (status bar, window background, accent, severity colors) MUST be updated to match the brand in both light and dark, including a dark-theme resource variant.

#### Typography
- **FR-007**: Latin text MUST render in the Inter typeface and Arabic/right-to-left text MUST render in the Cairo typeface, both supplied as downloadable fonts (no bundled font binaries), with a graceful fallback when a font cannot be retrieved.
- **FR-008**: The full Material type scale (all defined text styles) MUST be specified to the brand type ramp, applied consistently across the app.
- **FR-009**: The app MUST select the correct typeface automatically based on layout direction / locale (Cairo for Arabic/RTL, Inter otherwise).

#### Shape, spacing, responsiveness
- **FR-010**: The design system MUST define the brand shape set (rounded corner scale plus a pill shape and a top-only bottom-sheet shape) and a spacing/dimension token set (screen padding, gutter, vertical stack increments, minimum list-item height, separator inset, and existing icon/avatar/button/progress tokens retained).
- **FR-011**: The app MUST adapt layout to screen width across compact, medium, and expanded breakpoints — capping content width and centering it and increasing screen padding on larger widths.

#### Motion
- **FR-012**: The design system MUST provide reusable, recomposition-safe motion effects (skeleton shimmer for loading, fade-and-scale entrance, staggered list entrance, and subtle press feedback on primary buttons), applied tastefully (dashboard cards animate in on first appearance; devices/alerts show shimmer skeletons while loading; the lock screen uses only a calm fade).

#### Components
- **FR-013**: The app MUST provide a set of reusable, logic-free Haris-branded slot-API components — primary/secondary/ghost buttons, text field, card with status slot, chip (including a filter variant), switch, list item, and gradient header — each styled exclusively from theme tokens. These components MUST live in a new `presentation/designsystem/` package, and existing screen usages MUST migrate to them.
- **FR-014**: Every design-system component and every screen MUST have light and dark design previews using realistic sample data.

#### Screen sweep
- **FR-015**: Every Compose screen in the app — including but not limited to authentication, dashboard, devices, alerts, settings, permissions setup, lock screen, and the additional screens (blacklist, children, device setup, image review, screen-time limits, text-monitoring settings, word list) — MUST be restyled to use the brand tokens, shapes, spacing, and shared components, replacing any hardcoded colors or sizes — without altering its state, actions, navigation, or backing logic. No screen may retain the legacy styling.
- **FR-016**: Each restyled screen MUST apply the standard screen padding, consistent vertical rhythm, minimum touch-target sizes, accessible content descriptions on icons/images, and correct right-to-left behavior.

#### Accessibility & integrity
- **FR-017**: Body-text contrast MUST meet WCAG AA in both themes; any failing pairing MUST be corrected to the nearest accessible on-color token.
- **FR-018**: Interactive icons and images MUST expose meaningful, localized content descriptions; purely decorative graphics MUST expose none.
- **FR-019**: The restyle MUST NOT modify any non-presentation code — view models, repositories, use cases, dependency-injection wiring, services, workers, receivers, networking, the API service, or navigation routes/state/actions — nor change the package name or existing public class/composable names (an additive brand alias is permitted).
- **FR-020**: The app MUST build successfully and pass static linting after the work, with the design system established incrementally so the project remains buildable at each stage.

### Key Entities *(include if feature involves data)*

- **Brand color scheme**: The complete set of light and dark color tokens (primary, secondary, tertiary, error, background, surfaces, containers, outlines, and their on-colors) that all UI colors reference.
- **Semantic & status palette**: Named meaning-bearing colors (success/warning/info, online/suspended/offline, four-tier severity) derived from the brand palette.
- **Type scale**: The mapping of each Material text style to a size/line-height/weight, plus the bilingual Inter/Cairo family selection.
- **Shape & dimension tokens**: The named corner shapes and spacing/sizing values used across the UI.
- **Design-system component set**: The reusable Haris-branded UI building blocks (buttons, text field, card, chip, switch, list item, gradient header).

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 100% of presentation-layer color usages resolve to a theme token or design-system color object — zero hardcoded color literals remain in screens or components.
- **SC-002**: 100% of the app's Compose screens (the 7 named screens plus blacklist, children, device setup, image review, screen-time limits, text-monitoring settings, and word list) render in the new brand style in both light and dark themes.
- **SC-003**: The app renders dark on a device set to dark mode and light on a device set to light mode, following the system preference with no in-app theme toggle.
- **SC-004**: Body-text contrast meets WCAG AA in both light and dark themes on every screen (zero failing body-text pairings).
- **SC-005**: Every design-system component and every screen has both a light and a dark preview.
- **SC-006**: In an Arabic locale, every screen renders in the Cairo typeface with correctly mirrored right-to-left layout, and in a Latin locale every screen renders in Inter.
- **SC-007**: Zero behavioral regressions — all existing actions, navigation, and flows (including the permissions setup opening system settings and the alerts stacked-count rendering) behave exactly as before the restyle.
- **SC-008**: No files outside the presentation/theme layer and `res/` resources are modified (view models, repositories, use cases, DI, services, workers, receivers, networking, API service, and navigation remain untouched), and the package name and existing class/composable names are unchanged.
- **SC-009**: The project builds successfully and passes static linting with no new warnings introduced by this work.
- **SC-010**: A larger-width screen caps and centers its content rather than stretching it edge to edge, while a compact screen uses full-width content with standard padding.

## Assumptions

- The brand palette, type ramp, shape/spacing values, severity tiers, and gradient stops provided in the feature description are authoritative and used verbatim.
- "Dark is the default theme" means the theme follows the device's system dark/light preference with the dark scheme as the canonical brand design; no in-app theme toggle or persisted theme state is added (honoring the hard rule against new state).
- Downloadable fonts are retrievable via the standard Google Fonts provider on target devices; when unavailable, the platform falls back to a legible system font.
- The existing theme, components, and screens already follow the project's Compose MVVM conventions, so the restyle can be applied without restructuring state or navigation.
- "All screens" means every existing Compose screen in the app (the enumerated set plus blacklist, children, device setup, image review, screen-time limits, text-monitoring settings, word list); no new screens or navigation destinations are added by this feature.
- New Haris* design-system components live in a new `presentation/designsystem/` package; the existing `presentation/components/` files are migrated/superseded as screens adopt the new components.
- Adding a brand alias for the existing theme entry point (e.g., a `HarisTheme` alias) is acceptable and does not count as renaming.
- WCAG AA is the target conformance level for body-text contrast; correcting a failing pairing means snapping to the nearest accessible on-color token rather than introducing a new off-palette color.
- Responsive width tiers are compact / medium / expanded, with content capped to a readable maximum width and centered on the larger two tiers.
