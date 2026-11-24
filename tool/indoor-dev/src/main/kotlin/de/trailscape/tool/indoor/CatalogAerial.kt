package de.trailscape.tool.indoor

import de.trailscape.core.indoor.world.AerialSource
import java.time.Year

/** Cache der Geodaten des Werkzeugs (git-ignoriert). */
const val CACHE_DIR_TOOL = "tool/indoor-dev/cache"

/**
 * Jahr des Datenbezugs fuer Quellenangaben, die eines verlangen (Etalab,
 * PNOA). Das Werkzeug baut mit den Daten zum Bauzeitpunkt.
 */
private val BUILD_YEAR: Int = Year.now().value

/**
 * Luftbild-Dienste **nur fuer das Vorbauen** der Katalogwelten
 * (docs/indoor/catalog.md, Pruefung mit Belegen in `catalog/LUFTBILD.md`,
 * 23.11.2026). Die App kennt sie nicht: Ihr Register (`aerialSources` in
 * `:core`) und ihre Positivliste bleiben unveraendert; ob sie dort fuer eigene
 * Strecken freigeschaltet werden, entscheidet der Gruender. Die
 * Quellenangabe jedes genutzten Dienstes landet ueber das Manifest in der
 * Welt und damit in der App (HUD, Weltverwaltung).
 *
 * Baden-Wuerttemberg stand bis 24.11.2026 hier und ist jetzt im Register der
 * App (`aerialSources`); Katalogbau und App holen es von dort.
 *
 * Nicht aufgenommen: Italien/Toskana (keine sauber offene Quelle, siehe
 * LUFTBILD.md) — die toskanische Welt hat erzeugten Boden.
 */
val catalogAerialSources: List<AerialSource> = listOf(
    AerialSource(
        id = "fr",
        label = "Frankreich",
        wmsUrl = "https://data.geopf.fr/wms-r/wms",
        layer = "ORTHOIMAGERY.ORTHOPHOTOS",
        minLat = 41.30,
        minLon = -5.20,
        maxLat = 51.15,
        maxLon = 9.60,
        attribution = "Luftbild: © IGN – BD ORTHO®, Licence Ouverte Etalab ($BUILD_YEAR)",
        license = "Licence Ouverte 2.0",
        licenseUrl = "https://www.data.gouv.fr/pages/legal/licences/etalab-2.0",
        maxSizePx = 4096,
    ),
    // Geprueft am 23.11.2026: GetMap 1.3.0, EPSG:4326 lat/lon (Gent-Zentrum mittig), nahtlos, Baseline,
    // hoechstens 2048 px, ausserhalb weiss. Modellicentie gratis hergebruik v1.0, Bronvermelding laut Metadaten.
    AerialSource(
        id = "vl",
        label = "Flandern",
        wmsUrl = "https://geo.api.vlaanderen.be/OMWRGBMRVL/wms",
        layer = "Ortho",
        minLat = 50.68,
        minLon = 2.54,
        maxLat = 51.51,
        maxLon = 5.92,
        attribution = "Luftbild: Bron: Luchtopnamen Digitaal Vlaanderen",
        license = "Modellicentie gratis hergebruik v1.0",
        licenseUrl = "https://www.vlaanderen.be/digitaal-vlaanderen/onze-diensten-en-platformen/open-data/voorwaarden-voor-het-hergebruik-van-overheidsinformatie/modellicentie-gratis-hergebruik",
        maxSizePx = 2048,
    ),
    // Geprueft am 23.11.2026: GetMap 1.3.0, EPSG:4326 lat/lon (Sa Calobra, Teide mittig), nahtlos, Baseline,
    // bis 4096 px, ausserhalb weiss. CC BY 4.0; Kurzform nach IGN-Lizenz Nr. 3 („Cuarto“): „CC-BY 4.0 scne.es <fecha>“.
    // Zwei Rechtecke: Halbinsel + Balearen (ohne Ceuta/Melilla), Kanaren.
    AerialSource(
        id = "es",
        label = "Spanien",
        wmsUrl = "https://www.ign.es/wms-inspire/pnoa-ma",
        layer = "OI.OrthoimageCoverage",
        minLat = 35.95,
        minLon = -9.40,
        maxLat = 43.80,
        maxLon = 4.35,
        attribution = "Luftbild: PNOA CC-BY 4.0 scne.es $BUILD_YEAR",
        license = "CC BY 4.0",
        licenseUrl = "https://www.ign.es/resources/licencia/Condiciones_licenciaUso_IGN.pdf",
        maxSizePx = 4096,
    ),
    AerialSource(
        id = "es-cn",
        label = "Kanarische Inseln",
        wmsUrl = "https://www.ign.es/wms-inspire/pnoa-ma",
        layer = "OI.OrthoimageCoverage",
        minLat = 27.60,
        minLon = -18.20,
        maxLat = 29.45,
        maxLon = -13.35,
        attribution = "Luftbild: PNOA CC-BY 4.0 scne.es $BUILD_YEAR",
        license = "CC BY 4.0",
        licenseUrl = "https://www.ign.es/resources/licencia/Condiciones_licenciaUso_IGN.pdf",
        maxSizePx = 4096,
    ),
    // Geprueft am 23.11.2026: GetMap 1.3.0, EPSG:4326 lat/lon (Cottonwood Falls mittig), Baseline, bis 4000 px,
    // ausserhalb SCHWARZ (PNG transparent), 15–30 s je Bild. Public Domain (USGS/USDA NAIP). Nur CONUS
    // (Alaska-Bilder im Basisdienst sind SPOT mit Lizenzbeschraenkung).
    AerialSource(
        id = "us",
        label = "USA (CONUS)",
        wmsUrl = "https://imagery.nationalmap.gov/arcgis/services/USGSNAIPImagery/ImageServer/WMSServer",
        layer = "USGSNAIPImagery:NaturalColor",
        minLat = 24.40,
        minLon = -124.80,
        maxLat = 49.40,
        maxLon = -66.90,
        attribution = "Luftbild: USGS, USDA, The National Map: Orthoimagery",
        license = "Public Domain",
        licenseUrl = "https://www.usgs.gov/information-policies-and-instructions/copyrights-and-credits",
        maxSizePx = 2048,
    ),
    // Geprueft am 23.11.2026: GetMap 1.3.0, EPSG:4326 lat/lon (Grosser Feldberg mittig), nahtlos, Baseline,
    // bis 3000 px, ausserhalb weiss. Nutzung ohne Bedingung (HVGG § 18, dl-de/zero-2-0 laut opendata.hessen.de);
    // gibt man einen Quellenvermerk, ist auf Veraenderungen hinzuweisen („bearbeitet“).
    AerialSource(
        id = "he",
        label = "Hessen",
        wmsUrl = "https://www.gds-srv.hessen.de/cgi-bin/lika-services/ogc-free-images.ows",
        layer = "he_dop20_rgb",
        minLat = 49.36,
        minLon = 7.71,
        maxLat = 51.70,
        maxLon = 10.30,
        attribution = "Luftbild: Geobasisdaten © Hessische Verwaltung für Bodenmanagement und Geoinformation, bearbeitet",
        license = "dl-de/zero-2-0",
        licenseUrl = "https://www.govdata.de/dl-de/zero-2-0",
    ),
)
