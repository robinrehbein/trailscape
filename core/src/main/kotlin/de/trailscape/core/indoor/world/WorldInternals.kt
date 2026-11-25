package de.trailscape.core.indoor.world

import de.trailscape.core.indoor.SurfaceKind
import de.trailscape.core.indoor.SurfaceSection
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * # Gemeinsame Typen der Kachel-Erzeuger
 *
 * Der Weltbau setzt jede Kachel aus Schichten zusammen ([TileLayer]): Gelaende,
 * Weg, Strassen, Gebaeude, Vegetation, Wasser, Kleinteile. Alle Schichten
 * bekommen denselben [TileContext] (Boden, Strecke, Kartendaten, Texturen,
 * Budget) und schreiben ihre Netze in ein [TileOutput].
 *
 * Koordinaten: Welt-ENU in Metern (`e` Ost, `n` Nord, `u` oben, um den
 * Ursprung der Welt). In die GLB gehen sie relativ zum Kachelanker als
 * `x = e − anchorE`, `y = u − anchorU`, `z = −(n − anchorN)` — dafuer gibt es
 * [TileContext.x], [TileContext.y], [TileContext.z].
 */

// ---------------------------------------------------------------------------
// Kachel
// ---------------------------------------------------------------------------

/** Eine Kachel des festen Gitters (docs/indoor.md 7.2). */
internal data class TileSpec(val level: Int, val ix: Int, val iy: Int) {
    val cellM: Double get() = worldCellM(level)
    val minE: Double get() = ix * cellM
    val minN: Double get() = iy * cellM
    val maxE: Double get() = (ix + 1) * cellM
    val maxN: Double get() = (iy + 1) * cellM
    val anchorE: Double get() = (ix + 0.5) * cellM
    val anchorN: Double get() = (iy + 0.5) * cellM
    val id: String get() = worldTileId(level, ix, iy)

    fun contains(e: Double, n: Double): Boolean = e >= minE && e < maxE && n >= minN && n < maxN

    /** Abstand eines Punkts zum Kachelrechteck (0 innerhalb). */
    fun distanceTo(e: Double, n: Double): Double {
        val de = max(0.0, max(minE - e, e - maxE))
        val dn = max(0.0, max(minN - n, n - maxN))
        return hypot(de, dn)
    }

    companion object {
        fun at(level: Int, e: Double, n: Double): TileSpec {
            val c = worldCellM(level)
            return TileSpec(level, floor(e / c).toInt(), floor(n / c).toInt())
        }
    }
}

// ---------------------------------------------------------------------------
// Strecke
// ---------------------------------------------------------------------------

/** Treffer von [TrackLine.nearest]. */
internal class TrackHit(
    /** Laufmeter der naechsten Stelle der Mittellinie. */
    val distM: Double,
    /** Abstand zur Mittellinie in Metern (immer ≥ 0). */
    val distance: Double,
    /** Seitlicher Versatz: positiv = rechts in Fahrtrichtung. */
    val lateral: Double,
    /** Hoehe der Fahrbahn an der Stelle (ENU-`u`). */
    val u: Double,
)

/**
 * Die geglaettete Strecke, wie sie im Manifest steht ([WorldTrack]), mit
 * schneller Suche „naechster Punkt der Mittellinie". `u` ist die
 * Fahrbahnoberflaeche.
 */
