package de.trailscape.app.ui

import de.trailscape.core.Goal
import de.trailscape.core.GoalType
import de.trailscape.core.PlanPreferences
import de.trailscape.core.RideInfo
import de.trailscape.core.assessFitness
import de.trailscape.core.generatePlan
import de.trailscape.core.i18n.CoreTextsDe
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** Der Quittier-Schluessel der Karte „Plan und Ziel passen nicht zusammen". */
class PlanFeasibilityKeyTest {

    private val now = LocalDate.of(2026, 11, 24).atTime(10, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private val dateMs = LocalDate.of(2027, 2, 20).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun plan(goal: Goal = Goal("Lang", 200.0, null, dateMs), prefs: PlanPreferences? = null) =
        generatePlan(goal, assessFitness(emptyList<RideInfo>()), now = now, preferences = prefs, texts = CoreTextsDe)

    @Test
    fun `Plan ohne Zielzeit und Praeferenzen behaelt den frueheren Schluessel`() {
        val p = plan()
        // Format vor dem Update: distanz|datum|wochen — eine alte Quittierung gilt weiter.
        assertEquals("200.0|$dateMs|${p.weeks.size}", planFeasibilityIdentityKey(p))
        // Leere Praeferenzen (alle Tage, nichts sonst) zaehlen nicht.
        assertEquals(planFeasibilityIdentityKey(p), planFeasibilityIdentityKey(p.copy(preferences = PlanPreferences())))
        assertEquals(
            planFeasibilityIdentityKey(p),
            planFeasibilityIdentityKey(p.copy(preferences = PlanPreferences(trainingDays = (0..6).toList()))),
        )
    }

    @Test
    fun `Zeitbudget, Tage, lange Tour und Zielzeit aendern den Schluessel`() {
        val base = planFeasibilityIdentityKey(plan())
        val hours3 = planFeasibilityIdentityKey(plan(prefs = PlanPreferences(weeklyHours = 3.0)))
        val hours5 = planFeasibilityIdentityKey(plan(prefs = PlanPreferences(weeklyHours = 5.0)))
        val days = planFeasibilityIdentityKey(plan(prefs = PlanPreferences(trainingDays = listOf(1, 3, 5))))
        val longDay = planFeasibilityIdentityKey(plan(prefs = PlanPreferences(longRideDay = 6)))
        val target = planFeasibilityIdentityKey(plan(Goal("Lang", 200.0, null, dateMs, targetDurationMin = 600)))
        val all = listOf(base, hours3, hours5, days, longDay, target)
        assertEquals(all.size, all.toSet().size, "Alle Schluessel muessen verschieden sein: $all")
    }

    @Test
    fun `Gleiche Praeferenzen in anderer Schreibweise ergeben denselben Schluessel`() {
        val a = plan(prefs = PlanPreferences(trainingDays = listOf(5, 1, 3, 1), weeklyHours = 6.0))
        val b = plan(prefs = PlanPreferences(trainingDays = listOf(1, 3, 5), weeklyHours = 6.0))
        assertEquals(planFeasibilityIdentityKey(a), planFeasibilityIdentityKey(b))
        // Ein unplausibles Budget (ueber 40 h) wird vom Plan ignoriert und aendert den Schluessel nicht.
        val c = plan(prefs = PlanPreferences(trainingDays = listOf(1, 3, 5), weeklyHours = 99.0))
        assertEquals(planFeasibilityIdentityKey(a).substringBefore("|prefs="), planFeasibilityIdentityKey(c).substringBefore("|prefs="))
        assertEquals(planFeasibilityIdentityKey(plan(prefs = PlanPreferences(trainingDays = listOf(1, 3, 5)))), planFeasibilityIdentityKey(c))
    }

    @Test
    fun `Zieltyp geht in den Schluessel ein`() {
        val event = plan(Goal("X", 0.0, null, dateMs))
        val fitness = event.copy(goal = event.goal.copy(type = GoalType.FITNESS))
        assertNotEquals(planFeasibilityIdentityKey(event), planFeasibilityIdentityKey(fitness))
    }
}
