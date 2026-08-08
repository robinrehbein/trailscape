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

dependencies {
    // Fuer den eigenen Platform-Channel `trailscape/health_extra`: VO2max
    // (Vo2MaxRecord) bietet das Flutter-Paket `health` nicht an, Health
    // Connect selbst schon.
    //
    // Version bewusst identisch zu der, die das health-Plugin (health 13.3.1,
    // compileSdk 36) selbst deklariert. Stabil waere 1.1.0 (Okt. 2025), aber
    // das Plugin zieht 1.2.0-alpha02 herein und Gradle loest den Konflikt auf
    // dem *Runtime*-Classpath nach oben auf. Wuerden wir hier 1.1.0
    // deklarieren, kompilierte dieses Modul gegen 1.1.0 und liefe gegen
    // 1.2.0-alpha02 — bei Kotlin-Defaultargumenten (ReadRecordsRequest) ein
    // NoSuchMethodError-Risiko. Gleiche Version = gleicher Classpath.
    //
    // WICHTIG: Beim Aktualisieren des health-Pakets diese Zeile mitziehen
    // (siehe dessen android/build.gradle).
    implementation("androidx.health.connect:connect-client:1.2.0-alpha02")

    // readRecords ist eine suspend-Funktion. Nur -core, kein -android: der
    // Channel benutzt Dispatchers.Default und postet Ergebnisse selbst auf
    // den Main-Looper.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

flutter {
    source = "../.."
}
