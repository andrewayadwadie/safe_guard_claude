# Feature Specification: Replace All-Files Access with Parent-Granted Folder Access

**Feature Branch**: `016-remove-all-files-access`

**Created**: 2026-10-07

**Status**: Draft

**Input**: User description: "Replace MANAGE_EXTERNAL_STORAGE with Storage Access Framework persisted tree grants. Remove the android.permission.MANAGE_EXTERNAL_STORAGE declaration from the manifest (Google Play all-files-access policy blocker: parental control is not a permitted use). Migrate every shared-storage write in ImageBlurManager — replaceWithBlurred, restoreOriginal, deleteImage — from raw java.io.File paths to DocumentFile writes over SAF tree URIs the parent grants once during setup via ACTION_OPEN_DOCUMENT_TREE (DCIM, Pictures, Screenshots, WhatsApp Images). Persist the grants across reboots with takePersistableUriPermission. Keep detection, classification, app-private backup, parent alerts and parent review working unchanged on READ_MEDIA_IMAGES alone. Replace the isExternalStorageManager() permission gate in PermissionsSetupViewModel with a check on READ_MEDIA_IMAGES plus the set of granted folder trees. Downloads cannot be granted via SAF on Android 11+, so images there stay detect-and-alert only. Add a release-blocking merged-manifest build guard so MANAGE_EXTERNAL_STORAGE can never be reintroduced, mirroring the guard added in feature 015 for FOREGROUND_SERVICE_DATA_SYNC."

## Why This Feature Exists

The store review is blocked on an undeclared all-files-access permission. The store's permitted-use
list for that permission covers file managers, backup and restore tools, anti-virus apps, document
managers, on-device file search, disk encryption and device-to-device migration. **Parental control
is not on that list.** A justification form plus a demonstration video may be accepted, but the
app's honest answer to the form's mandatory "why can't you use the Storage Access Framework"
question is *it can* — which is the answer that gets the declaration rejected and the release
blocked again on every subsequent submission.

This feature removes the dependency rather than arguing for it.

## Audit Result (Pre-Condition for FR-001)

The feature description assumed the only thing standing between the app and a narrower permission
was the image-write path. A full audit of the current working tree was performed before writing
this spec, and two findings materially change the shape of the work.

| Item | Expected by request | Actual current state |
| --- | --- | --- |
| All-files access declared | yes, to be removed | **Yes** (`app/src/main/AndroidManifest.xml:50`), and present in the release merged manifest (`app/build/intermediates/merged_manifest/release/.../AndroidManifest.xml:60`) |
| Narrow photo-read permission declared | yes | **Yes** (`app/src/main/AndroidManifest.xml:41`) |
| Narrow photo-read permission **requested at runtime** on Android 11+ | assumed yes | **NO — never requested.** The setup flow routes Android 11+ straight to the all-files-access system screen and only asks for the legacy read permission below Android 11 (`PermissionsSetupScreen.kt:95-120`) |
| Legacy shared-storage **write** permission declared | assumed present for old devices | **NO.** Neither the legacy write permission nor a legacy-storage opt-in exists anywhere in the manifest |
| Image enumeration for the periodic scan | raw directory walk | Media database query, then raw-path read of each result (`ImageScanWorker.kt:124`) |
| Real-time detection | raw-path folder watching | Raw-path folder watching (`MediaFileObserver.kt:306-333`) |
| Folders watched | DCIM, Pictures, Screenshots, WhatsApp | DCIM, DCIM/Camera, DCIM/Screenshots, **Download**, Pictures, Pictures/Screenshots, and a root-level Screenshots folder when present. **No messaging-app folder is watched at all.** |
| Shared-storage writes | three call sites | Three: in-place blur replacement (`ImageBlurManager.kt:753`), restore of the original (`ImageBlurManager.kt:275`), delete from device (`ImageBlurManager.kt:319`) |
| Blur gated behind a parent setting | assumed | **Yes — "Maximum Protection", default OFF** (`PreferencesManager.kt:162`). With it off the app only takes an app-private backup copy and alerts; it never touches the gallery file |

