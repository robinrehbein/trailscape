package de.trailscape.app.ui.map

import de.trailscape.app.ui.MapStyle
import de.trailscape.app.ui.mapStyleById
import de.trailscape.app.ui.mapStyles
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Tests der reinen Rechnung hinter dem Offline-Download (`OfflineTileMath.kt`).
 *
 * Reiner JVM-Test: Die Datei kennt weder Android noch MapLibre, die Bereiche
 * kommen als vier Zahlen herein.
 *
 * Die Ausschnitte unten sind aus der Kameralogik von MapLibre gerechnet: Bei
 * Kamerazoom `z` ist die Welt `512 · 2^z` Punkte breit, ein uebliches Telefon
 * zeigt rund 360 × 800 Punkte.
 */
class OfflineTileMathTest {

    /**
     * Fixture mit maxZoom 20, unabhaengig vom Stil-Katalog: Die erwarteten
     * Zoom-Bereiche unten sind gegen diese Obergrenze gerechnet und sollen
     * nicht mitwandern, wenn der Katalog seinen Standardstil wechselt (wie
     * beim Abschied von CARTO, siehe `MapStyles.kt`).
     *
     * Ein 256er-**Raster** mit `offlineAllowed = true` — das gibt es im
     * Katalog seit der Kartenquellen-Pruefung nicht mehr (kein Rasteranbieter
     * erlaubt Vorab-Downloads). Die Fixture haelt die Raster-Rechnung (Versatz
     * +1) trotzdem unter Test, denn [offlineZoomRange] und
     * [offlineTileZoomRange] muessen fuer beide Bauarten stimmen.
     */
    private val voyager = MapStyle(
        id = "test-strasse",
        label = "Straßenkarte (Test)",
        urlTemplate = "https://tiles.example/{z}/{x}/{y}.png",
        maxZoom = 20,
        attribution = "Test",
        offlineAllowed = true,
    )
    private val openFreeMap = mapStyleById("openfreemap")
    private val opentopo = mapStyleById("opentopo")

    /** Sichtbarer Ausschnitt eines 360 × 800 dp grossen Telefons um [lat]/[lon]. */
    private fun phoneView(lat: Double, lon: Double, zoom: Double): DoubleArray {
        val worldPoints = 512.0 * Math.pow(2.0, zoom)
        val lonSpan = 360.0 * 360.0 / worldPoints
        // Grobe, fuer den Test ausreichende Naeherung der Mercator-Hoehe.
        val latSpan = 360.0 * 800.0 / worldPoints * Math.cos(lat * Math.PI / 180)
        return doubleArrayOf(lat + latSpan / 2, lat - latSpan / 2, lon + lonSpan / 2, lon - lonSpan / 2)
    }

    private fun plan(view: DoubleArray, zoom: Double, style: de.trailscape.app.ui.MapStyle) =
        planOfflineDownload(view[0], view[1], view[2], view[3], zoom, style)

    // ------------------------------------------------------------- Kachelzahl

    @Test
    fun `ganze Welt hat eine Kachel auf Stufe 0 und vier auf Stufe 1`() {
        assertEquals(1, estimateTileCount(85.0, -85.0, 179.9, -179.9, 0, 0))
        assertEquals(4, estimateTileCount(85.0, -85.0, 179.9, -179.9, 1, 1))
        assertEquals(5, estimateTileCount(85.0, -85.0, 179.9, -179.9, 0, 1))
    }

    @Test
    fun `verdrehte Grenzen ergeben keine Kacheln`() {
        assertEquals(0, estimateTileCount(48.0, 49.0, 8.0, 9.0, 10, 10))
        assertEquals(0, estimateTileCount(49.0, 48.0, 8.0, 9.0, 10, 10))
    }

    @Test
    fun `jede Stufe hoeher vervierfacht die Kachelzahl ungefaehr`() {
        val low = estimateTileCount(48.2, 48.0, 11.8, 11.5, 12, 12)
        val high = estimateTileCount(48.2, 48.0, 11.8, 11.5, 13, 13)
        assertTrue(high >= 3 * low, "Stufe 13 muss rund viermal so viele Kacheln haben: $low -> $high")
    }

