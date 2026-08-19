package de.trailscape.core

import kotlinx.serialization.json.JsonArray
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Portierung von `test/health_sync_test.dart`, soweit die Faelle
 * plattformneutral sind (86 der 96 Dart-Testfaelle).
 *
 * Nicht portiert, weil sie ausschliesslich an Flutter-Plugin bzw.
 * Platform-Channel haengen (Phase 3):
 *
 *  * Gruppe `angefragte Datentypen` (2 Faelle) — prueft `healthReadTypes` /
 *    `healthOptionalReadTypes` gegen `hc.HealthDataType`,
 *  * Gruppe `VO2max über den Platform-Channel` (6 Faelle) — `MethodChannel`
 *    plus `HealthPluginGateway`,
 *  * Gruppe `readExerciseSessionsNative über den Platform-Channel` (2 Faelle)
 *    — dito.
 */
class HealthSyncTest {

    // -----------------------------------------------------------------------
    // Attrappen und Helfer (entsprechen den Helfern am Kopf der Dart-Datei)
    // -----------------------------------------------------------------------

    /**
     * Attrappe der Health-Plattform. Jeder Lesevorgang kann einzeln auf Fehler
     * gestellt werden, um Teilausfaelle zu simulieren.
     */
    private class FakeHealthGateway(
        var availabilityValue: HealthAvailability = HealthAvailability.VERFUEGBAR,
        var permissionsGranted: Boolean = true,
        var grantOnRequest: Boolean = true,
        var workouts: List<HealthWorkout> = emptyList(),
        var routes: Map<String, List<HealthRoutePoint>> = emptyMap(),
        var heartRate: List<HealthHeartRateSample> = emptyList(),
        var restingHeartRate: List<HealthNumericSample> = emptyList(),
        var sleep: List<HealthSleepSession> = emptyList(),
        var vo2max: List<HealthNumericSample> = emptyList(),
        var hrv: List<HealthNumericSample> = emptyList(),
        var failWorkouts: Boolean = false,
        var failRoutes: Boolean = false,
        var failHeartRate: Boolean = false,
        var failRestingHeartRate: Boolean = false,
        var failSleep: Boolean = false,
        var failVo2max: Boolean = true,
        var failHrv: Boolean = false,
        var nativeSessions: List<HealthSessionInfo> = emptyList(),
        var failNativeSessions: Boolean = false,
        var workoutDiagnostics: HealthWorkoutReadDiagnostics? = null,
    ) : HealthGateway {
        var requestCount = 0
        var nativeSessionCalls = 0
        var lastWorkoutFrom: LocalDateTime? = null
        var lastWorkoutTo: LocalDateTime? = null
        val heartRateWindows = mutableListOf<Pair<LocalDateTime, LocalDateTime>>()

        override val lastWorkoutDiagnostics: HealthWorkoutReadDiagnostics?
            get() = workoutDiagnostics

        override fun availability(): HealthAvailability = availabilityValue

        override fun hasPermissions(): Boolean = permissionsGranted

        override fun requestPermissions(): Boolean {
            requestCount++
            permissionsGranted = grantOnRequest
            return grantOnRequest
        }

        override fun readWorkouts(from: LocalDateTime, to: LocalDateTime): List<HealthWorkout> {
            lastWorkoutFrom = from
            lastWorkoutTo = to
            if (failWorkouts) throw IllegalStateException("workouts kaputt")
            return workouts
        }

        override fun readExerciseSessionsNative(
            from: LocalDateTime,
            to: LocalDateTime,
        ): List<HealthSessionInfo> {
            nativeSessionCalls++
            if (failNativeSessions) throw IllegalStateException("kein Channel")
            return nativeSessions
        }

        override fun readRoutes(
            from: LocalDateTime,
            to: LocalDateTime,
        ): Map<String, List<HealthRoutePoint>> {
            if (failRoutes) throw IllegalStateException("routen kaputt")
            return routes
        }

        override fun readHeartRate(
            from: LocalDateTime,
            to: LocalDateTime,
        ): List<HealthHeartRateSample> {
            heartRateWindows.add(from to to)
            if (failHeartRate) throw IllegalStateException("hf kaputt")
            return heartRate
        }

        override fun readRestingHeartRate(
            from: LocalDateTime,
            to: LocalDateTime,
        ): List<HealthNumericSample> {
            if (failRestingHeartRate) throw IllegalStateException("ruhepuls kaputt")
            return restingHeartRate
        }

        override fun readSleepSessions(
            from: LocalDateTime,
            to: LocalDateTime,
        ): List<HealthSleepSession> {
            if (failSleep) throw IllegalStateException("schlaf kaputt")
            return sleep
        }

        override fun readVo2Max(
            from: LocalDateTime,
            to: LocalDateTime,
        ): List<HealthNumericSample> {
            if (failVo2max) throw UnsupportedOperationException("VO2max nicht unterstützt")
            return vo2max
        }

        override fun readHrv(from: LocalDateTime, to: LocalDateTime): List<HealthNumericSample> {
            if (failHrv) throw IllegalStateException("hrv kaputt")
            return hrv
        }
    }

    private fun at(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): LocalDateTime =
        LocalDateTime.of(year, month, day, hour, minute)

    private fun minutes(n: Long): Long = n * 60_000
    private fun hours(n: Long): Long = n * 3_600_000
    private fun days(n: Long): Long = n * 86_400_000
    private fun seconds(n: Long): Long = n * 1000

    private fun LocalDateTime.plusMs(ms: Long): LocalDateTime = dartPlusMillis(this, ms)

    private fun cycling(
        id: String = "w1",
        start: LocalDateTime,
        end: LocalDateTime,
        distanceM: Double? = 20000.0,
        kind: HealthActivityKind = HealthActivityKind.RADFAHREN,
    ): HealthWorkout = HealthWorkout(
        id = id,
        start = start,
        end = end,
        kind = kind,
        distanceM = distanceM,
        energyKcal = 500,
        sourceName = "com.sec.android.app.shealth",
    )

    private fun ride(
        id: String,
        start: LocalDateTime,
        durationMs: Long,
        distanceKm: Double = 20.0,
        avgHrBpm: Int? = null,
        maxHrBpm: Int? = null,
    ): Ride = Ride(
        id = id,
        name = "Bestehende Tour",
        createdAt = dartEpochMs(start),
        points = emptyList(),
        stats = RideStats(
            distanceKm = distanceKm,
            durationS = (durationMs / 1000).toInt(),
            ascentM = 0.0,
            descentM = 0.0,
            avgHrBpm = avgHrBpm,
            maxHrBpm = maxHrBpm,
        ),
    )

    /** Bestehende Tour mit Trackpunkten (wie vom Handy aufgezeichnet). */
    private fun rideWithPoints(
        id: String,
        start: LocalDateTime,
        pointCount: Int = 3,
        stepMs: Long = 600_000,
        avgHrBpm: Int? = null,
        pointHr: Int? = null,
    ): Ride = Ride(
        id = id,
        name = "Handy-Tour",
        createdAt = dartEpochMs(start),
        points = (0 until pointCount).map { i ->
            TrackPoint(
                lat = 48 + i * 0.001,
                lon = 11.0,
                ele = 500 + i * 5.0,
                time = dartEpochMs(start.plusMs(stepMs * i)),
                hr = pointHr,
            )
        },
        stats = RideStats(
            distanceKm = 30.0,
            durationS = (stepMs * (pointCount - 1) / 1000).toInt(),
            movingTimeS = 1500,
            avgSpeedKmh = 25.0,
            ascentM = 120.0,
            descentM = 110.0,
            avgHrBpm = avgHrBpm,
        ),
    )

    private fun session(
        uid: String = "s1",
        start: LocalDateTime,
        durationMs: Long = 3_600_000,
        typeName: String = "EXERCISE_TYPE_BIKING",
        typeCode: Int = 8,
        title: String? = null,
        source: String? = "com.sec.android.app.shealth",
        hasRoute: Boolean = false,
    ): HealthSessionInfo = HealthSessionInfo(
        uid = uid,
        start = start,
        end = start.plusMs(durationMs),
        typeCode = typeCode,
        typeName = typeName,
        title = title,
        source = source,
        hasRoute = hasRoute,
    )

    private fun serviceOf(
        gateway: FakeHealthGateway,
        now: LocalDateTime,
        store: HealthSyncStore = InMemoryHealthSyncStore(),
    ): HealthSyncService = HealthSyncService(gateway = gateway, store = store, now = { now })

    // -----------------------------------------------------------------------
    // group('overlapRatio')
    // -----------------------------------------------------------------------

    @Test
    fun `overlapRatio - kein Ueberlappen ergibt 0`() {
        assertEquals(
            0.0,
            overlapRatio(at(2026, 8, 1, 10), at(2026, 8, 1, 11), at(2026, 8, 1, 12), at(2026, 8, 1, 13)),
        )
    }

