# Feature Specification: Remove the DATA_SYNC Foreground Service Declaration

**Feature Branch**: `015-remove-datasync-fgs-permission`

**Created**: 2026-10-05

**Status**: Draft

**Input**: User description: "Remove FOREGROUND_SERVICE_DATA_SYNC and migrate MonitoringService to the specialUse foreground service type. Google Play Console is blocking the release review with an undeclared foreground service permissions form listing FOREGROUND_SERVICE_DATA_SYNC and FOREGROUND_SERVICE_SPECIAL_USE. The dataSync type is currently assigned to MonitoringService, but that service does not perform data transfer. Fully remove FOREGROUND_SERVICE_DATA_SYNC from the app including the final merged manifest of the release AAB. Monitoring, enforcement, and boot/restart behavior must stay exactly as it is today."

## Audit Result (Pre-Condition for FR-001)

The feature description assumed the monitoring service still runs under the data-transfer
service category. A full audit of the current working tree was performed before writing this
spec, and the finding changes the shape of the work:

| Item | Expected by request | Actual current state |
| --- | --- | --- |
| Monitoring service category | data-transfer (`dataSync`) | **Already special-use** (`app/src/main/AndroidManifest.xml:116`) |
| Special-use purpose declaration | missing, to be added | **Already present** with a parental-control purpose string |
| Runtime category passed when the service enters foreground | data-transfer | **Already special-use** (`MonitoringService.kt:232`) |
| Platform timeout handler | not present | **Already overridden** defensively (`MonitoringService.kt:352`) |
| VPN content-filter service | special-use | special-use, unchanged |
| Any worker or background job claiming the data-transfer category | unknown | **None.** No worker declares foreground information at all |
| Bundled third-party components requesting the data-transfer permission | unknown | **None.** The background-work library in use (2.9.0) requests only the base foreground-service permission |
| The data-transfer permission request itself | to be removed | **Still present** (`app/src/main/AndroidManifest.xml:21`) — orphaned, mapped to nothing |

The service-category migration landed with feature `013-play-store-release-readiness`. What
remains is a single orphaned permission request that no component uses. That orphan is the sole
reason the store console still asks the developer to justify a data-transfer foreground service.

This feature therefore delivers: **remove the orphaned permission request, prove it is gone from
the shipped bundle, and prove that monitoring, enforcement, boot and restart behaviour are
unchanged.** No service-category migration work is required, and none may be invented.

## Clarifications

### Session 2026-10-05

- Q: Where does the regression guard for FR-010 live? → A: A build-time verification task wired into the release build, which fails the build (mirrors the existing certificate-pin build guard precedent in the constitution)
- Q: What counts as evidence for the device-facing criteria? → A: Manual verification against a written matrix, with each criterion recorded as concrete evidence (logcat line, dumpsys excerpt, API level) stored in the feature folder; no new automated tests, since this feature changes no service code
- Q: Is the store console check (SC-008) a blocking completion gate? → A: Non-blocking — SC-008 is post-merge confirmation by the developer. Merge gates on SC-001, SC-002, SC-009 and the completed FR-012 verification matrix

## Data Flow & Permission Justification

The project's governing principles require every spec that touches monitored data or operating
system permissions to state its data flow and justify its permissions before planning begins.

**Data flow: unchanged, and nothing new is collected.** This feature deletes one permission
request from the app's manifest. It adds no collection, no storage and no transmission. Nothing
is captured, nothing is retained, nothing leaves the child's device that did not already. Sensitive
analysis stays on the child's device exactly as before, and the derived-signals-only boundary is
untouched. Retention policy: not applicable — no new data.

**Permission justification.**

| Permission | Disposition | Justification |
| --- | --- | --- |
| Data-transfer foreground service permission | **Removed** | Maps to no enforcement feature. No service, worker or foreground-entry call requests this category, and no bundled dependency contributes it. The governing rule is explicit that an unmapped permission must be removed — this is that removal. |
| Special-use foreground service permission | Retained | Maps to continuous parental-control monitoring and to network-layer content filtering, both of which must run uninterrupted for the child to stay protected. Each service declares its purpose. |
| Base foreground service permission | Retained | Required for both services to run in the foreground at all, on every supported platform version. |

