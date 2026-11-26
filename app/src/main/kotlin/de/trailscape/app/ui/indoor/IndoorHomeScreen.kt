package de.trailscape.app.ui.indoor

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.trailscape.app.R
import de.trailscape.app.i18n.LocalAppFormats
import de.trailscape.app.ui.components.Eyebrow
import de.trailscape.app.ui.components.NoticeBox
import de.trailscape.app.ui.components.PillSegments
import de.trailscape.app.ui.components.TagPill
import de.trailscape.app.ui.theme.LocalSignalColors
import de.trailscape.core.indoor.IndoorMode

/** Schrittweite der Vergleichsleistung in Watt. */
private const val GHOST_WATTS_STEP = 10

/** Grenzen der Vergleichsleistung (wie im ViewModel). */
private const val GHOST_WATTS_MIN = 30
private const val GHOST_WATTS_MAX = 1000

/**
 * # Startseite des Indoor-Trainings (docs/indoor/app.md, „Nutzerfluss")
 *
 * Ein offensichtlicher Hauptweg von oben nach unten:
 *
 *  1. **Trainer** — Status mit direktem „Koppeln" bzw. „Ändern" (fuehrt auf
 *     die Nebenseite [IndoorPairScreen]); Bluetooth aus oder fehlende
 *     Berechtigung stehen hier, nicht erst beim Start der Fahrt.
 *  2. Die Karte **Training des Tages**, wenn es eins gibt (eigener Knopf).
 *  3. **Optionen** (Fahrweise, Mitfahrer) — eingeklappt mit dem gewaehlten
 *     Stand in einer Zeile.
 *  4. **Eine Streckenliste**: deine Strecken und die bekannten Strecken
 *     untereinander, jede mit Status („Bereit", „Herunterladen · 77 MB",
 *     „Laedt · 45 %", „Wird einmal vorbereitet").
 *  5. Unten fest: ein Satz, was noch fehlt, mit dem Knopf, der es behebt
 *     (Koppeln, Herunterladen, Fortsetzen, Abbrechen), und der grosse Knopf
 *     **„Fahren"** — nur aktiv, wenn alles bereit ist ([indoorCanRide]).
 *
 * ## Genau eine Hauptaktion
 * Schlaegt die Karte „Einheit des Tages" etwas zum Losfahren vor und ist in
 * der Liste noch nichts gewaehlt, ist ihr Knopf die Hauptaktion: Unten
 * entfallen dann der (sonst ausgegraute) Knopf „Fahren" und der Satz „Wähle
 * oben eine Strecke." — er widerspraeche der Karte. Sobald eine Strecke
 * gewaehlt ist, steht unten wieder „Fahren" fuer diese Strecke, und der Knopf
 * der Karte tritt getoent zurueck.
 *
 * Technisches (Fahrgefuehl, Tageszeit, Diagnose, Strecken auf dem Geraet,
 * Liste aktualisieren) liegt hinter dem Zahnrad ([onOpenSettings]).
 * Zustandslos: jede Wahl geht als Rueckruf nach oben.
 *
 * [onGhostChange] bekommt bei „Frühere Fahrt" ein `PastRide` mit leerem Label
 * — das ViewModel setzt Namen und Datum selbst.
 *
 * ## Als Tab
 * Im Tab „Indoor" (`IndoorTabScreen.kt`) ist diese Seite die Sicht
 * „Strecken": [onBack] ist dann `null` (kein Zurueck-Pfeil), [title] der Name
 * des Tabs, [headerExtra] traegt die Segmente, und das Zahnrad
 * ([onOpenSettings]) oeffnet die eine Einstellungsseite der App. Daneben
 * steht dann das Symbol der Garage ([onOpenGarage]).
 */