### Finding 1 — removing the permission today would break detection, not just blurring

Because the narrow photo-read permission is **never requested at runtime on Android 11 and above**,
every image read on those versions — the real-time folder watch, the periodic scan, and the
classifier itself — works *only* because all-files access was granted. Deleting the permission
without adding a runtime request for the narrow one would silently end image protection on the
overwhelming majority of the installed base. This feature must therefore add the runtime request
that should already have been there. It is not optional cleanup; it is the load-bearing half.

### Finding 2 — in-place blurring has never worked on Android 8 through 10

All-files access exists only from Android 11. On Android 8, 9 and 10 the app has no shared-storage
write permission of any kind, so the in-place blur, the restore and the delete-from-device have
always failed on those versions — silently, as a logged error behind a parent-facing success path.
The minimum supported version is Android 8. Parent-granted folder access works from Android 5, so
this feature **fixes** those three actions on Android 8–10 rather than regressing them.

### What this feature therefore delivers

1. The runtime photo-read request that is currently missing, so detection survives the removal.
2. A one-time, parent-performed folder-access grant that carries the three write actions.
3. Removal of the all-files-access declaration, proven absent from the shipped bundle.
4. A release-blocking build guard so it cannot return, mirroring the guard from feature 015.

No change to detection logic, classification thresholds, the model, the alert contract, or the
backend. None may be invented.

## Clarifications

### Session 2026-10-07

- Q: Accept that images in the Download folder can no longer be blurred in place? → A: **Yes.** The
  platform does not allow the Download folder to be granted as a folder-access target from Android
  11 onward. Those images stay detect-plus-backup-plus-alert. The parent is told this in-product
  rather than left to discover it. Prompting per image was rejected: it shows the child a system
  dialog they can decline, which defeats the enforcement.
- Q: Should a partial photo grant ("select photos" rather than the whole library) count as granted?
  → A: **No.** Partial access cannot see images the child receives later, which is the entire point
  of the feature. Partial counts as not granted and re-prompts, with the protection status showing
  degraded.
- Q: What covers the regression risk of the permission returning? → A: A build-time check wired
  into the release build that reads the final merged manifest and fails the build, mirroring the
  existing foreground-service guard from feature 015 and the certificate-pin guard from 013.

## Data Flow & Permission Justification

The project's governing principles require every spec touching monitored data or operating-system
permissions to state its data flow and justify its permissions before planning begins.

**Data flow: narrowed, never widened.** Image analysis continues to happen entirely on the child's
device. The app continues to store a private backup copy of a flagged image inside its own private
storage so the parent can review it on the device, and continues to send the parent only a category
label, a confidence score and alert metadata. **No raw image bytes leave the device, before or
after this change.** Retention of backup copies is unchanged. What changes is that the app's reach
over the child's storage shrinks from *every file on the device* to *the specific picture folders
the parent chose*, which is strictly less collection for the same enforcement.

**Permission justification.**

| Permission | Disposition | Justification |
| --- | --- | --- |
| All-files access | **Removed** | Does not map to a permitted store use for this app category, and no longer maps to a needed capability once folder access carries the writes. The governing rule is explicit that an unmapped permission must be removed. |
| Narrow photo-read | Retained, and **actually requested at runtime for the first time** | Maps to saved-image explicit-content detection — reading the images the periodic scan and the folder watch discover, so they can be classified on-device. |
| Legacy shared-storage read (old versions only) | Retained unchanged | Same mapping, on versions predating the narrow permission. |
| Parent-granted folder access | **Added** | Maps to the Maximum Protection enforcement action: replacing a flagged image with a blurred version, restoring it when the parent approves, deleting it when the parent rejects. Granted by the parent through the system folder picker, revocable at any time, scoped to the folders they chose. |

## User Scenarios & Testing *(mandatory)*

