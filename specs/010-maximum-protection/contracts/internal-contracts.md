# Internal Component Contracts: Maximum Protection

**Feature**: 010-maximum-protection | **Date**: 2026-07-19

No external interfaces change (no REST endpoints, no backend contract, no exported Android components). These are the internal Kotlin contracts new/changed code must honor.

## C1. `PreferencesManager` (util/PreferencesManager.kt)

```kotlin
/** Maximum Protection: when true, detected image violations are blurred in the
 *  gallery in addition to being backed up for parent review. Default false =
 *  copy-only mode. Changeable only after parent-PIN verification (enforced at
 *  the UI layer). Cleared on logout by clearAll(). */
var isMaximumProtectionEnabled: Boolean   // get: KEY default false; set: persist
```

**Guarantees**: get reflects the latest persisted value within the process (standard SharedPreferences semantics); no additional caching introduced.

## C2. `Constants` (util/Constants.kt)

```kotlin
const val KEY_MAXIMUM_PROTECTION_ENABLED = "maximum_protection_enabled"
```

Placed with the other preference keys; comment documents PIN-gated write path.

## C3. `ImageBlurManager` (util/ImageBlurManager.kt)

### C3.1 Extended metadata

```kotlin
data class ImageMetadata(
    /* existing 7 fields unchanged */,
    val blurApplied: Boolean = true   // NEW; serialization writes it, parser defaults missing → true
)
```

### C3.2 New: copy-only processing

```kotlin
/** Backup the original and record metadata WITHOUT altering the gallery file.
 *  Used when Maximum Protection is OFF. Same dedup guard as blurImage(). */
fun backupOnly(imagePath: String, category: String, confidence: Float): BlurResult
```

**Postconditions**:
- `Success(backupId, metadata)` ⇒ backup file + `.meta` (`blurApplied=false`) exist; gallery file byte-identical to before the call; MediaStore untouched.
- `AlreadyBlurred(backupId)` ⇒ a backup already existed (from either mode); nothing written.
- `Error(msg)` ⇒ no partial state left behind (backup+meta cleaned up on failure), same as `blurImage()`.

### C3.3 New: retroactive pass

```kotlin
/** Blur every pending backup recorded with blurApplied=false. Idempotent.
 *  Skips (silently, per-entry) originals that are missing or unreadable.
 *  Never creates new backups and never sends alerts.
 *  @return number of images newly blurred. */
fun applyBlurToUnblurredBackups(): Int
```

**Postconditions**: for each processed entry — gallery file replaced with blurred version, MediaStore refreshed, metadata rewritten with `blurApplied=true`, backup file untouched. Safe to call from multiple triggers; concurrent double-invocation must not corrupt metadata (per-entry check-then-act on `blurApplied`).

### C3.4 Changed semantics (documented, not renamed)

`BlurResult.AlreadyBlurred` now means "backup already exists" (either mode). `isImageBlurred(path)` keeps meaning "backup exists" — its one production caller treats it as "already handled", which remains correct; KDoc updated to say so.

`blurImage()` writes `blurApplied=true` in metadata; behavior otherwise unchanged.

## C4. Violation call sites — required shape

Both sites MUST read the flag fresh inside the per-image flagged branch:

```kotlin
// MediaFileObserver.analyzeImage(...) / ImageScanWorker.doWork() per-image loop
if (imageHasher.shouldSendAlert(path)) {
    val maximumProtection = preferencesManager.isMaximumProtectionEnabled  // fresh, per image
    val blurResult = if (maximumProtection) {
        imageBlurManager.blurImage(path, category, confidence)
    } else {
        imageBlurManager.backupOnly(path, category, confidence)
    }
    /* existing when(blurResult) + alert logic — UNCHANGED per site */
}
```

Additionally `ImageScanWorker.doWork()`, before its scan loop:

```kotlin
if (preferencesManager.isMaximumProtectionEnabled) {
    val n = imageBlurManager.applyBlurToUnblurredBackups()   // retro safety net
    if (n > 0) Timber.i("$TAG: Retroactively blurred $n image(s)")
}
```

## C5. `SettingsViewModel` (presentation/settings/SettingsViewModel.kt)

```kotlin
// SettingsUiState: + val isMaximumProtectionEnabled: Boolean = false

/** Persist the PIN-approved value. On false→true, launches the retroactive
 *  blur pass on Dispatchers.IO. Caller MUST invoke only after PIN success. */
fun setMaximumProtection(enabled: Boolean)
```

**Behavior**: writes pref → `_uiState.update { it.copy(isMaximumProtectionEnabled = enabled) }` → if `enabled`, `viewModelScope.launch(Dispatchers.IO) { imageBlurManager.applyBlurToUnblurredBackups() }`. Init + on-resume refresh read the stored value into UiState.

## C6. `SettingsScreen` (presentation/settings/SettingsScreen.kt) — UI contract

- New `SettingsToggleItem` row in the child-only Parent Review section: title `R.string.settings_maximum_protection`, subtitle `R.string.settings_maximum_protection_desc`, `checked = uiState.isMaximumProtectionEnabled`.
- `onCheckedChange = { desired -> pendingMaxProtectionChange = desired }` — never calls the ViewModel directly.
- When `pendingMaxProtectionChange != null`: render `ParentPinDialog(hasPin = pinViewModel.hasParentPin, onVerify = pinViewModel::verifyParentPin, onCreate = pinViewModel::setParentPin, onSuccess = { viewModel.setMaximumProtection(pending); pending = null }, onDismiss = { pending = null })` with `pinViewModel: ParentPinViewModel = hiltViewModel()`.
- Invariant: the Switch's `checked` derives ONLY from UiState ⇒ wrong PIN / cancel cannot move it.

## C7. String resources

| Key | values (en) | values-ar |
|---|---|---|
| `settings_maximum_protection` | "Maximum Protection" | Arabic translation |
| `settings_maximum_protection_desc` | short explanation: blur flagged photos in the gallery | Arabic translation |

Both files MUST be updated in the same change (bilingual invariant from feature 008).
