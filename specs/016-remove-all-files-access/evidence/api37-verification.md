# Device evidence — API 37 (`emulator-5556`, `test_device`)

**Recorded**: 2026-10-07 | Partial coverage of T006 and T007

This is the only device rung available on this machine. It is **one above `targetSdk`** (API 37 vs 36),
so it exercises the Android 13+ photo-permission path and the Android 14+ partial-selection path, and
covers none of API 26–33. See [devices.md](./devices.md).

## What this rung established

### Audit Finding 1 — confirmed, not inferred

On the child-role device, **before** any change:

```text
Uid mode: MANAGE_EXTERNAL_STORAGE: allow
MANAGE_EXTERNAL_STORAGE: default

runtime permissions:
  android.permission.READ_MEDIA_IMAGES:               granted=false
  android.permission.READ_MEDIA_VISUAL_USER_SELECTED: granted=false
  android.permission.READ_PHONE_STATE:                granted=true
  android.permission.POST_NOTIFICATIONS:              granted=true
```

`READ_MEDIA_IMAGES` is declared and **ungranted** on a device that has completed setup. Every image
read was authorised by the UID-level all-files-access grant alone. This is the finding that made FR-003
load-bearing rather than cleanup, and it is now a measurement rather than a reading of the code.

### R-006 — the partial-grant signal is observable

`READ_MEDIA_VISUAL_USER_SELECTED` appears in the package's runtime-permission list **even though the
app does not declare it** (absent from the merged manifest — see
[baseline-manifest.md](./baseline-manifest.md)). The platform tracks it for any app holding the photo
permission, so `checkSelfPermission` on it is a usable signal for "the user chose Select photos".

That is what `PermissionsSetupViewModel.mediaReadAccess()` now reads, and it is why R-006's decision
not to declare the permission holds: the app can detect a partial grant in order to reject it without
asking for the ability to enumerate one.

### Target state applied

```powershell
adb -s emulator-5556 shell appops set com.safeguard.parentalcontrol MANAGE_EXTERNAL_STORAGE deny
adb -s emulator-5556 shell appops set --uid com.safeguard.parentalcontrol MANAGE_EXTERNAL_STORAGE deny
adb -s emulator-5556 shell pm grant com.safeguard.parentalcontrol android.permission.READ_MEDIA_IMAGES
```

Verified afterwards: `Uid mode: MANAGE_EXTERNAL_STORAGE: deny`, package mode `deny`,
`READ_MEDIA_IMAGES: granted=true`. Note that setting the **package** mode alone was not enough — the
UID mode still read `allow`, which would have left `isExternalStorageManager()` returning true and
produced a false pass. Both must be set.

### Post-change install — the removal verified on a device

A clean (non-instrumented) debug build of this feature was installed with `./gradlew installDebug`
onto `child_phone` (API 37) — `Installed on 1 device. BUILD SUCCESSFUL`. The previous
Studio-instrumented APK that ANR'd on launch is gone.

`adb shell dumpsys package com.safeguard.parentalcontrol`:

```text
versionCode=11 minSdk=26 targetSdk=36
  android.permission.READ_MEDIA_IMAGES
    android.permission.READ_MEDIA_IMAGES: granted=false, flags=[ USER_SENSITIVE_WHEN_GRANTED|…]
```

**`MANAGE_EXTERNAL_STORAGE` no longer appears in the package's permission list at all** — not as
granted, not as denied, not as a declared-but-ungranted entry. Compare against the pre-change
listing above, where it was `Uid mode: allow`. That is the removal verified against an installed
APK rather than against a manifest file. `READ_EXTERNAL_STORAGE` is correctly absent too, since its
`maxSdkVersion` is 32 and this device is API 37.

`READ_MEDIA_IMAGES` reading `granted=false` on a fresh install is the **correct** state now: it is a
runtime permission the setup flow asks for, which is the whole point of FR-003.

Launch smoke test: `monkey -p … -c android.intent.category.LAUNCHER 1`, then 12s of logcat filtered
for `ANR in com.safeguard`, `FATAL EXCEPTION`, `AndroidRuntime.*com.safeguard` → **none**, process
alive (`pidof` → 7109). The app starts and stays up with no all-files access and no photo permission
granted, which is the baseline the new setup flow has to work from.

## What this rung did NOT establish

**T006 is still not closed.** The remaining blocker is the test fixture, not the build. The full gate requires a flagged image to be detected, backed up and alerted
on with only the narrow permission held. Two things blocked it:

1. ~~**No clean build of this feature is installed.**~~ **Cleared.** At the time of the first attempt
   the APK on the device was an Android-Studio-instrumented build (`code_cache/startup_agents/*.so`,
   `code_cache/.studio/instruments-*.jar`) which ANR'd on launch:
   `ActivityManager: ANR in com.safeguard.parentalcontrol / Reason: Process … failed to complete
   startup`. An instrumentation artefact rather than a finding about this feature, but it meant no
   observation through the app could be trusted. A clean build is now installed and launches cleanly
   — see "Post-change install" above.
2. **No flagged test image.** A benign generated JPEG was pushed to `/sdcard/Pictures/` and registered
   with `content call --uri content://media/external --method scan_file`, which exercises the trigger
   but cannot exercise backup or alerting, since neither happens for an unflagged image. A fixture the
   bundled classifier flags is required, from feature 010's test material.

**T007 is not closed.** The partial-selection *signal* is confirmed available (above), but the actual
Android 14+ behaviour — what the platform reports when the user picks "Select photos" in an app that
does not declare the user-selected permission — was not driven through the UI, for the same reason.

**Also unobserved on any rung**: scenarios 2 (latency and gallery refresh), 4 (grant persistence
across reboot and update), 5 (ungranted and `Download` folders), 6 (induced mid-write failure), 7
(Android 8–10 writes), 9 (upgrade) and 10 (network capture).

## Next steps to close the device gates

1. ~~`./gradlew installDebug` to put a clean, non-instrumented build of this feature on the device.~~
   **Done** — see "Post-change install" above. Blocker 1 is cleared.
2. Pair the child device (needs a reachable backend for `POST /devices` plus in-app consent).
3. Obtain the feature-010 flagged test fixture, or drive detection through the classifier's regex
   stage in a debug build and record which method was used.
4. Install an API 30 image and an API 26–29 image, which are what SC-002's per-rung claim and SC-004
   actually depend on.
