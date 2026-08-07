/// Offline-Kachel-Cache für die Kartenanzeige.
///
/// Kacheln werden als PNG-Dateien unter
/// `<AppDocuments>/tiles/<stil-id>/<z>/<x>/<y>.png` abgelegt — jeder
/// Kartenstil ([MapStyle]) bekommt also ein eigenes Unterverzeichnis.
/// [TileCache.provider] liefert einen [TileProvider] für
/// [TileLayer.tileProvider], der Kacheln zunächst aus dem Datei-Cache liest
/// und nur bei einem Cache-Miss per HTTP nachlädt (Write-through: das
/// Ergebnis landet danach im Cache). [TileCache.downloadRegion] lädt eine
/// Region vorab herunter, damit sie offline verfügbar ist.
///
/// Die Kachel-Mathematik (Slippy-Map-Konvention) entspricht der Web-App-
/// Referenz (offline.ts).
library;

import 'dart:async';
import 'dart:io';
import 'dart:math';
import 'dart:ui' as ui;

import 'package:flutter/foundation.dart';
import 'package:flutter/painting.dart';
import 'package:flutter_map/flutter_map.dart';
import 'package:http/http.dart' as http;
import 'package:path_provider/path_provider.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// Obergrenze für einen Offline-Download, damit die Kachel-Server nicht
/// überlastet werden.
const int maxTilesPerDownload = 250;

/// Ein auswählbarer Kartenstil (Kachel-Quelle).
class MapStyle {
  const MapStyle({
    required this.id,
    required this.label,
    required this.urlTemplate,
    required this.maxZoom,
    required this.attribution,
  });

  /// Stabiler Schlüssel für das Cache-Verzeichnis und shared_preferences.
  final String id;

  /// Anzeigename in der Stil-Auswahl.
  final String label;

  /// Kachel-URL mit den Platzhaltern `{z}`, `{x}` und `{y}` in beliebiger
  /// Reihenfolge (Esri nutzt etwa `{z}/{y}/{x}`).
  final String urlTemplate;

  /// Höchste vom Anbieter unterstützte Zoomstufe.
  final int maxZoom;

  /// Attributionstext, der auf der Karte eingeblendet wird.
  final String attribution;
}

/// Alle auswählbaren Kartenstile. Der erste Eintrag ist der Standard.
const List<MapStyle> mapStyles = [
  MapStyle(
    id: 'voyager',
    label: 'Straßenkarte',
    urlTemplate:
        'https://a.basemaps.cartocdn.com/rastertiles/voyager/{z}/{x}/{y}.png',
    maxZoom: 20,
    attribution: '© OpenStreetMap-Mitwirkende © CARTO',
  ),
  MapStyle(
    id: 'cyclosm',
    label: 'CyclOSM (Fahrrad)',
    urlTemplate:
        'https://a.tile-cyclosm.openstreetmap.fr/cyclosm/{z}/{x}/{y}.png',
    maxZoom: 19,
    attribution: '© OpenStreetMap-Mitwirkende · Stil: CyclOSM',
  ),
  MapStyle(
    id: 'osm',
    label: 'OpenStreetMap',
    urlTemplate: 'https://tile.openstreetmap.org/{z}/{x}/{y}.png',
    maxZoom: 19,
    attribution: '© OpenStreetMap-Mitwirkende',
  ),
  MapStyle(
    id: 'opentopo',
    label: 'OpenTopoMap (Gelände)',
    urlTemplate: 'https://a.tile.opentopomap.org/{z}/{x}/{y}.png',
    maxZoom: 17,
    attribution:
        '© OpenStreetMap-Mitwirkende · SRTM · Stil: OpenTopoMap (CC-BY-SA)',
  ),
  MapStyle(
    id: 'esri-sat',
    label: 'Satellit (Esri)',
    urlTemplate: 'https://server.arcgisonline.com/ArcGIS/rest/services/'
        'World_Imagery/MapServer/tile/{z}/{y}/{x}',
    maxZoom: 19,
    attribution: 'Esri, Maxar, Earthstar Geographics',
  ),
];

const String _mapStyleStorageKey = 'trailscape.mapstyle';

/// Standard-Kartenstil, wenn nichts (Gültiges) gespeichert ist.
MapStyle get _defaultMapStyle => mapStyles.first;

