# Tasks: Legal Documents Public Websites

**Input**: Design documents from `/specs/007-legal-docs-websites/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/website-contract.md, quickstart.md

**Tests**: Not requested — validation is performed via quickstart checklist tasks (the spec's acceptance scenarios), not automated test suites.

**Organization**: Tasks grouped by user story. US1 = privacy site live (P1), US2 = terms site live (P1), US3 = bundled assets copy verified (P3).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: US1 / US2 / US3 from spec.md
- Exact file paths in every description

## Path Conventions

Sites are authored directly in `app/src/main/assets/websites/` (single source of truth — research R7). Tooling in `tools/legal-sites/`.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Directory skeleton + conversion/publish tooling scaffolding

- [X] T001 Create directory skeleton: `app/src/main/assets/websites/privacy-policy/fonts/`, `app/src/main/assets/websites/terms/fonts/`, `tools/legal-sites/`
- [X] T002 Write `tools/legal-sites/extract-docx.sh` — unzip each docx from `app/src/main/assets/`, strip `word/document.xml` to plain-text paragraphs, print paragraph/section counts (repeatability per FR-012; approach from research R2)
- [X] T003 [P] Acquire self-hosted fonts (dev-time download, OFL-licensed): Inter 400/600/700 + Cairo 400/600/700 as WOFF2 into `app/src/main/assets/websites/privacy-policy/fonts/` and copy the identical set into `app/src/main/assets/websites/terms/fonts/` (research R3)

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Verified source text + shared CSS/JS + publish tooling — everything both stories consume

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

- [X] T004 Run `tools/legal-sites/extract-docx.sh`; confirm Privacy ≈ 470 paragraphs / 30 sections and Terms ≈ 374 paragraphs / 24 sections; keep extraction outputs (scratchpad or `tools/legal-sites/out/`, gitignored) as the fidelity reference for FR-003
- [X] T005 [P] Author `app/src/main/assets/websites/privacy-policy/styles.css` implementing the full token table from research R4 (CSS custom properties, `prefers-color-scheme` light/dark with dark canonical, Inter/Cairo `@font-face`, `[dir="rtl"]` rules, responsive layout with 360 px floor, ~72ch content column, petrol→aqua header gradient, gold accents never in gradients, WCAG AA contrast, `:focus-visible`, skip-link, ≥44 px touch targets) — then copy the identical file to `app/src/main/assets/websites/terms/styles.css`
- [X] T006 [P] Author `app/src/main/assets/websites/privacy-policy/script.js` implementing contract §3 (EN/AR toggle flipping `html[lang]`/`html[dir]`, Arabic-unavailable notice show/hide, chrome label swap, `localStorage["haris-legal-lang"]` persistence applied pre-paint via inline head snippet, ToC active-state highlight, back-to-top; ≤10 KB, zero external requests, progressive enhancement per research R6) — then copy the identical file to `app/src/main/assets/websites/terms/script.js`
- [X] T007 Write `tools/legal-sites/publish.ps1` per contract §4: read `HARIS_FTP_HOST`/`HARIS_FTP_USER`/`HARIS_FTP_PASS` from env (abort exit 2 if unset), upload each site folder via `curl --ftp-create-dirs` to `/privacy-policy/` and `/terms/`, FTPS-first (`--ssl-reqd`) with plain-FTP fallback + warning, per-file OK/FAIL report, exit 0 only on full success, password never echoed (FR-009/FR-010, Constitution V)

**Checkpoint**: Shared text, styles, script, and publish tooling ready — US1/US2 can proceed in parallel

---

## Phase 3: User Story 1 - Privacy Policy on the public web (Priority: P1) 🎯 MVP

**Goal**: `https://www.harisfamily.com/privacy-policy/` serves the complete, Haris-branded privacy policy

**Independent Test**: Open the public URL in a browser — full policy content from `privacy_policy.docx` renders legibly, on-brand, responsive; works even if the terms site doesn't exist yet

### Implementation for User Story 1

- [X] T008 [US1] Author `app/src/main/assets/websites/privacy-policy/index.html` per DOM contract §2: `lang="en" dir="ltr"`, header with "حارس · HARIS" brand mark + "Privacy Policy" + version/date lines, ToC of 30 anchor links (functional without JS), hidden `#lang-notice` (Arabic text), `<main>` with one `<section id="<slug>">` per numbered section (h1→h2→h3 hierarchy), footer with contacts + "End of Privacy Policy", `#lang-toggle` + `#back-to-top` buttons — content transcribed in full from T004 extraction
- [X] T009 [US1] Fidelity check: diff `index.html` text content against T004 extraction — every heading and paragraph present, source order preserved, dates rendered verbatim (FR-003/FR-011, SC-002)
- [X] T010 [US1] Local validation per quickstart §2–4 for the privacy page: file:// render, dark/light, anchors with JS disabled, zero external requests in DevTools, EN⇄AR toggle + persistence + RTL, 360 px and desktop layouts
- [X] T011 [US1] Publish privacy site: run `tools/legal-sites/publish.ps1` with env credentials (supplied out-of-band); confirm per-file OK for `privacy-policy/*`
- [X] T012 [US1] Live verification: `https://www.harisfamily.com/privacy-policy/` loads < 3 s with full content and correct assets MIME (quickstart §6, SC-001)

