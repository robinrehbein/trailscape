package de.trailscape.core

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Portierung der Gruppe `Banister-TRIMP und hrTSS` aus
 * `test/training_load_test.dart`. Alle Erwartungswerte unveraendert.
 */
class HeartRateLoadTest {

    @Test
    fun `Einzel-Sample entspricht der Formel x mal a mal e hoch (b mal x)`() {
        // x = (130−50)/140 = 0,571428…; k = 0,64·e^(1,92·x)
        val v = trimpSampleContribution(hr = 130.0, dtS = 3600.0, profile = refProfile)
        assertEquals(65.73191188513462, v, 1e-9)
    }

    @Test
    fun `x wird bei 1,05 geklemmt (HF ueber HFmax)`() {
        val v = trimpSampleContribution(hr = 210.0, dtS = 600.0, profile = refProfile)
        assertEquals(50.4553181005325, v, 1e-9)
        // Deckelung greift - 300 bpm ergibt denselben Wert.
        assertEquals(
            v,
            trimpSampleContribution(hr = 300.0, dtS = 600.0, profile = refProfile),
            1e-9,
        )
    }

    @Test
    fun `HF unter Ruhepuls liefert Beitrag 0, nie negativ`() {
        assertEquals(0.0, trimpSampleContribution(hr = 40.0, dtS = 600.0, profile = refProfile), 0.0)
        assertEquals(0.0, trimpSampleContribution(hr = 50.0, dtS = 600.0, profile = refProfile), 0.0)
    }

    @Test
    fun `dt kleiner gleich 0 liefert 0`() {
        assertEquals(0.0, trimpSampleContribution(hr = 150.0, dtS = 0.0, profile = refProfile), 0.0)
        assertEquals(0.0, trimpSampleContribution(hr = 150.0, dtS = -5.0, profile = refProfile), 0.0)
    }

    @Test
    fun `TRIMP_ref ist die Stunde an der Schwelle`() {
        assertEquals(170.651090478826, trimpReference(refProfile), 1e-9)
    }

    @Test
    fun `1 h an der Schwelle ergibt exakt 100 Punkte`() {
        val trimp = trimpSampleContribution(hr = 170.0, dtS = 3600.0, profile = refProfile)
        assertEquals(100.0, normalizeTrimp(trimp, refProfile), 1e-9)
    }

    @Test
    fun `60 min bei 130 bpm ergeben 38,5 Punkte`() {
        val points = track(pointCount = 361, hr = { 130 })
        val series = buildRideSeries(points, refProfile)
        assertEquals(3600.0, series.movingTimeS, 1e-9)
        val load = computeHeartRateLoad(series, refProfile)
        assertTrue(load.available)
        assertEquals(65.73191188513462, load.trimpBanister, 1e-6)
        assertEquals(38.518307560003834, load.load, 1e-6)
        assertEquals(1.0, load.hrCoverage, 1e-9)
        assertEquals(130.0, load.avgHr!!, 1e-9)
        assertEquals(130, load.maxHr)
    }

    @Test
    fun `sample-weise Integration liegt ueber der Durchschnitts-HF-Variante (Jensen)`() {
        // 30 min @110 + 30 min @150 vs. 60 min @130 (gleiche Ø-HF).
        val points = track(pointCount = 361, hr = { i -> if (i <= 180) 110 else 150 })
        val split = computeHeartRateLoad(buildRideSeries(points, refProfile), refProfile)
        assertEquals(72.78410600605302, split.trimpBanister, 1e-6)
        assertEquals(42.65082971449509, split.load, 1e-6)
        assertTrue(split.load > 38.52)
    }

    @Test
    fun `Geschlechtskoeffizienten veraendern den TRIMP`() {
        val w = refProfile.copyWith(sex = Sex.WEIBLICH)
        val m = trimpSampleContribution(hr = 130.0, dtS = 3600.0, profile = refProfile)
        val f = trimpSampleContribution(hr = 130.0, dtS = 3600.0, profile = w)
        assertTrue(abs(f - m) > 0.5)
        // 0,86 · e^(1,67·0,571428…) · 0,571428… · 60
        assertEquals(76.56894668833213, f, 1e-6)
    }

    @Test
    fun `Edwards-TRIMP summiert Zonenminuten mit 1 bis 5`() {
        // 130/190 = 68,4 % HFmax -> Zone 2 (Gewicht 2), 60 min -> 120.
        val load = computeHeartRateLoad(
            buildRideSeries(track(pointCount = 361, hr = { 130 }), refProfile),
            refProfile,
        )
        assertEquals(120.0, load.trimpEdwards, 1e-6)
    }

