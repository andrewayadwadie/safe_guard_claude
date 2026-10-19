# Implementation Plan: Replace All-Files Access with Parent-Granted Folder Access

**Branch**: `fix/fgs-remove-data-sync` (no feature branch cut yet — see Pre-Implementation Note) | **Date**: 2026-10-07 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/016-remove-all-files-access/spec.md`

## Summary

Google Play blocks the release review on an undeclared all-files-access permission whose
permitted-use list has no parental-control entry, and whose declaration form asks the one question
the app cannot answer favourably: *why can't you use the Storage Access Framework?* It can. This
feature removes the permission and moves the three shared-storage writes behind folder access the
parent grants once through the system picker.

The Phase 0 audit in the spec found the work is not the shape the request assumed. Two findings
dominate the plan:

- **The narrow photo permission is declared but never requested at runtime on Android 11+.** Every
  image read on those versions — the real-time watch, the periodic scan, the classifier — works
  *only* because all-files access was granted. Removing the permission without adding that request
  would end image protection silently on most of the installed base. Adding it is the load-bearing
  half of this feature, not cleanup.
- **In-place blurring has never worked on Android 8 through 10.** No legacy write permission and no
  legacy-storage opt-in exist anywhere in the manifest, and all-files access only exists from
  Android 11, so the blur, the restore and the delete have always failed there behind a
  parent-facing success path. Folder grants work from Android 5, so those three actions are
  **fixed** on Android 8–10 rather than regressed.

Phase 0 research then changed the design twice more. `MANAGE_MEDIA` looked like a better fit than
folder grants — one system toggle, and it would have covered the `Download` folder — but its write
path returns an intent sender that must be launched from an activity, and this app's writes happen
in a background worker and a background service that have none; it is also Android 12+ only, which
would regress Android 11 (R-002). And the real-time file-system watch rests on undocumented inotify
behaviour over shared storage once scoped storage applies, so the real-time trigger moves to a
media-database observer on Android 11+ (R-005) — a change of trigger *source*, with no change to
detection *logic*, recorded in Complexity Tracking because the spec did not anticipate it.

**Technical approach**: request the narrow photo permission at runtime on every version; add a
single injected seam that owns folder-grant state and performs document writes and deletes, built
on the platform document contract with no new dependency (R-007); route the three write call sites
in the blur manager through it; replace the all-files-manager permission check with a
narrow-permission-plus-grants check feeding the existing protection-status surface; move the
real-time trigger to a media-database observer on Android 11+ while keeping the file watch below
it; delete the permission; add a release-blocking merged-manifest guard mirroring feature 015; drop
the now-unneeded store justification from the release checklist.

## Technical Context

**Language/Version**: Kotlin 2.0.21 (KSP), JDK 21, `jvmTarget = 17`

**Primary Dependencies**: Jetpack Compose (BOM 2024.09.00), Hilt 2.51.1, WorkManager 2.9.0,
Room 2.6.1, Retrofit 2.9.0 / OkHttp 4.12.0, Firebase BOM 32.7.0, TFLite 2.16.1, Timber 5.0.1.
**No dependency is added, removed or upgraded.** The document-file support library was evaluated
and rejected in R-007: every operation needed is on the platform document-contract class, and the
constitution's locked dependency table requires real rationale that "avoid writing two helper
functions" does not meet.

**Storage**: No new persistent store. Folder grants are **not** mirrored into app storage — the
platform's own persisted-permission list is the single source of truth, so there is no state to
drift or migrate. `EncryptedSharedPreferences` gains exactly one boolean: whether the parent has
already been shown the one-time `Download`-folder notice (FR-022). App-private backup copies and
their metadata keep their current location, format and retention.

**Testing**: JUnit + mockk + turbine + coroutines-test, as the module already uses. **No
Robolectric in the project**, so no test may touch a real framework class — new logic is placed
behind pure-Kotlin seams for that reason. New unit coverage: grant-state resolution (granted /
missing / revoked / partially granted), relative-path-to-document-identifier mapping including
names needing escaping, and the write-failure restore branch. Device-facing behaviour is verified
against [quickstart.md](./quickstart.md), five of whose scenarios are merge gates. Existing
`ImageBlurMetadataTest` and `FakeSharedPreferences` are the precedent for testing blur logic
without a device.

**Target Platform**: Android, `minSdk 26`, `targetSdk 36`, `compileSdk 36`. Release artifact is a
signed AAB. Behaviour splits at three version boundaries that the plan must treat separately:
Android 8–10 (legacy, file watch, no write permission today), Android 11–12L (scoped storage,
narrow permission is the legacy read permission), Android 13+ (narrow photo permission proper),
and Android 14+ (partial-selection prompt).

**Project Type**: Native Android mobile app, single Gradle module (`:app`).

**Performance Goals**: Observer-to-blurred-gallery latency no worse than today's
watch-to-blurred-gallery latency (SC-003). Path-to-document mapping must be constant-time, not
proportional to folder size — the reason R-003 constructs the document identifier arithmetically
instead of walking the tree, which matters on a camera folder holding thousands of images.

**Constraints**: Writes execute from a worker and a background service, so the write mechanism must
need no activity — this is what disqualified `MANAGE_MEDIA` (R-002). No child-facing prompt may
gate an enforcement action (FR-017). A failed write must never leave a truncated file where the
child's photo was (FR-018). Detection, classification, thresholds, the bundled model, the alert
payload and the backend contract are all untouched.

**Scale/Scope**: One new injected seam (~200 lines) plus its Hilt binding. Six existing files
modified: the manifest, the blur manager (three call sites), the media observer (trigger source),
the permission-setup view model and screen, the settings view model. One Gradle guard task. Two
documentation updates plus a version-code bump. Estimated net source change 600–900 lines, the
majority of it in the new seam and the grant-flow UI.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-checked after Phase 1 design — see
[Post-Design Re-Check](#post-design-re-check).*

### Twelve SpecKit Enforcement Rules

| # | Rule | Status |
| --- | --- | --- |
| 1 | Feature sub-package under `presentation/<feature>/` with ViewModel/UiState/Screen | **PASS by reuse** — the grant flow is a step inside the existing permission-setup feature and a branch of the existing settings feature. Both sub-packages already carry the required trio. Creating a third sub-package for one picker step would fragment the setup flow the parent experiences as one thing |
| 2 | Every ViewModel `@HiltViewModel` | **PASS** — both view models touched are already `@HiltViewModel`; no new one is added |
| 3 | Every repository `@Singleton` | **PASS** — the new write seam is a `@Singleton` injected collaborator, matching how the blur manager itself is scoped |
| 4 | ViewModel → Repository → `safeApiCall{}` → `ApiService` | **N/A** — no network call. The alert path is downstream and untouched |
| 5 | Tokens only in `TokenManager` | **PASS** — no token access anywhere in this feature |
| 6 | Special permissions via settings routing, never `requestPermissions()` | **PASS, and this rule is the reason Audit Finding 1 exists.** The four named special permissions keep their settings routing. The narrow photo permission is an ordinary runtime permission, so `requestPermissions` is the *correct* mechanism for it — the current code wrongly routed it to an all-files-access settings screen instead, which is the bug being fixed. Folder access is granted through the system document picker, which is activity-result routing, not `requestPermissions` |
| 7 | Services contain zero business logic, delegate to injected repositories | **PASS** — the media observer's change is which platform callback wakes it. The decision of what to do with a detected image stays where it is, behind the injected blur manager |
| 8 | Workers are `@HiltWorker` + `CoroutineWorker` + `@AssistedInject` | **PASS** — the image-scan worker keeps its annotations; only the collaborator it calls changes behaviour |
| 9 | `NetworkResult<T>` is the only repository return type | **N/A** — no repository function added. The write seam is a platform adapter, not a repository, and returns a sealed result in the style the blur manager already uses |
| 10 | UI errors via `Snackbar` + `clearError()` | **PASS** — grant-flow failures and revoked-access notices surface through the existing snackbar path on both screens. The one-time `Download` notice (FR-022) is informational copy inside the screen, not an error |
| 11 | ProGuard rules updated for every new Service, Receiver or Worker | **PASS** — none is added, so no rule change is required or permitted |
| 12 | `aaptOptions { noCompress "tflite" }` remains in `app/build.gradle` | **PASS** — the Gradle guard appends a verification task alongside the existing release guards and must not disturb that block. Re-verified as a Phase 1 checkpoint |

### Core Principles

| Principle | Status |
| --- | --- |
| **I. Child Safety & Privacy First** (NON-NEGOTIABLE) | **PASS, and advanced.** The app's reach over the child's storage shrinks from every file on the device to the picture folders the parent chose — strictly less collection for identical enforcement. Analysis stays on the child's device; only category, confidence and alert metadata leave it, unchanged. The principle's stated failure mode is protection that ends silently, and this plan removes two instances of exactly that: detection that would have died unnoticed when the permission went (Audit Finding 1), and writes that have been failing unnoticed on Android 8–10 for the app's whole life (Audit Finding 2). It is also why R-005 refuses to leave the real-time trigger on undocumented behaviour |
| **II. MVVM + Clean Architecture** (NON-NEGOTIABLE) | **PASS** — platform access is isolated in one injected seam; the view models gain state, not platform calls; no composable gains logic |
| **III. Two-Role Architecture** | **PASS** — enforcement stays on the child device against locally held grants. Nothing in the new path requires the parent app to be reachable or the backend to be up |
| **IV. Native Services & Permission Hygiene** | **PASS, and directly satisfied.** The rule is explicit: *"Every new high-risk permission added to the manifest MUST map to a named enforcement feature in its spec. Unmapped permissions MUST be removed."* All-files access maps to nothing the app may claim under store policy; this is that removal. Folder access replaces it mapped to one named action — replace, restore, delete a flagged image. `ProtectionStatusHelper` remains the single source of truth for the five protection layers it already owns and is **not** extended; image access is not one of those five (see Structure Decision) |
| **V. Defense-in-Depth Security & Secrets Hygiene** | **PASS** — no secret, token, key or network change. The feature reduces privilege rather than adding any |

### Principle I scope note — the `Download` folder

Principle I names the app's image-detection scope as *"saved media only (Downloads, Screenshots)"*.
The `Download` folder is precisely the one the platform refuses to grant through the picker
(R-001). **Detection there is unaffected** — the media-database observer sees it, the classifier
reads it, the backup is taken and the parent is alerted. Only the in-place blur is unavailable,
which is a narrowing of *enforcement*, not of *monitoring scope*. No constitutional amendment is
required: the principle governs what the app may look at, and that does not change. FR-016 and
FR-022 make the gap explicit to the parent instead of hiding it.

**Gate result: PASS. No violations requiring justification.** Two design consequences that the
spec did not anticipate are recorded in [Complexity Tracking](#complexity-tracking).

## Project Structure

### Documentation (this feature)

```text
specs/016-remove-all-files-access/
├── plan.md                        # This file
├── spec.md                        # Feature specification with the Phase 0 audit
├── research.md                    # Phase 0 output — R-001 … R-009
├── data-model.md                  # Phase 1 output
├── quickstart.md                  # Phase 1 output — device verification matrix
├── checklists/
│   └── requirements.md            # Spec quality checklist (16/16)
├── contracts/
│   ├── build-guard.md             # Release-blocking merged-manifest guard
│   └── media-write-access.md      # The write seam's contract
└── tasks.md                       # Phase 2 output (/speckit-tasks — NOT created here)
```

### Source Code (repository root)

```text
app/
├── build.gradle                                   # MODIFY: all-files-access guard; versionCode 10 → 11
├── src/main/AndroidManifest.xml                   # MODIFY: delete all-files access
└── src/main/java/com/safeguard/parentalcontrol/
    ├── util/
    │   ├── MediaWriteAccess.kt                    # NEW: grant state + document write/delete seam
    │   ├── ImageBlurManager.kt                    # MODIFY: 3 write call sites → MediaWriteAccess
    │   └── PreferencesManager.kt                  # MODIFY: one flag (Download notice shown)
    ├── service/
    │   └── MediaFileObserver.kt                   # MODIFY: media-database observer on API 30+
    └── presentation/
        ├── setup/
        │   ├── PermissionsSetupViewModel.kt       # MODIFY: replace all-files check; grant state
        │   └── PermissionsSetupScreen.kt          # MODIFY: runtime photo request; picker flow
        └── settings/
            └── SettingsViewModel.kt               # MODIFY: Maximum Protection gates on grants

