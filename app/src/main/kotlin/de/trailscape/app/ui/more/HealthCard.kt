package de.trailscape.app.ui.more

import de.trailscape.app.ui.health.rememberRouteConsentLauncher
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.trailscape.app.ui.AppViewModel
import de.trailscape.app.ui.components.NoticeBox
import de.trailscape.app.ui.components.OneUiDialog
import de.trailscape.app.ui.formatDateTime
import de.trailscape.app.ui.theme.LocalSignalColors
import de.trailscape.core.HealthAvailability
import de.trailscape.core.HealthSyncException
import de.trailscape.core.HealthSyncReport
import de.trailscape.core.summaryLine
import de.trailscape.core.healthSyncInitialWindowMs
import java.time.LocalDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val HEALTH_CONNECT_PACKAGE = "com.google.android.apps.healthdata"

/**
 * Health-Connect-Karte — Port von `_buildHealthCard()` aus
 * `lib/screens/more_screen.dart`.
 *
 * ## Warum sie nicht mehr „Samsung Health" heisst
 * Die App spricht ausschliesslich mit **Health Connect**, der
 * Android-Datendrehscheibe — nicht mit einem einzelnen Hersteller. Samsung
 * Health ist nur eine von vielen Quellen, die dort hineinschreiben; Garmin
 * Connect, Fitbit, Polar Flow und Google Fit tun dasselbe. Der alte Titel liess
 * jede Nutzerin ohne Samsung-Uhr an dieser Karte vorbeiscrollen, obwohl genau
 * sie gemeint war. Aus demselben Grund nennt der Text unten Samsung Health nur
 * noch als Beispiel unter mehreren.
 *
 * Abweichung vom Original: statt `HealthPluginGateway.installHealthConnect()`
 * (das es auf Android nativ nicht mehr gibt, siehe `HealthTypes.kt`-KDoc)
 * oeffnet der Installations-Button hier direkt den Play Store per Intent.
 *
 * Der Inhalt der Seite „Uhr & Gesundheitsdaten" der Einstellungen (siehe
 * `MoreScreen.kt`). Die Seite heisst nach dem, was die Nutzerin hat — eine
 * Uhr —, nicht nach der Android-Schnittstelle dazwischen; Health Connect
 * nennt erst der Text auf der Seite.
 */
