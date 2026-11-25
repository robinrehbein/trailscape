package de.trailscape.app.ui.rides

import de.trailscape.core.RideStats
import de.trailscape.core.RideSummary
import de.trailscape.core.i18n.AppLanguage
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Der Art-Filter („Alle · Draußen · Indoor") der Verlaufsliste. */
class RideKindFilterTest {

    private val today = LocalDate.of(2026, 11, 25)
    private val utc: (Long) -> LocalDateTime = { LocalDateTime.ofEpochSecond(it / 1000, 0, ZoneOffset.UTC) }

    private fun summary(id: String, day: Int, indoor: Boolean = false, planned: Boolean = false): RideSummary {
        val at = LocalDateTime.of(2026, 11, day, 9, 0).toEpochSecond(ZoneOffset.UTC) * 1000
        return RideSummary(
            id = id,
            name = id,
            createdAt = at,
            updatedAt = at,
            stats = RideStats(distanceKm = 20.0, ascentM = 0.0, descentM = 0.0),
            planned = planned,
            indoor = indoor,
        )
    }

    private val rides = listOf(
        summary("draussen", 20),
        summary("rolle", 21, indoor = true),
        summary("plan", 22, planned = true),
    )

    private fun ids(kind: RideKindFilter) =
        splitHistory(rides, "", today, utc, AppLanguage.DE, kind).months.flatMap { g -> g.rides.map { it.id } }

    @Test
    fun `Alle zeigt alles Gefahrene, Draussen und Indoor trennen`() {
        assertEquals(setOf("draussen", "rolle"), ids(RideKindFilter.ALL).toSet())
        assertEquals(listOf("draussen"), ids(RideKindFilter.OUTDOOR))
        assertEquals(listOf("rolle"), ids(RideKindFilter.INDOOR))
    }

    @Test
    fun `Planungen stehen nur unter Alle`() {
        assertEquals(listOf("plan"), splitHistory(rides, "", today, utc, AppLanguage.DE).planned.map { it.id })
        assertTrue(splitHistory(rides, "", today, utc, AppLanguage.DE, RideKindFilter.INDOOR).planned.isEmpty())
    }

    @Test
    fun `leere Auswahl ist ein Treffer-Leerzustand, kein Noch-keine-Fahrt`() {
        val onlyOutdoor = listOf(summary("a", 20))
        val sections = splitHistory(onlyOutdoor, "", today, utc, AppLanguage.DE, RideKindFilter.INDOOR)
        assertEquals(RiddenPart.KEIN_TREFFER, sections.ridden)
    }

    @Test
    fun `Suche und Art wirken zusammen`() {
        val sections = splitHistory(rides, "rol", today, utc, AppLanguage.DE, RideKindFilter.OUTDOOR)
        assertEquals(RiddenPart.KEIN_TREFFER, sections.ridden)
    }
}
