package de.trailscape.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Vollstaendige Portierung von `test/training_test.dart` (29 Faelle).
 *
 * Die Gruppe `savePlan / loadPlan` laeuft statt gegen `SharedPreferences`
 * gegen [InMemoryTrainingPlanStore] — die Erwartungswerte sind unveraendert.
 */
class TrainingTest {

    /**
     * Feste Zeitpunkte statt `LocalDateTime.now()`, damit die Tests
     * reproduzierbar sind. Mittwoch, 7. Januar 2026, 12:00 lokaler Zeit.
     */
    private val now: Long = dartEpochMs(LocalDateTime.of(2026, 1, 7, 12, 0))

    /** Montag der Woche von [now]. */
    private val firstMonday: LocalDate = LocalDate.of(2026, 1, 5)

    private fun ms(date: LocalDateTime): Long = dartEpochMs(date)

    /** Zeitstempel am Tag [dayOffset] nach dem ersten Montag, 09:00 lokal. */
    private fun dayAfterFirstMonday(dayOffset: Int, hour: Int = 9): Long =
        ms(firstMonday.plusDays(dayOffset.toLong()).atTime(hour, 0))

    private fun goalAt(
        timestamp: Long,
        distanceKm: Double = 160.0,
        ascentM: Double? = 2200.0,
    ): Goal = Goal(
        name = "Gravel Grinder",
        distanceKm = distanceKm,
        ascentM = ascentM,
        date = timestamp,
    )

    private val advanced = FitnessAssessment(
        level = FitnessLevel.FORTGESCHRITTEN,
        weeklyKm = 95.0,
        weeklyHm = 900.0,
        weeklyRides = 2.0,
        longestRideKm = 80.0,
        rideCount = 16,
    )

    private val beginner = FitnessAssessment(
        level = FitnessLevel.EINSTEIGER,
        weeklyKm = 12.0,
        weeklyHm = 120.0,
        weeklyRides = 1.0,
        longestRideKm = 25.0,
        rideCount = 8,
    )

    private fun ride(createdAt: Long, distanceKm: Double): Ride = Ride(
        id = "r$createdAt",
        name = "Fahrt",
        createdAt = createdAt,
        points = emptyList(),
        stats = RideStats(distanceKm = distanceKm, ascentM = 0.0, descentM = 0.0),
    )

    // -----------------------------------------------------------------------
    // group('generatePlan – 12-Wochen-Plan (Fortgeschritten)')
    // Zielwoche ist Index 11, Event am Samstag dieser Woche.
    // -----------------------------------------------------------------------

    private val twelveWeekGoal = goalAt(dayAfterFirstMonday(11 * 7 + 5))
    private val twelveWeekPlan = generatePlan(twelveWeekGoal, advanced, now = now)

    @Test
    fun `12 Wochen - Plan-Rahmendaten stimmen`() {
        assertEquals(12, twelveWeekPlan.weeks.size)
        assertEquals(FitnessLevel.FORTGESCHRITTEN, twelveWeekPlan.level)
        assertEquals(now, twelveWeekPlan.createdAt)
        assertEquals(160.0, twelveWeekPlan.goal.distanceKm)
    }

    @Test
    fun `12 Wochen - Wochenraster liegt Montag bis Sonntag lokal`() {
        assertEquals(ms(firstMonday.atStartOfDay()), twelveWeekPlan.weeks.first().start)
        for (i in twelveWeekPlan.weeks.indices) {
            val week = twelveWeekPlan.weeks[i]
            assertEquals(i, week.index)
            val start = dartLocalOf(week.start)
            val end = dartLocalOf(week.end)
            assertEquals(DayOfWeek.MONDAY, start.dayOfWeek)
            assertEquals(0, start.hour)
            assertEquals(DayOfWeek.MONDAY, end.dayOfWeek)
            assertEquals(0, end.hour)
            if (i > 0) {
                assertEquals(twelveWeekPlan.weeks[i - 1].end, week.start)
            }
        }
        // Der Zieltermin liegt in der letzten Woche.
        assertTrue(twelveWeekGoal.date >= twelveWeekPlan.weeks.last().start)
        assertTrue(twelveWeekGoal.date < twelveWeekPlan.weeks.last().end)
    }

