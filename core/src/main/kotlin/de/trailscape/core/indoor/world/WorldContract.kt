package de.trailscape.core.indoor.world

import de.trailscape.core.indoor.IndoorRoute
import java.io.File
import java.net.URI

/**
 * # Weltbau: Qualitaetsstufen, Geodaten-Abruf, Namen in den GLB-Dateien
 *
 * VERTRAGSDATEI (docs/indoor.md, Abschnitte 7 und 8). Besitzer: Slice
 * indoor-world. Typen, Signaturen und Namenskonstanten sind eingefroren.
 */

// ---------------------------------------------------------------------------
// Qualitaetsstufen
// ---------------------------------------------------------------------------

/**
 * Qualitaetsstufe — gilt fuer den **Bau** einer Welt (Texturgroessen,
 * Sichtweiten, Dichte) und als Voreinstellung der **Darstellung**
 * ([RenderSettings.forQuality]). Eine Welt laesst sich mit sparsameren
 * Darstellungs-Einstellungen zeigen, als sie gebaut wurde; ihr Speicherbedarf
 * liegt aber mit dem Bau fest.
 *
 * Die Zahlen sind **Obergrenzen**, die der Weltbau einhalten und im Manifest
 * nachweisen muss ([WorldBudget]); wie er sie ausschoepft, ist seine Sache.
 *
 * @property level0TexturePx Bodentextur einer 256-m-Kachel (1024 px = 25 cm je Pixel).
 * @property level1TexturePx Bodentextur einer 1024-m-Kachel.
 * @property level2TexturePx Bodentextur einer 4096-m-Kachel.
 * @property maxTextureBytes gleichzeitig geladener Texturspeicher (RGBA8 samt Mipmaps).
 * @property maxTriangles gleichzeitig geladene Dreiecke (ohne Fahrer und Geist).
 * @property viewDistanceM Sichtweite; dahinter liegt Dunst, es gibt keine Kacheln.
 */
enum class IndoorQuality(
    val level0TexturePx: Int,
    val level1TexturePx: Int,
    val level2TexturePx: Int,
    val maxTextureBytes: Long,
    val maxTriangles: Int,
    val viewDistanceM: Double,
) {
    LOW(512, 512, 256, 128L * 1024 * 1024, 300_000, 6_000.0),
    MEDIUM(1024, 512, 512, 256L * 1024 * 1024, 700_000, 12_000.0),
    HIGH(1024, 1024, 512, 400L * 1024 * 1024, 1_500_000, 20_000.0),
    ;

    fun texturePx(level: Int): Int = when (level) {
        0 -> level0TexturePx
        1 -> level1TexturePx
        else -> level2TexturePx
    }

    companion object {
        fun fromNameOrNull(name: String?): IndoorQuality? = entries.firstOrNull { it.name == name }
    }
}

/** GPU-Speicher einer quadratischen RGBA8-Textur samt Mipmaps in Bytes. */
fun textureGpuBytes(widthPx: Int, heightPx: Int = widthPx): Long = widthPx.toLong() * heightPx * 4 * 4 / 3

// ---------------------------------------------------------------------------
// Namen in den GLB-Dateien
// ---------------------------------------------------------------------------

/**
 * Knoten- und Materialnamen, an denen die Darstellung den Inhalt einer Kachel
 * erkennt (Schatten, Abschalten einzelner Schichten, Ausblenden von
 * Teilflaechen). Der Weltbau vergibt **genau** diese Namen; was er zusaetzlich
 * anlegt, beginnt mit `x_` und wird von der Darstellung wie Gelaende behandelt.
 *
 * ## Aufbau einer Kachel-GLB
 * Eine Szene, darunter hoechstens je ein Knoten der Schichten unten, alle
 * ohne eigene Verschiebung (die Eckpunkte sind relativ zum Kachel-Anker).
 * Keine Kameras, keine Lichter, keine Animationen, keine Skins. Nur
 * Kernmaterial (PBR Metallic-Roughness, `metallicFactor = 0`) und die
 * Erweiterung `KHR_materials_unlit`; Texturen als eingebettetes JPEG
 * (Baseline, nicht progressiv) oder PNG; Indizes 16 oder 32 Bit.
 */
