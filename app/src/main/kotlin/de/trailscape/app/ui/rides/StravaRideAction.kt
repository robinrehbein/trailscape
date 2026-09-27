package de.trailscape.app.ui.rides

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.trailscape.app.R
import de.trailscape.app.strava.StravaConfig
import de.trailscape.app.strava.StravaConnection
import de.trailscape.app.strava.StravaServices
import de.trailscape.app.ui.components.NeutralButton
import de.trailscape.app.ui.components.NoticeBox
import de.trailscape.app.ui.theme.LocalSignalColors
import de.trailscape.core.Ride
import de.trailscape.core.StravaError
import de.trailscape.core.StravaUploadRecord
import de.trailscape.core.StravaUploadState
import de.trailscape.core.isStravaUploadable
import de.trailscape.core.stravaActivityUrl

/**
 * Was die Strava-Zeile der Tour-Detailansicht zeigt — abgeleitet aus dem
 * Upload-Vermerk der Tour ([stravaRideActionState]).
 */
internal sealed interface StravaRideActionState {
    /** Noch nie hochgeladen: „Zu Strava hochladen". */
    data object NotUploaded : StravaRideActionState

    /** Wird gerade hochgeladen (oder wartet auf Netz). */
    data object Uploading : StravaRideActionState

    /** „Wird hochgeladen" seit ueber sechs Stunden — keine Arbeit mehr dahinter, „Erneut versuchen". */
    data object Stale : StravaRideActionState

    /** @param url `null` nur, wenn der Vermerk keine Aktivitaets-ID traegt (sollte nicht vorkommen). */
    data class Uploaded(val url: String?) : StravaRideActionState

    /** Strava kannte die Fahrt schon; [url] nur, wenn Strava die Aktivitaet genannt hat. */
    data class Duplicate(val url: String?) : StravaRideActionState

    /** @param error `null` = unbekannter Fehler. */
    data class Failed(val error: StravaError?, val detail: String?) : StravaRideActionState
}

/** Reine Zustandsableitung — getestet in `StravaRideActionStateTest`. */
internal fun stravaRideActionState(record: StravaUploadRecord?, nowMs: Long): StravaRideActionState = when {
    record == null -> StravaRideActionState.NotUploaded
    record.state == StravaUploadState.UPLOADING ->
        if (record.isStale(nowMs)) StravaRideActionState.Stale else StravaRideActionState.Uploading
    record.state == StravaUploadState.DONE ->
        StravaRideActionState.Uploaded(record.activityId?.let(::stravaActivityUrl))
    record.state == StravaUploadState.DUPLICATE ->
        StravaRideActionState.Duplicate(record.activityId?.let(::stravaActivityUrl))
    else -> StravaRideActionState.Failed(record.error, record.detail)
}

/**
 * Die Strava-Zeile unter den Aktionskacheln der Tour-Detailansicht.
 *
 * Eine eigene Zeile statt einer fuenften Kachel: Die Reihe
 * (`ActionTileRow`) ist fuer vier Kacheln ausgelegt und bricht sonst um.
 *
 * Sichtbar nur, wenn der Build Strava kann, die Tour sich eignet (gefahren,
 * mit Zeitstempeln) und ein Konto verbunden ist — ohne Verbindung sieht die
 * Tour aus wie immer. Einzige Ausnahme: Eine schon hochgeladene Tour zeigt
 * „Auf Strava ansehen" auch nach dem Trennen weiter.
 */
@Composable
internal fun StravaRideAction(ride: Ride, modifier: Modifier = Modifier) {
    if (!StravaConfig.available || !isStravaUploadable(ride)) return
    val connection by StravaServices.connection.collectAsStateWithLifecycle()
    val records by StravaServices.records.collectAsStateWithLifecycle()
    val state = stravaRideActionState(records[ride.id], System.currentTimeMillis())
    val connected = connection is StravaConnection.Connected
    val viewable = (state as? StravaRideActionState.Uploaded)?.url != null ||
        (state as? StravaRideActionState.Duplicate)?.url != null
    if (!connected && !viewable) return

    val uriHandler = LocalUriHandler.current
    StravaRideActionContent(
        state = state,
        onUpload = { StravaServices.requestUpload(ride.id, replace = true) },
        onOpen = { url -> runCatching { uriHandler.openUri(url) } },
        onRetry = { StravaServices.requestUpload(ride.id, replace = true) },
        canUpload = connected,
        modifier = modifier,
    )
}

