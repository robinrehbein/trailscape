package de.trailscape.app.ui.indoor

import de.trailscape.app.data.OkHttpClientAdapter
import de.trailscape.app.indoor.catalog.CatalogDownloader
import de.trailscape.app.indoor.catalog.CatalogStore
import de.trailscape.app.indoor.world.WorldLibrary
import de.trailscape.app.testing.MemoryKeyValueStore
import de.trailscape.core.SyncConfig
import de.trailscape.core.indoor.IndoorRoute
import de.trailscape.core.indoor.IndoorRoutePoint
import de.trailscape.core.indoor.IndoorRouteSource
import de.trailscape.core.indoor.SurfaceKind
import de.trailscape.core.indoor.SurfaceSection
import de.trailscape.core.indoor.catalog.sha256Hex
import de.trailscape.core.indoor.catalog.writeCatalogPackage
import de.trailscape.core.indoor.server.BuilderMismatch
import de.trailscape.core.indoor.server.LaserExpectation
import de.trailscape.core.indoor.server.ServerJobRecord
import de.trailscape.core.indoor.server.ServerWorldRequest
import de.trailscape.core.indoor.server.WorldServerClient
import de.trailscape.core.indoor.world.IndoorQuality
import de.trailscape.core.indoor.world.WorldManifest
import de.trailscape.core.indoor.world.createWorldBuilder
import de.trailscape.core.indoor.world.worldBuilderVersion
import java.io.File
import java.util.Collections
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test

/**
 * Server-Bau (docs/indoor/serverbau.md) gegen einen gefaelschten Bau-Dienst
 * ([MockWebServer]): Angebot mit Laser-Vorschau, Auftrag → Warteschlange →
 * Bau → Download (mit Token, Range) → Installation ueber den
 * Katalog-Installationsweg, Fortsetzen nach „Neustart", Abbrechen,
 * Versionskonflikt, kein Bau-Dienst. Das Paket ist die echte Testwelt.
 */
class IndoorServerBuildTest {

    companion object {
        private lateinit var shared: File
        private lateinit var pkg: File
        private lateinit var worldDirTemplate: File
        private lateinit var worldId: String
        private lateinit var sha: String

        @JvmStatic
        @BeforeClass
        fun buildPackage() {
            shared = createTempDirectory("server-build-pkg").toFile()
            worldDirTemplate = File(shared, "welt")
            val m = createWorldBuilder().buildTestWorld(worldDirTemplate, IndoorQuality.LOW)
            worldId = m.worldId
            pkg = File(shared, "package.zip")
            writeCatalogPackage(worldDirTemplate, worldId, 1, pkg)
            sha = sha256Hex(pkg)
        }

        @JvmStatic
        @AfterClass
        fun cleanup() {
            shared.deleteRecursively()
        }

        const val TOKEN = "geheimer-token-0123456789"
        const val JOB = "0123456789abcdef0123456789abcdef"
    }

    private val tmp: File = createTempDirectory("server-build").toFile()
    private val worldsDir = File(tmp, "files/indoor/worlds")
    private val worlds = WorldLibrary(worldsDir, File(tmp, "cache/indoor/geo"))
    private val store = CatalogStore(File(tmp, "files/indoor/catalog"), worlds, worldsDir, { null })
    private val kv = MemoryKeyValueStore()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private lateinit var server: MockWebServer
    private val seen: MutableList<RecordedRequest> = Collections.synchronizedList(ArrayList())

    /** Zustand des gefaelschten Dienstes. */
    @Volatile private var builder = worldBuilderVersion

    @Volatile private var service = true

    @Volatile private var statusCalls = 0

    @Volatile private var jobExists = false

    @Volatile private var holdInQueue = false

