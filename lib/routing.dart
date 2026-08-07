/// Routenberechnung über den öffentlichen BRouter-Server.
///
/// Spiegelt die Logik der früheren Web-App (routing.ts), damit Routen
/// gleich berechnet werden, egal ob über die Flutter-App oder den Browser.
library;

import 'dart:convert';

import 'package:http/http.dart' as http;

import 'brouter_profiles.dart';
import 'models.dart';

enum BikeType { gravel, rennrad }

enum WayPreference { gemischt, schotter, asphalt, radwege, kuerzester }

const bikeTypeLabels = {
  BikeType.gravel: 'Gravel',
  BikeType.rennrad: 'Rennrad',
};

const wayPreferenceLabels = {
  WayPreference.gemischt: 'Gemischt (Standard)',
  WayPreference.schotter: 'Schotter & Kieswege',
  WayPreference.asphalt: 'Asphalt & Straße',
  WayPreference.radwege: 'Radwege & verkehrsarm',
  WayPreference.kuerzester: 'Kürzester Weg',
};

/// Sentinel-Profilname für das eingebettete Gravel-Custom-Profil.
///
/// Kein echter BRouter-Profilname: [fetchRoute] lädt dafür zunächst
/// [gravelProfileText] auf den Server hoch und routet dann mit der vom
/// Server vergebenen `profileid`.
const String customGravelProfile = 'custom:gravel';

/// Öffentliches Profil, auf das zurückgefallen wird, wenn das Hochladen
/// des Custom-Profils scheitert.
const String _fallbackProfile = 'trekking';

/// Ermittelt den öffentlichen BRouter-Profilnamen für eine Kombination aus
/// Fahrrad-Typ und Weg-Präferenz.
String brouterProfile(BikeType bike, WayPreference way) {
  switch (bike) {
    case BikeType.gravel:
      switch (way) {
        case WayPreference.gemischt:
          return 'trekking';
        case WayPreference.schotter:
          return customGravelProfile;
        case WayPreference.asphalt:
          return 'fastbike-lowtraffic';
        case WayPreference.radwege:
          return 'safety';
        case WayPreference.kuerzester:
          return 'shortest';
      }
    case BikeType.rennrad:
      switch (way) {
        case WayPreference.gemischt:
          return 'fastbike';
        case WayPreference.schotter:
          return customGravelProfile;
        case WayPreference.asphalt:
          return 'fastbike';
        case WayPreference.radwege:
          return 'fastbike-lowtraffic';
        case WayPreference.kuerzester:
          return 'shortest';
      }
  }
}

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

/// Zwischengespeicherte `profileid` des hochgeladenen Gravel-Custom-Profils.
///
/// Der öffentliche Server verwirft hochgeladene Profile nach einiger Zeit,
/// deshalb ist das nur ein Best-Effort-Cache: schlägt ein Routing damit
/// fehl, wird das Profil neu hochgeladen.
String? _customGravelProfileId;

/// Setzt den Profil-Cache zurück (nur für Tests gedacht).
void resetCustomProfileCacheForTesting() {
  _customGravelProfileId = null;
}

/// Lädt das Gravel-Custom-Profil hoch und liefert die vom Server vergebene
/// `profileid` – oder `null`, wenn das Hochladen scheitert.
Future<String?> _uploadGravelProfile(http.Client client) async {
  http.Response response;
  try {
    response = await client.post(
      Uri.parse('https://brouter.de/brouter/profile'),
      body: gravelProfileText(),
    );
  } catch (_) {
    return null;
  }

  if (response.statusCode < 200 || response.statusCode >= 300) {
    return null;
  }

  dynamic data;
  try {
    data = jsonDecode(response.body);
  } catch (_) {
    return null;
  }
  if (data is! Map) {
    return null;
  }
  // Der Server liefert im Erfolgsfall teils ein leeres `error`-Feld mit.
  final error = data['error'];
  if (error != null && !(error is String && error.trim().isEmpty)) {
    return null;
  }
  final id = data['profileid'];
  if (id is! String || id.isEmpty) {
    return null;
  }
  return id;
}

