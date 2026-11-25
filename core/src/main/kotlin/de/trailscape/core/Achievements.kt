package de.trailscape.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * # Erfolge — Abzeichen, Punkte und Stufen
 *
 * Etappe 1 aus `docs/konzept-spiel-community.md` (Abschnitte 2.2–2.7), Regeln
 * und Entscheidungen im Einzelnen in `docs/erfolge.md`.
 *
 * Wie die uebrige Motivation (`Motivation.kt`, `Challenges.kt`) ist alles hier
 * **abgeleitet**: reine Rechnung ueber die gefahrenen Touren ([RideFact]),
 * den aktuellen Plan ([PlanAchievementFacts]), das Jahresziel und die wenigen
 * Werte, die sich nicht ableiten lassen ([AchievementProgressData]). Kein
 * Android, keine Uhr — „heute" kommt herein. Saetze baut die Oberflaeche.
 *
 * ## Was belohnt wird — und was nicht
 *  - **Punkte aus Fahrzeit** mit Tagesdeckel ([dailyMinutesCap]), dazu Boni
 *    fuer Planeinheiten, erreichte Wochenziele und Monats-Challenges.
 *  - **Keine** Punkte fuer Tempo, Bestwerte, Hoehenmeter, Last oder
 *    Intensitaet; **keine** Tagesserien. Die Serie zaehlt Wochen und kennt
 *    Pausenwochen ([acceptedPauseWeeks]).
 *  - Abzeichen auch fuer eingehaltene Erholung ([AchievementId.RECOVERY_WEEK]).
 */

// ---------------------------------------------------------------------------
// Die Abzeichen
// ---------------------------------------------------------------------------

/** Gruppen der Abzeichen — nur fuer die Gliederung des Bildschirms. */
enum class AchievementGroup { RIDES, TOTALS, ROUTINE, PLAN, INDOOR, EXPLORE }

/** Einheit des Fortschritts eines Abzeichens; [NONE] = erreicht oder nicht. */
enum class AchievementUnit { NONE, COUNT, KM, METERS, HOURS, WEEKS, SESSIONS, PERCENT, TILES }

/**
 * Die 30 Abzeichen aus Abschnitt 2.4 des Konzepts, in dessen Reihenfolge.
 *
 * @property key stabiler Name im gespeicherten Stand und im Backup — nie
 *   aendern, nur neue anhaengen. Der Kotlin-Bezeichner darf sich aendern.
 * @property target Schwelle in [unit]; bei [AchievementUnit.NONE] 1.
 */
enum class AchievementId(
    val key: String,
    val group: AchievementGroup,
    val target: Double,
    val unit: AchievementUnit,
) {
    FIRST_RIDE("first_ride", AchievementGroup.RIDES, 1.0, AchievementUnit.NONE),
    RIDE_50_KM("ride_50km", AchievementGroup.RIDES, 50.0, AchievementUnit.KM),
    RIDE_100_KM("ride_100km", AchievementGroup.RIDES, 100.0, AchievementUnit.KM),
    RIDE_1000_M("ride_1000m", AchievementGroup.RIDES, 1000.0, AchievementUnit.METERS),
    RIDE_4_H("ride_4h", AchievementGroup.RIDES, 4.0, AchievementUnit.HOURS),
    RIDES_10("rides_10", AchievementGroup.TOTALS, 10.0, AchievementUnit.COUNT),
    RIDES_100("rides_100", AchievementGroup.TOTALS, 100.0, AchievementUnit.COUNT),
    TOTAL_1000_KM("total_1000km", AchievementGroup.TOTALS, 1000.0, AchievementUnit.KM),
    TOTAL_EARTH("total_earth", AchievementGroup.TOTALS, earthCircumferenceKm, AchievementUnit.KM),
    TOTAL_EVEREST("total_everest", AchievementGroup.TOTALS, everestM, AchievementUnit.METERS),
    STREAK_4("streak_4", AchievementGroup.ROUTINE, 4.0, AchievementUnit.WEEKS),
    STREAK_12("streak_12", AchievementGroup.ROUTINE, 12.0, AchievementUnit.WEEKS),
    STREAK_52("streak_52", AchievementGroup.ROUTINE, 52.0, AchievementUnit.WEEKS),
    MONTH_COMPLETE("month_complete", AchievementGroup.ROUTINE, 1.0, AchievementUnit.COUNT),
    CHALLENGE_BONUS("challenge_bonus", AchievementGroup.ROUTINE, 1.0, AchievementUnit.NONE),
    YEAR_GOAL("year_goal", AchievementGroup.ROUTINE, 1.0, AchievementUnit.KM),
    PLAN_FIRST("plan_first", AchievementGroup.PLAN, 1.0, AchievementUnit.NONE),
    PLAN_FULL_WEEK("plan_full_week", AchievementGroup.PLAN, 1.0, AchievementUnit.SESSIONS),
    RECOVERY_WEEK("recovery_week", AchievementGroup.PLAN, 1.0, AchievementUnit.NONE),
    PLAN_FINISHED("plan_finished", AchievementGroup.PLAN, 80.0, AchievementUnit.PERCENT),
    INDOOR_FIRST("indoor_first", AchievementGroup.INDOOR, 1.0, AchievementUnit.NONE),
    INDOOR_10_H("indoor_10h", AchievementGroup.INDOOR, 10.0, AchievementUnit.HOURS),
    GHOST_BEATEN("ghost_beaten", AchievementGroup.INDOOR, 1.0, AchievementUnit.NONE),
    KNOWN_ROUTE("known_route", AchievementGroup.INDOOR, 1.0, AchievementUnit.NONE),
    CLIMB_REPEAT("climb_repeat", AchievementGroup.EXPLORE, 1.0, AchievementUnit.NONE),
    CLIMB_FASTER("climb_faster", AchievementGroup.EXPLORE, 1.0, AchievementUnit.NONE),
    ROUND_5("round_5", AchievementGroup.EXPLORE, 5.0, AchievementUnit.COUNT),
    TILES_100("tiles_100", AchievementGroup.EXPLORE, 100.0, AchievementUnit.TILES),
    TILES_SQUARE_5("tiles_square_5", AchievementGroup.EXPLORE, 5.0, AchievementUnit.TILES),
    NEW_WAYS_100_KM("new_ways_100km", AchievementGroup.EXPLORE, 100.0, AchievementUnit.KM),
    ;

    companion object {
        /** `null` fuer einen unbekannten Schluessel (z. B. aus einer neueren Version). */
        fun fromKey(key: String): AchievementId? = entries.firstOrNull { it.key == key }
    }
}

