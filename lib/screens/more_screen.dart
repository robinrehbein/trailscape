/// "Mehr"-Tab: Offline-Karten-Verwaltung, Selfhost-Sync, Health Connect und
/// Info.
library;

import 'dart:convert';
import 'dart:io';

import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:intl/intl.dart';
import 'package:path_provider/path_provider.dart';
import 'package:share_plus/share_plus.dart';

import '../export.dart';
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

  /// Sperrt die Backup-/Import-Buttons, während ein Export/Import läuft.
  bool _backupBusy = false;

  final _ageController = TextEditingController();
  final _weightController = TextEditingController();
  final _setupMassController = TextEditingController();
  final _weeklyHoursController = TextEditingController();
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
    _weeklyHoursController.dispose();
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
    _weeklyHoursController.text = profile.weeklyHours != null
        ? _formatNumber(profile.weeklyHours!)
        : '';
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
    final weeklyHours = _parseNumber(_weeklyHoursController.text);
    if (weeklyHours != null && (weeklyHours <= 0 || weeklyHours > 40)) {
      setState(() => _profileStatus =
          'Bitte eine Wochenzeit zwischen 1 und 40 Stunden angeben.');
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
      weeklyHours: weeklyHours,
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
            const SizedBox(height: 12),
            TextField(
              controller: _weeklyHoursController,
              decoration: const InputDecoration(
                labelText: 'Zeit pro Woche (Stunden, optional)',
              ),
              keyboardType: const TextInputType.numberWithOptions(
                decimal: true,
              ),
            ),
            const SizedBox(height: 4),
            Text(
              'Mit deinem Zeitbudget deckeln wir das Wochenziel auf das, was '
              'sich in dieser Zeit realistisch fahren lässt.',
              style: hint,
            ),
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

  // ---------------------------------------------------------------------
  // Daten & Backup
  // ---------------------------------------------------------------------

  /// Exportiert alle Touren und das Trainingsprofil als eine JSON-Datei über
  /// das System-Share-Sheet (z. B. Speichern in Google Drive, Versenden per
  /// Mail o. ä.) — das eigentliche Backup.
  Future<void> _exportBackup() async {
    setState(() => _backupBusy = true);
    try {
      final json = buildBackupJson(widget.state.rides, widget.state.profile);
      final dir = await getTemporaryDirectory();
      final file = File('${dir.path}/${backupFileName(DateTime.now())}');
      await file.writeAsString(json);
      await SharePlus.instance.share(
        ShareParams(
          files: [XFile(file.path, mimeType: 'application/json')],
          subject: 'Trailscape-Backup',
          title: 'Trailscape-Backup',
        ),
      );
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text('Export fehlgeschlagen: $e')));
      }
    } finally {
      if (mounted) setState(() => _backupBusy = false);
    }
  }

  /// Importiert eine zuvor exportierte Backup-Datei. Touren, deren ID schon
  /// vorhanden ist, werden übersprungen; ein enthaltenes Profil überschreibt
  /// das aktuelle.
  Future<void> _importBackup() async {
    setState(() => _backupBusy = true);
    try {
      final result = await FilePicker.platform.pickFiles(
        type: FileType.custom,
        allowedExtensions: ['json'],
        withData: true,
      );
      if (result == null || result.files.isEmpty) {
        return;
      }
      final bytes = result.files.single.bytes;
      if (bytes == null) {
        throw const FormatException('Die Datei konnte nicht gelesen werden.');
      }

      final raw = utf8.decode(bytes, allowMalformed: true);
      final data = parseBackupJson(raw);

      final existingIds = widget.state.rides.map((r) => r.id).toSet();
      final newRides =
          data.rides.where((r) => !existingIds.contains(r.id)).toList();
      final skipped = data.rides.length - newRides.length;

      await widget.state.addRides(newRides);
      if (data.profile != null) {
        await widget.state.setProfile(data.profile!);
      }

      if (!mounted) return;
      final rideWord = newRides.length == 1 ? 'Tour' : 'Touren';
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(
            '${newRides.length} $rideWord importiert'
            '${skipped > 0 ? ', $skipped übersprungen' : ''}'
            '${data.profile != null ? ' · Profil übernommen' : ''}',
          ),
        ),
      );
    } on FormatException catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text(e.message)));
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text('Import fehlgeschlagen: $e')));
      }
    } finally {
      if (mounted) setState(() => _backupBusy = false);
    }
  }

  /// Importiert eine einzelne GPX-Datei (z. B. Export aus Komoot oder
  /// Strava) als neue Tour.
  Future<void> _importGpxFile() async {
    setState(() => _backupBusy = true);
    try {
      final result = await FilePicker.platform.pickFiles(
        type: FileType.custom,
        allowedExtensions: ['gpx'],
        withData: true,
      );
      if (result == null || result.files.isEmpty) {
        return;
      }
      final file = result.files.single;
      final bytes = file.bytes;
      if (bytes == null) {
        throw const FormatException('Die Datei konnte nicht gelesen werden.');
      }

      final xml = utf8.decode(bytes, allowMalformed: true);
      final fallbackName = file.name.replaceFirst(RegExp(r'\.[^.]+$'), '');
      final ride = rideFromGpx(xml, fallbackName: fallbackName);

      if (widget.state.rides.any((r) => r.id == ride.id)) {
        if (!mounted) return;
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Diese Tour ist bereits vorhanden.')),
        );
        return;
      }

      await widget.state.addRide(ride);
      if (!mounted) return;
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text('„${ride.name}" importiert')));
    } on FormatException catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text(e.message)));
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text('Import fehlgeschlagen: $e')));
      }
    } finally {
      if (mounted) setState(() => _backupBusy = false);
    }
  }

  Widget _buildDataBackupCard(BuildContext context) {
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
            Text('Daten & Backup', style: theme.textTheme.titleMedium),
            const SizedBox(height: 12),
            Text(
              'Sichere alle Touren und dein Trainingsprofil in einer Datei — '
              'zum Übertragen auf ein neues Gerät oder als Backup vor einer '
              'Neuinstallation. Einzelne GPX-Dateien (z. B. aus Komoot oder '
              'Strava) lassen sich ebenfalls importieren.',
              style: hint,
            ),
            const SizedBox(height: 12),
            Wrap(
              spacing: 12,
              runSpacing: 8,
              children: [
                FilledButton.icon(
                  onPressed: _backupBusy ? null : _exportBackup,
                  icon: const Icon(Icons.save_alt),
                  label: const Text('Backup exportieren'),
                ),
                OutlinedButton.icon(
                  onPressed: _backupBusy ? null : _importBackup,
                  icon: const Icon(Icons.restore),
                  label: const Text('Backup importieren'),
                ),
                OutlinedButton.icon(
                  onPressed: _backupBusy ? null : _importGpxFile,
                  icon: const Icon(Icons.route),
                  label: const Text('GPX importieren'),
                ),
              ],
            ),
            if (_backupBusy) ...[
              const SizedBox(height: 12),
              const LinearProgressIndicator(),
            ],
          ],
        ),
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
              _EntranceFade(index: 4, child: _buildDataBackupCard(context)),
              const SizedBox(height: 16),
              _EntranceFade(
                index: 5,
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
