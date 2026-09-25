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
 * Zwei Bauarten stehen nebeneinander:
 *  * **Raster**-Kachelquellen (`{z}/{x}/{y}`), genau wie in der
 *    Flutter-App. MapLibre zeigt sie ueber eine zur Laufzeit gebaute
 *    Style-JSON mit einer `raster`-Source an ([toRasterStyleJson]).
 *  * Ein **Vektor**-Stil, den MapLibre fertig von einer Style-URL laedt
 *    ([vectorStyleUrl]). Den gibt es nur, weil er sich — anders als alle
 *    Rasterquellen — offline speichern laesst (siehe [offlineAllowed] und
 *    den KDoc an [mapStyles]). Sobald eine Region gespeichert ist, zeichnet
 *    die Karte ihn aus einer festgeschriebenen Kopie (siehe
 *    `OfflineRegions.kt`).
 *
 * Keine der Quellen braucht einen API-Schluessel.
 */
data class MapStyle(
    /** Stabiler Schluessel fuer Cache-Verzeichnis und Persistenz. */
    val id: String,
    /** Anzeigename in der Stil-Auswahl. */
    val label: String,
    /**
     * Kachel-URL mit den Platzhaltern `{z}`, `{x}` und `{y}` in beliebiger
     * Reihenfolge (Esri nutzt etwa `{z}/{y}/{x}`). Leer bei einem
     * Vektor-Stil — der bringt seine Quellen in der Style-JSON selbst mit.
     */
    val urlTemplate: String,
    /**
     * Hoechste vom Anbieter gelieferte Kachelstufe. Bei einem Vektor-Stil
     * die `maxzoom` seiner Vektorquelle — darueber vergroessert MapLibre die
     * letzte Stufe verlustfrei (siehe [maxCameraZoom]).
     */
    val maxZoom: Int,
    /** Attributionstext, der auf der Karte eingeblendet wird. */
    val attribution: String,
    /**
     * Adresse einer fertigen MapLibre-Style-JSON. Gesetzt heisst: Vektor-Stil,
     * [urlTemplate] und [toRasterStyleJson] spielen keine Rolle.
     */
    val vectorStyleUrl: String? = null,
    /**
     * Ob der Anbieter das Vorab-Herunterladen ganzer Ausschnitte erlaubt.
     *
     * Standard `false`, und zwar mit Absicht: Ein neuer Stil soll erst dann
     * offline speicherbar werden, wenn jemand die Nutzungsbedingungen
     * gelesen hat. Die Sperre greift in [de.trailscape.app.ui.map.planOfflineDownload]
     * (also auch dann, wenn die Oberflaeche sie einmal vergaesse).
     */
    val offlineAllowed: Boolean = false,
) {
    /** Ob MapLibre diesen Stil von [vectorStyleUrl] laedt statt aus [toRasterStyleJson]. */
    val isVector: Boolean get() = vectorStyleUrl != null

    /**
     * Um so viele Stufen liegt das Kachelraster ueber der Kamerazoomstufe:
     * MapLibre rechnet intern mit 512-Punkt-Kacheln. Unsere Rasterquellen sind
     * 256 Punkt gross (`log2(512 / 256) = 1`), Vektorkacheln haben die vollen
     * 512 (Versatz 0). Siehe `OfflineTileMath.kt`.
     */
    val tileZoomOffset: Int get() = if (isVector) 0 else 1

    /**
     * Wie weit die Kamera hineinzoomen darf. Raster werden ueber ihre letzte
     * Stufe hinaus schnell matschig (zwei Stufen Reserve); Vektorkacheln
     * bleiben beim Vergroessern scharf, dort begrenzt nur die Karte selbst.
     */
    val maxCameraZoom: Double get() = if (isVector) 20.0 else maxZoom + 2.0

    /**
     * Minimale MapLibre-Style-JSON, die genau diese Rasterquelle bildschirm-
     * fuellend darstellt. Bequemlichkeit fuer den Karten-Screen:
     * `MapLibreMap.setStyle(Style.Builder().fromJson(style.toRasterStyleJson()))`.
     * Nur fuer Rasterstile sinnvoll (siehe [isVector]).
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
 *
 * ## Warum nur ein Stil offline speicherbar ist
 * Die Rasterserver sind Gemeinschafts- oder Firmendienste fuers **Anzeigen**,
 * nicht fuers Vorab-Laden ganzer Gegenden:
 *  * `tile.openstreetmap.org`: Die OSMF Tile Usage Policy verbietet
 *    Bulk-Download und nennt „Download for offline"-Knoepfe ausdruecklich als
 *    unzulaessig (https://operations.osmfoundation.org/policies/tiles/,
 *    Abschnitt 4, abgerufen 25.09.2026).
 *  * FOSSGIS (`tile.openstreetmap.de`): „Das Massen-Herunterladen von
 *    Kacheln fuer andere Zwecke als die Web-Darstellung (insbesondere durch
 *    irgendwelche Apps fuer die Offline-Nutzung) ist nicht erwuenscht"
 *    (https://www.openstreetmap.de/germanstyle/, abgerufen 25.09.2026).
 *  * CyclOSM und OpenTopoMap laufen ebenfalls auf Spendenservern ohne
 *    Freigabe fuer Vorab-Downloads; Esri World Imagery ist proprietaer und
 *    erlaubt das Zwischenspeichern nur im Rahmen eigener Lizenzvertraege.
 *
 * Deshalb tragen sie `offlineAllowed = false`. Bereits frueher geladene
 * Regionen dieser Stile bleiben liegen (die Daten sind auf dem Geraet, ein
 * Loeschen braechte dem Server nichts) — neue gibt es nicht mehr.
 *
 * Offline speicherbar ist nur **OpenFreeMap** (`tiles.openfreemap.org`,
 * Vektorkacheln im OpenMapTiles-Schema aus OSM-Daten). Stand der Recherche
 * (25.09.2026):
 *  * https://openfreemap.org/ — „lets you display custom maps on your website
 *    and apps for free"; die oeffentliche Instanz hat „no limits on the
 *    number of map views or requests", keine Registrierung, keine
 *    API-Schluessel, keine Cookies. Kommerzielle Nutzung: „Yes."
 *  * https://openfreemap.org/quick_start/ — Style-URL
 *    `https://tiles.openfreemap.org/styles/liberty`, fuer Apps ausdruecklich
 *    „with MapLibre Native".
 *  * **Verbindlich sind die Nutzungsbedingungen**
 *    (https://openfreemap.org/tos/, „Last Updated: September 9, 2026",
 *    abgerufen 25.09.2026). Unter „User Conduct" steht: „You will not: …
 *    Attempt to collect data from the service in automated ways without
 *    permission". Ein Offline-Speicher-Knopf erlaubt oder verbietet die
 *    Seite nirgends ausdruecklich; an dieser Klausel muss er sich messen.
 *
 * Warum der Download hier trotzdem angeboten wird — und wo die Begruendung
 * endet: Gemeint ist mit der Klausel erkennbar das Abgrasen des Dienstes
 * (Scraper, Massen-Downloads; fuer ganze Gebiete verweist das Projekt selbst
 * auf seine woechentlichen Planet-Dateien als MBTiles). Der Download dieser
 * App ist das Gegenteil davon: Er startet nur auf ausdruecklichen Knopfdruck,
 * nimmt genau den Ausschnitt, den die Nutzerin gerade ansieht, und ist auf
 * [de.trailscape.app.ui.map.MAX_TILES_PER_DOWNLOAD] Kacheln und hoechstens
 * [de.trailscape.app.ui.map.MAX_OFFLINE_EDGE_KM] Kantenlaenge begrenzt
 * (siehe `OfflineTileMath.kt`) — das sind die Kacheln, die sie beim Hin- und
 * Herschieben ueber dieselbe Stelle ohnehin laden wuerde, nur vorab. Das ist
 * eine Auslegung, keine Erlaubnis. **Offen vor dem Release:** eine kurze
 * schriftliche Bestaetigung von info@openfreemap.org einholen und hier mit
 * Datum und Wortlaut vermerken; faellt sie negativ aus, bekommt dieser Stil
 * `offlineAllowed = false`, und die Oberflaeche bietet den Download dann bei
 * keinem Stil mehr an.
 *
 * Weitere Punkte der Bedingungen:
 *  * „You must be at least 18 years old … to integrate our public service
 *    into a website or application" — das betrifft die Person, die die App
 *    veroeffentlicht, nicht die Nutzerinnen („This age requirement does not
 *    apply to end users who merely view or interact with embedded maps").
 *  * „We may use Cloudflare as a CDN … By using the Site, you consent to
 *    Cloudflare's processing of your requests." Die Kacheln kommen live
 *    ueber Cloudflare (Antwort-Header `server: cloudflare`, `cf-ray`); das
 *    steht deshalb in `PRIVACY.md` bei den Empfaengern.
 *  * Attribution ist Pflicht: „OpenFreeMap © OpenMapTiles Data from
 *    OpenStreetMap". MapLibre blendet sie hinter dem Info-Knopf der Karte
 *    ein — aus der TileJSON bzw. aus der festgeschriebenen Kopie des Stils,
 *    die sie mit uebernimmt (`pinStyleSources` in `OfflineTileMath.kt`).
 *    Der Info-Knopf sitzt oben links, wo ihn kein Blatt verdeckt (siehe
 *    `MapViewHost.kt`). Der Text unten ist fuer die Lizenzseite.
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
    MapStyle(
        id = "openfreemap",
        // Nicht „Offline-Karte": Der Stil wird live geladen, solange nichts
        // gespeichert ist — offline ist er erst nach dem Speichern.
        label = "Vektorkarte (OpenFreeMap)",
        urlTemplate = "",
        // `maxzoom` der Vektorquelle `https://tiles.openfreemap.org/planet`.
        maxZoom = 14,
        attribution = "OpenFreeMap · © OpenMapTiles · Daten © OpenStreetMap-Mitwirkende",
        vectorStyleUrl = "https://tiles.openfreemap.org/styles/liberty",
        offlineAllowed = true,
    ),
)

/**
 * Erklaerender Halbsatz zu den Stilen, bei denen der Name allein nicht
 * reicht. Lag vorher als private Funktion im Karten-Screen und war deshalb nur
 * im Bottom-Sheet dort zu sehen — die Auswahl im Mehr-Tab zeigte dieselbe
 * Liste ohne jede Erlaeuterung.
 */
fun mapStyleSubtitle(id: String): String? = when (id) {
    "osmde" -> "Klar und aufgeräumt (Standard)"
    "cyclosm" -> "Radwege & Wegbeläge hervorgehoben"
    "openfreemap" -> "Lässt sich offline speichern"
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