internal class TrackLine(val track: WorldTrack) {
    companion object {
        /** Kuerzere Belagsabschnitte werden fuer die Darstellung dem laengeren Nachbarn zugeschlagen. */
        const val MIN_DRAWN_SECTION_M = 25.0

        /**
         * Die Strecke, wie der Weltbau sie zeichnet: Belagsabschnitte unter [MIN_DRAWN_SECTION_M]
         * gehen im laengeren Nachbarn auf. Geplante Routen (BRouter) haben an Kreuzungen oft
         * Wegstuecke von wenigen Metern mit anderem Belag oder anderer Wegart — gezeichnet als
         * brauner Fleck mit Fransen und Grasrand quer auf der Ortsstrasse. Das Manifest behaelt
         * die Abschnitte unveraendert (Rollwiderstand, Anzeige).
         */
        fun forDrawing(track: WorldTrack): TrackLine {
            val merged = drawnSections(track.surfaces)
            return TrackLine(if (merged == track.surfaces) track else track.copy(surfaces = merged))
        }

        internal fun drawnSections(input: List<SurfaceSection>): List<SurfaceSection> {
            if (input.size < 2) return input
            val list = input.toMutableList()
            while (list.size > 1) {
                var k = -1
                var shortest = MIN_DRAWN_SECTION_M
                for ((i, s) in list.withIndex()) {
                    val len = s.toM - s.fromM
                    if (len < shortest) { shortest = len; k = i }
                }
                if (k < 0) break
                val prev = list.getOrNull(k - 1)
                val next = list.getOrNull(k + 1)
                val into = when {
                    prev == null -> k + 1
                    next == null -> k - 1
                    prev.toM - prev.fromM >= next.toM - next.fromM -> k - 1
                    else -> k + 1
                }
                val target = list[into]
                list[into] = target.copy(fromM = minOf(target.fromM, list[k].fromM), toM = maxOf(target.toM, list[k].toM))
                list.removeAt(k)
                // Gleiche Nachbarn wieder zusammenlegen.
                var i = 1
                while (i < list.size) {
                    val a = list[i - 1]
                    val b = list[i]
                    if (a.kind == b.kind && a.highway == b.highway && a.widthM == b.widthM) {
                        list[i - 1] = a.copy(toM = b.toM)
                        list.removeAt(i)
                    } else {
                        i++
                    }
                }
            }
            return list
        }
    }

    val lengthM: Double get() = track.lengthM
    val stepM: Double get() = track.stepM
    val count: Int get() = track.sampleCount

    private val cell = 64.0
    private val grid = HashMap<Long, IntArray>()

    init {
        val tmp = HashMap<Long, MutableList<Int>>()
        for (i in 0 until count - 1) {
            val x0 = floor(min(track.e[i], track.e[i + 1]) / cell).toInt()
            val x1 = floor(max(track.e[i], track.e[i + 1]) / cell).toInt()
            val y0 = floor(min(track.n[i], track.n[i + 1]) / cell).toInt()
            val y1 = floor(max(track.n[i], track.n[i + 1]) / cell).toInt()
            for (x in x0..x1) for (y in y0..y1) tmp.getOrPut(key(x, y)) { ArrayList() }.add(i)
        }
        for ((k, v) in tmp) grid[k] = v.toIntArray()
    }

    private fun key(x: Int, y: Int): Long = (x.toLong() shl 32) xor (y.toLong() and 0xffffffffL)

    fun poseAt(distM: Double): TrackPose = track.poseAt(distM)

    /** Belagsabschnitt am Laufmeter. */
    fun surfaceAt(distM: Double): SurfaceSection {
        val s = track.surfaces
        var lo = 0
        var hi = s.size - 1
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (s[mid].toM <= distM) lo = mid + 1 else hi = mid
        }
        return s[lo]
    }

    fun kindAt(distM: Double): SurfaceKind = surfaceAt(distM).kind.visual

    /** Halbe Fahrbahnbreite am Laufmeter, an Belagswechseln ueber 20 m weich ueberblendet. */
    fun halfWidthAt(distM: Double): Double {
        val a = surfaceAt(distM - 10.0).effectiveWidthM
        val b = surfaceAt(distM + 10.0).effectiveWidthM
        if (a == b) return a / 2
        val here = surfaceAt(distM)
        val edge = if (here.effectiveWidthM == a) here.toM else here.fromM
        val t = ((distM - (edge - 10.0)) / 20.0).coerceIn(0.0, 1.0)
        val s = t * t * (3 - 2 * t)
        return (a + (b - a) * s) / 2
    }

    /**
     * Naechste Stelle der Mittellinie innerhalb von [maxDist] Metern, sonst
     * `null`. Kosten: wenige Segmente je Aufruf (Rastersuche).
     */
    fun nearest(e: Double, n: Double, maxDist: Double = 400.0): TrackHit? {
        var best = Double.MAX_VALUE
        var bestSeg = -1
        var bestT = 0.0
        val x0 = floor((e - maxDist) / cell).toInt()
        val x1 = floor((e + maxDist) / cell).toInt()
        val y0 = floor((n - maxDist) / cell).toInt()
        val y1 = floor((n + maxDist) / cell).toInt()
        for (x in x0..x1) for (y in y0..y1) {
            val segs = grid[key(x, y)] ?: continue
            for (i in segs) {
                val ax = track.e[i].toDouble()
                val ay = track.n[i].toDouble()
                val dx = track.e[i + 1] - ax
                val dy = track.n[i + 1] - ay
                val len2 = dx * dx + dy * dy
                val t = if (len2 > 0) (((e - ax) * dx + (n - ay) * dy) / len2).coerceIn(0.0, 1.0) else 0.0
                val px = ax + dx * t - e
                val py = ay + dy * t - n
                val d2 = px * px + py * py
                if (d2 < best) {
                    best = d2
                    bestSeg = i
                    bestT = t
                }
            }
        }
        if (bestSeg < 0) return null
        val d = sqrt(best)
        if (d > maxDist) return null
        val i = bestSeg
        val dx = (track.e[i + 1] - track.e[i]).toDouble()
        val dy = (track.n[i + 1] - track.n[i]).toDouble()
        val cx = track.e[i] + dx * bestT
        val cy = track.n[i] + dy * bestT
        // Kreuzprodukt Richtung × (Punkt − Linie): negativ = rechts der Fahrtrichtung.
        val cross = dx * (n - cy) - dy * (e - cx)
        val a = track.distAt(i)
        val b = track.distAt(i + 1)
        return TrackHit(
            distM = a + (b - a) * bestT,
            distance = d,
            lateral = if (cross <= 0) d else -d,
            u = track.u[i] + (track.u[i + 1] - track.u[i]) * bestT,
        )
    }

    /** Abstand zur Mittellinie, hoechstens [maxDist] (dann genau [maxDist]). */
    fun distanceTo(e: Double, n: Double, maxDist: Double = 400.0): Double = nearest(e, n, maxDist)?.distance ?: maxDist
}

