/// Fitness-Einschätzung aus den zuletzt aufgezeichneten Fahrten.
///
/// Semantisch identisch zur Web-App-Referenz (fitness.ts): Betrachtet wird ein
/// gleitendes 8-Wochen-Fenster; die Wochenwerte sind Mittelwerte über dieses
/// Fenster (nicht über die tatsächlich gefahrenen Wochen).
library;

import 'models.dart';

const int _windowWeeks = 8;
const int _windowMs = _windowWeeks * 7 * 24 * 60 * 60 * 1000;

double _round1(double value) => (value * 10).round() / 10;

FitnessLevel _determineLevel(
  double weeklyKm,
  double longestRideKm,
  double weeklyRides,
) {
  if (weeklyKm >= 100 && longestRideKm >= 70 && weeklyRides >= 2.5) {
    return FitnessLevel.ambitioniert;
  }
  if (weeklyKm >= 50 && longestRideKm >= 35 && weeklyRides >= 1.5) {
    return FitnessLevel.fortgeschritten;
  }
  return FitnessLevel.einsteiger;
}

/// Bewertet die Form anhand der Fahrten der letzten 8 Wochen.
///
/// [now] ist ein Zeitstempel in ms seit Epoch; ohne Angabe wird die aktuelle
/// Zeit verwendet.
FitnessAssessment assessFitness(List<Ride> rides, {int? now}) {
  final nowMs = now ?? DateTime.now().millisecondsSinceEpoch;
  final cutoff = nowMs - _windowMs;

  final relevantRides = rides
      .where((ride) =>
          ride.createdAt >= cutoff &&
          ride.createdAt <= nowMs &&
          ride.stats.distanceKm > 0)
      .toList();

  final rideCount = relevantRides.length;

  if (rideCount == 0) {
    return const FitnessAssessment(
      level: FitnessLevel.einsteiger,
      weeklyKm: 0,
      weeklyHm: 0,
      weeklyRides: 0,
      longestRideKm: 0,
      rideCount: 0,
    );
  }

  var totalKm = 0.0;
  var totalHm = 0.0;
  var longestRideKm = 0.0;

  for (final ride in relevantRides) {
    totalKm += ride.stats.distanceKm;
    totalHm += ride.stats.ascentM;
    if (ride.stats.distanceKm > longestRideKm) {
      longestRideKm = ride.stats.distanceKm;
    }
  }

  final weeklyKm = _round1(totalKm / _windowWeeks);
  final weeklyHm = (totalHm / _windowWeeks).roundToDouble();
  final weeklyRides = _round1(rideCount / _windowWeeks);

  return FitnessAssessment(
    level: _determineLevel(weeklyKm, longestRideKm, weeklyRides),
    weeklyKm: weeklyKm,
    weeklyHm: weeklyHm,
    weeklyRides: weeklyRides,
    longestRideKm: _round1(longestRideKm),
    rideCount: rideCount,
  );
}
