plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.kotlin.android)
}

/**
 * Macrobenchmark module for the critical TV journeys (startup, timeline scrolling, tab
 * switching, viewer open/close, album return, cold-vs-warm image cache).
 *
 * It measures the app's `benchmark` build type (release-like + profileable) on a REAL
 * device — benchmarks cannot run on an emulator meaningfully and there is no TV device in
 * this environment, so this module is wired to compile/configure now and be run later. See
 * macrobenchmark/README.md for the exact commands to run on the weakest target box.
 */
android {
    namespace = "com.familygallery.tv.macrobenchmark"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // Only benchmark the release-like, profileable build of the app.
    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true

    buildTypes {
        create("benchmark") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}

androidComponents {
    beforeVariants(selector().all()) {
        // Only the `benchmark` variant is meaningful for this module.
        it.enable = it.buildType == "benchmark"
    }
}
