package de.trailscape.app.ui.indoor

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import de.trailscape.app.R
import de.trailscape.app.indoor.IndoorProtocolStore
import de.trailscape.app.ui.components.AtlasCard
import de.trailscape.app.ui.components.Eyebrow
import de.trailscape.app.ui.components.TrailscapePrimaryButton
import de.trailscape.app.ui.components.TrailscapeSecondaryButton
import de.trailscape.app.ui.prepareShareDirectory
import de.trailscape.core.indoor.FEEL_TARGET_WINDOW_S
import de.trailscape.core.indoor.FeelPhase
import de.trailscape.core.indoor.FeelRejection
import de.trailscape.core.indoor.FeelResult
import de.trailscape.core.indoor.RideFeel
import java.io.File
import kotlin.math.roundToInt

/**
 * # Fahrgefühl: Regler, geführter Abgleich, Fahrprotokolle (idee-06)
 *
 * Zustandslose Bildschirmteile; Zustand und Dateizugriffe liegen im
 * `IndoorFeelController` bzw. dem Aufrufer (`IndoorFlowScreen`).
 * Siehe docs/indoor/kalibrierung.md.
 */

private fun pct(v: Double): Int = (v * 100).roundToInt()

/**
 * Die zwei Regler „Gesamtwiderstand“ und „Stärke des Belags“ samt „Zurücksetzen“.
 * Grenzen und Vorgabe stehen in [RideFeel].
 */
@Composable
internal fun FeelCard(
    feel: RideFeel,
    onResistanceChange: (Double) -> Unit,
    onSurfaceChange: (Double) -> Unit,
    onReset: () -> Unit,
) {
    AtlasCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(IndoorCardPadding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Eyebrow(stringResource(R.string.indoor_feel_card_title))
            FeelSlider(
                title = stringResource(R.string.indoor_feel_resistance_title),
                value = feel.resistance,
                range = RideFeel.RESISTANCE_MIN..RideFeel.RESISTANCE_MAX,
                steps = 11,
                low = stringResource(R.string.indoor_feel_resistance_light),
                high = stringResource(R.string.indoor_feel_resistance_heavy),
                onChange = onResistanceChange,
            )
            MutedText(stringResource(R.string.indoor_feel_resistance_body))
            HorizontalDivider()
            FeelSlider(
                title = stringResource(R.string.indoor_feel_surface_title),
                value = feel.surfaceStrength,
                range = RideFeel.SURFACE_MIN..RideFeel.SURFACE_MAX,
                steps = 19,
                low = stringResource(R.string.indoor_feel_surface_light),
                high = stringResource(R.string.indoor_feel_surface_heavy),
                onChange = onSurfaceChange,
            )
            MutedText(stringResource(R.string.indoor_feel_surface_body))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.indoor_feel_default_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onReset, enabled = !feel.isDefault) {
                    Text(stringResource(R.string.indoor_feel_reset))
                }
            }
        }
    }
}

