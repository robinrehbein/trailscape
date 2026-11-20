package de.trailscape.wear.glance

import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.WearableListenerService
import de.trailscape.core.PFAD_WOCHE
import de.trailscape.core.dekodiereWochenGlance

/**
 * Empfaengt den Wochenstand des Telefons ([PFAD_WOCHE], DataItem), merkt ihn
 * ([GlanceStore]) und laesst Kachel und Komplikation neu zeichnen.
 *
 * Eigener Dienst statt eines weiteren Filters im `CommandListenerService`:
 * Der haengt an der Aufzeichnung (Nachrichten), dieser an Daten; so bleibt die
 * Aufzeichnung unberuehrt, und ein Fehler hier kann dort nichts treffen. Im
 * Manifest mit eigenem `DATA_CHANGED`-Filter, damit das System ihn auch
 * startet, wenn die App gar nicht laeuft.
 *
 * Kaputte, fremde oder zu neue Nutzlasten dekodieren zu `null` und werden
 * uebergangen — der letzte gute Stand bleibt stehen.
 */
class WochenListenerService : WearableListenerService() {

    override fun onDataChanged(events: DataEventBuffer) {
        runCatching {
            // Alles sofort in Werte kopieren: Der Puffer gehoert dem System.
            val neu = events
                .filter { it.type == DataEvent.TYPE_CHANGED && it.dataItem.uri.path == PFAD_WOCHE }
                .mapNotNull { event -> event.dataItem.data?.let(::dekodiereWochenGlance) }
                .lastOrNull()
            if (neu != null && GlanceStore.speichern(applicationContext, neu)) {
                GlanceUpdater.aktualisiereAnzeigen(applicationContext)
            }
        }
    }
}