    @Test
    fun `overlapRatio - vollstaendige Ueberdeckung ergibt 1`() {
        assertEquals(
            1.0,
            overlapRatio(at(2026, 8, 1, 10), at(2026, 8, 1, 11), at(2026, 8, 1, 9), at(2026, 8, 1, 12)),
        )
    }

    @Test
    fun `overlapRatio - halbe Ueberdeckung ergibt exakt 0,5`() {
        assertEquals(
            0.5,
            overlapRatio(at(2026, 8, 1, 10), at(2026, 8, 1, 12), at(2026, 8, 1, 11), at(2026, 8, 1, 14)),
        )
    }

    @Test
    fun `overlapRatio - direkt aneinandergrenzende Zeitraeume ueberlappen nicht`() {
        assertEquals(
            0.0,
            overlapRatio(at(2026, 8, 1, 10), at(2026, 8, 1, 11), at(2026, 8, 1, 11), at(2026, 8, 1, 12)),
        )
    }

    @Test
    fun `overlapRatio - punktfoermiger Zeitraum zaehlt nur bei Treffer`() {
        val punkt = at(2026, 8, 1, 10, 30)
        assertEquals(1.0, overlapRatio(punkt, punkt, at(2026, 8, 1, 10), at(2026, 8, 1, 11)))
        assertEquals(0.0, overlapRatio(punkt, punkt, at(2026, 8, 1, 11), at(2026, 8, 1, 12)))
    }

    // -----------------------------------------------------------------------
    // group('rideTimeRange')
    // -----------------------------------------------------------------------

    @Test
    fun `rideTimeRange - nutzt Trackpunkt-Zeitstempel wenn vorhanden`() {
        val start = at(2026, 8, 1, 10)
        val r = Ride(
            id = "a",
            name = "a",
            createdAt = dartEpochMs(start),
            points = listOf(
                TrackPoint(lat = 1.0, lon = 2.0, time = dartEpochMs(start)),
                TrackPoint(lat = 1.0, lon = 2.0, time = dartEpochMs(start.plusMs(hours(2)))),
            ),
            stats = RideStats(distanceKm = 1.0, ascentM = 0.0, descentM = 0.0),
        )

        val range = rideTimeRange(r)
        assertEquals(start, range.start)
        assertEquals(start.plusMs(hours(2)), range.end)
    }

    @Test
    fun `rideTimeRange - faellt auf createdAt plus Dauer zurueck`() {
        val start = at(2026, 8, 1, 10)
        val range = rideTimeRange(ride(id = "a", start = start, durationMs = minutes(90)))
        assertEquals(start, range.start)
        assertEquals(start.plusMs(minutes(90)), range.end)
    }

    @Test
    fun `rideTimeRange - ohne Dauer und ohne Punkte ist der Zeitraum punktfoermig`() {
        val start = at(2026, 8, 1, 10)
        val r = Ride(
            id = "a",
            name = "a",
            createdAt = dartEpochMs(start),
            points = emptyList(),
            stats = RideStats(distanceKm = 0.0, ascentM = 0.0, descentM = 0.0),
        )
        val range = rideTimeRange(r)
        assertEquals(start, range.start)
        assertEquals(start, range.end)
    }

    // -----------------------------------------------------------------------
    // group('buildRideFromWorkout')
    // -----------------------------------------------------------------------

    @Test
    fun `buildRideFromWorkout - bildet Route, Hoehen und Herzfrequenz ab`() {
        val start = at(2026, 8, 1, 10)
        val workout = cycling(
            id = "abc-123",
            start = start,
            end = start.plusMs(hours(1)),
            distanceM = 25000.0,
        )

        val r = buildRideFromWorkout(
            workout,
            route = listOf(
                HealthRoutePoint(lat = 48.0, lon = 11.0, ele = 500.0, time = start),
                HealthRoutePoint(
                    lat = 48.01,
                    lon = 11.0,
                    ele = 520.0,
                    time = start.plusMs(minutes(30)),
                ),
                HealthRoutePoint(lat = 48.02, lon = 11.0, ele = 540.0, time = start.plusMs(hours(1))),
            ),
            heartRate = listOf(
                HealthHeartRateSample(time = start, bpm = 120.0),
                HealthHeartRateSample(time = start.plusMs(minutes(30)), bpm = 150.0),
                HealthHeartRateSample(time = start.plusMs(hours(1)), bpm = 180.0),
            ),
        )

        assertEquals("hc-abc-123", r.id)
        assertEquals("Tour 01.08.2026 (Watch)", r.name)
        assertEquals(dartEpochMs(start), r.createdAt)
        assertEquals(3, r.points.size)

        // Distanz kommt vom Geraet, nicht aus der Route.
        assertEquals(25.0, r.stats.distanceKm, 0.001)
        assertEquals(3600, r.stats.durationS)
        assertEquals(40.0, r.stats.ascentM, 0.001)
        assertEquals(150, r.stats.avgHrBpm)
        assertEquals(180, r.stats.maxHrBpm)

        // Jeder Trackpunkt bekommt die zeitlich passende Herzfrequenz.
        assertEquals(listOf(120, 150, 180), r.points.map { it.hr })
        assertEquals(500.0, r.points.first().ele)
        assertEquals(dartEpochMs(start), r.points.first().time)
    }

    @Test
    fun `buildRideFromWorkout - importiert ohne Route nur Distanz, Dauer und HF`() {
        val start = at(2026, 8, 2, 9)
        val r = buildRideFromWorkout(
            cycling(
                id = "ohne-route",
                start = start,
                end = start.plusMs(minutes(90)),
                distanceM = 42000.0,
            ),
            heartRate = listOf(
                HealthHeartRateSample(time = start.plusMs(minutes(10)), bpm = 130.0),
                HealthHeartRateSample(time = start.plusMs(minutes(20)), bpm = 140.0),
            ),
        )

        assertTrue(r.points.isEmpty())
        assertEquals(42.0, r.stats.distanceKm, 0.001)
        assertEquals(5400, r.stats.durationS)
        assertNull(r.stats.movingTimeS)
        assertEquals(0.0, r.stats.ascentM)
        assertEquals(0.0, r.stats.descentM)
        assertEquals(28.0, r.stats.avgSpeedKmh!!, 0.001)
        assertEquals(135, r.stats.avgHrBpm)
        assertEquals(140, r.stats.maxHrBpm)
    }

    @Test
    fun `buildRideFromWorkout - berechnet die Distanz aus der Route ohne Geraetewert`() {
        val start = at(2026, 8, 3, 8)
        val r = buildRideFromWorkout(
            cycling(
                id = "keine-distanz",
                start = start,
                end = start.plusMs(minutes(10)),
                distanceM = null,
            ),
            route = listOf(
                HealthRoutePoint(lat = 0.0, lon = 0.0, time = start),
                HealthRoutePoint(lat = 0.0, lon = 0.1, time = start.plusMs(minutes(10))),
            ),
        )

        assertTrue(r.stats.distanceKm > 10)
        assertTrue(r.stats.distanceKm < 12)
    }

    @Test
    fun `buildRideFromWorkout - ignoriert HF ausserhalb des Workout-Zeitraums`() {
        val start = at(2026, 8, 4, 7)
        val r = buildRideFromWorkout(
            cycling(id = "hf-fenster", start = start, end = start.plusMs(minutes(30))),
            heartRate = listOf(
                HealthHeartRateSample(time = start.plusMs(-hours(2)), bpm = 60.0),
                HealthHeartRateSample(time = start.plusMs(minutes(10)), bpm = 150.0),
                HealthHeartRateSample(time = start.plusMs(hours(5)), bpm = 200.0),
            ),
        )

        assertEquals(150, r.stats.avgHrBpm)
        assertEquals(150, r.stats.maxHrBpm)
    }

    @Test
    fun `buildRideFromWorkout - ohne Herzfrequenz bleiben die HF-Felder leer`() {
        val start = at(2026, 8, 5, 7)
        val r = buildRideFromWorkout(
            cycling(id = "ohne-hf", start = start, end = start.plusMs(minutes(30))),
        )
        assertNull(r.stats.avgHrBpm)
        assertNull(r.stats.maxHrBpm)
        // Rueckwaertskompatibel: ohne HF bleibt das JSON unveraendert.
        assertFalse(r.stats.toJson().containsKey("avgHrBpm"))
        assertTrue((r.toJson()["points"] as JsonArray).isEmpty())
    }

    @Test
    fun `buildRideFromWorkout - markiert Indoor-Fahrten im Namen`() {
        val start = at(2026, 8, 6, 18)
        val r = buildRideFromWorkout(
            cycling(
                id = "rolle",
                start = start,
                end = start.plusMs(minutes(45)),
                kind = HealthActivityKind.RADFAHREN_INDOOR,
            ),
        )
        assertEquals("Tour 06.08.2026 (Watch) (Indoor)", r.name)
    }

    @Test
    fun `buildRideFromWorkout - erzeugt eine dateisystemtaugliche ID`() {
        assertEquals("hc-a-b-c-d", healthRideId("a/b c:d"))
    }

    // -----------------------------------------------------------------------
    // group('importNewRides')
    // -----------------------------------------------------------------------

