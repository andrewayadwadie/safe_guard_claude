# Feature Specification: Play Store Release Readiness

**Feature Branch**: `013-play-store-release-readiness`

**Created**: 2026-08-24

**Status**: Ready for planning — all clarifications resolved 2026-08-24

**Input**: User description: "Play Store Release Readiness — Phases 1 to 4. Fix four release blockers in order (config externalization, network security config split, targetSdk 34→36 migration, release signing + AAB), each phase gated by a checkpoint, with hard guardrails around ML/VPN/accessibility/DI/API-contract code."

---

## Pre-Spec Audit Findings *(Phase 0 — completed during specification)*

The originating brief described the repository as it existed at an earlier commit. A read-only
audit of the working tree found that **several stated blockers are already resolved, and one
stated instruction would break the Play upload**. Requirements below are written against the
*actual* current state, not the brief's description.

### Current toolchain and build facts

| Property | Actual value |
|---|---|
| AGP | 8.13.2 |
| Gradle wrapper | 8.13 |
| Kotlin | 2.0.21 (KSP 2.0.21-1.0.28) |
| Annotation processing | KSP (no kapt anywhere) |
| JDK / jvmTarget | source & target 17, `jvmTarget = '17'` |
| compileSdk / targetSdk / minSdk | 34 / 34 / 26 |
| versionCode / versionName | **6 / "1.1.4"** |
| Compose BOM | 2023.10.01 |
| activity-compose | 1.8.2 |
| Firebase BOM | 33.10.0 |
| TFLite | 2.16.1 |
| NDK / CMake / `externalNativeBuild` | **none configured** |
| `app/src/main/jniLibs` | **does not exist** |
| ABIs built | no `abiFilters`; whatever the AAR dependencies ship |
| `app/src/main/assets` total | **28 MB** (`nsfw_classifier.tflite` alone is 24.4 MB) |
| Built debug APK | 64 MB |
| `app/google-services.json` | present, correctly untracked |

### Claims in the brief that the audit contradicts

| Brief asserted | Audit found |
|---|---|
| `API_BASE_URL` hardcoded to `http://192.168.1.5:8090/api/v1/` in debug and release | **Not true.** Those lines are commented out. All three `buildConfigField` sites resolve to `https://bw.noor.net:8090/api/v1/` |
| `GOOGLE_WEB_CLIENT_ID` is the literal placeholder `YOUR_WEB_CLIENT_ID...` | **Not true.** A real client ID for Firebase project `haris-db7b0` is present |
| Release build throws `SSLPeerUnverifiedException` on every call | **Overstated.** Pins are placeholders (`AAAA…=` / `BBBB…=`) but are registered against `Constants.API_HOST = "api.safeguard.app"`, a host the app never contacts. Pinning is currently an inert no-op — and becomes a total outage the moment `API_HOST` is corrected to `bw.noor.net` |
| No release `signingConfig` exists | **Not true.** One exists, guarded on `keystore.properties` presence |
| `.gitignore` has the keystore lines commented out | **Not true.** `*.jks`, `*.keystore`, `*.p12`, `*.pfx`, `keystore.properties` are all active |
| Set `versionCode 3`, `versionName "1.2.0"` | **Would break the upload.** Current versionCode is 6. Play rejects any bundle whose versionCode is not greater than the highest previously uploaded |
| `kapt` may break on the new Kotlin version | Not applicable — the project is already fully on KSP |
| Upgrade AGP to "8.9.x or newer" | Already on 8.13.2; compileSdk 36 is supported by the current toolchain with no upgrade |
| Vendored llama.cpp ships native libraries into the APK | **Not true.** `third_party/llama.cpp` (206 MB) is gitignored, has zero references from app source, and is wired into no CMake/`externalNativeBuild` block. It contributes **0 bytes** to the APK |
| `enableEdgeToEdge()` needs to be added to `MainActivity` | Already called at `MainActivity.kt:65` |

### Claims the audit confirms

- `res/xml/network_security_config.xml` carries a `cleartextTrafficPermitted="true"` `domain-config`
  (`10.0.2.2`, `localhost`, `127.0.0.1`, `192.168.1.5`, `192.168.1.1`) plus `<debug-overrides>` and a
  commented-out placeholder pin block — and it is the **only** copy, so it applies to release.
  No `app/src/debug/` source set exists.
