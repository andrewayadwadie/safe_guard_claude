# Quickstart: verifying the app-lock chain on real devices

**Feature**: 017-app-lock-enforcement | **Date**: 2026-10-18
**Package**: `com.safeguard.parentalcontrol`

Two devices: a **parent** device signed into the parent account, and a **child** device paired to it
with monitoring running. An emulator cannot verify this — usage-stats detection, overlay
presentation, Doze and OEM process kills all behave differently there.

## 0. Build and install

```powershell
./gradlew assembleDebug
adb devices -l                     # confirm both devices, note the two serials
adb -s <PARENT_SERIAL> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <CHILD_SERIAL>  install -r app/build/outputs/apk/debug/app-debug.apk
```

Every command below is prefixed `adb -s <SERIAL>`; the serial tells you which device it runs on.

## 1. Log streams to keep open

One terminal per device, for the whole session.

```bash
# PARENT — the save path
adb -s <PARENT_SERIAL> logcat -c && adb -s <PARENT_SERIAL> logcat -s AppLock:V OkHttp:V

# CHILD — delivery, persistence, enforcement
adb -s <CHILD_SERIAL> logcat -c && adb -s <CHILD_SERIAL> logcat -s AppLock:V ALERT_PIPE:V
```

The whole chain is on one tag by design (FR-024). If you need the pre-existing enforcement lines too:

```bash
adb -s <CHILD_SERIAL> logcat | grep -E "AppLock|ENFORCEMENT|screen-time-rules"
```

Confirm the child's prerequisites before blaming the feature:

```bash
adb -s <CHILD_SERIAL> shell appops get com.safeguard.parentalcontrol GET_USAGE_STATS      # expect allow
adb -s <CHILD_SERIAL> shell appops get com.safeguard.parentalcontrol SYSTEM_ALERT_WINDOW  # expect allow
adb -s <CHILD_SERIAL> shell dumpsys activity services com.safeguard.parentalcontrol | grep -iE "MonitoringService|specialUse"
```

---

## 2. MANDATORY CHECKPOINT (run after phase 1, before phase 2)

Scenarios (a)–(e). This is where assumptions **A1**, **A4** and **A7** are settled on hardware.

### (a) Toggle a lock with no button press → `PUT` succeeds

1. Parent: open Screen Time Limits for the child device.
2. Note the current daily limit and bedtime — needed for the A7 check in (c).
3. Add an app to Blocked Apps (pick one the child has used, e.g. `com.instagram.android`).
4. Do **not** look for a save button. There is none.

Parent log, expected:

```
AppLock  I  SAVE field=BLOCKED_APPS deviceId=42 value=+com.instagram.android size=2 isActive=true
AppLock  I  SAVE field=BLOCKED_APPS http=200 confirmed size=2
```

| What you see | Means |
|---|---|
| no `SAVE` line at all | the control is still not wired — phase 1 incomplete |
| `http=200` but `blocked_apps` absent from the response body in the OkHttp log | **A1 false** — backend ignores the field. Stop; backend fix needed first |
| `http=404` | **A6 false** — `PUT` does not upsert. Phase 1 needs a create-then-update path |
| `http=401` after a retry | auth problem, not this feature |

### (b) Toggle with the parent device offline → revert + error

```bash
adb -s <PARENT_SERIAL> shell svc wifi disable && adb -s <PARENT_SERIAL> shell svc data disable
```

Toggle any switch. Expected: brief progress, the switch returns to its old position, a Snackbar
appears.

```
AppLock  I  SAVE field=BLOCKED_APPS deviceId=42 value=+com.facebook.katana size=3
AppLock  W  SAVE field=BLOCKED_APPS failed http=-1 -> rollback size=2
```

Re-enable: `adb -s <PARENT_SERIAL> shell svc wifi enable`

### (c) Rapid on/off/on → server matches screen, nothing else clobbered

Toggle the same app's lock three times fast. Expected: **one** `SAVE … http=200` for the settled
value, not three. Then leave the screen, return, and confirm the switch shows the settled state.

Then the **A7 check**, which matters as much as the lock itself: confirm the daily limit and bedtime
noted in (a) are unchanged. In the OkHttp response body for the lock save, `daily_limit` and
`bedtime_start` must still hold their old values.

| What you see | Means |
|---|---|
| three `http=200` lines | coalescing not working (FR-006) |
| final server state ≠ screen | out-of-order response won (FR-006) |
| `daily_limit` came back `null` / `0`, or bedtime cleared | **A7 false** — the backend replaces the row instead of patching it. **Stop.** The per-field design must change before phase 2 |

### (d) Unlock the **last** locked app → child can open it

Remove every locked app until none remain. Expected:

```
AppLock  I  SAVE field=BLOCKED_APPS deviceId=42 value=-com.instagram.android size=0
AppLock  I  SAVE field=BLOCKED_APPS body={"blocked_apps":[]} http=200 confirmed size=0
```

The `[]` must be present in the body. An absent `blocked_apps` key is the pre-fix defect.

Child, within ~35 s:

```
AppLock  I  FETCH deviceId=42 http=200 blockedApps=[] isActive=true
AppLock  I  ENFORCE fg=com.instagram.android blocked=false -> dismiss lock screen
```

Then open the app on the child. It must work.

### (e) Child app closed → lock → open → blocked

Lock an app from the parent, wait for the child's `FETCH`, then launch it on the child.

```
AppLock  I  FETCH deviceId=42 http=200 blockedApps=[com.instagram.android] isActive=true
AppLock  I  PERSIST wrote rules deviceId=42 blocked=1
AppLock  I  ENFORCE fg=com.instagram.android blocked=true -> show lock (overlay)
```