/// Liest den gespeicherten Kartenstil. Unbekannte oder fehlende IDs fallen
/// auf den Standard (Straßenkarte/Voyager) zurück.
Future<MapStyle> loadMapStyle() async {
  final prefs = await SharedPreferences.getInstance();
  final id = prefs.getString(_mapStyleStorageKey);
  if (id == null) {
    return _defaultMapStyle;
  }
  for (final style in mapStyles) {
    if (style.id == id) {
      return style;
    }
  }
  return _defaultMapStyle;
}

/// Speichert die gewählte Kartenstil-ID.
Future<void> saveMapStyle(String id) async {
  final prefs = await SharedPreferences.getInstance();
  await prefs.setString(_mapStyleStorageKey, id);
}

/// Höflichkeits-User-Agent gemäß der OSM-Tile-Nutzungsrichtlinie.
const String _userAgent =
    'Trailscape/1.0 (github.com/robinrehbein/trailscape)';

const int _maxParallelFetches = 4;
const double _maxLatitude = 85.0511;

/// Transparentes 1x1-PNG als Platzhalter, wenn eine Kachel weder aus dem
/// Cache noch über das Netz geladen werden kann.
final Uint8List _transparentPixelPng = Uint8List.fromList(<int>[
  0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, //
  0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52, //
  0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01, //
  0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, 0xC4, //
  0x89, 0x00, 0x00, 0x00, 0x0A, 0x49, 0x44, 0x41, //
  0x54, 0x78, 0x9C, 0x63, 0x00, 0x01, 0x00, 0x00, //
  0x05, 0x00, 0x01, 0x0D, 0x0A, 0x2D, 0xB4, 0x00, //
  0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, 0xAE, //
  0x42, 0x60, 0x82, //
]);

double _clampD(double value, double min, double max) {
  if (value < min) return min;
  if (value > max) return max;
  return value;
}

int _clampI(int value, int min, int max) {
  if (value < min) return min;
  if (value > max) return max;
  return value;
}

int _tileCountAtZoom(int zoom) => 1 << zoom;

/// Slippy-Tile-X-Index einer Längengrad-Koordinate bei gegebenem Zoom.
///
/// Sichtbar für Tests. Klemmt das Ergebnis auf `[0, 2^zoom - 1]`.
@visibleForTesting
int lonToTileX(double lon, int zoom) {
  final n = _tileCountAtZoom(zoom);
  final x = (((lon + 180) / 360) * n).floor();
  return _clampI(x, 0, n - 1);
}

/// Slippy-Tile-Y-Index einer Breitengrad-Koordinate bei gegebenem Zoom.
///
/// Sichtbar für Tests. Klemmt die Breite zunächst auf `±85.0511°` (Mercator-
/// Grenze) und das Ergebnis auf `[0, 2^zoom - 1]`.
@visibleForTesting
int latToTileY(double lat, int zoom) {
  final n = _tileCountAtZoom(zoom);
  final rad = _clampD(lat, -_maxLatitude, _maxLatitude) * (pi / 180);
  final y = (((1 - log(tan(rad) + 1 / cos(rad)) / pi) / 2) * n).floor();
  return _clampI(y, 0, n - 1);
}

class _TileRect {
  const _TileRect(this.xMin, this.xMax, this.yMin, this.yMax);

  final int xMin;
  final int xMax;
  final int yMin;
  final int yMax;
}

/// Kachelrechteck einer Region auf einer Zoomstufe. Liefert `null` für leere
/// Rechtecke, etwa wenn Ost westlich von West liegt (Antimeridian wird nicht
/// unterstützt).
_TileRect? _tileRect(LatLngBounds bounds, int zoom) {
  if (bounds.east < bounds.west || bounds.north < bounds.south) {
    return null;
  }

  return _TileRect(
    lonToTileX(bounds.west, zoom),
    lonToTileX(bounds.east, zoom),
    latToTileY(bounds.north, zoom),
    latToTileY(bounds.south, zoom),
  );
}

List<int> _normalizeZoomRange(int minZoom, int maxZoom) {
  final from = minZoom < 0 ? 0 : minZoom;
  final zooms = <int>[];
  for (var zoom = from; zoom <= maxZoom; zoom++) {
    zooms.add(zoom);
  }
  return zooms;
}

/// Baut die Kachel-URL aus [MapStyle.urlTemplate] durch Ersetzen der
/// Platzhalter `{z}`, `{x}` und `{y}`. Die Reihenfolge im Template ist
/// beliebig (Esri nutzt `{z}/{y}/{x}`).
///
/// Sichtbar für Tests.
@visibleForTesting
String tileUrlFor(MapStyle style, int z, int x, int y) => style.urlTemplate
    .replaceAll('{z}', '$z')
    .replaceAll('{x}', '$x')
    .replaceAll('{y}', '$y');

