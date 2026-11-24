package de.trailscape.tool.indoor

import de.trailscape.core.TrackPoint
import de.trailscape.core.i18n.CoreTextsDe
import de.trailscape.core.indoor.IndoorRoute
import de.trailscape.core.indoor.IndoorRouteSource
import de.trailscape.core.indoor.SurfaceKind
import de.trailscape.core.indoor.SurfaceSection
import de.trailscape.core.indoor.buildIndoorRoute
import de.trailscape.core.indoor.catalog.CatalogEntry
import de.trailscape.core.indoor.catalog.CatalogRideKind
import de.trailscape.core.indoor.catalog.CatalogText
import de.trailscape.core.indoor.catalog.IndoorCatalog
import de.trailscape.core.indoor.catalog.isSafeCatalogId
import de.trailscape.core.indoor.catalog.sha256Hex
import de.trailscape.core.indoor.catalog.writeCatalogPackage
import de.trailscape.core.indoor.world.AerialSource
import de.trailscape.core.indoor.world.GeoSourceKind
import de.trailscape.core.indoor.world.IndoorQuality
import de.trailscape.core.indoor.world.WorldBuildDeps
import de.trailscape.core.indoor.world.WorldBuildFailed
import de.trailscape.core.indoor.world.WorldManifest
import de.trailscape.core.indoor.world.aerialSources
import de.trailscape.core.indoor.world.checkWorld
import de.trailscape.core.indoor.world.createGeoPipeline
import de.trailscape.core.indoor.world.createWorldBuilder
import de.trailscape.core.parseBrouterGeoJson
import java.io.File
import java.net.ProxySelector
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * # Katalog bekannter Strecken (docs/indoor/catalog.md)
 *
 * ```
 * catalog-routes [--only a,b]                       Strecken ueber BRouter neu herleiten (GPX + Belag)
 * catalog-build --out <dir> [--only a,b] [--quality MEDIUM] [--app-catalog <datei>] [--keep-going] [--no-aerial]
 * ```
 * Quelle der Eintraege ist `tool/indoor-dev/catalog/catalog-src.json`, die
 * hergeleiteten Strecken liegen unter `tool/indoor-dev/catalog/routes/`.
 * `catalog-build` baut je Eintrag die Welt, prueft sie (`checkWorld`), packt
 * sie (`<out>/packages/<id>-v<version>.zip`) und schreibt `<out>/catalog.json`.
 */

const val CATALOG_DIR = "tool/indoor-dev/catalog"
const val CATALOG_SRC = "$CATALOG_DIR/catalog-src.json"
const val CATALOG_ROUTES = "$CATALOG_DIR/routes"

/** Ein Abschnitt, dessen Belag die OSM-Daten nicht (richtig) kennen. */
data class SurfaceOverride(val fromM: Double, val toM: Double, val kind: SurfaceKind, val onlyUnknown: Boolean)

/** Ein Eintrag aus `catalog-src.json`. */
data class CatalogSource(
    val id: String,
    val version: Int,
    val name: CatalogText,
    val region: CatalogText,
    val country: String,
    val kind: CatalogRideKind,
    val description: CatalogText,
    val profile: String,
    val waypoints: List<Pair<Double, Double>>,
    val overrides: List<SurfaceOverride>,
    val quality: IndoorQuality,
    /** Datenstufe „Laser" (DGM1, nDOM, LoD2; `docs/indoor/lidar.md`) fuer diese Strecke — nur wirksam, wo `laserSources` sie abdeckt. */
    val laser: Boolean = false,
) {
    val gpx: File get() = File(CATALOG_ROUTES, "$id.gpx")
    val surfaces: File get() = File(CATALOG_ROUTES, "$id.surfaces.json")
    val packageFile: String get() = "$id-v$version.zip"
}

class CatalogSourceFile(val baseUrl: String, val entries: List<CatalogSource>)

