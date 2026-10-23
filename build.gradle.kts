plugins {
    alias(libs.plugins.android.application) apply false
    // kotlin-android removed: AGP 9.x integrates Kotlin support directly
    // alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
