// Wear-OS-Spike: ein eigenstaendiges APK, das auf der Uhr aufzeichnet und
// dabei protokolliert, was sich nur auf echter Hardware klaeren laesst.
//
// Wie `:app` ohne `org.jetbrains.kotlin.android`: AGP 9 bringt die
// Kotlin-Unterstuetzung (inkl. Compose-Compiler) selbst mit und lehnt das
// alte Plugin aktiv ab.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Signier-Logik woertlich aus `app/build.gradle.kts` uebernommen — und das ist
// keine Bequemlichkeit, sondern Voraussetzung:
//
// Uhr-App und Telefon-App duerfen nur dann ueber den Data Layer miteinander
// reden, wenn sie denselben Paketnamen UND dieselbe Signatur tragen. Sobald
// aus diesem Spike eine echte Begleit-App wird, muessen `:app` und `:wear`
// also aus demselben Keystore kommen. Wer hier einen zweiten Schluessel
// einfuehrt, merkt den Fehler erst auf dem Geraet — die Kopplung schlaegt
// dann kommentarlos fehl.
//
// Bewusst dupliziert statt in ein Convention-Plugin gehoben: Fuer einen
// Prototyp waere ein `buildSrc`/`build-logic`-Verzeichnis mehr Bauwerk als
// Nutzen. Wenn `:wear` bleibt, gehoert dieser Block als Erstes dorthin.
val releaseKeystorePath: String? = System.getenv("RELEASE_KEYSTORE_PATH")
val releaseKeystorePassword: String? = System.getenv("RELEASE_KEYSTORE_PASSWORD")
val releaseKeystore = releaseKeystorePath
    ?.takeIf { it.isNotBlank() && !releaseKeystorePassword.isNullOrBlank() }
    ?.let { rootProject.file(it) }
    ?.takeIf { it.isFile }

