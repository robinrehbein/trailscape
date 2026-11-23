package de.trailscape.core.indoor.world

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * # Netze: Mathe-Typen, [MeshBuilder], Grundkoerper, Triangulierung
 *
 * Koordinaten hier sind glTF-Koordinaten (rechtshaendig, Y oben,
 * `X = e`, `Z = −n`). 2D-Polygone ([Triangulator], [MeshBuilder.addPolygon],
 * [MeshBuilder.addExtrudedPolygon]) sind dagegen in der Ebene **(e, n)**
 * angegeben; sie werden als `X = e`, `Z = −n` abgelegt. Gegen den Uhrzeigersinn
 * in (e, n) heisst von oben gesehen gegen den Uhrzeigersinn — Dreiecke
 * zeigen dann nach oben.
 */

/** Unveraenderlicher 3D-Vektor (fuer Polylinien und Rahmen, nicht je Eckpunkt). */
internal data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Double) = Vec3(x * s, y * s, z * s)
    operator fun unaryMinus() = Vec3(-x, -y, -z)
    infix fun dot(o: Vec3): Double = x * o.x + y * o.y + z * o.z
    infix fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun length(): Double = sqrt(x * x + y * y + z * z)

    /** Einheitsvektor; ein Nullvektor bleibt [fallback]. */
    fun normalized(fallback: Vec3 = UP): Vec3 {
        val l = length()
        return if (l > 1e-12) Vec3(x / l, y / l, z / l) else fallback
    }

    fun lerp(o: Vec3, t: Double) = Vec3(x + (o.x - x) * t, y + (o.y - y) * t, z + (o.z - z) * t)

    companion object {
        val ZERO = Vec3(0.0, 0.0, 0.0)
        val X = Vec3(1.0, 0.0, 0.0)
        val UP = Vec3(0.0, 1.0, 0.0)
        val Z = Vec3(0.0, 0.0, 1.0)
        val ONE = Vec3(1.0, 1.0, 1.0)
    }
}

/** Einheitsquaternion `(x, y, z, w)` wie in glTF. */
internal data class Quat(val x: Double, val y: Double, val z: Double, val w: Double) {
    /** Hamilton-Produkt: erst [o], dann `this` drehen. */
    operator fun times(o: Quat) = Quat(
        w * o.x + x * o.w + y * o.z - z * o.y,
        w * o.y - x * o.z + y * o.w + z * o.x,
        w * o.z + x * o.y - y * o.x + z * o.w,
        w * o.w - x * o.x - y * o.y - z * o.z,
    )

    fun normalized(): Quat {
        val l = sqrt(x * x + y * y + z * z + w * w)
        return if (l > 1e-12) Quat(x / l, y / l, z / l, w / l) else IDENTITY
    }

    fun conjugate() = Quat(-x, -y, -z, w)

    fun rotate(v: Vec3): Vec3 {
        // v' = v + 2w(q×v) + 2 q×(q×v)
        val q = Vec3(x, y, z)
        val t = (q cross v) * 2.0
        return v + t * w + (q cross t)
    }

    companion object {
        val IDENTITY = Quat(0.0, 0.0, 0.0, 1.0)

        fun fromAxisAngle(axis: Vec3, angleRad: Double): Quat {
            val a = axis.normalized()
            val s = sin(angleRad / 2)
            return Quat(a.x * s, a.y * s, a.z * s, cos(angleRad / 2))
        }

        /** Erst um X, dann um Y, dann um Z (feste Achsen), Bogenmass. */
        fun fromEuler(rx: Double, ry: Double, rz: Double): Quat =
            (fromAxisAngle(Vec3.Z, rz) * fromAxisAngle(Vec3.UP, ry) * fromAxisAngle(Vec3.X, rx)).normalized()

        /** Kugelinterpolation auf dem kuerzeren Bogen. */
        fun slerp(a: Quat, b0: Quat, t: Double): Quat {
            var b = b0
            var d = a.x * b.x + a.y * b.y + a.z * b.z + a.w * b.w
            if (d < 0) {
                b = Quat(-b.x, -b.y, -b.z, -b.w)
                d = -d
            }
            if (d > 0.9995) {
                return Quat(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.z + (b.z - a.z) * t, a.w + (b.w - a.w) * t).normalized()
            }
            val th = acos(d.coerceIn(-1.0, 1.0))
            val s = sin(th)
            val wa = sin((1 - t) * th) / s
            val wb = sin(t * th) / s
            return Quat(a.x * wa + b.x * wb, a.y * wa + b.y * wb, a.z * wa + b.z * wb, a.w * wa + b.w * wb)
        }
    }
}

/** 4×4-Matrix, spaltenweise wie glTF (`m[col·4 + row]`). */
internal class Mat4(val m: DoubleArray = identityArray()) {
    init {
        require(m.size == 16)
    }

    operator fun get(row: Int, col: Int): Double = m[col * 4 + row]

    /** `this · o` — erst [o], dann `this` anwenden. */
    operator fun times(o: Mat4): Mat4 {
        val r = DoubleArray(16)
        for (c in 0 until 4) for (row in 0 until 4) {
            var s = 0.0
            for (k in 0 until 4) s += m[k * 4 + row] * o.m[c * 4 + k]
            r[c * 4 + row] = s
        }
        return Mat4(r)
    }

    fun transformPoint(v: Vec3) = Vec3(
        m[0] * v.x + m[4] * v.y + m[8] * v.z + m[12],
        m[1] * v.x + m[5] * v.y + m[9] * v.z + m[13],
        m[2] * v.x + m[6] * v.y + m[10] * v.z + m[14],
    )

    fun transformDir(v: Vec3) = Vec3(
        m[0] * v.x + m[4] * v.y + m[8] * v.z,
        m[1] * v.x + m[5] * v.y + m[9] * v.z,
        m[2] * v.x + m[6] * v.y + m[10] * v.z,
    )

    /** Determinante des oberen 3×3-Teils (negativ = spiegelnd). */
    fun det3(): Double =
        m[0] * (m[5] * m[10] - m[9] * m[6]) - m[4] * (m[1] * m[10] - m[9] * m[2]) + m[8] * (m[1] * m[6] - m[5] * m[2])

    /**
     * Matrix fuer Normalen (Kofaktoren des 3×3-Teils, Vorzeichen der
     * Determinante), spaltenweise 9 Werte. Ergebnis muss normiert werden.
     */
    fun normalMatrix(): DoubleArray {
        val a = m[0]; val b = m[4]; val c = m[8]
        val d = m[1]; val e = m[5]; val f = m[9]
        val g = m[2]; val h = m[6]; val i = m[10]
        val s = if (det3() < 0) -1.0 else 1.0
        // Kofaktormatrix C (C = det · (M⁻¹)ᵀ), spaltenweise abgelegt.
        val c00 = e * i - f * h; val c01 = -(d * i - f * g); val c02 = d * h - e * g
        val c10 = -(b * i - c * h); val c11 = a * i - c * g; val c12 = -(a * h - b * g)
        val c20 = b * f - c * e; val c21 = -(a * f - c * d); val c22 = a * e - b * d
        return doubleArrayOf(c00 * s, c10 * s, c20 * s, c01 * s, c11 * s, c21 * s, c02 * s, c12 * s, c22 * s)
    }