// ---------------------------------------------------------------------------
// Boden und Landnutzung
// ---------------------------------------------------------------------------

/** Die **endgueltige** Gelaendeoberflaeche — fuer alle Schichten dieselbe. */
internal fun interface GroundSurface {
    /** Gelaendehoehe als ENU-`u` (Meter ueber dem Ursprung der Welt). */
    fun heightAt(e: Double, n: Double): Double
}

/** Hangneigung als Normalenvektor (glTF-Achsen: x = Ost, y = oben, z = −Nord), aus zentralen Differenzen. */
internal fun GroundSurface.normalAt(e: Double, n: Double, eps: Double = 1.0): Vec3 {
    val dE = (heightAt(e + eps, n) - heightAt(e - eps, n)) / (2 * eps)
    val dN = (heightAt(e, n + eps) - heightAt(e, n - eps)) / (2 * eps)
    return Vec3(-dE, 1.0, dN).normalized()
}

/** Grobe Landnutzung einer Stelle — steuert Bodentextur, Bewuchs und Raender. */
internal enum class LandClass {
    /** Wiese, Weide, Gruenland — auch der Rueckfall, wenn nichts bekannt ist. */
    MEADOW,
    FARMLAND,
    FOREST,

    /** Gebuesch, Heide, Brache. */
    SCRUB,

    /** Obstwiese (`orchard`): Gruenland mit Obstbaeumen in Reihen. */
    ORCHARD,

    /** Wohngebiet (Gaerten, Hoefe). */
    RESIDENTIAL,

    /** Gewerbe, Industrie, versiegelte Flaechen. */
    INDUSTRIAL,

    /** Park, Friedhof, Sportplatz — gepflegtes Gruen. */
    PARK,
    WATER,
    WETLAND,

    /** Fels, Sand, Abbau. */
    BARE,
}

/** Landnutzung und Bewuchs als abfragbare Karte (gerastert aus den Vektorkacheln, ggf. verfeinert aus dem Luftbild). */
internal interface LandMap {
    fun classAt(e: Double, n: Double): LandClass

    /**
     * Wald-Unterart einer Stelle in [LandClass.FOREST]: 0 = Laubwald,
     * 1 = Nadelwald, dazwischen Mischwald. Ausserhalb von Wald ohne Bedeutung.
     */
    fun coniferShare(e: Double, n: Double): Double = 0.3

    /**
     * Wahrscheinlichkeit 0..1, dass an der Stelle eine Baumkrone steht — aus
     * dem Luftbild geschaetzt, wo es eines gibt; sonst 1 in Wald und 0 ausserhalb.
     */
    fun canopyAt(e: Double, n: Double): Double = if (classAt(e, n) == LandClass.FOREST) 1.0 else 0.0

    /** Gemessene Kronenhoehe ueber Grund (Laserdaten), `NaN` ohne Messung. */
    fun canopyHeightAt(e: Double, n: Double): Double = Double.NaN