object WorldNames {
    /** Gelaendeoberflaeche mit Bodentextur. Empfaengt Schatten, wirft keine. */
    const val NODE_TERRAIN = "terrain"

    /**
     * Teilflaeche des Gelaendes einer Kachel ab Stufe 1: Kind von
     * [NODE_TERRAIN], Name `terrain_sub_<i>_<j>` mit `i` nach Osten und `j`
     * nach Norden, je `0 .. WORLD_LEVEL_FACTOR-1`. Die Teilflaeche deckt genau
     * die Kachel `(ix·4 + i, iy·4 + j)` der naechstfeineren Stufe; die
     * Darstellung blendet sie aus, solange diese feinere Kachel gezeigt wird.
     */
    const val NODE_TERRAIN_SUB_PREFIX = "terrain_sub_"

    /** Der befahrene Weg (Fahrbahn und Raender). Empfaengt Schatten. */
    const val NODE_ROUTE = "route"

    /** Uebrige Strassen und Wege. */
    const val NODE_ROADS = "roads"

    /** Gebaeude. Werfen und empfangen Schatten. */
    const val NODE_BUILDINGS = "buildings"

    /** Baeume und Wald. Werfen Schatten. In niedriger Qualitaet abschaltbar. */
    const val NODE_TREES = "trees"

    /** Wasserflaechen. */
    const val NODE_WATER = "water"

    /** Kleinteile (Zaeune, Masten, Leitpfosten …). Als Erstes abschaltbar. */
    const val NODE_PROPS = "props"

    fun terrainSubName(i: Int, j: Int): String = "$NODE_TERRAIN_SUB_PREFIX${i}_$j"

    /** Material der Bodentextur. */
    const val MAT_TERRAIN = "mat_terrain"

    /** Material der Fahrbahn je Belag: `mat_route_<SurfaceKind.textureKey>`. */
    const val MAT_ROUTE_PREFIX = "mat_route_"
    const val MAT_BUILDING_WALL = "mat_building_wall"
    const val MAT_BUILDING_ROOF = "mat_building_roof"
    const val MAT_TREE_TRUNK = "mat_tree_trunk"

    /** Laub/Nadeln — `alphaMode = MASK`, doppelseitig. */
    const val MAT_TREE_LEAVES = "mat_tree_leaves"
    const val MAT_WATER = "mat_water"
}

/**
 * Die mitgelieferten Modelle (Fahrer, Geist) — route-unabhaengig, vom Slice
 * indoor-world erzeugt und als Assets eingecheckt
 * (`app/src/main/assets/indoor/models/`).
 *
 * ## Vereinbarung fuer [RIDER] und [GHOST]
 *  * Ursprung auf dem Boden mittig zwischen den Radaufstandspunkten, Blick
 *    nach **+Z** (glTF-Vorderseite), Y oben, Einheit Meter, Lebensgroesse.
 *  * Animation [ANIM_PEDAL]: genau **eine Kurbelumdrehung in 1,0 s**, nahtlos
 *    wiederholbar — bewegt Kurbel, Pedale und Beine. Die Darstellung setzt
 *    sie auf `Phase × 1,0 s`, die Phase laeuft mit der Trittfrequenz.
 *  * Animation [ANIM_ROLL]: genau **eine Radumdrehung in 1,0 s** — dreht
 *    beide Laufraeder. Die Phase laeuft mit `Tempo / WHEEL_CIRCUMFERENCE_M`.
 *  * Der Geist ist dasselbe Modell mit durchscheinenden Materialien
 *    (`alphaMode = BLEND`), als eigene Datei.
 */
object WorldModels {
    const val ASSET_DIR = "indoor/models"
    const val RIDER = "rider.glb"
    const val GHOST = "ghost.glb"
    const val ANIM_PEDAL = "pedal"
    const val ANIM_ROLL = "roll"

    /** Abrollumfang der Modell-Laufraeder in Metern. */
    const val WHEEL_CIRCUMFERENCE_M = 2.2
}

// ---------------------------------------------------------------------------
// Geodaten: Quellen, Positivliste, Abruf
// ---------------------------------------------------------------------------

