package de.trailscape.app.indoor.render

import de.trailscape.core.indoor.IndoorRouteSource
import de.trailscape.core.indoor.SurfaceKind
import de.trailscape.core.indoor.SurfaceSection
import de.trailscape.core.indoor.world.CameraMode
import de.trailscape.core.indoor.world.WorldTrack
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Lenkerperspektive: Cockpit-Modell und Kamera. Schreibt das Cockpit ausserdem
 * nach `app/build/indoor/cockpit.glb` — der Filament-Referenzrenderer
 * (`tool/indoor-dev/viewer/filashots.mjs`) nimmt es von dort.
 */
class CockpitAndHandlebarTest {
    private fun track(e: DoubleArray, n: DoubleArray, u: DoubleArray, loop: Boolean = false) = WorldTrack(
        lengthM = 5.0 * (e.size - 1),
        stepM = 5.0,
        loop = loop,
        e = FloatArray(e.size) { e[it].toFloat() },
        n = FloatArray(n.size) { n[it].toFloat() },
        u = FloatArray(u.size) { u[it].toFloat() },
        surfaces = listOf(SurfaceSection(0.0, 5.0 * (e.size - 1), SurfaceKind.ASPHALT)),
        source = IndoorRouteSource.NONE,
    )

    private fun straightEast(gradePct: Double = 0.0): WorldTrack {
        val count = 60
        return track(DoubleArray(count) { it * 5.0 }, DoubleArray(count), DoubleArray(count) { it * 5.0 * gradePct / 100 })
    }

    /** Kreis mit Radius [r] im Uhrzeigersinn (Rechtskurve). */
    private fun circleRight(r: Double): WorldTrack {
        val count = (2 * Math.PI * r / 5.0).toInt() + 1
        val e = DoubleArray(count) { r * sin(it * 5.0 / r) }
        val n = DoubleArray(count) { r * cos(it * 5.0 / r) - r }
        return track(e, n, DoubleArray(count))
    }

    @Test
    fun `Cockpit ist gueltiges glTF mit Lenkerknoten und wird fuer das Werkzeug geschrieben`() {
        val bytes = CockpitModel.buildGlb()
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x46546C67, bb.int)
        assertEquals(2, bb.int)
        assertEquals(bytes.size, bb.int)
        val len = bb.int
        bb.int
        val json = Json.parseToJsonElement(String(bytes, 20, len)).jsonObject
        val names = json["nodes"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertTrue(CockpitModel.NODE_ROOT in names && CockpitModel.NODE_BARS in names, "$names")
        val tris = CockpitModel.build().triangleCount()
        assertTrue(tris in 2_000..20_000, "Dreiecke $tris")
        val out = File("build/indoor").apply { mkdirs() }
        File(out, "cockpit.glb").writeBytes(bytes)
    }

    @Test
    fun `Lenkeinschlag dreht um die Lenkachse`() {
        val m = CockpitModel.steerMatrix(0.0)
        assertEquals(1f, m[0], 1e-6f)
        assertEquals(CockpitModel.HEAD_TOP.y.toFloat(), m[13], 1e-6f)
        val q = CockpitModel.steerQuaternion(0.3)
        val axis = V3(q[0], q[1], q[2]).normalized()
        assertEquals(1.0, axis.dot(CockpitModel.STEER_AXIS), 1e-9)
    }

    @Test
    fun `eben und geradeaus Auge ueber und hinter dem Lenker, Blick knapp unter der Waagrechten`() {
        val t = straightEast()
        val p = CameraRig.desired(t, 100.0, 0.0, CameraMode.HANDLEBAR)
        assertEquals(CameraRig.HANDLEBAR_EYE.y, p.eye.y, 1e-6)
        // Fahrtrichtung Ost: hinter dem Rad heisst kleineres x
        assertEquals(100.0 + CameraRig.HANDLEBAR_EYE.z, p.eye.x, 1e-6)
        val d = (p.target - p.eye).normalized()
        assertEquals(-sin(Math.toRadians(CameraRig.HANDLEBAR_LOOK_DOWN_DEG)), d.y, 1e-6)
        assertTrue(d.x > 0.99)
        assertEquals(1.0, p.up.y, 1e-9)
    }

