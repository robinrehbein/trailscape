/// GPX-Import/-Export für Trailscape.
///
/// Parst GPX 1.0/1.1-Dateien (Track- und Routenpunkte) und erzeugt valide
/// GPX-1.1-Dateien aus einer Liste von [TrackPoint]s.
library;

import 'package:xml/xml.dart';

import 'models.dart';

/// Namespace der Garmin-TrackPointExtension, in der die Herzfrequenz je
/// Trackpunkt transportiert wird (`gpxtpx:hr`) — de-facto-Standard, den auch
/// Komoot, Strava & Co. beim GPX-Export verwenden.
const String _garminTrackPointExtensionNs =
    'http://www.garmin.com/xmlschemas/TrackPointExtension/v1';

/// Liest den unqualifizierten (lokalen) Tag-Namen eines Elements.
String _localName(XmlElement el) => el.name.local;

/// Sucht den ersten direkten Kind-Text eines Elements mit gegebenem
/// (unqualifiziertem) Tag-Namen.
String? _findChildText(XmlElement parent, String tagName) {
  for (final child in parent.childElements) {
    if (_localName(child) == tagName) {
      return child.innerText;
    }
  }
  return null;
}

/// Sucht den Text des ersten Nachfahren-Elements mit gegebenem
/// (unqualifiziertem) Tag-Namen — anders als [_findChildText] auch beliebig
/// tief verschachtelt, wie es `gpxtpx:hr` innerhalb von
/// `<extensions><gpxtpx:TrackPointExtension>` ist.
String? _findDescendantText(XmlElement parent, String tagName) {
  for (final el in parent.descendantElements) {
    if (_localName(el) == tagName) {
      final text = el.innerText.trim();
      if (text.isNotEmpty) return text;
    }
  }
  return null;
}

int? _parseTimeToMs(String? raw) {
  if (raw == null) return null;
  final trimmed = raw.trim();
  if (trimmed.isEmpty) return null;
  try {
    return DateTime.parse(trimmed).millisecondsSinceEpoch;
  } on FormatException {
    return null;
  }
}

double? _parseEleM(String? raw) {
  if (raw == null) return null;
  final trimmed = raw.trim();
  if (trimmed.isEmpty) return null;
  final value = double.tryParse(trimmed);
  if (value == null || !value.isFinite) return null;
  return value;
}

int? _parseHrBpm(String? raw) {
  if (raw == null) return null;
  final trimmed = raw.trim();
  if (trimmed.isEmpty) return null;
  final value = double.tryParse(trimmed);
  if (value == null || !value.isFinite) return null;
  return value.round();
}

TrackPoint _parsePoint(XmlElement el) {
  final latRaw = el.getAttribute('lat');
  final lonRaw = el.getAttribute('lon');
  final lat = latRaw != null ? double.tryParse(latRaw.trim()) : null;
  final lon = lonRaw != null ? double.tryParse(lonRaw.trim()) : null;

  if (lat == null || !lat.isFinite || lon == null || !lon.isFinite) {
    throw const FormatException('Ungültige Koordinaten in der GPX-Datei.');
  }

  return TrackPoint(
    lat: lat,
    lon: lon,
    ele: _parseEleM(_findChildText(el, 'ele')),
    time: _parseTimeToMs(_findChildText(el, 'time')),
    hr: _parseHrBpm(_findDescendantText(el, 'hr')),
  );
}

String? _findName(XmlDocument doc) {
  final nameEls = doc.findAllElements('name');

  for (final el in nameEls) {
    final parent = el.parent;
    if (parent is XmlElement && _localName(parent) == 'trk') {
      final text = el.innerText.trim();
      if (text.isNotEmpty) return text;
    }
  }

  for (final el in nameEls) {
    final parent = el.parent;
    if (parent is XmlElement && _localName(parent) == 'metadata') {
      final text = el.innerText.trim();
      if (text.isNotEmpty) return text;
    }
  }

  return null;
}

/// Parst eine GPX-1.0/1.1-Datei (String) und liefert Name sowie alle
/// Trackpunkte in Reihenfolge. Fällt auf Routenpunkte (`rtept`) zurück,
/// falls keine Trackpunkte vorhanden sind.
({String? name, List<TrackPoint> points}) parseGpx(String xmlString) {
  final XmlDocument doc;
  try {
    doc = XmlDocument.parse(xmlString);
  } on XmlException {
    throw const FormatException('Die GPX-Datei enthält ungültiges XML.');
  }

  final root = doc.rootElement;
  if (_localName(root) != 'gpx') {
    throw const FormatException('Die Datei ist keine gültige GPX-Datei.');
  }

  var pointEls = doc.findAllElements('trkpt').toList();
  if (pointEls.isEmpty) {
    pointEls = doc.findAllElements('rtept').toList();
  }

  if (pointEls.isEmpty) {
    throw const FormatException('Die GPX-Datei enthält keine Trackpunkte.');
  }

  final points = pointEls.map(_parsePoint).toList();
  final name = _findName(doc);

  return (name: name, points: points);
}

/// Erzeugt eine valide GPX-1.1-Datei mit einem einzelnen Track/Segment.
///
/// Trägt ein Trackpunkt eine Herzfrequenz ([TrackPoint.hr]), wird sie als
/// Garmin-TrackPointExtension (`gpxtpx:hr`) mitgeschrieben — das Format, das
/// auch Komoot, Strava und die meisten Sportuhren beim GPX-Export nutzen und
/// das beim erneuten Einlesen (siehe [parseGpx]) wieder erkannt wird.
///
/// [time] wird — falls angegeben — zusätzlich als `<metadata><time>`
/// geschrieben (z. B. der Aufnahmezeitpunkt einer Tour).
String buildGpx(String name, List<TrackPoint> points, {DateTime? time}) {
  final hasHr = points.any((p) => p.hr != null);

  final builder = XmlBuilder();
  builder.processing('xml', 'version="1.0" encoding="UTF-8"');
  builder.element(
    'gpx',
    attributes: {
      'version': '1.1',
      'creator': 'Trailscape',
      'xmlns': 'http://www.topografix.com/GPX/1/1',
      if (hasHr) 'xmlns:gpxtpx': _garminTrackPointExtensionNs,
    },
    nest: () {
      builder.element(
        'metadata',
        nest: () {
          builder.element('name', nest: name);
          if (time != null) {
            builder.element(
              'time',
              nest: time.toUtc().toIso8601String(),
            );
          }
        },
      );
      builder.element(
        'trk',
        nest: () {
          builder.element('name', nest: name);
          builder.element(
            'trkseg',
            nest: () {
              for (final point in points) {
                builder.element(
                  'trkpt',
                  attributes: {
                    'lat': '${point.lat}',
                    'lon': '${point.lon}',
                  },
                  nest: () {
                    if (point.ele != null) {
                      builder.element('ele', nest: '${point.ele}');
                    }
                    if (point.time != null) {
                      final iso = DateTime.fromMillisecondsSinceEpoch(
                        point.time!,
                        isUtc: true,
                      ).toIso8601String();
                      builder.element('time', nest: iso);
                    }
                    if (point.hr != null) {
                      builder.element(
                        'extensions',
                        nest: () {
                          builder.element(
                            'gpxtpx:TrackPointExtension',
                            nest: () {
                              builder.element(
                                'gpxtpx:hr',
                                nest: '${point.hr}',
                              );
                            },
                          );
                        },
                      );
                    }
                  },
                );
              }
            },
          );
        },
      );
    },
  );

  final document = builder.buildDocument();
  return '${document.toXmlString(pretty: true, indent: '  ')}\n';
}
