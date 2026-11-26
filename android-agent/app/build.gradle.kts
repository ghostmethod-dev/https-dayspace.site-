plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "site.dayspace.phoneagent"
    compileSdk = 35

    defaultConfig {
        applicationId = "site.dayspace.phoneagent"
        minSdk = 30
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
    }

    // Fixed signing key so new builds install as updates over old ones.
    // It is a non-secret sideload key, not a Play Store key.
    signingConfigs {
        create("shared") {
            storeFile = file("phone-agent.keystore")
            storePassword = "android"
            keyAlias = "phoneagent"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
        debug {
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
