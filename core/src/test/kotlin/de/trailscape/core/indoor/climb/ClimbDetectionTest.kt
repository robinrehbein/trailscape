package de.trailscape.core.indoor.climb

import de.trailscape.core.indoor.IndoorRoute
import de.trailscape.core.indoor.IndoorRoutePoint
import de.trailscape.core.indoor.IndoorRouteSource
import de.trailscape.core.indoor.SurfaceKind
import de.trailscape.core.indoor.SurfaceSection
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClimbDetectionTest {
    /** Strecke mit 10-m-Punkten und Hoehe [ele] (Laufmeter → m). */
    private fun route(lengthM: Double, ele: (Double) -> Double): IndoorRoute {
        val n = (lengthM / 10).toInt()
        val pts = List(n + 1) { i -> val d = if (i == n) lengthM else i * 10.0; IndoorRoutePoint(d, 50.0 + d * 1e-5, 7.0, ele(d)) }
        return IndoorRoute("r", "R", pts, listOf(SurfaceSection(0.0, lengthM, SurfaceKind.ASPHALT)), false, IndoorRouteSource.NONE)
    }

    /** Stueckweise lineares Profil aus (Laufmeter, Steigung in %) ab Hoehe 100 m. */
    private fun piecewise(vararg legs: Pair<Double, Double>): (Double) -> Double = { d ->
        var e = 100.0
        var at = 0.0
        for ((len, g) in legs) {
            val part = (d - at).coerceIn(0.0, len)
            e += part * g / 100.0
            at += len
        }
        e
    }

    @Test
    fun `flache Strecke hat keine Anstiege`() {
        assertTrue(detectClimbs(route(10_000.0) { 200.0 }).isEmpty())
    }

    @Test
    fun `ein gleichmaessiger Anstieg wird mit Lage, Hoehe und Steigung erkannt`() {
        val r = route(8_000.0, piecewise(2_000.0 to 0.0, 2_400.0 to 6.0, 3_600.0 to 0.0))
        val climbs = detectClimbs(r)
        assertEquals(1, climbs.size)
        val c = climbs[0]
        assertEquals(2_000.0, c.startM, 50.0)
        assertEquals(4_400.0, c.endM, 50.0)
        assertEquals(144.0, c.gainM, 3.0)
        assertEquals(6.0, c.avgGradePct, 0.2)
        assertEquals(244.0, c.topEleM, 3.0)
        assertEquals(6.0, c.maxGradePct, 0.2)
        // 2400 m × 6 % = 14400 → vierte Kategorie
        assertEquals(ClimbCategory.CAT4, c.category)
        assertEquals(0, c.index)
    }

    @Test
    fun `kurzes Flachstueck wird zusammengefasst, langes trennt`() {
        val short = detectClimbs(route(6_000.0, piecewise(500.0 to 0.0, 1_000.0 to 7.0, 200.0 to 0.0, 1_000.0 to 7.0, 3_300.0 to 0.0)))
        assertEquals(1, short.size)
        assertEquals(500.0, short[0].startM, 50.0)
        assertEquals(2_700.0, short[0].endM, 50.0)

        val long = detectClimbs(route(6_000.0, piecewise(500.0 to 0.0, 1_000.0 to 7.0, 600.0 to 0.0, 1_000.0 to 7.0, 2_900.0 to 0.0)))
        assertEquals(2, long.size)
        assertEquals(listOf(0, 1), long.map { it.index })
    }

    @Test
    fun `kleine Senke verbindet, grosse Abfahrt trennt`() {
        val dip = detectClimbs(route(6_000.0, piecewise(500.0 to 0.0, 1_000.0 to 7.0, 150.0 to -4.0, 1_000.0 to 7.0, 3_350.0 to 0.0)))
        assertEquals(1, dip.size)
        // 250 m bei −8 % = 20 m Verlust: mehr als MAX_DIP_M
        val drop = detectClimbs(route(6_000.0, piecewise(500.0 to 0.0, 1_000.0 to 7.0, 250.0 to -8.0, 1_000.0 to 7.0, 3_250.0 to 0.0)))
        assertEquals(2, drop.size)
    }

    @Test
    fun `zu kurz, zu flach oder zu klein zaehlt nicht`() {
        // 300 m bei 10 %: kuerzer als MIN_LENGTH_M
        assertTrue(detectClimbs(route(4_000.0, piecewise(1_000.0 to 0.0, 300.0 to 10.0, 2_700.0 to 0.0))).isEmpty())
        // 3 km bei 2,5 %: unter MIN_AVG_GRADE_PCT
        assertTrue(detectClimbs(route(6_000.0, piecewise(1_000.0 to 0.0, 3_000.0 to 2.5, 2_000.0 to 0.0))).isEmpty())
        // 500 m bei 4 % = 2000 < MIN_SCORE
        assertTrue(detectClimbs(route(4_000.0, piecewise(1_000.0 to 0.0, 500.0 to 4.0, 2_500.0 to 0.0))).isEmpty())
        // 1 km bei 4 % = 4000 ≥ MIN_SCORE, ohne Kategorie
        val small = detectClimbs(route(4_000.0, piecewise(1_000.0 to 0.0, 1_000.0 to 4.0, 2_000.0 to 0.0)))
        assertEquals(ClimbCategory.UNCATEGORIZED, small.single().category)
    }

    @Test
    fun `Kategorien nach Wertungszahl`() {
        assertEquals(ClimbCategory.UNCATEGORIZED, ClimbCategory.of(5_000.0))
        assertEquals(ClimbCategory.CAT4, ClimbCategory.of(8_000.0))
        assertEquals(ClimbCategory.CAT3, ClimbCategory.of(20_000.0))
        assertEquals(ClimbCategory.CAT2, ClimbCategory.of(40_000.0))
        assertEquals(ClimbCategory.CAT1, ClimbCategory.of(70_000.0))
        assertEquals(ClimbCategory.HC, ClimbCategory.of(120_000.0))
        // 10 km bei 8 %: ein Pass ausser Kategorie
        val hc = detectClimbs(route(14_000.0, piecewise(2_000.0 to 0.0, 10_000.0 to 8.0, 2_000.0 to 0.0)))
        assertEquals(ClimbCategory.HC, hc.single().category)
        ClimbCategory.entries.forEach { assertEquals(it, ClimbCategory.fromJsonName(it.jsonName)) }
    }

    @Test
    fun `steilste Stelle ueber 100 m`() {
        val c = detectClimbs(route(5_000.0, piecewise(1_000.0 to 0.0, 800.0 to 5.0, 200.0 to 12.0, 800.0 to 5.0, 2_200.0 to 0.0))).single()
        assertEquals(12.0, c.maxGradePct, 0.3)
        assertTrue(c.avgGradePct in 5.5..7.0)
    }

    @Test
    fun `welliges Rauschen ohne Anstieg bleibt leer`() {
        assertTrue(detectClimbs(route(10_000.0) { d -> 300.0 + 1.5 * sin(d / 40.0) }).isEmpty())
    }

    @Test
    fun `Anstieg am Streckenanfang und am Ende`() {
        val c = detectClimbs(route(3_000.0, piecewise(1_500.0 to 6.0, 1_500.0 to 0.0)))
        assertEquals(0.0, c.single().startM, 1e-6)
        val e = detectClimbs(route(3_000.0, piecewise(1_500.0 to 0.0, 1_500.0 to 6.0)))
        assertEquals(3_000.0, e.single().endM, 1e-6)
    }

    @Test
    fun `unbrauchbare Eingaben ergeben keine Anstiege`() {
        assertTrue(detectClimbs(doubleArrayOf(0.0), doubleArrayOf(1.0)).isEmpty())
        assertTrue(detectClimbs(doubleArrayOf(0.0, 1000.0), doubleArrayOf(1.0)).isEmpty())
        assertTrue(detectClimbs(doubleArrayOf(0.0, 1000.0), doubleArrayOf(0.0, Double.NaN)).isEmpty())
    }

    @Test
    fun `Schluessel ist stabil und auf 10 m gerundet`() {
        val c = detectClimbs(route(8_000.0, piecewise(2_000.0 to 0.0, 2_400.0 to 6.0, 3_600.0 to 0.0))).single()
        assertEquals("ride:x@${Math.round(c.startM / 10) * 10}-${Math.round(c.endM / 10) * 10}", climbKey("ride:x", c))
        assertEquals(climbKey("ride:x", c), climbKey("ride:x", c.copy(startM = c.startM + 2)))
    }
}
