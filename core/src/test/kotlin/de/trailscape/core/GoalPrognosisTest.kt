package de.trailscape.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.time.LocalDateTime
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Zielzeit am [Goal] (Format, Rueckwaertskompatibilitaet) und die Prognose aus
 * [predictGoalFinish] samt ihrer Helfer.
 */
class GoalPrognosisTest {

    private companion object {
        const val NOW = 1_760_000_000_000L
        const val DAY_MS = 24L * 60L * 60L * 1000L
    }

    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun ride(
        km: Double,
        ascentM: Double,
        movingMin: Int?,
        daysAgo: Int = 3,
        id: String = "r$km$daysAgo",
        planned: Boolean = false,
    ) = RideSummary(
        id = id,
        name = "Tour",
        createdAt = NOW - daysAgo * DAY_MS,
        updatedAt = NOW - daysAgo * DAY_MS,
        stats = RideStats(
            distanceKm = km,
            ascentM = ascentM,
            descentM = ascentM,
            movingTimeS = movingMin?.let { it * 60 },
        ),
        planned = planned,
    )

    private val goal = Goal(
        name = "Rennen Hügelland",
        distanceKm = 60.0,
        ascentM = 700.0,
        date = NOW + 84 * DAY_MS,
        targetDurationMin = 130,
    )

    // -----------------------------------------------------------------------
    // Format
    // -----------------------------------------------------------------------

    @Test
    fun `Zielzeit im Roundtrip`() {
        val json = goal.toJson()
        assertEquals(130, (json["targetDurationMin"] as kotlinx.serialization.json.JsonPrimitive).content.toInt())
        assertEquals(goal, Goal.fromJson(json))
    }

    @Test
    fun `altes JSON ohne Zielzeit liest sich als null und bleibt byteidentisch`() {
        val old = """{"name":"Alpencross","distanceKm":180.0,"ascentM":null,"date":1735689600000}"""
        val parsed = Goal.fromJson(obj(old))
        assertNull(parsed.targetDurationMin)
        // Ohne Zielzeit wird der Schluessel nicht geschrieben — dasselbe JSON wie vorher.
        assertEquals(old, parsed.toJson().toString())
        assertFalse(parsed.toJson().containsKey("targetDurationMin"))
    }

    @Test
    fun `explizites null oder Unsinn als Zielzeit ergibt null`() {
        val base = """"name":"x","distanceKm":10.0,"ascentM":null,"date":1"""
        assertNull(Goal.fromJson(obj("{$base,\"targetDurationMin\":null}")).targetDurationMin)
        assertNull(Goal.fromJson(obj("{$base,\"targetDurationMin\":0}")).targetDurationMin)
    }

    @Test
    fun `Plan mit Zielzeit im Roundtrip`() {
        val plan = TrainingPlan(createdAt = 1L, goal = goal, level = FitnessLevel.FORTGESCHRITTEN, weeks = emptyList())
        assertEquals(plan, TrainingPlan.fromJson(obj(plan.toJson().toString())))
    }

    @Test
    fun `Zielzeit lesen und schreiben`() {
        assertEquals(130, parseGoalDuration("2:10"))
        assertEquals(130, parseGoalDuration(" 2:10 h "))
        assertEquals(130, parseGoalDuration("2.10"))
        assertEquals(130, parseGoalDuration("2h10"))
        assertEquals(180, parseGoalDuration("3"))
        assertEquals(95, parseGoalDuration("95 min"))
        assertNull(parseGoalDuration(""))
        assertNull(parseGoalDuration("2:75"))
        assertNull(parseGoalDuration("0:00"))
        assertNull(parseGoalDuration("abc"))
        assertEquals("2:10", formatGoalDuration(130))
        assertEquals("0:05", formatGoalDuration(5))
        assertEquals("12:00", formatGoalDuration(720))
    }

    // -----------------------------------------------------------------------
    // Prognose
    // -----------------------------------------------------------------------

