# Data Model: Maximum Protection

**Feature**: 010-maximum-protection | **Date**: 2026-07-19

No database (Room) or backend entities change. All data is device-local.

## Entity 1: Maximum Protection Setting

| Aspect | Value |
|---|---|
| Storage | EncryptedSharedPreferences (`safeguard_prefs`) via `PreferencesManager` |
| Key | `Constants.KEY_MAXIMUM_PROTECTION_ENABLED` = `"maximum_protection_enabled"` |
| Type | `Boolean` |
| Default | `false` (key absent ⇒ OFF) |
| Access | `PreferencesManager.isMaximumProtectionEnabled` (typed get/set property) |
| Lifecycle | Written only after successful parent-PIN verification; wiped by `clearAll()` on logout (reverts to default OFF) |
| Readers | `MediaFileObserver.analyzeImage()`, `ImageScanWorker.doWork()` (fresh read per violation), `SettingsViewModel` (UI state) |
| Writers | `SettingsViewModel.setMaximumProtection(Boolean)` — sole writer |

### Validation rules

- Value may only change following a successful `verifyParentPin()` (or first-time `setParentPin()`) in the same interaction (FR-002).
- No other code path mutates the key (FR-003).

### State transitions

```text
absent (OFF) ──PIN ok──▶ true (ON) ──PIN ok──▶ false (OFF) ──PIN ok──▶ true …
     │                        │
  logout: clearAll()      logout: clearAll() → absent (OFF)
```

- `false→true` side effect: trigger retroactive blur pass (FR-013).
- `true→false` side effect: none (FR-015 — no auto-restore).

## Entity 2: Violation Backup Metadata (`ImageBlurManager.ImageMetadata`) — EXTENDED

Sidecar file `<backupId>.meta` in `filesDir/image_backup/`, key=value lines. Existing fields unchanged; one field added.

| Field | Type | New? | Notes |
|---|---|---|---|
| `backupId` | String | — | SHA-256(path) first 16 bytes hex (unchanged) |
| `originalPath` | String | — | Gallery file path (unchanged) |
| `category` | String | — | Detection category (unchanged) |
| `confidence` | Float | — | Detection confidence (unchanged) |
| `timestamp` | Long | — | Detection time (unchanged) |
| `originalSize` | Long | — | Bytes (unchanged) |
| `originalName` | String | — | File name (unchanged) |
| `blurApplied` | Boolean | ✅ NEW | `true` = gallery file was replaced with blurred version; `false` = copy-only (gallery untouched) |

### Compatibility rule (critical)

`loadMetadata()`: missing `blurApplied` key ⇒ **`true`**. Legacy `.meta` files predate copy-only mode and were always written by the blur flow. Wrong default (`false`) would make the retroactive pass re-blur already-blurred galleries — harmless visually but wasteful; more importantly `true` keeps `blurApplied` semantics truthful for legacy data.

### State transitions

```text
(violation, flag ON)  ──▶ backup + meta{blurApplied=true}   [gallery blurred]
(violation, flag OFF) ──▶ backup + meta{blurApplied=false}  [gallery untouched]

meta{blurApplied=false} ──retro pass (flag ON)──▶ meta{blurApplied=true} [gallery blurred; same backup]
meta{blurApplied=false} ──retro pass, original missing──▶ unchanged (skip, no error)

any meta ──parent restore──▶ backup+meta deleted, original bytes restored/confirmed in gallery
any meta ──parent delete───▶ backup+meta deleted, gallery file deleted
```

### Identity & uniqueness

Unchanged: one backup per `backupId` (path hash). `backupFile.exists()` guard (`AlreadyBlurred`) prevents duplicate copies across modes and across re-detections — this is what makes retro-pass + re-scan interplay safe (FR-014).

## Entity 3: Parent PIN — UNCHANGED (new consumer only)

Existing salted-hash storage (`KEY_PARENT_PIN_HASH`/`KEY_PARENT_PIN_SALT`) in `PreferencesManager`; verify/create via `ParentPinViewModel` → `ParentPinDialog`. This feature adds one more consumer (the toggle) and changes nothing about storage or verification.

## UI State extension: `SettingsUiState`

| Field | Type | Default | Notes |
|---|---|---|---|
| `isMaximumProtectionEnabled` | Boolean | `false` | Mirrors stored flag; loaded in `init`, refreshed on resume; updated only by `setMaximumProtection()` after PIN success |

Ephemeral (Compose `remember`, not UiState): `pendingMaxProtectionChange: Boolean?` — desired value awaiting PIN; cleared on dialog success/dismiss. Deliberately not persisted or hoisted: a process death mid-dialog must abandon the pending change (safe direction: no change without PIN).
