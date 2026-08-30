package de.trailscape.app.ui

/**
 * Katalog der auswaehlbaren Kartenstile — Port von `mapStyles` aus
 * `lib/tile_cache.dart`.
 *
 * Liegt bewusst NICHT in `ui/map/MapScreen.kt`: Die Auswahl gehoert dem
 * geteilten [AppViewModel] (sie wird auch im Mehr-Tab angeboten und
 * persistiert), waehrend `MapScreen.kt` vom Karten-Agenten komplett ersetzt
 * wird. Siehe Zustaendigkeits-KDoc in `TrailscapeApp.kt`.
 *
 * Alle Eintraege sind **Raster**-Kachelquellen (`{z}/{x}/{y}`), genau wie in
 * der Flutter-App. MapLibre kann sie ueber eine zur Laufzeit gebaute
 * Style-JSON mit einer `raster`-Source anzeigen ([toRasterStyleJson]) — es
 * braucht also keinen Vektor-Tile-Anbieter und keinen API-Schluessel.
 */
data class MapStyle(
    /** Stabiler Schluessel fuer Cache-Verzeichnis und Persistenz. */
    val id: String,
    /** Anzeigename in der Stil-Auswahl. */
    val label: String,
    /**
     * Kachel-URL mit den Platzhaltern `{z}`, `{x}` und `{y}` in beliebiger
     * Reihenfolge (Esri nutzt etwa `{z}/{y}/{x}`).
     */
    val urlTemplate: String,
    /** Hoechste vom Anbieter unterstuetzte Zoomstufe. */
    val maxZoom: Int,
    /** Attributionstext, der auf der Karte eingeblendet wird. */
    val attribution: String,
) {
    /**
     * Minimale MapLibre-Style-JSON, die genau diese Rasterquelle bildschirm-
     * fuellend darstellt. Bequemlichkeit fuer den Karten-Screen:
     * `MapLibreMap.setStyle(Style.Builder().fromJson(style.toRasterStyleJson()))`.
     */
    fun toRasterStyleJson(): String = """
        {
          "version": 8,
          "sources": {
            "$id": {
              "type": "raster",
              "tiles": ["$urlTemplate"],
              "tileSize": 256,
              "maxzoom": $maxZoom,
              "attribution": "$attribution"
            }
          },
          "layers": [
            { "id": "$id-layer", "type": "raster", "source": "$id" }
          ]
        }
    """.trimIndent()
}

/**
 * Alle auswaehlbaren Kartenstile. Der erste Eintrag ist der Standard.
 *
 * ## Warum die Strassenkarte nicht mehr von CARTO kommt
 * CARTO liefert seine freien `basemaps.cartocdn.com`-Kacheln seit
 * August 2026 nur noch mit API-Schluessel aus — anonyme Abrufe bekommen
 * Kacheln mit dem Wasserzeichen "API KEY REQUIRED" quer ueber der Karte.
 * Ein Schluessel widerspraeche dem Grundsatz "ohne API-Schluessel" dieser
 * Liste (und muesste in einer quelloffenen App ohnehin mitgeliefert
 * werden). Die Strassenkarte kommt deshalb vom FOSSGIS-Kachelserver
 * (`tile.openstreetmap.de`): weltweite Abdeckung, aufgeraeumter Stil,
 * schluessellos. Die neue ID (`osmde`) sorgt dafuer, dass alte
 * CARTO-Kachel-Caches nicht mit den neuen Kacheln vermischt werden;
 * eine gespeicherte `voyager`-Auswahl faellt ueber [mapStyleById] von
 * selbst auf diesen Standard zurueck.
 */
val mapStyles: List<MapStyle> = listOf(
    MapStyle(
        id = "osmde",
        label = "Straßenkarte",
        urlTemplate = "https://tile.openstreetmap.de/{z}/{x}/{y}.png",
        maxZoom = 19,
        attribution = "© OpenStreetMap-Mitwirkende",
    ),
    MapStyle(
        id = "cyclosm",
        label = "CyclOSM (Fahrrad)",
        urlTemplate = "https://a.tile-cyclosm.openstreetmap.fr/cyclosm/{z}/{x}/{y}.png",
        maxZoom = 19,
        attribution = "© OpenStreetMap-Mitwirkende · Stil: CyclOSM",
    ),
    MapStyle(
        id = "osm",
        label = "OpenStreetMap",
        urlTemplate = "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
        maxZoom = 19,
        attribution = "© OpenStreetMap-Mitwirkende",
    ),
    MapStyle(
        id = "opentopo",
        label = "OpenTopoMap (Gelände)",
        urlTemplate = "https://a.tile.opentopomap.org/{z}/{x}/{y}.png",
        maxZoom = 17,
        attribution = "© OpenStreetMap-Mitwirkende · SRTM · Stil: OpenTopoMap (CC-BY-SA)",
    ),
    MapStyle(
        id = "esri-sat",
        label = "Satellit (Esri)",
        urlTemplate = "https://server.arcgisonline.com/ArcGIS/rest/services/" +
            "World_Imagery/MapServer/tile/{z}/{y}/{x}",
        maxZoom = 19,
        attribution = "Esri, Maxar, Earthstar Geographics",
    ),
)

/**
 * Erklaerender Halbsatz zu den beiden Stilen, bei denen der Name allein nicht
 * reicht. Lag vorher als private Funktion im Karten-Screen und war deshalb nur
 * im Bottom-Sheet dort zu sehen — die Auswahl im Mehr-Tab zeigte dieselbe
 * Liste ohne jede Erlaeuterung.
 */
fun mapStyleSubtitle(id: String): String? = when (id) {
    "osmde" -> "Klar und aufgeräumt (Standard)"
    "cyclosm" -> "Radwege & Wegbeläge hervorgehoben"
    else -> null
}

/** Standard-Kartenstil, wenn nichts (Gueltiges) gespeichert ist. */
val defaultMapStyle: MapStyle get() = mapStyles.first()

/**
 * Schluessel der Kartenstil-Auswahl im [de.trailscape.core.KeyValueStore] —
 * derselbe Name wie in der Flutter-App (`lib/tile_cache.dart`) und im selben
 * `trailscape.*`-Namensraum wie die uebrigen Schluessel (siehe
 * `data/PrefsStores.kt`).
 */
const val MAP_STYLE_STORAGE_KEY: String = "trailscape.mapstyle"

/** Loest eine gespeicherte ID auf; unbekannte oder fehlende IDs → [defaultMapStyle]. */
fun mapStyleById(id: String?): MapStyle =
    mapStyles.firstOrNull { it.id == id } ?: defaultMapStyle
