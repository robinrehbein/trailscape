package de.trailscape.app.reminder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import de.trailscape.app.MainActivity
import de.trailscape.app.R
import de.trailscape.app.ui.map.hasNotificationPermission
import de.trailscape.core.ReminderNotice

/**
 * Anzeige der Erinnerungen — Kanal, Benachrichtigung, Tippziel.
 *
 * ## Eigener Kanal, getrennt von der Aufzeichnung
 * Die Aufzeichnung haelt eine **dauerhafte** Benachrichtigung, die kein
 * Geraeusch machen darf und die man nicht wegwischen kann; die Erinnerungen
 * sind seltene Einzelmeldungen, die auffallen sollen. Im selben Kanal koennte
 * man das eine nicht abstellen, ohne das andere mitzunehmen — und Android
 * gibt die Kanalwahl bewusst der Nutzerin in die Hand (Ton, Vibration,
 * Wichtigkeit, komplett stummschalten). Deshalb [CHANNEL_ID] neben den beiden
 * Kanaelen des Aufzeichnungsdienstes.
 *
 * ## Berechtigung
 * Ab Android 13 braucht es `POST_NOTIFICATIONS`. Die App fragt diese
 * Berechtigung bereits an einer Stelle an — beim Start einer Aufzeichnung
 * (`ui/map/MapScreen.kt` ueber `missingPermissions`). Einen zweiten
 * Anfrageweg gibt es hier **absichtlich nicht**: Ein Hintergrundlauf kann
 * keinen Dialog zeigen, und eine Erinnerung ist kein Anlass, die App beim
 * naechsten Oeffnen mit einer Nachfrage zu begruessen. Fehlt die Berechtigung,
 * tut [show] still nichts und meldet das dem Aufrufer, damit der die Meldung
 * nicht als „gezeigt" vermerkt.
 */
internal object ReminderNotifications {

    /** Kanal-ID im `trailscape.*`-Namensraum wie die uebrigen Schluessel der App. */
    const val CHANNEL_ID: String = "trailscape.reminders"

    private const val NOTIFICATION_ID = 4711
    private const val REQUEST_OPEN = 41

    /**
     * Zeigt [notice] an.
     *
     * @return `false`, wenn nichts angezeigt werden konnte (fehlende
     *   Berechtigung, kein NotificationManager). Der Aufrufer merkt sich die
     *   Meldung dann nicht als erledigt.
     */
    fun show(context: Context, notice: ReminderNotice): Boolean {
        if (!hasNotificationPermission(context)) return false
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        ensureChannel(context, manager)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(notice.title)
            .setContentText(notice.text)
            // BigText, damit der Anstupser nicht nach zwei Dritteln abgeschnitten
            // wird; die kurzen Meldungen sehen dadurch unveraendert aus.
            .setStyle(NotificationCompat.BigTextStyle().bigText(notice.text))
            // Wie beim Aufzeichnungsdienst ein System-Symbol statt eigener
            // Zeichensaetze: Die kleine Statusleisten-Grafik wird ohnehin
            // eingefaerbt und maskiert.
            .setSmallIcon(android.R.drawable.ic_menu_my_calendar)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(openTodayIntent(context))
            .build()

        return try {
            // Feste ID: Eine neue Erinnerung ersetzt eine noch offene alte.
            // Drei Anlaesse, die sich in der Leiste stapeln, waeren genau die
            // Sammlung ungelesener Hinweise, die niemand will.
            manager.notify(NOTIFICATION_ID, notification)
            true
        } catch (e: Exception) {
            // Kann der Hersteller-Launcher/das System ablehnen; eine
            // ausgefallene Erinnerung ist kein Grund, den Lauf scheitern zu
            // lassen.
            false
        }
    }

    /**
     * Legt den Kanal an (idempotent — `createNotificationChannel` aktualisiert
     * einen vorhandenen, ohne die Einstellungen der Nutzerin zu ueberschreiben).
     *
     * `IMPORTANCE_DEFAULT`: Die Meldung darf einmal am Tag hoerbar sein, aber
     * nicht als Vollbild-Einblendung ueber allem liegen — das bleibt echten
     * Alarmen vorbehalten.
     */
    private fun ensureChannel(context: Context, manager: NotificationManager) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.reminder_notification_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        channel.description = context.getString(R.string.reminder_notification_channel_description)
        manager.createNotificationChannel(channel)
    }

    /**
     * Tippen oeffnet die App auf dem „Heute"-Tab.
     *
     * Bewusst ein **expliziter** Intent auf [MainActivity] und nicht der
     * Launcher-Intent, den der Aufzeichnungsdienst benutzt: Der Launcher-Intent
     * holt eine bereits laufende App nur nach vorne, ohne `onNewIntent` und
     * damit ohne Extras — die App bliebe auf dem Tab stehen, auf dem sie
     * zuletzt war. `SINGLE_TOP` + `CLEAR_TOP` stellt zu, dass die vorhandene
     * Instanz den Intent samt [EXTRA_OPEN_TODAY] tatsaechlich bekommt.
     */
    private fun openTodayIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP,
            )
            .putExtra(EXTRA_OPEN_TODAY, true)

        return PendingIntent.getActivity(
            context,
            REQUEST_OPEN,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

/**
 * Extra, mit dem eine Erinnerung die [MainActivity] bittet, den „Heute"-Tab zu
 * zeigen. Ausgewertet wird es dort (siehe `MainActivity.onCreate`/`onNewIntent`).
 */
const val EXTRA_OPEN_TODAY: String = "de.trailscape.app.reminder.OPEN_TODAY"
