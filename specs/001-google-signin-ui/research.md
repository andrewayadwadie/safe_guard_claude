# Phase 0 Research: Google Sign-In UI Integration

All unknowns resolved. No remaining NEEDS CLARIFICATION.

## R1 — Backend "new user / role required" detection signal

**Question**: How does the client know a Google user is new and needs a role? Spec's backend note says return HTTP 422 `{"detail": "role_required"}`. Current `AuthViewModel.authenticateWithGoogle` detects it via `result.message.contains("Role is required", ignoreCase = true)`.

**Decision**: Keep the message-substring detection but **broaden the match** to tolerate both the human string ("Role is required") and the machine code ("role_required"). The detection lives in the ViewModel where the `NetworkResult.Error.message` is available.

**Rationale**: Constitution forbids modifying the backend; the client must conform to whatever the deployed API actually returns. `safeApiCall` surfaces the backend `detail` field as `message`. A backend returning `detail: "role_required"` would NOT match the current substring `"Role is required"` — a latent bug. Matching a normalized form (lowercase, strip spaces/underscores, look for `rolerequired`) covers both shapes without a backend change.

**Alternatives considered**:
- Match on HTTP 422 status code only — rejected: `safeApiCall` abstracts away the raw status; other 422s (validation) would false-positive.
- Require backend to standardize the message — rejected: backend is out of scope and may already be deployed.

**Action**: In the error branch, normalize and check: `message.lowercase().replace(Regex("[^a-z]"), "").contains("rolerequired")`. Verify the real response shape against the Postman collection during implementation; if it differs, this is the single point to adjust.

## R2 — Analytics implementation (FR-014, SC-007)

**Question**: How to emit the four conversion events (tap, success, cancel/fail, role selected) given no analytics code exists yet?

**Decision**: Add a thin `@Singleton AnalyticsHelper` wrapping `FirebaseAnalytics`, injected into `AuthViewModel`. Emit four custom events: `google_signin_tapped`, `google_signin_success`, `google_signin_failed`, `google_signin_role_selected` (param: `role`).

**Rationale**: `firebase-analytics` is already on the classpath (BOM 33.10.0) — no new dependency. A Hilt singleton keeps ViewModels testable (mock in unit tests) and keeps Firebase out of Composables (Rule 2/5 spirit: side-effect infra stays injectable). Constitution Principle V explicitly permits Firebase for "passive analytics."

**Privacy guard**: Events carry only event name + a `role` string ("parent"/"child") + outcome. **Never** the idToken, email, or display name (Principle I/V). The cancel and fail cases share `google_signin_failed` with a `reason` param (`cancelled` | `error`) to keep the event taxonomy small (matches clarified "key events" scope).

**Alternatives considered**:
- Log events directly from Composables — rejected: violates layer discipline; harder to test.
- Full per-state instrumentation — rejected in /speckit-clarify (Option C declined).

## R3 — Reusable `GoogleSignInButton` styling (FR-003,004,013)

**Decision**: `OutlinedButton`, `fillMaxWidth().height(50.dp)`, `RoundedCornerShape(12.dp)`, `BorderStroke(1.dp, MaterialTheme.colorScheme.outline)`, surface container. Content swaps on `isLoading`: a 24.dp `CircularProgressIndicator(strokeWidth = 2.dp)` replaces the icon+label row.

**Rationale**: Matches the existing inline Login button (50.dp height, outline border) and the app's primary `Button` (50.dp). 12.dp radius matches spec. Material3 `OutlinedButton` default shape is overridden with `shape = RoundedCornerShape(12.dp)`.

**Icon**: `painterResource(R.drawable.ic_google)` inside an `Icon`/`Image`. Use `Image` (not tinted `Icon`) so the 4-color G renders correctly; set `contentDescription = "Google"` (FR-015).

## R4 — `ic_google.xml` vector (FR-013, Step 8)

**Decision**: Create a 24×24 `<vector>` with the four official Google brand paths (blue `#4285F4`, green `#34A853`, yellow `#FBBC05`, red `#EA4335`). `vectorDrawables.useSupportLibrary true` is already set in build.gradle, so multi-path color vectors render on `minSdk 26`.

**Rationale**: Replaces the current text-"G" placeholder with the real logo. Standard SVG→VectorDrawable paths, no tint (each `<path>` carries its own `android:fillColor`).

## R5 — Compliant `RoleSelectionDialog` (FR-006–010, FR-015)

