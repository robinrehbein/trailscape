/// Routenberechnung über den öffentlichen BRouter-Server.
///
/// Spiegelt die Logik der früheren Web-App (routing.ts), damit Routen
/// gleich berechnet werden, egal ob über die Flutter-App oder den Browser.
library;

import 'dart:convert';

import 'package:http/http.dart' as http;

import 'models.dart';

const Map<RoutingProfile, String> _profileNames = {
  RoutingProfile.trekking: 'trekking',
  RoutingProfile.fastbike: 'fastbike',
  RoutingProfile.shortest: 'shortest',
};

double _parseNumericProperty(dynamic value) {
  if (value is num && value.isFinite) {
    return value.toDouble();
  }
  if (value is String) {
    final parsed = double.tryParse(value);
    if (parsed != null && parsed.isFinite) {
      return parsed;
    }
  }
  return 0;
}

/// Berechnet eine Route über den öffentlichen BRouter-Server.
///
/// [client] kann für Tests injiziert werden; produktiv wird intern ein
/// eigener [http.Client] erzeugt und wieder geschlossen.
Future<PlannedRoute> fetchRoute(
  List<Waypoint> waypoints,
  RoutingProfile profile, {
  http.Client? client,
}) async {
  if (waypoints.length < 2) {
    throw Exception('Mindestens zwei Wegpunkte nötig.');
  }

  final lonlats = waypoints
      .map((wp) => '${wp.lon.toStringAsFixed(6)},${wp.lat.toStringAsFixed(6)}')
      .join('|');
  final profileName = _profileNames[profile]!;
  final url = Uri.parse(
    'https://brouter.de/brouter?lonlats=$lonlats&profile=$profileName&alternativeidx=0&format=geojson',
  );

  final ownClient = client == null;
  final httpClient = client ?? http.Client();
  http.Response response;
  try {
    try {
      response = await httpClient.get(url);
    } catch (_) {
      throw Exception('Routing-Server nicht erreichbar. Bist du online?');
    }
  } finally {
    if (ownClient) {
      httpClient.close();
    }
  }

  if (response.statusCode < 200 || response.statusCode >= 300) {
    throw Exception('Route konnte nicht berechnet werden: ${response.body}');
  }

  return parseBrouterGeoJson(response.body);
}

/// Parst ein GeoJSON-Antwortdokument des BRouter-Servers in eine
/// [PlannedRoute]. Öffentlich, damit Tests direkt gegen gecannte
/// Server-Antworten prüfen können.
PlannedRoute parseBrouterGeoJson(String body) {
  const unexpectedFormat = 'Unerwartete Antwort vom Routing-Server.';

  dynamic data;
  try {
    data = jsonDecode(body);
  } catch (_) {
    throw Exception(unexpectedFormat);
  }

  if (data is! Map || data['features'] is! List) {
    throw Exception(unexpectedFormat);
  }

  final features = data['features'] as List;
  if (features.isEmpty) {
    throw Exception(unexpectedFormat);
  }
  final feature = features[0];
  if (feature is! Map || !feature.containsKey('geometry') || !feature.containsKey('properties')) {
    throw Exception(unexpectedFormat);
  }

  final geometry = feature['geometry'];
  if (geometry is! Map || geometry['coordinates'] is! List) {
    throw Exception(unexpectedFormat);
  }

  final coordinates = geometry['coordinates'] as List;
  final points = <TrackPoint>[];
  for (final coord in coordinates) {
    if (coord is! List || coord.length < 2) {
      throw Exception(unexpectedFormat);
    }
    final lon = coord[0];
    final lat = coord[1];
    if (lon is! num || lat is! num) {
      throw Exception(unexpectedFormat);
    }
    double? ele;
    if (coord.length > 2) {
      final rawEle = coord[2];
      if (rawEle is num && rawEle.isFinite) {
        ele = rawEle.toDouble();
      }
    }
    points.add(TrackPoint(lat: lat.toDouble(), lon: lon.toDouble(), ele: ele));
  }

  final properties = feature['properties'];
  final props = properties is Map ? properties : const {};

  final distanceM = _parseNumericProperty(props['track-length']);
  final ascentM = _parseNumericProperty(props['filtered ascend']);

  return PlannedRoute(
    points: points,
    distanceKm: distanceM / 1000,
    ascentM: ascentM,
  );
}