    // ------------------------------------------------------------ Zoombereich

    @Test
    fun `die geladenen Kachelstufen liegen eine Stufe ueber der Kamerazoomstufe`() {
        // Das ist der Kern des zweiten Fehlers: MapLibre rechnet 256er-Raster
        // auf `zoom + log2(512/256)` um.
        val definition = offlineZoomRange(13.0, voyager)
        assertEquals(13..15, definition)
        assertEquals(14..16, offlineTileZoomRange(definition, voyager))
    }

    @Test
    fun `der Zoombereich endet spaetestens bei der hoechsten Stufe des Anbieters`() {
        // OpenTopoMap kann hoechstens 17 — die Definition darf deshalb nur bis
        // 16 gehen, damit die Kachelstufe genau 17 trifft.
        val definition = offlineZoomRange(17.0, opentopo)
        assertEquals(16..16, definition)
        assertEquals(17..17, offlineTileZoomRange(definition, opentopo))

        // Und bei einem Anbieter mit viel Reserve greift die eigene Grenze 17.
        assertEquals(17..17, offlineZoomRange(19.0, voyager))
        assertEquals(18..18, offlineTileZoomRange(17..17, voyager))
    }

    @Test
    fun `jeder Stil liefert einen gueltigen Zoombereich`() {
        for (style in mapStyles) {
            for (zoom in 0..20) {
                val definition = offlineZoomRange(zoom.toDouble(), style)
                val tiles = offlineTileZoomRange(definition, style)
                assertTrue(definition.first <= definition.last, "${style.id}@$zoom: $definition")
                assertTrue(tiles.first <= tiles.last, "${style.id}@$zoom: $tiles")
                assertTrue(tiles.last <= style.maxZoom, "${style.id}@$zoom laedt ueber maxZoom: $tiles")
            }
        }
    }

    @Test
    fun `beim Vektor-Stil ist die Kachelstufe gleich der Kamerazoomstufe`() {
        // Vektorkacheln sind 512 Punkt gross — kein Versatz. Die Quelle endet
        // bei 14; darueber vergroessert MapLibre, geladen wird nichts mehr.
        assertEquals(0, openFreeMap.tileZoomOffset)
        val definition = offlineZoomRange(13.0, openFreeMap)
        assertEquals(13..14, definition)
        assertEquals(13..14, offlineTileZoomRange(definition, openFreeMap))
        assertEquals(14..14, offlineZoomRange(16.0, openFreeMap))
    }

    // ------------------------------------------------------ Erlaubnis je Stil

    @Test
    fun `nur OpenFreeMap ist offline speicherbar, alle Rasterstile sind gesperrt`() {
        assertEquals(listOf("openfreemap"), mapStyles.filter { it.offlineAllowed }.map { it.id })
        assertTrue(mapStyles.filter { !it.isVector }.none { it.offlineAllowed })
        assertEquals(openFreeMap, offlineStyle())
        assertTrue(openFreeMap.isVector)
    }

    @Test
    fun `ein gesperrter Stil wird abgelehnt, egal wie klein der Ausschnitt ist`() {
        val munich = phoneView(lat = 48.14, lon = 11.58, zoom = 13.0)
        for (style in mapStyles.filter { !it.offlineAllowed }) {
            val rejected = assertIs<OfflineDownloadPlan.Rejected>(plan(munich, 13.0, style), style.id)
            // Ehrlich und mit Ausweg: warum nicht, und womit es geht.
            assertContains(rejected.message, style.label)
            assertContains(rejected.message, "erlaubt keine Vorab-Downloads")
            assertContains(rejected.message, openFreeMap.label)
        }
    }

    @Test
    fun `mit OpenFreeMap wird ein Stadtausschnitt angenommen`() {
        val munich = phoneView(lat = 48.14, lon = 11.58, zoom = 13.0)
        val ready = assertIs<OfflineDownloadPlan.Ready>(plan(munich, 13.0, openFreeMap))
        assertEquals(13..14, ready.tileZooms)
        assertTrue(ready.tileCount in 1..MAX_TILES_PER_DOWNLOAD, "Kachelzahl: ${ready.tileCount}")
    }

