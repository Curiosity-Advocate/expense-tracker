## Consolidated Decisions — Where We Stand

| Decision            | Confirmed Choice                                                |
| ------------------- | --------------------------------------------------------------- |
| Async boundary      | Option B — separate worker process                              |
| Job queue V1        | DB table with `FOR UPDATE SKIP LOCKED`                          |
| Job queue evolution | Redis Streams                                                   |
| Worker↔DB access    | Service layer abstraction (Repository pattern)                  |
| Push notifications  | Observer pattern in worker → FCM in V2                          |
| Basiq token storage | Bitwarden Secrets Manager + minimum CDR scopes                  |
| Prediction module   | Strategy pattern with typed `PredictionContext` + `canHandle()` |
| Cold storage        | Year-based PostgreSQL partitions + dynamic registry table       |
| Archival trigger    | Scheduled job, Dec 1st creates partition, Jan 1st archives      |

---

## API Contract

A few conventions before we start that apply to every endpoint:

**Base URL:** `/api/v1` — versioned from day one. When you make breaking changes, you add `/v2`
without removing `/v1` until clients migrate. This is non-negotiable for a public API.

**Auth header:** Every endpoint except `/auth/*` requires `Authorization: Bearer <token>`.

**Error envelope:** Every error response follows one shape:

```json
{
        "error": {
                "code": "EXPENSE_NOT_FOUND",
                "message": "No expense found with id 123",
                "timestamp": "2026-04-12T10:30:00Z",
                "traceId": "abc-123"
        }
}
```

**Success envelope:** Every collection response is paginated:

```json
{
  "data": [...],
  "pagination": {
    "page": 1,
    "pageSize": 20,
    "totalItems": 143,
    "totalPages": 8
  }
}
```

Single resource responses return the object directly under `"data"`.

**The `traceId`** is generated at the API gateway layer for every request. It flows through logs in
both the API and worker processes. This is your V1 substitute for distributed tracing — cheap to
implement, valuable immediately.

---

## Module 1 — Authentication

### POST `/api/v1/auth/register`

Creates a new user account.

**Request:**

```json
{
        "username": "john_doe",
        "email": "john@example.com",
        "password": "plaintext_password"
}
```

**Response `201 Created`:**

```json
{
        "data": {
                "userId": "uuid",
                "username": "john_doe",
                "email": "john@example.com",
                "createdAt": "2026-04-12T10:00:00Z"
        }
}
```

**Design notes:**

- Password is BCrypt hashed server-side before storage. The plaintext never touches the DB.
- Email must be verified before the account is active — even in V1 a verification flag prevents spam
  accounts. You don't need to send an actual email in V1; just set `emailVerified = true`
  programmatically for now with a TODO comment.
- Return `409 Conflict` if username or email already exists. Do not specify which one — that is a
  user enumeration vulnerability.

---

### POST `/api/v1/auth/login`

Issues a session token.

**Request:**

```json
{
        "username": "john_doe",
        "password": "plaintext_password"
}
```

**Response `200 OK`:**

```json
{
        "data": {
                "accessToken": "jwt_token",
                "expiresAt": "2026-04-19T10:00:00Z",
                "tokenType": "Bearer"
        }
}
```

**Design notes:**

- V1 token is long-lived (7 days). The `expiresAt` field is present from day one so the mobile
  client is already written to handle expiry — swapping to short-lived tokens later requires no
  client changes.
- Failed login always returns `401 Unauthorized` with a generic message. Never say "wrong password"
  vs "user not found" — again, enumeration.
- Implement login attempt rate limiting at the API gateway layer: 5 failed attempts within 10
  minutes triggers a 15-minute lockout. This is your brute force protection before MFA arrives.

---

### POST `/api/v1/auth/logout`

Invalidates the current token.

**Request:** No body. Token taken from header.

**Response `204 No Content`**

**Design note:** JWT tokens are stateless by design — you can't truly invalidate them without a
blocklist. You have two options: maintain a `revoked_tokens` table that the auth filter checks on
every request, or accept that logout is best-effort and rely on short expiry in V2. For V1 with
long-lived tokens, the revoked tokens table is the right call. It's a simple lookup and prevents a
stolen token from being used after the user logs out.

---

### POST `/api/v1/auth/refresh` _(V2 feature, define now)_

Exchanges a refresh token for a new access token.

