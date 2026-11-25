package de.trailscape.app.ui.indoor

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import de.trailscape.app.data.RideStorage
import de.trailscape.app.indoor.catalog.CatalogDownloader
import de.trailscape.app.indoor.catalog.CatalogStore
import de.trailscape.app.indoor.testing.FakeClock
import de.trailscape.app.indoor.testing.FakeIndoorParts
import de.trailscape.app.indoor.world.WorldLibrary
import de.trailscape.app.testing.MemoryKeyValueStore
import de.trailscape.app.testing.TestLocales
import de.trailscape.core.TrainingProfile
import de.trailscape.core.i18n.AppLanguage
import de.trailscape.core.i18n.CoreTextsDe
import de.trailscape.core.indoor.SurfaceKind
import de.trailscape.core.indoor.catalog.CatalogEntry
import de.trailscape.core.indoor.catalog.CatalogRideKind
import de.trailscape.core.indoor.catalog.CatalogText
import de.trailscape.core.indoor.catalog.IndoorCatalog
import de.trailscape.core.indoor.catalog.sha256Hex
import de.trailscape.core.indoor.catalog.writeCatalogPackage
import de.trailscape.core.indoor.world.IndoorQuality
import de.trailscape.core.indoor.world.WorldManifest
import de.trailscape.core.indoor.world.createWorldBuilder
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Bekannte Strecken im Indoor-Ablauf: Liste ohne Netz, „Laden" gegen einen
 * gefaelschten Release-Server (Katalog + Paket), 404 → „noch nicht
 * verfuegbar", Pruefsummenfehler, Wiederaufnahme, Update, und eine bereite
 * Strecke faehrt ohne „Welt laden" direkt los — auf dem Band des Manifests.
 *
 * Das Paket stammt aus der echten Testwelt des Weltbaus (ohne Netz).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = TestLocales.DE)
class IndoorCatalogFlowTest {

    companion object {
        /** Wanduhr der Tests (24.11.2026 9:00 MEZ). */
        private const val NOW = 1_795_507_200_000L
        private lateinit var shared: File
        private lateinit var packageV1: File
        private lateinit var packageV2: File
        private lateinit var worldId: String
        private var trackLengthM = 0.0

        @JvmStatic
        @BeforeClass
        fun buildPackages() {
            shared = createTempDirectory("catalog-flow-pkg").toFile()
            val world = File(shared, "welt")
            val m = createWorldBuilder().buildTestWorld(world, IndoorQuality.LOW)
            worldId = m.worldId
            trackLengthM = m.track.lengthM
            packageV1 = File(shared, "testrunde-v1.zip")
            packageV2 = File(shared, "testrunde-v2.zip")
            writeCatalogPackage(world, "testrunde", 1, packageV1)
            writeCatalogPackage(world, "testrunde", 2, packageV2)
        }

        @JvmStatic
        @AfterClass
        fun cleanupPackages() {
            shared.deleteRecursively()
        }
    }

    private val tmp: File = createTempDirectory("catalog-flow").toFile()
    private val worldsDir = File(tmp, "files/indoor/worlds")
    private val worlds = WorldLibrary(worldsDir, File(tmp, "cache/indoor/geo"))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private lateinit var server: MockWebServer

    /** Was der gefaelschte Release-Server ausliefert: Pfad → Antwort. */
    private val routes = HashMap<String, () -> MockResponse>()

