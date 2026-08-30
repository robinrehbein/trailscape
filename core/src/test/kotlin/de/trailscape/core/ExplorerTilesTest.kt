package de.trailscape.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests der reinen Rechnung hinter den „Entdeckt-Kacheln" (`ExplorerTiles.kt`).
 *
 * Die Slippy-Map-Referenzwerte unten sind mit derselben Formel wie
 * [explorerTileAt] unabhaengig nachgerechnet (`floor((lon+180)/360*16384)`
 * bzw. die Mercator-Formel fuer `y`, siehe Datei-KDoc von `ExplorerTiles.kt`):
 * Paris (48,8566° N, 2,3522° O) liegt auf Stufe 14 in Kachel x=8299, y=5636;
 * Berlin (52,5200° N, 13,4050° O) in x=8802, y=5373; Muenchen
 * (48,1374° N, 11,5755° O) in x=8718, y=5685.
 */
class ExplorerTilesTest {

    // --------------------------------------------------------------- Kachel

    @Test
    fun `bekannte Staedte treffen die von Hand nachgerechnete Kachel`() {
        assertEquals(ExplorerTile(8299, 5636), explorerTileAt(48.8566, 2.3522))
        assertEquals(ExplorerTile(8802, 5373), explorerTileAt(52.5200, 13.4050))
        assertEquals(ExplorerTile(8718, 5685), explorerTileAt(48.1374, 11.5755))
    }

    @Test
    fun `explorerTileBounds ist invers zu explorerTileAt`() {
        // Der Mittelpunkt der Kachelgrenzen muss wieder in derselben Kachel landen.
        for ((lat, lon) in listOf(48.8566 to 2.3522, 52.5200 to 13.4050, -33.8688 to 151.2093, 0.0 to 0.0)) {
            val tile = explorerTileAt(lat, lon)
            val bounds = explorerTileBounds(tile)
            val midLat = (bounds.north + bounds.south) / 2
            val midLon = (bounds.east + bounds.west) / 2
            assertEquals(tile, explorerTileAt(midLat, midLon), "Kachel $tile, Bounds $bounds")
            assertTrue(bounds.north > bounds.south, "Nord muss ueber Sued liegen: $bounds")
            assertTrue(bounds.east > bounds.west, "Ost muss ueber West liegen: $bounds")
        }
    }

    @Test
    fun `Kacheln jenseits der Gitterraender werden geklemmt statt negativ oder ausserhalb`() {
        // 200 Grad Laenge und 95 Grad Breite gibt es nicht — jenseits von
        // ±180° bzw. ±85,05112878° (Mercator-Grenze) wird auf den jeweils
        // gueltigen Rand geklemmt, statt einen Index ausserhalb 0..16383 zu
        // erzeugen.
        val northPoleish = explorerTileAt(95.0, 200.0)
        assertEquals(16383, northPoleish.x)
        assertEquals(0, northPoleish.y)

        val southPoleish = explorerTileAt(-95.0, -200.0)
        assertEquals(0, southPoleish.x)
        assertEquals(16383, southPoleish.y)
    }

    // ------------------------------------------------------------- Track-Interpolation

    @Test
    fun `zwei Punkte drei Kacheln auseinander auf gleicher Breite erfassen alle Zwischenkacheln`() {
        // Eine Kachel bei 48 Grad Nord ist rund 1,5 km breit; drei Kacheln
        // Abstand sind also weit unter der 20-km-Grenze, es wird interpoliert.
        val start = explorerTileAt(48.0, 11.0)
        val target = ExplorerTile(start.x + 3, start.y)
        val targetBounds = explorerTileBounds(target)
        val targetLon = (targetBounds.east + targetBounds.west) / 2

        val points = listOf(TrackPoint(lat = 48.0, lon = 11.0), TrackPoint(lat = 48.0, lon = targetLon))
        val tiles = explorerTilesForTrack(points)

        for (dx in 0..3) {
            assertTrue(
                ExplorerTile(start.x + dx, start.y) in tiles,
                "Kachel x=${start.x + dx} fehlt in $tiles",
            )
        }
    }

