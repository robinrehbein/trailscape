/// "Mehr"-Tab: Offline-Karten-Verwaltung, Selfhost-Sync, Health Connect und
/// Info.
library;

import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:intl/intl.dart';

import '../health_sync.dart';
import '../state.dart';
import '../storage.dart';
import '../sync_client.dart';
import '../tile_cache.dart';
import '../training_load.dart';
import 'map_screen.dart' show kGreen;

/// Sanftes Einblenden (Fade + Slide-up) für Karten beim ersten Aufbau.
///
/// Die Karten werden gestaffelt eingeblendet (~40 ms Versatz pro Index),
/// der Zustand bleibt danach pro Element erhalten.
class _EntranceFade extends StatefulWidget {
  const _EntranceFade({required this.index, required this.child});

  final int index;
  final Widget child;

  @override
  State<_EntranceFade> createState() => _EntranceFadeState();
}

class _EntranceFadeState extends State<_EntranceFade> {
  bool _visible = false;

  @override
  void initState() {
    super.initState();
    final delay = Duration(milliseconds: 40 * widget.index.clamp(0, 10));
    Future.delayed(delay, () {
      if (mounted) setState(() => _visible = true);
    });
  }

  @override
  Widget build(BuildContext context) {
    return AnimatedSlide(
      duration: const Duration(milliseconds: 250),
      curve: Curves.easeOutCubic,
      offset: _visible ? Offset.zero : const Offset(0, 0.08),
      child: AnimatedOpacity(
        duration: const Duration(milliseconds: 250),
        curve: Curves.easeOutCubic,
        opacity: _visible ? 1 : 0,
        child: widget.child,
      ),
    );
  }
}

class MoreScreen extends StatefulWidget {
  const MoreScreen({super.key, required this.state});

  final AppState state;

  @override
  State<MoreScreen> createState() => _MoreScreenState();
}

class _MoreScreenState extends State<MoreScreen> {
  late Future<int> _tileCountFuture;

  final _urlController = TextEditingController();
  final _tokenController = TextEditingController();
  String? _syncStatus;
  bool _syncing = false;

  HealthConnection? _healthConnection;
  DateTime? _healthLastSyncAt;
  bool _healthBusy = false;

  final _ageController = TextEditingController();
  final _weightController = TextEditingController();
  final _setupMassController = TextEditingController();
  final _hrMaxController = TextEditingController();
  final _lthrController = TextEditingController();
  final _restingHrController = TextEditingController();
  Sex _sex = Sex.unbekannt;
  bool _profileAdvancedOpen = false;
  String? _profileStatus;

  /// Signatur des zuletzt in die Felder übernommenen Profils. Verhindert,
  /// dass ein Rebuild (z. B. nach einem Health-Sync) die Eingaben überschreibt.
  String? _profileSignature;

  @override
  void initState() {
    super.initState();
    _tileCountFuture = TileCache.cachedTileCount();
    _loadSyncConfig();
    _loadHealthStatus();
    _adoptProfile();
    widget.state.addListener(_onStateChanged);
  }

  @override
  void dispose() {
    widget.state.removeListener(_onStateChanged);
    _urlController.dispose();
    _tokenController.dispose();
    _ageController.dispose();
    _weightController.dispose();
    _setupMassController.dispose();
    _hrMaxController.dispose();
    _lthrController.dispose();
    _restingHrController.dispose();
    super.dispose();
  }

  void _onStateChanged() {
    if (!mounted) return;
    setState(_adoptProfile);
  }