/** Stand eines Abzeichens. */
enum class AchievementStatus {
    /** Erreicht. */
    REACHED,

    /** Noch offen, Fortschritt bekannt (oder nicht in Zahlen fassbar). */
    OPEN,

    /** Offen, der Fortschritt ist gerade nicht bekannt (z. B. Kacheln noch nicht gerechnet). */
    UNKNOWN,

    /** Die Funktion, auf der es aufsetzt, gibt es in dieser Version noch nicht. */
    COMING_SOON,
}

/**
 * Stand eines Abzeichens.
 *
 * @property current Fortschritt in [AchievementId.unit]; `null`, wenn es nichts
 *   zu zaehlen gibt (kein Jahresziel, kein Plan, unbekannt).
 * @property target Schwelle; meist [AchievementId.target], bei Abzeichen mit
 *   wechselnder Schwelle (Jahresziel, Planwoche, Monats-Challenges) die von heute.
 * @property rideId die freischaltende Tour, soweit bekannt.
 * @property date Tag des Erreichens, soweit bekannt.
 */
data class AchievementState(
    val id: AchievementId,
    val status: AchievementStatus,
    val current: Double? = null,
    val target: Double = id.target,
    val rideId: String? = null,
    val date: LocalDate? = null,
) {
    val reached: Boolean get() = status == AchievementStatus.REACHED

    /** Fortschritt 0…1; `null` ohne zaehlbaren Stand. Erreicht ist immer 1. */
    val fraction: Double?
        get() = when {
            reached -> 1.0
            current == null || target <= 0 -> null
            else -> (current / target).coerceIn(0.0, 1.0)
        }
}

/** Tour und Tag, an dem etwas erreicht wurde. */
data class AchievementUnlock(val rideId: String?, val date: LocalDate)

// ---------------------------------------------------------------------------
// Schmale Schnittstellen zu Funktionen ausserhalb dieser Datei
// ---------------------------------------------------------------------------

/**
 * Anstiege — vorgesehen fuer die Bergwertung mit lokalen Bestzeiten
 * (Abzeichen 25 und 26). Bis sie da ist, bleibt [AchievementSources.climbs]
 * `null` und beide Abzeichen stehen auf „kommt bald".
 *
 * @property firstRepeatedClimb erster Anstieg mit zwei eigenen Befahrungen.
 * @property firstImprovedBest erste verbesserte eigene Bestzeit an einem Anstieg.
 */
data class ClimbAchievementFacts(
    val firstRepeatedClimb: AchievementUnlock?,
    val firstImprovedBest: AchievementUnlock?,
)

/**
 * Dieselbe Runde mehrfach (Abzeichen 27), aus dem Rundenvergleich.
 *
 * @property maxRepeats wie oft die am haeufigsten gefahrene Runde gefahren wurde.
 * @property fifthRide die Tour, mit der eine Runde zum fuenften Mal gefahren
 *   wurde (die frueheste solche), soweit bekannt.
 */
data class RoundAchievementFacts(val maxRepeats: Int, val fifthRide: AchievementUnlock? = null)

/** Entdeckt-Kacheln (Abzeichen 28 und 29), siehe `ExplorerTiles.kt`. */
data class ExplorationAchievementFacts(val tileCount: Int, val largestSquare: Int)

/**
 * Ergebnisse anderer Funktionen. `null` heisst: nicht da.
 *
 * Fuer Geist, bekannte Strecke und Anstiege ([comingSoonWhenMissing]) heisst
 * `null` „gibt es in dieser Version noch nicht" ([AchievementStatus.COMING_SOON]),
 * fuer die uebrigen „noch nicht gerechnet" ([AchievementStatus.UNKNOWN]).
 *
 * @property ghostWins Siege gegen den Geist einer frueheren eigenen Fahrt auf
 *   derselben Strecke („Tour drinnen nachfahren").
 * @property knownRouteCompletions vollstaendig gefahrene Strecken aus dem
 *   Katalog „Bekannte Strecken".
 * @property distinctWaysKm Laenge aller verschiedenen gefahrenen Wege in km
 *   ([distinctRiddenWaysKm]).
 */
data class AchievementSources(
    val ghostWins: List<AchievementUnlock>? = null,
    val knownRouteCompletions: List<AchievementUnlock>? = null,
    val climbs: ClimbAchievementFacts? = null,
    val rounds: RoundAchievementFacts? = null,
    val exploration: ExplorationAchievementFacts? = null,
    val distinctWaysKm: Double? = null,
)

/** Abzeichen, deren Quelle in dieser Version fehlt, wenn sie `null` ist. */
val comingSoonWhenMissing: Set<AchievementId> = setOf(
    AchievementId.GHOST_BEATEN,
    AchievementId.KNOWN_ROUTE,
    AchievementId.CLIMB_REPEAT,
    AchievementId.CLIMB_FASTER,
)

/**
 * Zellen je Kilometer Weg im Raster von `RiddenWays.kt` — der Erfahrungswert
 * aus dessen Kopfkommentar („etwa 65 Zellen je km").
 */
const val riddenWayCellsPerKm: Double = 65.0

/**
 * Laenge aller verschiedenen gefahrenen Wege in km, aus der Zellenzahl des
 * Wegeindex. Jede Zelle wird nur beim ersten Befahren gezaehlt — die Summe ist
 * also genau „km auf damals neuen Wegen" (Abzeichen 30), als Naeherung.
 */
