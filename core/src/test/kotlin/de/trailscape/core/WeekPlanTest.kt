package de.trailscape.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Tests fuer `WeekPlan.kt` — Gewohnheiten, Tagwahl, Obergrenze, Wetter. */
class WeekPlanTest {
    private companion object {
        /** Woche vom 16. bis 22. November 2026; der 16. ist ein Montag. */
        val MONDAY: LocalDate = LocalDate.of(2026, 11, 16)
        val TUESDAY: LocalDate = MONDAY.plusDays(1)
        val WEDNESDAY: LocalDate = MONDAY.plusDays(2)
        val THURSDAY: LocalDate = MONDAY.plusDays(3)
        val FRIDAY: LocalDate = MONDAY.plusDays(4)
        val SATURDAY: LocalDate = MONDAY.plusDays(5)
        val SUNDAY: LocalDate = MONDAY.plusDays(6)

        fun at(date: LocalDate, hour: Int = 9): LocalDateTime = date.atTime(hour, 0)

        fun fact(date: LocalDate, km: Double, id: String = "$date-$km") =
            RideFact(id = id, date = date, distanceKm = km, ascentM = 0.0, timeS = null, avgSpeedKmh = null)

        /** Acht Wochen lang dienstags 30 km und samstags 50 km. */
        fun regularHistory(): List<RideFact> = (1..8).flatMap { week ->
            val monday = MONDAY.minusWeeks(week.toLong())
            listOf(fact(monday.plusDays(1), 30.0), fact(monday.plusDays(5), 50.0))
        }

        fun habits(
            typical: Double? = 30.0,
            longest: Double? = 50.0,
            share: Map<DayOfWeek, Double> = emptyMap(),
        ) = RidingHabits(share, typical, longest, ridesInWindow = 10)

        fun dry(date: LocalDate, tMax: Double = 14.0) =
            DayForecast(date, precipProbabilityPct = 5, precipMm = 0.0, windMaxKmh = 12.0, tempMinC = 4.0, tempMaxC = tMax)

        fun rainy(date: LocalDate) =
            DayForecast(date, precipProbabilityPct = 90, precipMm = 8.0, windMaxKmh = 20.0, tempMinC = 5.0, tempMaxC = 9.0)
    }

    // --- Gewohnheiten ---

    @Test
    fun `ohne Fahrten keine Gewohnheiten`() {
        assertEquals(RidingHabits.NONE, ridingHabits(emptyList(), MONDAY))
    }

    @Test
    fun `regelmaessige Fahrten ergeben Wochentage, Median und laengste Fahrt`() {
        val habits = ridingHabits(regularHistory(), MONDAY)
        assertEquals(1.0, habits.shareOf(DayOfWeek.TUESDAY))
        assertEquals(1.0, habits.shareOf(DayOfWeek.SATURDAY))
        assertEquals(0.0, habits.shareOf(DayOfWeek.THURSDAY))
        assertEquals(40.0, habits.typicalRideKm)
        assertEquals(50.0, habits.longestRideKm)
    }

    @Test
    fun `Anteile beziehen sich nur auf die Wochen seit der ersten Fahrt`() {
        val facts = listOf(fact(MONDAY.minusDays(9), 20.0), fact(MONDAY.minusDays(2), 25.0)) // zwei Samstage
        val habits = ridingHabits(facts, MONDAY)
        assertEquals(1.0, habits.shareOf(DayOfWeek.SATURDAY))
    }

    @Test
    fun `zwei Fahrten am selben Tag zaehlen fuer den Wochentag einmal`() {
        val sat = MONDAY.minusDays(2)
        val facts = listOf(fact(sat, 10.0, "a"), fact(sat, 15.0, "b"), fact(MONDAY.minusDays(9), 20.0))
        assertEquals(1.0, ridingHabits(facts, MONDAY).shareOf(DayOfWeek.SATURDAY))
    }

