package de.trailscape.core.indoor.world

import java.io.File
import java.io.IOException
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

/**
 * # Datenstufe „Laser": amtliche Hoehen- und Gebaeudedaten der Laender
 *
 * Optional zum bisherigen Datensatz (Terrarium-Hoehen, OpenFreeMap, Luftbild):
 * Wo ein Land seine Laserscan-Produkte offen anbietet, baut der Weltbau
 *
 *  * das Gelaende im Korridor aus dem **DGM1** (1-m-Raster, Boden ohne Bewuchs
 *    und Gebaeude) — echte Boeschungen, Graeben, Daemme, Hohlwege,
 *  * den Bewuchs aus dem **nDOM** = DOM1 − DGM1 (Hoehe ueber Grund):
 *    Einzelbaeume mit echter Lage und Hoehe, Waldraender, Kronendach,
 *  * die Gebaeude aus dem **3D-Gebaeudemodell LoD2** (CityGML): echte
 *    Grundrisse, Trauf- und Firsthoehen, Dachform.
 *
 * Das Register [laserSources] pflegt der Slice indoor-world; aufgenommen wird
 * nur, was mit echten Anfragen geprueft ist (Datum am Eintrag, Protokoll in
 * `docs/indoor/lidar.md`).
 *
 * **Datenschutz/Positivliste:** Die Laser-Hosts stehen bewusst **nicht** in
 * [isAllowedGeoUrl] (eingefrorene Vertragsdatei) — die Stufe hat eine eigene,
 * ebenso strenge Liste ([isAllowedLaserUrl]) und einen eigenen Abruf
 * ([LaserFetcher]). Vorerst nutzt sie nur das Entwicklerwerkzeug
 * (`indoor-dev build --laser`); die App ruft diese Hosts nicht ab. Wer sie in
 * der App einschaltet, traegt die Hosts in PRIVACY.md ein.
 *
 * Zwei Abrufwege fuer die Raster: **WCS** (NRW: beliebiges Rechteck je
 * 256-m-Block, [dgmWcsUrl]/[domWcsUrl]) oder **Kachel-Download** ([tiles]:
 * ZIP-Archive fester Kacheln mit je einer Datei pro Kilometer, Baden-Wuerttemberg).
 * Genau einer der beiden ist gesetzt. Welche Quelle gilt, entscheidet die Lage
 * der Strecke ([covers], mindestens die Haelfte der Punkte).
 *
 * @property id stabiler Kurzname („nw", „bw").
 * @property utmZone UTM-Zone der Daten (ETRS89).
 * @property dgmWcsUrl WCS-2.0.1-Dienst des DGM (ohne Abfrageteil), [dgmCoverage] die Coverage-ID; `null` bei [tiles].
 * @property domWcsUrl WCS-Dienst des DOM (Oberflaeche mit Bewuchs und Gebaeuden), [domCoverage]; `null` bei [tiles].
 * @property lod2UrlTemplate Kachel-Download LoD2 mit `{e}`/`{n}` = Kilometer-Index des Rechts-/Hochwerts
 *   (bei [lod2Entry]: der Suedwestecke der ZIP-Kachel aus [tiles]).
 * @property lod2Entry Name der CityGML-Datei je Kilometer im ZIP (`{e}`/`{n}`), `null` = [lod2UrlTemplate] ist die GML selbst.
 * @property maxPx groesste Kante je WCS-Anfrage, die geprueft ist.
 * @property tiles Kachel-Download statt WCS (siehe [LaserTiles]).
 */
