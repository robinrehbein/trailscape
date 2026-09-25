package de.trailscape.app.ui.training

import de.trailscape.core.Goal
import de.trailscape.core.GoalFinishPrediction
import de.trailscape.core.GoalPrognosis
import de.trailscape.core.RecoveryFlag
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reine Textlogik des Trainings-Tabs (Zielzeile, Prognose-Satz, Quellzeile,
 * Koerperwert-Worte). Das Compose-Layout selbst bleibt ungetestet.
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

    @Test
    fun `Zielzeile wie im Prototyp`() {
        assertEquals(
            "60 km · 700 Hm · Sa, 19. Dezember · noch 12 Wochen",
            goalSummaryLine(goalOn(LocalDate.of(2026, 12, 19)), today),
        )
        assertEquals(
            "60 km · 700 Hm · Do, 1. Oktober · noch 6 Tage",
            goalSummaryLine(goalOn(LocalDate.of(2026, 10, 1)), today),
        )
    }

    @Test
    fun `Prognose-Satz mit Plan und Zielzeit`() {
        val note = prognosisNote(goalOn(today.plusWeeks(12)), prognosis(145, 128))
        assertEquals("Mit dem Plan kommst du bis zum Renntag auf ", note.lead)
        assertEquals("ca. 2:08 h", note.bold)
        assertEquals("Das reicht für deine Zielzeit. Bleib bei den langen Fahrten dran.", note.hint)

        val short = prognosisNote(goalOn(today.plusWeeks(12)), prognosis(150, 140))
        assertEquals(
            "Für deine Zielzeit fehlen noch etwa 10 Min. Am meisten bringen die langen Fahrten.",
            short.hint,
        )
    }

    @Test
    fun `ohne Prognose steht was fehlt`() {
        val note = prognosisNote(
            goalOn(today.plusWeeks(12)),
            GoalFinishPrediction(null, "Fahre 2–3 längere Touren (ab etwa 25 km), dann gibt es eine Prognose."),
        )
        assertEquals("Fahre 2–3 längere Touren (ab etwa 25 km), dann gibt es eine Prognose.", note.lead)
        assertNull(note.bold)
        assertNull(note.hint)
    }

    @Test
    fun `Quellzeile nennt Tag und Uhrzeit`() {
        assertEquals(
            "Von deiner Uhr über Health Connect · heute 06:12",
            vitalsSourceLine(LocalDateTime.of(2026, 9, 25, 6, 12), hasAny = true, today = today),
        )
        assertEquals("Von deiner Uhr über Health Connect", vitalsSourceLine(null, hasAny = true, today = today))
    }

    @Test
    fun `Koerperwerte als einfache Worte`() {
        assertEquals("etwas niedrig", hrvWord(RecoveryFlag.GELB))
        assertEquals("normal", restingHrWord(RecoveryFlag.GRUEN))
        assertEquals("gut", sleepWord(RecoveryFlag.GRUEN))
        assertEquals("7:40", formatSleep(7 + 40 / 60.0))
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
        assertEquals(
            listOf("heute", "am Renntag", "Ziel"),
            prognosisMarkers(targetMin = 130, atEventMin = 128).map { it.label },
        )
        assertEquals(listOf("heute", "Ziel"), prognosisMarkers(targetMin = 130, atEventMin = null).map { it.label })
        assertEquals(listOf("heute", "am Renntag"), prognosisMarkers(targetMin = null, atEventMin = 128).map { it.label })
        assertEquals(listOf("heute"), prognosisMarkers(targetMin = null, atEventMin = null).map { it.label })
    }

    @Test
    fun `Hinweistexte`() {
        assertEquals(
            "Plan an deine letzten Wochen angepasst: weniger gefahren als geplant.",
            planAdaptionText("weniger gefahren als geplant."),
        )
        // Der Grund aus `:core` bringt seine Einleitung selbst mit — nicht doppeln.
        assertEquals(
            "Plan angepasst: In Woche 3 hast du nur 0 % des Wochen-Solls erreicht.",
            planAdaptionText("Plan angepasst: In Woche 3 hast du nur 0 % des Wochen-Solls erreicht."),
        )
        assertTrue(unconfirmedProfileText.startsWith("Ohne Alter und Gewicht rechnen wir mit "))
        assertTrue(unconfirmedProfileText.endsWith("die Zahlen sind grob."))
        assertEquals("Profil öffnen", PROFILE_ACTION_LABEL)
    }
}
