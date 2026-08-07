/// Zentrale Datentypen von Trailscape.
///
/// Die JSON-Formate sind kompatibel zum Selfhost-Sync-Server (server/)
/// und zur früheren Web-App, damit bestehende Daten synchronisierbar bleiben.
library;

class TrackPoint {
  const TrackPoint({required this.lat, required this.lon, this.ele, this.time});

  final double lat;
  final double lon;

  /// Höhe in Metern.
  final double? ele;

  /// Zeitstempel in ms seit Epoch.
  final int? time;

  Map<String, dynamic> toJson() => {
        'lat': lat,
        'lon': lon,
        if (ele != null) 'ele': ele,
        if (time != null) 'time': time,
      };

  factory TrackPoint.fromJson(Map<String, dynamic> json) => TrackPoint(
        lat: (json['lat'] as num).toDouble(),
        lon: (json['lon'] as num).toDouble(),
        ele: (json['ele'] as num?)?.toDouble(),
        time: (json['time'] as num?)?.toInt(),
      );
}

class RideStats {
  const RideStats({
    required this.distanceKm,
    this.durationS,
    this.movingTimeS,
    this.avgSpeedKmh,
    required this.ascentM,
    required this.descentM,
  });

  final double distanceKm;
  final int? durationS;
  final int? movingTimeS;
  final double? avgSpeedKmh;
  final double ascentM;
  final double descentM;

  Map<String, dynamic> toJson() => {
        'distanceKm': distanceKm,
        'durationS': durationS,
        'movingTimeS': movingTimeS,
        'avgSpeedKmh': avgSpeedKmh,
        'ascentM': ascentM,
        'descentM': descentM,
      };

  factory RideStats.fromJson(Map<String, dynamic> json) => RideStats(
        distanceKm: (json['distanceKm'] as num?)?.toDouble() ?? 0,
        durationS: (json['durationS'] as num?)?.toInt(),
        movingTimeS: (json['movingTimeS'] as num?)?.toInt(),
        avgSpeedKmh: (json['avgSpeedKmh'] as num?)?.toDouble(),
        ascentM: (json['ascentM'] as num?)?.toDouble() ?? 0,
        descentM: (json['descentM'] as num?)?.toDouble() ?? 0,
      );
}

class Ride {
  const Ride({
    required this.id,
    required this.name,
    required this.createdAt,
    required this.points,
    required this.stats,
  });

  final String id;
  final String name;

  /// ms seit Epoch.
  final int createdAt;
  final List<TrackPoint> points;
  final RideStats stats;

  Map<String, dynamic> toJson() => {
        'id': id,
        'name': name,
        'createdAt': createdAt,
        'points': points.map((p) => p.toJson()).toList(),
        'stats': stats.toJson(),
      };

  factory Ride.fromJson(Map<String, dynamic> json) => Ride(
        id: json['id'] as String,
        name: json['name'] as String,
        createdAt: (json['createdAt'] as num).toInt(),
        points: (json['points'] as List)
            .map((p) => TrackPoint.fromJson(p as Map<String, dynamic>))
            .toList(),
        stats: json['stats'] is Map<String, dynamic>
            ? RideStats.fromJson(json['stats'] as Map<String, dynamic>)
            : const RideStats(distanceKm: 0, ascentM: 0, descentM: 0),
      );
}

enum FitnessLevel { einsteiger, fortgeschritten, ambitioniert }

const levelLabels = {
  FitnessLevel.einsteiger: 'Einsteiger',
  FitnessLevel.fortgeschritten: 'Fortgeschritten',
  FitnessLevel.ambitioniert: 'Ambitioniert',
};

class FitnessAssessment {
  const FitnessAssessment({
    required this.level,
    required this.weeklyKm,
    required this.weeklyHm,
    required this.weeklyRides,
    required this.longestRideKm,
    required this.rideCount,
  });

  final FitnessLevel level;
  final double weeklyKm;
  final double weeklyHm;
  final double weeklyRides;
  final double longestRideKm;
  final int rideCount;
}

class Goal {
  const Goal({
    required this.name,
    required this.distanceKm,
    this.ascentM,
    required this.date,
  });

  final String name;
  final double distanceKm;
  final double? ascentM;

