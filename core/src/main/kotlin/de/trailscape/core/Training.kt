package de.trailscape.core

import de.trailscape.core.i18n.CoreTexts
import de.trailscape.core.i18n.PLAN_WEEKDAY_CODES
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * Trainingsplan-Generator und -Persistenz.
 *
 * Urspruenglich eine 1:1-Portierung von `lib/training.dart` (Web-App-Referenz
 * training.ts). Das **JSON-Format** ist weiterhin mit Web-App und
 * Sync-Server abgestimmt (Wochenraster Montag–Sonntag lokaler Zeit, nur
 * angehaengte optionale Felder); die **Progression** ist seit dem Planumbau
 * eine eigene: Wochenbau in `PlanWeeks.kt`, Qualitaetseinheiten, Last und
 * Zielbereiche in `PlanWorkouts.kt`, Praeferenzen in `PlanPreferences.kt`.
 * Ein Plan der Web-App bleibt lesbar; ein hier erzeugter Plan ist fuer die
 * Web-App lesbar, rechnet dort aber nicht identisch neu.
 *
 * Der einzige IO-Rand des Dart-Originals ist `SharedPreferences`. Er wird hier
 * ueber [TrainingPlanStore] hereingereicht; die Android-Implementierung folgt
 * in Phase 3.
 */

/** Speicherschluessel des Plans (Dart: `_storageKey`). */
const val trainingPlanStorageKey: String = "trailscape.plan"

/**
 * Die Wochentagskuerzel im Plan-JSON. Ein interner Code (mit Web-App und
 * Sync-Server abgestimmt), keine Anzeige — angezeigt wird ueber
 * `texts.format.weekdayShort/weekdayLong`.
 */
private val weekdays = PLAN_WEEKDAY_CODES

/**
 * Index eines Plan-Wochentagskuerzels (0 = Mo … 6 = So); `-1` bei fremdem
 * Kuerzel. Fuer `TrainingPlanProgress.kt`, damit die Status-Zuordnung mit
 * derselben Tabelle rechnet, aus der [generatePlan] die Kuerzel erzeugt.
 */
internal fun planWeekdayIndex(day: String): Int = weekdays.indexOf(day)

/** Meldung, wenn das Ziel weniger als [MIN_WEEKS] Wochen entfernt liegt. */
fun errorTooSoon(texts: CoreTexts): String = texts.training.errorTooSoon()

/** Meldung, wenn das Ziel mehr als [MAX_WEEKS] Wochen entfernt liegt. */
fun errorTooFar(texts: CoreTexts): String = texts.training.errorTooFar()

/** Meldung, wenn ein Fitnessblock kuerzer als 4 oder laenger als 16 Wochen waere. */
fun errorFitnessBlockLength(texts: CoreTexts): String =
    texts.training.errorFitnessBlockLength(FITNESS_MIN_WEEKS, FITNESS_MAX_WEEKS)

private const val WEEK_MS = 7L * 24 * 60 * 60 * 1000

/**
 * Mindestanteil der Zieldistanz, den die **laengste geplante Fahrt** erreichen
 * muss, damit der Plan das Ziel traegt (0,6 = 60 %).
 *
 * Fuer lange Ausdauerveranstaltungen ist es gaengige Praxis, die volle
 * Zieldistanz **nicht** im Training zu fahren; die laengste Vorbereitungsfahrt
 * liegt ueblicherweise bei rund zwei Dritteln bis drei Vierteln der
 * Zieldistanz, den Rest tragen Wochenvolumen, Taper und Renntag (Faustregel).
 * 60 % ist die untere Kante dieses Korridors: darunter ist der Zieltag kein
 * „laenger als gewohnt" mehr, sondern Neuland. Die Gewichte der langen Tour
 * (`longRideWeight` in `PlanWeeks.kt`) sind so gewaehlt, dass ein Plan, der
 * seinen Peak von [PEAK_DISTANCE_FACTOR] × Zieldistanz **erreicht**, diese
 * Schwelle sicher nimmt.
 */
const val minLongestRideShare: Double = 0.6

