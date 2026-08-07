import 'dart:math' as math;

import 'package:flutter_test/flutter_test.dart';
import 'package:trailscape/models.dart';
import 'package:trailscape/navigation.dart';

/// Basisbreite der Testrouten.
const double _lat0 = 48.0;

/// Basislänge der Testrouten.
const double _lon0 = 11.0;

/// Meter pro Grad Länge auf [_lat0] (identisch zur Projektion im Navigator).
final double _mPerDegLon = 111320 * math.cos(_lat0 * math.pi / 180);

/// Grad Länge pro Meter nach Osten.
final double _degLonPerM = 1 / _mPerDegLon;

/// Grad Breite pro Meter nach Norden.
const double _degLatPerM = 1 / 111320;

/// Gerade Ost-Route auf konstanter Breite, [count] Punkte im Abstand [stepM].
List<TrackPoint> _straightRoute({int count = 21, double stepM = 100}) {
  return List<TrackPoint>.generate(
    count,
    (i) => TrackPoint(lat: _lat0, lon: _lon0 + i * stepM * _degLonPerM),
  );
}

/// Position [alongM] Meter östlich des Startpunkts, [offsetM] Meter nördlich.
({double lat, double lon}) _pos(double alongM, [double offsetM = 0]) => (
      lat: _lat0 + offsetM * _degLatPerM,
      lon: _lon0 + alongM * _degLonPerM,
    );

