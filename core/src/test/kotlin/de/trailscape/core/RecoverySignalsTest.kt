package de.trailscape.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Portierung der Gruppen `Ruhepuls-Bewertung`, `HRV-Bewertung` und
 * `Schlaf-Bewertung` aus `test/training_load_test.dart`.
 */
class RecoverySignalsTest {

    // Baseline-Fenster = Tage −60 … −8. 60 Werte reichen dafuer sicher.
    private fun rhrSeries(recent5: List<Double>, base: Double = 50.0): List<DailyValue> =
        daily(filled(55, base) + recent5)

    // --- group('Ruhepuls-Bewertung') ---

    @Test
    fun `unter 21 Werten im Baseline-Fenster deaktiviert`() {
        val a = assessRestingHeartRate(daily(filled(20, 50.0)))
        assertFalse(a.available)
        assertEquals(RecoveryFlag.UNBEKANNT, a.flag)
        assertTrue(a.unavailableReason!!.contains("Baseline wird aufgebaut"))
    }

    @Test
    fun `leere Ruhepuls-Serie wirft nicht`() {
        val a = assessRestingHeartRate(emptyList())
        assertFalse(a.available)
        assertEquals(0, a.baselineDays)
    }

    @Test
    fun `stabiler Ruhepuls ist gruen`() {
        val a = assessRestingHeartRate(rhrSeries(listOf(50.0, 50.0, 50.0, 50.0, 50.0)))
        assertTrue(a.available)
        assertEquals(50.0, a.baseline!!, 1e-9)
        assertEquals(1.5, a.sigma!!, 1e-9) // MAD = 0 -> Floor greift
        assertEquals(0.0, a.deltaBpm!!, 1e-9)
        assertEquals(RecoveryFlag.GRUEN, a.flag)
    }

    @Test
    fun `exakt an der Gelb-Schwelle - Delta 3 bpm an zwei Tagen`() {
        val a = assessRestingHeartRate(rhrSeries(listOf(50.0, 50.0, 50.0, 53.0, 53.0)))
        assertEquals(3.0, a.deltaBpm!!, 1e-9)
        assertEquals(2.0, a.z!!, 1e-9)
        assertEquals(RecoveryFlag.GELB, a.flag)
        assertTrue(a.streakDays >= 2)
    }

    @Test
    fun `knapp unter der Gelb-Schwelle - Delta 2,9 bpm bleibt gruen`() {
        val a = assessRestingHeartRate(rhrSeries(listOf(50.0, 50.0, 50.0, 52.9, 52.9)))
        assertEquals(2.9, a.deltaBpm!!, 1e-9)
        assertEquals(RecoveryFlag.GRUEN, a.flag)
    }

    @Test
    fun `ein einzelner Ausreisser loest nichts aus (3-Tages-Median)`() {
        val a = assessRestingHeartRate(rhrSeries(listOf(50.0, 50.0, 50.0, 50.0, 58.0)))
        assertEquals(50.0, a.current!!, 1e-9)
        assertEquals(RecoveryFlag.GRUEN, a.flag)
    }

    @Test
    fun `Delta groesser gleich 3 ohne z groesser gleich 1 bleibt gruen`() {
        // Streuende Baseline -> σ = 1,4826 × 4 = 5,93 -> z(Δ=3) ≈ 0,51
        val pattern = listOf(44.0, 46.0, 48.0, 50.0, 52.0, 54.0, 56.0)
        val spread = List(55) { i -> pattern[i % 7] }
        val a = assessRestingHeartRate(daily(spread + listOf(53.0, 53.0, 53.0)))
        assertEquals(1.4826 * 4, a.sigma!!, 1e-6)
        assertEquals(3.0, a.deltaBpm!!, 1e-9)
        assertTrue(a.z!! < 1.0)
        assertEquals(RecoveryFlag.GRUEN, a.flag)
    }

