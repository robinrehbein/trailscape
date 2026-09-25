package de.trailscape.core

import java.time.LocalDateTime
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Ableitungs-, Dedupe- und Aggregationslogik der Health-Connect-Anbindung.
 *
 * Portierung des plattformneutralen Teils von `lib/health_sync.dart`. Alles,
 * was in Dart direkt am `health`-Paket, an Health Connect, an
 * `SharedPreferences` oder am `MethodChannel` haengt, bleibt draussen und wird
 * ueber zwei schmale Schnittstellen hereingereicht:
 *
 *  * [HealthGateway] — jeder Lesezugriff auf die Plattform (Verfuegbarkeit,
 *    Berechtigungen, Workouts, native Sessions, Routen, Herzfrequenz,
 *    Ruhepuls, Schlaf, VO2max, HRV),
 *  * [HealthSyncStore] — der persistierte Zeitstempel des letzten Imports
 *    (in Dart `SharedPreferences` unter [healthSyncStorageKey]).
 *
 * Die Android-Implementierungen beider Schnittstellen (`HealthPluginGateway`,
 * Platform-Channel `trailscape/health_extra`, `SharedPreferences`) folgen in
 * Phase 3; die gesamte Entscheidungslogik ist hier ohne Geraet testbar.
 *
 * Bewusst **keine** `suspend`-Funktionen: `:core` haengt nicht an
 * kotlinx-coroutines. Die Android-Seite ruft die Schnittstellen aus einem
 * eigenen Dispatcher heraus auf.
 */

/** Speicherschluessel fuer den Zeitpunkt des letzten Imports (ms seit Epoch). */
const val healthSyncStorageKey: String = "trailscape.healthsync"

/**
 * Wie weit zurueck importiert wird, wenn noch nie synchronisiert wurde: 30
 * Tage in Millisekunden.
 *
 * Bewusst 30 Tage: Ohne die zusaetzliche Historien-Freigabe
 * (`READ_HEALTH_DATA_HISTORY`) gibt Health Connect nichts heraus, was mehr als
 * 30 Tage vor der ersten Zustimmung geschrieben wurde. Beim ersten Sync — also
 * kurz nach dieser Zustimmung — ist das praktisch dasselbe wie „die letzten
 * 30 Tage"; alles, was danach dazukommt, bleibt dauerhaft lesbar.
 */
const val healthSyncInitialWindowMs: Long = 30L * 24 * 60 * 60 * 1000

/**
 * Wie weit der einmalige Lang-Import zurueckgreift, wenn die Historien-Freigabe
 * (`READ_HEALTH_DATA_HISTORY`) erteilt ist: 365 Tage in Millisekunden.
 *
 * Warum ueberhaupt: Wer Trailscape neu installiert, hat meist schon Monate an
 * Fahrten in Samsung Health. Mit nur 30 Tagen starteten Fitness-, Form- und
 * Belastungskurven bei null, obwohl die Daten laengst auf dem Handy liegen.
 *
 * Warum nicht mehr: Ein Jahr deckt eine volle Saison samt Winterpause ab und
 * reicht fuer jede Trainingslast-Rechnung (die laengste Konstante, die
 * Fitness, klingt nach wenigen Wochen ab). Aeltere Fahrten waeren reine
 * Archivpflege und wuerden den ersten Sync spuerbar verlaengern — Herzfrequenz
 * wird je Fahrt einzeln gelesen.
 *
 * Wann: genau einmal, siehe [HealthSyncStore.historyImportDone].
 */
const val healthSyncHistoryWindowMs: Long = 365L * 24 * 60 * 60 * 1000

/**
 * Speicherschluessel des Merkers „Lang-Import mit Historien-Freigabe ist
 * gelaufen" (siehe [HealthSyncStore.historyImportDone]).
 */
const val healthSyncHistoryImportKey: String = "trailscape.healthsync.historyImportDone"

/**
 * Speicherschluessel des Lang-Import-Fortschritts (siehe
 * [HealthSyncStore.historyImportedUntilMs]).
 */
const val healthSyncHistoryProgressKey: String = "trailscape.healthsync.historyImportedUntil"

/**
 * Laenge eines Import-Abschnitts: 30 Tage in Millisekunden.
 *
 * Warum Abschnitte: Der Lang-Import liest ein ganzes Jahr. In einem Rutsch
 * laegen alle Sessions, alle Routen und alle gebauten Touren samt Trackpunkten
 * gleichzeitig im Speicher — bei einer Vielfahrerin weit ueber eine Million
 * Punkte —, und ein Abbruch kurz vor Schluss verwarf die ganze Arbeit.
 * Abschnittsweise wird jeder Teil sofort gespeichert und als erledigt
 * vermerkt (siehe [HealthSyncService.importWithReport]).
 *
 * Warum 30 Tage: So bleibt der erste Sync ohne Historien-Freigabe (genau
 * [healthSyncInitialWindowMs]) und jeder normale Folge-Sync ein einziger
 * Abschnitt — mit genau der Diagnose, die es vorher auch gab.
 */
const val healthSyncSliceMs: Long = 30L * 24 * 60 * 60 * 1000

/**
 * Um so viel greift jeder weitere Abschnitt vor seinen Beginn zurueck (ein
 * Tag), damit eine Session an der Grenze sicher in einem der beiden
 * Abschnitte ankommt.
 */
private const val SLICE_OVERLAP_MS = 24L * 60 * 60 * 1000

/**
 * Wie weit der Beginn des Importfensters hinter den Zeitpunkt des letzten
 * Imports zurueckgesetzt wird: 4 Tage in Millisekunden.
 *
 * Begruendung und Sicherheitsbetrachtung stehen an [healthImportWindowStart].
 * Vier Tage decken das lange Wochenende ab, an dem die Uhr nicht mit dem
 * Telefon spricht, und bleiben deutlich unter dem 30-Tage-Fenster, das Health
 * Connect ohne Historien-Freigabe ueberhaupt hergibt.
 */
const val healthSyncImportBackfillMs: Long = 4L * 24 * 60 * 60 * 1000

/**
 * Ab welchem zeitlichen Ueberlappungsanteil eine Health-Connect-Session als
 * bereits vorhandene Tour gilt und uebersprungen wird (strikt groesser).
 */
const val healthSyncOverlapThreshold: Double = 0.5

/**
 * Maximaler zeitlicher Abstand in Millisekunden, in dem eine
 * Herzfrequenz-Messung beim Anreichern einer bestehenden Tour noch einem
 * Trackpunkt zugeordnet wird (60 s).
 */
const val healthSyncHrMergeToleranceMs: Long = 60_000

/**
 * Wie viele Sessions je Quelle in [HealthSyncReport.debugLines] einzeln
 * aufgefuehrt werden.
 */
private const val DEBUG_SESSION_LIMIT = 12

/** Maximaler Abstand, in dem [nearestHr] eine Messung noch zuordnet (30 s). */
private const val NEAREST_HR_TOLERANCE_MS = 30_000L

// ---------------------------------------------------------------------------
// IO-Raender (Phase 3: Android-Implementierungen)
// ---------------------------------------------------------------------------

/**
 * Zugriff auf die Health-Plattform. Wird von [HealthSyncService] benutzt und in
 * Tests durch eine Attrappe ersetzt.
 *
 * Entspricht `abstract class HealthGateway` aus `lib/health_sync.dart`.
 */
interface HealthGateway {
    /** Zustand der Health-Connect-Installation. */
    fun availability(): HealthAvailability

    /** Ob alle benoetigten Leserechte erteilt sind. */
    fun hasPermissions(): Boolean

    /**
     * Ob die Historien-Freigabe (`READ_HEALTH_DATA_HISTORY`) erteilt ist, also
     * Daten aelter als 30 Tage vor der ersten Zustimmung lesbar sind.
     *
     * Die Vorgabe meldet `false`: Eine Implementierung, die das Recht nicht
     * kennt, bekommt so das bisherige 30-Tage-Fenster und fragt nie ins
     * Leere hinein ab.
     */
    fun hasHistoryPermission(): Boolean = false

    /** Fragt die benoetigten Leserechte an. Liefert `true` bei Zustimmung. */
    fun requestPermissions(): Boolean

    /** Alle Workouts im Zeitraum `[from, to]`. */
    fun readWorkouts(from: LocalDateTime, to: LocalDateTime): List<HealthWorkout>

    /**
     * Rohdiagnose des letzten [readWorkouts]-Aufrufs, `null` wenn die
     * Implementierung keine erhebt.
     */
    val lastWorkoutDiagnostics: HealthWorkoutReadDiagnostics?
        get() = null

    /**
     * Trainings-Sessions ueber den nativen Reader, am `health`-Paket vorbei.
     *
     * Rueckfallebene fuer den Fall, dass das Plugin gar nichts oder nichts
     * Verwertbares liefert. Wirft, wenn der Kanal fehlt (alte Installation)
     * oder Health Connect den Zugriff verweigert; die Vorgabe meldet „nicht
     * unterstuetzt".
     */
    fun readExerciseSessionsNative(from: LocalDateTime, to: LocalDateTime): List<HealthSessionInfo> =
        throw UnsupportedOperationException("Kein nativer Session-Reader verfügbar.")

