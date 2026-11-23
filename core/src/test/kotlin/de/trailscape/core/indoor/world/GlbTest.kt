package de.trailscape.core.indoor.world

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Suppress("UNCHECKED_CAST")
class GlbTest {
    // -----------------------------------------------------------------------
    // Testdaten
    // -----------------------------------------------------------------------

    private fun texture(size: Int, seed: Int): RgbaImage {
        val img = RgbaImage(size, size)
        for (y in 0 until size) for (x in 0 until size) {
            val n = Noise.tiledFbm(x + 0.5, y + 0.5, size, 4, seed, 4)
            img[x, y] = Argb.of(200 + (n * 50).toInt(), 90 + (n * 60).toInt(), 120 + (n * 40).toInt(), 60)
        }
        return img
    }

    private fun grid(n: Int, size: Double): MeshBuilder {
        val m = MeshBuilder((n + 1) * (n + 1), n * n * 2)
        for (j in 0..n) for (i in 0..n) {
            val x = i * size / n - size / 2
            val z = j * size / n - size / 2
            m.vertex(x, sin(x * 0.05) * 2 + cos(z * 0.07), z, 0.0, 1.0, 0.0, i.toDouble() / n, j.toDouble() / n)
        }
        for (j in 0 until n) for (i in 0 until n) {
            val a = j * (n + 1) + i
            m.quad(a, a + n + 1, a + n + 2, a + 1)
        }
        m.computeSmoothNormals()
        return m
    }

    private class Tile(val bytes: ByteArray, val triangles: Int)

    private fun cleanTile(animate: Boolean = false, gridN: Int = 16): Tile {
        val s = GlbScene()
        val jpg = encodeJpegBaseline(texture(64, 1))
        val png = encodePng(texture(32, 2), withAlpha = true)
        val tTerrain = s.addTexture(jpg, "image/jpeg", repeat = false)
        val tRoute = s.addTexture(png, "image/png", repeat = true)
        assertEquals(tTerrain, s.addTexture(jpg.copyOf(), "image/jpeg", repeat = false))
        val mTerrain = s.addMaterial(GlbMaterial(WorldNames.MAT_TERRAIN, texture = tTerrain))
        val mRoute = s.addMaterial(GlbMaterial(WorldNames.MAT_ROUTE_PREFIX + "gravel", texture = tRoute, roughness = 0.8f, alphaMode = GlbAlphaMode.BLEND))
        val mTrunk = s.addMaterial(GlbMaterial(WorldNames.MAT_TREE_TRUNK, baseColor = floatArrayOf(0.3f, 0.2f, 0.1f, 1f)))
        val mLeaves = s.addMaterial(GlbMaterial(WorldNames.MAT_TREE_LEAVES, texture = tRoute, alphaMode = GlbAlphaMode.MASK, doubleSided = true))
        val mWater = s.addMaterial(GlbMaterial(WorldNames.MAT_WATER, baseColor = floatArrayOf(0.2f, 0.3f, 0.4f, 1f), unlit = true))
        var tris = 0

        val terrain = s.addNode(WorldNames.NODE_TERRAIN)
        val sub = s.addNode(WorldNames.terrainSubName(0, 0), parent = terrain)
        val g = grid(gridN, 64.0)
        s.addMesh(sub, g, mTerrain)
        tris += g.triangleCount

        val route = s.addNode(WorldNames.NODE_ROUTE)
        val strip = MeshBuilder()
        for (i in 0..20) {
            val z = -30.0 + i * 3
            strip.vertex(-2.0, 2.1, z, 0.0, 1.0, 0.0, 0.0, i * 0.5, Argb.withAlpha(Argb.WHITE, 0))
            strip.vertex(-1.5, 2.1, z, 0.0, 1.0, 0.0, 0.2, i * 0.5)
            strip.vertex(1.5, 2.1, z, 0.0, 1.0, 0.0, 0.8, i * 0.5)
            strip.vertex(2.0, 2.1, z, 0.0, 1.0, 0.0, 1.0, i * 0.5, Argb.withAlpha(Argb.WHITE, 0))
            if (i > 0) for (k in 0 until 3) {
                val a = (i - 1) * 4 + k
                strip.quad(a, a + 4, a + 5, a + 1)
            }
        }
        s.addMesh(route, strip, mRoute)
        tris += strip.triangleCount

        val trees = s.addNode(WorldNames.NODE_TREES)
        val trunk = MeshBuilder()
        val leaves = MeshBuilder()
        for (k in 0 until 3) {
            val x = k * 6.0 - 6.0
            trunk.addLoft(listOf(Vec3(x, 0.0, 10.0), Vec3(x, 2.0, 10.0), Vec3(x + 0.2, 4.0, 10.0)), doubleArrayOf(0.3, 0.25, 0.15), 8)
            leaves.addEllipsoid(x, 5.0, 10.0, 2.0, 2.5, 2.0, 12, 8, Argb.rgb(150 + k * 30, 220, 140))
        }
        s.addMesh(trees, trunk, mTrunk)
        s.addMesh(trees, leaves, mLeaves)
        tris += trunk.triangleCount + leaves.triangleCount

        val water = s.addNode(WorldNames.NODE_WATER)
        val pond = MeshBuilder()
        pond.addPolygon(listOf(doubleArrayOf(10.0, 10.0, 20.0, 10.0, 22.0, 18.0, 12.0, 20.0), doubleArrayOf(14.0, 13.0, 15.0, 15.0, 16.0, 13.0)), 0.5, uvPerMeter = 0.1)
        s.addMesh(water, pond, mWater)
        tris += pond.triangleCount

        val extra = s.addNode("x_marker")
        s.addMesh(extra, MeshBuilder(), mTrunk) // leer → uebersprungen
        if (animate) {
            s.addAnimation("wiggle", listOf(GlbChannel(extra, GlbPath.TRANSLATION, floatArrayOf(0f, 1f), floatArrayOf(0f, 0f, 0f, 0f, 1f, 0f))))
        }
        assertEquals(tris, s.triangleCount)
        assertEquals(textureGpuBytes(64) + textureGpuBytes(32), s.textureGpuBytes())
        return Tile(s.toBytes(), tris)
    }

