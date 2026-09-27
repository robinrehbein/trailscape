package de.trailscape.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Tests der Wirkung einer Tour (`RideImpact.kt`). */
class RideImpactTest {

    private val day = 86_400_000L

    /** Montag der Planwoche (ms seit Epoch) — der genaue Wert ist egal. */
    private val weekStart = T0

    private fun ride(
        id: String,
        createdAt: Long,
        km: Double,
        planned: Boolean = false,
    ): RideSummary = RideSummary(
        id = id,
        name = id,
        createdAt = createdAt,
        updatedAt = createdAt,
        stats = RideStats(distanceKm = km, ascentM = 0.0, descentM = 0.0),
        planned = planned,
        pointCount = 10,
    )

    private fun week(start: Long, targetKm: Int, index: Int = 0): TrainingWeek = TrainingWeek(
        index = index,
        start = start,
        end = start + 7 * day,
        kind = WeekKind.AUFBAU,
        targetKm = targetKm,
        sessions = emptyList(),
    )

    private fun plan(vararg weeks: TrainingWeek): TrainingPlan = TrainingPlan(
        createdAt = T0 - 30 * day,
        goal = Goal(name = "Ziel", distanceKm = 100.0, date = T0 + 60 * day),
        level = FitnessLevel.FORTGESCHRITTEN,
        weeks = weeks.toList(),
    )

    /**
     * Lueckenlose Historie mit taeglich [daily] Last vom Januar-Tag [from]
     * (Werte < 1 reichen in den Dezember zurueck) bis einschliesslich [days].
     */
    private fun series(
        days: Int,
        daily: Double = 40.0,
        extra: List<LoadEntry> = emptyList(),
        from: Int = 1,
    ): FitnessSeries =
        computeFitnessSeries(
            dailyLoadsFrom((from..days).map { LoadEntry(dt(2024, 1, it, 8), daily) } + extra),
        )

    private fun view(
        segmentId: String,
        timeS: Int,
        previousBestTimeS: Int?,
        isNewBest: Boolean = previousBestTimeS != null && timeS < previousBestTimeS,
        startedAt: Long = 0L,
        name: String = "Anstieg $segmentId",
    ): SegmentEffortView = SegmentEffortView(
        segmentId = segmentId,
        name = name,
        distanceM = 1200.0,
        ascentM = 80.0,
        startedAt = startedAt,
        timeS = timeS,
        bestTimeS = timeS,
        effortCount = 2,
        rank = 1,
        deltaToBestS = 0,
        isNewBest = isNewBest,
        previousBestTimeS = previousBestTimeS,
    )

    private fun impact(
        target: RideSummary = ride("diese", weekStart + day, 40.0),
        rideLoad: Double? = null,
        fitness: FitnessSeries = FitnessSeries.EMPTY,
        plan: TrainingPlan? = null,
        rides: List<RideInfo> = listOf(target),
        newTiles: Int? = null,
        views: List<SegmentEffortView> = emptyList(),
    ): RideImpact? = computeRideImpact(
        ride = target,
        rideAt = dt(2024, 1, 31, 10),
        rideLoad = rideLoad,
        fitness = fitness,
        plan = plan,
        rides = rides,
        newTiles = newTiles,
        segmentViews = views,
    )

    // ----------------------------------------------------------------- Ganz

    @Test
    fun `geplante Tour hat keine Wirkung`() {
        val planned = ride("plan", weekStart + day, 40.0, planned = true)
        val result = impact(
            target = planned,
            rideLoad = 80.0,
            fitness = series(31),
            plan = plan(week(weekStart, 100)),
            newTiles = 5,
            views = listOf(view("s", 200, 220)),
        )
        assertNull(result)
    }

    @Test
    fun `ohne jede Datengrundlage entfaellt der Block`() {
        assertNull(impact())
    }

    // ----------------------------------------------------------------- Form

    @Test
    fun `Fitness und Muedigkeit steigen genau um lambda mal Last`() {
        val fitness = series(31)
        val result = assertNotNull(impact(rideLoad = 80.0, fitness = fitness))
        val form = assertNotNull(result.form)
        val point = assertNotNull(fitness.at(dt(2024, 1, 31)))

        assertEquals(80.0, form.load)
        assertEquals(lambdaCtl * 80, form.fitnessGain, 1e-9)
        assertEquals(lambdaAtl * 80, form.fatigueGain, 1e-9)
        assertEquals(point.ctl - point.atl, form.freshnessAfter, 1e-9)
        assertTrue(form.noticeable)
    }

