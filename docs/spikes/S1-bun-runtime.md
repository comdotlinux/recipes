# Spike S1, runtime half: bun as the only JavaScript runtime for the backend tools

Date: 2026-11-24. Host: Linux x86_64. bun 1.4.5, wrangler 4.168.0, `@cloudflare/vitest-plugin` 1.9.2, vitest 5.1.1.

## Question

Do `wrangler dev`, `wrangler deploy --dry-run` and the vitest plugin run with bun as the only JavaScript runtime?

## Result

| Tool | Runs under bun only? |
|------|----------------------|
| `bun install --frozen-lockfile` | yes |
| `wrangler types --check` + `tsc --noEmit` (`bun run typecheck`) | yes |
| vitest + `@cloudflare/vitest-plugin`, with istanbul coverage (`bun run test --coverage`) | yes, 10 of 10 tests pass |
| the same plugin on the S11 spike tests (`bun run test:spikes`) | yes, 8 of 8 tests pass |
| `wrangler deploy --dry-run --outdir dist --env production` (`bun run build:check`) | yes |
| `wrangler dev` | **no**: the server starts, but every request returns 503 |

**`wrangler dev` needs Node.js.** Under bun, wrangler prints `Ready on http://127.0.0.1:18788`, but every request answers:

```
503 Wrangler can't forward requests to your Worker under Bun, because Bun's `fetch()` ignores the undici `dispatcher` that Miniflare routes them through (https://github.com/oven-sh/bun/issues/39247). Run this using Node.js.
```

The upstream issue is open as of 2026-11-24: "undici shim: `Pool` lacks `dispatch()`/`close()`/`destroy()`, breaking miniflare + workerd (wrangler dev, @cloudflare/vite-plugin)". The same `wrangler dev --env local` on the host, where `node` is on PATH and the wrangler bin script runs under node, serves `/v1/health`, `/v1/test/idp` and `/` correctly.

Consequence, per the plan's S1 fallback: `mise.toml` pins `node` (24.22.3) as the runtime for `wrangler dev`. bun stays the package manager and script runner. On the host, `mise exec -- bun run dev -- --port 18789` answers `{"status":"ok","version":1}` on `/v1/health`. CI does not need node for the backend: typecheck, test, test:spikes and the dry-run build all pass under bun only.

## Method

The test ran in a clean container from `oven/bun:1.4.5-slim`. The image links `node` to bun in `/usr/local/bin`, so the script uses a PATH that holds only a `bun` link plus `/usr/bin:/bin`. It first asserts that `command -v node` and `command -v npx` both fail. The backend sources are copied in without `node_modules`, `dist` or `.wrangler`. The full script, saved as `s1-in-container.sh`, follows the command.

```sh
docker run --rm -v "$PWD/backend:/src/backend:ro" -v "$PWD/contract:/src/contract:ro" \
  -v "$PWD/s1-in-container.sh:/s1.sh:ro" oven/bun:1.4.5-slim sh /s1.sh
```

```sh
#!/bin/sh
# Runs inside oven/bun:1.4.5-slim. PATH holds only bun plus base system dirs; the image's node->bun symlink is left out.
mkdir -p /bunonly && ln -sf /usr/local/bin/bun /bunonly/bun
export PATH=/bunonly:/usr/bin:/bin
echo "== bun: $(bun --version)"
if command -v node >/dev/null 2>&1; then echo "node FOUND at $(command -v node)"; exit 9; else echo "== node: not on PATH"; fi
if command -v npx >/dev/null 2>&1; then echo "npx FOUND"; exit 9; fi
mkdir -p /work/backend /work/contract/fixtures
tar -C /src/backend --exclude=node_modules --exclude=dist --exclude='dist-*' --exclude=.wrangler --exclude=coverage -cf - . | tar -C /work/backend -xf -
ls -A /work/backend
cp /src/contract/fixtures/health.json /work/contract/fixtures/
cd /work/backend
echo "== bun install --frozen-lockfile"; bun install --frozen-lockfile > /tmp/install.log 2>&1; echo "exit $?"; tail -3 /tmp/install.log
echo "== typecheck"; bun run typecheck > /tmp/tc.log 2>&1; echo "exit $?"; tail -3 /tmp/tc.log
echo "== test --coverage"; bun run test --coverage > /tmp/test.log 2>&1; echo "exit $?"; grep -E "Test Files|Tests |Lines|ENOENT|node:" /tmp/test.log
echo "== test:spikes"; bun run test:spikes > /tmp/spikes.log 2>&1; echo "exit $?"; grep -E "Test Files|Tests " /tmp/spikes.log
echo "== build:check"; bun run build:check > /tmp/build.log 2>&1; echo "exit $?"; grep -E "dry-run|Total Upload|rror" /tmp/build.log
echo "== wrangler dev"
bun run dev -- --port 18788 --ip 127.0.0.1 > /tmp/dev.log 2>&1 &
i=0; until grep -q "Ready on" /tmp/dev.log || [ $i -ge 60 ]; do sleep 1; i=$((i+1)); done
bun -e 'const r = await fetch("http://127.0.0.1:18788/v1/health"); console.log("GET /v1/health", r.status, await r.text())'
grep -E "Ready on|rror" /tmp/dev.log | head -5
echo "== executables of processes running at this point"; ls /proc/*/exe 2>/dev/null | while read e; do readlink "$e"; done 2>/dev/null | sort -u
```

## Output (excerpt of the second run after fix round 1, ANSI colour codes removed)

```
== bun: 1.4.5
== node: not on PATH
== bun install --frozen-lockfile
exit 0
129 packages installed [2.90s]
== typecheck
exit 0
✨ Types at worker-configuration.d.ts are up to date.
== test --coverage
exit 0
 Test Files  2 passed (2)
      Tests  10 passed (10)
== test:spikes
exit 0
 Test Files  1 passed (1)
      Tests  8 passed (8)
== build:check
exit 0
$ wrangler deploy --dry-run --outdir dist --env production
Total Upload: 63.70 KiB / gzip: 16.63 KiB
--dry-run: exiting now.
== wrangler dev
GET /v1/health 503 Wrangler can't forward requests to your Worker under Bun, because Bun's `fetch()` ignores the undici `dispatcher` that Miniflare routes them through (https://github.com/oven-sh/bun/issues/39247). Run this using Node.js.
[wrangler:info] Ready on http://127.0.0.1:18788
== executables of processes running at this point
/usr/bin/dash
/usr/bin/readlink
/usr/local/bin/bun
/work/backend/node_modules/@cloudflare/workerd-linux-64/bin/workerd
/work/backend/node_modules/@esbuild/linux-x64/bin/esbuild
```

## Not covered here

- `mise install` and `cf --version` in a clean container are the toolchain half of S1 (`docs/spikes/S1-toolchain.md`, owned by worker-toolchain).
- No Cloudflare account was used. `wrangler whoami` on this machine reports "Not logged in" (expired token), so dev, tests and the dry-run build all ran without an account.