/// Führt den eigentlichen Routing-Request aus.
Future<http.Response> _requestRoute(
  String lonlats,
  String profileId,
  http.Client client,
) async {
  final url = Uri.parse(
    'https://brouter.de/brouter?lonlats=$lonlats&profile=$profileId&alternativeidx=0&format=geojson',
  );
  try {
    return await client.get(url);
  } catch (_) {
    throw Exception('Routing-Server nicht erreichbar. Bist du online?');
  }
}

bool _isOk(http.Response response) =>
    response.statusCode >= 200 && response.statusCode < 300;

PlannedRoute _parseRouteResponse(http.Response response) {
  if (!_isOk(response)) {
    throw Exception('Route konnte nicht berechnet werden: ${response.body}');
  }
  return parseBrouterGeoJson(response.body);
}

/// Routet mit dem eingebetteten Gravel-Custom-Profil.
///
/// Ablauf: Profil hochladen (bzw. gecachte `profileid` nutzen) → routen.
/// Schlägt das Routing mit der Custom-ID fehl (der Server verwirft Profile
/// nach einiger Zeit), wird einmal neu hochgeladen und erneut versucht.
/// Klappt auch das nicht, wird auf das öffentliche Profil `trekking`
/// zurückgefallen, damit der Nutzer trotzdem eine Route bekommt.
Future<PlannedRoute> _fetchRouteWithCustomGravel(
  String lonlats,
  http.Client client,
) async {
  var profileId = _customGravelProfileId;
  if (profileId == null) {
    profileId = await _uploadGravelProfile(client);
    _customGravelProfileId = profileId;
  }

  if (profileId != null) {
    final response = await _requestRoute(lonlats, profileId, client);
    if (_isOk(response)) {
      return parseBrouterGeoJson(response.body);
    }

    // Vermutlich wurde das hochgeladene Profil serverseitig verworfen:
    // einmal neu hochladen und wiederholen.
    _customGravelProfileId = null;
    final freshId = await _uploadGravelProfile(client);
    if (freshId != null) {
      _customGravelProfileId = freshId;
      final retry = await _requestRoute(lonlats, freshId, client);
      if (_isOk(retry)) {
        return parseBrouterGeoJson(retry.body);
      }
      _customGravelProfileId = null;
    }
  }

  // Fallback: öffentliches Profil, damit immer eine Route herauskommt.
  return _parseRouteResponse(
    await _requestRoute(lonlats, _fallbackProfile, client),
  );
}

/// Berechnet eine Route über den öffentlichen BRouter-Server.
///
/// [profileId] ist entweder ein öffentlicher BRouter-Profilname oder der
/// Sentinel [customGravelProfile]; im zweiten Fall wird das eingebettete
/// Gravel-Profil zunächst auf den Server hochgeladen.
///
/// [client] kann für Tests injiziert werden; produktiv wird intern ein
/// eigener [http.Client] erzeugt und wieder geschlossen.
Future<PlannedRoute> fetchRoute(
  List<Waypoint> waypoints,
  String profileId, {
  http.Client? client,
}) async {
  if (waypoints.length < 2) {
    throw Exception('Mindestens zwei Wegpunkte nötig.');
  }

  final lonlats = waypoints
      .map((wp) => '${wp.lon.toStringAsFixed(6)},${wp.lat.toStringAsFixed(6)}')
      .join('|');

  final ownClient = client == null;
  final httpClient = client ?? http.Client();
  try {
    if (profileId == customGravelProfile) {
      return await _fetchRouteWithCustomGravel(lonlats, httpClient);
    }
    return _parseRouteResponse(
      await _requestRoute(lonlats, profileId, httpClient),
    );
  } finally {
    if (ownClient) {
      httpClient.close();
    }
  }
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