    @Test
    fun `importNewRides - importiert nur Rad-Workouts`() {
        val start = at(2026, 8, 1, 10)
        val gateway = FakeHealthGateway(
            workouts = listOf(
                cycling(id = "rad", start = start, end = start.plusMs(hours(1))),
                HealthWorkout(
                    id = "lauf",
                    start = start.plusMs(days(1)),
                    end = start.plusMs(days(1) + hours(1)),
                    kind = HealthActivityKind.SONSTIGES,
                    distanceM = 10000.0,
                ),
            ),
        )
        val rides = serviceOf(gateway, at(2026, 8, 10)).importNewRides(existing = emptyList())
        assertEquals(1, rides.size)
        assertEquals("hc-rad", rides.single().id)
    }

    @Test
    fun `importNewRides - ueberspringt Sessions mit mehr als 50 Prozent Ueberlappung`() {
        val start = at(2026, 8, 1, 10)
        val gateway = FakeHealthGateway(
            workouts = listOf(cycling(id = "doppelt", start = start, end = start.plusMs(hours(2)))),
        )

        // Bestehende Tour deckt 90 Minuten von 120 ab -> 75 %.
        val rides = serviceOf(gateway, at(2026, 8, 10)).importNewRides(
            existing = listOf(ride(id = "lokal", start = start, durationMs = minutes(90))),
        )

        assertTrue(rides.isEmpty())
    }

    @Test
    fun `importNewRides - importiert bei exakt 50 Prozent Ueberlappung`() {
        val start = at(2026, 8, 1, 10)
        val gateway = FakeHealthGateway(
            workouts = listOf(cycling(id = "grenzfall", start = start, end = start.plusMs(hours(2)))),
        )

        val rides = serviceOf(gateway, at(2026, 8, 10)).importNewRides(
            existing = listOf(
                ride(id = "lokal", start = start.plusMs(hours(1)), durationMs = hours(3)),
            ),
        )

        assertEquals(1, rides.size)
        assertEquals("hc-grenzfall", rides.single().id)
    }

    @Test
    fun `importNewRides - importiert bei geringer Ueberlappung`() {
        val start = at(2026, 8, 1, 10)
        val gateway = FakeHealthGateway(
            workouts = listOf(cycling(id = "knapp", start = start, end = start.plusMs(hours(2)))),
        )

        val rides = serviceOf(gateway, at(2026, 8, 10)).importNewRides(
            existing = listOf(
                // Nur die letzten 15 min der Session -> 12,5 %.
                ride(id = "lokal", start = start.plusMs(minutes(105)), durationMs = hours(2)),
            ),
        )

        assertEquals(1, rides.size)
    }

    @Test
    fun `importNewRides - ueberspringt bereits importierte Sessions anhand der ID`() {
        val start = at(2026, 8, 1, 10)
        val gateway = FakeHealthGateway(
            workouts = listOf(cycling(id = "schon-da", start = start, end = start.plusMs(hours(1)))),
        )

        // Zeitlich verschobene, aber identisch benannte Tour: nur die ID greift.
        val rides = serviceOf(gateway, at(2026, 8, 10)).importNewRides(
            existing = listOf(
                ride(id = "hc-schon-da", start = at(2026, 7, 1, 10), durationMs = hours(1)),
            ),
        )

        assertTrue(rides.isEmpty())
    }

    @Test
    fun `importNewRides - entdoppelt auch innerhalb eines Laufs`() {
        val start = at(2026, 8, 1, 10)
        val gateway = FakeHealthGateway(
            workouts = listOf(
                cycling(id = "a", start = start, end = start.plusMs(hours(2))),
                // Nahezu identische Session einer zweiten Quell-App.
                cycling(id = "b", start = start.plusMs(minutes(2)), end = start.plusMs(hours(2))),
            ),
        )

        val rides = serviceOf(gateway, at(2026, 8, 10)).importNewRides(existing = emptyList())
        assertEquals(1, rides.size)
        assertEquals("hc-a", rides.single().id)
    }

    @Test
    fun `importNewRides - eine bestehende Tour ohne Dauer blockiert nichts`() {
        val start = at(2026, 8, 1, 10)
        val gateway = FakeHealthGateway(
            workouts = listOf(cycling(id = "neu", start = start, end = start.plusMs(hours(2)))),
        )

        val rides = serviceOf(gateway, at(2026, 8, 10)).importNewRides(
            existing = listOf(
                Ride(
                    id = "punkt",
                    name = "Punkt",
                    createdAt = dartEpochMs(start),
                    points = emptyList(),
                    stats = RideStats(distanceKm = 0.0, ascentM = 0.0, descentM = 0.0),
                ),
            ),
        )

        assertEquals(1, rides.size)
    }

    @Test
    fun `importNewRides - reicht die Route durch wenn sie verfuegbar ist`() {
        val start = at(2026, 8, 1, 10)
        val gateway = FakeHealthGateway(
            workouts = listOf(cycling(id = "mit-route", start = start, end = start.plusMs(hours(1)))),
            routes = mapOf(
                "mit-route" to listOf(
                    HealthRoutePoint(lat = 48.0, lon = 11.0, time = start),
                    HealthRoutePoint(lat = 48.01, lon = 11.0, time = start.plusMs(minutes(30))),
                ),
            ),
        )

        val rides = serviceOf(gateway, at(2026, 8, 10)).importNewRides(existing = emptyList())
        assertEquals(2, rides.single().points.size)
    }

    @Test
    fun `importNewRides - importiert ohne Route weiter wenn der Routen-Abruf scheitert`() {
        val start = at(2026, 8, 1, 10)
        val gateway = FakeHealthGateway(
            workouts = listOf(cycling(id = "route-kaputt", start = start, end = start.plusMs(hours(1)))),
            failRoutes = true,
            failHeartRate = true,
        )

        val rides = serviceOf(gateway, at(2026, 8, 10)).importNewRides(existing = emptyList())
        assertEquals(1, rides.size)
        assertTrue(rides.single().points.isEmpty())
        assertEquals(20.0, rides.single().stats.distanceKm, 0.001)
    }

    @Test
    fun `importNewRides - liest die HF je Workout, nicht ueber das ganze Fenster`() {
        val start = at(2026, 8, 1, 10)
        val zweite = at(2026, 8, 5, 10)
        val gateway = FakeHealthGateway(
            workouts = listOf(
                cycling(id = "a", start = start, end = start.plusMs(hours(1))),
                cycling(id = "b", start = zweite, end = zweite.plusMs(hours(2))),
            ),
            heartRate = listOf(HealthHeartRateSample(time = start, bpm = 140.0)),
        )

        val rides = serviceOf(gateway, at(2026, 8, 10)).importNewRides(existing = emptyList())

        assertEquals(2, rides.size)
        assertEquals(2, gateway.heartRateWindows.size)
        assertEquals(start, gateway.heartRateWindows.first().first)
        assertEquals(start.plusMs(hours(1)), gateway.heartRateWindows.first().second)
        assertEquals(zweite, gateway.heartRateWindows.last().first)
        // Nur die Messung im ersten Workout-Fenster wird zugeordnet.
        assertEquals(140, rides.first().stats.avgHrBpm)
        assertNull(rides.last().stats.avgHrBpm)
    }

    @Test
    fun `importNewRides - nutzt den gespeicherten Zeitstempel als Startpunkt`() {
        val letzterImport = at(2026, 8, 5)
        val store = InMemoryHealthSyncStore(dartEpochMs(letzterImport))

        val gateway = FakeHealthGateway()
        val now = at(2026, 8, 10, 12)
        serviceOf(gateway, now, store).importNewRides(existing = emptyList())

        // Um den Puffer zurueckgesetzt, damit spaet gespiegelte Watch-Daten
        // noch ins Fenster fallen (siehe healthImportWindowStart).
        assertEquals(letzterImport.plusMs(-healthSyncImportBackfillMs), gateway.lastWorkoutFrom)
        assertEquals(now, gateway.lastWorkoutTo)
    }

    /**
     * Der Fall aus dem Feld: Samsung Health spiegelt die Daten der Uhr erst
     * Stunden nach der Fahrt nach Health Connect. Gefiltert wird nach der
     * *Startzeit* des Workouts — ohne Ueberlappung lag die Session fuer immer
     * hinter dem Fensteranfang und wurde nie gefunden.
     */
    @Test
    fun `importNewRides - findet eine spaet gespiegelte Session vom Vortag`() {
        val tourStart = at(2026, 8, 9, 10)
        val letzterImport = at(2026, 8, 9, 12, 30)
        val store = InMemoryHealthSyncStore(dartEpochMs(letzterImport))
        val gateway = FakeHealthGateway(
            workouts = listOf(
                cycling(id = "spaet", start = tourStart, end = tourStart.plusMs(hours(2))),
            ),
        )

        val rides = serviceOf(gateway, at(2026, 8, 9, 14), store)
            .importNewRides(existing = emptyList())

        assertEquals(1, rides.size)
        assertEquals(healthRideId("spaet"), rides.first().id)
    }

