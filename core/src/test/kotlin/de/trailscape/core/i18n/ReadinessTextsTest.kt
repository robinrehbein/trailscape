package de.trailscape.core.i18n

import de.trailscape.core.DailyRecommendationKind
import de.trailscape.core.DailyValue
import de.trailscape.core.FitnessSeries
import de.trailscape.core.RecoveryFlag
import de.trailscape.core.assessDeload
import de.trailscape.core.assessHrv
import de.trailscape.core.assessRestingHeartRate
import de.trailscape.core.assessSleep
import de.trailscape.core.computeReadiness
import de.trailscape.core.recommendToday
import de.trailscape.core.TsbBand
import de.trailscape.core.shortSleeperHint
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Die Erholungsauswertung spricht Deutsch und Englisch — ueber die echten Funktionen. */
class ReadinessTextsTest {

    private val today = LocalDateTime.of(2026, 6, 5, 0, 0)

    private fun daily(values: List<Double>): List<DailyValue> =
        values.mapIndexed { i, v -> DailyValue(today.minusDays((values.size - 1 - i).toLong()), v) }

    @Test
    fun `leere Serien nennen den Grund in der Sprache der Texte`() {
        assertEquals(
            "Noch keine Ruhepuls-Werte vorhanden.",
            assessRestingHeartRate(emptyList(), texts = CoreTextsDe).unavailableReason,
        )
        assertEquals(
            "No resting heart rate values yet.",
            assessRestingHeartRate(emptyList(), texts = CoreTextsEn).unavailableReason,
        )
        assertEquals("No HRV values yet.", assessHrv(emptyList(), texts = CoreTextsEn).message)
        assertEquals("No sleep data yet.", assessSleep(emptyList(), texts = CoreTextsEn).message)
    }

    @Test
    fun `Baseline im Aufbau mit Zahlen`() {
        val few = daily(List(11) { 50.0 })
        assertEquals(
            "Building your resting heart rate baseline (3 of 21 days).",
            assessRestingHeartRate(few, today = today, texts = CoreTextsEn).unavailableReason,
        )
        assertEquals(
            "Ruhepuls-Baseline wird aufgebaut (3 von 21 Tagen).",
            assessRestingHeartRate(few, today = today, texts = CoreTextsDe).unavailableReason,
        )
    }

    @Test
    fun `gruener Ruhepuls mit Vorzeichen und Dezimaltrennzeichen je Sprache`() {
        val series = daily(List(55) { 50.0 } + listOf(50.0, 50.0, 50.0, 51.0, 51.0))
        val de = assessRestingHeartRate(series, today = today, texts = CoreTextsDe)
        val en = assessRestingHeartRate(series, today = today, texts = CoreTextsEn)
        assertEquals(RecoveryFlag.GRUEN, en.flag)
        assertTrue(de.message.contains("+1,0 bpm"), de.message)
        assertTrue(en.message.contains("+1.0 bpm"), en.message)
        assertTrue(en.message.startsWith("Your resting heart rate is in its usual range"), en.message)
    }

    @Test
    fun `Schlafabweichung mit Dezimalkomma bzw Punkt`() {
        val nights = daily(List(20) { 8.0 } + listOf(6.5))
        val de = assessSleep(nights, today = today, texts = CoreTextsDe)
        val en = assessSleep(nights, today = today, texts = CoreTextsEn)
        assertEquals(de.flag, en.flag)
        assertTrue(de.message.contains("1,5 h"), de.message)
        assertTrue(en.message.contains("1.5 h"), en.message)
    }

    @Test
    fun `Gesamtwert, Tagesempfehlung und Entlastung auf Englisch`() {
        val restingHr = assessRestingHeartRate(emptyList(), texts = CoreTextsEn)
        val sleep = assessSleep(emptyList(), texts = CoreTextsEn)
        val readiness = computeReadiness(restingHr, sleep, texts = CoreTextsEn)
        assertEquals("Readiness can’t be calculated yet", readiness.headline)
        assertEquals(
            "Not enough data for an overall score yet (resting heart rate, sleep, training history). " +
                "You can still see the individual signals.",
            readiness.unavailableReason,
        )
        val de = computeReadiness(
            assessRestingHeartRate(emptyList(), texts = CoreTextsDe),
            assessSleep(emptyList(), texts = CoreTextsDe),
            texts = CoreTextsDe,
        )
        assertTrue(de.unavailableReason!!.contains("(Ruhepuls, Schlaf, Trainingshistorie)"))

        val recommendation = recommendToday(readiness, tsb = -30.0, texts = CoreTextsEn)
        assertEquals(DailyRecommendationKind.RECOVERY, recommendation.kind)
        assertEquals("Recovery ride, very easy (zone 1–2)", recommendation.title)
        assertEquals(CoreTextsEn.load.tsbBandMessage(TsbBand.PRODUKTIV), recommendation.reasons.last())

        val deload = assessDeload(FitnessSeries.EMPTY, readinessLast7 = listOf(30.0, 30.0, 30.0), texts = CoreTextsEn)
        assertTrue(deload.recommended)
        assertEquals("Recovery week recommended", deload.title)
        assertEquals(listOf("Your readiness was in the low range on 3 of seven days."), deload.triggers)
        val deloadDe = assessDeload(FitnessSeries.EMPTY, texts = CoreTextsDe)
        assertEquals("Kein Deload nötig", deloadDe.title)
    }

    @Test
    fun `HRV-Luecke mit Einzahl und Mehrzahl`() {
        assertEquals(
            "Needs 1 more day of HRV data (13 of 14 in the comparison period).",
            CoreTextsEn.readiness.hrvNeedsDays(1, 13, 14),
        )
        assertEquals(
            "Braucht noch 2 Tage HRV-Daten (12 von 14 im Vergleichszeitraum).",
            CoreTextsDe.readiness.hrvNeedsDays(2, 12, 14),
        )
    }

    @Test
    fun `Kurzschlaefer-Hinweis in beiden Sprachen`() {
        assertTrue(shortSleeperHint(CoreTextsDe).contains("6,5 Stunden"))
        assertTrue(shortSleeperHint(CoreTextsEn).contains("6.5 hours"))
    }
}
