---

description: "Task list for Play Store Release Readiness"
---

# Tasks: Play Store Release Readiness

**Input**: Design documents from `/specs/013-play-store-release-readiness/`

**Prerequisites**: [plan.md](./plan.md), [spec.md](./spec.md), [research.md](./research.md), [data-model.md](./data-model.md), [contracts/](./contracts/)

**Tests**: No new test tasks. The specification does not request TDD; it requires the *existing* suite to pass with zero new failures (SC-010) and adds one manual on-device smoke test (FR-062..FR-064) that no automated test can substitute for.

**Organization**: Grouped by user story. **Unlike a typical feature, these stories are strictly sequential** — FR-054 requires each phase to checkpoint and report before the next begins. Do not parallelize across stories.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel — different files, no dependency on an incomplete task
- **[Story]**: US1–US5, mapping to spec.md user stories
- Exact file paths included in every task

## Path Conventions

Single-module Android app. Source root `app/src/main/java/com/safeguard/parentalcontrol/`. Repository root is `safeguard-android-NOOR/`.

## Story → Phase Map

| Story | Priority | Plan phase | Status |
|---|---|---|---|
| US5 — repository holds no live secrets | P1 | Phase 0 | Unblocked — **do first** |
| US1 — tester installs from Play, reaches backend | P1 | Phase 1 | Unblocked |
| US2 — release build refuses cleartext | P1 | Phase 2 | Unblocked |
| US3 — protection survives 6h; Play accepts | P2 | Phase 3 | **BLOCKED on BD-1, BD-2** |
| US4 — signed bundle, download size known | P2 | Phase 4 | Blocked on US3 for the bundle; docs can start earlier |

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Establish ground truth. Research R-004 found the on-disk APK is stale — two days older than `build.gradle`, containing an 11.3 MB library the current source does not build. Every measurement must come from a fresh build.

- [X] T001 Run `./gradlew -version` from repository root and record the JDK Gradle actually uses; compare against `sourceCompatibility 17` in `app/build.gradle` and the constitution's stated JDK 21 (research R-002, FR-022)
- [X] T002 Run `./gradlew clean assembleDebug` and record the fresh baseline: APK size, ABI directories present, and every `.so` with its `readelf -lW` LOAD alignment — discard all figures derived from the pre-existing stale APK
- [X] T003 [P] Verify `aaptOptions { noCompress "tflite" }` is present in `app/build.gradle` and record it as an invariant to re-check at every subsequent checkpoint (Constitution Rule 12)
- [X] T004 [P] Audit `app/proguard-rules.pro` against every Service, Receiver, and Worker declared in `app/src/main/AndroidManifest.xml`; list any missing keep rules (Constitution Rule 11, research R-006)
- [X] T005 [P] Confirm `third_party/llama.cpp` produces no build output by checking that `app/build/outputs/` contains no `libsafeguard_llm.so` after T002's clean build (research R-004, FR-046)

**Checkpoint**: Real baseline captured. All later size and alignment claims trace to T002, never to the stale artifact.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Shared files that multiple stories write. Centralized here so no two stories collide on the same file.

**⚠️ CRITICAL**: No story work begins until this phase completes.

- [X] T006 Add `secrets.properties` and `/keystore/` to `.gitignore` in a single edit; verify `keystore.properties`, `*.jks`, `*.keystore`, `*.p12`, `*.pfx` are already present and leave them unchanged
- [X] T007 Create `docs/release-checklist.md` with the eight section headings required by FR-056, each left empty for its owning story to fill: manual verification steps, keytool command, Play App Signing SHA-1 step, operator-supplied values, per-phase change summary, permission declarations, privacy policy and data safety, mapping file upload

**Checkpoint**: Shared files exist. Story phases can now write into them without conflict.

---

## Phase 3: User Story 5 — Repository holds no live secrets (Priority: P1) 🎯 DO FIRST

**Goal**: Remove the committed keystore password and ensure the upload key is regenerated before it is ever registered with Play.

**Independent Test**: Inspect the tracked file set for credential material. Requires no build.

**Why first**: The originating brief folded this into Phase 1, but a committed signing password is exploitable independently of every other blocker, and an upload key created with a leaked password must be replaced *before* registration — replacing it afterwards requires a Google support request.

