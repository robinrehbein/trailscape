package de.trailscape.app.indoor.render

import android.os.SystemClock
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
import de.trailscape.core.indoor.tree.TreeFade
import de.trailscape.core.indoor.tree.TreeFadeLook
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
 *  * **Wechsel ohne Sprung:** Instanzen und gebackene Baeume wechseln nicht je
 *    Abschnitt, sondern in einem **Ring um die Kamera** ([TreeFade]): `foliage.mat`
 *    rastert dort beide ineinander (`fadeMode` +1 an den Materialien der
 *    Bibliothek, −1 an den gebackenen Abschnitten) und toent die gebackenen
 *    Baeume bis zum Ring auf den Ton der Bibliothek ([TreeFadeLook]). Die
 *    Abschnitte einer Kachel bekommen dafuer **Kopien** ihrer zwei
 *    Materialinstanzen — die uebrigen Baeume und das Gras der Kachel teilen die
 *    Originale und blenden nicht. Reicht `maxTotal` nicht, rueckt der Ring
 *    naeher (stetig mit der Kamera); nach dem Laden waechst er auf, statt
 *    aufzuspringen. Kennt das Material `fadeMode` nicht (aelteres Paket,
 *    Ubershader als Rueckfall), schaltet wie frueher ein Abschnitt als Ganzes.
 *  * **Nie ein Loch:** Ein Abschnitt wird erst ausgeblendet ([hiddenBins]),
 *    wenn seine Instanzen stehen (im Ring: wenn er ganz innerhalb liegt). Geht
 *    irgendetwas schief (Bibliothek fehlt, Puffer lassen sich nicht anlegen),
 *    bleibt es fuer den Rest der Sitzung bei den gebackenen Baeumen.
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

    /** Ein Primitiv eines gebackenen Abschnitts mit seinem Material und dessen Kopie fuer den Ring. */
    private class FadeSlot(val entity: Int, val primitive: Int, val original: MaterialInstance, val copy: MaterialInstance)

    private class TileTrees(val bins: Map<Int, TreeBin>, val loaded: LoadedAsset) {
        var hidden: Set<String> = emptySet()

        /** Umgehaengte Primitive, solange der Ring an ist; `null` = Abschnitte tragen ihre Originale. */
        var fade: List<FadeSlot>? = null

        /** Die Kopien dazu (hoechstens zwei: Laub, Rinde). */
        var fadeCopies: List<MaterialInstance> = emptyList()

        /** Die Materialien dieser Kachel koennen nicht blenden: ihre Abschnitte bleiben gebacken. */
        var fadeFailed = false
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

    /** Ausladung je Vorlage (Art × Variante), vom E/A-Thread mit der Bibliothek gelesen. */
    private var reach: Map<Int, Double> = emptyMap()

    /** Die Materialien der Bibliothek kennen `fadeMode` (sonst der alte Wechsel je Abschnitt). */
    private var canFade = false
    private var fading = false
    private var fadeGrowing = false
    private var lastPlanNs = 0L

    /** Diagnose: aeusserer Rand des Wechselrings (m um die Kamera); 0 = kein Ring. */
    var fadeEndM = 0.0
        private set

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
        for (list in bins.values) for (p in list) setReach(p)
        tiles[tileId] = TileTrees(bins.mapValues { TreeBin(it.value) }, loaded)
        dirty = true
    }

    /** Die Kachel wird entladen: ihre Instanzen verschwinden mit ihr. Vor dem Zerstoeren ihres Assets rufen. */
    fun onTileGone(tileId: String) {
        val t = tiles.remove(tileId) ?: return
        for (b in t.bins.values) for (p in b.trees) remove(p)
        dropFade(t)
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
        // Ring an oder aus: die Abschnitte tragen Kopien ihrer Materialien nur, solange geblendet wird.
        val fade = budget != null && canFade
        if (fade != fading) {
            fading = fade
            fadeEndM = 0.0
            dirty = true
            runCatching { setLibraryFade(null) }
            if (!fade) for (t in tiles.values) dropFade(t)
        }
        // Nur neu waehlen, wenn sich etwas bewegt hat — oder der Ring noch waechst.
        val dx = camX - lastCamX
        val dz = camZ - lastCamZ
        if (!dirty && !fadeGrowing && dx * dx + dz * dz < MOVE_M * MOVE_M) return false
        dirty = false
        lastCamX = camX
        lastCamZ = camZ
        val now = SystemClock.elapsedRealtimeNanos()
        val dt = ((now - lastPlanNs) / 1e9).coerceIn(0.0, MAX_STEP_S)
        lastPlanNs = now
        fadeGrowing = false
        try {
            if (fade) {
                val all = ArrayList<TreeBin>()
                for (t in tiles.values) {
                    ensureFade(t)
                    if (t.fade != null) all += t.bins.values else for (b in t.bins.values) TreeInstancePlanner.select(listOf(b), 0.0, 0.0, null)
                }
                val ring = TreeInstancePlanner.selectFading(all, camX, camZ, budget, fadeEndM + TreeFadeLook.GROW_M_PER_S * dt)
                if (ring != null) {
                    fadeEndM = ring.endM
                    fadeGrowing = ring.growing
                    setLibraryFade(ring)
                    for (t in tiles.values) for (c in t.fadeCopies) setRange(c, ring)
                }
            } else {
                val all = ArrayList<TreeBin>()
                for (t in tiles.values) all += t.bins.values
                TreeInstancePlanner.select(all, camX, camZ, budget)
            }
        } catch (e: OutOfMemoryError) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "Wechselring laesst sich nicht einstellen, es bleibt bei den gebackenen Baeumen", t)
            giveUp()
            return true
        }
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

    private class Library(val file: TreeLibraryFile, val materialsGlb: ByteArray, val reach: Map<Int, Double>)

    private fun startLoading() {
        state = State.LOADING
        io.execute {
            val result = runCatching {
                val bytes = readLibrary() ?: throw IllegalStateException("Bibliothek ${TreeLibrary.ASSET_PATH} fehlt")
                val lib = TreeLibraryFile(bytes)
                check(lib.complete) { "Bibliothek unvollstaendig" }
                val reach = HashMap<Int, Double>()
                for (k in TreeKind.entries) for (v in 0 until TreeLibrary.VARIANTS) lib.reachM(k, v)?.let { reach[reachKey(k, v)] = it }
                Library(lib, lib.materialsGlb() ?: throw IllegalStateException("Atlanten fehlen"), reach)
            }
            post(Runnable { onLibrary(result) })
        }
    }

    private fun reachKey(kind: TreeKind, variant: Int): Int = kind.ordinal * TreeLibrary.VARIANTS + variant

    /** Ausladung des Baums aus der Vorlage; ohne Bibliothek bleibt die vorsichtige Vorgabe. */
    private fun setReach(p: TreePlace) {
        val r = reach[reachKey(p.kind, p.variant)] ?: return
        p.reachM = r * p.scale
    }

    private fun onLibrary(result: Result<Library>) {
        if (!alive || state != State.LOADING) return
        val loadedLibrary = result.getOrElse {
            Log.w(TAG, "Baumbibliothek nicht nutzbar", it)
            state = State.FAILED
            return
        }
        val lib = loadedLibrary.file
        val materialsGlb = loadedLibrary.materialsGlb
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
            canFade = barkMaterial?.material?.hasParameter(FADE_MODE) == true && leafMaterial?.material?.hasParameter(FADE_MODE) == true
            reach = loadedLibrary.reach
            for (t in tiles.values) for (b in t.bins.values) for (p in b.trees) setReach(p)
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

    // ---------------------------------------------------------------- Wechselring

    /** Materialien der Bibliothek: sichtbar bis zum Ring ([ring] `null` = ueberall, kein Ring). */
    private fun setLibraryFade(ring: TreeFade?) {
        if (!canFade) return
        for (mi in listOfNotNull(barkMaterial, leafMaterial)) {
            mi.setParameter(FADE_MODE, if (ring == null) 0f else 1f)
            if (ring != null) setRange(mi, ring)
        }
    }

    private fun setRange(mi: MaterialInstance, ring: TreeFade) {
        mi.setParameter(FADE_RANGE, ring.startM.toFloat(), ring.endM.toFloat(), (ring.endM + TreeFadeLook.TINT_FALLOFF_M).toFloat(), 0f)
    }

    /**
     * Gibt den gebackenen Abschnitten von [t] Kopien ihrer Materialinstanzen (`fadeMode` −1, Ton der
     * Bibliothek am Ring). Kann eines ihrer Materialien nicht blenden, bleibt die Kachel, wie sie ist.
     */
    private fun ensureFade(t: TileTrees) {
        if (t.fade != null || t.fadeFailed) return
        val rm = core.engine.renderableManager
        val copies = IdentityHashMap<MaterialInstance, MaterialInstance>()
        val slots = ArrayList<FadeSlot>()
        var ok = true
        try {
            loop@ for ((e, cat) in t.loaded.categories) {
                val bin = TreeInstanceNames.parseBin(cat) ?: continue
                if (bin !in t.bins) continue
                val ri = rm.getInstance(e)
                if (ri == 0) continue
                for (i in 0 until rm.getPrimitiveCount(ri)) {
                    val original = rm.getMaterialInstanceAt(ri, i)
                    // getMaterialInstanceAt liefert je Aufruf ein neues Java-Objekt: nach dem nativen Zeiger suchen.
                    val known = copies.entries.firstOrNull { it.key.nativeObject == original.nativeObject }
                    val copy = known?.value ?: core.worldMaterials.copyOf(original, FADE_MODE)?.also { c ->
                        copies[original] = c
                        val bark = WorldMaterialRules.cullsBackFaces(original.name)
                        c.setParameter(FADE_MODE, -1f)
                        set3(c, "fadeTintDark", if (bark) TreeFadeLook.BARK_DARK else TreeFadeLook.LEAF_DARK)
                        set3(c, "fadeTintLight", if (bark) TreeFadeLook.BARK_LIGHT else TreeFadeLook.LEAF_LIGHT)
                        val luma = if (bark) TreeFadeLook.BARK_LUMA else TreeFadeLook.LEAF_LUMA
                        c.setParameter("fadeTintLuma", luma[0], luma[1])
                    }
                    if (copy == null) {
                        ok = false
                        break@loop
                    }
                    slots += FadeSlot(e, i, known?.key ?: original, copy)
                }
            }
        } catch (e: OutOfMemoryError) {
            throw e
        } catch (x: Throwable) {
            Log.w(TAG, "Materialien der Abschnitte lassen sich nicht kopieren", x)
            ok = false
        }
        if (!ok || slots.isEmpty()) {
            for (c in copies.values) core.worldMaterials.dropCopy(c)
            t.fadeFailed = true
            return
        }
        // Erst wenn alle Kopien stehen, umhaengen: so traegt ein Abschnitt nie halb das eine, halb das andere.
        for (s in slots) rm.getInstance(s.entity).takeIf { it != 0 }?.let { rm.setMaterialInstanceAt(it, s.primitive, s.copy) }
        t.fade = slots
        t.fadeCopies = ArrayList(copies.values)
    }

    /** Haengt die Originale zurueck und gibt die Kopien frei (Ring aus, Kachel geht, Ende). */
    private fun dropFade(t: TileTrees) {
        val slots = t.fade ?: return
        val copies = t.fadeCopies
        t.fade = null
        t.fadeCopies = emptyList()
        val rm = core.engine.renderableManager
        for (s in slots) {
            runCatching { rm.getInstance(s.entity).takeIf { it != 0 }?.let { rm.setMaterialInstanceAt(it, s.primitive, s.original) } }
        }
        for (c in copies) core.worldMaterials.dropCopy(c)
    }

    private fun set3(mi: MaterialInstance, name: String, v: FloatArray) = mi.setParameter(name, v[0], v[1], v[2])

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
            dropFade(t)
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
        canFade = false
        fading = false
        fadeGrowing = false
        fadeEndM = 0.0
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
        for (t in tiles.values) dropFade(t)
        release()
        tiles.clear()
    }

    companion object {
        private const val TAG = "TreeInstancer"

        /** Erst nach so viel Kamerabewegung wird neu gewaehlt (die Hysterese des Planers ist 3 m). */
        const val MOVE_M = 0.5

        /** Zuschlag auf den Umriss einer Vorlage (Wind). */
        const val BOUNDS_PAD_M = 0.6f

        /** Laengster Zeitschritt (s), um den der Ring in einem Bild waechst (nach einer Pause kein Sprung). */
        const val MAX_STEP_S = 0.1

        private const val FADE_MODE = "fadeMode"
        private const val FADE_RANGE = "fadeRange"

    }
}
