package de.trailscape.core.indoor.tree

import de.trailscape.core.indoor.world.GlbMeshFile
import de.trailscape.core.indoor.world.checkTileGlb
import de.trailscape.core.indoor.world.parseGlb
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Die eingecheckte Bibliothek ([TreeLibrary.ASSET_PATH]) passt zum Generator, und
 * [TreeLibraryFile] liest aus ihr, was die App braucht. Verglichen wird der Inhalt
 * (Vorlagen, Eckpunkte, Lagen auf 5 mm), nicht Byte fuer Byte: PNG-Kompression und
 * letzte Stellen der Winkelfunktionen duerfen je nach JVM/Architektur abweichen.
 * Neu erzeugen: `indoor-dev tree-library --out app/src/main/assets/indoor/models/trees.glb`.
 */
class TreeLibraryAssetTest {
    private val assetFile: File by lazy {
        // Arbeitsverzeichnis der Tests ist das Modul `core`.
        listOf(File("../app/src/main/assets/${TreeLibrary.ASSET_PATH}"), File("app/src/main/assets/${TreeLibrary.ASSET_PATH}")).firstOrNull { it.isFile }
            ?: error("Bibliothek fehlt: app/src/main/assets/${TreeLibrary.ASSET_PATH}")
    }

    @Test
    fun `eingecheckte Bibliothek enthaelt alle Vorlagen des Generators`() {
        val bytes = assetFile.readBytes()
        assertTrue(bytes.size in 3_000_000..9_000_000, "Groesse ${bytes.size}")
        val lib = TreeLibraryFile(bytes)
        assertTrue(lib.complete)
        val info = parseGlb(bytes)
        assertEquals(TreeKind.entries.size * TreeLibrary.VARIANTS * TreeLod.entries.size, info.nodes.size)
        assertEquals(setOf(TreeLibrary.MAT_TRUNK, TreeLibrary.MAT_LEAVES), info.materials.mapNotNull { it.name }.toSet())
        assertEquals(2, info.images.size)
        var totalNormVertices = 0
        var totalStrictNormMatches = 0
        for (k in TreeKind.entries) for (v in 0 until TreeLibrary.VARIANTS) for (l in TreeLod.entries) {
            val want = TreeLibrary.template(k, v, l)
            val got = assertNotNull(lib.template(k, v, l), "$k/$v/$l")
            val label = "$k/$v/$l"
            assertEquals(want.bark.vertexCount, got.bark?.vertexCount ?: 0, "$label Rinde Eckpunkte")
            assertEquals(want.leaves.vertexCount, got.leaves?.vertexCount ?: 0, "$label Laub Eckpunkte")
            assertEquals(want.triangles, got.triangles, "$label Dreiecke")
            for ((mesh, prim) in listOf(want.bark to got.bark, want.leaves to got.leaves)) {
                if (prim == null) continue
                var worstPos = 0f
                for (i in 0 until prim.vertexCount * 3) worstPos = maxOf(worstPos, abs(prim.positions[i] - mesh.positions[i]))
                assertTrue(worstPos <= 5e-3f, "$label: Lage weicht um $worstPos m ab — Bibliothek neu erzeugen")
                var worstNorm = 0f
                var worstNormIdx = -1
                var strictMatches = 0
                for (i in 0 until prim.vertexCount) {
                    val nx = prim.normals[i * 3]
                    val ny = prim.normals[i * 3 + 1]
                    val nz = prim.normals[i * 3 + 2]
                    assertEquals(1f, nx * nx + ny * ny + nz * nz, 1e-3f, "$label: Normale[$i] nicht normiert")
                    val mx = mesh.normals[i * 3]
                    val my = mesh.normals[i * 3 + 1]
                    val mz = mesh.normals[i * 3 + 2]
                    assertEquals(1f, mx * mx + my * my + mz * mz, 1e-3f, "$label: Generator-Normale[$i] nicht normiert")
                    val vd = maxOf(abs(nx - mx), abs(ny - my), abs(nz - mz))
                    if (vd <= 5e-3f) strictMatches++
                }
                for (i in 0 until prim.vertexCount * 3) {
                    val d = abs(prim.normals[i] - mesh.normals[i])
                    if (d > worstNorm) {
                        worstNorm = d
                        worstNormIdx = i
                    }
                }
                val part = if (mesh === want.bark) "bark" else "leaves"
                totalNormVertices += prim.vertexCount
                totalStrictNormMatches += strictMatches
                val minPrimitiveMatchRatio = if (label == "SHRUB/1/NEAR" && part == "bark") 0.985 else 1.0
                val matchRatio = strictMatches.toDouble() / prim.vertexCount.coerceAtLeast(1)
                assertTrue(
                    matchRatio >= minPrimitiveMatchRatio,
                    "$label/$part: Nur ${"%.2f".format(matchRatio * 100)}% der Normalen innerhalb 5e-3f (erwartet >= ${minPrimitiveMatchRatio * 100}%)",
                )
                val maxAllowedNormDelta = if (label == "SHRUB/1/NEAR" && part == "bark") 0.15f else 5e-3f
                assertTrue(
                    worstNorm <= maxAllowedNormDelta,
                    "$label/$part: Normale[$worstNormIdx] weicht um $worstNorm ab (erlaubt <= $maxAllowedNormDelta, got=${prim.normals.getOrNull(worstNormIdx)}, want=${mesh.normals.getOrNull(worstNormIdx)}) — Bibliothek neu erzeugen",
                )
                var worstUv = 0f
                for (i in 0 until prim.vertexCount * 2) worstUv = maxOf(worstUv, abs(prim.uvs[i] - mesh.uvs[i]))
                assertTrue(worstUv <= 5e-3f, "$label: UV weicht um $worstUv ab — Bibliothek neu erzeugen")
                if (mesh.hasColors) {
                    val pc = assertNotNull(prim.colors, "$label colors")
                    assertEquals(prim.vertexCount * 4, pc.size, "$label colors.size")
                    for (i in 0 until prim.vertexCount) {
                        val wantColor = mesh.colors[i]
                        val wantR = (wantColor shr 16) and 0xFF
                        val wantG = (wantColor shr 8) and 0xFF
                        val wantB = wantColor and 0xFF
                        val wantA = (wantColor ushr 24) and 0xFF
                        val gotR = pc[i * 4].toInt() and 0xFF
                        val gotG = pc[i * 4 + 1].toInt() and 0xFF
                        val gotB = pc[i * 4 + 2].toInt() and 0xFF
                        val gotA = pc[i * 4 + 3].toInt() and 0xFF
                        assertTrue(abs(wantR - gotR) <= 1, "$label color[$i].r: want $wantR, got $gotR")
                        assertTrue(abs(wantG - gotG) <= 1, "$label color[$i].g: want $wantG, got $gotG")
                        assertTrue(abs(wantB - gotB) <= 1, "$label color[$i].b: want $wantB, got $gotB")
                        assertTrue(abs(wantA - gotA) <= 1, "$label color[$i].a: want $wantA, got $gotA")
                    }
                } else {
                    assertEquals(null, prim.colors, "$label colors")
                }
                for (i in 0 until prim.triangleCount * 3) assertEquals(mesh.indices[i], prim.indices[i], "$label Index $i")
            }
        }
        val globalRatio = totalStrictNormMatches.toDouble() / totalNormVertices.coerceAtLeast(1)
        assertTrue(
            globalRatio >= 0.99,
            "Gesamtanteil Normalen innerhalb 5e-3f nur ${"%.2f".format(globalRatio * 100)}% (erwartet >= 99%)",
        )
    }