    companion object {
        fun identityArray() = DoubleArray(16).also { it[0] = 1.0; it[5] = 1.0; it[10] = 1.0; it[15] = 1.0 }
        fun identity() = Mat4()

        fun translation(x: Double, y: Double, z: Double) = Mat4(identityArray().also { it[12] = x; it[13] = y; it[14] = z })
        fun translation(v: Vec3) = translation(v.x, v.y, v.z)
        fun scale(x: Double, y: Double, z: Double) = Mat4(identityArray().also { it[0] = x; it[5] = y; it[10] = z })
        fun scale(s: Double) = scale(s, s, s)

        fun rotationX(a: Double): Mat4 {
            val c = cos(a); val s = sin(a)
            return Mat4(identityArray().also { it[5] = c; it[6] = s; it[9] = -s; it[10] = c })
        }

        fun rotationY(a: Double): Mat4 {
            val c = cos(a); val s = sin(a)
            return Mat4(identityArray().also { it[0] = c; it[2] = -s; it[8] = s; it[10] = c })
        }

        fun rotationZ(a: Double): Mat4 {
            val c = cos(a); val s = sin(a)
            return Mat4(identityArray().also { it[0] = c; it[1] = s; it[4] = -s; it[5] = c })
        }

        fun rotation(q0: Quat): Mat4 {
            val q = q0.normalized()
            val x = q.x; val y = q.y; val z = q.z; val w = q.w
            return Mat4(
                doubleArrayOf(
                    1 - 2 * (y * y + z * z), 2 * (x * y + z * w), 2 * (x * z - y * w), 0.0,
                    2 * (x * y - z * w), 1 - 2 * (x * x + z * z), 2 * (y * z + x * w), 0.0,
                    2 * (x * z + y * w), 2 * (y * z - x * w), 1 - 2 * (x * x + y * y), 0.0,
                    0.0, 0.0, 0.0, 1.0,
                ),
            )
        }

        /** Verschiebung · Drehung · Skalierung, wie ein glTF-Knoten. */
        fun trs(t: Vec3 = Vec3.ZERO, r: Quat = Quat.IDENTITY, s: Vec3 = Vec3.ONE): Mat4 =
            translation(t) * rotation(r) * scale(s.x, s.y, s.z)
    }
}

/**
 * Wachsendes Dreiecksnetz auf primitiven Arrays (kein Objekt je Eckpunkt).
 * Je Eckpunkt Lage, Normale, Texturkoordinate und Farbe (`0xAARRGGBB`, wird
 * in der GLB zu `COLOR_0`, sobald eine Farbe nicht weiss ist). Dreiecke
 * gegen den Uhrzeigersinn von der Vorderseite gesehen.
 */
internal class MeshBuilder(vertexCapacity: Int = 64, triangleCapacity: Int = 64) {
    var positions = FloatArray(max(1, vertexCapacity) * 3)
        private set
    var normals = FloatArray(max(1, vertexCapacity) * 3)
        private set
    var uvs = FloatArray(max(1, vertexCapacity) * 2)
        private set
    var colors = IntArray(max(1, vertexCapacity))
        private set
    var indices = IntArray(max(1, triangleCapacity) * 3)
        private set

    var vertexCount = 0
        private set
    private var indexCount = 0

    /** Mindestens ein Eckpunkt hat eine Farbe ungleich Weiss. */
    var hasColors = false
        private set

    val triangleCount: Int get() = indexCount / 3
    val isEmpty: Boolean get() = indexCount == 0

    fun ensureVertexCapacity(n: Int) {
        if (n <= colors.size) return
        val cap = max(n, colors.size * 2)
        positions = positions.copyOf(cap * 3)
        normals = normals.copyOf(cap * 3)
        uvs = uvs.copyOf(cap * 2)
        colors = colors.copyOf(cap)
    }

    fun ensureTriangleCapacity(n: Int) {
        if (n * 3 <= indices.size) return
        indices = indices.copyOf(max(n * 3, indices.size * 2))
    }

    /** Haengt einen Eckpunkt an und gibt seinen Index zurueck. */
    fun vertex(
        x: Double,
        y: Double,
        z: Double,
        nx: Double,
        ny: Double,
        nz: Double,
        u: Double = 0.0,
        v: Double = 0.0,
        color: Int = Argb.WHITE,
    ): Int {
        val i = vertexCount
        if (i == colors.size) ensureVertexCapacity(i + 1)
        val p = i * 3
        positions[p] = x.toFloat(); positions[p + 1] = y.toFloat(); positions[p + 2] = z.toFloat()
        normals[p] = nx.toFloat(); normals[p + 1] = ny.toFloat(); normals[p + 2] = nz.toFloat()
        uvs[i * 2] = u.toFloat(); uvs[i * 2 + 1] = v.toFloat()
        colors[i] = color
        if (color != Argb.WHITE) hasColors = true
        vertexCount = i + 1
        return i
    }

    fun tri(a: Int, b: Int, c: Int) {
        if (indexCount + 3 > indices.size) ensureTriangleCapacity(indexCount / 3 + 1)
        indices[indexCount] = a
        indices[indexCount + 1] = b
        indices[indexCount + 2] = c
        indexCount += 3
    }

    /** Viereck `a b c d` gegen den Uhrzeigersinn → `(a b c)`, `(a c d)`. */
    fun quad(a: Int, b: Int, c: Int, d: Int) {
        tri(a, b, c)
        tri(a, c, d)
    }

    fun setNormal(i: Int, nx: Double, ny: Double, nz: Double) {
        normals[i * 3] = nx.toFloat(); normals[i * 3 + 1] = ny.toFloat(); normals[i * 3 + 2] = nz.toFloat()
    }

    fun setColor(i: Int, color: Int) {
        colors[i] = color
        if (color != Argb.WHITE) hasColors = true
    }

    fun x(i: Int): Float = positions[i * 3]
    fun y(i: Int): Float = positions[i * 3 + 1]
    fun z(i: Int): Float = positions[i * 3 + 2]

    fun clear() {
        vertexCount = 0
        indexCount = 0
        hasColors = false
    }

    /**
     * Haengt [other] an, optional mit Matrix [m] (Lagen und Normalen; bei
     * spiegelnder Matrix wird der Umlaufsinn umgedreht) und Abtoenung [tint]
     * (kanalweise multipliziert).
     */
    fun append(other: MeshBuilder, m: Mat4? = null, tint: Int? = null) {
        val n = other.vertexCount
        if (n == 0) return
        val base = vertexCount
        ensureVertexCapacity(base + n)
        ensureTriangleCapacity(triangleCount + other.triangleCount)
        val op = other.positions
        val on = other.normals
        if (m == null) {
            System.arraycopy(op, 0, positions, base * 3, n * 3)
            System.arraycopy(on, 0, normals, base * 3, n * 3)
        } else {
            val a = m.m
            val nm = m.normalMatrix()
            for (i in 0 until n) {
                val s = i * 3
                val d = (base + i) * 3
                val x = op[s].toDouble(); val y = op[s + 1].toDouble(); val z = op[s + 2].toDouble()
                positions[d] = (a[0] * x + a[4] * y + a[8] * z + a[12]).toFloat()
                positions[d + 1] = (a[1] * x + a[5] * y + a[9] * z + a[13]).toFloat()
                positions[d + 2] = (a[2] * x + a[6] * y + a[10] * z + a[14]).toFloat()
                val nx = on[s].toDouble(); val ny = on[s + 1].toDouble(); val nz = on[s + 2].toDouble()
                val tx = nm[0] * nx + nm[3] * ny + nm[6] * nz
                val ty = nm[1] * nx + nm[4] * ny + nm[7] * nz
                val tz = nm[2] * nx + nm[5] * ny + nm[8] * nz
                val l = sqrt(tx * tx + ty * ty + tz * tz)
                if (l > 1e-20) {
                    normals[d] = (tx / l).toFloat(); normals[d + 1] = (ty / l).toFloat(); normals[d + 2] = (tz / l).toFloat()
                } else {
                    normals[d] = 0f; normals[d + 1] = 1f; normals[d + 2] = 0f
                }
            }
        }
        System.arraycopy(other.uvs, 0, uvs, base * 2, n * 2)
        if (tint == null || tint == Argb.WHITE) {
            System.arraycopy(other.colors, 0, colors, base, n)
            if (other.hasColors) hasColors = true
        } else {
            for (i in 0 until n) colors[base + i] = Argb.multiply(other.colors[i], tint)
            hasColors = true
        }
        val flip = m != null && m.det3() < 0
        val oi = other.indices
        val ic = other.indexCount
        var d = indexCount
        var k = 0
        while (k < ic) {
            indices[d] = oi[k] + base
            if (flip) {
                indices[d + 1] = oi[k + 2] + base
                indices[d + 2] = oi[k + 1] + base
            } else {
                indices[d + 1] = oi[k + 1] + base
                indices[d + 2] = oi[k + 2] + base
            }
            d += 3
            k += 3
        }
        indexCount = d
        vertexCount = base + n
    }

