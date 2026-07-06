# Tasks: Child Pairing Gate for Monitoring

**Input**: Design documents from `/specs/004-child-pairing-gate/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/family-parents-api.md, quickstart.md

**Tests**: NOT included — feature directive explicitly says "do not add tests"; verification is manual/log-based per quickstart.md.

**Organization**: Tasks grouped by user story. Note: this feature has an unusually shared core — the data path (Phase 2) and the single gate (US1) mechanically implement all three stories; US2 and US3 phases are verification-only, confirming behavior the shared code must exhibit.

**Scope guardrail (applies to every task)**: ONLY these six files may change:
`util/Constants.kt`, `util/PreferencesManager.kt`, `data/model/Models.kt`, `data/remote/ApiService.kt`, `data/repository/FamilyRepository.kt`, `service/MonitoringService.kt` — all under `app/src/main/java/com/safeguard/parentalcontrol/`. No ViewModels, Composables, DI modules, Workers, Receivers, ML, VPN, alert logic, or enforcement internals.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (US1, US2, US3)

## Phase 1: Setup

**Purpose**: Confirm baseline before touching code

- [X] T001 Verify on branch `004-child-pairing-gate` and baseline build passes: `./gradlew assembleDebug` (repo root)

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Paired-status data path — source of truth every story depends on (plan.md Phase 1)

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

- [X] T002 [P] Add `const val KEY_HAS_LINKED_PARENT = "has_linked_parent"` to the existing `KEY_*` block (lines 9–32, follow existing naming/style) in `app/src/main/java/com/safeguard/parentalcontrol/util/Constants.kt`
- [X] T003 [P] Add tolerant `LinkedParent` DTO (all fields nullable: `id: Int?`, `parentId: Int?` @SerializedName("parent_id"), `parentEmail: String?` @SerializedName("parent_email"), `parentName: String?` @SerializedName("parent_name"), `createdAt: Date?` @SerializedName("created_at")) with `// TODO(backend): confirm exact field names for GET /family/parents.` comment, near the existing `FamilyLink` model in `app/src/main/java/com/safeguard/parentalcontrol/data/model/Models.kt` (Gson + `java.util.Date` already imported; exact shape in data-model.md)
- [X] T004 [P] Add `@GET("family/parents") suspend fun getLinkedParents(): Response<List<LinkedParent>>` with KDoc `/** Get all parents linked to this child (child only). Uses the user Bearer token. */` in the `Family Link Endpoints` section (after `getLinkedChildren`, ~line 275) of `app/src/main/java/com/safeguard/parentalcontrol/data/remote/ApiService.kt` (contract: contracts/family-parents-api.md)
- [X] T005 Add `hasLinkedParent: Boolean` var (getter: `encryptedPrefs.getBoolean(Constants.KEY_HAS_LINKED_PARENT, false)`; setter: `.edit().putBoolean(...).apply()` — mirror the `isDeviceRegistered` pattern at lines 76–78) in `app/src/main/java/com/safeguard/parentalcontrol/util/PreferencesManager.kt`; NO `clearAll()` change (depends on T002)
- [X] T006 In `app/src/main/java/com/safeguard/parentalcontrol/data/repository/FamilyRepository.kt`: add `private val preferencesManager: PreferencesManager` constructor param (Hilt resolves — no DI-module change); add `suspend fun getLinkedParents(): NetworkResult<List<LinkedParent>>` = `withContext(Dispatchers.IO) { safeApiCall { apiService.getLinkedParents() } }`; add `suspend fun refreshLinkedParentStatus(): Boolean` with exact outcome classification — `Success` non-empty → flag true, return true; `Success` empty → flag false, return false; `Error`/else (`Loading` branch of sealed class) → flag untouched, return cached value; Timber logs per plan.md Phase 1 step 5 (depends on T003, T004, T005)
- [X] T007 **BUILD CHECKPOINT**: `./gradlew assembleDebug` must pass (depends on T002–T006)

**Checkpoint**: Data path complete — flag storage, DTO, endpoint, repository semantics all in place

---

## Phase 3: User Story 1 - Unpaired child device stays dormant (Priority: P1) 🎯 MVP

**Goal**: Never-confirmed child device does NOT start enforcement/image monitoring; background refresh flips flag and starts monitoring at runtime when a parent link is confirmed

**Independent Test**: Fresh child install, no parent linked, start service → gated log, no enforcement/image monitoring; link parent, refresh runs → monitoring starts (quickstart.md Scenarios 1–2)

### Implementation for User Story 1

- [X] T008 [US1] Add `@Inject lateinit var familyRepository: FamilyRepository` field (match existing `@Inject lateinit var` pattern, ~line 68) in `app/src/main/java/com/safeguard/parentalcontrol/service/MonitoringService.kt`
- [X] T009 [US1] Replace child-start block in `onStartCommand` (lines 253–263) with pairing gate: `!isParent` → if `preferencesManager.hasLinkedParent` and `!isEnforcementLoopRunning` → log "Child is paired -> starting enforcement + image monitoring", `startEnforcementLoop()`, `startImageMonitoring()`; else (unpaired) → log "Child NOT yet paired -> monitoring gated; refreshing parent link", call `refreshParentLinkAndMaybeStartMonitoring()`; parent branch → log "Parent device -> skipping enforcement loop". Exact code in plan.md Phase 2 step 2. Do NOT touch consent gate (line 224), WorkManager scheduling, heartbeat, notification, `startEnforcementLoop()`/`startImageMonitoring()`/`performInitialSync()` bodies. In `app/src/main/java/com/safeguard/parentalcontrol/service/MonitoringService.kt` (depends on T008)
- [X] T010 [US1] Add private helper `refreshParentLinkAndMaybeStartMonitoring()` using existing `serviceScope.launch`: `val paired = familyRepository.refreshLinkedParentStatus()`; if `paired && !isEnforcementLoopRunning` → log "Parent link confirmed at runtime -> starting monitoring now", `startEnforcementLoop()`, `startImageMonitoring()`. KDoc per plan.md Phase 2 step 3. In `app/src/main/java/com/safeguard/parentalcontrol/service/MonitoringService.kt` (depends on T009)
- [X] T011 [US1] **BUILD CHECKPOINT**: `./gradlew assembleDebug` must pass (depends on T008–T010)
- [ ] T012 [US1] Manual verification — quickstart.md Scenario 1 (fresh unpaired stays gated: gated log present, NO `=== ENFORCEMENT LOOP STARTED ===`, NO `Started MediaFileObserver`) and Scenario 2 (pairing confirmed → `refreshLinkedParentStatus: parents=1, paired=true` → "starting monitoring now")

