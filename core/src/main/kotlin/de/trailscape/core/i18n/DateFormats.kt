package de.trailscape.core.i18n

import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAccessor

/**
 * Datumsformate je Sprache — an EINER Stelle festgelegt und getestet.
 *
 * | Name              | Deutsch                  | Englisch (UK)          |
 * |-------------------|--------------------------|------------------------|
 * | fullDate          | 05.06.2026               | 5 Jun 2026             |
 * | shortDate         | 05.06.                   | 5 Jun                  |
 * | weekdayDate       | Freitag, 5. Juni         | Friday 5 June          |
 * | weekdayDateYear   | Freitag, 5. Juni 2026    | Friday 5 June 2026     |
 * | monthName         | Juni                     | June                   |
 * | time              | 07:00                    | 07:00                  |
 *
 * Die Uhrzeit ist in beiden Sprachen 24-stuendig: Sie steht neben
 * Fliesstext und soll nicht mal mit, mal ohne „AM/PM" auftauchen.
 */
object DateFormats {
    private fun pattern(pattern: String, language: AppLanguage): DateTimeFormatter =
        DateTimeFormatter.ofPattern(pattern, language.locale)

    private val fullDe = pattern("dd.MM.yyyy", AppLanguage.DE)
    private val fullEn = pattern("d MMM yyyy", AppLanguage.EN)
    private val shortDe = pattern("dd.MM.", AppLanguage.DE)
    private val shortEn = pattern("d MMM", AppLanguage.EN)
    private val weekdayDe = pattern("EEEE, d. MMMM", AppLanguage.DE)
    private val weekdayEn = pattern("EEEE d MMMM", AppLanguage.EN)
    private val weekdayYearDe = pattern("EEEE, d. MMMM yyyy", AppLanguage.DE)
    private val weekdayYearEn = pattern("EEEE d MMMM yyyy", AppLanguage.EN)
    private val monthDe = pattern("LLLL", AppLanguage.DE)
    private val monthEn = pattern("LLLL", AppLanguage.EN)
    private val timeBoth = DateTimeFormatter.ofPattern("HH:mm")

    fun fullDate(language: AppLanguage): DateTimeFormatter =
        if (language == AppLanguage.DE) fullDe else fullEn

    fun shortDate(language: AppLanguage): DateTimeFormatter =
        if (language == AppLanguage.DE) shortDe else shortEn

    fun weekdayDate(language: AppLanguage): DateTimeFormatter =
        if (language == AppLanguage.DE) weekdayDe else weekdayEn

    fun weekdayDateYear(language: AppLanguage): DateTimeFormatter =
        if (language == AppLanguage.DE) weekdayYearDe else weekdayYearEn

    fun monthName(language: AppLanguage): DateTimeFormatter =
        if (language == AppLanguage.DE) monthDe else monthEn

    @Suppress("UNUSED_PARAMETER")
    fun time(language: AppLanguage): DateTimeFormatter = timeBoth
}

/** „05.06.2026" / „5 Jun 2026". */
fun formatDateFull(date: TemporalAccessor, language: AppLanguage): String =
    DateFormats.fullDate(language).format(date)

/** „05.06." / „5 Jun". */
fun formatDateShort(date: TemporalAccessor, language: AppLanguage): String =
    DateFormats.shortDate(language).format(date)

/** „Freitag, 5. Juni" / „Friday 5 June". */
fun formatWeekdayDate(date: TemporalAccessor, language: AppLanguage): String =
    DateFormats.weekdayDate(language).format(date)

/** „Freitag, 5. Juni 2026" / „Friday 5 June 2026". */
fun formatWeekdayDateYear(date: TemporalAccessor, language: AppLanguage): String =
    DateFormats.weekdayDateYear(language).format(date)

/** „Juni" / „June" (Nominativ, alleinstehend). */
fun formatMonthName(date: TemporalAccessor, language: AppLanguage): String =
    DateFormats.monthName(language).format(date)

/** „07:00" in beiden Sprachen. */
fun formatTime(time: TemporalAccessor, language: AppLanguage): String =
    DateFormats.time(language).format(time)
