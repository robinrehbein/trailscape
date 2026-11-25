package de.trailscape.app.ui.indoor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.trailscape.app.R
import de.trailscape.app.i18n.LocalAppFormats
import de.trailscape.app.ui.components.Eyebrow
import de.trailscape.app.ui.components.Fact
import de.trailscape.app.ui.components.NeutralButton
import de.trailscape.app.ui.components.NoticeBox
import de.trailscape.app.ui.components.OneUiDialog
import de.trailscape.app.ui.components.TagPill
import de.trailscape.app.ui.theme.LocalSignalColors

/** Unter dieser Dauer wird eine Fahrt nicht gespeichert (docs/indoor.md 10.1). */
const val INDOOR_MIN_SAVE_SECONDS: Int = 60

/** Ab diesem Abstand (Sekunden) gilt die Fahrt gegen den Geist als „gleichauf". */
private const val GHOST_EVEN_SECONDS = 1.0

/**
 * # Auswertung
 *
 * Kennzahlen der Fahrt, Vergleich mit dem Geist, Speichern oder Verwerfen.
 * Fahrten unter 60 Sekunden ([IndoorSummaryUi.tooShort]) lassen sich nicht
 * speichern; ein Fehlschlag beim Speichern ([IndoorSummaryUi.saveFailed])
 * bleibt sichtbar, die Fahrt geht erst mit „Verwerfen" verloren.
 *
 * Nach dem Speichern fuehrt der Hauptknopf „Tour ansehen" ([onOpenTour]) ins
 * Tourdetail der neuen Tour (Seitenkarte, Befund 9: die Auswertung endete
 * frueher nur mit „Fertig" am Ausgangsort); „Fertig" bleibt daneben.
 */
@Composable
fun IndoorSummaryScreen(
    ui: IndoorSummaryUi,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    onDone: () -> Unit,
    onOpenTour: (() -> Unit)? = null,
) {
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    val saved = ui.savedRideId != null

    IndoorPage(
        title = stringResource(R.string.indoor_app_summary_title),
        onBack = null,
        bottomBar = {
            when {
                saved && onOpenTour != null -> {
                    Button(onClick = onOpenTour, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.indoor_app_summary_open_tour_action))
                    }
                    NeutralButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.indoor_app_action_done))
                    }
                }
                saved || ui.tooShort || ui.recordMissing -> {
                    Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.indoor_app_action_done))
                    }
                }
                else -> {
                    Button(onClick = onSave, enabled = !ui.saving, modifier = Modifier.fillMaxWidth()) {
                        if (ui.saving) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text(
                                text = stringResource(R.string.indoor_app_summary_saving),
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        } else {
                            Text(stringResource(R.string.indoor_app_summary_save_action))
                        }
                    }
                    NeutralButton(
                        onClick = { confirmDiscard = true },
                        enabled = !ui.saving,
                        destructive = true,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.indoor_app_summary_discard_action))
                    }
                }
            }
        },
    ) {
        Text(text = ui.routeName, style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TagPill(text = stringResource(R.string.indoor_app_tag_indoor))
            TagPill(text = modeLabel(ui.mode))
            ui.startKm?.let { km ->
                TagPill(text = stringResource(R.string.indoor_app_summary_start_tag, LocalAppFormats.current.decimal(km, 1)))
            }
        }

        when {
            saved -> NoticeBox(
                icon = Icons.Filled.CheckCircle,
                color = LocalSignalColors.current.good,
                text = stringResource(R.string.indoor_app_summary_saved),
            )
            ui.recordMissing -> {
                NoticeBox(
                    icon = Icons.Filled.Warning,
                    color = LocalSignalColors.current.warning,
                    title = stringResource(R.string.indoor_app_summary_record_missing_title),
                    text = stringResource(R.string.indoor_app_summary_record_missing_body),
                )
                ui.recordProblem?.let { MutedText(problemText(it)) }
            }
            ui.tooShort -> NoticeBox(
                icon = Icons.Filled.Info,
                color = MaterialTheme.colorScheme.tertiary,
                text = stringResource(R.string.indoor_app_summary_too_short, INDOOR_MIN_SAVE_SECONDS),
            )
            ui.saveFailed -> NoticeBox(
                icon = Icons.Filled.Warning,
                color = LocalSignalColors.current.warning,
                title = stringResource(R.string.indoor_app_summary_save_failed_title),
                text = stringResource(R.string.indoor_app_summary_save_failed_body),
            )
        }

        SummaryFacts(ui)
        // Mit der eigenen Aussenfahrt als Mitfahrer: der Vergleich mit ihr (IndoorOutdoorComparison.kt).
        if (ui.ghostOutdoor) OutdoorComparison(ui) else GhostComparison(ui)
        IndoorTrailClimbs(ui.climbs)
    }

    if (confirmDiscard) {
        OneUiDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.indoor_app_summary_discard_confirm_title)) },
            text = { Text(stringResource(R.string.indoor_app_summary_discard_confirm_body)) },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) {
                    Text(stringResource(R.string.indoor_app_action_cancel))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDiscard = false
                        onDiscard()
                    },
                ) {
                    Text(stringResource(R.string.indoor_app_summary_discard_action))
                }
            },
        )
    }
}