    @Test
    fun `12 Wochen - Wochenfolge Aufbau x3, Erholung, Aufbau x3, Erholung, Aufbau x2, Taper, Zielwoche`() {
        assertEquals(
            listOf(
                WeekKind.AUFBAU,
                WeekKind.AUFBAU,
                WeekKind.AUFBAU,
                WeekKind.ERHOLUNG,
                WeekKind.AUFBAU,
                WeekKind.AUFBAU,
                WeekKind.AUFBAU,
                WeekKind.ERHOLUNG,
                WeekKind.AUFBAU,
                WeekKind.AUFBAU,
                WeekKind.TAPER,
                WeekKind.ZIELWOCHE,
            ),
            twelveWeekPlan.weeks.map { it.kind },
        )
    }

    @Test
    fun `12 Wochen - lineare Progression von startKm zum Peak, auf 5 km gerundet`() {
        // startKm = max(95, 70) = 95; peak = min(max(160*1.3, 95), 95*2.2) = 208
        val builds = twelveWeekPlan.weeks
            .filter { it.kind == WeekKind.AUFBAU }
            .map { it.targetKm }
        assertEquals(listOf(95, 110, 125, 145, 160, 175, 190, 210), builds)

        for (week in twelveWeekPlan.weeks) {
            assertTrue(
                week.targetKm % 5 == 0 || week.kind == WeekKind.ZIELWOCHE,
                "Woche ${week.index} nicht auf 5 km gerundet",
            )
            assertTrue(week.targetKm >= 5)
        }
    }

    @Test
    fun `12 Wochen - Erholungswochen liegen bei 60 Prozent der Vorwoche`() {
        for (week in twelveWeekPlan.weeks) {
            if (week.kind != WeekKind.ERHOLUNG) continue
            val previous = twelveWeekPlan.weeks[week.index - 1].targetKm
            assertEquals(
                dartRound(previous * 0.6 / 5).toInt() * 5,
                week.targetKm,
                "Erholungswoche ${week.index}",
            )
        }
        assertEquals(75, twelveWeekPlan.weeks[3].targetKm) // 60 % von 125
        assertEquals(105, twelveWeekPlan.weeks[7].targetKm) // 60 % von 175
    }

    @Test
    fun `12 Wochen - Taper liegt bei 50 Prozent des Peaks`() {
        // Peak = 208 → 104 → auf 5 km gerundet 105
        assertEquals(WeekKind.TAPER, twelveWeekPlan.weeks[10].kind)
        assertEquals(105, twelveWeekPlan.weeks[10].targetKm)
    }

    @Test
    fun `12 Wochen - Zielwoche enthaelt Aktivierung und Zielevent`() {
        val zielwoche = twelveWeekPlan.weeks.last()
        assertEquals(WeekKind.ZIELWOCHE, zielwoche.kind)
        assertEquals(2, zielwoche.sessions.size)

        val activation = zielwoche.sessions.first()
        assertEquals("Di", activation.day) // Samstags-Event → Aktivierung Dienstag
        assertEquals(15, activation.targetKm)

        val event = zielwoche.sessions.last()
        assertEquals("Sa", event.day)
        assertEquals("Zielevent: Gravel Grinder", event.title)
        // targetKm der Zieleinheit entspricht der Zieldistanz.
        assertEquals(160, event.targetKm)
        // 2200 Hm ≥ 1000 → Hoehenmeter-Hinweis in der Beschreibung.
        assertTrue(event.description.contains("2200 Hm"))

        assertEquals(zielwoche.sessions.sumOf { it.targetKm }, zielwoche.targetKm)
    }

    @Test
    fun `12 Wochen - Session-Summen treffen das Wochenziel auf plusminus 10 Prozent`() {
        for (week in twelveWeekPlan.weeks) {
            if (week.kind == WeekKind.ZIELWOCHE) continue
            val sum = week.sessions.sumOf { it.targetKm }
            assertTrue(
                sum >= floor(week.targetKm * 0.9).toInt(),
                "Woche ${week.index}",
            )
            assertTrue(
                sum <= ceil(week.targetKm * 1.1).toInt(),
                "Woche ${week.index}",
            )
        }
    }

    @Test
    fun `12 Wochen - Aufbauwochen Fortgeschritten haben drei Einheiten mit Hoehenmeter-Hinweis`() {
        val build = twelveWeekPlan.weeks.first()
        assertEquals(listOf("Di", "Do", "Sa"), build.sessions.map { it.day })
        assertEquals(listOf("GA1", "Intervalle", "Lange Tour"), build.sessions.map { it.title })
        assertTrue(build.sessions.last().description.contains("Baue dabei bewusst Anstiege ein"))
    }

