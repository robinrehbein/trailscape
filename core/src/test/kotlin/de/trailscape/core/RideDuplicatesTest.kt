package de.trailscape.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests der Duplikatpruefung (`RideDuplicates.kt`), die vom Einzel-Import in
 * der App und vom Massenimport ([importArchive]) gemeinsam genutzt wird.
 *
 * Uebernommen aus `app/src/test/.../RideImportTest.kt`, als die Funktionen nach
 * `:core` gezogen wurden — dort bleibt der Test als Nachweis stehen, dass die
 * Weiterleitung im UI-Paket weiter funktioniert.
 */
class RideDuplicatesTest {

    private fun ride(id: String, createdAt: Long, pointCount: Int) = Ride(
        id = id,
        name = "Tour $id",
        createdAt = createdAt,
        stats = RideStats(distanceKm = 1.0, ascentM = 0.0, descentM = 0.0),
        points = (0 until pointCount).map {
            TrackPoint(lat = 52.0 + it / 1000.0, lon = 13.0, time = createdAt + it * 1000L)
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
        val again = ride("b", createdAt = 1_000L, pointCount = 3)

        assertTrue(isDuplicateRide(existing, again))
        assertEquals("a", findDuplicateRide(existing, again)?.id)
    }

    @Test
    fun `gleicher Startzeitpunkt aber andere Punktzahl ist kein Duplikat`() {
        val existing = listOf(ride("a", createdAt = 1_000L, pointCount = 3))
        assertFalse(isDuplicateRide(existing, ride("b", createdAt = 1_000L, pointCount = 4)))
    }

    @Test
    fun `andere Startzeit ist kein Duplikat`() {
        val existing = listOf(ride("a", createdAt = 1_000L, pointCount = 3))
        assertFalse(isDuplicateRide(existing, ride("b", createdAt = 2_000L, pointCount = 3)))
    }

    @Test
    fun `leerer Bestand hat nie ein Duplikat`() {
        assertNull(findDuplicateRide(emptyList(), ride("a", createdAt = 1_000L, pointCount = 3)))
    }

    @Test
    fun `Meldung ist gesetzt`() {
        assertTrue(DUPLICATE_RIDE_MESSAGE.isNotBlank())
    }
}
