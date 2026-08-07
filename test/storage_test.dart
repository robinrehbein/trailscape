import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

import 'package:trailscape/models.dart';
import 'package:trailscape/storage.dart';

Ride _ride(String id, int createdAt) => Ride(
      id: id,
      name: 'Tour $id',
      createdAt: createdAt,
      points: [
        const TrackPoint(lat: 47.1, lon: 11.1, ele: 500, time: 1700000000000),
        const TrackPoint(lat: 47.2, lon: 11.2, ele: 520, time: 1700000060000),
      ],
      stats: const RideStats(
        distanceKm: 1.2,
        durationS: 60,
        movingTimeS: 60,
        avgSpeedKmh: 12,
        ascentM: 20,
        descentM: 0,
      ),
    );

void main() {
  late Directory tempDir;

  setUp(() async {
    tempDir = await Directory.systemTemp.createTemp('trailscape_storage_test_');
    setStorageDirForTesting(tempDir);
  });

  tearDown(() async {
    if (await tempDir.exists()) {
      await tempDir.delete(recursive: true);
    }
  });

  group('saveRide / getRide / deleteRide Roundtrip', () {
    test('speichert, liest und löscht eine Tour', () async {
      final ride = _ride('abc', 1700000000000);

      await saveRide(ride);

      final loaded = await getRide('abc');
      expect(loaded, isNotNull);
      expect(loaded!.id, 'abc');
      expect(loaded.name, 'Tour abc');
      expect(loaded.createdAt, 1700000000000);
      expect(loaded.points, hasLength(2));
      expect(loaded.points[0].lat, closeTo(47.1, 1e-9));
      expect(loaded.stats.distanceKm, closeTo(1.2, 1e-9));

      await deleteRide('abc');
      expect(await getRide('abc'), isNull);
    });

    test('getRide liefert null für unbekannte ID', () async {
      expect(await getRide('does-not-exist'), isNull);
    });

    test('deleteRide ist ohne Wirkung, wenn die Tour nicht existiert', () async {
      await deleteRide('does-not-exist');
    });
  });

  group('listRides', () {
    test('sortiert neueste zuerst (createdAt absteigend)', () async {
      await saveRide(_ride('old', 1000));
      await saveRide(_ride('newest', 3000));
      await saveRide(_ride('middle', 2000));

      final rides = await listRides();

      expect(rides.map((r) => r.id).toList(), ['newest', 'middle', 'old']);
    });

    test('überspringt defekte Dateien', () async {
      await saveRide(_ride('valid', 1000));

      final ridesDir = Directory('${tempDir.path}/rides');
      await ridesDir.create(recursive: true);
      await File('${ridesDir.path}/broken.json').writeAsString('{ not valid json');
      await File('${ridesDir.path}/wrong-shape.json')
          .writeAsString(jsonEncode({'foo': 'bar'}));

      final rides = await listRides();

      expect(rides, hasLength(1));
      expect(rides.single.id, 'valid');
    });

    test('liefert leere Liste, wenn keine Touren existieren', () async {
      expect(await listRides(), isEmpty);
    });
  });

  group('saveRide atomares Schreiben', () {
    test('hinterlässt keine .tmp-Datei nach dem Speichern', () async {
      await saveRide(_ride('atomic', 1234));

      final ridesDir = Directory('${tempDir.path}/rides');
      final entries = await ridesDir.list().toList();
      final tmpFiles = entries.where((e) => e.path.endsWith('.tmp'));

      expect(tmpFiles, isEmpty);
      expect(File('${ridesDir.path}/atomic.json').existsSync(), isTrue);
    });

    test('überschreiben einer bestehenden Tour bleibt atomar', () async {
      await saveRide(_ride('overwrite', 1));
      await saveRide(_ride('overwrite', 2));

      final ridesDir = Directory('${tempDir.path}/rides');
      final entries = await ridesDir.list().toList();
      expect(entries.where((e) => e.path.endsWith('.tmp')), isEmpty);

      final loaded = await getRide('overwrite');
      expect(loaded!.createdAt, 2);
    });
  });
}
