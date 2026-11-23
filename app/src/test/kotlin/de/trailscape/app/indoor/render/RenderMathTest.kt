package de.trailscape.app.indoor.render

import de.trailscape.core.indoor.IndoorRouteSource
import de.trailscape.core.indoor.SurfaceKind
import de.trailscape.core.indoor.SurfaceSection
import de.trailscape.core.indoor.world.TrackPose
import de.trailscape.core.indoor.world.WorldTrack
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Koordinaten ENU → glTF, Modellmatrix, Falten und Verlaengern des Bands (docs/indoor.md 7.1). */
class RenderMathTest {
    private fun near(a: Double, b: Double, eps: Double = 1e-6) = assertTrue(abs(a - b) < eps, "erwartet $b, war $a")
    private fun near(a: V3, b: V3, eps: Double = 1e-5) {
        near(a.x, b.x, eps)
        near(a.y, b.y, eps)
        near(a.z, b.z, eps)
    }

    /** Ein gerades Band nach Norden, 100 m, Steigung 5 %. */
    private fun northTrack(loop: Boolean = false) = WorldTrack(
        lengthM = 100.0,
        stepM = 5.0,
        loop = loop,
        e = FloatArray(21) { 0f },
        n = FloatArray(21) { it * 5f },
        u = FloatArray(21) { it * 0.25f },
        surfaces = listOf(SurfaceSection(0.0, 100.0, SurfaceKind.GRAVEL)),
        source = IndoorRouteSource.NONE,
    )

    @Test
    fun enuNachGltf() {
        near(enuToGltf(1.0, 2.0, 3.0), V3(1.0, 3.0, -2.0))
        val (e, n, u) = gltfToEnu(enuToGltf(10.0, -20.0, 5.0))
        near(e, 10.0)
        near(n, -20.0)
        near(u, 5.0)
    }

    @Test
    fun fahrtrichtungen() {
        near(headingForward(0.0), V3(0.0, 0.0, -1.0)) // Nord = −Z
        near(headingForward(90.0), V3(1.0, 0.0, 0.0)) // Ost = +X
        near(headingForward(180.0), V3(0.0, 0.0, 1.0))
        near(headingRight(0.0), V3(1.0, 0.0, 0.0)) // rechts von Nord ist Ost
        near(headingRight(90.0), V3(0.0, 0.0, 1.0)) // rechts von Ost ist Sued (+Z)
    }

    @Test
    fun modellBlicktInFahrtrichtung() {
        for (h in listOf(0.0, 37.0, 90.0, 180.0, 271.0)) {
            val m = modelMatrix(V3(5.0, 1.0, -3.0), h, 0.0)
            val nose = transformPoint(m, V3(0.0, 0.0, 1.0)) - V3(5.0, 1.0, -3.0)
            near(nose, headingForward(h))
            val up = transformPoint(m, V3(0.0, 1.0, 0.0)) - V3(5.0, 1.0, -3.0)
            near(up, V3.UP)
        }
    }

    @Test
    fun nasehochBergauf() {
        val m = modelMatrix(V3.ZERO, 0.0, 10.0)
        val nose = transformPoint(m, V3(0.0, 0.0, 1.0))
        assertTrue(nose.y > 0.09, "Nase hebt sich bergauf: ${nose.y}")
        near(nose.length(), 1.0)
        val down = transformPoint(modelMatrix(V3.ZERO, 0.0, -10.0), V3(0.0, 0.0, 1.0))
        assertTrue(down.y < -0.09)
    }

    @Test
    fun verschiebungSpaltenweise() {
        val m = translationMatrix(1.0, 2.0, 3.0)
        assertEquals(1f, m[12])
        assertEquals(2f, m[13])
        assertEquals(3f, m[14])
        near(transformPoint(m, V3(1.0, 1.0, 1.0)), V3(2.0, 3.0, 4.0))
    }

    @Test
    fun faltenUndVerlaengern() {
        val loop = northTrack(loop = true)
        near(loop.fold(250.0), 50.0)
        near(loop.fold(-10.0), 90.0)
        val open = northTrack()
        near(open.fold(250.0), 100.0)
        near(open.fold(Double.NaN), 0.0)
        // Vor dem Start geradeaus zurueck verlaengert (die Kamera steht dort).
        val before = open.poseExtended(-6.0)
        near(before.n, -6.0, 1e-4)
        near(before.u, -0.3, 1e-4)
        val after = open.poseExtended(110.0)
        near(after.n, 110.0, 1e-4)
    }

    @Test
    fun seitlicherVersatz() {
        val p = TrackPose(0.0, 0.0, 0.0, 0.0, 0.0)
        near(p.lateral(1.0), V3(1.0, 0.0, 0.0)) // rechts von Nord = Ost
        near(p.lateral(-1.0), V3(-1.0, 0.0, 0.0))
    }

    @Test
    fun winkelabstand() {
        near(angleDeltaDeg(350.0, 10.0), 20.0)
        near(angleDeltaDeg(10.0, 350.0), -20.0)
        near(angleDeltaDeg(0.0, 180.0), 180.0)
    }

    @Test
    fun spurversatz() {
        near(laneOffsetM(5.0), 1.1)
        near(laneOffsetM(1.5), 0.33)
        assertTrue(laneOffsetM(0.6) >= 0.0)
        assertTrue(laneOffsetM(1.5) <= 1.5 / 2 - 0.4 + 1e-9)
        near(laneOffsetM(Double.NaN), laneOffsetM(3.0))
    }
}
