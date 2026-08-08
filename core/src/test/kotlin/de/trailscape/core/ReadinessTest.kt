package de.trailscape.core

import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Portierung der Gruppen `Readiness`, `Empfehlungen`,
 * `Readiness-Reihe (rückwirkend)` und `Deload` aus
 * `test/training_load_test.dart`.
 */
class ReadinessTest {

    private val goodRhr = RestingHrAssessment(
        available = true,
        unavailableReason = null,
        baseline = 50.0,
        sigma = 1.5,
        current = 50.0,
        deltaBpm = 0.0,
        z = 0.0,
        flag = RecoveryFlag.GRUEN,
        baselineDays = 40,
        streakDays = 0,
        message = "ok",
    )

    private val goodSleep = SleepAssessment(
        available = true,
        unavailableReason = null,
        baselineH = 7.0,
        sigmaH = 0.5,
        lastNightH = 7.0,
        deviationH = 0.0,
        z = 0.0,
        debt7dH = 0.0,
        flag = RecoveryFlag.GRUEN,
        validNights = 28,
        shortSleeper = false,
        message = "ok",
    )

    private fun hrvWith(z: Double, status: HrvStatus, flag: RecoveryFlag): HrvAssessment =
        HrvAssessment(
            available = true,
            unavailableReason = null,
            baselineLn = ln(50.0),
            sigmaLn = 0.12,
            currentLn = ln(50.0) + z * 0.12,
            lastRmssd = 50.0,
            z = z,
            status = status,
            flag = flag,
            historyDays = 28,
            recentDays = 7,
            message = "hrv",
        )

    // --- group('Readiness') ---

    @Test
    fun `alles unauffaellig ergibt 100 Punkte`() {
        val r = computeReadiness(
            restingHr = goodRhr,
            sleep = goodSleep,
            tsb = 0.0,
            trainingHistoryDays = 40,
        )
        assertTrue(r.available)
        assertEquals(100.0, r.score, 1e-9)
        assertEquals(ReadinessBand.HART, r.band)
        assertTrue(r.headline.contains("100"))
        assertTrue(r.detail.contains("ohne HRV"))
    }

    @Test
    fun `Strafterme folgen exakt den Formeln aus Paragraf 5,4`() {
        val rhr = RestingHrAssessment(
            available = true,
            unavailableReason = null,
            baseline = 50.0,
            sigma = 1.5,
            current = 53.0,
            deltaBpm = 3.0,
            z = 2.0,
            flag = RecoveryFlag.GELB,
            baselineDays = 40,
            streakDays = 2,
            message = "x",
        )
        val sleep = SleepAssessment(
            available = true,
            unavailableReason = null,
            baselineH = 7.0,
            sigmaH = 0.5,
            lastNightH = 6.0,
            deviationH = -1.0,
            z = -2.0,
            debt7dH = -6.0,
            flag = RecoveryFlag.ORANGE,
            validNights = 28,
            shortSleeper = false,
            message = "x",
        )
        val r = computeReadiness(
            restingHr = rhr,
            sleep = sleep,
            tsb = -35.0,
            trainingHistoryDays = 40,
        )
        assertEquals(27.0, r.penaltyRhr, 1e-9) // (2,0 − 0,5) × 18
        assertEquals(33.0, r.penaltySleep, 1e-9) // 18 + 15 (gedeckelt)
        assertEquals(18.0, r.penaltyLoad, 1e-9) // (35 − 20) × 1,2
        assertEquals(22.0, r.score, 1e-9)
        assertEquals(ReadinessBand.RUHE, r.band)
    }

    @Test
    fun `Strafterme sind gedeckelt`() {
        val rhr = RestingHrAssessment(
            available = true,
            unavailableReason = null,
            baseline = 50.0,
            sigma = 1.5,
            current = 70.0,
            deltaBpm = 20.0,
            z = 20.0,
            flag = RecoveryFlag.ROT,
            baselineDays = 40,
            streakDays = 5,
            message = "x",
        )
        val r = computeReadiness(
            restingHr = rhr,
            sleep = goodSleep,
            tsb = -200.0,
            trainingHistoryDays = 40,
        )
        assertEquals(45.0, r.penaltyRhr, 0.0)
        assertEquals(30.0, r.penaltyLoad, 0.0)
        assertEquals(25.0, r.score, 1e-9)
    }

