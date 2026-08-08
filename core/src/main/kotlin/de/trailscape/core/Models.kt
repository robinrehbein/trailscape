package de.trailscape.core

/**
 * Einzelner aufgezeichneter Trackpunkt.
 *
 * Portiert aus `lib/models.dart` (Flutter-App). Serialisierung fehlt hier
 * bewusst: Persistenz kommt in einer spaeteren Phase des Rewrites.
 */
data class TrackPoint(
    val lat: Double,
    val lon: Double,
    val ele: Double? = null,
    /** ms seit Epoch. */
    val time: Long? = null,
    /** Herzfrequenz in bpm, falls bekannt. */
    val hr: Int? = null,
)

/** Kennzahlen einer Fahrt. Portiert aus `lib/models.dart`. */
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
)

/** Eine aufgezeichnete Fahrt. Portiert aus `lib/models.dart`. */
data class Ride(
    val id: String,
    val name: String,
    /** ms seit Epoch. */
    val createdAt: Long,
    val stats: RideStats,
    val points: List<TrackPoint> = emptyList(),
)

/** Fitness-Stufen wie in der Flutter-App und der Web-Referenz. */
enum class FitnessLevel(val label: String) {
    EINSTEIGER("Einsteiger"),
    FORTGESCHRITTEN("Fortgeschritten"),
    AMBITIONIERT("Ambitioniert"),
}

/** Ergebnis von [assessFitness]. */
data class FitnessAssessment(
    val level: FitnessLevel,
    val weeklyKm: Double,
    val weeklyHm: Double,
    val weeklyRides: Double,
    val longestRideKm: Double,
    val rideCount: Int,
)