    @Test
    fun `importNewRides - der Puffer fuehrt nicht zu Doppelimporten`() {
        val tourStart = at(2026, 8, 9, 10)
        val gateway = FakeHealthGateway(
            workouts = listOf(
                cycling(id = "spaet", start = tourStart, end = tourStart.plusMs(hours(2))),
            ),
        )
        val store = InMemoryHealthSyncStore()
        val service = serviceOf(gateway, at(2026, 8, 9, 14), store)

        val ersteRunde = service.importNewRides(existing = emptyList())
        assertEquals(1, ersteRunde.size)

        // Zweiter Lauf: Dieselbe Session liegt wegen des Puffers erneut im
        // Fenster — die ID-Pruefung faengt sie ab.
        val bericht = serviceOf(gateway, at(2026, 8, 9, 15), store)
            .importWithReport(existing = ersteRunde)

        assertTrue(bericht.imported.isEmpty())
        assertTrue(bericht.mergedRides.isEmpty())
        assertEquals(1, bericht.duplicatesSkipped)
    }

    @Test
    fun `healthImportWindowStart - since sticht Zeitstempel und Puffer`() {
        val since = at(2026, 8, 1)
        val letzterImport = at(2026, 8, 5)

        assertEquals(
            since,
            healthImportWindowStart(since = since, lastImportAt = letzterImport, to = at(2026, 8, 10)),
        )
    }

    @Test
    fun `healthImportWindowStart - ohne Zeitstempel bleibt es beim 30-Tage-Fenster`() {
        val now = at(2026, 8, 10)

        assertEquals(
            now.plusMs(-healthSyncInitialWindowMs),
            healthImportWindowStart(since = null, lastImportAt = null, to = now),
        )
    }

    @Test
    fun `importNewRides - ohne Zeitstempel wird das Startfenster verwendet`() {
        val gateway = FakeHealthGateway()
        val now = at(2026, 8, 10, 12)
        serviceOf(gateway, now).importNewRides(existing = emptyList())

        assertEquals(now.plusMs(-healthSyncInitialWindowMs), gateway.lastWorkoutFrom)
    }

    @Test
    fun `importNewRides - schreibt den Zeitstempel nach dem Import fort`() {
        val gateway = FakeHealthGateway()
        val now = at(2026, 8, 10, 12)
        val service = serviceOf(gateway, now)

        assertNull(service.lastImportAt())
        service.importNewRides(existing = emptyList())
        assertEquals(now, service.lastImportAt())
    }

    @Test
    fun `importNewRides - scheitert verstaendlich wenn Health Connect fehlt`() {
        val gateway = FakeHealthGateway(availabilityValue = HealthAvailability.NICHT_INSTALLIERT)
        assertFailsWith<HealthSyncException> {
            serviceOf(gateway, at(2026, 8, 10)).importNewRides(existing = emptyList())
        }
    }

    @Test
    fun `importNewRides - scheitert wenn die Berechtigungen fehlen`() {
        val gateway = FakeHealthGateway(permissionsGranted = false)
        assertFailsWith<HealthSyncException> {
            serviceOf(gateway, at(2026, 8, 10)).importNewRides(existing = emptyList())
        }
    }

    @Test
    fun `importNewRides - meldet einen Fehler beim Lesen der Workouts`() {
        val gateway = FakeHealthGateway(failWorkouts = true)
        assertFailsWith<HealthSyncException> {
            serviceOf(gateway, at(2026, 8, 10)).importNewRides(existing = emptyList())
        }
    }

    // -----------------------------------------------------------------------
    // group('checkAvailability / requestPermissions')
    // -----------------------------------------------------------------------

    @Test
    fun `checkAvailability - meldet Bereitschaft bei erteilten Rechten`() {
        val connection = serviceOf(FakeHealthGateway(), at(2026, 8, 10)).checkAvailability()
        assertTrue(connection.isReady)
        assertFalse(connection.needsPermissions)
    }

    @Test
    fun `checkAvailability - meldet fehlende Rechte mit Hinweistext`() {
        val connection = serviceOf(
            FakeHealthGateway(permissionsGranted = false),
            at(2026, 8, 10),
        ).checkAvailability()
        assertFalse(connection.isReady)
        assertTrue(connection.needsPermissions)
        assertTrue(connection.message.contains("Zustimmung"))
    }

    @Test
    fun `requestPermissions - fragt Rechte nur an wenn sie fehlen`() {
        val gateway = FakeHealthGateway(permissionsGranted = true)
        val service = serviceOf(gateway, at(2026, 8, 10))

        assertTrue(service.requestPermissions())
        assertEquals(0, gateway.requestCount)

        gateway.permissionsGranted = false
        assertTrue(service.requestPermissions())
        assertEquals(1, gateway.requestCount)
    }

    @Test
    fun `requestPermissions - fragt nichts an wenn Health Connect fehlt`() {
        val gateway = FakeHealthGateway(
            availabilityValue = HealthAvailability.NICHT_INSTALLIERT,
            permissionsGranted = false,
        )
        val service = serviceOf(gateway, at(2026, 8, 10))

        assertFalse(service.requestPermissions())
        assertEquals(0, gateway.requestCount)
    }

    @Test
    fun `checkAvailability - gibt Update-Bedarf verstaendlich zurueck`() {
        val connection = serviceOf(
            FakeHealthGateway(availabilityValue = HealthAvailability.UPDATE_NOETIG),
            at(2026, 8, 10),
        ).checkAvailability()
        assertTrue(connection.message.contains("aktualisiert"))
    }

    // -----------------------------------------------------------------------
    // group('readVitals')
    // 2026-08-10 ist der "heutige" Tag; letzte Woche = 04.08.-10.08.,
    // Vorwoche = 28.07.-03.08.
    // -----------------------------------------------------------------------

    private val vitalsNow = at(2026, 8, 10, 12)

    @Test
    fun `readVitals - mittelt den Ruhepuls je Tag und bildet den Wochentrend`() {
        val gateway = FakeHealthGateway(
            restingHeartRate = listOf(
                // Vorwoche: Mittel 60
                HealthNumericSample(time = at(2026, 7, 29, 6), value = 58.0),
                HealthNumericSample(time = at(2026, 7, 31, 6), value = 62.0),
                // Letzte Woche: 55 (Tagesmittel aus 54/56) und 57 -> Mittel 56
                HealthNumericSample(time = at(2026, 8, 5, 6), value = 54.0),
                HealthNumericSample(time = at(2026, 8, 5, 7), value = 56.0),
                HealthNumericSample(time = at(2026, 8, 9, 6), value = 57.0),
            ),
        )

        val hr = serviceOf(gateway, vitalsNow).readVitals().restingHeartRate
        assertTrue(hr.hasData)
        // Zwei Messungen am 05.08. werden zu einem Tageswert gemittelt.
        assertEquals(4, hr.series.size)
        assertEquals(55.0, hr.series[2].value)
        assertEquals(56.0, hr.lastWeekAvg!!)
        assertEquals(60.0, hr.previousWeekAvg!!)
        assertEquals(-4.0, hr.delta!!)
        assertEquals(-6.7, hr.deltaPercent!!, 0.05)
        assertEquals(55.0, hr.min!!)
        assertEquals(62.0, hr.max!!)
        assertEquals(57.0, hr.latest!!)
    }

    @Test
    fun `readVitals - summiert Schlaf je Aufwachtag und rechnet in Stunden`() {
        val gateway = FakeHealthGateway(
            sleep = listOf(
                // Nacht auf den 09.08.: 6 h + 1 h Nickerchen -> 7 h
                HealthSleepSession(start = at(2026, 8, 8, 23), end = at(2026, 8, 9, 5)),
                HealthSleepSession(start = at(2026, 8, 9, 14), end = at(2026, 8, 9, 15)),
                // Vorwoche
                HealthSleepSession(start = at(2026, 7, 29, 23), end = at(2026, 7, 30, 7)),
            ),
        )

        val sleep = serviceOf(gateway, vitalsNow).readVitals().sleepHours
        assertEquals(2, sleep.series.size)
        assertEquals(at(2026, 7, 30), sleep.series.first().day)
        assertEquals(8.0, sleep.series.first().value)
        assertEquals(at(2026, 8, 9), sleep.series.last().day)
        assertEquals(7.0, sleep.series.last().value)
        assertEquals(7.0, sleep.lastWeekAvg!!)
        assertEquals(8.0, sleep.previousWeekAvg!!)
        assertEquals(-1.0, sleep.delta!!)
    }

    @Test
    fun `readVitals - kein Trend wenn die Vorwoche keine Daten hat`() {
        val gateway = FakeHealthGateway(
            restingHeartRate = listOf(HealthNumericSample(time = at(2026, 8, 9, 6), value = 57.0)),
        )

        val hr = serviceOf(gateway, vitalsNow).readVitals().restingHeartRate
        assertTrue(hr.hasData)
        assertEquals(57.0, hr.lastWeekAvg!!)
        assertNull(hr.previousWeekAvg)
        assertFalse(hr.hasTrend)
        assertNull(hr.delta)
        assertNull(hr.deltaPercent)
    }

