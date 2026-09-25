package de.trailscape.app.ui.map

import androidx.compose.runtime.saveable.SaverScope
import de.trailscape.core.PlannedRoute
import de.trailscape.core.RouteCandidate
import de.trailscape.core.TrackPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Schotteranteil und Gelaendeart in der Oberflaeche: die Kandidatenzeile des
 * Rundkurs-Generators und das Retten des Belags ueber Tabwechsel/Drehung
 * ([PlannedRouteSaver]). Reine JVM-Tests; das Bild dazu liefert
 * `RouteSurfaceScreenshotTest`.
 */
class RouteSurfaceTextTest {

    private val points = listOf(
        TrackPoint(lat = 51.0, lon = 13.0, ele = 100.0),
        TrackPoint(lat = 51.1, lon = 13.1),
    )

    private fun candidate(distanceKm: Double, ascentM: Double, targetKm: Double) = RouteCandidate(
        route = PlannedRoute(points, distanceKm, ascentM),
        distanceKm = distanceKm,
        ascentM = ascentM,
        score = 0.0,
        bearingDeg = 0.0,
        targetKm = targetKm,
    )

    @Test
    fun `Kandidatenzeile nennt zuerst die Gelaendeart`() {
        assertEquals("Flach · 5 Hm/km · ±0 % zum Ziel", candidateDetailLine(candidate(40.0, 200.0, 40.0)))
        assertEquals("Wellig · 12 Hm/km · +5,0 % zum Ziel", candidateDetailLine(candidate(42.0, 504.0, 40.0)))
        assertEquals("Bergig · 20 Hm/km · −10,0 % zum Ziel", candidateDetailLine(candidate(36.0, 720.0, 40.0)))
    }

    // ------------------------------------------------------- Saver

    private val scope = SaverScope { true }

    private fun roundTrip(route: PlannedRoute?): PlannedRoute? {
        val saved = with(PlannedRouteSaver) { scope.save(route) }
        return saved?.let { PlannedRouteSaver.restore(it) }
    }

    @Test
    fun `der Belag uebersteht das Retten`() {
        val restored = assertNotNull(roundTrip(PlannedRoute(points, 10.0, 80.0, pavedKm = 3.8, unpavedKm = 6.2)))
        assertEquals(3.8, restored.pavedKm)
        assertEquals(6.2, restored.unpavedKm)
        assertEquals(10.0, restored.distanceKm)
        assertEquals(points, restored.points)
    }

    @Test
    fun `eine Route ohne Belag bleibt ohne Belag`() {
        val restored = assertNotNull(roundTrip(PlannedRoute(points, 10.0, 80.0)))
        assertNull(restored.pavedKm)
        assertNull(restored.unpavedKm)
    }

    @Test
    fun `ein geretteter Zustand von vorher wird weiter gelesen`() {
        // Das alte Format: nur Distanz, Hoehe und Punkte.
        val old = listOf(12.5, 90.0, doubleArrayOf(51.0, 13.0, 100.0, 51.1, 13.1, Double.NaN))
        val restored = assertNotNull(PlannedRouteSaver.restore(old))
        assertEquals(12.5, restored.distanceKm)
        assertEquals(2, restored.points.size)
        assertNull(restored.pavedKm)
        assertNull(restored.unpavedKm)
    }
}