data class LaserSource(
    val id: String,
    val label: String,
    val minLat: Double,
    val minLon: Double,
    val maxLat: Double,
    val maxLon: Double,
    val utmZone: Int,
    val dgmWcsUrl: String?,
    val dgmCoverage: String?,
    val domWcsUrl: String?,
    val domCoverage: String?,
    val lod2UrlTemplate: String?,
    val attribution: String,
    val license: String,
    val licenseUrl: String,
    val maxPx: Int = 1024,
    val tiles: LaserTiles? = null,
    val lod2Entry: String? = null,
) {
    init {
        require((tiles == null) == (dgmWcsUrl != null && dgmCoverage != null)) { "Laser-Quelle $id: entweder WCS oder Kacheln" }
        require(lod2Entry == null || tiles != null) { "Laser-Quelle $id: LoD2 im ZIP nur mit Kachelschema" }
    }

    fun covers(lat: Double, lon: Double): Boolean = lat in minLat..maxLat && lon in minLon..maxLon

    /** GetCoverage fuer das UTM-Rechteck [x0]..[x1] × [y0]..[y1] (ganze Meter), GeoTIFF Float32 (nur WCS-Quellen). */
    fun coverageUrl(dom: Boolean, x0: Int, y0: Int, x1: Int, y1: Int): String {
        val base = checkNotNull(if (dom) domWcsUrl else dgmWcsUrl) { "Laser-Quelle $id hat keinen WCS" }
        val cov = if (dom) domCoverage else dgmCoverage
        return "$base?SERVICE=WCS&VERSION=2.0.1&REQUEST=GetCoverage&COVERAGEID=$cov&FORMAT=image/tiff" +
            "&SUBSET=x($x0,$x1)&SUBSET=y($y0,$y1)"
    }

    /**
     * Download der LoD2-Daten fuer den Kilometer ([kmE], [kmN]) (Suedwestecke);
     * `null`, wenn das Land keine anbietet. Bei [lod2Entry] ist es das ZIP der
     * Kachel aus [tiles], in dem dieser Kilometer liegt ([lod2EntryName] nennt die Datei darin).
     */
    fun lod2Url(kmE: Int, kmN: Int): String? {
        val t = lod2UrlTemplate ?: return null
        val tl = tiles
        return if (lod2Entry != null && tl != null) fill(t, tl.tileE(kmE), tl.tileN(kmN)) else fill(t, kmE, kmN)
    }

    /** Dateiname der LoD2-GML des Kilometers im ZIP ([lod2Entry]); `null` = die Antwort ist die GML selbst. */
    fun lod2EntryName(kmE: Int, kmN: Int): String? = lod2Entry?.let { fill(it, kmE, kmN) }

    /** Alle Hosts dieser Quelle. */
    val hosts: List<String>
        get() = listOfNotNull(dgmWcsUrl, domWcsUrl, lod2UrlTemplate, tiles?.dgmUrl, tiles?.domUrl)
            .mapNotNull { runCatching { URI(fill(it, 0, 0)).host?.lowercase() }.getOrNull() }.distinct()

    /** Alle URL-Vorlagen mit `{e}`/`{n}` (Kacheln und LoD2). */
    internal val templates: List<String>
        get() = listOfNotNull(lod2UrlTemplate, tiles?.dgmUrl, tiles?.domUrl)
}

private fun fill(t: String, e: Int, n: Int): String = t.replace("{e}", e.toString()).replace("{n}", n.toString())

/** Format einer Rasterdatei im Kachel-ZIP. */
enum class LaserRasterFormat {
    /** Text, je Zeile `Rechtswert Hochwert Hoehe` der Pixelmitte (Leerzeichen), 1-m-Raster. */
    XYZ,

    /** GeoTIFF Float32 ([decodeFloatTiff]). */
    TIFF,
}

/**
 * Kachel-Download der Raster: ZIP-Archive von [sizeKm] × [sizeKm] Kilometern,
 * darin je Kilometer eine Datei. Die Suedwestecken der Archive liegen auf
 * Kilometern mit `(e − originE) mod sizeKm = 0` und `(n − originN) mod sizeKm = 0`.
 *
 * @property dgmUrl ZIP des DGM mit `{e}`/`{n}` = Kilometer der Suedwestecke.
 * @property dgmEntry Anfang des Dateinamens je Kilometer im ZIP (`{e}`/`{n}`); dahinter darf
 *   ein Zusatz wie das Erfassungsjahr folgen, das Ende ist [dgmFormat] (`.xyz`/`.tif`).
 */