/**
 * Persistenz des Trainingsplans — in Dart `SharedPreferences` unter
 * [trainingPlanStorageKey].
 *
 * Bewusst nur ein String-Schluessel: das JSON-Format bleibt damit in [savePlan]
 * bzw. [loadPlan] und ist ohne Plattform testbar.
 */
interface TrainingPlanStore {
    /** Gespeichertes JSON, `null` wenn nichts gespeichert ist. */
    fun read(): String?

    fun write(value: String)

    fun remove()
}

/** Plan nur im Arbeitsspeicher — Vorgabe und Attrappe in Tests. */
class InMemoryTrainingPlanStore(private var value: String? = null) : TrainingPlanStore {
    override fun read(): String? = value

    override fun write(value: String) {
        this.value = value
    }

    override fun remove() {
        value = null
    }
}

/** Montag 00:00 lokaler Zeit der Woche, in der [timestamp] liegt. */
private fun startOfWeek(timestamp: Long): Long {
    val date = dartLocalOf(timestamp)
    // DateTime.weekday: 1 = Montag … 7 = Sonntag
    val offset = date.dayOfWeek.value - 1
    // Kalenderarithmetik statt Duration – dadurch DST-sicher auf 00:00 lokal.
    return dartEpochMs(date.toLocalDate().minusDays(offset.toLong()).atStartOfDay())
}

/** Addiert [weeks] Wochen und bleibt dabei DST-sicher auf 00:00 lokaler Zeit. */
private fun addWeeks(timestamp: Long, weeks: Int): Long {
    val date = dartLocalOf(timestamp)
    return dartEpochMs(date.toLocalDate().plusDays(weeks * 7L).atStartOfDay())
}

/** Index des Wochentags (0 = Mo … 6 = So). */
private fun weekdayIndex(timestamp: Long): Int = dartLocalOf(timestamp).dayOfWeek.value - 1

/**
 * Die Einheiten eines Plans mit [weekCount] Wochen — die eine Stelle, an der
 * [generatePlan], [weeksForFeasibleGoal] und [assessPlanFeasibility] denselben
 * Aufbau rechnen.
 */
internal fun planSessions(
    goal: Goal,
    level: FitnessLevel,
    startKm: Double,
    weekCount: Int,
    currentCtl: Double?,
    preferences: PlanPreferences?,
    speedKmh: Double?,
    texts: CoreTexts,
    applyTimeBudget: Boolean = true,
): List<List<TrainingSession>> {
    val input = PlanBuildInput(
        goal = goal,
        level = level,
        texts = texts,
        speedKmh = speedKmh,
        preferences = preferences,
        currentCtl = currentCtl,
        kinds = planWeekKinds(weekCount, goal.type),
        startKm = startKm,
        peakKm = peakKmFor(goal, startKm),
        applyTimeBudget = applyTimeBudget,
    )
    return buildPlanWeekSessions(input)
}

