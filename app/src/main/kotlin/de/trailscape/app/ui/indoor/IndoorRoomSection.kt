package de.trailscape.app.ui.indoor

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.selection.toggleable
import de.trailscape.app.R
import de.trailscape.app.challenge.ChallengeAvailability
import de.trailscape.app.i18n.LocalAppFormats
import de.trailscape.app.ui.components.NoticeBox
import de.trailscape.app.ui.components.OneUiDialog
import de.trailscape.app.ui.components.TagPill
import de.trailscape.core.room.RoomRoute

/**
 * # „Raum" — Abschnitt in Indoor → Gemeinsam
 *
 * Der Raum für Gruppenfahrten (docs/raum-protokoll.md, docs/ux/umbau.md) als
 * eine Karte, je nach [IndoorRoomUi]:
 *  * **kein Raum**: „Raum anlegen" (nur mit eigenem Server, sonst der Satz,
 *    wohin man dafür muss) und „Raum beitreten" (Einladungslink einfügen);
 *  * **wird geöffnet**: Fortschritt und „Abbrechen";
 *  * **im Raum**: Strecke (mit demselben Weg wie bei einer Herausforderung:
 *    „Zum Start" bzw. „Strecke herunterladen"), gemeinsamer Start,
 *    Teilnehmer mit „Bereit", „Leistung zeigen", „Einladung teilen", „Raum
 *    verlassen" — und sichtbar, wenn die Verbindung hängt.
 *
 * Beide Dialoge zeigen vor dem Verbinden den Server und in einem Satz, was
 * andere im Raum sehen ([RoomPrivacyNote]). Verbunden wird erst mit dem
 * Bestätigen.
 *
 * @param createRoute die unter „Strecken" gewählte Strecke, falls sie sich für
 *   einen Raum eignet; `null` = keine oder nur auf diesem Gerät bekannt.
 * @param pendingInvite ein von außen geöffneter Einladungslink: öffnet „Raum
 *   beitreten" mit dem Link; [onInviteConsumed] quittiert ihn.
 * @param onJoin `false` = der Text ist kein Einladungslink (der Dialog bleibt offen).
 * @param onRide „Zum Start" mit dem Anzeigenamen der Strecke.
 * @param onChooseRoute wechselt auf „Strecken" (Strecke wählen).
 */
