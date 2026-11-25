package de.trailscape.core

import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Automatisch erledigt — egal ob draussen aufgezeichnet oder drinnen gefahren
 * (Regeln bei [weekSessionProgress], dokumentiert in docs/trainingswissenschaft.md 17).
 */
class PlanSessionDoneTest {

    private val monday: LocalDate = LocalDate.of(2026, 1, 5)
    private fun at(dayOffset: Int, hour: Int): Long = dartEpochMs(monday.plusDays(dayOffset.toLong()).atTime(hour, 0))
    private val afterWeek = dartEpochMs(LocalDateTime.of(2026, 1, 14, 12, 0))

    private fun ride(
        id: String,
        createdAt: Long,
        km: Double,
        movingMin: Int? = null,
        indoor: Boolean = false,
    ) = Ride(
        id = id,
        name = id,
        createdAt = createdAt,
        points = emptyList(),
        stats = RideStats(distanceKm = km, ascentM = 0.0, descentM = 0.0, movingTimeS = movingMin?.let { it * 60 }),
        indoor = indoor,
    )

    private fun week(vararg sessions: TrainingSession) = TrainingWeek(
        index = 0,
        start = dartEpochMs(monday.atStartOfDay()),
        end = dartEpochMs(monday.plusDays(7).atStartOfDay()),
        kind = WeekKind.AUFBAU,
        targetKm = 100,
        sessions = sessions.toList(),
    )

    private val intervalle = TrainingSession(
        day = "Di",
        title = "Intervalle",
        description = "",
        targetKm = 30,
        intensity = SessionIntensity.HART,
        durationMin = 60,
    )

    @Test
    fun `Indoor-Fahrt erledigt die Einheit ueber die Zeit, auch mit wenigen virtuellen Kilometern`() {
        // 60 min auf der Rolle, am Berg der Strecke nur 14 virtuelle km (47 %).
        val p = weekSessionProgress(week(intervalle), listOf(ride("rolle", at(1, 18), 14.0, 60, indoor = true)), afterWeek)
        assertEquals(PlanSessionStatus.ERLEDIGT, p.single().status)
        assertTrue(p.single().riddenIndoor)
        assertFalse(p.single().riddenOutdoor)
        assertEquals(60, p.single().riddenMinutes)
    }

    @Test
    fun `Zeit zaehlt auch draussen, wenn die Einheit eine Dauer hat`() {
        // Draussen langsam: 15 km (50 %) in 45 min (75 %).
        val p = weekSessionProgress(week(intervalle), listOf(ride("draussen", at(1, 10), 15.0, 45)), afterWeek)
        assertEquals(PlanSessionStatus.ERLEDIGT, p.single().status)
        assertTrue(p.single().riddenOutdoor)
    }

    @Test
    fun `alter Plan ohne Dauer - drinnen zaehlt die ERG-Dauer, draussen bleibt es bei km`() {
        val alt = intervalle.copy(durationMin = null, intensity = SessionIntensity.GRUNDLAGE)
        // Rolle: 50 min, 12 km. ERG-Dauer aus 30 km → 72 min; 50/72 = 69 % → erledigt.
        val drinnen = weekSessionProgress(week(alt), listOf(ride("r", at(1, 18), 12.0, 50, indoor = true)), afterWeek)
        assertEquals(PlanSessionStatus.ERLEDIGT, drinnen.single().status)
        // Draussen dieselben Werte: ohne Plandauer kein Zeitanteil, 12/30 = 40 % → teilweise.
        val draussen = weekSessionProgress(week(alt), listOf(ride("d", at(1, 18), 12.0, 50)), afterWeek)
        assertEquals(PlanSessionStatus.TEILWEISE, draussen.single().status)
    }

    @Test
    fun `draussen abgebrochen und drinnen zu Ende gefahren ist eine Einheit`() {
        val rides = listOf(
            ride("draussen", at(1, 16), 8.0, 20),
            ride("rolle", at(1, 18), 10.0, 25, indoor = true),
        )
        val p = weekSessionProgress(week(intervalle), rides, afterWeek).single()
        assertEquals(PlanSessionStatus.ERLEDIGT, p.status) // 45 von 60 min
        assertEquals("rolle", p.rideId) // die laengere ist die Haupttour
        assertEquals(listOf("draussen"), p.extraRideIds)
        assertEquals(18.0, p.riddenKm)
        assertEquals(45, p.riddenMinutes)
        assertTrue(p.riddenIndoor && p.riddenOutdoor)
    }

    @Test
    fun `Touren vom Nachbartag werden nicht zusammengezaehlt`() {
        val mittwoch = intervalle.copy(day = "Mi")
        val rides = listOf(
            ride("di", at(1, 18), 6.0, 15),
            ride("mi", at(2, 18), 30.0, 60),
        )
        val p = weekSessionProgress(week(intervalle, mittwoch), rides, afterWeek)
        // Di nimmt die Di-Tour (nur 25 %), nicht die Mi-Tour dazu.
        assertEquals(PlanSessionStatus.TEILWEISE, p[0].status)
        assertEquals("di", p[0].rideId)
        assertTrue(p[0].extraRideIds.isEmpty())
        assertEquals(PlanSessionStatus.ERLEDIGT, p[1].status)
        assertEquals("mi", p[1].rideId)
    }

    @Test
    fun `reicht die beste Tour allein, bleibt die zweite frei`() {
        val mittwoch = intervalle.copy(day = "Mi")
        val rides = listOf(
            ride("di-lang", at(1, 9), 30.0, 60),
            ride("di-kurz", at(1, 18), 20.0, 40),
        )
        val p = weekSessionProgress(week(intervalle, mittwoch), rides, afterWeek)
        assertEquals("di-lang", p[0].rideId)
        assertTrue(p[0].extraRideIds.isEmpty())
        // Die zweite Dienstagstour darf die Mittwochseinheit (Toleranz ±1 Tag) decken.
        assertEquals("di-kurz", p[1].rideId)
        assertEquals(PlanSessionStatus.ERLEDIGT, p[1].status)
    }

    @Test
    fun `Zeit-Anteil ohne Zeit der Tour ist null`() {
        assertNull(sessionTimeShare(intervalle, listOf(ride("x", at(1, 9), 10.0))))
        assertEquals(0.5, sessionTimeShare(intervalle, listOf(ride("x", at(1, 9), 10.0, 30))))
    }

    @Test
    fun `heutige Einheit aus der Woche`() {
        val w = week(intervalle)
        val p = todaySessionProgress(w, intervalle, listOf(ride("r", at(1, 18), 14.0, 60, indoor = true)), at(1, 20))
        assertEquals(PlanSessionStatus.ERLEDIGT, p?.status)
        assertNull(todaySessionProgress(null, intervalle, emptyList(), at(1, 20)))
        assertNull(todaySessionProgress(w, null, emptyList(), at(1, 20)))
    }
}
