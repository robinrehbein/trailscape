package de.trailscape.tool.indoor

import de.trailscape.core.indoor.world.IndoorQuality
import de.trailscape.core.indoor.world.WorldBuildCancelled
import de.trailscape.core.indoor.world.WorldBuildDeps
import de.trailscape.core.indoor.world.WorldBuildFailed
import de.trailscape.core.indoor.world.WorldBuildListener
import de.trailscape.core.indoor.world.WorldBuildPhase
import de.trailscape.core.indoor.world.WorldManifest
import de.trailscape.core.indoor.world.WorldPlan
import de.trailscape.core.indoor.world.checkWorld
import de.trailscape.core.indoor.world.createGeoPipeline
import de.trailscape.core.indoor.world.createWorldBuilder
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import java.lang.reflect.Modifier
import kotlin.system.exitProcess

/**
 * # Entwicklerwerkzeug des Indoor-Trainings (docs/indoor.md 9)
 *
 * ```
 * build --gpx <datei> [--quality LOW|MEDIUM|HIGH] --out <dir> [--surfaces <json>]
 *       [--from-km a --to-km b] [--no-aerial] [--offline]
 * plan  --gpx <datei> [--quality …] [--from-km a --to-km b] [--no-aerial]
 * testworld --out <dir> [--quality …]
 * check <dir>
 * models --out <dir>
 * ```
 * Relative Pfade gelten ab dem Projektverzeichnis (`:indoor-dev:run` startet dort).
 * Exit-Code 0 = in Ordnung, 1 = Bau gescheitert oder Vertragsverstoesse, 2 = falscher Aufruf.
 */
fun main(args: Array<String>) {
    // Umlaute der Quellenangaben auch bei POSIX-Locale lesbar ausgeben
    System.setOut(PrintStream(FileOutputStream(FileDescriptor.out), true, "UTF-8"))
    System.setErr(PrintStream(FileOutputStream(FileDescriptor.err), true, "UTF-8"))
    val code = try {
        run(args.toList())
    } catch (e: UsageError) {
        System.err.println("indoor-dev: ${e.message}")
        System.err.println(USAGE)
        2
    }
    exitProcess(code)
}

private const val USAGE = """Aufruf:
  build --gpx <datei> [--quality LOW|MEDIUM|HIGH] --out <dir> [--surfaces <json>] [--from-km a --to-km b] [--no-aerial] [--offline]
  plan --gpx <datei> [--quality LOW|MEDIUM|HIGH] [--from-km a --to-km b] [--no-aerial]
  testworld --out <dir> [--quality LOW|MEDIUM|HIGH]
  check <dir>
  models --out <dir>"""

/** Cache der Geodaten (git-ignoriert). */
private const val CACHE_DIR = "tool/indoor-dev/cache"

private class UsageError(message: String) : Exception(message)

/** Optionen `--name wert` und Schalter `--name`. */
private class Args(list: List<String>, private val flags: Set<String>) {
    val positional = ArrayList<String>()
    private val values = HashMap<String, String>()
    private val set = HashSet<String>()

    init {
        var i = 0
        while (i < list.size) {
            val a = list[i]
            when {
                a.startsWith("--") && a.removePrefix("--") in flags -> set += a.removePrefix("--")
                a.startsWith("--") -> {
                    if (i + 1 >= list.size) throw UsageError("$a braucht einen Wert")
                    values[a.removePrefix("--")] = list[i + 1]
                    i++
                }
                else -> positional += a
            }
            i++
        }
    }

    fun opt(name: String): String? = values[name]
    fun req(name: String): String = values[name] ?: throw UsageError("--$name fehlt")
    fun flag(name: String): Boolean = name in set
    fun double(name: String): Double? = values[name]?.let { it.replace(',', '.').toDoubleOrNull() ?: throw UsageError("--$name: keine Zahl: $it") }
    fun quality(): IndoorQuality = opt("quality")?.let { IndoorQuality.fromNameOrNull(it.uppercase()) ?: throw UsageError("--quality: LOW, MEDIUM oder HIGH") }
        ?: IndoorQuality.MEDIUM
}

private fun run(all: List<String>): Int {
    val cmd = all.firstOrNull() ?: throw UsageError("Befehl fehlt")
    val rest = all.drop(1)
    return when (cmd) {
        "build" -> build(Args(rest, setOf("no-aerial", "offline")))
        "plan" -> plan(Args(rest, setOf("no-aerial", "offline")))
        "testworld" -> testworld(Args(rest, emptySet()))
        "check" -> check(File(Args(rest, emptySet()).positional.firstOrNull() ?: throw UsageError("Weltverzeichnis fehlt")))
        "models" -> models(Args(rest, emptySet()))
        else -> throw UsageError("unbekannter Befehl: $cmd")
    }
}