    @Test
    fun `Haende und Griffe liegen im Bild, im Hochformat ueber dem unteren Viertel`() {
        // Winkel unter der Blickrichtung zu den Haenden auf den Griffen
        val eye = CameraRig.HANDLEBAR_EYE
        val hands = CockpitModel.BAR_CENTER + V3(0.21, 0.07, 0.09)
        val below = Math.toDegrees(atan((eye.y - hands.y) / (hands.z - eye.z))) - CameraRig.HANDLEBAR_LOOK_DOWN_DEG
        for (portrait in listOf(true, false)) {
            val p = FilamentCore.projectionFor(if (portrait) 0.75 else 16.0 / 9.0, handlebar = true)
            val t = tan(Math.toRadians(p.fovDeg / 2))
            // NDC-y der Haende: Projektion plus Verschiebung; Bildhoehe von oben 0..1
            val ndc = -tan(Math.toRadians(below)) / t + 2 * p.shiftY
            val fromTop = (1 - ndc) / 2
            assertTrue(fromTop in 0.6..0.95, "Haende bei ${"%.2f".format(fromTop)} der Hoehe (hoch=$portrait)")
            // Horizont (Blick waagrecht) im oberen Teil
            val horizon = (1 - (tan(Math.toRadians(CameraRig.HANDLEBAR_LOOK_DOWN_DEG)) / t + 2 * p.shiftY)) / 2
            assertTrue(horizon in 0.3..0.48, "Horizont bei $horizon (hoch=$portrait)")
        }
    }

    @Test
    fun `bergauf hebt sich der Blick, aber weniger als das Rad`() {
        val t = straightEast(10.0)
        val a = CameraRig.handlebarAngles(t, 100.0, 0.0, 6.0)
        assertEquals(atan(0.10), a.pitchRad, 1e-6)
        val p = CameraRig.handlebarPose(t.poseExtended(100.0).lateral(0.0), a)
        val d = (p.target - p.eye).normalized()
        val gaze = Math.asin(d.y)
        assertTrue(gaze > 0.0 && gaze < a.pitchRad, "Blick $gaze, Rad ${a.pitchRad}")
    }

    @Test
    fun `Rechtskurve neigt nach rechts, schaut hinein und schlaegt rechts ein`() {
        val t = circleRight(40.0)
        val a = CameraRig.handlebarAngles(t, 60.0, 0.0, 9.0)
        assertTrue(a.leanRad > 0.02 && a.leanRad <= CameraRig.HANDLEBAR_MAX_LEAN_RAD + 1e-9, "Schraeglage ${a.leanRad}")
        assertTrue(a.lookYawRad > 0.02, "Blick in die Kurve ${a.lookYawRad}")
        assertTrue(a.steerRad < 0.0, "Einschlag rechts ${a.steerRad}")
        val still = CameraRig.handlebarAngles(t, 60.0, 0.0, 0.0)
        assertEquals(0.0, still.leanRad, 1e-12)
    }

    @Test
    fun `Daempfung bewegt nur die Winkel, das Cockpit bleibt fest zur Kamera`() {
        val t = circleRight(30.0)
        val rig = CameraRig().apply { mode = CameraMode.HANDLEBAR }
        var d = 20.0
        repeat(40) {
            d += 0.3
            val pose = rig.update(t, d, 0.5, 1.0 / 30, 9.0)
            val cp = rig.cockpit!!
            // Auge = Rahmenmatrix × HANDLEBAR_EYE
            val m = cp.matrix
            val e = CameraRig.HANDLEBAR_EYE
            val x = m[0] * e.x + m[4] * e.y + m[8] * e.z + m[12]
            val y = m[1] * e.x + m[5] * e.y + m[9] * e.z + m[13]
            val z = m[2] * e.x + m[6] * e.y + m[10] * e.z + m[14]
            assertTrue(abs(pose.eye.x - x) < 1e-4 && abs(pose.eye.y - y) < 1e-4 && abs(pose.eye.z - z) < 1e-4)
        }
        rig.mode = CameraMode.CHASE
        rig.update(t, d, 0.5, 1.0 / 30)
        assertEquals(null, rig.cockpit)
    }
}