    @Test
    fun `ohne passende Touren sagt die Prognose was fehlt`() {
        val short = listOf(ride(10.0, 50.0, 30), ride(15.0, 80.0, 40, daysAgo = 5))
        val result = predictGoalFinish(goal, short, now = NOW)
        assertNull(result.prognosis)
        assertEquals("Fahre 2–3 längere Touren (ab etwa 25 km), dann gibt es eine Prognose.", result.missing)

        val one = predictGoalFinish(goal, listOf(ride(40.0, 400.0, 100)), now = NOW)
        assertNull(one.prognosis)
        assertTrue(one.missing!!.startsWith("Noch eine längere Tour"))
    }

    @Test
    fun `alte, geplante und zeitlose Touren zaehlen nicht`() {
        val rides = listOf(
            ride(50.0, 500.0, 120, daysAgo = 60),
            ride(50.0, 500.0, 120, planned = true, id = "p"),
            ride(50.0, 500.0, null, id = "n"),
        )
        assertNull(predictGoalFinish(goal, rides, now = NOW).prognosis)
    }

    @Test
    fun `gleiche Touren wie das Ziel ergeben ihre eigene Zeit`() {
        // Zwei Touren genau auf Zieldistanz und -profil in 2:20 h: Die
        // Prognose muss (bis auf Rundung) 2:20 h sagen — kein Laengen-,
        // kein Neuland-Zuschlag.
        val rides = listOf(ride(60.0, 700.0, 140, daysAgo = 2), ride(60.0, 700.0, 140, daysAgo = 9))
        val p = assertNotNull(predictGoalFinish(goal, rides, now = NOW).prognosis)
        assertEquals(140, p.currentMin)
        assertFalse(p.beyondLongestRide)
        assertEquals(2, p.ridesUsed)
        assertNull(p.atEventMin)
    }

    @Test
    fun `Hoehenmeter kosten Zeit ueber die Flachaequivalenz`() {
        val rides = listOf(ride(60.0, 0.0, 120, daysAgo = 2), ride(60.0, 0.0, 120, daysAgo = 4))
        val flatGoal = goal.copy(ascentM = 0.0)
        val flat = predictGoalFinish(flatGoal, rides, now = NOW).prognosis!!
        val hilly = predictGoalFinish(goal, rides, now = NOW).prognosis!!
        assertEquals(120, flat.currentMin)
        // 60 km + 700 Hm × 9 m = 66,3 Flach-km bei 30 km/h → 132,6 min.
        assertEquals(133, hilly.currentMin)
    }

    @Test
    fun `Ziel ueber der laengsten Tour bekommt Zuschlag und mehr Unsicherheit`() {
        val rides = listOf(ride(30.0, 0.0, 60, daysAgo = 2), ride(30.0, 0.0, 60, daysAgo = 4))
        val flatGoal = goal.copy(ascentM = 0.0)
        val p = predictGoalFinish(flatGoal, rides, now = NOW).prognosis!!
        assertTrue(p.beyondLongestRide)
        // Basis 120 min × 2^0,06 (≈1,0425) × Neuland 1,10 ≈ 137,6 min.
        assertEquals(138, p.currentMin)
        val same = predictGoalFinish(
            flatGoal,
            listOf(ride(60.0, 0.0, 120, daysAgo = 2), ride(60.0, 0.0, 120, daysAgo = 4)),
            now = NOW,
        ).prognosis!!
        assertTrue(p.uncertaintyMin > same.uncertaintyMin)
    }

    @Test
    fun `Unsicherheit schrumpft mit mehr Touren`() {
        val two = (0 until 2).map { ride(60.0, 700.0, 140, daysAgo = it + 1, id = "a$it") }
        val eight = (0 until 8).map { ride(60.0, 700.0, 140, daysAgo = it + 1, id = "b$it") }
        val u2 = predictGoalFinish(goal, two, now = NOW).prognosis!!.uncertaintyMin
        val u8 = predictGoalFinish(goal, eight, now = NOW).prognosis!!.uncertaintyMin
        assertTrue(u8 < u2, "u8=$u8 u2=$u2")
        // 2 Touren: (4 % + 12 %/√2) × 140 ≈ 17,48 → 17 min.
        assertEquals(17, u2)
    }