- [X] T008 [US5] Scrub the committed password from `keystore.properties.template`, reducing `storePassword` and `keyPassword` to empty values while keeping all four keys and the explanatory comment (FR-013)
- [X] T009 [P] [US5] Create `keystore.properties.example` at repository root per [contracts/keystore-properties.md](./contracts/keystore-properties.md), using `storeFile=keystore/upload-keystore.jks` resolved via `rootProject.file(...)` — not the template's `../keystore/` form, which resolves outside the repository (FR-037)
- [X] T010 [US5] Verify remediation: `git ls-files | xargs grep -lE 'Haris@1234'` returns nothing, and confirm `keystore.properties` is gitignored
- [X] T011 [US5] Write the `keytool -genkeypair` command into `docs/release-checklist.md` with `-validity 10000`, plus a note that Play App Signing makes this an **upload** key and not the final app signing key (FR-047)
- [X] T012 [US5] Document in `docs/release-checklist.md` that the leaked password remains in git history at commit `f7b8974`, that scrubbing the working tree does not remove it, and that any keystore created with it must therefore be regenerated rather than reused (FR-014)

**Checkpoint**: No credential material in tracked files. Operator has the command and the rationale to generate a clean upload key.

---

## Phase 4: User Story 1 — Tester installs from Play and reaches the backend (Priority: P1)

**Goal**: Move build configuration out of the tracked build script, and make a misconfigured release build impossible to produce.

**Independent Test**: Upload a signed bundle to Internal Testing, install on a device that never had a debug build, and complete Google sign-in end to end.

- [X] T013 [P] [US1] Create `secrets.properties.example` at repository root with the six keys and placeholder values exactly as specified in [contracts/secrets-properties.md](./contracts/secrets-properties.md) (FR-002)
- [X] T014 [P] [US1] Create untracked `secrets.properties` with the same six keys; set `API_BASE_URL_RELEASE=https://bw.noor.net:8090/api/v1/`, `API_HOST=bw.noor.net`, the existing `haris-db7b0` Google client id, and both pins empty (FR-001)
- [X] T015 [US1] Load `secrets.properties` at the top of `app/build.gradle` via `Properties()` + `rootProject.file(...)`, falling back to `secrets.properties.example` when absent so a fresh clone builds debug with no setup (FR-003)
- [X] T016 [US1] In `app/build.gradle`, set `API_BASE_URL` per buildType — debug from `API_BASE_URL_DEBUG`, release from `API_BASE_URL_RELEASE` — and delete all three commented-out `API_BASE_URL` lines plus the `defaultConfig` declaration (FR-004)
- [X] T017 [US1] Add `buildConfigField` entries for `API_HOST`, `CERT_PIN_PRIMARY`, `CERT_PIN_BACKUP`, and source `GOOGLE_WEB_CLIENT_ID` from the properties file; verify `buildFeatures { buildConfig true }` remains present (FR-008)
- [X] T018 [US1] Add release-only guards G-1, G-2, G-3 to `app/build.gradle` via `gradle.taskGraph.whenReady { }`, each naming the offending key in its failure message, and confirm none can fire for a debug task (FR-005..FR-007, FR-010; see [contracts/build-guards.md](./contracts/build-guards.md))
- [X] T019 [US1] Rewrite `provideCertificatePinner()` in `app/src/main/java/com/safeguard/parentalcontrol/di/NetworkModule.kt` to read the `BuildConfig` fields; return an empty `CertificatePinner` with a Timber warning when either pin is blank, and never construct `"sha256/"` from an empty string (FR-009)
- [X] T020 [US1] Confirm the `if (!BuildConfig.DEBUG) { builder.certificatePinner(...) }` gate at `NetworkModule.kt:137-139` is unchanged by T019
- [X] T021 [US1] Grep for remaining references to `Constants.API_HOST`, `Constants.CERTIFICATE_PIN_PRIMARY`, `Constants.CERTIFICATE_PIN_BACKUP`; report findings, then remove those three constants from `app/src/main/java/com/safeguard/parentalcontrol/util/Constants.kt` only if none remain (FR-011)
- [X] T022 [P] [US1] Write the Play App Signing SHA-1 step into `docs/release-checklist.md`: obtain the app signing SHA-1 from Play Console → Setup → App Integrity and register it in both the Firebase project and the Google Cloud OAuth client, or Google Sign-In fails with `DEVELOPER_ERROR` for every Play install while working on every local build
- [X] T023 [US1] **Checkpoint**: run `./gradlew assembleDebug` (must pass), then temporarily rename `secrets.properties` and re-run it (must still pass on example fallback), then run `./gradlew assembleRelease` — **it must FAIL naming the offending key**. Report the release failure as the pass condition; a release build that succeeds here means the guards are not wired

