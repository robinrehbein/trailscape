package de.trailscape.core

/**
 * Fitness-Einschaetzung aus den zuletzt aufgezeichneten Fahrten.
 *
 * 1:1-Portierung von `lib/fitness.dart` und damit semantisch identisch zur
 * Web-App-Referenz (fitness.ts): Betrachtet wird ein gleitendes
 * 8-Wochen-Fenster; die Wochenwerte sind Mittelwerte ueber dieses Fenster
 * (nicht ueber die tatsaechlich gefahrenen Wochen).
 */
private const val WINDOW_WEEKS = 8

private const val WINDOW_MS = WINDOW_WEEKS * 7L * 24L * 60L * 60L * 1000L

private fun round1(value: Double): Double = dartRound(value * 10) / 10

private fun determineLevel(
    weeklyKm: Double,
    longestRideKm: Double,
    weeklyRides: Double,
): FitnessLevel {
    if (weeklyKm >= 100 && longestRideKm >= 70 && weeklyRides >= 2.5) {
        return FitnessLevel.AMBITIONIERT
    }
    if (weeklyKm >= 50 && longestRideKm >= 35 && weeklyRides >= 1.5) {
        return FitnessLevel.FORTGESCHRITTEN
    }
    return FitnessLevel.EINSTEIGER
}

/**
 * Bewertet die Form anhand der Fahrten der letzten 8 Wochen.
 *
 * Gezaehlt wird nur, was auch gefahren wurde: Gespeicherte Planungen
 * ([Ride.planned]) fallen ueber [riddenRides] heraus. Sie wuerden sonst
 * Wochenumfang, laengste Fahrt und damit die Fitness-Stufe anheben — und aus
 * der Stufe entsteht das Startvolumen des naechsten Trainingsplans.
 *
 * [now] ist ein Zeitstempel in ms seit Epoch; ohne Angabe wird die aktuelle
 * Zeit verwendet.
 */
fun assessFitness(
    rides: List<Ride>,
    now: Long? = null,
): FitnessAssessment {
    val nowMs = now ?: System.currentTimeMillis()
    val cutoff = nowMs - WINDOW_MS

    val relevantRides = riddenRides(rides).filter { ride ->
        ride.createdAt >= cutoff &&
            ride.createdAt <= nowMs &&
            ride.stats.distanceKm > 0
    }

    val rideCount = relevantRides.size

    if (rideCount == 0) {
        return FitnessAssessment(
            level = FitnessLevel.EINSTEIGER,
            weeklyKm = 0.0,
            weeklyHm = 0.0,
            weeklyRides = 0.0,
            longestRideKm = 0.0,
            rideCount = 0,
        )
    }

    var totalKm = 0.0
    var totalHm = 0.0
    var longestRideKm = 0.0

    for (ride in relevantRides) {
        totalKm += ride.stats.distanceKm
        totalHm += ride.stats.ascentM
        if (ride.stats.distanceKm > longestRideKm) {
            longestRideKm = ride.stats.distanceKm
        }
    }

    val weeklyKm = round1(totalKm / WINDOW_WEEKS)
    val weeklyHm = dartRound(totalHm / WINDOW_WEEKS)
    val weeklyRides = round1(rideCount.toDouble() / WINDOW_WEEKS)

    return FitnessAssessment(
        level = determineLevel(weeklyKm, longestRideKm, weeklyRides),
        weeklyKm = weeklyKm,
        weeklyHm = weeklyHm,
        weeklyRides = weeklyRides,
        longestRideKm = round1(longestRideKm),
        rideCount = rideCount,
    )
}
