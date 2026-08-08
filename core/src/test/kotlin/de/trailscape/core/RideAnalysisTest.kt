package de.trailscape.core

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Portierung der Gruppen `Pe:Hr-Entkopplung`, `VO2max` und
 * `Formulierungen ohne Overclaim` aus `test/training_load_test.dart`.
 */
class RideAnalysisTest {

    /** Flache, gleichmaessige Tour ueber [seconds] Sekunden mit HF-Drift. */
    private fun flatRide(
        seconds: Int,
        hrFirst: Int,
        hrSecond: Int,
        gradeTan: Double = 0.0,
    ): PhysicsEstimate {
        val points = track(
            pointCount = seconds + 1,
            speedMs = 5.0,
            stepS = 1,
            gradeTan = gradeTan,
            startEle = 100.0,
            hr = { i -> if (i <= seconds / 2) hrFirst else hrSecond },
        )
        return computePhysicsEstimate(buildRideSeries(points, refProfile), refProfile)
    }

    // --- group('Pe:Hr-Entkopplung') ---

    @Test
    fun `zu kurze Tour - Gate greift`() {
        val d = computeDecoupling(
            flatRide(seconds = 1200, hrFirst = 130, hrSecond = 140),
            refProfile,
        )
        assertFalse(d.available)
        assertTrue(d.unavailableReason!!.contains("60 Minuten"))
        assertNull(d.decouplingPercent)
    }

    @Test
    fun `zu hohe Intensitaet - Gate greift`() {
        val d = computeDecoupling(
            flatRide(seconds = 3700, hrFirst = 175, hrSecond = 180),
            refProfile,
        )
        assertFalse(d.available)
        assertTrue(d.unavailableReason!!.contains("aeroben"))
    }

    @Test
    fun `zu niedrige Intensitaet - Gate greift`() {
        val d = computeDecoupling(
            flatRide(seconds = 3700, hrFirst = 100, hrSecond = 105),
            refProfile,
        )
        assertFalse(d.available)
    }

    @Test
    fun `ohne Leistungsmodell keine Entkopplung`() {
        val d = computeDecoupling(
            PhysicsEstimate.unavailable("kein Höhenprofil"),
            refProfile,
        )
        assertFalse(d.available)
        assertEquals("kein Höhenprofil", d.unavailableReason)
    }

    @Test
    fun `qualifizierende Tour - Entkopplung entspricht der HF-Drift`() {
        val d = computeDecoupling(
            flatRide(seconds = 3700, hrFirst = 130, hrSecond = 140),
            refProfile,
        )
        assertTrue(d.available)
        // Gleiche Leistung, HF +10 bpm -> ≈ (1/130 − 1/140)/(1/130) = 7,14 %
        assertEquals(7.14, d.decouplingPercent!!, 0.6)
        assertEquals("aerobe Ausdauer im Aufbau", d.rating)
        assertTrue(d.efFirst!! > d.efSecond!!)
        assertEquals(Confidence.MEDIUM, d.confidence)
    }

    @Test
    fun `konstante HF ergibt nahezu keine Entkopplung`() {
        val d = computeDecoupling(
            flatRide(seconds = 3700, hrFirst = 135, hrSecond = 135),
            refProfile,
        )
        assertTrue(d.available)
        assertTrue(abs(d.decouplingPercent!!) < 2)
        assertEquals("gute aerobe Ausdauer", d.rating)
    }

    @Test
    fun `fehlende HF in der zweiten Haelfte - Abdeckungs-Gate greift`() {
        val points = track(
            pointCount = 3701,
            speedMs = 5.0,
            stepS = 1,
            startEle = 100.0,
            hr = { i -> if (i < 3000) 135 else null },
        )
        val d = computeDecoupling(
            computePhysicsEstimate(buildRideSeries(points, refProfile), refProfile),
            refProfile,
        )
        assertFalse(d.available)
        assertTrue(d.unavailableReason!!.contains("90 %"))
    }

    @Test
    fun `Trend ist der Median der letzten fuenf Werte`() {
        assertNull(decouplingTrend(emptyList()))
        assertEquals(4.0, decouplingTrend(listOf(3.0, 4.0, 5.0))!!, 1e-9)
        assertEquals(
            3.0,
            decouplingTrend(listOf(100.0, 100.0, 1.0, 2.0, 3.0, 4.0, 5.0))!!,
            1e-9,
        )
    }

