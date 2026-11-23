package de.trailscape.tool.indoor

import de.trailscape.core.TrackPoint
import de.trailscape.core.haversineM
import de.trailscape.core.i18n.CoreTextsDe
import de.trailscape.core.indoor.IndoorRoute
import de.trailscape.core.indoor.IndoorRoutePoint
import de.trailscape.core.indoor.IndoorRouteSource
import de.trailscape.core.indoor.SurfaceKind
import de.trailscape.core.indoor.SurfaceSection
import de.trailscape.core.indoor.buildIndoorRoute
import de.trailscape.core.parseGpx
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.io.File
import java.security.MessageDigest
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Strecke fuer das Werkzeug: GPX lesen (ueber `:core`), Belag aus der
 * Beidatei `<name>.surfaces.json`, `IndoorRoute` bauen — mit
 * `buildIndoorRoute` aus `:core`, solange der noch Platzhalter ist mit
 * eigenem Rueckfall (neu abtasten ≤ 10 m, Runde bei ≤ 60 m, Laufmeter aus
 * Haversine).
 */

/** Belagsabschnitte einer Beidatei, in deren eigenen Laufmetern. */
class SurfaceFile(val source: IndoorRouteSource, val lengthM: Double, val sections: List<SurfaceSection>)

/**
 * Liest `{source, lengthM, sections:[{fromM, toM, kind, highway?, widthM?}]}`
 * (Format der Dateien unter `samples/`).
 */
fun readSurfaceFile(file: File): SurfaceFile {
    val o = Json.parseToJsonElement(file.readText()) as JsonObject
    fun num(o: JsonObject, k: String): Double? = (o[k] as? JsonPrimitive)?.doubleOrNull
    fun str(o: JsonObject, k: String): String? = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    val sections = (o["sections"] as JsonArray).map {
        val s = it as JsonObject
        SurfaceSection(
            fromM = num(s, "fromM") ?: error("fromM fehlt"),
            toM = num(s, "toM") ?: error("toM fehlt"),
            kind = SurfaceKind.fromNameOrNull(str(s, "kind")) ?: SurfaceKind.UNKNOWN,
            highway = str(s, "highway"),
            widthM = num(s, "widthM"),
        )
    }
    val source = IndoorRouteSource.entries.firstOrNull { it.name == str(o, "source") } ?: IndoorRouteSource.BROUTER_WAY_TAGS
    return SurfaceFile(source, num(o, "lengthM") ?: sections.last().toM, sections)
}

/** Beidatei neben der GPX-Datei (`x.gpx` → `x.surfaces.json`), falls vorhanden. */
fun sidecarOf(gpx: File): File? = File(gpx.parentFile, gpx.name.removeSuffix(".gpx") + ".surfaces.json").takeIf { it.isFile }

/** GPX lesen: Name (oder Dateiname) und Punkte. */
fun readGpx(file: File): Pair<String, List<TrackPoint>> {
    val r = parseGpx(file.readText(), CoreTextsDe)
    return (r.name ?: file.nameWithoutExtension) to r.points
}

/** Laufmeter je Punkt (Haversine). */
fun cumulative(points: List<TrackPoint>): DoubleArray {
    val d = DoubleArray(points.size)
    for (i in 1 until points.size) d[i] = d[i - 1] + haversineM(points[i - 1], points[i])
    return d
}

/** Ausschnitt `fromM .. toM` (Laufmeter der GPX) mit eingefuegten Endpunkten. */
fun cutPoints(points: List<TrackPoint>, fromM: Double, toM: Double): List<TrackPoint> {
    val d = cumulative(points)
    fun at(s: Double): TrackPoint {
        var i = 1
        while (i < d.size - 1 && d[i] < s) i++
        val t = if (d[i] > d[i - 1]) ((s - d[i - 1]) / (d[i] - d[i - 1])).coerceIn(0.0, 1.0) else 0.0
        val a = points[i - 1]
        val b = points[i]
        val ele = if (a.ele != null && b.ele != null) a.ele!! + (b.ele!! - a.ele!!) * t else a.ele ?: b.ele
        return TrackPoint(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t, ele)
    }
    val out = ArrayList<TrackPoint>()
    out += at(fromM)
    for (i in points.indices) if (d[i] > fromM && d[i] < toM) out += points[i]
    out += at(toM)
    return out
}

/**
 * Belaege der Beidatei anteilig auf [targetLengthM] gestreckt; mit
 * [cutFromM]/[cutToM] (in Metern der gestreckten Strecke) ausgeschnitten und
 * auf 0 verschoben.
 */
