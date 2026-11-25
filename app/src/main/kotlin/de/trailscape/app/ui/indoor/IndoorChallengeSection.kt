package de.trailscape.app.ui.indoor

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.trailscape.app.R
import de.trailscape.app.challenge.ChallengeAvailability
import de.trailscape.app.challenge.ChallengeStore
import de.trailscape.app.challenge.readChallengeBytes
import de.trailscape.app.i18n.LocalAppFormats
import de.trailscape.app.ui.components.Eyebrow
import de.trailscape.app.ui.components.NoticeBox
import de.trailscape.app.ui.components.OneUiDialog
import de.trailscape.app.ui.components.TagPill
import de.trailscape.core.challenge.CHALLENGE_NAME_MAX_CHARS
import de.trailscape.core.challenge.ChallengeReject
import de.trailscape.core.challenge.ChallengeRoute
import de.trailscape.core.i18n.formatDateShort
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * # „Herausforderungen" auf der Indoor-Startseite
 *
 * Eine Karte mit den gespeicherten Herausforderungen (geteilte Fahrten anderer)
 * und „Datei wählen". Pro Eintrag je nach [ChallengeAvailability]:
 *  * `READY`: „Gegen <Name> fahren" — wählt Strecke und Mitfahrer;
 *  * `DOWNLOAD`: „Strecke laden" — der vorhandene Katalog-Ablauf (Voranschlag,
 *    Laden, Fortsetzen);
 *  * `MISMATCH`/`UNKNOWN`: erklärender Hinweis, nicht fahrbar.
 *
 * Datenschutz und Format: docs/herausforderung.md.
 */
@Composable
fun IndoorChallengeSection(
    ui: IndoorChallengesUi,
    onRide: (String, String) -> Unit,
    onDownload: (String) -> Unit,
    onDelete: (String) -> Unit,
    onImport: (ByteArray?) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch { onImport(withContext(Dispatchers.IO) { readChallengeBytes(context, uri) }) }
        }
    }
    var askDelete by rememberSaveable { mutableStateOf<String?>(null) }

    Eyebrow(stringResource(R.string.spiel3_section_title))
    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            if (ui.items.isEmpty()) {
                MutedText(
                    text = stringResource(R.string.spiel3_section_empty),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
            ui.items.forEachIndexed { i, item ->
                if (i > 0) HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                ChallengeRow(
                    item = item,
                    selected = ui.selectedId == item.id,
                    onRide = { routeName -> onRide(item.id, routeName) },
                    onDownload = { item.catalogId?.let(onDownload) },
                    onDelete = { askDelete = item.id },
                )
            }
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            TextButton(
                onClick = { picker.launch(arrayOf("*/*")) },
                modifier = Modifier.padding(horizontal = 8.dp),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text(
                    text = stringResource(R.string.spiel3_section_pick_file),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }

    askDelete?.let { id ->
        val name = ui.items.firstOrNull { it.id == id }?.let { challengeTitle(it.name) }.orEmpty()
        OneUiDialog(
            onDismissRequest = { askDelete = null },
            title = { Text(stringResource(R.string.spiel3_delete_title)) },
            text = { Text(stringResource(R.string.spiel3_delete_body, name)) },
            dismissButton = {
                TextButton(onClick = { askDelete = null }) { Text(stringResource(R.string.common_action_cancel)) }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        askDelete = null
                        onDelete(id)
                    },
                ) { Text(stringResource(R.string.common_action_delete)) }
            },
        )
    }
}

