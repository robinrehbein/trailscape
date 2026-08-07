import 'dart:io';

import 'package:flutter_map/flutter_map.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:trailscape/tile_cache.dart';

MapStyle _styleById(String id) =>
    mapStyles.firstWhere((style) => style.id == id);

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  group('Kachel-Mathematik', () {
    test('Berlin 52.52/13.405 @ z14 ergibt x=8802, y=5373', () {
      expect(lonToTileX(13.405, 14), 8802);
      expect(latToTileY(52.52, 14), 5373);
    });

    test('Längengrad wird auf ±180° geklemmt', () {
      const zoom = 10;
      final tileCount = 1 << zoom;

      // -180° liegt exakt auf der Kante -> Index 0.
      expect(lonToTileX(-180, zoom), 0);
      // +180° würde ohne Klemmung den Index tileCount ergeben.
      expect(lonToTileX(180, zoom), tileCount - 1);
      // Werte weit außerhalb des gültigen Bereichs bleiben im Indexraum.
      expect(lonToTileX(1000, zoom), tileCount - 1);
      expect(lonToTileX(-1000, zoom), 0);
    });

    test('Breitengrad wird auf die Mercator-Grenze (±85.0511°) geklemmt', () {
      const zoom = 10;
      final tileCount = 1 << zoom;

      // Der Nordpol muss auf die oberste Kachelzeile klemmen (y=0).
      expect(latToTileY(90, zoom), 0);
      // Der Südpol muss auf die unterste Kachelzeile klemmen.
      expect(latToTileY(-90, zoom), tileCount - 1);
      // Exakt an der Mercator-Grenze ergibt sich derselbe Index wie am Pol.
      expect(latToTileY(85.0511, zoom), latToTileY(90, zoom));
      expect(latToTileY(-85.0511, zoom), latToTileY(-90, zoom));
    });

    test('Indizes bleiben stets in [0, 2^z - 1]', () {
      for (final zoom in [0, 1, 2, 5, 18]) {
        final tileCount = 1 << zoom;
        for (final lon in [-180.0, -0.0001, 0.0, 0.0001, 179.9999, 180.0]) {
          final x = lonToTileX(lon, zoom);
          expect(x, inInclusiveRange(0, tileCount - 1));
        }
        for (final lat in [-90.0, -85.0511, 0.0, 85.0511, 90.0]) {
          final y = latToTileY(lat, zoom);
          expect(y, inInclusiveRange(0, tileCount - 1));
        }
      }
    });
  });

  group('estimateTileCount', () {
    test('rechnet für eine einzelne Zoomstufe die Kachelfläche aus', () {
      final bounds = LatLngBounds.unsafe(
        north: 52.53,
        south: 52.51,
        east: 13.42,
        west: 13.39,
      );

      const zoom = 14;
      final xMin = lonToTileX(bounds.west, zoom);
      final xMax = lonToTileX(bounds.east, zoom);
      final yMin = latToTileY(bounds.north, zoom);
      final yMax = latToTileY(bounds.south, zoom);
      final expected = (xMax - xMin + 1) * (yMax - yMin + 1);

      expect(TileCache.estimateTileCount(bounds, zoom, zoom), expected);
    });

    test('summiert über mehrere Zoomstufen', () {
      final bounds = LatLngBounds.unsafe(
        north: 52.53,
        south: 52.51,
        east: 13.42,
        west: 13.39,
      );

      final singleZoom13 = TileCache.estimateTileCount(bounds, 13, 13);
      final singleZoom14 = TileCache.estimateTileCount(bounds, 14, 14);
      final combined = TileCache.estimateTileCount(bounds, 13, 14);

      expect(combined, singleZoom13 + singleZoom14);
    });

    test('ein einzelner Weltpunkt ergibt genau eine Kachel pro Zoomstufe',
        () {
      final bounds = LatLngBounds.unsafe(
        north: 52.52,
        south: 52.52,
        east: 13.405,
        west: 13.405,
      );

      expect(TileCache.estimateTileCount(bounds, 10, 12), 3);
    });
  });

  group('downloadRegion Limit', () {
    test('wirft bei zu großer Region über dem Limit', () async {
      // Die ganze Welt bei Zoom 5 ergibt 32*32 = 1024 Kacheln, deutlich über
      // dem Limit von 250.
      final worldBounds = LatLngBounds.unsafe(
        north: 85,
        south: -85,
        east: 180,
        west: -180,
      );

      final estimate = TileCache.estimateTileCount(worldBounds, 5, 5);
      expect(estimate, greaterThan(maxTilesPerDownload));

      await expectLater(
        () => TileCache.downloadRegion(
          mapStyles.first,
          worldBounds,
          5,
          5,
          (_, _) {},
        ),
        throwsA(
          isA<Exception>().having(
            (e) => e.toString(),
            'message',
            allOf(
              contains('Zu großer Bereich'),
              contains('$estimate'),
              contains('Limit $maxTilesPerDownload'),
            ),
          ),
        ),
      );
    });
  });

  group('cachedTileCount / clearCache', () {
    late Directory tempDir;

    setUp(() {
      tempDir = Directory.systemTemp.createTempSync('trailscape_tile_cache_');
      TileCache.setCacheDirForTesting(tempDir);
    });

    tearDown(() {
      if (tempDir.existsSync()) {
        tempDir.deleteSync(recursive: true);
      }
    });

    test('liefert 0, solange kein Kachel-Verzeichnis existiert', () async {
      expect(await TileCache.cachedTileCount(), 0);
    });

    test('zählt vorab angelegte .png-Dateien rekursiv', () async {
      final tilesDir = Directory('${tempDir.path}/tiles');
      final dummyTiles = [
        '${tilesDir.path}/cyclosm/10/500/300.png',
        '${tilesDir.path}/cyclosm/10/500/301.png',
        '${tilesDir.path}/cyclosm/11/1000/600.png',
      ];

      for (final path in dummyTiles) {
        final file = File(path);
        await file.parent.create(recursive: true);
        await file.writeAsBytes([1, 2, 3]);
      }

      // Nicht-PNG-Dateien im selben Baum dürfen nicht mitgezählt werden.
      final strayFile = File('${tilesDir.path}/cyclosm/10/500/readme.txt');
      await strayFile.parent.create(recursive: true);
      await strayFile.writeAsString('not a tile');

      expect(await TileCache.cachedTileCount(), dummyTiles.length);
    });

    test('zählt Kacheln über mehrere Stil-Verzeichnisse hinweg', () async {
      final tilesDir = Directory('${tempDir.path}/tiles');
      final dummyTiles = [
        '${tilesDir.path}/cyclosm/10/500/300.png',
        '${tilesDir.path}/cyclosm/10/500/301.png',
        '${tilesDir.path}/esri-sat/10/500/300.png',
      ];

      for (final path in dummyTiles) {
        final file = File(path);
        await file.parent.create(recursive: true);
        await file.writeAsBytes([1, 2, 3]);
      }

      expect(await TileCache.cachedTileCount(), 3);
    });

    test('clearCache entfernt das Kachel-Verzeichnis vollständig', () async {
      final tileFile = File('${tempDir.path}/tiles/cyclosm/12/2000/1300.png');
      await tileFile.parent.create(recursive: true);
      await tileFile.writeAsBytes([1, 2, 3]);

      expect(await TileCache.cachedTileCount(), 1);

      await TileCache.clearCache();

      expect(await TileCache.cachedTileCount(), 0);
      expect(Directory('${tempDir.path}/tiles').existsSync(), isFalse);
    });
  });

  group('Kartenstile', () {
    test('CyclOSM ist der erste Eintrag (Standard)', () {
      expect(mapStyles.first.id, 'cyclosm');
      expect(mapStyles.first.label, 'CyclOSM (Fahrrad)');
    });

    test('alle Stil-IDs sind eindeutig', () {
      final ids = mapStyles.map((style) => style.id).toSet();
      expect(ids.length, mapStyles.length);
    });

    test('jedes Template enthält alle drei Platzhalter', () {
      for (final style in mapStyles) {
        expect(style.urlTemplate, contains('{z}'), reason: style.id);
        expect(style.urlTemplate, contains('{x}'), reason: style.id);
        expect(style.urlTemplate, contains('{y}'), reason: style.id);
      }
    });
  });

  group('Platzhalter-Ersetzung', () {
    test('OSM-Template ergibt die korrekte URL', () {
      expect(
        tileUrlFor(_styleById('osm'), 14, 8802, 5373),
        'https://tile.openstreetmap.org/14/8802/5373.png',
      );
    });

    test('CyclOSM-Template ergibt die korrekte URL', () {
      expect(
        tileUrlFor(_styleById('cyclosm'), 14, 8802, 5373),
        'https://a.tile-cyclosm.openstreetmap.fr/cyclosm/14/8802/5373.png',
      );
    });

    test('Esri-Template vertauscht x und y korrekt', () {
      // Esri nutzt die Reihenfolge {z}/{y}/{x}.
      expect(
        tileUrlFor(_styleById('esri-sat'), 14, 8802, 5373),
        'https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/'
        'MapServer/tile/14/5373/8802',
      );
    });
  });

  group('loadMapStyle / saveMapStyle', () {
    test('ohne gespeicherten Wert gilt CyclOSM als Standard', () async {
      SharedPreferences.setMockInitialValues({});
      expect((await loadMapStyle()).id, 'cyclosm');
    });

    test('liest einen gespeicherten Stil zurück', () async {
      SharedPreferences.setMockInitialValues({
        'trailscape.mapstyle': 'opentopo',
      });
      expect((await loadMapStyle()).id, 'opentopo');
    });

    test('unbekannte id fällt auf CyclOSM zurück', () async {
      SharedPreferences.setMockInitialValues({
        'trailscape.mapstyle': 'gibt-es-nicht',
      });
      expect((await loadMapStyle()).id, 'cyclosm');
    });

    test('saveMapStyle wird von loadMapStyle gelesen', () async {
      SharedPreferences.setMockInitialValues({});
      await saveMapStyle('esri-sat');
      expect((await loadMapStyle()).id, 'esri-sat');
    });
  });

  group('Stil-getrennter Cache-Pfad', () {
    late Directory tempDir;

    setUp(() {
      tempDir = Directory.systemTemp.createTempSync('trailscape_tile_style_');
      TileCache.setCacheDirForTesting(tempDir);
    });

    tearDown(() {
      if (tempDir.existsSync()) {
        tempDir.deleteSync(recursive: true);
      }
    });

    test('downloadRegion legt Kacheln unter tiles/<style.id>/ ab', () async {
      // Genau eine Kachel; die Datei existiert bereits, daher kein Netz-IO.
      final bounds = LatLngBounds.unsafe(
        north: 52.52,
        south: 52.52,
        east: 13.405,
        west: 13.405,
      );
      const zoom = 14;
      final style = _styleById('opentopo');
      final x = lonToTileX(13.405, zoom);
      final y = latToTileY(52.52, zoom);

      final tileFile =
          File('${tempDir.path}/tiles/${style.id}/$zoom/$x/$y.png');
      await tileFile.parent.create(recursive: true);
      await tileFile.writeAsBytes([1, 2, 3]);

      final result = await TileCache.downloadRegion(
        style,
        bounds,
        zoom,
        zoom,
        (_, _) {},
      );

      // Die vorhandene Datei im Stil-Verzeichnis wurde gefunden.
      expect(result.skipped, 1);
      expect(result.downloaded, 0);
      expect(result.failed, 0);
    });

    test('ein anderer Stil sieht die Kachel nicht (eigener Unterbaum)',
        () async {
      const zoom = 14;
      final x = lonToTileX(13.405, zoom);
      final y = latToTileY(52.52, zoom);

      final tileFile = File('${tempDir.path}/tiles/opentopo/$zoom/$x/$y.png');
      await tileFile.parent.create(recursive: true);
      await tileFile.writeAsBytes([1, 2, 3]);

      expect(
        File('${tempDir.path}/tiles/cyclosm/$zoom/$x/$y.png').existsSync(),
        isFalse,
      );
      expect(await TileCache.cachedTileCount(), 1);
    });

    test('maxZoom wird auf style.maxZoom gekappt', () async {
      // OpenTopoMap endet bei Zoom 17; eine Anfrage für Zoom 18 über die
      // ganze Welt darf daher nicht am Kachel-Limit scheitern, sondern
      // ergibt schlicht keine Kacheln.
      final worldBounds = LatLngBounds.unsafe(
        north: 85,
        south: -85,
        east: 180,
        west: -180,
      );

      final result = await TileCache.downloadRegion(
        _styleById('opentopo'),
        worldBounds,
        18,
        18,
        (_, _) {},
      );

      expect(result.downloaded, 0);
      expect(result.skipped, 0);
      expect(result.failed, 0);
    });
  });
}
