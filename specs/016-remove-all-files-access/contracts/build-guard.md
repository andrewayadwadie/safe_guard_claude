# Contract: All-Files-Access Merged-Manifest Guard

**Feature**: `016-remove-all-files-access` | **Satisfies**: FR-001, FR-025, FR-026, FR-027, SC-001, SC-009

## Why a guard at all

Deleting `android.permission.MANAGE_EXTERNAL_STORAGE` from the app manifest fixes the store
blocker once. It does not keep it fixed. A dependency upgrade can contribute a `<uses-permission>`
node into the **merged** manifest with no change to any file a reviewer reads, and the first signal
would be a rejected submission weeks later — after the next release has already been staged.

Feature 015 established this pattern for the foreground-service permission. This contract extends
it rather than duplicating it.

## Decision: extend the existing guard, do not add a second task

The guard added by feature 015 at `app/build.gradle` already does everything this one needs: it
hooks `process{Debug,Release}MainManifest`, locates the merged manifest, strips XML comments, and
compares every `<uses-permission>` `android:name` **value** for equality. It gains one entry in a
banned-permission set.

A parallel task was rejected. Two guards reading the same file at the same point in the same build
would duplicate the manifest-location logic, the comment-stripping logic and the
origin-report message, and a future reader would have to check both to know what is enforced.

## Behaviour

### G-1 — Banned permission set

The guard MUST fail the build when the merged manifest declares a `<uses-permission>` whose
`android:name` value equals any of:

| Permission | Banned by |
| --- | --- |
| `android.permission.FOREGROUND_SERVICE_DATA_SYNC` | feature 015 |
| `android.permission.MANAGE_EXTERNAL_STORAGE` | **this feature** |

Comparison is on the attribute **value**, by equality. A substring search is forbidden: the app
manifest will carry a comment explaining why all-files access is absent and must not be reintroduced,
and that comment is the human-readable guardrail against this same regression. Comments are stripped
before matching, exactly as the existing guard does, so the guard cannot fail on its own
documentation.

### G-2 — Missing input is a failure, not a pass

If the guard cannot locate a merged manifest produced by the task it is attached to, it MUST throw.
A guard that silently skips when its input is missing reports a safety it never checked. This
behaviour already exists and MUST be preserved when the banned set is extended.

### G-3 — Both variants

The guard runs for debug and release. The release path is what protects the submission; the debug
path is what makes the failure surface during ordinary development rather than at release time.
`bundleRelease` and `assembleRelease` both depend on `processReleaseMainManifest`, so the guard
cannot be bypassed while producing a release artifact.

### G-4 — Actionable message

A failure MUST name the offending permission, the manifest file it was found in, and the path to
the manifest-merger report that identifies the contributing library when the origin is not the
app's own manifest. For the new entry the message MUST also state *why* it is banned, so the next
developer does not simply re-add it:

> Google Play restricts all-files access to file managers, backup/restore, anti-virus, document
> management, on-device file search, disk encryption and device migration. Parental control is not
> a permitted use. Image writes go through parent-granted folder access instead — see
> `specs/016-remove-all-files-access/`.

### G-5 — Must not disturb neighbours

The guard block sits with feature 013's release-configuration guards and feature 015's
foreground-service guard at the bottom of `app/build.gradle`. It MUST NOT touch the
`aaptOptions { noCompress "tflite" }` block (enforcement rule 12), and MUST NOT alter the existing
foreground-service checks' behaviour while extending them.

## Acceptance tests

| # | Setup | Expected |
| --- | --- | --- |
| **AT-1** | Clean tree, no all-files access anywhere | `assembleDebug` and `bundleRelease` succeed; guard passes having actually read the manifest |
| **AT-2** | Re-add `MANAGE_EXTERNAL_STORAGE` to `app/src/main/AndroidManifest.xml` | Both builds fail, message names the permission, the file, the merger report and the policy reason |
| **AT-3** | A comment in the app manifest containing the literal text `MANAGE_EXTERNAL_STORAGE` | Builds succeed — comments are stripped before matching |
| **AT-4** | `FOREGROUND_SERVICE_DATA_SYNC` re-added | Still fails, with 015's message — the extension did not weaken the existing check |
| **AT-5** | Both banned permissions present | Fails listing **both**, not just the first found |
| **AT-6** | Merged manifest output made unavailable to the guard | Build fails with the cannot-locate message (G-2) |

AT-2 and AT-6 are SC-009's merge gate. AT-4 is the regression test for feature 015, which this
change must not break.

## Out of scope

- Checking the AAB itself rather than the merged manifest. The merged manifest is the input the
  packaging step consumes, and feature 015 already settled this trade-off.
- Blocking `MANAGE_MEDIA`. It is not declared, not used, and rejected in research R-002 — but it is
  a *legitimate* future option for the `Download`-folder gap, so banning it in a build guard would
  pre-empt a decision this feature has no standing to make.
- Blocking the legacy write permission. It has never been declared, and the guard's job is
  protecting a removal that actually happened.
