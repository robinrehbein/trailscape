package de.trailscape.app.indoor.render

import android.util.Log
import de.trailscape.core.indoor.world.RenderSettings
import de.trailscape.core.indoor.world.WorldTrack

/**
 * # Leben in der Welt: andere Radfahrer und Voegel
 *
 * Zeichnet, was [OtherRiders] und [BirdFlock] ausrechnen. Rein dekorativ. Nur Hauptthread.
 *
 *  * **Radfahrer:** je Trikotfarbe ein eigenes Asset aus `rider.glb` ([RiderRecolor]),
 *    hoechstens eines je Bild nachgeladen (kein Ruckler beim Start). Ein Fahrer behaelt sein
 *    Asset, solange er lebt (keine Farbwechsel). Jenseits [VISIBLE_M] ausgeblendet, Schatten
 *    nur bis [SHADOW_M] (spart den Schattendurchgang fuer Ferne).
 *  * **Voegel:** ein Asset [BirdModel] mit sieben Voegeln; ausserhalb eines Fluges ist alles
 *    ausgeblendet (Ebenenmaske 0, also keine Draw Calls).
 */
internal class LifeLayer(
    private val core: FilamentCore,
    /** Bytes des Fahrermodells (Assets oder eingebaut); `null` = keine anderen Fahrer. */
    private val riderGlb: () -> ByteArray?,
) {
    private var traffic: OtherRiders? = null
    private var track: WorldTrack? = null
    private val actors = arrayOfNulls<AvatarActor>(OtherRiders.MAX_RIDERS)
    private val assigned = HashMap<Int, Int>() // Fahrer-Id → Asset
    private val shadowOn = BooleanArray(OtherRiders.MAX_RIDERS) { true }
    private var riderBytes: ByteArray? = null
    private var riderFailed = false

    private val flock = BirdFlock()
    private var birds: LoadedAsset? = null
    private var birdFailed = false
    private var birdRoots = IntArray(0)
    private var birdWings = Array(0) { IntArray(2) }
    private var birdsShown = -1

    /** Zahl der gerade gezeigten anderen Fahrer (Diagnose). */
    var shownRiders = 0
        private set

    /** Zahl der gerade fliegenden Voegel (Diagnose). */
    var shownBirds = 0
        private set

    fun setTrack(t: WorldTrack?) {
        if (t === track) return
        track = t
        traffic = t?.let { tr ->
            OtherRiders(
                seed = (tr.lengthM * 1000).toLong() xor 0x5DEECE66DL,
                lengthM = tr.lengthM,
                loop = tr.loop,
                gradePctAt = { d -> gradeAt(tr, d) },
                widthAt = { d -> widthAt(tr, d) },
            )
        }
        assigned.clear()
        for (a in actors) a?.setVisible(false)
    }

    /** Ein Bild. [offset]: schwebender Ursprung; [riderWorld]/[forward]: der Nutzer (Welt, glTF). */
    fun update(
        dt: Double,
        playerDistM: Double,
        playerSpeedMps: Double,
        playerLateralM: Double,
        riderWorld: V3,
        forward: V3,
        offset: V3,
        settings: RenderSettings,
        ridersEnabled: Boolean,
    ) {
        updateRiders(dt, playerDistM, playerSpeedMps, playerLateralM, offset, if (ridersEnabled) settings.otherRiders else 0)
        updateBirds(dt, riderWorld, forward, offset, settings.birds)
    }

    private fun updateRiders(dt: Double, playerDistM: Double, playerSpeedMps: Double, playerLateralM: Double, offset: V3, maxCount: Int) {
        val t = track
        val tr = traffic
        if (t == null || tr == null || maxCount <= 0) {
            if (shownRiders != 0 || assigned.isNotEmpty()) {
                for (a in actors) a?.setVisible(false)
                assigned.clear()
                shownRiders = 0
            }
            tr?.maxCount = 0
            return
        }
        tr.maxCount = maxCount
        // Hoechstens ein Asset je Bild nachladen.
        val need = minOf(maxCount, OtherRiders.MAX_RIDERS)
        val missing = (0 until need).firstOrNull { actors[it] == null }
        if (missing != null) loadActor(missing)

        val shown = tr.update(dt, playerDistM, playerSpeedMps, playerLateralM)
        val alive = shown.mapTo(HashSet()) { it.id }
        assigned.keys.retainAll(alive)
        val used = assigned.values.toHashSet()
        var visible = 0
        val drawn = BooleanArray(actors.size)
        for (s in shown) {
            val slot = assigned[s.id] ?: (0 until need).firstOrNull { it !in used && actors[it] != null }?.also {
                assigned[s.id] = it
                used += it
            } ?: continue
            val a = actors[slot] ?: continue
            val gap = kotlin.math.abs(s.distM - t.fold(playerDistM)).let { if (t.loop) minOf(it, t.lengthM - it) else it }
            if (gap > VISIBLE_M) continue
            val p = t.poseAt(s.distM)
            val heading = if (s.oncoming) (p.headingDeg + 180.0) % 360.0 else p.headingDeg
            val grade = if (s.oncoming) -p.gradePct else p.gradePct
            a.place(modelMatrix(p.lateral(s.lateralM) - offset, heading, grade))
            a.animate(s.crankPhase, s.wheelPhase)
            a.setVisible(true)
            val cast = gap < SHADOW_M
            if (cast != shadowOn[slot]) {
                a.loaded.applyShadows(cast to true)
                shadowOn[slot] = cast
            }
            drawn[slot] = true
            visible++
        }
        for (i in actors.indices) if (!drawn[i]) actors[i]?.setVisible(false)
        shownRiders = visible
    }

    private fun loadActor(slot: Int) {
        if (riderFailed) return
        val base = riderBytes ?: riderGlb().also { riderBytes = it } ?: run {
            riderFailed = true
            return
        }
        val jersey = RiderRecolor.JERSEYS[slot % RiderRecolor.JERSEYS.size]
        runCatching {
            val loaded = LoadedAsset.loadSync(core, RiderRecolor.recolor(base, jersey))
            try {
                AvatarActor(loaded).also { it.setVisible(false) }
            } catch (t: Throwable) {
                runCatching { loaded.destroy() }
                throw t
            }
        }.onSuccess { actors[slot] = it }
            .onFailure {
                Log.w(TAG, "Anderer Radfahrer nicht ladbar", it)
                riderFailed = true
            }
    }

    private fun updateBirds(dt: Double, riderWorld: V3, forward: V3, offset: V3, enabled: Boolean) {
        val list = if (enabled) flock.update(dt, riderWorld, forward) else emptyList()
        if (list.isNotEmpty() && birds == null && !birdFailed) loadBirds()
        val b = birds ?: return
        val tm = core.engine.transformManager
        for ((i, bird) in list.withIndex()) {
            if (i >= birdRoots.size) break
            tm.getInstance(birdRoots[i]).takeIf { it != 0 }?.let { tm.setTransform(it, BirdModel.birdMatrix(bird.position - offset, bird.yawRad)) }
            for (s in 0..1) {
                tm.getInstance(birdWings[i][s]).takeIf { it != 0 }?.let { tm.setTransform(it, BirdModel.wingMatrix(bird.flapRad, s == 0)) }
            }
        }
        val n = minOf(list.size, birdRoots.size)
        if (n != birdsShown) {
            val rm = core.engine.renderableManager
            for (i in birdRoots.indices) for (e in birdWings[i]) {
                val ri = rm.getInstance(e)
                if (ri != 0) rm.setLayerMask(ri, 0xFF, if (i < n) 0x01 else 0x00)
            }
            birdsShown = n
        }
        shownBirds = n
        if (b.inScene.not()) b.addToScene()
    }

    private fun loadBirds() {
        runCatching {
            val l = LoadedAsset.loadSync(core, BirdModel.buildGlb())
            l.applyShadows(false to false)
            val roots = IntArray(BirdFlock.MAX_BIRDS) { l.asset.getFirstEntityByName(BirdModel.node(it)) }
            val wings = Array(BirdFlock.MAX_BIRDS) { i ->
                intArrayOf(l.asset.getFirstEntityByName(BirdModel.wing(i, true)), l.asset.getFirstEntityByName(BirdModel.wing(i, false)))
            }
            birdRoots = roots
            birdWings = wings
            birdsShown = -1
            l
        }.onSuccess { birds = it }
            .onFailure {
                Log.w(TAG, "Voegel nicht ladbar", it)
                birdFailed = true
            }
    }

    fun destroy() {
        for (i in actors.indices) {
            actors[i]?.let { runCatching { it.destroy() } }
            actors[i] = null
        }
        assigned.clear()
        birds?.let { runCatching { it.destroy() } }
        birds = null
    }

    companion object {
        private const val TAG = "LifeLayer"
        const val VISIBLE_M = 260.0
        const val SHADOW_M = 60.0

        fun widthAt(t: WorldTrack, distM: Double): Double {
            val d = t.fold(distM)
            return t.surfaces.firstOrNull { d >= it.fromM && d <= it.toM }?.effectiveWidthM ?: 3.0
        }

        /** Steigung ueber ±15 m gemittelt (die 10-m-Stuetzpunkte springen). */
        fun gradeAt(t: WorldTrack, distM: Double): Double {
            val a = t.poseAt(distM - 15.0)
            val b = t.poseAt(distM + 15.0)
            return ((b.u - a.u) / 30.0 * 100.0).coerceIn(-25.0, 25.0)
        }
    }
}