### User Story 1 - The app ships and keeps protecting without all-files access (Priority: P1)

A parent installs the app on a child's device from the store. During setup the app asks for access
to the device's photos through the standard system prompt. Nothing asks for access to every file on
the device. From then on, every explicit image saved to a watched folder is still detected,
classified on the device, copied to the app's private backup, and reported to the parent as an
alert — exactly as before.

**Why this priority**: This is the release unblocker and it is self-contained. Shipped alone it
removes the store blocker while keeping the product's default behaviour (detection and alerting,
with the in-place blur setting off by default) completely intact. It is a viable release on its own.

**Independent Test**: Install a build with the all-files-access declaration removed and the runtime
photo request added, grant photos, save a known explicit test image to each watched folder, and
confirm one parent alert per image and one private backup per image, on each supported Android
version.

**Acceptance Scenarios**:

1. **Given** a freshly installed child device on Android 11 or newer, **When** the parent completes
   setup, **Then** the app requests access to photos through the standard system prompt and never
   routes the parent to an all-files-access settings screen.
2. **Given** photo access is granted and the in-place blur setting is off, **When** an explicit
   image is saved to a watched folder, **Then** the image is detected, backed up privately, and one
   alert reaches the parent, and the gallery file is left untouched.
3. **Given** the parent denies photo access, **When** setup continues, **Then** the setup screen
   shows image protection as not active with a plain-language reason and a way to retry, and no
   other protection is affected.
4. **Given** a child device on Android 14 or newer, **When** the parent chooses "select photos"
   instead of full access, **Then** the app treats image protection as not active, explains that
   partial access cannot see images that arrive later, and offers to ask again.

---

### User Story 2 - A one-time folder grant restores in-place blurring (Priority: P2)

A parent who wants the stronger setting turns on Maximum Protection. The app explains that it needs
permission to modify the child's picture folders and walks the parent through picking them in the
system folder picker — one picker per folder, each pre-pointed at the right place. Once granted, a
flagged image in those folders is replaced with a blurred version automatically, with no further
prompts, and the permission survives reboots and app updates.

**Why this priority**: This restores the strongest enforcement behaviour the app has today, and on
Android 8–10 it delivers it for the first time. It depends on Story 1 shipping but not the reverse.

**Independent Test**: With Story 1 in place, turn on Maximum Protection, complete the folder grant
for the camera and pictures folders, save an explicit test image to each, and confirm the gallery
shows a blurred version within the same time window as the current build — then reboot the device
and repeat without any new prompt.

**Acceptance Scenarios**:

1. **Given** Maximum Protection is being turned on with no folder access yet, **When** the parent
   confirms, **Then** the app explains what will change and opens the system folder picker for each
   needed folder in turn.
2. **Given** folder access has been granted for the camera folder, **When** an explicit image is
   saved there, **Then** the gallery copy is replaced with a blurred version and no prompt is shown
   to the child.
3. **Given** folder access has been granted, **When** the device reboots or the app is updated,
   **Then** blurring continues with no new grant prompt.
4. **Given** an explicit image is saved to a folder the parent did not grant, **When** it is
   detected, **Then** the app still backs it up and alerts the parent, records that the in-place
   blur could not be applied, and the alert tells the parent why.
5. **Given** Maximum Protection is on and the parent later revokes folder access in system settings,
   **When** the next flagged image is detected, **Then** detection and alerting continue, the
   protection status shows blurring as inactive with the reason, and the parent is prompted to
   restore access.
6. **Given** a child device on Android 8, 9 or 10, **When** folder access is granted and an explicit
   image is flagged, **Then** the in-place blur succeeds — behaviour that silently failed before.

---

### User Story 3 - Parent review actions work through the granted folders (Priority: P2)

A parent reviewing a flagged image on the child's device decides: approve it, and the original is
put back in the gallery unblurred; or reject it, and both the blurred gallery copy and the private
backup are removed from the device.

