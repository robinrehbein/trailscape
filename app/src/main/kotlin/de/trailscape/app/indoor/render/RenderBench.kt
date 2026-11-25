package de.trailscape.app.indoor.render

/**
 * Ergebnis einer Bildzeitmessung (Diagnose „Bildrate je Stufe messen“).
 *
 * @property label Name der Stufe (Anzeige).
 * @property frames gezeichnete Bilder im Messfenster.
 * @property seconds Dauer des Messfensters.
 * @property meanMs mittlere Bildzeit (Abstand zweier gezeichneter Bilder).
 * @property low1Ms „1 % niedrig“: die Bildzeit, die nur 1 % der Bilder ueberschreiten (99. Perzentil).
 * @property gpuMeanMs mittlere GPU-Zeit je Bild (`Renderer.FrameInfo.gpuFrameDuration`), `null` = Treiber liefert keine.
 */
data class BenchResult(
    val label: String,
    val frames: Int,
    val seconds: Double,
    val meanMs: Double,
    val low1Ms: Double,
    val gpuMeanMs: Double?,
) {
    val meanFps: Double get() = if (meanMs > 0) 1000.0 / meanMs else 0.0
    val low1Fps: Double get() = if (low1Ms > 0) 1000.0 / low1Ms else 0.0
}

/**
 * Misst ueber [durationNs] (Vorgabe 30 s) die Abstaende der gezeichneten Bilder
 * und — wenn der Treiber sie liefert — die GPU-Zeit je Bild. Die Bildzeit ist
 * durch die Obergrenze der Bildrate (`maxFps`) und die Anzeige begrenzt; erst die
 * GPU-Zeit zeigt die Reserve. Nur Hauptthread. Reine Rechnung, getestet.
 */
class RenderBench(val label: String, private val durationNs: Long = 30_000_000_000L, private val warmupNs: Long = 3_000_000_000L) {
    private val frameMs = ArrayList<Double>(4096)
    private val gpuMs = ArrayList<Double>(4096)
    private var startNs = -1L
    private var lastNs = -1L
    private var measureStartNs = -1L

    var result: BenchResult? = null
        private set

    val done: Boolean get() = result != null

    /** Fortschritt 0..1 (inklusive Vorlauf). */
    fun progress(nowNs: Long): Double =
        if (startNs < 0) 0.0 else ((nowNs - startNs).toDouble() / (warmupNs + durationNs)).coerceIn(0.0, 1.0)

    /** Ein gezeichnetes Bild zum Zeitpunkt [frameTimeNs]; [gpuFrameMs] = GPU-Zeit eines kuerzlich fertigen Bilds oder `null`. */
    fun frame(frameTimeNs: Long, gpuFrameMs: Double? = null) {
        if (done) return
        if (startNs < 0) {
            startNs = frameTimeNs
            lastNs = frameTimeNs
            return
        }
        val inWarmup = frameTimeNs - startNs < warmupNs
        if (!inWarmup) {
            if (measureStartNs < 0) measureStartNs = lastNs
            val dt = (frameTimeNs - lastNs) / 1e6
            if (dt > 0 && dt < 2_000) frameMs += dt
            if (gpuFrameMs != null && gpuFrameMs > 0 && gpuFrameMs < 2_000) gpuMs += gpuFrameMs
        }
        lastNs = frameTimeNs
        if (measureStartNs >= 0 && frameTimeNs - measureStartNs >= durationNs) finish(frameTimeNs)
    }

    /** Eine Pause (Hintergrund) verfaelscht die Messung: neu anfangen. */
    fun reset() {
        frameMs.clear()
        gpuMs.clear()
        startNs = -1L
        lastNs = -1L
        measureStartNs = -1L
    }

    private fun finish(nowNs: Long) {
        val secs = (nowNs - measureStartNs) / 1e9
        result = BenchResult(
            label = label,
            frames = frameMs.size,
            seconds = secs,
            meanMs = if (frameMs.isEmpty()) 0.0 else frameMs.sum() / frameMs.size,
            low1Ms = FrameMeter.percentile(frameMs, 0.99),
            gpuMeanMs = if (gpuMs.size >= 10) gpuMs.sum() / gpuMs.size else null,
        )
    }
}

/**
 * GPU-Zeit des juengsten fertigen Bilds aus `Renderer.getFrameInfoHistory`
 * (Filament 1.75.1; `FrameInfo.INVALID`/`PENDING` = nicht verfuegbar), nur je
 * Bild einmal ([lastFrameId] merkt das zuletzt gelieferte).
 */
internal class GpuFrameTimes {
    private val history = Array(8) { com.google.android.filament.Renderer.FrameInfo() }
    private var lastFrameId = -1

    fun latestMs(renderer: com.google.android.filament.Renderer): Double? = runCatching {
        val n = renderer.getFrameInfoHistory(history)
        // Reihenfolge der Liste ist nicht zugesichert: das juengste Bild mit GPU-Zeit nehmen.
        var best: com.google.android.filament.Renderer.FrameInfo? = null
        for (i in 0 until n) {
            val f = history[i]
            if (f.gpuFrameDuration > 0 && f.frameId > lastFrameId && (best == null || f.frameId > best.frameId)) best = f
        }
        best?.let { f ->
            lastFrameId = f.frameId
            f.gpuFrameDuration / 1e6
        }
    }.getOrNull()
}
