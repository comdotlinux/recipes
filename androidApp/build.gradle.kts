import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.linux.recipes.android"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = providers.gradleProperty("APP_ID").get()
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        debug {
            // Debug only: the local Worker. The emulator reaches the host at 10.0.2.2 (cleartext allowed for that
            // host only, src/debug/res/xml/network_security_config.xml). Release has neither field nor override.
            val debugBaseUrl = providers.gradleProperty("DEBUG_API_BASE_URL").getOrElse("http://10.0.2.2:8787")
            require(Regex("^https?://[^/]+$").matches(debugBaseUrl)) { "DEBUG_API_BASE_URL must be scheme://host[:port]: $debugBaseUrl" }
            buildConfigField("String", "DEBUG_API_BASE_URL", "\"$debugBaseUrl\"")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.filekit.dialogs.compose)
}

// The release APK must carry none of the debug-only local-Worker pieces (src/debug): no cleartext, no
// network security config, no test sign-in, no base URL override. Every APK entry is scanned (dex, resources.arsc,
// res/, assets/, lib/, META-INF/), since resource shrinking may rename res/ paths. verifyReleaseIsolation is part of
// `check`; verifyDebugIsolation exists to show the scan detects them and is expected to fail.
androidComponents {
    onVariants { variant ->
        val apkDir = variant.artifacts.get(com.android.build.api.artifact.SingleArtifact.APK)
        val manifest = variant.artifacts.get(com.android.build.api.artifact.SingleArtifact.MERGED_MANIFEST)
        val verify = tasks.register("verify${variant.name.replaceFirstChar { it.uppercase() }}Isolation") {
            inputs.dir(apkDir)
            inputs.file(manifest)
            doLast {
                val problems = mutableListOf<String>()
                val merged = manifest.get().asFile.readText()
                if ("networkSecurityConfig" in merged) problems += "manifest sets a network security config"
                if ("android:usesCleartextTraffic=\"false\"" !in merged) problems += "manifest does not forbid cleartext"
                val apks = apkDir.get().asFile.listFiles { f -> f.extension == "apk" }.orEmpty()
                if (apks.isEmpty()) problems += "no APK in ${apkDir.get().asFile}"
                // The last two: classes under earlier package names (stale generated sources) must not ship.
                val forbidden = listOf(
                    "__TEST_IDP__", "TestAuthProvider", "DEBUG_API_BASE_URL", "10.0.2.2", "network_security_config",
                    "Lapp/recipebox/", "Lde/munichtech/",
                )
                apks.forEach { apk ->
                    ZipFile(apk).use { zip ->
                        zip.entries().asSequence().filter { !it.isDirectory }.forEach { entry ->
                            val text = String(zip.getInputStream(entry).readBytes(), Charsets.ISO_8859_1)
                            forbidden.filter { it in text || it in entry.name }.forEach { problems += "$it found in ${apk.name}!${entry.name}" }
                        }
                    }
                }
                check(problems.isEmpty()) { problems.joinToString("\n") }
            }
        }
        if (variant.buildType == "release") tasks.named("check") { dependsOn(verify) }
    }
}