    private fun model(pedalDuration: Float = 1f, withRoll: Boolean = true): ByteArray {
        val s = GlbScene()
        val mFrame = s.addMaterial(GlbMaterial("x_frame", baseColor = floatArrayOf(0.8f, 0.1f, 0.1f, 1f), roughness = 0.6f))
        val mTyre = s.addMaterial(GlbMaterial("x_tyre", baseColor = floatArrayOf(0.05f, 0.05f, 0.05f, 1f)))
        val root = s.addNode("x_rider")
        val frame = s.addNode("x_frame", root)
        s.addMesh(
            frame,
            MeshBuilder().apply {
                addLoft(listOf(Vec3(0.0, 0.35, -0.5), Vec3(0.0, 0.9, 0.0), Vec3(0.0, 0.35, 0.55)), doubleArrayOf(0.02, 0.02, 0.02), 8)
            },
            mFrame,
        )
        val crank = s.addNode("x_crank", root, translation = Vec3(0.0, 0.3, 0.0))
        s.addMesh(crank, MeshBuilder().apply { addBox(-0.01, -0.17, -0.01, 0.01, 0.0, 0.01) }, mFrame)
        val wheel = s.addNode("x_wheel_rear", root, translation = Vec3(0.0, 0.35, -0.5), rotation = Quat.IDENTITY, scale = Vec3.ONE)
        s.addMesh(wheel, MeshBuilder().apply { addCylinder(Vec3(-0.015, 0.0, 0.0), Vec3(0.015, 0.0, 0.0), 0.35, 0.35, 32) }, mTyre)

        fun spin(node: Int, duration: Float): GlbChannel {
            val n = 8
            val times = FloatArray(n + 1) { it * duration / n }
            val values = FloatArray((n + 1) * 4)
            for (i in 0..n) {
                val q = Quat.fromAxisAngle(Vec3.X, -2 * PI * i / n)
                values[i * 4] = q.x.toFloat(); values[i * 4 + 1] = q.y.toFloat(); values[i * 4 + 2] = q.z.toFloat(); values[i * 4 + 3] = q.w.toFloat()
            }
            return GlbChannel(node, GlbPath.ROTATION, times, values)
        }
        s.addAnimation(WorldModels.ANIM_PEDAL, listOf(spin(crank, pedalDuration)))
        if (withRoll) s.addAnimation(WorldModels.ANIM_ROLL, listOf(spin(wheel, 1f)))
        return s.toBytes()
    }

