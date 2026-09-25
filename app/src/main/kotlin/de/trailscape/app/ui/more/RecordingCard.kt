package de.trailscape.app.ui.more

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
 * Einstellungen der Aufzeichnung — der Abschnitt „Aufzeichnung" der Seite
 * „Aufzeichnung & Ansagen" (siehe `MoreScreen.kt`); die Ansagen stehen
 * darunter in [AnnouncementsCardContent].
 *
 * Alles ohne Umweg ueber das `AppViewModel` direkt in den
 * `SharedPreferences` (siehe `record/RecordingSettings.kt` — der
 * `RecordingService` liest dieselben Schluessel auf seinem eigenen Thread):
 *
 *  * **Auto-Pause** (Default AN): Steht das Rad, pausiert die Aufzeichnung
 *    von selbst und laeuft bei Weiterfahrt weiter (`record/AutoPauseLogic.kt`).
 *  * **Batterieoptimierung**: Status und der Knopf zum Systemdialog — der
 *    dauerhafte Wohnort des Hinweises, der beim ersten Aufzeichnungsstart
 *    einmalig erscheint (`ui/map/BatteryNoticeDialog.kt`).
 *
 * Jede Aenderung gilt sofort — auch fuer eine gerade laufende Aufzeichnung,
 * der Dienst liest den Schalter je GPS-Meldung neu.
 */
@Composable
fun RecordingCardContent() {
    val context = LocalContext.current

    var autoPause by remember { mutableStateOf(autoPauseAktiviert(context)) }

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

    SettingsSwitchRow(
        title = "Auto-Pause",
        subtitle = "Pausiert, wenn du stehst, und läuft bei Weiterfahrt weiter.",
        checked = autoPause,
        onCheckedChange = {
            autoPause = it
            setzeAutoPauseAktiviert(context, it)
        },
    )

    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

    Text(text = "Batterieoptimierung", style = MaterialTheme.typography.bodyLarge)
    SettingsHint(
        text = if (ausgenommen) {
            "Ausgenommen — die Aufzeichnung läuft auch bei dunklem Bildschirm weiter."
        } else {
            "Manche Geräte beenden die Aufzeichnung bei dunklem Bildschirm. Eine Ausnahme verhindert das."
        },
    )
    if (!ausgenommen) {
        Spacer(modifier = Modifier.height(8.dp))
        SettingsSecondaryButton(
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
 * Die Ansagen — der Abschnitt „Ansagen" der Seite „Aufzeichnung & Ansagen".
 *
 *  * **Sprachansagen** (Default AUS — dass das Telefon spricht, ist eine
 *    bewusste Entscheidung): Hauptschalter fuer alle Ansagen ueber die
 *    lokale Android-Sprachausgabe (`voice/VoiceAnnouncer.kt`), darunter die
 *    Unterschalter „Abbiegehinweise" und „Kilometer-Ansagen" (beide Default
 *    AN, wirken nur mit Hauptschalter).
 *  * **Vibration abseits der Route** (Default AN): unabhaengig vom
 *    Hauptschalter, wirkt auch ganz ohne Sprachausgabe
 *    (`voice/Vibration.kt`).
 */
@Composable
fun AnnouncementsCardContent() {
    val context = LocalContext.current

    var sprachansagen by remember { mutableStateOf(sprachansagenAktiviert(context)) }
    var abbiegehinweise by remember { mutableStateOf(abbiegehinweiseAktiviert(context)) }
    var kilometerAnsagen by remember { mutableStateOf(kilometerAnsagenAktiviert(context)) }
    var offRouteVibration by remember { mutableStateOf(offRouteVibrationAktiviert(context)) }

    SettingsSwitchRow(
        title = "Sprachansagen",
        subtitle = "Abbiegehinweise, Kilometer und Aufzeichnungsstatus per Sprachausgabe.",
        checked = sprachansagen,
        onCheckedChange = {
            sprachansagen = it
            setzeSprachansagenAktiviert(context, it)
        },
    )
    // Die Unterschalter bleiben sichtbar, sind aber nur mit Hauptschalter
    // bedienbar — so ist ablesbar, was ein Einschalten mitbringt.
    SettingsSwitchRow(
        title = "Abbiegehinweise",
        subtitle = "„In 100 Metern links“ während einer Navigation.",
        checked = abbiegehinweise,
        enabled = sprachansagen,
        indented = true,
        onCheckedChange = {
            abbiegehinweise = it
            setzeAbbiegehinweiseAktiviert(context, it)
        },
    )
    SettingsSwitchRow(
        title = "Kilometer-Ansagen",
        subtitle = "Alle 5 km Distanz und Fahrzeit.",
        checked = kilometerAnsagen,
        enabled = sprachansagen,
        indented = true,
        onCheckedChange = {
            kilometerAnsagen = it
            setzeKilometerAnsagenAktiviert(context, it)
        },
    )
    SettingsSwitchRow(
        title = "Vibration abseits der Route",
        subtitle = "Vibriert, wenn du die Route verlässt — auch ohne Ansagen.",
        checked = offRouteVibration,
        onCheckedChange = {
            offRouteVibration = it
            setzeOffRouteVibrationAktiviert(context, it)
        },
    )
    Spacer(modifier = Modifier.height(4.dp))
    SettingsHint("Alles läuft lokal auf dem Gerät, ohne Internet.")
}
