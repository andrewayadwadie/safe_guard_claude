# Implementation Plan: Notifications Permission Row in Settings

**Branch**: `005-notifications-settings-row` | **Date**: 2026-07-06 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/005-notifications-settings-row/spec.md`

## Summary

Add a "Notifications" permission row to the existing "Notifications" section of the Settings screen. The row displays the current notification-permission state (via `ProtectionStatusHelper.isNotificationsEnabled(context)`), opens the app-scoped system notification settings on tap (`Settings.ACTION_APP_NOTIFICATION_SETTINGS` + `EXTRA_APP_PACKAGE`), and refreshes the displayed state on `ON_RESUME` through the already-existing lifecycle observer in `SettingsScreen`. Exactly two files change: `SettingsViewModel.kt` and `SettingsScreen.kt`. No new files, no DI changes, no manifest changes.

## Technical Context

**Language/Version**: Kotlin (JDK 21 toolchain, `jvmTarget = 17`)

**Primary Dependencies**: Jetpack Compose (BOM 2023.10.01, Material 3), Hilt 2.51.1, Timber 5.0.1, androidx.lifecycle (`collectAsStateWithLifecycle`, `LifecycleEventObserver`)

**Storage**: N/A — permission state read live from OS via `NotificationManager.areNotificationsEnabled()` (wrapped by `ProtectionStatusHelper`); nothing persisted

**Testing**: Build checkpoint only — `./gradlew assembleDebug` must pass clean (per task scope; no new test files)

**Target Platform**: Android, `minSdk 26` / `targetSdk 35`. Note: below API 33 (`TIRAMISU`), `isNotificationsEnabled` returns `true` unconditionally, so the row will show "On" on pre-13 devices unless the user disabled the app's notification channel access — acceptable per existing helper semantics

**Project Type**: Mobile app (single Android module `app/`)

**Performance Goals**: Status check is a synchronous system-service read (<1ms); executed in `init` and on each `ON_RESUME` — negligible

**Constraints**:
- Modify ONLY `SettingsViewModel.kt` and `SettingsScreen.kt`
- Reuse `ProtectionStatusHelper.isNotificationsEnabled(context)` — no new helper
- Extend existing `DisposableEffect` ON_RESUME observer — do not add a second observer
- Reuse existing `SettingsSection` / `SettingsItem` composables
- Inline strings (no `strings.xml` extraction)
- Do NOT touch: manifest, backend/API, repositories, DI graph, workers, receivers, services, ML pipeline, navigation routes, networking

**Scale/Scope**: One settings row, one UiState field, one refresh function. ~30 LOC total delta

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| # | Rule | Status | Notes |
|---|------|--------|-------|
| I | Child Safety & Privacy First | ✅ PASS | No child data captured, stored, or transmitted. Reads a local OS permission bit only. Data-flow note: nothing leaves the device. |
| II | MVVM + Clean Architecture | ✅ PASS | State lives in `SettingsUiState` (data class); update via `_uiState.update { it.copy(...) }`; screen collects with `collectAsStateWithLifecycle()`. No business logic in Composable beyond intent launch (matches existing `openUrl` / `PermissionsSetupScreen` precedent). |
| III | Two-Role Architecture | ✅ PASS | Presentation-only; no enforcement logic touched. Row visible to both roles (section is role-unconditional today). |
| IV | Native Services & Permission Hygiene | ✅ PASS | Notification permission granted via system-settings routing, never `requestPermissions()` — exactly the mandated pattern. `ProtectionStatusHelper` remains the single source of truth for the status check. No manifest permission added. |
| V | Security & Secrets Hygiene | ✅ PASS | No tokens, no network, no secrets. |
| Rule 1 (feature sub-package) | ✅ PASS | Extends existing `presentation/settings/` package; no new feature package needed (modification, not new feature screen). |
| Rule 2 (@HiltViewModel) | ✅ PASS | `SettingsViewModel` already `@HiltViewModel`; unchanged. |
| Rules 3–5, 9 (repos/API/tokens/NetworkResult) | ✅ N/A | No repository or network involvement. |
| Rule 6 (settings routing for special perms) | ✅ PASS | POST_NOTIFICATIONS handled via `ACTION_APP_NOTIFICATION_SETTINGS` deep-link, consistent with rule's intent. |
| Rules 7–8 (services/workers) | ✅ N/A | None touched. |
| Rule 10 (Snackbar errors) | ✅ PASS | Intent-launch failure is logged via `Timber.e` (matches `PermissionsSetupScreen` + `openUrl` precedent); no new error surface introduced, existing Snackbar path untouched. |
| Rule 11 (ProGuard) | ✅ N/A | No new Service/Receiver/Worker. |
| Rule 12 (noCompress tflite) | ✅ N/A | `build.gradle` untouched. |

**Gate result**: PASS — no violations, Complexity Tracking empty.

**Post-Phase-1 re-check**: PASS — design artifacts introduce no new components, dependencies, or data flows.

## Project Structure

### Documentation (this feature)

```text
specs/005-notifications-settings-row/
├── plan.md              # This file
├── spec.md              # Feature spec (with clarifications)
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/
│   └── ui-contract.md   # Phase 1 output — Settings row UI contract
├── checklists/
│   └── requirements.md
└── tasks.md             # Phase 2 output (/speckit-tasks — NOT created here)
```

### Source Code (repository root)

```text
app/src/main/java/com/safeguard/parentalcontrol/
├── presentation/settings/
│   ├── SettingsViewModel.kt    # MODIFIED — add notificationsEnabled to UiState + refreshNotificationStatus()
│   └── SettingsScreen.kt       # MODIFIED — extend ON_RESUME observer + add SettingsItem row
├── presentation/setup/
│   └── PermissionsSetupScreen.kt  # READ-ONLY reference — intent pattern copied from here (lines ~362-371)
└── util/
    └── ProtectionStatusHelper.kt  # READ-ONLY — isNotificationsEnabled(context) reused as-is
