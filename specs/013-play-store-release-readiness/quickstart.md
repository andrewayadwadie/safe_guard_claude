# Quickstart: Validating Play Store Release Readiness

**Feature**: `013-play-store-release-readiness` | **Date**: 2026-08-24

How to verify each phase actually worked. Run these in order; each phase reports before the next
begins (FR-054).

**Prerequisites**: JDK on PATH (currently Java 22), Android SDK with `android-36` and build-tools
`36.0.0` (both installed), a physical device or emulator for the manual checks.

---

## Invariant — check at every phase

```bash
grep -A2 'aaptOptions' app/build.gradle    # must still contain: noCompress "tflite"
```

Constitution Rule 12. Removing it corrupts model loading with no build error.

---

## Phase 0 — Secrets remediation

```bash
grep -E 'storePassword|keyPassword' keystore.properties.template
```

**Expect**: both keys present with empty values. Any credential-looking string is a failure.

```bash
git ls-files | xargs grep -lE 'Haris@1234' 2>/dev/null
```

**Expect**: no output.

**Manual**: confirm the operator has generated a replacement keystore, and has *not* registered the
compromised one with Play. The password remains in git history — regeneration is the remedy, not
scrubbing.

---

## Phase 1 — Configuration externalization

```bash
./gradlew assembleDebug
```
**Expect**: PASS. Also verify it passes with `secrets.properties` temporarily renamed — a fresh
clone must build on example values alone (FR-003).

```bash
./gradlew assembleRelease
```
**Expect**: **FAIL**, with a message naming `API_BASE_URL_RELEASE` and/or `GOOGLE_WEB_CLIENT_ID`.

> This failure is the pass condition. A release build that *succeeds* here means the guards are not
> wired. See [contracts/build-guards.md](./contracts/build-guards.md) for the full matrix.

```bash
git check-ignore -v secrets.properties     # must report a .gitignore match
grep -rn 'CERTIFICATE_PIN\|API_HOST' app/src/main/java/   # no Constants.* references remain
```

**Manual**: confirm `NetworkModule.provideCertificatePinner()` returns an empty `CertificatePinner`
and logs a warning when pins are blank — and does **not** build a pin from an empty string.

---

## Phase 2 — Network security split

```bash
./gradlew assembleDebug
./gradlew processReleaseManifest
```

Then inspect which resource each variant resolves:

```bash
# release must be the strict config
unzip -p app/build/outputs/apk/release/*.apk res/xml/network_security_config.xml 2>/dev/null | head -40
```

**Expect (release)**: `cleartextTrafficPermitted="false"`, system trust anchors only, no
`<domain-config>` exemptions, no `<debug-overrides>`, no commented-out pin block, no `<pin-set>`.

**Expect (debug)**: the five development hosts reachable in cleartext, user CAs trusted.

**Expect (manifest, both)**: `android:usesCleartextTraffic="false"` and
`android:networkSecurityConfig="@xml/network_security_config"` still present on `<application>`.

> Accuracy note: `<debug-overrides>` is only honoured when `android:debuggable="true"`, so user-CA
> trust never actually reached release. The cleartext `<domain-config>` block, which has no such
> gate, **did**. Report the exposure precisely.

---

## Phase 3 — API 36 migration

> **Blocked.** Do not start until BD-1 (TensorFlow Lite 16 KB compliance) and BD-2 (constitution
> amendment) are resolved. See [plan.md](./plan.md) § Blocking Decisions.

```bash
./gradlew -version          # confirm the JDK Gradle actually uses (FR-022)
./gradlew clean assembleDebug
./gradlew test
```
**Expect**: both PASS, zero new test failures.

### 16 KB alignment verification

```bash
unzip -o -j app/build/outputs/apk/debug/app-debug.apk 'lib/arm64-v8a/*.so' -d /tmp/soalign
cd /tmp/soalign
for f in *.so; do
  echo "$f: $(readelf -lW "$f" | awk '$1=="LOAD"{print $NF}' | sort -u | tr '\n' ' ')"
done
```
**Expect**: every library reports `0x4000`. **Currently every one reports `0x1000`** — this is BD-1.

