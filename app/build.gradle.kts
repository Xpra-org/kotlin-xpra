// AGP 9 has built-in Kotlin support, so there is no `org.jetbrains.kotlin.android` here:
// applying it is now an error. The compose and serialization compiler plugins are still
// applied separately.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "org.xpra.client"
    // 37 rather than 36 because androidx.lifecycle 2.11 requires it.
    compileSdk = 37

    defaultConfig {
        applicationId = "org.xpra.client"
        minSdk = 26
        targetSdk = 37
        // Release CI passes both from the tag (-PversionName=1.2 -PversionCode=<run number>).
        versionCode = (findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = findProperty("versionName") as String? ?: "0.1"
    }

    // The release key comes from the environment so the keystore never enters the repository:
    // CI decodes it from a secret. Without XPRA_KEYSTORE, assembleRelease still builds, but the
    // APK is unsigned and will not install.
    signingConfigs {
        System.getenv("XPRA_KEYSTORE")?.let { path ->
            create("release") {
                storeFile = file(path)
                storePassword = System.getenv("XPRA_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("XPRA_KEY_ALIAS")
                keyPassword = System.getenv("XPRA_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        // Forward -DxpraServer=host:port through to the test JVM so ServerSmokeTest can find
        // a real server. Without a value that test skips itself.
        unitTests.all {
            it.systemProperty("xpraServer", System.getProperty("xpraServer") ?: "")
            it.testLogging {
                showStandardStreams = true
            }
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}
