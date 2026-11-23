package de.trailscape.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.cos
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Tests der Rundenerkennung (`RouteMatch.kt`). */
class RouteMatchTest {

    private val lat0 = 48.5
    private val lon0 = 9.0
    private val mPerDegLat = 6371000.0 * PI / 180
    private val mPerDegLon = mPerDegLat * cos(lat0 * PI / 180)

    private fun p(x: Double, y: Double) = TrackPoint(lat = lat0 + y / mPerDegLat, lon = lon0 + x / mPerDegLon)

    /** Laeuft die Eckpunkte [corners] (in Metern) ab, ein Punkt je [stepM] Meter, optional mit Rauschen. */
    private fun walk(corners: List<Pair<Double, Double>>, stepM: Double = 100.0, jitterM: Double = 0.0, seed: Int = 1): List<TrackPoint> {
        val rnd = Random(seed)
        val out = ArrayList<TrackPoint>()
        out += p(corners[0].first, corners[0].second)
        for (i in 1 until corners.size) {
            val (x0, y0) = corners[i - 1]
            val (x1, y1) = corners[i]
            val len = kotlin.math.hypot(x1 - x0, y1 - y0)
            val n = (len / stepM).toInt().coerceAtLeast(1)
            for (k in 1..n) {
                val t = k.toDouble() / n
                val jx = if (jitterM > 0) (rnd.nextDouble() * 2 - 1) * jitterM else 0.0
                val jy = if (jitterM > 0) (rnd.nextDouble() * 2 - 1) * jitterM else 0.0
                out += p(x0 + (x1 - x0) * t + jx, y0 + (y1 - y0) * t + jy)
            }
        }
        return out
    }

    private fun square(side: Double, ox: Double = 0.0, oy: Double = 0.0) = listOf(
        ox to oy, (ox + side) to oy, (ox + side) to (oy + side), ox to (oy + side), ox to oy,
    )

    private fun sig(points: List<TrackPoint>) = assertNotNull(routeSignature(points))

    // ------------------------------------------------------------ Signatur

    @Test
    fun `gleiche Runde zweimal mit GPS-Rauschen ist dieselbe Strecke`() {
        val a = sig(walk(square(1000.0), jitterM = 3.0, seed = 1))
        val b = sig(walk(square(1000.0), jitterM = 3.0, seed = 2))
        assertTrue(sameRoute(a, b))
        assertEquals(4.0, a.distanceKm, 0.1)
    }

    @Test
    fun `gleiche Runde andersherum ist dieselbe Strecke`() {
        val a = sig(walk(square(1000.0)))
        val b = sig(walk(square(1000.0).reversed()))
        assertTrue(sameRoute(a, b))
        assertTrue(sameRoute(b, a))
    }

    @Test
    fun `Hinweg und Rueckweg einer Strecke sind nicht dieselbe Strecke`() {
        val way = listOf(0.0 to 0.0, 3000.0 to 0.0, 3000.0 to 3000.0)
        val out = sig(walk(way, jitterM = 3.0, seed = 1))
        val outAgain = sig(walk(way, jitterM = 3.0, seed = 2))
        val back = sig(walk(way.reversed(), jitterM = 3.0, seed = 3))
        assertTrue(sameRoute(out, outAgain))
        assertFalse(sameRoute(out, back))
        assertFalse(sameRoute(back, out))
    }

    @Test
    fun `gleiche Strecke mit Start ein paar Haeuser weiter bleibt dieselbe`() {
        val a = sig(walk(listOf(0.0 to 0.0, 6000.0 to 0.0)))
        val b = sig(walk(listOf(200.0 to 0.0, 6000.0 to 0.0)))
        assertTrue(sameRoute(a, b))
    }

    @Test
    fun `Runde mit nur halber Gemeinsamkeit ist nicht dieselbe Strecke`() {
        // Erste zwei Seiten gleich, danach (gleich lang) in die entgegengesetzte Richtung.
        val a = sig(walk(square(1000.0)))
        val b = sig(walk(listOf(0.0 to 0.0, 1000.0 to 0.0, 1000.0 to 1000.0, 2000.0 to 1000.0, 2000.0 to 0.0)))
        assertTrue(kotlin.math.abs(a.distanceKm - b.distanceKm) / b.distanceKm < 0.2)
        assertFalse(sameRoute(a, b))
    }

