<!--
SYNC IMPACT REPORT
Version change: 1.0.0 → 2.0.0 (MAJOR)
Last Amended: 2026-06-23

Rationale: Complete restatement of the project platform. v1.0.0 treated this repo as a
read-only Flutter-rebuild reference. v2.0.0 establishes this Kotlin/Compose codebase as the
living production app. All principles and constraints rewritten accordingly.

Principles:
  I.   Child Safety & Privacy First (NON-NEGOTIABLE)          — retained, refined
  II.  MVVM + Clean Architecture Discipline (NON-NEGOTIABLE)  — REPLACED (was On-Device Intelligence)
  III. Two-Role Architecture (Parent Client / Child Agent)    — retained, refined
  IV.  Native Android Services & Permission Hygiene           — REPLACED (was Offline-First Sync)
  V.   Defense-in-Depth Security & Secrets Hygiene           — retained, expanded

Added sections:
  - Platform & Technology Constraints (rewritten from Flutter reference to Android-first)
  - SpecKit Enforcement Rules (12 non-negotiable rules for every feature)

Removed sections / changed stance:
  - "Forward target: Flutter rebuild" — REMOVED. This repo is the production app.
  - "This repo is read-only reference" — REMOVED. Active development occurs here.

Templates reviewed for alignment:
  ✅ .specify/templates/plan-template.md      (Constitution Check gate references this file; no structural changes needed)
  ✅ .specify/templates/spec-template.md      (privacy/data-flow notes align with Principle I)
  ✅ .specify/templates/tasks-template.md     (quality gates and task phases compatible)
  ✅ .specify/templates/checklist-template.md (no changes required)

Deferred placeholders: none. All tokens resolved.

AMENDMENT v2.0.0 -> v2.1.0 (MINOR) - 2026-08-24
Driver: feature 013-play-store-release-readiness (Play Store Release Readiness).

  1. SDK: targetSdk/compileSdk 35 -> 36. Google Play requires API 36 for submissions from
     2026-08-31. Also corrects pre-existing drift: the document said 35 while the build was
     actually on 34.
  2. Build: AGP 8.5.2 -> 8.13.2, Kotlin 2.0.21/KSP recorded. The document lagged the repository;
     no upgrade was performed, the stated versions were simply wrong.
  3. Principle IV: MonitoringService TYPE_DATA_SYNC -> TYPE_SPECIAL_USE. From API 35 a dataSync
     foreground service is capped at 6 cumulative hours per 24, after which the platform stops it.
     For a 24/7 monitor that is a silent end of protection - the failure mode Principle I exists to
     prevent. Both foreground services now override onTimeout() defensively.
  4. Locked versions: Compose BOM 2023.10.01 -> 2024.09.00, required for supported edge-to-edge and
     predictive-back APIs at API 36.
  5. Principle V: certificate pinning may ship disabled when no verified pin set exists. Pins move
     to the untracked secrets.properties; blank values disable pinning rather than constructing an
     unsatisfiable pin. A build-time guard rejects a release whose pin subject host differs from the
     host actually called - the defect that made pinning silently inert before this feature.

Not amended, deliberately: the ML pipeline guardrail. TensorFlow Lite 2.16.1 ships 4 KB-aligned
native libraries and is not 16 KB page-size compliant. Migrating to LiteRT 1.4.0+ would edit
ml/TFLiteImageClassifier.kt, which Principle-level guardrails forbid without approval. The
non-compliance is documented as a known blocker with a remediation path, not silently accepted.
-->

# SafeGuard Constitution

## Core Principles

### I. Child Safety & Privacy First (NON-NEGOTIABLE)

SafeGuard runs surveillance-grade enforcement on the devices of minors. Protecting the child
— and the child's privacy — outranks every other concern, including feature scope, velocity,
and convenience.

- Sensitive content (screen text, image pixels, browsing activity) MUST be analyzed
  **on the child's device**. Only derived signals — classification scores, category labels,
  alert metadata — may leave the device. Raw monitored text and raw image bytes MUST NOT be
  transmitted to or stored on the backend.
- Data collection MUST be the minimum required to enforce the active policy.
  "Collect now, decide later" is prohibited.
- Image/NSFW detection operates on **saved media only** (Downloads, Screenshots).
  Live screen-capture / live-frame scanning is OUT OF SCOPE and MUST NOT be introduced
  without a constitutional amendment. The backend `screenshot_captured` alert type is
  dormant and MUST remain so.