    @Test
    fun `Fahrten nach heute und alte Fahrten ausserhalb des Fensters zaehlen nicht fuer Wochentage`() {
        val facts = regularHistory() + fact(MONDAY.plusDays(3), 99.0) + fact(MONDAY.minusWeeks(20), 200.0)
        val habits = ridingHabits(facts, MONDAY)
        assertEquals(0.0, habits.shareOf(DayOfWeek.THURSDAY))
        assertEquals(50.0, habits.longestRideKm)
    }

    @Test
    fun `duennes Fenster nimmt die juengsten Fahrten ueberhaupt`() {
        // Eine kurze Runde in den letzten acht Wochen, davor regelmaessig 60 km.
        val old = (10..14).map { fact(MONDAY.minusWeeks(it.toLong()), 60.0) }
        val facts = old + fact(MONDAY.minusDays(3), 8.0)
        val habits = ridingHabits(facts, MONDAY)
        assertEquals(1, habits.ridesInWindow)
        assertEquals(60.0, habits.longestRideKm)
        assertEquals(60.0, habits.typicalRideKm)
    }

    @Test
    fun `Fahrten ohne Strecke zaehlen nicht`() {
        assertEquals(RidingHabits.NONE, ridingHabits(listOf(fact(MONDAY.minusDays(1), 0.0)), MONDAY))
    }

    // --- Kein Plan ---

    @Test
    fun `geschafftes Ziel ergibt keinen Plan`() {
        assertNull(planWeek(WeekPlanInput(0.0, at(WEDNESDAY), habits())))
        assertNull(planWeek(WeekPlanInput(-5.0, at(WEDNESDAY), habits())))
        assertNull(planWeek(WeekPlanInput(0.3, at(WEDNESDAY), habits())))
    }

    @Test
    fun `Rest unter der kleinsten Runde ergibt keinen Plan`() {
        assertNull(planWeek(WeekPlanInput(WEEK_PLAN_MIN_REMAINING_KM - 0.1, at(WEDNESDAY), habits())))
        assertNotNull(planWeek(WeekPlanInput(WEEK_PLAN_MIN_REMAINING_KM, at(WEDNESDAY), habits())))
        // Auch am Sonntagabend: dann keine Karte statt „kein Fahrtag mehr" fuer 3 km.
        assertNull(planWeek(WeekPlanInput(3.0, at(SUNDAY, 20), habits())))
    }

    @Test
    fun `einzelne Fahrt hat mindestens die Mindestlaenge`() {
        val plan = assertNotNull(planWeek(WeekPlanInput(7.0, at(WEDNESDAY), habits())))
        assertEquals(7, plan.remainingKm)
        assertEquals(WEEK_PLAN_MIN_RIDE_KM, plan.rides.single().km)
        assertEquals(WeekPlanStatus.MACHBAR, plan.status)
        assertEquals(0, plan.shortfallKm)
    }

    @Test
    fun `aktiver Trainingsplan hat Vorrang`() {
        assertNull(planWeek(WeekPlanInput(60.0, at(WEDNESDAY), habits(), trainingPlanActive = true)))
    }

    @Test
    fun `unsinniger Rest ergibt keinen Plan`() {
        assertNull(planWeek(WeekPlanInput(Double.NaN, at(WEDNESDAY), habits())))
    }

    // --- Tage ---

    @Test
    fun `heute zaehlt bis 18 Uhr`() {
        assertEquals(THURSDAY, remainingWeekDays(at(THURSDAY, 17)).first())
        assertEquals(FRIDAY, remainingWeekDays(at(THURSDAY, 18)).first())
        assertEquals(SUNDAY, remainingWeekDays(at(THURSDAY, 9)).last())
    }

    @Test
    fun `heute schon gefahren zaehlt heute nicht mehr`() {
        assertEquals(FRIDAY, remainingWeekDays(at(THURSDAY, 9), rodeToday = true).first())
    }

    @Test
    fun `Wochenbeginn ist Montag`() {
        assertEquals(7, remainingWeekDays(at(MONDAY)).size)
        assertEquals(listOf(SUNDAY), remainingWeekDays(at(SUNDAY, 10)))
    }

    @Test
    fun `Sonntagabend bleibt kein Tag`() {
        val plan = assertNotNull(planWeek(WeekPlanInput(30.0, at(SUNDAY, 19), habits())))
        assertEquals(WeekPlanStatus.KEINE_TAGE, plan.status)
        assertTrue(plan.rides.isEmpty())
        assertEquals(30, plan.shortfallKm)
    }

