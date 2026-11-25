package de.trailscape.app.ui.indoor

import de.trailscape.app.indoor.IndoorProtocolStore
import de.trailscape.core.KeyValueStore
import de.trailscape.core.indoor.FeelCalibrator
import de.trailscape.core.indoor.FeelPhase
import de.trailscape.core.indoor.FeelResult
import de.trailscape.core.indoor.FeelSample
import de.trailscape.core.indoor.IndoorPhase
import de.trailscape.core.indoor.IndoorSessionState
import de.trailscape.core.indoor.IndoorStep
import de.trailscape.core.indoor.RideFeel
import de.trailscape.core.indoor.RideProtocolRecorder
import de.trailscape.core.indoor.RiderSetup
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Anzeigezustand des geführten Abgleichs „Fahrgefühl einstellen“.
 *
 * @property phase Stand der Zustandsmaschine ([FeelCalibrator]).
 * @property phaseS Sekunden im Abschnitt (Einlaufen/Messen).
 * @property remainingS Restzeit des Messfensters.
 * @property result Ergebnis, sobald [phase] = [FeelPhase.DONE].
 * @property accepted der Vorschlag wurde übernommen.
 */
data class IndoorCalibrationUi(
    val phase: FeelPhase,
    val phaseS: Int,
    val remainingS: Int,
    val powerW: Int?,
    val cadenceRpm: Int?,
    val gearIndex: Int,
    val result: FeelResult?,
    val accepted: Boolean = false,
)

/**
 * Hält das Fahrgefühl ([RideFeel]), den geführten Abgleich und das Protokoll
 * je Fahrt — getrennt vom großen `IndoorViewModel`, das sie nur anstößt.
 *
 * Alles lokal: Das Fahrgefühl liegt im [KeyValueStore] (wie die übrigen
 * Indoor-Einstellungen), die Protokolle als Dateien ([IndoorProtocolStore]).
 * Weder Telemetrie noch Konto.
 */
class IndoorFeelController(
    private val store: KeyValueStore,
    val protocols: IndoorProtocolStore? = null,
) {
    private val _feel = MutableStateFlow(read())
    val feel: StateFlow<RideFeel> = _feel.asStateFlow()

    private val _calibration = MutableStateFlow<IndoorCalibrationUi?>(null)
    val calibration: StateFlow<IndoorCalibrationUi?> = _calibration.asStateFlow()

    private var calibrator: FeelCalibrator? = null
    private var lastFedS = -1
    private var recorder: RideProtocolRecorder? = null
    private var recorderStartMs = 0L
    private var recorderName = ""

    /** Der Abgleich läuft (auch nach dem Ergebnis, bis er beendet wird). */
    val calibrating: Boolean get() = calibrator != null

    // -- Fahrgefühl --------------------------------------------------------

    /** Liest das gespeicherte Fahrgefühl neu — ein anderer Ablauf (Einstellungen → Indoor) kann es geändert haben. */
    fun reload() {
        _feel.value = read()
    }

    fun setResistance(value: Double) = write(_feel.value.copy(resistance = snap(value)))

    fun setSurfaceStrength(value: Double) = write(_feel.value.copy(surfaceStrength = snap(value)))

    /** Zurück auf das Verhalten von heute. */
    fun reset() = write(RideFeel.DEFAULT)

    private fun snap(v: Double): Double = Math.round(v / RideFeel.STEP) * RideFeel.STEP

    private fun write(feel: RideFeel) {
        val f = feel.sanitized()
        runCatching {
            if (f.resistance == RideFeel.DEFAULT_RESISTANCE) store.remove(KEY_RESISTANCE) else store.setString(KEY_RESISTANCE, f.resistance.toString())
            if (f.surfaceStrength == RideFeel.DEFAULT_SURFACE) store.remove(KEY_SURFACE) else store.setString(KEY_SURFACE, f.surfaceStrength.toString())
        }
        _feel.value = f
    }

    private fun read(): RideFeel = runCatching {
        RideFeel.fromStored(store.getString(KEY_RESISTANCE), store.getString(KEY_SURFACE))
    }.getOrDefault(RideFeel.DEFAULT)

    // -- Fahrt: Protokoll und Abgleich ------------------------------------

    /** Eine neue Fahrt beginnt: frisches Protokoll; mit [calibrate] auch ein frischer Abgleich auf [rider]. */
    fun beginRide(rider: RiderSetup, header: List<String>, startedAtMs: Long, routeName: String, calibrate: Boolean) {
        recorder = RideProtocolRecorder(header)
        recorderStartMs = startedAtMs
        recorderName = routeName
        lastFedS = -1
        if (calibrate) {
            calibrator = FeelCalibrator(rider)
            _calibration.value = IndoorCalibrationUi(FeelPhase.WAITING, 0, FEEL_WINDOW_FALLBACK_S, null, null, 0, null)
        } else {
            calibrator = null
            _calibration.value = null
        }
    }

    /** Ein Schritt der Sitzung (Takt-Thread). */
    fun onStep(step: IndoorStep) {
        recorder?.onStep(step)
        val c = calibrator ?: return
        val s = step.state
        if (c.phase == FeelPhase.DONE) return
        if (s.phase != IndoorPhase.RUNNING || s.elapsedS == lastFedS) return
        lastFedS = s.elapsedS
        c.feed(sampleOf(s))
        publishCalibration(c, s)
    }

    private fun sampleOf(s: IndoorSessionState): FeelSample? {
        val p = s.powerW ?: return null
        val cad = s.cadenceRpm ?: return null
        return FeelSample(p.toDouble(), cad.toDouble(), s.speedMps, s.gear.index, s.gear.ratio)
    }

    private fun publishCalibration(c: FeelCalibrator, s: IndoorSessionState) {
        _calibration.value = IndoorCalibrationUi(
            phase = c.phase,
            phaseS = c.phaseS,
            remainingS = c.remainingS,
            powerW = s.powerW,
            cadenceRpm = s.cadenceRpm,
            gearIndex = s.gear.index,
            result = c.result,
            accepted = _calibration.value?.accepted == true,
        )
    }

    /** „Fertig“ vor Ablauf des Fensters: wertet aus, was da ist (zu wenig wird abgelehnt). */
    fun finishCalibrationNow() {
        val c = calibrator ?: return
        c.finishNow()
        _calibration.update { it?.copy(phase = c.phase, result = c.result) }
    }

    /** Den Vorschlag annehmen: wird zum neuen Gesamtwiderstand. */
    fun acceptSuggestion() {
        val r = (_calibration.value?.result as? FeelResult.Suggest)?.suggestion ?: return
        setResistance(r.suggestedResistance)
        _calibration.update { it?.copy(accepted = true) }
    }

    /** Der Abgleich ist vorbei (angenommen, abgelehnt oder abgebrochen). */
    fun endCalibration() {
        calibrator = null
        _calibration.value = null
    }

    /**
     * Die Fahrt ist zu Ende: legt das Protokoll ab (falls etwas aufgezeichnet
     * wurde) und vergisst den Recorder. Mehrfacher Aufruf ist harmlos.
     */
    fun endRide() {
        val r = recorder ?: return
        recorder = null
        if (r.hasData) protocols?.save(recorderStartMs, recorderName, r.toText())
    }

    companion object {
        const val KEY_RESISTANCE = "trailscape.indoor.feel_resistance"
        const val KEY_SURFACE = "trailscape.indoor.feel_surface"
        private const val FEEL_WINDOW_FALLBACK_S = de.trailscape.core.indoor.FEEL_TARGET_WINDOW_S
    }
}