fun distinctRiddenWaysKm(cellCount: Int): Double = max(0, cellCount) / riddenWayCellsPerKm

/**
 * „Rundenkenner" (Abzeichen 27) aus den Signaturen des Rundenvergleichs
 * (`RouteMatch.kt`): Fuer jede draussen gefahrene Tour zaehlt, wie viele
 * fruehere Touren [sameRoute] zu ihr sind — wie im Tourblatt bezogen auf die
 * Tour selbst, ohne transitive Gruppen. Anders als dort zaehlt jede Fahrt,
 * auch ohne Bewegungszeit.
 *
 * @param rides alle Touren; Planungen und Indoor-Fahrten fallen heraus.
 * @param dateOf Kalendertag zu einem Zeitstempel (wie bei [rideFacts]).
 */
fun roundAchievementFacts(
    rides: List<RideInfo>,
    signatures: Map<String, RouteSignature>,
    dateOf: (Long) -> LocalDate,
): RoundAchievementFacts {
    val ordered = outdoorRides(rides).sortedBy { it.createdAt }.mapNotNull { info -> signatures[info.id]?.let { info to it } }
    var maxRepeats = 0
    var fifth: AchievementUnlock? = null
    for ((i, entry) in ordered.withIndex()) {
        val (info, sig) = entry
        var count = 1
        for (j in 0 until i) if (sameRoute(sig, ordered[j].second)) count += 1
        maxRepeats = max(maxRepeats, count)
        if (fifth == null && count >= 5) fifth = AchievementUnlock(info.id, dateOf(info.createdAt))
    }
    return RoundAchievementFacts(maxRepeats, fifth)
}

// ---------------------------------------------------------------------------
// Plan
// ---------------------------------------------------------------------------

/** Eine Planeinheit, so weit die Erfolge sie brauchen. */
data class PlanSessionFact(
    /** Kalendertag der Einheit; `null` bei fremdem Tageskuerzel. */
    val date: LocalDate?,
    val status: PlanSessionStatus,
    /** Die zugeordnete Tour bei ERLEDIGT/TEILWEISE. */
    val rideId: String?,
)

/** Eine Planwoche, so weit die Erfolge sie brauchen. */
data class PlanWeekFact(
    val monday: LocalDate,
    val kind: WeekKind,
    val targetKm: Int,
    val sessions: List<PlanSessionFact>,
)

/** Der aktuelle Plan fuer die Erfolge — abgeleitet von [planAchievementFacts]. */
data class PlanAchievementFacts(val weeks: List<PlanWeekFact>)

/**
 * Der Plan fuer die Erfolge: je Woche der Status jeder Einheit
 * ([weekSessionProgress], dieselbe Zuordnung wie im Plan-Tab).
 *
 * Es gibt nur den **aktuellen** Plan; frueher abgeschlossene Plaene sind nicht
 * gespeichert. Was aus ihnen erreicht wurde, haelt
 * [AchievementProgressData.earned] fest.
 */
fun planAchievementFacts(
    plan: TrainingPlan,
    rides: List<RideInfo>,
    nowMs: Long,
    rideLoads: Map<String, Double> = emptyMap(),
): PlanAchievementFacts = PlanAchievementFacts(
    plan.weeks.map { week ->
        val monday = dartLocalOf(week.start).toLocalDate()
        PlanWeekFact(
            monday = monday,
            kind = week.kind,
            targetKm = week.targetKm,
            sessions = weekSessionProgress(week, rides, nowMs, rideLoads).map { p ->
                val index = planWeekdayIndex(p.session.day)
                PlanSessionFact(
                    date = if (index < 0) null else monday.plusDays(index.toLong()),
                    status = p.status,
                    rideId = p.rideId,
                )
            },
        )
    },
)

/**
 * Anteil, bis zu dem eine Erholungswoche als eingehalten gilt: 110 % ihres
 * Wochenziels. Das Ziel ist auf 5 km gerundet und eine Runde nie auf den
 * Kilometer genau planbar; wer 12 statt 11 km faehrt, hat sich erholt.
 */
const val recoveryWeekTolerance: Double = 1.10

/** Mindestanteil erledigter Einheiten fuer „Durchgezogen" (Abzeichen 20). */
const val planFinishedShare: Double = 0.8

// ---------------------------------------------------------------------------
// Punkte und Stufen
// ---------------------------------------------------------------------------

/** 1 Punkt je Minute Bewegungszeit … */
const val pointsPerMinute: Int = 1

/** … hoechstens so viele Minuten je Kalendertag (3 h). */
const val dailyMinutesCap: Int = 180

const val planDonePoints: Int = 40
const val planPartialPoints: Int = 20
const val weekGoalPoints: Int = 60
const val challengeReachedPoints: Int = 100
const val challengeBonusPoints: Int = 50

/** Punkte, aufgeteilt nach Herkunft. */
data class AchievementPoints(
    val ride: Int = 0,
    val plan: Int = 0,
    val week: Int = 0,
    val challenge: Int = 0,
) {
    val total: Int get() = ride + plan + week + challenge
}

const val maxAchievementLevel: Int = 50

/** Grundwert und Exponent der Stufenkurve `P(L) = 500 × (L − 1)^1,6`. */
const val levelCurveBase: Double = 500.0
const val levelCurveExponent: Double = 1.6

/**
 * Gesamtpunkte, ab denen Stufe [level] gilt — auf 10 gerundet, damit die Zahl
 * sich merken laesst. Stufe 1 gilt ab 0. Ausserhalb 1…[maxAchievementLevel]
 * wird geklemmt.
 */
fun levelThreshold(level: Int): Int {
    val l = level.coerceIn(1, maxAchievementLevel)
    if (l == 1) return 0
    val raw = levelCurveBase * (l - 1).toDouble().pow(levelCurveExponent)
    return (raw / 10.0).roundToInt() * 10
}

