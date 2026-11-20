package de.trailscape.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Tests des Fitness-Trends (`FitnessComparison.kt`). */
class FitnessTrendTest {

    private val today = LocalDate.of(2026, 11, 20)

    /** `createdAt` ist hier schlicht der Epochentag; `dateOf` macht daraus das Datum. */
    private val dateOf: (Long) -> LocalDate = { LocalDate.ofEpochDay(it) }

    private fun ride(
        daysAgo: Int,
        kmh: Double = 24.0,
        hr: Int? = 140,
        km: Double = 30.0,
        ascent: Double = 150.0,
        planned: Boolean = false,
        id: String = "r$daysAgo-$kmh-$km",
    ): Ride {
        val timeS = (km / kmh * 3600).toInt()
        return Ride(
            id = id,
            name = id,
            createdAt = today.minusDays(daysAgo.toLong()).toEpochDay(),
            stats = RideStats(
                distanceKm = km,
                ascentM = ascent,
                descentM = ascent,
                durationS = timeS,
                movingTimeS = timeS,
                avgSpeedKmh = kmh,
                avgHrBpm = hr,
            ),
            planned = planned,
        )
    }

    private fun trend(rides: List<Ride>) = fitnessComparison(rides, dateOf, today)

    private fun FitnessComparison.signal(kind: FitnessSignalKind) = signals.firstOrNull { it.kind == kind }

    /** Drei Fahrten im jüngeren (Tage 1–41) und drei im älteren Fenster (Tage 42–83). */
    private fun block(recentKmh: Double, beforeKmh: Double, hr: Int? = 140, km: Double = 30.0) =
        listOf(5, 15, 30).mapIndexed { i, d -> ride(d, recentKmh, hr, km, id = "n$i") } +
            listOf(50, 60, 75).mapIndexed { i, d -> ride(d, beforeKmh, hr, km, id = "v$i") }

    @Test
    fun noRidesIsNotEnoughData() {
        val t = trend(emptyList())
        assertEquals(FitnessVerdict.NOT_ENOUGH_DATA, t.verdict)
        assertEquals(emptyList(), t.signals)
        assertEquals(42, t.windowDays)
    }

    @Test
    fun efficiencyAloneFitter() {
        // gleiche Geschwindigkeit, Puls sinkt: 24 km/h bei 140 → 24 km/h bei 130
        val rides = listOf(5, 15, 30).map { ride(it, hr = 130, id = "n$it") } +
            listOf(50, 60, 75).map { ride(it, hr = 140, id = "v$it") }
        val t = trend(rides)
        val e = assertNotNull(t.signal(FitnessSignalKind.EFFICIENCY))
        // 24000/60/140 = 2.857; 24000/60/130 = 3.077 → +7.7 %
        assertEquals(8, e.changePct)
        assertEquals(400.0 / 130, e.recent, 1e-9)
        assertEquals(3, e.sampleRecent)
        assertEquals(FitnessVerdict.FITTER, t.verdict)
    }

    @Test
    fun paceAloneWithoutHeartRate() {
        val t = trend(block(recentKmh = 25.0, beforeKmh = 24.0, hr = null))
        assertNull(t.signal(FitnessSignalKind.EFFICIENCY))
        val p = assertNotNull(t.signal(FitnessSignalKind.PACE))
        assertEquals(4, p.changePct) // 4.17 %
        assertEquals(FitnessVerdict.FITTER, t.verdict)
    }

    @Test
    fun enduranceAlone() {
        // Zu wenig flache/vergleichbare Fahrten für PACE: je Fenster nur zwei Fahrten ≥ 15 km.
        val rides = listOf(
            ride(5, km = 82.0, hr = null), ride(20, km = 30.0, hr = null),
            ride(50, km = 61.0, hr = null), ride(70, km = 30.0, hr = null),
        )
        val t = trend(rides)
        assertEquals(listOf(FitnessSignalKind.ENDURANCE), t.signals.map { it.kind })
        val s = t.signals.single()
        assertEquals(82.0, s.recent, 1e-9)
        assertEquals(61.0, s.before, 1e-9)
        assertEquals(34, s.changePct)
        assertEquals(FitnessVerdict.FITTER, t.verdict)
    }