fun readCatalogSource(file: File = File(CATALOG_SRC)): CatalogSourceFile {
    val o = Json.parseToJsonElement(file.readText()) as JsonObject
    fun s(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.content
    fun text(o: JsonObject, k: String) = CatalogText.fromJson(o[k] as? JsonObject)
    val entries = (o["entries"] as JsonArray).map { e ->
        val x = e as JsonObject
        val id = s(x, "id")!!
        require(isSafeCatalogId(id)) { "unzulaessige Kennung $id" }
        CatalogSource(
            id = id,
            version = (x["version"] as JsonPrimitive).intOrNull ?: error("$id: version fehlt"),
            name = text(x, "name"),
            region = text(x, "region"),
            country = s(x, "country") ?: "",
            kind = CatalogRideKind.fromNameOrNull(s(x, "kind")) ?: error("$id: kind unbekannt"),
            description = text(x, "description"),
            profile = s(x, "profile") ?: "trekking",
            waypoints = (x["waypoints"] as JsonArray).map { p ->
                val a = p as JsonArray
                (a[0] as JsonPrimitive).doubleOrNull!! to (a[1] as JsonPrimitive).doubleOrNull!!
            },
            overrides = (x["surfaceOverrides"] as? JsonArray).orEmpty().map { p ->
                val y = p as JsonObject
                SurfaceOverride(
                    (y["fromM"] as JsonPrimitive).doubleOrNull!!,
                    (y["toM"] as JsonPrimitive).doubleOrNull!!,
                    SurfaceKind.fromNameOrNull(s(y, "kind")) ?: error("$id: Belag unbekannt"),
                    (y["onlyUnknown"] as? JsonPrimitive)?.booleanOrNull ?: true,
                )
            },
            quality = IndoorQuality.fromNameOrNull(s(x, "quality")) ?: IndoorQuality.MEDIUM,
            laser = (x["laser"] as? JsonPrimitive)?.booleanOrNull ?: false,
        )
    }
    require(entries.map { it.id }.toSet().size == entries.size) { "Kennungen doppelt" }
    return CatalogSourceFile(s(o, "baseUrl") ?: IndoorCatalog.DEFAULT_BASE_URL, entries)
}

private fun selected(all: List<CatalogSource>, only: String?): List<CatalogSource> {
    if (only.isNullOrBlank()) return all
    val ids = only.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    ids.forEach { id -> require(all.any { it.id == id }) { "unbekannte Strecke: $id" } }
    return all.filter { it.id in ids }
}

// ---------------------------------------------------------------------------
// catalog-routes
// ---------------------------------------------------------------------------

/** Belaege auf [route] (Laufmeter der Strecke) mit den Korrekturen [overrides]. */
fun applyOverrides(sections: List<SurfaceSection>, overrides: List<SurfaceOverride>): List<SurfaceSection> {
    if (overrides.isEmpty()) return sections
    val cuts = (sections.flatMap { listOf(it.fromM, it.toM) } + overrides.flatMap { listOf(it.fromM, it.toM) })
        .filter { it >= sections.first().fromM && it <= sections.last().toM }.toSortedSet().toList()
    val out = ArrayList<SurfaceSection>()
    for (i in 0 until cuts.size - 1) {
        val a = cuts[i]
        val b = cuts[i + 1]
        if (b - a < 1e-6) continue
        val mid = (a + b) / 2
        val base = sections.first { mid >= it.fromM && mid <= it.toM }
        val ov = overrides.lastOrNull { mid >= it.fromM && mid <= it.toM && (!it.onlyUnknown || base.kind == SurfaceKind.UNKNOWN) }
        val s = base.copy(fromM = a, toM = b, kind = ov?.kind ?: base.kind)
        val last = out.lastOrNull()
        if (last != null && last.kind == s.kind && last.highway == s.highway) out[out.size - 1] = last.copy(toM = b) else out += s
    }
    return out
}

private fun brouterGeoJson(src: CatalogSource): String {
    val lonlats = src.waypoints.joinToString("|") { (lat, lon) -> "$lon,$lat" }
    val url = "https://brouter.de/brouter?lonlats=" + URLEncoder.encode(lonlats, Charsets.UTF_8) +
        "&profile=${src.profile}&alternativeidx=0&format=geojson"
    val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).proxy(ProxySelector.getDefault()).build()
    val req = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(90)).header("User-Agent", JavaHttpFetcher.USER_AGENT).GET().build()
    val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
    check(resp.statusCode() == 200) { "BRouter ${resp.statusCode()}: ${resp.body().take(200)}" }
    return resp.body()
}

