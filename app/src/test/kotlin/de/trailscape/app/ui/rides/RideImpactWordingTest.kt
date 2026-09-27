package de.trailscape.app.ui.rides

import de.trailscape.core.RideFormImpact
import de.trailscape.core.RideImpact
import de.trailscape.core.RideSegmentBest
import de.trailscape.core.RideWeekGoalImpact
import de.trailscape.core.i18n.AppLanguage
import de.trailscape.core.i18n.CoreTexts
import de.trailscape.core.i18n.CoreTextsDe
import de.trailscape.core.i18n.CoreTextsEn
import kotlin.test.Test
import kotlin.test.assertEquals

/** Tests fuer `ui/rides/RideImpactWording.kt`: die Saetze von „Was die Tour gebracht hat". */
class RideImpactWordingTest {

    private val empty = RideImpact(form = null, weekGoal = null, newTiles = null, newBests = emptyList())

    private fun best(name: String, timeS: Int, improvementS: Int) =
        RideSegmentBest(segmentId = name, name = name, timeS = timeS, improvementS = improvementS)

    /** Eine Zeile als fertiger Text in der Sprache von `texts`. */
    private data class Line(val lead: String, val body: String)

    /**
     * Ueberdeckt das echte [de.trailscape.app.ui.rides.rideImpactLines]
     * (Member vor Top-Level) und loest die [RideImpactLine] gegen die
     * Ressourcen auf — so pruefen die Tests weiter den Wortlaut.
     */
    private fun rideImpactLines(impact: RideImpact, texts: CoreTexts): List<Line> {
        val strings = if (texts.format.language == AppLanguage.DE) RidesXmlStrings.DE else RidesXmlStrings.EN
        return de.trailscape.app.ui.rides.rideImpactLines(impact, texts)
            .map { Line(strings.resolve(it.lead), strings.resolve(it.body)) }
    }

    // ----------------------------------------------------------------- Form

    @Test
    fun `spuerbarer Reiz nennt Fitness, Muedigkeit und Frische`() {
        val lines = rideImpactLines(
            empty.copy(
                form = RideFormImpact(
                    load = 80.0,
                    fitnessGain = 1.87,
                    fatigueGain = 10.63,
                    freshnessAfter = -12.2,
                    noticeable = true,
                ),
            ),
            texts = CoreTextsDe,
        )
        assertEquals(
            listOf(Line("Fitness +1,9,", "Müdigkeit +10,6, Frische danach −12 (etwas müde).")),
            lines,
        )
    }

    @Test
    fun `kleiner Reiz lobt nicht`() {
        val lines = rideImpactLines(
            empty.copy(
                form = RideFormImpact(
                    load = 12.0,
                    fitnessGain = 0.28,
                    fatigueGain = 1.6,
                    freshnessAfter = 3.0,
                    noticeable = false,
                ),
            ),
            texts = CoreTextsDe,
        )
        assertEquals(
            listOf(Line("Kleiner Reiz.", "Für die Fitness kaum messbar (+0,3), dafür auch kaum Müdigkeit.")),
            lines,
        )
    }

    @Test
    fun `Vorzeichen mit echtem Minus und Komma`() {
        assertEquals("+1,9", formatSignedDecimalDe(1.87))
        assertEquals("−0,4", formatSignedDecimalDe(-0.36))
        assertEquals("±0,0", formatSignedDecimalDe(0.02))
    }

    // ------------------------------------------------------------ Wochenziel

    @Test
    fun `Wochenziel noch offen`() {
        val lines = rideImpactLines(
            empty.copy(weekGoal = RideWeekGoalImpact(kmAfter = 142.3, targetKm = 180, rideKm = 42.4, reachedByThisRide = false)),
            texts = CoreTextsDe,
        )
        assertEquals(listOf(Line("Wochenziel:", "142 von 180 km, diese Tour +42 km.")), lines)
    }

