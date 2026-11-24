package de.trailscape.app.ui.training

import de.trailscape.core.FITNESS_BENCHMARK_MAX_KM
import de.trailscape.core.FITNESS_BENCHMARK_MIN_KM
import de.trailscape.core.FITNESS_MAX_WEEKS
import de.trailscape.core.FITNESS_MIN_WEEKS
import de.trailscape.app.R
import de.trailscape.app.i18n.UiText
import de.trailscape.core.Goal
import de.trailscape.core.GoalFinishPrediction
import de.trailscape.core.GoalPrognosis
import de.trailscape.core.TrainingPlan
import de.trailscape.core.i18n.AppLanguage
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
import kotlin.test.assertNotNull
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
        fun rebuild(input: GoalFormInput) = ok(input).let { planNeedsRebuild(plan, it.goal, it.preferences) }
        assertFalse(rebuild(eventInput()))
        assertFalse(rebuild(eventInput().copy(name = "Anders")))
        assertTrue(rebuild(eventInput(target = "2:10")))
        assertTrue(rebuild(eventInput().copy(distanceText = "80")))
        assertTrue(rebuild(eventInput(hours = "5")))
        assertTrue(rebuild(eventInput().copy(daysMask = maskOfDays(listOf(0, 2, 5)))))
        assertTrue(rebuild(GoalFormInput(name = "Form", fitness = true, weeksText = "8")))
        assertTrue(planNeedsRebuild(null, ok(eventInput()).goal, null))
    }

    @Test
    fun `Fitnessplan bleibt bei gleicher Blocklaenge`() {
        val input = GoalFormInput(name = "Form", fitness = true, weeksText = "8")
        val plan = planFor(input)
        assertEquals(8, plan.weeks.size)
        val again = ok(input.copy(name = "Neu"))
        assertFalse(planNeedsRebuild(plan, again.goal, again.preferences))
        val longer = ok(input.copy(weeksText = "10"))
        assertTrue(planNeedsRebuild(plan, longer.goal, longer.preferences))
    }

    @Test
    fun `Hinweiszeilen nennen das Zeitbudget, solange es der Engpass ist - auch nach dem Quittieren`() {
        // 200 km in rund 12 Wochen mit nur 3 h pro Woche: das Budget ist der Engpass.
        val goal = Goal("Lang", 200.0, null, date.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
        val plan = generatePlan(
            goal, assessFitness(emptyList<RideInfo>()), now = now,
            preferences = PlanPreferences(weeklyHours = 3.0), texts = CoreTextsDe,
        )
        val f = assessPlanFeasibility(plan, texts = CoreTextsDe)
        assertFalse(f.feasible)
        assertTrue(f.budgetLimited, "Der Testplan muss am Zeitbudget scheitern")
        val needed = assertNotNull(f.hoursNeededForGoal)
        val lines = feasibilityNoteLines(plan, f, texts = CoreTextsDe)
        assertEquals(CoreTextsDe.training.planBudgetLimited(3.0, needed), lines.first())

        // Ohne Budget gibt es keine Budgetzeile.
        val noBudget = generatePlan(goal, assessFitness(emptyList<RideInfo>()), now = now, texts = CoreTextsDe)
        val f2 = assessPlanFeasibility(noBudget, texts = CoreTextsDe)
        assertFalse(f2.budgetLimited)
        assertTrue(feasibilityNoteLines(noBudget, f2, texts = CoreTextsDe).none { it.contains("Zeitbudget") })
    }

    // ------------------------------------------------------------ Hoehenmeter

    @Test
    fun `Hoehenmeter vertragen Tausenderpunkt und Leerzeichen und melden Unlesbares`() {
        fun ascent(text: String) = parseGoalForm(eventInput().copy(ascentText = text), now)
        for ((text, expected) in listOf(
            "1200" to 1200.0, "1.200" to 1200.0, "1,200" to 1200.0, "1 200" to 1200.0,
            "1\u00A0200" to 1200.0, "12.500" to 12500.0, "850,5" to 850.5, "850.5" to 850.5, "0" to 0.0,
        )) {
            assertEquals(expected, assertIs<GoalFormResult.Ok>(ascent(text), text).goal.ascentM, text)
        }
        assertNull(assertIs<GoalFormResult.Ok>(ascent("")).goal.ascentM)
        for (bad in listOf("abc", "-5", "1.2.3", "12a")) {
            assertEquals(GoalFormResult.Invalid(GoalFormError.ASCENT), ascent(bad), bad)
        }
        // Reihenfolge wie im Blatt: Distanz vor Hoehenmetern vor Datum.
        assertEquals(
            GoalFormResult.Invalid(GoalFormError.DISTANCE),
            parseGoalForm(eventInput().copy(distanceText = "x", ascentText = "abc"), now),
        )
        assertEquals(
            GoalFormResult.Invalid(GoalFormError.ASCENT),
            parseGoalForm(eventInput().copy(ascentText = "abc", date = null), now),
        )
    }

    // ------------------------------------------------------------ Zeitbudget

    @Test
    fun `Zeitbudget - Fehlerfaelle und Grenzen`() {
        assertNull(parseWeeklyHours("").getOrThrow())
        assertNull(parseWeeklyHours("  ").getOrThrow())
        assertEquals(6.5, parseWeeklyHours("6,5").getOrThrow())
        assertEquals(6.5, parseWeeklyHours(" 6.5 ").getOrThrow())
        assertEquals(40.0, parseWeeklyHours("40").getOrThrow())
        assertEquals(PlanPreferences.MAX_PLAUSIBLE_WEEKLY_HOURS, 40.0)
        for (bad in listOf("0", "0,0", "-2", "40,1", "41", "abc", "6h", "NaN", "Infinity", "6,5,1")) {
            assertTrue(parseWeeklyHours(bad).isFailure, bad)
        }
    }

    @Test
    fun `Praeferenzen - lange Tour auf abgewaehltem Tag faellt weg, Rest bleibt`() {
        val days = maskOfDays(listOf(0, 2, 4))
        // Sonntag ist abgewaehlt: keine lange Tour vorgeben, Tage und Stunden bleiben.
        assertEquals(PlanPreferences(listOf(0, 2, 4), null, 5.0), buildPlanPreferences(days, 6, 5.0))
        assertEquals(PlanPreferences(listOf(0, 2, 4), 2, null), buildPlanPreferences(days, 2, null))
        // Alle Tage, lange Tour am Sonntag, nichts sonst: nur die lange Tour.
        assertEquals(PlanPreferences(null, 6, null), buildPlanPreferences(ALL_DAYS_MASK, 6, null))
        // Nichts gewaehlt (leere Maske) heisst keine Einschraenkung; ungueltiger Tag faellt weg.
        assertNull(buildPlanPreferences(0, 9, null))
        // Ueber das Formular: lange Tour (So) auf abgewaehltem Tag wird nicht uebernommen.
        val r = ok(eventInput(hours = "5").copy(daysMask = days, longRideDay = 6))
        assertEquals(PlanPreferences(listOf(0, 2, 4), null, 5.0), r.preferences)
    }

    @Test
    fun `Der letzte gewaehlte Tag laesst sich nicht abwaehlen`() {
        assertFalse(canToggleDay(selected = true, selectedCount = 1))
        assertTrue(canToggleDay(selected = true, selectedCount = 2))
        assertTrue(canToggleDay(selected = false, selectedCount = 1))
    }

    // ------------------------------------------------------------ Vergleichsrunde

    @Test
    fun `Vergleichsrunde - nur leer oder im Bereich des Kerns`() {
        fun parse(text: String) = parseGoalForm(GoalFormInput(name = "Form", fitness = true, weeksText = "8", benchmarkText = text), now)
        assertEquals(10.0, FITNESS_BENCHMARK_MIN_KM)
        assertEquals(120.0, FITNESS_BENCHMARK_MAX_KM)
        for ((text, km) in listOf("" to 0.0, "  " to 0.0, "10" to 10.0, "40,5" to 40.5, "120" to 120.0)) {
            assertEquals(km, assertIs<GoalFormResult.Ok>(parse(text), text).goal.distanceKm, text)
        }
        for (bad in listOf("0", "9,9", "9", "120,1", "121", "500", "-5", "abc", "NaN")) {
            assertEquals(GoalFormResult.Invalid(GoalFormError.BENCHMARK), parse(bad), bad)
        }
    }

    @Test
    fun `Blocklaenge - Grenzen kommen aus dem Kern`() {
        assertEquals(4, FITNESS_MIN_WEEKS)
        assertEquals(16, FITNESS_MAX_WEEKS)
        for (w in listOf(FITNESS_MIN_WEEKS, FITNESS_MAX_WEEKS)) {
            assertIs<GoalFormResult.Ok>(parseGoalForm(GoalFormInput(name = "Form", fitness = true, weeksText = "$w"), now))
        }
    }

    // ------------------------------------------------------------ Laufender Fitnessblock

    private fun fitnessInput(plan: TrainingPlan, weeks: String = plan.weeks.size.toString(), hours: String = "") =
        GoalFormInput(
            name = "Form", fitness = true, weeksText = weeks, hoursText = hours,
            runningBlockEnd = plan.goal.date, prefilledWeeks = plan.weeks.size,
        )

    private fun at(weeksLater: Long) = now + weeksLater * 7 * 24 * 3600 * 1000L + 3600_000L

    private fun mondayOf(ms: Long): LocalDate =
        java.time.Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate().let { it.minusDays((it.dayOfWeek.value - 1).toLong()) }

    @Test
    fun `Laufender Fitnessblock behaelt sein Ende, wenn nur das Zeitbudget geaendert wird`() {
        val plan = planFor(GoalFormInput(name = "Form", fitness = true, weeksText = "8"))
        val later = at(3) // Woche 4 von 8
        val r = assertIs<GoalFormResult.Ok>(parseGoalForm(fitnessInput(plan, hours = "5"), later))
        assertTrue(r.keepsBlockEnd)
        assertEquals(plan.goal.date, r.goal.date)
        assertEquals(5, r.fitnessWeeks)
        assertEquals(5.0, r.preferences?.weeklyHours)
        assertTrue(planNeedsRebuild(plan, r.goal, r.preferences))
        assertFalse(restOfBlockTooShort(r, rebuild = true))

        val rebuilt = generatePlan(r.goal, assessFitness(emptyList<RideInfo>()), now = later, preferences = r.preferences, texts = CoreTextsDe)
        assertEquals(5, rebuilt.weeks.size)
        assertEquals(plan.goal.date, rebuilt.goal.date)

        // Nur der Name: kein Neubau, auch wenn der Block laeuft.
        val nameOnly = assertIs<GoalFormResult.Ok>(parseGoalForm(fitnessInput(plan).copy(name = "Neu"), later))
        assertFalse(planNeedsRebuild(plan, nameOnly.goal, nameOnly.preferences))
    }

    @Test
    fun `Aktiv geaenderte Blocklaenge beginnt einen neuen Block ab heute`() {
        val plan = planFor(GoalFormInput(name = "Form", fitness = true, weeksText = "8"))
        val later = at(3)
        val r = assertIs<GoalFormResult.Ok>(parseGoalForm(fitnessInput(plan, weeks = "10"), later))
        assertFalse(r.keepsBlockEnd)
        assertEquals(10, r.fitnessWeeks)
        assertTrue(planNeedsRebuild(plan, r.goal, r.preferences))
        assertEquals(mondayOf(later).plusWeeks(9), mondayOf(r.goal.date))
        // Auch dieselbe Laenge neu eingegeben ist keine aktive Aenderung.
        assertTrue(assertIs<GoalFormResult.Ok>(parseGoalForm(fitnessInput(plan, weeks = " 8 "), later)).keepsBlockEnd)
    }

    @Test
    fun `Zu wenige Restwochen - Meldung statt Neubau, Umbenennen bleibt moeglich`() {
        val plan = planFor(GoalFormInput(name = "Form", fitness = true, weeksText = "8"))
        val late = at(5) // Woche 6 von 8: noch 3 Wochen
        val r = assertIs<GoalFormResult.Ok>(parseGoalForm(fitnessInput(plan, hours = "5"), late))
        assertTrue(r.keepsBlockEnd)
        assertEquals(3, r.fitnessWeeks)
        assertTrue(planNeedsRebuild(plan, r.goal, r.preferences))
        assertTrue(restOfBlockTooShort(r, rebuild = true))
        // Ohne Neubau-Grund (nur Name) ist es kein Fehler.
        val nameOnly = assertIs<GoalFormResult.Ok>(parseGoalForm(fitnessInput(plan).copy(name = "Neu"), late))
        assertFalse(restOfBlockTooShort(nameOnly, rebuild = planNeedsRebuild(plan, nameOnly.goal, nameOnly.preferences)))
    }

    @Test
    fun `Ein beendeter Block beginnt bei gleicher Laenge heute neu`() {
        val plan = planFor(GoalFormInput(name = "Form", fitness = true, weeksText = "8"))
        val after = at(9)
        val r = assertIs<GoalFormResult.Ok>(parseGoalForm(fitnessInput(plan), after))
        assertFalse(r.keepsBlockEnd)
        assertEquals(8, r.fitnessWeeks)
        assertTrue(planNeedsRebuild(plan, r.goal, r.preferences))
        assertEquals(mondayOf(after).plusWeeks(7), fitnessTestWeekStart(8, after))
    }

    // ------------------------------------------------------------ Testwoche und Zieldatum

    private fun weekdayOf(ms: Long) =
        java.time.Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate().dayOfWeek

    private fun benchDayOf(plan: TrainingPlan) = plan.weeks.last().sessions
        .single { it.textKey == de.trailscape.core.i18n.SessionTextKey.BENCHMARK_RIDE }.day

    @Test
    fun `Zieltag - lange Tour, sonst Samstag, sonst spaetester Tag`() {
        assertEquals(5, fitnessTestDay(null))
        assertEquals(6, fitnessTestDay(PlanPreferences(longRideDay = 6)))
        assertEquals(5, fitnessTestDay(PlanPreferences(trainingDays = listOf(0, 2, 5))))
        assertEquals(4, fitnessTestDay(PlanPreferences(trainingDays = listOf(0, 2, 4))))
        assertEquals(2, fitnessTestDay(PlanPreferences(trainingDays = listOf(1, 2))))
        // Lange Tour auf nicht erlaubtem Tag zaehlt nicht.
        assertEquals(4, fitnessTestDay(PlanPreferences(trainingDays = listOf(0, 4), longRideDay = 6)))
    }

    @Test
    fun `Lange Tour am Sonntag - Zieldatum und Vergleichsrunde am Sonntag`() {
        val input = GoalFormInput(name = "Form", fitness = true, weeksText = "8", benchmarkText = "40", longRideDay = 6)
        val parsed = ok(input)
        assertEquals(java.time.DayOfWeek.SUNDAY, weekdayOf(parsed.goal.date))
        val plan = planFor(input)
        assertEquals(8, plan.weeks.size)
        assertEquals(java.time.DayOfWeek.SUNDAY, weekdayOf(plan.goal.date))
        assertEquals("So", benchDayOf(plan))
        assertEquals(FitnessTestWeek.TEST_AND_BENCHMARK, fitnessTestWeek(plan))
        // Ohne Vorgabe: Samstag, Vergleichsrunde am Samstag.
        val sat = planFor(input.copy(longRideDay = -1))
        assertEquals(java.time.DayOfWeek.SATURDAY, weekdayOf(sat.goal.date))
        assertEquals("Sa", benchDayOf(sat))
    }

    @Test
    fun `Nur Di und Mi - Vergleichsrunde am Mittwoch, der Test entfaellt und die Karte sagt es`() {
        val input = GoalFormInput(name = "Form", fitness = true, weeksText = "8", benchmarkText = "40", daysMask = maskOfDays(listOf(1, 2)))
        val plan = planFor(input)
        assertEquals(java.time.DayOfWeek.WEDNESDAY, weekdayOf(plan.goal.date))
        assertEquals("Mi", benchDayOf(plan))
        assertEquals(FitnessTestWeek.BENCHMARK_ONLY, fitnessTestWeek(plan))
        assertTrue(plan.weeks.last().sessions.none { it.textKey == de.trailscape.core.i18n.SessionTextKey.FTP_TEST })
        assertTrue(
            XmlStrings.resolve(UiText.Res(R.string.plan_inputs_goal_fitness_body_no_test), AppLanguage.DE)
                .contains("20-Minuten-Test fehlt ein Trainingstag"),
        )
        // Drei Tage (Di, Mi, Fr): Zieltag Freitag, Test am Di/Mi davor.
        val three = planFor(input.copy(daysMask = maskOfDays(listOf(1, 2, 4))))
        assertEquals(java.time.DayOfWeek.FRIDAY, weekdayOf(three.goal.date))
        assertEquals(FitnessTestWeek.TEST_AND_BENCHMARK, fitnessTestWeek(three))
    }

    @Test
    fun `Testwoche aus den Einheiten - alle vier Faelle`() {
        fun week(vararg keys: de.trailscape.core.i18n.SessionTextKey): TrainingPlan {
            val base = planFor(GoalFormInput(name = "Form", fitness = true, weeksText = "8"))
            val sessions = keys.map { k ->
                base.weeks.last().sessions.first().copy(textKey = k)
            }
            return base.copy(weeks = base.weeks.dropLast(1) + base.weeks.last().copy(sessions = sessions))
        }
        val ftp = de.trailscape.core.i18n.SessionTextKey.FTP_TEST
        val bench = de.trailscape.core.i18n.SessionTextKey.BENCHMARK_RIDE
        assertEquals(FitnessTestWeek.TEST_AND_BENCHMARK, fitnessTestWeek(week(ftp, bench)))
        assertEquals(FitnessTestWeek.TEST_ONLY, fitnessTestWeek(week(ftp)))
        assertEquals(FitnessTestWeek.BENCHMARK_ONLY, fitnessTestWeek(week(bench)))
        assertEquals(FitnessTestWeek.NONE, fitnessTestWeek(week()))
        assertEquals(FitnessTestWeek.NONE, fitnessTestWeek(planFor(eventInput())))
    }

    @Test
    fun `Zielzeile und Texte je Testwoche`() {
        val today = LocalDate.of(2026, 9, 25)
        val goal = Goal("Form", 40.0, null, type = GoalType.FITNESS, date = LocalDate.of(2026, 12, 19).atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
        fun text(language: AppLanguage, week: FitnessTestWeek) =
            goalSummaryParts(goal, language, today, week).joinToString(" · ") { XmlStrings.resolve(it, language) }
        // Ohne Vergleichsrunde steht sie weder im Titel noch im Text, auch wenn eine Laenge eingetragen ist.
        assertEquals("Fitnessblock · Test am Sa, 19. Dezember · noch 12\u00A0Wochen", text(AppLanguage.DE, FitnessTestWeek.TEST_ONLY))
        assertEquals(
            "Fitnessblock · Vergleichsrunde 40 km · Testwoche bis Sa, 19. Dezember · noch 12\u00A0Wochen",
            text(AppLanguage.DE, FitnessTestWeek.TEST_AND_BENCHMARK),
        )
        assertEquals(
            "Fitness block · Benchmark ride 40 km · Test week until Sat 19 December · 12\u00A0weeks to go",
            text(AppLanguage.EN, FitnessTestWeek.TEST_AND_BENCHMARK),
        )
        assertEquals("Fitness block · Test on Sat 19 December · 12\u00A0weeks to go", text(AppLanguage.EN, FitnessTestWeek.TEST_ONLY))
        assertEquals(
            "Fitnessblock · Vergleichsrunde 40 km · Vergleichsrunde am Sa, 19. Dezember · noch 12\u00A0Wochen",
            text(AppLanguage.DE, FitnessTestWeek.BENCHMARK_ONLY),
        )
        assertEquals("Fitnessblock · Block endet am Sa, 19. Dezember · noch 12\u00A0Wochen", text(AppLanguage.DE, FitnessTestWeek.NONE))
        assertEquals("Fitness block · Block ends on Sat 19 December · 12\u00A0weeks to go", text(AppLanguage.EN, FitnessTestWeek.NONE))
    }

    @Test
    fun `Zielzeile ohne Vergleichsrunde nennt keine Distanz`() {
        val today = LocalDate.of(2026, 9, 25)
        val goal = Goal("Form", 0.0, null, LocalDate.of(2026, 12, 19).atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), type = GoalType.FITNESS)
        val de = goalSummaryParts(goal, AppLanguage.DE, today).joinToString(" · ") { XmlStrings.resolve(it, AppLanguage.DE) }
        assertEquals("Fitnessblock · Testwoche bis Sa, 19. Dezember · noch 12\u00A0Wochen", de)
        assertFalse(de.contains("km"))
    }

    @Test
    fun `Titel der Heute-Karte - Fitnessziel ohne Kilometer, Event mit Distanz`() {
        fun title(goal: Goal, language: AppLanguage, week: FitnessTestWeek) =
            goalTitleParts(goal, language, week) { formatGoalKm(it, language) }
                .joinToString(" · ") { XmlStrings.resolve(it, language) }
        val fitness = Goal("Form", 0.0, null, 0L, type = GoalType.FITNESS)
        assertEquals("Fitnessblock", title(fitness, AppLanguage.DE, FitnessTestWeek.TEST_AND_BENCHMARK))
        assertEquals("Fitness block", title(fitness, AppLanguage.EN, FitnessTestWeek.TEST_AND_BENCHMARK))
        assertEquals(
            "Fitnessblock · Vergleichsrunde 42,5 km",
            title(fitness.copy(distanceKm = 42.5), AppLanguage.DE, FitnessTestWeek.TEST_AND_BENCHMARK),
        )
        assertEquals("Fitnessblock", title(fitness.copy(distanceKm = 42.5), AppLanguage.DE, FitnessTestWeek.TEST_ONLY))
        val event = Goal("Rennen", 60.0, 700.0, 0L)
        assertEquals("60 km", title(event, AppLanguage.DE, FitnessTestWeek.TEST_ONLY))
        assertEquals("42.2 km", title(event.copy(distanceKm = 42.2), AppLanguage.EN, FitnessTestWeek.TEST_ONLY))
    }

    // ------------------------------------------------------------ Eine Zielzeit-Luecke, ein Satz

    @Test
    fun `Prognose-Hinweis entfaellt, wenn der Zielzeit-Hinweis schon darunter steht`() {
        val goal = Goal("Rennen", 60.0, 700.0, 0L, targetDurationMin = 130)
        val short = GoalFinishPrediction(GoalPrognosis(150, 140, 8, 4, 50.0, 28.0, false), null)
        assertNotNull(prognosisNote(goal, short).hint)
        assertNull(prognosisNote(goal, short, targetTimeNoteShown = true).hint)
        // Die anderen Hinweise bleiben: sie sagen etwas anderes.
        val beyond = GoalFinishPrediction(GoalPrognosis(150, 140, 8, 4, 50.0, 28.0, true), null)
        assertNotNull(prognosisNote(goal, beyond, targetTimeNoteShown = true).hint)
        val onTrack = GoalFinishPrediction(GoalPrognosis(150, 120, 8, 4, 50.0, 28.0, false), null)
        assertNotNull(prognosisNote(goal, onTrack, targetTimeNoteShown = true).hint)
    }
}