data class LaserTiles(
    val dgmUrl: String,
    val dgmEntry: String,
    val dgmFormat: LaserRasterFormat,
    val domUrl: String,
    val domEntry: String,
    val domFormat: LaserRasterFormat,
    val sizeKm: Int = 2,
    val originE: Int = 0,
    val originN: Int = 0,
    /** Erfahrungswert je Archiv (DGM + DOM), fuer den Voranschlag. */
    val bytesPerTile: Long = 0L,
    /** Erfahrungswert je LoD2-Archiv, fuer den Voranschlag. */
    val lod2BytesPerTile: Long = 0L,
) {
    /** Kilometer der Suedwestecke des Archivs, in dem der Kilometer [kmE] liegt. */
    fun tileE(kmE: Int): Int = originE + Math.floorDiv(kmE - originE, sizeKm) * sizeKm
    fun tileN(kmN: Int): Int = originN + Math.floorDiv(kmN - originN, sizeKm) * sizeKm

    fun url(dom: Boolean, tileE: Int, tileN: Int): String = fill(if (dom) domUrl else dgmUrl, tileE, tileN)

    /** Anfang des Dateinamens des Kilometers ([kmE], [kmN]) im Archiv. */
    fun entryPrefix(dom: Boolean, kmE: Int, kmN: Int): String = fill(if (dom) domEntry else dgmEntry, kmE, kmN)

    fun format(dom: Boolean): LaserRasterFormat = if (dom) domFormat else dgmFormat
}

/**
 * Das Register der Laser-Quellen.
 */
val laserSources: List<LaserSource> = listOf(
    // Geprueft am 24.11.2026 (docs/indoor/lidar.md): GetCapabilities + DescribeCoverage + GetCoverage
    // WCS 2.0.1, EPSG:25832, 1-m-Raster (Ursprung auf ganzen Metern + 0,5), GeoTIFF Float32
    // (DGM LZW, DOM unkomprimiert), Fehlwert -9999 (GDAL_NODATA) ausserhalb NRW, ausserhalb
    // des Umgebungsrechtecks Status 400. LoD2: 1-km-Kacheln CityGML 1.0 ohne Jahreszahl im Namen,
    // fehlende Kachel → 404. Lizenz laut Capabilities (Fees): dl-de/zero-2-0, kein Quellenvermerk
    // verlangt; wir nennen die Quelle trotzdem.
    LaserSource(
        id = "nw",
        label = "Nordrhein-Westfalen",
        minLat = 50.30,
        minLon = 5.85,
        maxLat = 52.55,
        maxLon = 9.50,
        utmZone = 32,
        dgmWcsUrl = "https://www.wcs.nrw.de/geobasis/wcs_nw_dgm",
        dgmCoverage = "nw_dgm",
        domWcsUrl = "https://www.wcs.nrw.de/geobasis/wcs_nw_dom",
        domCoverage = "nw_dom",
        lod2UrlTemplate = "https://www.opengeodata.nrw.de/produkte/geobasis/3dg/lod2_gml/lod2_gml/LoD2_32_{e}_{n}_1_NW.gml",
        attribution = "Gelände, Bewuchs und Gebäude: © Geobasis NRW (DGM1, DOM1, 3D-Gebäudemodell LoD2)",
        license = "dl-de/zero-2-0",
        licenseUrl = "https://www.govdata.de/dl-de/zero-2-0",
    ),
    // Geprueft am 24.11.2026 (docs/indoor/lidar.md, Abschnitt 7): Open GeoData Portal des LGL, kein WCS
    // fuer Hoehen. Kachel-Download als ZIP je 2 km × 2 km (Suedwestecke: Rechtswert-km ungerade,
    // Hochwert-km gerade; fehlende Kachel → 404), darin je Kilometer eine Datei mit Erfassungsjahr im
    // Namen: DGM1 als XYZ-Text (Pixelmitten auf ,5 m, NHN/DHHN2016), DOM1 als GeoTIFF Float32
    // (LZW, Praediktor 3, 512er-Kacheln), LoD2 als CityGML 1.0 (AdV-Profil wie NRW). EPSG:25832.
    // Lizenz laut lgl-bw.de/Produkte/Open-Data: dl-de/by-2-0 mit Namensnennung
    // „Datenquelle: LGL, www.lgl-bw.de, dl-de/by-2-0".
    LaserSource(
        id = "bw",
        label = "Baden-Württemberg",
        minLat = 47.53,
        minLon = 7.50,
        maxLat = 49.80,
        maxLon = 10.50,
        utmZone = 32,
        dgmWcsUrl = null,
        dgmCoverage = null,
        domWcsUrl = null,
        domCoverage = null,
        lod2UrlTemplate = "https://opengeodata.lgl-bw.de/data/lod2/LoD2_32_{e}_{n}_2_bw.zip",
        lod2Entry = "LoD2_32_{e}_{n}_1_BW.gml",
        tiles = LaserTiles(
            dgmUrl = "https://opengeodata.lgl-bw.de/data/dgm/dgm1_32_{e}_{n}_2_bw.zip",
            dgmEntry = "dgm1_32_{e}_{n}_1_bw",
            dgmFormat = LaserRasterFormat.XYZ,
            domUrl = "https://opengeodata.lgl-bw.de/data/dom1/dom1_32_{e}_{n}_2_bw.zip",
            domEntry = "dom1_32_{e}_{n}_1_bw",
            domFormat = LaserRasterFormat.TIFF,
            sizeKm = 2,
            originE = 1,
            originN = 0,
            bytesPerTile = 27_000_000L,
            lod2BytesPerTile = 8_000_000L,
        ),
        attribution = "Gelände, Bewuchs und Gebäude: Datenquelle: LGL, www.lgl-bw.de, dl-de/by-2-0 (DGM1, DOM1, 3D-Gebäudemodell LoD2)",
        license = "dl-de/by-2-0",
        licenseUrl = "https://www.govdata.de/dl-de/by-2-0",
    ),
)

