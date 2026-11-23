package de.trailscape.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.cos
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Tests fuer `RiddenWays.kt` — der Index der gefahrenen Wege hinter
 * „Neue Wege".
 *
 * Die Tracks entstehen synthetisch in einem lokalen Meter-Koordinatensystem
 * um [ORIGIN]: Osten = x, Norden = y. [road] legt 1-s-Punkte (7 m Abstand,
 * gut 25 km/h) auf eine Gerade, wahlweise mit begrenztem GPS-Rauschen.
 */
class RiddenWaysTest {

    private companion object {
        /** Irgendwo auf der Schwaebischen Alb. */
        val ORIGIN = TrackPoint(lat = 48.4, lon = 9.4)
        const val M_PER_DEG = 111_320.0
    }

    private fun at(xM: Double, yM: Double, origin: TrackPoint = ORIGIN): TrackPoint = TrackPoint(
        lat = origin.lat + yM / M_PER_DEG,
        lon = origin.lon + xM / (M_PER_DEG * cos(origin.lat * PI / 180)),
    )

    /**
     * Punkte von (x0, y0) nach (x1, y1) im Abstand [stepM]; [noiseM] verschiebt
     * jeden Punkt gleichverteilt um hoechstens so viele Meter je Achse.
     */
    private fun road(
        x0: Double, y0: Double, x1: Double, y1: Double,
        stepM: Double = 7.0,
        noiseM: Double = 0.0,
        seed: Int = 1,
        origin: TrackPoint = ORIGIN,
    ): List<TrackPoint> {
        val rnd = Random(seed)
        val len = kotlin.math.hypot(x1 - x0, y1 - y0)
        val n = kotlin.math.ceil(len / stepM).toInt().coerceAtLeast(1)
        return (0..n).map { i ->
            val t = i.toDouble() / n
            val nx = if (noiseM > 0) rnd.nextDouble(-noiseM, noiseM) else 0.0
            val ny = if (noiseM > 0) rnd.nextDouble(-noiseM, noiseM) else 0.0
            at(x0 + (x1 - x0) * t + nx, y0 + (y1 - y0) * t + ny, origin)
        }
    }

    private fun ride(id: String, points: List<TrackPoint>, updatedAt: Long = 1L, planned: Boolean = false) = Ride(
        id = id,
        name = id,
        createdAt = updatedAt,
        updatedAt = updatedAt,
        stats = RideStats(distanceKm = 0.0, ascentM = 0.0, descentM = 0.0),
        points = points,
        planned = planned,
    )

    // ------------------------------------------------------------ Bestand

    @Test
    fun `leerer Bestand - alles ist neu`() {
        val index = RiddenWaysIndex.EMPTY
        assertTrue(index.isEmpty)
        assertFalse(index.isKnown(ORIGIN.lat, ORIGIN.lon))
        val route = road(0.0, 0.0, 3000.0, 0.0, stepM = 500.0)
        val d = newWayDistance(route, index)
        assertEquals(3.0, d.totalKm, 0.01)
        assertEquals(d.totalKm, d.newKm, 1e-9)
        assertEquals(1.0, newDistanceShare(route, index), 1e-9)

        assertEquals(0, riddenWayCells(emptyList()).size)
        assertTrue(RiddenWaysIndex.fromTracks(emptyList()).isEmpty)
        assertEquals(0.0, newDistanceShare(emptyList(), index))
        assertEquals(0.0, newDistanceShare(listOf(ORIGIN), index))
    }

    @Test
    fun `Hin- und Rueckweg auf derselben Strasse zaehlen als bekannt`() {
        // Hin auf der rechten Fahrbahn, zurueck auf der linken, mit Rauschen.
        val out = road(0.0, -2.0, 4000.0, -2.0, noiseM = 6.0, seed = 3)
        val back = road(4000.0, 2.0, 0.0, 2.0, noiseM = 6.0, seed = 4)
        val index = RiddenWaysIndex.fromTracks(listOf(out + back))

        // Die geplante Route liegt auf der Mittellinie, mit Knoten nur alle 800 m.
        val route = road(0.0, 0.0, 4000.0, 0.0, stepM = 800.0)
        assertEquals(0.0, newDistanceShare(route, index), 1e-9)
        // Und genauso in Gegenrichtung.
        assertEquals(0.0, newDistanceShare(route.reversed(), index), 1e-9)
    }

