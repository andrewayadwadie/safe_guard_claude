# Phase 0 Research: Play Store Release Readiness

**Feature**: `013-play-store-release-readiness`
**Date**: 2026-08-24
**Method**: Direct inspection of the working tree, the Gradle dependency cache, and the last-built
APK. Every finding below was verified by running a command, not inferred from documentation.

---

## R-001: Does the current toolchain support API 36?

**Decision**: Yes. No toolchain upgrade is required.

**Evidence**:

| Component | Version present | Supports API 36 |
|---|---|---|
| AGP | 8.13.2 | Yes (AGP ≥ 8.9 required) |
| Gradle wrapper | 8.13 | Yes |
| Kotlin | 2.0.21 + KSP 2.0.21-1.0.28 | Yes |
| SDK Platform | `android-36` **installed** | Yes |
| Build tools | `36.0.0` installed | Yes |

**Rationale**: The originating brief assumed AGP 8.5.2 and asked for an upgrade to "8.9.x or newer".
The repository is already well past that. `compileSdk 36` / `targetSdk 36` is a two-line change.

**Alternatives considered**: Upgrading AGP anyway for latest fixes — rejected. Gratuitous toolchain
churn on a release-critical branch adds risk with no requirement behind it.

---

## R-002: JDK compatibility

**Decision**: Verify before Phase 3a; do not change the JDK.

**Evidence**: `java -version` on PATH reports **Java 22**. `app/build.gradle` sets
`sourceCompatibility`/`targetCompatibility` to `VERSION_17` and `jvmTarget = '17'`. The constitution
claims JDK 21.

**Finding**: Three different JDK expectations are in play (constitution says 21, build targets 17
bytecode, PATH provides 22). Bytecode target and toolchain JDK are different things, so this is not
necessarily broken — AGP 8.13 supports running on JDK 17–23. But the discrepancy is undocumented.

**Action**: Confirm which JDK Gradle actually uses (`./gradlew -version`) as the first step of
Phase 3a. Per FR-022, if API 36 requires a different JDK, stop and report rather than change it.

---

## R-003: 16 KB page-size compliance — **BLOCKER**

**Decision**: The app is **not** 16 KB compliant, and cannot be made compliant without changing
the machine-learning dependency. This blocks Phase 3c and requires a decision before proceeding.

**Evidence** — read directly from the pinned artifacts in the Gradle cache, so this is a property
of the declared dependency versions, not of any stale build output:

```
tensorflow-lite-2.16.1.aar
  jni/arm64-v8a/libtensorflowlite_jni.so      LOAD align 0x1000   (4 KB)  ← not compliant
  jni/armeabi-v7a/libtensorflowlite_jni.so    LOAD align 0x1000   (4 KB)  ← not compliant

tensorflow-lite-gpu-2.16.1.aar
  jni/arm64-v8a/libtensorflowlite_gpu_jni.so  LOAD align 0x1000   (4 KB)  ← not compliant
```

16 KB compliance requires `LOAD` segment alignment of `0x4000`. All shipped TFLite libraries are
`0x1000`.

**This contradicts a comment in the codebase.** `app/build.gradle:178` reads:

```groovy
// Image Analysis - TensorFlow Lite (2.16.1+ supports 16KB page alignment for Android 15+)
```

That comment is false. It should be corrected regardless of which resolution is chosen.

**Upstream state**: TensorFlow Lite 2.17.0 is also 4 KB-aligned. Google's successor package,
**LiteRT `com.google.ai.edge.litert` 1.4.0+**, is the first release built with 16 KB alignment.
Upstream also notes that 16 KB support does not extend to `x86`, `x86_64`, or `armeabi-v7a` — which
is acceptable in practice, since every device that uses 16 KB pages is `arm64-v8a`.

