plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

// Which commit a build came from, so an exported report says exactly what was running.
fun git(vararg args: String): String = runCatching {
    providers.exec { commandLine("git", *args); isIgnoreExitValue = true }.standardOutput.asText.get().trim()
}.getOrDefault("")

val gitSha = git("rev-parse", "--short", "HEAD").ifEmpty { "unknown" } +
    if (git("status", "--porcelain", "--untracked-files=no").isNotEmpty()) "-dirty" else ""

android {
    namespace = "dev.egarcia.harness"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.egarcia.harness"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "0.8.0-test"
        ndk {
            // Local Whisper is built for real phones (arm64) and the emulator (x86_64) only; models are downloaded later.
            // -PabiOnly=arm64-v8a builds the smaller phone-only APK (about 2 MB less than the universal one).
            abiFilters += ((findProperty("abiOnly") as String?)?.split(",")?.toSet() ?: setOf("arm64-v8a", "x86_64"))
        }
        externalNativeBuild {
            cmake {
                // Debug builds must be optimised too, or speech recognition is unusably slow.
                arguments += listOf("-DCMAKE_BUILD_TYPE=Release", "-DANDROID_STL=c++_static")
            }
        }
        buildConfigField("String", "GIT_SHA", "\"$gitSha\"")
    }

    ndkVersion = "28.2.13676358"
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    lint {
        abortOnError = true
        // The app is English-only for now, and dependency/plugin updates are handled by Dependabot.
        disable += setOf("SetTextI18n", "NewerVersionAvailable", "AndroidGradlePluginVersion", "GradleDependency")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/LICENSE.md", "/META-INF/NOTICE.md", "/META-INF/INDEX.LIST", "/META-INF/io.netty.versions.properties")
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.android.mail)
    implementation(libs.android.activation)

    testImplementation(libs.junit)
    // Real IMAP server in memory. Its own copy of JavaMail is dropped so the tests run against the same classes as the app.
    testImplementation(libs.greenmail) {
        exclude(group = "com.sun.mail", module = "javax.mail")
        exclude(group = "com.sun.mail", module = "jakarta.mail")
    }
    testImplementation(libs.kotlinx.coroutines.test)
}
