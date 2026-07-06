# Feature Specification: App Rebrand to "Haris"

**Feature Branch**: `002-app-rebrand-haris`

**Created**: 2026-06-26

**Status**: Draft

**Input**: User description: "create app logo and app icon and app icon launcher and app splash logo from C:\Users\Dell\Downloads\ui_system_design\logo.png and change app name to \"haris\""

## Clarifications

### Session 2026-06-26

- Q: App display name casing on user-facing surfaces? → A: `Haris` (capitalised proper noun) everywhere the name appears.
- Q: Background fill for assets where the source logo is transparent (adaptive-icon background, splash)? → A: Solid brand color sampled from the logo's dominant color.
- Q: Is a high-resolution store/marketing listing icon (512px) in scope? → A: Yes — produce it (FR-006 retained).
- Q: Provide a monochrome themed-icon layer for Android 13+ wallpaper tinting? → A: Yes — include a monochrome layer (FR-011).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - New brand identity on the device home screen (Priority: P1)

A user installs the app on an Android device. On the home screen and app drawer, the launcher icon shows the new brand artwork derived from the supplied source logo, and the label beneath the icon reads "Haris" instead of the previous name.

**Why this priority**: The launcher icon and name are the most visible, persistent representation of the product. Without them the rebrand has not effectively happened for the user, regardless of any other change. This single change alone delivers a recognisable rebrand.

**Independent Test**: Install the build on a device, view the home screen / app drawer, and confirm the icon matches the new logo and the label reads "Haris".

**Acceptance Scenarios**:

1. **Given** the app is installed, **When** the user views the app drawer or home screen, **Then** the launcher icon displays the new logo artwork and the label reads "Haris".
2. **Given** a device that supports themed/adaptive launcher icons, **When** the user views the icon at different sizes and shapes (round, squircle), **Then** the logo remains correctly framed, centred, and not clipped.

---

### User Story 2 - Consistent brand on app launch (Priority: P2)

When the user taps the icon, the app opens to a launch/splash experience that displays the new logo while the app initialises, reinforcing the brand before the main UI appears.

**Why this priority**: The splash is the first in-app impression and ties the home-screen icon to the running app. It is important for brand consistency but the app is still usable and recognisably rebranded without it, so it ranks below the launcher.

**Independent Test**: Cold-start the app and confirm the launch/splash screen shows the new logo, then transitions into the app.

**Acceptance Scenarios**:

1. **Given** the app is cold-started, **When** the launch/splash screen is shown, **Then** the new logo is displayed centred and legible.
2. **Given** the splash is shown on devices with light and dark system themes, **When** the logo renders, **Then** it remains clearly visible against the background in both themes.

---

### User Story 3 - Consistent brand inside the app and at the OS level (Priority: P3)

Wherever the product previously presented its name or mark — in-app branded surfaces (e.g. a logo on a sign-in or header screen), and OS surfaces such as the recent-apps/task switcher and system settings — the new "Haris" name and logo are shown consistently.

**Why this priority**: These touchpoints complete the rebrand and remove any leftover references to the old identity, avoiding a confusing mixed-brand experience. They are lower priority because they are encountered less frequently than the launcher and splash.

**Independent Test**: Navigate the app's branded screens, open the recent-apps switcher, and check system settings; confirm every visible product name reads "Haris" and any displayed logo matches the new artwork.

**Acceptance Scenarios**:

1. **Given** the app is running, **When** the user opens any in-app screen that displays the product logo or name, **Then** the new logo and the name "Haris" are shown.
2. **Given** the user opens the OS recent-apps switcher, **When** the app's task entry is shown, **Then** the title reads "Haris".

---

### Edge Cases

