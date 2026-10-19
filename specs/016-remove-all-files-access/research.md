# Phase 0 Research: Replace All-Files Access with Parent-Granted Folder Access

**Feature**: `016-remove-all-files-access` | **Date**: 2026-10-07 | **Spec**: [spec.md](./spec.md)

Nine questions had to be answered before the design could be fixed. Two of the answers changed the
design materially (R-002 and R-005). One answer removes a dependency the design would otherwise
have added (R-007). Three items cannot be settled from documentation and are carried into the
quickstart as device-verification gates (R-004, R-008, R-009).

---

## R-001 — Which folders can the parent actually grant?

**Decision**: Offer the parent the **camera folder tree (`DCIM`)** and the **pictures folder tree
(`Pictures`)**. Do not offer the storage root, do not offer `Download`, and resolve the
device-specific root-level `Screenshots` folder case at runtime rather than assuming it.

**Rationale**: The platform documentation is explicit about what the folder picker refuses from
Android 11 onward:

> The root directory of the internal storage volume.
> The root directory of each SD card volume that the device manufacturer considers to be *reliable* …
> The `Download` directory.

and separately, individual-file selection is blocked from `Android/data/` and `Android/obb/` and
all their subdirectories. `DCIM` and `Pictures` appear on neither list, and both carry the
watched subfolders the app already monitors (`DCIM/Camera`, `DCIM/Screenshots`,
`Pictures/Screenshots`) as subtrees of a single grant each. Two pickers therefore cover six of the
seven watched locations.

`Android/media/` is on neither restriction list, which is where current messaging apps keep
received images — but the app watches no messaging folder today, so this is recorded for a future
feature and not acted on here (spec Out of Scope).

**Consequence for the seventh location**: the root-level `Screenshots` folder that some
manufacturers use sits directly under the storage root. It is itself grantable — only the *root*
is refused, not its children — but it exists on a minority of devices and the app already guards
its watch registration with an existence check. The grant flow must do the same: offer it only
when it exists, and never block setup completion on it.

**Alternatives considered**: a single grant of the storage root, which the platform refuses
outright; one grant per watched subfolder (seven pickers), rejected because two tree grants
already cover the same files and SC-005 budgets 90 seconds for the whole flow.

---

## R-002 — Does `MANAGE_MEDIA` solve this better than folder grants?

**Decision**: **No. Use folder grants.** Record `MANAGE_MEDIA` as a rejected alternative with a
possible future additive role.

**Rationale**: `MANAGE_MEDIA` (Android 12+, granted by the user on the system *Media management
apps* screen) exists precisely to let an app modify media it does not own without a per-file
dialog, and it is **absent from the store's all-files-access policy page**, which lists only the
media-database API and the folder picker as the privacy-friendly alternatives it expects. On
paper it looks like a better fit than folder grants: one system toggle instead of two pickers, and
it would cover the `Download` folder, recovering the one capability the spec gives up.

Three facts defeat it for this app:

1. **It is a request-and-confirm flow, not a direct write grant.** What the permission removes is
   the *confirmation dialog* in front of `createWriteRequest`, `createTrashRequest` and
   `createDeleteRequest`. Those calls still return an intent sender that must be launched and
   have its result collected. The app's writes happen inside a background worker and a background
   service, neither of which has an activity to launch from. Folder-grant writes need nothing but
   a content resolver and work from any context.
2. **Android 12 and higher only.** The app supports Android 8 upward. Folder grants work from
   Android 5. Choosing `MANAGE_MEDIA` would leave Android 11 — where in-place blurring works
   today via all-files access — regressed to not working, while folder grants fix Android 8, 9
   and 10 where it has never worked (spec Audit Finding 2).
3. **The documented prerequisite is the legacy read permission**, which the manifest declares only
   up to Android 12L. Aligning that for Android 13+ is undocumented territory for a capability
   that items 1 and 2 already rule out.

**Future note**: `MANAGE_MEDIA` remains the only known way to blur a `Download`-folder image
without a child-facing prompt. If that gap ever justifies its own feature, the shape would be an
*additive* Android-12+ path layered on the folder-grant foundation this feature builds — never a
replacement for it.

**Alternatives considered**: per-image `createWriteRequest` without `MANAGE_MEDIA` — rejected in
the spec's Clarifications, because it shows the child a dialog they can decline, and enforcement a
child can decline is not enforcement (FR-017).

---

## R-003 — How does a detected image become a writable document?

**Decision**: Derive the document URI arithmetically from the granted tree URI plus the image's
path relative to that tree. Do not enumerate the tree to find the file.

