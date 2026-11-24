package de.trailscape.core.indoor

import de.trailscape.core.AscentPreference
import de.trailscape.core.RouteTarget
import de.trailscape.core.SessionIntensity
import de.trailscape.core.TodayRoute
import de.trailscape.core.TrackPoint
import de.trailscape.core.TrainingSession
import de.trailscape.core.canGenerateRouteFor
import de.trailscape.core.haversineM
import de.trailscape.core.i18n.SessionTextKey
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * # Die Strecke zur Einheit des Tages
 *
 * Wunsch des Gruenders: nicht nur aus den eigenen Routen eine 3D-Welt bauen,
 * sondern eine Welt fuer das Training, das der Plan heute vorsieht. Diese
 * Datei beantwortet die Frage „welche echte Strecke passt zu dieser Einheit?"
 * — als reine Rechnung ohne Speicher, Netz oder Saetze (`:core` erzeugt fuer
 * Indoor keine Texte, docs/indoor.md 2.5; die App formuliert aus den Zahlen).
 *
 * ## Ablauf
 *  1. [daySessionFor]: welche Einheit heute auf der Rolle gefahren wird —
 *     die Planeinheit, gedaempft durch die Tagesform aus [TodayRoute]
 *     (dieselbe Skalierung wie beim Routenangebot, nicht nachgebaut), an einem
 *     Tag ohne Planeinheit eine kurze lockere Runde, am Ruhetag nichts.
 *  2. [dayRouteGoal]: aus der ERG-Einheit (`ergWorkoutFor`) und dem Fahrer der
 *     gewuenschte Profilcharakter ([DayTerrain]) und die Belastungsabschnitte.
 *  3. [simulateDayRide]: die Einheit auf einer Strecke „nachfahren" — je
 *     Abschnitt das Gleichgewichtstempo bei der Sollleistung aus der
 *     Indoor-Physik ([steadySpeedMps]) auf jedem 50-m-Stueck. Ergebnis:
 *     gefahrene Distanz, wo die Belastungen liegen, ob die Strecke vor der
 *     Einheit endet.
 *  4. [scoreDayRoute] / [bestDayStart]: die Bewertung 0..1 und der beste
 *     Startpunkt (ein Abschnitt einer laengeren Strecke, z. B. so, dass die
 *     Intervalle in die Anstiege fallen).
 *  5. [rankDayRoutes]: alle Kandidaten der Reihe nach; bei annaehernd gleicher
 *     Passung gewinnt die Strecke ohne Bauzeit ([DayRouteAvailability]).
 *
 * ## Warum Gleichgewichtstempo statt der vollen Sitzung
 * Die Sitzung integriert mit Traegheit zehnmal je Sekunde; fuer eine Auswahl
 * unter dutzenden Strecken mit je vierzig Startpunkten ist das zu teuer und
 * auch nicht noetig: Die Traegheit verschiebt eine Stelle um Sekunden, die
 * Bewertung fragt nach Minuten. Das Tempo kommt aus derselben Physik
 * ([steadySpeedMps], Crr fuer festen Weg), also passt die Distanz zu dem, was
 * die Fahrt im ERG-Modus tatsaechlich zuruecklegt (im Test gegen die Sitzung
 * geprueft).
 */

// ---------------------------------------------------------------------------
// 1. Welche Einheit heute
// ---------------------------------------------------------------------------

/** Was heute auf der Rolle ansteht. */
enum class DaySessionKind {
    /** Die Planeinheit unveraendert. */
    PLANNED,

    /** Die Planeinheit, von der Tagesform gekuerzt oder entschaerft ([TodayRoute.downgraded]). */
    PLANNED_REDUCED,

    /** Keine Planeinheit (kein Plan oder Ruhetag im Plan), die Tagesform erlaubt eine Runde: kurz, locker, flach. */
    EASY,

    /** Die Tagesform raet zur Pause — kein Angebot ([TodayRoute.target] ist `null`). */
    REST,

    /** Heute ist das Zielevent; es hat eine eigene Strecke und ist kein Rollentraining. */
    EVENT,
}

/**
 * @property session die Einheit, aus der die ERG-Einheit entsteht (`ergWorkoutFor`);
 *   `null` bei [DaySessionKind.REST] und [DaySessionKind.EVENT]. Bei
 *   [DaySessionKind.EASY] eine erzeugte Einheit ohne Plantext (Titel setzt die App).
 * @property planned die Planeinheit, wie sie im Plan steht (zum Vergleich in der
 *   Oberflaeche: „statt 90 min"); `null` ohne Planeinheit.
 */
data class DaySession(val kind: DaySessionKind, val session: TrainingSession?, val planned: TrainingSession?)

/** Hoechstdauer der lockeren Runde an einem Tag ohne Planeinheit, in Minuten. */
const val DAY_EASY_MAX_MIN: Int = 45

/** Mindestdauer der lockeren Runde in Minuten. */
const val DAY_EASY_MIN_MIN: Int = 20

