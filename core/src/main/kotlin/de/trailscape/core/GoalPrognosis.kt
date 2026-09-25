package de.trailscape.core

import java.util.Locale
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * # Zielzeit und Zielzeit-Prognose
 *
 * Die Frage, fuer die der Trainings-Tab da ist: **„Schaffe ich mein Ziel?"** —
 * also etwa „in drei Monaten 60 km mit 700 Hm, und zwar in 2:10 h". Diese
 * Datei beantwortet sie mit einer bewusst einfachen, offen gelegten Rechnung
 * aus den eigenen Touren ([predictGoalFinish]) und liefert die kleinen
 * Helfer drumherum (Zielzeit „h:mm" lesen und schreiben, der Fitness-Trend als
 * Satz, die Frische als Wort).
 *
 * Alles hier ist reine Rechnung ohne Android-Bezug und in
 * `GoalPrognosisTest` festgenagelt.
 */

// ---------------------------------------------------------------------------
// Zielzeit „h:mm"
// ---------------------------------------------------------------------------

/**
 * Liest eine Zielzeit, wie man sie ins Formular tippt: „2:10", „2:10 h",
 * „2.10", „2h10", „2" (volle Stunden) oder „130 min".
 *
 * Liefert die Minuten, oder `null` bei leerer oder unsinniger Eingabe (Minuten
 * ≥ 60, null Minuten insgesamt, mehr als 48 h). Das Formular unterscheidet
 * „leer" (kein Fehler, keine Zielzeit) selbst ueber `isBlank()`.
 */
fun parseGoalDuration(text: String): Int? {
    val t = text.trim().lowercase(Locale.ROOT)
        .removeSuffix("std").removeSuffix("h").trim()
    if (t.isEmpty()) return null

    val minutesOnly = Regex("""^(\d{1,4})\s*min$""").matchEntire(t)
    if (minutesOnly != null) {
        return minutesOnly.groupValues[1].toInt().takeIf { it in 1..MAX_GOAL_DURATION_MIN }
    }

    val hm = Regex("""^(\d{1,2})\s*[:.h]\s*(\d{1,2})$""").matchEntire(t)
    val total = if (hm != null) {
        val h = hm.groupValues[1].toInt()
        val m = hm.groupValues[2].toInt()
        if (m >= 60) return null
        h * 60 + m
    } else {
        val hoursOnly = Regex("""^(\d{1,2})$""").matchEntire(t) ?: return null
        hoursOnly.groupValues[1].toInt() * 60
    }
    return total.takeIf { it in 1..MAX_GOAL_DURATION_MIN }
}

/** Obergrenze einer Zielzeit (48 h) — alles darueber ist ein Tippfehler. */
const val MAX_GOAL_DURATION_MIN: Int = 48 * 60

/** Minuten als „h:mm" (ohne Einheit), z. B. 130 → „2:10". */
fun formatGoalDuration(minutes: Int): String {
    val m = max(0, minutes)
    return "${m / 60}:${(m % 60).toString().padStart(2, '0')}"
}

// ---------------------------------------------------------------------------
// Prognose
// ---------------------------------------------------------------------------

/** Wie weit die Prognose zurueckschaut: sechs Wochen. */
const val PROGNOSIS_LOOKBACK_DAYS: Int = 42

/**
 * Mindestlaenge einer Tour, relativ zur Zieldistanz, damit ihr Tempo zaehlt.
 * Eine 10-km-Feierabendrunde sagt ueber das Tempo auf 60 km wenig — sie wird
 * in einem anderen Belastungsbereich gefahren.
 */
const val PROGNOSIS_MIN_DISTANCE_SHARE: Double = 0.4

/** Wie viele passende Touren es mindestens braucht. */
const val PROGNOSIS_MIN_RIDES: Int = 2

/**
 * Flachkilometer je Hoehenmeter: 1 Hm „kostet" so viel Zeit wie 9 m Strecke in
 * der Ebene. Die gaengige Faustregel liegt zwischen 8 und 10 m (Naismith-artige
 * Umrechnungen im Radsport); 9 ist die Mitte und passt zur Aequivalenzstrecke,
 * mit der `:core` auch Lasten schaetzt (`distanceKm + ascentM / 10`).
 */
const val FLAT_KM_PER_ASCENT_M: Double = 0.009

/**
 * Hoechster Tempogewinn, den die Prognose dem Plan bis zum Renntag zutraut
 * (8 %). Mehr waere bei wenigen Monaten Vorbereitung ein Versprechen, keine
 * Schaetzung.
 */
const val PROGNOSIS_MAX_FITNESS_GAIN: Double = 0.08

/**
 * Exponent, mit dem das Fitness-Verhaeltnis (CTL am Renntag / CTL heute) aufs
 * Tempo durchschlaegt. Deutlich unter 1, weil die Dauerleistung viel langsamer
 * waechst als die Trainingslast: +30 % CTL ergeben so rund +5 % Tempo.
 */
