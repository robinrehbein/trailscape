package de.trailscape.core.indoor.world

import java.io.File

/**
 * # Die Darstellung, von aussen gesehen
 *
 * VERTRAGSDATEI (docs/indoor.md, Abschnitt 7.6). Besitzer: Slice
 * indoor-render. Typen und Signaturen sind eingefroren.
 *
 * Die Schnittstelle ist bewusst android-frei und liegt in `:core`: Die
 * Sitzungs-Laufzeit und ihre Tests kennen nur sie, die Filament-Umsetzung
 * (`app/.../indoor/render/`) bleibt austauschbar und in Screenshot-Tests durch
 * einen Platzhalter ersetzt. Wie die Darstellung an eine Zeichenflaeche kommt,
 * ist Android-Sache (`WorldSurface` in `IndoorRenderEntry.kt`).
 *
 * ## Threads
 * Alle Methoden vom Hauptthread — ausser [setState], das von jedem Thread
 * kommen darf. Der [WorldRendererListener] wird auf dem Hauptthread gerufen.
 */

/**
 * Wo ein Fahrer ist.
 *
 * @property distM Laufmeter seit dem Start, ueber Runden fortlaufend — die
 *   Darstellung faltet selbst auf das Streckenband (`WorldTrack.poseAt`).
 * @property speedMps Tempo in m/s, fuer die Fortschreibung zwischen zwei Zustaenden und die Raddrehung.
 * @property cadenceRpm Trittfrequenz, fuer die Beine; 0 = die Beine stehen.
 */
data class ActorState(val distM: Double, val speedMps: Double, val cadenceRpm: Double)

/**
 * Ein Zustand der Fahrt fuer die Darstellung. Kommt mit 10–20 Hz; dazwischen
 * schreibt die Darstellung selbst fort: `distM + speedMps × (jetzt − timeMs)`,
 * hoechstens 500 ms weit, und gleicht Spruenge weich aus statt zu springen.
 *
 * @property timeMs Zeit des Zustands auf der monotonen Uhr
 *   (`SystemClock.elapsedRealtime()`) — dieselbe Uhr wie `IndoorSessionState.nowMs`.
 * @property paused die Fahrt steht (Pause): nichts fortschreiben, Beine und Raeder anhalten.
 */
data class WorldState(
    val timeMs: Long,
    val rider: ActorState,
    val ghost: ActorState? = null,
    val paused: Boolean = false,
)

/** Kameraeinstellung. */
enum class CameraMode {
    /** Hinter und ueber dem Fahrer, folgt weich. Die Voreinstellung. */
    CHASE,

    /** Blick ueber den Lenker. */
    HANDLEBAR,

    /** Hoch ueber der Strecke, schraeg von der Seite. */
    DRONE,
}

/**
 * Himmel und Licht.
 *
 * @property hourOfDay Ortszeit 0..24 (13,5 = 13:30 Uhr). Die Darstellung
 *   rechnet daraus mit [dayOfYear] und der Breite des Welt-Ursprungs den
 *   Sonnenstand. Nachts wird nicht dunkel gefahren: Unter 8° Sonnenhoehe
 *   bleibt das Licht auf dem Stand der tiefen Sonne.
 * @property dayOfYear Tag im Jahr 1..366.
 * @property haze Dunst 0..1 (0 = klare Fernsicht, 1 = dichter Dunst).
 */
data class SkySetting(val hourOfDay: Double, val dayOfYear: Int, val haze: Double = 0.3) {
    companion object {
        /** Ein Nachmittag im Fruehsommer — die Voreinstellung und der Stand der Screenshots. */
        val DEFAULT = SkySetting(hourOfDay = 15.5, dayOfYear = 160, haze = 0.3)
    }
}

