package de.trailscape.core.room

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DraftingTest {
    private fun other(id: String, distM: Double, lane: Int? = 0, stale: Boolean = false) = RiderPosition(
        id = id, name = id, isSelf = false, distM = distM, speedMps = 10.0, powerW = null, lane = lane,
        ageMs = 0, stale = stale, rank = 0, gapToLeaderM = 0.0, gapToSelfM = null, timeGapToSelfS = null,
    )

    @Test
    fun `Kurve an den Stuetzstellen und dazwischen`() {
        assertEquals(0.25, Drafting.reductionForGap(0.0), 1e-12)
        assertEquals(0.25, Drafting.reductionForGap(-3.0), 1e-12)
        assertEquals(0.22, Drafting.reductionForGap(1.0), 1e-12)
        assertEquals(0.185, Drafting.reductionForGap(1.5), 1e-12)
        assertEquals(0.15, Drafting.reductionForGap(2.0), 1e-12)
        assertEquals(0.08, Drafting.reductionForGap(3.0), 1e-12)
        assertEquals(0.04, Drafting.reductionForGap(4.0), 1e-12)
        assertEquals(0.0, Drafting.reductionForGap(5.0), 1e-12)
        assertEquals(0.0, Drafting.reductionForGap(50.0), 1e-12)
        assertEquals(0.0, Drafting.reductionForGap(Double.NaN), 1e-12)
    }

    @Test
    fun `Kurve faellt monoton und bleibt vorsichtig`() {
        var prev = 1.0
        var g = 0.0
        while (g <= 6.0) {
            val r = Drafting.reductionForGap(g)
            assertTrue(r <= prev + 1e-12, "monoton bei $g")
            assertTrue(r in 0.0..0.25)
            prev = r
            g += 0.05
        }
    }

    @Test
    fun `naechster Vordermann in derselben Spur zaehlt`() {
        val me = SelfPosition(distM = 100.0, speedMps = 10.0, lane = 0)
        val r = Drafting.factor(
            me,
            listOf(
                other("weit", 110.0),
                other("nah", 100.0 + Drafting.BIKE_LENGTH_M + 1.0),
                other("hinten", 99.0),
                other("daneben", 102.0, lane = 1),
            ),
        )
        assertEquals("nah", r.leaderId)
        assertEquals(1.0, r.wheelGapM!!, 1e-9)
        assertEquals(0.78, r.factor, 1e-9)
    }

    @Test
    fun `kein Windschatten ohne Vordermann, neben einem, bei stale oder anderer Spur`() {
        val me = SelfPosition(distM = 100.0, speedMps = 10.0)
        assertEquals(DraftingResult.NONE, Drafting.factor(me, emptyList()))
        assertEquals(DraftingResult.NONE, Drafting.factor(me, listOf(other("neben", 100.5))))
        assertEquals(DraftingResult.NONE, Drafting.factor(me, listOf(other("alt", 102.0, stale = true))))
        assertEquals(DraftingResult.NONE, Drafting.factor(me, listOf(other("links", 102.0, lane = -1))))
        // Fehlende Spur zählt als 0.
        assertEquals(0.75, Drafting.factor(me, listOf(other("ohne", 101.75, lane = null))).factor, 1e-9)
        assertEquals(1.0, Drafting.factor(me, listOf(other("weg", 120.0))).factor, 1e-9)
    }

    @Test
    fun `Glaetten ueber etwa eine Sekunde`() {
        // Nach einer Zeitkonstante ist rund 63 % des Sprungs erreicht.
        val after1s = Drafting.smoothFactor(1.0, 0.8, dtS = 1.0, tauS = 1.0)
        assertEquals(1.0 - 0.2 * (1 - kotlin.math.exp(-1.0)), after1s, 1e-9)
        var f = 1.0
        repeat(60) { f = Drafting.smoothFactor(f, 0.8, dtS = 1.0 / 60) }
        assertEquals(after1s, f, 1e-6)
        assertEquals(0.8, Drafting.smoothFactor(Double.NaN, 0.8, 0.1), 1e-12)
        assertEquals(0.9, Drafting.smoothFactor(0.9, 0.8, 0.0), 1e-12)
        assertEquals(0.75, Drafting.smoothFactor(0.75, 0.1, 10.0), 1e-12) // nie unter MIN_FACTOR
    }
}