    /** Gemessene Einzelbaeume einer Kachel der Stufe 0 (Laserdaten), `null` ohne Messung. */
    fun laserTrees(spec: TileSpec): List<LaserTree>? = null
}

// ---------------------------------------------------------------------------
// Kartendaten (aus den Vektorkacheln, in ENU)
// ---------------------------------------------------------------------------

/**
 * Ein Gebaeudegrundriss. [rings]: Aussenring zuerst, dann Loecher; je Ring
 * `e0, n0, e1, n1, …` ohne wiederholten Schlusspunkt.
 */
internal class MapBuilding(
    val id: Long,
    val rings: List<DoubleArray>,
    /** Hoehe ueber Grund in Metern (`render_height`), 0 = unbekannt. */
    val heightM: Double,
    val minHeightM: Double = 0.0,
    /** Farbangabe aus den Daten (`#rrggbb`), falls vorhanden. */
    val colour: String? = null,
)

/** Eine Strasse oder ein Weg (OpenMapTiles `transportation`). [line]: `e0, n0, e1, n1, …`. */
internal class MapRoad(
    val line: DoubleArray,
    /** `motorway`, `trunk`, `primary`, `secondary`, `tertiary`, `minor`, `service`, `track`, `path`, `rail`, … */
    val cls: String,
    val subclass: String? = null,
    /** `paved` / `unpaved` / `null`. */
    val surface: String? = null,
    /** `bridge` / `tunnel` / `ford` / `null`. */
    val brunnel: String? = null,
    val layer: Int = 0,
    val service: String? = null,
)

/** Eine Flaeche (Landnutzung, Wald, Wasser). Ringe wie bei [MapBuilding]. */
internal class MapArea(
    val rings: List<DoubleArray>,
    val kind: LandClass,
    /** Roh-Klasse der Quelle, z. B. `wood`, `farmland`, `lake`, `river`, `cemetery`. */
    val sourceClass: String? = null,
)

/** Ein Wasserlauf als Linie (`river`, `stream`, `canal`, `ditch`). */
internal class MapWaterway(val line: DoubleArray, val cls: String, val brunnel: String? = null)

/** Kartendaten eines Gebiets. Jede Abfrage liefert alles, was das Rechteck schneidet (unbeschnitten). */
/** Ein benannter Ort der Karte (`place`: city, town, village, hamlet, suburb …). */
internal class MapPlace(val e: Double, val n: Double, val name: String, val cls: String)

/** Ein Punkt der POI-Ebene (`class`/`subclass` wie in OpenMapTiles, z. B. `bus`/`bus_stop`, `lift_gate`). */
internal class MapPoi(val e: Double, val n: Double, val cls: String, val subclass: String?, val name: String?)

internal interface MapData {
    /** Benannte Orte im Rechteck (fuer Ortstafeln); ohne Daten leer. */
    fun places(minE: Double, minN: Double, maxE: Double, maxN: Double): List<MapPlace> = emptyList()

    /** Punkte der POI-Ebene im Rechteck ([PoiLayer], Detailtreue); ohne Daten leer. */
    fun pois(minE: Double, minN: Double, maxE: Double, maxN: Double): List<MapPoi> = emptyList()

    /** Zusatzobjekte aus vollen OSM-Daten ([OsmDetail], nur Katalog-/Server-Bau); ohne Daten leer. */
    fun osmFeatures(minE: Double, minN: Double, maxE: Double, maxN: Double): List<OsmFeature> = emptyList()

    fun buildings(minE: Double, minN: Double, maxE: Double, maxN: Double): List<MapBuilding>
    fun roads(minE: Double, minN: Double, maxE: Double, maxN: Double): List<MapRoad>
    fun areas(minE: Double, minN: Double, maxE: Double, maxN: Double): List<MapArea>
    fun waterways(minE: Double, minN: Double, maxE: Double, maxN: Double): List<MapWaterway>

    companion object {
        val EMPTY: MapData = object : MapData {
            override fun buildings(minE: Double, minN: Double, maxE: Double, maxN: Double) = emptyList<MapBuilding>()
            override fun roads(minE: Double, minN: Double, maxE: Double, maxN: Double) = emptyList<MapRoad>()
            override fun areas(minE: Double, minN: Double, maxE: Double, maxN: Double) = emptyList<MapArea>()
            override fun waterways(minE: Double, minN: Double, maxE: Double, maxN: Double) = emptyList<MapWaterway>()
        }
    }
}

