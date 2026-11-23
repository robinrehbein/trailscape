package de.trailscape.core.indoor.world

import de.trailscape.core.indoor.IndoorRoute
import de.trailscape.core.indoor.IndoorRoutePoint
import de.trailscape.core.indoor.IndoorRouteSource
import de.trailscape.core.indoor.SurfaceKind
import de.trailscape.core.indoor.SurfaceSection
import de.trailscape.core.optionalBoolean
import de.trailscape.core.optionalDouble
import de.trailscape.core.optionalInt
import de.trailscape.core.optionalLong
import de.trailscape.core.optionalString
import de.trailscape.core.requiredArray
import de.trailscape.core.requiredDouble
import de.trailscape.core.requiredInt
import de.trailscape.core.requiredObject
import de.trailscape.core.requiredString
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

/**
 * # Das Welt-Manifest — die Schnittstelle zwischen Weltbau und Darstellung
 *
 * VERTRAGSDATEI (docs/indoor.md, Abschnitt 7). Besitzer: Slice indoor-world.
 * Feldnamen, Einheiten und das JSON-Format (Version [WorldManifest.FORMAT]) sind
 * eingefroren; das Format ist hier **ausprogrammiert**, damit Weltbau,
 * Darstellung und der three.js-Betrachter dieselbe Datei lesen.
 *
 * Eine gebaute Welt ist ein Verzeichnis:
 * ```
 *  <welt>/manifest.json          dieses Manifest
 *  <welt>/tiles/L0_3_-2.glb      je Kachel eine GLB-Datei (glTF 2.0 binaer)
 * ```
 *
 * ## Koordinaten
 * Lokales **ENU** in Metern um [GeoOrigin]: `e` nach Osten, `n` nach Norden,
 * `u` nach oben, `u = Hoehe ueber dem Meer − origin.eleM`. Die Abbildung ist
 * die ebene Naeherung in [GeoOrigin.toEast]/[GeoOrigin.toNorth] — **jeder**
 * Baustein (Hoehenkacheln, Vektorkacheln, Luftbilder, Strecke) geht durch
 * genau diese Funktionen, dadurch passt alles aufeinander, auch wenn die
 * Naeherung in 50 km Abstand um etwa ein Prozent verzerrt.
 *
 * In den GLB-Dateien gilt die glTF-Konvention (rechtshaendig, Y oben):
 * `X = e`, `Y = u`, `Z = −n`. Die Eckpunkte einer Kachel sind **relativ zu
 * ihrem [WorldTile.anchor]** gespeichert (kleine Zahlen, volle
 * Float-Genauigkeit); die Darstellung setzt die Kachel mit der Verschiebung
 * `(anchorE, anchorU, −anchorN)` in die Welt.
 */
