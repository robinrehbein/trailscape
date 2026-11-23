package de.trailscape.core.indoor.world

import java.io.File

/**
 * # Einstiegspunkte des Slices indoor-world
 *
 * EINSTIEGSDATEI (docs/indoor.md, Abschnitt 3). Besitzer: Slice indoor-world.
 *
 * Die **Signaturen** hier sind Vertrag; der Architekt hat nur Platzhalter
 * hinterlegt. indoor-world ersetzt die Ruempfe (Umsetzung in eigenen Dateien
 * dieses Pakets) und pflegt das Register [aerialSources].
 */

private fun offen(was: String): Nothing =
    throw NotImplementedError("Indoor: $was ist noch nicht umgesetzt (Slice indoor-world)")

/**
 * Das Register der Luftbild-Dienste. Jeder Eintrag ist mit einer echten
 * `GetCapabilities`- und `GetMap`-Anfrage geprueft (Datum im Kommentar am
 * Eintrag); ein Land ohne Eintrag bekommt den erzeugten Boden.
 *
 * Die Hosts dieser Liste sind Teil der Positivliste ([isAllowedGeoUrl]) und
 * stehen in PRIVACY.md — wer hier etwas ergaenzt, nennt den Host in seinem
 * Abschlussbericht, damit der Slice indoor-app ihn dort nachtraegt.
 */
val aerialSources: List<AerialSource> = listOf(
    // Geprueft am 23.11.2026: GetCapabilities + GetMap 1.3.0, CRS=EPSG:4326 mit
    // BBOX=minLat,minLon,maxLat,maxLon (Altenberger Dom mittig), JPEG Baseline, bis 5000 px,
    // ausserhalb weisses Leerbild, Fehler als ServiceExceptionReport mit Status 200.
    AerialSource(
        id = "nw",
        label = "Nordrhein-Westfalen",
        wmsUrl = "https://www.wms.nrw.de/geobasis/wms_nw_dop",
        layer = "nw_dop_rgb",
        minLat = 50.30,
        minLon = 5.85,
        maxLat = 52.55,
        maxLon = 9.50,
        attribution = "Luftbild: © Geobasis NRW",
        license = "dl-de/zero-2-0",
        licenseUrl = "https://www.govdata.de/dl-de/zero-2-0",
    ),
    // Geprueft am 23.11.2026: GetCapabilities + GetMap 1.3.0, EPSG:4326 lat/lon (Frauenkirche
    // Dresden mittig), Nachbarbilder nahtlos, JPEG Baseline, bis 4096 px, ausserhalb weiss.
    // Nutzungsbedingungen: https://www.landesvermessung.sachsen.de/allgemeine-nutzungsbedingungen-8954.html
    AerialSource(
        id = "sn",
        label = "Sachsen",
        wmsUrl = "https://geodienste.sachsen.de/wms_geosn_dop-rgb/guest",
        layer = "sn_dop_020",
        minLat = 50.15,
        minLon = 11.78,
        maxLat = 51.73,
        maxLon = 15.09,
        attribution = "Luftbild: Quelle: GeoSN, dl-de/by-2-0",
        license = "dl-de/by-2-0",
        licenseUrl = "https://www.govdata.de/dl-de/by-2-0",
    ),
    // Geprueft am 23.11.2026: GetMap 1.3.0, EPSG:4326 lat/lon (Erfurter Altstadt), nahtlos,
    // Baseline, bis 5000 px. Lizenz laut GetCapabilities (Fees): CC BY 4.0, Namensnennung „© GDI-Th".
    AerialSource(
        id = "th",
        label = "Thüringen",
        wmsUrl = "https://www.geoproxy.geoportal-th.de/geoproxy/services/DOP",
        layer = "th_dop",
        minLat = 50.14,
        minLon = 9.79,
        maxLat = 51.70,
        maxLon = 12.89,
        attribution = "Luftbild: © GDI-Th",
        license = "CC BY 4.0",
        licenseUrl = "https://creativecommons.org/licenses/by/4.0/deed.de",
    ),
    // Geprueft am 23.11.2026: GetMap 1.3.0, EPSG:4326 lat/lon (Schloss Sanssouci mittig), nahtlos,
    // Baseline, bis 8000 px; die Ebene deckt Brandenburg und Berlin. Lizenz laut GetCapabilities
    // (AccessConstraints): dl-de/by-2-0.
    AerialSource(
        id = "bb",
        label = "Brandenburg und Berlin",
        wmsUrl = "https://isk.geobasis-bb.de/mapproxy/dop20c/service/wms",
        layer = "bebb_dop20c",
        minLat = 51.26,
        minLon = 11.15,
        maxLat = 53.62,
        maxLon = 15.01,
        attribution = "Luftbild: © GeoBasis-DE/LGB, dl-de/by-2-0; © Geoportal Berlin, dl-de/by-2-0",
        license = "dl-de/by-2-0",
        licenseUrl = "https://www.govdata.de/dl-de/by-2-0",
    ),
)

/**
 * Die Huelle um den rohen Netzabruf [network]: prueft jede URL gegen die
 * Positivliste ([isAllowedGeoUrl] mit [aerial]; Verstoss = Ausnahme, kein
 * Abruf), bedient aus dem Plattencache unter [cacheDir] und drosselt die
 * Anfragen je Host (Regeln in docs/indoor.md, Abschnitt 8).
 */
fun createGeoPipeline(
    network: GeoFetcher,
    cacheDir: File,
    aerial: List<AerialSource> = aerialSources,
): GeoFetcher = geoPipeline(network, cacheDir, aerial)

/** Der Weltbau. */
fun createWorldBuilder(): WorldBuilder = offen("createWorldBuilder")
