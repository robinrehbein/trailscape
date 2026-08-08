package de.trailscape.app.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.aggregate.AggregationResult
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseRouteResult
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import de.trailscape.core.HealthActivityKind
import de.trailscape.core.HealthAvailability
import de.trailscape.core.HealthGateway
import de.trailscape.core.HealthHeartRateSample
import de.trailscape.core.HealthNumericSample
import de.trailscape.core.HealthRoutePoint
import de.trailscape.core.HealthSessionInfo
import de.trailscape.core.HealthSleepSession
import de.trailscape.core.HealthSyncException
import de.trailscape.core.HealthWorkout
import de.trailscape.core.HealthWorkoutReadDiagnostics
import de.trailscape.core.mapNativeSessionKind
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.roundToInt
import kotlin.reflect.KClass
import kotlinx.coroutines.runBlocking

/**
 * Die produktive Implementierung von `:core`s [HealthGateway] — direkt gegen
 * `androidx.health.connect:connect-client`, ohne jede Plugin-Schicht.
 *
 * Loest damit gleich zwei Konstruktionen des Flutter-Vorgaengers ab: den
 * `HealthPluginGateway` (Paket `health`) und den Notbehelf-Platform-Channel
 * `trailscape/health_extra` (`HealthExtraChannel.kt`), der nur existierte, weil
 * das Plugin VO2max nicht kannte und beim Anreichern der Sessions still
 * scheiterte. Hier wird alles aus derselben Quelle gelesen, und zwar genau die
 * Datensaetze, die gebraucht werden.
 *
 * **Threading.** [HealthGateway] ist bewusst nicht `suspend` (`:core` haengt
 * nicht an kotlinx-coroutines), die Health-Connect-API dagegen schon. Die
 * Bruecke ist [runBlocking]: Jeder Lesezugriff blockiert den aufrufenden
 * Thread, bis Health Connect geantwortet hat. Das ist zulaessig, **weil
 * `HealthSyncService` ausschliesslich aus `Dispatchers.IO` heraus benutzt
 * wird** (siehe `AppServices.appScope`) — dort ist Blockieren der
 * vorgesehene Betriebsmodus. Vom Main-Thread darf keine der Methoden
 * aufgerufen werden; [requestPermissions] wuerde dort sogar verklemmen, weil
 * es auf einen Dialog wartet, den derselbe Thread anzeigen muesste.
 *
 * **Fehlersemantik.** `HealthSyncService` faengt Fehler an den richtigen
 * Stellen selbst ab (`readOptional`, `checkAvailability`) und uebersetzt sie in
 * `VitalsSummary.unavailable` bzw. Diagnosezeilen. Dieses Gateway wirft daher
 * *immer* [HealthSyncException] mit einer deutschen, UI-tauglichen Meldung —
 * eine [SecurityException] von Health Connect wird dabei ausdruecklich als
 * fehlende Freigabe formuliert.
 */
class HealthConnectGateway(context: Context) : HealthGateway {

    private val appContext: Context = context.applicationContext

    @Volatile
    private var cachedClient: HealthConnectClient? = null

    @Volatile
    private var diagnostics: HealthWorkoutReadDiagnostics? = null

    // -----------------------------------------------------------------------
    // Verfuegbarkeit und Berechtigungen
    // -----------------------------------------------------------------------

    override fun availability(): HealthAvailability = try {
        when (HealthConnectClient.getSdkStatus(appContext)) {
            HealthConnectClient.SDK_AVAILABLE -> HealthAvailability.VERFUEGBAR
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED ->
                HealthAvailability.UPDATE_NOETIG
            HealthConnectClient.SDK_UNAVAILABLE -> HealthAvailability.NICHT_INSTALLIERT
            else -> HealthAvailability.NICHT_UNTERSTUETZT
        }
    } catch (_: Throwable) {
        // getSdkStatus wirft auf Geraeten ohne Health-Connect-Unterbau
        // (fehlender Provider, eingeschraenkte ROM).
        HealthAvailability.NICHT_UNTERSTUETZT
    }

    override fun hasPermissions(): Boolean = read("Die Berechtigungen") { client ->
        HealthPermissions.hasAllRequired(client)
    }

