package de.trailscape.wear.glance

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Datums-, Zeitzonen- und Sprachwechsel: Kachel und Komplikation neu
 * zeichnen lassen.
 *
 * Besonders der Datumswechsel zaehlt: Am Montag beginnt eine neue Woche, und
 * die Uhr darf dann nicht weiter „diese Woche geschafft" zeigen, nur weil das
 * Telefon noch nichts Neues gesendet hat (siehe `glanceFuerHeute` in `:core`).
 * Der Empfaenger wird ohne App-Start vom System geweckt — dieselben drei
 * Ereignisse sind vom Verbot impliziter Broadcasts ausgenommen und stehen im
 * Telefon-Widget genauso im Manifest.
 */
class TagWechselReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_DATE_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_LOCALE_CHANGED,
            -> GlanceUpdater.aktualisiereAnzeigen(context)
        }
    }
}
