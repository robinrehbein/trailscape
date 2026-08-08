/// Trainings-Tab: Tagesempfehlung, Form-Kurve, Vitalwerte, Zielformular und
/// Trainingsplan.
library;

import 'package:flutter/material.dart';
import 'package:intl/intl.dart';

import '../fitness.dart';
import '../models.dart';
import '../stats.dart';
import '../state.dart';
import '../training.dart';
import '../training_load.dart';
import 'map_screen.dart' show kGreen;

/// Farbe eines Readiness-Bands (§5.4): grün → gelb → orange → rot.
Color readinessBandColor(ReadinessBand band) {
  switch (band) {
    case ReadinessBand.hart:
      return kGreen;
    case ReadinessBand.normal:
      return Colors.amber.shade800;
    case ReadinessBand.locker:
      return Colors.orange.shade800;
    case ReadinessBand.ruhe:
      return Colors.red.shade700;
  }
}

/// Ampelfarbe eines Erholungssignals (Ruhepuls, Schlaf).
Color recoveryFlagColor(RecoveryFlag flag, Color unknown) {
  switch (flag) {
    case RecoveryFlag.unbekannt:
      return unknown;
    case RecoveryFlag.gruen:
      return kGreen;
    case RecoveryFlag.gelb:
      return Colors.amber.shade800;
    case RecoveryFlag.orange:
      return Colors.orange.shade800;
    case RecoveryFlag.rot:
      return Colors.red.shade700;
  }
}

/// Zeichnet CTL (Fitness) und ATL (Ermüdung) auf gemeinsamer Skala.
///
/// Bewusst ohne zusätzliche Abhängigkeit: zwei Polylinien plus Nulllinie,
/// mehr braucht die Mini-Visualisierung nicht. Der Abstand beider Kurven ist
/// die Form (TSB), die daneben als Zahl steht.
class _PmcSparklinePainter extends CustomPainter {
  const _PmcSparklinePainter({
    required this.ctl,
    required this.atl,
    required this.ctlColor,
    required this.atlColor,
    required this.gridColor,
  });

  final List<double> ctl;
  final List<double> atl;
  final Color ctlColor;
  final Color atlColor;
  final Color gridColor;

  @override
  void paint(Canvas canvas, Size size) {
    if (ctl.length < 2) {
      return;
    }
    var maxValue = 1.0;
    for (final v in [...ctl, ...atl]) {
      if (v > maxValue) maxValue = v;
    }

    final baseline = Paint()
      ..color = gridColor
      ..strokeWidth = 1;
    canvas.drawLine(
      Offset(0, size.height - 0.5),
      Offset(size.width, size.height - 0.5),
      baseline,
    );

    void drawSeries(List<double> values, Color color, double width) {
      if (values.length < 2) return;
      final path = Path();
      for (var i = 0; i < values.length; i++) {
        final x = size.width * i / (values.length - 1);
        final y = size.height - (values[i] / maxValue) * size.height;
        if (i == 0) {
          path.moveTo(x, y);
        } else {
          path.lineTo(x, y);
        }
      }
      canvas.drawPath(
        path,
        Paint()
          ..color = color
          ..style = PaintingStyle.stroke
          ..strokeWidth = width
          ..strokeCap = StrokeCap.round
          ..strokeJoin = StrokeJoin.round,
      );
    }

    drawSeries(atl, atlColor, 1.5);
    drawSeries(ctl, ctlColor, 2);
  }

  @override
  bool shouldRepaint(_PmcSparklinePainter oldDelegate) =>
      !listEquals(oldDelegate.ctl, ctl) || !listEquals(oldDelegate.atl, atl);

  static bool listEquals(List<double> a, List<double> b) {
    if (a.length != b.length) return false;
    for (var i = 0; i < a.length; i++) {
      if (a[i] != b[i]) return false;
    }
    return true;
  }
}

/// Sanftes Einblenden (Fade + Slide-up) für Karten beim ersten Aufbau.
///
/// Die ersten Karten werden gestaffelt eingeblendet (~40 ms Versatz pro
/// Index, gedeckelt auf 10), der Zustand bleibt danach pro Element erhalten.
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

