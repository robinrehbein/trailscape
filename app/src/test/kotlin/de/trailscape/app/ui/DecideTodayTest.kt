package de.trailscape.app.ui

import de.trailscape.app.ui.today.TodayEffort
import de.trailscape.core.FitnessLevel
import de.trailscape.core.Goal
import de.trailscape.core.SessionIntensity
import de.trailscape.core.TrainingPlan
import de.trailscape.core.TrainingSession
import de.trailscape.core.TrainingWeek
import de.trailscape.core.WeekKind
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [decideToday] als ganze Kette: aus Plan und Uhrzeit bis zum Angebot.
 *
 * Die Wortlaut-Tests pruefen `offeredTarget`/`todayEffort` mit fertigen
 * Eingaben; die eigentliche Konsistenzgarantie — „Heute", Karte und Dialog
 * rechnen dieselbe Tagesart — haengt aber daran, dass `currentWeek` und
 * `planRestDay` aus `nowMs` richtig entstehen, auch vor Planbeginn und nach
 * Planende.
 */
class DecideTodayTest {
    private val zone = ZoneId.systemDefault()

    /** Montag der ersten Planwoche. */
    private val monday = LocalDate.of(2026, 9, 7)

    private fun ms(date: LocalDate): Long = date.atTime(LocalTime.NOON).atZone(zone).toInstant().toEpochMilli()
    private fun startMs(date: LocalDate): Long = date.atStartOfDay(zone).toInstant().toEpochMilli()

    /**
     * Zwei Wochen: in der ersten eine Grundlagenfahrt am Mittwoch, in der
     * zweiten das Zielevent am Samstag. Alle anderen Tage sind Plan-Ruhetage.
     */
    private val plan = TrainingPlan(
        createdAt = startMs(monday),
        goal = Goal(name = "Gravel-Marathon", distanceKm = 100.0, date = ms(monday.plusDays(12))),
        level = FitnessLevel.entries.first(),
        weeks = listOf(
            TrainingWeek(
                index = 0,
                start = startMs(monday),
                end = startMs(monday.plusDays(7)),
                kind = WeekKind.AUFBAU,
                targetKm = 40,
                sessions = listOf(
                    TrainingSession(
                        day = "Mi",
                        title = "Grundlage",
                        description = "Ruhig fahren.",
                        targetKm = 40,
                        intensity = SessionIntensity.GRUNDLAGE,
                        durationMin = 100,
                    ),
                ),
            ),
            TrainingWeek(
                index = 1,
                start = startMs(monday.plusDays(7)),
                end = startMs(monday.plusDays(14)),
                kind = WeekKind.entries.last(),
                targetKm = 100,
                sessions = listOf(
                    TrainingSession(
                        day = "Sa",
                        title = "Zielevent: Gravel-Marathon",
                        description = "Viel Erfolg.",
                        targetKm = 100,
                        isEvent = true,
                    ),
                ),
            ),
        ),
    )

    private val insights = emptyTrainingInsights(now = monday.atTime(LocalTime.NOON))

    private fun decide(day: LocalDate, withPlan: Boolean = true) =
        decideToday(insights, if (withPlan) plan else null, emptyList(), ms(day))

    @Test
    fun `Plan-Ruhetag bietet ueberall die lockere Runde`() {
        val d = decide(monday.plusDays(1)) // Dienstag, keine Einheit
        assertNotNull(d.currentWeek)
        assertNull(d.todaySession)
        assertTrue(d.planRestDay)
        assertEquals(TodayEffort.RUHETAG, d.effort)
        assertEquals(true, d.offer?.restDay)
        assertEquals("Heute ist Ruhetag.", d.restHeadline)
    }

    @Test
    fun `Fahrtag im Plan bietet die Trainingsrunde`() {
        val d = decide(monday.plusDays(2)) // Mittwoch
        assertNotNull(d.todaySession)
        assertFalse(d.planRestDay)
        assertEquals(false, d.offer?.restDay)
    }

    @Test
    fun `Zieltag bietet keine Runde`() {
        val d = decide(monday.plusDays(12)) // Samstag der Zielwoche
        assertEquals(TodayEffort.ZIELTAG, d.effort)
        assertNull(d.offer)
    }

    @Test
    fun `vor Planbeginn und nach Planende gibt es keinen Plan-Ruhetag`() {
        for (day in listOf(monday.minusDays(3), monday.plusDays(20))) {
            val d = decide(day)
            assertNull(d.currentWeek, "$day")
            assertFalse(d.planRestDay, "$day")
            assertEquals(false, d.offer?.restDay, "$day")
            // Dasselbe Angebot wie ganz ohne Plan.
            assertEquals(decide(day, withPlan = false).offer, d.offer, "$day")
        }
    }

    @Test
    fun `naechster Tagesanfang auch in der Zeitumstellungsnacht`() {
        val berlin = ZoneId.of("Europe/Berlin")
        // 25.10.2026: Die Nacht hat 25 Stunden; von 23 Uhr am Vorabend bis
        // Mitternacht bleibt trotzdem genau eine Stunde.
        assertEquals(3_600_000L, millisUntilNextDay(LocalDate.of(2026, 10, 24).atTime(23, 0), berlin))
        // Am Umstellungstag selbst: von 12 Uhr bis Mitternacht 12 Stunden.
        assertEquals(12 * 3_600_000L, millisUntilNextDay(LocalDate.of(2026, 10, 25).atTime(12, 0), berlin))
    }
}
