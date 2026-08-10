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
        // Die Kennung der ausgelieferten App. Waehrend des Rewrites trug der
        // native Build hier ein `.beta`-Suffix, damit er sich neben der alten
        // App installieren liess; dieser Parallelbetrieb ist beendet, die
        // native App IST jetzt Trailscape. Weil der Signierschluessel der
        // alten Pipeline nicht uebernommen wurde, ist der Umstieg von 1.x eine
        // einmalige Neuinstallation (siehe README, Abschnitt "Umstieg").
        applicationId = "io.github.robinrehbein.trailscape"
        minSdk = 26
        targetSdk = 36
        // Offset 2000, damit der Zaehler sicher ueber allen bisher von der
        // Flutter-Pipeline vergebenen versionCodes liegt.
        //
        // Dieselbe Lauf-Nummer steckt im Namen: Die CI legt zu jedem
        // main-Push ein Release `v2.0.<GITHUB_RUN_NUMBER>` an (siehe
        // .github/workflows/build.yml), und der In-App-Update-Check rechnet
        // aus dem versionCode die Lauf-Nummer zurueck
        // (`update/UpdateLogic.kt`, Offset unten gespiegelt als
        // VERSION_CODE_OFFSET). Anzeige, Tag und Code muessen deshalb
        // zusammenpassen — ein fest verdrahtetes "2.0.0" wuerde in der App
        // eine andere Version zeigen als das Release, aus dem sie stammt.
        //
        // Lokale Builds haben keine Lauf-Nummer und heissen darum
        // "2.0.0-dev": unverwechselbar und, weil die Nummer daraus nicht als
        // Tag existiert, ohne Anspruch auf einen Platz im Update-Kanal.
        val runNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
        versionCode = (runNumber ?: 1) + 2000
        versionName = if (runNumber != null) "2.0.$runNumber" else "2.0.0-dev"
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
            // R8 in der Voll-Optimierung: entfernt ungenutzten Code (vor allem
            // die grossen Bibliotheken MapLibre, Health Connect und
            // play-services, von denen die App jeweils nur einen Ausschnitt
            // benutzt) und danach die Ressourcen, auf die kein Code mehr
            // zeigt. Die noetigen Ausnahmen stehen in proguard-rules.pro.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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
    // Voller Icon-Satz statt nur material-icons-core: Die Kern-Auswahl deckte
    // Symbole wie "Route", "MyLocation" oder "DownloadForOffline" nicht ab,
    // was zu zweckentfremdeten bzw. selbst auf Canvas gezeichneten Icons
    // fuehrte. Seit Phase 6 ist R8 in der Voll-Optimierung aktiv (siehe
    // `isMinifyEnabled` oben) und entfernt aus dem Vektor-Berg alles, was
    // keine `Icons.Filled.X`-Referenz im Code erreicht — der Zuwachs im
    // fertigen APK bleibt dadurch im niedrigen dreistelligen KB-Bereich,
    // waehrend die Screens durchgehend passende Symbole bekommen.
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    // collectAsStateWithLifecycle() — die Screens lesen die StateFlows des
    // AppViewModel lebenszyklusbewusst, damit im Hintergrund nichts sammelt.
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.navigation:navigation-compose:2.9.8")

    // Kartendarstellung (Vektor-/Rasterkacheln, OpenGL). Bewusst die 11.x-Reihe:
    // Sie ist die letzte Serie mit der etablierten `org.maplibre.android.*`-API,
    // auf der die gesamte oeffentliche Dokumentation beruht — 12.x/13.x bringen
    // Umbauten an Style-/Annotation-APIs, die nur Risiko ohne Nutzen brachten.
    // 11.13.5 ist die neueste stabile 11er-Version und loest
    // gegen AGP 9.0.1 / compileSdk 36 konfliktfrei auf.
    implementation("org.maplibre.gl:android-sdk:11.13.5")

    // :core deklariert kotlinx-serialization-json nur als `implementation`
    // (bewusst, siehe core/build.gradle.kts-Kommentar), es ist also nicht
    // transitiv fuer :app sichtbar. RideStorage.kt braucht aber Json/JsonObject
    // direkt (zum Parsen bzw. Serialisieren von Ride.toJson()/fromJson()) —
    // deshalb hier explizit in derselben Version wie in :core, ohne das
    // `:core`-Build-Skript anzufassen.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Dispatchers.IO/Main fuer die kommenden ViewModels und den
    // Recording-Service; `:core` selbst bleibt bewusst frei von Coroutines.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // Implementierung von de.trailscape.core.HttpClient (siehe
    // data/OkHttpClientAdapter.kt). Bewusst die letzte 4.x-Version, nicht die
    // aktuellere 5.x-Reihe: 5.x aendert u. a. die Default-Dispatcher-/
    // Coroutine-Integration und ist fuer die schmale synchrone Nutzung hier
    // (ein Call pro HttpClient.execute) nicht noetig.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Health Connect: bewusst die stabile 1.1.0-Reihe, nicht die 1.2.0-alpha.
    // Version 1.x der App war nur deshalb an die Alpha gebunden, weil das
    // damals benutzte Plugin es so verlangte — diese Fesselung gibt es nicht
    // mehr, und Produktivcode soll nicht auf Vorab-APIs sitzen. 1.1.0 loest
    // gegen compileSdk 36 / AGP 9.0.1 konfliktfrei auf.
    implementation("androidx.health.connect:connect-client:1.1.0")

    // Fused Location Provider fuer den Aufzeichnungs-Service (record/).
    implementation("com.google.android.gms:play-services-location:21.4.0")
    // play-services-location zieht transitiv androidx.fragment 1.1.0 herein;
    // ohne diese Anhebung schlaegt lintVitalRelease an (InvalidFragmentVersionForActivityResult).
    implementation("androidx.fragment:fragment:1.8.6")

    debugImplementation("androidx.compose.ui:ui-tooling")

    // Nur Test-Klassenpfad, kein Bestandteil des APK. `:app` hat bewusst KEIN
    // Robolectric: getestet werden hier ausschliesslich Klassen ohne
    // Android-Imports — derzeit `record/RecordingJournal.kt`, das
    // Absturzsicherungs-Journal der Aufzeichnung. Version identisch zum
    // Kotlin-Plugin im Root-Build, damit kein zweiter Kotlin-Stack entsteht.
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5:2.3.20")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

// Gleiche Test-Engine wie in `:core` (siehe core/build.gradle.kts).
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
