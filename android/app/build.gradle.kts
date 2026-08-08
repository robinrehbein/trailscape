plugins {
    id("com.android.application")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

android {
    namespace = "io.github.robinrehbein.trailscape"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        applicationId = "io.github.robinrehbein.trailscape"
        // Das health-Plugin (Google Health Connect) setzt minSdkVersion 26
        // voraus; ohne diese Anhebung schlaegt der Manifest-Merge fehl.
        // Ansonsten wuerde weiterhin flutter.minSdkVersion (24) gelten.
        minSdk = maxOf(flutter.minSdkVersion, 26)
        targetSdk = flutter.targetSdkVersion
        // In CI pro Build hochzählen, damit Android neue APKs als Update erkennt.
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: flutter.versionCode
        versionName = flutter.versionName
    }

    signingConfigs {
        create("release") {
            // Eingecheckter Schlüssel: Die App wird ausschließlich als Sideload
            // über GitHub Releases verteilt; ohne stabile Signatur lehnt Android
            // jede Update-Installation ab.
            storeFile = file("release-keystore.jks")
            storePassword = "trailscape"
            keyAlias = "trailscape"
            keyPassword = "trailscape"
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

flutter {
    source = "../.."
}