**Flag this endpoint in your code as `@FutureFeature` with a comment.** Defining it now means your
mobile client can be written to call it — even if it returns `501 Not Implemented` in V1. This is
the cleanest upgrade path.

---

## Module 2 — User Management

### GET `/api/v1/users/me`

Returns the authenticated user's profile.

**Response `200 OK`:**

```json
{
        "data": {
                "userId": "uuid",
                "username": "john_doe",
                "email": "john@example.com",
                "isDiscoverable": false,
                "createdAt": "2026-04-12T10:00:00Z"
        }
}
```

**Design notes:**

Implement HTTPS (mandatory, not optional), add HSTS headers on your server (one line of Spring
Security config), and add certificate pinning to the Android app in V2. For V1, HTTPS alone is
acceptable given your threat model.

---

### PATCH `/api/v1/users/me`

Updates the authenticated user's profile. Only fields present in the request body are updated (this
is why it's PATCH not PUT).

**Request:**

```json
{
        "isDiscoverable": true
}
```

**Response `200 OK`:** Returns the updated user object.

**Design note:** `isDiscoverable` is the opt-in flag before another user can grant them access. It
defaults to `false` on registration.

---

### POST `/api/v1/users/me/access-grants`

User A grants User B access to their data.

**Request:**

```json
{
        "grants": [
                {
                        "granteeUsername": "jane_doe",
                        "accessLevel": "READ_ONLY",
                        "expiresInDays": 3
                },
                {
                        "granteeUsername": "bob_smith",
                        "accessLevel": "READ_ONLY",
                        "expiresInDays": 1
                }
        ]
}
```

**Response `201 Created`:**

```json
{
        "data": {
                "grantId": "uuid",
                "grantorUserId": "uuid",
                "granteeUserId": "uuid",
                "accessLevel": "READ_ONLY",
                "expiresAt": "2026-04-15T10:00:00Z"
        }
}
```

**Design notes:**

- Server must verify `granteeUsername` has `isDiscoverable = true` before creating the grant. Return
  `403 Forbidden` with code `USER_NOT_DISCOVERABLE` if not.
- `expiresInDays` is validated: minimum 1, maximum 10.
- `accessLevel` is an enum: `READ_ONLY` or `FULL`. Define what FULL means precisely — in V1 probably
  just read + write on expenses, never admin actions.

All-or-nothing for V1. Partial success with 207 as a future enhancement. The validation error
response should list exactly which usernames failed and the reason, so the caller can correct and
resubmit.

---

### DELETE `/api/v1/users/me/access-grants/{grantId}`

Revokes an access grant early.

**Response `204 No Content`**

---

### GET `/api/v1/users/me/access-grants`

Lists all grants the current user has given.

**Response `200 OK`:** Array of grant objects.

---

## Module 3 — Expense Management

This is your most complex module. I want to flag a design decision before listing endpoints.

**Flag — Expense creation vs Bank-imported expenses**

You will have two sources of expenses: manually entered by the user, and imported from Basiq. They
end up in the same table but have different fields and different validation rules. You have two
design options:

Option A is a single `POST /expenses` endpoint with a `source` field (`MANUAL` vs `BANK_IMPORT`).
The bank worker calls the same endpoint the user calls.

Option B has separate endpoints: `POST /expenses` for manual entry, and an internal service method
(not an HTTP endpoint) for bank imports that the worker calls through the service layer.