/**
 * Was die Darstellung einschaltet. Jede Stufe ist **einzeln abschaltbar** —
 * die Diagnoseseite bietet genau diese Schalter an, damit sich auf einem
 * Geraet eingrenzen laesst, was ein Bild zerstoert oder Zeit kostet.
 *
 * @property resolutionScale feste Skalierung der Zeichenflaeche 0,5..1 (vor der dynamischen Aufloesung).
 * @property maxFps Obergrenze der Bildrate (30 oder 60).
 * @property groundDetail Boden-Detail nah am Fahrer und schaerfere Belaege (Software-Anisotropie).
 * @property wind Laub und Gras wiegen im Wind.
 * @property birds gelegentlich Voegel am Himmel.
 * @property otherRiders hoechstens so viele andere Radfahrer auf der Strecke (rein dekorativ; 0 = keine).
 */
data class RenderSettings(
    val shadows: Boolean,
    val ambientOcclusion: Boolean,
    val bloom: Boolean,
    val fog: Boolean,
    val antiAliasing: Boolean,
    val dynamicResolution: Boolean,
    val postProcessing: Boolean,
    val sky: Boolean,
    val trees: Boolean,
    val buildings: Boolean,
    val props: Boolean,
    val resolutionScale: Double,
    val maxFps: Int,
    val groundDetail: Boolean = true,
    val wind: Boolean = true,
    val birds: Boolean = true,
    val otherRiders: Int = 3,
) {
    companion object {
        /** Voreinstellung je Qualitaetsstufe. */
        fun forQuality(quality: IndoorQuality): RenderSettings = when (quality) {
            IndoorQuality.LOW -> RenderSettings(
                shadows = false, ambientOcclusion = false, bloom = false, fog = true,
                antiAliasing = true, dynamicResolution = true, postProcessing = true, sky = true,
                trees = true, buildings = true, props = false, resolutionScale = 0.75, maxFps = 30,
                groundDetail = false, wind = false, birds = true, otherRiders = 2,
            )
            IndoorQuality.MEDIUM -> RenderSettings(
                shadows = true, ambientOcclusion = false, bloom = false, fog = true,
                antiAliasing = true, dynamicResolution = true, postProcessing = true, sky = true,
                trees = true, buildings = true, props = true, resolutionScale = 1.0, maxFps = 60,
                groundDetail = true, wind = true, birds = true, otherRiders = 3,
            )
            IndoorQuality.HIGH -> RenderSettings(
                shadows = true, ambientOcclusion = true, bloom = true, fog = true,
                antiAliasing = true, dynamicResolution = true, postProcessing = true, sky = true,
                trees = true, buildings = true, props = true, resolutionScale = 1.0, maxFps = 60,
                groundDetail = true, wind = true, birds = true, otherRiders = 5,
            )
        }

        /** Alles aus, was sich abschalten laesst — der Rettungsanker der Diagnoseseite. */
        val SAFE = RenderSettings(
            shadows = false, ambientOcclusion = false, bloom = false, fog = false,
            antiAliasing = false, dynamicResolution = false, postProcessing = false, sky = false,
            trees = false, buildings = true, props = false, resolutionScale = 0.75, maxFps = 30,
            groundDetail = false, wind = false, birds = false, otherRiders = 0,
        )
    }
}

/**
 * Messwerte der Darstellung, etwa einmal je Sekunde.
 *
 * @property fps Bilder je Sekunde (Mittel der letzten Sekunde).
 * @property frameMsP95 95-%-Wert der Bildzeit in ms ueber die letzte Sekunde.
 * @property resolutionScale wirksame Skalierung samt dynamischer Aufloesung.
 * @property textureBytes Texturspeicher der geladenen Kacheln laut Manifest.
 */
data class RenderStats(
    val fps: Double,
    val frameMsP95: Double,
    val resolutionScale: Double,
    val loadedTiles: Int,
    val pendingTiles: Int,
    val textureBytes: Long,
    val triangles: Int,
)

/**
 * Was ueber Geraet und Grafiktreiber bekannt ist — fuer die Diagnoseseite und
 * Fehlerberichte. Unbekanntes ist ein leerer String.
 */
