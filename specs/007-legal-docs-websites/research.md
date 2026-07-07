# Phase 0 Research: Legal Documents Public Websites

**Date**: 2026-07-06 | **Plan**: [plan.md](plan.md)

All Technical Context unknowns resolved. Findings below.

## R1 — Source document language content

**Decision**: Ship English content; EN/AR toggle renders an Arabic-language "translation unavailable" notice on the AR side (FR-014). Page chrome (toggle labels, nav, back-to-top) is bilingual — chrome is interface text, not legal text, so translating it is permitted.

**Rationale**: Both docx files were extracted and inspected (`word/document.xml` → plain text). Privacy Policy: 470 paragraphs, 30 numbered sections; Terms: 374 paragraphs, 24 numbered sections. Arabic characters appear only in the brand mark "حارس · HARIS" and one inline gloss "(حارس)" — there is no Arabic body text. The Terms' own §24 states an Arabic version prevails if it exists, but none is present in the source. Per spec, legal wording is never machine-translated.

**Alternatives considered**: (a) Machine-translate to Arabic — prohibited by FR-014 and legally unsafe. (b) Drop the toggle entirely — violates clarified Q2=C decision; toggle stays so the Arabic translation can be slotted in when the docx gains it.

## R2 — Docx → HTML conversion approach

**Decision**: Build-time conversion, hand-verified: extract text via `unzip` + XML strip (`tools/legal-sites/extract-docx.sh`), then author semantic HTML from the extraction during implementation. The extraction script is committed so regeneration is repeatable (FR-012, SC-005).

**Rationale**: A one-shot content transform of two stable legal documents does not justify a runtime parser or a Node/Python toolchain dependency. `unzip` + `sed` are already available in the dev environment and produced clean, complete text in testing. Hand-authored HTML gives correct semantic structure (h2 per numbered section, proper lists) that automated converters (mammoth, pandoc) frequently garble on numbered legal headings.

**Alternatives considered**: (a) `pandoc` — not installed; adds toolchain dependency for marginal gain. (b) `mammoth.js` — needs Node; style-map fiddling for numbered headings. (c) Runtime docx parsing in the app — absurd for a static site; violates "no server-side processing".

## R3 — Web typography without external requests

**Decision**: Self-host Inter (Latin) and Cairo (Arabic) as WOFF2, weights 400/600/700, inside each site's `fonts/` folder. `font-display: swap`; system-stack fallbacks (`system-ui, -apple-system, Segoe UI, Roboto, sans-serif` / `Tahoma` for Arabic).

**Rationale**: FR-015 bans CDN/external requests, ruling out Google Fonts links. The app's theme mandates Inter + Cairo (Theme.kt "No-Roboto Rule"), and SC-003 requires visible typographic match. WOFF2 subsets at 3 weights ≈ 40–60 KB per weight → ~300 KB total per site, inside the 500 KB budget. ExtraBold (800) dropped — the pages use at most bold headings.

**Alternatives considered**: (a) Google Fonts CDN — violates FR-015. (b) System font stack only — fails SC-003 brand-match. (c) Variable fonts — single file but larger for only 3 used weights; static subsets simpler.

## R4 — Theme token mapping (Compose → CSS)

**Decision**: Encode Theme.kt values as CSS custom properties with dark as canonical (`prefers-color-scheme` aware, defaulting dark to match brand). Mapping:

