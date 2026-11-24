# Spike S1 (toolchain half): bun-only toolchain with mise

Date: 2026-11-24. Host: Linux x86_64, mise 2026.11.10, docker 29.9.2.

## Question

Does a clean machine with mise and bun only (no node) install and run the Cloudflare CLI (`cf`, npm package `cf`)?

The wrangler and vitest half of S1 is in `docs/spikes/S1-bun-runtime.md`: everything works under bun except `wrangler dev`, so node is also the runtime for `wrangler dev`.

## Result

**FAIL for "bun only". Alternative taken: `node = "24.22.3"` added to `mise.toml` as the runtime for `cf`.** bun stays the package manager and script runner.

- `mise install` works without node. Since mise 2026.7.12 the `npm:` backend installs packages with its embedded aube package manager and needs neither node nor npm for installation (mise docs, `docs/dev-tools/backends/npm.md`, "Requirements"). The setting `npm.package_manager = "bun"` also exists, but only changes the installer, so it does not help.
- Running `cf` needs node. Its entry point `node_modules/cf/bin/cf` starts with `#!/usr/bin/env node` and the package declares `engines.node = ^22.23.0 || ^24.22.0 || ^26.12.0`. The mise docs say the same: "Most CLIs still need Node.js to run ... Add `node` to `[tools]`".
- bun can execute the script when called explicitly (`bun <path>/node_modules/cf/bin/cf --version` prints `1.0.0-rc.4`). The `cf` command on PATH still fails, so this does not meet the pass condition.

## Decisions in `mise.toml`

| Tool | Pin | Reason |
|------|-----|--------|
| bun | 1.4.5 | latest in `mise ls-remote bun` |
| `npm:cf` | 1.0.0-rc.4 | `npm view cf dist-tags` gives `{ latest: '1.0.0-rc.4' }`. The registry short name `cf` is the Cloud Foundry CLI; the registry alias `cloudflare-cf` maps to `npm:cf`, and the explicit backend form is used to be unambiguous |
| node | 24.22.3 | runtime for `cf` only (this spike); inside the `cf` engines range |
| java | temurin-25.0.5.1+2.0.LTS | owner prefers 25; see JDK matrix below |
| maestro | cli-2.12.1 | latest in `mise ls-remote maestro`, matches plan |
| gitleaks | 8.30.1 | latest in `mise ls-remote gitleaks` |
| actionlint | 1.7.12 | latest release v1.7.12 (2026-03-30) at https://github.com/rhysd/actionlint/releases; registry name `actionlint` resolves to `aqua:rhysd/actionlint`; used by the CI guards job |
| `ANDROID_HOME` | `[env]` | see Android SDK below |

### JDK matrix (checked 2026-11-24)

Owner decision: use JDK 25 if Gradle 9.7.0, AGP 9.3.1, Kotlin 2.4.21, Robolectric 4.18.1 and Kover 0.9.11 all allow it, else 21.

| Component | JDK 25 to run the build | Source |
|-----------|-------------------------|--------|
| Gradle 9.7.0 | yes: "25: toolchains 9.1.0, running Gradle 9.1.0 and after"; 26 from 9.4.0 | https://docs.gradle.org/9.7.0/userguide/compatibility.html, table 1 |
| AGP 9.3.1 | yes: only a minimum is defined ("JDK 17"); the JDK page asks for a JDK "higher than or equal to the JDK versions used by plugins". No maximum is stated | https://developer.android.com/build/releases/agp-9-3-0-release-notes (compatibility table), https://developer.android.com/build/jdks |
| Kotlin 2.4.21 | yes: Java 25 supported since Kotlin 2.3.0, Java 26 since 2.4.0; KGP 2.4.20 to 2.4.21 is tested with Gradle up to 9.7.0 and AGP up to 9.3.1 | https://kotlinlang.org/docs/whatsnew23.html, https://kotlinlang.org/docs/whatsnew24.html, https://kotlinlang.org/docs/gradle-configure-project.html |

