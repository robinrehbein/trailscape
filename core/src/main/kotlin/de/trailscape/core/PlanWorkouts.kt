package de.trailscape.core

import de.trailscape.core.i18n.SessionTextKey
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * # Qualitaetseinheiten, Zielbereiche und Last einer Planeinheit
 *
 * Diese Datei ist die **eine Wahrheit** darueber, wie hart eine Planeinheit
 * ist. Aus der Struktur einer Einheit (Ein-/Ausfahren, Wiederholungen,
 * Belastungs- und Pausenzeit, Zielbereich) entstehen an genau einer Stelle
 * Dauer, Trainingslast und — mit dem persoenlichen Profil — konkrete Puls- und
 * Wattbereiche.
 *
 * ## Warum die Last aus der Struktur kommt und nicht aus einem Budget
 * Frueher rechnete der Plan ein Wochen-Lastbudget `7 × (CTL + Rampe /
 * ctlWeeklyResponse)` und skalierte die Lastziele der Einheiten darauf, ohne
 * Dauer oder Kilometer anzufassen. Ergebnis: Woche 1 mit 427 Lastpunkten in
 * 4,6 h — rechnerisch ein Intensitaetsfaktor von 0,96 fuer
 * Grundlagenfahrten, und eine „ruhige Runde" mit IF 0,84. Jetzt gilt
 * `Last = Σ Dauer_h × IF² × 100` ueber die Abschnitte der Einheit (dieselbe
 * eTSS-Normierung wie `computeRideLoad`), und die Rampe wirkt nur noch als
 * **Deckel**, der notfalls Umfang kuerzt (siehe `PlanWeeks.kt`). Der
 * implizierte IF `√(Last / (Dauer_h × 100))` passt damit per Konstruktion zur
 * Struktur — und genau das prueft ein Test.
 */

// ---------------------------------------------------------------------------
// Zielbereiche
// ---------------------------------------------------------------------------

/** Worauf sich der Pulsbereich einer [TargetZone] bezieht. */
enum class HrAnchor {
    /** Schwellenpuls (LTHR) — die Friel-Zonen der App ([TrainingZones.frielBoundsBpm]). */
    LTHR,

    /** Maximalpuls — fuer kurze VO2max-Intervalle, deren Vorgabe als %HFmax formuliert ist. */
    HFMAX,
}

/**
 * Zielbereich einer Einheit bzw. eines Abschnitts.
 *
 * ## Woher die Grenzen stammen
 *  * **Leistung** (Anteil der FTP): die verbreiteten Leistungszonen nach
 *    Coggan (Allen & Coggan, *Training and Racing with a Power Meter*):
 *    Z1 < 55 %, Z2 56–75 %, Z3 76–90 %, Z4 91–105 %, Z5 106–120 %. „Sweet
 *    Spot" (88–94 %) ist **keine** formale Zone, sondern ein in der Praxis
 *    verbreiteter Bereich am oberen Z3/unteren Z4 — hier ehrlich als
 *    Faustregel gefuehrt. Die Untergrenze von Z1 (40 %) ist willkuerlich; sie
 *    soll nur einen anzeigbaren Bereich ergeben.
 *  * **Puls** (Anteil der LTHR): dieselben Friel-Grenzen, mit denen die App
 *    die Zonenverteilung rechnet ([TrainingZones.frielBoundsBpm]: 81/90/94/100 %
 *    LTHR). Sweet Spot liegt zwischen Friel Z3 und Z4 (92–96 %, Faustregel).
 *  * **VO2max** nach Helgerud et al. 2007 (*Med Sci Sports Exerc* 39:665–671):
 *    4×4 min bei 90–95 % HFmax — deshalb dort %HFmax statt %LTHR; der Puls
 *    erreicht in 4-min-Intervallen ohnehin erst gegen Ende den Zielbereich.
 *
 * [nominalIf] ist der Intensitaetsfaktor, mit dem der Plan die Last eines
 * Abschnitts in diesem Bereich rechnet (ungefaehr die Bereichsmitte; fuer
 * Grundlage der bisherige Anker 0,70 aus [weeklyLoadPerHour]).
 */
