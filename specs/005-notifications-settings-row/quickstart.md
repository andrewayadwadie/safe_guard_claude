# Quickstart Validation: Notifications Permission Row

**Feature**: 005-notifications-settings-row

## Prerequisites

- JDK 21, Android SDK (compileSdk 35)
- Physical device or emulator, ideally API 33+ (below 33 the state reads "On" unless app notifications are disabled in system settings; helper semantics)
- App installed and logged in (either role — row shows for both)

## Build checkpoint

```powershell
./gradlew assembleDebug
```

Expected: `BUILD SUCCESSFUL`, zero new warnings from the two modified files.

## Install & launch

```powershell
./gradlew installDebug
adb shell monkey -p com.safeguard.parentalcontrol 1
```

## Validation scenarios

### S1 — State shown correctly on open (spec SC-001, FR-002/003)

1. In device Settings → Apps → SafeGuard → Notifications: DISABLE notifications.
2. Open app → Settings screen → "Notifications" section.
3. Expect row: subtitle **"Off — tap to enable"**, trailing **"Off"** in error color.
4. Re-enable in system settings, kill + reopen app.
5. Expect subtitle **"On"**, trailing **"On"** in primary color.

### S2 — Tap opens app-scoped system settings (SC-002, FR-004)

1. Tap the "Notifications" row.
2. Expect: system notification settings **for this app** opens in one tap.

### S3 — Resume re-check (SC-003, FR-005)

1. From the row, tap → toggle the permission in system settings.
2. Press back to return to the app (do NOT kill it).
3. Expect: row reflects the new state immediately on resume, no manual refresh.
4. Repeat without changing anything — state remains correct (idempotent).

### S4 — Graceful failure (SC-004, FR-007)

Hard to force on stock devices. Verify by code inspection: `startActivity` wrapped in try/catch, `Timber.e(e, "Failed to open notification settings")` in catch. Optional check:

```powershell
adb logcat -s Timber
```

### S5 — Regression guard

1. Existing "Push Notifications" row still renders below the new row ("Coming soon").
2. Content-filtering resume refresh still works (toggle VPN state, resume screen).
3. Confirm diff touches ONLY `SettingsViewModel.kt` and `SettingsScreen.kt`:

```powershell
git diff --name-only
```

## References

- Contract: [contracts/ui-contract.md](./contracts/ui-contract.md)
- State field: [data-model.md](./data-model.md)
- Pattern decisions: [research.md](./research.md)