typedef _TileKey = ({int z, int x, int y});

Future<void> _writeTileFileAtomically(File file, List<int> bytes) async {
  final dir = file.parent;
  if (!await dir.exists()) {
    await dir.create(recursive: true);
  }
  final tmpFile = File('${file.path}.tmp');
  await tmpFile.writeAsBytes(bytes, flush: true);
  await tmpFile.rename(file.path);
}

/// Offline-Kachel-Cache: Datei-Cache-first [TileProvider], Vorab-Download von
/// Regionen sowie Verwaltung des Caches.
class TileCache {
  TileCache._();

  static Directory? _cacheDirOverride;

  /// Ersetzt das Basisverzeichnis, unter dem der Kachel-Cache angelegt wird.
  /// Für Tests gedacht, um [getApplicationDocumentsDirectory] nicht
  /// tatsächlich aufzurufen.
  static void setCacheDirForTesting(Directory dir) {
    _cacheDirOverride = dir;
  }

  static Future<Directory> _baseDir() async =>
      _cacheDirOverride ?? await getApplicationDocumentsDirectory();

  static Future<Directory> _tilesDir() async {
    final base = await _baseDir();
    return Directory('${base.path}/tiles');
  }

  static Future<File> _tileFile(MapStyle style, int z, int x, int y) async {
    final tiles = await _tilesDir();
    return File('${tiles.path}/${style.id}/$z/$x/$y.png');
  }

  /// [TileProvider] für `TileLayer(tileProvider: TileCache.provider(style))`:
  /// liefert Kacheln aus dem Datei-Cache, sonst per Netz mit Write-through in
  /// den Cache. Jeder Stil hat sein eigenes Cache-Unterverzeichnis.
  static TileProvider provider(MapStyle style) => _CachingTileProvider(style);

  /// Anzahl der aktuell offline vorgehaltenen Kacheln über alle Stile
  /// (`.png`-Dateien im Kachel-Cache, rekursiv gezählt).
  static Future<int> cachedTileCount() async {
    final tiles = await _tilesDir();
    if (!await tiles.exists()) {
      return 0;
    }

    var count = 0;
    await for (final entry in tiles.list(recursive: true)) {
      if (entry is File && entry.path.endsWith('.png')) {
        count++;
      }
    }
    return count;
  }

  /// Löscht den gesamten Kachel-Cache.
  static Future<void> clearCache() async {
    final tiles = await _tilesDir();
    if (await tiles.exists()) {
      await tiles.delete(recursive: true);
    }
  }

  /// Zählt die Kacheln einer Region über alle Zoomstufen — reine Rechnung
  /// ohne IO.
  static int estimateTileCount(
    LatLngBounds bounds,
    int minZoom,
    int maxZoom,
  ) {
    var total = 0;
    for (final zoom in _normalizeZoomRange(minZoom, maxZoom)) {
      final rect = _tileRect(bounds, zoom);
      if (rect != null) {
        total += (rect.xMax - rect.xMin + 1) * (rect.yMax - rect.yMin + 1);
      }
    }
    return total;
  }

  static List<_TileKey> _collectTiles(
    LatLngBounds bounds,
    int minZoom,
    int maxZoom,
  ) {
    final tiles = <_TileKey>[];
    for (final zoom in _normalizeZoomRange(minZoom, maxZoom)) {
      final rect = _tileRect(bounds, zoom);
      if (rect == null) continue;

      for (var x = rect.xMin; x <= rect.xMax; x++) {
        for (var y = rect.yMin; y <= rect.yMax; y++) {
          tiles.add((z: zoom, x: x, y: y));
        }
      }
    }
    return tiles;
  }