    @Test
    fun `eine parallele Strasse in 60 m Abstand zaehlt als neu`() {
        val ridden = road(0.0, 0.0, 4000.0, 0.0, noiseM = 4.0, seed = 7)
        val index = RiddenWaysIndex.fromTracks(listOf(ridden))

        val parallelNorth = road(0.0, 60.0, 4000.0, 60.0, stepM = 400.0)
        val parallelSouth = road(0.0, -60.0, 4000.0, -60.0, stepM = 400.0)
        assertEquals(1.0, newDistanceShare(parallelNorth, index), 1e-9)
        assertEquals(1.0, newDistanceShare(parallelSouth, index), 1e-9)
        // Gegenprobe: die gefahrene Strasse selbst ist bekannt.
        assertEquals(0.0, newDistanceShare(road(0.0, 0.0, 4000.0, 0.0, stepM = 400.0), index), 1e-9)
    }

    @Test
    fun `die Toleranz gilt in Metern - am Aequator wie im hohen Norden`() {
        for (origin in listOf(TrackPoint(0.5, 30.0), TrackPoint(48.4, 9.4), TrackPoint(63.4, 10.4), TrackPoint(-33.9, 151.2))) {
            val ridden = road(0.0, 0.0, 2000.0, 0.0, noiseM = 4.0, origin = origin)
            val index = RiddenWaysIndex.fromTracks(listOf(ridden))
            val same = road(0.0, 0.0, 2000.0, 0.0, stepM = 250.0, origin = origin)
            val parallel = road(0.0, 60.0, 2000.0, 60.0, stepM = 250.0, origin = origin)
            // Auch nordsuedlich: eine Parallele 60 m weiter oestlich.
            val riddenNs = road(0.0, 0.0, 0.0, 2000.0, noiseM = 4.0, origin = origin)
            val indexNs = RiddenWaysIndex.fromTracks(listOf(riddenNs))
            val parallelNs = road(60.0, 0.0, 60.0, 2000.0, stepM = 250.0, origin = origin)
            assertEquals(0.0, newDistanceShare(same, index), 1e-9, "bekannt bei $origin")
            assertEquals(1.0, newDistanceShare(parallel, index), 1e-9, "neu bei $origin")
            assertEquals(1.0, newDistanceShare(parallelNs, indexNs), 1e-9, "neu (N-S) bei $origin")
        }
    }

    @Test
    fun `GPS-Rauschen bis 15 m macht den Weg nicht neu`() {
        val noisy = road(0.0, 0.0, 5000.0, 0.0, noiseM = 15.0, seed = 11)
        val index = RiddenWaysIndex.fromTracks(listOf(noisy))
        val route = road(0.0, 0.0, 5000.0, 0.0, stepM = 1000.0)
        assertEquals(0.0, newDistanceShare(route, index), 1e-9)
    }

    @Test
    fun `garantierte Grenzen - bis 25 m bekannt, ab 54 m neu`() {
        val p = at(0.0, 0.0)
        val index = RiddenWaysIndex.fromTracks(listOf(listOf(p)))
        val rnd = Random(5)
        repeat(2000) {
            val angle = rnd.nextDouble(0.0, 2 * PI)
            val near = at(24.9 * cos(angle), 24.9 * kotlin.math.sin(angle))
            val far = at(54.0 * cos(angle), 54.0 * kotlin.math.sin(angle))
            assertTrue(index.isKnown(near.lat, near.lon), "24,9 m bei ${Math.toDegrees(angle)}°")
            assertFalse(index.isKnown(far.lat, far.lon), "54 m bei ${Math.toDegrees(angle)}°")
        }
    }

    @Test
    fun `eine halb bekannte Route ist zur Haelfte neu`() {
        val index = RiddenWaysIndex.fromTracks(listOf(road(0.0, 0.0, 3000.0, 0.0, noiseM = 5.0)))
        // 3 km auf der gefahrenen Strasse, dann 3 km nach Norden auf Neuland.
        val route = listOf(at(0.0, 0.0), at(3000.0, 0.0), at(3000.0, 3000.0))
        val d = newWayDistance(route, index)
        assertEquals(6.0, d.totalKm, 0.01)
        // Bekannt bleiben die ersten 25–50 m hinter der Kreuzung.
        assertEquals(2.97, d.newKm, 0.03)
        assertEquals(0.5, d.share, 0.01)
    }

