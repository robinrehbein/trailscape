package de.trailscape.app.ui.indoor

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import de.trailscape.app.data.RideStorage
import de.trailscape.app.indoor.IndoorDayRouteFinder
import de.trailscape.app.indoor.testing.FakeClock
import de.trailscape.app.indoor.testing.FakeIndoorParts
import de.trailscape.app.indoor.world.WorldLibrary
import de.trailscape.app.testing.MemoryKeyValueStore
import de.trailscape.app.testing.TestLocales
import de.trailscape.core.AscentPreference
import de.trailscape.core.Ride
import de.trailscape.core.RouteTarget
import de.trailscape.core.RouteTargetSource
import de.trailscape.core.SessionIntensity
import de.trailscape.core.TodayRoute
import de.trailscape.core.TrackPoint
import de.trailscape.core.TrainingProfile
import de.trailscape.core.TrainingSession
import de.trailscape.core.computeStats
import de.trailscape.core.i18n.AppLanguage
import de.trailscape.core.i18n.CoreTextsDe
import de.trailscape.core.i18n.SessionTextKey
import de.trailscape.core.indoor.DayRideEnd
import de.trailscape.core.indoor.DayRouteAvailability
import de.trailscape.core.indoor.DaySessionKind
import de.trailscape.core.indoor.IndoorMode
import de.trailscape.core.indoor.IndoorPhase
import de.trailscape.core.indoor.createIndoorSession
import de.trailscape.core.indoor.ergWorkoutFor
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
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
 * Einheit des Tages im ganzen Ablauf (docs/indoor/app.md, „Einheit des
 * Tages"): Karte im Schritt Strecke, „Fahren" waehlt Strecke, ERG und
 * Startpunkt, Ruhetag und Tag ohne Plan, Verhalten am Ende. Echte Sitzung und
 * echte ERG-Einheit aus `:core`, Demo-Trainer-Attrappe, Takt von Hand.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = TestLocales.DE)
class IndoorDayFlowTest {

    private val tmp: File = createTempDirectory("indoor-day").toFile()
    private val rideStorage = RideStorage(File(tmp, "rides"))
    private val worlds = WorldLibrary(File(tmp, "files/indoor/worlds"), File(tmp, "cache/indoor/geo"))
    private val clock = FakeClock()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val parts = FakeIndoorParts(
        sessionFactory = { createIndoorSession(it) },
        ergWorkoutFactory = { s, t -> ergWorkoutFor(s, t) },
    )
    private var vm: IndoorViewModel? = null

    @After
    fun cleanup() {
        vm?.release()
        scope.cancel()
        tmp.deleteRecursively()
    }

    /** 3 × 8 min mit 4 min Pause, 10 min Ein- und Ausfahren (52 min). */
    private val intervals = TrainingSession(
        day = "2026-11-24", title = "Schwelle", description = "", targetKm = 25,
        intensity = SessionIntensity.HART, durationMin = 52,
        textKey = SessionTextKey.INTERVALS, textArgs = listOf(10, 3, 8, 4, 10, 52, 0),
    )

    private fun target(intensity: SessionIntensity, hours: Double = 1.0) = RouteTarget(
        distanceKm = 30.0, ascentPreference = AscentPreference.BERGIG, durationH = hours, speedKmh = 28.0,
        intensity = intensity, label = "Runde", source = RouteTargetSource.PLAN,
    )

    private fun viewModel(today: TodayRoute?) = IndoorViewModel(
        parts = parts,
        rideStorage = rideStorage,
        worlds = worlds,
        store = MemoryKeyValueStore(),
        texts = ContextIndoorTexts(
            context = { ApplicationProvider.getApplicationContext<Application>() },
            language = { AppLanguage.DE },
        ),
        profile = { TrainingProfile(ageYears = 40, weightKg = 75.0) },
        plan = { null },
        coreTexts = { CoreTextsDe },
        today = { today },
        dayFinder = IndoorDayRouteFinder(rideStorage, worlds, null),
        io = Dispatchers.Unconfined,
        clock = clock,
        wallClock = { 1_765_000_000_000L },
        runtimeDispatcher = Dispatchers.Unconfined,
        autoLoop = false,
        scope = scope,
    ).also { vm = it }

