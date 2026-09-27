package de.trailscape.app.ui.rides

import de.trailscape.app.R
import de.trailscape.app.i18n.UiText
import de.trailscape.core.RideInfo
import de.trailscape.core.RideStats
import de.trailscape.core.i18n.AppLanguage
import de.trailscape.core.i18n.formatDateShort
import de.trailscape.core.i18n.formatDistanceKm
import de.trailscape.core.i18n.formatMonthName
import de.trailscape.core.riddenRides
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

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
 * Zeile „Di 23.9. · 32,4 km · 1:24 h" (englisch „Tue 23 Sept · 32.4 km ·
 * 1:24 h"). Monatsnamen und Datum kommen in der Sprache der Oberflaeche
 * ([AppLanguage], Muster aus `:core`/`DateFormats.kt`), Zeilen mit
 * uebersetzten Woertern als [UiText].
 *
 * Diese Klasse selbst ist eine Monatsgruppe: Ueberschrift plus Touren in
 * Listenreihenfolge.
 */
internal data class RideMonthGroup<T : RideInfo>(
    val month: YearMonth,
    val label: String,
    val rides: List<T>,
)

/**
 * Ueberschrift einer Monatsgruppe: im laufenden Jahr nur der Monat
 * („September"), sonst mit Jahr („September 2025") — sonst stuenden zwei
 * Septembers verschiedener Jahre ununterscheidbar untereinander. Monat vor
 * Jahr schreiben beide Sprachen gleich.
 */
