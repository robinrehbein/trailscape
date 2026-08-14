package de.trailscape.core

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Tests fuer `Reminders.kt` — welche Erinnerung wann faellig ist und wie ihr
 * Text lautet.
 *
 * Die Plaene kommen wo immer moeglich aus [generatePlan] und nicht aus von
 * Hand gebauten [TrainingWeek]-Objekten: Die Erinnerung soll gegen dieselben
 * Einheiten laufen, die der Nutzer im Trainings-Tab sieht — inklusive der
 * echten Titel und Wochenarten.
 *
 * Kalendarische Fixpunkte: **2026-03-02 ist ein Montag**, 2026-03-03 ein
 * Dienstag, 2026-03-08 ein Sonntag. In Europa wird 2026 in der Nacht auf
 * Sonntag, den 29. März, auf Sommerzeit gestellt.
 */
class RemindersTest {

    private companion object {
        val MONDAY: LocalDate = LocalDate.of(2026, 3, 2)
        val TUESDAY: LocalDate = LocalDate.of(2026, 3, 3)
        val SUNDAY: LocalDate = LocalDate.of(2026, 3, 8)

        val allEnabled = ReminderSettings(
            dailySessionEnabled = true,
            weeklyReviewEnabled = true,
            nudgeEnabled = true,
        )

        fun at(date: LocalDate, hour: Int, minute: Int = 0): LocalDateTime =
            date.atTime(hour, minute)

        fun ride(at: LocalDateTime, km: Double = 20.0): Ride = Ride(
            id = "r-$at",
            name = "Tour",
            createdAt = dartEpochMs(at),
            stats = RideStats(distanceKm = km, ascentM = 0.0, descentM = 0.0),
        )

        /**
         * Plan mit 13 Wochen (Montag der Woche von [start] bis zur Zielwoche),
         * Stufe „Fortgeschritten" — dessen Aufbauwochen haben Einheiten am
         * Di, Do und Sa, der Montag und der Sonntag sind Ruhetage.
         */
        fun planFrom(start: LocalDate, goalWeeksAhead: Long = 12): TrainingPlan = generatePlan(
            goal = Goal(
                name = "Gravel-Marathon",
                distanceKm = 120.0,
                ascentM = 800.0,
                date = dartEpochMs(start.plusWeeks(goalWeeksAhead).atTime(9, 0)),
            ),
            assessment = FitnessAssessment(
                level = FitnessLevel.FORTGESCHRITTEN,
                weeklyKm = 120.0,
                weeklyHm = 900.0,
                weeklyRides = 3.0,
                longestRideKm = 70.0,
                rideCount = 20,
            ),
            now = dartEpochMs(start.atTime(9, 0)),
        )

        fun parse(raw: String): JsonObject = Json.parseToJsonElement(raw) as JsonObject
    }

    // -----------------------------------------------------------------------
    // Grundfaelle
    // -----------------------------------------------------------------------

    @Test
    fun `ohne eingeschalteten Anlass bleibt alles still`() {
        val notice = dueReminder(
            now = at(TUESDAY, 7, 0),
            settings = ReminderSettings(),
            state = ReminderState(),
            plan = planFrom(MONDAY),
            rides = listOf(ride(at(TUESDAY.minusDays(30), 10))),
        )
        assertNull(notice)
    }

    @Test
    fun `Vorgabe ist aus`() {
        val defaults = ReminderSettings()
        assertEquals(false, defaults.dailySessionEnabled)
        assertEquals(false, defaults.weeklyReviewEnabled)
        assertEquals(false, defaults.nudgeEnabled)
        assertEquals(false, defaults.anyEnabled)
        assertEquals(LocalTime.of(7, 0), defaults.dailySessionTime)
        assertEquals(LocalTime.of(18, 0), defaults.weeklyReviewTime)
    }

    // -----------------------------------------------------------------------
    // Tageseinheit
    // -----------------------------------------------------------------------

