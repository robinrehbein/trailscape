package de.trailscape.core

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests fuer die erste Runde ([firstRoundTarget], [defaultFirstRoundDuration],
 * [formatRoundHours]) — die Abbildung „so viel Zeit habe ich" → Routenziel.
 */
class FirstRoundTest {
    private companion object {
        const val EPS = 1e-9

        val profile = TrainingProfile(ageYears = 40)

        fun ride(i: Int, speed: Double, planned: Boolean = false) = Ride(
            id = "r$i",
            name = "Tour",
            createdAt = i.toLong(),
            stats = RideStats(
                distanceKm = 30.0,
                ascentM = 0.0,
                descentM = 0.0,
                avgSpeedKmh = speed,
            ),
            planned = planned,
        )
    }

    @Test
    fun `ohne Historie wird die Dauer ueber 17,1 km pro Stunde zur Distanz`() {
        val expected = mapOf(1.0 to 17.0, 1.5 to 26.0, 2.0 to 34.0)
        expected.forEach { (hours, km) ->
            val target = firstRoundTarget(hours, profile, emptyList())
            assertEquals(km, target.distanceKm, EPS, "$hours h")
            assertEquals(AscentPreference.FLACH, target.ascentPreference)
            assertEquals(SessionIntensity.GRUNDLAGE, target.intensity)
            assertEquals(hours, target.durationH)
            assertEquals(RouteTargetSource.TAGESEMPFEHLUNG, target.source)
            assertEquals("Erste Runde", target.label)
            assertEquals(17.1, target.speedKmh, EPS)
        }
    }

    @Test
    fun `mit gefahrenen Touren zaehlt der Median der Historie`() {
        val rides = List(5) { ride(it, speed = 20.0) }
        val target = firstRoundTarget(1.0, profile, rides)
        // 20 km/h × 0,95 = 19 km/h
        assertEquals(19.0, target.distanceKm, EPS)
        assertEquals(19.0, target.speedKmh, EPS)
    }

    @Test
    fun `gespeicherte Planungen sind keine Tempoquelle`() {
        val rides = List(5) { ride(it, speed = 30.0, planned = true) }
        assertEquals(26.0, firstRoundTarget(1.5, profile, rides).distanceKm, EPS)
    }

    @Test
    fun `die erste Runde wird nie kuerzer als die Untergrenze`() {
        assertEquals(FIRST_ROUND_MIN_KM, firstRoundTarget(0.25, profile, emptyList()).distanceKm, EPS)
    }

    @Test
    fun `die Vorauswahl richtet sich nach dem halben Wochenbudget`() {
        fun default(weeklyHours: Double?) =
            defaultFirstRoundDuration(profile.copy(weeklyHours = weeklyHours))

        assertEquals(FirstRoundDuration.ANDERTHALB_STUNDEN, default(null))
        assertEquals(FirstRoundDuration.ANDERTHALB_STUNDEN, default(0.0))
        assertEquals(FirstRoundDuration.EINE_STUNDE, default(2.5))
        // Untergrenze: weniger als eine Stunde wird nie vorgeschlagen.
        assertEquals(FirstRoundDuration.EINE_STUNDE, default(1.0))
        assertEquals(FirstRoundDuration.ANDERTHALB_STUNDEN, default(3.0))
        // Obergrenze: die erste Runde ist nie die laengste der Woche.
        assertEquals(FirstRoundDuration.ANDERTHALB_STUNDEN, default(10.0))
    }

    @Test
    fun `Stunden werden so geschrieben, wie man sie sagt`() {
        assertEquals("1 h", formatRoundHours(1.0))
        assertEquals("1½ h", formatRoundHours(1.5))
        assertEquals("2 h", formatRoundHours(2.0))
        assertEquals("½ h", formatRoundHours(0.5))
        // Alles andere laeuft ueber formatHours (eine Nachkommastelle).
        assertEquals("2,3 h", formatRoundHours(2.3))
        assertEquals(listOf("1 h", "1½ h", "2 h"), FirstRoundDuration.entries.map { it.label })
    }

    @Test
    fun `die Einfuehrung waehlt nur ohne gefahrene Tour eine Dauer vor`() {
        assertEquals(FirstRoundDuration.ANDERTHALB_STUNDEN, onboardingFirstRoundPreselect(profile, emptyList()))
        // Eine gespeicherte Planung ist keine gefahrene Tour.
        assertEquals(
            FirstRoundDuration.ANDERTHALB_STUNDEN,
            onboardingFirstRoundPreselect(profile, listOf(ride(1, 20.0, planned = true))),
        )
        // Mit Historie (Einfuehrung erneut angesehen): „Später".
        assertEquals(null, onboardingFirstRoundPreselect(profile, listOf(ride(1, 20.0))))
    }
}
