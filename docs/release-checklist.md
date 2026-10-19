# Play Store Release Checklist

**App**: SafeGuard Parental Control (`com.safeguard.parentalcontrol`)
**Target track**: Internal Testing (first upload)
**Feature**: [`specs/013-play-store-release-readiness`](../specs/013-play-store-release-readiness/spec.md)
**Last updated**: 2026-08-24

This is the operator's single reference. Everything here is a manual step that cannot be automated.

---

## 0. ⚠️ Build environment prerequisite — Windows long paths

**Release builds cannot complete on this machine until this is fixed.**

Windows long-path support is **disabled** (`HKLM\SYSTEM\CurrentControlSet\Control\FileSystem\LongPathsEnabled = 0`),
so the 260-character `MAX_PATH` limit is active. Jetpack Compose generates deeply-nested lambda
class names; measured paths under
`app/build/intermediates/classes/debug/transformDebugClassesWithAsm/` reach **301 characters** —
41 over the limit. Example:

```
...LimitsScreenKt$AddStudyTimeAppDialog$3$invoke$lambda$11$lambda$10$lambda$9$$inlined$itemsIndexed$default$2.class
```

**Symptom**: any *incremental* build fails with

```
Execution failed for task ':app:transformDebugClassesWithAsm'.
> java.io.IOException: Unable to delete directory '...\transformDebugClassesWithAsm\dirs'
```

Clean builds succeed because nothing needs deleting; the failure appears only once outputs exist
and Gradle must replace them. Shortening the repository root does not help — the root is already
only 46 characters.

**Fix (requires administrator and a reboot):**

```powershell
Set-ItemProperty -Path 'HKLM:\SYSTEM\CurrentControlSet\Control\FileSystem' `
  -Name LongPathsEnabled -Value 1 -Type DWord