    @Test
    fun `Wochenziel mit dieser Tour geschafft`() {
        val lines = rideImpactLines(
            empty.copy(weekGoal = RideWeekGoalImpact(kmAfter = 184.2, targetKm = 180, rideKm = 34.0, reachedByThisRide = true)),
            texts = CoreTextsDe,
        )
        assertEquals(listOf(Line("Wochenziel geschafft.", "Mit dieser Tour 184 von 180 km.")), lines)
    }

    @Test
    fun `Wochenziel schon vorher erreicht`() {
        val lines = rideImpactLines(
            empty.copy(weekGoal = RideWeekGoalImpact(kmAfter = 212.0, targetKm = 180, rideKm = 32.0, reachedByThisRide = false)),
            texts = CoreTextsDe,
        )
        assertEquals(listOf(Line("Wochenziel erreicht.", "212 von 180 km, diese Tour +32 km.")), lines)
    }

    @Test
    fun `kurze Tour unter 10 km mit Komma`() {
        val lines = rideImpactLines(
            empty.copy(weekGoal = RideWeekGoalImpact(kmAfter = 64.2, targetKm = 180, rideKm = 4.2, reachedByThisRide = false)),
            texts = CoreTextsDe,
        )
        assertEquals(listOf(Line("Wochenziel:", "64 von 180 km, diese Tour +4,2 km.")), lines)
    }

    // ------------------------------------------------------------- Kacheln

    @Test
    fun `Kacheln in Einzahl und Mehrzahl`() {
        assertEquals(
            listOf(Line("1 neue Kachel", "zum ersten Mal befahren.")),
            rideImpactLines(empty.copy(newTiles = 1), texts = CoreTextsDe),
        )
        assertEquals(
            listOf(Line("12 neue Kacheln", "zum ersten Mal befahren.")),
            rideImpactLines(empty.copy(newTiles = 12), texts = CoreTextsDe),
        )
    }

    // ------------------------------------------------------------ Bestzeiten

    @Test
    fun `eine und zwei Bestzeiten stehen einzeln`() {
        val one = rideImpactLines(empty.copy(newBests = listOf(best("Anstieg 1,2 km · 80 Hm", 252, 14))), texts = CoreTextsDe)
        assertEquals(
            listOf(Line("Neue Bestzeit", "auf „Anstieg 1,2 km · 80 Hm“: 4:12, 14 s schneller.")),
            one,
        )

        val two = rideImpactLines(
            empty.copy(newBests = listOf(best("Kalkofen", 600, 75), best("Burgweg", 252, 14))),
            texts = CoreTextsDe,
        )
        assertEquals(
            listOf(
                Line("Neue Bestzeit", "auf „Kalkofen“: 10:00, 1:15 min schneller."),
                Line("Neue Bestzeit", "auf „Burgweg“: 4:12, 14 s schneller."),
            ),
            two,
        )
    }

    @Test
    fun `ab drei Bestzeiten eine Sammelzeile`() {
        val lines = rideImpactLines(
            empty.copy(newBests = listOf(best("a", 100, 30), best("b", 100, 20), best("c", 100, 10))),
            texts = CoreTextsDe,
        )
        assertEquals(
            listOf(Line("Neue Bestzeiten", "auf 3 Segmenten — Details unter „Alle Werte“.")),
            lines,
        )
    }

    @Test
    fun `Verbesserung unter und ab einer Minute`() {
        assertEquals("59 s", formatImprovementDe(59))
        assertEquals("1:00 min", formatImprovementDe(60))
        assertEquals("1:15 min", formatImprovementDe(75))
    }

    // --------------------------------------------------------------- Ganzes

    @Test
    fun `leere Wirkung ergibt keine Zeilen`() {
        assertEquals(emptyList(), rideImpactLines(empty, texts = CoreTextsDe))
    }

