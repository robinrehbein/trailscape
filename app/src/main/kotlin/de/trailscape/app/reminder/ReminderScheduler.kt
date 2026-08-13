package de.trailscape.app.reminder

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import de.trailscape.core.ReminderSettings
import de.trailscape.core.nextReminderRun
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * # Der Zeitplan der Erinnerungen
 *
 * ## Genau eine Arbeit, nicht drei
 * Es gibt eine einzige, eindeutig benannte periodische Arbeit ([WORK_NAME]).
 * Sie weiss beim Auslaufen selbst, welcher der drei Anlaesse faellig ist —
 * das entscheidet [de.trailscape.core.dueReminder] in `:core`. Drei getrennte
 * Arbeiten wuerden dreimal denselben Plan und dieselben Touren einlesen und
 * muessten sich obendrein untereinander abstimmen, damit nicht zwei
 * Benachrichtigungen gleichzeitig auflaufen.
 *
 * ## Keine exakten Weckzeiten
 * Bewusst [PeriodicWorkRequestBuilder] und **kein** `AlarmManager` mit
 * `setExactAndAllowWhileIdle`: Exakte Alarme kosten ab Android 12/13 eine
 * eigene Berechtigung (`SCHEDULE_EXACT_ALARM`/`USE_EXACT_ALARM`), wecken das
 * Geraet aus dem Doze und sind fuer eine Erinnerung, die genauso gut fuenf
 * oder zwanzig Minuten spaeter kommen darf, schlicht das falsche Werkzeug.
 * WorkManager buendelt den Lauf mit anderer Systemarbeit; eine Abweichung von
 * einigen Minuten (im Doze auch laenger) ist eingeplant. Ein verspaeteter Lauf
 * verliert nichts: [de.trailscape.core.dueReminder] holt eine Meldung bis
 * Mitternacht desselben Tages nach.
 *
 * ## Warum der Lauf sich nach jedem Mal neu ausrichtet
 * Ein fester 24-Stunden-Rhythmus kann nur **eine** Tageszeit treffen. Die
 * Tageseinheit haengt aber an der Morgen-Uhrzeit und die Wochenrueckschau am
 * Sonntagabend — zwei verschiedene Zeitpunkte. Deshalb wird der naechste Lauf
 * jedes Mal neu berechnet ([nextReminderRun]) und die Arbeit mit dieser
 * Wartezeit neu eingereiht ([ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE]).
 * Der Ein-Tages-Rhythmus bleibt als Sicherheitsnetz darunter: Faellt die
 * Neuausrichtung einmal aus, laeuft die Arbeit trotzdem weiter und meldet
 * spaetestens am naechsten Tag wieder.
 *
 * ## Neustart des Geraets
 * Dafuer ist **nichts** zu tun, und genau das wurde geprueft: WorkManager legt
 * seine Warteschlange in einer eigenen Datenbank ab und bringt in seiner
 * Bibliotheks-`AndroidManifest.xml` sowohl `RECEIVE_BOOT_COMPLETED` als auch
 * einen Empfaenger fuer `BOOT_COMPLETED` mit, der alle gespeicherten Arbeiten
 * nach dem Hochfahren neu einplant. Ein eigener `BootReceiver` waere
 * Doppelarbeit — die App deklariert deshalb weder die Berechtigung noch einen
 * Empfaenger selbst.
 *
 * ## Ohne Netz, ohne Nutzeraktion
 * Es werden **keine** [androidx.work.Constraints] gesetzt: Plan und Touren
 * liegen lokal, es gibt nichts zu laden. Eine Netz-Bedingung wuerde die
 * Erinnerung auf dem Berg ohne Empfang genau dann ausfallen lassen, wenn sie
 * gebraucht wird.
 */
internal object ReminderScheduler {

    /** Eindeutiger Name der Arbeit — im `trailscape.*`-Namensraum wie die Speicherschluessel. */
    const val WORK_NAME: String = "trailscape.reminders"

    /**
     * Richtet die Arbeit auf den naechsten faelligen Zeitpunkt aus — oder
     * raeumt sie ab, wenn alle drei Anlaesse aus sind.
     *
     * Wird bei jedem App-Start, nach jeder Aenderung in der Einstellungskarte
     * und am Ende jedes Laufs aufgerufen. Mehrfachaufrufe sind unschaedlich:
     * Der eindeutige Name sorgt dafuer, dass immer nur eine Arbeit existiert.
     *
     * @param now bewusst als Parameter, damit die Wartezeit gegen denselben
     *   Zeitpunkt gerechnet wird, den der aufrufende Lauf schon benutzt hat.
     */
    fun reschedule(
        context: Context,
        settings: ReminderSettings,
        now: LocalDateTime = LocalDateTime.now(),
    ) {
        val manager = WorkManager.getInstance(context.applicationContext)

        val next = nextReminderRun(now, settings)
        if (next == null) {
            manager.cancelUniqueWork(WORK_NAME)
            return
        }

        // Wanduhrzeit → absoluter Zeitpunkt. `atZone` loest dabei auch den
        // Sonderfall der Zeitumstellung auf: Eine in der Umstellungsnacht
        // uebersprungene Uhrzeit rueckt auf den ersten existierenden Zeitpunkt
        // danach.
        val nextMs = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val delayMs = (nextMs - System.currentTimeMillis()).coerceAtLeast(0L)

        val request = PeriodicWorkRequestBuilder<ReminderWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .build()

        manager.enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE,
            request,
        )
    }
}