**Rationale**: A tree grant on primary external storage yields a tree URI whose document
identifier is `primary:<relative path>` — for example `primary:DCIM` for the camera tree. A child
document under that tree has the identifier `primary:DCIM/Camera/IMG_0001.jpg`, and the platform
provides a direct construction from (tree URI, child document id) to a usable child document URI.
Since the image's own relative path is already known at detection time, the mapping is a string
operation and a single call — no directory listing, no recursive walk, and no cost that scales
with the size of the child's gallery.

The media database supplies the relative path and display name as columns, so the periodic scan
already has the exact inputs. The real-time trigger must supply the same two values, which R-005
settles.

**Alternatives considered**: walking the tree with the document-file helper to match by display
name — correct but linear in folder size, and a camera folder with several thousand images would
make every blur noticeably slow for no benefit.

---

## R-004 — Can the classifier still read image bytes with only the narrow photo permission?

**Decision**: Yes for the file-path read the classifier does today, and the design keeps that read
path. Carry a device-verification gate rather than trusting it blindly.

**Rationale**: Android 11 deliberately restored non-database read access to shared media:

> Android 11 (API level 30) and higher let you use APIs other than the `MediaStore` API to access
> media files from shared storage … the `File` API … native libraries, such as `fopen()`

with other apps' files requiring the narrow photo permission on Android 13+ and the legacy read
permission below that. The classifier decodes a bitmap from a path, and the app-private backup
copy is a path-to-path stream copy; both are reads and both are covered.

The same documentation is equally explicit that this does **not** extend to writing:

> If your app tries to access a file using the `File` API and it doesn't have the necessary
> permissions, a `FileNotFoundException` occurs

which is exactly why the three write call sites — and only those three — must move to folder
grants.

**Verification gate**: the read path is documented but the app has never exercised it, because the
narrow permission has never been requested at runtime (spec Audit Finding 1). Quickstart scenario
1 proves a decode succeeds with the narrow permission alone, on Android 11, 13 and 16, before any
other work is trusted.

---

## R-005 — Does the real-time folder watch survive without all-files access?

> **Corrected during implementation (2026-10-07).** This item was written from the spec's audit
> table, which recorded the real-time trigger as "raw-path folder watching". Reading
> `MediaFileObserver` to implement the change showed that a media-database `ContentObserver` **is
> already the primary trigger** and has been since the image-protection feature shipped — the file
> observers are explicitly a fallback (`registerMediaStoreObserver()` is called first, commented
> "PRIMARY", and `startFileObservers()` second, commented "FALLBACK").
>
> The decision below was therefore already satisfied. No trigger migration was needed; the work
> reduced to correcting the class documentation, which claimed "Uses Android's FileObserver API for
> real-time detection" and would have misled the next reader into thinking the fallback carried the
> feature. The conclusion stands, the effort estimate was wrong, and the plan's Complexity Tracking
> entry has been amended rather than quietly dropped.

**Decision**: Treat the file-system watch as **unreliable on Android 11 and higher** and rely on the
media-database observer as the real-time trigger — which the code already does. Keep the file-system
watch as a best-effort secondary on every version.

**Rationale**: The watch is built on the kernel's inotify facility over raw paths. Android 11
restored raw-path *reads* through a user-space filesystem shim; it never documented inotify
delivery for shared storage, and the platform's own storage guidance points apps at the media
database for change notification on those directories. The current watch works because all-files
access exempts the app from scoped storage altogether — remove the permission and the mechanism
rests on undocumented behaviour at the exact moment the app's protection depends on it. Principle
I names a silently-ended protection as the failure mode to design against, so an undocumented
mechanism is not an acceptable load-bearing component.

A media-database observer is the documented replacement, is version-agnostic from Android 8
upward, and hands back the two values R-003 needs (relative path, display name) plus the media URI
itself. It also observes `Download`, so detection coverage there is unaffected by that folder
being ungrantable.

**Scope note**: this changes the *trigger source* for real-time detection. It changes no
detection *logic* — the classifier, the two-stage pipeline, the thresholds, the duplicate-hash
suppression and the alert path are all downstream of the trigger and are not touched. The spec
states detection logic is unchanged; that remains true, and the distinction is recorded in the
plan's Complexity Tracking because the spec did not anticipate a trigger-source change.

**Verification gate**: quickstart scenario 2 measures observer-to-blur latency against the current
build's watch-to-blur latency, because SC-003 forbids a regression there.

**Alternatives considered**: keeping only the file-system watch and hoping inotify fires —
rejected above. Polling the media database on a timer — rejected: it trades a bounded latency for
battery drain on a device that already runs a continuous foreground service.

---

## R-006 — How is a partial photo grant detected and rejected?

**Decision**: Treat "selected photos" as **not granted**. Do not declare the user-selected-visual
permission.

**Rationale**: From Android 14, the photo permission prompt offers the user a middle option that
grants access to a hand-picked set of images rather than the library. For an app whose entire job
is to see images that *arrive later*, that grant is worthless: a photo the child receives tomorrow
was not in yesterday's selection. Declaring the user-selected-visual permission would make the
app's state machine aware of partial grants in order to… reject them, so it is simpler and
honester not to declare it and to treat anything short of a full grant as ungranted, which FR-005
already requires.