**Decision**: `AlertDialog` with:
- `onDismissRequest = {}` (no-op) + `properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = false)` → satisfies FR-009 (no outside dismiss).
- Internal `var selectedRole by remember { mutableStateOf<UserRole?>(null) }`; two tappable cards toggle selection (highlight via `primary` border + `primaryContainer` tint) — does NOT commit on tap.
- `confirmButton`: "Continue", `enabled = selectedRole != null` → FR-008.
- `dismissButton`: "Cancel" → `onDismiss` → FR-010.
- Cards merge title+subtitle semantics: `Modifier.semantics(mergeDescendants = true){}` with `contentDescription` like "Parent — I manage my child's device" → FR-015.

**Rationale**: The current private dialog commits immediately on card tap (no Continue gate) and dismisses on outside tap — both violate clarified requirements. A stateful selection + explicit Continue is the standard compliant pattern.

**ViewModel confirm path**: Dialog's `onRoleSelected(role)` calls the **existing** `viewModel.completeGoogleRegistration(role)` (do not invent `confirmGoogleSignInWithRole`). `onDismiss` calls the patched `viewModel.cancelGoogleRoleSelection()`.

## R6 — `cancelGoogleRoleSelection` loading reset (FR-010)

**Decision**: Patch the existing function to also set `isGoogleSignInLoading = false`.

**Rationale**: FR-010 requires cancel to reset *all* Google loading state. Current impl resets `needsRoleSelection` + `pendingGoogleIdToken` only. Although loading is already `false` when the dialog is visible (set false on the role-required error), resetting defensively guarantees no stuck spinner if call ordering changes.

## R7 — Web Client ID extraction (FR-011)

**Decision**: Hardcode the resolved value into `buildConfigField`:
`216776621097-p63ekcnr94bfcuoufehgpsvnl6b2g990.apps.googleusercontent.com` (the `oauth_client` entry with `client_type == 3` in `app/google-services.json`).

**Rationale**: A Web Client ID is a **public** OAuth identifier, not a secret — safe in `BuildConfig`/version control. It is required by `GoogleSignInManager` for the Credential Manager `GetGoogleIdOption`. `google-services.json` itself stays gitignored.

---

## Edit 2 Research (2026-06-24)

Four decisions, resolved in the `/speckit-clarify` session. No open NEEDS CLARIFICATION.

### R2 — Button disappears ~3s after render

**Question**: Why does the Google button flash then vanish, and how to make it permanent?

**Finding**: `AuthUiState.isGoogleSignInEnabled` defaults `true` (AuthViewModel.kt:35); `init` calls `checkGoogleOAuthStatus()` (line 81) which awaits `AuthRepository.isGoogleOAuthEnabled()`. That returns `false` on any non-200/exception (AuthRepository.kt:218-230) — and the status endpoint is unreachable/absent — so the flag flips to `false` once the call resolves. Both screens gate the button on the flag → it disappears.

**Decision**: Remove the status check and the visibility gate entirely. Button is unconditional. Cheapest, kills the bug at the source, removes a useless per-load network round-trip.

**Alternatives**: keep call but ignore for visibility (dead network call); default-true-never-overwrite (leaves dead code). Rejected — full removal is cleanest.

### R3 — User store / "saved in Firebase console"

**Decision**: FastAPI `users` table is source of truth; Firebase only verifies the Google ID token. Backend is **not** on Firebase Auth, so the console Users list stays empty by design — verification queries the DB. Directly restates Constitution Principle V ("Firebase is NOT the auth source of truth — the FastAPI backend owns all accounts").

**Alternatives**: Firebase Auth as store / dual-write via Admin SDK — rejected (adds dependency, contradicts P-V).

### R4 — Backend endpoint shape (dual)

**Decision**: Google auth accepted on **both** `POST /oauth/google` (primary, current client) and `auth/login`/`auth/register` via optional `id_token` (fallback). All three share one verify + create-or-fetch routine. Client primary path unchanged; login/register fallback is a backend capability (client DTO changes deferred).

**Alternatives**: dedicated-only (cleanest but user wanted login/register edits) / fold-into-login-register-only (mixes password+token, needs client DTO churn). Chose both.

### R5 — Verification mechanism

**Decision**: Hybrid. Automate button-persistence (FR-019) + client-ID assertion (FR-020) in instrumented/unit tests. Manual checklist (quickstart §17–21) for the live chain needing a real Google account + backend (token exchange → DB persistence → no-duplicate). Full E2E Google OAuth is impractical in CI (real credentials); the regression-prone part (button) is trivially automatable.