/**
 * Erzeugt einen Trainingsplan vom Montag der aktuellen Woche bis zur
 * Zielwoche.
 *
 * Wirft [IllegalArgumentException] (Darts `ArgumentError`), wenn ein Eventziel
 * weniger als 3 oder mehr als 52 Wochen entfernt liegt bzw. ein Fitnessblock
 * ([GoalType.FITNESS]) nicht 4–16 Wochen lang ist.
 *
 * ## Was der Plan jetzt beruecksichtigt
 *  * **Eine Wahrheit fuer Last, Dauer und Kilometer**: Jede Einheit bekommt
 *    ihre Dauer aus Kilometern und persoenlichem Tempo ([speedKmh]) bzw. aus
 *    ihrer Struktur, und ihre Last aus genau dieser Struktur
 *    (`PlanWorkouts.kt`). Die Rampe ist ein Deckel, der notfalls Umfang
 *    kuerzt — sie skaliert keine Zahlen.
 *  * **Periodisierung**: Grundlage → Aufbau → spezifisch mit steigender
 *    Belastungszeit, Erholungswochen mit kurzem Intensitaetsreiz, zwei
 *    Taperwochen bei gleicher Intensitaet und Haeufigkeit (`PlanWeeks.kt`).
 *  * **Persoenliches**: Trainingstage, Tag der langen Tour und Zeitbudget aus
 *    [preferences]; sie werden im Plan gespeichert, damit [adaptPlan] mit
 *    denselben Annahmen neu rechnet.
 *
 * **Der Plan sagt nicht, ob er traegt.** Das beantwortet
 * [assessPlanFeasibility] — und die Antwort gehoert vor die Augen der
 * Nutzerin, bevor sie zwoelf Wochen darauf baut.
 *
 * @param currentCtl aktuelle chronische Last (CTL), falls bekannt. Mit ihr ist
 *   die Last jeder Aufbauwoche auf das 1,30-Fache der Referenz (7 × CTL bzw.
 *   letzte Aufbauwoche) gedeckelt; ohne sie gilt nur die km-Rampe.
 * @param preferences Trainingstage, Tag der langen Tour, Zeitbudget; `null` =
 *   Stufenraster ohne Zeitdeckel.
 * @param speedKmh persoenliches Grundtempo in km/h (z. B.
 *   [typicalAvgSpeedKmh]); `null` = [fallbackGravelSpeedKmh].
 */
fun generatePlan(
    goal: Goal,
    assessment: FitnessAssessment,
    now: Long? = null,
    currentCtl: Double? = null,
    preferences: PlanPreferences? = null,
    speedKmh: Double? = null,
    texts: CoreTexts,
): TrainingPlan {
    val nowMs = now ?: System.currentTimeMillis()
    val firstMonday = startOfWeek(nowMs)
    val goalMonday = startOfWeek(goal.date)
    val weekCount = dartRound((goalMonday - firstMonday).toDouble() / WEEK_MS).toInt() + 1

    if (goal.type == GoalType.FITNESS) {
        if (weekCount < FITNESS_MIN_WEEKS || weekCount > FITNESS_MAX_WEEKS) {
            throw IllegalArgumentException(errorFitnessBlockLength(texts))
        }
    } else {
        if (weekCount < MIN_WEEKS) {
            throw IllegalArgumentException(errorTooSoon(texts))
        }
        if (weekCount > MAX_WEEKS) {
            throw IllegalArgumentException(errorTooFar(texts))
        }
    }

    val speed = speedKmh?.takeIf { it.isFinite() && it in minPlausibleAvgSpeedKmh..maxPlausibleAvgSpeedKmh }
    val prefs = preferences?.takeUnless { it.isEmpty }
    val level = assessment.level
    val kinds = planWeekKinds(weekCount, goal.type)
    val sessions = planSessions(
        goal = goal,
        level = level,
        startKm = startKmFor(assessment),
        weekCount = weekCount,
        currentCtl = currentCtl,
        preferences = prefs,
        speedKmh = speed,
        texts = texts,
    )

    val weeks = kinds.indices.map { i ->
        TrainingWeek(
            index = i,
            start = addWeeks(firstMonday, i),
            end = addWeeks(firstMonday, i + 1),
            kind = kinds[i],
            // Das Wochenziel ist die Summe der Einheiten — eine Wahrheit.
            targetKm = sessions[i].sumOf { it.targetKm },
            sessions = sessions[i],
        )
    }

    return TrainingPlan(
        createdAt = nowMs,
        goal = goal,
        level = level,
        weeks = weeks,
        preferences = prefs,
        planSpeedKmh = speed,
    )
}

/**
 * Ein Fitnessziel ([GoalType.FITNESS]) ueber [weeks] Wochen ab [now]: Der
 * Block endet am [testDay] (0 = Mo … 6 = So, Vorgabe Samstag) der letzten
 * Woche mit der Vergleichsrunde. [benchmarkKm] ist die Laenge der
 * Vergleichsrunde; `0` heisst „keine feste Runde, der Plan schlaegt eine
 * vor".
 */
