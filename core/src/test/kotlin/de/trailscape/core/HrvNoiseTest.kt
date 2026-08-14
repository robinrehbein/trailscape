package de.trailscape.core

import java.time.LocalDateTime
import java.util.Random
import kotlin.math.exp
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * HRV-Bewertung auf **realistisch rauschenden** Serien.
 *
 * ## Warum es diese Datei gibt
 * Alle bisherigen HRV-Tests laufen auf Konstantserien
 * (`RecoverySignalsTest`). Dort besteht die gesamte Streuung aus dem
 * kuenstlichen Sprung selbst — die Standardabweichung faellt auf ihren Boden
 * ([hrvMinSigmaLn]), und jeder noch so kleine Rueckgang wirkt riesig. Genau
 * deshalb ist jahrelang niemandem aufgefallen, dass zwei Fehler die Bewertung
 * lahmgelegt hatten:
 *
 *  1. Das 7-Tage-Rollfenster steckte in seiner eigenen 28-Tage-Baseline — ein
 *     anhaltender Einbruch zog seine Referenz mit **und** blaehte die Streuung
 *     auf.
 *  2. Der Zaehler war ein Mittel aus 7 Werten, der Nenner die Tagesstreuung.
 *
 * Zusammen ergab ein Einbruch von −40 % ueber eine ganze Woche gerade einmal
 * z ≈ −1,4, also **gelb**. [RecoveryFlag.ROT] war aus HRV faktisch
 * unerreichbar — und damit einer der beiden Ruhetag-Ausloeser tot.
 *
 * Die Serien hier haben deshalb die Streuung, die eine Galaxy Watch
 * tatsaechlich liefert: `ln(rMSSD)` mit σ ≈ 0,15 (Plews et al. berichten
 * Tag-zu-Tag-Variationskoeffizienten von 10–20 % fuer naechtliches rMSSD).
 * Feste Seeds halten das Ganze reproduzierbar.
 */
class HrvNoiseTest {

    private val today = dt(2026, 8, 8)

    /** Streuung von `ln(rMSSD)`, wie sie naechtliche Messungen zeigen. */
    private val sigmaLn = 0.15

    /**
     * Log-normal rauschende Tagesserie: [days] Tage endend auf [today], die
     * letzten [dipDays] Tage um den Faktor [dipFactor] abgesenkt.
     */
    private fun noisySeries(
        days: Int,
        baseMs: Double = 50.0,
        dipDays: Int = 0,
        dipFactor: Double = 1.0,
        seed: Long = 42,
        sigma: Double = sigmaLn,
    ): List<DailyValue> {
        val random = Random(seed)
        return (0 until days).map { i ->
            val offset = days - 1 - i
            val level = if (offset < dipDays) baseMs * dipFactor else baseMs
            DailyValue(
                day = addDaysForTest(today, -offset),
                value = exp(ln(level) + random.nextGaussian() * sigma),
            )
        }
    }

    private fun addDaysForTest(from: LocalDateTime, days: Int): LocalDateTime =
        from.toLocalDate().plusDays(days.toLong()).atStartOfDay()

    // -----------------------------------------------------------------------
    // Die Streuung wird ueberhaupt gemessen
    // -----------------------------------------------------------------------

    @Test
    fun `auf rauschender Serie misst die Baseline die echte Streuung`() {
        val h = assessHrv(noisySeries(days = 70), today = today)
        assertTrue(h.available)
        // Der Sigma-Boden darf hier nicht greifen — sonst waere der Test
        // wieder nur eine Konstantserie mit anderem Namen.
        assertTrue(h.sigmaLn!! > hrvMinSigmaLn * 2, "sigmaLn = ${h.sigmaLn}")
        assertEquals(sigmaLn, h.sigmaLn!!, 0.05)
        assertEquals(hrvBaselineDays - hrvRollingDays, h.historyDays)
    }

    @Test
    fun `stabile rauschende Serie bleibt gruen`() {
        // Zehn verschiedene Zufallslaeufe: reines Rauschen darf keine Ampel
        // ausloesen, sonst waere das System im Alltag unbrauchbar.
        var yellowOrWorse = 0
        for (seed in 1L..10L) {
            val h = assessHrv(noisySeries(days = 70, seed = seed), today = today)
            assertTrue(h.available)
            assertFalse(
                atLeast(h.flag, RecoveryFlag.ORANGE),
                "Seed $seed: reines Rauschen ergab ${h.flag} (z = ${h.z})",
            )
            if (atLeast(h.flag, RecoveryFlag.GELB)) {
                yellowOrWorse++
            }
        }
        // Ein paar Gelb-Treffer sind bei einem 0,75-σ-Band statistisch normal,
        // die Haelfte waere es nicht.
        assertTrue(yellowOrWorse <= 3, "$yellowOrWorse von 10 Laeufen auffaellig")
    }

    // -----------------------------------------------------------------------
    // Der Fall aus dem Review: −40 % ueber eine Woche
    // -----------------------------------------------------------------------

    @Test
    fun `minus 40 Prozent ueber eine Woche ist rot, nicht gelb`() {
        for (seed in 1L..10L) {
            val h = assessHrv(
                noisySeries(days = 70, dipDays = 7, dipFactor = 0.6, seed = seed),
                today = today,
            )
            assertTrue(h.available)
            assertEquals(HrvStatus.NIEDRIG, h.status)
            assertEquals(
                RecoveryFlag.ROT,
                h.flag,
                "Seed $seed: z = ${h.z}, zMean = ${h.zMean}",
            )
        }
    }

