# Client dependency versions

Verified on 2026-11-24. Maven versions come from `maven-metadata.xml` on Maven Central (`https://repo1.maven.org/maven2/...`) or Google Maven (`https://dl.google.com/dl/android/maven2/...`); release dates are the GitHub release date where one exists, otherwise the `Last-Modified` date of the artifact's `.pom`. All versions are pinned in `gradle/libs.versions.toml`.

## Compatibility constraint

The Kotlin Multiplatform compatibility guide (https://kotlinlang.org/docs/multiplatform/multiplatform-compatibility-guide.html, read 2026-11-24) lists for Kotlin 2.4.20 to 2.4.21: Gradle 7.6.3 to 9.7.0, Android Gradle plugin 8.5.2 to 9.3.1, Xcode 26.4. Newer AGP (9.3.3, 9.5.2) and Gradle (9.8.x, 9.9.2) exist but are outside the tested range, so they are not used.

## Build toolchain

| Component | Exact version | Release date (UTC) | Source | Note |
|-----------|---------------|--------------------|--------|------|
| Kotlin (Gradle plugins `multiplatform`, `plugin.serialization`, `plugin.compose`) | 2.4.21 | 2026-10-08 | https://github.com/JetBrains/kotlin/releases/tag/v2.4.21 | 2.5.0-RC exists; stay on stable |
| Gradle (wrapper, `bin` distribution, SHA-256 pinned) | 9.7.0 | 2026-08-06 | https://services.gradle.org/versions/all | upper end of the tested range |
| Android Gradle plugin (`com.android.application`, `com.android.kotlin.multiplatform.library`) | 9.3.1 | 2026-07-23 | https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/maven-metadata.xml | upper end of the tested range |
| JDK running Gradle | Temurin 25.0.5.1+2 | 2026-11-17 | `mise.toml` (owned by worker-toolchain) | Android bytecode is compiled for Java 17 (`jvmTarget` 17 in `shared`, `compileOptions` 17 in `androidApp`) |
| JVM bytecode target (Android) | 17 | | https://developer.android.com/build/jdks (section "Java versions in Android builds", read 2026-11-24) | see "JVM target" below |
| Xcode | 26.4 or newer | | compatibility guide above | used only on the macOS CI runner |

## JVM target

The JDK in `mise.toml` (Temurin 25) only runs Gradle, compiles the code and runs the host tests. For bytecode the build sets Java 17 explicitly:

- `shared`: `kotlin { android { compilerOptions { jvmTarget = JvmTarget.JVM_17 } } }`
- `androidApp`: `compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }`. AGP 9 compiles Kotlin itself and takes the Kotlin `jvmTarget` from `targetCompatibility`.

`jvmToolchain(...)` is not used: it would make the bytecode target default to the toolchain version (25).

Why 17: in the table on https://developer.android.com/build/jdks, Java 17 is the newest language level Android supports (Android 14, API 34). D8 desugars language features for minSdk 24. AGP 9.3 needs JDK 17 or newer to run, so a Java 25 JDK can produce Java 17 bytecode.

Checked on 2026-11-24: class files from both modules have major version 61 (Java 17). Without these settings, `shared` produced major version 69 (Java 25) and `androidApp` produced 55 (Java 11). `check`, `koverVerify` and `:androidApp:assembleRelease` (with R8) pass with the target set to 17.

## Android SDK levels

| Setting | Value | Source | Note |
|---------|-------|--------|------|
| compileSdk | 37 | https://dl.google.com/android/repository/repository2-3.xml (`platforms;android-37.0` is stable) | Deviation from the plan's 36: the Compose 1.12.1 AARs inside Compose Multiplatform 1.12.2 declare `minCompileSdk` 37 in their AAR metadata, so `checkAarMetadata` fails with 36 |
| targetSdk | 36 | plan section 5 (developer.android.com target-sdk) | compileSdk can be higher than targetSdk |
| minSdk | 24 | plan section 4.1 | |

## Libraries

| Component | Coordinates | Exact version | Release date (UTC) | Source | Used by |
|-----------|-------------|---------------|--------------------|--------|---------|
| Compose Multiplatform (Gradle plugin, runtime, foundation, ui, ui-test) | `org.jetbrains.compose`, `org.jetbrains.compose.{runtime,foundation,ui}:*` | 1.12.2 | 2026-10-15 | https://github.com/JetBrains/compose-multiplatform/releases/tag/v1.12.2 | `shared` |
| Material 3 (Compose Multiplatform) | `org.jetbrains.compose.material3:material3` | 1.9.0 | 2025-10-13 | https://repo1.maven.org/maven2/org/jetbrains/compose/material3/material3/maven-metadata.xml | `shared`. Latest stable. The CMP 1.12.2 release notes pair it with `1.12.0-alpha03`; the plan requires stable, and 1.9.0 builds, renders and passes the tests with 1.12.2 |
| kotlinx.serialization JSON | `org.jetbrains.kotlinx:kotlinx-serialization-json` | 1.13.0 | 2026-11-11 | https://github.com/Kotlin/kotlinx.serialization/releases/tag/v1.13.0 | `shared` (contract DTO) |
| AndroidX Activity Compose | `androidx.activity:activity-compose` | 1.13.0 | 2026-03-11 | https://dl.google.com/dl/android/maven2/androidx/activity/activity-compose/maven-metadata.xml | `androidApp` (latest stable; 1.14.0 is alpha) |
| Compose UI test manifest | `androidx.compose.ui:ui-test-manifest` | 1.12.1 | 2026-09-09 | https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-test-manifest/maven-metadata.xml | `shared` Android host tests; the Jetpack UI release CMP 1.12.2 is based on |
| Robolectric | `org.robolectric:robolectric` | 4.18.1 | 2026-11-18 | https://github.com/robolectric/robolectric/releases/tag/robolectric-4.18.1 | `shared` Android host tests (supports API 24 to 37) |
| Roborazzi (Gradle plugin, `roborazzi`, `roborazzi-compose`) | `io.github.takahirom.roborazzi` | 1.79.0 | 2026-11-12 | https://github.com/takahirom/roborazzi/releases/tag/1.79.0 | `shared` screenshot tests |
| Kover | `org.jetbrains.kotlinx.kover` | 0.9.11 | 2026-09-29 | https://github.com/Kotlin/kotlinx-kover/releases/tag/v0.9.11 | `shared` coverage, line threshold 80 percent |
| JUnit 4 | `junit:junit` | 4.13.2 | 2021-02-13 | https://repo1.maven.org/maven2/junit/junit/maven-metadata.xml | Android host tests (runner API for Robolectric) |
| kotlin-test | `org.jetbrains.kotlin:kotlin-test` | 2.4.21 | 2026-10-08 | Kotlin release above | `commonTest` |

## Added in M1

Verified on 2026-11-24 at the same sources. Each library's Gradle module metadata was checked: all are built with Kotlin 2.4.21 or older (Ktor stdlib 2.3.21, coroutines 2.2.20, kotlinx-io 2.3.0, lifecycle 2.2.20, Coil 2.4.10, FileKit 2.4.21) and the JetBrains Compose libraries depend on Compose 1.11.0 or 1.12.2, which resolve to 1.12.2.

| Component | Coordinates | Exact version | Release date (UTC) | Source | Used by |
|-----------|-------------|---------------|--------------------|--------|---------|
| Ktor client | `io.ktor:ktor-client-{core,android,darwin,content-negotiation,mock}`, `io.ktor:ktor-serialization-kotlinx-json` | 3.6.1 | 2026-10-30 | https://github.com/ktorio/ktor/releases/tag/3.6.1 | `shared` (`data/Api.kt`); `android` engine in androidMain, `darwin` in iosMain, `mock` in commonTest |
| kotlinx.coroutines | `org.jetbrains.kotlinx:kotlinx-coroutines-{core,test}` | 1.11.0 | 2026-05-08 | https://github.com/Kotlin/kotlinx.coroutines/releases/tag/1.11.0 | `shared`; `-test` in commonTest |
| kotlinx-io | `org.jetbrains.kotlinx:kotlinx-io-core` | 0.9.3 | 2026-11-13 | https://github.com/Kotlin/kotlinx-io/releases/tag/0.9.3 | `shared` (`data/RecipeCache.kt` files) |
| Navigation Compose (JetBrains) | `org.jetbrains.androidx.navigation:navigation-compose` | 2.10.0 | 2026-10-23 | https://repo1.maven.org/maven2/org/jetbrains/androidx/navigation/navigation-compose/maven-metadata.xml | `shared` UI (CMP 1.12.2 notes list 2.10.0-rc01; 2.10.0 is the stable that followed) |
| Lifecycle ViewModel Compose (JetBrains) | `org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose` | 2.11.0 | 2026-07-13 | https://repo1.maven.org/maven2/org/jetbrains/androidx/lifecycle/lifecycle-viewmodel-compose/maven-metadata.xml | `shared` UI (the version paired with CMP 1.12.2) |
| Compose Multiplatform resources | `org.jetbrains.compose.components:components-resources` | 1.12.2 | 2026-10-15 | CMP release above | `shared` UI strings |
| Coil Compose | `io.coil-kt.coil3:coil-compose` | 3.6.3 | 2026-09-18 | https://github.com/coil-kt/coil/releases/tag/3.6.3 | `shared` UI; local files only, no network module |
| FileKit dialogs (Compose) | `io.github.vinceglb:filekit-dialogs-compose` (includes `filekit-dialogs` and `filekit-core`) | 0.17.1 | 2026-11-17 | https://repo1.maven.org/maven2/io/github/vinceglb/filekit-dialogs-compose/maven-metadata.xml | `shared` UI (camera, gallery, image compression); `androidApp` calls `FileKit.init(activity)` |
| Credential Manager | `androidx.credentials:credentials`, `androidx.credentials:credentials-play-services-auth` | 1.6.0 | 2026-04-08 | https://dl.google.com/dl/android/maven2/androidx/credentials/credentials/maven-metadata.xml | `shared` androidMain (`GoogleAuthProvider`); 1.7.0 is alpha |
| Google ID | `com.google.android.libraries.identity.googleid:googleid` | 1.2.1 | 2026-09-16 | https://dl.google.com/dl/android/maven2/com/google/android/libraries/identity/googleid/googleid/maven-metadata.xml | `shared` androidMain |

Build settings that M1 adds to `gradle.properties` (compiled into `com.linux.recipes.AppConfig` by `:shared:generateAppConfig`): `API_BASE_URL` (default `https://recipe.munich-tech.de`, https and no trailing slash enforced) and `GOOGLE_SERVER_CLIENT_ID` (empty until the owner supplies the Google OAuth web client id).

## Added in M2

Verified on 2026-11-25.

| Component | Coordinates | Exact version | Release date (UTC) | Source | Used by |
|-----------|-------------|---------------|--------------------|--------|---------|
| ML Kit text recognition v2, Latin script, bundled model | `com.google.mlkit:text-recognition` | 16.0.1 | 2024-08-07 (`Last-Modified` of the `.pom`) | https://dl.google.com/dl/android/maven2/com/google/mlkit/text-recognition/maven-metadata.xml (latest and release 16.0.1); API and the bundled versus unbundled choice from https://developers.google.com/ml-kit/vision/text-recognition/v2/android (read through Context7 `/websites/developers_google_ml-kit`, 2026-11-25) | `shared` androidMain (`platform/MlKitTextRecognizer.kt`) |
| Apple Vision (`VNRecognizeTextRequest`, `VNImageRequestHandler`) | system framework, Kotlin/Native platform library `platform.Vision` | iOS 13.0 or newer (deployment target 15.0) | | https://developer.apple.com/documentation/vision/vnrecognizetextrequest and https://developer.apple.com/documentation/vision/vndetectedobjectobservation/boundingbox (normalised box, origin lower left), read 2026-11-25 | `shared` iosMain (`platform/VisionTextRecognizer.kt`) |

The bundled ML Kit model is linked into the app (Google lists about 4 MB per script and architecture) and needs no Play Services download; the unbundled variant `com.google.android.gms:play-services-mlkit-text-recognition` (19.0.2) would download the model on first use and is not used. ML Kit's documentation names API level 23 as the minimum; the app's minSdk is 24.

## Added in M3

Verified on 2026-11-25.

| Component | Coordinates | Exact version | Release date (UTC) | Source | Used by |
|-----------|-------------|---------------|--------------------|--------|---------|
| RevenueCat Purchases for Kotlin Multiplatform | `com.revenuecat.purchases:purchases-kmp-core` (pulls `purchases-kmp-models`, `-mappings`, `-kn-core` and, on Android, `com.revenuecat.purchases:purchases` 11.3.0) | 4.2.0 | 2026-11-16 | https://repo1.maven.org/maven2/com/revenuecat/purchases/purchases-kmp-core/maven-metadata.xml (latest and release 4.2.0); https://github.com/RevenueCat/purchases-kmp/releases/tag/4.2.0 (bundles purchases-ios 5.103.0 and purchases-android 11.3.0) | `shared` commonMain (`platform/BillingProvider.kt`) |

Compatibility: 4.2.0 is built with Kotlin 2.3.20 and Compose Multiplatform 1.9.3 (its `gradle/libs.versions.toml` at tag 4.2.0; the `.module` file names `kotlin-stdlib` 2.3.20) and requires Kotlin 2.3.20 or newer (README, "Requirements"). It compiles and runs with this project's Kotlin 2.4.21 for Android and for `iosSimulatorArm64` (checked 2026-11-25 with `:shared:compileKotlinIosSimulatorArm64` and the Android host tests). 3.16.1 was published after 4.2.0 (2026-11-24) as a maintenance release of the 3.x line; 4.2.0 is the newest.

API used, read in the sources at tag 4.2.0 (`core/src/commonMain/.../Purchases.kt`, `ktx/Coroutines.kt`, `models/.../CustomerInfo.kt`, `PurchasesErrorCode.kt`) and the docs through Context7 (`/websites/revenuecat`, 2026-11-25): the whole API is in common code (`Purchases.configure(apiKey)`, `awaitLogIn`, `awaitLogOut`, `awaitOfferings().current.monthly/annual`, `storeProduct.price.formatted`, `awaitPurchase(package)` throwing `PurchasesTransactionException` with `userCancelled`, `awaitRestore()`, `CustomerInfo.managementUrlString`, `entitlements.active`). A pending purchase is the error `PaymentPendingError`. On Android the SDK finds the application and the current activity through AndroidX App Startup (https://www.revenuecat.com/docs/getting-started/installation/kotlin-multiplatform, read 2026-11-25); the activity keeps the default `standard` launch mode the page asks for.

iOS: since purchases-kmp 3.0.0 the native RevenueCat iOS SDK is compiled into the Kotlin library itself, and the app must not add `PurchasesHybridCommon` through Swift Package Manager or CocoaPods (https://github.com/RevenueCat/purchases-kmp/blob/4.2.0/migrations/3.0.0-MIGRATION.md). The installation page asks only for a static framework (`isStatic = true`, already set) and, for code that calls native types, an opt-in to `ExperimentalForeignApi` (not needed: this project calls only the common API). The library's own sample (`iosApp/iosApp.xcodeproj` at tag 4.2.0) has no package references and only `-framework` in `OTHER_LDFLAGS`, as this project. Minimum iOS 13.0; the deployment target is 15.0. Linking the framework needs Xcode, so it is checked by the macOS CI job, not on Linux.

iOS test executables need one more thing, which the installation page does not mention. The published cinterop klibs of 4.2.0 (and 3.9.0 to 3.11.0) record the Swift library search path of RevenueCat's build machine, `-L/Applications/Xcode-16.4.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/usr/lib/swift/iphonesimulator/`. Kotlin/Native calls `ld` directly rather than through the Swift driver, so on any other Xcode the link of `linkDebugTestIosSimulatorArm64` fails with `Undefined symbols ... __swift_FORCE_LOAD_$_swiftCompatibility56`. That is open issue https://github.com/RevenueCat/purchases-kmp/issues/1067 (2026-10-07; it shows the manifest `linkerOpts` and says the libraries exist in the installed Xcode). RevenueCat's own build adds `-rpath /usr/lib/swift` to its test executables (`build-logic/convention/src/main/kotlin/com/revenuecat/purchases/kmp/buildlogic/convention/configureKotlin.kt`) and generates the `-L$toolchain/lib/swift/<platform>/` options (`.../buildlogic/swift/task/GenerateDefFileTask.kt`). `shared/build.gradle.kts` therefore adds, on macOS hosts only, `-L<xcode-select -p>/Toolchains/XcodeDefault.xctoolchain/usr/lib/swift/<iphoneos|iphonesimulator>` and `-rpath /usr/lib/swift` to every iOS binary. The path comes from the selected Xcode at configuration time, so it follows the runner's Xcode. Any other library that ships Swift code inside its Kotlin/Native klib needs the same options. Remove them once #1067 is fixed upstream.

RevenueCat's own UI, `com.revenuecat.purchases:purchases-kmp-ui` 4.2.0 (same release), was checked for the M3 decision gate and is not used: its `Paywall(options)` and `CustomerCenter(modifier, onDismiss)` are `expect` composables in commonMain with actuals for Android (Jetpack Compose `purchases-ui`) and iOS (the UIKit paywall wrapped in a UIKit view), so it does support Compose Multiplatform on both platforms (`revenuecatui/src/{commonMain,androidMain,iosMain}` at tag 4.2.0; README: "Paywalls and Customer Center (the `-ui` artifact) are iOS only" refers to watchOS). Its look is configured in the RevenueCat dashboard, not by the app's theme, so the rules "no gradients, no hue 250 to 320, every string in `strings.xml` in 10 languages" could not be enforced by `tools/guards.ts`. The paywall is therefore hand-written (`ui/paywall`).

Build settings that M3 adds to `gradle.properties` (compiled into `AppConfig`): `REVENUECAT_ANDROID_KEY` and `REVENUECAT_IOS_KEY` (public SDK keys, empty by default, a `sk_` secret key fails the build), `TERMS_URL` and `PRIVACY_URL`.

## iOS project template

`iosApp/iosApp.xcodeproj/project.pbxproj`, `iosApp/iosApp/Info.plist` and `iosApp/Configuration/Config.xcconfig` are adapted from the official JetBrains template https://github.com/Kotlin/KMP-App-Template at commit `6b6d09e4f3f5e845116422d59b658791ed6149c6` (read 2026-11-24), licensed Apache License 2.0 (https://github.com/Kotlin/KMP-App-Template/blob/main/LICENSE). Changes: preview and asset-catalog entries removed, both Swift files merged into `iOSApp.swift`, deployment target 15.0, bundle id `$(APP_ID)` (`com.linux.recipes`, the same value as `APP_ID` in `gradle.properties`; the Kotlin package stays `com.linux.recipes`), display name `$(APP_NAME)` (`Recipes`) through `CFBundleDisplayName`, `OTHER_LDFLAGS = -framework Shared`, `x86_64` excluded for the simulator (only `iosSimulatorArm64` is built), shared scheme `iosApp` added (the template ships none).