/** Art einer Geodatenquelle. */
enum class GeoSourceKind {
    /** Hoehen: Terrarium-Kacheln (Mapzen/Tilezen, AWS Open Data). */
    ELEVATION,

    /** Karteninhalt: OpenFreeMap-Vektorkacheln (OpenMapTiles-Schema, OpenStreetMap-Daten). */
    VECTOR,

    /** Luftbilder: amtliche WMS-Dienste der Bundeslaender. */
    AERIAL,
}

/**
 * Ein amtlicher Luftbild-Dienst (WMS). Das Register ([aerialSources] in
 * `IndoorWorldEntry.kt`) pflegt der Slice indoor-world; aufgenommen wird nur,
 * was mit einer echten Anfrage geprueft wurde.
 *
 * @property id stabiler Kurzname („nw", „sn").
 * @property label Anzeigename des Landes („Nordrhein-Westfalen").
 * @property wmsUrl Dienstadresse ohne Abfrageteil, zwingend `https`.
 * @property layer Name der Farb-Ebene.
 * @property minLat grobes Umgebungsrechteck des Landes ([minLat], [minLon],
 *   [maxLat], [maxLon]); ausserhalb wird der Dienst nicht gefragt.
 * @property attribution Wortlaut der Quellenangabe, wie die Lizenz ihn verlangt.
 * @property license Kurzname der Lizenz („dl-de/zero-2-0", „dl-de/by-2-0", „CC BY 4.0").
 * @property licenseUrl Seite mit dem Lizenztext.
 * @property maxSizePx groesste Kantenlaenge, die der Dienst je Anfrage liefert.
 */
data class AerialSource(
    val id: String,
    val label: String,
    val wmsUrl: String,
    val layer: String,
    val minLat: Double,
    val minLon: Double,
    val maxLat: Double,
    val maxLon: Double,
    val attribution: String,
    val license: String,
    val licenseUrl: String,
    val maxSizePx: Int = 2048,
) {
    fun covers(lat: Double, lon: Double): Boolean = lat in minLat..maxLat && lon in minLon..maxLon
}

/** Feste Hosts und Pfade der Quellen, die nicht vom Bundesland abhaengen. */
object GeoEndpoints {
    const val ELEVATION_HOST = "s3.amazonaws.com"
    const val ELEVATION_PATH_PREFIX = "/elevation-tiles-prod/terrarium/"

    /** `{z}/{x}/{y}` in Web-Mercator-Kachelzaehlung (XYZ). Hoehe = `R·256 + G + B/256 − 32768` Meter. */
    const val ELEVATION_URL_TEMPLATE = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png"

    const val VECTOR_HOST = "tiles.openfreemap.org"
    const val VECTOR_PATH_PREFIX = "/planet"

    /**
     * TileJSON der Vektorkacheln. Es nennt unter `tiles[0]` die **versionierte**
     * Kachel-URL (`https://tiles.openfreemap.org/planet/<version>/{z}/{x}/{y}.pbf`);
     * die Version wechselt etwa woechentlich, deshalb wird sie je Weltbau
     * einmal gelesen und nie fest eingebaut. Hoechste Zoomstufe 14.
     */
    const val VECTOR_TILEJSON_URL = "https://tiles.openfreemap.org/planet"
}

/**
 * Die Positivliste: Nur was hier `true` ergibt, darf abgerufen werden.
 *
 * Erlaubt ist ausschliesslich `https` ohne Benutzerangabe und ohne
 * abweichenden Port auf
 *  * [GeoEndpoints.ELEVATION_HOST] unter [GeoEndpoints.ELEVATION_PATH_PREFIX],
 *  * [GeoEndpoints.VECTOR_HOST] unter [GeoEndpoints.VECTOR_PATH_PREFIX],
 *  * der Dienstadresse eines Eintrags aus [aerial] (gleicher Host, gleicher Pfad).
 *
 * Geprueft wird die **fertige** URL unmittelbar vor dem Senden; Weiterleitungen
 * folgt der Abruf nicht (eine Umleitung auf einen anderen Host waere sonst ein
 * Loch in der Liste).
 */
