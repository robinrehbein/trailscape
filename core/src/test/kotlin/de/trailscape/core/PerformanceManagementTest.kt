package de.trailscape.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Portierung der Gruppen `CTL / ATL / TSB` und `Wochenziel` aus
 * `test/training_load_test.dart`. Alle Erwartungswerte unveraendert.
 */
class PerformanceManagementTest {

    // --- group('CTL / ATL / TSB') ---

    @Test
    fun `Lambda-Werte entsprechen 42 und 7 Tagen`() {
        assertEquals(0.023528313347756735, lambdaCtl, 1e-12)
        assertEquals(0.1331221002498184, lambdaAtl, 1e-12)
        assertEquals(0.15351827510938576, ctlWeeklyResponse, 1e-12)
    }

    @Test
    fun `drei Tage a 100 Punkte ohne Historie ergeben die exakte Rekursion`() {
        val series = computeFitnessSeries(constantLoads(3, 100.0))
        assertEquals(0.0, series.seedLoad, 0.0)
        assertFalse(series.displayReady)
        assertEquals(25, series.daysUntilDisplayReady)

        val p = series.points
        assertEquals(3, p.size)
        assertEquals(2.3528313347756735, p[0].ctl, 1e-9)
        assertEquals(13.312210024981841, p[0].atl, 1e-9)
        assertEquals(0.0, p[0].tsb, 1e-12)
        assertEquals(4.650304516652325, p[1].ctl, 1e-9)
        assertEquals(24.852270692471414, p[1].atl, 1e-9)
        assertEquals(-10.959378690206167, p[1].tsb, 1e-9)
        assertEquals(-20.201966175819088, p[2].tsb, 1e-9)
    }

    @Test
    fun `TSB nutzt die Vortagswerte (TrainingPeaks-Konvention)`() {
        val series = computeFitnessSeries(constantLoads(5, 80.0))
        val p = series.points
        for (i in 1 until p.size) {
            assertEquals(p[i - 1].ctl - p[i - 1].atl, p[i].tsb, 1e-12)
        }
    }

    @Test
    fun `Seeding ab 42 Tagen laesst CTL auf dem Mittel starten`() {
        val series = computeFitnessSeries(constantLoads(50, 50.0))
        assertEquals(50.0, series.seedLoad, 1e-9)
        assertEquals(50.0, series.latest!!.ctl, 1e-9)
        assertEquals(50.0, series.latest!!.atl, 1e-9)
        assertEquals(0.0, series.latest!!.tsb, 1e-9)
        assertTrue(series.displayReady)
    }

    @Test
    fun `Seeding bei 14 bis 41 Tagen nutzt den verfuegbaren Zeitraum`() {
        val series = computeFitnessSeries(constantLoads(20, 30.0))
        assertEquals(30.0, series.seedLoad, 1e-9)
        assertEquals(30.0, series.latest!!.ctl, 1e-9)
        assertEquals(20, series.historyDays)
    }

    @Test
    fun `unter 14 Tagen wird nicht geseedet und nicht angezeigt`() {
        val series = computeFitnessSeries(constantLoads(13, 90.0))
        assertEquals(0.0, series.seedLoad, 0.0)
        assertFalse(series.displayReady)
        assertEquals(15, series.daysUntilDisplayReady)
    }

    @Test
    fun `Ruhetage werden als 0 aufgefuellt`() {
        val series = computeFitnessSeries(
            listOf(
                DailyLoad(day = dt(2026, 8, 1), load = 100.0),
                DailyLoad(day = dt(2026, 8, 6), load = 50.0),
            ),
        )
        assertEquals(6, series.points.size)
        assertEquals(0.0, series.points[1].load, 0.0)
        assertEquals(0.0, series.points[4].load, 0.0)
        assertEquals(50.0, series.points[5].load, 0.0)
    }

    @Test
    fun `mehrere Touren am selben Tag werden summiert`() {
        val series = computeFitnessSeries(
            listOf(
                DailyLoad(day = dt(2026, 8, 1, 9), load = 60.0),
                DailyLoad(day = dt(2026, 8, 1, 17), load = 40.0),
            ),
        )
        assertEquals(1, series.points.size)
        assertEquals(100.0, series.points.single().load, 1e-9)
    }

    @Test
    fun `until verlaengert die Serie mit Ruhetagen`() {
        val series = computeFitnessSeries(
            listOf(DailyLoad(day = dt(2026, 8, 1), load = 100.0)),
            until = dt(2026, 8, 5),
        )
        assertEquals(5, series.points.size)
        assertEquals(0.0, series.latest!!.load, 0.0)
        assertTrue(series.latest!!.atl < series.points.first().atl)
    }

