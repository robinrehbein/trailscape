package de.trailscape.core

import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests fuer die Portierung von `lib/navigation.dart`.
 *
 * Direkt aus `test/navigation_test.dart` uebernommen — gleiche Faelle, gleiche
 * Erwartungswerte, damit das Verhalten nachweislich deckungsgleich bleibt.
 */
class NavigationTest {
    private companion object {
        /** Basisbreite der Testrouten. */
        const val LAT0 = 48.0

        /** Basislaenge der Testrouten. */
        const val LON0 = 11.0

        /** Meter pro Grad Laenge auf [LAT0] (identisch zur Projektion im Navigator). */
        val M_PER_DEG_LON = 111320 * cos(LAT0 * Math.PI / 180)

        /** Grad Laenge pro Meter nach Osten. */
        val DEG_LON_PER_M = 1 / M_PER_DEG_LON

        /** Grad Breite pro Meter nach Norden. */
        const val DEG_LAT_PER_M = 1.0 / 111320

        const val EPS = 1e-9
    }

    /** Gerade Ost-Route auf konstanter Breite, [count] Punkte im Abstand [stepM]. */
    private fun straightRoute(count: Int = 21, stepM: Double = 100.0): List<TrackPoint> =
        (0 until count).map { i -> TrackPoint(lat = LAT0, lon = LON0 + i * stepM * DEG_LON_PER_M) }

    private data class Pos(val lat: Double, val lon: Double)

    /** Position [alongM] Meter oestlich des Startpunkts, [offsetM] Meter noerdlich. */
    private fun pos(alongM: Double, offsetM: Double = 0.0): Pos = Pos(
        lat = LAT0 + offsetM * DEG_LAT_PER_M,
        lon = LON0 + alongM * DEG_LON_PER_M,
    )

    // --- RouteNavigator Konstruktor ---

    @Test
    fun `wirft IllegalArgumentException bei weniger als 2 Punkten`() {
        assertFailsWith<IllegalArgumentException> { RouteNavigator(emptyList()) }
        assertFailsWith<IllegalArgumentException> {
            RouteNavigator(listOf(TrackPoint(lat = LAT0, lon = LON0)))
        }
        val e = assertFailsWith<IllegalArgumentException> { RouteNavigator(emptyList()) }
        assertEquals("Route benoetigt mindestens 2 Punkte.", e.message)
    }

    @Test
    fun `totalKm entspricht der Routenlaenge von rund 2 km`() {
        val nav = RouteNavigator(straightRoute())
        assertTrue(kotlin.math.abs(nav.totalKm - 2.0) < 0.01, "erwartet nahe 2.0, war ${nav.totalKm}")
    }

    // --- Szenario 1: Position auf 40 % der Route ---

    @Test
    fun `Szenario 1 - doneKm und Abstand stimmen`() {
        val nav = RouteNavigator(straightRoute())
        val p = pos(800.0)
        val state = nav.update(lat = p.lat, lon = p.lon, now = 0)

        assertTrue(kotlin.math.abs(state.distanceToRouteM - 0) < 0.001)
        assertTrue(kotlin.math.abs(state.doneKm - 0.4 * nav.totalKm) < 1e-6)
        assertTrue(kotlin.math.abs(state.doneKm - 0.8) < 0.01)
        assertTrue(kotlin.math.abs(state.remainingKm - 0.6 * nav.totalKm) < 1e-6)
        assertEquals(8, state.nearestIndex)
        assertFalse(state.offRoute)
    }

    // --- Szenario 2: 100 m seitlich versetzt ---

    @Test
    fun `Szenario 2 - Abstand betraegt rund 100 m`() {
        val nav = RouteNavigator(straightRoute())
        val p = pos(800.0, 100.0)
        val state = nav.update(lat = p.lat, lon = p.lon, now = 0)

        assertTrue(kotlin.math.abs(state.distanceToRouteM - 100) < 0.5)
        assertTrue(kotlin.math.abs(state.doneKm - 0.4 * nav.totalKm) < 1e-6)
        assertFalse(state.offRoute)
    }