app/src/test/java/com/safeguard/parentalcontrol/util/
├── MediaWriteAccessGrantStateTest.kt              # NEW: granted / missing / revoked / partial
├── MediaWriteAccessDocumentIdTest.kt              # NEW: relative path → document identifier
└── ImageBlurWriteFailureTest.kt                   # NEW: failure restores the original

docs/
├── release-checklist.md                           # MODIFY: delete the all-files justification
└── release-notes-1.2.2.md                         # NEW: versionCode 11
```

**Structure Decision**: the existing single-module, package-by-feature layout is kept exactly.
Platform storage access is new behaviour, so it gets one new file in `util/` beside the blur
manager it serves — the same placement the project already uses for `ProtectionStatusHelper`,
`ImageBlurManager` and `ImageHasher`. No new presentation sub-package is created, because the grant
step belongs to the setup flow the parent already walks through (enforcement rule 1, PASS by
reuse). No new service, receiver or worker is introduced, which is why no ProGuard rule changes.

**Two deliberate non-changes**, each of which a first reading of FR-006 might suggest:

- **No Hilt module entry.** `ImageBlurManager` is a `@Singleton` with an `@Inject` constructor and
  no module declaration; the write seam follows that pattern exactly, so `di/AppModule.kt` is not
  touched. Adding a provider for a constructor-injectable singleton would be noise, and
  enforcement rule 2's ban on manual instantiation is satisfied either way.
- **No sixth protection type in `ProtectionStatusHelper`.** That helper models the five protection
  layers the constitution names, and its `ProtectionStatus`, `getDisabledProtections`,
  `getProtectionLevel` and newly-disabled-protection comparison all feed the parent dashboard and
  the tamper-alert path. Image protection is not one of those five and never has been: the media
  row the parent sees is owned by the permission-setup view model today. FR-006's "single source of
  truth" is therefore that view model's check, which this feature rewrites in place. Promoting
  image access to a sixth layer would ripple into dashboard copy and tamper alerting for no
  requirement in this spec, so it is left for a feature that actually asks for it.

## Phase Plan

**Phase 0 — Research. Complete.** Nine questions, nine decisions, in
[research.md](./research.md). Two reshaped the design (R-002 rejects `MANAGE_MEDIA`; R-005 moves the
real-time trigger); one removed a dependency the design would have taken (R-007); five device
questions are carried into the quickstart as verification gates.

**Phase 1 — Design & contracts. Complete.** [data-model.md](./data-model.md) fixes the four
entities and the grant state machine. [contracts/media-write-access.md](./contracts/media-write-access.md)
fixes the write seam's behaviour, including the failure ordering FR-018 demands.
[contracts/build-guard.md](./contracts/build-guard.md) fixes the release-blocking guard, mirroring
feature 015's. [quickstart.md](./quickstart.md) is the device verification matrix.

**Phase 2 — Tasks.** Produced by `/speckit-tasks`, not here. The expected ordering is: prove the
read path on a device first (quickstart scenario 1, which gates everything), then the write seam
and its unit tests, then the blur manager's three call sites, then the trigger-source move, then
the grant-flow UI, then the permission deletion and the guard, then the docs. The permission must
not be deleted before the read path is proven on a device, or the build in hand is one where
protection is silently off.

## Complexity Tracking

> Two design consequences that the specification did not anticipate. Neither is a constitution
> violation; both are recorded because a reviewer comparing spec to plan will notice them.

| Consequence | Why needed | Simpler alternative rejected because |
|---|---|---|
| ~~The real-time trigger moves from a file-system watch to a media-database observer~~ **— withdrawn, the migration was already done** | **Corrected during implementation.** `MediaFileObserver` already registers a media-database `ContentObserver` as its PRIMARY trigger and the file observers as a FALLBACK; the spec's audit table mis-recorded this, and R-005 was written from that table. Nothing migrated. The reasoning holds — a file watch over shared storage rests on undocumented inotify delivery once scoped storage applies — but the code was already on the documented mechanism. The actual change was to the class documentation, which claimed the file observers did the real-time detection and would have misled the next reader | n/a — there was no second option to weigh, only a wrong premise. Recorded rather than deleted because the plan's effort estimate was built on it |
| The file observers are kept despite being unreliable without all-files access | Where inotify does fire they arrive sooner than the database callback, and anything they catch is deduplicated downstream by `ImageHasher`, so a spurious or duplicate event costs nothing. Nothing depends on them | Deleting them would have been tidier and was considered. Rejected: on devices where inotify still works it is a free latency win, and removing it would slow detection on those devices for a cosmetic gain |

## Post-Design Re-Check

Re-evaluated after Phase 1 artifacts were written. **Still PASS**, with three items confirmed
rather than assumed:

1. **Rule 12** — the Gradle guard appends a task next to the existing release guards at the bottom
   of `app/build.gradle` and does not touch the `aaptOptions` block. Confirmed against the file's
   current shape, where feature 013's and 015's guards already sit in that position.
2. **Rule 3 / Rule 9** — the write seam is a `@Singleton` platform adapter, not a repository, so
   the `NetworkResult<T>` rule does not reach it. It returns a sealed result in the same style
   `ImageBlurManager.BlurResult` already uses, which keeps one idiom in that file rather than two.
3. **Principle I** — re-read against the finished data model. The model stores no image bytes
   outside the existing app-private backup, adds no new field that could carry content off the
   device, and the one new preference is a boolean about whether a notice was shown.

No new violation appeared, and Complexity Tracking did not grow during Phase 1.

## Pre-Implementation Note

The working branch is still `fix/fgs-remove-data-sync` from feature 015. No branch was cut for this
feature, because the repository's SpecKit setup has no `before_specify` git hook installed and
`/speckit-specify` does not create branches itself. Cut `016-remove-all-files-access` from `main`
before the first task, so feature 015's unmerged work does not ride along with this one.
