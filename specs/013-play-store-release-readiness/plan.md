# Implementation Plan: Play Store Release Readiness

**Branch**: `013-play-store-release-readiness` | **Date**: 2026-08-24 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/013-play-store-release-readiness/spec.md`

## Summary

Prepare the app's first Google Play upload (Internal Testing) by fixing four release blockers in
sequence: externalize build configuration and remediate a committed secret; split the network
security configuration so development conveniences never reach release; migrate to API 36; and
produce a signed, size-analysed App Bundle. Phase 0 research verified every premise directly rather
than trusting the originating brief — which surfaced one hard blocker (TensorFlow Lite is not
16 KB page-size compliant) and invalidated the existing size analysis (the on-disk APK is stale).

Phases 1, 2 and 4 are unblocked and can proceed immediately. **Phase 3 is blocked** on a decision
about the ML dependency, plus the constitution amendment that FR-071 already requires.

## Technical Context

**Language/Version**: Kotlin 2.0.21, Java bytecode target 17

**Primary Dependencies**: AGP 8.13.2, Gradle 8.13, KSP 2.0.21-1.0.28, Hilt 2.51.1, Retrofit 2.11.0,
OkHttp 4.12.0, Compose BOM 2023.10.01, Room 2.6.1, WorkManager 2.9.0, Firebase BOM 33.10.0,
TensorFlow Lite 2.16.1

**Storage**: Room (local), `EncryptedSharedPreferences` via `TokenManager` (tokens), DataStore
(preferences). Unchanged by this feature.

**Testing**: JUnit 4 + MockK + Turbine (unit); Espresso + Compose UI test (instrumented). Plus a
manual on-device smoke test of the minified release build (FR-062..FR-064) that no automated suite
can substitute for.

**Target Platform**: Android, `minSdk 26`, currently `compileSdk`/`targetSdk 34`, migrating to 36.
SDK Platform `android-36` and build-tools `36.0.0` are installed locally.

**Project Type**: Single-module native Android application (`app/`), package
`com.safeguard.parentalcontrol`.

**Performance Goals**: Not applicable — this feature changes packaging and configuration, not
runtime behavior. The one runtime-adjacent requirement (FR-023) is a correctness goal: the
monitoring service must not be terminated by the platform's six-hour foreground-service cap.

**Constraints**:
- Five hard guardrails (FR-048..FR-053): ML pipeline, VPN packet logic, accessibility monitoring
  logic, DI/repository architecture, backend API contracts. Manifest entries and `startForeground`
  calls for the two foreground services are the only authorized exceptions.
- Nothing may be committed (FR-055).
- Each phase checkpoints and reports before the next begins (FR-054).

**Scale/Scope**: ~72 functional requirements across 4 phases. Touches the build script, manifest,
two network security resources, two services, one activity, one DI module, one constants file, and
creates four new configuration/documentation files. No feature code, no new screens.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

Evaluated against SafeGuard Constitution v2.0.0 (all twelve SpecKit Enforcement Rules plus the five
Core Principles).

### Rules that pass unchanged

| Rule | Status | Note |
|---|---|---|
| 1. Feature sub-package structure | **N/A** | No presentation feature added |
| 2. `@HiltViewModel` only | **Pass** | No ViewModel added or modified |
| 3. Repositories `@Singleton` | **Pass** | Untouched (FR-051) |
| 4. ViewModel → Repository → `safeApiCall` → ApiService | **Pass** | Untouched (FR-051) |
| 5. Tokens only via `TokenManager` | **Pass** | Untouched |
| 6. Special permissions via settings routing | **Pass** | No permission-request flow changes |
| 7. Services contain no business logic | **Pass** | `onTimeout` overrides only log and request restart; no logic added |
| 8. Workers `@HiltWorker` + `CoroutineWorker` | **Pass** | No worker added |
| 9. `NetworkResult<T>` return type | **Pass** | Untouched |
| 10. Errors via Snackbar | **N/A** | No UI error paths added |
| 12. `aaptOptions { noCompress "tflite" }` | **Pass** | Must be preserved through all edits — explicitly verified at each checkpoint |

### Rule 11 — ProGuard rules for every Service/Receiver/Worker

**Status: Action required.** Not a violation, but this feature is the first to actually exercise
minification. Auditing `proguard-rules.pro` against the manifest is a Phase 4 prerequisite
(research R-006), and any gap found is a Rule 11 defect that predates this feature.

### Principle-level assessment

| Principle | Status |
|---|---|
| I. Child Safety & Privacy First | **Strengthened.** FR-015..FR-019 remove a cleartext path from release. FR-067 explicitly adds no data collection. FR-069 documents the existing data flow rather than changing it |
| II. MVVM + Clean Architecture | **Unaffected.** No layer boundary is touched |
| III. Two-Role Architecture | **Unaffected** |
| IV. Native Services & Permission Hygiene | **Deviation** — see below. Also *improved*: FR-035 removes two unmapped permissions, which the principle explicitly requires |
| V. Defense-in-Depth & Secrets Hygiene | **Deviation** — see below. Also *materially improved*: FR-013/FR-014 remediate a committed password, a P0 under this principle |

### Gate result

**CONDITIONAL PASS.** Phases 1, 2 and 4 pass outright. Phase 3 carries four constitutional
deviations, all recorded in Complexity Tracking and all resolved by the amendment that FR-071
already schedules as a task gating Phase 3 only.

## Blocking Decisions

> **RESOLVED 2026-08-24.** BD-1 → proceed to API 36, verify and **report** 16 KB non-compliance as
> a blocker; do not migrate the ML dependency. BD-2 → constitution amended to v2.1.0.
>
> **BD-1 rationale**: FR-028 requires that native libraries be *verified* and that non-compliant
> ones be *reported as blockers*. It does not require fixing them. Reporting therefore satisfies
> the requirement in full, while migrating to LiteRT would edit `ml/TFLiteImageClassifier.kt` — a
> hard guardrail under FR-048 that FR-053 says must not be crossed without approval. The narrower
> action satisfies the specification; the broader one violates it. LiteRT 1.4.0+ is recorded as the
> remediation path for a separate, ML-scoped feature.
>
> **Consequence, stated plainly**: the shipped bundle will contain 4 KB-aligned native libraries.
> On devices using 16 KB memory pages, TensorFlow Lite may fail to load, disabling image
> classification. Every other protection layer is unaffected. This is a known, documented gap —
> not an oversight — and it is the top item in the follow-up section of `docs/release-checklist.md`.

### BD-1 — TensorFlow Lite is not 16 KB page-size compliant *(new; not in the spec)*

Phase 0 research (R-003) verified directly from the pinned AAR that
`tensorflow-lite-2.16.1` ships `LOAD`-aligned-to-`0x1000` (4 KB) native libraries for every ABI.
16 KB compliance requires `0x4000`. TFLite 2.17.0 is also 4 KB. The first compliant release is
Google's successor package, **LiteRT `com.google.ai.edge.litert` 1.4.0+**.

Migrating to LiteRT changes the import namespace, which requires editing `ml/TFLiteImageClassifier.kt`
and the text-classification code — **hard guardrails under FR-048**. Per FR-053, this stops and asks.

| Option | Effect | Cost |
|---|---|---|
| **A.** Migrate to LiteRT 1.4.0+ | Achieves compliance; unblocks API 36 fully | Breaches the ML guardrail and the locked-version table. Requires re-verifying classification behavior against known inputs |
| **B.** Target API 36 with 4 KB libraries | Ships on schedule | Native loading may fail on 16 KB devices. For an app whose classifier is core enforcement, this is a silent protection failure — the exact class of defect Principle I forbids |
| **C.** Stay on `targetSdk 35` for this release | Sidesteps the blocker entirely; Phases 1, 2, 4 still deliver a shippable bundle | Play requires API 36 for submissions from **2026-08-31**. Viable only if the first upload lands before then |

Note the calendar pressure: today is 2026-08-24. Option C's window is **seven days**.

### BD-2 — Constitution amendment (already scheduled as FR-071)

Amend v2.0.0 to cover: `targetSdk`/`compileSdk` 36, the Compose BOM bump, `MonitoringService`
`dataSync` → `specialUse`, and the certificate-pinning posture. Must also reconcile the pre-existing
drift where the constitution states `targetSdk 35` while the build targets 34 (FR-072), and correct
the locked-version table for whichever ML dependency BD-1 selects.

## Project Structure

### Documentation (this feature)

```text
specs/013-play-store-release-readiness/
├── plan.md              # This file
├── spec.md              # Feature specification (72 FR, 19 SC)
├── research.md          # Phase 0 output — 12 verified findings
├── data-model.md        # Phase 1 output — configuration schemas and states
├── quickstart.md        # Phase 1 output — per-phase validation guide
├── contracts/
│   ├── secrets-properties.md      # Build configuration contract
│   ├── keystore-properties.md     # Signing credentials contract
│   └── build-guards.md            # Build-time validation contract
├── checklists/
│   └── requirements.md  # Spec quality checklist (16/16)
└── tasks.md             # Phase 2 output (/speckit-tasks — NOT created here)
```

### Source code (repository root)

Files this feature touches. Nothing outside this list may be modified (FR-052).

```text
safeguard-android-NOOR/
├── secrets.properties.example              # CREATE (tracked)
├── secrets.properties                      # CREATE (gitignored)
├── keystore.properties.example             # CREATE (tracked)
├── keystore.properties.template            # MODIFY — scrub committed password (FR-013)
├── .gitignore                              # MODIFY — add secrets.properties
├── docs/
│   └── release-checklist.md                # CREATE — the operator's single reference
└── app/
    ├── build.gradle                        # MODIFY — config loading, guards, abiFilters,
    │                                       #   bundle block, versionCode 7, compileSdk/targetSdk 36
    ├── proguard-rules.pro                  # AUDIT (Rule 11), MODIFY only if gaps found
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml         # MODIFY — MonitoringService type + property,
        │   │                               #   remove both exact-alarm permissions
        │   ├── res/xml/
        │   │   └── network_security_config.xml   # REWRITE — release-strict
        │   └── java/com/safeguard/parentalcontrol/
        │       ├── di/NetworkModule.kt             # MODIFY — pins from BuildConfig
        │       ├── util/Constants.kt               # MODIFY — remove dead pin constants
        │       ├── service/
        │       │   ├── MonitoringService.kt        # MODIFY — startForeground type, onTimeout
        │       │   └── ContentFilterVpnService.kt  # MODIFY — onTimeout ONLY (guarded file)
        │       └── presentation/lockscreen/
        │           └── LockScreenActivity.kt       # MODIFY — OnBackPressedCallback
        └── debug/res/xml/
            └── network_security_config.xml   # CREATE — dev cleartext + user CAs
