package de.trailscape.core.indoor.server

import de.trailscape.core.HttpClient
import de.trailscape.core.HttpMethod
import de.trailscape.core.HttpRequest
import de.trailscape.core.HttpResponse
import de.trailscape.core.SyncConfig
import de.trailscape.core.indoor.IndoorRoute
import de.trailscape.core.indoor.IndoorRoutePoint
import de.trailscape.core.indoor.IndoorRouteSource
import de.trailscape.core.indoor.SurfaceKind
import de.trailscape.core.indoor.SurfaceSection
import de.trailscape.core.indoor.world.IndoorQuality
import de.trailscape.core.indoor.world.createWorldBuilder
import de.trailscape.core.indoor.world.worldBuilderGeneration
import de.trailscape.core.indoor.world.worldBuilderVersion
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerWorldTest {

    /** Eine Strecke von [startLat]/[startLon] aus nach Osten (rund [km] km). */
    private fun route(startLat: Double = 51.05, startLon: Double = 7.13, km: Double = 3.0, name: String = "Altenberg"): IndoorRoute {
        val n = (km * 100).toInt()
        val dLon = 0.01 / 0.7 / 100 * 1.0 // ~10 m je Punkt bei 51° N
        val pts = (0..n).map { i -> IndoorRoutePoint(i * 10.0, startLat + i * 1e-6, startLon + i * dLon, 100.0 + i * 0.05) }
        val len = pts.last().distM
        return IndoorRoute(
            "r-geheim", name, pts,
            listOf(
                SurfaceSection(0.0, len / 3, SurfaceKind.ASPHALT, "residential", 5.5),
                SurfaceSection(len / 3, len, SurfaceKind.GRAVEL),
            ),
            isLoop = false, source = IndoorRouteSource.BROUTER_REMATCH,
        )
    }

    @Test
    fun auftragRundreiseBehaeltDieWeltId() {
        val r = route()
        val req = ServerWorldRequest(worldBuilderVersion, IndoorQuality.MEDIUM, laser = true, route = r, expectedWorldId = "w" + "a".repeat(24))
        val back = ServerWorldRequest.parse(req.toJson())
        assertEquals(req.builderId, back.builderId)
        assertEquals(true, back.laser)
        assertEquals(r.points, back.route.points)
        assertEquals(r.surfaces, back.route.surfaces)
        assertEquals(ServerWorldRequest.ROUTE_ID, back.route.id)
        // Welt-ID haengt nicht an der Routen-ID: Server und Geraet bauen dieselbe Welt.
        val b = createWorldBuilder()
        assertEquals(b.plan(r, IndoorQuality.MEDIUM).worldId, b.plan(back.route, IndoorQuality.MEDIUM).worldId)
        // Keine Tour-ID im Auftrag.
        assertTrue("r-geheim" !in req.toJson())
    }

    @Test
    fun auftragPrueftEingaben() {
        val ok = ServerWorldRequest(worldBuilderVersion, IndoorQuality.MEDIUM, true, route()).toJson()
        fun code(text: String, limits: ServerRouteLimits = ServerRouteLimits()) =
            assertFailsWith<ServerRequestException> { ServerWorldRequest.parse(text, limits) }.code
        assertEquals(ServerRequestError.INVALID, code("[]"))
        assertEquals(ServerRequestError.INVALID, code(ok.replace("\"format\":1", "\"format\":2")))
        assertEquals(ServerRequestError.INVALID, code(ok.replace("\"MEDIUM\"", "\"ULTRA\"")))
        assertEquals(ServerRequestError.INVALID, code(ok.replace(worldBuilderVersion, "../../etc")))
        assertEquals(ServerRequestError.INVALID, code(ok.replace("\"ASPHALT\"", "\"LAVA\"")))
        assertEquals(ServerRequestError.INVALID, code(ok.replace("\"residential\"", "\"$(rm -rf /)\"")))
        assertEquals(ServerRequestError.INVALID, code(ok.replace("[0.0,51.05,", "[5.0,51.05,")))
        assertEquals(ServerRequestError.INVALID, code(ok.replace("[0.0,51.05,", "[0.0,95.0,")))
        assertEquals(ServerRequestError.TOO_MANY_POINTS, code(ok, ServerRouteLimits(maxPoints = 100)))
        assertEquals(ServerRequestError.TOO_LONG, code(ok, ServerRouteLimits(maxLengthM = 1000.0)))
        assertEquals(ServerRequestError.TOO_LARGE, code(ok, ServerRouteLimits(maxBodyBytes = 100)))
        // Name: Steuerzeichen raus, gekuerzt.
        val named = ServerWorldRequest.parse(ServerWorldRequest(worldBuilderVersion, IndoorQuality.LOW, false, route(name = "A\u0000b\n" + "x".repeat(300))).toJson())
        assertEquals(120, named.route.name.length)
        assertTrue(named.route.name.startsWith("Abx"))
    }

    @Test
    fun laserVorschau() {
        assertEquals(LaserExpectation.FULL, laserPreviewOf(route()).kind)
        assertEquals("nw", laserPreviewOf(route()).source?.id)
        assertEquals("bw", laserPreviewOf(route(48.78, 9.09)).source?.id)
        assertEquals(LaserExpectation.NONE, laserPreviewOf(route(52.52, 13.40)).kind) // Berlin
        // Ueber die Grenze NRW/Hessen bei 9,50° O: 40 km nach Osten ab 9,30° O.
        val border = laserPreviewOf(route(51.3, 9.30, km = 40.0))
        assertNull(border.source)
        assertEquals(LaserExpectation.TOO_LITTLE, border.kind)
        val mostly = laserPreviewOf(route(51.3, 9.10, km = 40.0))
        assertEquals(LaserExpectation.PARTIAL, mostly.kind)
        assertTrue(mostly.share in 0.5..0.98)
    }

    private class FakeHttp(val answer: (HttpRequest) -> HttpResponse) : HttpClient {
        val seen = ArrayList<HttpRequest>()
        override fun execute(request: HttpRequest): HttpResponse {
            seen += request
            return answer(request)
        }
    }

    private val config = SyncConfig("https://example.test/", "geheimer-token-123456")

    private fun info(builder: String = worldBuilderVersion) =
        """{"service":"trailscape-worlds","api":1,"builderId":"$builder","laserSources":["nw","bw"],"maxLengthM":80000,"maxPoints":30000,"queueLength":2}"""

    @Test
    fun infoUndVersion() {
        val http = FakeHttp { HttpResponse(200, info()) }
        val ok = assertIs<ServerCall.Ok<ServerWorldInfo>>(WorldServerClient(http, config).info())
        assertEquals(listOf("nw", "bw"), ok.value.laserSources)
        assertEquals("https://example.test/api/worlds/info", http.seen.single().url)
        assertEquals("Bearer geheimer-token-123456", http.seen.single().headers["Authorization"])

        val older = assertIs<ServerCall.Incompatible>(WorldServerClient(FakeHttp { HttpResponse(200, info("indoor-world/1")) }, config).info())
        assertEquals(BuilderMismatch.UPDATE_SERVER, compareBuilder(older.serverBuilderId))
        val next = "indoor-world/${worldBuilderGeneration(worldBuilderVersion)!! + 1}"
        assertEquals(BuilderMismatch.UPDATE_APP, compareBuilder(next))
        assertEquals(BuilderMismatch.UNKNOWN, compareBuilder("etwas"))

        assertIs<ServerCall.NoService>(WorldServerClient(FakeHttp { HttpResponse(404, "{\"error\":\"Nicht gefunden\"}") }, config).info())
        assertIs<ServerCall.Unauthorized>(WorldServerClient(FakeHttp { HttpResponse(401, "{}") }, config).info())
        assertIs<ServerCall.Unreachable>(WorldServerClient(FakeHttp { throw IOException("weg") }, config).info())
        // Ein fremder Dienst unter /api/worlds/info ist kein Bau-Dienst.
        assertIs<ServerCall.Failed>(WorldServerClient(FakeHttp { HttpResponse(200, "{\"service\":\"x\"}") }, config).info())
    }

    @Test
    fun auftragStandUndLoeschen() {
        val id = "0123456789abcdef"
        val http = FakeHttp { req ->
            when {
                req.method == HttpMethod.POST -> HttpResponse(201, """{"id":"$id","state":"QUEUED","queuePosition":2}""")
                req.method == HttpMethod.DELETE -> HttpResponse(404, "{}")
                else -> HttpResponse(
                    200,
                    """{"id":"$id","state":"DONE","worldId":"w${"b".repeat(24)}","packageBytes":1234,"sha256":"${"A".repeat(64)}","laserSource":"nw"}""",
                )
            }
        }
        val c = WorldServerClient(http, config)
        val submitted = assertIs<ServerCall.Ok<ServerJobStatus>>(c.submit(ServerWorldRequest(worldBuilderVersion, IndoorQuality.MEDIUM, true, route())))
        assertEquals(ServerJobState.QUEUED, submitted.value.state)
        assertEquals(2, submitted.value.queuePosition)
        assertEquals("application/json", http.seen.last().headers["Content-Type"])
        val done = assertIs<ServerCall.Ok<ServerJobStatus>>(c.status(id)).value
        assertEquals("a".repeat(64), done.sha256)
        assertEquals("nw", done.laserSource)
        assertIs<ServerCall.Ok<Unit>>(c.delete(id))
        assertEquals("https://example.test/api/worlds/jobs/$id/package", c.packageUrl(id))
        assertFailsWith<IllegalArgumentException> { c.status("../../x") }
    }

    @Test
    fun abweisungenUndFehler() {
        fun with(code: Int, body: String) = WorldServerClient(FakeHttp { HttpResponse(code, body) }, config)
            .submit(ServerWorldRequest(worldBuilderVersion, IndoorQuality.MEDIUM, true, route()))
        val r = assertIs<ServerCall.Rejected>(with(413, """{"error":{"code":"TOO_LONG","detail":"120 km"}}"""))
        assertEquals("TOO_LONG", r.code)
        assertEquals("QUEUE_FULL", assertIs<ServerCall.Rejected>(with(429, """{"error":{"code":"QUEUE_FULL"}}""")).code)
        assertIs<ServerCall.Failed>(with(429, """{"error":"Zu viele fehlgeschlagene Anmeldeversuche"}"""))
        assertEquals(
            "indoor-world/3",
            assertIs<ServerCall.Incompatible>(with(409, """{"error":{"code":"BUILDER_MISMATCH","serverBuilderId":"indoor-world/3"}}""")).serverBuilderId,
        )
        assertIs<ServerCall.Failed>(with(500, "kaputt"))
        assertIs<ServerCall.NotFound>(WorldServerClient(FakeHttp { HttpResponse(404, "{}") }, config).status("0123456789abcdef"))
    }

    @Test
    fun auftragMerken() {
        val rec = ServerJobRecord("0123456789abcdef", "ride-1", "w" + "c".repeat(24), "Runde", "https://example.test", 42L)
        assertEquals(rec, ServerJobRecord.parseOrNull(rec.toJson()))
        assertNull(ServerJobRecord.parseOrNull("{\"jobId\":\"../x\"}"))
        assertNull(ServerJobRecord.parseOrNull(null))
        assertNotNull(ServerJobRecord.parseOrNull(rec.toJson().replace(",\"createdAtMs\":42", "")))
    }
}
