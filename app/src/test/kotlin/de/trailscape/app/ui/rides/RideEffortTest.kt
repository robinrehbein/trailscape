package de.trailscape.app.ui.rides

import de.trailscape.core.FitnessLevel
import de.trailscape.core.Goal
import de.trailscape.core.PlanSessionStatus
import de.trailscape.core.RideStats
import de.trailscape.core.RideSummary
import de.trailscape.core.SessionIntensity
import de.trailscape.core.TrainingPlan
import de.trailscape.core.TrainingSession
import de.trailscape.core.TrainingWeek
import de.trailscape.core.WeekKind
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests fuer `ui/rides/RideEffort.kt`: das Haerte-Wort aus Last pro Stunde,
 * die Planzuordnung einer Tour und der Klartext-Satz der Detailansicht.
 */
class RideEffortTest {

    // ------------------------------------------------------------ rideEffort

    @Test
    fun `unter 55 Last pro Stunde ist locker`() {
        // 2 h, Last 100 → 50 pro Stunde.
        assertEquals(RideEffort.LOCKER, rideEffort(100.0, 7200))
    }

    @Test
    fun `genau an der Grenze gilt die hoehere Stufe`() {
        assertEquals(RideEffort.MITTEL, rideEffort(55.0, 3600))
        assertEquals(RideEffort.HART, rideEffort(75.0, 3600))
    }

    @Test
    fun `zwischen 55 und 75 ist mittel, darueber hart`() {
        assertEquals(RideEffort.MITTEL, rideEffort(74.9, 3600))
        // 1:30 h mit Last 130 → 86,7 pro Stunde.
        assertEquals(RideEffort.HART, rideEffort(130.0, 5400))
    }

    @Test
    fun `ohne Last oder Dauer gibt es kein Wort`() {
        assertNull(rideEffort(null, 3600))
        assertNull(rideEffort(0.0, 3600))
        assertNull(rideEffort(50.0, null))
        assertNull(rideEffort(50.0, 30))
    }

    @Test
    fun `Fahrzeit geht vor Gesamtdauer`() {
        val stats = RideStats(
            distanceKm = 40.0,
            ascentM = 0.0,
            descentM = 0.0,
            durationS = 3 * 3600,
            movingTimeS = 3600,
        )
        // Last 80: ueber die Fahrzeit 80/h (hart), ueber die Gesamtdauer 26/h.
        assertEquals(RideEffort.HART, rideEffort(80.0, stats.movingTimeS ?: stats.durationS))
    }

    // ------------------------------------------------------------ planMatchForRide

    private val zone = ZoneId.systemDefault()
    private val monday: LocalDate = LocalDate.of(2026, 9, 21)
    private fun at(day: Long, hour: Int = 18): Long =
        monday.plusDays(day).atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    private fun summary(id: String, createdAt: Long, km: Double) = RideSummary(
        id = id,
        name = "Tour $id",
        createdAt = createdAt,
        updatedAt = createdAt,
        stats = RideStats(distanceKm = km, ascentM = 0.0, descentM = 0.0),
    )

    private val plan = TrainingPlan(
        createdAt = at(-7),
        goal = Goal(name = "Ziel", distanceKm = 100.0, date = at(60)),
        level = FitnessLevel.EINSTEIGER,
        weeks = listOf(
            TrainingWeek(
                index = 0,
                start = at(0, 0),
                end = at(7, 0),
                kind = WeekKind.AUFBAU,
                targetKm = 60,
                sessions = listOf(
                    TrainingSession(
                        day = "Di",
                        title = "Locker",
                        description = "",
                        targetKm = 30,
                        intensity = SessionIntensity.LOCKER,
                    ),
                ),
            ),
        ),
    )

    @Test
    fun `Tour am Tag der Einheit erledigt sie`() {
        val rides = listOf(summary("a", at(1), 32.0))
        val match = planMatchForRide(plan, rides, "a", emptyMap(), now = at(3))
        assertNotNull(match)
        assertEquals(PlanSessionStatus.ERLEDIGT, match.status)
        assertEquals("Locker", match.session.title)
    }

    @Test
    fun `zu kurze Tour erledigt die Einheit nur teilweise`() {
        val rides = listOf(summary("a", at(1), 10.0))
        val match = planMatchForRide(plan, rides, "a", emptyMap(), now = at(3))
        assertEquals(PlanSessionStatus.TEILWEISE, match?.status)
    }

    @Test
    fun `Tour ausserhalb der Planwochen und ohne Plan hat keinen Treffer`() {
        val rides = listOf(summary("a", at(20), 30.0))
        assertNull(planMatchForRide(plan, rides, "a", emptyMap(), now = at(21)))
        assertNull(planMatchForRide(null, rides, "a", emptyMap(), now = at(21)))
    }

    @Test
    fun `die laengere Tour am selben Tag bekommt die Einheit, die andere nicht`() {
        val rides = listOf(summary("kurz", at(1, 8), 12.0), summary("lang", at(1, 18), 35.0))
        assertEquals(PlanSessionStatus.ERLEDIGT, planMatchForRide(plan, rides, "lang", emptyMap(), at(3))?.status)
        assertNull(planMatchForRide(plan, rides, "kurz", emptyMap(), at(3)))
    }

    // ------------------------------------------------------------ rideNote

    private val lockerSession = plan.weeks[0].sessions[0]

    @Test
    fun `Plantreffer mit ruhigem Puls klingt wie im Zieldesign`() {
        val note = rideNote(
            effort = RideEffort.LOCKER,
            match = RidePlanMatch(lockerSession, PlanSessionStatus.ERLEDIGT),
            decouplingPercent = 3.2,
        )
        assertEquals("Passt zum Plan.", note?.headline)
        assertEquals("Lockere Einheit erledigt, dein Puls blieb bis zum Schluss ruhig.", note?.body)
    }

    @Test
    fun `zu hart gefahrene lockere Einheit wird benannt`() {
        val note = rideNote(
            effort = RideEffort.HART,
            match = RidePlanMatch(lockerSession, PlanSessionStatus.ERLEDIGT),
            decouplingPercent = 2.0,
        )
        assertEquals("Lockere Einheit erledigt, allerdings härter als vorgesehen.", note?.body)
    }

    @Test
    fun `teilweise erledigt nennt die geplante Distanz`() {
        val note = rideNote(null, RidePlanMatch(lockerSession, PlanSessionStatus.TEILWEISE), null)
        assertEquals("Zum Teil nach Plan.", note?.headline)
        assertTrue(note!!.body.contains("30 km"))
    }

    @Test
    fun `ohne Plan ordnet der Satz die Haerte ein, ohne beides gibt es keinen`() {
        assertEquals("Harte Tour.", rideNote(RideEffort.HART, null, null)?.headline)
        assertEquals("Locker gefahren.", rideNote(RideEffort.LOCKER, null, null)?.headline)
        assertNull(rideNote(null, null, 1.0))
    }
}
