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

    private fun viewModel(store: CatalogStore = store()) = IndoorViewModel(
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
        wallClock = { 1_765_000_000_000L },
        runtimeDispatcher = Dispatchers.Unconfined,
        autoLoop = false,
        scope = scope,
        catalogStore = store,
        catalogDownloader = CatalogDownloader(CatalogDownloader.createClient(), "Trailscape/test", local),
    )

    private fun IndoorViewModel.toRoute() {
        setDemoTrainer(true)
        continueFromPair()
        setRouteTab(IndoorRouteTab.CATALOG)
        assertEquals(IndoorRouteTab.CATALOG, route.value.tab)
    }

    private fun IndoorViewModel.item(): IndoorCatalogItemUi = catalog.value.items.single { it.id == "testrunde" }

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
}
