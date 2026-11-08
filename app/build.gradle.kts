plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.github.oustery.opencodephone"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.oustery.opencodephone"
        minSdk = 26
        // Deliberately pinned to 28, NOT the latest API — see ADR-007.
        // Android 10+ forbids execve() of files under app data for apps
        // targeting API 29+ (SELinux untrusted_app_29 noexec on app_data_file).
        // proot must execve guest binaries straight out of the rootfs in app
        // data, so the app must keep the legacy untrusted_app_27 domain.
        targetSdk = 28
        versionCode = 2
        versionName = "0.1.1"
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
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
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
        // targetSdk 28 (ExpiredTargetSdkVersion), so vital checks are off.
        checkReleaseBuilds = false
        abortOnError = false
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

    debugImplementation(libs.compose.ui.tooling)
}
