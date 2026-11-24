package de.trailscape.core.indoor.server

import de.trailscape.core.indoor.IndoorRoute
import de.trailscape.core.indoor.IndoorRoutePoint
import de.trailscape.core.indoor.IndoorRouteSource
import de.trailscape.core.indoor.SurfaceKind
import de.trailscape.core.indoor.SurfaceSection
import de.trailscape.core.indoor.world.IndoorQuality
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * # Auftrag an den Bau-Dienst (docs/indoor/serverbau.md)
 *
 * Die App schickt dem eigenen Server **genau die Strecke**, die sie auch auf
 * dem Geraet bauen wuerde (`IndoorRoute` nach dem Belagsabgleich). Dadurch
 * ergibt der Server-Bau dieselbe Welt-ID wie der Geraete-Bau (die ID haengt nur
 * an Geometrie, Belag, Qualitaet und Welt-Version, nicht an der Datenstufe) —
 * das Paket ersetzt die Geraetewelt derselben Strecke.
 *
 * Inhalt: nur Koordinaten, Laufmeter, Hoehen, Belagsabschnitte und ein Name.
 * Keine Zeiten, kein Puls, keine Tour-ID (die `id` der Strecke ist ein Hash der
 * Geometrie bzw. wird hier durch eine feste Kennung ersetzt).
 *
 * JSON (Format 1):
 * ```
 * {"format":1, "builderId":"indoor-world/11", "quality":"MEDIUM", "laser":true,
 *  "expectedWorldId":"w…",
 *  "route":{"name":"…", "isLoop":false, "source":"BROUTER_REMATCH",
 *           "points":[[distM, lat, lon, eleM], …],
 *           "surfaces":[{"fromM":0, "toM":120.5, "kind":"ASPHALT", "highway":"residential", "widthM":5.0}, …]}}
 * ```
 * Der Bau-Dienst prueft dasselbe noch einmal in JavaScript (server/worlds);
 * [parse] ist die Pruefung des Werkzeugs und der Tests.
 */
