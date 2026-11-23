package de.trailscape.app.indoor.render

import de.trailscape.core.indoor.world.ActorState
import de.trailscape.core.indoor.world.WorldModels
import kotlin.math.abs
import kotlin.math.exp

/**
 * # Fortschreiben zwischen zwei Zustaenden
 *
 * `setState` kommt mit 10–20 Hz, gezeichnet wird mit 30–60 Hz
 * (docs/indoor.md, Abschnitt 7.6). Je Bild:
 *
 *  * Ziel = `distM + speedMps × (jetzt − timeMs)`, hoechstens [MAX_EXTRAPOLATION_S] weit;
 *  * die gezeigte Stelle laeuft mit dem Tempo weiter und holt die Abweichung
 *    zum Ziel mit der Zeitkonstante [CORRECTION_TAU_S] ein;
 *  * mehr als [JUMP_M] daneben: springen (z. B. neue Runde, neue Sitzung);
 *  * Pause: nichts fortschreiben, Kurbel und Raeder stehen.
 *
 * Kurbelphase aus der Trittfrequenz, Radphase aus `Tempo / Abrollumfang`,
 * beide in Umdrehungen 0..1 (`WorldModels.ANIM_PEDAL`/`ANIM_ROLL` sind genau
 * eine Umdrehung je Sekunde, die Animationszeit ist also die Phase).
 */
class MotionSmoother {
    private var target: ActorState? = null
    private var targetTimeMs: Long = 0L
    private var paused: Boolean = false

    /** Gezeigte Laufmeter (fortlaufend). */
    var distM: Double = 0.0
        private set

    /** Gezeigtes Tempo in m/s (0 in der Pause). */
    var speedMps: Double = 0.0
        private set

    /** Kurbelphase in Umdrehungen 0..1. */
    var crankPhase: Double = 0.0
        private set

    /** Radphase in Umdrehungen 0..1. */
    var wheelPhase: Double = 0.0
        private set

    /** Ob schon ein Zustand kam. */
    val hasState: Boolean get() = target != null

    private var initialized = false

    /** Neuer Zustand von der Sitzung. */
    fun update(state: ActorState, timeMs: Long, paused: Boolean) {
        if (!state.distM.isFinite()) return
        target = state
        targetTimeMs = timeMs
        this.paused = paused
        if (!initialized) {
            distM = state.distM
            initialized = true
        }
    }

    /** Vergisst alles (neue Welt). */
    fun reset(distM: Double = 0.0) {
        target = null
        initialized = false
        this.distM = distM
        speedMps = 0.0
    }

    /**
     * Schreibt um [dtS] Sekunden fort bis zur Uhrzeit [nowMs] (dieselbe
     * monotone Uhr wie `WorldState.timeMs`).
     */
    fun advance(nowMs: Long, dtS: Double) {
        val t = target ?: return
        val dt = dtS.coerceIn(0.0, MAX_FRAME_S)
        val speed = if (paused) 0.0 else t.speedMps.coerceIn(0.0, MAX_SPEED_MPS)
        val ahead = ((nowMs - targetTimeMs) / 1000.0).coerceIn(0.0, MAX_EXTRAPOLATION_S)
        val goal = if (paused) t.distM else t.distM + speed * ahead

        if (abs(goal - distM) > JUMP_M) {
            distM = goal
        } else {
            var next = distM + speed * dt
            val k = 1.0 - exp(-dt / CORRECTION_TAU_S)
            next += (goal - next) * k
            distM = next
        }
        speedMps = speed

        val cadence = if (paused) 0.0 else t.cadenceRpm.coerceIn(0.0, MAX_CADENCE_RPM)
        crankPhase = wrap01(crankPhase + cadence / 60.0 * dt)
        wheelPhase = wrap01(wheelPhase + speed / WorldModels.WHEEL_CIRCUMFERENCE_M * dt)
    }

    companion object {
        const val MAX_EXTRAPOLATION_S = 0.5
        const val CORRECTION_TAU_S = 0.3
        const val JUMP_M = 30.0
        const val MAX_FRAME_S = 0.5
        const val MAX_SPEED_MPS = 40.0
        const val MAX_CADENCE_RPM = 220.0

        fun wrap01(x: Double): Double {
            if (!x.isFinite()) return 0.0
            val f = x - kotlin.math.floor(x)
            return if (f >= 1.0) 0.0 else f
        }
    }
}

/**
 * Tiefpass erster Ordnung fuer einen Wert (z. B. den seitlichen Versatz des
 * Fahrers, wenn die Wegbreite wechselt).
 */
class Smoothed(private val tauS: Double, initial: Double = 0.0) {
    var value: Double = initial
        private set
    private var first = true

    fun step(goal: Double, dtS: Double): Double {
        if (!goal.isFinite()) return value
        if (first) {
            value = goal
            first = false
        } else {
            val k = 1.0 - exp(-dtS.coerceIn(0.0, 1.0) / tauS)
            value += (goal - value) * k
        }
        return value
    }

    fun reset(v: Double) {
        value = v
        first = false
    }
}