data class RenderDeviceInfo(
    val deviceModel: String,
    val androidVersion: String,
    val backend: String,
    val glVendor: String,
    val glRenderer: String,
    val glVersion: String,
    val filamentVersion: String,
)

/** Warum die Darstellung nichts zeigen kann. */
enum class RenderFailure {
    /** Das Geraet kann kein OpenGL ES 3.0 oder Filament laesst sich nicht starten. */
    NO_GRAPHICS,

    /** Manifest fehlt, ist unlesbar oder hat ein fremdes Format. */
    BAD_WORLD,

    /** Die mitgelieferten Modelle (Fahrer) fehlen oder sind unlesbar. */
    BAD_ASSETS,

    /** Der Grafikspeicher reicht nicht. */
    OUT_OF_MEMORY,

    /** Die Zeichenflaeche ging verloren und liess sich nicht wiederherstellen. */
    SURFACE_LOST,

    /** Alles andere. */
    INTERNAL,
}

/** Ereignisse der Darstellung — alle auf dem Hauptthread. */
interface WorldRendererListener {
    /** Die Welt (oder Testszene) ist so weit geladen, dass der Fahrer auf Gelaende steht. */
    fun onReady(info: RenderDeviceInfo)

    /** Ladefortschritt der fuer die aktuelle Stelle noetigen Kacheln. */
    fun onLoading(loadedTiles: Int, neededTiles: Int)

    /** Eine einzelne Kachel liess sich nicht laden — die Fahrt geht weiter, dort fehlt ein Stueck. */
    fun onTileFailed(tileId: String, detail: String)

    /**
     * Nichts geht mehr. Die Oberflaeche zeigt eine Meldung **statt** der
     * 3D-Flaeche und bietet die Fahrt ohne Welt an (HUD laeuft weiter).
     *
     * @param detail deutscher Diagnosetext fuer den Fehlerbericht, kein UI-Text.
     */
    fun onFailure(failure: RenderFailure, detail: String)

    fun onStats(stats: RenderStats)
}

/**
 * Die 3D-Darstellung einer Welt.
 *
 * ## Vertrag
 *  * Jede Methode ist in jedem Zustand erlaubt und wirft nie; was gerade nicht
 *    geht, wird gemerkt (Einstellungen) oder ueber
 *    [WorldRendererListener.onFailure] gemeldet.
 *  * [loadWorld] ersetzt eine geladene Welt. Geladen wird im Hintergrund;
 *    Kacheln kommen und gehen nach `WorldTile.show` und der Stelle des Fahrers
 *    aus [setState].
 *  * Ohne [setState] steht der Fahrer am Start.
 *  * [setSettings], [setCamera], [setSky] wirken sofort, auch vor [loadWorld].
 *  * [pause] haelt die Zeichenschleife an (App im Hintergrund), [resume] setzt
 *    sie fort. [destroy] gibt alles frei; danach ist das Objekt verbraucht.
 */
interface WorldRenderer {
    fun setListener(listener: WorldRendererListener?)

    /** Laedt die Welt aus [worldDir] (Verzeichnis mit `manifest.json`). */
    fun loadWorld(worldDir: File, manifest: WorldManifest)

    /**
     * Zeigt die eingebaute Testszene, die **keine** Dateien braucht (kein
     * Weltbau, keine Geodaten): ein Stueck Boden, der Fahrer, Licht. Wenn
     * selbst das nichts zeigt, liegt es an Geraet oder Treiber.
     */
    fun loadTestScene()

    fun unloadWorld()

    fun setState(state: WorldState)

    fun setCamera(mode: CameraMode)

    fun setSky(sky: SkySetting)

    /**
     * Andere Radfahrer anzeigen (Einstellung des Nutzers, Vorgabe an). Die Zahl begrenzt
     * [RenderSettings.otherRiders]; rein dekorativ, ohne Einfluss auf Physik oder Windschatten.
     */
    fun setOtherRidersEnabled(enabled: Boolean) {}

    fun setSettings(settings: RenderSettings)

    fun pause()

    fun resume()

    fun destroy()
}
