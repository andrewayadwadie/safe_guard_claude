# Quickstart: Validating Arabic & English Localization

**Feature**: 008-arabic-english-localization

## Prerequisites

- Android device/emulator API 26+ connected (`adb devices`)
- JDK 21; repo root = `safeguard-android-NOOR`

## Build & install

```powershell
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Automated checks

```powershell
# EN/AR key + placeholder parity (SC-006) — must exit 0
python tools/check_strings_parity.py

# LocaleHelper unit tests
./gradlew testDebugUnitTest --tests "*LocaleHelper*"
```

## Manual validation

### 1. Toggle & RTL (US1, SC-002/003/004)

1. Launch app (English default on non-Arabic device). Settings → "Language" row shows "English".
2. Tap row → dialog with radio options English / العربية → pick العربية.
3. Expect: screen re-renders in Arabic ≤ 2 s; layout RTL (titles right-aligned, back arrow mirrored & on the right, list chevrons flipped).
4. Force-close app, relaunch → still Arabic RTL (persistence).
5. Reboot device, relaunch → still Arabic.
6. Switch back to English → LTR restored.

### 2. Full-screen sweep (US2, SC-001/005)

In Arabic mode, walk: splash → login → consent → dashboard → children → device setup → link parent → alerts → screen-time → content filter/blacklist/wordlist → image/text review → lock screen → settings → change password → legal docs (privacy + terms).
Check per screen: zero English app-authored text; no truncation/overlap; child/device names verbatim; all digits Western (0-9); logo NOT mirrored.
Repeat sweep in English: zero Arabic remnants, layout identical to pre-feature.

### 3. Notifications (US3)

1. Arabic selected → trigger monitoring notification (start monitoring service) and a tamper alert (toggle VPN off, or force-stop path).
2. Expect notification title/body in Arabic, Western digits.
3. Switch to English → next notification English.

### 4. Edge checks

- Arabic-system-language device, fresh install (or `adb shell pm clear` + set device to Arabic): first launch opens in Arabic (FR-007).
- Mid-flow switch: change language while on device-setup screen → screen re-renders, entered data intact.
- Legal doc screen opens site in current app language (best-effort).

## Expected outcome

All checks above pass; `assembleDebug` clean; parity script exit 0. Contracts: [contracts/locale-contract.md](./contracts/locale-contract.md). Entities: [data-model.md](./data-model.md).