/**
 * Positivliste der Laser-Stufe: `https`, kein Benutzer, Port 443, Host **und**
 * Pfad gleich einem Dienst aus [sources] (WCS: genau der Dienstpfad; LoD2 und
 * Kachel-Downloads: genau das Muster der Vorlage, nur Ziffern fuer `{e}`/`{n}`,
 * kein Abfrageteil).
 */
fun isAllowedLaserUrl(url: String, sources: List<LaserSource> = laserSources): Boolean {
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    if (uri.scheme != "https" || uri.userInfo != null || (uri.port != -1 && uri.port != 443)) return false
    val host = uri.host?.lowercase() ?: return false
    val path = uri.path ?: return false
    for (s in sources) {
        for (wcs in listOfNotNull(s.dgmWcsUrl, s.domWcsUrl)) {
            val b = URI(wcs)
            if (b.host.lowercase() == host && b.path == path && uri.rawQuery?.startsWith("SERVICE=WCS&") == true) return true
        }
        for (t in s.templates) {
            val b = URI(t.replace("{e}", "0").replace("{n}", "0"))
            if (b.host.lowercase() != host || uri.rawQuery != null) continue
            val pattern = Regex.escape(URI(t.replace("{e}", "EEE").replace("{n}", "NNN")).path)
                .replace("EEE", "\\E[0-9]{3,4}\\Q").replace("NNN", "\\E[0-9]{4}\\Q")
            if (Regex(pattern).matches(path)) return true
        }
    }
    return false
}

/**
 * Roher Abruf der Laser-Stufe (blockierend; HTTP-Fehler als Status, Netzfehler
 * als [IOException]). Wie beim [GeoFetcher] folgt er keinen Weiterleitungen.
 */
fun interface LaserFetcher {
    fun fetch(url: String): GeoResponse
}

