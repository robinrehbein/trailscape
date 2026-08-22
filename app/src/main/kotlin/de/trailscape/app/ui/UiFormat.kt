package de.trailscape.app.ui

import de.trailscape.core.formatKm
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * # Datums- und Zahlenformate der Oberflaeche — an EINER Stelle
 *
 * Vorher legte sich jeder Screen seinen eigenen [DateTimeFormatter] an
 * (`TourList`, `MapScreen`, `GoalCard`, `PlanSection`, `OfflineMapsCard`,
 * `HealthCard` — sechs Deklarationen fuer im Kern zwei Muster). Und die Zahlen
 * liefen auseinander: `formatKm` aus `:core` liefert `"42.3"` mit **Punkt**,
 * `formatHours`/`germanFixed` dagegen `"1,5"` mit **Komma** — in einer
 * durchgehend deutschen Oberflaeche standen damit beide Schreibweisen
 * nebeneinander.
 *
 * Diese Datei ist die gemeinsame Antwort darauf. `:core` bleibt unangetastet:
 * [formatKmDe] setzt lediglich das deutsche Dezimaltrennzeichen hinter das
 * dortige, getestete Rundungsverhalten.
 */

/** Vollstaendiges Datum, z. B. `14.03.2026`. */
val dateFormatFull: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.GERMANY)

/** Tag und Monat ohne Jahr, z. B. `14.03.` — fuer Wochenspannen im Plan. */
val dateFormatShort: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd.MM.", Locale.GERMANY)

/** Datum mit Uhrzeit, z. B. `14.03.2026, 09:41`. */
val dateTimeFormat: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm", Locale.GERMANY)

/**
 * Wochentag und Datum ausgeschrieben, z. B. `Donnerstag, 14. März` — das
 * einzige Format der App, das den Wochentag ausschreibt. Es steht im Kopf der
 * Startseite, wo eine Ziffernfolge zu behoerdlich klaenge.
 */
val weekdayDateFormat: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEEE, d. MMMM", Locale.GERMANY)

/**
 * Uhrzeit ohne Datum, z. B. `07:00` — fuer die eingestellten Weckzeiten der
 * Erinnerungen (`ui/more/ReminderCard.kt`). 24-Stunden-Form wie im uebrigen
 * Deutsch der App, unabhaengig von der Geraeteeinstellung: Die Zahl steht
 * neben deutschem Fliesstext und soll nicht mal mit, mal ohne „AM/PM"
 * auftauchen.
 */
val timeFormat: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm", Locale.GERMANY)

/** Formatiert eine Uhrzeit als `HH:mm`. */
fun formatTime(time: LocalTime): String = timeFormat.format(time)

/** Formatiert einen Epoch-Millisekunden-Zeitstempel als `dd.MM.yyyy`. */
fun formatDate(epochMs: Long): String = dateFormatFull.format(localOfEpochMs(epochMs))

/** Formatiert einen Epoch-Millisekunden-Zeitstempel als `dd.MM.`. */
fun formatDateShort(epochMs: Long): String = dateFormatShort.format(localOfEpochMs(epochMs))

/** Formatiert ein Datum als `dd.MM.yyyy`. */
fun formatDate(date: LocalDate): String = dateFormatFull.format(date)

/** Formatiert einen Zeitpunkt als `dd.MM.yyyy, HH:mm`. */
fun formatDateTime(at: LocalDateTime): String = dateTimeFormat.format(at)

/** Heutiges Datum als `dd.MM.yyyy` — fuer Vorschlagsnamen (Route, Offline-Region). */
fun formatToday(): String = formatDate(LocalDate.now())

/**
 * Deutsch formatierte Nachkommazahl (Punkt → Komma), kaufmaennisch gerundet —
 * Aequivalent zu Darts `value.toStringAsFixed(digits).replaceAll('.', ',')`.
 */
fun formatDecimalDe(value: Double, digits: Int): String =
    BigDecimal(value).setScale(digits, RoundingMode.HALF_UP).toPlainString().replace('.', ',')

/**
 * Kilometer mit einer Nachkommastelle und deutschem Dezimalkomma.
 *
 * Rundung und Stellenzahl kommen unveraendert aus [formatKm] (`:core`, dort
 * getestet); hier wird nur das Trennzeichen an die Sprache der Oberflaeche
 * angepasst, damit `42,3 km` und `1,5 h` nebeneinander stimmig aussehen.
 */
fun formatKmDe(km: Double): String = formatKm(km).replace('.', ',')

/** Eine Zahl mit einer Nachkommastelle, deutsch — z. B. Geschwindigkeit in km/h. */
fun formatOneDecimalDe(value: Double): String = formatDecimalDe(value, 1)

/**
 * Eine Dauer in Minuten als `h:mm` mit Stunden-Suffix — 390 → „6:30 h".
 *
 * Fuer Zielzeiten (Trainingsziel), nicht fuer Anzeigen von Stoppuhren: Die
 * Pläne der App zählen Zeit in Minuten, die Oberfläche aber spricht von
 * „6:30 h", wenn eine Ambition gemeint ist.
 */
fun formatDurationHm(minutes: Int): String =
    "${minutes / 60}:${(minutes % 60).toString().padStart(2, '0')} h"

/**
 * Eine Dateigroesse in der groessten passenden Einheit, deutsch —
 * `119,4 MB`, `1,3 GB`, `640 KB`.
 *
 * Stand frueher als `formatOfflineRegionSize` privat in `OfflineMapsCard.kt`.
 * Seit die Offline-Routingdaten (`OfflineRoutingCard.kt`) ebenfalls Groessen
 * anzeigen, gaebe es sonst zwei Formatierer fuer dieselbe Frage — und zwei
 * Gelegenheiten, dass die App an einer Stelle „119.4 MB" und an der anderen
 * „119,4 MB" schreibt.
 *
 * **1024er-Schritte** wie bisher (also MiB/GiB, benannt als MB/GB): Das ist
 * die Rechnung, mit der auch Android in seinen Speichereinstellungen arbeitet,
 * und die Zahl soll neben der des Systems nicht abweichen.
 *
 * `null` oder Werte `<= 0` ergeben „Größe unbekannt" — die ehrliche Auskunft
 * dort, wo eine Groesse nicht zu ermitteln war.
 */
fun formatBytes(bytes: Long?): String {
    if (bytes == null || bytes <= 0L) return "Größe unbekannt"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1 -> String.format(Locale.GERMANY, "%.1f GB", gb)
        mb >= 1 -> String.format(Locale.GERMANY, "%.1f MB", mb)
        else -> String.format(Locale.GERMANY, "%.0f KB", kb)
    }
}
