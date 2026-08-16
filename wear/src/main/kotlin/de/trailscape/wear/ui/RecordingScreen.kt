package de.trailscape.wear.ui

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.Text
import de.trailscape.core.formatDuration
import de.trailscape.core.formatKm
import de.trailscape.wear.comm.PhoneLink
import de.trailscape.wear.record.RecordingStatus
import de.trailscape.wear.ui.theme.AccentGreen
import de.trailscape.wear.ui.theme.LiveActionButtonSize
import de.trailscape.wear.ui.theme.MutedText
import de.trailscape.wear.ui.theme.OnAccentGreen
import de.trailscape.wear.ui.theme.StartButtonSize
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * Top-level Bildschirm: waehlt anhand von [RecordingStatus.phase], was zu
 * sehen ist. Rund gedacht (Watch Ultra): jeder Zustand ist eine einzige,
 * feste Flaeche ohne Scrollen — bei zwei Bildschirmen und wenigen Werten gibt
 * es nichts, wofuer eine Liste noetig waere. Dark-Ground und eine einzige
 * Akzentfarbe ([AccentGreen]) ueberall, keine Erklaertexte: Rams, nicht
 * Dashboard.
 *
 * ## Gestartet von der Uhr ODER vom Telefon
 * Diese Funktion fragt nirgends, wer die Aufzeichnung ausgeloest hat — sie
 * zeichnet ausschliesslich [RecordingStatus] nach. Kommt der Start-Befehl vom
 * Telefon (ueber [de.trailscape.wear.comm.CommandListenerService]), wechselt
 * dieselbe [RecordingStatus.phase] genauso wie bei einem Tipp auf dieser Uhr —
 * und dieser Bildschirm wechselt automatisch mit.
 */
@Composable
fun RecordingScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val phase by RecordingStatus.phase.collectAsStateWithLifecycle()
    val bericht by RecordingStatus.bericht.collectAsStateWithLifecycle()
    val fehler by RecordingStatus.fehler.collectAsStateWithLifecycle()

    val benoetigteRechte = remember { benoetigteRechte() }
    var rechteVollstaendig by remember { mutableStateOf(alleErteilt(context, benoetigteRechte)) }
    val rechteAnfragen = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        rechteVollstaendig = alleErteilt(context, benoetigteRechte)
        // Die Faehigkeitsabfrage selbst braucht keine Berechtigung, aber
        // Health Services antwortet erst nach der Freigabe zuverlaessig.
        scope.launch { RecordingStatus.ladeFaehigkeiten(context) }
    }

    LaunchedEffect(Unit) {
        RecordingStatus.ladeFaehigkeiten(context)
    }

    // `remember(context)` haelt den Flow (und seinen CapabilityClient-
    // Zuhoerer) ueber Rekompositionen hinweg am Leben — ohne das wuerde jede
    // Rekomposition dieses Bildschirms einen neuen Zuhoerer anmelden.
    val telefonFluss = remember(context) { PhoneLink.verbindungsFluss(context) }
    val telefonVerbunden by telefonFluss.collectAsStateWithLifecycle(initialValue = false)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        when (phase) {
            RecordingStatus.Phase.UNBEKANNT,
            RecordingStatus.Phase.BEREIT,
            RecordingStatus.Phase.FEHLER,
            -> StartScreen(
                telefonVerbunden = telefonVerbunden,
                kannStarten = rechteVollstaendig && bericht?.radfahrenUnterstuetzt == true,
                fehler = fehler,
                onStart = {
                    if (!rechteVollstaendig) {
                        rechteAnfragen.launch(benoetigteRechte)
                    } else {
                        RecordingStatus.start(context)
                    }
                },
            )

            RecordingStatus.Phase.VORBEREITEN -> VorbereitenScreen()

            RecordingStatus.Phase.LAEUFT,
            RecordingStatus.Phase.PAUSIERT,
            -> LiveScreen(context = context, phase = phase)

            RecordingStatus.Phase.BEENDET -> EndeScreen()
        }
    }
}

/**
 * Startbildschirm: ein grosser runder Start-Knopf, darunter die stille
 * Telefon-Verbindungszeile. Kein Fliesstext — fehlende Berechtigungen fragt
 * derselbe Knopf ab (der Systemdialog erklaert sich selbst), statt dass diese
 * App es vorher in Worten ankuendigt.
 */
@Composable
private fun StartScreen(
    telefonVerbunden: Boolean,
    kannStarten: Boolean,
    fehler: String?,
    onStart: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        RundKnopf(
            durchmesser = StartButtonSize,
            hintergrund = if (kannStarten) AccentGreen else MutedText.copy(alpha = 0.25f),
            onClick = onStart,
        ) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = "Aufzeichnung starten",
                tint = if (kannStarten) OnAccentGreen else Color.Black,
                modifier = Modifier.size(56.dp),
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        Text(
            text = if (telefonVerbunden) "Telefon verbunden" else "Telefon getrennt",
            color = MutedText,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
        )

        fehler?.let {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = it,
                color = MutedText,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 28.dp),
            )
        }
    }
}

/** GPS/HF waermen vor (`prepareExercise`) — ein stiller Ring, kein Fortschrittstext. */
@Composable
private fun VorbereitenScreen() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

