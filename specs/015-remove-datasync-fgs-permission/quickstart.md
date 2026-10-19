# Quickstart: Verifying the DATA_SYNC Removal

**Feature**: `015-remove-datasync-fgs-permission` · **Branch**: `fix/fgs-remove-data-sync`

Covers Phase 3 (build verification) and Phase 4 (runtime verification). Per FR-012, every
device-facing criterion needs **recorded evidence** — a log line, a `dumpsys` excerpt, the API
level, and the device or emulator identity. A criterion with no evidence counts as unverified.

Package: `com.safeguard.parentalcontrol`

## Prerequisites

| Item | Notes |
| --- | --- |
| `keystore.properties`, `secrets.properties` | Present in the repo root. Required for `bundleRelease` |
| JDK 21, Android SDK Platform 36, build-tools 36.0.0 | Already installed |
| Target A | Android 14+ device or emulator, preferably API 35 or 36 |
| Target B | Device or emulator in the API 26–33 range (SC-006) |
| A paired parent account | Needed for SC-005 (screen-time limit set from the parent side) |
| `bundletool` | **Not on `PATH` in this environment.** Use the fallback in step 3b, or fetch the jar from the bundletool releases page |

## Phase 3 — Build verification

### 3.1 Clean debug build (SC-002)

```bash
./gradlew clean assembleDebug
```

**Expected**: `BUILD SUCCESSFUL`. No new warning mentioning foreground services, service types or
manifest merging. Compare the warning set against a pre-change build if in doubt — the criterion
is "same or fewer", not "zero".

### 3.2 Release bundle (SC-002)

```bash
./gradlew bundleRelease
```

**Expected**: `BUILD SUCCESSFUL`. The new manifest guard runs and stays silent. The existing
`preReleaseBuild` secrets guard also stays silent.

### 3.3a Inspect the shipped bundle — primary path

```bash
bundletool dump manifest --bundle app/build/outputs/bundle/release/app-release.aab > /tmp/aab-manifest.xml

grep -c "FOREGROUND_SERVICE_DATA_SYNC" /tmp/aab-manifest.xml          # expect: 0
grep -o 'foregroundServiceType="[^"]*"' /tmp/aab-manifest.xml         # expect: specialUse only
grep -c "PROPERTY_SPECIAL_USE_FGS_SUBTYPE" /tmp/aab-manifest.xml      # expect: 2
```

**Expected (SC-001)**:

- Zero occurrences of `FOREGROUND_SERVICE_DATA_SYNC`.
- Every `foregroundServiceType` value is exactly `specialUse` — two of them.
- `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_SPECIAL_USE` both still present.
- Both `MonitoringService` and `ContentFilterVpnService` carry a subtype property.

### 3.3b Fallback when `bundletool` is unavailable

Two independent checks that together cover the same ground:

```bash
# (i) the text merged manifest that feeds the bundle
M=app/build/intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml
grep -n "FOREGROUND_SERVICE" "$M"
grep -n 'foregroundServiceType=' "$M"

# (ii) the compiled binary manifest, read back from the release APK
./gradlew assembleRelease
AAPT2="$ANDROID_HOME/build-tools/36.0.0/aapt2"
"$AAPT2" dump xmltree --file AndroidManifest.xml \
    app/build/outputs/apk/release/app-release.apk | grep -i "foreground\|dataSync"
```

**Expected**: in (i), the only `dataSync` hits are inside the explanatory XML comment —
`<uses-permission>` for the data-transfer permission is gone. In (ii), zero `DATA_SYNC`, and
`foregroundServiceType` resolves to the `specialUse` bit only.

Note on (i): check (i) alone is **not** sufficient evidence for SC-001, because it reads an
intermediate rather than the shipped artefact. Pair it with (ii), or use `bundletool`.

### 3.4 Guard negative test (SC-009)

Follow the acceptance table in [contracts/build-guard.md](./contracts/build-guard.md).
Abbreviated:

```bash
# reinsert the permission into app/src/main/AndroidManifest.xml, then:
./gradlew bundleRelease     # expect: FAILURE, GradleException naming the permission
# revert, then:
./gradlew bundleRelease     # expect: SUCCESS
```

Also run acceptance step 6 from that contract: with the real manifest, the explanatory XML comment
containing the word `dataSync` is still present **and** the build passes. That proves the guard
matches structurally rather than by substring.

### 3.5 Repo-wide grep (FR-002, FR-004)

