# Release Notes — Haris 1.2.1 (versionCode 10)

**Artifact**: `app/build/outputs/bundle/release/app-release.aab` — 57,384,664 bytes (54.7 MB)
**Built**: 2026-10-06 · `BUILD SUCCESSFUL in 20m 21s`
**Source**: branch `fix/fgs-remove-data-sync` @ `1964b68`
**Package**: `com.safeguard.parentalcontrol`
**SDK**: `minSdk 26` · `targetSdk 36` · `compileSdk 36`
**Mapping**: `app/build/outputs/mapping/release/mapping.txt` (205 MB) — upload with the bundle
**Signing**: release keystore, 3 `META-INF` signature entries present

> **This is the first Google Play release.** `versionName 1.2.0` / `versionCode 7` existed in the
> repository but was never published — it was an internal build produced while preparing for
> submission. There are no release tags, so no prior published version exists to diff against.
> The Play Console "What's new" text below is therefore written as a launch description rather
> than a changelog.

---

## What ships in this build

### Core product

| Capability | Notes |
| --- | --- |
| Parent / child role split | One app, two roles. Parent configures policy and reviews alerts; child device performs enforcement locally against cached policy and keeps working while offline. |
| Screen-time enforcement | Continuous limit tracking with lock screen when a limit is reached, enforced on the child device. |
| App restrictions | Per-app blocking and allow-listing. |
| Content filtering | Local DNS sinkhole via `ContentFilterVpnService`. |
| Text monitoring | Accessibility-based, two-stage on-device pipeline: regex pre-filter then TensorFlow Lite classification. |
| Saved-image scanning | Downloads and Screenshots only. No live screen capture. |
| Violation alerts | End-to-end delivery with FCM push, local persistence, alert list and detail view. Alerts authenticated with the device token. |
| Account deletion | Self-deletion and parent-initiated child deletion with ordered device teardown. Satisfies the Google Play data-deletion requirement. |
| Localization | English and Arabic, including RTL layout. |
| Auth | Email/password plus Google Sign-In, with forgot-password reset. Backend owns accounts and tokens; Firebase is not the auth source of truth. |

### Privacy posture

- Sensitive content — screen text and image pixels — is classified **on the child's device**.
  Only derived signals leave it: classification scores, category labels and alert metadata.
  Raw monitored text and raw image bytes are never transmitted or stored server-side.
- Live screen capture and live-frame scanning are **not** implemented and are out of scope by
  design. Image detection operates on saved media only.
- Monitoring will not start until the parent has accepted the in-app monitoring disclosure.
  `MonitoringService` and `ContentFilterVpnService` both gate on that consent at every start
  path, including boot and restart.

---

## Platform and compliance work in this build

### Foreground services — special use only

The app declares exactly two foreground services, both `specialUse`:

| Service | Type | Purpose declared to Play |
| --- | --- | --- |
| `MonitoringService` | `specialUse` | Continuous parental-control monitoring: screen-time enforcement, app-usage tracking and policy enforcement on a supervised child device. Must run uninterrupted for the child to remain protected. |
| `ContentFilterVpnService` | `specialUse` | VPN content filtering for parental control |

`FOREGROUND_SERVICE_DATA_SYNC` is **not** declared. It was previously present as an orphaned
`<uses-permission>` that mapped to no component, and it was the sole reason Play Console kept
requesting a data-sync justification. Removing it matters beyond the form:

- From API 35 a `dataSync` foreground service is capped at roughly 6 cumulative hours per
  24-hour window, after which the platform stops it. For a 24/7 child-protection monitor that
  ends protection silently, with no signal to the parent.
- From API 35 a `dataSync` foreground service cannot be started from a `BOOT_COMPLETED`
  receiver, which would have broken protection after every reboot.

Both services override `onTimeout()` defensively. `specialUse` is exempt from the cap, so it
should never fire — but a silent stop is exactly the failure mode worth logging loudly.

A release-blocking build guard now enforces this. It reads the merged manifest during
`process{Debug,Release}MainManifest` and fails the build if the permission or a `dataSync`
service type reappears — including combined forms such as `dataSync|specialUse`. The guard
exists because a dependency upgrade can inject either one into the merged manifest with no
change to any file a reviewer reads. Contract and acceptance tests:
`specs/015-remove-datasync-fgs-permission/contracts/build-guard.md`.