@Composable
private fun ChallengeRow(
    item: IndoorChallengeUi,
    selected: Boolean,
    onRide: (String) -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
) {
    val lang = LocalAppFormats.current.language
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = challengeTitle(item.name), style = MaterialTheme.typography.titleSmall)
                Text(
                    text = challengeSubtitle(item.routeName?.pick(lang.tag), item.distanceKm, item.durationS, item.date),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (selected) TagPill(text = stringResource(R.string.spiel3_row_selected))
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.spiel3_row_delete_cd))
            }
        }
        when (item.availability) {
            ChallengeAvailability.READY -> Button(onClick = { onRide(item.routeName?.pick(lang.tag).orEmpty()) }, modifier = Modifier.fillMaxWidth()) {
                Text(
                    if (item.name.isBlank()) {
                        stringResource(R.string.spiel3_row_ride_unnamed)
                    } else {
                        stringResource(R.string.spiel3_row_ride, item.name)
                    },
                )
            }
            ChallengeAvailability.DOWNLOAD -> OutlinedButton(onClick = onDownload, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.spiel3_row_download))
            }
            ChallengeAvailability.MISMATCH -> MutedText(stringResource(R.string.spiel3_row_mismatch))
            ChallengeAvailability.UNKNOWN -> MutedText(stringResource(R.string.spiel3_row_unknown))
        }
    }
}

// ---------------------------------------------------------------------------
// Vorschau: was die Datei enthält
// ---------------------------------------------------------------------------

/** Name, oder ein Ersatz, wenn die Datei keinen trägt. */
@Composable
internal fun challengeTitle(name: String): String =
    name.ifBlank { stringResource(R.string.spiel3_unnamed) }

@Composable
private fun challengeSubtitle(routeName: String?, distanceKm: Double, durationS: Int, date: String?): String {
    val formats = LocalAppFormats.current
    val route = routeName ?: stringResource(R.string.indoor_app_route_testworld_title)
    val parts = buildList {
        add(route)
        add(stringResource(R.string.indoor_app_value_km, formats.km(distanceKm)))
        add(formatIndoorClock(durationS))
        formatChallengeDate(date)?.let(::add)
    }
    return parts.joinToString(" · ")
}

@Composable
internal fun formatChallengeDate(iso: String?): String? {
    val lang = LocalAppFormats.current.language
    return iso?.let { runCatching { formatDateShort(LocalDate.parse(it), lang) }.getOrNull() }
}

/**
 * Die Vorschau: **alles**, was die Datei trägt, und was sie bewusst nicht trägt.
 * Dieselbe Ansicht beim Teilen (eigene Fahrt) und beim Import (fremde Datei).
 */
@Composable
fun ChallengePreviewFacts(preview: ChallengePreviewUi) {
    val formats = LocalAppFormats.current
    val lang = formats.language
    val route = preview.info.name?.pick(lang.tag) ?: stringResource(R.string.indoor_app_route_testworld_title)
    val version = (preview.route as? ChallengeRoute.Catalog)?.version
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        PreviewLine(
            stringResource(R.string.spiel3_preview_route),
            if (version != null) stringResource(R.string.spiel3_preview_route_version, route, version) else route,
        )
        PreviewLine(stringResource(R.string.spiel3_preview_name), challengeTitle(preview.name))
        PreviewLine(
            stringResource(R.string.spiel3_preview_ride),
            stringResource(
                R.string.spiel3_preview_ride_value,
                stringResource(R.string.indoor_app_value_km, formats.km(preview.distanceKm)),
                formatIndoorClock(preview.durationS),
            ),
        )
        PreviewLine(
            stringResource(R.string.spiel3_preview_date),
            formatChallengeDate(preview.date) ?: stringResource(R.string.spiel3_preview_not_included),
        )
        PreviewLine(
            stringResource(R.string.spiel3_preview_avatar),
            stringResource(
                if (preview.hasAvatar) R.string.spiel3_preview_included else R.string.spiel3_preview_not_included,
            ),
        )
        MutedText(stringResource(R.string.spiel3_preview_never))
    }
}

@Composable
private fun PreviewLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.38f),
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.62f))
    }
}

// ---------------------------------------------------------------------------
// Import-Dialog
// ---------------------------------------------------------------------------

/**
 * Vorschau, Speichern und Rückmeldung beim Import einer Datei. Für die
 * Dateiauswahl in der App und die `ChallengeImportActivity`. `ui == null` =
 * die Datei wird noch gelesen (nichts zu zeigen).
 *
 * @param onOpenApp nur in der `ChallengeImportActivity`: „Trailscape öffnen" nach dem Speichern.
 */
