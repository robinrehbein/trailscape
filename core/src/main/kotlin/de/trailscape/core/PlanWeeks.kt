package de.trailscape.core

import de.trailscape.core.i18n.CoreTexts
import de.trailscape.core.i18n.PLAN_WEEKDAY_CODES
import de.trailscape.core.i18n.SessionTextKey
import kotlin.math.abs
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
 *  3. **Tage und Rollen** ([chooseTrainingDays], [chooseQualityDays]): auf
 *     verfuegbare Tage verteilt, harte Tage nie nebeneinander und nie am Tag
 *     vor der langen Tour.
 *  4. **Einheiten** mit fester Struktur (Qualitaet) und verteilbarem Umfang
 *     (Grundlage, lange Tour); Dauer aus dem persoenlichen Tempo, Last aus der
 *     Struktur ([structuredSessionLoad]).
 *  5. **Deckel**: Zeitbudget und Lastrampe (≤ 1,30 × Referenz). Greift einer,
 *     wird der **Umfang** gekuerzt und neu gebaut — die Zahlen werden nie
 *     skaliert, damit Last, Dauer und Kilometer eine Wahrheit bleiben.
 *  6. **Pruefung harter Tage** ueber den ganzen Plan ([enforceHardDayRules]):
 *     hoechstens zwei harte Tage je 7 Tage, nie zwei nebeneinander, nie vor der
 *     langen Tour — eine Einheit, die das verletzen wuerde, wird zur
 *     Grundlagenfahrt gleicher Dauer.
 */

internal const val MIN_WEEKS = 3
internal const val MAX_WEEKS = 52

/**
 * Laufzeit eines Fitnessblocks in Wochen. Unter vier Wochen ist kein
 * Belastungsrhythmus samt Testwoche moeglich; ueber 16 Wochen ist ein Block
 * ohne Event kein Block mehr, sondern eine Saison — dafuer ist ein Eventziel
 * (oder ein zweiter Block) das ehrlichere Werkzeug (Faustregel).
 */
internal const val FITNESS_MIN_WEEKS = 4
internal const val FITNESS_MAX_WEEKS = 16

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

/** Kuerzeste sinnvolle Einheit in km — darunter lohnt das Umziehen nicht. */
private const val MIN_SESSION_KM = 5

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
 * Waehlt [count] Tage aus [available], immer inklusive [longDay], so
 * gleichmaessig wie moeglich ueber die Woche verteilt (groesster kleinster
 * Abstand, zyklisch gerechnet — die Woche wiederholt sich).
 */