    @Before
    fun start() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen += request
                if (request.getHeader("Authorization") != "Bearer $TOKEN") return MockResponse().setResponseCode(401)
                val path = request.path.orEmpty()
                if (!service) return MockResponse().setResponseCode(404).setBody("{\"error\":\"Nicht gefunden\"}")
                return when {
                    path == "/api/worlds/info" -> json(
                        """{"service":"trailscape-worlds","api":1,"builderId":"$builder","laserSources":["nw","bw"],"maxLengthM":100000,"maxPoints":40000,"queueLength":0}""",
                    )
                    path == "/api/worlds/jobs" && request.method == "POST" -> {
                        val body = request.body.readUtf8()
                        val req = ServerWorldRequest.parse(body)
                        if (req.builderId != builder) {
                            MockResponse().setResponseCode(409).setBody("""{"error":{"code":"BUILDER_MISMATCH","serverBuilderId":"$builder"}}""")
                        } else {
                            jobExists = true
                            statusCalls = 0
                            json("""{"id":"$JOB","state":"QUEUED","queuePosition":1}""", 201)
                        }
                    }
                    path == "/api/worlds/jobs/$JOB" && request.method == "DELETE" -> {
                        jobExists = false
                        MockResponse().setResponseCode(204)
                    }
                    path == "/api/worlds/jobs/$JOB" -> {
                        if (!jobExists) return MockResponse().setResponseCode(404).setBody("{}")
                        statusCalls++
                        when {
                            holdInQueue || statusCalls < 2 -> json("""{"id":"$JOB","state":"QUEUED","queuePosition":2}""")
                            statusCalls < 4 -> json("""{"id":"$JOB","state":"RUNNING","phase":"AERIAL","fraction":0.4}""")
                            else -> json(
                                """{"id":"$JOB","state":"DONE","worldId":"$worldId","packageBytes":${pkg.length()},"sha256":"$sha","laserSource":"nw"}""",
                            )
                        }
                    }
                    path == "/api/worlds/jobs/$JOB/package" -> {
                        val bytes = pkg.readBytes()
                        val range = request.getHeader("Range")
                        if (range != null) {
                            val from = range.removePrefix("bytes=").removeSuffix("-").toInt()
                            MockResponse().setResponseCode(206)
                                .setHeader("Content-Range", "bytes $from-${bytes.size - 1}/${bytes.size}")
                                .setBody(Buffer().write(bytes.copyOfRange(from, bytes.size)))
                        } else {
                            MockResponse().setResponseCode(200).setBody(Buffer().write(bytes))
                        }
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    @After
    fun stop() {
        server.shutdown()
        scope.cancel()
        tmp.deleteRecursively()
    }

    private val url get() = server.url("/").toString().trimEnd('/')

    private fun deps(configured: Boolean = true) = IndoorServerDeps(
        client = { if (configured) WorldServerClient(OkHttpClientAdapter(), SyncConfig(url, TOKEN)) else null },
        downloader = { base -> CatalogDownloader(CatalogDownloader.createClient(), "Trailscape/test") { isSameOrigin(it, base) } },
        store = store,
        keyValue = kv,
        downloadsDir = File(tmp, "files/indoor/server"),
        serverUrl = { if (configured) url else null },
        pollMs = 10,
    )

    private class Installed(val rideId: String, val dir: File, val manifest: WorldManifest, val laser: String?, val replaced: Boolean)

    @Volatile private var installed: Installed? = null

    private fun controller(d: IndoorServerDeps = deps()) = IndoorServerBuildController(d, scope, Dispatchers.IO) { rideId, dir, manifest, laser, replaced ->
        installed = Installed(rideId, dir, manifest, laser, replaced)
    }

    private fun waitUntil(ms: Long = 20_000, what: String = "", cond: () -> Boolean) {
        val until = System.currentTimeMillis() + ms
        while (!cond()) {
            check(System.currentTimeMillis() < until) { "nicht erreicht: $what" }
            Thread.sleep(10)
        }
    }

    /** Spur am Altenberger Dom (NRW). */
    private val nrwTrack = (0..200).map { 51.05 + it * 1e-5 to 7.13 + it * 1e-4 }

    private fun route(): IndoorRoute {
        val pts = (0..300).map { IndoorRoutePoint(it * 10.0, 51.05 + it * 1e-6, 7.13 + it * 1.4e-4, 100.0) }
        return IndoorRoute("r", "Runde", pts, listOf(SurfaceSection(0.0, 3000.0, SurfaceKind.ASPHALT)), false, IndoorRouteSource.NONE)
    }

    @Test
    fun angebotAuftragBauDownloadInstallation() {
        val c = controller()
        c.open("ride-1", nrwTrack, hasDeviceWorld = false)
        waitUntil(what = "Angebot") { c.ui.value is IndoorServerUi.Offer }
        val offer = c.ui.value as IndoorServerUi.Offer
        assertEquals(LaserExpectation.FULL, offer.laser.expectation)
        assertEquals("nw", offer.laser.regionId)
        // Nur die Info-Abfrage: keine Streckendaten vor der Nutzeraktion.
        assertTrue(seen.all { it.path == "/api/worlds/info" && it.bodySize == 0L })

        val steps = Collections.synchronizedSet(HashSet<IndoorServerStep>())
        val watcher = Thread {
            while (installed == null) {
                (c.ui.value as? IndoorServerUi.Working)?.let { steps += it.step }
                Thread.sleep(2)
            }
        }.apply { start() }
        c.start("ride-1", "Runde", route(), IndoorQuality.MEDIUM, worldId)
        waitUntil(what = "installiert") { installed != null }
        watcher.join()
        assertTrue(IndoorServerStep.QUEUED in steps, "$steps")

        val inst = assertNotNull(installed)
        assertEquals("ride-1", inst.rideId)
        assertEquals("nw", inst.laser)
        assertFalse(inst.replaced)
        assertEquals(worldId, inst.manifest.worldId)
        val info = assertNotNull(worlds.get(worldId))
        assertEquals("ride-1", info.source?.rideId)
        assertEquals("nw", info.server?.laserSource)
        assertNull(info.catalog)
        assertEquals("ride-1", worlds.findWorldForRide("ride-1")?.source?.rideId)
        assertTrue(worlds.toEntryUi(info, setOf("ride-1")).fromServer)
        // Auftrag vergessen, auf dem Server geloescht, Download aufgeraeumt.
        assertNull(kv.getString(IndoorServerBuildController.KEY_PREFIX + "ride-1"))
        waitUntil(what = "DELETE") { seen.any { it.method == "DELETE" } }
        assertTrue(File(tmp, "files/indoor/server").listFiles().orEmpty().isEmpty())
        // Token nur an den eigenen Server, auch beim Paket.
        assertTrue(seen.filter { it.path!!.endsWith("/package") }.all { it.getHeader("Authorization") == "Bearer $TOKEN" })
        assertIs<IndoorServerUi.Hidden>(c.ui.value)
    }

    @Test
    fun fortsetzenNachNeustartUndErsetzenDerGeraetewelt() {
        // Eine auf dem Geraet gebaute Welt derselben Strecke liegt schon da.
        val device = worlds.worldDirFor(worldId)
        worldDirTemplate.copyRecursively(device)
        WorldLibrary.writeSource(device, "ride-2", "Runde")
        holdInQueue = true
        val first = controller()
        first.open("ride-2", nrwTrack, hasDeviceWorld = true)
        waitUntil(what = "Angebot") { first.ui.value is IndoorServerUi.Offer }
        assertTrue((first.ui.value as IndoorServerUi.Offer).replacesDeviceWorld)
        first.start("ride-2", "Runde", route(), IndoorQuality.MEDIUM, worldId)
        // Erst die Antwort auf den Auftrag (Platz 1), dann der abgefragte Stand (Platz 2).
        waitUntil(what = "Warteschlange") { (first.ui.value as? IndoorServerUi.Working)?.queuePosition == 2 }
        assertEquals(IndoorServerStep.QUEUED, (first.ui.value as IndoorServerUi.Working).step)
        val record = assertNotNull(ServerJobRecord.parseOrNull(kv.getString(IndoorServerBuildController.KEY_PREFIX + "ride-2")))
        assertEquals(JOB, record.jobId)
        // „App beendet": Abfragen endet, der Auftrag bleibt gemerkt.
        first.close()

        holdInQueue = false
        val second = controller()
        second.open("ride-2", nrwTrack, hasDeviceWorld = true)
        waitUntil(what = "installiert") { installed != null }
        assertTrue(assertNotNull(installed).replaced)
        assertEquals("nw", worlds.get(worldId)?.server?.laserSource)
        assertEquals("ride-2", worlds.get(worldId)?.source?.rideId)
        // Kein zweiter Auftrag: nach dem Neustart nur Stand abgefragt.
        assertEquals(1, seen.count { it.method == "POST" })
    }

    @Test
    fun abbrechenLoeschtDenAuftrag() {
        holdInQueue = true
        val c = controller()
        c.open("ride-3", nrwTrack, hasDeviceWorld = false)
        waitUntil { c.ui.value is IndoorServerUi.Offer }
        c.start("ride-3", "Runde", route(), IndoorQuality.MEDIUM, worldId)
        waitUntil { (c.ui.value as? IndoorServerUi.Working)?.step == IndoorServerStep.QUEUED }
        c.cancel()
        waitUntil(what = "wieder Angebot") { c.ui.value is IndoorServerUi.Offer }
        assertTrue(seen.any { it.method == "DELETE" && it.path == "/api/worlds/jobs/$JOB" })
        assertNull(kv.getString(IndoorServerBuildController.KEY_PREFIX + "ride-3"))
        assertNull(worlds.get(worldId))
    }

    @Test
    fun versionskonfliktUndKeinDienst() {
        builder = "indoor-world/1"
        val c = controller()
        c.open("ride-4", nrwTrack, hasDeviceWorld = false)
        waitUntil { c.ui.value is IndoorServerUi.Incompatible }
        assertEquals(BuilderMismatch.UPDATE_SERVER, (c.ui.value as IndoorServerUi.Incompatible).mismatch)

        builder = worldBuilderVersion
        service = false
        val none = controller()
        none.open("ride-4", nrwTrack, hasDeviceWorld = false)
        waitUntil { seen.count { it.path == "/api/worlds/info" } >= 2 }
        Thread.sleep(100)
        assertIs<IndoorServerUi.Hidden>(none.ui.value)

        // Ohne eingerichteten Server: gar keine Anfrage.
        val before = seen.size
        val off = controller(deps(configured = false))
        off.open("ride-4", nrwTrack, hasDeviceWorld = false)
        Thread.sleep(100)
        assertIs<IndoorServerUi.Hidden>(off.ui.value)
        assertEquals(before, seen.size)
    }

    @Test
    fun ohneLaserGebietEhrlich() {
        val c = controller()
        c.open("ride-5", (0..100).map { 52.52 to 13.40 + it * 1e-4 }, hasDeviceWorld = false) // Berlin
        waitUntil { c.ui.value is IndoorServerUi.Offer }
        assertEquals(LaserExpectation.NONE, (c.ui.value as IndoorServerUi.Offer).laser.expectation)
    }

    @Test
    fun gleicheAdresse() {
        assertTrue(isSameOrigin("https://srv.example/api/worlds/jobs/a/package", "https://srv.example"))
        assertTrue(isSameOrigin("https://srv.example:443/x", "https://srv.example/"))
        assertFalse(isSameOrigin("https://evil.example/x", "https://srv.example"))
        assertFalse(isSameOrigin("http://srv.example/x", "https://srv.example"))
        assertFalse(isSameOrigin("https://srv.example:8443/x", "https://srv.example"))
        assertFalse(isSameOrigin("file:///etc/passwd", "https://srv.example"))
    }
}