/**
 * Leitet die Einheit fuer die Rolle aus der Tagesentscheidung [today] ab
 * (`decideTodayRoute`). Die Faelle folgen dort:
 *
 *  * Zieltag → [DaySessionKind.EVENT].
 *  * Kein Routenziel (Ruhetag nach Tagesform) → [DaySessionKind.REST]; eine
 *    Planeinheit wird dann auch auf der Rolle nicht angeboten — derselbe
 *    Grund wie beim Routenangebot: Ein Angebot an einem Pausentag waere ein
 *    Widerspruch.
 *  * Keine Planeinheit → [DaySessionKind.EASY]: locker, hoechstens
 *    [DAY_EASY_MAX_MIN] min (kuerzer, wenn die Tagesempfehlung kuerzer ist),
 *    mindestens [DAY_EASY_MIN_MIN] min. Bewusst nicht die volle
 *    Tagesempfehlung („Grundlage 2 h"): Wer ohne Plan auf die Rolle steigt,
 *    bekommt keine geratene lange Einheit, sondern eine Runde zum Bewegen.
 *  * Planeinheit, nicht gedaempft → [DaySessionKind.PLANNED].
 *  * Planeinheit, gedaempft (Distanz, Hoehenprofil oder Intensitaet) →
 *    [DaySessionKind.PLANNED_REDUCED]: Dauer mal [TodayRoute.factor],
 *    Intensitaet aus dem Routenziel. Wird die Intensitaet zurueckgenommen,
 *    entfaellt die Intervallstruktur des Plantexts (aus „4 × 8 min" wird eine
 *    gleichmaessige Einheit) — sonst stuende auf der Rolle, was die Tagesform
 *    gerade ausgeschlossen hat.
 */
fun daySessionFor(today: TodayRoute): DaySession {
    val planned = today.session
    if (planned != null && !canGenerateRouteFor(planned)) {
        return DaySession(DaySessionKind.EVENT, null, planned)
    }
    val target = today.target ?: return DaySession(DaySessionKind.REST, null, planned)
    if (planned == null) {
        val targetMin = target.durationH?.takeIf { it.isFinite() && it > 0 }?.let { (it * 60).roundToInt() } ?: DAY_EASY_MAX_MIN
        val minutes = targetMin.coerceIn(DAY_EASY_MIN_MIN, DAY_EASY_MAX_MIN)
        val km = (target.speedKmh.takeIf { it.isFinite() && it > 0 } ?: 25.0) * minutes / 60.0
        val easy = TrainingSession(
            day = "",
            title = target.label,
            description = "",
            targetKm = km.roundToInt().coerceAtLeast(1),
            intensity = SessionIntensity.LOCKER,
            durationMin = minutes,
        )
        return DaySession(DaySessionKind.EASY, easy, null)
    }
    val intensityCut = target.intensity < planned.intensity
    if (!today.downgraded && !intensityCut) {
        return DaySession(DaySessionKind.PLANNED, planned, planned)
    }
    val factor = today.factor.takeIf { it.isFinite() && it > 0 } ?: 1.0
    val minutes = (sessionDurationMin(planned) * factor).roundToInt().coerceAtLeast(MIN_DURATION_MIN)
    val reduced = planned.copy(
        intensity = target.intensity,
        durationMin = minutes,
        targetKm = max(1, target.distanceKm.roundToInt()),
        textKey = if (intensityCut) null else planned.textKey,
        textArgs = if (intensityCut) emptyList() else planned.textArgs,
    )
    // Ein Intervall-Plantext mit gekuerzter Dauer passt nicht mehr zu seinen Argumenten.
    val consistent = if (reduced.textKey == SessionTextKey.INTERVALS && minutes != sessionDurationMin(planned)) {
        reduced.copy(textKey = null, textArgs = emptyList())
    } else {
        reduced
    }
    return DaySession(DaySessionKind.PLANNED_REDUCED, consistent, planned)
}

/** Dauer einer Einheit in Minuten, wie die ERG-Einheit sie rechnet (`durationMin`, sonst aus km). */
fun sessionDurationMinutes(session: TrainingSession): Int = sessionDurationMin(session)

// ---------------------------------------------------------------------------
// 2. Hoehenprofil einer Kandidatenstrecke
// ---------------------------------------------------------------------------

/**
 * Das Hoehenprofil einer Strecke in festen Stuecken von [DayRouteProfile.STEP_M]
 * (das letzte kuerzer), geglaettet — so grob, wie die Bewertung es braucht.
 *
 * @property gradePct Steigung je Stueck in Prozent (−25..+25).
 * @property estimated aus Laenge und Hoehenmetern geschaetzt (Katalogstrecke
 *   vor dem Laden), nicht gemessen — die Bewertung nimmt dann einen
 *   Unsicherheitsabschlag ([ESTIMATE_FACTOR]) und schlaegt keinen Startpunkt vor.
 */
