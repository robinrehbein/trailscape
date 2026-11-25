package de.trailscape.tool.indoor

import de.trailscape.core.indoor.IndoorRoute
import de.trailscape.core.indoor.world.OsmDetail
import java.io.File
import java.net.ProxySelector
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration

/**
 * Volle OSM-Attribute entlang einer Strecke ueber die Overpass API holen (`--osm-detail`, nur Werkzeug:
 * Katalog-, Server- und Entwicklerbau — nie das Handy; `docs/indoor/detailtreue.md`, „Weitere Datenquellen“).
 *
 * Nutzungsregeln der oeffentlichen Instanz (https://dev.overpass-api.de/overpass-doc/en/preface/commons.html,
 * gelesen am 25.11.2026): hoechstens etwa 10 000 Anfragen und 1 GB am Tag, kein App-Backend. Eine Strecke
 * = eine Anfrage, Antwort im Plattencache ([OSM_CACHE_DAYS] Tage). Lizenz ODbL — Quellenvermerk steht im Manifest.
 *
 * @return `null`, wenn der Abruf scheitert (der Bau laeuft dann ohne Zusatzdaten weiter).
 */
fun fetchOsmDetail(route: IndoorRoute, cacheDir: File, offline: Boolean, endpoint: String = OVERPASS_ENDPOINT): OsmDetail? {
    val pts = route.points
    if (pts.size < 2) return null
    // Punkte etwa alle 80 m (Overpass „around“ an einer Linie)
    val ll = ArrayList<Double>()
    var last = -1e9
    for ((i, p) in pts.withIndex()) {
        if (p.distM - last >= 80.0 || i == pts.size - 1) {
            ll += p.lat; ll += p.lon
            last = p.distM
        }
    }
    val query = OsmDetail.overpassQuery(ll.toDoubleArray())
    val key = MessageDigest.getInstance("SHA-256").digest(query.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)
    val file = File(cacheDir, "osm-detail/$key.json")
    val fresh = file.isFile && System.currentTimeMillis() - file.lastModified() < OSM_CACHE_DAYS * 86_400_000L
    val text = if (fresh || (offline && file.isFile)) {
        file.readText(Charsets.UTF_8)
    } else if (offline) {
        System.err.println("OSM-Zusatzdaten: offline und nicht im Cache — ohne Zusatzdaten")
        return null
    } else {
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).proxy(ProxySelector.getDefault()).build()
        val body = "data=" + URLEncoder.encode(query, Charsets.UTF_8)
        val req = HttpRequest.newBuilder(URI.create(endpoint))
            .timeout(Duration.ofSeconds(240))
            .header("User-Agent", "Trailscape-indoor-dev (https://github.com/robinrehbein/trailscape)")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        var got: String? = null
        for (attempt in 0 until 3) {
            val res = try {
                client.send(req, HttpResponse.BodyHandlers.ofString())
            } catch (e: Exception) {
                // Netz/Host nicht erreichbar: nicht warten, ohne Zusatzdaten weiterbauen.
                System.err.println("OSM-Zusatzdaten: Abruf gescheitert (${e.message}) — ohne Zusatzdaten")
                return null
            }
            if (res.statusCode() == 200) { got = res.body(); break }
            if (res.statusCode() != 429 && res.statusCode() != 504) {
                System.err.println("OSM-Zusatzdaten: Status ${res.statusCode()} — ohne Zusatzdaten")
                return null
            }
            Thread.sleep(30_000L * (attempt + 1))
        }
        got ?: return null.also { System.err.println("OSM-Zusatzdaten: keine Antwort — ohne Zusatzdaten") }
    }
    val detail = try {
        OsmDetail.parseOverpass(text)
    } catch (e: IllegalArgumentException) {
        System.err.println("OSM-Zusatzdaten: ${e.message}")
        return null
    }
    if (!fresh) {
        file.parentFile.mkdirs()
        file.writeText(text, Charsets.UTF_8)
    }
    println("OSM-Zusatzdaten: ${detail.size} Objekte " + detail.counts().entries.joinToString(", ") { "${it.key}=${it.value}" })
    return detail
}

const val OVERPASS_ENDPOINT = "https://overpass-api.de/api/interpreter"
const val OSM_CACHE_DAYS = 30