enum class TargetZone(
    /** Stabiler Name, z. B. fuer die Anzeige-Zuordnung im UI. */
    val jsonName: String,
    val powerLowFtp: Double,
    val powerHighFtp: Double,
    val hrAnchor: HrAnchor,
    val hrLow: Double,
    val hrHigh: Double,
    val nominalIf: Double,
) {
    RECOVERY("recovery", 0.40, 0.55, HrAnchor.LTHR, 0.68, 0.80, 0.55),
    ENDURANCE("endurance", 0.56, 0.75, HrAnchor.LTHR, 0.81, 0.89, 0.70),
    TEMPO("tempo", 0.76, 0.87, HrAnchor.LTHR, 0.90, 0.93, 0.82),
    SWEET_SPOT("sweetSpot", 0.88, 0.94, HrAnchor.LTHR, 0.92, 0.96, 0.91),
    THRESHOLD("threshold", 0.95, 1.05, HrAnchor.LTHR, 0.94, 1.02, 1.00),
    VO2MAX("vo2max", 1.06, 1.20, HrAnchor.HFMAX, 0.90, 0.95, 1.12),
}

/**
 * Art einer strukturierten Qualitaetseinheit ([SessionTextKey.WORKOUT]).
 *
 * [code] steht als erstes Argument in [TrainingSession.textArgs] und ist damit
 * Teil des Plan-JSON — **nie aendern**, neue Arten bekommen neue Codes.
 */
enum class WorkoutKind(val code: Int, val zone: TargetZone, val restMin: Int) {
    /**
     * Zuegige Tempo-Abschnitte (Z3) — der sanfte Reiz fuer Einsteiger
     * („3×5 min zuegig") und die Form des Zieltempos.
     */
    TEMPO(0, TargetZone.TEMPO, 4),

    /** Sweet Spot (88–94 % FTP), lange Bloecke mit 5 min Pause — Grundlagenphase. */
    SWEET_SPOT(1, TargetZone.SWEET_SPOT, 5),

    /**
     * Schwellenintervalle (95–105 % FTP) in Bloecken von 8 min aufwaerts.
     *
     * Die **Form** 4×8 min ist an Seiler et al. 2013 (*Scand J Med Sci
     * Sports* 23:74–83) angelehnt; dort wurde sie aber mit der hoechsten ueber
     * alle Intervalle haltbaren Intensitaet gefahren (~90 % HFmax, Laktat
     * deutlich ueber der Schwelle) und mit 2 min Pause (die Pause ist nur ueber
     * Folgearbeiten/Sekundaerquellen belegt). Die Studie ist deshalb **kein**
     * Beleg fuer Intervalle *an* der Schwelle — sie ist Formvorbild. Die
     * Schwellenintensitaet und die 3 min Pause sind Faustregeln aus der
     * Trainingspraxis.
     */
    THRESHOLD(2, TargetZone.THRESHOLD, 3),

    /**
     * VO2max-Intervalle 4×4 min bei 90–95 % HFmax mit 3 min aktiver Pause
     * (~70 % HFmax) nach Helgerud et al. 2007 (*Med Sci Sports Exerc*
     * 39:665–671) — untersucht an Laeufern, fuer das Rad uebertragen. Die
     * Progressionsstufen 5×4 und 6×4 sind Faustregel.
     */
    VO2MAX(3, TargetZone.VO2MAX, 3),
    ;

    companion object {
        fun fromCode(code: Int?): WorkoutKind? = entries.firstOrNull { it.code == code }
    }
}

/** Index der Argumente von [SessionTextKey.WORKOUT]. */
internal object WorkoutArg {
    const val KIND = 0
    const val WARMUP = 1
    const val REPS = 2
    const val WORK = 3
    const val REST = 4
    const val COOLDOWN = 5
    const val TOTAL = 6
    const val ON_HILL = 7
    const val SIZE = 8
}