    @Test
    fun `auch der Vektor-Stil fuehrt eine eigene Offline-Adresse statt der Live-URL`() {
        // Die Live-URL zeigt ueber eine TileJSON auf wechselnde Kachelpfade;
        // die Region braucht die festgeschriebene Kopie (siehe pinStyleSources).
        assertEquals("https://offline-style.trailscape.invalid/openfreemap.json", offlineStyleUrl(openFreeMap))
        assertContains(offlineStyleUrl(mapStyleById("osmde")), ".invalid/")
    }

    // ---------------------------------------------------- Stil festschreiben

    /** Verkuerzter Liberty-Stil von OpenFreeMap (Stand 25.09.2026). */
    private val libertyStyle = """
        {"version":8,"name":"Liberty",
         "sources":{
           "ne2_shaded":{"maxzoom":6,"tileSize":256,
             "tiles":["https://tiles.openfreemap.org/natural_earth/ne2sr/{z}/{x}/{y}.png"],"type":"raster"},
           "openmaptiles":{"type":"vector","url":"https://tiles.openfreemap.org/planet"}},
         "sprite":"https://tiles.openfreemap.org/sprites/ofm_f384/ofm",
         "glyphs":"https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf",
         "layers":[{"id":"background","type":"background"},
           {"id":"road","type":"line","source":"openmaptiles","source-layer":"transportation"}]}
    """.trimIndent()

    /** Die TileJSON dazu, gekuerzt um die `vector_layers`. */
    private val planetTileJson = """
        {"tilejson":"3.0.0",
         "tiles":["https://tiles.openfreemap.org/planet/20260913_164504_pt/{z}/{x}/{y}.pbf"],
         "attribution":"<a href=\"https://openfreemap.org\">OpenFreeMap</a> © OpenMapTiles Data from OpenStreetMap",
         "bounds":[-180.0,-85.05113,180.0,85.05113],"minzoom":0,"maxzoom":14,"name":"OpenFreeMap"}
    """.trimIndent()

    @Test
    fun `nur die per TileJSON eingebundene Quelle muss nachgeladen werden`() {
        assertEquals(
            mapOf("openmaptiles" to "https://tiles.openfreemap.org/planet"),
            tileJsonSources(libertyStyle),
        )
    }

    @Test
    fun `festgeschrieben zeigt die Vektorquelle auf die versionierten Kachelpfade`() {
        val pinned = pinStyleSources(libertyStyle, mapOf("openmaptiles" to planetTileJson))
        val sources = Json.parseToJsonElement(pinned).jsonObject["sources"]!!.jsonObject
        val omt = sources["openmaptiles"]!!.jsonObject

        // Keine TileJSON mehr, die sich nach dem naechsten Planet-Update
        // aendern koennte — genau daran wurden Offline-Regionen still leer.
        assertEquals(null, omt["url"])
        assertEquals(
            "https://tiles.openfreemap.org/planet/20260913_164504_pt/{z}/{x}/{y}.pbf",
            omt["tiles"]!!.jsonArray.single().jsonPrimitive.content,
        )
        assertEquals("vector", omt["type"]!!.jsonPrimitive.content)
        assertEquals(14, omt["maxzoom"]!!.jsonPrimitive.content.toInt())
        assertEquals(0, omt["minzoom"]!!.jsonPrimitive.content.toInt())
        // Ohne sie fehlte der Pflichthinweis hinter dem Info-Knopf.
        assertContains(omt["attribution"]!!.jsonPrimitive.content, "OpenMapTiles")
        assertContains(omt["attribution"]!!.jsonPrimitive.content, "OpenStreetMap")

        // Alles andere bleibt, wie es war.
        assertEquals(
            Json.parseToJsonElement(libertyStyle).jsonObject["sources"]!!.jsonObject["ne2_shaded"],
            sources["ne2_shaded"],
        )
        val original = Json.parseToJsonElement(libertyStyle).jsonObject
        val result = Json.parseToJsonElement(pinned).jsonObject
        for (key in listOf("version", "sprite", "glyphs", "layers")) {
            assertEquals(original[key], result[key], key)
        }
        assertEquals(emptyMap(), tileJsonSources(pinned))
        assertEquals(
            "https://tiles.openfreemap.org/planet/20260913_164504_pt/{z}/{x}/{y}.pbf",
            pinnedTileTemplate(pinned),
        )
    }

