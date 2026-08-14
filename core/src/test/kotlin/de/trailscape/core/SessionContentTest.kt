package de.trailscape.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Inhalt und Kilometer einer Einheit muessen zusammenpassen — und die
 * Intensitaet darf nicht mehr aus dem Titel zurueckgeraten werden.
 *
 * Vorher bekam „Intervalle" pauschal 0,2 × Wochenvolumen: bei einer 70-km-Woche
 * also 14 km, waehrend die Beschreibung 20 Minuten Einfahren, 4×8 Minuten
 * Belastung und 3×4 Minuten Pause verlangte (rund 70 Minuten bzw. 30 km). Und
 * `classifySessionIntensity` las die Intensitaet per Stichwortsuche im Titel
 * zurueck — eine Umformulierung in `Training.kt` haette das lautlos gebrochen.
 */
class SessionContentTest {
    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private val now: Long = dartEpochMs(LocalDateTime.of(2026, 1, 7, 12, 0))
    private val firstMonday: LocalDate = LocalDate.of(2026, 1, 5)

    private fun goalIn(weeks: Int, distanceKm: Double, ascentM: Double? = null) = Goal(
        name = "Zielevent",
        distanceKm = distanceKm,
        ascentM = ascentM,
        date = dartEpochMs(firstMonday.plusDays(weeks * 7L + 5).atTime(9, 0)),
    )

    private fun assessment(level: FitnessLevel, weeklyKm: Double) = FitnessAssessment(
        level = level,
        weeklyKm = weeklyKm,
        weeklyHm = weeklyKm * 8,
        weeklyRides = 3.0,
        longestRideKm = weeklyKm / 2,
        rideCount = 20,
    )

    private fun planFor(level: FitnessLevel, weeklyKm: Double, weeks: Int = 23): TrainingPlan =
        generatePlan(goalIn(weeks, 150.0, ascentM = 1800.0), assessment(level, weeklyKm), now)

    /** Alle Einheiten eines Plans mit dem gegebenen Titel. */
    private fun sessions(plan: TrainingPlan, title: String): List<TrainingSession> =
        plan.weeks.flatMap { it.sessions }.filter { it.title == title }

    // -----------------------------------------------------------------------
    // M2 — die Kilometer passen zur Beschreibung
    // -----------------------------------------------------------------------

    @Test
    fun `die Intervalleinheit ist so lang, wie ihre Beschreibung sagt`() {
        val plan = planFor(FitnessLevel.FORTGESCHRITTEN, 70.0)

        for (session in sessions(plan, "Intervalle")) {
            val minutes = assertNotNull(session.durationMin, "Dauer fehlt")

            // Der Text nennt Einfahren, Wiederholungen, Pause, Ausfahren und
            // die Gesamtdauer — und die Gesamtdauer ist genau `durationMin`.
            val reps = Regex("(\\d+)×(\\d+) Minuten").find(session.description)
            assertNotNull(reps, "Keine Intervallangabe in: ${session.description}")
            val count = reps.groupValues[1].toInt()
            val work = reps.groupValues[2].toInt()
            assertTrue(count in 3..5, "Unplausible Wiederholungszahl $count")
            assertTrue(session.description.contains("rund $minutes Minuten"))

            // Rechnerisch: 20 min Einfahren + count×work + (count−1)×4 + 10 min aus.
            assertEquals(20 + count * work + (count - 1) * 4 + 10, minutes)

            // Und die Kilometer entsprechen dieser Dauer bei Planungstempo
            // (18 km/h × 1,0 fuer eine harte Einheit).
            assertEquals(dartRound(minutes / 60.0 * 18.0).toInt(), session.targetKm)
        }
    }

    @Test
    fun `eine 70-km-Woche gibt der Intervalleinheit nicht mehr nur 14 km`() {
        // Genau der Fall aus dem Review.
        val plan = planFor(FitnessLevel.FORTGESCHRITTEN, 70.0)
        val week = plan.weeks.first { it.kind == WeekKind.AUFBAU && it.targetKm == 70 }
        val intervals = week.sessions.single { it.title == "Intervalle" }

        assertTrue(
            intervals.targetKm >= 18,
            "Intervalleinheit nur ${intervals.targetKm} km bei 70-km-Woche",
        )
        // Die uebrigen Kilometer der Woche bleiben bei den Einheiten, die
        // Volumen tragen sollen — die Wochensumme stimmt weiterhin.
        assertEquals(week.targetKm, week.sessions.sumOf { it.targetKm })
    }

    @Test
    fun `jede Einheit traegt eine Dauer, die zu ihren Kilometern passt`() {
        for (level in FitnessLevel.entries) {
            val plan = planFor(level, 90.0)
            for (session in plan.weeks.flatMap { it.sessions }) {
                if (session.isEvent) {
                    // Wie lange das Event dauert, entscheidet der Renntag.
                    assertNull(session.durationMin)
                    continue
                }
                val minutes = assertNotNull(session.durationMin, "${session.title} ohne Dauer")
                val speed = 18.0 * intensitySpeedFactor(session.intensity)
                // Die Kilometer werden gerundet, deshalb eine Minute Toleranz.
                assertTrue(
                    kotlin.math.abs(session.targetKm / speed * 60 - minutes) <= 4.0,
                    "${session.title}: ${session.targetKm} km vs. $minutes min",
                )
            }
        }
    }