Option B is cleaner — manual and bank imports have genuinely different validation requirements (bank
imports have an `externalTransactionId`, manual entries don't). Option A conflates two different
operations. I recommend Option B.

---

### POST `/api/v1/expenses`

Manually creates an expense.

**Request:**

```json
{
        "idempotencyKey": "client-generated-uuid",
        "amount": 42.5,
        "merchantName": "Woolworths",
        "date": "2026-04-12",
        "categories": ["GROCERIES", "HOUSEHOLD"],
        "notes": "Weekly shop",
        "paymentMethod": "CREDIT_CARD",
        "bankAccountId": "uuid"
}
```

**Response `201 Created`:**

```json
{
        "data": {
                "expenseId": "uuid",
                "amount": 42.5,
                "merchantName": "Woolworths",
                "date": "2026-04-12",
                "categories": ["GROCERIES", "HOUSEHOLD"],
                "categoryWeights": {
                        "GROCERIES": 21.25,
                        "HOUSEHOLD": 21.25
                },
                "notes": "Weekly shop",
                "paymentMethod": "CREDIT_CARD",
                "bankAccountId": "uuid",
                "source": "MANUAL",
                "aiCategorised": false,
                "createdAt": "2026-04-12T10:30:00Z"
        }
}
```

**Design notes:**

- `categoryWeights` is computed server-side, never sent by the client. Even split of
  `amount / categories.length`. The client sends categories, the server assigns weights.
- `date` is a date, not a datetime. The time of purchase is rarely known and not required.
- `bankAccountId` is optional — not every manual expense is tied to a tracked account.

The cleanest design is a system-reserved bank account record created automatically when a user
registers. This record represents cash and is never editable or deletable.

```json
{
        "bankAccountId": "system-reserved-cash-uuid-per-user",
        "institutionName": "Cash",
        "accountName": "Cash",
        "accountNumberMasked": null,
        "isSystemAccount": true,
        "status": "ACTIVE"
}
```

Making bankAccountId mandatory (with cash as a valid option) gives you clean data — every expense is
always associated with a payment source. This is better than a nullable bankAccountId that you have
to handle specially everywhere in your query logic. The UI picklist you described is the right UX —
it shows all registered bank accounts plus the system Cash option. The user never manually types an
account ID.

- Manual entry — client-side draft When the user submits an expense and the server returns an error
  (network failure, DB down, 5xx), the responsibility for not losing that data sits with the client,
  not the server. The server either committed the write or it didn't - It cannot save a draft on
  behalf of a failed request. The Android app should:

1. Attempt the POST to /api/v1/expenses
1. On any failure (network timeout, 5xx), save the request payload to local SQLite on the device as
   a draft
1. A background job on the device retries the draft when connectivity is restored
1. On success, delete the local draft

This is the standard offline-first mobile pattern. The server gets a clean idempotent retry — you
add an idempotencyKey field to the expense creation request (a UUID generated by the client). The
server uses INSERT ... ON CONFLICT (idempotency_key) DO NOTHING to safely handle retries.

---

### GET `/api/v1/expenses`

Returns expenses for the authenticated user. This is your primary query endpoint.

**Query parameters:**

```
dateFrom        date        optional  e.g. 2026-01-01
dateTo          date        optional  e.g. 2026-04-12
merchantName    string      optional  partial match
categories      string[]    optional  comma-separated
paymentMethod   string      optional
bankAccountId   uuid        optional
minAmount       decimal     optional
maxAmount       decimal     optional
source          string      optional  MANUAL | BANK_IMPORT | ALL (default ALL)
includeArchived boolean     optional  default false
page            int         default 1
pageSize        int         default 20, max 100
sortBy          string      default date
sortOrder       string      ASC | DESC, default DESC
```

**Response `200 OK`:** Paginated expense array.

**Design notes:**

- This endpoint reads from the active partition by default. `includeArchived=true` crosses into the
  archive partition and gets a slower response — you should return a response header
  `X-Data-Source: INCLUDES_ARCHIVED` so the client can warn the user.
- This endpoint hits the **read replica** not the primary. The 60-second staleness NFR applies here.
- Indexes needed: composite index on `(user_id, date, category)` and `(user_id, merchant_name)`.
  This is your most-hit endpoint — get the indexes right before go-live.

---

### GET `/api/v1/expenses/{expenseId}`

Returns a single expense by ID.

**Response `200 OK`:** Single expense object.

**Design note:** Returns `404 Not Found` if the expense exists but belongs to a different user —
never leak existence. Same pattern for every resource endpoint.

---

### PATCH `/api/v1/expenses/{expenseId}`

Updates a manually entered expense. Bank-imported expenses can only have their categories updated —
amount, date, and merchant are immutable once imported to preserve the integrity of the bank record.

**Request:**

```json
{
        "categories": ["GROCERIES"],
        "notes": "Updated note"
}
```

**Response `200 OK`:** Updated expense object.

**Design note:** Server enforces which fields are mutable based on `source`. A bank-imported expense
that receives an `amount` field in the PATCH body returns `422 Unprocessable Entity` with code
`FIELD_IMMUTABLE_FOR_BANK_IMPORT`.

---

### DELETE `/api/v1/expenses/{expenseId}`

Soft-deletes a manually entered expense. Bank-imported expenses cannot be deleted — they can only be
archived.

**Response `204 No Content`**

**Design note:** This is a soft delete — sets `deletedAt` timestamp, never removes the row. Your
"records are never deleted" NFR applies here too. The GET endpoints filter out
`deletedAt IS NOT NULL` by default.

---

### GET `/api/v1/expenses/summary`

Returns aggregated expense data. This hits the materialized views, not raw expense tables.

**Query parameters:**

```
dateFrom        date        required
dateTo          date        required
groupBy         string      CATEGORY | MERCHANT | BANK_ACCOUNT | MONTH (required)
```

**Response `200 OK`:**

```json
{
        "data": {
                "totalAmount": 1243.5,
                "periodFrom": "2026-04-01",
                "periodTo": "2026-04-12",
                "groups": [
                        {
                                "groupKey": "GROCERIES",
                                "totalAmount": 423.75,
                                "transactionCount": 8,
                                "percentageOfTotal": 34.1
                        }
                ],
                "dataFreshAsOf": "2026-04-12T10:29:45Z"
        }
}
```

**Design note:** `dataFreshAsOf` tells the client exactly how stale the data is — derived from the
materialized view's last refresh timestamp. The client can show "data as of 45 seconds ago" in the
UI. This is how you make bounded staleness visible to the user rather than hiding it.

---

## Module 4 — Targets and Predictions

### POST `/api/v1/targets`

Creates a spending target.

**Request:**

```json
{
        "targetType": "CATEGORY",
        "category": "DINING",
        "amount": 400.0,
        "periodType": "MONTHLY",
        "periodYear": 2026,
        "periodMonth": 4
}
```

**Response `201 Created`:**

```json
{
        "data": {
                "targetId": "uuid",
                "targetType": "CATEGORY",
                "category": "DINING",
                "amount": 400.0,
                "periodType": "MONTHLY",
                "periodYear": 2026,
                "periodMonth": 4,
                "createdAt": "2026-04-12T10:00:00Z"
        }
}
```

**Design note:** `targetType` is `CATEGORY` or `TOTAL`. A `TOTAL` target has no `category` field.
Validation enforces this — `category` present with `targetType: TOTAL` returns `422`.

---

### GET `/api/v1/targets`

Lists all targets for the current user, optionally filtered by period.

**Query parameters:**

```
periodYear      int         optional
periodMonth     int         optional
targetType      string      optional
```

---

### GET `/api/v1/targets/{targetId}/status`

The most important read endpoint in this module. Returns current spend vs target with prediction.

**Response `200 OK`:**

```json
{
        "data": {
                "targetId": "uuid",
                "targetAmount": 400.0,
                "spentAmount": 187.5,
                "remainingAmount": 212.5,
                "percentageUsed": 46.9,
                "prediction": {
                        "projectedAmount": 468.75,
                        "willExceedTarget": true,
                        "projectedExceedanceAmount": 68.75,
                        "strategyUsed": "NAIVE_DAILY_RATE",
                        "confidence": "LOW",
                        "basedOnDaysElapsed": 12,
                        "daysRemainingInPeriod": 19
                },
                "dataFreshAsOf": "2026-04-12T10:29:45Z"
        }
}
```

**Design notes:**

- `strategyUsed` exposes which `PredictionStrategy` ran — directly surfacing your Strategy pattern
  in the API response. This is a talking point: the client can display "prediction based on daily
  average" vs "prediction based on historical patterns".
- `confidence` is `LOW | MEDIUM | HIGH` — the strategy sets this. A naive daily rate on day 2 of the
  month is `LOW` confidence. A historical pattern strategy with 3 months of data is `HIGH`.
- This endpoint's P95 target is < 2s per NFR-001.

- Yes, and for a reason more practical than it might seem. When you change the
  NaiveDailyRateStrategy algorithm, historical predictions that were made using the old version
  become inexplicable — you can't reproduce what the system said last month. The version sits on the
  strategy itself and is recorded when a prediction is persisted: javapublic interface
  PredictionStrategy { PredictionResult predict(PredictionContext context); String strategyName();
  String strategyVersion(); // e.g. "NAIVE_DAILY_RATE_v1.2" boolean canHandle(PredictionContext
  context); } The prediction result stored in the DB (or returned in the API response) includes
  strategyName and strategyVersion. If a user asks "why did it predict $468 last month?", you can
  reproduce it by re-running NAIVE_DAILY_RATE_v1.2 against the same context snapshot. This also
  means you should never modify an existing strategy class. Create NaiveDailyRateStrategyV2 as a new
  class. The old version remains in the codebase and can still be instantiated for historical
  reproduction. This is the Open/Closed Principle applied to the strategy implementations
  themselves, not just the engine.

