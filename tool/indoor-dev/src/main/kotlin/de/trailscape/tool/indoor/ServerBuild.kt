package de.trailscape.tool.indoor

import de.trailscape.core.indoor.catalog.sha256Hex
import de.trailscape.core.indoor.catalog.writeCatalogPackage
import de.trailscape.core.indoor.server.ServerRequestException
import de.trailscape.core.indoor.server.ServerRouteLimits
import de.trailscape.core.indoor.server.ServerWorldRequest
import de.trailscape.core.indoor.server.laserPreviewOf
import de.trailscape.core.indoor.world.GeoSourceKind
import de.trailscape.core.indoor.world.WorldBuildDeps
import de.trailscape.core.indoor.world.WorldBuildFailed
import de.trailscape.core.indoor.world.WorldBuildListener
import de.trailscape.core.indoor.world.WorldBuildPhase
import de.trailscape.core.indoor.world.WorldManifest
import de.trailscape.core.indoor.world.aerialSources
import de.trailscape.core.indoor.world.checkWorld
import de.trailscape.core.indoor.world.createGeoPipeline
import de.trailscape.core.indoor.world.createWorldBuilder
import de.trailscape.core.indoor.world.laserBuildStats
import de.trailscape.core.indoor.world.laserSources
import de.trailscape.core.indoor.world.worldBuilderVersion
import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * # Bau fuer den Bau-Dienst (server/worlds, docs/indoor/serverbau.md)
 *
 * ```
 * server-info                                         JSON: Welt-Version, Laser-Quellen
 * server-build --job <dir> --cache <dir> [--max-km n] [--max-points n]
 * server-request --gpx <datei> --out <request.json> [--laser] [--quality …]   (Probe/Entwicklung)
 * ```
 *
 * `server-build` liest `<job>/request.json` ([ServerWorldRequest], streng
 * geprueft), baut nach `<job>/world/` (ein abgebrochener Bau setzt dort fort),
 * prueft mit `checkWorld`, packt nach `<job>/package.zip` (Paketformat der
 * Katalogwelten, Kennung = Welt-ID, Version 1) und schreibt `<job>/result.json`.
 * Fortschritt als Zeilen `@progress {"phase":…,"done":…,"total":…}` auf stdout.
 * Schreibt nichts ausserhalb von `<job>` und `<cache>`; Netz nur ueber die
 * Positivlisten aus `:core` (Geodaten, Laser) und das Luftbild-Register des
 * Katalogbaus.
 */
fun serverInfo(): Int {
    val o = buildJsonObject {
        put("builderId", worldBuilderVersion)
        put("laserSources", buildJsonArray { laserSources.forEach { add(kotlinx.serialization.json.JsonPrimitive(it.id)) } })
        put("surfaceKinds", buildJsonArray { de.trailscape.core.indoor.SurfaceKind.entries.forEach { add(kotlinx.serialization.json.JsonPrimitive(it.name)) } })
    }
    println(o.toString())
    return 0
}

private fun writeResult(job: File, o: JsonObject) {
    val tmp = File(job, "result.json.tmp")
    tmp.writeText(o.toString())
    if (!tmp.renameTo(File(job, "result.json"))) File(job, "result.json").writeText(o.toString())
}

private fun fail(job: File, code: String, detail: String): Int {
    System.err.println("server-build: $code: $detail")
    writeResult(job, buildJsonObject {
        put("ok", false)
        put("error", buildJsonObject {
            put("code", code)
            put("detail", detail.take(500))
        })
    })
    return 1
}