**Why this priority**: Review is the promise the blur makes to the parent. A blur the parent cannot
undo is a bug, not protection. Same dependency as Story 2.

**Independent Test**: Blur a test image via Story 2, then exercise approve on one image and reject
on another, confirming the gallery result and the backup state for each.

**Acceptance Scenarios**:

1. **Given** a blurred image and granted folder access, **When** the parent approves it, **Then**
   the original image is restored in the gallery and the private backup is cleared.
2. **Given** a blurred image and granted folder access, **When** the parent rejects it, **Then**
   the gallery copy and the private backup are both deleted.
3. **Given** a blurred image whose folder access has since been revoked, **When** the parent tries
   to approve or reject, **Then** the app says plainly that it needs folder access again and offers
   to re-request it, and no backup is lost in the meantime.
4. **Given** an image blurred by a previous version of the app that used all-files access, **When**
   the parent grants folder access after updating, **Then** that image can still be restored or
   deleted.

---

### User Story 4 - The permission cannot silently return (Priority: P3)

A developer adds a dependency months from now that quietly contributes the all-files-access
permission into the final bundle. The release build fails with a message naming the permission, the
offending source and where to read about it, rather than the store rejecting the submission weeks
later.

**Why this priority**: The removal is worth exactly as much as its durability. Low priority only
because it protects a future release rather than enabling this one.

**Independent Test**: Reintroduce the permission in a scratch commit, run the release build, and
confirm it fails with an actionable message; remove it and confirm the build passes.

**Acceptance Scenarios**:

1. **Given** a release build, **When** the final merged manifest contains the all-files-access
   permission from any source, **Then** the build fails and the message names the permission and
   points at the written contract for the guard.
2. **Given** a release build whose merged manifest cannot be located, **When** the guard runs,
   **Then** the build fails rather than passing unverified.
3. **Given** a clean release build, **When** the guard runs, **Then** it passes and the shipped
   bundle contains no all-files-access declaration.

---

### Edge Cases

- An explicit image lands in the **Download folder**, which the platform forbids granting. Detection,
  private backup and the parent alert all still happen; the in-place blur does not, and the parent is
  told so once rather than on every alert.
- An explicit image lands in a folder nobody watches (a messaging app's own media folder, a
  downloads subfolder, a third-party camera folder). It is neither detected nor blurred — unchanged
  from today, and explicitly out of scope here.
- A watched folder does not exist at setup time and is created later (first screenshot taken after
  setup). The grant step must not silently skip it forever.
- The parent grants the wrong folder in the picker, or cancels the picker partway through a
  multi-folder flow.
- The parent grants a folder on a removable card or a secondary storage volume.
- The child revokes the photo permission or the folder access from system settings while protection
  is running.
- The flagged file is renamed, moved or deleted by the gallery app between detection and the write.
- The device runs out of storage mid-write, leaving a truncated gallery file where a photo was.
- The child clears the app's data, destroying the private backups while blurred gallery copies remain
  on the device with no originals to restore.
- The app is reinstalled: folder grants do not survive uninstall and must be re-requested.
- The device has multiple users or a work profile.
- Two flagged images are processed at once and both need the same folder grant.

## Requirements *(mandatory)*

### Functional Requirements

**Removing the permission**

- **FR-001**: The app MUST NOT declare all-files access, and the permission MUST be absent from the
  final merged manifest of the release bundle, not merely from the hand-written manifest.
- **FR-002**: Removing it MUST NOT require any change to the backend contract, the alert payload,
  the classification thresholds, or the bundled model.

**Keeping detection alive (the load-bearing half, per Audit Finding 1)**

- **FR-003**: The app MUST request the narrow photo-read permission at runtime on every supported
  Android version, including Android 11 and above where it is currently never requested.
- **FR-004**: Real-time folder watching, the periodic scan, on-device classification, the private
  backup copy and the parent alert MUST all function with only the narrow photo-read permission
  granted and no folder access at all.
