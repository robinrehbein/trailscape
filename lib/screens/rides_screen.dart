/// Tourenliste: GPX-Import/-Export, Auswahl und Löschen gespeicherter Touren.
library;

import 'dart:convert';
import 'dart:io';

import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:intl/intl.dart';
import 'package:path_provider/path_provider.dart';
import 'package:share_plus/share_plus.dart';

import '../export.dart';
import '../models.dart';
import '../state.dart';
import '../stats.dart';
import '../training_load.dart';

/// Kurzes Quellen-Label der Trainingslast für die Tourenliste.
const Map<LoadSource, String> rideLoadSourceShortLabels = {
  LoadSource.herzfrequenz: 'Puls',
  LoadSource.physik: 'Leistung',
  LoadSource.rpe: 'Empfinden',
  LoadSource.heuristik: 'geschätzt',
  LoadSource.keine: '',
};

/// Sanftes Einblenden (Fade + Slide-up) für neu aufgebaute Listeneinträge.
///
/// Die ersten Einträge werden gestaffelt eingeblendet (~40 ms Versatz pro
/// Item, gedeckelt auf 10 Items), danach startet die Animation ohne
/// zusätzliche Verzögerung. Der Zustand bleibt pro Element erhalten, sodass
/// die Animation nur beim ersten Aufbau eines Eintrags läuft.
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

class RidesScreen extends StatefulWidget {
  const RidesScreen({super.key, required this.state, required this.onShowMap});

  final AppState state;
  final VoidCallback onShowMap;

  @override
  State<RidesScreen> createState() => _RidesScreenState();
}

class _RidesScreenState extends State<RidesScreen> {
  bool _importing = false;

  Future<void> _import() async {
    if (_importing) return;
    setState(() => _importing = true);

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

      await widget.state.addRide(ride);
      widget.onShowMap();
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
      if (mounted) setState(() => _importing = false);
    }
  }

  /// Teilt eine Tour als GPX-Datei über das System-Share-Sheet (z. B. für
  /// Komoot, Strava oder eine andere Trainings-App).
  Future<void> _shareGpx(BuildContext context, Ride ride) async {
    try {
      final xml = rideToGpx(ride);
      final dir = await getTemporaryDirectory();
      final file = File('${dir.path}/${safeFileName(ride.name)}.gpx');
      await file.writeAsString(xml);
      await SharePlus.instance.share(
        ShareParams(
          files: [XFile(file.path, mimeType: 'application/gpx+xml')],
          subject: ride.name,
          title: ride.name,
        ),
      );
    } catch (e) {
      if (context.mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text('Teilen fehlgeschlagen: $e')));
      }
    }
  }

  Future<bool> _confirmDelete(BuildContext context, Ride ride) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Tour löschen'),
        content: Text('Soll „${ride.name}“ wirklich gelöscht werden?'),
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
    return confirmed == true;
  }

  @override
  Widget build(BuildContext context) {
    final dateFormat = DateFormat('dd.MM.yyyy', 'de_DE');
    final colorScheme = Theme.of(context).colorScheme;

    return Scaffold(
      appBar: AppBar(
        title: const Text('Meine Touren'),
        actions: [
          IconButton(
            icon: AnimatedSwitcher(
              duration: const Duration(milliseconds: 200),
              switchInCurve: Curves.easeOutCubic,
              switchOutCurve: Curves.easeInOutCubic,
              child: _importing
                  ? const SizedBox(
                      key: ValueKey('importing'),
                      width: 20,
                      height: 20,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    )
                  : const Icon(Icons.upload, key: ValueKey('upload')),
            ),
            tooltip: 'GPX importieren',
            onPressed: _importing ? null : _import,
          ),
        ],
      ),
      body: ListenableBuilder(
        listenable: widget.state,
        builder: (context, _) {
          final rides = widget.state.rides;

          if (rides.isEmpty) {
            return Center(
              child: Padding(
                padding: const EdgeInsets.all(24),
                child: Text(
                  'Noch keine Touren vorhanden.\n'
                  'Importiere eine GPX-Datei über das Symbol oben rechts.',
                  textAlign: TextAlign.center,
                  style: Theme.of(context).textTheme.bodyLarge,
                ),
              ),
            );
          }

          return ListView.builder(
            itemCount: rides.length,
            itemBuilder: (context, index) {
              final ride = rides[index];
              final selected = widget.state.selected?.id == ride.id;
              final load = widget.state.rideLoad(ride.id);
              final loadText = load != null && load.available
                  ? 'Last ${load.load.round()} · '
                      '${rideLoadSourceShortLabels[load.source]!}'
                  : null;

              return _EntranceFade(
                index: index,
                child: Dismissible(
                  key: ValueKey(ride.id),
                  direction: DismissDirection.endToStart,
                  background: Container(
                    color: colorScheme.errorContainer,
                    alignment: Alignment.centerRight,
                    padding: const EdgeInsets.symmetric(horizontal: 24),
                    child: Icon(
                      Icons.delete,
                      color: colorScheme.onErrorContainer,
                    ),
                  ),
                  confirmDismiss: (_) => _confirmDelete(context, ride),
                  onDismissed: (_) {
                    widget.state.removeRide(ride.id);
                  },
                  child: AnimatedContainer(
                    duration: const Duration(milliseconds: 200),
                    curve: Curves.easeOutCubic,
                    color: selected
                        ? colorScheme.secondaryContainer
                        : Colors.transparent,
                    child: ListTile(
                      selected: selected,
                      title: Text(ride.name),
                      isThreeLine: loadText != null,
                      subtitle: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          Text(
                            '${dateFormat.format(DateTime.fromMillisecondsSinceEpoch(ride.createdAt))} · '
                            '${formatKm(ride.stats.distanceKm)} km · '
                            '${formatDuration(ride.stats.durationS)}',
                          ),
                          if (loadText != null)
                            Text(
                              loadText,
                              style: Theme.of(context).textTheme.bodySmall
                                  ?.copyWith(color: colorScheme.primary),
                            ),
                        ],
                      ),
                      trailing: Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          if (ride.id.startsWith('hc-'))
                            Tooltip(
                              message: 'Aus Samsung Health importiert',
                              child: Icon(
                                Icons.watch,
                                size: 20,
                                color: colorScheme.onSurfaceVariant,
                              ),
                            ),
                          PopupMenuButton<String>(
                            tooltip: 'Weitere Aktionen',
                            onSelected: (value) {
                              if (value == 'share_gpx') {
                                _shareGpx(context, ride);
                              }
                            },
                            itemBuilder: (context) => const [
                              PopupMenuItem(
                                value: 'share_gpx',
                                child: ListTile(
                                  leading: Icon(Icons.ios_share),
                                  title: Text('Als GPX teilen'),
                                  contentPadding: EdgeInsets.zero,
                                ),
                              ),
                            ],
                          ),
                        ],
                      ),
                      onTap: () {
                        widget.state.select(ride);
                        widget.onShowMap();
                      },
                    ),
                  ),
                ),
              );
            },
          );
        },
      ),
    );
  }
}