data class WorldManifest(
    val worldId: String,
    /** Kennung und Version des Weltbaus, z. B. `indoor-world/1` — Teil der Welt-ID. */
    val builder: String,
    /** Wanduhrzeit der Fertigstellung, ms seit Epoch. */
    val createdAtMs: Long,
    val quality: IndoorQuality,
    val routeId: String,
    val routeName: String,
    val origin: GeoOrigin,
    val bounds: WorldBounds,
    val track: WorldTrack,
    val levels: List<WorldLevel>,
    val tiles: List<WorldTile>,
    val budget: WorldBudget,
    val attribution: List<WorldAttribution>,
) {
    fun toJson(): String = buildJsonObject {
        put("format", FORMAT)
        put("worldId", worldId)
        put("builder", builder)
        put("createdAtMs", createdAtMs)
        put("quality", quality.name)
        put("routeId", routeId)
        put("routeName", routeName)
        put("origin", origin.toJson())
        put("bounds", bounds.toJson())
        put("track", track.toJson())
        put("levels", buildJsonArray { levels.forEach { add(it.toJson()) } })
        put("tiles", buildJsonArray { tiles.forEach { add(it.toJson()) } })
        put("budget", budget.toJson())
        put("attribution", buildJsonArray { attribution.forEach { add(it.toJson()) } })
    }.toString()

    companion object {
        /** Version des JSON-Formats. Eine Darstellung lehnt hoehere Versionen ab. */
        const val FORMAT: Int = 1

        const val FILE_NAME: String = "manifest.json"

        /** Unterverzeichnis der Kachel-GLBs. */
        const val TILES_DIR: String = "tiles"

        /**
         * Liest ein Manifest. Wirft [IllegalArgumentException] mit deutschem
         * Diagnosetext bei kaputtem JSON, fehlenden Pflichtfeldern oder einer
         * unbekannten Formatversion — die Darstellung zeigt dann eine Meldung
         * statt eines schwarzen Bilds.
         */
        fun parse(text: String): WorldManifest = try {
            val o = Json.parseToJsonElement(text) as? JsonObject
                ?: throw IllegalArgumentException("Welt-Manifest: kein JSON-Objekt")
            val format = o.requiredInt("format")
            require(format == FORMAT) { "Welt-Manifest: Format $format wird nicht unterstuetzt (erwartet $FORMAT)" }
            WorldManifest(
                worldId = o.requiredString("worldId"),
                builder = o.optionalString("builder") ?: "",
                createdAtMs = o.optionalLong("createdAtMs") ?: 0L,
                quality = IndoorQuality.fromNameOrNull(o.optionalString("quality")) ?: IndoorQuality.MEDIUM,
                routeId = o.requiredString("routeId"),
                routeName = o.optionalString("routeName") ?: "",
                origin = GeoOrigin.fromJson(o.requiredObject("origin")),
                bounds = WorldBounds.fromJson(o.requiredObject("bounds")),
                track = WorldTrack.fromJson(o.requiredObject("track")),
                levels = o.requiredArray("levels").map { WorldLevel.fromJson(it as JsonObject) },
                tiles = o.requiredArray("tiles").map { WorldTile.fromJson(it as JsonObject) },
                budget = WorldBudget.fromJson(o.requiredObject("budget")),
                attribution = (o["attribution"] as? JsonArray).orEmpty()
                    .mapNotNull { (it as? JsonObject)?.let(WorldAttribution::fromJson) },
            )
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: Exception) {
            throw IllegalArgumentException("Welt-Manifest unlesbar: ${e.message}", e)
        }
    }
}

/** Mittlerer Erdradius der ENU-Naeherung in Metern — derselbe Wert wie `haversineM` in `Stats.kt`. */
const val WORLD_EARTH_RADIUS_M: Double = 6_371_000.0

/**
 * Ursprung des lokalen Koordinatensystems: die Mitte des Umgebungsrechtecks
 * der Strecke, [eleM] die Hoehe des Streckenstarts.
 */
data class GeoOrigin(val lat: Double, val lon: Double, val eleM: Double) {
    /** Ostwert in Metern: `Δlon · cos(lat0) · R`. Linear in der Laenge. */
    fun toEast(lon: Double): Double = Math.toRadians(lon - this.lon) * cos(Math.toRadians(lat)) * WORLD_EARTH_RADIUS_M

    /** Nordwert in Metern: `Δlat · R`. Linear in der Breite. */
    fun toNorth(lat: Double): Double = Math.toRadians(lat - this.lat) * WORLD_EARTH_RADIUS_M

    /** Hoehe ueber dem Ursprung. */
    fun toUp(eleM: Double): Double = eleM - this.eleM

    fun toLon(east: Double): Double = lon + Math.toDegrees(east / (cos(Math.toRadians(lat)) * WORLD_EARTH_RADIUS_M))

    fun toLat(north: Double): Double = lat + Math.toDegrees(north / WORLD_EARTH_RADIUS_M)

    fun toJson(): JsonObject = buildJsonObject {
        put("lat", lat)
        put("lon", lon)
        put("eleM", eleM)
    }

    companion object {
        fun fromJson(o: JsonObject) = GeoOrigin(o.requiredDouble("lat"), o.requiredDouble("lon"), o.requiredDouble("eleM"))
    }
}