@Composable
private fun FeelSlider(
    title: String,
    value: Double,
    range: ClosedFloatingPointRange<Double>,
    steps: Int,
    low: String,
    high: String,
    onChange: (Double) -> Unit,
) {
    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.indoor_feel_value_percent, pct(value)), style = MaterialTheme.typography.titleSmall)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toDouble()) },
            valueRange = range.start.toFloat()..range.endInclusive.toFloat(),
            steps = steps,
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(low, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(high, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---------------------------------------------------------------------------
// Geführter Abgleich
// ---------------------------------------------------------------------------

/**
 * Die Seite des Abgleichs während der Fahrt: Anleitung, Fortschritt, Messwerte,
 * am Ende Vorschlag oder klare Ablehnung. [onLeave] beendet die Fahrt (nichts
 * wird als Tour gespeichert).
 */
@Composable
internal fun IndoorCalibrationScreen(
    ui: IndoorCalibrationUi,
    onGearLess: () -> Unit,
    onGearMore: () -> Unit,
    onFinishNow: () -> Unit,
    onAccept: () -> Unit,
    onLeave: () -> Unit,
) {
    IndoorPage(
        title = stringResource(R.string.indoor_feel_cal_title),
        onBack = null,
        bottomBar = {
            val result = ui.result
            when {
                result is FeelResult.Suggest && !ui.accepted && result.suggestion.worthChanging -> {
                    TrailscapePrimaryButton(onClick = onAccept, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.indoor_feel_cal_accept))
                    }
                    TrailscapeSecondaryButton(onClick = onLeave, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.indoor_feel_cal_decline))
                    }
                }
                result != null -> {
                    TrailscapePrimaryButton(onClick = onLeave, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.indoor_feel_cal_done))
                    }
                }
                else -> TrailscapeSecondaryButton(onClick = onLeave, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.indoor_feel_cal_cancel))
                }
            }
        },
    ) {
        val result = ui.result
        if (result == null) {
            MutedText(stringResource(R.string.indoor_feel_cal_intro))
            AtlasCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(IndoorCardPadding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = when (ui.phase) {
                            FeelPhase.WAITING -> stringResource(R.string.indoor_feel_cal_waiting)
                            FeelPhase.WARMUP -> stringResource(
                                R.string.indoor_feel_cal_warmup,
                                (de.trailscape.core.indoor.FEEL_WARMUP_S - ui.phaseS + 1).coerceAtLeast(0),
                            )
                            else -> stringResource(R.string.indoor_feel_cal_measuring, ui.remainingS)
                        },
                        style = MaterialTheme.typography.titleSmall,
                    )
                    LinearProgressIndicator(
                        progress = { if (ui.phase == FeelPhase.MEASURING) ui.phaseS / FEEL_TARGET_WINDOW_S.toFloat() else 0f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = stringResource(
                            R.string.indoor_feel_cal_live,
                            ui.powerW ?: 0,
                            ui.cadenceRpm ?: 0,
                            ui.gearIndex.coerceAtLeast(0),
                        ),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    // FlowRow: passt der zweite Knopf nicht mehr daneben, rutscht er in die naechste Zeile.
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TrailscapeSecondaryButton(onClick = onGearLess) { Text(stringResource(R.string.indoor_feel_cal_gear_less)) }
                        TrailscapeSecondaryButton(onClick = onGearMore) { Text(stringResource(R.string.indoor_feel_cal_gear_more)) }
                    }
                    if (ui.phase == FeelPhase.MEASURING && ui.phaseS >= de.trailscape.core.indoor.FEEL_MIN_SAMPLES) {
                        TextButton(onClick = onFinishNow) { Text(stringResource(R.string.indoor_feel_cal_finish_now)) }
                    }
                }
            }
        } else {
            CalibrationResult(result, ui.accepted)
        }
        MutedText(stringResource(R.string.indoor_feel_cal_note))
    }
}