    @Test
    fun `Rampenrate erst ab Tag 8, dann CTL_t minus CTL_t-7`() {
        val series = computeFitnessSeries(constantLoads(30, 100.0))
        assertNull(series.points[6].rampRate7d)
        assertNotNull(series.points[7].rampRate7d)
        val p = series.points
        assertEquals(p[20].ctl - p[13].ctl, p[20].rampRate7d!!, 1e-9)
    }

    @Test
    fun `Rampenraten-Baender`() {
        assertEquals(RampBand.FORMVERLUST, classifyRampRate(-1.0))
        assertEquals(RampBand.ERHALTUNG, classifyRampRate(0.0))
        assertEquals(RampBand.ERHALTUNG, classifyRampRate(2.9))
        assertEquals(RampBand.AUFBAU, classifyRampRate(3.0))
        assertEquals(RampBand.AUFBAU, classifyRampRate(6.0))
        assertEquals(RampBand.AGGRESSIV, classifyRampRate(7.0))
        assertEquals(RampBand.AGGRESSIV, classifyRampRate(8.0))
        assertEquals(RampBand.ZU_SCHNELL, classifyRampRate(8.1))
    }

    @Test
    fun `TSB-Baender an den Grenzen`() {
        assertEquals(TsbBand.SEHR_FRISCH, classifyTsb(26.0))
        assertEquals(TsbBand.FORMSPITZE, classifyTsb(25.0))
        assertEquals(TsbBand.FORMSPITZE, classifyTsb(5.0))
        assertEquals(TsbBand.NEUTRAL, classifyTsb(4.9))
        assertEquals(TsbBand.NEUTRAL, classifyTsb(-10.0))
        assertEquals(TsbBand.PRODUKTIV, classifyTsb(-10.1))
        assertEquals(TsbBand.PRODUKTIV, classifyTsb(-30.0))
        assertEquals(TsbBand.UEBERLASTUNG, classifyTsb(-30.1))
        assertTrue(tsbBandMessages[TsbBand.FORMSPITZE]!!.contains("viele Fahrer"))
    }

    @Test
    fun `Belastungsverhaeltnis - gleichmaessige Last ergibt 1,0`() {
        val series = computeFitnessSeries(constantLoads(60, 50.0))
        assertEquals(1.0, series.latest!!.loadRatio!!, 1e-9)
        assertEquals(LoadRatioBand.IM_BAND, classifyLoadRatio(series.latest!!.loadRatio))
    }

    @Test
    fun `Belastungsverhaeltnis wird bei kleiner chronischer Last unterdrueckt`() {
        val series = computeFitnessSeries(constantLoads(60, 1.0))
        assertNull(series.latest!!.loadRatio)
        assertEquals(LoadRatioBand.UNBEKANNT, classifyLoadRatio(null))
    }

    @Test
    fun `Belastungssprung wird neutral benannt`() {
        val loads = constantLoads(60, 40.0, end = dt(2026, 7, 30)) +
            constantLoads(9, 250.0, end = dt(2026, 8, 8))
        val series = computeFitnessSeries(loads)
        assertTrue(series.latest!!.loadRatio!! > loadRatioBandHigh)
        assertEquals(
            LoadRatioBand.BELASTUNGSSPRUNG,
            classifyLoadRatio(series.latest!!.loadRatio),
        )
        assertEquals("Belastungssprung", loadRatioLabels[LoadRatioBand.BELASTUNGSSPRUNG])
        assertFalse(
            loadRatioLabels.values.joinToString(" ").lowercase().contains("verletzung"),
        )
    }

    @Test
    fun `Band 0,8 bis 1,5`() {
        assertEquals(LoadRatioBand.NIEDRIG, classifyLoadRatio(0.79))
        assertEquals(LoadRatioBand.IM_BAND, classifyLoadRatio(0.8))
        assertEquals(LoadRatioBand.IM_BAND, classifyLoadRatio(1.5))
        assertEquals(LoadRatioBand.BELASTUNGSSPRUNG, classifyLoadRatio(1.51))
    }

    @Test
    fun `leere und unsinnige Eingaben ergeben eine leere Serie`() {
        assertTrue(computeFitnessSeries(emptyList()).points.isEmpty())
        assertTrue(
            computeFitnessSeries(
                listOf(
                    DailyLoad(day = dt(2026, 8, 1), load = Double.NaN),
                    DailyLoad(day = dt(2026, 8, 2), load = -5.0),
                ),
            ).points.isEmpty(),
        )
        assertNull(FitnessSeries.EMPTY.latest)
    }

    @Test
    fun `at() und lastDays() greifen zu`() {
        val series = computeFitnessSeries(constantLoads(10, 40.0))
        assertNotNull(series.at(dt(2026, 8, 8)))
        assertNull(series.at(dt(2020, 1, 1)))
        assertEquals(3, series.lastDays(3).size)
        assertTrue(series.lastDays(0).isEmpty())
        assertEquals(10, series.lastDays(99).size)
    }

