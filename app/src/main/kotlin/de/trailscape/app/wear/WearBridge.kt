package de.trailscape.app.wear

import android.content.Context
import android.os.SystemClock
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import de.trailscape.app.record.RecordingRepository
import de.trailscape.core.AufzeichnungsZustand
import de.trailscape.core.DiagEvent
import de.trailscape.core.DiagLog
import de.trailscape.core.FAEHIGKEIT_UHR
import de.trailscape.core.PFAD_ZUSTAND
import de.trailscape.core.kodiereAufzeichnungsZustand

/**
 * Telefon-seitiges Gegenstueck zur Wear-OS-Datenschicht — der einzige Ort in
 * `:app`, an dem `com.google.android.gms.wearable.*` (Data-Layer-API:
 * `CapabilityClient`/`MessageClient`) auftaucht.
 *
 * ## Warum ueberhaupt eine proprietaere Abhaengigkeit
 * Trailscape ist sonst bewusst frei von `com.google.android.gms`-Artefakten
 * (siehe README, Abschnitt „Lizenz" — `play-services-location` wurde genau
 * deshalb entfernt). Bei der Uhr-Kopplung gibt es aber, anders als bei der
 * Standortbestimmung, keine Alternative auf Betriebssystemebene: Der
 * Nachrichtenaustausch zwischen einer Telefon-App und einer Wear-OS-3+-App
 * (`MessageClient`/`CapabilityClient`) LAEUFT ausschliesslich ueber Google
 * Play Services — es gibt keinen zweiten Transportweg. Diese eine
 * Abhaengigkeit ist damit eine bewusste Ausnahme fuer genau dieses Merkmal,
 * keine Abkehr vom Grundsatz; sie gehoert vor dem naechsten Release in die
 * Lizenzuebersicht (`OpenSourceNotices.kt`) und ins README aufgenommen.
 *
 * ## Aufbau
 *  * [attach] registriert einen [CapabilityClient]-Listener auf
 *    [FAEHIGKEIT_UHR] und spiegelt dessen Ergebnis nach
 *    [RecordingRepository.watchConnected] — aufgerufen sowohl von
 *    [WearListenerService.onCreate] (jede eingehende Nachricht darf die
 *    Bruecke aufwecken) als auch von `RecordingService.onCreate` (eine
 *    Aufzeichnung soll den Zustand der Kopplung so frueh wie moeglich
 *    kennen). Mehrfachaufrufe sind unschaedlich (siehe der Guard unten).
 *  * [sendZustand] schickt den aktuellen [AufzeichnungsZustand] an alle
 *    gerade erreichbaren Uhr-Knoten. Aufrufer ist `RecordingService`, und
 *    zwar sowohl bei jeder Zustandsaenderung (Start/Pause/Weiter/Stop) als
 *    auch angehaengt an dessen bestehenden 5-Sekunden-Notification-Takt —
 *    eine eigene Drosselung braucht dieser Aufruf deshalb nicht.
 *
 * Beide Methoden schlucken jede Ausnahme: Ein Geraet ohne Play Services, ohne
 * gekoppelte Uhr oder mit vorruebergehend gestoerter Verbindung darf die
 * Aufzeichnung nie zum Absturz bringen — dieselbe Haltung wie ueberall sonst
 * in `record/` gegenueber Notifications und Journal-IO.
 */
object WearBridge {

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var erreichbareKnotenIds: Set<String> = emptySet()

    private val capabilityListener = CapabilityClient.OnCapabilityChangedListener { info ->
        aktualisiereErreichbareKnoten(info)
    }

    /** Ob der Capability-Listener erfolgreich angehaengt ist. */
    @Volatile
    private var attached = false

    /** Zeitpunkt (elapsedRealtime) des letzten gescheiterten Versuchs. */
    @Volatile
    private var letzterFehlversuchMs: Long = 0

    private val attachLock = Any()

