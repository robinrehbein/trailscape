package de.trailscape.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests fuer [RideSummary] — den punktfreien Index-Eintrag einer Tour — und
 * seine Ableitung aus [Ride].
 */
class RideSummaryTest {

    private fun ride(): Ride = Ride(
        id = "r1",
        name = "Alpencross",
        createdAt = 1700000000000L,
        points = listOf(
            TrackPoint(lat = 47.1, lon = 11.6, ele = 1234.5, time = 1700000000000L, hr = 142),
            TrackPoint(lat = 47.2, lon = 11.7, time = 1700000060000L),
        ),
        stats = RideStats(
            distanceKm = 12.3,
            durationS = 600,
            movingTimeS = 580,
            avgSpeedKmh = 21.0,
            ascentM = 300.0,
            descentM = 100.0,
            avgHrBpm = 143,
            maxHrBpm = 150,
        ),
        planned = false,
        updatedAt = 1700000999000L,
    )

    @Test
    fun `toSummary uebernimmt alle Kerndaten inklusive Punktzahl`() {
        val summary = ride().toSummary()
        assertEquals("r1", summary.id)
        assertEquals("Alpencross", summary.name)
        assertEquals(1700000000000L, summary.createdAt)
        assertEquals(1700000999000L, summary.updatedAt)
        assertEquals(2, summary.pointCount)
        assertFalse(summary.planned)
        assertEquals(ride().stats, summary.stats)
    }

    @Test
    fun `JSON-Roundtrip erhaelt alle Felder`() {
        val summary = ride().toSummary().copy(planned = true)
        val json = summary.toJson().toString()
        val parsed = RideSummary.fromJson(Json.parseToJsonElement(json) as JsonObject)
        assertEquals(summary, parsed)
    }

    @Test
    fun `fromJson toleriert fehlende optionale Felder`() {
        val raw = """{"id":"x","name":"Alt","createdAt":1000,"stats":{"distanceKm":5.0,"ascentM":10.0,"descentM":0.0}}"""
        val parsed = RideSummary.fromJson(Json.parseToJsonElement(raw) as JsonObject)
        assertEquals("x", parsed.id)
        // Fehlendes updatedAt faellt wie bei Ride.fromJson auf createdAt zurueck.
        assertEquals(1000L, parsed.updatedAt)
        assertEquals(0, parsed.pointCount)
        assertFalse(parsed.planned)
        assertEquals(5.0, parsed.stats.distanceKm)
    }

    @Test
    fun `Duplikatpruefung arbeitet auch ueber Zusammenfassungen`() {
        val full = ride()
        val summaries = listOf(full.toSummary().copy(id = "anders"))
        // Gleicher Start + gleiche Punktzahl -> Duplikat, obwohl die IDs
        // verschieden sind und keine Punktlisten vorliegen.
        assertTrue(isDuplicateRide(summaries, full))
        // Anderer Start -> kein Duplikat.
        assertFalse(isDuplicateRide(summaries, full.copy(createdAt = 1L)))
    }

    @Test
    fun `riddenRides filtert auch Zusammenfassungen`() {
        val gefahren = ride().toSummary()
        val geplant = ride().toSummary().copy(id = "p", planned = true)
        assertEquals(listOf(gefahren), riddenRides(listOf(gefahren, geplant)))
    }
}
