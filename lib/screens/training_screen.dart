/// Trainings-Tab: Fitness-Einschätzung, Zielformular und Trainingsplan.
library;

import 'package:flutter/material.dart';
import 'package:intl/intl.dart';

import '../fitness.dart';
import '../models.dart';
import '../stats.dart';
import '../state.dart';
import '../training.dart';

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
        content: const Text(
          'Soll der Trainingsplan wirklich gelöscht werden?',
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

  Widget _metric(String value, String label) {
    return RichText(
      text: TextSpan(
        style: DefaultTextStyle.of(context).style,
        children: [
          TextSpan(
            text: value,
            style: const TextStyle(fontWeight: FontWeight.bold),
          ),
          TextSpan(text: ' $label'),
        ],
      ),
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
                _metric(formatKm(a.weeklyKm), 'km/Woche'),
                _metric(a.weeklyHm.round().toString(), 'Hm/Woche'),
                _metric(a.weeklyRides.toString(), 'Fahrten/Woche'),
                _metric(formatKm(a.longestRideKm), 'km längste Tour'),
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
      final ridden = isPastOrCurrent
          ? weekKm(week, widget.state.rides)
          : 0.0;
      final progress = week.targetKm > 0
          ? (ridden / week.targetKm).clamp(0.0, 1.0)
          : 0.0;
      final kindColor = _weekKindColor(week.kind);

      widgets.add(
        Card(
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
                  child: LinearProgressIndicator(
                    value: progress,
                    minHeight: 6,
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
          return Center(
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 640),
              child: ListView(
                padding: const EdgeInsets.all(16),
                children: [
                  _buildFitnessCard(context, assessment),
                  const SizedBox(height: 16),
                  _buildGoalCard(context),
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
