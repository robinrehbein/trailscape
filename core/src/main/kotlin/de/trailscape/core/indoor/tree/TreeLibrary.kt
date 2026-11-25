package de.trailscape.core.indoor.tree

import de.trailscape.core.indoor.world.GlbAlphaMode
import de.trailscape.core.indoor.world.GlbMaterial
import de.trailscape.core.indoor.world.GlbScene
import de.trailscape.core.indoor.world.MeshBuilder

/**
 * # Vorlagen und GLB-Ausgabe
 *
 * Eine **Vorlage** ist ein Baum einer Art in einer Variante (0..3) und Stufe:
 * zwei Netze, zwei Primitive. Vorlagen sind deterministisch (Art, Variante,
 * Same) und werden **gedreht und skaliert instanziert** — wenige Vorlagen,
 * viele Baeume (siehe Einbauplan in `docs/indoor/baeume.md`).
 *
 * Materialien wie der heutige Weltbau sie schreibt und die Darstellung sie
 * versteht (`WorldMaterialRules`, `foliage.mat`, unveraendert):
 *  * Rinde `mat_tree_trunk`: Kernmaterial, Rindenatlas (JPEG, kachelnd),
 *    Rauheit 0,95, ohne Spiegelanteil (`noSpecular` wie heute; die App
 *    entschaerft die Erweiterung in `GlbPatch`),
 *  * Laub `x_mat_tree_cards`: Laubatlas (PNG mit Alpha), `MASK` 0,5,
 *    **einseitig**, beide Wicklungen im Netz — die Darstellung tauscht es gegen
 *    das Laubmaterial mit Wind (Atlas-Modus 1; die Quadranten des Atlas folgen
 *    `windWeight`).
 * Keine neue glTF-Erweiterung.
 */
object TreeLibrary {
    const val MAT_TRUNK = "mat_tree_trunk"
    const val MAT_LEAVES = "x_mat_tree_cards"

    /** Same der Vorlagen (fest: die Vorlagen sind Teil der Bibliothek, nicht der Welt). */
    const val TEMPLATE_SEED = 1L

    /** Zahl der Varianten je Art in der Bibliothek. */
    const val VARIANTS = 4

    /** Die Bibliothek als App-Asset (unter `assets/`), eine GLB mit allen Vorlagen. */
    const val ASSET_PATH = "indoor/models/trees.glb"

    private val heights = HashMap<Int, Double>()

    /**
     * Hoehe der Vorlage (m) — der Weltbau teilt die Baumhoehe durch sie und schreibt den Massstab in
     * den Instanzknoten; die App braucht die Hoehen deshalb nicht zu kennen.
     */
    fun templateHeightM(kind: TreeKind, variant: Int): Double = synchronized(heights) {
        heights.getOrPut(kind.ordinal * 16 + variant) { TreeGrowth.grow(kind, variant, TEMPLATE_SEED).heightM }
    }

    /** Art, Variante und Stufe aus einem Knotennamen der Bibliothek ([nodeName]); `null`, wenn er nicht passt. */
    fun parseNodeName(name: String): Triple<TreeKind, Int, TreeLod>? {
        if (!name.startsWith(NODE_PREFIX)) return null
        val parts = name.substring(NODE_PREFIX.length).split('_')
        if (parts.size < 3) return null
        val lod = TreeLod.entries.firstOrNull { it.name.lowercase() == parts[parts.size - 1] } ?: return null
        val variant = parts[parts.size - 2].toIntOrNull() ?: return null
        val kindName = parts.subList(0, parts.size - 2).joinToString("_")
        val kind = TreeKind.entries.firstOrNull { it.name.lowercase() == kindName } ?: return null
        return Triple(kind, variant, lod)
    }

    const val NODE_PREFIX = "x_tree_"

    /** Knoten der kleinen Material-GLB ([materialsGlb]). */
    const val MATERIALS_NODE = "x_tree_materials"

    /**
     * Eine winzige GLB mit **nur den beiden Materialien** der Bibliothek (Rinde, Laub) und ihren
     * Atlanten [barkJpeg]/[leafPng] an zwei Dreiecken. Die App laedt sie ueber gltfio, damit die
     * Materialien der Instanzen genau so entstehen wie die der Kacheln (`WorldMaterialProvider`:
     * Laub mit Wind, Rinde ueber den Ubershader) — die Netze der Vorlagen legt sie selbst an.
     * Reines Umpacken, nichts wird gezeichnet oder gerechnet.
     */
    fun materialsGlb(barkJpeg: ByteArray, leafPng: ByteArray): ByteArray {
        val scene = GlbScene(compactUv = true)
        val (trunk, leaves) = addMaterials(scene, scene.addTexture(barkJpeg, "image/jpeg", repeat = true), scene.addTexture(leafPng, "image/png", repeat = false))
        val node = scene.addNode(MATERIALS_NODE)
        // Nicht ganz weiss: so schreibt der GLB-Bau `COLOR_0`, und gltfio waehlt die Materialvariante
        // mit Vertexfarben — die Netze der Vorlagen bringen immer welche mit.
        fun tri(): MeshBuilder {
            val m = MeshBuilder(3, 1)
            val c = 0xFFFEFEFE.toInt()
            m.tri(m.vertex(0.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, c), m.vertex(0.01, 0.0, 0.0, 0.0, 0.0, 1.0, 1.0, 0.0, c), m.vertex(0.0, 0.01, 0.0, 0.0, 0.0, 1.0, 0.0, 1.0, c))
            return m
        }
        scene.addMesh(node, tri(), trunk)
        scene.addMesh(node, tri(), leaves)
        return scene.toBytes()
    }

