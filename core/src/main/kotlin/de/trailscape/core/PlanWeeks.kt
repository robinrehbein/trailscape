package de.trailscape.core

import de.trailscape.core.i18n.CoreTexts
import de.trailscape.core.i18n.PLAN_WEEKDAY_CODES
import de.trailscape.core.i18n.SessionTextKey
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * # Der Wochenbau des Trainingsplans
 *
 * Aus Wochenfolge, Startumfang und Ziel entstehen hier die Einheiten jeder
 * Woche. Die Reihenfolge der Entscheidungen ist bewusst:
 *
 *  1. **Wochenart und Phase** ([planWeekKinds], [phasesFor]): Aufbau im
 *     3:1-Rhythmus, Grundlage → Aufbau → spezifisch, zwei Taperwochen bei
 *     genuegend langen Plaenen.
 *  2. **Wunschumfang** der Woche in km (Rampe +15 % gegen die letzte
 *     Aufbauwoche, Erholung −45 %, Taper −35 % / −55 %).
 *  3. **Tage und Rollen** ([chooseTrainingDays], [chooseQualityDays]): Zahl
 *     der Einheiten aus Raster, verfuegbaren Tagen und Zeitbudget (hoechstens
 *     Budget / [MIN_SESSION_MIN]); Tage und Qualitaetstage gemeinsam gewaehlt
 *     — harte Tage nie nebeneinander, nie am Tag vor und nie am Tag nach der
 *     langen Tour.
 *  4. **Einheiten** mit fester Struktur (Qualitaet) und verteilbarem Umfang
 *     (lange Tour, Grundlage, Regeneration); Dauer aus dem persoenlichen
 *     Tempo, Last aus der Struktur ([structuredSessionLoad]). Jede Einheit
 *     dauert mindestens [MIN_SESSION_MIN]; jede Fahrt hat eine Obergrenze
 *     (Regeneration [RECOVERY_RIDE_MAX_MIN], locker [EASY_RIDE_MAX_MIN], GA1
 *     [ENDURANCE_RIDE_MAX_MIN], lange Tour ≤ [LONG_RIDE_MAX_GOAL_SHARE] des
 *     Events, im Taper ≤ [TAPER_LONG_RIDE_MAX_MIN]). Was keine Fahrt sinnvoll
 *     traegt, wird nicht geplant — die Machbarkeitspruefung sagt dann, was
 *     fehlt.
 *  5. **Deckel**: Zeitbudget, Lastrampe (≤ 1,30 × Referenz) und Kilometerrampe
 *     — alle auf die tatsaechliche Wochensumme. Greift einer, wird gekuerzt
 *     und neu gebaut ([fitWeek]): im Aufbau eines Eventplans zuerst die
 *     Wiederholungen, dann die uebrigen Grundlagenfahrten, dann die Qualitaet,
 *     die lange Tour zuletzt; sonst zuerst der Umfang. Die Zahlen werden nie
 *     skaliert, damit Last, Dauer und Kilometer eine Wahrheit bleiben.
 *  6. **Pruefung harter Tage** ueber den ganzen Plan ([enforceHardDayRules]):
 *     hoechstens zwei harte Tage je 7 Tage, nie zwei nebeneinander, nie direkt
 *     vor oder nach der langen Tour — eine Einheit, die das verletzen wuerde
 *     (das kann nur noch ueber die Wochengrenze passieren), wird zur
 *     Grundlagenfahrt gleicher Dauer. Geprueft wird schon beim Bau jeder Woche,
 *     damit Rampe, Erholung und Taper mit der Woche rechnen, wie sie im Plan
 *     steht.
 */

internal const val MIN_WEEKS = 3
internal const val MAX_WEEKS = 52

/**
 * Laufzeit eines Fitnessblocks in Wochen. Unter vier Wochen ist kein
 * Belastungsrhythmus samt Testwoche moeglich; ueber 16 Wochen ist ein Block
 * ohne Event kein Block mehr, sondern eine Saison — dafuer ist ein Eventziel
 * (oder ein zweiter Block) das ehrlichere Werkzeug (Faustregel).
 */
const val FITNESS_MIN_WEEKS = 4
const val FITNESS_MAX_WEEKS = 16

/** Laenge der Vergleichsrunde eines Fitnessblocks, die der Plan uebernimmt; anderes wird zur Standardrunde. */
const val FITNESS_BENCHMARK_MIN_KM = 10.0
const val FITNESS_BENCHMARK_MAX_KM = 120.0

/**
 * Startumfang pro Woche in km fuer jemanden **ohne** Historie — die Stufe ist
 * dann die einzige Information. Mit Historie zaehlt der tatsaechliche Umfang
 * ([startKmFor]).
 */
private val levelBaseKm: Map<FitnessLevel, Double> = mapOf(
    FitnessLevel.EINSTEIGER to 40.0,
    FitnessLevel.FORTGESCHRITTEN to 70.0,
    FitnessLevel.AMBITIONIERT to 110.0,
)

/**
 * Kleinster Startumfang mit Historie in km. Rund eine Stunde Radfahren pro
 * Woche — darunter ist ein Wochenplan mit mehreren Einheiten nicht sinnvoll
 * teilbar (Faustregel).
 */
internal const val MIN_START_KM = 15.0

/**
 * Groesster zugelassener Sprung des Wochenumfangs von einer Aufbauwoche zur
 * naechsten (Anteil, 0,15 = +15 %).
 *
 * Die verbreitete „10-%-Regel" ist eine Faustregel: Buist et al. 2008
 * (randomisiert, Laufanfaenger) fanden mit ihr kein geringeres
 * Verletzungsrisiko. Die App selbst warnt ab +30 % Wochenlast gegen das
 * Vierwochenmittel (`assessDeload`) — ein Deckel in der Groessenordnung, die
 * Nielsen et al. 2014 explorativ bei Laufanfaengern fanden. 15 % liegt sauber
 * dazwischen; die Zahl selbst ist eine Faustregel. Die Grenze
 * wirkt gegen die **letzte Aufbauwoche**, nicht gegen die Vorwoche — nach
 * einer Erholungswoche ist die Rueckkehr auf das vertragene Niveau kein neuer
 * Reiz.
 */
internal const val MAX_WEEKLY_INCREASE = 0.15

/**
 * Kleinster Schritt nach oben in km. Ohne ihn bliebe ein kleiner Umfang ewig
 * stehen: 15 % von 20 km sind 3 km, und auf 5 km gerundet waere das nie ein
 * Schritt. 5 km sind bei kleinen Umfaengen rund 15–20 Minuten mehr pro Woche.
 */
internal const val MIN_WEEKLY_STEP_KM = 5.0

/**
 * Umfang einer Erholungswoche als Anteil der letzten Aufbauwoche (0,55 =
 * −45 %). Die App empfiehlt in `assessDeload` selbst „−40–50 % Volumen,
 * Intensitaet bleibt"; 0,55 liegt in der Mitte dieses Korridors.
 */
internal const val RECOVERY_FACTOR = 0.55

/**
 * Umfang der Taperwochen als Anteil des **erreichten** Spitzenumfangs.
 *
 * Bosquet et al. 2007 (*Med Sci Sports Exerc* 39:1358–1365, Metaanalyse):
 * am wirksamsten ist ein Taper von etwa zwei Wochen mit progressiv um 41–60 %
 * reduziertem Volumen bei **unveraenderter Intensitaet und Haeufigkeit**;
 * Mujika & Padilla 2003 (*Med Sci Sports Exerc* 35:1182–1187): Haeufigkeit
 * hoechstens um etwa 20 % senken. Der Plan stuft deshalb zweistufig ab
 * (65 % → 45 % des Spitzenumfangs, also −35 % und −55 %), haelt die Zahl der
 * Einheiten (bei drei Einheiten waere schon eine weniger −33 %) und kuerzt
 * stattdessen jede Einheit. Die Tage der Zielwoche vor dem Event setzen die
 * Reduktion fort. Plaene unter [TWO_WEEK_TAPER_MIN_WEEKS] Wochen haben fuer
 * zwei Taperwochen keinen Platz und bekommen **eine** Woche bei 55 % — das
 * ist eine Faustregel, keine Studienempfehlung.
 */
private val TWO_WEEK_TAPER_FACTORS = listOf(0.65, 0.45)
private const val SINGLE_TAPER_FACTOR = 0.55

/** Ab dieser Laufzeit gibt es zwei Taperwochen statt einer. */
internal const val TWO_WEEK_TAPER_MIN_WEEKS = 8

/**
 * Hoechstlaenge der langen Tour in den Taperwochen als Anteil der laengsten
 * langen Tour im Aufbau. Ein Taper, der eine 4,5-h-Fahrt enthaelt, ist keiner;
 * 60 % bzw. 45 % folgen den Umfangsfaktoren der jeweiligen Woche (Faustregel).
 */
private val TWO_WEEK_TAPER_LONG_SHARE = listOf(0.6, 0.45)
private const val SINGLE_TAPER_LONG_SHARE = 0.5

/**
 * Angestrebter Spitzenumfang als Vielfaches der Zieldistanz (1,3): Mit den
 * Anteilen der langen Tour (siehe [longRideWeight]) erreicht die laengste
 * Trainingsfahrt eines voll ausgefahrenen Plans damit sicher
 * [minLongestRideShare] der Zieldistanz.
 */
internal const val PEAK_DISTANCE_FACTOR = 1.3

/**
 * Umfangszuwachs ueber einen Fitnessblock (1,2 = +20 %). Ein Block auf
 * „schneller werden" lebt von der Intensitaetsprogression; der Umfang waechst
 * nur moderat mit (Faustregel).
 */
internal const val FITNESS_PEAK_FACTOR = 1.2

/**
 * Lastdeckel einer Aufbauwoche: hoechstens das 1,30-Fache der Referenz
 * (7 × CTL bzw. die Last der letzten Aufbauwoche, das Groessere). Dieselbe
 * Grenze, ab der `weeklyLoadTarget` und `assessDeload` einen Belastungssprung
 * melden — der Plan schreibt nie vor, was die eigene Auswertung danach
 * anmahnt.
 */
internal const val LOAD_RAMP_CAP = 1.30

/** Ab dieser Zielhoehe gilt ein Plan als huegelig (Anstiegshinweis, Intervalle am Berg). */
internal const val CLIMB_HINT_THRESHOLD_M = 1000.0

/**
 * So viele Aufbauwochen fahren Einsteiger reine Grundlage, bevor der erste
 * sanfte Reiz kommt: erst Umfang, dann Haerte (Faustregel der
 * Einsteigerbetreuung).
 */
private const val BEGINNER_BASE_WEEKS = 3

/**
 * Kuerzeste Einheit in Minuten (30 min, Faustregel). Darunter lohnt das
 * Umziehen nicht, und eine Qualitaetseinheit passt mit 15 min Einfahren und
 * 10 min Ausfahren ohnehin nicht hinein. Gilt fuer **jede** Einheit; passt
 * eine Einheit mit dieser Dauer nicht mehr in die Woche, sinkt die Zahl der
 * Einheiten. Einzige Ausnahme ist die Untergrenze einer Woche: Eine Einheit
 * dieser Dauer bleibt immer stehen, auch wenn sie Zeitbudget, Lastdeckel oder
 * Wochenumfang ueberschreitet: genau eine Grundlagenfahrt mit der kuerzesten
 * ganzen Kilometerzahl, die mindestens 30 Minuten dauert. Eine Woche „ganz
 * ohne" waere kein Plan, und 30 min Grundlage sind die kleinste Belastung, die
 * der Plan ueberhaupt vorschreibt (siehe [fitWeek]; bei CTL 1 etwa liegt
 * schon sie ueber dem Lastdeckel von 9 Punkten).
 */