    @Test
    fun `duenne Aufzeichnung wird interpoliert`() {
        // Ein Punkt alle 150 m (Stromsparmodus): die Strecke dazwischen ist trotzdem bekannt.
        val sparse = road(0.0, 0.0, 3000.0, 0.0, stepM = 150.0)
        val index = RiddenWaysIndex.fromTracks(listOf(sparse))
        assertEquals(0.0, newDistanceShare(road(0.0, 0.0, 3000.0, 0.0, stepM = 3000.0), index), 1e-9)
    }

    @Test
    fun `GPS-Sprung wird nicht als gefahrene Strecke gezaehlt`() {
        // 1 km gefahren, dann ein Sprung um 3 km (Funkloch, zusammengefuegte Tour), dann 1 km.
        val track = road(0.0, 0.0, 1000.0, 0.0) + road(4000.0, 0.0, 5000.0, 0.0)
        val index = RiddenWaysIndex.fromTracks(listOf(track))
        val gap = road(1100.0, 0.0, 3900.0, 0.0, stepM = 2800.0)
        assertEquals(1.0, newDistanceShare(gap, index), 1e-9)
        // Beide gefahrenen Stuecke sind bekannt.
        assertEquals(0.0, newDistanceShare(road(0.0, 0.0, 1000.0, 0.0, stepM = 1000.0), index), 1e-9)
        assertEquals(0.0, newDistanceShare(road(4000.0, 0.0, 5000.0, 0.0, stepM = 1000.0), index), 1e-9)
    }

    @Test
    fun `Stehen an der Ampel erzeugt keine Zellwolke`() {
        val rnd = Random(9)
        // Zehn Minuten Pause mit 1-s-Punkten und ±3 m Rauschen.
        val standing = (0 until 600).map { at(rnd.nextDouble(-3.0, 3.0), rnd.nextDouble(-3.0, 3.0)) }
        assertTrue(riddenWayCells(standing).size <= 9, "Zellen: ${riddenWayCells(standing).size}")
    }

    @Test
    fun `Hin und zurueck kostet keine doppelten Zellen, Zellen liegen nebeneinander`() {
        val out = road(0.0, 0.0, 2000.0, 0.0)
        val cellsOut = riddenWayCells(out)
        val cellsBoth = riddenWayCells(out + out.reversed())
        assertEquals(cellsOut.size, cellsBoth.size)
        // ~65 Zellen je km bei 20-m-Zellen — eine Gerade nach Osten braucht 50 je km.
        assertTrue(cellsOut.size in 95..110, "Zellen: ${cellsOut.size}")
        assertEquals(cellsOut.size, cellsOut.distinct().size)
    }

    @Test
    fun `plus mischt wie ein Neuaufbau`() {
        val a = riddenWayCells(road(0.0, 0.0, 2000.0, 0.0, noiseM = 5.0))
        val b = riddenWayCells(road(1000.0, -500.0, 1000.0, 1500.0, noiseM = 5.0))
        val merged = RiddenWaysIndex.of(a.copyOf()).plus(b)
        val rebuilt = RiddenWaysIndex.of(a + b)
        assertEquals(rebuilt.size, merged.size)
        for (c in a + b) assertTrue(c in merged)
        assertSame(merged, merged.plus(LongArray(0)))
        assertEquals(merged.size, merged.plus(a).size)
    }

    // ------------------------------------------------------------ Cache

    @Test
    fun `Cache ueberlebt Schreiben und Lesen, auch suedlich und westlich`() {
        val entries = linkedMapOf(
            "alb" to StoredRiddenWays(17L, 600, riddenWayCells(road(0.0, 0.0, 3000.0, 1000.0, noiseM = 5.0))),
            "sydney" to StoredRiddenWays(
                18L, 300,
                riddenWayCells(road(0.0, 0.0, 1500.0, -800.0, origin = TrackPoint(-33.9, 151.2))),
            ),
            "anden" to StoredRiddenWays(
                19L, 300,
                riddenWayCells(road(0.0, 0.0, 800.0, 800.0, origin = TrackPoint(-13.5, -71.9))),
            ),
            "leer" to StoredRiddenWays(20L, 0, LongArray(0)),
        )
        val bytes = ByteArrayOutputStream().also { writeRiddenWaysCache(entries, it) }.toByteArray()
        val back = assertNotNull(readRiddenWaysCache(ByteArrayInputStream(bytes)))
        assertEquals(entries.keys.toList(), back.keys.toList())
        for ((id, e) in entries) {
            assertEquals(e, back[id])
            assertContentEquals(e.cells, back[id]!!.cells)
        }
        // Fahrtreihenfolge macht's klein: im Schnitt hoechstens gut 2 Byte je Zelle.
        val cellCount = entries.values.sumOf { it.cells.size }
        assertTrue(bytes.size < cellCount * 2.3 + 200, "${bytes.size} Byte fuer $cellCount Zellen")
    }

