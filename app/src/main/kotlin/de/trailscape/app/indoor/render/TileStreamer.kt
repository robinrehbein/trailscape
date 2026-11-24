package de.trailscape.app.indoor.render

import android.os.SystemClock
import de.trailscape.core.indoor.world.RenderSettings
import de.trailscape.core.indoor.world.WorldManifest
import de.trailscape.core.indoor.world.WorldTile
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executor

/**
 * # Kacheln laden und entladen
 *
 * Laeuft im Bildtakt auf dem Hauptthread (docs/indoor.md 7.6 „Laden“):
 *
 *  1. **Lesen** der GLB-Datei auf dem E/A-Thread in einen direkten Puffer
 *     (nie auf dem Hauptthread), hoechstens [MAX_READS] gleichzeitig.
 *  2. **Beginnen:** `AssetLoader.createAsset` + `ResourceLoader.asyncBeginLoad`
 *     — hoechstens eine Kachel je Bild und immer nur eine zugleich im
 *     asynchronen Laden (ein `ResourceLoader` fuehrt eine Ladung).
 *  3. **Fortsetzen:** `asyncUpdateLoad` je Bild; bei 100 % Quelldaten
 *     freigeben, unter den Weltknoten haengen, in die Szene.
 *  4. **Entladen** mit `destroyAsset`, sobald der Plan die Kachel im Fenster
 *     mit Hysterese nicht mehr braucht.
 *  5. **Teilflaechen** grober Kacheln aus, solange die feinere Kachel fertig
 *     und sichtbar ist; wird sie entladen, kommt die Teilflaeche im selben
 *     Bild zurueck — nie ein Loch.
 *
 * Eine Kachel, die sich nicht laden laesst, meldet [Callbacks.onTileFailed]
 * und bleibt fuer diesen Durchgang aussen vor; die Fahrt geht weiter.
 */
