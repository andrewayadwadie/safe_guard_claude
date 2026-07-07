# Research: Notifications Permission Row in Settings

**Feature**: 005-notifications-settings-row | **Date**: 2026-07-06

No NEEDS CLARIFICATION markers existed in Technical Context. Research items below resolve implementation-pattern decisions against the actual codebase.

## R1 — Status-check source

**Decision**: Reuse `ProtectionStatusHelper.isNotificationsEnabled(context)` (ProtectionStatusHelper.kt:309).

**Rationale**: Constitution Principle IV names `ProtectionStatusHelper` as the single source of truth for protection statuses. The function already exists and handles the API-level split: on API 33+ it queries `NotificationManager.areNotificationsEnabled()`; below 33 it returns `true` (notifications on by default pre-Android 13). `minSdk 26` means pre-13 devices will effectively always show "On" — matches existing `PermissionsSetupScreen` behavior, so consistent app-wide.

**Alternatives considered**: `NotificationManagerCompat.from(context).areNotificationsEnabled()` directly in the ViewModel — rejected: duplicates the helper, violates single-source-of-truth rule and the task guardrail (reuse existing infra).

## R2 — System-settings intent pattern

**Decision**: `Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply { putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName) }`, launched in try/catch with `Timber.e` on failure.

**Rationale**: Exact pattern already proven in `PermissionsSetupScreen.kt:362-371`. `ACTION_APP_NOTIFICATION_SETTINGS` + `EXTRA_APP_PACKAGE` is valid API 26+ (both added in API 26), so no version branch needed at `minSdk 26`. Launching from a Composable's `context` (Activity context via `LocalContext.current`) needs no `FLAG_ACTIVITY_NEW_TASK`; the reference screen includes the flag defensively and it is harmless — omit unless desired for symmetry. try/catch guards OEM ROMs that mishandle the intent.

**Alternatives considered**:
- Runtime `POST_NOTIFICATIONS` permission request — rejected: task explicitly forbids new permission-request flow; settings-routing also survives the "denied twice, can't re-ask" dead end.
- `ACTION_APPLICATION_DETAILS_SETTINGS` fallback chain — rejected: out of scope; failure path is log-only per spec FR-007.

## R3 — `Settings` name collision in SettingsScreen.kt

**Decision**: Use the fully-qualified `android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS` / `android.provider.Settings.EXTRA_APP_PACKAGE` at the call site (or an import alias `import android.provider.Settings as AndroidSettings`).

**Rationale**: `SettingsScreen.kt` already references `Icons.Default.Settings` (line 204) via the wildcard `androidx.compose.material.icons.filled.*` import. Importing `android.provider.Settings` unqualified would shadow the icon reference and break compilation. `PermissionsSetupScreen.kt` imports `android.provider.Settings` directly because its icon usage differs. Fully-qualified usage is the minimal-diff, zero-risk option.

**Alternatives considered**: Renaming the icon usage — rejected: touches unrelated lines.

## R4 — Row placement inside "Notifications" section

**Decision**: New permission row placed FIRST in the existing `SettingsSection(title = "Notifications")`, followed by a `Divider(modifier = Modifier.padding(horizontal = 16.dp))`, then the existing "Push Notifications" (Coming soon) row.

**Rationale**: Clarification session pinned the section (existing "Notifications" section, no new section). Permission state is actionable; "Push Notifications" is a disabled placeholder ("Coming soon") — actionable items lead. Divider style matches every multi-row section in the file (lines 189, 211, 246, 288, 307, 314).

**Alternatives considered**: After the existing row — viable, but placeholder-above-actionable is worse UX; either passes spec FR-001.

## R5 — Trailing indicator styling

**Decision**: `Text("On"/"Off", style = MaterialTheme.typography.bodySmall)`, color `MaterialTheme.colorScheme.primary` when on, `MaterialTheme.colorScheme.error` when off.

**Rationale**: Task brief says "green if on, error color if off". The codebase's design system (003-design-system-restyle) expresses positive/active state via `colorScheme.primary` (see content-filtering trailing icon, SettingsScreen.kt:225-229) rather than a hardcoded green — hardcoded `Color.Green` would violate the restyle's token discipline and look wrong in dark theme. `bodySmall` matches the existing trailing-text precedent ("Coming soon", line 264-268).

**Alternatives considered**: Hardcoded `Color(0xFF4CAF50)` — rejected: bypasses theme tokens, breaks dark mode consistency.

## R6 — Refresh trigger

**Decision**: Extend the existing `DisposableEffect(lifecycleOwner)` ON_RESUME observer (SettingsScreen.kt:65-75) with `viewModel.refreshNotificationStatus()`; also call once in ViewModel `init`.

**Rationale**: Task brief mandates extending the existing observer, not adding one. ON_RESUME fires on return from system settings (activity resumes), covering spec FR-005. `init` call covers first composition before any resume event lands with correct state (avoids default-`false` flash for enabled devices).

**Alternatives considered**: `rememberLauncherForActivityResult(StartActivityForResult)` to detect return — rejected: unnecessary; resume observer already exists and result code from settings screen carries no signal anyway.