- `MonitoringService` is declared `android:foregroundServiceType="dataSync"` and calls bare
  `startForeground(id, notification)` with no type argument. `ContentFilterVpnService` is already
  `specialUse` with the required `<property>` element.
- Both `SCHEDULE_EXACT_ALARM` and `USE_EXACT_ALARM` are declared in the manifest.
- `LockScreenActivity` overrides the deprecated `onBackPressed()` (line 244).
- `FOREGROUND_SERVICE_SPECIAL_USE` is already declared.

### New blocker the brief did not mention

**`keystore.properties.template` is tracked in git and contains a live-looking keystore password
(`Haris@1234`), committed in `f7b8974`.** Under Constitution Principle V this is a P0 secrets
incident: the file must be scrubbed to empty placeholders, and any keystore already created with
that password must be regarded as compromised and regenerated before it is used as an upload key.

### Constitution deviations this feature requires

The constitution (v2.0.0) pins `targetSdk 35 / compileSdk 35`, `AGP 8.5.2 / Gradle 8.13`,
`Compose BOM 2023.10.01`, and mandates `MonitoringService` be `TYPE_DATA_SYNC` with
`CertificatePinner` on `bw.noor.net:8090`. This feature knowingly departs on four points —
SDK 36, Compose BOM bump, `dataSync` → `specialUse`, and pinning posture. Each is recorded in
FR-057. The amendment resolving them is a task within this feature and gates Phase 3 only
(FR-071); the amendment also reconciles the pre-existing SDK-level drift between the constitution
and the build (FR-072).

---

## Clarifications

### Session 2026-08-24

- Q: Are Play Console policy declarations for the app's restricted permissions (All files access, package visibility, Accessibility API, VPN, usage stats) in scope for this feature? → A: In scope — enumerate them and draft the justification text as operator documentation; no code change and no automation.
- Q: How far should verification of the minified release build go, given minification has never been exercised? → A: Install the release build on a device and smoke-test the guarded paths — login, monitoring service start, VPN start, accessibility events, and one image classification.
- Q: Should crash visibility be added for the Internal Testing track, given the app has no crash reporting? → A: Retain and upload the code-shrinking mapping file so store-console crash reports are readable. No new SDK and no new data collection.
- Q: The privacy policy ships as an in-app asset, which does not satisfy the store's requirement for a public URL, and the data-safety questionnaire is unaddressed. In scope? → A: In scope as operator documentation — record the required public policy URL and draft the data-safety answers from the constitution's data-flow rules. No automation.
- Q: Is the constitution amendment required by FR-057 work inside this feature or a prerequisite to it? → A: A task inside this feature, gating Phase 3 only. Phases 1, 2, 4 and the secrets remediation proceed without waiting on it.

---

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A tester installs the app from Play and it reaches the backend (Priority: P1)

A recruited internal tester accepts the Play Internal Testing invitation, installs the app from the
Play Store on their own phone, opens it, and signs in with Google. The app reaches the production
backend over TLS, authenticates, and lands on the home screen.

**Why this priority**: This is the entire point of the release. If sign-in or the backend connection
fails on a Play-installed build, the testing track delivers nothing and every later phase is moot.
It is also the failure mode most likely to slip through, because it cannot be reproduced with a
locally-signed debug build — Play App Signing re-signs the bundle with a different certificate than
the upload key, which changes the SHA-1 that Google Sign-In validates against.

**Independent Test**: Upload a signed bundle to Internal Testing, install it on a device that has
never had a debug build, and complete a Google sign-in end to end. Delivers a verified, distributable
build even if no other phase ships.

**Acceptance Scenarios**:

1. **Given** a build installed from the Play Internal Testing track, **When** the tester taps
   "Sign in with Google" and picks an account, **Then** authentication succeeds and the app proceeds
   past the login screen without a `DEVELOPER_ERROR`.
2. **Given** that same installed build, **When** the app issues any backend request, **Then** the
   request completes over HTTPS and no request is attempted in cleartext.
3. **Given** a release build whose configured backend URL is not HTTPS or still holds an unfilled
   placeholder, **When** a release artifact is assembled, **Then** the build fails before producing
   an artifact and the error message names the specific offending configuration key.
4. **Given** a fresh clone of the repository with no local secrets file, **When** a developer runs a
   debug build, **Then** the build succeeds using committed example values.

