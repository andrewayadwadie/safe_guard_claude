# Website Contracts: Legal Documents Public Websites

**Date**: 2026-07-06 | **Plan**: [../plan.md](../plan.md)

Four contracts: URL, DOM, script behavior, publish process.

## 1. URL Contract

| Resource | URL | Method | Response |
|---|---|---|---|
| Privacy Policy | `https://www.harisfamily.com/privacy-policy/` | GET | 200, `text/html`, full policy |
| Terms & Conditions | `https://www.harisfamily.com/terms/` | GET | 200, `text/html`, full terms |
| Site assets | `<site>/styles.css`, `<site>/script.js`, `<site>/fonts/*.woff2` | GET | 200, correct MIME |

- Entry file is `index.html` in each folder (server default-document convention).
- Pages make **zero** requests outside their own folder. Verification: DevTools network panel shows only same-folder requests.
- URLs are stable — app store listings will reference them; folder names must never change without a redirect plan.

## 2. DOM Contract (per page)

Required structure, top to bottom:

```html
<html lang="en" dir="ltr">                     <!-- toggled to lang="ar" dir="rtl" -->
  <header>            brand mark "حارس · HARIS" + doc title + version/date line
                      gradient band: petrol #0E6A86 → aqua #2799A5 (gold NEVER in gradient)
  <nav id="toc">      plain <a href="#anchor"> list — one link per numbered section;
                      functional WITHOUT JavaScript
  <div id="lang-notice" hidden>  Arabic-unavailable notice (Arabic text, dir=rtl)
  <main>              <section id="<anchorId>"> per numbered section;
                      heading hierarchy: h1 doc title → h2 section → h3 sub-heads
  <footer>            contact emails + www.harisfamily.com + "End of ..." line
  <button id="lang-toggle">   EN/AR switch — labeled in both scripts ("العربية" / "English")
  <button id="back-to-top" hidden>
```

**Content fidelity**: every paragraph of the source docx appears in `<main>`, source order preserved (FR-003). Effective/updated date placeholders rendered verbatim.

**Accessibility**: WCAG AA contrast for body text on both themes; single h1; skip-to-content link; `:focus-visible` styles; touch targets ≥ 44 px.

**Responsive**: no horizontal overflow at 360 px; ToC collapses to a details/summary or top strip on narrow viewports; content column max-width ~72ch on desktop.

**Theming**: `prefers-color-scheme` drives light/dark using research R4 token table; dark is canonical brand rendering.

## 3. Script Behavior Contract (`script.js`)

| Behavior | Trigger | Effect | No-JS fallback |
|---|---|---|---|
| Language toggle | click `#lang-toggle` | flips `html[lang]` + `html[dir]`; `ar` → shows `#lang-notice`, switches chrome labels to Arabic, body font to Cairo; `en` → reverse | toggle hidden; page stays EN/LTR |
| Persistence | after toggle | `localStorage.setItem("haris-legal-lang", lang)`; applied on load before first paint (inline head snippet to avoid FOUC) | n/a |
| Section nav highlight | scroll | active ToC link gets `aria-current` | anchors still navigate |
| Back-to-top | scroll > 2 viewports | button appears; click scrolls smooth to top | not shown |

Constraints: no frameworks, no external requests, no cookies, no analytics, total JS ≤ 10 KB unminified.

## 4. Publish Contract (`tools/legal-sites/publish.ps1`)

**Inputs** (environment only — never committed, never logged):

```
HARIS_FTP_HOST   e.g. www.harisfamily.com
HARIS_FTP_USER
HARIS_FTP_PASS
```

**Behavior**:

1. Abort with usage message if any env var unset (exit 2).
2. For each site folder under `app/src/main/assets/websites/`: upload every file via `curl --ftp-create-dirs` to the matching remote folder (`privacy-policy/`, `terms/`).
3. Try explicit FTPS first (`--ssl-reqd`); on TLS rejection, retry plain FTP once and print a cleartext-transport warning (research R5).
4. Print per-file OK/FAIL; exit 0 only if all files uploaded (FR-010). On partial failure, list failed files so a re-run can complete the set.
5. Never echo the password; `curl` invoked with credentials via `--netrc-file` style temp config or `-u` from variable, command echo disabled.

**Post-conditions**: both URL-contract endpoints return 200 with the freshly uploaded content (spot-check `Last-Modified`/content diff).