fun fitnessGoal(name: String, weeks: Int, now: Long, benchmarkKm: Double = 0.0, testDay: Int = 5): Goal {
    val monday = startOfWeek(now)
    val lastMonday = addWeeks(monday, max(weeks, 1) - 1)
    val day = dartLocalOf(lastMonday).toLocalDate().plusDays(testDay.coerceIn(0, 6).toLong()).atTime(9, 0)
    return Goal(
        name = name,
        distanceKm = max(benchmarkKm, 0.0),
        ascentM = null,
        date = dartEpochMs(day),
        type = GoalType.FITNESS,
    )
}

// ---------------------------------------------------------------------------
// Plausibilitaet: traegt der Plan das Ziel?
// ---------------------------------------------------------------------------

/**
 * Das Urteil ueber einen Plan: Holt die laengste geplante Fahrt die Zieldistanz
 * ein — und wenn nicht, was waere stattdessen realistisch?
 *
 * Bewusst **kein** Feld von [TrainingPlan]: Das JSON-Format des Plans ist mit
 * Web-App und Sync-Server abgestimmt, und ein Urteil ist ohnehin kein
 * Speicherinhalt, sondern eine Rechnung ueber den gespeicherten Plan.
 */
data class PlanFeasibility(
    /** Laengste geplante Trainingsfahrt in km (ohne das Zielevent selbst). */
    val longestRideKm: Int,
    val goalDistanceKm: Double,
    /** [longestRideKm] / [goalDistanceKm]; 0, wenn die Zieldistanz unsinnig ist. */
    val coverage: Double,
    /** `true`, wenn [coverage] mindestens [minLongestRideShare] erreicht. */
    val feasible: Boolean,
    /**
     * Zieldistanz, die dieser Plan in seiner Laufzeit tatsaechlich truege —
     * `null`, wenn der Plan ohnehin traegt.
     */
    val suggestedDistanceKm: Int?,
    /**
     * Wochen, die es fuer die **gewuenschte** Zieldistanz braeuchte — `null`,
     * wenn der Plan traegt oder selbst ein Jahr Vorbereitung nicht reicht.
     */
    val suggestedWeeks: Int?,
    /** Fertiger Hinweistext in der Sprache der Texte; `null`, wenn es nichts zu sagen gibt. */
    val message: String?,
    /**
     * `true`, wenn das Zeitbudget aus [TrainingPlan.preferences] der Grund ist,
     * warum der Plan nicht traegt: Ohne Budget truege derselbe Plan.
     */
    val budgetLimited: Boolean = false,
    /**
     * Wochenstunden, die der Plan ohne Budget in der Spitze braeuchte — nur
     * bei [budgetLimited].
     */
    val hoursNeededForGoal: Double? = null,
    /**
     * Wie viele Minuten die Zielzeit schneller ist als die Prognose; `null`
     * ohne Zielzeit oder Prognose, oder wenn die Zielzeit realistisch ist.
     */
    val targetTimeGapMin: Int? = null,
    /** Hinweis zur Zielzeit (nur mit [targetTimeGapMin]). */
    val targetTimeMessage: String? = null,
)

/**
 * Prueft, ob [plan] sein eigenes Ziel traegt, und macht sonst einen konkreten
 * Gegenvorschlag.
 *
 * ## Was geprueft wird
 *  * Die **laengste geplante Trainingsfahrt** gegen die Zieldistanz (das
 *    Zielevent zaehlt nicht mit). Liegt das Verhaeltnis unter
 *    [minLongestRideShare], ist der Zieltag kein „laenger als gewohnt",
 *    sondern Neuland. Fitnessbloecke ([GoalType.FITNESS]) haben keine
 *    Zieldistanz und tragen immer.
 *  * Das **Zeitbudget**: Traegt der Plan nicht, wird derselbe Plan ohne
 *    Zeitdeckel gerechnet. Truege er dann, sagt der Hinweis, dass das Budget
 *    der Engpass ist und wie viele Stunden es braeuchte.
 *  * Die **Zielzeit** (mit [prognosis] aus [predictGoalFinish]): Ist sie um
 *    mehr als die Unsicherheit der Prognose (mindestens 5 %) schneller,
 *    entsteht [PlanFeasibility.targetTimeMessage].
 *
 * ## Woher der Gegenvorschlag kommt
 *  * **Machbare Distanz**: die Distanz, zu der die laengste geplante Fahrt
 *    genau [minLongestRideShare] waere, auf 5 km **ab**gerundet.
 *  * **Noetige Wochen**: derselbe Aufbau fuer wachsende Laufzeiten
 *    durchgerechnet ([weeksForFeasibleGoal]).
 *
 * Das Startvolumen wird aus Woche 0 des Plans zurueckgelesen. Hat das
 * Zeitbudget schon Woche 0 gekuerzt, ist das eine Unterschaetzung des echten
 * Umfangs — die Stundenzahl ohne Budget ist dann eher zu niedrig als zu hoch.
 */
