---
description: "Task list for Google Sign-In UI Integration"
---

# Tasks: Google Sign-In UI Integration

**Input**: Design documents from `specs/001-google-signin-ui/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/oauth-google.md, quickstart.md

**Tests**: Original feature — lean optional set in Polish (Phase 6). **Edit 2** — hybrid verification (per clarify): automated tests T021 (button persistence) + T023 (client-ID assertion) are REQUIRED (FR-019/020); live token-exchange + DB persistence is manual (T024).

**Organization**: Tasks grouped by user story. Reflects the plan's codebase audit — feature is partially built; tasks correct drift + fill genuine gaps.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: parallelizable (different files, no incomplete-task deps)
- **[Story]**: US1 / US2 / US3 (Setup/Foundational/Polish have no story label)

## Path Conventions

Android single module: `app/src/main/java/com/safeguard/parentalcontrol/`, resources `app/src/main/res/`, app gradle `app/build.gradle`.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Config + asset prerequisites with no code dependencies.

- [x] T001 Replace `GOOGLE_WEB_CLIENT_ID` placeholder in `app/build.gradle` `defaultConfig` with real value `216776621097-p63ekcnr94bfcuoufehgpsvnl6b2g990.apps.googleusercontent.com` (FR-011, research R7)
- [x] T002 [P] Create Google "G" 4-color vector drawable at `app/src/main/res/drawable/ic_google.xml` (24×24, blue #4285F4 / red #EA4335 / yellow #FBBC05 / green #34A853; no tint) (FR-013, research R4)

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Shared analytics infra + all ViewModel-layer changes. BLOCKS every user story (UI wiring depends on these).

**⚠️ CRITICAL**: No user story phase can start until T003–T004 complete.

- [x] T003 [P] Create `AnalyticsHelper` at `app/src/main/java/com/safeguard/parentalcontrol/util/AnalyticsHelper.kt` — `@Singleton`, inject `FirebaseAnalytics`, methods `logGoogleSignInTapped()`, `logGoogleSignInSuccess()`, `logGoogleSignInFailed(reason)`, `logGoogleRoleSelected(role)`; events carry event name + outcome + role string ONLY, never idToken/email/name (FR-014, research R2, Principle I/V). Add a Hilt `@Provides FirebaseAnalytics` in an existing/new `di/` module if not already provided.
- [x] T004 Patch `app/src/main/java/com/safeguard/parentalcontrol/presentation/auth/AuthViewModel.kt`: (a) inject `AnalyticsHelper`; (b) in `signInWithGoogle` emit `logGoogleSignInTapped()` at start, `logGoogleSignInFailed("cancelled")` in `Cancelled`/`NoAccounts` branches; (c) in `authenticateWithGoogle` success → `logGoogleSignInSuccess()`; (d) broaden new-user detection to normalized `rolerequired` match covering both `"Role is required"` and `"role_required"` (research R1); (e) on other errors set generic `error = "Sign-in failed. Please try again."` + `logGoogleSignInFailed("error")` (FR-016); (f) in `completeGoogleRegistration` emit `logGoogleRoleSelected(role)`; (g) make `cancelGoogleRoleSelection` also set `isGoogleSignInLoading = false` (FR-010, research R6)

**Checkpoint**: Config, asset, analytics, and all ViewModel logic ready — UI wiring can begin.

---

## Phase 3: User Story 1 — Existing User Signs In via Google (Priority: P1) 🎯 MVP

**Goal**: Returning Google user taps "Continue with Google" on Login or Register and lands on the correct home screen.

**Independent Test**: Flag enabled, tap button, pick existing account → home screen, no role dialog (quickstart scenarios 1, 2, 9).

- [x] T005 [P] [US1] Create reusable `GoogleSignInButton` at `app/src/main/java/com/safeguard/parentalcontrol/presentation/auth/components/GoogleSignInButton.kt` — `OutlinedButton`, `fillMaxWidth().height(50.dp)`, `RoundedCornerShape(12.dp)`, outline border; `ic_google` via `Image` left with `contentDescription = "Google"`; label "Continue with Google"; `CircularProgressIndicator` (24.dp, 2.dp stroke) replaces content when `isLoading`; disabled when `!enabled` (FR-003,004,013,015, research R3)
- [x] T006 [US1] Refactor `app/src/main/java/com/safeguard/parentalcontrol/presentation/auth/LoginScreen.kt`: replace the inline `OutlinedButton` (lines ~220–251) with `GoogleSignInButton(onClick = { viewModel.signInWithGoogle(context) }, isLoading = uiState.isGoogleSignInLoading, enabled = !uiState.isLoading && !uiState.isGoogleSignInLoading)`; keep the `if (uiState.isGoogleSignInEnabled)` visibility gate + OR divider (FR-001,003,004,012)
- [x] T007 [US1] Add Google sign-in block to `app/src/main/java/com/safeguard/parentalcontrol/presentation/auth/RegisterScreen.kt`: after "Create Account" button, before Terms text, add `Spacer(16.dp)` + OR divider row + `Spacer(16.dp)` + `GoogleSignInButton(...)`, all gated by `if (uiState.isGoogleSignInEnabled)`; add `val context = LocalContext.current` (FR-002,012)

**Checkpoint**: Existing-user Google sign-in works on both screens. MVP demoable.

---

## Phase 4: User Story 2 — New User Registers via Google and Selects Role (Priority: P1)

**Goal**: New Google user is gated through a compliant role-selection dialog before account creation.

**Independent Test**: New account → dialog appears; Continue disabled until card tapped; outside tap won't dismiss; Continue creates account; Cancel aborts cleanly (quickstart scenarios 3, 4, 5, 6).

- [x] T008 [P] [US2] Create reusable `RoleSelectionDialog` at `app/src/main/java/com/safeguard/parentalcontrol/presentation/auth/components/RoleSelectionDialog.kt` — `AlertDialog` with `onDismissRequest = {}` + `DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = false)` (FR-009); internal `selectedRole: UserRole?` (none preselected); two tappable cards Parent (shield, "I manage my child's device") / Child (child icon, "My device is monitored"), selected → primary border + `primaryContainer` tint, tap selects only (FR-007); `confirmButton` "Continue" `enabled = selectedRole != null` → `onRoleSelected(selectedRole!!)` (FR-008); `dismissButton` "Cancel" → `onDismiss` (FR-010); cards merge title+subtitle semantics for TalkBack (FR-015, research R5)
- [x] T009 [US2] In `LoginScreen.kt`: replace the existing `RoleSelectionDialog(...)` call to use the new shared component import; wire `onRoleSelected = { viewModel.completeGoogleRegistration(it) }`, `onDismiss = { viewModel.cancelGoogleRoleSelection() }` under `if (uiState.needsRoleSelection)` (FR-006)
- [x] T010 [US2] Remove the obsolete `private fun RoleSelectionDialog(...)` (lines ~291–388) from `LoginScreen.kt` and its now-unused imports
- [x] T011 [US2] In `RegisterScreen.kt`: add top-level `if (uiState.needsRoleSelection) { RoleSelectionDialog(onRoleSelected = { viewModel.completeGoogleRegistration(it) }, onDismiss = { viewModel.cancelGoogleRoleSelection() }) }` using the shared component (FR-006)

**Checkpoint**: New-user role flow compliant on both screens; US1 still works.

---

## Phase 5: User Story 3 — User Cancels or Has No Google Accounts (Priority: P2)

**Goal**: Cancelled picker / no-accounts device returns to a clean interactive state.

**Independent Test**: Tap Google then dismiss picker → spinner clears, form usable, no error; remove all accounts → tap → graceful, no crash (quickstart scenarios 7, 8).

- [x] T012 [US3] Verify in `AuthViewModel.signInWithGoogle` that `Cancelled` and `NoAccounts` branches reset `isGoogleSignInLoading = false` and (per T004) emit `logGoogleSignInFailed("cancelled")`; for `Cancelled` ensure NO `error` snackbar is set (silent return per FR/US3); confirm via the `GoogleSignInResult` sealed handling. Adjust `NoAccounts` to set generic non-crashing state (existing "No Google accounts found" message acceptable, or silent)

**Checkpoint**: All three stories independently functional.

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Tests (optional), audits, validation.

- [x] T013 [P] (OPTIONAL) ViewModel unit tests with Turbine in `app/src/test/java/com/safeguard/parentalcontrol/presentation/auth/AuthViewModelGoogleTest.kt`: `cancelGoogleRoleSelection` clears all three Google fields incl. loading (FR-010); role-required error → `needsRoleSelection=true` + token stored (R1, both message shapes); generic error mapping (FR-016); `AnalyticsHelper` (mocked) invoked per transition (FR-014)
- [x] T014 [P] (OPTIONAL) Compose UI tests in `app/src/androidTest/java/com/safeguard/parentalcontrol/presentation/auth/GoogleSignInUiTest.kt`: button absent when `isGoogleSignInEnabled=false`; dialog "Continue" disabled until selection; outside tap does not dismiss
- [x] T015 [P] Audit `AnalyticsHelper` call sites — confirm no idToken/email/name in any event param (Principle I/V)
- [ ] T016 Run `quickstart.md` manual validation (scenarios 1–12) on a device with a Google account

---

# Edit 2 (2026-06-24): Persistent Button, Verification, Backend Integration

**Adds**: US4 (persistent button), US5 (auth verification), backend doc deliverable. Tasks T017+. Builds on shipped T001–T015.

## Phase 7: User Story 4 — Google Button Stays Visible (Priority: P1) 🐞 BUGFIX

**Goal**: Button visible at first render and permanently; never hidden by the OAuth status check.

**Independent Test**: Open Login with status endpoint unreachable/disabled → button visible at first render and still visible after 10s; same on Register (quickstart scenarios 13–16).

**⚠️ Ordering**: remove screen gates (T017–T018) BEFORE removing the `isGoogleSignInEnabled` field (T019), else screens fail to compile.

- [x] T017 [P] [US4] Remove the `if (uiState.isGoogleSignInEnabled)` gate in `LoginScreen.kt` — Google block (OR divider + `GoogleSignInButton`) now rendered unconditionally (FR-017)
- [x] T018 [P] [US4] Remove the `if (uiState.isGoogleSignInEnabled)` gate in `RegisterScreen.kt` — Google block rendered unconditionally (FR-017)
- [x] T019 [US4] `AuthViewModel.kt`: removed `isGoogleSignInEnabled` from `AuthUiState`, deleted `checkGoogleOAuthStatus()` + its `init` call (FR-018, research R2)
- [x] T020 [US4] (cleanup) Removed dead `AuthRepository.isGoogleOAuthEnabled()`, `ApiService.getGoogleOAuthStatus()`, and `GoogleOAuthStatus` model (no other callers; grep-verified). Removed stale test stub in `AuthViewModelGoogleTest.kt`

**Checkpoint**: Button always visible on both screens; status network call gone.

---

## Phase 8: User Story 5 — Verify Firebase OAuth & User Persistence (Priority: P1)

**Goal**: Prove the OAuth chain works end-to-end and the user is persisted (no duplicate).

**Independent Test**: Real Google account → 200 token exchange → one row in backend `users` table → no duplicate on repeat (quickstart scenarios 17–21).

- [x] T021 [P] [US5] Added regression UI test `googleButton_isDisplayedByDefault` in `GoogleSignInUiTest.kt`: asserts `GoogleSignInButton` exists + displayed + enabled by default (no flag input) — guards the disappearing-button bug (FR-019). Also added missing `assertExists`/`assertIsDisplayed` imports
- [x] T022 [US5] Reviewed `GoogleSignInUiTest.kt` — the prior `googleButton_disabledWhenEnabledFalse` tests the component `enabled` param (FR-004, still valid), NOT a screen-flag gate. No obsolete screen-flag assertion existed to remove; the stale ViewModel test stub was the only obsolete reference (fixed in T020)
- [x] T023 [P] [US5] Added `GoogleWebClientIdTest.kt` (`app/src/test/.../presentation/auth/`): asserts `BuildConfig.GOOGLE_WEB_CLIENT_ID` non-blank, not `YOUR_WEB_CLIENT_ID` placeholder, ends with `.apps.googleusercontent.com` (FR-020)
- [ ] T024 [US5] **MANUAL — needs device + live backend**. Run quickstart scenarios 17–21 with a real Google account + reachable backend: confirm 200 token exchange, exactly one row in the FastAPI `users` table (`google_sub`, `auth_provider='google'`, chosen role), and no duplicate on repeat sign-in (FR-021, FR-022, SC-009, SC-010). Per `backend-google-auth-integration.md` §6

**Checkpoint**: OAuth chain verified; persistence confirmed in DB; no duplicates.

---

## Phase 9: Backend Integration (Doc Deliverable — separate FastAPI repo)

**Purpose**: Hand the backend team exact endpoint edits. **No client code in this Android repo.**

- [x] T025 [P] Author `specs/001-google-signin-ui/backend-google-auth-integration.md` — token verification, create-or-fetch upsert (no duplicate), both `/oauth/google` + login/register `id_token` fallback, DB migration, verification checklist (FR-024, FR-025) — **done**
- [ ] T026 Hand `backend-google-auth-integration.md` to the backend team; track endpoint implementation in the FastAPI repo (out of this module's scope). Optional future client work: add `id_token` to `LoginRequest`/`RegisterRequest` DTOs in `data/model/Models.kt` if the fallback path is adopted client-side (deferred)

**Checkpoint**: Backend instructions delivered; client unaffected.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (P1)**: no deps — start immediately. T001, T002 parallel.
- **Foundational (P2)**: after Setup. T003 [P] parallel with nothing else; T004 depends on T003 (injects AnalyticsHelper). **Blocks all stories.**
- **US1 (P3)**: after Foundational. MVP.
- **US2 (P4)**: after Foundational. Independent of US1 (different components; both edit LoginScreen/RegisterScreen — sequence US1 screen edits before US2 screen edits to avoid conflicts).
- **US3 (P5)**: after Foundational (T004 already covers most). Light.
- **Polish (P6)**: after target stories complete.

### Story Independence Notes

- US1 = `GoogleSignInButton` + button wiring. US2 = `RoleSelectionDialog` + dialog wiring. Components are separate files (T005 vs T008) → parallel.
- ⚠️ File-conflict caution: T006/T007 (US1) and T009/T010/T011 (US2) both edit `LoginScreen.kt`/`RegisterScreen.kt`. Within a screen, do US1 edits then US2 edits sequentially — do NOT parallelize edits to the same screen file.

### Within Each Story

- Component file (T005, T008) before screen wiring that consumes it.
- ViewModel (T004, Foundational) before all UI emission.

### Edit 2 (US4 / US5 / Backend)

- **US4 (Phase 7)**: T017 + T018 (different screen files) → parallel. T019 (remove field) MUST follow both — removing `isGoogleSignInEnabled` breaks any remaining screen reference. T020 cleanup last (grep for other callers first).
- **US5 (Phase 8)**: T021 + T023 parallel (different test files). T022 depends on US4 (field must be gone). T024 manual — needs live backend, run after T017–T019 deployed to a build.
- **Phase 9**: T025 done (doc authored). T026 = handoff, no code dep.
- **Cross-edit caution**: T017/T018 (US4) edit the same screen files US1/US2 touched — but those are shipped `[x]`, so no live conflict. Still, apply US4 gate removal cleanly against current file state (line numbers may have drifted; match on the `if (uiState.isGoogleSignInEnabled)` text).

---

## Parallel Opportunities

```bash
# Phase 1 Setup — parallel:
Task: T001 build.gradle web client id
Task: T002 ic_google.xml drawable

