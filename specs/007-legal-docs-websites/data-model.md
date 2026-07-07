# Data Model: Legal Documents Public Websites

**Date**: 2026-07-06 | **Plan**: [plan.md](plan.md)

Static-content feature — the "model" is the document structure pipeline, not a database.

## Entities

### LegalDocument

The authoritative source content. Two instances.

| Field | Type | Notes |
|---|---|---|
| `id` | enum | `privacy-policy` \| `terms` |
| `sourceFile` | path | `app/src/main/assets/privacy_policy.docx` / `terms_conditions.docx` |
| `title` | string | "Privacy Policy" / "Terms & Conditions" |
| `brandMark` | string | "حارس · HARIS" (bidirectional; rendered in header) |
| `version` | string | "Version 1.0 (Draft)" — from doc front matter |
| `effectiveDate` | string | Currently "[To be assigned upon public release]" — rendered verbatim |
| `sections` | Section[] | Ordered; 30 (privacy) / 24 (terms) |
| `languagesPresent` | lang[] | `["en"]` for both (verified in research R1) |

**Validation rules**: Every extracted paragraph must appear in exactly one section (FR-003 — no dropped wording). Section numbering must be continuous 1..N and match the doc's own Table of Contents.

### Section

| Field | Type | Notes |
|---|---|---|
| `number` | int | 1-based, matches source numbering |
| `heading` | string | e.g. "5. Information We Collect" |
| `anchorId` | slug | e.g. `information-we-collect` — used by ToC links |
| `blocks` | Block[] | paragraphs, lists, sub-headings in source order |

**State**: none — immutable once generated.

### GeneratedSite

| Field | Type | Notes |
|---|---|---|
| `document` | LegalDocument | 1:1 |
| `rootDir` | path | `app/src/main/assets/websites/<id>/` |
| `entryPage` | file | `index.html` — semantic HTML5, `lang="en"` `dir="ltr"` initial |
| `stylesheet` | file | `styles.css` — theme tokens (research R4) |
| `script` | file | `script.js` — enhancement only (research R6) |
| `fonts` | file[] | inter/cairo woff2 ×3 weights each |
| `publicUrl` | url | `https://www.harisfamily.com/privacy-policy/` / `.../terms/` |

**Invariant**: rootDir contents are byte-identical to what is uploaded (research R7).

### ThemeTokens (read-only input)

Derived from `presentation/theme/Theme.kt` — full mapping table in research R4. Never modified by this feature.

### PublishTarget

| Field | Type | Notes |
|---|---|---|
| `host` | env `HARIS_FTP_HOST` | never committed |
| `user` | env `HARIS_FTP_USER` | never committed |
| `password` | env `HARIS_FTP_PASS` | never committed |
| `remoteDirs` | map | `privacy-policy` → `/privacy-policy/`, `terms` → `/terms/` |
| `transport` | enum | FTPS-explicit preferred → plain FTP fallback w/ warning |

### VisitorLanguagePreference (client-side state)

| Field | Type | Notes |
|---|---|---|
| storage key | `haris-legal-lang` | `localStorage`, per-origin |
| values | `"en"` \| `"ar"` | default `"en"` when absent |

**State transitions**: `en ⇄ ar` via toggle. Selecting `ar` sets `<html lang="ar" dir="rtl">`, shows the Arabic-unavailable notice, keeps English legal text visible (FR-014). Selecting `en` restores `lang="en" dir="ltr"`.