/**
 * Huelle um den rohen Abruf: Positivliste (Verstoss = [IllegalArgumentException]),
 * Plattencache unter `<cacheDir>/indoor/laser/<2>/<sha256>` (365 Tage — die
 * Daten werden jaehrlich in Teilen erneuert; ohne Netz gilt auch
 * Abgelaufenes), hoechstens 2 Anfragen je Host, bei 429/503 dreimal
 * 2 s · 4 s · 8 s warten.
 */
fun createLaserPipeline(
    network: LaserFetcher,
    cacheDir: File,
    sources: List<LaserSource> = laserSources,
    offline: Boolean = false,
): LaserFetcher = LaserPipeline(network, cacheDir, sources, offline)

private class LaserPipeline(
    private val network: LaserFetcher,
    private val cacheDir: File,
    private val sources: List<LaserSource>,
    private val offline: Boolean,
) : LaserFetcher {
    private val perHost = ConcurrentHashMap<String, Semaphore>()

    override fun fetch(url: String): GeoResponse {
        require(isAllowedLaserUrl(url, sources)) { "Laser-URL nicht auf der Positivliste: $url" }
        val hash = sha256Hex(url)
        val dir = File(cacheDir, "indoor/laser/${hash.substring(0, 2)}")
        val data = File(dir, hash)
        val meta = File(dir, "$hash.meta")
        val fresh = data.isFile && meta.isFile &&
            System.currentTimeMillis() - (meta.readText().lineSequence().drop(1).firstOrNull()?.toLongOrNull() ?: 0L) < MAX_AGE_MS
        if (fresh || (offline && data.isFile)) {
            val type = runCatching { meta.readText().lineSequence().drop(2).firstOrNull() }.getOrNull()
            return GeoResponse(200, type, data.readBytes(), fromCache = true)
        }
        // 404 merkt sich der Cache ebenfalls (fehlende LoD2-Kachel = keine Gebaeude), als leere Datei.
        val missing = File(dir, "$hash.404")
        if (missing.isFile && (offline || System.currentTimeMillis() - missing.lastModified() < MAX_AGE_MS)) {
            return GeoResponse(404, null, ByteArray(0), fromCache = true)
        }
        if (offline) throw IOException("offline: $url nicht im Cache")
        val host = URI(url).host.lowercase()
        val sem = perHost.computeIfAbsent(host) { Semaphore(2) }
        var resp: GeoResponse? = null
        for (attempt in 0..3) {
            sem.acquire()
            try {
                resp = network.fetch(url)
            } finally {
                sem.release()
            }
            if (resp.status != 429 && resp.status != 503) break
            if (attempt < 3) Thread.sleep(2000L shl attempt)
        }
        val r = resp!!
        dir.mkdirs()
        if (r.status == 200 && plausible(r)) {
            val tmp = File(dir, "$hash.tmp${Thread.currentThread().id}")
            tmp.writeBytes(r.bytes)
            File(dir, "$hash.meta").writeText("$url\n${System.currentTimeMillis()}\n${r.contentType ?: ""}\n")
            if (!tmp.renameTo(data)) {
                data.delete()
                tmp.renameTo(data)
            }
        } else if (r.status == 404) {
            missing.writeText(url)
        }
        return r
    }

    private fun plausible(r: GeoResponse): Boolean {
        val b = r.bytes
        if (b.size < 8) return false
        val tiff = (b[0] == 'I'.code.toByte() && b[1] == 'I'.code.toByte()) || (b[0] == 'M'.code.toByte() && b[1] == 'M'.code.toByte())
        val xml = b[0] == '<'.code.toByte() || (b[0] == 0xEF.toByte() && b[3] == '<'.code.toByte())
        val zip = b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte() && b[2] == 3.toByte() && b[3] == 4.toByte()
        return tiff || zip || (xml && String(b, 0, minOf(b.size, 2000), Charsets.UTF_8).contains("CityModel"))
    }

    companion object {
        const val MAX_AGE_MS = 365L * 24 * 3600 * 1000
    }
}