    /**
     * Zeigt den Health-Connect-Berechtigungsdialog und wartet auf das Ergebnis.
     *
     * Angefragt wird [HealthPermissions.all] — Pflicht- und Zusatzrechte in
     * einem Dialog, wie im Dart-Original. Massgeblich fuer das Ergebnis ist
     * aber allein [HealthPermissions.required]: Wer nur HRV, VO2max oder die
     * Routen ablehnt, gilt trotzdem als verbunden.
     *
     * Blockiert bis zur Antwort der Nutzerin — siehe Klassendoc zum Threading.
     */
    override fun requestPermissions(): Boolean {
        val client = requireClient()
        return runBlocking {
            HealthPermissionHub.request(HealthPermissions.all)
                ?: throw HealthSyncException(
                    "Der Berechtigungsdialog von Health Connect lässt sich nur öffnen, " +
                        "solange Trailscape im Vordergrund läuft.",
                )
            // Nicht das Contract-Ergebnis auswerten, sondern nachfragen: Der
            // Contract meldet nur die in *diesem* Dialog erteilten Rechte,
            // frueher erteilte fehlten sonst.
            try {
                HealthPermissions.hasAllRequired(client)
            } catch (_: Throwable) {
                false
            }
        }
    }

    // -----------------------------------------------------------------------
    // Trainings
    // -----------------------------------------------------------------------

    override val lastWorkoutDiagnostics: HealthWorkoutReadDiagnostics?
        get() = diagnostics

    /**
     * Alle Trainings im Fenster, angereichert um Distanz und Energie.
     *
     * Distanz und Kalorien liegen in Health Connect in eigenen Datensaetzen
     * ([DistanceRecord], [TotalCaloriesBurnedRecord]) und werden je Session
     * ueber `aggregate` zusammengezaehlt — eingegrenzt auf die Quell-App der
     * Session, damit sich nicht die Werte einer parallel laufenden zweiten
     * Tracking-App dazumischen. Schlaegt die Aggregation fehl (fehlende
     * Freigabe), bleibt es bei `null`; `buildRideFromWorkout` rechnet die
     * Distanz dann aus der Route.
     */
    override fun readWorkouts(from: LocalDateTime, to: LocalDateTime): List<HealthWorkout> =
        read("Die Trainings") { client ->
            val records = client.readAllPages(ExerciseSessionRecord::class, from, to)

            val activityTypes = linkedMapOf<String, Int>()
            val workouts = ArrayList<HealthWorkout>(records.size)

            for (record in records) {
                val typeName = exerciseTypeName(record.exerciseType)
                activityTypes[typeName] = (activityTypes[typeName] ?: 0) + 1

                val totals = client.readSessionTotals(record)
                workouts.add(
                    HealthWorkout(
                        id = record.metadata.id,
                        start = record.startTime.toLocal(),
                        end = record.endTime.toLocal(),
                        kind = activityKind(record, typeName),
                        distanceM = totals?.distanceM,
                        energyKcal = totals?.energyKcal,
                        sourceName = record.metadata.dataOrigin.packageName,
                    ),
                )
            }

            diagnostics = HealthWorkoutReadDiagnostics(
                rawPointCount = records.size,
                // Der native Reader liefert genau einen Datensatztyp; das Feld
                // stammt aus der Plugin-Zeit, in der auch andere Werttypen
                // ankommen konnten.
                valueTypeCounts = if (records.isEmpty()) {
                    emptyMap()
                } else {
                    mapOf("ExerciseSessionRecord" to records.size)
                },
                activityTypeCounts = activityTypes.toMap(),
            )

            workouts.sortedBy { it.start }
        }

    /**
     * Die rohen Sessions ohne jede Anreicherung.
     *
     * In `:core` ist das die Rueckfallebene, wenn [readWorkouts] nichts
     * Verwertbares liefert. Gegenueber [readWorkouts] fallen hier genau die
     * Zusatzabfragen weg (Distanz, Energie), die eigene Freigaben brauchen und
     * damit als einzige scheitern koennen — der Fallback bleibt also auch dann
     * benutzbar, wenn READ_DISTANCE oder READ_TOTAL_CALORIES_BURNED fehlen.
     */
    override fun readExerciseSessionsNative(
        from: LocalDateTime,
        to: LocalDateTime,
    ): List<HealthSessionInfo> = read("Die Trainings") { client ->
        client.readAllPages(ExerciseSessionRecord::class, from, to)
            .map { it.toSessionInfo() }
            .sortedBy { it.start }
    }