---

### User Story 2 - The release build refuses to leak plaintext traffic (Priority: P1)

A security reviewer inspects the shipped artifact and confirms that the development conveniences
which make local work possible — cleartext to loopback and LAN addresses, trusting user-installed
certificate authorities — exist only in the debug variant and are absent from the release artifact.

**Why this priority**: Trusting user CAs in a shipping build lets anyone with device access
man-in-the-middle a monitored child's traffic. In a product whose legitimacy rests on being a
guardianship tool rather than a surveillance liability, this is a Principle I violation, not a
hardening nicety. It is P1 alongside Story 1 because the fix is small and the exposure is severe.

**Independent Test**: Inspect the merged release manifest and the resource it resolves, and confirm
no cleartext permission and no user trust anchors. Testable without a device.

**Acceptance Scenarios**:

1. **Given** the merged release configuration, **When** its network security policy is inspected,
   **Then** cleartext traffic is denied for all destinations, only system certificate authorities
   are trusted, and no development host exemptions appear.
2. **Given** the merged debug configuration, **When** it is inspected, **Then** loopback and LAN
   development hosts are reachable in cleartext and user-installed certificate authorities are
   trusted.
3. **Given** the release artifact, **When** its network policy is inspected, **Then** it contains no
   certificate pins — pinning is enforced in exactly one place, the HTTP client layer.

---

### User Story 3 - Protection survives past six hours and the app is accepted by Play (Priority: P2)

A parent enables protection on a child's device in the morning and leaves it running. That evening,
and again the next morning, protection is still active — the monitoring service has not been
silently terminated by the platform. Separately, the submitted bundle clears Play's target-API and
restricted-permission checks without rejection.

**Why this priority**: Depends on Stories 1 and 2 being able to produce a build at all, so it is P2.
But the six-hour cumulative foreground-service cap that Android 15 applies to `dataSync` services is
the single most dangerous defect in this set: protection stops, no error is surfaced, and the parent
believes the child is still covered. A silent failure of the core safety promise is worse than a
loud one.

**Independent Test**: Install on an Android 16 device, enable protection, leave it running overnight,
and confirm in the morning that the service is still alive and still reporting.

**Acceptance Scenarios**:

1. **Given** protection is enabled on an Android 15+ device, **When** more than six cumulative hours
   of foreground-service runtime elapse within a 24-hour window, **Then** the monitoring service is
   still running and still reporting.
2. **Given** the platform nonetheless signals a foreground-service timeout, **When** that signal is
   received, **Then** the event is recorded in logs and the service attempts a graceful restart
   rather than terminating silently.
3. **Given** the app is running on an Android 16 device, **When** the user visits every screen,
   **Then** no interactive control or text is obscured by the status bar or the navigation bar.
4. **Given** a child device with the lock screen active, **When** the child performs a back gesture
   or a predictive-back swipe, **Then** the lock screen is not dismissed and remains full-screen.
5. **Given** the submitted bundle, **When** Play evaluates it, **Then** it is not rejected for
   targeting an insufficient API level, and it declares no permission restricted to app categories
   this product does not belong to.
6. **Given** the migrated build, **When** the existing automated test suite runs, **Then** it passes
   with no new failures.
7. **Given** the migrated build on a device that uses 16 KB memory pages, **When** the app launches
   and performs an image classification, **Then** it starts and classifies without a loader failure.

---

### User Story 4 - A signed bundle is produced and its download size is known (Priority: P2)

The release engineer runs one command, gets a signed Android App Bundle, verifies its signature,
and knows the actual over-the-air download size a tester on a typical 64-bit device will see —
not the bundle's on-disk size, which is much larger and misleading.

**Why this priority**: The gate on distribution. P2 rather than P1 only because it is inert without
Stories 1–3; nothing is worth uploading until the build is configured, secure, and accepted.

**Independent Test**: Produce a bundle, verify its signature, and derive the per-device download
estimate. Fully verifiable on the build machine.

**Acceptance Scenarios**:

1. **Given** valid local signing credentials, **When** a release bundle is produced, **Then** the
   output is a signed bundle whose signature verifies against the expected upload certificate.
2. **Given** no local signing credentials, **When** a debug build runs, **Then** it succeeds; **and
   when** a release task runs, **Then** it fails with a message that names the missing credential
   file and the values it needs.
