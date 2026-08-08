package de.trailscape.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Zentrale Datentypen von Trailscape.
 *
 * 1:1-Portierung von `lib/models.dart` (Flutter-App). Die JSON-Formate sind
 * absichtlich bytegetreu kompatibel zum Selfhost-Sync-Server (server/) und
 * zur frueheren Web-App, damit bestehende Tour-/Trainings-Dateien der Nutzer
 * beim Umstieg auf die native App weiter lesbar bleiben: gleiche
 * Feldnamen, gleiche Nullable-/Default-Semantik, gleiche Zeitstempel-Form
 * (durchgehend ms seit Epoch als JSON-Zahl — Dart nutzt hier nirgends
 * `toIso8601String()`).
 */

/** Einzelner aufgezeichneter Trackpunkt. */
data class TrackPoint(
    val lat: Double,
    val lon: Double,
    /** Hoehe in Metern. */
    val ele: Double? = null,
    /** Zeitstempel in ms seit Epoch. */
    val time: Long? = null,
    /**
     * Herzfrequenz in Schlaegen pro Minute, falls bekannt (z. B. aus einer
     * ueber Health Connect importierten Watch-Aufzeichnung). Optional und
     * wird nur serialisiert, wenn gesetzt — bestehende Tour-Dateien und der
     * Sync-Server bleiben damit unveraendert kompatibel.
     */
    val hr: Int? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("lat", lat)
        put("lon", lon)
        ele?.let { put("ele", it) }
        time?.let { put("time", it) }
        hr?.let { put("hr", it) }
    }

    companion object {
        fun fromJson(json: JsonObject): TrackPoint = TrackPoint(
            lat = json.requiredDouble("lat"),
            lon = json.requiredDouble("lon"),
            ele = json.optionalDouble("ele"),
            time = json.optionalLong("time"),
            hr = json.optionalInt("hr"),
        )
    }
}

/** Kennzahlen einer Fahrt. */
data class RideStats(
    val distanceKm: Double,
    val ascentM: Double,
    val descentM: Double,
    val durationS: Int? = null,
    val movingTimeS: Int? = null,
    val avgSpeedKmh: Double? = null,
    /** Durchschnittliche Herzfrequenz in bpm, falls bekannt. */
    val avgHrBpm: Int? = null,
    /** Maximale Herzfrequenz in bpm, falls bekannt. */
    val maxHrBpm: Int? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("distanceKm", distanceKm)
        // durationS/movingTimeS/avgSpeedKmh werden immer geschrieben (auch als
        // explizites JSON-`null`) — nur avgHrBpm/maxHrBpm werden bei Abwesenheit
        // ganz weggelassen. So bleibt das JSON fuer Touren ohne Herzfrequenz
        // identisch zum bisherigen Format (Sync-Server, Web-App).
        put("durationS", durationS)
        put("movingTimeS", movingTimeS)
        put("avgSpeedKmh", avgSpeedKmh)
        put("ascentM", ascentM)
        put("descentM", descentM)
        avgHrBpm?.let { put("avgHrBpm", it) }
        maxHrBpm?.let { put("maxHrBpm", it) }
    }

    companion object {
        /** Leere Stats wie `_emptyStats`/der Default-Fallback in `Ride.fromJson`. */
        val empty = RideStats(distanceKm = 0.0, ascentM = 0.0, descentM = 0.0)

        fun fromJson(json: JsonObject): RideStats = RideStats(
            distanceKm = json.optionalDouble("distanceKm") ?: 0.0,
            durationS = json.optionalInt("durationS"),
            movingTimeS = json.optionalInt("movingTimeS"),
            avgSpeedKmh = json.optionalDouble("avgSpeedKmh"),
            ascentM = json.optionalDouble("ascentM") ?: 0.0,
            descentM = json.optionalDouble("descentM") ?: 0.0,
            avgHrBpm = json.optionalInt("avgHrBpm"),
            maxHrBpm = json.optionalInt("maxHrBpm"),
        )
    }
}

/** Eine aufgezeichnete Fahrt. */
data class Ride(
    val id: String,
    val name: String,
    /** ms seit Epoch. */
    val createdAt: Long,
    val stats: RideStats,
    val points: List<TrackPoint> = emptyList(),
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("name", name)
        put("createdAt", createdAt)
        put("points", buildJsonArray { points.forEach { add(it.toJson()) } })
        put("stats", stats.toJson())
    }

    companion object {
        fun fromJson(json: JsonObject): Ride = Ride(
            id = json.requiredString("id"),
            name = json.requiredString("name"),
            createdAt = json.requiredLong("createdAt"),
            points = json.requiredArray("points").map { TrackPoint.fromJson(it.asRequiredObject()) },
            // Entspricht Darts `json['stats'] is Map<String, dynamic> ? ... : const RideStats(...)`:
            // fehlt 'stats' oder ist es kein Objekt, wird lautlos auf leere Stats zurueckgefallen.
            stats = (json.fieldOrNull("stats") as? JsonObject)?.let { RideStats.fromJson(it) } ?: RideStats.empty,
        )
    }
}

/** Fitness-Stufen wie in der Flutter-App und der Web-Referenz. */
enum class FitnessLevel(
    /** Exakter Dart-Enum-Name (`FitnessLevel.name`), wie er im JSON steht. */
    val jsonName: String,
    val label: String,
) {
    EINSTEIGER("einsteiger", "Einsteiger"),
    FORTGESCHRITTEN("fortgeschritten", "Fortgeschritten"),
    AMBITIONIERT("ambitioniert", "Ambitioniert"),
    ;

    companion object {
        /** Entspricht Darts `FitnessLevel.values.byName(...)`: wirft bei unbekanntem Namen. */
        fun fromJsonName(name: String): FitnessLevel =
            entries.firstOrNull { it.jsonName == name }
                ?: throw MissingOrInvalidFieldException("Unbekannter FitnessLevel: '$name'")
    }
}