internal fun monthLabel(month: YearMonth, today: LocalDate, language: AppLanguage): String {
    val name = formatMonthName(month.atDay(1), language)
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
    language: AppLanguage,
): List<RideMonthGroup<T>> {
    val byMonth = LinkedHashMap<YearMonth, MutableList<T>>()
    for (ride in rides) {
        val month = YearMonth.from(localOf(ride.createdAt))
        byMonth.getOrPut(month) { mutableListOf() }.add(ride)
    }
    return byMonth.map { (month, list) -> RideMonthGroup(month, monthLabel(month, today, language), list) }
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

/** Deutsche zweibuchstabige Wochentagskuerzel ohne Punkt — „Di", nicht „Di.". */
private val weekdayShortDe: Map<DayOfWeek, String> = mapOf(
    DayOfWeek.MONDAY to "Mo",
    DayOfWeek.TUESDAY to "Di",
    DayOfWeek.WEDNESDAY to "Mi",
    DayOfWeek.THURSDAY to "Do",
    DayOfWeek.FRIDAY to "Fr",
    DayOfWeek.SATURDAY to "Sa",
    DayOfWeek.SUNDAY to "So",
)

/** Englisch: Wochentag und Tag vor dem Monat, wie `d MMM` in `:core` („Tue 23 Sept"). */
private val shortDateEn: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM", AppLanguage.EN.locale)

/**
 * Kurzdatum der Listenzeile, z. B. `Di 23.9.` / `Tue 23 Sept`. Das Jahr steht
 * in der Monatsueberschrift darueber, nicht in jeder Zeile.
 */
internal fun formatRideShortDate(at: LocalDateTime, language: AppLanguage): String = when (language) {
    AppLanguage.DE -> "${weekdayShortDe.getValue(at.dayOfWeek)} ${at.dayOfMonth}.${at.monthValue}."
    AppLanguage.EN -> shortDateEn.format(at)
}

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
 * die Zeitangabe statt als „–" dazustehen — dafuer eine eigene Ressource,
 * statt Bruchstuecke aneinanderzuhaengen.
 */
internal fun rideListMeta(at: LocalDateTime, stats: RideStats, language: AppLanguage): UiText {
    val date = formatRideShortDate(at, language)
    val km = formatDistanceKm(stats.distanceKm, language)
    val duration = stats.durationS
        ?: return UiText.Res(R.string.rides_list_meta_no_duration, listOf(date, km))
    return UiText.Res(R.string.rides_list_meta, listOf(date, km, formatHoursMinutes(duration)))
}

/**
 * Was der gefahrene Teil des Verlaufs unter dem Abschnitt „Geplant" zeigt.
 *
 * Drei Faelle, weil ein leerer Teil zweierlei bedeuten kann: Es gibt noch gar
 * keine gefahrene Tour ([NOCH_KEINE_FAHRT] — auch waehrend einer Suche, denn
 * „keine gefahrene Tour heisst so" waere dann eine halbe Wahrheit), oder es
 * gibt welche, aber die Suche trifft keine ([KEIN_TREFFER]).
 */
internal enum class RiddenPart { LISTE, NOCH_KEINE_FAHRT, KEIN_TREFFER }

/**
 * Der Verlauf, aufgeteilt in gespeicherte Planungen und gefahrene Touren.
 *
 * @property planned Planungen in Listenreihenfolge (neueste zuerst) — sie
 *   stehen oben in einem eigenen Abschnitt, nicht nach Monaten gruppiert:
 *   Eine Planung hat kein Fahrtdatum, ihr Erstelldatum als Monat einzusortieren
 *   hiesse, sie zwischen echte Fahrten zu mischen.
 * @property months die gefahrenen Touren, wie bisher nach Monaten gruppiert.
 * @property ridden was der gefahrene Teil zeigt (siehe [RiddenPart]).
 * @property noMatch die Suche trifft weder eine Planung noch eine gefahrene
 *   Tour — dann steht nur ein Satz da, keine zwei halben Leermeldungen.
 */
internal data class HistorySections<T : RideInfo>(
    val planned: List<T>,
    val months: List<RideMonthGroup<T>>,
    val ridden: RiddenPart,
    val noMatch: Boolean,
)

/**
 * Teilt [rides] in den Abschnitt „Geplant" und die gefahrenen Touren.
 *
 * Bis hierher standen gespeicherte Planungen ([RideInfo.planned]) ohne
 * Kennzeichen zwischen den Fahrten, einsortiert nach ihrem Erstelldatum —
 * im Verlauf, der die Frage „Was bin ich gefahren?" beantwortet, sah eine
 * nie gefahrene Route aus wie eine Fahrt. Die Trennung laeuft ueber
 * [riddenRides], dieselbe eine Stelle, ueber die auch jede Statistik
 * „gefahren" von „geplant" unterscheidet; die Planungen sind genau der Rest.
 *
 * Die Suche ([filterRidesByName]) wirkt auf beide Teile gleich.
 */
internal fun <T : RideInfo> splitHistory(
    rides: List<T>,
    query: String,
    today: LocalDate,
    localOf: (Long) -> LocalDateTime,
    language: AppLanguage,
): HistorySections<T> {
    val ridden = riddenRides(rides)
    val planned = rides.filter { it.planned }

    val plannedHits = filterRidesByName(planned, query)
    val riddenHits = filterRidesByName(ridden, query)
    val months = groupRidesByMonth(riddenHits, today, localOf, language)

    val part = when {
        ridden.isEmpty() -> RiddenPart.NOCH_KEINE_FAHRT
        months.isEmpty() -> RiddenPart.KEIN_TREFFER
        else -> RiddenPart.LISTE
    }
    val noMatch = rides.isNotEmpty() && query.isNotBlank() && plannedHits.isEmpty() && riddenHits.isEmpty()
    return HistorySections(plannedHits, months, part, noMatch)
}

/**
 * Die Kennzahlen-Zeile einer gespeicherten Planung: `58 km · 640 Hm ·
 * erstellt 24.09.` — im Verlauf (Abschnitt „Geplant") und im hochgewischten
 * „Wohin?"-Blatt (Abschnitt „Gespeicherte Routen") dieselbe.
 *
 * Statt Dauer und Tempo, die es fuer eine nie gefahrene Route nicht gibt, steht
 * hier, was man beim Aussuchen einer Route wissen will: Laenge und Hoehenmeter.
 * Das Datum heisst ausdruecklich „erstellt", damit es nicht als Fahrtag
 * gelesen wird. Kilometer ganzzahlig wie die Hoehenmeter — eine Planung ist
 * eine Absicht, keine Messung; erst unter 10 km zaehlt die Nachkommastelle
 * wieder (sonst stuende eine 2,4-km-Runde als „2 km" da).
 *
 * Englisch: `58 km · 640 m · created 24 Sept` — Hoehenmeter in „m" wie
 * `common_value_elevation`.
 */
internal fun plannedRouteMeta(createdAt: LocalDateTime, stats: RideStats, language: AppLanguage): UiText {
    val km = if (stats.distanceKm < 10.0) {
        formatDistanceKm(stats.distanceKm, language)
    } else {
        "${stats.distanceKm.roundToInt()}"
    }
    return UiText.Res(
        R.string.rides_list_planned_meta,
        listOf(km, stats.ascentM.roundToInt(), formatDateShort(createdAt, language)),
    )
}

/**
 * Die bisherige, rein deutsche Fassung von [plannedRouteMeta] als fertiger
 * String — nur noch fuer das „Wohin?"-Blatt der Karte (`ui/map/ExploreSheet.kt`),
 * das dem Zweig i18n-map gehoert und dort auf die [UiText]-Fassung umzieht.
 * Danach kann sie weg.
 */
internal fun plannedRouteMeta(createdAt: LocalDateTime, stats: RideStats): String {
    val km = if (stats.distanceKm < 10.0) {
        formatDistanceKm(stats.distanceKm, AppLanguage.DE)
    } else {
        "${stats.distanceKm.roundToInt()}"
    }
    return "$km km · ${stats.ascentM.roundToInt()} Hm · erstellt ${formatDateShort(createdAt, AppLanguage.DE)}"
}

/**
 * Anzahl und Kilometer der **gefahrenen** Touren — die Zahlen unter der
 * Verlaufskarte (`HistorySummarySheet`). Planungen zaehlen nicht ([riddenRides]).
 */
internal data class HistoryTotals(val rideCount: Int, val totalKm: Double)

/** Siehe [HistoryTotals]. */
internal fun historyTotals(rides: List<RideInfo>): HistoryTotals {
    val ridden = riddenRides(rides)
    return HistoryTotals(ridden.size, ridden.sumOf { it.stats.distanceKm })
}