3. **Given** the produced bundle, **When** a device-specific artifact set is generated for a typical
   64-bit device, **Then** the estimated download size is reported and recorded.
4. **Given** a tester whose device locale is Arabic and another whose device locale is English,
   **When** each installs from Play, **Then** both see fully localized text with no missing strings.
5. **Given** the produced bundle, **When** its version code is compared to the highest version code
   previously uploaded for this package, **Then** it is strictly greater.

---

### User Story 5 - The repository holds no live secrets (Priority: P1)

A maintainer clones the repository fresh and finds only placeholders where credentials belong — no
keystore password, no keystore file, no Firebase configuration.

**Why this priority**: P1 and independent of the phase ordering, because a committed signing
password is exploitable the moment the repository is shared or made public, and because an upload
key created with a leaked password must be replaced *before* it is registered with Play — replacing
it afterwards is a support escalation, not a commit.

**Independent Test**: Inspect the tracked file set for credential material. Requires no build.

**Acceptance Scenarios**:

1. **Given** the tracked files in the repository, **When** they are inspected for credential
   material, **Then** every credential field is an empty placeholder.
2. **Given** a keystore that was created using the leaked password, **When** release signing is
   configured, **Then** that keystore is not used and a replacement is generated.
3. **Given** a fresh clone, **When** a developer follows the release documentation, **Then** every
   value they must supply locally is enumerated in one place.

---

### Edge Cases

- A developer clones the repo with no local secrets file and runs a debug build — must succeed on
  committed example values, never fail on a missing local file.
- A release build is attempted with a partially-filled secrets file — must fail naming the specific
  key, not with a generic or downstream error.
- Certificate pins are left empty — the HTTP client must apply no pinning at all rather than
  registering a pin derived from an empty string, which would reject every connection.
- The configured API host and the configured backend URL disagree — any pin registered against the
  host would silently never apply, giving false confidence in a protection that is not active. This
  is the current state.
- A tester's device runs a locale with no translation — must fall back to a complete language rather
  than rendering untranslated identifiers.
- The platform delivers a foreground-service timeout despite the service-type change — must be
  logged and recovered from, not swallowed.
- The device uses 16 KB memory pages — every bundled native library must load.
- A release bundle is produced whose version code does not exceed the last uploaded one — must be
  caught before upload, not by Play's rejection.
- The lock screen is active and the child attempts a predictive-back gesture — must not dismiss.
- `third_party/llama.cpp` is absent from a fresh clone (it is gitignored) — the build must be
  unaffected, confirming it is not a build input.
- Code shrinking removes a class reached only reflectively or only via the manifest — a dependency
  injection graph, a database entity, a model interpreter, or a declared service. The build succeeds
  and the signature verifies; the failure appears only at runtime, on the shrunk build, which no
  prior build has exercised.
- The release build's smoke test fails on a guarded path — the fix must not be to weaken
  minification silently, and must not be to modify the guarded subsystem itself.

---

## Requirements *(mandatory)*

### Phase 1 — Configuration externalization and secrets hygiene

- **FR-001**: Build configuration values that differ per environment or per developer MUST be
  supplied from an untracked local file, not embedded in tracked build scripts.
- **FR-002**: A tracked example file MUST enumerate every required configuration key with safe
  placeholder values.
- **FR-003**: When the untracked local file is absent, the build MUST fall back to the tracked
  example values so a fresh clone builds successfully.
- **FR-004**: The backend base URL MUST be resolvable independently per build type, so the debug
  build can target a development backend while release targets production.
- **FR-005**: Producing a release artifact MUST fail before any artifact is emitted if the release
  backend URL is not HTTPS, or if the release backend URL or the Google client identifier still
  contains an unfilled placeholder.
- **FR-006**: The failure in FR-005 MUST name the specific offending configuration key.
- **FR-007**: The guard in FR-005 MUST NOT block debug builds under any circumstance.
- **FR-008**: Certificate pinning configuration MUST be read from build configuration rather than
  from hardcoded source constants.
- **FR-009**: When either configured pin value is blank, the HTTP client MUST apply no certificate
  pinning and MUST record a warning, rather than registering a pin built from an empty value.
  *(Resolved Q1: pinning is disabled for the first release — both pin values ship empty.)*