Detection is therefore a positive check for the full narrow permission, not an attempt to
enumerate what was selected.

**Verification gate**: Android 14+ behaviour when an app that does *not* declare the
user-selected-visual permission receives a partial selection is the one point the documentation
consulted did not settle — the platform may report the narrow permission as granted for
backward compatibility. Quickstart scenario 3 pins this down on a real Android 14+ device, and the
grant check must be written so that a false "granted" is caught by a follow-up capability probe
rather than trusted.

---

## R-007 — Does this need a new library?

**Decision**: **No new dependency.** Use the platform document-contract and content-resolver APIs
directly.

**Rationale**: The obvious choice is the support library's document-file wrapper, but everything
this feature needs — constructing a child document URI from a tree (R-003), opening an output
stream, deleting a document, persisting a grant — is on the framework contract class that ships
with the platform. The wrapper's value is its directory-walking convenience, which R-003
deliberately avoids. The constitution carries a locked dependency table and requires explicit
rationale to change it; adding a library to avoid writing two short helper functions does not
clear that bar.

**Alternatives considered**: the document-file support library (rejected as above); a
third-party scoped-storage wrapper (rejected outright — an unaudited dependency inside the write
path of a child-safety app).

---

## R-008 — Can a write fail halfway and destroy a child's photo?

**Decision**: Yes, in principle — and the existing flow already contains the remedy. Keep the
backup-before-write ordering, make the restore-on-failure path explicit, and verify the written
bytes before reporting success.

**Rationale**: Opening a document for writing truncates it first, so a failure mid-stream (storage
full, grant revoked between check and write, process death) leaves a truncated file where a photo
was. FR-018 forbids that outcome. The current flow already takes the app-private backup copy
*before* it replaces the gallery file, so a verified recovery source exists at the moment of risk
— what is missing is a failure branch that uses it. The design therefore makes the sequence
explicit: back up, verify the backup, write, verify the written result, and on any failure restore
from the backup and report the failure to the parent rather than reporting the blur as applied
(FR-016).

**Verification gate**: quickstart scenario 6 induces a mid-write failure and asserts the original
image is intact afterwards. This is the one scenario where a wrong implementation destroys user
data, so it is a merge gate, not an optional check.

**Alternatives considered**: write a temporary document in the same folder and rename it over the
original — the platform's rename does not overwrite an existing document, so the original must be
deleted first, which widens rather than narrows the window where the photo exists nowhere but the
backup.

---

## R-009 — Does the gallery still refresh after a folder-grant write?

**Decision**: Keep the existing media-scan refresh, and verify it. Fall back to a media-database
update on the known row if it proves ineffective.

**Rationale**: The current code asks the media scanner to rescan the written path, which is a read
operation and should remain permitted under the narrow photo permission (R-004). But the write now
arrives through a different route than before, and whether the database row's size and modified
time are refreshed promptly is observable behaviour rather than documented contract. Since the
parent's acceptance of this feature is literally "the gallery shows a blurred picture", an
unrefreshed thumbnail reads as a total failure even when the bytes on disk are correct.

**Verification gate**: quickstart scenario 2 checks the gallery thumbnail, not just the file
bytes. If the rescan proves unreliable, the fallback is a direct update of the known media-database
row, whose identifier the observer in R-005 already supplies — no new permission either way.

---

## Carried Forward

| Item | Kind | Where it is settled |
| --- | --- | --- |
| R-004 read path on the narrow permission | Device verification | quickstart scenario 1 — **gate** |
| R-005 observer latency vs. today's watch | Device verification | quickstart scenario 2 — **gate** |
| R-006 partial-grant reporting on Android 14+ | Device verification | quickstart scenario 3 |
| R-008 mid-write failure leaves original intact | Device verification | quickstart scenario 6 — **gate** |
| R-009 gallery thumbnail refresh | Device verification | quickstart scenario 2 — **gate** |
| Root-level `Screenshots` grantability | Runtime handling | design handles absence; never blocks setup |
| Messaging-app media folders | Out of scope | future feature, noted in R-001 |

No `NEEDS CLARIFICATION` remains in the Technical Context.

## Sources

- [Access documents and other files using the Storage Access Framework](https://developer.android.com/training/data-storage/shared/documents-files)
- [Access media files from shared storage](https://developer.android.com/training/data-storage/shared/media)
- [Storage updates in Android 11](https://developer.android.com/about/versions/11/privacy/storage)
- [Google Play — Use of the All files access (MANAGE_EXTERNAL_STORAGE) permission](https://support.google.com/googleplay/android-developer/answer/10467955)