- Any feature that captures, stores, or transmits child data MUST document in its spec:
  what is collected, why, where it goes, and its retention policy — **before** implementation.

*Rationale:* The product's legitimacy rests entirely on being a guardianship tool, not a
data-harvesting one. A privacy regression here is existential, not cosmetic.

### II. MVVM + Clean Architecture Discipline (NON-NEGOTIABLE)

Every feature MUST conform to the established layered architecture and package-by-feature
structure. Shortcuts that blur layer boundaries accumulate unbounded technical debt in a
safety-critical product.

**Layer rules (enforced strictly):**
- **Presentation** (`presentation/<feature>/`) — Jetpack Compose UI + `@HiltViewModel` +
  `StateFlow<UiState>`. No business logic, no direct repository calls from Composables.
- **Data** (`data/repository/`, `data/remote/`, `data/model/`) — implements contracts.
  All network calls go through `ApiService` → `safeApiCall{}` → `NetworkResult<T>`.
  All repository `suspend fun` run on `Dispatchers.IO` via `withContext`.
- **Service / Worker / Receiver** — platform-layer only. Delegate ALL business logic to
  repositories. No HTTP calls from Services; no Room queries from BroadcastReceivers.
- **DI** (`di/`) — Hilt modules only. Manual instantiation of ViewModels, repositories, or
  workers is prohibited.

**State management invariants:**
- One `@HiltViewModel` per screen — never instantiate manually.
- One `StateFlow<[Feature]UiState>` per ViewModel — private `MutableStateFlow`, public
  `asStateFlow()`. UiState is always a `data class`.
- State updates MUST use `_uiState.update { it.copy(...) }` — never reassign the whole state.
- Screens MUST collect with `collectAsStateWithLifecycle()` — never `collectAsState()`.
- UI errors MUST surface via `Snackbar` through `LaunchedEffect(uiState.error)` — never
  `AlertDialog`. Always call `viewModel.clearError()` after display.

*Rationale:* A security app that runs platform services 24/7 cannot survive architectural
entropy. Clean, predictable layers make the codebase auditable and safe to extend.

### III. Two-Role Architecture (Parent Client / Child Agent)

SafeGuard is one product with two strictly separated roles. Enforcement decisions execute
on the child device; the backend is a policy store and alert sink.

- **PARENT** role is a thin REST management client: configure policy, review alerts, issue
  commands. It holds zero enforcement logic.
- **CHILD** role is the native OS enforcement agent: VPN DNS sinkhole, accessibility-based
  text monitoring, screen-time enforcement + remote lock, saved-image scanning.
- Enforcement decisions execute **on the child device** against locally cached policy.
  FCM/push is an optional accelerant — never a required control path. A child device MUST
  enforce rules when the parent app is offline or the backend is unreachable.
- Role boundaries MUST stay clean: parent-side code MUST NOT embed enforcement; child-side
  enforcement MUST NOT depend on the parent app being reachable.
- Child device post-auth flow is: login/register → `POST /devices` (one-time) → store
  `device_token` via `TokenManager` → start services → route user to permission setup.

*Rationale:* Enforcement that requires live connectivity or a parent session can be defeated
by going offline. Authority must reside where the child is.

### IV. Native Android Services & Permission Hygiene

The five protection layers (AccessibilityService, UsageStats, Overlay, Notifications,
Battery Optimization) are the core enforcement surface. They MUST be treated as
high-risk, always-on infrastructure.

**Service rules:**
- `MonitoringService` — `TYPE_SPECIAL_USE` foreground service. `stopWithTask="false"` in
  manifest. (Was `TYPE_DATA_SYNC` until v2.1.0; API 35+ imposes a cumulative 6-hour/24-hour
  runtime cap on `dataSync`, after which the platform stops the service and protection ends
  silently. `specialUse` is exempt. Both foreground services override `onTimeout()` defensively.) MUST be restarted on boot (`BootReceiver`), user unlock, and screen-on
  (`ServiceRestartReceiver`).
- `ContentFilterVpnService` — local DNS sinkhole. On disconnect: enqueue
  `TamperAlertWorker`. Broadcasts `ACTION_VPN_STATE_CHANGED` to UI.
- `TextMonitoringAccessibilityService` — reads `AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED`.
  Routes text through the two-stage ML pipeline (regex → TFLite). Reports via `POST /alerts`.
- Services MUST NOT contain business logic. All logic delegates to injected repositories.

