package de.trailscape.core

import java.time.LocalDateTime

/**
 * Reine Datentypen aus `lib/health_sync.dart`.
 *
 * Portiert ist hier **nur** [DailyValue] — der einzige Typ, den
 * `lib/training_load.dart` aus `health_sync.dart` importiert
 * (`import 'health_sync.dart' show DailyValue;`). Der Rest von `health_sync`
 * (Health-Connect-Anbindung, Berechtigungen, Sync-Zustand, `VitalsTrend` und
 * die Aggregationen darum herum) ist Plattform-Logik und folgt in Phase 3.
 */

/**
 * Ein Tageswert einer Vitalserie (Ruhepuls in bpm, HRV/rMSSD in ms, Schlaf in
 * Stunden, VO2max in ml/kg/min).
 *
 * [day] ist ein Kalendertag in lokaler Zeit; die Rechenkern-Funktionen
 * normalisieren ihn selbst auf Mitternacht, genau wie im Dart-Original.
 */
data class DailyValue(
    val day: LocalDateTime,
    val value: Double,
)
