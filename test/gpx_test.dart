import 'package:flutter_test/flutter_test.dart';

import 'package:trailscape/gpx.dart';
import 'package:trailscape/models.dart';

void main() {
  group('buildGpx / parseGpx roundtrip', () {
    test('roundtrip erhält Name und Punkte (inkl. ele/time)', () {
      final points = [
        const TrackPoint(
          lat: 47.123456,
          lon: 11.654321,
          ele: 1234.5,
          time: 1700000000000,
        ),
        const TrackPoint(lat: 47.2, lon: 11.7),
        const TrackPoint(
          lat: 47.3,
          lon: 11.8,
          time: 1700000600000,
        ),
      ];

      final xml = buildGpx('Meine Tour', points);
      expect(xml, contains('<?xml version="1.0" encoding="UTF-8"?>'));
      expect(xml, contains('version="1.1"'));
      expect(xml, contains('creator="Trailscape"'));
      expect(xml, contains('http://www.topografix.com/GPX/1/1'));

      final result = parseGpx(xml);
      expect(result.name, 'Meine Tour');
      expect(result.points, hasLength(3));

      expect(result.points[0].lat, closeTo(47.123456, 1e-9));
      expect(result.points[0].lon, closeTo(11.654321, 1e-9));
      expect(result.points[0].ele, closeTo(1234.5, 1e-9));
      expect(result.points[0].time, 1700000000000);

      expect(result.points[1].ele, isNull);
      expect(result.points[1].time, isNull);

      expect(result.points[2].time, 1700000600000);
      expect(result.points[2].ele, isNull);
    });

    test('escaped Name wird korrekt gebaut und wieder geparst', () {
      final points = [const TrackPoint(lat: 1, lon: 2)];
      final xml = buildGpx('Tour & <Test> "Zitat" \'Apostroph\'', points);

      // Das xml-Paket escaped Sonderzeichen im Builder automatisch.
      expect(xml, isNot(contains('<Test>')));
      expect(xml, contains('&amp;'));
      expect(xml, contains('&lt;Test'));

      final result = parseGpx(xml);
      expect(result.name, 'Tour & <Test> "Zitat" \'Apostroph\'');
    });

    test('leere Punktliste erzeugt GPX ohne Trackpunkte, parseGpx wirft', () {
      final xml = buildGpx('Leer', const []);
      expect(() => parseGpx(xml), throwsFormatException);
    });
  });

  group('parseGpx mit handgeschriebenem GPX', () {
    const handwritten = '''
<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="Testsuite" xmlns="http://www.topografix.com/GPX/1/1">
  <metadata>
    <name>Metadata-Name</name>
  </metadata>
  <trk>
    <name>Zwei Segmente Tour</name>
    <trkseg>
      <trkpt lat="47.1" lon="11.1">
        <ele>500.0</ele>
        <time>2023-05-01T10:00:00Z</time>
      </trkpt>
      <trkpt lat="47.2" lon="11.2">
        <ele>510.5</ele>
        <time>2023-05-01T10:01:00Z</time>
      </trkpt>
    </trkseg>
    <trkseg>
      <trkpt lat="47.3" lon="11.3">
        <ele>520.0</ele>
        <time>2023-05-01T10:05:00Z</time>
      </trkpt>
    </trkseg>
  </trk>
</gpx>
''';

    test('liest alle trkpt aus beiden Segmenten in Reihenfolge', () {
      final result = parseGpx(handwritten);

      expect(result.points, hasLength(3));
      expect(result.points[0].lat, 47.1);
      expect(result.points[0].lon, 11.1);
      expect(result.points[0].ele, 500.0);
      expect(
        result.points[0].time,
        DateTime.parse('2023-05-01T10:00:00Z').millisecondsSinceEpoch,
      );

      expect(result.points[1].lat, 47.2);
      expect(result.points[2].lat, 47.3);
      expect(result.points[2].ele, 520.0);
    });

    test('Name kommt aus trk>name, nicht aus metadata>name', () {
      final result = parseGpx(handwritten);
      expect(result.name, 'Zwei Segmente Tour');
    });

    test('Name fällt auf metadata>name zurück, wenn trk>name fehlt', () {
      const xml = '''
<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
  <metadata>
    <name>Nur Metadata</name>
  </metadata>
  <trk>
    <trkseg>
      <trkpt lat="1.0" lon="2.0"/>
    </trkseg>
  </trk>
</gpx>
''';
      final result = parseGpx(xml);
      expect(result.name, 'Nur Metadata');
    });

    test('Name ist null, wenn weder trk>name noch metadata>name existiert',
        () {
      const xml = '''
<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
  <trk>
    <trkseg>
      <trkpt lat="1.0" lon="2.0"/>
    </trkseg>
  </trk>
</gpx>
''';
      final result = parseGpx(xml);
      expect(result.name, isNull);
    });
  });

  group('rtept-Fallback', () {
    test('nutzt rtept, wenn keine trkpt vorhanden sind', () {
      const xml = '''
<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.0">
  <rte>
    <name>Route</name>
    <rtept lat="10.0" lon="20.0">
      <ele>100</ele>
    </rtept>
    <rtept lat="10.5" lon="20.5"/>
  </rte>
</gpx>
''';
      final result = parseGpx(xml);
      expect(result.points, hasLength(2));
      expect(result.points[0].lat, 10.0);
      expect(result.points[0].ele, 100.0);
      expect(result.points[1].lat, 10.5);
      expect(result.points[1].ele, isNull);
    });
  });

  group('Fehlerfälle', () {
    test('kaputtes XML wirft FormatException', () {
      const brokenXml = '<gpx><trk><trkseg><trkpt lat="1" lon="2">';
      expect(() => parseGpx(brokenXml), throwsFormatException);
    });

    test('gültiges XML ohne gpx-Wurzel wirft FormatException', () {
      const xml = '<?xml version="1.0"?><notgpx></notgpx>';
      expect(() => parseGpx(xml), throwsFormatException);
    });

    test('gültiges GPX ohne Trackpunkte wirft FormatException', () {
      const xml = '''
<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
  <trk>
    <name>Leer</name>
    <trkseg></trkseg>
  </trk>
</gpx>
''';
      expect(() => parseGpx(xml), throwsFormatException);
    });

    test('ungültige Koordinaten werfen FormatException', () {
      const xml = '''
<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
  <trk>
    <trkseg>
      <trkpt lat="nicht-numerisch" lon="2.0"/>
    </trkseg>
  </trk>
</gpx>
''';
      expect(() => parseGpx(xml), throwsFormatException);
    });

    test('leerer String wirft FormatException', () {
      expect(() => parseGpx(''), throwsFormatException);
    });
  });
}