    @Test
    fun `exakt an der Orange-Schwelle - Delta 5 bpm und z groesser gleich 1,5`() {
        val a = assessRestingHeartRate(rhrSeries(listOf(50.0, 50.0, 55.0, 55.0, 55.0)))
        assertEquals(5.0, a.deltaBpm!!, 1e-9)
        assertEquals(5 / 1.5, a.z!!, 1e-9)
        // Drei aufeinanderfolgende Tage ≥ 5 bpm -> laut Dokument bereits rot.
        assertEquals(RecoveryFlag.ROT, a.flag)
    }

    @Test
    fun `Delta 5 an nur zwei Tagen bleibt orange`() {
        val a = assessRestingHeartRate(rhrSeries(listOf(50.0, 50.0, 50.0, 55.0, 55.0)))
        assertEquals(5.0, a.deltaBpm!!, 1e-9)
        assertEquals(RecoveryFlag.ORANGE, a.flag)
    }

    @Test
    fun `Delta 4,9 an zwei Tagen ist gelb, nicht orange`() {
        val a = assessRestingHeartRate(rhrSeries(listOf(50.0, 50.0, 50.0, 54.9, 54.9)))
        assertEquals(4.9, a.deltaBpm!!, 1e-9)
        assertEquals(RecoveryFlag.GELB, a.flag)
    }

    @Test
    fun `Delta groesser gleich 8 bpm ist sofort rot`() {
        val a = assessRestingHeartRate(rhrSeries(listOf(50.0, 50.0, 50.0, 50.0, 58.0)))
        assertEquals(RecoveryFlag.GRUEN, a.flag) // Einzeltag zaehlt nicht
        val b = assessRestingHeartRate(rhrSeries(listOf(50.0, 50.0, 50.0, 58.0, 58.0)))
        assertEquals(8.0, b.deltaBpm!!, 1e-9)
        assertEquals(RecoveryFlag.ROT, b.flag)
    }

    @Test
    fun `Text nennt moegliche Ursachen statt einer Diagnose`() {
        val a = assessRestingHeartRate(rhrSeries(listOf(50.0, 50.0, 50.0, 58.0, 58.0)))
        assertTrue(a.message.contains("Infekt"))
        assertFalse(a.message.lowercase().contains("übertrainiert"))
    }

    @Test
    fun `nach einer harten Tour wird die Formulierung angepasst`() {
        val a = assessRestingHeartRate(
            rhrSeries(listOf(50.0, 50.0, 50.0, 53.0, 53.0)),
            afterHardDay = true,
        )
        assertEquals(RecoveryFlag.GELB, a.flag)
        assertTrue(a.message.contains("erwartbar"))
    }

    @Test
    fun `ohne aktuellen Ruhepuls-Wert wird nichts behauptet`() {
        val a = assessRestingHeartRate(
            daily(filled(60, 50.0), end = dt(2026, 8, 1)),
            today = dt(2026, 8, 8),
        )
        assertFalse(a.available)
        assertTrue(a.unavailableReason!!.contains("aktueller"))
    }

    @Test
    fun `unplausible Ruhepuls-Werte werden ignoriert`() {
        val a = assessRestingHeartRate(
            daily(filled(55, 50.0) + listOf(200.0, 5.0, 50.0, 50.0, 50.0)),
        )
        assertTrue(a.available)
        assertEquals(50.0, a.baseline!!, 1e-9)
    }

    // --- group('HRV-Bewertung') ---

    @Test
    fun `leere HRV-Serie wirft nicht und meldet den Grund`() {
        val h = assessHrv(emptyList())
        assertFalse(h.available)
        assertEquals(RecoveryFlag.UNBEKANNT, h.flag)
        assertEquals(HrvStatus.UNBEKANNT, h.status)
        assertTrue(h.unavailableReason!!.contains("Noch keine HRV-Werte"))
        assertNull(h.currentRmssd)
    }

    @Test
    fun `unter 14 Tagen im Vergleichszeitraum kommt der Aufbauhinweis mit Restzahl`() {
        // 10 Tage Historie: Tage 0–6 sind das Rollfenster, nur 3 Tage liegen im
        // Vergleichszeitraum (Tage 7…59).
        val h = assessHrv(daily(filled(10, 50.0)))
        assertFalse(h.available)
        assertEquals(3, h.historyDays)
        assertTrue(h.unavailableReason!!.contains("Braucht noch 11 Tage HRV-Daten"))
        // Auch ohne Bewertung bleibt der Messwert anzeigbar.
        assertEquals(50.0, h.lastRmssd!!, 1e-9)
    }

