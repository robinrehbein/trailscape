package de.trailscape.app.indoor.render

import android.util.Log
import com.google.android.filament.Box
import com.google.android.filament.EntityManager
import com.google.android.filament.IndexBuffer
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.VertexBuffer
import com.google.android.filament.gltfio.ResourceLoader
import de.trailscape.core.indoor.tree.TangentFrames
import de.trailscape.core.indoor.tree.TreeBin
import de.trailscape.core.indoor.tree.TreeInstanceBudget
import de.trailscape.core.indoor.tree.TreeInstanceNames
import de.trailscape.core.indoor.tree.TreeInstancePlanner
import de.trailscape.core.indoor.tree.TreeKind
import de.trailscape.core.indoor.tree.TreeLibrary
import de.trailscape.core.indoor.tree.TreeLibraryFile
import de.trailscape.core.indoor.tree.TreeLod
import de.trailscape.core.indoor.tree.TreePlace
import de.trailscape.core.indoor.world.GlbPrimitiveData
import de.trailscape.core.indoor.world.RenderSettings
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.IdentityHashMap
import java.util.concurrent.Executor
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * # Baeume aus der Bibliothek, instanziert (docs/indoor/baeume.md)
 *
 * Kacheln ab `indoor-world/15` tragen je Bandbaum einen leeren Knoten
 * `x_treei_<art>_<variante>_<abschnitt>` und die gebackenen Bandbaeume je
 * Abschnitt in `x_trees_bin_<abschnitt>` ([TreeInstanceNames]). Auf den
 * Grafikstufen mit `RenderSettings.treeDetail` > 0 zeigt diese Klasse nah an
 * der Kamera statt der gebackenen Baeume **Instanzen der Bibliothek**
 * (`assets/indoor/models/trees.glb`, [TreeLibrary]): welche, rechnet
 * [TreeInstancePlanner] (`:core`, getestet); hier steht nur Filament.
 *
 *  * **Ein Netz je Vorlage:** Vertex- und Indexpuffer entstehen einmal je
 *    (Art, Variante, Stufe), erst wenn ein Baum sie braucht. Jeder Baum ist ein
 *    eigenes Renderable mit **denselben** Puffern und **denselben** zwei
 *    Materialinstanzen; mit `Engine.setAutomaticInstancingEnabled` fasst
 *    Filament sie zu instanzierten Zeichenaufrufen zusammen (die Java-API 1.75.1
 *    hat keinen `InstanceBuffer`). Jeder Baum behaelt seinen Umriss — Baeume
 *    ausserhalb des Bilds zeichnet Filament nicht.
 *  * **Materialien wie die Kacheln:** Eine winzige GLB mit den zwei Materialien
 *    und den Atlanten ([TreeLibraryFile.materialsGlb]) geht durch gltfio und
 *    damit durch [WorldMaterialProvider] — Laub mit Wind (`foliage.mat`,
 *    Atlas-Modus 1), Rinde ueber den Ubershader. Schalter Wind wirkt wie auf
 *    die Kacheln; Baeume werfen Schatten und empfangen keine (wie `trees`).
 *  * **Nie ein Loch:** Ein Abschnitt wird erst ausgeblendet ([hiddenBins]),
 *    wenn seine Instanzen stehen. Geht irgendetwas schief (Bibliothek fehlt,
 *    Puffer lassen sich nicht anlegen), bleibt es fuer den Rest der Sitzung bei
 *    den gebackenen Baeumen.
 *
 * Nur Hauptthread (ausser dem Lesen der Bibliothek auf [io]).
 */