/** Die Stufe zu [points] (1…[maxAchievementLevel]). */
fun levelForPoints(points: Int): Int {
    var level = 1
    while (level < maxAchievementLevel && points >= levelThreshold(level + 1)) level += 1
    return level
}

/**
 * Stufe und Weg zur naechsten.
 *
 * @property level die angezeigte Stufe: die aus den Punkten, aber nie unter
 *   der gespeicherten hoechsten ([AchievementProgressData.keptLevel]) — eine
 *   geaenderte Formel oder ein geloeschter Plan stuft niemanden herab.
 * @property levelPoints Schwelle von [level].
 * @property nextLevelPoints Schwelle der naechsten Stufe; `null` auf der obersten.
 */
data class LevelProgress(
    val level: Int,
    val points: Int,
    val levelPoints: Int,
    val nextLevelPoints: Int?,
) {
    /** Weg von [levelPoints] zu [nextLevelPoints], 0…1; 1 auf der obersten Stufe. */
    val fraction: Double
        get() {
            val next = nextLevelPoints ?: return 1.0
            val span = next - levelPoints
            return if (span <= 0) 0.0 else ((points - levelPoints).toDouble() / span).coerceIn(0.0, 1.0)
        }

    /** Punkte bis zur naechsten Stufe; 0 auf der obersten. */
    val remaining: Int get() = nextLevelPoints?.let { (it - points).coerceAtLeast(0) } ?: 0
}

fun levelProgress(points: Int, keptLevel: Int = 1): LevelProgress {
    val level = max(levelForPoints(points), keptLevel.coerceIn(1, maxAchievementLevel))
    return LevelProgress(
        level = level,
        points = points,
        levelPoints = levelThreshold(level),
        nextLevelPoints = if (level >= maxAchievementLevel) null else levelThreshold(level + 1),
    )
}

// ---------------------------------------------------------------------------
// Pausenwochen und Serie
// ---------------------------------------------------------------------------

/** Hoechstens so viele Pausenwochen … */
const val pauseWeeksPerWindow: Int = 2

/** … in je so vielen aufeinanderfolgenden Wochen. */
const val pauseWindowWeeks: Int = 12


/**
 * Die Pausenwochen, die wirklich gelten (Abschnitt 2.2 des Konzepts): als
 * Montage, nur Wochen **ohne** Tour (eine Woche mit Tour zaehlt ohnehin und
 * verbraucht keine Pause), und hoechstens [pauseWeeksPerWindow] in je
 * [pauseWindowWeeks] Wochen. Gezaehlt wird der Reihe nach: Eine Pause gilt,
 * wenn in den elf Wochen davor weniger als zwei gelten — so enthaelt kein
 * Zwoelf-Wochen-Fenster mehr als zwei.
 *
 * Ein Tag, der kein Montag ist, meint seine Woche.
 */
fun acceptedPauseWeeks(facts: List<RideFact>, pauses: Set<LocalDate>): Set<LocalDate> {
    val rideWeeks = facts.mapTo(HashSet()) { mondayOfWeek(it.date) }
    val accepted = sortedSetOf<LocalDate>()
    for (monday in pauses.map(::mondayOfWeek).toSortedSet()) {
        if (monday in rideWeeks) continue
        val from = monday.minusWeeks((pauseWindowWeeks - 1).toLong())
        val inWindow = accepted.count { !it.isBefore(from) && it.isBefore(monday) }
        if (inWindow < pauseWeeksPerWindow) accepted += monday
    }
    return accepted
}

/**
 * Ob die Woche von [date] als Pause markiert werden kann: ohne Tour, und die
 * Markierung haelt die Grenze ein, ohne eine schon geltende Pause zu
 * verdraengen.
 */
fun canMarkPauseWeek(facts: List<RideFact>, pauses: Set<LocalDate>, date: LocalDate): Boolean {
    val monday = mondayOfWeek(date)
    if (facts.any { mondayOfWeek(it.date) == monday }) return false
    val before = acceptedPauseWeeks(facts, pauses)
    if (monday in before) return false
    val after = acceptedPauseWeeks(facts, pauses + monday)
    return monday in after && after.containsAll(before)
}

/**
 * Wie [weekStreak], aber geltende Pausenwochen ([acceptedPauseWeeks]) halten
 * die Serie, statt sie zu unterbrechen. Sie verlaengern sie nicht.
 *
 * Eine laufende Woche, die als Pause gilt, zaehlt fuer [WeekStreak.thisWeekDone]
 * als erledigt — die Serie ist diese Woche nicht in Gefahr
 * ([WeekStreak.atRisk] ist `false`).
 */
fun weekStreakWithPauses(facts: List<RideFact>, pauses: Set<LocalDate>, today: LocalDate): WeekStreak {
    val t = streakTimeline(facts, acceptedPauseWeeks(facts, pauses), today, emptyList())
    return WeekStreak(
        weeks = t.current,
        thisWeekDone = t.thisWeekDone || t.thisWeekPaused,
        best = t.best,
        daysLeft = 8 - today.dayOfWeek.value,
    )
}

private class StreakTimeline(
    val current: Int,
    val best: Int,
    val thisWeekDone: Boolean,
    val thisWeekPaused: Boolean,
    /** Je Schwelle die Woche (Montag), in der die Serie sie zum ersten Mal erreichte. */
    val reachedAt: Map<Int, LocalDate>,
)