    @Test
    fun `kaputter oder fremder Cache gilt als leer`() {
        val entries = mapOf("a" to StoredRiddenWays(1L, 10, riddenWayCells(road(0.0, 0.0, 500.0, 0.0))))
        val bytes = ByteArrayOutputStream().also { writeRiddenWaysCache(entries, it) }.toByteArray()
        assertNull(readRiddenWaysCache(ByteArrayInputStream(bytes.copyOf(bytes.size - 3))))
        assertNull(readRiddenWaysCache(ByteArrayInputStream("{\"version\":1}".toByteArray())))
        assertNull(readRiddenWaysCache(ByteArrayInputStream(ByteArray(0))))
        // Andere Version
        val other = bytes.copyOf().also { it[7] = (RIDDEN_WAYS_CACHE_VERSION + 1).toByte() }
        assertNull(readRiddenWaysCache(ByteArrayInputStream(other)))
    }

    // ------------------------------------------------------------ Inkrementell

    private class CountingLoader(val rides: Map<String, Ride>) {
        val loads = mutableListOf<String>()
        fun load(id: String): Ride? {
            loads += id
            return rides[id]
        }
    }

    @Test
    fun `inkrementell - unveraendert, dazugekommen, geloescht, geaendert, geplant`() {
        val a = ride("a", road(0.0, 0.0, 2000.0, 0.0))
        val b = ride("b", road(0.0, 500.0, 2000.0, 500.0))
        val c = ride("c", road(0.0, 1000.0, 2000.0, 1000.0))
        val plan = ride("p", road(0.0, 2000.0, 2000.0, 2000.0), planned = true)
        val store = InMemoryRiddenWaysStore()
        val loader = CountingLoader(listOf(a, b, c, plan).associateBy { it.id })

        // Erster Lauf: alles Gefahrene laden, Geplantes nie.
        val first = updateRiddenWays(null, listOf(a, b, plan).map { it.toSummary() }, store, loader::load)
        assertEquals(listOf("a", "b"), loader.loads)
        assertEquals(2, first.rideCount)
        assertEquals(setOf("a", "b"), store.entries.keys)
        val planPoint = at(1000.0, 2000.0)
        assertFalse(first.index.isKnown(planPoint.lat, planPoint.lon))

        // Unveraendert: dieselbe Instanz, kein Laden, kein Store-Zugriff.
        loader.loads.clear()
        val flushes = store.flushes
        val same = updateRiddenWays(first, listOf(a, b, plan).map { it.toSummary() }, store, loader::load)
        assertSame(first, same)
        assertTrue(loader.loads.isEmpty())
        assertEquals(flushes, store.flushes)

        // Dazugekommen: nur die neue Tour wird geladen.
        val added = updateRiddenWays(first, listOf(a, b, c, plan).map { it.toSummary() }, store, loader::load)
        assertEquals(listOf("c"), loader.loads)
        val cPoint = at(1000.0, 1000.0)
        assertTrue(added.index.isKnown(cPoint.lat, cPoint.lon))
        assertEquals(
            RiddenWaysIndex.fromTracks(listOf(a.points, b.points, c.points)).size,
            added.index.size,
        )

        // Geloescht: Neuaufbau aus dem Cache, ohne eine Tour zu laden.
        loader.loads.clear()
        val deleted = updateRiddenWays(added, listOf(a, c).map { it.toSummary() }, store, loader::load)
        assertTrue(loader.loads.isEmpty())
        val bPoint = at(1000.0, 500.0)
        assertFalse(deleted.index.isKnown(bPoint.lat, bPoint.lon))
        assertEquals(setOf("a", "c"), store.entries.keys)

        // Geaendert (neuer Stand): genau diese Tour wird neu geladen.
        val a2 = ride("a", road(0.0, -700.0, 2000.0, -700.0), updatedAt = 2L)
        val loader2 = CountingLoader(mapOf("a" to a2, "c" to c))
        val changed = updateRiddenWays(deleted, listOf(a2, c).map { it.toSummary() }, store, loader2::load)
        assertEquals(listOf("a"), loader2.loads)
        val oldA = at(1000.0, 0.0)
        val newA = at(1000.0, -700.0)
        assertFalse(changed.index.isKnown(oldA.lat, oldA.lon))
        assertTrue(changed.index.isKnown(newA.lat, newA.lon))
    }