- The source logo is a single raster image; when scaled down to the smallest icon sizes, fine detail or thin strokes may become illegible. The produced icons MUST remain recognisable at the smallest standard launcher size.
- The source logo may have a non-square aspect ratio or transparent/coloured background; framing into the required square and adaptive-icon safe zones must avoid clipping the mark and must present an acceptable background where transparency would otherwise show.
- Devices with adaptive icons apply a system mask (circle, squircle, rounded square); the mark must stay inside the safe zone so no part is cut off by any mask shape.
- Light vs dark system themes and themed-icon (monochrome) modes must not render the logo invisible or low-contrast.
- App-store / listing artwork (high-resolution icon) is required at a higher resolution than on-device icons; the source image must be sufficient to produce it without visible upscaling artefacts.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The app's user-facing display name MUST be exactly "Haris" (capitalised proper noun) everywhere the product name is presented to users (launcher label, OS recent-apps/task switcher, system app settings, and in-app branded surfaces).
- **FR-002**: The launcher icon MUST be produced from the supplied source logo and MUST be provided at all standard density variants so it renders crisply on low- to high-density displays.
- **FR-003**: The launcher icon MUST support the platform adaptive-icon format (separate foreground mark and background) so it displays correctly under any system mask shape without clipping the mark. The background MUST be a solid brand color sampled from the source logo's dominant color.
- **FR-004**: A launch/splash representation of the logo MUST be displayed during app startup, centred and legible against a solid brand-color background sampled from the source logo's dominant color.
- **FR-005**: An in-app brand logo asset MUST be available for branded screens (e.g. sign-in / header) and MUST visually match the launcher and splash artwork.
- **FR-006**: A high-resolution brand logo / store-listing icon MUST be produced for store and marketing use at the platform-required resolution.
- **FR-007**: All previous brand name references visible to users MUST be replaced so that no occurrence of the prior product name remains on any user-facing surface.
- **FR-008**: All generated brand assets MUST be derived from the single supplied source logo so that the launcher icon, splash logo, in-app logo, and store icon are visually consistent.
- **FR-009**: Each produced asset MUST preserve the logo's aspect ratio (no stretching/distortion) and keep the mark within the safe area for its context.
- **FR-010**: The rebrand MUST NOT change the app's installation identity in a way that prevents existing installs from updating in place (the rebrand is presentation-only; it does not create a separate, second app).
- **FR-011**: The launcher icon MUST include a monochrome variant of the mark so devices that support themed icons (Android 13+) can tint it to the user's wallpaper; on devices without themed-icon support the standard adaptive icon is shown unchanged.

### Key Entities *(include if feature involves data)*

- **Source Logo**: The single supplied master image (`C:\Users\Dell\Downloads\ui_system_design\logo.png`) from which every brand asset is derived. Key attributes: artwork, aspect ratio, background (transparent or coloured), resolution.
- **App Display Name**: The user-facing product name, set to the value "Haris".
- **Brand Asset Set**: The derived deliverables — launcher icon (all densities + adaptive foreground/background), splash logo, in-app logo, and high-resolution store icon — each a sized/framed rendering of the Source Logo.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 100% of user-facing surfaces that display the product name show "Haris"; zero occurrences of the previous product name remain on any user-visible surface.
- **SC-002**: The launcher icon renders without clipping, distortion, or visible upscaling artefacts across all standard launcher sizes and under every system icon-mask shape (round, squircle, rounded square).
- **SC-003**: The new logo is clearly legible (recognisable without zooming) at the smallest standard launcher icon size on a representative low-density device.
- **SC-004**: The splash logo is visible and legible against both light and dark system backgrounds.
- **SC-005**: A first-time viewer can identify the launcher icon, splash logo, in-app logo, and store icon as the same brand (visual consistency confirmed by side-by-side review).
- **SC-006**: After updating an existing install to the rebranded build, the app updates in place (no duplicate app appears) and launches successfully showing the new identity.

## Assumptions

- The rebrand is **presentation-only**: only the user-facing display name and brand artwork change. The application's internal package/installation identity is unchanged, so existing installs update in place rather than appearing as a separate app.
- "Haris" is the exact display label, capitalised as a proper noun (confirmed in Clarifications, Session 2026-06-26).
- The supplied `logo.png` is the authoritative master artwork and is of sufficient resolution to generate the highest-resolution required asset (store icon) without noticeable quality loss.
- Standard platform icon/splash conventions apply (adaptive launcher icon with foreground/background, density-specific raster variants, a dedicated splash logo, and a high-resolution store icon).
- Where the source logo has transparency, contexts requiring a filled background (adaptive icon background, splash background) use a solid brand color sampled from the logo's dominant color (confirmed in Clarifications, Session 2026-06-26).
- No change to app functionality, navigation, or data handling is in scope; this feature only changes name and visual brand assets.