    // -----------------------------------------------------------------------
    // GLB zerlegen und zusammensetzen (fuer gezielt kaputte Dateien)
    // -----------------------------------------------------------------------

    private fun toMutable(e: JsonElement): Any? = when (e) {
        is JsonObject -> e.mapValuesTo(LinkedHashMap()) { toMutable(it.value) }
        is JsonArray -> e.mapTo(ArrayList()) { toMutable(it) }
        else -> e
    }

    private fun toJson(a: Any?): JsonElement = when (a) {
        null -> JsonNull
        is JsonElement -> a
        is Map<*, *> -> JsonObject(a.entries.associate { it.key as String to toJson(it.value) })
        is List<*> -> JsonArray(a.map { toJson(it) })
        is Number -> JsonPrimitive(a)
        is String -> JsonPrimitive(a)
        is Boolean -> JsonPrimitive(a)
        else -> error("unbekannt: $a")
    }

    private class Parts(val json: MutableMap<String, Any?>, var bin: ByteArray)

    private fun split(glb: ByteArray): Parts {
        val bb = ByteBuffer.wrap(glb).order(ByteOrder.LITTLE_ENDIAN)
        val jl = bb.getInt(12)
        val json = toMutable(Json.parseToJsonElement(String(glb, 20, jl, Charsets.UTF_8))) as MutableMap<String, Any?>
        val bl = bb.getInt(20 + jl)
        return Parts(json, glb.copyOfRange(28 + jl, 28 + jl + bl))
    }

    private fun join(p: Parts): ByteArray {
        val js = toJson(p.json).toString().toByteArray()
        val jl = (js.size + 3) and 3.inv()
        val bl = (p.bin.size + 3) and 3.inv()
        val total = 28 + jl + bl
        val bb = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(0x46546C67).putInt(2).putInt(total).putInt(jl).putInt(0x4E4F534A).put(js)
        repeat(jl - js.size) { bb.put(0x20) }
        bb.putInt(bl).putInt(0x004E4942).put(p.bin)
        return bb.array()
    }

    private fun Any?.m(): MutableMap<String, Any?> = this as MutableMap<String, Any?>
    private fun Any?.l(): MutableList<Any?> = this as MutableList<Any?>
    private fun Any?.i(): Int = (this as JsonPrimitive).content.toDouble().toInt()

    private fun mutate(glb: ByteArray, f: (Parts) -> Unit): ByteArray = split(glb).also(f).let { join(it) }

    private fun Parts.node(name: String) = json["nodes"].l().first { it.m()["name"] == JsonPrimitive(name) }.m()
    private fun Parts.material(i: Int) = json["materials"].l()[i].m()
    private fun Parts.firstPrimitive() = json["meshes"].l()[0].m()["primitives"].l()[0].m()

    /** Byte-Anfang der Daten eines Accessors im BIN-Block. */
    private fun Parts.accessorOffset(acc: Int): Int {
        val a = json["accessors"].l()[acc].m()
        val v = json["bufferViews"].l()[a["bufferView"].i()].m()
        return (v["byteOffset"]?.i() ?: 0) + (a["byteOffset"]?.i() ?: 0)
    }