class DayRouteProfile private constructor(
    val lengthM: Double,
    val isLoop: Boolean,
    val gradePct: DoubleArray,
    val ascentM: Double,
    val estimated: Boolean,
) {
    val steps: Int get() = gradePct.size

    /** Laenge des Stuecks [i] in Metern. */
    fun stepLengthM(i: Int): Double = if (i < steps - 1) STEP_M else lengthM - STEP_M * (steps - 1)

    /** Index des Stuecks an der Stelle [m] (0..Laenge). */
    fun stepAt(m: Double): Int = (m / STEP_M).toInt().coerceIn(0, steps - 1)

    companion object {
        /** Laenge eines Stuecks. */
        const val STEP_M: Double = 50.0

        /** Glaettung der Hoehe: gleitendes Mittel ueber so viele Stuecke je Seite (±100 m). */
        private const val SMOOTH_STEPS: Int = 2

        /** Kuerzer bewertet die Auswahl keine Strecke. */
        const val MIN_LENGTH_M: Double = 500.0

        /**
         * Aus Laufmetern und Hoehen (aufsteigend, gleich lang). `null`, wenn die
         * Strecke kuerzer als [MIN_LENGTH_M] ist oder die Werte unbrauchbar sind.
         */
        fun fromSamples(distM: DoubleArray, eleM: DoubleArray, isLoop: Boolean): DayRouteProfile? {
            if (distM.size < 2 || distM.size != eleM.size) return null
            val length = distM.last() - distM.first()
            if (!length.isFinite() || length < MIN_LENGTH_M) return null
            val n = ceil(length / STEP_M - 1e-9).toInt().coerceAtLeast(1)
            // Hoehe an den n + 1 Stuetzstellen (linear interpoliert).
            val raw = DoubleArray(n + 1)
            var j = 0
            for (k in 0..n) {
                val m = distM.first() + min(k * STEP_M, length)
                while (j < distM.size - 2 && distM[j + 1] < m) j++
                val d0 = distM[j]
                val d1 = distM[j + 1]
                val e0 = eleM[j].takeIf { it.isFinite() } ?: 0.0
                val e1 = eleM[j + 1].takeIf { it.isFinite() } ?: e0
                val t = if (d1 > d0) ((m - d0) / (d1 - d0)).coerceIn(0.0, 1.0) else 0.0
                raw[k] = e0 + (e1 - e0) * t
            }
            val smooth = DoubleArray(n + 1) { k ->
                val lo = max(0, k - SMOOTH_STEPS)
                val hi = min(n, k + SMOOTH_STEPS)
                var s = 0.0
                for (q in lo..hi) s += raw[q]
                s / (hi - lo + 1)
            }
            var ascent = 0.0
            val grades = DoubleArray(n) { k ->
                val stepLen = if (k < n - 1) STEP_M else length - STEP_M * (n - 1)
                val dz = smooth[k + 1] - smooth[k]
                if (dz > 0) ascent += dz
                if (stepLen > 1e-6) (dz / stepLen * 100).coerceIn(IndoorRoute.MIN_GRADE_PCT, IndoorRoute.MAX_GRADE_PCT) else 0.0
            }
            return DayRouteProfile(length, isLoop, grades, ascent, estimated = false)
        }

        /** Aus einer Indoor-Strecke (Streckenband einer Welt oder aufbereitete Tour). */
        fun of(route: IndoorRoute): DayRouteProfile? = fromSamples(
            DoubleArray(route.points.size) { route.points[it].distM },
            DoubleArray(route.points.size) { route.points[it].eleM },
            route.isLoop,
        )

        /**
         * Aus den Punkten einer gespeicherten Planung oder Tour. `null` ohne
         * Hoehen (kein einziger Punkt mit `ele`) — dann laesst sich das Profil
         * nicht beurteilen, und eine flache Annahme waere eine Behauptung.
         * Runde wie [IndoorRoute.isLoop]: Start und Ziel hoechstens
         * [IndoorRoute.LOOP_CLOSE_M] auseinander.
         */
        fun ofTrack(points: List<TrackPoint>): DayRouteProfile? {
            if (points.size < 2 || points.none { it.ele?.isFinite() == true }) return null
            val dist = DoubleArray(points.size)
            for (i in 1 until points.size) dist[i] = dist[i - 1] + haversineM(points[i - 1], points[i])
            val firstEle = points.first { it.ele?.isFinite() == true }.ele!!
            var last = firstEle
            val ele = DoubleArray(points.size) { i ->
                val e = points[i].ele?.takeIf { it.isFinite() } ?: last
                last = e
                e
            }
            val loop = haversineM(points.first(), points.last()) <= IndoorRoute.LOOP_CLOSE_M
            return fromSamples(dist, ele, loop)
        }

        /**
         * Grobe Schaetzung aus Laenge und Hoehenmetern (Katalogstrecke vor dem
         * Laden: Der Katalog kennt nur diese beiden Zahlen). Steiler als
         * [SINGLE_CLIMB_AVG_PCT] im Mittel gilt als ein durchgehender Anstieg
         * (Passstrassen), sonst als Wellen aus 1-km-Stuecken, je bergauf und
         * bergab im Wechsel.
         */
        fun estimate(lengthM: Double, ascentM: Double, isLoop: Boolean): DayRouteProfile? {
            if (!lengthM.isFinite() || lengthM < MIN_LENGTH_M) return null
            val up = if (ascentM.isFinite()) max(0.0, ascentM) else 0.0
            val n = ceil(lengthM / STEP_M - 1e-9).toInt().coerceAtLeast(1)
            val avg = up / lengthM * 100
            val grades = if (!isLoop && avg >= SINGLE_CLIMB_AVG_PCT) {
                DoubleArray(n) { avg.coerceAtMost(IndoorRoute.MAX_GRADE_PCT) }
            } else {
                val wave = (2 * avg).coerceAtMost(IndoorRoute.MAX_GRADE_PCT)
                val per = (WAVE_M / STEP_M).toInt()
                DoubleArray(n) { k -> if ((k / per) % 2 == 0) wave else -wave }
            }
            return DayRouteProfile(lengthM, isLoop, grades, up, estimated = true)
        }

        /** Ab diesem mittleren Anstieg gilt eine geschaetzte Strecke als ein durchgehender Berg. */
        const val SINGLE_CLIMB_AVG_PCT: Double = 4.0

        private const val WAVE_M: Double = 1000.0
    }
}

// ---------------------------------------------------------------------------
// 3. Das Ziel: Profilcharakter und Belastungen
// ---------------------------------------------------------------------------