/**
 * Der Weltbau mit der Datenstufe „Laser" (wie [createWorldBuilder], dazu
 * DGM1, nDOM und LoD2, wo [options] eine Quelle fuer die Strecke kennt;
 * sonst baut er genau wie ohne). Vorerst nur fuer das Entwicklerwerkzeug.
 */
fun createLaserWorldBuilder(options: LaserOptions): WorldBuilder = WorldBuilderImpl(FullWorldParts, laser = options)

/**
 * Kennzahlen der Laserdaten des letzten Baus von [builder] (aus
 * [createLaserWorldBuilder]) als `Schluessel=Wert`, sonst leer.
 */
fun laserBuildStats(builder: WorldBuilder): Map<String, String> {
    val l = (builder as? WorldBuilderImpl)?.lastLaser ?: return emptyMap()
    val s = l.stats
    return linkedMapOf(
        "quelle" to l.source.id,
        "bloecke" to s.blocks.toString(),
        "anfragen" to s.requests.toString(),
        "ausCache" to s.fromCache.toString(),
        "rasterBytes" to s.bytes.toString(),
        "lod2Kacheln" to s.lod2Tiles.toString(),
        "lod2Bytes" to s.lod2Bytes.toString(),
        "lod2Gebaeude" to l.buildings.size.toString(),
        "ladenMs" to s.millis.toString(),
        "versatzM" to "%.2f".format(java.util.Locale.ROOT, l.offsetM),
        "versatzP05" to "%.2f".format(java.util.Locale.ROOT, s.offsetP05),
        "versatzP95" to "%.2f".format(java.util.Locale.ROOT, s.offsetP95),
    )
}

/**
 * Hoehenprofil des letzten Baus von [builder] entlang der Linie (`e0`,`n0`) → (`e1`,`n1`) (ENU der Welt) alle
 * [stepM] Meter: je Zeile Abstand, e, n, DGM1, DOM1, Terrarium + Versatz, ueberblendet, Gewicht DGM1 und das
 * eingeebnete Gelaende (Meter ueber dem Ursprung, wie in den Kacheln). Leer ohne Laserdaten. Nur fuer Messungen.
 */
fun laserProfile(builder: WorldBuilder, e0: Double, n0: Double, e1: Double, n1: Double, stepM: Double = 1.0): List<String> {
    val impl = builder as? WorldBuilderImpl ?: return emptyList()
    val l = impl.lastLaser ?: return emptyList()
    val g = impl.lastGround
    val eleM = l.origin.eleM
    val len = kotlin.math.hypot(e1 - e0, n1 - n0)
    val steps = kotlin.math.max(1, kotlin.math.ceil(len / stepM).toInt())
    val out = ArrayList<String>()
    out += "s\te\tn\tdgm1\tdom1\tterrarium+versatz\tueberblendet\tgewicht\teingeebnet   (Meter ueber dem Ursprung ${"%.2f".format(java.util.Locale.ROOT, eleM)} m)"
    for (i in 0..steps) {
        val t = i.toDouble() / steps
        val e = e0 + (e1 - e0) * t
        val n = n0 + (n1 - n0) * t
        val p = l.probe(e, n)
        val carved = g?.heightAt(e, n) ?: Double.NaN
        out += listOf(len * t, e, n, p[0] - eleM, p[1] - eleM, p[2] - eleM, p[3] - eleM, p[4], carved)
            .joinToString("\t") { "%.2f".format(java.util.Locale.ROOT, it) }
    }
    return out
}

/**
 * Auskunft ueber die Wege der Karte im Umkreis [r] um (`e`, `n`) nach dem letzten Bau von [builder]: Klasse,
 * Belag, gezeichnete Breite, Punkte (ENU) und je Punkt der Abstand zur Strecke; dazu die Ecken an
 * Einmuendungen ([JunctionCorners]). Nur fuer die Fehlersuche im Werkzeug.
 */
