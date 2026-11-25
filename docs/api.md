# API contract

The single source for the HTTP interface between the app and the Worker. Backend tests and Kotlin tests both validate against the JSON files in `contract/fixtures/`, which must match this document. Sections are added per milestone.

All routes are under `/v1`. Bodies are JSON unless stated. Times are ISO 8601 UTC strings. Ids are lowercase UUIDs generated on the client; anything else is rejected with `invalid_id`.

## Errors

Every error has this body, with the HTTP status shown in the tables below:

```json
{ "error": { "code": "snake_case_code", "message": "English text for logs, not for users" } }
```

Codes used everywhere: `invalid_body` (400), `invalid_id` (400), `unauthenticated` (401), `not_found` (404), `rate_limited` (429).

The `POST /v1/webhooks/revenuecat` route (Milestone M3) is called by RevenueCat, not by the app, and uses its own authorisation.

## Authentication

- Header: `Authorization: Bearer v1.<userId>.<secret>.<tag>`. The app treats the token as opaque and never parses it.
- `userId` is 43 base64url characters. `secret` is 43 base64url characters (256 random bits). `tag` is 43 base64url characters: HMAC-SHA256 over `v1.<userId>.<secret>` with a server key, so the Worker rejects a forged token before it touches any user's storage.
- A missing, malformed, forged, unknown or expired token gives 401 `unauthenticated`.
- A resource id that belongs to another user gives 404 `not_found`, never 403.

## Milestone M1

### Session

