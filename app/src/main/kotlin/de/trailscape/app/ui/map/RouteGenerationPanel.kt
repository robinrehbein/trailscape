package de.trailscape.app.ui.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.trailscape.app.ui.components.NoticeBox
import de.trailscape.app.ui.formatKmDe
import de.trailscape.app.ui.formatOneDecimalDe
import de.trailscape.app.ui.theme.CardPadding
import de.trailscape.app.ui.theme.LocalSignalColors
import de.trailscape.app.ui.theme.OverlayCardPaddingVertical
import de.trailscape.core.RouteCandidate
import de.trailscape.core.RouteTarget
import de.trailscape.core.RouteTargetSource
import de.trailscape.core.ascentPreferenceLabels
import de.trailscape.core.formatHours
import de.trailscape.core.sessionIntensityLabels
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Das Panel „Trainingsempfehlung → passende Runde" auf der Karte.
 *
 * Es erscheint, sobald der Trainings-Tab ueber
 * [de.trailscape.app.ui.AppViewModel.requestRouteGeneration] ein Ziel
 * herschickt, und fuehrt durch drei Zustaende: Ziel bestaetigen → suchen (mit
 * Fortschritt und Abbrechen) → einen Vorschlag waehlen und uebernehmen.
 *
 * Der [de.trailscape.core.RouteCandidate.score] wird **nicht** angezeigt: Es
 * sind Strafpunkte, also ein internes Mass ohne Einheit. Was die Nutzerin
 * braucht, steht ohnehin da — die Reihenfolge (bester zuerst) und die
 * Abweichung vom Ziel in Prozent.
 */