/** Index der Argumente von [SessionTextKey.LONG_RIDE] (die ersten seit jeher, der Rest angehaengt). */
internal object LongRideArg {
    const val CLIMBING = 0
    const val BLOCKS = 1
    const val BLOCK_MIN = 2

    /** Zielschnitt in Zehntel km/h; `-1` ohne Zielzeit. */
    const val TARGET_SPEED_TENTHS = 3
}

/** Index der Argumente von [SessionTextKey.FTP_TEST]. */
internal object FtpTestArg {
    const val WARMUP = 0
    const val TEST = 1
    const val COOLDOWN = 2
    const val TOTAL = 3
}

// ---------------------------------------------------------------------------
// Struktur einer Qualitaetseinheit und ihre Progression
// ---------------------------------------------------------------------------

/** Einfahren vor jeder Qualitaetseinheit in Minuten (mit Steigerung zum Schluss). */
internal const val WORKOUT_WARMUP_MIN = 15

/** Ausfahren nach jeder Qualitaetseinheit in Minuten. */
internal const val WORKOUT_COOLDOWN_MIN = 10

/** Die Struktur einer Qualitaetseinheit. */
internal data class WorkoutShape(
    val kind: WorkoutKind,
    val reps: Int,
    val workMin: Int,
    val onHill: Boolean = false,
) {
    val restMin: Int get() = kind.restMin
    val totalMin: Int
        get() = WORKOUT_WARMUP_MIN + reps * workMin + max(0, reps - 1) * restMin + WORKOUT_COOLDOWN_MIN

    /** Reine Belastungszeit in Minuten — die Groesse, die ueber den Plan steigt. */
    val workTotalMin: Int get() = reps * workMin

    fun args(): List<Int> = listOf(
        kind.code,
        WORKOUT_WARMUP_MIN,
        reps,
        workMin,
        restMin,
        WORKOUT_COOLDOWN_MIN,
        totalMin,
        if (onHill) 1 else 0,
    )

    /** Dieselbe Einheit mit weniger Wiederholungen (Erholung, Taper): Intensitaet bleibt. */
    fun reduced(factor: Double): WorkoutShape =
        copy(reps = max(MIN_REDUCED_REPS, (reps * factor).roundToInt()).coerceAtMost(reps))
}

/**
 * Mindestzahl an Wiederholungen einer verkuerzten Einheit. Zwei Belastungen
 * sind das Kleinste, was noch ein Intervallreiz ist und nicht nur ein Antritt.
 */
internal const val MIN_REDUCED_REPS = 2

/**
 * Progressionsstufen je Art: `(Wiederholungen, Belastungsminuten)`, die
 * Belastungszeit steigt von Stufe zu Stufe.
 *
 * Die Schritte sind Faustregeln aus der Trainingspraxis (eine Wiederholung
 * mehr oder laengere Bloecke je Woche), keine Studienzahlen; Formvorbilder
 * sind nur 4×4 (Helgerud 2007) und — fuer die Form, nicht die Intensitaet —
 * 4×8 (Seiler 2013, siehe [WorkoutKind.THRESHOLD]). Die
 * Belastungszeit ist streng monoton — eine Stufe darf nie weniger Arbeit
 * bedeuten als die vorige.
 */
private val progressionSteps: Map<WorkoutKind, List<Pair<Int, Int>>> = mapOf(
    WorkoutKind.TEMPO to listOf(3 to 5, 4 to 5, 3 to 8, 4 to 8, 3 to 12),
    WorkoutKind.SWEET_SPOT to listOf(2 to 10, 3 to 10, 3 to 12, 3 to 15, 4 to 15),
    WorkoutKind.THRESHOLD to listOf(3 to 8, 4 to 8, 5 to 8, 4 to 12),
    WorkoutKind.VO2MAX to listOf(3 to 4, 4 to 4, 5 to 4, 6 to 4),
)

/**
 * Die Qualitaetseinheit auf Progressionsstufe [step] (geklemmt auf die
 * vorhandenen Stufen).
 */
