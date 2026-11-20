package de.trailscape.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * # Wochen-Fahrplan — „Wann fahre ich den Rest meines Wochenziels?"
 *
 * Das mitwachsende Wochenziel (`WeeklyGoal.kt`) sagt *wie viel*, aber nicht
 * *wann*. Diese Datei schlaegt fuer die restlichen Tage der Woche ein bis drei
 * (notfalls fuenf) Fahrten vor — bevorzugt an Tagen, an denen die Nutzerin ohnehin faehrt, und
 * an Tagen mit gutem Wetter, wenn eine Vorhersage vorliegt.
 *
 * Reine Rechnung wie `Motivation.kt`: kein Android, keine Uhr, keine
 * Zeitzone; „jetzt" kommt als Parameter. Gruende sind ein
 * [WeekPlanReason] — Saetze baut die Oberflaeche.
 *
 * ## Die Regeln
 *  1. **Kein Plan**, wenn das Wochenziel geschafft ist oder ein Trainingsplan
 *     laeuft — der Plan hat seine eigenen Einheiten und Vorrang.
 *  2. **Tage**: ab heute bis Sonntag (Wochenbeginn Montag wie ueberall).
 *     Heute nur bis [WEEK_PLAN_LAST_HOUR_TODAY] Uhr und nur, wenn heute noch
 *     nicht gefahren wurde.
 *  3. **Anzahl**: so viele Fahrten in der typischen Laenge, wie es fuer den
 *     Rest braucht — ueblicherweise hoechstens [WEEK_PLAN_MAX_RIDES],
 *     hoechstens so viele wie Tage bleiben, und keine Fahrt unter
 *     [WEEK_PLAN_MIN_RIDE_KM] (auch nicht die einzige: Ein Rest von 7 km wird
 *     eine Runde ueber 10 km). Unter [WEEK_PLAN_MIN_REMAINING_KM] Rest gibt es
 *     keinen Plan — das ist keine eigene Runde mehr, und die Zeile unter dem
 *     Balken nennt den Rest ohnehin.
 *  4. **Obergrenze**: keine Fahrt laenger als das [WEEK_PLAN_MAX_FACTOR]-Fache
 *     der laengsten Fahrt der letzten [WEEK_PLAN_HISTORY_WEEKS] Wochen. Reichen
 *     drei Fahrten bis zur Obergrenze nicht, sind aber noch Tage frei, werden
 *     es bis zu [WEEK_PLAN_MAX_RIDES_TIGHT] Fahrten — mehr Fahrten statt einer
 *     Ueberforderung. Reicht auch das nicht, heisst der Plan
 *     [WeekPlanStatus.KNAPP] und nennt den Rest samt Grund ([WeekPlanTightCause]).
 *  5. **Tagwahl**: Punkte fuer den ueblichen Fahrtag, Abzug fuer Regen, Sturm,
 *     Kaelte und Hitze. Schlechtes Wetter wird gemieden, aber nie verboten —
 *     bleibt nur ein verregneter Tag, steht er mit [WeekPlanReason.REGEN_MOEGLICH]
 *     im Plan.
 */

/** Wie weit die Historie zurueckreicht: lang genug fuer ein Muster, kurz genug fuer die aktuelle Form. */
const val WEEK_PLAN_HISTORY_WEEKS: Int = 8

/** Mehr Fahrten schlaegt der Plan normalerweise nicht vor — eine Woche ist kein Trainingsplan. */
const val WEEK_PLAN_MAX_RIDES: Int = 3

/**
 * So viele Fahrten werden es hoechstens, wenn [WEEK_PLAN_MAX_RIDES] Fahrten bis
 * zur Obergrenze nicht reichen und noch Tage frei sind. Fuenf Fahrten in einer
 * Woche sind viel, aber machbar — und ehrlicher als „knapp" bei freien Tagen.
 */
const val WEEK_PLAN_MAX_RIDES_TIGHT: Int = 5