> Always re-extract from a **freshly built** APK. The APK on disk at planning time was two days
> older than `build.gradle` and contained an 11.3 MB library the current source does not build
> (research R-004). Stale artifacts will give you a wrong answer here and in the size analysis.

### Manual device checks (FR-030..FR-032, SC-006..SC-008)

- [ ] App launches on an Android 16 device or emulator
- [ ] No control or text clipped behind status or navigation bars, on every screen
- [ ] Lock screen covers the full screen; 10 consecutive back / predictive-back gestures do not dismiss it
- [ ] Accessibility service enables and fires events
- [ ] VPN service starts and blocks a test domain
- [ ] Monitoring service still running after 6+ hours (leave overnight)
- [ ] Image scan still classifies correctly — ML pipeline untouched
- [ ] Manifest declares neither `SCHEDULE_EXACT_ALARM` nor `USE_EXACT_ALARM`

---

## Phase 4 — Signing, bundle, size

### ProGuard audit first (Constitution Rule 11)

```bash
grep -oE '\.(service|receiver|worker)\.[A-Za-z]+' app/src/main/AndroidManifest.xml | sort -u
grep -c 'keep' app/proguard-rules.pro
```
Every Service, Receiver and Worker in the manifest needs a keep rule. Gaps found here predate this
feature and are the most likely cause of a smoke-test failure.

### Build

```bash
./gradlew clean bundleRelease
ls -lh app/build/outputs/bundle/release/*.aab
```

### Verify signature (FR-039)

```bash
jarsigner -verify -verbose -certs app/build/outputs/bundle/release/*.aab | head -20
```
**Expect**: `jar verified`, certificate matching the upload key.

### Download size (FR-044)

bundletool is **not installed** (research R-007). Install it, then:

```bash
bundletool build-apks --bundle=app-release.aab --output=out.apks \
  --connected-device --ks=keystore/upload-keystore.jks --ks-key-alias=upload
bundletool get-size total --apks=out.apks --dimensions=ABI,SCREEN_DENSITY
```
**Expect**: a per-device estimate for a typical `arm64-v8a` device.

> Do **not** substitute the `.aab`'s on-disk size. It is not the number Play shows testers, and
> reporting it as such misrepresents the download.

### Size breakdown (FR-045)

```bash
du -sh app/src/main/assets/* | sort -h
unzip -l app/build/outputs/apk/release/*.apk 2>/dev/null | grep '\.so$'
```
**Expect**: `nsfw_classifier.tflite` (24.4 MB) dominates; native libs only `arm64-v8a` and
`armeabi-v7a`, with no `x86`/`x86_64`.

### llama.cpp (FR-046)

```bash
find app/build/outputs -name 'libsafeguard_llm*.so'
```
**Expect**: no output from a clean build. Research R-004 established it is not a build input; the
206 MB under `third_party/` is working-copy disk only. **Report, do not remove.**

### Release-build smoke test (FR-062..FR-064) — the step nothing else substitutes for

Install the **minified release** build and exercise:

- [ ] Login completes
- [ ] Monitoring service starts
- [ ] VPN service starts
- [ ] Accessibility service receives an event
- [ ] One image classification succeeds

**Expect**: zero crashes, zero `ClassNotFoundException` / `NoSuchMethodError`.

> This build has never been run. R8 strips reflectively-reached code — Hilt graphs, Room entities,
> TFLite reflection, manifest-declared services — without emitting a build error. A verified
> signature says nothing about whether the app starts.

### Mapping file (FR-065)

```bash
ls -lh app/build/outputs/mapping/release/mapping.txt
```
**Expect**: present. Upload with the bundle, or every tester crash report is unreadable.

---

## Final deliverable

`docs/release-checklist.md` exists and contains all eight items from
[data-model.md](./data-model.md) § E-5.

```bash
git status --short    # changes present, NOTHING committed (FR-055)
```

Propose commit boundaries; do not commit.
