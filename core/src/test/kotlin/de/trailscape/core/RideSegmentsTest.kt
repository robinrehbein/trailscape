package de.trailscape.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Tests fuer die lokalen Segment-Bestleistungen (`RideSegments.kt`):
 * Anstiegserkennung, Matching ueber Touren hinweg, Runden, Platzierung,
 * JSON-Roundtrip und inkrementelle Pflege der Registry.
 *
 * Die synthetischen Spuren laufen entlang des Laengengrads auf 47° Breite;
 * ein Stuetzpunkt alle ~25 m. Damit sind Distanzen und Zeiten der erwarteten
 * Befahrungen von Hand nachrechenbar.
 */
class RideSegmentsTest {

    private companion object {
        const val BASE_LAT = 47.0
        const val BASE_LON = 13.0

        /** ~25 m in Grad Laenge auf 47° Breite. */
        const val STEP_DEG = 0.00032929

        const val T0 = 1_700_000_000_000L
    }

    /**
     * Baut eine Spur aus (latOffset, lonIndex, ele)-Schritten: Punkt `k`
     * liegt bei `BASE_LON + lonIndex * STEP_DEG`, Zeit `T0 + k * dtMs`.
     */
    private fun track(
        steps: List<Triple<Double, Double, Double>>,
        dtMs: Long?,
        startMs: Long = T0,
        hr: Int? = null,
    ): List<TrackPoint> = steps.mapIndexed { k, (latOff, lonIdx, ele) ->
        TrackPoint(
            lat = BASE_LAT + latOff,
            lon = BASE_LON + lonIdx * STEP_DEG,
            ele = ele,
            time = dtMs?.let { startMs + k * it },
            hr = hr,
        )
    }

    /**
     * Standardprofil: 40 Schritte flach (500 m), 48 Schritte Anstieg (+2 m je
     * Schritt ≈ 8 %), 40 Schritte flach oben — insgesamt ~3,2 km, 96 Hm.
     */
    private fun climbProfile(
        jitterLat: Double = 0.0,
        noise: (Int) -> Double = { 0.0 },
    ): List<Triple<Double, Double, Double>> = (0 until 128).map { k ->
        val ele = when {
            k <= 40 -> 500.0
            k < 88 -> 500.0 + 2.0 * (k - 40)
            else -> 596.0
        }
        Triple(jitterLat, k.toDouble(), ele + noise(k))
    }

    private fun climbRide(
        id: String,
        createdAt: Long,
        dtMs: Long? = 5000L,
        jitterLat: Double = 0.0,
        noise: (Int) -> Double = { 0.0 },
        hr: Int? = null,
    ): Ride {
        val points = track(climbProfile(jitterLat, noise), dtMs, startMs = createdAt, hr = hr)
        return Ride(id = id, name = id, createdAt = createdAt, stats = computeStats(points), points = points)
    }

    private fun flatRide(id: String, createdAt: Long): Ride {
        val points = track((0 until 128).map { Triple(0.0, it.toDouble(), 500.0) }, 5000L, startMs = createdAt)
        return Ride(id = id, name = id, createdAt = createdAt, stats = computeStats(points), points = points)
    }

    // ----------------------------------------------------------- Erkennung

    @Test
    fun `flache Tour liefert keine Kandidaten`() {
        assertEquals(emptyList(), detectSegmentCandidates(flatRide("flat", T0)))
    }

    @Test
    fun `klarer Anstieg liefert genau einen Kandidaten mit plausiblen Werten`() {
        val candidates = detectSegmentCandidates(climbRide("r1", T0))

        assertEquals(1, candidates.size)
        val c = candidates.first()
        assertTrue(c.ascentM in 80.0..100.0, "Hoehengewinn unplausibel: ${c.ascentM}")
        assertTrue(c.distanceM in 1000.0..1700.0, "Laenge unplausibel: ${c.distanceM}")
        // 48 Anstiegs-Schritte x 5 s = 240 s, plus geglaettete Raender.
        assertTrue(abs(c.timeS - 250) <= 40, "Zeit unplausibel: ${c.timeS}")
        assertEquals("r1", c.rideId)
    }

    @Test
    fun `Hoehenrauschen zerlegt den Anstieg nicht`() {
        // +-1,5 m alternierend — typisches Barometer-/GPS-Zappeln.
        val noisy = detectSegmentCandidates(
            climbRide("r1", T0, noise = { k -> if (k % 2 == 0) 1.5 else -1.5 }),
        )

        assertEquals(1, noisy.size)
    }