    @Test
    fun `Confidence-Gate - ohne Historie kein Gesamtscore`() {
        val r = computeReadiness(
            restingHr = goodRhr,
            sleep = goodSleep,
            tsb = 0.0,
            trainingHistoryDays = 10,
        )
        assertFalse(r.available)
        assertTrue(r.unavailableReason!!.contains("Trainingshistorie"))
        assertEquals(Confidence.NONE, r.confidence)
    }

    @Test
    fun `Confidence-Gate nennt alle fehlenden Signale`() {
        val r = computeReadiness(
            restingHr = RestingHrAssessment.unavailable("x", 3),
            sleep = SleepAssessment.unavailable("y", 2),
            trainingHistoryDays = 5,
        )
        assertFalse(r.available)
        assertTrue(r.unavailableReason!!.contains("Ruhepuls"))
        assertTrue(r.unavailableReason.contains("Schlaf"))
    }

    @Test
    fun `fehlende Einzelsignale erzeugen keinen Strafterm`() {
        val r = computeReadiness(
            restingHr = RestingHrAssessment.unavailable("x", 3),
            sleep = SleepAssessment.unavailable("y", 2),
        )
        assertEquals(0.0, r.penaltyRhr, 0.0)
        assertEquals(0.0, r.penaltySleep, 0.0)
        assertEquals(0.0, r.penaltyLoad, 0.0)
        assertEquals(100.0, r.score, 0.0)
    }

    @Test
    fun `Baender 80, 60 und 40`() {
        assertEquals(ReadinessBand.HART, classifyReadiness(100.0))
        assertEquals(ReadinessBand.HART, classifyReadiness(80.0))
        assertEquals(ReadinessBand.NORMAL, classifyReadiness(79.9))
        assertEquals(ReadinessBand.NORMAL, classifyReadiness(60.0))
        assertEquals(ReadinessBand.LOCKER, classifyReadiness(59.9))
        assertEquals(ReadinessBand.LOCKER, classifyReadiness(40.0))
        assertEquals(ReadinessBand.RUHE, classifyReadiness(39.9))
    }

    @Test
    fun `mit HRV gilt die Gewichtung 40-25-20-15`() {
        val r = computeReadiness(
            restingHr = goodRhr,
            sleep = goodSleep,
            hrv = hrvWith(z = -2.0, status = HrvStatus.NIEDRIG, flag = RecoveryFlag.ORANGE),
            tsb = 0.0,
            trainingHistoryDays = 40,
        )
        assertTrue(r.usesHrv)
        // (2,0 − 0,75) × 50 = 62,5 Strafpunkte, davon 40 %.
        assertEquals(62.5, r.penaltyHrv, 1e-9)
        assertEquals(75.0, r.score, 1e-9)
        assertEquals(Confidence.HIGH, r.confidence)
        assertTrue(r.detail.contains("HRV, Ruhepuls"))
        assertFalse(r.detail.contains("ohne HRV"))
    }

    @Test
    fun `HRV im Band kostet nichts, Ruhepuls wirkt nur noch mit 25 Prozent`() {
        val rhr = RestingHrAssessment(
            available = true,
            unavailableReason = null,
            baseline = 50.0,
            sigma = 1.5,
            current = 53.0,
            deltaBpm = 3.0,
            z = 2.0,
            flag = RecoveryFlag.GELB,
            baselineDays = 40,
            streakDays = 2,
            message = "x",
        )
        val withHrv = computeReadiness(
            restingHr = rhr,
            sleep = goodSleep,
            hrv = hrvWith(z = 0.0, status = HrvStatus.IM_BAND, flag = RecoveryFlag.GRUEN),
            tsb = 0.0,
            trainingHistoryDays = 40,
        )
        // 27 von 45 moeglichen Ruhepuls-Strafpunkten, gewichtet mit 25 %.
        assertEquals(0.0, withHrv.penaltyHrv, 0.0)
        assertEquals(85.0, withHrv.score, 1e-9)

        val withoutHrv = computeReadiness(
            restingHr = rhr,
            sleep = goodSleep,
            tsb = 0.0,
            trainingHistoryDays = 40,
        )
        assertFalse(withoutHrv.usesHrv)
        assertEquals(73.0, withoutHrv.score, 1e-9)
        assertEquals(Confidence.MEDIUM, withoutHrv.confidence)
    }