- **FR-010**: The pinned host MUST match the host the app actually contacts. A configuration where
  they differ MUST be rejected at build time, because a mismatch renders pinning silently inert.
  This guard MUST apply whenever pins are non-empty, so that the current failure mode — pins bound
  to a host the app never calls — cannot recur when pinning is later enabled.
- **FR-011**: Source constants that become unreferenced as a result of FR-008 MUST be removed only
  after confirming no remaining references.
- **FR-012**: The untracked local configuration file MUST be excluded from version control.
- **FR-013**: The tracked `keystore.properties.template` file MUST be scrubbed of its committed
  password and reduced to empty placeholders.
- **FR-014**: Documentation MUST state that any keystore created with the leaked password is
  compromised and MUST be regenerated before use as an upload key.

### Phase 2 — Network security configuration split

- **FR-015**: The release network security configuration MUST deny cleartext traffic for all
  destinations and trust only system certificate authorities.
- **FR-016**: The release network security configuration MUST contain no development host
  exemptions, no debug overrides, and no commented-out placeholder pin material.
- **FR-017**: A debug-only network security configuration MUST permit cleartext to loopback and LAN
  development hosts and MUST trust user-installed certificate authorities.
- **FR-018**: The application declaration MUST continue to deny cleartext by default and MUST
  continue to reference the network security configuration resource.
- **FR-019**: Certificate pinning MUST be enforced in exactly one place — the HTTP client layer —
  and MUST NOT be duplicated into the network security configuration.

### Phase 3 — Android 16 (API 36) migration

- **FR-020**: The app MUST compile against and target API level 36, retaining a minimum of API 26.
- **FR-021**: The build toolchain MUST be confirmed to support API 36 and MUST be upgraded only if
  the current versions do not. Any version change MUST be reported with its rationale.
- **FR-022**: If the toolchain required for API 36 is incompatible with the current JDK, the
  migration MUST stop and report rather than change the JDK silently.
- **FR-023**: The monitoring service MUST NOT be subject to the platform's cumulative six-hour
  daily foreground-service runtime cap.
- **FR-024**: The monitoring service declaration MUST carry a justification describing its
  parental-control purpose, matching the pattern already used by the VPN service.
- **FR-025**: The monitoring service MUST declare its foreground service type when entering the
  foreground on platform versions that require it.
- **FR-026**: Both long-running foreground services MUST handle a platform-issued timeout signal by
  recording it and attempting a graceful restart, without altering monitoring behavior.
- **FR-027**: Foreground service permissions that no service requires after FR-023 MUST be reported;
  removal is deferred to a later change.
- **FR-028**: Every native library in the built artifact MUST be verified to load on devices using
  16 KB memory pages. Libraries that are not compliant MUST be reported as blockers.
- **FR-029**: If the app configures no native build and ships no first-party native libraries, this
  MUST be stated explicitly rather than assumed, and the audit MUST cover libraries arriving through
  dependencies.
- **FR-030**: No screen may render interactive controls or text underneath the status bar or the
  navigation bar.
- **FR-031**: The lock screen MUST remain full-screen and MUST NOT be dismissible by a back gesture,
  including predictive back.
- **FR-032**: Deprecated back-press handling MUST be replaced with the supported mechanism.
- **FR-033**: If a UI dependency upgrade introduces widespread deprecation errors, the scope MUST be
  reported before any bulk edit is made.
- **FR-034**: Permissions restricted by Play to app categories this product does not belong to MUST
  be removed from the manifest.
- **FR-035**: Alarm-scheduling permissions that map to no scheduling behavior in the app MUST be
  removed from the manifest. *(Resolved Q2: the audit found no exact-alarm scheduling anywhere —
  only an inexact, Doze-friendly restart alarm — so both exact-alarm permissions are unmapped and
  both MUST be removed.)*
- **FR-036**: No runtime exact-alarm permission check is required, because the app schedules no
  exact alarms. Should exact alarms ever be introduced, the permission and its runtime capability
  check MUST be reintroduced together as one deliberate change.

### Phase 4 — Release signing, bundle output, and size

- **FR-037**: Release signing credentials MUST be supplied from an untracked local file, with a
  tracked example enumerating the required keys.
- **FR-038**: A missing or incomplete credentials file MUST NOT affect debug builds and MUST fail
  only when a release task actually runs.