```

**Structure Decision**: Single-module Android application. The existing layout is preserved
unchanged; this feature adds one new source set (`app/src/debug/res/xml/`) for variant-specific
resource override, which is the standard AGP mechanism and requires no manifest change (research
R-009). No new modules, no package restructuring — Constitution Principle II is untouched.

## Phase Sequencing

Ordering is fixed by FR-054. Each phase checkpoints and reports before the next begins.

| Phase | Scope | Blocked by | Checkpoint |
|---|---|---|---|
| **0** | Secrets remediation (FR-013, FR-014) | Nothing | `keystore.properties.template` holds no credential |
| **1** | Config externalization, pinning posture (FR-001..FR-012) | Nothing | `assembleDebug` passes; `assembleRelease` fails naming the offending key |
| **2** | Network security split (FR-015..FR-019) | Phase 1 | `assembleDebug` passes; merged release manifest resolves the strict config |
| **3** | API 36 migration (FR-020..FR-036, FR-071, FR-072) | **BD-1 and BD-2** | `clean assembleDebug` + `test` pass; manual device checks |
| **4** | Signing, bundle, size, docs (FR-037..FR-047, FR-058..FR-070) | Phase 3 for the bundle; docs can start earlier | Signed AAB verifies; smoke test passes; size recorded |

**Phase 0 is promoted ahead of Phase 1.** The originating brief folded the secrets fix into Phase 1,
but a committed signing password is exploitable independently of every other blocker, and an upload
key generated with it must be replaced *before* registration with Play rather than after. It is a
few minutes of work with no dependencies.

## Complexity Tracking

> Constitution Check produced deviations requiring justification.

| Violation | Why Needed | Simpler Alternative Rejected Because |
|---|---|---|
| `targetSdk`/`compileSdk` 36 vs constitution's pinned 35 | Play requires API 36 for submissions from 2026-08-31 (FR-020) | Staying at 34/35 means rejection at upload. The constitution's own value (35) is already ahead of the build's actual 34, so the document is stale regardless |
| Compose BOM bump vs locked-version table | `activity-compose` ≥ 1.9.0 is needed for supported edge-to-edge and predictive-back APIs (FR-030..FR-032) | Hand-pinning individual artifacts around a stale BOM produces version skew across the Compose graph, which is harder to reason about than one BOM move |
| `MonitoringService` `dataSync` → `specialUse` vs Principle IV's explicit `TYPE_DATA_SYNC` mandate | API 35+ imposes a cumulative 6-hour/24-hour cap on `dataSync`, after which protection stops silently (FR-023) | No alternative type exempts a 24/7 monitor. Splitting into multiple services to reset the budget is a workaround that fights the platform and multiplies the failure surface |
| Certificate pinning disabled vs Principle V's mandate to pin `bw.noor.net:8090` | Pinning is already inert (pins bound to a host never contacted, R-008). Enabling it with placeholder pins takes every tester offline (Q1) | Computing real pins now couples app releases to backend certificate rotation, with no rollback path for installed testers. FR-010's host-match guard preserves the ability to enable pinning safely later |
| **BD-1**: potential ML dependency migration vs guardrail FR-048 and the locked TFLite version | 16 KB compliance is mandatory at API 36 and unattainable on any TensorFlow Lite release (R-003) | Rebuilding TFLite from source with `max-page-size=16384` requires standing up a native build the project does not have, and vendoring a self-built ML runtime is a permanent maintenance burden |