    @Test
    fun `ohne brauchbare TileJSON wird nichts festgeschrieben`() {
        // Eine Region ohne Kacheladresse waere leer — lieber gleich ein Fehler.
        assertFailsWith<IllegalArgumentException> { pinStyleSources(libertyStyle, emptyMap()) }
        assertFailsWith<IllegalArgumentException> {
            pinStyleSources(libertyStyle, mapOf("openmaptiles" to """{"tiles":[]}"""))
        }
        assertFailsWith<IllegalArgumentException> {
            pinStyleSources(libertyStyle, mapOf("openmaptiles" to "<html>Fehler</html>"))
        }
    }

    @Test
    fun `ein Rasterstil hat nichts festzuschreiben`() {
        val raster = mapStyleById("osmde").toRasterStyleJson()
        assertEquals(emptyMap(), tileJsonSources(raster))
        assertEquals(null, pinnedTileTemplate(raster))
    }

    // ------------------------------------------------------------ Kantenlaenge

    @Test
    fun `Kantenlaengen stimmen groessenordnungsmaessig`() {
        // Ein Grad Breite sind rund 111 km.
        assertEquals(111.0, boundsHeightKm(48.5, 47.5), 1.0)
        // Ein Grad Laenge auf 48 Grad Nord sind rund 74 km.
        assertEquals(74.0, boundsWidthKm(48.5, 47.5, 12.0, 11.0), 2.0)
    }

    // ----------------------------------------------------------------- Planung

    @Test
    fun `der Europa-Ausschnitt aus dem Bugreport wird abgelehnt`() {
        // Kamerazoom 4 ueber Mitteleuropa — der Fall aus Robins Screenshot.
        val europe = phoneView(lat = 50.0, lon = 10.0, zoom = 4.0)
        val plan = plan(europe, 4.0, voyager)
        val rejected = assertIs<OfflineDownloadPlan.Rejected>(plan)
        assertContains(rejected.message, "Zoome näher heran")
    }

    @Test
    fun `der Europa-Ausschnitt blieb unter der alten Kachelgrenze`() {
        // Belegt, warum der Download ueberhaupt startete: Die Kachelgrenze
        // allein haette ihn nicht aufgehalten — weder in der alten Rechnung
        // auf den Kamerazoomstufen noch in der neuen auf den Kachelstufen.
        val europe = phoneView(lat = 50.0, lon = 10.0, zoom = 4.0)
        val alteRechnung = estimateTileCount(europe[0], europe[1], europe[2], europe[3], 4, 6)
        assertTrue(
            alteRechnung <= MAX_TILES_PER_DOWNLOAD,
            "Die alte Schaetzung war mit $alteRechnung Kacheln unauffaellig",
        )
    }

    @Test
    fun `ein Stadtausschnitt wird angenommen und bleibt unter der Kachelgrenze`() {
        val munich = phoneView(lat = 48.14, lon = 11.58, zoom = 13.0)
        val ready = assertIs<OfflineDownloadPlan.Ready>(plan(munich, 13.0, voyager))
        assertEquals(13, ready.minZoom)
        assertEquals(15, ready.maxZoom)
        assertEquals(14..16, ready.tileZooms)
        assertTrue(ready.tileCount in 1..MAX_TILES_PER_DOWNLOAD, "Kachelzahl: ${ready.tileCount}")
        assertEquals("Zoomstufen 14–16", ready.zoomLabel)
    }

    @Test
    fun `die Kachelgrenze greift bei einem grossen Ausschnitt knapp unter der Kantengrenze`() {
        // 140 km breit, 120 km hoch: unter der Kantengrenze, aber mit den
        // Kachelstufen 11 bis 13 weit ueber 250 Kacheln.
        val plan = planOfflineDownload(
            north = 48.54,
            south = 47.46,
            east = 12.94,
            west = 11.06,
            cameraZoom = 10.0,
            style = voyager,
        )
        val rejected = assertIs<OfflineDownloadPlan.Rejected>(plan)
        assertContains(rejected.message, "Kacheln")
        assertContains(rejected.message, "$MAX_TILES_PER_DOWNLOAD")
    }