    @Test
    fun `Reihenfolge Form, Woche, Kacheln, Bestzeiten`() {
        val lines = rideImpactLines(
            RideImpact(
                form = RideFormImpact(load = 80.0, fitnessGain = 1.9, fatigueGain = 10.6, freshnessAfter = 0.0, noticeable = true),
                weekGoal = RideWeekGoalImpact(kmAfter = 100.0, targetKm = 180, rideKm = 40.0, reachedByThisRide = false),
                newTiles = 4,
                newBests = listOf(best("a", 100, 10)),
            ),
            texts = CoreTextsDe,
        )
        assertEquals(listOf("Fitness +1,9,", "Wochenziel:", "4 neue Kacheln", "Neue Bestzeit"), lines.map { it.lead })
        assertEquals("Müdigkeit +10,6, Frische danach ±0 (ausgeglichen).", lines.first().body)
    }

    // -------------------------------------------------------------- Englisch

    @Test
    fun `alle Zeilen auf Englisch mit Punkt statt Komma`() {
        val lines = rideImpactLines(
            RideImpact(
                form = RideFormImpact(load = 80.0, fitnessGain = 1.87, fatigueGain = 10.63, freshnessAfter = -12.2, noticeable = true),
                weekGoal = RideWeekGoalImpact(kmAfter = 64.2, targetKm = 180, rideKm = 4.2, reachedByThisRide = false),
                newTiles = 1,
                newBests = listOf(best("Kalkofen", 600, 75)),
            ),
            texts = CoreTextsEn,
        )
        assertEquals(
            listOf(
                Line("Fitness +1.9,", "fatigue +10.6, form afterwards −12 (${de.trailscape.core.freshnessWord(-12.2, CoreTextsEn)})."),
                Line("Weekly goal:", "64 of 180 km, this ride +4.2 km."),
                Line("1 new tile", "ridden for the first time."),
                Line("New personal best", "on “Kalkofen”: 10:00, 1:15 min faster."),
            ),
            lines,
        )
    }

    @Test
    fun `Wochenziel, kleiner Reiz und Sammelzeile auf Englisch`() {
        assertEquals(
            listOf(Line("Weekly goal reached.", "With this ride, 184 of 180 km.")),
            rideImpactLines(
                empty.copy(weekGoal = RideWeekGoalImpact(kmAfter = 184.2, targetKm = 180, rideKm = 34.0, reachedByThisRide = true)),
                texts = CoreTextsEn,
            ),
        )
        assertEquals(
            listOf(Line("Weekly goal already met.", "212 of 180 km, this ride +32 km.")),
            rideImpactLines(
                empty.copy(weekGoal = RideWeekGoalImpact(kmAfter = 212.0, targetKm = 180, rideKm = 32.0, reachedByThisRide = false)),
                texts = CoreTextsEn,
            ),
        )
        assertEquals(
            listOf(Line("Small stimulus.", "Barely measurable for fitness (+0.3), but hardly any fatigue either.")),
            rideImpactLines(
                empty.copy(form = RideFormImpact(load = 12.0, fitnessGain = 0.28, fatigueGain = 1.6, freshnessAfter = 3.0, noticeable = false)),
                texts = CoreTextsEn,
            ),
        )
        assertEquals(
            listOf(Line("12 new tiles", "ridden for the first time.")),
            rideImpactLines(empty.copy(newTiles = 12), texts = CoreTextsEn),
        )
        assertEquals(
            listOf(Line("New personal bests", "on 3 segments — details under “All values”.")),
            rideImpactLines(
                empty.copy(newBests = listOf(best("a", 100, 30), best("b", 100, 20), best("c", 100, 10))),
                texts = CoreTextsEn,
            ),
        )
    }

    @Test
    fun `Vorzeichen auf Englisch mit Punkt`() {
        assertEquals("+1.9", formatSignedDecimal(1.87, AppLanguage.EN))
        assertEquals("−0.4", formatSignedDecimal(-0.36, AppLanguage.EN))
        assertEquals("±0.0", formatSignedDecimal(0.02, AppLanguage.EN))
    }
}