    @Test
    fun `12 Wochen - ohne nennenswerte Hoehenmeter fehlt der Anstiegs-Hinweis`() {
        val flat = generatePlan(
            goalAt(dayAfterFirstMonday(11 * 7 + 5), ascentM = 400.0),
            advanced,
            now = now,
        )
        assertFalse(
            flat.weeks.first().sessions.last().description
                .contains("Baue dabei bewusst Anstiege ein"),
        )
        assertFalse(flat.weeks.last().sessions.last().description.contains("Hm"))
    }

    // -----------------------------------------------------------------------
    // group('generatePlan – 3-Wochen-Minimalplan (Einsteiger)')
    // Zielwoche ist Index 2, Event am Samstag.
    // -----------------------------------------------------------------------

    private val minimalPlan = generatePlan(
        goalAt(dayAfterFirstMonday(2 * 7 + 5), distanceKm = 30.0, ascentM = null),
        beginner,
        now = now,
    )

    @Test
    fun `3 Wochen - drei Wochen Aufbau, Taper, Zielwoche`() {
        assertEquals(3, minimalPlan.weeks.size)
        assertEquals(
            listOf(WeekKind.AUFBAU, WeekKind.TAPER, WeekKind.ZIELWOCHE),
            minimalPlan.weeks.map { it.kind },
        )
    }

    @Test
    fun `3 Wochen - Basisvolumen Einsteiger ist 40 km`() {
        // weeklyKm 12 < 40 → startKm = 40; peak = min(max(39, 40), 88) = 40
        assertEquals(40, minimalPlan.weeks[0].targetKm)
        assertEquals(20, minimalPlan.weeks[1].targetKm) // 50 % vom Peak
        assertEquals(FitnessLevel.EINSTEIGER, minimalPlan.level)
    }

    @Test
    fun `3 Wochen - Einsteiger-Aufbau unter 60 km hat nur zwei Einheiten`() {
        val sessions = minimalPlan.weeks[0].sessions
        assertEquals(2, sessions.size)
        assertEquals(listOf("Di", "Sa"), sessions.map { it.day })
        assertEquals(16, sessions[0].targetKm) // 40 % von 40
        assertEquals(24, sessions[1].targetKm) // 60 % von 40
    }

    @Test
    fun `3 Wochen - Einsteiger-Aufbau ab 60 km bekommt zusaetzlich Regeneration`() {
        val big = generatePlan(
            goalAt(dayAfterFirstMonday(2 * 7 + 5), distanceKm = 90.0, ascentM = null),
            beginner,
            now = now,
        )
        // peak = min(max(117, 40), 88) = 88 → round5 = 90
        val sessions = big.weeks[0].sessions
        assertEquals(90, big.weeks[0].targetKm)
        assertEquals(3, sessions.size)
        assertEquals(listOf("Di", "Sa", "So"), sessions.map { it.day })
        assertEquals(listOf(27, 45, 18), sessions.map { it.targetKm })
    }

    @Test
    fun `3 Wochen - Montags-Event bekommt keine Aktivierung`() {
        val mondayGoal = generatePlan(
            goalAt(dayAfterFirstMonday(2 * 7), distanceKm = 30.0, ascentM = null),
            beginner,
            now = now,
        )
        val zielwoche = mondayGoal.weeks.last()
        assertEquals(1, zielwoche.sessions.size)
        assertEquals("Mo", zielwoche.sessions.single().day)
        assertEquals(30, zielwoche.targetKm)
    }

    @Test
    fun `3 Wochen - Dienstags-Event bekommt Aktivierung am Montag`() {
        val tuesdayGoal = generatePlan(
            goalAt(dayAfterFirstMonday(2 * 7 + 1), distanceKm = 30.0, ascentM = null),
            beginner,
            now = now,
        )
        val zielwoche = tuesdayGoal.weeks.last()
        assertEquals(2, zielwoche.sessions.size)
        assertEquals("Mo", zielwoche.sessions.first().day)
        assertEquals("Di", zielwoche.sessions.last().day)
        assertEquals(45, zielwoche.targetKm)
    }

    // -----------------------------------------------------------------------
    // group('generatePlan – Fehlerfälle und Grenzen')
    // -----------------------------------------------------------------------

    @Test
    fun `Fehler - Ziel in dieser Woche ist zu nah`() {
        val error = assertFailsWith<IllegalArgumentException> {
            generatePlan(goalAt(dayAfterFirstMonday(4)), advanced, now = now)
        }
        assertEquals(errorTooSoon, error.message)
    }

