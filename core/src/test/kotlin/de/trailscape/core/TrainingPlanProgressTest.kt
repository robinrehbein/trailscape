package de.trailscape.core

import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Tests fuer `TrainingPlanProgress.kt`: Status-Zuordnung der Planeinheiten
 * ([weekSessionProgress]) und Plan-Adaption ([adaptPlan]).
 *
 * Feste Zeitpunkte wie in [TrainingTest]: Mittwoch, 7. Januar 2026; Montag der
 * Woche ist der 5. Januar.
 */
class TrainingPlanProgressTest {

    private val now: Long = dartEpochMs(LocalDateTime.of(2026, 1, 7, 12, 0))
    private val firstMonday: LocalDate = LocalDate.of(2026, 1, 5)

    private fun ms(date: LocalDateTime): Long = dartEpochMs(date)

    /** Zeitstempel am Tag [dayOffset] nach dem ersten Montag, 09:00 lokal. */
    private fun dayAfterFirstMonday(dayOffset: Int, hour: Int = 9): Long =
        ms(firstMonday.plusDays(dayOffset.toLong()).atTime(hour, 0))

    private fun ride(id: String, createdAt: Long, distanceKm: Double): Ride = Ride(
        id = id,
        name = "Fahrt $id",
        createdAt = createdAt,
        points = emptyList(),
        stats = RideStats(distanceKm = distanceKm, ascentM = 0.0, descentM = 0.0),
    )

    /** Woche Mo 5.1.–Mo 12.1. mit dem Fortgeschrittenen-Raster Di/Do/Sa. */
    private val week = TrainingWeek(
        index = 0,
        start = ms(firstMonday.atStartOfDay()),
        end = ms(firstMonday.plusDays(7).atStartOfDay()),
        kind = WeekKind.AUFBAU,
        targetKm = 95,
        sessions = listOf(
            TrainingSession(
                day = "Di",
                title = "GA1",
                description = "",
                targetKm = 25,
                intensity = SessionIntensity.GRUNDLAGE,
                targetLoad = 60.0,
            ),
            TrainingSession(
                day = "Do",
                title = "Intervalle",
                description = "",
                targetKm = 15,
                intensity = SessionIntensity.HART,
                targetLoad = 80.0,
            ),
            TrainingSession(
                day = "Sa",
                title = "Lange Tour",
                description = "",
                targetKm = 55,
                intensity = SessionIntensity.GRUNDLAGE,
                targetLoad = 130.0,
            ),
        ),
    )

    /** Zeitpunkt weit nach Wochenende — alles Unerledigte ist dann verpasst. */
    private val afterWeek: Long = dayAfterFirstMonday(9)

    // -----------------------------------------------------------------------
    // weekSessionProgress — Status
    // -----------------------------------------------------------------------

    @Test
    fun `erledigt, teilweise und verpasst in einer Woche`() {
        val rides = listOf(
            ride("di", dayAfterFirstMonday(1), 26.0), // Di: 26/25 → erledigt
            ride("sa", dayAfterFirstMonday(5), 20.0), // Sa: 20/55 = 36 % → teilweise
        )
        val progress = weekSessionProgress(week, rides, now = afterWeek)

        assertEquals(3, progress.size)
        // Ergebnis in Wochenreihenfolge Di/Do/Sa.
        assertEquals(week.sessions, progress.map { it.session })

        assertEquals(PlanSessionStatus.ERLEDIGT, progress[0].status)
        assertEquals("di", progress[0].rideId)
        assertEquals(26.0, progress[0].riddenKm)

        assertEquals(PlanSessionStatus.VERPASST, progress[1].status)
        assertNull(progress[1].rideId)

        assertEquals(PlanSessionStatus.TEILWEISE, progress[2].status)
        assertEquals("sa", progress[2].rideId)
    }

    @Test
    fun `offen bis der Toleranztag vorbei ist`() {
        // Mittwoch 12:00: Di ist vorbei, aber der Toleranztag (Mi) laeuft noch.
        val wednesday = dayAfterFirstMonday(2, hour = 12)
        val open = weekSessionProgress(week, emptyList(), now = wednesday)
        assertEquals(
            listOf(PlanSessionStatus.OFFEN, PlanSessionStatus.OFFEN, PlanSessionStatus.OFFEN),
            open.map { it.status },
        )

        // Donnerstag 00:00: jetzt ist auch der Toleranztag der Di-Einheit um.
        val thursday = ms(firstMonday.plusDays(3).atStartOfDay())
        val missed = weekSessionProgress(week, emptyList(), now = thursday)
        assertEquals(PlanSessionStatus.VERPASST, missed[0].status)
        assertEquals(PlanSessionStatus.OFFEN, missed[1].status)
        assertEquals(PlanSessionStatus.OFFEN, missed[2].status)
    }

