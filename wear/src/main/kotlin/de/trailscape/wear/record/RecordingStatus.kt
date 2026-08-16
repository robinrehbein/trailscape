package de.trailscape.wear.record

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.health.services.client.HealthServices
import de.trailscape.core.AufzeichnungsZustand
import de.trailscape.core.Befehl
import de.trailscape.wear.exercise.FaehigkeitsBericht
import de.trailscape.wear.exercise.ermittleFaehigkeiten
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Beobachtbarer Aufzeichnungszustand — die Bruecke zwischen [RecordingService]
 * und der Compose-Oberflaeche, im Aufbau bewusst wie
 * `de.trailscape.app.record.RecordingRepository` auf der Telefon-Seite.
 *
 * Wie dort gilt: Diese Flows sind ein Spiegel fuer die Anzeige, nicht die
 * Quelle der Wahrheit — die ist [RecordingService]s laufende Health-Services-
 * Uebung. Stirbt der Prozess, verschwinden diese Flows; die naechste
 * `MainActivity` sieht wieder [Phase.UNBEKANNT], bis der Dienst (falls er
 * noch laeuft) sich neu meldet.
 *
 * ## Zwei Herkuenfte fuer dieselben Zahlen
 * [laufzeitMs]/[distanzKm]/[letzteHfBpm] werden sowohl vom lokalen
 * [RecordingService] (eigene Health-Services-Messung) als auch — ueber
 * [wendeTelefonZustandAn] — vom Telefon befuellt, sobald dessen periodischer
 * [AufzeichnungsZustand] eintrifft. Letzterer gewinnt bei jedem Empfang
 * einfach durch spaeteres Schreiben: Das Telefon kennt die ueber
 * `LocationFusion` aus BEIDEN Quellen (Telefon + Uhr) verschmolzene Strecke,
 * die lokale Uhr-Zahl ist nur die eigene Naeherung dafuer, wie gut sie gerade
 * ohne Telefon-Echo waere. Bei guter Verbindung ueberschreibt das Telefon die
 * Uhr-Zahl praktisch sofort wieder; bei einem Funkloch bleibt die letzte
 * lokale Messung stehen — in beiden Faellen zeigt die Uhr die beste gerade
 * verfuegbare Zahl, ohne dass die Anzeige zwischen zwei Werten "springt".
 *
 * ## Gestartet von der Uhr ODER vom Telefon
 * Diese Symmetrie ist Absicht: [start]/[pausieren]/[fortsetzen]/[stop]
 * (lokale UI-Aktion) und [wendeFerncodeAn] (vom Telefon ueber
 * `CommandListenerService` weitergereichter Befehl) fuehren am Ende auf
 * DIESELBEN zwei privaten Hilfsfunktionen — der [RecordingService] weiss beim
 * Verarbeiten nicht mehr, wer ihn ausgeloest hat, und muss es auch nicht.
 * Das Melden an das Telefon (`PFAD_BEFEHL_AN_TELEFON`) uebernimmt NICHT diese
 * Klasse, sondern der [RecordingService] selbst bei jedem tatsaechlich
 * vollzogenen Zustandswechsel (siehe dortiger Klassen-KDoc) — so wird immer
 * gemeldet, was wirklich passiert ist, nicht was nur beabsichtigt war.
 */
object RecordingStatus {

    /** Grober Ablauf der Aufzeichnung, danach richtet sich die Oberflaeche. */
    enum class Phase {
        /** Faehigkeiten noch nicht abgefragt. */
        UNBEKANNT,

        /** Faehigkeiten stehen fest, es kann losgehen. */
        BEREIT,

        /** `prepareExerciseAsync` laeuft — GPS und HF waermen vor. */
        VORBEREITEN,

        LAEUFT,
        PAUSIERT,
        BEENDET,
        FEHLER,
    }

    private val _phase = MutableStateFlow(Phase.UNBEKANNT)
    private val _bericht = MutableStateFlow<FaehigkeitsBericht?>(null)
    private val _laufzeitMs = MutableStateFlow(0L)
    private val _distanzKm = MutableStateFlow(0.0)
    private val _tempoKmh = MutableStateFlow<Double?>(null)
    private val _letzteHfBpm = MutableStateFlow<Int?>(null)
    private val _fehler = MutableStateFlow<String?>(null)

    val phase: StateFlow<Phase> = _phase.asStateFlow()
    val bericht: StateFlow<FaehigkeitsBericht?> = _bericht.asStateFlow()

    /**
     * Chronometer in ms.
     *
     * Lokal bewusst NICHT aus dem `ExerciseUpdate` abgeleitet: Dessen aktive
     * Dauer steht still, sobald Health Services bei dunklem Display batcht.
     * Ein eigener Sekundentakt (siehe [RecordingService.starteChronometer])
     * laeuft weiter; der Telefon-Zustand (siehe Klassen-KDoc) synchronisiert
     * ihn periodisch nach.
     */
    val laufzeitMs: StateFlow<Long> = _laufzeitMs.asStateFlow()