    @Test
    fun `Beitrag entspricht der Differenz der Kurve mit und ohne diese Tour`() {
        // Gegenprobe zur Linearitaet: die Kurve einmal mit, einmal ohne die
        // 80 Punkte am Fahrtag rechnen. Historie > 42 Tage, damit der
        // Startwert (Mittel der ersten 42 Tage) den Fahrtag nicht enthaelt.
        val without = series(31, from = -40)
        val with = series(31, from = -40, extra = listOf(LoadEntry(dt(2024, 1, 31, 10), 80.0)))
        val result = assertNotNull(impact(rideLoad = 80.0, fitness = with))
        val form = assertNotNull(result.form)
        val a = assertNotNull(without.at(dt(2024, 1, 31)))
        val b = assertNotNull(with.at(dt(2024, 1, 31)))
        assertEquals(b.ctl - a.ctl, form.fitnessGain, 1e-9)
        assertEquals(b.atl - a.atl, form.fatigueGain, 1e-9)
    }

    @Test
    fun `zweite Tour am selben Tag aendert den eigenen Beitrag nicht`() {
        val fitness = series(31, extra = listOf(LoadEntry(dt(2024, 1, 31, 17), 120.0)))
        val result = assertNotNull(impact(rideLoad = 80.0, fitness = fitness))
        val form = assertNotNull(result.form)
        assertEquals(lambdaCtl * 80, form.fitnessGain, 1e-9)
        assertEquals(lambdaAtl * 80, form.fatigueGain, 1e-9)
        // Die Frische danach meint das Tagesende — mit beiden Touren.
        val point = assertNotNull(fitness.at(dt(2024, 1, 31)))
        assertEquals(point.ctl - point.atl, form.freshnessAfter, 1e-9)
    }

    @Test
    fun `ohne 28 Tage Historie keine Formzeile`() {
        val fitness = series(20)
        assertFalse(fitness.displayReady)
        val result = impact(
            rideLoad = 80.0,
            fitness = fitness,
            newTiles = 3,
        )
        assertNotNull(result)
        assertNull(result.form)
        assertEquals(3, result.newTiles)
    }

    @Test
    fun `ohne Last oder mit Last 0 keine Formzeile`() {
        val fitness = series(31)
        assertNull(impact(rideLoad = null, fitness = fitness))
        assertNull(impact(rideLoad = 0.0, fitness = fitness))
        assertNull(impact(rideLoad = Double.NaN, fitness = fitness))
    }

    @Test
    fun `ohne Punkt fuer den Fahrtag keine Formzeile`() {
        // Kurve endet am 25.1., die Tour liegt am 31.1.
        val result = impact(rideLoad = 80.0, fitness = series(25).copy(displayReady = true))
        assertNull(result)
    }

    @Test
    fun `kleine Last ist nicht spuerbar`() {
        val result = assertNotNull(impact(rideLoad = 10.0, fitness = series(31)))
        val form = assertNotNull(result.form)
        assertTrue(form.fitnessGain < rideImpactNoticeableFitnessGain)
        assertFalse(form.noticeable)
    }

    // ------------------------------------------------------------ Wochenziel

    @Test
    fun `Wochenziel zaehlt nur Touren bis einschliesslich dieser`() {
        val target = ride("diese", weekStart + 3 * day, 42.0)
        val rides = listOf(
            ride("montag", weekStart + 2 * 3_600_000L, 60.0),
            ride("mittwoch", weekStart + 2 * day, 40.0),
            ride("geplant", weekStart + day, 80.0, planned = true),
            ride("spaeter", weekStart + 5 * day, 55.0),
            ride("vorwoche", weekStart - day, 70.0),
            target,
        )
        val result = assertNotNull(impact(target = target, plan = plan(week(weekStart - 7 * day, 150, 0), week(weekStart, 180, 1)), rides = rides))
        val goal = assertNotNull(result.weekGoal)
        assertEquals(142.0, goal.kmAfter, 1e-9)
        assertEquals(42.0, goal.rideKm, 1e-9)
        assertEquals(180, goal.targetKm)
        assertFalse(goal.reachedByThisRide)
    }