@Composable
fun IndoorRoomSection(
    ui: IndoorRoomUi,
    server: RoomServerUi,
    createRoute: RoomRoute?,
    savedName: String,
    savedSharePower: Boolean,
    pendingInvite: String?,
    onInviteConsumed: () -> Unit,
    onCreate: (name: String, sharePower: Boolean) -> Unit,
    onJoin: (link: String, name: String, sharePower: Boolean) -> Boolean,
    onLeave: () -> Unit,
    onEnd: () -> Unit,
    onStartCountdown: () -> Unit,
    onSharePower: (Boolean) -> Unit,
    onRide: (routeName: String) -> Unit,
    onDownload: (catalogId: String) -> Unit,
    onDismissNotice: () -> Unit,
    onOpenSettings: () -> Unit,
    onChooseRoute: () -> Unit,
) {
    var showCreate by rememberSaveable { mutableStateOf(false) }
    var showJoin by rememberSaveable { mutableStateOf(false) }
    var joinLink by rememberSaveable { mutableStateOf("") }
    var askEnd by rememberSaveable { mutableStateOf(false) }

    // Einladungslink von außen: nur vormerken und den Dialog öffnen — verbunden
    // wird erst mit „Beitreten". In einem offenen Raum wird er nicht angewendet.
    LaunchedEffect(pendingInvite) {
        val link = pendingInvite ?: return@LaunchedEffect
        if (ui is IndoorRoomUi.Closed) {
            joinLink = link
            showCreate = false
            showJoin = true
        }
        onInviteConsumed()
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        when (ui) {
            is IndoorRoomUi.Closed -> RoomClosedContent(
                notice = ui.notice,
                server = server,
                onCreate = { showCreate = true },
                onJoin = {
                    joinLink = ""
                    showJoin = true
                },
                onDismissNotice = onDismissNotice,
                onOpenSettings = onOpenSettings,
            )
            is IndoorRoomUi.Opening -> RoomOpeningContent(ui = ui, onCancel = onLeave)
            is IndoorRoomUi.Open -> RoomOpenContent(
                ui = ui,
                onRide = onRide,
                onDownload = onDownload,
                onStartCountdown = onStartCountdown,
                onSharePower = onSharePower,
                onLeave = onLeave,
                onEnd = { askEnd = true },
            )
        }
    }

    if (showCreate && ui is IndoorRoomUi.Closed) {
        var name by rememberSaveable { mutableStateOf(savedName) }
        var sharePower by rememberSaveable { mutableStateOf(savedSharePower) }
        OneUiDialog(
            onDismissRequest = { showCreate = false },
            title = { Text(stringResource(R.string.raum_create)) },
            text = {
                RoomDialogScroll {
                    RoomCreateForm(
                        serverHost = server.host.orEmpty(),
                        route = createRoute,
                        name = name,
                        onNameChange = { name = it },
                        sharePower = sharePower,
                        onSharePowerChange = { sharePower = it },
                        onChooseRoute = {
                            showCreate = false
                            onChooseRoute()
                        },
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreate = false }) { Text(stringResource(R.string.common_action_cancel)) }
            },
            confirmButton = {
                TextButton(
                    enabled = createRoute != null && name.isNotBlank(),
                    onClick = {
                        showCreate = false
                        onCreate(name, sharePower)
                    },
                ) { Text(stringResource(R.string.raum_create)) }
            },
        )
    }

    if (showJoin && ui is IndoorRoomUi.Closed) {
        var name by rememberSaveable { mutableStateOf(savedName) }
        var sharePower by rememberSaveable { mutableStateOf(savedSharePower) }
        val invite = IndoorRoomController.parseInvite(joinLink)
        OneUiDialog(
            onDismissRequest = { showJoin = false },
            title = { Text(stringResource(R.string.raum_join)) },
            text = {
                RoomDialogScroll {
                    RoomJoinForm(
                        link = joinLink,
                        onLinkChange = { joinLink = it },
                        serverHost = invite?.let { IndoorRoomController.hostOf(it.serverUrl) },
                        name = name,
                        onNameChange = { name = it },
                        sharePower = sharePower,
                        onSharePowerChange = { sharePower = it },
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showJoin = false }) { Text(stringResource(R.string.common_action_cancel)) }
            },
            confirmButton = {
                TextButton(
                    enabled = invite != null && name.isNotBlank(),
                    onClick = { if (onJoin(joinLink, name, sharePower)) showJoin = false },
                ) { Text(stringResource(R.string.raum_join_confirm)) }
            },
        )
    }

    if (askEnd && ui is IndoorRoomUi.Open) {
        OneUiDialog(
            onDismissRequest = { askEnd = false },
            title = { Text(stringResource(R.string.raum_end_confirm_title)) },
            text = { Text(stringResource(R.string.raum_end_confirm_body)) },
            dismissButton = {
                TextButton(onClick = { askEnd = false }) { Text(stringResource(R.string.common_action_cancel)) }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        askEnd = false
                        onEnd()
                    },
                ) { Text(stringResource(R.string.raum_end_confirm_action)) }
            },
        )
    }
}

// ---------------------------------------------------------------------------
// Kein Raum offen
// ---------------------------------------------------------------------------

@Composable
private fun RoomClosedContent(
    notice: RoomNotice?,
    server: RoomServerUi,
    onCreate: () -> Unit,
    onJoin: () -> Unit,
    onDismissNotice: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (notice != null) {
            NoticeBox(
                icon = Icons.Filled.Info,
                color = MaterialTheme.colorScheme.primary,
                text = roomNoticeText(notice),
                action = {
                    TextButton(onClick = onDismissNotice) { Text(stringResource(R.string.common_action_ok)) }
                },
            )
        }
        MutedText(stringResource(R.string.raum_intro))
        Button(onClick = onCreate, enabled = server.configured, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.raum_create))
        }
        if (!server.configured) {
            MutedText(stringResource(R.string.raum_no_server))
            TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.raum_open_settings)) }
        }
        OutlinedButton(onClick = onJoin, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.raum_join))
        }
    }
}