fun writeGpx(file: File, name: String, desc: String, points: List<TrackPoint>) {
    fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    val sb = StringBuilder()
    sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
    sb.append("<gpx version=\"1.1\" creator=\"Trailscape indoor-dev catalog-routes\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
    sb.append("  <metadata>\n    <name>${esc(name)}</name>\n    <desc>${esc(desc)}</desc>\n")
    sb.append("    <copyright author=\"OpenStreetMap-Mitwirkende\">\n      <license>https://opendatacommons.org/licenses/odbl/1-0/</license>\n    </copyright>\n  </metadata>\n")
    sb.append("  <trk>\n    <name>${esc(name)}</name>\n    <trkseg>\n")
    for (p in points) {
        sb.append("      <trkpt lat=\"%.6f\" lon=\"%.6f\">".format(java.util.Locale.ROOT, p.lat, p.lon))
        p.ele?.let { sb.append("<ele>%.1f</ele>".format(java.util.Locale.ROOT, it)) }
        sb.append("</trkpt>\n")
    }
    sb.append("    </trkseg>\n  </trk>\n</gpx>\n")
    file.parentFile.mkdirs()
    file.writeText(sb.toString())
}

fun writeSurfaceFile(file: File, route: IndoorRoute) {
    val o = buildJsonObject {
        put("source", route.source.name)
        put("lengthM", Math.round(route.lengthM).toDouble())
        put(
            "sections",
            buildJsonArray {
                route.surfaces.forEach { s ->
                    add(
                        buildJsonObject {
                            put("fromM", Math.round(s.fromM * 10) / 10.0)
                            put("toM", Math.round(s.toM * 10) / 10.0)
                            put("kind", s.kind.name)
                            s.highway?.let { put("highway", it) }
                            s.widthM?.let { put("widthM", it) }
                        },
                    )
                }
            },
        )
    }
    file.writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), o) + "\n")
}

fun catalogRoutes(only: String?): Int {
    val src = readCatalogSource()
    var failed = 0
    for (e in selected(src.entries, only)) {
        try {
            val body = brouterGeoJson(e)
            val planned = parseBrouterGeoJson(body, CoreTextsDe)
            val route = buildIndoorRoute(e.name.de, planned.points, planned.waySections, IndoorRouteSource.BROUTER_WAY_TAGS)
                ?: error("Strecke nicht fahrbar")
            val fixed = route.copy(surfaces = applyOverrides(route.surfaces, e.overrides))
            writeGpx(
                e.gpx,
                e.name.de,
                "Katalogstrecke ${e.id}: BRouter (${e.profile}) zwischen ${e.waypoints.size} Wegpunkten aus catalog-src.json. " +
                    "Daten © OpenStreetMap-Mitwirkende, ODbL 1.0.",
                planned.points,
            )
            writeSurfaceFile(e.surfaces, fixed)
            println("${e.id}: ${"%.2f".format(route.lengthM / 1000)} km, ${planned.ascentM.toInt()} Hm, ${fixed.surfaces.size} Belagsabschnitte → ${e.gpx.path}")
        } catch (x: Exception) {
            System.err.println("${e.id}: gescheitert: ${x.message}")
            failed++
        }
        Thread.sleep(1500) // brouter.de schonen
    }
    return if (failed == 0) 0 else 1
}

// ---------------------------------------------------------------------------
// catalog-build
// ---------------------------------------------------------------------------

/** Anteile der Belagsklassen an der Laenge. */
fun surfaceShares(sections: List<SurfaceSection>, lengthM: Double): Map<SurfaceKind, Double> {
    if (lengthM <= 0) return emptyMap()
    return sections.groupBy { it.kind }
        .mapValues { (_, l) -> l.sumOf { it.toM - it.fromM } / lengthM }
        .filterValues { it >= 0.005 }
}

/**
 * Positive Hoehenmeter des Streckenbands mit einer Schwelle von
 * [thresholdM] (wie die „gefilterten" Hoehenmeter der Router): kleine
 * Wellen des Gelaendemodells zaehlen nicht mit.
 */