    @Test
    fun `genau 21 Tage reichen fuer die volle Wertung`() {
        // 7 Tage Rollfenster + 14 Tage Vergleichszeitraum ist das Minimum,
        // seit sich die beiden Fenster nicht mehr ueberlappen.
        val h = assessHrv(daily(filled(21, 50.0)))
        assertTrue(h.available)
        assertEquals(14, h.historyDays)
        assertEquals(7, h.recentDays)

        assertFalse(assessHrv(daily(filled(20, 50.0))).available)
    }

    @Test
    fun `das Rollfenster steckt nicht in seiner eigenen Baseline`() {
        // Sieben Tage Einbruch: Die Baseline darf davon nichts sehen, sonst
        // zoege der Einbruch seine eigene Referenz mit.
        val h = assessHrv(daily(filled(30, 50.0) + filled(7, 30.0)))
        assertEquals(50.0, h.baselineRmssd!!, 1e-6)
        assertEquals(30.0, h.currentRmssd!!, 1e-6)
        assertEquals(30, h.historyDays)
    }

    @Test
    fun `stabile Serie liegt im Band und Sigma hat einen Boden`() {
        val h = assessHrv(daily(filled(28, 50.0)))
        assertTrue(h.available)
        assertEquals(HrvStatus.IM_BAND, h.status)
        assertEquals(RecoveryFlag.GRUEN, h.flag)
        assertEquals(0.0, h.z!!, 1e-9)
        assertEquals(hrvMinSigmaLn, h.sigmaLn!!, 0.0)
        assertEquals(50.0, h.currentRmssd!!, 1e-9)
        assertEquals(50.0, h.baselineRmssd!!, 1e-9)
        assertTrue(h.bandLowRmssd!! < 50)
        assertTrue(h.bandHighRmssd!! > 50)
        assertTrue(h.message.contains("Normalband"))
    }

    @Test
    fun `Einbruch unter das Band ist niedrig und mindestens orange`() {
        val h = assessHrv(daily(filled(21, 50.0) + filled(7, 35.0)))
        assertTrue(h.available)
        assertEquals(HrvStatus.NIEDRIG, h.status)
        assertTrue(atLeast(h.flag, RecoveryFlag.ORANGE))
        assertTrue(h.z!! < -hrvBandFactor)
        assertTrue(h.deviationPercent!! < -10)
        assertTrue(h.message.contains("unter deinem Normalband"))
        // Nuechterne Sprache: keine Diagnose.
        assertFalse(h.message.lowercase().contains("übertrain"))
    }

    @Test
    fun `leichter Rueckgang auf rauschfreier Serie ist schon deutlich`() {
        // Ohne Rauschen greift der Sigma-Boden (0,05): −8 % sind dort bereits
        // 1,7 Tagesstreuungen. Auf einer realistisch streuenden Serie waere
        // derselbe Rueckgang unauffaellig — siehe die Rauschtests unten.
        val h = assessHrv(daily(filled(21, 50.0) + filled(7, 46.0)))
        assertEquals(HrvStatus.NIEDRIG, h.status)
        assertEquals(RecoveryFlag.ORANGE, h.flag)
        assertTrue(h.message.contains("deutlich unter"))
    }

    @Test
    fun `ueber dem Band ist ohne Ruhepuls-Auffaelligkeit ein gutes Zeichen`() {
        val h = assessHrv(daily(filled(21, 50.0) + filled(7, 62.0)))
        assertEquals(HrvStatus.UEBER_BAND, h.status)
        assertEquals(RecoveryFlag.GRUEN, h.flag)
        assertTrue(h.z!! > hrvBandFactor)
        assertTrue(h.message.contains("gut erholt"))
    }

