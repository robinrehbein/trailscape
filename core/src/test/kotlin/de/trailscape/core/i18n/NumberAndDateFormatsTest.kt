package de.trailscape.core.i18n

import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals

class NumberAndDateFormatsTest {

    @Test
    fun `Dezimaltrennzeichen je Sprache`() {
        assertEquals("42,3", formatDecimal(42.3, 1, AppLanguage.DE))
        assertEquals("42.3", formatDecimal(42.3, 1, AppLanguage.EN))
        assertEquals("1234,50", formatDecimal(1234.5, 2, AppLanguage.DE))
        assertEquals("1234.50", formatDecimal(1234.5, 2, AppLanguage.EN))
        assertEquals("42,3", formatDistanceKm(42.25, AppLanguage.DE))
    }

    @Test
    fun `Rundung ist kaufmaennisch und ohne negative Null`() {
        assertEquals("2,5", formatDecimal(2.45, 1, AppLanguage.DE))
        assertEquals("3", formatDecimal(2.5, 0, AppLanguage.EN))
        assertEquals("0,0", formatDecimal(-0.04, 1, AppLanguage.DE))
        assertEquals("0", formatDecimal(-0.2, 0, AppLanguage.EN))
        assertEquals("-1.5", formatDecimal(-1.5, 1, AppLanguage.EN))
        assertEquals("–", formatDecimal(Double.NaN, 1, AppLanguage.DE))
    }

    @Test
    fun `Kilometer runden wie formatKm ueber die Dezimaldarstellung`() {
        // 12.35 und 0.15 liegen binaer knapp unter ,x5 — gerundet wird trotzdem auf.
        assertEquals("12,4", formatDistanceKm(12.35, AppLanguage.DE))
        assertEquals("0.2", formatDistanceKm(0.15, AppLanguage.EN))
        assertEquals(de.trailscape.core.formatKm(12.35), formatDistanceKm(12.35, AppLanguage.EN))
        assertEquals("0,0", formatDistanceKm(-0.04, AppLanguage.DE))
        assertEquals("–", formatDistanceKm(Double.NaN, AppLanguage.EN))
    }

    @Test
    fun `kompakte Zahl ohne ueberfluessige Nachkommastelle`() {
        assertEquals("5", formatCompactDecimal(5.0, AppLanguage.DE))
        assertEquals("4,5", formatCompactDecimal(4.47, AppLanguage.DE))
        assertEquals("4.5", formatCompactDecimal(4.47, AppLanguage.EN))
        assertEquals("5", formatCompactDecimal(4.96, AppLanguage.EN))
    }

    @Test
    fun `Datumsmuster auf Deutsch`() {
        val date = LocalDate.of(2026, 6, 5)
        assertEquals("05.06.2026", formatDateFull(date, AppLanguage.DE))
        assertEquals("05.06.", formatDateShort(date, AppLanguage.DE))
        assertEquals("Freitag, 5. Juni", formatWeekdayDate(date, AppLanguage.DE))
        assertEquals("Freitag, 5. Juni 2026", formatWeekdayDateYear(date, AppLanguage.DE))
        assertEquals("Juni", formatMonthName(date, AppLanguage.DE))
        assertEquals("07:05", formatTime(LocalTime.of(7, 5), AppLanguage.DE))
    }

    @Test
    fun `Datumsmuster auf Englisch`() {
        val date = LocalDate.of(2026, 6, 5)
        assertEquals("5 Jun 2026", formatDateFull(date, AppLanguage.EN))
        assertEquals("5 Jun", formatDateShort(date, AppLanguage.EN))
        assertEquals("Friday 5 June", formatWeekdayDate(date, AppLanguage.EN))
        assertEquals("Friday 5 June 2026", formatWeekdayDateYear(date, AppLanguage.EN))
        assertEquals("June", formatMonthName(date, AppLanguage.EN))
        assertEquals("19:30", formatTime(LocalTime.of(19, 30), AppLanguage.EN))
    }

    @Test
    fun `Wochentagscodes des Plans`() {
        assertEquals("Mo", CoreTextsDe.format.weekdayShort("Mo"))
        assertEquals("Sonntag", CoreTextsDe.format.weekdayLong("So"))
        assertEquals("Wed", CoreTextsEn.format.weekdayShort("Mi"))
        assertEquals("Thursday", CoreTextsEn.format.weekdayLong("Do"))
        assertEquals("??", CoreTextsEn.format.weekdayShort("??"))
    }

    @Test
    fun `Stunden und Rundenstunden`() {
        assertEquals("1½ h", CoreTextsDe.format.roundHours(1.5))
        assertEquals("½ h", CoreTextsEn.format.roundHours(0.5))
        assertEquals("2 h", CoreTextsEn.format.roundHours(2.0))
        assertEquals("2,3 h", CoreTextsDe.format.roundHours(2.3))
        assertEquals("2.3 h", CoreTextsEn.format.roundHours(2.3))
        assertEquals("4.5", CoreTextsEn.format.hours(4.47))
    }
}