**Checkpoint**: Privacy policy URL is live and store-listing-ready — MVP delivered

---

## Phase 4: User Story 2 - Terms & Conditions on the public web (Priority: P1)

**Goal**: `https://www.harisfamily.com/terms/` serves the complete, Haris-branded terms & conditions

**Independent Test**: Open the public URL — full terms content from `terms_conditions.docx` renders legibly, on-brand, responsive, visually consistent with the privacy page

### Implementation for User Story 2

- [X] T013 [P] [US2] Author `app/src/main/assets/websites/terms/index.html` per DOM contract §2: same structure as privacy page with "Terms & Conditions" title, ToC of 24 anchor links, one `<section>` per numbered section, footer with support/legal emails + www.harisfamily.com + "End of Terms and Conditions" — content transcribed in full from T004 extraction
- [X] T014 [US2] Fidelity check: diff terms `index.html` text against T004 extraction — every heading and clause present, order preserved (FR-003/FR-011, SC-002)
- [X] T015 [US2] Local validation per quickstart §2–4 for the terms page (same checklist as T010), plus cross-check visual consistency with the privacy page (US2 acceptance #3)
- [X] T016 [US2] Publish terms site via `tools/legal-sites/publish.ps1`; confirm per-file OK for `terms/*`
- [X] T017 [US2] Live verification: `https://www.harisfamily.com/terms/` loads < 3 s with full content (SC-001)

**Checkpoint**: Both legal URLs live and consistent

---

## Phase 5: User Story 3 - Documents bundled inside the app package (Priority: P3)

**Goal**: Generated sites ship inside app assets, identical to the published copies

**Independent Test**: Inspect `app/src/main/assets/websites/` — both sites present, open correctly from disk, match live pages

### Implementation for User Story 3

- [X] T018 [US3] Verify bundled copies: both `app/src/main/assets/websites/*/index.html` render correctly from `file://` and their content is byte-identical to what publish.ps1 uploaded (research R7 invariant; US3 acceptance #1–2)
- [X] T019 [US3] Run `./gradlew assembleDebug` and confirm the build succeeds with the new `websites/` assets included (APK size delta ≤ ~1 MB per plan note; `aaptOptions` untouched — Constitution rule 12)

**Checkpoint**: All three user stories independently verified

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Final audits and maintainer documentation

- [X] T020 [P] Write `tools/legal-sites/README.md`: regeneration procedure (docx changed → extract → update index.html → validate → publish), env-var setup for publishing, explicit "credentials are never committed" note (SC-005 — update live in < 10 min)
- [X] T021 Secrets audit per quickstart §7: `git grep` for FTP username/password fragments across the tracked tree — expect CLEAN; also confirm no credentials in shell scripts, specs, or generated HTML (SC-006, Constitution V)
- [X] T022 Run full quickstart.md end-to-end (all 7 sections) as final acceptance pass

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: none — start immediately
- **Foundational (Phase 2)**: needs T001–T003; T004 needs T002; T005/T006 need T001 (T003 for font references); T007 independent of T004–T006
- **US1 (Phase 3)**: needs Phase 2 complete (T008 consumes T004 text + T005 css + T006 js; T011 needs T007)
- **US2 (Phase 4)**: needs Phase 2 complete — independent of US1 (T013 parallel with T008 if staffed)
- **US3 (Phase 5)**: needs US1 + US2 published (byte-comparison against live)
- **Polish (Phase 6)**: needs all stories complete

### User Story Dependencies

- **US1 (P1)**: independent after Foundational
- **US2 (P1)**: independent after Foundational; shares only identical-copy css/js authored in Phase 2
- **US3 (P3)**: verification-only; depends on US1+US2 outputs existing

### Parallel Opportunities

- T003 ∥ T002 (fonts vs script)
- T005 ∥ T006 ∥ T007 (css vs js vs publish script — different files)
- Phase 3 ∥ Phase 4 entirely (different site folders): T008 ∥ T013, T010 ∥ T015, etc.
- T020 ∥ T021

## Parallel Example: after Phase 2 checkpoint

```bash
# Two authors, one per site:
Task: "Author app/src/main/assets/websites/privacy-policy/index.html (T008)"
Task: "Author app/src/main/assets/websites/terms/index.html (T013)"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Phase 1 → Phase 2 (foundation)
2. Phase 3 (privacy site) → **STOP and VALIDATE**: privacy URL live = store-listing unblocked
3. Continue to US2/US3

### Incremental Delivery

1. Foundation → privacy site live (MVP) → terms site live → bundled-copy verification → polish
2. Each publish is independent; a failed terms upload never breaks the live privacy URL

---

## Notes

- [P] = different files, no shared-file conflicts
- T005/T006 copies must stay identical between the two site folders — any later edit re-copies to both
- Credentials only ever via `HARIS_FTP_*` env vars at T011/T016 runtime
- Commit after each task or logical group; never commit anything from T021's search targets