  /// ms seit Epoch.
  final int date;

  Map<String, dynamic> toJson() => {
        'name': name,
        'distanceKm': distanceKm,
        'ascentM': ascentM,
        'date': date,
      };

  factory Goal.fromJson(Map<String, dynamic> json) => Goal(
        name: json['name'] as String,
        distanceKm: (json['distanceKm'] as num).toDouble(),
        ascentM: (json['ascentM'] as num?)?.toDouble(),
        date: (json['date'] as num).toInt(),
      );
}

enum WeekKind { aufbau, erholung, taper, zielwoche }

const weekKindLabels = {
  WeekKind.aufbau: 'Aufbau',
  WeekKind.erholung: 'Erholung',
  WeekKind.taper: 'Taper',
  WeekKind.zielwoche: 'Zielwoche',
};

class TrainingSession {
  const TrainingSession({
    required this.day,
    required this.title,
    required this.description,
    required this.targetKm,
  });

  final String day;
  final String title;
  final String description;
  final int targetKm;

  Map<String, dynamic> toJson() => {
        'day': day,
        'title': title,
        'description': description,
        'targetKm': targetKm,
      };

  factory TrainingSession.fromJson(Map<String, dynamic> json) =>
      TrainingSession(
        day: json['day'] as String,
        title: json['title'] as String,
        description: json['description'] as String,
        targetKm: (json['targetKm'] as num).toInt(),
      );
}

class TrainingWeek {
  const TrainingWeek({
    required this.index,
    required this.start,
    required this.end,
    required this.kind,
    required this.targetKm,
    required this.sessions,
  });

  final int index;

  /// Montag 00:00 lokal, ms seit Epoch (inklusiv).
  final int start;

  /// Folgemontag 00:00 lokal, ms seit Epoch (exklusiv).
  final int end;
  final WeekKind kind;
  final int targetKm;
  final List<TrainingSession> sessions;

  Map<String, dynamic> toJson() => {
        'index': index,
        'start': start,
        'end': end,
        'kind': kind.name,
        'targetKm': targetKm,
        'sessions': sessions.map((s) => s.toJson()).toList(),
      };

  factory TrainingWeek.fromJson(Map<String, dynamic> json) => TrainingWeek(
        index: (json['index'] as num).toInt(),
        start: (json['start'] as num).toInt(),
        end: (json['end'] as num).toInt(),
        kind: WeekKind.values.byName(json['kind'] as String),
        targetKm: (json['targetKm'] as num).toInt(),
        sessions: (json['sessions'] as List)
            .map((s) => TrainingSession.fromJson(s as Map<String, dynamic>))
            .toList(),
      );
}

class TrainingPlan {
  const TrainingPlan({
    required this.createdAt,
    required this.goal,
    required this.level,
    required this.weeks,
  });

  final int createdAt;
  final Goal goal;
  final FitnessLevel level;
  final List<TrainingWeek> weeks;

  Map<String, dynamic> toJson() => {
        'createdAt': createdAt,
        'goal': goal.toJson(),
        'level': level.name,
        'weeks': weeks.map((w) => w.toJson()).toList(),
      };

  factory TrainingPlan.fromJson(Map<String, dynamic> json) => TrainingPlan(
        createdAt: (json['createdAt'] as num).toInt(),
        goal: Goal.fromJson(json['goal'] as Map<String, dynamic>),
        level: FitnessLevel.values.byName(json['level'] as String),
        weeks: (json['weeks'] as List)
            .map((w) => TrainingWeek.fromJson(w as Map<String, dynamic>))
            .toList(),
      );
}

class Waypoint {
  const Waypoint({required this.lat, required this.lon});

  final double lat;
  final double lon;
}

class PlannedRoute {
  const PlannedRoute({
    required this.points,
    required this.distanceKm,
    required this.ascentM,
  });

  final List<TrackPoint> points;
  final double distanceKm;
  final double ascentM;
}

class NavState {
  const NavState({
    required this.nearestIndex,
    required this.distanceToRouteM,
    required this.doneKm,
    required this.remainingKm,
    required this.offRoute,
  });

  final int nearestIndex;
  final double distanceToRouteM;
  final double doneKm;
  final double remainingKm;
  final bool offRoute;
}
