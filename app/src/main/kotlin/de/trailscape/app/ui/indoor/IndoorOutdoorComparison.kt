package de.trailscape.app.ui.indoor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import de.trailscape.app.R
import de.trailscape.app.i18n.LocalAppFormats
import de.trailscape.app.ui.components.Eyebrow
import de.trailscape.core.indoor.GHOST_EVEN_S
import de.trailscape.core.indoor.GhostSection
import kotlin.math.abs
import kotlin.math.roundToInt

/** Ab dieser Stelle (m) nennt die Auswertung „ab km …"; davor gilt der Abschnitt als ab dem Start. */
private const val SECTION_FROM_START_M = 50.0

/**
 * # Vergleich mit der Aussenfahrt (Auswertung)
 *
 * „Draußen gefahren → drinnen nachfahren" (docs/indoor/app.md): Fuhr die eigene
 * Aussenfahrt als Mitfahrer mit ([IndoorSummaryUi.ghostOutdoor]), steht hier
 * statt des allgemeinen Mitfahrer-Satzes
 *
 *  1. das Ergebnis: „Du warst 0:12 vor/hinter deiner Außenfahrt." — die
 *     Zeitdifferenz auf demselben Abschnitt ([GhostSection.riderAheadS]),
 *     sonst der Endabstand ([IndoorSummaryUi.ghostGapS], positiv = Geist vorn);
 *  2. welcher Abschnitt verglichen wurde (Laenge, ggf. „ab km …");
 *  3. eine kleine Tabelle Drinnen/Draußen: Zeit, Ø Tempo, Ø Leistung. Fehlt der
 *     Aussenfahrt die Leistung, steht dort „–" und ein Satz darunter.
 *
 * Gerechnet wird in `:core` (IndoorGhostSection.kt); hier wird nur formatiert.
 */
@Composable
internal fun OutdoorComparison(ui: IndoorSummaryUi) {
    val gap = ui.ghostGapS
    val section = ui.ghostSection
    if (gap == null && section == null) return
    Eyebrow(stringResource(R.string.idee01_summary_eyebrow))
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(IndoorCardPadding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ui.ghostLabel?.let { Text(text = it, style = MaterialTheme.typography.titleSmall) }
            if (gap != null) {
                val time = formatIndoorGapSeconds(gap)
                Text(
                    text = when {
                        abs(gap) < GHOST_EVEN_S -> stringResource(R.string.idee01_summary_even)
                        gap > 0.0 -> stringResource(R.string.idee01_summary_behind, time)
                        else -> stringResource(R.string.idee01_summary_ahead, time)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (section != null) SectionTable(section)
        }
    }
}

@Composable
private fun SectionTable(section: GhostSection) {
    val formats = LocalAppFormats.current
    val none = INDOOR_NO_VALUE
    val lengthKm = formats.km(section.lengthM / 1000.0)
    Text(
        text = if (section.startM >= SECTION_FROM_START_M) {
            stringResource(R.string.idee01_summary_section, lengthKm, formats.km(section.startM / 1000.0))
        } else {
            stringResource(R.string.idee01_summary_section_full, lengthKm)
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    HorizontalDivider()
    TableRow(
        label = "",
        indoor = stringResource(R.string.idee01_summary_col_indoor),
        outdoor = stringResource(R.string.idee01_summary_col_outdoor),
        header = true,
    )
    TableRow(
        label = stringResource(R.string.idee01_summary_row_time),
        indoor = formatIndoorClock(section.riderS.roundToInt()),
        outdoor = section.ghostS?.let { formatIndoorClock(it.roundToInt()) } ?: none,
    )
    val kmh: @Composable (Double?) -> String = { v ->
        v?.let { stringResource(R.string.indoor_app_value_kmh, formats.decimal(it, 1)) } ?: none
    }
    TableRow(
        label = stringResource(R.string.idee01_summary_row_speed),
        indoor = kmh(section.riderAvgSpeedKmh),
        outdoor = kmh(section.ghostAvgSpeedKmh),
    )
    val watts: @Composable (Double?) -> String = { v ->
        v?.takeIf { it.isFinite() }?.let { stringResource(R.string.indoor_app_value_watts, it.roundToInt()) } ?: none
    }
    TableRow(
        label = stringResource(R.string.idee01_summary_row_power),
        indoor = watts(section.riderAvgPowerW),
        outdoor = watts(section.ghostAvgPowerW),
    )
    if (section.ghostAvgPowerW == null) {
        Text(
            text = stringResource(R.string.idee01_summary_no_power),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TableRow(label: String, indoor: String, outdoor: String, header: Boolean = false) {
    val style = if (header) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyMedium
    val color = if (header) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = label,
            style = style,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1.2f),
        )
        Text(text = indoor, style = style, color = color, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
        Text(text = outdoor, style = style, color = color, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
    }
}