fun isAllowedGeoUrl(url: String, aerial: List<AerialSource>): Boolean {
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    if (uri.scheme != "https" || uri.userInfo != null || (uri.port != -1 && uri.port != 443)) return false
    val host = uri.host?.lowercase() ?: return false
    val path = uri.path ?: return false
    if (host == GeoEndpoints.ELEVATION_HOST && path.startsWith(GeoEndpoints.ELEVATION_PATH_PREFIX)) return true
    if (host == GeoEndpoints.VECTOR_HOST && path.startsWith(GeoEndpoints.VECTOR_PATH_PREFIX)) return true
    return aerial.any { source ->
        val base = runCatching { URI(source.wmsUrl) }.getOrNull() ?: return@any false
        base.scheme == "https" && base.host?.lowercase() == host && path == base.path
    }
}

/** Eine Geodaten-Anfrage (immer GET). */
data class GeoRequest(
    val kind: GeoSourceKind,
    val url: String,
    /** Wert des `Accept`-Kopfs, falls die Quelle einen braucht. */
    val accept: String? = null,
)

/**
 * Antwort auf eine [GeoRequest].
 *
 * @property status HTTP-Status; aus dem Cache bediente Antworten tragen 200.
 * @property fromCache die Bytes kamen von der Platte, es gab keinen Netzzugriff.
 */
class GeoResponse(
    val status: Int,
    val contentType: String?,
    val bytes: ByteArray,
    val fromCache: Boolean = false,
)

/**
 * Holt Geodaten — blockierend, wie `HttpClient.execute`.
 *
 * Das vorhandene `HttpClient` taugt hier nicht (seine Antwort ist Text; Kacheln
 * und Bilder sind Bytes), deshalb diese eigene, ebenso schmale Schnittstelle.
 * Ein HTTP-Fehlerstatus kommt als [GeoResponse] zurueck; bei Netzfehlern darf
 * die Implementierung werfen ([java.io.IOException]).
 *
 * Die **rohe** Netz-Implementierung liegt in `:app` (OkHttp, Slice indoor-app)
 * bzw. im Entwicklerwerkzeug. Positivliste, Plattencache und Drosselung legt
 * der Weltbau als Huelle darum ([createGeoPipeline] in `IndoorWorldEntry.kt`) —
 * wer Geodaten braucht, benutzt nur die Huelle.
 */
fun interface GeoFetcher {
    fun fetch(request: GeoRequest): GeoResponse
}

// ---------------------------------------------------------------------------
// Bilddekodierung
// ---------------------------------------------------------------------------

/** Ein dekodiertes Bild, zeilenweise von oben links, je Pixel `0xAARRGGBB`. */
class RasterImage(val width: Int, val height: Int, val argb: IntArray)

/**
 * Dekodiert PNG oder JPEG. `:core` kann das nicht selbst (kein `javax.imageio`
 * auf Android, kein `android.graphics` in `:core`): In der App steckt
 * `BitmapFactory` dahinter (Slice indoor-app), in Tests und im
 * Entwicklerwerkzeug `ImageIO`. `null` bei unlesbaren Bytes — nie eine Ausnahme.
 *
 * Wichtig fuer die Hoehenkacheln: ohne Farbverwaltung und ohne
 * Vormultiplikation dekodieren (`inPremultiplied = false`,
 * `ARGB_8888`), sonst stimmen die Meter nicht.
 */
fun interface RasterDecoder {
    fun decode(bytes: ByteArray): RasterImage?
}

// ---------------------------------------------------------------------------
// Weltbau
// ---------------------------------------------------------------------------

/** Was der Weltbau von aussen braucht. */
class WorldBuildDeps(
    /** Die Huelle aus [createGeoPipeline] — nie ein roher Netzabruf. */
    val fetcher: GeoFetcher,
    val decoder: RasterDecoder,
)

/**
 * Voranschlag vor dem Laden — die Oberflaeche zeigt ihn, bevor die
 * Nutzeraktion „Welt laden" irgendetwas abruft.
 *
 * @property requests Zahl der Netzanfragen je Quelle (ohne Cache gerechnet).
 * @property downloadBytes geschaetzte Datenmenge.
 * @property diskBytes geschaetzter Platz der fertigen Welt.
 * @property hosts alle Hosts, die angefragt wuerden (fuer den Hinweistext).
 * @property aerial die Luftbild-Dienste, die fuer diese Strecke in Frage kommen (leer = erzeugter Boden).
 */