internal fun workoutAtStep(kind: WorkoutKind, step: Int, onHill: Boolean = false): WorkoutShape {
    val steps = progressionSteps.getValue(kind)
    val (reps, work) = steps[step.coerceIn(0, steps.lastIndex)]
    return WorkoutShape(kind, reps, work, onHill)
}

/** Hoechste Progressionsstufe einer Art (fuer Tests). */
internal fun lastWorkoutStep(kind: WorkoutKind): Int = progressionSteps.getValue(kind).lastIndex

/**
 * Zieltempo-Bloecke in der langen Tour je Stufe: `(Bloecke, Minuten)`.
 * Faustregel: in den letzten Wochen vor dem Event 20–60 min im angestrebten
 * Renntempo, aufgeteilt in Bloecke, damit die Grundlagenfahrt eine bleibt.
 */
private val racePaceSteps: List<Pair<Int, Int>> = listOf(2 to 10, 2 to 15, 3 to 15, 2 to 25, 3 to 20)

internal fun racePaceAtStep(step: Int): Pair<Int, Int> = racePaceSteps[step.coerceIn(0, racePaceSteps.lastIndex)]

/**
 * IF der Zieltempo-Bloecke. Fuer Ausdauerevents ueber 3 h liegt das
 * durchhaltbare Renntempo typischerweise bei IF 0,70–0,85 (Faustregel aus der
 * Leistungsmesser-Praxis); 0,80 ist die Mitte fuer die Zieldistanzen dieser
 * App.
 */
internal const val RACE_PACE_IF = 0.80

/** IF des Einfahrens (mit Steigerung bis Z3) — gemittelt. */
internal const val WARMUP_IF = 0.60

/** IF von Pausen und Ausfahren (aktiv locker, Z1). */
internal const val EASY_SEGMENT_IF = 0.55

/** Dauer des 20-min-Tests samt Vorbelastung. */
internal const val FTP_TEST_WARMUP_MIN = 20
internal const val FTP_TEST_MIN = 20
internal const val FTP_TEST_COOLDOWN_MIN = 15

/**
 * IF des 20-min-Tests: Coggan setzt die FTP bei ≈ 95 % des 20-min-Mittels an,
 * der Test selbst liegt also bei ≈ 1,05 × FTP.
 */
internal const val FTP_TEST_IF = 1.05

/**
 * IF der Vergleichsrunde: gleichmaessig zuegig, aber kein Test — oberes Z3
 * (Faustregel).
 */
internal const val BENCHMARK_IF = 0.85

// ---------------------------------------------------------------------------
// Abschnitte und Last
// ---------------------------------------------------------------------------

/** Ein Abschnitt einer Einheit: Dauer und angenommener Intensitaetsfaktor. */
internal data class LoadSegment(val minutes: Double, val intensityFactor: Double)

/** `Σ Dauer_h × IF² × 100` — dieselbe eTSS-Normierung wie `computeRideLoad` (§3.3). */
internal fun segmentsLoad(segments: List<LoadSegment>): Double =
    segments.sumOf { max(it.minutes, 0.0) / 60.0 * it.intensityFactor * it.intensityFactor * 100 }

/** Abschnitte einer Qualitaetseinheit. */
internal fun workoutSegments(shape: WorkoutShape): List<LoadSegment> = listOf(
    LoadSegment(WORKOUT_WARMUP_MIN.toDouble(), WARMUP_IF),
    LoadSegment((shape.reps * shape.workMin).toDouble(), shape.kind.zone.nominalIf),
    LoadSegment((max(0, shape.reps - 1) * shape.restMin).toDouble(), EASY_SEGMENT_IF),
    LoadSegment(WORKOUT_COOLDOWN_MIN.toDouble(), EASY_SEGMENT_IF),
)

/**
 * Angenommener IF einer **gleichmaessigen** Einheit je Intensitaet —
 * derselbe Anker wie in der Herleitung von [weeklyLoadPerHour]: LIT ≈ 0,70
 * (Grundlage), Regeneration darunter. Harte Einheiten rechnen nicht mehr mit
 * pauschal 1,0, sondern ueber ihre Struktur ([workoutSegments]).
 */