No permission is added by this feature. No high-risk permission changes meaning. Because no
service, receiver or worker is added, the obfuscation keep-rules require no update.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - The developer gets a clean store declaration (Priority: P1)

The developer uploads a new release bundle to the store console. The console's foreground service
declaration form no longer asks about data transfer. Only the special-use section remains, and it
is answerable with the parental-control justification the app already declares.

**Why this priority**: This is the release blocker. Nothing ships until the console stops asking
about a permission the app does not use. It is also the only part of this feature that changes a
file.

**Independent Test**: Build the release bundle, extract its final merged manifest, and search for
the data-transfer permission and the data-transfer service category. Both must be absent. No
device is required.

**Acceptance Scenarios**:

1. **Given** the app source after this change, **When** the release bundle is built and its final
   merged manifest is extracted, **Then** that manifest contains zero occurrences of the
   data-transfer foreground service permission and zero occurrences of the data-transfer service
   category.
2. **Given** the same build, **When** the merged manifest is inspected for the special-use
   permission and for both special-use services, **Then** all three are still present and each
   service still carries its purpose declaration.
3. **Given** the release bundle is uploaded, **When** the developer opens the foreground service
   declaration form, **Then** the form presents only the special-use section. *(Post-merge
   confirmation, not a merge gate — see SC-008.)*
4. **Given** the change is applied, **When** both the debug and release variants are built,
   **Then** both complete successfully and produce no new foreground-service-related warning.

---

### User Story 2 - Protection on a supervised device survives the change (Priority: P2)

A parent has already set up protection on a child's device. After the app is updated, screen-time
limits and app restrictions continue to be enforced exactly as before, including beyond the point
where a data-transfer service would have been stopped by the platform.

**Why this priority**: This is the behaviour the feature must not break. It is verification rather
than new capability, which is why it ranks below the release unblock, but a regression here is a
child left unprotected.

**Independent Test**: Install the updated app on a supervised device, leave the app in the
background, and confirm that a screen-time limit still triggers the lock screen. Repeat after the
device has been monitored for longer than the data-transfer runtime cap.

**Acceptance Scenarios**:

1. **Given** a supervised device with protection enabled, **When** the app is in the background
   and a configured screen-time limit is reached, **Then** the lock screen is shown.
2. **Given** protection has been running continuously for more than six hours in a 24-hour window,
   **When** the parent checks the device, **Then** monitoring is still running and enforcement
   still fires.
3. **Given** protection is enabled, **When** the parent looks at the device's notification shade,
   **Then** the persistent monitoring notification is present, with the same text and behaviour as
   before the change.
4. **Given** a device running a platform version older than the one that introduced service
   categories, **When** protection is enabled, **Then** monitoring starts and enforces normally
   with no crash and no permission failure.

---

### User Story 3 - Monitoring recovers from reboot and from being killed (Priority: P3)

The child reboots the device, or swipes the app away, or the system reclaims the service. In every
case protection comes back on its own, the same way it does today.

**Why this priority**: Recovery paths are where a wrong service category fails loudest, so they
must be confirmed. They rank third because they are exercised less often than steady-state
enforcement.

**Independent Test**: Reboot a supervised device and confirm both the monitoring service and the
content-filter service are running. Separately, swipe the app from recents and confirm monitoring
returns.

**Acceptance Scenarios**:

1. **Given** a supervised device, **When** it is rebooted and finishes starting up, **Then** both
   the monitoring service and the content-filter service are running, with no crash and no
   start-not-allowed or security failure reported.
2. **Given** protection is running, **When** the child swipes the app out of recents, **Then**
   monitoring is restarted by the existing restart path on the existing schedule.
3. **Given** protection is running, **When** the system kills the service, **Then** the existing
   restart receiver brings it back, unchanged in timing.
4. **Given** reboot or restart happens on each of the three most recent platform versions,
   **When** monitoring is asked to start, **Then** it starts successfully on all of them.

---

### Edge Cases

