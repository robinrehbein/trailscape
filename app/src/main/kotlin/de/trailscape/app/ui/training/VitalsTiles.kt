package de.trailscape.app.ui.training

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import de.trailscape.app.ui.TrainingInsights
import de.trailscape.app.ui.components.NeutralButton
import de.trailscape.app.ui.components.NoticeBox
import de.trailscape.app.ui.formatTime
import de.trailscape.app.ui.theme.CardGap
import de.trailscape.core.RecoveryFlag
import de.trailscape.core.confidenceLabels
import de.trailscape.core.shortSleeperHint
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * # „Körperwerte": Ruhepuls, Erholung (HRV), Schlaf, VO₂max
 *
 * Im Redesign „Klartext" (`docs/design/prototyp-klartext.html`, `.vitals` im
 * Screen `#s-training`) ein 2×2-Raster schlichter Kacheln: Name, Wert mit
 * Einheit und **ein Wort** zur Einordnung („normal", „etwas niedrig", „gut") —
 * statt der Ampelstufen „unauffällig / leicht erhöht / stark auffällig" und
 * eines Begruendungssatzes je Kachel. Darunter eine gedaempfte Quellzeile:
 * woher die Werte kommen und wann zuletzt gelesen wurde.
 *
 * ## Die Begruendungen sind nicht weg
 * Die Saetze aus `:core` (Rollmittel, Baseline, Tendenz, Deutung des
 * Erholungsbilds) stehen jetzt im Blatt [VitalsSheet], das ein Tipp auf das
 * Raster oeffnet — dieselbe Ebene-tiefer-Regel wie bei der Form.
 *
 * ## Messwert und Bewertung sind zweierlei
 * Unveraendert: Der Kachelwert ist der **zuletzt gemessene Tageswert**; das
 * Wort bewertet dagegen das 7-Tage-Rollmittel bzw. den 3-Tage-Median.
 *
 * ## Fehlende Werte
 * Eine Kachel ohne Wert sagt „Noch keine Daten" — alle vier Kacheln bleiben
 * stehen, damit man sieht, was die Uhr liefern koennte.
 *
 * [showShortSleeperHint] setzt die Regel „hoechstens einmal im Monat" aus
 * `:core` (`shouldShowShortSleeperHint`) durch; [onShortSleeperHintShown]
 * quittiert die Anzeige.
 *
 * @param syncedAt letzter Vitalwerte-Sync dieser Sitzung
 *   (`AppViewModel.vitalsSyncedAt`), `null` ohne.
 */
@Composable
fun VitalsTiles(
    insights: TrainingInsights,
    syncedAt: LocalDateTime?,
    onOpenDetails: () -> Unit,
    showShortSleeperHint: Boolean = true,
    onShortSleeperHintShown: () -> Unit = {},
) {
    val theme = MaterialTheme.colorScheme
    val muted = theme.onSurfaceVariant
    val hrv = insights.hrv
    val rhr = insights.restingHr
    val sleep = insights.sleep
    val vo2 = insights.vo2max

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(CardGap),
    ) {
        Row(
            modifier = Modifier.height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            VitalTile(
                label = "Ruhepuls",
                value = rhr.last?.let { "${it.roundToInt()}" },
                unit = "bpm",
                word = if (rhr.available) restingHrWord(rhr.flag) else null,
                color = recoveryFlagColor(rhr.flag, muted),
                onClick = onOpenDetails,
            )
            VitalTile(
                label = "Erholung (HRV)",
                value = hrv.lastRmssd?.let { "${it.roundToInt()}" },
                unit = "ms",
                word = if (hrv.available) hrvWord(hrv.flag) else null,
                color = recoveryFlagColor(hrv.flag, muted),
                onClick = onOpenDetails,
            )
        }
        Row(
            modifier = Modifier.height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            VitalTile(
                label = "Schlaf",
                value = sleep.lastNightH?.let { formatSleep(it) },
                unit = "h",
                word = if (sleep.available) sleepWord(sleep.flag) else null,
                color = recoveryFlagColor(sleep.flag, muted),
                onClick = onOpenDetails,
            )
            VitalTile(
                label = "VO₂max",
                // Immer als Band, nie als Punktwert (§8.5).
                value = if (vo2.available) "${vo2.lower!!.roundToInt()}–${vo2.upper!!.roundToInt()}" else null,
                unit = "ml/kg/min",
                word = if (vo2.available) "geschätzt" else null,
                color = Color.Unspecified,
                onClick = onOpenDetails,
            )
        }

        Text(
            text = vitalsSourceLine(syncedAt, hasAny = rhr.last != null || hrv.lastRmssd != null || sleep.lastNightH != null),
            style = MaterialTheme.typography.labelSmall,
            color = muted.copy(alpha = 0.8f),
            modifier = Modifier.padding(horizontal = 4.dp),
        )

        if (sleep.available && sleep.shortSleeper && showShortSleeperHint) {
            NoticeBox(icon = TrainingInfoIcon, color = muted, text = shortSleeperHint)
            LaunchedEffect(Unit) { onShortSleeperHintShown() }
        }
    }
}

/**
 * Quellzeile unter dem Raster: „Von deiner Uhr über Health Connect · heute 6:12".
 *
 * Health Connect nennt fuer die Vitalwerte keine Herkunfts-App an die
 * Auswertung weiter; die Zeile sagt deshalb „deiner Uhr" statt eines
 * Geraetenamens.
 */
