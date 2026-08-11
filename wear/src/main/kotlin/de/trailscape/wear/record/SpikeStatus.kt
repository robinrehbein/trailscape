package de.trailscape.wear.record

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.health.services.client.HealthServices
import de.trailscape.wear.exercise.FaehigkeitsBericht
import de.trailscape.wear.exercise.ermittleFaehigkeiten
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Beobachtbarer Zustand des Spikes — die Bruecke zwischen [SpikeService] und
 * der Compose-Oberflaeche, im Aufbau bewusst wie
 * `de.trailscape.app.record.RecordingRepository`.
 *
 * Wie dort gilt: Diese Flows sind ein Spiegel fuer die Anzeige, nicht die
 * Quelle der Wahrheit. Die Wahrheit steht Zeile fuer Zeile im
 * [SpikeJournal] auf dem Datentraeger. Stirbt der Prozess mit dem Akku,
 * verschwinden diese Flows — das Protokoll bleibt.
 */
object SpikeStatus {

    /** Grober Ablauf des Versuchs, danach richtet sich die Oberflaeche. */
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
    private val _punktzahl = MutableStateFlow(0)
    private val _letzteHfBpm = MutableStateFlow<Int?>(null)
    private val _letzteHoeheM = MutableStateFlow<Double?>(null)
    private val _letzteGeschwindigkeitKmh = MutableStateFlow<Double?>(null)
    private val _gpsZustand = MutableStateFlow("—")
    private val _hfZustand = MutableStateFlow("—")
    private val _akkuProzent = MutableStateFlow<Int?>(null)
    private val _hsDistanzKm = MutableStateFlow<Double?>(null)
    private val _coreDistanzKm = MutableStateFlow(0.0)
    private val _coreAufstiegM = MutableStateFlow(0.0)
    private val _journalPfad = MutableStateFlow<String?>(null)
    private val _fehler = MutableStateFlow<String?>(null)

    val phase: StateFlow<Phase> = _phase.asStateFlow()

    /** Antwort auf Frage 1 — steht schon vor dem Start bereit. */
    val bericht: StateFlow<FaehigkeitsBericht?> = _bericht.asStateFlow()

    /**
     * Eigener Chronometer in ms.
     *
     * Bewusst NICHT aus dem [androidx.health.services.client.data.ExerciseUpdate]
     * abgeleitet: Dessen aktive Dauer steht still, sobald Health Services bei
     * dunklem Display batcht. Der `activeDurationCheckpoint` taugt nur als
     * gelegentlicher Sync-Anker, nicht als laufende Uhr.
     */
    val laufzeitMs: StateFlow<Long> = _laufzeitMs.asStateFlow()

    val punktzahl: StateFlow<Int> = _punktzahl.asStateFlow()
    val letzteHfBpm: StateFlow<Int?> = _letzteHfBpm.asStateFlow()

    /** `null`, wenn die Uhr keine absolute Hoehe liefert — die Oberflaeche zeigt dann „—". */
    val letzteHoeheM: StateFlow<Double?> = _letzteHoeheM.asStateFlow()

    val letzteGeschwindigkeitKmh: StateFlow<Double?> = _letzteGeschwindigkeitKmh.asStateFlow()
    val gpsZustand: StateFlow<String> = _gpsZustand.asStateFlow()
    val hfZustand: StateFlow<String> = _hfZustand.asStateFlow()
    val akkuProzent: StateFlow<Int?> = _akkuProzent.asStateFlow()

    /** Distanz, wie Health Services sie meldet. */
    val hsDistanzKm: StateFlow<Double?> = _hsDistanzKm.asStateFlow()

    /**
     * Dieselbe Fahrt, gerechnet mit `computeStats()` aus `:core` ueber die
     * empfangenen Positionen. Der direkte Vergleich beider Zahlen auf dem
     * Display ist der Beweis fuer Frage 4 — und jede Abweichung ist ein
     * Ergebnis, kein Fehler.
     */
    val coreDistanzKm: StateFlow<Double> = _coreDistanzKm.asStateFlow()