    /**
     * GPS-Routen im Fenster, nach `ExerciseSessionRecord`-ID gruppiert.
     *
     * Die Route steckt bei connect-client 1.1.0 im Session-Datensatz selbst:
     * `exerciseRouteResult` ist entweder [ExerciseRouteResult.Data] (Route da),
     * `ConsentRequired` (Health Connect verlangt eine ausdrueckliche Freigabe)
     * oder `NoData` (Indoor-Session, keine Route aufgezeichnet). Nur der erste
     * Fall landet in der Map — genau wie im Dart-Original, das leere
     * Standortlisten uebersprang.
     */
    override fun readRoutes(
        from: LocalDateTime,
        to: LocalDateTime,
    ): Map<String, List<HealthRoutePoint>> = read("Die Routen") { client ->
        val routes = linkedMapOf<String, List<HealthRoutePoint>>()
        for (record in client.readAllPages(ExerciseSessionRecord::class, from, to)) {
            val result = record.exerciseRouteResult
            if (result !is ExerciseRouteResult.Data) {
                continue
            }
            val locations = result.exerciseRoute.route
            if (locations.isEmpty()) {
                continue
            }
            routes[record.metadata.id] = locations.map { location ->
                HealthRoutePoint(
                    lat = location.latitude,
                    lon = location.longitude,
                    time = location.time.toLocal(),
                    ele = location.altitude?.inMeters,
                )
            }
        }
        routes
    }

    // -----------------------------------------------------------------------
    // Vitaldaten
    // -----------------------------------------------------------------------

    /**
     * Herzfrequenz-Zeitreihe.
     *
     * [HeartRateRecord] ist ein Serien-Datensatz: ein Record deckt einen
     * Zeitraum ab und traegt viele Einzelmessungen in `samples`. Ohne dieses
     * Aufloesen kaeme je Aufzeichnungsblock nur ein Wert an.
     */
    override fun readHeartRate(
        from: LocalDateTime,
        to: LocalDateTime,
    ): List<HealthHeartRateSample> = read("Die Herzfrequenz") { client ->
        client.readAllPages(HeartRateRecord::class, from, to)
            .flatMap { record -> record.samples }
            .map { sample ->
                HealthHeartRateSample(
                    time = sample.time.toLocal(),
                    bpm = sample.beatsPerMinute.toDouble(),
                )
            }
            .sortedBy { it.time }
    }

    override fun readRestingHeartRate(
        from: LocalDateTime,
        to: LocalDateTime,
    ): List<HealthNumericSample> = read("Der Ruhepuls") { client ->
        client.readAllPages(RestingHeartRateRecord::class, from, to)
            .map { HealthNumericSample(it.time.toLocal(), it.beatsPerMinute.toDouble()) }
            .sortedBy { it.time }
    }

    override fun readSleepSessions(
        from: LocalDateTime,
        to: LocalDateTime,
    ): List<HealthSleepSession> = read("Der Schlaf") { client ->
        // Bewusst die ganze Sitzung und nicht die einzelnen Phasen (`stages`):
        // `HealthSyncService.readVitals` summiert Schlafdauer je Aufwachtag,
        // und genau das erwartet auch das Dart-Original (SLEEP_SESSION).
        client.readAllPages(SleepSessionRecord::class, from, to)
            .map { HealthSleepSession(it.startTime.toLocal(), it.endTime.toLocal()) }
            .sortedBy { it.start }
    }

    override fun readVo2Max(
        from: LocalDateTime,
        to: LocalDateTime,
    ): List<HealthNumericSample> = read("Der VO2max-Wert") { client ->
        client.readAllPages(Vo2MaxRecord::class, from, to)
            .map {
                HealthNumericSample(it.time.toLocal(), it.vo2MillilitersPerMinuteKilogram)
            }
            .sortedBy { it.time }
    }

    override fun readHrv(
        from: LocalDateTime,
        to: LocalDateTime,
    ): List<HealthNumericSample> = read("Die Herzratenvariabilität") { client ->
        client.readAllPages(HeartRateVariabilityRmssdRecord::class, from, to)
            .map { HealthNumericSample(it.time.toLocal(), it.heartRateVariabilityMillis) }
            .sortedBy { it.time }
    }

    // -----------------------------------------------------------------------
    // Innereien
    // -----------------------------------------------------------------------