    @Test
    fun decliningAndStable() {
        assertEquals(FitnessVerdict.DECLINING, trend(block(22.0, 24.0, hr = null)).verdict)
        assertEquals(FitnessVerdict.STABLE, trend(block(24.0, 24.0, hr = null)).verdict)
    }

    @Test
    fun paceThresholdAtEdge() {
        // Die Schwelle gilt für den gerundeten Wert: +2,46 % → 2, nicht bewegt; +3,0 % → 3, bewegt.
        assertEquals(FitnessVerdict.FITTER, trend(block(24.72, 24.0, hr = null)).verdict)
        assertEquals(FitnessVerdict.STABLE, trend(block(24.59, 24.0, hr = null)).verdict)
        assertEquals(FitnessVerdict.DECLINING, trend(block(23.28, 24.0, hr = null)).verdict)
        assertEquals(FitnessVerdict.STABLE, trend(block(23.41, 24.0, hr = null)).verdict)
    }

    @Test
    fun enduranceThresholdAtEdge() {
        fun t(recentKm: Double) = trend(
            listOf(
                ride(5, km = recentKm, hr = null), ride(10, km = 20.0, hr = null),
                ride(50, km = 100.0, hr = null), ride(60, km = 20.0, hr = null),
            ),
        )
        assertEquals(FitnessVerdict.FITTER, t(110.0).verdict) // +10 %
        assertEquals(FitnessVerdict.STABLE, t(109.0).verdict) // +9 %
        // Eine kuerzere laengste Tour ist Gelegenheit, nicht Form: Das Signal
        // bewegt sich (−10 %), loest allein aber kein „schwaecher" aus.
        assertEquals(-10, assertNotNull(t(90.0).signal(FitnessSignalKind.ENDURANCE)).changePct)
        assertEquals(FitnessVerdict.STABLE, t(90.0).verdict)
        assertEquals(FitnessVerdict.STABLE, t(91.0).verdict)
    }

    @Test
    fun efficiencyOverridesContradictingPace() {
        // Schneller (26 vs 24), aber nur mit deutlich höherem Puls (160 vs 130): EFFICIENCY sinkt.
        val rides = listOf(5, 15, 30).map { ride(it, kmh = 26.0, hr = 160, id = "n$it") } +
            listOf(50, 60, 75).map { ride(it, kmh = 24.0, hr = 130, id = "v$it") }
        val t = trend(rides)
        assertEquals(8, assertNotNull(t.signal(FitnessSignalKind.PACE)).changePct)
        assertEquals(-12, assertNotNull(t.signal(FitnessSignalKind.EFFICIENCY)).changePct)
        assertEquals(FitnessVerdict.DECLINING, t.verdict)
    }

    @Test
    fun unmovedEfficiencyDecidesAlone() {
        // Puls und Tempo steigen im gleichen Verhältnis: EFFICIENCY ≈ 0, PACE +8 %.
        // Schneller bei entsprechend hoeherem Puls heisst haerter gefahren,
        // nicht fitter — und die Ueberschrift darf der Puls-Zeile nicht widersprechen.
        val rides = listOf(5, 15, 30).map { ride(it, kmh = 26.0, hr = 151, id = "n$it") } +
            listOf(50, 60, 75).map { ride(it, kmh = 24.0, hr = 140, id = "v$it") }
        val t = trend(rides)
        assertEquals(0, assertNotNull(t.signal(FitnessSignalKind.EFFICIENCY)).changePct)
        assertEquals(8, assertNotNull(t.signal(FitnessSignalKind.PACE)).changePct)
        assertEquals(FitnessVerdict.STABLE, t.verdict)

        // Dasselbe in die andere Richtung: langsamer bei entsprechend
        // niedrigerem Puls ist kein Formverlust.
        val slower = listOf(5, 15, 30).map { ride(it, kmh = 22.0, hr = 128, id = "n$it") } +
            listOf(50, 60, 75).map { ride(it, kmh = 24.0, hr = 140, id = "v$it") }
        assertEquals(FitnessVerdict.STABLE, trend(slower).verdict)
    }