**Checkpoint**: US1 fully functional — MVP deliverable

---

## Phase 4: User Story 2 - Paired device works offline and deterministically (Priority: P1)

**Goal**: Cached-flag gate makes an already-paired device start monitoring with zero network dependency; network errors never mutate the flag

**Independent Test**: Paired device (flag true), airplane mode, service restart → monitoring starts normally (quickstart.md Scenario 3)

### Implementation for User Story 2

> No new code — the cached-read gate (T009) and error-freezes-flag semantics (T006) ARE this story. Verification confirms the properties.

- [ ] T013 [US2] Manual verification — quickstart.md Scenario 3: with `hasLinkedParent=true`, enable airplane mode, force-stop + reopen app; expect `"Child is paired -> starting enforcement + image monitoring"` with no network; confirm flag unchanged after any failing background calls (SC-002, SC-003)

**Checkpoint**: Offline determinism + tamper resilience proven

---

## Phase 5: User Story 3 - Confirmed unpair stops future monitoring (Priority: P2)

**Goal**: Successful empty parent-list response flips flag false; monitoring stays stopped on next gated start

**Independent Test**: Backend returns `200 []` on refresh → flag false, monitoring not started (quickstart.md Scenario 4)

### Implementation for User Story 3

> No new code — empty-list → flag-false classification lives in T006. Verification confirms it.

- [ ] T014 [US3] Manual verification — quickstart.md Scenario 4: parent unlinks child; trigger refresh from a gated start; expect `refreshLinkedParentStatus: parents=0, paired=false`; monitoring stays stopped; note locked-design caveat (paired device doesn't re-poll at start — plan.md Design Notes)

**Checkpoint**: All three stories verified

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Scope integrity + hand-off

- [X] T015 Scope check: `git diff --stat main` shows ONLY the six scoped files changed; revert anything else (verified — six scoped files + speckit artifacts; LockScreenActivity.kt/ContentFilterVpnService.kt diffs pre-existed this feature and were left untouched)
- [X] T016 [P] Final build: `./gradlew assembleDebug` green
- [X] T017 [P] Hand off backend coordination items (plan.md Backend Coordination / contracts/family-parents-api.md Open items): confirm schema field names, child-role JWT auth, 200-empty (not 404), unpair-propagation requirements

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: none
- **Foundational (Phase 2)**: depends on Setup — BLOCKS all stories
- **US1 (Phase 3)**: depends on Foundational; delivers the gate all stories share
- **US2 (Phase 4)**: verification-only; depends on US1 code (T009) being in place
- **US3 (Phase 5)**: verification-only; depends on Foundational (T006) + gate (T009)
- **Polish (Phase 6)**: depends on all stories

### Task-level graph

```text
T001 → T002 ─┬→ T005 ─┐
       T003 ─┼────────┼→ T006 → T007 → T008 → T009 → T010 → T011 → T012 (US1)
       T004 ─┘        │                                        ├→ T013 (US2)
                      │                                        └→ T014 (US3)
                      └→ (T012–T014) → T015 → T016, T017
```

### Parallel Opportunities

- **T002, T003, T004** — three different files, no interdependency → parallel
- **T013, T014** — independent manual verifications after T011 → parallel with each other (and with T012)
- **T016, T017** — parallel after T015

## Parallel Example: Foundational

```bash
# Launch together after T001:
Task: "Add KEY_HAS_LINKED_PARENT constant in util/Constants.kt"
Task: "Add LinkedParent DTO in data/model/Models.kt"
Task: "Add getLinkedParents endpoint in data/remote/ApiService.kt"
# Then T005 (needs T002), then T006 (needs T003+T004+T005), then T007 build gate.
```

## Implementation Strategy

### MVP First (US1)

1. Phase 1 → Phase 2 (data path, build gate T007)
2. Phase 3 (gate + helper, build gate T011, verify Scenarios 1–2)
3. **STOP and VALIDATE** — US1 alone is a shippable safety increment: unpaired devices gated

### Incremental Delivery

- US2/US3 add zero code — they are property verifications of the shared core. Run T013/T014 as soon as T011 passes; total wall-clock cost is manual test time only.
- Sequential single-dev path: T001→T002→T003→T004→T005→T006→T007→T008→T009→T010→T011→T012→T013→T014→T015→T016→T017

## Notes

- Exact code bodies for T005/T006/T009/T010 are in plan.md (Implementation Phases) — copy faithfully; behavior is locked by the feature directive.
- `when` over `NetworkResult` must be exhaustive (sealed class has `Loading`) — else-branch returns cached value (research.md D6).
- Commit after each build checkpoint (T007, T011) at minimum.
