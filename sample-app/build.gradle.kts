// Deterministic target app for agent, policy and UI tests: risky-looking buttons with visible counters.
plugins {
    alias(libs.plugins.android.application)
}

// AGP 9.x integrates Kotlin support directly; kotlin-android plugin not needed.

android {
    namespace = "dev.egarcia.sampleapp"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.egarcia.sampleapp"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Kotlin 17 toolchain (AGP 9.x manages compiler)
kotlin { jvmToolchain(17) }