fun ascentOf(m: WorldManifest, thresholdM: Double = 4.0): Double {
    val u = m.track.u
    if (u.isEmpty()) return 0.0
    var sum = 0.0
    var ref = u[0].toDouble()
    for (i in 1 until u.size) {
        val v = u[i].toDouble()
        when {
            v - ref >= thresholdM -> { sum += v - ref; ref = v }
            v < ref -> ref = v
        }
    }
    return sum
}

/** Luftbild-Dienste (Register der App + nur fuer das Vorbauen), deren Rechteck die Strecke beruehrt. */
fun aerialFor(route: IndoorRoute, register: List<AerialSource>): List<AerialSource> {
    val minLat = route.points.minOf { it.lat }
    val maxLat = route.points.maxOf { it.lat }
    val minLon = route.points.minOf { it.lon }
    val maxLon = route.points.maxOf { it.lon }
    return register.filter { it.minLat <= maxLat && it.maxLat >= minLat && it.minLon <= maxLon && it.maxLon >= minLon }
}

fun catalogBuild(
    out: File,
    only: String?,
    qualityOverride: IndoorQuality?,
    appCatalog: File?,
    keepGoing: Boolean,
    noAerial: Boolean,
    offline: Boolean,
    laser: Boolean = false,
): Int {
    val src = readCatalogSource()
    val register = aerialSources + catalogAerialSources
    val raw = if (offline) OfflineFetcher() else JavaHttpFetcher()
    val pipeline = createGeoPipeline(raw, File(CACHE_DIR_TOOL), register)
    val built = ArrayList<CatalogEntry>()
    // Schon gebaute Eintraege eines frueheren Laufs (mit --only) behalten.
    val previous = File(out, IndoorCatalog.CATALOG_FILE).takeIf { it.isFile }
        ?.let { runCatching { IndoorCatalog.parse(it.readText()) }.getOrNull() }
    var failed = 0
    for (e in selected(src.entries, only)) {
        println("== ${e.id} (v${e.version})")
        try {
            val useLaser = laser || e.laser
            built += buildOne(e, out, qualityOverride ?: e.quality, register, pipeline, noAerial, if (useLaser) toolLaserOptions(File(CACHE_DIR_TOOL), offline, null) else null)
        } catch (x: Exception) {
            System.err.println("${e.id}: gescheitert: ${x.message}")
            x.cause?.let { System.err.println("  Ursache: $it") }
            failed++
            if (!keepGoing) return 1
        }
    }
    val order = src.entries.map { it.id }
    val merged = (built + previous?.entries.orEmpty().filter { p -> built.none { it.id == p.id } && p.id in order })
        .sortedBy { order.indexOf(it.id) }
    val catalog = IndoorCatalog(src.baseUrl, System.currentTimeMillis(), merged)
    File(out, IndoorCatalog.CATALOG_FILE).writeText(catalog.toJson() + "\n")
    println("Katalog: ${File(out, IndoorCatalog.CATALOG_FILE).path} (${merged.size} Eintraege)")
    appCatalog?.let {
        it.parentFile?.mkdirs()
        // Ohne Pruefsummen: Die App holt Version, Groesse und SHA-256 vor jedem
        // Laden aus dem Release-Katalog; die Pakete dieses Laufs sind nicht die
        // des CI-Laufs (Geodaten aendern sich), Groessen bleiben als Richtwert.
        it.writeText(catalog.copy(entries = catalog.entries.map { e -> e.copy(sha256 = null) }).toJson() + "\n")
        println("App-Katalog: ${it.path}")
    }
    return if (failed == 0) 0 else 1
}

