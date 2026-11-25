package de.trailscape.app.ui.indoor

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import de.trailscape.app.challenge.ChallengeStore
import de.trailscape.app.data.RideStorage
import de.trailscape.app.indoor.testing.FakeClock
import de.trailscape.app.indoor.testing.FakeIndoorParts
import de.trailscape.app.indoor.world.WorldLibrary
import de.trailscape.app.testing.MemoryKeyValueStore
import de.trailscape.app.testing.TestLocales
import de.trailscape.core.TrainingProfile
import de.trailscape.core.challenge.Challenge
import de.trailscape.core.challenge.ChallengeCurve
import de.trailscape.core.challenge.ChallengeParse
import de.trailscape.core.challenge.ChallengeReject
import de.trailscape.core.challenge.ChallengeRoute
import de.trailscape.core.challenge.createChallenge
import de.trailscape.core.challenge.parseChallenge
import de.trailscape.core.i18n.AppLanguage
import de.trailscape.core.i18n.CoreTextsDe
import de.trailscape.core.indoor.createIndoorSession
import java.io.File
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
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * „Herausforderung teilen" im ganzen Ablauf (docs/herausforderung.md): Datei
 * importieren, als Mitfahrer wählen, fahren, Ergebnis, eigene Fahrt teilen.
 * Echte Sitzung aus `:core`, Demo-Trainer, Teststrecke, Takt von Hand.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = TestLocales.DE)
class IndoorChallengeFlowTest {

    private val tmp: File = createTempDirectory("indoor-challenge").toFile()
    private val rideStorage = RideStorage(File(tmp, "rides"))
    private val worlds = WorldLibrary(File(tmp, "files/indoor/worlds"), File(tmp, "cache/indoor/geo"))
    private val clock = FakeClock()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val store = MemoryKeyValueStore()
    private val challengeStore = ChallengeStore(File(tmp, "challenges"))
    private val parts = FakeIndoorParts(sessionFactory = { createIndoorSession(it) })
    private var vm: IndoorViewModel? = null

    @After
    fun cleanup() {
        vm?.release()
        scope.cancel()
        tmp.deleteRecursively()
    }

    private fun viewModel() = IndoorViewModel(
        parts = parts,
        rideStorage = rideStorage,
        worlds = worlds,
        store = store,
        texts = ContextIndoorTexts(
            context = { ApplicationProvider.getApplicationContext<Application>() },
            language = { AppLanguage.DE },
        ),
        profile = { TrainingProfile(ageYears = 40, weightKg = 75.0) },
        plan = { null },
        coreTexts = { CoreTextsDe },
        io = Dispatchers.Unconfined,
        clock = clock,
        wallClock = { 1_765_000_000_000L },
        runtimeDispatcher = Dispatchers.Unconfined,
        autoLoop = false,
        scope = scope,
        challengeStore = challengeStore,
    ).also { vm = it }

    /** 120 s mit 6 m/s auf der Teststrecke. */
    private fun testWorldChallenge(name: String = "Anna"): Challenge =
        createChallenge(ChallengeRoute.TestWorld, ChallengeCurve(5, 120.0, List(25) { it * 300 }), name, "2026-11-20")

    private fun IndoorViewModel.tick(times: Int = 1) {
        val runtime = assertNotNull(activeRuntime, "Fahrt laeuft")
        repeat(times) {
            clock.advance(100)
            runtime.tickOnce()
        }
    }

    private fun IndoorViewModel.importAndSave(c: Challenge) {
        importChallengeFile(c.toJson().toByteArray())
        assertIs<ChallengeImportUi.Preview>(challengeImport.value)
        confirmChallengeImport()
        assertIs<ChallengeImportUi.Saved>(challengeImport.value)
    }

    @Test
    fun importMitVorschauSpeichertUndListet() {
        val vm = viewModel()
        val c = testWorldChallenge()
        vm.importChallengeFile(c.toJson().toByteArray())
        val preview = assertIs<ChallengeImportUi.Preview>(vm.challengeImport.value)
        assertEquals("Anna", preview.preview.name)
        assertEquals(0.72, preview.preview.distanceKm, 1e-9)
        assertEquals(120, preview.preview.durationS)
        assertTrue(vm.challenges.value.isEmpty(), "vor „Speichern“ liegt nichts da")

        vm.confirmChallengeImport()
        val saved = assertIs<ChallengeImportUi.Saved>(vm.challengeImport.value)
        assertEquals(ChallengeStore.SaveResult.SAVED, saved.result)
        assertEquals(listOf(c.id), vm.challenges.value.map { it.id })
        assertEquals(de.trailscape.app.challenge.ChallengeAvailability.READY, vm.challenges.value.single().availability)

        // Dieselbe Datei noch einmal: kein zweiter Eintrag.
        vm.importChallengeFile(c.toJson().toByteArray())
        vm.confirmChallengeImport()
        assertEquals(ChallengeStore.SaveResult.ALREADY_THERE, assertIs<ChallengeImportUi.Saved>(vm.challengeImport.value).result)
        assertEquals(1, vm.challenges.value.size)

        vm.dismissChallengeImport()
        assertNull(vm.challengeImport.value)
    }