- **A future dependency upgrade reintroduces the permission.** A bundled component can add a
  permission request to the final merged manifest without any source change in this app. The
  verification must read the *final merged* manifest of the shipped bundle, not only the app's own
  manifest, so a reintroduction is caught.
- **Stale build output.** Previously generated merged manifests on disk still contain the
  permission. Verification must run against freshly produced output, or it will report a false
  pass or a false failure.
- **An abandoned worktree copy of the project still carries the old declaration.** A scratch copy
  of this project exists under a tooling directory with the pre-migration manifest. It contributes
  nothing to the shipped bundle and must not be edited or counted as a finding.
- **The permission is removed while something still needs it.** If any component were to request
  the data-transfer category at runtime after the permission is gone, that start would be refused.
  The audit above establishes that no component does; the change must not proceed if a later audit
  disagrees.
- **Older platform versions ignore service categories entirely.** On those versions the service
  enters foreground without naming a category, so removing the permission must be a no-op there —
  confirmed by exercising a device in that range.
- **The store console caches the previous declaration.** The form may continue to show the old
  question until a bundle built after this change is processed. Only a freshly uploaded bundle is
  evidence.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: Before any file is modified, the project MUST be audited for every dependency on the
  data-transfer foreground service category or its permission — the app's own manifest, every
  service, every background worker, every foreground-entry call, and every bundled dependency's
  contributed manifest. Removal MUST NOT proceed unless the audit finds no remaining dependency,
  or every dependency found has a written migration path. *(Completed; see Audit Result above.)*
- **FR-002**: The data-transfer foreground service permission MUST NOT appear in the app's own
  manifest.
- **FR-003**: The data-transfer foreground service permission MUST NOT appear in the final merged
  manifest of either the debug or the release build, and MUST NOT appear in the shipped release
  bundle.
- **FR-004**: No service, background worker, or foreground-entry call anywhere in the app may name
  the data-transfer service category.
- **FR-005**: The monitoring service MUST run as a special-use foreground service and MUST carry a
  declared special-use purpose describing its parental-control function. *(Already satisfied;
  this requirement is a preservation constraint, not new work.)*
- **FR-006**: The monitoring service's persistent notification MUST remain visible, with its
  current text, channel, importance and dismissal behaviour unchanged.
- **FR-007**: The content-filter service's declaration, purpose string and runtime behaviour MUST
  remain unchanged.
- **FR-008**: Starting the monitoring service from the boot path and from the restart path MUST
  succeed on the three most recent platform versions, with no start-not-allowed failure and no
  permission failure.
- **FR-009**: The monitoring service's lifecycle, restart strategy and enforcement-loop timing
  MUST be byte-for-byte unchanged by this feature.
- **FR-010**: The project MUST gain an automated build-time guard that reads the final merged
  manifest produced for a release build and **fails the build** if the data-transfer permission or
  the data-transfer service category is present, so a future dependency upgrade cannot silently
  reintroduce either one. The guard MUST run as part of producing a release artifact — not as an
  optional manual step, and not only in a continuous-integration pipeline — so that a
  non-compliant bundle cannot be produced locally. It MUST follow the same pattern as the existing
  release-blocking build guard already established for certificate pinning.
- **FR-011**: The release documentation MUST record that the data-transfer permission is now
  removed, and the deliberate deferral recorded against feature `013-play-store-release-readiness`
  (which verified the permission was unused but left it declared) MUST be marked closed. No
  governing document currently misstates the service category, so no correction to the category
  itself is required or permitted.
- **FR-012**: Every device-facing success criterion MUST be verified manually against a written
  verification matrix stored in this feature's folder, and each row MUST carry concrete recorded
  evidence — a log line, a service-state dump excerpt, or an equivalent artefact — together with
  the platform version and device or emulator identity it was captured on. A criterion with no
  recorded evidence counts as unverified.
- **FR-013**: This feature MUST NOT add automated test coverage for the service-category migration
  itself. That migration shipped with feature `013-play-store-release-readiness`; new tests around
  it would verify prior work rather than this change. Recurrence is prevented by the FR-010 build
  guard, not by tests.