internal class TreeInstancer(
    private val core: FilamentCore,
    /** Liest die Bibliothek (E/A-Thread); `null`, wenn sie fehlt. */
    private val readLibrary: () -> ByteArray?,
    private val io: Executor,
    private val post: (Runnable) -> Unit,
) {
    private enum class State { IDLE, LOADING, READY, FAILED }

    private class Mesh(val vertices: VertexBuffer, val indices: IndexBuffer)

    private class Template(val bark: Mesh?, val leaves: Mesh?, val box: Box, val triangles: Int)

    private class Shown(val entity: Int, val lod: TreeLod)

    private class TileTrees(val bins: Map<Int, TreeBin>) {
        var hidden: Set<String> = emptySet()
    }

    private var state = State.IDLE
    private var alive = true
    private var library: TreeLibraryFile? = null
    private var materials: LoadedAsset? = null
    private var barkMaterial: MaterialInstance? = null
    private var leafMaterial: MaterialInstance? = null
    private val templates = HashMap<Int, Template>()
    private val tiles = HashMap<String, TileTrees>()
    private val shown = IdentityHashMap<TreePlace, Shown>()
    private var autoInstancing = false
    private var lastCamX = Double.NaN
    private var lastCamZ = Double.NaN
    private var lastDetail = 0
    private var dirty = true

    /** Diagnose: Instanzen je Stufe, ihre Dreiecke, angelegte Vorlagen. */
    var nearCount = 0
        private set
    var midCount = 0
        private set
    var triangles = 0
        private set
    val templateCount: Int get() = templates.size

    /** Diagnoseseite, sprachneutral: `nah / mittel / Dreiecke / Vorlagen`; `–` aus, `…` laedt, `×` nicht verfuegbar. */
    val status: String
        get() = when (state) {
            State.IDLE -> "–"
            State.LOADING -> "…"
            State.FAILED -> "×"
            State.READY -> "$nearCount / $midCount / $triangles / $templateCount"
        }

    /** Eine Kachel ist fertig geladen: ihre Instanzknoten merken. [anchorX]… ist ihr Anker unter dem Weltknoten. */
    fun onTileReady(tileId: String, loaded: LoadedAsset, anchorX: Double, anchorY: Double, anchorZ: Double) {
        if (!alive) return
        val bins = HashMap<Int, ArrayList<TreePlace>>()
        val unknown = HashSet<Int>()
        runCatching {
            val tm = core.engine.transformManager
            val m = FloatArray(16)
            for (e in loaded.asset.entities) {
                val name = runCatching { loaded.asset.getName(e) }.getOrNull() ?: continue
                if (!name.startsWith(TreeInstanceNames.INSTANCE_PREFIX)) continue
                val parsed = TreeInstanceNames.parseInstance(name)
                if (parsed == null) {
                    // Unbekannte Art oder Variante (neuere Welt): der ganze Abschnitt bleibt gebacken.
                    name.substringAfterLast('_').toIntOrNull()?.let { unknown += it }
                    continue
                }
                val ti = tm.getInstance(e)
                if (ti == 0) continue
                tm.getTransform(ti, m)
                val scale = sqrt(m[0] * m[0] + m[1] * m[1] + m[2] * m[2]).toDouble()
                if (!(scale > 0.05 && scale < 20.0) || !m[12].isFinite() || !m[13].isFinite() || !m[14].isFinite()) {
                    unknown += parsed.third
                    continue
                }
                val yaw = atan2(m[8].toDouble(), m[10].toDouble())
                bins.getOrPut(parsed.third) { ArrayList() } +=
                    TreePlace(anchorX + m[12], anchorY + m[13], anchorZ + m[14], yaw, scale, parsed.first, parsed.second)
            }
        }.onFailure { Log.w(TAG, "Instanzknoten von $tileId unlesbar", it) }
        for (b in unknown) bins.remove(b)
        // Nur Abschnitte, die es in der Kachel auch gebacken gibt (sonst gaebe es nichts auszublenden — und nichts zum Zurueckfallen).
        val baked = loaded.categories.values.mapNotNullTo(HashSet()) { TreeInstanceNames.parseBin(it) }
        bins.keys.retainAll(baked)
        if (bins.isEmpty()) return
        tiles[tileId] = TileTrees(bins.mapValues { TreeBin(it.value) })
        dirty = true
    }

    /** Die Kachel wird entladen: ihre Instanzen verschwinden mit ihr. */
    fun onTileGone(tileId: String) {
        val t = tiles.remove(tileId) ?: return
        for (b in t.bins.values) for (p in b.trees) remove(p)
        dirty = true
    }

    /** Namen der Abschnittsknoten von [tileId], die gerade durch Instanzen ersetzt sind. */
    fun hiddenBins(tileId: String): Set<String> = tiles[tileId]?.hidden ?: emptySet()

    /**
     * Ein Bildschritt mit der Kamera an (`camX`, `camZ`) in Weltkoordinaten (vor dem schwebenden Ursprung).
     * `true`, wenn sich geaendert hat, welche Abschnitte ausgeblendet sind (dann [TileStreamer.invalidateVisibility]).
     */
    fun update(camX: Double, camZ: Double, settings: RenderSettings): Boolean {
        if (!alive) return false
        val wanted = if (settings.trees && state != State.FAILED) TreeInstanceBudget.forDetail(settings.treeDetail) else null
        if (wanted != null && state == State.IDLE && tiles.isNotEmpty()) startLoading()
        val budget = if (state == State.READY) wanted else null
        setAutoInstancing(budget != null)
        // Stufe gewechselt (oder Bibliothek eben fertig): sofort neu waehlen.
        val detail = if (budget == null) 0 else settings.treeDetail
        if (detail != lastDetail) {
            lastDetail = detail
            dirty = true
        }
        if (budget == null && shown.isEmpty() && !dirty) return false
        // Nur neu waehlen, wenn sich etwas bewegt hat.
        val dx = camX - lastCamX
        val dz = camZ - lastCamZ
        if (!dirty && dx * dx + dz * dz < MOVE_M * MOVE_M) return false
        dirty = false
        lastCamX = camX
        lastCamZ = camZ
        val all = ArrayList<TreeBin>()
        for (t in tiles.values) all += t.bins.values
        TreeInstancePlanner.select(all, camX, camZ, budget)
        return try {
            apply()
        } catch (e: OutOfMemoryError) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "Instanzen lassen sich nicht anlegen, es bleibt bei den gebackenen Baeumen", t)
            giveUp()
            true
        }
    }

    private fun apply(): Boolean {
        var changed = false
        var near = 0
        var mid = 0
        var tris = 0
        for (t in tiles.values) {
            val hidden = HashSet<String>()
            for ((id, bin) in t.bins) {
                for (p in bin.trees) {
                    val want = p.lod
                    val have = shown[p]
                    if (have?.lod != want) {
                        if (have != null) remove(p)
                        if (want != null) show(p, want)
                    }
                    if (want != null) {
                        if (want == TreeLod.NEAR) near++ else mid++
                        tris += template(p.kind, p.variant, want).triangles
                    }
                }
                if (bin.active) hidden += TreeInstanceNames.binName(id)
            }
            if (hidden != t.hidden) {
                t.hidden = hidden
                changed = true
            }
        }
        nearCount = near
        midCount = mid
        triangles = tris
        return changed
    }

    // ---------------------------------------------------------------- Bibliothek

    private fun startLoading() {
        state = State.LOADING
        io.execute {
            val result = runCatching {
                val bytes = readLibrary() ?: throw IllegalStateException("Bibliothek ${TreeLibrary.ASSET_PATH} fehlt")
                val lib = TreeLibraryFile(bytes)
                check(lib.complete) { "Bibliothek unvollstaendig" }
                lib to (lib.materialsGlb() ?: throw IllegalStateException("Atlanten fehlen"))
            }
            post(Runnable { onLibrary(result) })
        }
    }

    private fun onLibrary(result: Result<Pair<TreeLibraryFile, ByteArray>>) {
        if (!alive || state != State.LOADING) return
        val (lib, materialsGlb) = result.getOrElse {
            Log.w(TAG, "Baumbibliothek nicht nutzbar", it)
            state = State.FAILED
            return
        }
        try {
            // Eigener Lader: der gemeinsame fuehrt gerade vielleicht eine Kachel (asynchron, eine Ladung zugleich).
            val asset = core.assetLoader.createAsset(LoadedAsset.direct(materialsGlb)) ?: throw IllegalStateException("gltfio las die Material-GLB nicht")
            val loaded = LoadedAsset(core, asset)
            materials = loaded
            val loader = ResourceLoader(core.engine)
            try {
                loader.loadResources(asset)
            } finally {
                runCatching { loader.destroy() }
            }
            asset.releaseSourceData()
            for (mi in asset.instance.materialInstances) {
                when (mi.name) {
                    TreeLibrary.MAT_TRUNK -> barkMaterial = mi
                    TreeLibrary.MAT_LEAVES -> leafMaterial = mi
                }
            }
            check(barkMaterial != null && leafMaterial != null) { "Materialien der Bibliothek fehlen" }
            library = lib
            state = State.READY
            dirty = true
        } catch (e: OutOfMemoryError) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "Materialien der Baumbibliothek nicht ladbar", t)
            giveUp()
        }
    }

    private fun template(kind: TreeKind, variant: Int, lod: TreeLod): Template {
        val key = (kind.ordinal * TreeLibrary.VARIANTS + variant) * 2 + lod.ordinal
        templates[key]?.let { return it }
        val lib = library ?: throw IllegalStateException("Bibliothek nicht geladen")
        val data = lib.template(kind, variant, lod) ?: throw IllegalStateException("Vorlage $kind/$variant/$lod fehlt")
        val b = data.bounds()
        // Etwas Luft fuer das Wiegen im Wind.
        val box = Box(
            (b[0] + b[3]) / 2, (b[1] + b[4]) / 2, (b[2] + b[5]) / 2,
            (b[3] - b[0]) / 2 + BOUNDS_PAD_M, (b[4] - b[1]) / 2 + BOUNDS_PAD_M, (b[5] - b[2]) / 2 + BOUNDS_PAD_M,
        )
        var bark: Mesh? = null
        var leaves: Mesh? = null
        try {
            bark = data.bark?.let { mesh(it) }
            leaves = data.leaves?.let { mesh(it) }
        } catch (t: Throwable) {
            bark?.let { destroy(it) }
            leaves?.let { destroy(it) }
            throw t
        }
        return Template(bark, leaves, box, data.triangles).also { templates[key] = it }
    }

    /** Vertex- und Indexpuffer eines Primitivs: Lage, Tangentenrahmen aus der Normale, UV (auch als UV1), Farbe. */
    private fun mesh(p: GlbPrimitiveData): Mesh {
        val engine = core.engine
        val n = p.vertexCount
        val vb = VertexBuffer.Builder()
            .vertexCount(n)
            .bufferCount(4)
            .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, 12)
            .attribute(VertexBuffer.VertexAttribute.TANGENTS, 1, VertexBuffer.AttributeType.SHORT4, 0, 8)
            .normalized(VertexBuffer.VertexAttribute.TANGENTS)
            .attribute(VertexBuffer.VertexAttribute.UV0, 2, VertexBuffer.AttributeType.FLOAT2, 0, 8)
            // Der Ubershader (Rinde) verlangt auch UV1; gltfio legt dafuer Platzhalter an, hier zeigt es auf UV0.
            .attribute(VertexBuffer.VertexAttribute.UV1, 2, VertexBuffer.AttributeType.FLOAT2, 0, 8)
            .attribute(VertexBuffer.VertexAttribute.COLOR, 3, VertexBuffer.AttributeType.UBYTE4, 0, 4)
            .normalized(VertexBuffer.VertexAttribute.COLOR)
            .build(engine)
        var ib: IndexBuffer? = null
        try {
            vb.setBufferAt(engine, 0, direct(n * 12).apply { asFloatBuffer().put(p.positions, 0, n * 3) })
            vb.setBufferAt(engine, 1, direct(n * 8).apply { asShortBuffer().put(TangentFrames.quatsFromNormals(p.normals, n)) })
            vb.setBufferAt(engine, 2, direct(n * 8).apply { asFloatBuffer().put(p.uvs, 0, n * 2) })
            val colors = p.colors ?: ByteArray(n * 4) { 255.toByte() }
            vb.setBufferAt(engine, 3, direct(n * 4).apply { put(colors, 0, n * 4); flip() })
            val count = p.indices.size
            val small = n <= 65535
            ib = IndexBuffer.Builder()
                .indexCount(count)
                .bufferType(if (small) IndexBuffer.Builder.IndexType.USHORT else IndexBuffer.Builder.IndexType.UINT)
                .build(engine)
            if (small) {
                ib.setBuffer(engine, direct(count * 2).apply { asShortBuffer().put(ShortArray(count) { p.indices[it].toShort() }) })
            } else {
                ib.setBuffer(engine, direct(count * 4).apply { asIntBuffer().put(p.indices) })
            }
            return Mesh(vb, ib)
        } catch (t: Throwable) {
            runCatching { engine.destroyVertexBuffer(vb) }
            ib?.let { runCatching { engine.destroyIndexBuffer(it) } }
            throw t
        }
    }

    private fun direct(bytes: Int): ByteBuffer = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())

    // ---------------------------------------------------------------- Instanzen

    private fun show(p: TreePlace, lod: TreeLod) {
        val t = template(p.kind, p.variant, lod)
        val bark = t.bark
        val leaves = t.leaves
        val barkMi = barkMaterial
        val leafMi = leafMaterial
        val parts = (if (bark != null && barkMi != null) 1 else 0) + (if (leaves != null && leafMi != null) 1 else 0)
        if (parts == 0) throw IllegalStateException("Vorlage ${p.kind}/${p.variant}/$lod ohne Netz")
        val engine = core.engine
        val entity = EntityManager.get().create()
        try {
            val b = RenderableManager.Builder(parts)
                .boundingBox(t.box)
                .culling(true)
                // Wie die Knotenart `trees` (docs/indoor.md 7.3): werfen Schatten, empfangen keine.
                .castShadows(true)
                .receiveShadows(false)
            var i = 0
            if (bark != null && barkMi != null) {
                b.geometry(i, RenderableManager.PrimitiveType.TRIANGLES, bark.vertices, bark.indices).material(i, barkMi)
                i++
            }
            if (leaves != null && leafMi != null) {
                b.geometry(i, RenderableManager.PrimitiveType.TRIANGLES, leaves.vertices, leaves.indices).material(i, leafMi)
            }
            b.build(engine, entity)
            val tm = engine.transformManager
            tm.create(entity, tm.getInstance(core.worldRoot), matrix(p))
            core.scene.addEntity(entity)
        } catch (t2: Throwable) {
            runCatching { engine.destroyEntity(entity) }
            runCatching { EntityManager.get().destroy(entity) }
            throw t2
        }
        shown[p] = Shown(entity, lod)
    }

    private fun remove(p: TreePlace) {
        val s = shown.remove(p) ?: return
        runCatching { core.scene.removeEntity(s.entity) }
        runCatching { core.engine.destroyEntity(s.entity) }
        runCatching { EntityManager.get().destroy(s.entity) }
    }

    /** Verschiebung · Drehung um y · gleichmaessiger Massstab (spaltenweise, wie Filament). */
    private fun matrix(p: TreePlace): FloatArray {
        val c = (cos(p.yawRad) * p.scale).toFloat()
        val s = (sin(p.yawRad) * p.scale).toFloat()
        val m = identityMatrix()
        m[0] = c; m[2] = -s
        m[5] = p.scale.toFloat()
        m[8] = s; m[10] = c
        m[12] = p.x.toFloat(); m[13] = p.y.toFloat(); m[14] = p.z.toFloat()
        return m
    }

    private fun setAutoInstancing(on: Boolean) {
        if (on == autoInstancing) return
        autoInstancing = on
        runCatching { core.engine.setAutomaticInstancingEnabled(on) }
    }

    // ---------------------------------------------------------------- Ende

    /** Alles zurueck auf die gebackenen Baeume; fuer diese Sitzung kein neuer Versuch. */
    private fun giveUp() {
        state = State.FAILED
        release()
        for (t in tiles.values) {
            t.hidden = emptySet()
            for (b in t.bins.values) TreeInstancePlanner.select(listOf(b), 0.0, 0.0, null)
        }
        nearCount = 0
        midCount = 0
        triangles = 0
    }

    private fun release() {
        for (p in ArrayList(shown.keys)) remove(p)
        for (t in templates.values) {
            t.bark?.let { destroy(it) }
            t.leaves?.let { destroy(it) }
        }
        templates.clear()
        barkMaterial = null
        leafMaterial = null
        // Die Materialinstanzen gehoeren dem Asset; erst nach den Renderables freigeben.
        materials?.let { m -> runCatching { m.destroy() } }
        materials = null
        library = null
        setAutoInstancing(false)
    }

    private fun destroy(m: Mesh) {
        runCatching { core.engine.destroyVertexBuffer(m.vertices) }
        runCatching { core.engine.destroyIndexBuffer(m.indices) }
    }

    /** Gibt alles frei (neue Welt, Ende). Danach verbraucht. */
    fun destroy() {
        if (!alive) return
        alive = false
        release()
        tiles.clear()
    }

    companion object {
        private const val TAG = "TreeInstancer"

        /** Erst nach so viel Kamerabewegung wird neu gewaehlt (die Hysterese des Planers ist 3 m). */
        const val MOVE_M = 0.5

        /** Zuschlag auf den Umriss einer Vorlage (Wind). */
        const val BOUNDS_PAD_M = 0.6f
    }
}
