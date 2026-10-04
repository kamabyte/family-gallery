import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// SMB connection — from local.properties (not in git), keys gallery.*; see README.
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}
fun galleryProp(key: String, default: String): String =
    (localProps.getProperty("gallery.$key") ?: default).replace("\\", "\\\\").replace("\"", "\\\"")

android {
    namespace = "com.familygallery.tv"
    compileSdk = 35

    defaultConfig {
        // One APK serves both form factors: it installs on Android TV *and* on phones/tablets,
        // and picks a D-pad or a touch shell at runtime (see FormFactor). The applicationId keeps
        // its historical ".tv" suffix so existing TV installs upgrade in place rather than
        // appearing as a second app.
        applicationId = "com.familygallery.tv"
        // Covers older/cheaper Android TV boxes (Android 7+) and every phone from Android P
        // (API 28) upward, which is the oldest phone we target.
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "GALLERY_HOST", "\"${galleryProp("host", "192.168.1.10")}\"")
        buildConfigField("String", "GALLERY_SHARE", "\"${galleryProp("share", "Media")}\"")
        buildConfigField("String", "GALLERY_BASE_PATH", "\"${galleryProp("basePath", "")}\"")
        buildConfigField("String", "GALLERY_USERNAME", "\"${galleryProp("username", "")}\"")
        buildConfigField("String", "GALLERY_PASSWORD", "\"${galleryProp("password", "")}\"")
        buildConfigField("String", "GALLERY_DOMAIN", "\"${galleryProp("domain", "")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        // Release-like, signed with the debug key and profileable, so Macrobenchmark can
        // measure realistic (minified, non-debuggable) startup/scroll on a real device.
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            isDebuggable = false
            isProfileable = true
            proguardFiles("benchmark-rules.pro")
        }
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
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.compose.material3)

    // Lifecycle / ViewModel
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // Data layer — direct SQLite (Android framework) + Paging 3.
    // The catalog is authored by the Python indexer and read directly, so no ORM.
    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)

    // Image loading + SMB
    implementation(libs.coil.compose)
    implementation(libs.smbj)

    // Video playback (stretch goal)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)

    // Unit tests (JVM)
    testImplementation(libs.junit)
    testImplementation(libs.json)
    testImplementation(libs.kotlinx.coroutines.test)
}