---

### DELETE `/api/v1/targets/{targetId}`

Deletes a target. Soft delete — historical target data is retained for trend analysis.

**Response `204 No Content`**

---

## Module 5 — Bank Integration

### POST `/api/v1/bank/sync`

Manually triggers a bank sync for the authenticated user. This is the only way to initiate a sync —
no scheduled trigger.

**Request:**

```json
{
        "bankAccountIds": ["uuid1", "uuid2"]
}
```

**Response `202 Accepted`:**

```json
{
        "data": {
                "jobId": "uuid",
                "status": "QUEUED",
                "triggeredAt": "2026-04-12T10:30:00Z",
                "estimatedCompletionSeconds": 30
        }
}
```

**Design notes:**

- `202 Accepted` not `200 OK` — the work hasn't happened yet, it's been queued. This is the correct
  HTTP semantic for async operations. The mobile client uses the `jobId` to poll for completion.
- If a sync job is already running for this user, return `409 Conflict` with code
  `SYNC_ALREADY_IN_PROGRESS`. Prevent duplicate jobs.

- Proposed rate limit: maximum 5 sync triggers per user per 24-hour window. Rationale: Basiq charges
  per user per month, not per sync call, so this is more about preventing abuse and Basiq API
  hammering than cost control. Implementation in Spring Boot: Bucket4j is the standard library. It
  integrates with Spring and supports per-user rate limits using the user ID as the bucket key.
  In-memory for V1, backed by Redis when you evolve to Redis Streams (natural shared state for
  distributed rate limiting).