private fun streakTimeline(
    facts: List<RideFact>,
    accepted: Set<LocalDate>,
    today: LocalDate,
    thresholds: List<Int>,
): StreakTimeline {
    val thisMonday = mondayOfWeek(today)
    val rideWeeks = facts.asSequence().filter { !it.date.isAfter(today) }.map { mondayOfWeek(it.date) }.toSortedSet()
    val reachedAt = HashMap<Int, LocalDate>()
    if (rideWeeks.isEmpty()) {
        return StreakTimeline(0, 0, false, thisMonday in accepted, reachedAt)
    }
    var run = 0
    var best = 0
    var monday = rideWeeks.first()
    while (!monday.isAfter(thisMonday)) {
        when {
            monday in rideWeeks -> {
                run += 1
                for (n in thresholds) if (run >= n && n !in reachedAt) reachedAt[n] = monday
            }
            monday in accepted -> Unit
            // Die laufende Woche ist noch offen: Sie bricht nichts ab.
            monday == thisMonday -> Unit
            else -> run = 0
        }
        best = max(best, run)
        monday = monday.plusWeeks(1)
    }
    return StreakTimeline(
        current = run,
        best = best,
        thisWeekDone = thisMonday in rideWeeks,
        thisWeekPaused = thisMonday !in rideWeeks && thisMonday in accepted,
        reachedAt = reachedAt,
    )
}

// ---------------------------------------------------------------------------
// Eingabe und Rechnung
// ---------------------------------------------------------------------------

/**
 * Alles, woraus die Erfolge entstehen.
 *
 * @param facts alle gefahrenen Touren, aelteste zuerst ([rideFacts]) — draussen
 *   und drinnen.
 * @param indoorRideIds die davon auf dem Rollentrainer ([RideInfo.indoor]).
 * @param plan der aktuelle Plan ([planAchievementFacts]); `null` ohne Plan.
 * @param pointsSince ab diesem Tag gibt es Punkte (Abschnitt 2.7: importierte
 *   Touren von vor der Installation zaehlen fuer Abzeichen, nicht fuer Punkte).
 *   `null` = alle Touren.
 */
data class AchievementInput(
    val facts: List<RideFact>,
    val today: LocalDate,
    val indoorRideIds: Set<String> = emptySet(),
    val plan: PlanAchievementFacts? = null,
    val yearGoal: YearGoal? = null,
    val progress: AchievementProgressData = AchievementProgressData(),
    val sources: AchievementSources = AchievementSources(),
) {
    val pointsSince: LocalDate? get() = progress.pointsSince
}

/** Das Ergebnis fuer den Bildschirm „Erfolge". */
data class AchievementOverview(
    val states: List<AchievementState>,
    val points: AchievementPoints,
    val level: LevelProgress,
    /** Wochenserie mit Pausenwochen. */
    val streak: WeekStreak,
    /** Die geltenden Pausenwochen (Montage). */
    val pauseWeeks: Set<LocalDate>,
) {
    /** Abzeichen, die es in dieser Version gibt (ohne „kommt bald"). */
    val available: List<AchievementState> get() = states.filter { it.status != AchievementStatus.COMING_SOON }
    val reachedCount: Int get() = states.count { it.reached }
}

/**
 * Stand aller Erfolge. Die Reihenfolge der Abzeichen ist die von [AchievementId].
 *
 * Abzeichen, die schon einmal erreicht und in [AchievementProgressData.earned]
 * festgehalten wurden, bleiben erreicht, auch wenn die Rechnung sie heute nicht
 * mehr ergibt (Plan ersetzt, Tour geloescht, Jahresziel geaendert) — einmal
 * erreicht bleibt erreicht.
 */
fun achievementOverview(input: AchievementInput): AchievementOverview {
    val accepted = acceptedPauseWeeks(input.facts, input.progress.pauseWeeks)
    val states = achievementStates(input, accepted)
    val points = achievementPoints(input)
    val timeline = streakTimeline(input.facts, accepted, input.today, emptyList())
    return AchievementOverview(
        states = states,
        points = points,
        level = levelProgress(points.total, input.progress.keptLevel),
        streak = WeekStreak(
            weeks = timeline.current,
            thisWeekDone = timeline.thisWeekDone || timeline.thisWeekPaused,
            best = timeline.best,
            daysLeft = 8 - input.today.dayOfWeek.value,
        ),
        pauseWeeks = accepted,
    )
}

/** Abzeichen, die mit der Tour [rideId] erreicht wurden, in der Reihenfolge von [AchievementId]. */
fun achievementsUnlockedBy(rideId: String, states: List<AchievementState>): List<AchievementId> =
    states.filter { it.reached && it.rideId == rideId }.map { it.id }

/** Erreichte Abzeichen, die noch nicht als gesehen markiert sind. */
fun unseenAchievements(states: List<AchievementState>, progress: AchievementProgressData): List<AchievementId> =
    states.filter { it.reached && it.id.key !in progress.seen }.map { it.id }

private fun AchievementInput.counts(date: LocalDate): Boolean = pointsSince?.let { !date.isBefore(it) } ?: true

/** Die Punkte aus Abschnitt 2.3 des Konzepts. Touren nach [AchievementInput.today] zaehlen nicht. */
fun achievementPoints(input: AchievementInput): AchievementPoints {
    val facts = input.facts.filter { !it.date.isAfter(input.today) }
    if (facts.isEmpty()) return AchievementPoints()

    // Fahrzeit: je Kalendertag hoechstens dailyMinutesCap Minuten.
    val ride = facts.filter { input.counts(it.date) }
        .groupBy { it.date }
        .values
        .sumOf { day -> min(day.sumOf { (it.timeS ?: 0).coerceAtLeast(0) } / 60, dailyMinutesCap) * pointsPerMinute }

    // Plan: je erledigter bzw. teilweiser Einheit.
    val plan = input.plan?.weeks.orEmpty().sumOf { week ->
        week.sessions.sumOf { s ->
            val date = s.rideId?.let { id -> facts.firstOrNull { it.id == id }?.date } ?: s.date
            if (date == null || date.isAfter(input.today) || !input.counts(date)) {
                0
            } else {
                when (s.status) {
                    PlanSessionStatus.ERLEDIGT -> planDonePoints
                    PlanSessionStatus.TEILWEISE -> planPartialPoints
                    else -> 0
                }
            }
        }
    }

    // Wochenziel: je Woche, deren Ziel erreicht ist (Planwoche vor mitwachsendem Ziel).
    var week = 0
    for (w in weekGoals(input, facts)) {
        if (w.targetKm > 0 && w.riddenKm >= w.targetKm && input.counts(w.monday.plusDays(6))) week += weekGoalPoints
    }

    // Monats-Challenges: je erreichter Challenge, je erreichtem Bonus.
    var challenge = 0
    for (m in monthTimelines(facts, input.today)) {
        if (!input.counts(m.month.atEndOfMonth())) continue
        challenge += m.reachedAtEnd * challengeReachedPoints + m.bonusAtEnd * challengeBonusPoints
    }

    return AchievementPoints(ride = ride, plan = plan, week = week, challenge = challenge)
}