    @Test
    fun `gleiche Form aber 15 Prozent laenger ist nicht dieselbe Strecke`() {
        val a = sig(walk(square(1000.0)))
        val b = sig(walk(square(1150.0)))
        assertFalse(sameRoute(a, b))
        // Auch bei identischen Zellen scheitert allein die Laengenpruefung.
        assertFalse(sameRoute(a, a.copy(distanceKm = a.distanceKm * 1.15)))
        assertTrue(sameRoute(a, a.copy(distanceKm = a.distanceKm * 1.05)))
    }

    @Test
    fun `zu kurzer Track oder zu wenig Punkte ergibt keine Signatur`() {
        assertNull(routeSignature(emptyList()))
        assertNull(routeSignature(listOf(p(0.0, 0.0))))
        assertNull(routeSignature(walk(listOf(0.0 to 0.0, 1500.0 to 0.0))))
    }

    @Test
    fun `duenne GPS-Punkte lassen keine Zellen aus`() {
        val sparse = sig(walk(listOf(0.0 to 0.0, 3000.0 to 0.0), stepM = 3000.0))
        val dense = sig(walk(listOf(0.0 to 0.0, 3000.0 to 0.0), stepM = 20.0))
        assertContentEquals(dense.cells, sparse.cells)
        assertTrue(sparse.cells.size > 10)
    }

    @Test
    fun `Start- und Endzelle einer Runde`() {
        val a = sig(walk(square(1000.0)))
        assertEquals(a.startCell, a.endCell)
        assertTrue(a.startCell in a.cells)
    }

    @Test
    fun `Zellen sind sortiert und eindeutig, Signaturen vergleichen nach Inhalt`() {
        val a = sig(walk(square(1000.0)))
        assertTrue(a.cells.toList().zipWithNext().all { (x, y) -> x < y })
        val b = sig(walk(square(1000.0)))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertFalse(a == a.copy(cells = a.cells.copyOf(a.cells.size - 1)))
    }

    @Test
    fun `Schnittmenge per Merge entspricht der Mengenrechnung`() {
        // Zwei gegeneinander verschobene Strecken: Jaccard haendisch nachgerechnet.
        val a = sig(walk(listOf(0.0 to 0.0, 6000.0 to 0.0)))
        val b = sig(walk(listOf(1000.0 to 0.0, 7000.0 to 0.0)))
        val common = a.cells.toSet().intersect(b.cells.toSet()).size
        val jaccard = common.toDouble() / (a.cells.size + b.cells.size - common)
        assertEquals(jaccard >= ROUTE_MIN_JACCARD, sameRoute(a, b))
        assertTrue(common > 0)
    }

    @Test
    fun `Start mehrere Zellen weiter ist keine gleiche A-nach-B-Strecke mehr`() {
        // 1,2 km Versatz = ~6 Zellen: weit ueber ROUTE_ENDPOINT_TOLERANCE_CELLS.
        val a = sig(walk(listOf(0.0 to 0.0, 6000.0 to 0.0)))
        val b = sig(walk(listOf(1200.0 to 0.0, 7200.0 to 0.0)))
        assertFalse(sameRoute(a, b))
    }

    // ------------------------------------------------------------- Cache-Datei

    private fun encode(entries: Map<String, StoredRouteSignature>): ByteArray =
        ByteArrayOutputStream().also { writeRouteSignatureCache(entries, it) }.toByteArray()

    private fun decode(raw: String) = readRouteSignatureCache(ByteArrayInputStream(raw.toByteArray(Charsets.UTF_8)))

    @Test
    fun `Cache-Datei Rundlauf mit und ohne Signatur`() {
        val entries = linkedMapOf(
            "a" to StoredRouteSignature(5L, 40, sig(walk(square(1000.0)))),
            "b" to StoredRouteSignature(7L, 3, null),
        )
        val back = assertNotNull(readRouteSignatureCache(ByteArrayInputStream(encode(entries))))
        assertEquals(entries, back)
        assertEquals(listOf("a", "b"), back.keys.toList())
    }