internal fun vitalsSourceLine(
    syncedAt: LocalDateTime?,
    hasAny: Boolean,
    today: LocalDate = LocalDate.now(),
): String {
    if (!hasAny && syncedAt == null) {
        return "Noch keine Werte von deiner Uhr. Health Connect verbindest du in den Einstellungen."
    }
    val base = "Von deiner Uhr über Health Connect"
    val at = syncedAt ?: return base
    val day = when (at.toLocalDate()) {
        today -> "heute"
        today.minusDays(1) -> "gestern"
        else -> at.format(DateTimeFormatter.ofPattern("d. MMMM", Locale.GERMANY))
    }
    return "$base · $day ${formatTime(at.toLocalTime())}"
}

/** Schlafdauer als „7:40". */
internal fun formatSleep(hours: Double): String {
    val minutes = (hours * 60).roundToInt()
    return "${minutes / 60}:${(minutes % 60).toString().padStart(2, '0')}"
}

/** Ein Wort zum Ruhepuls: hoch ist hier das Auffaellige. */
internal fun restingHrWord(flag: RecoveryFlag): String = when (flag) {
    RecoveryFlag.UNBEKANNT -> "noch offen"
    RecoveryFlag.GRUEN -> "normal"
    RecoveryFlag.GELB -> "etwas erhöht"
    RecoveryFlag.ORANGE -> "erhöht"
    RecoveryFlag.ROT -> "stark erhöht"
}

/** Ein Wort zur HRV: niedrig ist hier das Auffaellige. */
internal fun hrvWord(flag: RecoveryFlag): String = when (flag) {
    RecoveryFlag.UNBEKANNT -> "noch offen"
    RecoveryFlag.GRUEN -> "normal"
    RecoveryFlag.GELB -> "etwas niedrig"
    RecoveryFlag.ORANGE -> "niedrig"
    RecoveryFlag.ROT -> "sehr niedrig"
}

/** Ein Wort zum Schlaf. */
internal fun sleepWord(flag: RecoveryFlag): String = when (flag) {
    RecoveryFlag.UNBEKANNT -> "noch offen"
    RecoveryFlag.GRUEN -> "gut"
    RecoveryFlag.GELB -> "etwas kurz"
    RecoveryFlag.ORANGE -> "kurz"
    RecoveryFlag.ROT -> "sehr kurz"
}

/**
 * Eine Kachel: Name, Wert mit Einheit, Einordnung als Pille — oder
 * „Noch keine Daten".
 */
@Composable
private fun RowScope.VitalTile(
    label: String,
    value: String?,
    unit: String,
    word: String?,
    color: Color,
    onClick: () -> Unit,
) {
    val theme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .clickable(role = Role.Button, onClickLabel = "Körperwerte erklärt öffnen", onClick = onClick),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = theme.onSurfaceVariant)
            if (value != null) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(text = value, style = MaterialTheme.typography.headlineSmall)
                    Text(
                        text = " $unit",
                        style = MaterialTheme.typography.labelMedium,
                        color = theme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 3.dp),
                    )
                }
                word?.let {
                    MetricChip(it, color)
                }
            } else {
                Text(
                    "Noch keine Daten",
                    style = MaterialTheme.typography.bodyMedium,
                    color = theme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/**
 * Blatt „Körperwerte": je Signal der Satz aus `:core` (Deutung, Baseline,
 * Tendenz bzw. warum es noch keine Aussage gibt) und zuletzt, wie daraus ein
 * Erholungsbild wird. Das ist der Inhalt, der frueher als Deutungszeile in
 * jeder Kachel und unter dem Raster stand.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VitalsSheet(insights: TrainingInsights, onDismiss: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val rhr = insights.restingHr
    val hrv = insights.hrv
    val sleep = insights.sleep
    val vo2 = insights.vo2max
    val readiness = insights.readiness
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        SheetColumn {
            Text("Körperwerte", style = MaterialTheme.typography.titleLarge)
            ExplainRow(
                label = "Ruhepuls",
                pill = if (rhr.available) restingHrWord(rhr.flag) else null,
                pillColor = recoveryFlagColor(rhr.flag, muted),
                text = if (rhr.available) rhr.message else rhr.unavailableReason ?: "Keine Aussage möglich.",
            )
            ExplainRow(
                label = "Erholung",
                pill = if (hrv.available) hrvWord(hrv.flag) else null,
                pillColor = recoveryFlagColor(hrv.flag, muted),
                text = if (hrv.available) {
                    "${hrv.message} ${hrvTrendText(hrv)}"
                } else {
                    hrv.unavailableReason ?: "Keine Aussage möglich."
                },
                jargon = "HRV",
            )
            ExplainRow(
                label = "Schlaf",
                pill = if (sleep.available) sleepWord(sleep.flag) else null,
                pillColor = recoveryFlagColor(sleep.flag, muted),
                text = if (sleep.available) sleep.message else sleep.unavailableReason ?: "Keine Aussage möglich.",
            )
            ExplainRow(
                label = "VO₂max",
                pill = if (vo2.available) confidenceLabels.getValue(vo2.confidence) else null,
                text = if (vo2.available) {
                    "Deine maximale Sauerstoffaufnahme unter Volllast, geschätzt aus Touren mit " +
                        "Puls und Höhenprofil – deshalb ein Bereich, keine Messung."
                } else {
                    vo2.unavailableReason ?: "Noch nicht schätzbar."
                },
            )
            Text(
                text = if (readiness.available) readiness.detail else readiness.unavailableReason ?: readiness.detail,
                style = MaterialTheme.typography.bodySmall,
                color = muted,
            )
            NeutralButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Verstanden") }
        }
    }
}