    /**
     * Mindestabstand zwischen zwei Anlaufversuchen nach einem Fehlschlag.
     * [sendZustand] ruft [attach] im 5-Sekunden-Takt; auf einem Geraet ohne
     * Play Services soll nicht jeder Takt erneut eine Ausnahme erzeugen.
     */
    private const val WIEDERHOLUNG_NACH_MS = 60_000L

    /**
     * Registriert die Bruecke fuer den Prozess. Idempotent — ein zweiter
     * Aufruf (aus einer zweiten Einstiegsstelle, siehe Klassendoc) haengt
     * keinen zweiten Listener an.
     *
     * Als erledigt gilt der Aufruf erst, wenn der Listener wirklich haengt.
     * Frueher wurde der Kontext schon **vor** dem Versuch gesetzt und diente
     * zugleich als Guard — ein einziger Fehlschlag (Play Services gerade im
     * Update) hat die Uhr-Kopplung damit bis zum Prozessende abgeschaltet.
     * Jetzt wird nach einem Fehlschlag fruehestens nach
     * [WIEDERHOLUNG_NACH_MS] erneut versucht.
     */
    fun attach(context: Context) {
        if (attached) return
        val ctx = context.applicationContext
        synchronized(attachLock) {
            if (attached) return
            appContext = ctx
            val jetzt = SystemClock.elapsedRealtime()
            if (letzterFehlversuchMs != 0L && jetzt - letzterFehlversuchMs < WIEDERHOLUNG_NACH_MS) {
                return
            }
            try {
                val client = Wearable.getCapabilityClient(ctx)
                client.addListener(capabilityListener, FAEHIGKEIT_UHR)
                // Ab hier haengt der Listener; ein zweiter addListener waere
                // ein Duplikat, egal wie die Abfrage unten ausgeht.
                attached = true
                client.getCapability(FAEHIGKEIT_UHR, CapabilityClient.FILTER_REACHABLE)
                    .addOnSuccessListener { info -> aktualisiereErreichbareKnoten(info) }
                    .addOnFailureListener { e ->
                        DiagLog.shared.log(DiagEvent.WEAR_CAPABILITY_FAILED, error = e)
                    }
            } catch (e: Exception) {
                // Kein Play-Services-Geraet (z. B. AOSP/F-Droid-Referenzgeraet ohne
                // GMS) — die App bleibt ohne Uhr-Kopplung voll nutzbar.
                letzterFehlversuchMs = jetzt
                DiagLog.shared.log(DiagEvent.WEAR_BRIDGE_UNAVAILABLE, error = e)
            }
        }
    }

    private fun aktualisiereErreichbareKnoten(info: CapabilityInfo) {
        val nodes: Set<Node> = info.nodes
        erreichbareKnotenIds = nodes.map { it.id }.toSet()
        RecordingRepository.publishWatchConnected(nodes.isNotEmpty())
    }

    /**
     * Schickt [zustand] an alle gerade erreichbaren Uhr-Knoten. Ohne
     * erreichbare Uhr ein Nop — es fehlt dann schlicht ein Empfaenger.
     */
    fun sendZustand(context: Context, zustand: AufzeichnungsZustand) {
        attach(context)
        val knotenIds = erreichbareKnotenIds
        if (knotenIds.isEmpty()) return
        val ctx = appContext ?: context.applicationContext
        val bytes = kodiereAufzeichnungsZustand(zustand)
        try {
            val messageClient = Wearable.getMessageClient(ctx)
            knotenIds.forEach { id -> messageClient.sendMessage(id, PFAD_ZUSTAND, bytes) }
        } catch (e: Exception) {
            // Fire-and-forget: Der naechste Takt (siehe Klassendoc) versucht es
            // ohnehin in wenigen Sekunden erneut.
            // Die Wiederholungsbremse des DiagLog verhindert, dass der Takt
            // das Log mit identischen Eintraegen flutet.
            DiagLog.shared.log(DiagEvent.WEAR_SEND_FAILED, error = e)
        }
    }
}