    val distanzKm: StateFlow<Double> = _distanzKm.asStateFlow()
    val tempoKmh: StateFlow<Double?> = _tempoKmh.asStateFlow()
    val letzteHfBpm: StateFlow<Int?> = _letzteHfBpm.asStateFlow()

    /** Kurzer Fehlertext fuer den Startbildschirm (fehlende Faehigkeiten, gescheiterte Vorbereitung). */
    val fehler: StateFlow<String?> = _fehler.asStateFlow()

    /**
     * Fragt die Geraetefaehigkeiten ab, ohne etwas zu starten — damit der
     * Startbildschirm weiss, ob Radfahren ueberhaupt aufgezeichnet werden kann,
     * bevor die Fahrerin den Start-Knopf antippt.
     */
    suspend fun ladeFaehigkeiten(context: Context) {
        try {
            val client = HealthServices.getClient(context.applicationContext).exerciseClient
            _bericht.value = ermittleFaehigkeiten(client)
            if (_phase.value == Phase.UNBEKANNT) _phase.value = Phase.BEREIT
        } catch (e: Exception) {
            _fehler.value = "Fähigkeiten nicht abrufbar: ${e.message ?: e::class.java.simpleName}"
            _phase.value = Phase.FEHLER
        }
    }

    // --- Lokale UI-Aktionen (Start-/Live-Bildschirm) --------------------------

    fun start(context: Context) = starteLokal(context)
    fun pausieren(context: Context) = sendeKommandoLokal(context, RecordingService.ACTION_PAUSE)
    fun fortsetzen(context: Context) = sendeKommandoLokal(context, RecordingService.ACTION_RESUME)
    fun stop(context: Context) = sendeKommandoLokal(context, RecordingService.ACTION_STOP)

    /** Verlaesst den [Phase.BEENDET]/[Phase.FEHLER]-Bildschirm zurueck zum Start. */
    fun zurueckZumStart() {
        if (_phase.value == Phase.BEENDET || _phase.value == Phase.FEHLER) {
            zuruecksetzen()
            _phase.value = Phase.BEREIT
        }
    }

    // --- Vom Telefon ausgeloest (ueber CommandListenerService) ----------------

    /**
     * Setzt einen vom Telefon empfangenen [Befehl] genauso um, wie es ein Tipp
     * auf den entsprechenden Knopf dieser Uhr getan haette (siehe Klassen-KDoc).
     */
    internal fun wendeFerncodeAn(context: Context, cmd: String) {
        when (cmd) {
            Befehl.START -> starteLokal(context)
            Befehl.PAUSE -> sendeKommandoLokal(context, RecordingService.ACTION_PAUSE)
            Befehl.WEITER -> sendeKommandoLokal(context, RecordingService.ACTION_RESUME)
            Befehl.STOPP -> sendeKommandoLokal(context, RecordingService.ACTION_STOP)
            // Unbekannter Wert (z. B. von einer neueren `:app`-Version):
            // ignorieren statt zu werfen. Ein Steuerkanal, der bei jedem
            // unbekannten Befehl abstuerzt, ist fragiler als einer, der ihn
            // folgenlos verwirft.
            else -> Unit
        }
    }

    /** Uebernimmt Dauer/Distanz/HF aus dem periodischen Telefon-Zustand (siehe Klassen-KDoc). */
    internal fun wendeTelefonZustandAn(zustand: AufzeichnungsZustand) {
        _laufzeitMs.value = zustand.dauerMs
        _distanzKm.value = zustand.distanzKm
        zustand.hf?.let { _letzteHfBpm.value = it }
    }

    private fun starteLokal(context: Context) {
        ContextCompat.startForegroundService(
            context.applicationContext,
            Intent(context.applicationContext, RecordingService::class.java)
                .setAction(RecordingService.ACTION_START),
        )
    }

    private fun sendeKommandoLokal(context: Context, aktion: String) {
        context.applicationContext.startService(
            Intent(context.applicationContext, RecordingService::class.java).setAction(aktion),
        )
    }

    // --- Nur der Service schreibt. ---------------------------------------------

    internal fun setzePhase(phase: Phase) {
        _phase.value = phase
    }

    internal fun setzeBericht(bericht: FaehigkeitsBericht) {
        _bericht.value = bericht
    }

    internal fun setzeLaufzeit(ms: Long) {
        _laufzeitMs.value = ms
    }

    internal fun setzeDistanz(km: Double) {
        _distanzKm.value = km
    }

    internal fun setzeTempo(kmh: Double?) {
        _tempoKmh.value = kmh
    }

    internal fun setzeHf(bpm: Int?) {
        _letzteHfBpm.value = bpm
    }

    internal fun setzeFehler(text: String?) {
        _fehler.value = text
    }

    /** Setzt die Messwerte zurueck, damit ein zweiter Versuch sauber beginnt. */
    internal fun zuruecksetzen() {
        _laufzeitMs.value = 0L
        _distanzKm.value = 0.0
        _tempoKmh.value = null
        _letzteHfBpm.value = null
        _fehler.value = null
    }
}