internal fun sessionIntensityIf(intensity: SessionIntensity): Double = when (intensity) {
    SessionIntensity.LOCKER -> 0.60
    SessionIntensity.GRUNDLAGE -> TargetZone.ENDURANCE.nominalIf
    SessionIntensity.HART -> TargetZone.THRESHOLD.nominalIf
}

/**
 * IF-Aufschlag fuer Grundlagenabschnitte in Plaenen auf ein Ziel mit
 * nennenswerten Hoehenmetern: Bergauffahren hebt die mittlere Intensitaet auch
 * bei gleichem gefuehltem Einsatz. +0,05 IF entspricht grob dem Unterschied
 * zwischen flacher und huegeliger Grundlagenfahrt (Faustregel);
 * Regenerationsfahrten bleiben ausgenommen.
 */
internal const val HILLY_IF_BONUS = 0.05

/**
 * Die Abschnitte einer Planeinheit, rekonstruiert aus ihren Feldern — die
 * Grundlage von [TrainingSession.targetLoad] und der Invariante „implizierter
 * IF passt zur Struktur". `null` fuer das Zielevent (keine budgetierte
 * Einheit) und fuer Einheiten ohne Dauer (Altplaene).
 */
internal fun sessionSegments(session: TrainingSession, hilly: Boolean): List<LoadSegment>? {
    if (session.isEvent) return null
    val total = session.durationMin?.toDouble() ?: return null
    val args = session.textArgs
    val baseIf = sessionIntensityIf(session.intensity) +
        if (hilly && session.intensity == SessionIntensity.GRUNDLAGE) HILLY_IF_BONUS else 0.0
    return when (session.textKey) {
        SessionTextKey.WORKOUT -> parseWorkoutArgs(args)?.let { workoutSegments(it) }
        SessionTextKey.FTP_TEST -> listOf(
            LoadSegment(args.getOrElse(FtpTestArg.WARMUP) { FTP_TEST_WARMUP_MIN }.toDouble(), WARMUP_IF),
            LoadSegment(args.getOrElse(FtpTestArg.TEST) { FTP_TEST_MIN }.toDouble(), FTP_TEST_IF),
            LoadSegment(args.getOrElse(FtpTestArg.COOLDOWN) { FTP_TEST_COOLDOWN_MIN }.toDouble(), EASY_SEGMENT_IF),
        )
        SessionTextKey.BENCHMARK_RIDE -> listOf(
            LoadSegment(WORKOUT_WARMUP_MIN.toDouble(), WARMUP_IF),
            LoadSegment(max(0.0, total - WORKOUT_WARMUP_MIN), BENCHMARK_IF),
        )
        SessionTextKey.LONG_RIDE -> {
            val blocks = args.getOrElse(LongRideArg.BLOCKS) { 0 }
            val blockMin = args.getOrElse(LongRideArg.BLOCK_MIN) { 0 }
            val paceMin = (blocks * blockMin).toDouble().coerceIn(0.0, total)
            listOf(LoadSegment(total - paceMin, baseIf), LoadSegment(paceMin, RACE_PACE_IF))
        }
        else -> listOf(LoadSegment(total, baseIf))
    }
}

/** Ziel-Last einer Einheit aus ihren Abschnitten, auf ganze Punkte gerundet (mindestens 1). */
internal fun structuredSessionLoad(session: TrainingSession, hilly: Boolean): Double? =
    sessionSegments(session, hilly)?.let { max(1.0, dartRound(segmentsLoad(it))) }

/** Die Struktur aus den Argumenten von [SessionTextKey.WORKOUT]; `null`, wenn sie unplausibel sind. */
internal fun parseWorkoutArgs(args: List<Int>): WorkoutShape? {
    if (args.size < WorkoutArg.SIZE) return null
    val kind = WorkoutKind.fromCode(args[WorkoutArg.KIND]) ?: return null
    val reps = args[WorkoutArg.REPS]
    val work = args[WorkoutArg.WORK]
    if (reps !in 1..20 || work !in 1..60) return null
    return WorkoutShape(kind, reps, work, onHill = args[WorkoutArg.ON_HILL] == 1)
}

