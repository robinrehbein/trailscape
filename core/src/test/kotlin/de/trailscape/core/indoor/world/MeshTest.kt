package de.trailscape.core.indoor.world

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MeshTest {
    private fun triArea(ring: DoubleArray, tris: IntArray, all: DoubleArray = ring): Double {
        var s = 0.0
        var k = 0
        while (k < tris.size) {
            val a = tris[k]; val b = tris[k + 1]; val c = tris[k + 2]
            val ar = ((all[b * 2] - all[a * 2]) * (all[c * 2 + 1] - all[a * 2 + 1]) -
                (all[c * 2] - all[a * 2]) * (all[b * 2 + 1] - all[a * 2 + 1])) / 2
            assertTrue(ar > 0, "Dreieck nicht gegen den Uhrzeigersinn")
            s += ar
            k += 3
        }
        return s
    }

    private fun circle(n: Int, r: Double, cx: Double = 0.0, cy: Double = 0.0, cw: Boolean = false, wobble: Double = 0.0): DoubleArray {
        val rnd = Random(n)
        val out = DoubleArray(n * 2)
        for (i in 0 until n) {
            val a = 2 * PI * i / n * (if (cw) -1 else 1)
            val rr = r * (1 + wobble * (rnd.nextDouble() - 0.5))
            out[i * 2] = cx + rr * cos(a)
            out[i * 2 + 1] = cy + rr * sin(a)
        }
        return out
    }

    @Test
    fun triangulatesConvexConcaveAndHoles() {
        val square = doubleArrayOf(0.0, 0.0, 1.0, 0.0, 1.0, 1.0, 0.0, 1.0)
        val t1 = Triangulator.triangulate(listOf(square))
        assertEquals(6, t1.size)
        assertEquals(1.0, triArea(square, t1), 1e-12)

        // Im Uhrzeigersinn angegeben: Ergebnis trotzdem gegen den Uhrzeigersinn.
        val cwSquare = doubleArrayOf(0.0, 0.0, 0.0, 1.0, 1.0, 1.0, 1.0, 0.0)
        assertEquals(1.0, triArea(cwSquare, Triangulator.triangulate(listOf(cwSquare))), 1e-12)

        val l = doubleArrayOf(0.0, 0.0, 2.0, 0.0, 2.0, 1.0, 1.0, 1.0, 1.0, 2.0, 0.0, 2.0)
        val tl = Triangulator.triangulate(listOf(l))
        assertEquals(4, tl.size / 3)
        assertEquals(3.0, triArea(l, tl), 1e-12)
        assertEquals(3.0, Triangulator.signedArea(l), 1e-12)

        val outer = doubleArrayOf(0.0, 0.0, 10.0, 0.0, 10.0, 10.0, 0.0, 10.0)
        val hole = doubleArrayOf(3.0, 3.0, 3.0, 7.0, 7.0, 7.0, 7.0, 3.0)
        val th = Triangulator.triangulate(listOf(outer, hole))
        assertEquals(84.0, triArea(outer, th, outer + hole), 1e-9)

        // Zwei Loecher, gross genug fuer den Z-Ordnungs-Pfad (> 80 Punkte).
        val big = circle(300, 100.0, wobble = 0.1)
        val h1 = circle(90, 20.0, -40.0, 0.0, cw = true)
        val h2 = circle(60, 15.0, 40.0, 10.0, cw = false)
        val all = big + h1 + h2
        val tb = Triangulator.triangulate(listOf(big, h1, h2))
        val expected = Triangulator.signedArea(big) - abs(Triangulator.signedArea(h1)) - abs(Triangulator.signedArea(h2))
        assertEquals(expected, triArea(big, tb, all), expected * 1e-9)
        assertEquals(300 + 90 + 60 + 2 * 2 - 2, tb.size / 3)
    }

    @Test
    fun degenerateRingsNeitherHangNorThrow() {
        val cases = listOf(
            listOf(doubleArrayOf()),
            listOf(doubleArrayOf(0.0, 0.0, 1.0, 1.0)),
            listOf(doubleArrayOf(0.0, 0.0, 1.0, 1.0, 2.0, 2.0, 3.0, 3.0)), // kollinear
            listOf(doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0)),
            listOf(doubleArrayOf(0.0, 0.0, 1.0, 0.0, 1.0, 0.0, 1.0, 1.0, 0.0, 1.0, 0.0, 0.0)), // Doppel- und Schlusspunkt
            listOf(doubleArrayOf(0.0, 0.0, 2.0, 2.0, 2.0, 0.0, 0.0, 2.0)), // Fliege (selbstschneidend)
            listOf(doubleArrayOf(0.0, 0.0, 2.0, 0.0, 2.0, 2.0, 1.0, 2.0, 1.0, 1.0, 1.0, 2.0, 0.0, 2.0)), // beruehrt sich selbst
            listOf(doubleArrayOf(0.0, 0.0, Double.NaN, 0.0, 1.0, 1.0)),
            listOf(doubleArrayOf(0.0, 0.0, 4.0, 0.0, 4.0, 4.0, 0.0, 4.0), doubleArrayOf(1.0, 1.0, Double.POSITIVE_INFINITY, 1.0, 2.0, 2.0)),
            listOf(doubleArrayOf(0.0, 0.0, 4.0, 0.0, 4.0, 4.0, 0.0, 4.0), doubleArrayOf(-1.0, -1.0, 5.0, -1.0, 5.0, 5.0)), // Loch ragt hinaus
            listOf(doubleArrayOf(0.0, 0.0, 4.0, 0.0, 4.0, 4.0, 0.0, 4.0), doubleArrayOf(0.0, 0.0, 4.0, 0.0, 4.0, 4.0, 0.0, 4.0)), // Loch = Aussenring
        )
        for ((i, rings) in cases.withIndex()) {
            val t = Triangulator.triangulate(rings)
            assertEquals(0, t.size % 3, "Fall $i")
            val n = rings.sumOf { it.size / 2 }
            assertTrue(t.all { it in 0 until n }, "Fall $i: Index ausserhalb")
        }
        // Zufaellige Punktwolken als „Polygon": darf nur nicht haengen oder werfen.
        val rnd = Random(7)
        repeat(300) {
            val n = rnd.nextInt(3, 60)
            val ring = DoubleArray(n * 2) { rnd.nextDouble(-10.0, 10.0).let { v -> if (rnd.nextInt(10) == 0) 0.0 else v } }
            val t = Triangulator.triangulate(listOf(ring))
            assertTrue(t.all { it in 0 until n })
        }
        val m = MeshBuilder()
        m.addPolygon(listOf(doubleArrayOf(0.0, 0.0, Double.NaN, 1.0, 1.0, 1.0)), 0.0)
        m.addExtrudedPolygon(listOf(doubleArrayOf(0.0, 0.0, 1.0, 1.0)), 0.0, 1.0)
        assertEquals(0, m.triangleCount)
    }

    /** Vorzeichenbehaftetes Volumen; positiv heisst: alle Dreiecke zeigen nach aussen. */
    private fun signedVolume(m: MeshBuilder): Double {
        var v = 0.0
        val p = m.positions
        for (t in 0 until m.triangleCount) {
            val a = m.indices[t * 3] * 3; val b = m.indices[t * 3 + 1] * 3; val c = m.indices[t * 3 + 2] * 3
            v += (p[a] * (p[b + 1] * p[c + 2] - p[b + 2] * p[c + 1]) -
                p[a + 1] * (p[b] * p[c + 2] - p[b + 2] * p[c]) +
                p[a + 2] * (p[b] * p[c + 1] - p[b + 1] * p[c])).toDouble()
        }
        return v / 6
    }

    /** Gueltige Indizes, Einheitsnormalen, und Eckpunktnormalen auf der Seite der Dreiecksnormale. */
    private fun assertConsistent(m: MeshBuilder, label: String) {
        assertTrue(m.triangleCount > 0, label)
        for (k in 0 until m.triangleCount * 3) assertTrue(m.indices[k] in 0 until m.vertexCount, "$label: Index")
        for (i in 0 until m.vertexCount) {
            val l = sqrt((m.normals[i * 3] * m.normals[i * 3] + m.normals[i * 3 + 1] * m.normals[i * 3 + 1] + m.normals[i * 3 + 2] * m.normals[i * 3 + 2]).toDouble())
            assertEquals(1.0, l, 1e-4, "$label: Normale $i nicht normiert")
        }
        var bad = 0
        for (t in 0 until m.triangleCount) {
            val ia = m.indices[t * 3]; val ib = m.indices[t * 3 + 1]; val ic = m.indices[t * 3 + 2]
            val ux = m.x(ib) - m.x(ia); val uy = m.y(ib) - m.y(ia); val uz = m.z(ib) - m.z(ia)
            val vx = m.x(ic) - m.x(ia); val vy = m.y(ic) - m.y(ia); val vz = m.z(ic) - m.z(ia)
            val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
            if (nx * nx + ny * ny + nz * nz < 1e-14f) continue
            for (v in intArrayOf(ia, ib, ic)) {
                val d = nx * m.normals[v * 3] + ny * m.normals[v * 3 + 1] + nz * m.normals[v * 3 + 2]
                if (d <= 0f) bad++
            }
        }
        assertEquals(0, bad, "$label: Eckpunktnormalen gegen den Umlaufsinn")
    }

    @Test
    fun closedPrimitivesFaceOutwards() {
        val box = MeshBuilder().apply { addBox(-1.0, 0.0, -2.0, 2.0, 1.0, 2.0, uvPerMeter = 0.5) }
        assertConsistent(box, "Quader")
        assertEquals(12.0, signedVolume(box), 1e-5)
        assertEquals(24, box.vertexCount)

        val cyl = MeshBuilder().apply { addCylinder(Vec3(1.0, 0.0, 0.0), Vec3(1.0, 2.0, 0.0), 0.5, 0.5, 32) }
        assertConsistent(cyl, "Zylinder")
        val polyArea = 0.5 * 32 * 0.25 * sin(2 * PI / 32)
        assertEquals(polyArea * 2, signedVolume(cyl), 1e-4)

        val cone = MeshBuilder().apply { addCylinder(Vec3(0.0, 0.0, 0.0), Vec3(0.3, 1.0, -0.2), 0.6, 0.2, 24) }
        assertConsistent(cone, "Kegelstumpf")
        assertTrue(signedVolume(cone) > 0)
        // Mantelnormalen neigen sich zur Spitze hin (Radius nimmt ab): Anteil laengs = sin(atan(0,4 / Laenge)).
        val axis = Vec3(0.3, 1.0, -0.2).normalized()
        val expectedAxial = sin(kotlin.math.atan(0.4 / Vec3(0.3, 1.0, -0.2).length()))
        for (i in 0 until 25 * 2) {
            val n = Vec3(cone.normals[i * 3].toDouble(), cone.normals[i * 3 + 1].toDouble(), cone.normals[i * 3 + 2].toDouble())
            assertEquals(expectedAxial, n dot axis, 1e-5)
        }

        val sphere = MeshBuilder().apply { addEllipsoid(0.0, 1.0, 0.0, 1.0, 2.0, 0.5, 48, 24) }
        assertConsistent(sphere, "Ellipsoid")
        val ell = 4.0 / 3 * PI * 1.0 * 2.0 * 0.5
        assertEquals(ell, signedVolume(sphere), ell * 0.03)

        val cap = MeshBuilder().apply { addCapsule(Vec3(0.0, 0.0, 0.0), Vec3(0.0, 0.0, 2.0), 0.5, 32, 8) }
        assertConsistent(cap, "Kapsel")
        val capVol = PI * 0.25 * 2 + 4.0 / 3 * PI * 0.125
        assertEquals(capVol, signedVolume(cap), capVol * 0.03)

        val house = MeshBuilder().apply {
            addExtrudedPolygon(
                listOf(
                    doubleArrayOf(0.0, 0.0, 10.0, 0.0, 10.0, 6.0, 4.0, 6.0, 4.0, 10.0, 0.0, 10.0), // L, gegen den Uhrzeigersinn
                    doubleArrayOf(1.0, 1.0, 2.0, 1.0, 2.0, 2.0, 1.0, 2.0), // Hof, falsch herum angegeben
                ),
                bottom = 0.0, top = 3.0, capTop = true, capBottom = true,
            )
        }
        assertConsistent(house, "Prisma")
        assertEquals((76.0 - 1.0) * 3.0, signedVolume(house), 1e-3)
        // Reihenfolge im Uhrzeigersinn ergibt dasselbe.
        val cw = MeshBuilder().apply {
            addExtrudedPolygon(listOf(doubleArrayOf(0.0, 0.0, 0.0, 5.0, 5.0, 5.0, 5.0, 0.0)), 1.0, 2.0, capBottom = true)
        }
        assertEquals(25.0, signedVolume(cw), 1e-4)

        val flat = MeshBuilder()
        assertEquals(2, flat.addPolygon(listOf(doubleArrayOf(0.0, 0.0, 2.0, 0.0, 2.0, 2.0, 0.0, 2.0)), 5.0))
        assertConsistent(flat, "Flaeche")
        assertEquals(1f, flat.normals[1])
        assertEquals(-2f, flat.z(2)) // n = 2 → Z = −2
    }

    @Test
    fun loftAlongCurveHasOutwardSmoothNormals() {
        val pts = (0..20).map { i ->
            val a = i / 20.0 * PI
            Vec3(cos(a) * 3, i * 0.1, sin(a) * 3)
        }
        val radii = DoubleArray(pts.size) { 0.3 - it * 0.01 }
        val m = MeshBuilder()
        m.addLoft(pts, radii, segments = 10, ringColors = IntArray(pts.size) { Argb.rgb(it * 10, 100, 50) })
        assertConsistent(m, "Loft")
        assertTrue(m.hasColors)
        assertEquals(pts.size * 11, m.vertexCount)
        // Normale zeigt vom naechsten Punkt der Mittellinie weg.
        for (i in 0 until m.vertexCount) {
            val p = Vec3(m.x(i).toDouble(), m.y(i).toDouble(), m.z(i).toDouble())
            var best = Vec3.ZERO
            var bestD = Double.MAX_VALUE
            for (s in 0 until pts.size - 1) {
                val a = pts[s]; val b = pts[s + 1]
                val ab = b - a
                val t = (((p - a) dot ab) / (ab dot ab)).coerceIn(0.0, 1.0)
                val q = a + ab * t
                val d = (p - q).length()
                if (d < bestD) { bestD = d; best = q }
            }
            val dir = (p - best).normalized()
            val n = Vec3(m.normals[i * 3].toDouble(), m.normals[i * 3 + 1].toDouble(), m.normals[i * 3 + 2].toDouble())
            assertTrue((n dot dir) > 0.8, "Eckpunkt $i: Normale zeigt nicht nach aussen (${n dot dir})")
        }
        // Mit Deckeln geschlossen → positives Volumen.
        val closed = MeshBuilder().apply { addLoft(pts, radii, 10, capStart = true, capEnd = true) }
        assertConsistent(closed, "Loft mit Deckeln")
        assertTrue(signedVolume(closed) > 0)
        // Doppelte Punkte stoeren nicht.
        val dup = MeshBuilder().apply { addLoft(listOf(Vec3.ZERO, Vec3.ZERO, Vec3.UP, Vec3.UP), doubleArrayOf(0.1, 0.1, 0.1, 0.1), 6) }
        assertEquals(2 * 7, dup.vertexCount)
    }

    @Test
    fun appendTransformsPositionsNormalsAndWinding() {
        val box = MeshBuilder().apply { addBox(0.0, 0.0, 0.0, 1.0, 2.0, 3.0) }
        val dst = MeshBuilder()
        val m = Mat4.translation(10.0, 0.0, 0.0) * Mat4.rotationY(PI / 2)
        dst.append(box, m, tint = Argb.rgb(255, 0, 0))
        assertEquals(box.vertexCount, dst.vertexCount)
        assertEquals(6.0, signedVolume(dst), 1e-4)
        assertTrue(dst.hasColors)
        assertEquals(Argb.rgb(255, 0, 0), dst.colors[0])
        // Erste Flaeche des Quaders ist +X; nach 90° um Y zeigt sie nach −Z.
        assertEquals(0f, dst.normals[0], 1e-6f)
        assertEquals(-1f, dst.normals[2], 1e-6f)
        val b = dst.bounds()
        assertEquals(10f, b[0], 1e-5f) // z ∈ [0,3] → x = 10 + z
        assertEquals(13f, b[3], 1e-5f)
        assertEquals(-1f, b[2], 1e-5f)

        // Spiegelung: Umlaufsinn wird gedreht, Volumen bleibt positiv.
        val mirrored = MeshBuilder().apply { append(box, Mat4.scale(-1.0, 1.0, 2.0)) }
        assertEquals(12.0, signedVolume(mirrored), 1e-4)
        assertConsistent(mirrored, "gespiegelt")

        // Ungleichmaessige Skalierung: Normalen mit der inversen Transponierten.
        val cyl = MeshBuilder().apply { addCylinder(Vec3.ZERO, Vec3.UP, 1.0, 1.0, 16, capStart = false, capEnd = false) }
        val squashed = MeshBuilder().apply { append(cyl, Mat4.scale(1.0, 1.0, 0.2)) }
        assertConsistent(squashed, "gestaucht")

        // transform() und translate() an Ort und Stelle.
        val t = MeshBuilder().apply { addBox(0.0, 0.0, 0.0, 1.0, 1.0, 1.0) }
        t.transform(Mat4.scale(2.0))
        t.translate(1.0, 0.0, 0.0)
        assertEquals(8.0, signedVolume(t), 1e-4)
        assertEquals(1f, t.bounds()[0])
    }

    @Test
    fun smoothAndFlatNormals() {
        val m = MeshBuilder()
        // Pyramide aus geteilten Eckpunkten.
        val top = m.vertex(0.0, 1.0, 0.0, 0.0, 0.0, 0.0)
        val a = m.vertex(-1.0, 0.0, 1.0, 0.0, 0.0, 0.0)
        val b = m.vertex(1.0, 0.0, 1.0, 0.0, 0.0, 0.0)
        val c = m.vertex(1.0, 0.0, -1.0, 0.0, 0.0, 0.0)
        val d = m.vertex(-1.0, 0.0, -1.0, 0.0, 0.0, 0.0)
        m.tri(top, a, b); m.tri(top, b, c); m.tri(top, c, d); m.tri(top, d, a)
        m.computeSmoothNormals()
        assertEquals(1f, m.normals[top * 3 + 1], 1e-6f)
        assertConsistent(m, "glatt")
        m.computeFlatNormals()
        // 12 Eckpunkt-Nutzungen, je Dreieck eigene Normalen → 12 Eckpunkte.
        assertEquals(12, m.vertexCount)
        assertConsistent(m, "flach")
        val n0 = sqrt(2.0) / 2
        assertEquals(n0.toFloat(), m.normals[top * 3 + 1], 1e-5f)

        // Glaetten ueber Texturnaht: Kugelnaht verschwindet mit byPosition.
        val s = MeshBuilder().apply { addEllipsoid(0.0, 0.0, 0.0, 1.0, 1.0, 1.0, 8, 4) }
        s.computeSmoothNormals(byPosition = true)
        assertConsistent(s, "Kugel geglaettet")
    }

    @Test
    fun quaternionAndMatrixMath() {
        val q = Quat.fromAxisAngle(Vec3.UP, PI / 2)
        val v = q.rotate(Vec3.X)
        assertEquals(0.0, v.x, 1e-12)
        assertEquals(-1.0, v.z, 1e-12)
        val mq = Mat4.rotation(q)
        val my = Mat4.rotationY(PI / 2)
        for (i in 0 until 16) assertEquals(my.m[i], mq.m[i], 1e-12)

        val half = Quat.slerp(Quat.IDENTITY, q, 0.5)
        val ref = Quat.fromAxisAngle(Vec3.UP, PI / 4)
        assertEquals(ref.y, half.y, 1e-9)
        assertEquals(ref.w, half.w, 1e-9)
        // Kuerzerer Bogen: negiertes Ziel ergibt dieselbe Drehung.
        val halfNeg = Quat.slerp(Quat.IDENTITY, Quat(-q.x, -q.y, -q.z, -q.w), 0.5)
        assertEquals(ref.w, halfNeg.w, 1e-9)

        val e = Quat.fromEuler(0.3, -0.7, 1.1)
        val me = Mat4.rotationZ(1.1) * Mat4.rotationY(-0.7) * Mat4.rotationX(0.3)
        val p = Vec3(0.2, -1.5, 0.7)
        val r1 = e.rotate(p)
        val r2 = me.transformPoint(p)
        assertEquals(r2.x, r1.x, 1e-9); assertEquals(r2.y, r1.y, 1e-9); assertEquals(r2.z, r1.z, 1e-9)

        val trs = Mat4.trs(Vec3(1.0, 2.0, 3.0), e, Vec3(2.0, 2.0, 2.0))
        val r3 = trs.transformPoint(p)
        val r4 = e.rotate(p * 2.0) + Vec3(1.0, 2.0, 3.0)
        assertEquals(r4.x, r3.x, 1e-9); assertEquals(r4.y, r3.y, 1e-9); assertEquals(r4.z, r3.z, 1e-9)
        assertEquals(8.0, trs.det3(), 1e-9)
        assertEquals(Vec3(0.0, 0.0, 1.0), Vec3.X cross Vec3.UP)
    }
}
