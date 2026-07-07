# Quickstart Validation: Change Password Flow

**Feature**: 006-change-password-flow

## Prerequisites

- JDK 21, Android SDK (compileSdk 35)
- Device/emulator, app installed, signed in with a password-based account (email+password login; Google-only accounts may be rejected server-side — that path validates S4 error handling)
- Backend reachable (`https://bw.noor.net:8090/api/v1`)

## Build checkpoint

```powershell
./gradlew assembleDebug
```

Expected: `BUILD SUCCESSFUL`, no new warnings from the four touched files.

## Install & launch

```powershell
./gradlew installDebug
adb shell monkey -p com.safeguard.parentalcontrol 1
```

## Validation scenarios

### S1 — Row navigates (FR-001, SC-001)

1. Settings → Account section → "Change Password" row: "Coming soon" label GONE.
2. Tap row → Change Password screen opens: TopAppBar title, two password fields, submit button.
3. Tap back arrow → returns to Settings, nothing changed.

### S2 — Client validation blocks bad input (FR-003, SC-002)

With airplane mode ON (proves no network call):

1. Submit with any field blank → button disabled (blank gate) — no request possible.
2. Enter current="whatever", new="short" (<8) → submit → Snackbar "New password must be at least 8 characters".
3. Enter same value in both fields (≥8 chars) → submit → Snackbar "New password must be different from current password".
4. Airplane mode OFF afterward.

### S3 — Happy path (FR-004, FR-005, SC-001, SC-004)

1. Enter correct current password + valid new password (≥8, different).
2. Tap submit → button disables, spinner shows.
3. Expect: automatic return to Settings, still signed in (no logout).
4. Log out, sign back in with NEW password → succeeds. (Restore original password afterward if needed.)

### S4 — Server error surfaced (FR-006, SC-003)

1. Enter WRONG current password + valid new password.
2. Submit → Snackbar shows server failure reason; user stays on screen; fields retain input; retry possible.

### S5 — Visibility toggles (FR-002)

1. Both fields masked by default.
2. Toggle field 1 eye icon → only field 1 reveals. Toggle field 2 → independent.

### S6 — Regression guard

1. Settings: all other rows (Notifications, Permissions Setup, Word List, Log Out, etc.) behave as before.
2. Login screen unchanged.
3. Scope check:

```powershell
git diff --name-only
```

Expected app-source delta: ONLY `SettingsScreen.kt`, `NavGraph.kt`, plus new `ChangePasswordViewModel.kt`, `ChangePasswordScreen.kt`.

## References

- Contract: [contracts/ui-contract.md](./contracts/ui-contract.md)
- State model: [data-model.md](./data-model.md)
- Decisions: [research.md](./research.md)
