package de.trailscape.app.ui.progress

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.TrendingDown
import androidx.compose.material.icons.automirrored.rounded.TrendingFlat
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import de.trailscape.app.R
import de.trailscape.app.i18n.LocalAppFormats
import de.trailscape.app.ui.theme.CardPadding
import de.trailscape.app.ui.theme.LocalSignalColors
import de.trailscape.core.FITNESS_MOVE_PCT
import de.trailscape.core.FITNESS_MOVE_PCT_ENDURANCE
import de.trailscape.core.FitnessComparison
import de.trailscape.core.FitnessSignal
import de.trailscape.core.FitnessSignalKind
import de.trailscape.core.FitnessVerdict
import kotlin.math.abs

/**
 * „Werde ich fitter?" in drei Zeilen Klartext.
 *
 * ## Warum es diese Karte neben der Formkarte gibt
 * Die Karte „Deine Form" zeigt die Belastungsrechnung (CTL, „Fitness 14 →"):
 * praezise, aber ohne Fachwissen nicht zu lesen — und sie misst, wie viel
 * trainiert wurde, nicht, ob es etwas gebracht hat. Diese Karte beantwortet
 * die Frage dahinter mit Dingen, die jeder versteht: bei gleichem Puls
 * schneller, auf gleichen Runden schneller, die laengste Tour waechst. Sie
 * ersetzt die CTL-Karte nicht; beide koennen sich zeitweise widersprechen
 * (viel Training, aber noch muede), und das ist keine Fehlfunktion.
 *
 * Die Wortwahl bei „schwaecher" ist bewusst ohne Schuld: Hitze, Wind und eine
 * Erkaeltung sind haeufiger als echter Formverlust.
 *
 * Die Rechnung (`fitnessComparison`) gehoert in ein `remember(rides, today)`
 * des Aufrufers — die Karte selbst rechnet nichts.
 */
@Composable
internal fun FitnessTrendCard(trend: FitnessComparison, modifier: Modifier = Modifier) {
    val theme = MaterialTheme.colorScheme
    val signals = LocalSignalColors.current
    val verdict = trend.verdict
    val headline = stringResource(
        when (verdict) {
            FitnessVerdict.FITTER -> R.string.progress_fitness_headline_fitter
            FitnessVerdict.STABLE -> R.string.progress_fitness_headline_stable
            FitnessVerdict.DECLINING -> R.string.progress_fitness_headline_declining
            FitnessVerdict.NOT_ENOUGH_DATA -> R.string.progress_fitness_headline_unknown
        },
    )
    val icon = when (verdict) {
        FitnessVerdict.FITTER -> Icons.AutoMirrored.Rounded.TrendingUp
        FitnessVerdict.STABLE -> Icons.AutoMirrored.Rounded.TrendingFlat
        FitnessVerdict.DECLINING -> Icons.AutoMirrored.Rounded.TrendingDown
        FitnessVerdict.NOT_ENOUGH_DATA -> Icons.Rounded.Timer
    }
    val tint = when (verdict) {
        FitnessVerdict.FITTER -> signals.good
        FitnessVerdict.DECLINING -> signals.caution
        else -> theme.onSurfaceVariant
    }
    val subline = if (verdict == FitnessVerdict.NOT_ENOUGH_DATA) {
        stringResource(R.string.progress_fitness_unknown_hint)
    } else {
        stringResource(R.string.progress_fitness_window, trend.windowDays / 7)
    }

    Card(modifier = modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier.padding(CardPadding),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconBubble(icon, tint)
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = headline,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        text = subline,
                        style = MaterialTheme.typography.bodySmall,
                        color = theme.onSurfaceVariant,
                    )
                }
            }
            trend.signals.forEach { signal ->
                RowDivider()
                SignalRow(signal)
            }
            val hasEfficiency = trend.signals.any { it.kind == FitnessSignalKind.EFFICIENCY }
            if (trend.signals.isNotEmpty() && !hasEfficiency) {
                Text(
                    text = stringResource(R.string.progress_fitness_no_hr_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = theme.onSurfaceVariant,
                    modifier = Modifier.padding(start = CardPadding, end = CardPadding, bottom = CardPadding, top = 2.dp),
                )
            } else {
                Spacer(modifier = Modifier.height(4.dp))
            }
        }
    }
}

/** Eine Beobachtung: Symbol vorn, darueber die Art, darunter der Satz. */
@Composable
private fun SignalRow(signal: FitnessSignal) {
    val formats = LocalAppFormats.current
    val colors = LocalSignalColors.current
    val limit = if (signal.kind == FitnessSignalKind.ENDURANCE) FITNESS_MOVE_PCT_ENDURANCE else FITNESS_MOVE_PCT
    val moved = abs(signal.changePct) >= limit
    val tint = when {
        !moved -> MaterialTheme.colorScheme.onSurfaceVariant
        signal.changePct > 0 -> colors.good
        else -> colors.caution
    }
    val (icon, label) = when (signal.kind) {
        FitnessSignalKind.EFFICIENCY -> Icons.Rounded.Favorite to R.string.progress_fitness_efficiency_label
        FitnessSignalKind.PACE -> Icons.Rounded.Speed to R.string.progress_fitness_pace_label
        FitnessSignalKind.ENDURANCE -> Icons.Rounded.Route to R.string.progress_fitness_endurance_label
    }
    val sentence = when (signal.kind) {
        FitnessSignalKind.EFFICIENCY -> when {
            !moved -> stringResource(R.string.progress_fitness_efficiency_same)
            signal.changePct > 0 -> stringResource(R.string.progress_fitness_efficiency_faster, signal.changePct)
            else -> stringResource(R.string.progress_fitness_efficiency_slower, -signal.changePct)
        }
        FitnessSignalKind.PACE -> stringResource(
            R.string.progress_fitness_pace_value,
            formats.decimal(signal.recent, 1),
            formats.decimal(signal.before, 1),
        )
        FitnessSignalKind.ENDURANCE -> stringResource(
            R.string.progress_fitness_endurance_value,
            formats.decimal(signal.recent, 0),
            formats.decimal(signal.before, 0),
        )
    }
    SignalLine(icon, tint, stringResource(label), sentence)
}

@Composable
private fun SignalLine(icon: ImageVector, tint: Color, label: String, sentence: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(horizontal = CardPadding, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBubble(icon, tint)
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(text = sentence, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
