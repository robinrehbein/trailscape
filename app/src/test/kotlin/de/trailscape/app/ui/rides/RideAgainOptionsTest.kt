package de.trailscape.app.ui.rides

import kotlin.test.Test
import kotlin.test.assertEquals

/** Was der Hauptknopf des Tourdetails („Nochmal fahren") anbietet. */
class RideAgainOptionsTest {

    @Test
    fun `eine Aussenfahrt mit Spur bietet alle drei Arten in fester Reihenfolge`() {
        assertEquals(
            listOf(RideAgainOption.OUTDOOR, RideAgainOption.INDOOR, RideAgainOption.PREVIEW_3D),
            rideAgainOptions(indoor = false, pointCount = 120),
        )
    }

    @Test
    fun `eine Indoor-Tour laesst sich nur draussen nachfahren`() {
        assertEquals(
            listOf(RideAgainOption.OUTDOOR),
            rideAgainOptions(indoor = true, pointCount = 120),
        )
    }

    @Test
    fun `ohne Spur gibt es nichts nachzufahren`() {
        assertEquals(emptyList(), rideAgainOptions(indoor = false, pointCount = 0))
        assertEquals(emptyList(), rideAgainOptions(indoor = false, pointCount = 1))
        assertEquals(emptyList(), rideAgainOptions(indoor = true, pointCount = 1))
    }

    @Test
    fun `zwei Punkte genuegen`() {
        assertEquals(3, rideAgainOptions(indoor = false, pointCount = 2).size)
    }

    @Test
    fun `fehlende Wege des Aufrufers fallen weg`() {
        assertEquals(
            listOf(RideAgainOption.OUTDOOR, RideAgainOption.PREVIEW_3D),
            rideAgainOptions(indoor = false, pointCount = 50, indoorAvailable = false),
        )
        assertEquals(
            listOf(RideAgainOption.OUTDOOR, RideAgainOption.INDOOR),
            rideAgainOptions(indoor = false, pointCount = 50, previewAvailable = false),
        )
        assertEquals(
            listOf(RideAgainOption.OUTDOOR),
            rideAgainOptions(indoor = false, pointCount = 50, indoorAvailable = false, previewAvailable = false),
        )
    }
}
