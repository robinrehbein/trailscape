package de.trailscape.core

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests fuer `WindScore.kt`: Konvention (Wind **aus** einer Richtung),
 * Haelftenteilung nach Distanz, Staerkefaktor, Score und die deutschen Texte.
 */
class WindScoreTest {
    private companion object {
        const val EPS = 1e-9

        /** Startpunkt in Muenchen. */
        val START = TrackPoint(lat = 48.1372, lon = 11.5756)

        const val WEST = 270.0
        const val EAST = 90.0

        /** Punkt [km] Kilometer unter Kurs [bearing] von [from]. */
        fun go(from: TrackPoint, bearing: Double, km: Double): TrackPoint =
            destinationPoint(from, bearing, km * 1000)

        /** 10 km nach Westen und auf demselben Weg zurueck. */
        fun outWestAndBack(): List<TrackPoint> {
            val far = go(START, WEST, 10.0)
            return listOf(START, far, START)
        }

        fun loop(bearing: Double, clockwise: Boolean = true): List<TrackPoint> =
            loopWaypoints(START, radiusM = 3000.0, bearingDeg = bearing, viaCount = 12, clockwise = clockwise)
                .map { TrackPoint(lat = it.lat, lon = it.lon) }
    }

    // --- Geometrie ---

    @Test
    fun `Anfangskurs fuer reine Nord-, Ost-, Sued- und Westverschiebung`() {
        assertEquals(0.0, initialBearingDeg(START, TrackPoint(START.lat + 0.05, START.lon)), 0.5)
        assertEquals(90.0, initialBearingDeg(START, TrackPoint(START.lat, START.lon + 0.05)), 0.5)
        assertEquals(180.0, initialBearingDeg(START, TrackPoint(START.lat - 0.05, START.lon)), 0.5)
        assertEquals(270.0, initialBearingDeg(START, TrackPoint(START.lat, START.lon - 0.05)), 0.5)
    }

    @Test
    fun `Gegenwind-Anteil folgt der meteorologischen Konvention`() {
        // Kurs West bei Wind aus West: voll hinein.
        assertEquals(1.0, headwindComponent(270.0, 270.0), EPS)
        assertEquals(-1.0, headwindComponent(90.0, 270.0), EPS)
        assertEquals(0.0, headwindComponent(0.0, 270.0), EPS)
        assertEquals(1.0, headwindComponent(360.0, 0.0), EPS)
    }

    @Test
    fun `Staerkefaktor steigt linear von 10 bis 25 kmh`() {
        assertEquals(0.0, windStrengthFactor(0.0), EPS)
        assertEquals(0.0, windStrengthFactor(9.9), EPS)
        assertEquals(0.0, windStrengthFactor(10.0), EPS)
        assertEquals(0.5, windStrengthFactor(17.5), EPS)
        assertEquals(1.0, windStrengthFactor(25.0), EPS)
        assertEquals(1.0, windStrengthFactor(60.0), EPS)
        assertEquals(0.0, windStrengthFactor(Double.NaN), EPS)
        assertEquals(0.0, windStrengthFactor(-5.0), EPS)
        assertEquals(0.0, windStrengthFactor(Double.POSITIVE_INFINITY), EPS)
    }

    // --- Form ---

    @Test
    fun `hin nach Westen und zurueck bei Westwind ist ideal`() {
        val shape = assertNotNull(windShape(outWestAndBack(), WEST))
        assertTrue(shape > 0.95, "Form $shape")
    }

    @Test
    fun `dieselbe Strecke bei Ostwind ist das Gegenteil`() {
        val shape = assertNotNull(windShape(outWestAndBack(), EAST))
        assertTrue(shape < -0.95, "Form $shape")
    }

    @Test
    fun `Nord-Sued-Strecke bei Westwind ist neutral`() {
        val far = go(START, 0.0, 10.0)
        val shape = assertNotNull(windShape(listOf(START, far, START), WEST))
        assertTrue(abs(shape) < 0.05, "Form $shape")
    }

    @Test
    fun `Kreisrunde gegen den Wind erreicht etwa zwei durch Pi`() {
        val into = assertNotNull(windShape(loop(270.0), WEST))
        assertTrue(into in 0.5..0.7, "Form $into")

        val away = assertNotNull(windShape(loop(90.0), WEST))
        assertTrue(away < -0.5, "Form $away")

        // Der Umlaufsinn aendert das Vorzeichen nicht.
        val ccw = assertNotNull(windShape(loop(270.0, clockwise = false), WEST))
        assertTrue(ccw in 0.5..0.7, "Form $ccw")
        val ccwAway = assertNotNull(windShape(loop(90.0, clockwise = false), WEST))
        assertTrue(ccwAway < -0.5, "Form $ccwAway")
    }

    @Test
    fun `Haelften werden nach Distanz getrennt, nicht nach Punktindex`() {
        // 3 km West, 1 km West, 4 km Ost: Mitte nach 4 km, beim Punktindex 2
        // laege sie nach 4 km — hier stimmt es, aber nur ueber die Distanz.
        val a = go(START, WEST, 3.0)
        val b = go(a, WEST, 1.0)
        val points = listOf(START, a, b, START)
        val (h1, h2) = assertNotNull(windHalves(points, WEST))
        assertEquals(1.0, h1, 1e-3)
        assertEquals(-1.0, h2, 1e-3)

        // Viele kurze Punkte auf dem Hinweg, einer auf dem Rueckweg: Der
        // Punktindex-Mittelpunkt laege tief im Hinweg, die Distanz trennt
        // trotzdem sauber an der Wende.
        val dense = mutableListOf(START)
        repeat(10) { dense.add(go(dense.last(), WEST, 0.5)) }
        dense.add(START)
        val (d1, d2) = assertNotNull(windHalves(dense, WEST))
        assertEquals(1.0, d1, 1e-3)
        assertEquals(-1.0, d2, 1e-3)
    }