private class WeekGoalFact(
    val monday: LocalDate,
    val targetKm: Int,
    val riddenKm: Double,
    val rides: Int,
    val kind: WeeklyGoalKind?,
    val planKind: WeekKind?,
)

/** Je Woche von der ersten Tour bis heute: Ziel, gefahrene km, Art. */
private fun weekGoals(input: AchievementInput, facts: List<RideFact>): List<WeekGoalFact> {
    if (facts.isEmpty()) return emptyList()
    val planWeeks = input.plan?.weeks.orEmpty().associateBy { it.monday }
    val byWeek = facts.groupBy { mondayOfWeek(it.date) }
    val thisMonday = mondayOfWeek(input.today)
    val result = ArrayList<WeekGoalFact>()
    var monday = mondayOfWeek(facts.first().date)
    while (!monday.isAfter(thisMonday)) {
        val sunday = monday.plusDays(6)
        val inWeek = byWeek[monday].orEmpty()
        val km = inWeek.sumOf { it.distanceKm }
        val planWeek = planWeeks[monday]?.takeIf { it.targetKm > 0 }
        if (planWeek != null) {
            result += WeekGoalFact(monday, planWeek.targetKm, km, inWeek.size, null, planWeek.kind)
        } else {
            val goal = suggestWeeklyGoal(facts, if (sunday.isAfter(input.today)) input.today else sunday)
            result += WeekGoalFact(monday, goal?.targetKm ?: 0, km, inWeek.size, goal?.kind, null)
        }
        monday = monday.plusWeeks(1)
    }
    return result
}

private class MonthTimeline(
    val month: YearMonth,
    val total: Int,
    val reachedAtEnd: Int,
    val bonusAtEnd: Int,
    /** Tour, mit der alle Challenges des Monats erreicht waren. */
    val allReached: RideFact?,
    /** Tour, mit der die erste Bonusstufe erreicht war. */
    val firstBonus: RideFact?,
)

/** Je Monat mit Touren: Challenges, Stand am Ende (bzw. heute) und die entscheidenden Touren. */
private fun monthTimelines(facts: List<RideFact>, today: LocalDate): List<MonthTimeline> {
    val byMonth = facts.filter { !it.date.isAfter(today) }.groupBy { YearMonth.from(it.date) }
    return byMonth.keys.sorted().map { month ->
        val challenges = monthlyChallenges(facts, month)
        val rides = byMonth.getValue(month)
        var km = 0.0
        var ascent = 0.0
        val days = HashSet<LocalDate>()
        var longest = 0.0
        var allReached: RideFact? = null
        var firstBonus: RideFact? = null
        var progress: List<ChallengeProgress> = challenges.map { ChallengeProgress(it, 0.0, 0) }
        for (ride in rides) {
            km += ride.distanceKm
            ascent += ride.ascentM
            days += ride.date
            longest = max(longest, ride.distanceKm)
            progress = challenges.map {
                val current = when (it.kind) {
                    ChallengeKind.DISTANCE -> km
                    ChallengeKind.ASCENT -> ascent
                    ChallengeKind.ACTIVE_DAYS -> days.size.toDouble()
                    ChallengeKind.LONG_RIDE -> longest
                }
                ChallengeProgress(it, current, 0)
            }
            if (allReached == null && progress.isNotEmpty() && progress.all { it.reached }) allReached = ride
            if (firstBonus == null && progress.any { it.hasBonus && it.bonusReached }) firstBonus = ride
        }
        MonthTimeline(
            month = month,
            total = challenges.size,
            reachedAtEnd = progress.count { it.reached },
            bonusAtEnd = progress.count { it.hasBonus && it.bonusReached },
            allReached = allReached,
            firstBonus = firstBonus,
        )
    }
}

private fun state(
    id: AchievementId,
    unlock: AchievementUnlock?,
    current: Double? = null,
    target: Double = id.target,
    openStatus: AchievementStatus = AchievementStatus.OPEN,
): AchievementState =
    if (unlock != null) {
        AchievementState(id, AchievementStatus.REACHED, current, target, unlock.rideId, unlock.date)
    } else {
        AchievementState(id, openStatus, current, target)
    }

private fun RideFact.unlock() = AchievementUnlock(id, date)

private const val SECONDS_PER_HOUR = 3600.0