internal class TileStreamer(
    private val core: FilamentCore,
    private val worldDir: File,
    val manifest: WorldManifest,
    private val io: Executor,
    private val post: (Runnable) -> Unit,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        fun onTileFailed(tileId: String, detail: String)
    }

    private sealed class Slot {
        data object Reading : Slot()
        class Read(val buffer: ByteBuffer) : Slot()
        class Loading(val loaded: LoadedAsset, val startedMs: Long) : Slot()
        class Ready(val loaded: LoadedAsset) : Slot()
    }

    val planner = TilePlanner(manifest.tiles, manifest.track.lengthM, manifest.track.loop)
    private val slots = LinkedHashMap<String, Slot>()
    private val failed = HashSet<String>()
    private var loadingId: String? = null
    private var alive = true
    private var lastSettings: RenderSettings? = null
    private var visibilityDirty = true

    val readyCount: Int get() = slots.values.count { it is Slot.Ready }
    val pendingCount: Int get() = slots.values.count { it !is Slot.Ready }

    /** Texturspeicher und Dreiecke der fertigen Kacheln laut Manifest. */
    fun readyTextureBytes(): Long = slots.entries.filter { it.value is Slot.Ready }.sumOf { planner.tile(it.key)?.textureBytes ?: 0L }
    fun readyTriangles(): Int = slots.entries.filter { it.value is Slot.Ready }.sumOf { planner.tile(it.key)?.triangles ?: 0 }

    /** `(fertig oder gescheitert, gebraucht)` fuer die Stelle [distM]. */
    fun progressAt(distM: Double): Pair<Int, Int> {
        val needed = planner.neededNow(distM)
        val done = needed.count { slots[it.id] is Slot.Ready || it.id in failed }
        return done to needed.size
    }

    /** Ob an [distM] alles Gebrauchte fertig ist und wenigstens eine Kachel steht. */
    fun readyAt(distM: Double): Boolean {
        val needed = planner.neededNow(distM)
        if (needed.isEmpty()) return true
        return needed.all { slots[it.id] is Slot.Ready || it.id in failed } && needed.any { slots[it.id] is Slot.Ready }
    }

    /** Ob an [distM] alles Gebrauchte gescheitert ist (dann gibt es nichts zu zeigen). */
    fun allFailedAt(distM: Double): Boolean {
        val needed = planner.neededNow(distM)
        return needed.isNotEmpty() && needed.all { it.id in failed }
    }

    /** Ein Bildschritt. Wirft nur bei echten Programm- oder Treiberfehlern; Kachelfehler bleiben bei der Kachel. */
    fun update(distM: Double, settings: RenderSettings) {
        if (!alive) return
        if (settings != lastSettings) {
            lastSettings = settings
            visibilityDirty = true
        }
        continueLoading()
        if (loadingId == null) beginNext(distM)
        startReads(distM)
        unload(distM)
        if (visibilityDirty) applyVisibility(settings)
    }

    private fun continueLoading() {
        val id = loadingId ?: return
        val slot = slots[id] as? Slot.Loading ?: run {
            loadingId = null
            return
        }
        core.resourceLoader.asyncUpdateLoad()
        val progress = core.resourceLoader.asyncGetLoadProgress()
        if (progress >= 1f) {
            slot.loaded.asset.releaseSourceData()
            slot.loaded.applyShadows()
            slot.loaded.addToScene()
            slots[id] = Slot.Ready(slot.loaded)
            loadingId = null
            visibilityDirty = true
        } else if (SystemClock.elapsedRealtime() - slot.startedMs > LOAD_TIMEOUT_MS) {
            core.resetResourceLoader()
            slot.loaded.destroy()
            slots.remove(id)
            loadingId = null
            fail(id, "Laden dauerte laenger als ${LOAD_TIMEOUT_MS / 1000} s")
        }
    }

    private fun beginNext(distM: Double) {
        // Die wichtigste bereits gelesene Kachel zuerst.
        val next = planner.wanted(distM).firstOrNull { slots[it.id] is Slot.Read } ?: return
        val read = slots[next.id] as Slot.Read
        val asset = try {
            core.assetLoader.createAsset(read.buffer)
        } catch (e: OutOfMemoryError) {
            throw e
        } catch (e: Exception) {
            slots.remove(next.id)
            fail(next.id, "createAsset: ${e.javaClass.simpleName}: ${e.message}")
            return
        }
        if (asset == null) {
            slots.remove(next.id)
            fail(next.id, "gltfio konnte die Kachel nicht lesen (${read.buffer.capacity()} Bytes)")
            return
        }
        val loaded = LoadedAsset(core, asset)
        // Kachel unter den Weltknoten, an ihren Anker: (anchorE, anchorU, −anchorN).
        loaded.setParent(core.worldRoot)
        loaded.setRootTransform(translationMatrix(next.anchorE, next.anchorU, -next.anchorN))
        val started = try {
            core.resourceLoader.asyncBeginLoad(asset)
        } catch (e: Exception) {
            false
        }
        if (!started) {
            // asyncBeginLoad merkt sich das Asset schon vor dem Fehlschlag: Lader erneuern, bevor es verschwindet.
            core.resetResourceLoader()
            loaded.destroy()
            slots.remove(next.id)
            fail(next.id, "asyncBeginLoad lehnte ab")
            return
        }
        // createAsset hat die GLB kopiert; der Puffer darf jetzt frei werden.
        slots[next.id] = Slot.Loading(loaded, SystemClock.elapsedRealtime())
        loadingId = next.id
    }

    private fun startReads(distM: Double) {
        // Gelesene Puffer fuer Kacheln, die gerade nicht mehr gebraucht werden, verwerfen —
        // sie wuerden sonst die Plaetze fuer neue Lesevorgaenge belegen.
        val wantedIds = planner.wanted(distM).mapTo(HashSet()) { it.id }
        slots.entries.removeAll { (id, s) -> s is Slot.Read && id !in wantedIds }
        var reading = slots.values.count { it is Slot.Reading }
        if (reading >= MAX_READS) return
        // Nicht zu weit vorauslesen: hoechstens zwei gelesene Puffer warten.
        if (slots.values.count { it is Slot.Read } >= MAX_WAITING_BUFFERS) return
        for (t in planner.wanted(distM)) {
            if (reading >= MAX_READS) break
            if (t.id in slots || t.id in failed) continue
            slots[t.id] = Slot.Reading
            reading++
            val file = File(worldDir, t.file)
            io.execute {
                val result: Result<ByteBuffer> = try {
                    Result.success(readFile(file))
                } catch (e: Throwable) {
                    Result.failure(e)
                }
                post(Runnable { onRead(t, result) })
            }
        }
    }

    private fun onRead(tile: WorldTile, result: Result<ByteBuffer>) {
        if (!alive) return
        if (slots[tile.id] !is Slot.Reading) return // inzwischen entladen
        result.fold(
            onSuccess = { slots[tile.id] = Slot.Read(it) },
            onFailure = { e ->
                slots.remove(tile.id)
                fail(tile.id, "Datei ${tile.file}: ${e.javaClass.simpleName}: ${e.message}")
            },
        )
    }

    private fun unload(distM: Double) {
        val drop = planner.toUnload(distM, slots.keys.toList())
        for (id in drop) {
            when (val s = slots[id]) {
                is Slot.Ready -> s.loaded.destroy()
                is Slot.Loading -> {
                    core.resetResourceLoader()
                    s.loaded.destroy()
                    if (loadingId == id) loadingId = null
                }
                is Slot.Read, Slot.Reading, null -> Unit
            }
            slots.remove(id)
            visibilityDirty = true
        }
        // Gescheiterte, die nicht mehr gebraucht werden, duerfen spaeter neu versucht werden.
        failed.removeAll { id -> planner.tile(id)?.let { !planner.keep(it, distM) } ?: true }
    }

    private fun applyVisibility(settings: RenderSettings) {
        visibilityDirty = false
        val shown = slots.entries.filter { it.value is Slot.Ready }.mapTo(HashSet()) { it.key }
        for ((id, s) in slots) {
            if (s !is Slot.Ready) continue
            val tile = planner.tile(id) ?: continue
            val hidden = TilePlanner.hiddenSubs(tile, shown)
            // Wasser: eigenes Material (Spiegelung, Wellen), abschaltbar mit dem Schalter „Himmel"
            s.loaded.applyWater(if (settings.sky) core.ensureWaterInstance() else null)
            s.loaded.applyVisibility { cat ->
                when {
                    cat != null && cat in hidden -> false
                    else -> nodeVisible(cat, settings)
                }
            }
        }
    }

    private fun fail(id: String, detail: String) {
        failed += id
        callbacks.onTileFailed(id, detail)
    }

    /** Gibt alles frei (neue Welt, Ende). Danach verbraucht. */
    fun destroy() {
        alive = false
        if (loadingId != null) runCatching { core.resetResourceLoader() }
        loadingId = null
        for (s in slots.values) {
            when (s) {
                is Slot.Ready -> s.loaded.destroy()
                is Slot.Loading -> s.loaded.destroy()
                else -> Unit
            }
        }
        slots.clear()
    }

    companion object {
        const val MAX_READS = 2
        const val MAX_WAITING_BUFFERS = 2
        const val LOAD_TIMEOUT_MS = 45_000L

        /** Groesste Kachel, die wir lesen (Schutz gegen kaputte Dateien). */
        const val MAX_TILE_BYTES = 96L * 1024 * 1024

        fun readFile(file: File): ByteBuffer {
            RandomAccessFile(file, "r").use { raf ->
                val len = raf.length()
                require(len in 20..MAX_TILE_BYTES) { "Groesse $len Bytes" }
                val buf = ByteBuffer.allocateDirect(len.toInt()).order(ByteOrder.nativeOrder())
                val ch = raf.channel
                while (buf.hasRemaining()) {
                    if (ch.read(buf) < 0) break
                }
                require(!buf.hasRemaining()) { "Datei kuerzer als gemeldet" }
                buf.flip()
                return buf
            }
        }
    }
}