    @Test
    fun `Szenario 2 - offRoute erst nach 5 s durchgehend ueber 60 m`() {
        val nav = RouteNavigator(straightRoute())
        val far = pos(800.0, 100.0)

        // Erster Treffer setzt nur den Zeitstempel.
        assertFalse(nav.update(lat = far.lat, lon = far.lon, now = 0).offRoute)
        assertFalse(nav.update(lat = far.lat, lon = far.lon, now = 2000).offRoute)
        assertFalse(nav.update(lat = far.lat, lon = far.lon, now = 4999).offRoute)
        assertTrue(nav.update(lat = far.lat, lon = far.lon, now = 5000).offRoute)
        assertTrue(nav.update(lat = far.lat, lon = far.lon, now = 6000).offRoute)
    }

    @Test
    fun `Szenario 2 - Rueckkehr unter 35 m setzt offRoute sofort zurueck`() {
        val nav = RouteNavigator(straightRoute())
        val far = pos(800.0, 100.0)
        val near = pos(800.0, 10.0)

        nav.update(lat = far.lat, lon = far.lon, now = 0)
        assertTrue(nav.update(lat = far.lat, lon = far.lon, now = 5000).offRoute)
        assertFalse(nav.update(lat = near.lat, lon = near.lon, now = 5500).offRoute)
    }

    @Test
    fun `Szenario 2 - 45-m-Band haelt den Zustand in beide Richtungen`() {
        val nav = RouteNavigator(straightRoute())
        val far = pos(800.0, 100.0)
        val band = pos(800.0, 45.0)
        val near = pos(800.0, 10.0)

        // Zustand false wird im Band gehalten.
        assertFalse(nav.update(lat = near.lat, lon = near.lon, now = 0).offRoute)
        assertFalse(nav.update(lat = band.lat, lon = band.lon, now = 1000).offRoute)
        assertFalse(nav.update(lat = band.lat, lon = band.lon, now = 20000).offRoute)

        // Zustand true wird im Band ebenfalls gehalten.
        nav.update(lat = far.lat, lon = far.lon, now = 30000)
        assertTrue(nav.update(lat = far.lat, lon = far.lon, now = 35000).offRoute)
        assertTrue(nav.update(lat = band.lat, lon = band.lon, now = 36000).offRoute)
        assertTrue(nav.update(lat = band.lat, lon = band.lon, now = 60000).offRoute)
    }

    @Test
    fun `Szenario 2 - Band resettet den Continuity-Timer`() {
        val nav = RouteNavigator(straightRoute())
        val far = pos(800.0, 100.0)
        val band = pos(800.0, 45.0)

        nav.update(lat = far.lat, lon = far.lon, now = 0)
        // Kurzer Abstecher ins Band loescht den Zaehler.
        assertFalse(nav.update(lat = band.lat, lon = band.lon, now = 3000).offRoute)
        // Ab hier laeuft die 5-s-Frist neu.
        assertFalse(nav.update(lat = far.lat, lon = far.lon, now = 4000).offRoute)
        assertFalse(nav.update(lat = far.lat, lon = far.lon, now = 8000).offRoute)
        assertTrue(nav.update(lat = far.lat, lon = far.lon, now = 9000).offRoute)
    }

    // --- Szenario 3: Entlangfahren ---

    @Test
    fun `Szenario 3 - remainingKm faellt monoton, Summe bleibt total, Ende ist 0`() {
        val nav = RouteNavigator(straightRoute())
        val total = nav.totalKm

        var previousRemaining = Double.POSITIVE_INFINITY
        var alongM = 0.0
        while (alongM <= 2000.0) {
            val p = pos(alongM)
            val state = nav.update(lat = p.lat, lon = p.lon, now = Math.round(alongM * 10))

            assertTrue(state.remainingKm <= previousRemaining + 1e-9)
            previousRemaining = state.remainingKm

            assertTrue(kotlin.math.abs(state.doneKm + state.remainingKm - total) < 1e-9)
            assertTrue(state.doneKm >= 0)
            assertTrue(state.remainingKm >= 0)
            assertTrue(kotlin.math.abs(state.distanceToRouteM - 0) < 0.001)
            assertFalse(state.offRoute)

            alongM += 50
        }

        val end = pos(2000.0)
        val endState = nav.update(lat = end.lat, lon = end.lon, now = 999999)
        assertTrue(kotlin.math.abs(endState.remainingKm - 0) < 1e-9)
        assertTrue(kotlin.math.abs(endState.doneKm - total) < 1e-9)
        assertEquals(20, endState.nearestIndex)
    }