  /// Übernimmt das Profil aus dem AppState in die Eingabefelder — aber nur,
  /// wenn es sich tatsächlich geändert hat (z. B. beim asynchronen Laden
  /// nach dem App-Start).
  void _adoptProfile() {
    final profile = widget.state.profile;
    final signature = jsonEncode(profile.toJson());
    if (signature == _profileSignature) {
      return;
    }
    _profileSignature = signature;
    _ageController.text = profile.ageYears.toString();
    _weightController.text = _formatNumber(profile.weightKg);
    _setupMassController.text = _formatNumber(profile.setupMassKg);
    _hrMaxController.text = profile.hrMaxOverride != null
        ? _formatNumber(profile.hrMaxOverride!)
        : '';
    _lthrController.text =
        profile.lthrOverride != null ? _formatNumber(profile.lthrOverride!) : '';
    _restingHrController.text = profile.restingHrOverride != null
        ? _formatNumber(profile.restingHrOverride!)
        : '';
    _sex = profile.sex;
  }

  static String _formatNumber(double value) => value == value.roundToDouble()
      ? value.toInt().toString()
      : value.toString();

  static double? _parseNumber(String raw) {
    final trimmed = raw.trim().replaceAll(',', '.');
    if (trimmed.isEmpty) {
      return null;
    }
    return double.tryParse(trimmed);
  }

  Future<void> _loadSyncConfig() async {
    final config = await getSyncConfig();
    if (!mounted || config == null) return;
    setState(() {
      _urlController.text = config.url;
      _tokenController.text = config.token;
    });
  }

  void _refreshTileCount() {
    setState(() {
      _tileCountFuture = TileCache.cachedTileCount();
    });
  }