    @Test
    fun `ein 25-km-Sprung wird nicht interpoliert`() {
        // 25 km bei 48 Grad Nord entlang eines Breitengrads: rund 0,34 Grad Laenge
        // (aequirektangulare Naeherung: 0,34 * 111,32 km * cos(48°) ≈ 25,3 km).
        val a = TrackPoint(lat = 48.0, lon = 11.0)
        val b = TrackPoint(lat = 48.0, lon = 11.34)

        val tiles = explorerTilesForTrack(listOf(a, b))

        val tileA = explorerTileAt(a.lat, a.lon)
        val tileB = explorerTileAt(b.lat, b.lon)
        assertEquals(setOf(tileA, tileB), tiles)
        // Der Sprung ueberspringt garantiert mehr als nur die Endpunkte —
        // ohne die 20-km-Grenze waeren das viele weitere Kacheln.
        assertTrue(tileB.x - tileA.x > 2)
    }

    @Test
    fun `leerer Track ergibt keine Kacheln, ein Punkt genau eine`() {
        assertEquals(emptySet(), explorerTilesForTrack(emptyList()))
        val single = TrackPoint(lat = 48.0, lon = 11.0)
        assertEquals(setOf(explorerTileAt(single.lat, single.lon)), explorerTilesForTrack(listOf(single)))
    }

    // --------------------------------------------------------- Groesstes Quadrat

    @Test
    fun `leere Menge ergibt kein Quadrat`() {
        assertNull(largestExplorerSquare(emptySet()))
    }

    @Test
    fun `eine einzelne Kachel ist ein Quadrat der Groesse eins`() {
        val square = largestExplorerSquare(setOf(ExplorerTile(5, 5)))
        assertEquals(ExplorerSquare(5, 5, 1), square)
    }

    @Test
    fun `ein 3x3-Block innerhalb eines moeglichen 4x4 mit Eckloch bleibt bei drei`() {
        // Volles 4x4-Feld (0..3, 0..3) minus der Ecke (3,3): Das groesste
        // vollstaendig entdeckte Quadrat ist das 3x3-Feld (0..2, 0..2).
        val tiles = (0..3).flatMap { x -> (0..3).map { y -> ExplorerTile(x, y) } }
            .filterNot { it.x == 3 && it.y == 3 }
            .toSet()

        val square = largestExplorerSquare(tiles)
        assertEquals(3, square?.size)
    }

    @Test
    fun `bei mehreren gleich grossen Quadraten ist irgendeines das Ergebnis`() {
        // Zwei getrennte 2x2-Bloecke gleicher Groesse — Hauptsache ein
        // gueltiges 2x2-Quadrat kommt zurueck, welches genau ist unerheblich.
        val block1 = setOf(ExplorerTile(0, 0), ExplorerTile(1, 0), ExplorerTile(0, 1), ExplorerTile(1, 1))
        val block2 = setOf(ExplorerTile(10, 10), ExplorerTile(11, 10), ExplorerTile(10, 11), ExplorerTile(11, 11))
        val square = largestExplorerSquare(block1 + block2)
        assertEquals(2, square?.size)
    }

    // ------------------------------------------------------------- Sammeln (Cache)

    /** Einfacher Map-basierter Fake-Store fuer die Tests, mit Zaehlern fuer Aufrufe. */
    private class FakeExplorerTilesStore : ExplorerTilesStore {
        val entries = mutableMapOf<String, StoredExplorerTiles>()
        var flushCount = 0

        override fun get(id: String): StoredExplorerTiles? = entries[id]
        override fun put(id: String, entry: StoredExplorerTiles) {
            entries[id] = entry
        }

        override fun retainAll(ids: Set<String>) {
            entries.keys.retainAll(ids)
        }

        override fun flush() {
            flushCount++
        }
    }

    private fun ride(id: String, updatedAt: Long, points: List<TrackPoint>, planned: Boolean = false): Ride =
        Ride(
            id = id,
            name = id,
            createdAt = updatedAt,
            updatedAt = updatedAt,
            stats = RideStats.empty,
            points = points,
            planned = planned,
        )