    @Test
    fun `Toleranz - die Tour am Folgetag zaehlt fuer die Einheit`() {
        // Lange Tour auf Sonntag geschoben: |Sa − So| = 1 Tag → zaehlt.
        val rides = listOf(ride("so", dayAfterFirstMonday(6), 50.0))
        val progress = weekSessionProgress(week, rides, now = afterWeek)
        assertEquals(PlanSessionStatus.ERLEDIGT, progress[2].status)
        assertEquals("so", progress[2].rideId)
    }

    @Test
    fun `eine Tour deckt hoechstens eine Einheit`() {
        // Eine einzige Freitags-Tour liegt fuer Do UND Sa im Toleranzfenster —
        // die fruehere Einheit (Do) nimmt sie, Sa bleibt verpasst.
        val rides = listOf(ride("fr", dayAfterFirstMonday(4), 100.0))
        val progress = weekSessionProgress(week, rides, now = afterWeek)
        assertEquals(PlanSessionStatus.ERLEDIGT, progress[1].status)
        assertEquals("fr", progress[1].rideId)
        assertEquals(PlanSessionStatus.VERPASST, progress[2].status)
    }

    @Test
    fun `bei gleichem Tagesabstand gewinnt die laengere Tour die grosse Einheit`() {
        // Zwei Touren am Samstag: die 50-km-Runde gehoert zur langen Tour, die
        // kurze bleibt unzugeordnet (Sa ist die einzige Einheit im Fenster).
        val rides = listOf(
            ride("kurz", dayAfterFirstMonday(5, hour = 8), 8.0),
            ride("lang", dayAfterFirstMonday(5, hour = 14), 50.0),
        )
        val progress = weekSessionProgress(week, rides, now = afterWeek)
        assertEquals("lang", progress[2].rideId)
        assertEquals(PlanSessionStatus.ERLEDIGT, progress[2].status)
    }

    @Test
    fun `der Last-Anteil erledigt eine Einheit auch bei wenig Kilometern`() {
        // 10 km klingen nach 18 % der langen Tour — aber die Tour hat 90 von
        // 130 Ziel-Last (69 %): Der Reiz wurde gesetzt, die Einheit ist erledigt.
        val rides = listOf(ride("sa", dayAfterFirstMonday(5), 10.0))

        val ohneLast = weekSessionProgress(week, rides, now = afterWeek)
        assertEquals(PlanSessionStatus.TEILWEISE, ohneLast[2].status)

        val mitLast = weekSessionProgress(
            week,
            rides,
            now = afterWeek,
            rideLoads = mapOf("sa" to 90.0),
        )
        assertEquals(PlanSessionStatus.ERLEDIGT, mitLast[2].status)
        assertEquals(90.0, mitLast[2].riddenLoad)
    }

    @Test
    fun `gespeicherte Planungen erledigen keine Einheit`() {
        val rides = listOf(ride("plan", dayAfterFirstMonday(1), 30.0).copy(planned = true))
        val progress = weekSessionProgress(week, rides, now = afterWeek)
        assertEquals(PlanSessionStatus.VERPASST, progress[0].status)
        assertNull(progress[0].rideId)
    }

    // -----------------------------------------------------------------------
    // adaptPlan
    // -----------------------------------------------------------------------

    private val goal = Goal(
        name = "Gravel Grinder",
        distanceKm = 160.0,
        ascentM = 2200.0,
        date = dayAfterFirstMonday(11 * 7 + 5),
    )

    private val advanced = FitnessAssessment(
        level = FitnessLevel.FORTGESCHRITTEN,
        weeklyKm = 95.0,
        weeklyHm = 900.0,
        weeklyRides = 2.0,
        longestRideKm = 80.0,
        rideCount = 16,
    )

    private val plan = generatePlan(goal, advanced, now = now)

    /** Dienstag der zweiten Planwoche — Woche 0 ist gerade abgeschlossen. */
    private val secondWeekTuesday: Long = dayAfterFirstMonday(7 + 1)