/** Umgebungsquader aller Kacheln in ENU-Metern. */
data class WorldBounds(
    val minE: Double,
    val minN: Double,
    val maxE: Double,
    val maxN: Double,
    val minU: Double,
    val maxU: Double,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("minE", minE)
        put("minN", minN)
        put("maxE", maxE)
        put("maxN", maxN)
        put("minU", minU)
        put("maxU", maxU)
    }

    companion object {
        fun fromJson(o: JsonObject) = WorldBounds(
            o.requiredDouble("minE"), o.requiredDouble("minN"), o.requiredDouble("maxE"),
            o.requiredDouble("maxN"), o.requiredDouble("minU"), o.requiredDouble("maxU"),
        )
    }
}

/** Lage und Ausrichtung auf dem Streckenband. */
data class TrackPose(
    val e: Double,
    val n: Double,
    val u: Double,
    /** Fahrtrichtung, 0 = Nord, 90 = Ost. */
    val headingDeg: Double,
    /** Steigung in Prozent, positiv = bergauf. */
    val gradePct: Double,
)

/**
 * Das Streckenband: die Mittellinie des befahrenen Wegs **in der Welt**.
 *
 * Probe `i` liegt bei Laufmeter `min(i · stepM, lengthM)`; die letzte Probe
 * liegt genau bei [lengthM]. [u] ist die Hoehe der **gezeichneten
 * Fahrbahnoberflaeche** — der Weltbau legt die Strecke ins Gelaende und
 * glaettet sie, und genau diese Hoehe faehrt und spuert man
 * ([toIndoorRoute]). Die Laufmeter sind die der eingegebenen `IndoorRoute`.
 *
 * @property surfaces Belag je Abschnitt, mit aufgeloesten `UNKNOWN`-Stellen,
 *   wo die Vektorkacheln etwas hergaben. Lueckenlos von 0 bis [lengthM].
 */
