package de.trailscape.app.indoor

import de.trailscape.app.data.RideStorage
import de.trailscape.core.RideStats
import de.trailscape.core.TrackPoint
import de.trailscape.core.indoor.IndoorMode
import de.trailscape.core.indoor.IndoorRideRecord
import de.trailscape.core.indoor.IndoorTotals
import de.trailscape.core.outdoorRides
import de.trailscape.core.riddenRides
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests fuer [indoorRideToRide] (`docs/indoor.md` 10.2): aus der fertigen
 * Aufzeichnung wird eine Tour mit `indoor = true`, zu kurze Fahrten werden
 * nicht gespeichert. Reine JVM-Tests ohne Android.
 */
class IndoorRideSaverTest {

    private val start = 1_750_000_000_000L

    private fun record(movingS: Int = 600, pointCount: Int = movingS): IndoorRideRecord {
        val points = (0 until pointCount).map {
            TrackPoint(
                lat = 50.0 + it * 1e-5,
                lon = 7.0,
                ele = 100.0,
                time = start + it * 1000L,
                power = 200,
                cad = 88,
            )
        }
        return IndoorRideRecord(
            startedAtEpochMs = start,
            routeId = "r1",
            routeName = "Hausrunde",
            mode = IndoorMode.SIM,
            points = points,
            stats = RideStats(distanceKm = 5.0, ascentM = 40.0, descentM = 40.0, durationS = movingS, movingTimeS = movingS),
            totals = IndoorTotals(
                distanceM = 5000.0,
                ascentM = 40.0,
                movingS = movingS,
                avgPowerW = 200.0,
                normalizedPowerW = 205.0,
                avgCadenceRpm = 88.0,
                avgHrBpm = null,
                workKj = 120.0,
            ),
            laps = 1,
            ghostFinalGapS = null,
        )
    }

    @Test
    fun `Aufzeichnung wird zur Indoor-Tour mit Punkten und Kennzahlen`() {
        val rec = record()
        val ride = assertNotNull(indoorRideToRide(rec, name = "Indoor · Hausrunde", id = "fest"))

        assertEquals("fest", ride.id)
        assertEquals("Indoor · Hausrunde", ride.name)
        assertEquals(start, ride.createdAt)
        assertEquals(rec.stats, ride.stats)
        assertEquals(rec.points, ride.points)
        assertTrue(ride.indoor)
        assertFalse(ride.planned)
        // Gefahren: zaehlt fuer Belastung und Statistik, nicht fuer Ortsbezogenes.
        assertEquals(listOf(ride), riddenRides(listOf(ride)))
        assertTrue(outdoorRides(listOf(ride)).isEmpty())
    }

    @Test
    fun `ohne ID gibt es jedes Mal eine neue`() {
        val a = assertNotNull(indoorRideToRide(record(), name = "x"))
        val b = assertNotNull(indoorRideToRide(record(), name = "x"))
        assertTrue(a.id.isNotBlank())
        assertTrue(a.id != b.id)
    }

    @Test
    fun `Fahrten unter 60 s werden nicht gespeichert`() {
        assertNull(indoorRideToRide(record(movingS = MIN_INDOOR_RIDE_S - 1), name = "x"))
        assertNotNull(indoorRideToRide(record(movingS = MIN_INDOOR_RIDE_S), name = "x"))
    }

    @Test
    fun `weniger als zwei Punkte werden nicht gespeichert`() {
        assertNull(indoorRideToRide(record(movingS = 600, pointCount = 1), name = "x"))
        assertNull(indoorRideToRide(record(movingS = 600, pointCount = 0), name = "x"))
    }

    @Test
    fun `Rundreise durch RideStorage behaelt Kennzeichen, Leistung und Trittfrequenz`() {
        val dir = createTempDirectory("indoor-saver").toFile()
        try {
            val ride = assertNotNull(indoorRideToRide(record(), name = "Indoor · Hausrunde", id = "i1"))
            RideStorage(dir).saveRide(ride)

            val fresh = RideStorage(dir)
            assertTrue(fresh.listSummaries().summaries.single().indoor)
            val loaded = assertNotNull(fresh.loadRide("i1"))
            assertTrue(loaded.indoor)
            assertEquals(200, loaded.points.first().power)
            assertEquals(88, loaded.points.first().cad)
        } finally {
            dir.deleteRecursively()
        }
    }
}