    @Test
    fun shorterLongestRideDoesNotCancelAFasterPace() {
        // PACE +8 %, ENDURANCE −20 %, kein Puls: Die kuerzere laengste Tour zaehlt nicht dagegen.
        val rides = listOf(
            ride(5, kmh = 26.0, km = 20.0, hr = null), ride(15, kmh = 26.0, km = 20.0, hr = null),
            ride(30, kmh = 26.0, km = 20.0, hr = null),
            ride(50, kmh = 24.0, km = 25.0, hr = null), ride(60, kmh = 24.0, km = 20.0, hr = null),
            ride(75, kmh = 24.0, km = 20.0, hr = null),
        )
        val t = trend(rides)
        assertEquals(-20, assertNotNull(t.signal(FitnessSignalKind.ENDURANCE)).changePct)
        assertEquals(FitnessVerdict.FITTER, t.verdict)
    }

    @Test
    fun hillyRidesAreExcludedFromEfficiencyAndPace() {
        // 25 m/km zählt nicht, 20 m/km (genau die Grenze) schon.
        val hilly = block(24.0, 24.0).map { it.copy(stats = it.stats.copy(ascentM = 750.0)) } // 25 m/km
        val tHilly = trend(hilly)
        assertNull(tHilly.signal(FitnessSignalKind.EFFICIENCY))
        assertNull(tHilly.signal(FitnessSignalKind.PACE))
        assertNotNull(tHilly.signal(FitnessSignalKind.ENDURANCE))

        val edge = block(24.0, 24.0).map { it.copy(stats = it.stats.copy(ascentM = 600.0)) } // 20 m/km
        assertNotNull(trend(edge).signal(FitnessSignalKind.PACE))
    }

    @Test
    fun hillyRidesDoNotPullTheMedianDown() {
        val flat = block(24.0, 24.0, hr = null)
        val hillyExtra = listOf(ride(7, kmh = 14.0, ascent = 1200.0, id = "berg1"), ride(9, kmh = 14.0, ascent = 1200.0, id = "berg2"))
        val t = trend(flat + hillyExtra)
        assertEquals(0, assertNotNull(t.signal(FitnessSignalKind.PACE)).changePct)
        assertEquals(3, t.signal(FitnessSignalKind.PACE)!!.sampleRecent)
    }

    @Test
    fun ridesWithoutHeartRateGiveNoEfficiency() {
        val t = trend(block(24.0, 24.0, hr = null))
        assertNull(t.signal(FitnessSignalKind.EFFICIENCY))
        // Puls außerhalb 80–200 zählt auch nicht.
        assertNull(trend(block(24.0, 24.0, hr = 60)).signal(FitnessSignalKind.EFFICIENCY))
        assertNull(trend(block(24.0, 24.0, hr = 210)).signal(FitnessSignalKind.EFFICIENCY))
        assertNotNull(trend(block(24.0, 24.0, hr = 80)).signal(FitnessSignalKind.EFFICIENCY))
    }

    @Test
    fun tooFewRidesIsNotEnoughData() {
        val rides = listOf(ride(5), ride(15), ride(50), ride(60)) // je 2: für ENDURANCE reicht es, für PACE nicht
        val t = trend(rides.take(3)) // 2 recent, 1 before
        assertEquals(FitnessVerdict.NOT_ENOUGH_DATA, t.verdict)
        assertEquals(FitnessVerdict.NOT_ENOUGH_DATA, trend(listOf(ride(5), ride(10), ride(15))).verdict)
    }

