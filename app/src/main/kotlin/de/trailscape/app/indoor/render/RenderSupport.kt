package de.trailscape.app.indoor.render

import de.trailscape.core.indoor.world.RenderFailure
import de.trailscape.core.indoor.world.RenderSettings
import de.trailscape.core.indoor.world.WorldManifest
import de.trailscape.core.indoor.world.WorldNames

/**
 * Bildrate und Bildzeiten ueber ein Fenster von etwa einer Sekunde
 * (`RenderStats.fps`, `frameMsP95`).
 */
class FrameMeter(private val windowNs: Long = 1_000_000_000L) {
    private val frameMs = ArrayList<Double>(128)
    private var windowStartNs = -1L
    private var lastNs = -1L

    /**
     * Ein gezeichnetes Bild zum Zeitpunkt [frameTimeNs]. Liefert `(fps, p95)`,
     * sobald ein Fenster voll ist, sonst `null`.
     */
    fun frame(frameTimeNs: Long): Pair<Double, Double>? {
        if (windowStartNs < 0) {
            windowStartNs = frameTimeNs
            lastNs = frameTimeNs
            return null
        }
        val dt = (frameTimeNs - lastNs) / 1e6
        lastNs = frameTimeNs
        if (dt > 0 && dt < 5_000) frameMs += dt
        val span = frameTimeNs - windowStartNs
        if (span < windowNs) return null
        val fps = frameMs.size * 1e9 / span
        val p95 = percentile(frameMs, 0.95)
        frameMs.clear()
        windowStartNs = frameTimeNs
        return fps to p95
    }

    /** Nach einer Pause neu beginnen (sonst zaehlt die Pause als ein langes Bild). */
    fun reset() {
        frameMs.clear()
        windowStartNs = -1L
        lastNs = -1L
    }

    companion object {
        fun percentile(values: List<Double>, q: Double): Double {
            if (values.isEmpty()) return 0.0
            val s = values.sorted()
            val idx = ((s.size - 1) * q.coerceIn(0.0, 1.0)).let { kotlin.math.ceil(it).toInt() }
            return s[idx.coerceIn(0, s.size - 1)]
        }
    }
}

/**
 * Ordnet eine Ausnahme einer [RenderFailure] zu — fuer `onFailure`.
 * `UnsatisfiedLinkError` und Startfehler der Engine heissen „kein Grafik“,
 * Speicher ist Speicher, der Rest ist intern.
 */
fun classifyFailure(t: Throwable, stage: RenderStage): RenderFailure = when {
    t is OutOfMemoryError -> RenderFailure.OUT_OF_MEMORY
    t is UnsatisfiedLinkError || t is NoClassDefFoundError || t is ExceptionInInitializerError -> RenderFailure.NO_GRAPHICS
    stage == RenderStage.ENGINE -> RenderFailure.NO_GRAPHICS
    stage == RenderStage.MODELS -> RenderFailure.BAD_ASSETS
    stage == RenderStage.MANIFEST -> RenderFailure.BAD_WORLD
    stage == RenderStage.SURFACE -> RenderFailure.SURFACE_LOST
    else -> RenderFailure.INTERNAL
}

/** Stufen des Starts (docs/indoor.md 7.6: Engine → Testszene → Modelle → Manifest → Kacheln → bereit). */
enum class RenderStage(val label: String) {
    ENGINE("Engine starten"),
    SURFACE("Zeichenflaeche"),
    SCENE("Licht und Himmel"),
    MODELS("Modelle laden"),
    MANIFEST("Manifest pruefen"),
    TILES("Kacheln laden"),
    FRAME("Bild zeichnen"),
}

/** Deutscher Diagnosetext fuer `onFailure(…, detail)`: Stufe, Ausnahme, Nachricht — kein UI-Text. */
fun failureDetail(stage: RenderStage, t: Throwable?): String {
    val what = t?.let { "${it.javaClass.simpleName}: ${it.message ?: "ohne Meldung"}" } ?: "ohne Ausnahme"
    return "Darstellung, Stufe „${stage.label}“: $what"
}

/**
 * Prueft ein Manifest auf das, was die Darstellung zwingend braucht. Liefert
 * `null`, wenn es passt, sonst den Grund (deutsch, Diagnose).
 */
fun manifestProblem(m: WorldManifest): String? {
    val t = m.track
    return when {
        t.sampleCount < 2 -> "Streckenband mit ${t.sampleCount} Proben"
        t.n.size != t.sampleCount || t.u.size != t.sampleCount -> "Streckenband mit ungleich langen Reihen"
        !(t.lengthM > 0.0) || !t.lengthM.isFinite() -> "Streckenlaenge ${t.lengthM}"
        !(t.stepM > 0.0) || !t.stepM.isFinite() -> "Probenabstand ${t.stepM}"
        m.tiles.isEmpty() -> "Welt ohne Kacheln"
        m.tiles.any { it.file.isBlank() || it.file.contains("..") } -> "Kachel mit unzulaessigem Dateinamen"
        else -> null
    }
}

/**
 * Was eine Stufe aus [RenderSettings] fuer einen Knoten einer Kachel
 * bedeutet: sichtbar oder nicht (Schalter `trees`, `buildings`, `props`).
 */
fun nodeVisible(category: String?, settings: RenderSettings): Boolean = when (category) {
    WorldNames.NODE_TREES -> settings.trees
    WorldNames.NODE_BUILDINGS -> settings.buildings
    WorldNames.NODE_PROPS -> settings.props
    else -> true
}

/** Schatten je Knotenart (docs/indoor.md 7.3): `(wirft, empfaengt)`. */
fun shadowFlags(category: String?): Pair<Boolean, Boolean> = when (category) {
    WorldNames.NODE_BUILDINGS -> true to true
    WorldNames.NODE_TREES -> true to false
    WorldNames.NODE_PROPS -> false to false
    else -> false to true
}

/**
 * Die Knotenart eines Knotens anhand seines Namens und der Namen seiner
 * Vorfahren (vom Knoten zur Wurzel): der erste bekannte Name gewinnt.
 * Teilflaechen `terrain_sub_i_j` werden als solche gemeldet.
 */
fun nodeCategory(namesUpwards: List<String?>): String? {
    for (n in namesUpwards) {
        if (n == null) continue
        if (n.startsWith(WorldNames.NODE_TERRAIN_SUB_PREFIX)) return n
        when (n) {
            WorldNames.NODE_TERRAIN, WorldNames.NODE_ROUTE, WorldNames.NODE_ROADS, WorldNames.NODE_BUILDINGS,
            WorldNames.NODE_TREES, WorldNames.NODE_WATER, WorldNames.NODE_PROPS,
            -> return n
        }
    }
    return null
}

/** Grundkategorie einer Teilflaeche ist das Gelaende. */
fun baseCategory(category: String?): String? =
    if (category != null && category.startsWith(WorldNames.NODE_TERRAIN_SUB_PREFIX)) WorldNames.NODE_TERRAIN else category

/** Seitlicher Versatz auf dem Weg (Vertrag 7.5): rechts der Mitte, hoechstens halbe Breite minus 0,4 m. */
fun laneOffsetM(widthM: Double): Double {
    val w = if (widthM.isFinite() && widthM > 0) widthM else 3.0
    return minOf(w * 0.22, w / 2 - 0.4, 1.2).coerceAtLeast(0.0)
}