Response when rate limit exceeded: HTTP 429 Too Many Requests Headers: X-RateLimit-Limit: 5
X-RateLimit-Remaining: 0 X-RateLimit-Reset: 1713000000 (unix timestamp of window reset)

- You're identifying a real behaviour: Basiq often returns a transaction twice — once as `PENDING`
  and once as `POSTED` when it settles. These have different transaction IDs but represent the same
  real-world event.

```json
POST /api/v1/bank/duplicates/{duplicateId}/resolve
{
  "primarySourceId": "expense-uuid-to-keep",
  "duplicateIds": ["expense-uuid-1", "expense-uuid-2"],
  "action": "MERGE_PREFER_PRIMARY"
}
```

Design note on Basiq's PENDING/POSTED behaviour: your duplicate detection logic in the worker should
specifically handle this case. Detection heuristic: same merchant name + same amount + date within 5
days + one has status `PENDING` and one has `POSTED`. Flag these with a
`PROBABLE_PENDING_SETTLEMENT` duplicate type so the UI can display a more specific message than just
"possible duplicate". The `duplicateIds` as a list means one resolution action can handle three-way
duplicates (PENDING + POSTED + manual entry all for the same coffee purchase).

---

### GET `/api/v1/bank/sync/{jobId}`

Polls the status of a sync job.

**Response `200 OK`:**

```json
{
        "data": {
                "jobId": "uuid",
                "status": "COMPLETED",
                "triggeredAt": "2026-04-12T10:30:00Z",
                "completedAt": "2026-04-12T10:30:28Z",
                "transactionsImported": 14,
                "duplicatesDetected": 2,
                "errors": []
        }
}
```

**`status` values:** `QUEUED → PROCESSING → COMPLETED | FAILED | DEAD_LETTER`

---

### GET `/api/v1/bank/accounts`

Lists connected bank accounts for the authenticated user.

**Response `200 OK`:**

```json
{
        "data": [
                {
                        "bankAccountId": "uuid",
                        "institutionName": "Commonwealth Bank",
                        "accountName": "Smart Access",
                        "accountNumberMasked": "****1234",
                        "lastSyncedAt": "2026-04-12T08:00:00Z",
                        "status": "ACTIVE"
                }
        ]
}
```

---

### GET `/api/v1/bank/duplicates`

Returns flagged duplicate transactions awaiting user resolution.

**Response `200 OK`:**