internal const val MIN_SESSION_MIN = 30

/**
 * Ab dieser Dauer heisst die laengste Fahrt der Woche „Lange Tour" (60 min,
 * Faustregel). Darunter ist sie eine gewoehnliche Grundlagenfahrt und traegt
 * deren Titel — eine „Lange Tour" ueber 25 Minuten verspricht etwas, das sie
 * nicht ist.
 */
internal const val LONG_RIDE_MIN_MIN = 60

/**
 * Obergrenze der Regenerations- bzw. Kompensationsfahrt am Tag nach der
 * langen Tour (75 min, Faustregel: 60–90 min locker). Laenger ist es keine
 * Regeneration mehr.
 */
internal const val RECOVERY_RIDE_MAX_MIN = 75

/**
 * Obergrenze jeder lockeren Fahrt (Erholungs- und Taperwoche): 90 min
 * (Faustregel). Was darueber hinaus verteilt werden muesste, plant der Plan
 * nicht ein — er kippt den Rest nicht auf eine Einheit.
 */
internal const val EASY_RIDE_MAX_MIN = 90

/**
 * Bis zu dieser Dauer heisst eine lockere Taperfahrt „Kurze lockere
 * Ausfahrt"; laenger wird sie zur Fahrt „Locker mit Antritten" (bis
 * [EASY_RIDE_MAX_MIN]).
 */
internal const val TAPER_SHORT_MAX_MIN = 60

/**
 * Hoechstdauer der langen Tour in einer Taperwoche (4 h, Faustregel) —
 * zusaetzlich zum Anteil an der laengsten Aufbau-Tour. Ein Taper mit einer
 * 8-h-Fahrt ist keiner.
 */
internal const val TAPER_LONG_RIDE_MAX_MIN = 240

/**
 * Die lange Tour eines Eventplans ist hoechstens 80 % der Zieldistanz lang
 * (Faustregel: die laengste Vorbereitungsfahrt liegt bei rund zwei Dritteln
 * bis drei Vierteln der Zieldistanz, siehe [minLongestRideShare]) und nie
 * laenger als das Event. Mehr Umfang, als lange Tour und Grundlagenfahrten
 * tragen, plant der Plan nicht ein.
 */
internal const val LONG_RIDE_MAX_GOAL_SHARE = 0.8

/**
 * Obergrenze einer gewoehnlichen Grundlagenfahrt (GA1) neben der langen Tour:
 * 4 h (Faustregel). Ein „GA1" ueber zehn Stunden ist eine zweite lange Tour,
 * keine Fuellfahrt; was darueber hinaus verteilt werden muesste, plant der
 * Plan nicht ein.
 */
internal const val ENDURANCE_RIDE_MAX_MIN = 240

/** Hoechstdauer der langen Tour eines Fitnessblocks (4 h, Faustregel; ohne Zieldistanz). */
internal const val FITNESS_LONG_RIDE_MAX_MIN = 240

/**
 * Die verkuerzte lange Tour der Erholungswoche: hoechstens 70 % der langen
 * Tour der letzten Aufbauwoche (Faustregel, passend zu −45 % Umfang) und —
 * wie im Taper — hoechstens [TAPER_LONG_RIDE_MAX_MIN] Minuten.
 */
private const val RECOVERY_LONG_SHARE = 0.7

/** Dauer der Aktivierung vor dem Event in Minuten (30–45 min sind ueblich). */
private const val ACTIVATION_MIN = 40

/** Dauer der lockeren Ausfahrt in der Zielwoche in Minuten. */
private const val EVENT_WEEK_EASY_MIN = 50

/** Ob der Plan bewusst Anstiege einbaut (IF-Aufschlag, Intervalle am Berg). */
internal fun planIsHilly(goal: Goal): Boolean = (goal.ascentM ?: 0.0) >= CLIMB_HINT_THRESHOLD_M

// ---------------------------------------------------------------------------
// Wochenfolge und Phasen
// ---------------------------------------------------------------------------

/**
 * Die Wochenarten eines Plans.
 *
 * Event: Zielwoche zuletzt, davor eine bzw. (ab [TWO_WEEK_TAPER_MIN_WEEKS])
 * zwei Taperwochen; davor jede 4. Woche Erholung (3:1 — gaengige Praxis; fuer
 * Ausdauersport gibt es dazu keine kontrollierten Studien, die Konsensarbeiten
 * stammen aus dem Kraftsport bzw. die Studien zur Blockperiodisierung sind von
 * geringer Qualitaet), ausser sie waere die
 * letzte Aufbauwoche vor dem Taper. Fitnessblock: 3:1 bis zur Testwoche (eine
 * Erholungswoche direkt vor dem Test ist erwuenscht — frische Beine).
 */
internal fun planWeekKinds(weekCount: Int, type: GoalType = GoalType.EVENT): List<WeekKind> {
    if (type == GoalType.FITNESS) {
        return List(weekCount) { i ->
            when {
                i == weekCount - 1 -> WeekKind.ZIELWOCHE
                i % 4 == 3 -> WeekKind.ERHOLUNG
                else -> WeekKind.AUFBAU
            }
        }
    }
    val taperWeeks = if (weekCount >= TWO_WEEK_TAPER_MIN_WEEKS) 2 else 1
    val lastBuildIndex = weekCount - 2 - taperWeeks
    return List(weekCount) { i ->
        when {
            i == weekCount - 1 -> WeekKind.ZIELWOCHE
            i >= weekCount - 1 - taperWeeks -> WeekKind.TAPER
            i % 4 == 3 && i != lastBuildIndex -> WeekKind.ERHOLUNG
            else -> WeekKind.AUFBAU
        }
    }
}

/**
 * Die Phase jeder Woche.
 *
 * Die Aufbauwochen werden gedrittelt: rund 35 % Grundlage, 25 % spezifisch am
 * Ende, der Rest Aufbau — die klassische Periodisierung vom Allgemeinen zum
 * Spezifischen (Faustregel; die genauen Anteile sind nicht studienbelegt).
 * Bei nur einer oder zwei Aufbauwochen faellt die Grundlage weg. Erholungs-
 * und Taperwochen tragen ihre eigene Phase.
 */
internal fun phasesFor(kinds: List<WeekKind>, type: GoalType): List<TrainingPhase> {
    val buildCount = kinds.count { it == WeekKind.AUFBAU }
    val (baseCount, specificCount) = when {
        buildCount <= 1 -> 0 to 0
        buildCount == 2 -> 0 to 1
        else -> {
            val spec = max(1, (buildCount * 0.25).roundToInt())
            val base = max(1, (buildCount * 0.35).roundToInt()).coerceAtMost(buildCount - spec - 1).coerceAtLeast(0)
            base to spec
        }
    }
    var seen = 0
    return kinds.map { kind ->
        when (kind) {
            WeekKind.AUFBAU -> {
                val phase = when {
                    seen < baseCount -> TrainingPhase.GRUNDLAGE
                    seen >= buildCount - specificCount -> TrainingPhase.SPEZIFISCH
                    else -> TrainingPhase.AUFBAU
                }
                seen++
                phase
            }
            WeekKind.ERHOLUNG -> TrainingPhase.ERHOLUNG
            WeekKind.TAPER -> TrainingPhase.TAPER
            WeekKind.ZIELWOCHE -> if (type == GoalType.FITNESS) TrainingPhase.TEST else TrainingPhase.EVENT
        }
    }
}

/**
 * Die Phase jeder Planwoche (Grundlage, Aufbau, spezifisch, Erholung, Taper,
 * Event/Test) — fuer die Anzeige. Aus der Wochenfolge abgeleitet, nicht
 * gespeichert (siehe [TrainingPhase]).
 */
fun planWeekPhases(plan: TrainingPlan): List<TrainingPhase> =
    phasesFor(plan.weeks.map { it.kind }, plan.goal.type)

// ---------------------------------------------------------------------------
// Umfang
// ---------------------------------------------------------------------------

/**
 * Startumfang eines Plans: mit Historie der tatsaechliche Wochenumfang
 * (mindestens [MIN_START_KM]), ohne Historie das Basisvolumen der Stufe.
 *
 * Frueher galt immer `max(Wochenumfang, Basis)` — wer 20 km pro Woche fuhr,
 * sprang in Woche 1 auf 40 km. Die Stufenbasis ist eine Annahme, wenn man
 * nichts weiss; sobald man etwas weiss, gilt das Gewusste.
 */
internal fun startKmFor(assessment: FitnessAssessment): Double =
    if (assessment.rideCount > 0 && assessment.weeklyKm > 0) {
        max(assessment.weeklyKm, MIN_START_KM)
    } else {
        levelBaseKm.getValue(assessment.level)
    }

/** Angestrebter Spitzenumfang — die Absicht, nicht die Zusage. */
internal fun peakKmFor(goal: Goal, startKm: Double): Double = when (goal.type) {
    GoalType.EVENT -> max(goal.distanceKm * PEAK_DISTANCE_FACTOR, startKm)
    GoalType.FITNESS -> startKm * FITNESS_PEAK_FACTOR
}

private fun round5(km: Double): Int = max(5, dartRound(km / 5).toInt() * 5)

/** Wie [round5], aber nie ueber [capKm] hinaus (notfalls eine Stufe zurueck). */
private fun round5AtMost(km: Double, capKm: Double): Int {
    val rounded = round5(min(km, capKm))
    return if (rounded > capKm && rounded > 5) rounded - 5 else rounded
}

/** Erlaubter Hoechstumfang einer Aufbauwoche nach der letzten Aufbauwoche [lastBuildKm]. */
internal fun rampCapKm(lastBuildKm: Double): Double =
    max(lastBuildKm * (1 + MAX_WEEKLY_INCREASE), lastBuildKm + MIN_WEEKLY_STEP_KM)

// ---------------------------------------------------------------------------
// Einheitenraster und Tage
// ---------------------------------------------------------------------------

/**
 * Einheiten je Aufbauwoche nach Stufe und Umfang — das Raster, das ohne
 * Praeferenzen gilt (mit Praeferenzen: `min(Raster, verfuegbare Tage)`).
 * Mehr Umfang auf mehr Einheiten statt auf immer laengere Fahrten.
 */
internal fun rasterSessionCount(level: FitnessLevel, weekKm: Double): Int = when (level) {
    FitnessLevel.EINSTEIGER -> if (weekKm >= 60) 3 else 2
    FitnessLevel.FORTGESCHRITTEN -> if (weekKm >= 130) 4 else 3
    FitnessLevel.AMBITIONIERT -> if (weekKm >= 120) 5 else 4
}

/** Trainingstage ohne Praeferenzen (0 = Mo): das bisherige Di/Do/Sa/So-Raster, erweitert. */
private fun defaultTrainingDays(level: FitnessLevel): List<Int> = when (level) {
    FitnessLevel.EINSTEIGER -> listOf(1, 5, 6)
    FitnessLevel.FORTGESCHRITTEN -> listOf(1, 3, 5, 6)
    FitnessLevel.AMBITIONIERT -> listOf(1, 2, 3, 5, 6)
}

private fun circularGap(a: Int, b: Int): Int {
    val d = abs(a - b) % 7
    return min(d, 7 - d)
}

/** Alle Teilmengen der Groesse [k] von [items] in fester Reihenfolge. */
private fun <T> combinations(items: List<T>, k: Int): List<List<T>> {
    if (k == 0) return listOf(emptyList())
    if (items.size < k) return emptyList()
    val head = items.first()
    val tail = items.drop(1)
    return combinations(tail, k - 1).map { listOf(head) + it } + combinations(tail, k)
}