    @Test
    fun `Cache-Datei traegt die Formatversion`() {
        val text = String(encode(emptyMap()), Charsets.UTF_8)
        assertTrue("\"version\":$ROUTE_SIGNATURE_CACHE_VERSION" in text, text)
    }

    @Test
    fun `Cache-Datei mit anderer oder fehlender Version gilt als leer`() {
        val entry = """{"id":"a","updatedAt":1,"pointCount":2,"signature":null}"""
        assertNotNull(decode("""{"version":$ROUTE_SIGNATURE_CACHE_VERSION,"entries":[$entry]}"""))
        assertNull(decode("""{"version":${ROUTE_SIGNATURE_CACHE_VERSION - 1},"entries":[$entry]}"""))
        assertNull(decode("""{"version":${ROUTE_SIGNATURE_CACHE_VERSION + 1},"entries":[$entry]}"""))
        assertNull(decode("""{"entries":[$entry]}"""))
    }

    @Test
    fun `kaputte Cache-Datei ergibt null`() {
        assertNull(decode("keine json"))
        assertNull(decode("[1,2]"))
        assertNull(decode(""))
        assertNull(decode("""{"version":$ROUTE_SIGNATURE_CACHE_VERSION}"""))
        assertNull(
            decode(
                """{"version":$ROUTE_SIGNATURE_CACHE_VERSION,"entries":[{"id":"a","updatedAt":1,"pointCount":2,""" +
                    """"signature":{"distanceKm":3,"startCell":1,"endCell":1,"cells":["x"]}}]}""",
            ),
        )
    }

    @Test
    fun `Eintrag mit unsortierten Zellen faellt weg, der Rest bleibt`() {
        val good = """{"id":"gut","updatedAt":1,"pointCount":2,"signature":{"distanceKm":3.0,"startCell":1,"endCell":1,"cells":[1,2,5]}}"""
        val unsorted = """{"id":"schief","updatedAt":1,"pointCount":2,"signature":{"distanceKm":3.0,"startCell":1,"endCell":1,"cells":[5,2]}}"""
        val dupes = """{"id":"doppelt","updatedAt":1,"pointCount":2,"signature":{"distanceKm":3.0,"startCell":1,"endCell":1,"cells":[2,2]}}"""
        val back = assertNotNull(decode("""{"version":$ROUTE_SIGNATURE_CACHE_VERSION,"entries":[$unsorted,$dupes,$good]}"""))
        assertEquals(setOf("gut"), back.keys)
    }

    // ------------------------------------------------------------- Collect

    private data class Info(
        override val id: String,
        override val createdAt: Long,
        override val updatedAt: Long = createdAt,
        override val stats: RideStats,
        override val planned: Boolean = false,
        override val pointCount: Int = 0,
        override val indoor: Boolean = false,
    ) : RideInfo {
        override val name: String get() = id
    }

    private fun stats(movingS: Int?, durationS: Int? = null) =
        RideStats(distanceKm = 4.0, ascentM = 0.0, descentM = 0.0, durationS = durationS, movingTimeS = movingS)

    private fun ride(info: Info, points: List<TrackPoint>) =
        Ride(id = info.id, name = info.id, createdAt = info.createdAt, stats = info.stats, points = points, updatedAt = info.updatedAt)

    @Test
    fun `collect laedt nur fehlende und veraltete Touren`() {
        val loop = walk(square(1000.0))
        val a = Info("a", 1, stats = stats(1000), pointCount = loop.size)
        val b = Info("b", 2, stats = stats(1000), pointCount = loop.size)
        val store = InMemoryRouteSignatureStore()
        val loaded = ArrayList<String>()
        val load: (String) -> Ride? = { id -> loaded += id; ride(if (id == "a") a else b, loop) }

        val first = collectRouteSignatures(listOf(a, b), store, load)
        assertEquals(setOf("a", "b"), first.keys)
        assertEquals(listOf("a", "b"), loaded)

        loaded.clear()
        collectRouteSignatures(listOf(a, b), store, load)
        assertTrue(loaded.isEmpty())

        loaded.clear()
        val bNewer = b.copy(updatedAt = 99)
        collectRouteSignatures(listOf(a, bNewer), store, load)
        assertEquals(listOf("b"), loaded)

        loaded.clear()
        val aMorePoints = a.copy(pointCount = a.pointCount + 1)
        collectRouteSignatures(listOf(aMorePoints, bNewer), store, load)
        assertEquals(listOf("a"), loaded)
    }

