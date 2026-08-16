package de.trailscape.wear.comm

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import de.trailscape.core.PFAD_BEFEHL_AN_UHR
import de.trailscape.core.PFAD_ZUSTAND
import de.trailscape.core.dekodiereAufzeichnungsZustand
import de.trailscape.core.dekodiereBefehl
import de.trailscape.wear.record.RecordingStatus

/**
 * Empfaengt, was das Telefon an die Uhr schickt: Steuerbefehle
 * ([PFAD_BEFEHL_AN_UHR] — Start/Pause/Weiter/Stopp) und den Anzeigezustand
 * ([PFAD_ZUSTAND] — Dauer/Distanz/HF, waehrend die Aufzeichnung auf dem
 * Telefon laeuft).
 *
 * Im Manifest deklariert, nicht nur zur Laufzeit registriert: Nur so startet
 * das System diesen Dienst zuverlaessig, wenn eine Nachricht ankommt, waehrend
 * auf der Uhr gerade keine Activity offen ist — ein "Pause" vom Telefon aus
 * darf nicht verlorengehen, nur weil niemand gerade auf die Uhr schaut.
 *
 * ## Warum hier abgefangen wird, obwohl `dekodiere*` bewusst ungeschuetzt wirft
 * [de.trailscape.core.dekodiereBefehl]/[de.trailscape.core.dekodiereAufzeichnungsZustand]
 * werfen absichtlich ungeschuetzt (siehe WearProtocol.kt) — das ist richtig
 * fuer einen expliziten Aufruf wie in `SyncClient.kt`, wo eine Nutzerin einen
 * Fehler sehen und reagieren kann. Hier ist der Aufrufer aber das System
 * selbst, ausgeloest ohne jede Nutzerinteraktion: Eine kaputte oder von einer
 * neueren `:app`-Version stammende Nutzlast wuerde sonst bei JEDER weiteren
 * Nachricht denselben Absturz wiederholen. Deshalb genau an dieser
 * Systemgrenze faengt der Aufrufer ab, statt den Fehler weiterzureichen.
 */
class CommandListenerService : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        runCatching {
            when (event.path) {
                PFAD_BEFEHL_AN_UHR ->
                    RecordingStatus.wendeFerncodeAn(applicationContext, dekodiereBefehl(event.data).cmd)

                PFAD_ZUSTAND ->
                    RecordingStatus.wendeTelefonZustandAn(dekodiereAufzeichnungsZustand(event.data))
            }
        }
    }
}
