plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tropicalstream.tappong"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tropicalstream.tappong"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

// No external dependencies — pure Android SDK. Custom Canvas rendering,
// dual-draw binocular, self-contained trackpad input, synthesized SFX.
dependencies {
}
