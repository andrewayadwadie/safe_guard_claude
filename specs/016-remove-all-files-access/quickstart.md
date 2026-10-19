# Quickstart & Verification Matrix: Replace All-Files Access with Parent-Granted Folder Access

**Feature**: `016-remove-all-files-access` | **Date**: 2026-10-07 | **Plan**: [plan.md](./plan.md)

This is the validation guide, not an implementation guide. Five scenarios are **merge gates** and
must be recorded as evidence before the feature lands. Feature 015 established the
written-evidence convention and the practice of annotating tasks that no available device can
verify; both carry over.

---

## Prerequisites

```bash
# From the repository root. Shell is PowerShell; these run in the Bash tool too.
./gradlew assembleDebug                 # must be clean before starting
./gradlew testDebugUnitTest             # existing suite green (2 known WordPiece CRLF failures)
```

The two `WordPieceTokenizerTest` failures on this machine are a line-ending artefact, not a
regression — leave them be.

A test image that the bundled classifier reliably flags is required. Reuse whatever feature 010
(`maximum-protection`) used; do not create new explicit material. If no such fixture exists, drive
detection with the classifier's regex stage or a threshold override in a debug build and record
which method was used — a synthetic trigger is acceptable evidence for a *storage* feature.

### Device matrix

| Android | API | Why this rung matters |
| --- | --- | --- |
| 8.0 / 8.1 | 26–27 | `minSdk`. Writes have **never** worked here (Audit Finding 2); folder grants should fix them |
| 10 | 29 | Last version before scoped storage is mandatory; file-watch trigger path |
| 11 | 30 | Scoped storage; where all-files access exists today and the read path changes |
| 13 | 33 | Narrow photo permission proper |
| 14+ | 34+ | Partial-selection prompt (R-006) |
| 16 | 36 | `targetSdk`; the submitted configuration |

**Minimum viable set if hardware is short**: one pre-11 device (26 or 29), API 30, and API 34+. Any
rung not covered must be annotated as device-blocked in `tasks.md` with the reason, exactly as
feature 015 did — never silently skipped.

---

## Scenario 1 — The read path survives on the narrow permission ⟵ **GATE, run first**

Proves research R-004 on real hardware. **Nothing else in this feature may be trusted until this
passes**, and the permission must not be deleted before it does: a build with the permission gone
and the read path broken is a build where protection is silently off.

```bash
# Grant ONLY the narrow photo permission; confirm all-files access is not held.
adb shell pm grant com.safeguard.parentalcontrol android.permission.READ_MEDIA_IMAGES
adb shell appops get com.safeguard.parentalcontrol MANAGE_EXTERNAL_STORAGE      # expect: deny / default

# Place a test image and make the media database aware of it.
adb push flagged-test.jpg /sdcard/DCIM/Camera/
adb shell content call --uri content://media/external --method scan_file \
  --arg /sdcard/DCIM/Camera/flagged-test.jpg
```

**Expect**: the periodic scan finds the image, the classifier decodes it, an app-private backup
appears, one parent alert is raised. On API ≤ 32 grant the legacy read permission instead.

**Evidence**: the logcat lines showing decode success and alert creation, plus the `appops` output
proving all-files access was not held. Record per API level.

---

## Scenario 2 — Blur latency and gallery refresh ⟵ **GATE**

Proves R-005 (trigger latency) and R-009 (gallery refresh), and guards SC-003.

Measure the **current** build first, on the same device with the same image, so the comparison is
real rather than remembered:

```bash
# Baseline on the pre-change build, then repeat on the new build.
adb logcat -c
adb push flagged-test.jpg /sdcard/DCIM/Camera/
adb shell content call --uri content://media/external --method scan_file \
  --arg /sdcard/DCIM/Camera/flagged-test.jpg
adb logcat -d -v time | grep -E "MediaFileObserver|ImageBlurManager"
```

**Expect**: with Maximum Protection on and the camera folder granted, the gallery file is replaced
and the **gallery thumbnail** shows blurred — check the thumbnail, not just the bytes, because an
unrefreshed thumbnail reads to the parent as total failure. New-build latency must be no worse than
baseline.

**Evidence**: both latency figures, and a screenshot of the gallery thumbnail.

---

## Scenario 3 — Partial photo access is refused

Proves R-006, the one documentation question left open. API 34+ only.

Revoke photo access, relaunch the setup flow, and choose **Select photos** rather than Allow all.

**Expect**: image protection shows **not active** with the explanation that a hand-picked selection
cannot contain images that arrive later, and the only remedy offered is to ask again for full
access (FR-005).

**Watch for**: the platform may report the narrow permission as *granted* for backward
compatibility, since the app does not declare the user-selected-visual permission. If it does, the
positive permission check alone is insufficient and the grant check must fall through to a
capability probe. **Record which behaviour the device actually shows** — this determines whether
the implementation needs that probe.

---

## Scenario 4 — Grants persist, and revocation degrades honestly

Covers FR-009, FR-021, SC-006.