| What you see | Means |
|---|---|
| `FETCH … blockedApps=[]` after a successful parent save | **A1 false**, or **A4 false** — ids differ |
| `FETCH … http=401/403` | **A3 false** — the child is not authorised for its own device |
| `FETCH … http=404` | no rules row for the id the child is asking about — check **A4** |
| `FETCH` shows the package but `blocked=false` | package-string mismatch; compare `adb shell dumpsys activity activities \| grep mResumedActivity` |
| `blocked=true` but no overlay | presentation problem, not delivery — check `SYSTEM_ALERT_WINDOW` |
| `isActive=false` | the master switch is off, or **A2** bit — the policy is stored inactive |

**→ Report (a)–(e) results before phase 2 starts.**

---

## 3. Phase 2 verification

### (g) Reboot the child with networking off → still blocked

```bash
# with a lock confirmed working from (e):
adb -s <CHILD_SERIAL> shell svc wifi disable
adb -s <CHILD_SERIAL> shell svc data disable
adb -s <CHILD_SERIAL> reboot
# wait for boot, keep the log stream open
```

Expected, **before** any network line appears:

```
AppLock  I  RESTORE loaded persisted rules deviceId=42 blocked=1 isActive=true age=412s
AppLock  I  ENFORCE fg=com.instagram.android blocked=true -> show lock (overlay)
AppLock  W  FETCH deviceId=42 failed http=-1 -> keeping persisted copy
```

Open the locked app. It must be blocked. Order matters: `RESTORE` must precede any `FETCH`
(FR-014). A `FETCH … failed` that is followed by the lock still working is FR-015.

Also confirm the negative: clear app data, reboot offline, and the child must log
`RESTORE no persisted rules` and enforce nothing — not silently pretend nothing is locked (FR-019).

---

## 4. Phase 3 verification

### (f) Child inside the app → lock → removed ≤10 s

Child opens the app and stays in it. Parent locks it. Time it.

```
AppLock  I  PUSH rules_changed keys=[type] -> refresh
AppLock  I  FETCH deviceId=42 http=200 blockedApps=[com.instagram.android]
AppLock  I  ENFORCE immediate (rules changed) fg=com.instagram.android blocked=true -> show lock
```

If the backend has not shipped A5, there will be no `PUSH` line and the bound is ~35 s instead of
10 s — that is the documented fallback, not a failure. Prove the push branch separately with a
hand-crafted message:

```bash
curl -X POST https://fcm.googleapis.com/fcm/send \
  -H "Authorization: key=<SERVER_KEY>" -H "Content-Type: application/json" \
  -d '{"to":"<CHILD_FCM_TOKEN>","priority":"high","data":{"type":"rules_changed"}}'
```

Force a refresh without waiting, as an alternative:

```bash
adb -s <CHILD_SERIAL> shell am startservice -a com.safeguard.parentalcontrol.REFRESH_RULES \
  -n com.safeguard.parentalcontrol/.service.MonitoringService
```

---

## 5. Phase 4 verification

### (h) Revoke usage access → loud log + parent alerted

```bash
adb -s <CHILD_SERIAL> shell appops set com.safeguard.parentalcontrol GET_USAGE_STATS ignore
```

Child, expected:

```
AppLock  E  CAPABILITY usage_access=MISSING (hard-required) -> enforcement impossible, alerting parent
AppLock  E  ENFORCE aborted: cannot read foreground app without usage access
```

The parent must receive a protection alert. Never a silent return (FR-021).

Then the degraded case:

```bash
adb -s <CHILD_SERIAL> shell appops set com.safeguard.parentalcontrol GET_USAGE_STATS allow
adb -s <CHILD_SERIAL> shell appops set com.safeguard.parentalcontrol SYSTEM_ALERT_WINDOW ignore
```

```
AppLock  W  CAPABILITY overlay=MISSING (degraded) -> using notification fallback, no alert
AppLock  I  ENFORCE fg=com.instagram.android blocked=true -> show lock (notification)
```

The app must still be blocked. Restore with `… SYSTEM_ALERT_WINDOW allow`.

### (i) Regression: the ML path is untouched

Trigger a text-monitoring alert on the child the way QA normally does, and confirm it still reaches
the parent:

```bash
adb -s <CHILD_SERIAL> logcat -s ALERT_PIPE:V
```

The `ALERT_PIPE` stream must look exactly as it did before this feature. Nothing in
`TextMonitoringAccessibilityService`, the TFLite classifiers, `TextPatternMatcher`,
`ContentFilterVpnService` or `ImageBlurManager` was modified, so any change here is a bug in this
feature.

Also confirm the VPN/DNS filter still blocks a known-blocked domain, and that an image scan still
flags, since both share the alert channel this feature now also posts to.

### (j) OEM device: repeat (e), (f), (g) on Xiaomi / Infinix / Tecno / Oppo

Default battery settings, **no** manual allowlisting — the point is the out-of-the-box behaviour.

```bash
adb -s <CHILD_SERIAL> shell dumpsys deviceidle force-idle      # simulate Doze
adb -s <CHILD_SERIAL> shell dumpsys deviceidle unforce
adb -s <CHILD_SERIAL> shell am kill com.safeguard.parentalcontrol   # simulate an OEM kill
```

After the kill, the service should restart and `RESTORE` should fire before any `FETCH`. Expect the
≤10 s target to degrade on these devices; expect (g) — blocked after reboot, offline — to hold
anyway. That is the whole point of phase 2.

---

## 6. Unit tests

```powershell
./gradlew testDebugUnitTest --tests "*ScreenTimeLimitsSaveTest*" --tests "*ScreenTimeRulesPersistenceTest*" --tests "*AppBlockDecisionTest*"
```

Note: two pre-existing `WordPieceTokenizerTest` failures in the `ml` module are a Windows CRLF
artefact, not a regression from this feature.