    @Test
    fun `collect ueberspringt geplante Touren und toleriert fehlende Dateien`() {
        val loop = walk(square(1000.0))
        val planned = Info("p", 1, stats = stats(1000), planned = true, pointCount = loop.size)
        val gone = Info("gone", 2, stats = stats(1000), pointCount = loop.size)
        val ok = Info("ok", 3, stats = stats(1000), pointCount = loop.size)
        val store = InMemoryRouteSignatureStore()
        val loaded = ArrayList<String>()

        val result = collectRouteSignatures(listOf(planned, gone, ok), store) { id ->
            loaded += id
            if (id == "gone") null else ride(ok, loop)
        }
        assertEquals(setOf("ok"), result.keys)
        assertEquals(listOf("gone", "ok"), loaded)
        assertNull(store.read("p"))
        assertNull(store.read("gone"))
    }

    @Test
    fun `collect ueberspringt Indoor-Fahrten ohne Laden und ohne Cache-Eintrag`() {
        val loop = walk(square(1000.0))
        val drinnen = Info("i", 1, stats = stats(1000), pointCount = loop.size, indoor = true)
        val ok = Info("ok", 2, stats = stats(1000), pointCount = loop.size)
        val store = InMemoryRouteSignatureStore()
        val loaded = ArrayList<String>()

        val result = collectRouteSignatures(listOf(drinnen, ok), store) { id ->
            loaded += id
            ride(if (id == "i") drinnen else ok, loop)
        }
        assertEquals(setOf("ok"), result.keys)
        assertEquals(listOf("ok"), loaded)
        assertNull(store.read("i"))
    }

    @Test
    fun `collect merkt sich Touren ohne Signatur und entfernt nichts`() {
        val shortTrack = walk(listOf(0.0 to 0.0, 500.0 to 0.0))
        val info = Info("s", 1, stats = stats(100), pointCount = shortTrack.size)
        val store = InMemoryRouteSignatureStore()
        store.write("old", StoredRouteSignature(1, 1, null))
        var calls = 0
        val load: (String) -> Ride? = { calls++; ride(info, shortTrack) }

        assertTrue(collectRouteSignatures(listOf(info), store, load).isEmpty())
        assertTrue(collectRouteSignatures(listOf(info), store, load).isEmpty())
        assertEquals(1, calls)
        assertEquals(StoredRouteSignature(info.updatedAt, info.pointCount, null), store.read("s"))
        assertNotNull(store.read("old"))
    }

    // ------------------------------------------------------------- Vergleich

    private val loopSig = sig(walk(square(1000.0)))
    private val otherSig = sig(walk(square(1000.0, ox = 20000.0)))

    private fun info(
        id: String,
        createdAt: Long,
        movingS: Int?,
        durationS: Int? = null,
        planned: Boolean = false,
        indoor: Boolean = false,
    ) = Info(id, createdAt, stats = stats(movingS, durationS), planned = planned, indoor = indoor)

    @Test
    fun `Rang, Vorgaenger, Bestzeit und Deltas`() {
        val rides = listOf(
            info("r1", 100, 1500),
            info("r2", 200, 1300),
            info("r3", 300, 1400),
            info("r4", 400, 1350),
        )
        val sigs = rides.associate { it.id to loopSig }

        val c = assertNotNull(compareOnRoute("r4", rides, sigs))
        assertEquals(listOf("r1", "r2", "r3", "r4"), c.efforts.map { it.rideId })
        assertEquals(4, c.count)
        assertEquals(2, c.rank)
        assertEquals("r3", c.previous?.rideId)
        assertEquals("r2", c.best.rideId)
        assertFalse(c.isBest)
        assertEquals(-50, c.deltaToPreviousS)
        assertEquals(50, c.deltaToBestOtherS)
        assertEquals(loopSig.distanceKm / (1350 / 3600.0), c.efforts.last().avgSpeedKmh, 1e-9)
    }