class TrainingScreen extends StatefulWidget {
  const TrainingScreen({super.key, required this.state});

  final AppState state;

  @override
  State<TrainingScreen> createState() => _TrainingScreenState();
}

class _TrainingScreenState extends State<TrainingScreen> {
  TrainingPlan? _plan;
  bool _loadingPlan = true;

  final _nameController = TextEditingController();
  final _distanceController = TextEditingController();
  final _ascentController = TextEditingController();
  DateTime? _goalDate;

  String? _goalStatus;
  bool _statusIsError = false;

  @override
  void initState() {
    super.initState();
    _loadPlan();
  }

  @override
  void dispose() {
    _nameController.dispose();
    _distanceController.dispose();
    _ascentController.dispose();
    super.dispose();
  }

  Future<void> _loadPlan() async {
    final plan = await loadPlan();
    if (!mounted) return;
    setState(() {
      _plan = plan;
      _loadingPlan = false;
      if (plan != null) {
        _nameController.text = plan.goal.name;
        _distanceController.text = _formatNum(plan.goal.distanceKm);
        _ascentController.text = plan.goal.ascentM != null
            ? _formatNum(plan.goal.ascentM!)
            : '';
        _goalDate = DateTime.fromMillisecondsSinceEpoch(plan.goal.date);
      }
    });
  }

  String _formatNum(double value) {
    if (value == value.roundToDouble()) {
      return value.toInt().toString();
    }
    return value.toString();
  }

  Future<void> _pickDate() async {
    final now = DateTime.now();
    final picked = await showDatePicker(
      context: context,
      initialDate: _goalDate ?? now.add(const Duration(days: 60)),
      firstDate: now,
      lastDate: now.add(const Duration(days: 400)),
    );
    if (picked != null) {
      setState(() => _goalDate = picked);
    }
  }

  Future<void> _createPlan() async {
    final name = _nameController.text.trim();
    final distance = double.tryParse(
      _distanceController.text.trim().replaceAll(',', '.'),
    );
    final ascentRaw = _ascentController.text.trim().replaceAll(',', '.');
    final ascent = ascentRaw.isEmpty ? null : double.tryParse(ascentRaw);

    if (name.isEmpty) {
      setState(() {
        _goalStatus = 'Bitte einen Namen für das Ziel angeben.';
        _statusIsError = true;
      });
      return;
    }
    if (distance == null || distance <= 0) {
      setState(() {
        _goalStatus = 'Bitte eine gültige Distanz angeben.';
        _statusIsError = true;
      });
      return;
    }
    if (_goalDate == null) {
      setState(() {
        _goalStatus = 'Bitte ein Zieldatum angeben.';
        _statusIsError = true;
      });
      return;
    }

    final goalDate = _goalDate!;
    final goal = Goal(
      name: name,
      distanceKm: distance,
      ascentM: ascent,
      date: DateTime(
        goalDate.year,
        goalDate.month,
        goalDate.day,
        12,
      ).millisecondsSinceEpoch,
    );

    final assessment = assessFitness(widget.state.rides);

    try {
      final plan = generatePlan(goal, assessment);
      await savePlan(plan);
      if (!mounted) return;
      setState(() {
        _plan = plan;
        _goalStatus = 'Plan mit ${plan.weeks.length} Wochen erstellt.';
        _statusIsError = false;
      });
    } on ArgumentError catch (e) {
      setState(() {
        _goalStatus = e.message?.toString() ?? 'Ungültiges Ziel.';
        _statusIsError = true;
      });
    }
  }

  Future<void> _deletePlan() async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Trainingsplan löschen'),
        content: const Text('Soll der Trainingsplan wirklich gelöscht werden?'),
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