/**
 * Live-Bildschirm: Herzfrequenz gross und mittig — das ist der Grund, diesen
 * Wert am Handgelenk und nicht nur auf dem Telefon abzulesen — darunter Zeit,
 * Distanz und Tempo kompakt in einer Zeile. Pause/Weiter und
 * Beenden liegen als zwei runde 48-dp-Knoepfe am unteren Bogen, weit genug
 * auseinander, um sie auf dem Rad ohne hinzusehen sicher zu treffen.
 */
@Composable
private fun LiveScreen(context: Context, phase: RecordingStatus.Phase) {
    val laufzeitMs by RecordingStatus.laufzeitMs.collectAsStateWithLifecycle()
    val distanzKm by RecordingStatus.distanzKm.collectAsStateWithLifecycle()
    val tempoKmh by RecordingStatus.tempoKmh.collectAsStateWithLifecycle()
    val hf by RecordingStatus.letzteHfBpm.collectAsStateWithLifecycle()

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 56.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = hf?.toString() ?: "–",
                color = AccentGreen,
                fontSize = 72.sp,
                fontWeight = FontWeight.Bold,
                // Tabellen-/Versalziffern: der Herzschlag darf beim Wechseln
                // von "9" auf "10" nicht seitlich wandern.
                style = TextStyle(fontFeatureSettings = "tnum"),
            )

            Spacer(modifier = Modifier.height(6.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = formatDuration((laufzeitMs / 1000).toInt()),
                    color = Color.White,
                    fontSize = 16.sp,
                    style = TextStyle(fontFeatureSettings = "tnum"),
                )
                Text(
                    text = "${formatKmDe(distanzKm)} km",
                    color = Color.White,
                    fontSize = 16.sp,
                    style = TextStyle(fontFeatureSettings = "tnum"),
                )
                Text(
                    text = "${formatTempoDe(tempoKmh)} km/h",
                    color = Color.White,
                    fontSize = 16.sp,
                    style = TextStyle(fontFeatureSettings = "tnum"),
                )
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            RundKnopf(
                durchmesser = LiveActionButtonSize,
                hintergrund = AccentGreen,
                onClick = {
                    if (phase == RecordingStatus.Phase.PAUSIERT) {
                        RecordingStatus.fortsetzen(context)
                    } else {
                        RecordingStatus.pausieren(context)
                    }
                },
            ) {
                Icon(
                    imageVector = if (phase == RecordingStatus.Phase.PAUSIERT) {
                        Icons.Filled.PlayArrow
                    } else {
                        Icons.Filled.Pause
                    },
                    contentDescription = if (phase == RecordingStatus.Phase.PAUSIERT) "Weiter" else "Pause",
                    tint = OnAccentGreen,
                )
            }

            // Nebenaktion als helle, unauffaellige Flaeche statt einer
            // zweiten Akzentfarbe — "Beenden" ist mit Bedacht getroffen, kein
            // gleichrangiger Hauptknopf.
            RundKnopf(
                durchmesser = LiveActionButtonSize,
                hintergrund = MutedText.copy(alpha = 0.18f),
                onClick = { RecordingStatus.stop(context) },
            ) {
                Icon(imageVector = Icons.Filled.Stop, contentDescription = "Beenden", tint = MutedText)
            }
        }
    }
}

/** Kurzer Abschluss: Endstand, ein Knopf zurueck zum Start. */
@Composable
private fun EndeScreen() {
    val laufzeitMs by RecordingStatus.laufzeitMs.collectAsStateWithLifecycle()
    val distanzKm by RecordingStatus.distanzKm.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = formatDuration((laufzeitMs / 1000).toInt()),
            color = Color.White,
            fontSize = 24.sp,
            style = TextStyle(fontFeatureSettings = "tnum"),
        )
        Text(
            text = "${formatKmDe(distanzKm)} km",
            color = MutedText,
            fontSize = 15.sp,
            style = TextStyle(fontFeatureSettings = "tnum"),
        )

        Spacer(modifier = Modifier.height(18.dp))

        RundKnopf(
            durchmesser = LiveActionButtonSize,
            hintergrund = AccentGreen,
            onClick = { RecordingStatus.zurueckZumStart() },
        ) {
            Icon(imageVector = Icons.Filled.PlayArrow, contentDescription = "Zurück zum Start", tint = OnAccentGreen)
        }
    }
}

/**
 * Ein runder, farbig gefuellter Knopf.
 *
 * Bewusst von Hand aus `Box`/`clip`/`background`/`clickable` gebaut statt
 * `androidx.wear.compose.material3.Button`/`IconButton`: Diese App braucht an
 * genau drei Stellen einen perfekten Kreis in einer fest vorgegebenen Farbe
 * (Start-Knopf, Pause/Weiter, Beenden) — mit dieser kleinen, selbst
 * kontrollierten Bauform ist das direkter als drei mal die Farb-Parameter
 * einer fremden Komponente zu treffen.
 */
@Composable
private fun RundKnopf(
    durchmesser: Dp,
    hintergrund: Color,
    onClick: () -> Unit,
    inhalt: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(durchmesser)
            .clip(CircleShape)
            .background(hintergrund)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        inhalt()
    }
}

/** Kilometer mit deutschem Dezimalkomma — [formatKm] liefert einen Punkt. */
private fun formatKmDe(km: Double): String = formatKm(km).replace('.', ',')

/** Tempo mit einer Nachkommastelle und deutschem Komma, "–" ohne Wert. */
private fun formatTempoDe(kmh: Double?): String =
    kmh?.let { String.format(Locale.GERMANY, "%.1f", it) } ?: "–"