    @Test
    fun `die Tour selbst zaehlt auch wenn sie in rides noch fehlt`() {
        val target = ride("neu", weekStart + 3 * day, 42.0)
        val result = assertNotNull(
            impact(
                target = target,
                plan = plan(week(weekStart, 180)),
                rides = listOf(ride("davor", weekStart + day, 100.0)),
            ),
        )
        val goal = assertNotNull(result.weekGoal)
        assertEquals(142.0, goal.kmAfter, 1e-9)
    }

    @Test
    fun `Wochenziel mit dieser Tour geschafft`() {
        fun goalFor(beforeKm: Double, rideKm: Double): RideWeekGoalImpact {
            val target = ride("diese", weekStart + 3 * day, rideKm)
            val rides = listOf(ride("davor", weekStart + day, beforeKm), target)
            val result = assertNotNull(impact(target = target, plan = plan(week(weekStart, 180)), rides = rides))
            return assertNotNull(result.weekGoal)
        }

        assertTrue(goalFor(150.0, 34.0).reachedByThisRide)
        assertFalse(goalFor(190.0, 34.0).reachedByThisRide, "schon vorher erreicht")
        assertFalse(goalFor(100.0, 20.0).reachedByThisRide, "noch nicht erreicht")
        // Die Grenze vergleicht die gerundete Zahl: 179,6 km zeigt „180 von 180 km".
        assertTrue(goalFor(150.0, 29.6).reachedByThisRide)
        assertFalse(goalFor(179.6, 10.0).reachedByThisRide, "179,6 gilt schon vorher als 180")
    }

    @Test
    fun `kein Plan, Tour ausserhalb aller Planwochen oder Ziel 0 km ergibt keine Wochenzeile`() {
        val target = ride("diese", weekStart + 3 * day, 42.0)
        assertNull(impact(target = target, plan = null))
        assertNull(impact(target = target, plan = plan(week(weekStart + 7 * day, 180))))
        assertNull(impact(target = target, plan = plan(week(weekStart, 0))))
        // Die Wochengrenze ist exklusiv: Folgemontag 00:00 gehoert nicht mehr dazu.
        val nextMonday = ride("montag", weekStart + 7 * day, 42.0)
        assertNull(impact(target = nextMonday, plan = plan(week(weekStart, 180))))
    }

    // ------------------------------------------------------------- Kacheln

    @Test
    fun `null oder 0 neue Kacheln erscheinen nicht`() {
        assertNull(impact(newTiles = null))
        assertNull(impact(newTiles = 0))
        assertEquals(5, assertNotNull(impact(newTiles = 5)).newTiles)
    }

    // ------------------------------------------------------------ Bestzeiten

    @Test
    fun `nur neue Bestzeiten, je Segment die schnellste, nach Verbesserung sortiert`() {
        val views = listOf(
            // Zwei Runden auf demselben Segment: die zweite ist schneller.
            view("a", timeS = 290, previousBestTimeS = 300, startedAt = 1),
            view("a", timeS = 280, previousBestTimeS = 290, startedAt = 2),
            // Deutlich schneller als frueher.
            view("b", timeS = 200, previousBestTimeS = 260, startedAt = 3),
            // Keine neue Bestzeit.
            view("c", timeS = 400, previousBestTimeS = 350, startedAt = 4),
            // Erste Befahrung ueberhaupt.
            view("d", timeS = 100, previousBestTimeS = null, startedAt = 5),
        )
        val bests = assertNotNull(impact(views = views)).newBests
        assertEquals(listOf("b", "a"), bests.map { it.segmentId })
        assertEquals(60, bests[0].improvementS)
        assertEquals(200, bests[0].timeS)
        assertEquals(280, bests[1].timeS)
        assertEquals(10, bests[1].improvementS)
        assertEquals("Anstieg b", bests[0].name)
    }

    @Test
    fun `ohne neue Bestzeit keine Bestzeitzeile`() {
        val views = listOf(view("c", timeS = 400, previousBestTimeS = 350))
        assertNull(impact(views = views))
    }
}