/** Welches Gelaende zur Einheit passt. */
enum class DayTerrain {
    /** Erholung, lockere Runde: flach. */
    FLAT,

    /** Grundlage: flach bis wellig, keine langen steilen Stuecke. */
    ROLLING,

    /** Intervalle: Anstiege, deren Dauer zur Belastung passt. */
    CLIMBS,
}

/** Ein Belastungsabschnitt der Einheit: ab [startS] fuer [durationS] Sekunden mit [ftpFraction] der FTP. */
data class DayInterval(val startS: Int, val durationS: Int, val ftpFraction: Double)

/**
 * @property expectedFlatM Distanz der Einheit auf flacher Strecke bei den
 *   Sollleistungen — die Groessenordnung fuer die Oberflaeche („etwa 32 km");
 *   die Bewertung rechnet je Strecke selbst ([simulateDayRide]).
 */
data class DayRouteGoal(
    val workout: ErgWorkout,
    val rider: RiderSetup,
    val terrain: DayTerrain,
    val intervals: List<DayInterval>,
    val expectedFlatM: Double,
) {
    val totalS: Int get() = workout.totalS
}

/** Ab diesem Anteil der FTP ist ein Belastungsabschnitt ein Intervall, das an den Berg gehoert. */
const val DAY_INTERVAL_MIN_FRACTION: Double = 0.85

/** Ab diesem Anteil der FTP (gleichmaessiger Abschnitt) darf es wellig sein. */
const val DAY_ROLLING_MIN_FRACTION: Double = 0.62

/** Steigung, ab der ein Stueck als Anstieg zaehlt, in Prozent. */
const val DAY_CLIMB_GRADE_PCT: Double = 3.0

/** Steigung, ab der ein Stueck fuer eine Grundlageneinheit zu steil ist, in Prozent. */
const val DAY_STEEP_GRADE_PCT: Double = 6.0

/**
 * Das Ziel der Einheit [workout] fuer den Fahrer [rider]:
 *  * Belastungsabschnitte ([ErgStepKind.WORK]) ab [DAY_INTERVAL_MIN_FRACTION]
 *    → [DayTerrain.CLIMBS];
 *  * sonst ein gleichmaessiger Abschnitt ab [DAY_ROLLING_MIN_FRACTION]
 *    (Grundlage, 0,68) → [DayTerrain.ROLLING];
 *  * sonst (locker, 0,55; Erholung) → [DayTerrain.FLAT].
 */
fun dayRouteGoal(workout: ErgWorkout, rider: RiderSetup): DayRouteGoal {
    val intervals = mutableListOf<DayInterval>()
    var t = 0
    for (s in workout.steps) {
        if (s.kind == ErgStepKind.WORK && s.ftpFraction >= DAY_INTERVAL_MIN_FRACTION) {
            intervals += DayInterval(t, s.durationS, s.ftpFraction)
        }
        t += s.durationS
    }
    val terrain = when {
        intervals.isNotEmpty() -> DayTerrain.CLIMBS
        workout.steps.any { it.ftpFraction >= DAY_ROLLING_MIN_FRACTION } -> DayTerrain.ROLLING
        else -> DayTerrain.FLAT
    }
    val ftp = ftpOf(rider)
    val flat = workout.steps.sumOf { it.durationS * daySpeedMps(it.ftpFraction * ftp, 0.0, rider) }
    return DayRouteGoal(workout, rider, terrain, intervals, flat)
}

private fun ftpOf(rider: RiderSetup): Double = rider.ftpW.takeIf { it.isFinite() && it > 0 } ?: 200.0

/** Untergrenze des Tempos in der Rechnung (auch eine Erholung am steilen Berg kommt voran). */
private const val MIN_SPEED_MPS: Double = 0.5

internal fun daySpeedMps(powerW: Double, gradePct: Double, rider: RiderSetup): Double = max(
    MIN_SPEED_MPS,
    steadySpeedMps(powerW, gradePct, effectiveCrr(SurfaceKind.COMPACTED, rider.profileCrr), 0.0, 0.0, rider),
)

// ---------------------------------------------------------------------------
// 4. Die Einheit auf einer Strecke nachfahren
// ---------------------------------------------------------------------------

/**
 * Ergebnis von [simulateDayRide].
 *
 * @property distanceM gefahrene Strecke waehrend der Einheit (ueber Runden fortlaufend).
 * @property endM Laufmeter am Ende (ab Streckenanfang, ueber Runden fortlaufend).
 * @property routeEndsAtS auf einer Strecke ohne Rundschluss: Sekunde der
 *   Einheit, zu der das Ziel erreicht ist; `null`, wenn die Strecke reicht.
 * @property laps angefangene Runden (1 ohne Rundschluss).
 * @property intervalClimbShare je Belastungsabschnitt der Anteil seiner Zeit
 *   auf Anstieg (≥ [DAY_CLIMB_GRADE_PCT]); Zeit nach dem Streckenende zaehlt nicht.
 * @property intervalGradePct je Belastungsabschnitt die mittlere Steigung.
 * @property climbShare Anteil der ganzen Einheit auf Anstieg.
 * @property steepShare Anteil der ganzen Einheit an Stuecken ab [DAY_STEEP_GRADE_PCT].
 */
data class DayRideSim(
    val startM: Double,
    val distanceM: Double,
    val endM: Double,
    val routeEndsAtS: Int?,
    val laps: Int,
    val intervalClimbShare: List<Double>,
    val intervalGradePct: List<Double>,
    val climbShare: Double,
    val steepShare: Double,
)