/** Keine Fahrt laenger als dieses Vielfache der laengsten Fahrt der letzten Wochen. */
const val WEEK_PLAN_MAX_FACTOR: Double = 1.5

/** Ab dieser Stunde wird fuer heute nichts mehr vorgeschlagen. */
const val WEEK_PLAN_LAST_HOUR_TODAY: Int = 18

/**
 * Kuerzer lohnt das Aufsatteln nicht — dann lieber eine Fahrt weniger, und
 * eine einzelne Fahrt wird auf diese Laenge aufgerundet. Der Rundkurs-Generator
 * plant ab [minRouteTargetKm] ohne Sonderbehandlung (darunter hebt er das Ziel
 * mit Hinweis an); 10 km liegen sicher darueber und sind dieselbe Untergrenze
 * wie bei der ersten Runde ([FIRST_ROUND_MIN_KM]).
 */
const val WEEK_PLAN_MIN_RIDE_KM: Int = 10

/**
 * Unter diesem Rest gibt es keinen Plan: Fuer weniger als die kleinste Runde,
 * die der Rundkurs-Generator ueberhaupt plant ([minRouteTargetKm]), lohnt
 * keine eigene Karte.
 */
const val WEEK_PLAN_MIN_REMAINING_KM: Double = minRouteTargetKm

/** Typische Fahrt ohne jede Historie — dieselbe Groessenordnung wie die erste Runde. */
const val WEEK_PLAN_DEFAULT_RIDE_KM: Double = 25.0

/** Ab diesem Anteil der Wochen gilt ein Wochentag als „dein ueblicher Fahrtag". */
const val WEEK_PLAN_USUAL_DAY_SHARE: Double = 0.4

/** Unter so vielen Fahrten im Fenster ist ein Median ein Zufall; dann zaehlen die letzten Fahrten ueberhaupt. */
private const val MIN_RIDES_FOR_WINDOW = 3

/** So viele juengste Fahrten ersetzen ein zu duennes Fenster. */
private const val FALLBACK_RECENT_RIDES = 5

/**
 * Die Fahrgewohnheiten aus der Historie.
 *
 * @property weekdayShare je Wochentag der Anteil der beobachteten Wochen mit
 *   einer Fahrt an diesem Tag (0…1). Fehlende Tage zaehlen 0.
 * @property typicalRideKm Median der Fahrtlaengen; `null` ohne jede Fahrt.
 * @property longestRideKm laengste Fahrt der letzten [WEEK_PLAN_HISTORY_WEEKS]
 *   Wochen (bei zu wenig Fahrten: der juengsten Fahrten); `null` ohne Fahrt.
 * @property ridesInWindow Fahrten im Fenster — wie belastbar das Muster ist.
 */
data class RidingHabits(
    val weekdayShare: Map<DayOfWeek, Double>,
    val typicalRideKm: Double?,
    val longestRideKm: Double?,
    val ridesInWindow: Int,
) {
    fun shareOf(day: DayOfWeek): Double = weekdayShare[day] ?: 0.0

    companion object {
        /** Keine Historie: kein Muster, Laengen aus den Vorgaben. */
        val NONE = RidingHabits(emptyMap(), null, null, 0)
    }
}

private fun median(values: List<Double>): Double {
    val sorted = values.sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
}

/**
 * Die Gewohnheiten aus den Fahrten bis einschliesslich [today].
 *
 * Fenster sind die letzten [WEEK_PLAN_HISTORY_WEEKS] Wochen (56 Tage bis
 * heute). Die Wochentags-Anteile beziehen sich nur auf die Wochen, seit es
 * ueberhaupt Fahrten gibt — wer erst seit zwei Wochen faehrt und beide Male
 * samstags, hat den Samstag zu 100 %, nicht zu 25 %. Mehrere Fahrten an einem
 * Tag zaehlen fuer den Wochentag einmal. Fahrten nach [today] und solche ohne
 * Strecke zaehlen nicht.
 */
