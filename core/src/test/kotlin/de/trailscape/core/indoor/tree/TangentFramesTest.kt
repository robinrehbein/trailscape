package de.trailscape.core.indoor.tree

import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TangentFramesTest {
    /** Normale aus dem Quaternion, wie Filaments Shader sie bildet (`toTangentFrame`, dritte Spalte). */
    private fun normalOf(x: Double, y: Double, z: Double, w: Double) =
        doubleArrayOf(2 * (x * z + w * y), 2 * (y * z - w * x), 1 - 2 * (x * x + y * y))

    @Test
    fun `Quaternion dreht die z-Achse in die Normale, auch an den Polen`() {
        val normals = ArrayList<DoubleArray>()
        for (a in listOf(doubleArrayOf(0.0, 1.0, 0.0), doubleArrayOf(0.0, -1.0, 0.0), doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(-1.0, 0.0, 0.0), doubleArrayOf(0.0, 0.0, 1.0), doubleArrayOf(0.0, 0.0, -1.0))) normals += a
        var s = 12345L
        repeat(500) {
            fun r(): Double { s = s * 6364136223846793005L + 1442695040888963407L; return ((s ushr 11) / (1L shl 53).toDouble()) * 2 - 1 }
            val v = doubleArrayOf(r(), r(), r())
            val l = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
            if (l > 0.05) normals += doubleArrayOf(v[0] / l, v[1] / l, v[2] / l)
        }
        val q = DoubleArray(4)
        for (n in normals) {
            TangentFrames.quat(n[0], n[1], n[2], q)
            assertEquals(1.0, q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3], 1e-9)
            assertTrue(q[3] > 0, "w > 0 (Haendigkeit): ${q.toList()} fuer ${n.toList()}")
            val got = normalOf(q[0], q[1], q[2], q[3])
            for (c in 0 until 3) assertEquals(n[c], got[c], 1e-4, "Normale ${n.toList()} → ${got.toList()}")
        }
    }

    @Test
    fun `gepackte Quaternionen bleiben nah an der Normale, unnormierte und leere Normalen stoeren nicht`() {
        val src = floatArrayOf(0f, 2f, 0f, 0.3f, 0.4f, -0.5f, 0f, 0f, 0f, Float.NaN, 1f, 0f)
        val packed = TangentFrames.quatsFromNormals(src, 4)
        assertEquals(16, packed.size)
        fun n(i: Int) = normalOf(packed[i * 4] / 32767.0, packed[i * 4 + 1] / 32767.0, packed[i * 4 + 2] / 32767.0, packed[i * 4 + 3] / 32767.0)
        assertEquals(1.0, n(0)[1], 1e-3)
        val l = sqrt(0.09 + 0.16 + 0.25)
        assertEquals(0.3 / l, n(1)[0], 2e-3)
        assertEquals(0.4 / l, n(1)[1], 2e-3)
        assertEquals(-0.5 / l, n(1)[2], 2e-3)
        // Null- und NaN-Normale: nach oben
        assertEquals(1.0, n(2)[1], 1e-3)
        assertEquals(1.0, n(3)[1], 1e-3)
        for (i in 0 until 4) assertTrue(packed[i * 4 + 3] > 0)
    }

    @Test
    fun `Normalen einer Vorlage ueberleben das Packen`() {
        val t = TreeLibrary.template(TreeKind.FRUIT, 0, TreeLod.MID)
        for (mesh in listOf(t.bark, t.leaves)) {
            val packed = TangentFrames.quatsFromNormals(mesh.normals, mesh.vertexCount)
            for (i in 0 until mesh.vertexCount) {
                val x = packed[i * 4] / 32767.0; val y = packed[i * 4 + 1] / 32767.0; val z = packed[i * 4 + 2] / 32767.0; val w = packed[i * 4 + 3] / 32767.0
                val got = normalOf(x, y, z, w)
                val nx = mesh.normals[i * 3].toDouble(); val ny = mesh.normals[i * 3 + 1].toDouble(); val nz = mesh.normals[i * 3 + 2].toDouble()
                val l = sqrt(nx * nx + ny * ny + nz * nz)
                if (l < 1e-6) continue
                val dot = (got[0] * nx + got[1] * ny + got[2] * nz) / l
                assertTrue(dot > 0.999, "Eckpunkt $i: Winkel zu gross ($dot)")
            }
        }
    }
}