    /**
     * Der Client, oder [HealthSyncException] mit der passenden deutschen
     * Meldung. Wird gecached: `getOrCreate` baut eine Service-Verbindung auf,
     * die nicht pro Lesezugriff neu entstehen soll.
     */
    private fun requireClient(): HealthConnectClient {
        cachedClient?.let { return it }

        when (availability()) {
            HealthAvailability.VERFUEGBAR -> Unit
            HealthAvailability.NICHT_INSTALLIERT -> throw HealthSyncException(
                "Health Connect ist nicht installiert.",
            )
            HealthAvailability.UPDATE_NOETIG -> throw HealthSyncException(
                "Health Connect muss aktualisiert werden.",
            )
            HealthAvailability.NICHT_UNTERSTUETZT -> throw HealthSyncException(
                "Health Connect wird auf diesem Gerät nicht unterstützt.",
            )
        }

        val created = try {
            HealthConnectClient.getOrCreate(appContext)
        } catch (error: Throwable) {
            throw HealthSyncException(
                "Die Verbindung zu Health Connect konnte nicht aufgebaut werden: " +
                    describe(error),
            )
        }
        cachedClient = created
        return created
    }

    /**
     * Gemeinsamer Rand aller Lesezugriffe: Client besorgen, [block] blockierend
     * ausfuehren, Fehler in die `:core`-Semantik uebersetzen.
     *
     * [subject] ist der deutsche Betreff der Fehlermeldung („Die Trainings",
     * „Der Ruhepuls", ...) und wird zu „<Betreff> konnten/konnte nicht ..."
     * ergaenzt — bewusst schlicht gehalten: Die Meldung landet ueber
     * `HealthSyncReport.debugLines` bzw. `HealthSyncException` in der UI.
     */
    private fun <T> read(subject: String, block: suspend (HealthConnectClient) -> T): T {
        val client = requireClient()
        return try {
            runBlocking { block(client) }
        } catch (error: SecurityException) {
            throw HealthSyncException(
                "$subject: Health Connect verweigert den Zugriff. Bitte die Freigabe " +
                    "in den Health-Connect-Einstellungen erteilen.",
            )
        } catch (error: HealthSyncException) {
            throw error
        } catch (error: Throwable) {
            throw HealthSyncException(
                "$subject konnte nicht aus Health Connect gelesen werden: ${describe(error)}",
            )
        }
    }

    /**
     * Liest alle Seiten eines Datensatztyps im Fenster.
     *
     * Health Connect antwortet seitenweise (Vorgabe 1000 Datensaetze). Ohne die
     * `pageToken`-Schleife fehlten aeltere Eintraege stillschweigend — derselbe
     * Fehler, den `HealthExtraChannel` schon vermied.
     */
    private suspend fun <T : Record> HealthConnectClient.readAllPages(
        type: KClass<T>,
        from: LocalDateTime,
        to: LocalDateTime,
    ): List<T> {
        val start = from.toInstant()
        val end = to.toInstant()
        // TimeRangeFilter.between besteht auf start < end.
        if (!start.isBefore(end)) {
            return emptyList()
        }
        val filter = TimeRangeFilter.between(start, end)

        val all = ArrayList<T>()
        var pageToken: String? = null
        do {
            val response = readRecords(readRequest(type, filter, pageToken))
            all.addAll(response.records)
            pageToken = response.pageToken
        } while (pageToken != null)
        return all
    }

    /**
     * Baut die Leseanfrage.
     *
     * Alle sechs Parameter stehen ausdruecklich und **unbenannt** da, obwohl
     * vier davon Vorgabewerte haben. Grund ist eine Eigenheit von
     * connect-client 1.1.0: Neben diesem Konstruktor gibt es einen zweiten mit
     * zusaetzlichem `deduplicateStrategy` — der ist `@RestrictTo` und damit
     * nichts fuer App-Code. Mit benannten Argumenten waere der Aufruf zwischen
     * beiden mehrdeutig; sechs Positionsargumente treffen dagegen eindeutig
     * den oeffentlichen Konstruktor, weil er als einziger ohne Vorgabewert
     * auskommt.
     */
    private fun <T : Record> readRequest(
        type: KClass<T>,
        filter: TimeRangeFilter,
        pageToken: String?,
    ): ReadRecordsRequest<T> = ReadRecordsRequest(
        type,
        filter,
        emptySet(),
        true,
        PAGE_SIZE,
        pageToken,
    )

