/// Kartenansicht: Aufzeichnung, Routenplanung, Navigation und Offline-Karten.
///
/// Funktional äquivalent zur früheren Web-App (main.ts), aber als
/// idiomatischer Flutter-Screen mit Material 3.
library;

import 'dart:async';
import 'dart:io';
import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter_map/flutter_map.dart';
import 'package:geolocator/geolocator.dart';
import 'package:latlong2/latlong.dart';
import 'package:path_provider/path_provider.dart';
import 'package:share_plus/share_plus.dart';
import 'package:vibration/vibration.dart';
import 'package:wakelock_plus/wakelock_plus.dart';

import '../geocoding.dart';
import '../gpx.dart';
import '../models.dart';
import '../navigation.dart';
import '../recorder.dart';
import '../routing.dart';
import '../state.dart';
import '../stats.dart';
import '../tile_cache.dart';

/// Primärfarbe (Gravel-Grün).
const Color kGreen = Color(0xFF2D5A3D);

/// Warn- und Aufnahmefarbe.
const Color kRed = Color(0xFFB3382C);

/// Farbe der Routenplanung.
const Color kBlue = Color(0xFF2563EB);

const String _userAgent = 'io.github.robinrehbein.trailscape';

const LatLng _germanyCenter = LatLng(51.0, 10.0);
const double _germanyZoom = 6;

/// Zoomstufe, auf die beim ersten aufgezeichneten Punkt mindestens
/// herangezoomt wird.
const double _minRecordingZoom = 15;

const String _planHint =
    'Tippe auf die Karte, um Wegpunkte zu setzen. Tippe auf einen Wegpunkt, '
    'um ihn zu entfernen.';

/// Maximale Höhe des Planungs-Panels als Anteil der Bildschirmhöhe.
const double _planPanelMaxHeightFactor = 0.55;

/// Kartenansicht mit Aufzeichnung, Planung und Navigation.
class MapScreen extends StatefulWidget {
  const MapScreen({super.key, required this.state});

  final AppState state;

  @override
  State<MapScreen> createState() => _MapScreenState();
}

class _MapScreenState extends State<MapScreen> {
  final MapController _mapController = MapController();
  final Recorder _recorder = Recorder();
  final math.Random _random = math.Random();

  bool _mapReady = false;
  List<LatLng>? _pendingFit;

  /// Zuletzt gesehene Auswahl, um Wechsel zu erkennen.
  String? _selectedId;

  // ------------------------------------------------------------ Aufzeichnung
  Timer? _liveTimer;
  List<TrackPoint> _livePoints = const [];
  RideStats? _liveStats;

  // -------------------------------------------------------------- Planung
  bool _planning = false;
  final List<Waypoint> _waypoints = [];
  PlannedRoute? _plannedRoute;
  RouteProfile _routeProfile = RouteProfile.gravel;
  String? _planError;
  bool _planBusy = false;
  int _routeSeq = 0;

  // ------------------------------------------------------------- Ortssuche
  final TextEditingController _searchController = TextEditingController();
  List<GeoResult> _searchResults = const [];
  bool _searchBusy = false;
  String? _searchError;
  int _searchSeq = 0;

  // ------------------------------------------------------------- Navigation
  RouteNavigator? _navigator;
  StreamSubscription<Position>? _navSub;
  String? _navRideId;
  NavState? _navState;
  bool _navOffRoute = false;
  LatLng? _navPosition;

  /// Position aus dem Positions-Button (bleibt stehen, bis sie ersetzt wird).
  LatLng? _myPosition;

  // ---------------------------------------------------------------- Offline
  bool _downloading = false;
  int _downloadDone = 0;
  int _downloadTotal = 0;

  // ------------------------------------------------------------- Kartenstil
  MapStyle _style = mapStyles.first;

  @override
  void initState() {
    super.initState();
    widget.state.addListener(_onStateChanged);

    final selected = widget.state.selected;
    _selectedId = selected?.id;
    if (selected != null && selected.points.isNotEmpty) {
      _pendingFit = selected.points.map(_toLatLng).toList();
    }

    unawaited(_loadStyle());
  }

  Future<void> _loadStyle() async {
    try {
      final style = await loadMapStyle();
      if (!mounted || style.id == _style.id) return;
      setState(() => _style = style);
    } catch (_) {
      // Ohne gespeicherte Einstellung bleibt der Standardstil aktiv.
    }
  }