    @Test
    fun `Tageseinheit meldet Titel und Kilometer der geplanten Einheit`() {
        val plan = planFrom(MONDAY)
        val now = at(TUESDAY, 7, 0)
        val session = sessionsForDay(plan, dartEpochMs(now)).first()

        val notice = dueReminder(
            now = now,
            settings = ReminderSettings(dailySessionEnabled = true),
            state = ReminderState(),
            plan = plan,
            rides = emptyList(),
        )

        assertNotNull(notice)
        assertEquals(ReminderKind.TAGESEINHEIT, notice.kind)
        assertEquals("Heute", notice.title)
        assertEquals("${session.title}, ${session.targetKm} km", notice.text)
    }

    @Test
    fun `ohne Trainingsplan kommt keine Tageseinheit`() {
        val notice = dueReminder(
            now = at(TUESDAY, 7, 0),
            settings = ReminderSettings(dailySessionEnabled = true),
            state = ReminderState(),
            plan = null,
            rides = emptyList(),
        )
        assertNull(notice)
    }

    @Test
    fun `am Ruhetag kommt der Ruhetag-Hinweis`() {
        val plan = planFrom(MONDAY)
        // Montag ist in einer Aufbauwoche dieses Plans frei.
        assertTrue(sessionsForDay(plan, dartEpochMs(at(MONDAY, 7, 0))).isEmpty())

        val notice = dueReminder(
            now = at(MONDAY, 7, 0),
            settings = ReminderSettings(dailySessionEnabled = true),
            state = ReminderState(),
            plan = plan,
            rides = emptyList(),
        )

        assertNotNull(notice)
        assertEquals(ReminderKind.TAGESEINHEIT, notice.kind)
        assertEquals("Ruhetag — im Plan steht heute keine Einheit.", notice.text)
    }

    @Test
    fun `abgelaufener Plan meldet nichts - auch keinen Ruhetag`() {
        val plan = planFrom(MONDAY)
        val afterEnd = dartLocalOf(plan.weeks.last().end).toLocalDate().plusDays(3)

        val notice = dueReminder(
            now = at(afterEnd, 7, 0),
            settings = allEnabled,
            state = ReminderState(),
            plan = plan,
            rides = emptyList(),
        )
        assertNull(notice)
    }

    @Test
    fun `vor Planbeginn meldet nichts`() {
        val plan = planFrom(MONDAY)

        val notice = dueReminder(
            now = at(MONDAY.minusWeeks(1), 7, 0),
            settings = ReminderSettings(dailySessionEnabled = true),
            state = ReminderState(),
            plan = plan,
            rides = emptyList(),
        )
        assertNull(notice)
    }

    @Test
    fun `vor der eingestellten Uhrzeit ist nichts faellig`() {
        val notice = dueReminder(
            now = at(TUESDAY, 6, 59),
            settings = ReminderSettings(dailySessionEnabled = true),
            state = ReminderState(),
            plan = planFrom(MONDAY),
            rides = emptyList(),
        )
        assertNull(notice)
    }

    @Test
    fun `ein verspaeteter Lauf holt die Tageseinheit am selben Tag nach`() {
        val notice = dueReminder(
            now = at(TUESDAY, 11, 40),
            settings = ReminderSettings(dailySessionEnabled = true),
            state = ReminderState(),
            plan = planFrom(MONDAY),
            rides = emptyList(),
        )
        assertNotNull(notice)
        assertEquals(ReminderKind.TAGESEINHEIT, notice.kind)
    }

    @Test
    fun `die Tageseinheit kommt nur einmal am Tag`() {
        val notice = dueReminder(
            now = at(TUESDAY, 9, 0),
            settings = ReminderSettings(dailySessionEnabled = true),
            state = ReminderState(lastDailySessionOn = TUESDAY),
            plan = planFrom(MONDAY),
            rides = emptyList(),
        )
        assertNull(notice)
    }

    @Test
    fun `die Meldung vom Vortag blockiert den naechsten Morgen nicht`() {
        val notice = dueReminder(
            now = at(TUESDAY, 7, 0),
            settings = ReminderSettings(dailySessionEnabled = true),
            state = ReminderState(lastDailySessionOn = MONDAY),
            plan = planFrom(MONDAY),
            rides = emptyList(),
        )
        assertNotNull(notice)
    }