    @Test
    fun `nach 18 Uhr kein Vorschlag fuer heute`() {
        val plan = assertNotNull(planWeek(WeekPlanInput(30.0, at(THURSDAY, 19), habits())))
        assertTrue(plan.rides.none { it.date == THURSDAY })
    }

    // --- Anzahl und Laenge ---

    @Test
    fun `ein Rest in typischer Laenge wird eine Fahrt`() {
        val plan = assertNotNull(planWeek(WeekPlanInput(28.0, at(WEDNESDAY), habits())))
        assertEquals(1, plan.rides.size)
        assertEquals(30, plan.rides.single().km)
        assertEquals(WeekPlanStatus.MACHBAR, plan.status)
    }

    @Test
    fun `groesserer Rest wird auf mehrere Fahrten in 5-km-Stufen verteilt`() {
        val plan = assertNotNull(planWeek(WeekPlanInput(70.0, at(WEDNESDAY), habits())))
        assertEquals(3, plan.rides.size)
        assertTrue(plan.rides.all { it.km % 5 == 0 })
        assertTrue(plan.rides.sumOf { it.km } in 70..74)
        assertEquals(WeekPlanStatus.MACHBAR, plan.status)
    }

    @Test
    fun `hoechstens drei Fahrten`() {
        val plan = assertNotNull(planWeek(WeekPlanInput(150.0, at(MONDAY), habits(typical = 20.0, longest = 60.0))))
        assertEquals(3, plan.rides.size)
        assertEquals(150, plan.rides.sumOf { it.km })
    }

    @Test
    fun `keine Fahrt ueber dem Anderthalbfachen der laengsten`() {
        val plan = assertNotNull(planWeek(WeekPlanInput(100.0, at(MONDAY), habits(typical = 50.0, longest = 40.0))))
        assertEquals(60, plan.maxRideKm)
        assertTrue(plan.rides.all { it.km <= 60 })
        assertEquals(WeekPlanStatus.MACHBAR, plan.status)
    }

    @Test
    fun `reichen drei Fahrten bis zur Obergrenze nicht, werden es mehr, solange Tage frei sind`() {
        val plan = assertNotNull(planWeek(WeekPlanInput(200.0, at(MONDAY), habits(typical = 30.0, longest = 40.0))))
        assertEquals(WeekPlanStatus.MACHBAR, plan.status)
        assertEquals(4, plan.rides.size)
        assertTrue(plan.rides.all { it.km <= 60 })
        assertEquals(200, plan.rides.sumOf { it.km })
        assertNull(plan.tightCause)
    }

    @Test
    fun `reichen auch fuenf Fahrten nicht, ist die Obergrenze der Grund`() {
        val plan = assertNotNull(planWeek(WeekPlanInput(400.0, at(MONDAY), habits(typical = 30.0, longest = 40.0))))
        assertEquals(WeekPlanStatus.KNAPP, plan.status)
        assertEquals(WEEK_PLAN_MAX_RIDES_TIGHT, plan.rides.size)
        assertTrue(plan.rides.all { it.km == 60 })
        assertEquals(100, plan.shortfallKm)
        assertEquals(WeekPlanTightCause.OBERGRENZE, plan.tightCause)
    }

    @Test
    fun `zu wenige Tage machen es knapp`() {
        val plan = assertNotNull(planWeek(WeekPlanInput(90.0, at(SUNDAY, 9), habits(longest = 40.0))))
        assertEquals(WeekPlanStatus.KNAPP, plan.status)
        assertEquals(listOf(PlannedRide(SUNDAY, 60, WeekPlanReason.FREIER_TAG)), plan.rides)
        assertEquals(30, plan.shortfallKm)
        assertEquals(WeekPlanTightCause.ZU_WENIGE_TAGE, plan.tightCause)
    }