    /** Eine gerade Strecke nach Norden, Hoehe nach [ele] (Laufmeter → m), Punkte alle 50 m. */
    private fun ride(id: String, name: String, lengthM: Double, ele: (Double) -> Double): Ride {
        val n = (lengthM / 50).toInt()
        val points = List(n + 1) { i ->
            val d = i * 50.0
            TrackPoint(lat = 50.0 + d / 111_195.0, lon = 7.0, ele = ele(d), time = 1_700_000_000_000L + i * 10_000L)
        }
        return Ride(id = id, name = name, createdAt = 1_700_000_000_000L, stats = computeStats(points), points = points, planned = true)
    }

    /**
     * 12 km flach, dann drei Anstiege von 1,8 km mit 5 % (bei 171 W rund 8 min),
     * je 1,5 km flach dazwischen.
     */
    private val hilly: (Double) -> Double = { d ->
        var e = 0.0
        var at = 12_000.0
        repeat(3) {
            if (d > at) e += minOf(d - at, 1_800.0) * 0.05
            at += 1_800.0 + 1_500.0
        }
        e
    }

    private fun IndoorViewModel.toRoute() {
        setDemoTrainer(true)
        continueFromPair()
        assertEquals(IndoorStage.ROUTE, stage.value)
    }

    private fun IndoorViewModel.tick(times: Int) {
        val runtime = assertNotNull(activeRuntime)
        repeat(times) {
            clock.advance(100)
            runtime.tickOnce()
        }
    }

    @Test
    fun fahrenWaehltPassendeStreckeErgUndStartpunkt() {
        rideStorage.saveRide(ride("flach", "Flache Runde", 30_000.0) { 0.0 })
        rideStorage.saveRide(ride("berge", "Drei Anstiege", 40_000.0, hilly))
        val vm = viewModel(TodayRoute(target(SessionIntensity.HART), intervals, 25, 1.0, false, null))
        vm.toRoute()

        val offer = assertIs<IndoorDayUi.Offer>(vm.day.value)
        assertEquals(DaySessionKind.PLANNED, offer.kind)
        assertEquals(52, offer.minutes)
        val best = assertNotNull(offer.current)
        assertEquals("ride:berge", best.key)
        assertEquals(DayRouteAvailability.BUILD, best.availability)
        assertNotNull(best.buildMinutes)
        val climbs = assertNotNull(best.climbs)
        assertEquals(3, climbs.reps)
        assertEquals(8, climbs.workMinutes)
        val startKm = assertNotNull(best.startKm, "Start so, dass die Intervalle in die Anstiege fallen")
        assertTrue(startKm > 2.0, "$startKm")
        assertTrue(offer.suggestions.any { it.key == IndoorDayRouteFinder.PLAN_KEY }, "neue Runde als Rueckfall")
        assertTrue(offer.suggestions.indexOfFirst { it.key == "ride:flach" } > 0)

        // Andere Strecke blaettert und kommt zurueck.
        vm.nextDaySuggestion()
        assertEquals(1, (vm.day.value as IndoorDayUi.Offer).index)
        repeat(offer.suggestions.size - 1) { vm.nextDaySuggestion() }
        assertEquals("ride:berge", (vm.day.value as IndoorDayUi.Offer).current?.key)

        vm.rideDaySuggestion()
        assertEquals(IndoorStage.WORLD, vm.stage.value)
        assertEquals(IndoorMode.ERG, vm.route.value.mode)
        assertEquals("ride:berge", vm.route.value.selected?.key)
        assertIs<IndoorWorldUi.Plan>(vm.world.value, "eigene Strecke ohne Welt: Voranschlag zum Bauen")

        vm.startRide(withWorld = false)
        vm.tick(1)
        val start = assertNotNull(vm.ride.value?.start)
        assertEquals(startKm * 1000, start.selectedM, 60.0)
        val session = assertNotNull(vm.ride.value?.session)
        assertEquals(IndoorMode.ERG, session.mode)
        assertEquals(startKm * 1000, session.routeDistM, 60.0)
    }

