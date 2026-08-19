package de.trailscape.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests fuer das Tourlast-Destillat (`RideLoadFacts.kt`): Die aus dem
 * Destillat rekonstruierte [RideLoad] muss fuer jede FTP dieselben Kennzahlen
 * liefern wie die direkte Punkt-Berechnung [computeRideLoad] — sonst wuerde
 * der persistente Cache die Lastskala verschieben.
 */
class RideLoadFactsTest {

    private val profile = TrainingProfile(ageYears = 40, weightKg = 78.0)

    private fun ride(hr: Int? = 140, speedMs: Double = 8.33, withEle: Boolean = true): Ride {
        val startMs = 1700000000000L
        val stepDeg = speedMs * 5.0 / 111_320.0
        val points = (0..720).map { i ->
            TrackPoint(
                lat = 48.0 + i * stepDeg,
                lon = 11.0,
                ele = if (withEle) 500.0 else null,
                time = startMs + i * 5_000L,
                hr = hr,
            )
        }
        return Ride(
            id = "r",
            name = "Tour",
            createdAt = startMs,
            points = points,
            stats = computeStats(points),
        )
    }

    private fun assertSameLoad(expected: RideLoad, actual: RideLoad) {
        assertEquals(expected.load, actual.load, 0.0)
        assertEquals(expected.source, actual.source)
        assertEquals(expected.confidence, actual.confidence)
        assertEquals(expected.note, actual.note)
        assertEquals(expected.heartRate.available, actual.heartRate.available)
        assertEquals(expected.heartRate.load, actual.heartRate.load, 0.0)
        assertEquals(expected.heartRate.secondsAboveLthr, actual.heartRate.secondsAboveLthr, 1e-9)
        assertEquals(expected.physics.available, actual.physics.available)
        assertEquals(expected.physics.eTss, actual.physics.eTss, 0.0)
        assertEquals(expected.physics.normalizedPowerW, actual.physics.normalizedPowerW, 0.0)
    }

    @Test
    fun `HF-Pfad - Rekonstruktion ist deckungsgleich zur Punkt-Berechnung`() {
        val r = ride(hr = 140)
        val facts = computeRideLoadFacts(r, profile)
        assertSameLoad(
            computeRideLoadForRide(r, profile, eftpW = 200.0),
            rideLoadFromFacts(facts, profile, eftpW = 200.0),
        )
    }

    @Test
    fun `Physik-Pfad - eTss folgt jeder FTP ohne Neuberechnung der Punkte`() {
        val r = ride(hr = null)
        val facts = computeRideLoadFacts(r, profile)
        for (ftp in listOf(125.0, 187.2, 250.0, 400.0)) {
            assertSameLoad(
                computeRideLoadForRide(r, profile, eftpW = ftp),
                rideLoadFromFacts(facts, profile, eftpW = ftp),
            )
        }
    }

    @Test
    fun `Heuristik-Pfad - ohne Hoehenprofil bleibt es bei der Stats-Schaetzung`() {
        val r = ride(hr = null, withEle = false)
        val facts = computeRideLoadFacts(r, profile)
        val expected = computeRideLoadForRide(r, profile, eftpW = 200.0)
        assertEquals(LoadSource.HEURISTIK, expected.source)
        assertSameLoad(expected, rideLoadFromFacts(facts, profile, eftpW = 200.0))
    }

    @Test
    fun `bestes 20-min-Mittel und Steady-Segmente stimmen mit der Reihe ueberein`() {
        val r = ride(hr = 140)
        val facts = computeRideLoadFacts(r, profile)
        val physics = computePhysicsEstimate(buildRideSeries(r.points, profile), profile)
        assertTrue(physics.available)
        assertEquals(bestRollingMeanPowerW(physics.series), facts.bestTwentyMinW)
        assertEquals(extractSteadySegments(physics.series, profile), facts.steadySegments)
    }

    @Test
    fun `Destillat und Cache-Eintrag ueberleben den JSON-Roundtrip`() {
        val facts = computeRideLoadFacts(ride(hr = 140), profile)
        val parsed = RideLoadFacts.fromJson(
            Json.parseToJsonElement(facts.toJson().toString()) as JsonObject,
        )
        assertEquals(facts, parsed)

        val entry = StoredRideLoadFacts(updatedAt = 42L, profileSignature = "sig", facts = facts)
        val parsedEntry = assertNotNull(
            StoredRideLoadFacts.fromJson(
                Json.parseToJsonElement(entry.toJson().toString()) as JsonObject,
            ),
        )
        assertEquals(entry, parsedEntry)
    }

    @Test
    fun `Notnagel aus der Zusammenfassung faellt auf die Stats-Heuristik zurueck`() {
        val r = ride(hr = 140)
        val load = rideLoadFromFacts(rideLoadFactsFromSummary(r.toSummary()), profile, eftpW = 200.0)
        assertEquals(LoadSource.HEURISTIK, load.source)
        assertTrue(load.load > 0.0)
    }
}
