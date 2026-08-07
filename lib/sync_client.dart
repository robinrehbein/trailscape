/// Client für den optionalen Trailscape-Selfhost-Sync-Server (server/).
///
/// Spiegelt die Logik der früheren Web-App (sync.ts): Konfiguration wird
/// in shared_preferences abgelegt, und syncRides gleicht lokale Touren
/// gegen den Server ab (fehlende lokal hochladen, fehlende remote holen).
library;

import 'dart:convert';

import 'package:http/http.dart' as http;
import 'package:shared_preferences/shared_preferences.dart';

import 'models.dart';

const String _storageKey = 'trailscape.sync';

class SyncConfig {
  const SyncConfig({required this.url, required this.token});

  final String url;
  final String token;

  Map<String, dynamic> toJson() => {'url': url, 'token': token};

  factory SyncConfig.fromJson(Map<String, dynamic> json) => SyncConfig(
        url: json['url'] as String,
        token: json['token'] as String,
      );
}

class SyncResult {
  const SyncResult({
    required this.pushed,
    required this.pulled,
    required this.total,
  });

  final int pushed;
  final int pulled;
  final int total;
}

class _RemoteRideSummary {
  const _RemoteRideSummary({required this.id, required this.name});

  final String id;
  final String name;
}

String _normalizeUrl(String url) {
  var normalized = url.trim();
  while (normalized.endsWith('/')) {
    normalized = normalized.substring(0, normalized.length - 1);
  }
  return normalized;
}

/// Liest die gespeicherte Sync-Konfiguration aus shared_preferences.
Future<SyncConfig?> getSyncConfig() async {
  final prefs = await SharedPreferences.getInstance();
  final raw = prefs.getString(_storageKey);
  if (raw == null) {
    return null;
  }
  try {
    final parsed = jsonDecode(raw);
    if (parsed is Map &&
        parsed['url'] is String &&
        parsed['token'] is String) {
      return SyncConfig.fromJson(parsed.cast<String, dynamic>());
    }
    return null;
  } catch (_) {
    return null;
  }
}

/// Speichert (oder löscht bei `null`) die Sync-Konfiguration. Die URL wird
/// beim Speichern normalisiert (getrimmt, abschließende Slashes entfernt).
Future<void> setSyncConfig(SyncConfig? config) async {
  final prefs = await SharedPreferences.getInstance();
  if (config == null) {
    await prefs.remove(_storageKey);
    return;
  }
  final normalized = SyncConfig(
    url: _normalizeUrl(config.url),
    token: config.token.trim(),
  );
  await prefs.setString(_storageKey, jsonEncode(normalized.toJson()));
}

Map<String, String> _authHeaders(SyncConfig config) => {
      'Authorization': 'Bearer ${config.token}',
    };

Future<List<_RemoteRideSummary>> _fetchRemoteRides(
  http.Client client,
  SyncConfig config,
) async {
  http.Response response;
  try {
    response = await client.get(
      Uri.parse('${config.url}/api/rides'),
      headers: _authHeaders(config),
    );
  } catch (_) {
    throw Exception('Sync-Server nicht erreichbar.');
  }

  if (response.statusCode < 200 || response.statusCode >= 300) {
    if (response.statusCode == 401) {
      throw Exception('Token wird vom Server abgelehnt.');
    }
    throw Exception('Sync fehlgeschlagen (HTTP ${response.statusCode}).');
  }

  final data = jsonDecode(response.body) as List;
  return data
      .map((entry) => _RemoteRideSummary(
            id: entry['id'] as String,
            name: entry['name'] as String,
          ))
      .toList();
}

Future<void> _pushRide(
  http.Client client,
  SyncConfig config,
  Ride ride,
) async {
  http.Response response;
  try {
    response = await client.put(
      Uri.parse('${config.url}/api/rides/${ride.id}'),
      headers: {
        ..._authHeaders(config),
        'Content-Type': 'application/json',
      },
      body: jsonEncode(ride.toJson()),
    );
  } catch (_) {
    throw Exception(
      'Hochladen der Tour "${ride.name}" fehlgeschlagen: Sync-Server nicht erreichbar.',
    );
  }

  if (response.statusCode < 200 || response.statusCode >= 300) {
    throw Exception(
      'Hochladen der Tour "${ride.name}" fehlgeschlagen (HTTP ${response.statusCode}).',
    );
  }
}

bool _isValidRideJson(dynamic data) {
  return data is Map &&
      data['id'] is String &&
      data['name'] is String &&
      data['points'] is List;
}

Future<Ride> _pullRide(
  http.Client client,
  SyncConfig config,
  _RemoteRideSummary entry,
) async {
  http.Response response;
  try {
    response = await client.get(
      Uri.parse('${config.url}/api/rides/${entry.id}'),
      headers: _authHeaders(config),
    );
  } catch (_) {
    throw Exception(
      'Herunterladen der Tour "${entry.name}" fehlgeschlagen: Sync-Server nicht erreichbar.',
    );
  }

  if (response.statusCode < 200 || response.statusCode >= 300) {
    throw Exception(
      'Herunterladen der Tour "${entry.name}" fehlgeschlagen (HTTP ${response.statusCode}).',
    );
  }

  dynamic data;
  try {
    data = jsonDecode(response.body);
  } catch (_) {
    throw Exception(
      'Herunterladen der Tour "${entry.name}" fehlgeschlagen: ungültige Daten vom Server.',
    );
  }

  if (!_isValidRideJson(data)) {
    throw Exception(
      'Herunterladen der Tour "${entry.name}" fehlgeschlagen: ungültige Daten vom Server.',
    );
  }

  return Ride.fromJson(data.cast<String, dynamic>());
}

/// Gleicht lokale Touren mit dem konfigurierten Sync-Server ab: fehlende
/// lokale Touren werden hochgeladen, fehlende remote Touren heruntergeladen
/// und lokal gespeichert. Läuft sequenziell, wie die Referenz-Web-App.
Future<SyncResult> syncRides({
  required Future<List<Ride>> Function() listLocal,
  required Future<void> Function(Ride ride) saveLocal,
  http.Client? client,
}) async {
  final config = await getSyncConfig();
  if (config == null) {
    throw Exception('Sync ist nicht konfiguriert.');
  }

  final ownClient = client == null;
  final httpClient = client ?? http.Client();
  try {
    final remoteRides = await _fetchRemoteRides(httpClient, config);
    final remoteIds = remoteRides.map((r) => r.id).toSet();

    final localRides = await listLocal();
    final localIds = localRides.map((r) => r.id).toSet();

    var pushed = 0;
    for (final ride in localRides) {
      if (!remoteIds.contains(ride.id)) {
        await _pushRide(httpClient, config, ride);
        pushed++;
      }
    }

    var pulled = 0;
    for (final entry in remoteRides) {
      if (!localIds.contains(entry.id)) {
        final ride = await _pullRide(httpClient, config, entry);
        await saveLocal(ride);
        pulled++;
      }
    }

    return SyncResult(
      pushed: pushed,
      pulled: pulled,
      total: localRides.length + pulled,
    );
  } finally {
    if (ownClient) {
      httpClient.close();
    }
  }
}
