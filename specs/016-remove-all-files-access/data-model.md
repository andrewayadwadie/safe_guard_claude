# Phase 1 Data Model: Replace All-Files Access with Parent-Granted Folder Access

**Feature**: `016-remove-all-files-access` | **Date**: 2026-10-07 | **Plan**: [plan.md](./plan.md)

Four entities. Only one of them is new, and it deliberately holds **no persistent state of its
own** — the single most important decision in this model.

---

## 1. Folder Access Grant

A durable, parent-given permission to read and modify one picture folder tree.

| Field | Meaning | Notes |
| --- | --- | --- |
| Tree identity | Which folder was granted | A tree reference issued by the system picker, scoped to the chosen folder and everything under it |
| Target | Which watched location it covers | `CAMERA` (the `DCIM` tree) or `PICTURES` (the `Pictures` tree) or `ROOT_SCREENSHOTS` (device-specific, optional) |
| Held | Whether the app currently has it | **Derived, never stored** |

### The grant list is not persisted by the app

The platform already keeps the authoritative list of persisted permissions this app holds, and it
is the only copy that can go stale correctly: the device owner can revoke a grant from system
settings at any time, and an app-side mirror would then claim access the app does not have. Since
the consequence of a stale "granted" is a flagged image reported to the parent as blurred when it
was not — the exact thing FR-016 forbids — the design reads the platform list every time and keeps
nothing.

This removes an entire class of bug and makes the upgrade path trivial: there is no stored grant
state to migrate, so an install coming from the all-files-access version simply has no grants yet
and is offered the flow (FR-023).

### Validation rules

- A grant counts for a target only if its tree is that target's folder **or an ancestor of it**. A
  parent who picked `DCIM/Camera` instead of `DCIM` gets a grant that covers camera images and not
  screenshots; the flow must recognise the partial win rather than discard it (FR-011).
- A grant for the storage root is impossible, so a tree reference that resolves to the root is
  treated as not a grant at all rather than as a universal one.
- A grant on a non-primary volume is recognised but out of scope (spec Out of Scope); it is held
  without being relied on.

### State machine

```text
            ┌──────────────┐
            │ NOT_REQUESTED│  no grant for this target
            └──────┬───────┘
                   │ parent completes the picker for this target
                   ▼
            ┌──────────────┐
      ┌─────│   GRANTED    │  writes proceed, no prompts
      │     └──────┬───────┘
      │            │ owner revokes in system settings,
      │            │ or app data is cleared, or app is reinstalled
      │            ▼
      │     ┌──────────────┐
      │     │   REVOKED    │  detection + backup + alert continue;
      │     └──────┬───────┘  blur inactive, reason shown (FR-021)
      │            │ parent re-grants
      └────────────┘

            ┌──────────────┐
            │ UNGRANTABLE  │  the Download folder; terminal, never offered
            └──────────────┘
```

`REVOKED` and `NOT_REQUESTED` behave identically at the write site — both mean *no access* — and
differ only in the parent-facing copy: one says "grant access", the other says "access was turned
off".

---

## 2. Watched Folder

A location monitored for newly saved images. Existing concept; this feature gives it a grant
relationship and an explicit grantability fact.

| Watched location | Covered by grant target | Grantable | In-place blur |
| --- | --- | --- | --- |
| `DCIM` | `CAMERA` | yes | yes, once granted |
| `DCIM/Camera` | `CAMERA` | via ancestor | yes, once granted |
| `DCIM/Screenshots` | `CAMERA` | via ancestor | yes, once granted |
| `Pictures` | `PICTURES` | yes | yes, once granted |
| `Pictures/Screenshots` | `PICTURES` | via ancestor | yes, once granted |
| `Screenshots` (storage root level) | `ROOT_SCREENSHOTS` | yes, where the folder exists | yes, once granted |
| `Download` | — | **no — platform refuses** | **never** (FR-016, FR-022) |

Two grants cover six of the seven. The seventh is offered only when the folder exists on that
device and never blocks setup completion. `Download` is terminal: detection, backup and alerting
all work there, and the blur does not.

### Validation rules

- The set of watched folders is unchanged by this feature. Adding any is out of scope.
- A folder that does not exist at setup time must become offerable later, so grantability is
  evaluated when the flow runs, not cached from first run (FR-010).

---

## 3. Flagged Image Backup

The app-private copy of an image classified as explicit, plus its metadata. **Existing entity, existing
on-disk format, existing location, existing retention.** One field gains meaning and one is added.

| Field | Change | Meaning |
| --- | --- | --- |
| Backup identifier | unchanged | Derived from the original path, as today |
| Original path | unchanged | Where the image lives in shared storage |
| Category, confidence | unchanged | Classifier output |
| Blurred | **meaning sharpened** | Now means *the gallery copy was actually replaced*, never *we tried*. Set only after the write is verified |
| Not-blurred reason | **new, optional** | Why the in-place blur did not happen: no grant for that folder, the folder is ungrantable, the write failed, or the setting was off. Drives the parent-facing explanation (FR-016, FR-019) |

### Validation rules

- The backup copy is written and verified **before** any write to the gallery file. This ordering
  already exists and becomes load-bearing: it is the recovery source FR-018 depends on.
- `Blurred = true` with no verified write is invalid state. The write seam reports success only
  after the result is checked, and the manager sets the flag only on that success.
- A backup whose original has vanished stays restorable in the sense that the parent can still
  review and delete it; restore-in-place simply has nowhere to land, which is reported rather than
  silently succeeding.
- Existing backups written by the all-files-access version remain valid and readable with no
  migration. Their `Blurred` flag is trusted as-is; the new reason field is absent, which reads as
  "no reason recorded" rather than as an error (FR-023).

---

## 4. Image Protection Status

What the parent sees. Owned by the permission-setup view model's state, which is where the media
row already lives — **not** promoted into the five-layer protection helper (see the plan's
Structure Decision).

| Field | Values | Drives |
| --- | --- | --- |
| Reading images | `FULL` / `PARTIAL` / `DENIED` | Whether detection works at all |
| Blurring images | `ACTIVE` / `NO_GRANT` / `REVOKED` / `SETTING_OFF` | Whether enforcement works |
| Folders still to grant | list of targets | What the next picker should offer |
| Remedy | one action | What the parent taps to fix it |

### Validation rules

- `PARTIAL` reading is **not** a success state (FR-005): a hand-picked set of photos cannot contain
  an image the child receives tomorrow. It renders as not-protected with its own explanation, and
  the only remedy offered is to ask again for full access.
- `Blurring = ACTIVE` requires reading `FULL` **and** at least one grant **and** the Maximum
  Protection setting on. Any missing piece names itself as the reason.
- `SETTING_OFF` is not a problem to fix — it is the product's default (the setting ships off), so it
  must read as a choice, not a warning.
- Turning the setting on while no grant is held starts the grant flow rather than enabling
  something inert (FR-020).
- The `Download` notice is shown once and then suppressed by the single new preference flag
  (FR-022), not re-derived from this status on every alert.

---

## New Persistent State, In Full

One boolean: whether the parent has been shown the one-time `Download`-folder notice. It lives
beside the existing flags in the encrypted preference store. That is the complete list — folder
grants are read from the platform (entity 1), backups keep their current format (entity 3), and
protection status is derived per screen load (entity 4).

## What This Model Does Not Contain

- No image bytes anywhere outside the existing app-private backup directory.
- No new field capable of carrying image content, a path, or a classification off the device; the
  alert payload is untouched.
- No mirror of the platform's grant list, for the reason given in entity 1.
- No new table, no Room entity, no schema change, no migration.