    @Test
    fun `readVitals - leere Daten ergeben eine leere Zusammenfassung`() {
        val vitals = serviceOf(FakeHealthGateway(), vitalsNow).readVitals()

        assertTrue(vitals.isEmpty)
        assertFalse(vitals.restingHeartRate.hasData)
        assertTrue(vitals.restingHeartRate.series.isEmpty())
        assertNull(vitals.restingHeartRate.lastWeekAvg)
        assertNull(vitals.restingHeartRate.min)
        assertFalse(vitals.sleepHours.hasData)
        assertNull(vitals.vo2max)
    }

    @Test
    fun `readVitals - ein Ausfall beim Schlaf verhindert den Ruhepuls nicht`() {
        val gateway = FakeHealthGateway(
            restingHeartRate = listOf(HealthNumericSample(time = at(2026, 8, 9, 6), value = 57.0)),
            failSleep = true,
        )

        val vitals = serviceOf(gateway, vitalsNow).readVitals()
        assertTrue(vitals.restingHeartRate.hasData)
        assertFalse(vitals.sleepHours.hasData)
        assertTrue(vitals.unavailable.contains(VitalsDataKind.SCHLAF))
        assertFalse(vitals.unavailable.contains(VitalsDataKind.RUHEPULS))
    }

    @Test
    fun `readVitals - ein Ausfall beim Ruhepuls verhindert den Schlaf nicht`() {
        val gateway = FakeHealthGateway(
            sleep = listOf(HealthSleepSession(start = at(2026, 8, 8, 23), end = at(2026, 8, 9, 7))),
            failRestingHeartRate = true,
        )

        val vitals = serviceOf(gateway, vitalsNow).readVitals()
        assertTrue(vitals.sleepHours.hasData)
        assertFalse(vitals.restingHeartRate.hasData)
        assertTrue(vitals.unavailable.contains(VitalsDataKind.RUHEPULS))
    }

    @Test
    fun `readVitals - VO2max wird als nicht verfuegbar gemeldet`() {
        val vitals = serviceOf(FakeHealthGateway(), vitalsNow).readVitals()
        assertNull(vitals.vo2max)
        assertTrue(vitals.unavailable.contains(VitalsDataKind.VO2MAX))
    }

    @Test
    fun `readVitals - nimmt den neuesten VO2max-Wert`() {
        val gateway = FakeHealthGateway(
            failVo2max = false,
            vo2max = listOf(
                HealthNumericSample(time = at(2026, 8, 1), value = 47.5),
                HealthNumericSample(time = at(2026, 8, 8), value = 48.26),
                HealthNumericSample(time = at(2026, 8, 4), value = 46.0),
            ),
        )

        val vitals = serviceOf(gateway, vitalsNow).readVitals()
        assertEquals(48.3, vitals.vo2max!!)
        assertEquals(at(2026, 8, 8), vitals.vo2maxAt)
        assertTrue(vitals.unavailable.isEmpty())
    }

    @Test
    fun `readVitals - das Fenster richtet sich nach days`() {
        val vitals = serviceOf(FakeHealthGateway(), vitalsNow).readVitals(days = 7)
        assertEquals(7, vitals.days)
        assertEquals(at(2026, 8, 4), vitals.from)
        assertEquals(vitalsNow, vitals.to)
    }

    @Test
    fun `readVitals - liest HRV als Tagesserie mit Wochentrend`() {
        val gateway = FakeHealthGateway(
            hrv = listOf(
                // Vorwoche
                HealthNumericSample(time = at(2026, 7, 29, 3), value = 60.0),
                HealthNumericSample(time = at(2026, 7, 31, 3), value = 50.0),
                // Letzte Woche
                HealthNumericSample(time = at(2026, 8, 5, 2), value = 44.0),
                HealthNumericSample(time = at(2026, 8, 9, 4), value = 40.0),
            ),
        )

        val hrv = serviceOf(gateway, vitalsNow).readVitals().heartRateVariability
        assertTrue(hrv.hasData)
        assertEquals(4, hrv.series.size)
        assertEquals(40.0, hrv.latest!!)
        assertEquals(42.0, hrv.lastWeekAvg!!)
        assertEquals(55.0, hrv.previousWeekAvg!!)
        assertEquals(-13.0, hrv.delta!!)
    }

    @Test
    fun `readVitals - ein HRV-Ausfall laesst Ruhepuls und Schlaf unberuehrt`() {
        val gateway = FakeHealthGateway(
            restingHeartRate = listOf(HealthNumericSample(time = at(2026, 8, 9, 6), value = 57.0)),
            failHrv = true,
        )

        val vitals = serviceOf(gateway, vitalsNow).readVitals()
        assertTrue(vitals.restingHeartRate.hasData)
        assertFalse(vitals.heartRateVariability.hasData)
        assertTrue(vitals.unavailable.contains(VitalsDataKind.HRV))
        assertFalse(vitals.unavailable.contains(VitalsDataKind.RUHEPULS))
    }

    @Test
    fun `readVitals - ohne HRV bleibt die Reihe leer, ohne Fehlermeldung`() {
        val vitals = serviceOf(FakeHealthGateway(), vitalsNow).readVitals()
        assertFalse(vitals.heartRateVariability.hasData)
        assertFalse(vitals.unavailable.contains(VitalsDataKind.HRV))
    }

    // -----------------------------------------------------------------------
    // group('dailyHrvValues')
    // -----------------------------------------------------------------------

    @Test
    fun `dailyHrvValues - mittelt die Messungen zwischen 0 und 12 Uhr`() {
        val series = dailyHrvValues(
            listOf(
                HealthNumericSample(time = at(2026, 8, 5, 2), value = 40.0),
                HealthNumericSample(time = at(2026, 8, 5, 5), value = 50.0),
                // Nachmittagswert zaehlt nicht, solange es Nachtwerte gibt.
                HealthNumericSample(time = at(2026, 8, 5, 18), value = 20.0),
            ),
        )
        assertEquals(1, series.size)
        assertEquals(at(2026, 8, 5), series.single().day)
        assertEquals(45.0, series.single().value)
    }

    @Test
    fun `dailyHrvValues - 11 59 zaehlt noch zum Morgenfenster, 12 00 nicht mehr`() {
        val series = dailyHrvValues(
            listOf(
                HealthNumericSample(time = at(2026, 8, 5, 11, 59), value = 42.0),
                HealthNumericSample(time = at(2026, 8, 5, 12), value = 20.0),
            ),
        )
        assertEquals(42.0, series.single().value)
    }

    @Test
    fun `dailyHrvValues - ohne Morgenwerte faellt der Tag ganz weg`() {
        // Tages-rMSSD liegt systematisch unter dem naechtlichen Wert (Belastung,
        // Kaffee, Koerperhaltung). Als Ersatz eingesetzt erschiene jeder solche
        // Tag der Baseline als HRV-Einbruch — ein fehlender Tag ist ehrlicher.
        val series = dailyHrvValues(
            listOf(
                HealthNumericSample(time = at(2026, 8, 5, 14), value = 30.0),
                HealthNumericSample(time = at(2026, 8, 5, 20), value = 40.0),
            ),
        )
        assertTrue(series.isEmpty())
    }

    @Test
    fun `dailyHrvValues - Nachmittagswerte kippen einen Tag mit Morgenwert nicht`() {
        val series = dailyHrvValues(
            listOf(
                HealthNumericSample(time = at(2026, 8, 5, 4), value = 60.0),
                HealthNumericSample(time = at(2026, 8, 5, 17), value = 20.0),
                // Tag ohne Nachtmessung faellt weg, statt den Schnitt zu senken.
                HealthNumericSample(time = at(2026, 8, 6, 17), value = 22.0),
            ),
        )
        assertEquals(1, series.size)
        assertEquals(at(2026, 8, 5), series.single().day)
        assertEquals(60.0, series.single().value)
    }

    @Test
    fun `dailyHrvValues - trennt Kalendertage und sortiert aufsteigend`() {
        val series = dailyHrvValues(
            listOf(
                HealthNumericSample(time = at(2026, 8, 6, 3), value = 38.0),
                HealthNumericSample(time = at(2026, 8, 4, 3), value = 52.0),
                HealthNumericSample(time = at(2026, 8, 5, 3), value = 45.0),
            ),
        )
        assertEquals(
            listOf(at(2026, 8, 4), at(2026, 8, 5), at(2026, 8, 6)),
            series.map { it.day },
        )
        assertEquals(listOf(52.0, 45.0, 38.0), series.map { it.value })
    }

    @Test
    fun `dailyHrvValues - unbrauchbare Messwerte fallen raus`() {
        val series = dailyHrvValues(
            listOf(
                HealthNumericSample(time = at(2026, 8, 5, 3), value = 0.0),
                HealthNumericSample(time = at(2026, 8, 5, 4), value = -5.0),
                HealthNumericSample(time = at(2026, 8, 5, 5), value = Double.NaN),
                HealthNumericSample(time = at(2026, 8, 6, 3), value = 40.0),
            ),
        )
        assertEquals(1, series.size)
        assertEquals(at(2026, 8, 6), series.single().day)
    }

