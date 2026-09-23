# Phase 1 Data Model: Play Store Release Readiness

**Feature**: `013-play-store-release-readiness` | **Date**: 2026-08-24

This feature introduces no runtime entities, no database tables, and no API payloads. Its "data
model" is the set of **build-time configuration entities** that determine what the artifact
contains and whether it is permitted to be produced.

---

## E-1: Build Configuration (`secrets.properties`)

Untracked, developer-supplied. Read at Gradle configuration time; falls back to
`secrets.properties.example` when absent (FR-003).

| Key | Type | Required | Placeholder | Consumed by |
|---|---|---|---|---|
| `API_BASE_URL_DEBUG` | URL | Yes | `http://10.0.2.2:8000/api/v1/` | `BuildConfig.API_BASE_URL` (debug) |
| `API_BASE_URL_RELEASE` | HTTPS URL | Yes | `https://REPLACE_ME/api/v1/` | `BuildConfig.API_BASE_URL` (release) |
| `API_HOST` | hostname | Yes | `REPLACE_ME` | `BuildConfig.API_HOST` → pin subject |
| `GOOGLE_WEB_CLIENT_ID` | OAuth client id | Yes | `REPLACE_ME.apps.googleusercontent.com` | `BuildConfig.GOOGLE_WEB_CLIENT_ID` |
| `CERT_PIN_PRIMARY` | base64 SHA-256, or empty | No | *(empty)* | `BuildConfig.CERT_PIN_PRIMARY` |
| `CERT_PIN_BACKUP` | base64 SHA-256, or empty | No | *(empty)* | `BuildConfig.CERT_PIN_BACKUP` |

### Validation rules

| Rule | Scope | Requirement |
|---|---|---|
| V-1 | Release tasks only | `API_BASE_URL_RELEASE` must start with `https://` |
| V-2 | Release tasks only | `API_BASE_URL_RELEASE` must not contain `REPLACE_ME` |
| V-3 | Release tasks only | `GOOGLE_WEB_CLIENT_ID` must not contain `REPLACE_ME` |
| V-4 | Release tasks only | If either pin is non-empty, `API_HOST` must equal the host of `API_BASE_URL_RELEASE` |
| V-5 | All build types | Failure messages name the offending key |
| V-6 | Debug tasks | V-1..V-4 never evaluated — debug builds are never blocked (FR-007) |

**Note on V-4**: this is the guard that prevents the current defect recurring. Pins bound to a host
the app never contacts are silently inert (research R-008), which is worse than no pinning because
it reads as protection.

### Known values for this project

`API_BASE_URL_RELEASE` = `https://bw.noor.net:8090/api/v1/`; `API_HOST` = `bw.noor.net`;
`GOOGLE_WEB_CLIENT_ID` = the existing id for Firebase project `haris-db7b0`. Both pins ship empty
per the Q1 decision. These are not new values — they are being relocated out of the tracked build
script.

---

## E-2: Signing Credentials (`keystore.properties`)

Untracked, operator-supplied. Already exists and is already gitignored.

| Key | Type | Required for release | Notes |
|---|---|---|---|
| `storeFile` | path, relative to repo root | Yes | |
| `storePassword` | secret | Yes | |
| `keyAlias` | string | Yes | |
| `keyPassword` | secret | Yes | |

### State transitions

```
absent  ──────────────► debug builds succeed; release tasks fail naming the file      (FR-038)
present, blank password ► debug builds succeed; release tasks fail naming the key      (FR-038)
present, complete ──────► release tasks produce a signed bundle                        (FR-039)
```

The existing `app/build.gradle` already implements the absent case correctly. The blank-password
case is new.

### Tracked companion — `keystore.properties.template` ⚠️

Currently contains the live-looking password `Haris@1234`, committed in `f7b8974`. Under FR-013 it
is scrubbed to empty placeholders. Under FR-014, any keystore created with that password is treated
as compromised and regenerated before it becomes an upload key.

---

## E-3: Network Security Policy

Two variants resolved by AGP resource merging; variant `res/` wins over `main/`.

