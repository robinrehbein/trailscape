package de.trailscape.app.ui.more

import android.Manifest
import android.os.Build
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.trailscape.app.reminder.ReminderScheduler
import de.trailscape.app.ui.AppViewModel
import de.trailscape.app.ui.components.NoticeBox
import de.trailscape.app.ui.formatTime
import de.trailscape.app.ui.map.hasNotificationPermission
import de.trailscape.app.ui.theme.LocalSignalColors
import de.trailscape.core.ReminderSettings
import de.trailscape.core.reminderNudgeAfterDays
import java.time.LocalTime

/**
 * Einstellungen der lokalen Erinnerungen — drei Anlaesse, jeder einzeln
 * abschaltbar, ab Werk alle aus.
 *
 * Kein Vorbild im Dart-Original: Die Flutter-App hatte keinerlei geplante
 * Hintergrundarbeit. Was die Schalter ausloesen, steht in
 * [de.trailscape.app.reminder.ReminderScheduler]; **was** dann gemeldet wird,
 * entscheidet `:core` ([de.trailscape.core.dueReminder]).
 *
 * ## Zwei Uhrzeiten, drei Schalter
 * Der Anstupser bekommt bewusst keine eigene Uhrzeit — er haengt an der
 * Morgen-Uhrzeit der Tageseinheit (siehe
 * [ReminderSettings.dailySessionTime]). Eine dritte Zeitangabe fuer eine
 * Meldung, die ohnehin nur alle paar Tage kommt, waere Bedienflaeche ohne
 * Gewinn. Die Uhrzeit bleibt deshalb auch dann bedienbar, wenn nur der
 * Anstupser eingeschaltet ist.
 *
 * ## Die Berechtigung wird hier angefragt
 * Ab Android 13 braucht jede Benachrichtigung `POST_NOTIFICATIONS`. Bisher
 * fragte nur der Start einer Aufzeichnung danach (`ui/map`) — wer nie
 * aufzeichnete, konnte die Erinnerungen einschalten und bekam nie eine. Der
 * Hinweis samt Knopf unten holt die Freigabe deshalb dort, wo sie gebraucht
 * wird; das Muster stammt aus [OfflineRoutingCard], die es fuer die Ortung
 * ebenso macht.
 *
 * ## Speichern und Umplanen in einem Schritt
 * Jede Aenderung geht sofort an [AppViewModel.setReminderSettings] (Speichern)
 * **und** an [ReminderScheduler.reschedule] (naechster Termin) — mit demselben
 * Wert, nicht ueber einen erneuten Lesevorgang. Es gibt keinen
 * „Speichern"-Knopf: Ein Schalter, der erst nach einer Bestaetigung gilt, ist
 * in einer Einstellungsliste eine Falle.
 */