    /** Wendet [m] auf alle Eckpunkte an (wie [append] mit sich selbst). */
    fun transform(m: Mat4) {
        val copy = MeshBuilder(vertexCount, triangleCount)
        copy.append(this)
        clear()
        append(copy, m)
    }

    fun translate(dx: Double, dy: Double, dz: Double) {
        for (i in 0 until vertexCount) {
            positions[i * 3] += dx.toFloat(); positions[i * 3 + 1] += dy.toFloat(); positions[i * 3 + 2] += dz.toFloat()
        }
    }

    /**
     * Glatte Normalen (flaechengewichtet) fuer die Dreiecke
     * `[firstTriangle, endTriangle)`; nur deren Eckpunkte werden angefasst.
     * [byPosition] fasst Eckpunkte an gleicher Stelle zusammen (Naehte von
     * Texturkoordinaten bleiben dann unsichtbar).
     */
    fun computeSmoothNormals(firstTriangle: Int = 0, endTriangle: Int = triangleCount, byPosition: Boolean = false) {
        val touched = BooleanArray(vertexCount)
        for (k in firstTriangle * 3 until endTriangle * 3) touched[indices[k]] = true
        // Gruppen: jeder Eckpunkt zeigt auf einen Vertreter.
        val rep = IntArray(vertexCount) { it }
        if (byPosition) {
            val map = HashMap<Long, Int>()
            for (i in 0 until vertexCount) {
                if (!touched[i]) continue
                val key = positionKey(i)
                val r = map.getOrPut(key) { i }
                rep[i] = r
            }
        }
        val acc = DoubleArray(vertexCount * 3)
        for (t in firstTriangle until endTriangle) {
            val a = indices[t * 3]; val b = indices[t * 3 + 1]; val c = indices[t * 3 + 2]
            val ax = positions[a * 3].toDouble(); val ay = positions[a * 3 + 1].toDouble(); val az = positions[a * 3 + 2].toDouble()
            val ux = positions[b * 3] - ax; val uy = positions[b * 3 + 1] - ay; val uz = positions[b * 3 + 2] - az
            val vx = positions[c * 3] - ax; val vy = positions[c * 3 + 1] - ay; val vz = positions[c * 3 + 2] - az
            val nx = uy * vz - uz * vy
            val ny = uz * vx - ux * vz
            val nz = ux * vy - uy * vx
            val ra = rep[a] * 3
            val rb = rep[b] * 3
            val rc = rep[c] * 3
            acc[ra] += nx; acc[ra + 1] += ny; acc[ra + 2] += nz
            acc[rb] += nx; acc[rb + 1] += ny; acc[rb + 2] += nz
            acc[rc] += nx; acc[rc + 1] += ny; acc[rc + 2] += nz
        }
        for (i in 0 until vertexCount) {
            if (!touched[i]) continue
            val r = rep[i]
            val nx = acc[r * 3]; val ny = acc[r * 3 + 1]; val nz = acc[r * 3 + 2]
            val l = sqrt(nx * nx + ny * ny + nz * nz)
            if (l > 1e-20) setNormal(i, nx / l, ny / l, nz / l) else setNormal(i, 0.0, 1.0, 0.0)
        }
    }

    private fun positionKey(i: Int): Long {
        // Raster 0,1 mm, je Achse 21 Bit (±104 m um den Ursprung eindeutig, darueber Hash-Kollisionen moeglich).
        fun q(v: Float): Long = (Math.round(v * 10000.0) and 0x1FFFFF)
        return (q(positions[i * 3]) shl 42) or (q(positions[i * 3 + 1]) shl 21) or q(positions[i * 3 + 2])
    }

    /**
     * Flache Normalen (je Dreieck) fuer `[firstTriangle, endTriangle)`.
     * Eckpunkte, die mehrere Dreiecke teilen, werden dafuer verdoppelt.
     */
    fun computeFlatNormals(firstTriangle: Int = 0, endTriangle: Int = triangleCount) {
        val claimed = BooleanArray(vertexCount)
        for (t in firstTriangle until endTriangle) {
            val a = indices[t * 3]; val b = indices[t * 3 + 1]; val c = indices[t * 3 + 2]
            val ax = positions[a * 3].toDouble(); val ay = positions[a * 3 + 1].toDouble(); val az = positions[a * 3 + 2].toDouble()
            val ux = positions[b * 3] - ax; val uy = positions[b * 3 + 1] - ay; val uz = positions[b * 3 + 2] - az
            val vx = positions[c * 3] - ax; val vy = positions[c * 3 + 1] - ay; val vz = positions[c * 3 + 2] - az
            var nx = uy * vz - uz * vy
            var ny = uz * vx - ux * vz
            var nz = ux * vy - uy * vx
            val l = sqrt(nx * nx + ny * ny + nz * nz)
            if (l > 1e-20) { nx /= l; ny /= l; nz /= l } else { nx = 0.0; ny = 1.0; nz = 0.0 }
            for (k in 0 until 3) {
                var v = indices[t * 3 + k]
                if (v < claimed.size && claimed[v]) {
                    v = vertex(
                        positions[v * 3].toDouble(), positions[v * 3 + 1].toDouble(), positions[v * 3 + 2].toDouble(),
                        nx, ny, nz, uvs[v * 2].toDouble(), uvs[v * 2 + 1].toDouble(), colors[v],
                    )
                    indices[t * 3 + k] = v
                } else {
                    if (v < claimed.size) claimed[v] = true
                    setNormal(v, nx, ny, nz)
                }
            }
        }
    }

    /** Achsparalleler Umriss `[minX, minY, minZ, maxX, maxY, maxZ]`; leer → alles 0. */
    fun bounds(): FloatArray {
        if (vertexCount == 0) return FloatArray(6)
        val b = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (i in 0 until vertexCount) {
            for (k in 0 until 3) {
                val v = positions[i * 3 + k]
                if (v < b[k]) b[k] = v
                if (v > b[k + 3]) b[k + 3] = v
            }
        }
        return b
    }

