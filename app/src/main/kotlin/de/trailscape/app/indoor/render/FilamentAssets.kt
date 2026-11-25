package de.trailscape.app.indoor.render

import com.google.android.filament.MaterialInstance
import com.google.android.filament.gltfio.FilamentAsset
import de.trailscape.core.indoor.world.WorldModels
import de.trailscape.core.indoor.world.WorldNames
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Ein geladenes glTF-Asset mit der Knotenart jedes Renderables
 * (`terrain`, `trees`, `terrain_sub_1_2` …), damit Schalter und
 * Teilflaechen-Regel einzelne Teile ein- und ausblenden koennen.
 * Nur Hauptthread.
 */
internal class LoadedAsset(private val core: FilamentCore, val asset: FilamentAsset) {
    /** Renderable-Entity → Knotenart (oder `null`). */
    val categories: Map<Int, String?>
    private val visible = HashMap<Int, Boolean>()
    var inScene = false
        private set

    init {
        val tm = core.engine.transformManager
        val root = asset.root
        val map = HashMap<Int, String?>()
        for (e in asset.renderableEntities) {
            val names = ArrayList<String?>(6)
            var cur = e
            var guard = 0
            while (cur != 0 && guard++ < 32) {
                names += runCatching { asset.getName(cur) }.getOrNull()
                if (cur == root) break
                val ti = tm.getInstance(cur)
                if (ti == 0) break
                cur = tm.getParent(ti)
            }
            map[e] = nodeCategory(names)
        }
        categories = map
    }

    /** Schatten je Knotenart; [all] ueberschreibt (Fahrer: wirft und empfaengt). */
    fun applyShadows(all: Pair<Boolean, Boolean>? = null) {
        val rm = core.engine.renderableManager
        for ((e, cat) in categories) {
            val ri = rm.getInstance(e)
            if (ri == 0) continue
            val (cast, receive) = all ?: shadowFlags(baseCategory(cat))
            rm.setCastShadows(ri, cast)
            rm.setReceiveShadows(ri, receive)
        }
    }

    /** Sichtbarkeit je Renderable ueber die Ebenenmaske (0 = in keiner View). Nur Aenderungen gehen an Filament. */
    fun applyVisibility(isVisible: (String?) -> Boolean) {
        val rm = core.engine.renderableManager
        for ((e, cat) in categories) {
            val v = isVisible(cat)
            if (visible[e] == v) continue
            val ri = rm.getInstance(e)
            if (ri == 0) continue
            rm.setLayerMask(ri, 0xFF, if (v) 0x01 else 0x00)
            visible[e] = v
        }
    }

    fun setAllVisible(v: Boolean) = applyVisibility { v }

    /** Urspruengliche Materialien der Wasser-Renderables, solange [applyWater] ein eigenes gesetzt hat. */
    private var waterOriginals: Map<Int, List<MaterialInstance>>? = null
    private var waterApplied: MaterialInstance? = null

    /**
     * Setzt [mi] auf alle Teile der Knotenart `water` (oder stellt mit `null` das Material
     * der Kachel wieder her). Wasser wirft dann keinen Schatten.
     */
    fun applyWater(mi: MaterialInstance?) {
        if (mi === waterApplied) return
        val rm = core.engine.renderableManager
        val waterEntities = categories.filterValues { it == WorldNames.NODE_WATER }.keys
        if (waterEntities.isEmpty()) {
            waterApplied = mi
            return
        }
        if (mi != null) {
            val originals = waterOriginals ?: HashMap<Int, List<MaterialInstance>>().also { map ->
                for (e in waterEntities) {
                    val ri = rm.getInstance(e)
                    if (ri == 0) continue
                    map[e] = (0 until rm.getPrimitiveCount(ri)).map { rm.getMaterialInstanceAt(ri, it) }
                }
                waterOriginals = map
            }
            for (e in originals.keys) {
                val ri = rm.getInstance(e)
                if (ri == 0) continue
                for (p in 0 until rm.getPrimitiveCount(ri)) rm.setMaterialInstanceAt(ri, p, mi)
                rm.setCastShadows(ri, false)
            }
        } else {
            waterOriginals?.forEach { (e, list) ->
                val ri = rm.getInstance(e)
                if (ri != 0) list.forEachIndexed { p, orig -> rm.setMaterialInstanceAt(ri, p, orig) }
            }
            waterOriginals = null
        }
        waterApplied = mi
    }

    fun addToScene() {
        if (inScene) return
        core.show(asset)
        inScene = true
    }

    fun setRootTransform(m: FloatArray) {
        val tm = core.engine.transformManager
        val i = tm.getInstance(asset.root)
        if (i != 0) tm.setTransform(i, m)
    }