data class ServerWorldRequest(
    val builderId: String,
    val quality: IndoorQuality,
    val laser: Boolean,
    val route: IndoorRoute,
    /** Welt-ID, die die App fuer diese Strecke ausgerechnet hat; der Server prueft sie nach dem Bau. */
    val expectedWorldId: String? = null,
) {
    fun toJson(): String = buildJsonObject {
        put("format", FORMAT)
        put("builderId", builderId)
        put("quality", quality.name)
        put("laser", laser)
        expectedWorldId?.let { put("expectedWorldId", it) }
        put(
            "route",
            buildJsonObject {
                put("name", cleanName(route.name))
                put("isLoop", route.isLoop)
                put("source", route.source.name)
                put(
                    "points",
                    buildJsonArray {
                        route.points.forEach { p ->
                            add(
                                buildJsonArray {
                                    add(JsonPrimitive(p.distM))
                                    add(JsonPrimitive(p.lat))
                                    add(JsonPrimitive(p.lon))
                                    add(JsonPrimitive(p.eleM))
                                },
                            )
                        }
                    },
                )
                put(
                    "surfaces",
                    buildJsonArray {
                        route.surfaces.forEach { s ->
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
            },
        )
    }.toString()

    companion object {
        const val FORMAT: Int = 1

        /** Feste Kennung der Strecke im Auftrag (die echte `id` der App bleibt auf dem Geraet). */
        const val ROUTE_ID: String = "server"

        /**
         * Liest und prueft einen Auftrag. Wirft [ServerRequestException] mit
         * einem Code, den auch der Bau-Dienst benutzt.
         */
        fun parse(text: String, limits: ServerRouteLimits = ServerRouteLimits()): ServerWorldRequest {
            if (text.length > limits.maxBodyBytes) throw ServerRequestException(ServerRequestError.TOO_LARGE, "Auftrag ${text.length} Zeichen")
            val o = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
                ?: throw ServerRequestException(ServerRequestError.INVALID, "kein JSON-Objekt")
            val format = (o["format"] as? JsonPrimitive)?.intOrNull
            if (format != FORMAT) throw ServerRequestException(ServerRequestError.INVALID, "Format $format unbekannt")
            val builderId = str(o, "builderId") ?: throw ServerRequestException(ServerRequestError.INVALID, "builderId fehlt")
            if (!BUILDER_ID.matches(builderId)) throw ServerRequestException(ServerRequestError.INVALID, "builderId unzulaessig")
            val quality = IndoorQuality.fromNameOrNull(str(o, "quality"))
                ?: throw ServerRequestException(ServerRequestError.INVALID, "quality unbekannt")
            val laser = (o["laser"] as? JsonPrimitive)?.booleanOrNull ?: false
            val expected = str(o, "expectedWorldId")?.also {
                if (!WORLD_ID.matches(it)) throw ServerRequestException(ServerRequestError.INVALID, "expectedWorldId unzulaessig")
            }
            val r = o["route"] as? JsonObject ?: throw ServerRequestException(ServerRequestError.INVALID, "route fehlt")
            return ServerWorldRequest(builderId, quality, laser, parseRoute(r, limits), expected)
        }

        private fun str(o: JsonObject, k: String): String? = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

        private fun num(e: Any?): Double? = (e as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() }

        private fun parseRoute(r: JsonObject, limits: ServerRouteLimits): IndoorRoute {
            fun bad(detail: String): Nothing = throw ServerRequestException(ServerRequestError.INVALID, detail)
            val name = cleanName(str(r, "name") ?: "")
            val isLoop = (r["isLoop"] as? JsonPrimitive)?.booleanOrNull ?: bad("isLoop fehlt")
            val source = IndoorRouteSource.entries.firstOrNull { it.name == str(r, "source") } ?: bad("source unbekannt")
            val rawPoints = r["points"] as? JsonArray ?: bad("points fehlt")
            if (rawPoints.size < 2) bad("weniger als 2 Punkte")
            if (rawPoints.size > limits.maxPoints) {
                throw ServerRequestException(ServerRequestError.TOO_MANY_POINTS, "${rawPoints.size} Punkte (hoechstens ${limits.maxPoints})")
            }
            val points = ArrayList<IndoorRoutePoint>(rawPoints.size)
            var prev = -1.0
            for ((i, e) in rawPoints.withIndex()) {
                val a = e as? JsonArray ?: bad("Punkt $i kein Feld")
                if (a.size != 4) bad("Punkt $i hat ${a.size} Werte")
                val d = num(a[0]) ?: bad("Punkt $i: distM")
                val lat = num(a[1]) ?: bad("Punkt $i: lat")
                val lon = num(a[2]) ?: bad("Punkt $i: lon")
                val ele = num(a[3]) ?: bad("Punkt $i: eleM")
                if (lat !in -85.0..85.0 || lon !in -180.0..180.0) bad("Punkt $i ausserhalb")
                if (ele !in -500.0..9000.0) bad("Punkt $i: Hoehe $ele")
                if (i == 0 && d != 0.0) bad("erster Punkt nicht bei 0 m")
                if (d < prev) bad("Laufmeter fallen bei Punkt $i")
                prev = d
                points += IndoorRoutePoint(d, lat, lon, ele)
            }
            val length = points.last().distM
            if (length < 100.0) bad("Strecke kuerzer als 100 m")
            if (length > limits.maxLengthM) {
                throw ServerRequestException(ServerRequestError.TOO_LONG, "${length.toInt()} m (hoechstens ${limits.maxLengthM.toInt()} m)")
            }
            val rawSurfaces = r["surfaces"] as? JsonArray ?: bad("surfaces fehlt")
            if (rawSurfaces.isEmpty() || rawSurfaces.size > limits.maxSurfaces) bad("${rawSurfaces.size} Belagsabschnitte")
            val surfaces = ArrayList<SurfaceSection>(rawSurfaces.size)
            for ((i, e) in rawSurfaces.withIndex()) {
                val s = e as? JsonObject ?: bad("Belag $i kein Objekt")
                val from = num(s["fromM"]) ?: bad("Belag $i: fromM")
                val to = num(s["toM"]) ?: bad("Belag $i: toM")
                val kind = SurfaceKind.fromNameOrNull(str(s, "kind")) ?: bad("Belag $i: kind")
                val highway = when (s["highway"]) {
                    null, JsonNull -> null
                    else -> str(s, "highway")?.takeIf { HIGHWAY.matches(it) } ?: bad("Belag $i: highway")
                }
                val width = when (s["widthM"]) {
                    null, JsonNull -> null
                    else -> num(s["widthM"])?.takeIf { it > 0 && it <= 60 } ?: bad("Belag $i: widthM")
                }
                if (to < from) bad("Belag $i rueckwaerts")
                val expectedFrom = surfaces.lastOrNull()?.toM ?: 0.0
                if (kotlin.math.abs(from - expectedFrom) > 0.5) bad("Belag $i schliesst nicht an")
                surfaces += SurfaceSection(from, to, kind, highway, width)
            }
            if (kotlin.math.abs(surfaces.last().toM - length) > 1.0) bad("Belag endet nicht am Streckenende")
            return IndoorRoute(ROUTE_ID, name, points, surfaces, isLoop, source)
        }

        /** Name ohne Steuerzeichen, hoechstens 120 Zeichen. */
        fun cleanName(name: String): String =
            name.filter { !it.isISOControl() && it != '\u2028' && it != '\u2029' }.trim().take(120)

        private val BUILDER_ID = Regex("^[a-z0-9-]{1,40}/[0-9]{1,6}$")
        private val WORLD_ID = Regex("^w[0-9a-f]{24}$")
        private val HIGHWAY = Regex("^[a-z_]{1,40}$")
    }
}

/** Grenzen eines Auftrags (dieselben Vorgaben hat der Bau-Dienst). */
data class ServerRouteLimits(
    val maxPoints: Int = 40_000,
    val maxLengthM: Double = 100_000.0,
    val maxSurfaces: Int = 20_000,
    val maxBodyBytes: Int = 4 * 1024 * 1024,
)

/** Fehlercodes eines Auftrags — dieselben Namen liefert der Bau-Dienst in `error.code`. */
enum class ServerRequestError { INVALID, TOO_LARGE, TOO_LONG, TOO_MANY_POINTS }

class ServerRequestException(val code: ServerRequestError, detail: String) : IllegalArgumentException("Auftrag: $detail")
