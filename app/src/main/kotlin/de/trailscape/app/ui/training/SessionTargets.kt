package de.trailscape.app.ui.training

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import de.trailscape.app.R
import de.trailscape.app.i18n.LocalCoreTexts
import de.trailscape.app.ui.TrainingInsights
import de.trailscape.core.Confidence
import de.trailscape.core.PersonalTargetRange
import de.trailscape.core.SessionPersonalTargets
import de.trailscape.core.TargetZone
import de.trailscape.core.TrainingSession
import de.trailscape.core.sessionPersonalTargets

/**
 * Persoenliche Zielbereiche einer Planeinheit — die Formatierungslogik ist
 * compose-frei und getestet (`SessionTargetsTest`), die Darstellung steht in
 * [SessionTargetLines].
 */

/** Welcher Wert einer Zeile ist nur geschaetzt? */
enum class EstimateMark { NONE, HEART_RATE, POWER, BOTH }

/**
 * Eine Zeile „Zone: Puls [· Watt]" in Zahlen. Watt sind `null`, wenn keine
 * Leistungsangabe vorliegt; [hrText]/[powerText] sind fertige Bereiche
 * („152–163"), Einheit und Satzbau liefern die Ressourcen.
 */
data class TargetLine(
    val zone: TargetZone,
    val hrText: String,
    val powerText: String?,
    val estimate: EstimateMark,
)

/**
 * Ab welcher Verlaesslichkeit ein Wert als „geschaetzt" gilt: LOW und NONE
 * (Profil-Schaetzung, Tanaka, 2,4 W/kg). MEDIUM (LTHR aus eingetragener HFmax)
 * und HIGH (Test/eigene Eingabe) stehen ohne Zusatz.
 */
fun isEstimated(confidence: Confidence): Boolean =
    confidence == Confidence.LOW || confidence == Confidence.NONE

/** „152–163"; bei gleichen Grenzen nur „152". `null` bei unbrauchbaren Werten (≤ 0 oder low > high). */
fun formatTargetRange(low: Int, high: Int): String? = when {
    low <= 0 || high <= 0 || low > high -> null
    low == high -> low.toString()
    else -> "$low–$high"
}

/** Eine Zeile zu [range]; `null`, wenn nicht einmal der Puls brauchbar ist. */
fun targetLine(range: PersonalTargetRange): TargetLine? {
    val hr = formatTargetRange(range.hrLowBpm, range.hrHighBpm) ?: return null
    val power = formatTargetRange(range.powerLowW, range.powerHighW)
    val hrEstimated = isEstimated(range.hrConfidence)
    val powerEstimated = power != null && isEstimated(range.powerConfidence)
    val mark = when {
        hrEstimated && powerEstimated -> EstimateMark.BOTH
        hrEstimated -> EstimateMark.HEART_RATE
        powerEstimated -> EstimateMark.POWER
        else -> EstimateMark.NONE
    }
    return TargetLine(range.zone, hr, power, mark)
}

/**
 * Die Zeilen einer Einheit: bei Intervallen zuerst der Belastungsbereich, dann
 * der Bereich dazwischen; sonst nur der Hauptbereich. Leer ohne Daten.
 */
fun sessionTargetLines(targets: SessionPersonalTargets?): List<TargetLine> {
    if (targets == null) return emptyList()
    return listOfNotNull(targets.work, targets.main).mapNotNull { targetLine(it) }
}

/** Persoenliche Ziele einer Einheit aus der Trainingsauswertung; `null` beim Event. */
fun planSessionTargets(session: TrainingSession, insights: TrainingInsights): SessionPersonalTargets? =
    sessionPersonalTargets(session, insights.profile, insights.eftp)

/**
 * Zeigt die Zielbereiche als kleine, gedaempfte Zeilen
 * („Schwelle (Z4): 152–163 bpm · 215–240 W (geschätzt)"). Ohne Zeilen: nichts.
 */
@Composable
fun SessionTargetLines(
    targets: SessionPersonalTargets?,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    style: TextStyle = MaterialTheme.typography.bodySmall,
) {
    val lines = sessionTargetLines(targets)
    if (lines.isEmpty()) return
    val texts = LocalCoreTexts.current
    Column(modifier = modifier) {
        for (line in lines) {
            val hr = stringResource(R.string.plan_targets_hr, line.hrText).let {
                if (line.estimate == EstimateMark.HEART_RATE) stringResource(R.string.plan_targets_estimated, it) else it
            }
            val power = line.powerText?.let { stringResource(R.string.plan_targets_power, it) }?.let {
                if (line.estimate == EstimateMark.POWER) stringResource(R.string.plan_targets_estimated, it) else it
            }
            var body = if (power != null) stringResource(R.string.plan_targets_values, hr, power) else hr
            if (line.estimate == EstimateMark.BOTH) body = stringResource(R.string.plan_targets_estimated, body)
            Text(
                text = stringResource(R.string.plan_targets_line, texts.training.targetZone(line.zone), body),
                style = style,
                color = color,
            )
        }
    }
}