    @Test
    fun ruhetagZeigtKeineEinheitUndKeinErg() {
        val vm = viewModel(TodayRoute(null, intervals, 25, 0.0, true, null))
        vm.toRoute()
        val rest = assertIs<IndoorDayUi.Rest>(vm.day.value)
        assertTrue(rest.plannedTitle!!.isNotBlank())
        assertNull(vm.route.value.ergTitle, "am Ruhetag gibt es auch keine ERG-Einheit")
        vm.setMode(IndoorMode.ERG)
        assertEquals(IndoorMode.SIM, vm.route.value.mode)
    }

    @Test
    fun ohnePlaneinheitEineKurzeLockereRunde() {
        rideStorage.saveRide(ride("flach", "Flache Runde", 25_000.0) { 0.0 })
        val vm = viewModel(TodayRoute(target(SessionIntensity.GRUNDLAGE, hours = 2.0), null, null, 0.9, false, null))
        vm.toRoute()
        val offer = assertIs<IndoorDayUi.Offer>(vm.day.value)
        assertEquals(DaySessionKind.EASY, offer.kind)
        assertEquals(45, offer.minutes)
        assertEquals("Lockere Runde", offer.title)
        assertEquals("ride:flach", offer.current?.key)
        assertEquals(45, vm.route.value.ergMinutes)
    }

    @Test
    fun streckeOhneRundschlussEndetVorDerEinheit() {
        rideStorage.saveRide(ride("kurz", "Kurze Strecke", 1_000.0) { 0.0 })
        val easy = TrainingSession(day = "", title = "Locker", description = "", targetKm = 10, intensity = SessionIntensity.LOCKER, durationMin = 30)
        val vm = viewModel(TodayRoute(target(SessionIntensity.LOCKER), easy, 10, 1.0, false, null))
        vm.toRoute()
        val offer = assertIs<IndoorDayUi.Offer>(vm.day.value)
        val short = offer.suggestions.first { it.key == "ride:kurz" }
        assertNotNull(short.endsEarlyMin, "die Karte sagt vorher, dass die Strecke nicht reicht")
        vm.preferDaySuggestion("ride:kurz")
        vm.rideDaySuggestion()
        vm.startRide(withWorld = false)
        vm.releaseStart()
        var guard = 0
        while (vm.ride.value?.session?.phase != IndoorPhase.FINISHED && guard++ < 6000) vm.tick(10)
        val end = assertNotNull(vm.ride.value?.dayEnd)
        assertEquals(DayRideEnd.ROUTE_DONE_EARLY, end.kind)
        assertTrue(end.remainingMin in 1..30, "${end.remainingMin}")
        vm.dismissDayEnd()
        assertNull(vm.ride.value?.dayEnd)
    }

    @Test
    fun einheitVorDerStreckeZuEndeBietetAusfahrenAn() {
        rideStorage.saveRide(ride("lang", "Lange Strecke", 30_000.0) { 0.0 })
        val short = TrainingSession(day = "", title = "Kurz", description = "", targetKm = 5, intensity = SessionIntensity.LOCKER, durationMin = 10)
        val vm = viewModel(TodayRoute(target(SessionIntensity.LOCKER), short, 5, 1.0, false, null))
        vm.toRoute()
        vm.preferDaySuggestion("ride:lang")
        vm.rideDaySuggestion()
        vm.startRide(withWorld = false)
        vm.releaseStart()
        vm.tick(9 * 600)
        assertNull(vm.ride.value?.dayEnd, "nach 9 Minuten laeuft die Einheit noch")
        vm.tick(2 * 600)
        val end = assertNotNull(vm.ride.value?.dayEnd)
        assertEquals(DayRideEnd.WORKOUT_DONE, end.kind)
        assertEquals(IndoorPhase.RUNNING, vm.ride.value?.session?.phase, "es wird weitergefahren")
        vm.dismissDayEnd() // „Ausfahren"
        vm.tick(10)
        assertNull(vm.ride.value?.dayEnd)
    }

    @Test
    fun ohneTagesentscheidungKeineKarte() {
        val vm = viewModel(null)
        vm.toRoute()
        assertNull(vm.day.value)
    }
}