    @Test
    fun `dailyHrvValues - leere Eingabe ergibt eine leere Reihe`() {
        assertTrue(dailyHrvValues(emptyList()).isEmpty())
    }

    // -----------------------------------------------------------------------
    // group('healthSyncInitialWindow')
    // -----------------------------------------------------------------------

    @Test
    fun `healthSyncInitialWindow - umfasst 30 Tage`() {
        assertEquals(days(30), healthSyncInitialWindowMs)
    }

    @Test
    fun `healthSyncInitialWindow - ohne Zeitstempel wird genau 30 Tage zurueckgeschaut`() {
        val gateway = FakeHealthGateway()
        val now = at(2026, 8, 10, 12)
        serviceOf(gateway, now).importWithReport(existing = emptyList())

        assertEquals(at(2026, 7, 11, 12), gateway.lastWorkoutFrom)
        assertEquals(now, gateway.lastWorkoutTo)
    }

    // -----------------------------------------------------------------------
    // group('rideHasHeartRate')
    // -----------------------------------------------------------------------

    @Test
    fun `rideHasHeartRate - erkennt Kennzahlen und Trackpunkt-Werte`() {
        val start = at(2026, 8, 1, 10)
        assertFalse(rideHasHeartRate(rideWithPoints(id = "ohne", start = start)))
        assertTrue(rideHasHeartRate(rideWithPoints(id = "avg", start = start, avgHrBpm = 140)))
        assertTrue(rideHasHeartRate(rideWithPoints(id = "punkte", start = start, pointHr = 132)))
        assertTrue(
            rideHasHeartRate(
                ride(id = "max", start = start, durationMs = hours(1), maxHrBpm = 180),
            ),
        )
    }

    // -----------------------------------------------------------------------
    // group('mergeHeartRateIntoRide')
    // -----------------------------------------------------------------------

    private val mergeStart = at(2026, 8, 1, 10)

    @Test
    fun `mergeHeartRateIntoRide - ordnet die zeitlich naechste Messung innerhalb 60s zu`() {
        val r = rideWithPoints(id = "lokal", start = mergeStart)
        val merged = mergeHeartRateIntoRide(
            r,
            listOf(
                // 30 s nach dem ersten Punkt -> zugeordnet.
                HealthHeartRateSample(time = mergeStart.plusMs(seconds(30)), bpm = 120.0),
                // 70 s nach dem zweiten Punkt -> ausserhalb der Toleranz.
                HealthHeartRateSample(
                    time = mergeStart.plusMs(minutes(11) + seconds(10)),
                    bpm = 200.0,
                ),
                // Exakt auf dem dritten Punkt.
                HealthHeartRateSample(time = mergeStart.plusMs(minutes(20)), bpm = 180.0),
            ),
        )

        assertNotNull(merged)
        assertEquals(listOf(120, null, 180), merged.points.map { it.hr })
        assertEquals(150, merged.stats.avgHrBpm)
        assertEquals(180, merged.stats.maxHrBpm)
    }

    @Test
    fun `mergeHeartRateIntoRide - nimmt bei zwei Messungen die naeher liegende`() {
        val r = rideWithPoints(id = "lokal", start = mergeStart, pointCount = 1)
        val merged = mergeHeartRateIntoRide(
            r,
            listOf(
                HealthHeartRateSample(time = mergeStart.plusMs(-seconds(50)), bpm = 100.0),
                HealthHeartRateSample(time = mergeStart.plusMs(seconds(5)), bpm = 155.0),
            ),
        )

        assertNotNull(merged)
        assertEquals(155, merged.points.single().hr)
        assertEquals(155, merged.stats.avgHrBpm)
    }

    @Test
    fun `mergeHeartRateIntoRide - behaelt ID, Name, Zeitpunkt, Punkte und Kennzahlen`() {
        val r = rideWithPoints(id = "lokal", start = mergeStart)
        val merged = mergeHeartRateIntoRide(
            r,
            listOf(HealthHeartRateSample(time = mergeStart, bpm = 130.0)),
        )!!

        assertEquals(r.id, merged.id)
        assertEquals(r.name, merged.name)
        assertEquals(r.createdAt, merged.createdAt)
        assertEquals(r.points.size, merged.points.size)
        assertEquals(r.points.first().lat, merged.points.first().lat)
        assertEquals(r.points.first().lon, merged.points.first().lon)
        assertEquals(r.points.first().ele, merged.points.first().ele)
        assertEquals(r.points.last().time, merged.points.last().time)
        assertEquals(r.stats.distanceKm, merged.stats.distanceKm)
        assertEquals(r.stats.ascentM, merged.stats.ascentM)
        assertEquals(r.stats.descentM, merged.stats.descentM)
        assertEquals(r.stats.durationS, merged.stats.durationS)
        assertEquals(r.stats.movingTimeS, merged.stats.movingTimeS)
        assertEquals(r.stats.avgSpeedKmh, merged.stats.avgSpeedKmh)
    }

    @Test
    fun `mergeHeartRateIntoRide - ohne Messwerte passiert nichts`() {
        assertNull(
            mergeHeartRateIntoRide(rideWithPoints(id = "lokal", start = mergeStart), emptyList()),
        )
    }

    @Test
    fun `mergeHeartRateIntoRide - ohne Trackpunkte passiert nichts`() {
        assertNull(
            mergeHeartRateIntoRide(
                ride(id = "lokal", start = mergeStart, durationMs = hours(1)),
                listOf(HealthHeartRateSample(time = mergeStart, bpm = 130.0)),
            ),
        )
    }

    @Test
    fun `mergeHeartRateIntoRide - alles ausserhalb der Toleranz laesst die Tour unangetastet`() {
        assertNull(
            mergeHeartRateIntoRide(
                rideWithPoints(id = "lokal", start = mergeStart),
                listOf(HealthHeartRateSample(time = mergeStart.plusMs(-hours(3)), bpm = 130.0)),
            ),
        )
    }

    @Test
    fun `mergeHeartRateIntoRide - viele Punkte und Messungen laufen in einem Durchlauf`() {
        val r = rideWithPoints(
            id = "lang",
            start = mergeStart,
            pointCount = 500,
            stepMs = seconds(10),
        )
        val samples = (0 until 2000).map { i ->
            HealthHeartRateSample(
                time = mergeStart.plusMs(i * 2500L),
                bpm = 100 + (i % 60).toDouble(),
            )
        }

        val merged = mergeHeartRateIntoRide(r, samples)!!
        assertEquals(500, merged.points.count { it.hr != null })
        assertNotNull(merged.stats.maxHrBpm)
    }

    // -----------------------------------------------------------------------
    // group('importWithReport')
    // -----------------------------------------------------------------------

    @Test
    fun `importWithReport - zaehlt gefundene, importierte, zusammengefuehrte und doppelte Sessions`() {
        val tag1 = at(2026, 8, 1, 10)
        val tag2 = at(2026, 8, 2, 10)
        val tag3 = at(2026, 8, 3, 10)
        val tag4 = at(2026, 8, 4, 10)
        val tag5 = at(2026, 8, 5, 10)

        val gateway = FakeHealthGateway(
            workouts = listOf(
                cycling(id = "mit-route", start = tag1, end = tag1.plusMs(hours(1))),
                cycling(id = "ohne-route", start = tag2, end = tag2.plusMs(hours(1))),
                cycling(id = "bekannt", start = tag3, end = tag3.plusMs(hours(1))),
                cycling(id = "hf-schon-da", start = tag4, end = tag4.plusMs(hours(1))),
                cycling(id = "merge", start = tag5, end = tag5.plusMs(minutes(25))),
                HealthWorkout(
                    id = "lauf",
                    start = tag1.plusMs(days(6)),
                    end = tag1.plusMs(days(6) + hours(1)),
                    kind = HealthActivityKind.SONSTIGES,
                    distanceM = 10000.0,
                ),
            ),
            routes = mapOf(
                "mit-route" to listOf(
                    HealthRoutePoint(lat = 48.0, lon = 11.0, time = tag1),
                    HealthRoutePoint(lat = 48.01, lon = 11.0, time = tag1.plusMs(minutes(30))),
                ),
            ),
            heartRate = listOf(
                HealthHeartRateSample(time = tag5, bpm = 120.0),
                HealthHeartRateSample(time = tag5.plusMs(minutes(10)), bpm = 160.0),
                HealthHeartRateSample(time = tag5.plusMs(minutes(20)), bpm = 180.0),
            ),
        )

        val now = at(2026, 8, 10, 12)
        val report = serviceOf(gateway, now).importWithReport(
            existing = listOf(
                ride(id = "hc-bekannt", start = at(2026, 7, 1), durationMs = hours(1)),
                ride(id = "mit-hf", start = tag4, durationMs = hours(1), avgHrBpm = 142),
                rideWithPoints(id = "ohne-hf", start = tag5),
            ),
        )

        assertEquals(now.plusMs(-days(30)), report.from)
        assertEquals(now, report.to)
        // Nur Rad-Sessions zaehlen, das Laufen nicht.
        assertEquals(5, report.workoutsFound)
        assertEquals(listOf("hc-mit-route", "hc-ohne-route"), report.imported.map { it.id })
        assertEquals(listOf("ohne-hf"), report.mergedRides.map { it.id })
        // 'bekannt' (gleiche ID) und 'hf-schon-da' (Tour hat bereits HF).
        assertEquals(2, report.duplicatesSkipped)
        // Outdoor-Tour ohne Trackpunkte.
        assertEquals(1, report.routesMissing)
        assertEquals(3, report.changedCount)
        assertFalse(report.isEmpty)
    }