```bash
EX='--exclude-dir=build --exclude-dir=.git --exclude-dir=.kilo --exclude-dir=specs --exclude-dir=.gradle'
grep -rn $EX "FOREGROUND_SERVICE_DATA_SYNC" .                 # expect: 0
grep -rn $EX "FOREGROUND_SERVICE_TYPE_DATA_SYNC" .            # expect: 0
grep -rn $EX 'foregroundServiceType="[^"]*dataSync' .         # expect: 0
```

**Expected**: all three empty. A plain `grep -rn "dataSync"` will still hit the explanatory
comments in `AndroidManifest.xml`, `MonitoringService.kt`, `ContentFilterVpnService.kt`,
`.specify/memory/constitution.md` and `docs/release-checklist.md`. Those are prose and are
**expected to remain** — they are the rationale that prevents a future regression.

### 3.6 Confirm the frozen files really are frozen (FR-006, FR-007, FR-009)

```bash
git diff --stat
```

**Expected**: exactly four paths — `app/src/main/AndroidManifest.xml`,
`app/src/main/java/com/safeguard/parentalcontrol/service/MonitoringService.kt`,
`app/build.gradle`, `docs/release-checklist.md`. Nothing else. In particular `BootReceiver.kt`,
`ServiceRestartReceiver.kt`, `BootServiceStartWorker.kt`, `ContentFilterVpnService.kt`,
`SyncWorker.kt`, `proguard-rules.pro`, `network_security_config.xml` and every DI module must be
absent from the diff.

```bash
git diff app/build.gradle | grep -i "noCompress\|implementation\|libs\."
```

**Expected**: empty. Confirms enforcement rule 12 holds and no dependency changed.

## Phase 4 — Runtime verification

Run the whole sequence on **Target A** (API 35/36) and the applicable subset on **Target B**
(API 26–33). Record each step PASS/FAIL with the actual output.

### 4.1 Install and pair

```bash
./gradlew installDebug
adb shell am start -n com.safeguard.parentalcontrol/.presentation.MainActivity
```

Log in as a child, complete pairing, grant the five protection permissions, and accept the
monitoring disclosure.

**Note**: `MonitoringService` has a consent gate — it calls `startForeground` and then stops
itself if the monitoring disclosure has not been accepted. If the service refuses to stay up,
check the disclosure before suspecting this change.

### 4.2 Confirm the running service type (SC-003)

```bash
adb shell dumpsys activity services com.safeguard.parentalcontrol | grep -A3 -i "MonitoringService\|isForeground\|foregroundServiceType"
```

**Expected on Target A**: `MonitoringService` present with `isForeground=true` and the
special-use type bit (`0x40000000`, reported as `specialUse`). **No** `dataSync` bit
(`0x00000001`).

**Expected on Target B**: service running and foreground, with no type reported — service types do
not exist before API 34. That absence is the correct result, not a failure.

**Evidence to record**: the matching `dumpsys` lines, verbatim.

### 4.3 Clean start, no platform rejection (FR-008)

```bash
adb logcat -c
# toggle protection off and on from the app
adb logcat -d | grep -E "MonitoringService|ForegroundService|ContentFilterVpn|SafeGuard"
```

**Expected**: `MonitoringService started` present. **Absent**:
`ForegroundServiceStartNotAllowedException`, `SecurityException`,
`MissingForegroundServiceTypeException`, `Failed to start foreground service`,
`InvalidForegroundServiceTypeException`.

`Failed to start foreground service` is the message from the new Phase 2 `try`/`catch`. Seeing it
means the foreground start genuinely failed — investigate, do not dismiss.

### 4.4 Reboot recovery (SC-004)

```bash
adb reboot
# wait for boot to complete
adb wait-for-device
adb shell getprop sys.boot_completed      # expect: 1
adb shell dumpsys activity services com.safeguard.parentalcontrol | grep -i "MonitoringService\|ContentFilterVpnService"
```

**Expected**: `MonitoringService` running and foreground. `ContentFilterVpnService` also running
if content filtering was enabled before the reboot. No start-not-allowed and no security
exception in `adb logcat -b all -d`.

Run **three times** — SC-004 requires 3 of 3.

### 4.5 Swipe-away restart (FR-009)

Swipe the app out of recents on the device, then after the restart delay:

```bash
adb shell dumpsys activity services com.safeguard.parentalcontrol | grep -i MonitoringService
adb logcat -d | grep -iE "restart scheduled|onTaskRemoved|TamperAlert"
```

