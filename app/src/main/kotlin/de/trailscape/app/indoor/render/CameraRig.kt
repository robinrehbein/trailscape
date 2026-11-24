package de.trailscape.app.indoor.render

import de.trailscape.core.indoor.world.CameraMode
import de.trailscape.core.indoor.world.WorldTrack
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/** Wohin die Kamera blickt: Auge, Ziel, Oben — glTF-Raum, Meter. */
data class CameraPose(val eye: V3, val target: V3, val up: V3 = V3.UP)

/** Winkel der Lenkerperspektive: Kurs (Grad), Steigung, Schraeglage (positiv = nach rechts), Blick in die Kurve (positiv = rechts), Lenkeinschlag (positiv = links), Bogenmass. */
data class HandlebarAngles(val headingDeg: Double, val pitchRad: Double, val leanRad: Double, val lookYawRad: Double, val steerRad: Double)

/** Lage des Cockpits: Modellmatrix des Rahmens (spaltenweise, glTF-Raum) und Lenkeinschlag. */
class CockpitPose(val matrix: FloatArray, val steerRad: Double)

/**
 * # Kameras laut Vertrag (docs/indoor.md, Abschnitt 7.6)
 *
 *  * [CameraMode.CHASE]: rund 6 m hinter und 2,5 m ueber dem Fahrer, weich
 *    gedaempft. „Hinter“ heisst: auf dem Streckenband 6 m zurueck — so folgt
 *    die Kamera Kurven, statt durch den Hang zu schneiden, und steht immer
 *    ueber der Fahrbahn, nie unter dem Gelaende.
 *  * [CameraMode.HANDLEBAR]: Ich-Perspektive vom Rad ([handlebar]): Auge fest im
 *    Rahmen des Rads ueber und hinter dem Lenker, so dass Lenker, Griffe und Haende
 *    ([CockpitModel]) unten im Bild liegen; der Rahmen folgt Kurs (gemittelt),
 *    Steigung und Kurvenlage, der Blick schaut etwas in die Kurve. Gedaempft
 *    werden die Winkel, nicht die Lage — das Cockpit wackelt nie gegen die Kamera.
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
    private var bar: HandlebarAngles? = null

    /** Lage des Cockpits zum letzten [update] (nur in [CameraMode.HANDLEBAR]): Modellmatrix und Lenkeinschlag. */
    var cockpit: CockpitPose? = null
        private set

    /** Beim naechsten Bild ohne Daempfung springen (neue Welt, neuer Modus). */
    fun snapNext() {
        snap = true
    }

    /**
     * Kamera fuer den Fahrer bei Laufmeter [distM] (fortlaufend), seitlich um
     * [lateralM] versetzt, nach [dtS] Sekunden.
     */
    fun update(track: WorldTrack, distM: Double, lateralM: Double, dtS: Double, speedMps: Double = 0.0): CameraPose {
        if (mode == CameraMode.HANDLEBAR) return updateHandlebar(track, distM, lateralM, dtS, speedMps)
        cockpit = null
        bar = null
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

    private fun updateHandlebar(track: WorldTrack, distM: Double, lateralM: Double, dtS: Double, speedMps: Double): CameraPose {
        val want = handlebarAngles(track, distM, lateralM, speedMps)
        val prev = bar
        val dt = dtS.coerceIn(0.0, 0.5)
        val a = if (snap || prev == null) {
            snap = false
            want
        } else {
            fun k(tau: Double) = 1.0 - exp(-dt / tau)
            HandlebarAngles(
                headingDeg = prev.headingDeg + angleDeltaDeg(prev.headingDeg, want.headingDeg) * k(HANDLEBAR_HEADING_TAU_S),
                pitchRad = prev.pitchRad + (want.pitchRad - prev.pitchRad) * k(HANDLEBAR_PITCH_TAU_S),
                leanRad = prev.leanRad + (want.leanRad - prev.leanRad) * k(HANDLEBAR_LEAN_TAU_S),
                lookYawRad = prev.lookYawRad + (want.lookYawRad - prev.lookYawRad) * k(HANDLEBAR_HEADING_TAU_S),
                steerRad = prev.steerRad + (want.steerRad - prev.steerRad) * k(HANDLEBAR_LEAN_TAU_S),
            )
        }
        bar = a
        val rider = track.poseExtended(distM).lateral(lateralM)
        val pose = handlebarPose(rider, a)
        cockpit = CockpitPose(bikeMatrix(rider, a), a.steerRad)
        eye = pose.eye
        target = pose.target
        return pose
    }

    companion object {
        /** Auge im Rahmen des Rads (ueber und hinter dem Lenker, Kopfhoehe eines Rennradfahrers). */
        val HANDLEBAR_EYE = V3(0.0, 1.46, -0.36)

        /** Blick so viel unter die Waagrechte (Grad). */
        const val HANDLEBAR_LOOK_DOWN_DEG = 3.0

        /** Anteil der Steigung, um den der Blick mitkippt (der Kopf gleicht den Rest aus). */
        const val HANDLEBAR_PITCH_FOLLOW = 0.6

        /** Kurvenlage: Anteil der physikalischen Schraeglage `atan(v²κ/g)` und Obergrenze. */
        const val HANDLEBAR_LEAN_FACTOR = 0.5
        val HANDLEBAR_MAX_LEAN_RAD = Math.toRadians(9.0)

        /** Blick in die Kurve: Anteil des Winkels zum Punkt [HANDLEBAR_LOOK_AT_M] voraus, hoechstens 22°. */
        const val HANDLEBAR_LOOK_INTO_CURVE = 0.6
        const val HANDLEBAR_LOOK_AT_M = 20.0
        val HANDLEBAR_MAX_LOOK_YAW_RAD = Math.toRadians(22.0)
        const val HANDLEBAR_HEADING_TAU_S = 0.25
        const val HANDLEBAR_PITCH_TAU_S = 0.4
        const val HANDLEBAR_LEAN_TAU_S = 0.35
        const val WHEELBASE_M = 1.02

        /**
         * Winkel der Lenkerperspektive bei Laufmeter [distM] (ungedaempft): Kurs ueber
         * ±3 m gemittelt, Steigung ueber ±4 m, Kruemmung aus dem Kurs ueber je 8 m davor
         * und danach.
         */
        fun handlebarAngles(track: WorldTrack, distM: Double, lateralM: Double, speedMps: Double): HandlebarAngles {
            fun at(d: Double) = track.poseExtended(d).lateral(lateralM)
            fun bearing(a: V3, b: V3): Double = Math.toDegrees(kotlin.math.atan2(b.x - a.x, -(b.z - a.z)))
            val heading = bearing(at(distM - 3.0), at(distM + 3.0))
            val pitch = kotlin.math.atan((track.poseExtended(distM + 4.0).u - track.poseExtended(distM - 4.0).u) / 8.0)
            val h1 = bearing(at(distM - 8.0), at(distM))
            val h2 = bearing(at(distM), at(distM + 8.0))
            val kappa = Math.toRadians(angleDeltaDeg(h1, h2)) / 8.0 // > 0: Rechtskurve
            val v = speedMps.coerceIn(0.0, 25.0)
            val lean = (kotlin.math.atan(v * v * kappa / 9.81) * HANDLEBAR_LEAN_FACTOR)
                .coerceIn(-HANDLEBAR_MAX_LEAN_RAD, HANDLEBAR_MAX_LEAN_RAD)
            val toAhead = angleDeltaDeg(heading, bearing(at(distM), at(distM + HANDLEBAR_LOOK_AT_M)))
            val yaw = (Math.toRadians(toAhead) * HANDLEBAR_LOOK_INTO_CURVE).coerceIn(-HANDLEBAR_MAX_LOOK_YAW_RAD, HANDLEBAR_MAX_LOOK_YAW_RAD)
            // Lenkeinschlag (positiv = links): Ackermann, sichtbar verstaerkt, hoechstens 20°.
            val steer = (-kotlin.math.atan(WHEELBASE_M * kappa) * 1.5).coerceIn(-0.35, 0.35)
            return HandlebarAngles(heading, pitch, lean, yaw, steer)
        }

        /** Rahmen des Rads: `T(rider) · Ry(π − Kurs) · Rx(−Steigung) · Rz(Schraeglage)`, spaltenweise. */
        fun bikeMatrix(rider: V3, a: HandlebarAngles): FloatArray {
            val r = bikeRotation(a)
            return floatArrayOf(
                r[0].toFloat(), r[1].toFloat(), r[2].toFloat(), 0f,
                r[3].toFloat(), r[4].toFloat(), r[5].toFloat(), 0f,
                r[6].toFloat(), r[7].toFloat(), r[8].toFloat(), 0f,
                rider.x.toFloat(), rider.y.toFloat(), rider.z.toFloat(), 1f,
            )
        }

        /** Spalten (x-, y-, z-Achse des Rads in der Welt) als 9 Werte. */
        fun bikeRotation(a: HandlebarAngles): DoubleArray {
            val ya = Math.PI - Math.toRadians(a.headingDeg)
            val p = -a.pitchRad
            val f = a.leanRad
            val cy = cos(ya); val sy = sin(ya)
            val cp = cos(p); val sp = sin(p)
            val cf = cos(f); val sf = sin(f)
            // Ry · Rx
            val m = arrayOf(
                doubleArrayOf(cy, sy * sp, sy * cp),
                doubleArrayOf(0.0, cp, -sp),
                doubleArrayOf(-sy, cy * sp, cy * cp),
            )
            // · Rz(f): Spalten x' = x·cf + y·sf, y' = −x·sf + y·cf
            fun col(c: Int) = V3(m[0][c], m[1][c], m[2][c])
            val x = col(0) * cf + col(1) * sf
            val y = col(0) * -sf + col(1) * cf
            val z = col(2)
            return doubleArrayOf(x.x, x.y, x.z, y.x, y.y, y.z, z.x, z.y, z.z)
        }

        /** Auge, Ziel und Oben fuer die Winkel [a] am Fahrer [rider]. */
        fun handlebarPose(rider: V3, a: HandlebarAngles): CameraPose {
            val r = bikeRotation(a)
            val e = HANDLEBAR_EYE
            val eye = rider + V3(r[0], r[1], r[2]) * e.x + V3(r[3], r[4], r[5]) * e.y + V3(r[6], r[7], r[8]) * e.z
            val h = Math.toRadians(a.headingDeg) + a.lookYawRad
            val p = a.pitchRad * HANDLEBAR_PITCH_FOLLOW - Math.toRadians(HANDLEBAR_LOOK_DOWN_DEG)
            val dir = V3(sin(h) * cos(p), sin(p), -cos(h) * cos(p))
            val right = V3(cos(h), 0.0, sin(h))
            val up = V3.UP * cos(a.leanRad) + right * sin(a.leanRad)
            return CameraPose(eye, eye + dir * 10.0, up)
        }

        const val CHASE_BACK_M = 6.0
        const val CHASE_UP_M = 2.5
        const val CHASE_LOOK_AHEAD_M = 6.0
        const val CHASE_LOOK_UP_M = 1.1
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
                CameraMode.HANDLEBAR -> handlebarPose(rider, handlebarAngles(track, distM, lateralM, 0.0))
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
