# Feature Specification: Child Pairing Gate for Monitoring

**Feature Branch**: `004-child-pairing-gate`

**Created**: 2026-07-06

**Status**: Draft

**Input**: User description: "A child device must only START monitoring (enforcement loop + image monitoring) if the child account is PAIRED to a parent — i.e. the child account has at least one linked parent (family link). Pairing in this app is account-level (family link), NOT device-to-device. The single gate point is MonitoringService.onStartCommand."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Unpaired child device stays dormant (Priority: P1)

A child installs the app and signs in, but no parent has linked to the child account yet. On a fresh, never-confirmed install the device must NOT start enforcement or image monitoring. Monitoring only begins once a parent link is confirmed.

**Why this priority**: This is the core safety/consent boundary. Running surveillance-grade enforcement on a child device that no parent has claimed is both a privacy violation and a legitimacy risk. Blocking startup for never-confirmed devices is the whole point of the feature.

**Independent Test**: Fresh install, no parent linked, start the monitoring service. Verify enforcement loop and image monitoring do not start; verify the "not yet paired → gated" log appears.

**Acceptance Scenarios**:

1. **Given** a fresh child install with no confirmed parent link (cached flag = false), **When** the monitoring service starts, **Then** the enforcement loop and image monitoring do NOT start and a background parent-link refresh is triggered.
2. **Given** an unpaired child device with the background refresh running, **When** the backend confirms at least one linked parent, **Then** the cached paired flag becomes true and monitoring starts at runtime without requiring a service restart.

---

### User Story 2 - Paired child device works offline and deterministically (Priority: P1)

A child device that has previously confirmed a linked parent must start monitoring at every service start, even with no network connectivity, based solely on the cached paired flag.

**Why this priority**: Enforcement must be resilient to tampering. If a child (or a transient outage) drops the network, an already-paired device must keep protecting. Depending on a live network call at startup would create an obvious bypass.

**Independent Test**: Paired device (cached flag = true), disable network, start the service. Verify monitoring starts normally with no network dependency.

**Acceptance Scenarios**:

1. **Given** a child device with cached paired flag = true, **When** the monitoring service starts with no connectivity, **Then** the enforcement loop and image monitoring start normally.
2. **Given** a child device with cached paired flag = true, **When** the background refresh encounters a network error, **Then** the cached flag is left unchanged and monitoring continues.

---

### User Story 3 - Confirmed unpair stops future monitoring (Priority: P2)

When a parent unlinks the child, the next successful refresh returns an empty parent list. The cached flag flips to false so monitoring does not start on the next service start.

**Why this priority**: Consent can be withdrawn. A confirmed (HTTP 200, empty list) unpair must be honored, distinct from a transient network failure. Lower priority than P1 because it is a de-escalation, not a protection gap.

**Independent Test**: Paired device, backend returns an empty parent list on refresh. Verify the cached flag becomes false and monitoring stays stopped on the next start.

**Acceptance Scenarios**:

1. **Given** a paired child device, **When** a refresh succeeds with an empty parent list, **Then** the cached flag is set to false (confirmed unpair) and monitoring is not started.
2. **Given** an unpaired child device (flag = false), **When** the service restarts, **Then** monitoring stays gated.

---

### Edge Cases

