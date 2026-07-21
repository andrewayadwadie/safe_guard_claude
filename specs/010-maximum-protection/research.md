# Research: Maximum Protection

**Feature**: 010-maximum-protection | **Date**: 2026-07-19

All Technical Context items were resolvable from the existing codebase — no external research needed. Findings below are grounded in direct code reading.

## R1. Where the violation flow lives (integration points)

**Decision**: Branch at exactly two call sites — `MediaFileObserver.analyzeImage()` (`service/MediaFileObserver.kt:424-480`) and `ImageScanWorker.doWork()` (`worker/ImageScanWorker.kt:125-176`).

**Rationale**: These are the only two places that call `ImageBlurManager.blurImage()`. Both follow the same shape: `contentClassifier.analyzeImage()` → `imageHasher.shouldSendAlert()` → `blurImage()` → `alertRepository.createInappropriateImageAlert()`. Inserting the flag read just before the blur call keeps each file's existing structure and alert-dedup semantics intact.

**Alternatives considered**:
- *Central `ImageViolationHandler` class consolidating both flows* — cleaner DRY, but refactors two battle-tested enforcement paths at once; higher regression risk for a feature that only needs a two-way branch. Rejected for this feature; a candidate for later cleanup.
- *Reading the flag inside `ImageBlurManager.blurImage()`* — hides a behavioral branch inside a utility and gives `ImageBlurManager` a prefs dependency it doesn't need. Rejected: call sites own the decision, manager owns the mechanics.

## R2. Flag storage & key naming

**Decision**: `Constants.KEY_MAXIMUM_PROTECTION_ENABLED = "maximum_protection_enabled"`; typed property `PreferencesManager.isMaximumProtectionEnabled: Boolean` (get default `false`, set) — exactly the pattern of `isContentFilteringEnabled` / `KEY_CONTENT_FILTERING_ENABLED`.

**Rationale**: `PreferencesManager` is the project's single prefs wrapper (EncryptedSharedPreferences, AES256). Existing keys are snake_case constants in `Constants`; typed properties are the established access style. `clearAll()` on logout wipes the key → next login defaults OFF, which matches the spec's logout edge case with zero extra code.

**Alternatives considered**: generic `getBoolean("maximum_protection_enabled")` at call sites — works, but loses the discoverable typed property and invites key-string typos. Rejected.

## R3. Fresh-read guarantee (FR-005 / SC-004)

**Decision**: Read `preferencesManager.isMaximumProtectionEnabled` inside the per-image flagged branch, next to the `shouldSendAlert()` check. In `ImageScanWorker` this is inside the `for` loop, not hoisted.

**Rationale**: SharedPreferences getters read the in-memory map backed by the persisted file — always current within the process, no caching layer added by us. Placing the read per-image means a toggle change mid-batch affects the very next image (SC-004). EncryptedSharedPreferences get cost is negligible on these background threads.

## R4. Copy-only mode mechanics

**Decision**: New `ImageBlurManager.backupOnly(imagePath, category, confidence): BlurResult` that performs `blurImage()` steps 1–3 only (generate backupId → copy original to `image_backup/` → write `.meta` with `blurApplied=false`) and returns `Success`/`AlreadyBlurred`/`Error` from the same sealed type.

