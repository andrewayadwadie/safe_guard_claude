# Baseline: pre-change permission state — feature 016

**Recorded**: 2026-10-07 | Task T003 | "Before" half of SC-001

## Merged release manifest, before any change

Source: `app/build/intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml`
(produced by the last release build on this tree, at `versionCode 10`).

```text
48:    <uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />
50:        android:name="android.permission.READ_EXTERNAL_STORAGE"        (maxSdkVersion="32")
60:    <uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE" />
```

`grep -c MANAGE_EXTERNAL_STORAGE` → **1**. SC-001 requires this to become **0**.

### Absent from the merged manifest, confirming the spec's Audit Finding 2

```text
WRITE_EXTERNAL_STORAGE              — not present
requestLegacyExternalStorage        — not present
preserveLegacyExternalStorage       — not present
MANAGE_MEDIA                        — not present
ACCESS_MEDIA_LOCATION               — not present
READ_MEDIA_VISUAL_USER_SELECTED     — not present
```

With no legacy write permission and no legacy-storage opt-in, and with all-files access existing only
from API 30, the app has **never** been able to write a shared-storage image on API 26–29. Audit
Finding 2 holds against the shipped artefact, not just the source.

## Audit Finding 1 — confirmed on hardware

`emulator-5556` (`test_device`, API 37), the child-role device, **before** any change by this
feature:

```text
Uid mode: MANAGE_EXTERNAL_STORAGE: allow
MANAGE_EXTERNAL_STORAGE: default

runtime permissions:
  android.permission.READ_MEDIA_IMAGES:              granted=false
  android.permission.READ_MEDIA_VISUAL_USER_SELECTED: granted=false
  android.permission.READ_PHONE_STATE:                granted=true
  android.permission.POST_NOTIFICATIONS:              granted=true
```

**`READ_MEDIA_IMAGES` is declared in the manifest and `granted=false` on a fully set-up child
device.** Every image read on this device today is authorised solely by the UID-level all-files-access
grant. This is the spec's Audit Finding 1, observed rather than inferred, and it is why FR-003 (add
the runtime request that was never made) is the load-bearing half of this feature rather than
cleanup.

## Incidental finding for research R-006

`READ_MEDIA_VISUAL_USER_SELECTED` appears in the package's runtime-permission list on API 37 even
though it is **not declared** in the merged manifest. The platform tracks it implicitly for any app
holding `READ_MEDIA_IMAGES`, so a partial grant is observable through the permission state without
the app declaring that permission. This supports R-006's decision not to declare it, and means the
T007 probe has a signal to read.