void main() {
  group('RouteNavigator Konstruktor', () {
    test('wirft ArgumentError bei weniger als 2 Punkten', () {
      expect(() => RouteNavigator(const []), throwsArgumentError);
      expect(
        () => RouteNavigator(const [TrackPoint(lat: _lat0, lon: _lon0)]),
        throwsArgumentError,
      );
      expect(
        () => RouteNavigator(const []),
        throwsA(
          isA<ArgumentError>().having(
            (e) => e.message,
            'message',
            'Route benötigt mindestens 2 Punkte.',
          ),
        ),
      );
    });

    test('totalKm entspricht der Routenlänge von rund 2 km', () {
      final nav = RouteNavigator(_straightRoute());
      expect(nav.totalKm, closeTo(2.0, 0.01));
    });
  });

  group('Szenario 1: Position auf 40 % der Route', () {
    test('doneKm und Abstand stimmen', () {
      final nav = RouteNavigator(_straightRoute());
      final p = _pos(800);
      final state = nav.update(lat: p.lat, lon: p.lon, now: 0);

      expect(state.distanceToRouteM, closeTo(0, 0.001));
      expect(state.doneKm, closeTo(0.4 * nav.totalKm, 1e-6));
      expect(state.doneKm, closeTo(0.8, 0.01));
      expect(state.remainingKm, closeTo(0.6 * nav.totalKm, 1e-6));
      expect(state.nearestIndex, 8);
      expect(state.offRoute, isFalse);
    });
  });

  group('Szenario 2: 100 m seitlich versetzt', () {
    test('Abstand betraegt rund 100 m', () {
      final nav = RouteNavigator(_straightRoute());
      final p = _pos(800, 100);
      final state = nav.update(lat: p.lat, lon: p.lon, now: 0);

      expect(state.distanceToRouteM, closeTo(100, 0.5));
      expect(state.doneKm, closeTo(0.4 * nav.totalKm, 1e-6));
      expect(state.offRoute, isFalse);
    });

    test('offRoute erst nach 5 s durchgehend > 60 m', () {
      final nav = RouteNavigator(_straightRoute());
      final far = _pos(800, 100);

      // Erster Treffer setzt nur den Zeitstempel.
      expect(nav.update(lat: far.lat, lon: far.lon, now: 0).offRoute, isFalse);
      expect(
        nav.update(lat: far.lat, lon: far.lon, now: 2000).offRoute,
        isFalse,
      );
      expect(
        nav.update(lat: far.lat, lon: far.lon, now: 4999).offRoute,
        isFalse,
      );
      expect(nav.update(lat: far.lat, lon: far.lon, now: 5000).offRoute, isTrue);
      expect(nav.update(lat: far.lat, lon: far.lon, now: 6000).offRoute, isTrue);
    });

    test('Rueckkehr unter 35 m setzt offRoute sofort zurueck', () {
      final nav = RouteNavigator(_straightRoute());
      final far = _pos(800, 100);
      final near = _pos(800, 10);

      nav.update(lat: far.lat, lon: far.lon, now: 0);
      expect(nav.update(lat: far.lat, lon: far.lon, now: 5000).offRoute, isTrue);
      expect(
        nav.update(lat: near.lat, lon: near.lon, now: 5500).offRoute,
        isFalse,
      );
    });

    test('45-m-Band haelt den Zustand in beide Richtungen', () {
      final nav = RouteNavigator(_straightRoute());
      final far = _pos(800, 100);
      final band = _pos(800, 45);
      final near = _pos(800, 10);

      // Zustand false wird im Band gehalten.
      expect(
        nav.update(lat: near.lat, lon: near.lon, now: 0).offRoute,
        isFalse,
      );
      expect(
        nav.update(lat: band.lat, lon: band.lon, now: 1000).offRoute,
        isFalse,
      );
      expect(
        nav.update(lat: band.lat, lon: band.lon, now: 20000).offRoute,
        isFalse,
      );

      // Zustand true wird im Band ebenfalls gehalten.
      nav.update(lat: far.lat, lon: far.lon, now: 30000);
      expect(
        nav.update(lat: far.lat, lon: far.lon, now: 35000).offRoute,
        isTrue,
      );
      expect(
        nav.update(lat: band.lat, lon: band.lon, now: 36000).offRoute,
        isTrue,
      );
      expect(
        nav.update(lat: band.lat, lon: band.lon, now: 60000).offRoute,
        isTrue,
      );
    });

    test('Band resettet den Continuity-Timer', () {
      final nav = RouteNavigator(_straightRoute());
      final far = _pos(800, 100);
      final band = _pos(800, 45);

      nav.update(lat: far.lat, lon: far.lon, now: 0);
      // Kurzer Abstecher ins Band loescht den Zaehler.
      expect(
        nav.update(lat: band.lat, lon: band.lon, now: 3000).offRoute,
        isFalse,
      );
      // Ab hier laeuft die 5-s-Frist neu.
      expect(
        nav.update(lat: far.lat, lon: far.lon, now: 4000).offRoute,
        isFalse,
      );
      expect(
        nav.update(lat: far.lat, lon: far.lon, now: 8000).offRoute,
        isFalse,
      );
      expect(
        nav.update(lat: far.lat, lon: far.lon, now: 9000).offRoute,
        isTrue,
      );
    });
  });

  group('Szenario 3: Entlangfahren', () {
    test('remainingKm faellt monoton, Summe bleibt total, Ende ist 0', () {
      final nav = RouteNavigator(_straightRoute());
      final total = nav.totalKm;

      var previousRemaining = double.infinity;
      for (var alongM = 0.0; alongM <= 2000.0; alongM += 50) {
        final p = _pos(alongM);
        final state = nav.update(
          lat: p.lat,
          lon: p.lon,
          now: (alongM * 10).round(),
        );

        expect(state.remainingKm, lessThanOrEqualTo(previousRemaining + 1e-9));
        previousRemaining = state.remainingKm;

        expect(state.doneKm + state.remainingKm, closeTo(total, 1e-9));
        expect(state.doneKm, greaterThanOrEqualTo(0));
        expect(state.remainingKm, greaterThanOrEqualTo(0));
        expect(state.distanceToRouteM, closeTo(0, 0.001));
        expect(state.offRoute, isFalse);
      }

      final end = _pos(2000);
      final endState = nav.update(lat: end.lat, lon: end.lon, now: 999999);
      expect(endState.remainingKm, closeTo(0, 1e-9));
      expect(endState.doneKm, closeTo(total, 1e-9));
      expect(endState.nearestIndex, 20);
    });
  });

  group('Szenario 4: 300-Punkte-Route mit globalem Fallback', () {
    test('Sprung ans Ende und zurueck wird gefunden', () {
      final route = _straightRoute(count: 300);
      final nav = RouteNavigator(route);

      // Start am Routenanfang: Fenster ist [0, 50].
      final start = _pos(0);
      final startState = nav.update(lat: start.lat, lon: start.lon, now: 0);
      expect(startState.nearestIndex, 0);
      expect(startState.doneKm, closeTo(0, 1e-9));

      // Sprung auf Punkt 290 - weit ausserhalb des Fensters.
      final jump = _pos(290 * 100);
      final jumpState = nav.update(lat: jump.lat, lon: jump.lon, now: 1000);
      expect(jumpState.distanceToRouteM, closeTo(0, 0.01));
      expect(jumpState.nearestIndex, 290);
      expect(jumpState.doneKm, closeTo(290 / 299 * nav.totalKm, 1e-6));

      // Ruecksprung an den Anfang - ebenfalls nur global auffindbar.
      final back = _pos(5 * 100);
      final backState = nav.update(lat: back.lat, lon: back.lon, now: 2000);
      expect(backState.distanceToRouteM, closeTo(0, 0.01));
      expect(backState.nearestIndex, 5);
      expect(backState.doneKm, closeTo(5 / 299 * nav.totalKm, 1e-6));
    });

    test('kleine Bewegung bleibt im Fenster', () {
      final nav = RouteNavigator(_straightRoute(count: 300));
      final a = _pos(150 * 100);
      nav.update(lat: a.lat, lon: a.lon, now: 0);

      final b = _pos(150 * 100 + 250);
      final state = nav.update(lat: b.lat, lon: b.lon, now: 1000);
      expect(state.nearestIndex, anyOf(152, 153));
      expect(state.distanceToRouteM, closeTo(0, 0.01));
    });
  });

  group('Szenario 5: Klemmung vor Start und hinter Ende', () {
    test('vor dem Start bleibt doneKm bei 0', () {
      final nav = RouteNavigator(_straightRoute());
      final p = _pos(-500);
      final state = nav.update(lat: p.lat, lon: p.lon, now: 0);

      expect(state.doneKm, closeTo(0, 1e-9));
      expect(state.remainingKm, closeTo(nav.totalKm, 1e-9));
      expect(state.distanceToRouteM, closeTo(500, 0.5));
      expect(state.nearestIndex, 0);
    });

    test('hinter dem Ende bleibt remainingKm bei 0', () {
      final nav = RouteNavigator(_straightRoute());
      final p = _pos(2500);
      final state = nav.update(lat: p.lat, lon: p.lon, now: 0);

      expect(state.doneKm, closeTo(nav.totalKm, 1e-9));
      expect(state.remainingKm, closeTo(0, 1e-9));
      expect(state.distanceToRouteM, closeTo(500, 0.5));
      expect(state.nearestIndex, 20);
    });

    test('seitlich versetzt vor dem Start klemmt ebenfalls', () {
      final nav = RouteNavigator(_straightRoute());
      final p = _pos(-300, 400);
      final state = nav.update(lat: p.lat, lon: p.lon, now: 0);

      expect(state.doneKm, closeTo(0, 1e-9));
      expect(state.remainingKm, closeTo(nav.totalKm, 1e-9));
      expect(state.distanceToRouteM, closeTo(500, 1));
    });
  });
}
