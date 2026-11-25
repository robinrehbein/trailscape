package de.trailscape.core.indoor.tree

import de.trailscape.core.indoor.world.MeshBuilder
import de.trailscape.core.indoor.world.Vec3
import de.trailscape.core.indoor.world.checkTileGlb
import de.trailscape.core.indoor.world.parseGlb
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TreeGeneratorTest {
    private fun allTemplates(): List<Triple<TreeKind, Int, TreeLod>> =
        TreeKind.entries.flatMap { k -> (0..3).flatMap { v -> TreeLod.entries.map { l -> Triple(k, v, l) } } }

    @Test
    fun gleicherSameGleicherBaum() {
        for (k in TreeKind.entries) {
            val a = TreeLibrary.template(k, 1, TreeLod.NEAR, seed = 7)
            val b = TreeLibrary.template(k, 1, TreeLod.NEAR, seed = 7)
            assertContentEquals(a.bark.positions.copyOf(a.bark.vertexCount * 3), b.bark.positions.copyOf(b.bark.vertexCount * 3), "$k Rinde")
            assertContentEquals(a.leaves.positions.copyOf(a.leaves.vertexCount * 3), b.leaves.positions.copyOf(b.leaves.vertexCount * 3), "$k Laub")
            assertContentEquals(a.leaves.colors.copyOf(a.leaves.vertexCount), b.leaves.colors.copyOf(b.leaves.vertexCount), "$k Farben")
            val c = TreeLibrary.template(k, 1, TreeLod.NEAR, seed = 8)
            assertFalse(a.leaves.positions.copyOf(a.leaves.vertexCount * 3).contentEquals(c.leaves.positions.copyOf(c.leaves.vertexCount * 3)), "$k: anderer Same, gleicher Baum")
        }
    }

    @Test
    fun budgetJeArtUndStufeEingehalten() {
        val report = StringBuilder()
        for ((k, v, l) in allTemplates()) {
            val t = TreeLibrary.template(k, v, l)
            val budget = TreeBudget.triangles(k, l)
            report.append("$k/$v/$l ${t.triangles}/$budget  ")
            assertTrue(t.triangles <= budget, "$k Variante $v $l: ${t.triangles} Dreiecke > Budget $budget")
            // Die Nahstufe nutzt das Budget (sonst ist sie unnoetig licht), die mittlere ist deutlich billiger
            if (l == TreeLod.NEAR && k != TreeKind.SHRUB) assertTrue(t.triangles >= budget * 0.45, "$k/$v NEAR nutzt nur ${t.triangles} von $budget")
            if (l == TreeLod.MID) assertTrue(t.triangles * 3 <= TreeLibrary.template(k, v, TreeLod.NEAR).triangles + 600, "$k/$v MID kaum billiger als NEAR")
        }
        println(report)
    }

    @Test
    fun netzeSindGueltig() {
        for ((k, v, l) in allTemplates()) {
            val t = TreeLibrary.template(k, v, l)
            for ((name, m) in listOf("Rinde" to t.bark, "Laub" to t.leaves)) {
                assertTrue(m.vertexCount > 0, "$k/$v/$l $name leer")
                assertTrue(m.vertexCount <= 65535, "$k/$v/$l $name: 16-Bit-Indizes reichen nicht")
                for (i in 0 until m.vertexCount * 3) assertTrue(m.positions[i].isFinite(), "$k/$v/$l $name: Lage nicht endlich")
                for (i in 0 until m.vertexCount) {
                    val n = sqrt(m.normals[i * 3] * m.normals[i * 3] + m.normals[i * 3 + 1] * m.normals[i * 3 + 1] + m.normals[i * 3 + 2] * m.normals[i * 3 + 2])
                    assertTrue(abs(n - 1f) < 1e-3, "$k/$v/$l $name: Normale nicht normiert ($n)")
                }
                for (i in 0 until m.triangleCount * 3) assertTrue(m.indices[i] in 0 until m.vertexCount)
            }
            // Laub-UV im Motiv der Art, Rinden-u in der Spalte der Art
            val reg = TreeTextures.region(Species.of(k).leafMotif)
            val u0 = reg.x / 512.0 - 1e-6
            val u1 = (reg.x + reg.w) / 512.0 + 1e-6
            val v0 = reg.y / 512.0 - 1e-6
            val v1 = (reg.y + reg.h) / 512.0 + 1e-6
            val mass = TreeTextures.MASS
            for (i in 0 until t.leaves.vertexCount) {
                val u = t.leaves.uvs[i * 2]
                val vv = t.leaves.uvs[i * 2 + 1]
                val inMass = u >= mass.x / 512.0 - 1e-6 && vv >= mass.y / 512.0 - 1e-6
                assertTrue((u in u0..u1 && vv in v0..v1) || inMass, "$k: Laub-UV ($u, $vv) ausserhalb des Motivs")
            }
            val col = Species.of(k).barkMotif.column
            for (i in 0 until t.bark.vertexCount) {
                val u = t.bark.uvs[i * 2]
                assertTrue(u >= col / 4.0 - 1e-6 && u <= (col + 1) / 4.0 + 1e-6, "$k: Rinden-u $u ausserhalb der Spalte $col")
            }
        }
    }

    private class Bounds(val minX: Double, val maxX: Double, val minY: Double, val maxY: Double, val minZ: Double, val maxZ: Double) {
        val width get() = max(maxX - minX, maxZ - minZ)
        val height get() = maxY
    }

    private fun bounds(m: MeshBuilder): Bounds {
        var a = DoubleArray(6) { if (it % 2 == 0) Double.MAX_VALUE else -Double.MAX_VALUE }
        for (i in 0 until m.vertexCount) for (c in 0 until 3) {
            val p = m.positions[i * 3 + c].toDouble()
            a[c * 2] = min(a[c * 2], p)
            a[c * 2 + 1] = max(a[c * 2 + 1], p)
        }
        return Bounds(a[0], a[1], a[2], a[3], a[4], a[5])
    }

    @Test
    fun proportionenJeArt() {
        for (k in TreeKind.entries) for (v in 0..3) {
            val t = TreeLibrary.template(k, v, TreeLod.NEAR)
            val h = t.skeleton.heightM
            val target = Species.of(k).heights[v]
            assertTrue(abs(h - target) <= target * 0.06, "$k/$v Hoehe $h statt ~$target")
            val b = bounds(t.leaves)
            // Spitze der Krone in der Naehe der Baumhoehe, Stammfuss im Boden
            // Bueschel duerfen um ihre Kantenlaenge ueber die Astspitzen ragen
            assertTrue(b.height in h * 0.8..h * 1.2 + Species.of(k).clusterM, "$k/$v Kronenspitze ${b.height} bei Hoehe $h")
            assertTrue(bounds(t.bark).minY < 0.0, "$k/$v Stamm steht nicht im Boden")
            val ratio = b.width / h
            val range = when (k) {
                TreeKind.POPLAR -> 0.12..0.42
                TreeKind.BIRCH -> 0.2..0.65
                TreeKind.SPRUCE -> 0.25..0.6
                TreeKind.PINE -> 0.3..0.8
                TreeKind.BEECH -> 0.45..1.0
                TreeKind.OAK -> 0.6..1.35
                TreeKind.ALEPPO_PINE -> 0.6..1.5
                TreeKind.FRUIT -> 0.8..1.7
                TreeKind.SHRUB -> 0.6..1.8
            }
            assertTrue(ratio in range, "$k/$v Kronenbreite/Hoehe ${"%.2f".format(ratio)} nicht in $range")
            // um den Fusspunkt (kein Versatz der Vorlage, sonst drehen Instanzen um einen falschen Punkt)
            val cx = (b.minX + b.maxX) / 2
            val cz = (b.minZ + b.maxZ) / 2
            assertTrue(abs(cx) < h * 0.25 && abs(cz) < h * 0.25, "$k/$v Krone um ($cx, $cz) versetzt")
        }
    }

    @Test
    fun mittlereStufeHaeltDieSilhouette() {
        for (k in TreeKind.entries) for (v in 0..3) {
            val near = bounds(TreeLibrary.template(k, v, TreeLod.NEAR).leaves)
            val mid = bounds(TreeLibrary.template(k, v, TreeLod.MID).leaves)
            assertTrue(abs(mid.height - near.height) <= near.height * 0.15, "$k/$v Hoehe mittel ${mid.height} gegen nah ${near.height}")
            assertTrue(abs(mid.width - near.width) <= near.width * 0.3 + 0.6, "$k/$v Breite mittel ${mid.width} gegen nah ${near.width}")
        }
    }

    @Test
    fun laubnormalenZeigenVonDerKronenmitteWeg() {
        for (k in TreeKind.entries) {
            val t = TreeLibrary.template(k, 0, TreeLod.NEAR)
            val s = t.skeleton
            var sum = 0.0
            var up = 0.0
            val m = t.leaves
            for (i in 0 until m.vertexCount) {
                val p = Vec3(m.positions[i * 3].toDouble(), m.positions[i * 3 + 1].toDouble(), m.positions[i * 3 + 2].toDouble())
                val r = s.rel(p).normalized(Vec3.UP)
                val n = Vec3(m.normals[i * 3].toDouble(), m.normals[i * 3 + 1].toDouble(), m.normals[i * 3 + 2].toDouble())
                sum += n dot r
                up += n.y
            }
            val mean = sum / m.vertexCount
            assertTrue(mean > 0.6, "$k: Laubnormalen nicht kugelig ($mean)")
            assertTrue(up / m.vertexCount > 0.0, "$k: Laubnormalen im Mittel nach unten")
        }
    }

    @Test
    fun glbFolgtDenKachelregeln() {
        for (k in TreeKind.entries) {
            val glb = TreeLibrary.templateGlb(k, 0, TreeLod.NEAR)
            val problems = checkTileGlb(glb)
            assertTrue(problems.isEmpty(), "$k: ${problems.joinToString()}")
            val info = parseGlb(glb)
            val names = info.materials.map { it.name }.toSet()
            assertEquals(setOf(TreeLibrary.MAT_TRUNK, TreeLibrary.MAT_LEAVES), names)
            val leaves = info.materials.first { it.name == TreeLibrary.MAT_LEAVES }
            assertEquals("MASK", leaves.alphaMode)
            assertFalse(leaves.doubleSided, "Laub einseitig wie x_mat_tree_cards heute")
            // keine neue Erweiterung (nur die, die der Weltbau heute schon schreibt)
            val json = String(glb, 20, java.nio.ByteBuffer.wrap(glb, 12, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int, Charsets.UTF_8)
            val used = Regex("\"(KHR|EXT)_[a-z_]+\"").findAll(json).map { it.value.trim('"') }.toSet()
            assertTrue(used.all { it == "KHR_materials_specular" }, "unerwartete Erweiterungen: $used")
        }
        val lib = TreeLibrary.libraryGlb()
        // Bibliothek: Knoten x_tree_…, ohne Transformation — besteht auch die Kachelregeln
        val problems = checkTileGlb(lib)
        assertTrue(problems.isEmpty(), "Bibliothek: ${problems.joinToString()}")
        assertEquals(TreeKind.entries.size * 4 * 2, parseGlb(lib).nodes.count { it.name?.startsWith("x_tree_") == true })
    }

    @Test
    fun atlantenOhneDunkleSaeumeUndKachelnd() {
        val leaf = ImageIO.read(ByteArrayInputStream(TreeTextures.leafPng))
        assertEquals(512, leaf.width)
        for (reg in listOf(TreeTextures.BROAD, TreeTextures.NEEDLE, TreeTextures.PINE, TreeTextures.SMALL, TreeTextures.MASS)) {
            var covered = 0
            var dark = 0
            var n = 0
            for (y in reg.y until reg.y + reg.h) for (x in reg.x until reg.x + reg.w) {
                val argb = leaf.getRGB(x, y)
                val a = argb ushr 24
                n++
                if (a >= 128) covered++
                val lum = ((argb shr 16 and 255) + (argb shr 8 and 255) + (argb and 255)) / 3
                if (a < 128 && lum < 40) dark++
            }
            assertTrue(covered.toDouble() / n > 0.18, "Motiv ${reg.x},${reg.y}: Deckung ${covered.toDouble() / n}")
            assertEquals(0, dark, "Motiv ${reg.x},${reg.y}: dunkle durchsichtige Pixel (Saum in den Mip-Stufen)")
        }
        val bark = ImageIO.read(ByteArrayInputStream(TreeTextures.barkJpeg))
        assertEquals(512, bark.width)
        // senkrecht kachelnd: erste und letzte Zeile aehnlich (JPEG-Toleranz)
        var diff = 0.0
        for (x in 0 until bark.width) {
            val a = bark.getRGB(x, 0)
            val b = bark.getRGB(x, bark.height - 1)
            diff += abs((a shr 8 and 255) - (b shr 8 and 255))
        }
        assertTrue(diff / bark.width < 22, "Rinde kachelt senkrecht nicht (${diff / bark.width})")
    }

    @Test
    fun vergleichsszeneIstGueltig() {
        val dir = Files.createTempDirectory("tree-proto").toFile()
        try {
            writeTreeComparison(dir)
            for (w in listOf("alt", "neu", "schau-alt", "schau-neu")) {
                val tile = File(dir, "$w/tiles").listFiles()!!.single()
                val problems = checkTileGlb(tile.readBytes())
                assertTrue(problems.isEmpty(), "$w: ${problems.joinToString()}")
                assertTrue(File(dir, "$w/manifest.json").length() > 100)
            }
            val cams = File(dir, "cameras.json").readText()
            for (name in listOf("einzel-05m-gegenlicht", "einzel-15m-auflicht", "einzel-40m-gegenlicht", "allee-auflicht", "waldrand-gegenlicht", "mediterran-auflicht")) {
                assertTrue(name in cams, "Kamera $name fehlt")
            }
            assertTrue(File(dir, "metrics.txt").readText().contains("Vergleichsszene"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