internal fun chooseTrainingDays(available: List<Int>, count: Int, longDay: Int): List<Int> {
    val others = available.filter { it != longDay }
    val k = (count - 1).coerceIn(0, others.size)
    val best = combinations(others, k).maxWithOrNull(
        compareBy<List<Int>> { combo ->
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
 * Regeln: nie die lange Tour, nie der Tag davor, zwei harte Tage nie
 * nebeneinander (zyklisch — der Sonntag liegt neben dem naechsten Montag).
 * Unter den erlaubten Kombinationen gewinnt die mit dem groessten Abstand,
 * der Tag direkt nach der langen Tour wird gemieden, solange es geht. Passt
 * keine Kombination, werden es weniger Qualitaetseinheiten.
 */
internal fun chooseQualityDays(days: List<Int>, longDay: Int, count: Int): List<Int> {
    val dayBefore = (longDay + 6) % 7
    val dayAfter = (longDay + 1) % 7
    val candidates = days.filter { it != longDay && it != dayBefore }
    for (k in min(count, candidates.size) downTo 1) {
        val valid = combinations(candidates, k).filter { combo ->
            combo.all { a -> combo.all { b -> a == b || circularGap(a, b) > 1 } }
        }
        val best = valid.maxWithOrNull(
            compareBy<List<Int>> { combo -> if (dayAfter in combo) 0 else 1 }
                .thenBy { combo -> combo.minOf { circularGap(it, longDay) } }
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
    val longDay: Int = run {
        val wanted = preferences?.validLongRideDay()
        when {
            wanted != null && wanted in available -> wanted
            5 in available -> 5
            6 in available -> 6
            else -> available.last()
        }
    }
    val budgetMin: Double? =
        if (applyTimeBudget) preferences?.validWeeklyHours()?.let { it * 60 } else null
    val ctl: Double? = currentCtl?.takeIf { it.isFinite() && it > 0 }
    val buildCount: Int = kinds.count { it == WeekKind.AUFBAU }

    /** Ziel unter dem heutigen Umfang: Fortschritt kommt dann ueber Qualitaet. */
    val goalUnderVolume: Boolean = goal.type == GoalType.EVENT && peakKm <= startKm * 1.05

    fun speed(intensity: SessionIntensity): Double = max(baseSpeed * intensitySpeedFactor(intensity), 1.0)
    fun minutesFor(km: Double, intensity: SessionIntensity): Int =
        max(1, (km / speed(intensity) * 60).roundToInt())
    fun kmForMinutes(minutes: Int, intensity: SessionIntensity): Int =
        max(1, (minutes / 60.0 * speed(intensity)).roundToInt())
}

/** Zustand, der von Woche zu Woche weitergereicht wird. */
internal class PlanBuildState(
    var buildSeen: Int = 0,
    var lastBuildKm: Double,
    var achievedPeakKm: Double,
    var lastBuildLoad: Double? = null,
    var lastBuildSessionCount: Int = 0,
    var longestBuildLongKm: Int = 0,
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
 * Baut die Einheiten aller Wochen ab [fromIndex]; davor stehen unveraendert
 * [priorWeeks] (fuer die Pruefung der harten Tage ueber die Wochengrenze).
 *
 * [state] ist der Zustand vor Woche [fromIndex] — fuer einen neuen Plan der
 * Startzustand, fuer [adaptPlan] der aus den bereits gefahrenen Wochen.
 */
internal fun buildPlanWeekSessions(
    input: PlanBuildInput,
    state: PlanBuildState = PlanBuildState(lastBuildKm = input.startKm, achievedPeakKm = input.startKm),
    fromIndex: Int = 0,
    priorWeeks: List<List<TrainingSession>> = emptyList(),
): List<List<TrainingSession>> {
    val weeks = ArrayList<List<TrainingSession>>(input.kinds.size)
    for (i in input.kinds.indices) {
        if (i < fromIndex) {
            weeks.add(priorWeeks.getOrElse(i) { emptyList() })
            continue
        }
        weeks.add(buildWeek(input, state, i))
    }
    return enforceHardDayRules(input, weeks, fromIndex)
}

private fun buildWeek(input: PlanBuildInput, state: PlanBuildState, index: Int): List<TrainingSession> {
    val kind = input.kinds[index]
    val phase = input.phases[index]
    return when (kind) {
        WeekKind.AUFBAU -> buildAufbauWeek(input, state, phase)
        WeekKind.ERHOLUNG -> buildErholungWeek(input, state)
        WeekKind.TAPER -> buildTaperWeek(input, state, index)
        WeekKind.ZIELWOCHE -> if (input.goal.type == GoalType.FITNESS) {
            buildTestWeek(input, state)
        } else {
            buildEventWeek(input, state)
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
 * besteht). Damit nimmt ein Plan, der
 * seinen Spitzenumfang erreicht, die Machbarkeitsschwelle in jeder Stufe,
 * auch wenn zwei Qualitaetseinheiten einen Teil des Umfangs binden; ohne
 * genug Umfang greift die Obergrenze, und die Machbarkeit sagt ehrlich, dass
 * es nicht reicht.
 */
private const val LONG_RIDE_FLOOR_MARGIN = 0.05
private const val LONG_RIDE_MAX_SHARE = 0.7
private const val LONG_RIDE_MAX_WEEK_SHARE = 0.5

/** Gewicht einer Regenerations-/Kompensationsfahrt beim Verteilen. */
private const val RECOVERY_RIDE_WEIGHT = 0.7

/** Verteilt [totalKm] nach [weights] auf ganze km, jede Einheit mindestens [MIN_SESSION_KM]. */
private fun distributeKm(totalKm: Double, weights: List<Double>): List<Int> {
    val sum = weights.sum()
    if (sum <= 0) return weights.map { MIN_SESSION_KM }
    return weights.map { max(MIN_SESSION_KM, (max(totalKm, 0.0) * it / sum).roundToInt()) }
}

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

/**
 * Setzt eine Woche aus Tagen, Rollen und Umfang zusammen.
 *
 * Qualitaetseinheiten haben ihre Laenge aus der Struktur; der restliche
 * Umfang [weekKm] geht nach Gewichten an lange Tour, Grundlage und
 * Regeneration. [longCapKm] begrenzt die lange Tour (Taper), [longKey]
 * erlaubt die verkuerzte Variante der Erholungswoche.
 */
private fun composeWeek(
    input: PlanBuildInput,
    days: List<Int>,
    quality: List<WorkoutShape>,
    weekKm: Double,
    longKey: SessionTextKey,
    easyKeys: List<SessionTextKey>,
    easyIntensity: SessionIntensity,
    longWeight: Double,
    longCapKm: Int? = null,
    racePace: Pair<Int, Int>? = null,
    longFloorKm: Int? = null,
): List<TrainingSession> {
    val longDay = if (input.longDay in days) input.longDay else days.last()
    val qualityDays = chooseQualityDays(days, longDay, quality.size)
    val roles = days.associateWith { day ->
        when {
            day == longDay -> Role.LONG
            day in qualityDays -> Role.QUALITY
            day == (longDay + 1) % 7 && days.size >= 4 && easyKeys.size == 1 -> Role.RECOVERY
            else -> Role.ENDURANCE
        }
    }
    val qualityByDay = qualityDays.zip(quality).toMap()
    val qualitySessions = qualityByDay.map { (day, shape) -> workoutSession(input, day, shape) }
    val volumeKm = weekKm - qualitySessions.sumOf { it.targetKm }

    val volumeDays = days.filter { roles[it] != Role.QUALITY }
    val weights = volumeDays.map { day ->
        when (roles.getValue(day)) {
            Role.LONG -> longWeight
            Role.RECOVERY -> RECOVERY_RIDE_WEIGHT
            else -> 1.0
        }
    }
    val km = distributeKm(volumeKm, weights).toMutableList()
    val longIndex = volumeDays.indexOf(longDay)
    if (longCapKm != null && longIndex >= 0 && km[longIndex] > longCapKm) {
        km[longIndex] = max(MIN_SESSION_KM, longCapKm)
    }
    // Untergrenze der langen Tour (Aufbau): Der Rest wird anteilig von den
    // uebrigen Grundlagenfahrten genommen, nie unter deren Mindestlaenge.
    val floor = longFloorKm?.coerceAtMost(
        min(weekKm * LONG_RIDE_MAX_WEEK_SHARE, max(volumeKm, 0.0) * LONG_RIDE_MAX_SHARE).roundToInt(),
    )
    if (floor != null && longIndex >= 0 && km[longIndex] < floor) {
        var missing = floor - km[longIndex]
        for (j in km.indices.sortedByDescending { km[it] }) {
            if (j == longIndex || missing <= 0) continue
            val take = min(missing, km[j] - MIN_SESSION_KM).coerceAtLeast(0)
            km[j] -= take
            km[longIndex] += take
            missing -= take
        }
    }

    var easyCounter = 0
    val volumeSessions = volumeDays.mapIndexed { i, day ->
        when (roles.getValue(day)) {
            Role.LONG -> if (longKey == SessionTextKey.LONG_RIDE) {
                longRideSession(input, day, km[i], racePace)
            } else {
                plainSession(input, day, longKey, km[i], SessionIntensity.GRUNDLAGE)
            }
            Role.RECOVERY -> plainSession(input, day, recoveryKey(input.level), km[i], SessionIntensity.LOCKER)
            else -> {
                val key = easyKeys[easyCounter % easyKeys.size]
                easyCounter++
                plainSession(input, day, key, km[i], easyIntensity)
            }
        }
    }
    return (qualitySessions + volumeSessions).sortedBy { PLAN_WEEKDAY_CODES.indexOf(it.day) }
}

/**
 * Baut eine Woche ueber [compose] und haelt dabei Zeitbudget und Lastdeckel
 * ein — durch **weniger Umfang**, nie durch Skalieren der Zahlen. Reicht das
 * nicht (die Qualitaet allein sprengt das Budget), werden die Wiederholungen
 * gekuerzt und zuletzt die Qualitaet gestrichen.
 */
private fun fitWeek(
    input: PlanBuildInput,
    wantedKm: Double,
    quality: List<WorkoutShape>,
    loadCap: Double?,
    compose: (km: Double, quality: List<WorkoutShape>) -> List<TrainingSession>,
): List<TrainingSession> {
    val budget = input.budgetMin
    var km = wantedKm
    var shapes = quality
    var best: List<TrainingSession>? = null
    repeat(24) {
        val sessions = compose(km, shapes)
        val draft = WeekDraft(sessions, input.hilly)
        val timeRatio = if (budget != null && draft.minutes > budget) budget / draft.minutes else 1.0
        val loadRatio = if (loadCap != null && draft.load > loadCap) loadCap / draft.load else 1.0
        best = sessions
        if (timeRatio >= 1.0 && loadRatio >= 1.0) return sessions
        val minimalKm = sessions.filter { it.intensity != SessionIntensity.HART }.all { it.targetKm <= MIN_SESSION_KM }
        if (!minimalKm) {
            val next = floor1(km * min(timeRatio, loadRatio) * 0.97)
            km = if (next >= km) km - MIN_WEEKLY_STEP_KM else next
        } else if (shapes.any { it.reps > MIN_REDUCED_REPS }) {
            shapes = shapes.map { it.reduced(0.75) }
        } else if (shapes.isNotEmpty()) {
            shapes = shapes.dropLast(1)
        } else {
            return sessions
        }
    }
    return best ?: compose(km, shapes)
}

private fun floor1(value: Double): Double = kotlin.math.floor(value)

/** Wie viele Einheiten in eine Woche passen: Raster gegen verfuegbare Tage. */
private fun sessionCountFor(input: PlanBuildInput, raster: Int): Int = min(raster, input.available.size).coerceAtLeast(1)

private fun buildAufbauWeek(input: PlanBuildInput, state: PlanBuildState, phase: TrainingPhase): List<TrainingSession> {
    val progress = if (input.buildCount > 1) state.buildSeen.toDouble() / (input.buildCount - 1) else 1.0
    val wanted = input.startKm + (input.peakKm - input.startKm) * progress
    val capKm = if (state.holdNextBuild) state.lastBuildKm else rampCapKm(state.lastBuildKm)
    state.holdNextBuild = false
    val weekKm = round5AtMost(wanted, capKm).toDouble()

    val count = sessionCountFor(input, rasterSessionCount(input.level, weekKm))
    val days = chooseTrainingDays(input.available, count, input.longDay)
    val maxQuality = min(maxQualityFor(days.size), chooseQualityDays(days, input.longDay, 2).size)
    val offset = levelStepOffset(input.level)
    val quality = qualityKindsFor(input, phase, state.buildSeen).take(maxQuality).map { (kind, hill) ->
        workoutAtStep(kind, offset + (state.workoutUses[kind] ?: 0), hill)
    }

    val racePaceActive = input.goal.type == GoalType.EVENT && (
        phase == TrainingPhase.SPEZIFISCH ||
            (phase == TrainingPhase.AUFBAU && (input.goal.targetDurationMin != null || input.goalUnderVolume))
        )
    val racePace = if (racePaceActive) racePaceAtStep(state.racePaceUses) else null

    val loadCap = input.ctl?.let { ctl -> LOAD_RAMP_CAP * max(7 * ctl, state.lastBuildLoad ?: 0.0) }
    // Die lange Tour soll bei vollem Aufbau die Machbarkeitsschwelle sicher nehmen.
    val longFloor = if (input.goal.type == GoalType.EVENT && input.goal.distanceKm > 0) {
        (input.goal.distanceKm * (minLongestRideShare + LONG_RIDE_FLOOR_MARGIN)).roundToInt()
    } else {
        null
    }
    val sessions = fitWeek(input, weekKm, quality, loadCap) { km, shapes ->
        composeWeek(
            input = input,
            days = days,
            quality = shapes,
            weekKm = km,
            longKey = SessionTextKey.LONG_RIDE,
            easyKeys = listOf(enduranceKey(input.level)),
            easyIntensity = SessionIntensity.GRUNDLAGE,
            longWeight = longRideWeight(input.level),
            racePace = racePace,
            longFloorKm = longFloor,
        )
    }

    val draft = WeekDraft(sessions, input.hilly)
    val usedShapes = sessions.mapNotNull { if (it.textKey == SessionTextKey.WORKOUT) parseWorkoutArgs(it.textArgs) else null }
    for (shape in usedShapes) {
        state.workoutUses[shape.kind] = (state.workoutUses[shape.kind] ?: 0) + 1
    }
    if (sessions.any { it.textKey == SessionTextKey.LONG_RIDE && (it.textArgs.getOrNull(LongRideArg.BLOCKS) ?: 0) > 0 }) {
        state.racePaceUses++
    }
    state.lastQuality = usedShapes
    state.buildSeen++
    state.lastBuildKm = draft.km.toDouble()
    state.achievedPeakKm = max(state.achievedPeakKm, draft.km.toDouble())
    state.lastBuildLoad = draft.load
    state.lastBuildSessionCount = sessions.size
    state.longestBuildLongKm = max(
        state.longestBuildLongKm,
        sessions.filter { isLongRideSession(it) }.maxOfOrNull { it.targetKm } ?: 0,
    )
    return sessions
}

private fun buildErholungWeek(input: PlanBuildInput, state: PlanBuildState): List<TrainingSession> {
    val weekKm = round5(state.lastBuildKm * RECOVERY_FACTOR).toDouble()
    val reference = if (state.lastBuildSessionCount > 0) state.lastBuildSessionCount else rasterSessionCount(input.level, weekKm)
    val count = sessionCountFor(input, max(2, reference - 1))
    val days = chooseTrainingDays(input.available, count, input.longDay)
    // Ein kurzer Reiz mit etwa halbem Umfang — „Intensitaet bleibt" (assessDeload).
    val quality = state.lastQuality.take(min(1, maxQualityFor(days.size))).map { it.reduced(0.5) }
    return fitWeek(input, weekKm, quality, loadCap = null) { km, shapes ->
        composeWeek(
            input = input,
            days = days,
            quality = shapes,
            weekKm = km,
            longKey = SessionTextKey.RECOVERY_LONG,
            easyKeys = listOf(SessionTextKey.RECOVERY_EASY_RIDE, SessionTextKey.RECOVERY_CALM_LOOP),
            easyIntensity = SessionIntensity.LOCKER,
            longWeight = 1.2,
        )
    }
}

private fun buildTaperWeek(input: PlanBuildInput, state: PlanBuildState, index: Int): List<TrainingSession> {
    val taperCount = input.kinds.count { it == WeekKind.TAPER }
    val position = input.kinds.subList(0, index).count { it == WeekKind.TAPER }
    val twoWeeks = taperCount >= 2
    val factor = if (twoWeeks) TWO_WEEK_TAPER_FACTORS[min(position, 1)] else SINGLE_TAPER_FACTOR
    val longShare = if (twoWeeks) TWO_WEEK_TAPER_LONG_SHARE[min(position, 1)] else SINGLE_TAPER_LONG_SHARE
    val repsFactor = if (twoWeeks && position == 0) 0.6 else 0.5

    val weekKm = round5(state.achievedPeakKm * factor).toDouble()
    val reference = if (state.lastBuildSessionCount > 0) state.lastBuildSessionCount else rasterSessionCount(input.level, weekKm)
    // Haeufigkeit halten (Mujika & Padilla 2003: hoechstens −20 %) — gekuerzt wird die Einheit, nicht ihre Zahl.
    val count = sessionCountFor(input, reference)
    val days = chooseTrainingDays(input.available, count, input.longDay)
    val quality = state.lastQuality.take(maxQualityFor(days.size)).map { it.reduced(repsFactor) }
    val longCap = if (state.longestBuildLongKm > 0) {
        max(MIN_SESSION_KM, (state.longestBuildLongKm * longShare).roundToInt())
    } else {
        null
    }
    state.taperSeen++
    return fitWeek(input, weekKm, quality, loadCap = null) { km, shapes ->
        composeWeek(
            input = input,
            days = days,
            quality = shapes,
            weekKm = km,
            longKey = SessionTextKey.LONG_RIDE,
            easyKeys = listOf(SessionTextKey.TAPER_EASY_SURGES, SessionTextKey.TAPER_SHORT_EASY),
            easyIntensity = SessionIntensity.LOCKER,
            longWeight = 1.6,
            longCapKm = longCap,
        )
    }
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
 * Die Zielwoche: kurze Vorbelastung mit gleicher Intensitaet und wenigen
 * Wiederholungen 3–5 Tage vorher, bei grossem Raster eine lockere Ausfahrt,
 * Aktivierung 1–2 Tage vorher, dann das Event. Ein Montags-Event bekommt
 * nichts davor (die Vorwoche ist Taper).
 */
private fun buildEventWeek(input: PlanBuildInput, state: PlanBuildState): List<TrainingSession> {
    val event = eventSession(input.goal, input.texts, input.baseSpeed)
    val eventDay = PLAN_WEEKDAY_CODES.indexOf(event.day)
    val allowed = if (input.explicitDays) input.available else (0..6).toList()
    val before = allowed.filter { it < eventDay }

    val activationDay = before.filter { it >= eventDay - 2 }.maxOrNull()
    val qualityDay = before.filter { it in (eventDay - 5)..(eventDay - 3) }.maxOrNull()
        ?.takeIf { state.lastQuality.isNotEmpty() }
    val wantsEasy = rasterSessionCount(input.level, state.lastBuildKm) >= 4
    val easyDay = if (wantsEasy) {
        before.filter { it != activationDay && it <= eventDay - 2 && (qualityDay == null || it > qualityDay) }
            .maxOrNull()
    } else {
        null
    }

    val sessions = mutableListOf<TrainingSession>()
    if (qualityDay != null) {
        val shape = state.lastQuality.first().let { it.copy(reps = min(it.reps, MIN_REDUCED_REPS)) }
        sessions += workoutSession(input, qualityDay, shape)
    }
    if (easyDay != null) {
        sessions += timedSession(input, easyDay, SessionTextKey.TAPER_SHORT_EASY, EVENT_WEEK_EASY_MIN, SessionIntensity.LOCKER)
    }
    if (activationDay != null) {
        sessions += timedSession(input, activationDay, SessionTextKey.ACTIVATION, ACTIVATION_MIN, SessionIntensity.LOCKER)
    }
    sessions += event
    return sessions.sortedBy { PLAN_WEEKDAY_CODES.indexOf(it.day) }
}

/**
 * Die Testwoche eines Fitnessblocks: zwei lockere Tage, der 20-min-Test
 * (Coggan: FTP ≈ 95 % des 20-min-Mittels) in der Wochenmitte und am Tag der
 * langen Tour die Vergleichsrunde — beide nicht als Event markiert, fuer
 * beide darf eine Runde generiert werden.
 */
private fun buildTestWeek(input: PlanBuildInput, state: PlanBuildState): List<TrainingSession> {
    val allowed = input.available
    val testDay = allowed.filter { it in 1..4 }.minByOrNull { abs(it - 3) } ?: allowed.first()
    val benchDay = allowed.filter { it >= testDay + 2 }.let { later ->
        if (input.longDay in later) input.longDay else later.maxOrNull()
    }
    val easyDay = allowed.filter { it <= testDay - 2 }.maxOrNull()

    val sessions = mutableListOf<TrainingSession>()
    if (easyDay != null) {
        sessions += timedSession(input, easyDay, SessionTextKey.TAPER_SHORT_EASY, EVENT_WEEK_EASY_MIN, SessionIntensity.LOCKER)
    }
    val testTotal = FTP_TEST_WARMUP_MIN + FTP_TEST_MIN + FTP_TEST_COOLDOWN_MIN
    sessions += timedSession(
        input,
        testDay,
        SessionTextKey.FTP_TEST,
        testTotal,
        SessionIntensity.HART,
        listOf(FTP_TEST_WARMUP_MIN, FTP_TEST_MIN, FTP_TEST_COOLDOWN_MIN, testTotal),
    )
    if (benchDay != null) {
        val fixedKm = input.goal.distanceKm.takeIf { it.isFinite() && it in 10.0..120.0 }?.roundToInt()
        val km = fixedKm ?: (state.lastBuildKm * 0.35).roundToInt().coerceIn(15, 60)
        val session = plainSession(input, benchDay, SessionTextKey.BENCHMARK_RIDE, km, SessionIntensity.HART, listOf(km))
        sessions += session
    }
    return sessions.sortedBy { PLAN_WEEKDAY_CODES.indexOf(it.day) }
}

// ---------------------------------------------------------------------------
// Harte Tage ueber den ganzen Plan
// ---------------------------------------------------------------------------

/** Hoechstens so viele harte Tage je rollierende 7 Tage — wie `MAX_HARD_DAYS_PER_7D` der App. */
internal const val PLAN_MAX_HARD_DAYS_PER_7D = 2

/**
 * Prueft die harten Tage ueber den ganzen Plan (auch ueber Wochengrenzen)
 * und macht aus jeder Qualitaetseinheit, die eine Regel verletzen wuerde,
 * eine Grundlagenfahrt gleicher Dauer. Das Event bleibt immer, wie es ist.
 *
 * Regeln: hoechstens [PLAN_MAX_HARD_DAYS_PER_7D] harte Tage in jedem Fenster
 * von 7 Tagen, nie zwei harte Tage nebeneinander, nie ein harter Tag direkt
 * vor der langen Tour. Danach bekommt jede Einheit ihre Last aus der Struktur.
 */
internal fun enforceHardDayRules(
    input: PlanBuildInput,
    weeks: List<List<TrainingSession>>,
    fromIndex: Int,
): List<List<TrainingSession>> {
    fun absDay(week: Int, session: TrainingSession) = week * 7 + PLAN_WEEKDAY_CODES.indexOf(session.day)

    val fixedHard = sortedSetOf<Int>()
    val longDays = mutableSetOf<Int>()
    // Fest: das Event und die Tests der Testwoche — sie sind der Zweck des Plans.
    fun isFixed(s: TrainingSession) =
        s.isEvent || s.textKey == SessionTextKey.FTP_TEST || s.textKey == SessionTextKey.BENCHMARK_RIDE
    weeks.forEachIndexed { w, sessions ->
        sessions.forEach { s ->
            if (isFixed(s) || (w < fromIndex && s.intensity == SessionIntensity.HART)) fixedHard += absDay(w, s)
            if (isLongRideSession(s)) longDays += absDay(w, s)
        }
    }
    val kept = sortedSetOf<Int>().apply { addAll(fixedHard) }
    val result = weeks.mapIndexed { w, sessions ->
        if (w < fromIndex) return@mapIndexed sessions
        sessions.map { s ->
            if (isFixed(s) || s.intensity != SessionIntensity.HART) return@map s
            val d = absDay(w, s)
            val adjacent = (d - 1) in kept || (d + 1) in kept
            val beforeLong = (d + 1) in longDays
            val windowFull = (d - 6..d).any { start ->
                kept.count { it in start..start + 6 } + 1 > PLAN_MAX_HARD_DAYS_PER_7D
            }
            if (adjacent || beforeLong || windowFull) {
                downgradeToEndurance(input, s)
            } else {
                kept += d
                s
            }
        }
    }
    return result.mapIndexed { w, sessions ->
        if (w < fromIndex) sessions else sessions.map { attachStructuredLoad(it, input.hilly) }
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
 * Der Bauzustand vor der ersten neu zu rechnenden Woche, gelesen aus den
 * bereits geplanten Wochen [before] (fuer [adaptPlan]): wie viele
 * Aufbauwochen und Qualitaetseinheiten es schon gab, welche Qualitaet zuletzt
 * gefahren werden sollte und wie lang die laengste lange Tour war.
 * [startKm] ist der tatsaechlich erreichte Umfang, von dem neu aufgebaut wird.
 */
internal fun planBuildStateBefore(before: List<TrainingWeek>, startKm: Double): PlanBuildState {
    val builds = before.filter { it.kind == WeekKind.AUFBAU }
    val state = PlanBuildState(
        buildSeen = builds.size,
        lastBuildKm = startKm,
        achievedPeakKm = startKm,
        lastBuildSessionCount = builds.lastOrNull()?.sessions?.size ?: 0,
        longestBuildLongKm = builds.flatMap { it.sessions }.filter { isLongRideSession(it) }.maxOfOrNull { it.targetKm } ?: 0,
    )
    for (week in builds) {
        val shapes = week.sessions.mapNotNull {
            if (it.textKey == SessionTextKey.WORKOUT) parseWorkoutArgs(it.textArgs) else null
        }
        shapes.forEach { state.workoutUses[it.kind] = (state.workoutUses[it.kind] ?: 0) + 1 }
        if (week.sessions.any { it.textKey == SessionTextKey.LONG_RIDE && (it.textArgs.getOrNull(LongRideArg.BLOCKS) ?: 0) > 0 }) {
            state.racePaceUses++
        }
        state.lastQuality = shapes
    }
    return state
}
