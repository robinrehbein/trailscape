package de.trailscape.app.ui.more

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import de.trailscape.app.R
import de.trailscape.app.data.AppServices
import de.trailscape.app.i18n.UiText
import de.trailscape.app.i18n.asString
import de.trailscape.app.ui.components.OneUiTextField
import de.trailscape.app.ui.AppViewModel
import de.trailscape.app.ui.withCause
import de.trailscape.core.SyncConfig
import de.trailscape.core.SyncResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Selfhost-Sync — Inhalt der Seite „Sync mit eigenem Server" der
 * Einstellungen (siehe `MoreScreen.kt`). Urspruenglich ein Port der
 * `Sync (Selfhost)`-Karte in `lib/screens/more_screen.dart` (`_runSync`,
 * `_loadSyncConfig`).
 *
 * ## Speichert beim Tippen
 * Server-URL und Token gehen bei jeder Aenderung an
 * [AppViewModel.setSyncConfig] — sobald beide ausgefuellt sind; sind beide
 * leer, wird die Konfiguration entfernt (Listenzeile: „Aus"). Frueher wurde
 * erst beim Abgleich gespeichert, und wer die Seite ohne Abgleich verliess,
 * verlor die Eingabe. Der Knopf gleicht jetzt nur noch ab.
 *
 * **Abweichung/Workaround.** [AppViewModel.setSyncConfig] persistiert
 * fire-and-forget auf [kotlinx.coroutines.Dispatchers.IO] (siehe dessen
 * KDoc). Damit [AppViewModel.syncNow] garantiert die aktuelle Konfiguration
 * liest, schreibt der Abgleich-Knopf sie vorher noch einmal selbst und
 * *abgewartet* ueber [de.trailscape.core.setSyncConfig] auf denselben, von
 * [AppServices] bereitgestellten [de.trailscape.core.KeyValueStore] —
 * derselbe Wert, doppelt, aber unschaedlich geschrieben.
 */
@Composable
fun SyncCardContent(appViewModel: AppViewModel) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val syncConfig by appViewModel.syncConfig.collectAsStateWithLifecycle()

    var urlText by remember { mutableStateOf("") }
    var tokenText by remember { mutableStateOf("") }
    var syncing by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf<UiText?>(null) }
    var appliedConfig by remember { mutableStateOf<SyncConfig?>(null) }

    LaunchedEffect(syncConfig) {
        if (syncConfig == appliedConfig) return@LaunchedEffect
        appliedConfig = syncConfig
        urlText = syncConfig?.url ?: urlText
        tokenText = syncConfig?.token ?: tokenText
    }

    // Sofort speichern (siehe KDoc). `appliedConfig` wird vorher gesetzt,
    // damit der zurueckkommende Wert die Felder nicht (getrimmt) ueberschreibt.
    fun persist(url: String, token: String) {
        val config = if (url.isBlank() && token.isBlank()) {
            null
        } else if (url.isBlank() || token.isBlank()) {
            // Halb ausgefuellt: nichts anfassen, bis beides dasteht.
            return
        } else {
            SyncConfig(url = url.trim(), token = token.trim())
        }
        if (config == syncConfig) return
        appliedConfig = config
        appViewModel.setSyncConfig(config)
    }

    SettingsHint(stringResource(R.string.more_sync_hint))
    Spacer(modifier = Modifier.height(12.dp))

    OneUiTextField(
        label = stringResource(R.string.more_sync_url_label),
        value = urlText,
        onValueChange = {
            urlText = it
            persist(urlText, tokenText)
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(modifier = Modifier.height(8.dp))
    OneUiTextField(
        label = stringResource(R.string.more_sync_token_label),
        value = tokenText,
        onValueChange = {
            tokenText = it
            persist(urlText, tokenText)
        },
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(modifier = Modifier.height(12.dp))

    Button(
        onClick = {
            val url = urlText.trim()
            val token = tokenText.trim()
            if (url.isEmpty() || token.isEmpty()) {
                statusText = UiText.Res(R.string.more_sync_missing_error)
                return@Button
            }
            scope.launch {
                syncing = true
                statusText = UiText.Res(R.string.more_sync_running_status)
                try {
                    val config = SyncConfig(url = url, token = token)
                    // Siehe Klassen-KDoc: bewusst selbst geschrieben und
                    // abgewartet, damit syncNow() garantiert die neue
                    // Konfiguration sieht.
                    withContext(Dispatchers.IO) {
                        de.trailscape.core.setSyncConfig(AppServices.keyValueStore, config)
                    }
                    appViewModel.setSyncConfig(config)
                    val result = appViewModel.syncNow()
                    statusText = syncResultText(result)
                } catch (e: Exception) {
                    // Vorher gewann die technische Meldung („Failed to
                    // connect to …"); der eigene Satz kam nur zum
                    // Vorschein, wenn die Ausnahme gar keinen Text trug.
                    statusText = UiText.Plain(withCause(context.getString(R.string.more_sync_failed_error), e))
                } finally {
                    syncing = false
                }
            }
        },
        enabled = !syncing,
    ) {
        if (syncing) {
            CircularProgressIndicator(
                strokeWidth = 2.dp,
                modifier = Modifier.size(18.dp),
                color = MaterialTheme.colorScheme.onPrimary,
            )
        } else {
            // Nicht „Jetzt synchronisieren": So hiess auch der Knopf der
            // Health-Connect-Karte, der Touren aus Health Connect holt.
            // Hier geht es in beide Richtungen und gegen einen eigenen
            // Server — das sagt die Beschriftung jetzt.
            Text(stringResource(R.string.more_sync_action))
        }
    }

    statusText?.let { status ->
        Spacer(modifier = Modifier.height(12.dp))
        Text(text = status.asString(), style = MaterialTheme.typography.bodyMedium)
    }

    Spacer(modifier = Modifier.height(12.dp))
    SettingsHint(stringResource(R.string.more_sync_guide_hint))
}

/**
 * Der kompakte Ergebnissatz eines Abgleichs: „3 hochgeladen, 2 geladen
 * (davon 1 aktualisiert), 1 gelöscht, 42 Touren". Loeschungen und
 * Aktualisierungen stehen nur da, wenn es welche gab — der haeufigste Fall
 * bleibt so kurz wie bisher. Die Teile sind je ein eigener Schluessel und
 * werden mit „, " verbunden ([UiText.Res] mit [SYNC_RESULT_JOIN]).
 */
internal fun syncResultText(result: SyncResult): UiText {
    val deleted = result.deletedLocal + result.deletedRemote
    val parts = buildList {
        add(UiText.Res(R.string.more_sync_result_pushed, listOf(result.pushed)))
        add(
            if (result.updated > 0) {
                UiText.Res(R.string.more_sync_result_pulled_updated, listOf(result.pulled, result.updated))
            } else {
                UiText.Res(R.string.more_sync_result_pulled, listOf(result.pulled))
            },
        )
        if (deleted > 0) add(UiText.Res(R.string.more_sync_result_deleted, listOf(deleted)))
        add(UiText.Plural(R.plurals.more_sync_result_total_count, result.total))
    }
    return parts.reduce { joined, part -> UiText.Res(SYNC_RESULT_JOIN, listOf(joined, part)) }
}

/** „%1$s, %2$s" — verbindet die Teile von [syncResultText]. */
private val SYNC_RESULT_JOIN = R.string.more_sync_result_join
