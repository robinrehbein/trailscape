package de.trailscape.core.indoor.world

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
        assertEquals(RIDER_MATERIAL_NAMES, parseGlb(rider).materials.map { it.name })
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
            assertContentEquals(buildRiderLodGlb(), File(dir, RiderModelFiles.RIDER_LOD).readBytes())
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
        assertGlbEquivalent(WorldModels.RIDER, rider, File(dir, WorldModels.RIDER).readBytes(), hint)
        assertGlbEquivalent(WorldModels.GHOST, ghost, File(dir, WorldModels.GHOST).readBytes(), hint)
        assertGlbEquivalent(RiderModelFiles.RIDER_LOD, buildRiderLodGlb(), File(dir, RiderModelFiles.RIDER_LOD).readBytes(), hint)
        assertTrue(File(dir, "LICENSES.md").isFile)
    }

    private fun assertGlbEquivalent(assetName: String, expectedBytes: ByteArray, actualBytes: ByteArray, hint: String) {
        val label = "$assetName $hint"
        val tolerance = 5e-3f

        assertEquals(emptyList(), checkModelGlb(actualBytes), "$label: checkModelGlb")

        val expectedInfo = parseGlb(expectedBytes)
        val actualInfo = parseGlb(actualBytes)
        assertEquals(expectedInfo.generator, actualInfo.generator, "$label: generator")
        assertEquals(expectedInfo.sceneCount, actualInfo.sceneCount, "$label: sceneCount")
        assertEquals(expectedInfo.roots, actualInfo.roots, "$label: roots")
        assertEquals(expectedInfo.meshCount, actualInfo.meshCount, "$label: meshCount")
        assertEquals(expectedInfo.textureCount, actualInfo.textureCount, "$label: textureCount")
        assertEquals(expectedInfo.cameraCount, actualInfo.cameraCount, "$label: cameraCount")
        assertEquals(expectedInfo.skinCount, actualInfo.skinCount, "$label: skinCount")
        assertEquals(expectedInfo.extensionsUsed, actualInfo.extensionsUsed, "$label: extensionsUsed")
        assertEquals(expectedInfo.extensionsRequired, actualInfo.extensionsRequired, "$label: extensionsRequired")
        assertEquals(expectedInfo.triangleCount, actualInfo.triangleCount, "$label: triangleCount")

        assertEquals(expectedInfo.nodes.size, actualInfo.nodes.size, "$label: nodeCount")
        for (i in expectedInfo.nodes.indices) {
            val en = expectedInfo.nodes[i]
            val an = actualInfo.nodes[i]
            assertEquals(en.name, an.name, "$label: node[$i].name")
            assertEquals(en.parent, an.parent, "$label: node[$i].parent")
            assertEquals(en.children, an.children, "$label: node[$i].children")
            assertEquals(en.mesh, an.mesh, "$label: node[$i].mesh")
            assertEquals(en.hasTransform, an.hasTransform, "$label: node[$i].hasTransform")
        }

        assertEquals(expectedInfo.materials.size, actualInfo.materials.size, "$label: materialCount")
        for (i in expectedInfo.materials.indices) {
            val em = expectedInfo.materials[i]
            val am = actualInfo.materials[i]
            assertEquals(em.name, am.name, "$label: material[$i].name")
            assertEquals(em.alphaMode, am.alphaMode, "$label: material[$i].alphaMode")
            assertEquals(em.doubleSided, am.doubleSided, "$label: material[$i].doubleSided")
            assertEquals(em.unlit, am.unlit, "$label: material[$i].unlit")
            assertEquals(em.baseColorTexture, am.baseColorTexture, "$label: material[$i].baseColorTexture")
            assertEquals(em.metallicFactor, am.metallicFactor, tolerance.toDouble(), "$label: material[$i].metallicFactor")
            assertEquals(em.roughnessFactor, am.roughnessFactor, tolerance.toDouble(), "$label: material[$i].roughnessFactor")
        }

        assertEquals(expectedInfo.animations.size, actualInfo.animations.size, "$label: animationCount")
        for (i in expectedInfo.animations.indices) {
            val ea = expectedInfo.animations[i]
            val aa = actualInfo.animations[i]
            assertEquals(ea.name, aa.name, "$label: animation[$i].name")
            assertEquals(ea.channelCount, aa.channelCount, "$label: animation[$i].channelCount")
            assertEquals(ea.startS, aa.startS, tolerance.toDouble(), "$label: animation[$i].startS")
            assertEquals(ea.durationS, aa.durationS, tolerance.toDouble(), "$label: animation[$i].durationS")
        }

        val eMin = assertNotNull(expectedInfo.boundsMin, "$label: expected boundsMin")
        val aMin = assertNotNull(actualInfo.boundsMin, "$label: actual boundsMin")
        val eMax = assertNotNull(expectedInfo.boundsMax, "$label: expected boundsMax")
        val aMax = assertNotNull(actualInfo.boundsMax, "$label: actual boundsMax")
        for (c in 0 until 3) {
            assertEquals(eMin[c], aMin[c], tolerance.toDouble(), "$label: boundsMin[$c]")
            assertEquals(eMax[c], aMax[c], tolerance.toDouble(), "$label: boundsMax[$c]")
        }

        val expectedMeshFile = GlbMeshFile(expectedBytes)
        val actualMeshFile = GlbMeshFile(actualBytes)
        assertEquals(expectedMeshFile.meshNodeNames, actualMeshFile.meshNodeNames, "$label: meshNodeNames")
        for (nodeName in expectedMeshFile.meshNodeNames) {
            val ePrims = assertNotNull(expectedMeshFile.primitives(nodeName), "$label: expected primitives($nodeName)")
            val aPrims = assertNotNull(actualMeshFile.primitives(nodeName), "$label: actual primitives($nodeName)")
            assertEquals(ePrims.size, aPrims.size, "$label: primitives($nodeName).size")
            for (pi in ePrims.indices) {
                val ep = ePrims[pi]
                val ap = aPrims[pi]
                val pLabel = "$label: node=$nodeName prim=$pi"
                assertEquals(ep.material, ap.material, "$pLabel material")
                assertEquals(ep.vertexCount, ap.vertexCount, "$pLabel vertexCount")
                assertEquals(ep.triangleCount, ap.triangleCount, "$pLabel triangleCount")
                assertContentEquals(ep.indices, ap.indices, "$pLabel indices")
                assertFloatArrayClose("$pLabel positions", ep.positions, ap.positions, tolerance)
                assertFloatArrayClose("$pLabel normals", ep.normals, ap.normals, tolerance)
                assertFloatArrayClose("$pLabel uvs", ep.uvs, ap.uvs, tolerance)
                val ec = ep.colors
                if (ec == null) {
                    assertEquals(null, ap.colors, "$pLabel colors")
                } else {
                    val ac = assertNotNull(ap.colors, "$pLabel colors")
                    assertEquals(ec.size, ac.size, "$pLabel colors.size")
                    for (ci in ec.indices) {
                        val diff = abs((ec[ci].toInt() and 0xFF) - (ac[ci].toInt() and 0xFF))
                        assertTrue(diff <= 1, "$pLabel colors[$ci] differs by $diff")
                    }
                }
            }
        }

        val eGlb = RawGlb.read(expectedBytes)
        val aGlb = RawGlb.read(actualBytes)
        assertEquals(eGlb.accessors.size, aGlb.accessors.size, "$label: accessors.size")
        assertEquals(eGlb.bufferViews.size, aGlb.bufferViews.size, "$label: bufferViews.size")
        assertEquals(eGlb.meshes.size, aGlb.meshes.size, "$label: meshes.size")
        assertEquals(eGlb.materials.size, aGlb.materials.size, "$label: raw materials.size")

        for (i in eGlb.materials.indices) {
            val em = eGlb.materials[i]
            val am = aGlb.materials[i]
            val ePbr = em.obj("pbrMetallicRoughness")
            val aPbr = am.obj("pbrMetallicRoughness")
            val eBase = ePbr?.floatList("baseColorFactor") ?: floatArrayOf(1f, 1f, 1f, 1f)
            val aBase = aPbr?.floatList("baseColorFactor") ?: floatArrayOf(1f, 1f, 1f, 1f)
            assertFloatArrayClose("$label: material[$i].baseColorFactor", eBase, aBase, tolerance)

            val eEmissive = em.floatList("emissiveFactor")
            val aEmissive = am.floatList("emissiveFactor")
            assertEquals(eEmissive != null, aEmissive != null, "$label: material[$i].emissiveFactor presence")
            if (eEmissive != null && aEmissive != null) {
                assertFloatArrayClose("$label: material[$i].emissiveFactor", eEmissive, aEmissive, tolerance)
            }

            val eAlphaCutoff = em.float("alphaCutoff")
            val aAlphaCutoff = am.float("alphaCutoff")
            assertEquals(eAlphaCutoff != null, aAlphaCutoff != null, "$label: material[$i].alphaCutoff presence")
            if (eAlphaCutoff != null && aAlphaCutoff != null) {
                assertEquals(eAlphaCutoff, aAlphaCutoff, tolerance, "$label: material[$i].alphaCutoff")
            }

            val eSpec = em.obj("extensions")?.obj("KHR_materials_specular")
            val aSpec = am.obj("extensions")?.obj("KHR_materials_specular")
            assertEquals(eSpec != null, aSpec != null, "$label: material[$i].KHR_materials_specular presence")
            if (eSpec != null && aSpec != null) {
                assertEquals(
                    eSpec.float("specularFactor") ?: 1f,
                    aSpec.float("specularFactor") ?: 1f,
                    tolerance,
                    "$label: material[$i].KHR_materials_specular.specularFactor",
                )
            }
        }

        for (i in eGlb.accessors.indices) {
            val ea = eGlb.accessors[i]
            val aa = aGlb.accessors[i]
            assertEquals(ea.str("type"), aa.str("type"), "$label: accessor[$i].type")
            assertEquals(ea.int("componentType"), aa.int("componentType"), "$label: accessor[$i].componentType")
            assertEquals(ea.int("count"), aa.int("count"), "$label: accessor[$i].count")
            assertEquals(ea.bool("normalized"), aa.bool("normalized"), "$label: accessor[$i].normalized")
        }

        for (i in eGlb.nodes.indices) {
            val en = eGlb.nodes[i]
            val an = aGlb.nodes[i]
            for (key in listOf("translation", "rotation", "scale", "matrix")) {
                val ev = en.floatList(key)
                val av = an.floatList(key)
                if (ev == null) {
                    assertEquals(null, av, "$label: node[$i].$key")
                } else {
                    val actualVec = assertNotNull(av, "$label: node[$i].$key")
                    assertFloatArrayClose("$label: node[$i].$key", ev, actualVec, tolerance)
                }
            }
        }

        for (mi in eGlb.meshes.indices) {
            val ePrims = eGlb.meshes[mi].list("primitives")
            val aPrims = aGlb.meshes[mi].list("primitives")
            assertEquals(ePrims.size, aPrims.size, "$label: mesh[$mi].primitives.size")
            for (pi in ePrims.indices) {
                val eAttrs = ePrims[pi]["attributes"] as JsonObject
                val aAttrs = aPrims[pi]["attributes"] as JsonObject
                assertEquals(eAttrs.keys, aAttrs.keys, "$label: mesh[$mi].prim[$pi].attributes.keys")
            }
        }

        assertEquals(eGlb.animations.size, aGlb.animations.size, "$label: raw animations.size")
        for (ai in eGlb.animations.indices) {
            val eAnim = eGlb.animations[ai]
            val aAnim = aGlb.animations[ai]
            assertEquals(eAnim.str("name"), aAnim.str("name"), "$label: anim[$ai].name")
            val eCh = eAnim.list("channels")
            val aCh = aAnim.list("channels")
            assertEquals(eCh.size, aCh.size, "$label: anim[$ai].channels.size")
            for (ci in eCh.indices) {
                assertEquals(eCh[ci].int("sampler"), aCh[ci].int("sampler"), "$label: anim[$ai].ch[$ci].sampler")
                val et = eCh[ci]["target"] as JsonObject
                val at = aCh[ci]["target"] as JsonObject
                assertEquals(et.int("node"), at.int("node"), "$label: anim[$ai].ch[$ci].target.node")
                assertEquals(et.str("path"), at.str("path"), "$label: anim[$ai].ch[$ci].target.path")
            }
            val eSamp = eAnim.list("samplers")
            val aSamp = aAnim.list("samplers")
            assertEquals(eSamp.size, aSamp.size, "$label: anim[$ai].samplers.size")
            for (si in eSamp.indices) {
                assertEquals(eSamp[si].str("interpolation"), aSamp[si].str("interpolation"), "$label: anim[$ai].sampler[$si].interpolation")
                val eIn = eGlb.readFloatAccessor(eSamp[si].int("input")!!)
                val aIn = aGlb.readFloatAccessor(aSamp[si].int("input")!!)
                assertFloatArrayClose("$label: anim[$ai].sampler[$si].input", eIn, aIn, tolerance)
                val eOut = eGlb.readFloatAccessor(eSamp[si].int("output")!!)
                val aOut = aGlb.readFloatAccessor(aSamp[si].int("output")!!)
                assertFloatArrayClose("$label: anim[$ai].sampler[$si].output", eOut, aOut, tolerance)
            }
        }
    }

    private fun assertFloatArrayClose(label: String, expected: FloatArray, actual: FloatArray, tolerance: Float) {
        assertEquals(expected.size, actual.size, "$label length")
        var maxDiff = 0f
        var maxIdx = -1
        for (i in expected.indices) {
            val d = abs(expected[i] - actual[i])
            if (d > maxDiff) {
                maxDiff = d
                maxIdx = i
            }
        }
        assertTrue(maxDiff <= tolerance, "$label max delta $maxDiff at [$maxIdx] (expected ${expected.getOrNull(maxIdx)}, actual ${actual.getOrNull(maxIdx)}) > $tolerance")
    }

    private class RawGlb(val json: JsonObject, private val bin: ByteArray) {
        val nodes = json.list("nodes")
        val meshes = json.list("meshes")
        val materials = json.list("materials")
        val accessors = json.list("accessors")
        val bufferViews = json.list("bufferViews")
        val animations = json.list("animations")

        fun readFloatAccessor(accessorIndex: Int): FloatArray {
            val acc = accessors[accessorIndex]
            val view = bufferViews[acc.int("bufferView")!!]
            val width = when (acc.str("type")) {
                "SCALAR" -> 1
                "VEC2" -> 2
                "VEC3" -> 3
                "VEC4" -> 4
                "MAT4" -> 16
                else -> error("Unsupported accessor type ${acc.str("type")}")
            }
            val ct = acc.int("componentType")!!
            val normalized = acc.bool("normalized") == true
            val count = acc.int("count")!!
            val offset = (view.int("byteOffset") ?: 0) + (acc.int("byteOffset") ?: 0)
            val compSize = when (ct) {
                5120, 5121 -> 1
                5122, 5123 -> 2
                else -> 4
            }
            val stride = view.int("byteStride") ?: (width * compSize)
            val bb = ByteBuffer.wrap(bin).order(ByteOrder.LITTLE_ENDIAN)
            return FloatArray(count * width) { idx ->
                val e = idx / width
                val c = idx % width
                val pos = offset + e * stride + c * compSize
                when (ct) {
                    5126 -> bb.getFloat(pos)
                    5121 -> {
                        val v = (bb.get(pos).toInt() and 0xFF).toFloat()
                        if (normalized) v / 255f else v
                    }
                    5123 -> {
                        val v = (bb.getShort(pos).toInt() and 0xFFFF).toFloat()
                        if (normalized) v / 65535f else v
                    }
                    else -> error("Unsupported float componentType $ct")
                }
            }
        }

        companion object {
            fun read(bytes: ByteArray): RawGlb {
                val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                val total = bb.getInt(8)
                val jsonLen = bb.getInt(12)
                val jsonObj = Json.parseToJsonElement(String(bytes, 20, jsonLen, Charsets.UTF_8)) as JsonObject
                var p = 20 + jsonLen
                var bin = ByteArray(0)
                if (p + 8 <= total) {
                    val binLen = bb.getInt(p)
                    val type = bb.getInt(p + 4)
                    if (type == 0x004E4942) bin = bytes.copyOfRange(p + 8, p + 8 + binLen)
                }
                return RawGlb(jsonObj, bin)
            }
        }
    }
}

private fun JsonObject.list(key: String): List<JsonObject> = (this[key] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun JsonObject.int(key: String): Int? = this[key]?.intVal()
private fun JsonObject.float(key: String): Float? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toFloatOrNull()
private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toBooleanStrictOrNull()
private fun JsonElement.intVal(): Int? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull()?.toInt()
private fun JsonObject.floatList(key: String): FloatArray? =
    (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content?.toFloatOrNull() }?.toFloatArray()