    @Test
    fun `dailyLoadsFrom fasst Touren zu Tagessummen zusammen`() {
        val loads = dailyLoadsFrom(
            listOf(
                LoadEntry(at = dt(2026, 8, 1, 8), load = 40.0),
                LoadEntry(at = dt(2026, 8, 1, 18), load = 30.0),
                LoadEntry(at = dt(2026, 8, 3, 12), load = 20.0),
                LoadEntry(at = dt(2026, 8, 4, 12), load = Double.NaN),
            ),
        )
        assertEquals(2, loads.size)
        assertEquals(70.0, loads.first().load, 1e-9)
    }

    // --- group('Wochenziel') ---

    @Test
    fun `Zielrampe ergibt die Wochenlast ueber die EWMA-Rekursion`() {
        val target = weeklyLoadTarget(ctl = 50.0, targetRamp = 5.0)
        assertEquals(82.56941231548733, target.dailyLoad, 1e-6)
        assertEquals(577.9858862084113, target.weeklyLoad, 1e-6)
        assertTrue(target.caps.isEmpty())
    }

    @Test
    fun `130-Prozent-Deckel und Zeitdeckel greifen`() {
        val capped = weeklyLoadTarget(
            ctl = 50.0,
            targetRamp = 5.0,
            recentWeeklyMean = 300.0,
        )
        assertEquals(390.0, capped.weeklyLoad, 1e-9)
        assertEquals(1, capped.caps.size)

        val both = weeklyLoadTarget(
            ctl = 50.0,
            targetRamp = 5.0,
            recentWeeklyMean = 300.0,
            weeklyHours = 4.0,
        )
        // 4 h × 58 Last/h = 232 - schaerfer als der 130-%-Deckel (390).
        assertEquals(232.0, both.weeklyLoad, 1e-9)
        assertEquals(2, both.caps.size)
        assertTrue(both.caps.last().contains("Zeitbudget"))
        assertTrue(both.caps.last().contains("4 h"))
    }

    @Test
    fun `Zeitbudget deckelt auf weeklyHours mal 58 Last pro Stunde`() {
        val target = weeklyLoadTarget(
            ctl = 50.0,
            targetRamp = 5.0,
            weeklyHours = 6.0,
        )
        assertEquals(6 * weeklyLoadPerHour, target.weeklyLoad, 1e-9)
        assertEquals(6.0, target.weeklyHours!!, 0.0)
        assertEquals(6.0, target.estimatedHours, 1e-9)
        assertEquals(6 * weeklyLoadPerHour / 7, target.dailyLoad, 1e-9)
    }

    @Test
    fun `grosszuegiges Zeitbudget greift nicht ein`() {
        val target = weeklyLoadTarget(
            ctl = 50.0,
            targetRamp = 5.0,
            weeklyHours = 20.0,
        )
        assertEquals(577.9858862084113, target.weeklyLoad, 1e-6)
        assertTrue(target.caps.isEmpty())
        assertEquals(577.9858862084113 / 58, target.estimatedHours, 1e-6)
    }

    @Test
    fun `ohne Zeitbudget bleibt der Zielwert unveraendert`() {
        val target = weeklyLoadTarget(ctl = 50.0, targetRamp = 5.0)
        assertNull(target.weeklyHours)
        assertTrue(target.caps.isEmpty())
    }

    @Test
    fun `unplausibles Zeitbudget (0 oder negativ) wird ignoriert`() {
        assertTrue(
            weeklyLoadTarget(ctl = 50.0, targetRamp = 5.0, weeklyHours = 0.0).caps.isEmpty(),
        )
        assertTrue(
            weeklyLoadTarget(ctl = 50.0, targetRamp = 5.0, weeklyHours = -3.0).caps.isEmpty(),
        )
    }

    @Test
    fun `Stundenformat deutsch - ganze Zahl ohne Komma`() {
        assertEquals("5", formatHours(5.0))
        assertEquals("4,5", formatHours(4.5))
        assertEquals("4,5", formatHours(4.47))
    }

    @Test
    fun `negative Zielrampe erzeugt keine negative Last`() {
        val target = weeklyLoadTarget(ctl = 5.0, targetRamp = -15.0)
        assertEquals(0.0, target.weeklyLoad, 0.0)
    }

    @Test
    fun `Ziel-Intensitaetsverteilung`() {
        assertEquals(listOf(80.0, 5.0, 15.0), intensityDistributionTarget())
        assertEquals(listOf(75.0, 15.0, 10.0), intensityDistributionTarget(polarized = false))
    }
}
