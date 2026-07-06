# Data Model: Child Pairing Gate for Monitoring

**Feature**: 004-child-pairing-gate | **Date**: 2026-07-06

## Entities

### LinkedParent (new DTO — `data/model/Models.kt`)

Represents one parent account linked to the child account via family link. Parsed from `GET /family/parents`. **All fields nullable by design** — the gate only inspects list emptiness, so a schema mismatch degrades to nulls, never a parse crash.

| Field | Type | JSON name | Notes |
|---|---|---|---|
| `id` | `Int?` | `id` | Link or record id — TODO(backend): confirm |
| `parentId` | `Int?` | `parent_id` | TODO(backend): confirm |
| `parentEmail` | `String?` | `parent_email` | TODO(backend): confirm |
| `parentName` | `String?` | `parent_name` | TODO(backend): confirm |
| `createdAt` | `Date?` | `created_at` | TODO(backend): confirm |

Relationships: conceptually the inverse of the existing `FamilyLink` (parent-side view of children). No Room persistence — network-only DTO.

### hasLinkedParent (new persisted flag — `PreferencesManager`)

| Property | Value |
|---|---|
| Key | `Constants.KEY_HAS_LINKED_PARENT` = `"has_linked_parent"` |
| Type | `Boolean` |
| Default | `false` (never-confirmed → gated) |
| Storage | `EncryptedSharedPreferences` (AES256, at-rest encrypted) |
| Cleared by | existing `clearAll()` on logout (no code change) |
| Written by | `FamilyRepository.refreshLinkedParentStatus()` ONLY |
| Read by | `MonitoringService.onStartCommand` gate; `refreshLinkedParentStatus()` fallback |

## State Transitions

```text
                    ┌────────────────────────────────────────────┐
                    │  hasLinkedParent = false  (GATED)           │
                    │  fresh install / post-logout default        │
                    └────────────┬───────────────────────────────┘
                                 │ refresh: 200 + non-empty list
                                 ▼
                    ┌────────────────────────────────────────────┐
                    │  hasLinkedParent = true  (MONITORING)       │
                    └────────────┬───────────────────────────────┘
                                 │ refresh: 200 + EMPTY list (confirmed unpair)
                                 ▼
                    hasLinkedParent = false (GATED on next start)

  Network error / no connectivity: NO transition (flag frozen) — from either state.
  logout → clearAll(): flag removed → reads as false (GATED).
```

Invariants:

- **I1**: Only a successful (2xx) response mutates the flag. Errors freeze state (FR-005, FR-006).
- **I2**: Flag defaults false — absence of evidence = gated (FR-009).
- **I3**: The refresh is triggered only from the unpaired branch of the gate; a true flag is never re-validated at service start (locked design — see plan.md Design Notes).
- **I4**: Enforcement loop + image monitoring start iff (child role) ∧ (flag true) ∧ (loop not already running) — evaluated after the existing consent gate (FR-002, FR-003, FR-007).