    @Test
    fun `parasympathische Saettigung kostet den halben HRV-Strafterm`() {
        val r = computeReadiness(
            restingHr = goodRhr,
            sleep = goodSleep,
            hrv = hrvWith(z = 1.4, status = HrvStatus.SAETTIGUNG, flag = RecoveryFlag.ORANGE),
            tsb = 0.0,
            trainingHistoryDays = 40,
        )
        assertEquals(50.0, r.penaltyHrv, 0.0)
        assertEquals(80.0, r.score, 1e-9)
    }

    @Test
    fun `alle vier Signale am Anschlag ergeben 0`() {
        val rhr = RestingHrAssessment(
            available = true,
            unavailableReason = null,
            baseline = 50.0,
            sigma = 1.5,
            current = 70.0,
            deltaBpm = 20.0,
            z = 20.0,
            flag = RecoveryFlag.ROT,
            baselineDays = 40,
            streakDays = 5,
            message = "x",
        )
        val sleep = SleepAssessment(
            available = true,
            unavailableReason = null,
            baselineH = 7.0,
            sigmaH = 0.5,
            lastNightH = 3.0,
            deviationH = -4.0,
            z = -8.0,
            debt7dH = -20.0,
            flag = RecoveryFlag.ROT,
            validNights = 28,
            shortSleeper = false,
            message = "x",
        )
        val r = computeReadiness(
            restingHr = rhr,
            sleep = sleep,
            hrv = hrvWith(z = -10.0, status = HrvStatus.NIEDRIG, flag = RecoveryFlag.ROT),
            tsb = -200.0,
            trainingHistoryDays = 40,
        )
        assertEquals(100.0, r.penaltyHrv, 0.0)
        assertEquals(0.0, r.score, 0.0)
        assertEquals(ReadinessBand.RUHE, r.band)
    }

    @Test
    fun `HRV allein oeffnet kein Gate, Ruhepuls fehlt weiterhin`() {
        val r = computeReadiness(
            restingHr = RestingHrAssessment.unavailable("x", 3),
            sleep = goodSleep,
            hrv = hrvWith(z = 0.0, status = HrvStatus.IM_BAND, flag = RecoveryFlag.GRUEN),
            trainingHistoryDays = 40,
        )
        assertFalse(r.available)
        assertTrue(r.unavailableReason!!.contains("Ruhepuls"))
    }

    @Test
    fun `nicht berechenbare HRV faellt auf die Formel ohne HRV zurueck`() {
        val r = computeReadiness(
            restingHr = goodRhr,
            sleep = goodSleep,
            hrv = HrvAssessment.unavailable("Braucht noch 6 Tage HRV-Daten.", 8),
            tsb = 0.0,
            trainingHistoryDays = 40,
        )
        assertFalse(r.usesHrv)
        assertEquals(0.0, r.penaltyHrv, 0.0)
        assertEquals(100.0, r.score, 1e-9)
        assertTrue(r.detail.contains("ohne HRV"))
        assertTrue(r.hrv.unavailableReason!!.contains("Braucht noch 6 Tage"))
    }

    @Test
    fun `End-to-End ueber echte Serien - Kurzschlaefer bleibt bei 100`() {
        val rhr = assessRestingHeartRate(daily(filled(60, 50.0)))
        val sleep = assessSleep(daily(filled(28, 5.8)))
        val r = computeReadiness(
            restingHr = rhr,
            sleep = sleep,
            tsb = -5.0,
            trainingHistoryDays = 60,
        )
        assertTrue(r.available)
        assertEquals(100.0, r.score, 1e-9)
        assertEquals(ReadinessBand.HART, r.band)
        assertTrue(r.sleep.shortSleeper)
    }

    // --- group('Empfehlungen') ---