data class WorldTrack(
    val lengthM: Double,
    val stepM: Double,
    val loop: Boolean,
    val e: FloatArray,
    val n: FloatArray,
    val u: FloatArray,
    val surfaces: List<SurfaceSection>,
    val source: IndoorRouteSource,
) {
    val sampleCount: Int get() = e.size

    /** Laufmeter der Probe [i]. */
    fun distAt(i: Int): Double = minOf(i * stepM, lengthM)

    /**
     * Lage auf dem Band bei [distM], linear zwischen den Proben. Auf einer
     * Runde modulo [lengthM], sonst auf `0 .. lengthM` begrenzt. Die
     * Darstellung darf zusaetzlich glaetten (Kamera), muss aber durch diese
     * Punkte fahren.
     */
    fun poseAt(distM: Double): TrackPose {
        val d = when {
            !distM.isFinite() -> 0.0
            loop && lengthM > 0 -> ((distM % lengthM) + lengthM) % lengthM
            else -> distM.coerceIn(0.0, lengthM)
        }
        val last = sampleCount - 1
        val i = (d / stepM).toInt().coerceIn(0, maxOf(0, last - 1))
        val j = minOf(i + 1, last)
        val span = distAt(j) - distAt(i)
        val t = if (span > 0) ((d - distAt(i)) / span).coerceIn(0.0, 1.0) else 0.0
        val de = (e[j] - e[i]).toDouble()
        val dn = (n[j] - n[i]).toDouble()
        val du = (u[j] - u[i]).toDouble()
        return TrackPose(
            e = e[i] + de * t,
            n = n[i] + dn * t,
            u = u[i] + du * t,
            headingDeg = (Math.toDegrees(atan2(de, dn)) + 360.0) % 360.0,
            gradePct = if (span > 0) du / span * 100.0 else 0.0,
        )
    }

    /**
     * Das Band als `IndoorRoute` — die Strecke, mit der die Sitzung faehrt,
     * sobald eine Welt geladen ist (gleiche Laufmeter, Hoehen der Fahrbahn).
     */
    fun toIndoorRoute(origin: GeoOrigin, id: String, name: String): IndoorRoute = IndoorRoute(
        id = id,
        name = name,
        points = List(sampleCount) { i ->
            IndoorRoutePoint(
                distM = distAt(i),
                lat = origin.toLat(n[i].toDouble()),
                lon = origin.toLon(e[i].toDouble()),
                eleM = origin.eleM + u[i],
            )
        }.distinctBy { it.distM },
        surfaces = surfaces,
        isLoop = loop,
        source = source,
    )

    fun toJson(): JsonObject = buildJsonObject {
        put("lengthM", lengthM)
        put("stepM", stepM)
        put("loop", loop)
        put("e", floatsToJson(e))
        put("n", floatsToJson(n))
        put("u", floatsToJson(u))
        put(
            "surfaces",
            buildJsonArray {
                surfaces.forEach { s ->
                    add(
                        buildJsonObject {
                            put("fromM", s.fromM)
                            put("toM", s.toM)
                            put("kind", s.kind.name)
                            s.highway?.let { put("highway", it) }
                            s.widthM?.let { put("widthM", it) }
                        },
                    )
                }
            },
        )
        put("source", source.name)
    }

    // FloatArray-Felder: Gleichheit ueber den Inhalt, nicht ueber die Referenz.
    override fun equals(other: Any?): Boolean =
        other is WorldTrack && lengthM == other.lengthM && stepM == other.stepM && loop == other.loop &&
            e.contentEquals(other.e) && n.contentEquals(other.n) && u.contentEquals(other.u) &&
            surfaces == other.surfaces && source == other.source

    override fun hashCode(): Int =
        listOf(lengthM, stepM, loop, e.contentHashCode(), n.contentHashCode(), u.contentHashCode(), surfaces, source).hashCode()

    companion object {
        /** Ueblicher Probenabstand des Bands in Metern. */
        const val DEFAULT_STEP_M: Double = 5.0

        fun fromJson(o: JsonObject): WorldTrack {
            val e = floatsFromJson(o.requiredArray("e"))
            val n = floatsFromJson(o.requiredArray("n"))
            val u = floatsFromJson(o.requiredArray("u"))
            require(e.size >= 2 && e.size == n.size && e.size == u.size) {
                "Welt-Manifest: Streckenband mit ${e.size}/${n.size}/${u.size} Proben"
            }
            val lengthM = o.requiredDouble("lengthM")
            val surfaces = o.requiredArray("surfaces").map {
                val s = it as JsonObject
                SurfaceSection(
                    fromM = s.requiredDouble("fromM"),
                    toM = s.requiredDouble("toM"),
                    kind = SurfaceKind.fromNameOrNull(s.optionalString("kind")) ?: SurfaceKind.UNKNOWN,
                    highway = s.optionalString("highway"),
                    widthM = s.optionalDouble("widthM"),
                )
            }.ifEmpty { listOf(SurfaceSection(0.0, lengthM, SurfaceKind.UNKNOWN)) }
            return WorldTrack(
                lengthM = lengthM,
                stepM = o.requiredDouble("stepM"),
                loop = o.optionalBoolean("loop") ?: false,
                e = e,
                n = n,
                u = u,
                surfaces = surfaces,
                source = o.optionalString("source")
                    ?.let { name -> IndoorRouteSource.entries.firstOrNull { it.name == name } }
                    ?: IndoorRouteSource.NONE,
            )
        }
    }
}

/**
 * Eine Detailstufe. Stufe 0 ist die feinste; jede weitere hat die vierfache
 * Kantenlaenge ([WORLD_LEVEL_FACTOR]).
 *
 * @property cellM Kantenlaenge einer Kachel in Metern.
 * @property texturePx Kantenlaenge der Bodentextur einer Kachel in Pixeln.
 */
data class WorldLevel(val level: Int, val cellM: Double, val texturePx: Int) {
    fun toJson(): JsonObject = buildJsonObject {
        put("level", level)
        put("cellM", cellM)
        put("texturePx", texturePx)
    }

    companion object {
        fun fromJson(o: JsonObject) = WorldLevel(o.requiredInt("level"), o.requiredDouble("cellM"), o.requiredInt("texturePx"))
    }
}

/** Kantenlaenge einer Kachel der feinsten Stufe in Metern. */
const val WORLD_LEVEL0_CELL_M: Double = 256.0

/** Jede Stufe ist um diesen Faktor groeber; eine Kachel hat `FACTOR × FACTOR` Teilflaechen. */
const val WORLD_LEVEL_FACTOR: Int = 4

