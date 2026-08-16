package de.trailscape.app.ui.more

/**
 * Handgepflegte Liste der Lizenzen und Datenquellen, die in der „Über"-Karte
 * aufklappbar ist.
 *
 * **Bewusst ohne Lizenz-Plugin** (`com.mikepenz.aboutlibraries` o. ae.): Ein
 * solches Plugin haengt sich in den Build, erzeugt zur Bauzeit eine
 * JSON-Datei aus dem Abhaengigkeitsbaum und zieht seine eigene UI-Bibliothek
 * herein — fuer eine App mit einer Handvoll direkter Abhaengigkeiten ist das
 * mehr Maschinerie als Nutzen. Diese Liste ist stattdessen Teil des
 * Quellcodes und wird gepflegt, wenn sich `app/build.gradle.kts` aendert.
 *
 * Die Angaben sind gegen die jeweiligen Projekt-Repositories geprueft (Stand:
 * August 2026). Die Kachel-Attributionen stammen aus dem Stil-Katalog
 * (`ui/MapStyles.kt`) und muessen zu dem passen, was die Karte einblendet.
 *
 * ## Copyright-Vermerke
 *
 * Bei Lizenzen, die den Copyright-Vermerk ausdruecklich verlangen (MIT, BSD),
 * steht er hier direkt im [LicenseNotice.license]-Text — der wird angezeigt,
 * ein zusaetzliches Feld waere es nicht. Der vollstaendige Lizenztext von
 * BRouter liegt ausserdem unveraendert als Datei im APK
 * (`assets/licenses/brouter-MIT.txt`), womit die MIT-Auflage erfuellt ist,
 * ihn „in allen Kopien der Software" mitzuliefern.
 */

/** Ein Eintrag der Lizenzliste: Komponente, Lizenz, Herkunft. */
data class LicenseNotice(
    val name: String,
    val license: String,
    val url: String,
)

/**
 * Die Bibliotheken, die im APK landen — nach Gewicht sortiert, nicht
 * alphabetisch: Wer sich fuer Lizenzen interessiert, sucht zuerst die grossen
 * Bausteine.
 */
val libraryNotices: List<LicenseNotice> = listOf(
    LicenseNotice(
        name = "MapLibre Native (Android SDK)",
        license = "BSD 2-Clause",
        url = "https://github.com/maplibre/maplibre-native",
    ),
    LicenseNotice(
        name = "Jetpack Compose, AndroidX, Material 3, Health Connect Client",
        license = "Apache 2.0",
        url = "https://developer.android.com/jetpack/androidx",
    ),
    LicenseNotice(
        name = "Kotlin, kotlinx.coroutines, kotlinx.serialization",
        license = "Apache 2.0",
        url = "https://github.com/JetBrains/kotlin",
    ),
    LicenseNotice(
        name = "OkHttp (Square)",
        license = "Apache 2.0",
        url = "https://github.com/square/okhttp",
    ),
    // Fuer die Uhr-Anbindung (Wear-OS-Datenschicht) gibt es keinen freien
    // Ersatz — MessageClient/CapabilityClient stecken ausschliesslich in
    // Play Services. Bewusste Entscheidung des Maintainers (PR #30): Die
    // Live-Sensorik der Uhr wiegt schwerer als die fruehere Zusicherung,
    // ohne proprietaere Abhaengigkeiten auszukommen.
    LicenseNotice(
        name = "Google Play Services (Wearable Data Layer)",
        license = "proprietär — Android SDK Terms",
        url = "https://developer.android.com/distribute/play-services",
    ),
    // Die Routing-Engine steckt seit dem Offline-Routing im APK selbst (Modul
    // `:brouter`, gebaut aus dem Submodul `third_party/brouter` auf Tag
    // v1.7.10). MIT verlangt Lizenztext UND Copyright-Vermerk in jeder Kopie
    // — beides ist hier: der Vermerk im angezeigten Text, der volle
    // Lizenztext als `assets/licenses/brouter-MIT.txt`.
    LicenseNotice(
        name = "BRouter-Routing-Engine (im Gerät rechnend)",
        license = "MIT — Copyright (c) 2019 BRouter contributors",
        url = "https://github.com/abrensch/brouter",
    ),
)

/**
 * Dienste und Daten, die die App zur Laufzeit benutzt — sie stecken nicht im
 * APK, ihre Lizenzen verlangen aber genauso eine Nennung. Welche
 * Kachelquellen hier stehen, ergibt sich aus `ui/MapStyles.kt`.
 */
val dataNotices: List<LicenseNotice> = listOf(
    LicenseNotice(
        name = "Kartendaten: © OpenStreetMap-Mitwirkende",
        license = "ODbL 1.0",
        url = "https://www.openstreetmap.org/copyright",
    ),
    LicenseNotice(
        name = "Kacheln „Straßenkarte“: CARTO Voyager",
        license = "CC BY 3.0 (Stil), Daten ODbL",
        url = "https://carto.com/attributions",
    ),
    LicenseNotice(
        name = "Kacheln „CyclOSM“",
        license = "Kacheln CC BY-SA 2.0, Stil-Code BSD 3-Clause, Daten ODbL",
        url = "https://www.cyclosm.org/",
    ),
    LicenseNotice(
        name = "Kacheln „OpenStreetMap“",
        license = "Daten ODbL, Nutzung nach OSMF Tile Usage Policy",
        url = "https://operations.osmfoundation.org/policies/tiles/",
    ),
    LicenseNotice(
        name = "Kacheln „OpenTopoMap“ (inkl. SRTM-Höhendaten)",
        license = "CC BY-SA 3.0 (Stil), Daten ODbL",
        url = "https://opentopomap.org/about",
    ),
    LicenseNotice(
        name = "Kacheln „Satellit“: Esri World Imagery (Esri, Maxar, Earthstar Geographics)",
        license = "proprietär — Esri Terms of Use",
        url = "https://www.esri.com/en-us/legal/terms/full-master-agreement",
    ),
    LicenseNotice(
        name = "Routing: BRouter (Dienst brouter.de)",
        license = "MIT",
        url = "https://github.com/abrensch/brouter",
    ),
    // Die Routing-Kacheln (*.rd5), die die Engine auf dem Gerät liest, sind
    // aus OpenStreetMap abgeleitete Daten und stehen damit unter derselben
    // ODbL wie die Kartenkacheln — eigener Eintrag, weil sie aus einer
    // anderen Quelle kommen als die Darstellungskacheln darüber.
    LicenseNotice(
        name = "Offline-Routingdaten (BRouter-Segmente): © OpenStreetMap-Mitwirkende",
        license = "ODbL 1.0",
        url = "https://www.openstreetmap.org/copyright",
    ),
    LicenseNotice(
        name = "Ortssuche: Nominatim (OpenStreetMap Foundation)",
        license = "GPL 2.0 bzw. 3.0 (Software), Daten ODbL",
        url = "https://operations.osmfoundation.org/policies/nominatim/",
    ),
)
