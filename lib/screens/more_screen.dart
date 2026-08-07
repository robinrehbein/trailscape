/// "Mehr"-Tab: Offline-Karten-Verwaltung, Selfhost-Sync und Info.
library;

import 'package:flutter/material.dart';

import '../storage.dart';
import '../sync_client.dart';
import '../tile_cache.dart';

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
  const MoreScreen({super.key});

  @override
  State<MoreScreen> createState() => _MoreScreenState();
}

class _MoreScreenState extends State<MoreScreen> {
  late Future<int> _tileCountFuture;

  final _urlController = TextEditingController();
  final _tokenController = TextEditingController();
  String? _syncStatus;
  bool _syncing = false;

  @override
  void initState() {
    super.initState();
    _tileCountFuture = TileCache.cachedTileCount();
    _loadSyncConfig();
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
              _EntranceFade(
                index: 2,
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