    @Test
    fun `neue Bestzeit hat negatives Delta zur besten anderen`() {
        val rides = listOf(info("r1", 100, 1500), info("r2", 200, 1300), info("r3", 300, 1200))
        val sigs = rides.associate { it.id to loopSig }
        val c = assertNotNull(compareOnRoute("r3", rides, sigs))
        assertEquals(1, c.rank)
        assertTrue(c.isBest)
        assertEquals(-100, c.deltaToBestOtherS)
        assertEquals(-100, c.deltaToPreviousS)
    }

    @Test
    fun `erste Fahrt hat keinen Vorgaenger`() {
        val rides = listOf(info("r1", 100, 1500), info("r2", 200, 1300))
        val sigs = rides.associate { it.id to loopSig }
        val c = assertNotNull(compareOnRoute("r1", rides, sigs))
        assertNull(c.previous)
        assertNull(c.deltaToPreviousS)
        assertEquals(2, c.rank)
        assertEquals(200, c.deltaToBestOtherS)
    }

    @Test
    fun `Gleichstand rankt die fruehere Tour besser`() {
        val rides = listOf(info("r1", 100, 1300), info("r2", 200, 1300))
        val sigs = rides.associate { it.id to loopSig }
        val second = assertNotNull(compareOnRoute("r2", rides, sigs))
        assertEquals(2, second.rank)
        assertEquals("r1", second.best.rideId)
        assertEquals(0, second.deltaToBestOtherS)
        val first = assertNotNull(compareOnRoute("r1", rides, sigs))
        assertEquals(1, first.rank)
        assertTrue(first.isBest)
        assertEquals(0, first.deltaToBestOtherS)
    }

    @Test
    fun `einzelne Tour oder nur andere Strecken ergibt null`() {
        val rides = listOf(info("r1", 100, 1500), info("r2", 200, 1300))
        assertNull(compareOnRoute("r1", rides.take(1), mapOf("r1" to loopSig)))
        assertNull(compareOnRoute("r1", rides, mapOf("r1" to loopSig, "r2" to otherSig)))
        assertNull(compareOnRoute("r1", rides, mapOf("r2" to loopSig)))
        assertNull(compareOnRoute("nope", rides, mapOf("r1" to loopSig, "r2" to loopSig)))
    }

    @Test
    fun `geplante Touren und Touren ohne Zeit zaehlen nicht`() {
        val rides = listOf(
            info("r1", 100, 1500),
            info("noTime", 150, null),
            info("zero", 160, 0),
            info("planned", 170, 900, planned = true),
            info("dur", 180, null, durationS = 1400),
            info("r2", 200, 1300),
        )
        val sigs = rides.associate { it.id to loopSig }
        val c = assertNotNull(compareOnRoute("r2", rides, sigs))
        // „dur" hat nur eine Gesamtdauer, keine Bewegungszeit: zaehlt nicht (Pausen waeren mit drin).
        assertEquals(listOf("r1", "r2"), c.efforts.map { it.rideId })
        assertEquals(1, c.rank)
        assertNull(compareOnRoute("noTime", rides, sigs))
        assertNull(compareOnRoute("dur", rides, sigs))
        assertNull(compareOnRoute("planned", rides, sigs))
    }

    @Test
    fun `Indoor-Fahrten zaehlen im Rundenvergleich nicht`() {
        val rides = listOf(
            info("r1", 100, 1500),
            info("indoor", 150, 900, indoor = true),
            info("r2", 200, 1300),
        )
        val sigs = rides.associate { it.id to loopSig }
        val c = assertNotNull(compareOnRoute("r2", rides, sigs))
        assertEquals(listOf("r1", "r2"), c.efforts.map { it.rideId })
        assertEquals("r1", c.previous?.rideId)
        assertNull(compareOnRoute("indoor", rides, sigs))
    }
}