fun ridingHabits(facts: List<RideFact>, today: LocalDate): RidingHabits {
    val ridden = facts.filter { !it.date.isAfter(today) && it.distanceKm > 0.0 }
    if (ridden.isEmpty()) return RidingHabits.NONE
    val windowStart = today.minusDays(WEEK_PLAN_HISTORY_WEEKS * 7L - 1)
    val window = ridden.filter { !it.date.isBefore(windowStart) }

    val firstDate = ridden.minOf { it.date }
    val observedFrom = if (firstDate.isAfter(windowStart)) firstDate else windowStart
    val observedDays = ChronoUnit.DAYS.between(observedFrom, today) + 1
    val observedWeeks = ceil(observedDays / 7.0).toInt().coerceIn(1, WEEK_PLAN_HISTORY_WEEKS)
    val share = window.map { it.date }.distinct()
        .groupingBy { it.dayOfWeek }.eachCount()
        .mapValues { (_, count) -> min(1.0, count.toDouble() / observedWeeks) }

    // Zu duenn besetztes Fenster: die juengsten Fahrten ueberhaupt, damit eine
    // einzelne kurze Runde nicht die Obergrenze fuer die ganze Woche setzt.
    val basis = if (window.size >= MIN_RIDES_FOR_WINDOW) {
        window
    } else {
        ridden.sortedBy { it.date }.takeLast(max(FALLBACK_RECENT_RIDES, window.size))
    }
    val distances = basis.map { it.distanceKm }
    return RidingHabits(
        weekdayShare = share,
        typicalRideKm = median(distances),
        longestRideKm = distances.max(),
        ridesInWindow = window.size,
    )
}

/** Warum ein Tag im Plan steht — ein Wort fuer die Oberflaeche, kein Satz. */
enum class WeekPlanReason {
    /** Vorhersage trocken. */
    TROCKEN,

    /** Nicht ganz trocken, aber kaum Wind. */
    WENIG_WIND,

    /** An diesem Wochentag faehrt sie meistens. */
    UEBLICHER_FAHRTAG,

    /** Kein besonderer Grund — ein freier Tag, der zum Rest passt. */
    FREIER_TAG,

    /** Regen ist angesagt; der Tag steht trotzdem drin, weil es sonst nicht reicht. */
    REGEN_MOEGLICH,

    /** Viel Wind angesagt. */
    WINDIG,

    /** Sehr kalt angesagt. */
    KALT,

    /** Sehr heiss angesagt. */
    HEISS,
}

/** Eine vorgeschlagene Fahrt. [forecast] nur, wenn fuer den Tag eine Vorhersage vorlag. */
data class PlannedRide(
    val date: LocalDate,
    val km: Int,
    val reason: WeekPlanReason,
    val forecast: DayForecast? = null,
)

enum class WeekPlanStatus {
    /** Der Rest ist mit den Vorschlaegen geschafft. */
    MACHBAR,

    /**
     * Es wird knapp: Selbst mit den laengsten vertretbaren Fahrten bleibt ein
     * Rest ([WeekPlan.shortfallKm]). Die Vorschlaege sind trotzdem da — so weit
     * wie vernuenftig.
     */
    KNAPP,

    /** Diese Woche bleibt kein Tag mehr (Sonntagabend, oder heute schon gefahren am Sonntag). */
    KEINE_TAGE,
}

/** Warum ein Plan [WeekPlanStatus.KNAPP] ist — die Oberflaeche sagt es dazu. */
enum class WeekPlanTightCause {
    /**
     * Es bleiben weniger Tage, als es Fahrten bis zur Obergrenze braucht —
     * jeder verbleibende Tag hat schon eine Fahrt.
     */
    ZU_WENIGE_TAGE,

    /**
     * Tage waeren frei, aber selbst [WEEK_PLAN_MAX_RIDES_TIGHT] Fahrten bis zur
     * Obergrenze reichen nicht.
     */
    OBERGRENZE,
}