    private fun readinessWith(
        score: Double,
        rhrFlag: RecoveryFlag = RecoveryFlag.GRUEN,
        sleepFlag: RecoveryFlag = RecoveryFlag.GRUEN,
        hrvFlag: RecoveryFlag? = null,
        tsb: Double? = null,
    ): Readiness {
        val rhr = RestingHrAssessment(
            available = true,
            unavailableReason = null,
            baseline = 50.0,
            sigma = 1.5,
            current = 50.0,
            deltaBpm = 0.0,
            z = 0.0,
            flag = rhrFlag,
            baselineDays = 40,
            streakDays = 0,
            message = "rhr",
        )
        val sleep = SleepAssessment(
            available = true,
            unavailableReason = null,
            baselineH = 7.0,
            sigmaH = 0.5,
            lastNightH = 7.0,
            deviationH = 0.0,
            z = 0.0,
            debt7dH = 0.0,
            flag = sleepFlag,
            validNights = 28,
            shortSleeper = false,
            message = "schlaf",
        )
        val hrv = if (hrvFlag == null) {
            HrvAssessment.MISSING
        } else {
            HrvAssessment(
                available = true,
                unavailableReason = null,
                baselineLn = ln(50.0),
                sigmaLn = 0.12,
                currentLn = ln(50.0),
                lastRmssd = 50.0,
                z = if (hrvFlag == RecoveryFlag.GRUEN) 0.0 else -2.0,
                status = if (hrvFlag == RecoveryFlag.GRUEN) {
                    HrvStatus.IM_BAND
                } else {
                    HrvStatus.NIEDRIG
                },
                flag = hrvFlag,
                historyDays = 28,
                recentDays = 7,
                message = "hrv",
            )
        }
        return Readiness(
            available = true,
            unavailableReason = null,
            score = score,
            band = classifyReadiness(score),
            penaltyRhr = 0.0,
            penaltySleep = 0.0,
            penaltyLoad = 0.0,
            restingHr = rhr,
            sleep = sleep,
            hrv = hrv,
            usesHrv = hrvFlag != null,
            tsb = tsb,
            confidence = Confidence.MEDIUM,
            headline = "",
            detail = "",
        )
    }

    @Test
    fun `Readiness unter 40 ergibt einen Ruhetag`() {
        val r = recommendToday(readiness = readinessWith(score = 30.0), tsb = 0.0)
        assertEquals(DailyRecommendationKind.RUHETAG, r.kind)
        assertTrue(r.reasons.isNotEmpty())
    }

    @Test
    fun `roter Ruhepuls ergibt einen Ruhetag, auch bei gutem Score`() {
        val r = recommendToday(
            readiness = readinessWith(score = 90.0, rhrFlag = RecoveryFlag.ROT),
            tsb = 0.0,
        )
        assertEquals(DailyRecommendationKind.RUHETAG, r.kind)
    }

    @Test
    fun `Readiness unter 60 ergibt locker Z2`() {
        val r = recommendToday(readiness = readinessWith(score = 55.0), tsb = 0.0)
        assertEquals(DailyRecommendationKind.LOCKER_Z2, r.kind)
        assertTrue(r.detail.contains("Intervalle"))
    }

    @Test
    fun `orange Schlaf-Stufe ergibt locker Z2`() {
        val r = recommendToday(
            readiness = readinessWith(score = 85.0, sleepFlag = RecoveryFlag.ORANGE),
            tsb = 0.0,
        )
        assertEquals(DailyRecommendationKind.LOCKER_Z2, r.kind)
    }

    @Test
    fun `TSB unter minus 25 ergibt eine Regenerationsfahrt`() {
        val r = recommendToday(readiness = readinessWith(score = 70.0), tsb = -28.0)
        assertEquals(DailyRecommendationKind.RECOVERY, r.kind)
    }

    @Test
    fun `Readiness ab 80 mit Budget gibt die harte Einheit frei`() {
        val r = recommendToday(readiness = readinessWith(score = 85.0), tsb = -10.0)
        assertEquals(DailyRecommendationKind.HARTE_EINHEIT, r.kind)
    }

    @Test
    fun `ohne HIT-Budget bleibt es bei der Grundlageneinheit`() {
        val r = recommendToday(
            readiness = readinessWith(score = 85.0),
            tsb = -10.0,
            hitBudgetLeft = false,
        )
        assertEquals(DailyRecommendationKind.GRUNDLAGE, r.kind)
    }

    @Test
    fun `ohne Gesamtscore steuern nur die Einzelsignale`() {
        val r = recommendToday(
            readiness = computeReadiness(
                restingHr = RestingHrAssessment.unavailable("x", 0),
                sleep = SleepAssessment.unavailable("y", 0),
            ),
        )
        assertEquals(DailyRecommendationKind.GRUNDLAGE, r.kind)
    }

