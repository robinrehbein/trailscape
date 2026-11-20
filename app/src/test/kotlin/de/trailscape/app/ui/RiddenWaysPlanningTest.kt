package de.trailscape.app.ui

import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.core.app.ApplicationProvider
import de.trailscape.app.data.AppServices
import de.trailscape.app.testing.TestLocales
import de.trailscape.app.ui.map.RouteGenerationController
import de.trailscape.core.AscentPreference
import de.trailscape.core.InMemoryRiddenWaysStore
import de.trailscape.core.Ride
import de.trailscape.core.RideStats
import de.trailscape.core.RouteProfile
import de.trailscape.core.RouteTarget
import de.trailscape.core.RouteTargetSource
import de.trailscape.core.SessionIntensity
import de.trailscape.core.TrackPoint
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * „Neue Wege" am echten [AppViewModel]: welche Touren der Wege-Index sieht
 * (Gnadenfrist einer Loeschung) und dass der Routen-Controller das
 * ViewModel nach dessen Ende loslaesst.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = TestLocales.DE, application = ScreenshotApplication::class)
class RiddenWaysPlanningTest {

    private fun ride(id: String, lat: Double) = Ride(
        id = id,
        name = id,
        createdAt = 1_000L + lat.toLong(),
        updatedAt = 1_000L,
        stats = RideStats(distanceKm = 1.0, ascentM = 0.0, descentM = 0.0),
        // Eine Gerade nach Osten, ~1 km, Punkte alle ~7 m.
        points = (0..150).map { TrackPoint(lat = lat, lon = 9.4 + it * 0.0001, time = 1_000L + it * 1000L) },
    )

    private val a = ride("wege-a", 48.40)
    private val b = ride("wege-b", 48.42)

    private fun idleUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "Zeitueberschreitung" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    private fun newViewModel(store: ViewModelStore): AppViewModel {
        val factory = viewModelFactory { initializer { AppViewModel(riddenWaysStore = InMemoryRiddenWaysStore()) } }
        return ViewModelProvider(store, factory)[AppViewModel::class.java]
    }

    @Test
    fun `eine Tour in der Gnadenfrist der Loeschung zaehlt nicht als gefahren`() {
        AppServices.rideStorage.saveRides(listOf(a, b))
        val vm = newViewModel(ViewModelStore())
        idleUntil { vm.rides.value.map { it.id }.containsAll(listOf(a.id, b.id)) }

        // Wie der Controller: von einem IO-Thread aus.
        val before = assertNotNull(runBlocking(Dispatchers.IO) { vm.riddenWaysForPlanning() })
        assertTrue(before.isKnown(48.42, 9.41))

        vm.deleteRideWithUndo(b.id)
        shadowOf(Looper.getMainLooper()).idle()
        val during = assertNotNull(runBlocking(Dispatchers.IO) { vm.riddenWaysForPlanning() })
        assertTrue(during.isKnown(48.40, 9.41))
        assertFalse(during.isKnown(48.42, 9.41))

        vm.undoDeleteRide()
        shadowOf(Looper.getMainLooper()).idle()
        val after = assertNotNull(runBlocking(Dispatchers.IO) { vm.riddenWaysForPlanning() })
        assertTrue(after.isKnown(48.42, 9.41))
        assertEquals(before.size, after.size)
    }

    @Test
    fun `der Controller laesst das ViewModel nach dessen Ende los`() {
        val store = ViewModelStore()
        val vm = newViewModel(store)
        RouteGenerationController.releasePlanningSources()
        RouteGenerationController.open(
            RouteTarget(
                distanceKm = 30.0,
                ascentPreference = AscentPreference.MODERAT,
                durationH = 1.5,
                speedKmh = 20.0,
                intensity = SessionIntensity.GRUNDLAGE,
                label = "Test",
                source = RouteTargetSource.SELBST_GEWAEHLT,
            ),
        )
        RouteGenerationController.start(
            context = ApplicationProvider.getApplicationContext(),
            start = TrackPoint(lat = 48.4, lon = 9.4),
            profile = RouteProfile.GRAVEL,
            fromMapCenter = false,
            onMessage = {},
            preferNewAreas = false,
            exploredTiles = vm::exploredTilesForPlanning,
            preferNewWays = true,
            riddenWays = vm::riddenWaysForPlanning,
        )
        // Sofort abbrechen — hier geht es nur um das, was der Controller sich merkt.
        RouteGenerationController.cancel()
        RouteGenerationController.close()
        assertTrue(RouteGenerationController.holdsPlanningSources)

        store.clear()
        assertFalse(RouteGenerationController.holdsPlanningSources)
    }
}