fun assessPlanFeasibility(
    plan: TrainingPlan,
    prognosis: GoalPrognosis? = null,
    texts: CoreTexts,
): PlanFeasibility {
    val goalKm = plan.goal.distanceKm
    val longest = longestTrainingRideKm(plan.weeks.map { it.sessions })
    val timeHint = targetTimeHint(plan.goal, prognosis, texts)

    if (plan.goal.type == GoalType.FITNESS || !goalKm.isFinite() || goalKm <= 0) {
        return PlanFeasibility(
            longest, goalKm, 0.0, true, null, null, null,
            targetTimeGapMin = timeHint?.first, targetTimeMessage = timeHint?.second,
        )
    }

    val coverage = longest / goalKm
    if (coverage >= minLongestRideShare) {
        return PlanFeasibility(
            longest, goalKm, coverage, true, null, null, null,
            targetTimeGapMin = timeHint?.first, targetTimeMessage = timeHint?.second,
        )
    }

    val startKm = plan.weeks.firstOrNull()?.targetKm?.toDouble() ?: 0.0
    // Abgerundet auf 5 km: aufgerundet truege der Vorschlag selbst knapp nicht.
    val suggestedDistance = max(5, kotlin.math.floor(longest / minLongestRideShare / 5).toInt() * 5)
    val suggestedWeeks = weeksForFeasibleGoal(
        goal = plan.goal,
        level = plan.level,
        startKm = startKm,
        preferences = plan.preferences,
        speedKmh = plan.planSpeedKmh,
        texts = texts,
    )

    // Ist das Zeitbudget der Engpass? Derselbe Plan ohne Zeitdeckel — in
    // derselben Laufzeit oder, wenn mit Budget selbst ein Jahr nicht reicht,
    // in der Laufzeit, die ohne Budget genuegen wuerde.
    var budgetLimited = false
    var hoursNeeded: Double? = null
    val prefs = plan.preferences
    val budget = prefs?.validWeeklyHours()
    if (prefs != null && budget != null && startKm > 0) {
        val noBudget = prefs.copy(weeklyHours = null).takeUnless { it.isEmpty }
        val sameLength = planSessions(
            plan.goal, plan.level, startKm, plan.weeks.size, null, plan.preferences, plan.planSpeedKmh, texts,
            applyTimeBudget = false,
        )
        val unconstrained = if (longestTrainingRideKm(sameLength) / goalKm >= minLongestRideShare) {
            sameLength
        } else if (suggestedWeeks == null) {
            weeksForFeasibleGoal(plan.goal, plan.level, startKm, noBudget, plan.planSpeedKmh, texts)
                ?.let { weeks -> planSessions(plan.goal, plan.level, startKm, weeks, null, noBudget, plan.planSpeedKmh, texts) }
        } else {
            null
        }
        if (unconstrained != null) {
            budgetLimited = true
            val peakMinutes = unconstrained.maxOf { week -> week.filterNot { it.isEvent }.sumOf { it.durationMin ?: 0 } }
            hoursNeeded = dartRound(peakMinutes / 60.0 * 2) / 2
        }
    }

    val percent = dartRound(coverage * 100).toInt()
    val goalText = dartRound(goalKm).toInt()
    val distanceMessage = texts.training.planNotFeasible(
        longestKm = longest,
        percent = percent,
        goalKm = goalText,
        suggestedKm = suggestedDistance,
        suggestedWeeks = suggestedWeeks,
        needsLongerPlan = suggestedWeeks != null && suggestedWeeks > plan.weeks.size,
    )
    val message = if (budgetLimited && budget != null && hoursNeeded != null) {
        distanceMessage + " " + texts.training.planBudgetLimited(budget, hoursNeeded)
    } else {
        distanceMessage
    }

    return PlanFeasibility(
        longestRideKm = longest,
        goalDistanceKm = goalKm,
        coverage = coverage,
        feasible = false,
        suggestedDistanceKm = suggestedDistance,
        suggestedWeeks = suggestedWeeks,
        message = message,
        budgetLimited = budgetLimited,
        hoursNeededForGoal = hoursNeeded,
        targetTimeGapMin = timeHint?.first,
        targetTimeMessage = timeHint?.second,
    )
}

