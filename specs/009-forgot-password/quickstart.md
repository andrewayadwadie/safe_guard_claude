# Quickstart Validation: Forgot Password

**Feature**: 009-forgot-password

## Prerequisites

- Emulator/device with the app installed: `.\gradlew.bat installDebug`
- Backend reachable at `https://bw.noor.net:8090/api/v1`
- A real account email you can read (reset codes arrive by email)
- App signed out (Login screen visible)

## Build & unit tests

```powershell
.\gradlew.bat assembleDebug          # must compile clean
.\gradlew.bat testDebugUnitTest      # ForgotPasswordViewModel + parser tests green
```

## Scenario 1 — Happy path (US1 + US2)

1. Login screen → tap **Forgot Password?** (below password field).
2. Enter account email → **Send Code**. Expect loading spinner, then confirmation text and navigation to reset step. Resend button disabled, counting down from 60.
3. Read 6-digit code from email. Enter into OTP boxes (verify auto-advance while typing).
4. Enter new password (≥ 8 chars) + matching confirm. Tap **Reset Password**.
5. Expect: immediate return to Login + snackbar "Password has been reset successfully" (localized).
6. Log in with the new password → succeeds (SC-003).

## Scenario 2 — Validation (local, no network)

- Email step: blank / `foo@` → inline error, no request sent (check Logcat: no HTTP line).
- Reset step: 3-digit code → inline code error. 6-char password → inline "at least 8 characters". Mismatched confirm → inline mismatch error.

## Scenario 3 — Server errors

- Wrong code (e.g., `000000`) → inline "Invalid or expired reset code" on code field; can correct and retry.
- Trigger rate limit (repeat sends) → "too many attempts" message; user stays on screen (429 path).
- Airplane mode → submit → connectivity snackbar; disable airplane mode → retry works.

## Scenario 4 — Resend & back-nav (US3)

1. On reset step, wait for cooldown → 0, tap **Resend code** → new email arrives, countdown restarts at 60.
2. Tap back → email step shows the previously entered email intact.
3. Old code after resend → server decides; expect 400 inline error if invalidated.

## Scenario 5 — Localization / RTL (FR-012)

1. Settings → switch language to Arabic (or set device locale ar).
2. Repeat Scenario 1 through step 4: all labels/errors Arabic, layout RTL, OTP boxes still fill left-to-right, no clipped text (SC-004).

## Scenario 6 — Paste & lifecycle edge cases

- Copy 6-digit code, long-press OTP field → paste → all six boxes filled.
- Rotate device on each step → entered values and cooldown preserved (ViewModel survives).
- Background app mid-flow → resume → no crash, state intact.

## Pass criteria

All scenarios behave as stated; no raw JSON (`[{"type":...}]`) ever shown to the user; Logcat contains no reset code or password values (`adb logcat | Select-String "955|password"` shows nothing sensitive).

References: [contracts/auth-password-reset.md](contracts/auth-password-reset.md) · [data-model.md](data-model.md) · [spec.md](spec.md)
