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
    LicenseNotice(
        name = "Google Play services (Fused Location Provider)",
        license = "proprietär — Android Software Development Kit License Agreement (Google)",
        url = "https://developers.google.com/android/guides/opensource",
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
        name = "Kacheln „Straßenkarte\": CARTO Voyager",
        license = "CC BY 3.0 (Stil), Daten ODbL",
        url = "https://carto.com/attributions",
    ),
    LicenseNotice(
        name = "Kacheln „CyclOSM\"",
        license = "Kacheln CC BY-SA 2.0, Stil-Code BSD 3-Clause, Daten ODbL",
        url = "https://www.cyclosm.org/",
    ),
    LicenseNotice(
        name = "Kacheln „OpenStreetMap\"",
        license = "Daten ODbL, Nutzung nach OSMF Tile Usage Policy",
        url = "https://operations.osmfoundation.org/policies/tiles/",
    ),
    LicenseNotice(
        name = "Kacheln „OpenTopoMap\" (inkl. SRTM-Höhendaten)",
        license = "CC BY-SA 3.0 (Stil), Daten ODbL",
        url = "https://opentopomap.org/about",
    ),
    LicenseNotice(
        name = "Kacheln „Satellit\": Esri World Imagery (Esri, Maxar, Earthstar Geographics)",
        license = "proprietär — Esri Terms of Use",
        url = "https://www.esri.com/en-us/legal/terms/full-master-agreement",
    ),
    LicenseNotice(
        name = "Routing: BRouter (Dienst brouter.de)",
        license = "MIT",
        url = "https://github.com/abrensch/brouter",
    ),
    LicenseNotice(
        name = "Ortssuche: Nominatim (OpenStreetMap Foundation)",
        license = "GPL 2.0 bzw. 3.0 (Software), Daten ODbL",
        url = "https://operations.osmfoundation.org/policies/nominatim/",
    ),
)