- **Parent device**: On a parent-role device the gate is bypassed entirely — enforcement loop is skipped (parent devices are never monitored). The pairing gate applies to child role only.
- **Network error vs. confirmed empty**: A network error/no connectivity MUST NOT change the cached flag; only a successful response mutates it. This distinguishes "confirmed no parent" (200 + empty) from "cannot tell right now."
- **Schema mismatch**: The parent-link response is modeled tolerantly (all fields nullable) so an unexpected backend field name or shape never crashes parsing and never falsely gates a paired device.
- **Runtime pairing**: A device that starts unpaired and gets confirmed during the same service lifetime starts monitoring mid-session without a restart.
- **Logout**: Existing logout/clear-all behavior already clears cached preferences, so a new account starts from the never-confirmed (gated) state.
- **Near-real-time unpair**: The child currently learns of an unpair only by polling at service start. Faster propagation (push signal) is out of scope and flagged for backend coordination.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The child monitoring gate MUST be enforced at a single point — the monitoring service start — and MUST read a locally cached paired flag so the decision is deterministic and available offline.
- **FR-002**: On a child device where the cached paired flag is true, the system MUST start the enforcement loop and image monitoring (if not already running).
- **FR-003**: On a child device where the cached paired flag is false, the system MUST NOT start the enforcement loop or image monitoring, and MUST trigger a one-shot background refresh of parent-link status.
- **FR-004**: When the background refresh confirms at least one linked parent, the system MUST set the cached flag to true and start monitoring at runtime (no restart required).
- **FR-005**: The refresh MUST classify outcomes as follows: success with a non-empty parent list → flag = true; success with an empty list → flag = false (confirmed unpair); any network/connectivity error → flag left unchanged.
- **FR-006**: A transient network failure MUST NEVER disable an already-paired device; the cached flag is mutated only on a successful response.
- **FR-007**: On a parent-role device, the pairing gate MUST be bypassed and the enforcement loop skipped.
- **FR-008**: The parent-link response MUST be parsed tolerantly (all fields optional) so a schema mismatch never crashes parsing.
- **FR-009**: The paired flag MUST default to false (never-confirmed / gated) on a fresh install and after logout/clear-all.
- **FR-010**: This feature MUST only gate WHETHER image monitoring and the enforcement loop start; it MUST NOT alter how alerts are produced, the ML pipeline, VPN/content-filter services, screen-time enforcement logic itself, workers, receivers, DI wiring, or any UI.

### Key Entities *(include if feature involves data)*

- **Linked Parent**: A parent account linked to the child account via family link. Attributes are treated as optional/derived (identifier, parent identifier, parent email, parent name, link creation time). Only the presence (non-empty list) vs. absence (empty list) matters to the gate.
- **Cached Paired Flag**: A persisted boolean on the child device recording whether a parent link has ever been confirmed. Source of truth for the startup gate. Defaults to false.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A fresh child install with no linked parent never starts enforcement or image monitoring — 100% of never-confirmed starts remain gated.
- **SC-002**: A previously paired child device starts monitoring on service start with no network connectivity in 100% of cases.
- **SC-003**: A transient network failure results in zero changes to the cached paired flag — an already-paired device is never disabled by a network error.
- **SC-004**: When a parent links a previously unpaired child, monitoring begins within one background-refresh cycle of the same service lifetime, without a manual restart.
- **SC-005**: A confirmed unpair (successful empty response) results in monitoring staying stopped on the next service start in 100% of cases.

## Assumptions

- The child device authenticates with the user (JWT) access token, and the parent-link endpoint is authorized for the child role with that token (to be confirmed with backend).
- The "no parent linked" case returns a successful response with an empty list (not an error/404), so the gate can distinguish confirmed-no-parent from network failure (to be confirmed with backend).
- Exact field names of the parent-link response are unconfirmed; the data model is intentionally tolerant with a visible TODO pending backend confirmation.
- Existing logout/clear-all already wipes cached preferences; no additional clearing logic is needed.
- Parent-role detection and the existing enforcement-loop / image-monitoring start routines already exist and are reused unchanged.
- Near-real-time unpair propagation (push/FCM/RTDB) is out of scope; the child learns of link changes by polling at service start.

## Dependencies & Backend Coordination

- Confirm exact JSON schema of the parent-link endpoint (field names for parent id / email / name / created-at). Current model is tolerant and marked with a TODO.
- Confirm the endpoint is authorized for the child role using the user access (Bearer/JWT) token, not the device token.
- Confirm the no-parent case returns HTTP 200 with an empty list (not 404).
- Confirm whether near-real-time unpair propagation is required; if so, a push signal is a follow-up task.