```json
{
  "data": [
    {
      "duplicateId": "uuid",
      "bankImportedExpense": { ...expense object... },
      "manualExpense": { ...expense object... },
      "similarityScore": 0.94,
      "detectedAt": "2026-04-12T10:31:00Z",
      "status": "PENDING_REVIEW"
    }
  ]
}
```

---

### POST `/api/v1/bank/duplicates/{duplicateId}/resolve`

User resolves a flagged duplicate.

**Request:**

```json
{
        "action": "KEEP_BOTH"
}
```

**`action` values:** `KEEP_BOTH | MERGE_PREFER_BANK | MERGE_PREFER_MANUAL`

**Response `200 OK`:** Returns the resolved expense(s).

**Design note:** When `action` is a MERGE variant, the resulting expense has
`mergedFrom: ["uuid1", "uuid2"]` and `isMerged: true` set as immutable fields. The DB trigger fires
on any subsequent attempt to UPDATE these fields and raises an exception. The audit trail is
permanent.

---

## Module 6 — AI Categorisation

### GET `/api/v1/ai/suggestions`

Returns pending AI category suggestions for the authenticated user.

**Response `200 OK`:**

```json
{
        "data": [
                {
                        "suggestionId": "uuid",
                        "expenseId": "uuid",
                        "merchantName": "WOOLWORTHS 1234 SYDNEY",
                        "suggestedCategories": ["GROCERIES"],
                        "confidence": 0.94,
                        "status": "PENDING_REVIEW",
                        "createdAt": "2026-04-12T10:35:00Z"
                }
        ]
}
```

---

### POST `/api/v1/ai/suggestions/{suggestionId}/resolve`

User accepts or overrides a suggestion.

**Request:**

```json
{
        "expenseId": "uuid",
        "categories": [
                {
                        "original": null,
                        "target": "GROCERIES"
                },
                {
                        "original": null,
                        "target": "HOUSEHOLD"
                }
        ]
}
```

**Response `200 OK`:** Returns the updated expense.

**Design note:**

What the server does with this:

`original: null, target: X` → new category being added (AI acceptance) `original: X, target: Y` →
category being changed (user override) `original: X, target: null` → category being removed

## The `original` field serves as an optimistic lock — if the expense's current categories don't match the `original` values in the request, someone else modified it between the suggestion being shown and the user resolving it. Return `409 Conflict` with code `CATEGORY_MODIFIED_SINCE_SUGGESTION`. This prevents silent overwrites in a multi-device scenario.

## Module 7 — Dead Letter and Observability

### GET `/api/v1/system/dead-letters`

Returns failed jobs that have exhausted all retry cycles.

**Response `200 OK`:**

```json
{
        "data": [
                {
                        "deadLetterId": "uuid",
                        "jobType": "BANK_SYNC",
                        "jobId": "uuid",
                        "failedAt": "2026-04-11T02:00:00Z",
                        "cyclesAttempted": 3,
                        "lastError": "Basiq API timeout after 30s",
                        "payload": {"bankAccountIds": ["uuid1"]},
                        "status": "AWAITING_MANUAL_RETRY"
                }
        ]
}
```

---

### POST `/api/v1/system/dead-letters/{deadLetterId}/retry`

Manually re-queues a dead letter job.

**Response `202 Accepted`:** Returns a new `jobId`.

**Design note:** This endpoint should be restricted — only the owning user can retry their own dead
letter jobs. A future admin role could see all users' dead letters, but not in V1.

---

### POST /api/v1/auth/sudo

When User B calls `GET /api/v1/expenses?asUserId=<A's uuid>`, there should be positive confirmation
that User B intends this action and is not just a process or script abusing a leaked grant.

The simplest mechanism that gives you this: **re-authentication challenge**. When the `asUserId`
parameter is present, require the caller to include a short-lived **sudo token** obtained from a
separate endpoint:

```
POST /api/v1/auth/sudo
{ "password": "current_password" }
→ { "sudoToken": "short_lived_token", "expiresAt": "+15 minutes" }

GET /api/v1/expenses?asUserId=uuid
Headers:
  Authorization: Bearer <normal_token>
  X-Sudo-Token: <sudo_token>
```

The sudo token expires in 15 minutes. To use the `asUserId` feature, the user had to have
authenticated their password within the last 15 minutes. This is exactly how GitHub handles
dangerous operations (deleting a repo) and how AWS handles sensitive console actions.

