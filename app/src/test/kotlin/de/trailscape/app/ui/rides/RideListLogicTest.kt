package de.trailscape.app.ui.rides

import de.trailscape.core.RideStats
import de.trailscape.core.RideSummary
import de.trailscape.core.TrackPoint
import de.trailscape.core.i18n.AppLanguage
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    private fun summary(
        id: String,
        at: LocalDateTime,
        name: String = "Tour $id",
        planned: Boolean = false,
        km: Double = 10.0,
    ) = RideSummary(
        id = id,
        name = name,
        createdAt = ms(at),
        updatedAt = ms(at),
        stats = RideStats(distanceKm = km, ascentM = 0.0, descentM = 0.0),
        planned = planned,
    )

    private fun plan(id: String, at: LocalDateTime, name: String = "Plan $id") =
        summary(id, at, name = name, planned = true, km = 58.0)

    private val de = RidesXmlStrings.DE
    private val en = RidesXmlStrings.EN

    @Test
    fun `Touren werden nach Monat gruppiert, Reihenfolge bleibt`() {
        val rides = listOf(
            summary("a", LocalDateTime.of(2026, 9, 23, 18, 12)),
            summary("b", LocalDateTime.of(2026, 9, 1, 8, 0)),
            summary("c", LocalDateTime.of(2026, 8, 28, 8, 0)),
            summary("d", LocalDateTime.of(2025, 9, 14, 8, 0)),
        )
        val groups = groupRidesByMonth(rides, today, utc, AppLanguage.DE)

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
            de.resolve(rideListMeta(LocalDateTime.of(2026, 9, 22, 18, 12), stats, AppLanguage.DE)),
        )
        // Ohne Dauer entfaellt die Zeitangabe.
        assertEquals(
            "So 1.3. · 32,4 km",
            de.resolve(rideListMeta(LocalDateTime.of(2026, 3, 1, 9, 0), stats.copy(durationS = null), AppLanguage.DE)),
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
            de.resolve(rideDetailDateLine(at, today, planned = false, fromHealthConnect = false, AppLanguage.DE)),
        )
        assertEquals(
            "Dienstag, 22. September · 18:12 · aus Health Connect",
            de.resolve(rideDetailDateLine(at, today, planned = false, fromHealthConnect = true, AppLanguage.DE)),
        )
        assertEquals(
            "Sonntag, 22. September 2024 · 18:12",
            de.resolve(rideDetailDateLine(at.withYear(2024), today, planned = false, fromHealthConnect = false, AppLanguage.DE)),
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

    // -----------------------------------------------------------------------
    // Planungen getrennt von gefahrenen Touren (splitHistory)
    // -----------------------------------------------------------------------

    @Test
    fun `gemischte Liste - Planungen oben, gefahrene Touren nach Monaten ohne Planungen`() {
        val rides = listOf(
            summary("a", LocalDateTime.of(2026, 9, 23, 18, 0)),
            // Zwischen zwei Fahrten desselben Monats erstellt — frueher stand
            // sie genau dort, als waere sie gefahren worden.
            plan("p1", LocalDateTime.of(2026, 9, 20, 9, 0)),
            summary("b", LocalDateTime.of(2026, 9, 2, 8, 0)),
            plan("p2", LocalDateTime.of(2026, 8, 30, 9, 0)),
            summary("c", LocalDateTime.of(2026, 8, 28, 8, 0)),
        )
        val sections = splitHistory(rides, "", today, utc, AppLanguage.DE)

        assertEquals(listOf("p1", "p2"), sections.planned.map { it.id })
        assertEquals(listOf("September", "August"), sections.months.map { it.label })
        assertEquals(listOf("a", "b"), sections.months[0].rides.map { it.id })
        assertEquals(listOf("c"), sections.months[1].rides.map { it.id })
        assertTrue(sections.months.flatMap { it.rides }.none { it.planned })
        assertEquals(RiddenPart.LISTE, sections.ridden)
        assertFalse(sections.noMatch)
    }

    @Test
    fun `Suche trifft nur Planungen - gefahrener Teil meldet keinen Treffer`() {
        val rides = listOf(
            summary("a", LocalDateTime.of(2026, 9, 23, 18, 0), name = "Feierabendrunde"),
            plan("p", LocalDateTime.of(2026, 9, 20, 9, 0), name = "Alb-Runde über Hayingen"),
        )
        val sections = splitHistory(rides, "hayingen", today, utc, AppLanguage.DE)

        assertEquals(listOf("p"), sections.planned.map { it.id })
        assertTrue(sections.months.isEmpty())
        assertEquals(RiddenPart.KEIN_TREFFER, sections.ridden)
        assertFalse(sections.noMatch)
    }

    @Test
    fun `nur Planungen - der gefahrene Teil sagt ehrlich noch keine Fahrt`() {
        val rides = listOf(
            plan("p1", LocalDateTime.of(2026, 9, 20, 9, 0)),
            plan("p2", LocalDateTime.of(2026, 9, 10, 9, 0)),
        )
        val sections = splitHistory(rides, "", today, utc, AppLanguage.DE)

        assertEquals(listOf("p1", "p2"), sections.planned.map { it.id })
        assertTrue(sections.months.isEmpty())
        assertEquals(RiddenPart.NOCH_KEINE_FAHRT, sections.ridden)
        // Auch waehrend einer Suche: Es gibt keine Fahrt, nicht nur keinen Treffer.
        assertEquals(RiddenPart.NOCH_KEINE_FAHRT, splitHistory(rides, "p1", today, utc, AppLanguage.DE).ridden)
    }

    @Test
    fun `keine Planungen - Liste wie bisher, kein Abschnitt Geplant`() {
        val rides = listOf(
            summary("a", LocalDateTime.of(2026, 9, 23, 18, 0)),
            summary("b", LocalDateTime.of(2026, 8, 2, 8, 0)),
        )
        val sections = splitHistory(rides, "", today, utc, AppLanguage.DE)

        assertTrue(sections.planned.isEmpty())
        assertEquals(groupRidesByMonth(rides, today, utc, AppLanguage.DE), sections.months)
        assertEquals(RiddenPart.LISTE, sections.ridden)
    }

    @Test
    fun `Suche ohne jeden Treffer ist ein einziger Fall`() {
        val rides = listOf(
            summary("a", LocalDateTime.of(2026, 9, 23, 18, 0)),
            plan("p", LocalDateTime.of(2026, 9, 20, 9, 0)),
        )
        val sections = splitHistory(rides, "gibtsnicht", today, utc, AppLanguage.DE)

        assertTrue(sections.noMatch)
        assertTrue(sections.planned.isEmpty())
        // Ohne Touren ist es kein Such-, sondern der Erststart-Fall.
        assertFalse(splitHistory(emptyList<RideSummary>(), "x", today, utc, AppLanguage.DE).noMatch)
    }

    @Test
    fun `Planungszeile zeigt Laenge, Hoehenmeter und Erstelldatum statt Dauer`() {
        val stats = RideStats(distanceKm = 58.3, ascentM = 640.4, descentM = 640.0, durationS = 3600)
        assertEquals(
            "58 km · 640 Hm · erstellt 24.09.",
            de.resolve(plannedRouteMeta(LocalDateTime.of(2026, 9, 24, 21, 5), stats, AppLanguage.DE)),
        )
        // Kurze Runden behalten die Nachkommastelle.
        assertEquals(
            "2,4 km · 12 Hm · erstellt 01.03.",
            de.resolve(
                plannedRouteMeta(LocalDateTime.of(2026, 3, 1, 8, 0), stats.copy(distanceKm = 2.4, ascentM = 12.0), AppLanguage.DE),
            ),
        )
    }

    @Test
    fun `Verlauf-Summen zaehlen nur gefahrene Touren`() {
        val rides = listOf(
            summary("a", LocalDateTime.of(2026, 9, 23, 18, 0), km = 30.0),
            plan("p", LocalDateTime.of(2026, 9, 20, 9, 0)),
            summary("b", LocalDateTime.of(2026, 9, 2, 8, 0), km = 12.5),
        )
        assertEquals(HistoryTotals(rideCount = 2, totalKm = 42.5), historyTotals(rides))
        assertEquals(HistoryTotals(0, 0.0), historyTotals(listOf(plan("p", LocalDateTime.of(2026, 9, 20, 9, 0)))))
    }

    // ------------------------------------------------------------- Englisch

    @Test
    fun `Monatsueberschriften auf Englisch`() {
        val rides = listOf(
            summary("a", LocalDateTime.of(2026, 9, 23, 18, 12)),
            summary("d", LocalDateTime.of(2025, 9, 14, 8, 0)),
        )
        assertEquals(listOf("September", "September 2025"), groupRidesByMonth(rides, today, utc, AppLanguage.EN).map { it.label })
    }

    @Test
    fun `Kennzahlen-Zeile auf Englisch`() {
        val stats = RideStats(distanceKm = 32.4, ascentM = 410.0, descentM = 400.0, durationS = 5074)
        assertEquals(
            "Tue 22 Sept · 32.4 km · 1:24 h",
            en.resolve(rideListMeta(LocalDateTime.of(2026, 9, 22, 18, 12), stats, AppLanguage.EN)),
        )
        assertEquals(
            "Sun 1 Mar · 32.4 km",
            en.resolve(rideListMeta(LocalDateTime.of(2026, 3, 1, 9, 0), stats.copy(durationS = null), AppLanguage.EN)),
        )
    }

    @Test
    fun `Datumszeile der Detailansicht auf Englisch`() {
        val at = LocalDateTime.of(2026, 9, 22, 18, 12)
        assertEquals(
            "Tuesday 22 September · 18:12 · from Health Connect",
            en.resolve(rideDetailDateLine(at, today, planned = false, fromHealthConnect = true, AppLanguage.EN)),
        )
        assertEquals(
            "Sunday 22 September 2024 · 18:12 · planned route",
            en.resolve(rideDetailDateLine(at.withYear(2024), today, planned = true, fromHealthConnect = false, AppLanguage.EN)),
        )
        assertEquals(
            "Dienstag, 22. September · 18:12 · geplante Route · aus Health Connect",
            de.resolve(rideDetailDateLine(at, today, planned = true, fromHealthConnect = true, AppLanguage.DE)),
        )
    }

    @Test
    fun `Planungszeile auf Englisch mit m statt Hm`() {
        val stats = RideStats(distanceKm = 58.3, ascentM = 640.4, descentM = 640.0, durationS = 3600)
        assertEquals(
            "58 km · 640 m · created 24 Sept",
            en.resolve(plannedRouteMeta(LocalDateTime.of(2026, 9, 24, 21, 5), stats, AppLanguage.EN)),
        )
        // Die deutsche String-Fassung fuer das Karten-Blatt bleibt gleich.
        assertEquals(
            de.resolve(plannedRouteMeta(LocalDateTime.of(2026, 9, 24, 21, 5), stats, AppLanguage.DE)),
            plannedRouteMeta(LocalDateTime.of(2026, 9, 24, 21, 5), stats),
        )
    }
}
