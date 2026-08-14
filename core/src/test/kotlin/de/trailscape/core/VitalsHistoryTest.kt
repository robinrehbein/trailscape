package de.trailscape.core

import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Lokale Vitalhistorie: Zusammenfuehren, Nachladefenster, Persistenz.
 *
 * Der Fall, um den es geht (H3): Health Connect loescht nach 30 Tagen. Wer bei
 * jedem Start 60 Tage liest und das Ergebnis **ersetzt**, hat dauerhaft nur
 * 30 Tage — und die Ruhepuls-Baseline (≥ 21 Werte aus Tag −8 … −60) bleibt
 * unerreichbar, weil dort nur 23 Tage ueberhaupt moeglich sind.
 */
class VitalsHistoryTest {

    private val today = dt(2026, 8, 8)

    private class MemoryStore : KeyValueStore {
        val values = mutableMapOf<String, String>()
        override fun getString(key: String): String? = values[key]
        override fun setString(key: String, value: String) {
            values[key] = value
        }

        override fun remove(key: String) {
            values.remove(key)
        }
    }

    private fun day(offset: Int): LocalDateTime =
        today.toLocalDate().minusDays(offset.toLong()).atStartOfDay()

    /** Zusammenfassung, wie sie Health Connect fuer die letzten [days] Tage liefert. */
    private fun summary(
        days: Int,
        restingHr: Double = 50.0,
        from: Int = 0,
        now: LocalDateTime = today,
    ): VitalsSummary {
        val series = (from until from + days).map {
            DailyValue(day = day(it), value = restingHr)
        }.reversed()
        return VitalsSummary(
            days = days,
            from = day(from + days - 1),
            to = now,
            restingHeartRate = VitalsTrend(series, null, null),
            sleepHours = VitalsTrend.empty,
        )
    }

    // -----------------------------------------------------------------------
    // Zusammenfuehren
    // -----------------------------------------------------------------------

    @Test
    fun `neue Tage kommen dazu, alte bleiben stehen`() {
        // Erster Lauf vor 30 Tagen: Tage −59 … −30 (relativ zu heute).
        val first = VitalsHistory.EMPTY.merge(
            summary(days = 30, restingHr = 50.0, from = 30, now = day(30)),
            now = day(30),
        )
        assertEquals(30, first.restingHeartRate.size)

        // Heute liefert Health Connect nur noch die letzten 30 Tage.
        val second = first.merge(summary(days = 30, restingHr = 52.0), now = today)
        assertEquals(60, second.restingHeartRate.size)
        // Die alten Tage sind unveraendert erhalten.
        assertEquals(50.0, second.restingHeartRate.first().value)
        assertEquals(52.0, second.restingHeartRate.last().value)

        // Und genau darum geht es: Die Baseline traegt jetzt.
        val assessment = assessRestingHeartRate(second.restingHeartRate, today = today)
        assertTrue(assessment.available, assessment.unavailableReason)
        assertTrue(assessment.baselineDays >= 21)
    }

    @Test
    fun `ohne Persistenz reicht das Health-Connect-Fenster nicht`() {
        // Gegenprobe: nur die letzten 30 Tage, so wie es vorher lief. Im
        // Baselinefenster (Tag −8 … −60) liegen davon hoechstens 23 Tage — und
        // die Uhr wird nicht jede Nacht getragen. Hier drei von vier Naechten,
        // was fuer einen Nutzer ohne Brustgurt eher optimistisch ist.
        val worn = summary(days = 30).restingHeartRate.series
            .filterIndexed { index, _ -> index % 4 != 0 }
        val assessment = assessRestingHeartRate(worn, today = today)
        assertFalse(assessment.available)
        assertTrue(assessment.baselineDays < 21)
        assertTrue(assessment.unavailableReason!!.contains("Baseline wird aufgebaut"))

        // Mit lokaler Historie ueber 60 Tage traegt dieselbe Trage-Quote.
        val longTerm = summary(days = 60).restingHeartRate.series
            .filterIndexed { index, _ -> index % 4 != 0 }
        assertTrue(assessRestingHeartRate(longTerm, today = today).available)
    }

    @Test
    fun `ein nachtraeglich korrigierter Tag gewinnt`() {
        val first = VitalsHistory.EMPTY.merge(summary(days = 5, restingHr = 50.0), now = today)
        val second = first.merge(summary(days = 2, restingHr = 47.0), now = today)
        assertEquals(5, second.restingHeartRate.size)
        assertEquals(47.0, second.restingHeartRate.last().value)
        assertEquals(50.0, second.restingHeartRate.first().value)
    }