```

**Workaround if you cannot change the registry** — relocate the build directory to a short path:

```
# gradle.properties
org.gradle.project.buildDir=C:\\b\\safeguard
```

**Recovery when a build has already wedged** (ordinary `Remove-Item` fails on these paths):

```powershell
.\gradlew.bat --stop
$e = "$env:TEMP\empty"; New-Item -ItemType Directory $e -Force | Out-Null
robocopy $e "app\build" /MIR /NFL /NDL /NJH /NJS /NC /NS | Out-Null
Remove-Item -Recurse -Force "app\build", ".gradle\configuration-cache", ".gradle\kotlin", ".kotlin" -EA SilentlyContinue
```

This blocks Phase 4 (`clean bundleRelease`) and affects every Windows developer on the project.

---

## 0b. Measured results — verified 2026-08-24

Everything below was measured from an actual build, not estimated. All builds used the §0
short-build-dir workaround (`-Dorg.gradle.project.buildDir=C:\b\sg`).

| Check | Result |
|---|---|
| `assembleDebug` at API 36 | ✅ BUILD SUCCESSFUL |
| Unit tests (`testDebugUnitTest`) | ✅ **94 tests, 0 failures, 0 errors** |
| `bundleRelease` (minified, R8) | ✅ BUILD SUCCESSFUL in 31m |
| AAB signature (`jarsigner -verify`) | ✅ **jar verified** |
| ABIs in bundle | ✅ `arm64-v8a`, `armeabi-v7a` only — x86/x86_64 **excluded** |
| Release network config | ✅ cleartext denied, system CAs only, no pins, no debug-overrides |
| Build guards G-1/G-2/G-3 | ✅ all three verified firing with correct key names |
| Debug build with no `secrets.properties` | ✅ succeeds on example fallback |

### Sizes

| Artifact | Size |
|---|---|
| Debug APK (all 4 ABIs, pre-`abiFilters`) | 72 MB |
| **Release AAB** | **55 MB** |
| **Download size, arm64-v8a device** | **≈ 36.8 MB** ← the number Play shows testers |
| Download size, armeabi-v7a device | ≈ 36.5 MB |
| `mapping.txt` | 204 MB (retain per §8) |

Dominated by `nsfw_classifier.tflite` (24.4 MB of 28 MB assets). Native payload per ABI after
filtering: arm64 5.2 MB, armv7 3.4 MB. Removing x86/x86_64 saved ~13 MB from the bundle.

### 16 KB page alignment — measured per library

| Library | Alignment | Compliant |
|---|---|---|
| `libandroidx.graphics.path.so` (all ABIs) | `0x4000` | ✅ |
| `libtensorflowlite_jni.so` (all ABIs) | `0x1000` | ❌ |
| `libtensorflowlite_gpu_jni.so` (all ABIs) | `0x1000` | ❌ |

TensorFlow Lite is the **sole** non-compliant dependency — the newly-added Compose native library
is already aligned. See §4b; deadline is 2027-02-01, so this does not block today's upload.

### ⚠️ Upload key: regenerated, then REVERTED — Play had already registered the leaked key

**Read this before touching signing.** A clean key was generated on 2026-08-24 and a bundle signed
with it was rejected by Play:

```
Your Android App Bundle is signed with the wrong key.
expected: SHA1: 59:5A:D2:DD:AD:EE:63:22:AE:A9:A3:06:74:ED:77:4C:63:97:34:F4
uploaded: SHA1: 90:9F:DF:43:27:C6:FE:0B:02:D5:5C:20:8D:2D:EB:17:10:06:43:B9
```

`59:5A:D2…` is **`safeguard-release.jks` — the compromised key**. It was registered with Play
*before* this remediation, which is precisely the failure §3 warned about: an upload key created
with a leaked password must be replaced **before** registration, because replacing it afterwards
requires a Google request.

**Current decision: ship on the compromised key.** `keystore.properties` points back at
`safeguard-release.jks`. The leaked upload key remains live and is accepted by Play.

**Pending remediation — not yet filed.** The clean replacement key already exists and its public
certificate is exported and ready to submit:

| Field | Value |
|---|---|
| Reset request | Play Console → Test and release → Setup → App signing → **Request upload key reset** |
| Certificate to submit | `upload_certificate.pem` (repository root; a public cert — safe to send) |
| Replacement keystore | `haris_release.jks` (repository root, gitignored via `.gitignore:48`) |
| Alias | `upload` |
| Algorithm | RSA 2048, SHA384withRSA, PKCS12 |
| Validity | 10000 days — **2026-08-24 → 2054-01-09** |
| Subject | `CN=Safeguard Parental Control, OU=Safeguard, O=Safeguard, L=Giza, ST=Giza, C=EG` |
| **New SHA-1** | `90:9F:DF:43:27:C6:FE:0B:02:D5:5C:20:8D:2D:EB:17:10:06:43:B9` |
| **New SHA-256** | `C3:D1:D0:83:2C:0A:D9:D5:16:4E:E0:FD:76:A5:1C:CC:35:12:92:5E:33:C2:6B:73:82:75:27:CA:4E:DE:37:D9` |

After Google approves the reset (typically 1–2 business days), point `keystore.properties` back at
`haris_release.jks` / alias `upload` and rebuild. Until then every upload uses the leaked key.

**Verify Play App Signing is actually enrolled.** If Play Console → App Integrity shows `59:5A:D2…`
as the *app signing* key rather than the *upload* key, no reset is possible and that key is
permanent for the life of the app — a materially worse position that changes the remediation plan.

<details>
<summary>Superseded: clean-key build, verified 2026-08-24 (rejected by Play)</summary>

Rebuild verified: `clean bundleRelease` BUILD SUCCESSFUL in 26m 28s, AAB 57,294,359 bytes (55 MB),
`jarsigner -verify` → **jar verified**, signer was the new certificate, old `CN=haris` certificate
appeared **zero** times. ABIs `arm64-v8a` + `armeabi-v7a` only; `BundleConfig.pb` carried the
`**.[tT][fF][lL][iI][tT][eE]` uncompressed glob (T003 invariant). The build was correct in every
respect except the key Play expects.

</details>

The self-signed / no-timestamp / PKIX warnings from `jarsigner` are expected for an upload key and
are not defects — Play App Signing re-signs the bundle with Google's own key.

> **Neither SHA-1 above is the app signing key.** Do not register either one in Firebase. Take the
> **app signing** SHA-1 from Play Console → Setup → App Integrity and register that one per §6, or
> Google Sign-In fails with `DEVELOPER_ERROR` on every Play install while working on local builds.

### Not executed — requires a physical device

- §5 manual Android 16 verification
- §10 minified-release smoke test

Both are unavoidably manual. The release build compiles, minifies, and signs, but **nothing has
confirmed it runs**. Treat §10 as mandatory before promoting the build.

---

## 1. Values you must supply locally

### `secrets.properties` (repository root, gitignored)

Copy `secrets.properties.example` and fill in:

| Key | Value to use |
|---|---|
| `API_BASE_URL_DEBUG` | `http://10.0.2.2:8000/api/v1/` — or your LAN backend |
| `API_BASE_URL_RELEASE` | `https://bw.noor.net:8090/api/v1/` |
| `API_HOST` | `bw.noor.net` |
| `GOOGLE_WEB_CLIENT_ID` | The existing web client id for Firebase project `haris-db7b0` |
| `CERT_PIN_PRIMARY` | **Leave empty** — pinning is disabled for this release |
| `CERT_PIN_BACKUP` | **Leave empty** |

If this file is absent, debug builds fall back to `secrets.properties.example`. Release builds fail
with a message naming the missing key — that is intentional.