@Composable
fun HealthCardContent(appViewModel: AppViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val connection by appViewModel.healthConnection.collectAsStateWithLifecycle()
    val report = appViewModel.lastSyncReport.collectAsStateWithLifecycle().value
    val historyAccess by appViewModel.healthHistoryAccess.collectAsStateWithLifecycle()

    var busy by remember { mutableStateOf(false) }
    var lastSyncAt by remember { mutableStateOf<LocalDateTime?>(null) }
    var showDebugDialog by remember { mutableStateOf(false) }

    suspend fun refreshLastSyncAt() {
        lastSyncAt = withContext(Dispatchers.IO) { appViewModel.healthSync.lastImportAt() }
    }

    LaunchedEffect(Unit) {
        appViewModel.refreshHealthConnection()
        refreshLastSyncAt()
    }

    val hintColor = MaterialTheme.colorScheme.onSurfaceVariant
    // Dieselbe Warnfarbe wie die Ampeln des Trainings-Tabs; vorher lag hier
    // eine private Kopie von `Colors.orange.shade800`, die im Dunkelmodus
    // nicht mit aufgehellt wurde.
    val warningColor = LocalSignalColors.current.warning

    SettingsHint("Workouts und Vitalwerte deiner Uhr kommen über Health Connect.")
    Spacer(modifier = Modifier.height(12.dp))

    when (val current = connection) {
        null -> Text("Prüfe Verbindung …", style = MaterialTheme.typography.bodyMedium)
        else -> Row(verticalAlignment = Alignment.Top) {
            Icon(
                imageVector = if (current.isReady) Icons.Filled.CheckCircle else Icons.Filled.Info,
                contentDescription = null,
                tint = if (current.isReady) MaterialTheme.colorScheme.primary else hintColor,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = current.message,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
        }
    }
    Spacer(modifier = Modifier.height(12.dp))

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when {
            connection?.availability == HealthAvailability.NICHT_INSTALLIERT -> {
                Button(
                    onClick = { openHealthConnectInPlayStore(context) },
                    enabled = !busy,
                ) { Text("Health Connect installieren") }
            }

            connection?.needsPermissions == true -> {
                Button(
                    onClick = {
                        scope.launch {
                            busy = true
                            try {
                                // requestHealthPermissions() wechselt intern auf
                                // Dispatchers.IO (siehe AppViewModel-KDoc) — auf dem
                                // Main-Thread wuerde HealthConnectGateway hier
                                // verklemmen, weil es auf den Berechtigungsdialog
                                // wartet.
                                appViewModel.requestHealthPermissions()
                            } catch (e: HealthSyncException) {
                                // Der Berechtigungsweg wirft bei jedem
                                // Problem des Anbieters (SecurityException,
                                // RemoteException, abgebrochener Dialog) —
                                // ungefangen waere das ein Absturz.
                                appViewModel.showMessage(e.message)
                            } finally {
                                busy = false
                            }
                        }
                    },
                    enabled = !busy,
                ) { Text("Verbinden") }
            }

            connection?.isReady == true -> {
                Button(
                    onClick = {
                        scope.launch {
                            busy = true
                            try {
                                appViewModel.syncHealthNow(reimportAll = false)
                                appViewModel.showMessage(syncMessage(appViewModel.lastSyncReport.value))
                            } catch (e: HealthSyncException) {
                                appViewModel.showMessage(e.message)
                            } finally {
                                busy = false
                            }
                            refreshLastSyncAt()
                        }
                    },
                    enabled = !busy,
                ) {
                    if (busy) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(18.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        // Nicht „Jetzt synchronisieren": So heisst auch der
                        // Knopf der Sync-Karte weiter unten, der etwas
                        // voellig anderes tut (Abgleich mit dem eigenen
                        // Server). Hier werden Workouts und Vitalwerte aus
                        // Health Connect **geholt** — in eine Richtung.
                        Text("Neue Touren holen")
                    }
                }
                SettingsSecondaryButton(
                    onClick = {
                        scope.launch {
                            busy = true
                            try {
                                appViewModel.syncHealthNow(reimportAll = true)
                                appViewModel.showMessage(syncMessage(appViewModel.lastSyncReport.value))
                            } catch (e: HealthSyncException) {
                                appViewModel.showMessage(e.message)
                            } finally {
                                busy = false
                            }
                            refreshLastSyncAt()
                        }
                    },
                    enabled = !busy,
                ) { Text("Alles neu importieren") }
            }
        }
    }

    // Historien-Freigabe nur anbieten, wenn Health Connect sie kennt und sie
    // fehlt (`false`, nicht `null`) — sonst fuehrte der Knopf ins Leere.
    if (connection?.isReady == true && historyAccess == false) {
        Spacer(modifier = Modifier.height(12.dp))
        HealthHistoryNotice(
            enabled = !busy,
            onRequest = {
                scope.launch {
                    busy = true
                    try {
                        val longImport = appViewModel.requestHealthHistoryAccess()
                        appViewModel.showMessage(
                            if (longImport != null) {
                                syncMessage(longImport)
                            } else {
                                // Health Connect zeigt den Dialog nach zwei
                                // Ablehnungen nicht mehr — dann bleibt nur der
                                // Weg ueber die Einstellungen.
                                "Ohne Freigabe bleiben ältere Fahrten außen vor. Erlauben lässt sie " +
                                    "sich in Health Connect unter „App-Berechtigungen → Trailscape“."
                            },
                        )
                    } catch (e: HealthSyncException) {
                        appViewModel.showMessage(e.message)
                    } finally {
                        busy = false
                    }
                    refreshLastSyncAt()
                }
            },
        )
    }

    lastSyncAt?.let { at ->
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "Letzter Sync: ${formatDateTime(at)}",
            style = MaterialTheme.typography.bodySmall,
            color = hintColor,
        )
    }

    val currentReport = report
    if (currentReport != null) {
        Spacer(modifier = Modifier.height(12.dp))
        HealthSyncSummary(currentReport)
        if (currentReport.debugLines.isNotEmpty()) {
            TextButton(onClick = { showDebugDialog = true }) {
                Text("Diagnose-Details", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (currentReport.workoutsFound == 0) {
            Spacer(modifier = Modifier.height(8.dp))
            NoticeBox(
                icon = Icons.Filled.Info,
                color = hintColor,
                text = "Keine Workouts gefunden — schreibt die App deiner Uhr " +
                    "(Samsung Health, Garmin Connect, Fitbit …) nach Health Connect?",
            )
        }
    }

    Spacer(modifier = Modifier.height(12.dp))

    // Routen, die Health Connect nur mit einer Einzel-Freigabe herausgibt
    // (Samsung Health ohne „Immer erlauben"): ein Knopf pro Stapel, der den
    // Freigabe-Dialog fuer die naechste offene Tour zeigt.
    val consentPending by appViewModel.routeConsentPending.collectAsStateWithLifecycle()
    val requestRoute = rememberRouteConsentLauncher(appViewModel)
    consentPending.firstOrNull()?.let { first ->
        SettingsSecondaryButton(
            onClick = { requestRoute(first) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                if (consentPending.size == 1) {
                    "Route freigeben"
                } else {
                    "Routen freigeben (${consentPending.size})"
                },
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
    }

    // Nur die Touren, bei denen die Freigabe tatsaechlich hilft: Fuer die
    // uebrigen ohne Route (Rolle, Aufzeichnung ohne GPS) hat Health Connect
    // gar keine Routendaten — die Zusammenfassung oben nennt sie getrennt
    // „ohne GPS-Daten", und eine Freigabe-Empfehlung waere dort irrefuehrend.
    val routesLocked = currentReport?.routeConsentPending?.size ?: 0
    if (routesLocked > 0) {
        NoticeBox(
            icon = Icons.Filled.LocationOn,
            color = warningColor,
            // „in Health Connect" gehoert in BEIDE Zweige: Ohne die Angabe
            // sucht der Nutzer die Einstellung in Trailscape — und findet
            // sie dort nie. Das schliessende Anfuehrungszeichen fehlte hier
            // ausserdem ganz, der Pfad lief ungebremst in den naechsten
            // Satzteil.
            text = "$routesLocked " +
                "${if (routesLocked == 1) "Tour kam" else "Touren kamen"} ohne Route. " +
                "Erlaube in Health Connect „App-Berechtigungen → Trailscape → " +
                "Trainingsrouten“ dauerhaft.",
        )
    } else {
        SettingsHint(
            "Für die Route: in Health Connect „App-Berechtigungen → Trailscape → " +
                "Trainingsrouten“ dauerhaft erlauben.",
        )
    }
    Spacer(modifier = Modifier.height(8.dp))
    SettingsHint(
        if (historyAccess == true) {
            "„Alles neu importieren“ holt die Radfahrten der letzten 12 Monate erneut."
        } else {
            "„Alles neu importieren“ holt die letzten " +
                "${healthSyncInitialWindowMs / (24L * 60 * 60 * 1000)} Tage erneut."
        },
    )

    if (showDebugDialog && report != null) {
        HealthDebugDialog(
            lines = report.debugLines,
            onDismiss = { showDebugDialog = false },
            onCopied = { appViewModel.showMessage("Diagnose kopiert.") },
        )
    }
}

/**
 * Ergebnis des letzten Imports: oben die Zeile, auf die es ankommt
 * ([summaryLine] — importiert, ohne Route wegen fehlender Freigabe, ohne
 * GPS-Daten), darunter klein die Rohzahlen fuer Neugierige.
 *
 * Die Freigabe-Zahl steht bewusst in der Hauptzeile: Touren ohne Karte sehen
 * sonst wie ein Fehler von Trailscape aus, dabei fehlt nur ein Tippen auf
 * „Routen freigeben".
 */
@Composable
internal fun HealthSyncSummary(report: HealthSyncReport) {
    val hintColor = MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        text = report.summaryLine(),
        style = MaterialTheme.typography.bodyMedium,
    )
    Text(
        text = "${report.workoutsFound} " +
            "${if (report.workoutsFound == 1) "Radfahrt" else "Radfahrten"} gefunden · " +
            "${report.duplicatesSkipped} schon vorhanden",
        style = MaterialTheme.typography.bodySmall,
        color = hintColor,
    )
}

/**
 * Hinweis samt Knopf, wenn die Historien-Freigabe fehlt.
 *
 * Erklaert in einem Satz, was sie bringt — ohne sie gibt Health Connect
 * nichts heraus, was mehr als 30 Tage vor dem Verbinden liegt; mit ihr holt
 * Trailscape einmalig bis zu 12 Monate Radfahrten —, damit die Nutzerin nicht
 * blind einer weiteren Berechtigung zustimmen muss. Bewusst nicht „nur die
 * letzten 30 Tage": Was nach dem Verbinden dazukommt, bleibt ohnehin lesbar. Sonst gibt es keinen Ort, an dem diese Freigabe
 * nachzuholen waere; ohne den Knopf bliebe sie eine versteckte Funktion.
 */
@Composable
internal fun HealthHistoryNotice(enabled: Boolean, onRequest: () -> Unit) {
    NoticeBox(
        icon = Icons.Filled.History,
        color = MaterialTheme.colorScheme.primary,
        title = "Ältere Fahrten",
        text = "Ohne Freigabe sieht Trailscape keine Fahrten, die mehr als 30 Tage vor " +
            "dem Verbinden liegen. Mit Freigabe holt Trailscape einmalig deine Radfahrten " +
            "der letzten 12 Monate — Training und Form starten dann mit deiner " +
            "Vorgeschichte statt bei null.",
    )
    Spacer(modifier = Modifier.height(8.dp))
    SettingsSecondaryButton(
        onClick = onRequest,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Ältere Fahrten freigeben") }
}

/** Snackbar-Text nach einem Sync; `null` (kein Bericht) zaehlt als „nichts Neues". */
private fun syncMessage(report: HealthSyncReport?): String =
    report?.summaryLine() ?: "Keine neuen Touren"

@Composable
private fun HealthDebugDialog(lines: List<String>, onDismiss: () -> Unit, onCopied: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val text = lines.joinToString("\n")

    OneUiDialog(
        onDismissRequest = onDismiss,
        title = { Text("Diagnose-Details") },
        text = {
            Box(modifier = Modifier.height(320.dp)) {
                SelectionContainer {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    clipboard.setText(AnnotatedString(text))
                    onCopied()
                },
            ) { Text("Kopieren") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Schließen") }
        },
    )
}

/**
 * Oeffnet den Play-Store-Eintrag von Health Connect. Erst ueber `market://`
 * (oeffnet direkt die Play-Store-App), bei Fehlschlag (kein Play Store
 * installiert oder Paket-Sichtbarkeit verweigert) ueber die `https`-Variante,
 * die jeder Browser oeffnen kann.
 */
private fun openHealthConnectInPlayStore(context: Context) {
    val marketIntent = Intent(
        Intent.ACTION_VIEW,
        Uri.parse("market://details?id=$HEALTH_CONNECT_PACKAGE"),
    )
    try {
        context.startActivity(marketIntent)
    } catch (e: ActivityNotFoundException) {
        val webIntent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://play.google.com/store/apps/details?id=$HEALTH_CONNECT_PACKAGE"),
        )
        context.startActivity(webIntent)
    }
}