    @Test
    fun `eigene Uhrzeit wird beachtet`() {
        val settings = ReminderSettings(
            dailySessionEnabled = true,
            dailySessionTime = LocalTime.of(5, 30),
        )
        val plan = planFrom(MONDAY)

        assertNull(
            dueReminder(at(TUESDAY, 5, 29), settings, ReminderState(), plan, emptyList()),
        )
        assertNotNull(
            dueReminder(at(TUESDAY, 5, 30), settings, ReminderState(), plan, emptyList()),
        )
    }

    // -----------------------------------------------------------------------
    // Wochenrueckschau
    // -----------------------------------------------------------------------

    @Test
    fun `Wochenrueckschau vergleicht gefahrene und geplante Kilometer`() {
        val plan = planFrom(MONDAY)
        val week = plan.weeks.first()
        val rides = listOf(
            ride(at(TUESDAY, 17), km = 30.0),
            ride(at(LocalDate.of(2026, 3, 7), 10), km = 33.4),
            // Vorwoche — zaehlt nicht mit.
            ride(at(MONDAY.minusDays(2), 10), km = 99.0),
        )

        val notice = dueReminder(
            now = at(SUNDAY, 18, 0),
            settings = ReminderSettings(weeklyReviewEnabled = true),
            state = ReminderState(),
            plan = plan,
            rides = rides,
        )

        assertNotNull(notice)
        assertEquals(ReminderKind.WOCHENRUECKSCHAU, notice.kind)
        assertEquals("Wochenrückschau", notice.title)
        assertEquals("Diese Woche: 63 von ${week.targetKm} km gefahren.", notice.text)
    }

    @Test
    fun `Wochenrueckschau ohne gefahrene Kilometer meldet null`() {
        val plan = planFrom(MONDAY)

        val notice = dueReminder(
            now = at(SUNDAY, 18, 0),
            settings = ReminderSettings(weeklyReviewEnabled = true),
            state = ReminderState(),
            plan = plan,
            rides = emptyList(),
        )

        assertNotNull(notice)
        assertEquals("Diese Woche: 0 von ${plan.weeks.first().targetKm} km gefahren.", notice.text)
    }

    @Test
    fun `Wochenrueckschau nur am Sonntag`() {
        val notice = dueReminder(
            now = at(LocalDate.of(2026, 3, 7), 18, 0),
            settings = ReminderSettings(weeklyReviewEnabled = true),
            state = ReminderState(),
            plan = planFrom(MONDAY),
            rides = emptyList(),
        )
        assertNull(notice)
    }

    @Test
    fun `Wochenrueckschau nicht vor der eingestellten Uhrzeit`() {
        val notice = dueReminder(
            now = at(SUNDAY, 17, 59),
            settings = ReminderSettings(weeklyReviewEnabled = true),
            state = ReminderState(),
            plan = planFrom(MONDAY),
            rides = emptyList(),
        )
        assertNull(notice)
    }

    @Test
    fun `Wochenrueckschau ohne Plan bleibt still`() {
        val notice = dueReminder(
            now = at(SUNDAY, 18, 0),
            settings = ReminderSettings(weeklyReviewEnabled = true),
            state = ReminderState(),
            plan = null,
            rides = listOf(ride(at(TUESDAY, 17))),
        )
        assertNull(notice)
    }

    @Test
    fun `Wochenrueckschau kommt nur einmal am Sonntag`() {
        val notice = dueReminder(
            now = at(SUNDAY, 20, 0),
            settings = ReminderSettings(weeklyReviewEnabled = true),
            state = ReminderState(lastWeeklyReviewOn = SUNDAY),
            plan = planFrom(MONDAY),
            rides = emptyList(),
        )
        assertNull(notice)
    }

    // -----------------------------------------------------------------------
    // Sonntag mit zwei Anlaessen
    // -----------------------------------------------------------------------