    @Test
    fun `Fehler - Ziel in 2 Wochen ist zu nah`() {
        val error = assertFailsWith<IllegalArgumentException> {
            generatePlan(goalAt(dayAfterFirstMonday(7 + 3)), advanced, now = now)
        }
        assertEquals(errorTooSoon, error.message)
    }

    @Test
    fun `Fehler - Ziel in der Vergangenheit ist zu nah`() {
        val error = assertFailsWith<IllegalArgumentException> {
            generatePlan(goalAt(dayAfterFirstMonday(-5)), advanced, now = now)
        }
        assertEquals(errorTooSoon, error.message)
    }

    @Test
    fun `Fehler - Ziel ueber 52 Wochen entfernt ist zu weit`() {
        val error = assertFailsWith<IllegalArgumentException> {
            generatePlan(goalAt(dayAfterFirstMonday(52 * 7 + 3)), advanced, now = now)
        }
        assertEquals(errorTooFar, error.message)
    }

    @Test
    fun `Fehler - Grenzen 3 und 52 Wochen sind gueltig`() {
        val min = generatePlan(goalAt(dayAfterFirstMonday(2 * 7 + 3)), advanced, now = now)
        assertEquals(3, min.weeks.size)

        val max = generatePlan(goalAt(dayAfterFirstMonday(51 * 7 + 3)), advanced, now = now)
        assertEquals(52, max.weeks.size)
    }

    // -----------------------------------------------------------------------
    // group('currentWeekIndex')
    // -----------------------------------------------------------------------

    @Test
    fun `currentWeekIndex - minus 1 vor Planbeginn`() {
        assertEquals(-1, currentWeekIndex(twelveWeekPlan, now = twelveWeekPlan.weeks.first().start - 1))
        assertEquals(-1, currentWeekIndex(twelveWeekPlan, now = dayAfterFirstMonday(-3)))
    }

    @Test
    fun `currentWeekIndex - liefert die laufende Woche`() {
        assertEquals(0, currentWeekIndex(twelveWeekPlan, now = now))
        assertEquals(0, currentWeekIndex(twelveWeekPlan, now = twelveWeekPlan.weeks.first().start))
        assertEquals(5, currentWeekIndex(twelveWeekPlan, now = twelveWeekPlan.weeks[5].start))
        assertEquals(5, currentWeekIndex(twelveWeekPlan, now = twelveWeekPlan.weeks[5].end - 1))
        assertEquals(6, currentWeekIndex(twelveWeekPlan, now = twelveWeekPlan.weeks[5].end))
    }

    @Test
    fun `currentWeekIndex - klemmt nach Planende auf die letzte Woche`() {
        assertEquals(11, currentWeekIndex(twelveWeekPlan, now = twelveWeekPlan.weeks.last().end))
        assertEquals(
            11,
            currentWeekIndex(
                twelveWeekPlan,
                now = twelveWeekPlan.weeks.last().end + 90L * 86400000,
            ),
        )
    }

    // -----------------------------------------------------------------------
    // group('sessionsForDay') — Grundlage der Startseite „Heute"
    // -----------------------------------------------------------------------

    @Test
    fun `sessionsForDay - liefert die Einheit des laufenden Wochentags`() {
        // Fortgeschritten, Aufbauwoche: Di GA1, Do Intervalle, Sa Lange Tour.
        val tuesday = sessionsForDay(twelveWeekPlan, now = dayAfterFirstMonday(1))
        assertEquals(1, tuesday.size)
        assertEquals("GA1", tuesday.first().title)
        assertEquals("Di", tuesday.first().day)

        val saturday = sessionsForDay(twelveWeekPlan, now = dayAfterFirstMonday(5))
        assertEquals(listOf("Lange Tour"), saturday.map { it.title })
    }

    @Test
    fun `sessionsForDay - an einem Ruhetag leer`() {
        // Montag und Freitag tragen im Fortgeschrittenen-Raster keine Einheit.
        assertTrue(sessionsForDay(twelveWeekPlan, now = dayAfterFirstMonday(0)).isEmpty())
        assertTrue(sessionsForDay(twelveWeekPlan, now = dayAfterFirstMonday(4)).isEmpty())
    }