// ---------------------------------------------------------------------------
// Strecke und Plan
// ---------------------------------------------------------------------------

private class Prepared(val plan: WorldPlan, val routeFromCore: Boolean, val surfaceFile: File?)

private fun prepare(a: Args): Prepared {
    val gpx = File(a.req("gpx"))
    if (!gpx.isFile) throw UsageError("GPX-Datei nicht gefunden: $gpx")
    val (name, points) = readGpx(gpx)
    if (points.size < 2) throw UsageError("GPX-Datei hat keine Strecke: $gpx")
    val fullLen = cumulative(points).last()
    val fromM = (a.double("from-km") ?: 0.0) * 1000
    val toM = a.double("to-km")?.let { it * 1000 } ?: fullLen
    if (fromM < 0 || toM > fullLen + 1 || toM - fromM < 200) throw UsageError("Ausschnitt ${fromM.toInt()}..${toM.toInt()} m passt nicht zur Strecke (${fullLen.toInt()} m)")
    val cutTo = minOf(toM, fullLen)
    val cut = if (fromM > 0 || cutTo < fullLen) cutPoints(points, fromM, cutTo) else points
    val sfile = a.opt("surfaces")?.let(::File) ?: sidecarOf(gpx)
    val surfaces = sfile?.let(::readSurfaceFile)
    val label = if (fromM > 0 || cutTo < fullLen) "$name (km ${"%.1f".format(fromM / 1000)}–${"%.1f".format(cutTo / 1000)})" else name
    val (route, fromCore) = indoorRouteOf(label, cut, surfaces, fullLen, fromM, cutTo)
    var plan = createWorldBuilder().plan(route, a.quality())
    if (a.flag("no-aerial")) plan = plan.copy(aerial = emptyList())
    return Prepared(plan, fromCore, sfile)
}

private fun printPlan(p: Prepared) {
    val plan = p.plan
    val r = plan.route
    println("Strecke: ${r.name} — ${"%.2f".format(r.lengthM / 1000)} km, ${r.points.size} Punkte, Runde: ${if (r.isLoop) "ja" else "nein"}")
    println("  gebaut mit: ${if (p.routeFromCore) "buildIndoorRoute (:core)" else "eigenem Rueckfall des Werkzeugs (buildIndoorRoute ist noch Platzhalter)"}")
    println("  Belag: ${r.source}${p.surfaceFile?.let { " aus ${it.path}" } ?: ""}, ${r.surfaces.size} Abschnitte")
    println("Plan ${plan.worldId} (${plan.quality}): ${plan.tileCount} Kacheln")
    println("  Anfragen ohne Cache: " + plan.requests.entries.joinToString(", ") { "${it.key.name.lowercase()} ${it.value}" })
    println("  Download geschaetzt: ${mb(plan.downloadBytes)}, Platz geschaetzt: ${mb(plan.diskBytes)}")
    println("  Hosts: ${plan.hosts.joinToString(", ")}")
    println("  Luftbild: ${plan.aerial.joinToString(", ") { it.label }.ifEmpty { "keines (erzeugter Boden)" }}")
}

private fun mb(bytes: Long) = "%.1f MB".format(bytes / 1_048_576.0)

private fun plan(a: Args): Int {
    printPlan(prepare(a))
    return 0
}

// ---------------------------------------------------------------------------
// Bau
// ---------------------------------------------------------------------------

/** Fortschritt auf der Konsole: je Phase beim ersten Mal, am Ende und sonst hoechstens alle 2 s. */
private class ConsoleListener : WorldBuildListener {
    private val last = HashMap<WorldBuildPhase, Long>()
    private val start = System.nanoTime()

    override fun onProgress(phase: WorldBuildPhase, done: Int, total: Int) {
        val now = System.nanoTime()
        val prev = last[phase]
        if (prev == null || done == total || now - prev > 2_000_000_000L) {
            println("  [%6.1f s] %-9s %d/%d".format((now - start) / 1e9, phase.name, done, total))
            last[phase] = now
        }
    }
}