    @Test
    fun `eine nicht lesbare Serie loescht die Historie nicht`() {
        val first = VitalsHistory.EMPTY.merge(summary(days = 10), now = today)
        val blocked = summary(days = 0).copy(
            restingHeartRate = VitalsTrend.empty,
            unavailable = setOf(VitalsDataKind.RUHEPULS),
        )
        val second = first.merge(blocked, now = today)
        assertEquals(10, second.restingHeartRate.size)
    }

    @Test
    fun `zu alte Tage fallen aus der Historie`() {
        val old = VitalsHistory(
            restingHeartRate = listOf(
                DailyValue(day = day(vitalsHistoryRetentionDays + 10), value = 48.0),
                DailyValue(day = day(10), value = 50.0),
            ),
        )
        val merged = old.merge(summary(days = 1), now = today)
        assertEquals(2, merged.restingHeartRate.size)
        assertTrue(merged.restingHeartRate.none { dayDifference(today, it.day) > vitalsHistoryRetentionDays })
    }

    // -----------------------------------------------------------------------
    // Nachladefenster
    // -----------------------------------------------------------------------

    @Test
    fun `ohne Stand wird das volle Fenster geholt`() {
        assertEquals(60, VitalsHistory.EMPTY.daysToFetch(today, 60))
    }

    @Test
    fun `nach einem Sync wird nur die Luecke plus Ueberlappung geholt`() {
        val history = VitalsHistory(syncedThroughDay = day(3))
        // 3 Tage Luecke + heute + 2 Tage Sicherheitsueberlappung.
        assertEquals(3 + 1 + vitalsSyncOverlapDays, history.daysToFetch(today, 60))
    }

    @Test
    fun `am selben Tag wird nur die Ueberlappung geholt`() {
        val history = VitalsHistory(syncedThroughDay = today)
        assertEquals(1 + vitalsSyncOverlapDays, history.daysToFetch(today, 60))
    }

    @Test
    fun `eine lange Pause deckelt auf das volle Fenster`() {
        val history = VitalsHistory(syncedThroughDay = day(500))
        assertEquals(60, history.daysToFetch(today, 60))
    }

    @Test
    fun `eine rueckwaerts gestellte Uhr laedt vorsichtshalber alles`() {
        val history = VitalsHistory(syncedThroughDay = today.plusDays(5))
        assertEquals(60, history.daysToFetch(today, 60))
    }

    // -----------------------------------------------------------------------
    // Persistenz
    // -----------------------------------------------------------------------

    @Test
    fun `JSON-Roundtrip erhaelt Serien, VO2max und Sync-Stand`() {
        val history = VitalsHistory(
            restingHeartRate = listOf(DailyValue(day = day(2), value = 51.0)),
            sleepHours = listOf(DailyValue(day = day(1), value = 7.25)),
            heartRateVariability = listOf(DailyValue(day = day(0), value = 42.5)),
            vo2max = 51.3,
            vo2maxAt = day(4),
            syncedThroughDay = today,
        )
        val store = MemoryStore()
        writeVitalsHistory(store, history)
        val back = readVitalsHistory(store)

        assertEquals(history.restingHeartRate, back.restingHeartRate)
        assertEquals(history.sleepHours, back.sleepHours)
        assertEquals(history.heartRateVariability, back.heartRateVariability)
        assertEquals(51.3, back.vo2max!!, 1e-9)
        assertEquals(day(4), back.vo2maxAt)
        assertEquals(today, back.syncedThroughDay)
    }

    @Test
    fun `kaputter oder fehlender Eintrag wirft nicht`() {
        val store = MemoryStore()
        assertTrue(readVitalsHistory(store).isEmpty)
        store.setString(vitalsHistoryStorageKey, "das ist kein JSON")
        assertTrue(readVitalsHistory(store).isEmpty)
        clearVitalsHistory(store)
        assertNull(store.getString(vitalsHistoryStorageKey))
    }

    // -----------------------------------------------------------------------
    // Rueckweg in die VitalsSummary
    // -----------------------------------------------------------------------

    @Test
    fun `toSummary schneidet auf das Fenster zu und baut den Trend neu`() {
        val history = VitalsHistory(
            restingHeartRate = (0 until 40).map { DailyValue(day = day(it), value = 50.0) }
                .reversed(),
        )
        val summary = history.toSummary(now = today, days = 14)
        assertEquals(14, summary.restingHeartRate.series.size)
        assertEquals(14, summary.days)
        assertEquals(day(13), summary.from)
        assertEquals(50.0, summary.restingHeartRate.lastWeekAvg!!, 1e-9)
        assertEquals(50.0, summary.restingHeartRate.previousWeekAvg!!, 1e-9)
    }
}
