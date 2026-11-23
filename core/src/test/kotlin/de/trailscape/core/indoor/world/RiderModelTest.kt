package de.trailscape.core.indoor.world

import java.io.File
import java.nio.file.Files
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RiderModelTest {
    private val rider = buildRiderGlb()
    private val ghost = buildGhostGlb()

    @Test
    fun `beide Modelle bestehen die Formatpruefung`() {
        assertEquals(emptyList(), checkModelGlb(rider))
        assertEquals(emptyList(), checkModelGlb(ghost))
    }

    @Test
    fun `Animationen pedal und roll dauern genau eine Sekunde`() {
        for (bytes in listOf(rider, ghost)) {
            val info = parseGlb(bytes)
            assertEquals(2, info.animations.size)
            val pedal = assertNotNull(info.animation(WorldModels.ANIM_PEDAL))
            val roll = assertNotNull(info.animation(WorldModels.ANIM_ROLL))
            assertEquals(1.0, pedal.durationS, 1e-6)
            assertEquals(1.0, roll.durationS, 1e-6)
            assertEquals(0.0, pedal.startS, 1e-6)
            assertEquals(0.0, roll.startS, 1e-6)
            // Kurbel, zwei Pedale, je Bein drei Glieder, Oberkoerper; roll nur die beiden Laufraeder.
            assertEquals(10, pedal.channelCount)
            assertEquals(2, roll.channelCount)
            assertEquals(0, info.skinCount)
        }
    }

    @Test
    fun `Dreiecke und Bytes bleiben zusammen im Rahmen`() {
        val r = parseGlb(rider)
        val g = parseGlb(ghost)
        assertTrue(r.triangleCount + g.triangleCount <= 60_000, "zusammen ${r.triangleCount + g.triangleCount} Dreiecke")
        assertTrue(rider.size + ghost.size <= 2 * 1024 * 1024, "zusammen ${rider.size + ghost.size} Bytes")
        // Das Budget soll auch genutzt werden: runde Silhouetten brauchen Dreiecke.
        assertTrue(r.triangleCount >= 20_000, "Fahrer hat nur ${r.triangleCount} Dreiecke")
        assertTrue(r.images.isEmpty() && g.images.isEmpty())
    }

    @Test
    fun `Knoten und Materialien tragen die vereinbarten Namen`() {
        val expected = listOf(
            RiderNodes.ROOT, RiderNodes.BIKE, RiderNodes.WHEEL_FRONT, RiderNodes.WHEEL_REAR, RiderNodes.CRANK, RiderNodes.PEDAL_L, RiderNodes.PEDAL_R,
            RiderNodes.BODY, RiderNodes.THIGH_L, RiderNodes.SHANK_L, RiderNodes.FOOT_L, RiderNodes.THIGH_R, RiderNodes.SHANK_R, RiderNodes.FOOT_R,
        )
        for (bytes in listOf(rider, ghost)) {
            val info = parseGlb(bytes)
            assertEquals(expected, info.nodes.map { it.name })
            assertEquals(1, info.roots.size)
            assertEquals(RiderNodes.THIGH_L, info.nodes[info.node(RiderNodes.SHANK_L)!!.parent!!].name)
            assertEquals(RiderNodes.SHANK_L, info.nodes[info.node(RiderNodes.FOOT_L)!!.parent!!].name)
            assertEquals(RiderNodes.CRANK, info.nodes[info.node(RiderNodes.PEDAL_R)!!.parent!!].name)
        }
        assertEquals(RiderMat.entries.map { it.matName }, parseGlb(rider).materials.map { it.name })
        assertTrue(parseGlb(rider).materials.all { it.alphaMode == "OPAQUE" && it.metallicFactor == 0.0 && it.roughnessFactor >= 0.6 })
        val ghostMaterial = parseGlb(ghost).materials.single()
        assertEquals(GHOST_MATERIAL, ghostMaterial.name)
        assertEquals("BLEND", ghostMaterial.alphaMode)
        assertTrue(!ghostMaterial.doubleSided)
    }

    @Test
    fun `Ursprung am Boden zwischen den Raedern und Blick nach plus Z`() {
        val r = RiderRig.WHEEL_R
        assertEquals(WorldModels.WHEEL_CIRCUMFERENCE_M, 2 * Math.PI * r, 1e-9)
        assertEquals(0.0, RiderRig.FRONT_AXLE.z + RiderRig.REAR_AXLE.z, 1e-9)
        assertTrue(RiderRig.FRONT_AXLE.z > 0.5)
        for (bytes in listOf(rider, ghost)) {
            val info = parseGlb(bytes)
            val mn = assertNotNull(info.boundsMin)
            val mx = assertNotNull(info.boundsMax)
            // Die Reifen beruehren y = 0 und sind die vordersten und hintersten Teile.
            assertEquals(0.0, mn[1], 1e-4)
            assertEquals(RiderRig.FRONT_Z + r, mx[2], 1e-4)
            assertEquals(RiderRig.REAR_Z - r, mn[2], 1e-4)
            // Lebensgroesse, seitlich symmetrisch.
            assertTrue(mx[1] in 1.6..1.8, "Hoehe ${mx[1]}")
            assertEquals(-mn[0], mx[0], 1e-3)
            assertTrue(mx[0] in 0.2..0.35, "halbe Breite ${mx[0]}")
        }
        // Der Fahrer blickt nach vorn: Huefte hinter dem Tretlager, Pedal bei Kurbel „vorn" vor dem Tretlager.
        assertTrue(RiderRig.hip(true).z < RiderRig.BB.z)
        assertTrue(RiderRig.pedalAxle(0.25, true).z > RiderRig.BB.z + 0.17)
        assertTrue(RiderRig.hip(true).x > 0 && RiderRig.hip(false).x < 0)
    }

    /** Vorwaertskinematik aus den Knotendrehungen, wie die Darstellung sie ausfuehrt. */
    private class Joints(val knee: Vec3, val ankle: Vec3, val contact: Vec3, val toe: Vec3)

    private fun forward(hip: Vec3, thigh: Quat, shank: Quat, foot: Quat): Joints {
        val knee = hip + thigh.rotate(Vec3(0.0, -RiderRig.THIGH, 0.0))
        val shankWorld = thigh * shank
        val ankle = knee + shankWorld.rotate(Vec3(0.0, -RiderRig.SHANK, 0.0))
        val footWorld = shankWorld * foot
        return Joints(knee, ankle, ankle + footWorld.rotate(RiderRig.FOOT_TO_AXLE), footWorld.rotate(Vec3.Z))
    }

    @Test
    fun `Fuss bleibt in jeder Phase auf der Pedalachse`() {
        val n = RiderRig.KEYFRAMES
        for (left in listOf(true, false)) {
            for (k in 0 until n) {
                val a = RiderRig.legPose(k.toDouble() / n, left)
                val b = RiderRig.legPose(((k + 1) % n).toDouble() / n, left)
                // An der Stuetzstelle und dazwischen (lineare Interpolation der Drehungen, wie im Renderer).
                for (t in listOf(0.0, 0.25, 0.5, 0.75)) {
                    val phase = (k + t) / n
                    val j = forward(a.hip, Quat.slerp(a.thigh, b.thigh, t), Quat.slerp(a.shank, b.shank, t), Quat.slerp(a.foot, b.foot, t))
                    val axle = RiderRig.pedalAxle(phase, left)
                    val miss = (j.contact - axle).length()
                    assertTrue(miss < 0.01, "Fuss ${if (left) "links" else "rechts"} bei Phase $phase um ${miss * 1000} mm neben der Pedalachse")
                    // Zwischen den Stuetzstellen bleibt der Fehler weit unter einem Millimeter an der Stuetzstelle selbst.
                    if (t == 0.0) assertTrue(miss < 1e-6, "Fuss an der Stuetzstelle $k um $miss m daneben")
                }
            }
        }
    }

    @Test
    fun `Knie bleibt anatomisch und Gliedlaengen sind konstant`() {
        var lo = Double.MAX_VALUE
        var hi = 0.0
        for (left in listOf(true, false)) {
            val side = if (left) 1.0 else -1.0
            for (i in 0 until 360) {
                val p = RiderRig.legPose(i / 360.0, left)
                assertEquals(RiderRig.THIGH, (p.knee - p.hip).length(), 1e-9)
                assertEquals(RiderRig.SHANK, (p.ankle - p.knee).length(), 1e-9)
                assertTrue(p.kneeAngleDeg in 65.0..150.0, "Kniewinkel ${p.kneeAngleDeg} bei Phase ${i / 360.0}")
                lo = minOf(lo, p.kneeAngleDeg)
                hi = maxOf(hi, p.kneeAngleDeg)
                // Das Knie knickt nach vorn: es liegt vor der Linie Huefte–Knoechel …
                val mid = p.hip.lerp(p.ankle, 0.5)
                assertTrue(p.knee.z > mid.z + 0.05, "Knie schlaegt durch bei Phase ${i / 360.0}")
                // … und bricht seitlich nicht aus: zwischen Huefte und Fuss, auf der eigenen Seite.
                assertTrue(p.knee.x * side in RiderRig.HIP_X - 0.005..RiderRig.PEDAL_X + 0.005, "Knie seitlich bei ${p.knee.x}")
                // Die Drehungen aus dem Knoten ergeben dieselben Gelenkpunkte.
                val j = forward(p.hip, p.thigh, p.shank, p.foot)
                assertTrue((j.knee - p.knee).length() < 1e-9)
                assertTrue((j.ankle - p.ankle).length() < 1e-9)
                assertTrue((j.contact - p.axle).length() < 1e-9)
                // Die Fussspitze zeigt nach vorn und hoechstens maessig nach unten.
                assertTrue(j.toe.z > 0.9 && j.toe.y < 0.0 && j.toe.y > -0.45 && abs(j.toe.x) < 1e-9, "Fussrichtung ${j.toe}")
            }
        }
        // Der Tritt nutzt das Bein aus, ohne es durchzustrecken.
        assertTrue(lo < 75.0 && hi > 140.0, "Kniewinkel nur $lo..$hi")
    }

    @Test
    fun `Drehungen laufen stetig und schliessen nahtlos`() {
        val n = RiderRig.KEYFRAMES
        for (left in listOf(true, false)) {
            for (k in 0 until n) {
                val a = RiderRig.legPose(k.toDouble() / n, left)
                val b = RiderRig.legPose(((k + 1) % n).toDouble() / n, left)
                for ((p, q) in listOf(a.thigh to b.thigh, a.shank to b.shank, a.foot to b.foot)) {
                    val dot = abs(p.x * q.x + p.y * q.y + p.z * q.z + p.w * q.w)
                    assertTrue(dot > 0.995, "Sprung zwischen Stuetzstelle $k und ${k + 1}: $dot")
                }
            }
        }
        val sway = RiderRig.bodySway(0.25)
        assertTrue(abs(sway.z) < 0.02 && abs(sway.x) < 1e-12 && abs(sway.y) < 1e-12)
    }

    @Test
    fun `zweimaliges Erzeugen liefert dieselben Bytes`() {
        assertContentEquals(rider, buildRiderGlb())
        assertContentEquals(ghost, buildGhostGlb())
        val dir = Files.createTempDirectory("indoor-models").toFile()
        try {
            writeIndoorModels(dir)
            assertContentEquals(rider, File(dir, WorldModels.RIDER).readBytes())
            assertContentEquals(ghost, File(dir, WorldModels.GHOST).readBytes())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `eingecheckte Assets stimmen mit der Erzeugung ueberein`() {
        var root: File? = File("").absoluteFile
        while (root != null && !File(root, "app/src/main/assets/${WorldModels.ASSET_DIR}").isDirectory) root = root.parentFile
        val dir = File(assertNotNull(root, "Verzeichnis app/src/main/assets/${WorldModels.ASSET_DIR} nicht gefunden"), "app/src/main/assets/${WorldModels.ASSET_DIR}")
        val hint = "weicht von der Erzeugung ab — mit writeIndoorModels(File(\"${dir.path}\")) neu schreiben"
        assertTrue(File(dir, WorldModels.RIDER).readBytes().contentEquals(rider), "${WorldModels.RIDER} $hint")
        assertTrue(File(dir, WorldModels.GHOST).readBytes().contentEquals(ghost), "${WorldModels.GHOST} $hint")
        assertTrue(File(dir, "LICENSES.md").isFile)
    }
}