### Accessibility service declaration

`TextMonitoringAccessibilityService` now declares `android:isAccessibilityTool="false"` explicitly
in `res/xml/accessibility_service_config.xml`.

The first Play review attempt returned two items against `BIND_ACCESSIBILITY_SERVICE`. They are
not the same kind of problem:

| Review item | Nature | Resolution |
| --- | --- | --- |
| "`BIND_ACCESSIBILITY_SERVICE` ... has not been declared" | **The blocker.** The Permissions Declaration Form was incomplete | Console-side. No code change can satisfy it |
| "`isAccessibilityTool` has not been set, which means you have declared that your app is not an accessibility tool" | Informational. It states which policy branch applies | The stated classification is correct. Now declared explicitly rather than left implicit |

**The value is `false` and must stay `false`.** Haris is not an accessibility tool: it does not
assist users with disabilities, it monitors a supervised child device. Declaring `true` would be a
false declaration to Google and an enforcement risk — app removal or account-level action — which
is a worse outcome than the review block it would appear to clear. The attribute is a sworn
classification, not a warning toggle.

Because the app is not an accessibility tool, Google Play applies the
prominent-disclosure-and-consent branch of the accessibility policy. That requirement is already
met in full:

- `ConsentScreen` is shown before any monitoring starts, and itemizes each data category
  separately — typed and on-screen text, app and screen-time usage, images, web activity.
- It states that analysis happens on the device, that only flagged events and alerts reach the
  parent account, and that the child is shown a notice while monitoring is active.
- It links the Privacy Policy.
- Consent is an explicit, unchecked checkbox carrying a parent/guardian attestation, followed by
  an affirmative button. Nothing is pre-consented.
- `shouldRunMonitoring` gates **every** service start path, including boot and restart, so no
  component can begin monitoring ahead of consent.

**No functional change.** The attribute requires API 30+ and is ignored on API 26–29, where
behaviour is identical to leaving it unset. Every other value in the config is untouched:
`accessibilityEventTypes`, `accessibilityFlags`, `accessibilityFeedbackType`,
`canRetrieveWindowContent`, `canPerformGestures`, `canTakeScreenshot`, `description`,
`settingsActivity`, `notificationTimeout`.

**Form answers for `BIND_ACCESSIBILITY_SERVICE`:**

- *Is it an accessibility tool?* **No.**
- *Core function*: parental control and child-safety monitoring, installed by a parent on a
  child device with consent.
- *Why the permission is needed*: to read on-screen and typed text in order to detect bullying,
  grooming and other harmful content, and to identify the foreground app so screen-time limits
  can be enforced. No alternative Android API exposes in-app text content.
- *Disclosure and consent*: in-app consent screen before any monitoring begins, itemized per
  category, with parent/guardian attestation and a privacy policy link; a persistent notification
  runs on the child device while monitoring is active.
- *Data handling*: classification runs on-device; raw text and raw image bytes are never
  transmitted; only classification scores, category labels and alert metadata leave the device.

### Android 16 (API 36) readiness

- `compileSdk` / `targetSdk` raised to 36. No toolchain change was needed — AGP 8.13.2,
  Gradle 8.13, Kotlin 2.0.21 and SDK Platform 36 were already in place.
- **Predictive back**: `LockScreenActivity` relied on the deprecated `onBackPressed()` override,
  which is not invoked on the predictive-back path API 36 enables by default. The lock screen
  would have become dismissible. Replaced with an always-enabled `OnBackPressedCallback`.
- **Exact-alarm permissions removed**: `SCHEDULE_EXACT_ALARM` and `USE_EXACT_ALARM` were declared
  but the app schedules no exact alarms — only an inexact, Doze-friendly restart.
  `USE_EXACT_ALARM` is additionally restricted by Play to alarm-clock and calendar apps.
- **Compose BOM** 2023.10.01 → 2024.09.00; `activity-compose` 1.9.2; `lifecycle` 2.8.6.
  `material3` and `material-icons-extended` had hardcoded versions overriding the BOM and now
  defer to it.
- Edge-to-edge handling updated for the API 36 defaults.

### Build and security configuration