    @Test
    fun `jeder verbleibende Tag bekommt eine Fahrt, bevor es knapp heisst`() {
        // Donnerstag bis Sonntag: vier Tage, gebraucht wuerden fuenf Fahrten bis 60 km.
        val plan = assertNotNull(planWeek(WeekPlanInput(250.0, at(THURSDAY), habits(typical = 30.0, longest = 40.0))))
        assertEquals(listOf(THURSDAY, FRIDAY, SATURDAY, SUNDAY), plan.rides.map { it.date })
        assertEquals(10, plan.shortfallKm)
        assertEquals(WeekPlanTightCause.ZU_WENIGE_TAGE, plan.tightCause)
    }

    @Test
    fun `kleiner Rest wird keine Mini-Fahrten`() {
        val plan = assertNotNull(planWeek(WeekPlanInput(14.0, at(MONDAY), habits(typical = 10.0, longest = 20.0))))
        assertEquals(1, plan.rides.size)
        assertEquals(14, plan.rides.single().km)
    }

    @Test
    fun `ohne Historie gelten die Vorgaben`() {
        val plan = assertNotNull(planWeek(WeekPlanInput(40.0, at(MONDAY), RidingHabits.NONE)))
        assertEquals(37, plan.maxRideKm)
        assertEquals(2, plan.rides.size)
        assertEquals(40, plan.rides.sumOf { it.km })
        assertFalse(plan.withWeather)
        assertTrue(plan.rides.all { it.reason == WeekPlanReason.FREIER_TAG })
    }

    @Test
    fun `Rest wird aufgerundet`() {
        val plan = assertNotNull(planWeek(WeekPlanInput(12.2, at(MONDAY), habits())))
        assertEquals(13, plan.remainingKm)
    }

    // --- Tagwahl ohne Wetter ---

    @Test
    fun `ohne Wetter gewinnen die ueblichen Fahrtage`() {
        val habits = ridingHabits(regularHistory(), MONDAY)
        val plan = assertNotNull(planWeek(WeekPlanInput(80.0, at(MONDAY), habits)))
        assertEquals(listOf(TUESDAY, SATURDAY), plan.rides.map { it.date })
        assertTrue(plan.rides.all { it.reason == WeekPlanReason.UEBLICHER_FAHRTAG })
        assertFalse(plan.withWeather)
    }

    @Test
    fun `Fahrten liegen nicht an aufeinanderfolgenden Tagen, wenn es anders geht`() {
        val plan = assertNotNull(planWeek(WeekPlanInput(75.0, at(MONDAY), habits(typical = 25.0, longest = 40.0))))
        val dates = plan.rides.map { it.date }
        assertEquals(3, dates.size)
        dates.zipWithNext().forEach { (a, b) -> assertTrue(b.toEpochDay() - a.toEpochDay() >= 2, "$a und $b") }
    }

    @Test
    fun `Plan ist nach Datum sortiert`() {
        val share = mapOf(DayOfWeek.SUNDAY to 1.0, DayOfWeek.TUESDAY to 0.5)
        val plan = assertNotNull(planWeek(WeekPlanInput(60.0, at(MONDAY), habits(share = share))))
        assertEquals(plan.rides.sortedBy { it.date }, plan.rides)
    }

    // --- Wetter ---

    @Test
    fun `Regen am ueblichen Tag verschiebt die Fahrt auf einen trockenen`() {
        val habits = ridingHabits(regularHistory(), MONDAY)
        val forecast = listOf(rainy(SATURDAY), dry(SUNDAY), dry(TUESDAY))
        val plan = assertNotNull(planWeek(WeekPlanInput(80.0, at(MONDAY), habits, forecast)))
        assertTrue(plan.rides.none { it.date == SATURDAY })
        assertTrue(plan.withWeather)
        assertTrue(plan.rides.any { it.reason == WeekPlanReason.TROCKEN })
    }

    @Test
    fun `schlechtes Wetter wird nicht verboten`() {
        val forecast = listOf(rainy(SATURDAY), rainy(SUNDAY))
        val plan = assertNotNull(planWeek(WeekPlanInput(30.0, at(SATURDAY, 8), habits(), forecast)))
        assertEquals(1, plan.rides.size)
        assertEquals(WeekPlanReason.REGEN_MOEGLICH, plan.rides.single().reason)
        assertEquals(WeekPlanStatus.MACHBAR, plan.status)
    }

