package de.trailscape.wear.comm

import android.content.Context
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import de.trailscape.core.FAEHIGKEIT_TELEFON
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Duenne Bruecke zum gekoppelten Telefon ueber die Wear-Data-Layer-APIs
 * (`play-services-wearable`: `CapabilityClient`/`MessageClient`).
 *
 * Sowohl der Sensor-Kanal ([de.trailscape.wear.comm.SensorSender], Uhr →
 * Telefon unter [de.trailscape.core.PFAD_SENSOR]) als auch der Steuer-Kanal
 * ([de.trailscape.wear.record.RecordingService], Uhr → Telefon unter
 * [de.trailscape.core.PFAD_BEFEHL_AN_TELEFON]) suchen denselben Knoten auf
 * dieselbe Weise — deshalb eine einzige Stelle statt zwei beinahe
 * identischer `CapabilityClient`-Aufrufe.
 *
 * ## Warum "best effort" statt Fehlerbehandlung
 * Ein Telefon ausser Reichweite (WLAN/Bluetooth aus, andere Etage) ist waehrend
 * einer Ausfahrt der Normalfall, kein Ausnahmezustand. [sende] liefert dafuer
 * schlicht `false` zurueck und wirft nie — dieselbe Haltung wie
 * [de.trailscape.wear.record.RecordingService]s Umgang mit dem Akku (siehe
 * dortiger Klassen-KDoc): fehlende Zustellung ist ein erwarteter Betriebszustand,
 * kein Fehler, der eine Absturz- oder Retry-Logik verdient.
 */
object PhoneLink {

    /**
     * Naechster erreichbarer Knoten mit [FAEHIGKEIT_TELEFON], `null` wenn
     * gerade keiner erreichbar ist (oder die Abfrage selbst scheitert — ein
     * Play-Services-Ausfall ist hier kein Grund, die Aufzeichnung zu stoeren).
     */
    private suspend fun bestenKnotenSuchen(context: Context): Node? = try {
        val info = Wearable.getCapabilityClient(context.applicationContext)
            .getCapability(FAEHIGKEIT_TELEFON, CapabilityClient.FILTER_REACHABLE)
            .await()
        // `isNearby` (direkte Funkverbindung) vor einem nur ueber die Cloud
        // erreichbaren Knoten — bei einer Ausfahrt ist "in der Trikottasche"
        // der erwartete Fall, ein Cloud-Umweg nur der Nachzuegler dafuer.
        info.nodes.firstOrNull { it.isNearby } ?: info.nodes.firstOrNull()
    } catch (e: Exception) {
        null
    }

    /** Sendet [bytes] unter [pfad] ans naechste erreichbare Telefon. `false` bei fehlendem Knoten oder Sendefehler. */
    suspend fun sende(context: Context, pfad: String, bytes: ByteArray): Boolean {
        val knoten = bestenKnotenSuchen(context) ?: return false
        return try {
            Wearable.getMessageClient(context.applicationContext)
                .sendMessage(knoten.id, pfad, bytes)
                .await()
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Wie [sende], aber feuert in [scope] und ignoriert das Ergebnis — fuer
     * Aufrufer (Steuer-Echos, gepufferte Sensordaten), die weder auf
     * Zustellung warten koennen noch bei einem Fehlschlag etwas anderes taeten
     * als weiterzumachen.
     */
    fun sendeBestEffort(scope: CoroutineScope, context: Context, pfad: String, bytes: ByteArray) {
        scope.launch { sende(context, pfad, bytes) }
    }

    /**
     * Live-Strom, ob gerade ein Telefon mit [FAEHIGKEIT_TELEFON] erreichbar ist
     * — Grundlage der stillen Verbindungs-Unterzeile auf dem Startbildschirm.
     */
    fun verbindungsFluss(context: Context): Flow<Boolean> = callbackFlow {
        val client = Wearable.getCapabilityClient(context.applicationContext)
        val zuhoerer = CapabilityClient.OnCapabilityChangedListener { info ->
            trySend(info.nodes.isNotEmpty())
        }
        client.addListener(zuhoerer, FAEHIGKEIT_TELEFON)
        // `addListener` meldet erst die NAECHSTE Aenderung, nicht den
        // aktuellen Stand — ohne diese Abfrage bliebe die Unterzeile bis zur
        // ersten Verbindungsaenderung leer, obwohl das Telefon laengst da ist.
        launch {
            val anfangsstand = try {
                Wearable.getCapabilityClient(context.applicationContext)
                    .getCapability(FAEHIGKEIT_TELEFON, CapabilityClient.FILTER_REACHABLE)
                    .await()
                    .nodes
                    .isNotEmpty()
            } catch (e: Exception) {
                false
            }
            trySend(anfangsstand)
        }
        awaitClose { client.removeListener(zuhoerer, FAEHIGKEIT_TELEFON) }
    }.distinctUntilChanged()
}
