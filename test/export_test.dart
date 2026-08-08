import 'package:flutter_test/flutter_test.dart';

import 'package:trailscape/export.dart';
import 'package:trailscape/gpx.dart';
import 'package:trailscape/models.dart';
import 'package:trailscape/training_load.dart';

Ride _ride({
  String id = 'r1',
  String name = 'Alpencross',
  int createdAt = 1700000000000,
  List<TrackPoint>? points,
}) {
  final pts = points ??
      [
        const TrackPoint(
          lat: 47.123456,
          lon: 11.654321,
          ele: 1234.5,
          time: 1700000000000,
          hr: 142,
        ),
        const TrackPoint(lat: 47.2, lon: 11.7, time: 1700000060000, hr: 150),
        const TrackPoint(
          lat: 47.3,
          lon: 11.8,
          ele: 1300,
          time: 1700000600000,
          hr: 138,
        ),
      ];
  return Ride(
    id: id,
    name: name,
    createdAt: createdAt,
    points: pts,
    stats: const RideStats(
      distanceKm: 12.3,
      durationS: 600,
      movingTimeS: 580,
      avgSpeedKmh: 21,
      ascentM: 300,
      descentM: 100,
      avgHrBpm: 143,
      maxHrBpm: 150,
    ),
  );
}

void main() {
  group('rideToGpx / GPX-Roundtrip', () {
    test('erhält Punktzahl, Zeiten und Herzfrequenz je Punkt', () {
      final ride = _ride();

      final xml = rideToGpx(ride);
      expect(xml, contains('<?xml version="1.0" encoding="UTF-8"?>'));
      expect(xml, contains('version="1.1"'));
      expect(xml, contains('gpxtpx:hr'));

      final parsed = parseGpx(xml);
      expect(parsed.name, ride.name);
      expect(parsed.points, hasLength(ride.points.length));

      for (var i = 0; i < ride.points.length; i++) {
        final original = ride.points[i];
        final roundtripped = parsed.points[i];
        expect(roundtripped.lat, closeTo(original.lat, 1e-9));
        expect(roundtripped.lon, closeTo(original.lon, 1e-9));
        expect(roundtripped.time, original.time);
        expect(roundtripped.hr, original.hr);
        if (original.ele != null) {
          expect(roundtripped.ele, closeTo(original.ele!, 1e-9));
        } else {
          expect(roundtripped.ele, isNull);
        }
      }
    });

    test('enthält Metadaten mit Name und Aufnahmezeitpunkt', () {
      final ride = _ride(createdAt: 1700000000000);
      final xml = rideToGpx(ride);

      expect(xml, contains('<metadata>'));
      expect(xml, contains(ride.name));
      expect(
        xml,
        contains(
          DateTime.fromMillisecondsSinceEpoch(ride.createdAt, isUtc: true)
              .toIso8601String(),
        ),
      );
    });

    test('ohne Herzfrequenz wird kein gpxtpx-Namespace geschrieben', () {
      final ride = _ride(
        points: const [
          TrackPoint(lat: 47.0, lon: 11.0),
          TrackPoint(lat: 47.1, lon: 11.1),
        ],
      );
      final xml = rideToGpx(ride);
      expect(xml, isNot(contains('gpxtpx')));
    });
  });

  group('XML-Escaping', () {
    test('Sonderzeichen im Tournamen werden escaped und korrekt zurückgelesen', () {
      final ride = _ride(name: 'Tour & <Test> "Zitat" \'Apostroph\'');
      final xml = rideToGpx(ride);

      expect(xml, isNot(contains('<Test>')));
      expect(xml, contains('&amp;'));
      expect(xml, contains('&lt;Test'));

      final parsed = parseGpx(xml);
      expect(parsed.name, ride.name);
    });
  });

  group('rideFromGpx', () {
    test('berechnet Statistiken inkl. Ø-/Max-Puls aus den Trackpunkten', () {
      final original = _ride();
      final xml = rideToGpx(original);

      final imported = rideFromGpx(xml, fallbackName: 'egal', id: 'imported');

      expect(imported.id, 'imported');
      expect(imported.name, original.name);
      expect(imported.points, hasLength(original.points.length));
      expect(imported.stats.avgHrBpm, 143); // Mittel aus 142, 150, 138
      expect(imported.stats.maxHrBpm, 150);
      expect(imported.stats.distanceKm, greaterThan(0));
    });

    test('funktioniert ohne ele/time/hr (nur Koordinaten)', () {
      const xml = '''
<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
  <trk>
    <trkseg>
      <trkpt lat="47.0" lon="11.0"/>
      <trkpt lat="47.01" lon="11.01"/>
    </trkseg>
  </trk>
</gpx>
''';

      final ride = rideFromGpx(xml, fallbackName: 'Ohne Extras');

      expect(ride.name, 'Ohne Extras');
      expect(ride.points, hasLength(2));
      expect(ride.points.every((p) => p.ele == null), isTrue);
      expect(ride.points.every((p) => p.time == null), isTrue);
      expect(ride.points.every((p) => p.hr == null), isTrue);
      expect(ride.stats.avgHrBpm, isNull);
      expect(ride.stats.maxHrBpm, isNull);
      expect(ride.stats.durationS, isNull);
      expect(ride.stats.distanceKm, greaterThan(0));
    });

    test('nutzt den GPX-Namen, wenn vorhanden, statt fallbackName', () {
      final original = _ride(name: 'Original-Name');
      final xml = rideToGpx(original);
      final imported = rideFromGpx(xml, fallbackName: 'Fallback');
      expect(imported.name, 'Original-Name');
    });

    test('kaputtes GPX wirft FormatException', () {
      expect(
        () => rideFromGpx('<gpx><trk>', fallbackName: 'x'),
        throwsFormatException,
      );
    });
  });

  group('Backup: buildBackupJson / parseBackupJson', () {
    test('Roundtrip erhält Touren und Profil', () {
      final rides = [_ride(id: 'a'), _ride(id: 'b', name: 'Feierabendrunde')];
      const profile = TrainingProfile(
        ageYears: 34,
        sex: Sex.weiblich,
        weightKg: 62,
        hrMaxOverride: 188,
      );

      final json = buildBackupJson(rides, profile);
      expect(json, contains('"app": "trailscape"'));
      expect(json, contains('"backupVersion": 1'));

      final data = parseBackupJson(json);
      expect(data.rides, hasLength(2));
      expect(data.rides.map((r) => r.id), containsAll(['a', 'b']));
      expect(data.rides.first.points, hasLength(rides.first.points.length));
      expect(data.profile, isNotNull);
      expect(data.profile!.ageYears, 34);
      expect(data.profile!.sex, Sex.weiblich);
      expect(data.profile!.weightKg, closeTo(62, 1e-9));
      expect(data.profile!.hrMaxOverride, closeTo(188, 1e-9));
    });

    test('Roundtrip ohne Profil liefert null', () {
      final json = buildBackupJson([_ride()], null);
      final data = parseBackupJson(json);
      expect(data.profile, isNull);
      expect(data.rides, hasLength(1));
    });

    test('leere Tourenliste erzeugt gültiges Backup mit leerer Liste', () {
      final json = buildBackupJson(const [], null);
      final data = parseBackupJson(json);
      expect(data.rides, isEmpty);
      expect(data.profile, isNull);
    });

    test('kaputtes JSON wirft FormatException', () {
      expect(
        () => parseBackupJson('{ das ist kein json'),
        throwsFormatException,
      );
    });

    test('valides JSON ohne Trailscape-Signatur wirft FormatException', () {
      expect(
        () => parseBackupJson('{"foo": "bar"}'),
        throwsFormatException,
      );
    });

    test('fremdes app-Feld wirft FormatException', () {
      expect(
        () => parseBackupJson(
          '{"app": "andereApp", "backupVersion": 1, "rides": []}',
        ),
        throwsFormatException,
      );
    });

    test('höhere, unbekannte backupVersion wirft FormatException', () {
      expect(
        () => parseBackupJson(
          '{"app": "trailscape", "backupVersion": 999, "rides": []}',
        ),
        throwsFormatException,
      );
    });

    test('fehlende Touren-Liste wirft FormatException', () {
      expect(
        () => parseBackupJson('{"app": "trailscape", "backupVersion": 1}'),
        throwsFormatException,
      );
    });
  });

  group('safeFileName / backupFileName', () {
    test('ersetzt Sonderzeichen und trimmt Unterstriche', () {
      expect(safeFileName('Alpen Cross 2026 (Tag 1)!'), 'Alpen_Cross_2026_Tag_1');
      expect(safeFileName('   '), 'tour');
    });

    test('backupFileName formatiert Datum zweistellig', () {
      expect(
        backupFileName(DateTime(2026, 8, 8)),
        'trailscape-backup-2026-08-08.json',
      );
      expect(
        backupFileName(DateTime(2026, 1, 2)),
        'trailscape-backup-2026-01-02.json',
      );
    });
  });
}
