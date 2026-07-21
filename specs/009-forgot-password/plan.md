# Implementation Plan: Forgot Password

**Branch**: `009-forgot-password` | **Date**: 2026-07-16 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/009-forgot-password/spec.md`

## Summary

Add a two-step password recovery flow to the parent auth experience: (1) request a reset code by email via `POST auth/forgot-password`, (2) redeem the 6-digit code with a new password via `POST auth/reset-password`, then auto-navigate back to Login with a success snackbar. Implemented as a new `presentation/forgotpassword/` feature package (one ViewModel, one UiState, two-step single navigation destination), two new `ApiService` endpoints, two request models, and two `AuthRepository` functions — all following the existing MVVM + `safeApiCall{}` → `NetworkResult<T>` pipeline, Haris design system components, and full EN/AR localization with RTL support.

## Technical Context

**Language/Version**: Kotlin (JDK 21 toolchain, `jvmTarget 17`), Jetpack Compose

**Primary Dependencies**: Compose BOM 2023.10.01, Material 3, Hilt 2.51.1, Retrofit 2.9.0, OkHttp 4.12.0, Navigation Compose 2.7.6, Coroutines 1.7.3, Timber 5.0.1 (all locked per constitution)

**Storage**: None — flow is fully transient (email held in ViewModel state only; code and passwords never persisted)

**Testing**: JUnit4 unit tests for ViewModel + repository (existing test setup), manual quickstart validation on emulator

**Target Platform**: Android `minSdk 26` / `targetSdk 35`

**Project Type**: Mobile app (single Android module `app/`)

**Performance Goals**: UI responds instantly to input; network calls show loading state; no ANR (all IO on `Dispatchers.IO`)

**Constraints**: Unauthenticated endpoints (user signed out — no auth token attached; `AuthInterceptor` must not block these paths); anti-enumeration message shown verbatim from server; reset code/new password never logged (Principle V)

**Scale/Scope**: 2 new screens (email entry, code + new password), 1 ViewModel, 2 endpoints, ~14 new string resources × 2 locales

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| # | Rule | Status | Notes |
|---|------|--------|-------|
| I | Child Safety & Privacy First | ✅ PASS | No child data touched. Parent account recovery only. No new data collection; code/password transient, never logged (FR-013). |
| II | MVVM + Clean Architecture | ✅ PASS | New `presentation/forgotpassword/` package: `ForgotPasswordViewModel` + `ForgotPasswordUiState` + screens. Repository via `AuthRepository`. No logic in Composables. |
| III | Two-Role Architecture | ✅ PASS | Pure REST client flow, pre-auth. No enforcement logic. Works for any account role recovering access. |
| IV | Native Services & Permissions | ✅ PASS | No services, workers, receivers, or permissions touched. |
| V | Defense-in-Depth Security | ✅ PASS | No tokens involved (pre-auth). No secrets committed. TLS + CertificatePinner already applied at OkHttp level. Code/password excluded from Timber logs. |
| R1 | Feature sub-package | ✅ | `presentation/forgotpassword/` with ViewModel/UiState/Screens |
| R2 | `@HiltViewModel` | ✅ | `ForgotPasswordViewModel @Inject constructor(AuthRepository, @ApplicationContext)` |
| R3 | `@Singleton` repository | ✅ | Reuses existing `@Singleton AuthRepository` — no new repository needed |
| R4 | ViewModel → Repository → `safeApiCall{}` → `ApiService` | ✅ | Both new calls follow the chain exactly |
| R5 | No tokens in VM/Screens | ✅ | Endpoints unauthenticated; no token access anywhere in flow |
| R6 | Special permissions via settings routing | ✅ N/A | None used |
| R7 | Zero business logic in services | ✅ N/A | No services touched |
| R8 | `@HiltWorker` workers | ✅ N/A | No workers |
| R9 | `NetworkResult<T>` only | ✅ | `forgotPassword()` and `resetPassword()` return `NetworkResult<MessageResponse>` |
| R10 | Errors via Snackbar + `LaunchedEffect(uiState.error)` + `clearError()` | ✅ | Same pattern as `ChangePasswordScreen`; field-level validation errors shown inline, network errors via snackbar |
| R11 | ProGuard for new Service/Receiver/Worker | ✅ N/A | None added; new request models covered by existing Gson model keep rules |
| R12 | `noCompress "tflite"` intact | ✅ | `app/build.gradle` untouched |

**Gate result: PASS — no violations, Complexity Tracking empty.**

## Project Structure

### Documentation (this feature)

```text
specs/009-forgot-password/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/
│   └── auth-password-reset.md   # Endpoint contracts (from user-supplied API behavior)
└── tasks.md             # Phase 2 output (/speckit-tasks — NOT created by /speckit-plan)
```

### Source Code (repository root)

```text
app/src/main/java/com/safeguard/parentalcontrol/
├── data/
│   ├── model/Models.kt                        # MODIFY: + ForgotPasswordRequest, ResetPasswordRequest
│   ├── remote/ApiService.kt                   # MODIFY: + POST auth/forgot-password, + POST auth/reset-password
│   ├── remote/NetworkResult.kt                # MODIFY: parseErrorMessage handles 422 detail-array shape
│   └── repository/AuthRepository.kt           # MODIFY: + forgotPassword(email), + resetPassword(email, code, newPassword)
├── presentation/
│   ├── forgotpassword/                        # NEW feature package (Rule 1)
│   │   ├── ForgotPasswordViewModel.kt         # NEW: @HiltViewModel, single VM for both steps + 60s cooldown timer
│   │   ├── ForgotPasswordUiState.kt           # NEW: data class (step, email, isLoading, error, fieldErrors, cooldownSeconds, resetSuccess)
│   │   ├── ForgotPasswordScreen.kt            # NEW: step 1 — email entry
│   │   └── ResetPasswordScreen.kt             # NEW: step 2 — OTP boxes + new password + confirm
│   │   └── components/OtpCodeInput.kt         # NEW: 6-box OTP composable (auto-advance, paste, LTR-forced)
│   ├── auth/LoginScreen.kt                    # MODIFY: + "Forgot Password?" text button; + success snackbar on return
│   └── navigation/NavGraph.kt                 # MODIFY: + Screen.ForgotPassword route (shared VM across both steps), result back to Login
└── res/
    ├── values/strings.xml                     # MODIFY: + ~14 forgotpw_* strings (EN)
    └── values-ar/strings.xml                  # MODIFY: + same keys (AR)
```

**Structure Decision**: Single Android module, package-by-feature. New `presentation/forgotpassword/` package per Enforcement Rule 1. Both steps share one `ForgotPasswordViewModel` (email + cooldown state must survive step transition) hosted in a nested nav graph scoped to the flow. No new repository — `AuthRepository` already owns the `auth/*` endpoint family (login, register, change-password precedent).

## Complexity Tracking

No constitution violations. Section intentionally empty.