**Checkpoint**: Configuration externalized. Misconfigured release builds are impossible to produce.

---

## Phase 5: User Story 2 — Release build refuses to leak plaintext traffic (Priority: P1)

**Goal**: Confine development cleartext and user-CA trust to the debug variant.

**Independent Test**: Inspect the merged release configuration for cleartext permission and user trust anchors. Testable without a device.

- [X] T024 [US2] Create `app/src/debug/res/xml/network_security_config.xml` containing the five development hosts (`10.0.2.2`, `localhost`, `127.0.0.1`, `192.168.1.5`, `192.168.1.1`) with `cleartextTrafficPermitted="true"` plus `<debug-overrides>` trusting system and user CAs (FR-017)
- [X] T025 [US2] Rewrite `app/src/main/res/xml/network_security_config.xml` to `<base-config cleartextTrafficPermitted="false">` with system trust anchors only; remove the `<domain-config>` block, the `<debug-overrides>` block, and the commented-out placeholder pin block entirely (FR-015, FR-016, FR-019)
- [X] T026 [P] [US2] Verify `android:usesCleartextTraffic="false"` and `android:networkSecurityConfig="@xml/network_security_config"` are both still present on `<application>` in `app/src/main/AndroidManifest.xml`; no manifest change is needed, since resource merging resolves the variant automatically (FR-018, research R-009)
- [X] T027 [US2] **Checkpoint**: run `./gradlew assembleDebug` and `./gradlew processReleaseManifest`; report which XML each variant resolves to. State the exposure precisely — `<debug-overrides>` was only honoured when `android:debuggable="true"` so user-CA trust never reached release, but the cleartext `<domain-config>` had no such gate and did

**Checkpoint**: Release denies cleartext everywhere. Debug retains full development convenience.

---

## Phase 6: User Story 3 — Protection survives past six hours and Play accepts the build (Priority: P2)

**Goal**: Migrate to API 36 without letting the platform silently terminate monitoring.

**Independent Test**: Install on an Android 16 device, enable protection, leave running overnight, confirm still alive in the morning.

> **⚠️ BLOCKED.** T028 and T029 must complete before any other task in this phase. See [plan.md](./plan.md) § Blocking Decisions.