    @Test
    fun `Indoor-Fahrt kommt weder in den Index noch in den Cache`() {
        val a = ride("a", road(0.0, 0.0, 2000.0, 0.0))
        val drinnen = ride("i", road(0.0, 3000.0, 2000.0, 3000.0)).copy(indoor = true)
        val store = InMemoryRiddenWaysStore()
        val loader = CountingLoader(listOf(a, drinnen).associateBy { it.id })

        val snapshot = updateRiddenWays(null, listOf(a, drinnen).map { it.toSummary() }, store, loader::load)
        assertEquals(listOf("a"), loader.loads)
        assertEquals(1, snapshot.rideCount)
        assertEquals(setOf("a"), store.entries.keys)
        val virtuell = at(1000.0, 3000.0)
        assertFalse(snapshot.index.isKnown(virtuell.lat, virtuell.lon))

        // Dazugekommen als Indoor-Fahrt: nichts zu tun, dieselbe Instanz.
        loader.loads.clear()
        val again = updateRiddenWays(snapshot, listOf(a, drinnen).map { it.toSummary() }, store, loader::load)
        assertSame(snapshot, again)
        assertTrue(loader.loads.isEmpty())
    }

    /** Zaehlt jeden Store-Zugriff — beim echten Store liest der erste davon die ganze Datei. */
    private class CountingStore(val inner: InMemoryRiddenWaysStore = InMemoryRiddenWaysStore()) : RiddenWaysStore {
        var accesses = 0
        override fun get(id: String): StoredRiddenWays? { accesses++; return inner.get(id) }
        override fun put(id: String, entry: StoredRiddenWays) { accesses++; inner.put(id, entry) }
        override fun retainAll(ids: Set<String>) { accesses++; inner.retainAll(ids) }
        override fun flush() { accesses++; inner.flush() }
    }

    @Test
    fun `eine nicht ladbare Tour wird erst nach einer Aenderung erneut versucht`() {
        val a = ride("a", road(0.0, 0.0, 1000.0, 0.0))
        val b = ride("b", road(0.0, 500.0, 1000.0, 500.0))
        val store = CountingStore()
        val missingB = CountingLoader(mapOf("a" to a))
        val first = updateRiddenWays(null, listOf(a, b).map { it.toSummary() }, store, missingB::load)
        assertEquals(1, first.rideCount)
        assertEquals(1, first.failedCount)
        assertFalse("b" in store.inner.entries)

        // Dieselbe kaputte Tour, unveraendert: derselbe Stand, kein Laden und
        // vor allem kein Store-Zugriff (der echte Store laese dafuer die
        // ganze Cache-Datei).
        missingB.loads.clear()
        val accesses = store.accesses
        repeat(3) {
            assertSame(first, updateRiddenWays(first, listOf(a, b).map { it.toSummary() }, store, missingB::load))
        }
        assertTrue(missingB.loads.isEmpty())
        assertEquals(accesses, store.accesses)

        // Kommt eine neue Tour dazu, wird die kaputte trotzdem nicht erneut geladen.
        val c = ride("c", road(0.0, 1000.0, 1000.0, 1000.0))
        val withC = CountingLoader(mapOf("a" to a, "c" to c))
        val added = updateRiddenWays(first, listOf(a, b, c).map { it.toSummary() }, store, withC::load)
        assertEquals(listOf("c"), withC.loads)
        assertEquals(2, added.rideCount)
        assertEquals(1, added.failedCount)

        // Neuer Fingerabdruck (die Tour wurde repariert/geaendert): erneut versucht.
        val b2 = ride("b", b.points, updatedAt = 2L)
        val withB = CountingLoader(mapOf("a" to a, "b" to b2, "c" to c))
        val repaired = updateRiddenWays(added, listOf(a, b2, c).map { it.toSummary() }, store, withB::load)
        assertEquals(listOf("b"), withB.loads)
        assertEquals(3, repaired.rideCount)
        assertEquals(0, repaired.failedCount)
        val bPoint = at(500.0, 500.0)
        assertTrue(repaired.index.isKnown(bPoint.lat, bPoint.lon))

        // Ohne vorigen Stand (Neustart) wird ebenfalls erneut versucht.
        val again = CountingLoader(mapOf("a" to a, "b" to b))
        val fresh = updateRiddenWays(null, listOf(a, b).map { it.toSummary() }, InMemoryRiddenWaysStore(), again::load)
        assertEquals(2, fresh.rideCount)
    }

