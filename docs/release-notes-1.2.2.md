# Release notes — 1.2.2 (versionCode 12)

**Feature**: `016-remove-all-files-access` | **Date**: 2026-10-07
**Previous**: [1.2.1 (versionCode 10)](./release-notes-1.2.1.md)

> versionCode 11 carried the same feature work but was never released: uploading it raised a second
> Play Console permission declaration, for `QUERY_ALL_PACKAGES`. That permission is removed in
> versionCode 12 (see *Package visibility*), and 11 is superseded. The versionName stays `1.2.2`
> because nothing user-facing changed between the two bundles.

## Why this release exists

The Play Console blocked the review with an undeclared **All files access**
(`MANAGE_EXTERNAL_STORAGE`) permission. Google Play restricts that permission to file managers,
backup and restore tools, anti-virus apps, document managers, on-device file search, disk encryption
and device migration. Parental control is not on that list, and the declaration form's mandatory
question — why the Storage Access Framework cannot be used instead — had no good answer, because it
can be.

So the permission is gone rather than argued for. **No All files access declaration is made, and
none should be filled in.**

## What changed

### The permission is removed, and guarded

`MANAGE_EXTERNAL_STORAGE` is absent from the manifest and from the merged manifest of the release
bundle. The build guard that already banned `FOREGROUND_SERVICE_DATA_SYNC` was extended with it, so a
dependency upgrade that quietly contributes it fails the build rather than the store review weeks
later. The failure message names the permission and the policy reason.

### Photos are now actually requested at runtime — the fix nobody noticed was needed

An audit before this work found something the feature was not looking for: `READ_MEDIA_IMAGES` was
declared in the manifest but **never requested at runtime on Android 11 or above**. The setup flow
routed those versions straight to the All files access settings screen instead. Confirmed on a
fully set-up child device, where the permission read `granted=false` while all-files access was
allowed at UID level.

Every image read on those versions — the periodic scan, the real-time watch, the classifier — was
authorised solely by all-files access. Removing the permission without fixing this would have ended
image protection silently on most of the installed base, while the UI kept reporting it as on. The
app now requests the ordinary photo permission on every supported version.

**A partial grant is refused.** From Android 14 the permission prompt offers "Select photos". A
hand-picked selection cannot contain an image the child receives tomorrow, which is the whole job, so
it is reported as not protected with that explanation rather than quietly accepted.

### Blurring a flagged image now uses folder access the parent grants

Replacing an inappropriate image with a blurred version, restoring it when the parent approves, and
deleting it when they reject all now go through folder access granted once per picture folder through
the system document picker — the camera folder and the pictures folder, plus a device-specific
screenshots folder where one exists. Two pickers cover six of the seven watched locations, the grants
survive reboots and app updates, and no prompt is ever shown to the child.

Revoking that access in system settings degrades honestly: detection, the private backup and the
parent alert keep working, and the parent is told that blurring is inactive and why.

### In-place blurring works on Android 8, 9 and 10 for the first time

All-files access only exists from Android 11, and no legacy write permission or legacy-storage
opt-in was ever declared. On Android 8–10 the blur, the restore and the delete had therefore always
failed — silently, behind a parent-facing success path. Folder access works from Android 5, so these
versions gain the capability rather than losing it.

### The app no longer claims a blur it did not perform

A flagged image is recorded as blurred only after the write is verified. When the blur cannot happen,
the reason is recorded — no folder access, access revoked, a folder the platform will not grant, the
image gone, or a failed write — and the parent is shown it. The private backup is taken and verified
*before* the gallery file is opened, and a write that fails part-way restores the original from that
backup; a failed restore or delete never discards the backup.

### Package visibility replaces QUERY_ALL_PACKAGES

Uploading versionCode 11 raised a second declaration form: Google Play treats the inventory of
installed apps as personal and sensitive data and restricts `QUERY_ALL_PACKAGES` to apps whose core
purpose is broad app visibility — device search, anti-virus, file managers. Parental control is not on
that list.

The permission was not needed. The justification drafted for it rested on a wrong premise: that a
filtered `<queries>` list could not work "because the parent may restrict any app on the device, which
is not knowable in advance". A `<queries>` entry does not have to name packages — it can match an
**intent**, and `ACTION_MAIN` + `CATEGORY_LAUNCHER` matches every launchable app on the device without
naming any of them. That is exactly the set of apps a parent can limit or block.

The manifest now declares two intent queries, launcher and home, and no package-visibility
permission. Nothing in the product changes:

| Capability | Still works because |
| --- | --- |
| The parent's app list for per-app limits and blocking | `getInstalledApplications()` returns every launcher-visible app, and the list already dropped anything without a launch intent — the visible set is identical |
| App name and icon for a chosen app | Launchable apps are visible to `getApplicationInfo`, `getApplicationLabel` and `getApplicationIcon` |
| Screen-time and block enforcement on the foreground app | The package name comes from `UsageStatsManager` under `PACKAGE_USAGE_STATS`, which needs no package visibility |
| Keeping OEM launchers out of text monitoring | `queryIntentActivities(ACTION_MAIN + CATEGORY_HOME)` is covered by the second query |

The one narrowing: a package with no launcher activity is no longer visible, so its human-readable
label is unavailable and the UI shows the package name — the fallback it already used whenever a
lookup failed. Such packages were never offered to the parent.

`QUERY_ALL_PACKAGES` was added to the same build guard that bans `MANAGE_EXTERNAL_STORAGE`, so a
dependency cannot reintroduce it into the merged manifest without failing the build.

## Known limitation: the Downloads folder

**Images saved to `Download` are detected and reported to the parent but are never blurred in
place.** From Android 11 the platform refuses to grant that folder to any app through the document
picker, and the only alternative would show the child a system dialog they could decline — which is
not enforcement. The parent is told this once in-product rather than left to discover it.

The app's reach over the child's storage therefore narrows from every file on the device to the
picture folders the parent chose: strictly less access for the same protection.

## Privacy

Unchanged, and narrowed. Image analysis stays entirely on the child's device. Only a category label,
a confidence score and alert metadata leave it. No image bytes and no file paths are transmitted. No
new data is collected; the only new stored value is a flag recording that the Downloads notice has
been shown.

## Play Console checklist for this submission

1. **Do not fill in the All files access form.** If it appears, the permission has returned through a
   dependency — check `app/build/outputs/logs/manifest-merger-release-report.txt`.
2. **Do not fill in the `QUERY_ALL_PACKAGES` form either.** The permission is not declared. If the
   console still presents it, the bundle being reviewed is versionCode 11 or older, or a dependency
   has contributed the permission — check the same merger report.
3. The foreground-service form should still present only the Special use section, as in versionCode
   10.
4. The accessibility declaration is unchanged from versionCode 10: answer **No** to "is this an
   accessibility tool".
5. Data safety form unchanged: on-device processing; raw monitored text and image bytes are not
   transmitted.

## Verification status

Merge gates and their evidence are tracked in
`specs/016-remove-all-files-access/quickstart.md` and `specs/016-remove-all-files-access/evidence/`.

**Outstanding before submission**: the device gates. Every emulator available on the build machine
reports API 37, so the Android 8–10 rung (which this release claims to fix) and the API 30/33/34
rungs are recorded as device-blocked, not passed. Do not submit the bundle until quickstart scenarios
1, 2, 6, 7 and 8 are signed off.