    @Test
    fun `am Sonntag kommen Einheit und Rueckschau in getrennten Laeufen`() {
        // Der Sonntag ist in diesem Plan ein Ruhetag — die Tageseinheit meldet
        // sich morgens also mit dem Ruhetag-Hinweis. Worum es hier geht, ist
        // die Verteilung auf zwei Laeufe: Die Rueckschau am Abend verdraengt
        // die Morgenmeldung nicht und umgekehrt.
        val plan = planFrom(MONDAY)
        val sundayInPlan = SUNDAY
        val morningState = ReminderState()

        val morning = dueReminder(
            now = at(sundayInPlan, 7, 0),
            settings = allEnabled,
            state = morningState,
            plan = plan,
            rides = listOf(ride(at(sundayInPlan.minusDays(1), 10))),
        )
        assertNotNull(morning)
        assertEquals(ReminderKind.TAGESEINHEIT, morning.kind)

        val evening = dueReminder(
            now = at(sundayInPlan, 18, 0),
            settings = allEnabled,
            state = morningState.markDelivered(morning.kind, sundayInPlan),
            plan = plan,
            rides = listOf(ride(at(sundayInPlan.minusDays(1), 10))),
        )
        assertNotNull(evening)
        assertEquals(ReminderKind.WOCHENRUECKSCHAU, evening.kind)
    }

    @Test
    fun `treffen beide auf denselben Lauf, gewinnt die Rueckschau`() {
        val notice = dueReminder(
            now = at(SUNDAY, 18, 5),
            settings = allEnabled,
            // Der Morgenlauf ist ausgefallen (Geraet aus), nichts ist vermerkt.
            state = ReminderState(),
            plan = planFrom(MONDAY),
            rides = listOf(ride(at(SUNDAY.minusDays(1), 10))),
        )
        assertNotNull(notice)
        assertEquals(ReminderKind.WOCHENRUECKSCHAU, notice.kind)
    }

    @Test
    fun `bei gleicher Uhrzeit gewinnt ebenfalls die Rueckschau`() {
        val settings = allEnabled.copy(weeklyReviewTime = LocalTime.of(7, 0))

        val notice = dueReminder(
            now = at(SUNDAY, 7, 0),
            settings = settings,
            state = ReminderState(),
            plan = planFrom(MONDAY),
            rides = emptyList(),
        )
        assertNotNull(notice)
        assertEquals(ReminderKind.WOCHENRUECKSCHAU, notice.kind)
    }

    @Test
    fun `die Tageseinheit geht dem Anstupser vor`() {
        val notice = dueReminder(
            now = at(TUESDAY, 7, 0),
            settings = allEnabled,
            state = ReminderState(),
            plan = planFrom(MONDAY),
            rides = listOf(ride(at(TUESDAY.minusDays(20), 10))),
        )
        assertNotNull(notice)
        assertEquals(ReminderKind.TAGESEINHEIT, notice.kind)
    }

    // -----------------------------------------------------------------------
    // Anstupser
    // -----------------------------------------------------------------------

    @Test
    fun `Anstupser nach fuenf Tagen ohne Aufzeichnung`() {
        val notice = dueReminder(
            now = at(TUESDAY, 7, 0),
            settings = ReminderSettings(nudgeEnabled = true),
            state = ReminderState(),
            plan = null,
            rides = listOf(ride(at(TUESDAY.minusDays(5), 16))),
        )

        assertNotNull(notice)
        assertEquals(ReminderKind.ANSTUPSER, notice.kind)
        assertEquals("Seit 5 Tagen keine Tour", notice.title)
        assertTrue(notice.text.startsWith("Wenn du wieder unterwegs bist"))
    }

    @Test
    fun `Anstupser noch nicht nach vier Tagen`() {
        val notice = dueReminder(
            now = at(TUESDAY, 7, 0),
            settings = ReminderSettings(nudgeEnabled = true),
            state = ReminderState(),
            plan = null,
            rides = listOf(ride(at(TUESDAY.minusDays(4), 16))),
        )
        assertNull(notice)
    }

    @Test
    fun `Anstupser zaehlt die juengste Tour, nicht die erste`() {
        val notice = dueReminder(
            now = at(TUESDAY, 7, 0),
            settings = ReminderSettings(nudgeEnabled = true),
            state = ReminderState(),
            plan = null,
            rides = listOf(
                ride(at(TUESDAY.minusDays(40), 16)),
                ride(at(TUESDAY.minusDays(2), 16)),
                ride(at(TUESDAY.minusDays(9), 16)),
            ),
        )
        assertNull(notice)
    }