// ---------------------------------------------------------------------------
// Texturen
// ---------------------------------------------------------------------------

/** Eine fertig kodierte Textur. */
internal class EncodedTexture(val bytes: ByteArray, val mime: String, val width: Int, val height: Int) {
    val gpuBytes: Long get() = textureGpuBytes(width, height)
}

/**
 * Erzeugte Texturen einer Welt — jede wird je Welt genau einmal gerechnet und
 * kodiert, auch wenn hunderte Kacheln sie einbetten. Threadsicher.
 */
internal class TextureCache {
    private val map = HashMap<String, EncodedTexture>()

    /**
     * Liefert die Textur zu [key]; beim ersten Mal wird [make] gerufen und das
     * Bild kodiert: mit [alpha] als PNG (RGBA), sonst als Baseline-JPEG
     * ([jpegQuality]) bzw. als PNG, wenn [png] gesetzt ist. Kantenlaengen
     * muessen Zweierpotenzen ≤ 2048 sein.
     */
    fun get(
        key: String,
        alpha: Boolean = false,
        png: Boolean = false,
        jpegQuality: Int = 88,
        make: () -> RgbaImage,
    ): EncodedTexture = synchronized(map) {
        map.getOrPut(key) {
            val img = make()
            require(isPow2(img.width) && isPow2(img.height) && img.width <= 2048 && img.height <= 2048) {
                "Textur $key: ${img.width}×${img.height} ist keine Zweierpotenz ≤ 2048"
            }
            if (alpha || png) {
                EncodedTexture(encodePng(img, alpha), "image/png", img.width, img.height)
            } else {
                EncodedTexture(encodeJpegBaseline(img, jpegQuality), "image/jpeg", img.width, img.height)
            }
        }
    }

    private fun isPow2(v: Int) = v > 0 && (v and (v - 1)) == 0
}

/** Ein Material einer Kachel. Gleicher [name] = dasselbe Material (ein Netz je Knoten und Material). */
internal class TileMaterial(
    /** Name in der GLB: aus [WorldNames] oder mit `x_` beginnend. */
    val name: String,
    val texture: EncodedTexture? = null,
    /** `true` = kachelnd (REPEAT), `false` = CLAMP_TO_EDGE. */
    val repeat: Boolean = true,
    val baseColor: FloatArray = floatArrayOf(1f, 1f, 1f, 1f),
    /** Mindestens 0,6 (Vertrag). */
    val roughness: Float = 0.9f,
    val alphaMode: GlbAlphaMode = GlbAlphaMode.OPAQUE,
    val alphaCutoff: Float = 0.5f,
    val doubleSided: Boolean = false,
    /** Ohne Spiegelanteil (`KHR_materials_specular`, `specularFactor` 0) — Vegetation. */
    val noSpecular: Boolean = false,
    /** Mit [repeat]: nur `u` kachelt, `v` wird geklemmt (Deckung quer in der Textur, siehe [GlbScene.addTexture]). */
    val clampV: Boolean = false,
)

// ---------------------------------------------------------------------------
// Budget
// ---------------------------------------------------------------------------

