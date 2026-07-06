# On-Device ML Models — exact spec for the Flutter port

SafeGuard runs all content classification **on the child device**; nothing the child types or
views is sent off-device for analysis. This file is the authoritative reference for the model
I/O you must replicate when porting to `tflite_flutter` — input shapes, tokenization,
normalization, class order, and thresholds. Get these byte-exact or results drift silently.

There are **three** models across two pipelines: two script-routed **text** classifiers and one
**image** (NSFW) classifier.

---

## Text classification (Stage 2) — two script-routed backends

Stage 1 is a regex matcher (`TextPatternMatcher`, English + Arabic + 3arabizi, evasion-resistant)
that runs on every captured string. Stage 2 is the ML below, run when Stage 1 is uncertain. The
two text models are **real BERT** with **identical I/O**:

| | EN — toxic-bert | AR — MARBERTv2 (Egyptian) |
|---|---|---|
| Model file | `toxicbert_en_int8.tflite` (~112 MB) | `marbert_ar_int8.tflite` (~166 MB) |
| Vocab (bundled asset) | `toxicbert_en_vocab.txt` | `marbert_ar_vocab.txt` |
| Inputs | `input_ids` + `attention_mask`, both `[1, 128]` **int64** | same |
| Output | `[1, 6]` float **sigmoid** | `[1, 2]` float **softmax** |
| Labels (in order) | `toxic, severe_toxic, obscene, threat, insult, identity_hate` | `Neutral, Hate` |
| Special tokens | cls=101, sep=102, pad=0, unk=100 | cls=2, sep=3, pad=0, unk=1 |

- **Tokenizer:** real BERT **WordPiece** (`WordPieceTokenizer`), parity-locked to HuggingFace — not
  the old whitespace tokenizer. Max sequence length **128**, padded with the model's pad id; build
  `attention_mask` as 1 for real tokens, 0 for padding. The sigmoid/softmax is **already applied in
  the exported graph** — read the output probabilities directly, do not re-apply.
- **Script routing** (`TFLiteTextClassifier.route`): Arabic-script-dominant text → **AR**; Latin
  text detected as 3arabizi (`Arabizi.looksLikeArabizi`) → transliterate to Arabic script then
  **AR**; everything else → **EN**. Digit-less arabizi is indistinguishable from English and falls
  to EN (a known gap).
- **Category mapping** (model labels → app categories, consumed by `FlagGating`):
  - EN: `profanity = max(toxic, severe_toxic, obscene)`, `violence = threat`,
    `bullying = max(insult, identity_hate)`.
  - AR: `bullying = Hate` (HIGH severity). Arabic category granularity comes from the Stage-1 regex.
- **Flag threshold:** a mapped category score **≥ 0.5**.
- **`sexual` and `self_harm` are intentionally NOT model outputs** — the ML is blind to their polite
  phrasing, so the Stage-1 regex owns them. Don't invent model labels for them.
- **Single-resident:** only one text model is held in memory at a time; switching script evicts the
  other (the two are large).

## Image classification (NSFW)

| | Value |
|---|---|
| Model file | `nsfw_classifier.tflite` (bundled, GantMan MobileNetV2 NSFW) |
| Input | `[1, 224, 224, 3]` float, RGB, **normalized ÷255 → [0, 1]** (NOT [-1, 1]) |
| Output | `[1, 5]` float softmax |
| Labels (in order) | `drawings, hentai, neutral, porn, sexy` |
| Flag rule | `nsfwProb = hentai + porn + sexy`; flag when **≥ 0.6** (summed) |

- Safe indices are `drawings` (0) and `neutral` (2). Very small images are skipped (a
  `MIN_IMAGE_DIMENSION` guard — upscaling tiny thumbnails to 224² was a false-positive source).
- NSFW runs only on **saved** images (Downloads/Screenshots via `MediaFileObserver`); there is no
  live screen-frame scanning (deferred for app-store compliance).

---

## Model delivery (on-demand download)

The two text `.tflite` blobs are **large and NOT bundled** in the APK. They are downloaded on demand
(`ml/download/ModelDownloader.kt`) to `filesDir/models/` and **verified before use** against a pinned
byte size **and** SHA-256; a mismatch is discarded. The small vocab `.txt` files and the NSFW model
**are** bundled in `assets/`.

| Artifact | Size (bytes) | SHA-256 |
|---|---|---|
| `toxicbert_en_int8.tflite` | 112,174,496 | `fdbb91b03f48fd24f6967573f2d413c2784c0cfc54d91e337bf1f81599ceba11` |
| `marbert_ar_int8.tflite` | 166,364,192 | `ec64837e1b699d695f29dd1d42efe8bc2da7c308d3ce6f9a276f65804b8d6d89` |

The blobs are served by the backend at `GET /api/v1/models/{filename}` (public, no auth; the SHA-256
is returned as the `ETag`). For local testing before wiring the downloader, push them manually:

```
adb push toxicbert_en_int8.tflite /data/data/<pkg>/files/models/
adb push marbert_ar_int8.tflite   /data/data/<pkg>/files/models/
```

## Fallback behavior (degrade, never crash)

The app keeps working when a model can't run:

1. **Text:** if a backend's model file is absent, or free heap < 64 MB, or native load fails, Stage 2
   returns "safe" for that string and detection rests on the **Stage-1 regex**. (Genuine absence also
   signals the app to fetch the model.)
2. **Image:** if `nsfw_classifier.tflite` can't load, the classifier **fails open** (returns safe).
   There is deliberately **no** skin-tone heuristic — a skin-ratio test can't tell nudity from a face
   and fabricated false positives, so it was removed.

## Notes

- Models are loaded lazily; thread count is capped at 2 for battery.
- Stage 1 (regex) catches the bulk of obvious inappropriate content without any model present, so the
  app is protective even before the text models finish downloading.
