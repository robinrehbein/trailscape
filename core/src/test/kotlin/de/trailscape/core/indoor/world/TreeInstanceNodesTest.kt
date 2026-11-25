package de.trailscape.core.indoor.world

import de.trailscape.core.indoor.tree.TreeInstanceNames
import de.trailscape.core.indoor.tree.TreeKind
import de.trailscape.core.indoor.tree.TreeLibrary
import org.junit.jupiter.api.Timeout
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Abschnitts- und Instanzknoten der Bandbaeume in gebauten Kacheln und die Zuordnung zur Bibliothek. */
@Timeout(value = 300, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class TreeInstanceNodesTest {
    @Test
    fun `Kacheln der Testwelt tragen je Bandbaum einen Instanzknoten und je Abschnitt die gebackenen Baeume`() {
        val dir = tempDir("bauminstanzen")
        val m = createWorldBuilder().buildTestWorld(dir, IndoorQuality.MEDIUM)
        assertEquals(emptyList(), checkWorld(dir))
        var instances = 0
        var bins = 0
        for (t in m.tiles) {
            val bytes = File(dir, t.file).readBytes()
            val info = parseGlb(bytes)
            val placed = GlbMeshFile(bytes).placements(TreeInstanceNames.INSTANCE_PREFIX)
            val binNodes = info.nodes.filter { TreeInstanceNames.parseBin(it.name) != null }
            if (t.level != 0) {
                assertTrue(placed.isEmpty() && binNodes.isEmpty(), "${t.id}: Instanzen nur in Stufe 0")
                continue
            }
            if (placed.isEmpty()) {
                assertTrue(binNodes.isEmpty(), "${t.id}: Abschnitt ohne Instanzknoten")
                continue
            }
            val trees = assertNotNull(info.node(WorldNames.NODE_TREES), t.id)
            // Jeder Abschnitt haengt mit Netz unter `trees`, ohne eigene Transformation.
            for (b in binNodes) {
                assertEquals(trees.index, b.parent, "${t.id} ${b.name}")
                assertNotNull(b.mesh, "${t.id} ${b.name} ohne Netz")
                assertTrue(!b.hasTransform)
            }
            val binIds = binNodes.map { TreeInstanceNames.parseBin(it.name)!! }
            assertEquals(binIds.size, binIds.toSet().size, "${t.id}: Abschnitt doppelt")
            // Jeder Instanzknoten nennt eine Vorlage der Bibliothek und einen vorhandenen Abschnitt.
            val half = m.levels.first { it.level == 0 }.cellM / 2 + 1.0
            for (p in placed) {
                val (kind, variant, bin) = assertNotNull(TreeInstanceNames.parseInstance(p.name), p.name)
                assertTrue(bin in binIds, "${t.id}: ${p.name} ohne Abschnittsknoten")
                assertTrue(variant in 0 until TreeLibrary.VARIANTS)
                assertTrue(abs(p.x) <= half && abs(p.z) <= half, "${t.id}: ${p.name} ausserhalb der Kachel (${p.x}, ${p.z})")
                assertTrue(p.scale in 0.3..2.5, "${p.name}: Massstab ${p.scale}")
                val h = p.scale * TreeLibrary.templateHeightM(kind, variant)
                assertTrue(h in 1.0..32.0, "${p.name}: Hoehe $h m")
            }
            // Je Abschnitt so viele Instanzen wie gebackene Baeume: beide stammen aus derselben Schleife —
            // mindestens ein Baum je Abschnitt.
            for (id in binIds) assertTrue(placed.any { TreeInstanceNames.parseInstance(it.name)!!.third == id }, "${t.id}: Abschnitt $id ohne Instanz")
            instances += placed.size
            bins += binNodes.size
        }
        assertTrue(instances >= 20, "Die Testwelt hat Baeume am Weg: $instances Instanzknoten")
        assertTrue(bins >= 4, "$bins Abschnitte")
        // Abschnitte sind kurz: im Mittel deutlich weniger Baeume, als die Obergrenze der Darstellung fasst.
        assertTrue(instances / bins <= 40, "$instances Instanzen in $bins Abschnitten")
    }

    @Test
    fun `gebackene Baumdreiecke bleiben trotz der Abschnitte im Budget`() {
        val dir = tempDir("bauminstanzen-budget")
        val m = createWorldBuilder().buildTestWorld(dir, IndoorQuality.MEDIUM)
        assertTrue(m.budget.maxTriangles <= IndoorQuality.MEDIUM.maxTriangles)
    }

    @Test
    fun `jede Wuchsform des Weltbaus hat eine Vorlage`() {
        for (f in TreeModels.Form.entries) {
            val k = TreeInstanceMapping.kindOf(f, aleppo = false)
            assertTrue(k != TreeKind.ALEPPO_PINE)
            if (f == TreeModels.Form.PINE) assertEquals(TreeKind.ALEPPO_PINE, TreeInstanceMapping.kindOf(f, aleppo = true))
            else assertEquals(k, TreeInstanceMapping.kindOf(f, aleppo = true))
        }
        assertEquals(TreeKind.SHRUB, TreeInstanceMapping.kindOf(TreeModels.Form.BUSH, false))
        assertEquals(TreeKind.SPRUCE, TreeInstanceMapping.kindOf(TreeModels.Form.SPRUCE, false))
        assertEquals(TreeKind.BIRCH, TreeInstanceMapping.kindOf(TreeModels.Form.BIRCH, false))
    }

    @Test
    fun `Massstab trifft die Baumhoehe, Varianten streuen, uebliche Hoehen brauchen wenig Streckung`() {
        fun spec(species: TreeModels.Species, h: Double, seed: Int, pale: Boolean = false, variant: Int = 0) =
            TreeModels.Spec(species, h, 1.0, 1.0, seed, 0.0, pale, variant = variant)
        val heights = mapOf(
            TreeModels.Species.BROAD to listOf(8.0, 13.0, 16.0, 21.0),
            TreeModels.Species.TALL to listOf(10.0, 14.0, 19.0, 21.0),
            TreeModels.Species.CONIFER to listOf(9.0, 14.0, 20.0, 24.0),
            TreeModels.Species.PINE to listOf(10.0, 15.0, 23.0),
            TreeModels.Species.SMALL to listOf(4.5, 6.0, 8.0),
            TreeModels.Species.BUSH to listOf(1.2, 2.0, 3.2),
        )
        for ((species, hs) in heights) for (h in hs) for (pale in listOf(false, true)) {
            val seen = HashSet<Int>()
            for (seed in 0 until 40) {
                val s = spec(species, h, seed, pale)
                val (kind, variant, scale) = TreeInstanceMapping.instanceOf(s, aleppo = false)
                seen += variant
                assertEquals(h, scale * TreeLibrary.templateHeightM(kind, variant), 1e-6, "$species $h")
                // Ausserhalb der Spanne der Vorlagen wird gestreckt, sonst hoechstens MAX_STRETCH.
                val all = (0 until TreeLibrary.VARIANTS).map { TreeLibrary.templateHeightM(kind, it) }
                if (h >= all.min() / TreeInstanceMapping.MAX_STRETCH && h <= all.max() * TreeInstanceMapping.MAX_STRETCH) {
                    assertTrue(scale in 1 / TreeInstanceMapping.MAX_STRETCH - 1e-9..TreeInstanceMapping.MAX_STRETCH + 1e-9, "$species $h m als $kind/$variant: Massstab $scale")
                }
                // deterministisch
                assertEquals(Triple(kind, variant, scale), TreeInstanceMapping.instanceOf(s, aleppo = false))
            }
            assertTrue(seen.isNotEmpty())
        }
        // Eine mittlere Buche passt zu mehreren Varianten: der Same streut.
        val variants = (0 until 60).map { TreeInstanceMapping.instanceOf(spec(TreeModels.Species.BROAD, 20.0, it), false).second }.toSet()
        assertTrue(variants.size >= 2, "$variants")
    }

    @Test
    fun `Pruefung laesst Transformation nur an leeren Instanzknoten unter trees zu`() {
        fun scene(build: (GlbScene, Int) -> Unit): List<String> {
            val s = GlbScene(compactUv = true)
            val mat = s.addMaterial(GlbMaterial(WorldNames.MAT_TREE_TRUNK))
            val trees = s.addNode(WorldNames.NODE_TREES)
            val m = MeshBuilder(3, 1)
            m.tri(m.vertex(0.0, 0.0, 0.0, 0.0, 1.0, 0.0), m.vertex(1.0, 0.0, 0.0, 0.0, 1.0, 0.0), m.vertex(0.0, 0.0, -1.0, 0.0, 1.0, 0.0))
            s.addMesh(trees, m, mat)
            build(s, trees)
            return checkTileGlb(s.toBytes()).filter { "Instanzknoten" in it || "Abschnittsknoten" in it || "Transformation" in it }
        }
        val name = TreeInstanceNames.instanceName(TreeKind.OAK, 2, 5)
        assertEquals(emptyList(), scene { s, trees -> s.addNode(name, parent = trees, translation = Vec3(3.0, 1.0, -4.0), rotation = Quat.fromAxisAngle(Vec3(0.0, 1.0, 0.0), 1.0), scale = Vec3(0.9, 0.9, 0.9)) })
        // nicht unter `trees`
        assertTrue(scene { s, _ -> s.addNode(name, translation = Vec3(3.0, 1.0, -4.0)) }.any { "kein Kind" in it })
        // unbekannte Art
        assertTrue(scene { s, trees -> s.addNode("x_treei_palme_0_5", parent = trees, translation = Vec3(1.0, 0.0, 0.0)) }.any { "unlesbar" in it })
        // andere Knoten duerfen weiterhin keine Transformation haben
        assertTrue(scene { s, trees -> s.addNode("x_sonst", parent = trees, translation = Vec3(1.0, 0.0, 0.0)) }.any { "Transformation" in it })
        // Abschnitt muss unter `trees` haengen
        assertTrue(scene { s, _ -> s.addNode(TreeInstanceNames.binName(5)) }.any { "Abschnittsknoten" in it })
        assertEquals(emptyList(), scene { s, trees -> s.addNode(TreeInstanceNames.binName(5), parent = trees) })
    }

    @Test
    fun `Lage der Instanzknoten laesst sich aus der GLB lesen`() {
        val s = GlbScene(compactUv = true)
        val trees = s.addNode(WorldNames.NODE_TREES)
        s.addNode(TreeInstanceNames.instanceName(TreeKind.SPRUCE, 1, 9), parent = trees, translation = Vec3(12.5, 3.25, -40.0), rotation = Quat.fromAxisAngle(Vec3(0.0, 1.0, 0.0), 2.5), scale = Vec3(0.8, 0.8, 0.8))
        s.addNode(TreeInstanceNames.instanceName(TreeKind.SHRUB, 0, 9), parent = trees, translation = Vec3(-1.0, 0.0, 2.0), rotation = Quat.fromAxisAngle(Vec3(0.0, 1.0, 0.0), 0.0), scale = Vec3(1.0, 1.0, 1.0))
        val p = GlbMeshFile(s.toBytes()).placements(TreeInstanceNames.INSTANCE_PREFIX)
        assertEquals(2, p.size)
        assertEquals(12.5, p[0].x, 1e-6)
        assertEquals(3.25, p[0].y, 1e-6)
        assertEquals(-40.0, p[0].z, 1e-6)
        assertEquals(2.5, p[0].yawRad, 1e-5)
        assertEquals(0.8, p[0].scale, 1e-6)
        assertEquals(0.0, p[1].yawRad, 1e-9)
        assertEquals(1.0, p[1].scale, 1e-9)
    }
}
