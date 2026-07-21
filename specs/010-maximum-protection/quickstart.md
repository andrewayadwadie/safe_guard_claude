# Quickstart Validation: Maximum Protection

**Feature**: 010-maximum-protection — validates spec.md success criteria SC-001…SC-006.

## Prerequisites

- Physical Android device or emulator, API 26+, with a few images in `Pictures/` or `Download/`.
- App set up as a **CHILD** device: logged in as child, device registered, monitoring consent granted (image pipeline gates on `shouldRunMonitoring` — see `PreferencesManager`).
- At least one image the classifier flags. For deterministic testing, temporarily lower `Constants.NSFW_CONFIDENCE_THRESHOLD` or use a known-flagged test asset (revert before commit).
- Build & install: `./gradlew assembleDebug` then `adb install -r app/build/outputs/apk/debug/app-debug.apk`
- Watch logs: `adb logcat -s ImageScanWorker MediaFileObserver ImageBlurManager SafeGuardSettings`

Contracts referenced: [contracts/internal-contracts.md](contracts/internal-contracts.md) · Data model: [data-model.md](data-model.md)

## Scenario 1 — PIN gate on toggle (SC-001, US1)

1. Child device → Settings → Parent Review section → verify "Maximum Protection" row exists, toggle OFF.
2. Tap toggle. If no PIN exists: create-PIN dialog appears (enter + confirm) — set one; change applies after creation. Log out/in to reset if you need the no-PIN case again.
3. Tap toggle → enter WRONG PIN → expect inline error, toggle stays OFF after dismiss.
4. Tap toggle → CANCEL dialog → toggle stays OFF.
5. Tap toggle → correct PIN → toggle ON. Kill + reopen app → still ON (persisted).
6. Repeat 3–5 in the OFF direction (both directions gated).

**Pass**: no path changes the toggle without a correct PIN.

## Scenario 2 — Violation with protection OFF = copy-only (SC-003, US3)

1. Ensure toggle OFF (default on fresh install).
2. Push a flagged image: `adb push flagged.jpg /sdcard/Download/` (MediaFileObserver picks it up; or trigger the worker per Scenario 3).
3. Expect log: `backupOnly` path (no blur), alert sent.
4. Open gallery → image **unaltered** (no blur, no "Protected by Haris" banner).
5. Settings → Parent Review → Review Images (PIN) → entry **present**.
6. Verify metadata: `adb shell run-as com.safeguard.parentalcontrol cat files/image_backup/<id>.meta` → contains `blurApplied=false`.
7. Parent app / backend: `inappropriate_image` alert received (metadata only).

**Pass**: gallery untouched + copy present + alert sent.

## Scenario 3 — Violation with protection ON = blur + copy (SC-002, US2)

1. Toggle ON (correct PIN).
2. Push a *different* flagged image (different bytes ⇒ different path/hash).
3. Trigger periodic path explicitly to cover the worker:
   `adb shell am broadcast` is not needed — use WorkManager test trigger: `adb shell cmd jobscheduler run -f com.safeguard.parentalcontrol <job-id>` or simply wait ≤30 min / reinstall to re-enqueue; observer path fires immediately on push.
4. Gallery → image is **blurred** with banner; review area has the entry; `.meta` shows `blurApplied=true`; alert received.

**Pass**: identical to pre-feature behavior.

## Scenario 4 — Retroactive blur on OFF→ON (SC-006, FR-013/014)

1. With toggle OFF, flag an image (Scenario 2 state: copy-only entry, gallery clean).
2. Toggle ON with correct PIN.
3. Expect log: `Retroactively blurred 1 image(s)` (immediate trigger from SettingsViewModel).
4. Gallery → that image now blurred. `.meta` now `blurApplied=true`.
5. Verify **no second backup** (`run-as … ls files/image_backup` — one pair per image) and **no duplicate alert** on the parent side.
6. Edge: repeat 1, then delete the gallery file before toggling ON → toggle ON → pass skips it without error; review entry remains.

**Pass**: previously copied images get blurred, exactly once, no new copies/alerts.

## Scenario 5 — ON→OFF does not restore (FR-015)

1. With blurred images present (Scenario 3/4), toggle OFF (PIN).
2. Gallery → images **stay blurred**.
3. Review Images → Restore one → original returns (existing flow intact).

**Pass**: no mass-restore; per-image restore still works.

## Scenario 6 — Fresh read mid-batch (SC-004)

1. Toggle OFF. Push 2+ flagged images seconds apart while flipping toggle ON (PIN) between pushes.
2. Expect: image processed before the change → copy-only; image after → blurred (then retro pass blurs the earlier one).

**Pass**: each violation follows the value at its processing moment.

## Scenario 7 — Regression sweep (SC-005)

- Legacy compatibility: an entry created on a pre-feature build (or hand-edit a `.meta` to remove the `blurApplied` line) → appears in review; restore works; retro pass does NOT touch it (missing key ⇒ `blurApplied=true`).
- Text review, content-filtering toggle, language switch, logout/login: unchanged.
- Logout → login: Maximum Protection back to OFF (prefs cleared).

## Unit tests

`./gradlew testDebugUnitTest` — new tests must cover: metadata round-trip with `blurApplied` true/false, and missing-key ⇒ `true` legacy default.