    @Test
    fun `verrauschte Flachtour liefert weiterhin nichts`() {
        val points = track(
            (0 until 128).map { k -> Triple(0.0, k.toDouble(), 500.0 + if (k % 2 == 0) 1.5 else -1.5) },
            5000L,
        )
        val ride = Ride(id = "f", name = "f", createdAt = T0, stats = computeStats(points), points = points)

        assertEquals(emptyList(), detectSegmentCandidates(ride))
    }

    @Test
    fun `Befahrung ohne Zeitstempel wird verworfen`() {
        assertEquals(emptyList(), detectSegmentCandidates(climbRide("r1", T0, dtMs = null)))
    }

    @Test
    fun `zu flache lange Rampe ist kein Kandidat`() {
        // 3,2 km mit 40 Hm ≈ 1,3 % — Gewinn und Laenge reichen, die Steigung nicht.
        val points = track(
            (0 until 128).map { k -> Triple(0.0, k.toDouble(), 500.0 + k * 40.0 / 127.0) },
            5000L,
        )
        val ride = Ride(id = "ramp", name = "ramp", createdAt = T0, stats = computeStats(points), points = points)

        assertEquals(emptyList(), detectSegmentCandidates(ride))
    }

    // ------------------------------------------------------------- Matching

    @Test
    fun `zwei Touren ueber denselben Anstieg ergeben ein Segment mit zwei Efforts`() {
        val r1 = climbRide("r1", T0, dtMs = 5000L)
        val r2 = climbRide("r2", T0 + 86_400_000L, dtMs = 4000L, jitterLat = 0.00004)

        val after1 = updateSegmentRegistry(SegmentRegistry.EMPTY, r1)
        assertEquals(0, after1.registry.segments.size)
        assertEquals(1, after1.registry.candidates.size)
        assertEquals(emptyList(), after1.newBests)

        val after2 = updateSegmentRegistry(after1.registry, r2)
        assertEquals(1, after2.registry.segments.size)
        assertEquals(0, after2.registry.candidates.size)
        val segment = after2.registry.segments.first()
        assertEquals(listOf("r1", "r2"), segment.efforts.map { it.rideId })
        assertTrue(segment.name.startsWith("Anstieg "), "Namensvorschlag fehlt: ${segment.name}")
    }

    @Test
    fun `schnellere zweite Tour meldet eine neue Bestzeit`() {
        val r1 = climbRide("r1", T0, dtMs = 5000L)
        val r2 = climbRide("r2", T0 + 86_400_000L, dtMs = 4000L, jitterLat = 0.00004)

        val registry = updateSegmentRegistry(SegmentRegistry.EMPTY, r1).registry
        val update = updateSegmentRegistry(registry, r2)

        assertEquals(1, update.newBests.size)
        val best = update.newBests.first()
        // 48 Schritte x 1 s Differenz = ~48 s schneller (Raender geglaettet).
        assertTrue(best.improvementS in 30..70, "Verbesserung unplausibel: ${best.improvementS}")
        assertEquals(update.registry.segments.first().name, best.segmentName)
    }

    @Test
    fun `langsamere zweite Tour meldet keine Bestzeit`() {
        val r1 = climbRide("r1", T0, dtMs = 4000L)
        val r2 = climbRide("r2", T0 + 86_400_000L, dtMs = 5000L, jitterLat = 0.00004)

        val registry = updateSegmentRegistry(SegmentRegistry.EMPTY, r1).registry
        val update = updateSegmentRegistry(registry, r2)

        assertEquals(1, update.registry.segments.size)
        assertEquals(emptyList(), update.newBests)
    }

    @Test
    fun `Gegenrichtung wird nicht demselben Segment zugeordnet`() {
        val r1 = climbRide("r1", T0)
        // Dieselben Zellen, aber rueckwaerts durchfahren (Hoehe steigt in
        // Gegenrichtung — geometrisch derselbe Korridor, Start und Ende
        // vertauscht).
        val reversedSteps = (0 until 128).map { k ->
            val ele = when {
                k <= 40 -> 500.0
                k < 88 -> 500.0 + 2.0 * (k - 40)
                else -> 596.0
            }
            Triple(0.0, (127 - k).toDouble(), ele)
        }
        val points = track(reversedSteps, 5000L, startMs = T0 + 86_400_000L)
        val r2 = Ride(id = "r2", name = "r2", createdAt = T0 + 86_400_000L, stats = computeStats(points), points = points)

        val registry = updateSegmentRegistry(SegmentRegistry.EMPTY, r1).registry
        val after = updateSegmentRegistry(registry, r2).registry

        assertEquals(0, after.segments.size)
        assertEquals(2, after.candidates.size)
    }

