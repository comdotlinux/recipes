# Spike E2E: Maestro on an Android emulator against local `wrangler dev`

Date: 2026-11-24. Goal (plan section 8, AC36): a Maestro smoke flow on an Android emulator, against a local
`wrangler dev` started from `backend/src/index.local.ts` (test identity provider), locally and in CI.
`e2e/smoke.yaml` and `.github/workflows/e2e.yml` are not written yet: the screens and the test sign-in do not
exist, and a flow that cannot run would be unverifiable.

## a. This machine (Linux x86_64)

| Item | Result | Evidence |
|------|--------|----------|
| Android SDK | `/home/guru/Android/Sdk` (the `mise.toml` default; `ANDROID_HOME` is unset in the shell) | `mise exec -- sh -c 'echo $ANDROID_HOME'` |
| Emulator | 36.6.11.0 | `emulator -version` |
| System image | only `system-images;android-36;google_apis;x86_64` | `find $ANDROID_HOME/system-images` |
| KVM | usable; `/dev/kvm` is `crw-rw-rw- root kvm`, user is in group `kvm`, CPU has VT-x/AMD-V | `emulator -accel-check`: "KVM (version 12) is installed and usable." |
| AVD | none existed; created `e2e-probe-api36` (pixel_6, the image above) with `avdmanager create avd` | avdmanager writes to `$XDG_CONFIG_HOME/.android/avd` here, so the emulator needs `ANDROID_AVD_HOME=$HOME/.config/.android/avd` |
| Headless boot | booted in 40 s, `sys.boot_completed=1`, `ro.build.version.sdk=36` | `emulator -avd e2e-probe-api36 -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect -port 5580` |
| Maestro | `mise exec -- maestro --version` prints `2.12.1`; a two-step flow (`launchApp` com.android.settings, `assertVisible`) passed against the emulator | `maestro --device emulator-5580 test flow.yaml`: "Launch app ... COMPLETED", "Assert that ... is visible ... COMPLETED", exit 0 |
| Host reachable from the emulator | yes, at `10.0.2.2`, also for a server bound to `127.0.0.1` only | `toybox nc 10.0.2.2 8797` from `adb shell` returned the host server's body |
| `adb reverse` to `localhost` | not confirmed: `adb reverse tcp:8797 tcp:8797` was listed, but `toybox nc localhost 8797` returned nothing | not used in the proposal below |

Conclusion: this machine can run the whole flow once the app and the local Worker support it. The probe AVD
was left in place (nothing is deleted); the emulator was stopped with `adb emu kill`.

## b. CI: hardware-accelerated emulator on the Ubuntu runner

- Action: `ReactiveCircus/android-emulator-runner` v2.38.0 (latest release, 2026-07-05), commit
  `a421e43855164a8197daf9d8d40fe71c6996bb0d` (annotated tag `c9c93e6b...` dereferenced through the GitHub API).
- Runner: `ubuntu-26.04` (the image the other CI jobs use). The action's README recommends Ubuntu runners for
  hardware acceleration and requires KVM to be opened to the runner user first, with exactly this step:

  ```yaml
  - name: Enable KVM
    run: |
      echo 'KERNEL=="kvm", GROUP="kvm", MODE="0666", OPTIONS+="static_node=kvm"' | sudo tee /etc/udev/rules.d/99-kvm4all.rules
      sudo udevadm control --reload-rules
      sudo udevadm trigger --name-match=kvm
  ```
- The `ubuntu-26.04` image README lists Android platforms and build-tools but no emulator package or system
  image, so the action downloads both (from the SDK repository). The image installs its SDK packages with sdkmanager, which implies accepted licences, but I did not see the accepting command itself.
- Inputs to use: `api-level: 36`, `target: google_apis`, `arch: x86_64`, `profile: pixel_6`,
  `disable-animations: true` (default), `emulator-boot-timeout: 600` (default),
  `emulator-options: -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect`.
- The action runs every line of `script` as a separate command (`parseScript` in `src/main.ts` at v2.38.0), so
  background processes and `cd` do not carry over between lines. Plan: `wrangler dev` is started in a step
  before the action, and `script` is a single line `bash e2e/run-android.sh` (install the APK, run Maestro).
- Maestro comes from `mise.toml` (`maestro = "cli-2.12.1"`), installed by `jdx/mise-action` as in `ci.yml`.

Not verified yet: an actual run on GitHub (needs the workflow, which is deferred).

## c. Debug build reaching the local Worker without opening cleartext in release

Current state: `androidApp/src/main/AndroidManifest.xml` sets `android:usesCleartextTraffic="false"`, and
`shared/build.gradle.kts` compiles `API_BASE_URL` from `gradle.properties` into `commonMain` and rejects
anything that is not `https://`. That constant is shared by the debug and release variants, so it cannot
carry a debug-only URL.

Proposal (smallest change that touches debug builds only):

1. `androidApp/src/debug/res/xml/network_security_config.xml` (new): cleartext only to the emulator's host
   alias, nothing else.

   ```xml
   <network-security-config>
       <base-config cleartextTrafficPermitted="false" />
       <domain-config cleartextTrafficPermitted="true">
           <domain includeSubdomains="false">10.0.2.2</domain>
       </domain-config>
   </network-security-config>
   ```
2. `androidApp/src/debug/AndroidManifest.xml` (new): adds
   `android:networkSecurityConfig="@xml/network_security_config"` to `<application>`. The manifest merger adds it
   to the debug variant only. From API 24 (our minSdk) the network security config takes precedence over
   `usesCleartextTraffic`, so the main manifest stays unchanged.
3. `androidApp/build.gradle.kts`: `buildFeatures { buildConfig = true }`; in `buildTypes.debug` a
   `buildConfigField("String", "API_BASE_URL_OVERRIDE", ...)` filled from an optional Gradle property such as
   `-PDEBUG_API_BASE_URL=http://10.0.2.2:8787` (decided name; debug only, default `http://10.0.2.2:8787`); in `release` the field is always `null`.
4. The place in `androidApp/src/main/kotlin/...` that builds the app graph (`MainActivity` or the Android
   `AppGraph` factory) passes `BuildConfig.API_BASE_URL_OVERRIDE ?: AppConfig.API_BASE_URL` to the API client.
   The shared `Api` class already takes `baseUrl` as a constructor parameter (`shared/src/commonMain/kotlin/com/linux/recipes/data/Api.kt:111`).

Why this is safe: the release variant has no network security exception and its override field is `null`,
so even a wrong property cannot make a release build talk plain HTTP; the platform would block it. The shared
`https://` check stays for the production constant.

Rejected alternative: `wrangler dev --local-protocol https` with its self-signed certificate. It avoids
cleartext but needs the certificate trusted through `<debug-overrides>` and installed on the emulator; more
moving parts for the same debug-only result.

Files worker-client would change: items 1 to 4 above (two new files under `androidApp/src/debug/`,
`androidApp/build.gradle.kts`, and the Android graph wiring).

iOS (not in this spike): the simulator shares the host network, so `http://localhost:8787` works there, but
App Transport Security needs `NSAllowsLocalNetworking` in a debug-only Info.plist setting.