    // -----------------------------------------------------------------------
    // Grundkoerper
    // -----------------------------------------------------------------------

    /**
     * Quader zwischen zwei Ecken, Flaechen mit eigenen Normalen.
     * [uvPerMeter] `> 0` legt die Textur in Metern (kachelnd), sonst 0..1 je Flaeche.
     */
    fun addBox(
        minX: Double, minY: Double, minZ: Double,
        maxX: Double, maxY: Double, maxZ: Double,
        color: Int = Argb.WHITE,
        uvPerMeter: Double = 0.0,
    ) {
        val dx = maxX - minX
        val dy = maxY - minY
        val dz = maxZ - minZ
        // Ecken unten-links, unten-rechts, oben-rechts, oben-links (von aussen gesehen).
        face(maxX, minY, maxZ, maxX, minY, minZ, maxX, maxY, minZ, maxX, maxY, maxZ, 1.0, 0.0, 0.0, dz, dy, color, uvPerMeter)
        face(minX, minY, minZ, minX, minY, maxZ, minX, maxY, maxZ, minX, maxY, minZ, -1.0, 0.0, 0.0, dz, dy, color, uvPerMeter)
        face(minX, minY, maxZ, maxX, minY, maxZ, maxX, maxY, maxZ, minX, maxY, maxZ, 0.0, 0.0, 1.0, dx, dy, color, uvPerMeter)
        face(maxX, minY, minZ, minX, minY, minZ, minX, maxY, minZ, maxX, maxY, minZ, 0.0, 0.0, -1.0, dx, dy, color, uvPerMeter)
        face(minX, maxY, maxZ, maxX, maxY, maxZ, maxX, maxY, minZ, minX, maxY, minZ, 0.0, 1.0, 0.0, dx, dz, color, uvPerMeter)
        face(minX, minY, minZ, maxX, minY, minZ, maxX, minY, maxZ, minX, minY, maxZ, 0.0, -1.0, 0.0, dx, dz, color, uvPerMeter)
    }

    private fun face(
        x0: Double, y0: Double, z0: Double, x1: Double, y1: Double, z1: Double,
        x2: Double, y2: Double, z2: Double, x3: Double, y3: Double, z3: Double,
        nx: Double, ny: Double, nz: Double, w: Double, h: Double, color: Int, uvPerMeter: Double,
    ) {
        val us = if (uvPerMeter > 0) w * uvPerMeter else 1.0
        val vs = if (uvPerMeter > 0) h * uvPerMeter else 1.0
        val a = vertex(x0, y0, z0, nx, ny, nz, 0.0, vs, color)
        val b = vertex(x1, y1, z1, nx, ny, nz, us, vs, color)
        val c = vertex(x2, y2, z2, nx, ny, nz, us, 0.0, color)
        val d = vertex(x3, y3, z3, nx, ny, nz, 0.0, 0.0, color)
        quad(a, b, c, d)
    }

    /**
     * Zylinder oder Kegelstumpf von [p0] (Radius [r0]) nach [p1] (Radius [r1]),
     * glatte Mantelnormalen, flache Deckel. UV: `u` rundum 0..1, `v` laengs 0..1.
     */
    fun addCylinder(
        p0: Vec3, p1: Vec3, r0: Double, r1: Double, segments: Int = 12,
        capStart: Boolean = true, capEnd: Boolean = true, color: Int = Argb.WHITE,
    ) {
        addLoft(listOf(p0, p1), doubleArrayOf(r0, r1), segments, capStart, capEnd, color)
    }

    /**
     * Schlauch entlang der Polylinie [points] mit Radius [radii] je Punkt
     * (Gliedmassen, Aeste, Rohre): Ringe mit parallel verschobenem Rahmen,
     * glatte Normalen (Neigung des Radius beruecksichtigt). Doppelte Punkte
     * werden uebergangen. UV: `u` rundum `0..uRepeat`, `v` laengs
     * `Laenge · vPerMeter` bzw. `0..1` bei `vPerMeter = 0`.
     * [ringColors] setzt optional die Farbe je Punkt.
     */
    fun addLoft(
        points: List<Vec3>,
        radii: DoubleArray,
        segments: Int = 8,
        capStart: Boolean = false,
        capEnd: Boolean = false,
        color: Int = Argb.WHITE,
        ringColors: IntArray? = null,
        uRepeat: Double = 1.0,
        vPerMeter: Double = 0.0,
    ) {
        require(radii.size == points.size) { "Je Punkt ein Radius" }
        val keep = ArrayList<Int>(points.size)
        for (i in points.indices) {
            if (keep.isEmpty() || (points[i] - points[keep.last()]).length() > 1e-9) keep.add(i) else keep[keep.size - 1] = i
        }
        val n = keep.size
        if (n < 2 || segments < 3) return
        val pts = Array(n) { points[keep[it]] }
        val rad = DoubleArray(n) { radii[keep[it]] }
        val cols = IntArray(n) { ringColors?.get(keep[it]) ?: color }
        val tan = Array(n) { i ->
            val a = pts[max(0, i - 1)]
            val b = pts[min(n - 1, i + 1)]
            (b - a).normalized(Vec3.Z)
        }
        val arc = DoubleArray(n)
        for (i in 1 until n) arc[i] = arc[i - 1] + (pts[i] - pts[i - 1]).length()
        val slope = DoubleArray(n) { i ->
            val a = max(0, i - 1)
            val b = min(n - 1, i + 1)
            val ds = arc[b] - arc[a]
            if (ds > 1e-12) (rad[b] - rad[a]) / ds else 0.0
        }
        val nrm = arrayOfNulls<Vec3>(n)
        nrm[0] = perpendicular(tan[0])
        for (i in 1 until n) {
            val prev = nrm[i - 1]!!
            val t = tan[i]
            val proj = prev - t * (prev dot t)
            nrm[i] = if (proj.length() > 1e-6) proj.normalized() else perpendicular(t)
        }
        val radialW = DoubleArray(n) { 1.0 / sqrt(1 + slope[it] * slope[it]) }
        val axialW = DoubleArray(n) { -slope[it] / sqrt(1 + slope[it] * slope[it]) }
        val total = arc[n - 1]
        val vs = DoubleArray(n) { if (vPerMeter > 0) arc[it] * vPerMeter else if (total > 0) arc[it] / total else 0.0 }
        ringStrip(pts, rad, tan, Array(n) { nrm[it]!! }, radialW, axialW, vs, cols, segments, uRepeat)
        if (capStart) disc(pts[0], -tan[0], nrm[0]!!, tan[0], rad[0], segments, cols[0], reverse = true)
        if (capEnd) disc(pts[n - 1], tan[n - 1], nrm[n - 1]!!, tan[n - 1], rad[n - 1], segments, cols[n - 1], reverse = false)
    }