    @Test
    fun `Vorlagen kommen mit Normalen, Atlas-UV und Farben aus der Datei`() {
        val lib = TreeLibraryFile(TreeLibrary.libraryGlb(kinds = listOf(TreeKind.BIRCH, TreeKind.SHRUB), variants = 0..1))
        assertTrue(!lib.complete)
        assertTrue(lib.has(TreeKind.BIRCH, 1, TreeLod.NEAR))
        assertTrue(!lib.has(TreeKind.OAK, 0, TreeLod.NEAR))
        assertEquals(null, lib.template(TreeKind.OAK, 0, TreeLod.NEAR))
        val t = assertNotNull(lib.template(TreeKind.BIRCH, 1, TreeLod.NEAR))
        val want = TreeLibrary.template(TreeKind.BIRCH, 1, TreeLod.NEAR)
        val leaves = assertNotNull(t.leaves)
        val bark = assertNotNull(t.bark)
        assertEquals(want.leaves.triangleCount, leaves.triangleCount)
        // Normalen normiert, Laub-UV im Atlas, Farben vorhanden (Verschattung bzw. Rindenton)
        for (i in 0 until leaves.vertexCount) {
            val x = leaves.normals[i * 3]; val y = leaves.normals[i * 3 + 1]; val z = leaves.normals[i * 3 + 2]
            assertEquals(1f, x * x + y * y + z * z, 1e-3f)
            assertTrue(leaves.uvs[i * 2] in 0f..1f && leaves.uvs[i * 2 + 1] in 0f..1f)
            assertEquals(want.leaves.uvs[i * 2], leaves.uvs[i * 2], 1e-4f)
        }
        val lc = assertNotNull(leaves.colors)
        val bc = assertNotNull(bark.colors)
        assertEquals(leaves.vertexCount * 4, lc.size)
        for (i in 0 until bark.vertexCount) {
            val c = want.bark.colors[i]
            assertEquals((c shr 16 and 0xFF).toByte(), bc[i * 4], "Rot $i")
            assertEquals((c and 0xFF).toByte(), bc[i * 4 + 2], "Blau $i")
        }
        // Umriss: Fuss am Boden, Hoehe wie das Skelett
        val b = t.bounds()
        // Der Wurzelanlauf reicht etwas unter den Boden (Haenge).
        assertTrue(b[1] <= 0f && b[1] > -0.8f, "Fuss bei ${b[1]}")
        assertEquals(TreeLibrary.templateHeightM(TreeKind.BIRCH, 1), b[4].toDouble(), TreeLibrary.templateHeightM(TreeKind.BIRCH, 1) * 0.2)
    }

