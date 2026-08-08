package de.trailscape.core

import kotlin.math.floor

/**
 * Rundet wie Darts `double.round()`: zur naechsten ganzen Zahl, bei genau .5
 * vom Nullpunkt weg. Kotlins `kotlin.math.round` rundet .5 stattdessen Richtung
 * positiv unendlich — fuer negative Werte waere das ein anderes Ergebnis.
 *
 * Gemeinsam genutzt von `Fitness.kt` (Portierung von `lib/fitness.dart`) und
 * `Stats.kt` (Portierung von `lib/stats.dart`), die beide `.round()` auf
 * Doubles im Original-Dart-Code verwenden.
 */
internal fun dartRound(value: Double): Double =
    if (value < 0) -floor(-value + 0.5) else floor(value + 0.5)
