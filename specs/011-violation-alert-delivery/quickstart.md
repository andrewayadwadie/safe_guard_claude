# Quickstart & Validation: End-to-End Violation Alert Delivery

**Feature**: 011-violation-alert-delivery | **Date**: 2026-07-25 | **Plan**: [plan.md](plan.md)

Runnable validation for each user story. Details of payloads and component behaviour live in [contracts/](contracts/) and [data-model.md](data-model.md) — not repeated here.

---

## Prerequisites

- **Two devices/emulators** (API 33+ preferred so the notification-permission path is exercised): one signed in as **parent**, one as **child**, linked via the existing pairing flow.
- Real `google-services.json` in `app/` (not the placeholder) — FCM will not deliver otherwise.
- Backend reachable at `https://bw.noor.net:8090/api/v1`, with the coordination items in [contracts/backend-contract.md](contracts/backend-contract.md) deployed (parent fan-out + data-only violation pushes).
- Child device: monitoring consent granted and the five protection permissions set up.

## Build & install

```powershell
./gradlew assembleDebug
adb -s <child-serial>  install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <parent-serial> install -r app/build/outputs/apk/debug/app-debug.apk
```

## Logging

```powershell
adb -s <child-serial>  logcat -s SafeGuard:* AlertRepository:* PendingAlertWorker:* -v time
adb -s <parent-serial> logcat -s SafeGuard:* ViolationNotifier:* PushTokenSyncWorker:* -v time
```

---

## Story 5 first — parent reachability (run this before anything else)

This is the precondition that does not exist in the current build; if it fails, Stories 2–4 cannot be observed.

| # | Step | Expected |
|---|---|---|
| 5.1 | Fresh install, parent signs in | `PUT /auth/me/fcm-token` with a non-empty `fcm_token`; log line confirms publish, **token value never printed** |
| 5.2 | Parent already signed in from a previous build, then upgrade + open the app | Token published on **startup** — no sign-out/sign-in required (FR-029 backfill) |
| 5.3 | Force a token rotation: clear Google Play services data, or `adb shell pm clear` a test build and re-auth | `onNewToken` → `PUT /auth/me/fcm-token` with the new value |
| 5.4 | Parent signs out | `PUT /auth/me/fcm-token` with `{"fcm_token": null}` observed **before** the session is torn down (no 401) |
| 5.5 | API 33+ parent device, notifications denied at first launch after sign-in | Permission prompt appears |
| 5.6 | Deny it, reopen the app | Non-blocking banner explains alerts cannot be shown, offers a route to settings; every other screen still usable |
| 5.7 | Dismiss the banner, navigate around | Banner stays gone; nothing is blocked (FR-032) |
| 5.8 | Child device registration (fresh child setup) | `POST /devices` body carries a non-null `fcm_token` |

**Verify server-side**: the parent's token is stored against the account, and the child's token against its device record.

---

## Story 1 — no violation is silently lost

| # | Step | Expected |
|---|---|---|
| 1.1 | Child device → airplane mode ON. Trigger a text violation (type a blacklisted word in a monitored app) | Alert is **queued**, not discarded. `PendingAlertStore` count = 1. Detection/enforcement behave exactly as before (FR-013) |
| 1.2 | Airplane mode OFF | Within ~2 min (SC-002) the alert appears in the parent's list; the queue row is deleted |
| 1.3 | Point the client at an unreachable backend (or stop it) and trigger a violation | Queued and retried with **increasing** delays — check the WorkManager retry timestamps in logcat, not a tight loop |
| 1.4 | Trigger the *same* text twice inside the dedup window, offline | Second is **suppressed, not queued**. Queue count stays 1 (FR-007) |
| 1.5 | Same for cooldown and daily-cap suppression | Not queued |
| 1.6 | Sign the child out with entries queued | Queue is empty afterwards (FR-011) |
| 1.7 | Offline burst of >100 alerts (script the detection path, or lower the cap temporarily in a debug build) | Exactly 100 retained; the **oldest** are dropped, the newest kept (FR-010) |
| 1.8 | Kill the app with entries queued, then restart it so `MonitoringService` starts | A delivery attempt fires at service start (FR-012) |
| 1.9 | 50-violation offline run, then restore connectivity | **Zero** losses (SC-001); all 50 appear in the parent list |

