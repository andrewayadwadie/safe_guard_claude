# API Contract: GET /family/parents

**Feature**: 004-child-pairing-gate | **Status**: UNCONFIRMED — pending backend verification (see Backend Coordination in plan.md)

## Request

```
GET {baseUrl}/family/parents
Authorization: Bearer <user JWT access token>   ← standard AuthInterceptor path, NOT the device token
```

- Caller role: **child** account.
- No query params, no body.
- Retrofit declaration (ApiService.kt, Family Link Endpoints section):

```kotlin
/** Get all parents linked to this child (child only). Uses the user Bearer token. */
@GET("family/parents")
suspend fun getLinkedParents(): Response<List<LinkedParent>>
```

## Response — 200 OK

JSON array of linked-parent objects. Field names are the client's **assumed** shape (TODO(backend): confirm):

```json
[
  {
    "id": 12,
    "parent_id": 7,
    "parent_email": "parent@example.com",
    "parent_name": "Jane Doe",
    "created_at": "2026-07-01T10:15:00Z"
  }
]
```

**No parent linked** → `200 OK` with `[]` (empty array). MUST NOT be a 404 — the client gate treats a successful empty list as *confirmed unpair* (flag → false) and any error as *unknown* (flag frozen). A 404 here would be classified as an error and freeze the flag, breaking confirmed-unpair semantics (SC-005).

## Client outcome mapping

| Backend response | `NetworkResult` | `hasLinkedParent` | Monitoring |
|---|---|---|---|
| 200 + non-empty array | `Success(list)` | → `true` | starts (runtime or next start) |
| 200 + `[]` | `Success(emptyList)` | → `false` | stays gated |
| 4xx / 5xx | `Error` | unchanged | unchanged |
| No connectivity / timeout | `Error` | unchanged | unchanged |

## Open items for backend

1. Confirm exact field names (`parent_id` / `parent_email` / `parent_name` / `created_at`) — client DTO is nullable-tolerant so mismatches don't crash, but nulls hide data.
2. Confirm child-role authorization with the **user** access token.
3. Confirm 200-empty (not 404) for the no-parents case.
4. Unlink propagation latency: client polls only at gated service starts; near-real-time requires a push signal (FCM/RTDB) — separate follow-up task.