- **FR-005**: A partial photo grant (choosing individual photos rather than the whole library) MUST
  be treated as not granted: the app MUST explain why it is insufficient, show image protection as
  inactive, and offer to ask again.
- **FR-006**: The permission check that currently asks whether the app is an all-files manager MUST
  be replaced by a check on the narrow photo-read permission plus the set of currently held folder
  grants, and that check MUST remain the single source of truth for the image-protection status
  shown to the parent.

**Parent-granted folder access**

- **FR-007**: The app MUST let the parent grant access to each picture folder it watches, through
  the system folder picker, one folder at a time, with each request explained in plain language
  before the picker opens.
- **FR-008**: The folders offered MUST correspond to the folders the app actually watches: the
  camera folder tree, the pictures folder tree, and a device-specific screenshots folder where one
  exists outside those trees.
- **FR-009**: Granted access MUST persist across app restart, device reboot and app update without
  re-prompting the parent.
- **FR-010**: The app MUST record which folders are granted and MUST re-offer any watched folder
  that is missing a grant, including a folder that did not exist when the parent first went through
  setup.
- **FR-011**: If the parent cancels the picker, picks an unexpected folder, or grants only some of
  the folders, the app MUST accept the partial result, keep working for the folders it did get, and
  make the remaining ones re-requestable without restarting setup.

**The three write actions**

- **FR-012**: Replacing a flagged image with a blurred version MUST go through granted folder access
  and MUST NOT depend on all-files access.
- **FR-013**: Restoring an approved original MUST go through granted folder access.
- **FR-014**: Deleting a rejected image from the device MUST go through granted folder access.
- **FR-015**: These three actions MUST work on every supported Android version from Android 8
  upward, fixing the silent failure documented in Audit Finding 2.
- **FR-016**: When a flagged image sits in a folder with no grant — including the Download folder,
  which the platform does not allow to be granted — the app MUST still back it up privately and
  alert the parent, MUST record that the in-place blur was not applied, and MUST NOT report success
  for an action it did not perform.
- **FR-017**: The app MUST NOT show the child a per-image system prompt in order to modify a flagged
  image. Enforcement the child can decline is not enforcement.
- **FR-018**: A failed write MUST NOT leave a truncated or corrupt file where the child's photo was;
  on failure the original MUST remain intact and the failure MUST be surfaced to the parent.

**Parent-facing state**

- **FR-019**: The parent MUST be able to see whether in-place blurring is currently active, and when
  it is not, the reason in plain language and the action that fixes it.
- **FR-020**: Turning on Maximum Protection without the needed folder access MUST explain the
  dependency and start the grant flow rather than enabling a setting that silently does nothing.
- **FR-021**: Revoked folder access MUST be detected and surfaced; detection and alerting MUST
  continue uninterrupted while blurring is inactive.
- **FR-022**: The parent MUST be told once, in-product, that images saved to the Download folder
  cannot be blurred in place on modern Android — not repeated on every alert.

**Upgrade path**

- **FR-023**: On an existing install that already holds all-files access, the update MUST keep every
  private backup and MUST let the parent restore or delete images that were blurred by the previous
  version, once folder access is granted.
- **FR-024**: The update MUST NOT leave a child device in a state where protection appears active
  but no image is being read, which is the silent-failure mode the governing principles exist to
  prevent.

**Durability**

- **FR-025**: A release build MUST fail when the all-files-access permission is present in the final
  merged manifest from any source, with a message naming the permission and pointing at the written
  guard contract.
- **FR-026**: The guard MUST fail rather than pass if it cannot read the merged manifest.
- **FR-027**: The written guard contract MUST live in this feature's folder, mirroring the
  foreground-service guard contract from feature 015.
- **FR-028**: The release checklist MUST drop the all-files-access justification text, since the
  declaration is no longer made.

### Key Entities

