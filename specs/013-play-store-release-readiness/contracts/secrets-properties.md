# Contract: `secrets.properties`

**Feature**: `013-play-store-release-readiness` | **Requirements**: FR-001..FR-012

Build-time configuration supplied by the developer. Untracked. Falls back to
`secrets.properties.example` when absent, so a fresh clone builds with no setup step.

---

## `secrets.properties.example` (tracked)

```properties
# Copy to secrets.properties and fill in. secrets.properties is gitignored.
# When secrets.properties is absent, these values are used as the fallback,
# so a fresh clone builds debug without any setup.

API_BASE_URL_DEBUG=http://10.0.2.2:8000/api/v1/
API_BASE_URL_RELEASE=https://REPLACE_ME/api/v1/

# Host used as the certificate-pin subject. Must match the host in
# API_BASE_URL_RELEASE whenever either pin below is non-empty.
API_HOST=REPLACE_ME

GOOGLE_WEB_CLIENT_ID=REPLACE_ME.apps.googleusercontent.com

# Leave both EMPTY to disable certificate pinning (the posture for this release).
# An empty value means "no pinning" — never a pin built from an empty string.
CERT_PIN_PRIMARY=
CERT_PIN_BACKUP=
```

## `secrets.properties` (untracked)

Same key set. For this project the operator fills:

| Key | Value |
|---|---|
| `API_BASE_URL_DEBUG` | `http://10.0.2.2:8000/api/v1/` (or the LAN backend in use) |
| `API_BASE_URL_RELEASE` | `https://bw.noor.net:8090/api/v1/` |
| `API_HOST` | `bw.noor.net` |
| `GOOGLE_WEB_CLIENT_ID` | the existing id for Firebase project `haris-db7b0` |
| `CERT_PIN_PRIMARY` | *(empty)* |
| `CERT_PIN_BACKUP` | *(empty)* |

None of these are new secrets. They are being relocated out of the tracked build script.

---

## Emitted `BuildConfig` fields

| Field | Debug source | Release source |
|---|---|---|
| `API_BASE_URL` | `API_BASE_URL_DEBUG` | `API_BASE_URL_RELEASE` |
| `API_HOST` | `API_HOST` | `API_HOST` |
| `CERT_PIN_PRIMARY` | `CERT_PIN_PRIMARY` | `CERT_PIN_PRIMARY` |
| `CERT_PIN_BACKUP` | `CERT_PIN_BACKUP` | `CERT_PIN_BACKUP` |
| `GOOGLE_WEB_CLIENT_ID` | `GOOGLE_WEB_CLIENT_ID` | `GOOGLE_WEB_CLIENT_ID` |
| `API_VERSION` | unchanged, stays literal in the build script | same |

`API_BASE_URL` is set **per buildType**, not in `defaultConfig` (FR-004). The three commented-out
`API_BASE_URL` lines currently in `app/build.gradle` are removed entirely.

`buildFeatures { buildConfig true }` is already present — verify it survives.

---

## Consumer contract: `NetworkModule.provideCertificatePinner()`

Current implementation reads `Constants.API_HOST`, `Constants.CERTIFICATE_PIN_PRIMARY`, and
`Constants.CERTIFICATE_PIN_BACKUP`. It must read the `BuildConfig` fields instead (FR-008).

Required behavior:

| Condition | Behavior |
|---|---|
| Either pin blank | Return an empty `CertificatePinner`; log a Timber warning stating pinning is disabled |
| Both pins non-blank | Register both against `BuildConfig.API_HOST` |

**Must not**: construct `"sha256/"` + an empty string. That produces a pin no certificate can
satisfy, rejecting every connection.

**Unchanged**: the `if (!BuildConfig.DEBUG) { builder.certificatePinner(...) }` gate at
`NetworkModule.kt:137-139` stays exactly as-is.

---

## `Constants.kt` cleanup

Remove `API_HOST`, `CERTIFICATE_PIN_PRIMARY`, `CERTIFICATE_PIN_BACKUP` **only after** confirming no
remaining references (FR-011). Verified at planning time: `NetworkModule.kt:97` and
`NetworkModule.kt:99` are the sole consumers, and both are being rewritten. Re-grep before deleting
— do not rely on this note.

---

## `.gitignore`

Add `secrets.properties`. Already covered and needing no change: `keystore.properties`, `*.jks`,
`*.keystore`, `*.p12`, `*.pfx`, `app/google-services.json`.