Inspect the queue directly if needed:

```powershell
adb -s <child-serial> shell "run-as com.safeguard.parentalcontrol sqlite3 databases/safeguard.db 'SELECT id, created_at, attempt_count FROM pending_alerts ORDER BY created_at;'"
```

---

## Story 2 — the parent understands the alert without opening the app

| # | Step | Expected |
|---|---|---|
| 2.1 | Parent app backgrounded; trigger a violation on the child | Notification reads `{child name} — {device name} · {violation type} · {time}`, never a bare "Alert" (SC-003) |
| 2.2 | Trigger each of the six types: inappropriate text, inappropriate image, blocked content, screen-time limit, blocked app, device-admin disabled | Each shows its own localized label (FR-016) |
| 2.3 | Have the backend send an unknown `alert_type` | Generic localized "Violation" label — never the raw internal string (FR-017) |
| 2.4 | Violation today vs one back-dated to yesterday | Today → short time only; earlier → short date + time (FR-018) |
| 2.5 | Switch the parent device (or the in-app language) to Arabic; repeat 2.1 | All labels **and** the date/time render in Arabic (SC-007) |
| 2.6 | Have the backend re-push the same `alert_id` | Exactly **one** notification, updated in place — no duplicate stack (FR-020, SC-006) |
| 2.7 | Critical-severity violation | Full alerting treatment (sound + vibration), same as today (FR-021) |
| 2.8 | Very long child/device names | Collapsed line truncates; expanding shows the full text (FR-019) |
| 2.9 | Inspect the alert in the parent list | `metadata` carries `child_name`, `device_name`, `device_db_id`, `occurred_at`, `app_version` — for **every** alert type (FR-001/002) |
| 2.10 | Trigger an alert type whose helper already sets one of those keys | The alert's own value wins (FR-003) |

---

## Story 3 — tapping the notification lands on the alert

| # | Step | Expected |
|---|---|---|
| 3.1 | Parent app fully closed; tap the notification | Opens directly on the Alerts screen filtered to the originating device |
| 3.2 | Parent app running on another screen; tap the notification | Navigates to Alerts **without restarting** the app (state preserved) |
| 3.3 | On arrival | List already reflects the new alert, shown unread |
| 3.4 | Rotate the device on the Alerts screen | Does **not** re-navigate (FR-024) |
| 3.5 | Leave Alerts and come back | List refreshes on resume (FR-025) |
| 3.6 | Let the session expire (or sign out), then tap a notification | Routed to Login; after successful auth it **continues** to the Alerts screen for that device (FR-026) |
| 3.7 | Unlink the child device, then tap an older notification for it | Falls back to the unfiltered Alerts/device-list view — no broken or empty screen (FR-027) |
| 3.8 | Push arrives while the parent is already on the Alerts screen | List refreshes; no navigation away and no re-navigation |
| 3.9 | Time the whole path | Notification → full alert record in one tap, under 5 s (SC-004) |

---

## Story 4 — only parents are notified

| # | Step | Expected |
|---|---|---|
| 4.1 | Send the same violation push to a signed-in **child** device | **No** notification, **no** sound; a log-only entry (FR-014, SC-005) |
| 4.2 | Same push to a signed-in **parent** device | Displayed normally |

---

## Regression checks (SC-009 — nothing else moved)

| # | Check |
|---|---|
| R1 | Alert titles, message text, and severity mapping are byte-identical to the previous build for every type |
| R2 | Dedup, cooldown, and daily-cap outcomes are unchanged (compare counters in the `alert_cooldown` DataStore across a scripted run) |
| R3 | The child `sync`-command push still triggers `SyncWorker.enqueueImmediate` and an instant rule re-fetch |
| R4 | With push disabled entirely on the parent device, alerts still appear when the app is opened — push remains an accelerant, not a control path (Constitution Principle III) |
| R5 | Detection, VPN filtering, screen-time enforcement, and boot restart behave exactly as before |

## Automated tests

```powershell
./gradlew testDebugUnitTest
```

Priority units: queue cap + ordering, the suppression-vs-failure classifier, enrichment precedence, violation-type label fallback, and today-vs-earlier time formatting.