/** Entspricht der Dart-Konstante `levelLabels`. */
val levelLabels: Map<FitnessLevel, String> = FitnessLevel.entries.associateWith { it.label }

/** Ergebnis von [assessFitness]. */
data class FitnessAssessment(
    val level: FitnessLevel,
    val weeklyKm: Double,
    val weeklyHm: Double,
    val weeklyRides: Double,
    val longestRideKm: Double,
    val rideCount: Int,
)

/** Trainingsziel: Zieldistanz zu einem bestimmten Datum. */
data class Goal(
    val name: String,
    val distanceKm: Double,
    val ascentM: Double? = null,
    /** ms seit Epoch. */
    val date: Long,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("name", name)
        put("distanceKm", distanceKm)
        put("ascentM", ascentM)
        put("date", date)
    }

    companion object {
        fun fromJson(json: JsonObject): Goal = Goal(
            name = json.requiredString("name"),
            distanceKm = json.requiredDouble("distanceKm"),
            ascentM = json.optionalDouble("ascentM"),
            date = json.requiredLong("date"),
        )
    }
}

/** Art einer Trainingswoche. */
enum class WeekKind(
    /** Exakter Dart-Enum-Name (`WeekKind.name`), wie er im JSON steht. */
    val jsonName: String,
    val label: String,
) {
    AUFBAU("aufbau", "Aufbau"),
    ERHOLUNG("erholung", "Erholung"),
    TAPER("taper", "Taper"),
    ZIELWOCHE("zielwoche", "Zielwoche"),
    ;

    companion object {
        /** Entspricht Darts `WeekKind.values.byName(...)`: wirft bei unbekanntem Namen. */
        fun fromJsonName(name: String): WeekKind =
            entries.firstOrNull { it.jsonName == name }
                ?: throw MissingOrInvalidFieldException("Unbekannter WeekKind: '$name'")
    }
}

/** Entspricht der Dart-Konstante `weekKindLabels`. */
val weekKindLabels: Map<WeekKind, String> = WeekKind.entries.associateWith { it.label }

/** Eine einzelne Trainingseinheit innerhalb einer [TrainingWeek]. */
data class TrainingSession(
    val day: String,
    val title: String,
    val description: String,
    val targetKm: Int,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("day", day)
        put("title", title)
        put("description", description)
        put("targetKm", targetKm)
    }

    companion object {
        fun fromJson(json: JsonObject): TrainingSession = TrainingSession(
            day = json.requiredString("day"),
            title = json.requiredString("title"),
            description = json.requiredString("description"),
            targetKm = json.requiredInt("targetKm"),
        )
    }
}

/** Eine Trainingswoche innerhalb eines [TrainingPlan]. */
data class TrainingWeek(
    val index: Int,
    /** Montag 00:00 lokal, ms seit Epoch (inklusiv). */
    val start: Long,
    /** Folgemontag 00:00 lokal, ms seit Epoch (exklusiv). */
    val end: Long,
    val kind: WeekKind,
    val targetKm: Int,
    val sessions: List<TrainingSession>,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("index", index)
        put("start", start)
        put("end", end)
        put("kind", kind.jsonName)
        put("targetKm", targetKm)
        put("sessions", buildJsonArray { sessions.forEach { add(it.toJson()) } })
    }

    companion object {
        fun fromJson(json: JsonObject): TrainingWeek = TrainingWeek(
            index = json.requiredInt("index"),
            start = json.requiredLong("start"),
            end = json.requiredLong("end"),
            kind = WeekKind.fromJsonName(json.requiredString("kind")),
            targetKm = json.requiredInt("targetKm"),
            sessions = json.requiredArray("sessions").map { TrainingSession.fromJson(it.asRequiredObject()) },
        )
    }
}

/** Ein vollstaendiger Trainingsplan auf ein [Goal] hin. */
data class TrainingPlan(
    val createdAt: Long,
    val goal: Goal,
    val level: FitnessLevel,
    val weeks: List<TrainingWeek>,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("createdAt", createdAt)
        put("goal", goal.toJson())
        put("level", level.jsonName)
        put("weeks", buildJsonArray { weeks.forEach { add(it.toJson()) } })
    }

    companion object {
        fun fromJson(json: JsonObject): TrainingPlan = TrainingPlan(
            createdAt = json.requiredLong("createdAt"),
            goal = Goal.fromJson(json.requiredObject("goal")),
            level = FitnessLevel.fromJsonName(json.requiredString("level")),
            weeks = json.requiredArray("weeks").map { TrainingWeek.fromJson(it.asRequiredObject()) },
        )
    }
}

/**
 * Ein Wegpunkt fuer die Routenplanung. Rein In-Memory — im Original-Dart-Code
 * ohne `toJson`/`fromJson`, daher auch hier ohne Serialisierung.
 */
data class Waypoint(val lat: Double, val lon: Double)

/** Eine geplante Route. Rein In-Memory, siehe [Waypoint]. */
data class PlannedRoute(
    val points: List<TrackPoint>,
    val distanceKm: Double,
    val ascentM: Double,
)

/** Navigationszustand waehrend einer laufenden Fahrt. Rein In-Memory, siehe [Waypoint]. */
data class NavState(
    val nearestIndex: Int,
    val distanceToRouteM: Double,
    val doneKm: Double,
    val remainingKm: Double,
    val offRoute: Boolean,
)