/**
 * Faehrt die Einheit von [goal] auf [profile] ab Laufmeter [startM] nach:
 * Abschnitt fuer Abschnitt, Stueck fuer Stueck mit dem Gleichgewichtstempo bei
 * der Sollleistung (Begruendung im Dateikopf). Auf einer Runde geht es nach
 * der Ziellinie von vorn weiter (wie die Sitzung), auf einer Strecke ohne
 * Rundschluss endet die Rechnung im Ziel.
 */
fun simulateDayRide(goal: DayRouteGoal, profile: DayRouteProfile, startM: Double): DayRideSim {
    val ftp = ftpOf(goal.rider)
    val cache = HashMap<Long, Double>()
    fun speed(fraction: Double, step: Int): Double {
        val key = (Math.round(fraction * 1000) shl 20) or step.toLong()
        return cache.getOrPut(key) { daySpeedMps(fraction * ftp, profile.gradePct[step], goal.rider) }
    }
    val length = profile.lengthM
    var lapStart = 0.0
    var pos = startM.coerceIn(0.0, length)
    val start = pos
    var ended: Int? = null
    var tAll = 0.0
    var climbT = 0.0
    var steepT = 0.0
    val intervalClimb = DoubleArray(goal.intervals.size)
    val intervalGrade = DoubleArray(goal.intervals.size)
    var stepStartS = 0
    var intervalIdx = 0
    for (s in goal.workout.steps) {
        val isInterval = intervalIdx < goal.intervals.size && goal.intervals[intervalIdx].startS == stepStartS &&
            s.kind == ErgStepKind.WORK
        var left = s.durationS.toDouble()
        var climbInStep = 0.0
        var gradeTime = 0.0
        while (left > 1e-9 && ended == null) {
            if (pos >= length - 1e-9) {
                if (profile.isLoop) {
                    lapStart += length
                    pos = 0.0
                } else {
                    ended = (tAll).roundToInt()
                    break
                }
            }
            val k = profile.stepAt(pos)
            val stepEnd = min(length, k * DayRouteProfile.STEP_M + profile.stepLengthM(k))
            val v = speed(s.ftpFraction, k)
            val toEnd = max(stepEnd - pos, 1e-6)
            val dt = min(left, toEnd / v)
            pos = if (dt >= toEnd / v) stepEnd else pos + v * dt
            val g = profile.gradePct[k]
            if (g >= DAY_CLIMB_GRADE_PCT) {
                climbT += dt
                climbInStep += dt
            }
            if (g >= DAY_STEEP_GRADE_PCT) steepT += dt
            gradeTime += g * dt
            left -= dt
            tAll += dt
        }
        if (isInterval) {
            intervalClimb[intervalIdx] = climbInStep / s.durationS
            intervalGrade[intervalIdx] = gradeTime / s.durationS
            intervalIdx++
        }
        stepStartS += s.durationS
        if (ended != null) {
            // Restliche Intervalle liegen hinter dem Ziel: kein Anstieg.
            break
        }
    }
    val endM = lapStart + pos
    val total = goal.totalS.toDouble().coerceAtLeast(1.0)
    return DayRideSim(
        startM = start,
        distanceM = endM - start,
        endM = endM,
        routeEndsAtS = ended,
        laps = if (profile.isLoop) max(1, ceil(endM / length - 1e-9).toInt()) else 1,
        intervalClimbShare = intervalClimb.toList(),
        intervalGradePct = intervalGrade.toList(),
        climbShare = climbT / total,
        steepShare = steepT / total,
    )
}

// ---------------------------------------------------------------------------
// 5. Bewertung und Startpunkt
// ---------------------------------------------------------------------------

/** Unsicherheitsabschlag fuer ein geschaetztes Profil ([DayRouteProfile.estimated]). */
const val ESTIMATE_FACTOR: Double = 0.92

/** Gewicht der Distanzpassung in der Gesamtbewertung (der Rest ist das Gelaende). */
const val DAY_DISTANCE_WEIGHT: Double = 0.45

/**
 * Wie gut [sim] zur Einheit passt, 0 (gar nicht) bis 1 (genau).
 *
 * **Distanz** (Gewicht [DAY_DISTANCE_WEIGHT]):
 *  * Strecke ohne Rundschluss endet vor der Einheit: Anteil der geschafften
 *    Zeit hoch 1,5 — wer nach der Haelfte im Ziel steht, hat keine passende
 *    Strecke (0,35).
 *  * Strecke laenger als die Einheit: bis −0,2 fuer die Laenge, die nicht
 *    gefahren wird (Streckenlaenge minus gefahrene Strecke, im Verhaeltnis zur
 *    gefahrenen; ein Rest doppelt so lang wie die Fahrt kostet alles) —
 *    unabhaengig vom Startpunkt. Ein Abschnitt einer langen Strecke ist
 *    ausdruecklich erlaubt (Startpunkt), deshalb kostet der Rest wenig; eine
 *    Strecke, die genau passt, ist trotzdem besser.
 *  * Runde, mehrfach gefahren: bis −0,3 fuer viele Runden (ab der siebten
 *    voll) — eine 3-km-Runde zwoelfmal ist fahrbar, aber eintoenig.
 *
 * **Gelaende**:
 *  * [DayTerrain.CLIMBS]: mittlerer Anstiegsanteil der Intervalle, 0,1 + 0,9 · Anteil.
 *  * [DayTerrain.ROLLING]: 1 − 0,7 · min(1, 3 · steiler Anteil).
 *  * [DayTerrain.FLAT]: 1 − 0,8 · min(1, 2 · Anstiegsanteil).
 *
 * Ein geschaetztes Profil bekommt [ESTIMATE_FACTOR].
 */