    @Test
    fun `verdrehte Grenzen werden abgelehnt statt gerechnet`() {
        val plan = planOfflineDownload(47.0, 48.0, 11.0, 12.0, 13.0, voyager)
        assertIs<OfflineDownloadPlan.Rejected>(plan)
    }

    @Test
    fun `die Kantengrenze greift genau an der Grenze`() {
        // Knapp unter 150 km hoch (1,3 Grad Breite = rund 145 km).
        val schmal = planOfflineDownload(48.65, 47.35, 11.6, 11.4, 9.0, voyager)
        assertIs<OfflineDownloadPlan.Ready>(schmal)
        // Knapp darueber (1,4 Grad = rund 156 km).
        val breit = planOfflineDownload(48.7, 47.3, 11.6, 11.4, 9.0, voyager)
        assertIs<OfflineDownloadPlan.Rejected>(breit)
    }

    // -------------------------------------------------------------- Aufsicht

    @Test
    fun `die Abbruchmeldung nennt die Wartezeit und die letzte Ursache`() {
        val ohne = stalledMessage(null)
        assertContains(ohne, "${STALL_TIMEOUT_MS / 1000} Sekunden")
        assertContains(ohne, "Internetverbindung")

        val mit = stalledMessage("keine Verbindung: Unable to resolve host")
        assertContains(mit, "keine Verbindung")
        assertContains(mit, "${STALL_TIMEOUT_MS / 1000} Sekunden")

        // Leere Ursachen sollen nicht als „()" durchschlagen.
        assertEquals(ohne, stalledMessage("   "))
    }

    // ------------------------------------------------------------- Style-URL

    @Test
    fun `die Style-Adresse traegt die Stilkennung und ist stabil`() {
        for (style in mapStyles) {
            val url = offlineStyleUrl(style)
            assertTrue(url.startsWith("https://"), url)
            assertContains(url, style.id)
            assertEquals(url, offlineStyleUrl(style), "Die Adresse muss bei jedem Aufruf gleich sein")
        }
        // Verschiedene Stile duerfen sich nicht denselben Eintrag teilen.
        assertEquals(mapStyles.size, mapStyles.map { offlineStyleUrl(it) }.toSet().size)
    }

    // ------------------------------------------------------------- Metadaten

    @Test
    fun `Metadaten ueberstehen den Weg durch die Datenbank`() {
        val raw = offlineRegionMetadata("Straßenkarte · 09.08.2026", "voyager", 1_754_700_000_000L)
        val info = readOfflineRegionInfo(raw)
        assertEquals(OfflineRegionInfo("Straßenkarte · 09.08.2026", "voyager", 1_754_700_000_000L), info)
    }

    @Test
    fun `die festgeschriebene Kacheladresse ueberlebt den Weg durch die Datenbank`() {
        val template = "https://tiles.openfreemap.org/planet/20260913_164504_pt/{z}/{x}/{y}.pbf"
        val raw = offlineRegionMetadata("Vektorkarte · 25.09.2026", "openfreemap", 1L, template)
        assertEquals(template, readOfflineRegionInfo(raw)?.tileTemplate)
        // Rasterregionen und aeltere Regionen tragen das Feld nicht.
        assertEquals(null, readOfflineRegionInfo(offlineRegionMetadata("Alt", "osmde", 1L))?.tileTemplate)
    }

    @Test
    fun `fremde oder fehlende Metadaten ergeben null statt Absturz`() {
        assertEquals(null, readOfflineRegionInfo(null))
        assertEquals(null, readOfflineRegionInfo(ByteArray(0)))
        assertEquals(null, readOfflineRegionInfo("kein JSON".toByteArray()))
        assertEquals(null, readOfflineRegionInfo("""{"foo":1}""".toByteArray()))
    }

    @Test
    fun `Metadaten ohne Stil oder Datum bleiben lesbar`() {
        val info = readOfflineRegionInfo("""{"name":"Alt"}""".toByteArray())
        assertEquals(OfflineRegionInfo("Alt", "", 0L), info)
    }
}