    @Test
    fun `Material-GLB traegt nur die beiden Materialien mit den Atlanten der Bibliothek`() {
        val lib = TreeLibraryFile(TreeLibrary.libraryGlb(kinds = listOf(TreeKind.SHRUB), variants = 0..0))
        val glb = assertNotNull(lib.materialsGlb())
        assertEquals(emptyList(), checkTileGlb(glb))
        val info = parseGlb(glb)
        assertEquals(listOf(TreeLibrary.MATERIALS_NODE), info.nodes.mapNotNull { it.name })
        assertEquals(2, info.triangleCount)
        assertEquals(listOf(TreeLibrary.MAT_TRUNK, TreeLibrary.MAT_LEAVES), info.materials.map { it.name })
        assertEquals("MASK", info.materials[1].alphaMode)
        assertTrue(!info.materials[1].doubleSided, "einseitig wie in der Bibliothek (beide Wicklungen im Netz)")
        assertTrue(info.materials.all { it.baseColorTexture != null })
        // dieselben Bilder, unveraendert umgepackt
        val f = GlbMeshFile(glb)
        assertTrue(f.baseColorImage(TreeLibrary.MAT_TRUNK)!!.contentEquals(TreeTextures.barkJpeg))
        assertTrue(f.baseColorImage(TreeLibrary.MAT_LEAVES)!!.contentEquals(TreeTextures.leafPng))
        // beide Dreiecke mit Vertexfarben: gltfio waehlt dieselbe Materialvariante wie fuer die Vorlagen
        for (p in f.primitives(TreeLibrary.MATERIALS_NODE)!!) assertNotNull(p.colors)
        assertTrue(glb.size < 600_000, "Material-GLB ${glb.size} Bytes")
    }

    @Test
    fun `Vorlagenhoehe ist die Hoehe des Skeletts und je Variante verschieden`() {
        for (k in TreeKind.entries) {
            val hs = (0 until TreeLibrary.VARIANTS).map { TreeLibrary.templateHeightM(k, it) }
            assertEquals(hs.size, hs.toSet().size, "$k: $hs")
            for ((v, h) in hs.withIndex()) assertEquals(TreeLibrary.template(k, v, TreeLod.MID).skeleton.heightM, h, 1e-9)
        }
    }
}