@Composable
fun ChallengeImportDialog(
    ui: ChallengeImportUi?,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    onOpenApp: (() -> Unit)? = null,
) {
    when (ui) {
        null -> Unit
        is ChallengeImportUi.Preview -> OneUiDialog(
            onDismissRequest = onDismiss,
            title = {
                Text(
                    if (ui.preview.name.isBlank()) {
                        stringResource(R.string.spiel3_import_title_unnamed)
                    } else {
                        stringResource(R.string.spiel3_import_title, ui.preview.name)
                    },
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ChallengePreviewFacts(ui.preview)
                    MutedText(availabilityText(ui.preview))
                }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.spiel3_import_discard)) } },
            confirmButton = { TextButton(onClick = onSave) { Text(stringResource(R.string.common_action_save)) } },
        )
        is ChallengeImportUi.Saved -> {
            val savedOk = ui.result == ChallengeStore.SaveResult.SAVED || ui.result == ChallengeStore.SaveResult.ALREADY_THERE
            if (onOpenApp != null) {
                OneUiDialog(
                    onDismissRequest = onDismiss,
                    title = { Text(savedTitle(ui.result)) },
                    text = { Text(stringResource(if (savedOk) R.string.spiel3_import_saved_body else R.string.spiel3_import_failed_body)) },
                    dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_action_close)) } },
                    confirmButton = { TextButton(onClick = onOpenApp) { Text(stringResource(R.string.spiel3_import_open_app)) } },
                )
            } else {
                OneUiDialog(
                    onDismissRequest = onDismiss,
                    title = { Text(savedTitle(ui.result)) },
                    text = { Text(stringResource(if (savedOk) R.string.spiel3_import_saved_body else R.string.spiel3_import_failed_body)) },
                    confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_action_close)) } },
                )
            }
        }
        is ChallengeImportUi.Failed -> OneUiDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.spiel3_reject_title)) },
            text = { Text(rejectText(ui.reason, ui.fileVersion)) },
            confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_action_close)) } },
        )
    }
}

@Composable
private fun availabilityText(preview: ChallengePreviewUi): String = when (preview.info.availability) {
    ChallengeAvailability.READY -> stringResource(R.string.spiel3_avail_ready)
    ChallengeAvailability.DOWNLOAD -> stringResource(R.string.spiel3_avail_download)
    ChallengeAvailability.MISMATCH -> stringResource(R.string.spiel3_avail_mismatch)
    ChallengeAvailability.UNKNOWN -> stringResource(R.string.spiel3_avail_unknown)
}

@Composable
private fun savedTitle(result: ChallengeStore.SaveResult): String = stringResource(
    when (result) {
        ChallengeStore.SaveResult.SAVED -> R.string.spiel3_import_saved
        ChallengeStore.SaveResult.ALREADY_THERE -> R.string.spiel3_import_already
        ChallengeStore.SaveResult.FULL -> R.string.spiel3_import_full
        ChallengeStore.SaveResult.FAILED -> R.string.spiel3_import_failed
    },
)

/** Verständliche Ablehnung — nie ein Absturz, nie ein Fachbegriff. */
@Composable
internal fun rejectText(reason: ChallengeReject, fileVersion: Int?): String = when (reason) {
    ChallengeReject.TOO_NEW -> stringResource(R.string.spiel3_reject_too_new, fileVersion ?: 0)
    ChallengeReject.TOO_LARGE -> stringResource(R.string.spiel3_reject_too_large)
    ChallengeReject.NOT_JSON, ChallengeReject.WRONG_FORMAT -> stringResource(R.string.spiel3_reject_not_ours)
    ChallengeReject.ROUTE_OWN_UNSUPPORTED -> stringResource(R.string.spiel3_reject_own_route)
    ChallengeReject.ROUTE_INVALID -> stringResource(R.string.spiel3_reject_route)
    ChallengeReject.CURVE_INVALID, ChallengeReject.CURVE_IMPLAUSIBLE -> stringResource(R.string.spiel3_reject_curve)
}

// ---------------------------------------------------------------------------
// Teilen der eigenen Fahrt
// ---------------------------------------------------------------------------

/**
 * Vorschau und Auswahl vor dem Teilen: Name und Datum sind frei, die Vorschau
 * zeigt den Inhalt der Datei wie er jetzt wäre. „Teilen" öffnet das Share-Sheet.
 */