    /**
     * Ringe um die Mittelpunkte [c] mit Rahmen ([t], [nv]); Normale je Ring
     * `radial · radialW + t · axialW`.
     */
    private fun ringStrip(
        c: Array<Vec3>, r: DoubleArray, t: Array<Vec3>, nv: Array<Vec3>,
        radialW: DoubleArray, axialW: DoubleArray, v: DoubleArray, cols: IntArray, segments: Int, uRepeat: Double,
    ) {
        val n = c.size
        val first = vertexCount
        ensureVertexCapacity(vertexCount + n * (segments + 1))
        for (i in 0 until n) {
            val b = t[i] cross nv[i]
            for (j in 0..segments) {
                val th = 2 * Math.PI * j / segments
                val ct = cos(th)
                val st = sin(th)
                val rx = nv[i].x * ct + b.x * st
                val ry = nv[i].y * ct + b.y * st
                val rz = nv[i].z * ct + b.z * st
                val nx = rx * radialW[i] + t[i].x * axialW[i]
                val ny = ry * radialW[i] + t[i].y * axialW[i]
                val nz = rz * radialW[i] + t[i].z * axialW[i]
                val l = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-12)
                vertex(
                    c[i].x + rx * r[i], c[i].y + ry * r[i], c[i].z + rz * r[i],
                    nx / l, ny / l, nz / l, uRepeat * j / segments, v[i], cols[i],
                )
            }
        }
        val row = segments + 1
        for (i in 0 until n - 1) {
            for (j in 0 until segments) {
                val a = first + i * row + j
                quad(a, a + 1, a + row + 1, a + row)
            }
        }
    }

    /** Kreisscheibe als Faecher, Normale [normal]; [reverse] dreht den Umlaufsinn. */
    private fun disc(center: Vec3, normal: Vec3, nv: Vec3, t: Vec3, r: Double, segments: Int, color: Int, reverse: Boolean) {
        if (r <= 1e-9) return
        val b = t cross nv
        val c = vertex(center.x, center.y, center.z, normal.x, normal.y, normal.z, 0.5, 0.5, color)
        val first = vertexCount
        for (j in 0 until segments) {
            val th = 2 * Math.PI * j / segments
            val ct = cos(th)
            val st = sin(th)
            val px = center.x + (nv.x * ct + b.x * st) * r
            val py = center.y + (nv.y * ct + b.y * st) * r
            val pz = center.z + (nv.z * ct + b.z * st) * r
            vertex(px, py, pz, normal.x, normal.y, normal.z, 0.5 + 0.5 * ct, 0.5 - 0.5 * st, color)
        }
        for (j in 0 until segments) {
            val a = first + j
            val b2 = first + (j + 1) % segments
            if (reverse) tri(c, b2, a) else tri(c, a, b2)
        }
    }

    /**
     * Kapsel von [p0] nach [p1] mit Radius [radius]: Zylinder mit zwei
     * Halbkugeln, exakte glatte Normalen. [rings] Ringe je Halbkugel.
     */
    fun addCapsule(p0: Vec3, p1: Vec3, radius: Double, segments: Int = 12, rings: Int = 4, color: Int = Argb.WHITE) {
        val axis = (p1 - p0)
        val len = axis.length()
        val t = axis.normalized(Vec3.UP)
        val nv = perpendicular(t)
        val count = 2 * (rings + 1)
        val centers = ArrayList<Vec3>(count)
        val radii = ArrayList<Double>(count)
        val rw = ArrayList<Double>(count)
        val aw = ArrayList<Double>(count)
        val total = len + 2 * radius
        val vs = ArrayList<Double>(count)
        for (k in 0..rings) {
            val phi = -Math.PI / 2 + (Math.PI / 2) * k / rings
            val off = radius * sin(phi)
            centers.add(p0 + t * off)
            radii.add(radius * cos(phi))
            rw.add(cos(phi)); aw.add(sin(phi))
            vs.add((radius + off) / total)
        }
        for (k in 0..rings) {
            val phi = (Math.PI / 2) * k / rings
            val off = radius * sin(phi)
            centers.add(p1 + t * off)
            radii.add(radius * cos(phi))
            rw.add(cos(phi)); aw.add(sin(phi))
            vs.add((radius + len + off) / total)
        }
        ringStrip(
            centers.toTypedArray(), radii.toDoubleArray(), Array(count) { t }, Array(count) { nv },
            rw.toDoubleArray(), aw.toDoubleArray(), vs.toDoubleArray(), IntArray(count) { color }, segments, 1.0,
        )
    }

    /** UV-Kugel bzw. Ellipsoid mit Halbachsen [rx], [ry], [rz]; glatte Normalen. */
    fun addEllipsoid(
        cx: Double, cy: Double, cz: Double, rx: Double, ry: Double, rz: Double,
        segments: Int = 16, rings: Int = 8, color: Int = Argb.WHITE,
    ) {
        val first = vertexCount
        ensureVertexCapacity(vertexCount + (rings + 1) * (segments + 1))
        for (i in 0..rings) {
            val phi = Math.PI * i / rings // 0 = oben
            val sp = sin(phi)
            val cp = cos(phi)
            for (j in 0..segments) {
                val th = 2 * Math.PI * j / segments
                val ux = sp * cos(th)
                val uy = cp
                val uz = -sp * sin(th)
                val nx = ux / max(rx, 1e-12)
                val ny = uy / max(ry, 1e-12)
                val nz = uz / max(rz, 1e-12)
                val l = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-12)
                vertex(cx + ux * rx, cy + uy * ry, cz + uz * rz, nx / l, ny / l, nz / l, j.toDouble() / segments, i.toDouble() / rings, color)
            }
        }
        val row = segments + 1
        for (i in 0 until rings) {
            for (j in 0 until segments) {
                val a = first + i * row + j
                val b = a + 1
                val c = a + row + 1
                val d = a + row
                if (i != 0) tri(a, d, b)
                if (i != rings - 1) tri(b, d, c)
            }
        }
    }

    fun addSphere(c: Vec3, r: Double, segments: Int = 16, rings: Int = 8, color: Int = Argb.WHITE) =
        addEllipsoid(c.x, c.y, c.z, r, r, r, segments, rings, color)

    /**
     * Ebenes Polygon (Ringe in (e, n), erster aussen, weitere Loecher) auf
     * Hoehe [y], Normale nach oben (oder unten bei `up = false`).
     * UV: `(e, −n) · uvPerMeter`. Gibt die Zahl der erzeugten Dreiecke zurueck.
     */
    fun addPolygon(rings: List<DoubleArray>, y: Double, color: Int = Argb.WHITE, uvPerMeter: Double = 1.0, up: Boolean = true): Int {
        val clean = Triangulator.cleanRings(rings)
        val tris = Triangulator.triangulate(clean)
        if (tris.isEmpty()) return 0
        val first = vertexCount
        val ny = if (up) 1.0 else -1.0
        for (ring in clean) {
            var k = 0
            while (k + 1 < ring.size) {
                val e = ring[k]
                val nn = ring[k + 1]
                vertex(e, y, -nn, 0.0, ny, 0.0, e * uvPerMeter, -nn * uvPerMeter, color)
                k += 2
            }
        }
        var k = 0
        while (k < tris.size) {
            if (up) tri(first + tris[k], first + tris[k + 1], first + tris[k + 2])
            else tri(first + tris[k], first + tris[k + 2], first + tris[k + 1])
            k += 3
        }
        return tris.size / 3
    }

    /**
     * Prisma ueber einem Polygon (Grundriss in (e, n)): Waende von [bottom]
     * bis [top] mit flachen Normalen nach aussen, optional Deckel und Boden.
     * UV der Waende: `u` = Laenge entlang des Umrisses, `v` = Hoehe (Meter mal
     * [uvPerMeter], v = 0 oben). [wallColor]/[capColor] fuer die Vertexfarben.
     */
    fun addExtrudedPolygon(
        rings: List<DoubleArray>,
        bottom: Double,
        top: Double,
        wallColor: Int = Argb.WHITE,
        capTop: Boolean = true,
        capBottom: Boolean = false,
        capColor: Int = wallColor,
        uvPerMeter: Double = 1.0,
    ) {
        val clean = Triangulator.cleanRings(rings)
        if (clean.isEmpty()) return
        val h = top - bottom
        for ((ri, ring) in clean.withIndex()) {
            val area = Triangulator.signedArea(ring)
            if (area == 0.0) continue
            // Aussenring gegen, Loecher im Uhrzeigersinn → Normale (dn, −de) zeigt aus dem Koerper.
            val forward = if (ri == 0) area > 0 else area < 0
            val n = ring.size / 2
            var u = 0.0
            for (s in 0 until n) {
                val i0 = if (forward) s else n - 1 - s
                val i1 = if (forward) (s + 1) % n else (n - 2 - s + n) % n
                val e0 = ring[i0 * 2]; val n0 = ring[i0 * 2 + 1]
                val e1 = ring[i1 * 2]; val n1 = ring[i1 * 2 + 1]
                val de = e1 - e0
                val dn = n1 - n0
                val len = sqrt(de * de + dn * dn)
                if (len < 1e-9) continue
                // Aussen in (e, n): (dn, −de)/len → glTF (X = e, Z = −n): (dn, 0, de)/len.
                val nx = dn / len
                val nz = de / len
                val u0 = u * uvPerMeter
                val u1 = (u + len) * uvPerMeter
                val vb = h * uvPerMeter
                val a = vertex(e0, bottom, -n0, nx, 0.0, nz, u0, vb, wallColor)
                val b = vertex(e1, bottom, -n1, nx, 0.0, nz, u1, vb, wallColor)
                val c = vertex(e1, top, -n1, nx, 0.0, nz, u1, 0.0, wallColor)
                val d = vertex(e0, top, -n0, nx, 0.0, nz, u0, 0.0, wallColor)
                quad(a, b, c, d)
                u += len
            }
        }
        if (capTop) addPolygon(clean, top, capColor, uvPerMeter, up = true)
        if (capBottom) addPolygon(clean, bottom, capColor, uvPerMeter, up = false)
    }

    private fun perpendicular(t: Vec3): Vec3 {
        val ref = if (abs(t.y) < 0.9) Vec3.UP else Vec3.X
        return (ref - t * (ref dot t)).normalized(Vec3.X)
    }
}