fun serverBuild(
    jobDir: File,
    cacheDir: File,
    limits: ServerRouteLimits,
    aerialBand: de.trailscape.core.indoor.world.AerialBandOptions? = null,
): Int {
    if (!jobDir.isDirectory) return 2
    val job = jobDir.canonicalFile
    File(job, "result.json").delete()
    val request = try {
        ServerWorldRequest.parse(File(job, "request.json").readText(Charsets.UTF_8), limits)
    } catch (e: ServerRequestException) {
        return fail(job, e.code.name, e.message ?: "")
    } catch (e: java.io.IOException) {
        return fail(job, "INVALID", "request.json unlesbar")
    }
    if (request.builderId != worldBuilderVersion) {
        return fail(job, "BUILDER_MISMATCH", "Auftrag fuer ${request.builderId}, Werkzeug ist $worldBuilderVersion")
    }
    val route = request.route
    val preview = laserPreviewOf(route)
    val laserOpts = if (request.laser && preview.source != null) toolLaserOptions(cacheDir, offline = false, radiusM = null) else null
    val builder = createWorldBuilder(laserOpts, aerialBand)
    val register = aerialSources + catalogAerialSources
    val plan = builder.plan(route, request.quality).let { it.copy(aerial = aerialFor(route, register)) }
    if (request.expectedWorldId != null && request.expectedWorldId != plan.worldId) {
        return fail(job, "WORLD_ID_MISMATCH", "App erwartet ${request.expectedWorldId}, Werkzeug plant ${plan.worldId}")
    }
    val worldDir = File(job, "world")
    File(worldDir, WorldManifest.FILE_NAME).takeIf { it.isFile }?.let { f ->
        val old = runCatching { WorldManifest.parse(f.readText()) }.getOrNull()
        if (old == null || old.worldId != plan.worldId) worldDir.deleteRecursively()
    }
    println("@plan " + buildJsonObject {
        put("worldId", plan.worldId)
        put("tiles", plan.tileCount)
        put("laser", laserOpts != null)
        preview.source?.let { put("laserRegion", it.id) }
    })
    val pipeline = createGeoPipeline(JavaHttpFetcher(), cacheDir, register)
    val start = System.nanoTime()
    val listener = object : WorldBuildListener {
        private val last = HashMap<WorldBuildPhase, Long>()
        override fun onProgress(phase: WorldBuildPhase, done: Int, total: Int) {
            val now = System.nanoTime()
            val prev = last[phase]
            if (prev == null || done == total || now - prev > 1_000_000_000L) {
                last[phase] = now
                println("@progress {\"phase\":\"${phase.name}\",\"done\":$done,\"total\":$total}")
            }
        }
    }
    val manifest = try {
        builder.build(plan, WorldBuildDeps(pipeline, ImageIoDecoder), worldDir, listener)
    } catch (e: WorldBuildFailed) {
        return fail(job, "BUILD_FAILED", "${e.reason}: ${e.message} ${e.cause ?: ""}")
    } catch (e: OutOfMemoryError) {
        return fail(job, "OUT_OF_MEMORY", "Heap zu klein (JAVA_OPTS -Xmx erhoehen)")
    }
    val problems = checkWorld(worldDir)
    if (problems.isNotEmpty()) return fail(job, "CHECK_FAILED", problems.take(5).joinToString("; "))
    val pkg = File(job, "package.zip")
    writeCatalogPackage(worldDir, manifest.worldId, 1, pkg)
    val stats = laserBuildStats(builder)
    val seconds = (System.nanoTime() - start) / 1e9
    val rt = Runtime.getRuntime()
    writeResult(job, buildJsonObject {
        put("ok", true)
        put("worldId", manifest.worldId)
        put("packageBytes", pkg.length())
        put("sha256", sha256Hex(pkg))
        put("diskBytes", manifest.budget.diskBytes)
        put("tiles", manifest.tiles.size)
        put("aerialTiles", manifest.tiles.count { it.aerial })
        put("lengthM", manifest.track.lengthM)
        put("buildSeconds", Math.round(seconds * 10) / 10.0)
        put("heapUsedBytes", rt.totalMemory() - rt.freeMemory())
        stats["quelle"]?.let { put("laserSource", it) }
        put("laser", buildJsonObject { stats.forEach { (k, v) -> put(k, v) } })
        put("attribution", buildJsonArray { manifest.attribution.forEach { add(it.toJson()) } })
        put("aerial", manifest.attribution.filter { it.source == GeoSourceKind.AERIAL }.joinToString("; ") { it.text })
    })
    println("@done ${manifest.worldId} ${pkg.length()} B in ${"%.1f".format(seconds)} s")
    // Die entpackte Welt braucht der Dienst nicht mehr: Platz sparen.
    worldDir.deleteRecursively()
    return 0
}

/** Auftrag aus einer GPX-Datei (fuer Proben und die Ende-zu-Ende-Pruefung). */
fun serverRequest(
    gpx: File,
    out: File,
    laser: Boolean,
    quality: de.trailscape.core.indoor.world.IndoorQuality,
    fromKm: Double? = null,
    toKm: Double? = null,
): Int {
    val (name, all) = readGpx(gpx)
    val full = cumulative(all).last()
    val fromM = (fromKm ?: 0.0) * 1000
    val toM = minOf(full, (toKm ?: (full / 1000)) * 1000)
    val points = if (fromM > 0 || toM < full) cutPoints(all, fromM, toM) else all
    val surfaces = sidecarOf(gpx)?.let(::readSurfaceFile)
    val (route, _) = indoorRouteOf(name, points, surfaces, full, fromM, toM)
    val plan = createWorldBuilder().plan(route, quality)
    out.parentFile?.mkdirs()
    out.writeText(ServerWorldRequest(worldBuilderVersion, quality, laser, route, plan.worldId).toJson())
    println("Auftrag ${out.path}: ${"%.2f".format(route.lengthM / 1000)} km, ${route.points.size} Punkte, Welt ${plan.worldId}, ${out.length()} B")
    return 0
}
