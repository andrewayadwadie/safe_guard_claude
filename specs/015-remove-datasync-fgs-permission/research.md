# Phase 0 — Dependency Audit: `FOREGROUND_SERVICE_DATA_SYNC` / `dataSync`

**Feature**: `015-remove-datasync-fgs-permission`
**Date**: 2026-10-05
**Mode**: READ-ONLY. No source file was modified. One Gradle task (`:app:processReleaseMainManifest`) was executed; it writes only into `app/build/`.

## Scope searched

| Dimension | Coverage |
| --- | --- |
| Gradle modules | `settings.gradle` includes exactly one module: `:app`. There is no `:eval` module and no `build-logic`/`buildSrc` module. The `eval/` directory exists on disk but is not an included Gradle project, so it contributes nothing to any build. |
| Source sets | `app/src/main`, `app/src/debug`, `app/src/test`, `app/src/androidTest`. Only `app/src/main` has an `AndroidManifest.xml`; no flavor or buildType manifest overlay exists. |
| Build flavors | None declared. Build types only (`debug`, `release`). |
| Other manifests on disk | `third_party/llama.cpp/examples/llama.android/**` (two manifests). Not referenced by `settings.gradle`, therefore never merged. |
| Excluded by design | `.kilo/worktrees/midnight-witch/` — abandoned scratch copy of this project carrying the pre-migration manifest. Declared out of scope by the spec; contributes nothing to any build output. |
| Dependency manifests | Verified through the generated manifest-merger report, which names the origin file of every merged node. |

## Search results

### 1. `FOREGROUND_SERVICE_DATA_SYNC`

Exactly one occurrence in all source:

```
app/src/main/AndroidManifest.xml:21:    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
```

### 2. `dataSync`

No occurrence is a live declaration. All eight hits are prose:

| File | Line | Nature |
| --- | --- | --- |
| `app/src/main/AndroidManifest.xml` | 116–117 | XML comment explaining why the type is `specialUse` and *not* `dataSync` |
| `app/src/main/java/.../service/MonitoringService.kt` | 229 | Code comment, same explanation |
| `app/src/main/java/.../service/MonitoringService.kt` | 340 | KDoc on `onTimeout()` |
| `app/src/main/java/.../service/ContentFilterVpnService.kt` | 1895 | KDoc on `onTimeout()` |
| `.specify/memory/constitution.md` | 41, 142 | Amendment history, already states `specialUse` as current |
| `docs/release-checklist.md` | 535 | Release note recording the completed migration |

**No `android:foregroundServiceType` attribute anywhere contains `dataSync`, alone or combined (`dataSync|specialUse`).**

### 3. `FOREGROUND_SERVICE_TYPE_DATA_SYNC`

Zero occurrences. The constant is not referenced anywhere in the codebase.

### 4. `startForeground(` call sites

| Call site | Service | Type passed |
| --- | --- | --- |
| `ContentFilterVpnService.kt:270` | `ContentFilterVpnService` | `ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE` (API ≥ 34 branch) |
| `ContentFilterVpnService.kt:276` | `ContentFilterVpnService` | none — two-argument call (API < 34 branch) |
| `MonitoringService.kt:232` | `MonitoringService` | `ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE` (API ≥ 34 branch) |
| `MonitoringService.kt:238` | `MonitoringService` | none — two-argument call (API < 34 branch) |

Four call sites total, in two services. **Neither passes a data-transfer type.**

### 5. Worker foreground APIs

| API | Occurrences |
| --- | --- |
| `ForegroundInfo` construction | **zero** |
| `getForegroundInfo` / `getForegroundInfoAsync` override | **zero** |
| `setForeground` / `setForegroundAsync` | **zero** |
| `setExpedited` | **one** — `SyncWorker.kt:441` |

No worker in the project declares foreground information of any kind, so no worker can name a service type.

