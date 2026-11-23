package de.trailscape.app.indoor.render

import de.trailscape.core.indoor.world.TrackPose
import de.trailscape.core.indoor.world.WorldTrack
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * # Koordinaten und Matrizen der Darstellung
 *
 * Reine Mathematik ohne Android und ohne Filament — damit sie sich auf der
 * JVM pruefen laesst (docs/indoor.md, Abschnitt 7.1):
 *
 *  * Welt: ENU in Metern um `GeoOrigin` (`e` Ost, `n` Nord, `u` oben).
 *  * glTF/Filament: rechtshaendig, Y oben, `X = e`, `Y = u`, `Z = −n`.
 *  * Matrizen sind **spaltenweise** (column-major) wie in Filament:
 *    Element (Zeile r, Spalte c) liegt bei `m[c * 4 + r]`.
 */

/** Ein Punkt oder eine Richtung im glTF-Raum (Meter). */
data class V3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: V3) = V3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: V3) = V3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Double) = V3(x * s, y * s, z * s)
    fun dot(o: V3) = x * o.x + y * o.y + z * o.z
    fun cross(o: V3) = V3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun length() = sqrt(dot(this))
    fun normalized(): V3 {
        val l = length()
        return if (l > 1e-12) this * (1.0 / l) else ZERO
    }

    /** Lineare Mischung: `t = 0` → this, `t = 1` → [o]. */
    fun lerp(o: V3, t: Double) = V3(x + (o.x - x) * t, y + (o.y - y) * t, z + (o.z - z) * t)

    fun isFinite() = x.isFinite() && y.isFinite() && z.isFinite()

    companion object {
        val ZERO = V3(0.0, 0.0, 0.0)
        val UP = V3(0.0, 1.0, 0.0)
    }
}

/** ENU (Meter) → glTF: `X = e`, `Y = u`, `Z = −n`. */
fun enuToGltf(e: Double, n: Double, u: Double): V3 = V3(e, u, -n)

/** glTF → ENU als Tripel `(e, n, u)`. */
fun gltfToEnu(p: V3): Triple<Double, Double, Double> = Triple(p.x, -p.z, p.y)

/**
 * Fahrtrichtung als waagrechter Einheitsvektor im glTF-Raum. [headingDeg]
 * wie in [TrackPose]: 0 = Nord (−Z), 90 = Ost (+X).
 */
fun headingForward(headingDeg: Double): V3 {
    val h = Math.toRadians(headingDeg)
    return V3(sin(h), 0.0, -cos(h))
}

/** Rechts zur Fahrtrichtung (waagrecht): bei Nord ist das Ost. */
fun headingRight(headingDeg: Double): V3 {
    val h = Math.toRadians(headingDeg)
    return V3(cos(h), 0.0, sin(h))
}

/** Die Lage auf dem Band im glTF-Raum. */
fun TrackPose.position(): V3 = enuToGltf(e, n, u)

/** Einheitsmatrix, spaltenweise. */
fun identityMatrix(): FloatArray = floatArrayOf(
    1f, 0f, 0f, 0f,
    0f, 1f, 0f, 0f,
    0f, 0f, 1f, 0f,
    0f, 0f, 0f, 1f,
)

/** Reine Verschiebung, spaltenweise. */
fun translationMatrix(x: Double, y: Double, z: Double): FloatArray = identityMatrix().also {
    it[12] = x.toFloat()
    it[13] = y.toFloat()
    it[14] = z.toFloat()
}

/**
 * Lage eines Modells, das nach **+Z** blickt (glTF-Vorderseite, Vertrag der
 * Modelle in `WorldModels`), an [position] mit Blick in [headingDeg] und um
 * die Steigung [gradePct] geneigt (Nase hoch bergauf).
 *
 * `M = T · R_y(180° − heading) · R_x(−atan(Steigung))`: Die Drehung um Y
 * bringt +Z auf die Fahrtrichtung ([headingForward]), die Drehung um X hebt
 * die Nase. Ergebnis spaltenweise.
 */
fun modelMatrix(position: V3, headingDeg: Double, gradePct: Double): FloatArray {
    val a = Math.toRadians(180.0 - headingDeg)
    val p = -atan(gradePct.coerceIn(-60.0, 60.0) / 100.0)
    val ca = cos(a)
    val sa = sin(a)
    val cp = cos(p)
    val sp = sin(p)
    // R_y(a) = [ca 0 sa; 0 1 0; -sa 0 ca], R_x(p) = [1 0 0; 0 cp -sp; 0 sp cp]
    // R = R_y · R_x
    val r00 = ca
    val r01 = sa * sp
    val r02 = sa * cp
    val r10 = 0.0
    val r11 = cp
    val r12 = -sp
    val r20 = -sa
    val r21 = ca * sp
    val r22 = ca * cp
    return floatArrayOf(
        r00.toFloat(), r10.toFloat(), r20.toFloat(), 0f,
        r01.toFloat(), r11.toFloat(), r21.toFloat(), 0f,
        r02.toFloat(), r12.toFloat(), r22.toFloat(), 0f,
        position.x.toFloat(), position.y.toFloat(), position.z.toFloat(), 1f,
    )
}

/** Wendet eine spaltenweise 4×4-Matrix auf einen Punkt an. */
fun transformPoint(m: FloatArray, p: V3): V3 = V3(
    m[0] * p.x + m[4] * p.y + m[8] * p.z + m[12],
    m[1] * p.x + m[5] * p.y + m[9] * p.z + m[13],
    m[2] * p.x + m[6] * p.y + m[10] * p.z + m[14],
)

/**
 * Faltet fortlaufende Laufmeter (ueber Runden) auf das Band: auf einer Runde
 * modulo Laenge, sonst auf `0 .. lengthM` begrenzt. Wie `WorldTrack.poseAt`.
 */
fun WorldTrack.fold(distM: Double): Double = when {
    !distM.isFinite() -> 0.0
    loop && lengthM > 0 -> ((distM % lengthM) + lengthM) % lengthM
    else -> distM.coerceIn(0.0, lengthM)
}

/**
 * Lage bei [distM] — auch vor dem Start und hinter dem Ziel einer offenen
 * Strecke: dort geradeaus verlaengert (die Kamera steht am Start 6 m
 * „hinter“ dem Fahrer, also vor Laufmeter 0).
 */
fun WorldTrack.poseExtended(distM: Double): TrackPose {
    if (loop || sampleCount < 2 || !distM.isFinite()) return poseAt(distM)
    return when {
        distM < 0.0 -> extend(poseAt(0.0), distM)
        distM > lengthM -> extend(poseAt(lengthM), distM - lengthM)
        else -> poseAt(distM)
    }
}

private fun extend(base: TrackPose, byM: Double): TrackPose {
    val h = Math.toRadians(base.headingDeg)
    return base.copy(e = base.e + sin(h) * byM, n = base.n + cos(h) * byM, u = base.u + base.gradePct / 100.0 * byM)
}

/**
 * Lage um [lateralM] quer zur Fahrtrichtung versetzt (positiv = rechts), im
 * glTF-Raum.
 */
fun TrackPose.lateral(lateralM: Double): V3 = position() + headingRight(headingDeg) * lateralM

/** Kleinster Winkelabstand `b − a` in Grad, im Bereich −180..180. */
fun angleDeltaDeg(a: Double, b: Double): Double {
    var d = (b - a) % 360.0
    if (d > 180.0) d -= 360.0
    if (d < -180.0) d += 360.0
    return d
}