fun roadsNear(builder: WorldBuilder, e: Double, n: Double, r: Double): List<String> {
    val impl = builder as? WorldBuilderImpl ?: return emptyList()
    val (line, map, land) = impl.lastDebug ?: return emptyList()
    val f = { v: Double -> "%.1f".format(java.util.Locale.ROOT, v) }
    val out = ArrayList<String>()
    for (road in map.roads(e - r, n - r, e + r, n + r)) {
        val spec = roadSpec(road)
        val pts = (0 until road.line.size / 2).joinToString(" ") { i ->
            val pe = road.line[2 * i]; val pn = road.line[2 * i + 1]
            val hit = line.nearest(pe, pn, 60.0)
            "(${f(pe)},${f(pn)}|${hit?.let { f(it.distance) } ?: "-"})"
        }
        out += "Weg ${road.cls}/${road.subclass}/${road.surface}/${road.service} breite=${spec?.widthM} rang=${spec?.rank}: $pts"
    }
    val spec = TileSpec(0, kotlin.math.floor(e / 256.0).toInt(), kotlin.math.floor(n / 256.0).toInt())
    val ctx = TileContext(spec = spec, origin = GeoOrigin(0.0, 0.0, 0.0), quality = IndoorQuality.MEDIUM, anchorU = 0.0,
        ground = impl.lastGround ?: return out, track = line, map = map, land = land, textures = TextureCache(),
        budget = TileBudget.of(IndoorQuality.MEDIUM, 0))
    val net = RoadNet(ctx, marginM = 30.0, maxStepM = RoadsLayer.STEP_M)
    for (c in JunctionCorners.find(line, land, net)) {
        if (kotlin.math.hypot(c.pe - e, c.pn - n) > r) continue
        out += "Ecke ${c.road.source.cls} P=(${f(c.pe)},${f(c.pn)}) r=${f(c.radius)} bogen=" +
            (0 until c.arc.size / 2).joinToString(" ") { "(${f(c.arc[2 * it])},${f(c.arc[2 * it + 1])})" }
    }
    for (rd in net.roads) {
        if (rd.e.indices.none { kotlin.math.hypot(rd.e[it] - e, rd.n[it] - n) < r }) continue
        out += "Netz ${rd.source.cls} hw=${f(rd.halfWidth)}: " + rd.e.indices.filter { kotlin.math.hypot(rd.e[it] - e, rd.n[it] - n) < r }
            .joinToString(" ") { "(${f(rd.e[it])},${f(rd.n[it])}${if (rd.onTrack[it]) "*" else ""}|${f(rd.trackDist[it])})" }
    }
    return out
}

/**
 * Die gemessenen Einzelbaeume (nDOM) im Umkreis [r] um (`e`, `n`) nach dem letzten Bau: Lage, Hoehe,
 * Kronenradius, Verhaeltnis Radius/Hoehe und die Art, die [laserTreeSpec] daraus waehlt. Nur fuer die Fehlersuche.
 */
fun laserTreesNear(builder: WorldBuilder, e: Double, n: Double, r: Double): List<String> {
    val impl = builder as? WorldBuilderImpl ?: return emptyList()
    val l = impl.lastLaser ?: return emptyList()
    val land = impl.lastDebug?.third
    val f = { v: Double -> "%.1f".format(java.util.Locale.ROOT, v) }
    val base = TreeModels.Spec(TreeModels.Species.BROAD, 10.0, 1.0, 1.0, 1, 0.0, false)
    return l.detectTrees(e - r, n - r, e + r, n + r).filter { kotlin.math.hypot(it.e - e, it.n - n) <= r }.map { t ->
        val spec = laserTreeSpec(base, t, conifer = land?.coniferShare(t.e, t.n) ?: 0.3)
        "Baum (${f(t.e)},${f(t.n)}) h=${f(t.heightM)} krone=${f(t.crownRadiusM)} r/h=${"%.3f".format(java.util.Locale.ROOT, t.crownRadiusM / t.heightM)} " +
            "art=${spec?.species} breite=${spec?.let { "%.2f".format(java.util.Locale.ROOT, it.width) }}"
    }
}
