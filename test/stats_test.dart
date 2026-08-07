import 'package:flutter_test/flutter_test.dart';
import 'package:trailscape/models.dart';
import 'package:trailscape/stats.dart';

void main() {
  group('haversineM', () {
    test('Berlin -> Potsdam liegt bei rund 25 km', () {
      const berlin = TrackPoint(lat: 52.5163, lon: 13.3777);
      const potsdam = TrackPoint(lat: 52.3989, lon: 13.0657);

      final distanceM = haversineM(berlin, potsdam);

      expect(distanceM, closeTo(24846, 500));
    });

    test('Distanz zu sich selbst ist 0', () {
      const p = TrackPoint(lat: 48.1, lon: 11.5);
      expect(haversineM(p, p), 0);
    });
  });

  group('computeStats – Randfälle', () {
    test('leere Liste liefert Nullwerte', () {
      final stats = computeStats(const []);

      expect(stats.distanceKm, 0);
      expect(stats.durationS, isNull);
      expect(stats.movingTimeS, isNull);
      expect(stats.avgSpeedKmh, isNull);
      expect(stats.ascentM, 0);
      expect(stats.descentM, 0);
    });

    test('Liste mit einem Punkt liefert Nullwerte', () {
      final stats = computeStats(const [
        TrackPoint(lat: 48.1, lon: 11.5, ele: 500, time: 1000),
      ]);

      expect(stats.distanceKm, 0);
      expect(stats.durationS, isNull);
      expect(stats.movingTimeS, isNull);
      expect(stats.avgSpeedKmh, isNull);
      expect(stats.ascentM, 0);
      expect(stats.descentM, 0);
    });
  });

  group('computeStats – Distanz/Dauer/Geschwindigkeit', () {
    // 9 Punkte entlang des Äquators, je 0.0001° Longitude (~11.12 m) und
    // 10 s Zeitabstand -> konstante Geschwindigkeit von ca. 4 km/h.
    List<TrackPoint> buildMovingTrack() {
      const step = 0.0001;
      const lon0 = 13.0;
      return List.generate(9, (i) {
        return TrackPoint(
          lat: 0,
          lon: lon0 + i * step,
          time: i * 10000,
        );
      });
    }

    test('Distanz ist die Haversine-Summe der Segmente', () {
      final points = buildMovingTrack();
      final stats = computeStats(points);

      var expectedM = 0.0;
      for (var i = 1; i < points.length; i++) {
        expectedM += haversineM(points[i - 1], points[i]);
      }

      expect(stats.distanceKm, closeTo(expectedM / 1000, 1e-9));
    });

    test('durationS aus erstem/letztem Zeitstempel', () {
      final stats = computeStats(buildMovingTrack());
      expect(stats.durationS, 80);
    });

    test('movingTimeS zählt nur Segmente über 1 km/h', () {
      final stats = computeStats(buildMovingTrack());
      // Alle Segmente liegen bei ca. 4 km/h -> die komplette Dauer zählt.
      expect(stats.movingTimeS, 80);
    });

    test('avgSpeedKmh = Distanz / movingTime, wenn movingTime vorhanden ist',
        () {
      final stats = computeStats(buildMovingTrack());
      final expectedSpeed = stats.distanceKm / (stats.movingTimeS! / 3600);
      expect(stats.avgSpeedKmh, closeTo(expectedSpeed, 1e-9));
      expect(stats.avgSpeedKmh, closeTo(4.0, 0.1));
    });

    test('Stillstand fließt in durationS, aber nicht in movingTimeS ein', () {
      final points = [
        const TrackPoint(lat: 0, lon: 13.0, time: 0),
        // Punkt bleibt 100 s lang an derselben Stelle stehen.
        const TrackPoint(lat: 0, lon: 13.0, time: 100000),
        // Danach 10 s Bewegung mit klar erkennbarem Tempo.
        const TrackPoint(lat: 0, lon: 13.0011, time: 110000),
      ];

      final stats = computeStats(points);

      expect(stats.durationS, 110);
      // Nur das letzte 10-Sekunden-Segment zählt als Bewegung.
      expect(stats.movingTimeS, 10);
    });

    test('avgSpeedKmh fällt auf durationS zurück, wenn keine Zeiten für '
        'Segmente vorliegen, aber Start-/Endzeit bekannt sind', () {
      final points = [
        const TrackPoint(lat: 0, lon: 13.0, time: 0),
        const TrackPoint(lat: 0, lon: 13.001), // keine Zeit -> kein Segment-dt
        const TrackPoint(lat: 0, lon: 13.002, time: 3600000), // 1 h später
      ];

      final stats = computeStats(points);

      expect(stats.movingTimeS, isNull);
      expect(stats.durationS, 3600);
      expect(stats.avgSpeedKmh, closeTo(stats.distanceKm, 1e-9));
    });

    test('ohne jegliche Zeitstempel bleiben Dauer/Tempo null', () {
      final points = const [
        TrackPoint(lat: 0, lon: 13.0),
        TrackPoint(lat: 0, lon: 13.001),
      ];

      final stats = computeStats(points);

      expect(stats.durationS, isNull);
      expect(stats.movingTimeS, isNull);
      expect(stats.avgSpeedKmh, isNull);
    });
  });

  group('computeStats – Höhenmeter-Hysterese', () {
    test('reines GPS-Zittern (±1 m) zählt nicht als Anstieg/Abstieg', () {
      final points = [
        for (final ele in [100.0, 100.8, 99.3, 100.5, 99.8, 100.2])
          TrackPoint(lat: 0, lon: 13.0, ele: ele),
      ];

      final stats = computeStats(points);

      expect(stats.ascentM, 0);
      expect(stats.descentM, 0);
    });

    test('echte Anstiege/Abstiege ≥ 3 m werden trotz Rauschen erfasst', () {
      final points = [
        for (final ele in [
          100.0, // Referenz
          100.5, // Rauschen, < 3 m -> ignoriert
          99.5, // Rauschen, < 3 m -> ignoriert
          100.2, // Rauschen, < 3 m -> ignoriert
          110.0, // echter Anstieg von 10 m -> neue Referenz 110
          110.5, // Rauschen -> ignoriert
          109.7, // Rauschen -> ignoriert
          100.0, // echter Abstieg von 10 m -> neue Referenz 100
        ])
          TrackPoint(lat: 0, lon: 13.0, ele: ele),
      ];

      final stats = computeStats(points);

      expect(stats.ascentM, closeTo(10, 1e-9));
      expect(stats.descentM, closeTo(10, 1e-9));
    });

    test('Punkte ohne Höhe werden für die Höhenberechnung ignoriert', () {
      final points = [
        const TrackPoint(lat: 0, lon: 13.0, ele: 100),
        const TrackPoint(lat: 0, lon: 13.0), // keine Höhe
        const TrackPoint(lat: 0, lon: 13.0, ele: 120),
      ];

      final stats = computeStats(points);

      expect(stats.ascentM, closeTo(20, 1e-9));
      expect(stats.descentM, 0);
    });

    test('weniger als zwei Punkte mit Höhe liefern 0/0', () {
      final points = [
        const TrackPoint(lat: 0, lon: 13.0, ele: 100),
        const TrackPoint(lat: 0, lon: 13.001),
      ];

      final stats = computeStats(points);

      expect(stats.ascentM, 0);
      expect(stats.descentM, 0);
    });
  });

  group('formatDuration', () {
    test('null wird zu "–"', () {
      expect(formatDuration(null), '–');
    });

    test('unter einer Stunde als M:SS', () {
      expect(formatDuration(42 * 60 + 5), '42:05');
    });

    test('ab einer Stunde als H:MM:SS', () {
      expect(formatDuration(3600 + 42 * 60 + 5), '1:42:05');
    });

    test('0 Sekunden', () {
      expect(formatDuration(0), '0:00');
    });

    test('mehrstellige Stunden', () {
      expect(formatDuration(12 * 3600 + 5 * 60 + 9), '12:05:09');
    });
  });

  group('formatKm', () {
    test('eine Nachkommastelle', () {
      expect(formatKm(42.34), '42.3');
    });

    test('rundet korrekt auf', () {
      expect(formatKm(42.36), '42.4');
    });

    test('ganze Zahl bekommt .0', () {
      expect(formatKm(0), '0.0');
    });
  });
}