    @Test
    fun `importWithReport - Indoor-Touren ohne Route zaehlen nicht als fehlende Route`() {
        val start = at(2026, 8, 1, 18)
        val gateway = FakeHealthGateway(
            workouts = listOf(
                cycling(
                    id = "rolle",
                    start = start,
                    end = start.plusMs(minutes(45)),
                    kind = HealthActivityKind.RADFAHREN_INDOOR,
                ),
            ),
        )

        val report = serviceOf(gateway, at(2026, 8, 10)).importWithReport(existing = emptyList())
        assertEquals(1, report.imported.size)
        assertEquals(0, report.routesMissing)
    }

    @Test
    fun `importWithReport - ohne Sessions bleibt der Bericht leer und schreibt den Zeitstempel fort`() {
        val now = at(2026, 8, 10, 12)
        val service = serviceOf(FakeHealthGateway(), now)

        val report = service.importWithReport(existing = emptyList())
        assertEquals(0, report.workoutsFound)
        assertTrue(report.imported.isEmpty())
        assertTrue(report.mergedRides.isEmpty())
        assertEquals(0, report.duplicatesSkipped)
        assertEquals(0, report.routesMissing)
        assertTrue(report.isEmpty)
        assertEquals(now, service.lastImportAt())
    }

    @Test
    fun `importWithReport - importNewRides bleibt ein duenner Wrapper`() {
        val start = at(2026, 8, 1, 10)
        val gateway = FakeHealthGateway(
            workouts = listOf(cycling(id = "neu", start = start, end = start.plusMs(hours(1)))),
        )

        val rides = serviceOf(gateway, at(2026, 8, 10)).importNewRides(existing = emptyList())
        assertEquals(listOf("hc-neu"), rides.map { it.id })
    }

    // -----------------------------------------------------------------------
    // group('HF-Merge beim Import')
    // -----------------------------------------------------------------------

    private fun mergeGatewayWith(
        heartRate: List<HealthHeartRateSample> = emptyList(),
    ): FakeHealthGateway = FakeHealthGateway(
        workouts = listOf(
            cycling(id = "watch", start = mergeStart, end = mergeStart.plusMs(minutes(25))),
        ),
        heartRate = heartRate,
    )

    @Test
    fun `HF-Merge - reichert eine ueberlappende Tour ohne HF an statt sie zu verwerfen`() {
        val gateway = mergeGatewayWith(
            heartRate = listOf(
                HealthHeartRateSample(time = mergeStart.plusMs(seconds(20)), bpm = 120.0),
                HealthHeartRateSample(time = mergeStart.plusMs(minutes(10)), bpm = 150.0),
                HealthHeartRateSample(time = mergeStart.plusMs(minutes(20)), bpm = 180.0),
            ),
        )

        val bestehend = rideWithPoints(id = "lokal", start = mergeStart)
        val report = serviceOf(gateway, at(2026, 8, 10)).importWithReport(
            existing = listOf(bestehend),
        )

        assertTrue(report.imported.isEmpty())
        assertEquals(0, report.duplicatesSkipped)
        assertEquals(1, report.mergedRides.size)

        val merged = report.mergedRides.single()
        assertEquals("lokal", merged.id)
        assertEquals(bestehend.points.size, merged.points.size)
        assertEquals(listOf(120, 150, 180), merged.points.map { it.hr })
        assertEquals(150, merged.stats.avgHrBpm)
        assertEquals(180, merged.stats.maxHrBpm)
        assertEquals(bestehend.stats.distanceKm, merged.stats.distanceKm)
        // Die HF wird nur fuer das Session-Fenster gelesen.
        assertEquals(mergeStart, gateway.heartRateWindows.single().first)
        assertEquals(mergeStart.plusMs(minutes(25)), gateway.heartRateWindows.single().second)
    }

    @Test
    fun `Zusammenfassungs-Variante - laedt nur ueberlappende Touren nach und merged wie bisher`() {
        val gateway = mergeGatewayWith(
            heartRate = listOf(
                HealthHeartRateSample(time = mergeStart.plusMs(seconds(20)), bpm = 120.0),
                HealthHeartRateSample(time = mergeStart.plusMs(minutes(10)), bpm = 150.0),
                HealthHeartRateSample(time = mergeStart.plusMs(minutes(20)), bpm = 180.0),
            ),
        )

        val ueberlappend = rideWithPoints(id = "lokal", start = mergeStart)
        val fern = rideWithPoints(id = "fern", start = at(2026, 7, 20, 9))
        val alle = listOf(ueberlappend, fern)
        val loaded = mutableListOf<String>()

        val report = serviceOf(gateway, at(2026, 8, 10)).importWithReport(
            existing = alle.map { it.toSummary() },
            loadRide = { id ->
                loaded.add(id)
                alle.firstOrNull { it.id == id }
            },
        )

        // Nur die tatsaechlich ueberlappende Tour wurde von der Platte geholt —
        // nie der Gesamtbestand.
        assertEquals(listOf("lokal"), loaded)
        assertEquals(listOf("lokal"), report.mergedRides.map { it.id })
        assertEquals(listOf(120, 150, 180), report.mergedRides.single().points.map { it.hr })
    }

    @Test
    fun `Zusammenfassungs-Variante - eine nicht ladbare Tour zaehlt als Duplikat statt zu verlieren`() {
        val gateway = mergeGatewayWith()

        val report = serviceOf(gateway, at(2026, 8, 10)).importWithReport(
            existing = listOf(rideWithPoints(id = "lokal", start = mergeStart).toSummary()),
            loadRide = { null },
        )

        assertTrue(report.imported.isEmpty())
        assertTrue(report.mergedRides.isEmpty())
        assertEquals(1, report.duplicatesSkipped)
    }

    @Test
    fun `HF-Merge - eine Tour mit vorhandener HF wird nicht angefasst`() {
        val gateway = mergeGatewayWith(
            heartRate = listOf(HealthHeartRateSample(time = mergeStart, bpm = 120.0)),
        )

        val report = serviceOf(gateway, at(2026, 8, 10)).importWithReport(
            existing = listOf(rideWithPoints(id = "lokal", start = mergeStart, avgHrBpm = 138)),
        )

        assertTrue(report.mergedRides.isEmpty())
        assertTrue(report.imported.isEmpty())
        assertEquals(1, report.duplicatesSkipped)
        // Ohne Merge-Kandidat wird die HF gar nicht erst gelesen.
        assertTrue(gateway.heartRateWindows.isEmpty())
    }

    @Test
    fun `HF-Merge - auch Trackpunkt-HF schuetzt die bestehende Tour`() {
        val gateway = mergeGatewayWith(
            heartRate = listOf(HealthHeartRateSample(time = mergeStart, bpm = 120.0)),
        )

        val report = serviceOf(gateway, at(2026, 8, 10)).importWithReport(
            existing = listOf(rideWithPoints(id = "lokal", start = mergeStart, pointHr = 131)),
        )

        assertTrue(report.mergedRides.isEmpty())
        assertEquals(1, report.duplicatesSkipped)
    }

    @Test
    fun `HF-Merge - ohne HF-Samples bleibt die Session ein Duplikat`() {
        val report = serviceOf(mergeGatewayWith(), at(2026, 8, 10)).importWithReport(
            existing = listOf(rideWithPoints(id = "lokal", start = mergeStart)),
        )

        assertTrue(report.mergedRides.isEmpty())
        assertTrue(report.imported.isEmpty())
        assertEquals(1, report.duplicatesSkipped)
    }

    @Test
    fun `HF-Merge - ein Fehler beim HF-Lesen macht die Session zum Duplikat`() {
        val gateway = mergeGatewayWith(
            heartRate = listOf(HealthHeartRateSample(time = mergeStart, bpm = 120.0)),
        ).apply { failHeartRate = true }

        val report = serviceOf(gateway, at(2026, 8, 10)).importWithReport(
            existing = listOf(rideWithPoints(id = "lokal", start = mergeStart)),
        )

        assertTrue(report.mergedRides.isEmpty())
        assertEquals(1, report.duplicatesSkipped)
    }

