// AGP 9 bringt Kotlin-Unterstuetzung (inkl. Compose-Compiler) selbst mit; das
// frueher noetige Plugin `org.jetbrains.kotlin.android` wird von AGP 9 aktiv
// abgelehnt.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release-Signierung ausschliesslich aus der Umgebung: Der Schluessel gehoert
// nicht ins Repository. Fehlen die Variablen (lokale Builds, Forks, PR-Builds
// ohne Secret-Zugriff), faellt der Build bewusst auf den Debug-Schluessel
// zurueck — so bleibt die Pipeline gruen, das APK ist dann aber nicht fuer
// Updates der offiziellen Installation geeignet.
val releaseKeystorePath: String? = System.getenv("RELEASE_KEYSTORE_PATH")
val releaseKeystorePassword: String? = System.getenv("RELEASE_KEYSTORE_PASSWORD")
val releaseKeystore = releaseKeystorePath
    ?.takeIf { it.isNotBlank() && !releaseKeystorePassword.isNullOrBlank() }
    ?.let { rootProject.file(it) }
    ?.takeIf { it.isFile }

android {
    namespace = "de.trailscape.app"
    compileSdk = 36

    defaultConfig {
        // Bewusst mit `.beta`-Suffix: Die native App muss sich parallel zur
        // bestehenden Flutter-App (io.github.robinrehbein.trailscape)
        // installieren lassen, solange der Rewrite laeuft.
        applicationId = "io.github.robinrehbein.trailscape.beta"
        minSdk = 26
        targetSdk = 36
        // Offset 2000, damit der Zaehler sicher ueber allen bisher von der
        // Flutter-Pipeline vergebenen versionCodes liegt.
        versionCode = (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1) + 2000
        versionName = "2.0.0-alpha"
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = releaseKeystorePassword
                keyAlias = "trailscape"
                keyPassword = releaseKeystorePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (releaseKeystore != null) {
                signingConfigs.getByName("release")
            } else {
                logger.warn(
                    "Trailscape: RELEASE_KEYSTORE_PATH/RELEASE_KEYSTORE_PASSWORD nicht gesetzt " +
                        "— Release-APK wird mit dem Debug-Schluessel signiert und ist NICHT " +
                        "als Update fuer die verteilte App installierbar.",
                )
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":core"))

    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    // Nur der Kern-Satz an Icons: material-icons-extended zieht mehrere tausend
    // Vektoren ins APK, und Minifizierung ist in dieser Phase noch aus.
    implementation("androidx.compose.material:material-icons-core")

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.navigation:navigation-compose:2.9.8")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