/** Der Satz zu einem beendeten oder gescheiterten Raum. */
@Composable
internal fun roomNoticeText(notice: RoomNotice): String = stringResource(
    when (notice) {
        RoomNotice.ENDED_BY_HOST -> R.string.raum_notice_ended_by_host
        RoomNotice.ENDED_EXPIRED -> R.string.raum_notice_ended_expired
        RoomNotice.ENDED_IDLE -> R.string.raum_notice_ended_idle
        RoomNotice.ENDED_SERVER -> R.string.raum_notice_ended_server
        RoomNotice.DENIED -> R.string.raum_notice_denied
        RoomNotice.FULL -> R.string.raum_notice_full
        RoomNotice.VERSION -> R.string.raum_notice_version
        RoomNotice.REPLACED -> R.string.raum_notice_replaced
        RoomNotice.BLOCKED -> R.string.raum_notice_blocked
        RoomNotice.PROTOCOL -> R.string.raum_notice_protocol
        RoomNotice.GAVE_UP -> R.string.raum_notice_gave_up
        RoomNotice.CREATE_UNAUTHORIZED -> R.string.raum_notice_create_unauthorized
        RoomNotice.CREATE_DISABLED -> R.string.raum_notice_create_disabled
        RoomNotice.CREATE_SERVER_FULL -> R.string.raum_notice_create_server_full
        RoomNotice.CREATE_NETWORK -> R.string.raum_notice_create_network
        RoomNotice.CREATE_FAILED -> R.string.raum_notice_create_failed
    },
)

// ---------------------------------------------------------------------------
// Dialoginhalte (eigene Composables, damit Screenshot-Tests sie zeichnen können)
// ---------------------------------------------------------------------------

/** In einem Satz, was andere im Raum sehen — vor jedem Anlegen und Beitreten. */
@Composable
internal fun RoomPrivacyNote(modifier: Modifier = Modifier) {
    NoticeBox(
        icon = Icons.Filled.Info,
        color = MaterialTheme.colorScheme.primary,
        text = stringResource(R.string.raum_privacy),
        modifier = modifier,
    )
}

