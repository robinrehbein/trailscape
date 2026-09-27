package de.trailscape.app.ui.training

import de.trailscape.app.R
import de.trailscape.app.i18n.UiText
import de.trailscape.app.ui.training.XmlStrings.de
import de.trailscape.app.ui.training.XmlStrings.en
import de.trailscape.core.Goal
import de.trailscape.core.GoalFinishPrediction
import de.trailscape.core.GoalPrognosis
import de.trailscape.core.HrvAssessment
import de.trailscape.core.HrvStatus
import de.trailscape.core.RecoveryFlag
import de.trailscape.core.i18n.AppLanguage
import de.trailscape.core.i18n.CoreTextsDe
import de.trailscape.core.i18n.CoreTextsEn
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reine Textlogik des Trainings-Tabs (Zielzeile, Prognose-Satz, Quellzeile,
 * Koerperwert-Worte). Das Compose-Layout selbst bleibt ungetestet.
 *
 * Die Logik liefert [UiText]; [XmlStrings] loest sie gegen die echten
 * Ressourcendateien auf — die deutschen Erwartungen stehen woertlich wie vor
 * dem Umzug in die Ressourcen, die englischen daneben.
 */
class TrainingTextTest {

    private val today = LocalDate.of(2026, 9, 25)

    private fun goalOn(date: LocalDate, target: Int? = 130) = Goal(
        name = "Rennen Hügelland",
        distanceKm = 60.0,
        ascentM = 700.0,
        date = date.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
        targetDurationMin = target,
    )

    private fun prognosis(current: Int, atEvent: Int?, beyond: Boolean = false) = GoalFinishPrediction(
        GoalPrognosis(current, atEvent, 8, 4, 50.0, 28.0, beyond),
        null,
    )

    private fun summary(goal: Goal, language: AppLanguage): String =
        goalSummaryParts(goal, language, today).joinToString(" · ") { XmlStrings.resolve(it, language) }

    @Test
    fun `Zielzeile wie im Prototyp`() {
        assertEquals(
            "60 km · 700\u00A0Hm · Sa, 19. Dezember · noch 12\u00A0Wochen",
            summary(goalOn(LocalDate.of(2026, 12, 19)), AppLanguage.DE),
        )
        assertEquals(
            "60 km · 700\u00A0Hm · Do, 1. Oktober · noch 6\u00A0Tage",
            summary(goalOn(LocalDate.of(2026, 10, 1)), AppLanguage.DE),
        )
    }

    @Test
    fun `Zielzeile auf Englisch`() {
        assertEquals(
            "60 km · 700\u00A0m · Sat 19 December · 12\u00A0weeks to go",
            summary(goalOn(LocalDate.of(2026, 12, 19)), AppLanguage.EN),
        )
        assertEquals(
            "60 km · 700\u00A0m · Thu 1 October · 6\u00A0days to go",
            summary(goalOn(LocalDate.of(2026, 10, 1)), AppLanguage.EN),
        )
        assertEquals(
            "60 km · 700\u00A0m · Sat 26 September · tomorrow",
            summary(goalOn(LocalDate.of(2026, 9, 26)), AppLanguage.EN),
        )
        assertEquals(
            "60 km · 700\u00A0m · Thu 24 September · past",
            summary(goalOn(LocalDate.of(2026, 9, 24)), AppLanguage.EN),
        )
        // Nachkommastelle der Zieldistanz im Format der Sprache.
        val marathon = goalOn(LocalDate.of(2026, 12, 19)).copy(distanceKm = 42.2, ascentM = null)
        assertTrue(summary(marathon, AppLanguage.DE).startsWith("42,2 km · Sa, 19. Dezember"))
        assertTrue(summary(marathon, AppLanguage.EN).startsWith("42.2 km · Sat 19 December"))
    }

    @Test
    fun `Prognose-Satz mit Plan und Zielzeit`() {
        val note = prognosisNote(goalOn(today.plusWeeks(12)), prognosis(145, 128))
        assertEquals("Mit dem Plan kommst du bis zum Renntag auf ca. 2:08\u00A0h.", de(note.sentence))
        assertEquals("ca. 2:08\u00A0h", de(note.bold!!))
        assertEquals("Das reicht für deine Zielzeit. Bleib bei den langen Fahrten dran.", de(note.hint!!))
        // Die fette Zeit steht woertlich im Satz — sonst fande die Anzeige sie nicht.
        assertTrue(de(note.sentence).contains(de(note.bold!!)))
        assertTrue(en(note.sentence).contains(en(note.bold!!)))
        assertEquals("With the plan, you’ll reach about 2:08\u00A0h by race day.", en(note.sentence))

        val short = prognosisNote(goalOn(today.plusWeeks(12)), prognosis(150, 140))
        assertEquals(
            "Für deine Zielzeit fehlen noch etwa 10 Min. Am meisten bringen die langen Fahrten.",
            de(short.hint!!),
        )
        assertEquals(UiText.Res(R.string.training_goal_hint_short, listOf(10)), short.hint)
        assertEquals("You’re still about 10 min short of your target time. The long rides help most.", en(short.hint!!))
    }