| Route | Request | Success | Errors |
|-------|---------|---------|--------|
| `POST /session/nonce` (no auth) | empty | 200 `{ "nonce": string }`, valid 5 minutes, single use | 429 |
| `POST /session` (no auth) | `SessionRequest` | 200 `SessionResponse` | 400 `invalid_body`; 400 `age_confirmation_required`; 401 `invalid_turnstile`; 401 `invalid_nonce`; 401 `invalid_token`; 409 `account_deleting`; 411 `length_required`; 429; 503 `provider_unavailable` (the provider's signing keys cannot be fetched; the app shows "try again later") |
| `DELETE /session` | none | 204 | 401 |
| `GET /me` | none | 200 `Me` | 401 |

```jsonc
// SessionRequest
{
  "provider": "apple",            // "apple" | "google"; "test" exists only in the local entry
  "idToken": "<provider id token>",
  "nonce": "<value from /session/nonce>",
  "turnstileToken": "<token from the Turnstile widget>",
  "ageConfirmed": true,           // must be true
  "appleAuthCode": "<code>"       // optional, iOS only
}
// SessionResponse
{ "token": "v1.<userId>.<secret>.<tag>", "userId": "<43 chars>", "expiresAt": "2026-12-24T10:00:00Z" }
// Me
{
  "userId": "<43 chars>",
  "state": "active",              // "active" | "suspended"
  "suspension": null,             // or { "reason": "<code>" }
  "entitlement": { "active": false, "expiresAt": null },
  "quota": { "freeImportUsed": false, "monthUsed": 0, "monthLimit": 30 },
  "storage": { "usedBytes": 0, "capBytes": 52428800 },
  "consentVersion": null          // or an integer
}
```

Order of checks in `POST /session`: body, age confirmation, Turnstile, nonce signature and expiry, id token (signature against the provider's keys, issuer, audience, expiry, nonce claim), then the user's object consumes the nonce and creates the session. The nonce claim is the nonce as issued for Google and its SHA-256 (lowercase hex) for Apple. Sessions last 30 days; the expiry is extended at most once per day on use.

### Recipes

```jsonc
// Recipe (as returned by the server)
{
  "id": "0b9f6c1e-3a52-4d0f-9a43-2f1d0c6b7e11",
  "rev": 7,                        // server-assigned, increases with every change of this user
  "deleted": false,
  "updatedAt": "2026-11-24T18:00:00Z",
  "title": "Kartoffelsalat",       // 1..200 characters
  "description": "",               // 0..2000
  "servings": 4,                   // 1..100 or null
  "prepMinutes": 20,               // 0..10000 or null
  "cookMinutes": 25,               // 0..10000 or null
  "ingredients": [                 // 0..100 items
    { "quantity": "1", "unit": "kg", "item": "Kartoffeln", "group": null }
  ],                               // quantity 0..40, unit 0..40, item 1..200, group 0..80 or null
  "steps": ["Kartoffeln kochen."], // 0..100 items, each 1..4000
  "tags": ["Beilage"],             // 0..20 items, each 1..40
  "sourceUrl": null,               // or a string up to 2048
  "sourceType": "manual",          // "manual" | "photo" | "url" | "shared"
  "language": "de",                // BCP 47, 2..35
  "imageIds": []                   // 0..5 distinct ids, in display order
}
```

- The client sends the same object without `rev`, `deleted` and `updatedAt` (`RecipeInput`).
- A deletion marker in a list has only `id`, `rev`, `deleted: true` and `updatedAt`.
- All strings are plain text. The server never interprets HTML or Markdown in them.

| Route | Request | Success | Errors |
|-------|---------|---------|--------|
| `GET /recipes?since=<rev>` | `since` optional, default 0 | 200 `{ "recipes": [Recipe or deletion marker], "rev": <cursor>, "more": <boolean> }`, ordered by `rev`, at most 200 entries per response | 410 `cursor_expired` when `since` is older than the purge horizon (the client then pulls with `since=0` and replaces its cache) |
| `PUT /recipes/:id` | `RecipeInput` (its `id` must equal the id in the path); header `If-None-Match: *` to create or `If-Match: "<rev>"` to update | 200 `Recipe` | 428 `precondition_required` (neither header); 412 `precondition_failed` (exists on create, or stale rev); 404 `not_found` (update of an id this user never had); 413 `recipe_too_large` (body over 64 KB); 413 `recipe_storage_cap` (the user's stored recipe text would exceed 20 MB); 409 `recipe_limit` (2,000 recipes); 400 `invalid_body` |
| `DELETE /recipes/:id` | none | 204, also when already deleted | 404 when the id never existed |
| `PUT /recipes/:id/images/:imageId` | raw bytes; `Content-Type` `image/jpeg`, `image/png` or `image/webp` | 201 `{ "id": "<imageId>", "size": 12345, "mime": "image/jpeg" }` | 404 (recipe); 413 `image_too_large` (over 1,572,864 bytes); 413 `storage_cap`; 409 `image_limit` (a sixth image); 415 `unsupported_media_type` (magic bytes do not match an allowed type) |
| `GET /images/:imageId` | none | 200 bytes with the stored `Content-Type`, `Cache-Control: private, immutable, max-age=31536000`, `X-Content-Type-Options: nosniff` and `Content-Security-Policy: sandbox` | 404 |

Rules:
- Paging: `rev` in the list response is the cursor for the next call. When `more` is true the client calls again with `since=<rev>` until `more` is false; then `rev` is the user's current revision. A full pull (`since=0`) replaces the cache only after the last page has arrived.
- Every request with a body must carry `Content-Length`. Without it the server answers 411 `length_required` before reading the body.
- Ids are scoped to the user. The same id in two accounts names two unrelated recipes, so a create with an id that another user happens to use simply creates the caller's own recipe.
- A picture can only be uploaded to a recipe that exists. A new recipe with pictures is therefore saved in three calls: create without pictures, upload the pictures, update with the picture list. An edit needs two: upload, then update.
- A create on an id that exists only as a deletion marker succeeds and revives the recipe.
- Uploading an image does not attach it; the recipe's `imageIds` decides which images belong to it. Images of a recipe that are no longer listed are removed when the recipe is saved.
- Storage cap: 52,428,800 bytes for free users, 524,288,000 for subscribers, counted over image bytes.
- Recipe text cap: the stored JSON of a user's live recipes may total at most 20,971,520 bytes (UTF-8), so a full pull fits in a phone's memory. A create or an update that would grow the total past it answers 413 `recipe_storage_cap`; an update that keeps or shrinks a recipe always passes, and deleting a recipe frees its share at once.
- Deletion markers are purged after 90 days.

### Turnstile page

The app shows `GET /turnstile.html` (served by the Worker, not under `/v1`) inside a WebView before it calls `POST /session`. The page renders the Turnstile widget with the environment's site key and hands the token to the app exactly once:

- Android: `window.RecipesBridge.onToken(token)` (a JavaScript interface named `RecipesBridge` with the single method `onToken(String)`).
- iOS: `window.webkit.messageHandlers.turnstile.postMessage(token)` (a script message handler named `turnstile`).

The page calls whichever of the two exists. It loads script and frames only from `https://challenges.cloudflare.com`, and the WebView is restricted to the page's own origin plus that host. The local environment uses Cloudflare's documented test keys, which always pass, so automated runs need no human.

### Rate limits

`POST /session/nonce` and `POST /session` are limited per IP address. Writes are limited per user. A limited request gets 429 `rate_limited`.

## Milestone M2

### AI consent

| Route | Request | Success | Errors |
|-------|---------|---------|--------|
| `PUT /me/consent` | `{ "version": 1 }` (the AI notice version the user accepted) | 200 `{ "consentVersion": 1 }` | 400 `consent_version_unknown` (not the current notice version); 400 `invalid_body` |
| `DELETE /me/consent` | none | 204 | |

`GET /me` shows `consentVersion` (null until accepted). Without the current version, `POST /imports` answers 403 `consent_required`, and no model is ever called.

### Imports

`POST /imports` needs the header `Idempotency-Key: <lowercase UUID>`, chosen by the client. That key is the job id: repeating the request with the same key returns the same job (202 again) and charges nothing more. Three input kinds, all costing one unit and all going through the same structuring step:

| Kind | Body | Limits |
|------|------|--------|
| `url` | `Content-Type: application/json`, `{ "kind": "url", "url": "https://..." }` | `http` or `https`, at most 2,048 characters; the server fetches the page (at most 2 MB, `text/html` only, at most 3 redirects, 10 s) |
| `text` | `Content-Type: application/json`, `{ "kind": "text", "text": "..." }` | 1 to 20,000 characters; the default photo path: text recognised on the phone, the picture never leaves the device |
| `photo` | `Content-Type: image/jpeg`, raw JPEG bytes | at most 1,572,864 bytes, longest side 2,048 px (client side); for pictures the device cannot read (handwriting, unsupported script) |

| Route | Success | Errors |
|-------|---------|--------|
| `POST /imports` | 202 `ImportJob` with `status: "pending"` | 400 `invalid_body`; 400 `invalid_id` (Idempotency-Key); 400 `invalid_url` (rejected by the URL rules before any fetch); 402 `quota_exhausted`; 403 `consent_required`; 409 `job_running` (another import of this user is still open); 411 `length_required`; 413 `text_too_large`; 413 `image_too_large`; 415 `unsupported_media_type` (not JSON, or not a JPEG by its magic bytes); 429 `attempt_limit` (10 counted attempts today, UTC); 429 `rate_limited` |
| `GET /jobs/:id` | 200 `ImportJob` | 404 (unknown, another user's, or older than 7 days) |

```jsonc
// ImportJob
{
  "id": "4c0e9a1b-7d2f-4e3a-9b8c-1f2e3d4c5b6a",   // the Idempotency-Key
  "kind": "url",                                  // "url" | "text" | "photo"
  "status": "done",                               // "pending" | "running" | "done" | "guard_block" | "no_recipe" | "invalid" | "failed"
  "createdAt": "2026-11-24T18:00:00Z",
  "draft": { /* RecipeInput, only when status is "done", otherwise null */ }
}
```

- The client polls every 2 seconds until the status is no longer `pending` or `running`. Every job ends in exactly one of the five outcomes; a job still open after 10 minutes ends as `failed`.
- The draft is a `RecipeInput` with a new server-generated `id`, `sourceType` `"url"` (with `sourceUrl`) or `"photo"` (for `text` and `photo`), and `imageIds: []`. Nothing is saved: the app opens the draft in the edit form, and the user saves it with the normal `PUT /recipes/:id` and `If-None-Match: *`.
- Outcomes and the unit: `done` keeps the charge. `guard_block` (blocked by the safety filter), `no_recipe` (the input is not a recipe) and `invalid` (the result failed validation) refund the unit and count one attempt. `failed` (model or network error after 3 tries, or the 10-minute timeout) refunds without counting an attempt.
- Units: the free lifetime import is used first. Monthly units (30 per UTC month) apply only while `entitlement.active` is true, which needs the subscription (M3). The free unit is refunded at most 5 times in an account's life; after that the next import consumes it whatever the outcome.
- The job's input (URL, text or photo) is deleted when the job ends. Results are kept 7 days.

### Reports

| Route | Request | Success | Errors |
|-------|---------|---------|--------|
| `POST /reports` | `Report` | 204 | 400 `invalid_body`; 404 (the job does not exist for this user); 429 `rate_limited` |

```jsonc
// Report
{
  "kind": "import_result",        // later milestones add "translation" and "share"
  "jobId": "4c0e9a1b-7d2f-4e3a-9b8c-1f2e3d4c5b6a",
  "reason": "wrong_content",      // "wrong_content" | "offensive" | "other"
  "note": ""                      // 0..1000 characters, optional
}
```

### Quota in `GET /me`

`quota.freeImportUsed` is true while the free unit is consumed (it becomes false again after a refund). `quota.monthUsed` counts this UTC month's monthly units; `quota.monthLimit` is 30 and applies only while `entitlement.active` is true.

## Milestone M3

### Subscription

One product (`monthly`) grants the RevenueCat entitlement `recipes_pro`. The server alone decides what a user may do; the app only shows what `GET /me` says.

App side:
- Right after `POST /session` succeeds, and before any purchase UI, the app calls the store SDK's log-in with our `userId` (`Purchases.logIn(userId)`). No purchase is possible while logged out of the SDK.
- After a purchase, and after "Restore purchases" (the SDK's restore), the app calls `POST /entitlement/refresh` and shows the answer. On a new device the SDK log-in is enough; the server learns of purchases from RevenueCat.
- The paywall appears when `POST /imports` answers 402 `quota_exhausted` and `entitlement.active` is false.

| Route | Request | Success | Errors |
|-------|---------|---------|--------|
| `POST /entitlement/refresh` | none | 200 `EntitlementRefresh` | 401; 429 `rate_limited` (more than one call in 5 seconds, or the per-user write limit); 503 `billing_unavailable` (RevenueCat could not be reached; the server keeps retrying in the background and `GET /me` shows the result later) |

```jsonc
// EntitlementRefresh: the entitlement, quota and storage parts of GET /me, read from RevenueCat just now
{
  "entitlement": { "active": true, "expiresAt": "2026-12-25T10:00:00Z" },
  "quota": { "freeImportUsed": true, "monthUsed": 3, "monthLimit": 30 },
  "storage": { "usedBytes": 1048576, "capBytes": 524288000 }
}
```

`entitlement` in `GET /me` and in `EntitlementRefresh`:
- `active` is true while now is before the end of the subscription as RevenueCat reports it. A store grace period after a failed renewal counts as active until the grace period ends. A refund or revocation makes it false at once.
- `expiresAt` is that end (the grace-period end when it is later), or null when `active` is false.
- If RevenueCat cannot be reached, the stored entitlement is honoured until 24 hours after its end, then `active` is false.
- `storage.capBytes` follows `active`: 524,288,000 while active, otherwise 52,428,800. A lapsed subscriber who stores more keeps everything; only new picture uploads answer 413 `storage_cap`.

Quota and `402 quota_exhausted` on `POST /imports`:
- The free lifetime import is used first, also by subscribers, and does not count against the 30.
- Free user: 402 once the free import is used (the app shows the paywall).
- Subscriber: 30 monthly units per UTC month (plus the free import if it is still unused); once `quota.monthUsed` is 30 the next import answers 402 (the app says the monthly allowance is used up until the 1st of the next month, UTC). `quota.monthUsed` counts per UTC month whatever the subscription did in between: lapsing and resubscribing in the same month keeps that month's count. A refund does not give used units back.
- Failed imports on monthly units: a `failed` outcome (model or network error after 3 tries, or the 10-minute timeout) refunds a monthly unit but counts one attempt against the daily limit, so the eleventh counted attempt of a UTC day answers 429 `attempt_limit`. A `failed` outcome on the free unit still counts no attempt (the free unit has its own limit of 5 refunds). This bounds the model calls a subscriber can cause with failing imports to 10 jobs a day.

### RevenueCat webhook

Not called by the app. RevenueCat posts every subscription event to `POST /v1/webhooks/revenuecat`.

- Authorisation: the dashboard's webhook "Authorization header value" is set to exactly the value of the Worker secret `REVENUECAT_WEBHOOK_SECRET`; the Worker compares the whole `Authorization` header with it in constant time before reading the body. Wrong or missing: 401 `unauthenticated` (and repeated failures from one IP: 429).
- `Content-Length` is required (411 `length_required`); bodies over 64 KB answer 413 `body_too_large`; a body that is not JSON with `event.type` answers 400 `invalid_body`.
- The payload is a trigger only: the Worker takes `event.app_user_id`, and for `TRANSFER` also `event.transferred_from` and `event.transferred_to`, keeps the values that have the shape of our user ids (43 base64url characters), and schedules a refresh from RevenueCat's REST record for each account that exists. Ids of other shapes and ids without an account are ignored; they never create storage. Answer: 200 `{ "received": true }`, before RevenueCat is asked.
- The refresh runs in the user's object, retried with backoff (up to 10 tries) while RevenueCat is unreachable. Replays and out-of-order events only cause another read of the same record, so they are harmless. An account being deleted ignores webhooks.

### Local environment only

`POST /v1/testing/entitlement` with the session token and `{ "active": true }` or `{ "active": false }` switches the signed-in user's subscription without RevenueCat (a 30-day subscription, or none) and answers like `POST /entitlement/refresh`. It exists only in the local entry, which has no RevenueCat key; see `backend/README.md`.

## Module boundaries

These signatures let people work on both sides at once. Change them only together with this section.

### Backend: `backend/src/auth/index.ts`

```ts
export type Provider = "apple" | "google";

/** True when Cloudflare Turnstile accepts the token. */
export function verifyTurnstile(token: string, ip: string | null, env: Env): Promise<boolean>;

/** A signed nonce valid for 5 minutes from `now` (milliseconds). */
export function issueNonce(env: Env, now: number): Promise<string>;

/** Signature and expiry only. Single use is enforced by the user's object with the returned id. */
export function verifyNonce(nonce: string, env: Env, now: number): Promise<{ id: string; expiresAt: number } | null>;

/** Subject of a valid id token whose nonce claim matches; null when it is invalid; "unavailable" when the provider's keys cannot be fetched. */
export function verifyIdToken(provider: Provider, idToken: string, nonce: string, env: Env, now: number): Promise<{ sub: string } | null | "unavailable">;

/** base64url(HMAC-SHA256(USER_ID_PEPPER, provider + ":" + sub)), 43 characters. */
export function deriveUserId(provider: Provider | "test", sub: string, env: Env): Promise<string>;

/** A new token (with its tag) and the SHA-256 (hex) of its secret, which is all the object stores. */
export function newSessionToken(userId: string, env: Env): Promise<{ token: string; secretHash: string }>;

/** Null when the token is malformed or its tag does not verify with SERVER_KEY or SERVER_KEY_PREVIOUS. */
export function parseSessionToken(token: string, env: Env): Promise<{ userId: string; secretHash: string } | null>;
```

`Env` additions used by auth: secrets `USER_ID_PEPPER`, `SERVER_KEY`, `TURNSTILE_SECRET`; variables `APPLE_AUDIENCES` and `GOOGLE_AUDIENCES` (comma-separated). Outbound calls (provider keys, Turnstile) go through the global `fetch`, so tests can intercept them.

### Client: `shared/src/commonMain/kotlin/com/linux/recipes/data/Repositories.kt`

```kotlin
sealed interface SessionState {
    data object SignedOut : SessionState
    data class SignedIn(val userId: String) : SessionState
}

sealed interface SignInResult {
    data object Success : SignInResult
    data object Cancelled : SignInResult
    data class Failed(val reason: Reason) : SignInResult {
        enum class Reason { Network, Rejected, AgeNotConfirmed, AccountDeleting, RateLimited }
    }
}

interface SessionRepository {
    val state: StateFlow<SessionState>
    /** Runs the platform sign-in, the Turnstile check and the server call. */
    suspend fun signIn(ageConfirmed: Boolean): SignInResult
    /** Revokes the session on the server when reachable and always wipes local data. */
    suspend fun signOut()
}

sealed interface SaveResult {
    data class Saved(val recipe: Recipe) : SaveResult
    data object Conflict : SaveResult          // 412: someone else changed it
    data object Offline : SaveResult
    data class Rejected(val code: String) : SaveResult
}

interface RecipeRepository {
    /** Cached recipes without deletion markers, newest change first. Works offline. */
    val recipes: StateFlow<List<Recipe>>
    /** Pulls changes and missing pictures. False when the server could not be reached. */
    suspend fun sync(): Boolean
    /**
     * Creates when `expectedRev` is null, otherwise updates that revision.
     * With new pictures on a create: create without pictures, upload them, then update with the picture list.
     * With new pictures on an update: upload them, then update.
     */
    suspend fun save(recipe: RecipeInput, expectedRev: Long?, newImages: List<NewImage>): SaveResult
    suspend fun delete(id: String): Boolean
    /** Absolute path of a cached picture, or null when it is not on the device yet. */
    fun imagePath(imageId: String): String?
}

/** A picture chosen by the user, already compressed to JPEG of at most 1.5 MB. */
class NewImage(val id: String, val bytes: ByteArray)
```

`Recipe` and `RecipeInput` are `@Serializable` data classes with exactly the fields of the JSON above, in `data/Recipe.kt`.

### Client, milestone M2: `data/ImportRepositories.kt` and `platform/TextRecognizer.kt`

```kotlin
sealed interface ImportSource {
    data class Link(val url: String) : ImportSource
    /** JPEG of at most 1.5 MB, longest side 2,048 px. The repository reads it on the device first. */
    class Photo(val jpeg: ByteArray) : ImportSource
}

sealed interface ImportStart {
    data class Started(val jobId: String) : ImportStart
    data object ConsentRequired : ImportStart            // 403 consent_required
    data object QuotaExhausted : ImportStart             // 402 quota_exhausted
    data object AttemptLimit : ImportStart               // 429 attempt_limit
    data object AlreadyRunning : ImportStart             // 409 job_running; activeJob holds it
    data object Offline : ImportStart                    // no connection or a 5xx answer
    data class Rejected(val code: String) : ImportStart  // invalid_url, text_too_large, image_too_large, ...
}

sealed interface ImportOutcome {
    data class Done(val draft: RecipeInput) : ImportOutcome
    data object GuardBlocked : ImportOutcome
    data object NoRecipe : ImportOutcome
    data object Invalid : ImportOutcome
    data object Failed : ImportOutcome
}

/** The one open job. It survives leaving the screen and an app restart (the job id is kept in the cache settings). */
data class ImportJobState(val jobId: String, val kind: String, val outcome: ImportOutcome?, val offline: Boolean)

data class Quota(val freeImportUsed: Boolean, val monthUsed: Int, val monthLimit: Int, val entitled: Boolean)

enum class ReportReason { WrongContent, Offensive, Other }

interface ImportRepository {
    val activeJob: StateFlow<ImportJobState?>
    /** `jobId` is the Idempotency-Key. The caller keeps it across retries after Offline, so nothing is charged twice. */
    suspend fun start(source: ImportSource, jobId: String): ImportStart
    /** Polls `GET /jobs/:id` every 2 seconds into `activeJob` until an outcome arrives; keeps retrying while offline. */
    suspend fun resume()
    /** Clears `activeJob` after the user has opened or dismissed the outcome. */
    fun acknowledge(jobId: String)
    /** From `GET /me`; null when offline. */
    suspend fun quota(): Quota?
    suspend fun report(jobId: String, reason: ReportReason, note: String): Boolean
}

sealed interface ConsentState {
    data object Unknown : ConsentState
    data object NotGiven : ConsentState
    data class Given(val version: Int) : ConsentState
}

interface ConsentRepository {
    val state: StateFlow<ConsentState>
    /** The notice version this build shows. */
    val currentVersion: Int
    suspend fun accept(): Boolean      // PUT /me/consent
    suspend fun withdraw(): Boolean    // DELETE /me/consent
}

sealed interface Recognition {
    data class Text(val text: String) : Recognition
    data object Unreadable : Recognition
}

/** Google ML Kit on Android, Apple Vision on iOS. On this path the picture never leaves the device. */
interface TextRecognizer {
    suspend fun recognize(jpeg: ByteArray): Recognition
}
```

Rules:
- `ImportSource.Photo`: the repository runs `TextRecognizer` first. Readable text is sent as kind `text`; `Unreadable`, or text shorter than the hand-over threshold, sends the JPEG as kind `photo`. The threshold is one constant in the repository and is set from the bake-off numbers.
- The app sends nothing to an import route before `ConsentState` is `Given` for `currentVersion`.
- Signing out, and any 401, ends the open job locally and clears `activeJob`.
- Not in M2: receiving a link shared from another app (Android share intent, iOS share extension).
