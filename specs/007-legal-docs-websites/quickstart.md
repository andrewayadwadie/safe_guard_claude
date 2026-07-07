# Quickstart: Legal Documents Public Websites

**Date**: 2026-07-06 | **Plan**: [plan.md](plan.md) | **Contracts**: [contracts/website-contract.md](contracts/website-contract.md)

Validation guide — proves the feature end-to-end. Run after implementation.

## Prerequisites

- Repo checked out on branch `007-legal-docs-websites`
- A modern browser (Chrome/Edge/Firefox)
- `curl` on PATH (ships with Windows 10)
- FTP credentials exported in the current shell (publish step only):

  ```powershell
  $env:HARIS_FTP_HOST = "www.harisfamily.com"
  $env:HARIS_FTP_USER = "<user>"        # supplied out-of-band
  $env:HARIS_FTP_PASS = "<password>"    # supplied out-of-band — never commit
  ```

## 1. Regenerate source text (repeatability check — FR-012)

```bash
bash tools/legal-sites/extract-docx.sh
# → writes extracted text for both docx files to a temp/output dir and prints paragraph counts
```

**Expected**: Privacy ≈ 470 paragraphs / 30 sections; Terms ≈ 374 paragraphs / 24 sections. Counts changing means the docx changed → regenerate HTML content.

## 2. Local render check (User Story 3 + edge cases)

Open directly from disk (no server):

```
app/src/main/assets/websites/privacy-policy/index.html
app/src/main/assets/websites/terms/index.html
```

Verify each page:

- [ ] Full document renders — spot-check first section, a middle section, and the "End of ..." line (FR-003)
- [ ] Haris theme visible: petrol→aqua header gradient, gold accents, Inter typography (SC-003)
- [ ] OS dark mode ⇄ light mode both render correctly (dark = canonical)
- [ ] ToC anchor links jump to correct sections **with JavaScript disabled** (progressive enhancement)
- [ ] DevTools network tab: zero external requests (FR-015)

## 3. Language toggle (SC-007 / FR-013 / FR-014)

- [ ] Click toggle → page flips to `dir="rtl"`, chrome labels become Arabic (Cairo font), Arabic-unavailable notice shown, English legal text still readable
- [ ] Reload → Arabic choice persisted (localStorage `haris-legal-lang`)
- [ ] Toggle back → LTR restored, notice hidden

## 4. Responsive check (SC-004)

DevTools device toolbar:

- [ ] 360 px width: no horizontal scroll, legible text, ToC usable — in **both** LTR and RTL modes
- [ ] Desktop width: content column capped (~72ch), layout intact

## 5. Publish (FR-008/009/010)

```powershell
powershell -File tools/legal-sites/publish.ps1
```

**Expected**: per-file OK lines, exit code 0. Failure lists failed files; re-run completes the set. Password never appears in output.

## 6. Live verification (SC-001/SC-002)

- [ ] `https://www.harisfamily.com/privacy-policy/` loads < 3 s, full content
- [ ] `https://www.harisfamily.com/terms/` loads < 3 s, full content
- [ ] Side-by-side: live page text vs `extract-docx.sh` output — every heading/clause present (SC-002)
- [ ] Bundled copy (step 2) matches live pages byte-for-byte (research R7 invariant)

## 7. Secrets audit (SC-006)

Search the tracked tree for the FTP username and any fragment of the FTP password (values supplied out-of-band):

```bash
git grep -iE '<ftp-user>|<any-password-fragment>' && echo LEAK || echo CLEAN
```

**Expected**: `CLEAN` — no credentials anywhere in the tracked tree. Env-var *names* (`HARIS_FTP_*`) are fine; their *values* are not.
