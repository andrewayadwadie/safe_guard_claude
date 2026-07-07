# Implementation Plan: Legal Documents Public Websites

**Branch**: `007-legal-docs-websites` | **Date**: 2026-07-06 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/007-legal-docs-websites/spec.md`

## Summary

Convert the two legal documents bundled in app assets (`privacy_policy.docx`, `terms_conditions.docx`) into two self-contained static websites styled with the Haris brand theme (petrol/teal + gold, Inter/Cairo typography, dark-canonical with light support). Place the generated sites in `app/src/main/assets/websites/`, then publish them via FTP to `harisfamily.com/privacy-policy/` and `harisfamily.com/terms/`. Both source documents were verified to be English-body-only (Arabic appears only in the brand mark), so pages ship English content with an EN/AR toggle whose Arabic side shows a clearly-marked "translation unavailable" notice per FR-014 — no legal text is machine-translated.

## Technical Context

**Language/Version**: HTML5 + CSS3 (custom properties, `prefers-color-scheme`) + vanilla JavaScript (ES2017, no build step). Tooling scripts: PowerShell 5.1 / bash.

**Primary Dependencies**: None at runtime (FR-015: zero external requests). Fonts self-hosted as WOFF2 (Inter + Cairo, weights 400/600/700). Upload tooling: `curl` (FTP/FTPS).

**Storage**: Browser `localStorage` — single key per site for persisted language choice (`haris-legal-lang`).

**Testing**: Manual validation checklist (quickstart.md) — content-fidelity diff against extracted docx text, responsive check at 360 px/desktop, LTR/RTL toggle check, offline/file:// render check, live URL verification after upload.

**Target Platform**: Any modern browser (mobile + desktop). Hosting: shared web host at `www.harisfamily.com` reachable via FTP; static files only, no server-side processing.

**Project Type**: Static websites (2) + Android app assets copy + one-off publish tooling.

**Performance Goals**: SC-001 — full page load < 3 s on broadband. Page-weight budget ≤ 500 KB per site including fonts; content HTML dominates (~100–150 KB text).

**Constraints**: Self-contained (no CDN/analytics/external fonts); readable with JS disabled (progressive enhancement); correct RTL when Arabic selected; FTP credentials never committed (Constitution V); repeatable regeneration (SC-005).

**Scale/Scope**: 2 pages. Privacy Policy = 30 numbered sections (~470 extracted paragraphs); Terms = 24 numbered sections (~374 paragraphs). Single deployment target.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

This feature produces static web artifacts and touches no Android runtime code. The twelve SpecKit rules are evaluated as follows:

| # | Rule | Status |
|---|------|--------|
| 1 | Feature sub-package under `presentation/<feature>/` | N/A — no app screens added or changed |
| 2 | `@HiltViewModel` | N/A — no ViewModels |
| 3 | `@Singleton` repositories | N/A — no repositories |
| 4 | ViewModel → Repository → `safeApiCall{}` → `ApiService` | N/A — no API calls |
| 5 | Tokens only in `TokenManager` | N/A — no app tokens involved |
| 6 | Special permissions via settings routing | N/A — no permissions touched |
| 7 | Services contain zero business logic | N/A — no services touched |
| 8 | Workers `@HiltWorker` + `CoroutineWorker` | N/A — no workers |
| 9 | `NetworkResult<T>` from repositories | N/A |
| 10 | Errors via Snackbar | N/A — no app UI |
| 11 | ProGuard for new Service/Receiver/Worker | N/A — none added |
| 12 | `aaptOptions { noCompress "tflite" }` retained | PASS — `app/build.gradle` untouched |

**Principle-level gates:**

- **Principle I (Child Safety & Privacy):** PASS. The sites are public legal documents; no child data is collected, transmitted, or rendered. Pages make zero network requests beyond loading their own static files, and contain no analytics.
- **Principle V (Secrets Hygiene):** PASS with enforcement — FTP host/user/password are supplied out-of-band (environment variables at publish time). They MUST NOT appear in any committed file: not in scripts, not in specs, not in generated sites. The upload script reads `HARIS_FTP_HOST` / `HARIS_FTP_USER` / `HARIS_FTP_PASS` from the environment and fails fast if unset.
- **APK impact note:** adding `websites/` to assets grows the APK by an estimated ≤ 1 MB (two sites × HTML/CSS/JS + self-hosted fonts). Accepted per spec assumption; fonts are the dominant cost and are deduplicated where possible.

**GATE RESULT: PASS** — no violations; Complexity Tracking not required.

## Project Structure

### Documentation (this feature)

```text
specs/007-legal-docs-websites/
├── plan.md              # This file (/speckit-plan command output)
├── research.md          # Phase 0 output (/speckit-plan command)
├── data-model.md        # Phase 1 output (/speckit-plan command)
├── quickstart.md        # Phase 1 output (/speckit-plan command)
├── contracts/
│   └── website-contract.md   # URL / DOM / JS-behavior / publish contracts
└── tasks.md             # Phase 2 output (/speckit-tasks command - NOT created by /speckit-plan)
```

### Source Code (repository root)

```text
app/src/main/assets/
├── privacy_policy.docx            # source of truth (existing, unchanged)
├── terms_conditions.docx          # source of truth (existing, unchanged)
└── websites/                      # NEW — generated static sites (FR-007)
    ├── privacy-policy/
    │   ├── index.html             # full policy content, EN, semantic headings
    │   ├── styles.css             # Haris theme tokens as CSS custom properties
    │   ├── script.js              # lang toggle + dir switch + nav + back-to-top
    │   └── fonts/
    │       ├── inter-*.woff2      # 400 / 600 / 700
    │       └── cairo-*.woff2      # 400 / 600 / 700
    └── terms/
        ├── index.html
        ├── styles.css
        ├── script.js
        └── fonts/                 # same set (each site self-contained)

tools/legal-sites/
├── extract-docx.sh                # repeatable docx → text extraction (SC-005 / FR-012)
└── publish.ps1                    # FTP upload; creds from env vars only (FR-009/FR-010)
```

**Structure Decision**: Each site is a fully self-contained folder (own CSS/JS/fonts) because the two sites deploy to two independent folders on the host (`/privacy-policy/`, `/terms/`) and must not share server-side paths. The same folders are copied verbatim into `app/src/main/assets/websites/` so the bundled copy is byte-identical to what is published (User Story 3). Conversion and upload tooling lives in `tools/legal-sites/`, outside the app source tree, since it runs at development time only.

## Complexity Tracking

> No constitutional violations — table not required.
