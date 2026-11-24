# Spikes

Each spike answers one question the plan could not settle from documentation. The plan's section 6 (M0) defines the pass condition and the alternative for each.

| Spike | Question | Status on 2026-11-24 | Record |
|-------|----------|----------------------|--------|
| S1 | Can bun be the only JavaScript runtime? | Done. No: the `cf` CLI and `wrangler dev` need node. Install, typecheck, tests and the dry-run build run under bun alone. node is pinned in `mise.toml` for those two uses. | [toolchain](spikes/S1-toolchain.md), [runtime](spikes/S1-bun-runtime.md) |
| S2 | AI Gateway binding, Guardrails, vision requests, payload logging | Blocked: needs a Cloudflare account login | |
| S3 | Exact Workers AI model ids and limits | Blocked: needs a Cloudflare account login | |
| S4 | iOS Keychain, WKWebView bridge and Apple sign-in request from Kotlin without Swift | Open: runs on the macOS CI job once the first CI run is green | |
| S5 | Compose UI tests and screenshot tests as Android host tests on Linux | Done. Pass: no extra `jvm` target is needed. | [S5](spikes/S5.md) |
| S6 | iOS signing in CI | Blocked: needs the Apple Developer account | |
| S7 | PDF rendering through Browser Run, fonts | Blocked: needs a Cloudflare account login | |
| S8 | Picture size limit and read latency in Durable Objects | Blocked: latency needs staging | |
| S9 | Cloudflare malware-blocking resolver from a Worker | Blocked: needs staging | |
| S10 | Analytics Engine query and alert from the cron handler | Blocked: needs staging | |
| S11 | Alarm and storage behaviour | Done locally. Alarms replace and retry as assumed; work survives a client disconnect. Writes placed right after `deleteAll()` are not committed with it, so erasure uses one `transactionSync()` (plan section 4.7 was changed). Re-check on staging. | [S11](spikes/S11.md) |
| S12 | Large file through the system file picker, Drive and iCloud destinations | Blocked: manual check on devices | |
| S13 | Cloudflare Access in front of the admin host | Blocked: needs a Cloudflare account login | |

Version evidence for dependencies is in [versions.md](versions.md).