/** Laengste Trainingsfahrt (ohne Event) ueber alle Wochen. */
private fun longestTrainingRideKm(weeks: List<List<TrainingSession>>): Int =
    weeks.flatten().filterNot { it.isEvent }.maxOfOrNull { it.targetKm } ?: 0

/**
 * Mindestabstand zwischen Zielzeit und Prognose, ab dem die Zielzeit als
 * „deutlich schneller" gilt: die Unsicherheit der Prognose, mindestens 5 %
 * der prognostizierten Zeit — darunter ist der Unterschied Rauschen.
 */
private const val TARGET_TIME_MIN_GAP_SHARE = 0.05

/** `(Luecke in Minuten, Text)`, wenn die Zielzeit deutlich unter der Prognose liegt. */
private fun targetTimeHint(goal: Goal, prognosis: GoalPrognosis?, texts: CoreTexts): Pair<Int, String>? {
    val target = goal.targetDurationMin ?: return null
    val p = prognosis ?: return null
    if (goal.type != GoalType.EVENT || goal.distanceKm <= 0) return null
    val predicted = p.atEventMin ?: p.currentMin
    if (predicted <= 0) return null
    val margin = max(p.uncertaintyMin, (predicted * TARGET_TIME_MIN_GAP_SHARE).roundToInt())
    val gap = predicted - target
    if (gap <= margin) return null
    val targetSpeed = goal.distanceKm / (target / 60.0)
    val predictedSpeed = goal.distanceKm / (predicted / 60.0)
    return gap to texts.training.targetTimeAmbitious(target, predicted, targetSpeed, predictedSpeed)
}

/**
 * Die kuerzeste Laufzeit in Wochen, nach der ein Plan fuer [goal] die
 * [minLongestRideShare]-Schwelle nimmt — `null`, wenn selbst [MAX_WEEKS] nicht
 * reichen.
 *
 * Rechnet den echten Aufbau durch ([planSessions]) statt einer
 * Naeherungsformel: Rampengrenze, Erholungswochen, Zeitbudget und Gewichte
 * der langen Tour greifen ineinander, und eine zweite, „ungefaehre" Formel
 * daneben waere genau die Sorte Wahrheit, die irgendwann von der ersten
 * abweicht.
 */
fun weeksForFeasibleGoal(
    goal: Goal,
    level: FitnessLevel,
    startKm: Double,
    preferences: PlanPreferences? = null,
    speedKmh: Double? = null,
    texts: CoreTexts,
): Int? {
    if (goal.type == GoalType.FITNESS || !goal.distanceKm.isFinite() || goal.distanceKm <= 0 || startKm <= 0) {
        return null
    }
    for (weekCount in MIN_WEEKS..MAX_WEEKS) {
        val sessions = planSessions(goal, level, startKm, weekCount, null, preferences, speedKmh, texts)
        if (longestTrainingRideKm(sessions) / goal.distanceKm >= minLongestRideShare) {
            return weekCount
        }
    }
    return null
}