```

**Structure Decision**: Existing single-module Android app; changes confined to the two files in `presentation/settings/`. Reference files listed for pattern fidelity only — they are not modified.

## Implementation Design

### Change 1 — `SettingsViewModel.kt`

1. `SettingsUiState`: add field `val notificationsEnabled: Boolean = false`.
2. Add import: `com.safeguard.parentalcontrol.util.ProtectionStatusHelper`.
3. Add function:
   ```kotlin
   fun refreshNotificationStatus() {
       _uiState.update {
           it.copy(notificationsEnabled = ProtectionStatusHelper.isNotificationsEnabled(context))
       }
   }
   ```
4. `init` block: call `refreshNotificationStatus()` after `loadContentFilteringState()`.
5. Context already injected via `@ApplicationContext private val context: Context` — no constructor change.

### Change 2 — `SettingsScreen.kt`

1. Extend existing `DisposableEffect(lifecycleOwner)` ON_RESUME branch (SettingsScreen.kt:65-75):
   ```kotlin
   if (event == Lifecycle.Event.ON_RESUME) {
       viewModel.refreshContentFilteringState()
       viewModel.refreshNotificationStatus()
   }
   ```
2. Add imports: `android.provider.Settings` (aliased or fully-qualified if it collides with the screen's own naming — see research.md R3).
3. In the existing `SettingsSection(title = "Notifications")` block (SettingsScreen.kt:257-271), add the new row (with `Divider` separator, matching sibling-section style) alongside the existing "Push Notifications" row:
   ```kotlin
   SettingsItem(
       icon = Icons.Default.Notifications,
       title = "Notifications",
       subtitle = if (uiState.notificationsEnabled) "On" else "Off — tap to enable",
       onClick = {
           try {
               context.startActivity(
                   Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                       putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                   }
               )
           } catch (e: Exception) {
               Timber.e(e, "Failed to open notification settings")
           }
       },
       trailing = {
           Text(
               text = if (uiState.notificationsEnabled) "On" else "Off",
               style = MaterialTheme.typography.bodySmall,
               color = if (uiState.notificationsEnabled) {
                   MaterialTheme.colorScheme.primary
               } else {
                   MaterialTheme.colorScheme.error
               }
           )
       }
   )
   ```
   Placement: new row FIRST (permission state), existing "Push Notifications" row second, `Divider(modifier = Modifier.padding(horizontal = 16.dp))` between them — see research.md R4.

### Build checkpoint

`./gradlew assembleDebug` must pass clean after both changes.

## Complexity Tracking

> No constitution violations — table intentionally empty.