const val PROGNOSIS_FITNESS_EXPONENT: Double = 0.2

/** Halbwertszeit der Aktualitaets-Gewichtung in Tagen. */
private const val RECENCY_HALF_LIFE_DAYS = 21.0

/** Riegel-artiger Ermuedungsexponent fuer laengere Distanzen. */
private const val DISTANCE_FATIGUE_EXPONENT = 0.06

/** Zuschlag je 100 % Zieldistanz ueber der laengsten Tour („Neuland"). */
private const val NEW_TERRITORY_PENALTY = 0.10

/** Hoechster Neuland-Zuschlag. */
private const val MAX_NEW_TERRITORY_PENALTY = 0.15

/** Plausibles Flachtempo (km/h) — alles ausserhalb ist ein Messfehler. */
private val PLAUSIBLE_FLAT_SPEED = 6.0..60.0

private const val DAY_MS = 24L * 60 * 60 * 1000

/**
 * Ergebnis von [predictGoalFinish].
 *
 * Genau eines von [prognosis] und [missing] ist gesetzt.
 */
data class GoalFinishPrediction(
    val prognosis: GoalPrognosis?,
    /** Was fuer eine Prognose fehlt, als fertiger deutscher Satz. */
    val missing: String?,
)

/** Die eigentliche Schaetzung. Alle Zeiten sind Minuten Fahrzeit. */
data class GoalPrognosis(
    /** Zielzeit, wenn das Rennen heute waere. */
    val currentMin: Int,
    /**
     * Zielzeit am Renntag mit der Fitness, die der Plan bis dahin aufbaut —
     * `null`, wenn es keinen Plan oder keine Fitnesskurve gibt.
     */
    val atEventMin: Int?,
    /** Unsicherheit (±) in Minuten; schrumpft mit jeder passenden Tour. */
    val uncertaintyMin: Int,
    /** Anzahl der Touren, deren Tempo eingeflossen ist. */
    val ridesUsed: Int,
    /** Laengste der verwendeten Touren in km. */
    val longestRideKm: Double,
    /** Gewichtetes Flachtempo aus den Touren (km/h, Hoehenmeter eingerechnet). */
    val flatSpeedKmh: Double,
    /** Ob die Zieldistanz ueber der laengsten verwendeten Tour liegt. */
    val beyondLongestRide: Boolean,
)

/**
 * Schaetzt die Fahrzeit fuer [goal] — heute und am Renntag.
 *
 * ## Die Rechnung
 *  1. **Touren auswaehlen**: gefahrene (nicht geplante) Touren der letzten
 *     [PROGNOSIS_LOOKBACK_DAYS] Tage, mindestens [PROGNOSIS_MIN_DISTANCE_SHARE]
 *     der Zieldistanz lang, mit bekannter Fahrzeit (Bewegungszeit, sonst
 *     Gesamtdauer).
 *  2. **Flachtempo je Tour**: Strecke plus Hoehenmeter × [FLAT_KM_PER_ASCENT_M]
 *     (9 m Flachstrecke je Hm), geteilt durch die Fahrzeit. So werden eine
 *     flache und eine huegelige Tour vergleichbar.
 *  3. **Gewichten**: laengere Touren zaehlen mehr (Anteil an der Zieldistanz,
 *     bis 150 %), juengere ebenfalls (Halbwertszeit 3 Wochen) — der Mittelwert
 *     ist gewichtet.
 *  4. **Auf das Ziel anwenden**: Zieldistanz plus Ziel-Hoehenmeter in
 *     Flachkilometer umrechnen, durch das Tempo teilen.
 *  5. **Laenger ist langsamer**: ein Riegel-artiger Faktor
 *     `(Ziel / gewichtete Tourlaenge)^0,06` (im Laufsport 1,06 auf die Zeit),
 *     geklemmt auf 0,97–1,10; liegt das Ziel ueber der laengsten Tour, kommt
 *     ein „Neuland"-Zuschlag von 10 % je 100 % Ueberlaenge dazu (hoechstens
 *     15 %) — ungewohnte Laenge kostet mehr als die Formel fuer Geuebte sagt.
 *  6. **Renntag**: mit [projectedCtl] aus dem Plan skaliert das Tempo mit
 *     `(CTL Renntag / CTL heute)^0,2`, gedeckelt auf
 *     ±[PROGNOSIS_MAX_FITNESS_GAIN] (Verlust auf 5 %).
 *
 * ## Unsicherheit
 * `±(4 % + 12 % / √n)` der Zeit bei n Touren, plus 3 % im Neuland, mindestens
 * drei Minuten. Mit zwei Touren sind das rund ±12 %, mit acht rund ±8 % — die
 * Zahl wird mit jeder Tour genauer, aber nie scheingenau.
 *
 * ## Was bewusst nicht drin ist
 * Puls und Intensitaet der Touren (ob sie locker oder am Limit gefahren
 * wurden), Wind, Untergrund und Pausen. Die Prognose meint **Fahrzeit**; bei
 * einem Rennen ist das praktisch die Zielzeit.
 *
 * @param currentCtl Fitness (CTL) heute, `null` ohne Kurve.
 * @param projectedCtl Fitness am Renntag laut Plan ([projectedEventCtl]),
 *   `null` ohne Plan — dann bleibt [GoalPrognosis.atEventMin] leer.
 */