    @Test
    fun `Prognose-Hinweise ohne Zielzeit und bei zu kurzen Touren`() {
        val noTarget = prognosisNote(goalOn(today.plusWeeks(12), target = null), prognosis(145, 128))
        assertEquals("Trag eine Zielzeit ein, dann siehst du, ob es reicht.", de(noTarget.hint!!))
        val beyond = prognosisNote(goalOn(today.plusWeeks(12)), prognosis(145, 128, beyond = true))
        assertEquals(UiText.Res(R.string.training_goal_hint_long_rides), beyond.hint)
        val pending = prognosisNote(goalOn(today.plusWeeks(12)), prognosis(145, null))
        assertNull(pending.bold)
        assertEquals("Once your fitness curve is in place, we’ll work out race day too.", en(pending.sentence))
    }

    @Test
    fun `ohne Prognose steht was fehlt`() {
        val note = prognosisNote(
            goalOn(today.plusWeeks(12)),
            GoalFinishPrediction(null, "Fahre 2–3 längere Touren (ab etwa 25 km), dann gibt es eine Prognose."),
        )
        assertEquals("Fahre 2–3 längere Touren (ab etwa 25 km), dann gibt es eine Prognose.", de(note.sentence))
        assertNull(note.bold)
        assertNull(note.hint)
        val nothing = prognosisNote(goalOn(today.plusWeeks(12)), GoalFinishPrediction(null, null))
        assertEquals("Noch keine Prognose.", de(nothing.sentence))
        assertEquals("No prediction yet.", en(nothing.sentence))
    }

    @Test
    fun `Quellzeile nennt Tag und Uhrzeit`() {
        assertEquals(
            "Von deiner Uhr über Health Connect · heute 06:12",
            de(vitalsSourceLine(LocalDateTime.of(2026, 9, 25, 6, 12), hasAny = true, AppLanguage.DE, today = today)),
        )
        assertEquals(
            "Von deiner Uhr über Health Connect",
            de(vitalsSourceLine(null, hasAny = true, AppLanguage.DE, today = today)),
        )
        assertEquals(
            "Von deiner Uhr über Health Connect · 3. September 21:05",
            de(vitalsSourceLine(LocalDateTime.of(2026, 9, 3, 21, 5), hasAny = true, AppLanguage.DE, today = today)),
        )
    }

    @Test
    fun `Quellzeile auf Englisch`() {
        assertEquals(
            "From your watch via Health Connect · today 06:12",
            en(vitalsSourceLine(LocalDateTime.of(2026, 9, 25, 6, 12), hasAny = true, AppLanguage.EN, today = today)),
        )
        assertEquals(
            "From your watch via Health Connect · yesterday 22:40",
            en(vitalsSourceLine(LocalDateTime.of(2026, 9, 24, 22, 40), hasAny = true, AppLanguage.EN, today = today)),
        )
        assertEquals(
            "From your watch via Health Connect · 3 September 21:05",
            en(vitalsSourceLine(LocalDateTime.of(2026, 9, 3, 21, 5), hasAny = true, AppLanguage.EN, today = today)),
        )
        assertEquals(
            "No values from your watch yet. You can connect Health Connect in Settings.",
            en(vitalsSourceLine(null, hasAny = false, AppLanguage.EN, today = today)),
        )
    }

    @Test
    fun `Koerperwerte als einfache Worte`() {
        assertEquals("etwas niedrig", XmlStrings.string(hrvWord(RecoveryFlag.GELB), AppLanguage.DE))
        assertEquals("normal", XmlStrings.string(restingHrWord(RecoveryFlag.GRUEN), AppLanguage.DE))
        assertEquals("gut", XmlStrings.string(sleepWord(RecoveryFlag.GRUEN), AppLanguage.DE))
        assertEquals("7:40", formatSleep(7 + 40 / 60.0))

        assertEquals("slightly low", XmlStrings.string(hrvWord(RecoveryFlag.GELB), AppLanguage.EN))
        assertEquals("very high", XmlStrings.string(restingHrWord(RecoveryFlag.ROT), AppLanguage.EN))
        assertEquals("a bit short", XmlStrings.string(sleepWord(RecoveryFlag.GELB), AppLanguage.EN))
    }