- **FR-039**: The distributable artifact MUST be a signed Android App Bundle whose signature
  verifies.
- **FR-040**: The version code MUST be strictly greater than the highest version code previously
  uploaded for this package. *(Resolved Q3: target is version code **7**, version name **"1.2.0"**,
  a monotonic increase from the current 6. The brief's stated `versionCode 3` is a downgrade and
  would be rejected by Play.)*
- **FR-041**: Language-based bundle splitting MUST be disabled so that a device in any supported
  locale receives complete translations.
- **FR-042**: Density-based and ABI-based bundle splitting MUST be enabled.
- **FR-043**: The bundle MUST include only ABIs that target devices actually use. Any ABI built
  without a corresponding target device MUST be reported.
- **FR-044**: The estimated per-device download size for a typical 64-bit device MUST be measured
  and recorded, distinctly from the bundle's on-disk size.
- **FR-045**: A size breakdown covering bundled assets, native libraries per ABI, and resources MUST
  be produced.
- **FR-046**: The role of the vendored large language model source tree MUST be determined and
  reported: whether any active code path reaches it, whether it is a build input at all, and what
  removing it would save. It MUST NOT be removed in this feature.
- **FR-047**: Signing keys MUST NOT be generated automatically. The exact generation command MUST be
  documented for the operator to run.
- **FR-062**: The minified, resource-shrunk release build MUST be installed on a device and
  exercised before the bundle is considered ready. Signature verification alone is insufficient,
  because code shrinking removes reflectively-reached code without emitting a build error.
- **FR-063**: The smoke test in FR-062 MUST cover, at minimum: completing login, starting the
  monitoring service, starting the VPN service, the accessibility service receiving an event, and
  performing one image classification. Each is reached reflectively or via the manifest and is
  therefore at risk from code shrinking.
- **FR-064**: Any failure surfaced by FR-063 MUST be reported before the bundle is uploaded.
  Resolving it by disabling minification is not acceptable unless stated explicitly and accepted.
- **FR-065**: The code-shrinking mapping file produced for the release build MUST be retained and
  its location documented, so it can be uploaded alongside the bundle.
- **FR-066**: The release documentation MUST instruct the operator to upload the mapping file, and
  MUST state that without it every crash report from a tester is unreadable.
- **FR-067**: No crash-reporting SDK is introduced by this feature, and no new data collection is
  added. Crash visibility comes from the store console's existing reporting made legible by FR-065.

### Cross-cutting

- **FR-048**: Machine-learning classification code, model assets, and thresholds MUST NOT be
  modified.
- **FR-049**: VPN packet parsing, checksum, DNS forwarding, and blocklist logic MUST NOT be
  modified. Its manifest declaration and foreground-entry call may be modified where a requirement
  explicitly says so.
- **FR-050**: Accessibility text-monitoring debouncing, rate limiting, and app exclusion behavior
  MUST NOT be modified.
- **FR-051**: Dependency injection structure, repository naming, and backend API contracts MUST NOT
  be modified.
- **FR-052**: Nothing outside an explicit requirement may be deleted or restructured.
- **FR-053**: If a requirement cannot be satisfied without modifying a guarded area, work MUST stop
  and ask rather than proceed.
- **FR-054**: Each phase MUST complete its checkpoint and report before the next phase begins.
- **FR-055**: No changes may be committed. Commit boundaries MUST be proposed for the operator.
- **FR-056**: A release documentation file MUST enumerate: manual verification steps, the key
  generation command, the Play App Signing certificate registration step, every value the operator
  must supply locally, the permission declarations of FR-058..FR-060, and a per-phase summary of
  what changed and why.
- **FR-057**: Deviations from the project constitution introduced by this feature — API level 36,
  Compose dependency bump, monitoring service type change, and certificate pinning posture — MUST be
  recorded with rationale and resolved by a constitution amendment. The amendment is a tracked task
  within this feature.
- **FR-071**: The amendment of FR-057 MUST be completed before Phase 3 begins, and MUST gate Phase 3
  only. Phases 1, 2, and 4 and the secrets remediation of FR-013/FR-014 introduce no constitutional
  deviation and MUST NOT be blocked by it.
- **FR-072**: The amendment MUST additionally reconcile the pre-existing drift between the
  constitution's stated target API level and the level the build actually targets, so the document
  matches reality on completion.

