package de.trailscape.app.ui.rides

import de.trailscape.core.RideStats
import de.trailscape.core.RideSummary
import de.trailscape.core.TrackPoint
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests fuer die reine Rechnung der Verlaufsliste (`RideListLogic.kt`) und die
 * Mini-Spur ([thumbnailPolyline]).
 */
class RideListLogicTest {

    private val today = LocalDate.of(2026, 9, 25)

    /** Feste UTC-Umrechnung, damit der Test nicht von der Zeitzone abhaengt. */
    private val utc: (Long) -> LocalDateTime = { LocalDateTime.ofEpochSecond(it / 1000, 0, ZoneOffset.UTC) }

    private fun ms(at: LocalDateTime) = at.toEpochSecond(ZoneOffset.UTC) * 1000

    private fun summary(id: String, at: LocalDateTime, name: String = "Tour $id") = RideSummary(
        id = id,
        name = name,
        createdAt = ms(at),
        updatedAt = ms(at),
        stats = RideStats(distanceKm = 10.0, ascentM = 0.0, descentM = 0.0),
    )

    @Test
    fun `Touren werden nach Monat gruppiert, Reihenfolge bleibt`() {
        val rides = listOf(
            summary("a", LocalDateTime.of(2026, 9, 23, 18, 12)),
            summary("b", LocalDateTime.of(2026, 9, 1, 8, 0)),
            summary("c", LocalDateTime.of(2026, 8, 28, 8, 0)),
            summary("d", LocalDateTime.of(2025, 9, 14, 8, 0)),
        )
        val groups = groupRidesByMonth(rides, today, utc)

        assertEquals(listOf("September", "August", "September 2025"), groups.map { it.label })
        assertEquals(listOf("a", "b"), groups[0].rides.map { it.id })
        assertEquals(YearMonth.of(2025, 9), groups[2].month)
    }

    @Test
    fun `Suche filtert nach Namensteil ohne Gross-Kleinschreibung`() {
        val rides = listOf(
            summary("a", LocalDateTime.of(2026, 9, 1, 8, 0), name = "Feierabendrunde"),
            summary("b", LocalDateTime.of(2026, 9, 2, 8, 0), name = "GA1 Kanalrunde"),
        )
        assertEquals(listOf("b"), filterRidesByName(rides, "kanal").map { it.id })
        assertEquals(2, filterRidesByName(rides, "  ").size)
        assertEquals(2, filterRidesByName(rides, "RUNDE").size)
    }

    @Test
    fun `Kennzahlen-Zeile wie im Zieldesign`() {
        val stats = RideStats(distanceKm = 32.4, ascentM = 410.0, descentM = 400.0, durationS = 5074)
        assertEquals(
            "Di 22.9. · 32,4 km · 1:24 h",
            rideListMeta(LocalDateTime.of(2026, 9, 22, 18, 12), stats),
        )
        // Ohne Dauer entfaellt die Zeitangabe.
        assertEquals(
            "So 1.3. · 32,4 km",
            rideListMeta(LocalDateTime.of(2026, 3, 1, 9, 0), stats.copy(durationS = null)),
        )
    }

    @Test
    fun `Stunden und Minuten statt h mm ss`() {
        assertEquals("1:24", formatHoursMinutes(5099))
        assertEquals("0:05", formatHoursMinutes(300))
        assertEquals("12:00", formatHoursMinutes(43_200))
        assertEquals("–", formatHoursMinutes(null))
    }

    @Test
    fun `Datumszeile der Detailansicht nennt die Herkunft einmal`() {
        val at = LocalDateTime.of(2026, 9, 22, 18, 12)
        assertEquals(
            "Dienstag, 22. September · 18:12",
            rideDetailDateLine(at, today, planned = false, fromHealthConnect = false),
        )
        assertEquals(
            "Dienstag, 22. September · 18:12 · aus Health Connect",
            rideDetailDateLine(at, today, planned = false, fromHealthConnect = true),
        )
        assertEquals(
            "Sonntag, 22. September 2024 · 18:12",
            rideDetailDateLine(at.withYear(2024), today, planned = false, fromHealthConnect = false),
        )
    }

    // ------------------------------------------------------------ Mini-Spur

    @Test
    fun `Mini-Spur ist ausgeduennt, normiert und endet am letzten Punkt`() {
        val points = (0 until 1000).map { TrackPoint(lat = 52.0 + it * 1e-5, lon = 13.0 + it * 2e-5) }
        val line = thumbnailPolyline(points)

        assertTrue(line.size / 2 <= ThumbnailMaxPoints)
        assertTrue(line.all { it in 0f..1f })
        // Ost-West ist hier die laengere Achse (cos 52° ≈ 0,62): sie fuellt
        // die Breite, Start links, Ende rechts.
        assertEquals(0f, line[0], 1e-4f)
        assertEquals(1f, line[line.size - 2], 1e-4f)
        // Norden oben: das Ende liegt hoeher (kleineres y) als der Start, und
        // die kuerzere Achse ist mittig ausgerichtet.
        assertTrue(line[line.size - 1] < line[1])
        assertEquals(1f, line[1] + line[line.size - 1], 1e-4f)
    }

    @Test
    fun `Nord-Sued-Strecke bleibt ein mittiger senkrechter Strich`() {
        val points = listOf(TrackPoint(52.0, 13.0), TrackPoint(52.1, 13.0))
        val line = thumbnailPolyline(points)
        assertEquals(0.5f, line[0], 1e-4f)
        assertEquals(0.5f, line[2], 1e-4f)
    }

    @Test
    fun `zu wenige Punkte ergeben keine Linie`() {
        assertEquals(0, thumbnailPolyline(emptyList()).size)
        assertEquals(0, thumbnailPolyline(listOf(TrackPoint(52.0, 13.0))).size)
    }
}