private fun build(a: Args): Int {
    val out = File(a.req("out"))
    val prepared = prepare(a)
    printPlan(prepared)
    val raw = if (a.flag("offline")) OfflineFetcher() else JavaHttpFetcher()
    val counting = CountingFetcher(createGeoPipeline(raw, File(CACHE_DIR)))
    val start = System.nanoTime()
    println("Baue nach ${out.path} …")
    val manifest = try {
        createWorldBuilder().build(prepared.plan, WorldBuildDeps(counting, ImageIoDecoder), out, ConsoleListener())
    } catch (e: WorldBuildFailed) {
        System.err.println("Bau gescheitert: ${e.message}")
        e.cause?.let { c ->
            System.err.println("  Ursache: $c")
            c.stackTrace.take(12).forEach { System.err.println("    at $it") }
        }
        return 1
    } catch (e: WorldBuildCancelled) {
        System.err.println("Bau abgebrochen")
        return 1
    }
    val seconds = (System.nanoTime() - start) / 1e9
    summary(manifest, out, seconds)
    println("Anfragen: ${counting.summary()}")
    return checkAndReport(out)
}

private fun summary(m: WorldManifest, out: File, seconds: Double) {
    println("Fertig in ${"%.1f".format(seconds)} s: ${m.worldId}")
    val byLevel = m.tiles.groupBy { it.level }.toSortedMap()
    for ((level, tiles) in byLevel) {
        println(
            "  Stufe $level: ${tiles.size} Kacheln, ${mb(tiles.sumOf { it.bytes })}, " +
                "Luftbild ${tiles.count { it.aerial }}, unvollstaendig ${tiles.count { it.degraded }}",
        )
    }
    val q = m.quality
    println(
        "  Budget: Texturen ${mb(m.budget.maxTextureBytes)} von ${mb(q.maxTextureBytes)}, " +
            "Dreiecke ${m.budget.maxTriangles} von ${q.maxTriangles}, Platte ${mb(m.budget.diskBytes)} " +
            "(${mb((m.budget.diskBytes / (m.track.lengthM / 1000)).toLong())} je km)",
    )
    println("  Strecke: ${"%.2f".format(m.track.lengthM / 1000)} km, Belag ${m.track.source}, Band ${m.track.sampleCount} Proben")
    m.attribution.forEach { println("  Quelle (${it.source}): ${it.text} [${it.license}]") }
    println("  Verzeichnis: ${out.absolutePath}")
}

private fun checkAndReport(dir: File): Int {
    val problems = checkWorld(dir)
    if (problems.isEmpty()) {
        println("Pruefung: in Ordnung")
        return 0
    }
    System.err.println("Pruefung: ${problems.size} Verstoesse")
    problems.forEach { System.err.println("  - $it") }
    return 1
}

private fun testworld(a: Args): Int {
    val out = File(a.req("out"))
    val start = System.nanoTime()
    val m = createWorldBuilder().buildTestWorld(out, a.quality())
    summary(m, out, (System.nanoTime() - start) / 1e9)
    return checkAndReport(out)
}

private fun check(dir: File): Int {
    if (!dir.isDirectory) throw UsageError("Weltverzeichnis nicht gefunden: $dir")
    return checkAndReport(dir)
}

// ---------------------------------------------------------------------------
// Modelle
// ---------------------------------------------------------------------------

/**
 * Schreibt `rider.glb`/`ghost.glb` ueber `writeIndoorModels(dir: File)` aus
 * `RiderModel.kt` in `:core` — per Reflection, denn die Funktion entsteht
 * parallel in einem anderen Arbeitspaket; fehlt sie, sagt das Werkzeug das.
 */
private fun models(a: Args): Int {
    val out = File(a.req("out"))
    val cls = runCatching { Class.forName("de.trailscape.core.indoor.world.RiderModelKt") }.getOrNull()
    val method = cls?.declaredMethods?.firstOrNull { m ->
        (m.name == "writeIndoorModels" || m.name.startsWith("writeIndoorModels$")) &&
            Modifier.isStatic(m.modifiers) && m.parameterTypes.size == 1 && m.parameterTypes[0] == File::class.java
    }
    if (method == null) {
        System.err.println("models: writeIndoorModels(dir: File) gibt es in diesem Stand von :core noch nicht (RiderModel.kt fehlt).")
        return 1
    }
    out.mkdirs()
    method.isAccessible = true
    method.invoke(null, out)
    println("Modelle geschrieben nach ${out.absolutePath}: ${out.listFiles()?.joinToString(", ") { "${it.name} (${it.length()} B)" }}")
    return 0
}