@Composable
private fun RoomNameAndPower(
    name: String,
    onNameChange: (String) -> Unit,
    sharePower: Boolean,
    onSharePowerChange: (Boolean) -> Unit,
) {
    OutlinedTextField(
        value = name,
        onValueChange = { onNameChange(it.take(IndoorRoomController.NAME_MAX_CHARS)) },
        label = { Text(stringResource(R.string.raum_name_label)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    SharePowerRow(checked = sharePower, onCheckedChange = onSharePowerChange)
}

@Composable
private fun SharePowerRow(checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.raum_share_power), style = MaterialTheme.typography.bodyLarge)
            MutedText(stringResource(R.string.raum_share_power_hint))
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/**
 * Die eine Scroll-Ebene der beiden Dialoge: Der Inhalt (Felder, Schalter, der
 * Satz zum Datenschutz) ist höher als ein kleiner Bildschirm mit offener
 * Tastatur. Die Höhe ist begrenzt, damit die Knöpfe darunter sichtbar bleiben
 * und der scrollende Teil nie eine unbegrenzte Höhe gemessen bekommt. Die
 * Inhalte selbst ([RoomCreateForm], [RoomJoinForm]) scrollen nicht.
 */
@Composable
private fun RoomDialogScroll(content: @Composable () -> Unit) {
    Column(modifier = Modifier.heightIn(max = ROOM_DIALOG_MAX_HEIGHT).verticalScroll(rememberScrollState())) {
        content()
    }
}

private val ROOM_DIALOG_MAX_HEIGHT = 420.dp

/** Inhalt des Dialogs „Raum anlegen". */
@Composable
internal fun RoomCreateForm(
    serverHost: String,
    route: RoomRoute?,
    name: String,
    onNameChange: (String) -> Unit,
    sharePower: Boolean,
    onSharePowerChange: (Boolean) -> Unit,
    onChooseRoute: () -> Unit,
) {
    val formats = LocalAppFormats.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (route == null) {
            NoticeBox(
                icon = Icons.Filled.Warning,
                color = MaterialTheme.colorScheme.error,
                text = stringResource(R.string.raum_create_no_route),
                action = {
                    TextButton(onClick = onChooseRoute) { Text(stringResource(R.string.raum_create_to_routes)) }
                },
            )
        } else {
            LabelValueRow(
                label = stringResource(R.string.raum_create_route_label),
                value = stringResource(
                    R.string.raum_route_value,
                    route.name ?: stringResource(R.string.indoor_app_route_testworld_title),
                    formats.km(route.lengthM / 1000.0),
                ),
            )
        }
        MutedText(stringResource(R.string.raum_server_line, serverHost))
        RoomNameAndPower(name, onNameChange, sharePower, onSharePowerChange)
        RoomPrivacyNote()
    }
}

/**
 * Inhalt des Dialogs „Raum beitreten".
 *
 * @param serverHost der Server aus dem Link, `null` = der Text ist (noch) kein Einladungslink.
 */
@Composable
internal fun RoomJoinForm(
    link: String,
    onLinkChange: (String) -> Unit,
    serverHost: String?,
    name: String,
    onNameChange: (String) -> Unit,
    sharePower: Boolean,
    onSharePowerChange: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = link,
            onValueChange = { onLinkChange(it.take(INVITE_INPUT_MAX_CHARS)) },
            label = { Text(stringResource(R.string.raum_join_link_label)) },
            supportingText = {
                Text(
                    when {
                        serverHost != null -> stringResource(R.string.raum_server_line, serverHost)
                        link.isBlank() -> stringResource(R.string.raum_join_link_hint)
                        else -> stringResource(R.string.raum_join_link_invalid)
                    },
                )
            },
            isError = serverHost == null && link.isNotBlank(),
            maxLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )
        RoomNameAndPower(name, onNameChange, sharePower, onSharePowerChange)
        RoomPrivacyNote()
    }
}

/** Eingefügter Text wird höchstens so lang übernommen (ein Link hat rund 150 Zeichen). */
private const val INVITE_INPUT_MAX_CHARS = 600

// ---------------------------------------------------------------------------
// Raum wird geöffnet
// ---------------------------------------------------------------------------

@Composable
private fun RoomOpeningContent(ui: IndoorRoomUi.Opening, onCancel: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (ui.creating) {
                        stringResource(R.string.raum_opening_create)
                    } else {
                        stringResource(R.string.raum_opening_connect, ui.serverHost)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (ui.attempt > 1) MutedText(stringResource(R.string.raum_opening_attempt, ui.attempt))
            }
        }
        TextButton(onClick = onCancel) { Text(stringResource(R.string.common_action_cancel)) }
    }
}

// ---------------------------------------------------------------------------
// Im Raum
// ---------------------------------------------------------------------------