/**
 * Waehlt [count] Tage aus [available], immer inklusive [longDay].
 *
 * Zuerst zaehlt, dass [qualityCount] Qualitaetseinheiten nach den Regeln von
 * [chooseQualityDays] Platz finden (nie am Tag nach der langen Tour, nie
 * davor, nie nebeneinander) — Tage und Qualitaetstage werden gemeinsam
 * gewaehlt, sonst landet die Qualitaet am Tag nach der langen Tour, obwohl
 * eine andere Tagauswahl das vermieden haette. Danach so gleichmaessig wie
 * moeglich ueber die Woche verteilt (groesster kleinster Abstand, zyklisch
 * gerechnet — die Woche wiederholt sich).
 */
internal fun chooseTrainingDays(available: List<Int>, count: Int, longDay: Int, qualityCount: Int = 0): List<Int> {
    val others = available.filter { it != longDay }
    val k = (count - 1).coerceIn(0, others.size)
    val best = combinations(others, k).maxWithOrNull(
        compareBy<List<Int>> { combo ->
            if (qualityCount <= 0) 0 else chooseQualityDays(combo + longDay, longDay, qualityCount).size
        }.thenBy { combo ->
            val days = (combo + longDay).sorted()
            if (days.size < 2) 7 else days.indices.minOf { i -> circularGap(days[i], days[(i + 1) % days.size]) }
        }.thenByDescending { combo ->
            // Gleichstand: kleinere Summe der quadrierten Abstaende = gleichmaessiger.
            val days = (combo + longDay).sorted()
            days.indices.sumOf { i ->
                val gap = (days[(i + 1) % days.size] - days[i] + 7) % 7
                gap * gap
            }
        }.thenByDescending { combo -> combo.joinToString(",") { (9 - it).toString() } },
    ) ?: emptyList()
    return (best + longDay).sorted()
}

/**
 * Waehlt aus [days] die Tage fuer [count] Qualitaetseinheiten.
 *
 * Regeln: nie die lange Tour, nie der Tag davor, nie der Tag danach, zwei
 * harte Tage nie nebeneinander (zyklisch — der Sonntag liegt neben dem
 * naechsten Montag). Unter den erlaubten Kombinationen gewinnt die mit dem
 * groessten Abstand. Passt keine Kombination, werden es weniger
 * Qualitaetseinheiten — eine harte Einheit am Tag nach der langen Tour plant
 * der Plan nicht, er laesst sie dann weg (gewaehlt werden die Tage deshalb
 * gemeinsam mit den Qualitaetstagen, [chooseTrainingDays]).
 */
internal fun chooseQualityDays(days: List<Int>, longDay: Int, count: Int): List<Int> {
    val dayBefore = (longDay + 6) % 7
    val dayAfter = (longDay + 1) % 7
    val candidates = days.filter { it != longDay && it != dayBefore && it != dayAfter }
    for (k in min(count, candidates.size) downTo 1) {
        val valid = combinations(candidates, k).filter { combo ->
            combo.all { a -> combo.all { b -> a == b || circularGap(a, b) > 1 } }
        }
        val best = valid.maxWithOrNull(
            compareBy<List<Int>> { combo -> combo.minOf { circularGap(it, longDay) } }
                .thenBy { combo ->
                    if (combo.size < 2) 7 else combo.minOf { a -> combo.filter { it != a }.minOf { circularGap(a, it) } }
                }
                .thenByDescending { combo -> combo.sum() },
        )
        if (best != null) return best.sorted()
    }
    return emptyList()
}

// ---------------------------------------------------------------------------
// Bauplan
// ---------------------------------------------------------------------------

/** Alles, was der Wochenbau ueber den Plan wissen muss. */
internal class PlanBuildInput(
    val goal: Goal,
    val level: FitnessLevel,
    val texts: CoreTexts,
    /** Persoenliches Grundtempo; `null` = [fallbackGravelSpeedKmh]. */
    val speedKmh: Double?,
    val preferences: PlanPreferences?,
    val currentCtl: Double?,
    val kinds: List<WeekKind>,
    val startKm: Double,
    val peakKm: Double,
    /** Mit Zeitdeckel aus [preferences] rechnen (aus fuer „was braeuchte es ohne Budget"). */
    val applyTimeBudget: Boolean = true,
) {
    val hilly: Boolean = planIsHilly(goal)
    val phases: List<TrainingPhase> = phasesFor(kinds, goal.type)
    val baseSpeed: Double = speedKmh?.takeIf { it.isFinite() && it > 0 } ?: fallbackGravelSpeedKmh
    val available: List<Int> = preferences?.normalizedDays() ?: defaultTrainingDays(level)
    val explicitDays: Boolean = preferences?.normalizedDays() != null
    val longDay: Int = longDayIn(available)
    val budgetMin: Double? =
        if (applyTimeBudget) preferences?.validWeeklyHours()?.let { it * 60 } else null
    val ctl: Double? = currentCtl?.takeIf { it.isFinite() && it > 0 }
    val buildCount: Int = kinds.count { it == WeekKind.AUFBAU }

    /** Wochentag des Ziels (0 = Mo): Eventtag bzw. Tag der Vergleichsrunde. */
    val goalDay: Int = dartLocalOf(goal.date).dayOfWeek.value - 1

    /** Ziel unter dem heutigen Umfang: Fortschritt kommt dann ueber Qualitaet. */
    val goalUnderVolume: Boolean = goal.type == GoalType.EVENT && peakKm <= startKm * 1.05

    /** Der Tag der langen Tour innerhalb von [days]: Wunschtag, sonst Sa, So, der letzte. */
    fun longDayIn(days: List<Int>): Int {
        val wanted = preferences?.validLongRideDay()
        return when {
            wanted != null && wanted in days -> wanted
            5 in days -> 5
            6 in days -> 6
            else -> days.last()
        }
    }

    fun speed(intensity: SessionIntensity): Double = max(baseSpeed * intensitySpeedFactor(intensity), 1.0)
    fun minutesFor(km: Double, intensity: SessionIntensity): Int =
        max(1, (km / speed(intensity) * 60).roundToInt())
    fun kmForMinutes(minutes: Int, intensity: SessionIntensity): Int =
        max(1, (minutes / 60.0 * speed(intensity)).roundToInt())

    /** Kleinste Strecke, die [MIN_SESSION_MIN] Minuten dauert. */
    fun minKm(intensity: SessionIntensity): Int = ceil(MIN_SESSION_MIN / 60.0 * speed(intensity)).toInt()

    /** Groesste Strecke, die hoechstens [minutes] Minuten dauert. */
    fun kmAtMostMinutes(minutes: Int, intensity: SessionIntensity): Int =
        max(1, floor(minutes / 60.0 * speed(intensity)).toInt())

    /** Kleinste Strecke, die mindestens [minutes] Minuten dauert. */
    fun kmAtLeastMinutes(minutes: Int, intensity: SessionIntensity): Int =
        max(1, ceil(minutes / 60.0 * speed(intensity)).toInt())

    /** IF einer gleichmaessigen Fahrt dieser Intensitaet (wie [sessionSegments]). */
    fun steadyIf(intensity: SessionIntensity): Double =
        sessionIntensityIf(intensity) + if (hilly && intensity == SessionIntensity.GRUNDLAGE) HILLY_IF_BONUS else 0.0

    /** Hoechstzahl an Einheiten, die das Zeitbudget mit [MIN_SESSION_MIN] je Einheit zulaesst. */
    fun budgetSessionCount(reservedMin: Int = 0): Int =
        budgetMin?.let { max(1, floor((it - reservedMin) / MIN_SESSION_MIN).toInt()) } ?: Int.MAX_VALUE
}

/** Zustand, der von Woche zu Woche weitergereicht wird. */
internal class PlanBuildState(
    var buildSeen: Int = 0,
    var lastBuildKm: Double,
    /**
     * Groesster Umfang einer **tatsaechlich gebauten** Aufbauwoche; `null`,
     * solange es keine gab (dann rechnet der Taper mit dem Startumfang).
     * Druecken Zeitbudget oder Lastdeckel die Aufbauwochen unter den
     * Startumfang, ist das hier der niedrigere, erreichte Wert.
     */
    var achievedPeakKm: Double? = null,
    var lastBuildLoad: Double? = null,
    var lastBuildSessionCount: Int = 0,
    var longestBuildLongKm: Int = 0,
    /** Laenge der Fahrt am Tag der langen Tour in der letzten Aufbauwoche. */
    var lastBuildLongKm: Int = 0,
    var lastQuality: List<WorkoutShape> = emptyList(),
    val workoutUses: MutableMap<WorkoutKind, Int> = mutableMapOf(),
    var racePaceUses: Int = 0,
    var taperSeen: Int = 0,
    /**
     * Die naechste Aufbauwoche bleibt auf [lastBuildKm], statt schon wieder zu
     * steigen — nach einer deutlich verfehlten Woche ([adaptPlan]) wird erst
     * das Erreichte bestaetigt, dann weiter aufgebaut.
     */
    var holdNextBuild: Boolean = false,
    /** Ob die zuletzt gebaute Aufbauwoche Qualitaet vorsah (fuer [recordBuildWeek]). */
    var plannedQuality: Boolean = false,
)

/** Rolle einer Einheit innerhalb der Woche. */
private enum class Role { LONG, QUALITY, ENDURANCE, RECOVERY }

/** Rohbau einer Woche: Einheiten und ihre Summen. */
private class WeekDraft(val sessions: List<TrainingSession>, hilly: Boolean) {
    val minutes: Int = sessions.sumOf { it.durationMin ?: 0 }
    val load: Double = sessions.filter { !it.isEvent }.sumOf { structuredSessionLoad(it, hilly) ?: 0.0 }
    val km: Int = sessions.sumOf { it.targetKm }
}

/**
 * Baut die Einheiten aller Wochen; die Wochen vor [fromIndex] und die in
 * [frozen] stehen unveraendert aus [priorWeeks] (fuer [adaptPlan]: gefahrene
 * Wochen und die woertlich bleibende Zielwoche) und zaehlen in der Pruefung
 * der harten Tage als fest.
 *
 * [state] ist der Zustand vor Woche [fromIndex] — fuer einen neuen Plan der
 * Startzustand, fuer [adaptPlan] der aus den bereits gefahrenen Wochen.
 */
internal fun buildPlanWeekSessions(
    input: PlanBuildInput,
    state: PlanBuildState = PlanBuildState(lastBuildKm = input.startKm),
    fromIndex: Int = 0,
    priorWeeks: List<List<TrainingSession>> = emptyList(),
    frozen: Set<Int> = emptySet(),
): List<List<TrainingSession>> {
    val weeks = ArrayList<List<TrainingSession>>(input.kinds.size)
    for (i in input.kinds.indices) {
        if (i < fromIndex || i in frozen) {
            weeks.add(priorWeeks.getOrElse(i) { emptyList() })
            continue
        }
        val raw = buildWeek(input, state, i, weeks.lastOrNull().orEmpty())
        // Schon hier gegen die fertigen Vorwochen pruefen (Wochengrenze!), damit
        // der Zustand die Woche kennt, wie sie im Plan steht.
        val week = enforceHardDayRules(input, weeks + listOf(raw)) { it < i }.last()
        if (input.kinds[i] == WeekKind.AUFBAU) recordBuildWeek(input, state, week)
        weeks.add(week)
    }
    return enforceHardDayRules(input, weeks) { it < fromIndex || it in frozen }
}