/**
 * Die zustandslose Zeile — fuer den Screenshot-Test ohne Strava.
 *
 * @param canUpload `false` nach dem Trennen: Dann gibt es nur noch „Auf
 *   Strava ansehen", keine Aktion, die hochladen wuerde.
 */
@Composable
internal fun StravaRideActionContent(
    state: StravaRideActionState,
    onUpload: () -> Unit,
    onOpen: (String) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    canUpload: Boolean = true,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        when (state) {
            StravaRideActionState.NotUploaded -> {
                val description = stringResource(R.string.strava_upload_action_cd)
                NeutralButton(
                    onClick = onUpload,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = description },
                ) {
                    Icon(
                        Icons.Filled.CloudUpload,
                        contentDescription = null,
                        modifier = Modifier.size(ButtonDefaults.IconSize),
                    )
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    Text(stringResource(R.string.strava_upload_action))
                }
            }
            StravaRideActionState.Uploading, StravaRideActionState.Stale -> StatusLine(
                leading = { CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp)) },
                text = stringResource(R.string.strava_uploading),
                action = if (state == StravaRideActionState.Stale && canUpload) {
                    { TextButton(onClick = onRetry) { Text(stringResource(R.string.strava_retry)) } }
                } else {
                    null
                },
            )
            is StravaRideActionState.Uploaded -> StatusLine(
                leading = { DoneIcon() },
                text = stringResource(R.string.strava_uploaded),
                action = state.url?.let { url -> { ViewButton { onOpen(url) } } },
            )
            is StravaRideActionState.Duplicate -> StatusLine(
                leading = { DoneIcon() },
                text = stringResource(R.string.strava_duplicate),
                action = state.url?.let { url -> { ViewButton { onOpen(url) } } },
            )
            is StravaRideActionState.Failed -> NoticeBox(
                icon = Icons.Filled.ErrorOutline,
                color = MaterialTheme.colorScheme.error,
                title = stringResource(R.string.strava_failed_title),
                text = stringResource(stravaErrorText(state.error)),
                action = if (canUpload) {
                    { TextButton(onClick = onRetry) { Text(stringResource(R.string.strava_retry)) } }
                } else {
                    null
                },
            )
        }
    }
}

/**
 * Eine Statuszeile: Symbol, Satz, darunter rechtsbuendig die Aktion — so
 * bricht auch bei grosser Schrift nichts ab.
 */
@Composable
private fun StatusLine(
    leading: @Composable () -> Unit,
    text: String,
    action: (@Composable () -> Unit)?,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            leading()
            Text(text = text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        }
        action?.invoke()
    }
}

@Composable
private fun DoneIcon() {
    Icon(
        Icons.Filled.CheckCircle,
        contentDescription = null,
        tint = LocalSignalColors.current.good,
        modifier = Modifier.size(20.dp),
    )
}

@Composable
private fun ViewButton(onClick: () -> Unit) {
    NeutralButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Icon(
            Icons.AutoMirrored.Filled.OpenInNew,
            contentDescription = null,
            modifier = Modifier.size(ButtonDefaults.IconSize),
        )
        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
        Text(stringResource(R.string.strava_view))
    }
}

/** Satz zu einer Fehlerklasse; `null` = unbekannt. */
internal fun stravaErrorText(error: StravaError?): Int = when (error) {
    StravaError.NETWORK, StravaError.SERVER -> R.string.strava_failed_network
    StravaError.RATE_LIMITED -> R.string.strava_failed_rate_limited
    StravaError.UNAUTHORIZED -> R.string.strava_failed_auth
    StravaError.FILE, StravaError.NOT_UPLOADABLE -> R.string.strava_failed_file
    null -> R.string.strava_failed_generic
}