**`setExpedited` observation (pre-existing, unrelated to this feature).** `SyncWorker.enqueueImmediate()` builds a one-time request with `setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)`. On API < 31 the background-work library implements expedited work by running the worker inside its own foreground service and calling `getForegroundInfoAsync()`. `SyncWorker` does not override it, and the `CoroutineWorker` default implementation throws. The immediate-sync request can therefore fail on API 26–30 today. This is independent of the permission being removed — the library's own service declares no service type at all (see §7) and never requested the data-transfer permission. Recorded here because the audit was asked to note it; **not** in scope for this feature, and fixing it would touch worker logic that the guardrails forbid.

### 6. Every place that starts `MonitoringService`

| Call site | Mechanism |
| --- | --- |
| `receiver/BootReceiver.kt:107` | `startForegroundService` on boot |
| `receiver/BootReceiver.kt:195` | `startForegroundService` (secondary boot path) |
| `receiver/BootServiceStartWorker.kt:38` | `startForegroundService` from the deferred boot worker |
| `receiver/ServiceRestartReceiver.kt:64` | `startForegroundService` on unlock / screen-on |
| `service/MonitoringService.kt:485` | `PendingIntent.getService` armed on `AlarmManager` by `scheduleServiceRestart()` (the `onTaskRemoved` / `onTimeout` path) |
| `service/MonitoringService.kt:1285` | `PendingIntent.getService` for the in-notification sync action |
| `presentation/MainActivity.kt:240` | `startForegroundService` on app open |
| `presentation/dashboard/DashboardScreen.kt:128` | `startForegroundService` from the dashboard toggle |
| `presentation/settings/SettingsViewModel.kt:301, 380` | `startForegroundService` from settings toggles |
| `presentation/setup/PermissionsSetupViewModel.kt:215` | `startForegroundService` after permission setup |
| `data/account/DeviceTeardownOps.kt:37` | `PendingIntent.getService` with `FLAG_NO_CREATE`, used only to **cancel** the restart alarm during account-deletion teardown |

All start paths already target a service declared `specialUse`. None of them names a service type; the type comes from the manifest and from the `startForeground` call inside the service.

**`PendingIntent.getService` observation (pre-existing, frozen by the spec).** `scheduleServiceRestart()` arms `PendingIntent.getService`, not `getForegroundService`. When that alarm fires with the app in the background, `Context.startService` is subject to the API 26+ background-start restriction. This is the existing restart strategy, which spec FR-009 freezes and the guardrails forbid changing. Recorded, not actioned.

### 7. Merged manifest and manifest-merger report

`./gradlew :app:processReleaseMainManifest` was executed successfully.

Merged release manifest — `app/build/intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml`:

```
25:    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
142:   (comment only)
143:   (comment only)
```

Every `foregroundServiceType` attribute in the merged manifest:

```
151:            android:foregroundServiceType="specialUse"     (MonitoringService)
178:            android:foregroundServiceType="specialUse"     (ContentFilterVpnService)
```

Manifest-merger report — `app/build/outputs/logs/manifest-merger-release-report.txt:239`:

```
uses-permission#android.permission.FOREGROUND_SERVICE_DATA_SYNC
ADDED from C:\...\app\src\main\AndroidManifest.xml:21:5-87
	android:name
		ADDED from C:\...\app\src\main\AndroidManifest.xml:21:22-84
```

**One origin, and it is the app's own manifest.** No library contributes the permission.

Checked specifically:

| Library | Verdict |
| --- | --- |
| `androidx.work:work-runtime:2.9.0` | Declares only `android.permission.FOREGROUND_SERVICE`. Its `SystemForegroundService` is merged at line 395 of the merged manifest with **no** `android:foregroundServiceType` attribute. Does not request the data-transfer permission. Confirmed by extracting the AAR's own `AndroidManifest.xml` from the Gradle module cache. |
| Firebase (BOM 32.7.0, FCM) | No data-transfer permission, no `dataSync` service type in the merged output. |
| All other dependencies | No data-transfer permission reaches the merged manifest from any origin other than line 21 of the app manifest, per the merger report. |

## Audit table