private fun buildWeek(
    input: PlanBuildInput,
    state: PlanBuildState,
    index: Int,
    previous: List<TrainingSession>,
): List<TrainingSession> {
    val kind = input.kinds[index]
    val phase = input.phases[index]
    return when (kind) {
        WeekKind.AUFBAU -> buildAufbauWeek(input, state, phase)
        WeekKind.ERHOLUNG -> buildErholungWeek(input, state)
        WeekKind.TAPER -> buildTaperWeek(input, state, index)
        WeekKind.ZIELWOCHE -> if (input.goal.type == GoalType.FITNESS) {
            buildTestWeek(input, state, previous)
        } else {
            buildEventWeek(input, state, previous)
        }
    }
}

/** Qualitaetsarten einer Aufbauwoche nach Stufe und Phase. */
private fun qualityKindsFor(input: PlanBuildInput, phase: TrainingPhase, buildSeen: Int): List<Pair<WorkoutKind, Boolean>> {
    val hill = input.hilly && phase == TrainingPhase.SPEZIFISCH
    return when (input.level) {
        FitnessLevel.EINSTEIGER ->
            if (buildSeen < BEGINNER_BASE_WEEKS || phase == TrainingPhase.GRUNDLAGE) {
                emptyList()
            } else {
                listOf(WorkoutKind.TEMPO to hill)
            }
        FitnessLevel.FORTGESCHRITTEN -> when (phase) {
            TrainingPhase.GRUNDLAGE -> listOf(WorkoutKind.SWEET_SPOT to false)
            TrainingPhase.SPEZIFISCH ->
                if (input.hilly) listOf(WorkoutKind.THRESHOLD to true) else listOf(WorkoutKind.VO2MAX to false)
            else -> listOf(WorkoutKind.THRESHOLD to false)
        }
        FitnessLevel.AMBITIONIERT -> when (phase) {
            TrainingPhase.GRUNDLAGE -> listOf(WorkoutKind.SWEET_SPOT to false, WorkoutKind.TEMPO to false)
            TrainingPhase.SPEZIFISCH -> listOf(WorkoutKind.VO2MAX to false, WorkoutKind.THRESHOLD to input.hilly)
            else -> listOf(WorkoutKind.THRESHOLD to false, WorkoutKind.VO2MAX to false)
        }
    }
}

/**
 * Hoechstzahl an Qualitaetseinheiten fuer [sessionCount] Einheiten.
 *
 * Grob 80/20 nach Einheiten (Seiler 2010, *Int J Sports Physiol Perform*
 * 5:276–291 — die 80/20-Verteilung dort zaehlt **Einheiten**, nicht Zeit in
 * Zonen): Bei bis
 * zu vier Einheiten ist eine harte die Obergrenze, ab fuenf Einheiten zwei —
 * nie mehr als die zwei harten Tage je 7 Tage, die auch die Tagesempfehlung
 * der App zulaesst (`MAX_HARD_DAYS_PER_7D`). Nach Zeit bleibt der harte Anteil
 * dabei deutlich unter 20 %, weil Ein-/Ausfahren und Pausen locker sind.
 */
private fun maxQualityFor(sessionCount: Int): Int = when {
    sessionCount >= 5 -> 2
    sessionCount >= 2 -> 1
    else -> 0
}

/** Stufen-Versatz der Progression: Ambitionierte steigen eine Stufe hoeher ein. */
private fun levelStepOffset(level: FitnessLevel): Int = if (level == FitnessLevel.AMBITIONIERT) 1 else 0

private fun enduranceKey(level: FitnessLevel): SessionTextKey = when (level) {
    FitnessLevel.EINSTEIGER -> SessionTextKey.BASE_EASY_RIDE
    FitnessLevel.FORTGESCHRITTEN -> SessionTextKey.ENDURANCE_FILL
    FitnessLevel.AMBITIONIERT -> SessionTextKey.ENDURANCE_STEADY
}

private fun recoveryKey(level: FitnessLevel): SessionTextKey =
    if (level == FitnessLevel.AMBITIONIERT) SessionTextKey.ENDURANCE_COMPENSATION else SessionTextKey.RECOVERY_SPIN

/**
 * Gewicht der langen Tour beim Verteilen des Umfangs (Grundlagenfahrt = 1,0).
 * Entspricht den bisherigen Anteilen (Einsteiger 0,6 : 0,4, Fortgeschritten
 * 0,55 : 0,25, Ambitioniert 0,45 : 0,2) — sie sind der Grund, warum ein voll
 * ausgefahrener Plan [minLongestRideShare] nimmt.
 */
private fun longRideWeight(level: FitnessLevel): Double = when (level) {
    FitnessLevel.EINSTEIGER -> 1.5
    FitnessLevel.FORTGESCHRITTEN -> 2.2
    FitnessLevel.AMBITIONIERT -> 2.25
}

/**
 * Die lange Tour einer Aufbauwoche soll mindestens
 * `(minLongestRideShare + 0,05) × Zieldistanz` lang sein — aber nie mehr als
 * die Haelfte des Wochenumfangs ([LONG_RIDE_MAX_WEEK_SHARE]) und nie mehr als
 * [LONG_RIDE_MAX_SHARE] des nach den Qualitaetseinheiten verteilbaren
 * Umfangs (beides Faustregeln gegen eine Woche, die nur aus einer Fahrt
 * besteht). Was die Untergrenze braucht, wird **anteilig** von den uebrigen
 * Grundlagenfahrten genommen; reicht es dann fuer eine von ihnen nicht mehr
 * zur Mindestdauer, wird sie gestrichen. Damit nimmt ein Plan, der seinen
 * Spitzenumfang erreicht, die Machbarkeitsschwelle in jeder Stufe; ohne genug
 * Umfang greift die Obergrenze, und die Machbarkeit sagt ehrlich, dass es
 * nicht reicht.
 */
private const val LONG_RIDE_FLOOR_MARGIN = 0.05
private const val LONG_RIDE_MAX_SHARE = 0.7
private const val LONG_RIDE_MAX_WEEK_SHARE = 0.5

/** Gewicht einer Regenerations-/Kompensationsfahrt beim Verteilen. */
private const val RECOVERY_RIDE_WEIGHT = 0.7

private fun plainSession(
    input: PlanBuildInput,
    day: Int,
    key: SessionTextKey,
    km: Int,
    intensity: SessionIntensity,
    args: List<Int> = emptyList(),
): TrainingSession = TrainingSession(
    day = PLAN_WEEKDAY_CODES[day],
    title = input.texts.training.sessionTitle(key, args, null),
    description = input.texts.training.sessionDescription(key, args),
    targetKm = km,
    intensity = intensity,
    durationMin = input.minutesFor(km.toDouble(), intensity),
    textKey = key,
    textArgs = args,
)

private fun timedSession(
    input: PlanBuildInput,
    day: Int,
    key: SessionTextKey,
    minutes: Int,
    intensity: SessionIntensity,
    args: List<Int> = emptyList(),
): TrainingSession = TrainingSession(
    day = PLAN_WEEKDAY_CODES[day],
    title = input.texts.training.sessionTitle(key, args, null),
    description = input.texts.training.sessionDescription(key, args),
    targetKm = input.kmForMinutes(minutes, intensity),
    intensity = intensity,
    durationMin = minutes,
    textKey = key,
    textArgs = args,
)

private fun workoutSession(input: PlanBuildInput, day: Int, shape: WorkoutShape): TrainingSession =
    timedSession(input, day, SessionTextKey.WORKOUT, shape.totalMin, SessionIntensity.HART, shape.args())

/** Die lange Tour; mit [racePace] (Bloecke, Minuten) samt Zieltempo-Abschnitten. */
private fun longRideSession(input: PlanBuildInput, day: Int, km: Int, racePace: Pair<Int, Int>?): TrainingSession {
    val climbing = if (input.hilly) 1 else 0
    val args = if (racePace == null) {
        listOf(climbing)
    } else {
        val minutes = input.minutesFor(km.toDouble(), SessionIntensity.GRUNDLAGE)
        // Die Bloecke duerfen hoechstens ein Drittel der Fahrt sein — sonst ist es keine Grundlagenfahrt mehr.
        var (blocks, blockMin) = racePace
        while (blocks * blockMin > minutes / 3 && blocks > 1) blocks--
        if (blocks * blockMin > minutes / 3) blockMin = max(5, minutes / 3)
        listOf(climbing, blocks, blockMin, targetSpeedTenths(input.goal))
    }
    return plainSession(input, day, SessionTextKey.LONG_RIDE, km, SessionIntensity.GRUNDLAGE, args)
}

/** Zielschnitt in Zehntel km/h aus Distanz und Zielzeit; `-1` ohne Zielzeit. */
private fun targetSpeedTenths(goal: Goal): Int {
    val minutes = goal.targetDurationMin ?: return -1
    if (minutes <= 0 || !goal.distanceKm.isFinite() || goal.distanceKm <= 0) return -1
    val speed = goal.distanceKm / (minutes / 60.0)
    return if (speed in minPlausibleAvgSpeedKmh..maxPlausibleAvgSpeedKmh) (speed * 10).roundToInt() else -1
}

// ---------------------------------------------------------------------------
// Wochenbau: Rezept, Zusammensetzen, Deckel
// ---------------------------------------------------------------------------

/** Was eine Woche ausser ihren Zahlen ausmacht (je Wochenart fest). */
private class WeekStyle(
    /** [SessionTextKey.LONG_RIDE] oder [SessionTextKey.RECOVERY_LONG]. */
    val longKey: SessionTextKey,
    val easyKeys: List<SessionTextKey>,
    val easyIntensity: SessionIntensity,
    /** Obergrenze der uebrigen Fahrten in Minuten; `null` = nur „nie laenger als die lange Tour". */
    val easyMaxMin: Int?,
    val longWeight: Double,
    /** Obergrenze der langen Tour in km. */
    val longCapKm: Int?,
    /** Gewuenschte Untergrenze der langen Tour in km (Aufbau eines Eventplans). */
    val longFloorKm: Int? = null,
    val racePace: Pair<Int, Int>? = null,
    /** Die lange Tour muss die laengste Einheit sein (Aufbau eines Eventplans). */
    val longMustBeLongest: Boolean = false,
    /**
     * Was die uebrigen Fahrten wegen ihrer Obergrenze nicht tragen, geht an die
     * lange Tour (bis zu deren Obergrenze) — in Erholung und Taper, damit der
     * Umfangsfaktor der Woche gilt. Im Aufbau nicht: Dort waechst die lange
     * Tour ueber ihr Gewicht und ihre Untergrenze, nicht ueber Reste.
     */
    val spillToLong: Boolean = false,
)

/** Deckel einer Woche: Zeit (ohne fest reservierte Minuten), Last, Kilometer. */
private class WeekLimits(val budgetMin: Double?, val loadCap: Double?, val maxKm: Int?)

/**
 * Was beim Kuerzen zuerst nachgibt.
 *
 *  * [REPS]: Wiederholungen der Qualitaet (×0,75, bis [MIN_REDUCED_REPS]),
 *  * [ENDURANCE]: Umfang der uebrigen Grundlagen-/Regenerationsfahrten — unter
 *    [MIN_SESSION_MIN] wird eine Fahrt gestrichen,
 *  * [QUALITY]: eine Qualitaetseinheit streichen,
 *  * [LONG]: die lange Tour kuerzen (bis [MIN_SESSION_MIN]).
 */
private enum class WeekCut { REPS, ENDURANCE, QUALITY, LONG }

/** Eventplan im Aufbau: lange Tour zuletzt (sie traegt das Ziel). */
private val EVENT_BUILD_CUTS = listOf(WeekCut.REPS, WeekCut.ENDURANCE, WeekCut.QUALITY, WeekCut.LONG)

/** Fitnessblock, Erholung und Taper: Intensitaet zuletzt (sie ist der Reiz bzw. bleibt im Taper). */
private val VOLUME_FIRST_CUTS = listOf(WeekCut.ENDURANCE, WeekCut.LONG, WeekCut.REPS, WeekCut.QUALITY)