/** Zahl der Detailstufen (0 = nah, 1 = mittel, 2 = fern). */
const val WORLD_LEVEL_COUNT: Int = 3

/** Kantenlaenge einer Kachel der Stufe [level] in Metern: 256, 1024, 4096. */
fun worldCellM(level: Int): Double {
    var c = WORLD_LEVEL0_CELL_M
    repeat(level) { c *= WORLD_LEVEL_FACTOR }
    return c
}

/** Kachel-ID `L<stufe>_<ix>_<iy>`, z. B. `L0_3_-2`. Zugleich der Dateiname ohne `.glb`. */
fun worldTileId(level: Int, ix: Int, iy: Int): String = "L${level}_${ix}_$iy"

/** Ein Bereich der Strecke, `fromM .. toM` Laufmeter innerhalb der Runde (beide einschliesslich). */
data class ShowInterval(val fromM: Double, val toM: Double) {
    operator fun contains(routeDistM: Double): Boolean = routeDistM in fromM..toM
}

/**
 * Eine Kachel der Welt.
 *
 * Kachel `(ix, iy)` der Stufe `level` bedeckt in ENU
 * `e ∈ [ix·cellM, (ix+1)·cellM)`, `n ∈ [iy·cellM, (iy+1)·cellM)`.
 *
 * @property file Pfad der GLB-Datei relativ zum Weltverzeichnis.
 * @property bytes Groesse der GLB-Datei.
 * @property anchorE Bezugspunkt der Eckpunkte in ENU-Metern: die Kachelmitte
 *   ([anchorE], [anchorN]) auf der Hoehe [anchorU].
 * @property minU tiefster Punkt des Inhalts; mit [maxU] der Hoehenbereich fuer
 *   Sichtbarkeitstests.
 * @property show wann die Kachel geladen sein soll: Sie wird gebraucht, solange
 *   die Stelle des Fahrers innerhalb der Runde in einem der Bereiche liegt.
 *   Der Weltbau rechnet die Bereiche so, dass die Summe aller gleichzeitig
 *   gebrauchten Kacheln das [WorldBudget] einhaelt — die Darstellung braucht
 *   keine eigene Raumlogik.
 * @property textureBytes GPU-Speicher aller Texturen der Kachel
 *   (unkomprimiert RGBA8 samt Mipmaps = Breite × Hoehe × 4 × 4/3).
 * @property triangles Zahl der Dreiecke.
 * @property aerial die Bodentextur ist ein echtes Luftbild (sonst erzeugt).
 * @property degraded der Kachel fehlen Daten (z. B. Vektorkachel nicht ladbar)
 *   — fahrbar, aber unvollstaendig.
 */
data class WorldTile(
    val id: String,
    val level: Int,
    val ix: Int,
    val iy: Int,
    val file: String,
    val bytes: Long,
    val anchorE: Double,
    val anchorN: Double,
    val anchorU: Double,
    val minU: Double,
    val maxU: Double,
    val show: List<ShowInterval>,
    val textureBytes: Long,
    val triangles: Int,
    val aerial: Boolean = false,
    val degraded: Boolean = false,
) {
    /** Ob die Kachel an der Stelle [routeDistM] (innerhalb der Runde) geladen sein soll. */
    fun neededAt(routeDistM: Double): Boolean = show.any { routeDistM in it }

    fun toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("level", level)
        put("ix", ix)
        put("iy", iy)
        put("file", file)
        put("bytes", bytes)
        put(
            "anchor",
            buildJsonArray {
                add(anchorE)
                add(anchorN)
                add(anchorU)
            },
        )
        put("minU", minU)
        put("maxU", maxU)
        put(
            "show",
            buildJsonArray {
                show.forEach {
                    add(
                        buildJsonArray {
                            add(it.fromM)
                            add(it.toM)
                        },
                    )
                }
            },
        )
        put("textureBytes", textureBytes)
        put("triangles", triangles)
        if (aerial) put("aerial", true)
        if (degraded) put("degraded", true)
    }

    companion object {
        fun fromJson(o: JsonObject): WorldTile {
            val anchor = o.requiredArray("anchor").map { num(it) }
            require(anchor.size == 3) { "Welt-Manifest: anchor braucht drei Zahlen" }
            return WorldTile(
                id = o.requiredString("id"),
                level = o.requiredInt("level"),
                ix = o.requiredInt("ix"),
                iy = o.requiredInt("iy"),
                file = o.requiredString("file"),
                bytes = o.optionalLong("bytes") ?: 0L,
                anchorE = anchor[0],
                anchorN = anchor[1],
                anchorU = anchor[2],
                minU = o.optionalDouble("minU") ?: 0.0,
                maxU = o.optionalDouble("maxU") ?: 0.0,
                show = o.requiredArray("show").map { pair ->
                    val p = (pair as JsonArray).map { num(it) }
                    require(p.size == 2) { "Welt-Manifest: show braucht Paare" }
                    ShowInterval(p[0], p[1])
                },
                textureBytes = o.optionalLong("textureBytes") ?: 0L,
                triangles = o.optionalInt("triangles") ?: 0,
                aerial = o.optionalBoolean("aerial") ?: false,
                degraded = o.optionalBoolean("degraded") ?: false,
            )
        }
    }
}