    @Test
    fun `abweichender Pfad ist kein Match`() {
        val r1 = climbRide("r1", T0)
        // Parallelstrasse ~220 m weiter noerdlich.
        val r2 = climbRide("r2", T0 + 86_400_000L, jitterLat = 0.002)

        val registry = updateSegmentRegistry(SegmentRegistry.EMPTY, r1).registry
        val after = updateSegmentRegistry(registry, r2).registry

        assertEquals(0, after.segments.size)
        assertEquals(2, after.candidates.size)
    }

    // --------------------------------------------------------------- Runden

    /**
     * Tour mit zwei Runden ueber denselben Anstieg: hoch, auf einer
     * Parallelspur (~110 m noerdlich) zurueck hinunter, noch einmal hoch.
     */
    private fun twoLapRide(id: String, createdAt: Long, dtMs: Long = 5000L): Ride {
        val up = climbProfile()
        val down = (0 until 128).map { k ->
            val ele = when {
                k <= 40 -> 596.0
                k < 88 -> 596.0 - 2.0 * (k - 40)
                else -> 500.0
            }
            Triple(0.001, (127 - k).toDouble(), ele)
        }
        val upAgain = climbProfile(jitterLat = 0.00004)
        val points = track(up + down + upAgain, dtMs, startMs = createdAt)
        return Ride(id = id, name = id, createdAt = createdAt, stats = computeStats(points), points = points)
    }

    @Test
    fun `zwei Runden in einer Tour bleiben zwei Kandidaten und werden zwei Efforts`() {
        val laps = twoLapRide("laps", T0)
        val after1 = updateSegmentRegistry(SegmentRegistry.EMPTY, laps)

        // Zwei Runden EINER Tour gruenden noch kein Segment.
        assertEquals(0, after1.registry.segments.size)
        assertEquals(2, after1.registry.candidates.size)

        // Eine zweite Tour ueber den Anstieg etabliert das Segment — mit
        // beiden Runden der ersten Tour als Efforts.
        val r2 = climbRide("r2", T0 + 86_400_000L, dtMs = 4000L, jitterLat = 0.00002)
        val after2 = updateSegmentRegistry(after1.registry, r2)

        assertEquals(1, after2.registry.segments.size)
        assertEquals(0, after2.registry.candidates.size)
        val segment = after2.registry.segments.first()
        assertEquals(3, segment.efforts.size)
        assertEquals(2, segment.efforts.count { it.rideId == "laps" })

        val lapViews = segmentEffortsForRide(after2.registry, "laps")
        assertEquals(2, lapViews.size)
        assertTrue(lapViews[0].startedAt < lapViews[1].startedAt)
    }

    // ------------------------------------------------- Bestzeit und Platz

    @Test
    fun `Platzierung, Delta und Neue-Bestzeit-Kennzeichen stimmen`() {
        val r1 = climbRide("r1", T0, dtMs = 5000L)
        val r2 = climbRide("r2", T0 + 86_400_000L, dtMs = 4000L, jitterLat = 0.00004)
        val r3 = climbRide("r3", T0 + 2 * 86_400_000L, dtMs = 4500L, jitterLat = 0.00002)

        var registry = updateSegmentRegistry(SegmentRegistry.EMPTY, r1).registry
        registry = updateSegmentRegistry(registry, r2).registry
        registry = updateSegmentRegistry(registry, r3).registry

        assertEquals(1, registry.segments.size)
        val v1 = segmentEffortsForRide(registry, "r1").single()
        val v2 = segmentEffortsForRide(registry, "r2").single()
        val v3 = segmentEffortsForRide(registry, "r3").single()

        assertEquals(3, v1.effortCount)
        // r2 ist die Bestzeit, r3 Zweiter, r1 Dritter.
        assertEquals(1, v2.rank)
        assertEquals(2, v3.rank)
        assertEquals(3, v1.rank)
        assertEquals(v2.timeS, v1.bestTimeS)
        assertEquals(0, v2.deltaToBestS)
        assertEquals(v3.timeS - v2.timeS, v3.deltaToBestS)

        // r1 war die erste Befahrung — keine „neue Bestzeit"; r2 unterbot
        // r1; r3 blieb ueber der Bestzeit von r2.
        assertEquals(false, v1.isNewBest)
        assertEquals(true, v2.isNewBest)
        assertEquals(false, v3.isNewBest)
    }

    @Test
    fun `Durchschnittspuls der Befahrung wird uebernommen`() {
        val r1 = climbRide("r1", T0, hr = 150)
        val candidate = detectSegmentCandidates(r1).single()

        assertEquals(150, candidate.avgHr)
    }

    // -------------------------------------------------------- JSON-Roundtrip