/** Wunschzahlen einer Woche: Qualitaet, lange Tour, Topf der uebrigen Fahrten (km). */
private data class Recipe(val quality: List<WorkoutShape>, val longKm: Int, val poolKm: Int)

/** Eine der uebrigen Fahrten einer Woche (nicht lange Tour, nicht Qualitaet). */
private class Slot(val day: Int, val role: Role, val intensity: SessionIntensity, val weight: Double, val maxMin: Int?)

/** Ergebnis des Zusammensetzens samt den Groessen, an denen das Kuerzen ansetzt. */
private class Composed(
    val sessions: List<TrainingSession>,
    val longKm: Int,
    val longMinutes: Int,
    val enduranceKm: Int,
    val qualityMinutesMax: Int,
)

private fun slotsFor(days: List<Int>, longDay: Int, qualityDays: List<Int>, style: WeekStyle): List<Slot> {
    val recoveryDay = (longDay + 1) % 7
    return days.filter { it != longDay && it !in qualityDays }.map { day ->
        val recovery = day == recoveryDay && days.size >= 4 && style.easyKeys.size == 1
        if (recovery) {
            Slot(day, Role.RECOVERY, SessionIntensity.LOCKER, RECOVERY_RIDE_WEIGHT, RECOVERY_RIDE_MAX_MIN)
        } else {
            Slot(day, Role.ENDURANCE, style.easyIntensity, 1.0, style.easyMaxMin)
        }
    }
}

/**
 * Die Reihenfolge, in der Fahrten bleiben: Grundlage vor Regeneration, Tage
 * weit weg von der langen Tour vor nahen (die Woche bleibt verteilt).
 */
private fun keepOrder(slots: List<Slot>, longDay: Int): List<Slot> =
    slots.sortedWith(
        compareBy<Slot> { if (it.role == Role.RECOVERY) 1 else 0 }
            .thenByDescending { circularGap(it.day, longDay) }
            .thenBy { it.day },
    )

/**
 * Die Wunschzahlen einer Woche aus ihrem Wunschumfang [wantedKm]: Qualitaet
 * mit ihrer Struktur, der Rest nach Gewichten auf lange Tour und uebrige
 * Fahrten — so, dass moeglichst viele uebrige Fahrten ihre Mindestdauer
 * erreichen; dann die Untergrenze der langen Tour (Eventaufbau), dann ihre
 * Obergrenze.
 */
private fun initialRecipe(
    input: PlanBuildInput,
    days: List<Int>,
    longDay: Int,
    style: WeekStyle,
    quality: List<WorkoutShape>,
    wantedKm: Double,
): Recipe {
    val qualityDays = chooseQualityDays(days, longDay, quality.size)
    val shapes = quality.take(qualityDays.size)
    val qualityKm = shapes.sumOf { input.kmForMinutes(it.totalMin, SessionIntensity.HART) }
    val volume = max(0.0, wantedKm - qualityKm)
    val slots = keepOrder(slotsFor(days, longDay, qualityDays, style), longDay)
    val minLong = input.minKm(SessionIntensity.GRUNDLAGE)

    // So viele uebrige Fahrten wie moeglich mit Mindestdauer — die lange Tour gibt dafuer ab.
    val fitting = (slots.size downTo 0).firstOrNull { k ->
        val mins = slots.take(k).map { input.minKm(it.intensity) }
        mins.sum() + max(minLong, mins.maxOrNull() ?: 0) <= volume
    } ?: 0
    var long = volume * style.longWeight / (style.longWeight + slots.take(fitting).sumOf { it.weight })
    val neededPool = slots.take(fitting).sumOf { input.minKm(it.intensity) }.toDouble()
    if (volume - long < neededPool) long = volume - neededPool
    style.longFloorKm?.let { floorKm ->
        val bounded = min(floorKm.toDouble(), min(wantedKm * LONG_RIDE_MAX_WEEK_SHARE, volume * LONG_RIDE_MAX_SHARE))
        long = max(long, bounded)
    }
    long = max(long, minLong.toDouble())
    style.longCapKm?.let { long = min(long, max(it, minLong).toDouble()) }
    val longKm = long.roundToInt()
    return Recipe(shapes, longKm, max(0, (volume - longKm).roundToInt()))
}

/**
 * Setzt eine Woche aus Tagen, Rollen und den Zahlen von [recipe] zusammen.
 *
 * Qualitaetseinheiten haben ihre Laenge aus der Struktur. Die lange Tour hat
 * mindestens [MIN_SESSION_MIN] und hoechstens [WeekStyle.longCapKm]; muss sie
 * die laengste Einheit sein, holt sie sich dafuer Umfang aus dem Topf der
 * uebrigen Fahrten. Jede uebrige Fahrt bekommt zuerst ihre Mindestdauer
 * (passen nicht alle, werden welche gestrichen — [keepOrder]), der Rest geht
 * nach Gewichten an sie, jede hoechstens so lang wie die lange Tour und
 * [Slot.maxMin]. Was danach uebrig bleibt, wird **nicht** geplant.
 */
private fun compose(
    input: PlanBuildInput,
    days: List<Int>,
    longDay: Int,
    style: WeekStyle,
    recipe: Recipe,
): Composed {
    val qualityDays = chooseQualityDays(days, longDay, recipe.quality.size)
    val qualitySessions = qualityDays.zip(recipe.quality).map { (day, shape) -> workoutSession(input, day, shape) }
    val qualityMax = qualitySessions.maxOfOrNull { it.durationMin ?: 0 } ?: 0

    val minLong = input.minKm(SessionIntensity.GRUNDLAGE)
    val longCap = style.longCapKm?.let { max(it, minLong) } ?: Int.MAX_VALUE
    var pool = recipe.poolKm
    var longKm = recipe.longKm
    if (longKm < minLong) {
        pool = max(0, pool - (minLong - longKm))
        longKm = minLong
    }
    if (longKm > longCap) {
        pool += longKm - longCap
        longKm = longCap
    }
    if (style.longMustBeLongest && qualityMax > 0) {
        val need = input.kmAtLeastMinutes(qualityMax, SessionIntensity.GRUNDLAGE)
        if (need > longKm) {
            val add = minOf(need - longKm, pool, longCap - longKm).coerceAtLeast(0)
            longKm += add
            pool -= add
        }
    }
    var longMinutes = input.minutesFor(longKm.toDouble(), SessionIntensity.GRUNDLAGE)

    // Uebrige Fahrten: Mindestdauer zuerst, nie laenger als die lange Tour.
    val kept = keepOrder(slotsFor(days, longDay, qualityDays, style), longDay).toMutableList()
    fun capKm(slot: Slot) = input.kmAtMostMinutes(min(slot.maxMin ?: Int.MAX_VALUE, longMinutes), slot.intensity)
    kept.removeAll { capKm(it) < input.minKm(it.intensity) }
    while (kept.isNotEmpty() && kept.sumOf { input.minKm(it.intensity) } > pool) kept.removeAt(kept.lastIndex)
    val alloc = DoubleArray(kept.size) { input.minKm(kept[it].intensity).toDouble() }
    val caps = IntArray(kept.size) { capKm(kept[it]) }
    var rest = pool - alloc.sum()
    val active = kept.indices.toMutableSet()
    while (rest > 1e-9 && active.isNotEmpty()) {
        val weightSum = active.sumOf { kept[it].weight }
        for (i in active) alloc[i] += rest * kept[i].weight / weightSum
        rest = 0.0
        for (i in active.toList()) {
            if (alloc[i] > caps[i]) {
                rest += alloc[i] - caps[i]
                alloc[i] = caps[i].toDouble()
                active.remove(i)
            }
        }
    }
    if (style.spillToLong && rest > 0 && longKm < longCap) {
        val spill = min(floor(rest).toInt(), longCap - longKm)
        longKm += spill
        longMinutes = input.minutesFor(longKm.toDouble(), SessionIntensity.GRUNDLAGE)
    }
    // Ganze km mit groesstem Rest, ohne die Kappen zu reissen.
    val km = IntArray(kept.size) { floor(alloc[it]).toInt() }
    var missing = (alloc.sum() + 1e-9).toInt() - km.sum()
    for (i in kept.indices.sortedByDescending { alloc[it] - km[it] }) {
        if (missing <= 0) break
        if (km[i] < caps[i] && alloc[i] - km[i] > 1e-9) {
            km[i]++
            missing--
        }
    }

    val longSession = when {
        longMinutes < LONG_RIDE_MIN_MIN ->
            plainSession(input, longDay, enduranceKey(input.level), longKm, SessionIntensity.GRUNDLAGE)
        style.longKey == SessionTextKey.LONG_RIDE -> longRideSession(input, longDay, longKm, style.racePace)
        else -> plainSession(input, longDay, style.longKey, longKm, SessionIntensity.GRUNDLAGE)
    }
    var easyCounter = 0
    val volumeSessions = kept.sortedBy { it.day }.map { slot ->
        val k = km[kept.indexOf(slot)]
        when (slot.role) {
            Role.RECOVERY -> plainSession(input, slot.day, recoveryKey(input.level), k, SessionIntensity.LOCKER)
            else -> {
                var key = style.easyKeys[easyCounter % style.easyKeys.size]
                easyCounter++
                // „Kurze lockere Ausfahrt" nur, wenn sie kurz ist.
                if (key == SessionTextKey.TAPER_SHORT_EASY &&
                    input.minutesFor(k.toDouble(), slot.intensity) > TAPER_SHORT_MAX_MIN
                ) {
                    key = SessionTextKey.TAPER_EASY_SURGES
                }
                plainSession(input, slot.day, key, k, slot.intensity)
            }
        }
    }
    val sessions = (qualitySessions + longSession + volumeSessions).sortedBy { PLAN_WEEKDAY_CODES.indexOf(it.day) }
    return Composed(sessions, longKm, longMinutes, km.sum(), qualityMax)
}

/** Ueberschreitungen einer Woche gegen [limits]; `null`, wenn alles passt. */
private class Excess(val minutes: Double, val load: Double, val km: Int, val longNotLongest: Boolean) {
    val volume: Boolean get() = minutes > 0 || load > 0 || km > 0
}

private fun excessOf(input: PlanBuildInput, composed: Composed, limits: WeekLimits, style: WeekStyle): Excess? {
    val draft = WeekDraft(composed.sessions, input.hilly)
    val minutes = limits.budgetMin?.let { draft.minutes - it }?.takeIf { it > 1e-9 } ?: 0.0
    val load = limits.loadCap?.let { draft.load - it }?.takeIf { it > 1e-9 } ?: 0.0
    val km = limits.maxKm?.let { draft.km - it }?.takeIf { it > 0 } ?: 0
    val longRule = style.longMustBeLongest && composed.qualityMinutesMax > composed.longMinutes
    return if (minutes > 0 || load > 0 || km > 0 || longRule) Excess(minutes, load, km, longRule) else null
}

/** Wie viele km einer Fahrt der Intensitaet [intensity] die Ueberschreitung [excess] ausmachen (mindestens 1). */
private fun cutKm(input: PlanBuildInput, excess: Excess, intensity: SessionIntensity): Int {
    val speed = input.speed(intensity)
    val f = input.steadyIf(intensity)
    val byTime = excess.minutes / 60 * speed
    val byLoad = excess.load / (f * f * 100) * speed
    return max(1, ceil(maxOf(byTime, byLoad, excess.km.toDouble()) - 1e-9).toInt())
}

