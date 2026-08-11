package de.trailscape.wear.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import de.trailscape.core.formatDuration
import de.trailscape.wear.record.SpikeStatus
import kotlinx.coroutines.launch

/**
 * Die Oberflaeche des Spikes: bewusst haesslich, dafuer vollstaendig.
 *
 * Sie ist kein Entwurf fuer eine spaetere Uhr-App, sondern die Anzeige eines
 * Messgeraets. Alles, was hier steht, steht hier, weil es waehrend der Fahrt
 * am Handgelenk ablesbar sein muss — allen voran die beiden Distanzen
 * nebeneinander (Health Services gegen `:core`) und der GPS-Zustand.
 *
 * Gerüst nach Wear-Vorgabe: [AppScaffold] (App-weit, haelt die Uhrzeit),
 * darin [ScreenScaffold] (pro Bildschirm, liefert Scroll-Indikator) und
 * [TransformingLazyColumn] als scrollende Liste.
 */
@Composable
fun SpikeScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val phase by SpikeStatus.phase.collectAsStateWithLifecycle()
    val bericht by SpikeStatus.bericht.collectAsStateWithLifecycle()
    val laufzeitMs by SpikeStatus.laufzeitMs.collectAsStateWithLifecycle()
    val punktzahl by SpikeStatus.punktzahl.collectAsStateWithLifecycle()
    val hf by SpikeStatus.letzteHfBpm.collectAsStateWithLifecycle()
    val hoehe by SpikeStatus.letzteHoeheM.collectAsStateWithLifecycle()
    val tempo by SpikeStatus.letzteGeschwindigkeitKmh.collectAsStateWithLifecycle()
    val gps by SpikeStatus.gpsZustand.collectAsStateWithLifecycle()
    val hfZustand by SpikeStatus.hfZustand.collectAsStateWithLifecycle()
    val akku by SpikeStatus.akkuProzent.collectAsStateWithLifecycle()
    val hsKm by SpikeStatus.hsDistanzKm.collectAsStateWithLifecycle()
    val coreKm by SpikeStatus.coreDistanzKm.collectAsStateWithLifecycle()
    val coreAufstieg by SpikeStatus.coreAufstiegM.collectAsStateWithLifecycle()
    val pfad by SpikeStatus.journalPfad.collectAsStateWithLifecycle()
    val fehler by SpikeStatus.fehler.collectAsStateWithLifecycle()

    val benoetigteRechte = remember { benoetigteRechte() }
    var rechteVollstaendig by remember {
        mutableStateOf(alleErteilt(context, benoetigteRechte))
    }
    val rechteAnfragen = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        rechteVollstaendig = alleErteilt(context, benoetigteRechte)
        // Die Faehigkeitsabfrage selbst braucht keine Berechtigung, aber
        // Health Services antwortet erst nach der Freigabe zuverlaessig.
        scope.launch { SpikeStatus.ladeFaehigkeiten(context) }
    }

    LaunchedEffect(Unit) {
        SpikeStatus.ladeFaehigkeiten(context)
    }

    AppScaffold {
        val listenZustand = rememberTransformingLazyColumnState()
        ScreenScaffold(listenZustand) { innenAbstand: PaddingValues ->
            TransformingLazyColumn(
                state = listenZustand,
                contentPadding = innenAbstand,
            ) {
                item {
                    ListHeader { Text("Wear-Spike") }
                }

                fehler?.let { text ->
                    item { Zeile("Fehler", text) }
                }

                when (phase) {
                    SpikeStatus.Phase.UNBEKANNT,
                    SpikeStatus.Phase.BEREIT,
                    SpikeStatus.Phase.FEHLER,
                    -> {
                        if (!rechteVollstaendig) {
                            item {
                                Button(
                                    onClick = { rechteAnfragen.launch(benoetigteRechte) },
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text("Berechtigungen erteilen") }
                            }
                        }

                        val b = bericht
                        if (b == null) {
                            item { Zeile("Faehigkeiten", "werden abgefragt …") }
                        } else {
                            item {
                                Zeile(
                                    "Absolute Hoehe",
                                    if (b.hatAbsoluteHoehe) "JA — Hoehenprofil moeglich" else "NEIN",
                                )
                            }
                            item { ListHeader { Text("Unterstuetzt (${b.unterstuetzteNamen.size})") } }
                            items(b.unterstuetzteNamen) { name -> Zeile("+", name) }
                            item { ListHeader { Text("Fehlt (${b.vermissteNamen.size})") } }
                            if (b.vermissteNamen.isEmpty()) {
                                item { Zeile("—", "nichts, die Uhr kann alles") }
                            } else {
                                items(b.vermissteNamen) { name -> Zeile("-", name) }
                            }
                            item { ListHeader { Text("Uhr kann fuer Rad (${b.geraeteNamen.size})") } }
                            items(b.geraeteNamen) { name -> Zeile("·", name) }
                        }

                        item {
                            Button(
                                onClick = { SpikeStatus.start(context) },
                                enabled = rechteVollstaendig && bericht?.radfahrenUnterstuetzt == true,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Aufzeichnung starten") }
                        }
                    }

                    SpikeStatus.Phase.VORBEREITEN -> {
                        item { Zeile("Zustand", "waermt GPS und HF vor …") }
                        item { Zeile("GPS", gps) }
                        item { AbbruchKnopf(context) }
                    }

                    SpikeStatus.Phase.LAEUFT,
                    SpikeStatus.Phase.PAUSIERT,
                    -> {
                        item { Zeile("Zeit", formatDuration((laufzeitMs / 1000).toInt())) }
                        item { Zeile("Punkte", punktzahl.toString()) }
                        item { Zeile("GPS", gps) }
                        item { Zeile("HF-Sensor", hfZustand) }
                        item { Zeile("HF", hf?.let { "$it bpm" } ?: "—") }
                        item { Zeile("Hoehe", hoehe?.let { "${it.toInt()} m" } ?: "—") }
                        item { Zeile("Tempo", tempo?.let { "%.1f km/h".format(it) } ?: "—") }
                        item { Zeile("Akku", akku?.let { "$it %" } ?: "—") }
                        item { ListHeader { Text("Distanz") } }
                        item { Zeile("Health Serv.", hsKm?.let { "%.2f km".format(it) } ?: "—") }
                        // Frage 4 auf einen Blick: dieselbe Fahrt, gerechnet
                        // vom plattformfreien `:core`. Weicht die Zahl ab, ist
                        // das eine Erkenntnis und kein Anzeigefehler.
                        item { Zeile(":core", "%.2f km".format(coreKm)) }
                        item { Zeile(":core Anstieg", "${coreAufstieg.toInt()} m") }

                        item {
                            Button(
                                onClick = {
                                    if (phase == SpikeStatus.Phase.PAUSIERT) {
                                        SpikeStatus.fortsetzen(context)
                                    } else {
                                        SpikeStatus.pausieren(context)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(if (phase == SpikeStatus.Phase.PAUSIERT) "Weiter" else "Pause")
                            }
                        }
                        item { AbbruchKnopf(context) }
                    }

                    SpikeStatus.Phase.BEENDET -> {
                        item { ListHeader { Text("Fertig") } }
                        item { Zeile("Dauer", formatDuration((laufzeitMs / 1000).toInt())) }
                        item { Zeile("Punkte", punktzahl.toString()) }
                        item { Zeile("Health Serv.", hsKm?.let { "%.2f km".format(it) } ?: "—") }
                        item { Zeile(":core", "%.2f km".format(coreKm)) }
                        item { Zeile("Akku", akku?.let { "$it %" } ?: "—") }
                        item { ListHeader { Text("Protokoll") } }
                        // Vollstaendiger Pfad, damit sich die Datei ohne Root
                        // per `adb pull <pfad>` holen laesst.
                        item { Zeile("Datei", pfad ?: "—") }
                    }
                }
            }
        }
    }
}

/** Eine Beschriftung-Wert-Zeile. Keine Gestaltung, nur Lesbarkeit. */
@Composable
private fun Zeile(beschriftung: String, wert: String) {
    Text(
        text = "$beschriftung: $wert",
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp),
        textAlign = TextAlign.Start,
    )
}

@Composable
private fun AbbruchKnopf(context: Context) {
    Button(
        onClick = { SpikeStatus.stop(context) },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Beenden") }
}

/**
 * Die Rechte, die der Spike zur Laufzeit braucht.
 *
 * BODY_SENSORS wurde mit API 36 durch `health.READ_HEART_RATE` abgeloest;
 * beide gleichzeitig anzufragen ist auf keiner Version richtig, deshalb die
 * Fallunterscheidung. Es gibt KEINE Berechtigung namens ONGOING_ACTIVITY —
 * die laufende Anzeige haengt an der Notification und damit an
 * POST_NOTIFICATIONS.
 */
private fun benoetigteRechte(): Array<String> = buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACTIVITY_RECOGNITION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
    if (Build.VERSION.SDK_INT <= 35) {
        @Suppress("DEPRECATION")
        add(Manifest.permission.BODY_SENSORS)
    } else {
        add("android.permission.health.READ_HEART_RATE")
    }
}.toTypedArray()

private fun alleErteilt(context: Context, rechte: Array<String>): Boolean =
    rechte.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