private fun achievementStates(input: AchievementInput, accepted: Set<LocalDate>): List<AchievementState> {
    val today = input.today
    val facts = input.facts.filter { !it.date.isAfter(today) }
    val result = LinkedHashMap<AchievementId, AchievementState>()
    fun put(s: AchievementState) {
        result[s.id] = s
    }

    // --- Einzelne Touren und Summen (1–10, 21, 22) ---------------------------
    fun firstWhere(predicate: (RideFact) -> Boolean) = facts.firstOrNull(predicate)?.unlock()
    put(state(AchievementId.FIRST_RIDE, facts.firstOrNull()?.unlock(), facts.size.coerceAtMost(1).toDouble()))
    val maxKm = facts.maxOfOrNull { it.distanceKm } ?: 0.0
    put(state(AchievementId.RIDE_50_KM, firstWhere { it.distanceKm >= 50.0 }, maxKm))
    put(state(AchievementId.RIDE_100_KM, firstWhere { it.distanceKm >= 100.0 }, maxKm))
    put(
        state(
            AchievementId.RIDE_1000_M,
            firstWhere { it.ascentM >= 1000.0 },
            facts.maxOfOrNull { it.ascentM } ?: 0.0,
        ),
    )
    put(
        state(
            AchievementId.RIDE_4_H,
            firstWhere { (it.timeS ?: 0) >= 4 * 3600 },
            (facts.maxOfOrNull { it.timeS ?: 0 } ?: 0) / SECONDS_PER_HOUR,
        ),
    )
    put(state(AchievementId.RIDES_10, facts.getOrNull(9)?.unlock(), facts.size.toDouble()))
    put(state(AchievementId.RIDES_100, facts.getOrNull(99)?.unlock(), facts.size.toDouble()))

    var km = 0.0
    var ascent = 0.0
    var indoorS = 0.0
    var km1000: AchievementUnlock? = null
    var earth: AchievementUnlock? = null
    var everest: AchievementUnlock? = null
    var indoorFirst: AchievementUnlock? = null
    var indoor10: AchievementUnlock? = null
    for (f in facts) {
        km += f.distanceKm
        ascent += f.ascentM
        if (km1000 == null && km >= 1000.0) km1000 = f.unlock()
        if (earth == null && km >= earthCircumferenceKm) earth = f.unlock()
        if (everest == null && ascent >= everestM) everest = f.unlock()
        if (f.id in input.indoorRideIds) {
            indoorS += (f.timeS ?: 0).coerceAtLeast(0)
            if (indoorFirst == null) indoorFirst = f.unlock()
            if (indoor10 == null && indoorS >= 10 * SECONDS_PER_HOUR) indoor10 = f.unlock()
        }
    }
    put(state(AchievementId.TOTAL_1000_KM, km1000, km))
    put(state(AchievementId.TOTAL_EARTH, earth, km))
    put(state(AchievementId.TOTAL_EVEREST, everest, ascent))

    // --- Serie (11–13) ------------------------------------------------------
    val thresholds = listOf(4, 12, 52)
    val timeline = streakTimeline(facts, accepted, today, thresholds)
    val firstRideOfWeek = facts.groupBy { mondayOfWeek(it.date) }.mapValues { it.value.first() }
    for ((id, n) in listOf(
        AchievementId.STREAK_4 to 4,
        AchievementId.STREAK_12 to 12,
        AchievementId.STREAK_52 to 52,
    )) {
        val unlock = timeline.reachedAt[n]?.let { monday ->
            firstRideOfWeek[monday]?.unlock() ?: AchievementUnlock(null, monday)
        }
        put(state(id, unlock, timeline.current.toDouble()))
    }

    // --- Monats-Challenges (14, 15) ------------------------------------------
    val months = monthTimelines(facts, today)
    val thisMonth = months.lastOrNull()?.takeIf { it.month == YearMonth.from(today) }
    val monthTarget = thisMonth?.total?.toDouble() ?: monthlyChallenges(facts, YearMonth.from(today)).size.toDouble()
    put(
        state(
            AchievementId.MONTH_COMPLETE,
            months.firstNotNullOfOrNull { it.allReached }?.unlock(),
            (thisMonth?.reachedAtEnd ?: 0).toDouble(),
            target = monthTarget,
        ),
    )
    put(state(AchievementId.CHALLENGE_BONUS, months.firstNotNullOfOrNull { it.firstBonus }?.unlock()))

    // --- Jahresziel (16) ------------------------------------------------------
    val goal = input.yearGoal
    if (goal == null) {
        put(state(AchievementId.YEAR_GOAL, null, null, target = 0.0))
    } else {
        var unlock: AchievementUnlock? = null
        val target = goal.targetKm.toDouble()
        for (year in goal.since.year..today.year) {
            var inYear = 0.0
            for (f in facts) {
                if (f.date.year != year) continue
                inYear += f.distanceKm
                if (inYear >= target) {
                    unlock = f.unlock()
                    break
                }
            }
            if (unlock != null) break
        }
        val thisYear = facts.filter { it.date.year == today.year }.sumOf { it.distanceKm }
        put(state(AchievementId.YEAR_GOAL, unlock, thisYear, target = target))
    }

    // --- Plan (17–20), Erholung auch ohne Plan (19) ---------------------------
    putPlanStates(input, facts, ::put)

    // --- Drinnen (21–24) ------------------------------------------------------
    put(state(AchievementId.INDOOR_FIRST, indoorFirst))
    put(state(AchievementId.INDOOR_10_H, indoor10, indoorS / SECONDS_PER_HOUR))
    putSourceList(AchievementId.GHOST_BEATEN, input.sources.ghostWins, ::put)
    putSourceList(AchievementId.KNOWN_ROUTE, input.sources.knownRouteCompletions, ::put)

    // --- Entdecken (25–30) ----------------------------------------------------
    val climbs = input.sources.climbs
    if (climbs == null) {
        put(AchievementState(AchievementId.CLIMB_REPEAT, AchievementStatus.COMING_SOON))
        put(AchievementState(AchievementId.CLIMB_FASTER, AchievementStatus.COMING_SOON))
    } else {
        put(state(AchievementId.CLIMB_REPEAT, climbs.firstRepeatedClimb))
        put(state(AchievementId.CLIMB_FASTER, climbs.firstImprovedBest))
    }

    val rounds = input.sources.rounds
    put(
        when {
            rounds == null -> AchievementState(AchievementId.ROUND_5, AchievementStatus.UNKNOWN)
            rounds.maxRepeats >= 5 -> AchievementState(
                AchievementId.ROUND_5,
                AchievementStatus.REACHED,
                rounds.maxRepeats.toDouble(),
                rideId = rounds.fifthRide?.rideId,
                date = rounds.fifthRide?.date,
            )
            else -> AchievementState(AchievementId.ROUND_5, AchievementStatus.OPEN, rounds.maxRepeats.toDouble())
        },
    )

    val exploration = input.sources.exploration
    for ((id, value) in listOf(
        AchievementId.TILES_100 to exploration?.tileCount,
        AchievementId.TILES_SQUARE_5 to exploration?.largestSquare,
    )) {
        put(countState(id, value?.toDouble()))
    }
    put(countState(AchievementId.NEW_WAYS_100_KM, input.sources.distinctWaysKm))

    // --- Festgehaltene Abzeichen ----------------------------------------------
    return AchievementId.entries.map { id ->
        val derived = result.getValue(id)
        val earnedOn = input.progress.earned[id.key]
        if (!derived.reached && earnedOn != null) {
            derived.copy(status = AchievementStatus.REACHED, rideId = null, date = earnedOn)
        } else {
            derived
        }
    }
}