fun scoreDayRoute(goal: DayRouteGoal, profile: DayRouteProfile, sim: DayRideSim): Double {
    val distanceFit = distanceFit(goal, profile, sim)
    val terrainFit = when (goal.terrain) {
        DayTerrain.CLIMBS -> 0.1 + 0.9 * (if (sim.intervalClimbShare.isEmpty()) 0.0 else sim.intervalClimbShare.average())
        DayTerrain.ROLLING -> 1 - 0.7 * min(1.0, 3 * sim.steepShare)
        DayTerrain.FLAT -> 1 - 0.8 * min(1.0, 2 * sim.climbShare)
    }
    val raw = DAY_DISTANCE_WEIGHT * distanceFit + (1 - DAY_DISTANCE_WEIGHT) * terrainFit
    return (if (profile.estimated) raw * ESTIMATE_FACTOR else raw).coerceIn(0.0, 1.0)
}

private fun distanceFit(goal: DayRouteGoal, profile: DayRouteProfile, sim: DayRideSim): Double {
    val ended = sim.routeEndsAtS
    if (ended != null) {
        val covered = (ended.toDouble() / goal.totalS.coerceAtLeast(1)).coerceIn(0.0, 1.0)
        return covered.pow(1.5)
    }
    val ridden = sim.distanceM.coerceAtLeast(1.0)
    if (profile.isLoop && sim.laps > 1) {
        return 1 - 0.3 * min(1.0, (sim.laps - 1) / 6.0)
    }
    // Der Rest zaehlt gegen die ganze Laenge, nicht ab dem Startpunkt: Ein
    // spaeterer Start soll die Intervalle an den Berg legen, nicht einen langen
    // Rest „wegschneiden".
    val leftover = max(0.0, profile.lengthM - ridden)
    return 1 - 0.2 * min(1.0, leftover / (2 * ridden))
}

/** Bewertung samt Startpunkt. */
data class DayStart(val startM: Double, val score: Double, val sim: DayRideSim)

/** So viel besser muss ein spaeterer Start sein, damit er vorgeschlagen wird — sonst bleibt es beim Anfang. */
const val DAY_START_MIN_GAIN: Double = 0.03

/** Hoechstens so viele Startpunkte werden je Strecke probiert. */
private const val MAX_START_TRIES: Int = 48

/** Startpunkte liegen mindestens so weit auseinander. */
private const val MIN_START_SPACING_M: Double = 200.0

/**
 * Der beste Startpunkt auf [profile] fuer [goal]. Probiert werden der Anfang
 * und Stellen im Abstand von mindestens [MIN_START_SPACING_M] (hoechstens
 * [MAX_START_TRIES]) bis zur spaetesten Startstelle
 * ([IndoorStartPoints.maxStartM]), gerundet wie die Startwahl der Fahrt
 * ([IndoorStartPoints.clamp]). Ein spaeterer Start gewinnt nur, wenn er
 * mindestens [DAY_START_MIN_GAIN] besser ist — sonst beginnt man vorn, wie man
 * es von einer Strecke erwartet — und nur, wenn er die Einheit nicht kuerzt
 * (reicht die Strecke von vorn, muss sie auch ab dem Startpunkt reichen). Ein geschaetztes Profil startet immer vorn
 * (wo die Anstiege liegen, ist dort nicht bekannt).
 */
fun bestDayStart(goal: DayRouteGoal, profile: DayRouteProfile): DayStart {
    val first = simulateDayRide(goal, profile, 0.0)
    val atZero = DayStart(0.0, scoreDayRoute(goal, profile, first), first)
    if (profile.estimated) return atZero
    val top = IndoorStartPoints.maxStartM(profile.lengthM, profile.isLoop)
    if (top <= 0) return atZero
    val spacing = max(MIN_START_SPACING_M, top / MAX_START_TRIES)
    var best = atZero
    var m = spacing
    while (m <= top + 1e-6) {
        val at = IndoorStartPoints.clamp(m, profile.lengthM, profile.isLoop)
        val sim = simulateDayRide(goal, profile, at)
        m += spacing
        // Ein spaeterer Start darf die Einheit nicht kuerzen: Reicht die
        // Strecke von vorn, muss sie es auch ab hier.
        if (sim.routeEndsAtS != null && first.routeEndsAtS == null) continue
        val score = scoreDayRoute(goal, profile, sim)
        if (score > best.score + 1e-9) best = DayStart(at, score, sim)
    }
    return if (best.score - atZero.score >= DAY_START_MIN_GAIN) best else atZero
}

// ---------------------------------------------------------------------------
// 6. Kandidaten der Reihe nach
// ---------------------------------------------------------------------------

/** Was es kostet, eine Strecke zu fahren — in dieser Reihenfolge werden Kandidaten gesammelt. */
enum class DayRouteAvailability(val penalty: Double) {
    /** Die Welt liegt fertig auf dem Geraet. */
    READY(0.0),

    /** Katalogstrecke: Welt nur herunterladen. */
    DOWNLOAD(0.02),

    /** Eigene Planung oder Tour ohne Welt: Welt bauen. */
    BUILD(0.04),

    /** Noch keine Strecke: eine neue Runde planen (danach Welt bauen). */
    PLAN(0.06),
}