    @Test
    fun `Intensitaetsverteilung in 5 Friel- und 3 Lucia-Zonen`() {
        // 5 Bloecke a 600 s: 130 (Z1/LIT), 150 (Z2/MIT), 155 (Z3/MIT),
        // 165 (Z4/MIT), 175 (Z5/HIT).
        val blockHr = listOf(130, 150, 155, 165, 175)
        val points = track(
            pointCount = 301,
            hr = { i -> blockHr[minOf(4, maxOf(0, (i - 1) / 60))] },
        )
        val load = computeHeartRateLoad(buildRideSeries(points, refProfile), refProfile)
        for (i in 0 until 5) {
            assertEquals(600.0, load.frielZones.seconds[i], 1.0)
        }
        assertEquals(600.0, load.luciaZones.seconds[0], 1.0)
        assertEquals(1800.0, load.luciaZones.seconds[1], 1.0)
        assertEquals(600.0, load.luciaZones.seconds[2], 1.0)
        assertEquals(0.2, load.luciaZones.fractions[0], 0.01)
        assertEquals(600.0, load.secondsAboveLthr, 1.0)
        assertEquals(1.0 * 10 + 2 * 30 + 3 * 10, load.trimpLucia, 0.1)
    }

    @Test
    fun `Confidence sinkt ohne Feldtest und ohne Geschlecht`() {
        val points = track(pointCount = 361, hr = { 130 })
        val full = computeHeartRateLoad(buildRideSeries(points, refProfile), refProfile)
        assertEquals(Confidence.HIGH, full.confidence)

        val anon = TrainingProfile(ageYears = 40)
        val weak = computeHeartRateLoad(buildRideSeries(points, anon), anon)
        assertEquals(Confidence.LOW, weak.confidence)
    }

    @Test
    fun `unter 80 Prozent HF-Abdeckung faellt die Tour aus dem HF-Pfad`() {
        val points = track(pointCount = 361, hr = { i -> if (i < 150) 130 else null })
        val series = buildRideSeries(points, refProfile)
        val load = computeHeartRateLoad(series, refProfile)
        assertTrue(load.hrCoverage < 0.8)
        assertFalse(load.available)
        assertTrue(load.unavailableReason!!.contains("Herzfrequenz"))
        assertEquals(Confidence.NONE, load.confidence)
        // Der TRIMP wird trotzdem berechnet (Transparenz), nur nicht benutzt.
        assertTrue(load.trimpBanister > 0)
    }

    @Test
    fun `Luecke groesser 30 s wird nicht interpoliert`() {
        // 60-s-Abtastung: die HF darf nur noch vom Endpunkt kommen.
        val points = track(
            pointCount = 40,
            stepS = 60,
            hr = { i -> if (i % 2 == 0) 150 else null },
        )
        val series = buildRideSeries(points, refProfile)
        val withHr = series.segments.count { it.hr != null }
        assertTrue(withHr < series.segments.size)
        assertTrue(series.hrCoverage < 0.8)
    }

    @Test
    fun `leere und zu kurze Tracks werfen nicht`() {
        val cases = listOf(
            emptyList<TrackPoint>(),
            listOf(TrackPoint(lat = 47.0, lon = 11.0)),
            listOf(TrackPoint(lat = 47.0, lon = 11.0, time = T0)),
        )
        for (points in cases) {
            val series = buildRideSeries(points, refProfile)
            assertTrue(series.isEmpty)
            val load = computeHeartRateLoad(series, refProfile)
            assertFalse(load.available)
            assertEquals(0.0, load.load, 0.0)
            assertNotNull(load.unavailableReason)
        }
    }

    @Test
    fun `Tour ohne Herzfrequenz meldet den Fehlgrund`() {
        val load = computeHeartRateLoad(
            buildRideSeries(track(pointCount = 100), refProfile),
            refProfile,
        )
        assertFalse(load.available)
        assertTrue(load.unavailableReason!!.contains("keine Herzfrequenz"))
    }

    @Test
    fun `Punkte ohne Zeitstempel ergeben eine leere Serie`() {
        val points = listOf(
            TrackPoint(lat = 47.0, lon = 11.0, ele = 100.0),
            TrackPoint(lat = 47.001, lon = 11.0, ele = 105.0),
        )
        assertTrue(buildRideSeries(points, refProfile).isEmpty)
    }

    @Test
    fun `unsortierte Punkte werden sortiert`() {
        val points = track(pointCount = 61, hr = { 130 }).reversed()
        val series = buildRideSeries(points, refProfile)
        assertEquals(600.0, series.movingTimeS, 1e-9)
    }
}