    @Test
    fun `ueber dem Band bei erhoehtem Ruhepuls ist Saettigung ein Warnzeichen`() {
        val h = assessHrv(
            daily(filled(21, 50.0) + filled(7, 62.0)),
            restingHrFlag = RecoveryFlag.GELB,
        )
        assertEquals(HrvStatus.SAETTIGUNG, h.status)
        assertEquals(RecoveryFlag.ORANGE, h.flag)
        assertTrue(h.message.contains("Ruhepuls"))
        assertTrue(h.message.contains("beobachte"))
    }

    @Test
    fun `ohne aktuelle Messungen im Rollfenster keine Aussage`() {
        val h = assessHrv(
            daily(filled(20, 50.0), end = dt(2026, 7, 25)),
            today = dt(2026, 8, 8),
        )
        assertFalse(h.available)
        assertTrue(h.historyDays >= hrvMinBaselineDays)
        assertTrue(h.unavailableReason!!.contains("letzten sieben Tagen"))
    }

    @Test
    fun `unplausible HRV-Werte fallen raus`() {
        val h = assessHrv(daily(filled(28, 900.0)))
        assertFalse(h.available)
        assertEquals(0, h.historyDays)

        val mixed = assessHrv(
            daily(
                filled(21, 50.0) +
                    // Aussetzer der Uhr: 0 ms und ein absurd hoher Wert.
                    listOf(0.0, 900.0, 50.0, 50.0, 50.0, 50.0, 50.0),
            ),
        )
        assertTrue(mixed.available)
        assertEquals(21, mixed.historyDays)
        assertEquals(5, mixed.recentDays)
        assertEquals(HrvStatus.IM_BAND, mixed.status)
    }

    @Test
    fun `nur die Tage 7 bis 59 zaehlen zur HRV-Baseline`() {
        val h = assessHrv(daily(filled(90, 50.0)))
        assertEquals(hrvBaselineDays - hrvRollingDays, h.historyDays)
        assertEquals(hrvRollingDays, h.recentDays)
    }

    // --- group('Schlaf-Bewertung') ---

    @Test
    fun `unter 14 Naechten deaktiviert`() {
        val a = assessSleep(daily(filled(10, 7.0)))
        assertFalse(a.available)
        assertEquals(RecoveryFlag.UNBEKANNT, a.flag)
        assertTrue(a.unavailableReason!!.contains("14"))
    }

    @Test
    fun `leere Schlaf-Serie wirft nicht`() {
        assertFalse(assessSleep(emptyList()).available)
    }

    @Test
    fun `Kurzschlaefer mit 5,8 h Normalwert ist gruen`() {
        val a = assessSleep(daily(filled(28, 5.8)))
        assertTrue(a.available)
        assertEquals(5.8, a.baselineH!!, 1e-9)
        assertEquals(0.0, a.deviationH!!, 1e-9)
        assertEquals(RecoveryFlag.GRUEN, a.flag)
        assertTrue(a.shortSleeper)
        assertTrue(a.message.contains("Normalwert"))
    }

    @Test
    fun `Kurzschlaefer mit akutem Einbruch bekommt eine Warnung`() {
        val a = assessSleep(daily(filled(27, 5.8) + listOf(4.0)))
        assertEquals(5.8, a.baselineH!!, 1e-9)
        assertEquals(-1.8, a.deviationH!!, 1e-9)
        assertEquals(RecoveryFlag.ORANGE, a.flag)
    }

    @Test
    fun `leichter Einbruch von minus 1,0 h ist gelb`() {
        val a = assessSleep(daily(filled(27, 7.0) + listOf(6.0)))
        assertEquals(-1.0, a.deviationH!!, 1e-9)
        assertEquals(RecoveryFlag.GELB, a.flag)
    }

    @Test
    fun `z-Regel greift auch ohne minus 1,0-h-Abweichung`() {
        // σ-Floor 0,5 h -> dev −0,8 h ergibt z = −1,6
        val a = assessSleep(daily(filled(27, 7.0) + listOf(6.2)))
        assertEquals(-0.8, a.deviationH!!, 1e-9)
        assertEquals(-1.6, a.z!!, 1e-9)
        assertEquals(RecoveryFlag.GELB, a.flag)
    }