/**
 * Ein Kandidat fuer die Einheit des Tages.
 *
 * @property key Schluessel der Streckenwahl (`testwelt`, `ride:<id>`, `catalog:<id>`, `plan`).
 * @property profile Hoehenprofil; `null` nur bei [DayRouteAvailability.PLAN].
 */
data class DayRouteCandidate(
    val key: String,
    val name: String,
    val availability: DayRouteAvailability,
    val profile: DayRouteProfile?,
)

/**
 * Ein bewerteter Kandidat.
 *
 * @property rank Bewertung minus [DayRouteAvailability.penalty] — danach wird sortiert.
 * @property start Startpunkt samt Nachrechnung; `null` bei [DayRouteAvailability.PLAN].
 * @property climbs die Anstiege, in die Belastungen fallen (fuer die Begruendung).
 */
data class DayRouteMatch(
    val candidate: DayRouteCandidate,
    val score: Double,
    val rank: Double,
    val start: DayStart?,
    val climbs: DayClimbs?,
)

/**
 * Bewertung einer neu geplanten Runde: Die Routensuche trifft die Distanz und
 * das Hoehenprofil nach dem Routenziel ungefaehr; ob die Anstiege dort liegen,
 * wo die Intervalle sind, ist vorher nicht bekannt. 0,75 heisst: Eine
 * vorhandene Strecke, die ordentlich passt (fertige Welt ab 0,69, eigene
 * Strecke ab 0,71), schlaegt sie; eine schlecht passende (z. B. Intervalle auf
 * flacher Strecke, um 0,5) nicht.
 */
const val DAY_PLAN_SCORE: Double = 0.75

/**
 * Sortiert alle [candidates] nach Passung. Sortiert wird nach
 * `Bewertung − Aufschlag der Verfuegbarkeit` ([DayRouteAvailability.penalty]):
 * Liegen zwei Strecken innerhalb dieses Abstands, gewinnt die ohne Bauzeit
 * — eine fertige Welt mit 0,80 schlaegt einen Neubau mit 0,83. Danach nach
 * Verfuegbarkeit und Schluessel (stabil). Kandidaten ohne Profil (ausser
 * [DayRouteAvailability.PLAN]) fallen weg.
 */
fun rankDayRoutes(goal: DayRouteGoal, candidates: List<DayRouteCandidate>): List<DayRouteMatch> =
    candidates.mapNotNull { c ->
        if (c.availability == DayRouteAvailability.PLAN) {
            return@mapNotNull DayRouteMatch(c, DAY_PLAN_SCORE, DAY_PLAN_SCORE - c.availability.penalty, null, null)
        }
        val p = c.profile ?: return@mapNotNull null
        val start = bestDayStart(goal, p)
        DayRouteMatch(c, start.score, start.score - c.availability.penalty, start, dayClimbs(goal, p, start.sim))
    }.sortedWith(
        compareByDescending<DayRouteMatch> { it.rank }
            .thenBy { it.candidate.availability.ordinal }
            .thenBy { it.candidate.key },
    )

/**
 * Das Routenziel fuer eine neu zu planende Runde zur Einheit (Kandidat
 * [DayRouteAvailability.PLAN]), fuer den vorhandenen Weg ueber die
 * Routensuche (`generateRoutes`). Ausgangspunkt ist das Routenziel des Tages
 * [todayTarget] aus `decideTodayRoute` — es traegt schon die Daempfung der
 * Tagesform (Hoehenprofil-Kappe); ersetzt werden:
 *  * die Distanz durch die erwartete Distanz der Einheit auf flacher Strecke
 *    ([DayRouteGoal.expectedFlatM]) — die Rolle faehrt nach Zeit, nicht nach km;
 *  * das Hoehenprofil durch das zur Einheit passende ([DayTerrain]), hoechstens
 *    so bergig wie das Tagesziel erlaubt;
 *  * Dauer, Tempo und Intensitaet durch die der Einheit.
 */
fun dayPlanTarget(goal: DayRouteGoal, session: TrainingSession, todayTarget: RouteTarget): RouteTarget {
    val wanted = when (goal.terrain) {
        DayTerrain.FLAT -> AscentPreference.FLACH
        DayTerrain.ROLLING -> AscentPreference.MODERAT
        DayTerrain.CLIMBS -> AscentPreference.BERGIG
    }
    val km = max(1.0, Math.round(goal.expectedFlatM / 100.0) / 10.0)
    val hours = goal.totalS / 3600.0
    return todayTarget.copy(
        distanceKm = km,
        ascentPreference = minOf(wanted, todayTarget.ascentPreference),
        durationH = hours,
        speedKmh = if (hours > 0) km / hours else todayTarget.speedKmh,
        intensity = session.intensity,
    )
}

// ---------------------------------------------------------------------------
// 7. Begruendung: Anstiege unter den Intervallen
// ---------------------------------------------------------------------------

/**
 * Zahlen fuer den Satz der Begruendung („42 km, drei Anstiege von 6–8 min fuer
 * deine 3 × 8 min").
 *
 * @property count Anstiege (ab [MIN_CLIMB_M], Steigung ≥ [DAY_CLIMB_GRADE_PCT],
 *   Luecken bis [CLIMB_GAP_M] ueberbrueckt) im gefahrenen Abschnitt; ein
 *   Anstieg, der auf einer Runde zweimal gefahren wird, zaehlt zweimal.
 * @property minMinutes / [maxMinutes] Fahrzeit dieser Anstiege bei der
 *   Belastungsleistung, gerundet auf ganze Minuten (mindestens 1).
 * @property reps Wiederholungen der Einheit und [workMinutes] deren Dauer
 *   (der laengste Belastungsabschnitt), 0 ohne Intervalle.
 */