| Variant | Path | Cleartext | Trust anchors | Pins |
|---|---|---|---|---|
| release | `app/src/main/res/xml/network_security_config.xml` | Denied, no exemptions | System only | None — OkHttp owns pinning (FR-019) |
| debug | `app/src/debug/res/xml/network_security_config.xml` | Permitted for `10.0.2.2`, `localhost`, `127.0.0.1`, `192.168.1.5`, `192.168.1.1` | System + user | None |

No manifest change: `android:networkSecurityConfig="@xml/network_security_config"` resolves per
variant automatically.

---

## E-4: Distributable Artifact

| Property | Value | Requirement |
|---|---|---|
| Format | Android App Bundle (`.aab`) | FR-039 |
| `versionCode` | **7** (from 6) | FR-040 |
| `versionName` | **"1.2.0"** (from "1.1.4") | FR-040 |
| Language split | **Disabled** — Arabic/RTL must ship in base | FR-041 |
| Density split | Enabled | FR-042 |
| ABI split | Enabled | FR-042 |
| ABIs included | `arm64-v8a`, `armeabi-v7a` only | FR-043 |
| Signature | Verifies against the upload certificate | FR-039 |
| Mapping file | Retained, location documented | FR-065 |

**Derived, not stored**: the per-device download estimate (FR-044) is computed via bundletool and is
distinct from the `.aab`'s on-disk size. Reporting the latter as the former would misrepresent what
testers actually download.

**Size baseline is currently unknown.** Research R-004 established that the on-disk 64 MB APK is
stale — it contains an 11.3 MB library the current source does not build, and only one ABI where
current configuration would produce four. Every size figure must be re-measured from a clean build.

---

## E-5: Release Documentation (`docs/release-checklist.md`)

The operator's single reference. Must contain (FR-056):

1. Manual verification steps from Phase 3
2. The `keytool` command for generating the upload key, with a note that Play App Signing makes it an
   **upload** key, not the final app signing key (FR-047)
3. The Play App Signing SHA-1 registration step for Firebase and Google Cloud — without which Google
   Sign-In fails for every Play install
4. Every value the operator must supply into `secrets.properties` and `keystore.properties`
5. Per-phase "what changed and why" summary
6. Permission declaration justifications for the five restricted permissions (FR-058..FR-061)
7. The public privacy-policy URL requirement and drafted data-safety answers (FR-068..FR-070)
8. Mapping-file upload instructions, and why omitting it makes every crash report unreadable (FR-066)

---

## E-6: Permission Declarations

Documentation-only. Drafted for the operator to paste into Play Console forms (FR-058..FR-061).

| Permission | Declaration type | Enforcement feature |
|---|---|---|
| `MANAGE_EXTERNAL_STORAGE` | All-files access | Replacing detected images with blurred versions |
| `QUERY_ALL_PACKAGES` | Package visibility | Per-app screen-time limits and app blocking |
| `BIND_ACCESSIBILITY_SERVICE` | Accessibility API policy | Text monitoring |
| `BIND_VPN_SERVICE` | VpnService | DNS-sinkhole content filtering |
| `PACKAGE_USAGE_STATS` | Sensitive permission | Screen-time measurement |

Every entry maps to a named enforcement feature, satisfying Constitution Principle IV and giving
each declaration a truthful justification.

**Removed, not declared**: `SCHEDULE_EXACT_ALARM` and `USE_EXACT_ALARM` — no exact-alarm scheduling
exists anywhere in the app (research R-010), so both are unmapped and are deleted under FR-035.

---

## Entity relationships

```
secrets.properties ──fallback──► secrets.properties.example
        │
        ├── validated by ──► Build Guards (V-1..V-6, release only)
        └── emits ─────────► BuildConfig fields ──► NetworkModule certificate pinning

keystore.properties ──shape──► keystore.properties.example
        └── gates ─────────► Distributable Artifact (signing)

Network Security Policy ──resource merge──► Distributable Artifact (release variant)

Distributable Artifact ──produces──► mapping file ──uploaded with──► bundle

Release Documentation ──describes──► every entity above
```
