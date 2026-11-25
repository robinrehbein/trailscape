package de.trailscape.core.indoor

import de.trailscape.core.challenge.ChallengeCurve
import de.trailscape.core.challenge.ChallengeParse
import de.trailscape.core.challenge.ChallengeRoute
import de.trailscape.core.challenge.createChallenge
import de.trailscape.core.challenge.parseChallenge
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Von der Fahrt zur Herausforderung und als Geist wieder zurueck. */
class ChallengeGhostTest {
    private val route = IndoorTestRoutes.straight(lengthM = 3000.0)

    @Test
    fun `eine Fahrt wird zur Kurve und die Datei traegt sie unveraendert`() {
        val ride = IndoorTestRoutes.pastRide(route, 300) { s -> 8.0 * s }
        val curve = assertNotNull(challengeCurveOf(route, ride))
        assertEquals(300.0, curve.totalS, 1.0)
        assertEquals(2400.0, curve.totalDistM, 12.0)
        val text = createChallenge(ChallengeRoute.TestWorld, curve, "Anna", "2026-11-25").toJson()
        val back = assertIs<ChallengeParse.Ok>(parseChallenge(text)).challenge
        assertEquals(curve, back.curve)
    }

    @Test
    fun `zu kurze Fahrt ergibt keine Herausforderung`() {
        assertNull(challengeCurveOf(route, IndoorTestRoutes.pastRide(route, 5) { s -> 8.0 * s }))
        assertNull(challengeCurveOf(route, emptyList()))
    }

    @Test
    fun `der geteilte Geist faehrt die Kurve nach`() {
        val ride = IndoorTestRoutes.pastRide(route, 300) { s -> 8.0 * s }
        val curve = assertNotNull(challengeCurveOf(route, ride))
        val ghost = assertNotNull(ghostRunner(GhostSpec.Shared("Anna", curve), route, IndoorTestRoutes.RIDER, IndoorWind.CALM))
        ghost.advance(60_000, 0.1)
        assertEquals(480.0, ghost.distM, 5.0)
        assertEquals(8.0, ghost.speedMps, 0.2)
        ghost.advance(400_000, 0.1)
        // Nach dem Ende der Kurve bleibt der Geist stehen.
        assertEquals(curve.totalDistM, ghost.distM, 1e-6)
    }

    @Test
    fun `Kurve aus Abtastwerten hat gleichmaessige Zeiten`() {
        val c = ChallengeCurve(5, 12.5, listOf(0, 400, 800, 1000))
        assertTrue(c.timesS().toList() == listOf(0.0, 5.0, 10.0, 12.5))
    }
}