/**
 * Baut eine Woche und haelt dabei Zeitbudget, Lastdeckel und Kilometerdeckel
 * ein — durch **weniger**, nie durch Skalieren der Zahlen. Gekuerzt wird in
 * der Reihenfolge [cuts] (siehe [WeekCut]); reine Kilometer-Ueberschreitungen
 * (Rundung, Mindestdauern) nimmt zuerst der Umfang. Muss die lange Tour die
 * laengste Einheit sein und ist sie es nicht, werden Wiederholungen gekuerzt
 * und notfalls die Qualitaet gestrichen.
 *
 * Im Aufbau eines Eventplans bekommt am Ende die lange Tour, was vom
 * Wunschumfang unverplant blieb ([refillLong]).
 *
 * **Untergrenze:** Ist nichts mehr zu kuerzen — eine einzige Fahrt mit
 * [MIN_SESSION_MIN] Minuten —, bleibt diese Woche stehen, auch wenn sie
 * Budget oder Lastdeckel ueberschreitet (siehe [MIN_SESSION_MIN]).
 */
private fun fitWeek(
    input: PlanBuildInput,
    days: List<Int>,
    longDay: Int,
    style: WeekStyle,
    quality: List<WorkoutShape>,
    wantedKm: Double,
    limits: WeekLimits,
    cuts: List<WeekCut>,
): List<TrainingSession> {
    var recipe = initialRecipe(input, days, longDay, style, quality, wantedKm)
    val minLong = input.minKm(SessionIntensity.GRUNDLAGE)
    var composed = compose(input, days, longDay, style, recipe)
    repeat(400) {
        val excess = excessOf(input, composed, limits, style)
            ?: return if (style.longMustBeLongest) refillLong(input, days, longDay, style, composed, limits) else composed.sessions
        val order = when {
            !excess.volume -> listOf(WeekCut.REPS, WeekCut.QUALITY)
            excess.minutes <= 0 && excess.load <= 0 -> listOf(WeekCut.ENDURANCE, WeekCut.LONG, WeekCut.REPS, WeekCut.QUALITY)
            else -> cuts
        }
        val next = order.firstNotNullOfOrNull { cut ->
            when (cut) {
                // Weniger Qualitaet gibt ihren Umfang an die Fahrten zurueck: neu verteilt.
                WeekCut.REPS -> if (recipe.quality.any { it.reps > MIN_REDUCED_REPS }) {
                    initialRecipe(input, days, longDay, style, recipe.quality.map { it.reduced(0.75) }, wantedKm)
                } else {
                    null
                }
                WeekCut.ENDURANCE -> if (excess.volume && composed.enduranceKm > 0) {
                    recipe.copy(
                        longKm = composed.longKm,
                        poolKm = max(0, composed.enduranceKm - cutKm(input, excess, style.easyIntensity)),
                    )
                } else {
                    null
                }
                WeekCut.QUALITY -> if (recipe.quality.isNotEmpty()) {
                    initialRecipe(input, days, longDay, style, recipe.quality.dropLast(1), wantedKm)
                } else {
                    null
                }
                WeekCut.LONG -> if (excess.volume && composed.longKm > minLong) {
                    recipe.copy(
                        longKm = max(minLong, composed.longKm - cutKm(input, excess, SessionIntensity.GRUNDLAGE)),
                        poolKm = composed.enduranceKm,
                    )
                } else {
                    null
                }
            }
        } ?: return composed.sessions
        recipe = next
        composed = compose(input, days, longDay, style, recipe)
    }
    return composed.sessions
}

/**
 * Im Aufbau eines Eventplans: Was vom Wunschumfang nicht verplant ist (weil
 * Grundlagenfahrten gestrichen oder gekappt wurden) und was Budget und
 * Lastdeckel noch hergeben, bekommt die lange Tour — hoechstens bis zu ihrer
 * Untergrenze `(minLongestRideShare + 0,05) × Zieldistanz` und ihrer
 * Obergrenze. Gestrichene Fahrten sollen nicht die Fahrt kleiner lassen, die
 * das Ziel traegt; mehr als die Machbarkeit braucht, bekommt sie so nicht.
 */
private fun refillLong(
    input: PlanBuildInput,
    days: List<Int>,
    longDay: Int,
    style: WeekStyle,
    composed: Composed,
    limits: WeekLimits,
): List<TrainingSession> {
    val draft = WeekDraft(composed.sessions, input.hilly)
    val speed = input.speed(SessionIntensity.GRUNDLAGE)
    val f = input.steadyIf(SessionIntensity.GRUNDLAGE)
    val bySlack = listOfNotNull(
        limits.budgetMin?.let { (it - draft.minutes) / 60 * speed },
        limits.loadCap?.let { (it - draft.load) / (f * f * 100) * speed },
        limits.maxKm?.let { (it - draft.km).toDouble() },
        style.longCapKm?.let { (it - composed.longKm).toDouble() },
        (style.longFloorKm ?: 0).let { (it - composed.longKm).toDouble() },
    )
    var delta = floor(bySlack.minOrNull() ?: 0.0).toInt()
    while (delta >= 1) {
        val grown = compose(input, days, longDay, style, Recipe(
            quality = composed.sessions.mapNotNull { if (it.textKey == SessionTextKey.WORKOUT) parseWorkoutArgs(it.textArgs) else null },
            longKm = composed.longKm + delta,
            poolKm = composed.enduranceKm,
        ))
        if (excessOf(input, grown, limits, style) == null) return grown.sessions
        delta--
    }
    return composed.sessions
}

/** Wie viele Einheiten in eine Woche passen: Raster gegen Tage und Zeitbudget. */
private fun sessionCountFor(input: PlanBuildInput, raster: Int, days: List<Int> = input.available, reservedMin: Int = 0): Int =
    minOf(raster, days.size, input.budgetSessionCount(reservedMin)).coerceAtLeast(1)

/** Die Fahrt am Tag der langen Tour (welcher Titel auch immer sie traegt). */
private fun longDaySession(sessions: List<TrainingSession>, longDay: Int): TrainingSession? =
    sessions.firstOrNull { it.day == PLAN_WEEKDAY_CODES[longDay] && it.intensity != SessionIntensity.HART }

private fun buildAufbauWeek(input: PlanBuildInput, state: PlanBuildState, phase: TrainingPhase): List<TrainingSession> {
    val progress = if (input.buildCount > 1) state.buildSeen.toDouble() / (input.buildCount - 1) else 1.0
    val wanted = input.startKm + (input.peakKm - input.startKm) * progress
    val capKm = if (state.holdNextBuild) state.lastBuildKm else rampCapKm(state.lastBuildKm)
    state.holdNextBuild = false
    val weekKm = round5AtMost(wanted, capKm)

    val count = sessionCountFor(input, rasterSessionCount(input.level, weekKm.toDouble()))
    val kinds = qualityKindsFor(input, phase, state.buildSeen)
    val days = chooseTrainingDays(input.available, count, input.longDay, min(maxQualityFor(count), kinds.size))
    val maxQuality = min(maxQualityFor(days.size), chooseQualityDays(days, input.longDay, 2).size)
    val offset = levelStepOffset(input.level)
    val quality = kinds.take(maxQuality).map { (kind, hill) ->
        workoutAtStep(kind, offset + (state.workoutUses[kind] ?: 0), hill)
    }

    val event = input.goal.type == GoalType.EVENT
    val racePaceActive = event && (
        phase == TrainingPhase.SPEZIFISCH ||
            (phase == TrainingPhase.AUFBAU && (input.goal.targetDurationMin != null || input.goalUnderVolume))
        )
    val racePace = if (racePaceActive) racePaceAtStep(state.racePaceUses) else null

    val loadCap = input.ctl?.let { ctl -> LOAD_RAMP_CAP * max(7 * ctl, state.lastBuildLoad ?: 0.0) }
    val goalKm = input.goal.distanceKm.takeIf { it.isFinite() && it > 0 }
    // Die lange Tour soll bei vollem Aufbau die Machbarkeitsschwelle sicher nehmen …
    val longFloor = if (event && goalKm != null) (goalKm * (minLongestRideShare + LONG_RIDE_FLOOR_MARGIN)).roundToInt() else null
    // … aber nie laenger als ein sinnvoller Anteil des Events werden.
    val longCap = when {
        event && goalKm != null -> floor(goalKm * LONG_RIDE_MAX_GOAL_SHARE).toInt()
        event -> null
        else -> input.kmAtMostMinutes(FITNESS_LONG_RIDE_MAX_MIN, SessionIntensity.GRUNDLAGE)
    }
    val style = WeekStyle(
        longKey = SessionTextKey.LONG_RIDE,
        easyKeys = listOf(enduranceKey(input.level)),
        easyIntensity = SessionIntensity.GRUNDLAGE,
        easyMaxMin = ENDURANCE_RIDE_MAX_MIN,
        longWeight = longRideWeight(input.level),
        longCapKm = longCap,
        longFloorKm = longFloor,
        racePace = racePace,
        longMustBeLongest = event,
    )
    val sessions = fitWeek(
        input, days, input.longDay, style, quality, weekKm.toDouble(),
        WeekLimits(input.budgetMin, loadCap, weekKm),
        if (event) EVENT_BUILD_CUTS else VOLUME_FIRST_CUTS,
    )

    state.plannedQuality = quality.isNotEmpty()
    return sessions
}

/**
 * Schreibt eine fertige Aufbauwoche [sessions] in den Zustand — **nach** der
 * Pruefung der harten Tage gegen die Vorwochen, damit Rampe, Erholung und
 * Taper mit den Kilometern rechnen, die tatsaechlich im Plan stehen.
 */
private fun recordBuildWeek(input: PlanBuildInput, state: PlanBuildState, sessions: List<TrainingSession>) {
    val draft = WeekDraft(sessions, input.hilly)
    val usedShapes = sessions.mapNotNull { if (it.textKey == SessionTextKey.WORKOUT) parseWorkoutArgs(it.textArgs) else null }
    for (shape in usedShapes) {
        state.workoutUses[shape.kind] = (state.workoutUses[shape.kind] ?: 0) + 1
    }
    if (sessions.any { it.textKey == SessionTextKey.LONG_RIDE && (it.textArgs.getOrNull(LongRideArg.BLOCKS) ?: 0) > 0 }) {
        state.racePaceUses++
    }
    // Die Erholungs- und Taperwochen halten die Intensitaet des Aufbaus; ohne
    // Qualitaet in dieser Woche bleibt die zuletzt gefahrene Qualitaet der Bezug.
    if (usedShapes.isNotEmpty() || !state.plannedQuality) state.lastQuality = usedShapes
    state.buildSeen++
    state.lastBuildKm = draft.km.toDouble()
    state.achievedPeakKm = max(state.achievedPeakKm ?: 0.0, draft.km.toDouble())
    state.lastBuildLoad = draft.load
    state.lastBuildSessionCount = sessions.size
    state.lastBuildLongKm = longDaySession(sessions, input.longDay)?.targetKm ?: 0
    state.longestBuildLongKm = max(state.longestBuildLongKm, state.lastBuildLongKm)
}