    private fun addMaterials(scene: GlbScene, barkTex: Int?, leafTex: Int?): Pair<Int, Int> {
        val trunk = scene.addMaterial(GlbMaterial(MAT_TRUNK, texture = barkTex, roughness = 0.95f, noSpecular = true))
        val leaves = scene.addMaterial(
            GlbMaterial(MAT_LEAVES, texture = leafTex, roughness = 0.95f, alphaMode = GlbAlphaMode.MASK, alphaCutoff = 0.5f, doubleSided = false, noSpecular = true),
        )
        return trunk to leaves
    }

    internal fun template(kind: TreeKind, variant: Int, lod: TreeLod, heightM: Double? = null, seed: Long = TEMPLATE_SEED): TreeMeshes =
        TreeMesher.mesh(TreeGrowth.grow(kind, variant, seed, heightM), lod)

    /** Dreiecke einer Vorlage. */
    fun triangles(kind: TreeKind, variant: Int, lod: TreeLod): Int = template(kind, variant, lod).triangles

    /** Kennzahlen einer Vorlage. */
    class Stats(val triangles: Int, val vertices: Int, val primitives: Int, val heightM: Double, val crownWidthM: Double, val meshBytes: Int)

    fun stats(kind: TreeKind, variant: Int, lod: TreeLod): Stats {
        val t = template(kind, variant, lod)
        val glbNoTex = glb(listOf("x_tree" to t), textures = false)
        return Stats(
            t.triangles, t.bark.vertexCount + t.leaves.vertexCount,
            (if (t.bark.isEmpty) 0 else 1) + (if (t.leaves.isEmpty) 0 else 1),
            t.skeleton.heightM, 2 * t.skeleton.crownRx, glbNoTex.size,
        )
    }

    /** GLB einer Vorlage (Knoten `trees`, zwei Primitive), mit eingebetteten Atlanten. */
    fun templateGlb(kind: TreeKind, variant: Int, lod: TreeLod): ByteArray =
        glb(listOf("trees" to template(kind, variant, lod)), textures = true)

    /**
     * Die ganze Bibliothek als **eine** GLB: je Vorlage ein Knoten `x_tree_<art>_<variante>_<stufe>`
     * (Kleinbuchstaben), beide Atlanten einmal. So wuerde sie als App-Asset (wie `rider.glb`) liegen.
     */
    fun libraryGlb(kinds: List<TreeKind> = TreeKind.entries, variants: IntRange = 0..3, lods: List<TreeLod> = TreeLod.entries): ByteArray {
        val items = ArrayList<Pair<String, TreeMeshes>>()
        for (k in kinds) for (v in variants) for (l in lods) items += nodeName(k, v, l) to template(k, v, l)
        return glb(items, textures = true)
    }

    fun nodeName(kind: TreeKind, variant: Int, lod: TreeLod): String = "$NODE_PREFIX${kind.name.lowercase()}_${variant}_${lod.name.lowercase()}"

    /** Groesse der beiden Atlanten (Bytes in der Datei; Grafikspeicher mit Mip-Stufen). */
    fun textureBytes(): Pair<Int, Long> {
        val file = TreeTextures.leafPng.size + TreeTextures.barkJpeg.size
        val gpu = (TreeTextures.LEAF_SIZE.toLong() * TreeTextures.LEAF_SIZE + TreeTextures.BARK_W.toLong() * TreeTextures.BARK_H) * 4 * 4 / 3
        return file to gpu
    }

    private fun glb(items: List<Pair<String, TreeMeshes>>, textures: Boolean): ByteArray {
        val scene = GlbScene(compactUv = true)
        val barkTex = if (textures) scene.addTexture(TreeTextures.barkJpeg, "image/jpeg", repeat = true) else null
        val leafTex = if (textures) scene.addTexture(TreeTextures.leafPng, "image/png", repeat = false) else null
        val (trunk, leaves) = addMaterials(scene, barkTex, leafTex)
        for ((name, t) in items) {
            val node = scene.addNode(name)
            scene.addMesh(node, t.bark, trunk)
            scene.addMesh(node, t.leaves, leaves)
        }
        return scene.toBytes()
    }
}