@Composable
private fun RoomOpenContent(
    ui: IndoorRoomUi.Open,
    onRide: (String) -> Unit,
    onDownload: (String) -> Unit,
    onStartCountdown: () -> Unit,
    onSharePower: (Boolean) -> Unit,
    onLeave: () -> Unit,
    onEnd: () -> Unit,
) {
    val context = LocalContext.current
    val formats = LocalAppFormats.current
    val online = ui.connection is RoomConnectionUi.Online
    val routeName = ui.route.name?.pick(formats.language.tag)
        ?: ui.route.fallbackName
        ?: stringResource(R.string.indoor_app_route_testworld_title)
    val chooserTitle = stringResource(R.string.raum_share_invite)
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.raum_open_title, ui.serverHost),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            if (ui.host) TagPill(text = stringResource(R.string.raum_host_tag))
        }
        when (val connection = ui.connection) {
            RoomConnectionUi.Online -> Unit
            is RoomConnectionUi.Reconnecting -> NoticeBox(
                icon = Icons.Filled.Warning,
                color = MaterialTheme.colorScheme.error,
                text = roomReconnectingText(connection),
            )
        }

        // Strecke — derselbe Weg wie bei einer Herausforderung.
        LabelValueRow(
            label = stringResource(R.string.raum_create_route_label),
            value = stringResource(R.string.raum_route_value, routeName, formats.km(ui.route.lengthKm)),
        )
        when (ui.route.availability) {
            ChallengeAvailability.READY -> if (!ui.selfAtStart) {
                Button(onClick = { onRide(routeName) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.raum_to_start))
                }
            }
            ChallengeAvailability.DOWNLOAD -> OutlinedButton(
                onClick = { ui.route.catalogId?.let(onDownload) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.spiel3_row_download)) }
            ChallengeAvailability.MISMATCH -> MutedText(stringResource(R.string.raum_route_mismatch))
            ChallengeAvailability.UNKNOWN -> MutedText(stringResource(R.string.raum_route_unknown))
        }

        // Gemeinsamer Start
        when {
            ui.countdownS != null -> Text(
                text = stringResource(R.string.raum_countdown, ui.countdownS),
                style = MaterialTheme.typography.titleMedium,
            )
            ui.started -> MutedText(stringResource(R.string.raum_started))
            ui.host -> {
                OutlinedButton(onClick = onStartCountdown, enabled = online, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.raum_start_action, IndoorRoomController.COUNTDOWN_S))
                }
                MutedText(stringResource(R.string.raum_start_host_hint))
            }
            else -> MutedText(stringResource(R.string.raum_start_guest_hint))
        }

        HorizontalDivider()
        Text(
            text = stringResource(R.string.raum_riders_title, ui.riders.size, ui.maxRiders),
            style = MaterialTheme.typography.titleSmall,
        )
        ui.riders.forEach { rider -> RoomRiderRow(rider) }
        MutedText(stringResource(R.string.raum_ready_explain))

        HorizontalDivider()
        SharePowerRow(checked = ui.sharePower, onCheckedChange = onSharePower)
        OutlinedButton(onClick = { shareInvite(context, ui.inviteLink, chooserTitle) }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Share, contentDescription = null)
            Text(text = stringResource(R.string.raum_share_invite), modifier = Modifier.padding(start = 8.dp))
        }
        MutedText(stringResource(R.string.raum_share_invite_hint))
        // FlowRow: „Raum für alle beenden" rutscht auf schmalen Geraeten in die naechste Zeile.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onLeave) { Text(stringResource(R.string.raum_leave)) }
            if (ui.host) {
                TextButton(onClick = onEnd, enabled = online) { Text(stringResource(R.string.raum_end)) }
            }
        }
    }
}

@Composable
internal fun roomReconnectingText(connection: RoomConnectionUi.Reconnecting): String {
    val inS = connection.retryInS
    return if (inS != null && inS > 0) {
        stringResource(R.string.raum_reconnecting_in, inS)
    } else {
        stringResource(R.string.raum_reconnecting_now)
    }
}

@Composable
private fun RoomRiderRow(rider: RoomRiderUi) {
    val formats = LocalAppFormats.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (rider.self) stringResource(R.string.raum_you, rider.name) else rider.name,
                style = MaterialTheme.typography.bodyLarge,
            )
            val distKm = rider.distKm
            MutedText(
                when {
                    rider.riding && distKm != null -> stringResource(R.string.raum_rider_riding, formats.km(distKm))
                    rider.atStart -> stringResource(R.string.raum_rider_ready)
                    else -> stringResource(R.string.raum_rider_waiting)
                },
            )
        }
        if (rider.host) TagPill(text = stringResource(R.string.raum_host_tag))
    }
}

/** Öffnet das Teilen-Blatt des Systems mit dem Einladungslink als Text. Die App selbst verschickt nichts. */
private fun shareInvite(context: Context, link: String, chooserTitle: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link)
    runCatching {
        context.startActivity(Intent.createChooser(send, chooserTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