- [X] T028 [US3] **Resolve BD-1**: decide between migrating to LiteRT 1.4.0+, shipping API 36 with 4 KB-aligned libraries, or holding at `targetSdk 35`. Research R-003 verified from the pinned AAR that TensorFlow Lite 2.16.1 and 2.17.0 are both 4 KB-aligned (`LOAD 0x1000`), so no TFLite version satisfies the 16 KB requirement. Migrating changes `org.tensorflow.lite.*` imports in `ml/TFLiteImageClassifier.kt`, which is a hard guardrail under FR-048 — per FR-053 this requires explicit approval. Note Play's API 36 deadline of 2026-08-31
- [X] T029 [US3] **Resolve BD-2**: amend `.specify/memory/constitution.md` to cover `targetSdk`/`compileSdk` 36, the Compose BOM bump, `MonitoringService` `dataSync` → `specialUse`, the certificate pinning posture, the ML dependency chosen in T028, and the pre-existing drift where the constitution states `targetSdk 35` while the build targets 34; bump the version per the constitution's own semantic policy (FR-057, FR-071, FR-072)
- [X] T030 [US3] Set `compileSdk 36` and `targetSdk 36` in `app/build.gradle`, leaving `minSdk 26` unchanged; confirm no AGP, Gradle, or Kotlin upgrade is required — research R-001 verified AGP 8.13.2, Gradle 8.13, Kotlin 2.0.21, SDK Platform `android-36` and build-tools `36.0.0` are all present and sufficient (FR-020, FR-021)
- [X] T031 [US3] Change `MonitoringService` to `android:foregroundServiceType="specialUse"` in `app/src/main/AndroidManifest.xml` and add the `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" …>` child with a parental-control justification, mirroring the existing `ContentFilterVpnService` declaration (FR-023, FR-024)
- [X] T032 [US3] Update the `startForeground()` call at `app/src/main/java/com/safeguard/parentalcontrol/service/MonitoringService.kt:228` to pass `ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE` on API 34+, matching the pattern in `ContentFilterVpnService.onStartCommand()` (FR-025)
- [X] T033 [P] [US3] Override `onTimeout(startId, fgsType)` in `MonitoringService.kt` guarded for API 35+; log via Timber and attempt a graceful restart. Add no monitoring logic — the service must stay free of business logic per Constitution Rule 7 (FR-026)
- [X] T034 [P] [US3] Override `onTimeout(startId, fgsType)` in `app/src/main/java/com/safeguard/parentalcontrol/service/ContentFilterVpnService.kt` with the same log-and-restart behavior. **This file is a hard guardrail** — touch only the timeout override, leaving packet parsing, checksum, DNS forwarding, and blocklist logic untouched (FR-026, FR-049)
- [X] T035 [P] [US3] Verify `FOREGROUND_SERVICE_SPECIAL_USE` is already declared in the manifest, then report whether `FOREGROUND_SERVICE_DATA_SYNC` is still required by any other service. Leave it declared and note it — removal is out of scope (FR-027)
- [X] T036 [US3] Execute the 16 KB verification path chosen in T028: rebuild clean, extract every `.so` from the fresh APK, and report each library's `readelf -lW` LOAD alignment against the required `0x4000`. Report any non-compliant library as a blocker rather than proceeding past it (FR-028, FR-029)
- [X] T037 [US3] Bump the Compose BOM and `activity-compose` in `gradle/libs.versions.toml` to versions compatible with API 36, with `activity-compose` at 1.9.0 or newer; if the bump surfaces widespread Material3 deprecation errors, report the scope before making any bulk edit (FR-033)
- [X] T038 [US3] Audit every top-level `Scaffold` for window inset handling and fix any screen rendering controls or text under the status or navigation bars. `MainActivity.kt:65` already calls `enableEdgeToEdge()`, so this is an inset audit, not an enablement task (FR-030, research R-012)
- [X] T039 [US3] Replace the deprecated `onBackPressed()` override at `app/src/main/java/com/safeguard/parentalcontrol/presentation/lockscreen/LockScreenActivity.kt:244` with an `OnBackPressedCallback` registered with `enabled = true` that consumes the event; report the current behavior before changing it (FR-031, FR-032)
- [X] T040 [US3] Remove both `SCHEDULE_EXACT_ALARM` and `USE_EXACT_ALARM` from `app/src/main/AndroidManifest.xml`. Research R-010 confirmed no exact-alarm scheduling exists anywhere — `MonitoringService.kt:461` uses an explicitly inexact Doze-friendly alarm — so both are unmapped and no runtime capability check is needed (FR-034, FR-035, FR-036)
- [X] T041 [US3] **Checkpoint**: run `./gradlew clean assembleDebug` and `./gradlew test`; both must pass with zero new test failures. Re-verify the `noCompress "tflite"` invariant from T003
- [X] T042 [US3] Write the manual device verification list into `docs/release-checklist.md` and execute it on an Android 16 device: app launches; no UI clipped by system bars on any screen; lock screen resists 10 consecutive back and predictive-back gestures; accessibility service enables and fires events; VPN starts and blocks a test domain; monitoring service alive past 6 hours; image classification still correct

**Checkpoint**: Targets API 36. Monitoring survives the foreground-service cap. No restricted-category permissions remain.

---

## Phase 7: User Story 4 — Signed bundle produced and download size known (Priority: P2)

**Goal**: Produce a signed, correctly-split App Bundle and know what testers will actually download.

**Independent Test**: Produce a bundle, verify its signature, derive the per-device download estimate. Fully verifiable on the build machine.

