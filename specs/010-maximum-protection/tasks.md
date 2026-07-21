# Tasks: Maximum Protection

**Input**: Design documents from `/specs/010-maximum-protection/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/internal-contracts.md, quickstart.md

**Tests**: Unit tests included only where the plan calls for them (metadata compatibility — research.md R11). On-device validation runs via quickstart.md scenarios.

**Organization**: Tasks grouped by user story. Note: the enforcement branch (flag check at both violation call sites) is built in US2 and consumed by US3 — see Dependencies.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: US1 (PIN-gated toggle), US2 (ON mode + retroactive blur), US3 (OFF copy-only default)

## Phase 1: Setup

**Purpose**: Baseline before touching code

- [X] T001 Verify checkout is on branch `010-maximum-protection` and baseline build passes: `./gradlew assembleDebug` (repository root)

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Flag storage + metadata versioning that every story depends on (US1 writes the flag; US2/US3 read it and the metadata)

**⚠️ CRITICAL**: Complete before any user story phase

- [X] T002 Add `const val KEY_MAXIMUM_PROTECTION_ENABLED = "maximum_protection_enabled"` with a KDoc comment (PIN-gated write path, cleared on logout) alongside the other preference keys in `app/src/main/java/com/safeguard/parentalcontrol/util/Constants.kt` (contract C2)
- [X] T003 Add typed property `var isMaximumProtectionEnabled: Boolean` (get default `false`, set persists; pattern-match `isContentFilteringEnabled`) in `app/src/main/java/com/safeguard/parentalcontrol/util/PreferencesManager.kt` (contract C1; depends on T002)
- [X] T004 Extend `ImageMetadata` with `val blurApplied: Boolean = true`; write the field in `saveMetadata()`; parse it in `loadMetadata()` with missing-key ⇒ `true` (legacy default); have `blurImage()` write `blurApplied = true`; update KDoc for `BlurResult.AlreadyBlurred` and `isImageBlurred()` to mean "backup exists" (contracts C3.1/C3.4) in `app/src/main/java/com/safeguard/parentalcontrol/util/ImageBlurManager.kt`. If needed for T005, mark the metadata parse/serialize helpers `internal` + `@VisibleForTesting`
- [X] T005 [P] Add JVM unit test covering metadata round-trip with `blurApplied` true and false, plus legacy file without the key parsing as `true`, in `app/src/test/java/com/safeguard/parentalcontrol/util/ImageBlurMetadataTest.kt` (data-model.md compatibility rule; depends on T004; parallel with Phase 3 work)

**Checkpoint**: Flag readable/writable; metadata versioned; unit tests green (`./gradlew testDebugUnitTest`)

---

## Phase 3: User Story 1 - Parent controls Maximum Protection from child settings (Priority: P1) 🎯 MVP

**Goal**: PIN-gated "Maximum Protection" toggle in the child-device settings; wrong/cancelled PIN never changes state; value persisted

**Independent Test**: quickstart.md Scenario 1 — flip attempts with wrong PIN, cancel, correct PIN, first-time PIN creation; state survives app restart (SC-001)

### Implementation for User Story 1

- [X] T006 [P] [US1] Add strings `settings_maximum_protection` ("Maximum Protection") and `settings_maximum_protection_desc` (short blur-mode explanation) to `app/src/main/res/values/strings.xml` (contract C7)
- [X] T007 [P] [US1] Add the same two strings in Arabic to `app/src/main/res/values-ar/strings.xml` (bilingual invariant, contract C7)
- [X] T008 [US1] Add `isMaximumProtectionEnabled: Boolean = false` to `SettingsUiState`; load it from `preferencesManager` in `init` and in the existing on-resume refresh path; add `fun setMaximumProtection(enabled: Boolean)` that persists the pref and updates UiState (retro-blur trigger added later by T015) in `app/src/main/java/com/safeguard/parentalcontrol/presentation/settings/SettingsViewModel.kt` (contract C5; depends on T003)
- [X] T009 [US1] Add `SettingsToggleItem` row (shield icon) to the child-only Parent Review section; `checked = uiState.isMaximumProtectionEnabled`; `onCheckedChange` stores desired value in `pendingMaxProtectionChange: Boolean?` remember-state and shows `ParentPinDialog` backed by `ParentPinViewModel` via `hiltViewModel()` (`ParentPinGate` wiring pattern); `onSuccess` → `viewModel.setMaximumProtection(pending)` then clear pending; `onDismiss` → clear pending only, in `app/src/main/java/com/safeguard/parentalcontrol/presentation/settings/SettingsScreen.kt` (contract C6; depends on T006, T008)
- [ ] T010 [US1] On-device validation of quickstart.md Scenario 1 (PIN gate both directions, create-PIN first run, persistence across restart) per `specs/010-maximum-protection/quickstart.md`

**Checkpoint**: Toggle fully functional and tamper-resistant — MVP deliverable, even before enforcement consumes the flag

---

## Phase 4: User Story 2 - Violation handled with Maximum Protection ON (Priority: P2)

**Goal**: Flag branch at both violation call sites (ON = existing blur+backup, OFF = copy-only), fresh read per violation, plus retroactive blur when the toggle turns ON

**Independent Test**: quickstart.md Scenarios 3–5 — flagged image with toggle ON gets blurred + backed up + alerted exactly as pre-feature; enabling after OFF-mode violations retro-blurs them once; disabling restores nothing (SC-002, SC-006, FR-015)

### Implementation for User Story 2

- [X] T011 [US2] Implement `fun backupOnly(imagePath: String, category: String, confidence: Float): BlurResult` — `blurImage()` steps 1–3 only (backupId → copy → `.meta` with `blurApplied=false`), same `AlreadyBlurred` guard, cleanup-on-failure, gallery file and MediaStore untouched — in `app/src/main/java/com/safeguard/parentalcontrol/util/ImageBlurManager.kt` (contract C3.2; depends on T004)
- [X] T012 [US2] Implement `fun applyBlurToUnblurredBackups(): Int` — iterate `getPendingReviews()`, for `blurApplied == false` entries: blur original in place (`createBlurredBitmap` → `replaceWithBlurred` → `refreshMediaStore`), rewrite metadata with `blurApplied=true`; skip missing/unreadable originals silently; idempotent; no new backups, no alerts — in `app/src/main/java/com/safeguard/parentalcontrol/util/ImageBlurManager.kt` (contract C3.3; depends on T011 — same file, sequential)
- [X] T013 [US2] In `analyzeImage()`, inside the `shouldSendAlert` branch, read `preferencesManager.isMaximumProtectionEnabled` fresh and select `blurImage(...)` (true) vs `backupOnly(...)` (false); keep the existing `when(blurResult)` + alert logic byte-for-byte, in `app/src/main/java/com/safeguard/parentalcontrol/service/MediaFileObserver.kt` (contract C4; depends on T003, T011)
- [X] T014 [P] [US2] In `doWork()`: (a) at scan start, if flag ON call `applyBlurToUnblurredBackups()` and log the count; (b) inside the per-image loop's flagged branch, same fresh-read + `blurImage`/`backupOnly` selection with existing alert logic unchanged, in `app/src/main/java/com/safeguard/parentalcontrol/worker/ImageScanWorker.kt` (contract C4; depends on T003, T011, T012; parallel with T013 — different file)
- [X] T015 [US2] Extend `setMaximumProtection(enabled)`: when `enabled == true`, launch `imageBlurManager.applyBlurToUnblurredBackups()` in `viewModelScope` on `Dispatchers.IO` (inject `ImageBlurManager` into the ViewModel), in `app/src/main/java/com/safeguard/parentalcontrol/presentation/settings/SettingsViewModel.kt` (contract C5; depends on T008, T012)
- [ ] T016 [US2] On-device validation of quickstart.md Scenarios 3, 4, 5 (ON blur parity, retroactive blur incl. missing-file skip and no-duplicate checks, ON→OFF no-restore) per `specs/010-maximum-protection/quickstart.md`

**Checkpoint**: Both call sites branch on the fresh flag; ON mode identical to production; retro pass proven once and idempotent

---

## Phase 5: User Story 3 - Violation handled with Maximum Protection OFF (Priority: P3)

**Goal**: Prove the new default: OFF (or unset) ⇒ gallery untouched, copy in review area, alert still sent — across both detection paths and the review flows

**Independent Test**: quickstart.md Scenario 2 on a fresh state (flag unset) — flagged image stays visible in gallery, `.meta` shows `blurApplied=false`, review entry present, parent alert received (SC-003)

### Implementation for User Story 3

- [ ] T017 [US3] On-device validation of quickstart.md Scenario 2: fresh/unset flag ⇒ copy-only via the `MediaFileObserver` path AND the `ImageScanWorker` path; verify `.meta` contains `blurApplied=false`, gallery byte-identical, `inappropriate_image` alert delivered, per `specs/010-maximum-protection/quickstart.md` (depends on Phase 4 branch tasks T013, T014)
- [ ] T018 [US3] Validate review flows on copy-only entries: list shows entry; Restore keeps original intact in gallery and clears entry; Delete removes gallery file + backup (FR-010, quickstart Scenario 2 steps 5–7 + Scenario 7 review checks) — no code change expected in `app/src/main/java/com/safeguard/parentalcontrol/presentation/imagereview/ImageReviewViewModel.kt`; fix only if validation fails
- [ ] T019 [US3] Validate fresh-read guarantee mid-batch: quickstart.md Scenario 6 — flip toggle between two flagged-image pushes; first follows old value, second follows new value (SC-004), per `specs/010-maximum-protection/quickstart.md`

**Checkpoint**: All three stories independently validated; default behavior confirmed as copy-only

---

## Phase 6: Polish & Cross-Cutting Concerns

- [ ] T020 Run regression sweep quickstart.md Scenario 7: legacy `.meta` without `blurApplied` (parses as `true`, retro pass skips it, restore works); logout→login resets flag to OFF; text review / content filtering / language switch unaffected, per `specs/010-maximum-protection/quickstart.md`
- [X] T021 [P] Review KDoc/comments for the changed surfaces (`backupOnly`, `applyBlurToUnblurredBackups`, `AlreadyBlurred`, `isImageBlurred`, flag property) for accuracy against contracts C1–C4 in `app/src/main/java/com/safeguard/parentalcontrol/util/ImageBlurManager.kt` and `app/src/main/java/com/safeguard/parentalcontrol/util/PreferencesManager.kt`
- [X] T022 Final gate: `./gradlew testDebugUnitTest` and `./gradlew assembleDebug` both green (repository root)

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1 (Setup)**: none
- **Phase 2 (Foundational)**: after T001 — BLOCKS all stories. Internal order: T002 → T003; T004 independent of T002/T003; T005 after T004
- **Phase 3 (US1)**: after Phase 2 (needs T003)
- **Phase 4 (US2)**: after Phase 2 (needs T003, T004); T015 additionally needs T008 from US1
- **Phase 5 (US3)**: after Phase 4 T013+T014 (the branch built in US2 delivers US3's OFF mechanics — deliberate cross-story dependency, see plan.md decision 3)
- **Phase 6 (Polish)**: after all stories

### Story Dependency Notes

- **US1**: independent (foundational only). Deliverable MVP on its own.
- **US2**: foundational + one ViewModel touch-point from US1 (T008). T011–T014 can proceed without US1 if T015 is deferred.
- **US3**: validation-heavy by design — its implementation ships inside US2's branch tasks; keeps priority order P1→P2→P3 while avoiding a compile-broken intermediate state (a branch whose OFF arm has no target).

### Parallel Opportunities

- T005 ‖ T006 ‖ T007 (after T004; three different files)
- T006 ‖ T007 (strings en/ar)
- T013 ‖ T014 (after T011/T012; observer vs worker files)
- T021 parallel with T020

## Parallel Example: after Foundational

```bash
# Three independent files at once:
Task: "T005 unit test in app/src/test/java/com/safeguard/parentalcontrol/util/ImageBlurMetadataTest.kt"
Task: "T006 English strings in app/src/main/res/values/strings.xml"
Task: "T007 Arabic strings in app/src/main/res/values-ar/strings.xml"

# After T011+T012, both call sites at once:
Task: "T013 branch in app/src/main/java/com/safeguard/parentalcontrol/service/MediaFileObserver.kt"
Task: "T014 branch + safety net in app/src/main/java/com/safeguard/parentalcontrol/worker/ImageScanWorker.kt"
```

## Implementation Strategy

### MVP First (User Story 1 Only)

1. T001 → T002–T005 (foundation)
2. T006–T010 (toggle + PIN)
3. **STOP and VALIDATE**: Scenario 1. Toggle is shippable alone — enforcement still behaves exactly as production until Phase 4 lands.

### Incremental Delivery

1. Foundation → US1 (toggle, MVP) → validate
2. US2 (branch + retro) → validate Scenarios 3–5 — ⚠️ this is the release-visible behavior change: default OFF stops blurring until a parent enables Maximum Protection (spec edge case; make sure stakeholders signed off)
3. US3 (OFF-mode validation) → validate Scenario 2, 6
4. Polish → Scenario 7 + final build/test gate

### Notes

- Same-file tasks are intentionally sequential (T011→T012; T004 before T005): avoids merge conflicts
- Commit after each task or logical group
- All alert/dedup logic stays byte-identical at call sites — any diff there beyond the branch is scope creep
