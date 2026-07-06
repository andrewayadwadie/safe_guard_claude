# Implementation Plan: Child Pairing Gate for Monitoring

**Branch**: `004-child-pairing-gate` | **Date**: 2026-07-06 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/004-child-pairing-gate/spec.md`

**Note**: This template is filled in by the `/speckit-plan` command. See `.specify/templates/plan-template.md` for the execution workflow.

## Summary

A child device must only START monitoring (screen-time enforcement loop + saved-image monitoring) when the child account has at least one linked parent (account-level family link). The single gate point is `MonitoringService.onStartCommand`. The gate reads a locally cached boolean (`hasLinkedParent` in `PreferencesManager`) so the decision is deterministic and offline-capable. The flag is refreshed from `GET /family/parents` on a background coroutine at service start: success + non-empty list → flag true (and start monitoring at runtime); success + empty list → flag false (confirmed unpair); network error → flag unchanged (tamper-resilient — a transient outage never disables an already-paired device).

## Technical Context

**Language/Version**: Kotlin (JDK 21 toolchain, `jvmTarget = '17'`)

**Primary Dependencies**: Hilt 2.51.1, Retrofit 2.9.0 + Gson (`@SerializedName`), OkHttp 4.12.0, Coroutines 1.7.3, Timber 5.0.1

**Storage**: `EncryptedSharedPreferences` via existing `PreferencesManager` (new boolean key `has_linked_parent`)

**Testing**: Manual log-based verification only (per feature directive — no automated tests added). Build gate: `./gradlew assembleDebug` after each phase.

**Target Platform**: Android — `minSdk 26`, `targetSdk 35`

**Project Type**: mobile-app (existing single-module Android app)

**Performance Goals**: Gate decision at service start is a synchronous cached-preference read (no network on hot path). Background refresh is one-shot per service start.

**Constraints**: Offline-deterministic gate; network failure MUST NOT mutate cached state; strict 6-file scope (listed below); no changes to ViewModels, Composables, DI modules, Workers, Receivers, ML pipeline, VPN services, alert logic, or enforcement internals.

**Scale/Scope**: 6 files modified, 0 files created. One new preference key, one new DTO, one new endpoint declaration, two new repository functions, one gate rewrite + one private helper in the service.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

Constitution v2.0.0 — evaluated against all five principles and the twelve SpecKit Enforcement Rules.

| Principle / Rule | Status | Notes |
|---|---|---|
| I. Child Safety & Privacy First | PASS | No new child-data collection. The feature *reduces* data flow: monitoring stays off until a parent link is confirmed. Data-flow note below. |
| II. MVVM + Clean Architecture | PASS | Network call goes `Repository → safeApiCall{} → ApiService`; repository suspend funs on `Dispatchers.IO` via `withContext`. Service delegates refresh logic to `FamilyRepository`. |
| III. Two-Role Architecture | PASS | Gate applies to CHILD role only; parent branch unchanged (enforcement loop already skipped). Enforcement remains offline-capable via the cached flag — backend reachability is never a required control path for an already-paired device. |
| IV. Native Services & Permission Hygiene | PASS | No new permissions, no new Service/Receiver/Worker (no ProGuard change needed — Rule 11 N/A). `MonitoringService` start/restart choreography untouched. |
| V. Defense-in-Depth Security | PASS | Flag stored in `EncryptedSharedPreferences`. Endpoint uses existing user Bearer token via `AuthInterceptor`; no token handling added. |
| Rule 1–2 (presentation sub-package, @HiltViewModel) | N/A | No UI in this feature. |
| Rule 3 (@Singleton repository) | PASS | `FamilyRepository` already `@Singleton`; only gains a constructor param. |
| Rule 4 (ViewModel → Repository → safeApiCall → ApiService) | PASS | Service (platform layer) → Repository → `safeApiCall{}` → `ApiService`. No layer skipped. |
| Rule 5 (tokens only in TokenManager) | PASS | No token access added. |
| Rule 6 (special permissions) | N/A | None touched. |
| Rule 7 (services contain zero business logic) | PASS | Gate in service is a cached-flag read + delegation; classification of network outcomes lives in `FamilyRepository.refreshLinkedParentStatus()`. |
| Rule 8 (workers) | N/A | No workers touched. |
| Rule 9 (NetworkResult-only repository returns) | **DEVIATION — justified** | See Complexity Tracking. `refreshLinkedParentStatus(): Boolean` returns the effective cached state, not a raw network outcome; the network call itself is exposed as `getLinkedParents(): NetworkResult<List<LinkedParent>>`. |
| Rule 10 (Snackbar errors) | N/A | No UI errors. |
| Rule 11 (ProGuard for new components) | N/A | No new Service/Receiver/Worker. |
| Rule 12 (noCompress tflite) | PASS | `build.gradle` untouched. |

**Data-flow note (Principle I):** The feature fetches only pairing metadata (parent id/email/name/link-creation time) belonging to the *parent* account, cached as a single boolean on-device. No monitored child content (text, images, browsing) is collected, transmitted, or newly retained. Retention: the boolean lives in encrypted prefs and is wiped by existing `clearAll()` on logout.

**Gate result (pre-Phase 0): PASS** with one justified deviation (Rule 9, below).
**Gate result (post-Phase 1 re-check): PASS** — design artifacts introduce no additional deviations.

## Project Structure

### Documentation (this feature)

```text
specs/004-child-pairing-gate/
├── plan.md              # This file (/speckit-plan command output)
├── research.md          # Phase 0 output (/speckit-plan command)
├── data-model.md        # Phase 1 output (/speckit-plan command)
├── quickstart.md        # Phase 1 output (/speckit-plan command)
├── contracts/           # Phase 1 output (/speckit-plan command)
│   └── family-parents-api.md
└── tasks.md             # Phase 2 output (/speckit-tasks command - NOT created by /speckit-plan)
```

### Source Code (repository root)

```text
app/src/main/java/com/safeguard/parentalcontrol/
├── util/
│   ├── Constants.kt                 # MODIFY: add KEY_HAS_LINKED_PARENT
│   └── PreferencesManager.kt        # MODIFY: add hasLinkedParent var
├── data/
│   ├── model/
│   │   └── Models.kt                # MODIFY: add LinkedParent DTO (tolerant, nullable)
│   ├── remote/
│   │   └── ApiService.kt            # MODIFY: add GET family/parents in Family Link section
│   └── repository/
│       └── FamilyRepository.kt      # MODIFY: inject PreferencesManager; add getLinkedParents() + refreshLinkedParentStatus()
└── service/
    └── MonitoringService.kt         # MODIFY: pairing gate in onStartCommand + refreshParentLinkAndMaybeStartMonitoring() helper
