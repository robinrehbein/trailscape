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
    // Geprueft am 23.11.2026 (Architekt): GetMap 1.3.0, EPSG:3857, image/jpeg → 200.
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
): GeoFetcher = offen("createGeoPipeline")

/** Der Weltbau. */
fun createWorldBuilder(): WorldBuilder = offen("createWorldBuilder")