    @Test
    fun `Registry uebersteht den JSON-Roundtrip verlustfrei`() {
        val laps = twoLapRide("laps", T0)
        val r2 = climbRide("r2", T0 + 86_400_000L, dtMs = 4000L, jitterLat = 0.00002, hr = 148)
        val flat = flatRide("flat", T0 + 3 * 86_400_000L)

        var registry = updateSegmentRegistry(SegmentRegistry.EMPTY, laps).registry
        registry = updateSegmentRegistry(registry, r2).registry
        registry = updateSegmentRegistry(registry, flat).registry
        // Zusaetzlich ein wartender Kandidat im Bestand.
        registry = updateSegmentRegistry(registry, climbRide("solo", T0 + 4 * 86_400_000L, jitterLat = 0.003)).registry

        val json = registry.toJson().toString()
        val decoded = SegmentRegistry.fromJson(Json.parseToJsonElement(json) as JsonObject)

        assertEquals(registry, decoded)
        assertTrue(decoded.segments.first().path.size >= 12)
        assertEquals(registry.processed, decoded.processed)
    }

    // -------------------------------------------- Inkrementell und Loeschung

    @Test
    fun `geaenderte Tour ersetzt ihre Efforts statt sie zu verdoppeln`() {
        val r1 = climbRide("r1", T0)
        val r2 = climbRide("r2", T0 + 86_400_000L, jitterLat = 0.00004)
        var registry = updateSegmentRegistry(SegmentRegistry.EMPTY, r1).registry
        registry = updateSegmentRegistry(registry, r2).registry

        // HF-Anreicherung: gleiche Punkte, neues updatedAt.
        val r2b = r2.copy(updatedAt = r2.updatedAt + 1000L)
        val after = updateSegmentRegistry(registry, r2b).registry

        assertEquals(1, after.segments.size)
        assertEquals(2, after.segments.first().efforts.size)
        assertEquals(1, after.segments.first().efforts.count { it.rideId == "r2" })
        assertEquals(r2b.updatedAt, after.processed["r2"])
    }

    @Test
    fun `ridesNeedingSegmentUpdate erkennt neue und geaenderte Touren`() {
        val r1 = climbRide("r1", T0)
        val registry = updateSegmentRegistry(SegmentRegistry.EMPTY, r1).registry

        val unchanged = r1.toSummary()
        val changed = r1.copy(updatedAt = r1.updatedAt + 5).toSummary()
        val fresh = climbRide("r2", T0 + 1000L).toSummary()
        val planned = Ride(
            id = "plan",
            name = "plan",
            createdAt = T0,
            stats = RideStats.empty,
            points = climbRide("x", T0).points,
            planned = true,
        ).toSummary()

        assertEquals(emptyList(), ridesNeedingSegmentUpdate(registry, listOf(unchanged)))
        assertEquals(listOf("r1"), ridesNeedingSegmentUpdate(registry, listOf(changed)).map { it.id })
        assertEquals(listOf("r2"), ridesNeedingSegmentUpdate(registry, listOf(unchanged, fresh, planned)).map { it.id })
    }

    @Test
    fun `Loeschung entfernt Efforts und leere Segmente`() {
        val r1 = climbRide("r1", T0)
        val r2 = climbRide("r2", T0 + 86_400_000L, jitterLat = 0.00004)
        var registry = updateSegmentRegistry(SegmentRegistry.EMPTY, r1).registry
        registry = updateSegmentRegistry(registry, r2).registry

        // r1 geloescht: Segment bleibt mit dem verbliebenen Effort bestehen.
        val withoutR1 = retainRidesInSegmentRegistry(registry, setOf("r2"))
        assertEquals(1, withoutR1.segments.size)
        assertEquals(listOf("r2"), withoutR1.segments.first().efforts.map { it.rideId })
        assertEquals(setOf("r2"), withoutR1.processed.keys)

        // Beide geloescht: nichts bleibt uebrig.
        val empty = retainRidesInSegmentRegistry(registry, emptySet())
        assertEquals(0, empty.segments.size)
        assertEquals(0, empty.candidates.size)
        assertEquals(emptyMap(), empty.processed)

        // Nichts geloescht: dieselbe Instanz (billiger „nichts zu speichern"-Check).
        assertTrue(retainRidesInSegmentRegistry(registry, setOf("r1", "r2")) === registry)
    }

    @Test
    fun `IDs der Segmente sind deterministisch und eindeutig`() {
        val r1 = climbRide("r1", T0)
        val r2 = climbRide("r2", T0 + 86_400_000L, jitterLat = 0.00004)

        val a = updateSegmentRegistry(updateSegmentRegistry(SegmentRegistry.EMPTY, r1).registry, r2).registry
        val b = updateSegmentRegistry(updateSegmentRegistry(SegmentRegistry.EMPTY, r1).registry, r2).registry

        assertEquals(a.segments.single().id, b.segments.single().id)
        assertNotEquals("", a.segments.single().id)
    }
}
