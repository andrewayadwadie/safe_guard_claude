# Contract: Media Write Access Seam

**Feature**: `016-remove-all-files-access` | **Satisfies**: FR-006 … FR-018, FR-021, FR-023
**Implements**: research [R-003](../research.md), [R-007](../research.md), [R-008](../research.md)

The one new component. It owns every interaction with parent-granted folder access and is the only
place in the app that writes to or deletes from shared storage. `ImageBlurManager` keeps its
reads on the existing file path and routes its three writes here.

It is a platform adapter, not a repository — enforcement rule 9's `NetworkResult<T>` requirement
does not reach it, and it returns a sealed result in the same style `ImageBlurManager.BlurResult`
already uses so that file keeps one idiom rather than two.

---

## Capability surface

### C-1 — Report grant state for a target

Given a watched location, report whether the app currently holds folder access covering it.

- MUST read the platform's persisted-permission list every call. MUST NOT cache, and MUST NOT
  mirror the list into app storage — see [data-model.md](../data-model.md) entity 1 for why a stale
  "granted" is the specific bug FR-016 forbids.
- A grant whose tree is an **ancestor** of the target counts (a `DCIM` grant covers
  `DCIM/Camera` and `DCIM/Screenshots`).
- A grant whose tree is a **descendant** does not cover the parent (a `DCIM/Camera` grant does not
  cover `DCIM/Screenshots`), and MUST be reported as the partial win it is, not discarded (FR-011).
- A tree that resolves to the storage root MUST be treated as no grant rather than as a universal
  one; the platform never issues such a grant, so one appearing means something is wrong.
- The `Download` folder MUST report `UNGRANTABLE`, distinct from `NOT_REQUESTED`, so the caller can
  tell "ask the parent" apart from "never ask" (FR-016, FR-022).

### C-2 — Build the picker request for a target

Produce the request that opens the system folder picker aimed at a target, pre-pointed at the right
folder so the parent does not have to navigate.

- MUST pre-point the picker at the target folder.
- MUST NOT be callable in a way that silently picks a folder without the parent acting.
- The caller launches it from an activity result contract; this seam does not launch anything, which
  keeps it usable from the worker and the service where no activity exists.

### C-3 — Persist a grant returned by the picker

- MUST take the persistable form of the grant so it survives process death, reboot and app update
  (FR-009).
- MUST accept a tree that is not the folder that was asked for — the parent may navigate elsewhere —
  and report which target, if any, the returned tree actually covers (FR-011).
- MUST be idempotent: re-granting an already-held tree is not an error.

### C-4 — Resolve an image to a writable document

Given an image's path relative to shared storage, produce the document reference to write to.

- MUST construct the document identifier arithmetically from the covering grant's tree and the
  relative path (R-003). MUST NOT enumerate or walk the tree: a camera folder with thousands of
  images would make every blur proportionally slower for no benefit.
- MUST handle names and folder segments needing escaping.
- MUST fail cleanly, not throw, when no grant covers the path.
- MUST NOT assume the document exists — the file may have been moved, renamed or deleted between
  detection and the write.

### C-5 — Replace a document's bytes

- MUST write from a caller-supplied byte source and MUST verify the result before reporting success.
  "The stream closed without throwing" is not verification; FR-016 forbids reporting a blur that did
  not happen.
- MUST NOT require an activity, a foreground task or any user interaction (FR-017). This is the
  constraint that disqualified `MANAGE_MEDIA` in R-002.
- MUST report a distinguishable failure for: no grant, grant revoked between check and write,
  document missing, out of space, and verification mismatch. The caller turns these into the
  parent-facing reason recorded on the backup (FR-019).

### C-6 — Delete a document

- Same preconditions and failure reporting as C-5.
- A document that is already gone MUST report success, not failure — the desired end state holds.

---

## The write ordering FR-018 depends on

The one sequence where a wrong implementation destroys a child's photo. Opening a document for
writing truncates it first, so a failure mid-stream leaves a truncated file where a picture was.

```text
1. Take the app-private backup copy of the original        (existing behaviour)
2. VERIFY the backup is complete and readable              ← must be explicit, not assumed
3. Resolve the document                            (C-4)
4. Write the blurred bytes                         (C-5)
5. VERIFY the written result                       (C-5)
6a. On success → mark the backup Blurred = true
6b. On ANY failure in 3-5 → restore the original from the backup,
    record the not-blurred reason, report failure to the caller
```

Steps 1 and 2 happen **before** step 4 so a verified recovery source exists at the moment of risk.
Step 1 already exists in `ImageBlurManager`; steps 2, 5 and 6b are what this feature adds.

The order is not negotiable and is not an implementation detail: it is the difference between a
failed blur and a destroyed photo. Quickstart scenario 6 is its merge gate.

A temporary-document-then-rename approach was considered and rejected in R-008 — the platform's
rename does not overwrite, so the original must be deleted first, widening the window in which the
photo exists nowhere but the backup.

---

## What this seam must not do

- **Must not hold state.** No cached grant list, no cached document references, no remembered
  failures. Everything is derived per call.
- **Must not read image bytes for classification.** Reads stay on the existing file path under the
  narrow photo permission (R-004). Routing reads through here too would be tidier-looking and would
  break detection in every folder the parent has not granted — including `Download`, where
  detection must keep working.
- **Must not touch the media database's contents.** Refreshing the gallery after a write stays
  where it is in `ImageBlurManager` (R-009).
- **Must not request any permission.** It reports state and performs operations; the setup flow
  owns asking.
- **Must not log a file path at info level or above.** Paths of flagged images are sensitive;
  existing logging discipline in `ImageBlurManager` applies here.

---

## Testability

The project has **no Robolectric**, so none of these behaviours may be verified against a real
framework class in a unit test. The seam is therefore split:

| Piece | Testable where |
| --- | --- |
| Relative path → document identifier, including escaping and ancestor matching (C-1, C-4) | Pure Kotlin — `MediaWriteAccessDocumentIdTest` |
| Grant-state resolution over a supplied list of held trees: granted / missing / revoked / partial / root / ungrantable (C-1) | Pure Kotlin over an injected list — `MediaWriteAccessGrantStateTest` |
| The failure branch restores the original and records a reason (the FR-018 ordering) | `ImageBlurWriteFailureTest` with the seam faked via mockk |
| Actual document writes, picker behaviour, grant persistence across reboot | Device only — [quickstart.md](../quickstart.md) |

The grant-state logic MUST take the held-tree list as an input rather than reaching for the platform
itself, so the table above is achievable. That is the only design constraint the testing situation
imposes, and it costs one parameter.
