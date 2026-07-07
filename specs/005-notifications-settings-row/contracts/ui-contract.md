# UI Contract: Notifications Permission Row

**Feature**: 005-notifications-settings-row | **Date**: 2026-07-06

No external API contracts (no backend, no network). The exposed interface is a Settings-screen UI element and one ViewModel function.

## ViewModel contract

### `SettingsViewModel.refreshNotificationStatus()`

| Aspect | Contract |
|---|---|
| Visibility | `public` (called from Composable) |
| Input | none |
| Effect | Sets `uiState.notificationsEnabled` to current value of `ProtectionStatusHelper.isNotificationsEnabled(context)` via `_uiState.update { it.copy(...) }` |
| Threading | Main-safe (synchronous system-service read; no coroutine required) |
| Errors | none thrown; helper returns Boolean unconditionally |
| Idempotency | Yes — repeated calls converge on current OS state |
| Call sites | ViewModel `init`; SettingsScreen ON_RESUME observer |

## Screen contract

### Row: "Notifications" (inside existing "Notifications" section, first row)

| Aspect | Contract |
|---|---|
| Component | Existing private `SettingsItem` composable |
| Icon | `Icons.Default.Notifications` |
| Title | `"Notifications"` (inline string) |
| Subtitle | `uiState.notificationsEnabled ? "On" : "Off — tap to enable"` |
| Trailing | `Text("On"/"Off", bodySmall)`; tint `colorScheme.primary` when on, `colorScheme.error` when off |
| Divider | `Divider(padding horizontal 16.dp)` between this row and existing "Push Notifications" row |
| onClick | Launch `Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)` with `putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)`; wrapped in try/catch; on failure `Timber.e(e, "Failed to open notification settings")` — no crash, no UI error |
| Visibility | Both roles (section is role-unconditional) |

### ON_RESUME observer (existing, extended)

| Aspect | Contract |
|---|---|
| Location | Existing `DisposableEffect(lifecycleOwner)` in `SettingsScreen` |
| Added behavior | `viewModel.refreshNotificationStatus()` called alongside `viewModel.refreshContentFilteringState()` on `Lifecycle.Event.ON_RESUME` |
| Constraint | NO second observer added |

## Non-goals (contract boundaries)

- No runtime `POST_NOTIFICATIONS` request flow
- No new composables, files, DI providers, or navigation routes
- No string resources
- No change to existing "Push Notifications" placeholder row