fun predictGoalFinish(
    goal: Goal,
    rides: List<RideInfo>,
    now: Long = System.currentTimeMillis(),
    currentCtl: Double? = null,
    projectedCtl: Double? = null,
): GoalFinishPrediction {
    val goalKm = goal.distanceKm
    if (!goalKm.isFinite() || goalKm <= 0) {
        return GoalFinishPrediction(null, "Für eine Prognose braucht das Ziel eine Distanz.")
    }
    val minKm = goalKm * PROGNOSIS_MIN_DISTANCE_SHARE
    val since = now - PROGNOSIS_LOOKBACK_DAYS * DAY_MS

    data class Sample(val km: Double, val speed: Double, val weight: Double)

    val samples = riddenRides(rides).mapNotNull { ride ->
        if (ride.createdAt < since || ride.createdAt > now) return@mapNotNull null
        val km = ride.stats.distanceKm
        if (!km.isFinite() || km < minKm) return@mapNotNull null
        val seconds = ride.stats.movingTimeS?.takeIf { it > 0 }
            ?: ride.stats.durationS?.takeIf { it > 0 }
            ?: return@mapNotNull null
        val flatKm = km + max(0.0, ride.stats.ascentM) * FLAT_KM_PER_ASCENT_M
        val speed = flatKm / (seconds / 3600.0)
        if (speed !in PLAUSIBLE_FLAT_SPEED) return@mapNotNull null
        val ageDays = (now - ride.createdAt).toDouble() / DAY_MS
        val lengthWeight = min(1.5, km / goalKm)
        val recencyWeight = exp(-ln(2.0) * ageDays / RECENCY_HALF_LIFE_DAYS)
        Sample(km, speed, lengthWeight * recencyWeight)
    }

    if (samples.size < PROGNOSIS_MIN_RIDES) {
        val needKm = ceil(minKm / 5.0).toInt() * 5
        val text = if (samples.isEmpty()) {
            "Fahre 2–3 längere Touren (ab etwa $needKm km), dann gibt es eine Prognose."
        } else {
            "Noch eine längere Tour (ab etwa $needKm km), dann gibt es eine Prognose."
        }
        return GoalFinishPrediction(null, text)
    }

    val weightSum = samples.sumOf { it.weight }
    val speed = samples.sumOf { it.speed * it.weight } / weightSum
    val refKm = samples.sumOf { it.km * it.weight } / weightSum
    val longest = samples.maxOf { it.km }

    val goalFlatKm = goalKm + max(0.0, goal.ascentM ?: 0.0) * FLAT_KM_PER_ASCENT_M
    val baseMin = goalFlatKm / speed * 60.0
    val fatigue = (goalKm / refKm).pow(DISTANCE_FATIGUE_EXPONENT).coerceIn(0.97, 1.10)
    val beyond = goalKm > longest
    val territory = if (beyond) {
        1 + min(MAX_NEW_TERRITORY_PENALTY, NEW_TERRITORY_PENALTY * (goalKm / longest - 1))
    } else {
        1.0
    }
    val currentMin = baseMin * fatigue * territory

    val atEventMin = if (
        currentCtl != null && projectedCtl != null &&
        currentCtl.isFinite() && projectedCtl.isFinite() && currentCtl > 0 && projectedCtl > 0
    ) {
        val gain = (projectedCtl / currentCtl).pow(PROGNOSIS_FITNESS_EXPONENT)
            .coerceIn(0.95, 1 + PROGNOSIS_MAX_FITNESS_GAIN)
        (currentMin / gain).roundToInt()
    } else {
        null
    }

    val share = 0.04 + 0.12 / sqrt(samples.size.toDouble()) + if (beyond) 0.03 else 0.0
    val uncertainty = max(3, (currentMin * share).roundToInt())

    return GoalFinishPrediction(
        prognosis = GoalPrognosis(
            currentMin = currentMin.roundToInt(),
            atEventMin = atEventMin,
            uncertaintyMin = uncertainty,
            ridesUsed = samples.size,
            longestRideKm = longest,
            flatSpeedKmh = speed,
            beyondLongestRide = beyond,
        ),
        missing = null,
    )
}