| Kotlin Gradle plugin 2.4.21 with toolchain 25 | yes: Gradle supports Java 25 toolchains since 9.1.0, and the Kotlin compiler emits Java 25 bytecode since 2.3.0 | links above |
| Robolectric 4.18.1 | no maximum stated, no known blocker. 4.18 added JDK 24+ handling ("Enable native access for the native runtime on JDK 24+", #11482). Issue #11470 "New warnings when using robolectric with JDK 25" was about warnings only and is closed. Robolectric's own CI runs on JDK 21 (`.github/.java-version`), so 25 is used but not tested there | https://github.com/robolectric/robolectric/releases/tag/robolectric-4.18, https://github.com/robolectric/robolectric/issues/11470, https://github.com/robolectric/robolectric/blob/master/.github/.java-version |
| Kover 0.9.11 | no maximum stated, no known blocker. The README gives only minimums (JVM 1.7 for tests, Gradle 6.8.3, AGP 7.0.0). No issue in the repo mentions JDK 25 or Java 25 | https://github.com/Kotlin/kotlinx-kover/blob/main/README.md, https://github.com/Kotlin/kotlinx-kover/blob/main/CHANGELOG.md |

Result: no component states a maximum below 25 or has a known JDK 25 blocker, so Temurin 25 runs Gradle. The pin `temurin-25.0.5.1+2.0.LTS` is the current Temurin 25 release (`jdk-25.0.5.1+2`, published 2026-11-18 on github.com/adoptium/temurin25-binaries). No fallback to 21 was needed.

Residual risk: AGP, Robolectric and Kover state only minimums, and Robolectric CI tests on JDK 21. The first Gradle build with host tests and Kover on JDK 25 is the real proof. If that build fails because of JDK 25, set the pin to `temurin-21.0.13.1+2.0.LTS`. Side note: AGP 9.3.1 lint crashes on JDK 17 (issue #522845800, fixed in 9.3.2), so 17 is not an option anyway.

The JDK that runs Gradle is separate from the bytecode target of the Android code. Per https://developer.android.com/build/jdks, Android API 34 supports Java 17 language features and core libraries, and the target defaults to the toolchain version when not set. So the Gradle files must set the target to 17 explicitly (`compileOptions` `sourceCompatibility`/`targetCompatibility = JavaVersion.VERSION_17` and Kotlin `compilerOptions.jvmTarget = JvmTarget.JVM_17`) and must not set `jvmToolchain(25)`, which would raise the target to 25.

### Android SDK

The `android-sdk` plugin (`vfox:jdx/vfox-android-sdk`) is not used. Its hooks (`hooks/post_install.lua`, `hooks/env_keys.lua` in github.com/jdx/vfox-android-sdk) install only the command-line tools into the mise install directory and set `ANDROID_HOME` there. Platform 36 and build-tools must then be installed with `sdkmanager`, which requires license acceptance and is not part of `mise install`. It would also duplicate the existing SDK at `$HOME/Android/Sdk` (which already has `platforms/android-36` and `build-tools/36.0.0`).

Instead `mise.toml` sets:

```toml
ANDROID_HOME = "{{ get_env(name='ANDROID_HOME', default=env.HOME ~ '/Android/Sdk') }}"
```

An existing `ANDROID_HOME` (for example on GitHub runners) wins; otherwise the Android Studio default under `$HOME` is used. No user name is hardcoded.

## Method and output

### Host

```
$ mise install
mise ████████████████ 13/13 · installed 4 tools · 9 already installed in 18.9s     (exit 0)
$ mise exec -- cf --version
1.0.0-rc.4
$ mise exec -- cf --help | grep -m1 "Cloudflare CLI"
The Cloudflare CLI - manage your Cloudflare resources
$ mise exec -- bun --version
1.4.5
$ mise exec -- java -version
openjdk version "21.0.13.1" 2026-11-17 LTS
$ mise exec -- maestro --version
2.12.1
$ mise exec -- gitleaks version
8.30.1
$ mise exec -- env | grep ANDROID
ANDROID_HOME=<home>/Android/Sdk
$ ANDROID_HOME=/opt/ci-sdk mise exec -- env | grep ANDROID
ANDROID_HOME=/opt/ci-sdk
```

After switching to JDK 25 (owner decision):

```
$ mise install
mise all tools are installed     (exit 0)
$ mise exec -- java -version
openjdk version "25.0.5.1" 2026-11-17 LTS
```

The "Current proof" run below uses the final `mise.toml`. Attempts A and B are history: they ran before the switch to JDK 25 and before actionlint was added.

Note: the host also has a node outside mise, so the host run proves nothing about bun-only. The container runs below do.

### Container image (same for every run)

The build context holds the host mise binary (2026.11.10) and the repo's `mise.toml`.

```dockerfile
FROM debian:trixie-slim
RUN apt-get update -qq && apt-get install -y -qq --no-install-recommends ca-certificates >/dev/null
COPY mise /usr/local/bin/mise
WORKDIR /repo
COPY mise.toml /repo/mise.toml
RUN mise trust /repo/mise.toml
CMD ["bash"]
```

### Current proof: final `mise.toml` (image `recipe-box-s1c-final`)

The build context holds the committed `mise.toml` byte for byte (`diff` showed no difference). Command run in the container:

```bash
set -x
mise --version
command -v node || echo "no node on PATH"
mise install 2>&1 | grep -E "✓|ERROR|error|installed" ; echo "mise install exit=${PIPESTATUS[0]}"
mise exec -- bun --version; echo "exit=$?"
mise exec -- node --version; echo "exit=$?"
mise exec -- cf --version; echo "exit=$?"
mise exec -- cf --help 2>&1 | grep -m1 "The Cloudflare CLI"; echo "exit=${PIPESTATUS[1]}"
mise exec -- java -version 2>&1 | head -1
mise exec -- gitleaks version
mise exec -- actionlint -version | head -1
mise exec -- maestro --version 2>/dev/null | tail -1
```

Output (the `+ echo` trace lines are omitted):

```
+ mise --version
2026.11.10 linux-x64 (2026-11-18)
+ command -v node
no node on PATH
+ mise install
mise ✓ actionlint@1.7.12              2.9s  actionlint_1.7.12_linux_amd64.tar.gz
mise ✓ node@24.22.3                   3.0s  node-v24.22.3-linux-x64.tar.gz
mise ✓ gitleaks@8.30.1                3.2s  gitleaks_8.30.1_linux_x64.tar.gz
mise ✓ bun@1.4.5                      3.6s  bun-linux-x64.zip
mise ✓ npm:cf@1.0.0-rc.4              4.2s
mise ✓ java@temurin-25.0.5.1+2.0.LTS  12.7s  OpenJDK25U-jdk_x64_linux_hotspot_25.0.5.1_2.tar.gz
mise ✓ maestro@cli-2.12.1             19.2s  maestro.zip
mise ████████████████ 7/7 · installed 7 tools in 19.7s
mise install exit=0
+ mise exec -- bun --version
1.4.5
exit=0
+ mise exec -- node --version
v24.22.3
exit=0
+ mise exec -- cf --version
1.0.0-rc.4
exit=0
+ mise exec -- cf --help
The Cloudflare CLI - manage your Cloudflare resources
exit=0
+ mise exec -- java -version
openjdk version "25.0.5.1" 2026-11-17 LTS
+ mise exec -- gitleaks version
8.30.1
+ mise exec -- actionlint -version
1.7.12
+ mise exec -- maestro --version
2.12.1
```

### History, attempt A: `mise.toml` without node, JDK 21

```
+ command -v node
no node on PATH
+ mise install
mise ✓ gitleaks@8.30.1                 1.0s  gitleaks_8.30.1_linux_x64.tar.gz
mise ✓ bun@1.4.5                       5.4s  bun-linux-x64.zip
mise ✓ npm:cf@1.0.0-rc.4               8.3s
mise ✓ java@temurin-21.0.13.1+2.0.LTS  14.2s  OpenJDK21U-jdk_x64_linux_hotspot_21.0.13.1_2.tar.gz
mise ✓ maestro@cli-2.12.1              19.1s  maestro.zip
mise ████████████████ 5/5 · installed 5 tools in 19.4s
mise install exit=0
+ mise exec -- bun --version
1.4.5
+ mise exec -- cf --version
/root/.local/share/mise/installs/npm-cf/1.0.0-rc.4/node_modules/.bin/cf: 18: exec: node: not found
exit=127
+ mise exec -- java -version
openjdk version "21.0.13.1" 2026-11-17 LTS
+ mise exec -- gitleaks version
8.30.1
```

Extra check, same image:

```
+ mise exec -- bun /root/.local/share/mise/installs/npm-cf/1.0.0-rc.4/node_modules/cf/bin/cf --version
1.0.0-rc.4
exit=0
```

### History, attempt B: `mise.toml` with `node = "24.22.3"`, JDK 21

```
+ command -v node
no node on PATH
+ mise install
mise ✓ gitleaks@8.30.1                 1.6s  gitleaks_8.30.1_linux_x64.tar.gz
mise ✓ node@24.22.3                    4.0s  node-v24.22.3-linux-x64.tar.gz
mise ✓ bun@1.4.5                       5.7s  bun-linux-x64.zip
mise ✓ npm:cf@1.0.0-rc.4               6.2s
mise ✓ java@temurin-21.0.13.1+2.0.LTS  14.8s  OpenJDK21U-jdk_x64_linux_hotspot_21.0.13.1_2.tar.gz
mise ✓ maestro@cli-2.12.1              21.2s  maestro.zip
mise ████████████████ 6/6 · installed 6 tools in 21.6s
mise install exit=0
+ mise exec -- bun --version
1.4.5
+ mise exec -- node --version
v24.22.3
+ mise exec -- cf --version
1.0.0-rc.4
exit=0
+ mise exec -- cf --help | head -3
cf <command> [options]

The Cloudflare CLI - manage your Cloudflare resources
+ mise exec -- java -version
openjdk version "21.0.13.1" 2026-11-17 LTS
```

## Notes for the other workers

- Run tools through mise: `mise exec -- <tool> ...` (or activate mise in the shell). In CI, install mise, then run `mise install` in the repo root.
- `cf --version` prints only the bare version (`1.0.0-rc.4`). It does not name the product. To prove it is the Cloudflare CLI and not Cloud Foundry (which prints `cf version 8.x`), check `cf --help | grep -q "The Cloudflare CLI"`. Use this in the AC1 CI step.
- node is in `mise.toml` for `cf` and `wrangler dev` only. That does not change the bun-first rule: use `bun install` and `bun run`, not npm.
- `ANDROID_HOME` comes from mise `[env]` and respects an existing value. Gradle reads it, so no `local.properties` is needed.
- `JAVA_HOME` is set by mise to Temurin 25 inside `mise exec`. Android bytecode target: 17 (see JDK matrix).