/** Dreiecks-Obergrenzen **dieser** Kachel je Schicht (docs/indoor.md 7.4). */
internal data class TileBudget(
    val terrain: Int,
    val route: Int,
    val roads: Int,
    val buildings: Int,
    val vegetation: Int,
    val water: Int,
    val props: Int,
) {
    val total: Int get() = terrain + route + roads + buildings + vegetation + water + props

    companion object {
        /** Obergrenzen nach Qualitaet und Stufe; die Planung rechnet mit [total]. */
        fun of(quality: IndoorQuality, level: Int): TileBudget {
            val f = when (quality) {
                IndoorQuality.LOW -> 0.5
                IndoorQuality.MEDIUM -> 1.0
                IndoorQuality.HIGH -> 2.0
            }
            fun s(v: Int) = (v * f).toInt()
            return when (level) {
                0 -> TileBudget(s(9_000), s(3_000), s(2_000), s(8_000), s(20_500), s(2_000), s(3_000))
                1 -> TileBudget(s(5_000), 0, 0, s(3_000), s(3_700), s(600), 0)
                else -> TileBudget(s(2_500), 0, 0, 0, 0, 0, 0)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Kontext und Ausgabe
// ---------------------------------------------------------------------------

/**
 * Alles, was eine Schicht ueber ihre Kachel weiss.
 *
 * @property anchorU Hoehe des Kachelankers (ENU-`u`); Eckpunkte sind relativ dazu.
 * @property track die Strecke; `null` gibt es nicht — jede Welt hat eine.
 * @property aerial das dekodierte Luftbild **dieser** Kachel (Norden oben,
 *   deckt genau das Kachelrechteck), falls es eines gibt — zum Abgreifen von
 *   Farben (Dachfarbe, Laubton), nie zum Einbetten.
 * @property seed fester Startwert dieser Kachel fuer alles Zufaellige.
 */
internal class TileContext(
    val spec: TileSpec,
    val origin: GeoOrigin,
    val quality: IndoorQuality,
    val anchorU: Double,
    val ground: GroundSurface,
    val track: TrackLine,
    val map: MapData,
    val land: LandMap,
    val textures: TextureCache,
    val budget: TileBudget,
    val aerial: RgbaImage? = null,
    /** Das Luftbild dieser Kachel als Original-Bytes des Dienstes — die Bodentextur des Gelaendes ([WorldNames.MAT_TERRAIN], CLAMP). */
    val aerialTexture: EncodedTexture? = null,
    val seed: Int = Noise.seedOf(spec.id),
    /** Das Luftbild, wie der Dienst es liefert (ohne Waldboden-Abtoenung, [ForestFloor]) — fuer die Kronenschaetzung. */
    val aerialRaw: RgbaImage? = aerial,
) {
    val level: Int get() = spec.level

    /**
     * Hoehe des gezeichneten Gelaendenetzes dieser Kachel, wenn es kein
     * regelmaessiges Gitter ist (Laserdaten: [LaserTerrainMesh]); sonst `null`.
     */
    var meshHeight: ((Double, Double) -> Double)? = null

    /** Hoehe des gezeichneten Gelaendenetzes: [meshHeight] oder das Gitter der Stufe 0 ([terrainMeshHeightAt]). */
    fun meshHeightAt(e: Double, n: Double, cellM: Double): Double =
        meshHeight?.invoke(e, n) ?: terrainMeshHeightAt(ground, e, n, cellM)

    fun x(e: Double): Double = e - spec.anchorE
    fun y(u: Double): Double = u - anchorU
    fun z(n: Double): Double = -(n - spec.anchorN)

    /** Farbe des Luftbilds an einer Stelle der Kachel (sRGB, `0xFFrrggbb`), `null` ohne Luftbild oder ausserhalb. */
    fun aerialColorAt(e: Double, n: Double): Int? {
        val img = aerial ?: return null
        if (!spec.contains(e, n)) return null
        val px = ((e - spec.minE) / spec.cellM * img.width).toInt().coerceIn(0, img.width - 1)
        val py = ((spec.maxN - n) / spec.cellM * img.height).toInt().coerceIn(0, img.height - 1)
        return img[px, py] or (0xFF shl 24)
    }

    /** Mittlere Luftbildfarbe in einem Umkreis (linear gemittelt), `null` ohne Luftbild. */
    fun aerialMeanAt(e: Double, n: Double, radiusM: Double): Int? {
        val img = aerial ?: return null
        val mpp = spec.cellM / img.width
        val r = max(1, (radiusM / mpp).toInt())
        val cx = ((e - spec.minE) / mpp).toInt()
        val cy = ((spec.maxN - n) / mpp).toInt()
        var sr = 0.0
        var sg = 0.0
        var sb = 0.0
        var cnt = 0
        val step = max(1, r / 4)
        var y = cy - r
        while (y <= cy + r) {
            var x = cx - r
            while (x <= cx + r) {
                if (x >= 0 && y >= 0 && x < img.width && y < img.height) {
                    val c = img[x, y]
                    sr += Argb.SRGB_TO_LINEAR[Argb.red(c)]
                    sg += Argb.SRGB_TO_LINEAR[Argb.green(c)]
                    sb += Argb.SRGB_TO_LINEAR[Argb.blue(c)]
                    cnt++
                }
                x += step
            }
            y += step
        }
        if (cnt == 0) return null
        return Argb.of(255, Argb.linearToSrgb8((sr / cnt).toFloat()), Argb.linearToSrgb8((sg / cnt).toFloat()), Argb.linearToSrgb8((sb / cnt).toFloat()))
    }
}

/**
 * Sammelt die Netze einer Kachel: je Knoten ([WorldNames]) und Material ein
 * [MeshBuilder].
 *
 * **Ab Stufe 1** haengt jeder Inhalt an der Gelaende-Teilflaeche
 * `terrain_sub_i_j`, in der er liegt (docs/indoor.md 7.2): Die Darstellung
 * blendet die Teilflaeche aus, sobald die feinere Kachel gezeigt wird — damit
 * verschwinden Waldkoerper und grobe Gebaeude der Stufe 1 gemeinsam mit ihrem
 * Boden und stehen nie doppelt neben den Baeumen und Haeusern der Stufe 0.
 * Deshalb nimmt [mesh] immer eine Stelle (`e`, `n`) entgegen: In Stufe 0 ist
 * sie gleichgueltig, ab Stufe 1 waehlt sie die Teilflaeche. Ein Koerper, der
 * ueber eine Teilflaechen-Grenze ragt, gehoert ganz der Teilflaeche seines
 * Schwerpunkts.
 */
internal class TileOutput(val spec: TileSpec) {
    internal class Part(val node: String, val sub: Int, val material: TileMaterial, val mesh: MeshBuilder)

    private val parts = LinkedHashMap<String, Part>()

    /**
     * Das Netz fuer Knoten [node] und [material] an der Stelle (`e`, `n`).
     * Fuer das Gelaende selbst ([WorldNames.NODE_TERRAIN]) ab Stufe 1 ebenfalls
     * je Teilflaeche rufen.
     */
    fun mesh(node: String, material: TileMaterial, e: Double = spec.anchorE, n: Double = spec.anchorN): MeshBuilder {
        val sub = if (spec.level == 0) -1 else subIndex(e, n)
        val key = "$node|$sub|${material.name}"
        return parts.getOrPut(key) { Part(node, sub, material, MeshBuilder(256, 256)) }.mesh
    }

    /** Index der Teilflaeche `i·4 + j` (i nach Osten, j nach Norden) einer Stelle, auf die Kachel geklemmt. */
    fun subIndex(e: Double, n: Double): Int {
        val f = WORLD_LEVEL_FACTOR
        val i = floor((e - spec.minE) / spec.cellM * f).toInt().coerceIn(0, f - 1)
        val j = floor((n - spec.minN) / spec.cellM * f).toInt().coerceIn(0, f - 1)
        return i * f + j
    }

    /** Dreiecke aller Netze eines Knotens (ueber alle Teilflaechen). */
    fun triangles(node: String): Int = parts.values.filter { it.node == node }.sumOf { it.mesh.triangleCount }

    val triangleCount: Int get() = parts.values.sumOf { it.mesh.triangleCount }

    /** Schreibt die Kachel als GLB nach den Regeln aus docs/indoor.md 7.3. */
    fun toGlb(): GlbScene {
        val scene = GlbScene(compactUv = true)
        val materials = HashMap<String, Int>()
        fun materialIndex(m: TileMaterial): Int = materials.getOrPut(m.name) {
            val tex = m.texture?.let { scene.addTexture(it.bytes, it.mime, m.repeat, m.clampV) }
            scene.addMaterial(
                GlbMaterial(
                    name = m.name,
                    baseColor = m.baseColor,
                    texture = tex,
                    roughness = m.roughness,
                    alphaMode = m.alphaMode,
                    alphaCutoff = m.alphaCutoff,
                    doubleSided = m.doubleSided,
                    noSpecular = m.noSpecular,
                ),
            )
        }
        if (spec.level == 0) {
            for (name in NODE_ORDER) {
                val own = parts.values.filter { it.node == name && !it.mesh.isEmpty }
                if (own.isEmpty()) continue
                val node = scene.addNode(name)
                for (p in own) scene.addMesh(node, p.mesh, materialIndex(p.material))
            }
            // Zusatzknoten (x_…) in Einfuege-Reihenfolge.
            for (name in parts.values.map { it.node }.distinct().filter { it !in NODE_ORDER }) {
                val own = parts.values.filter { it.node == name && !it.mesh.isEmpty }
                if (own.isEmpty()) continue
                val node = scene.addNode(name)
                for (p in own) scene.addMesh(node, p.mesh, materialIndex(p.material))
            }
        } else {
            val terrain = scene.addNode(WorldNames.NODE_TERRAIN)
            val f = WORLD_LEVEL_FACTOR
            for (i in 0 until f) for (j in 0 until f) {
                val sub = scene.addNode(WorldNames.terrainSubName(i, j), parent = terrain)
                // Gelaende zuerst, dann der uebrige Inhalt der Teilflaeche.
                val own = parts.values.filter { it.sub == i * f + j && !it.mesh.isEmpty }
                    .sortedBy { if (it.node == WorldNames.NODE_TERRAIN) 0 else 1 }
                for (p in own) scene.addMesh(sub, p.mesh, materialIndex(p.material))
            }
        }
        return scene
    }

    private companion object {
        val NODE_ORDER = listOf(
            WorldNames.NODE_TERRAIN, WorldNames.NODE_ROUTE, WorldNames.NODE_ROADS, WorldNames.NODE_BUILDINGS,
            WorldNames.NODE_TREES, WorldNames.NODE_WATER, WorldNames.NODE_PROPS,
        )
    }
}

/** Eine Schicht des Kachelbaus. Zustandslos; alles Kachelbezogene steht im [TileContext]. */
internal fun interface TileLayer {
    fun build(ctx: TileContext, out: TileOutput)
}

/** Geglaettete Stufe: 0 unterhalb [a], 1 oberhalb [b]. */
internal fun smoothstep(a: Double, b: Double, x: Double): Double {
    if (a == b) return if (x < a) 0.0 else 1.0
    val t = ((x - a) / (b - a)).coerceIn(0.0, 1.0)
    return t * t * (3 - 2 * t)
}

/** Punkt-in-Polygon (gerade/ungerade ueber alle Ringe, also mit Loechern). */
internal fun pointInRings(rings: List<DoubleArray>, e: Double, n: Double): Boolean {
    var inside = false
    for (r in rings) {
        val cnt = r.size / 2
        var j = cnt - 1
        for (i in 0 until cnt) {
            val xi = r[2 * i]
            val yi = r[2 * i + 1]
            val xj = r[2 * j]
            val yj = r[2 * j + 1]
            if ((yi > n) != (yj > n) && e < (xj - xi) * (n - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
    }
    return inside
}

/** Vorzeichenflaeche eines Rings (positiv = gegen den Uhrzeigersinn in e/n). */
internal fun ringArea(r: DoubleArray): Double {
    var a = 0.0
    val cnt = r.size / 2
    var j = cnt - 1
    for (i in 0 until cnt) {
        a += r[2 * j] * r[2 * i + 1] - r[2 * i] * r[2 * j + 1]
        j = i
    }
    return a / 2
}

/** Abstand eines Punkts zu einem Linienzug `e0, n0, e1, n1, …`. */
internal fun distanceToLine(line: DoubleArray, e: Double, n: Double): Double {
    var best = Double.MAX_VALUE
    for (i in 0 until line.size / 2 - 1) {
        val ax = line[2 * i]
        val ay = line[2 * i + 1]
        val dx = line[2 * i + 2] - ax
        val dy = line[2 * i + 3] - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 > 0) (((e - ax) * dx + (n - ay) * dy) / len2).coerceIn(0.0, 1.0) else 0.0
        val d = hypot(ax + dx * t - e, ay + dy * t - n)
        if (d < best) best = d
    }
    return if (line.size >= 4) best else if (line.size >= 2) hypot(line[0] - e, line[1] - n) else Double.MAX_VALUE
}

/** `true`, wenn |a − b| ≤ eps. */
internal fun near(a: Double, b: Double, eps: Double = 1e-9): Boolean = abs(a - b) <= eps

/**
 * Begrenzter Zwischenspeicher (LRU) fuer abgeleitete Daten, die sich jederzeit
 * neu rechnen lassen (Landnutzungs-Bloecke, Strassenprofile). Ein
 * unbegrenzter Speicher wuchs mit der Streckenlaenge: Bei 40–100 km und
 * 256 MB Heap endete der Weltbau so gegen Ende im `OutOfMemoryError`.
 *
 * Gerechnet wird ausserhalb der Sperre; rechnen zwei Threads denselben
 * Schluessel, gewinnt der erste (die Werte sind deterministisch, also gleich).
 */
internal class BoundedCache<K : Any, V : Any>(private val capacity: Int) {
    private val map = object : LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > capacity
    }

    fun getOrPut(key: K, make: () -> V): V {
        synchronized(map) { map[key] }?.let { return it }
        val value = make()
        synchronized(map) {
            map[key]?.let { return it }
            map[key] = value
        }
        return value
    }

    val size: Int get() = synchronized(map) { map.size }
}