  @override
  void didUpdateWidget(covariant MapScreen oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.state != widget.state) {
      oldWidget.state.removeListener(_onStateChanged);
      widget.state.addListener(_onStateChanged);
    }
  }

  @override
  void dispose() {
    widget.state.removeListener(_onStateChanged);
    _liveTimer?.cancel();
    _liveTimer = null;
    unawaited(_navSub?.cancel());
    _navSub = null;
    if (_recorder.isRecording) {
      _recorder.stop();
    }
    unawaited(WakelockPlus.disable().catchError((_) {}));
    _searchController.dispose();
    _mapController.dispose();
    super.dispose();
  }

  // ------------------------------------------------------------------ Hilfen

  static LatLng _toLatLng(TrackPoint p) => LatLng(p.lat, p.lon);

  void _snack(String message) {
    if (!mounted) return;
    ScaffoldMessenger.of(context)
      ..hideCurrentSnackBar()
      ..showSnackBar(SnackBar(content: Text(message)));
  }

  static String _errorMessage(Object error) {
    final text = error is Exception ? error.toString() : error.toString();
    return text.startsWith('Exception: ') ? text.substring(11) : text;
  }

  static String _formatDate(int timestampMs) {
    final d = DateTime.fromMillisecondsSinceEpoch(timestampMs);
    final dd = d.day.toString().padLeft(2, '0');
    final mm = d.month.toString().padLeft(2, '0');
    return '$dd.$mm.${d.year}';
  }

  static String _safeFileName(String name) {
    final cleaned = name
        .trim()
        .replaceAll(RegExp(r'[^a-zA-Z0-9\-_]+'), '_')
        .replaceAll(RegExp(r'^_+|_+$'), '');
    return cleaned.isEmpty ? 'tour' : cleaned;
  }

  String _newId() {
    final suffix = _random.nextInt(0x1000000).toRadixString(36);
    return '${DateTime.now().millisecondsSinceEpoch}-$suffix';
  }

  static int _firstTimestamp(List<TrackPoint> points) {
    for (final p in points) {
      final t = p.time;
      if (t != null) return t;
    }
    return DateTime.now().millisecondsSinceEpoch;
  }

  void _onStateChanged() {
    if (!mounted) return;

    final selected = widget.state.selected;
    if (selected?.id != _selectedId) {
      _selectedId = selected?.id;
      if (selected != null && selected.points.isNotEmpty) {
        _fitToPoints(selected.points.map(_toLatLng).toList());
      }
    }

    final navRideId = _navRideId;
    if (navRideId != null &&
        !widget.state.rides.any((ride) => ride.id == navRideId)) {
      _stopNavigation();
      return;
    }

    setState(() {});
  }

  void _fitToPoints(List<LatLng> points) {
    if (points.isEmpty) return;
    if (!_mapReady) {
      _pendingFit = points;
      return;
    }
    _mapController.fitCamera(
      CameraFit.bounds(
        bounds: LatLngBounds.fromPoints(points),
        padding: const EdgeInsets.fromLTRB(48, 120, 48, 200),
        maxZoom: 16,
      ),
    );
  }

  /// Fragt einen Namen ab. Liefert `null`, wenn abgebrochen wurde.
  Future<String?> _askName(String title, String suggestion) async {
    final controller = TextEditingController(text: suggestion);
    try {
      final answer = await showDialog<String>(
        context: context,
        builder: (dialogContext) => AlertDialog(
          title: Text(title),
          content: TextField(
            controller: controller,
            autofocus: true,
            textInputAction: TextInputAction.done,
            decoration: const InputDecoration(labelText: 'Name'),
            onSubmitted: (value) => Navigator.of(dialogContext).pop(value),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(dialogContext).pop(),
              child: const Text('Abbrechen'),
            ),
            FilledButton(
              onPressed: () => Navigator.of(dialogContext).pop(controller.text),
              child: const Text('Speichern'),
            ),
          ],
        ),
      );
      if (answer == null) return null;
      final trimmed = answer.trim();
      return trimmed.isEmpty ? suggestion : trimmed;
    } finally {
      controller.dispose();
    }
  }

  // ------------------------------------------------------------ Aufzeichnung

  Future<void> _toggleRecording() async {
    if (_recorder.isRecording) {
      await _stopRecording();
    } else {
      await _startRecording();
    }
  }

  Future<void> _startRecording() async {
    if (_planning) {
      _exitPlanning();
    }
    widget.state.select(null);

    try {
      await _recorder.start(
        onPoint: _onRecordedPoint,
        onError: (message) => _snack(message),
      );
    } catch (error) {
      _snack(_errorMessage(error));
      return;
    }

    unawaited(WakelockPlus.enable().catchError((_) {}));

    _liveTimer?.cancel();
    _liveTimer = Timer.periodic(const Duration(seconds: 1), (_) {
      if (!mounted) return;
      setState(() {});
    });

    if (!mounted) return;
    setState(() {
      _livePoints = const [];
      _liveStats = null;
    });
  }

  void _onRecordedPoint(TrackPoint point, List<TrackPoint> all) {
    if (!mounted) return;

    final isFirst = _livePoints.isEmpty;
    setState(() {
      _livePoints = List<TrackPoint>.unmodifiable(all);
      _liveStats = computeStats(all);
    });

    if (_mapReady) {
      final zoom = isFirst
          ? math.max(_mapController.camera.zoom, _minRecordingZoom)
          : _mapController.camera.zoom;
      _mapController.move(LatLng(point.lat, point.lon), zoom);
    }
  }

  void _toggleRecordingPause() {
    if (!_recorder.isRecording) return;
    setState(() {
      if (_recorder.isPaused) {
        _recorder.resume();
      } else {
        _recorder.pause();
      }
    });
  }

  Future<void> _stopRecording() async {
    final points = _recorder.stop();

    _liveTimer?.cancel();
    _liveTimer = null;
    if (_navSub == null) {
      unawaited(WakelockPlus.disable().catchError((_) {}));
    }

    if (mounted) {
      setState(() {
        _livePoints = const [];
        _liveStats = null;
      });
    }

    if (points.length < 2) {
      _snack('Zu wenige GPS-Punkte.');
      return;
    }
    if (!mounted) return;

    final suggestion =
        'Tour ${_formatDate(DateTime.now().millisecondsSinceEpoch)}';
    final name = await _askName('Name der Tour', suggestion);
    if (name == null) {
      _snack('Aufzeichnung verworfen.');
      return;
    }

    final ride = Ride(
      id: _newId(),
      name: name,
      createdAt: _firstTimestamp(points),
      points: points,
      stats: computeStats(points),
    );

    try {
      await widget.state.addRide(ride);
    } catch (error) {
      _snack(_errorMessage(error));
    }
  }

  // ---------------------------------------------------------------- Planung

  void _togglePlanning() {
    if (_planning) {
      _exitPlanning();
    } else {
      _enterPlanning();
    }
  }

  void _enterPlanning() {
    if (_recorder.isRecording) {
      _snack('Beende zuerst die Aufzeichnung.');
      return;
    }
    widget.state.select(null);
    setState(() {
      _planning = true;
      _waypoints.clear();
      _plannedRoute = null;
      _planError = null;
      _planBusy = false;
      _routeSeq++;
      _resetSearch();
    });
  }

  void _exitPlanning() {
    setState(() {
      _planning = false;
      _waypoints.clear();
      _plannedRoute = null;
      _planError = null;
      _planBusy = false;
      _routeSeq++;
      _resetSearch();
    });
  }

  /// Setzt den Suchzustand zurück. Muss innerhalb von [setState] laufen.
  void _resetSearch() {
    _searchSeq++;
    _searchResults = const [];
    _searchBusy = false;
    _searchError = null;
    _searchController.clear();
  }

  void _onMapTap(TapPosition tapPosition, LatLng point) {
    if (!_planning) return;
    setState(() {
      _waypoints.add(Waypoint(lat: point.latitude, lon: point.longitude));
    });
    unawaited(_recomputeRoute());
  }

  void _removeWaypoint(int index) {
    if (index < 0 || index >= _waypoints.length) return;
    setState(() => _waypoints.removeAt(index));
    unawaited(_recomputeRoute());
  }

  void _undoWaypoint() {
    if (_waypoints.isEmpty) return;
    setState(() => _waypoints.removeLast());
    unawaited(_recomputeRoute());
  }

  void _clearWaypoints() {
    setState(() {
      _waypoints.clear();
      _plannedRoute = null;
      _planError = null;
      _planBusy = false;
      _routeSeq++;
    });
  }

  Future<void> _recomputeRoute() async {
    final seq = ++_routeSeq;

    if (_waypoints.length < 2) {
      if (!mounted) return;
      setState(() {
        _plannedRoute = null;
        _planError = null;
        _planBusy = false;
      });
      return;
    }

    setState(() {
      _planBusy = true;
      _planError = null;
    });

    final waypoints = List<Waypoint>.unmodifiable(_waypoints);
    try {
      final route = await fetchRoute(waypoints, brouterProfile(_routeProfile));
      if (!mounted || seq != _routeSeq) return;
      setState(() {
        _plannedRoute = route;
        _planError = null;
        _planBusy = false;
      });
    } catch (error) {
      if (!mounted || seq != _routeSeq) return;
      // Wegpunkte bleiben erhalten, damit der Nutzer es erneut versuchen kann.
      setState(() {
        _plannedRoute = null;
        _planError = _errorMessage(error);
        _planBusy = false;
      });
    }
  }

  void _setRouteProfile(RouteProfile profile) {
    if (profile == _routeProfile) return;
    setState(() => _routeProfile = profile);
    unawaited(_recomputeRoute());
  }

  // ------------------------------------------------------------- Ortssuche

  Future<void> _searchPlace() async {
    final query = _searchController.text.trim();
    if (query.isEmpty) return;

    final seq = ++_searchSeq;
    setState(() {
      _searchBusy = true;
      _searchError = null;
    });

    try {
      final results = await searchPlaces(query);
      if (!mounted || seq != _searchSeq) return;
      setState(() {
        _searchResults = results.take(5).toList(growable: false);
        _searchBusy = false;
        _searchError = results.isEmpty ? 'Keine Treffer gefunden.' : null;
      });
    } catch (error) {
      if (!mounted || seq != _searchSeq) return;
      // Suchfeld bleibt erhalten, damit der Nutzer es erneut versuchen kann.
      setState(() {
        _searchResults = const [];
        _searchBusy = false;
        _searchError = _errorMessage(error);
      });
    }
  }

  /// Hängt ein Suchergebnis als letzten Wegpunkt (Ziel) an.
  void _addSearchResult(GeoResult result) {
    final target = LatLng(result.lat, result.lon);
    setState(() {
      _waypoints.add(Waypoint(lat: result.lat, lon: result.lon));
      _resetSearch();
    });
    if (_mapReady) {
      _mapController.move(
        target,
        math.max(_mapController.camera.zoom, _minRecordingZoom),
      );
    }
    unawaited(_recomputeRoute());
  }

  /// Fügt die aktuelle Position als ersten Wegpunkt (Start) ein.
  Future<void> _useMyPositionAsStart() async {
    final position = await _currentPosition();
    if (position == null || !mounted) return;

    setState(() {
      _waypoints.insert(
        0,
        Waypoint(lat: position.latitude, lon: position.longitude),
      );
    });
    if (_mapReady) {
      _mapController.move(
        LatLng(position.latitude, position.longitude),
        math.max(_mapController.camera.zoom, _minRecordingZoom),
      );
    }
    unawaited(_recomputeRoute());
  }

  Future<void> _savePlannedRoute() async {
    final route = _plannedRoute;
    if (route == null) return;

    final suggestion =
        'Route ${_formatDate(DateTime.now().millisecondsSinceEpoch)}';
    final name = await _askName('Name der Route', suggestion);
    if (name == null) return;

    final base = computeStats(route.points);
    final stats = RideStats(
      distanceKm: route.distanceKm,
      durationS: base.durationS,
      movingTimeS: base.movingTimeS,
      avgSpeedKmh: base.avgSpeedKmh,
      ascentM: route.ascentM,
      descentM: base.descentM,
    );

    final ride = Ride(
      id: _newId(),
      name: name,
      createdAt: DateTime.now().millisecondsSinceEpoch,
      points: route.points,
      stats: stats,
    );

    try {
      await widget.state.addRide(ride);
      if (!mounted) return;
      _exitPlanning();
    } catch (error) {
      _snack(_errorMessage(error));
    }
  }

  // ------------------------------------------------------------- Teilen

  Future<void> _shareGpx(String name, List<TrackPoint> points) async {
    if (points.isEmpty) {
      _snack('Keine Punkte zum Teilen.');
      return;
    }
    try {
      final xml = buildGpx(name, points);
      final dir = await getTemporaryDirectory();
      final file = File('${dir.path}/${_safeFileName(name)}.gpx');
      await file.writeAsString(xml);
      await SharePlus.instance.share(
        ShareParams(
          files: [XFile(file.path, mimeType: 'application/gpx+xml')],
          subject: name,
          title: name,
        ),
      );
    } catch (error) {
      _snack('Teilen fehlgeschlagen: ${_errorMessage(error)}');
    }
  }

  // ------------------------------------------------------------- Löschen

  Future<void> _deleteSelected() async {
    final ride = widget.state.selected;
    if (ride == null) return;

    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('Tour löschen'),
        content: Text('Soll „${ride.name}“ wirklich gelöscht werden?'),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(dialogContext).pop(false),
            child: const Text('Abbrechen'),
          ),
          FilledButton(
            style: FilledButton.styleFrom(backgroundColor: kRed),
            onPressed: () => Navigator.of(dialogContext).pop(true),
            child: const Text('Löschen'),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) return;

    if (_navRideId == ride.id) {
      _stopNavigation();
    }

    try {
      await widget.state.removeRide(ride.id);
    } catch (error) {
      _snack(_errorMessage(error));
    }
  }

  // ---------------------------------------------------------------- Navigation

  Future<bool> _ensureLocationPermission() async {
    try {
      if (!await Geolocator.isLocationServiceEnabled()) {
        _snack('Standortdienste sind deaktiviert.');
        return false;
      }
      var permission = await Geolocator.checkPermission();
      if (permission == LocationPermission.denied) {
        permission = await Geolocator.requestPermission();
      }
      if (permission == LocationPermission.denied ||
          permission == LocationPermission.deniedForever) {
        _snack('Standortfreigabe wurde abgelehnt.');
        return false;
      }
      return true;
    } catch (error) {
      _snack(_errorMessage(error));
      return false;
    }
  }

  /// Holt die aktuelle Position inklusive Berechtigungsprüfung.
  /// Liefert `null`, wenn dies fehlschlägt (Meldung erfolgt als SnackBar).
  Future<Position?> _currentPosition() async {
    if (!await _ensureLocationPermission()) return null;
    if (!mounted) return null;
    try {
      return await Geolocator.getCurrentPosition(
        locationSettings: const LocationSettings(
          accuracy: LocationAccuracy.best,
        ),
      );
    } catch (error) {
      _snack(_errorMessage(error));
      return null;
    }
  }

  /// Zentriert die Karte auf die aktuelle Position und zeigt einen Marker.
  Future<void> _goToMyPosition() async {
    final position = await _currentPosition();
    if (position == null || !mounted) return;

    final target = LatLng(position.latitude, position.longitude);
    setState(() => _myPosition = target);

    if (_mapReady) {
      _mapController.move(
        target,
        math.max(_mapController.camera.zoom, _minRecordingZoom),
      );
    }
  }

  Future<void> _startNavigation() async {
    final ride = widget.state.selected;
    if (ride == null) return;

    if (ride.points.length < 2) {
      _snack('Die Tour hat zu wenige Punkte für die Navigation.');
      return;
    }
    if (_navSub != null) {
      _stopNavigation();
    }
    if (!await _ensureLocationPermission()) return;
    if (!mounted) return;

    final RouteNavigator navigator;
    try {
      navigator = RouteNavigator(ride.points);
    } catch (error) {
      _snack(_errorMessage(error));
      return;
    }

    // Eigener Stream, unabhängig von einer eventuell laufenden Aufzeichnung.
    _navSub = Geolocator.getPositionStream(
      locationSettings: const LocationSettings(
        accuracy: LocationAccuracy.best,
        distanceFilter: 5,
      ),
    ).listen(
      _onNavPosition,
      onError: (Object error) {
        _snack(_errorMessage(error));
        _stopNavigation();
      },
    );

    unawaited(WakelockPlus.enable().catchError((_) {}));

    setState(() {
      _navigator = navigator;
      _navRideId = ride.id;
      _navOffRoute = false;
      _navState = null;
      _navPosition = null;
    });
  }

  void _onNavPosition(Position position) {
    final navigator = _navigator;
    if (navigator == null || !mounted) return;

    final state = navigator.update(
      lat: position.latitude,
      lon: position.longitude,
    );

    final wasOffRoute = _navOffRoute;
    setState(() {
      _navState = state;
      _navOffRoute = state.offRoute;
      _navPosition = LatLng(position.latitude, position.longitude);
    });

    if (_mapReady) {
      _mapController.move(
        LatLng(position.latitude, position.longitude),
        _mapController.camera.zoom,
      );
    }

    if (state.offRoute && !wasOffRoute) {
      unawaited(_vibrateOffRoute());
    }
  }

  Future<void> _vibrateOffRoute() async {
    try {
      if (await Vibration.hasVibrator()) {
        await Vibration.vibrate(pattern: const [0, 200, 100, 200]);
      }
    } catch (_) {
      // Vibration ist optional.
    }
  }

  void _stopNavigation() {
    unawaited(_navSub?.cancel());
    _navSub = null;

    if (!_recorder.isRecording) {
      unawaited(WakelockPlus.disable().catchError((_) {}));
    }

    if (!mounted) {
      _navigator = null;
      _navRideId = null;
      _navState = null;
      _navPosition = null;
      _navOffRoute = false;
      return;
    }

    setState(() {
      _navigator = null;
      _navRideId = null;
      _navState = null;
      _navPosition = null;
      _navOffRoute = false;
    });
  }

  // ------------------------------------------------------------------ Offline

  Future<void> _saveRegion() async {
    if (_downloading) return;
    if (!_mapReady) {
      _snack('Karte ist noch nicht bereit.');
      return;
    }

    final camera = _mapController.camera;
    final bounds = camera.visibleBounds;
    final minZoom = camera.zoom.round();
    final maxZoom = math.min(minZoom + 2, 17);

    if (maxZoom < minZoom) {
      _snack('Zoomstufe wird nicht unterstützt.');
      return;
    }

    final estimate = TileCache.estimateTileCount(bounds, minZoom, maxZoom);
    if (estimate > maxTilesPerDownload) {
      _snack(
        'Bereich zu groß: ca. $estimate Kacheln (max. $maxTilesPerDownload). '
        'Zoome näher heran.',
      );
      return;
    }

    setState(() {
      _downloading = true;
      _downloadDone = 0;
      _downloadTotal = estimate;
    });

    try {
      final result = await TileCache.downloadRegion(
        _style,
        bounds,
        minZoom,
        maxZoom,
        (done, total) {
          if (!mounted) return;
          setState(() {
            _downloadDone = done;
            _downloadTotal = total;
          });
        },
      );
      _snack(
        '${result.downloaded} neu, ${result.skipped} vorhanden, '
        '${result.failed} Fehler',
      );
    } catch (error) {
      _snack(_errorMessage(error));
    } finally {
      if (mounted) {
        setState(() => _downloading = false);
      } else {
        _downloading = false;
      }
    }
  }

  // ---------------------------------------------------------- Kartenstil-UI

  /// Öffnet die Stil-Auswahl und übernimmt die Wahl sofort.
  Future<void> _showStyleSheet() async {
    final chosen = await showModalBottomSheet<MapStyle>(
      context: context,
      builder: (sheetContext) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(20, 16, 20, 4),
              child: Text(
                'Kartenstil',
                style: Theme.of(sheetContext)
                    .textTheme
                    .titleMedium
                    ?.copyWith(fontWeight: FontWeight.w700),
              ),
            ),
            RadioGroup<String>(
              groupValue: _style.id,
              onChanged: (value) {
                if (value == null) return;
                Navigator.of(sheetContext).pop(
                  mapStyles.firstWhere((style) => style.id == value),
                );
              },
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  for (final style in mapStyles)
                    AnimatedContainer(
                      duration: const Duration(milliseconds: 200),
                      curve: Curves.easeOutCubic,
                      color: style.id == _style.id
                          ? Theme.of(sheetContext)
                              .colorScheme
                              .primary
                              .withValues(alpha: 0.08)
                          : Colors.transparent,
                      child: RadioListTile<String>(
                        value: style.id,
                        title: Text(style.label),
                        subtitle: switch (style.id) {
                          'voyager' => const Text(
                              'Klar und aufgeräumt (Standard)',
                            ),
                          'cyclosm' => const Text(
                              'Radwege & Wegbeläge hervorgehoben',
                            ),
                          _ => null,
                        },
                      ),
                    ),
                ],
              ),
            ),
            const SizedBox(height: 8),
          ],
        ),
      ),
    );

    if (chosen == null || !mounted) return;
    if (chosen.id != _style.id) {
      setState(() => _style = chosen);
    }
    try {
      await saveMapStyle(chosen.id);
    } catch (error) {
      _snack(_errorMessage(error));
    }
  }

  // -------------------------------------------------------------------- Build

  /// Blendet [child] sanft ein/aus (Fade + leichter Slide), statt hart
  /// umzuschalten. [child] wird unabhängig von [visible] gebaut (billige,
  /// zustandslose Panels), aber nur bei `visible == true` sichtbar animiert.
  static Widget _slideFadeIn({
    required bool visible,
    required Widget child,
    required Offset hiddenOffset,
  }) {
    return AnimatedSwitcher(
      duration: const Duration(milliseconds: 250),
      switchInCurve: Curves.easeOutCubic,
      switchOutCurve: Curves.easeInOutCubic,
      transitionBuilder: (transitionChild, animation) => FadeTransition(
        opacity: animation,
        child: SlideTransition(
          position: Tween<Offset>(begin: hiddenOffset, end: Offset.zero)
              .animate(animation),
          child: transitionChild,
        ),
      ),
      child: visible
          ? KeyedSubtree(key: const ValueKey('visible'), child: child)
          : const SizedBox.shrink(key: ValueKey('hidden')),
    );
  }

  @override
  Widget build(BuildContext context) {
    final selected = widget.state.selected;
    final recording = _recorder.isRecording;
    final navigating = _navigator != null;
    final positionDot = _navPosition ?? _myPosition;

    return Stack(
      fit: StackFit.expand,
      children: [
        FlutterMap(
          mapController: _mapController,
          options: MapOptions(
            initialCenter: _germanyCenter,
            initialZoom: _germanyZoom,
            onTap: _onMapTap,
            onMapReady: () {
              _mapReady = true;
              final pending = _pendingFit;
              _pendingFit = null;
              if (pending != null) {
                WidgetsBinding.instance.addPostFrameCallback((_) {
                  if (mounted) _fitToPoints(pending);
                });
              }
            },
          ),
          children: [
            TileLayer(
              // Der eigene Provider baut die URL selbst aus dem Stil; der
              // Parameter ist aber formal nötig und bleibt konsistent.
              key: ValueKey(_style.id),
              urlTemplate: _style.urlTemplate,
              tileProvider: TileCache.provider(_style),
              userAgentPackageName: _userAgent,
              maxNativeZoom: _style.maxZoom,
              maxZoom: _style.maxZoom.toDouble(),
            ),
            if (selected != null && selected.points.length >= 2)
              PolylineLayer(
                polylines: [
                  Polyline(
                    points: selected.points.map(_toLatLng).toList(),
                    strokeWidth: 4,
                    color: kGreen,
                  ),
                ],
              ),
            if (_plannedRoute != null && _plannedRoute!.points.length >= 2)
              PolylineLayer(
                polylines: [
                  Polyline(
                    points: _plannedRoute!.points.map(_toLatLng).toList(),
                    strokeWidth: 4,
                    color: kBlue,
                    pattern: StrokePattern.dashed(segments: const [12, 8]),
                  ),
                ],
              ),
            if (_livePoints.length >= 2)
              PolylineLayer(
                polylines: [
                  Polyline(
                    points: _livePoints.map(_toLatLng).toList(),
                    strokeWidth: 4,
                    color: kRed,
                  ),
                ],
              ),
            if (_planning && _waypoints.isNotEmpty)
              MarkerLayer(markers: _buildWaypointMarkers()),
            if (positionDot != null)
              MarkerLayer(
                markers: [
                  Marker(
                    point: positionDot,
                    width: 22,
                    height: 22,
                    child: const _PositionDot(),
                  ),
                ],
              ),
            RichAttributionWidget(
              alignment: AttributionAlignment.bottomLeft,
              showFlutterMapAttribution: false,
              attributions: [
                TextSourceAttribution(_style.attribution),
              ],
            ),
          ],
        ),

        // ------------------------------------------------------------ Oben
        Positioned(
          top: 0,
          left: 0,
          right: 0,
          child: SafeArea(
            bottom: false,
            child: Padding(
              padding: const EdgeInsets.fromLTRB(12, 12, 12, 0),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.end,
                children: [
                  _buildTopButtons(navigating),
                  _slideFadeIn(
                    visible: navigating,
                    hiddenOffset: const Offset(0, -0.15),
                    child: Padding(
                      padding: const EdgeInsets.only(top: 8),
                      child: _NavBar(
                        remainingKm: _navState?.remainingKm ??
                            _navigator?.totalKm ??
                            0,
                        offRoute: _navOffRoute,
                        onStop: _stopNavigation,
                      ),
                    ),
                  ),
                  _slideFadeIn(
                    visible: _planning,
                    hiddenOffset: const Offset(0, -0.15),
                    child: Padding(
                      padding: const EdgeInsets.only(top: 8),
                      child: _PlanPanel(
                        routeProfile: _routeProfile,
                        onRouteProfileChanged: _setRouteProfile,
                        searchController: _searchController,
                        searchResults: _searchResults,
                        searchBusy: _searchBusy,
                        searchError: _searchError,
                        onSearch: () => unawaited(_searchPlace()),
                        onResultSelected: _addSearchResult,
                        onUseMyPosition: () =>
                            unawaited(_useMyPositionAsStart()),
                        maxHeight: MediaQuery.sizeOf(context).height *
                            _planPanelMaxHeightFactor,
                        waypointCount: _waypoints.length,
                        route: _plannedRoute,
                        busy: _planBusy,
                        error: _planError,
                        onUndo: _waypoints.isEmpty ? null : _undoWaypoint,
                        onClear: _waypoints.isEmpty ? null : _clearWaypoints,
                        onSave:
                            _plannedRoute == null ? null : _savePlannedRoute,
                        onShare: _plannedRoute == null
                            ? null
                            : () => _shareGpx(
                                  'trailscape-route',
                                  _plannedRoute!.points,
                                ),
                      ),
                    ),
                  ),
                  _slideFadeIn(
                    visible: _downloading,
                    hiddenOffset: const Offset(0, -0.15),
                    child: Padding(
                      padding: const EdgeInsets.only(top: 8),
                      child: _DownloadProgress(
                        done: _downloadDone,
                        total: _downloadTotal,
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ),
        ),

        // ------------------------------------------------------------ Unten
        Positioned(
          left: 0,
          right: 0,
          bottom: 0,
          child: SafeArea(
            top: false,
            child: Padding(
              padding: const EdgeInsets.fromLTRB(12, 0, 12, 12),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.end,
                children: [
                  _PressScale(
                    child: AnimatedContainer(
                      duration: const Duration(milliseconds: 250),
                      curve: Curves.easeInOutCubic,
                      decoration: BoxDecoration(
                        shape: BoxShape.circle,
                        boxShadow: recording
                            ? [
                                BoxShadow(
                                  color: kRed.withValues(alpha: 0.35),
                                  blurRadius: 16,
                                  spreadRadius: 1,
                                ),
                              ]
                            : const [],
                      ),
                      child: FloatingActionButton(
                        heroTag: 'trailscape-record-fab',
                        backgroundColor:
                            Theme.of(context).colorScheme.surface,
                        tooltip: recording
                            ? 'Aufzeichnung beenden'
                            : 'Aufzeichnung starten',
                        onPressed: () => unawaited(_toggleRecording()),
                        child: AnimatedSwitcher(
                          duration: const Duration(milliseconds: 200),
                          switchInCurve: Curves.easeOutCubic,
                          switchOutCurve: Curves.easeInOutCubic,
                          transitionBuilder: (transitionChild, animation) =>
                              ScaleTransition(
                            scale: animation,
                            child: FadeTransition(
                              opacity: animation,
                              child: transitionChild,
                            ),
                          ),
                          child: Icon(
                            recording ? Icons.stop : Icons.fiber_manual_record,
                            key: ValueKey(recording),
                            color: recording ? kRed : kGreen,
                          ),
                        ),
                      ),
                    ),
                  ),
                  const SizedBox(height: 12),
                  _PressScale(
                    child: FloatingActionButton(
                      heroTag: 'trailscape-location-fab',
                      backgroundColor: kGreen,
                      foregroundColor: Colors.white,
                      tooltip: 'Meine Position',
                      onPressed: () => unawaited(_goToMyPosition()),
                      child:
                          const Icon(Icons.my_location, color: Colors.white),
                    ),
                  ),
                  const SizedBox(height: 12),
                  AnimatedSwitcher(
                    duration: const Duration(milliseconds: 250),
                    switchInCurve: Curves.easeOutCubic,
                    switchOutCurve: Curves.easeInOutCubic,
                    transitionBuilder: (transitionChild, animation) =>
                        FadeTransition(
                      opacity: animation,
                      child: SlideTransition(
                        position: Tween<Offset>(
                          begin: const Offset(0, 0.15),
                          end: Offset.zero,
                        ).animate(animation),
                        child: transitionChild,
                      ),
                    ),
                    child: recording
                        ? KeyedSubtree(
                            key: const ValueKey('live-bar'),
                            child: _LiveBar(
                              speedKmh: _recorder.currentSpeedKmh,
                              stats: _liveStats,
                              elapsedS: _elapsedSeconds(),
                              paused: _recorder.isPaused,
                              onTogglePause: _toggleRecordingPause,
                              onStop: () => unawaited(_stopRecording()),
                            ),
                          )
                        : selected != null
                            ? KeyedSubtree(
                                key: ValueKey('stats-${selected.id}'),
                                child: _StatsCard(
                                  ride: selected,
                                  navigating: _navRideId == selected.id,
                                  onNavigate: () =>
                                      unawaited(_startNavigation()),
                                  onShare: () => unawaited(
                                    _shareGpx(selected.name, selected.points),
                                  ),
                                  onDelete: () => unawaited(_deleteSelected()),
                                  onClose: () => widget.state.select(null),
                                ),
                              )
                            : const SizedBox.shrink(key: ValueKey('empty')),
                  ),
                ],
              ),
            ),
          ),
        ),
      ],
    );
  }

  int _elapsedSeconds() {
    final startedAt = _recorder.startedAt;
    if (startedAt == null) return 0;
    final elapsedMs =
        DateTime.now().millisecondsSinceEpoch - startedAt - _recorder.pausedMs;
    return elapsedMs <= 0 ? 0 : (elapsedMs / 1000).round();
  }

  Widget _buildTopButtons(bool navigating) {
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        if (!navigating)
          _MapButton(
            label: _planning ? 'Planung beenden' : 'Route planen',
            icon: _planning ? Icons.close : Icons.route_outlined,
            active: _planning,
            activeColor: kBlue,
            onPressed: _togglePlanning,
          ),
        const SizedBox(width: 8),
        Material(
          color: Theme.of(context).colorScheme.surface,
          elevation: 2,
          shape: const CircleBorder(),
          clipBehavior: Clip.antiAlias,
          child: IconButton(
            tooltip: 'Kartenstil',
            onPressed: () => unawaited(_showStyleSheet()),
            icon: const Icon(Icons.layers),
          ),
        ),
        const SizedBox(width: 8),
        Material(
          color: Theme.of(context).colorScheme.surface,
          elevation: 2,
          shape: const CircleBorder(),
          clipBehavior: Clip.antiAlias,
          child: IconButton(
            tooltip: 'Region offline speichern',
            onPressed: _downloading ? null : () => unawaited(_saveRegion()),
            icon: const Icon(Icons.download_for_offline_outlined),
          ),
        ),
      ],
    );
  }

  List<Marker> _buildWaypointMarkers() {
    final last = _waypoints.length - 1;
    return [
      for (var i = 0; i < _waypoints.length; i++)
        Marker(
          point: LatLng(_waypoints[i].lat, _waypoints[i].lon),
          width: 28,
          height: 28,
          child: GestureDetector(
            onTap: () => _removeWaypoint(i),
            child: _WaypointDot(
              color: i == 0
                  ? kGreen
                  : i == last
                      ? kRed
                      : kBlue,
            ),
          ),
        ),
    ];
  }
}

// ---------------------------------------------------------------- Teil-Widgets

/// Skaliert [child] beim Antippen leicht herunter (dezentes Press-Feedback).
///
/// Nutzt [Listener] statt [GestureDetector], damit die eigentliche
/// Tap-Erkennung des Kindes (z. B. eines [FloatingActionButton]) unberührt
/// bleibt.
class _PressScale extends StatefulWidget {
  const _PressScale({required this.child});

  final Widget child;

  @override
  State<_PressScale> createState() => _PressScaleState();
}

class _PressScaleState extends State<_PressScale> {
  bool _pressed = false;

  void _setPressed(bool value) {
    if (_pressed == value) return;
    setState(() => _pressed = value);
  }

  @override
  Widget build(BuildContext context) {
    return Listener(
      onPointerDown: (_) => _setPressed(true),
      onPointerUp: (_) => _setPressed(false),
      onPointerCancel: (_) => _setPressed(false),
      child: AnimatedScale(
        scale: _pressed ? 0.92 : 1,
        duration: const Duration(milliseconds: 150),
        curve: Curves.easeOutCubic,
        child: widget.child,
      ),
    );
  }
}

class _MapButton extends StatelessWidget {
  const _MapButton({
    required this.label,
    required this.icon,
    required this.active,
    required this.activeColor,
    required this.onPressed,
  });

  final String label;
  final IconData icon;
  final bool active;
  final Color activeColor;
  final VoidCallback onPressed;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Material(
      color: active ? activeColor : scheme.surface,
      elevation: 2,
      borderRadius: BorderRadius.circular(24),
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: onPressed,
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(
                icon,
                size: 18,
                color: active ? Colors.white : scheme.onSurface,
              ),
              const SizedBox(width: 6),
              Text(
                label,
                style: TextStyle(
                  fontWeight: FontWeight.w600,
                  color: active ? Colors.white : scheme.onSurface,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _PositionDot extends StatelessWidget {
  const _PositionDot();

  @override
  Widget build(BuildContext context) {
    return DecoratedBox(
      decoration: BoxDecoration(
        color: kBlue,
        shape: BoxShape.circle,
        border: Border.all(color: Colors.white, width: 3),
        boxShadow: const [
          BoxShadow(color: Color(0x552563EB), blurRadius: 8, spreadRadius: 2),
        ],
      ),
    );
  }
}

class _WaypointDot extends StatelessWidget {
  const _WaypointDot({required this.color});

  final Color color;

  @override
  Widget build(BuildContext context) {
    return DecoratedBox(
      decoration: BoxDecoration(
        color: color,
        shape: BoxShape.circle,
        border: Border.all(color: Colors.white, width: 3),
        boxShadow: const [
          BoxShadow(color: Color(0x33000000), blurRadius: 4),
        ],
      ),
    );
  }
}

/// Ein großer Wert mit Beschriftung (Live-Leiste und Statistik-Karte).
class _Metric extends StatelessWidget {
  const _Metric({
    required this.value,
    required this.label,
    this.big = false,
  });

  final String value;
  final String label;
  final bool big;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final valueStyle = (big
            ? theme.textTheme.headlineSmall
            : theme.textTheme.titleMedium)
        ?.copyWith(
      fontWeight: FontWeight.w700,
      fontFeatures: const [FontFeature.tabularFigures()],
    );
    return Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        ClipRect(
          child: AnimatedSwitcher(
            duration: const Duration(milliseconds: 200),
            switchInCurve: Curves.easeOutCubic,
            switchOutCurve: Curves.easeInOutCubic,
            transitionBuilder: (child, animation) => FadeTransition(
              opacity: animation,
              child: SlideTransition(
                position: Tween<Offset>(
                  begin: const Offset(0, 0.3),
                  end: Offset.zero,
                ).animate(animation),
                child: child,
              ),
            ),
            child: Text(
              value,
              key: ValueKey(value),
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: valueStyle,
            ),
          ),
        ),
        Text(
          label,
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
          style: theme.textTheme.labelSmall?.copyWith(
            color: theme.colorScheme.onSurfaceVariant,
          ),
        ),
      ],
    );
  }
}

/// Statistik-Karte der ausgewählten Tour.
class _StatsCard extends StatelessWidget {
  const _StatsCard({
    required this.ride,
    required this.navigating,
    required this.onNavigate,
    required this.onShare,
    required this.onDelete,
    required this.onClose,
  });

  final Ride ride;
  final bool navigating;
  final VoidCallback onNavigate;
  final VoidCallback onShare;
  final VoidCallback onDelete;
  final VoidCallback onClose;

  @override
  Widget build(BuildContext context) {
    final stats = ride.stats;
    final avg = stats.avgSpeedKmh;

    return Card(
      elevation: 4,
      margin: EdgeInsets.zero,
      child: Padding(
        padding: const EdgeInsets.fromLTRB(16, 12, 8, 12),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Expanded(
                  child: Text(
                    ride.name,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: Theme.of(context)
                        .textTheme
                        .titleMedium
                        ?.copyWith(fontWeight: FontWeight.w700),
                  ),
                ),
                IconButton(
                  tooltip: 'Auswahl aufheben',
                  onPressed: onClose,
                  icon: const Icon(Icons.close),
                ),
              ],
            ),
            const SizedBox(height: 4),
            Padding(
              padding: const EdgeInsets.only(right: 8),
              child: Row(
                children: [
                  Expanded(
                    child: _Metric(
                      value: formatKm(stats.distanceKm),
                      label: 'km',
                    ),
                  ),
                  Expanded(
                    child: _Metric(
                      value: formatDuration(stats.durationS),
                      label: 'Dauer',
                    ),
                  ),
                  Expanded(
                    child: _Metric(
                      value: avg == null ? '–' : avg.toStringAsFixed(1),
                      label: 'Ø km/h',
                    ),
                  ),
                  Expanded(
                    child: _Metric(
                      value: '${stats.ascentM.round()}',
                      label: 'Hm ↑',
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(height: 8),
            Padding(
              padding: const EdgeInsets.only(right: 8),
              child: Row(
                children: [
                  Expanded(
                    child: FilledButton.icon(
                      style: FilledButton.styleFrom(backgroundColor: kGreen),
                      onPressed: navigating ? null : onNavigate,
                      icon: const Icon(Icons.navigation_outlined, size: 18),
                      label: const Text('Navigieren'),
                    ),
                  ),
                  const SizedBox(width: 8),
                  IconButton.filledTonal(
                    tooltip: 'Teilen',
                    onPressed: onShare,
                    icon: const Icon(Icons.ios_share),
                  ),
                  const SizedBox(width: 4),
                  IconButton(
                    tooltip: 'Löschen',
                    color: kRed,
                    onPressed: onDelete,
                    icon: const Icon(Icons.delete_outline),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// Live-Leiste während der Aufzeichnung.
class _LiveBar extends StatelessWidget {
  const _LiveBar({
    required this.speedKmh,
    required this.stats,
    required this.elapsedS,
    required this.paused,
    required this.onTogglePause,
    required this.onStop,
  });

  final double? speedKmh;
  final RideStats? stats;
  final int elapsedS;
  final bool paused;
  final VoidCallback onTogglePause;
  final VoidCallback onStop;

  @override
  Widget build(BuildContext context) {
    final distanceKm = stats?.distanceKm ?? 0;
    final ascentM = stats?.ascentM ?? 0;

    return Card(
      elevation: 4,
      margin: EdgeInsets.zero,
      child: AnimatedOpacity(
        opacity: paused ? 0.55 : 1,
        duration: const Duration(milliseconds: 200),
        curve: Curves.easeInOutCubic,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(16, 12, 16, 12),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  const Icon(Icons.fiber_manual_record, size: 12, color: kRed),
                  const SizedBox(width: 6),
                  AnimatedSwitcher(
                    duration: const Duration(milliseconds: 200),
                    switchInCurve: Curves.easeOutCubic,
                    switchOutCurve: Curves.easeInOutCubic,
                    transitionBuilder: (child, animation) =>
                        FadeTransition(opacity: animation, child: child),
                    child: Text(
                      paused ? 'Pausiert' : 'Aufzeichnung läuft',
                      key: ValueKey(paused),
                      style:
                          Theme.of(context).textTheme.labelMedium?.copyWith(
                                color: kRed,
                                fontWeight: FontWeight.w700,
                              ),
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 8),
              Row(
                children: [
                  Expanded(
                    child: _Metric(
                      big: true,
                      value: speedKmh == null ? '–' : speedKmh!.toStringAsFixed(1),
                      label: 'km/h',
                    ),
                  ),
                  Expanded(
                    child: _Metric(
                      big: true,
                      value: formatKm(distanceKm),
                      label: 'km',
                    ),
                  ),
                  Expanded(
                    child: _Metric(
                      big: true,
                      value: formatDuration(elapsedS),
                      label: 'Zeit',
                    ),
                  ),
                  Expanded(
                    child: _Metric(
                      big: true,
                      value: '${ascentM.round()}',
                      label: 'Hm ↑',
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 8),
              Row(
                children: [
                  Expanded(
                    child: OutlinedButton.icon(
                      onPressed: onTogglePause,
                      icon: Icon(
                        paused ? Icons.play_arrow : Icons.pause,
                        size: 18,
                      ),
                      label: Text(paused ? 'Weiter' : 'Pause'),
                    ),
                  ),
                  const SizedBox(width: 8),
                  Expanded(
                    child: FilledButton.icon(
                      style: FilledButton.styleFrom(backgroundColor: kRed),
                      onPressed: onStop,
                      icon: const Icon(Icons.stop_rounded, size: 18),
                      label: const Text('Beenden'),
                    ),
                  ),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/// Leiste während der Navigation.
class _NavBar extends StatelessWidget {
  const _NavBar({
    required this.remainingKm,
    required this.offRoute,
    required this.onStop,
  });

  final double remainingKm;
  final bool offRoute;
  final VoidCallback onStop;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Card(
      elevation: 4,
      margin: EdgeInsets.zero,
      child: Padding(
        padding: const EdgeInsets.fromLTRB(16, 12, 8, 12),
        child: Row(
          children: [
            Expanded(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    '${formatKm(remainingKm)} km übrig',
                    style: theme.textTheme.headlineSmall?.copyWith(
                      fontWeight: FontWeight.w700,
                      color: kGreen,
                      fontFeatures: const [FontFeature.tabularFigures()],
                    ),
                  ),
                  if (offRoute)
                    Padding(
                      padding: const EdgeInsets.only(top: 2),
                      child: Text(
                        '⚠️ Abseits der Route',
                        style: theme.textTheme.titleSmall?.copyWith(
                          color: kRed,
                          fontWeight: FontWeight.w700,
                        ),
                      ),
                    ),
                ],
              ),
            ),
            TextButton(
              onPressed: onStop,
              child: const Text('Beenden'),
            ),
          ],
        ),
      ),
    );
  }
}

/// Panel der Routenplanung.
class _PlanPanel extends StatelessWidget {
  const _PlanPanel({
    required this.routeProfile,
    required this.onRouteProfileChanged,
    required this.searchController,
    required this.searchResults,
    required this.searchBusy,
    required this.searchError,
    required this.onSearch,
    required this.onResultSelected,
    required this.onUseMyPosition,
    required this.maxHeight,
    required this.waypointCount,
    required this.route,
    required this.busy,
    required this.error,
    required this.onUndo,
    required this.onClear,
    required this.onSave,
    required this.onShare,
  });

  final RouteProfile routeProfile;
  final ValueChanged<RouteProfile> onRouteProfileChanged;
  final TextEditingController searchController;
  final List<GeoResult> searchResults;
  final bool searchBusy;
  final String? searchError;
  final VoidCallback onSearch;
  final ValueChanged<GeoResult> onResultSelected;
  final VoidCallback onUseMyPosition;
  final double maxHeight;
  final int waypointCount;
  final PlannedRoute? route;
  final bool busy;
  final String? error;
  final VoidCallback? onUndo;
  final VoidCallback? onClear;
  final VoidCallback? onSave;
  final VoidCallback? onShare;

  String get _info {
    final current = route;
    if (current != null) {
      return '${formatKm(current.distanceKm)} km · '
          '${current.ascentM.round()} Hm ↑ · $waypointCount Wegpunkte';
    }
    if (waypointCount == 1) {
      return '1 Wegpunkt – setze mindestens 2.';
    }
    if (waypointCount > 1) {
      return '$waypointCount Wegpunkte – berechne Route …';
    }
    return _planHint;
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final errorText = error;

    return Card(
      elevation: 4,
      margin: EdgeInsets.zero,
      child: ConstrainedBox(
        constraints: BoxConstraints(maxHeight: math.max(maxHeight, 160)),
        child: SingleChildScrollView(
          padding: const EdgeInsets.fromLTRB(16, 12, 16, 12),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              // ------------------------------------------------ Ziel-Suche
              TextField(
                controller: searchController,
                textInputAction: TextInputAction.search,
                onSubmitted: (_) => onSearch(),
                decoration: InputDecoration(
                  isDense: true,
                  hintText: 'Ort, Stadt oder Straße suchen…',
                  border: const OutlineInputBorder(),
                  prefixIcon: const Icon(Icons.place_outlined, size: 20),
                  suffixIcon: searchBusy
                      ? const Padding(
                          padding: EdgeInsets.all(12),
                          child: SizedBox(
                            width: 18,
                            height: 18,
                            child: CircularProgressIndicator(strokeWidth: 2),
                          ),
                        )
                      : IconButton(
                          tooltip: 'Suchen',
                          onPressed: onSearch,
                          icon: const Icon(Icons.search),
                        ),
                ),
              ),
              if (searchError != null)
                Padding(
                  padding: const EdgeInsets.only(top: 6),
                  child: Text(
                    searchError!,
                    style: theme.textTheme.bodySmall?.copyWith(
                      color: kRed,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                ),
              AnimatedSwitcher(
                duration: const Duration(milliseconds: 200),
                switchInCurve: Curves.easeOutCubic,
                switchOutCurve: Curves.easeInOutCubic,
                transitionBuilder: (child, animation) => FadeTransition(
                  opacity: animation,
                  child: SlideTransition(
                    position: Tween<Offset>(
                      begin: const Offset(0, -0.06),
                      end: Offset.zero,
                    ).animate(animation),
                    child: child,
                  ),
                ),
                child: searchResults.isEmpty
                    ? const SizedBox.shrink(key: ValueKey('no-results'))
                    : Padding(
                        key: ValueKey(
                          searchResults
                              .map((r) => '${r.lat},${r.lon}')
                              .join('|'),
                        ),
                        padding: const EdgeInsets.only(top: 4),
                        child: Column(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            for (final result in searchResults.take(5))
                              ListTile(
                                dense: true,
                                visualDensity: VisualDensity.compact,
                                contentPadding: EdgeInsets.zero,
                                leading: const Icon(
                                  Icons.location_on_outlined,
                                  size: 20,
                                  color: kBlue,
                                ),
                                title: Text(
                                  result.displayName,
                                  maxLines: 2,
                                  overflow: TextOverflow.ellipsis,
                                  style: theme.textTheme.bodySmall,
                                ),
                                onTap: () => onResultSelected(result),
                              ),
                          ],
                        ),
                      ),
              ),
              const SizedBox(height: 4),
              Align(
                alignment: Alignment.centerLeft,
                child: TextButton.icon(
                  onPressed: onUseMyPosition,
                  icon: const Icon(Icons.my_location, size: 18),
                  label: const Text('Meine Position als Start'),
                ),
              ),
              const Divider(height: 12),

              // ------------------------------------------ Profil-Auswahl
              Row(
                children: [
                  const Icon(Icons.route_outlined, size: 18, color: kBlue),
                  const SizedBox(width: 8),
                  Expanded(
                    child: DropdownButtonHideUnderline(
                      child: DropdownButton<RouteProfile>(
                        isExpanded: true,
                        value: routeProfile,
                        hint: const Text('Routenprofil'),
                        onChanged: (value) {
                          if (value != null) onRouteProfileChanged(value);
                        },
                        items: [
                          for (final entry in routeProfileLabels.entries)
                            DropdownMenuItem(
                              value: entry.key,
                              child: Text(entry.value),
                            ),
                        ],
                      ),
                    ),
                  ),
                  if (busy)
                    const SizedBox(
                      width: 18,
                      height: 18,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    ),
                ],
              ),
              const SizedBox(height: 4),
              Text(
                errorText ?? _info,
                style: theme.textTheme.bodySmall?.copyWith(
                  color: errorText != null
                      ? kRed
                      : theme.colorScheme.onSurfaceVariant,
                  fontWeight:
                      errorText != null ? FontWeight.w600 : FontWeight.normal,
                ),
              ),
              const SizedBox(height: 8),
              Wrap(
                spacing: 8,
                runSpacing: 4,
                children: [
                  TextButton.icon(
                    onPressed: onUndo,
                    icon: const Icon(Icons.undo, size: 18),
                    label: const Text('Rückgängig'),
                  ),
                  TextButton.icon(
                    onPressed: onClear,
                    icon: const Icon(Icons.clear_all, size: 18),
                    label: const Text('Leeren'),
                  ),
                  FilledButton.icon(
                    style: FilledButton.styleFrom(backgroundColor: kBlue),
                    onPressed: onSave,
                    icon: const Icon(Icons.save_outlined, size: 18),
                    label: const Text('Speichern'),
                  ),
                  TextButton.icon(
                    onPressed: onShare,
                    icon: const Icon(Icons.ios_share, size: 18),
                    label: const Text('Teilen'),
                  ),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/// Fortschrittsanzeige des Kachel-Downloads.
class _DownloadProgress extends StatelessWidget {
  const _DownloadProgress({required this.done, required this.total});

  final int done;
  final int total;

  @override
  Widget build(BuildContext context) {
    final value = total > 0 ? (done / total).clamp(0.0, 1.0) : null;
    return Card(
      elevation: 4,
      margin: EdgeInsets.zero,
      child: Padding(
        padding: const EdgeInsets.fromLTRB(16, 12, 16, 12),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              'Lade Kacheln … $done/$total',
              style: Theme.of(context).textTheme.bodySmall,
            ),
            const SizedBox(height: 6),
            LinearProgressIndicator(value: value, color: kGreen),
          ],
        ),
      ),
    );
  }
}