    @Test
    fun `juengere Touren wiegen mehr`() {
        val rides = listOf(
            ride(60.0, 0.0, 100, daysAgo = 1, id = "neu"),
            ride(60.0, 0.0, 140, daysAgo = 40, id = "alt"),
        )
        val p = predictGoalFinish(goal.copy(ascentM = 0.0), rides, now = NOW).prognosis!!
        // Ungewichtet laege das Mittel bei 120; die neue, schnelle Tour zieht es runter.
        assertTrue(p.currentMin < 115, "war ${p.currentMin}")
    }

    @Test
    fun `Renntag skaliert mit der Plan-Fitness und ist gedeckelt`() {
        val rides = listOf(ride(60.0, 700.0, 140, daysAgo = 2), ride(60.0, 700.0, 140, daysAgo = 9))
        val modest = predictGoalFinish(goal, rides, now = NOW, currentCtl = 50.0, projectedCtl = 60.0).prognosis!!
        // (60/50)^0,2 ≈ 1,0371 → 140 / 1,0371 ≈ 135.
        assertEquals(135, modest.atEventMin)
        val huge = predictGoalFinish(goal, rides, now = NOW, currentCtl = 30.0, projectedCtl = 90.0).prognosis!!
        // Gedeckelt auf +8 %: 140 / 1,08 ≈ 129,6.
        assertEquals((140 / 1.08).roundToInt(), huge.atEventMin)
    }

    @Test
    fun `Plan-Fitness zaehlt die verbleibenden Aufbauwochen`() {
        val week = 7 * DAY_MS
        fun w(i: Int, kind: WeekKind) = TrainingWeek(i, NOW + (i - 1) * week, NOW + i * week, kind, 50, emptyList())
        val plan = TrainingPlan(
            createdAt = NOW,
            goal = goal,
            level = FitnessLevel.FORTGESCHRITTEN,
            weeks = listOf(
                w(0, WeekKind.AUFBAU), // vorbei
                w(1, WeekKind.AUFBAU), // laufend
                w(2, WeekKind.AUFBAU),
                w(3, WeekKind.ERHOLUNG),
                w(4, WeekKind.TAPER),
                w(5, WeekKind.ZIELWOCHE),
            ),
        )
        assertEquals(50.0 + 2 * defaultTargetRampPerWeek, projectedEventCtl(plan, 50.0, now = NOW + DAY_MS))
        assertNull(projectedEventCtl(plan, null, now = NOW))
    }

    // -----------------------------------------------------------------------
    // Form in Worten
    // -----------------------------------------------------------------------

    private fun series(ctl: List<Double>): List<FitnessPoint> {
        val start = LocalDateTime.of(2026, 1, 1, 0, 0)
        return ctl.mapIndexed { i, c ->
            FitnessPoint(start.plusDays(i.toLong()), 0.0, c, c, 0.0, null, null)
        }
    }

    @Test
    fun `steigende Kurve nennt die Wochen`() {
        // 6 Wochen stetiger Anstieg nach 2 flachen Wochen.
        val ctl = List(14) { 30.0 } + List(43) { 30.0 + (it + 1) * 0.5 }
        val trend = describeFitnessTrend(series(ctl))!!
        assertEquals(FitnessDirection.STEIGT, trend.direction)
        assertEquals(6, trend.weeks)
        assertEquals("Fitness steigt seit 6 Wochen", trend.sentence)
    }

    @Test
    fun `flache und fallende Kurven`() {
        assertEquals("Fitness stabil", describeFitnessTrend(series(List(30) { 40.0 }))!!.sentence)
        val falling = List(20) { 50.0 - it * 0.4 }
        val trend = describeFitnessTrend(series(falling))!!
        assertEquals(FitnessDirection.SINKT, trend.direction)
        assertEquals("Fitness sinkt seit 2 Wochen", trend.sentence)
        assertNull(describeFitnessTrend(series(List(10) { 1.0 })))
    }

    @Test
    fun `Frische als Wort`() {
        assertEquals("frisch", freshnessWord(10.0))
        assertEquals("ausgeglichen", freshnessWord(0.0))
        assertEquals("etwas müde", freshnessWord(-9.0 - 5))
        assertEquals("sehr müde", freshnessWord(-40.0))
        assertEquals("sehr frisch", freshnessWord(30.0))
    }
}
