/// "Mehr"-Tab: Offline-Karten-Verwaltung, Selfhost-Sync, Health Connect und
/// Info.
library;

import 'package:flutter/material.dart';
import 'package:intl/intl.dart';

import '../health_sync.dart';
import '../state.dart';
import '../storage.dart';
import '../sync_client.dart';
import '../tile_cache.dart';
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

  @override
  void initState() {
    super.initState();
    _tileCountFuture = TileCache.cachedTileCount();
    _loadSyncConfig();
    _loadHealthStatus();
  }

  @override
  void dispose() {
    _urlController.dispose();
    _tokenController.dispose();
    super.dispose();
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

  Widget _buildHealthCard(BuildContext context) {
    final theme = Theme.of(context);
    final connection = _healthConnection;
    final dateFormat = DateFormat('dd.MM.yyyy, HH:mm', 'de_DE');

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
            const SizedBox(height: 12),
            Text(
              'Damit auch die aufgezeichnete Route mit importiert wird, '
              'erlaube in Health Connect unter „App-Berechtigungen → '
              'Trailscape → Trainingsrouten" den dauerhaften Zugriff. Ohne '
              'diese Freigabe werden Distanz, Dauer und Herzfrequenz trotzdem '
              'übernommen.',
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
              _EntranceFade(index: 2, child: _buildHealthCard(context)),
              const SizedBox(height: 16),
              _EntranceFade(
                index: 3,
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