    @Test
    fun `Abbruch je Tour - wirft, behaelt den alten Stand und raeumt nichts ab`() {
        val rides = (0 until 6).map { i -> ride("r$i", road(0.0, i * 300.0, 1500.0, i * 300.0)) }
        val store = InMemoryRiddenWaysStore()
        val loader = CountingLoader(rides.associateBy { it.id })
        val base = updateRiddenWays(null, rides.take(2).map { it.toSummary() }, store, loader::load)
        store.entries["weg"] = StoredRiddenWays(1L, 1, LongArray(1))
        val flushes = store.flushes

        // Nach zwei weiteren Touren abbrechen.
        loader.loads.clear()
        var asked = 0
        assertFailsWith<kotlin.coroutines.cancellation.CancellationException> {
            updateRiddenWays(base, rides.map { it.toSummary() }, store, loader::load, isCancelled = { asked++ >= 2 })
        }
        assertEquals(listOf("r2", "r3"), loader.loads)
        // Kein flush, kein retainAll: der fremde Eintrag ist noch da.
        assertEquals(flushes, store.flushes)
        assertTrue("weg" in store.entries)
        // Die gerechneten Eintraege sind je Tour vollstaendig.
        assertContentEquals(riddenWayCells(rides[2].points), store.entries.getValue("r2").cells)
        // Der alte Stand ist unberuehrt; der naechste Lauf rechnet korrekt
        // weiter und laedt die schon gerechneten Touren nicht erneut.
        assertEquals(2, base.rideCount)
        loader.loads.clear()
        val full = updateRiddenWays(base, rides.map { it.toSummary() }, store, loader::load)
        assertEquals(listOf("r4", "r5"), loader.loads)
        assertEquals(RiddenWaysIndex.fromTracks(rides.map { it.points }).size, full.index.size)
        assertFalse("weg" in store.entries)

        // Auch der Neuaufbau bricht ab.
        assertFailsWith<kotlin.coroutines.cancellation.CancellationException> {
            updateRiddenWays(null, rides.map { it.toSummary() }, store, loader::load, isCancelled = { true })
        }
    }

    @Test
    fun `viele neue Touren auf einmal ergeben denselben Index wie ein Neuaufbau`() {
        val rides = (0 until 40).map { i -> ride("r$i", road(i * 50.0, 0.0, i * 50.0 + 800.0, 600.0, seed = i)) }
        val store = InMemoryRiddenWaysStore()
        val loader = CountingLoader(rides.associateBy { it.id })
        val base = updateRiddenWays(null, rides.take(1).map { it.toSummary() }, store, loader::load)
        val added = updateRiddenWays(base, rides.map { it.toSummary() }, store, loader::load)
        val rebuilt = updateRiddenWays(null, rides.map { it.toSummary() }, InMemoryRiddenWaysStore(), loader::load)
        val expected = RiddenWaysIndex.fromTracks(rides.map { it.points })
        assertEquals(expected.size, added.index.size)
        assertEquals(expected.size, rebuilt.index.size)
        for (r in rides) {
            for (p in r.points) assertTrue(added.index.isKnown(p.lat, p.lon))
        }
        // Der Neuaufbau sortiert nicht die Cache-Eintraege selbst um (Fahrtreihenfolge bleibt).
        assertContentEquals(riddenWayCells(rides[0].points), store.entries.getValue("r0").cells)
    }
}