/**
 * Polygon-Triangulierung (Ohrenschneiden mit Loechern, nach dem Verfahren von
 * mapbox/earcut, ISC-Lizenz). Robust gegen entartete und sich selbst
 * beruehrende Ringe: Es haengt nicht und wirft nicht, liefert im Zweifel aber
 * weniger Dreiecke.
 */
internal object Triangulator {
    /**
     * [rings]: erster Ring aussen, weitere Loecher; je Ring `e0, n0, e1, n1, …`
     * (offen, der letzte Punkt nicht gleich dem ersten — sonst wird er
     * verworfen). Ergebnis: Dreiecke als Indizes in die aneinandergehaengten
     * Punkte aller Ringe (Ringe mit nicht endlichen Werten zaehlen dabei mit
     * ihrer Punktzahl, liefern aber keine Dreiecke), jedes gegen den
     * Uhrzeigersinn.
     */
    fun triangulate(rings: List<DoubleArray>): IntArray {
        if (rings.isEmpty()) return IntArray(0)
        val total = rings.sumOf { it.size / 2 }
        val data = DoubleArray(total * 2)
        var offset = 0
        val validRanges = ArrayList<IntArray>()
        for ((ri, ring) in rings.withIndex()) {
            val n = ring.size / 2
            System.arraycopy(ring, 0, data, offset * 2, n * 2)
            val ok = isFiniteRing(ring) && n >= 3
            if (ri == 0 && !ok) return IntArray(0)
            if (ok) {
                var end = n
                // Schliessenden Wiederholungspunkt nicht doppelt fuehren.
                if (n > 3 && ring[0] == ring[(n - 1) * 2] && ring[1] == ring[(n - 1) * 2 + 1]) end = n - 1
                validRanges.add(intArrayOf(offset, offset + end))
            }
            offset += n
        }
        // Arbeitsbudget weit ueber dem, was Earcut je braucht — nur Schutz vor Endlosschleifen.
        val budget = (64L * total * total + 100_000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val ec = Earcut(data, budget)
        val tris = ec.run(validRanges)
        // Einheitlich gegen den Uhrzeigersinn ausrichten, Nullflaechen verwerfen.
        val out = IntArray(tris.size)
        var o = 0
        var k = 0
        while (k + 2 < tris.size) {
            val a = tris[k]; val b = tris[k + 1]; val c = tris[k + 2]
            val ar = (data[b * 2] - data[a * 2]) * (data[c * 2 + 1] - data[a * 2 + 1]) -
                (data[c * 2] - data[a * 2]) * (data[b * 2 + 1] - data[a * 2 + 1])
            if (ar > 0) {
                out[o++] = a; out[o++] = b; out[o++] = c
            } else if (ar < 0) {
                out[o++] = a; out[o++] = c; out[o++] = b
            }
            k += 3
        }
        return out.copyOf(o)
    }

    fun isFiniteRing(ring: DoubleArray): Boolean = ring.all { it.isFinite() }

    /**
     * Brauchbare Ringe: leer, wenn der Aussenring unbrauchbar ist (nicht
     * endlich oder weniger als 3 Punkte), sonst Aussenring plus brauchbare Loecher.
     */
    fun cleanRings(rings: List<DoubleArray>): List<DoubleArray> {
        val outer = rings.firstOrNull() ?: return emptyList()
        if (!isFiniteRing(outer) || outer.size < 6) return emptyList()
        return listOf(outer) + rings.drop(1).filter { isFiniteRing(it) && it.size >= 6 }
    }

    /** Vorzeichenbehaftete Flaeche, positiv gegen den Uhrzeigersinn. */
    fun signedArea(ring: DoubleArray): Double {
        val n = ring.size / 2
        var s = 0.0
        for (i in 0 until n) {
            val j = (i + 1) % n
            s += ring[i * 2] * ring[j * 2 + 1] - ring[j * 2] * ring[i * 2 + 1]
        }
        return s / 2
    }
}

/** Ein Knoten der doppelt verketteten Ringliste von [Earcut]. */
private class EarNode(val i: Int, val x: Double, val y: Double) {
    lateinit var prev: EarNode
    lateinit var next: EarNode
    var z = 0
    var prevZ: EarNode? = null
    var nextZ: EarNode? = null
    var steiner = false
}

/** Earcut-Kern; [budget] begrenzt die Arbeitsschritte (Schutz gegen Endlosschleifen). */
private class Earcut(private val data: DoubleArray, private var budget: Int) {
    private val triangles = ArrayList<Int>()
    private var minX = 0.0
    private var minY = 0.0
    private var invSize = 0.0

    fun run(ranges: List<IntArray>): IntArray {
        if (ranges.isEmpty()) return IntArray(0)
        val outer = ranges[0]
        var outerNode = linkedList(outer[0], outer[1], true) ?: return IntArray(0)
        if (outerNode.next === outerNode.prev) return IntArray(0)
        if (ranges.size > 1) outerNode = eliminateHoles(ranges, outerNode)
        if (outer[1] - outer[0] > 80) {
            var mnx = data[outer[0] * 2]; var mny = data[outer[0] * 2 + 1]
            var mxx = mnx; var mxy = mny
            for (i in outer[0] until outer[1]) {
                val x = data[i * 2]; val y = data[i * 2 + 1]
                if (x < mnx) mnx = x
                if (y < mny) mny = y
                if (x > mxx) mxx = x
                if (y > mxy) mxy = y
            }
            minX = mnx; minY = mny
            val s = max(mxx - mnx, mxy - mny)
            invSize = if (s != 0.0) 32767.0 / s else 0.0
        }
        earcutLinked(outerNode, 0)
        return triangles.toIntArray()
    }