@Composable
private fun CalibrationResult(result: FeelResult, accepted: Boolean) {
    AtlasCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(IndoorCardPadding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (result) {
                is FeelResult.Suggest -> {
                    val s = result.suggestion
                    Eyebrow(stringResource(R.string.indoor_feel_cal_result_title))
                    Text(
                        text = if (s.worthChanging) {
                            stringResource(R.string.indoor_feel_cal_result_suggest, pct(s.currentResistance), pct(s.suggestedResistance))
                        } else {
                            stringResource(R.string.indoor_feel_cal_result_same, pct(s.currentResistance))
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    MutedText(
                        stringResource(
                            R.string.indoor_feel_cal_result_detail,
                            s.medianPowerW.roundToInt(),
                            s.medianCadenceRpm.roundToInt(),
                            s.expectedPowerW.roundToInt(),
                            s.uncertaintyPct.roundToInt().coerceAtLeast(1),
                        ),
                    )
                    if (s.clamped) MutedText(stringResource(R.string.indoor_feel_cal_result_clamped))
                    if (accepted) MutedText(stringResource(R.string.indoor_feel_cal_accepted))
                }
                is FeelResult.Reject -> {
                    Eyebrow(stringResource(R.string.indoor_feel_cal_reject_title))
                    Text(stringResource(rejectionText(result.reason)), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

internal fun rejectionText(reason: FeelRejection): Int = when (reason) {
    FeelRejection.TOO_FEW_SAMPLES -> R.string.indoor_feel_cal_reject_too_few
    FeelRejection.POWER_TOO_LOW -> R.string.indoor_feel_cal_reject_power
    FeelRejection.CADENCE_OUT_OF_RANGE -> R.string.indoor_feel_cal_reject_cadence
    FeelRejection.UNSTEADY -> R.string.indoor_feel_cal_reject_unsteady
    FeelRejection.INCONSISTENT_SPEED -> R.string.indoor_feel_cal_reject_speed
    FeelRejection.GEAR_CHANGED -> R.string.indoor_feel_cal_reject_gear
    FeelRejection.IMPLAUSIBLE -> R.string.indoor_feel_cal_reject_implausible
}

// ---------------------------------------------------------------------------
// Fahrprotokolle
// ---------------------------------------------------------------------------

/** Eine Zeile der Protokollliste. */
internal data class ProtocolRowUi(val id: String, val title: String, val sizeKb: Int)

/** Höchstens so viele Zeilen zeigt die Ansicht; geteilt wird immer die ganze Datei. */
internal const val PROTOCOL_PREVIEW_LINES = 120

/**
 * Liste der Protokolle bzw. — wenn [openedText] gesetzt ist — eines davon im
 * Wortlaut (Textvorschau, auswählbar) mit „Teilen“.
 */
@Composable
internal fun IndoorProtocolScreen(
    rows: List<ProtocolRowUi>,
    openedId: String?,
    openedText: String?,
    onOpen: (String) -> Unit,
    onShare: (String) -> Unit,
    onDelete: (String) -> Unit,
    onDeleteAll: () -> Unit,
    onCloseOpened: () -> Unit,
    onBack: () -> Unit,
) {
    IndoorPage(
        title = stringResource(R.string.indoor_feel_log_title),
        onBack = if (openedId != null) onCloseOpened else onBack,
    ) {
        if (openedId != null && openedText != null) {
            val lines = openedText.lines()
            val preview = lines.take(PROTOCOL_PREVIEW_LINES).joinToString("\n")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TrailscapePrimaryButton(onClick = { onShare(openedId) }) { Text(stringResource(R.string.indoor_feel_log_share)) }
                TrailscapeSecondaryButton(onClick = { onDelete(openedId) }, destructive = true) { Text(stringResource(R.string.indoor_feel_log_delete)) }
            }
            if (lines.size > PROTOCOL_PREVIEW_LINES) {
                MutedText(stringResource(R.string.indoor_feel_log_truncated, PROTOCOL_PREVIEW_LINES))
            }
            AtlasCard(modifier = Modifier.fillMaxWidth()) {
                SelectionContainer {
                    Text(
                        text = preview,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(IndoorCardPadding),
                    )
                }
            }
            return@IndoorPage
        }
        MutedText(stringResource(R.string.indoor_feel_log_intro))
        if (rows.isEmpty()) {
            MutedText(stringResource(R.string.indoor_feel_log_empty))
            return@IndoorPage
        }
        AtlasCard(modifier = Modifier.fillMaxWidth()) {
            Column {
                rows.forEachIndexed { i, row ->
                    if (i > 0) HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = stringResource(R.string.indoor_feel_log_entry, row.title, row.sizeKb),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { onOpen(row.id) }) { Text(stringResource(R.string.indoor_feel_log_open)) }
                        TextButton(onClick = { onShare(row.id) }) { Text(stringResource(R.string.indoor_feel_log_share)) }
                    }
                }
            }
        }
        TrailscapeSecondaryButton(onClick = onDeleteAll, destructive = true, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.indoor_feel_log_delete_all))
        }
    }
}

/**
 * Teilt ein Protokoll als Textdatei über den System-Auswahldialog (FileProvider,
 * Cache-Unterverzeichnis wie bei den GPX-Exporten). Nichts wird hochgeladen; die
 * Datei geht nur an die App, die der Nutzer wählt.
 */
internal fun shareProtocol(context: Context, entry: IndoorProtocolStore.Entry) {
    val dir = prepareShareDirectory(context.cacheDir)
    val copy = File(dir, entry.file.name)
    entry.file.copyTo(copy, overwrite = true)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", copy)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.indoor_feel_log_share_subject))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(
        Intent.createChooser(send, context.getString(R.string.indoor_feel_log_share_chooser)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}
