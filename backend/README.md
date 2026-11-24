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

## Checks

```sh
mise exec -- bun run typecheck
mise exec -- bun run test --coverage
mise exec -- bun run test:spikes     # platform behaviour (spike S11), not part of the default run
mise exec -- bun run build:check     # production dry-run bundle in dist/
```