    @Test
    fun `sessionsForDay - beruecksichtigt die Art der laufenden Woche`() {
        // Woche 3 ist Erholung (Di + Sa), also steht donnerstags nichts an.
        assertEquals(WeekKind.ERHOLUNG, twelveWeekPlan.weeks[3].kind)
        assertEquals(
            listOf("Lockere Ausfahrt"),
            sessionsForDay(twelveWeekPlan, now = dayAfterFirstMonday(3 * 7 + 1)).map { it.title },
        )
        assertTrue(sessionsForDay(twelveWeekPlan, now = dayAfterFirstMonday(3 * 7 + 3)).isEmpty())
    }

    @Test
    fun `sessionsForDay - ausserhalb der Planlaufzeit leer`() {
        // Vor Planbeginn …
        assertTrue(sessionsForDay(twelveWeekPlan, now = dayAfterFirstMonday(-6)).isEmpty())
        // … und nach Planende, wo currentWeekIndex auf die letzte Woche klemmt.
        assertTrue(sessionsForDay(twelveWeekPlan, now = twelveWeekPlan.weeks.last().end).isEmpty())
        assertTrue(
            sessionsForDay(
                twelveWeekPlan,
                now = twelveWeekPlan.weeks.last().end + 30L * 86400000,
            ).isEmpty(),
        )
    }

    // -----------------------------------------------------------------------
    // group('weekKm')
    // -----------------------------------------------------------------------

    @Test
    fun `weekKm - summiert halboffen start bis end auf eine Nachkommastelle`() {
        val week = twelveWeekPlan.weeks[1]
        val rides = listOf(
            ride(week.start - 1, 100.0), // davor
            ride(week.start, 12.34), // exakt am Start → zaehlt
            ride(week.start + 3L * 86400000, 20.01), // mitten drin
            ride(week.end - 1, 7.6), // letzte Millisekunde → zaehlt
            ride(week.end, 500.0), // exakt am Ende → zaehlt nicht
        )
        assertEquals(40.0, weekKm(week, rides))
    }

    @Test
    fun `weekKm - ohne passende Fahrten 0`() {
        assertEquals(0.0, weekKm(twelveWeekPlan.weeks[2], emptyList()))
        assertEquals(
            0.0,
            weekKm(twelveWeekPlan.weeks[2], listOf(ride(twelveWeekPlan.weeks[2].end, 50.0))),
        )
    }

    // -----------------------------------------------------------------------
    // group('savePlan / loadPlan')
    // -----------------------------------------------------------------------

    @Test
    fun `Speicher - ohne gespeicherten Plan kommt null zurueck`() {
        assertNull(loadPlan(InMemoryTrainingPlanStore()))
    }

    @Test
    fun `Speicher - JSON-Roundtrip erhaelt den vollstaendigen Plan`() {
        val store = InMemoryTrainingPlanStore()
        val plan = generatePlan(goalAt(dayAfterFirstMonday(11 * 7 + 5)), advanced, now = now)
        savePlan(store, plan)

        val loaded = loadPlan(store)
        assertNotNull(loaded)
        assertEquals(plan.createdAt, loaded.createdAt)
        assertEquals(plan.level, loaded.level)
        assertEquals(plan.goal.name, loaded.goal.name)
        assertEquals(plan.goal.distanceKm, loaded.goal.distanceKm)
        assertEquals(plan.goal.ascentM, loaded.goal.ascentM)
        assertEquals(plan.goal.date, loaded.goal.date)
        assertEquals(plan.weeks.size, loaded.weeks.size)

        for (i in plan.weeks.indices) {
            val a = plan.weeks[i]
            val b = loaded.weeks[i]
            assertEquals(a.index, b.index)
            assertEquals(a.start, b.start)
            assertEquals(a.end, b.end)
            assertEquals(a.kind, b.kind)
            assertEquals(a.targetKm, b.targetKm)
            assertEquals(a.sessions.map { it.day }, b.sessions.map { it.day })
            assertEquals(a.sessions.map { it.title }, b.sessions.map { it.title })
            assertEquals(a.sessions.map { it.description }, b.sessions.map { it.description })
            assertEquals(a.sessions.map { it.targetKm }, b.sessions.map { it.targetKm })
        }
    }

    @Test
    fun `Speicher - savePlan null entfernt den gespeicherten Plan`() {
        val store = InMemoryTrainingPlanStore()
        val plan = generatePlan(goalAt(dayAfterFirstMonday(11 * 7 + 5)), advanced, now = now)
        savePlan(store, plan)
        assertNotNull(loadPlan(store))

        savePlan(store, null)
        assertNull(loadPlan(store))
    }
}
