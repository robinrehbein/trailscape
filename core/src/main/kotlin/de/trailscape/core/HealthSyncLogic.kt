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
 * (`READ_HEALTH_DATA_HISTORY`) gibt Health Connect grundsaetzlich nur Daten der
 * letzten 30 Tage ab Zustimmung heraus.
 */
const val healthSyncInitialWindowMs: Long = 30L * 24 * 60 * 60 * 1000

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
}

/**
 * Zeitstempel nur im Arbeitsspeicher — Vorgabe fuer [HealthSyncService], wenn
 * (noch) kein persistenter Speicher angebunden ist, und Attrappe in Tests.
 */
class InMemoryHealthSyncStore(private var value: Long? = null) : HealthSyncStore {
    override fun lastImportAtMs(): Long? = value

    override fun setLastImportAtMs(value: Long?) {
        this.value = value
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
private fun nearestHr(samples: List<HealthHeartRateSample>, time: LocalDateTime): Int? {
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
 * Massgeblich sind die Messungen zwischen 0:00 und 12:00 Uhr lokaler Zeit:
 * Die Galaxy Watch schreibt rMSSD im Schlaf, und nur naechtliche bzw.
 * morgendliche Werte sind untereinander vergleichbar (tagsueber verzerren
 * Belastung, Kaffee und Stress den Wert stark). Gibt es an einem Tag keine
 * Messung in diesem Fenster, gilt ersatzweise das Tagesmittel.
 */
fun dailyHrvValues(samples: Iterable<HealthNumericSample>): List<DailyValue> {
    val morningSums = linkedMapOf<LocalDateTime, Double>()
    val morningCounts = linkedMapOf<LocalDateTime, Int>()
    val daySums = linkedMapOf<LocalDateTime, Double>()
    val dayCounts = linkedMapOf<LocalDateTime, Int>()

    for (sample in samples) {
        if (!sample.value.isFinite() || sample.value <= 0) {
            continue
        }
        val day = atMidnight(sample.time)
        daySums[day] = (daySums[day] ?: 0.0) + sample.value
        dayCounts[day] = (dayCounts[day] ?: 0) + 1
        if (sample.time.hour < 12) {
            morningSums[day] = (morningSums[day] ?: 0.0) + sample.value
            morningCounts[day] = (morningCounts[day] ?: 0) + 1
        }
    }

    val byDay = linkedMapOf<LocalDateTime, Double>()
    for (day in daySums.keys) {
        val morning = morningCounts[day]
        byDay[day] = if (morning != null && morning > 0) {
            morningSums[day]!! / morning
        } else {
            daySums[day]!! / dayCounts[day]!!
        }
    }
    return sortedDaily(byDay)
}

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
 * Startpunkt des Importfensters: `since ?? lastImportAt ?? to - 30 Tage`.
 *
 * Als reine Funktion herausgezogen, damit die Fensterlogik ohne
 * [HealthSyncStore] pruefbar bleibt.
 */
fun healthImportWindowStart(
    since: LocalDateTime?,
    lastImportAt: LocalDateTime?,
    to: LocalDateTime,
): LocalDateTime = since ?: lastImportAt ?: dartPlusMillis(to, -healthSyncInitialWindowMs)

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
     * Wirft [HealthSyncException], wenn Health Connect nicht verfuegbar ist
     * oder die Berechtigungen fehlen.
     */
    fun importWithReport(existing: List<Ride>, since: LocalDateTime? = null): HealthSyncReport {
        val connection = checkAvailability()
        if (!connection.isReady) {
            throw HealthSyncException(connection.message)
        }

        val to = now()
        val from = healthImportWindowStart(since, lastImportAt(), to)

        val debug = mutableListOf("Zeitraum: ${healthDebugTime(from)} – ${healthDebugTime(to)}")

        val workouts: List<HealthWorkout> = try {
            gateway.readWorkouts(from, to)
        } catch (error: Throwable) {
            throw HealthSyncException(
                "Die Trainings konnten nicht aus Health Connect gelesen werden: " +
                    describeError(error),
            )
        }

        val diagnostics = gateway.lastWorkoutDiagnostics
        debug.add(diagnostics?.describe() ?: "Plugin: keine Rohdiagnose erhoben")
        debug.add("Plugin: ${workouts.size} Session(s) gemappt")
        for (workout in workouts.take(DEBUG_SESSION_LIMIT)) {
            debug.add(
                "  · ${workout.kind.dartName} ${healthDebugTime(workout.start)}" +
                    "–${healthDebugTime(workout.end)} · ${workout.sourceName ?: "ohne Quelle"}",
            )
        }

        var cycling = workouts.filter { it.isCycling }.sortedBy { dartEpochMs(it.start) }
        debug.add("Plugin: ${cycling.size} Rad-Session(s)")

        var fallbackUsed = false
        if (cycling.isEmpty()) {
            val fallback = readNativeSessions(from = from, to = to, log = debug)
            if (fallback.isNotEmpty()) {
                fallbackUsed = true
                cycling = fallback
            }
        }
        debug.add(
            if (fallbackUsed) {
                "Fallback: aktiv, ${cycling.size} Rad-Session(s) aus dem nativen Reader"
            } else {
                "Fallback: nicht verwendet"
            },
        )

        // Zeitraum plus (falls bekannt) die dahinterstehende Tour. Innerhalb
        // dieses Laufs importierte Sessions kommen ohne Tour dazu, damit zwei
        // nahezu identische Sessions nicht doppelt landen.
        val ranges = mutableListOf<TimeRangeWithRide>()
        for (ride in existing) {
            val range = rideTimeRange(ride)
            ranges.add(TimeRangeWithRide(range.start, range.end, ride))
        }
        val knownIds = existing.map { it.id }.toMutableSet()
        val mergeTargets = mutableSetOf<String>()

        val candidates = mutableListOf<HealthWorkout>()
        val mergeCandidates = mutableListOf<MergeCandidate>()
        var duplicates = 0

        for (workout in cycling) {
            if (knownIds.contains(healthRideId(workout.id))) {
                duplicates++
                continue
            }

            val overlap = findOverlap(workout, ranges)
            if (overlap == null) {
                candidates.add(workout)
                ranges.add(TimeRangeWithRide(workout.start, workout.end, null))
                knownIds.add(healthRideId(workout.id))
                continue
            }

            val ride = overlap.ride
            // Nur bestehende Touren ohne Herzfrequenz werden angereichert, und
            // jede hoechstens einmal je Lauf.
            if (ride == null || rideHasHeartRate(ride) || !mergeTargets.add(ride.id)) {
                duplicates++
                continue
            }
            mergeCandidates.add(MergeCandidate(workout, ride))
        }

        val imported = mutableListOf<Ride>()
        var routesMissing = 0

        if (candidates.isNotEmpty()) {
            val windowStart = candidates.first().start
            val windowEnd = candidates.map { it.end }.reduce { a, b -> if (a.isAfter(b)) a else b }

            // Routen sind pro Session wenige Datensaetze — eine Abfrage ueber das
            // ganze Fenster reicht. Die Herzfrequenz wird dagegen je Workout
            // gelesen: ueber 30 Tage kaemen sonst leicht sechsstellige Messreihen
            // zusammen.
            val routes = readOptional({ gateway.readRoutes(windowStart, windowEnd) }, emptyMap())

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

        setLastImportAt(to)

        debug.add(
            "Ergebnis: ${imported.size} importiert, ${merged.size} angereichert, " +
                "$duplicates Duplikat(e), $routesMissing ohne Route",
        )

        return HealthSyncReport(
            from = from,
            to = to,
            workoutsFound = cycling.size,
            imported = imported.toList(),
            mergedRides = merged.toList(),
            duplicatesSkipped = duplicates,
            routesMissing = routesMissing,
            debugLines = debug.toList(),
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
     */
    fun readVitals(days: Int = 14): VitalsSummary {
        val windowDays = max(1, days)
        val to = now()
        val from = dartPlusMillis(atMidnight(to), -(windowDays - 1).toLong() * 24 * 60 * 60 * 1000)

        val unavailable = linkedSetOf<VitalsDataKind>()

        val resting = readOptional(
            { gateway.readRestingHeartRate(from, to) },
            emptyList(),
        ) { unavailable.add(VitalsDataKind.RUHEPULS) }
        val sleep = readOptional(
            { gateway.readSleepSessions(from, to) },
            emptyList(),
        ) { unavailable.add(VitalsDataKind.SCHLAF) }
        val vo2 = readOptional(
            { gateway.readVo2Max(from, to) },
            emptyList(),
        ) { unavailable.add(VitalsDataKind.VO2MAX) }
        val hrv = readOptional(
            { gateway.readHrv(from, to) },
            emptyList(),
        ) { unavailable.add(VitalsDataKind.HRV) }

        val restingSeries = dailyAverages(
            resting.map { DayEntry(day = atMidnight(it.time), value = it.value) },
        )
        val sleepSeries = dailySums(
            sleep.map {
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
        )
    }

    private data class TimeRangeWithRide(
        val start: LocalDateTime,
        val end: LocalDateTime,
        val ride: Ride?,
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

    private fun <T> readOptional(read: () -> T, fallback: T, onError: (() -> Unit)? = null): T =
        try {
            read()
        } catch (_: Throwable) {
            onError?.invoke()
            fallback
        }
}

/**
 * Entspricht Darts String-Interpolation eines gefangenen Fehlers (`'$error'`):
 * dort greift `Object.toString()`, das bei `StateError`/`UnsupportedError` die
 * Meldung mitfuehrt.
 */
private fun describeError(error: Throwable): String = error.toString()
