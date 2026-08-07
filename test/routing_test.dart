import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:trailscape/brouter_profiles.dart';
import 'package:trailscape/models.dart';
import 'package:trailscape/routing.dart';
import 'package:trailscape/sync_client.dart';

const _sampleGeoJson = '''
{
  "type": "FeatureCollection",
  "features": [
    {
      "type": "Feature",
      "properties": {
        "creator": "BRouter-1.7.0",
        "track-length": "1234",
        "filtered ascend": "56.7",
        "plain-ascend": "50"
      },
      "geometry": {
        "type": "LineString",
        "coordinates": [
          [11.111111, 48.111111, 500.0],
          [11.222222, 48.222222],
          [11.333333, 48.333333, 520.5]
        ]
      }
    }
  ]
}
''';

void main() {
  group('parseBrouterGeoJson', () {
    test('parst Koordinaten und String-Properties', () {
      final route = parseBrouterGeoJson(_sampleGeoJson);

      expect(route.points, hasLength(3));
      expect(route.points[0].lat, 48.111111);
      expect(route.points[0].lon, 11.111111);
      expect(route.points[0].ele, 500.0);
      // Fehlende ele -> null statt 0.
      expect(route.points[1].ele, isNull);
      expect(route.points[2].ele, 520.5);

      // "track-length" (String) in Metern -> distanceKm.
      expect(route.distanceKm, closeTo(1.234, 1e-9));
      // "filtered ascend" (String) -> ascentM.
      expect(route.ascentM, closeTo(56.7, 1e-9));
    });

    test('fehlende Properties werden zu 0', () {
      const body = '''
      {
        "features": [
          {
            "geometry": {"coordinates": [[1.0, 2.0]]},
            "properties": {}
          }
        ]
      }
      ''';
      final route = parseBrouterGeoJson(body);
      expect(route.distanceKm, 0);
      expect(route.ascentM, 0);
    });

    test('wirft bei kaputtem JSON', () {
      expect(
        () => parseBrouterGeoJson('not json{'),
        throwsA(isA<Exception>().having(
          (e) => e.toString(),
          'message',
          contains('Unerwartete Antwort vom Routing-Server.'),
        )),
      );
    });

    test('wirft bei unerwartetem Format (fehlende features)', () {
      expect(
        () => parseBrouterGeoJson('{"foo": "bar"}'),
        throwsA(isA<Exception>().having(
          (e) => e.toString(),
          'message',
          contains('Unerwartete Antwort vom Routing-Server.'),
        )),
      );
    });
  });

  group('brouterProfile', () {
    test('bildet jedes RouteProfile 1:1 auf seine Profil-ID ab', () {
      expect(brouterProfile(RouteProfile.gravel), 'trekking');
      expect(brouterProfile(RouteProfile.schotter), 'custom:gravel');
      expect(brouterProfile(RouteProfile.asphalt), 'fastbike');
      expect(brouterProfile(RouteProfile.radwege), 'safety');
      expect(brouterProfile(RouteProfile.kuerzester), 'shortest');
    });

    test('liefert für jedes RouteProfile einen nicht-leeren Profilnamen', () {
      for (final profile in RouteProfile.values) {
        expect(brouterProfile(profile), isNotEmpty);
      }
    });

    test('jedes Routenprofil hat ein Label', () {
      for (final profile in RouteProfile.values) {
        expect(routeProfileLabels[profile], isNotNull);
      }
      expect(routeProfileLabels[RouteProfile.schotter], 'Schotter & Kieswege');
      // Gravel steht zuerst, Schotter direkt danach im Dropdown.
      expect(
        routeProfileLabels.keys.toList(),
        containsAllInOrder([RouteProfile.gravel, RouteProfile.schotter]),
      );
      expect(routeProfileLabels.keys.first, RouteProfile.gravel);
      expect(routeProfileLabels.keys.elementAt(1), RouteProfile.schotter);
    });
  });

  group('gravelProfileText', () {
    test('schaltet prefer_unpaved_paths auf true', () {
      final text = gravelProfileText();
      expect(text, contains('assign prefer_unpaved_paths true'));
      expect(text, isNot(contains('assign prefer_unpaved_paths false')));
    });

    test('lässt den Rest des Profils intakt', () {
      final text = gravelProfileText();
      expect(text.startsWith('#'), isTrue, reason: 'Header muss erhalten sein');
      expect(text, contains('gravel.brf'));
      expect(text, contains('---context:global'));
      expect(text.length, gravelBrf.length - 1);
    });
  });

  group('fetchRoute', () {
    test('wirft bei weniger als 2 Wegpunkten ohne Netzwerkaufruf', () async {
      final client = MockClient((request) async {
        fail('sollte nicht aufgerufen werden');
      });

      expect(
        () => fetchRoute(
          [const Waypoint(lat: 48.1, lon: 11.1)],
          'trekking',
          client: client,
        ),
        throwsA(isA<Exception>().having(
          (e) => e.toString(),
          'message',
          contains('Mindestens zwei Wegpunkte nötig.'),
        )),
      );
    });

    test('baut die URL mit lon,lat (6 Nachkommastellen) und Profil auf', () async {
      late Uri capturedUrl;
      final client = MockClient((request) async {
        capturedUrl = request.url;
        return http.Response(_sampleGeoJson, 200);
      });

      final waypoints = [
        const Waypoint(lat: 48.1, lon: 11.1),
        const Waypoint(lat: 48.2, lon: 11.2),
      ];

      final route = await fetchRoute(
        waypoints,
        'fastbike',
        client: client,
      );

      expect(capturedUrl.host, 'brouter.de');
      expect(capturedUrl.path, '/brouter');
      expect(
        capturedUrl.queryParameters['lonlats'],
        '11.100000,48.100000|11.200000,48.200000',
      );
      expect(capturedUrl.queryParameters['profile'], 'fastbike');
      expect(capturedUrl.queryParameters['alternativeidx'], '0');
      expect(capturedUrl.queryParameters['format'], 'geojson');
      expect(route.points, hasLength(3));
    });

    test('wirft bei HTTP-Fehler mit Servertext', () async {
      final client = MockClient((request) async {
        return http.Response('Server explodiert', 500);
      });

      expect(
        () => fetchRoute(
          [
            const Waypoint(lat: 48.1, lon: 11.1),
            const Waypoint(lat: 48.2, lon: 11.2),
          ],
          'trekking',
          client: client,
        ),
        throwsA(isA<Exception>().having(
          (e) => e.toString(),
          'message',
          allOf(
            contains('Route konnte nicht berechnet werden'),
            contains('Server explodiert'),
          ),
        )),
      );
    });

    test('wirft bei kaputtem JSON in der Antwort', () async {
      final client = MockClient((request) async {
        return http.Response('kaputtes json{{{', 200);
      });

      expect(
        () => fetchRoute(
          [
            const Waypoint(lat: 48.1, lon: 11.1),
            const Waypoint(lat: 48.2, lon: 11.2),
          ],
          'shortest',
          client: client,
        ),
        throwsA(isA<Exception>().having(
          (e) => e.toString(),
          'message',
          contains('Unerwartete Antwort vom Routing-Server.'),
        )),
      );
    });

    test('wirft bei Netzwerkfehler', () async {
      final client = MockClient((request) async {
        throw const SocketExceptionStub();
      });

      expect(
        () => fetchRoute(
          [
            const Waypoint(lat: 48.1, lon: 11.1),
            const Waypoint(lat: 48.2, lon: 11.2),
          ],
          'trekking',
          client: client,
        ),
        throwsA(isA<Exception>().having(
          (e) => e.toString(),
          'message',
          contains('Routing-Server nicht erreichbar'),
        )),
      );
    });
  });

  group('fetchRoute mit Custom-Gravel-Profil', () {
    const waypoints = [
      Waypoint(lat: 48.1, lon: 11.1),
      Waypoint(lat: 48.2, lon: 11.2),
    ];

    setUp(resetCustomProfileCacheForTesting);
    tearDown(resetCustomProfileCacheForTesting);

    test('lädt das Profil hoch und routet mit der zurückgegebenen ID', () async {
      final uploadBodies = <String>[];
      final routedProfiles = <String>[];

      final client = MockClient((request) async {
        if (request.method == 'POST' && request.url.path == '/brouter/profile') {
          expect(request.url.host, 'brouter.de');
          uploadBodies.add(request.body);
          return http.Response(
            jsonEncode({'profileid': 'custom_1234', 'error': ''}),
            200,
          );
        }
        if (request.method == 'GET' && request.url.path == '/brouter') {
          routedProfiles.add(request.url.queryParameters['profile']!);
          return http.Response(_sampleGeoJson, 200);
        }
        fail('unerwarteter Request: ${request.method} ${request.url}');
      });

      final route = await fetchRoute(waypoints, 'custom:gravel', client: client);

      expect(uploadBodies, hasLength(1));
      expect(uploadBodies.single, contains('prefer_unpaved_paths'));
      expect(uploadBodies.single, contains('assign prefer_unpaved_paths true'));
      expect(routedProfiles, ['custom_1234']);
      expect(route.points, hasLength(3));
    });

    test('zweiter Aufruf nutzt die gecachte profileid', () async {
      var uploads = 0;
      final routedProfiles = <String>[];

      final client = MockClient((request) async {
        if (request.method == 'POST' && request.url.path == '/brouter/profile') {
          uploads++;
          return http.Response(jsonEncode({'profileid': 'custom_abc'}), 200);
        }
        routedProfiles.add(request.url.queryParameters['profile']!);
        return http.Response(_sampleGeoJson, 200);
      });

      await fetchRoute(waypoints, 'custom:gravel', client: client);
      await fetchRoute(waypoints, 'custom:gravel', client: client);

      expect(uploads, 1);
      expect(routedProfiles, ['custom_abc', 'custom_abc']);
    });

    test('lädt nach Routing-Fehler neu hoch und wiederholt einmal', () async {
      var uploads = 0;
      final routedProfiles = <String>[];

      final client = MockClient((request) async {
        if (request.method == 'POST' && request.url.path == '/brouter/profile') {
          uploads++;
          return http.Response(
            jsonEncode({'profileid': 'custom_v$uploads'}),
            200,
          );
        }
        final profile = request.url.queryParameters['profile']!;
        routedProfiles.add(profile);
        // Die erste (angeblich verworfene) ID schlägt fehl.
        if (profile == 'custom_v1') {
          return http.Response('profile not found', 500);
        }
        return http.Response(_sampleGeoJson, 200);
      });

      final route = await fetchRoute(waypoints, 'custom:gravel', client: client);

      expect(uploads, 2);
      expect(routedProfiles, ['custom_v1', 'custom_v2']);
      expect(route.points, hasLength(3));

      // Nach dem erfolgreichen Retry ist die neue ID gecacht.
      await fetchRoute(waypoints, 'custom:gravel', client: client);
      expect(uploads, 2);
      expect(routedProfiles, ['custom_v1', 'custom_v2', 'custom_v2']);
    });

    test('fällt bei fehlgeschlagenem Upload auf trekking zurück', () async {
      final routedProfiles = <String>[];

      final client = MockClient((request) async {
        if (request.method == 'POST' && request.url.path == '/brouter/profile') {
          return http.Response('upload kaputt', 500);
        }
        routedProfiles.add(request.url.queryParameters['profile']!);
        return http.Response(_sampleGeoJson, 200);
      });

      final route = await fetchRoute(waypoints, 'custom:gravel', client: client);

      expect(routedProfiles, ['trekking']);
      expect(route.points, hasLength(3));
    });

    test('fällt bei Fehler-Feld in der Upload-Antwort auf trekking zurück',
        () async {
      final routedProfiles = <String>[];

      final client = MockClient((request) async {
        if (request.method == 'POST' && request.url.path == '/brouter/profile') {
          return http.Response(jsonEncode({'error': 'syntax error'}), 200);
        }
        routedProfiles.add(request.url.queryParameters['profile']!);
        return http.Response(_sampleGeoJson, 200);
      });

      final route = await fetchRoute(waypoints, 'custom:gravel', client: client);

      expect(routedProfiles, ['trekking']);
      expect(route.points, hasLength(3));
    });

    test('fällt auf trekking zurück, wenn auch der Retry scheitert', () async {
      var uploads = 0;
      final routedProfiles = <String>[];

      final client = MockClient((request) async {
        if (request.method == 'POST' && request.url.path == '/brouter/profile') {
          uploads++;
          return http.Response(
            jsonEncode({'profileid': 'custom_v$uploads'}),
            200,
          );
        }
        final profile = request.url.queryParameters['profile']!;
        routedProfiles.add(profile);
        if (profile.startsWith('custom_')) {
          return http.Response('profile not found', 500);
        }
        return http.Response(_sampleGeoJson, 200);
      });

      final route = await fetchRoute(waypoints, 'custom:gravel', client: client);

      expect(uploads, 2);
      expect(routedProfiles, ['custom_v1', 'custom_v2', 'trekking']);
      expect(route.points, hasLength(3));
    });

    test('wirft, wenn auch trekking fehlschlägt', () async {
      final client = MockClient((request) async {
        if (request.method == 'POST' && request.url.path == '/brouter/profile') {
          return http.Response('nope', 500);
        }
        return http.Response('Server explodiert', 500);
      });

      expect(
        () => fetchRoute(waypoints, 'custom:gravel', client: client),
        throwsA(isA<Exception>().having(
          (e) => e.toString(),
          'message',
          allOf(
            contains('Route konnte nicht berechnet werden'),
            contains('Server explodiert'),
          ),
        )),
      );
    });
  });

  group('sync_client', () {
    setUp(() {
      SharedPreferences.setMockInitialValues({});
    });

    test('getSyncConfig/setSyncConfig normalisieren die URL', () async {
      expect(await getSyncConfig(), isNull);

      await setSyncConfig(const SyncConfig(
        url: '  https://sync.example.com/// ',
        token: '  secret  ',
      ));

      final config = await getSyncConfig();
      expect(config, isNotNull);
      expect(config!.url, 'https://sync.example.com');
      expect(config.token, 'secret');

      await setSyncConfig(null);
      expect(await getSyncConfig(), isNull);
    });

    test('syncRides pusht fehlende lokale und pullt fehlende remote Touren', () async {
      SharedPreferences.setMockInitialValues({
        'trailscape.sync': jsonEncode({
          'url': 'https://sync.example.com',
          'token': 'secret',
        }),
      });

      final pushedBodies = <Map<String, dynamic>>[];

      final client = MockClient((request) async {
        expect(request.headers['Authorization'], 'Bearer secret');

        if (request.method == 'GET' && request.url.path == '/api/rides') {
          return http.Response(
            jsonEncode([
              {'id': 'shared', 'name': 'Gemeinsame Tour', 'createdAt': 1000},
              {'id': 'remote-only', 'name': 'Nur remote', 'createdAt': 2000},
            ]),
            200,
          );
        }

        if (request.method == 'PUT' &&
            request.url.path == '/api/rides/local-only') {
          pushedBodies.add(
            jsonDecode(request.body) as Map<String, dynamic>,
          );
          return http.Response('', 200);
        }

        if (request.method == 'GET' &&
            request.url.path == '/api/rides/remote-only') {
          return http.Response(
            jsonEncode({
              'id': 'remote-only',
              'name': 'Nur remote',
              'createdAt': 2000,
              'points': <Map<String, dynamic>>[],
              'stats': {'distanceKm': 0, 'ascentM': 0, 'descentM': 0},
            }),
            200,
          );
        }

        fail('unerwarteter Request: ${request.method} ${request.url}');
      });

      const sharedRide = Ride(
        id: 'shared',
        name: 'Gemeinsame Tour',
        createdAt: 1000,
        points: [],
        stats: RideStats(distanceKm: 0, ascentM: 0, descentM: 0),
      );
      const localOnlyRide = Ride(
        id: 'local-only',
        name: 'Nur lokal',
        createdAt: 3000,
        points: [],
        stats: RideStats(distanceKm: 0, ascentM: 0, descentM: 0),
      );

      final saved = <Ride>[];

      final result = await syncRides(
        listLocal: () async => [sharedRide, localOnlyRide],
        saveLocal: (ride) async => saved.add(ride),
        client: client,
      );

      expect(result.pushed, 1);
      expect(result.pulled, 1);
      expect(result.total, 3);
      expect(pushedBodies, hasLength(1));
      expect(pushedBodies.single['id'], 'local-only');
      expect(saved, hasLength(1));
      expect(saved.single.id, 'remote-only');
    });

    test('syncRides wirft bei 401', () async {
      SharedPreferences.setMockInitialValues({
        'trailscape.sync': jsonEncode({
          'url': 'https://sync.example.com',
          'token': 'falsch',
        }),
      });

      final client = MockClient((request) async {
        return http.Response('nope', 401);
      });

      expect(
        () => syncRides(
          listLocal: () async => const <Ride>[],
          saveLocal: (ride) async {},
          client: client,
        ),
        throwsA(isA<Exception>().having(
          (e) => e.toString(),
          'message',
          contains('Token wird vom Server abgelehnt.'),
        )),
      );
    });

    test('syncRides wirft bei Netzwerkfehler', () async {
      SharedPreferences.setMockInitialValues({
        'trailscape.sync': jsonEncode({
          'url': 'https://sync.example.com',
          'token': 'secret',
        }),
      });

      final client = MockClient((request) async {
        throw const SocketExceptionStub();
      });

      expect(
        () => syncRides(
          listLocal: () async => const <Ride>[],
          saveLocal: (ride) async {},
          client: client,
        ),
        throwsA(isA<Exception>().having(
          (e) => e.toString(),
          'message',
          contains('Sync-Server nicht erreichbar.'),
        )),
      );
    });
  });
}

/// Minimaler Stand-in für einen Netzwerkfehler (z. B. SocketException),
/// ohne dass dart:io in Tests importiert werden muss.
class SocketExceptionStub implements Exception {
  const SocketExceptionStub();

  @override
  String toString() => 'SocketExceptionStub: Netzwerkfehler';
}
