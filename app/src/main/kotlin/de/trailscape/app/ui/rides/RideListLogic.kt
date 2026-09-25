package de.trailscape.app.ui.rides

import de.trailscape.app.ui.formatKmDe
import de.trailscape.core.RideInfo
import de.trailscape.core.RideStats
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * # Die reine Rechnung hinter der Verlaufsliste
 *
 * Alles, was die Liste im Verlauf-Tab zeigt, ohne dafuer Compose zu brauchen:
 * die Gruppierung nach Monaten samt Ueberschrift, die gedaempfte Kennzahlen-
 * Zeile einer Tour und der Namensfilter der Suche. Getrennt vom Zeichnen, damit
 * diese Regeln als JVM-Test pruefbar sind (`RideListLogicTest`).
 *
 * Zieldesign `docs/design/prototyp-klartext.html`, Screen `#s-verlauf`: Monats-
 * Augenbrauen („September", „August") ueber je einer Gruppen-Karte, in der
 * Zeile „Di 23.9. · 32,4 km · 1:24 h".
 *
 * Diese Klasse selbst ist eine Monatsgruppe: Ueberschrift plus Touren in
 * Listenreihenfolge.
 */
internal data class RideMonthGroup<T : RideInfo>(
    val month: YearMonth,
    val label: String,
    val rides: List<T>,
)

/** Monat ausgeschrieben, z. B. `September`. */
private val monthFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("LLLL", Locale.GERMANY)

/**
 * Ueberschrift einer Monatsgruppe: im laufenden Jahr nur der Monat
 * („September"), sonst mit Jahr („September 2025") — sonst stuenden zwei
 * Septembers verschiedener Jahre ununterscheidbar untereinander.
 */
internal fun monthLabel(month: YearMonth, today: LocalDate): String {
    val name = monthFormat.format(month)
    return if (month.year == today.year) name else "$name ${month.year}"
}

/**
 * Gruppiert [rides] nach Kalendermonat (lokale Zeit, ueber [localOf]).
 *
 * Die Reihenfolge der Eingabe bleibt erhalten — innerhalb der Gruppen wie
 * zwischen ihnen. Die Tourenliste des ViewModels ist bereits absteigend nach
 * Startzeit sortiert, eine zweite Sortierung hier waere eine zweite Wahrheit.
 * Folgen zwei Touren desselben Monats nicht aufeinander (kann nur bei
 * unsortierter Eingabe passieren), landen sie trotzdem in **einer** Gruppe.
 */
internal fun <T : RideInfo> groupRidesByMonth(
    rides: List<T>,
    today: LocalDate,
    localOf: (Long) -> LocalDateTime,
): List<RideMonthGroup<T>> {
    val byMonth = LinkedHashMap<YearMonth, MutableList<T>>()
    for (ride in rides) {
        val month = YearMonth.from(localOf(ride.createdAt))
        byMonth.getOrPut(month) { mutableListOf() }.add(ride)
    }
    return byMonth.map { (month, list) -> RideMonthGroup(month, monthLabel(month, today), list) }
}

/**
 * Filtert nach Namen — Gross-/Kleinschreibung egal, Teilwort genuegt. Eine
 * leere (oder nur aus Leerzeichen bestehende) Suche laesst alles durch.
 */
internal fun <T : RideInfo> filterRidesByName(rides: List<T>, query: String): List<T> {
    val needle = query.trim()
    if (needle.isEmpty()) return rides
    return rides.filter { it.name.contains(needle, ignoreCase = true) }
}

/** Zweibuchstabige Wochentagskuerzel ohne Punkt — „Di", nicht „Di.". */
private val weekdayShort: Map<DayOfWeek, String> = mapOf(
    DayOfWeek.MONDAY to "Mo",
    DayOfWeek.TUESDAY to "Di",
    DayOfWeek.WEDNESDAY to "Mi",
    DayOfWeek.THURSDAY to "Do",
    DayOfWeek.FRIDAY to "Fr",
    DayOfWeek.SATURDAY to "Sa",
    DayOfWeek.SUNDAY to "So",
)

/**
 * Kurzdatum der Listenzeile, z. B. `Di 23.9.`. Das Jahr steht in der
 * Monatsueberschrift darueber, nicht in jeder Zeile.
 */
internal fun formatRideShortDate(at: LocalDateTime): String =
    "${weekdayShort.getValue(at.dayOfWeek)} ${at.dayOfMonth}.${at.monthValue}."

/**
 * Dauer als Stunden und Minuten, z. B. `1:24` — die Form, die das Label
 * „Std." verspricht. `formatDuration` aus `:core` liefert `h:mm:ss`, was
 * unter einem Label „h:min" schlicht falsch war. Sekunden werden
 * abgeschnitten, nicht gerundet: 1:24:59 ist noch 1:24. `null` bleibt „–".
 */
internal fun formatHoursMinutes(seconds: Int?): String {
    if (seconds == null) return "–"
    val total = seconds.coerceAtLeast(0)
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    return "$hours:${minutes.toString().padStart(2, '0')}"
}

/**
 * Die gedaempfte Kennzahlen-Zeile einer Listenzeile: `Di 23.9. · 32,4 km ·
 * 1:24 h`. Bewusst nur drei Angaben wie im Zieldesign; Hoehenmeter und Puls
 * stehen gross in der Detailansicht. Ohne Dauer (manche Importe) entfaellt
 * die Zeitangabe statt als „–" dazustehen.
 */
internal fun rideListMeta(at: LocalDateTime, stats: RideStats): String = buildList {
    add(formatRideShortDate(at))
    add("${formatKmDe(stats.distanceKm)} km")
    stats.durationS?.let { add("${formatHoursMinutes(it)} h") }
}.joinToString(" · ")