/**
 * Was diese Welt hoechstens kostet — vom Weltbau **nachgerechnet**, nicht
 * geschaetzt.
 *
 * @property maxTextureBytes groesste Summe der [WorldTile.textureBytes] aller
 *   an irgendeiner Stelle der Strecke gleichzeitig gebrauchten Kacheln.
 * @property maxTriangles dasselbe fuer die Dreiecke.
 * @property diskBytes Groesse des ganzen Weltverzeichnisses.
 */
data class WorldBudget(val maxTextureBytes: Long, val maxTriangles: Int, val diskBytes: Long) {
    fun toJson(): JsonObject = buildJsonObject {
        put("maxTextureBytes", maxTextureBytes)
        put("maxTriangles", maxTriangles)
        put("diskBytes", diskBytes)
    }

    companion object {
        fun fromJson(o: JsonObject) = WorldBudget(
            o.optionalLong("maxTextureBytes") ?: 0L,
            o.optionalInt("maxTriangles") ?: 0,
            o.optionalLong("diskBytes") ?: 0L,
        )
    }
}

/**
 * Eine Quellenangabe, die die Oberflaeche zeigen **muss**, solange diese Welt
 * zu sehen ist (docs/indoor.md, Abschnitt 8).
 *
 * @property text der Wortlaut, wie die Quelle ihn verlangt („© GeoBasis-DE / LGLN (2026)").
 * @property license Kurzname der Lizenz („dl-de/zero-2-0", „ODbL"), darf leer sein.
 * @property url Seite mit den Bedingungen, darf leer sein.
 */
data class WorldAttribution(val source: GeoSourceKind, val text: String, val license: String, val url: String) {
    fun toJson(): JsonObject = buildJsonObject {
        put("source", source.name)
        put("text", text)
        put("license", license)
        put("url", url)
    }

    companion object {
        fun fromJson(o: JsonObject): WorldAttribution? {
            val source = GeoSourceKind.entries.firstOrNull { it.name == o.optionalString("source") } ?: return null
            return WorldAttribution(
                source = source,
                text = o.optionalString("text") ?: return null,
                license = o.optionalString("license") ?: "",
                url = o.optionalString("url") ?: "",
            )
        }
    }
}

// ---------------------------------------------------------------------------
// JSON-Helfer
// ---------------------------------------------------------------------------

private fun num(el: kotlinx.serialization.json.JsonElement): Double =
    (el as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
        ?: throw IllegalArgumentException("Welt-Manifest: Zahl erwartet, gefunden $el")

/** Zentimetergenau — mehr traegt ein Float in dieser Groessenordnung ohnehin nicht. */
private fun floatsToJson(values: FloatArray): JsonArray = buildJsonArray {
    for (v in values) add((v * 100.0).roundToLong() / 100.0)
}

private fun floatsFromJson(arr: JsonArray): FloatArray = FloatArray(arr.size) { num(arr[it]).toFloat() }

