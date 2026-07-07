# Haris legal sites — build & publish

Generates and publishes the two public legal websites from the source `.docx`
files bundled in `app/src/main/assets/`:

| Document | Generated site | Public URL |
|---|---|---|
| `privacy_policy.docx` | `app/src/main/assets/websites/privacy-policy/` | https://www.harisfamily.com/privacy-policy/ |
| `terms_conditions.docx` | `app/src/main/assets/websites/terms/` | https://www.harisfamily.com/terms/ |

The generated folder is the **single source of truth** — the same files are
bundled in the app APK and uploaded to the host, so the two copies never drift.
Pages are fully self-contained: no CDN, no analytics, no external requests at
runtime (Inter + Cairo fonts are self-hosted WOFF2).

## Tooling

| Script | Purpose |
|---|---|
| `extract-docx.sh` | Dump each docx to plain text + paragraph/section counts (fidelity reference). Output → `tools/legal-sites/out/` (gitignored). |
| `fetch-fonts.py` | Download Inter (Latin) + Cairo (Arabic+Latin) WOFF2 @ 400/600/700 into each site's `fonts/`. Build-time only. |
| `build_sites.py` | Convert both docx → semantic `index.html`, honouring Word styles (numbered sections/subsections, lists, named callouts). Regenerates ToC with working anchors. |
| `publish.ps1` | Upload both sites via FTP. Credentials from env vars only. FTPS-first (falls back to unverified-TLS, then cleartext, each with a warning). |

## Regenerate after a document changes (SC-005)

```bash
# 1. (first time / font refresh only) fetch self-hosted fonts
python tools/legal-sites/fetch-fonts.py

# 2. rebuild the two index.html from the updated .docx
python tools/legal-sites/build_sites.py

# 3. sanity-check counts & fidelity
bash tools/legal-sites/extract-docx.sh
```

Then open the files locally (`app/src/main/assets/websites/*/index.html`) to eyeball,
and publish:

```powershell
$env:HARIS_FTP_HOST = "www.harisfamily.com"
$env:HARIS_FTP_USER = "<ftp-user>"      # supplied out-of-band
$env:HARIS_FTP_PASS = "<ftp-password>"  # supplied out-of-band
powershell -File tools/legal-sites/publish.ps1
```

## Credentials — never committed

FTP host, user, and password are **secrets** (Constitution Principle V). They are
supplied only through the `HARIS_FTP_*` environment variables at publish time and
must never be written into any tracked file — not scripts, not specs, not CI
config in the clear. `publish.ps1` aborts if the variables are unset and never
echoes the password.

The host presents a TLS certificate that does not match `harisfamily.com` (shared
hosting), so verified FTPS fails and the script uses encrypted-but-unverified
FTPS. To move to verified FTPS, obtain an FTP cert matching the domain (or an
SFTP endpoint) from the host and drop the `-k` tier from `publish.ps1`.

## Notes on source fidelity

`build_sites.py` emits every source paragraph in order (verified: 0 missing).
The Privacy Policy source contains a duplicated `8.2`/`8.3` block inside §18 (Data
Retention) — this is reproduced faithfully (with de-duplicated anchor ids) rather
than silently edited, because the legal text is authoritative and not rewritten.