    @Test
    fun `rote HRV ergibt einen Ruhetag, auch bei gutem Score`() {
        val r = recommendToday(
            readiness = readinessWith(score = 90.0, hrvFlag = RecoveryFlag.ROT),
            tsb = 0.0,
        )
        assertEquals(DailyRecommendationKind.RUHETAG, r.kind)
        assertEquals("hrv", r.reasons.first())
    }

    @Test
    fun `orange HRV ergibt locker Z2`() {
        val r = recommendToday(
            readiness = readinessWith(score = 85.0, hrvFlag = RecoveryFlag.ORANGE),
            tsb = 0.0,
        )
        assertEquals(DailyRecommendationKind.LOCKER_Z2, r.kind)
    }

    @Test
    fun `gelbe HRV vertagt die harte Einheit`() {
        val r = recommendToday(
            readiness = readinessWith(score = 85.0, hrvFlag = RecoveryFlag.GELB),
            tsb = -10.0,
        )
        assertEquals(DailyRecommendationKind.GRUNDLAGE, r.kind)

        val green = recommendToday(
            readiness = readinessWith(score = 85.0, hrvFlag = RecoveryFlag.GRUEN),
            tsb = -10.0,
        )
        assertEquals(DailyRecommendationKind.HARTE_EINHEIT, green.kind)
    }

    // --- group('Readiness-Reihe (rückwirkend)') ---

    private val today = dt(2026, 8, 8)

    private val fitness = computeFitnessSeries(
        constantLoads(40, 60.0, end = dt(2026, 8, 8)),
        until = dt(2026, 8, 8),
    )

    @Test
    fun `liefert sieben aufsteigende Tage bis heute`() {
        val series = computeReadinessSeries(
            restingHrSeries = daily(filled(60, 50.0), end = today),
            sleepSeries = daily(filled(40, 7.0), end = today),
            fitness = fitness,
            today = today,
        )
        assertEquals(7, series.size)
        assertEquals(dt(2026, 8, 2), series.first().day)
        assertEquals(today, series.last().day)
        assertTrue(series.all { it.readiness.available })
        assertEquals(7, availableReadinessScores(series).size)
        assertEquals(100.0, series.last().readiness.score, 1e-9)
    }

    @Test
    fun `jeder Tag sieht nur die bis dahin vorhandenen Daten`() {
        // Ruhepuls und Schlaf kippen erst in den letzten Tagen.
        val series = computeReadinessSeries(
            restingHrSeries = daily(
                filled(56, 50.0) + listOf(62.0, 62.0, 62.0, 62.0),
                end = today,
            ),
            sleepSeries = daily(
                filled(36, 7.0) + listOf(2.5, 2.5, 2.5, 2.5),
                end = today,
            ),
            fitness = fitness,
            today = today,
        )
        val scores = availableReadinessScores(series)
        assertEquals(7, scores.size)
        // Die fruehen Tage der Woche sind unauffaellig, die spaeten brechen ein.
        assertEquals(100.0, scores.first(), 1e-9)
        assertTrue(scores.count { it < 40 } >= 3)
    }

    @Test
    fun `schliesst den Deload-Trigger 3 von 7 Tagen`() {
        val series = computeReadinessSeries(
            restingHrSeries = daily(
                filled(56, 50.0) + listOf(62.0, 62.0, 62.0, 62.0),
                end = today,
            ),
            sleepSeries = daily(
                filled(36, 7.0) + listOf(2.5, 2.5, 2.5, 2.5),
                end = today,
            ),
            fitness = fitness,
            today = today,
        )
        val deload = assessDeload(
            fitness,
            readinessLast7 = availableReadinessScores(series),
        )
        assertTrue(deload.recommended)
        assertTrue(deload.triggers.single().contains("Erholung"))
    }

    @Test
    fun `HRV geht in die Reihe ein und senkt die Scores`() {
        val restingHr = daily(filled(60, 50.0), end = today)
        val sleep = daily(filled(40, 7.0), end = today)
        val hrv = daily(filled(21, 50.0) + filled(7, 33.0), end = today)
        val withHrv = computeReadinessSeries(
            restingHrSeries = restingHr,
            sleepSeries = sleep,
            hrvSeries = hrv,
            fitness = fitness,
            today = today,
        )
        assertTrue(withHrv.last().readiness.usesHrv)
        assertTrue(withHrv.last().readiness.score < 100)

        val withoutHrv = computeReadinessSeries(
            restingHrSeries = restingHr,
            sleepSeries = sleep,
            fitness = fitness,
            today = today,
        )
        assertFalse(withoutHrv.last().readiness.usesHrv)
        assertEquals(100.0, withoutHrv.last().readiness.score, 1e-9)
    }

