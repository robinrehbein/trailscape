package de.trailscape.app.ui.indoor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.trailscape.app.R
import de.trailscape.app.ui.theme.CockpitTheme
import de.trailscape.app.ui.theme.LocalCockpitColors

/**
 * # Der Raum in der Fahransicht
 *
 * Zwei Teile (docs/raum-protokoll.md, docs/ux/umbau.md):
 *  * [HudRoomChips] — unter den Kernwerten, wo sonst der eine Mitfahrer steht:
 *    die anderen im Raum mit Abstand in Metern („Anna · 12 m vor dir"), der
 *    nächste zuerst. Ausgegraut, wenn von jemandem seit über 3 s nichts kam.
 *    Dazu eine Zeile, wenn die eigene Verbindung hängt oder der Raum zu Ende ist.
 *  * [RoomStartCard] — vor dem gemeinsamen Start über der gehaltenen Fahrt:
 *    wie viele am Start stehen, der Countdown, für den Gastgeber der Startknopf,
 *    für alle „Raum verlassen und allein fahren".
 *
 * Gezeichnet wird nur, was das Protokoll hergibt: die Stelle auf der Strecke,
 * und die Leistung nur von Fahrern, die sie zeigen. Die anderen erscheinen
 * (noch) nicht als Figuren in der 3D-Ansicht.
 */
@Composable
internal fun HudRoomChips(room: RoomRideUi, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (room.closed) {
            HudChip { RoomChipText(stringResource(R.string.raum_hud_closed)) }
            return@Column
        }
        when (room.connection) {
            RoomConnectionUi.Online -> Unit
            is RoomConnectionUi.Reconnecting -> HudChip { RoomChipText(stringResource(R.string.raum_hud_reconnecting)) }
        }
        room.riders.forEach { rider -> HudRoomRiderChip(rider) }
    }
}

@Composable
private fun RoomChipText(text: String, muted: Boolean = false) {
    val cockpit = LocalCockpitColors.current
    Text(
        text = text,
        style = hudStyle(MaterialTheme.typography.labelLarge, if (muted) cockpit.inkMuted else cockpit.ink),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun HudRoomRiderChip(rider: RoomGapUi) {
    val cockpit = LocalCockpitColors.current
    val ahead = rider.gapM >= 0.0
    val meters = indoorGapMeters(rider.gapM)
    val watts = rider.powerW?.takeIf { !rider.stale }
    val text = if (watts != null) {
        stringResource(if (ahead) R.string.raum_hud_ahead_power else R.string.raum_hud_behind_power, rider.name, meters, watts)
    } else {
        stringResource(if (ahead) R.string.indoor_app_hud_ghost_ahead_m else R.string.indoor_app_hud_ghost_behind_m, rider.name, meters)
    }
    HudChip {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(if (rider.stale) cockpit.inkMuted else HudColors.ghost, RoundedCornerShape(3.dp)),
            )
            Spacer(modifier = Modifier.width(8.dp))
            RoomChipText(text, muted = rider.stale)
        }
    }
}

/**
 * Vor dem gemeinsamen Start: liegt über der Fahransicht, solange die Fahrt
 * gehalten wird ([RoomRideUi.waiting]).
 */
@Composable
internal fun RoomStartCard(
    room: RoomRideUi,
    onStart: () -> Unit,
    onLeave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CockpitTheme {
        val cockpit = LocalCockpitColors.current
        val online = room.connection is RoomConnectionUi.Online
        Surface(
            color = cockpit.panel,
            shape = MaterialTheme.shapes.large,
            border = BorderStroke(1.dp, cockpit.border),
            modifier = modifier.widthIn(max = 360.dp),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val countdown = room.countdownS
                if (countdown != null) {
                    Text(
                        text = stringResource(R.string.raum_wait_countdown, countdown),
                        style = hudStyle(MaterialTheme.typography.displaySmall, cockpit.ink),
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                } else {
                    Text(
                        text = stringResource(R.string.raum_wait_title),
                        style = hudStyle(MaterialTheme.typography.titleLarge, cockpit.ink),
                    )
                }
                Text(
                    text = stringResource(R.string.raum_wait_count, room.atStart, room.total),
                    style = hudStyle(MaterialTheme.typography.bodyLarge, cockpit.inkMuted),
                )
                when (val connection = room.connection) {
                    RoomConnectionUi.Online -> Unit
                    is RoomConnectionUi.Reconnecting -> Text(
                        text = roomReconnectingText(connection),
                        style = hudStyle(MaterialTheme.typography.bodyMedium, cockpit.ink),
                    )
                }
                if (countdown == null) {
                    if (room.host) {
                        Button(
                            onClick = onStart,
                            enabled = online,
                            shape = MaterialTheme.shapes.small,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = cockpit.action,
                                contentColor = cockpit.onAction,
                            ),
                        ) {
                            Text(stringResource(R.string.raum_start_action, IndoorRoomController.COUNTDOWN_S))
                        }
                    } else {
                        Text(
                            text = stringResource(R.string.raum_start_guest_hint),
                            style = hudStyle(MaterialTheme.typography.bodyMedium, cockpit.inkMuted),
                        )
                    }
                }
                TextButton(onClick = onLeave) {
                    Text(text = stringResource(R.string.raum_wait_leave), color = cockpit.ink)
                }
            }
        }
    }
}
