# Key rotation

Secrets live only in `wrangler secret` per environment (`staging`, `production`). Never paste a secret into the repository, a chat or a ticket. Rotate one environment at a time, staging first.

## `SERVER_KEY` (rotatable)

What it protects (HKDF subkeys, `backend/src/auth/`, `backend/src/share.ts`): sign-in nonces (5 minutes), session-token tags (sessions last 30 days after their last daily extension), share tokens (30 days) and share-report references (30 days). Every verifier also accepts `SERVER_KEY_PREVIOUS`. `backend/test/key-rotation.test.ts` proves that tokens made under the old key stay valid during their lifetime once both are set (AC34).

1. Generate a new value: `openssl rand -base64 48`.
2. `wrangler secret put SERVER_KEY_PREVIOUS --env <env>` with the **current** `SERVER_KEY` value (from your password manager).
3. `wrangler secret put SERVER_KEY --env <env>` with the new value. Store it in the password manager.
4. Check: sign in on a device, open an existing share link, open a report reference younger than 30 days on the admin page.
5. Keep `SERVER_KEY_PREVIOUS` set for **at least 60 days** (sessions can be extended for 30 days after the last use, links and references live 30 days), then `wrangler secret delete SERVER_KEY_PREVIOUS --env <env>`. After that, older sessions sign out and older links answer 404, as intended.
6. If the old key leaked: skip step 2 instead (no previous key). Every session, link and reference made under the old key stops working at once; users sign in again.

## `USER_ID_PEPPER` (never rotated)

Every user id is HMAC-SHA256 of the provider subject under this pepper, and each user's data lives in the object named by that id. Changing it orphans every account. Never rotate it, never delete it; keep the offline backup. If it leaks, that is an incident (`incident.md`), not a rotation: the leak alone lets someone compute user ids, which still need a valid session token to be used.

## `APPLE_P8` and `APPLE_KEY_ID` (Sign in with Apple key)

Used to sign the client secret for Apple's token and revoke endpoints (account deletion).
1. In the Apple developer account create a new Sign in with Apple key; download the `.p8` once.
2. `wrangler secret put APPLE_P8 --env <env>` with the file content; set the variable `APPLE_KEY_ID` in `backend/wrangler.jsonc` to the new key id and deploy.
3. Check: a deletion test account with Apple sign-in reaches the erased state (the revoke step succeeds).
4. Revoke the old key in the Apple account.

## `REVENUECAT_WEBHOOK_SECRET`

1. Generate a value: `openssl rand -base64 32`, prefixed as RevenueCat sends it (`Bearer <value>`).
2. `wrangler secret put REVENUECAT_WEBHOOK_SECRET --env <env>`, then immediately set the same "Authorization header value" in the RevenueCat dashboard. Webhooks between the two steps answer 401 and RevenueCat retries them (up to 5 times over about 75 minutes).
3. Check: send a test event from the RevenueCat dashboard; the admin page shows no new webhook failures.

## `REVENUECAT_API_KEY`, `REVENUECAT_V2_API_KEY`

Create the new key in RevenueCat (v1 secret key; v2 secret key with `customer_information:customers:read_write` and `charts_metrics:overview:read`), `wrangler secret put` it, check `POST /v1/entitlement/refresh` and the admin Subscriptions page, then revoke the old key in RevenueCat.

## `TURNSTILE_SECRET`

Rotate in the Turnstile widget settings (Cloudflare dashboard, or `POST .../challenges/widgets/{sitekey}/rotate_secret`), then `wrangler secret put TURNSTILE_SECRET --env <env>`. Sign-ins in between fail with `invalid_turnstile` and are retried by the user.

## `CF_API_TOKEN_READ`, `ALERT_WEBHOOK_URL`

Create a new token with only "Account Analytics Read", put it, check the admin Overview, delete the old token. For the alert hook: create the new hook URL at the alert destination, put it, wait for (or trigger) a cron run, remove the old hook.
