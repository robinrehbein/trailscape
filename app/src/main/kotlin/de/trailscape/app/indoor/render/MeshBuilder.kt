package de.trailscape.app.indoor.render

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Sammelt Dreiecke fuer ein Netz-Teil (ein Material): Quader, Rohre,
 * Kugeln, Ringe. Positionen im Koordinatensystem des Knotens (Meter,
 * glTF: Y oben, +Z vorn). Flaechen gegen den Uhrzeigersinn von aussen.
 */
class MeshBuilder {
    private val pos = ArrayList<Float>()
    private val nor = ArrayList<Float>()
    private val col = ArrayList<Float>()
    private val idx = ArrayList<Int>()
    private var color: FloatArray? = null

    val vertexCount: Int get() = pos.size / 3
    val triangleCount: Int get() = idx.size / 3
    val isEmpty: Boolean get() = idx.isEmpty()

    /** Ab jetzt bekommen neue Punkte diese Farbe (`COLOR_0`, RGBA linear). `null` = ohne Farbattribut. */
    fun vertexColor(rgba: FloatArray?): MeshBuilder {
        color = rgba
        return this
    }

    private fun vertex(p: V3, n: V3): Int {
        pos += p.x.toFloat(); pos += p.y.toFloat(); pos += p.z.toFloat()
        val nn = n.normalized()
        nor += nn.x.toFloat(); nor += nn.y.toFloat(); nor += nn.z.toFloat()
        val c = color ?: WHITE
        col += c[0]; col += c[1]; col += c[2]; col += c[3]
        return vertexCount - 1
    }

    fun triangle(a: Int, b: Int, c: Int) {
        idx += a; idx += b; idx += c
    }

    /** Viereck a-b-c-d (gegen den Uhrzeigersinn von der Seite der Normale [n]). */
    fun quad(a: V3, b: V3, c: V3, d: V3, n: V3) {
        val i0 = vertex(a, n)
        val i1 = vertex(b, n)
        val i2 = vertex(c, n)
        val i3 = vertex(d, n)
        triangle(i0, i1, i2)
        triangle(i0, i2, i3)
    }

    /** Achsparalleler Quader um [c] mit den Kantenlaengen [s]. */
    fun box(c: V3, s: V3): MeshBuilder {
        val hx = s.x / 2
        val hy = s.y / 2
        val hz = s.z / 2
        fun p(x: Double, y: Double, z: Double) = V3(c.x + x * hx, c.y + y * hy, c.z + z * hz)
        quad(p(1.0, -1.0, 1.0), p(1.0, -1.0, -1.0), p(1.0, 1.0, -1.0), p(1.0, 1.0, 1.0), V3(1.0, 0.0, 0.0))
        quad(p(-1.0, -1.0, -1.0), p(-1.0, -1.0, 1.0), p(-1.0, 1.0, 1.0), p(-1.0, 1.0, -1.0), V3(-1.0, 0.0, 0.0))
        quad(p(-1.0, 1.0, 1.0), p(1.0, 1.0, 1.0), p(1.0, 1.0, -1.0), p(-1.0, 1.0, -1.0), V3(0.0, 1.0, 0.0))
        quad(p(-1.0, -1.0, -1.0), p(1.0, -1.0, -1.0), p(1.0, -1.0, 1.0), p(-1.0, -1.0, 1.0), V3(0.0, -1.0, 0.0))
        quad(p(-1.0, -1.0, 1.0), p(1.0, -1.0, 1.0), p(1.0, 1.0, 1.0), p(-1.0, 1.0, 1.0), V3(0.0, 0.0, 1.0))
        quad(p(1.0, -1.0, -1.0), p(-1.0, -1.0, -1.0), p(-1.0, 1.0, -1.0), p(1.0, 1.0, -1.0), V3(0.0, 0.0, -1.0))
        return this
    }