    @Test
    fun `das Segment ueber der Mitte wird anteilig geteilt`() {
        // 4 km Nord (Seitenwind), 4 km West (Gegenwind), zurueck nach Suedost
        // (≈ 5,66 km, Anteil cos 135° ≈ −0,707). Gesamt ≈ 13,66 km, Mitte bei
        // ≈ 6,83 km — mitten im Westsegment.
        val n = go(START, 0.0, 4.0)
        val w = go(n, WEST, 4.0)
        val (h1, h2) = assertNotNull(windHalves(listOf(START, n, w, START), WEST))
        val total = 8.0 + 4.0 * Math.sqrt(2.0)
        val half = total / 2
        val expectedH1 = (half - 4.0) / half
        val expectedH2 = ((4.0 - (half - 4.0)) - 4.0 * Math.sqrt(2.0) * Math.sqrt(0.5)) / half
        assertEquals(expectedH1, h1, 0.02)
        assertEquals(expectedH2, h2, 0.02)
    }

    @Test
    fun `entartete Eingaben ergeben keine Form`() {
        assertNull(windShape(emptyList(), WEST))
        assertNull(windShape(listOf(START), WEST))
        assertNull(windShape(listOf(START, START), WEST))
        assertNull(windShape(outWestAndBack(), Double.NaN))
    }

    // --- Score ---

    @Test
    fun `Windscore beruecksichtigt Staerke und Richtung`() {
        val route = outWestAndBack()
        assertEquals(0.0, windScore(route, WindConditions(8.0, WEST, null)), EPS)

        val strong = windScore(route, WindConditions(25.0, WEST, null))
        assertEquals(-windScoreWeight, strong, 0.1)

        val reversed = windScore(route, WindConditions(25.0, EAST, null))
        assertEquals(windScoreWeight, reversed, 0.1)

        val half = windScore(route, WindConditions(17.5, WEST, null))
        assertEquals(strong / 2, half, 1e-6)
    }

    @Test
    fun `Schwelle fuer Rueckenwind heim`() {
        assertTrue(isTailwindHome(0.25))
        assertFalse(isTailwindHome(0.249))
        assertFalse(isTailwindHome(-0.5))
        assertFalse(isTailwindHome(Double.NaN))
    }

    // --- Texte ---

    @Test
    fun `Himmelsrichtungen in acht Stufen`() {
        assertEquals("Nord", windDirectionLabel(0.0))
        assertEquals("Nord", windDirectionLabel(22.4))
        assertEquals("Nordost", windDirectionLabel(22.5))
        assertEquals("Ost", windDirectionLabel(90.0))
        assertEquals("Südost", windDirectionLabel(135.0))
        assertEquals("Süd", windDirectionLabel(180.0))
        assertEquals("Südwest", windDirectionLabel(225.0))
        assertEquals("West", windDirectionLabel(270.0))
        assertEquals("Nordwest", windDirectionLabel(315.0))
        assertEquals("Nord", windDirectionLabel(337.6))
        assertEquals("Nord", windDirectionLabel(359.9))
        assertEquals("West", windDirectionLabel(-90.0))
        assertEquals("wechselnder Richtung", windDirectionLabel(Double.NaN))
    }

    @Test
    fun `Windzeile bei zu schwachem Wind`() {
        assertEquals(
            "Wind 6 km/h aus West – zu schwach, um die Runde danach auszurichten",
            windLine(WindConditions(6.0, WEST, null), shape = null),
        )
    }

    @Test
    fun `Windzeile nach Form der Runde`() {
        val wind = WindConditions(18.0, WEST, null)
        assertEquals("Wind 18 km/h aus West – Rückenwind auf dem Heimweg", windLine(wind, 0.5))
        assertEquals("Wind 18 km/h aus West – Gegenwind auf dem Heimweg", windLine(wind, -0.5))
        assertEquals("Wind 18 km/h aus West – Seitenwind, kein klarer Vorteil", windLine(wind, 0.1))
        assertEquals("Wind 18 km/h aus West – Seitenwind, kein klarer Vorteil", windLine(wind, null))
    }

    @Test
    fun `Boeen nur deutlich ueber dem Mittelwind`() {
        assertEquals(
            "Wind 18 km/h aus West, Böen bis 35 km/h – Rückenwind auf dem Heimweg",
            windLine(WindConditions(18.0, WEST, 35.0), 0.5),
        )
        assertEquals(
            "Wind 18 km/h aus West – Rückenwind auf dem Heimweg",
            windLine(WindConditions(18.0, WEST, 24.0), 0.5),
        )
        assertEquals(
            "Wind 18 km/h aus West – Rückenwind auf dem Heimweg",
            windLine(WindConditions(18.0, WEST, null), 0.5),
        )
        assertEquals(
            "Wind 18 km/h aus West – Rückenwind auf dem Heimweg",
            windLine(WindConditions(17.6, WEST, null), 0.5),
        )
    }
}