data class DayClimbs(val count: Int, val minMinutes: Int, val maxMinutes: Int, val reps: Int, val workMinutes: Int)

/** Kuerzere Anstiege zaehlen nicht. */
const val MIN_CLIMB_M: Double = 300.0

/** Flachere Stuecke bis zu dieser Laenge unterbrechen einen Anstieg nicht. */
const val CLIMB_GAP_M: Double = 150.0

/**
 * Die Anstiege im gefahrenen Abschnitt von [sim] und ihre Dauer bei der
 * Belastungsleistung der Einheit (ohne Intervalle: bei der hoechsten
 * Sollleistung). `null`, wenn es keine gibt.
 */
fun dayClimbs(goal: DayRouteGoal, profile: DayRouteProfile, sim: DayRideSim): DayClimbs? {
    val fraction = goal.intervals.maxOfOrNull { it.ftpFraction } ?: goal.workout.steps.maxOfOrNull { it.ftpFraction } ?: return null
    val power = fraction * ftpOf(goal.rider)
    // Anstiege auf einer Runde der Strecke: [vonM, bisM) und Fahrzeit.
    data class Climb(val fromM: Double, val toM: Double, val seconds: Double)
    val climbs = mutableListOf<Climb>()
    var k = 0
    while (k < profile.steps) {
        if (profile.gradePct[k] < DAY_CLIMB_GRADE_PCT) {
            k++
            continue
        }
        var end = k
        var gap = 0.0
        var q = k
        while (q < profile.steps) {
            if (profile.gradePct[q] >= DAY_CLIMB_GRADE_PCT) {
                end = q
                gap = 0.0
            } else {
                gap += profile.stepLengthM(q)
                if (gap > CLIMB_GAP_M) break
            }
            q++
        }
        val fromM = k * DayRouteProfile.STEP_M
        val toM = end * DayRouteProfile.STEP_M + profile.stepLengthM(end)
        if (toM - fromM >= MIN_CLIMB_M) {
            var s = 0.0
            for (i in k..end) s += profile.stepLengthM(i) / daySpeedMps(power, profile.gradePct[i], goal.rider)
            climbs += Climb(fromM, toM, s)
        }
        k = end + 1
    }
    if (climbs.isEmpty()) return null
    // Gefahrener Abschnitt [startM, endM) ueber Runden; ein Anstieg zaehlt, wenn seine Mitte darin liegt.
    val ridden = mutableListOf<Double>()
    val length = profile.lengthM
    val laps = if (profile.isLoop) ceil(sim.endM / length - 1e-9).toInt().coerceAtLeast(1) else 1
    for (lap in 0 until laps) {
        for (c in climbs) {
            val mid = lap * length + (c.fromM + c.toM) / 2
            if (mid >= sim.startM && mid < sim.endM) ridden += c.seconds
        }
    }
    if (ridden.isEmpty()) return null
    val minutes = ridden.map { max(1, (it / 60).roundToInt()) }
    val reps = goal.intervals.size
    val work = goal.intervals.maxOfOrNull { it.durationS }?.let { (it / 60.0).roundToInt() } ?: 0
    return DayClimbs(minutes.size, minutes.min(), minutes.max(), reps, work)
}

// ---------------------------------------------------------------------------
// 8. Einheit und Strecke enden nicht gleichzeitig
// ---------------------------------------------------------------------------

/** Was am Ende einer Einheit-des-Tages-Fahrt anzubieten ist. */
enum class DayRideEnd {
    /** Nichts — es wird gefahren, oder die Fahrt ist kein ERG. */
    NONE,

    /** Die Einheit ist geschafft, die Strecke geht weiter: Ausfahren anbieten (der Trainer haelt die Ausfahrleistung). */
    WORKOUT_DONE,

    /** Die Strecke ohne Rundschluss ist zu Ende, die Einheit noch nicht: Hinweis (Rundkurse wiederholt die Sitzung selbst). */
    ROUTE_DONE_EARLY,
}

/**
 * Die Regel fuer das Ende ([DayRideEnd]) aus dem Zustand der Sitzung. Eine
 * Runde endet nie vorzeitig: Die Sitzung beginnt nach der Ziellinie die
 * naechste Runde (docs/indoor.md 5.6), die Einheit laeuft weiter.
 */
fun dayRideEnd(state: IndoorSessionState): DayRideEnd {
    val erg = state.erg ?: return DayRideEnd.NONE
    if (state.mode != IndoorMode.ERG) return DayRideEnd.NONE
    return when {
        state.phase == IndoorPhase.FINISHED && !erg.done -> DayRideEnd.ROUTE_DONE_EARLY
        erg.done && state.phase != IndoorPhase.FINISHED -> DayRideEnd.WORKOUT_DONE
        else -> DayRideEnd.NONE
    }
}

/** Restzeit der Einheit in Sekunden ab dem Zustand [erg] (laufender Abschnitt plus alle folgenden). */
fun ergRemainingS(workout: ErgWorkout, erg: ErgState): Int {
    if (erg.done) return 0
    val later = workout.steps.drop(erg.stepIndex + 1).sumOf { it.durationS }
    return max(0, erg.stepRemainingS) + later
}