### Play Console submission readiness

- **FR-058**: Every permission the app declares that requires a Play Console declaration form or
  written justification MUST be enumerated in the release documentation, together with the
  enforcement feature it serves.
- **FR-059**: For each permission in FR-058, draft justification text suitable for submission MUST
  be provided in the release documentation, so the operator can paste rather than compose it.
- **FR-060**: The enumeration in FR-058 MUST cover, at minimum, the permissions governing all-files
  storage access, full package visibility, the accessibility API, VPN establishment, and usage-stats
  access — these carry the highest rejection risk for this app category.
- **FR-061**: FR-058..FR-060 are documentation deliverables. No manifest change, no automation, and
  no submission action is performed by this feature.
- **FR-068**: The release documentation MUST record that a publicly reachable privacy policy URL is
  required for submission, and MUST state that the in-app bundled policy does not satisfy this.
- **FR-069**: Draft answers for the store's data-safety questionnaire MUST be provided, derived from
  the project's established data-flow rules: what is collected, why, whether it leaves the device,
  and its retention. Where the rules state that raw monitored text and raw image bytes never leave
  the device, the draft MUST say so.
- **FR-070**: FR-068 and FR-069 are documentation deliverables. This feature neither publishes the
  policy nor submits the questionnaire.

### Key Entities

- **Local secrets file** — untracked, developer-supplied. Holds backend URLs per build type, the
  API host, the Google client identifier, and optional certificate pins.
- **Secrets example file** — tracked. Mirrors the key set with placeholder values; doubles as the
  fallback source for fresh clones.
- **Local signing credentials file** — untracked, operator-supplied. Locates the keystore and holds
  its passwords and alias.
- **Signing credentials example file** — tracked. Mirrors the key set with empty values.
- **Release documentation** — tracked. The operator's single reference for everything that cannot be
  automated.
- **Network security policy** — two variants: a strict release policy and a permissive debug policy.
- **Distributable artifact** — the signed bundle uploaded to Play, distinct from the per-device
  artifacts Play derives from it.

---

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A developer who clones the repository with no local configuration can produce a
  working debug build on the first attempt, with no manual setup step.
- **SC-002**: 100% of release-artifact attempts made with incomplete or non-HTTPS configuration fail
  before an artifact exists, and each failure message identifies the offending key by name.
- **SC-003**: Zero cleartext-permitted destinations and zero user-trusted certificate authorities
  are present in the release artifact.
- **SC-004**: Zero credential values remain in tracked repository files.
- **SC-005**: A tester installing from the Play Internal Testing track completes Google sign-in and
  reaches the home screen on the first attempt.
- **SC-006**: Protection remains continuously active for at least 24 uninterrupted hours on an
  Android 16 device, with no unreported service termination.
- **SC-007**: Every screen renders with no control or text obscured by system bars, verified across
  the full screen inventory.
- **SC-008**: The lock screen resists 10 consecutive back and predictive-back gesture attempts
  without dismissing.
- **SC-009**: The submitted bundle is accepted by Play with zero rejections for target API level or
  restricted permissions.
- **SC-010**: The existing automated test suite passes with zero new failures after migration.
- **SC-011**: The signed bundle's signature verifies against the expected upload certificate, and
  its version code exceeds every previously uploaded version code.
- **SC-012**: The per-device download estimate for a typical 64-bit device is recorded, and the size
  breakdown accounts for at least 95% of the artifact's total size.
- **SC-013**: Testers in both Arabic and English locales report zero missing or untranslated strings.
- **SC-014**: All five guarded areas are unchanged apart from the specific manifest and
  foreground-entry modifications a requirement explicitly authorizes.
- **SC-015**: Every value the operator must supply is enumerated in one document; an operator
  following it needs no additional questions to produce an uploadable bundle.
- **SC-016**: Every declaration-requiring permission the app ships has drafted justification text
  ready to submit; the operator completes the Play Console declaration forms without composing any
  wording themselves.
- **SC-017**: The minified release build completes all five smoke-test paths — login, monitoring
  service start, VPN start, accessibility event delivery, image classification — with zero crashes
  and zero missing-class failures, before any bundle is uploaded.
- **SC-018**: Crash reports arriving from testers are readable — every frame resolves to a source
  symbol rather than an obfuscated name — with no new data collected from any device.
