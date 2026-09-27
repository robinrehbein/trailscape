package de.trailscape.app.ui.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.trailscape.app.R
import de.trailscape.app.data.AppServices
import de.trailscape.app.strava.StravaAuthMessage
import de.trailscape.app.strava.StravaConnection
import de.trailscape.app.strava.StravaServices
import de.trailscape.app.ui.components.OneUiDialog
import kotlinx.coroutines.launch

/**
 * Strava — Inhalt der Seite „Strava" der Einstellungen (siehe
 * `MoreScreen.kt`). Die Seite gibt es nur in Builds mit Strava-Zugangsdaten
 * ([de.trailscape.app.strava.StravaConfig.available]).
 *
 * Aufbau nach Zustand:
 *  * **Nicht verbunden:** ein Satz, was die Funktion tut und dass ohne
 *    Verbindung nichts das Geraet verlaesst, darunter „Mit Strava verbinden".
 *  * **Beim Verbinden:** Fortschritt, solange der Code getauscht wird.
 *  * **Verbunden:** „Verbunden als …", der Schalter fuer den Auto-Upload (ab
 *    Werk aus) und „Trennen" mit Rueckfrage.
 *
 * Darunter steht immer, was hochgeladen wird — damit niemand erst nach dem
 * Verbinden erfaehrt, dass Spur und Puls mitgehen.
 */
@Composable
fun StravaCardContent() {
    val context = LocalContext.current
    val connection by StravaServices.connection.collectAsStateWithLifecycle()
    val autoUpload by StravaServices.autoUpload.collectAsStateWithLifecycle()
    val message by StravaServices.authMessage.collectAsStateWithLifecycle()
    val toolbarColor = MaterialTheme.colorScheme.surface.toArgb()

    StravaCardBody(
        connection = connection,
        autoUpload = autoUpload,
        message = message,
        onConnect = { StravaServices.beginAuth(context, toolbarColor) },
        // App-Scope: Das Trennen soll auch zu Ende laufen, wenn man die Seite
        // sofort verlaesst.
        onDisconnect = { AppServices.appScope.launch { StravaServices.disconnect() } },
        onAutoUploadChange = StravaServices::setAutoUpload,
    )
}

/**
 * Die zustandslose Karte — getrennt von [StravaCardContent], damit der
 * Screenshot-Test jeden Zustand ohne Strava und ohne Keystore zeigen kann.
 */
@Composable
internal fun StravaCardBody(
    connection: StravaConnection,
    autoUpload: Boolean,
    message: StravaAuthMessage?,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onAutoUploadChange: (Boolean) -> Unit,
) {
    var confirmDisconnect by rememberSaveable { mutableStateOf(false) }

    SettingsHint(stringResource(R.string.strava_intro))
    Spacer(modifier = Modifier.height(12.dp))

    when (connection) {
        StravaConnection.Disconnected -> Button(onClick = onConnect) {
            Text(stringResource(R.string.strava_connect))
        }
        StravaConnection.Connecting -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.height(48.dp),
        ) {
            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
            Text(stringResource(R.string.strava_connecting), style = MaterialTheme.typography.bodyMedium)
        }
        is StravaConnection.Connected -> {
            Text(
                text = connection.athleteFirstName?.let { stringResource(R.string.strava_connected_as, it) }
                    ?: stringResource(R.string.strava_connected_plain),
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(modifier = Modifier.height(4.dp))
            SettingsSwitchRow(
                title = stringResource(R.string.strava_auto_upload),
                subtitle = stringResource(R.string.strava_auto_upload_hint),
                checked = autoUpload,
                onCheckedChange = onAutoUploadChange,
            )
            Spacer(modifier = Modifier.height(8.dp))
            SettingsSecondaryButton(onClick = { confirmDisconnect = true }, destructive = true) {
                Text(stringResource(R.string.strava_disconnect))
            }
        }
    }

    message?.let {
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stravaAuthMessageText(it),
            style = MaterialTheme.typography.bodyMedium,
            color = if (it == StravaAuthMessage.DISCONNECTED) Color.Unspecified else MaterialTheme.colorScheme.error,
        )
    }

    Spacer(modifier = Modifier.height(12.dp))
    SettingsHint(stringResource(R.string.strava_privacy_hint))

    if (confirmDisconnect) {
        OneUiDialog(
            onDismissRequest = { confirmDisconnect = false },
            title = { Text(stringResource(R.string.strava_disconnect_title)) },
            text = { Text(stringResource(R.string.strava_disconnect_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDisconnect = false
                        onDisconnect()
                    },
                ) {
                    Text(stringResource(R.string.strava_disconnect), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDisconnect = false }) {
                    Text(stringResource(R.string.strava_cancel))
                }
            },
        )
    }
}

@Composable
private fun stravaAuthMessageText(message: StravaAuthMessage): String = stringResource(
    when (message) {
        StravaAuthMessage.DENIED -> R.string.strava_error_denied
        StravaAuthMessage.MISSING_SCOPE -> R.string.strava_error_missing_scope
        StravaAuthMessage.INVALID -> R.string.strava_error_invalid
        StravaAuthMessage.CONNECT_FAILED -> R.string.strava_error_connect_failed
        StravaAuthMessage.NO_BROWSER -> R.string.strava_error_no_browser
        StravaAuthMessage.KEYSTORE -> R.string.strava_error_keystore
        StravaAuthMessage.DISCONNECTED -> R.string.strava_disconnected
    },
)

/**
 * Statuszeile der Listenzeile „Strava" — bewusst hier statt in
 * `SettingsStatus.kt`, weil sie Ressourcen braucht und die Strava-Teile so
 * beisammen bleiben.
 */
@Composable
internal fun stravaStatusText(connection: StravaConnection, autoUpload: Boolean): String = stringResource(
    when {
        connection !is StravaConnection.Connected -> R.string.strava_status_off
        autoUpload -> R.string.strava_status_connected_auto
        else -> R.string.strava_status_connected
    },
)