**Expected**: `Monitoring service restart scheduled via AlarmManager` in the log, the service back
up afterwards, and the tamper-alert path behaving exactly as before the change. Timing must be
unchanged — the restart delay is frozen by FR-009.

### 4.6 Screen-time enforcement while backgrounded (SC-005)

1. From the paired parent device, set a short screen-time limit for the child.
2. On the child device, open an unrelated app so the monitored app is in the background.
3. Wait for the limit to be reached.

```bash
adb logcat -d | grep -iE "LockScreen|screen time|limit reached"
```

**Expected**: `LockScreenActivity` appears while the app is backgrounded. Run **three times** —
SC-005 requires 3 of 3.

### 4.7 Persistent notification unchanged (FR-006, SC-005)

Pull down the notification shade.

**Expected**: the monitoring notification is present, with the same text, icon, channel and
importance as before the change, and is still not user-dismissible. Compare against a screenshot
from the pre-change build. Notification copy — including the existing active-protection wording —
must be byte-identical; the spec puts rebranding out of scope.

### 4.8 Continuity past the data-transfer cap (SC-007)

The point of `specialUse` is exemption from the ~6-hour-per-24 cap that applies to data-transfer
services. On Target A (API 35+), leave protection running and confirm after the window that the
service is still alive and still enforcing:

```bash
adb shell dumpsys activity services com.safeguard.parentalcontrol | grep -i "isForeground"
adb logcat -d | grep -i "onTimeout\|foreground-service timeout"
```

**Expected**: still `isForeground=true`; enforcement still fires (repeat 4.6); **no** `onTimeout`
log line. `MonitoringService.onTimeout()` exists as a defensive handler and should never fire for
a special-use service — if it does, that is a real finding.

Per the spec's assumption, this may be satisfied by the platform's own accounting rather than a
wall-clock vigil, **provided the recorded evidence states which method was used**.

### 4.9 Pre-API-34 regression sweep (SC-006)

On Target B (API 26–33), repeat 4.2, 4.3, 4.4, 4.6 and 4.7.

**Expected**: everything works, zero crashes, no permission failure. Service types and the
special-use permission are inert on these versions, so removing the data-transfer permission must
be a complete no-op here. That is the whole point of running this target.

## Evidence matrix (FR-012)

Fill this in and keep it with the feature. Blank evidence = unverified.

**Status as of 2026-10-05**: build-side criteria verified; device-side criteria BLOCKED (see below).

| Criterion | Step | Target | API | Device / emulator | Result | Evidence |
| --- | --- | --- | --- | --- | --- | --- |
| FR-002/FR-003 permission absent on installed package | — | A | 37 | `emulator-5556` (`sdk_gphone16k_x86_64`) | **PASS** | `dumpsys package` grants only `FOREGROUND_SERVICE: granted=true` and `FOREGROUND_SERVICE_SPECIAL_USE: granted=true`. No `FOREGROUND_SERVICE_DATA_SYNC` entry at all |
| App installs and launches, no FGS exception | 4.1 | A | 37 | `emulator-5556` | **PASS** | `Success` on streamed install; `topResumedActivity=.../.presentation.MainActivity`. Zero `ForegroundServiceStartNotAllowedException`, `MissingForegroundServiceTypeException`, `InvalidForegroundServiceTypeException`, `SecurityException` in logcat |
| `MonitoringService` correctly not externally startable | — | A | 37 | `emulator-5556` | **PASS** | `Permission Denial: ... not exported from uid 10230` — `exported=false` holds |
| SC-003 service reports specialUse | 4.2 | A | 37 | `emulator-5556` | **BLOCKED** | Service never enters foreground: `MainActivityKt: Not starting MonitoringService (loggedIn=true, deviceRegistered=false, consent=false)` |
| FR-008 clean start, no rejection | 4.3 | A | 37 | `emulator-5556` | **BLOCKED** | Same gate. Negative evidence only: no FGS exception was raised on any start attempt |
| SC-004 reboot recovery (runs 1–3) | 4.4 | A | | | **BLOCKED** | Requires a child-registered device |
| FR-009 swipe-away restart | 4.5 | A | | | **BLOCKED** | Requires a running service |
| SC-005 lock screen (runs 1–3) | 4.6 | A | | | **BLOCKED** | Requires pairing + a parent-set screen-time limit |
| FR-006 notification unchanged | 4.7 | A | | | **BLOCKED** | No baseline captured (T004 blocked) and no running service to compare against |
| SC-007 continuity past 6h | 4.8 | A | | | **BLOCKED** | Requires a running service |
| SC-006 pre-API-34 no regression | 4.9 | B | | | **BLOCKED** | No API 26–33 emulator image available; both local AVDs are API 37 |

