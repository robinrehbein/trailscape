package de.trailscape.core.indoor.tree

import de.trailscape.core.indoor.world.GlbAlphaMode
import de.trailscape.core.indoor.world.GlbMaterial
import de.trailscape.core.indoor.world.GlbScene

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

    fun nodeName(kind: TreeKind, variant: Int, lod: TreeLod): String = "x_tree_${kind.name.lowercase()}_${variant}_${lod.name.lowercase()}"

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
        val trunk = scene.addMaterial(GlbMaterial(MAT_TRUNK, texture = barkTex, roughness = 0.95f, noSpecular = true))
        val leaves = scene.addMaterial(
            GlbMaterial(MAT_LEAVES, texture = leafTex, roughness = 0.95f, alphaMode = GlbAlphaMode.MASK, alphaCutoff = 0.5f, doubleSided = false, noSpecular = true),
        )
        for ((name, t) in items) {
            val node = scene.addNode(name)
            scene.addMesh(node, t.bark, trunk)
            scene.addMesh(node, t.leaves, leaves)
        }
        return scene.toBytes()
    }
}
