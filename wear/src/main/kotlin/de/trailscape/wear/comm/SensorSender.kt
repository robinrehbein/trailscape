package de.trailscape.wear.comm

import android.content.Context
import de.trailscape.core.PFAD_SENSOR
import de.trailscape.core.SensorBatch
import de.trailscape.core.SensorSample
import de.trailscape.core.kodiereSensorBatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Puffert [SensorSample]s und sendet sie alle [SENDE_INTERVALL_MS] als EIN
 * [SensorBatch] an das Telefon — nie eine Nachricht pro Probe.
 *
 * Bei rund 1 Probe/s waeren das sonst 3× so viele Data-Layer-Nachrichten wie
 * noetig: Jede Nachricht kostet einen eigenen Bluetooth-Funkstoss, und genau
 * das Sparen daran war schon der Grund fuer den ganzen `SensorBatch`-Typ in
 * `:core` (siehe dortiger Klassen-KDoc in WearProtocol.kt).
 *
 * Ist kein Telefon erreichbar, wird der Puffer bis [MAX_PUFFER] Eintraege
 * weiter gefuellt und danach von vorne verworfen (aelteste Probe zuerst) —
 * still, ohne Fehler nach aussen. Eine Ausfahrt mit laengerem Funkloch soll
 * weiterlaufen, nicht abbrechen; dass ein paar Minuten Sensordaten dabei
 * verlorengehen, ist der Preis dafuer und unvermeidlich, solange nur im RAM
 * gepuffert wird (das dauerhafte Protokoll liegt in [de.trailscape.wear.record.RecordingJournal]s
 * schlankem Nachfolger — bewusst NICHT hier: siehe [de.trailscape.wear.record.RecordingService]).
 */
internal class SensorSender(private val context: Context, private val scope: CoroutineScope) {

    private val puffer = ArrayDeque<SensorSample>()
    private var job: Job? = null

    /** Reiht eine Probe ein. Threadsicher, weil Health-Services-Callback und Sendeschleife unabhaengig laufen. */
    fun probe(sample: SensorSample) {
        synchronized(this) {
            puffer.addLast(sample)
            while (puffer.size > MAX_PUFFER) puffer.removeFirst()
        }
    }

    /** Startet die periodische Sendeschleife. Idempotent — ein zweiter Aufruf ersetzt die laufende Schleife. */
    fun starte() {
        job?.cancel()
        job = scope.launch {
            while (isActive) {
                delay(SENDE_INTERVALL_MS)
                leereUndSende()
            }
        }
    }

    /** Stoppt die Sendeschleife UND leert den Puffer — ein neuer Versuch soll nicht mit Proben der letzten Fahrt beginnen. */
    fun stoppe() {
        job?.cancel()
        job = null
        synchronized(this) { puffer.clear() }
    }

    private suspend fun leereUndSende() {
        val proben = synchronized(this) {
            if (puffer.isEmpty()) return
            val kopie = puffer.toList()
            puffer.clear()
            kopie
        }
        // Ergebnis bewusst ignoriert: Ob ein Telefon erreichbar war oder
        // nicht, aendert nichts am weiteren Ablauf (siehe Klassen-KDoc).
        PhoneLink.sende(context, PFAD_SENSOR, kodiereSensorBatch(SensorBatch(proben)))
    }

    companion object {
        /** Abstand zwischen zwei gesendeten Batches. */
        private const val SENDE_INTERVALL_MS = 3_000L

        /**
         * Obergrenze des RAM-Puffers ohne erreichbares Telefon — bei rund
         * 1 Probe/s mehrere Minuten, genug um ein kurzes Funkloch (Tunnel,
         * Aufzug) zu ueberbruecken, ohne bei einer laengeren Funkstille
         * unbegrenzt Speicher zu binden.
         */
        private const val MAX_PUFFER = 180
    }
}
