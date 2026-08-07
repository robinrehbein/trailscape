import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

import 'package:trailscape/geocoding.dart';

void main() {
  group('searchPlaces', () {
    test('baut die URL und Header korrekt auf', () async {
      late Uri capturedUrl;
      late Map<String, String> capturedHeaders;
      final client = MockClient((request) async {
        capturedUrl = request.url;
        capturedHeaders = request.headers;
        return http.Response('[]', 200);
      });

      await searchPlaces('München', client: client);

      expect(capturedUrl.scheme, 'https');
      expect(capturedUrl.host, 'nominatim.openstreetmap.org');
      expect(capturedUrl.path, '/search');
      expect(capturedUrl.queryParameters['q'], 'München');
      expect(capturedUrl.queryParameters['format'], 'jsonv2');
      expect(capturedUrl.queryParameters['limit'], '5');
      expect(capturedUrl.queryParameters['accept-language'], 'de');
      expect(
        capturedHeaders['User-Agent'],
        'Trailscape/1.0 (github.com/robinrehbein/trailscape)',
      );
    });

    test('Normalfall: parst zwei Ergebnisse mit lat/lon als Strings', () async {
      final client = MockClient((request) async {
        return http.Response(
          '''
          [
            {"display_name": "München, Bayern, Deutschland", "lat": "48.137154", "lon": "11.576124"},
            {"display_name": "Münchenbernsdorf, Thüringen, Deutschland", "lat": "50.816", "lon": "12.048"}
          ]
          ''',
          200,
        );
      });

      final results = await searchPlaces('München', client: client);

      expect(results, hasLength(2));
      expect(results[0].displayName, 'München, Bayern, Deutschland');
      expect(results[0].lat, closeTo(48.137154, 1e-9));
      expect(results[0].lon, closeTo(11.576124, 1e-9));
      expect(results[1].displayName, 'Münchenbernsdorf, Thüringen, Deutschland');
      expect(results[1].lat, closeTo(50.816, 1e-9));
      expect(results[1].lon, closeTo(12.048, 1e-9));
    });

    test('überspringt unparsebare Einträge', () async {
      final client = MockClient((request) async {
        return http.Response(
          '''
          [
            {"display_name": "Gültig", "lat": "1.0", "lon": "2.0"},
            {"display_name": "Kaputt", "lat": "nicht-numerisch", "lon": "2.0"},
            {"display_name": "Fehlt lon", "lat": "1.0"},
            {"lat": "1.0", "lon": "2.0"}
          ]
          ''',
          200,
        );
      });

      final results = await searchPlaces('irgendwas', client: client);

      expect(results, hasLength(1));
      expect(results.single.displayName, 'Gültig');
    });

    test('leere Antwort ergibt leere Liste', () async {
      final client = MockClient((request) async {
        return http.Response('[]', 200);
      });

      final results = await searchPlaces('nirgendwo', client: client);

      expect(results, isEmpty);
    });

    test('leerer Query löst keinen Request aus', () async {
      final client = MockClient((request) async {
        fail('sollte nicht aufgerufen werden');
      });

      expect(await searchPlaces('', client: client), isEmpty);
      expect(await searchPlaces('   ', client: client), isEmpty);
    });

    test('wirft bei HTTP-Fehler', () async {
      final client = MockClient((request) async {
        return http.Response('Server explodiert', 500);
      });

      expect(
        () => searchPlaces('München', client: client),
        throwsA(isA<Exception>().having(
          (e) => e.toString(),
          'message',
          contains('Ortssuche fehlgeschlagen (HTTP 500).'),
        )),
      );
    });

    test('wirft bei kaputtem JSON', () async {
      final client = MockClient((request) async {
        return http.Response('kaputtes json{{{', 200);
      });

      expect(
        () => searchPlaces('München', client: client),
        throwsA(isA<Exception>().having(
          (e) => e.toString(),
          'message',
          contains('Unerwartete Antwort der Ortssuche.'),
        )),
      );
    });

    test('wirft bei Netzwerkfehler', () async {
      final client = MockClient((request) async {
        throw const SocketExceptionStub();
      });

      expect(
        () => searchPlaces('München', client: client),
        throwsA(isA<Exception>().having(
          (e) => e.toString(),
          'message',
          contains('Ortssuche nicht erreichbar. Bist du online?'),
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