data class WorldPlan(
    val worldId: String,
    val route: IndoorRoute,
    val quality: IndoorQuality,
    val tileCount: Int,
    val requests: Map<GeoSourceKind, Int>,
    val downloadBytes: Long,
    val diskBytes: Long,
    val hosts: List<String>,
    val aerial: List<AerialSource>,
)

/** Schritt des Weltbaus, fuer die Fortschrittsanzeige. */
enum class WorldBuildPhase { ELEVATION, VECTOR, AERIAL, MESHING, WRITING }

/** Fortschritt und Abbruch eines Weltbaus. Aufrufe kommen vom Thread des Baus. */
interface WorldBuildListener {
    /** @param done erledigte von [total] Arbeitsschritten der Phase. */
    fun onProgress(phase: WorldBuildPhase, done: Int, total: Int)

    /** Wird zwischen den Arbeitsschritten gefragt; `true` bricht mit [WorldBuildCancelled] ab. */
    fun isCancelled(): Boolean = false
}

/** Der Bau wurde ueber [WorldBuildListener.isCancelled] abgebrochen. Fertige Kacheln bleiben liegen. */
class WorldBuildCancelled : Exception("Weltbau abgebrochen")

/**
 * Der Bau ist gescheitert.
 *
 * @property reason maschinenlesbarer Grund — die Oberflaeche waehlt daraus ihren Text.
 */
class WorldBuildFailed(val reason: Reason, detail: String, cause: Throwable? = null) :
    Exception("Weltbau gescheitert ($reason): $detail", cause) {
    enum class Reason {
        /** Kein Netz und die noetigen Hoehendaten liegen nicht im Cache. */
        OFFLINE,

        /** Eine Quelle antwortet dauerhaft mit Fehlern. */
        SOURCE_ERROR,

        /** Der Platz auf dem Geraet reicht nicht. */
        NO_SPACE,

        /** Die Strecke ist zu lang oder zu kurz ([MAX_WORLD_ROUTE_M]). */
        ROUTE_UNSUPPORTED,

        /** Alles andere (Programmfehler, kaputte Daten). */
        INTERNAL,
    }
}

/** Laengste Strecke, fuer die eine Welt gebaut wird, in Metern. */
const val MAX_WORLD_ROUTE_M: Double = 150_000.0

/**
 * Der Weltbau: Geodaten → Netze und Texturen → GLB-Kacheln + Manifest.
 * Reines Kotlin/JVM, blockierend; die App ruft ihn auf einem
 * Hintergrund-Dispatcher.
 */
interface WorldBuilder {
    /** Rechnet ohne Netzzugriff aus, was der Bau braucht. Dieselbe Strecke und Stufe ergeben dieselbe `worldId`. */
    fun plan(route: IndoorRoute, quality: IndoorQuality): WorldPlan

    /**
     * Baut die Welt nach [outDir] (wird angelegt). **Fortsetzbar**: Schon
     * fertige Kacheln desselben Plans werden wiederverwendet, das Manifest
     * wird zuletzt und atomar geschrieben — ein Verzeichnis ohne
     * `manifest.json` ist eine angefangene Welt, keine kaputte.
     *
     * Fehlende Vektorkacheln oder Luftbilder brechen den Bau nicht ab
     * ([WorldTile.degraded], [WorldTile.aerial]); fehlende Hoehen schon.
     *
     * @throws WorldBuildCancelled nach Abbruch
     * @throws WorldBuildFailed wenn keine fahrbare Welt entstehen kann
     */
    fun build(plan: WorldPlan, deps: WorldBuildDeps, outDir: File, listener: WorldBuildListener): WorldManifest

    /**
     * Baut ohne jeden Netzzugriff eine kleine erfundene Welt (Huegel, Wald,
     * ein paar Haeuser, eine Runde von etwa 3 km mit wechselnden Belaegen) —
     * fuer die Testszene der Darstellung, den Demo-Modus und Screenshots.
     */
    fun buildTestWorld(outDir: File, quality: IndoorQuality): WorldManifest
}
