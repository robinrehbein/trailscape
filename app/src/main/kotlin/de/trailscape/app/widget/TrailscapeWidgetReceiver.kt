package de.trailscape.app.widget

import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.updateAll
import de.trailscape.app.data.AppServices
import kotlinx.coroutines.launch

/**
 * Der Empfaenger, ueber den der Launcher das Widget anlegt, aktualisiert und
 * entfernt (im Manifest als `<receiver>` mit dem `appwidget-provider`-Eintrag
 * `trailscape_widget_info`).
 *
 * Zusaetzlich zu den Standard-Aktualisierungen hoert er auf drei Systemereignisse,
 * die ohne App-Start passieren und das Bild aendern: **Datumswechsel** (neuer
 * Tag = ein Tag weniger fuer die Serie, Montag = neue Woche und neues Ziel),
 * **Zeitzonenwechsel** (verschiebt, welcher Tag „heute" ist) und
 * **Sprachwechsel**. Der Takt von 30 Minuten aus `trailscape_widget_info.xml`
 * fing das erst bis zu einer halben Stunde spaeter ein — nach Mitternacht soll
 * aber nicht noch das „Noch 1 Tag" von gestern stehen. Alle drei sind von der
 * Beschraenkung impliziter Broadcasts ausgenommen und duerfen deshalb im
 * Manifest stehen.
 */
class TrailscapeWidgetReceiver : GlanceAppWidgetReceiver() {

    override val glanceAppWidget: GlanceAppWidget = TrailscapeWidget()

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            Intent.ACTION_DATE_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_LOCALE_CHANGED,
            -> WidgetUpdater.request(context)
        }
    }
}

/**
 * Der eine Einstieg, um das Widget neu zeichnen zu lassen.
 *
 * Aufrufer: [de.trailscape.app.TrailscapeApplication] (nach jeder gespeicherten
 * Aufzeichnung und wenn die App in den Hintergrund geht — dort steht, warum
 * genau dort) und [TrailscapeWidgetReceiver]. Kostet ohne platziertes Widget
 * fast nichts: [updateAll] sucht die Instanzen und kehrt ohne Treffer zurueck.
 *
 * Laeuft auf dem App-Scope und faengt Fehler selbst ab — eine Aktualisierung
 * des Widgets darf nie den Aufrufer (Speichern einer Tour!) mitreissen.
 */
object WidgetUpdater {
    fun request(context: Context) {
        val app = context.applicationContext
        AppServices.appScope.launch {
            runCatching { TrailscapeWidget().updateAll(app) }
        }
    }
}