/**
 * Der Wochen-Fahrplan.
 *
 * @property rides ein bis drei (notfalls fuenf) Fahrten nach Datum; leer nur bei [WeekPlanStatus.KEINE_TAGE].
 * @property remainingKm der Rest zum Wochenziel, auf ganze km aufgerundet.
 * @property shortfallKm was nach den Vorschlaegen noch fehlt (0 ausser bei [WeekPlanStatus.KNAPP]
 *   und [WeekPlanStatus.KEINE_TAGE]).
 * @property maxRideKm die Obergrenze je Fahrt (Regel 4).
 * @property withWeather ob fuer mindestens einen der moeglichen Tage eine Vorhersage vorlag.
 * @property tightCause warum es knapp ist; `null` ausser bei [WeekPlanStatus.KNAPP].
 */
data class WeekPlan(
    val rides: List<PlannedRide>,
    val status: WeekPlanStatus,
    val remainingKm: Int,
    val shortfallKm: Int,
    val maxRideKm: Int,
    val withWeather: Boolean,
    val tightCause: WeekPlanTightCause? = null,
)

/**
 * Die Eingaben von [planWeek].
 *
 * @property remainingKm Rest zum Wochenziel in km (≤ 0: geschafft).
 * @property now jetzt, in der Zeitzone der Nutzerin.
 * @property habits aus [ridingHabits].
 * @property forecast Tagesvorhersagen, beliebige Tage; Tage ausserhalb der Woche werden ignoriert.
 * @property trainingPlanActive ein Trainingsplan regelt diese Woche.
 * @property rodeToday heute wurde schon gefahren — dann zaehlt heute nicht mehr als Fahrtag.
 */
data class WeekPlanInput(
    val remainingKm: Double,
    val now: LocalDateTime,
    val habits: RidingHabits,
    val forecast: List<DayForecast> = emptyList(),
    val trainingPlanActive: Boolean = false,
    val rodeToday: Boolean = false,
)

/**
 * Die Tage, an denen diese Woche noch gefahren werden kann: heute (vor
 * [WEEK_PLAN_LAST_HOUR_TODAY] Uhr und nur ohne heutige Fahrt) bis Sonntag.
 */
fun remainingWeekDays(now: LocalDateTime, rodeToday: Boolean = false): List<LocalDate> {
    val today = now.toLocalDate()
    val sunday = today.with(DayOfWeek.SUNDAY)
    val first = if (now.hour < WEEK_PLAN_LAST_HOUR_TODAY && !rodeToday) today else today.plusDays(1)
    return generateSequence(first) { it.plusDays(1) }.takeWhile { !it.isAfter(sunday) }.toList()
}

/** Abzuege fuer das Wetter eines Tages, getrennt nach Ursache (0 = kein Abzug). */
internal data class WeatherPenalty(val rain: Double, val wind: Double, val cold: Double, val heat: Double) {
    val total: Double get() = rain + wind + cold + heat
}

/**
 * Wie unangenehm das Wetter eines Tages fuers Rad ist. Fehlende Werte kosten
 * nichts — ohne Angabe wird nichts unterstellt.
 *
 * Schwellen: Regen ab 70 % oder 5 mm ist ein Regentag, ab 40 % oder 1,5 mm
 * wahrscheinlich nass, ab 25 % oder 0,5 mm ein kleines Risiko. Wind ab
 * 40 km/h Mittelwind ist Sturm auf dem Rad, ab 30 km/h anstrengend.
 * Hoechstwert unter 2 °C ist Glaettegefahr, ab 33 °C Hitze.
 */
internal fun weatherPenalty(f: DayForecast): WeatherPenalty {
    val p = f.precipProbabilityPct ?: 0
    val mm = f.precipMm ?: 0.0
    val rain = when {
        p >= 70 || mm >= 5.0 -> 1.0
        p >= 40 || mm >= 1.5 -> 0.5
        p >= 25 || mm >= 0.5 -> 0.15
        else -> 0.0
    }
    val windKmh = f.windMaxKmh ?: 0.0
    val wind = when {
        windKmh >= 40 -> 0.8
        windKmh >= 30 -> 0.4
        windKmh >= 22 -> 0.15
        else -> 0.0
    }
    val tMax = f.tempMaxC
    val cold = when {
        tMax == null -> 0.0
        tMax < 2 -> 0.5
        tMax < 7 -> 0.15
        else -> 0.0
    }
    val heat = when {
        tMax == null -> 0.0
        tMax >= 33 -> 0.4
        tMax >= 29 -> 0.1
        else -> 0.0
    }
    return WeatherPenalty(rain, wind, cold, heat)
}

