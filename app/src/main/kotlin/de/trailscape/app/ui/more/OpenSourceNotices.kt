package de.trailscape.app.ui.more

import de.trailscape.app.R
import de.trailscape.app.i18n.UiText

/**
 * Handgepflegte Liste der Lizenzen und Datenquellen, die auf der Seite „Über Trailscape"
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
 * August 2026, Kartenquellen September 2026). Die Kachel-Attributionen stammen aus dem Stil-Katalog
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

/**
 * Ein Eintrag der Lizenzliste: Komponente, Lizenz, Herkunft. Name und Lizenz
 * sind [UiText]: Produktnamen und Lizenzkuerzel stehen als [UiText.Plain] in
 * jeder Sprache gleich da, Beschreibendes („Kartendaten", „proprietär")
 * kommt aus `strings_more.xml`.
 */
data class LicenseNotice(
    val name: UiText,
    val license: UiText,
    val url: String,
)

/**
 * Die Bibliotheken, die im APK landen — nach Gewicht sortiert, nicht
 * alphabetisch: Wer sich fuer Lizenzen interessiert, sucht zuerst die grossen
 * Bausteine.
 */
val libraryNotices: List<LicenseNotice> = listOf(
    LicenseNotice(
        name = UiText.Plain("MapLibre Native (Android SDK)"),
        license = UiText.Plain("BSD 2-Clause"),
        url = "https://github.com/maplibre/maplibre-native",
    ),
    LicenseNotice(
        name = UiText.Plain("Jetpack Compose, AndroidX, Material 3, Health Connect Client"),
        license = UiText.Plain("Apache 2.0"),
        url = "https://developer.android.com/jetpack/androidx",
    ),
    LicenseNotice(
        name = UiText.Plain("Kotlin, kotlinx.coroutines, kotlinx.serialization"),
        license = UiText.Plain("Apache 2.0"),
        url = "https://github.com/JetBrains/kotlin",
    ),
    LicenseNotice(
        name = UiText.Plain("OkHttp (Square)"),
        license = UiText.Plain("Apache 2.0"),
        url = "https://github.com/square/okhttp",
    ),
    // Fuer die Uhr-Anbindung (Wear-OS-Datenschicht) gibt es keinen freien
    // Ersatz — MessageClient/CapabilityClient stecken ausschliesslich in
    // Play Services. Bewusste Entscheidung des Maintainers (PR #30): Die
    // Live-Sensorik der Uhr wiegt schwerer als die fruehere Zusicherung,
    // ohne proprietaere Abhaengigkeiten auszukommen.
    LicenseNotice(
        name = UiText.Plain("Google Play Services (Wearable Data Layer)"),
        license = UiText.Res(R.string.more_notice_play_services_license),
        url = "https://developer.android.com/distribute/play-services",
    ),
    // Die Routing-Engine steckt seit dem Offline-Routing im APK selbst (Modul
    // `:brouter`, gebaut aus dem Submodul `third_party/brouter` auf Tag
    // v1.7.10). MIT verlangt Lizenztext UND Copyright-Vermerk in jeder Kopie
    // — beides ist hier: der Vermerk im angezeigten Text, der volle
    // Lizenztext als `assets/licenses/brouter-MIT.txt`.
    LicenseNotice(
        name = UiText.Res(R.string.more_notice_brouter_engine_name),
        license = UiText.Plain("MIT — Copyright (c) 2019 BRouter contributors"),
        url = "https://github.com/abrensch/brouter",
    ),
    // Indoor: 3D-Darstellung der Welt (Slice indoor-render), je ABI im APK.
    LicenseNotice(
        name = UiText.Plain("Filament (Google)"),
        license = UiText.Plain("Apache 2.0"),
        url = "https://github.com/google/filament",
    ),
)

/**
 * Dienste und Daten, die die App zur Laufzeit benutzt — sie stecken nicht im
 * APK, ihre Lizenzen verlangen aber genauso eine Nennung. Welche
 * Kachelquellen hier stehen, ergibt sich aus `ui/MapStyles.kt`.
 */