/**
 * Fitness (CTL), die [plan] bis zum Renntag aufbaut, ausgehend von
 * [currentCtl].
 *
 * Dieselbe Annahme, mit der [generatePlan] die Last-Budgets rechnet
 * (`planWeekLoadBudgets`): Jede **Aufbauwoche** hebt die CTL um
 * [defaultTargetRampPerWeek], Erholung, Taper und Zielwoche halten sie. Gezaehlt
 * werden die Aufbauwochen ab der laufenden (eingeschlossen) — wer den Plan
 * faehrt, ist am Renntag so fit. `null` ohne gueltige CTL.
 */
fun projectedEventCtl(plan: TrainingPlan, currentCtl: Double?, now: Long? = null): Double? {
    if (currentCtl == null || !currentCtl.isFinite() || currentCtl <= 0) return null
    val nowMs = now ?: System.currentTimeMillis()
    val buildWeeksLeft = plan.weeks.count { it.kind == WeekKind.AUFBAU && it.end > nowMs }
    return currentCtl + buildWeeksLeft * defaultTargetRampPerWeek
}

// ---------------------------------------------------------------------------
// Form in Worten
// ---------------------------------------------------------------------------

/** Richtung der Fitness-Kurve. */
enum class FitnessDirection { STEIGT, STABIL, SINKT }

/** Ergebnis von [describeFitnessTrend]. */
data class FitnessTrend(
    val direction: FitnessDirection,
    /** Seit wie vielen Wochen die Richtung ununterbrochen anhaelt (≥ 1). */
    val weeks: Int,
    /** Der fertige Satz fuer die Formkarte, z. B. „Fitness steigt seit 6 Wochen". */
    val sentence: String,
)

/** Schwelle fuer „steigt"/„sinkt" ueber zwei Wochen, in CTL-Punkten. */
private const val TREND_THRESHOLD_14D = 1.5

/** Schwelle fuer eine einzelne Woche in derselben Richtung. */
private const val TREND_THRESHOLD_WEEK = 0.5

/**
 * Die Fitness-Kurve als ein Satz: steigt, stabil oder sinkt — und seit wann.
 *
 * Die Richtung entscheidet der Abstand der CTL heute zu der vor 14 Tagen (mehr
 * als ±1,5 Punkte); zwei Wochen, weil ein einzelner Ruhetag die Tageskurve
 * schon knicken laesst. Die Dauer zaehlt von heute rueckwaerts die
 * Wochenschritte (je 7 Tage), die in dieselbe Richtung gingen (mehr als
 * ±0,5 Punkte je Woche). „Stabil" nennt keine Dauer — „seit 5 Wochen stabil"
 * hiesse bei einer kaum bewegten Kurve nichts.
 *
 * `null`, solange die Kurve kuerzer als 15 Tage ist.
 */
fun describeFitnessTrend(points: List<FitnessPoint>): FitnessTrend? {
    if (points.size < 15) return null
    val ctl = points.map { it.ctl }
    val last = ctl.size - 1
    val delta = ctl[last] - ctl[last - 14]
    val direction = when {
        delta > TREND_THRESHOLD_14D -> FitnessDirection.STEIGT
        delta < -TREND_THRESHOLD_14D -> FitnessDirection.SINKT
        else -> FitnessDirection.STABIL
    }
    if (direction == FitnessDirection.STABIL) {
        return FitnessTrend(direction, 1, "Fitness stabil")
    }
    var weeks = 0
    var i = last
    while (i - 7 >= 0) {
        val step = ctl[i] - ctl[i - 7]
        val same = if (direction == FitnessDirection.STEIGT) {
            step > TREND_THRESHOLD_WEEK
        } else {
            step < -TREND_THRESHOLD_WEEK
        }
        if (!same) break
        weeks++
        i -= 7
    }
    weeks = max(1, weeks)
    val verb = if (direction == FitnessDirection.STEIGT) "steigt" else "sinkt"
    val sentence = if (weeks >= 2) "Fitness $verb seit $weeks Wochen" else "Fitness $verb"
    return FitnessTrend(direction, weeks, sentence)
}

/**
 * Die Form (TSB) als ein Wort — „frisch", „etwas müde" … —, entlang derselben
 * Baender wie [classifyTsb].
 */
val freshnessWords: Map<TsbBand, String> = mapOf(
    TsbBand.SEHR_FRISCH to "sehr frisch",
    TsbBand.FORMSPITZE to "frisch",
    TsbBand.NEUTRAL to "ausgeglichen",
    TsbBand.PRODUKTIV to "etwas müde",
    TsbBand.UEBERLASTUNG to "sehr müde",
)

/** Kurzform: Frische-Wort zu einem TSB-Wert. */
fun freshnessWord(tsb: Double): String = freshnessWords.getValue(classifyTsb(tsb))
