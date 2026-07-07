# Haris — حارس · landing page

Standalone, static marketing landing page for the Haris parental-control app.
Bilingual (English / Arabic + RTL), Material-3 theme mirroring the mobile app.

> **Standalone site.** These files live under the repo-root `assets/` tree, **not**
> `app/src/main/assets/`. They are **not** bundled into the APK and must never be
> referenced by app code, Gradle, or the manifest.

## Files

```
assets/websites/haris-landing/
├── index.html      semantic markup, data-i18n attributes, inline SVG logo/icons
├── css/styles.css  tokens, layout, light+dark themes, RTL logical props, animations
├── js/main.js      i18n toggle + localStorage, IntersectionObserver reveals,
│                   count-up, single-open accordion, back-to-top
├── favicon.svg     shield mark in brand colors
├── robots.txt      allow all
└── README.md       this file
```

Pure HTML + CSS + vanilla JS. **No build step, no npm, no frameworks.** The only
remote resource is Google Fonts (Cairo + Inter) via `<link>` in `index.html`.

## Preview locally

Any static server, or just open the file:

```bash
# option A — open directly
start assets/websites/haris-landing/index.html      # Windows
# option B — tiny local server (nicer for relative paths)
python -m http.server 8080 --directory assets/websites/haris-landing
# then browse http://localhost:8080/
```

Checklist while previewing:

- Hero renders with petrol-teal → aqua gradient and gold accents.
- Language toggle (**EN | ع**) flips the whole layout to RTL and swaps all copy;
  choice persists across reloads (localStorage key `haris-lang`).
- Every section fades/rises in on scroll; the phone mockups animate.
- `prefers-reduced-motion: reduce` disables entrance/loop animation.
- Responsive at 360 px / 768 px / 1200 px; no horizontal scroll.
- No console errors; the only network calls are to `fonts.googleapis.com` /
  `fonts.gstatic.com`.

## Deploy (FTP)

Credentials come from **environment variables only** — never commit them.

```bash
export FTP_HOST=www.harisfamily.com
export FTP_USER='harisftp@harisfamily.com'   # supplied out-of-band
export FTP_PASS='********'                    # supplied out-of-band

# Confirm the web root first (/public_html vs /). Mirror ONLY these files;
# never delete unrelated remote content (e.g. existing /privacy-policy, /terms).
lftp -u "$FTP_USER","$FTP_PASS" "$FTP_HOST" -e "
  set ftp:ssl-allow true;
  mirror -R --only-newer --verbose \
    assets/websites/haris-landing/ /public_html/;
  bye"
```

If `lftp` is unavailable, upload each file with `curl -T` over `ftp://`/`ftps://`.

After upload, verify: <https://www.harisfamily.com/>

### TODO before launch (from brief — confirm with owner)

- Footer **Privacy Policy** link: brief said `/privacy`; site currently points to the
  live `/privacy-policy/`. Confirm canonical URL.
- **Account & Data Deletion** page `/data-deletion` — confirm it exists.
- **Support** `mailto:support@harisfamily.com` — confirm the address.
- Google Play badge stays **"coming soon"** until the store listing is live.