    private fun linkedList(start: Int, end: Int, clockwise: Boolean): EarNode? {
        var last: EarNode? = null
        if (clockwise == (signedArea(start, end) > 0)) {
            for (i in start until end) last = insertNode(i, last)
        } else {
            for (i in end - 1 downTo start) last = insertNode(i, last)
        }
        if (last != null && equals(last, last.next)) {
            removeNode(last)
            last = last.next
        }
        return last
    }

    private fun signedArea(start: Int, end: Int): Double {
        var sum = 0.0
        var j = end - 1
        for (i in start until end) {
            sum += (data[j * 2] - data[i * 2]) * (data[i * 2 + 1] + data[j * 2 + 1])
            j = i
        }
        return sum
    }

    private fun filterPoints(start: EarNode?, end0: EarNode? = null): EarNode? {
        if (start == null) return null
        var end = end0 ?: start
        var p: EarNode = start
        var again: Boolean
        do {
            again = false
            if (--budget < 0) return end
            if (!p.steiner && (equals(p, p.next) || area(p.prev, p, p.next) == 0.0)) {
                removeNode(p)
                p = p.prev
                end = p
                if (p === p.next) break
                again = true
            } else {
                p = p.next
            }
        } while (again || p !== end)
        return end
    }

    private fun earcutLinked(ear0: EarNode?, pass: Int) {
        var ear = ear0 ?: return
        if (pass == 0 && invSize != 0.0) indexCurve(ear)
        var stop = ear
        while (ear.prev !== ear.next) {
            if (--budget < 0) return
            val prev = ear.prev
            val next = ear.next
            if (if (invSize != 0.0) isEarHashed(ear) else isEar(ear)) {
                triangles.add(prev.i); triangles.add(ear.i); triangles.add(next.i)
                removeNode(ear)
                ear = next.next
                stop = next.next
                continue
            }
            ear = next
            if (ear === stop) {
                when (pass) {
                    0 -> earcutLinked(filterPoints(ear), 1)
                    1 -> earcutLinked(cureLocalIntersections(filterPoints(ear)), 2)
                    else -> splitEarcut(ear)
                }
                break
            }
        }
    }

    private fun isEar(ear: EarNode): Boolean {
        val a = ear.prev; val b = ear; val c = ear.next
        if (area(a, b, c) >= 0) return false
        val x0 = min(a.x, min(b.x, c.x)); val y0 = min(a.y, min(b.y, c.y))
        val x1 = max(a.x, max(b.x, c.x)); val y1 = max(a.y, max(b.y, c.y))
        var p = c.next
        while (p !== a) {
            if (p.x >= x0 && p.x <= x1 && p.y >= y0 && p.y <= y1 &&
                pointInTriangle(a.x, a.y, b.x, b.y, c.x, c.y, p.x, p.y) && area(p.prev, p, p.next) >= 0
            ) {
                return false
            }
            p = p.next
        }
        return true
    }

    private fun isEarHashed(ear: EarNode): Boolean {
        val a = ear.prev; val b = ear; val c = ear.next
        if (area(a, b, c) >= 0) return false
        val x0 = min(a.x, min(b.x, c.x)); val y0 = min(a.y, min(b.y, c.y))
        val x1 = max(a.x, max(b.x, c.x)); val y1 = max(a.y, max(b.y, c.y))
        val minZ = zOrder(x0, y0)
        val maxZ = zOrder(x1, y1)
        var p = ear.prevZ
        var n = ear.nextZ
        fun blocks(q: EarNode): Boolean = q.x >= x0 && q.x <= x1 && q.y >= y0 && q.y <= y1 && q !== a && q !== c &&
            pointInTriangle(a.x, a.y, b.x, b.y, c.x, c.y, q.x, q.y) && area(q.prev, q, q.next) >= 0
        while (p != null && p.z >= minZ && n != null && n.z <= maxZ) {
            if (blocks(p)) return false
            p = p.prevZ
            if (blocks(n)) return false
            n = n.nextZ
        }
        while (p != null && p.z >= minZ) {
            if (blocks(p)) return false
            p = p.prevZ
        }
        while (n != null && n.z <= maxZ) {
            if (blocks(n)) return false
            n = n.nextZ
        }
        return true
    }

    private fun cureLocalIntersections(start0: EarNode?): EarNode? {
        var start = start0 ?: return null
        var p = start
        do {
            if (--budget < 0) return p
            val a = p.prev
            val b = p.next.next
            if (!equals(a, b) && intersects(a, p, p.next, b) && locallyInside(a, b) && locallyInside(b, a)) {
                triangles.add(a.i); triangles.add(p.i); triangles.add(b.i)
                removeNode(p)
                removeNode(p.next)
                p = b
                start = b
            }
            p = p.next
        } while (p !== start)
        return filterPoints(p)
    }

    private fun splitEarcut(start: EarNode) {
        var a = start
        do {
            var b = a.next.next
            while (b !== a.prev) {
                if (--budget < 0) return
                if (a.i != b.i && isValidDiagonal(a, b)) {
                    var c: EarNode? = splitPolygon(a, b)
                    val a2 = filterPoints(a, a.next)
                    c = filterPoints(c, c!!.next)
                    earcutLinked(a2, 0)
                    earcutLinked(c, 0)
                    return
                }
                b = b.next
            }
            a = a.next
        } while (a !== start)
    }

    private fun eliminateHoles(ranges: List<IntArray>, outer0: EarNode): EarNode {
        var outerNode = outer0
        val queue = ArrayList<EarNode>()
        for (k in 1 until ranges.size) {
            val list = linkedList(ranges[k][0], ranges[k][1], false) ?: continue
            if (list === list.next) list.steiner = true
            queue.add(getLeftmost(list))
        }
        queue.sortWith(compareBy<EarNode> { it.x }.thenBy { it.y })
        for (hole in queue) {
            outerNode = eliminateHole(hole, outerNode)
        }
        return outerNode
    }

    private fun eliminateHole(hole: EarNode, outerNode: EarNode): EarNode {
        val bridge = findHoleBridge(hole, outerNode) ?: return outerNode
        val bridgeReverse = splitPolygon(bridge, hole)
        filterPoints(bridgeReverse, bridgeReverse.next)
        return filterPoints(bridge, bridge.next) ?: outerNode
    }

    private fun findHoleBridge(hole: EarNode, outerNode: EarNode): EarNode? {
        var p = outerNode
        val hx = hole.x
        val hy = hole.y
        var qx = Double.NEGATIVE_INFINITY
        var m: EarNode? = null
        do {
            if (hy <= p.y && hy >= p.next.y && p.next.y != p.y) {
                val x = p.x + (hy - p.y) * (p.next.x - p.x) / (p.next.y - p.y)
                if (x <= hx && x > qx) {
                    qx = x
                    m = if (p.x < p.next.x) p else p.next
                    if (x == hx) return m
                }
            }
            p = p.next
        } while (p !== outerNode)
        if (m == null) return null
        val stop: EarNode = m
        val mx = m.x
        val my = m.y
        var tanMin = Double.POSITIVE_INFINITY
        p = m
        do {
            if (hx >= p.x && p.x >= mx && hx != p.x &&
                pointInTriangle(if (hy < my) hx else qx, hy, mx, my, if (hy < my) qx else hx, hy, p.x, p.y)
            ) {
                val tan = abs(hy - p.y) / (hx - p.x)
                val mm = m!!
                if (locallyInside(p, hole) &&
                    (tan < tanMin || (tan == tanMin && (p.x > mm.x || (p.x == mm.x && sectorContainsSector(mm, p)))))
                ) {
                    m = p
                    tanMin = tan
                }
            }
            p = p.next
        } while (p !== stop)
        return m
    }

