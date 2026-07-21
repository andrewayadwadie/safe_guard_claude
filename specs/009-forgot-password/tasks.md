# Tasks: Forgot Password

**Input**: Design documents from `/specs/009-forgot-password/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/auth-password-reset.md, quickstart.md

**Tests**: Included for ViewModel logic and error-parser only — constitution lists auth lifecycle as a testing priority area. No UI/instrumentation tests.

**Organization**: Tasks grouped by user story. US1 (request code) + US2 (redeem code) together form the functional MVP; each is independently testable per its checkpoint.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: US1 = request reset code, US2 = redeem code & set password, US3 = resend & recovery

## Path Conventions

Single Android module. Source root: `app/src/main/java/com/safeguard/parentalcontrol/`, resources: `app/src/main/res/`, unit tests: `app/src/test/java/com/safeguard/parentalcontrol/`.

---

## Phase 1: Setup (string resources)

**Purpose**: Localized strings both UI stories depend on.

- [X] T001 [P] Add EN string resources in `app/src/main/res/values/strings.xml`: `forgotpw_title`, `forgotpw_subtitle`, `forgotpw_email_label`, `forgotpw_send_code`, `forgotpw_code_sent_confirmation` (neutral anti-enumeration text), `forgotpw_reset_title`, `forgotpw_code_label`, `forgotpw_new_password_label`, `forgotpw_confirm_password_label`, `forgotpw_reset_button`, `forgotpw_resend`, `forgotpw_resend_countdown` (`%1$d`), `forgotpw_success`, `forgotpw_error_email_invalid`, `forgotpw_error_code_incomplete`, `forgotpw_error_password_too_short`, `forgotpw_error_password_mismatch`, `forgotpw_error_code_invalid`, `forgotpw_error_rate_limited`, `login_forgot_password`
- [X] T002 [P] Add Arabic translations for all T001 keys in `app/src/main/res/values-ar/strings.xml`

---

## Phase 2: Foundational (data layer — blocks all stories)

**Purpose**: Models, endpoints, repository functions, error parser — the network plumbing every story uses.

**⚠️ CRITICAL**: Complete before any user story phase.

- [X] T003 [P] Add `ForgotPasswordRequest(email)` and `ResetPasswordRequest(email, code, @SerializedName("new_password") newPassword)` data classes in `app/src/main/java/com/safeguard/parentalcontrol/data/model/Models.kt` (place near `ChangePasswordRequest`, Models.kt:117)
- [X] T004 [P] Extend `parseErrorMessage` in `app/src/main/java/com/safeguard/parentalcontrol/data/remote/NetworkResult.kt` (line 134): when `detail` is a JSON array (FastAPI 422 shape), return first element's `msg`; keep string-`detail` behavior unchanged; fallback to existing generic message (contract: contracts/auth-password-reset.md "Parser requirement")
- [X] T005 Add endpoints in `app/src/main/java/com/safeguard/parentalcontrol/data/remote/ApiService.kt` under Authentication section (after `changePassword`, line 39): `@POST("auth/forgot-password") suspend fun forgotPassword(@Body request: ForgotPasswordRequest): Response<MessageResponse>` and `@POST("auth/reset-password") suspend fun resetPassword(@Body request: ResetPasswordRequest): Response<MessageResponse>` (depends on T003)
- [X] T006 Add repository functions in `app/src/main/java/com/safeguard/parentalcontrol/data/repository/AuthRepository.kt` (after `changePassword`, line 169): `suspend fun forgotPassword(email: String): NetworkResult<MessageResponse>` and `suspend fun resetPassword(email: String, code: String, newPassword: String): NetworkResult<MessageResponse>` — both `withContext(Dispatchers.IO) { safeApiCall { ... } }`, no Timber logging of code/password (depends on T005)
- [X] T007 [P] Verify `app/src/main/java/com/safeguard/parentalcontrol/data/remote/AuthInterceptor.kt` does not attach tokens or trigger refresh for `auth/forgot-password` / `auth/reset-password` when signed out (same precondition as `auth/login`); if an explicit no-auth path list exists, add both paths (research R5)
- [X] T008 [P] Create `app/src/main/java/com/safeguard/parentalcontrol/presentation/forgotpassword/ForgotPasswordUiState.kt`: `ForgotPasswordStep` enum (`EMAIL`, `RESET`) + `ForgotPasswordUiState` data class exactly per data-model.md (step, email, isLoading, error, emailFieldError, codeFieldError, passwordFieldError, cooldownSeconds, codeSent, resetSuccess)
- [X] T009 Create `app/src/main/java/com/safeguard/parentalcontrol/presentation/forgotpassword/ForgotPasswordViewModel.kt` skeleton: `@HiltViewModel`, inject `AuthRepository` + `@ApplicationContext Context`, private `MutableStateFlow(ForgotPasswordUiState())` + public `asStateFlow()`, `getString` via `LocaleHelper.localizedContext` (pattern: ChangePasswordViewModel.kt:34), `clearError()`, `onEmailChange()` (depends on T006, T008)

**Checkpoint**: Data layer compiles; `.\gradlew.bat assembleDebug` green.

---

## Phase 3: User Story 1 — Request a password reset code (Priority: P1) 🎯 MVP part 1

**Goal**: "Forgot Password?" on Login → email screen → request code → neutral confirmation, 429 and network errors handled.

**Independent Test**: quickstart.md Scenario 1 steps 1–2, Scenario 2 (email validation), Scenario 3 (rate limit, airplane mode).

### Implementation for User Story 1

- [X] T010 [US1] Implement `submitEmail()` in `app/src/main/java/com/safeguard/parentalcontrol/presentation/forgotpassword/ForgotPasswordViewModel.kt`: trim + validate via `Patterns.EMAIL_ADDRESS` (invalid → `emailFieldError`, no network); on valid → `isLoading`, call `authRepository.forgotPassword`; Success → `codeSent=true`, `step=RESET`, start 60s cooldown (coroutine `delay(1000)` decrement loop per research R3); Error code 429 → localized `forgotpw_error_rate_limited`; other Error → `error=result.message`
- [X] T011 [US1] Create `app/src/main/java/com/safeguard/parentalcontrol/presentation/forgotpassword/ForgotPasswordScreen.kt`: stateful wrapper + stateless `ForgotPasswordContent` (pattern: LoginScreen.kt:97); `HarisGradientHeader` + title/subtitle, `HarisTextField` for email (keyboard type Email, ImeAction.Done), inline `emailFieldError`, `HarisPrimaryButton` "Send Code" disabled while `isLoading`, back navigation icon, error snackbar via `LaunchedEffect(uiState.error)` + `viewModel.clearError()` (Rule 10), `@Preview` with `SafeGuardTheme`
- [X] T012 [US1] Add "Forgot Password?" `TextButton` in `app/src/main/java/com/safeguard/parentalcontrol/presentation/auth/LoginScreen.kt` below the password field (end-aligned), string `login_forgot_password`, new `onNavigateToForgotPassword: () -> Unit` parameter threaded through `LoginContent`
- [X] T013 [US1] Wire navigation in `app/src/main/java/com/safeguard/parentalcontrol/presentation/navigation/NavGraph.kt`: add `Screen.ForgotPassword("forgot_password_email")` + `Screen.ResetPassword("forgot_password_reset")` inside nested `navigation(route = "forgot_password_flow", startDestination = forgot_password_email)`; both destinations obtain `ForgotPasswordViewModel` scoped to the flow's back-stack entry (research R1); pass `onNavigateToForgotPassword` into `LoginScreen` composable (Screen.Login block, line 151); email destination navigates to reset destination when `uiState.codeSent` flips true
- [X] T014 [P] [US1] Unit tests: `app/src/test/java/com/safeguard/parentalcontrol/presentation/forgotpassword/ForgotPasswordViewModelTest.kt` (submitEmail: invalid format → field error + repo never called; success → codeSent + cooldown=60; 429 → rate-limit message). NOTE: standalone `ParseErrorMessageTest` was NOT created — `parseErrorMessage` uses `org.json`, which is an android.jar stub that throws under plain JVM unit tests (no Robolectric on the classpath). The 422-array→passwordFieldError path is instead covered end-to-end in `ForgotPasswordViewModelTest` ("reset 422 sets password field error from server message") and by quickstart Scenario 3.

**Checkpoint**: Tap Forgot Password → send code → see confirmation + land on reset step (screen may be stub until Phase 4). US1 independently demoable.

---

## Phase 4: User Story 2 — Redeem code and set new password (Priority: P1) 🎯 MVP part 2

**Goal**: 6-digit OTP entry + new password → reset → auto-return to Login with success snackbar; 400/422 handled inline.

**Independent Test**: quickstart.md Scenario 1 steps 3–6, Scenario 2 (code/password validation), Scenario 3 (wrong code), Scenario 6 (paste, rotation).

### Implementation for User Story 2

- [X] T015 [P] [US2] Create `app/src/main/java/com/safeguard/parentalcontrol/presentation/forgotpassword/components/OtpCodeInput.kt`: single hidden `BasicTextField` backing 6 styled digit boxes (border/focus colors matching `HarisTextField`), digits-only filter, max 6, paste fills all, `CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr)` around the row (research R6), `@Preview`
- [X] T016 [US2] Implement `submitReset(code, newPassword, confirmPassword)` in `ForgotPasswordViewModel.kt`: local validation first (code ≠ 6 digits → `codeFieldError`; password < 8 → `passwordFieldError` `forgotpw_error_password_too_short`; mismatch → `forgotpw_error_password_mismatch`) — no network on local failure; on valid → `authRepository.resetPassword`; Success → `resetSuccess=true`; Error 400 → `codeFieldError` = `forgotpw_error_code_invalid`; Error 422 → `passwordFieldError` = parsed message; other → `error` (state transitions per data-model.md)
- [X] T017 [US2] Create `app/src/main/java/com/safeguard/parentalcontrol/presentation/forgotpassword/ResetPasswordScreen.kt`: stateless-content pattern; `OtpCodeInput` with inline `codeFieldError`, two `HarisTextField` password fields with show/hide toggles (`Visibility`/`VisibilityOff` icons, pattern LoginScreen), inline `passwordFieldError`, `HarisPrimaryButton` "Reset Password" disabled while `isLoading`, error snackbar via `LaunchedEffect(uiState.error)` + `clearError()` (Rule 10)
- [X] T018 [US2] Complete flow navigation in `NavGraph.kt`: on `uiState.resetSuccess` → set `navController.getBackStackEntry(Screen.Login.route).savedStateHandle["password_reset_success"] = true` (research R2) and `popBackStack(Screen.Login.route, inclusive = false)`
- [X] T019 [US2] Show success snackbar in `LoginScreen.kt`: observe `password_reset_success` from the Login back-stack entry's `savedStateHandle`; when true → snackbar `forgotpw_success` via existing `snackbarHostState`, then clear the flag
- [X] T020 [P] [US2] Extend `ForgotPasswordViewModelTest.kt`: submitReset local validation (short code, short password, mismatch → field errors, repo never called), 400 → codeFieldError, 422 → passwordFieldError, success → resetSuccess=true

**Checkpoint**: Full happy path works end-to-end (quickstart Scenario 1). MVP complete.

---

## Phase 5: User Story 3 — Resend code and graceful recovery (Priority: P2)

**Goal**: Resend with 60s cooldown UI; back to email step preserves email.

**Independent Test**: quickstart.md Scenario 4.

### Implementation for User Story 3

- [X] T021 [US3] Implement `resendCode()` in `ForgotPasswordViewModel.kt`: guard `cooldownSeconds == 0`, reuse forgot-password call with stored email, restart cooldown job (cancel previous — single `Job` reference), stay on RESET step; 429 → rate-limit message + cooldown continues
- [X] T022 [US3] Add resend row to `ResetPasswordScreen.kt`: `TextButton` — enabled shows `forgotpw_resend`, disabled shows `forgotpw_resend_countdown` with `cooldownSeconds`
- [X] T023 [US3] Back navigation on reset step in `ResetPasswordScreen.kt` + `NavGraph.kt`: back arrow / system back pops to email destination; ViewModel `step` reset to EMAIL, `email` preserved (graph-scoped VM), cooldown keeps ticking
- [X] T024 [P] [US3] Extend `ForgotPasswordViewModelTest.kt`: cooldown decrements with test dispatcher time advance, resend blocked while > 0, resend restarts at 60

**Checkpoint**: All three stories functional.

---

## Phase 6: Polish & Cross-Cutting Concerns

- [X] T025 [P] Privacy audit: grep new files for Timber/Log calls; confirm no email/code/password values logged (FR-013, constitution Principle V)
- [X] T026 [P] RTL/Arabic pass on both screens per quickstart Scenario 5: Arabic strings render, layout mirrors, OTP boxes stay LTR, no clipped text (FR-012, SC-004)
- [X] T027 Run `.\gradlew.bat testDebugUnitTest` + `.\gradlew.bat installDebug`, then execute full quickstart.md validation (Scenarios 1–6) on emulator

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1 (Setup)**: none — start immediately; T001 ∥ T002
- **Phase 2 (Foundational)**: independent of Phase 1 (code vs resources); blocks Phases 3–5. Order: T003+T004 [P] → T005 → T006 → T009; T007, T008 anytime in phase
- **Phase 3 (US1)**: needs Phases 1–2. Order: T010 → T011 → T012 → T013; T014 parallel after T010
- **Phase 4 (US2)**: needs Phase 2; T015 parallel anytime; T016 → T017 → T018 → T019; T020 after T016. Navigation entry depends on T013 (US1) for the nested graph shell
- **Phase 5 (US3)**: needs Phase 4 screens. T021 → T022 → T023; T024 after T021
- **Phase 6 (Polish)**: after Phases 3–5

### User Story Dependencies

- **US1**: independent (MVP part 1)
- **US2**: builds on US1's nested nav graph (T013) but logic/UI tasks independent of US1 logic
- **US3**: extends US2's ResetPasswordScreen

### Parallel Opportunities

- T001 ∥ T002 (different resource files)
- T003 ∥ T004 ∥ T007 ∥ T008 (different files)
- T015 (OTP component) parallel with all of Phase 3
- Test tasks T014, T020, T024 parallel with subsequent UI tasks
- T025 ∥ T026 in polish

## Parallel Example: Foundational

```bash
# After Phase 1, launch simultaneously:
Task: "T003 request models in Models.kt"
Task: "T004 422-array parser in NetworkResult.kt"
Task: "T007 AuthInterceptor no-auth verification"
Task: "T008 ForgotPasswordUiState.kt"
```

## Implementation Strategy

**MVP = Phase 1 + 2 + 3 + 4** (US1 and US2 together — a reset flow that can't redeem codes delivers no user value alone; US1 checkpoint still independently demoable). Then US3 (resend polish), then Phase 6 validation. Single developer: strictly sequential phase order. Commit after each phase checkpoint.

## Notes

- Constitution rules enforced throughout: Rule 4 (ViewModel → Repository → safeApiCall → ApiService), Rule 9 (`NetworkResult` returns), Rule 10 (snackbar errors + `clearError()`), Rule 1 (feature package)
- No ProGuard changes (no new Service/Receiver/Worker); no manifest changes; no new dependencies
- Reset code / passwords never logged or persisted (data-model.md retention table)