    @Test
    fun `Gruende aus dem Wetter`() {
        fun reasonFor(f: DayForecast): WeekPlanReason =
            assertNotNull(planWeek(WeekPlanInput(20.0, at(SUNDAY, 8), habits(), listOf(f)))).rides.single().reason
        assertEquals(WeekPlanReason.TROCKEN, reasonFor(dry(SUNDAY)))
        assertEquals(WeekPlanReason.REGEN_MOEGLICH, reasonFor(rainy(SUNDAY)))
        assertEquals(WeekPlanReason.WINDIG, reasonFor(dry(SUNDAY).copy(windMaxKmh = 45.0)))
        assertEquals(WeekPlanReason.KALT, reasonFor(dry(SUNDAY, tMax = 0.0)))
        assertEquals(WeekPlanReason.HEISS, reasonFor(dry(SUNDAY, tMax = 35.0)))
        assertEquals(
            WeekPlanReason.WENIG_WIND,
            reasonFor(DayForecast(SUNDAY, precipProbabilityPct = 30, windMaxKmh = 8.0)),
        )
        assertEquals(WeekPlanReason.FREIER_TAG, reasonFor(DayForecast(SUNDAY)))
    }

    @Test
    fun `ueblicher Fahrtag als Grund, wenn das Wetter nichts Besonderes sagt`() {
        val share = mapOf(DayOfWeek.SUNDAY to 0.8)
        val forecast = listOf(DayForecast(SUNDAY, precipProbabilityPct = 30, windMaxKmh = 20.0))
        val plan = assertNotNull(planWeek(WeekPlanInput(20.0, at(SUNDAY, 8), habits(share = share), forecast)))
        assertEquals(WeekPlanReason.UEBLICHER_FAHRTAG, plan.rides.single().reason)
    }

    @Test
    fun `Vorhersage ausserhalb der Woche zaehlt nicht als Wetter`() {
        val plan = assertNotNull(
            planWeek(WeekPlanInput(30.0, at(SATURDAY), habits(), listOf(dry(MONDAY.plusWeeks(1))))),
        )
        assertFalse(plan.withWeather)
    }

    @Test
    fun `die Vorhersage haengt am vorgeschlagenen Tag`() {
        val forecast = listOf(dry(FRIDAY, tMax = 14.0))
        val plan = assertNotNull(planWeek(WeekPlanInput(30.0, at(FRIDAY, 8), habits(), forecast)))
        val ride = plan.rides.first { it.date == FRIDAY }
        assertEquals(14.0, ride.forecast?.tempMaxC)
    }

    @Test
    fun `Wind und Kaelte zaehlen als Abzug, fehlende Werte nicht`() {
        assertEquals(0.0, weatherPenalty(DayForecast(MONDAY)).total)
        assertTrue(weatherPenalty(DayForecast(MONDAY, windMaxKmh = 45.0)).wind > 0.5)
        assertTrue(weatherPenalty(DayForecast(MONDAY, tempMaxC = 1.0)).cold > 0.0)
        assertEquals(1.0, weatherPenalty(DayForecast(MONDAY, precipMm = 6.0)).rain)
    }

    // --- Verteilung ---

    @Test
    fun `Verteilung in 5-km-Stufen, der Rest an die besten Tage`() {
        assertEquals(listOf(35, 40), splitKm(71, listOf(0.1, 0.9), cap = 60))
        assertEquals(listOf(30), splitKm(28, listOf(0.0), cap = 60))
    }

    @Test
    fun `Verteilung auf den Kilometer, wenn 5er-Stufen die Obergrenze reissen`() {
        assertEquals(listOf(22, 22), splitKm(44, listOf(0.0, 0.0), cap = 22))
    }

    @Test
    fun `Verteilung kappt bei der Obergrenze`() {
        assertEquals(listOf(30, 30), splitKm(100, listOf(0.0, 0.0), cap = 30))
        assertEquals(emptyList(), splitKm(10, emptyList(), cap = 30))
    }
}
