package de.trailscape.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * # Die Ampel- und Akzentfarben der App — an EINER Stelle
 *
 * Alles, was nicht aus `MaterialTheme.colorScheme` kommt und trotzdem *auf*
 * einer Theme-Flaeche liegt (Readiness-Ampel, Erholungssignale, Wochentypen des
 * Trainingsplans, Warnhinweise im Mehr-Tab), steht hier. Vorher lagen dieselben
 * Farbwerte in vier Dateien verstreut — teils doppelt (`Color(0xFF4CAF50)`
 * gleich dreimal), teils als privates Duplikat in einem anderen Paket.
 *
 * ## Warum hell und dunkel getrennt
 * Die urspruenglichen Werte sind 1:1 die Flutter-Materialtoene der alten App
 * (`Colors.green`, `Colors.amber.shade800`, …) und waren fuer eine helle
 * Flaeche gedacht. Auf dem dunklen Untergrund (`DarkSurface` = #101410) ist
 * etwa das Markengruen #2D5A3D als *Textfarbe* praktisch unlesbar (Kontrast
 * ≈ 1,7:1). Deshalb gibt es je Rolle einen zweiten, aufgehellten Ton — dieselbe
 * Logik, mit der Material 3 seine Tonpalette im Dunkelmodus umdreht. Die
 * Zuordnung Rolle → Bedeutung bleibt in beiden Modi identisch, nur die
 * Helligkeit passt sich an.
 *
 * ## Abgrenzung zu `ui/map/MapColors.kt`
 * Die drei Kartenfarben dort liegen auf **Kachelbildern**, nicht auf einer
 * Theme-Flaeche; sie muessen in beiden Modi gleich bleiben, damit eine Tour
 * immer gleich aussieht. Sie gehoeren deshalb bewusst nicht hierher.
 *
 * ## Benutzung
 * ```kotlin
 * val signals = LocalSignalColors.current
 * Text("Achtung", color = signals.warning)
 * ```
 * Der Wert wird von [TrailscapeTheme] passend zum Hell-/Dunkelmodus gestellt.
 */
@Immutable
data class SignalColors(
    /** Grün: alles in Ordnung (Readiness „hart", grüne Erholungsampel). */
    val good: Color,
    /** Gelb/Bernstein: erste Stufe der Zurueckhaltung. */
    val caution: Color,
    /** Orange: deutliche Warnung (Deload, Belastungssprung, fehlende Route). */
    val warning: Color,
    /** Rot: Ruhe/Abbruch — die staerkste Ampelstufe. */
    val danger: Color,
    /** Materialgruen der Wochentypen „Aufbau" und des Fitnesslevel-Chips. */
    val accentGreen: Color,
    /** Materialblau des Wochentyps „Erholung". */
    val accentBlue: Color,
    /** Materialrot der „Zielwoche". */
    val accentRed: Color,
)

/**
 * Helle Fassung — exakt die Werte, die vorher als Literale in
 * `TrainingColors.kt`, `TrainingCommon.kt`, `FitnessCard.kt` und `HealthCard.kt`
 * standen.
 */
internal val LightSignalColors = SignalColors(
    good = TrailscapeSeed,
    caution = Color(0xFFFF8F00),
    warning = Color(0xFFEF6C00),
    danger = Color(0xFFD32F2F),
    accentGreen = Color(0xFF4CAF50),
    accentBlue = Color(0xFF2196F3),
    accentRed = Color(0xFFF44336),
)

/**
 * Dunkle Fassung: dieselben Farbtoene, auf die dunkle Flaeche aufgehellt.
 * [SignalColors.good] ist bewusst `DarkPrimary` — im Dunkelmodus ist das
 * ohnehin die gruene Markenfarbe des Schemas, und Ampelgruen und Primaerfarbe
 * sollen nicht auseinanderlaufen.
 */
internal val DarkSignalColors = SignalColors(
    good = DarkPrimary,
    caution = Color(0xFFFFC246),
    warning = Color(0xFFFFA95E),
    danger = Color(0xFFFF8A7A),
    accentGreen = Color(0xFF81C784),
    accentBlue = Color(0xFF7EC0F5),
    accentRed = Color(0xFFFF8A80),
)

/**
 * Die aktuell gueltigen Ampelfarben. `static`, weil der Wert sich nur beim
 * Wechsel des Hell-/Dunkelmodus aendert — dann wird ohnehin alles neu gebaut.
 */
val LocalSignalColors = staticCompositionLocalOf { LightSignalColors }
