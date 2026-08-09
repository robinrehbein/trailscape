package de.trailscape.app.ui.training

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import de.trailscape.app.ui.theme.LocalSignalColors
import de.trailscape.core.ReadinessBand
import de.trailscape.core.RecoveryFlag
import de.trailscape.core.WeekKind

/**
 * Farben des Trainings-Tabs — Port der Konstanten/Switch-Ausdruecke aus
 * `lib/screens/training_screen.dart` (`readinessBandColor`,
 * `recoveryFlagColor`, `_weekKindColor`).
 *
 * ## Was sich gegenueber der ersten Fassung geaendert hat
 * Die Farbwerte selbst liegen nicht mehr hier, sondern zentral in
 * `ui/theme/SignalColors.kt` — zusammen mit den Duplikaten, die vorher in
 * `TrainingCommon.kt`, `FitnessCard.kt` und `ui/more/HealthCard.kt` noch einmal
 * als `Color(0xFF…)` standen. Dadurch sind alle Zugriffe hier `@Composable`:
 * Sie lesen die Palette, die [de.trailscape.app.ui.theme.TrailscapeTheme] je
 * nach Hell-/Dunkelmodus stellt. Das war noetig, weil das Markengruen #2D5A3D
 * als Textfarbe auf der dunklen Flaeche unlesbar war (Kontrast ≈ 1,7:1).
 */

/** Gruen der Ampel — hell das Markengruen, dunkel dessen aufgehellte Fassung. */
val trainingGood: Color
    @Composable @ReadOnlyComposable get() = LocalSignalColors.current.good

/** Gelb/Bernstein der Ampel. */
val trainingCaution: Color
    @Composable @ReadOnlyComposable get() = LocalSignalColors.current.caution

/** Orange der Ampel (Deload-Hinweis, Belastungssprung, Taper-Wochen). */
val trainingWarning: Color
    @Composable @ReadOnlyComposable get() = LocalSignalColors.current.warning

/** Rot der Ampel (Ruhetag). */
val trainingDanger: Color
    @Composable @ReadOnlyComposable get() = LocalSignalColors.current.danger

/** Farbe eines Readiness-Bands (§5.4): grün → gelb → orange → rot. */
@Composable
@ReadOnlyComposable
fun readinessBandColor(band: ReadinessBand): Color = when (band) {
    ReadinessBand.HART -> trainingGood
    ReadinessBand.NORMAL -> trainingCaution
    ReadinessBand.LOCKER -> trainingWarning
    ReadinessBand.RUHE -> trainingDanger
}

/** Ampelfarbe eines Erholungssignals (Ruhepuls, HRV, Schlaf). */
@Composable
@ReadOnlyComposable
fun recoveryFlagColor(flag: RecoveryFlag, unknown: Color): Color = when (flag) {
    RecoveryFlag.UNBEKANNT -> unknown
    RecoveryFlag.GRUEN -> trainingGood
    RecoveryFlag.GELB -> trainingCaution
    RecoveryFlag.ORANGE -> trainingWarning
    RecoveryFlag.ROT -> trainingDanger
}

/** Farbe eines Wochentyps im Trainingsplan (Dart: `_weekKindColor`). */
@Composable
@ReadOnlyComposable
fun weekKindColor(kind: WeekKind): Color {
    val signals = LocalSignalColors.current
    return when (kind) {
        WeekKind.AUFBAU -> signals.accentGreen
        WeekKind.ERHOLUNG -> signals.accentBlue
        WeekKind.TAPER -> signals.caution
        WeekKind.ZIELWOCHE -> signals.accentRed
    }
}