private fun buildErholungWeek(input: PlanBuildInput, state: PlanBuildState): List<TrainingSession> {
    val weekKm = round5(state.lastBuildKm * RECOVERY_FACTOR)
    val reference = if (state.lastBuildSessionCount > 0) state.lastBuildSessionCount else rasterSessionCount(input.level, weekKm.toDouble())
    val count = sessionCountFor(input, max(2, reference - 1))
    // Ein kurzer Reiz mit etwa halbem Umfang — „Intensitaet bleibt" (assessDeload).
    val quality = state.lastQuality.take(min(1, maxQualityFor(count))).map { it.reduced(0.5) }
    val days = chooseTrainingDays(input.available, count, input.longDay, quality.size)
    val timeCap = input.kmAtMostMinutes(TAPER_LONG_RIDE_MAX_MIN, SessionIntensity.GRUNDLAGE)
    val longCap = min(state.lastBuildLongKm.takeIf { it > 0 }?.let { floor(it * RECOVERY_LONG_SHARE).toInt() } ?: timeCap, timeCap)
    val style = WeekStyle(
        longKey = SessionTextKey.RECOVERY_LONG,
        easyKeys = listOf(SessionTextKey.RECOVERY_EASY_RIDE, SessionTextKey.RECOVERY_CALM_LOOP),
        easyIntensity = SessionIntensity.LOCKER,
        easyMaxMin = EASY_RIDE_MAX_MIN,
        longWeight = 1.2,
        longCapKm = longCap,
        spillToLong = true,
    )
    return fitWeek(
        input, days, input.longDay, style, quality, weekKm.toDouble(),
        WeekLimits(input.budgetMin, null, weekKm), VOLUME_FIRST_CUTS,
    )
}

private fun buildTaperWeek(input: PlanBuildInput, state: PlanBuildState, index: Int): List<TrainingSession> {
    val taperCount = input.kinds.count { it == WeekKind.TAPER }
    val position = input.kinds.subList(0, index).count { it == WeekKind.TAPER }
    val twoWeeks = taperCount >= 2
    val factor = if (twoWeeks) TWO_WEEK_TAPER_FACTORS[min(position, 1)] else SINGLE_TAPER_FACTOR
    val longShare = if (twoWeeks) TWO_WEEK_TAPER_LONG_SHARE[min(position, 1)] else SINGLE_TAPER_LONG_SHARE
    val repsFactor = if (twoWeeks && position == 0) 0.6 else 0.5
    state.taperSeen++

    // Der Taper rechnet mit dem erreichten Spitzenumfang, nicht mit dem Startumfang.
    val weekKm = round5((state.achievedPeakKm ?: input.startKm) * factor)

    // Letzte Taperwoche vor einem Montags-/Dienstagsevent: die Tage direkt vor
    // dem Event tragen weder lange Tour noch Qualitaet; vor einem Montagsevent
    // liegt die Aktivierung am Sonntag dieser Woche.
    val beforeEvent = input.goal.type == GoalType.EVENT && input.kinds.getOrNull(index + 1) == WeekKind.ZIELWOCHE
    val blocked = if (beforeEvent) (0..6).filter { it >= input.goalDay + 5 } else emptyList()
    val activationDay = if (beforeEvent && input.goalDay == 0 && (!input.explicitDays || 6 in input.available)) 6 else null
    val reserved = if (activationDay != null) ACTIVATION_MIN else 0
    val activation = activationDay?.let {
        listOf(timedSession(input, it, SessionTextKey.ACTIVATION, ACTIVATION_MIN, SessionIntensity.LOCKER))
    }.orEmpty()
    val available = input.available.filter { it !in blocked }
    // Kein Platz mehr neben der Aktivierung: dann ist sie die Woche.
    val budgetLeft = input.budgetMin?.let { it - reserved }
    if (available.isEmpty() || (activation.isNotEmpty() && budgetLeft != null && budgetLeft < MIN_SESSION_MIN)) return activation

    val reference = if (state.lastBuildSessionCount > 0) state.lastBuildSessionCount else rasterSessionCount(input.level, weekKm.toDouble())
    // Haeufigkeit halten (Mujika & Padilla 2003: hoechstens −20 %) — gekuerzt wird die Einheit, nicht ihre Zahl.
    val count = sessionCountFor(input, reference, available, reserved)
    val quality = state.lastQuality.take(maxQualityFor(count)).map { it.reduced(repsFactor) }
    val longDay = input.longDayIn(available)
    val days = chooseTrainingDays(available, count, longDay, quality.size)
    val shareCap = state.longestBuildLongKm.takeIf { it > 0 }?.let { (it * longShare).roundToInt() }
    val goalCap = input.goal.distanceKm.takeIf { input.goal.type == GoalType.EVENT && it.isFinite() && it > 0 }
        ?.let { floor(it * LONG_RIDE_MAX_GOAL_SHARE).toInt() }
    val timeCap = min(input.kmAtMostMinutes(TAPER_LONG_RIDE_MAX_MIN, SessionIntensity.GRUNDLAGE), goalCap ?: Int.MAX_VALUE)
    val style = WeekStyle(
        longKey = SessionTextKey.LONG_RIDE,
        easyKeys = listOf(SessionTextKey.TAPER_EASY_SURGES, SessionTextKey.TAPER_SHORT_EASY),
        easyIntensity = SessionIntensity.LOCKER,
        easyMaxMin = EASY_RIDE_MAX_MIN,
        longWeight = 1.6,
        longCapKm = min(shareCap ?: timeCap, timeCap),
        spillToLong = true,
    )
    val sessions = fitWeek(
        input, days, longDay, style, quality, weekKm.toDouble(),
        WeekLimits(input.budgetMin?.let { it - reserved }, null, weekKm), VOLUME_FIRST_CUTS,
    )
    return (sessions + activation).sortedBy { PLAN_WEEKDAY_CODES.indexOf(it.day) }
}

/** Das Zielevent als Einheit. */
internal fun eventSession(goal: Goal, texts: CoreTexts, baseSpeed: Double = fallbackGravelSpeedKmh): TrainingSession {
    val eventIndex = dartLocalOf(goal.date).dayOfWeek.value - 1
    val eventKm = max(1, dartRound(goal.distanceKm).toInt())
    val ascentM = goal.ascentM
    // Hoehenmeter nur als Argument, wenn der Anstiegshinweis greift; sonst −1.
    val args = listOf(
        eventKm,
        if (ascentM != null && ascentM >= CLIMB_HINT_THRESHOLD_M) dartRound(ascentM).toInt() else -1,
    )
    // Last des Events: Renntempo-IF ueber die geschaetzte Fahrzeit — eine
    // Pruefung, kein budgetierter Reiz; nur fuer die CTL-Vorschau.
    val hours = eventKm / max(baseSpeed * intensitySpeedFactor(SessionIntensity.GRUNDLAGE), 1.0)
    return TrainingSession(
        day = PLAN_WEEKDAY_CODES[eventIndex],
        title = texts.training.sessionTitle(SessionTextKey.GOAL_EVENT, args, goal.name),
        description = texts.training.sessionDescription(SessionTextKey.GOAL_EVENT, args),
        targetKm = eventKm,
        intensity = SessionIntensity.HART,
        // Keine Dauerangabe: Wie lange das Event dauert, entscheidet der Renntag.
        durationMin = null,
        // Das Event hat eine eigene Strecke — dafuer wird nie eine Runde generiert.
        isEvent = true,
        targetLoad = max(1.0, dartRound(hours * RACE_PACE_IF * RACE_PACE_IF * 100)),
        textKey = SessionTextKey.GOAL_EVENT,
        textArgs = args,
    )
}

/**
 * Ob ein Tag [day] dieser Woche direkt auf eine lange Tour oder eine harte
 * Einheit am Sonntag der Vorwoche [previous] folgt.
 */
private fun followsPreviousStress(day: Int, previous: List<TrainingSession>): Boolean =
    day == 0 && previous.any {
        it.day == PLAN_WEEKDAY_CODES[6] && (isLongRideSession(it) || it.intensity == SessionIntensity.HART)
    }

/** Nimmt aus [candidates] (nach Vorrang) die Einheiten, die zusammen in [budgetMin] passen. */
private fun withinBudget(candidates: List<TrainingSession>, budgetMin: Double?): List<TrainingSession> {
    if (budgetMin == null) return candidates
    var used = 0
    return candidates.filter { s ->
        val m = s.durationMin ?: 0
        (used + m <= budgetMin + 1e-9).also { if (it) used += m }
    }
}

/**
 * Die Zielwoche: kurze Vorbelastung mit gleicher Intensitaet und wenigen
 * Wiederholungen 3–5 Tage vorher (nie am Montag nach einer langen oder harten
 * Sonntagsfahrt der Vorwoche [previous]), bei grossem Raster eine lockere
 * Ausfahrt, Aktivierung 1–2 Tage vorher, dann das Event. Das Zeitbudget gilt
 * auch hier (ohne das Event): Zuerst faellt die lockere Ausfahrt, dann die
 * Vorbelastung; die Aktivierung bleibt (notfalls auf das Budget, mindestens
 * [MIN_SESSION_MIN], gekuerzt). Vor einem Montagsevent liegt die Aktivierung
 * am Sonntag der Taperwoche ([buildTaperWeek]).
 */
private fun buildEventWeek(input: PlanBuildInput, state: PlanBuildState, previous: List<TrainingSession>): List<TrainingSession> {
    val event = eventSession(input.goal, input.texts, input.baseSpeed)
    val eventDay = PLAN_WEEKDAY_CODES.indexOf(event.day)
    val allowed = if (input.explicitDays) input.available else (0..6).toList()
    val before = allowed.filter { it < eventDay }

    val activationDay = before.filter { it >= eventDay - 2 }.maxOrNull()
    val qualityDay = before.filter { it in (eventDay - 5)..(eventDay - 3) && !followsPreviousStress(it, previous) }.maxOrNull()
        ?.takeIf { state.lastQuality.isNotEmpty() }
    val wantsEasy = rasterSessionCount(input.level, state.lastBuildKm) >= 4
    val easyDay = if (wantsEasy) {
        before.filter { it != activationDay && it <= eventDay - 2 && (qualityDay == null || it > qualityDay) }
            .maxOrNull()
    } else {
        null
    }

    val budget = input.budgetMin
    val candidates = mutableListOf<TrainingSession>()
    if (activationDay != null) {
        val minutes = if (budget != null && budget < ACTIVATION_MIN) max(MIN_SESSION_MIN, floor(budget).toInt()) else ACTIVATION_MIN
        candidates += timedSession(input, activationDay, SessionTextKey.ACTIVATION, minutes, SessionIntensity.LOCKER)
    }
    if (qualityDay != null) {
        val shape = state.lastQuality.first().let { it.copy(reps = min(it.reps, MIN_REDUCED_REPS)) }
        candidates += workoutSession(input, qualityDay, shape)
    }
    if (easyDay != null) {
        candidates += timedSession(input, easyDay, SessionTextKey.TAPER_SHORT_EASY, EVENT_WEEK_EASY_MIN, SessionIntensity.LOCKER)
    }
    val fitting = if (candidates.size == 1) candidates else withinBudget(candidates, budget)
    return (fitting + event).sortedBy { PLAN_WEEKDAY_CODES.indexOf(it.day) }
}

/**
 * Die Testwoche eines Fitnessblocks: Der Block endet am Zieltag
 * ([Goal.date], siehe [fitnessGoal]) mit der **Vergleichsrunde** — wie das
 * Event immer an diesem Tag, auch wenn er kein Trainingstag ist. Der
 * **20-min-Test** (Coggan: FTP ≈ 95 % des 20-min-Mittels) liegt an einem
 * Trainingstag mindestens zwei Tage davor (bevorzugt drei), nie am Montag nach
 * einer langen oder harten Sonntagsfahrt; davor eine lockere Ausfahrt. Beide
 * sind nicht als Event markiert, fuer beide darf eine Runde generiert werden.
 * Nichts liegt nach dem Zieltag.
 *
 * Gibt es vor dem Zieltag keinen passenden Trainingstag (z. B. nur Sa/So
 * erlaubt) oder passt der Test nicht mehr ins Zeitbudget, entfaellt er — was
 * die Woche enthaelt, sagt [fitnessTestWeekContents]. Das Zeitbudget gilt fuer
 * Test, lockere Ausfahrt und eine vom Plan vorgeschlagene Vergleichsrunde
 * (Vorrang in dieser Reihenfolge: Vergleichsrunde, Test, lockere Ausfahrt);
 * eine vom Ziel festgelegte Vergleichsrunde ist wie das Event davon
 * ausgenommen.
 */
