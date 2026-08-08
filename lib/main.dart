import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:intl/date_symbol_data_local.dart';

import 'screens/map_screen.dart';
import 'screens/more_screen.dart';
import 'screens/rides_screen.dart';
import 'screens/training_screen.dart';
import 'state.dart';

void main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await initializeDateFormatting('de_DE');
  runApp(const TrailscapeApp());
}

class TrailscapeApp extends StatelessWidget {
  const TrailscapeApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Trailscape',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: const Color(0xFF2D5A3D)),
        useMaterial3: true,
      ),
      locale: const Locale('de'),
      supportedLocales: const [Locale('de')],
      localizationsDelegates: const [
        GlobalMaterialLocalizations.delegate,
        GlobalWidgetsLocalizations.delegate,
        GlobalCupertinoLocalizations.delegate,
      ],
      home: const HomeShell(),
    );
  }
}

class HomeShell extends StatefulWidget {
  const HomeShell({super.key});

  @override
  State<HomeShell> createState() => _HomeShellState();
}

class _HomeShellState extends State<HomeShell>
    with SingleTickerProviderStateMixin {
  final AppState _state = AppState();
  int _tabIndex = 0;

  late final AnimationController _tabFadeController;
  late final Animation<double> _tabFade;

  @override
  void initState() {
    super.initState();
    _bootstrap();
    _tabFadeController = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 220),
      value: 1,
    );
    _tabFade = CurvedAnimation(
      parent: _tabFadeController,
      curve: Curves.easeOutCubic,
    );
  }

  @override
  void dispose() {
    _state.dispose();
    _tabFadeController.dispose();
    super.dispose();
  }

  /// Lädt die gespeicherten Touren und stößt danach einmalig den stillen
  /// Health-Connect-Hintergrund-Sync an (siehe [AppState.autoSyncHealth]).
  /// Blockiert den App-Start nicht: `initState` wartet nicht auf diese
  /// Future, die UI baut sich sofort mit den (noch leeren) Touren auf.
  Future<void> _bootstrap() async {
    await _state.loadRides();
    await _state.autoSyncHealth();
  }

  void _showMap() {
    _switchTab(0);
  }

  void _switchTab(int index) {
    if (index == _tabIndex) return;
    setState(() {
      _tabIndex = index;
    });
    // Kurzer Fade beim Tab-Wechsel; der Zustand der Screens bleibt dank
    // IndexedStack erhalten (insbesondere die Karte wird nicht neu gebaut).
    _tabFadeController
      ..value = 0
      ..forward();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: FadeTransition(
        opacity: _tabFade,
        child: IndexedStack(
          index: _tabIndex,
          children: [
            MapScreen(state: _state),
            RidesScreen(state: _state, onShowMap: _showMap),
            TrainingScreen(state: _state),
            MoreScreen(state: _state),
          ],
        ),
      ),
      bottomNavigationBar: NavigationBar(
        selectedIndex: _tabIndex,
        onDestinationSelected: _switchTab,
        destinations: const [
          NavigationDestination(icon: Icon(Icons.map), label: 'Karte'),
          NavigationDestination(icon: Icon(Icons.route), label: 'Touren'),
          NavigationDestination(
            icon: Icon(Icons.fitness_center),
            label: 'Training',
          ),
          NavigationDestination(icon: Icon(Icons.settings), label: 'Mehr'),
        ],
      ),
    );
  }
}