    val coreAufstiegM: StateFlow<Double> = _coreAufstiegM.asStateFlow()

    /** Voller Pfad des Protokolls, fuer `adb pull`. */
    val journalPfad: StateFlow<String?> = _journalPfad.asStateFlow()

    val fehler: StateFlow<String?> = _fehler.asStateFlow()

    /**
     * Fragt die Geraetefaehigkeiten ab, ohne etwas zu starten — damit die
     * Oberflaeche Frage 1 schon vor der ersten Ausfahrt beantworten kann.
     */
    suspend fun ladeFaehigkeiten(context: Context) {
        try {
            val client = HealthServices.getClient(context.applicationContext).exerciseClient
            _bericht.value = ermittleFaehigkeiten(client)
            if (_phase.value == Phase.UNBEKANNT) _phase.value = Phase.BEREIT
        } catch (e: Exception) {
            _fehler.value = "Faehigkeiten nicht abrufbar: ${e.message ?: e::class.java.simpleName}"
            _phase.value = Phase.FEHLER
        }
    }

    /** Startet die Aufzeichnung (Vordergrunddienst). */
    fun start(context: Context) {
        ContextCompat.startForegroundService(
            context.applicationContext,
            Intent(context.applicationContext, SpikeService::class.java)
                .setAction(SpikeService.ACTION_START),
        )
    }

    fun pausieren(context: Context) = sendeKommando(context, SpikeService.ACTION_PAUSE)

    fun fortsetzen(context: Context) = sendeKommando(context, SpikeService.ACTION_RESUME)

    fun stop(context: Context) = sendeKommando(context, SpikeService.ACTION_STOP)

    private fun sendeKommando(context: Context, aktion: String) {
        context.applicationContext.startService(
            Intent(context.applicationContext, SpikeService::class.java).setAction(aktion),
        )
    }

    // --- Nur der Service schreibt. -------------------------------------------

    internal fun setzePhase(phase: Phase) {
        _phase.value = phase
    }

    internal fun setzeBericht(bericht: FaehigkeitsBericht) {
        _bericht.value = bericht
    }

    internal fun setzeLaufzeit(ms: Long) {
        _laufzeitMs.value = ms
    }

    internal fun setzePunktzahl(anzahl: Int) {
        _punktzahl.value = anzahl
    }

    internal fun setzeHf(bpm: Int?) {
        _letzteHfBpm.value = bpm
    }

    internal fun setzeHoehe(m: Double?) {
        _letzteHoeheM.value = m
    }

    internal fun setzeGeschwindigkeit(kmh: Double?) {
        _letzteGeschwindigkeitKmh.value = kmh
    }

    internal fun setzeGpsZustand(zustand: String) {
        _gpsZustand.value = zustand
    }

    internal fun setzeHfZustand(zustand: String) {
        _hfZustand.value = zustand
    }

    internal fun setzeAkku(prozent: Int) {
        _akkuProzent.value = prozent
    }

    internal fun setzeHsDistanz(km: Double) {
        _hsDistanzKm.value = km
    }

    internal fun setzeCoreWerte(km: Double, aufstiegM: Double) {
        _coreDistanzKm.value = km
        _coreAufstiegM.value = aufstiegM
    }

    internal fun setzeJournalPfad(pfad: String) {
        _journalPfad.value = pfad
    }

    internal fun setzeFehler(text: String?) {
        _fehler.value = text
    }

    /** Setzt die Messwerte zurueck, damit ein zweiter Versuch sauber beginnt. */
    internal fun zuruecksetzen() {
        _laufzeitMs.value = 0L
        _punktzahl.value = 0
        _letzteHfBpm.value = null
        _letzteHoeheM.value = null
        _letzteGeschwindigkeitKmh.value = null
        _gpsZustand.value = "—"
        _hfZustand.value = "—"
        _hsDistanzKm.value = null
        _coreDistanzKm.value = 0.0
        _coreAufstiegM.value = 0.0
        _fehler.value = null
    }
}