    /** Ersetzt die Daten von Bild 0. */
    private fun Parts.replaceImage(bytes: ByteArray, mime: String) {
        val off = (bin.size + 3) and 3.inv()
        bin = bin.copyOf(off + bytes.size).also { System.arraycopy(bytes, 0, it, off, bytes.size) }
        val views = json["bufferViews"].l()
        views.add(mutableMapOf<String, Any?>("buffer" to 0, "byteOffset" to off, "byteLength" to bytes.size))
        json["buffers"].l()[0].m()["byteLength"] = bin.size
        val img = json["images"].l()[0].m()
        img["bufferView"] = views.size - 1
        img["mimeType"] = mime
    }

    private fun assertViolation(bytes: ByteArray, vararg keywords: String) {
        val v = checkTileGlb(bytes)
        for (k in keywords) assertTrue(v.any { it.contains(k) }, "Erwartet Verstoss mit \"$k\", gemeldet: $v")
    }

    private fun progressiveJpeg(): ByteArray {
        val bi = BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB)
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val param = writer.defaultWriteParam
        param.progressiveMode = ImageWriteParam.MODE_DEFAULT
        val bos = ByteArrayOutputStream()
        ImageIO.createImageOutputStream(bos).use { ios ->
            writer.output = ios
            writer.write(null, IIOImage(bi, null, null), param)
        }
        writer.dispose()
        return bos.toByteArray()
    }

    // -----------------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------------

    @Test
    fun roundTripThroughParseGlb() {
        val tile = cleanTile()
        val bytes = tile.bytes
        assertEquals(0, bytes.size % 4)
        val info = parseGlb(bytes)
        assertEquals(GLB_GENERATOR, info.generator)
        assertEquals(tile.triangles, info.triangleCount)
        assertEquals(listOf("terrain", "terrain_sub_0_0", "route", "trees", "water", "x_marker"), info.nodes.map { it.name })
        assertEquals(listOf(0, 2, 3, 4, 5), info.roots)
        assertEquals(0, info.node("terrain_sub_0_0")!!.parent)
        assertEquals(null, info.node("x_marker")!!.mesh)
        assertEquals(4, info.meshCount)
        assertEquals(
            listOf("mat_terrain", "mat_route_gravel", "mat_tree_trunk", "mat_tree_leaves", "mat_water"),
            info.materials.map { it.name },
        )
        assertTrue(info.materials.all { it.metallicFactor == 0.0 })
        assertEquals("MASK", info.materials[3].alphaMode)
        assertTrue(info.materials[3].doubleSided)
        assertTrue(info.materials[4].unlit)
        assertEquals(listOf(KHR_MATERIALS_UNLIT), info.extensionsUsed)
        assertEquals(2, info.images.size)
        assertEquals(listOf("jpeg", "png"), info.images.map { it.format })
        assertEquals(listOf(64, 32), info.images.map { it.width })
        assertTrue(info.images.all { it.baseline && !it.progressive })
        assertEquals(textureGpuBytes(64) + textureGpuBytes(32), info.textureGpuBytes)
        val min = assertNotNull(info.boundsMin)
        val max = assertNotNull(info.boundsMax)
        assertEquals(-32.0, min[0], 1e-4)
        assertEquals(32.0, max[0], 1e-4)
        assertEquals(-32.0, min[2], 1e-4)

        val withAnim = parseGlb(cleanTile(animate = true).bytes)
        assertEquals(1, withAnim.animations.size)
        assertEquals("wiggle", withAnim.animations[0].name)
        assertEquals(1.0, withAnim.animations[0].durationS, 1e-9)
    }

    @Test
    fun textureDeduplicationAndWriterGuards() {
        val s = GlbScene()
        val png = encodePng(texture(16, 3), withAlpha = false)
        val a = s.addTexture(png, "image/png", repeat = true)
        val b = s.addTexture(png.copyOf(), "image/png", repeat = true)
        val c = s.addTexture(png, "image/png", repeat = false)
        assertEquals(a, b)
        assertTrue(a != c)
        val mat = s.addMaterial(GlbMaterial("x_a", texture = a))
        s.addMaterial(GlbMaterial("x_c", texture = c))
        val n = s.addNode("x_n")
        s.addMesh(n, MeshBuilder().apply { addBox(0.0, 0.0, 0.0, 1.0, 1.0, 1.0) }, mat)
        val info = parseGlb(s.toBytes())
        assertEquals(1, info.images.size)
        assertEquals(2, info.textureCount)
        assertEquals(textureGpuBytes(16), s.textureGpuBytes())

        assertFailsWith<IllegalArgumentException> { s.addMaterial(GlbMaterial("x_shiny", roughness = 0.5f)) }
        assertFailsWith<IllegalArgumentException> { s.addTexture(progressiveJpeg(), "image/jpeg", false) }
        assertFailsWith<IllegalArgumentException> { s.addTexture(encodePng(RgbaImage(30, 32), false), "image/png", false) }
        assertFailsWith<IllegalArgumentException> { s.addTexture(png, "image/jpeg", false) }
        assertFailsWith<IllegalArgumentException> { s.addTexture("GIF89a".toByteArray(), "image/gif", false) }
        assertFailsWith<IllegalArgumentException> {
            s.addAnimation("x", listOf(GlbChannel(n, GlbPath.SCALE, floatArrayOf(0f, 0f), FloatArray(6))))
        }
        assertFailsWith<IllegalArgumentException> { parseGlb(ByteArray(40)) }
        assertEquals(1, checkTileGlb("kaputt".toByteArray()).size)
    }

    @Test
    fun largeMeshesUse32BitIndices() {
        val s = GlbScene()
        val mat = s.addMaterial(GlbMaterial(WorldNames.MAT_TERRAIN))
        val node = s.addNode(WorldNames.NODE_TERRAIN)
        val g = grid(300, 256.0)
        assertTrue(g.vertexCount > 65535)
        s.addMesh(node, g, mat)
        val bytes = s.toBytes()
        val p = split(bytes)
        val idxAcc = p.firstPrimitive()["indices"].i()
        assertEquals(5125, p.json["accessors"].l()[idxAcc].m()["componentType"].i())
        assertEquals(emptyList(), checkTileGlb(bytes))
        assertEquals(300 * 300 * 2, parseGlb(bytes).triangleCount)
    }

    @Test
    fun cleanTilePassesAndEveryViolationIsFound() {
        val clean = cleanTile(gridN = 8).bytes
        assertEquals(emptyList(), checkTileGlb(clean))
        // Zerlegen und unveraendert zusammensetzen bleibt sauber.
        assertEquals(emptyList(), checkTileGlb(mutate(clean) {}))

        assertViolation(mutate(clean) { it.json["extensionsUsed"].l().add("EXT_mesh_gpu_instancing") }, "EXT_mesh_gpu_instancing")
        assertViolation(mutate(clean) { it.json["extensionsRequired"] = mutableListOf<Any?>("KHR_texture_basisu") }, "KHR_texture_basisu")
        assertViolation(mutate(clean) { it.json["extensionsUsed"] = mutableListOf<Any?>() }, "fehlt aber in extensionsUsed")
        assertViolation(
            mutate(clean) { it.material(0)["extensions"] = mutableMapOf<String, Any?>("KHR_materials_clearcoat" to mutableMapOf<String, Any?>()) },
            "KHR_materials_clearcoat",
        )
        assertViolation(mutate(clean) { it.node("route")["name"] = "strasse" }, "Knotenname \"strasse\"")
        assertViolation(mutate(clean) { it.node("x_marker").remove("name") }, "keinen Namen")
        assertViolation(mutate(clean) { it.material(2)["name"] = "rinde" }, "Materialname \"rinde\"")
        assertViolation(mutate(clean) { it.material(1)["name"] = "mat_route_lava" }, "Materialname \"mat_route_lava\"")
        assertViolation(mutate(clean) { it.material(0)["pbrMetallicRoughness"].m()["metallicFactor"] = 1 }, "metallicFactor")
        assertViolation(mutate(clean) { it.material(0)["pbrMetallicRoughness"].m().remove("metallicFactor") }, "metallicFactor 1.0")
        assertViolation(mutate(clean) { it.material(0)["pbrMetallicRoughness"].m()["roughnessFactor"] = 0.3 }, "roughnessFactor 0.3")
        assertViolation(mutate(clean) { it.material(0)["normalTexture"] = mutableMapOf<String, Any?>("index" to 0) }, "normalTexture")
        assertViolation(mutate(clean) { it.replaceImage(progressiveJpeg(), "image/jpeg") }, "progressives JPEG")
        assertViolation(mutate(clean) { it.replaceImage(encodePng(RgbaImage(48, 32), false), "image/png") }, "keine Zweierpotenz")
        val huge = encodePng(RgbaImage(32, 32), false).also { b -> b[18] = 0x10; b[19] = 0 } // Breite 4096 im IHDR
        assertViolation(mutate(clean) { it.replaceImage(huge, "image/png") }, "groesser als 2048")
        assertViolation(mutate(clean) { it.replaceImage("GIF89a-kein-Bild".toByteArray(), "image/gif") }, "weder JPEG noch PNG")
        assertViolation(mutate(clean) { it.json["images"].l()[0].m()["mimeType"] = "image/png" }, "mimeType")
        assertViolation(mutate(clean) { it.json["images"].l()[0].m()["uri"] = "textur.jpg" }, "nicht in der GLB eingebettet")
        assertViolation(mutate(clean) { it.firstPrimitive()["attributes"].m().remove("NORMAL") }, "NORMAL fehlt")
        assertViolation(mutate(clean) { it.firstPrimitive()["attributes"].m().remove("TEXCOORD_0") }, "TEXCOORD_0 fehlt")
        assertViolation(
            mutate(clean) { p -> p.firstPrimitive()["attributes"].m().let { a -> a["TANGENT"] = a["NORMAL"] } },
            "TANGENT ist nicht erlaubt",
        )
        assertViolation(mutate(clean) { it.firstPrimitive()["mode"] = 1 }, "mode 1")
        assertViolation(mutate(clean) { it.firstPrimitive().remove("material") }, "kein Material")
        assertViolation(
            mutate(clean) { it.json["cameras"] = mutableListOf<Any?>(mutableMapOf<String, Any?>("type" to "perspective")) },
            "Kamera",
        )
        assertViolation(cleanTile(animate = true).bytes, "Animation")
        assertViolation(mutate(clean) { it.json["skins"] = mutableListOf<Any?>(mutableMapOf<String, Any?>("joints" to mutableListOf<Any?>(0))) }, "Skin")
        assertViolation(
            mutate(clean) {
                it.json["extensionsUsed"].l().add("KHR_lights_punctual")
                it.json["extensions"] = mutableMapOf<String, Any?>("KHR_lights_punctual" to mutableMapOf<String, Any?>("lights" to mutableListOf<Any?>()))
            },
            "Lichter",
        )
        assertViolation(mutate(clean) { it.node("route")["translation"] = mutableListOf<Any?>(1, 0, 0) }, "eigene Transformation")
        assertViolation(
            mutate(clean) { p ->
                val copy = LinkedHashMap(p.node("water"))
                p.json["nodes"].l().add(copy)
                p.json["scenes"].l()[0].m()["nodes"].l().add(p.json["nodes"].l().size - 1)
            },
            "mehrfach",
        )
        assertViolation(
            mutate(clean) { p ->
                p.node("terrain").remove("children")
                p.json["scenes"].l()[0].m()["nodes"].l().add(1)
            },
            "kein Kind",
        )
        assertViolation(
            mutate(clean) { p ->
                val off = p.accessorOffset(p.firstPrimitive()["indices"].i())
                p.bin[off] = 0xFF.toByte(); p.bin[off + 1] = 0x7F // Index 32767
            },
            "ausserhalb",
        )
        assertViolation(
            mutate(clean) { p ->
                val off = p.accessorOffset(p.firstPrimitive()["attributes"].m()["POSITION"].i())
                ByteBuffer.wrap(p.bin).order(ByteOrder.LITTLE_ENDIAN).putFloat(off + 4, Float.NaN)
            },
            "NaN",
        )
        assertViolation(
            mutate(clean) { p ->
                val acc = p.json["accessors"].l()[p.firstPrimitive()["attributes"].m()["POSITION"].i()].m()
                acc["max"] = mutableListOf<Any?>(1, 1, 1)
            },
            "min/max stimmen nicht",
        )
        assertViolation(
            mutate(clean) { p ->
                p.json["accessors"].l()[p.firstPrimitive()["attributes"].m()["POSITION"].i()].m().remove("min")
            },
            "ohne min/max",
        )
        assertViolation(
            mutate(clean) { p ->
                val acc = p.json["accessors"].l()[p.firstPrimitive()["attributes"].m()["NORMAL"].i()].m()
                acc["count"] = 100_000
            },
            "ragt ueber bufferView",
        )
        assertViolation(
            mutate(clean) { p ->
                val acc = p.json["accessors"].l()[p.firstPrimitive()["attributes"].m()["POSITION"].i()].m()
                p.json["bufferViews"].l()[acc["bufferView"].i()].m()["byteLength"] = 10_000_000
            },
            "nicht im eingebetteten Puffer",
        )
        assertViolation(mutate(clean) { it.json["asset"].m()["version"] = "1.0" }, "asset.version")
        assertViolation(
            mutate(clean) { it.json["scenes"].l().add(mutableMapOf<String, Any?>()) },
            "genau eine Szene",
        )
    }

    @Test
    fun modelCheckRequiresAnimationsOfOneSecond() {
        val good = model()
        assertEquals(emptyList(), checkModelGlb(good))
        val info = parseGlb(good)
        assertEquals(listOf(WorldModels.ANIM_PEDAL, WorldModels.ANIM_ROLL), info.animations.map { it.name })
        assertTrue(info.animations.all { it.durationS == 1.0 })
        assertTrue(info.node("x_crank")!!.hasTransform)
        // Kachelregeln gelten fuer Modelle nicht (Transformation, Animation) — umgekehrt meldet die Kachelpruefung sie.
        assertTrue(checkTileGlb(good).any { it.contains("Animation") })

        assertTrue(checkModelGlb(model(withRoll = false)).any { it.contains("\"roll\" fehlt") })
        assertTrue(checkModelGlb(model(pedalDuration = 2f)).any { it.contains("\"pedal\" dauert 2.0 s") })
        assertTrue(checkModelGlb(good, maxTriangles = 10).any { it.contains("Dreiecke") })
        assertTrue(checkModelGlb(good, maxBytes = 100).any { it.contains("Bytes") })
        assertTrue(checkModelGlb(mutate(good) { it.node("x_crank")["name"] = "kurbel" }).any { it.contains("\"kurbel\"") })
        assertTrue(
            checkModelGlb(
                mutate(good) { p ->
                    p.json["animations"].l()[0].m()["channels"].l()[0].m()["target"].m()["node"] = 99
                },
            ).any { it.contains("keinen gueltigen Knoten") },
        )
    }

    @Test
    fun writesSampleFilesForExternalValidators() {
        val dir = File("build/indoor-world").apply { mkdirs() }
        val tile = cleanTile(gridN = 32).bytes
        File(dir, "sample-tile.glb").writeBytes(tile)
        val m = model()
        File(dir, "sample-model.glb").writeBytes(m)
        assertEquals(emptyList(), checkTileGlb(File(dir, "sample-tile.glb").readBytes()))
        assertEquals(emptyList(), checkModelGlb(File(dir, "sample-model.glb").readBytes()))
    }
}