**Rationale**: Reuses the backup folder, backupId scheme (SHA-256 of path), metadata sidecar format, and the `AlreadyBlurred`("backup exists") guard — so re-detections of the same image (ImageHasher's 1-hour in-memory dedup window expiring, app restart) short-circuit exactly like today and cannot duplicate copies or alerts. Same return type means call-site `when` branches stay uniform.

**Alternatives considered**: separate result type for copy-only — no consumer benefits from the distinction; call sites treat outcomes identically. Rejected.

## R5. Distinguishing copy-only from blurred backups (needed for retro-blur)

**Decision**: Add `blurApplied: Boolean` to `ImageBlurManager.ImageMetadata` and its key-value sidecar serialization. `loadMetadata()` treats a **missing** `blurApplied` key as `true`.

**Rationale**: Pre-feature `.meta` files were only ever written by the always-blur flow, so defaulting legacy files to `true` is correct-by-construction — no migration pass required. `isImageBlurred()`'s current "backup exists" heuristic stops implying "gallery is blurred" once copy-only exists; the explicit field restores an accurate signal and is what the retroactive pass filters on.

**Alternatives considered**: separate marker file per copy-only backup, or a second backup directory — both fragment the existing single-folder + sidecar scheme that `getPendingReviews()`/review UI already iterate. Rejected.

## R6. Retroactive blur on OFF→ON (FR-013/014)

**Decision**: New `ImageBlurManager.applyBlurToUnblurredBackups(): Int` (returns count blurred). Iterate `getPendingReviews()`; for entries with `blurApplied == false`: if `File(originalPath)` exists → `createBlurredBitmap` → `replaceWithBlurred` → `refreshMediaStore` → rewrite metadata with `blurApplied=true`; if the original is missing/moved → skip silently, leave entry untouched (spec edge case). Never re-copies the backup, never sends alerts.

**Triggers**:
1. Immediately: `SettingsViewModel.setMaximumProtection(true)` launches it in `viewModelScope` on `Dispatchers.IO`.
2. Safety net: `ImageScanWorker.doWork()` calls it at scan start when the flag is ON — covers process death between toggle and completion, guaranteeing the spec's "no later than the next scan cycle" bound.

**Rationale**: Both triggers reuse one idempotent method (filter on `blurApplied=false` makes double-invocation harmless). The worker net makes SC-006 hold even if the immediate pass is interrupted.

**Alternatives considered**: one-shot WorkManager job enqueued on toggle — adds a new Worker class (ProGuard rule, Hilt worker wiring) for work the existing periodic worker already covers. Rejected as over-engineering.

## R7. Toggle UI & PIN gating

**Decision**: `SettingsToggleItem` row in the child-only **Parent Review** section of `SettingsScreen` (icon `Icons.Default.Shield`-family). Tap intercepts: store desired value in `pendingMaxProtectionChange: Boolean?` remember-state → show `ParentPinDialog(hasPin, onVerify, onCreate, onSuccess, onDismiss)` backed by `ParentPinViewModel` (`hiltViewModel()`), the exact wiring `ParentPinGate` uses. `onSuccess` → `settingsViewModel.setMaximumProtection(pending)`; `onDismiss` → clear pending. The Switch's `checked` binds only to `uiState.isMaximumProtectionEnabled`, so a failed/cancelled PIN can never move it (FR-003).

**Rationale**: `ParentPinDialog` already implements verify mode, create mode (first-time PIN), digits-only input, and localized errors — zero new PIN UI. Placing the row in "Parent Review" groups it with the other PIN-protected parental surfaces on the child device. `ParentPinGate` itself is unsuitable (full-screen content gate, not a tap-action gate).

**Alternatives considered**: putting the row in "Device Setup" section — that section is child-operable setup status; a parent-authority control fits the Parent Review grouping better. Rejected.

## R8. Alert behavior per mode (FR-008)

**Decision**: Keep each call site's existing alert logic byte-for-byte; only the `blurImage(...)`/`backupOnly(...)` selection changes.

**Rationale**: Clarification session confirmed alerts fire in both modes. `MediaFileObserver` alerts on `Success`/`Error` (skips `AlreadyBlurred`); `ImageScanWorker` alerts unconditionally after the blur call inside its `shouldSendAlert` guard. Preserving each site's shape keeps dedup semantics identical to production today.

## R9. Review flow compatibility (FR-010)

**Decision**: No changes to `ImageReviewViewModel`/`ImageReviewScreen`. No visual distinction for copy-only entries (deferred clarification → resolved: none needed).

**Rationale**: `getPendingReviews()` lists all `.meta` entries regardless of `blurApplied` (field ignored by UI). `restoreOriginal()` on a copy-only entry copies identical bytes over the untouched original then clears the backup — semantically "approve & dismiss", harmless. `deleteImage()` deletes gallery original + backup — exactly the spec's OFF-mode delete expectation.

## R10. Localization

**Decision**: Two new string pairs — `settings_maximum_protection` (title) and `settings_maximum_protection_desc` (subtitle) — added to `values/strings.xml` and `values-ar/strings.xml`, following the `settings_*` naming convention.

**Rationale**: App ships bilingual (feature 008); every settings row uses `stringResource`. PIN dialog strings already exist and are localized.

## R11. Testing approach

**Decision**: Unit tests where logic is JVM-testable: metadata serialize/parse round-trip incl. `blurApplied` default-true for legacy files. Instrumented/manual validation via quickstart.md for the on-device flows (toggle+PIN, both modes, retro pass) since blur/MediaStore/EncryptedSharedPreferences need a device.

**Rationale**: Constitution's testing priorities target detection logic and service resilience; this feature's risk concentrates in metadata compatibility (regression on legacy entries) and the mode branch — both covered. Full end-to-end needs real media files + MediaStore, matching how existing image-pipeline behavior is validated (manually).
