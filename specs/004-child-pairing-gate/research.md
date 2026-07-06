# Research: Child Pairing Gate for Monitoring

**Feature**: 004-child-pairing-gate | **Date**: 2026-07-06

No NEEDS CLARIFICATION markers existed in the Technical Context — the feature directive locked the design. Research below records the decisions and the codebase findings that ground them.

## D1. Where to store the paired flag

- **Decision**: New boolean `hasLinkedParent` in existing `PreferencesManager` (EncryptedSharedPreferences), key `has_linked_parent` in `Constants.KEY_*` block, default `false`.
- **Rationale**: Matches every existing boolean gate in the codebase (`isDeviceRegistered`, `monitoringConsentGranted`, `isContentFilteringEnabled` — PreferencesManager.kt:76-113). Encrypted at rest (Principle V). `clearAll()` (PreferencesManager.kt:231-233) already wipes it on logout, so a new account starts gated with zero extra code.
- **Alternatives considered**: DataStore (project has it at 1.0.0 but PreferencesManager is the established singleton for gates — mixing stores for one boolean adds inconsistency); Room (overkill for one flag); in-memory only (fails the offline-deterministic requirement — flag must survive process death).

## D2. Gate placement

- **Decision**: Single gate point inside `MonitoringService.onStartCommand`, replacing the existing child-start block (MonitoringService.kt:253-263), evaluated AFTER the existing consent gate (`shouldRunMonitoring`, line 224).
- **Rationale**: Every start path (boot, restart receiver, sync, app open, manual) already funnels through `onStartCommand` — the consent gate comment at lines 218-223 documents this funnel property. One gate point = auditable, no scattered checks. Ordering after consent means pairing never bypasses the Play prominent-disclosure gate.
- **Alternatives considered**: Gating inside `startEnforcementLoop()`/`startImageMonitoring()` (rejected: hard guardrail forbids modifying them; also splits one decision into two places); gating in `BootReceiver`/`ServiceRestartReceiver` (rejected: receivers are out of scope and it would miss the in-app start path).

## D3. Refresh trigger & outcome semantics

- **Decision**: One-shot background refresh from `GET /family/parents` launched on the existing `serviceScope`, only when the cached flag is false (unpaired branch). Outcomes: 200 + non-empty → flag true + start monitoring at runtime; 200 + empty → flag false (confirmed unpair); network error → flag untouched.
- **Rationale**: Locked by the feature directive. The asymmetry (error never mutates) is the tamper-resilience property: a child toggling airplane mode cannot disable an already-paired device, and only an authenticated 200-empty (parent genuinely unlinked) de-escalates.
- **Alternatives considered**: Periodic re-poll for paired devices (rejected: outside locked design; unpair propagation explicitly deferred to a push-signal follow-up — backend coordination item #4); refreshing in `SyncWorker` (rejected: workers are hard-guardrailed out of scope).
- **Known consequence (documented, accepted)**: a paired device never re-polls at service start, so a confirmed unpair takes effect only via a start where the flag is already false or via logout. Flagged in plan.md Design Notes.

## D4. Repository shape & Rule 9 deviation

- **Decision**: Two functions in `FamilyRepository` — `getLinkedParents(): NetworkResult<List<LinkedParent>>` (constitution-compliant raw call) and `refreshLinkedParentStatus(): Boolean` (state-collapsing convenience for the service gate). `PreferencesManager` injected into the repository constructor.
- **Rationale**: Rule 7 requires zero business logic in services; the three-way outcome classification is business logic, so it must live in the repository. That forces a non-`NetworkResult` return for the collapsed answer — justified in plan.md Complexity Tracking. `FamilyRepository` is already `@Singleton` with constructor injection (FamilyRepository.kt:18-21); `PreferencesManager` is `@Singleton @Inject` (PreferencesManager.kt:17-19), so Hilt resolves the new param with no DI-module change (DI modules are hard-guardrailed).
- **Alternatives considered**: `NetworkResult<Boolean>` return (rejected: pushes error→keep-cached classification into the service, violating Rule 7); new dedicated repository (rejected: hard guardrail — only FamilyRepository may change).

## D5. DTO tolerance

- **Decision**: `LinkedParent` DTO in existing `Models.kt`, all five fields nullable, `@SerializedName` snake_case Gson style, `// TODO(backend)` marker on field names.
- **Rationale**: Directive forbids inventing the response schema. Nullable fields mean an unexpected backend shape degrades to nulls instead of a Gson crash — and since the gate only tests `list.isNotEmpty()`, field values are irrelevant to correctness. `Models.kt` already imports `java.util.Date` and uses `@SerializedName("created_at")` broadly (e.g., Models.kt:210-211).
- **Alternatives considered**: Separate file (rejected: directive says add into existing Models.kt); non-null fields with defaults (rejected: a missing field would still parse but hide the mismatch; nullable + TODO is more honest).

## D6. `when` exhaustiveness over NetworkResult

- **Finding**: `NetworkResult` is sealed with three variants — `Success`, `Error`, `Loading` (NetworkResult.kt:7-10). `safeApiCall` never emits `Loading`, but the `when` in `refreshLinkedParentStatus` must still be exhaustive.
- **Decision**: `else -> preferencesManager.hasLinkedParent` — behaves like Error (flag unchanged). Matches the directive's provided implementation.

## D7. Service injection style

- **Finding**: `MonitoringService` already uses `@Inject lateinit var` field injection for repositories/controllers (e.g., `lockOverlayController`, MonitoringService.kt:68-69) under `@AndroidEntryPoint`.
- **Decision**: `@Inject lateinit var familyRepository: FamilyRepository` — same pattern, no DI-module change.

## D8. Verification approach

- **Decision**: Manual log-based verification via Timber output + `./gradlew assembleDebug` build checkpoints after each phase. No automated tests.
- **Rationale**: Explicit directive ("do not add tests"). Expected log lines per scenario are tabled in plan.md Verification.