- **Folder Access Grant**: A durable, parent-given permission to read and modify the contents of one
  chosen picture folder. Has a target folder, a granted-or-not state, and survives reboot and
  update but not uninstall. Revocable by the device owner at any time outside the app.
- **Watched Folder**: A location the app monitors for newly saved images. May or may not have a
  Folder Access Grant; may or may not be grantable at all (the Download folder is not).
- **Flagged Image Backup**: The app-private copy of an image classified as explicit, plus its
  metadata — which folder it came from, what it was classified as, how confident the classifier was,
  whether the gallery copy was blurred, and whether the parent has reviewed it.
- **Image Protection Status**: What the parent sees — whether images are being read at all, whether
  in-place blurring is active, and the reason and remedy when either is not.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: The shipped release bundle contains zero all-files-access declarations, verified
  against the final merged manifest rather than the hand-written one. *(Merge gate.)*
- **SC-002**: Detection coverage does not regress: across a fixed set of test images placed in every
  watched folder, the number of parent alerts produced after the change equals the number produced
  before it, on every supported Android version tested. *(Merge gate.)*
- **SC-003**: With folder access granted, 100% of flagged images in granted folders are blurred in
  place, and the time from the image being saved to the gallery showing it blurred is no longer than
  the current build's. *(Merge gate.)*
- **SC-004**: On Android 8, 9 and 10, in-place blurring, restore and delete succeed for granted
  folders — a change from the 0% success rate measured before this feature.
- **SC-005**: A parent completes the entire folder-grant flow in under 90 seconds, with no more than
  three taps per folder, on first attempt without assistance.
- **SC-006**: Folder access survives at least three device reboots and one app update with zero
  re-prompts.
- **SC-007**: Every write the app cannot perform produces a parent-visible reason; zero flagged
  images are reported to the parent as blurred when they were not.
- **SC-008**: Zero crashes and zero app-not-responding reports across the verification matrix, with
  photo access and folder access each granted, denied, partially granted and revoked mid-run.
- **SC-009**: Reintroducing the permission in a scratch commit fails the release build; removing it
  again lets the build pass. *(Merge gate.)*
- **SC-010**: Network capture during a full detect-blur-review cycle shows no raw image bytes leaving
  the device — only category, confidence and alert metadata.
- **SC-011**: The store release review proceeds with no all-files-access declaration form presented.
  *(Post-merge confirmation by the developer; non-blocking, since it depends on the store's own
  review timing.)*

## Assumptions

- The platform allows the camera folder tree and the pictures folder tree to be granted through the
  system folder picker from Android 11 onward, and forbids granting the storage root and the Download
  folder. The exact grantability of a device-specific screenshots folder outside those trees, and of
  any messaging-app media folder, is to be confirmed during planning research before those folders
  are offered to the parent.
- The watched-folder set stays exactly as it is today. Adding messaging-app folders is a separate
  feature; the original request mentioned one, but the audit shows none is watched today, so adding
  one here would be new monitoring scope smuggled into a permission-removal feature.
- Maximum Protection stays off by default, so a device that never grants folder access behaves
  exactly as a default install does today.
- No backend change, no alert-contract change, no change to the classifier, its thresholds or the
  bundled model.
- The parent is physically present with the child's device during setup, which the existing setup
  flow already assumes for the other special permissions.
- Private backup copies keep their current location and retention behaviour.
- Verification is manual against a written matrix recorded in this feature's folder, in the same
  form feature 015 used, plus automated coverage for the grant-state logic and the write-failure
  paths, which are new decision logic rather than untestable platform glue.

## Out of Scope

- Blurring images in the Download folder in place, which the platform does not permit without the
  removed permission.
- Per-image system prompts as a fallback write path — rejected in Clarifications.
- Watching any folder not watched today, including messaging-app media folders.
- Live screen capture or live frame scanning, which the governing principles forbid without an
  amendment.
- Removable storage and secondary volumes.
- Any change to the other declared permissions or their store declarations.
- Parent-app-side changes beyond displaying the image-protection status.
