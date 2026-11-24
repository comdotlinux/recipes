# API contract

The single source for the HTTP interface between the app and the Worker. Backend tests and Kotlin tests both validate against the JSON files in `contract/fixtures/`, which must match this document. Sections are added per milestone.

All routes are under `/v1`. Bodies are JSON unless stated. Times are ISO 8601 UTC strings. Ids are lowercase UUIDs generated on the client; anything else is rejected with `invalid_id`.

## Errors

Every error has this body, with the HTTP status shown in the tables below:

```json
{ "error": { "code": "snake_case_code", "message": "English text for logs, not for users" } }
```

Codes used everywhere: `invalid_body` (400), `invalid_id` (400), `unauthenticated` (401), `not_found` (404), `rate_limited` (429).

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
| `PUT /recipes/:id` | `RecipeInput` (its `id` must equal the id in the path); header `If-None-Match: *` to create or `If-Match: "<rev>"` to update | 200 `Recipe` | 428 `precondition_required` (neither header); 412 `precondition_failed` (exists on create, or stale rev); 404 `not_found` (update of an id this user never had); 413 `recipe_too_large` (body over 64 KB); 409 `recipe_limit` (2,000 recipes); 400 `invalid_body` |
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
- Deletion markers are purged after 90 days.

### Turnstile page

The app shows `GET /turnstile.html` (served by the Worker, not under `/v1`) inside a WebView before it calls `POST /session`. The page renders the Turnstile widget with the environment's site key and hands the token to the app exactly once:

- Android: `window.RecipesBridge.onToken(token)` (a JavaScript interface named `RecipesBridge` with the single method `onToken(String)`).
- iOS: `window.webkit.messageHandlers.turnstile.postMessage(token)` (a script message handler named `turnstile`).

The page calls whichever of the two exists. It loads script and frames only from `https://challenges.cloudflare.com`, and the WebView is restricted to the page's own origin plus that host. The local environment uses Cloudflare's documented test keys, which always pass, so automated runs need no human.

### Rate limits

`POST /session/nonce` and `POST /session` are limited per IP address. Writes are limited per user. A limited request gets 429 `rate_limited`.

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

### Client: `shared/src/commonMain/kotlin/de/munichtech/recipes/data/Repositories.kt`

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