    /**
     * GPS-Routen im Zeitraum, nach Workout-ID ([HealthWorkout.id]) gruppiert.
     * Workouts ohne (freigegebene) Route fehlen in der Map.
     */
    fun readRoutes(from: LocalDateTime, to: LocalDateTime): Map<String, List<HealthRoutePoint>>

    /**
     * Wie [readRoutes], meldet aber zusaetzlich die Sessions, deren Route
     * Health Connect nur nach einer Einzel-Freigabe herausgibt
     * (`ExerciseRouteResult.ConsentRequired`). Die Vorgabe kennt diesen Fall
     * nicht und meldet keine.
     */
    fun readRoutesWithStatus(from: LocalDateTime, to: LocalDateTime): HealthRouteReadResult =
        HealthRouteReadResult(routes = readRoutes(from, to))

    /**
     * Wie [readRoutesWithStatus], aber nur fuer die Sessions [sessionIds] (die
     * Import-Kandidaten, also Radfahrten). [from]/[to] umschliessen sie.
     *
     * Warum eigens: Ueber ein Zeitfenster gelesen kaemen auch die Routen von
     * Laeufen und Spaziergaengen mit — beim Jahres-Import ein Jahr fremder
     * GPS-Spuren, die Trailscape weder braucht noch lesen soll. Die produktive
     * Implementierung liest deshalb je Session einzeln. Die Vorgabe filtert
     * nur nachtraeglich und ist fuer Attrappen und alte Implementierungen da.
     */
    fun readRoutesForSessions(
        sessionIds: Set<String>,
        from: LocalDateTime,
        to: LocalDateTime,
    ): HealthRouteReadResult {
        val all = readRoutesWithStatus(from, to)
        return HealthRouteReadResult(
            routes = all.routes.filterKeys { it in sessionIds },
            consentRequired = all.consentRequired.filterTo(linkedSetOf()) { it in sessionIds },
        )
    }

    /**
     * Welche Leserechte erteilt sind, soweit sie die Diagnose betreffen.
     * Fehlende Schluessel bedeuten „unbekannt" bzw. „vom Geraet nicht
     * unterstuetzt"; `null` = die Implementierung meldet es gar nicht.
     */
    fun readPermissionStatus(): Map<HealthReadType, Boolean>? = null

    /** Herzfrequenz-Zeitreihe im Zeitraum. */
    fun readHeartRate(from: LocalDateTime, to: LocalDateTime): List<HealthHeartRateSample>

    /** Ruhepuls-Messungen im Zeitraum. */
    fun readRestingHeartRate(from: LocalDateTime, to: LocalDateTime): List<HealthNumericSample>

    /** Schlafsitzungen im Zeitraum. */
    fun readSleepSessions(from: LocalDateTime, to: LocalDateTime): List<HealthSleepSession>

    /**
     * VO2max-Messungen im Zeitraum. Health Connect kennt den Datentyp, das
     * `health`-Paket bietet ihn aber nicht an — die produktive Implementierung
     * liest ihn ueber einen eigenen Platform-Channel und wirft, wenn dieser
     * nicht antwortet.
     */
    fun readVo2Max(from: LocalDateTime, to: LocalDateTime): List<HealthNumericSample>

    /** Herzratenvariabilitaet (rMSSD) im Zeitraum, Werte in Millisekunden. */
    fun readHrv(from: LocalDateTime, to: LocalDateTime): List<HealthNumericSample>
}

/**
 * Persistenz des Import-Zeitstempels — in Dart `SharedPreferences` unter
 * [healthSyncStorageKey], auf Android in Phase 3 ebenso.
 */
interface HealthSyncStore {
    /** Zeitpunkt des letzten erfolgreichen Imports in ms seit Epoch. */
    fun lastImportAtMs(): Long?

    /** Setzt den Zeitstempel; `null` loescht ihn. */
    fun setLastImportAtMs(value: Long?)

    /**
     * Ob der einmalige Lang-Import ([healthSyncHistoryWindowMs]) schon
     * gelaufen ist.
     *
     * Ein eigener Merker statt „erster Sync ja/nein": Die Historien-Freigabe
     * kann auch erst Wochen nach dem ersten Sync kommen (zweiter Dialog,
     * Health-Connect-Einstellungen). Dann liegt [lastImportAtMs] laengst vor,
     * und nur dieser Merker sagt, dass das Jahr davor noch nie gelesen wurde.
     */
    fun historyImportDone(): Boolean

    /** Setzt den Merker aus [historyImportDone]. */
    fun setHistoryImportDone(value: Boolean)

    /**
     * Bis wohin (ms seit Epoch) ein noch nicht abgeschlossener Lang-Import
     * schon gespeichert ist, `null` wenn keiner laeuft.
     *
     * Der Lang-Import laeuft in Abschnitten ([healthSyncSliceMs]); bricht er
     * ab (Prozess beendet, Speichern gescheitert), setzt der naechste Lauf
     * hier wieder an, statt das ganze Jahr erneut zu lesen.
     */
    fun historyImportedUntilMs(): Long?

    /** Setzt [historyImportedUntilMs]; `null` loescht den Fortschritt. */
    fun setHistoryImportedUntilMs(value: Long?)
}

/**
 * Zeitstempel nur im Arbeitsspeicher — Vorgabe fuer [HealthSyncService], wenn
 * (noch) kein persistenter Speicher angebunden ist, und Attrappe in Tests.
 */
class InMemoryHealthSyncStore(
    private var value: Long? = null,
    private var historyDone: Boolean = false,
) : HealthSyncStore {
    override fun lastImportAtMs(): Long? = value

    override fun setLastImportAtMs(value: Long?) {
        this.value = value
    }

    override fun historyImportDone(): Boolean = historyDone

    override fun setHistoryImportDone(value: Boolean) {
        historyDone = value
    }

    private var historyUntil: Long? = null

    override fun historyImportedUntilMs(): Long? = historyUntil

    override fun setHistoryImportedUntilMs(value: Long?) {
        historyUntil = value
    }
}

// ---------------------------------------------------------------------------
// Ableitungen (frei testbar, ohne Plugin)
// ---------------------------------------------------------------------------

/**
 * Titel, die auf ein Rad-Workout hindeuten, wenn der Aktivitaetstyp nichts
 * hergibt (Samsung Health schreibt manche Sessions als „anderes Training" mit
 * sprechendem Titel).
 */
val healthCyclingTitlePattern: Regex =
    Regex("(rad|fahrrad|bike|cycl|mtb|gravel)", RegexOption.IGNORE_CASE)

/**
 * Rad-Art einer nativ gelesenen Session, `null` wenn es kein Rad-Workout ist.
 *
 * Massgeblich ist der Name der androidx-Konstante (die Kotlin-Seite bildet die
 * Zahl darauf ab); zusaetzlich greift die Titel-Heuristik
 * [healthCyclingTitlePattern].
 */
fun mapNativeSessionKind(session: HealthSessionInfo): HealthActivityKind? {
    when (session.typeName) {
        "EXERCISE_TYPE_BIKING" -> return HealthActivityKind.RADFAHREN
        "EXERCISE_TYPE_BIKING_STATIONARY" -> return HealthActivityKind.RADFAHREN_INDOOR
    }

    val title = session.title
    if (title != null && healthCyclingTitlePattern.containsMatchIn(title)) {
        return HealthActivityKind.RADFAHREN
    }
    return null
}

/** Kompakter Zeitstempel fuer die Diagnosezeilen: „08.08. 14:30". */
internal fun healthDebugTime(value: LocalDateTime): String {
    fun two(v: Int): String = v.toString().padStart(2, '0')
    return "${two(value.dayOfMonth)}.${two(value.monthValue)}. " +
        "${two(value.hour)}:${two(value.minute)}"
}

/**
 * Ride-ID fuer ein Health-Connect-Workout. Aus der Datensatz-ID abgeleitet,
 * damit ein zweiter Import dieselbe Tour erkennt. Nicht dateisystemtaugliche
 * Zeichen werden ersetzt (Touren liegen als `<id>.json` auf der Platte).
 */
fun healthRideId(workoutId: String): String {
    val safe = workoutId.replace(Regex("[^A-Za-z0-9_-]"), "-")
    return "hc-$safe"
}

/** Zeitraum einer bestehenden Tour, Ergebnis von [rideTimeRange]. */
data class RideTimeRange(val start: LocalDateTime, val end: LocalDateTime)

/**
 * Zeitraum einer bestehenden Tour. Bevorzugt die Trackpunkt-Zeitstempel,
 * sonst `createdAt` plus Dauer.
 */
fun rideTimeRange(ride: Ride): RideTimeRange {
    val times = ride.points.mapNotNull { it.time }

    var startMs = ride.createdAt
    var endMs: Long? = null

    if (times.isNotEmpty()) {
        startMs = times.min()
        endMs = times.max()
    }

    val durationS = ride.stats.durationS
    if (endMs == null && durationS != null && durationS > 0) {
        endMs = startMs + durationS * 1000L
    }

    return RideTimeRange(
        start = dartLocalOf(startMs),
        end = dartLocalOf(max(endMs ?: startMs, startMs)),
    )
}

