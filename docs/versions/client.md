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

## iOS project template

`iosApp/iosApp.xcodeproj/project.pbxproj`, `iosApp/iosApp/Info.plist` and `iosApp/Configuration/Config.xcconfig` are adapted from the official JetBrains template https://github.com/Kotlin/KMP-App-Template at commit `6b6d09e4f3f5e845116422d59b658791ed6149c6` (read 2026-11-24), licensed Apache License 2.0 (https://github.com/Kotlin/KMP-App-Template/blob/main/LICENSE). Changes: preview and asset-catalog entries removed, both Swift files merged into `iOSApp.swift`, deployment target 15.0, bundle id `$(APP_ID)` (`de.munichtech.recipes`, the same value as `APP_ID` in `gradle.properties`), display name `$(APP_NAME)` (`Recipes`) through `CFBundleDisplayName`, `OTHER_LDFLAGS = -framework Shared`, `x86_64` excluded for the simulator (only `iosSimulatorArm64` is built), shared scheme `iosApp` added (the template ships none).
