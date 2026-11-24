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
 * @property id stabiler Kurzname („nw").
 * @property utmZone UTM-Zone der Daten (ETRS89).
 * @property dgmWcsUrl WCS-2.0.1-Dienst des DGM (ohne Abfrageteil), [dgmCoverage] die Coverage-ID.
 * @property domWcsUrl WCS-Dienst des DOM (Oberflaeche mit Bewuchs und Gebaeuden), [domCoverage].
 * @property lod2UrlTemplate Kachel-Download LoD2 mit `{e}`/`{n}` = Kilometer-Index des Rechts-/Hochwerts.
 * @property maxPx groesste Kante je WCS-Anfrage, die geprueft ist.
 */
data class LaserSource(
    val id: String,
    val label: String,
    val minLat: Double,
    val minLon: Double,
    val maxLat: Double,
    val maxLon: Double,
    val utmZone: Int,
    val dgmWcsUrl: String,
    val dgmCoverage: String,
    val domWcsUrl: String,
    val domCoverage: String,
    val lod2UrlTemplate: String?,
    val attribution: String,
    val license: String,
    val licenseUrl: String,
    val maxPx: Int = 1024,
) {
    fun covers(lat: Double, lon: Double): Boolean = lat in minLat..maxLat && lon in minLon..maxLon

    /** GetCoverage fuer das UTM-Rechteck [x0]..[x1] × [y0]..[y1] (ganze Meter), GeoTIFF Float32. */
    fun coverageUrl(dom: Boolean, x0: Int, y0: Int, x1: Int, y1: Int): String {
        val base = if (dom) domWcsUrl else dgmWcsUrl
        val cov = if (dom) domCoverage else dgmCoverage
        return "$base?SERVICE=WCS&VERSION=2.0.1&REQUEST=GetCoverage&COVERAGEID=$cov&FORMAT=image/tiff" +
            "&SUBSET=x($x0,$x1)&SUBSET=y($y0,$y1)"
    }

    /** LoD2-Kachel (1 km × 1 km) mit der Suedwestecke bei Kilometer ([kmE], [kmN]); `null`, wenn das Land keine anbietet. */
    fun lod2Url(kmE: Int, kmN: Int): String? = lod2UrlTemplate?.replace("{e}", kmE.toString())?.replace("{n}", kmN.toString())

    /** Alle Hosts dieser Quelle. */
    val hosts: List<String>
        get() = listOfNotNull(dgmWcsUrl, domWcsUrl, lod2UrlTemplate).mapNotNull { runCatching { URI(it.replace("{e}", "0").replace("{n}", "0")).host?.lowercase() }.getOrNull() }.distinct()
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
)

/**
 * Positivliste der Laser-Stufe: `https`, kein Benutzer, Port 443, Host **und**
 * Pfad gleich einem Dienst aus [sources] (WCS: genau der Dienstpfad; LoD2:
 * genau das Muster der Vorlage, nur Ziffern fuer `{e}`/`{n}`).
 */
fun isAllowedLaserUrl(url: String, sources: List<LaserSource> = laserSources): Boolean {
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    if (uri.scheme != "https" || uri.userInfo != null || (uri.port != -1 && uri.port != 443)) return false
    val host = uri.host?.lowercase() ?: return false
    val path = uri.path ?: return false
    for (s in sources) {
        for (wcs in listOf(s.dgmWcsUrl, s.domWcsUrl)) {
            val b = URI(wcs)
            if (b.host.lowercase() == host && b.path == path && uri.rawQuery?.startsWith("SERVICE=WCS&") == true) return true
        }
        val t = s.lod2UrlTemplate ?: continue
        val b = URI(t.replace("{e}", "0").replace("{n}", "0"))
        if (b.host.lowercase() != host || uri.rawQuery != null) continue
        val pattern = Regex.escape(URI(t.replace("{e}", "EEE").replace("{n}", "NNN")).path)
            .replace("EEE", "\\E[0-9]{3,4}\\Q").replace("NNN", "\\E[0-9]{4}\\Q")
        if (Regex(pattern).matches(path)) return true
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
        return tiff || (xml && String(b, 0, minOf(b.size, 2000), Charsets.UTF_8).contains("CityModel"))
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