/**
 * Zeitraum aus einer punktfreien Zusammenfassung: `createdAt` plus Dauer —
 * derselbe Rueckfall-Zweig, den [rideTimeRange] fuer Touren ohne
 * Punkt-Zeitstempel nimmt. Fuer die Ueberlappungspruefung des Health-Imports
 * gleichwertig, ohne dafuer die Punkte laden zu muessen.
 */
fun summaryTimeRange(info: RideInfo): RideTimeRange {
    val startMs = info.createdAt
    val durationS = info.stats.durationS
    val endMs = if (durationS != null && durationS > 0) startMs + durationS * 1000L else startMs
    return RideTimeRange(
        start = dartLocalOf(startMs),
        end = dartLocalOf(max(endMs, startMs)),
    )
}

/**
 * Anteil des Zeitraums A, der von Zeitraum B ueberdeckt wird (0..1).
 *
 * Fuer einen punktfoermigen Zeitraum A (Start == Ende) gilt 1, wenn der Punkt
 * in B liegt, sonst 0.
 */
fun overlapRatio(
    aStart: LocalDateTime,
    aEnd: LocalDateTime,
    bStart: LocalDateTime,
    bEnd: LocalDateTime,
): Double {
    val aFrom = dartEpochMs(aStart)
    val aTo = max(dartEpochMs(aEnd), aFrom)
    val bFrom = dartEpochMs(bStart)
    val bTo = max(dartEpochMs(bEnd), bFrom)

    val overlap = min(aTo, bTo) - max(aFrom, bFrom)
    val durationA = aTo - aFrom

    if (durationA <= 0) {
        return if (aFrom >= bFrom && aFrom <= bTo) 1.0 else 0.0
    }
    if (overlap <= 0) {
        return 0.0
    }
    return overlap.toDouble() / durationA
}

/**
 * Bildet ein Health-Connect-Workout auf das [Ride]-Modell ab.
 *
 * [route] sind die GPS-Punkte der Session (ggf. leer), [heartRate] eine
 * Herzfrequenz-Zeitreihe, aus der die zum Workout gehoerenden Messungen
 * gefiltert werden.
 */
fun buildRideFromWorkout(
    workout: HealthWorkout,
    route: List<HealthRoutePoint> = emptyList(),
    heartRate: List<HealthHeartRateSample> = emptyList(),
): Ride {
    val samples = heartRate
        .filter { !it.time.isBefore(workout.start) && !it.time.isAfter(workout.end) }
        .sortedBy { dartEpochMs(it.time) }

    val sorted = route.sortedBy { dartEpochMs(it.time) }

    val points = sorted.map { p ->
        TrackPoint(
            lat = p.lat,
            lon = p.lon,
            ele = p.ele,
            time = dartEpochMs(p.time),
            hr = nearestHr(samples, p.time),
        )
    }

    val geo = if (points.size >= 2) computeStats(points) else null

    val workoutSeconds = workout.durationS
    val durationS = if (workoutSeconds > 0) workoutSeconds.toInt() else geo?.durationS

    // Die vom Geraet gemessene Distanz ist genauer als die aus der (geglaetteten
    // und ggf. ausgeduennten) Route berechnete und wird daher bevorzugt.
    val distanceM = workout.distanceM
    val distanceKm = if (distanceM != null && distanceM > 0) {
        distanceM / 1000
    } else {
        geo?.distanceKm ?: 0.0
    }

    val movingTimeS = geo?.movingTimeS
    var avgSpeedKmh: Double? = null
    if (movingTimeS != null && movingTimeS > 0) {
        avgSpeedKmh = distanceKm / (movingTimeS / 3600.0)
    } else if (durationS != null && durationS > 0) {
        avgSpeedKmh = distanceKm / (durationS / 3600.0)
    }

    var avgHr: Int? = null
    var maxHr: Int? = null
    if (samples.isNotEmpty()) {
        var sum = 0.0
        var peak = samples.first().bpm
        for (sample in samples) {
            sum += sample.bpm
            if (sample.bpm > peak) {
                peak = sample.bpm
            }
        }
        avgHr = dartRound(sum / samples.size).toInt()
        maxHr = dartRound(peak).toInt()
    }

    return Ride(
        id = healthRideId(workout.id),
        name = healthRideName(workout),
        createdAt = dartEpochMs(workout.start),
        points = points,
        stats = RideStats(
            distanceKm = distanceKm,
            durationS = durationS,
            movingTimeS = movingTimeS,
            avgSpeedKmh = avgSpeedKmh,
            ascentM = geo?.ascentM ?: 0.0,
            descentM = geo?.descentM ?: 0.0,
            avgHrBpm = avgHr,
            maxHrBpm = maxHr,
        ),
    )
}

/**
 * Ob eine Tour bereits Herzfrequenzdaten mitbringt.
 *
 * Geprueft werden sowohl die Kennzahlen ([RideStats.avgHrBpm],
 * [RideStats.maxHrBpm]) als auch die Trackpunkte ([TrackPoint.hr]).
 */
fun rideHasHeartRate(ride: Ride): Boolean =
    ride.stats.avgHrBpm != null ||
        ride.stats.maxHrBpm != null ||
        ride.points.any { it.hr != null }

/**
 * Reichert eine bestehende Tour mit den Herzfrequenzen einer ueberlappenden
 * Watch-Session an.
 *
 * Jedem Trackpunkt wird die zeitlich naechstgelegene Messung zugeordnet,
 * sofern sie hoechstens [toleranceMs] entfernt liegt. Beide Listen sind
 * zeitlich sortiert — der Abgleich laeuft daher als Zwei-Zeiger-Durchlauf in
 * O(n + m); die Trackpunkte werden dabei **nicht** umsortiert, ihre Reihenfolge
 * ist die Streckenreihenfolge.
 *
 * Liefert eine Kopie mit unveraenderter [Ride.id] (Name, Zeitpunkt, Distanz und
 * Hoehenmeter bleiben ebenfalls, nur `avgHrBpm`/`maxHrBpm` kommen hinzu), oder
 * `null`, wenn sich keine einzige Messung zuordnen liess — dann bleibt die Tour
 * unangetastet.
 */
fun mergeHeartRateIntoRide(
    ride: Ride,
    samples: List<HealthHeartRateSample>,
    toleranceMs: Long = healthSyncHrMergeToleranceMs,
): Ride? {
    if (samples.isEmpty() || ride.points.isEmpty()) {
        return null
    }

    val sorted = samples.sortedBy { dartEpochMs(it.time) }
    val sortedMs = sorted.map { dartEpochMs(it.time) }

    val points = mutableListOf<TrackPoint>()
    var cursor = 0
    var sum = 0.0
    var count = 0
    var peak = 0

    for (point in ride.points) {
        val time = point.time
        if (time == null) {
            points.add(point)
            continue
        }

        // Der Zeiger wandert nur vorwaerts, solange die naechste Messung nicht
        // weiter entfernt ist als die aktuelle.
        while (cursor + 1 < sorted.size &&
            abs(sortedMs[cursor + 1] - time) <= abs(sortedMs[cursor] - time)
        ) {
            cursor++
        }

        val best = sorted[cursor]
        val delta = abs(sortedMs[cursor] - time)
        if (delta > toleranceMs) {
            points.add(point)
            continue
        }

        val bpm = dartRound(best.bpm).toInt()
        sum += best.bpm
        count++
        if (bpm > peak) {
            peak = bpm
        }
        points.add(
            TrackPoint(
                lat = point.lat,
                lon = point.lon,
                ele = point.ele,
                time = point.time,
                hr = bpm,
            ),
        )
    }

    if (count == 0) {
        return null
    }

    return Ride(
        id = ride.id,
        name = ride.name,
        createdAt = ride.createdAt,
        points = points.toList(),
        stats = RideStats(
            distanceKm = ride.stats.distanceKm,
            durationS = ride.stats.durationS,
            movingTimeS = ride.stats.movingTimeS,
            avgSpeedKmh = ride.stats.avgSpeedKmh,
            ascentM = ride.stats.ascentM,
            descentM = ride.stats.descentM,
            avgHrBpm = dartRound(sum / count).toInt(),
            maxHrBpm = peak,
        ),
        // Der Merge baut die Tour neu auf; ohne diese Zeile wuerde er einer
        // gespeicherten Planung ihr Kennzeichen abnehmen (siehe [Ride.planned]).
        planned = ride.planned,
    )
}

/** Name einer importierten Tour, im Stil der App: „Tour 08.08.2026 (Watch)". */
fun healthRideName(workout: HealthWorkout): String {
    val d = workout.start
    val day = d.dayOfMonth.toString().padStart(2, '0')
    val month = d.monthValue.toString().padStart(2, '0')
    val suffix = if (workout.kind == HealthActivityKind.RADFAHREN_INDOOR) " (Indoor)" else ""
    return "Tour $day.$month.${d.year} (Watch)$suffix"
}

