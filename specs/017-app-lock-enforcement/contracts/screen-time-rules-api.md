# Contract: `screen-time-rules` REST surface

**Feature**: 017-app-lock-enforcement | **Date**: 2026-10-18
**Base URL**: `https://bw.noor.net:8090/api/v1/`
**Client declarations**: `ApiService.kt:343-405` · **Models**: `Models.kt:680-753`
**Status**: the client side of this contract is what the app sends today. The server side is
**unverified** — the backend is a separate repository. Everything marked ❓ needs the backend
developer's confirmation; the blockers gate implementation phases 2–4.

No change to any endpoint, URL, verb or field name is requested by this feature. What changes is
*which fields the client sends per request*, and that an emptied list is sent as `[]` rather than
omitted.

---

## 1. `PUT /screen-time-rules/{device_id}` — the only write path

`ApiService.kt:358-362`. Called by the parent on every control change. Authorisation: parent bearer
token.

### 1a. Lock an app (and assert the policy is active)

Parent turns on the lock for `com.instagram.android`, master enforcement switch already on.

Request:

```http
PUT /api/v1/screen-time-rules/42
Authorization: Bearer <parent access token>
Content-Type: application/json
```

```json
{
  "blocked_apps": ["com.facebook.katana", "com.instagram.android"],
  "is_active": true
}
```

Note what is **absent**: `daily_limit`, `bedtime_*`, `study_time_*`, `app_limits`,
`is_device_locked`, `device_locked_message`. Per **A7** those must survive untouched.

Expected response — the full updated row, `200`:

```json
{
  "id": 7,
  "device_id": 42,
  "daily_limit": 7200,
  "bedtime_enabled": true,
  "bedtime_start": "21:00",
  "bedtime_end": "07:00",
  "study_time_enabled": false,
  "study_time_start": null,
  "study_time_end": null,
  "study_time_allowed_apps": null,
  "is_device_locked": false,
  "device_locked_message": null,
  "device_locked_at": null,
  "app_limits": {"com.google.android.youtube": 1800},
  "blocked_apps": ["com.facebook.katana", "com.instagram.android"],
  "is_active": true,
  "created_at": "2026-10-01T09:14:22Z",
  "updated_at": "2026-10-18T11:02:05Z"
}
```

`daily_limit`, `bedtime_*` and `app_limits` coming back **unchanged** is the A7 proof.

### 1b. Unlock the last locked app — the FR-007 case

Parent removes the final entry. The client must send an explicit empty array:

```json
{
  "blocked_apps": []
}
```

❓ **A1 / blocker**: does `[]` clear the stored list, and does the response echo
`"blocked_apps": []` (or `null`)? Today the client sends **no `blocked_apps` key at all** in this
case, because of `.ifEmpty { null }` at `ScreenTimeLimitsViewModel.kt:396` plus Gson null-omission —
so the unlock is unrepresentable. If the backend rejects `[]` or treats it as "no change", the fix
needs a different clearing convention (a sentinel, or `DELETE .../blocked-app/{pkg}` per Q6).

Same question applies to `"app_limits": {}` and `"study_time_allowed_apps": []`.

### 1c. Change one unrelated control

Parent drags the daily limit to 1 h 30 m. Only that field is sent:

```json
{
  "daily_limit": 5400
}
```

`blocked_apps` must be untouched in the response. This is the other half of the A7 check, and the
FR-003a concurrency guarantee rests on it.

### 1d. Clear the daily limit

Existing, already-working convention — kept, documented here because it is the precedent that
establishes the partial-update semantics:

```json
{
  "daily_limit": 0
}
```

`0` means "no limit"; omitting the key would mean "leave unchanged"
(`ScreenTimeLimitsViewModel.kt:363-370`).

### 1e. Rules row does not exist yet

❓ **A6 / blocker**: does `PUT` upsert, or `404`? The client never calls
`POST /screen-time-rules/{device_id}` — `createScreenTimeRules` (`ScreenTimeRulesRepository.kt:115-145`,
`ApiService.kt:349-353`) has zero call sites — so a child device that has never had rules depends on
`PUT` creating the row. If it `404`s, the parent's first-ever lock on a device silently fails and
this feature must add a create-then-update path.

❓ **A2 / non-blocker after FR-008b**: what is `is_active` on a row the backend creates? The client
now always sends it explicitly alongside a lock change, so the default no longer decides behaviour.
Still worth knowing, because a `false` default would have silently disabled every rule set created
before this feature shipped.

---

## 2. `GET /screen-time-rules/{device_id}` — the only read path

`ApiService.kt:343-344`. Called by the child. Response shape is identical to 1a.

❓ **A3 / blocker**: may a **child** account call this for its own `device_id`, with the normal
bearer token and no `X-Device-Token`? That is exactly what the enforcement loop does
(`MonitoringService.kt:594` → `ScreenTimeRulesRepository.getCachedRules` → `:50`). A `401`/`403`
here is indistinguishable, from the child's side, from "no rules" — and today it leaves
`_cachedRules` untouched while logging only `getCachedRules: Failed to fetch rules`.

❓ **A4 / blocker**: is the `device_id` in `screen-time-rules` the same integer the parent reads from
`GET /devices` and the child stores as its `deviceDbId`
(`deviceRepository.getCurrentDeviceId()`, `MonitoringService.kt:556`)? If the parent writes against
one id space and the child reads another, every hop logs success and nothing is ever enforced.

**404 semantics** — relied on, already implemented: `404` is treated as "no rules configured", not an
error, and clears the cache (`ScreenTimeRulesRepository.kt:68-74`). This feature extends that to the
persisted copy (see `research.md` R3), so **`404` must mean "this device genuinely has no rules"** and
must never be returned for an auth, routing or lookup problem. ❓ **non-blocker**, but a wrong `404`
unlocks a child device.

---

## 3. Endpoints this feature deliberately does not call

| Endpoint | Client wrapper | Why unused |
|---|---|---|
| `POST /screen-time-rules/{id}` | `createScreenTimeRules` | zero call sites today; A6 assumes `PUT` upserts |
| `POST /screen-time-rules/{id}/blocked-app?package_name=` | `ScreenTimeRulesRepository.addBlockedApp:254-273` | never exercised; would be a second write path (`research.md` R9) |
| `DELETE /screen-time-rules/{id}/blocked-app/{package_name}` | `ScreenTimeRulesRepository.removeBlockedApp:274-289` | same |

❓ **Q6 / non-blocker**: do the two `blocked-app` routes exist and work? If yes they are a worthwhile
later optimisation — add/remove by package is immune to the same-field last-write-wins case in
FR-003a. Not adopted now because the feature should not depend on two routes with no production
traffic.

---

## 4. Request-building rules the client now follows

1. One changed control → one request carrying only that control's field(s).
2. A list or map that became empty → sent explicitly as `[]` / `{}`, never omitted.
3. A lock being **added** → also send `"is_active": true`, but only when the parent's master
   enforcement switch is already on (FR-008b). Never send `"is_active": true` to re-enable a policy
   the parent switched off.
4. At most one request in flight for the screen at a time; rapid changes to one control coalesce to
   the newest value (`research.md` R7).
5. Never send `null` for a field meaning "clear" — `null` is the wire-absent marker and means
   "leave unchanged".

## 5. Logging constraints on this surface

OkHttp body logging is debug-only and `X-Device-Token` is redacted (`NetworkModule.kt:81`). New
`AppLock` entries log package names, HTTP status codes, field names and list sizes. They must not log
bearer tokens, `X-Device-Token`, the parent's email, or any monitored content (FR-025, and the
privacy contract inherited from `AlertPipe`).