    @Before
    fun startServer() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty().substringBefore('?')
                return routes[path]?.invoke() ?: MockResponse().setResponseCode(404).setBody("Not Found")
            }
        }
        server.start()
    }

    @After
    fun cleanup() {
        server.shutdown()
        scope.cancel()
        tmp.deleteRecursively()
    }

    private val base get() = server.url("/release/").toString()
    private val local: (String) -> Boolean get() = { it.startsWith(server.url("/").toString()) }

    private fun entry(version: Int = 1, sha: String? = null, bytes: Long = 0) = CatalogEntry(
        id = "testrunde",
        version = version,
        worldId = worldId,
        name = CatalogText("Testrunde am See", "Test loop by the lake"),
        region = CatalogText("Erfunden", "Made up"),
        country = "DE",
        kind = CatalogRideKind.GRAVEL,
        lengthM = 3000.0,
        ascentM = 40.0,
        surfaces = mapOf(SurfaceKind.GRAVEL to 0.7, SurfaceKind.ASPHALT to 0.3),
        description = CatalogText("Zum Testen.", "For tests."),
        packageFile = "testrunde-v$version.zip",
        packageBytes = bytes,
        sha256 = sha,
        aerial = false,
    )

    private fun bundledText() = IndoorCatalog(base, 0, listOf(entry(bytes = 25_000_000))).toJson()

    private fun releaseCatalog(vararg entries: CatalogEntry) {
        val text = IndoorCatalog(base, 1, entries.toList()).toJson()
        routes["/release/catalog.json"] = { MockResponse().setBody(text) }
    }

    private fun servePackage(file: File) {
        routes["/release/${file.name}"] = { MockResponse().setBody(Buffer().write(file.readBytes())) }
    }

    private fun store() = CatalogStore(File(tmp, "files/indoor/catalog"), worlds, worldsDir, { bundledText() }, local)

    private fun viewModel(store: CatalogStore = store(), online: Boolean = false, nowMs: Long = NOW) = IndoorViewModel(
        parts = FakeIndoorParts(),
        rideStorage = RideStorage(File(tmp, "rides")),
        worlds = worlds,
        store = MemoryKeyValueStore(),
        texts = ContextIndoorTexts(
            context = { ApplicationProvider.getApplicationContext<Application>() },
            language = { AppLanguage.DE },
        ),
        profile = { TrainingProfile(ageYears = 40, weightKg = 75.0) },
        plan = { null },
        coreTexts = { CoreTextsDe },
        io = Dispatchers.Unconfined,
        clock = FakeClock(),
        wallClock = { nowMs },
        runtimeDispatcher = Dispatchers.Unconfined,
        autoLoop = false,
        scope = scope,
        catalogStore = store,
        catalogDownloader = CatalogDownloader(CatalogDownloader.createClient(), "Trailscape/test", local),
        catalogOnline = { online },
    )

    private fun IndoorViewModel.toRoute() {
        setDemoTrainer(true)
        continueFromPair()
        assertEquals(IndoorStage.ROUTE, stage.value)
    }

    private fun IndoorViewModel.item(): IndoorCatalogItemUi = catalog.value.items.single { it.id == "testrunde" }

    private fun requests(): List<String> = generateSequence { server.takeRequest(0, java.util.concurrent.TimeUnit.SECONDS) }
        .map { it.path.orEmpty() }.toList()

    /** Ein kleines, echtes JPEG (wie es `shots.mjs` erzeugt, nur winzig). */
    private fun jpegBytes(): ByteArray {
        val img = java.awt.image.BufferedImage(64, 36, java.awt.image.BufferedImage.TYPE_INT_RGB)
        for (x in 0 until 64) for (y in 0 until 36) img.setRGB(x, y, (x * 4 shl 16) or (y * 7 shl 8) or 0x30)
        val out = java.io.ByteArrayOutputStream()
        check(javax.imageio.ImageIO.write(img, "jpg", out))
        return out.toByteArray()
    }

    @Test
    fun listeOhneNetzUnd404WirdZuNochNichtVerfuegbar() {
        val vm = viewModel()
        vm.toRoute()
        val item = vm.item()
        assertEquals(IndoorCatalogState.NotLoaded, item.state)
        assertEquals("Test loop by the lake", item.name.pick("en"))
        assertEquals(0, server.requestCount, "die Liste braucht kein Netz")

        vm.downloadCatalog("testrunde")
        val failed = assertIs<IndoorCatalogState.Failed>(vm.item().state)
        assertEquals(IndoorCatalogFailure.NOT_AVAILABLE, failed.reason)

        // Katalog da, Paket noch nicht hochgeladen.
        releaseCatalog(entry(sha = sha256Hex(packageV1), bytes = packageV1.length()))
        vm.downloadCatalog("testrunde")
        assertEquals(IndoorCatalogFailure.NOT_AVAILABLE, assertIs<IndoorCatalogState.Failed>(vm.item().state).reason)
        assertTrue(worlds.listWorlds().isEmpty())
    }

    @Test
    fun ladenEntpackenUndDirektLosfahren() {
        releaseCatalog(entry(sha = sha256Hex(packageV1), bytes = packageV1.length()))
        servePackage(packageV1)
        val vm = viewModel()
        vm.toRoute()
        vm.downloadCatalog("testrunde")
        val item = vm.item()
        assertEquals(IndoorCatalogState.Ready, item.state)
        assertEquals(packageV1.length(), item.packageBytes, "Groesse aus dem Release-Katalog")
        assertTrue(item.ready)
        val installed = worlds.listWorlds().single()
        assertEquals(worldId, installed.dirName)
        assertEquals(WorldLibrary.CatalogMarker("testrunde", 1), installed.catalog)
        assertTrue(installed.complete)
        assertTrue(!worlds.isOrphaned(installed, emptySet()), "Katalogwelten sind nie verwaist")
        assertTrue(worlds.toEntryUi(installed, emptySet()).fromCatalog)

        vm.selectRoute(IndoorRouteOption.Catalog("testrunde", item.name.pick("de"), item.distanceKm, item.ascentM))
        assertTrue(vm.route.value.canContinue)
        vm.continueFromRoute()
        assertEquals(IndoorStage.RIDE, vm.stage.value, "bereit → ohne „Welt laden“ direkt in die Fahrt")
        val ride = assertNotNull(vm.ride.value)
        assertEquals("Testrunde am See", ride.routeName)
        assertEquals(trackLengthM, assertNotNull(ride.profile).lengthM, 1e-6)
        assertTrue(ride.attribution.isNotEmpty(), "Quellenangaben der Welt")
        vm.release()
    }

    @Test
    fun pruefsummenfehlerUndWiederaufnahme() {
        // Falsche Pruefsumme im Katalog → verworfen.
        releaseCatalog(entry(sha = "0".repeat(64), bytes = packageV1.length()))
        servePackage(packageV1)
        val vm = viewModel()
        vm.toRoute()
        vm.downloadCatalog("testrunde")
        assertEquals(IndoorCatalogFailure.CHECKSUM, assertIs<IndoorCatalogState.Failed>(vm.item().state).reason)
        val s = store()
        assertEquals(0L, s.partialBytes(entry(bytes = packageV1.length())))

        // Angefangener Download (z. B. nach Abbruch oder Prozesstod) → „Fortsetzen" mit Range.
        releaseCatalog(entry(sha = sha256Hex(packageV1), bytes = packageV1.length()))
        val part = s.partFile(entry())
        part.parentFile.mkdirs()
        part.writeBytes(packageV1.readBytes().copyOf(4096))
        routes["/release/testrunde-v1.zip"] = {
            MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes 4096-${packageV1.length() - 1}/${packageV1.length()}")
                .setBody(Buffer().write(packageV1.readBytes().copyOfRange(4096, packageV1.length().toInt())))
        }
        val vm2 = viewModel(s)
        vm2.toRoute()
        val paused = assertIs<IndoorCatalogState.Paused>(vm2.item().state)
        assertEquals(4096L, paused.doneBytes)
        vm2.downloadCatalog("testrunde")
        assertEquals(IndoorCatalogState.Ready, vm2.item().state)
        val pkgRequest = generateSequence { server.takeRequest(0, java.util.concurrent.TimeUnit.SECONDS) }
            .last { it.path.orEmpty().endsWith(".zip") }
        assertEquals("bytes=4096-", pkgRequest.getHeader("Range"))
        assertTrue(!part.exists(), "Teildatei ist nach dem Einsortieren weg")
    }

    @Test
    fun updateErsetztDieAlteFassung() {
        releaseCatalog(entry(sha = sha256Hex(packageV1), bytes = packageV1.length()))
        servePackage(packageV1)
        val vm = viewModel()
        vm.toRoute()
        vm.downloadCatalog("testrunde")
        assertEquals(IndoorCatalogState.Ready, vm.item().state)

        // Neue Fassung im Release; „Katalog aktualisieren" zeigt sie an.
        releaseCatalog(entry(version = 2, sha = sha256Hex(packageV2), bytes = packageV2.length()))
        servePackage(packageV2)
        vm.refreshCatalog()
        assertEquals(IndoorCatalogRefresh.UPDATED, vm.catalog.value.refreshResult)
        assertEquals(IndoorCatalogState.UpdateAvailable(1, 2), vm.item().state)
        assertTrue(vm.item().ready, "alte Fassung bleibt fahrbar")

        vm.downloadCatalog("testrunde")
        assertEquals(IndoorCatalogState.Ready, vm.item().state)
        val all = worlds.listWorlds()
        assertEquals(1, all.size)
        assertEquals(2, all.single().catalog?.version)
        assertEquals(worldId, WorldManifest.parse(File(all.single().dir, "manifest.json").readText()).worldId)
    }

    @Test
    fun katalogAktualisierenOhneRelease() {
        val vm = viewModel()
        vm.toRoute()
        vm.refreshCatalog()
        assertEquals(IndoorCatalogRefresh.NOT_AVAILABLE, vm.catalog.value.refreshResult)
        assertEquals(1, vm.catalog.value.items.size, "beigelegte Liste bleibt")
    }

    @Test
    fun automatischerAbrufHoechstensEinmalAmTagMitNeuerStrecke() {
        val zweite = entry().copy(id = "zweite", packageFile = "zweite-v1.zip", name = CatalogText("Zweite Runde", "Second loop"))
        releaseCatalog(entry(sha = sha256Hex(packageV1), bytes = packageV1.length()), zweite)
        val s = store()
        val vm = viewModel(s, online = true)
        vm.toRoute()
        assertEquals(listOf("/release/catalog.json"), requests(), "beim Oeffnen der Liste von selbst geholt")
        val items = vm.catalog.value.items
        assertEquals(listOf("testrunde", "zweite"), items.map { it.id })
        assertTrue(items.single { it.id == "zweite" }.isNew, "neue Strecke ist gekennzeichnet")
        assertTrue(!items.single { it.id == "testrunde" }.isNew)
        assertEquals(packageV1.length(), vm.item().packageBytes, "Groesse aus dem Release-Katalog")
        assertEquals(null, vm.catalog.value.refreshResult, "keine Meldung beim stillen Abruf")
        assertTrue(!vm.catalog.value.refreshing)
        assertEquals(NOW, s.lastRefreshAttemptMs())
        vm.release()

        // Am selben Tag (neuer Ablauf, eine Stunde spaeter): kein zweiter Abruf, Liste aus der Platte.
        val vm2 = viewModel(s, online = true, nowMs = NOW + 3_600_000L)
        vm2.toRoute()
        assertEquals(emptyList(), requests())
        assertEquals(2, vm2.catalog.value.items.size)
        vm2.release()

        // Am naechsten Tag wieder.
        val vm3 = viewModel(s, online = true, nowMs = NOW + 25 * 3_600_000L)
        vm3.toRoute()
        assertEquals(listOf("/release/catalog.json"), requests())
        vm3.release()

        // „Katalog aktualisieren" geht jederzeit sofort.
        vm3.refreshCatalog()
        assertEquals(listOf("/release/catalog.json"), requests())
        assertEquals(IndoorCatalogRefresh.UPDATED, vm3.catalog.value.refreshResult)
    }

    @Test
    fun ohneNetzKeinAbrufUndFehlerBleibenStill() {
        // Flugmodus: kein Versuch, kein Zeitstempel, keine Meldung.
        val s = store()
        val vm = viewModel(s, online = false)
        vm.toRoute()
        assertEquals(0, server.requestCount)
        assertEquals(null, s.lastRefreshAttemptMs())
        assertEquals(null, vm.catalog.value.refreshResult)
        vm.release()

        // Server kaputt: still, die beigelegte Liste bleibt, der Versuch zaehlt fuer heute.
        routes["/release/catalog.json"] = { MockResponse().setResponseCode(503) }
        val vm2 = viewModel(s, online = true)
        vm2.toRoute()
        assertEquals(1, server.requestCount)
        assertEquals(null, vm2.catalog.value.refreshResult)
        assertTrue(!vm2.catalog.value.refreshing)
        assertEquals(listOf("testrunde"), vm2.catalog.value.items.map { it.id })
        assertEquals(IndoorCatalogState.NotLoaded, vm2.item().state)
        assertEquals(NOW, s.lastRefreshAttemptMs())

        // Netz da, aber Verbindung bricht ab (offline-Fehler): zaehlt nicht als Versuch.
        val s2 = CatalogStore(File(tmp, "files2/indoor/catalog"), worlds, worldsDir, { bundledText() }, local)
        server.shutdown()
        val vm3 = viewModel(s2, online = true)
        vm3.toRoute()
        assertEquals(null, s2.lastRefreshAttemptMs())
        assertEquals(null, vm3.catalog.value.refreshResult)
        assertEquals(1, vm3.catalog.value.items.size)
    }

    @Test
    fun vorschaubildWirdGeprueftGecachtUndProfilGezeigt() {
        val jpeg = jpegBytes()
        val profile = de.trailscape.core.indoor.catalog.CatalogProfile(1500.0, listOf(500.0, 520.0, 515.0))
        val withPreview = entry(sha = sha256Hex(packageV1), bytes = packageV1.length()).copy(
            laser = true,
            profile = profile,
            preview = de.trailscape.core.indoor.catalog.CatalogPreview("testrunde-v1.jpg", jpeg.size.toLong(), sha256(jpeg), 64, 36),
        )
        releaseCatalog(withPreview)
        routes["/release/testrunde-v1.jpg"] = { MockResponse().setHeader("Content-Type", "image/jpeg").setBody(Buffer().write(jpeg)) }
        val s = store()
        val vm = viewModel(s, online = true)
        vm.toRoute()
        assertEquals(listOf("/release/catalog.json", "/release/testrunde-v1.jpg"), requests())
        val item = vm.item()
        assertTrue(item.laser)
        assertEquals(profile, item.profile)
        val path = assertNotNull(item.previewPath, "Bild geladen")
        assertTrue(File(path).readBytes().contentEquals(jpeg))
        vm.release()

        // Naechstes Oeffnen, sogar ohne Netz: aus dem Cache, keine Anfrage.
        val vm2 = viewModel(s, online = false, nowMs = NOW + 60_000L)
        vm2.toRoute()
        assertEquals(emptyList(), requests())
        assertEquals(path, vm2.item().previewPath)
        vm2.release()

        // Neue Version mit anderem (falschem) Bild: altes Bild weg, falsches verworfen.
        val bad = withPreview.copy(
            version = 2, packageFile = "testrunde-v2.zip",
            preview = de.trailscape.core.indoor.catalog.CatalogPreview("testrunde-v2.jpg", jpeg.size.toLong(), "0".repeat(64)),
        )
        releaseCatalog(bad)
        routes["/release/testrunde-v2.jpg"] = { MockResponse().setBody(Buffer().write(jpeg)) }
        val vm3 = viewModel(s, online = true, nowMs = NOW + 25 * 3_600_000L)
        vm3.toRoute()
        assertEquals(listOf("/release/catalog.json", "/release/testrunde-v2.jpg"), requests())
        assertEquals(null, vm3.item().previewPath, "Pruefsumme passt nicht → kein Bild, die Karte zeigt das Profil")
        assertEquals(profile, vm3.item().profile)
        assertTrue(!File(path).exists(), "Bild der alten Version aufgeraeumt")
    }

    private fun sha256(b: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
}