### `keystore.properties` (repository root, gitignored)

Copy `keystore.properties.example` and fill in all four values from the keystore you generate in
§2 below.

---

## 2. Generate the upload key

> ⚠️ **Do not reuse any existing keystore.** See §3.

```bash
keytool -genkeypair -v \
  -keystore keystore/upload-keystore.jks \
  -alias upload \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -storetype PKCS12
```

`-validity 10000` (~27 years) is Google's recommended minimum. A key that expires before the app's
lifetime cannot be used to publish updates.

**This is an upload key, not the app signing key.** Play App Signing re-signs your bundle with a
different key that Google holds. Losing the upload key is recoverable via Play support; it is not
the key users' devices verify against.

---

## 3. ⚠️ Compromised credential — read before generating

The tracked file `keystore.properties.template` contained a live password (`Haris@1234`), committed
in `f7b8974`. It has been scrubbed from the working tree, **but it remains in git history.**

Consequences:

- Any keystore created with that password must be treated as **compromised**.
- Generate a **new** keystore (§2) and use it as your upload key.
- Do this **before** registering an upload key with Play. Replacing an upload key after registration
  requires a Google support request; replacing it beforehand costs nothing.
- Scrubbing the working tree does not remove the password from history. Regeneration is the remedy.

---

## 4. Play App Signing SHA-1 — skip this and Google Sign-In fails for every tester

Play re-signs your bundle with an app signing key that is **not** your upload key. The certificate
fingerprint reaching a user's device therefore differs from your local one.

Google Sign-In validates against the fingerprint registered for the OAuth client. Unless you do
this, sign-in fails with `DEVELOPER_ERROR` for every Play install — while working perfectly on every
build you install locally, which makes it easy to miss.

- [ ] Upload the bundle to Internal Testing at least once
- [ ] Play Console → **Setup → App Integrity** → copy the **app signing certificate SHA-1**
- [ ] Add that SHA-1 to the Firebase project (`haris-db7b0`) → Project Settings → Your apps
- [ ] Add that SHA-1 to the Google Cloud Console OAuth 2.0 client for Android
- [ ] Re-download `google-services.json` if Firebase prompts, and replace `app/google-services.json`
- [ ] Verify sign-in on a device that has never had a debug build installed

---

## 4b. Post-launch tasks

### ⚠️ Migrate TensorFlow Lite → LiteRT 1.4.0+ before Feb 1, 2027

**Why**: TensorFlow Lite 2.16.1 ships native libraries aligned to 4 KB (`LOAD 0x1000`), not the
16 KB (`0x4000`) that Android 16 requires. Verified directly from the pinned AAR — see
`specs/013-play-store-release-readiness/research.md` R-003. TFLite 2.17.0 is also 4 KB. Google's
successor package **LiteRT (`com.google.ai.edge.litert`) 1.4.0+** is the first 16 KB-aligned
release.

**Deliberate decision (T028, Option B)**: ship `targetSdk 36` with the current 4 KB libraries.
Target-market devices (Redmi, Galaxy A, Infinix, Tecno) use 4 KB pages. Migration is deferred to a
dedicated task with full classifier re-verification, because changing the import namespace
(`org.tensorflow.lite.*` → `com.google.ai.edge.litert.*`) edits `ml/TFLiteImageClassifier.kt`, a
guarded file.

**Known gap while deferred**: on a device that does use 16 KB pages, the TFLite native library
fails to load and image classification silently degrades to a heuristic. `TFLiteImageClassifier`
catches `UnsatisfiedLinkError` explicitly, logs the cause, exposes `hasImageClassifier = false`,
and never crashes. Text monitoring, VPN filtering, screen-time enforcement, and alerts are
unaffected.

