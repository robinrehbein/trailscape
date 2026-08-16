package de.trailscape.app.wear

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import de.trailscape.app.record.RecordingRepository
import de.trailscape.core.Befehl
import de.trailscape.core.PFAD_BEFEHL_AN_TELEFON
import de.trailscape.core.PFAD_SENSOR
import de.trailscape.core.dekodiereBefehl
import de.trailscape.core.dekodiereSensorBatch

/**
 * Empfangsseite der Handy-Bruecke: nimmt entgegen, was die Uhr ueber die
 * Wear-OS-Datenschicht schickt ([PFAD_SENSOR], [PFAD_BEFEHL_AN_TELEFON]).
 *
 * Registriert ueber den Intent-Filter `com.google.android.gms.wearable.BIND_LISTENER`
 * im Manifest (siehe dort) — Play Services erzeugt und bindet diese
 * Service-Instanz bei Bedarf selbst, auch wenn `:app` gerade nicht laeuft.
 * Genau deshalb enthaelt diese Klasse selbst KEINEN Zustand: Jede Instanz ist
 * kurzlebig und kann jederzeit neu erzeugt werden. Der einzige geteilte
 * Anlaufpunkt fuer das, was hier ankommt, ist [RecordingRepository]
 * (Sensorproben, Herzfrequenz) bzw. direkt [RecordingRepository]s
 * Start/Pause/Weiter/Stop-Kommandos (Befehle der Uhr).
 *
 * Kaputte oder unbekannte Nachrichten werden verworfen, nicht durchgereicht:
 * `dekodiereSensorBatch`/`dekodiereBefehl` werfen bei ungueltigem JSON
 * absichtlich ungeschuetzt (siehe `WearProtocol.kt`) — diese Klasse ist der
 * Rand des Systems, an dem eine kaputte Bluetooth-Uebertragung nicht die App
 * mitreissen darf.
 */
class WearListenerService : WearableListenerService() {

    private val tag = "WearListenerService"

    override fun onCreate() {
        super.onCreate()
        WearBridge.attach(this)
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        when (messageEvent.path) {
            PFAD_SENSOR -> verarbeiteSensorBatch(messageEvent.data)
            PFAD_BEFEHL_AN_TELEFON -> verarbeiteBefehl(messageEvent.data)
        }
    }

    private fun verarbeiteSensorBatch(bytes: ByteArray) {
        val batch = try {
            dekodiereSensorBatch(bytes)
        } catch (e: Exception) {
            Log.w(tag, "Kaputtes Sensor-Paket von der Uhr verworfen: $e")
            return
        }
        batch.samples.forEach { RecordingRepository.offerWatchSample(it) }
    }

    /**
     * Setzt einen Befehl der Uhr eins zu eins auf die bestehenden
     * [RecordingRepository]-Kommandos um — denselben Weg, den auch die
     * Notification-Aktionen und die Compose-Oberflaeche nehmen. Ein
     * fehlerhafter START (fehlende Berechtigung, Standort aus) bricht hier
     * nichts ab: `RecordingRepository.start` startet defensiv einen
     * Vordergrunddienst, der sich bei fehlender Berechtigung selbst mit
     * `laeuft=false` zurueckmeldet (siehe `RecordingService.enterForeground`)
     * statt abzustuerzen — die naechste Aufzeichnungszustand-Meldung an die
     * Uhr traegt das dann von selbst nach.
     */
    private fun verarbeiteBefehl(bytes: ByteArray) {
        val befehl = try {
            dekodiereBefehl(bytes)
        } catch (e: Exception) {
            Log.w(tag, "Kaputter Befehl von der Uhr verworfen: $e")
            return
        }
        when (befehl.cmd) {
            Befehl.START -> RecordingRepository.start(applicationContext)
            Befehl.PAUSE -> RecordingRepository.pause()
            Befehl.WEITER -> RecordingRepository.resume()
            Befehl.STOPP -> RecordingRepository.stop()
        }
    }
}