    @Test
    fun `Bewertungstext nach Friel-Schwellen`() {
        // ueber die oeffentliche API: Werte werden ueber das Rating abgebildet
        val good = computeDecoupling(
            flatRide(seconds = 3700, hrFirst = 135, hrSecond = 135),
            refProfile,
        )
        assertEquals("gute aerobe Ausdauer", good.rating)
        val drifting = computeDecoupling(
            flatRide(seconds = 3700, hrFirst = 125, hrSecond = 145),
            refProfile,
        )
        assertEquals("mehr Grundlagenarbeit sinnvoll", drifting.rating)
    }

    // --- group('VO2max') ---

    @Test
    fun `Uth-Formel mit plus minus 15-Prozent-Band`() {
        val e = estimateVo2MaxFromHrRatio(refProfile)
        assertTrue(e.available)
        assertEquals(58.14, e.value!!, 1e-9)
        assertEquals(58.14 * 0.85, e.lower!!, 1e-9)
        assertEquals(58.14 * 1.15, e.upper!!, 1e-9)
        assertEquals(Vo2MaxMethod.UTH_RATIO, e.method)
        assertEquals(Confidence.LOW, e.confidence)
        assertTrue(e.text.contains("geschätzt"))
        assertTrue(e.text.contains("–"))
    }

    @Test
    fun `ACSM-Regression bei perfektem Zusammenhang`() {
        // P = 2·(HF − 100) + 60, VO2 = 10,8·P/75 + 7 = 0,144·P + 7
        // -> Steigung 0,288 ml/kg/min pro bpm, Achsenabschnitt −13,16
        val segments = (110..160 step 10).map { hr ->
            SteadySegment(
                avgPowerW = (2 * (hr - 100) + 60).toDouble(),
                avgHr = hr.toDouble(),
                durationS = 360.0,
            )
        }
        val e = estimateVo2MaxFromSegments(segments, refProfile)
        assertTrue(e.available)
        assertEquals(1.0, e.r2!!, 1e-9)
        assertEquals(6, e.segmentCount)
        assertEquals(50.0, e.hrSpanBpm!!, 1e-9)
        assertEquals(41.56, e.value!!, 1e-6)
        assertEquals(41.56 * 0.9, e.lower!!, 1e-6)
        assertEquals(Vo2MaxMethod.REGRESSION, e.method)
        assertEquals(Confidence.MEDIUM, e.confidence)
    }

    @Test
    fun `Gate - weniger als 6 Segmente`() {
        val segments = (110..150 step 10).map { hr ->
            SteadySegment(
                avgPowerW = (2 * (hr - 100) + 60).toDouble(),
                avgHr = hr.toDouble(),
                durationS = 360.0,
            )
        }
        val e = estimateVo2MaxFromSegments(segments, refProfile)
        assertFalse(e.available)
        assertTrue(e.unavailableReason!!.contains("6"))
    }

    @Test
    fun `Gate - HF-Spanne unter 25 bpm`() {
        val segments = (0 until 8).map { i ->
            SteadySegment(
                avgPowerW = 100.0 + i,
                avgHr = 140.0 + i,
                durationS = 360.0,
            )
        }
        val e = estimateVo2MaxFromSegments(segments, refProfile)
        assertFalse(e.available)
        assertTrue(e.unavailableReason!!.contains("Spanne"))
    }

    @Test
    fun `Gate - r2 unter 0,80`() {
        val noise = listOf(0.0, 60.0, -50.0, 55.0, -45.0, 40.0, -60.0, 50.0)
        val segments = (0 until 8).map { i ->
            SteadySegment(
                avgPowerW = (110.0 + noise[i]).coerceIn(50.0, 200.0),
                avgHr = 120.0 + i * 5,
                durationS = 360.0,
            )
        }
        val e = estimateVo2MaxFromSegments(segments, refProfile)
        assertFalse(e.available)
        assertTrue(e.unavailableReason!!.contains("r²"))
    }