  Future<void> _clearTileCache() async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Kacheln löschen'),
        content: const Text(
          'Sollen alle gespeicherten Kartenkacheln wirklich gelöscht werden?',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('Abbrechen'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text('Löschen'),
          ),
        ],
      ),
    );
    if (confirmed != true) return;

    await TileCache.clearCache();
    _refreshTileCount();
  }

  Future<void> _runSync() async {
    final url = _urlController.text.trim();
    final token = _tokenController.text.trim();

    if (url.isEmpty || token.isEmpty) {
      setState(() => _syncStatus = 'Bitte Server-URL und Token eintragen.');
      return;
    }

    setState(() {
      _syncing = true;
      _syncStatus = 'Synchronisiere …';
    });

    try {
      await setSyncConfig(SyncConfig(url: url, token: token));
      final result = await syncRides(listLocal: listRides, saveLocal: saveRide);
      if (!mounted) return;
      setState(() {
        _syncStatus =
            '${result.pushed} hochgeladen, ${result.pulled} geladen, '
            '${result.total} Touren';
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _syncStatus = e.toString().replaceFirst('Exception: ', '');
      });
    } finally {
      if (mounted) setState(() => _syncing = false);
    }
  }

  Future<void> _loadHealthStatus() async {
    final connection = await widget.state.healthSync.checkAvailability();
    final lastSync = await widget.state.healthSync.lastImportAt();
    if (!mounted) return;
    setState(() {
      _healthConnection = connection;
      _healthLastSyncAt = lastSync;
    });
  }

  Future<void> _connectHealth() async {
    setState(() => _healthBusy = true);
    try {
      await widget.state.healthSync.requestPermissions();
    } finally {
      if (mounted) setState(() => _healthBusy = false);
    }
    await _loadHealthStatus();
  }

  Future<void> _installHealthConnect() async {
    final gateway = widget.state.healthSync.gateway;
    if (gateway is! HealthPluginGateway) {
      return;
    }
    setState(() => _healthBusy = true);
    try {
      await gateway.installHealthConnect();
    } finally {
      if (mounted) setState(() => _healthBusy = false);
    }
    await _loadHealthStatus();
  }

  Future<void> _syncHealth({required bool reimportAll}) async {
    setState(() => _healthBusy = true);
    try {
      final count = await widget.state.syncHealthNow(reimportAll: reimportAll);
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(
            count > 0
                ? '$count ${count == 1 ? 'Tour' : 'Touren'} importiert'
                : 'Keine neuen Touren',
          ),
        ),
      );
    } on HealthSyncException catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text(e.message)));
      }
    } finally {
      if (mounted) setState(() => _healthBusy = false);
    }
    await _loadHealthStatus();
  }

  Future<void> _saveProfile() async {
    final age = int.tryParse(_ageController.text.trim());
    if (age == null || age < 10 || age > 100) {
      setState(() => _profileStatus = 'Bitte ein Alter zwischen 10 und 100 '
          'Jahren angeben.');
      return;
    }
    final weight = _parseNumber(_weightController.text);
    if (weight == null || weight < 30 || weight > 250) {
      setState(() => _profileStatus =
          'Bitte ein Gewicht zwischen 30 und 250 kg angeben.');
      return;
    }
    final setupMass = _parseNumber(_setupMassController.text);
    if (setupMass != null && (setupMass < 0 || setupMass > 60)) {
      setState(() => _profileStatus =
          'Das Gewicht von Rad und Gepäck sollte unter 60 kg liegen.');
      return;
    }

    final profile = TrainingProfile(
      ageYears: age,
      sex: _sex,
      weightKg: weight,
      setupMassKg: setupMass ?? defaultSetupMassKg,
      hrMaxOverride: _parseNumber(_hrMaxController.text),
      lthrOverride: _parseNumber(_lthrController.text),
      restingHrOverride: _parseNumber(_restingHrController.text),
      cda: widget.state.profile.cda,
      crr: widget.state.profile.crr,
      driveEfficiency: widget.state.profile.driveEfficiency,
      eftpOverrideW: widget.state.profile.eftpOverrideW,
    );

    await widget.state.setProfile(profile);
    if (!mounted) return;
    setState(() => _profileStatus = 'Profil gespeichert.');
  }

  Widget _buildProfileCard(BuildContext context) {
    final theme = Theme.of(context);
    final hint = theme.textTheme.bodySmall?.copyWith(
      color: theme.colorScheme.onSurfaceVariant,
    );

    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('Profil', style: theme.textTheme.titleMedium),
            const SizedBox(height: 12),
            Text(
              'Alter, Geschlecht und Gewicht sind die Grundlage für '
              'Trainingslast, Fitness-Kurve und Erholungswerte.',
              style: hint,
            ),
            const SizedBox(height: 12),
            Row(
              children: [
                Expanded(
                  child: TextField(
                    controller: _ageController,
                    decoration: const InputDecoration(labelText: 'Alter'),
                    keyboardType: TextInputType.number,
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: DropdownButtonFormField<Sex>(
                    initialValue: _sex,
                    decoration: const InputDecoration(labelText: 'Geschlecht'),
                    items: const [
                      DropdownMenuItem(
                        value: Sex.maennlich,
                        child: Text('männlich'),
                      ),
                      DropdownMenuItem(
                        value: Sex.weiblich,
                        child: Text('weiblich'),
                      ),
                      DropdownMenuItem(
                        value: Sex.unbekannt,
                        child: Text('keine Angabe'),
                      ),
                    ],
                    onChanged: (value) {
                      if (value != null) setState(() => _sex = value);
                    },
                  ),
                ),
              ],
            ),
            const SizedBox(height: 12),
            Row(
              children: [
                Expanded(
                  child: TextField(
                    controller: _weightController,
                    decoration: const InputDecoration(labelText: 'Gewicht (kg)'),
                    keyboardType: const TextInputType.numberWithOptions(
                      decimal: true,
                    ),
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: TextField(
                    controller: _setupMassController,
                    decoration: const InputDecoration(
                      labelText: 'Rad + Gepäck (kg)',
                    ),
                    keyboardType: const TextInputType.numberWithOptions(
                      decimal: true,
                    ),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 4),
            Text('Ohne Angabe rechnen wir mit '
                '${_formatNumber(defaultSetupMassKg)} kg für Rad und Gepäck.',
                style: hint),
            const SizedBox(height: 8),
            InkWell(
              onTap: () => setState(
                () => _profileAdvancedOpen = !_profileAdvancedOpen,
              ),
              child: Padding(
                padding: const EdgeInsets.symmetric(vertical: 8),
                child: Row(
                  children: [
                    Icon(
                      _profileAdvancedOpen
                          ? Icons.expand_less
                          : Icons.expand_more,
                      size: 20,
                      color: theme.colorScheme.onSurfaceVariant,
                    ),
                    const SizedBox(width: 4),
                    Text('Erweitert', style: theme.textTheme.titleSmall),
                  ],
                ),
              ),
            ),
            AnimatedSize(
              duration: const Duration(milliseconds: 200),
              curve: Curves.easeOutCubic,
              alignment: Alignment.topCenter,
              child: _profileAdvancedOpen
                  ? Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          'Ohne eigene Werte schätzen wir die maximale '
                          'Herzfrequenz aus deinem Alter (208 − 0,7 × Alter) '
                          'und die Schwelle daraus. Ein HFmax-Feldtest — nach '
                          'gutem Aufwärmen ein harter Anstieg über 3–5 Minuten '
                          'mit maximalem Endspurt — verbessert die Genauigkeit '
                          'aller Auswertungen deutlich.',
                          style: hint,
                        ),
                        const SizedBox(height: 12),
                        TextField(
                          controller: _hrMaxController,
                          decoration: const InputDecoration(
                            labelText: 'HFmax (bpm, optional)',
                          ),
                          keyboardType: TextInputType.number,
                        ),
                        const SizedBox(height: 12),
                        TextField(
                          controller: _lthrController,
                          decoration: const InputDecoration(
                            labelText: 'Schwellenpuls LTHR (bpm, optional)',
                          ),
                          keyboardType: TextInputType.number,
                        ),
                        const SizedBox(height: 12),
                        TextField(
                          controller: _restingHrController,
                          decoration: const InputDecoration(
                            labelText: 'Ruhepuls (bpm, optional)',
                          ),
                          keyboardType: TextInputType.number,
                        ),
                        const SizedBox(height: 4),
                        Text(
                          'Ohne eigenen Ruhepuls nehmen wir den aus deinen '
                          'Vitaldaten gemessenen Wert.',
                          style: hint,
                        ),
                      ],
                    )
                  : const SizedBox(width: double.infinity),
            ),
            const SizedBox(height: 12),
            if (_profileStatus != null) ...[
              Text(
                _profileStatus!,
                style: TextStyle(color: theme.colorScheme.primary),
              ),
              const SizedBox(height: 8),
            ],
            FilledButton(
              onPressed: _saveProfile,
              child: const Text('Profil speichern'),
            ),
          ],
        ),
      ),
    );
  }

  /// Diagnose des letzten Import-Laufs (siehe [HealthSyncReport]).
  List<Widget> _buildHealthDiagnostics(BuildContext context) {
    final theme = Theme.of(context);
    final report = widget.state.lastSyncReport;
    if (report == null) {
      return const [];
    }

    final hint = theme.textTheme.bodySmall?.copyWith(
      color: theme.colorScheme.onSurfaceVariant,
    );

    return [
      const SizedBox(height: 12),
      Text(
        '${report.workoutsFound} '
        '${report.workoutsFound == 1 ? 'Workout' : 'Workouts'} gefunden · '
        '${report.imported.length} importiert · '
        '${report.mergedRides.length} mit Puls angereichert · '
        '${report.duplicatesSkipped} '
        '${report.duplicatesSkipped == 1 ? 'Duplikat' : 'Duplikate'}',
        style: hint,
      ),
      if (report.workoutsFound == 0) ...[
        const SizedBox(height: 8),
        _notice(
          context,
          icon: Icons.info_outline,
          color: theme.colorScheme.onSurfaceVariant,
          text: 'Keine Workouts im Zeitraum — prüfe in Samsung Health, ob die '
              'Health-Connect-Synchronisierung aktiv ist.',
        ),
      ],
    ];
  }

  Widget _notice(
    BuildContext context, {
    required IconData icon,
    required Color color,
    required String text,
  }) {
    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.12),
        borderRadius: BorderRadius.circular(8),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(icon, size: 20, color: color),
          const SizedBox(width: 8),
          Expanded(child: Text(text)),
        ],
      ),
    );
  }

  Widget _buildHealthCard(BuildContext context) {
    final theme = Theme.of(context);
    final connection = _healthConnection;
    final dateFormat = DateFormat('dd.MM.yyyy, HH:mm', 'de_DE');
    final routesMissing = widget.state.lastSyncReport?.routesMissing ?? 0;

    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('Samsung Health', style: theme.textTheme.titleMedium),
            const SizedBox(height: 12),
            if (connection == null)
              const Text('Prüfe Verbindung …')
            else
              Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Icon(
                    connection.isReady
                        ? Icons.check_circle
                        : Icons.info_outline,
                    size: 20,
                    color: connection.isReady
                        ? kGreen
                        : theme.colorScheme.onSurfaceVariant,
                  ),
                  const SizedBox(width: 8),
                  Expanded(child: Text(connection.message)),
                ],
              ),
            const SizedBox(height: 12),
            Wrap(
              spacing: 12,
              runSpacing: 8,
              children: [
                if (connection?.availability ==
                    HealthAvailability.nichtInstalliert)
                  FilledButton(
                    onPressed: _healthBusy ? null : _installHealthConnect,
                    child: const Text('Health Connect installieren'),
                  )
                else if (connection?.needsPermissions ?? false)
                  FilledButton(
                    onPressed: _healthBusy ? null : _connectHealth,
                    child: const Text('Verbinden'),
                  )
                else if (connection?.isReady ?? false) ...[
                  FilledButton(
                    onPressed: _healthBusy
                        ? null
                        : () => _syncHealth(reimportAll: false),
                    child: _healthBusy
                        ? const SizedBox(
                            width: 18,
                            height: 18,
                            child:
                                CircularProgressIndicator(strokeWidth: 2),
                          )
                        : const Text('Jetzt synchronisieren'),
                  ),
                  OutlinedButton(
                    onPressed: _healthBusy
                        ? null
                        : () => _syncHealth(reimportAll: true),
                    child: const Text('Alles neu importieren'),
                  ),
                ],
              ],
            ),
            if (_healthLastSyncAt != null) ...[
              const SizedBox(height: 12),
              Text(
                'Letzter Sync: ${dateFormat.format(_healthLastSyncAt!)}',
                style: theme.textTheme.bodySmall?.copyWith(
                  color: theme.colorScheme.onSurfaceVariant,
                ),
              ),
            ],
            ..._buildHealthDiagnostics(context),
            const SizedBox(height: 12),
            if (routesMissing > 0)
              _notice(
                context,
                icon: Icons.route,
                color: Colors.orange.shade800,
                text: 'Für $routesMissing '
                    '${routesMissing == 1 ? 'importierte Tour' : 'importierte Touren'} '
                    'hat Health Connect keine Route geliefert. Erlaube unter '
                    '„App-Berechtigungen → Trailscape → Trainingsrouten" den '
                    'dauerhaften Zugriff, damit die aufgezeichnete Strecke '
                    'mitkommt.',
              )
            else
              Text(
                'Damit auch die aufgezeichnete Route mit importiert wird, '
                'erlaube in Health Connect unter „App-Berechtigungen → '
                'Trailscape → Trainingsrouten" den dauerhaften Zugriff. Ohne '
                'diese Freigabe werden Distanz, Dauer und Herzfrequenz '
                'trotzdem übernommen.',
                style: theme.textTheme.bodySmall?.copyWith(
                  color: theme.colorScheme.onSurfaceVariant,
                ),
              ),
            const SizedBox(height: 8),
            Text(
              '„Alles neu importieren" betrachtet wieder die letzten '
              '${healthSyncInitialWindow.inDays} Tage.',
              style: theme.textTheme.bodySmall?.copyWith(
                color: theme.colorScheme.onSurfaceVariant,
              ),
            ),
          ],
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    return Scaffold(
      appBar: AppBar(title: const Text('Mehr')),
      body: Center(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 640),
          child: ListView(
            padding: const EdgeInsets.all(16),
            children: [
              _EntranceFade(
                index: 0,
                child: Card(
                  child: Padding(
                    padding: const EdgeInsets.all(16),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          'Offline-Karten',
                          style: theme.textTheme.titleMedium,
                        ),
                        const SizedBox(height: 12),
                        FutureBuilder<int>(
                          future: _tileCountFuture,
                          builder: (context, snapshot) {
                            if (snapshot.connectionState !=
                                ConnectionState.done) {
                              return const Text('Lade …');
                            }
                            if (snapshot.hasError) {
                              return const Text(
                                'Kachelanzahl nicht verfügbar.',
                              );
                            }
                            return Text(
                              '${snapshot.data ?? 0} Kacheln gespeichert',
                            );
                          },
                        ),
                        const SizedBox(height: 12),
                        OutlinedButton(
                          onPressed: _clearTileCache,
                          child: const Text('Kacheln löschen'),
                        ),
                        const SizedBox(height: 12),
                        Text(
                          'Der Download von Kacheln für die Offline-Nutzung '
                          'läuft über das Karten-Symbol auf der Karte.',
                          style: theme.textTheme.bodySmall?.copyWith(
                            color: theme.colorScheme.onSurfaceVariant,
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
              ),
              const SizedBox(height: 16),
              _EntranceFade(
                index: 1,
                child: Card(
                  child: Padding(
                    padding: const EdgeInsets.all(16),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          'Sync (Selfhost)',
                          style: theme.textTheme.titleMedium,
                        ),
                        const SizedBox(height: 12),
                        TextField(
                          controller: _urlController,
                          decoration: const InputDecoration(
                            labelText: 'Server-URL',
                          ),
                          keyboardType: TextInputType.url,
                        ),
                        const SizedBox(height: 8),
                        TextField(
                          controller: _tokenController,
                          decoration: const InputDecoration(labelText: 'Token'),
                          obscureText: true,
                        ),
                        const SizedBox(height: 12),
                        FilledButton(
                          onPressed: _syncing ? null : _runSync,
                          child: _syncing
                              ? const SizedBox(
                                  width: 18,
                                  height: 18,
                                  child: CircularProgressIndicator(
                                    strokeWidth: 2,
                                  ),
                                )
                              : const Text('Jetzt synchronisieren'),
                        ),
                        if (_syncStatus != null) ...[
                          const SizedBox(height: 12),
                          Text(_syncStatus!),
                        ],
                        const SizedBox(height: 12),
                        Text(
                          'Details zum Aufsetzen eines eigenen Sync-Servers '
                          'findest du im Repository unter server/README.',
                          style: theme.textTheme.bodySmall?.copyWith(
                            color: theme.colorScheme.onSurfaceVariant,
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
              ),
              const SizedBox(height: 16),
              _EntranceFade(index: 2, child: _buildProfileCard(context)),
              const SizedBox(height: 16),
              _EntranceFade(index: 3, child: _buildHealthCard(context)),
              const SizedBox(height: 16),
              _EntranceFade(
                index: 4,
                child: Card(
                  child: Padding(
                    padding: const EdgeInsets.all(16),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text('Über', style: theme.textTheme.titleMedium),
                        const SizedBox(height: 12),
                        const Text(
                          'Trailscape ist kostenlos und local-first: deine '
                          'Touren bleiben auf deinem Gerät, ein Sync-Server ist '
                          'optional. Kartendaten © OpenStreetMap-Mitwirkende, '
                          'Routing über BRouter.',
                        ),
                      ],
                    ),
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