@Composable
internal fun RouteGenerationPanel(
    state: RouteGenerationState,
    maxHeight: Dp,
    /**
     * Ob gerade auf einen GPS-Fix gewartet wird — das geschieht **vor** dem
     * ersten Server-Aufruf und dauert bis zu zehn Sekunden. Ohne diese Anzeige
     * passierte nach dem Tipp auf „Routen suchen" sichtbar gar nichts.
     */
    locating: Boolean = false,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onSelect: (Int) -> Unit,
    onNextSuggestions: () -> Unit,
    onApply: () -> Unit,
    onDiscard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val target = state.target ?: return
    val theme = MaterialTheme.colorScheme
    val signals = LocalSignalColors.current

    Card(modifier = modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(4.dp)) {
        Column(
            modifier = Modifier
                .heightIn(max = maxHeight)
                .verticalScroll(rememberScrollState())
                .padding(
                    start = CardPadding,
                    top = OverlayCardPaddingVertical,
                    end = 8.dp,
                    bottom = OverlayCardPaddingVertical,
                ),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Passende Runde",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                IconButton(onClick = onDiscard) {
                    Icon(Icons.Filled.Close, contentDescription = "Routenvorschlag verwerfen")
                }
            }

            Text(
                text = targetLine(target),
                modifier = Modifier.padding(end = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = sourceLine(target),
                modifier = Modifier.padding(end = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = theme.onSurfaceVariant,
            )

            Spacer(Modifier.height(8.dp))

            for (hint in state.hints) {
                NoticeBox(
                    icon = Icons.Filled.Info,
                    color = signals.caution,
                    text = hint,
                    modifier = Modifier.padding(end = 8.dp, bottom = 8.dp),
                )
            }

            if (state.fromMapCenter) {
                NoticeBox(
                    icon = Icons.Filled.Info,
                    color = signals.caution,
                    text = "Deine Position war nicht verfügbar – die Runde startet in der " +
                        "Kartenmitte. „Neu suchen“ nimmt den Startpunkt noch einmal neu auf.",
                    modifier = Modifier.padding(end = 8.dp, bottom = 8.dp),
                )
            }

            state.error?.let { error ->
                NoticeBox(
                    icon = Icons.Filled.Warning,
                    color = signals.danger,
                    text = error,
                    modifier = Modifier.padding(end = 8.dp, bottom = 8.dp),
                )
            }

            when {
                locating -> Row(
                    modifier = Modifier.padding(end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Position wird ermittelt …",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                state.running -> SearchProgress(
                    done = state.done,
                    total = state.total,
                    onCancel = onCancel,
                )

                state.candidates.isEmpty() -> {
                    Text(
                        text = "Trailscape sucht ${RouteGenerationController.CANDIDATE_COUNT} " +
                            "Rundkurse ab deiner aktuellen Position – ohne Standort ab der " +
                            "Kartenmitte. Das dauert etwa eine halbe Minute; du kannst " +
                            "zwischendurch ruhig den Tab wechseln.",
                        modifier = Modifier.padding(end = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = theme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    PrimaryButton(
                        text = if (state.error == null) "Routen suchen" else "Erneut suchen",
                        onClick = onStart,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(end = 8.dp),
                    )
                }

                else -> {
                    state.candidates.forEachIndexed { index, candidate ->
                        CandidateRow(
                            candidate = candidate,
                            rank = index + 1,
                            selected = index == state.selectedIndex,
                            onClick = { onSelect(index) },
                            modifier = Modifier.padding(end = 8.dp, bottom = 6.dp),
                        )
                    }

                    Spacer(Modifier.height(2.dp))
                    PrimaryButton(
                        text = "Übernehmen",
                        onClick = onApply,
                        enabled = state.selected != null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(end = 8.dp),
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        TextButton(onClick = onNextSuggestions) {
                            Icon(
                                Icons.Filled.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("Andere Vorschläge")
                        }
                        // Gleiche Runden-Variation, aber Startpunkt neu
                        // bestimmen — nach einem GPS-Fix oder einer
                        // verschobenen Karte.
                        TextButton(onClick = onStart) { Text("Neu suchen") }
                        TextButton(onClick = onDiscard) { Text("Verwerfen") }
                    }
                }
            }
        }
    }
}

/** Fortschritt der laufenden Suche mit Abbruch. */
@Composable
private fun SearchProgress(done: Int, total: Int, onCancel: () -> Unit) {
    Column(modifier = Modifier.padding(end = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (total <= 0) {
                    "Suche läuft …"
                } else {
                    "Kandidat ${(done + 1).coerceAtMost(total)} von $total …"
                },
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = onCancel) { Text("Abbrechen") }
        }
        if (total > 0) {
            LinearProgressIndicator(
                progress = { (done.toFloat() / total.toFloat()).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
                color = RouteBlue,
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = RouteBlue)
        }
    }
}

/** Ein Vorschlag in der Ergebnisliste. */
@Composable
private fun CandidateRow(
    candidate: RouteCandidate,
    rank: Int,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = if (selected) theme.primaryContainer else theme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DirectionChip(bearingDeg = candidate.bearingDeg, highlighted = selected)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${formatKmDe(candidate.distanceKm)} km · " +
                        "${candidate.ascentM.roundToInt()} Hm ↑",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "${candidate.ascentPerKm.roundToInt()} Hm/km · " +
                        "${deviationLabel(candidate)} zum Ziel",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = theme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (rank == 1) "Beste" else "#$rank",
                style = MaterialTheme.typography.labelSmall,
                color = theme.onSurfaceVariant,
            )
        }
    }
}

/** Himmelsrichtung des Rundkurses als runder Chip. */
@Composable
private fun DirectionChip(bearingDeg: Double, highlighted: Boolean) {
    Surface(
        modifier = Modifier.size(36.dp),
        shape = RoundedCornerShape(18.dp),
        color = if (highlighted) RouteBlue else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (highlighted) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = compassLabel(bearingDeg),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

// --------------------------------------------------------------------- Texte

/** „≈ 45 km · Flach · locker · ca. 2,5 h" */
internal fun targetLine(target: RouteTarget): String {
    val parts = mutableListOf(
        "≈ ${formatKmDe(target.distanceKm)} km",
        ascentPreferenceLabels.getValue(target.ascentPreference),
        sessionIntensityLabels.getValue(target.intensity),
    )
    target.durationH?.takeIf { it.isFinite() && it > 0 }?.let {
        parts.add("ca. ${formatHours(it)} h")
    }
    return parts.joinToString(" · ")
}

/** „aus: GA1-Einheit (Trainingsplan)" */
internal fun sourceLine(target: RouteTarget): String {
    // Selbst gewaehlte Runden kommen aus keinem Trainingsziel. `:core` kennt
    // dafuer keinen Wert in [RouteTargetSource] (und ist hier tabu), deshalb
    // erkennt die Beschriftung sie an ihrem festen Label — sonst stuende ueber
    // einer von Hand eingegebenen Distanz „aus: … (Tagesempfehlung)".
    if (target.label == SELF_PLANNED_ROUTE_LABEL) {
        return "aus: deiner Eingabe auf der Karte"
    }
    val source = when (target.source) {
        RouteTargetSource.PLAN -> "Trainingsplan"
        RouteTargetSource.TAGESEMPFEHLUNG -> "Tagesempfehlung"
    }
    return "aus: ${target.label} ($source)"
}

/**
 * Beschriftung eines Ziels, das die Nutzerin selbst auf der Karte eingegeben
 * hat („Runde ab hier über 50 km"). Steht zugleich als Erkennungsmerkmal in
 * [sourceLine].
 */
internal const val SELF_PLANNED_ROUTE_LABEL: String = "Selbst gewählte Distanz"

/**
 * Abweichung vom Ziel mit Vorzeichen, z. B. `+3,4 %`.
 *
 * [RouteCandidate.distanceDeviation] ist der Betrag; die Richtung ergibt sich
 * aus dem Vergleich mit [RouteCandidate.targetKm].
 */
internal fun deviationLabel(candidate: RouteCandidate): String {
    val percent = candidate.distanceDeviation * 100
    if (!percent.isFinite() || abs(percent) < 0.05) {
        return "±0 %"
    }
    val sign = if (candidate.distanceKm >= candidate.targetKm) "+" else "−"
    return "$sign${formatOneDecimalDe(percent)} %"
}

/**
 * Himmelsrichtung eines Kurses in acht Stufen (`N`, `NO`, …). 0° ist Nord,
 * gezaehlt wird im Uhrzeigersinn — dieselbe Konvention wie
 * [RouteCandidate.bearingDeg].
 */
internal fun compassLabel(bearingDeg: Double): String {
    if (!bearingDeg.isFinite()) return "–"
    val normalized = ((bearingDeg % 360) + 360) % 360
    val index = ((normalized + 22.5) / 45).toInt() % COMPASS_LABELS.size
    return COMPASS_LABELS[index]
}

private val COMPASS_LABELS = listOf("N", "NO", "O", "SO", "S", "SW", "W", "NW")