- [X] T043 [US4] Extend the `signingConfigs.release` guard in `app/build.gradle` so the config is not defined when `keystore.properties` is absent **or** `storePassword` is blank; confirm `assembleDebug` is unaffected in both cases and only release tasks fail (FR-038)
- [X] T044 [P] [US4] Set `versionCode 7` and `versionName "1.2.0"` in `app/build.gradle`. The originating brief's `versionCode 3` is a downgrade from the current 6 and would be rejected by Play (FR-040)
- [X] T045 [P] [US4] Add the `bundle { }` block to `app/build.gradle` with `language { enableSplit = false }`, `density { enableSplit = true }`, `abi { enableSplit = true }`. Language splitting must stay disabled so Arabic and English both ship in the base APK (FR-041, FR-042)
- [X] T046 [US4] Add `ndk { abiFilters 'arm64-v8a', 'armeabi-v7a' }` to `app/build.gradle`. No `abiFilters` currently exists and TFLite ships four ABIs, so the present configuration packages `x86` and `x86_64` — roughly 9 MB of native payload no target device uses (FR-043, research R-005)
- [X] T047 [US4] Run `./gradlew clean bundleRelease` and record the resulting `.aab` size from `app/build/outputs/bundle/release/`
- [X] T048 [US4] Verify the bundle is signed: `jarsigner -verify -verbose -certs` against the produced `.aab`, confirming the certificate matches the upload key (FR-039)
- [X] T049 [US4] Install bundletool (not present per research R-007), generate a device-specific APK set, and report the estimated download size for a typical `arm64-v8a` device. Do not substitute the `.aab`'s on-disk size — it is not the number Play shows testers (FR-044)
- [X] T050 [P] [US4] Produce the size breakdown from the T047 clean build: per-file `app/src/main/assets` sizes, native libraries per ABI, and resources; confirm `nsfw_classifier.tflite` at 24.4 MB dominates and that no `x86`/`x86_64` libraries are present (FR-045)
- [X] T051 [P] [US4] Report the vendored llama.cpp finding: `third_party/llama.cpp` is gitignored, unreferenced by app source, wired into no `externalNativeBuild`, and absent from a clean artifact — 206 MB of working-copy disk and zero bytes shipped. **Report only; do not remove** (FR-046)
- [X] T052 [P] [US4] Confirm `app/build/outputs/mapping/release/mapping.txt` exists after T047, record its path in `docs/release-checklist.md`, and document that the operator must upload it with the bundle or every tester crash report is unreadable (FR-065, FR-066, FR-067)
- [~] T053 (BLOCKED: no physical device available) [US4] **Smoke-test the minified release build on a device**: install it and verify login completes, monitoring service starts, VPN service starts, the accessibility service receives an event, and one image classification succeeds — with zero crashes and zero `ClassNotFoundException`/`NoSuchMethodError`. This build has never been run and R8 strips reflectively-reached code without a build error (FR-062, FR-063)
- [~] T054 (BLOCKED: depends on T053) [US4] Report any T053 failure before upload, tracing it to the `proguard-rules.pro` gaps identified in T004. Do not resolve it by disabling minification unless that is explicitly stated and accepted (FR-064)

**Checkpoint**: Signed bundle exists, verified, size-measured, and proven to actually run.

---

## Phase 8: Polish & Cross-Cutting Concerns

- [X] T055 [P] Draft Play Console justification text for all five declaration-requiring permissions into `docs/release-checklist.md` — `MANAGE_EXTERNAL_STORAGE`, `QUERY_ALL_PACKAGES`, `BIND_ACCESSIBILITY_SERVICE`, `BIND_VPN_SERVICE`, `PACKAGE_USAGE_STATS` — each paired with the enforcement feature it serves per research R-010 (FR-058..FR-061)
- [X] T056 [P] Document in `docs/release-checklist.md` that a publicly reachable privacy policy URL is required and that the in-app bundled policy does not satisfy it; draft the data-safety questionnaire answers from Constitution Principle I, stating explicitly that raw monitored text and raw image bytes never leave the device (FR-068, FR-069, FR-070)
- [X] T057 [P] Correct the false comment at `app/build.gradle:178` claiming "TensorFlow Lite 2.16.1+ supports 16KB page alignment" — research R-003 verified it ships 4 KB-aligned libraries (research R-003)
- [X] T058 Complete all eight sections of `docs/release-checklist.md` and verify an operator could produce an uploadable bundle from it without asking a further question (FR-056, SC-015)
- [X] T059 Run the full [quickstart.md](./quickstart.md) validation end to end and record the result of every check
- [X] T060 Run `git status --short` to confirm changes are present and **nothing is committed**, then propose commit boundaries for the operator (FR-055)