    @Test
    fun `ohne jede Tour kommt kein Anstupser`() {
        val notice = dueReminder(
            now = at(TUESDAY, 7, 0),
            settings = ReminderSettings(nudgeEnabled = true),
            state = ReminderState(),
            plan = null,
            rides = emptyList(),
        )
        assertNull(notice)
    }

    @Test
    fun `Anstupser hoechstens einmal pro Woche`() {
        val settings = ReminderSettings(nudgeEnabled = true)
        val rides = listOf(ride(at(TUESDAY.minusDays(20), 16)))

        assertNull(
            dueReminder(
                now = at(TUESDAY, 7, 0),
                settings = settings,
                state = ReminderState(lastNudgeOn = TUESDAY.minusDays(6)),
                plan = null,
                rides = rides,
            ),
        )
        assertNotNull(
            dueReminder(
                now = at(TUESDAY, 7, 0),
                settings = settings,
                state = ReminderState(lastNudgeOn = TUESDAY.minusDays(7)),
                plan = null,
                rides = rides,
            ),
        )
    }

    @Test
    fun `Anstupser haengt an der Morgen-Uhrzeit, auch ohne Tageseinheit`() {
        val settings = ReminderSettings(nudgeEnabled = true, dailySessionTime = LocalTime.of(9, 0))
        val rides = listOf(ride(at(TUESDAY.minusDays(20), 16)))

        assertNull(dueReminder(at(TUESDAY, 8, 59), settings, ReminderState(), null, rides))
        assertNotNull(dueReminder(at(TUESDAY, 9, 0), settings, ReminderState(), null, rides))
    }

    // -----------------------------------------------------------------------
    // Naechster Lauf
    // -----------------------------------------------------------------------

    @Test
    fun `ohne Anlass gibt es keinen naechsten Lauf`() {
        assertNull(nextReminderRun(at(TUESDAY, 12), ReminderSettings()))
    }

    @Test
    fun `naechster Lauf ist die Morgen-Uhrzeit`() {
        val settings = ReminderSettings(dailySessionEnabled = true)

        assertEquals(
            at(TUESDAY, 7, 0),
            nextReminderRun(at(TUESDAY, 6, 0), settings),
        )
        // Genau auf der Uhrzeit: der laufende Durchgang hat sie schon in der
        // Hand, der naechste Lauf ist der Folgetag.
        assertEquals(
            at(TUESDAY.plusDays(1), 7, 0),
            nextReminderRun(at(TUESDAY, 7, 0), settings),
        )
    }

    @Test
    fun `nur Wochenrueckschau weckt erst am Sonntag`() {
        assertEquals(
            at(SUNDAY, 18, 0),
            nextReminderRun(at(TUESDAY, 8, 0), ReminderSettings(weeklyReviewEnabled = true)),
        )
        assertEquals(
            at(SUNDAY.plusWeeks(1), 18, 0),
            nextReminderRun(at(SUNDAY, 18, 0), ReminderSettings(weeklyReviewEnabled = true)),
        )
    }

    @Test
    fun `nur Anstupser weckt zur Morgen-Uhrzeit`() {
        assertEquals(
            at(TUESDAY.plusDays(1), 7, 0),
            nextReminderRun(at(TUESDAY, 9, 0), ReminderSettings(nudgeEnabled = true)),
        )
    }

    @Test
    fun `am Sonntag liegt der naechste Lauf abends`() {
        assertEquals(
            at(SUNDAY, 18, 0),
            nextReminderRun(at(SUNDAY, 7, 30), allEnabled),
        )
        assertEquals(
            at(SUNDAY.plusDays(1), 7, 0),
            nextReminderRun(at(SUNDAY, 19, 0), allEnabled),
        )
    }

    // -----------------------------------------------------------------------
    // Zeitumstellung
    // -----------------------------------------------------------------------

