package de.trailscape.app.reminder

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import de.trailscape.app.data.AppServices
import de.trailscape.core.dueReminder
import de.trailscape.core.loadPlan
import java.time.LocalDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Der eine Hintergrundlauf der Erinnerungen: Daten holen, `:core` fragen,
 * anzeigen, Stand merken, naechsten Termin setzen.
 *
 * **Hier wird nichts entschieden.** Ob und was zu melden ist, sagt
 * [dueReminder] — eine reine Funktion in `:core` mit Tests
 * (`RemindersTest.kt`). Dieser Worker enthaelt deshalb keinen einzigen
 * Vergleich auf Wochentag, Uhrzeit oder Ruhetag; er ist die Verkabelung
 * ringsherum und damit das, was ohne Geraet ohnehin nicht zu pruefen waere.
 *
 * ## Warum der Lauf immer „erfolgreich" ist
 * Es gibt nichts zu wiederholen: Alle Daten liegen lokal, ein Fehlschlag
 * waere ein Programmierfehler und kein voruebergehendes Problem. Ein
 * [Result.retry] wuerde nur einen zweiten Termin neben den geplanten legen —
 * und im schlimmsten Fall die Meldung doppelt zeigen. Faellt ein Lauf aus,
 * uebernimmt der naechste (siehe die Nachhol-Regel in [dueReminder]).
 *
 * ## Reihenfolge im Rumpf
 * Die Neuausrichtung des naechsten Termins steht bewusst **am Ende**: Sie
 * reiht die laufende Arbeit neu ein und beendet damit diesen Durchgang: Alles
 * Wesentliche muss vorher passiert sein.
 */
internal class ReminderWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        runCatching {
            // Der Worker kann in einem Prozess starten, dessen Application
            // gerade erst hochgefahren ist; `init` ist idempotent (siehe
            // AppServices) und kostet nichts.
            AppServices.init(applicationContext)

            val store = AppServices.reminderStore
            val settings = store.readSettings()
            if (!settings.anyEnabled) {
                // Alles abgeschaltet, waehrend die Arbeit schon eingeplant war
                // (z. B. Einstellungen aus einem Backup): still aufraeumen.
                ReminderScheduler.reschedule(applicationContext, settings)
                return@runCatching
            }

            val now = LocalDateTime.now()
            val state = store.readState()

            val notice = dueReminder(
                now = now,
                settings = settings,
                state = state,
                plan = loadPlan(AppServices.trainingPlanStore),
                rides = AppServices.rideStorage.listRides(),
            )

            if (notice != null && ReminderNotifications.show(applicationContext, notice)) {
                // Nur eine tatsaechlich sichtbare Meldung gilt als erledigt:
                // Fehlt die Benachrichtigungsberechtigung, soll die Erinnerung
                // nicht lautlos „verbraucht" werden.
                store.writeState(state.markDelivered(notice.kind, now.toLocalDate()))
            }

            ReminderScheduler.reschedule(applicationContext, settings, now)
        }

        Result.success()
    }
}