@Composable
fun ChallengeShareDialog(
    ui: ChallengeShareUi,
    onNameChange: (String) -> Unit,
    onIncludeDateChange: (Boolean) -> Unit,
    onShare: () -> Unit,
    onDismiss: () -> Unit,
) {
    OneUiDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(if (ui.reply) R.string.spiel3_share_title_reply else R.string.spiel3_share_title),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (ui.failed) {
                    NoticeBox(
                        icon = Icons.Filled.Warning,
                        color = MaterialTheme.colorScheme.tertiary,
                        text = stringResource(R.string.spiel3_share_failed),
                    )
                } else {
                    OutlinedTextField(
                        value = ui.name,
                        onValueChange = { onNameChange(it.take(CHALLENGE_NAME_MAX_CHARS * 2)) },
                        label = { Text(stringResource(R.string.spiel3_share_name_label)) },
                        supportingText = { Text(stringResource(R.string.spiel3_share_name_hint, CHALLENGE_NAME_MAX_CHARS)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.spiel3_share_date_switch),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Switch(checked = ui.includeDate, onCheckedChange = onIncludeDateChange)
                    }
                    Eyebrow(stringResource(R.string.spiel3_share_preview_title))
                    ui.preview?.let { ChallengePreviewFacts(it) }
                    MutedText(stringResource(R.string.spiel3_share_privacy))
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_action_cancel)) } },
        confirmButton = {
            TextButton(onClick = onShare, enabled = ui.json != null && !ui.failed) {
                Text(stringResource(R.string.spiel3_share_action))
            }
        },
    )
}

// ---------------------------------------------------------------------------
// Auswertung: Ergebnis gegen die Herausforderung
// ---------------------------------------------------------------------------

/** Ab diesem Abstand (s) gilt es als „gleichauf". */
private const val CHALLENGE_EVEN_S = 1.0

/** „12 s" bzw. „1:12 min" — knapp, für „x s vor/hinter <Name>". */
@Composable
internal fun formatChallengeGap(gapS: Double): String {
    val s = kotlin.math.abs(gapS).let { Math.round(it).toInt() }
    return if (s < 60) {
        stringResource(R.string.spiel3_result_seconds, s)
    } else {
        stringResource(R.string.spiel3_result_minutes, s / 60, s % 60)
    }
}

/**
 * „12 s vor Anna" / „12 s hinter Anna" und die Möglichkeit, die eigene Fahrt
 * als Antwort zu teilen. [gapS] wie [IndoorSummaryUi.ghostGapS]: positiv = der
 * Gegner war vorn.
 */
@Composable
fun ChallengeResultCard(opponent: String, gapS: Double?, canShare: Boolean, onShare: () -> Unit) {
    Eyebrow(stringResource(R.string.spiel3_result_eyebrow))
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(IndoorCardPadding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val name = challengeTitle(opponent)
            Text(text = name, style = MaterialTheme.typography.titleSmall)
            Text(
                text = when {
                    gapS == null -> stringResource(R.string.spiel3_result_unknown)
                    kotlin.math.abs(gapS) < CHALLENGE_EVEN_S -> stringResource(R.string.spiel3_result_even, name)
                    gapS > 0.0 -> stringResource(R.string.spiel3_result_behind, formatChallengeGap(gapS), name)
                    else -> stringResource(R.string.spiel3_result_ahead, formatChallengeGap(gapS), name)
                },
                style = MaterialTheme.typography.bodyLarge,
            )
            if (canShare) {
                OutlinedButton(onClick = onShare, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Share, contentDescription = null)
                    Text(
                        text = stringResource(R.string.spiel3_result_reply),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }
}

/** Der Knopf „Herausforderung teilen" in der Auswertung, wenn kein Gegner mitfuhr. */
@Composable
fun ChallengeShareEntry(onShare: () -> Unit) {
    OutlinedButton(onClick = onShare, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Filled.Share, contentDescription = null)
        Text(text = stringResource(R.string.spiel3_share_entry), modifier = Modifier.padding(start = 8.dp))
    }
}
