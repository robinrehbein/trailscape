package de.trailscape.app.ui.training

import de.trailscape.core.Goal
import de.trailscape.core.GoalType
import de.trailscape.core.PlanPreferences
import de.trailscape.core.RideInfo
import de.trailscape.core.assessFitness
import de.trailscape.core.assessPlanFeasibility
import de.trailscape.core.generatePlan
import de.trailscape.core.i18n.CoreTextsDe
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Reine Logik des Zielblatts: Eingaben lesen, Praeferenzen bilden, Neuerzeugung entscheiden. */
class PlanInputsTest {

    private val now = LocalDate.of(2026, 11, 24).atTime(10, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private val date = LocalDate.of(2027, 2, 20)

    private fun eventInput(target: String = "", hours: String = "") = GoalFormInput(
        name = "Rennen",
        fitness = false,
        distanceText = "60",
        ascentText = "700",
        date = date,
        targetText = target,
        hoursText = hours,
    )

    private fun ok(input: GoalFormInput) = assertIs<GoalFormResult.Ok>(parseGoalForm(input, now))

    private fun planFor(input: GoalFormInput) = ok(input).let {
        generatePlan(
            it.goal, assessFitness(emptyList<RideInfo>()), now = now,
            preferences = it.preferences, texts = CoreTextsDe,
        )
    }

    @Test
    fun `Fitnessziel entsteht ueber fitnessGoal und prueft die Blocklaenge`() {
        val r = ok(GoalFormInput(name = "Form", fitness = true, weeksText = "8", benchmarkText = "40,5"))
        assertEquals(GoalType.FITNESS, r.goal.type)
        assertEquals(40.5, r.goal.distanceKm)
        assertEquals(8, r.fitnessWeeks)
        assertNull(r.goal.targetDurationMin)
        for (bad in listOf("3", "17", "", "x")) {
            val e = parseGoalForm(GoalFormInput(name = "Form", fitness = true, weeksText = bad), now)
            assertEquals(GoalFormResult.Invalid(GoalFormError.FITNESS_WEEKS), e)
        }
        assertEquals(
            GoalFormResult.Invalid(GoalFormError.BENCHMARK),
            parseGoalForm(GoalFormInput(name = "Form", fitness = true, weeksText = "6", benchmarkText = "abc"), now),
        )
    }

    @Test
    fun `Eventformular meldet Fehler in Blattreihenfolge`() {
        assertEquals(GoalFormResult.Invalid(GoalFormError.NAME), parseGoalForm(eventInput().copy(name = " "), now))
        assertEquals(GoalFormResult.Invalid(GoalFormError.DISTANCE), parseGoalForm(eventInput().copy(distanceText = "0"), now))
        assertEquals(GoalFormResult.Invalid(GoalFormError.DATE), parseGoalForm(eventInput().copy(date = null), now))
        assertEquals(GoalFormResult.Invalid(GoalFormError.TARGET), parseGoalForm(eventInput(target = "zwei"), now))
        assertEquals(GoalFormResult.Invalid(GoalFormError.HOURS), parseGoalForm(eventInput(hours = "41"), now))
        assertEquals(GoalFormResult.Invalid(GoalFormError.HOURS), parseGoalForm(eventInput(hours = "0"), now))
    }

    @Test
    fun `Praeferenzen - alle Tage sind keine Einschraenkung`() {
        assertNull(buildPlanPreferences(ALL_DAYS_MASK, -1, null))
        val p = buildPlanPreferences(maskOfDays(listOf(1, 3, 5)), 5, 6.5)
        assertEquals(PlanPreferences(listOf(1, 3, 5), 5, 6.5), p)
        // Lange Tour auf einem abgewaehlten Tag faellt weg.
        assertEquals(PlanPreferences(listOf(1, 3), null, null), buildPlanPreferences(maskOfDays(listOf(1, 3)), 5, null))
        assertEquals(6.5, ok(eventInput(hours = "6,5")).preferences?.weeklyHours)
    }

    @Test
    fun `Zielzeit aendert den Plan - nur der Name nicht`() {
        val plan = planFor(eventInput())
        fun rebuild(input: GoalFormInput) = ok(input).let { planNeedsRebuild(plan, it.goal, it.preferences, it.fitnessWeeks) }
        assertFalse(rebuild(eventInput()))
        assertFalse(rebuild(eventInput().copy(name = "Anders")))
        assertTrue(rebuild(eventInput(target = "2:10")))
        assertTrue(rebuild(eventInput().copy(distanceText = "80")))
        assertTrue(rebuild(eventInput(hours = "5")))
        assertTrue(rebuild(eventInput().copy(daysMask = maskOfDays(listOf(0, 2, 5)))))
        assertTrue(rebuild(GoalFormInput(name = "Form", fitness = true, weeksText = "8")))
        assertTrue(planNeedsRebuild(null, ok(eventInput()).goal, null, null))
    }

    @Test
    fun `Fitnessplan bleibt bei gleicher Blocklaenge`() {
        val input = GoalFormInput(name = "Form", fitness = true, weeksText = "8")
        val plan = planFor(input)
        assertEquals(8, plan.weeks.size)
        val again = ok(input.copy(name = "Neu"))
        assertFalse(planNeedsRebuild(plan, again.goal, again.preferences, again.fitnessWeeks))
        val longer = ok(input.copy(weeksText = "10"))
        assertTrue(planNeedsRebuild(plan, longer.goal, longer.preferences, longer.fitnessWeeks))
    }

    @Test
    fun `Hinweiszeilen nennen Zeitbudget nur bei offener Karte`() {
        val goal = Goal("Lang", 200.0, null, date.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
        val plan = generatePlan(
            goal, assessFitness(emptyList<RideInfo>()), now = now,
            preferences = PlanPreferences(weeklyHours = 3.0), texts = CoreTextsDe,
        )
        val f = assessPlanFeasibility(plan, texts = CoreTextsDe)
        val open = feasibilityNoteLines(plan, f, feasibilityOpen = true, texts = CoreTextsDe)
        val closed = feasibilityNoteLines(plan, f, feasibilityOpen = false, texts = CoreTextsDe)
        assertEquals(if (f.budgetLimited) 1 else 0, open.size)
        assertTrue(closed.isEmpty())
    }
}
