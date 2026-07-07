# Feature Specification: Legal Documents Public Websites

**Feature Branch**: `007-legal-docs-websites`

**Created**: 2026-07-06

**Status**: Draft

**Input**: User description: "read from assets (app/src/main/assets) file : privacy_policy.docx and file : terms_conditions.docx and from data in those files create two static websites (html, css and js) to present data exist in files and use theme of app to create ui of those two websites and put files of websites in assets folder then upload websites by using FTP (server www.harisfamily.com)."

## Clarifications

### Session 2026-07-06

- Q: Deployment layout / public URLs for the two sites? → A: Each site in its own subfolder at the web root — `harisfamily.com/privacy-policy/` and `harisfamily.com/terms/`.
- Q: Content language & text direction? → A: Bilingual — each page provides an English/Arabic language toggle that also switches text direction (LTR/RTL). Legal text is never machine-translated; a page exposes only the language(s) actually present in its source document and flags a missing translation rather than inventing one.
- Q: JavaScript scope for the sites? → A: Minimal self-contained vanilla JS — language (EN/AR) toggle with LTR/RTL switch and persisted preference, plus in-page section navigation and back-to-top. No frameworks, no CDNs, no external requests.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Read the Privacy Policy on the public web (Priority: P1)

An app store reviewer, a parent evaluating SafeGuard, or a data-protection authority follows a public link and reads the current SafeGuard privacy policy in a clean, branded web page without needing to install the app or open a Word document.

**Why this priority**: App store listings (Google Play) legally require a publicly reachable privacy policy URL. Without it the app cannot be published or updated. This is the single most business-critical outcome of the feature.

**Independent Test**: Deploy only the privacy-policy site, open its public URL in a browser, and confirm the full policy content from `privacy_policy.docx` renders legibly and matches the app's visual identity. Delivers standalone value (a usable privacy URL) even if the terms site does not exist yet.

**Acceptance Scenarios**:

1. **Given** the privacy-policy site is deployed, **When** a visitor opens its public URL, **Then** the complete text of `privacy_policy.docx` (headings, paragraphs, lists, ordering preserved) is displayed as a readable web page.
2. **Given** a visitor on a phone-sized screen, **When** they open the privacy-policy page, **Then** the layout adapts to the small screen with no horizontal scrolling and readable text.
3. **Given** a visitor lands on the page, **When** they view it, **Then** the colors, typography, and branding visibly match the SafeGuard app theme.

---

### User Story 2 - Read the Terms & Conditions on the public web (Priority: P1)

A parent or reviewer follows a public link and reads the current SafeGuard terms & conditions in the same branded style as the privacy policy.

**Why this priority**: Terms & Conditions are the second legal document commonly required for app distribution and for user trust. It is co-equal with the privacy policy for compliance, hence also P1.

**Independent Test**: Deploy only the terms site, open its public URL, and confirm the full content of `terms_conditions.docx` renders legibly and on-brand.

**Acceptance Scenarios**:

1. **Given** the terms site is deployed, **When** a visitor opens its public URL, **Then** the complete text of `terms_conditions.docx` (structure and ordering preserved) is displayed as a readable web page.
2. **Given** a visitor on any common device size, **When** they open the terms page, **Then** the layout is responsive and legible.
3. **Given** both sites are deployed, **When** a visitor views either page, **Then** the two pages share a consistent visual identity so they are recognizably the same product.

---

### User Story 3 - Documents bundled inside the app package (Priority: P3)

A future maintainer (or an in-app "Privacy Policy" / "Terms" link) can access the generated website files from within the app's bundled assets, so the same rendered content is available offline and versioned alongside the source documents.

**Why this priority**: Nice-to-have redundancy and future in-app linking. The public URLs (P1) satisfy the core compliance need; bundling is convenience and offline resilience.

**Independent Test**: Inspect the app assets directory and confirm the generated website files sit alongside the source `.docx` files and open correctly when loaded locally in a browser.

**Acceptance Scenarios**:

1. **Given** the sites have been generated, **When** the assets directory is inspected, **Then** both websites' files are present under the app assets area.
2. **Given** the bundled website files, **When** opened directly from disk in a browser, **Then** they render the same content as the hosted versions.

---

### Edge Cases

