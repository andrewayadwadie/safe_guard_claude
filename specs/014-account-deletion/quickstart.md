# Quickstart: Verifying Account Deletion (014)

> **THROWAWAY ACCOUNTS ONLY.** Create every account with Postman folder **0** (setup). Never run these steps against a real family. Deletion is immediate and permanent on production.

## Prerequisites

- A debug build: `./gradlew assembleDebug`, then `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
- Two devices or emulators. Device **P** is signed in as a throwaway parent. Device **C** is signed in as a throwaway child that has finished setup (consent, permissions, content filtering ON).
- Postman: folder 0 (create accounts and link them), plus the "Sanity: account still alive" request (`GET /auth/me` with that account's token).
- Logcat filter on its own terminal:

  ```sh
  adb logcat -s AccountDeletion:* MonitoringService:* ContentFilterVpnService:* TamperAlertWorker:*
  ```

- Checkpoint after every implementation phase: `./gradlew assembleDebug`. Unit tests: `./gradlew testDebugUnitTest --tests "*Deletion*"`.

## Scenarios

| ID | Setup | Steps | Expected |
|---|---|---|---|
| **V1** Wrong password (parent) | P signed in | Settings → Delete account → enter a wrong password → Delete → confirm the dialog | Inline error on the password field, still signed in, the retry works. Logcat: `AccountDeletion: failed code=400 mapped=WrongPassword`. Postman sanity returns 200. |
| **V2** Rate limit | P | Repeat V1 until the server returns 429 | The rate-limit wording ("wait and try again") appears. The words "wrong password" **never** appear. Logcat `code=429 mapped=RateLimited`. |
| **V3** Supervised child | C is linked to P | C: Settings → Delete account → correct password → confirm | The "only your parent can delete this account" message. C stays signed in, the monitoring notification stays, protection runs. Logcat `code=403 mapped=SupervisedChild`. Postman sanity for C returns 200. |
| **V4** Parent deletes a child | P with two linked children, C1 and C2 | P: Children → C1 → "Delete account permanently" → type C1's exact name → password → Delete | Wrong name or empty password keeps Delete disabled. On success C1 disappears and C2 is unchanged. Postman sanity for C1 returns 401. **Record** what device C1 shows on its next request: expected today, a "session ended" toast, Login, and protection **still running**. This is the known limitation RD-2, pending backend Q6. |
| **V4b** Child already unlinked | P's list is stale: unlink C via Postman first | Delete that child from P | The list refreshes, with "no longer linked to your account". Nothing is shown as a failure. |
| **V4d** Deleted child gone everywhere (FR-023) | Right after V4, without restarting the app | Open Dashboard, Alerts, Devices and Screen-time limits | None of them shows C1 or its devices. The Dashboard refreshes after returning from Children. |
| **V4c** Google parent | P signed in with Google | Delete a child | The Google account chooser appears. Cancelling keeps the dialog open. Choosing another account aborts with nothing deleted. The same account deletes the child. |
| **V5** Parent deletes own account | P with ≥1 linked child | Settings → Delete account (the list mentions the linked children) → password → Delete → confirm | Logcat shows every teardown step from `contracts/teardown.md` **in the documented order**: marker, filtering off, cancel work and alarm, stop services, each wipe, prefs, notice, tokens, final cancel, complete. The total teardown duration is logged and is under 2 s. The app lands on Login with "Account deleted". There is **no** "session ended" or "session expired" toast. Force-stop and cold start: Login, empty email field, no names. `adb shell dumpsys activity services com.safeguard.parentalcontrol` lists no running services. Postman sanity for P **and** for each child returns 401. Also watch the cold start for the pre-existing SyncWorker "Session expired" toast (research R6-1) and record it if it appears. |
| **V5b** Google self-delete | Google-only parent | Delete account | DELETE mode is pre-selected. Delete stays disabled until exactly `DELETE` is typed (`delete` or `DELETE ` keeps it disabled). Success as in V5. |
| **V6** Unlinked child self-deletes | C not linked, content filtering ON, some flagged images in review | Settings → Delete account | The warning says blurred photos can no longer be restored. After success: the VPN key icon and the monitoring notification disappear. Login shows "Account deleted" and the uninstall note with an "Open app settings" button. For 1 minute: no service restarts (`dumpsys` stays empty). Postman / parent side: **no** `vpn_disconnected` or `service_stopped` alert. `adb shell run-as com.safeguard.parentalcontrol ls files/` shows no `image_backup`. Reboot: nothing starts. |
| **V7** Airplane mode | P | Enable airplane mode → Delete account → confirm | "Nothing was deleted" with Retry. The password is kept. Turn airplane mode off → Retry → success. |
| **V8** Double tap / rotation | P | Tap confirm twice quickly; rotate during loading; press Back during loading | Logcat shows exactly **one** `start` line. Back is blocked while loading. After rotating, the loading state is still shown and the outcome is still delivered. |
| **V9** Killed mid-request | P, a slow network (emulator network speed set to GPRS) | Confirm, then `adb shell am force-stop com.safeguard.parentalcontrol` immediately → relaunch | If the server deleted the account: `GET auth/me` returns 401, teardown runs, and the neutral "session ended" wording appears (not "Account deleted"). If it did not: 200, the marker is cleared, and the user is still signed in. The monitoring service does not start until recovery has finished. |
| **V9b** Lost response | P. Turn on airplane mode the moment the request leaves (or drop the network through the emulator proxy after the request is sent) | Confirm → "Nothing was deleted" with Retry appears → turn airplane mode off → Retry | If the server had deleted the account, Retry gets a 401 and the recovery teardown runs, with neutral wording, never "Account deleted", and a fresh-install state. The marker is **not** lost when the network error happens. |
| **V10** Session already expired | P, revoke the session via Postman (logout-all or the token) | Delete account → confirm | The "session ended" flow, and Login. It **never** says "Account deleted". |
| **V11** RTL | Language set to Arabic | Run V1, V4 and V5 | The consequences list, fields, dialogs and messages are all in Arabic and right-to-left. Typing an Arabic child name matches exactly. |
| **V12** Help link | Any signed-in user | Settings → About → "How account deletion works" | The browser opens `{root}/account-deletion`. With no browser available, a Snackbar shows the URL. |
| **V14** Tamper matrix (SC-009) | C is **linked** to P, all protection on | On C, one at a time: (a) Settings → Apps → Haris → Clear data; (b) revoke C's session through Postman; (c) airplane mode for 10 minutes; (d) move the device clock forward 30 days; (e) start a self-deletion, then force-stop the app mid-request and relaunch; (f) sign in again and repeat (e) with airplane mode on | In **every** case protection stays on, or the app goes back to setup and requires the parent's consent again (after (a), which clears the gates). In **no** case does a deletion marker or a 401 on its own stop monitoring on a linked child. The backend still receives the existing tamper alerts where they apply. Record the results for each case. |
| **V13** Unlink wording | P | Children → the existing Remove action | Reads "Stop monitoring (account kept)". The dialog explains that the account is kept. Its behaviour is unchanged (`DELETE family/link`). |

## Pass criteria mapping

- SC-001: V5 (3 taps from opening Settings).
- SC-002: V1–V10.
- SC-003: V5, V6, V9.
- SC-004: V2.
- SC-005: V6.
- SC-006: V8.
- SC-008: V6.
- SC-009: V3, V4 and **V14** (protection stays on; the known limitation is recorded).
- FR-023: V4d.
