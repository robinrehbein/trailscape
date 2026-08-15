package de.trailscape.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Eine gespeicherte **Planung** ist keine gefahrene Tour.
 *
 * „Als Tour speichern" auf der Karte legte bis hierher eine ganz gewoehnliche
 * [Ride] an; danach meldete die Startseite die geplanten Kilometer als
 * gefahren, der Wochenfortschritt sprang, und die Trainingsauswertung rechnete
 * mit einer Fahrt, die es nie gab.
 *
 * Geprueft wird beides: dass [Ride.planned] ueberall greift, wo „gefahren"
 * gemeint ist — **und** dass das JSON gefahrener Touren dabei byteweise
 * unveraendert bleibt (Sync-Server, Web-App, bestehende Sicherungen).
 */
class PlannedRideTest {
    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun ride(
        id: String,
        createdAt: Long,
        distanceKm: Double,
        planned: Boolean = false,
        avgSpeedKmh: Double? = 20.0,
    ): Ride = Ride(
        id = id,
        name = if (planned) "Geplante Route" else "Tour",
        createdAt = createdAt,
        stats = RideStats(
            distanceKm = distanceKm,
            ascentM = 0.0,
            descentM = 0.0,
            avgSpeedKmh = avgSpeedKmh,
        ),
        planned = planned,
    )

    // -----------------------------------------------------------------------
    // Rueckwaertskompatibilitaet des JSON-Formats
    // -----------------------------------------------------------------------

    @Test
    fun `eine gefahrene Tour schreibt den neuen Schluessel gar nicht`() {
        val json = ride("abc", 1_700_000_000_000, 12.3).toJson()

        assertFalse(json.containsKey("planned"))
        // Reihenfolge und Umfang der bisherigen Schluessel bleiben unangetastet.
        assertEquals(listOf("id", "name", "createdAt", "points", "stats"), json.keys.toList())
    }

    @Test
    fun `eine Planung schreibt den Schluessel hinten an`() {
        val json = ride("abc", 1, 12.3, planned = true).toJson()

        assertEquals(
            listOf("id", "name", "createdAt", "points", "stats", "planned"),
            json.keys.toList(),
        )
        assertEquals(true, Ride.fromJson(json).planned)
    }

    @Test
    fun `alte Dateien ohne den Schluessel gelten als gefahren`() {
        val json = obj(
            """{"id":"x","name":"n","createdAt":1,"points":[],""" +
                """"stats":{"distanceKm":10.0,"ascentM":0.0,"descentM":0.0}}""",
        )

        assertFalse(Ride.fromJson(json).planned)
    }

    @Test
    fun `planned ueberlebt den Roundtrip und toleriert String-Codierung`() {
        val planning = ride("p", 1, 55.0, planned = true)
        assertEquals(planning, Ride.fromJson(planning.toJson()))

        // Dart codiert Wahrheitswerte je nach Weg auch als String.
        val asString = obj(
            """{"id":"x","name":"n","createdAt":1,"points":[],"planned":"true"}""",
        )
        assertTrue(Ride.fromJson(asString).planned)
    }

    // -----------------------------------------------------------------------
    // Ueberall filtern, wo „gefahren" gemeint ist
    // -----------------------------------------------------------------------

    @Test
    fun `riddenRides laesst Planungen weg`() {
        val list = listOf(
            ride("a", 1, 10.0),
            ride("b", 2, 90.0, planned = true),
            ride("c", 3, 20.0),
        )

        assertEquals(listOf("a", "c"), riddenRides(list).map { it.id })
    }

    @Test
    fun `der Wochenfortschritt springt durch eine gespeicherte Planung nicht`() {
        val now = dartEpochMs(LocalDateTime.of(2026, 1, 7, 12, 0))
        val plan = generatePlan(
            Goal(
                name = "Ziel",
                distanceKm = 120.0,
                ascentM = null,
                date = dartEpochMs(LocalDate.of(2026, 1, 5).plusDays(11 * 7L + 5).atTime(9, 0)),
            ),
            FitnessAssessment(FitnessLevel.FORTGESCHRITTEN, 95.0, 900.0, 2.0, 80.0, 16),
            now = now,
        )
        val week = plan.weeks.first()

        val gefahren = ride("a", week.start + 1000, 30.0)
        val geplant = ride("b", week.start + 2000, 90.0, planned = true)

        assertEquals(30.0, weekKm(week, listOf(gefahren, geplant)))
        assertEquals(30.0, weekKm(week, listOf(gefahren)))
    }

    @Test
    fun `die Fitness-Einschaetzung zaehlt Planungen nicht mit`() {
        val now = 1_700_000_000_000L
        val day = 24L * 60 * 60 * 1000

        val onlyPlanned = assessFitness(
            listOf(ride("p", now - day, 200.0, planned = true)),
            now = now,
        )
        assertEquals(0, onlyPlanned.rideCount)
        assertEquals(0.0, onlyPlanned.weeklyKm)
        assertEquals(0.0, onlyPlanned.longestRideKm)

        // Und sie hebt auch die laengste Fahrt nicht an, wenn echte dabei sind.
        val mixed = assessFitness(
            listOf(
                ride("a", now - day, 40.0),
                ride("p", now - 2 * day, 200.0, planned = true),
            ),
            now = now,
        )
        assertEquals(1, mixed.rideCount)
        assertEquals(40.0, mixed.longestRideKm)
    }

    @Test
    fun `der Geschwindigkeits-Median ignoriert Planungen`() {
        val real = List(3) { ride("r$it", it.toLong(), 40.0, avgSpeedKmh = 20.0) }
        val planning = ride("p", 99, 40.0, planned = true, avgSpeedKmh = 40.0)

        assertEquals(20.0, typicalAvgSpeedKmh(real + planning)!!, 1e-9)
    }

    @Test
    fun `der Anstupser verstummt nicht, weil jemand eine Route abgelegt hat`() {
        val today = LocalDateTime.of(2026, 3, 20, 8, 30)
        val settings = ReminderSettings(
            dailySessionEnabled = false,
            weeklyReviewEnabled = false,
            nudgeEnabled = true,
        )
        val longAgo = dartEpochMs(today.toLocalDate().minusDays(20).atTime(10, 0))
        val yesterday = dartEpochMs(today.toLocalDate().minusDays(1).atTime(10, 0))

        val notice = dueReminder(
            now = today,
            settings = settings,
            state = ReminderState(),
            plan = null,
            rides = listOf(
                ride("alt", longAgo, 30.0),
                ride("plan", yesterday, 80.0, planned = true),
            ),
        )

        assertEquals(ReminderKind.ANSTUPSER, notice?.kind)
    }
}