    @Test
    fun shortOrSlowRidesAreNotComparable() {
        val short = block(24.0, 24.0).map { it.copy(stats = it.stats.copy(distanceKm = 14.9)) }
        assertEquals(FitnessVerdict.NOT_ENOUGH_DATA, trend(short).verdict)
        val brief = block(24.0, 24.0).map {
            it.copy(stats = it.stats.copy(movingTimeS = 2399, durationS = 2399))
        }
        // Zu kurze Fahrzeit zählt nicht für Tempo und Puls; die Länge (ENDURANCE) kennt kein Zeitminimum.
        assertEquals(listOf(FitnessSignalKind.ENDURANCE), trend(brief).signals.map { it.kind })
        val e = block(24.0, 24.0).map { it.copy(stats = it.stats.copy(avgSpeedKmh = 55.0)) }
        assertNull(trend(e).signal(FitnessSignalKind.PACE))
        val slow = block(24.0, 24.0).map { it.copy(stats = it.stats.copy(avgSpeedKmh = 7.9)) }
        assertNull(trend(slow).signal(FitnessSignalKind.PACE))
    }

    @Test
    fun durationIsFallbackForMovingTime() {
        val rides = block(24.0, 24.0).map { it.copy(stats = it.stats.copy(movingTimeS = null)) }
        assertNotNull(trend(rides).signal(FitnessSignalKind.PACE))
    }

    @Test
    fun futureRidesAreIgnored() {
        val future = listOf(ride(-1, id = "f1"), ride(-3, id = "f2"), ride(-5, id = "f3"))
        val t = trend(block(24.0, 24.0, hr = null) + future)
        assertEquals(3, assertNotNull(t.signal(FitnessSignalKind.PACE)).sampleRecent)
        // Nur Zukunft + älteres Fenster: nichts im jüngeren.
        val onlyFuture = trend(future + listOf(50, 60, 75).map { ride(it, id = "v$it") })
        assertEquals(FitnessVerdict.NOT_ENOUGH_DATA, onlyFuture.verdict)
    }

    @Test
    fun plannedRidesNeverCount() {
        val planned = listOf(5, 15, 30).map { ride(it, kmh = 30.0, planned = true, id = "p$it") }
        val base = listOf(50, 60, 75).map { ride(it, id = "v$it") }
        assertEquals(FitnessVerdict.NOT_ENOUGH_DATA, trend(planned + base).verdict)
    }

    @Test
    fun windowBoundaries() {
        // Tag 41 ist noch „jüngst", Tag 42 schon „davor"; Tag 83 noch „davor", Tag 84 zu alt.
        val rides = listOf(0, 20, 41).map { ride(it, id = "n$it") } +
            listOf(42, 60, 83).map { ride(it, id = "v$it") }
        val p = assertNotNull(trend(rides).signal(FitnessSignalKind.PACE))
        assertEquals(3, p.sampleRecent)
        assertEquals(3, p.sampleBefore)
        val tooOld = listOf(0, 20, 41).map { ride(it, id = "n$it") } +
            listOf(84, 90, 100).map { ride(it, id = "v$it") }
        assertEquals(FitnessVerdict.NOT_ENOUGH_DATA, trend(tooOld).verdict)
    }

    @Test
    fun medianOfEvenCountIsMeanOfMiddleTwo() {
        val rides = listOf(
            ride(3, kmh = 20.0, hr = null, id = "a"), ride(6, kmh = 22.0, hr = null, id = "b"),
            ride(9, kmh = 26.0, hr = null, id = "c"), ride(12, kmh = 40.0, hr = null, id = "d"),
            ride(50, kmh = 24.0, hr = null, id = "e"), ride(55, kmh = 24.0, hr = null, id = "f"),
            ride(60, kmh = 24.0, hr = null, id = "g"),
        )
        val p = assertNotNull(trend(rides).signal(FitnessSignalKind.PACE))
        assertEquals(24.0, p.recent, 1e-9) // (22 + 26) / 2
        assertEquals(0, p.changePct)
    }
}