    @Test
    fun `minus 0,5 h bei schwankendem Schlaf bleibt gruen`() {
        // Median 7,0 h, σ = 1,4826 × 0,5 = 0,741 -> z(−0,5 h) = −0,67
        val nights = (0 until 9).flatMap { listOf(6.5, 7.0, 7.5) } + listOf(6.5)
        val a = assessSleep(daily(nights))
        assertEquals(7.0, a.baselineH!!, 1e-9)
        assertEquals(1.4826 * 0.5, a.sigmaH!!, 1e-6)
        assertEquals(-0.5, a.deviationH!!, 1e-9)
        assertTrue(a.z!! > -1.0)
        assertEquals(RecoveryFlag.GRUEN, a.flag)
    }

    @Test
    fun `derselbe Ausfall trifft den sehr regelmaessigen Schlaefer haerter`() {
        // Konstanter Schlaf -> σ-Floor 0,5 h -> z(−0,5 h) = −1,0 -> gelb.
        val a = assessSleep(daily(filled(27, 7.0) + listOf(6.5)))
        assertEquals(0.5, a.sigmaH!!, 1e-9)
        assertEquals(-0.5, a.deviationH!!, 1e-9)
        assertEquals(-1.0, a.z!!, 1e-9)
        assertEquals(RecoveryFlag.GELB, a.flag)
    }

    @Test
    fun `7-Tage-Defizit kleiner gleich minus 4 h wird orange`() {
        val a = assessSleep(daily(filled(21, 7.0) + filled(7, 6.0)))
        // Baseline bleibt 7,0 h (Median von 21×7 und 7×6)
        assertEquals(7.0, a.baselineH!!, 1e-9)
        assertEquals(-7.0, a.debt7dH!!, 1e-9)
        assertEquals(RecoveryFlag.ORANGE, a.flag)
    }

    @Test
    fun `rot nur zusammen mit auffaelligem Ruhepuls`() {
        val nights = daily(filled(27, 7.0) + listOf(4.4))
        val withGreen = assessSleep(nights)
        assertEquals(RecoveryFlag.ORANGE, withGreen.flag)
        val withYellow = assessSleep(nights, restingHrFlag = RecoveryFlag.GELB)
        assertTrue(withYellow.deviationH!! <= -2.5)
        assertEquals(RecoveryFlag.ROT, withYellow.flag)
    }

    @Test
    fun `Schlaf-Baseline wird auf 4,5 bis 9,5 h geklemmt`() {
        val low = assessSleep(daily(filled(28, 4.0)))
        assertEquals(4.5, low.baselineH!!, 1e-9)
        assertEquals(-0.5, low.deviationH!!, 1e-9)

        val high = assessSleep(daily(filled(28, 11.0)))
        assertEquals(9.5, high.baselineH!!, 1e-9)
    }

    @Test
    fun `Sensorartefakte unter 2 h und ueber 14 h fallen raus`() {
        val a = assessSleep(
            daily(
                filled(20, 7.0) +
                    listOf(0.0, 0.0, 20.0, 7.0, 7.0, 7.0, 7.0, 7.0),
            ),
        )
        assertTrue(a.available)
        assertEquals(7.0, a.baselineH!!, 1e-9)
        assertEquals(25, a.validNights)
    }

    @Test
    fun `Kurzschlaefer-Hinweis ist entkoppelt und selten`() {
        assertTrue(shortSleeperHint.contains("7–9"))
        assertTrue(shortSleeperHint.contains("ändert das"))
        assertTrue(shouldShowShortSleeperHint(null, dt(2026, 8, 8)))
        assertFalse(shouldShowShortSleeperHint(dt(2026, 8, 1), dt(2026, 8, 8)))
        assertTrue(shouldShowShortSleeperHint(dt(2026, 7, 1), dt(2026, 8, 8)))
    }

    @Test
    fun `ohne aktuelle Nacht wird nichts behauptet`() {
        val a = assessSleep(
            daily(filled(20, 7.0), end = dt(2026, 8, 1)),
            today = dt(2026, 8, 8),
        )
        assertFalse(a.available)
    }
}