**Why this is a guardrail collision**: migrating to LiteRT changes the import namespace from
`org.tensorflow.lite.*` to `com.google.ai.edge.litert.*`, which requires editing
`ml/TFLiteImageClassifier.kt` and the text-classification code. Those files are **hard guardrails**
under FR-048, and the constitution's locked-version table pins TFLite 2.16.1. Per **FR-053**, work
must stop and ask rather than proceed.

**Options** (decision required — see plan's Blocking Decisions):

| Option | Consequence |
|---|---|
| Migrate to LiteRT 1.4.0+ | Achieves compliance. Breaches the ML guardrail and the locked-version table; needs an amendment and re-verification of classification behavior |
| Ship targeting API 36 with 4 KB libs | The app installs but risks failing to load native code on 16 KB devices. Not viable for a monitoring app whose classifier is core |
| Stay on targetSdk 35 for this release | Sidesteps the blocker entirely. Play's API 36 requirement binds submissions from **2026-08-31** — one week away — so this only works if the upload happens first |

**Alternatives considered**: Rebuilding TFLite from source with `-Wl,-z,max-page-size=16384`.
Rejected — the app has no native build configuration, and vendoring a self-built TFLite is a far
larger maintenance burden than adopting the supported successor package.

---

## R-004: Native libraries actually shipped, and the vendored llama.cpp

**Decision**: The specification's assumption that `third_party/llama.cpp` contributes nothing to the
artifact is **correct for the current source**, but the last-built APK contradicts it because that
APK is stale.

**Evidence**:

```
app/build/outputs/apk/debug/app-debug.apk   built 2026-08-16 16:32   (64 MB)
app/build.gradle                            modified 2026-08-18 18:22   (2 days later)

APK contains:
  lib/arm64-v8a/libsafeguard_llm.so          11.3 MB
  lib/arm64-v8a/libtensorflowlite_jni.so      3.4 MB
  lib/arm64-v8a/libtensorflowlite_gpu_jni.so  1.9 MB
```

`libsafeguard_llm.so` was produced by CMake — build intermediates survive at
`app/build/intermediates/cxx/Debug/…`. But:

- `git log -S "externalNativeBuild" -- app/build.gradle` returns **no commits**. The native build
  was never in any committed version of the build script.
- The current `app/build.gradle` contains no `externalNativeBuild`, no `ndk` block, and no
  `sourceSets` override.
- `app/src/main/cpp` does not exist, and `app/src/main/jniLibs` does not exist.
- `third_party/llama.cpp/app/CMakeLists.txt` builds an executable named `llama-app`, not
  `libsafeguard_llm.so`.

**Conclusion**: the 11.3 MB library is an artifact of an **uncommitted, since-reverted local
build configuration**. A clean build from current source will not produce it. The stale APK
therefore overstates the artifact size by ~11 MB, and the "64 MB APK" figure in the spec is not a
measurement of what the current source produces.

**Action**: FR-045's size breakdown MUST be taken from a fresh clean build, never from the existing
output. Phase 4 begins with `clean`. FR-046's llama.cpp report is answered: **not a build input,
zero bytes in a clean artifact, 206 MB of working-copy disk only.**

---

## R-005: ABI set actually built

**Decision**: `abiFilters` is required. The current configuration builds four ABIs.

**Evidence**: `tensorflow-lite-2.16.1.aar` ships `arm64-v8a`, `armeabi-v7a`, `x86`, and `x86_64`.
`app/build.gradle` declares no `abiFilters` and no `splits` block, so all four are packaged.

The stale APK contains only `arm64-v8a`, which is further confirmation that it was produced by a
local configuration that differs from committed source.

**Size at stake** (native payload, uncompressed, from the AAR):

| ABI | `libtensorflowlite_jni.so` | Needed? |
|---|---|---|
| arm64-v8a | 3.4 MB | Yes |
| armeabi-v7a | 2.2 MB | Yes (minSdk 26 permits 32-bit devices) |
| x86 | 4.5 MB | **No** — emulator only |
| x86_64 | 4.5 MB | **No** — emulator only |

Restricting to the two ARM ABIs removes ~9 MB of native payload before bundle splitting, and ABI
splitting then delivers only one ABI per device.

**Alternatives considered**: keeping x86/x86_64 for emulator-based testing. Rejected for the release
bundle — debug builds are unaffected by a release-scoped `abiFilters`, so local emulator work
continues to function.

---

## R-006: Release-build runtime risk (R8)

**Decision**: Treat the first minified build as unverified until smoke-tested on a device.

**Evidence**: `buildTypes.release` sets `minifyEnabled true` and `shrinkResources true`. No release
build has ever been produced — there is no `app/build/outputs/bundle/` or release APK on disk. The
app depends heavily on reflective and manifest-mediated construction: Hilt DI graphs, Room entities,
WorkManager workers, four manifest-declared services, and TFLite interpreter reflection.

R8 removes unreachable code without emitting a build error, so this failure class appears only at
runtime, on a build nobody has run. This is exactly the risk FR-062..FR-064 were added to cover.

**Action**: Audit `app/proguard-rules.pro` for keep rules covering every Service, Receiver, and
Worker in the manifest (Constitution Rule 11) as a Phase 4 prerequisite, then run the FR-063 smoke
test on the installed release build.

---

## R-007: bundletool availability

**Decision**: Not installed. Required for FR-044.

**Evidence**: `bundletool` is not on PATH and no `bundletool*.jar` is present in the home directory.

**Action**: Phase 4 must either install bundletool (to compute the per-device download estimate from
`build-apks --connected-device` / `get-size total`) or record the estimate as unavailable and
explain why. Do not substitute the AAB's on-disk size — it is not the number Play shows testers, and
reporting it as such would be misleading.

---

## R-008: Certificate pinning — current behavior

**Decision**: Pinning is presently inert; the clarified posture (disabled, with a host-match guard)
is safe to implement.

**Evidence**:

```kotlin
// Constants.kt
const val API_HOST = "api.safeguard.app"                              // never contacted
const val CERTIFICATE_PIN_PRIMARY = "AAAAAAAA…="                      // placeholder
const val CERTIFICATE_PIN_BACKUP  = "BBBBBBBB…="                      // placeholder

// NetworkModule.kt:93-105 — pins registered against API_HOST
// NetworkModule.kt:137-139 — pinner applied only when !BuildConfig.DEBUG
```

All traffic goes to `bw.noor.net`. OkHttp's `CertificatePinner` enforces pins only for hostnames
that match a configured pattern, so no pin is ever consulted. Pinning is a no-op today — it neither
protects nor breaks anything.

**Consequence**: correcting `API_HOST` to `bw.noor.net` *without* also supplying real pins would
convert a silent no-op into a total outage. This is precisely why FR-010 requires a build-time
host-match guard rather than a source-level fix.

---

## R-009: Network security configuration resolution

**Decision**: A debug source set must be created; the main configuration becomes release-only.

**Evidence**: `app/src/` contains only `main/`, `test/`, and `androidTest/`. There is no `debug/`
source set, so `app/src/main/res/xml/network_security_config.xml` is the single resource for every
variant. It currently permits cleartext to five development hosts and trusts user CAs via
`<debug-overrides>`.

**Note on `<debug-overrides>`**: this block is only honoured when `android:debuggable="true"`, so
user-CA trust does not in fact reach a release build today. The cleartext `<domain-config>` block,
however, has no such gate and **does** apply to release. The exposure is real but narrower than the
originating brief implied — worth stating accurately in the phase report.

**Resolution**: resource merging gives variant-specific `res/` directories precedence over `main/`,
so placing a permissive copy at `app/src/debug/res/xml/network_security_config.xml` overrides the
strict `main/` copy for debug builds only, with no manifest change required.

---

## R-010: Restricted permissions requiring Play declarations

**Decision**: Five permissions need declaration text drafted (FR-058..FR-061).

**Evidence** — from `app/src/main/AndroidManifest.xml`:

| Permission | Declaration required | Enforcement feature it serves |
|---|---|---|
| `MANAGE_EXTERNAL_STORAGE` | All-files-access declaration; heavily scrutinised | Replacing detected images with blurred versions |
| `QUERY_ALL_PACKAGES` | Package-visibility declaration | Per-app screen-time limits and app blocking |
| `BIND_ACCESSIBILITY_SERVICE` | Accessibility API policy justification; top rejection cause | Text monitoring |
| `BIND_VPN_SERVICE` | VpnService declaration | DNS-sinkhole content filtering |
| `PACKAGE_USAGE_STATS` | Sensitive-permission justification | Screen-time measurement |

Every one maps to a named enforcement feature, which satisfies Constitution Principle IV and gives
each declaration a truthful justification to write.

**Exact-alarm permissions**: `SCHEDULE_EXACT_ALARM` and `USE_EXACT_ALARM` are both declared, and
`grep` finds **no** exact-alarm scheduling anywhere — `MonitoringService.kt:461` uses an explicitly
inexact, Doze-friendly restart alarm. Both are unmapped and are removed under FR-035.

---

## R-011: Foreground service timeout handling

**Decision**: `specialUse` for `MonitoringService`; `onTimeout` overrides on both services.

**Evidence**:

- `MonitoringService` — manifest `android:foregroundServiceType="dataSync"`;
  `MonitoringService.kt:228` calls `startForeground(id, notification)` with no type argument.
- `ContentFilterVpnService` — already `specialUse`, already carries the required
  `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" …>`. This is the pattern to
  mirror.
- `FOREGROUND_SERVICE_SPECIAL_USE` is already declared; no new permission is needed.
- `FOREGROUND_SERVICE_DATA_SYNC` becomes unused once `MonitoringService` changes — reported under
  FR-027, not removed.

`Service.onTimeout(int, int)` was added in API 35. Overriding it requires `@RequiresApi(35)` or an
SDK-version guard, and the two-argument form supersedes the single-argument API 34 variant.

---

## R-012: Predictive back

**Decision**: Migrate `LockScreenActivity` off the deprecated override.

**Evidence**: `LockScreenActivity.kt:244` overrides `onBackPressed()`. From API 36 predictive back
is enabled by default, and the deprecated override is not invoked in the predictive-back path — so
the lock screen could become dismissible, defeating enforcement.

**Resolution**: register an `OnBackPressedCallback` with `enabled = true` that consumes the event.
`MainActivity.kt:65` already calls `enableEdgeToEdge()`, so FR-030's remaining work is auditing
Scaffold inset handling per screen, not enabling the mode.

---

## Summary of corrections to the specification

Two spec assumptions were falsified by this research and must be amended:

1. **"TFLite 2.16.1 … is expected to be compliant"** — it is **not**. 4 KB-aligned. This is a
   blocker, not a verification step.
2. **"Debug APK 64 MB"** and the associated size analysis — measured from a stale artifact
   containing an 11.3 MB library the current source does not build. All size figures must be
   re-measured from a clean build.

One spec assumption was **confirmed**: `third_party/llama.cpp` is not a build input and contributes
zero bytes to a clean artifact.

**Sources**:

- [LiteRT issue #43 — 16 KB page size support](https://github.com/google-ai-edge/LiteRT/issues/43)
- [tensorflow #96602 — lib new version not support 16kb pages](https://github.com/tensorflow/tensorflow/issues/96602)
- [Google AI Developers Forum — TensorFlow Lite and 16 KB Page Size Support](https://discuss.ai.google.dev/t/tensorflow-lite-and-16-kb-page-size-support/93052)
- [dotnet/android-libraries #1271 — Xamarin.TensorFlow.Lite 2.16.1.7 and the 16 KB policy](https://github.com/dotnet/android-libraries/issues/1271)