    @Test
    fun `roter HRV-Einbruch fuehrt bis zur Ruhetag-Empfehlung durch`() {
        val hrv = assessHrv(
            noisySeries(days = 70, dipDays = 7, dipFactor = 0.6),
            today = today,
        )
        val readiness = computeReadiness(
            restingHr = assessRestingHeartRate(daily(filled(60, 50.0), end = today), today = today),
            sleep = assessSleep(daily(filled(28, 7.5), end = today), today = today),
            hrv = hrv,
            tsb = 0.0,
            trainingHistoryDays = 60,
        )
        assertTrue(readiness.available)
        assertTrue(readiness.usesHrv)
        assertEquals(
            DailyRecommendationKind.RUHETAG,
            recommendToday(readiness = readiness, tsb = 0.0).kind,
        )
    }

    // -----------------------------------------------------------------------
    // Abstufung
    // -----------------------------------------------------------------------

    @Test
    fun `die Ampel stuft mit der Tiefe des Einbruchs ab`() {
        fun flagFor(factor: Double): RecoveryFlag =
            assessHrv(
                noisySeries(days = 70, dipDays = 7, dipFactor = factor, seed = 7),
                today = today,
            ).flag

        // −5 %: im Rauschen, keine Aussage.
        assertEquals(RecoveryFlag.GRUEN, flagFor(0.95))
        // −12 %: unter dem Normalband, aber noch nicht abgesichert genug.
        assertEquals(RecoveryFlag.GELB, flagFor(0.88))
        // −20 %: deutlich auffaellig.
        assertEquals(RecoveryFlag.ORANGE, flagFor(0.80))
        // −30 %: stark auffaellig.
        assertEquals(RecoveryFlag.ROT, flagFor(0.70))
    }

    // -----------------------------------------------------------------------
    // Die SEM-Achse haelt duenne Rollfenster zurueck
    // -----------------------------------------------------------------------

    @Test
    fun `wenige getragene Naechte eskalieren zurueckhaltender`() {
        val full = noisySeries(days = 70, dipDays = 7, dipFactor = 0.78, seed = 3)
        val dense = assessHrv(full, today = today)
        assertEquals(RecoveryFlag.ORANGE, dense.flag)
        assertEquals(7, dense.recentDays)

        // Dieselbe Absenkung, aber die Uhr lag an vier von sieben Naechten auf
        // dem Nachttisch: dieselbe Effektstaerke, weniger Sicherheit.
        val sparse = full.filter { dayDifference(today, it.day) !in listOf(0, 2, 4, 6) }
        val thin = assessHrv(sparse, today = today)
        assertEquals(3, thin.recentDays)
        assertTrue(
            thin.zMean!! > dense.zMean!!,
            "Weniger Naechte muessen einen kleineren |zMean| ergeben",
        )
        assertFalse(
            atLeast(thin.flag, RecoveryFlag.ORANGE),
            "Drei Naechte duerfen bei dieser Tiefe noch nicht eskalieren (${thin.flag})",
        )
    }

    // -----------------------------------------------------------------------
    // Baseline und Rollfenster sind entkoppelt
    // -----------------------------------------------------------------------

    @Test
    fun `ein anhaltender Einbruch zieht seine Baseline nicht mehr mit`() {
        val stable = assessHrv(noisySeries(days = 70, seed = 5), today = today)
        val dipped = assessHrv(
            noisySeries(days = 70, dipDays = 7, dipFactor = 0.6, seed = 5),
            today = today,
        )
        // Exakt dieselben Baselinetage (nur das Rollfenster wurde abgesenkt) —
        // Baseline und Streuung duerfen sich deshalb kein Stueck bewegen.
        assertEquals(stable.baselineLn!!, dipped.baselineLn!!, 1e-9)
        assertEquals(stable.sigmaLn!!, dipped.sigmaLn!!, 1e-9)
    }

    @Test
    fun `im alten 28-Tage-Fenster waere derselbe Einbruch verwaessert`() {
        // Gegenprobe zur Fehlerursache: Nimmt man die Baseline aus denselben
        // 28 Tagen inklusive Rollfenster, sinkt sie mit und die Streuung
        // steigt — der z-Wert schrumpft auf einen Bruchteil.
        val series = noisySeries(days = 70, dipDays = 7, dipFactor = 0.6)
        val correct = assessHrv(series, today = today)

        val overlapping = series
            .filter { dayDifference(today, it.day) in 0..27 }
            .map { ln(it.value) }
        val overlapMean = overlapping.average()
        val overlapSigma = kotlin.math.sqrt(
            overlapping.sumOf { (it - overlapMean) * (it - overlapMean) } /
                (overlapping.size - 1),
        )
        val recentMean = series
            .filter { dayDifference(today, it.day) < hrvRollingDays }
            .map { ln(it.value) }
            .average()
        val oldZ = (recentMean - overlapMean) / overlapSigma

        assertTrue(oldZ > -2.5, "Alter z-Wert war $oldZ")
        assertTrue(
            correct.z!! < oldZ - 1.0,
            "Neu ${correct.z} muss deutlich unter alt $oldZ liegen",
        )
    }
}