| Location | What was found | Impact if the permission is removed | Required action |
| --- | --- | --- | --- |
| `app/src/main/AndroidManifest.xml:21` | `<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />` | **None.** No component requests the data-transfer service category, so no `startForeground` call can be refused for lack of this permission. | **Delete the line.** This is the entire source change of the feature. |
| `app/src/main/AndroidManifest.xml:116` | `MonitoringService` already declares `android:foregroundServiceType="specialUse"` | None | **None.** Already correct. Phase 1 step 2 of the plan request is already satisfied. |
| `app/src/main/AndroidManifest.xml:118–120` | `MonitoringService` already declares `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` with a parental-control purpose string | None | **None.** Already correct. See the deviation note below regarding the requested replacement wording. |
| `app/src/main/AndroidManifest.xml` (`ContentFilterVpnService`) | `specialUse` + subtype property | None | **None.** Frozen by FR-007. |
| `MonitoringService.kt:232` | API ≥ 34 branch already passes `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` | None | **None.** Already correct. |
| `MonitoringService.kt:238` | API < 34 branch uses the two-argument call | None | **None.** Correct for pre-API-34, where types do not exist. |
| `MonitoringService.kt:228–239` | The `startForeground` block has **no** `try`/`catch`, unlike `ContentFilterVpnService.kt:267–282` | None from the permission removal; this is an independent robustness gap | **Add `try`/`catch` mirroring `ContentFilterVpnService` exactly**, per the plan request. Only approved code change. |
| `MonitoringService.kt:340`, `ContentFilterVpnService.kt:1895`, `AndroidManifest.xml:116–117` | Comments/KDoc mentioning `dataSync` explanatorily | None | **None.** Accurate prose explaining the current design. Deleting it would remove the rationale that prevents a future regression. |
| `SyncWorker.kt:441` | `setExpedited(RUN_AS_NON_EXPEDITED_WORK_REQUEST)`, with no `getForegroundInfo` override | **None.** The library's foreground service declares no type and never needed this permission. | **None in this feature.** Logged as a pre-existing API 26–30 concern; touching worker logic is forbidden by the guardrails. |
| `MonitoringService.kt:485` | Restart alarm uses `PendingIntent.getService` | None | **None.** Restart strategy frozen by FR-009. |
| `androidx.work:work-runtime:2.9.0` | Declares base `FOREGROUND_SERVICE` only; `SystemForegroundService` merged with no type | None | **None.** No `tools:node="remove"` needed. |
| Merged release manifest + merger report | Permission has exactly one origin: app manifest line 21 | Removal eliminates it from the merged manifest entirely | **None beyond deleting line 21.** |
| `.kilo/worktrees/midnight-witch/**` | Pre-migration manifest with `dataSync` type and the permission | None — not an included Gradle project | **None.** Out of scope by spec. Must not be edited. |
| `third_party/llama.cpp/examples/llama.android/**` | Two unrelated manifests | None — not included in `settings.gradle` | **None.** |

## STOP GATE evaluation

The gate permits proceeding only if findings are confined to:

- **(a)** the `<uses-permission>` line in `AndroidManifest.xml` — **found, exactly as expected (line 21).**
- **(b)** `MonitoringService`'s `foregroundServiceType="dataSync"` — **NOT found. The attribute is already `specialUse`.**

Findings are a strict **subset** of what the gate allows. Nothing outside (a) and (b) was found: no worker uses a data-transfer type, no library injects the permission, no module or overlay manifest declares it, and the constant is never referenced in Kotlin.

**Gate verdict: PASS.** No approval exception is required.

Two items were found that are *not* data-transfer dependencies and are explicitly recorded as out of scope: the `SyncWorker` expedited-work gap on API 26–30, and the `PendingIntent.getService` restart alarm. Neither is affected by removing the permission, and both sit behind guardrails.

## Decisions

### Decision 1 — Remove the permission line with no replacement and no `tools:node="remove"`

