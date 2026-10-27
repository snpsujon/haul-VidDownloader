import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Release signing comes from keystore.properties (local) or environment variables (CI).
// Neither is committed. See README "Signing".
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(key: String, env: String): String? =
    keystoreProps.getProperty(key) ?: System.getenv(env)

// The website for update checks and links. Change it here, or pass
// -PappSite=https://your.site (CI reads the HAUL_SITE repository variable).
val appSite = (findProperty("appSite") as String?)?.trimEnd('/') ?: "https://stackforge-pro.xyz"

android {
    namespace = "app.haul"
    compileSdk = 35

    defaultConfig {
        // The package name can never change after the first public release.
        applicationId = "app.haul.android"
        minSdk = 24
        targetSdk = 35
        // Release builds pass these from the git tag; see .github/workflows/build.yml.
        versionCode = (findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = (findProperty("versionName") as String?) ?: "0.1.0"

        buildConfigField("String", "UPDATE_URL", "\"$appSite/app/version.json\"")
        buildConfigField("String", "WEBSITE_URL", "\"$appSite\"")
        buildConfigField("String", "SOURCE_URL", "\"$appSite/source\"")
        buildConfigField("String", "PRIVACY_URL", "\"$appSite/privacy\"")
        buildConfigField("String", "TAKEDOWN_EMAIL", "\"takedown@stackforge-pro.xyz\"")
        buildConfigField("String", "YOUTUBEDL_ANDROID_VERSION", "\"${libs.versions.youtubedl.get()}\"")
    }

    signingConfigs {
        create("release") {
            val storePath = signingValue("storeFile", "HAUL_KEYSTORE_PATH")
            if (storePath != null) {
                storeFile = file(storePath)
                storePassword = signingValue("storePassword", "HAUL_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "HAUL_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "HAUL_KEY_PASSWORD")
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val release = signingConfigs.getByName("release")
            signingConfig = if (release.storeFile != null) release else signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            // With the release key present, debug builds use it too, so a debug build from any
            // machine that has the key installs over another. Without it, the SDK's debug key.
            val release = signingConfigs.getByName("release")
            if (release.storeFile != null) signingConfig = release
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    packaging {
        // yt-dlp, Python and FFmpeg are run as executables from the native library folder,
        // so they must be extracted on install.
        jniLibs.useLegacyPackaging = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    androidResources {
        generateLocaleConfig = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.datastore.preferences)
    implementation(libs.work.runtime.ktx)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.ui)
    implementation(libs.coil.compose)
    implementation(libs.coroutines.android)
    implementation(libs.documentfile)

    implementation(libs.youtubedl.library)
    implementation(libs.youtubedl.ffmpeg)
    implementation(libs.youtubedl.aria2c)

    testImplementation(libs.junit)
    // Android's org.json is a stub in unit tests; use the real one.
    testImplementation(libs.org.json)
}
