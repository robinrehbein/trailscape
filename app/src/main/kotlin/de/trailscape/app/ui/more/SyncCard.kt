package de.trailscape.app.ui.more

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import de.trailscape.app.ui.AppViewModel
import de.trailscape.app.ui.withCause
import de.trailscape.core.SyncConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Selfhost-Sync-Karte — Port von der `Sync (Selfhost)`-Karte in
 * `lib/screens/more_screen.dart` (`_runSync`, `_loadSyncConfig`).
 *
 * **Abweichung/Workaround.** [AppViewModel.setSyncConfig] persistiert
 * fire-and-forget auf [kotlinx.coroutines.Dispatchers.IO] (siehe dessen
 * KDoc) — anders als im Dart-Original, wo `await setSyncConfig(...)` die
 * Schreiboperation abwartet, bevor synchronisiert wird. Ein direktes
 * `appViewModel.setSyncConfig(config)` gefolgt von `appViewModel.syncNow()`
 * koennte daher — rein theoretisch — noch die alte (oder gar keine)
 * Konfiguration lesen. Da `AppViewModel` keinen suspend-Setter anbietet und
 * diese Datei `AppViewModel` nicht aendern darf, schreibt diese Karte die
 * Konfiguration stattdessen selbst *synchron abgewartet* ueber
 * [de.trailscape.core.setSyncConfig] auf denselben, von [AppServices]
 * bereitgestellten [de.trailscape.core.KeyValueStore] — erst danach beginnt
 * der eigentliche Sync. Der zusaetzliche `appViewModel.setSyncConfig(config)`-
 * Aufruf haelt nur den beobachtbaren [AppViewModel.syncConfig]-Zustand
 * konsistent (derselbe Wert wird doppelt, aber unschaedlich geschrieben).
 *
 * Der Inhalt der Zeile „Sync (Selfhost)" in der Gruppe „App" des Mehr-Tabs
 * (siehe `MoreScreen.kt`) — keine eigene Karte mehr, `MoreRow` stellt Titel
 * und Aufklapp-Rahmen.
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

    OutlinedTextField(
        value = urlText,
        onValueChange = { urlText = it },
        label = { Text("Server-URL") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(modifier = Modifier.height(8.dp))
    OutlinedTextField(
        value = tokenText,
        onValueChange = { tokenText = it },
        label = { Text("Token") },
        singleLine = true,
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
                    statusText = "${result.pushed} hochgeladen, ${result.pulled} geladen, " +
                        "${result.total} Touren"
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
    Text(
        text = "Details zum Aufsetzen eines eigenen Sync-Servers findest du im " +
            "Repository unter server/README.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