@Composable
fun ReminderCard(appViewModel: AppViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val settings by appViewModel.reminderSettings.collectAsStateWithLifecycle()
    val hintColor = MaterialTheme.colorScheme.onSurfaceVariant

    // Welche der beiden Uhrzeiten gerade im Dialog steht; `null` = kein Dialog.
    var editing by remember { mutableStateOf<ReminderTime?>(null) }

    // Eigener Zustand statt eines direkten Aufrufs im Rumpf: Die Antwort des
    // Systemdialogs loest von sich aus keine Recomposition aus — ohne diesen
    // Wert bliebe der Hinweis auch nach dem Erlauben stehen.
    var permissionGranted by remember { mutableStateOf(hasNotificationPermission(context)) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionGranted = granted || hasNotificationPermission(context)
        if (!granted) {
            appViewModel.showMessage(
                "Ohne diese Freigabe bleiben die Erinnerungen still. Erteilen lässt sie " +
                    "sich jederzeit unter „Einstellungen → Apps → Trailscape → " +
                    "Benachrichtigungen“.",
            )
        }
    }

    fun apply(next: ReminderSettings) {
        appViewModel.setReminderSettings(next)
        ReminderScheduler.reschedule(context, next)
    }

    MoreSectionCard(title = "Erinnerungen", modifier = modifier) {
        Text(
            text = "Trailscape kann dich an die heutige Einheit, den Wochenabschluss und " +
                "längere Pausen erinnern. Die Meldungen entstehen auf dem Gerät — es gibt " +
                "keinen Push-Dienst und kein Konto dahinter.",
            style = MaterialTheme.typography.bodySmall,
            color = hintColor,
        )
        Spacer(modifier = Modifier.height(12.dp))

        ReminderSwitchRow(
            title = "Tageseinheit",
            subtitle = "Morgens, was heute ansteht. Nur mit Trainingsplan.",
            checked = settings.dailySessionEnabled,
            onCheckedChange = { apply(settings.copy(dailySessionEnabled = it)) },
        )
        ReminderTimeRow(
            label = "Uhrzeit morgens",
            time = settings.dailySessionTime,
            enabled = settings.dailySessionEnabled || settings.nudgeEnabled,
            onClick = { editing = ReminderTime.DAILY },
        )

        Spacer(modifier = Modifier.height(8.dp))

        ReminderSwitchRow(
            title = "Wochenrückblick",
            subtitle = "Sonntagabends: gefahrene gegen geplante Kilometer.",
            checked = settings.weeklyReviewEnabled,
            onCheckedChange = { apply(settings.copy(weeklyReviewEnabled = it)) },
        )
        ReminderTimeRow(
            label = "Uhrzeit sonntags",
            time = settings.weeklyReviewTime,
            enabled = settings.weeklyReviewEnabled,
            onClick = { editing = ReminderTime.WEEKLY },
        )

        Spacer(modifier = Modifier.height(8.dp))

        ReminderSwitchRow(
            title = "Anstupser",
            subtitle = "Nach $reminderNudgeAfterDays Tagen ohne Aufzeichnung, höchstens " +
                "einmal pro Woche. Nutzt die Uhrzeit morgens.",
            checked = settings.nudgeEnabled,
            onCheckedChange = { apply(settings.copy(nudgeEnabled = it)) },
        )

        // Ein Schalter, der sich einschalten laesst und danach nichts tut, ist
        // eine Falle — und die Benachrichtigungs-Berechtigung war die einzige
        // der App, die nirgends dort angefragt wurde, wo man sie braucht (sie
        // kam nur nebenbei beim Start einer Aufzeichnung). Deshalb steht hier
        // derselbe Anfrageknopf wie in `OfflineRoutingCard` bei der Ortung:
        // erklaeren, was fehlt, und es an Ort und Stelle erledigen.
        if (settings.anyEnabled && !permissionGranted) {
            Spacer(modifier = Modifier.height(12.dp))
            NoticeBox(
                icon = Icons.Filled.Info,
                color = LocalSignalColors.current.warning,
                text = "Trailscape darf keine Benachrichtigungen anzeigen — die Erinnerungen " +
                    "bleiben deshalb still. Erlaube sie hier; nachträglich geht es auch " +
                    "in den Android-Einstellungen unter " +
                    "„Apps → Trailscape → Benachrichtigungen“.",
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = {
                    // Ab Android 13 gibt es die Laufzeit-Berechtigung; darunter
                    // sind Benachrichtigungen ohne Nachfrage erlaubt und der
                    // Zweig hier wird nie erreicht (siehe
                    // `hasNotificationPermission`).
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                },
            ) { Text("Benachrichtigungen erlauben") }
        }
    }

    editing?.let { target ->
        val initial = when (target) {
            ReminderTime.DAILY -> settings.dailySessionTime
            ReminderTime.WEEKLY -> settings.weeklyReviewTime
        }
        ReminderTimeDialog(
            initial = initial,
            onDismiss = { editing = null },
            onConfirm = { picked ->
                editing = null
                apply(
                    when (target) {
                        ReminderTime.DAILY -> settings.copy(dailySessionTime = picked)
                        ReminderTime.WEEKLY -> settings.copy(weeklyReviewTime = picked)
                    },
                )
            },
        )
    }
}

/** Welche der beiden Uhrzeiten gerade bearbeitet wird. */
private enum class ReminderTime { DAILY, WEEKLY }

/**
 * Eine Zeile mit Titel, Erklaerung und Schalter. Die ganze Zeile schaltet
 * (`toggleable` mit [Role.Switch]) — ein 32 dp breiter Schalter ist ein
 * unnoetig kleines Ziel, wenn daneben ohnehin nichts anderes liegt.
 */
@Composable
private fun ReminderSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        // onCheckedChange = null: Der Schalter meldet nicht selbst, die Zeile
        // tut es (sonst kaeme das Ereignis doppelt).
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** Zeile „Uhrzeit … 07:00" — die Zeit selbst ist der Knopf zum Dialog. */
@Composable
private fun ReminderTimeRow(
    label: String,
    time: LocalTime,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onClick, enabled = enabled) {
            Text(text = formatTime(time))
        }
    }
}

/**
 * Uhrzeit-Dialog. Bewusst [TimeInput] (Ziffernfelder) statt der runden
 * Uhr-Auswahl: Der Dialog steht in einer Liste von Einstellungen, und die
 * Uhr braucht in einem `AlertDialog` mehr Hoehe, als auf kleinen Geraeten
 * uebrig ist.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReminderTimeDialog(
    initial: LocalTime,
    onDismiss: () -> Unit,
    onConfirm: (LocalTime) -> Unit,
) {
    val state = rememberTimePickerState(
        initialHour = initial.hour,
        initialMinute = initial.minute,
        is24Hour = true,
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Uhrzeit") },
        text = { TimeInput(state = state) },
        confirmButton = {
            TextButton(onClick = { onConfirm(LocalTime.of(state.hour, state.minute)) }) {
                Text("Übernehmen")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Abbrechen") }
        },
    )
}