/** Trocken heisst: Wahrscheinlichkeit oder Menge bekannt, und beide unauffaellig. */
private fun isDry(f: DayForecast): Boolean =
    (f.precipProbabilityPct != null || f.precipMm != null) &&
        (f.precipProbabilityPct ?: 0) < 25 && (f.precipMm ?: 0.0) < 0.5

private fun reasonFor(forecast: DayForecast?, share: Double): WeekPlanReason {
    val usual = share >= WEEK_PLAN_USUAL_DAY_SHARE
    if (forecast == null) return if (usual) WeekPlanReason.UEBLICHER_FAHRTAG else WeekPlanReason.FREIER_TAG
    val penalty = weatherPenalty(forecast)
    return when {
        penalty.rain >= 0.5 -> WeekPlanReason.REGEN_MOEGLICH
        penalty.wind >= 0.4 -> WeekPlanReason.WINDIG
        penalty.cold >= 0.5 -> WeekPlanReason.KALT
        penalty.heat >= 0.4 -> WeekPlanReason.HEISS
        isDry(forecast) -> WeekPlanReason.TROCKEN
        forecast.windMaxKmh != null && forecast.windMaxKmh < 15.0 -> WeekPlanReason.WENIG_WIND
        usual -> WeekPlanReason.UEBLICHER_FAHRTAG
        else -> WeekPlanReason.FREIER_TAG
    }
}

/** Wie stark das Wetter gegen den ueblichen Fahrtag wiegt: ein Regentag schlaegt jede Gewohnheit. */
private const val WEATHER_WEIGHT = 1.5

/** Abzug fuer einen Tag direkt neben einem schon gewaehlten — Erholung zwischen zwei Fahrten. */
private const val ADJACENT_PENALTY = 0.3

/** Kleiner Vorzug fuer fruehe Tage: Wer frueh faehrt, hat noch Luft, falls etwas dazwischenkommt. */
private const val EARLY_BONUS_PER_DAY = 0.02

/**
 * Der Wochen-Fahrplan, oder `null`, wenn es keinen braucht: Ziel geschafft
 * oder fast (Rest unter [WEEK_PLAN_MIN_REMAINING_KM]) oder Trainingsplan
 * aktiv. Regeln siehe Datei-KDoc.
 */