    // --- Szenario 4: 300-Punkte-Route mit globalem Fallback ---

    @Test
    fun `Szenario 4 - Sprung ans Ende und zurueck wird gefunden`() {
        val route = straightRoute(count = 300)
        val nav = RouteNavigator(route)

        // Start am Routenanfang: Fenster ist [0, 50].
        val start = pos(0.0)
        val startState = nav.update(lat = start.lat, lon = start.lon, now = 0)
        assertEquals(0, startState.nearestIndex)
        assertTrue(kotlin.math.abs(startState.doneKm - 0) < 1e-9)

        // Sprung auf Punkt 290 - weit ausserhalb des Fensters.
        val jump = pos(290 * 100.0)
        val jumpState = nav.update(lat = jump.lat, lon = jump.lon, now = 1000)
        assertTrue(kotlin.math.abs(jumpState.distanceToRouteM - 0) < 0.01)
        assertEquals(290, jumpState.nearestIndex)
        assertTrue(kotlin.math.abs(jumpState.doneKm - 290.0 / 299 * nav.totalKm) < 1e-6)

        // Ruecksprung an den Anfang - ebenfalls nur global auffindbar.
        val back = pos(5 * 100.0)
        val backState = nav.update(lat = back.lat, lon = back.lon, now = 2000)
        assertTrue(kotlin.math.abs(backState.distanceToRouteM - 0) < 0.01)
        assertEquals(5, backState.nearestIndex)
        assertTrue(kotlin.math.abs(backState.doneKm - 5.0 / 299 * nav.totalKm) < 1e-6)
    }

    @Test
    fun `Szenario 4 - kleine Bewegung bleibt im Fenster`() {
        val nav = RouteNavigator(straightRoute(count = 300))
        val a = pos(150 * 100.0)
        nav.update(lat = a.lat, lon = a.lon, now = 0)

        val b = pos(150 * 100.0 + 250)
        val state = nav.update(lat = b.lat, lon = b.lon, now = 1000)
        assertTrue(state.nearestIndex == 152 || state.nearestIndex == 153)
        assertTrue(kotlin.math.abs(state.distanceToRouteM - 0) < 0.01)
    }

    // --- Szenario 5: Klemmung vor Start und hinter Ende ---

    @Test
    fun `Szenario 5 - vor dem Start bleibt doneKm bei 0`() {
        val nav = RouteNavigator(straightRoute())
        val p = pos(-500.0)
        val state = nav.update(lat = p.lat, lon = p.lon, now = 0)

        assertTrue(kotlin.math.abs(state.doneKm - 0) < 1e-9)
        assertTrue(kotlin.math.abs(state.remainingKm - nav.totalKm) < 1e-9)
        assertTrue(kotlin.math.abs(state.distanceToRouteM - 500) < 0.5)
        assertEquals(0, state.nearestIndex)
    }

    @Test
    fun `Szenario 5 - hinter dem Ende bleibt remainingKm bei 0`() {
        val nav = RouteNavigator(straightRoute())
        val p = pos(2500.0)
        val state = nav.update(lat = p.lat, lon = p.lon, now = 0)

        assertTrue(kotlin.math.abs(state.doneKm - nav.totalKm) < 1e-9)
        assertTrue(kotlin.math.abs(state.remainingKm - 0) < 1e-9)
        assertTrue(kotlin.math.abs(state.distanceToRouteM - 500) < 0.5)
        assertEquals(20, state.nearestIndex)
    }

    @Test
    fun `Szenario 5 - seitlich versetzt vor dem Start klemmt ebenfalls`() {
        val nav = RouteNavigator(straightRoute())
        val p = pos(-300.0, 400.0)
        val state = nav.update(lat = p.lat, lon = p.lon, now = 0)

        assertTrue(kotlin.math.abs(state.doneKm - 0) < 1e-9)
        assertTrue(kotlin.math.abs(state.remainingKm - nav.totalKm) < 1e-9)
        assertTrue(kotlin.math.abs(state.distanceToRouteM - 500) < 1)
    }
}
