package de.trailscape.app.ui.indoor

import de.trailscape.core.indoor.IndoorMode
import de.trailscape.core.indoor.catalog.CatalogRideKind
import de.trailscape.core.indoor.catalog.CatalogText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Was vor „Fahren" fehlt (Startseite, docs/indoor/app.md „Nutzerfluss"). */
class IndoorReadinessTest {

    private val noTrainer = IndoorPairUi(demoTrainer = false, trainerName = null, shifterName = null)
    private val kickr = IndoorPairUi(demoTrainer = false, trainerName = "KICKR CORE", shifterName = null)
    private val demo = IndoorPairUi(demoTrainer = true, trainerName = null, shifterName = null)

    private fun route(selected: IndoorRouteOption?, mode: IndoorMode = IndoorMode.SIM, erg: String? = null) = IndoorRouteUi(
        options = listOf(IndoorRouteOption.TestWorld),
        selected = selected,
        mode = mode,
        ergTitle = erg,
        ergMinutes = null,
        ghost = IndoorGhostOption.None,
        ghostPastRideAvailable = false,
        defaultGhostWatts = 180,
    )

    private fun item(state: IndoorCatalogState) = IndoorCatalogItemUi(
        id = "stelvio", name = CatalogText("Stilfser Joch", "Stelvio"), region = CatalogText("", ""),
        description = CatalogText("", ""), country = "IT", kind = CatalogRideKind.ROAD, distanceKm = 12.0,
        ascentM = 900.0, surfaces = emptyList(), packageBytes = 80L shl 20, aerial = true, state = state,
    )

    private fun readiness(selected: IndoorRouteOption?, state: IndoorCatalogState = IndoorCatalogState.NotLoaded) =
        indoorRouteReadiness(route(selected), IndoorCatalogUi(listOf(item(state))), "Testwelt") { it.name.de }

    private val stelvio = IndoorRouteOption.Catalog("stelvio", "Stilfser Joch", 12.0, 900.0)

    @Test
    fun trainer() {
        assertEquals(IndoorTrainerIssue.NOT_PAIRED, indoorTrainerIssue(noTrainer, bluetoothOn = true, permissionGranted = true))
        assertNull(indoorTrainerIssue(kickr, bluetoothOn = true, permissionGranted = true))
        assertEquals(IndoorTrainerIssue.BLUETOOTH_OFF, indoorTrainerIssue(kickr, bluetoothOn = false, permissionGranted = true))
        assertEquals(IndoorTrainerIssue.NO_PERMISSION, indoorTrainerIssue(kickr, bluetoothOn = false, permissionGranted = false))
        // Der Demo-Trainer braucht kein Bluetooth.
        assertNull(indoorTrainerIssue(demo, bluetoothOn = false, permissionGranted = false))
    }

    @Test
    fun streckenZustaende() {
        assertEquals(IndoorRouteReadiness.NoRoute, readiness(null))
        assertEquals(IndoorRouteReadiness.Ready("Testwelt"), readiness(IndoorRouteOption.TestWorld))
        val withWorld = IndoorRouteOption.Saved("r1", "Hausrunde", 40.0, 300.0, 0, planned = false, hasWorld = true)
        assertEquals(IndoorRouteReadiness.Ready("Hausrunde"), readiness(withWorld))
        val noWorld = withWorld.copy(hasWorld = false)
        val prepare = readiness(noWorld)
        assertEquals(IndoorRouteReadiness.NeedsPreparing("Hausrunde"), prepare)
        assertTrue(prepare.rideable, "eine eigene Strecke ohne Welt fuehrt mit „Fahren“ zu „Strecke vorbereiten“")

        assertEquals(IndoorRouteReadiness.Ready("Stilfser Joch"), readiness(stelvio, IndoorCatalogState.Ready))
        val update = assertIs<IndoorRouteReadiness.Ready>(readiness(stelvio, IndoorCatalogState.UpdateAvailable(1, 2)))
        assertEquals("stelvio", update.update?.id)
        for (state in listOf(
            IndoorCatalogState.NotLoaded,
            IndoorCatalogState.Paused(10, 100),
            IndoorCatalogState.Downloading(10, 100),
            IndoorCatalogState.Installing,
            IndoorCatalogState.Failed(IndoorCatalogFailure.OFFLINE),
        )) {
            val r = assertIs<IndoorRouteReadiness.Catalog>(readiness(stelvio, state), "$state")
            assertFalse(r.rideable, "$state: erst herunterladen")
        }
        // Gescheitertes Update: die alte Fassung bleibt fahrbar.
        val failedUpdate = readiness(stelvio, IndoorCatalogState.Failed(IndoorCatalogFailure.OFFLINE, installed = true))
        assertTrue(failedUpdate.rideable)
    }

    @Test
    fun fahrenNurWennAllesBereit() {
        val ready = IndoorRouteReadiness.Ready("Testwelt")
        val test = route(IndoorRouteOption.TestWorld)
        assertTrue(indoorCanRide(null, test, ready))
        assertFalse(indoorCanRide(IndoorTrainerIssue.NOT_PAIRED, test, ready))
        assertFalse(indoorCanRide(IndoorTrainerIssue.BLUETOOTH_OFF, test, ready))
        assertFalse(indoorCanRide(null, route(null), IndoorRouteReadiness.NoRoute))
        assertFalse(indoorCanRide(null, test, readiness(stelvio)))
        // ERG ohne Einheit geht nicht (wie bisher canContinue).
        assertFalse(indoorCanRide(null, route(IndoorRouteOption.TestWorld, IndoorMode.ERG, erg = null), ready))
        assertTrue(indoorCanRide(null, route(IndoorRouteOption.TestWorld, IndoorMode.ERG, erg = "Schwelle"), ready))
    }
}