fun planWeek(input: WeekPlanInput): WeekPlan? {
    if (input.trainingPlanActive) return null
    if (!input.remainingKm.isFinite() || input.remainingKm < WEEK_PLAN_MIN_REMAINING_KM) return null
    val remaining = ceil(input.remainingKm - 1e-9).toInt()

    val habits = input.habits
    val typical = (habits.typicalRideKm ?: WEEK_PLAN_DEFAULT_RIDE_KM).coerceAtLeast(WEEK_PLAN_MIN_RIDE_KM.toDouble())
    val longest = habits.longestRideKm ?: typical
    val cap = max(WEEK_PLAN_MIN_RIDE_KM, floor(longest * WEEK_PLAN_MAX_FACTOR + 1e-9).toInt())

    val days = remainingWeekDays(input.now, input.rodeToday)
    val forecastByDate = input.forecast.associateBy { it.date }
    val withWeather = days.any { forecastByDate.containsKey(it) }
    if (days.isEmpty()) {
        return WeekPlan(emptyList(), WeekPlanStatus.KEINE_TAGE, remaining, remaining, cap, withWeather)
    }

    // Anzahl: typische Laenge (hoechstens drei), dann so lange mehr, bis die
    // Obergrenze passt — notfalls bis zu fuenf Fahrten (Regel 4).
    var count = ceil(remaining / typical - 1e-9).toInt().coerceIn(1, WEEK_PLAN_MAX_RIDES)
    while (count < WEEK_PLAN_MAX_RIDES_TIGHT && remaining.toDouble() / count > cap) count++
    val limitedByDays = count > days.size
    count = min(count, days.size)
    while (count > 1 && remaining.toDouble() / count < WEEK_PLAN_MIN_RIDE_KM) count--

    // Tagwahl: gierig nach Punkten, Nachbarn eines gewaehlten Tages verlieren etwas.
    val baseScore = days.associateWith { day ->
        val weather = forecastByDate[day]?.let { weatherPenalty(it).total } ?: 0.0
        val daysAhead = ChronoUnit.DAYS.between(input.now.toLocalDate(), day)
        habits.shareOf(day.dayOfWeek) - WEATHER_WEIGHT * weather - EARLY_BONUS_PER_DAY * daysAhead
    }
    val chosen = mutableListOf<LocalDate>()
    repeat(count) {
        val best = days.filter { it !in chosen }.maxByOrNull { day ->
            val adjacent = chosen.any { ChronoUnit.DAYS.between(it, day).let { d -> d == 1L || d == -1L } }
            baseScore.getValue(day) - if (adjacent) ADJACENT_PENALTY else 0.0
        } ?: return@repeat
        chosen += best
    }

    // Auch eine einzelne Fahrt hat mindestens die Mindestlaenge (Regel 3).
    val kms = splitKm(max(remaining, WEEK_PLAN_MIN_RIDE_KM), chosen.map { baseScore.getValue(it) }, cap)
    val rides = chosen.zip(kms)
        .map { (day, km) ->
            val forecast = forecastByDate[day]
            PlannedRide(day, km, reasonFor(forecast, habits.shareOf(day.dayOfWeek)), forecast)
        }
        .sortedBy { it.date }
    val planned = rides.sumOf { it.km }
    val shortfall = (remaining - planned).coerceAtLeast(0)
    val tightCause = when {
        shortfall == 0 -> null
        limitedByDays -> WeekPlanTightCause.ZU_WENIGE_TAGE
        else -> WeekPlanTightCause.OBERGRENZE
    }
    return WeekPlan(
        rides = rides,
        status = if (shortfall > 0) WeekPlanStatus.KNAPP else WeekPlanStatus.MACHBAR,
        remainingKm = remaining,
        shortfallKm = shortfall,
        maxRideKm = cap,
        withWeather = withWeather,
        tightCause = tightCause,
    )
}

/**
 * Verteilt [total] km auf so viele Fahrten, wie [scores] Eintraege hat.
 *
 * Ab 20 km wird der Rest auf volle 5 km aufgerundet und in 5-km-Stufen
 * verteilt („35 km" statt „33 km") — hoechstens 4 km Puffer. Uebrige Stufen
 * bekommen die Tage mit den meisten Punkten. Wuerde eine Fahrt dadurch ueber
 * [cap] rutschen, wird stattdessen auf den Kilometer genau verteilt. Reicht
 * auch das nicht, bekommt jede Fahrt [cap] (der Rest bleibt offen).
 */
internal fun splitKm(total: Int, scores: List<Double>, cap: Int): List<Int> {
    val n = scores.size
    if (n == 0) return emptyList()
    val order = scores.indices.sortedByDescending { scores[it] }

    fun distribute(units: Int, unitKm: Int): List<Int> {
        val result = MutableList(n) { (units / n) * unitKm }
        repeat(units % n) { i -> result[order[i]] += unitKm }
        return result
    }

    if (total.toDouble() / n > cap) return List(n) { cap }
    if (total >= 20) {
        val units = ceil(total / 5.0 - 1e-9).toInt()
        val rounded = distribute(units, 5)
        if (rounded.all { it in WEEK_PLAN_MIN_RIDE_KM..cap } || (n == 1 && rounded[0] <= cap)) return rounded
    }
    return distribute(total, 1)
}