    @Test
    fun `ohne Daten entstehen Tage ohne Gesamtscore und nichts wirft`() {
        val series = computeReadinessSeries(today = today)
        assertEquals(7, series.size)
        assertTrue(series.all { !it.readiness.available })
        assertTrue(availableReadinessScores(series).isEmpty())
        assertFalse(
            assessDeload(
                FitnessSeries.EMPTY,
                readinessLast7 = availableReadinessScores(series),
            ).recommended,
        )
    }

    @Test
    fun `days kleiner gleich 0 liefert eine leere Reihe`() {
        assertTrue(computeReadinessSeries(today = today, days = 0).isEmpty())
    }

    // --- group('Deload') ---

    private fun seriesWithTsb(tsbs: List<Double>): FitnessSeries = FitnessSeries(
        points = List(tsbs.size) { i ->
            FitnessPoint(
                day = dt(2026, 8, 8 - (tsbs.size - 1 - i)),
                load = 0.0,
                ctl = 50.0,
                atl = 50.0,
                tsb = tsbs[i],
                rampRate7d = 0.0,
                loadRatio = 1.0,
            )
        },
        historyDays = tsbs.size,
        seedLoad = 50.0,
        displayReady = true,
    )

    @Test
    fun `TSB unter minus 30 ueber drei Tage loest Deload aus`() {
        val d = assessDeload(seriesWithTsb(listOf(-10.0, -31.0, -32.0, -33.0)))
        assertTrue(d.recommended)
        assertEquals(1, d.triggers.size)
        assertTrue(d.detail.contains("40–50 %"))
        assertTrue(d.detail.contains("Intensität"))
        assertEquals(0.40, d.volumeReductionLow, 0.0)
        assertEquals(0.50, d.volumeReductionHigh, 0.0)
    }

    @Test
    fun `nur zwei Tage unter minus 30 loesen nichts aus`() {
        val d = assessDeload(seriesWithTsb(listOf(-10.0, -20.0, -31.0, -32.0)))
        assertFalse(d.recommended)
        assertEquals("Kein Deload nötig", d.title)
    }

    @Test
    fun `Rampenrate ueber 8 ueber drei Wochen loest Deload aus`() {
        val points = List(21) { i ->
            FitnessPoint(
                day = dt(2026, 8, 8 - (20 - i)),
                load = 100.0,
                ctl = 50.0,
                atl = 50.0,
                tsb = 0.0,
                rampRate7d = 9.0,
                loadRatio = 1.0,
            )
        }
        val d = assessDeload(
            FitnessSeries(
                points = points,
                historyDays = 21,
                seedLoad = 0.0,
                displayReady = true,
            ),
        )
        assertTrue(d.recommended)
        assertTrue(d.triggers.first().contains("drei Wochen"))
    }

    @Test
    fun `Readiness unter 40 an drei von sieben Tagen loest Deload aus`() {
        val d = assessDeload(
            seriesWithTsb(listOf(0.0, 0.0, 0.0)),
            readinessLast7 = listOf(80.0, 35.0, 30.0, 70.0, 39.0, 60.0, 65.0),
        )
        assertTrue(d.recommended)
        assertTrue(d.triggers.single().contains("Erholung"))
    }

    @Test
    fun `Wochenlastsprung ist nur eine Warnung, kein Deload`() {
        val d = assessDeload(
            seriesWithTsb(listOf(0.0, 0.0, 0.0)),
            weeklyLoad = 500.0,
            fourWeekMeanWeeklyLoad = 300.0,
        )
        assertFalse(d.recommended)
        assertTrue(d.warnings.first().contains("deutlich gestiegen"))
        assertFalse(d.warnings.joinToString(" ").lowercase().contains("verletzung"))
    }

    @Test
    fun `leere Fitness-Serie wirft nicht`() {
        val d = assessDeload(FitnessSeries.EMPTY)
        assertFalse(d.recommended)
        assertTrue(d.triggers.isEmpty())
    }
}
