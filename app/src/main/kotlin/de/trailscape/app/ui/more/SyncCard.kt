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
import de.trailscape.app.data.AppServices
import de.trailscape.app.ui.components.OneUiTextField
import de.trailscape.app.ui.AppViewModel
import de.trailscape.app.ui.withCause
import de.trailscape.core.SyncConfig
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
    val syncConfig by appViewModel.syncConfig.collectAsStateWithLifecycle()

    var urlText by remember { mutableStateOf("") }
    var tokenText by remember { mutableStateOf("") }
    var syncing by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf<String?>(null) }
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

    SettingsHint("Gleicht deine Touren mit einem selbst betriebenen Trailscape-Server ab.")
    Spacer(modifier = Modifier.height(12.dp))

    OneUiTextField(
        label = "Server-URL",
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
        label = "Token",
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
                statusText = "Bitte Server-URL und Token eintragen."
                return@Button
            }
            scope.launch {
                syncing = true
                statusText = "Synchronisiere …"
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
                    // Kompakter Ergebnissatz: Loeschungen und Aktualisierungen
                    // nur nennen, wenn es welche gab — der haeufigste Fall
                    // bleibt so kurz wie bisher.
                    val deleted = result.deletedLocal + result.deletedRemote
                    statusText = buildString {
                        append("${result.pushed} hochgeladen, ${result.pulled} geladen")
                        if (result.updated > 0) append(" (davon ${result.updated} aktualisiert)")
                        if (deleted > 0) append(", $deleted gelöscht")
                        append(", ${result.total} Touren")
                    }
                } catch (e: Exception) {
                    // Vorher gewann die technische Meldung („Failed to
                    // connect to …"); der deutsche Satz kam nur zum
                    // Vorschein, wenn die Ausnahme gar keinen Text trug.
                    statusText = withCause(
                        "Der Abgleich ist fehlgeschlagen. Prüfe Server-URL und Token " +
                            "und ob der Server erreichbar ist.",
                        e,
                    )
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
            Text("Mit Server abgleichen")
        }
    }

    statusText?.let { status ->
        Spacer(modifier = Modifier.height(12.dp))
        Text(text = status, style = MaterialTheme.typography.bodyMedium)
    }

    Spacer(modifier = Modifier.height(12.dp))
    SettingsHint("Anleitung zum eigenen Server: server/README im Repository.")
}