/** Naechstgelegene Herzfrequenz zu [time], maximal 30 s entfernt. */
internal fun nearestHr(samples: List<HealthHeartRateSample>, time: LocalDateTime): Int? {
    if (samples.isEmpty()) {
        return null
    }

    var best: HealthHeartRateSample? = null
    var bestDelta = NEAREST_HR_TOLERANCE_MS
    val timeMs = dartEpochMs(time)

    for (sample in samples) {
        val delta = abs(dartEpochMs(sample.time) - timeMs)
        if (delta <= bestDelta) {
            bestDelta = delta
            best = sample
        }
    }

    return best?.let { dartRound(it.bpm).toInt() }
}

/**
 * Verdichtet HRV-Messungen (rMSSD in ms) zu einem Wert je Kalendertag.
 *
 * Massgeblich sind ausschliesslich die Messungen zwischen 0:00 und 12:00 Uhr
 * lokaler Zeit: Die Galaxy Watch schreibt rMSSD im Schlaf, und nur naechtliche
 * bzw. morgendliche Werte sind untereinander vergleichbar. Tages-rMSSD liegt
 * durch Belastung, Kaffee, Stress und Koerperhaltung **systematisch** niedriger
 * — frueher galt an Tagen ohne Morgenwert ersatzweise das Tagesmittel, und
 * jeder solche Tag erschien der Baseline-Rechnung als HRV-Einbruch. Ein
 * fehlender Morgenwert ist deshalb ein fehlender Tag: Die Gates in [assessHrv]
 * (≥ 14 Tage Baseline, ≥ 3 Tage im Rollfenster) sind genau dafuer da, mit
 * Luecken umzugehen — ein verzerrter Wert ist schlechter als gar keiner.
 */
fun dailyHrvValues(samples: Iterable<HealthNumericSample>): List<DailyValue> {
    val morningSums = linkedMapOf<LocalDateTime, Double>()
    val morningCounts = linkedMapOf<LocalDateTime, Int>()

    for (sample in samples) {
        if (!sample.value.isFinite() || sample.value <= 0) {
            continue
        }
        if (sample.time.hour >= hrvMorningWindowEndHour) {
            continue
        }
        val day = atMidnight(sample.time)
        morningSums[day] = (morningSums[day] ?: 0.0) + sample.value
        morningCounts[day] = (morningCounts[day] ?: 0) + 1
    }

    val byDay = linkedMapOf<LocalDateTime, Double>()
    for ((day, sum) in morningSums) {
        byDay[day] = sum / morningCounts[day]!!
    }
    return sortedDaily(byDay)
}

/**
 * Ende des Zeitfensters, in dem eine rMSSD-Messung als „naechtlich" gilt
 * (exklusiv, lokale Stunde).
 */
const val hrvMorningWindowEndHour: Int = 12

private data class DayEntry(val day: LocalDateTime, val value: Double)

private fun dailyAverages(entries: Iterable<DayEntry>): List<DailyValue> {
    val sums = linkedMapOf<LocalDateTime, Double>()
    val counts = linkedMapOf<LocalDateTime, Int>()
    for (entry in entries) {
        sums[entry.day] = (sums[entry.day] ?: 0.0) + entry.value
        counts[entry.day] = (counts[entry.day] ?: 0) + 1
    }
    val averaged = linkedMapOf<LocalDateTime, Double>()
    for ((day, sum) in sums) {
        averaged[day] = sum / counts[day]!!
    }
    return sortedDaily(averaged)
}

private fun dailySums(entries: Iterable<DayEntry>): List<DailyValue> {
    val sums = linkedMapOf<LocalDateTime, Double>()
    for (entry in entries) {
        sums[entry.day] = (sums[entry.day] ?: 0.0) + entry.value
    }
    return sortedDaily(sums)
}

private fun sortedDaily(byDay: Map<LocalDateTime, Double>): List<DailyValue> =
    byDay.keys.sorted().map { DailyValue(day = it, value = dartRound1(byDay[it]!!)) }

/** Baut aus einer Tagesserie den 7-Tage-Trend relativ zu [now]. */
internal fun buildVitalsTrend(series: List<DailyValue>, now: LocalDateTime): VitalsTrend {
    if (series.isEmpty()) {
        return VitalsTrend.empty
    }

    val today = atMidnight(now)
    val lastWeekStart = dartPlusMillis(today, -6L * 24 * 60 * 60 * 1000)
    val previousWeekStart = dartPlusMillis(today, -13L * 24 * 60 * 60 * 1000)

    val lastWeek = mutableListOf<Double>()
    val previousWeek = mutableListOf<Double>()

    for (entry in series) {
        if (!entry.day.isBefore(lastWeekStart) && !entry.day.isAfter(today)) {
            lastWeek.add(entry.value)
        } else if (!entry.day.isBefore(previousWeekStart) && entry.day.isBefore(lastWeekStart)) {
            previousWeek.add(entry.value)
        }
    }

    return VitalsTrend(
        series = series,
        lastWeekAvg = averageOrNull(lastWeek),
        previousWeekAvg = averageOrNull(previousWeek),
    )
}

private fun averageOrNull(values: List<Double>): Double? {
    if (values.isEmpty()) {
        return null
    }
    return dartRound1(values.sum() / values.size)
}

/**
 * Startpunkt des Importfensters:
 * `since ?? (lastImportAt - Puffer) ?? to - initialWindowMs`.
 *
 * [initialWindowMs] ist das Anfangsfenster, wenn es keinen Zeitstempel gibt:
 * [healthSyncInitialWindowMs] (30 Tage) ohne Historien-Freigabe — mehr gibt
 * Health Connect dann ohnehin nicht heraus —, [healthSyncHistoryWindowMs] mit.
 * Welches gilt, entscheidet [HealthSyncService]; hier bleibt es eine Zahl,
 * damit die Fensterlogik rein und ohne Gateway pruefbar bleibt.
 *
 * ## Warum der Puffer
 * Gefiltert wird nach der **Startzeit** eines Workouts, und Samsung Health
 * spiegelt die Daten einer Uhr erst Stunden nach der Fahrt nach Health
 * Connect. Ohne Ueberlappung fiel eine solche Session fuer immer durchs
 * Raster: Tour von 10 bis 12 Uhr, App-Sync um 12:30 → `lastImportAt = 12:30`;
 * die Uhr spiegelt um 14 Uhr, das naechste Fenster beginnt aber bei 12:30 und
 * die Session startete um 10 Uhr. Sie wurde nie gefunden — weder importiert
 * noch zum Anreichern der aufgezeichneten Tour um die Herzfrequenz benutzt.
 *
 * [healthSyncImportBackfillMs] Tage decken jede realistische Verzoegerung ab
 * (Uhr tagelang nicht in Reichweite des Telefons, Health Connect im
 * Energiesparmodus).
 *
 * ## Warum das gefahrlos ist
 * Der Puffer laesst dieselben Sessions mehrfach *betrachten*, nicht mehrfach
 * importieren. Zwei Schranken greifen davor, beide in `importWithReport`:
 *
 *  * Die Ride-ID einer importierten Session ist aus der Datensatz-ID
 *    abgeleitet ([healthRideId]); eine erneut gesehene Session ist damit
 *    namentlich ein Duplikat und wird uebersprungen.
 *  * Sessions ohne bekannte ID (nativer Reader, andere Quelle) fallen ueber
 *    die Ueberlappungssuche (`findOverlap`, mehr als
 *    [healthSyncOverlapThreshold] gemeinsame Zeit) auf die bestehende Tour;
 *    die wird nur dann angefasst, wenn sie noch **keine** Herzfrequenz hat.
 *
 * Der Preis ist also nur eine etwas groessere Abfrage an Health Connect.
 *
 * Als reine Funktion herausgezogen, damit die Fensterlogik ohne
 * [HealthSyncStore] pruefbar bleibt.
 */
fun healthImportWindowStart(
    since: LocalDateTime?,
    lastImportAt: LocalDateTime?,
    to: LocalDateTime,
    initialWindowMs: Long = healthSyncInitialWindowMs,
): LocalDateTime {
    if (since != null) return since
    if (lastImportAt != null) return dartPlusMillis(lastImportAt, -healthSyncImportBackfillMs)
    return dartPlusMillis(to, -initialWindowMs)
}

/**
 * Zerlegt das Importfenster `[from, to]` in aufeinanderfolgende Abschnitte von
 * hoechstens [sliceMs], von alt nach neu; der letzte endet genau bei [to].
 *
 * Von alt nach neu, damit ein abgebrochener Lang-Import einen lueckenlosen
 * Stand „bis hierhin erledigt" hinterlaesst. Gerechnet wird wie ueberall auf
 * der absoluten Zeitachse ([dartPlusMillis]); ein Sommerzeitwechsel
 * verschiebt die Grenzen also nicht gegeneinander. Ein leeres oder
 * verkehrtes Fenster ergibt genau einen Abschnitt, damit der Aufrufer
 * denselben Weg nimmt wie immer.
 */
fun healthImportSlices(
    from: LocalDateTime,
    to: LocalDateTime,
    sliceMs: Long = healthSyncSliceMs,
): List<Pair<LocalDateTime, LocalDateTime>> {
    require(sliceMs > 0) { "sliceMs muss positiv sein" }
    val fromMs = dartEpochMs(from)
    val toMs = dartEpochMs(to)
    if (toMs - fromMs <= sliceMs) return listOf(from to to)

    val slices = mutableListOf<Pair<LocalDateTime, LocalDateTime>>()
    var start = fromMs
    while (start < toMs) {
        val end = minOf(start + sliceMs, toMs)
        slices.add(dartLocalOf(start) to (if (end == toMs) to else dartLocalOf(end)))
        start = end
    }
    return slices
}

