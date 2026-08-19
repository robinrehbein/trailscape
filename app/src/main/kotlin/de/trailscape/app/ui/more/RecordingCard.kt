package de.trailscape.app.ui.more

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import de.trailscape.app.record.abbiegehinweiseAktiviert
import de.trailscape.app.record.autoPauseAktiviert
import de.trailscape.app.record.batterieAusnahmeIntent
import de.trailscape.app.record.kilometerAnsagenAktiviert
import de.trailscape.app.record.offRouteVibrationAktiviert
import de.trailscape.app.record.setzeAbbiegehinweiseAktiviert
import de.trailscape.app.record.setzeAutoPauseAktiviert
import de.trailscape.app.record.setzeKilometerAnsagenAktiviert
import de.trailscape.app.record.setzeOffRouteVibrationAktiviert
import de.trailscape.app.record.setzeSprachansagenAktiviert
import de.trailscape.app.record.sprachansagenAktiviert
import de.trailscape.app.record.vonBatterieoptimierungAusgenommen

/**
 * Einstellungen der Aufzeichnung — Inhalt der Zeile „Aufzeichnung" in der
 * Gruppe „App" des Mehr-Tabs (siehe `MoreScreen.kt`).
 *
 * Alles ohne Umweg ueber das `AppViewModel` direkt in den
 * `SharedPreferences` (siehe `record/RecordingSettings.kt` — der
 * `RecordingService` liest dieselben Schluessel auf seinem eigenen Thread):
 *
 *  * **Auto-Pause** (Default AN): Steht das Rad, pausiert die Aufzeichnung
 *    von selbst und laeuft bei Weiterfahrt weiter (`record/AutoPauseLogic.kt`).
 *  * **Sprachansagen** (Default AUS — dass das Telefon spricht, ist eine
 *    bewusste Entscheidung): Hauptschalter fuer alle Ansagen ueber die
 *    lokale Android-Sprachausgabe (`voice/VoiceAnnouncer.kt`), darunter die
 *    Unterschalter „Abbiegehinweise" und „Kilometer-Ansagen" (beide Default
 *    AN, wirken nur mit Hauptschalter).
 *  * **Vibration abseits der Route** (Default AN): unabhaengig vom
 *    Hauptschalter, wirkt auch ganz ohne Sprachausgabe
 *    (`voice/Vibration.kt`).
 *  * **Batterieoptimierung**: Status und der Knopf zum Systemdialog — der
 *    dauerhafte Wohnort des Hinweises, der beim ersten Aufzeichnungsstart
 *    einmalig erscheint (`ui/map/BatteryNoticeDialog.kt`).
 *
 * Kein „Speichern"-Knopf, wie ueberall in dieser Liste: Jede Aenderung gilt
 * sofort — auch fuer eine gerade laufende Aufzeichnung, der Dienst liest den
 * Schalter je GPS-Meldung neu.
 */