### Key Entities

- **Foreground service declaration**: The set of permission requests and per-service category and
  purpose declarations that the final merged manifest carries, and that the store console reads to
  build its declaration form. This feature changes exactly one entry in this set: it deletes the
  data-transfer permission request.
- **Monitoring service**: The long-running component that enforces screen-time limits, identifies
  the app in the foreground, raises the lock screen when a limit is reached, and watches saved
  media. Declared special-use. Behaviour frozen by this feature.
- **Content-filter service**: The long-running component that filters content at the network
  layer. Declared special-use. Untouched by this feature.
- **Recovery paths**: The boot path and the restart path that bring monitoring back after a reboot,
  an app swipe-away, or a system kill. Behaviour frozen; used as verification surface.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: The release bundle's final merged manifest contains zero occurrences of the
  data-transfer foreground service permission and zero occurrences of the data-transfer service
  category.
- **SC-002**: The debug and release builds both complete successfully, and the count of
  foreground-service-related build warnings is the same as or lower than before the change.
- **SC-003**: On a device running a platform version that reports service categories, the running
  monitoring service reports the special-use category and no other.
- **SC-004**: After a reboot of a supervised device, both the monitoring service and the
  content-filter service are observed running, in 3 of 3 reboot attempts.
- **SC-005**: With the app in the background, reaching a configured screen-time limit raises the
  lock screen, in 3 of 3 attempts.
- **SC-006**: On a device in the older platform range that predates service categories, protection
  starts, enforces, and survives a reboot with zero crashes.
- **SC-007**: Monitoring is still running and still enforcing after more than six continuous hours
  of protection in a single 24-hour window.
- **SC-008**: *(Post-merge confirmation — not a merge gate.)* The store console's foreground
  service declaration form presents exactly one section — special use — after a bundle built
  from this change is processed. Observing this requires an upload, which this feature does not
  perform; it is the developer's confirmation that the release blocker is cleared.
- **SC-009**: Attempting to produce a release artifact with the data-transfer permission
  artificially reinserted fails the build, with a message naming the offending permission. The
  same build succeeds once the permission is removed.

**Completion gate**: this feature is complete when SC-001, SC-002 and SC-009 pass and the FR-012
verification matrix is filled in with recorded evidence for SC-003 through SC-007. SC-008 is
confirmed by the developer after upload and does not block completion.

## Assumptions

- The monitoring service's special-use category, purpose string, timeout handler and notification
  are already correct as shipped; this feature preserves them and does not redesign them. The
  wording of the existing purpose string is accepted as-is and is not rewritten.
- The data-transfer permission currently declared is orphaned. Removing it changes no runtime
  behaviour on any platform version, because nothing requests that category.
- Verification of device-facing criteria is manual, recorded in a verification matrix, and uses
  the lowest and highest supported platform versions plus the three most recent, on emulators
  where physical devices are not available. Emulator results are accepted as evidence except
  where a behaviour is known to differ.
- The six-hour observation for SC-007 may be satisfied by the platform's own accounting rather
  than a wall-clock vigil, provided the service is confirmed alive and enforcing at the end, and
  the evidence captured says which method was used.
- Filling in the store console's declaration form is the developer's manual step and is outside
  this feature's deliverable; the feature's job is to make the form ask only the special-use
  question.
- The scratch worktree copy of this project under the tooling directory is abandoned and is
  excluded from every audit, change and verification in this feature.
- Notification copy, including the existing active-protection wording, stays exactly as it is.
  No rebranding is in scope.

## Out of Scope

- Any change to the on-device classification pipelines, text or image analysis, network-layer
  filtering logic, the accessibility service, the alert pipeline, dependency wiring, worker logic,
  or navigation.
- Any change to service lifecycle, restart strategy, or enforcement-loop timing.
- New automated test coverage for the already-shipped service-category migration.
- Any change to notification text, channels, or icons, including rebranding.
- Entering content into the store console's declaration form.
- Revisiting the choice of the special-use category itself, or its purpose string wording.
- Any new foreground service, or any new permission.
