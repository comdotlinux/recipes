# Recipes backend

Cloudflare Worker (Hono) with one Durable Object per user. Tool versions are pinned in `mise.toml` at the repository root.

## Run locally

```sh
cd backend && mise exec -- bun install && mise exec -- bun run dev
```

`bun run dev` starts `wrangler dev --env local`; it needs node, which `mise.toml` pins (see `docs/spikes/S1-bun-runtime.md`).
No `.dev.vars` file is needed: `env.local` in `wrangler.jsonc` sets development values for `USER_ID_PEPPER` and `SERVER_KEY`
(prefixed `dev-only-`, not secrets, because `env.local` is never deployed) and Cloudflare's always-pass Turnstile test keys.
Staging and production still require real secrets (`wrangler secret put <NAME> --env <environment>`); the names are in
`.dev.vars.example`. Tests and `build:check` fail if the development or test values reach those environments or the production bundle.
Local sign-in uses the provider `test` (`src/testing/test-idp.ts`), which only the local entry accepts.

## Try an import locally

`env.local` has no Workers AI binding. The local entry uses a deterministic stand-in instead (`src/testing/local-ai.ts`):
the first line of the text becomes the title and the other lines the steps. Markers in the text produce the other outcomes:
`__NO_RECIPE__` gives `no_recipe`, `__GUARD__` gives `guard_block`, `__FAIL__` gives `failed` (after the three tries, which
take about 1.5 minutes because of the retry backoff). Every photo reads as a fixed text. URL imports fetch the real page.

```sh
B=http://127.0.0.1:8787/v1
N=$(curl -s -X POST $B/session/nonce | jq -r .nonce)
T=$(curl -s -X POST $B/session -H 'content-type: application/json' \
  -d "{\"provider\":\"test\",\"idToken\":\"__TEST_IDP__|me|$N\",\"nonce\":\"$N\",\"turnstileToken\":\"x\",\"ageConfirmed\":true}" | jq -r .token)
curl -s -X PUT $B/me/consent -H "authorization: Bearer $T" -H 'content-type: application/json' -d '{"version":1}'
K=$(uuidgen | tr 'A-Z' 'a-z')
curl -s -X POST $B/imports -H "authorization: Bearer $T" -H "idempotency-key: $K" -H 'content-type: application/json' \
  -d '{"kind":"text","text":"Pancakes\nMix the batter.\nFry."}'     # or put __NO_RECIPE__, __GUARD__ or __FAIL__ in the text
curl -s $B/jobs/$K -H "authorization: Bearer $T"                     # poll until status is done, no_recipe, guard_block, invalid or failed
```

A free account has one import; a `no_recipe`, `guard_block` or `failed` outcome gives it back (at most 5 times), a `done` one
uses it. Sign in with another `idToken` subject (`__TEST_IDP__|someone-else|...`) for a fresh account.
URL import: `-d '{"kind":"url","url":"https://example.org/"}'`. Photo import: `-H 'content-type: image/jpeg' --data-binary @photo.jpg`.

## Try the subscription locally

`env.local` has no RevenueCat key, so the local entry answers RevenueCat's part itself (`src/testing/local-billing.ts`):
a switch per signed-in user, set with a local-only route. With `$B` and `$T` from the import example above:

```sh
curl -s -X POST $B/testing/entitlement -H "authorization: Bearer $T" -H 'content-type: application/json' -d '{"active":true}'   # 30-day subscription
curl -s $B/me -H "authorization: Bearer $T"                                   # entitlement.active true, 30 monthly imports, 500 MB cap
curl -s -X POST $B/entitlement/refresh -H "authorization: Bearer $T"          # what the app calls after a purchase or restore
curl -s -X POST $B/testing/entitlement -H "authorization: Bearer $T" -H 'content-type: application/json' -d '{"active":false}'  # lapse
```

The route exists only in the local entry, and `build:check` fails if its marker reaches the production bundle.
Staging and production need the secrets `REVENUECAT_API_KEY` (a RevenueCat v1 secret key) and `REVENUECAT_WEBHOOK_SECRET`
(the exact value entered as the webhook's authorization header in the RevenueCat dashboard; webhook URL `https://<host>/v1/webhooks/revenuecat`).

## Checks

```sh
mise exec -- bun run typecheck
mise exec -- bun run test --coverage
mise exec -- bun run test:spikes     # platform behaviour (spike S11), not part of the default run
mise exec -- bun run build:check     # production dry-run bundle in dist/
```