- Configuration externalized out of the repository: API hosts, OAuth client IDs and certificate
  pins come from untracked `secrets.properties`; signing from untracked `keystore.properties`.
  The repo ships placeholders only.
- Network security config split per build type, so cleartext stays debug-only.
- Release signing configured; R8 full mode on, with R8 pinned to 8.8.34 to avoid a
  tree-shaker `ConcurrentModificationException` in the version AGP 8.5.2 bundles.
- Certificate pinning is supported and may ship disabled when no verified pin set exists —
  blank pin values disable pinning rather than constructing an unsatisfiable pin. A build-time
  guard rejects any release whose pin subject host differs from the host the app actually calls,
  because such a pin is silently inert.
- Language splitting is deliberately **disabled** in the bundle config. With it on, a tester
  whose device locale differs from the install-time locale can receive an APK missing their
  strings. Density and ABI splitting remain on.

---

## Verification status

### Verified for this bundle

| Check | Result |
| --- | --- |
| Shipped AAB compiled manifest — `FOREGROUND_SERVICE_DATA_SYNC` | 0 occurrences |
| Shipped AAB compiled manifest — `dataSync` | 0 occurrences |
| Shipped AAB compiled manifest — `specialUse` | 2 |
| Shipped AAB compiled manifest — `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` | 2 |
| Foreground-service permissions declared | `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` — and nothing else |
| `clean bundleRelease` | `BUILD SUCCESSFUL`, 75 Kotlin warnings (identical to the pre-change baseline), zero foreground-service warnings |
| Build guard | Silent on this build; fails as designed on a reinserted permission and on `dataSync\|specialUse`; passes on the real manifest whose explanatory comment contains the word `dataSync` |
| `versionCode` in merged release manifest | `10` |
| `isAccessibilityTool` in shipped bundle resources | present, `false` |
| `BIND_ACCESSIBILITY_SERVICE` declared in shipped manifest | yes |
| `processDebugResources` after the config change | `BUILD SUCCESSFUL` — XML well-formed, attribute accepted |
| Bundle signature | Present |

Compiled manifests drop XML comments, so the zero counts above are conclusive rather than
contaminated by the explanatory prose that remains in the source manifest.

### Not verified in this build

Runtime verification of the foreground-service change on a device is **outstanding**. It needs a
child-registered device, which this build does not itself provide: `MonitoringService` declines
to start unless both `deviceRegistered` and `consent` are true, and clearing that gate requires a
`POST /devices` registration against the live backend on a real parent account plus accepting
the in-app disclosure.

Outstanding criteria — the running service reporting `specialUse`, reboot recovery, swipe-away
restart, lock-screen enforcement while backgrounded, continuity past the 6-hour window, and the
API 26–33 regression sweep. The per-criterion matrix with blocking reasons is in
`specs/015-remove-datasync-fgs-permission/quickstart.md`.

Also unverified: **the accessibility service at runtime.** Changing
`accessibility_service_config.xml` means the platform re-parses the service metadata on
install. The resource compiles cleanly and the new attribute is inert below API 30, but
`TextMonitoringAccessibilityService` has not been observed binding and delivering
`AccessibilityEvent`s on a device in this build. Same blocker as above — it needs a paired
child device with the service enabled in system settings.

The one piece of on-device evidence obtained, on an API 37 emulator: the installed package grants
only `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_SPECIAL_USE`, with no `DATA_SYNC` entry, and the
app installs and launches with no `ForegroundServiceStartNotAllowedException`,
`MissingForegroundServiceTypeException` or `SecurityException`.

---

## Known limitations

- **16 KB page size: not compliant.** TensorFlow Lite 2.16.1 ships 4 KB-aligned native libraries.
  Migrating to LiteRT 1.4.0+ would edit the ML pipeline, which is guardrailed against change
  without explicit approval. Verified, reported and deliberately deferred with a remediation path
  rather than silently accepted.
- **Expedited sync on API 26–30.** `SyncWorker.enqueueImmediate()` requests expedited work without
  overriding `getForegroundInfo()`. On API < 31 the work library runs expedited work inside its own
  foreground service and calls `getForegroundInfoAsync()`, whose `CoroutineWorker` default throws,
  so an immediate sync can fail on those versions. Periodic sync is unaffected. Pre-existing and
  out of scope for this build.
