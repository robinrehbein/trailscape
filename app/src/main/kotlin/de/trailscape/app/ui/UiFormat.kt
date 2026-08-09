package de.trailscape.app.ui

import de.trailscape.core.formatKm
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * # Datums- und Zahlenformate der Oberflaeche — an EINER Stelle
 *
 * Vorher legte sich jeder Screen seinen eigenen [DateTimeFormatter] an
 * (`RidesScreen`, `MapScreen`, `GoalCard`, `PlanSection`, `OfflineMapsCard`,
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