/** Kennzahlen in zwei Spalten; fehlende Sensorwerte erscheinen als „–". */
@Composable
private fun SummaryFacts(ui: IndoorSummaryUi) {
    val formats = LocalAppFormats.current
    val none = INDOOR_NO_VALUE
    val facts = buildList {
        add(stringResource(R.string.indoor_app_summary_time) to formatIndoorClock(ui.movingS))
        add(
            stringResource(R.string.indoor_app_summary_distance) to
                stringResource(R.string.indoor_app_value_km, formats.km(ui.distanceKm)),
        )
        add(
            stringResource(R.string.indoor_app_summary_ascent) to
                stringResource(R.string.indoor_app_value_elevation, formats.indoorAscent(ui.ascentM)),
        )
        add(
            stringResource(R.string.indoor_app_summary_speed) to
                stringResource(R.string.indoor_app_value_kmh, formats.decimal(ui.avgSpeedKmh, 1)),
        )
        add(
            stringResource(R.string.indoor_app_summary_power) to
                (ui.avgPowerW?.let { stringResource(R.string.indoor_app_value_watts, it) } ?: none),
        )
        add(
            stringResource(R.string.indoor_app_summary_np) to
                (ui.normalizedPowerW?.let { stringResource(R.string.indoor_app_value_watts, it) } ?: none),
        )
        add(
            stringResource(R.string.indoor_app_summary_cadence) to
                (ui.avgCadenceRpm?.let { stringResource(R.string.indoor_app_value_rpm, it) } ?: none),
        )
        add(
            stringResource(R.string.indoor_app_summary_heart_rate) to
                (ui.avgHrBpm?.let { stringResource(R.string.indoor_app_value_bpm, it) } ?: none),
        )
        add(stringResource(R.string.indoor_app_summary_work) to stringResource(R.string.indoor_app_value_kj, ui.workKj))
        if (ui.laps > 1) {
            add(stringResource(R.string.indoor_app_summary_laps) to ui.laps.toString())
        }
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(IndoorCardPadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            facts.chunked(2).forEach { pair ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    pair.forEach { (label, value) -> Fact(label = label, value = value, modifier = Modifier.weight(1f)) }
                    if (pair.size == 1) Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/** „Der Geist war am Ende 0:12 vor dir." */
@Composable
private fun GhostComparison(ui: IndoorSummaryUi) {
    val gap = ui.ghostGapS ?: return
    val name = ui.ghostLabel ?: stringResource(R.string.indoor_app_hud_ghost_default)
    Eyebrow(stringResource(R.string.indoor_app_summary_ghost_eyebrow))
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(IndoorCardPadding), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = name, style = MaterialTheme.typography.titleSmall)
            val time = formatIndoorGapSeconds(gap)
            Text(
                text = when {
                    kotlin.math.abs(gap) < GHOST_EVEN_SECONDS -> stringResource(R.string.indoor_app_summary_ghost_even)
                    gap > 0.0 -> stringResource(R.string.indoor_app_summary_ghost_ahead, time)
                    else -> stringResource(R.string.indoor_app_summary_ghost_behind, time)
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