private fun buildOne(
    e: CatalogSource,
    out: File,
    quality: IndoorQuality,
    register: List<AerialSource>,
    pipeline: de.trailscape.core.indoor.world.GeoFetcher,
    noAerial: Boolean,
    laser: de.trailscape.core.indoor.world.LaserOptions? = null,
): CatalogEntry {
    check(e.gpx.isFile) { "${e.gpx} fehlt — erst catalog-routes" }
    val (_, points) = readGpx(e.gpx)
    val surfaces = e.surfaces.takeIf { it.isFile }?.let(::readSurfaceFile)
    val full = cumulative(points).last()
    val (route, _) = indoorRouteOf(e.name.de, points, surfaces, full, 0.0, full)
    val builder = laser?.let { de.trailscape.core.indoor.world.createLaserWorldBuilder(it) } ?: createWorldBuilder()
    val plan = builder.plan(route, quality).let { p -> p.copy(aerial = if (noAerial) emptyList() else aerialFor(route, register)) }
    val worldDir = File(out, "worlds/${e.id}")
    // Ein Rest eines anderen Plans (andere Strecke/Stufe) wuerde den Bau verwirren.
    File(worldDir, WorldManifest.FILE_NAME).takeIf { it.isFile }?.let { f ->
        val old = runCatching { WorldManifest.parse(f.readText()) }.getOrNull()
        if (old == null || old.worldId != plan.worldId) worldDir.deleteRecursively()
    }
    println("  ${"%.2f".format(route.lengthM / 1000)} km, ${plan.tileCount} Kacheln, Luftbild: ${plan.aerial.joinToString { it.label }.ifEmpty { "keines" }}, Laser: ${if (laser != null) "ja" else "nein"}")
    val start = System.nanoTime()
    val manifest = try {
        builder.build(plan, WorldBuildDeps(pipeline, ImageIoDecoder), worldDir, ConsoleProgress())
    } catch (x: WorldBuildFailed) {
        throw IllegalStateException("Bau gescheitert: ${x.message}", x.cause)
    }
    de.trailscape.core.indoor.world.laserBuildStats(builder).takeIf { it.isNotEmpty() }?.let { s ->
        println("  Laser: " + s.entries.joinToString(", ") { "${it.key}=${it.value}" })
    }
    val problems = checkWorld(worldDir)
    check(problems.isEmpty()) { "Pruefung: ${problems.size} Verstoesse: ${problems.take(5)}" }
    val pkg = File(out, "packages/${e.packageFile}")
    writeCatalogPackage(worldDir, e.id, e.version, pkg)
    val sha = sha256Hex(pkg)
    val aerialTiles = manifest.tiles.count { it.aerial }
    val aerialText = manifest.attribution.filter { it.source == GeoSourceKind.AERIAL }.joinToString("; ") { it.text }
    val entry = CatalogEntry(
        id = e.id,
        version = e.version,
        worldId = manifest.worldId,
        name = e.name,
        region = e.region,
        country = e.country,
        kind = e.kind,
        lengthM = manifest.track.lengthM,
        ascentM = ascentOf(manifest),
        surfaces = surfaceShares(manifest.track.surfaces, manifest.track.lengthM),
        description = e.description,
        packageFile = e.packageFile,
        packageBytes = pkg.length(),
        sha256 = sha,
        aerial = aerialTiles > 0,
        aerialSource = aerialText.takeIf { aerialTiles > 0 && it.isNotBlank() },
        quality = quality,
        diskBytes = manifest.budget.diskBytes,
    )
    println(
        "  fertig in ${"%.0f".format((System.nanoTime() - start) / 1e9)} s: ${manifest.tiles.size} Kacheln " +
            "(Luftbild ${aerialTiles}), Paket ${"%.1f".format(pkg.length() / 1_048_576.0)} MB, ${"%.0f".format(entry.ascentM)} Hm, sha256 $sha",
    )
    return entry
}

/** Fortschritt knapp: je Phase hoechstens alle 10 s eine Zeile. */
private class ConsoleProgress : de.trailscape.core.indoor.world.WorldBuildListener {
    private val last = HashMap<de.trailscape.core.indoor.world.WorldBuildPhase, Long>()
    override fun onProgress(phase: de.trailscape.core.indoor.world.WorldBuildPhase, done: Int, total: Int) {
        val now = System.nanoTime()
        val prev = last[phase]
        if (prev == null || done == total || now - prev > 10_000_000_000L) {
            println("    ${phase.name} $done/$total")
            last[phase] = now
        }
    }
}
