# Contract: `keystore.properties`

**Feature**: `013-play-store-release-readiness` | **Requirements**: FR-013, FR-014, FR-037, FR-038, FR-047

Signing credentials supplied by the operator. Untracked and already gitignored.

---

## ⚠️ Pre-existing secrets incident

`keystore.properties.template` is **tracked in git** and contains:

```properties
storePassword=Haris@1234
keyPassword=Haris@1234
```

Committed in `f7b8974`. Under Constitution Principle V this is a P0 incident.

**Remediation (FR-013, FR-014)**:

1. Scrub the tracked template to empty placeholders.
2. Treat any keystore created with that password as compromised.
3. Generate a replacement **before** registering an upload key with Play. Replacing an upload key
   after registration requires a Google support request; replacing it before costs nothing.
4. The password is in git history. Scrubbing the working tree does not remove it from prior commits
   — history rewriting is out of scope here, which is precisely why the key must be regenerated
   rather than the leak merely tidied.

This work is **independent of all four phases** and should be done first.

---

## `keystore.properties.example` (tracked, new)

```properties
# Copy to keystore.properties and fill in. keystore.properties is gitignored.
# Play App Signing re-signs the bundle, so this is your UPLOAD key,
# not the final app signing key.
storeFile=keystore/upload-keystore.jks
storePassword=
keyAlias=upload
keyPassword=
```

Note `storeFile` is resolved via `rootProject.file(...)`, so the path is relative to the repository
root. The existing template's `../keystore/...` form would resolve outside the repo.

---

## `keystore.properties` (untracked)

Same keys, operator-filled. Already exists; values must be replaced with those of the newly
generated key per FR-014.

---

## Build script contract

```
absent                    → debug succeeds; release tasks fail naming the file
present, storePassword="" → debug succeeds; release tasks fail naming the key
present, complete         → release tasks produce a signed bundle
```

The `signingConfigs.release` block must not be defined at all when credentials are unusable, and
must never affect `assembleDebug` (FR-038). The existing `app/build.gradle` handles the absent case;
the blank-password case is new.

---

## Key generation (operator runs this — never automated, FR-047)

```bash
keytool -genkeypair -v \
  -keystore keystore/upload-keystore.jks \
  -alias upload \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -storetype PKCS12
```

`-validity 10000` (~27 years) is Google's recommended minimum — a key that expires before the app's
lifetime cannot be used to publish updates.

Add `/keystore/` to `.gitignore`. `*.jks` already matches, but the directory entry prevents
accidentally committing anything else placed there.

---

## Play App Signing — the step that breaks Google Sign-In if skipped

Play re-signs the uploaded bundle with an **app signing key** that differs from the upload key. The
certificate fingerprint that reaches a user's device is therefore **not** the one from the keystore
above.

Google Sign-In validates against the fingerprint registered for the OAuth client. Unless the **app
signing SHA-1** — from Play Console → Setup → App Integrity — is added to both the Firebase project
and the Google Cloud OAuth client, Google Sign-In fails with `DEVELOPER_ERROR` for every user who
installs from Play, while working perfectly on every locally-signed build.

This cannot be automated and cannot be caught by any build-time check. It is the single most likely
cause of a failed first Internal Testing round, and belongs in `docs/release-checklist.md` as an
explicit step.

---

## Bundle configuration

```groovy
bundle {
    language { enableSplit = false }   // Arabic/RTL must ship in the base APK
    density  { enableSplit = true }
    abi      { enableSplit = true }
}
```

**Language splitting must stay disabled** (FR-041). The app ships Arabic and English; with language
splitting on, a tester whose device locale differs from the install-time locale can receive an APK
missing their strings.

```groovy
ndk { abiFilters 'arm64-v8a', 'armeabi-v7a' }
```

Required (FR-043). No `abiFilters` currently exists, and TFLite ships four ABIs — so the current
configuration would package `x86` and `x86_64` (~9 MB of native payload) that no target device uses.

---

## Version

| Field | From | To |
|---|---|---|
| `versionCode` | 6 | **7** |
| `versionName` | "1.1.4" | **"1.2.0"** |

The originating brief specified `versionCode 3`. That is a **downgrade** from the current 6 and
would be rejected by Play, which requires each upload to strictly exceed the highest version code
previously uploaded.

---

## Mapping file (FR-065, FR-066)

Release builds run R8, so stack traces from testers arrive obfuscated. Retain:

```
app/build/outputs/mapping/release/mapping.txt
```

Upload it alongside the bundle. Without it every crash report is unreadable, and the mapping for a
given build cannot be reconstructed after the fact.