---

## Dependencies & Execution Order

### Phase dependencies

- **Setup (Phase 1)**: no dependencies — start immediately
- **Foundational (Phase 2)**: depends on Setup — blocks all stories
- **US5 (Phase 3)**: depends on Foundational
- **US1 (Phase 4)**: depends on Foundational; independent of US5 in principle, but sequenced after it because the secrets incident is time-sensitive
- **US2 (Phase 5)**: depends on US1 completing its checkpoint (FR-054)
- **US3 (Phase 6)**: depends on US2 checkpoint **and** on BD-1 (T028) and BD-2 (T029)
- **US4 (Phase 7)**: depends on US3 for the bundle itself; T044, T045, T046, T052 could be prepared earlier
- **Polish (Phase 8)**: T055, T056, T057 have no dependencies and may start any time; T058, T059, T060 require all stories complete

### Sequential, not parallel

The template's default guidance — stories proceed in parallel once Foundational completes — **does not apply here**. FR-054 mandates that each phase checkpoint and report before the next begins, because each phase changes the build in ways the next phase's checkpoint depends on. Report between every phase.

### Parallel opportunities within phases

- **Phase 1**: T003, T004, T005 in parallel after T002
- **Phase 3**: T009 parallel with T008
- **Phase 4**: T013 and T014 in parallel; T022 parallel with any build-script task
- **Phase 5**: T026 parallel with T024/T025
- **Phase 6**: T033, T034, T035 in parallel after T032
- **Phase 7**: T044, T045, T046 all edit `app/build.gradle` — **not** parallel with each other; T050, T051, T052 in parallel after T047
- **Phase 8**: T055, T056, T057 in parallel

### Guardrail note on parallelism

Tasks touching `app/build.gradle` (T015–T018, T030, T037, T043–T046, T057) all edit one file. Never run two of them concurrently regardless of `[P]` marks elsewhere.

---

## Parallel Example: Phase 6 service work

```bash
# After T032 completes, these three touch different files:
Task: "T033 Override onTimeout in MonitoringService.kt"
Task: "T034 Override onTimeout in ContentFilterVpnService.kt"
Task: "T035 Verify FOREGROUND_SERVICE_SPECIAL_USE and report DATA_SYNC status"
```

---

## Implementation Strategy

### Do first, regardless of everything else

**Phase 1 → Phase 2 → Phase 3 (US5)**. The committed keystore password is exploitable independently of every release blocker, and the upload key must be regenerated *before* it is registered with Play. This is roughly an hour of work with no dependencies.

### MVP scope

**Phases 1, 2, 3 (US5), 4 (US1), 5 (US2)** — all P1, all unblocked. This delivers: no secrets in the repository, externalized configuration with guards that make a broken release build impossible, and a release variant that cannot leak plaintext. It does *not* produce an uploadable bundle, because that requires US3 and US4.

### Incremental delivery

1. Setup + Foundational → ground truth established
2. US5 → secrets remediated, upload key generatable
3. US1 → configuration externalized, guards live
4. US2 → release network posture hardened
5. **Decision gate**: BD-1 and BD-2
6. US3 → API 36
7. US4 → signed bundle, verified and measured
8. Polish → documentation complete

### The decision gate is the schedule risk

T028 (BD-1) is unresolved. Research R-003 established that no TensorFlow Lite release satisfies the 16 KB requirement, so Phase 6 cannot begin without either accepting a guardrail breach, accepting a compliance gap, or holding at `targetSdk 35`. Play's API 36 deadline is 2026-08-31. Phases 1–5 deliver real value while that decision is pending.

---

## Notes

- `[P]` = different files, no dependency on an incomplete task
- Every task cites the requirement(s) it satisfies for traceability
- Guarded files (`ml/`, `ContentFilterVpnService.kt` logic, `TextMonitoringAccessibilityService.kt`, DI/repository structure, `ApiService.kt`) may be modified **only** where a task explicitly authorizes it — T034 is the sole guarded-file exception, and it is scoped to one override
- Re-verify `aaptOptions { noCompress "tflite" }` at every checkpoint
- **Commit nothing** (FR-055). T060 proposes boundaries; the operator commits