// ---------------------------------------------------------------------------
// Service
// ---------------------------------------------------------------------------

/**
 * Liest Touren und Vitaldaten aus Health Connect.
 *
 * 1:1-Portierung von `HealthSyncService` — nur die Plattformzugriffe laufen
 * ueber [gateway] und [store] statt ueber `health`/`SharedPreferences`.
 */
class HealthSyncService(
    val gateway: HealthGateway,
    val store: HealthSyncStore = InMemoryHealthSyncStore(),
    private val now: () -> LocalDateTime = { LocalDateTime.now() },
) {
    /** Prueft Installation und Berechtigungen in einem Rutsch. */
    fun checkAvailability(): HealthConnection {
        val availability = gateway.availability()
        if (availability != HealthAvailability.VERFUEGBAR) {
            return HealthConnection(availability = availability, hasPermissions = false)
        }

        val granted = try {
            gateway.hasPermissions()
        } catch (_: Throwable) {
            false
        }

        return HealthConnection(availability = availability, hasPermissions = granted)
    }

    /**
     * Fragt die benoetigten Leserechte an.
     *
     * Liefert `false`, wenn Health Connect nicht verfuegbar ist oder die
     * Nutzerin ablehnt.
     */
    fun requestPermissions(): Boolean {
        if (gateway.availability() != HealthAvailability.VERFUEGBAR) {
            return false
        }
        if (gateway.hasPermissions()) {
            return true
        }
        return gateway.requestPermissions()
    }

    /**
     * Ob die Historien-Freigabe erteilt ist. Jeder Fehler beim Nachfragen
     * zaehlt als „nein" — dann bleibt es beim 30-Tage-Fenster, das Health
     * Connect ohnehin liefert, statt den Import scheitern zu lassen.
     */
    fun hasHistoryAccess(): Boolean = try {
        gateway.hasHistoryPermission()
    } catch (_: Throwable) {
        false
    }

    /**
     * Stand der Historien-Freigabe fuer die UI: `true` erteilt, `false`
     * moeglich, aber (noch) nicht erteilt, `null` unbekannt oder vom Geraet
     * nicht unterstuetzt.
     *
     * Die Unterscheidung von `false` und `null` ist der Punkt: Nur bei `false`
     * lohnt es, die Nutzerin um die Freigabe zu bitten. Kennt Health Connect
     * das Recht gar nicht, fuehrte ein Knopf ins Leere.
     */
    fun historyAccessStatus(): Boolean? {
        if (hasHistoryAccess()) return true
        return try {
            gateway.readPermissionStatus()?.get(HealthReadType.HISTORIE)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Zeigt den Berechtigungsdialog erneut, um die Historien-Freigabe
     * nachzuholen, und meldet, ob sie danach erteilt ist.
     *
     * Anders als [requestPermissions] ohne Abkuerzung „Pflichtrechte schon da
     * → fertig": Genau dann fehlt ja nur noch die Historie, und der Dialog
     * muss trotzdem erscheinen. Die Gateway-Anfrage enthaelt das Recht, wenn
     * das Geraet es kennt.
     */
    fun requestHistoryAccess(): Boolean {
        if (gateway.availability() != HealthAvailability.VERFUEGBAR) return false
        gateway.requestPermissions()
        return hasHistoryAccess()
    }

    /**
     * Das volle Fenster fuer „Alles neu importieren": ein Jahr mit
     * Historien-Freigabe, sonst 30 Tage.
     */
    fun fullWindowMs(): Long =
        if (hasHistoryAccess()) healthSyncHistoryWindowMs else healthSyncInitialWindowMs

    /**
     * Beginn des vollen Fensters fuer „Alles neu importieren": jetzt minus
     * [fullWindowMs], auf der absoluten Zeitachse — dieselbe Rechnung wie ohne
     * Zeitstempel in [healthImportWindowStart].
     *
     * Blockiert: fragt die Historien-Freigabe bei Health Connect nach, also
     * nie vom Main-Thread aufrufen.
     */
    fun fullWindowStart(): LocalDateTime = dartPlusMillis(now(), -fullWindowMs())

    /** Zeitpunkt des letzten erfolgreichen Imports, `null` wenn noch nie. */
    fun lastImportAt(): LocalDateTime? = store.lastImportAtMs()?.let { dartLocalOf(it) }

    /**
     * Setzt den Import-Zeitstempel. `null` loescht ihn (naechster Import
     * betrachtet dann wieder [healthSyncInitialWindowMs]).
     */
    fun setLastImportAt(value: LocalDateTime?) {
        store.setLastImportAtMs(value?.let { dartEpochMs(it) })
    }

    /**
     * Importiert neue Rad-Workouts als [Ride]s.
     *
     * Duenner Wrapper um [importWithReport] — liefert nur die neu angelegten
     * Touren.
     */
    fun importNewRides(existing: List<Ride>, since: LocalDateTime? = null): List<Ride> =
        importWithReport(existing = existing, since = since).imported

    /**
     * Importiert neue Rad-Workouts und liefert zusaetzlich eine Diagnose.
     *
     * Ohne [persist] speichert der Aufrufer die Touren selbst — Zeitstempel
     * und Lang-Import-Merker sind dann aber schon gesetzt, wenn der Bericht
     * zurueckkommt. Wer speichert, sollte deshalb [persist] benutzen (siehe
     * die zweite Ueberladung): Nur dann bestaetigt der Dienst den Fortschritt
     * erst, wenn die Touren wirklich auf der Platte liegen.
     *
     * Wirft [HealthSyncException], wenn Health Connect nicht verfuegbar ist
     * oder die Berechtigungen fehlen.
     */
    fun importWithReport(
        existing: List<Ride>,
        since: LocalDateTime? = null,
        persist: ((HealthSyncReport) -> Unit)? = null,
    ): HealthSyncReport =
        importWithReportInternal(
            // Zeitraum wie eh und je aus den Punkten der bereits geladenen
            // Touren; fuer den HF-Merge liegen sie ohnehin schon vor.
            refs = existing.map { ExistingRideRef(it.id, rideTimeRange(it), it) },
            loadRide = { null },
            since = since,
            excludedRideIds = emptySet(),
            persist = persist,
        )

    /**
     * Wie [importWithReport], arbeitet aber auf **Zusammenfassungen** statt
     * voller Touren — die App muss dafuer nicht mehr saemtliche GPS-Punkte
     * aller Touren in den Speicher heben.
     *
     * Der Zeitraum je Bestandstour kommt dann aus `createdAt` plus
     * `stats.durationS` (siehe [summaryTimeRange]) statt aus den
     * Punkt-Zeitstempeln — fuer die Ueberlappungspruefung gegen frische
     * Workouts gleichwertig, denn beides beschreibt dieselbe Fahrt. Nur wenn
     * ein Workout tatsaechlich ueberlappt UND die Tour fuer eine
     * HF-Anreicherung infrage kommt, laedt [loadRide] die volle Tour nach;
     * liefert es `null` (Datei unlesbar/geloescht), zaehlt die Session wie
     * bisher als Duplikat und nichts geht verloren.
     *
     * [excludedRideIds] sind Touren, die die Nutzerin geloescht hat
     * (Tombstones des Selfhost-Syncs). Sie gelten als vorhanden: Gerade der
     * Lang-Import liest ein ganzes Jahr und holte sonst ungefragt zurueck, was
     * bewusst weg sollte.
     *
     * ## Speichern ueber [persist]
     * Ist [persist] gesetzt, reicht der Dienst jeden Abschnitt (siehe
     * [healthImportSlices]) sofort dorthin weiter und bestaetigt
     * Zeitstempel, Lang-Import-Fortschritt und Merker erst **nachdem**
     * [persist] ohne Fehler zurueckkam. Wirft [persist], bricht der Lauf mit
     * genau dieser Ausnahme ab; bestaetigt bleibt nur, was schon gespeichert
     * ist. Ein abgebrochener Lang-Import (Fehler, Prozess beendet) setzt beim
     * naechsten Lauf am letzten gespeicherten Abschnitt wieder an, statt das
     * Jahr abzuhaken, ohne dass eine Tour davon auf der Platte liegt.
     *
     * Der zurueckgegebene Bericht traegt dann nur noch Touren **ohne**
     * Trackpunkte ([HealthSyncReport.persisted]): Sie sind gespeichert, und
     * ein Jahr voller GPS-Spuren gleichzeitig im Speicher zu halten, war
     * genau das, was die Abschnitte verhindern sollen.
     */
    fun importWithReport(
        existing: List<RideSummary>,
        loadRide: (String) -> Ride?,
        since: LocalDateTime? = null,
        excludedRideIds: Set<String> = emptySet(),
        persist: ((HealthSyncReport) -> Unit)? = null,
    ): HealthSyncReport = importWithReportInternal(
        refs = existing.map { ExistingRideRef(it.id, summaryTimeRange(it), null) },
        loadRide = loadRide,
        since = since,
        excludedRideIds = excludedRideIds,
        persist = persist,
    )

    /** Eine Bestandstour, wie die Import-Logik sie braucht: ID, Zeitraum, ggf. schon geladen. */
    private class ExistingRideRef(
        val id: String,
        val range: RideTimeRange,
        val preloaded: Ride?,
    )

    /**
     * Was ueber alle Abschnitte eines Laufs hinweg gilt: bekannte Zeitraeume
     * und IDs (auch die der gerade importierten Touren, damit ein spaeterer
     * Abschnitt sie nicht noch einmal anlegt), die schon angereicherten
     * Touren und die bereits behandelten Sessions.
     */
    private class RunState(
        val ranges: MutableList<TimeRangeWithRide>,
        val knownIds: MutableSet<String>,
        val mergeTargets: MutableSet<String> = mutableSetOf(),
        /**
         * Workout-IDs, die ein frueherer Abschnitt schon gesehen hat. Die
         * Abschnitte ueberlappen sich um [SLICE_OVERLAP_MS]; eine Session an
         * der Grenze kaeme sonst zweimal vor und zaehlte beim zweiten Mal als
         * Duplikat.
         */
        val seen: MutableSet<String> = mutableSetOf(),
    )

    /** Ergebnis eines einzelnen Abschnitts, noch ohne Diagnosezeilen. */
    private class SliceResult(
        val workoutsFound: Int,
        val imported: List<Ride>,
        val merged: List<Ride>,
        val duplicates: Int,
        val routesMissing: Int,
        val consentPending: List<RouteConsentRequest>,
    )

    private fun importWithReportInternal(
        refs: List<ExistingRideRef>,
        loadRide: (String) -> Ride?,
        since: LocalDateTime?,
        excludedRideIds: Set<String>,
        persist: ((HealthSyncReport) -> Unit)?,
    ): HealthSyncReport {
        val connection = checkAvailability()
        if (!connection.isReady) {
            throw HealthSyncException(connection.message)
        }

        val to = now()
        val historyStatus = historyAccessStatus()
        val hasHistory = historyStatus == true
        // Freigabe ausdruecklich entzogen (nicht nur „unbekannt"): Der Merker
        // faellt, damit eine spaeter erneut erteilte Freigabe wieder den
        // versprochenen Jahres-Import bekommt. Dank der stabilen Ride-IDs
        // entstehen dabei keine Duplikate.
        if (historyStatus == false && store.historyImportDone()) {
            store.setHistoryImportDone(false)
            store.setHistoryImportedUntilMs(null)
        }

        // Lang-Import: Historien-Freigabe da, das Jahr davor aber noch nie
        // vollstaendig gelesen. Das trifft den ersten Sync mit Freigabe ebenso
        // wie eine Freigabe, die erst nach Wochen nachgereicht wurde — in
        // beiden Faellen wird der Zeitstempel ignoriert und das Jahresfenster
        // genommen. Ein ausdrueckliches `since` (manuelles Neu-Importieren)
        // hat Vorrang.
        val longImport = since == null && hasHistory && !store.historyImportDone()
        val historyStart = dartPlusMillis(to, -healthSyncHistoryWindowMs)
        // Ein abgebrochener Lang-Import setzt am letzten gespeicherten
        // Abschnitt wieder an. Liegt der Fortschritt ausserhalb des Jahres
        // (Uhr verstellt, uralter Stand), gilt er nicht.
        val resumeAt = if (longImport) {
            store.historyImportedUntilMs()
                ?.let { dartLocalOf(it) }
                ?.takeIf { it.isAfter(historyStart) && it.isBefore(to) }
        } else {
            null
        }
        val from = when {
            longImport -> resumeAt ?: healthImportWindowStart(
                since = null,
                lastImportAt = null,
                to = to,
                initialWindowMs = healthSyncHistoryWindowMs,
            )
            else -> healthImportWindowStart(since, lastImportAt(), to)
        }
        // Auch „Alles neu importieren" ueber das ganze Jahr (mit Freigabe)
        // erledigt den Lang-Import — sonst laese der naechste normale Sync
        // dasselbe Jahr gleich noch einmal.
        val coversHistory = longImport || (since != null && hasHistory && !since.isAfter(historyStart))

        val debug = mutableListOf("Zeitraum: ${healthDebugTime(from)} – ${healthDebugTime(to)}")
        if (longImport) {
            debug.add(
                if (resumeAt != null) {
                    "Historie: freigegeben, Import der letzten 365 Tage wird fortgesetzt"
                } else {
                    "Historie: freigegeben, einmaliger Import der letzten 365 Tage"
                },
            )
        }

        val slices = healthImportSlices(from, to)
        val verbose = slices.size == 1
        if (!verbose) {
            debug.add("Abschnitte: ${slices.size} zu je höchstens 30 Tagen")
        }

        // Zeitraum plus (falls bekannt) die dahinterstehende Tour. Innerhalb
        // dieses Laufs importierte Sessions kommen ohne Tour dazu, damit zwei
        // nahezu identische Sessions nicht doppelt landen.
        val state = RunState(
            ranges = refs.mapTo(mutableListOf()) { TimeRangeWithRide(it.range.start, it.range.end, it) },
            knownIds = (refs.map { it.id } + excludedRideIds).toMutableSet(),
        )

        var workoutsFound = 0
        var duplicates = 0
        var routesMissing = 0
        val imported = mutableListOf<Ride>()
        val merged = mutableListOf<Ride>()
        val consentPending = mutableListOf<RouteConsentRequest>()

        for ((index, slice) in slices.withIndex()) {
            // Ab dem zweiten Abschnitt einen Tag zurueckgreifen: Ob Health
            // Connect eine Session liefert, die ueber die Grenze reicht,
            // haengt an ihrer Lage zum Filter — die Ueberlappung stellt
            // sicher, dass sie in einem der beiden Abschnitte ankommt.
            val readFrom = if (index == 0) {
                slice.first
            } else {
                maxOf(from, dartPlusMillis(slice.first, -SLICE_OVERLAP_MS))
            }
            val result = importSlice(
                from = readFrom,
                to = slice.second,
                state = state,
                loadRide = loadRide,
                log = if (verbose) debug else null,
            )
            if (!verbose) {
                debug.add(
                    "  · ${healthDebugTime(slice.first)}–${healthDebugTime(slice.second)}: " +
                        "${result.workoutsFound} Rad-Session(s), ${result.imported.size} importiert",
                )
            }

            if (persist != null) {
                val sliceReport = HealthSyncReport(
                    from = slice.first,
                    to = slice.second,
                    workoutsFound = result.workoutsFound,
                    imported = result.imported,
                    mergedRides = result.merged,
                    duplicatesSkipped = result.duplicates,
                    routesMissing = result.routesMissing,
                    routeConsentPending = result.consentPending,
                    historyImport = longImport,
                )
                if (!sliceReport.isEmpty) persist(sliceReport)
                // Erst jetzt ist der Abschnitt wirklich erledigt.
                if (longImport) store.setHistoryImportedUntilMs(dartEpochMs(slice.second))
            }

            workoutsFound += result.workoutsFound
            duplicates += result.duplicates
            routesMissing += result.routesMissing
            consentPending.addAll(result.consentPending)
            // Gespeicherte Touren ohne ihre Punkte weitertragen: Anzeige und
            // Aufrufer brauchen nur IDs und Zahlen, und genau die Punkte eines
            // ganzen Jahres sollen nicht gleichzeitig im Speicher liegen.
            if (persist != null) {
                result.imported.mapTo(imported) { it.copy(points = emptyList()) }
                result.merged.mapTo(merged) { it.copy(points = emptyList()) }
            } else {
                imported.addAll(result.imported)
                merged.addAll(result.merged)
            }
        }

        setLastImportAt(to)
        if (coversHistory) store.setHistoryImportDone(true)
        if (coversHistory || longImport) store.setHistoryImportedUntilMs(null)

        debug.add(
            "Ergebnis: ${imported.size} importiert, ${merged.size} angereichert, " +
                "$duplicates Duplikat(e), $routesMissing ohne Route",
        )
        if (routesMissing > 0) {
            debug.add(
                "Routen: ${consentPending.size} brauchen eine Einzel-Freigabe, " +
                    "${routesMissing - consentPending.size} ohne Routendaten in Health Connect",
            )
        }

        return HealthSyncReport(
            from = from,
            to = to,
            workoutsFound = workoutsFound,
            imported = imported.toList(),
            mergedRides = merged.toList(),
            duplicatesSkipped = duplicates,
            routesMissing = routesMissing,
            debugLines = debug.toList(),
            routeConsentPending = consentPending.toList(),
            historyImport = longImport,
            persisted = persist != null,
        )
    }

    /**
     * Ein Abschnitt des Imports: Workouts lesen, gegen Bestand und bisherige
     * Abschnitte abgleichen, neue Touren bauen und Kandidaten anreichern.
     *
     * [log] bekommt die ausfuehrliche Diagnose (Rohdaten, Fallback) — nur bei
     * einem Lauf aus einem einzigen Abschnitt; bei zwoelf Abschnitten wuerde
     * sie den Dialog sprengen, dort genuegt je Abschnitt eine Zeile.
     */
    private fun importSlice(
        from: LocalDateTime,
        to: LocalDateTime,
        state: RunState,
        loadRide: (String) -> Ride?,
        log: MutableList<String>?,
    ): SliceResult {
        val workouts: List<HealthWorkout> = try {
            gateway.readWorkouts(from, to)
        } catch (error: Throwable) {
            throw HealthSyncException(
                "Die Trainings konnten nicht aus Health Connect gelesen werden: " +
                    describeError(error),
            )
        }

        if (log != null) {
            val diagnostics = gateway.lastWorkoutDiagnostics
            log.add(diagnostics?.describe() ?: "Plugin: keine Rohdiagnose erhoben")
            log.add("Plugin: ${workouts.size} Session(s) gemappt")
            for (workout in workouts.take(DEBUG_SESSION_LIMIT)) {
                log.add(
                    "  · ${workout.kind.dartName} ${healthDebugTime(workout.start)}" +
                        "–${healthDebugTime(workout.end)} · ${workout.sourceName ?: "ohne Quelle"}",
                )
            }
        }

        var cycling = workouts.filter { it.isCycling }.sortedBy { dartEpochMs(it.start) }
        log?.add("Plugin: ${cycling.size} Rad-Session(s)")

        var fallbackUsed = false
        if (cycling.isEmpty()) {
            val fallback = readNativeSessions(from = from, to = to, log = log ?: mutableListOf())
            if (fallback.isNotEmpty()) {
                fallbackUsed = true
                cycling = fallback
            }
        }
        log?.add(
            if (fallbackUsed) {
                "Fallback: aktiv, ${cycling.size} Rad-Session(s) aus dem nativen Reader"
            } else {
                "Fallback: nicht verwendet"
            },
        )

        // Sessions, die schon ein frueherer Abschnitt behandelt hat, zaehlen
        // hier gar nicht — weder als gefunden noch als Duplikat.
        cycling = cycling.filter { state.seen.add(it.id) }

        val candidates = mutableListOf<HealthWorkout>()
        val mergeCandidates = mutableListOf<MergeCandidate>()
        var duplicates = 0

        for (workout in cycling) {
            if (state.knownIds.contains(healthRideId(workout.id))) {
                duplicates++
                continue
            }

            val overlap = findOverlap(workout, state.ranges)
            if (overlap == null) {
                candidates.add(workout)
                state.ranges.add(TimeRangeWithRide(workout.start, workout.end, null))
                state.knownIds.add(healthRideId(workout.id))
                continue
            }

            val ref = overlap.ride
            // Nur bestehende Touren ohne Herzfrequenz werden angereichert, und
            // jede hoechstens einmal je Lauf. Die volle Tour wird erst hier —
            // also nur fuer tatsaechlich ueberlappende Sessions — geladen;
            // laesst sie sich nicht laden, bleibt die Session ein Duplikat.
            val ride = ref?.let { it.preloaded ?: loadRide(it.id) }
            if (ride == null || rideHasHeartRate(ride) || !state.mergeTargets.add(ride.id)) {
                duplicates++
                continue
            }
            mergeCandidates.add(MergeCandidate(workout, ride))
        }

        val imported = mutableListOf<Ride>()
        var routesMissing = 0
        val consentPending = mutableListOf<RouteConsentRequest>()

        if (candidates.isNotEmpty()) {
            val windowStart = candidates.first().start
            val windowEnd = candidates.map { it.end }.reduce { a, b -> if (a.isAfter(b)) a else b }

            // Routen nur fuer die Kandidaten: Laeufe und Spaziergaenge im
            // selben Zeitraum gehen Trailscape nichts an. Die Herzfrequenz
            // wird dagegen je Workout gelesen: ueber 30 Tage kaemen sonst
            // leicht sechsstellige Messreihen zusammen.
            val routeResult = readOptional(
                {
                    gateway.readRoutesForSessions(
                        sessionIds = candidates.mapTo(linkedSetOf()) { it.id },
                        from = windowStart,
                        to = windowEnd,
                    )
                },
                HealthRouteReadResult(routes = emptyMap()),
            )
            val routes = routeResult.routes

            for (workout in candidates) {
                val heartRate = readOptional(
                    { gateway.readHeartRate(workout.start, workout.end) },
                    emptyList(),
                )
                val ride = buildRideFromWorkout(
                    workout,
                    route = routes[workout.id] ?: emptyList(),
                    heartRate = heartRate,
                )
                imported.add(ride)
                if (workout.kind == HealthActivityKind.RADFAHREN && ride.points.isEmpty()) {
                    routesMissing++
                    // ConsentRequired ist kein „keine Route": Die Route liegt
                    // in Health Connect und laesst sich per Einzel-Freigabe
                    // nachholen (siehe HealthRouteConsent.kt).
                    if (routeResult.consentRequired.contains(workout.id)) {
                        consentPending.add(
                            RouteConsentRequest(
                                sessionId = workout.id,
                                rideId = ride.id,
                                start = workout.start,
                                end = workout.end,
                                source = workout.sourceName,
                            ),
                        )
                    }
                }
            }
        }

        val merged = mutableListOf<Ride>()
        for (entry in mergeCandidates) {
            val heartRate = readOptional(
                { gateway.readHeartRate(entry.workout.start, entry.workout.end) },
                emptyList(),
            )
            val enriched = mergeHeartRateIntoRide(entry.ride, heartRate)
            if (enriched == null) {
                // Ohne verwertbare Messwerte bleibt es beim bisherigen Verhalten:
                // Die Session ist ein Duplikat der bestehenden Tour.
                duplicates++
                continue
            }
            merged.add(enriched)
        }

        return SliceResult(
            workoutsFound = cycling.size,
            imported = imported,
            merged = merged,
            duplicates = duplicates,
            routesMissing = routesMissing,
            consentPending = consentPending,
        )
    }

    /**
     * Liest die Sessions ueber den nativen Reader und filtert die Rad-Sessions
     * heraus. Schlaegt der Weg fehl (fehlender Kanal, verweigerter Zugriff),
     * bleibt es beim Plugin-Ergebnis — der Fehler landet nur in [log].
     */
    private fun readNativeSessions(
        from: LocalDateTime,
        to: LocalDateTime,
        log: MutableList<String>,
    ): List<HealthWorkout> {
        val sessions: List<HealthSessionInfo> = try {
            gateway.readExerciseSessionsNative(from, to)
        } catch (error: Throwable) {
            log.add("Nativ: nicht verfügbar (${describeError(error)})")
            return emptyList()
        }

        log.add("Nativ: ${sessions.size} Session(s)")
        for (session in sessions.take(DEBUG_SESSION_LIMIT)) {
            log.add(
                "  · ${session.typeName} (${session.typeCode}) " +
                    "${healthDebugTime(session.start)}–${healthDebugTime(session.end)} · " +
                    "${session.title ?: "ohne Titel"} · " +
                    "${session.source ?: "ohne Quelle"} · " +
                    "Route ${if (session.hasRoute) "ja" else "nein"}",
            )
        }

        val cycling = mutableListOf<HealthWorkout>()
        for (session in sessions) {
            val kind = mapNativeSessionKind(session) ?: continue
            // Distanz und Energie bleiben leer: sie stecken in eigenen Datensaetzen,
            // die der native Reader bewusst nicht mitliest. buildRideFromWorkout
            // rechnet die Distanz dann aus der Route.
            cycling.add(
                HealthWorkout(
                    id = session.uid,
                    start = session.start,
                    end = session.end,
                    kind = kind,
                    sourceName = session.source,
                ),
            )
        }
        return cycling.sortedBy { dartEpochMs(it.start) }
    }

    /**
     * Liest Ruhepuls, Schlaf, HRV und (falls verfuegbar) VO2max der letzten
     * [days] Tage und verdichtet sie zu Tagesserien mit 7-Tage-Trend.
     *
     * Wirft nicht: Faellt ein einzelner Datentyp aus (fehlende Berechtigung,
     * Plattform-Grenze), landet er in [VitalsSummary.unavailable]; die uebrigen
     * Werte werden trotzdem geliefert.
     *
     * Zwei Ableitungen kommen hinzu:
     *  * **Ruhepuls-Ersatz:** Fuer jeden Tag ohne `RestingHeartRateRecord`
     *    wird der Nacht-Puls gelesen und daraus ein Ruhepuls abgeleitet
     *    ([nightWindowForDay], [nightlyRestingHeartRate]). Ein vorhandener
     *    Datensatz gewinnt immer; welche Tage abgeleitet sind, steht in
     *    [VitalsSummary.restingHeartRateDerivedDays].
     *  * **Schlaf ohne Doppelzaehlung:** Ueberlappende Sitzungen mehrerer
     *    Apps werden vor dem Summieren vereinigt ([mergeOverlappingSleep]).
     *
     * [VitalsSummary.diagnostics] haelt je Datentyp fest, ob die Freigabe
     * erteilt ist, wie viele Eintraege kamen und aus welchen Apps — damit
     * „leer" von „nicht freigegeben" unterscheidbar ist.
     */
    fun readVitals(days: Int = 14): VitalsSummary {
        val windowDays = max(1, days)
        val to = now()
        val from = dartPlusMillis(atMidnight(to), -(windowDays - 1).toLong() * 24 * 60 * 60 * 1000)

        val unavailable = linkedSetOf<VitalsDataKind>()
        val errors = mutableMapOf<HealthReadType, String>()

        val resting = readOptional(
            { gateway.readRestingHeartRate(from, to) },
            emptyList(),
        ) { error ->
            unavailable.add(VitalsDataKind.RUHEPULS)
            errors[HealthReadType.RUHEPULS] = describeError(error)
        }
        val sleep = readOptional(
            { gateway.readSleepSessions(from, to) },
            emptyList(),
        ) { error ->
            unavailable.add(VitalsDataKind.SCHLAF)
            errors[HealthReadType.SCHLAF] = describeError(error)
        }
        val vo2 = readOptional(
            { gateway.readVo2Max(from, to) },
            emptyList(),
        ) { error ->
            unavailable.add(VitalsDataKind.VO2MAX)
            errors[HealthReadType.VO2MAX] = describeError(error)
        }
        val hrv = readOptional(
            { gateway.readHrv(from, to) },
            emptyList(),
        ) { error ->
            unavailable.add(VitalsDataKind.HRV)
            errors[HealthReadType.HRV] = describeError(error)
        }
        val permissions = readOptional({ gateway.readPermissionStatus() }, null)

        // Ruhepuls-Ersatz aus dem Nacht-Puls — nur fuer Tage ohne
        // Ruhepuls-Datensatz (Methode siehe nightlyRestingHeartRate).
        val restingDays = resting.mapTo(HashSet()) { atMidnight(it.time) }
        val derived = linkedMapOf<LocalDateTime, Double>()
        var fallbackAttempted = false
        var nightSamples = 0
        val nightOrigins = sortedSetOf<String>()
        var day = atMidnight(from)
        val lastDay = atMidnight(to)
        while (!day.isAfter(lastDay)) {
            if (!restingDays.contains(day)) {
                val window = nightWindowForDay(day, sleep)
                val windowEnd = if (window.end.isAfter(to)) to else window.end
                if (window.start.isBefore(windowEnd)) {
                    fallbackAttempted = true
                    val samples = try {
                        gateway.readHeartRate(window.start, windowEnd)
                    } catch (error: Throwable) {
                        // Ohne Herzfrequenz-Freigabe scheitern alle Naechte
                        // gleich — einmal fragen reicht.
                        errors[HealthReadType.HERZFREQUENZ] = describeError(error)
                        break
                    }
                    nightSamples += samples.count {
                        !it.time.isBefore(window.start) && it.time.isBefore(windowEnd)
                    }
                    samples.mapNotNullTo(nightOrigins) { it.source }
                    nightlyRestingHeartRate(samples, window.start, windowEnd)?.let {
                        derived[day] = it
                    }
                }
            }
            day = addDays(day, 1)
        }
        // Liefert der Ersatz Werte, ist die Ruhepuls-Reihe gueltig — auch wenn
        // das Lesen der Ruhepuls-Datensaetze selbst scheiterte. Sonst wuerde
        // VitalsHistory.merge die abgeleiteten Tage verwerfen.
        if (derived.isNotEmpty()) {
            unavailable.remove(VitalsDataKind.RUHEPULS)
        }

        val restingSeries = dailyAverages(
            resting.map { DayEntry(day = atMidnight(it.time), value = it.value) } +
                derived.map { (d, bpm) -> DayEntry(day = d, value = bpm) },
        )
        // Ueberlappende Sitzungen (zwei Apps, dieselbe Nacht) zaehlen nur
        // einmal — siehe mergeOverlappingSleep.
        val mergedSleep = mergeOverlappingSleep(sleep)
        val sleepSeries = dailySums(
            mergedSleep.map {
                DayEntry(
                    // Der Schlaf wird dem Aufwachtag zugeordnet.
                    day = atMidnight(it.end),
                    value = it.durationMinutes / 60.0,
                )
            },
        )

        var latestVo2: HealthNumericSample? = null
        for (sample in vo2) {
            val current = latestVo2
            if (current == null || sample.time.isAfter(current.time)) {
                latestVo2 = sample
            }
        }

        return VitalsSummary(
            days = windowDays,
            from = from,
            to = to,
            restingHeartRate = buildVitalsTrend(restingSeries, to),
            sleepHours = buildVitalsTrend(sleepSeries, to),
            heartRateVariability = buildVitalsTrend(dailyHrvValues(hrv), to),
            vo2max = latestVo2?.let { dartRound1(it.value) },
            vo2maxAt = latestVo2?.time,
            unavailable = unavailable,
            diagnostics = vitalsDiagnostics(
                permissions = permissions,
                errors = errors,
                resting = resting,
                derivedDays = derived.size,
                fallbackAttempted = fallbackAttempted,
                nightSamples = nightSamples,
                nightOrigins = nightOrigins.toList(),
                sleep = sleep,
                mergedSleepCount = mergedSleep.size,
                hrv = hrv,
                vo2 = vo2,
            ),
            restingHeartRateDerivedDays = derived.keys.toSet(),
            historyAccessGranted = permissions?.get(HealthReadType.HISTORIE),
        )
    }

    /** Stellt die Diagnose je Datentyp fuer [readVitals] zusammen. */
    private fun vitalsDiagnostics(
        permissions: Map<HealthReadType, Boolean>?,
        errors: Map<HealthReadType, String>,
        resting: List<HealthNumericSample>,
        derivedDays: Int,
        fallbackAttempted: Boolean,
        nightSamples: Int,
        nightOrigins: List<String>,
        sleep: List<HealthSleepSession>,
        mergedSleepCount: Int,
        hrv: List<HealthNumericSample>,
        vo2: List<HealthNumericSample>,
    ): List<VitalsTypeDiagnostics> {
        fun origins(sources: List<String?>): List<String> =
            sources.filterNotNull().toSortedSet().toList()

        val out = mutableListOf(
            VitalsTypeDiagnostics(
                type = HealthReadType.RUHEPULS,
                permissionGranted = permissions?.get(HealthReadType.RUHEPULS),
                recordCount = resting.size,
                origins = origins(resting.map { it.source }),
                error = errors[HealthReadType.RUHEPULS],
                derivedDays = derivedDays,
                fallbackAttempted = fallbackAttempted,
            ),
        )
        if (fallbackAttempted) {
            out.add(
                VitalsTypeDiagnostics(
                    type = HealthReadType.HERZFREQUENZ,
                    permissionGranted = permissions?.get(HealthReadType.HERZFREQUENZ),
                    recordCount = nightSamples,
                    origins = nightOrigins,
                    error = errors[HealthReadType.HERZFREQUENZ],
                ),
            )
        }
        out.add(
            VitalsTypeDiagnostics(
                type = HealthReadType.SCHLAF,
                permissionGranted = permissions?.get(HealthReadType.SCHLAF),
                recordCount = sleep.size,
                origins = origins(sleep.map { it.source }),
                error = errors[HealthReadType.SCHLAF],
                mergedOverlaps = (sleep.count { !it.end.isBefore(it.start) } - mergedSleepCount)
                    .coerceAtLeast(0),
            ),
        )
        out.add(
            VitalsTypeDiagnostics(
                type = HealthReadType.HRV,
                permissionGranted = permissions?.get(HealthReadType.HRV),
                recordCount = hrv.size,
                origins = origins(hrv.map { it.source }),
                error = errors[HealthReadType.HRV],
            ),
        )
        out.add(
            VitalsTypeDiagnostics(
                type = HealthReadType.VO2MAX,
                permissionGranted = permissions?.get(HealthReadType.VO2MAX),
                recordCount = vo2.size,
                origins = origins(vo2.map { it.source }),
                error = errors[HealthReadType.VO2MAX],
            ),
        )
        return out
    }

    private data class TimeRangeWithRide(
        val start: LocalDateTime,
        val end: LocalDateTime,
        val ride: ExistingRideRef?,
    )

    private data class MergeCandidate(val workout: HealthWorkout, val ride: Ride)

    /**
     * Erster Zeitraum, mit dem sich [workout] zu mehr als
     * [healthSyncOverlapThreshold] ueberschneidet.
     */
    private fun findOverlap(
        workout: HealthWorkout,
        ranges: List<TimeRangeWithRide>,
    ): TimeRangeWithRide? {
        for (range in ranges) {
            val ratio = overlapRatio(
                aStart = workout.start,
                aEnd = workout.end,
                bStart = range.start,
                bEnd = range.end,
            )
            if (ratio > healthSyncOverlapThreshold) {
                return range
            }
        }
        return null
    }

    private fun <T> readOptional(
        read: () -> T,
        fallback: T,
        onError: ((Throwable) -> Unit)? = null,
    ): T =
        try {
            read()
        } catch (error: Throwable) {
            onError?.invoke(error)
            fallback
        }
}

/**
 * Entspricht Darts String-Interpolation eines gefangenen Fehlers (`'$error'`):
 * dort greift `Object.toString()`, das bei `StateError`/`UnsupportedError` die
 * Meldung mitfuehrt.
 */
private fun describeError(error: Throwable): String = error.toString()