- What happens when a source `.docx` contains formatting the web page does not model (tables, embedded images, footnotes, hyperlinks)? Content MUST still be presented without loss of the underlying text; unsupported decorative formatting may be simplified but no wording may be dropped.
- How does the page behave with no network / when a visitor is offline after first load? (Static assets; expected to render from cache with no server round-trips required for content.)
- What happens when a `.docx` is later updated? There MUST be a repeatable way to regenerate and re-publish so the live site reflects the new document.
- How does the system handle a failed upload (bad credentials, server unreachable, partial transfer)? The publish step MUST make the failure visible and MUST NOT leave a half-updated site that mixes old and new files silently.
- What happens with right-to-left or non-Latin content, given the app ships Arabic language resources? Text direction and character rendering MUST remain correct for the selected language; toggling to Arabic switches the page to RTL and toggling to English switches to LTR.
- What happens when a source document exists in only one language but the page offers a two-language toggle? The page MUST show the available language and clearly mark the other as unavailable — never auto-translate legal wording.
- What happens if a visitor has JavaScript disabled? Core content MUST remain readable (the document's primary-language text renders without scripts); only the toggle/nav conveniences degrade.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The system MUST extract the full textual content and document structure (headings, paragraphs, ordered/unordered lists, and section order) from `privacy_policy.docx` and from `terms_conditions.docx` located in the app assets.
- **FR-002**: The system MUST produce two distinct static websites — one for the privacy policy and one for the terms & conditions — each consisting of self-contained web files (markup, styling, and behavior) that require no server-side processing to display.
- **FR-003**: Each website MUST present the complete content of its source document with the original reading order and section hierarchy preserved; no clauses or wording may be omitted.
- **FR-004**: The visual design (color palette, typography, spacing, and branding) of both websites MUST be derived from the SafeGuard app's existing theme so the pages are recognizably part of the same product.
- **FR-005**: Both websites MUST be responsive and legible across common screen sizes (phone, tablet, desktop) with no horizontal overflow of body content.
- **FR-006**: Both websites MUST meet baseline readability/accessibility expectations: sufficient text contrast, scalable text, and a logical heading structure.
- **FR-007**: The generated website files MUST be placed within the app assets area alongside the source documents.
- **FR-008**: The system MUST publish both websites to the designated public web host so each is reachable at a stable public URL, each site in its own document-root subfolder: the privacy policy at `/privacy-policy/` and the terms at `/terms/`.
- **FR-013**: Each page MUST provide an English/Arabic language toggle that switches both the displayed content and the text direction (LTR for English, RTL for Arabic), and MUST persist the visitor's chosen language across reloads.
- **FR-014**: The language toggle MUST expose only the language(s) actually present in the source document. Legal text MUST NOT be machine-translated; if a document lacks a translation for a language, the page MUST clearly indicate the translation is unavailable rather than fabricate one.
- **FR-015**: Page interactivity (language toggle, direction switch, in-page section navigation, back-to-top) MUST be implemented with self-contained scripts only — no third-party frameworks, no CDN or other external network requests at runtime.
- **FR-009**: Publishing MUST use the provided host and account credentials, which are supplied out-of-band and MUST NOT be committed to the repository or embedded in any tracked file.
- **FR-010**: The publish process MUST report success or failure for each site, and on failure MUST surface enough detail to diagnose it (e.g., authentication vs. connectivity vs. transfer error).
- **FR-011**: The content of each published page MUST match its corresponding source document at publish time (no stale or placeholder text).
- **FR-012**: The generation and publish steps MUST be repeatable so that an updated source document can be re-rendered and re-published without manual re-authoring of the page.

### Key Entities *(include if feature involves data)*

- **Privacy Policy Document**: The source `privacy_policy.docx`. Attributes: title, ordered sections, headings, body text, effective/updated date if present. It is the single source of truth for the privacy-policy website's content.
- **Terms & Conditions Document**: The source `terms_conditions.docx`. Same structural attributes; single source of truth for the terms website's content.
- **Generated Website**: A deployable bundle representing one document. Attributes: entry page, styling, optional scripts, association to exactly one source document, and a target public URL.
- **App Theme**: The existing SafeGuard visual identity (colors, typography, branding tokens). Read-only input that constrains the websites' appearance; not modified by this feature.
- **Publish Target**: The public web host and credentialed account used to make each website publicly reachable. Credentials are sensitive and externally supplied.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Both the privacy policy and terms & conditions are each reachable at a public URL and load their full content in a browser within 3 seconds on a typical broadband connection.
- **SC-002**: 100% of the substantive text present in each source `.docx` (every heading and clause) is present on the corresponding published page — verified by a side-by-side content comparison.
- **SC-003**: A first-time viewer can identify both pages as belonging to the same SafeGuard product on sight, and the pages visibly match the app's color and typography (validated by comparison against the in-app theme).
- **SC-004**: Both pages render without horizontal scrolling and remain legible at a 360 px-wide viewport and at desktop width, in both LTR (English) and RTL (Arabic) modes.
- **SC-007**: A visitor can switch a page between English and Arabic in one action; the switch updates both the text and the reading direction, and the choice is remembered on the next visit to that page.
- **SC-005**: When a source document is changed, a maintainer can regenerate and re-publish the affected site and see the update live in under 10 minutes, without hand-editing page content.
- **SC-006**: No host credentials or secrets appear anywhere in the committed repository after the feature is complete.

## Assumptions

- **Two independent sites**: The user asked for "two static websites," interpreted as two independently deployable bundles (one per document) rather than a single multi-page site. They may share a common visual style but are separately addressable.
- **Deployment layout** *(resolved in Clarifications)*: Each site is published into its own folder at the web host's document root — `privacy-policy/` and `terms/` — yielding `harisfamily.com/privacy-policy/` and `harisfamily.com/terms/`.
- **Language & translation** *(resolved in Clarifications)*: Pages are bilingual-capable with an EN/AR toggle that also flips direction. The `.docx` files are the sole source of wording for each language; no legal text is machine-translated. If a document turns out to contain only one language, that page ships that language and marks the other unavailable.
- **Assets copy**: "Put files of websites in assets folder" is interpreted as the app's `app/src/main/assets` directory, keeping generated output next to the source `.docx` files. This is accepted despite a modest increase in packaged size; if size becomes a concern the copy can be excluded later.
- **Theme source**: The "theme of app" refers to the SafeGuard Compose theme (color and typography definitions in the app's theme/resources). Its values are read as the design reference; the app itself is not modified by this feature.
- **Document fidelity**: Source documents are primarily structured text (headings, paragraphs, lists). Complex embedded objects (if any) are simplified visually but never dropped textually.
- **Credentials handling**: The provided FTP host, user, and password are treated as secrets. They are used only to perform the upload and are never written into any tracked repository file (per Constitution Principle V).
- **Content authority**: The `.docx` files are the authoritative, review-approved legal text; this feature presents them faithfully and does not rewrite, summarize, or legally interpret them.
