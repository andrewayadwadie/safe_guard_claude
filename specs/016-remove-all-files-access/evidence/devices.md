# Device Inventory — feature 016

**Recorded**: 2026-10-07 | Task T002

## Available

| Serial | AVD | API | Android | Role | App installed |
| --- | --- | --- | --- | --- | --- |
| `emulator-5554` | `Parent_Phone` | 37 | 17 | parent | yes |
| `emulator-5556` | `test_device` | 37 | 17 | **child** (holds all-files access) | yes |

A third AVD exists but was not running: `child_phone` (`target=android-37.0`).

`adb` is **not on PATH**. Full path:
`C:\Users\Dell\AppData\Local\Android\Sdk\platform-tools\adb.exe`

Git Bash mangles `/sdcard/...` arguments through MSYS path conversion
(`ls: C:/Program: No such file or directory`). **Run all `adb shell` commands with device paths from
PowerShell**, or set `MSYS_NO_PATHCONV=1`.

## Quickstart matrix coverage

| Rung | API | Covered | Note |
| --- | --- | --- | --- |
| Android 8.0/8.1 | 26–27 | **NO** | No image installed. Carried over from feature 015 T029 — every local AVD reports API 37 |
| Android 10 | 29 | **NO** | Same |
| Android 11 | 30 | **NO** | Same |
| Android 13 | 33 | **NO** | Same |
| Android 14+ | 34+ | **partially** — API 37 exercises the same partial-selection behaviour, but is not literally 34 | |
| Android 16 | 36 | **NO** — `targetSdk` is 36 but no API 36 image is installed | Both running emulators are API 37, i.e. one above target |

**Consequence**: SC-004 (Android 8–10 writes, T005/T035) and SC-002's per-rung claim (T016) cannot be
closed on this machine. They are **device-blocked**, not passed. An API 26–29 image and an API 30
image must be installed, or a physical device of each used, before this feature's merge gates can be
signed off.

## Emulator state changed by verification

`emulator-5556` had all-files access granted at both UID and package level. T006 revoked both:

```powershell
adb -s emulator-5556 shell appops set com.safeguard.parentalcontrol MANAGE_EXTERNAL_STORAGE deny
adb -s emulator-5556 shell appops set --uid com.safeguard.parentalcontrol MANAGE_EXTERNAL_STORAGE deny
adb -s emulator-5556 shell pm grant com.safeguard.parentalcontrol android.permission.READ_MEDIA_IMAGES
```

This is left in place deliberately — it is the post-feature target state. To restore the
pre-feature state for a comparison run, replace `deny` with `allow` and
`pm grant` with `pm revoke`.