  /// Lädt alle Kacheln einer Region für [style] herunter und legt sie im
  /// Datei-Cache des Stils ab (write-through). Bereits vorhandene Dateien
  /// werden übersprungen (`skipped`), Netzfehler zählen als `failed`, ohne
  /// den Download abzubrechen.
  ///
  /// [maxZoom] wird zusätzlich auf [MapStyle.maxZoom] gekappt.
  ///
  /// Wirft eine [Exception], falls die Region mehr als
  /// [maxTilesPerDownload] Kacheln umfasst.
  static Future<({int downloaded, int skipped, int failed})> downloadRegion(
    MapStyle style,
    LatLngBounds bounds,
    int minZoom,
    int maxZoom,
    void Function(int done, int total) onProgress,
  ) async {
    final effectiveMaxZoom = min(maxZoom, style.maxZoom);
    final estimate = estimateTileCount(bounds, minZoom, effectiveMaxZoom);
    if (estimate > maxTilesPerDownload) {
      throw Exception(
        'Zu großer Bereich: $estimate Kacheln (Limit $maxTilesPerDownload). '
        'Zoome näher heran.',
      );
    }

    final tiles = _collectTiles(bounds, minZoom, effectiveMaxZoom);
    final total = tiles.length;

    var nextIndex = 0;
    var done = 0;
    var downloaded = 0;
    var skipped = 0;
    var failed = 0;

    final client = http.Client();
    try {
      Future<void> worker() async {
        while (true) {
          final index = nextIndex;
          nextIndex += 1;
          if (index >= total) {
            return;
          }

          final tile = tiles[index];
          try {
            final file = await _tileFile(style, tile.z, tile.x, tile.y);
            if (await file.exists()) {
              skipped += 1;
            } else {
              final url = tileUrlFor(style, tile.z, tile.x, tile.y);
              final response = await client.get(
                Uri.parse(url),
                headers: const {'User-Agent': _userAgent},
              );
              if (response.statusCode != 200) {
                throw HttpException('HTTP ${response.statusCode} für $url');
              }
              await _writeTileFileAtomically(file, response.bodyBytes);
              downloaded += 1;
            }
          } catch (_) {
            failed += 1;
          }

          done += 1;
          onProgress(done, total);
        }
      }

      final workerCount =
          total < _maxParallelFetches ? total : _maxParallelFetches;
      await Future.wait(List.generate(workerCount, (_) => worker()));
    } finally {
      client.close();
    }

    return (downloaded: downloaded, skipped: skipped, failed: failed);
  }
}

/// [TileProvider], der Kacheln zunächst aus dem lokalen Datei-Cache liefert
/// und bei einem Cache-Miss per HTTP nachlädt (Write-through).
class _CachingTileProvider extends TileProvider {
  _CachingTileProvider(this.style);

  final MapStyle style;

  @override
  ImageProvider getImage(TileCoordinates coordinates, TileLayer options) {
    return _CachedTileImageProvider(
      style: style,
      z: coordinates.z,
      x: coordinates.x,
      y: coordinates.y,
    );
  }
}

/// [ImageProvider], der eine einzelne Kachel lädt: zuerst Datei-Cache, sonst
/// Netz + Write-through. Der Cache-Key ist die Kachel-URL (via `==`/
/// [hashCode]), damit gleiche Kacheln nicht mehrfach angefragt werden und die
/// Karte nicht flackert.
@immutable
class _CachedTileImageProvider
    extends ImageProvider<_CachedTileImageProvider> {
  const _CachedTileImageProvider({
    required this.style,
    required this.z,
    required this.x,
    required this.y,
  });

  final MapStyle style;
  final int z;
  final int x;
  final int y;

  String get _url => tileUrlFor(style, z, x, y);

  @override
  Future<_CachedTileImageProvider> obtainKey(
    ImageConfiguration configuration,
  ) =>
      SynchronousFuture<_CachedTileImageProvider>(this);

  @override
  ImageStreamCompleter loadImage(
    _CachedTileImageProvider key,
    ImageDecoderCallback decode,
  ) {
    return MultiFrameImageStreamCompleter(
      codec: _load(key, decode),
      scale: 1.0,
      debugLabel: key._url,
    );
  }

  Future<ui.Codec> _load(
    _CachedTileImageProvider key,
    ImageDecoderCallback decode,
  ) async {
    try {
      final file = await TileCache._tileFile(key.style, key.z, key.x, key.y);
      if (await file.exists()) {
        final bytes = await file.readAsBytes();
        return decode(await ui.ImmutableBuffer.fromUint8List(bytes));
      }

      final response = await http.get(
        Uri.parse(key._url),
        headers: const {'User-Agent': _userAgent},
      );
      if (response.statusCode != 200) {
        throw HttpException('HTTP ${response.statusCode} für ${key._url}');
      }

      final bytes = response.bodyBytes;
      unawaited(_writeTileFileAtomically(file, bytes).catchError((_) {}));
      return decode(await ui.ImmutableBuffer.fromUint8List(bytes));
    } catch (_) {
      return decode(
        await ui.ImmutableBuffer.fromUint8List(_transparentPixelPng),
      );
    }
  }

  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      (other is _CachedTileImageProvider && other._url == _url);

  @override
  int get hashCode => _url.hashCode;

  @override
  String toString() => '_CachedTileImageProvider("$_url")';
}