**WorkManager rules:**
- All workers MUST be `CoroutineWorker` + `@HiltWorker` + `@AssistedInject`.
- Periodic workers MUST use `enqueueUniquePeriodicWork` with `ExistingPeriodicWorkPolicy.KEEP`.
- `TamperAlertWorker` is one-shot and MUST require `Constraints.CONNECTED`.

**Permission rules:**
- NEVER use runtime `requestPermissions()` for special permissions:
  `PACKAGE_USAGE_STATS`, `BIND_ACCESSIBILITY_SERVICE`, `BIND_VPN_SERVICE`,
  `SYSTEM_ALERT_WINDOW`.
- ALWAYS route the user to the exact system settings screen for each special permission.
  `ProtectionStatusHelper` is the single source of truth for checking all five protection
  statuses.
- Every new high-risk permission added to the manifest MUST map to a named enforcement
  feature in its spec. Unmapped permissions MUST be removed.
- ProGuard rules MUST be updated for every new Service, Receiver, or Worker added.

*Rationale:* Android's special-permission model cannot be worked around without permanently
breaking enforcement. Correct permission handling is what keeps the child protected.

### V. Defense-in-Depth Security & Secrets Hygiene

The app holds elevated OS privileges and family data; it MUST be hardened accordingly and
MUST keep secrets out of the repository.

- Secrets (`google-services.json`, signing keystore, `local.properties`, OAuth client IDs,
  API hosts) MUST NOT be committed. The repo ships placeholders only; real values are
  supplied locally or via CI secrets. Any committed secret is a P0 incident requiring
  immediate rotation.
- Network traffic to `bw.noor.net:8090` uses TLS. `CertificatePinner` is supported but MAY ship
  disabled when no verified pin set exists: pin values come from the untracked
  `secrets.properties`, and blank values disable pinning rather than producing an unsatisfiable
  pin. A build-time guard rejects any release whose pin subject host differs from the host the
  app actually calls, because such a pin is silently inert. At-rest
  secrets and tokens use `EncryptedSharedPreferences` (`TokenManager`). Plaintext token
  storage in any `SharedPreferences` is prohibited.
- `TokenManager` is the ONLY class that reads or writes tokens. ViewModels and Screens MUST
  NOT access tokens directly.
- `AuthInterceptor` owns the full JWT refresh lifecycle: proactive pre-expiry refresh,
  atomic `compareAndSet` guard on 401, synchronous retry. ViewModels MUST NOT call refresh
  manually. On unrecoverable refresh failure: `tokenManager.clearTokensDueToRevocation()`
  triggers UI redirect.
- Firebase is used ONLY for: Google Sign-In (`idToken` forwarded to backend), FCM push
  (`alert` and `sync` message types), and passive analytics. Firebase is NOT the auth source
  of truth — the FastAPI backend owns all accounts and tokens.

*Rationale:* Powerful privileges plus children's data is a high-value target. Layered
controls limit blast radius when any single layer fails.

## Platform & Technology Constraints

**This is the production codebase.** Active feature development occurs here.

- **Platform:** Native Android — Kotlin + Jetpack Compose
- **SDK:** `minSdk 26` (Android 8.0), `targetSdk 36`, `compileSdk 36` (Android 16)
- **Toolchain:** JDK 21, `jvmTarget = '17'`, Kotlin Compiler Extension `1.5.14`
- **Build:** Gradle 8.13 / AGP 8.13.2 / Kotlin 2.0.21 (KSP) — build via `./gradlew assembleDebug`
- **Backend:** FastAPI at `https://bw.noor.net:8090/api/v1` — NOT modified. Client conforms
  to existing contract. The Postman collection is the executable reference.

**Locked dependency versions** (do not upgrade without explicit rationale in the PR):

| Library | Version |
|---|---|
| Hilt | 2.51.1 |
| Retrofit | 2.9.0 |
| OkHttp | 4.12.0 |
| Compose BOM | 2024.09.00 |
| Navigation Compose | 2.7.6 |
| Room | 2.6.1 |
| WorkManager | 2.9.0 |
| hilt-work | 1.1.0 |
| Coroutines | 1.7.3 |
| Firebase BOM | 32.7.0 |
| TFLite | 2.16.1 |
| Credentials | 1.3.0 |
| DataStore | 1.0.0 |
| Timber | 5.0.1 |
| Coil Compose | 2.5.0 |

**Build config invariants:**
- `aaptOptions { noCompress "tflite" }` MUST always be present. Removing it silently
  corrupts model loading.
- Kotlin opt-ins MUST include `RequiresOptIn`, `ExperimentalCoroutinesApi`,
  `ExperimentalMaterial3Api`.

