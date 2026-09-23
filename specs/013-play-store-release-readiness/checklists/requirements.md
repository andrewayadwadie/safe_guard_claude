# Specification Quality Checklist: Play Store Release Readiness

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-08-24
**Feature**: [spec.md](../spec.md)

## Content Quality

- [x] No implementation details (languages, frameworks, APIs)
- [x] Focused on user value and business needs
- [x] Written for non-technical stakeholders
- [x] All mandatory sections completed

## Requirement Completeness

- [x] No [NEEDS CLARIFICATION] markers remain
- [x] Requirements are testable and unambiguous
- [x] Success criteria are measurable
- [x] Success criteria are technology-agnostic (no implementation details)
- [x] All acceptance scenarios are defined
- [x] Edge cases are identified
- [x] Scope is clearly bounded
- [x] Dependencies and assumptions identified

## Feature Readiness

- [x] All functional requirements have clear acceptance criteria
- [x] User scenarios cover primary flows
- [x] Feature meets measurable outcomes defined in Success Criteria
- [x] No implementation details leak into specification

## Notes

**Validation run 2 (2026-08-24)** — 16 of 16 items pass. Spec is ready for `/speckit-plan`.

**Run 1 → run 2 delta**: three clarifications answered and folded into FR-009, FR-010, FR-035,
FR-036, and FR-040; the Open Questions section replaced with a Resolved Decisions table carrying
the rationale for each override.

### Resolved clarifications

| # | Decision | Resolution |
|---|---|---|
| Q1 | Certificate pinning posture | Disabled for first release; both pins ship empty; build-time host-match guard added so pinning cannot be silently inert when later enabled |
| Q2 | Alarm-scheduling permissions | Both `SCHEDULE_EXACT_ALARM` and `USE_EXACT_ALARM` removed; no runtime check added, since no exact alarms are scheduled anywhere |
| Q3 | Version code target | 7 / "1.2.0" — the brief's `versionCode 3` is a downgrade from the current 6 and would be rejected by Play |

### Deliberate deviation on "no implementation details"

This is a build-and-release engineering feature. The Pre-Spec Audit Findings section names
concrete files, versions, and configuration keys because the specification's central value is
correcting a factual premise — the brief described a repository state that no longer exists.
Requirements FR-001..FR-057 and all Success Criteria remain outcome-phrased and
implementation-agnostic; the technical specificity is confined to the audit section, where it
functions as evidence rather than design.

### Carried into planning

**Constitution amendment required before implementation lands** (FR-057). Four departures from
constitution v2.0.0:

1. API level 36 vs the constitution's pinned `targetSdk 35 / compileSdk 35`
2. Compose BOM bump vs the locked dependency version table
3. `MonitoringService` `dataSync` → `specialUse` vs Principle IV's explicit `TYPE_DATA_SYNC` mandate
4. Certificate pinning disabled vs Principle V's mandate to pin `bw.noor.net:8090`

Note also that the constitution states `targetSdk 35` while the build is actually at 34 — a
pre-existing drift the amendment should reconcile.

**P0 secrets incident found during audit** (absent from the original brief): tracked file
`keystore.properties.template` contains the password `Haris@1234`, committed in `f7b8974`.
Covered by FR-013 (scrub to placeholders) and FR-014 (treat any keystore built with it as
compromised and regenerate before registering it as an upload key). This is independent of phase
ordering and should be actioned first.

---

**Validation run 3 (2026-08-24, post-`/speckit-clarify`)** — 16/16 items still passing; no checkbox
state changed. Five further clarifications were asked and integrated, growing the spec from 57 to
72 functional requirements and from 15 to 19 success criteria.

The clarify pass surfaced four gaps that neither the original brief nor the spec covered, all in
categories the first validation had marked Clear on the strength of what was *asked for* rather
than what shipping actually requires:

| Gap | Category | Resolution | New requirements |
|---|---|---|---|
| Restricted-permission declarations (all-files access, package visibility, accessibility API, VPN, usage stats) — the top rejection causes for this app category | Compliance | Document and draft justification text; no automation | FR-058..FR-061 |
| Minified release build never executed; R8 can strip DI graphs, entities, and manifest-declared services with no build error | Reliability | Install and smoke-test five guarded paths before upload | FR-062..FR-064 |
| No crash visibility; obfuscated stack traces from testers are unreadable | Observability | Retain and upload the mapping file; no new SDK, no new data collection | FR-065..FR-067 |
| Privacy policy ships only as an in-app asset; data-safety questionnaire unaddressed — both block submission | Compliance | Document the public-URL requirement and draft the questionnaire answers from Principle I | FR-068..FR-070 |

Also resolved: the constitution amendment of FR-057 is now scoped as a task **inside** this feature
gating **Phase 3 only** (FR-071), and must additionally reconcile the pre-existing SDK-level drift
between the constitution and the build (FR-072). Phases 1, 2, 4 and the FR-013/FR-014 secrets
remediation are explicitly unblocked by it.

Requirement IDs FR-062..FR-072 sit outside file order (they appear in Phase 4, Play Console, and
Cross-cutting sections rather than at the end). This is deliberate — IDs are stable identifiers,
and renumbering forty existing requirements to preserve visual ordering would invalidate every
cross-reference for no functional gain.