    private fun sectorContainsSector(m: EarNode, p: EarNode): Boolean =
        area(m.prev, m, p.prev) < 0 && area(p.next, m, m.next) < 0

    private fun indexCurve(start: EarNode) {
        var p = start
        do {
            if (p.z == 0) p.z = zOrder(p.x, p.y)
            p.prevZ = p.prev
            p.nextZ = p.next
            p = p.next
        } while (p !== start)
        p.prevZ!!.nextZ = null
        p.prevZ = null
        sortLinked(p)
    }

    private fun sortLinked(list0: EarNode) {
        var list: EarNode? = list0
        var inSize = 1
        var numMerges: Int
        do {
            var p = list
            list = null
            var tail: EarNode? = null
            numMerges = 0
            while (p != null) {
                numMerges++
                var q: EarNode? = p
                var pSize = 0
                for (i in 0 until inSize) {
                    pSize++
                    q = q!!.nextZ
                    if (q == null) break
                }
                var qSize = inSize
                while (pSize > 0 || (qSize > 0 && q != null)) {
                    val e: EarNode
                    if (pSize != 0 && (qSize == 0 || q == null || p!!.z <= q.z)) {
                        e = p!!
                        p = p.nextZ
                        pSize--
                    } else {
                        e = q!!
                        q = q.nextZ
                        qSize--
                    }
                    if (tail != null) tail.nextZ = e else list = e
                    e.prevZ = tail
                    tail = e
                }
                p = q
            }
            tail!!.nextZ = null
            inSize *= 2
        } while (numMerges > 1)
    }

    private fun zOrder(x0: Double, y0: Double): Int {
        var x = ((x0 - minX) * invSize).toInt()
        var y = ((y0 - minY) * invSize).toInt()
        x = (x or (x shl 8)) and 0x00FF00FF
        x = (x or (x shl 4)) and 0x0F0F0F0F
        x = (x or (x shl 2)) and 0x33333333
        x = (x or (x shl 1)) and 0x55555555
        y = (y or (y shl 8)) and 0x00FF00FF
        y = (y or (y shl 4)) and 0x0F0F0F0F
        y = (y or (y shl 2)) and 0x33333333
        y = (y or (y shl 1)) and 0x55555555
        return x or (y shl 1)
    }

    private fun getLeftmost(start: EarNode): EarNode {
        var p = start
        var leftmost = start
        do {
            if (p.x < leftmost.x || (p.x == leftmost.x && p.y < leftmost.y)) leftmost = p
            p = p.next
        } while (p !== start)
        return leftmost
    }

    private fun pointInTriangle(ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double, px: Double, py: Double): Boolean =
        (cx - px) * (ay - py) >= (ax - px) * (cy - py) &&
            (ax - px) * (by - py) >= (bx - px) * (ay - py) &&
            (bx - px) * (cy - py) >= (cx - px) * (by - py)

    private fun isValidDiagonal(a: EarNode, b: EarNode): Boolean =
        a.next.i != b.i && a.prev.i != b.i && !intersectsPolygon(a, b) &&
            (
                locallyInside(a, b) && locallyInside(b, a) && middleInside(a, b) &&
                    (area(a.prev, a, b.prev) != 0.0 || area(a, b.prev, b) != 0.0) ||
                    equals(a, b) && area(a.prev, a, a.next) > 0 && area(b.prev, b, b.next) > 0
                )

    private fun area(p: EarNode, q: EarNode, r: EarNode): Double = (q.y - p.y) * (r.x - q.x) - (q.x - p.x) * (r.y - q.y)

    private fun equals(a: EarNode, b: EarNode): Boolean = a.x == b.x && a.y == b.y

    private fun sign(v: Double): Int = if (v > 0) 1 else if (v < 0) -1 else 0

    private fun intersects(p1: EarNode, q1: EarNode, p2: EarNode, q2: EarNode): Boolean {
        val o1 = sign(area(p1, q1, p2))
        val o2 = sign(area(p1, q1, q2))
        val o3 = sign(area(p2, q2, p1))
        val o4 = sign(area(p2, q2, q1))
        if (o1 != o2 && o3 != o4) return true
        if (o1 == 0 && onSegment(p1, p2, q1)) return true
        if (o2 == 0 && onSegment(p1, q2, q1)) return true
        if (o3 == 0 && onSegment(p2, p1, q2)) return true
        if (o4 == 0 && onSegment(p2, q1, q2)) return true
        return false
    }

    private fun onSegment(p: EarNode, q: EarNode, r: EarNode): Boolean =
        q.x <= max(p.x, r.x) && q.x >= min(p.x, r.x) && q.y <= max(p.y, r.y) && q.y >= min(p.y, r.y)

    private fun intersectsPolygon(a: EarNode, b: EarNode): Boolean {
        var p = a
        do {
            if (p.i != a.i && p.next.i != a.i && p.i != b.i && p.next.i != b.i && intersects(p, p.next, a, b)) return true
            p = p.next
        } while (p !== a)
        return false
    }

    private fun locallyInside(a: EarNode, b: EarNode): Boolean =
        if (area(a.prev, a, a.next) < 0) {
            area(a, b, a.next) >= 0 && area(a, a.prev, b) >= 0
        } else {
            area(a, b, a.prev) < 0 || area(a, a.next, b) < 0
        }

    private fun middleInside(a: EarNode, b: EarNode): Boolean {
        var p = a
        var inside = false
        val px = (a.x + b.x) / 2
        val py = (a.y + b.y) / 2
        do {
            if (((p.y > py) != (p.next.y > py)) && p.next.y != p.y &&
                (px < (p.next.x - p.x) * (py - p.y) / (p.next.y - p.y) + p.x)
            ) {
                inside = !inside
            }
            p = p.next
        } while (p !== a)
        return inside
    }

    private fun splitPolygon(a: EarNode, b: EarNode): EarNode {
        val a2 = EarNode(a.i, a.x, a.y)
        val b2 = EarNode(b.i, b.x, b.y)
        val an = a.next
        val bp = b.prev
        a.next = b; b.prev = a
        a2.next = an; an.prev = a2
        b2.next = a2; a2.prev = b2
        bp.next = b2; b2.prev = bp
        return b2
    }

    private fun insertNode(i: Int, last: EarNode?): EarNode {
        val p = EarNode(i, data[i * 2], data[i * 2 + 1])
        if (last == null) {
            p.prev = p
            p.next = p
        } else {
            p.next = last.next
            p.prev = last
            last.next.prev = p
            last.next = p
        }
        return p
    }

    private fun removeNode(p: EarNode) {
        p.next.prev = p.prev
        p.prev.next = p.next
        p.prevZ?.nextZ = p.nextZ
        p.nextZ?.prevZ = p.prevZ
    }
}
