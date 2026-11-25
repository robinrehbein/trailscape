package de.trailscape.core.indoor.tree

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Tangentenrahmen als Quaternionen, wie Filament sie im Attribut `TANGENTS`
 * erwartet (ein Quaternion `x y z w` je Eckpunkt, `w` > 0; der Shader dreht
 * damit `(0, 0, 1)` in die Normale). Die Vorlagen der Bibliothek haben nur
 * Normalen und keine Normalkarte: die Tangente ist deshalb frei gewaehlt
 * (senkrecht zur Normale, moeglichst waagrecht). gltfio rechnet fuer Kacheln
 * dasselbe aus den Normalen; die App legt die Netze der Vorlagen selbst an und
 * braucht die Rechnung hier — rein, getestet.
 */
object TangentFrames {
    /** Kleinstes `w`, das als normierter 16-Bit-Wert nicht zu 0 wird (Filament `packTangentFrame`). */
    private const val BIAS = 1.0 / 32767.0

    /** Quaternionen als normierte `SHORT4` (`count · 4` Werte) aus `count` Normalen `x y z`. */
    fun quatsFromNormals(normals: FloatArray, count: Int): ShortArray {
        require(normals.size >= count * 3) { "Normalen zu kurz" }
        val out = ShortArray(count * 4)
        val q = DoubleArray(4)
        for (i in 0 until count) {
            quat(normals[i * 3].toDouble(), normals[i * 3 + 1].toDouble(), normals[i * 3 + 2].toDouble(), q)
            for (c in 0 until 4) out[i * 4 + c] = Math.round(q[c] * 32767.0).coerceIn(-32767, 32767).toShort()
        }
        return out
    }

    /** Quaternion des Rahmens (Tangente, Bitangente, Normale) zur Normale (`nx`, `ny`, `nz`) nach [out] (`x y z w`). */
    fun quat(nx0: Double, ny0: Double, nz0: Double, out: DoubleArray) {
        var nx = nx0
        var ny = ny0
        var nz = nz0
        val nl = sqrt(nx * nx + ny * ny + nz * nz)
        if (nl > 1e-9 && nl.isFinite()) { nx /= nl; ny /= nl; nz /= nl } else { nx = 0.0; ny = 1.0; nz = 0.0 }
        // Tangente = hoch × Normale (waagrecht); steht die Normale senkrecht, die x-Achse.
        var tx = nz
        var ty = 0.0
        var tz = -nx
        val tl = sqrt(tx * tx + tz * tz)
        if (tl > 1e-6) { tx /= tl; tz /= tl } else { tx = 1.0; ty = 0.0; tz = 0.0 }
        // Bitangente = Normale × Tangente
        val bx = ny * tz - nz * ty
        val by = nz * tx - nx * tz
        val bz = nx * ty - ny * tx
        // Drehmatrix mit den Spalten (t, b, n) → Quaternion
        val m00 = tx; val m01 = bx; val m02 = nx
        val m10 = ty; val m11 = by; val m12 = ny
        val m20 = tz; val m21 = bz; val m22 = nz
        val tr = m00 + m11 + m22
        var x: Double; var y: Double; var z: Double; var w: Double
        if (tr > 0) {
            val s = sqrt(tr + 1.0) * 2
            w = s / 4; x = (m21 - m12) / s; y = (m02 - m20) / s; z = (m10 - m01) / s
        } else if (m00 > m11 && m00 > m22) {
            val s = sqrt(1.0 + m00 - m11 - m22) * 2
            w = (m21 - m12) / s; x = s / 4; y = (m01 + m10) / s; z = (m02 + m20) / s
        } else if (m11 > m22) {
            val s = sqrt(1.0 + m11 - m00 - m22) * 2
            w = (m02 - m20) / s; x = (m01 + m10) / s; y = s / 4; z = (m12 + m21) / s
        } else {
            val s = sqrt(1.0 + m22 - m00 - m11) * 2
            w = (m10 - m01) / s; x = (m02 + m20) / s; y = (m12 + m21) / s; z = s / 4
        }
        if (w < 0) { x = -x; y = -y; z = -z; w = -w }
        // w darf nicht 0 werden: sein Vorzeichen traegt die Haendigkeit des Rahmens.
        if (abs(w) < BIAS) {
            val f = sqrt(1.0 - BIAS * BIAS)
            x *= f; y *= f; z *= f; w = BIAS
        }
        out[0] = x; out[1] = y; out[2] = z; out[3] = w
    }
}