| CSS token | Light | Dark | Source (Theme.kt) |
|---|---|---|---|
| `--primary` | `#168BB6` | `#A4DFF4` | HarisPetrol / HarisPetrolLight |
| `--on-primary` | `#FFFFFF` | `#0B465B` | onPrimary |
| `--secondary` | `#AF861D` | `#F1DCA7` | HarisGoldDark / HarisGoldLight |
| `--background` | `#FAFCFC` | `#0F1719` | background |
| `--on-background` | `#1C2022` | `#DDE1E3` | onBackground |
| `--surface` | `#FFFFFF` | `#0F1719` | surface |
| `--surface-container` | `#F0F5F7` | `#1A2427` | HarisColors.surfaceContainer |
| `--surface-container-high` | `#E8F0F3` | `#242F33` | surfaceContainerHigh |
| `--outline` | `#6B8C94` | `#8FA7AE` | outline |
| `--outline-variant` | `#C4D1D4` | `#3C4E53` | outlineVariant |
| `--gradient-start` | `#0E6A86` | `#0E6A86` | GradientStart |
| `--gradient-end` | `#2799A5` | `#2799A5` | GradientEnd |
| `--gold` | `#C8941E` | `#C8941E` | HarisGold (solid accent only — never in gradients) |

Type scale mirrors SafeGuardTypography: display 30/38, headline-L 24/32, headline-M 20/28, title-L 17/24, body-L 15/22, body-M 14/20 (px ≈ sp). Radii from SafeGuardShapes: 8/12/16/24 px; pill for buttons/chips. Spacing rhythm: 20 px screen padding, 16 px gutter, 8/16/24 stack.

**Rationale**: Direct 1:1 token transfer is the only way to satisfy SC-003 ("visibly match the in-app theme") verifiably. Brand rules preserved: gold never appears in gradients; petrol→aqua gradient reserved for the header band.

**Alternatives considered**: Eyeballing colors — unverifiable against SC-003; rejected.

## R5 — FTP publish tooling on Windows

**Decision**: `curl` per-file upload with `--ftp-create-dirs`, driven by `tools/legal-sites/publish.ps1`. Credentials read exclusively from environment variables `HARIS_FTP_HOST`, `HARIS_FTP_USER`, `HARIS_FTP_PASS`; script aborts with a clear error if any is unset (FR-009). Script first attempts explicit FTPS (`--ssl-reqd`); if the server rejects TLS it retries plain FTP with a logged warning. Per-file success/failure reported; non-zero exit on any failure (FR-010).

**Rationale**: `curl` ships with Windows 10 and git-bash — zero new dependencies. `--ftp-create-dirs` creates `/privacy-policy/` and `/terms/` remotely as needed. Env-var credentials keep secrets out of the repo and out of shell history (Constitution V). FTPS-first because plain FTP sends the password in cleartext; many shared hosts (cPanel-style, like this one) support explicit FTPS on port 21.

**Alternatives considered**: (a) WinSCP scripting — capable but requires install. (b) PowerShell `FtpWebRequest` — verbose, no FTPS-fallback ergonomics. (c) `lftp` — not available on Windows without WSL/cygwin.

## R6 — Progressive enhancement / no-JS behavior

**Decision**: English content is served in the initial HTML and fully readable without JavaScript. JS adds: language toggle (+`dir`/`lang` attribute switch), persisted choice via `localStorage["haris-legal-lang"]`, section nav (ToC links are plain anchors — work without JS; JS adds active-state highlight), and back-to-top button.

**Rationale**: Spec edge case requires content readable with JS disabled. Anchors + semantic headings give a functional baseline; everything scripted is enhancement only. `localStorage` chosen over cookies — no server, no consent-banner implications.

**Alternatives considered**: Rendering content via JS from a JSON payload — breaks no-JS requirement and SEO/reviewer crawlability; rejected.

## R7 — Bundled assets copy vs. deployed copy

**Decision**: `app/src/main/assets/websites/{privacy-policy,terms}/` is the single authored location; the publish script uploads exactly those folders. No separate "web build" directory.

**Rationale**: One source of truth guarantees the bundled and published copies never drift (User Story 3 acceptance: bundled files render identically). Android AAPT compresses text assets, so APK cost is below the raw ~1 MB estimate.

**Alternatives considered**: Authoring under `website/` at repo root and copying into assets — introduces a sync step that can silently drift; rejected.