- **Restart alarm start mechanism.** `MonitoringService.scheduleServiceRestart()` arms
  `PendingIntent.getService` rather than `getForegroundService`, so when the alarm fires with the
  app backgrounded the start is subject to the API 26+ background-start restriction. Pre-existing;
  the restart strategy is frozen for this build.

---

## Play Console submission checklist

1. **`versionCode` is `10`.** Bumped from 9 because a build 9 bundle had already been produced
   and may have reached a track; Console rejects a duplicate `versionCode`. If 10 has also
   been uploaded, bump again and rebuild.
2. Upload `app-release.aab`.
3. Upload `mapping.txt` so crash traces deobfuscate.
4. **Foreground service declaration form**: only the **Special use** section should appear.
   If it asks about data sync, a dependency has reintroduced the permission — read
   `app/build/outputs/logs/manifest-merger-release-report.txt` to find which one.
   Paste the subtype purpose strings from the table above as the justification.
5. **Permissions Declaration Form — `BIND_ACCESSIBILITY_SERVICE`.** This was the blocker on
   the first review attempt. Answer **No** to "is this an accessibility tool". Full answer
   set under "Accessibility service declaration" above.
6. Complete the remaining restricted-permission declarations — `PACKAGE_USAGE_STATS`,
   `SYSTEM_ALERT_WINDOW`. Drafted text is in `docs/release-checklist.md` §6.
   **Neither `MANAGE_EXTERNAL_STORAGE` nor `QUERY_ALL_PACKAGES` is on this list any more.** Feature
   `016-remove-all-files-access` removed all-files access in 1.2.2 rather than declaring it, because
   parental control is not one of Play's permitted uses for it; package visibility moved to a
   filtered `<queries>` block in versionCode 12 for the same reason. See
   `docs/release-notes-1.2.2.md`.
7. Data safety form: declare on-device processing, and that raw monitored text and image bytes
   are not transmitted.
8. Point the account-deletion URL at the in-app deletion flow and its web equivalent.
9. Flag the app as directed at children / mixed audience as appropriate, and confirm the
   Families policy answers.

---

## Play Console "What's new" text

First release, so this reads as a launch description. Both locales are under the 500-character
limit.

### English (`en-US`)

```
Haris helps parents keep children safer on Android.

- Set daily screen-time limits and app restrictions, enforced on the child's device even offline
- Filter harmful web content
- Get alerts when risky text or images are detected, with analysis performed on the device
- Review activity and alerts from the parent app
- Available in English and Arabic

Content is analysed on your child's device. Raw text and images are never uploaded.
```

### Arabic (`ar`)

```
حارس يساعد الآباء على حماية أطفالهم على أندرويد.

- حدد وقت الشاشة اليومي وقيود التطبيقات، ويتم تطبيقها على جهاز الطفل حتى بدون اتصال بالإنترنت
- تصفية المحتوى الضار على الويب
- تلقَّ التنبيهات عند اكتشاف نصوص أو صور خطرة، مع تحليل يتم على الجهاز نفسه
- راجع النشاط والتنبيهات من تطبيق الوالدين
- متوفر بالعربية والإنجليزية

يتم تحليل المحتوى على جهاز طفلك. لا يتم رفع النصوص أو الصور الأصلية أبدًا.
```

---

## Commits in this build

Everything after `0360bf5`, which introduced the unpublished `versionCode 7`:

```
1964b68  fix: declare isAccessibilityTool=false explicitly; bump versionCode to 10
26eb363  docs: add release notes for 1.2.1 (versionCode 9)
72e7c73  docs: mark 015 task state, annotate device-blocked tasks
3c2e6ad  fix: remove orphaned FOREGROUND_SERVICE_DATA_SYNC and guard against its return
c52d858  change version code and version name (7/1.2.0 -> 9/1.2.1)
8f3c767  feat: add llama.cpp third-party dependency and account deletion quickstart spec
90b2962  docs: add account deletion spec (014)
0360bf5  feat: prepare Play Store release readiness (config externalization,
         network security split, API 36, release signing)
```

Earlier work carried in from before `versionCode 7` and shipping publicly for the first time
includes the violation-alert delivery pipeline (`9912696`, `b594628`), maximum-protection image
scanning (`cc0279d`), the forgot-password flow (`dd1fe57`) and Arabic localization.