fun stretchSurfaces(file: SurfaceFile, fullLengthM: Double, cutFromM: Double, cutToM: Double): List<SurfaceSection> {
    val f = fullLengthM / file.lengthM
    val out = ArrayList<SurfaceSection>()
    for (s in file.sections) {
        val a = max(s.fromM * f, cutFromM)
        val b = min(s.toM * f, cutToM)
        if (b > a) out += s.copy(fromM = a - cutFromM, toM = b - cutFromM)
    }
    if (out.isEmpty()) return listOf(SurfaceSection(0.0, cutToM - cutFromM, SurfaceKind.UNKNOWN))
    out[0] = out[0].copy(fromM = 0.0)
    out[out.size - 1] = out.last().copy(toM = cutToM - cutFromM)
    for (i in 1 until out.size) out[i] = out[i].copy(fromM = out[i - 1].toM)
    return out
}

/**
 * Die `IndoorRoute` aus GPX-Punkten ([points] = schon ausgeschnittener Teil
 * `cutFromM .. cutToM` der ganzen GPX-Strecke mit [fullLengthM]). Erst
 * `buildIndoorRoute` aus `:core`; wirft der noch [NotImplementedError], baut
 * [fallbackRoute] sie selbst. Die Belaege setzt danach die Beidatei
 * [surfaces]: auf die ganze GPX-Laenge gestreckt, ausgeschnitten, auf die
 * Laenge der Strecke gebracht. Zweiter Wert: ob `:core` die Strecke gebaut hat.
 */
fun indoorRouteOf(
    name: String,
    points: List<TrackPoint>,
    surfaces: SurfaceFile?,
    fullLengthM: Double,
    cutFromM: Double,
    cutToM: Double,
): Pair<IndoorRoute, Boolean> {
    val (base, fromCore) = try {
        (buildIndoorRoute(name, points, null, IndoorRouteSource.NONE) ?: error("Strecke ist nicht fahrbar (zu kurz?)")) to true
    } catch (_: NotImplementedError) {
        fallbackRoute(name, points) to false
    }
    if (surfaces == null) return base to fromCore
    val len = base.lengthM
    val scale = len / (cutToM - cutFromM)
    val sections = stretchSurfaces(surfaces, fullLengthM, cutFromM, cutToM)
        .map { it.copy(fromM = it.fromM * scale, toM = it.toM * scale) }
        .toMutableList()
    sections[sections.size - 1] = sections.last().copy(toM = len)
    return base.copy(surfaces = sections, source = surfaces.source) to fromCore
}

/** Eigener Rueckfall: gleichmaessig ≤ 10 m, Hoehen geglaettet und auf ±25 % begrenzt. */
fun fallbackRoute(name: String, points: List<TrackPoint>): IndoorRoute {
    val pts = points.filterIndexed { i, p -> i == 0 || haversineM(points[i - 1], p) > 0.01 }
    require(pts.size >= 2) { "Strecke hat weniger als zwei verschiedene Punkte" }
    val d = cumulative(pts)
    val len = d.last()
    val count = max(1, ceil(len / 10.0).toInt())
    val step = len / count
    val known = pts.indices.filter { pts[it].ele != null }
    fun eleAt(i: Int, t: Double): Double {
        if (known.isEmpty()) return 0.0
        fun e(k: Int): Double = pts[k].ele ?: pts[known.minBy { kotlin.math.abs(it - k) }].ele!!
        return e(i - 1) + (e(i) - e(i - 1)) * t
    }
    val out = ArrayList<IndoorRoutePoint>(count + 1)
    var j = 1
    for (k in 0..count) {
        val s = if (k == count) len else k * step
        while (j < d.size - 1 && d[j] < s) j++
        val t = if (d[j] > d[j - 1]) ((s - d[j - 1]) / (d[j] - d[j - 1])).coerceIn(0.0, 1.0) else 0.0
        val a = pts[j - 1]
        val b = pts[j]
        out += IndoorRoutePoint(s, a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t, eleAt(j, t))
    }
    // Hoehen glaetten (±3 Punkte) und Steigung begrenzen.
    val ele = DoubleArray(out.size) { i ->
        var sum = 0.0
        var c = 0
        for (o in -3..3) {
            val q = i + o
            if (q in out.indices) { sum += out[q].eleM; c++ }
        }
        sum / c
    }
    repeat(50) {
        for (i in 0 until ele.size - 1) {
            val lim = 0.24 * (out[i + 1].distM - out[i].distM)
            val du = ele[i + 1] - ele[i]
            if (du > lim) ele[i + 1] = ele[i] + lim
            if (du < -lim) ele[i + 1] = ele[i] - lim
        }
    }
    val pointsOut = out.mapIndexed { i, p -> p.copy(eleM = ele[i]) }
    val loop = haversineM(pts.first(), pts.last()) <= IndoorRoute.LOOP_CLOSE_M
    val md = MessageDigest.getInstance("SHA-256")
    for (p in pointsOut) md.update("${Math.round(p.lat * 1e6)},${Math.round(p.lon * 1e6)};".toByteArray())
    val id = "gpx-" + md.digest().take(8).joinToString("") { "%02x".format(it) }
    return IndoorRoute(id, name, pointsOut, listOf(SurfaceSection(0.0, len, SurfaceKind.UNKNOWN)), loop, IndoorRouteSource.NONE)
}
