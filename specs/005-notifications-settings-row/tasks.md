# Tasks: Notifications Permission Row in Settings

**Input**: Design documents from `/specs/005-notifications-settings-row/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/ui-contract.md, quickstart.md

**Tests**: Not requested. Build checkpoint (`./gradlew assembleDebug`) is the only automated gate, per task scope.

**Organization**: Single user story (US1, P1). No setup or foundational phase needed — all infrastructure (`ProtectionStatusHelper`, `SettingsSection`/`SettingsItem`, ON_RESUME observer, injected context) already exists and is reused as-is.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to
- Include exact file paths in descriptions

## Path Conventions

Single Android module. All paths relative to repo root; source prefix: `app/src/main/java/com/safeguard/parentalcontrol/`.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: None required — feature modifies two existing files only. No dependencies, DI, manifest, or resource changes.

*(No tasks.)*

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: None required — `ProtectionStatusHelper.isNotificationsEnabled()` (util/ProtectionStatusHelper.kt:309), `SettingsSection`/`SettingsItem` composables, and the ON_RESUME `DisposableEffect` observer all pre-exist.

*(No tasks.)*

---

## Phase 3: User Story 1 - See notification permission state and open system settings (Priority: P1) 🎯 MVP

**Goal**: Settings screen shows a "Notifications" row reflecting live permission state; tap opens app-scoped system notification settings; state auto-refreshes on resume.

**Independent Test**: Disable app notifications in system settings → open Settings screen → row shows "Off — tap to enable" + "Off" (error color). Tap row → system notification settings for this app opens. Enable, press back → row shows "On" + "On" (primary color) without manual refresh. (Full script: quickstart.md S1–S3.)

### Implementation for User Story 1

- [X] T001 [US1] Add `notificationsEnabled: Boolean = false` field to `SettingsUiState` data class in `app/src/main/java/com/safeguard/parentalcontrol/presentation/settings/SettingsViewModel.kt` (data class at lines 30–38)
- [X] T002 [US1] In the same file, add import `com.safeguard.parentalcontrol.util.ProtectionStatusHelper` and public function `refreshNotificationStatus()` that does `_uiState.update { it.copy(notificationsEnabled = ProtectionStatusHelper.isNotificationsEnabled(context)) }`; call it from the `init` block after `loadContentFilteringState()`. Do NOT add constructor params — `@ApplicationContext context` already injected. (Contract: contracts/ui-contract.md § ViewModel contract; depends on T001)
- [X] T003 [US1] Extend existing `DisposableEffect(lifecycleOwner)` ON_RESUME branch in `app/src/main/java/com/safeguard/parentalcontrol/presentation/settings/SettingsScreen.kt` (lines 65–75): add `viewModel.refreshNotificationStatus()` next to `viewModel.refreshContentFilteringState()`. Do NOT add a second observer. (Depends on T002)
- [X] T004 [US1] In `SettingsScreen.kt`, add new `SettingsItem` as FIRST row inside existing `SettingsSection(title = "Notifications")` (currently lines 257–271), followed by `Divider(modifier = Modifier.padding(horizontal = 16.dp))` before the existing "Push Notifications" row. Row spec: `icon = Icons.Default.Notifications`; `title = "Notifications"`; `subtitle = if (uiState.notificationsEnabled) "On" else "Off — tap to enable"`; `trailing` = `Text("On"/"Off", style = MaterialTheme.typography.bodySmall, color = primary if on / error if off)`; `onClick` = try/catch launching `Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)` with `putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)`, catch → `Timber.e(e, "Failed to open notification settings")`. CRITICAL: fully-qualify `android.provider.Settings` — unqualified import shadows `Icons.Default.Settings` usage at line 204 (research.md R3). (Contract: contracts/ui-contract.md § Screen contract; depends on T001)

**Checkpoint**: Row renders with correct state, tap opens system settings, resume refreshes state — US1 fully functional.

---

## Phase 4: Polish & Cross-Cutting Concerns

**Purpose**: Verify build cleanliness and scope discipline.

- [X] T005 Run `./gradlew assembleDebug` from repo root — MUST pass clean (build checkpoint from plan.md)
- [X] T006 Verify scope guardrails: `git diff --name-only` shows ONLY `SettingsViewModel.kt` and `SettingsScreen.kt` changed; no manifest, DI, repository, service, worker, navigation, or network changes (quickstart.md S5)
- [ ] T007 Execute quickstart.md validation scenarios S1–S3 on device/emulator (API 33+ preferred); confirm existing "Push Notifications" row and content-filtering resume refresh unaffected (S5 regression guard) — **NOT RUN**: no `adb`/device available in this environment. Manual verification required before merge.

---

## Dependencies & Execution Order

### Phase Dependencies

- Phases 1–2: empty — start directly at Phase 3
- **Phase 3 (US1)**: no prerequisites
- **Phase 4 (Polish)**: depends on Phase 3 complete

### Task Dependencies

```text
T001 (UiState field)
 ├─▶ T002 (refresh fn + init)  ─▶ T003 (observer extension)
 └─▶ T004 (row UI)
T003 + T004 ─▶ T005 (build) ─▶ T006 (scope check) ─▶ T007 (device validation)
```

### Parallel Opportunities

- None practical: T003/T004 touch the same file (`SettingsScreen.kt`), T001/T002 the same file (`SettingsViewModel.kt`), and the screen tasks depend on the ViewModel field. Execute sequentially T001 → T002 → T003 → T004 — total delta ~30 LOC.

---

## Implementation Strategy

Single-story MVP. Execute T001–T004 in order, then validate with T005–T007. Feature is one coherent increment; no partial-delivery split makes sense at this size.

---

## Notes

- No [P] tasks — file overlap forces sequential execution
- Inline strings only; no `strings.xml` extraction (task guardrail)
- Constitution check already PASS in plan.md — no violations to track
- Commit after T005 passes clean