private fun buildTestWeek(input: PlanBuildInput, state: PlanBuildState, previous: List<TrainingSession>): List<TrainingSession> {
    val benchDay = input.goalDay
    val testDay = input.available
        .filter { it <= benchDay - 2 && !followsPreviousStress(it, previous) }
        .sortedWith(compareBy<Int> { abs(it - (benchDay - 3)) }.thenByDescending { it })
        .firstOrNull()
    val easyDay = testDay?.let { t -> input.available.filter { it <= t - 2 }.maxOrNull() }

    val fixedKm = input.goal.distanceKm
        .takeIf { it.isFinite() && it in FITNESS_BENCHMARK_MIN_KM..FITNESS_BENCHMARK_MAX_KM }?.roundToInt()
    val budget = input.budgetMin
    val sessions = mutableListOf<TrainingSession>()
    var used = 0
    // Vorrang: die Vergleichsrunde (sie ist der Zieltag), dann der Test, dann die lockere Ausfahrt.
    val benchKm = fixedKm ?: run {
        val wanted = (state.lastBuildKm * 0.35).roundToInt().coerceIn(15, 60)
        val room = budget?.let { input.kmAtMostMinutes(floor(it).toInt(), SessionIntensity.HART) }
        max(if (room != null) min(wanted, room) else wanted, input.minKm(SessionIntensity.HART))
    }
    val bench = plainSession(input, benchDay, SessionTextKey.BENCHMARK_RIDE, benchKm, SessionIntensity.HART, listOf(benchKm))
    sessions += bench
    if (fixedKm == null) used += bench.durationMin ?: 0
    val testTotal = FTP_TEST_WARMUP_MIN + FTP_TEST_MIN + FTP_TEST_COOLDOWN_MIN
    val withTest = testDay != null && (budget == null || used + testTotal <= budget + 1e-9)
    if (withTest && testDay != null) {
        sessions += timedSession(
            input,
            testDay,
            SessionTextKey.FTP_TEST,
            testTotal,
            SessionIntensity.HART,
            listOf(FTP_TEST_WARMUP_MIN, FTP_TEST_MIN, FTP_TEST_COOLDOWN_MIN, testTotal),
        )
        used += testTotal
    }
    if (withTest && easyDay != null && (budget == null || used + EVENT_WEEK_EASY_MIN <= budget + 1e-9)) {
        sessions += timedSession(input, easyDay, SessionTextKey.TAPER_SHORT_EASY, EVENT_WEEK_EASY_MIN, SessionIntensity.LOCKER)
    }
    return sessions.sortedBy { PLAN_WEEKDAY_CODES.indexOf(it.day) }
}

/** Was die Testwoche eines Fitnessblocks enthaelt (siehe [fitnessTestWeekContents]). */
data class FitnessTestWeekContents(
    /** Ob der 20-min-Test eingeplant ist. */
    val hasFtpTest: Boolean,
    /** Ob die Vergleichsrunde eingeplant ist. */
    val hasBenchmarkRide: Boolean,
)

/**
 * Was die Testwoche (letzte Woche) eines Fitnessblocks enthaelt; `null` fuer
 * Eventplaene oder einen Plan ohne Wochen. Der 20-min-Test entfaellt, wenn vor
 * dem Zieltag kein passender Trainingstag liegt (siehe `buildTestWeek`) —
 * die Oberflaeche kann dann darauf hinweisen, statt ihn stillschweigend zu
 * verschweigen.
 */
fun fitnessTestWeekContents(plan: TrainingPlan): FitnessTestWeekContents? {
    if (plan.goal.type != GoalType.FITNESS) return null
    val week = plan.weeks.lastOrNull() ?: return null
    return FitnessTestWeekContents(
        hasFtpTest = week.sessions.any { it.textKey == SessionTextKey.FTP_TEST },
        hasBenchmarkRide = week.sessions.any { it.textKey == SessionTextKey.BENCHMARK_RIDE },
    )
}

// ---------------------------------------------------------------------------
// Harte Tage ueber den ganzen Plan
// ---------------------------------------------------------------------------

/** Hoechstens so viele harte Tage je rollierende 7 Tage — wie `MAX_HARD_DAYS_PER_7D` der App. */
internal const val PLAN_MAX_HARD_DAYS_PER_7D = 2

/**
 * Prueft die harten Tage ueber den ganzen Plan (auch ueber Wochengrenzen)
 * und macht aus jeder Qualitaetseinheit, die eine Regel verletzen wuerde,
 * eine Grundlagenfahrt gleicher Dauer. Das Event, die Tests der Testwoche und
 * die harten Einheiten eingefrorener Wochen ([frozen]) bleiben, wie sie sind.
 *
 * Regeln: hoechstens [PLAN_MAX_HARD_DAYS_PER_7D] harte Tage in jedem Fenster
 * von 7 Tagen, nie zwei harte Tage nebeneinander, nie ein harter Tag direkt
 * vor oder direkt nach der langen Tour. Danach bekommt jede Einheit ihre Last
 * aus der Struktur.
 */
internal fun enforceHardDayRules(
    input: PlanBuildInput,
    weeks: List<List<TrainingSession>>,
    frozen: (Int) -> Boolean,
): List<List<TrainingSession>> {
    fun absDay(week: Int, session: TrainingSession) = week * 7 + PLAN_WEEKDAY_CODES.indexOf(session.day)

    val fixedHard = sortedSetOf<Int>()
    val longDays = mutableSetOf<Int>()
    // Fest: das Event und die Tests der Testwoche — sie sind der Zweck des Plans.
    fun isFixed(s: TrainingSession) =
        s.isEvent || s.textKey == SessionTextKey.FTP_TEST || s.textKey == SessionTextKey.BENCHMARK_RIDE
    weeks.forEachIndexed { w, sessions ->
        sessions.forEach { s ->
            if (isFixed(s) || (frozen(w) && s.intensity == SessionIntensity.HART)) fixedHard += absDay(w, s)
            if (isLongRideSession(s)) longDays += absDay(w, s)
        }
    }
    val kept = sortedSetOf<Int>().apply { addAll(fixedHard) }
    val result = weeks.mapIndexed { w, sessions ->
        if (frozen(w)) return@mapIndexed sessions
        sessions.map { s ->
            if (isFixed(s) || s.intensity != SessionIntensity.HART) return@map s
            val d = absDay(w, s)
            val adjacent = (d - 1) in kept || (d + 1) in kept
            val nextToLong = (d + 1) in longDays || (d - 1) in longDays
            val windowFull = (d - 6..d).any { start ->
                kept.count { it in start..start + 6 } + 1 > PLAN_MAX_HARD_DAYS_PER_7D
            }
            if (adjacent || nextToLong || windowFull) {
                downgradeToEndurance(input, s)
            } else {
                kept += d
                s
            }
        }
    }
    return result.mapIndexed { w, sessions ->
        if (frozen(w)) sessions else sessions.map { attachStructuredLoad(it, input.hilly) }
    }
}

/** Eine Qualitaetseinheit, die keinen Platz hat, wird zur Grundlagenfahrt gleicher Dauer. */
private fun downgradeToEndurance(input: PlanBuildInput, session: TrainingSession): TrainingSession {
    val minutes = session.durationMin ?: input.minutesFor(session.targetKm.toDouble(), SessionIntensity.GRUNDLAGE)
    val key = enduranceKey(input.level)
    return TrainingSession(
        day = session.day,
        title = input.texts.training.sessionTitle(key, emptyList(), null),
        description = input.texts.training.sessionDescription(key, emptyList()),
        targetKm = input.kmForMinutes(minutes, SessionIntensity.GRUNDLAGE),
        intensity = SessionIntensity.GRUNDLAGE,
        durationMin = minutes,
        textKey = key,
    )
}

/** Haengt die Last aus der Struktur an (das Event behaelt seine). */
internal fun attachStructuredLoad(session: TrainingSession, hilly: Boolean): TrainingSession =
    if (session.isEvent) session else session.copy(targetLoad = structuredSessionLoad(session, hilly))

/**
 * Die Qualitaet einer bereits geplanten Einheit als [WorkoutShape] — auch aus
 * Altplaenen: [SessionTextKey.INTERVALS] (vor dem Planumbau) wird zur
 * Schwelleneinheit mit derselben Wiederholungszahl und Belastungsdauer, eine
 * harte Einheit ohne lesbare Struktur zur Schwelleneinheit auf der Stufe
 * [usedBefore]. `null` fuer alles, was keine Qualitaetseinheit ist.
 */
private fun plannedQualityShape(session: TrainingSession, usedBefore: Int): WorkoutShape? = when {
    session.isEvent -> null
    session.textKey == SessionTextKey.WORKOUT -> parseWorkoutArgs(session.textArgs)
    session.textKey == SessionTextKey.FTP_TEST || session.textKey == SessionTextKey.BENCHMARK_RIDE -> null
    session.textKey == SessionTextKey.INTERVALS && session.textArgs.size >= 3 &&
        session.textArgs[1] in 1..20 && session.textArgs[2] in 1..60 ->
        WorkoutShape(WorkoutKind.THRESHOLD, session.textArgs[1].coerceAtLeast(MIN_REDUCED_REPS), session.textArgs[2])
    session.intensity == SessionIntensity.HART -> workoutAtStep(WorkoutKind.THRESHOLD, usedBefore)
    else -> null
}

/**
 * Der Bauzustand vor der ersten neu zu rechnenden Woche, gelesen aus den
 * bereits geplanten Wochen [before] (fuer [adaptPlan]): wie viele
 * Aufbauwochen und Qualitaetseinheiten es schon gab, welche Qualitaet zuletzt
 * gefahren werden sollte und wie lang die laengste lange Tour war — auch fuer
 * Altplaene mit Intervallen alten Formats ([plannedQualityShape]).
 * [startKm] ist der tatsaechlich erreichte Umfang, von dem neu aufgebaut wird;
 * der Spitzenumfang fuer den Taper kommt nur aus den **neu gebauten**
 * Aufbauwochen (ohne solche: [startKm]) — die alten Planzahlen sind nicht
 * erreicht worden.
 */
internal fun planBuildStateBefore(before: List<TrainingWeek>, startKm: Double): PlanBuildState {
    val builds = before.filter { it.kind == WeekKind.AUFBAU }
    val lastBuild = builds.lastOrNull()
    val state = PlanBuildState(
        buildSeen = builds.size,
        lastBuildKm = startKm,
        lastBuildSessionCount = lastBuild?.sessions?.size ?: 0,
        longestBuildLongKm = builds.flatMap { it.sessions }
            .filter { !it.isEvent && it.intensity != SessionIntensity.HART }.maxOfOrNull { it.targetKm } ?: 0,
        lastBuildLongKm = lastBuild?.sessions?.filter { !it.isEvent && it.intensity != SessionIntensity.HART }
            ?.maxOfOrNull { it.targetKm } ?: 0,
    )
    for (week in builds) {
        val shapes = week.sessions.mapNotNull { s ->
            plannedQualityShape(s, state.workoutUses[WorkoutKind.THRESHOLD] ?: 0)
        }
        shapes.forEach { state.workoutUses[it.kind] = (state.workoutUses[it.kind] ?: 0) + 1 }
        if (week.sessions.any { it.textKey == SessionTextKey.LONG_RIDE && (it.textArgs.getOrNull(LongRideArg.BLOCKS) ?: 0) > 0 }) {
            state.racePaceUses++
        }
        if (shapes.isNotEmpty()) state.lastQuality = shapes
    }
    return state
}
