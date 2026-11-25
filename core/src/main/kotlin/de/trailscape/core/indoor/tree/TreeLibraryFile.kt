package de.trailscape.core.indoor.tree

import de.trailscape.core.indoor.world.GlbMeshFile
import de.trailscape.core.indoor.world.GlbPrimitiveData
import java.io.File

/**
 * # Die Baumbibliothek als Datei (App-Asset)
 *
 * Die Bibliothek wird **nicht auf dem Geraet erzeugt** und liegt in keinem
 * Streckenpaket: [writeTreeLibrary] schreibt sie einmal als GLB
 * ([TreeLibrary.ASSET_PATH], 72 Vorlagen, beide Atlanten), die Datei ist
 * eingecheckt, und `TreeLibraryAssetTest` prueft, dass sie zum Generator passt.
 *
 * Die App liest sie mit dieser Klasse: Netze je Vorlage erst bei Bedarf
 * ([template]) und die kleine Material-GLB fuer gltfio ([materialsGlb]).
 *
 * @throws IllegalArgumentException wenn [bytes] keine lesbare GLB ist.
 */
class TreeLibraryFile(bytes: ByteArray) {
    private val glb = GlbMeshFile(bytes)
    private val nodes: Set<String> = glb.meshNodeNames.toSet()

    /** Rinde und Laub einer Vorlage; eines von beiden kann fehlen (`null`). */
    class Template(val bark: GlbPrimitiveData?, val leaves: GlbPrimitiveData?) {
        val triangles: Int get() = (bark?.triangleCount ?: 0) + (leaves?.triangleCount ?: 0)

        /** Umriss `[minX, minY, minZ, maxX, maxY, maxZ]` ueber beide Netze. */
        fun bounds(): FloatArray {
            val b = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
            for (p in listOfNotNull(bark, leaves)) for (i in 0 until p.vertexCount * 3) {
                val c = i % 3
                val v = p.positions[i]
                if (v < b[c]) b[c] = v
                if (v > b[c + 3]) b[c + 3] = v
            }
            return b
        }
    }

    fun has(kind: TreeKind, variant: Int, lod: TreeLod): Boolean = TreeLibrary.nodeName(kind, variant, lod) in nodes

    /** Ob die Datei beide Stufen fuer jede Art und Variante enthaelt. */
    val complete: Boolean
        get() = TreeKind.entries.all { k -> (0 until TreeLibrary.VARIANTS).all { v -> TreeLod.entries.all { l -> has(k, v, l) } } }

    /** Die Netze einer Vorlage; `null`, wenn sie fehlt oder unlesbar ist. */
    fun template(kind: TreeKind, variant: Int, lod: TreeLod): Template? {
        val prims = glb.primitives(TreeLibrary.nodeName(kind, variant, lod)) ?: return null
        val bark = prims.firstOrNull { it.material == TreeLibrary.MAT_TRUNK }
        val leaves = prims.firstOrNull { it.material == TreeLibrary.MAT_LEAVES }
        if (bark == null && leaves == null) return null
        return Template(bark, leaves)
    }

    /**
     * Waagrechte Ausladung (m) der Vorlage um ihren Fusspunkt, die groessere beider Stufen
     * ([TreePlace.reachM] = dieser Wert × Massstab); `null`, wenn die Vorlage fehlt.
     */
    fun reachM(kind: TreeKind, variant: Int): Double? {
        var best: Double? = null
        for (lod in TreeLod.entries) {
            val r = glb.horizontalReach(TreeLibrary.nodeName(kind, variant, lod)) ?: continue
            if (best == null || r > best) best = r.toDouble()
        }
        return best
    }

    /** Die Material-GLB ([TreeLibrary.materialsGlb]) mit den Atlanten dieser Datei; `null`, wenn einer fehlt. */
    fun materialsGlb(): ByteArray? {
        val bark = glb.baseColorImage(TreeLibrary.MAT_TRUNK) ?: return null
        val leaf = glb.baseColorImage(TreeLibrary.MAT_LEAVES) ?: return null
        return TreeLibrary.materialsGlb(bark, leaf)
    }
}

/** Schreibt die Bibliothek nach [file] (Werkzeug `indoor-dev tree-library`); deterministisch. */
fun writeTreeLibrary(file: File) {
    file.parentFile?.mkdirs()
    file.writeBytes(TreeLibrary.libraryGlb())
}