/**
 * Implizierter Intensitaetsfaktor einer Planeinheit: `√(Last / (Dauer_h × 100))`.
 * `null` ohne Last oder Dauer (Event, Altplan).
 */
fun impliedSessionIf(session: TrainingSession): Double? {
    val load = session.targetLoad ?: return null
    val minutes = session.durationMin?.takeIf { it > 0 } ?: return null
    return kotlin.math.sqrt(load / (minutes / 60.0 * 100))
}

// ---------------------------------------------------------------------------
// Zielbereiche einer Einheit und persoenliche Werte
// ---------------------------------------------------------------------------

/**
 * Die Zielbereiche einer Planeinheit — abgeleitet aus [TrainingSession.textKey]
 * und [TrainingSession.textArgs], daher ohne neues JSON-Feld und auch fuer
 * gespeicherte Plaene verfuegbar.
 *
 * @property main Bereich der Einheit insgesamt bzw. zwischen den Belastungen
 *   (bei Intervallen: Grundlage).
 * @property work Bereich der Belastungsabschnitte; `null` bei gleichmaessigen
 *   Einheiten.
 * @property reps Zahl der Belastungen (0 ohne Struktur).
 * @property workMin Dauer einer Belastung in Minuten (0 ohne Struktur).
 */
data class SessionTargetZones(
    val main: TargetZone,
    val work: TargetZone?,
    val reps: Int = 0,
    val workMin: Int = 0,
)

/**
 * Zielbereiche einer Planeinheit; `null` fuer das Zielevent (dort entscheidet
 * der Renntag, nicht eine Zone).
 */
fun sessionTargetZones(session: TrainingSession): SessionTargetZones? {
    if (session.isEvent) return null
    val args = session.textArgs
    return when (session.textKey) {
        SessionTextKey.WORKOUT -> parseWorkoutArgs(args)
            ?.let { SessionTargetZones(TargetZone.ENDURANCE, it.kind.zone, it.reps, it.workMin) }
            ?: SessionTargetZones(TargetZone.ENDURANCE, TargetZone.THRESHOLD)
        SessionTextKey.INTERVALS -> SessionTargetZones(
            TargetZone.ENDURANCE,
            TargetZone.THRESHOLD,
            args.getOrElse(1) { 0 },
            args.getOrElse(2) { 0 },
        )
        SessionTextKey.FTP_TEST -> SessionTargetZones(
            TargetZone.ENDURANCE,
            TargetZone.THRESHOLD,
            1,
            args.getOrElse(FtpTestArg.TEST) { FTP_TEST_MIN },
        )
        SessionTextKey.BENCHMARK_RIDE -> SessionTargetZones(TargetZone.TEMPO, null)
        SessionTextKey.LONG_RIDE -> {
            val blocks = args.getOrElse(LongRideArg.BLOCKS) { 0 }
            if (blocks > 0) {
                SessionTargetZones(TargetZone.ENDURANCE, TargetZone.TEMPO, blocks, args.getOrElse(LongRideArg.BLOCK_MIN) { 0 })
            } else {
                SessionTargetZones(TargetZone.ENDURANCE, null)
            }
        }
        else -> when (session.intensity) {
            SessionIntensity.LOCKER -> SessionTargetZones(TargetZone.RECOVERY, null)
            SessionIntensity.GRUNDLAGE -> SessionTargetZones(TargetZone.ENDURANCE, null)
            // Harte Einheit ohne Struktur (Altplan): Schwelle als vorsichtigste Annahme.
            SessionIntensity.HART -> SessionTargetZones(TargetZone.ENDURANCE, TargetZone.THRESHOLD)
        }
    }
}