    @Test
    fun `Wochensumme und Wochenziel bleiben deckungsgleich`() {
        for (level in FitnessLevel.entries) {
            val plan = planFor(level, 120.0)
            for (week in plan.weeks) {
                if (week.kind == WeekKind.ZIELWOCHE) continue
                val sum = week.sessions.sumOf { it.targetKm }
                assertTrue(
                    kotlin.math.abs(sum - week.targetKm) <= 2,
                    "$level Woche ${week.index}: $sum statt ${week.targetKm} km",
                )
            }
        }
    }

    // -----------------------------------------------------------------------
    // M2 — Intensitaet ist ein Feld, kein Textfund
    // -----------------------------------------------------------------------

    @Test
    fun `die Intensitaet steht am Datensatz und nicht im Titel`() {
        val plan = planFor(FitnessLevel.AMBITIONIERT, 120.0)
        val intervals = sessions(plan, "Intervalle").first()

        assertEquals(SessionIntensity.HART, intervals.intensity)
        assertEquals(SessionIntensity.HART, classifySessionIntensity(intervals))

        // Umformulierung des Titels: Die Klassifikation bleibt korrekt — frueher
        // waere daraus lautlos eine Grundlageneinheit geworden.
        val renamed = intervals.copy(title = "Schwellenblock")
        assertEquals(SessionIntensity.HART, classifySessionIntensity(renamed))
        assertEquals(SessionIntensity.HART, routeTargetForSession(renamed, TrainingProfile(40), emptyList()).intensity)

        // Das alte Stichwortverfahren gibt es nur noch fuer alte Plaene.
        assertEquals(SessionIntensity.GRUNDLAGE, sessionIntensityFromTitle("Schwellenblock"))
    }

    // -----------------------------------------------------------------------
    // Rueckwaertskompatibilitaet des Planformats
    // -----------------------------------------------------------------------

    @Test
    fun `neue Felder werden hinten angehaengt`() {
        val session = TrainingSession(
            day = "Di",
            title = "GA1",
            description = "Text",
            targetKm = 30,
            intensity = SessionIntensity.GRUNDLAGE,
            durationMin = 100,
        )

        assertEquals(
            listOf("day", "title", "description", "targetKm", "intensity", "durationMin"),
            session.toJson().keys.toList(),
        )
        assertEquals("grundlage", (session.toJson()["intensity"] as JsonPrimitive).content)
        assertEquals(session, TrainingSession.fromJson(session.toJson()))
    }

    @Test
    fun `isEvent wird nur geschrieben, wenn es zutrifft`() {
        val normal = TrainingSession("Di", "GA1", "Text", 30)
        assertFalse(normal.toJson().containsKey("isEvent"))
        assertFalse(normal.toJson().containsKey("durationMin"))

        val event = normal.copy(title = "Zielevent: X", isEvent = true)
        assertEquals(true, TrainingSession.fromJson(event.toJson()).isEvent)
    }

    @Test
    fun `ein Plan aus der Zeit vor den neuen Feldern bleibt lesbar`() {
        // Exakt das Format, das die Web-App und die Vorgaengerversion schreiben.
        val old = obj(
            """{"day":"Do","title":"Intervalle","description":"Nach 20 Minuten …","targetKm":14}""",
        )
        val session = TrainingSession.fromJson(old)

        assertEquals(14, session.targetKm)
        // Intensitaet faellt auf das alte Stichwortverfahren zurueck …
        assertEquals(SessionIntensity.HART, session.intensity)
        // … Dauer bleibt unbekannt, statt eine zu erfinden …
        assertNull(session.durationMin)
        assertFalse(session.isEvent)

        // … und das Zielevent wird an seinem festen Titelpraefix erkannt.
        val oldEvent = obj(
            """{"day":"Sa","title":"Zielevent: Gravel Grinder","description":"…","targetKm":200}""",
        )
        assertTrue(TrainingSession.fromJson(oldEvent).isEvent)
        assertFalse(canGenerateRouteFor(TrainingSession.fromJson(oldEvent)))
    }

    @Test
    fun `ein unbekannter Intensitaetswert macht den Plan nicht unlesbar`() {
        val json = obj(
            """{"day":"Di","title":"Ruhige Runde","description":"…","targetKm":20,""" +
                """"intensity":"was-auch-immer"}""",
        )

        assertEquals(SessionIntensity.LOCKER, TrainingSession.fromJson(json).intensity)
    }

    @Test
    fun `ein vollstaendiger Plan ueberlebt den Roundtrip mit allen neuen Feldern`() {
        val store = InMemoryTrainingPlanStore()
        val plan = planFor(FitnessLevel.AMBITIONIERT, 120.0)
        savePlan(store, plan)

        val loaded = assertNotNull(loadPlan(store))
        assertEquals(plan, loaded)
    }

    // -----------------------------------------------------------------------
    // M4 — das Zielevent ist markiert
    // -----------------------------------------------------------------------

    @Test
    fun `das Zielevent traegt isEvent und keine andere Einheit`() {
        val plan = planFor(FitnessLevel.FORTGESCHRITTEN, 95.0)
        val events = plan.weeks.flatMap { it.sessions }.filter { it.isEvent }

        assertEquals(1, events.size)
        assertTrue(events.single().title.startsWith("Zielevent:"))
        assertEquals(150, events.single().targetKm)
        assertFalse(canGenerateRouteFor(events.single()))

        // Und die Machbarkeitspruefung zaehlt es nicht als Trainingsfahrt mit.
        assertTrue(assessPlanFeasibility(plan).longestRideKm < 150)
    }
}