- **Decision**: delete `app/src/main/AndroidManifest.xml:21` outright.
- **Rationale**: the merger report proves a single origin — the app's own manifest. A `tools:node="remove"` directive is only needed to suppress a node a dependency contributes, and no dependency contributes this one. Adding it would be dead configuration that implies a library problem that does not exist.
- **Alternatives considered**: (i) `tools:node="remove"` — rejected, nothing to remove from a library; (ii) keep the permission and answer the store form — rejected, it is the release blocker and the governing principle requires unmapped permissions to be deleted.

### Decision 2 — Treat the service-category migration as already delivered

- **Decision**: perform no manifest type change and no `startForeground` type change. Verify and preserve instead.
- **Rationale**: the migration shipped with feature `013-play-store-release-readiness` (its task `T035` verified the permission was unused and deliberately deferred removal as out of scope). Re-applying a completed change risks churn on a 24/7 enforcement service for zero benefit.
- **Alternatives considered**: re-applying the edits idempotently — rejected; editing a live protection service to produce no net change is unjustifiable risk.

### Decision 3 — Keep the existing special-use subtype wording

- **Decision**: leave the existing `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` value unchanged.
- **Rationale**: the plan request supplies replacement wording (`"Parental control screen time enforcement and child safety monitoring"`). The spec places the purpose-string wording explicitly out of scope and accepts the shipped value as-is. The shipped string is also materially stronger for store review — it names screen-time enforcement, app-usage tracking, policy enforcement, the supervised-device context, and why interruption harms the child. Replacing it with a shorter string would weaken the justification that the store reviewer reads.
- **Alternatives considered**: adopting the supplied wording — rejected as a scope conflict and a downgrade. **Flagged for the developer**; one word of approval reverses this.

### Decision 4 — Add the missing `try`/`catch` around `MonitoringService.startForeground`

- **Decision**: wrap the existing `startForeground` branches in `try`/`catch`, logging with `Timber.e` including the exception and the API level, then `return START_NOT_STICKY`. Mirror `ContentFilterVpnService.kt:267–282` exactly.
- **Rationale**: requested by the plan input, and it closes a genuine asymmetry — the VPN service survives a foreground-start failure with a loud log while the monitoring service would propagate the exception. The reference pattern already exists in the codebase, so no new pattern is introduced.
- **Note on FR-009**: FR-009 freezes lifecycle, restart strategy and enforcement-loop timing. This change alters none of them on the success path; it only changes the failure path from an uncaught exception to a logged, explicit `START_NOT_STICKY`. Recorded as a deliberate, approved deviation rather than a silent one.
- **Alternatives considered**: leaving it as-is — rejected, the developer asked for it and the gap is real; adding a retry or restart on failure — rejected, that would change restart strategy and violate FR-009.

### Decision 5 — Build-time regression guard via a Gradle verification task

- **Decision**: add a Gradle task that reads the processed merged manifest for a release build, fails the build if the data-transfer permission or a `dataSync` service type is present, and is wired so that producing a release artifact cannot skip it.
- **Rationale**: settled in clarification. A dependency upgrade can inject a permission with no source change, so a one-time grep cannot hold. The constitution already establishes the release-blocking build-guard pattern for certificate pinning, so this follows existing precedent rather than inventing one.
- **Scope note**: this touches `app/build.gradle`, which the guardrails list as do-not-modify "dependencies". The guard adds **no dependency** — it adds a verification task. Flagged explicitly so the developer can veto.
- **Alternatives considered**: manual checklist step (rejected — skippable, and the thing it guards against is silent); CI-only (rejected — a non-compliant bundle could still be produced locally); unit/instrumented test (rejected — does not see the merged manifest of a release build).

## Open items for developer decision

| # | Item | Default if no answer |
| --- | --- | --- |
| 1 | Subtype wording: keep the shipped string, or replace it with the shorter string supplied in the plan request? | Keep the shipped string (Decision 3). |
| 2 | The FR-010 build guard requires editing `app/build.gradle` (a verification task, no dependency change). Approved? | Proceed — the clarification session explicitly chose a build-failing guard. |