    @Test
    fun `unveraenderter Fingerabdruck ruft loadRide kein zweites Mal`() {
        val store = FakeExplorerTilesStore()
        val points = listOf(TrackPoint(lat = 48.0, lon = 11.0), TrackPoint(lat = 48.01, lon = 11.0))
        val r = ride(id = "a", updatedAt = 100L, points = points)
        val summary = r.toSummary()
        var loadCount = 0
        val loadRide: (String) -> Ride? = { id -> loadCount++; if (id == "a") r else null }

        val first = collectExplorerTiles(listOf(summary), store, loadRide)
        assertEquals(1, loadCount)
        assertTrue(first.isNotEmpty())

        val second = collectExplorerTiles(listOf(summary), store, loadRide)
        assertEquals(1, loadCount, "Bei unveraendertem Fingerabdruck darf loadRide nicht erneut aufgerufen werden")
        assertEquals(first, second)
        assertEquals(2, store.flushCount)
    }

    @Test
    fun `geaenderte updatedAt laesst die Tour neu laden`() {
        val store = FakeExplorerTilesStore()
        val points = listOf(TrackPoint(lat = 48.0, lon = 11.0), TrackPoint(lat = 48.01, lon = 11.0))
        var current = ride(id = "a", updatedAt = 100L, points = points)
        var loadCount = 0
        val loadRide: (String) -> Ride? = { id -> loadCount++; if (id == "a") current else null }

        collectExplorerTiles(listOf(current.toSummary()), store, loadRide)
        assertEquals(1, loadCount)

        current = current.copy(updatedAt = 200L)
        collectExplorerTiles(listOf(current.toSummary()), store, loadRide)
        assertEquals(2, loadCount, "Eine geaenderte updatedAt muss das Destillat neu berechnen")
    }

    @Test
    fun `geplante Touren zaehlen nicht als entdeckt`() {
        val store = FakeExplorerTilesStore()
        val plannedRide = ride(
            id = "geplant",
            updatedAt = 100L,
            points = listOf(TrackPoint(lat = 10.0, lon = 10.0), TrackPoint(lat = 10.1, lon = 10.0)),
            planned = true,
        )
        var loadCount = 0
        val loadRide: (String) -> Ride? = { id -> loadCount++; plannedRide.takeIf { id == "geplant" } }

        val tiles = collectExplorerTiles(listOf(plannedRide.toSummary()), store, loadRide)

        assertEquals(emptySet(), tiles)
        assertEquals(0, loadCount, "Eine geplante Tour darf gar nicht erst geladen werden")
        assertTrue(store.entries.isEmpty())
    }

    @Test
    fun `retainAll raeumt Eintraege geloeschter Touren auf`() {
        val store = FakeExplorerTilesStore()
        val a = ride(id = "a", updatedAt = 1L, points = listOf(TrackPoint(lat = 48.0, lon = 11.0)))
        val b = ride(id = "b", updatedAt = 1L, points = listOf(TrackPoint(lat = 49.0, lon = 12.0)))
        val loadRide: (String) -> Ride? = { id -> if (id == "a") a else if (id == "b") b else null }

        collectExplorerTiles(listOf(a.toSummary(), b.toSummary()), store, loadRide)
        assertEquals(setOf("a", "b"), store.entries.keys)

        // "b" ist inzwischen geloescht — nur noch "a" liegt im Index.
        collectExplorerTiles(listOf(a.toSummary()), store, loadRide)
        assertEquals(setOf("a"), store.entries.keys)
    }

    @Test
    fun `eine nicht mehr ladbare Tour wird uebersprungen statt abzustuerzen`() {
        val store = FakeExplorerTilesStore()
        val missing = ride(id = "weg", updatedAt = 1L, points = emptyList())
        val tiles = collectExplorerTiles(listOf(missing.toSummary()), store, loadRide = { null })
        assertEquals(emptySet(), tiles)
    }
}