@Composable
fun RecordingCardContent() {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val hintColor = MaterialTheme.colorScheme.onSurfaceVariant

    var autoPause by remember { mutableStateOf(autoPauseAktiviert(context)) }
    var sprachansagen by remember { mutableStateOf(sprachansagenAktiviert(context)) }
    var abbiegehinweise by remember { mutableStateOf(abbiegehinweiseAktiviert(context)) }
    var kilometerAnsagen by remember { mutableStateOf(kilometerAnsagenAktiviert(context)) }
    var offRouteVibration by remember { mutableStateOf(offRouteVibrationAktiviert(context)) }

    // Der Systemdialog liefert kein Ergebnis im eigentlichen Sinn — nach der
    // Rueckkehr wird der Status schlicht neu gelesen. Eigener Zustand statt
    // eines direkten Aufrufs im Rumpf, weil die Antwort des Dialogs von sich
    // aus keine Recomposition ausloest (dasselbe Muster wie in
    // `ReminderCard.kt` bei der Benachrichtigungs-Berechtigung).
    var ausgenommen by remember { mutableStateOf(vonBatterieoptimierungAusgenommen(context)) }
    val ausnahmeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        ausgenommen = vonBatterieoptimierungAusgenommen(context)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = autoPause,
                role = Role.Switch,
                onValueChange = {
                    haptics.performHapticFeedback(HapticFeedbackType.ToggleOn)
                    autoPause = it
                    setzeAutoPauseAktiviert(context, it)
                },
            )
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = "Auto-Pause", style = MaterialTheme.typography.bodyLarge)
            Text(
                text = "Pausiert von selbst, wenn du stehst, und zeichnet bei " +
                    "Weiterfahrt automatisch weiter auf.",
                style = MaterialTheme.typography.bodySmall,
                color = hintColor,
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        // onCheckedChange = null: Die Zeile meldet, nicht der Schalter.
        Switch(checked = autoPause, onCheckedChange = null)
    }

    HorizontalDivider()

    SchalterZeile(
        titel = "Sprachansagen",
        beschreibung = "Sagt Abbiegehinweise, Kilometerstände und den Zustand der " +
            "Aufzeichnung über die Android-Sprachausgabe an.",
        checked = sprachansagen,
        onChange = {
            sprachansagen = it
            setzeSprachansagenAktiviert(context, it)
        },
    )
    // Die Unterschalter bleiben sichtbar, sind aber nur mit Hauptschalter
    // bedienbar — so ist ablesbar, was ein Einschalten mitbringt.
    SchalterZeile(
        titel = "Abbiegehinweise",
        beschreibung = "„In 100 Metern links“ während einer Navigation, aus der " +
            "Routengeometrie berechnet.",
        checked = abbiegehinweise,
        enabled = sprachansagen,
        eingerueckt = true,
        onChange = {
            abbiegehinweise = it
            setzeAbbiegehinweiseAktiviert(context, it)
        },
    )
    SchalterZeile(
        titel = "Kilometer-Ansagen",
        beschreibung = "Alle 5 Kilometer Distanz und Fahrzeit, z. B. „15 Kilometer, 42 Minuten“.",
        checked = kilometerAnsagen,
        enabled = sprachansagen,
        eingerueckt = true,
        onChange = {
            kilometerAnsagen = it
            setzeKilometerAnsagenAktiviert(context, it)
        },
    )
    SchalterZeile(
        titel = "Vibration abseits der Route",
        beschreibung = "Deutliches Vibrationsmuster, wenn du die Route verlässt — " +
            "auch ohne Sprachansagen.",
        checked = offRouteVibration,
        onChange = {
            offRouteVibration = it
            setzeOffRouteVibrationAktiviert(context, it)
        },
    )
    Text(
        text = "Alles läuft lokal auf dem Gerät über die installierte Sprachausgabe — " +
            "Trailscape braucht dafür keine Internetverbindung und sendet nichts.",
        style = MaterialTheme.typography.bodySmall,
        color = hintColor,
    )
    Spacer(modifier = Modifier.height(8.dp))

    HorizontalDivider()
    Spacer(modifier = Modifier.height(8.dp))

    Text(text = "Batterieoptimierung", style = MaterialTheme.typography.bodyLarge)
    Text(
        text = if (ausgenommen) {
            "Trailscape ist von der Batterieoptimierung ausgenommen — die " +
                "Aufzeichnung läuft auch bei ausgeschaltetem Bildschirm zuverlässig weiter."
        } else {
            "Manche Geräte beenden die GPS-Aufzeichnung im Hintergrund, wenn der " +
                "Bildschirm länger aus ist. Eine Ausnahme von der Batterieoptimierung " +
                "verhindert das."
        },
        style = MaterialTheme.typography.bodySmall,
        color = hintColor,
    )
    if (!ausgenommen) {
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = {
                try {
                    ausnahmeLauncher.launch(batterieAusnahmeIntent(context))
                } catch (e: Exception) {
                    // Manche Geraete kennen den Dialog nicht; dann bleibt der
                    // Status eben stehen und der Text erklaert die Lage.
                }
            },
        ) { Text("Ausnahme erlauben") }
    }
}

/**
 * Eine Schalterzeile im Stil der Auto-Pause-Zeile oben: ganze Zeile
 * [toggleable], der [Switch] selbst nur Anzeige (`onCheckedChange = null`).
 *
 * @param enabled `false` fuer Unterschalter, deren Hauptschalter aus ist —
 *   die Zeile bleibt sichtbar (man sieht, was ein Einschalten mitbraechte),
 *   ist aber nicht bedienbar und gedimmt.
 * @param eingerueckt rueckt Unterschalter unter ihren Hauptschalter ein.
 */
@Composable
private fun SchalterZeile(
    titel: String,
    beschreibung: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    eingerueckt: Boolean = false,
) {
    val haptics = LocalHapticFeedback.current
    val hintColor = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = {
                    haptics.performHapticFeedback(HapticFeedbackType.ToggleOn)
                    onChange(it)
                },
            )
            .padding(start = if (eingerueckt) 16.dp else 0.dp)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = titel,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else hintColor,
            )
            Text(
                text = beschreibung,
                style = MaterialTheme.typography.bodySmall,
                color = hintColor,
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}