- **SC-019**: The operator can complete every store submission form — permission declarations,
  data-safety questionnaire, privacy policy URL — from the release documentation alone, without
  composing wording or re-deriving what the app collects.

---

## Assumptions

- The production backend is `https://bw.noor.net:8090/api/v1/`, already configured in the current
  build. The release URL is not being changed by this feature; it is being moved out of the tracked
  build script.
- The existing Google client identifier is correct for the current Firebase project. Sign-in
  failures on Play-installed builds are expected to stem from the App Signing certificate not being
  registered, not from a wrong client identifier.
- The current toolchain (AGP 8.13.2, Gradle 8.13, Kotlin 2.0.21, KSP) already supports API 36; no
  upgrade is anticipated, and this will be verified rather than assumed.
- The project has no native build configuration and ships no first-party native libraries.
  16 KB page-size compliance therefore reduces to auditing dependency-supplied libraries, chiefly
  TFLite 2.16.1, which is expected to be compliant and will be verified.
- `third_party/llama.cpp` is not a build input. It is gitignored, unreferenced, and absent from a
  fresh clone. Its 206 MB affects working-copy disk usage only, never the artifact.
- Artifact size is dominated by `nsfw_classifier.tflite` at 24.4 MB of 28 MB total assets. Moving
  models to on-demand delivery is explicitly out of scope.
- The operator generates and safeguards the upload keystore. Play App Signing is enabled, so the
  upload key is not the final app signing key.
- Manual verification steps requiring a physical Android 16 device are performed by the operator;
  automated verification cannot cover them.
- Arabic and English are the supported locales; no other translations are in scope.
- Backend availability and correctness are out of scope; this feature changes client packaging and
  configuration only.

---

## Out of Scope

- Migrating annotation processing (already on KSP).
- Moving model assets to on-demand delivery.
- Removing the vendored language model source tree (report only).
- Removing the now-unneeded foreground service permission (report only).
- Changing the backend, its contracts, or its choreography.
- Any change to ML, VPN, or accessibility monitoring behavior.
- Committing any of this work.
- Submitting the store's declaration forms or data-safety questionnaire, publishing the privacy
  policy, or producing store-listing assets (screenshots, descriptions, graphics). This feature
  drafts the text (FR-058..FR-061, FR-068..FR-070); the operator publishes and submits it.
- Building or verifying the legal-document publishing pipeline under `tools/legal-sites/`.
- Re-auditing whether each declaration-requiring permission still maps to a live enforcement
  feature. The exact-alarm permissions were audited because a requirement targeted them; the
  remaining restricted permissions are documented as-declared, not re-litigated.

---

## Resolved Decisions

All three open questions were answered on 2026-08-24. Each overrides a literal instruction in the
originating brief, for the reason given.

| # | Decision | Resolution | Rationale |
|---|---|---|---|
| Q1 | Certificate pinning posture | **Disabled for the first release.** Both pin values ship empty; the HTTP client applies an empty `CertificatePinner`. A build-time guard rejects any configuration where a non-empty pin is bound to a host other than the one the app calls. | Pinning is inert today (pins bound to `api.safeguard.app`, traffic goes to `bw.noor.net`). Enabling it with wrong pins would take every tester offline, and a backend cert rotation without a matching app update would do the same. The host-match guard ensures the silent-inertness failure cannot recur when pinning is enabled later. Requires a Principle V amendment. |
| Q2 | Alarm-scheduling permissions | **Both `SCHEDULE_EXACT_ALARM` and `USE_EXACT_ALARM` removed.** No runtime capability check is added. | The audit found no exact-alarm scheduling anywhere in the app — `MonitoringService` uses only an inexact, Doze-friendly restart alarm. Both permissions are unmapped, and Principle IV requires unmapped permissions be removed. `USE_EXACT_ALARM` additionally carries a Play rejection risk for a non-calendar, non-alarm-clock app. A runtime guard for a capability the app never exercises would be dead code. |
| Q3 | Version code target | **Version code 7, version name "1.2.0".** | The repository is at version code 6. Play rejects any bundle whose version code does not exceed the highest previously uploaded, so the brief's `versionCode 3` would guarantee rejection. 7 is the smallest monotonic increase; "1.2.0" preserves the version name the brief asked for. |
