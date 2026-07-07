# Data Model: Notifications Permission Row in Settings

**Feature**: 005-notifications-settings-row | **Date**: 2026-07-06

No persistent entities. One transient UI-state field.

## SettingsUiState (modified)

Existing `data class` in `presentation/settings/SettingsViewModel.kt`. One field added.

| Field | Type | Default | Source | Notes |
|-------|------|---------|--------|-------|
| `notificationsEnabled` | `Boolean` | `false` | `ProtectionStatusHelper.isNotificationsEnabled(context)` | NEW. Live OS read; never persisted. Refreshed in `init` and on every ON_RESUME of SettingsScreen. |
| *(all existing fields)* | — | — | — | Unchanged: `userName`, `userEmail`, `userRole`, `isContentFilteringEnabled`, `isLoading`, `logoutSuccess`, `error` |

### Validation rules

- Field is derived, never user-set; only `refreshNotificationStatus()` writes it, via `_uiState.update { it.copy(...) }`.
- On devices below API 33, source function returns `true` unconditionally (helper semantics; documented in research.md R1).

### State transitions

```
[init] ──refreshNotificationStatus()──▶ notificationsEnabled = OS value
[ON_RESUME] ──refreshNotificationStatus()──▶ notificationsEnabled = OS value (idempotent)
```

No other writer. No transition side effects.

## Derived UI mapping

| `notificationsEnabled` | Subtitle | Trailing text | Trailing color |
|---|---|---|---|
| `true` | `"On"` | `"On"` | `colorScheme.primary` |
| `false` | `"Off — tap to enable"` | `"Off"` | `colorScheme.error` |