    /** Rohr (Zylinder mit Deckeln) von [a] nach [b], Radius [r], [seg] Seiten. */
    fun tube(a: V3, b: V3, r: Double, seg: Int = 8, r2: Double = r): MeshBuilder {
        val axis = (b - a)
        if (axis.length() < 1e-6) return this
        val w = axis.normalized()
        val helper = if (kotlin.math.abs(w.y) < 0.9) V3.UP else V3(1.0, 0.0, 0.0)
        val u = w.cross(helper).normalized()
        val v = w.cross(u).normalized()
        val ring0 = IntArray(seg)
        val ring1 = IntArray(seg)
        for (i in 0 until seg) {
            val t = 2 * PI * i / seg
            val dir = u * cos(t) + v * sin(t)
            ring0[i] = vertex(a + dir * r, dir)
            ring1[i] = vertex(b + dir * r2, dir)
        }
        for (i in 0 until seg) {
            val j = (i + 1) % seg
            // Aussenseite: a0, a1, b1 / a0, b1, b0 (gegen den Uhrzeigersinn von aussen)
            triangle(ring0[i], ring0[j], ring1[j])
            triangle(ring0[i], ring1[j], ring1[i])
        }
        cap(a, w * -1.0, u, v, r, seg, flip = true)
        cap(b, w, u, v, r2, seg, flip = false)
        return this
    }

    private fun cap(c: V3, n: V3, u: V3, v: V3, r: Double, seg: Int, flip: Boolean) {
        val center = vertex(c, n)
        val ring = IntArray(seg) { i ->
            val t = 2 * PI * i / seg
            vertex(c + (u * cos(t) + v * sin(t)) * r, n)
        }
        for (i in 0 until seg) {
            val j = (i + 1) % seg
            if (flip) triangle(center, ring[j], ring[i]) else triangle(center, ring[i], ring[j])
        }
    }

    /** Kugel (UV) um [c] mit Radius [r]. */
    fun sphere(c: V3, r: Double, rings: Int = 8, seg: Int = 12, scale: V3 = V3(1.0, 1.0, 1.0)): MeshBuilder {
        val grid = Array(rings + 1) { IntArray(seg + 1) }
        for (i in 0..rings) {
            val th = PI * i / rings
            for (j in 0..seg) {
                val ph = 2 * PI * j / seg
                val d = V3(sin(th) * cos(ph), cos(th), sin(th) * sin(ph))
                val p = V3(d.x * scale.x, d.y * scale.y, d.z * scale.z)
                grid[i][j] = vertex(c + p * r, V3(d.x / scale.x, d.y / scale.y, d.z / scale.z))
            }
        }
        for (i in 0 until rings) for (j in 0 until seg) {
            val a = grid[i][j]
            val b = grid[i][j + 1]
            val cc = grid[i + 1][j + 1]
            val d = grid[i + 1][j]
            triangle(a, b, cc)
            triangle(a, cc, d)
        }
        return this
    }

    /** Ring um die X-Achse durch [c] (Reifen): Hauptradius [major], Rohrradius [minor]. */
    fun torusX(c: V3, major: Double, minor: Double, seg: Int = 28, side: Int = 6): MeshBuilder {
        val grid = Array(seg + 1) { IntArray(side + 1) }
        for (i in 0..seg) {
            val a = 2 * PI * i / seg
            val radial = V3(0.0, cos(a), sin(a))
            for (j in 0..side) {
                val b = 2 * PI * j / side
                val n = radial * cos(b) + V3(sin(b), 0.0, 0.0)
                grid[i][j] = vertex(c + radial * major + n * minor, n)
            }
        }
        for (i in 0 until seg) for (j in 0 until side) {
            val a = grid[i][j]
            val b = grid[i + 1][j]
            val cc = grid[i + 1][j + 1]
            val d = grid[i][j + 1]
            triangle(a, b, cc)
            triangle(a, cc, d)
        }
        return this
    }

    fun primitive(material: Int, withColors: Boolean = false): GlbWriter.Primitive = GlbWriter.Primitive(
        positions = pos.toFloatArray(),
        normals = nor.toFloatArray(),
        indices = idx.toIntArray(),
        material = material,
        colors = if (withColors) col.toFloatArray() else null,
    )

    private companion object {
        val WHITE = floatArrayOf(1f, 1f, 1f, 1f)
    }
}
