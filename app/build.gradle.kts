plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.github.oustery.opencodephone"
    compileSdk = 37

    // Release workflow (.github/workflows/release.yml) passes the tag-driven
    // version via -PversionName / -PversionCode; local builds use the defaults.
    val ciVersionName = providers.gradleProperty("versionName").filter { it.isNotBlank() }
    val ciVersionCode = providers.gradleProperty("versionCode")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { it.toInt() }

    defaultConfig {
        applicationId = "io.github.oustery.opencodephone"
        minSdk = 26
        // Deliberately pinned to 28, NOT the latest API — see ADR-007.
        // Android 10+ forbids execve() of files under app data for apps
        // targeting API 29+ (SELinux untrusted_app_29 noexec on app_data_file).
        // proot must execve guest binaries straight out of the rootfs in app
        // data, so the app must keep the legacy untrusted_app_27 domain.
        targetSdk = 28
        versionCode = ciVersionCode.getOrElse(4)
        versionName = ciVersionName.getOrElse("0.1.3")
        manifestPlaceholders["appLabel"] = "OpenCodePhone"
    }

    // One APK per architecture: each APK bundles only the matching proot binary.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }

    signingConfigs {
        val keystorePath = System.getenv("ANDROID_KEYSTORE_FILE")
        val keystorePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
        val aliasName = System.getenv("ANDROID_KEY_ALIAS")
        val aliasPassword = System.getenv("ANDROID_KEY_PASSWORD")
        if (!keystorePath.isNullOrBlank() && !keystorePassword.isNullOrBlank()) {
            create("ciRelease") {
                storeFile = file(keystorePath)
                storePassword = keystorePassword
                keyAlias = aliasName ?: "opencodephone"
                keyPassword = aliasPassword ?: keystorePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            val ciRelease = signingConfigs.findByName("ciRelease")
            if (ciRelease != null) {
                signingConfig = ciRelease
            }
        }

        // Dev channel: release-grade build of the same commit, published as a
        // vX.Y.Z-dev.N pre-release. Separate application id (installs side by
        // side with the stable app) and launcher label. The in-app updater only
        // ever selects non-prerelease releases, so dev builds never receive
        // stable update cards (their versionName does not match v?X.Y.Z either).
        create("dev") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            applicationIdSuffix = ".dev"
            manifestPlaceholders["appLabel"] = "OpenCodePhone Dev"
            matchingFallbacks += listOf("release")
            val ciRelease = signingConfigs.findByName("ciRelease")
            if (ciRelease != null) {
                signingConfig = ciRelease
            }
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

    packaging {
        // proot is bundled as a native library and must be extracted to
        // nativeLibraryDir so the app can exec it.
        jniLibs {
            useLegacyPackaging = true
        }
    }

    lint {
        // Not distributed through Play Store, so Play's targetSdk floor does
        // not apply; lint would still abort the release build on the pinned
        // targetSdk 28. Only that intentional policy exception is suppressed.
        checkReleaseBuilds = true
        abortOnError = true
        disable += "ExpiredTargetSdkVersion"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:opencode"))
    implementation(project(":core:linuxenv"))
    implementation(project(":core:settings"))
    implementation(project(":core:designsystem"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.service)
    implementation(libs.navigation.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    testImplementation(libs.junit)

    debugImplementation(libs.compose.ui.tooling)
}

// Keep verification part of the existing Gradle entrypoint, without editing workflows.
val nativeRegressions by tasks.registering(Exec::class) {
    group = "verification"
    workingDir(rootProject.projectDir)
    commandLine("python3", rootProject.file("tools/test_proot_seccomp.py").absolutePath)
}
tasks.matching { it.name == "test" }.configureEach { dependsOn("lintDebug", "assembleRelease", nativeRegressions) }
