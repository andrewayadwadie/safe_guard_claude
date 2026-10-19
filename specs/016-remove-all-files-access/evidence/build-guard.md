# Evidence: build-guard acceptance tests — feature 016

**Recorded**: 2026-10-07 | Tasks T014, T041, T042 | Contract: [../contracts/build-guard.md](../contracts/build-guard.md)

The guard was **extended, not duplicated**: feature 015's `process(Debug|Release)MainManifest` block in
`app/build.gradle` now compares each declared permission against a banned-permission map carrying its
own policy reason, printed on failure.

## Results

| # | Test | Result | Observed |
| --- | --- | --- | --- |
| **AT-1** | Clean tree builds and the guard actually inspects the manifest | ✅ PASS | `assembleDebug`, `processDebugMainManifest` and `bundleRelease` all succeed |
| **AT-2** | Re-adding `MANAGE_EXTERNAL_STORAGE` fails the build | ✅ PASS | see below |
| **AT-3** | A comment mentioning the permission does not fail the build | ✅ PASS | see below |
| **AT-4** | Feature 015's `FOREGROUND_SERVICE_DATA_SYNC` check still fires | ✅ PASS | covered by AT-5, which shows it reported alongside the new one |
| **AT-5** | Both banned permissions are reported, not just the first | ✅ PASS | see below |
| **AT-6** | A missing merged manifest fails rather than passing | ⚠️ **NOT EXERCISED** | the failure branch is unchanged from feature 015 and could not be induced without modifying the guard itself, which would have tested the modification rather than the guard |

## AT-2 — the permission reintroduced

Added `<uses-permission android:name="android.permission.MANEXT..." />` back to
`app/src/main/AndroidManifest.xml`, then ran `./gradlew processDebugMainManifest`:

```text
Build blocked - banned manifest declaration present:
  * android.permission.MANAGE_EXTERNAL_STORAGE is present in the merged manifest (AndroidManifest.xml)
    Google Play restricts all-files access to file managers, backup/restore,
    anti-virus, document management, on-device file search, disk encryption and
    device migration. Parental control is NOT a permitted use, so declaring it
    blocks the release review. Photos are read with READ_MEDIA_IMAGES; flagged
    images are modified through parent-granted folder access (MediaWriteAccess).
    See specs/016-remove-all-files-access/contracts/build-guard.md

  Origin: see app/build/outputs/logs/manifest-merger-debug-report.txt
BUILD FAILED in 9s
```

The manifest was then restored and the build passed again. This is **SC-009**.

## AT-3 — the documentation does not trip the guard

The app manifest carries a comment block explaining that all-files access is deliberately absent and
must not return. That comment survives into the merged manifest:

```text
app/build/intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml
  53:    <!--
  54:         All-files access (MANAGE_EXTERNAL_STORAGE) is deliberately NOT declared and must never be
  55:         reintroduced.
```

`grep -c MANAGE_EXTERNAL_STORAGE` on that file returns **1**, while
`grep -c '<uses-permission.*MANAGE_EXTERNAL_STORAGE'` returns **0** — and the build passes. Comments
are stripped before matching, so the human guardrail does not break the machine one. A substring
search would have failed the build on our own prose.

## AT-5 — both banned permissions at once

```text
Build blocked - banned manifest declaration present:
  * android.permission.MANAGE_EXTERNAL_STORAGE is present in the merged manifest (AndroidManifest.xml)
    ...
  * android.permission.FOREGROUND_SERVICE_DATA_SYNC is present in the merged manifest (AndroidManifest.xml)
    A dataSync foreground service is capped at ~6 cumulative hours per 24 on
    API 35+ and cannot be started from BOOT_COMPLETED...
BUILD FAILED in 7s
```

Both are listed, each with its own reason. The extension did not weaken feature 015's check (AT-4).

## T014 / SC-001 — the shipped bundle

`./gradlew bundleRelease` → **BUILD SUCCESSFUL in 14m 12s**, `app-release.aab` produced
(57,412,091 bytes), `versionCode 11` / `versionName 1.2.2`.

Merged release manifest
(`app/build/intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml`):

```text
grep -c '<uses-permission[^>]*MANAGE_EXTERNAL_STORAGE'  →  0     ✅ SC-001
line 48: <uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />
line 50: <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE"
line 51:     android:maxSdkVersion="32" />
```

The before/after pair against [baseline-manifest.md](./baseline-manifest.md): **1 → 0**. The legacy
read permission is retained for API ≤ 32, as FR-003 requires, and no new permission was added.