    @Test
    fun `Prioritaet - Plattform vor Regression vor Uth`() {
        val segments = (110..160 step 10).map { hr ->
            SteadySegment(
                avgPowerW = (2 * (hr - 100) + 60).toDouble(),
                avgHr = hr.toDouble(),
                durationS = 360.0,
            )
        }
        assertEquals(
            Vo2MaxMethod.PLATTFORM,
            estimateVo2Max(profile = refProfile, platformValue = 52.0).method,
        )
        assertEquals(
            Vo2MaxMethod.REGRESSION,
            estimateVo2Max(profile = refProfile, segments = segments).method,
        )
        assertEquals(Vo2MaxMethod.UTH_RATIO, estimateVo2Max(profile = refProfile).method)
    }

    @Test
    fun `ohne Gewicht keine Regression`() {
        val e = estimateVo2MaxFromSegments(
            emptyList(),
            refProfile.copyWith(weightKg = 0.0),
        )
        assertFalse(e.available)
        assertTrue(e.text.contains("Gewicht"))
    }

    @Test
    fun `Segmente ausserhalb 50 bis 200 W werden verworfen`() {
        val segments = (110..160 step 10).map { hr ->
            SteadySegment(avgPowerW = 400.0, avgHr = hr.toDouble(), durationS = 360.0)
        }
        assertFalse(estimateVo2MaxFromSegments(segments, refProfile).available)
    }

    @Test
    fun `stabile Segmente werden aus der Leistungsreihe extrahiert`() {
        val points = track(
            pointCount = 1801,
            speedMs = 5.0,
            stepS = 1,
            startEle = 100.0,
            hr = { 140 },
        )
        val power = buildPowerSeries(buildRideSeries(points, refProfile), refProfile)
        val segments = extractSteadySegments(power, refProfile)
        assertTrue(segments.size >= 5)
        assertEquals(140.0, segments.first().avgHr, 1e-6)
        assertTrue(segments.first().durationS >= 300)
        // Ohne HF gibt es keine Segmente.
        val noHr = buildPowerSeries(
            buildRideSeries(
                track(pointCount = 1801, speedMs = 5.0, stepS = 1, startEle = 100.0),
                refProfile,
            ),
            refProfile,
        )
        assertTrue(extractSteadySegments(noHr, refProfile).isEmpty())
        assertTrue(extractSteadySegments(PowerSeries.EMPTY, refProfile).isEmpty())
    }

    @Test
    fun `rollierender 28-Tage-Median und 2-Punkte-Regel`() {
        assertNull(vo2MaxRollingMedian(emptyList()))
        assertEquals(50.0, vo2MaxRollingMedian(daily(listOf(48.0, 50.0, 52.0)))!!, 1e-9)
        assertFalse(vo2MaxChangeWorthShowing(50.0, 51.0))
        assertTrue(vo2MaxChangeWorthShowing(50.0, 52.0))
        assertTrue(vo2MaxChangeWorthShowing(null, 52.0))
        assertFalse(vo2MaxChangeWorthShowing(50.0, null))
    }

    // --- group('Formulierungen ohne Overclaim') ---

    @Test
    fun `Lastquellen sind als Schaetzung gekennzeichnet`() {
        assertTrue(loadSourceLabels[LoadSource.PHYSIK]!!.contains("schätzung"))
        assertTrue(loadSourceLabels[LoadSource.HEURISTIK]!!.contains("geschätzt"))
    }

    @Test
    fun `Confidence-Labels sind sprechend`() {
        assertEquals("nicht berechenbar", confidenceLabels[Confidence.NONE])
        assertEquals(Confidence.entries.size, confidenceLabels.size)
    }

    @Test
    fun `Erholungs-Ampel spricht nicht von Krankheit als Diagnose`() {
        val all = recoveryFlagLabels.values.joinToString(" ").lowercase()
        assertFalse(all.contains("krank"))
        assertFalse(all.contains("übertraining"))
    }

    @Test
    fun `VO2max wird immer als Band ausgegeben`() {
        val e = estimateVo2MaxFromHrRatio(refProfile)
        assertTrue(Regex("""\d+–\d+ ml/kg/min""").containsMatchIn(e.text))
    }
}
