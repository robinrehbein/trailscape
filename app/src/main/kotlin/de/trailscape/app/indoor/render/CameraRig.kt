package de.trailscape.app.indoor.render

import de.trailscape.core.indoor.world.CameraMode
import de.trailscape.core.indoor.world.WorldTrack
import kotlin.math.exp

/** Wohin die Kamera blickt: Auge, Ziel, Oben — glTF-Raum, Meter. */
data class CameraPose(val eye: V3, val target: V3, val up: V3 = V3.UP)

/**
 * # Kameras laut Vertrag (docs/indoor.md, Abschnitt 7.6)
 *
 *  * [CameraMode.CHASE]: rund 6 m hinter und 2,5 m ueber dem Fahrer, weich
 *    gedaempft. „Hinter“ heisst: auf dem Streckenband 6 m zurueck — so folgt
 *    die Kamera Kurven, statt durch den Hang zu schneiden, und steht immer
 *    ueber der Fahrbahn, nie unter dem Gelaende.
 *  * [CameraMode.HANDLEBAR]: Augenhoehe ueber dem Lenker, Blick die Strecke entlang.
 *  * [CameraMode.DRONE]: hoch ueber der Strecke, schraeg von der Seite.
 *
 * Reine Mathematik; die Darstellung reicht das Ergebnis an `Camera.lookAt`.
 */
class CameraRig {
    var mode: CameraMode = CameraMode.CHASE
        set(value) {
            if (field != value) snap = true
            field = value
        }

    private var eye: V3? = null
    private var target: V3? = null
    private var snap = true

    /** Beim naechsten Bild ohne Daempfung springen (neue Welt, neuer Modus). */
    fun snapNext() {
        snap = true
    }

    /**
     * Kamera fuer den Fahrer bei Laufmeter [distM] (fortlaufend), seitlich um
     * [lateralM] versetzt, nach [dtS] Sekunden.
     */
    fun update(track: WorldTrack, distM: Double, lateralM: Double, dtS: Double): CameraPose {
        val desired = desired(track, distM, lateralM, mode)
        val e0 = eye
        val t0 = target
        val dt = dtS.coerceIn(0.0, 0.5)
        val (e, t) = if (snap || e0 == null || t0 == null || (desired.eye - e0).length() > SNAP_DISTANCE_M) {
            snap = false
            desired.eye to desired.target
        } else {
            val tau = when (mode) {
                CameraMode.CHASE -> CHASE_TAU_S
                CameraMode.HANDLEBAR -> HANDLEBAR_TAU_S
                CameraMode.DRONE -> DRONE_TAU_S
            }
            val k = 1.0 - exp(-dt / tau)
            e0.lerp(desired.eye, k) to t0.lerp(desired.target, k)
        }
        // Nie unter die Fahrbahn: Mindesthoehe ueber dem Band an der Stelle des Auges.
        val floor = minEyeHeight(track, distM, mode)
        val clamped = if (e.y < floor) V3(e.x, floor, e.z) else e
        eye = clamped
        target = t
        return CameraPose(clamped, t)
    }

    companion object {
        const val CHASE_BACK_M = 6.0
        const val CHASE_UP_M = 2.5
        const val CHASE_LOOK_AHEAD_M = 6.0
        const val CHASE_LOOK_UP_M = 1.1
        const val HANDLEBAR_EYE_M = 1.45
        const val HANDLEBAR_FORWARD_M = 0.25
        const val HANDLEBAR_LOOK_AHEAD_M = 25.0
        const val DRONE_UP_M = 28.0
        const val DRONE_SIDE_M = 22.0
        const val DRONE_BACK_M = 18.0
        const val CHASE_TAU_S = 0.35
        const val HANDLEBAR_TAU_S = 0.08
        const val DRONE_TAU_S = 0.9
        const val SNAP_DISTANCE_M = 80.0

        /** Ungedaempfte Wunschlage der Kamera. */
        fun desired(track: WorldTrack, distM: Double, lateralM: Double, mode: CameraMode): CameraPose {
            val here = track.poseExtended(distM)
            val rider = here.lateral(lateralM)
            return when (mode) {
                CameraMode.CHASE -> {
                    val back = track.poseExtended(distM - CHASE_BACK_M).lateral(lateralM)
                    val ahead = track.poseExtended(distM + CHASE_LOOK_AHEAD_M).lateral(lateralM)
                    // Hoehe relativ zum hoeheren von Fahrer und Kamerapunkt: bergab
                    // bleibt die Kamera ueber dem Fahrer, bergauf ueber dem Hang.
                    val baseY = maxOf(back.y, rider.y)
                    CameraPose(
                        eye = V3(back.x, baseY + CHASE_UP_M, back.z),
                        target = V3(ahead.x, (rider.y + ahead.y) / 2 + CHASE_LOOK_UP_M, ahead.z),
                    )
                }
                CameraMode.HANDLEBAR -> {
                    val fwd = headingForward(here.headingDeg)
                    val ahead = track.poseExtended(distM + HANDLEBAR_LOOK_AHEAD_M).lateral(lateralM)
                    CameraPose(
                        eye = rider + fwd * HANDLEBAR_FORWARD_M + V3(0.0, HANDLEBAR_EYE_M, 0.0),
                        target = ahead + V3(0.0, HANDLEBAR_EYE_M * 0.8, 0.0),
                    )
                }
                CameraMode.DRONE -> {
                    val fwd = headingForward(here.headingDeg)
                    val right = headingRight(here.headingDeg)
                    CameraPose(
                        eye = rider + right * DRONE_SIDE_M - fwd * DRONE_BACK_M + V3(0.0, DRONE_UP_M, 0.0),
                        target = rider + fwd * 8.0 + V3(0.0, 1.0, 0.0),
                    )
                }
            }
        }

        /** Mindesthoehe des Auges: ueber dem Band an der Stelle, an der die Kamera steht. */
        fun minEyeHeight(track: WorldTrack, distM: Double, mode: CameraMode): Double = when (mode) {
            CameraMode.CHASE -> maxOf(track.poseExtended(distM - CHASE_BACK_M).u, track.poseExtended(distM).u) + 1.2
            CameraMode.HANDLEBAR -> track.poseExtended(distM).u + 1.0
            CameraMode.DRONE -> track.poseExtended(distM).u + 10.0
        }
    }
}