    @Test
    fun `die Zeitumstellung verschiebt die Weckzeit nicht`() {
        val settings = ReminderSettings(dailySessionEnabled = true)
        val saturdayBeforeSwitch = LocalDate.of(2026, 3, 28)

        // Gerechnet wird auf der Wanduhr: Vor und nach der Umstellung bleibt
        // es 7:00, obwohl zwischen beiden Terminen nur 23 Stunden liegen.
        assertEquals(
            at(saturdayBeforeSwitch.plusDays(1), 7, 0),
            nextReminderRun(at(saturdayBeforeSwitch, 8, 0), settings),
        )
        assertEquals(
            at(saturdayBeforeSwitch.plusDays(2), 7, 0),
            nextReminderRun(at(saturdayBeforeSwitch.plusDays(1), 7, 0), settings),
        )
    }

    @Test
    fun `in der Umstellungswoche zaehlt die Rueckschau die richtige Woche`() {
        val previous = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"))
            val switchMonday = LocalDate.of(2026, 3, 23)
            val switchSunday = LocalDate.of(2026, 3, 29)
            val plan = planFrom(switchMonday)
            val week = plan.weeks.first()

            val rides = listOf(
                // Vor der Umstellung …
                ride(at(switchMonday.plusDays(1), 18), km = 40.0),
                // … und nach der Umstellung, in derselben Planwoche.
                ride(at(switchSunday, 11), km = 20.0),
                // Der Montag danach gehoert schon zur naechsten Woche.
                ride(at(switchSunday.plusDays(1), 8), km = 55.0),
            )

            val notice = dueReminder(
                now = at(switchSunday, 18, 0),
                settings = ReminderSettings(weeklyReviewEnabled = true),
                state = ReminderState(),
                plan = plan,
                rides = rides,
            )

            assertNotNull(notice)
            assertEquals("Diese Woche: 60 von ${week.targetKm} km gefahren.", notice.text)
        } finally {
            TimeZone.setDefault(previous)
        }
    }

    // -----------------------------------------------------------------------
    // Speicherung
    // -----------------------------------------------------------------------

    @Test
    fun `Einstellungen ueberstehen die Runde durch JSON`() {
        val settings = ReminderSettings(
            dailySessionEnabled = true,
            weeklyReviewEnabled = false,
            nudgeEnabled = true,
            dailySessionTime = LocalTime.of(6, 45),
            weeklyReviewTime = LocalTime.of(19, 30),
        )
        assertEquals(settings, ReminderSettings.fromJson(parse(settings.toJson().toString())))
    }

    @Test
    fun `fehlende Felder fallen auf die Vorgabe zurueck`() {
        assertEquals(ReminderSettings(), ReminderSettings.fromJson(parse("{}")))
        assertEquals(
            ReminderSettings(dailySessionEnabled = true),
            ReminderSettings.fromJson(parse("""{"dailySessionEnabled":true}""")),
        )
    }

    @Test
    fun `unsinnige Uhrzeiten fallen auf die Vorgabe zurueck`() {
        val settings = ReminderSettings.fromJson(
            parse("""{"dailySessionMinute":-5,"weeklyReviewMinute":5000}"""),
        )
        assertEquals(LocalTime.of(7, 0), settings.dailySessionTime)
        assertEquals(LocalTime.of(18, 0), settings.weeklyReviewTime)
    }

    @Test
    fun `Meldestand ueberstehen die Runde durch JSON`() {
        val state = ReminderState(
            lastDailySessionOn = TUESDAY,
            lastWeeklyReviewOn = SUNDAY,
            lastNudgeOn = MONDAY,
        )
        assertEquals(state, ReminderState.fromJson(parse(state.toJson().toString())))
    }

    @Test
    fun `unlesbarer Meldestand gilt als nie gemeldet`() {
        assertEquals(ReminderState(), ReminderState.fromJson(parse("{}")))
        assertEquals(
            ReminderState(),
            ReminderState.fromJson(parse("""{"lastNudgeOn":"vorgestern"}""")),
        )
    }

    @Test
    fun `markDelivered vermerkt nur den gemeldeten Anlass`() {
        val state = ReminderState().markDelivered(ReminderKind.ANSTUPSER, TUESDAY)
        assertEquals(TUESDAY, state.lastNudgeOn)
        assertNull(state.lastDailySessionOn)
        assertNull(state.lastWeeklyReviewOn)
    }
}
