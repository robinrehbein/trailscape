package de.trailscape.app.widget

import de.trailscape.core.FitnessLevel
import de.trailscape.core.Goal
import de.trailscape.core.RideStats
import de.trailscape.core.RideSummary
import de.trailscape.core.TrainingPlan
import de.trailscape.core.TrainingWeek
import de.trailscape.core.WeekKind
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Feste Daten, feste Zone (UTC): Der Test haengt weder von der Uhr noch von der
 * Zeitzone des Rechners ab. „Jetzt" ist Mittwoch, 18.11.2026 — Montag der Woche
 * ist der 16.11.
 */
class WidgetStateTest {

    private val zone = ZoneOffset.UTC
    private val monday = LocalDate.of(2026, 11, 16)
    private val now = LocalDateTime.of(2026, 11, 18, 12, 0)

    private fun dateOf(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

    private fun ms(day: LocalDate): Long = day.atTime(10, 0).toInstant(zone).toEpochMilli()

    private fun ride(day: LocalDate, km: Double, planned: Boolean = false) = RideSummary(
        id = "r-$day-$km",
        name = "Tour",
        createdAt = ms(day),
        updatedAt = ms(day),
        stats = RideStats(distanceKm = km, ascentM = 0.0, descentM = 0.0),
        planned = planned,
    )

    private fun state(rides: List<RideSummary>, plan: TrainingPlan? = null) =
        buildWidgetState(rides, plan, now, ::dateOf, zone)

    /** Plan mit genau einer Woche, die den 16.–23.11. abdeckt. */
    private fun planWithTarget(targetKm: Int) = TrainingPlan(
        createdAt = ms(monday.minusDays(7)),
        goal = Goal(name = "Ziel", distanceKm = 100.0, date = ms(monday.plusDays(30))),
        level = FitnessLevel.EINSTEIGER,
        weeks = listOf(
            TrainingWeek(
                index = 0,
                start = monday.atStartOfDay().toInstant(zone).toEpochMilli(),
                end = monday.plusDays(7).atStartOfDay().toInstant(zone).toEpochMilli(),
                kind = WeekKind.AUFBAU,
                targetKm = targetKm,
                sessions = emptyList(),
            ),
        ),
    )

    /** Vier volle Vorwochen mit je 40 km: das adaptive Ziel ist dann 45 km. */
    private fun steadyHistory() =
        (1L..4L).map { ride(monday.minusWeeks(it).plusDays(2), 40.0) }

    @Test
    fun `ohne Touren ist es der Leerzustand`() {
        val s = state(emptyList())

        assertFalse(s.hasRides)
        assertEquals(0, s.streakWeeks)
        assertFalse(s.thisWeekSecured)
        assertFalse(s.streakAtRisk)
        assertNull(s.targetKm)
        assertEquals(0f, s.fraction)
        assertEquals(0.0, s.riddenKm)
    }

    @Test
    fun `geplante Routen zaehlen nicht als Tour`() {
        val s = state(listOf(ride(monday.plusDays(1), 50.0, planned = true)))

        assertFalse(s.hasRides)
        assertEquals(0.0, s.riddenKm)
    }

    @Test
    fun `Serie in Gefahr - Vorwochen gefahren, diese Woche noch nicht`() {
        val s = state(steadyHistory())

        assertTrue(s.hasRides)
        assertEquals(4, s.streakWeeks)
        assertFalse(s.thisWeekSecured)
        assertTrue(s.streakAtRisk)
        // Mittwoch: Mi, Do, Fr, Sa, So.
        assertEquals(5, s.daysLeft)
        assertEquals(0.0, s.riddenKm)
        assertEquals(0f, s.fraction)
    }

    @Test
    fun `eine Tour diese Woche sichert die Serie und verlaengert sie`() {
        val s = state(steadyHistory() + ride(monday.plusDays(1), 20.0))

        assertEquals(5, s.streakWeeks)
        assertTrue(s.thisWeekSecured)
        assertFalse(s.streakAtRisk)
        assertEquals(20.0, s.riddenKm)
    }

    @Test
    fun `adaptives Ziel ohne Plan`() {
        val s = state(steadyHistory() + ride(monday.plusDays(1), 18.0))

        assertEquals(45, s.targetKm)
        assertEquals(18.0, s.riddenKm)
        assertEquals(0.4f, s.fraction, 1e-6f)
        assertFalse(s.goalReached)
    }

    @Test
    fun `erste Woche ohne Vorgeschichte nimmt den Einstiegswert`() {
        val s = state(listOf(ride(monday.plusDays(1), 12.0)))

        assertEquals(30, s.targetKm)
        assertEquals(1, s.streakWeeks)
        assertEquals(0.4f, s.fraction, 1e-6f)
    }

    @Test
    fun `Plan-Ziel schlaegt das adaptive Ziel`() {
        val rides = steadyHistory() + ride(monday.plusDays(1), 30.0)

        val s = state(rides, planWithTarget(60))

        assertEquals(60, s.targetKm)
        assertEquals(30.0, s.riddenKm)
        assertEquals(0.5f, s.fraction, 1e-6f)
    }

    @Test
    fun `Planwoche ohne Ziel faellt auf das adaptive zurueck`() {
        val s = state(steadyHistory(), planWithTarget(0))

        assertEquals(45, s.targetKm)
    }

    @Test
    fun `Plan, der noch nicht begonnen hat, zaehlt nicht`() {
        val future = planWithTarget(60).let { p ->
            p.copy(
                weeks = p.weeks.map {
                    it.copy(start = it.start + 14L * 86_400_000, end = it.end + 14L * 86_400_000)
                },
            )
        }

        val s = state(steadyHistory(), future)

        assertEquals(45, s.targetKm)
    }

    @Test
    fun `erreichtes Ziel bleibt bei 100 Prozent stehen`() {
        val s = state(steadyHistory() + ride(monday.plusDays(1), 70.0), planWithTarget(60))

        assertTrue(s.goalReached)
        assertEquals(1f, s.fraction)
        assertEquals(70.0, s.riddenKm)
    }

    @Test
    fun `Sonntag ist der letzte Tag`() {
        val sunday = LocalDateTime.of(2026, 11, 22, 20, 0)

        val s = buildWidgetState(steadyHistory(), null, sunday, ::dateOf, zone)

        assertEquals(1, s.daysLeft)
        assertTrue(s.streakAtRisk)
    }

    @Test
    fun `das von der Startseite gezeigte Wochenziel gilt - aber nur fuer seine Woche`() {
        val rides = steadyHistory() + ride(monday, 20.0)
        val own = buildWidgetState(rides, planWithTarget(150), now, ::dateOf, zone)
        assertEquals(150, own.targetKm)

        // Die Startseite hat den angepassten Plan gezeigt: 120 statt 150.
        val shown = buildWidgetState(
            rides, planWithTarget(150), now, ::dateOf, zone, shownTarget = ShownWeekTarget(monday, 120),
        )
        assertEquals(120, shown.targetKm)
        assertEquals(own.riddenKm, shown.riddenKm)

        // Eine Notiz der Vorwoche ist veraltet und zaehlt nicht.
        val stale = buildWidgetState(
            rides, planWithTarget(150), now, ::dateOf, zone, shownTarget = ShownWeekTarget(monday.minusWeeks(1), 120),
        )
        assertEquals(150, stale.targetKm)
    }

    @Test
    fun `Notiz des Wochenziels uebersteht das Speichern`() {
        val target = ShownWeekTarget(monday, 90)
        assertEquals("2026-11-16|90", target.encode())
        assertEquals(target, ShownWeekTarget.decode(target.encode()))
        for (broken in listOf(null, "", "90", "gestern|90", "2026-11-16|0", "2026-11-16|x", "a|b|c")) {
            assertNull(ShownWeekTarget.decode(broken), "Eingabe $broken")
        }
    }

    // --- Ziel-Snapshot zum Start einer Aufzeichnung ---

    private fun startMs(at: LocalDateTime): Long = at.toInstant(zone).toEpochMilli()

    @Test
    fun `Ziel-Snapshot zaehlt die laufende Tour nicht mit, auch wenn sie schon gespeichert ist`() {
        val laufend = ride(monday.plusDays(2), 50.0)
        val rides = steadyHistory() + ride(monday.plusDays(1), 18.0) + laufend
        val s = zielSnapshotZumStart(rides, null, startMs(now), laufend.id, zone, null)
        // Adaptives Ziel 45 km, 18 km gefahren; weiteste Tour 40 km, nicht die
        // laufende mit 50 km.
        assertEquals(27.0, s?.wochenRestKm)
        assertEquals(40.0, s?.laengsteKm)
    }

    @Test
    fun `Ziel-Snapshot rechnet die Woche zum Start, nicht zum Neuladen`() {
        // Sonntagabend gestartet; ein Neuladen nach Mitternacht nimmt weiter
        // die Woche des Starts.
        val sonntag = monday.plusDays(6).atTime(22, 0)
        val rides = steadyHistory() + ride(monday.plusDays(1), 18.0)
        val s = zielSnapshotZumStart(rides, null, startMs(sonntag), "laufend", zone, null)
        assertEquals(27.0, s?.wochenRestKm)
    }
}