### Why the device criteria are blocked

Two independent blockers, neither created by this change:

1. **No child-registered device.** `MonitoringService` has a consent gate; `MainActivity` declines
   to start it unless `deviceRegistered` and `consent` are both true. Clearing that requires a
   `POST /devices` registration against the live backend on a real parent account, plus accepting
   the in-app monitoring disclosure. That creates a durable child-device record on someone's real
   account, so it is the developer's call, not an automated step.
2. **No API 26–33 image.** Both available AVDs report API 37. SC-006 needs a second image
   installed.

### What the blocked criteria still need

Run `quickstart.md` steps 4.1 → 4.9 on a paired child device, plus step 4.9 on an API 26–33
image, and fill the rows above. The build-side evidence (SC-001, SC-002, SC-009) is complete and
independent of all of this — see the completion gate.

## Build-side evidence (recorded 2026-10-05)

Baseline captured before the source change, then compared after. Temporary baseline files have
been removed; their conclusions are below.

| Check | Baseline (pre-change) | After change | Verdict |
| --- | --- | --- | --- |
| `clean assembleDebug` Kotlin warnings | 75 | 75 | **PASS** — identical, SC-002 |
| `bundleRelease` Kotlin warnings | 75 | 75 | **PASS** — identical, SC-002 |
| Foreground-service-specific warnings | 0 | 0 | **PASS** — none introduced |
| `bundleRelease` result | SUCCESSFUL | SUCCESSFUL (9m 08s) | **PASS** |
| Merged release manifest delta | — | exactly 1 line removed | **PASS** — `20d19 < <uses-permission ... FOREGROUND_SERVICE_DATA_SYNC />` and nothing else |

Shipped AAB compiled manifest (`base/manifest/AndroidManifest.xml` extracted from
`app-release.aab`). Compiled manifests drop XML comments, so a zero here is conclusive rather
than prose-contaminated:

| Token | Occurrences | Expected |
| --- | --- | --- |
| `FOREGROUND_SERVICE_DATA_SYNC` | 0 | 0 ✔ |
| `dataSync` | 0 | 0 ✔ |
| `specialUse` | 2 | ≥1 ✔ |
| `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` | 2 | 2 ✔ |
| FGS permissions present | `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` | exactly these two ✔ |

**SC-001 satisfied on the shipped artifact.**

Guard acceptance (SC-009), all via `:app:process{Debug,Release}MainManifest`, which
`bundleRelease` and `assembleRelease` both depend on:

| Test | Action | Result |
| --- | --- | --- |
| 1 | Reinsert the permission | **BUILD FAILED** — `android.permission.FOREGROUND_SERVICE_DATA_SYNC is present in the merged manifest (AndroidManifest.xml)` ✔ |
| 2 | Revert | **BUILD SUCCESSFUL** ✔ |
| 3 | Set `foregroundServiceType="dataSync\|specialUse"` | **BUILD FAILED** — `<service android:name="com.safeguard.parentalcontrol.service.MonitoringService"> declares foregroundServiceType="dataSync\|specialUse"` ✔ |
| 4 | Revert | **BUILD SUCCESSFUL** ✔ |
| 5 | Real manifest, explanatory comment with `dataSync` intact (2 occurrences survive into the merged manifest) | **BUILD SUCCESSFUL** ✔ — proves structural matching, not substring |

Subtype strings preserved verbatim in the shipped manifest, as decided:

- `MonitoringService` — "Continuous parental-control monitoring: screen-time enforcement, app-usage tracking and policy enforcement on a supervised child device. Must run uninterrupted for the child to remain protected."
- `ContentFilterVpnService` — "VPN content filtering for parental control"

## Completion gate

| Criterion | Blocking | Where verified |
| --- | --- | --- |
| SC-001 merged manifest clean | Yes | 3.3a / 3.3b |
| SC-002 both builds clean | Yes | 3.1, 3.2 |
| SC-009 guard blocks reinsertion | Yes | 3.4 |
| SC-003 → SC-007 | Yes | evidence matrix complete |
| SC-008 store console shows special-use only | **No** | developer confirms after upload |

Commit only after Phase 3 passes. Branch `fix/fgs-remove-data-sync`; commit message in English.