    fun setEntityTransform(entity: Int, m: FloatArray) {
        val tm = core.engine.transformManager
        val i = tm.getInstance(entity)
        if (i != 0) tm.setTransform(i, m)
    }

    fun setParent(parentEntity: Int) {
        val tm = core.engine.transformManager
        val i = tm.getInstance(asset.root)
        val p = tm.getInstance(parentEntity)
        if (i != 0 && p != 0) tm.setParent(i, p)
    }

    fun destroy() {
        core.destroyAsset(asset)
        inScene = false
    }

    companion object {
        /** GLB-Bytes in einen direkten Puffer (gltfio liest ueber JNI). */
        fun direct(bytes: ByteArray): ByteBuffer =
            ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply {
                put(bytes)
                flip()
                GlbPatch.neutralizeSpecular(this)
            }

        /**
         * Erzeugt ein Asset und laedt seine Ressourcen **synchron** — nur fuer
         * kleine Modelle ohne Texturen (Fahrer, Geist, Testszene).
         */
        fun loadSync(core: FilamentCore, glb: ByteArray): LoadedAsset {
            val buffer = direct(glb)
            val asset = core.assetLoader.createAsset(buffer)
                ?: throw IllegalStateException("gltfio konnte das Modell nicht lesen (${glb.size} Bytes)")
            try {
                core.resourceLoader.loadResources(asset)
                asset.releaseSourceData()
                return LoadedAsset(core, asset)
            } catch (t: Throwable) {
                runCatching { core.assetLoader.destroyAsset(asset) }
                throw t
            }
        }
    }
}

/**
 * Fahrer oder Geist: ein Modell nach `WorldModels` mit den Animationen
 * `pedal` und `roll`. Fehlt eine Animation, steht der Teil still — kein Fehler.
 */
internal class AvatarActor(val loaded: LoadedAsset) {
    private val animator = runCatching { loaded.asset.instance.animator }.getOrNull()
    private val pedalIndex: Int
    private val rollIndex: Int
    private val pedalDuration: Float
    private val rollDuration: Float
    var visible = true
        private set

    init {
        var p = -1
        var r = -1
        val a = animator
        if (a != null) {
            val count = runCatching { a.animationCount }.getOrDefault(0)
            for (i in 0 until count) {
                when (runCatching { a.getAnimationName(i) }.getOrNull()) {
                    WorldModels.ANIM_PEDAL -> p = i
                    WorldModels.ANIM_ROLL -> r = i
                }
            }
        }
        pedalIndex = p
        rollIndex = r
        pedalDuration = if (p >= 0) runCatching { a!!.getAnimationDuration(p) }.getOrDefault(1f) else 1f
        rollDuration = if (r >= 0) runCatching { a!!.getAnimationDuration(r) }.getOrDefault(1f) else 1f
        loaded.applyShadows(true to true)
        loaded.addToScene()
    }

    val hasAnimations: Boolean get() = pedalIndex >= 0 || rollIndex >= 0

    fun place(matrix: FloatArray) = loaded.setRootTransform(matrix)

    /** Phasen in Umdrehungen 0..1; die Animationen sind je eine Umdrehung lang. */
    fun animate(crankPhase: Double, wheelPhase: Double) {
        val a = animator ?: return
        if (pedalIndex >= 0) a.applyAnimation(pedalIndex, (crankPhase * pedalDuration).toFloat().coerceIn(0f, pedalDuration))
        if (rollIndex >= 0) a.applyAnimation(rollIndex, (wheelPhase * rollDuration).toFloat().coerceIn(0f, rollDuration))
        if (pedalIndex >= 0 || rollIndex >= 0) a.updateBoneMatrices()
    }

    fun setVisible(v: Boolean) {
        if (v == visible) return
        visible = v
        loaded.setAllVisible(v)
    }

    fun destroy() = loaded.destroy()
}

/** Cockpit der Lenkerperspektive ([CockpitModel]): Rahmen fest, Lenker schlaegt ein. */
internal class CockpitActor(val loaded: LoadedAsset) {
    private val barsEntity: Int = runCatching { loaded.asset.getFirstEntityByName(CockpitModel.NODE_BARS) }.getOrDefault(0)
    private var lastSteer = Double.NaN
    var visible = true
        private set

    init {
        loaded.applyShadows(true to true)
        loaded.addToScene()
    }

    fun place(matrix: FloatArray) = loaded.setRootTransform(matrix)

    fun steer(angleRad: Double) {
        if (barsEntity == 0 || kotlin.math.abs(angleRad - lastSteer) < 1e-4) return
        lastSteer = angleRad
        loaded.setEntityTransform(barsEntity, CockpitModel.steerMatrix(angleRad))
    }

    fun setVisible(v: Boolean) {
        if (v == visible) return
        visible = v
        loaded.setAllVisible(v)
    }

    fun destroy() = loaded.destroy()
}
