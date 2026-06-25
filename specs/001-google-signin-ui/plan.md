# Implementation Plan: Google Sign-In UI Integration

**Branch**: `001-google-signin-ui` | **Date**: 2026-06-23 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/001-google-signin-ui/spec.md`

## Summary

Wire the existing Google Sign-In backend/ViewModel infrastructure to the auth UI. A codebase audit (see Technical Context) shows the feature is **partially implemented already** — the spec's stated "gap" is stale. LoginScreen already renders a Google button + role dialog inline; RegisterScreen does not. This plan corrects the spec-vs-reality drift and delivers the *true* remaining work: extract two reusable components (`GoogleSignInButton`, `RoleSelectionDialog`) that satisfy the clarified requirements (Continue-gated dialog, no outside-dismiss, accessibility, generic error text, analytics), wire them into **both** screens, populate the real Web Client ID, add the `ic_google` vector, and patch two ViewModel gaps.

## Technical Context

**Language/Version**: Kotlin (JDK 21 toolchain, `jvmTarget = '17'`), Jetpack Compose

**Primary Dependencies**: Compose BOM 2023.10.01, Material3 1.1.2, Hilt 2.51.1, androidx.credentials 1.3.0, googleid 1.1.1, Firebase BOM 33.10.0 (firebase-analytics present), Retrofit 2.9.0

**Storage**: N/A for this feature (tokens via existing `TokenManager`/`EncryptedSharedPreferences`; no new persistence)

**Testing**: JUnit4 + kotlinx-coroutines-test + Turbine (unit, ViewModel); Compose ui-test-junit4 (UI) — all already on classpath

**Target Platform**: Android `minSdk 26`, `targetSdk 34`, `compileSdk 34` (note: constitution states 35; out of scope here)

**Project Type**: Mobile app (single Android module `app/`)

**Performance Goals**: Returning-user sign-in < 10s; new-user flow < 60s (SC-001/002) — dominated by network + Google picker, UI adds negligible latency

**Constraints**: No child-monitored data touched (Principle I N/A — auth metadata only); no backend modification (client conforms to existing `POST /oauth/google` contract); errors via Snackbar only (Rule 10); generic error text (FR-016)

**Scale/Scope**: 2 screens, 2 new reusable components, 1 vector drawable, 1 analytics helper, 3 file edits. ~7 files touched.

### Codebase audit — spec claims vs. reality

| Item | Spec/prompt said | Actual state | Action |
|---|---|---|---|
| `GoogleSignInManager`, `AuthRepository.googleSignIn`, `ApiService` OAuth endpoints | done | ✅ confirmed present | none |
| build.gradle deps (credentials, googleid, firebase) | done | ✅ present (Firebase BOM 33.10.0, firebase-analytics on classpath) | none |
| `google-services.json` | present | ✅ present; Web Client ID (type 3) = `216776621097-p63ekcnr94bfcuoufehgpsvnl6b2g990.apps.googleusercontent.com` | use it |
| `BuildConfig.GOOGLE_WEB_CLIENT_ID` | placeholder | ❌ still `"YOUR_WEB_CLIENT_ID..."` | **fix (FR-011)** |
| LoginScreen Google button | "NO button yet" | ⚠️ **already exists** inline (`OutlinedButton`, text "G") + OR divider | refactor to shared component |
| LoginScreen role dialog | not implemented | ⚠️ **already exists** as `private fun RoleSelectionDialog` in LoginScreen.kt; commits on card-tap, dismisses on outside-tap | replace w/ compliant shared component |
| RegisterScreen Google button | "NO button yet" | ✅ confirmed absent | **add (FR-002)** |
| `GoogleSignInButton.kt` shared component | create | ❌ absent | **create** |
| `RoleSelectionDialog.kt` shared component | create | ❌ absent (only private copy) | **create** |
| `ic_google.xml` | create | ❌ absent | **create (FR-013)** |
| ViewModel `confirmGoogleSignInWithRole` | add | ⚠️ equivalent `completeGoogleRegistration(role)` already exists | reuse existing; do NOT add duplicate |
| ViewModel `cancelGoogleRoleSelection` | add | ⚠️ exists but does NOT reset `isGoogleSignInLoading` | **patch (FR-010)** |
| Analytics events | (clarified FR-014) | ❌ no `FirebaseAnalytics` usage anywhere | **create helper + emit** |
| Generic error text (FR-016) | (clarified) | ❌ raw `result.message` passed through | **map to generic string** |
| Backend new-user signal | "HTTP 422 role_required" | ⚠️ ViewModel matches message substring `"Role is required"` — may not match backend `detail: role_required` | **research item R1** |

## Constitution Check

*GATE: evaluated against all 12 SpecKit Enforcement Rules + 5 principles. Re-checked post-design.*

| Rule / Principle | Status | Notes |
|---|---|---|
| R1 feature sub-package | ✅ | Work stays in `presentation/auth/`; new `presentation/auth/components/` sub-dir for shared composables |
| R2 `@HiltViewModel`, no manual instantiation | ✅ | Reuses existing `AuthViewModel` (`hiltViewModel()`) |
| R3 repository `@Singleton` | ✅ | No repo changes; `AuthRepository` already `@Singleton` |
| R4 ViewModel→Repo→safeApiCall→ApiService | ✅ | No new API path; existing chain untouched |
| R5 tokens only in TokenManager | ✅ | UI/analytics never read tokens; analytics emits metadata only (no idToken, no email) |
| R6 special permissions via settings routing | ✅ | N/A — no special Android permissions in this feature |
| R7 Services hold no business logic | ✅ | N/A — no service changes |
| R8 Workers `@HiltWorker`+`CoroutineWorker` | ✅ | N/A — no workers |
| R9 `NetworkResult<T>` sole repo return | ✅ | Unchanged; `googleSignIn` already returns `NetworkResult<User>` |
| R10 errors via Snackbar, not AlertDialog | ✅ | Error (FR-016) goes through existing `LaunchedEffect(uiState.error)` Snackbar. `RoleSelectionDialog` is a **choice** dialog, not an error dialog — permitted |
| R11 ProGuard rules per new Service/Receiver/Worker | ✅ | N/A — none added |
| R12 `aaptOptions { noCompress "tflite" }` | ✅ | Untouched |
| P-I Child Safety & Privacy First | ✅ | No monitored data; auth metadata only (documented in spec Assumptions). Analytics carries event name + role label only |
| P-II MVVM/Clean Arch | ✅ | UiState-driven; `_uiState.update{}`; `collectAsStateWithLifecycle()` already used in both screens |
| P-III Two-role separation | ✅ | Role selection feeds account creation; no enforcement logic added |
| P-IV Permission hygiene | ✅ | N/A |
| P-V Secrets & defense-in-depth | ✅ | Web Client ID is a **public** OAuth client identifier (safe in `BuildConfig`); `google-services.json` stays gitignored; no secret committed |

**Result: PASS — no violations. Complexity Tracking not required.**

**Privacy/data-flow note (Principle I gate):** This feature collects nothing from the monitored child surface. Data in flight: Google `idToken` (device → backend, existing path) and an optional `role` label. Analytics events carry only: event name, success/failure boolean, and selected role string. No email, no name, no token is logged. Retention: none added client-side.

## Project Structure

### Documentation (this feature)

```text
specs/001-google-signin-ui/
├── plan.md              # This file
├── research.md          # Phase 0 — backend signal + analytics + accessibility decisions
├── data-model.md        # Phase 1 — UI state & component contracts (no DB entities)
├── quickstart.md        # Phase 1 — manual + automated validation guide
├── contracts/
│   └── oauth-google.md  # POST /oauth/google + GET /oauth/google/status (existing contract, documented)
├── checklists/
│   └── requirements.md  # From /speckit-specify (12/12 passing)
└── tasks.md             # Phase 2 (/speckit-tasks — NOT created here)
```

### Source Code (repository root)

```text
app/src/main/
├── java/com/safeguard/parentalcontrol/
│   ├── presentation/auth/
│   │   ├── AuthViewModel.kt           # EDIT: patch cancelGoogleRoleSelection (reset loading);
│   │   │                              #       map Google backend errors to generic text (FR-016);
│   │   │                              #       emit analytics events (FR-014)
│   │   ├── LoginScreen.kt             # EDIT: replace inline button + private dialog with shared components
│   │   ├── RegisterScreen.kt          # EDIT: add OR divider + GoogleSignInButton + dialog trigger
│   │   └── components/                # NEW sub-package
│   │       ├── GoogleSignInButton.kt  # NEW: reusable button (FR-003,004,013,015)
│   │       └── RoleSelectionDialog.kt # NEW: reusable dialog (FR-006,007,008,009,010,015)
│   └── util/
│       └── AnalyticsHelper.kt         # NEW: thin Firebase Analytics wrapper (FR-014) [or @Singleton in di/]
├── res/drawable/
│   └── ic_google.xml                  # NEW: 4-color Google "G" vector (FR-013)
└── ../build.gradle (app)              # EDIT: real GOOGLE_WEB_CLIENT_ID (FR-011)
```

**Structure Decision**: Single Android module. New shared composables live in `presentation/auth/components/` (per Rule 1, feature-local). `AnalyticsHelper` is a `util/` singleton injected via Hilt so it is testable and ViewModel-only (Screens never call it directly). All edits stay inside the `auth` feature boundary; no cross-feature or data-layer churn.

## Complexity Tracking

> No constitution violations. Section intentionally empty.

---

# Implementation Plan — Edit 2: Persistent Button, Auth Verification, Backend Integration

**Date**: 2026-06-24 | **Amends**: original plan above | **Spec**: [spec.md](./spec.md) (US4, US5, FR-017–025, SC-008–010)

## Summary (Edit 2)

Three deltas on top of the shipped T001-T015 work:

1. **Persistent button (US4 / FR-017–019)** — bug: button flashes then disappears ~3s after render. Root cause: `AuthViewModel.checkGoogleOAuthStatus()` overwrites the `isGoogleSignInEnabled = true` default with `false` (the status endpoint is unreachable/absent, and `AuthRepository.isGoogleOAuthEnabled()` returns `false` on any failure). Both screens gate the button on that flag (`LoginScreen.kt:173`, `RegisterScreen.kt:248`). **Fix: remove the status check and the visibility gate entirely** — button always rendered. Add a regression UI test.

2. **Auth verification (US5 / FR-020–021, SC-009–010)** — hybrid: automate button-persistence (FR-019) + client-ID assertion (FR-020); manual checklist (quickstart §17–21) for the live token-exchange → DB-persistence → no-duplicate chain.

3. **Backend integration doc (FR-024–025)** — `backend-google-auth-integration.md` instructs the **backend team** (separate FastAPI repo) how to verify the Google ID token and create-or-fetch the user on **both** `/oauth/google` (primary) and `auth/login`/`auth/register` (`id_token` fallback). **No backend code lives in this Android repo.**

## Technical Context (Edit 2 delta)

- **No new dependencies.** Pure deletion + test addition on the client.
- **Client API surface unchanged for the primary path**: app keeps calling `POST /oauth/google`. The `id_token` fallback on login/register is a **backend capability** (doc deliverable); client DTO changes for it are **out of scope for this plan** (deferred — current client already authenticates via `/oauth/google`).
- **Files touched (client)**: `AuthViewModel.kt` (remove `checkGoogleOAuthStatus()` + its call in `init`), `AuthUiState` (drop `isGoogleSignInEnabled`), `LoginScreen.kt` + `RegisterScreen.kt` (remove `if (uiState.isGoogleSignInEnabled)` gate), one new Compose UI test. `AuthRepository.isGoogleOAuthEnabled()` + `ApiService.getGoogleOAuthStatus()` may be left dead or removed (low-risk cleanup).
- **Doc deliverable**: `backend-google-auth-integration.md` (already authored).

## Constitution Check (Edit 2 re-evaluation)

| Rule / Principle | Status | Notes |
|---|---|---|
| Backend "NOT modified" (Platform Constraints) | ✅ **Honored in this repo** | Edit 2 ships **zero** backend code here. `backend-google-auth-integration.md` is an instruction doc for the separate FastAPI repo/team. The Android client still conforms to the existing `/oauth/google` contract; the login/register `id_token` fallback is a backend-team recommendation, not implemented client-side in this plan. |
| P-V "Firebase is NOT auth source of truth — FastAPI owns all accounts/tokens" | ✅ **Reinforced** | Q2 clarification (FastAPI DB = source of truth; Firebase only verifies the ID token) restates this principle verbatim. Verification surface is the DB, not the Firebase console. |
| R10 errors via Snackbar | ✅ | FR-023 persistence-failure path reuses the existing Snackbar error channel. |
| R4 ViewModel→Repo→safeApiCall→ApiService | ✅ | Removing the status call only deletes a path; remaining auth path untouched. |
| P-I Child Safety/Privacy | ✅ | Auth metadata only; no monitored data. Unchanged from original. |
| P-II MVVM/state invariants | ✅ | Dropping a UiState field via `data class` copy; `_uiState.update{}` unchanged. |

**Result: PASS.** The only constitution-adjacent risk — backend modification — is resolved by scoping backend work as an out-of-repo documentation deliverable. No violation, no Complexity Tracking entry required.

## Codebase audit — Edit 2

| Item | State | Action |
|---|---|---|
| `AuthViewModel.checkGoogleOAuthStatus()` (line 87) + `init` call (line 81) | present, causes bug | **remove** |
| `AuthUiState.isGoogleSignInEnabled` (line 35) | present, gates button | **remove field** |
| `LoginScreen.kt:173` `if (uiState.isGoogleSignInEnabled)` | gates button | **remove gate** — render always |
| `RegisterScreen.kt:248` `if (uiState.isGoogleSignInEnabled)` | gates button | **remove gate** — render always |
| `AuthRepository.isGoogleOAuthEnabled()` (line 218) | present, returns false on fail | dead after removal → delete (optional cleanup) |
| `ApiService.getGoogleOAuthStatus()` | present | unused after removal (may keep) |
| Compose UI test asserting button persists | absent | **create (FR-019)** |
| Client-ID assertion test (FR-020) | absent | **create** (assert `BuildConfig.GOOGLE_WEB_CLIENT_ID` non-placeholder) |
| `backend-google-auth-integration.md` | authored | done (FR-024) |

## Phase 0 — Research (Edit 2)

All unknowns were resolved in the `/speckit-clarify` session (2026-06-24); no open NEEDS CLARIFICATION. See [research.md](./research.md) Edit-2 section for the four decisions (dual-endpoint, DB source-of-truth, status-check removal, hybrid verification).

## Phase 1 — Design & Contracts (Edit 2)

- **data-model.md**: `AuthUiState` loses `isGoogleSignInEnabled`; button visibility is now unconditional (no state input). See data-model Edit-2 note.
- **contracts/**: `oauth-google.md` `/status` endpoint is now client-unused (noted). New `backend-google-auth-integration.md` documents the create-or-fetch contract for both endpoints.
- **quickstart.md**: scenarios 13–21 added (persistent button + verification).
- **Agent context**: `CLAUDE.md` plan reference already points to this plan.md — no change.