android {
    namespace = "de.trailscape.wear"
    compileSdk = 36

    defaultConfig {
        // EXAKT dieselbe applicationId wie `:app` — siehe Kommentar zur
        // Signierung oben. Paketname und Signatur zusammen sind Googles
        // Bedingung dafuer, dass eine Wear-App als Begleiter einer
        // Telefon-App gilt; ein `.wear`-Suffix waere bequem, wuerde die
        // spaetere Data-Layer-Kommunikation aber unmoeglich machen.
        //
        // Ein Konflikt entsteht daraus nicht: Uhr und Telefon sind
        // verschiedene Geraete, dieselbe applicationId kann auf beiden
        // gleichzeitig installiert sein.
        applicationId = "io.github.robinrehbein.trailscape"
        // Wear OS 5 auf der Galaxy Watch Ultra ist API 34; minSdk 30 deckt
        // zusaetzlich Wear OS 3 ab, ohne dass am Code etwas anders waere.
        minSdk = 30
        targetSdk = 36

        // Ein Play-Konto kennt pro applicationId genau EINEN versionCode je
        // Artefakt-Reihe. Weil Uhr und Telefon hier denselben Paketnamen
        // teilen, muessen sich ihre versionCodes zwingend unterscheiden —
        // sonst gilt das eine APK als Neuauflage des anderen und der Store
        // (bzw. `adb install`) lehnt die Installation ab.
        //
        // Deshalb derselbe Ausdruck wie in `:app` plus 1_000_000: Der Abstand
        // ist so gross, dass die Lauf-Nummern der CI ihn auf absehbare Zeit
        // nicht einholen, und man einem versionCode auf einen Blick ansieht,
        // von welchem Modul er stammt (2xxx = Telefon, 1002xxx = Uhr).
        val runNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
        versionCode = (runNumber ?: 1) + 2000 + 1_000_000
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
            // BEWUSSTE, TEMPORAERE Abweichung von `:app`, das R8 in der
            // Voll-Optimierung faehrt: Dieser Spike soll Fragen ueber die
            // Uhr beantworten — welche DataTypes sie liefert, wie dicht die
            // GPS-Punkte kommen, was das den Akku kostet. Waere R8 aktiv,
            // koennte jede Ueberraschung auf dem Geraet auch eine
            // weggeschrumpfte Klasse oder eine fehlende Keep-Regel sein, und
            // jede Messung muesste erst gegen diese zweite Erklaerung
            // verteidigt werden. Ein Spike, dessen Messergebnisse man
            // anzweifeln muss, ist wertlos.
            //
            // Wenn aus `:wear` ein Produkt wird, ist das Einschalten von R8
            // (samt eigener proguard-rules.pro fuer health-services-client)
            // eine der ersten Aufgaben.
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = if (releaseKeystore != null) {
                signingConfigs.getByName("release")
            } else {
                logger.warn(
                    "Trailscape (:wear): RELEASE_KEYSTORE_PATH/RELEASE_KEYSTORE_PASSWORD nicht " +
                        "gesetzt — Spike-APK wird mit dem Debug-Schluessel signiert. Fuer den " +
                        "Geraetetest reicht das; fuer eine spaetere Data-Layer-Kopplung mit " +
                        ":app nicht, die verlangt denselben Schluessel in beiden Modulen.",
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
    // Frage 4 des Spikes: Laeuft `:core` unveraendert auf der Uhr? `:core` ist
    // reines Kotlin/JVM ohne Android-Bezug, es SOLLTE also gehen — aber genau
    // das ist die Behauptung, die der Spike pruefen soll. Die Oberflaeche
    // rechnet die Distanz zusaetzlich mit `computeStats()` und stellt sie
    // neben die von Health Services gemeldete.
    implementation(project(":core"))

    // Health Services ist der EINZIGE unterstuetzte Weg an Standort und
    // Herzfrequenz einer Wear-Uhr waehrend eines Trainings; der Fused
    // Location Provider ist auf der Uhr weder sparsam noch zuverlaessig.
    //
    // Bewusst 1.0.0 (stabil) und nicht 1.1.0: Letztere haengt seit Monaten im
    // RC fest. 1.0.0 bringt die Coroutine-Erweiterungen bereits mit
    // (`ExerciseClientExtension.kt`: getCapabilities(), prepareExercise(),
    // startExercise(), …), deshalb ist KEIN kotlinx-coroutines-guava noetig —
    // die ListenableFuture-Rueckgaben der `…Async`-Methoden werden nirgends
    // von Hand ueberbrueckt.
    implementation("androidx.health:health-services-client:1.0.0")

    // Compose-BOM des Projekts fuer die plattformneutralen
    // `androidx.compose.*`-Artefakte (runtime, ui, foundation). Die
    // `androidx.wear.compose.*`-Artefakte stehen NICHT in dieser BOM und
    // tragen ihre eigene Versionsreihe (unten, 1.6.2).
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.runtime:runtime")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")

    // Compose fuer Wear OS. Diese Bibliotheken ersetzen `material3` der
    // Telefon-Seite vollstaendig — Mischen von `androidx.wear.compose.material3`
    // und `androidx.compose.material3` fuehrt laut Google zu unvorhersehbarem
    // Verhalten (doppelte Themes, falsch dimensionierte Komponenten). Im
    // gesamten `:wear`-Quelltext gibt es deshalb keinen einzigen Import aus
    // `androidx.compose.material3`.
    implementation("androidx.wear.compose:compose-material3:1.6.2")
    implementation("androidx.wear.compose:compose-foundation:1.6.2")

    // Ongoing Activity: der Wear-Aufsatz auf die Vordergrund-Notification, der
    // die laufende Aufzeichnung auf dem Zifferblatt und im App-Starter sichtbar
    // haelt. Reine Jetpack-API — es gibt KEINE Berechtigung "ONGOING_ACTIVITY";
    // was es braucht, haengt an der Notification (POST_NOTIFICATIONS).
    implementation("androidx.wear:wear-ongoing:1.1.0")
    implementation("androidx.wear:wear:1.4.0")

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // `:core` deklariert kotlinx-serialization-json nur als `implementation`,
    // es ist hier also nicht transitiv sichtbar. Das Spike-Journal baut seine
    // Zeilen mit `buildJsonObject` — gleiche Version wie in `:core` und `:app`,
    // damit kein zweiter Serialisierungs-Stack entsteht.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