```

**Structure Decision**: Existing single-module Android app; strictly the six files above. No files created, no other files touched (hard guardrail from the feature directive).

## Implementation Phases

### Phase 1 — Data path (paired-status source of truth)

1. **`util/Constants.kt`** — add alongside the existing `KEY_*` block (lines 9–32):
   ```kotlin
   const val KEY_HAS_LINKED_PARENT = "has_linked_parent"
   ```

2. **`util/PreferencesManager.kt`** — add a var mirroring the existing boolean pattern (e.g., `isDeviceRegistered`):
   ```kotlin
   var hasLinkedParent: Boolean
       get() = encryptedPrefs.getBoolean(Constants.KEY_HAS_LINKED_PARENT, false)
       set(value) = encryptedPrefs.edit().putBoolean(Constants.KEY_HAS_LINKED_PARENT, value).apply()
   ```
   No `clearAll()` change — it already clears all keys on logout.

3. **`data/model/Models.kt`** — add tolerant DTO (all fields nullable; Gson + `java.util.Date` already imported):
   ```kotlin
   // TODO(backend): confirm exact field names for GET /family/parents.
   data class LinkedParent(
       val id: Int?,
       @SerializedName("parent_id") val parentId: Int?,
       @SerializedName("parent_email") val parentEmail: String?,
       @SerializedName("parent_name") val parentName: String?,
       @SerializedName("created_at") val createdAt: Date?
   )
   ```

4. **`data/remote/ApiService.kt`** — in the `Family Link Endpoints` section (after `getLinkedChildren`, line ~275):
   ```kotlin
   /** Get all parents linked to this child (child only). Uses the user Bearer token. */
   @GET("family/parents")
   suspend fun getLinkedParents(): Response<List<LinkedParent>>
   ```

5. **`data/repository/FamilyRepository.kt`** — add `private val preferencesManager: PreferencesManager` to the constructor (Hilt resolves it — `PreferencesManager` is `@Singleton @Inject`); add:
   - `suspend fun getLinkedParents(): NetworkResult<List<LinkedParent>>` — `withContext(Dispatchers.IO) { safeApiCall { apiService.getLinkedParents() } }`
   - `suspend fun refreshLinkedParentStatus(): Boolean` — outcome classification exactly per spec FR-005/FR-006:
     - `Success` + non-empty → `hasLinkedParent = true`, return true
     - `Success` + empty → `hasLinkedParent = false`, return false
     - `Error` (and `Loading` else-branch) → flag untouched, return current cached value

   > `safeApiCall` never returns `Loading`, but `NetworkResult` is a sealed class with three variants, so the `when` needs an `else` branch — it falls through to the cached value.

**BUILD CHECKPOINT**: `./gradlew assembleDebug` must pass before Phase 2.

### Phase 2 — Gate child monitoring on paired status

`service/MonitoringService.kt` only:

1. Inject the repository (field injection, matching the service's existing `@Inject lateinit var` pattern):
   ```kotlin
   @Inject lateinit var familyRepository: FamilyRepository
   ```

2. Replace the child-start block in `onStartCommand` (currently lines 253–263):
   ```kotlin
   if (!isParent) {
       if (preferencesManager.hasLinkedParent) {
           if (!isEnforcementLoopRunning) {
               Timber.d("Child is paired -> starting enforcement + image monitoring")
               startEnforcementLoop()
               startImageMonitoring()
           }
       } else {
           Timber.d("Child NOT yet paired -> monitoring gated; refreshing parent link")
           refreshParentLinkAndMaybeStartMonitoring()
       }
   } else {
       Timber.d("Parent device -> skipping enforcement loop")
   }
   ```

3. Add the private helper using the existing `serviceScope`:
   ```kotlin
   /**
    * One-shot background check: if the backend confirms a linked parent, flip the
    * cached flag and start monitoring. Network failures leave everything unchanged
    * (a never-paired device simply stays gated until it can confirm a parent).
    */
   private fun refreshParentLinkAndMaybeStartMonitoring() {
       serviceScope.launch {
           val paired = familyRepository.refreshLinkedParentStatus()
           if (paired && !isEnforcementLoopRunning) {
               Timber.d("Parent link confirmed at runtime -> starting monitoring now")
               startEnforcementLoop()
               startImageMonitoring()
           }
       }
   }
   ```

   Do NOT modify `startEnforcementLoop()`, `startImageMonitoring()`, `performInitialSync()`, the foreground notification, heartbeat loop, network callback, or WorkManager scheduling. The existing consent gate (`shouldRunMonitoring`, line 224) stays above this gate untouched — pairing gate is additive, evaluated after consent passes.

**BUILD CHECKPOINT**: `./gradlew assembleDebug` must pass.

## Design Notes & Known Behavior

- **Paired devices never re-poll at service start (locked design).** The refresh runs only in the unpaired branch. A confirmed unpair (US3/SC-005) takes effect only when some path sets the flag false — e.g., a start where the flag is already false, or logout `clearAll()`. This is the explicitly locked behavior from the feature directive; near-real-time unpair propagation is flagged as backend coordination item #4 (push signal, later task).
- **`Loading` else-branch**: `safeApiCall` cannot emit `Loading`, but the exhaustive `when` over the sealed class requires handling it; it behaves like Error (flag unchanged).
- **Gate ordering in `onStartCommand`**: consent gate (stops service) → WorkManager scheduling → heartbeat → role check → **pairing gate (new)**. Service stays alive as a foreground data-sync service even when gated — only enforcement + image monitoring are withheld.

## Verification (manual, log-based — no tests added)

| Scenario | Expected logs / behavior |
|---|---|
| Fresh child install, no parent linked, service starts | `"Child NOT yet paired -> monitoring gated; refreshing parent link"`; no `=== ENFORCEMENT LOOP STARTED ===`; no `Started MediaFileObserver` |
| Parent links child, then refresh runs (or service restarts) | `refreshLinkedParentStatus: parents=1, paired=true` → `"Parent link confirmed at runtime -> starting monitoring now"` |
| Paired child, network OFF, service restarts | `"Child is paired -> starting enforcement + image monitoring"` — no network needed |
| Parent unlinks; next refresh returns empty 200 | `refreshLinkedParentStatus: parents=0, paired=false`; monitoring stays stopped on next gated start |

## Backend Coordination (hand-off — not implemented here)

1. Confirm exact JSON schema of `GET /family/parents` (field names for parent id/email/name/created_at). DTO is tolerant and TODO-marked.
2. Confirm auth: call uses the **user Bearer (JWT) token** (default `AuthInterceptor` path), NOT the device token. Verify child-role authorization.
3. Confirm "no parent linked" returns **HTTP 200 + empty list** (not 404) — the gate distinguishes confirmed-no-parent from network error.
4. Confirm unlink propagation requirements; near-real-time requires a push signal (FCM/RTDB) — later task.

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| Rule 9: `FamilyRepository.refreshLinkedParentStatus()` returns `Boolean`, not `NetworkResult<T>` | The function's contract is "effective cached paired state after a refresh attempt" — a state read that must collapse three network outcomes (non-empty/empty/error) into one deterministic answer the service gate can act on. The raw network outcome IS exposed constitution-compliantly via `getLinkedParents(): NetworkResult<List<LinkedParent>>`. | Returning `NetworkResult<Boolean>` would force the outcome-classification (error → keep cached value) into `MonitoringService`, violating Rule 7 (zero business logic in services) — a worse trade. The Boolean here is not a "network outcome" but a preference-backed state value, keeping tamper-resilience logic in the data layer where it belongs. |