    await savePlan(null);
    if (!mounted) return;
    setState(() {
      _plan = null;
      _goalStatus = null;
    });
  }

  Color _weekKindColor(WeekKind kind) {
    switch (kind) {
      case WeekKind.aufbau:
        return Colors.green;
      case WeekKind.erholung:
        return Colors.blue;
      case WeekKind.taper:
        return Colors.amber.shade800;
      case WeekKind.zielwoche:
        return Colors.red;
    }
  }

  Widget _metric(double value, String label, String Function(double) format) {
    return TweenAnimationBuilder<double>(
      tween: Tween(begin: 0, end: value),
      duration: const Duration(milliseconds: 600),
      curve: Curves.easeOutCubic,
      builder: (context, animatedValue, _) {
        return RichText(
          text: TextSpan(
            style: DefaultTextStyle.of(context).style,
            children: [
              TextSpan(
                text: format(animatedValue),
                style: const TextStyle(fontWeight: FontWeight.bold),
              ),
              TextSpan(text: ' $label'),
            ],
          ),
        );
      },
    );
  }

  Widget _buildFitnessCard(BuildContext context, FitnessAssessment a) {
    final theme = Theme.of(context);
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('Dein Fitnesslevel', style: theme.textTheme.titleMedium),
            const SizedBox(height: 12),
            Chip(
              label: Text(
                levelLabels[a.level]!,
                style: const TextStyle(
                  color: Colors.white,
                  fontWeight: FontWeight.bold,
                ),
              ),
              backgroundColor: Colors.green,
            ),
            const SizedBox(height: 12),
            Wrap(
              spacing: 24,
              runSpacing: 8,
              children: [
                _metric(a.weeklyKm, 'km/Woche', formatKm),
                _metric(a.weeklyHm, 'Hm/Woche', (v) => v.round().toString()),
                _metric(
                  a.weeklyRides,
                  'Fahrten/Woche',
                  (v) => v.toStringAsFixed(1),
                ),
                _metric(a.longestRideKm, 'km längste Tour', formatKm),
              ],
            ),
            if (a.rideCount == 0) ...[
              const SizedBox(height: 12),
              Text(
                'Noch keine Touren der letzten 8 Wochen vorhanden – die '
                'Einstufung ist daher konservativ.',
                style: theme.textTheme.bodySmall?.copyWith(
                  color: theme.colorScheme.onSurfaceVariant,
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }

  Widget _buildGoalCard(BuildContext context) {
    final theme = Theme.of(context);
    final dateFormat = DateFormat('dd.MM.yyyy', 'de_DE');

    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('Dein Ziel', style: theme.textTheme.titleMedium),
            const SizedBox(height: 12),
            TextField(
              controller: _nameController,
              decoration: const InputDecoration(labelText: 'Name'),
            ),
            const SizedBox(height: 12),
            Row(
              children: [
                Expanded(
                  child: TextField(
                    controller: _distanceController,
                    decoration: const InputDecoration(
                      labelText: 'Distanz (km)',
                    ),
                    keyboardType: const TextInputType.numberWithOptions(
                      decimal: true,
                    ),
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: TextField(
                    controller: _ascentController,
                    decoration: const InputDecoration(
                      labelText: 'Höhenmeter (optional)',
                    ),
                    keyboardType: const TextInputType.numberWithOptions(
                      decimal: true,
                    ),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 12),
            InkWell(
              onTap: _pickDate,
              child: InputDecorator(
                decoration: const InputDecoration(labelText: 'Zieldatum'),
                child: Text(
                  _goalDate != null
                      ? dateFormat.format(_goalDate!)
                      : 'Datum wählen',
                ),
              ),
            ),
            const SizedBox(height: 16),
            if (_goalStatus != null) ...[
              Text(
                _goalStatus!,
                style: TextStyle(
                  color: _statusIsError
                      ? theme.colorScheme.error
                      : theme.colorScheme.primary,
                ),
              ),
              const SizedBox(height: 8),
            ],
            Wrap(
              spacing: 12,
              runSpacing: 8,
              children: [
                FilledButton(
                  onPressed: _createPlan,
                  child: const Text('Plan erstellen'),
                ),
                if (_plan != null)
                  OutlinedButton(
                    onPressed: _deletePlan,
                    child: const Text('Plan löschen'),
                  ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  /// Farbig hinterlegter Hinweisblock (Ampel, Empfehlung, Warnung).
  Widget _notice(
    BuildContext context, {
    required IconData icon,
    required Color color,
    required String text,
    String? title,
  }) {
    final theme = Theme.of(context);
    return Container(
      width: double.infinity,
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
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                if (title != null)
                  Text(
                    title,
                    style: theme.textTheme.titleSmall?.copyWith(color: color),
                  ),
                Text(text),
              ],
            ),
          ),
        ],
      ),
    );
  }

  /// Beschriftete Kennzahl (Zahl fett, Beschriftung darunter).
  Widget _figure(
    BuildContext context,
    String value,
    String label, {
    Color? color,
  }) {
    final theme = Theme.of(context);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      mainAxisSize: MainAxisSize.min,
      children: [
        Text(
          value,
          style: theme.textTheme.headlineSmall?.copyWith(
            fontWeight: FontWeight.bold,
            color: color,
          ),
        ),
        Text(
          label,
          style: theme.textTheme.bodySmall?.copyWith(
            color: theme.colorScheme.onSurfaceVariant,
          ),
        ),
      ],
    );
  }

  /// Karte „Heute": Readiness-Score, Band und Tagesempfehlung.
  Widget _buildTodayCard(BuildContext context, TrainingInsights insights) {
    final theme = Theme.of(context);
    final readiness = insights.readiness;
    final recommendation = insights.recommendation;
    final color = readiness.available
        ? readinessBandColor(readiness.band)
        : theme.colorScheme.onSurfaceVariant;
    final missingDays = insights.fitness.daysUntilDisplayReady;

    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('Heute', style: theme.textTheme.titleMedium),
            const SizedBox(height: 12),
            if (readiness.available)
              Row(
                crossAxisAlignment: CrossAxisAlignment.center,
                children: [
                  TweenAnimationBuilder<double>(
                    tween: Tween(begin: 0, end: readiness.score),
                    duration: const Duration(milliseconds: 600),
                    curve: Curves.easeOutCubic,
                    builder: (context, value, _) => Text(
                      value.round().toString(),
                      style: theme.textTheme.displaySmall?.copyWith(
                        fontWeight: FontWeight.bold,
                        color: color,
                      ),
                    ),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          'Erholung (0–100)',
                          style: theme.textTheme.bodySmall?.copyWith(
                            color: theme.colorScheme.onSurfaceVariant,
                          ),
                        ),
                        Text(
                          readinessBandLabels[readiness.band]!,
                          style: theme.textTheme.titleSmall
                              ?.copyWith(color: color),
                        ),
                      ],
                    ),
                  ),
                ],
              )
            else ...[
              Text(
                readiness.unavailableReason ?? readiness.headline,
                style: theme.textTheme.bodyMedium,
              ),
              if (missingDays > 0) ...[
                const SizedBox(height: 4),
                Text(
                  'Braucht noch $missingDays '
                  '${missingDays == 1 ? 'Tag' : 'Tage'} Daten.',
                  style: theme.textTheme.bodySmall?.copyWith(
                    color: theme.colorScheme.onSurfaceVariant,
                  ),
                ),
              ],
            ],
            const SizedBox(height: 12),
            _notice(
              context,
              icon: Icons.directions_bike,
              color: color,
              title: recommendation.title,
              text: recommendation.detail,
            ),
            if (recommendation.reasons.isNotEmpty) ...[
              const SizedBox(height: 8),
              for (final reason in recommendation.reasons)
                Padding(
                  padding: const EdgeInsets.only(bottom: 4),
                  child: Text(
                    '· $reason',
                    style: theme.textTheme.bodySmall?.copyWith(
                      color: theme.colorScheme.onSurfaceVariant,
                    ),
                  ),
                ),
            ],
            if (readiness.available) ...[
              const SizedBox(height: 4),
              Text(
                readiness.detail,
                style: theme.textTheme.bodySmall?.copyWith(
                  color: theme.colorScheme.onSurfaceVariant,
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }

  /// Karte „Form": CTL/ATL/TSB, Rampenrate und Belastungsverhältnis.
  Widget _buildFormCard(BuildContext context, TrainingInsights insights) {
    final theme = Theme.of(context);
    final series = insights.fitness;
    final latest = series.latest;

    if (latest == null) {
      return Card(
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text('Form', style: theme.textTheme.titleMedium),
              const SizedBox(height: 12),
              Text(
                'Sobald die erste Tour ausgewertet ist, entsteht hier deine '
                'Fitness-Kurve.',
                style: theme.textTheme.bodySmall?.copyWith(
                  color: theme.colorScheme.onSurfaceVariant,
                ),
              ),
            ],
          ),
        ),
      );
    }

    final tsbBand = classifyTsb(latest.tsb);
    final ramp = latest.rampRate7d;
    final rampBand = ramp == null ? null : classifyRampRate(ramp);
    final ratioBand = classifyLoadRatio(latest.loadRatio);
    final window = series.lastDays(60);

    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('Form', style: theme.textTheme.titleMedium),
            const SizedBox(height: 12),
            if (!series.displayReady)
              _notice(
                context,
                icon: Icons.hourglass_bottom,
                color: theme.colorScheme.onSurfaceVariant,
                text: 'Kurve wird aufgebaut (noch '
                    '${series.daysUntilDisplayReady} '
                    '${series.daysUntilDisplayReady == 1 ? 'Tag' : 'Tage'}).',
              )
            else ...[
              SizedBox(
                height: 72,
                width: double.infinity,
                child: CustomPaint(
                  painter: _PmcSparklinePainter(
                    ctl: window.map((p) => p.ctl).toList(),
                    atl: window.map((p) => p.atl).toList(),
                    ctlColor: kGreen,
                    atlColor: Colors.orange.shade800,
                    gridColor: theme.colorScheme.outlineVariant,
                  ),
                ),
              ),
              const SizedBox(height: 4),
              Text(
                'Letzte ${window.length} '
                '${window.length == 1 ? 'Tag' : 'Tage'} · '
                'grün: Fitness, orange: Ermüdung',
                style: theme.textTheme.bodySmall?.copyWith(
                  color: theme.colorScheme.onSurfaceVariant,
                ),
              ),
            ],
            const SizedBox(height: 12),
            Wrap(
              spacing: 28,
              runSpacing: 12,
              children: [
                _figure(
                  context,
                  latest.ctl.round().toString(),
                  'Fitness (CTL)',
                  color: kGreen,
                ),
                _figure(
                  context,
                  latest.atl.round().toString(),
                  'Ermüdung (ATL)',
                  color: Colors.orange.shade800,
                ),
                _figure(
                  context,
                  _signed(latest.tsb),
                  'Form (TSB)',
                ),
              ],
            ),
            const SizedBox(height: 12),
            Text(
              '${tsbBandLabels[tsbBand]!} — ${tsbBandMessages[tsbBand]!}',
              style: theme.textTheme.bodySmall,
            ),
            const SizedBox(height: 8),
            Text(
              ramp == null || rampBand == null
                  ? 'Rampenrate: noch keine Aussage möglich (weniger als '
                      '7 Tage Historie).'
                  : 'Rampenrate: ${_signed(ramp)} CTL-Punkte pro Woche — '
                      '${rampBandLabels[rampBand]!}.',
              style: theme.textTheme.bodySmall,
            ),
            if (ratioBand == LoadRatioBand.belastungssprung) ...[
              const SizedBox(height: 12),
              _notice(
                context,
                icon: Icons.trending_up,
                color: Colors.orange.shade800,
                text: 'Belastungssprung: dein Verhältnis von akuter zu '
                    'gewohnter Belastung liegt bei '
                    '${latest.loadRatio!.toStringAsFixed(2).replaceAll('.', ',')} '
                    '— außerhalb des Bandes 0,8–1,5.',
              ),
            ] else ...[
              const SizedBox(height: 8),
              Text(
                'Belastungsverhältnis: ${loadRatioLabels[ratioBand]!}'
                '${latest.loadRatio != null ? ' (${latest.loadRatio!.toStringAsFixed(2).replaceAll('.', ',')})' : ''}.',
                style: theme.textTheme.bodySmall,
              ),
            ],
          ],
        ),
      ),
    );
  }

  /// Karte „Diese Woche": Wochenlast, Zielwert und Deload-Empfehlung.
  ///
  /// Ersetzt den früheren Erholungs-Banner: die Wochenanpassung kommt jetzt
  /// aus [assessDeload] und [weeklyLoadTarget] statt aus einer eigenen
  /// Vitaldaten-Heuristik.
  Widget _buildWeekCard(BuildContext context, TrainingInsights insights) {
    final theme = Theme.of(context);
    final deload = insights.deload;
    final target = insights.weeklyTarget;
    final reference = insights.fourWeekMeanWeeklyLoad;

    String? deloadRange;
    if (deload.recommended && reference != null && reference > 0) {
      final low = reference * (1 - deload.volumeReductionHigh);
      final high = reference * (1 - deload.volumeReductionLow);
      deloadRange = '${low.round()}–${high.round()} Last statt zuletzt '
          '${reference.round()}';
    }

    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('Diese Woche', style: theme.textTheme.titleMedium),
            const SizedBox(height: 12),
            Wrap(
              spacing: 28,
              runSpacing: 12,
              children: [
                _figure(
                  context,
                  insights.weeklyLoad.round().toString(),
                  'Last (7 Tage)',
                ),
                if (reference != null)
                  _figure(
                    context,
                    reference.round().toString(),
                    'ø Woche (4 Wochen)',
                  ),
                if (target != null && !deload.recommended)
                  _figure(
                    context,
                    target.weeklyLoad.round().toString(),
                    'Zielwert',
                    color: kGreen,
                  ),
              ],
            ),
            const SizedBox(height: 12),
            _notice(
              context,
              icon: deload.recommended
                  ? Icons.battery_alert
                  : Icons.check_circle_outline,
              color: deload.recommended ? Colors.orange.shade800 : kGreen,
              title: deload.title,
              text: deloadRange != null
                  ? '${deload.detail} Richtwert: $deloadRange.'
                  : deload.detail,
            ),
            for (final trigger in deload.triggers)
              Padding(
                padding: const EdgeInsets.only(top: 8),
                child: Text(
                  '· $trigger',
                  style: theme.textTheme.bodySmall?.copyWith(
                    color: theme.colorScheme.onSurfaceVariant,
                  ),
                ),
              ),
            for (final warning in deload.warnings)
              Padding(
                padding: const EdgeInsets.only(top: 8),
                child: Text(
                  '· $warning',
                  style: theme.textTheme.bodySmall?.copyWith(
                    color: theme.colorScheme.onSurfaceVariant,
                  ),
                ),
              ),
            if (target != null && !deload.recommended)
              for (final cap in target.caps)
                Padding(
                  padding: const EdgeInsets.only(top: 8),
                  child: Text(
                    '· $cap',
                    style: theme.textTheme.bodySmall?.copyWith(
                      color: theme.colorScheme.onSurfaceVariant,
                    ),
                  ),
                ),
          ],
        ),
      ),
    );
  }

  /// Karte „Vitalwerte": Ruhepuls- und Schlafampel plus VO2max-Band.
  Widget _buildVitalsCard(BuildContext context, TrainingInsights insights) {
    final theme = Theme.of(context);
    final unknown = theme.colorScheme.onSurfaceVariant;
    final rhr = insights.restingHr;
    final sleep = insights.sleep;
    final vo2 = insights.vo2max;

    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('Vitalwerte', style: theme.textTheme.titleMedium),
            const SizedBox(height: 12),
            _signalRow(
              context,
              icon: Icons.favorite_outline,
              color: recoveryFlagColor(rhr.flag, unknown),
              headline: rhr.available && rhr.current != null
                  ? 'Ruhepuls ${rhr.current!.round()} bpm · '
                      '${recoveryFlagLabels[rhr.flag]!}'
                  : 'Ruhepuls',
              detail: rhr.available
                  ? rhr.message
                  : (rhr.unavailableReason ?? 'Keine Aussage möglich.'),
            ),
            const SizedBox(height: 12),
            _signalRow(
              context,
              icon: Icons.bedtime_outlined,
              color: recoveryFlagColor(sleep.flag, unknown),
              headline: sleep.available && sleep.lastNightH != null
                  ? 'Schlaf ${sleep.lastNightH!.toStringAsFixed(1).replaceAll('.', ',')} h · '
                      '${recoveryFlagLabels[sleep.flag]!}'
                  : 'Schlaf',
              detail: sleep.available
                  ? sleep.message
                  : (sleep.unavailableReason ?? 'Keine Aussage möglich.'),
            ),
            if (sleep.available && sleep.shortSleeper) ...[
              const SizedBox(height: 12),
              _notice(
                context,
                icon: Icons.info_outline,
                color: unknown,
                text: shortSleeperHint,
              ),
            ],
            if (vo2.available) ...[
              const SizedBox(height: 12),
              _signalRow(
                context,
                icon: Icons.air,
                color: unknown,
                headline: vo2.text,
                detail: 'Geschätzt (${confidenceLabels[vo2.confidence]!}) — '
                    'ein Bereich, keine Messung.',
              ),
            ],
          ],
        ),
      ),
    );
  }

  Widget _signalRow(
    BuildContext context, {
    required IconData icon,
    required Color color,
    required String headline,
    required String detail,
  }) {
    final theme = Theme.of(context);
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Icon(icon, size: 20, color: color),
        const SizedBox(width: 8),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                headline,
                style: theme.textTheme.titleSmall?.copyWith(color: color),
              ),
              Text(
                detail,
                style: theme.textTheme.bodySmall?.copyWith(
                  color: theme.colorScheme.onSurfaceVariant,
                ),
              ),
            ],
          ),
        ),
      ],
    );
  }

  /// Vorzeichenbehaftete, deutsch formatierte Zahl (eine Nachkommastelle
  /// entfällt bei ganzen Werten).
  static String _signed(double value) {
    final rounded = value.round();
    if (rounded > 0) {
      return '+$rounded';
    }
    if (rounded < 0) {
      return '−${rounded.abs()}';
    }
    return '±0';
  }

  List<Widget> _buildPlanWeeks(BuildContext context, TrainingPlan plan) {
    final theme = Theme.of(context);
    final activeIndex = currentWeekIndex(plan);
    final shortDate = DateFormat('dd.MM.', 'de_DE');
    final longDate = DateFormat('dd.MM.yyyy', 'de_DE');

    final widgets = <Widget>[
      Text(
        '${plan.goal.name} – ${formatKm(plan.goal.distanceKm)} km am '
        '${longDate.format(DateTime.fromMillisecondsSinceEpoch(plan.goal.date))}',
        style: theme.textTheme.titleMedium,
      ),
      const SizedBox(height: 12),
    ];

    for (final week in plan.weeks) {
      final isCurrent = week.index == activeIndex;
      final isPastOrCurrent = week.index <= activeIndex;
      final ridden = isPastOrCurrent ? weekKm(week, widget.state.rides) : 0.0;
      final progress = week.targetKm > 0
          ? (ridden / week.targetKm).clamp(0.0, 1.0)
          : 0.0;
      final kindColor = _weekKindColor(week.kind);

      widgets.add(
        _EntranceFade(
          index: week.index,
          child: Card(
            color: isCurrent
                ? theme.colorScheme.primaryContainer.withValues(alpha: 0.5)
                : null,
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    mainAxisAlignment: MainAxisAlignment.spaceBetween,
                    children: [
                      Expanded(
                        child: Text(
                          'Woche ${week.index + 1} · '
                          '${shortDate.format(DateTime.fromMillisecondsSinceEpoch(week.start))}–'
                          '${shortDate.format(DateTime.fromMillisecondsSinceEpoch(week.end))}',
                          style: theme.textTheme.titleSmall,
                        ),
                      ),
                      Chip(
                        label: Text(
                          weekKindLabels[week.kind]!,
                          style: TextStyle(
                            color: kindColor,
                            fontWeight: FontWeight.bold,
                          ),
                        ),
                        backgroundColor: kindColor.withValues(alpha: 0.15),
                        side: BorderSide.none,
                      ),
                    ],
                  ),
                  const SizedBox(height: 8),
                  ClipRRect(
                    borderRadius: BorderRadius.circular(4),
                    child: TweenAnimationBuilder<double>(
                      tween: Tween(begin: 0, end: progress),
                      duration: const Duration(milliseconds: 500),
                      curve: Curves.easeOutCubic,
                      builder: (context, animatedProgress, _) {
                        return LinearProgressIndicator(
                          value: animatedProgress,
                          minHeight: 6,
                        );
                      },
                    ),
                  ),
                  const SizedBox(height: 4),
                  Text(
                    isPastOrCurrent
                        ? '${formatKm(ridden)} von ${week.targetKm} km'
                        : 'Ziel: ${week.targetKm} km',
                    style: theme.textTheme.bodySmall,
                  ),
                  const SizedBox(height: 12),
                  for (final session in week.sessions)
                    Padding(
                      padding: const EdgeInsets.symmetric(vertical: 4),
                      child: Row(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          SizedBox(
                            width: 32,
                            child: Text(
                              session.day,
                              style: const TextStyle(
                                fontWeight: FontWeight.bold,
                                color: Colors.green,
                              ),
                            ),
                          ),
                          Expanded(
                            child: RichText(
                              text: TextSpan(
                                style: DefaultTextStyle.of(context).style,
                                children: [
                                  TextSpan(
                                    text: session.title,
                                    style: const TextStyle(
                                      fontWeight: FontWeight.bold,
                                    ),
                                  ),
                                  TextSpan(text: ' – ${session.description}'),
                                ],
                              ),
                            ),
                          ),
                          const SizedBox(width: 8),
                          Text('${session.targetKm} km'),
                        ],
                      ),
                    ),
                ],
              ),
            ),
          ),
        ),
      );
      widgets.add(const SizedBox(height: 12));
    }

    return widgets;
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Training')),
      body: ListenableBuilder(
        listenable: widget.state,
        builder: (context, _) {
          final assessment = assessFitness(widget.state.rides);
          // Einmal je Rebuild aus dem State geholt; die Auswertung selbst ist
          // dort gecacht und wird nur bei Änderungen neu gerechnet.
          final insights = widget.state.insights;
          return Center(
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 640),
              child: ListView(
                padding: const EdgeInsets.all(16),
                children: [
                  _EntranceFade(
                    index: 0,
                    child: _buildTodayCard(context, insights),
                  ),
                  const SizedBox(height: 16),
                  _EntranceFade(
                    index: 1,
                    child: _buildFormCard(context, insights),
                  ),
                  const SizedBox(height: 16),
                  _EntranceFade(
                    index: 2,
                    child: _buildWeekCard(context, insights),
                  ),
                  const SizedBox(height: 16),
                  _EntranceFade(
                    index: 3,
                    child: _buildVitalsCard(context, insights),
                  ),
                  const SizedBox(height: 16),
                  _EntranceFade(
                    index: 4,
                    child: _buildFitnessCard(context, assessment),
                  ),
                  const SizedBox(height: 16),
                  _EntranceFade(index: 5, child: _buildGoalCard(context)),
                  const SizedBox(height: 16),
                  if (_loadingPlan)
                    const Padding(
                      padding: EdgeInsets.all(24),
                      child: Center(child: CircularProgressIndicator()),
                    )
                  else if (_plan != null)
                    ..._buildPlanWeeks(context, _plan!),
                ],
              ),
            ),
          );
        },
      ),
    );
  }
}