**This is not over-engineered** — it's a well-established pattern called **step-up authentication**
and it's proportionate to the sensitivity of cross-user data access. The implementation is simple:
one new endpoint, one new short-lived token type, one additional header check in the filter chain
for requests containing `asUserId`.

---

### Bank Account Connection — Desktop Simplification

Assuming a desktop app removes the most awkward part of the OAuth flow: the Android deep link and
intent filter configuration. On desktop, the standard pattern is a **localhost redirect**.

Here's how the flow simplifies:

```
1. User clicks "Add Bank Account" in desktop app
2. App calls POST /api/v1/bank/accounts/connect
3. Server generates a CSRF state token, returns the Basiq consent URL
4. Desktop app opens the consent URL in the user's default browser
5. User logs into their bank inside Basiq's UI
6. User grants consent
7. Basiq redirects to http://localhost:{port}/oauth/callback?code=...&state=...
8. Desktop app has a temporary local HTTP listener on that port
9. App captures the auth code from the redirect, closes the listener
10. App calls POST /api/v1/bank/accounts/callback with the auth code
11. Server exchanges code for tokens, stores in Bitwarden, creates account record
12. Server returns the bank account object
```

The key difference is **step 7-9**: instead of a mobile deep link, the desktop app spins up a
temporary HTTP server on localhost (e.g. port 8765) purely to catch the OAuth redirect. This is the
same mechanism used by the AWS CLI, GitHub CLI, and Spotify's desktop app. It's a well-established
pattern, takes about 30 lines of code, and requires no OS-level registration.

**Security properties that remain unchanged:**

- CSRF state token still generated in step 3 and validated in step 11
- Your server never sees the user's bank credentials — Basiq handles that entirely
- The localhost listener closes immediately after capturing the code — no persistent open port

**One practical constraint:** the redirect URL registered with Basiq in your app configuration must
match exactly. You register `http://localhost:8765/oauth/callback` as your allowed redirect URI in
the Basiq dashboard. This is a one-time setup.

**The amended flow is now:** the backend endpoints are identical (`/connect` and `/callback`). The
only change is in the desktop client implementation, not in your API contract or server-side design.
Everything we defined holds.

---

## Confirming All Decisions Before Module Boundaries

Before we move forward let me do a clean confirmation pass so nothing is ambiguous going into the
next phase.

| #                        | Decision                                                         | Confirmed |
| ------------------------ | ---------------------------------------------------------------- | --------- |
| Auth                     | HTTPS + BCrypt, no client-side hashing                           | ✅        |
| Logout                   | Synchronous revocation, 7-day expiry V1                          | ✅        |
| Transport security       | HTTPS mandatory, HSTS headers                                    | ✅        |
| Access grants            | All-or-nothing bulk request                                      | ✅        |
| Failed manual save       | Client-side SQLite draft + idempotency key                       | ✅        |
| Cash payment option      | System-reserved account per user, auto-created at registration   | ✅        |
| Strategy versioning      | `strategyVersion()` on interface, never modify existing strategy | ✅        |
| Sync rate limiting       | Bucket4j, 5 syncs per user per 24h, rate limit headers           | ✅        |
| Duplicate resolution     | `primarySourceId` + `duplicateIds` list                          | ✅        |
| AI suggestion resolution | `expenseId` + `categories` array with `original`/`target`        | ✅        |
| Cross-user access        | Step-up auth via `X-Sudo-Token`                                  | ✅        |
| Bank account connection  | Desktop localhost OAuth listener, CSRF state token               | ✅        |
| DB abstraction           | Service layer interfaces, workers never touch SQL directly       | ✅        |
| Job queue V1             | DB table + `FOR UPDATE SKIP LOCKED`                              | ✅        |
| Job queue evolution      | Redis Streams                                                    | ✅        |
| Token storage            | Bitwarden Secrets Manager + minimum CDR scopes                   | ✅        |
| Cold storage             | Year-based PostgreSQL partitions + dynamic registry table        | ✅        |
| Prediction module        | Strategy pattern + typed `PredictionContext` + `canHandle()`     | ✅        |
| Push notifications       | Observer pattern V1 (polling) → FCM V2 (push)                    | ✅        |

Everything looks consistent. Shall we move to module boundaries and low-level design?