/** Ein Abzeichen mit reiner Schwelle ohne bekannten Tag (Kacheln, Wege). */
private fun countState(id: AchievementId, value: Double?): AchievementState = when {
    value == null -> AchievementState(id, AchievementStatus.UNKNOWN)
    value >= id.target -> AchievementState(id, AchievementStatus.REACHED, value)
    else -> AchievementState(id, AchievementStatus.OPEN, value)
}

private fun putSourceList(
    id: AchievementId,
    unlocks: List<AchievementUnlock>?,
    put: (AchievementState) -> Unit,
) {
    if (unlocks == null) {
        put(AchievementState(id, AchievementStatus.COMING_SOON))
    } else {
        put(state(id, unlocks.minByOrNull { it.date }))
    }
}

private fun putPlanStates(input: AchievementInput, facts: List<RideFact>, put: (AchievementState) -> Unit) {
    val today = input.today
    val dateOfRide = facts.associate { it.id to it.date }
    val weeks = input.plan?.weeks.orEmpty().sortedBy { it.monday }
    fun over(week: PlanWeekFact) = !today.isBefore(week.monday.plusDays(7L + sessionMatchToleranceDays))
    fun sessionUnlock(s: PlanSessionFact): AchievementUnlock? {
        val date = s.rideId?.let(dateOfRide::get) ?: s.date ?: return null
        return AchievementUnlock(s.rideId, date)
    }

    // 17: erste erledigte Einheit.
    val firstDone = weeks.flatMap { it.sessions }
        .filter { it.status == PlanSessionStatus.ERLEDIGT }
        .mapNotNull(::sessionUnlock)
        .minByOrNull { it.date }
    put(state(AchievementId.PLAN_FIRST, firstDone))

    // 18: eine Planwoche ganz erledigt oder teilweise, mindestens eine erledigt.
    val fullWeek = weeks.firstNotNullOfOrNull { week ->
        val s = week.sessions
        val full = s.isNotEmpty() &&
            s.all { it.status == PlanSessionStatus.ERLEDIGT || it.status == PlanSessionStatus.TEILWEISE } &&
            s.any { it.status == PlanSessionStatus.ERLEDIGT }
        if (full) s.mapNotNull(::sessionUnlock).maxByOrNull { it.date } else null
    }
    val currentWeek = weeks.firstOrNull { !today.isBefore(it.monday) && today.isBefore(it.monday.plusDays(7)) }
    put(
        state(
            AchievementId.PLAN_FULL_WEEK,
            fullWeek,
            current = currentWeek?.sessions?.count {
                it.status == PlanSessionStatus.ERLEDIGT || it.status == PlanSessionStatus.TEILWEISE
            }?.toDouble(),
            target = currentWeek?.sessions?.size?.toDouble() ?: 0.0,
        ),
    )

    // 19: Erholungswoche eingehalten — aus dem Plan oder, ohne Plan, die
    // Entlastungswoche des mitwachsenden Wochenziels.
    val recovery = weekGoals(input, facts)
        .filter { w ->
            val recoveryWeek = w.planKind == WeekKind.ERHOLUNG ||
                (w.planKind == null && w.kind == WeeklyGoalKind.ENTLASTUNG)
            val weekOver = today.isAfter(w.monday.plusDays(6))
            recoveryWeek && weekOver && w.rides > 0 && w.targetKm > 0 &&
                w.riddenKm <= w.targetKm * recoveryWeekTolerance + 1e-9
        }
        .minByOrNull { it.monday }
        ?.let { AchievementUnlock(null, it.monday.plusDays(6)) }
    put(state(AchievementId.RECOVERY_WEEK, recovery))

    // 20: Plan bis zum Ziel mit mindestens 80 % erledigten Einheiten.
    val sessions = weeks.flatMap { it.sessions }
    if (sessions.isEmpty()) {
        put(state(AchievementId.PLAN_FINISHED, null))
    } else {
        val share = sessions.count { it.status == PlanSessionStatus.ERLEDIGT }.toDouble() / sessions.size
        val finished = weeks.all(::over) && share >= planFinishedShare - 1e-9
        val unlock = if (finished) AchievementUnlock(null, weeks.last().monday.plusDays(6)) else null
        put(state(AchievementId.PLAN_FINISHED, unlock, current = share * 100.0))
    }
}

// ---------------------------------------------------------------------------
// Highlights nach der Fahrt
// ---------------------------------------------------------------------------

/**
 * [highlights] mit den Abzeichen, die die Tour gebracht hat
 * ([RideHighlight.AchievementsUnlocked]), gekuerzt auf [max].
 *
 * Ein neues Abzeichen ist selten und deshalb weit vorn: direkt nach einer
 * neuen Bestweite bzw. Rundenbestzeit, sonst ganz oben. Mehrere Abzeichen
 * einer Tour stehen in **einer** Zeile.
 */
fun withAchievementHighlight(
    highlights: List<RideHighlight>,
    unlocked: List<AchievementId>,
    max: Int,
): List<RideHighlight> {
    if (unlocked.isEmpty() || max <= 0) return highlights.take(max.coerceAtLeast(0))
    val lead = highlights.takeWhile {
        (it is RideHighlight.Record && it.gain.kind == RecordKind.DISTANCE) || it is RideHighlight.RouteBest
    }.size.coerceAtMost(1)
    val result = highlights.toMutableList()
    result.add(lead, RideHighlight.AchievementsUnlocked(unlocked))
    return result.take(max)
}