**Deadline — confirmed from the official source**
([developer.android.com/guide/practices/page-sizes](https://developer.android.com/guide/practices/page-sizes),
"Google Play compatibility requirement", page updated 2026-08-23):

> "To ensure your app works correctly on the latest versions of Android, all apps targeting
> Android 15 (API level 35) and higher must support 16 KB memory page sizes on 64-bit devices on
> Google Play. **Starting February 1, 2027**, if your app updates don't support 16 KB memory page
> sizes, you won't be able to release these updates."

The November 1, 2025 date circulating in blog posts is the **superseded original**; the official
page states no earlier date. Uploading today is therefore permitted — the deadline is ~17 months
out.

**Still verify** Play Console → your app → Bundle details before the first upload. It is
authoritative for this specific app and will show the "Memory page size: Does not support 16 KB"
warning.

### 16 KB backcompat mode — two consequences worth knowing

The same page documents backcompat: rather than failing, the package manager runs a 4 KB-aligned
app in **16 KB backcompat mode** on a 16 KB-kernel device, when the app's `.so` files have 4 KB
LOAD alignment and are 4 KB ZIP-aligned. That is this app's exact situation.

**(a) The warning dialog is visible to the child.** Per the page: *"If the package manager has
enabled 16 KB backcompat mode for an app, the app displays a warning when it's first launched
saying that it's running in 16 KB backcompat mode."* On a supervised child device, a system dialog
announcing that the monitoring app is running in a compatibility mode is an obvious tamper prompt.
The `android:pageSizeCompat` manifest property suppresses it — *"When this property is set, the app
won't display backcompat mode warnings when it launches."*
**Logged as a candidate, not a task. Not implemented.**

**(b) Backcompat is a grace period, not a fix.** Per the page, Android 17 can force it off entirely
and abort incompatible binaries immediately:

```
adb shell setprop bionic.linker.16kb.app_compat.enabled fatal
adb shell setprop pm.16kb.app_compat.disabled true
```

The LiteRT migration above still has to land before February 1, 2027 regardless.

### Remove the vendored llama.cpp source tree

`third_party/llama.cpp` is gitignored, referenced by no app source, wired into no
`externalNativeBuild`, and confirmed absent from a clean build artifact. It contributes **zero
bytes** to the APK and costs 206 MB of working-copy disk. Scheduled for removal as a separate task
after Phase 5 — not removed by this feature.

---

## 5. Manual verification on an Android 16 device

None of these can be automated from a build machine. Run them on a physical Android 16 device
before promoting the build.

- [ ] App launches without crashing
- [ ] No control or text clipped behind the status bar or navigation bar, on **every** screen
      (edge-to-edge is enforced from API 35; all 21 Scaffold screens were verified in source to
      pass window insets through, but only a device confirms it)
- [ ] Lock screen covers the full screen
- [ ] Lock screen resists **10 consecutive** back presses AND predictive-back swipe gestures
      without dismissing
- [ ] Accessibility service enables in Settings and fires events
- [ ] VPN service starts and blocks a known test domain
- [ ] Monitoring service still alive after **6+ hours** (start it and leave the device overnight —
      this is the specific failure the `specialUse` change exists to prevent)
- [ ] Image scan still classifies correctly (ML pipeline was not modified)
- [ ] Screen-time limits still trigger the lock screen

---

## 6. Restricted permission declarations

Each of these requires a declaration form or written justification in Play Console. The
accessibility and all-files-access policies are the most common rejection causes for parental
control apps. Text below is drafted for you to paste and adapt.

### Foreground service types — Special use ONLY

The app declares exactly **two** foreground services, both `specialUse`:

| Service | Type | Subtype property |
| --- | --- | --- |
| `MonitoringService` | `specialUse` | Continuous parental-control monitoring: screen-time enforcement, app-usage tracking and policy enforcement on a supervised child device. Must run uninterrupted for the child to remain protected. |
| `ContentFilterVpnService` | `specialUse` | VPN content filtering for parental control |

`FOREGROUND_SERVICE_DATA_SYNC` is **NOT** declared and must never be reintroduced. The Play
Console foreground-service declaration form should therefore present only the Special use
section. If it asks about data sync, a dependency has reintroduced the permission into the
merged manifest — read `app/build/outputs/logs/manifest-merger-release-report.txt` to find
which one.

A release-blocking build guard enforces this. It reads the merged manifest during
`process{Debug,Release}MainManifest` and fails the build if the permission or a `dataSync`
service type is present. Contract and acceptance tests:
`specs/015-remove-datasync-fgs-permission/contracts/build-guard.md`.

### `MANAGE_EXTERNAL_STORAGE` — All files access — **REMOVED, no declaration is made**

**Do not fill in the All files access form. The app does not declare this permission.** If the
Play Console presents that form, a dependency has reintroduced the permission into the merged
manifest — read `app/build/outputs/logs/manifest-merger-release-report.txt` to find which one, and
note that the build should already have failed before reaching the console.

The justification previously drafted here was withdrawn with feature
`016-remove-all-files-access`. It did not survive its own reasoning: Google Play restricts all-files
access to file managers, backup and restore tools, anti-virus apps, document managers, on-device
file search, disk encryption and device migration. **Parental control is not on that permitted-use
list**, and the form's mandatory question — why the Storage Access Framework cannot be used — had
no good answer, because it can be.

What the app does instead:

| Capability | Mechanism |
| --- | --- |
| Read saved photos for on-device classification | `READ_MEDIA_IMAGES`, requested at runtime (API 33+); `READ_EXTERNAL_STORAGE` below that |
| Replace a flagged image with a blurred version, restore it, or delete it | Folder access the parent grants once per picture folder through the system document picker, persisted across reboots |

Two consequences worth knowing before a review conversation:

- **Images in `Download` are detected and reported but never blurred in place.** From Android 11 the
  platform refuses to grant that folder to any app through the picker. Detection, the private backup
  and the parent alert all still work there; only the in-place blur does not, and the parent is told
  so once in-product.
- **In-place blurring now also works on Android 8–10**, where it had silently failed for the app's
  whole life: all-files access only exists from Android 11, and no legacy write permission was ever
  declared.

A release-blocking build guard enforces the removal. The same guard that bans
`FOREGROUND_SERVICE_DATA_SYNC` was extended with this permission rather than duplicated. Contract and
acceptance tests: `specs/016-remove-all-files-access/contracts/build-guard.md`.

### `QUERY_ALL_PACKAGES` — removed, no declaration needed

**Nothing to declare. The permission is gone.** Play's declaration prompt for it means the build
still carries it; rebuild from a commit that contains this change.

The justification previously drafted here was withdrawn. Its own key claim was wrong: it said a
filtered `<queries>` list was insufficient "because the parent may restrict any app on the device,
which is not knowable in advance". A `<queries>` entry does not have to name packages — it can match
an **intent**, and `ACTION_MAIN` + `CATEGORY_LAUNCHER` matches every launchable app on the device
without naming any of them. That is the whole set the parent can act on, so the permission bought the
app nothing except a sensitive-data declaration and a review risk: Play restricts
`QUERY_ALL_PACKAGES` to apps whose core purpose is broad app visibility (device search, anti-virus,
file managers), and parental control is not on that list.

What the app does instead — `app/src/main/AndroidManifest.xml`:

```xml
<queries>
    <intent>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent>
    <intent>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.HOME" />
    </intent>
</queries>
```

| Capability | Still works because |
| --- | --- |
| Parent's app list for per-app limits and blocking | `getInstalledApplications()` returns every launcher-visible app; the list already filtered on `getLaunchIntentForPackage() != null`, so the visible set is unchanged |
| App name and icon for a chosen app | `getApplicationInfo` / `getApplicationLabel` / `getApplicationIcon` — launchable apps are visible |
| Foreground-app enforcement | Package name comes from `UsageStatsManager` under `PACKAGE_USAGE_STATS`, which needs no package visibility |
| Skipping OEM launchers in text monitoring | `queryIntentActivities(ACTION_MAIN + CATEGORY_HOME)` is covered by the second `<intent>` |

The one difference: packages with **no launcher activity** are not visible, so `getAppName()` returns
null for them and the UI falls back to the package name — as it already did for any lookup failure.
Such packages were never in the parent's app list.

A release-blocking build guard enforces the removal: `QUERY_ALL_PACKAGES` was added to the same
banned-permission list in `app/build.gradle` that bans `MANAGE_EXTERNAL_STORAGE` and
`FOREGROUND_SERVICE_DATA_SYNC`, so a dependency cannot reintroduce it through the merged manifest.

### `BIND_ACCESSIBILITY_SERVICE` — Accessibility API

> SafeGuard uses the Accessibility API for its core, user-facing child-safety function: detecting
> harmful content (bullying, sexual solicitation, self-harm) in text displayed on a child's device
> and alerting the parent. Text is classified **on the device** by a bundled model; raw text is
> never transmitted or stored off-device — only a category label and severity score. The service is
> enabled explicitly by the parent through the system Accessibility settings screen after an
> in-app disclosure. Messaging apps the parent excludes, plus banking and password-manager apps,
> are excluded from monitoring. There is no alternative API that exposes on-screen text.

### `BIND_VPN_SERVICE` — VpnService

> SafeGuard runs a **local, on-device DNS sinkhole** to block access to pornography, gambling and
> other categories the parent has restricted. No traffic is routed to any external server; the VPN
> interface exists solely so DNS queries can be inspected and blocked locally. No browsing data
> leaves the device.

### `PACKAGE_USAGE_STATS` — Usage access

> Required to measure per-application screen time so the daily and per-app limits configured by
> the parent can be enforced, and to identify the foreground app when a limit is reached. Usage
> data is aggregated on the device; only totals needed for the parent's dashboard are synced.

### Also declare

- [ ] Prominent Disclosure & Consent: the app shows an in-app monitoring disclosure and requires
      parental acknowledgement before any service starts. `MonitoringService` and
      `ContentFilterVpnService` both gate on `preferencesManager.shouldRunMonitoring`.
- [ ] Target audience & content: this app is **for parents**, not directed at children, even though
      it is installed on a child's device. Answering this wrong pulls the app into Families policy.

---

## 7. Privacy policy URL and Data Safety questionnaire

### ⚠️ A public privacy policy URL is mandatory

The app ships the policy as a bundled asset (`app/src/main/assets/websites/privacy-policy/`)
rendered in-app by `LegalDocScreen`. **That does not satisfy Play.** Play requires a URL that is
publicly reachable without installing the app.

- [ ] Publish the policy to a public URL (tooling exists at `tools/legal-sites/publish.ps1`)
- [ ] Confirm the URL loads in a browser with no login
- [ ] Enter it in Play Console → App content → Privacy policy
- [ ] Confirm the policy actually describes: accessibility text monitoring, image scanning, usage
      statistics, VPN-based filtering, and location of processing (on-device)

### Data Safety questionnaire — drafted answers

Derived from the project's data-flow rules. **Raw monitored text and raw image bytes never leave
the device** — only derived signals do. Answer accordingly:

| Data type | Collected? | Shared? | Purpose | Notes |
|---|---|---|---|---|
| Name / email | Yes | No | Account management | Parent account only |
| Approximate/precise location | No | No | — | Not collected |
| Photos / videos | **No** | No | — | Images are classified **on-device**; bytes are never uploaded |
| Message/screen text | **No** | No | — | Classified **on-device**; only a category label + score is sent |
| App activity (app usage) | Yes | No | App functionality | Aggregated screen-time totals for the parent dashboard |
| Browsing history | **No** | No | — | DNS filtering is local; queries are not uploaded |
| Device identifiers | Yes | No | App functionality | Device token for pairing, FCM token for push |
| Crash logs / diagnostics | Yes | No | Diagnostics | Play Console vitals only; no crash SDK is bundled |

- [ ] Data is encrypted in transit — **Yes** (TLS; cleartext denied in release)
- [ ] Users can request deletion — answer per your backend's actual capability; do not overstate
- [ ] Committed to Play Families Policy — only if you answer "directed at children", which per §6
      you should not

---

## 8. Mapping file upload

Release builds run R8 with `minifyEnabled true`, so every stack trace a tester generates arrives
obfuscated. The deobfuscation mapping is produced at:

```
app/build/outputs/mapping/release/mapping.txt
```

- [ ] Upload `mapping.txt` alongside each bundle (Play Console → Release → App bundle explorer →
      Downloads → Upload deobfuscation file, or let the Play Console upload flow attach it
      automatically)
- [ ] Retain a copy of the exact `mapping.txt` for every uploaded `versionCode` — it cannot be
      regenerated after the fact, and a mapping from a different build will not resolve

**Without it, every crash report from a tester is unreadable.** No crash-reporting SDK was added by
this feature and no new data is collected; crash visibility comes from Play Console vitals made
legible by this file.

### R8 risk note

This is the first release build the project has ever produced. R8 removes reflectively-reached code
without emitting a build error, and this app relies heavily on reflection and manifest-mediated
construction (Hilt graphs, Room entities, WorkManager workers, four declared services, TFLite).

`proguard-rules.pro` was audited and **does** cover every Service, Receiver and Worker via wildcard
keeps. Two directives are still worth watching if the smoke test in §10 misbehaves:

- `-repackageclasses ''` combined with `-allowaccessmodification` — aggressive repackaging
- `-assumenosideeffects class java.lang.Object { java.lang.String toString(); }` — applies to
  **all** `toString()` calls globally, not just logging

---

## 10. Release-build smoke test — do not skip

Install the **minified release** build on a device and confirm each path. A verified signature says
nothing about whether the app starts.

- [ ] Login completes
- [ ] Monitoring service starts
- [ ] VPN service starts
- [ ] Accessibility service receives an event
- [ ] One image classification succeeds
- [ ] No `ClassNotFoundException`, `NoSuchMethodError`, or `UnsatisfiedLinkError` in logcat

If a path fails, trace it to a missing keep rule in `proguard-rules.pro`. Do **not** resolve it by
setting `minifyEnabled false` — that ships an unshrunk, unobfuscated build and hides the real gap.

---

## 9. What changed and why, per phase

### Phase 0 — Secrets remediation

`keystore.properties.template` was tracked in git containing a live password. Scrubbed to empty
placeholders; `keystore.properties.example` added. The password remains in history at `f7b8974`,
so the key must be regenerated rather than reused — see §3. `.gitignore` gained
`secrets.properties` and `/keystore/`.

### Phase 1 — Configuration externalization

Build configuration moved out of the tracked build script into an untracked `secrets.properties`,
falling back to `secrets.properties.example` so a fresh clone still builds. `API_BASE_URL` is now
resolved per build type instead of being declared three times with two of them commented out.

Three release-only guards were added, hooked to `preReleaseBuild` so they fail before compilation
and never touch debug builds:

- **G-1** release URL must be HTTPS
- **G-2** no `REPLACE_ME` placeholders survive into a release
- **G-3** the certificate-pin subject host must match the host actually called

G-3 exists because of a real defect found during the audit: pins were registered against
`api.safeguard.app` while all traffic goes to `bw.noor.net`. OkHttp only enforces pins on matching
hostnames, so pinning was **silently doing nothing** — which reads as protection in code review.
Pins now come from `BuildConfig`; blank values disable pinning outright rather than producing an
unsatisfiable `sha256/` pin. The dead constants were removed from `Constants.kt`.

### Phase 2 — Network security split

`res/xml/network_security_config.xml` permitted cleartext to five development hosts and applied to
**every** build type, release included. It is now release-strict — cleartext denied everywhere,
system trust anchors only, no pins. Development cleartext and user-CA trust moved to a new
`app/src/debug/` source set, which resource merging applies to debug builds only.

Accuracy note: `<debug-overrides>` is only honoured when `android:debuggable="true"`, so user-CA
trust never actually reached release. The cleartext `<domain-config>` block had no such gate and
did.

### Phase 3 — Android 16 (API 36)

`compileSdk`/`targetSdk` 34 → 36. No toolchain upgrade was needed — AGP 8.13.2, Gradle 8.13,
Kotlin 2.0.21 and SDK Platform 36 were already present.

- **`MonitoringService` `dataSync` → `specialUse`.** From API 35 a `dataSync` foreground service is
  capped at 6 cumulative hours per 24, after which the platform stops it. For a 24/7 monitor that
  ends protection silently, with no signal to the parent. Both foreground services now override
  `onTimeout()` to log and request a restart.
  **Follow-up, feature 015:** the now-unused `FOREGROUND_SERVICE_DATA_SYNC` `<uses-permission>`
  was left declared here (task `T035`, FR-027 recorded removal as out of scope at the time). That
  orphan was the sole reason Play Console kept asking for a data-sync justification. It has now
  been removed and the deferral is **closed**; a build guard prevents its return. See §6.
- **Both exact-alarm permissions removed.** `SCHEDULE_EXACT_ALARM` and `USE_EXACT_ALARM` were
  declared but the app schedules no exact alarms at all — only an inexact Doze-friendly restart.
  `USE_EXACT_ALARM` is additionally restricted by Play to alarm-clock and calendar apps.
- **Predictive back.** `LockScreenActivity` relied on the deprecated `onBackPressed()` override,
  which is **not** invoked on the predictive-back path that API 36 enables by default — the lock
  screen would have become dismissible. Replaced with an always-enabled `OnBackPressedCallback`.
- **Compose BOM 2023.10.01 → 2024.09.00**, `activity-compose` 1.9.2, `lifecycle` 2.8.6. `material3`
  and `material-icons-extended` had hardcoded versions overriding the BOM; those now defer to it.
- **16 KB page size: NOT compliant.** See §4b. Verified, reported, deliberately not fixed here.

### Phase 4 — Signing, bundle, size

Release signing guarded so a missing or blank credential fails only release tasks, never debug.
`versionCode` 6 → **7**, `versionName` → **1.2.0** (the original brief said `versionCode 3`, which
is a downgrade Play would reject). Bundle splitting: language **off** so Arabic ships in the base
APK, density and ABI on. `abiFilters` restricted to `arm64-v8a` and `armeabi-v7a` — the build was
packaging `x86` and `x86_64`, about 13 MB no target device uses.

### Constitution

Amended to **v2.1.0**: SDK 36, AGP/Kotlin versions corrected to match reality, `MonitoringService`
foreground type, Compose BOM, and the pinning stance. The ML guardrail was deliberately **not**
amended — see §4b.

---

## 11. Proposed commit boundaries

Nothing was committed (FR-055). Suggested split — each commit is independently revertable and
leaves the build green:

**1. `fix(security): scrub committed keystore password`**
```
keystore.properties.template   (scrubbed to empty placeholders)
keystore.properties.example    (new)
.gitignore                     (+ secrets.properties, /keystore/)
```
Land this first and independently. The password is in history at `f7b8974`; regenerate the key.

**2. `feat(build): externalize configuration and add release guards`**
```
secrets.properties.example     (new)
app/build.gradle               (secrets loading, per-buildType API_BASE_URL, G-1/G-2/G-3)
.../di/NetworkModule.kt        (pins from BuildConfig, blank = no pinning)
.../util/Constants.kt          (remove dead pin constants)
```

**3. `fix(security): confine cleartext and user CAs to debug builds`**
```
app/src/main/res/xml/network_security_config.xml   (release-strict rewrite)
app/src/debug/res/xml/network_security_config.xml  (new)
```

**4. `feat(android): migrate to API 36`**
```
app/build.gradle                    (compileSdk/targetSdk 36, versionCode 7, versionName 1.2.0)
gradle/libs.versions.toml           (Compose BOM 2024.09.00, activity 1.9.2, lifecycle 2.8.6)
app/src/main/AndroidManifest.xml    (MonitoringService specialUse; exact-alarm perms removed)
.../service/MonitoringService.kt    (typed startForeground, onTimeout)
.../service/ContentFilterVpnService.kt (onTimeout only)
.../presentation/lockscreen/LockScreenActivity.kt (OnBackPressedCallback)
```

**5. `fix(ml): handle UnsatisfiedLinkError from native TFLite`**
```
.../ml/TFLiteImageClassifier.kt
.../ml/TFLiteTextClassifier.kt
```
42 insertions, 0 deletions. Purely additive. Worth its own commit: it fixes a latent bug —
`UnsatisfiedLinkError` is a `LinkageError`, caught by neither `Exception` nor `OutOfMemoryError`,
so it previously propagated uncaught out of `Interpreter()`.

**6. `feat(build): release signing, AAB config and ABI filters`**
```
app/build.gradle    (signing guard, bundle {} block, ndk abiFilters)
```

**7. `docs: Play Store release readiness spec, plan and checklist`**
```
specs/013-play-store-release-readiness/
docs/release-checklist.md
.specify/memory/constitution.md   (v2.1.0 amendment)
.specify/feature.json
CLAUDE.md
```

### Do not commit

- `secrets.properties` — gitignored, correct
- `safeguard-release.jks` — gitignored; compromised, replace it
- `third_party/` — gitignored, 206 MB, not a build input
- `nightrun.ps1` — untracked; not part of this feature, review separately

---

## 12. Account deletion (feature 014): limitations, follow-ups and backend questions

**Feature**: [`specs/014-account-deletion`](../specs/014-account-deletion/spec.md). This is the in-app deletion flow Google Play requires. It ships in the same release as §7 (Data Safety).

**Play Console**: the account-deletion URL to register is `{root}/account-deletion`, where `{root}` is the API base URL without `/api/v1` (for release: `https://bw.noor.net:8090/account-deletion`). The app opens the same page from Settings → About → "How account deletion works".

### Known limitations

1. **A child device is not told when its account is deleted remotely.** The server returns the same 401 for a deleted account as for any lost session, so the app cannot tell them apart and keeps today's behaviour: tokens cleared, Login shown, protection left running. Blocked on backend question Q6. (Spec FR-025.)
2. `SyncWorker` is enqueued on every cold start with no login gate. It can raise a "Session expired" toast on the Login screen after a deletion or a logout. Outside this feature's worker guardrail.
3. `TamperDetectionReceiver` and `ServiceRestartReceiver` read the encrypted preferences file as plain preferences, so both are effectively inert. Affects features 004 and 010.
4. The logout flow still leaves `MonitoringService` running. Migrating logout to `AccountTeardownManager` is a follow-up.
5. The session toasts in `MainActivity` are hard-coded English.
6. The `Retry-After` value is not available to the client (`NetworkResult.Error` carries no headers), so the rate-limit message says "wait a while" without a number. The seconds string exists for when it is exposed.
7. The Google credential state (`clearCredentialState`) is not cleared after a deletion.

### Questions for the backend developer

| # | Question |
|---|---|
| Q1 | What does a 200 from `DELETE /auth/account` and `DELETE /family/child/{id}` contain: `MessageResponse`, an empty body, or 204? (The client accepts any 2xx.) |
| Q2 | How can the client tell that an account has no password? Does `GET /auth/me` always return `auth_provider`, and what does it report for a local account that later linked Google? Could `has_password` be added? |
| Q3 | Is `DELETE /auth/account` rate-limited? What window, and is there a `Retry-After` header? Does `DELETE /family/child` share the login limiter? |
| Q4 | After a child (or a parent with children) is deleted, is any FCM message sent to the child's device? |
| Q5 | Is the error body for 400/403 always `{"detail": "<string>"}`, and is it localized? (The client maps by status code only.) |
| Q6 | Can the server return something that clearly means "this account was deleted", different from an expired or revoked session (for example `error_code: "account_deleted"`, or a 410 on `/auth/refresh` and `/auth/me`)? Unblocks limitation 1. |
| Q7 | Could `DELETE /family/child/{id}` accept `{"password"}` / `{"confirmation"}` like `/auth/account`? It would replace the separate re-authentication login step with one atomic call. |

### Verification still to run on a device

Unit tests and the build are green. The on-device scenarios V1 to V14 in [`quickstart.md`](../specs/014-account-deletion/quickstart.md) need throwaway accounts (Postman folder 0) and have **not** been run yet. Never run them against a real family: deletion is immediate and permanent.
