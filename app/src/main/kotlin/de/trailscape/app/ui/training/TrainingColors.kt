package de.trailscape.app.ui.training

import androidx.compose.ui.graphics.Color
import de.trailscape.app.ui.theme.TrailscapeSeed
import de.trailscape.core.ReadinessBand
import de.trailscape.core.RecoveryFlag
import de.trailscape.core.WeekKind

/**
 * Farben des Trainings-Tabs — Port der Konstanten/Switch-Ausdruecke aus
 * `lib/screens/training_screen.dart` (`readinessBandColor`,
 * `recoveryFlagColor`, `_weekKindColor`).
 *
 * [TrailscapeGreen] ist bewusst [TrailscapeSeed] aus dem gemeinsamen
 * `ui/theme/Color.kt` (Fundament, nicht Teil der Parallel-Agenten) statt einer
 * eigenen Kopie: Darts `kGreen` in `lib/screens/map_screen.dart` ist exakt
 * derselbe Farbwert (`0xFF2D5A3D`) wie die App-Saatfarbe. Ein direkter Import
 * aus `ui/map/MapScreen.kt` waere ein Wettlauf mit dem Karten-Agenten, der
 * diese Datei parallel komplett ersetzt.
 */
val TrailscapeGreen: Color = TrailscapeSeed

/** Entspricht Flutters `Colors.green` (Standardton 500). */
private val FlutterGreen = Color(0xFF4CAF50)

/** Entspricht Flutters `Colors.blue` (Standardton 500). */
private val FlutterBlue = Color(0xFF2196F3)

/** Entspricht Flutters `Colors.red` (Standardton 500). */
private val FlutterRed = Color(0xFFF44336)

/**
 * Entspricht Flutters `Colors.amber.shade800`. Oeffentlich, da mehrere Karten
 * (Deload-Hinweis, Belastungssprung, Taper-Wochen) dieselbe Ampelfarbe
 * brauchen wie [readinessBandColor]/[recoveryFlagColor].
 */
val TrainingAmber800: Color = Color(0xFFFF8F00)

/** Entspricht Flutters `Colors.orange.shade800`. Oeffentlich, siehe [TrainingAmber800]. */
val TrainingOrange800: Color = Color(0xFFEF6C00)

/** Entspricht Flutters `Colors.red.shade700`. Oeffentlich, siehe [TrainingAmber800]. */
val TrainingRed700: Color = Color(0xFFD32F2F)

/** Farbe eines Readiness-Bands (§5.4): grün → gelb → orange → rot. */
fun readinessBandColor(band: ReadinessBand): Color = when (band) {
    ReadinessBand.HART -> TrailscapeGreen
    ReadinessBand.NORMAL -> TrainingAmber800
    ReadinessBand.LOCKER -> TrainingOrange800
    ReadinessBand.RUHE -> TrainingRed700
}

/** Ampelfarbe eines Erholungssignals (Ruhepuls, HRV, Schlaf). */
fun recoveryFlagColor(flag: RecoveryFlag, unknown: Color): Color = when (flag) {
    RecoveryFlag.UNBEKANNT -> unknown
    RecoveryFlag.GRUEN -> TrailscapeGreen
    RecoveryFlag.GELB -> TrainingAmber800
    RecoveryFlag.ORANGE -> TrainingOrange800
    RecoveryFlag.ROT -> TrainingRed700
}

/** Farbe eines Wochentyps im Trainingsplan (Dart: `_weekKindColor`). */
fun weekKindColor(kind: WeekKind): Color = when (kind) {
    WeekKind.AUFBAU -> FlutterGreen
    WeekKind.ERHOLUNG -> FlutterBlue
    WeekKind.TAPER -> TrainingAmber800
    WeekKind.ZIELWOCHE -> FlutterRed
}