    @Test
    fun unbrauchbareDateienWerdenMitGrundAbgelehnt() {
        val vm = viewModel()
        vm.importChallengeFile("kein json".toByteArray())
        assertEquals(ChallengeReject.NOT_JSON, assertIs<ChallengeImportUi.Failed>(vm.challengeImport.value).reason)
        vm.importChallengeFile("""{"format":"trailscape-challenge","version":4}""".toByteArray())
        val tooNew = assertIs<ChallengeImportUi.Failed>(vm.challengeImport.value)
        assertEquals(ChallengeReject.TOO_NEW, tooNew.reason)
        assertEquals(4, tooNew.fileVersion)
        vm.importChallengeFile(null)
        assertIs<ChallengeImportUi.Failed>(vm.challengeImport.value)
        assertTrue(vm.challenges.value.isEmpty())
    }

    @Test
    fun loeschenEntferntDieHerausforderungUndWaehltSieAb() {
        val vm = viewModel()
        val c = testWorldChallenge()
        vm.importAndSave(c)
        vm.selectRoute(IndoorRouteOption.TestWorld)
        vm.useChallenge(c.id, "")
        assertEquals(c.id, (vm.route.value.ghost as IndoorGhostOption.Challenge).challengeId)
        vm.deleteChallenge(c.id)
        assertTrue(vm.challenges.value.isEmpty())
        assertEquals(IndoorGhostOption.None, vm.route.value.ghost)
    }

    @Test
    fun herausforderungWirdMitfahrerNurAufDerPassendenStrecke() {
        val vm = viewModel()
        val c = testWorldChallenge()
        vm.importAndSave(c)
        vm.useChallenge(c.id, "")
        assertEquals(IndoorRouteOption.TestWorld, vm.route.value.selected)
        val ghost = assertIs<IndoorGhostOption.Challenge>(vm.route.value.ghost)
        assertEquals("Anna", ghost.label)

        // Andere Strecke gewählt: der Mitfahrer entfällt.
        val other = IndoorRouteOption.Saved("r1", "Hausrunde", 10.0, 100.0, 1L, planned = false)
        vm.selectRoute(other)
        assertEquals(IndoorGhostOption.None, vm.route.value.ghost)
        // Und ist dort auch nicht setzbar.
        vm.setGhost(IndoorGhostOption.Challenge(c.id, "x"))
        assertEquals(IndoorGhostOption.None, vm.route.value.ghost)
        // Unbekannte Kennung: nichts.
        vm.selectRoute(IndoorRouteOption.TestWorld)
        vm.setGhost(IndoorGhostOption.Challenge("0000000000000000", "x"))
        assertEquals(IndoorGhostOption.None, vm.route.value.ghost)
    }

    @Test
    fun fahrtGegenHerausforderungZeigtErgebnisUndLaesstSichAlsAntwortTeilen() {
        val vm = viewModel()
        val c = testWorldChallenge("Anna")
        vm.importAndSave(c)
        vm.setDemoTrainer(true)
        vm.continueFromPair()
        vm.useChallenge(c.id, "")
        vm.continueFromRoute()
        vm.startRide(withWorld = false)
        assertEquals(IndoorStage.RIDE, vm.stage.value)
        vm.tick()
        vm.releaseStart()
        vm.tick(900) // 90 s
        vm.finishRide()

        val summary = assertNotNull(vm.summary.value)
        assertEquals("Anna", summary.challengeOpponent)
        assertNotNull(summary.ghostGapS, "Abstand zur Herausforderung am Ende")
        assertTrue(summary.canShareChallenge)

        // Antwort teilen: Vorschau mit Name und Datum, Datei ohne Roh- und Ortsdaten.
        vm.setChallengeShareName("  Ben\u0007 ")
        vm.openChallengeShare()
        val share = assertNotNull(vm.challengeShare.value)
        assertTrue(share.reply)
        assertFalse(share.failed)
        val json = assertNotNull(share.json)
        val file = assertIs<ChallengeParse.Ok>(parseChallenge(json)).challenge
        assertEquals(ChallengeRoute.TestWorld, file.route)
        assertEquals("Ben", file.name)
        assertTrue(Regex("2025-12-0[56]").matches(assertNotNull(file.date)), file.date)
        assertEquals("Ben", assertNotNull(share.preview).name)
        for (key in listOf("hr", "power", "cadence", "lat", "lon", "time", "ele")) assertFalse(json.contains("\"$key\""), key)

        // Datum abschalten: es steht weder in der Vorschau noch in der Datei.
        vm.setChallengeShareDate(false)
        val off = assertNotNull(vm.challengeShare.value)
        assertNull(off.preview?.date)
        assertNull(assertIs<ChallengeParse.Ok>(parseChallenge(assertNotNull(off.json))).challenge.date)

        vm.closeChallengeShare()
        assertNull(vm.challengeShare.value)
    }

    @Test
    fun eigeneStreckenSindNichtTeilbar() {
        val vm = viewModel()
        vm.setDemoTrainer(true)
        vm.continueFromPair()
        // Die Teststrecke ist teilbar, eine eigene Strecke ist es nicht: dafuer
        // fehlt hier die Aussentour; geprueft wird die Zuordnung im Kern.
        assertNull(de.trailscape.core.challenge.routeOfKey("ride:abc", 1))
        assertNull(de.trailscape.core.challenge.routeOfKey("generated:1f", 1))
        assertEquals(ChallengeRoute.TestWorld, de.trailscape.core.challenge.routeOfKey(IndoorRouteOption.TestWorld.key, 1))
    }

    @Test
    fun ohneFahrtOderStreckeIstTeilenNichtMoeglich() {
        val vm = viewModel()
        vm.openChallengeShare()
        assertTrue(assertNotNull(vm.challengeShare.value).failed)
        vm.closeChallengeShare()
    }
}
