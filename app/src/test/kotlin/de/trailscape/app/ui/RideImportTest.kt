package de.trailscape.app.ui

import de.trailscape.core.Ride
import de.trailscape.core.RideStats
import de.trailscape.core.TrackPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests der Duplikatpruefung fuer importierte Touren (`ui/RideImport.kt`).
 *
 * Reiner JVM-Test: Die geprueften Funktionen haben keinen Android-Import.
 */
class RideImportTest {

    private fun ride(
        id: String,
        createdAt: Long,
        pointCount: Int,
    ) = Ride(
        id = id,
        name = "Tour $id",
        createdAt = createdAt,
        stats = RideStats(distanceKm = 1.0, ascentM = 0.0, descentM = 0.0),
        points = (0 until pointCount).map {
            TrackPoint(lat = 52.0 + it / 1000.0, lon = 13.0, ele = null, time = createdAt + it * 1000L)
        },
    )

    @Test
    fun `gleiche ID gilt als Duplikat`() {
        val existing = listOf(ride("a", createdAt = 1_000L, pointCount = 3))

        assertTrue(isDuplicateRide(existing, ride("a", createdAt = 9_999L, pointCount = 7)))
    }

    @Test
    fun `gleicher Startzeitpunkt und gleiche Punktzahl gelten als Duplikat`() {
        val existing = listOf(ride("a", createdAt = 1_000L, pointCount = 3))

        // Genau der Fall aus dem Review: derselbe GPX-Import ein zweites Mal —
        // rideFromGpx vergibt dabei jedes Mal eine frische ID.
        val again = ride("1723118400000", createdAt = 1_000L, pointCount = 3)

        assertTrue(isDuplicateRide(existing, again))
        assertEquals("a", findDuplicateRide(existing, again)?.id)
    }

    @Test
    fun `gleicher Startzeitpunkt mit anderer Punktzahl ist kein Duplikat`() {
        val existing = listOf(ride("a", createdAt = 1_000L, pointCount = 3))

        assertFalse(isDuplicateRide(existing, ride("b", createdAt = 1_000L, pointCount = 4)))
    }

    @Test
    fun `gleiche Punktzahl mit anderem Startzeitpunkt ist kein Duplikat`() {
        val existing = listOf(ride("a", createdAt = 1_000L, pointCount = 3))

        assertFalse(isDuplicateRide(existing, ride("b", createdAt = 2_000L, pointCount = 3)))
    }

    @Test
    fun `ohne bestehende Touren gibt es kein Duplikat`() {
        assertNull(findDuplicateRide(emptyList(), ride("a", createdAt = 1_000L, pointCount = 3)))
    }
}