@Composable
fun IndoorHomeScreen(
    route: IndoorRouteUi,
    pair: IndoorPairUi,
    trainerIssue: IndoorTrainerIssue?,
    onSelect: (IndoorRouteOption) -> Unit,
    onModeChange: (IndoorMode) -> Unit,
    onGhostChange: (IndoorGhostOption) -> Unit,
    onRide: () -> Unit,
    onBack: (() -> Unit)?,
    onOpenPair: () -> Unit,
    onOpenSettings: () -> Unit,
    catalog: IndoorCatalogUi = IndoorCatalogUi(emptyList()),
    onCatalogDownload: (String) -> Unit = {},
    onCatalogCancel: (String) -> Unit = {},
    onCatalogDiscard: (String) -> Unit = {},
    day: IndoorDayUi? = null,
    onDayRide: () -> Unit = {},
    onDayNext: () -> Unit = {},
    onDayGenerated: () -> Unit = {},
    onDayPath: (IndoorDayPath) -> Unit = {},
    initialOptionsOpen: Boolean = false,
    initialCatalogOpen: Boolean = false,
    catalogExtras: IndoorCatalogExtras = IndoorCatalogExtras(),
    initialCatalogFilterOpen: Boolean = false,
    initialCatalogDetailId: String? = null,
    title: String = stringResource(R.string.indoor_app_route_title),
    headerExtra: (@Composable () -> Unit)? = null,
    onOpenGarage: (() -> Unit)? = null,
) {
    val lang = LocalAppFormats.current.language.tag
    val readiness = indoorRouteReadiness(
        route = route,
        catalog = catalog,
        testWorldName = stringResource(R.string.indoor_app_route_testworld_title),
        nameOf = { it.name.pick(lang) },
    )
    val canRide = indoorCanRide(trainerIssue, route, readiness)
    // Die Tageskarte traegt die Hauptaktion, solange unten nichts gewaehlt ist.
    val dayLeads = readiness == IndoorRouteReadiness.NoRoute && (day as? IndoorDayUi.Offer)?.startable == true
    IndoorPage(
        title = title,
        onBack = onBack,
        headerExtra = headerExtra,
        actions = {
            // Garage: Fahrer und Rad anpassen (docs/indoor/garage.md) — der eine
            // Einstieg, nur im Tab; im gepushten Ablauf ist [onOpenGarage] `null`.
            if (onOpenGarage != null) {
                IconButton(onClick = onOpenGarage) {
                    Icon(Icons.Outlined.Palette, contentDescription = stringResource(R.string.garage_home_cd))
                }
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.indoor_app_home_settings_cd))
            }
        },
        // Mit der Tageskarte als Hauptaktion bleibt unten nur, was am Trainer fehlt.
        bottomBar = if (dayLeads && trainerIssue == null) null else {
            {
            ReadinessLines(
                trainerIssue = trainerIssue,
                readiness = readiness,
                showNoRoute = !dayLeads,
                onOpenPair = onOpenPair,
                onDownload = onCatalogDownload,
                onCancel = onCatalogCancel,
                onDiscard = onCatalogDiscard,
            )
            if (!dayLeads) Button(
                onClick = onRide,
                enabled = canRide,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(22.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.indoor_app_home_ride_action), style = MaterialTheme.typography.titleMedium)
            }
            }
        },
    ) {
        TrainerCard(pair = pair, issue = trainerIssue, onOpenPair = onOpenPair)

        // Training des Tages: eine passende Strecke ohne weitere Auswahl.
        day?.let {
            IndoorDayCard(
                ui = it, onRide = { onDayRide() }, onNext = onDayNext,
                onRideGenerated = onDayGenerated, onPathChange = onDayPath,
                primary = dayLeads,
            )
        }

        OptionsCard(route = route, onModeChange = onModeChange, onGhostChange = onGhostChange, initiallyOpen = initialOptionsOpen)

        Eyebrow(stringResource(R.string.indoor_app_route_tab_own))
        Card(modifier = Modifier.fillMaxWidth()) {
            Column {
                route.options.filter { it !is IndoorRouteOption.Catalog }.forEachIndexed { i, option ->
                    if (i > 0) HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    RouteOptionRow(
                        option = option,
                        selected = route.selected?.key == option.key,
                        onSelect = { onSelect(option) },
                    )
                }
                if (route.loadingOptions) {
                    Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                } else if (route.options.size <= 1) {
                    MutedText(
                        text = stringResource(R.string.indoor_app_route_no_saved),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
        }

        // Bekannte Strecken: eingeklappt, damit die Seite kurz bleibt — offen, sobald eine davon gewaehlt ist.
        val catalogSelected = route.selected is IndoorRouteOption.Catalog
        var catalogOpen by rememberSaveable { mutableStateOf(catalogSelected || initialCatalogOpen) }
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) { catalogOpen = !catalogOpen }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Eyebrow(stringResource(R.string.indoor_app_route_tab_catalog))
                    Text(
                        text = if (catalog.items.isEmpty()) {
                            stringResource(R.string.indoor_app_catalog_intro)
                        } else {
                            pluralStringResource(R.plurals.indoor_app_home_catalog_summary, catalog.items.size, catalog.items.size)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Icon(
                    if (catalogOpen) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (catalogOpen || catalogSelected) {
            MutedText(stringResource(R.string.indoor_app_catalog_intro))
            IndoorCatalogList(
                catalog = catalog,
                selectedKey = route.selected?.key,
                onSelect = onSelect,
                extras = catalogExtras,
                canRide = trainerIssue == null && pair.canContinue,
                onRide = onRide,
                onDownload = onCatalogDownload,
                onCancel = onCatalogCancel,
                onDiscard = onCatalogDiscard,
                initialFilterOpen = initialCatalogFilterOpen,
                initialDetailId = initialCatalogDetailId,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Trainer
// ---------------------------------------------------------------------------

@Composable
private fun TrainerCard(pair: IndoorPairUi, issue: IndoorTrainerIssue?, onOpenPair: () -> Unit) {
    val signals = LocalSignalColors.current
    val name = pair.trainerName.orEmpty()
    data class Look(val icon: ImageVector, val tint: Color, val title: String, val body: String, val action: String, val strong: Boolean)
    val look = when {
        pair.demoTrainer -> Look(
            Icons.AutoMirrored.Filled.DirectionsBike, MaterialTheme.colorScheme.primary,
            stringResource(R.string.indoor_app_home_trainer_demo_title),
            stringResource(R.string.indoor_app_home_trainer_demo_body),
            stringResource(R.string.indoor_app_home_trainer_change_action), strong = false,
        )
        issue == IndoorTrainerIssue.NOT_PAIRED -> Look(
            Icons.Filled.Warning, signals.warning,
            stringResource(R.string.indoor_app_home_trainer_missing_title),
            stringResource(R.string.indoor_app_home_trainer_missing_body),
            stringResource(R.string.indoor_app_home_trainer_pair_action), strong = true,
        )
        issue == IndoorTrainerIssue.BLUETOOTH_OFF -> Look(
            Icons.Filled.BluetoothDisabled, signals.warning,
            stringResource(R.string.indoor_app_home_trainer_bt_off_title),
            stringResource(R.string.indoor_app_home_trainer_bt_off_body, name),
            stringResource(R.string.indoor_app_home_trainer_fix_action), strong = true,
        )
        issue == IndoorTrainerIssue.NO_PERMISSION -> Look(
            Icons.Filled.BluetoothDisabled, signals.warning,
            stringResource(R.string.indoor_app_home_trainer_permission_title),
            stringResource(R.string.indoor_app_home_trainer_permission_body, name),
            stringResource(R.string.indoor_app_home_trainer_fix_action), strong = true,
        )
        else -> Look(
            Icons.Filled.CheckCircle, signals.good, name,
            stringResource(R.string.indoor_app_home_trainer_paired_body),
            stringResource(R.string.indoor_app_home_trainer_change_action), strong = false,
        )
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = look.action, onClick = onOpenPair),
    ) {
        Column(modifier = Modifier.padding(IndoorCardPadding), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Eyebrow(stringResource(R.string.indoor_app_home_trainer_eyebrow))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(look.icon, contentDescription = null, tint = look.tint, modifier = Modifier.size(28.dp))
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = look.title, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                    Text(
                        text = look.body,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!pair.demoTrainer && issue == null && pair.shifterName != null) {
                        Text(
                            text = stringResource(R.string.indoor_app_home_trainer_shifter, pair.shifterName),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                if (look.strong) {
                    FilledTonalButton(onClick = onOpenPair) { Text(look.action) }
                } else {
                    TextButton(onClick = onOpenPair) { Text(look.action) }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Was fehlt (unten ueber „Fahren")
// ---------------------------------------------------------------------------

@Composable
private fun ReadinessLines(
    trainerIssue: IndoorTrainerIssue?,
    readiness: IndoorRouteReadiness,
    showNoRoute: Boolean,
    onOpenPair: () -> Unit,
    onDownload: (String) -> Unit,
    onCancel: (String) -> Unit,
    onDiscard: (String) -> Unit,
) {
    val formats = LocalAppFormats.current
    if (trainerIssue != null) {
        StatusLine(
            text = stringResource(
                when (trainerIssue) {
                    IndoorTrainerIssue.NOT_PAIRED -> R.string.indoor_app_home_need_trainer
                    IndoorTrainerIssue.BLUETOOTH_OFF -> R.string.indoor_app_home_need_bluetooth
                    IndoorTrainerIssue.NO_PERMISSION -> R.string.indoor_app_home_need_permission
                },
            ),
            warning = true,
        ) {
            OutlinedButton(onClick = onOpenPair) {
                Text(
                    stringResource(
                        if (trainerIssue == IndoorTrainerIssue.NOT_PAIRED) {
                            R.string.indoor_app_home_trainer_pair_action
                        } else {
                            R.string.indoor_app_home_trainer_fix_action
                        },
                    ),
                )
            }
        }
    }
    when (readiness) {
        IndoorRouteReadiness.NoRoute -> if (showNoRoute) StatusLine(stringResource(R.string.indoor_app_home_need_route))
        is IndoorRouteReadiness.Ready -> {
            val update = readiness.update
            if (update == null) {
                if (trainerIssue == null) StatusLine(stringResource(R.string.indoor_app_home_ready, readiness.name), good = true)
            } else {
                StatusLine(stringResource(R.string.indoor_app_home_update, readiness.name), stacked = true) {
                    OutlinedButton(onClick = { onDownload(update.id) }) {
                        Text(
                            if (update.packageBytes > 0) {
                                stringResource(R.string.indoor_app_catalog_update, formats.indoorBytes(update.packageBytes))
                            } else {
                                stringResource(R.string.indoor_app_catalog_update_unknown)
                            },
                        )
                    }
                }
            }
        }
        is IndoorRouteReadiness.NeedsPreparing -> StatusLine(stringResource(R.string.indoor_app_home_ready_prepare, readiness.name))
        is IndoorRouteReadiness.Catalog -> CatalogLines(readiness, onDownload, onCancel, onDiscard)
    }
}

@Composable
private fun CatalogLines(
    readiness: IndoorRouteReadiness.Catalog,
    onDownload: (String) -> Unit,
    onCancel: (String) -> Unit,
    onDiscard: (String) -> Unit,
) {
    val formats = LocalAppFormats.current
    val item = readiness.item
    val name = readiness.name
    when (val s = item.state) {
        IndoorCatalogState.NotLoaded -> StatusLine(
            if (item.packageBytes > 0) {
                stringResource(R.string.indoor_app_home_need_download, name, formats.indoorBytes(item.packageBytes))
            } else {
                stringResource(R.string.indoor_app_home_need_download_unknown, name)
            },
        ) {
            FilledTonalButton(onClick = { onDownload(item.id) }) { Text(stringResource(R.string.indoor_app_home_download_action)) }
        }
        is IndoorCatalogState.Paused -> {
            StatusLine(stringResource(R.string.indoor_app_home_paused, name), stacked = true) {
                TextButton(onClick = { onDiscard(item.id) }) { Text(stringResource(R.string.indoor_app_catalog_discard)) }
                FilledTonalButton(onClick = { onDownload(item.id) }) { Text(stringResource(R.string.indoor_app_catalog_resume)) }
            }
            CatalogProgressLine(s.doneBytes, s.totalBytes, indeterminate = false)
        }
        is IndoorCatalogState.Downloading -> {
            StatusLine(stringResource(R.string.indoor_app_home_downloading, name)) {
                TextButton(onClick = { onCancel(item.id) }, enabled = !s.cancelling) {
                    Text(stringResource(if (s.cancelling) R.string.indoor_app_catalog_cancelling else R.string.indoor_app_action_cancel))
                }
            }
            CatalogProgressLine(s.doneBytes, s.totalBytes, indeterminate = s.totalBytes <= 0)
        }
        IndoorCatalogState.Installing -> {
            StatusLine(stringResource(R.string.indoor_app_home_installing, name))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        is IndoorCatalogState.Failed -> {
            NoticeBox(
                icon = when (s.reason) {
                    IndoorCatalogFailure.OFFLINE -> Icons.Filled.CloudOff
                    IndoorCatalogFailure.NOT_AVAILABLE -> Icons.Filled.Info
                    else -> Icons.Filled.Warning
                },
                color = if (s.reason == IndoorCatalogFailure.NOT_AVAILABLE) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                title = name,
                text = listOfNotNull(
                    catalogFailureText(s.reason),
                    if (s.installed) stringResource(R.string.indoor_app_catalog_failed_keeps_old) else null,
                ).joinToString(" "),
                action = {
                    TextButton(onClick = { onDownload(item.id) }) {
                        Text(stringResource(if (s.doneBytes > 0) R.string.indoor_app_catalog_resume else R.string.indoor_app_action_retry))
                    }
                },
            )
            if (s.reason != IndoorCatalogFailure.NOT_AVAILABLE) TechnicalDetail(s.detail)
        }
        // Bereit und neue Fassung behandelt indoorRouteReadiness als Ready.
        IndoorCatalogState.Ready, is IndoorCatalogState.UpdateAvailable -> Unit
    }
}

/**
 * Ein Satz ueber „Fahren", rechts optional der Knopf, der ihn behebt.
 *
 * @param stacked die Knoepfe stehen in einer eigenen Zeile unter dem Satz —
 *   fuer zwei Knoepfe oder einen langen („Aktualisieren (114 MB)"), neben denen
 *   dem Satz auf einem schmalen Geraet nur ein Streifen bliebe.
 */
@Composable
private fun StatusLine(
    text: String,
    warning: Boolean = false,
    good: Boolean = false,
    stacked: Boolean = false,
    actions: (@Composable () -> Unit)? = null,
) {
    if (stacked && actions != null) {
        Column(modifier = Modifier.fillMaxWidth()) {
            StatusLine(text = text, warning = warning, good = good)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) { actions() }
        }
        return
    }
    val signals = LocalSignalColors.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        when {
            warning -> Icon(Icons.Filled.Warning, contentDescription = null, tint = signals.warning, modifier = Modifier.size(20.dp))
            good -> Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = signals.good, modifier = Modifier.size(20.dp))
            else -> Unit
        }
        if (warning || good) Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (warning || good) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (actions != null) {
            Spacer(modifier = Modifier.width(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) { actions() }
        }
    }
}

// ---------------------------------------------------------------------------
// Optionen: Fahrweise und Mitfahrer
// ---------------------------------------------------------------------------

@Composable
private fun OptionsCard(
    route: IndoorRouteUi,
    onModeChange: (IndoorMode) -> Unit,
    onGhostChange: (IndoorGhostOption) -> Unit,
    initiallyOpen: Boolean,
) {
    var open by rememberSaveable { mutableStateOf(initiallyOpen) }
    val summary = listOf(
        modeLabel(route.mode),
        when (val g = route.ghost) {
            IndoorGhostOption.None -> stringResource(R.string.indoor_app_ghost_none_body)
            is IndoorGhostOption.PastRide -> stringResource(R.string.indoor_app_ghost_past)
            is IndoorGhostOption.ConstantPower -> stringResource(R.string.indoor_app_ghost_power_label, g.watts)
            is IndoorGhostOption.Challenge -> g.label
        },
    ).joinToString(" · ")
    Card(modifier = Modifier.fillMaxWidth().animateContentSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button) { open = !open }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Eyebrow(stringResource(R.string.indoor_app_home_section_options))
                Text(text = summary, style = MaterialTheme.typography.bodyMedium)
            }
            Icon(
                if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (open) {
            Column(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(stringResource(R.string.indoor_app_route_section_mode), style = MaterialTheme.typography.titleSmall)
                if (route.ergTitle != null) {
                    PillSegments(
                        options = listOf(modeLabel(IndoorMode.SIM), modeLabel(IndoorMode.ERG)),
                        selectedIndex = if (route.mode == IndoorMode.SIM) 0 else 1,
                        onSelect = { onModeChange(if (it == 0) IndoorMode.SIM else IndoorMode.ERG) },
                    )
                }
                when {
                    route.mode == IndoorMode.SIM -> MutedText(stringResource(R.string.indoor_app_mode_sim_body))
                    route.ergTitle != null -> MutedText(
                        if (route.ergMinutes != null) {
                            stringResource(R.string.indoor_app_mode_erg_body, route.ergTitle, route.ergMinutes)
                        } else {
                            stringResource(R.string.indoor_app_mode_erg_body_no_duration, route.ergTitle)
                        },
                    )
                }
                // Ohne geeignete Einheit gibt es keine Wahl — ein Satz sagt warum.
                if (route.ergTitle == null && !route.loadingOptions) {
                    MutedText(stringResource(R.string.indoor_app_mode_erg_unavailable))
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(stringResource(R.string.indoor_app_route_section_ghost), style = MaterialTheme.typography.titleSmall)
                GhostChooser(ui = route, onGhostChange = onGhostChange)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Eigene Strecken
// ---------------------------------------------------------------------------

@Composable
private fun RouteOptionRow(option: IndoorRouteOption, selected: Boolean, onSelect: () -> Unit) {
    val formats = LocalAppFormats.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            when (option) {
                IndoorRouteOption.TestWorld -> {
                    Text(
                        text = stringResource(R.string.indoor_app_route_testworld_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = stringResource(R.string.indoor_app_route_testworld_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                is IndoorRouteOption.Catalog -> Text(text = option.name, style = MaterialTheme.typography.titleSmall)
                is IndoorRouteOption.Generated -> Text(text = option.name, style = MaterialTheme.typography.titleSmall)
                is IndoorRouteOption.Saved -> {
                    Text(
                        text = option.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                    )
                    Text(
                        text = stringResource(
                            R.string.indoor_app_route_meta,
                            formats.km(option.distanceKm),
                            formats.indoorAscent(option.ascentM),
                        ) + " · " + stringResource(
                            if (option.planned) R.string.indoor_app_route_kind_planned else R.string.indoor_app_route_kind_ride,
                            formats.dateShort(option.createdAt),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            val ready = option is IndoorRouteOption.TestWorld || (option is IndoorRouteOption.Saved && option.hasWorld)
            if (ready) {
                TagPill(
                    text = stringResource(R.string.indoor_app_home_status_ready),
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(top = 4.dp),
                )
            } else {
                TagPill(text = stringResource(R.string.indoor_app_home_status_prepare), modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable
private fun GhostChooser(ui: IndoorRouteUi, onGhostChange: (IndoorGhostOption) -> Unit) {
    val ghost = ui.ghost
    // Ohne gefahrene Tour mit Zeitstempeln gibt es „Frühere Fahrt" nicht.
    val baseLabels = buildList {
        add(stringResource(R.string.indoor_app_ghost_none))
        if (ui.ghostPastRideAvailable) add(stringResource(R.string.indoor_app_ghost_past))
        add(stringResource(R.string.indoor_app_ghost_power))
    }
    val powerIndex = baseLabels.lastIndex
    // Eine gewählte Herausforderung bekommt einen eigenen Eintrag am Ende; gewählt wird sie im Segment „Herausforderungen".
    val challengeIndex = if (ghost is IndoorGhostOption.Challenge) baseLabels.size else -1
    val labels = if (challengeIndex >= 0) baseLabels + stringResource(R.string.spiel3_ghost_pill) else baseLabels
    val selectedIndex = when (ghost) {
        IndoorGhostOption.None -> 0
        is IndoorGhostOption.PastRide -> if (ui.ghostPastRideAvailable) 1 else 0
        is IndoorGhostOption.ConstantPower -> powerIndex
        is IndoorGhostOption.Challenge -> challengeIndex
    }
    PillSegments(
        options = labels,
        selectedIndex = selectedIndex,
        onSelect = { index ->
            val pastIndex = if (ui.ghostPastRideAvailable) 1 else -1
            val selectedRide = ui.selected as? IndoorRouteOption.Saved
            onGhostChange(
                when {
                    index == challengeIndex -> ghost
                    index == 0 -> IndoorGhostOption.None
                    index == pastIndex && selectedRide != null ->
                        IndoorGhostOption.PastRide(selectedRide.rideId, "")
                    else -> IndoorGhostOption.ConstantPower(
                        (ghost as? IndoorGhostOption.ConstantPower)?.watts ?: ui.defaultGhostWatts,
                    )
                },
            )
        },
    )
    when (ghost) {
        IndoorGhostOption.None -> MutedText(stringResource(R.string.indoor_app_ghost_none_body))
        is IndoorGhostOption.PastRide -> MutedText(stringResource(R.string.indoor_app_ghost_past_body))
        is IndoorGhostOption.Challenge -> MutedText(stringResource(R.string.spiel3_ghost_challenge_body))
        is IndoorGhostOption.ConstantPower -> {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                IconButton(
                    onClick = {
                        onGhostChange(IndoorGhostOption.ConstantPower((ghost.watts - GHOST_WATTS_STEP).coerceAtLeast(GHOST_WATTS_MIN)))
                    },
                    enabled = ghost.watts > GHOST_WATTS_MIN,
                ) {
                    Icon(Icons.Filled.Remove, contentDescription = stringResource(R.string.indoor_app_ghost_power_less_cd))
                }
                Text(
                    text = stringResource(R.string.indoor_app_ghost_power_value, ghost.watts),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                )
                IconButton(
                    onClick = {
                        onGhostChange(IndoorGhostOption.ConstantPower((ghost.watts + GHOST_WATTS_STEP).coerceAtMost(GHOST_WATTS_MAX)))
                    },
                    enabled = ghost.watts < GHOST_WATTS_MAX,
                ) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.indoor_app_ghost_power_more_cd))
                }
            }
            MutedText(stringResource(R.string.indoor_app_ghost_power_body))
        }
    }
}
