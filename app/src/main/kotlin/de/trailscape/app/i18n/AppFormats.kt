package de.trailscape.app.i18n

import de.trailscape.core.i18n.AppLanguage
import de.trailscape.core.i18n.formatDateFull
import de.trailscape.core.i18n.formatDateShort
import de.trailscape.core.i18n.formatDecimal
import de.trailscape.core.i18n.formatDistanceKm
import de.trailscape.core.i18n.formatMonthName
import de.trailscape.core.i18n.formatTime
import de.trailscape.core.i18n.formatWeekdayDate
import de.trailscape.core.i18n.formatWeekdayDateYear
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Zahl- und Datumsformate der Oberflaeche in einer Sprache.
 *
 * Der neue Weg fuer alle Bereiche (im Composable ueber [LocalAppFormats]).
 * Die Muster selbst stehen getestet in `:core` (`core/i18n/DateFormats.kt`,
 * `NumberFormats.kt`); hier nur die bequemen Einstiege fuer die App-Typen.
 * Die alten deutschen Helfer in `ui/UiFormat.kt` bleiben, bis alle Bereiche
 * umgezogen sind (siehe `docs/i18n.md`).
 */
class AppFormats(val language: AppLanguage) {
    /** Kilometer mit einer Nachkommastelle, ohne Einheit: „42,3" / „42.3". */
    fun km(km: Double): String = formatDistanceKm(km, language)

    /** Dezimalzahl mit [digits] Stellen im Format der Sprache. */
    fun decimal(value: Double, digits: Int): String = formatDecimal(value, digits, language)

    /** „05.06.2026" / „5 Jun 2026". */
    fun dateFull(date: LocalDate): String = formatDateFull(date, language)

    /** Wie [dateFull] fuer einen Epoch-Millisekunden-Zeitstempel (lokale Zeitzone). */
    fun dateFull(epochMs: Long): String = formatDateFull(local(epochMs), language)

    /** „05.06." / „5 Jun". */
    fun dateShort(date: LocalDate): String = formatDateShort(date, language)

    /** Wie [dateShort] fuer einen Epoch-Millisekunden-Zeitstempel. */
    fun dateShort(epochMs: Long): String = formatDateShort(local(epochMs), language)

    /** „Freitag, 5. Juni" / „Friday 5 June". */
    fun weekdayDate(date: LocalDate): String = formatWeekdayDate(date, language)

    /** „Freitag, 5. Juni 2026" / „Friday 5 June 2026". */
    fun weekdayDateYear(date: LocalDate): String = formatWeekdayDateYear(date, language)

    /** „Juni" / „June". */
    fun monthName(date: LocalDate): String = formatMonthName(date, language)

    /** „07:00" in beiden Sprachen. */
    fun time(time: LocalTime): String = formatTime(time, language)

    /** Wie [time] fuer einen Zeitpunkt. */
    fun time(at: LocalDateTime): String = formatTime(at, language)

    /**
     * Eine Dateigroesse in der groessten passenden Einheit („119,4 MB",
     * „1.3 GB", „640 KB"), 1024er-Schritte wie in den Android-Einstellungen.
     * `null` bei unbekannter Groesse (`null` oder ≤ 0) — den Text dafuer
     * liefert der jeweilige Bereich selbst.
     */
    fun bytes(bytes: Long?): String? {
        if (bytes == null || bytes <= 0L) return null
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1 -> "${decimal(gb, 1)} GB"
            mb >= 1 -> "${decimal(mb, 1)} MB"
            else -> "${decimal(kb, 0)} KB"
        }
    }

    private fun local(epochMs: Long): LocalDateTime =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault())
}