**ML pipeline:**
- Two-stage: Stage 1 regex (`TextPatternMatcher`) → Stage 2 TFLite. If Stage 1 flags,
  skip Stage 2 and report immediately.
- Text model I/O: `[1, 128]` int32 tokens → `[1, 7]` float32 sigmoid probabilities.
- Thresholds are constants: `TOXICITY_THRESHOLD = 0.5f`, `SEVERE_THRESHOLD = 0.4f`.
- `ContentClassifier` orchestrates both stages on a background thread. ML runs without
  model files present — fall back to regex/heuristic only.

## SpecKit Enforcement Rules

Every spec, plan, and implementation MUST satisfy all twelve rules. Violations MUST be
justified in the plan's Complexity Tracking section.

1. Every feature has its own sub-package under `presentation/<feature>/` containing
   `[Feature]ViewModel.kt`, `[Feature]UiState.kt`, and `[Feature]Screen.kt`.
2. Every ViewModel is annotated `@HiltViewModel`. Manual instantiation is prohibited.
3. Every repository is `@Singleton`. Repository-scoped-to-ViewModel is prohibited.
4. All API calls follow exactly: ViewModel → Repository → `safeApiCall{}` → `ApiService`.
   No layer may be skipped.
5. Tokens MUST NOT appear in ViewModels or Screens. Only `TokenManager` reads/writes tokens.
6. Special Android permissions (`PACKAGE_USAGE_STATS`, `BIND_ACCESSIBILITY_SERVICE`,
   `BIND_VPN_SERVICE`, `SYSTEM_ALERT_WINDOW`) are always granted via system settings routing
   — never via `requestPermissions()`.
7. Services (`MonitoringService`, `ContentFilterVpnService`,
   `TextMonitoringAccessibilityService`, `SafeGuardFirebaseMessagingService`) contain zero
   business logic; they delegate entirely to injected repositories.
8. Workers are always `@HiltWorker` + `CoroutineWorker` + `@AssistedInject`.
9. `NetworkResult<T>` is the only return type from repository functions. No raw exceptions,
   no nullable returns, no plain Booleans for network outcomes.
10. UI errors are always shown as `Snackbar` via `LaunchedEffect(uiState.error)`.
    `AlertDialog` for error display is prohibited. `viewModel.clearError()` is always called
    after display.
11. ProGuard rules are updated for every new Service, Receiver, or Worker added to the
    manifest.
12. `aaptOptions { noCompress "tflite" }` must remain in `app/build.gradle` at all times.

## Development Workflow & Quality Gates

- **Spec-driven flow:** work proceeds through SpecKit — `/speckit-constitution` →
  `/speckit-specify` → `/speckit-plan` → `/speckit-tasks` → `/speckit-implement`. Plans
  MUST pass the Constitution Check gate against all twelve enforcement rules before Phase 0
  research. Re-check after Phase 1 design.
- **Privacy & permission review:** every spec touching monitored data or OS permissions MUST
  include a data-flow note (Principle I) and a permission justification (Principle IV/V)
  before planning begins.
- **Testing priorities:** detection logic (both pipeline stages, including no-model
  fallback), auth/refresh lifecycle, service restart resilience, and WorkManager
  deduplication are the priority areas for automated tests.
- **Contract fidelity:** changes affecting the backend REST contract, sync choreography,
  or model I/O shape require explicit rationale and updates to authoritative references.
- **Logging:** `Timber.d/w/e` for all logging in non-service code. Services may use
  `Log.*` directly only when Timber has not yet been initialized (early boot path).

## Governance

This constitution supersedes all other practices for the SafeGuard product. When guidance
conflicts, the constitution wins.

- **Amendments** require a written rationale, a version bump per the policy below, and a
  migration note when existing specs, code, or contracts are affected. Loosening a
  NON-NEGOTIABLE principle (Principles I or II) requires explicit documented approval and
  is a MAJOR version bump.
- **Versioning (semantic):** MAJOR for removing/redefining a principle or other
  backward-incompatible governance change; MINOR for adding a principle/section or
  materially expanding guidance; PATCH for clarifications and wording that change no rule.
- **Compliance:** every plan and review verifies adherence to all twelve enforcement rules.
  Unjustified violations are removed, not annotated. Runtime guidance lives in agent context
  files, which MUST stay consistent with this constitution.

**Version**: 2.1.0 | **Ratified**: 2026-06-23 | **Last Amended**: 2026-08-24