    @Test
    fun `HF-Merge - dieselbe Tour wird hoechstens einmal je Lauf angereichert`() {
        val gateway = FakeHealthGateway(
            workouts = listOf(
                cycling(id = "watch-a", start = mergeStart, end = mergeStart.plusMs(minutes(25))),
                // Nahezu identische Session einer zweiten Quell-App.
                cycling(
                    id = "watch-b",
                    start = mergeStart.plusMs(minutes(1)),
                    end = mergeStart.plusMs(minutes(25)),
                ),
            ),
            heartRate = listOf(
                HealthHeartRateSample(time = mergeStart, bpm = 120.0),
                HealthHeartRateSample(time = mergeStart.plusMs(minutes(20)), bpm = 160.0),
            ),
        )

        val report = serviceOf(gateway, at(2026, 8, 10)).importWithReport(
            existing = listOf(rideWithPoints(id = "lokal", start = mergeStart)),
        )

        assertEquals(1, report.mergedRides.size)
        assertEquals(1, report.duplicatesSkipped)
    }

    @Test
    fun `HF-Merge - importNewRides liefert Merges nicht mit zurueck`() {
        val gateway = mergeGatewayWith(
            heartRate = listOf(
                HealthHeartRateSample(time = mergeStart, bpm = 120.0),
                HealthHeartRateSample(time = mergeStart.plusMs(minutes(20)), bpm = 160.0),
            ),
        )

        val rides = serviceOf(gateway, at(2026, 8, 10)).importNewRides(
            existing = listOf(rideWithPoints(id = "lokal", start = mergeStart)),
        )
        assertTrue(rides.isEmpty())
    }

    // -----------------------------------------------------------------------
    // group('mapNativeSessionKind')
    // -----------------------------------------------------------------------

    private val sessionStart = at(2026, 8, 8, 9)

    @Test
    fun `mapNativeSessionKind - BIKING und BIKING_STATIONARY werden direkt zugeordnet`() {
        assertEquals(
            HealthActivityKind.RADFAHREN,
            mapNativeSessionKind(session(start = sessionStart)),
        )
        assertEquals(
            HealthActivityKind.RADFAHREN_INDOOR,
            mapNativeSessionKind(
                session(
                    start = sessionStart,
                    typeName = "EXERCISE_TYPE_BIKING_STATIONARY",
                    typeCode = 9,
                ),
            ),
        )
    }

    @Test
    fun `mapNativeSessionKind - Titel-Heuristik erkennt Rad-Titel bei fremdem Typ`() {
        for (title in listOf("Fahrrad", "Radtour am Abend", "Gravel Ride", "MTB-Runde", "Cycling", "E-Bike")) {
            assertEquals(
                HealthActivityKind.RADFAHREN,
                mapNativeSessionKind(
                    session(
                        start = sessionStart,
                        typeName = "EXERCISE_TYPE_OTHER_WORKOUT",
                        typeCode = 0,
                        title = title,
                    ),
                ),
                title,
            )
        }
    }

    @Test
    fun `mapNativeSessionKind - andere Titel bleiben unberuecksichtigt`() {
        for (title in listOf("Laufen", "Schwimmen", "Krafttraining", "Wandern")) {
            assertNull(
                mapNativeSessionKind(
                    session(
                        start = sessionStart,
                        typeName = "EXERCISE_TYPE_OTHER_WORKOUT",
                        typeCode = 0,
                        title = title,
                    ),
                ),
                title,
            )
        }
        assertNull(
            mapNativeSessionKind(
                session(start = sessionStart, typeName = "EXERCISE_TYPE_RUNNING", typeCode = 56),
            ),
        )
    }

    // -----------------------------------------------------------------------
    // group('Nativer Fallback beim Import')
    // -----------------------------------------------------------------------

    private val fallbackNow = at(2026, 8, 8, 20)

    @Test
    fun `Nativer Fallback - greift wenn das Plugin keine Rad-Session liefert`() {
        val gateway = FakeHealthGateway(
            nativeSessions = listOf(session(uid = "abc", start = sessionStart)),
        )
        val report = serviceOf(gateway, fallbackNow).importWithReport(existing = emptyList())

        assertEquals(1, gateway.nativeSessionCalls)
        assertEquals(1, report.workoutsFound)
        assertEquals(1, report.imported.size)
        assertEquals(healthRideId("abc"), report.imported.single().id)
        assertTrue(report.debugLines.any { it.startsWith("Fallback: aktiv") })
    }

    @Test
    fun `Nativer Fallback - nutzt die uid fuer Route und Duplikatserkennung`() {
        val gateway = FakeHealthGateway(
            nativeSessions = listOf(session(uid = "abc", start = sessionStart, hasRoute = true)),
            routes = mapOf(
                "abc" to listOf(
                    HealthRoutePoint(lat = 48.0, lon = 11.0, time = sessionStart, ele = 500.0),
                    HealthRoutePoint(
                        lat = 48.01,
                        lon = 11.01,
                        time = sessionStart.plusMs(minutes(30)),
                        ele = 520.0,
                    ),
                ),
            ),
        )
        val service = serviceOf(gateway, fallbackNow)

        val report = service.importWithReport(existing = emptyList())
        assertEquals(2, report.imported.single().points.size)
        assertEquals(0, report.routesMissing)

        // Zweiter Lauf: die abgeleitete ID ist bereits bekannt.
        val zweiter = service.importWithReport(
            since = sessionStart.plusMs(-days(1)),
            existing = report.imported,
        )
        assertTrue(zweiter.imported.isEmpty())
        assertEquals(1, zweiter.duplicatesSkipped)
    }

    @Test
    fun `Nativer Fallback - Indoor-Sessions landen als Indoor-Tour`() {
        val gateway = FakeHealthGateway(
            nativeSessions = listOf(
                session(
                    start = sessionStart,
                    typeName = "EXERCISE_TYPE_BIKING_STATIONARY",
                    typeCode = 9,
                ),
            ),
        )

        val report = serviceOf(gateway, fallbackNow).importWithReport(existing = emptyList())
        assertTrue(report.imported.single().name.contains("(Indoor)"))
        // Ohne Route fehlt nur draussen etwas.
        assertEquals(0, report.routesMissing)
    }

    @Test
    fun `Nativer Fallback - Sessions ohne Rad-Bezug loesen keinen Import aus`() {
        val gateway = FakeHealthGateway(
            nativeSessions = listOf(
                session(
                    start = sessionStart,
                    typeName = "EXERCISE_TYPE_RUNNING",
                    typeCode = 56,
                    title = "Morgenlauf",
                ),
            ),
        )

        val report = serviceOf(gateway, fallbackNow).importWithReport(existing = emptyList())
        assertEquals(0, report.workoutsFound)
        assertTrue(report.imported.isEmpty())
        assertTrue(report.debugLines.any { it == "Fallback: nicht verwendet" })
    }

    @Test
    fun `Nativer Fallback - ein Fehler des nativen Wegs bleibt folgenlos`() {
        val gateway = FakeHealthGateway(failNativeSessions = true)
        val report = serviceOf(gateway, fallbackNow).importWithReport(existing = emptyList())

        assertEquals(0, report.workoutsFound)
        assertTrue(report.imported.isEmpty())
        assertTrue(report.debugLines.any { it.startsWith("Nativ: nicht verfügbar") })
    }

    @Test
    fun `Nativer Fallback - liefert das Plugin Rad-Sessions wird nativ gar nicht gelesen`() {
        val gateway = FakeHealthGateway(
            workouts = listOf(cycling(start = sessionStart, end = sessionStart.plusMs(hours(1)))),
            nativeSessions = listOf(session(uid = "nativ", start = sessionStart)),
        )

        val report = serviceOf(gateway, fallbackNow).importWithReport(existing = emptyList())
        assertEquals(0, gateway.nativeSessionCalls)
        assertEquals(healthRideId("w1"), report.imported.single().id)
        assertTrue(report.debugLines.any { it == "Fallback: nicht verwendet" })
    }

    // -----------------------------------------------------------------------
    // group('debugLines')
    // -----------------------------------------------------------------------

    @Test
    fun `debugLines - nennen Rohpunkte und Werttypen des Plugins`() {
        val gateway = FakeHealthGateway(
            workoutDiagnostics = HealthWorkoutReadDiagnostics(
                rawPointCount = 3,
                valueTypeCounts = mapOf("NumericHealthValue" to 3),
                activityTypeCounts = emptyMap(),
            ),
        )

        val report = serviceOf(gateway, at(2026, 8, 8, 20)).importWithReport(existing = emptyList())
        assertTrue(
            report.debugLines.any {
                it.contains("3 Rohpunkt(e)") && it.contains("NumericHealthValue×3")
            },
        )
        assertTrue(report.debugLines.first().startsWith("Zeitraum:"))
    }

    @Test
    fun `debugLines - ohne Rohdiagnose bleibt die Zeile trotzdem sprechend`() {
        val report = serviceOf(FakeHealthGateway(), at(2026, 8, 8, 20))
            .importWithReport(existing = emptyList())
        assertTrue(report.debugLines.contains("Plugin: keine Rohdiagnose erhoben"))
        assertTrue(report.debugLines.contains("Plugin: 0 Rad-Session(s)"))
    }
}
