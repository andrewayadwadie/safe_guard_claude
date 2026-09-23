# Contract: Build-Time Validation Guards

**Feature**: `013-play-store-release-readiness` | **Requirements**: FR-005..FR-007, FR-010, FR-038

The build script's contract with the developer: what it refuses to produce, when, and what it says.

---

## Guard scope

Guards evaluate **only when a release task is in the task graph**. `assembleDebug` must never be
blocked by a missing or incomplete `secrets.properties` (FR-007) — a fresh clone must build on
committed example values alone.

Implement via `gradle.taskGraph.whenReady { }` or a `doFirst` on the release variant. Do **not**
evaluate at configuration time, which would fail every invocation including `./gradlew tasks`.

---

## G-1: Release URL must be HTTPS

**Trigger**: release task, `API_BASE_URL_RELEASE` does not start with `https://`

**Fails with**: a message naming `API_BASE_URL_RELEASE`, showing its current value, and stating that
release builds require HTTPS.

**Rationale**: a cleartext release URL contradicts `usesCleartextTraffic="false"` and the strict
network security config — the app would build successfully and then fail every request at runtime.

---

## G-2: Placeholders must be replaced

**Trigger**: release task, `API_BASE_URL_RELEASE` or `GOOGLE_WEB_CLIENT_ID` contains `REPLACE_ME`

**Fails with**: a message naming **which** key still holds a placeholder. When both do, name both —
reporting one at a time forces a second failed build to discover the second problem.

---

## G-3: Pin host must match request host

**Trigger**: release task, either `CERT_PIN_PRIMARY` or `CERT_PIN_BACKUP` is non-empty, **and**
`API_HOST` differs from the host component of `API_BASE_URL_RELEASE`

**Fails with**: a message naming both keys and both hosts, and explaining that a pin registered
against a host the app never contacts is silently inert.

**Rationale**: this is the exact defect present today (research R-008) — pins bound to
`api.safeguard.app` while traffic goes to `bw.noor.net`. Inert pinning is worse than absent pinning
because it reads as protection in code review. The guard is inactive while pins are empty, which is
the shipping configuration for this release, but it makes enabling pinning later safe by
construction.

---

## G-4: Signing credentials required for release

**Trigger**: release task, `keystore.properties` absent **or** `storePassword` blank

**Fails with**: a message naming the missing file or key, and listing the four values required.

**Must not**: affect `assembleDebug` in any way (FR-038). The existing build script already handles
the absent-file case; the blank-password case is new.

---

## Behavioral matrix

| Task | secrets present | pins set | host match | keystore | Outcome |
|---|---|---|---|---|---|
| `assembleDebug` | no | — | — | no | **Succeeds** on example fallback |
| `assembleDebug` | yes | yes | no | no | **Succeeds** — guards never run for debug |
| `assembleRelease` | no | — | — | yes | **Fails** G-2, names both keys |
| `assembleRelease` | placeholders | — | — | yes | **Fails** G-2, names offending key(s) |
| `assembleRelease` | http URL | — | — | yes | **Fails** G-1 |
| `assembleRelease` | valid | yes | no | yes | **Fails** G-3 |
| `assembleRelease` | valid | empty | n/a | no | **Fails** G-4 |
| `bundleRelease` | valid | empty | n/a | yes | **Succeeds**, signed |

---

## Expected state at Phase 1 checkpoint

```bash
./gradlew assembleDebug     # MUST pass
./gradlew assembleRelease   # MUST fail on G-2 until secrets.properties is filled
```

The release failure **is the correct outcome** at that checkpoint and must be reported as a pass,
not a defect. A release build that succeeds at Phase 1 means the guards are not wired.

---

## Preserved invariant

`aaptOptions { noCompress "tflite" }` must remain in `app/build.gradle` through every edit
(Constitution Rule 12). Removing it silently corrupts model loading with no build error. Verify at
every phase checkpoint.