/**
 * Ein Zielbereich in persoenlichen Werten: Puls in bpm und Leistung in Watt,
 * jeweils mit ihrer Verlaesslichkeit — damit die Oberflaeche „150–160 bpm /
 * 210–230 W" zeigen und eine geschaetzte Zahl als solche kennzeichnen kann.
 */
data class PersonalTargetRange(
    val zone: TargetZone,
    val hrLowBpm: Int,
    val hrHighBpm: Int,
    val hrConfidence: Confidence,
    val powerLowW: Int,
    val powerHighW: Int,
    val powerConfidence: Confidence,
)

/**
 * Persoenliche Puls- und Wattbereiche zu [zone].
 *
 * ## Woher die Ankerwerte kommen
 *  * **Puls**: [zones] (z. B. der Zonen-Snapshot, mit dem die App gerade
 *    rechnet), sonst [TrainingProfile.zones]. Verlaesslichkeit: Bei einem
 *    LTHR-Bereich [Confidence.HIGH] mit eingetragener LTHR,
 *    [Confidence.MEDIUM] mit eingetragener HFmax (LTHR = 0,89 × HFmax), sonst
 *    [Confidence.LOW] (Tanaka, SEE ≈ ±10 bpm). Bei einem HFmax-Bereich
 *    [Confidence.HIGH] mit eingetragener HFmax, sonst [Confidence.LOW].
 *  * **Leistung**: [eftp] (z. B. [resolveEftp]), sonst der Profilwert
 *    ([EftpEstimate.fromProfile]); die Verlaesslichkeit ist die der FTP.
 *
 * Wirft nie; Grenzen werden auf ganze bpm/W gerundet.
 */
fun personalTargetRange(
    zone: TargetZone,
    profile: TrainingProfile,
    eftp: EftpEstimate? = null,
    zones: TrainingZones? = null,
): PersonalTargetRange {
    val hrZones = zones ?: profile.zones
    val anchorBpm = when (zone.hrAnchor) {
        HrAnchor.LTHR -> hrZones.lthr
        HrAnchor.HFMAX -> hrZones.hrMax
    }
    val hrConfidence = when (zone.hrAnchor) {
        HrAnchor.LTHR -> when {
            profile.lthrSource == ValueSource.TEST -> Confidence.HIGH
            profile.hrMaxSource == ValueSource.TEST -> Confidence.MEDIUM
            else -> Confidence.LOW
        }
        HrAnchor.HFMAX -> if (profile.hrMaxSource == ValueSource.TEST) Confidence.HIGH else Confidence.LOW
    }
    val ftp = eftp ?: EftpEstimate.fromProfile(profile)
    val watts = ftp.watts.takeIf { it.isFinite() && it > 0 } ?: profile.eftpW
    return PersonalTargetRange(
        zone = zone,
        hrLowBpm = (anchorBpm * zone.hrLow).roundToInt(),
        hrHighBpm = (anchorBpm * zone.hrHigh).roundToInt(),
        hrConfidence = hrConfidence,
        powerLowW = (watts * zone.powerLowFtp).roundToInt(),
        powerHighW = (watts * zone.powerHighFtp).roundToInt(),
        powerConfidence = ftp.confidence,
    )
}

/** Persoenliche Bereiche einer Planeinheit: Hauptbereich und (falls vorhanden) Belastungsbereich. */
data class SessionPersonalTargets(
    val zones: SessionTargetZones,
    val main: PersonalTargetRange,
    val work: PersonalTargetRange?,
)

/**
 * Persoenliche Zielbereiche einer Planeinheit in einem Aufruf (siehe
 * [sessionTargetZones] und [personalTargetRange]); `null` fuer das Zielevent.
 */
fun sessionPersonalTargets(
    session: TrainingSession,
    profile: TrainingProfile,
    eftp: EftpEstimate? = null,
    zones: TrainingZones? = null,
): SessionPersonalTargets? {
    val target = sessionTargetZones(session) ?: return null
    return SessionPersonalTargets(
        zones = target,
        main = personalTargetRange(target.main, profile, eftp, zones),
        work = target.work?.let { personalTargetRange(it, profile, eftp, zones) },
    )
}