    @Test
    fun `HRV-Tendenz mit Vorzeichen und Baseline`() {
        // Baseline 52 ms, Rollmittel 12 % darunter.
        val hrv = HrvAssessment(
            available = true,
            unavailableReason = null,
            baselineLn = ln(52.0),
            sigmaLn = 0.1,
            currentLn = ln(52.0 * 0.88),
            lastRmssd = 45.0,
            z = null,
            zMean = null,
            status = HrvStatus.entries.first(),
            flag = RecoveryFlag.GELB,
            historyDays = 20,
            recentDays = 7,
            message = "",
        )
        assertEquals("Tendenz: −12 % gegenüber deinem Normalwert (52 ms).", de(hrvTrendText(hrv)!!))
        assertEquals("Trend: −12 % compared with your usual value (52 ms).", en(hrvTrendText(hrv)!!))
        assertNull(hrvTrendText(hrv.copy(baselineLn = null)))
    }

    @Test
    fun `hoechstens eine Warnflaeche - Tragfaehigkeit geht vor Profil`() {
        assertEquals(
            TrainingNoticeLayout(card = TrainingWarning.PLAN_FEASIBILITY, quiet = setOf(TrainingWarning.PROFILE)),
            trainingNoticeLayout(feasibilityOpen = true, profileMissing = true),
        )
        assertEquals(
            TrainingNoticeLayout(card = TrainingWarning.PROFILE, quiet = emptySet()),
            trainingNoticeLayout(feasibilityOpen = false, profileMissing = true),
        )
        assertEquals(
            TrainingNoticeLayout(card = TrainingWarning.PLAN_FEASIBILITY, quiet = emptySet()),
            trainingNoticeLayout(feasibilityOpen = true, profileMissing = false),
        )
        assertEquals(
            TrainingNoticeLayout(card = null, quiet = emptySet()),
            trainingNoticeLayout(feasibilityOpen = false, profileMissing = false),
        )
    }

    @Test
    fun `Legende nennt nur die Markierungen auf der Skala`() {
        fun labels(targetMin: Int?, atEventMin: Int?, language: AppLanguage) =
            prognosisMarkers(targetMin, atEventMin).map { XmlStrings.string(it.labelRes, language) }

        assertEquals(listOf("heute", "am Renntag", "Ziel"), labels(130, 128, AppLanguage.DE))
        assertEquals(listOf("heute", "Ziel"), labels(130, null, AppLanguage.DE))
        assertEquals(listOf("heute", "am Renntag"), labels(null, 128, AppLanguage.DE))
        assertEquals(listOf("heute"), labels(null, null, AppLanguage.DE))
        assertEquals(listOf("today", "on race day", "target"), labels(130, 128, AppLanguage.EN))
    }

    @Test
    fun `Hinweistexte`() {
        // Die Anpassungs-Notiz zeigt den Grund aus `:core` unveraendert — er
        // bringt seine Einleitung in beiden Sprachen selbst mit.
        assertTrue(CoreTextsDe.training.planAdapted(3, 0).startsWith("Plan angepasst: In Woche 3 "))
        assertTrue(CoreTextsEn.training.planAdapted(3, 0).startsWith("Plan adjusted: in week 3 "))
        assertTrue(de(unconfirmedProfileText).startsWith("Ohne Alter und Gewicht rechnen wir mit "))
        assertTrue(de(unconfirmedProfileText).endsWith("die Zahlen sind grob."))
        assertEquals("Profil öffnen", XmlStrings.string(PROFILE_ACTION_LABEL, AppLanguage.DE))
        assertTrue(en(unconfirmedProfileText).startsWith("Without your age and weight we assume "))
        assertEquals("Open profile", XmlStrings.string(PROFILE_ACTION_LABEL, AppLanguage.EN))
    }

    @Test
    fun `Entlastungs-Richtwert liest sich in beiden Sprachen`() {
        val text = UiText.Res(R.string.training_week_deload_range, listOf("Zeit fuer eine leichte Woche.", 12, 20, 30))
        assertEquals("Zeit fuer eine leichte Woche. Richtwert: 12–20 Last statt zuletzt 30.", de(text))
        assertEquals("Zeit fuer eine leichte Woche. Guideline: 12–20 load instead of the recent 30.", en(text))
    }

    @Test
    fun `Plurals zaehlen im Englischen und Deutschen richtig`() {
        assertEquals(
            "Plan mit 1 Woche erstellt.",
            de(UiText.Plural(R.plurals.training_goal_editor_plan_created_count, 1)),
        )
        assertEquals(
            "Plan created with 9 weeks.",
            en(UiText.Plural(R.plurals.training_goal_editor_plan_created_count, 9)),
        )
        assertEquals(
            "Letzte 60 Tage · grün: Fitness, orange: Ermüdung",
            de(UiText.Plural(R.plurals.training_form_legend_count, 60)),
        )
        assertEquals(
            "Your pace on rides of about 40 % of the goal distance or more. Longer and more recent rides " +
                "count more. Included: 1 ride.",
            en(UiText.Plural(R.plurals.training_prognosis_rides_body_used_count, 1)),
        )
    }
}
