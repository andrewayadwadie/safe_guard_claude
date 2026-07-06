# Haris Brand Asset Generation

Regenerates every "Haris" brand asset from the single source logo. No ImageMagick/Inkscape required — uses .NET `System.Drawing` (present on Windows). See `../research.md` (R1–R8) and `../contracts/brand-assets.md`.

## Source

`C:\Users\Dell\Downloads\ui_system_design\logo.png` — 1024×1024, opaque, square (teal ribbon ring enclosing a gold people mark on white).

## Run

```powershell
& "specs\002-app-rebrand-haris\tooling\generate-assets.ps1"
# optional overrides:
#   -Source   <path to logo.png>
#   -RepoRoot <repo root>
```

Idempotent — rerunning overwrites outputs.

## What it produces

| Output | Notes |
|--------|-------|
| `app/src/main/res/mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher_foreground.png` | Mark on transparent (white bg removed via corner flood-fill), inset to 66% safe zone. 108/162/216/324/432 px. |
| `app/src/main/res/drawable/ic_launcher_monochrome.png` | White alpha silhouette of the mark (Android 13+ themed icons). 432 px, 60% inset. |
| `app/src/main/res/drawable/splash_logo.png` | Mark on transparent, 80% inset, 432 px. |
| `app/src/main/res/drawable/ic_brand_logo.png` | In-app logo, mark on transparent, 92% inset, 512 px. |
| `store/ic_store_512.png` | Full-bleed original (white bg) downscaled to 512×512. |
| `specs/002-app-rebrand-haris/tooling/dominant-color.txt` | Sampled dominant color. |

## Sampled brand color

**`#084868`** (dark teal) — the modal non-white/non-black color of the logo. Stored as `@color/brand_icon_background` in `app/src/main/res/values/colors.xml`; used for the adaptive-icon background drawable and the splash window background.

## How it works (pipeline)

1. Load source as 32bpp ARGB; lock bits to a BGRA byte buffer.
2. **Dominant color**: histogram of opaque pixels quantized to 16-steps, excluding near-white (>235) and near-black (<18); pick the modal bucket's center.
3. **Background removal**: stack-based flood-fill from the four corners over near-white (≥232) pixels → alpha 0. Leaves the mark; gaps in the ribbon become transparent so the teal background shows through.
4. Compute the tight bounding box of opaque pixels → crop to the mark.
5. Render the mark centered into each square canvas at the per-asset inset fraction with HighQualityBicubic.
6. **Monochrome**: map mark alpha → flat white silhouette (system tints it).
7. **Store icon**: separate full-bleed downscale of the untouched original.

## Manual review

After regenerating, eyeball:
- `ic_launcher_monochrome.png` — silhouette legible (open in an editor with a dark backdrop; it is white-on-transparent so invisible on white).
- Foreground PNGs — mark not clipped, white fully removed.
If the monochrome silhouette is muddy, substitute a simplified emblem per research R3.