# After Foundational — component creation parallel (different files):
Task: T005 GoogleSignInButton.kt   (US1)
Task: T008 RoleSelectionDialog.kt  (US2)

# Polish — parallel:
Task: T013 ViewModel unit tests
Task: T014 Compose UI tests
Task: T015 analytics PII audit

# Edit 2 US4 — gate removal parallel (different screen files):
Task: T017 LoginScreen.kt remove gate
Task: T018 RegisterScreen.kt remove gate
# then T019 (remove field) sequentially

# Edit 2 US5 — tests parallel (different files):
Task: T021 button-persistence UI test
Task: T023 BuildConfig client-ID assertion test
```

---

## Implementation Strategy

### MVP First (US1)

1. Phase 1 Setup (T001–T002)
2. Phase 2 Foundational (T003–T004)
3. Phase 3 US1 (T005–T007)
4. **STOP & VALIDATE**: existing-user sign-in on both screens (quickstart 1, 2, 9). Demoable MVP.

### Incremental Delivery

- + US2 (T008–T011) → new-user role flow → validate scenarios 3–6
- + US3 (T012) → cancel/no-accounts → validate 7, 8
- + Polish (T013–T016)

### Edit 2 Delivery

- **US4 BUGFIX first** (T017–T020) → button always visible → validate scenarios 13–16. Highest priority — unblocks the whole feature.
- + US5 (T021–T024) → automated guard (T021/T023) + manual live verification (T024) → validate 17–21.
- + Backend handoff (T026) → deliver doc to FastAPI team.

---

## Notes

- [P] = different files, no deps. Same-screen edits across US1/US2 are NOT [P].
- Reuse existing `completeGoogleRegistration(role)` — do NOT add `confirmGoogleSignInWithRole` (plan audit).
- Do NOT modify `GoogleSignInManager`, `AuthInterceptor`, navigation graph (spec "Do NOT touch"). **Edit 2 exception**: T020 may remove the now-dead `AuthRepository.isGoogleOAuthEnabled()` + `ApiService.getGoogleOAuthStatus()` (status check), and T019 removes `checkGoogleOAuthStatus()` from `AuthViewModel` — all part of the persistent-button bugfix (FR-018). No other repo/api changes.
- Web Client ID is public (safe in BuildConfig); `google-services.json` stays gitignored (Principle V).
- Commit after each task or logical group.