    @Test
    fun `deutlich unter Soll - die restlichen Wochen starten vom Erreichten`() {
        // Woche 0 wollte 95 km, gefahren wurden 30 → 32 % < 70 %.
        val rides = listOf(ride("r1", dayAfterFirstMonday(2), 30.0))
        val result = adaptPlan(plan, rides, now = secondWeekTuesday)

        assertTrue(result.adapted)
        val reason = assertNotNull(result.reason)
        assertTrue(reason.contains("Woche 1"), reason)
        assertTrue(reason.contains("32 %"), reason)

        val adapted = result.plan
        // Vergangenheit, Raster und Ziel bleiben stehen.
        assertEquals(plan.weeks[0], adapted.weeks[0])
        assertEquals(plan.goal, adapted.goal)
        assertEquals(plan.weeks.map { it.kind }, adapted.weeks.map { it.kind })
        assertEquals(plan.weeks.map { it.start }, adapted.weeks.map { it.start })
        assertEquals(plan.weeks.map { it.end }, adapted.weeks.map { it.end })
        // Die Zielwoche bleibt woertlich unveraendert.
        assertEquals(plan.weeks.last(), adapted.weeks.last())

        // Die naechste Woche startet beim tatsaechlich Erreichten (30 km),
        // nicht bei den geplanten 105.
        assertEquals(30, adapted.weeks[1].targetKm)
        assertTrue(adapted.weeks[1].targetKm < plan.weeks[1].targetKm)

        // Der +15-%-Deckel gilt weiter fuer jede neue Aufbauwoche.
        val builds = adapted.weeks.filter { it.kind == WeekKind.AUFBAU }.map { it.targetKm }
        for (i in 1 until builds.size) {
            assertTrue(builds[i] <= builds[i - 1] * 1.15 + 1e-9)
        }

        // Und die Einheiten der neuen Wochen tragen wieder Last-Ziele.
        assertTrue(adapted.weeks[1].sessions.all { it.targetLoad != null })
    }

    @Test
    fun `ueber Soll bleibt der Plan unveraendert`() {
        val rides = listOf(ride("r1", dayAfterFirstMonday(5), 90.0)) // 95 % von 95 km
        val result = adaptPlan(plan, rides, now = secondWeekTuesday)
        assertFalse(result.adapted)
        assertNull(result.reason)
        assertSame(plan, result.plan)
    }

    @Test
    fun `ohne abgeschlossene Woche passiert nichts`() {
        // Mitten in Woche 0: nichts ist abgeschlossen, nichts wird angepasst.
        val result = adaptPlan(plan, emptyList(), now = now)
        assertFalse(result.adapted)
        assertSame(plan, result.plan)
    }

    @Test
    fun `mit Budgets und Tourlasten entscheidet die Last, nicht die Distanz`() {
        // Plan mit Last-Budgets (CTL 50 → Woche 0 ≈ 532 Last). Die Woche wurde
        // zwar fast komplett in Kilometern gefahren (90 von 95), aber nur mit
        // 100 Last — ein Bruchteil des Budgets: Der Aufbau traegt nicht.
        val planned = generatePlan(goal, advanced, now = now, currentCtl = 50.0)
        val rides = listOf(ride("r1", dayAfterFirstMonday(5), 90.0))

        val kmOnly = adaptPlan(planned, rides, now = secondWeekTuesday)
        assertFalse(kmOnly.adapted)

        val withLoads = adaptPlan(
            planned,
            rides,
            now = secondWeekTuesday,
            currentCtl = 40.0,
            rideLoads = mapOf("r1" to 100.0),
        )
        assertTrue(withLoads.adapted)
        // Die neuen Wochen rechnen ihre Budgets mit der uebergebenen CTL.
        val nextWeek = withLoads.plan.weeks[1]
        assertEquals(
            7 * (40.0 + defaultTargetRampPerWeek / ctlWeeklyResponse),
            nextWeek.sessions.sumOf { it.targetLoad ?: 0.0 },
            2.0,
        )
    }

    @Test
    fun `das Erreichte ist das Maximum der letzten Wochen, nicht die letzte allein`() {
        // Woche 0: 80 km gefahren (84 % — ok). Woche 1: nur 20 von 105 km →
        // Ausloeser. Der Neustart rechnet trotzdem mit den 80 km aus Woche 0.
        val rides = listOf(
            ride("w0", dayAfterFirstMonday(5), 80.0),
            ride("w1", dayAfterFirstMonday(7 + 5), 20.0),
        )
        val thirdWeekTuesday = dayAfterFirstMonday(14 + 1)
        val result = adaptPlan(plan, rides, now = thirdWeekTuesday)

        assertTrue(result.adapted)
        assertEquals(80, result.plan.weeks[2].targetKm)
    }
}