    /** Distanz (m) und Energie (kcal) einer Session, `null` wenn nicht lesbar. */
    private suspend fun HealthConnectClient.readSessionTotals(
        record: ExerciseSessionRecord,
    ): SessionTotals? {
        if (!record.startTime.isBefore(record.endTime)) {
            return null
        }
        val result: AggregationResult = try {
            aggregate(
                AggregateRequest(
                    metrics = setOf(
                        DistanceRecord.DISTANCE_TOTAL,
                        TotalCaloriesBurnedRecord.ENERGY_TOTAL,
                    ),
                    timeRangeFilter = TimeRangeFilter.between(
                        record.startTime,
                        record.endTime,
                    ),
                    dataOriginFilter = setOf(
                        DataOrigin(record.metadata.dataOrigin.packageName),
                    ),
                ),
            )
        } catch (_: Throwable) {
            // Fehlende Freigabe fuer Distanz/Kalorien darf den Import nicht
            // kippen — das war der Konstruktionsfehler des `health`-Plugins.
            return null
        }

        val distanceM = result[DistanceRecord.DISTANCE_TOTAL]?.inMeters
        val energyKcal = result[TotalCaloriesBurnedRecord.ENERGY_TOTAL]
            ?.inKilocalories
            ?.roundToInt()
        if (distanceM == null && energyKcal == null) {
            return null
        }
        return SessionTotals(distanceM = distanceM, energyKcal = energyKcal)
    }

    private data class SessionTotals(val distanceM: Double?, val energyKcal: Int?)

    private fun ExerciseSessionRecord.toSessionInfo(): HealthSessionInfo = HealthSessionInfo(
        uid = metadata.id,
        start = startTime.toLocal(),
        end = endTime.toLocal(),
        typeCode = exerciseType,
        typeName = exerciseTypeName(exerciseType),
        title = title,
        source = metadata.dataOrigin.packageName,
        hasRoute = exerciseRouteResult is ExerciseRouteResult.Data,
    )

    /**
     * Rad-Art einer Session.
     *
     * Die Zuordnung liegt in `:core` ([mapNativeSessionKind]) — inklusive der
     * Titel-Heuristik fuer Quell-Apps, die Radfahrten als „anderes Training"
     * mit sprechendem Titel schreiben. Hier wird sie nur um den Rest-Fall
     * ergaenzt, den `:core` bewusst offen laesst.
     */
    private fun activityKind(
        record: ExerciseSessionRecord,
        typeName: String,
    ): HealthActivityKind = mapNativeSessionKind(
        HealthSessionInfo(
            uid = record.metadata.id,
            start = record.startTime.toLocal(),
            end = record.endTime.toLocal(),
            typeCode = record.exerciseType,
            typeName = typeName,
            title = record.title,
            source = record.metadata.dataOrigin.packageName,
        ),
    ) ?: HealthActivityKind.SONSTIGES

    /**
     * Entspricht Darts `DateTime.fromMillisecondsSinceEpoch(ms)` und damit dem
     * `dartLocalOf` aus `:core` (das dort `internal` ist): absoluter Zeitpunkt,
     * gelesen in der Zeitzone des Geraets.
     */
    private fun Instant.toLocal(): LocalDateTime =
        LocalDateTime.ofInstant(this, ZoneId.systemDefault())

    private fun LocalDateTime.toInstant(): Instant =
        atZone(ZoneId.systemDefault()).toInstant()

    private fun describe(error: Throwable): String =
        error.message?.takeIf { it.isNotBlank() } ?: error.toString()

    private companion object {
        /** Vorgabe von Health Connect; ausdruecklich gesetzt, siehe `readRequest`. */
        const val PAGE_SIZE = 1000

        /**
         * Name der androidx-Konstante zu [type]; unbekannte Typen kommen als
         * `TYPE_<int>`.
         *
         * `mapNativeSessionKind` in `:core` entscheidet anhand genau dieser
         * Namen (`EXERCISE_TYPE_BIKING`, `EXERCISE_TYPE_BIKING_STATIONARY`), die
         * uebrigen sind reine Diagnose.
         */
        fun exerciseTypeName(type: Int): String = when (type) {
            ExerciseSessionRecord.EXERCISE_TYPE_BIKING -> "EXERCISE_TYPE_BIKING"
            ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY ->
                "EXERCISE_TYPE_BIKING_STATIONARY"
            ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT -> "EXERCISE_TYPE_OTHER_WORKOUT"
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING -> "EXERCISE_TYPE_RUNNING"
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL ->
                "EXERCISE_TYPE_RUNNING_TREADMILL"
            ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> "EXERCISE_TYPE_WALKING"
            ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> "EXERCISE_TYPE_HIKING"
            ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL -> "EXERCISE_TYPE_ELLIPTICAL"
            ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE ->
                "EXERCISE_TYPE_ROWING_MACHINE"
            ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING ->
                "EXERCISE_TYPE_STRENGTH_TRAINING"
            ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER ->
                "EXERCISE_TYPE_SWIMMING_OPEN_WATER"
            ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL -> "EXERCISE_TYPE_SWIMMING_POOL"
            else -> "TYPE_$type"
        }
    }
}
