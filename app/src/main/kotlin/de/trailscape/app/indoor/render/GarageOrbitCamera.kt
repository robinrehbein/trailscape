package de.trailscape.app.indoor.render

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.tan

/**
 * # Drehbare Studio-Kamera fuer die 3D-Garagenbuehne
 *
 * Reine Mathematik ohne Android- oder Filament-Imports, damit Ansicht,
 * Drehwinkel und Framing vollstaendig auf der JVM geprueft werden koennen.
 *
 * Koordinaten des Fahrermodells (`RiderModel.kt`): Ursprung `(0, 0, 0)` am
 * Boden mittig zwischen den Raedern, Blickrichtung `+Z`, oben `+Y`, Antrieb
 * (Kettenblatt und rechte Kurbel) auf `-X`. Die Startansicht (`-55°`) zeigt
 * deshalb die rechte 3/4-Vorderansicht mit Helm, Trikotmuster, Lenkerband,
 * Rahmen-Akzent und Antrieb gleichzeitig.
 */
internal class GarageOrbitCamera {
    var yawDeg: Double = DEFAULT_YAW_DEG
        private set

    var pitchDeg: Double = DEFAULT_PITCH_DEG
        private set

    var autoRotate: Boolean = true
        private set

    /** Dreht die Kamera waagerecht um [deltaXDp] und stoppt die sanfte Eigendrehung. */
    fun onDrag(deltaXDp: Float) {
        if (!deltaXDp.isFinite()) return
        autoRotate = false
        yawDeg = normalizeAngleDeg(yawDeg - deltaXDp * DRAG_DEG_PER_DP)
    }

    /** Setzt Blickwinkel und sanfte Eigendrehung auf die Startansicht zurueck. */
    fun reset() {
        yawDeg = DEFAULT_YAW_DEG
        pitchDeg = DEFAULT_PITCH_DEG
        autoRotate = true
    }

    /** Fuehrt die langsame Praesentations-Drehung fort, solange der Nutzer nicht selbst gedreht hat. */
    fun tick(dtSeconds: Double) {
        if (!autoRotate || !dtSeconds.isFinite() || dtSeconds <= 0.0) return
        yawDeg = normalizeAngleDeg(yawDeg + dtSeconds.coerceAtMost(0.1) * AUTO_ROTATE_DEG_PER_SEC)
    }

    /** Berechnet Augenpunkt und Ziel fuer das Seitenverhaeltnis [aspect] (`width / height`). */
    fun pose(aspect: Double): CameraPose {
        val safeAspect = aspect.takeIf { it.isFinite() && it > 0.0 }?.coerceAtLeast(0.4) ?: 1.0
        val fovRad = Math.toRadians(FOV_DEG)
        val halfTanV = tan(fovRad / 2.0)
        val distV = (SUBJECT_HEIGHT_M / 2.0) / halfTanV * 1.18
        val distH = (SUBJECT_SPAN_M / 2.0) / (halfTanV * safeAspect) * 1.15
        val dist = max(distV, distH).coerceIn(MIN_DISTANCE_M, MAX_DISTANCE_M)

        val yawRad = Math.toRadians(yawDeg)
        val pitchRad = Math.toRadians(pitchDeg.coerceIn(MIN_PITCH_DEG, MAX_PITCH_DEG))
        val horiz = dist * cos(pitchRad)
        val target = V3(0.0, TARGET_Y_M, TARGET_Z_M)
        val eye = target + V3(
            x = horiz * sin(yawRad),
            y = dist * sin(pitchRad),
            z = horiz * cos(yawRad),
        )
        return CameraPose(eye = eye, target = target, up = V3.UP)
    }

    companion object {
        /** 3/4-Ansicht vorn rechts (Antriebsseite `-X`, vorn `+Z`). */
        const val DEFAULT_YAW_DEG = -55.0
        const val DEFAULT_PITCH_DEG = 15.0
        const val MIN_PITCH_DEG = 5.0
        const val MAX_PITCH_DEG = 35.0
        const val FOV_DEG = 34.0
        const val DRAG_DEG_PER_DP = 0.65
        const val AUTO_ROTATE_DEG_PER_SEC = 10.0

        private const val TARGET_Y_M = 0.86
        private const val TARGET_Z_M = 0.02
        private const val SUBJECT_HEIGHT_M = 1.75
        private const val SUBJECT_SPAN_M = 2.05
        private const val MIN_DISTANCE_M = 2.8
        private const val MAX_DISTANCE_M = 5.6

        fun normalizeAngleDeg(deg: Double): Double {
            if (!deg.isFinite()) return DEFAULT_YAW_DEG
            val mod = ((deg + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
            return if (mod == 180.0) -180.0 else mod
        }
    }
}