1. Grant the camera and pictures folders. Confirm blurring works.
2. Reboot. Repeat — **no new prompt**, blurring still works. Do this three times (SC-006).
3. Install an app update over the top. Repeat — no new prompt.
4. Revoke the folder access from system settings, then flag another image.

**Expect after step 4**: detection, backup and the parent alert all continue; the backup records a
not-blurred reason; the status surface shows blurring inactive with the reason and a remedy; the
parent is prompted to restore access. No crash (FR-021, SC-008).

5. Uninstall and reinstall. Grants are gone — expected and documented, not a bug.

---

## Scenario 5 — Ungranted and ungrantable folders

Covers FR-016, FR-022, and the spec's accepted `Download` regression.

| Placement | Expect |
| --- | --- |
| Image in `Pictures` with only `DCIM` granted | Detected, backed up, alerted; **not** blurred; reason recorded; the remaining folder is re-offerable without restarting setup (FR-011) |
| Image in `Download` | Detected, backed up, alerted; **never** blurred; the parent sees the one-time notice **once**, not on every alert (FR-022) |
| Image in a folder nobody watches | Nothing happens — unchanged from today, out of scope |

**Also confirm**: no flagged image is ever reported to the parent as blurred when it was not
(SC-007). This is the honesty requirement and it is checked here deliberately, not inferred.

---

## Scenario 6 — A failed write must not destroy the photo ⟵ **GATE**

The single scenario where a wrong implementation causes data loss. Proves R-008 and the ordering in
[contracts/media-write-access.md](./contracts/media-write-access.md).

Induce failure three ways, one at a time:

1. Fill the volume so the write cannot complete.
2. Revoke the folder grant between detection and the write (narrow timing window — retry, or use a
   debug hook that pauses between steps 3 and 4 of the contract's sequence).
3. Delete the original file between detection and the write.

**Expect in every case**: the original image is **intact and viewable** afterwards, or restored from
the app-private backup; never truncated, never zero-byte, never corrupt. The failure is reported to
the parent with a reason. The backup is not marked blurred.

**Evidence**: file size and a successful decode of the original after each induced failure.

---

## Scenario 7 — Android 8–10 regain the writes ⟵ **GATE**

Proves SC-004, the capability this feature *adds*.

On an API 26–29 device: grant folder access, flag an image in a granted folder, and confirm the
in-place blur succeeds. Then confirm restore and delete from the parent review screen.

**Expect**: all three succeed. On the pre-change build all three fail on these versions — capture
that baseline failure first, because the before-and-after pair is the evidence, not the after alone.

---

## Scenario 8 — The permission is gone and cannot return ⟵ **GATE**

Covers SC-001 and SC-009; acceptance tests in
[contracts/build-guard.md](./contracts/build-guard.md).

```bash
./gradlew bundleRelease

# SC-001: the shipped artefact's manifest, not the hand-written one.
grep -c MANAGE_EXTERNAL_STORAGE \
  app/build/intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml
# expect: 0  (grep exits 1 — that is the pass)

# SC-009: re-add the permission in a scratch commit, then
./gradlew assembleDebug     # must FAIL, naming the permission and the policy reason
# revert, then confirm the build passes again
```

Also run build-guard acceptance tests AT-3 (a comment mentioning the permission must not fail the
build) and AT-4 (feature 015's foreground-service check still fires).

---

## Scenario 9 — Upgrade from the all-files-access build

Covers FR-023, FR-024.

Install the current release build, grant all-files access, flag and blur an image, then install the
new build over the top **without clearing data**.

**Expect**: every app-private backup survives; the previously blurred image can be restored or
deleted once folder access is granted; the parent is told image protection needs attention rather
than being left with a device that looks protected and reads nothing (FR-024).

---

## Scenario 10 — Privacy invariant

Covers SC-010 and Principle I.

Capture network traffic across a full detect → blur → review cycle.

**Expect**: category, confidence and alert metadata only. **No image bytes, no file paths.** The
alert payload is unchanged by this feature, so any difference from the current build is a defect.

---

## Recording evidence

One file per rung, in this folder, matching feature 015's convention:

```text
specs/016-remove-all-files-access/evidence/
├── api26-verification.md
├── api30-verification.md
└── api34-verification.md
```

Each entry records: scenario number, device and API level, the command run, the observed output
(logcat line, `appops` output, file size, screenshot filename), and pass/fail. A scenario no
available device can run is annotated **device-blocked** with the reason, never marked passed.

## Merge gates

| Gate | Scenario | Criterion |
| --- | --- | --- |
| Read path works on the narrow permission | 1 | SC-002 |
| No latency regression, gallery refreshes | 2 | SC-003 |
| Failed write leaves the original intact | 6 | FR-018, SC-007 |
| Android 8–10 writes now work | 7 | SC-004 |
| Permission gone and guarded | 8 | SC-001, SC-009 |

SC-011 — the store review proceeding with no all-files-access form — is **post-merge confirmation**
and does not gate the merge, since it depends on the store's own review timing.