val dataNotices: List<LicenseNotice> = listOf(
    LicenseNotice(
        name = UiText.Res(R.string.more_notice_osm_name),
        license = UiText.Plain("ODbL 1.0"),
        url = "https://www.openstreetmap.org/copyright",
    ),
    // CARTO ist seit dem Wechsel der Straßenkarte auf FOSSGIS (siehe
    // `mapStyles`) nicht mehr im Spiel und steht deshalb hier nicht mehr.
    LicenseNotice(
        name = UiText.Res(R.string.more_notice_fossgis_name),
        // Stil: openstreetmap-carto-de, wie sein Vorbild openstreetmap-carto
        // unter CC0 (LICENSE.txt in github.com/giggls/openstreetmap-carto-de) —
        // nicht CC BY-SA; das gilt nur fuer die Texte der FOSSGIS-Webseite.
        license = UiText.Res(R.string.more_notice_fossgis_license),
        url = "https://www.openstreetmap.de/germanstyle/",
    ),
    // Der offline speicherbare Vektor-Stil. Die Pflicht-Attribution
    // („OpenFreeMap © OpenMapTiles Data from OpenStreetMap") blendet MapLibre
    // aus der Quelle selbst ein (TileJSON bzw. festgeschriebene Stil-Kopie);
    // hier stehen die Lizenzen dahinter.
    LicenseNotice(
        name = UiText.Res(R.string.more_notice_openfreemap_name),
        license = UiText.Res(R.string.more_notice_openfreemap_license),
        url = "https://openfreemap.org/",
    ),
    LicenseNotice(
        name = UiText.Res(R.string.more_notice_openmaptiles_name),
        license = UiText.Res(R.string.more_notice_openmaptiles_license),
        url = "https://www.openmaptiles.org/",
    ),
    LicenseNotice(
        name = UiText.Res(R.string.more_notice_cyclosm_name),
        license = UiText.Res(R.string.more_notice_cyclosm_license),
        url = "https://www.cyclosm.org/",
    ),
    LicenseNotice(
        name = UiText.Res(R.string.more_notice_osm_tiles_name),
        license = UiText.Res(R.string.more_notice_osm_tiles_license),
        url = "https://operations.osmfoundation.org/policies/tiles/",
    ),
    LicenseNotice(
        name = UiText.Res(R.string.more_notice_opentopomap_name),
        license = UiText.Res(R.string.more_notice_opentopomap_license),
        url = "https://opentopomap.org/about",
    ),
    LicenseNotice(
        name = UiText.Res(R.string.more_notice_satellite_name),
        license = UiText.Res(R.string.more_notice_satellite_license),
        url = "https://www.esri.com/en-us/legal/terms/full-master-agreement",
    ),
    LicenseNotice(
        name = UiText.Res(R.string.more_notice_brouter_service_name),
        license = UiText.Plain("MIT"),
        url = "https://github.com/abrensch/brouter",
    ),
    // Die Routing-Kacheln (*.rd5), die die Engine auf dem Gerät liest, sind
    // aus OpenStreetMap abgeleitete Daten und stehen damit unter derselben
    // ODbL wie die Kartenkacheln — eigener Eintrag, weil sie aus einer
    // anderen Quelle kommen als die Darstellungskacheln darüber.
    LicenseNotice(
        name = UiText.Res(R.string.more_notice_segments_name),
        license = UiText.Plain("ODbL 1.0"),
        url = "https://www.openstreetmap.org/copyright",
    ),
    LicenseNotice(
        name = UiText.Res(R.string.more_notice_nominatim_name),
        license = UiText.Res(R.string.more_notice_nominatim_license),
        url = "https://operations.osmfoundation.org/policies/nominatim/",
    ),
    // Indoor-Welten (`docs/indoor.md` 8.1): Hoehen, Karteninhalt und Luftbild.
    // Die Texte stehen fest im Code wie die Produkt- und Lizenznamen oben; die
    // Quellenangaben je Welt kommen aus dem Weltmanifest.
    LicenseNotice(
        name = UiText.Plain("Terrarium-Höhenkacheln (Tilezen, AWS Open Data)"),
        license = UiText.Plain("Quellenmix u. a. SRTM, EU-DEM; Angabe laut Tilezen"),
        url = "https://github.com/tilezen/joerd/blob/master/docs/attribution.md",
    ),
    LicenseNotice(
        name = UiText.Plain("OpenFreeMap, OpenMapTiles, OpenStreetMap (Indoor-Welten)"),
        license = UiText.Plain("ODbL 1.0 — © OpenMapTiles, Daten von OpenStreetMap"),
        url = "https://openfreemap.org/",
    ),
    LicenseNotice(
        name = UiText.Plain("Geobasis NRW (Digitale Orthophotos)"),
        license = UiText.Plain("Datenlizenz Deutschland Zero 2.0 (dl-de/zero-2-0)"),
        url = "https://www.govdata.de/dl-de/zero-2-0",
    ),
    LicenseNotice(
        name = UiText.Plain("GeoSN Sachsen (Digitale Orthophotos)"),
        license = UiText.Plain("Datenlizenz Deutschland Namensnennung 2.0 (dl-de/by-2-0) — Quelle: GeoSN"),
        url = "https://www.govdata.de/dl-de/by-2-0",
    ),
    LicenseNotice(
        name = UiText.Plain("GDI-Th Thüringen (Digitale Orthophotos)"),
        license = UiText.Plain("CC BY 4.0 — © GDI-Th"),
        url = "https://creativecommons.org/licenses/by/4.0/deed.de",
    ),
    LicenseNotice(
        name = UiText.Plain("GeoBasis-DE/LGB und Geoportal Berlin (Digitale Orthophotos)"),
        license = UiText.Plain("Datenlizenz Deutschland Namensnennung 2.0 (dl-de/by-2-0) — © GeoBasis-DE/LGB; © Geoportal Berlin"),
        url = "https://www.govdata.de/dl-de/by-2-0",
    ),
    LicenseNotice(
        name = UiText.Plain("LGL Baden-Württemberg (Digitale Orthophotos)"),
        license = UiText.Plain("Datenlizenz Deutschland Namensnennung 2.0 (dl-de/by-2-0) — Datenquelle: LGL, www.lgl-bw.de, dl-de/by-2-0"),
        url = "https://www.govdata.de/dl-de/by-2-0",
    ),
    // Bekannte Strecken (docs/indoor/catalog.md): Streckenverlauf aus OSM,
    // Luftbilder der vorgebauten Welten (nur beim Vorbauen abgerufen; die
    // genaue Angabe mit Jahr steht im Manifest jeder Welt).
    LicenseNotice(
        name = UiText.Plain("Bekannte Strecken: Streckenverlauf (OpenStreetMap, BRouter)"),
        license = UiText.Plain("ODbL 1.0 — © OpenStreetMap-Mitwirkende"),
        url = "https://www.openstreetmap.org/copyright",
    ),
    LicenseNotice(
        name = UiText.Plain("IGN France, BD ORTHO® (bekannte Strecken in Frankreich)"),
        license = UiText.Plain("Licence Ouverte / Etalab 2.0 — © IGN"),
        url = "https://www.etalab.gouv.fr/licence-ouverte-open-licence/",
    ),
    LicenseNotice(
        name = UiText.Plain("Digitaal Vlaanderen, Luchtopnamen (bekannte Strecken in Flandern)"),
        license = UiText.Plain("Modellicentie gratis hergebruik v1.0 — Bron: Digitaal Vlaanderen"),
        url = "https://www.vlaanderen.be/digitaal-vlaanderen/onze-diensten-en-platformen/open-data/voorwaarden-voor-het-hergebruik-van-overheidsinformatie/modellicentie-gratis-hergebruik",
    ),
    LicenseNotice(
        name = UiText.Plain("PNOA, Instituto Geográfico Nacional (bekannte Strecken in Spanien)"),
        license = UiText.Plain("CC BY 4.0 — PNOA, scne.es"),
        url = "https://creativecommons.org/licenses/by/4.0/",
    ),
    LicenseNotice(
        name = UiText.Plain("USGS/USDA NAIP, The National Map (bekannte Strecken in den USA)"),
        license = UiText.Plain("Public Domain"),
        url = "https://www.usgs.gov/information-policies-and-instructions/copyrights-and-credits",
    ),
    LicenseNotice(
        name = UiText.Plain("HVBG Hessen (Digitale Orthophotos, bekannte Strecken)"),
        license = UiText.Plain("Datenlizenz Deutschland Zero 2.0 (dl-de/zero-2-0)"),
        url = "https://www.govdata.de/dl-de/zero-2-0",
    ),
)