/**
 * Laedt den gespeicherten Plan; `null`, wenn keiner existiert oder die Daten
 * unbrauchbar sind.
 */
fun loadPlan(store: TrainingPlanStore): TrainingPlan? = try {
    val raw = store.read()
    if (raw == null) {
        null
    } else {
        val parsed = Json.parseToJsonElement(raw)
        if (parsed !is JsonObject || parsed["weeks"] !is JsonArray) {
            null
        } else {
            TrainingPlan.fromJson(parsed)
        }
    }
} catch (_: Throwable) {
    // Speicher nicht verfuegbar oder Daten defekt – kein Plan.
    null
}

/** Speichert den Plan; `null` entfernt den gespeicherten Plan. */
fun savePlan(store: TrainingPlanStore, plan: TrainingPlan?) {
    try {
        if (plan == null) {
            store.remove()
            return
        }
        store.write(plan.toJson().toString())
    } catch (_: Throwable) {
        // Speicher nicht verfuegbar – Plan bleibt nur im Arbeitsspeicher.
    }
}

/** Index der aktuellen Planwoche; -1 vor Planbeginn, letzte Woche nach Planende. */
fun currentWeekIndex(plan: TrainingPlan, now: Long? = null): Int {
    val nowMs = now ?: System.currentTimeMillis()
    val weeks = plan.weeks

    if (weeks.isEmpty()) {
        return -1
    }
    if (nowMs < weeks.first().start) {
        return -1
    }

    for (week in weeks) {
        if (nowMs >= week.start && nowMs < week.end) {
            return week.index
        }
    }

    return weeks.size - 1
}

/**
 * Die fuer **heute** geplanten Einheiten — leer, wenn heute ein Ruhetag ist.
 *
 * Ergaenzung fuer die Startseite „Heute" (`ui/today/TodayScreen.kt`), die genau
 * eine Frage beantworten muss: „Was steht heute an?". Die Zuordnung
 * Zeitpunkt → Wochentagskuerzel liegt bewusst hier und nicht in der
 * Oberflaeche: [TrainingSession.day] traegt die Kuerzel „Mo"…„So", die
 * [generatePlan] aus derselben privaten Tabelle erzeugt. Ein zweites,
 * handgeschriebenes Kuerzel-Mapping im UI wuerde beim naechsten Sprachwechsel
 * oder Umbenennen lautlos auseinanderlaufen.
 *
 * Ausserhalb der Planlaufzeit ist die Liste leer: Vor Planbeginn liefert
 * [currentWeekIndex] `-1`, nach Planende klemmt es auf die letzte Woche — deren
 * Einheiten liegen dann aber in der Vergangenheit und sind kein Tagesprogramm
 * mehr. Beides wird hier abgefangen.
 */
fun sessionsForDay(plan: TrainingPlan, now: Long? = null): List<TrainingSession> {
    val nowMs = now ?: System.currentTimeMillis()
    val index = currentWeekIndex(plan, nowMs)
    if (index < 0) {
        return emptyList()
    }
    val week = plan.weeks.getOrNull(index) ?: return emptyList()
    if (nowMs < week.start || nowMs >= week.end) {
        return emptyList()
    }
    val day = weekdays[weekdayIndex(nowMs)]
    return week.sessions.filter { it.day == day }
}

/**
 * Summiert die tatsaechlich gefahrenen Kilometer im Zeitraum [start, end).
 *
 * „Tatsaechlich gefahren" ist woertlich gemeint: Gespeicherte **Planungen**
 * ([Ride.planned]) zaehlen nicht mit. Vorher taten sie es — „Als Tour
 * speichern" auf der Karte legte eine gewoehnliche Tour an, und der
 * Wochenfortschritt sprang, ohne dass jemand im Sattel sass.
 */
fun weekKm(week: TrainingWeek, rides: List<RideInfo>): Double {
    var total = 0.0
    for (ride in riddenRides(rides)) {
        if (ride.createdAt >= week.start && ride.createdAt < week.end) {
            total += ride.stats.distanceKm
        }
    }
    return dartRound1(total)
}
