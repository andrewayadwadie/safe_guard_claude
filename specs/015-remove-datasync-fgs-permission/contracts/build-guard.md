# Contract: Release-Blocking Foreground-Service Manifest Guard

**Feature**: `015-remove-datasync-fgs-permission` · **Satisfies**: FR-010, SC-009
**File**: `app/build.gradle`
**Precedent**: the `preReleaseBuild` secrets guard in the same file (feature 013,
`specs/013-play-store-release-readiness/contracts/build-guards.md`)

## Why this guard exists

Deleting the `FOREGROUND_SERVICE_DATA_SYNC` permission from the app manifest fixes the problem
once. It does not keep it fixed. A dependency upgrade can contribute a `<uses-permission>` node or
a `foregroundServiceType` attribute into the **merged** manifest with no change to any file a
reviewer reads. The Phase 0 audit confirmed no current dependency does this, which is exactly the
state that makes a future regression easy to miss.

The permission's practical cost is not cosmetic: a data-transfer foreground service is capped at
roughly six cumulative hours per 24 on API 35+, and cannot be started from a boot receiver. For a
24/7 child-protection monitor both failures are silent.

## Contract

### Trigger

The guard runs as part of processing the merged manifest, for both build types:

```
tasks.matching { it.name ==~ /process(Debug|Release)MainManifest/ }.configureEach {
    doLast { ... }
}
```

| Property | Value |
| --- | --- |
| Runs on | `assembleDebug`, `assembleRelease`, `bundleDebug`, `bundleRelease`, and anything depending on them |
| Skippable while producing a release artifact | **No.** `bundleRelease` and `assembleRelease` both depend on `processReleaseMainManifest` |
| Phase | `doLast` — the merged manifest must already exist on disk |

**Why not `preReleaseBuild`**, where the certificate-pin guard lives: at that point the merged
manifest has not been produced, so there is nothing to inspect. The manifest-processing task is
the earliest point where the real artefact exists.

**Why `debug` as well**: the developer hits the failure during ordinary development rather than at
release time.

### Input

The merged manifest written by the triggering task. Resolve it from the task's own output
directory rather than hard-coding a path, because the directory name is AGP-version-dependent.
Observed under AGP 8.13.2:

```
app/build/intermediates/merged_manifest/<variant>/process<Variant>MainManifest/AndroidManifest.xml
```

If the file cannot be found, the guard **must fail**, not pass. A guard that silently skips when
its input is missing is worse than no guard, because it reports safety it never checked.

### Violations detected

| # | Violation | Match target |
| --- | --- | --- |
| V-1 | Data-transfer foreground-service permission present | A `<uses-permission>` node whose `android:name` is exactly `android.permission.FOREGROUND_SERVICE_DATA_SYNC` |
| V-2 | A service declares the data-transfer category | An `android:foregroundServiceType` **attribute value** containing the token `dataSync`, including combined forms such as `dataSync\|specialUse` |

### Matching rule — the one thing that must not be got wrong

**A bare substring search for `dataSync` or `DATA_SYNC` over the manifest text will fail the build
on the manifest's own documentation.** The app manifest carries an explanatory XML comment at
lines 115–118 that contains both the words `specialUse` and `dataSync`, and that comment survives
into the merged manifest:

```xml
<!-- Monitoring Service
     foregroundServiceType is specialUse, not dataSync: from Android 15 (API 35) a
     dataSync foreground service is capped at 6 cumulative hours per 24-hour window, ... -->
```

That comment is deliberate — it is the human-readable guardrail against the same regression — and
it must not be deleted to make a lazy check pass.

Therefore the guard **must** match structurally:

- For **V-1**: the value of an `android:name` attribute on a `uses-permission` element, compared
  for equality against the full permission name.
- For **V-2**: the value of an `android:foregroundServiceType` attribute, split on `|` and
  compared token-wise.

Acceptable implementations: parse the XML (`groovy.xml.XmlSlurper`), or match attribute-assignment
patterns (`android:name="…"` / `android:foregroundServiceType="…"`) after stripping XML comments.
Not acceptable: `text.contains('dataSync')`.

### Output

**On pass**: silence. No log line on the success path — a guard that chatters on every build gets
tuned out.

**On failure**: throw `GradleException`, in the house style of the existing `preReleaseBuild`
guard — a blank line, a one-line headline, bullet-per-violation, and a pointer to this contract:

```
Release build blocked - foreground service declaration regression:
  * android.permission.FOREGROUND_SERVICE_DATA_SYNC is present in the merged manifest
    (origin: see app/build/outputs/logs/manifest-merger-<variant>-report.txt)
  * <service android:name=".service.Example"> declares foregroundServiceType="dataSync"

A dataSync foreground service is capped at ~6 cumulative hours per 24 on API 35+ and
cannot be started from BOOT_COMPLETED. For a 24/7 child-protection monitor both failures
are silent.

See specs/015-remove-datasync-fgs-permission/contracts/build-guard.md
```

Naming the merger report in the message matters: when a *library* reintroduces the permission, the
report is the only place that names which one.

## Constraints

| Constraint | Requirement |
| --- | --- |
| Dependencies | Add none. Remove none. Upgrade none. This is a verification task only — the basis on which the developer approved editing a guardrailed file |
| Enforcement rule 12 | `aaptOptions { noCompress "tflite" }` must remain untouched and byte-identical |
| Existing guard | The `preReleaseBuild` secrets guard must remain untouched and must keep working |
| New abstractions | None. No plugin, no `buildSrc`, no convention plugin. An inline task block in `app/build.gradle`, matching the existing guard's shape |
| Configuration cache | Read the manifest inside `doLast`, not at configuration time |

## Acceptance (SC-009)

| Step | Action | Expected |
| --- | --- | --- |
| 1 | `./gradlew bundleRelease` on the finished change | **Passes.** Guard silent |
| 2 | Reinsert `<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />` into `app/src/main/AndroidManifest.xml`, then `./gradlew bundleRelease` | **Fails** with `GradleException`, message names the permission |
| 3 | Revert the reinsertion, `./gradlew bundleRelease` | **Passes** again |
| 4 | Temporarily set a service's `foregroundServiceType` to `dataSync\|specialUse`, then `./gradlew assembleDebug` | **Fails**, message names the service and the combined value |
| 5 | Revert step 4, `./gradlew assembleDebug` | **Passes** |
| 6 | With the real manifest, confirm the explanatory XML comment containing the word `dataSync` is still present | Guard **passes** — proof it matches structurally, not by substring |

Step 6 is the regression test for the guard itself. Run it, and do not delete the comment to make
step 6 easier.
