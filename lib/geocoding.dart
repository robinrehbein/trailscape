/// Ortssuche über den öffentlichen Nominatim-Server (OpenStreetMap).
///
/// Hält sich an die Nominatim-Nutzungsrichtlinien (aussagekräftiger
/// User-Agent-Header), damit die App nicht gesperrt wird.
library;

import 'dart:convert';

import 'package:http/http.dart' as http;

const _userAgent = 'Trailscape/1.0 (github.com/robinrehbein/trailscape)';

class GeoResult {
  const GeoResult({required this.displayName, required this.lat, required this.lon});

  final String displayName;
  final double lat;
  final double lon;
}

/// Sucht Orte über Nominatim.
///
/// [client] kann für Tests injiziert werden; produktiv wird intern ein
/// eigener [http.Client] erzeugt und wieder geschlossen.
Future<List<GeoResult>> searchPlaces(String query, {http.Client? client}) async {
  final trimmed = query.trim();
  if (trimmed.isEmpty) {
    return [];
  }

  final url = Uri.parse('https://nominatim.openstreetmap.org/search').replace(
    queryParameters: {
      'q': trimmed,
      'format': 'jsonv2',
      'limit': '5',
      'accept-language': 'de',
    },
  );

  final ownClient = client == null;
  final httpClient = client ?? http.Client();
  http.Response response;
  try {
    try {
      response = await httpClient.get(url, headers: {'User-Agent': _userAgent});
    } catch (_) {
      throw Exception('Ortssuche nicht erreichbar. Bist du online?');
    }
  } finally {
    if (ownClient) {
      httpClient.close();
    }
  }

  if (response.statusCode < 200 || response.statusCode >= 300) {
    throw Exception('Ortssuche fehlgeschlagen (HTTP ${response.statusCode}).');
  }

  dynamic data;
  try {
    data = jsonDecode(response.body);
  } catch (_) {
    throw Exception('Unerwartete Antwort der Ortssuche.');
  }

  if (data is! List) {
    throw Exception('Unerwartete Antwort der Ortssuche.');
  }

  final results = <GeoResult>[];
  for (final entry in data) {
    if (entry is! Map) {
      continue;
    }
    final displayName = entry['display_name'];
    final latStr = entry['lat'];
    final lonStr = entry['lon'];
    if (displayName is! String || latStr is! String || lonStr is! String) {
      continue;
    }
    final lat = double.tryParse(latStr);
    final lon = double.tryParse(lonStr);
    if (lat == null || lon == null) {
      continue;
    }
    results.add(GeoResult(displayName: displayName, lat: lat, lon: lon));
  }

  return results;
}
