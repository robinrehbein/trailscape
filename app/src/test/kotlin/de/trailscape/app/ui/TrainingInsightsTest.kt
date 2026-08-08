package de.trailscape.app.ui

import de.trailscape.core.DailyValue
import de.trailscape.core.Ride
import de.trailscape.core.RideLoad
import de.trailscape.core.TrackPoint
import de.trailscape.core.TrainingProfile
import de.trailscape.core.VitalsSummary
import de.trailscape.core.VitalsTrend
import de.trailscape.core.computeRideLoadForRide
import de.trailscape.core.computeStats
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Tests der Zustands-Rechenschicht (Port von `_computeInsights` aus
 * `lib/state.dart`).
 *
 * Laufen als normale JVM-Unit-Tests, weil [computeInsights] bewusst keinen
 * einzigen Android-Import hat — [AppViewModel] selbst (Android-`ViewModel`)
 * bleibt ungetestet, seine Rechenarbeit steckt komplett hier.
 */
class TrainingInsightsTest {

    private val now: LocalDateTime = LocalDateTime.of(2026, 6, 15, 12, 0)
    private val profile = TrainingProfile(ageYears = 40, weightKg = 78.0)

    private fun epochMs(at: LocalDateTime): Long =
        at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    /** Eine Stunde gleichmaessiges Fahren mit Puls, ~20 km/h. */
    private fun ride(id: String, startedAt: LocalDateTime, hr: Int = 140): Ride {
        val startMs = epochMs(startedAt)
        val points = (0..720).map { i ->
            TrackPoint(
                lat = 48.0 + i * 0.000_05,
                lon = 11.0,
                ele = 500.0,
                time = startMs + i * 5_000L,
                hr = hr,
            )
        }
        return Ride(
            id = id,
            name = "Tour $id",
            createdAt = startMs,
            stats = computeStats(points),
            points = points,
        )
    }

    @Test
    fun `ohne Daten bleiben alle abgeleiteten Werte leer`() {
        val insights = computeInsights(
            rides = emptyList(),
            vitals = null,
            profile = profile,
            now = now,
        )

        assertTrue(insights.rideLoads.isEmpty())
        assertTrue(insights.fitness.points.isEmpty())
        assertEquals(0, insights.fitness.historyDays)
        assertEquals(0.0, insights.weeklyLoad)
        assertNull(insights.fourWeekMeanWeeklyLoad)
        assertNull(insights.weeklyTarget)
        assertNull(insights.latest)
        assertEquals(profile, insights.profile)
        // Die Ampeln liefern trotzdem ein anzeigbares Ergebnis.
        assertTrue(insights.readinessLast7.isEmpty())
        assertTrue(insights.recommendation.title.isNotBlank())
    }

    @Test
    fun `Tourlast entspricht dem Rechenkern und die Fitnesskurve laeuft bis heute`() {
        val ride = ride("a", now.minusDays(3))
        val insights = computeInsights(
            rides = listOf(ride),
            vitals = null,
            profile = profile,
            now = now,
        )

        assertEquals(setOf("a"), insights.rideLoads.keys)
        // Unter fuenf Kalibrierungspaaren ist α = 1,0 — die Last muss deshalb
        // exakt der unveraenderten :core-Berechnung entsprechen.
        assertEquals(1.0, insights.calibration.alpha)
        assertEquals(computeRideLoadForRide(ride, profile), insights.rideLoads.getValue("a"))

        val latest = assertNotNull(insights.latest)
        assertEquals(now.toLocalDate().atStartOfDay(), latest.day)
        assertEquals(4, insights.fitness.historyDays)
        assertTrue(insights.weeklyLoad > 0.0)
        assertNotNull(insights.fourWeekMeanWeeklyLoad)
        assertNotNull(insights.weeklyTarget)
    }

    @Test
    fun `unveraenderte Touren werden aus dem Cache bedient`() {
        val ride = ride("a", now.minusDays(2))
        val cache = mutableMapOf<String, RideLoad>()

        val first = computeInsights(listOf(ride), null, profile, now, cache)
        assertEquals(1, cache.size)

        val second = computeInsights(listOf(ride), null, profile, now, cache)
        // Identitaet, nicht nur Gleichheit: der zweite Lauf hat nicht gerechnet.
        assertSame(first.rideLoads.getValue("a"), second.rideLoads.getValue("a"))

        // Eine geloeschte Tour raeumt ihren Cache-Eintrag mit ab.
        computeInsights(emptyList(), null, profile, now, cache)
        assertTrue(cache.isEmpty())
    }

    @Test
    fun `gemessener Ruhepuls ersetzt den fehlenden Profilwert`() {
        val series = (1..10).map { DailyValue(day = now.minusDays(it.toLong()), value = 48.0 + it % 2) }
        val vitals = VitalsSummary(
            days = VITALS_WINDOW_DAYS,
            from = now.minusDays(VITALS_WINDOW_DAYS.toLong()),
            to = now,
            restingHeartRate = VitalsTrend(
                series = series,
                lastWeekAvg = null,
                previousWeekAvg = null,
            ),
            sleepHours = VitalsTrend.empty,
        )

        val effective = effectiveProfile(profile, vitals)
        assertEquals(48.5, effective.restingHrOverride)

        // Ein eigener Wert im Profil gewinnt weiterhin.
        val own = profile.copyWith(restingHrOverride = 55.0)
        assertEquals(55.0, effectiveProfile(own, vitals).restingHrOverride)

        // Und die Auswertung benutzt genau dieses effektive Profil.
        assertEquals(effective, computeInsights(emptyList(), vitals, profile, now).profile)
    }

    @Test
    fun `Kartenstil faellt bei unbekannter Kennung auf den Standard zurueck`() {
        assertEquals("cyclosm", mapStyleById("cyclosm").id)
        assertEquals(defaultMapStyle, mapStyleById("gibt-es-nicht"))
        assertEquals(defaultMapStyle, mapStyleById(null))
        assertTrue(defaultMapStyle.toRasterStyleJson().contains("\"type\": \"raster\""))
    }
}
