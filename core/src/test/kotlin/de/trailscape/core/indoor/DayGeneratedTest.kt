package de.trailscape.core.indoor

import de.trailscape.core.SessionIntensity
import de.trailscape.core.TrainingSession
import de.trailscape.core.i18n.SessionTextKey
import de.trailscape.core.indoor.generated.GeneratedProfile.Kind
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Zielprofil der generierten Welt: Fahrzeit = Einheit, Anstiege unter den Intervallen, stabiler Seed. */
class DayGeneratedTest {
    private val rider = RiderSetup(riderMassKg = 75.0, bikeMassKg = 10.0, cdaM2 = 0.4, profileCrr = 0.008, ftpW = 250.0)

    private fun workout(intensity: SessionIntensity, minutes: Int, key: SessionTextKey? = null, args: List<Int> = emptyList()) =
        ergWorkoutForImpl(TrainingSession("", "t", "", 20, intensity, minutes, textKey = key, textArgs = args), "t")!!

    private val intervals = workout(SessionIntensity.HART, 52, SessionTextKey.INTERVALS, listOf(10, 3, 8, 4, 10, 52, 0))

    /** Fahrzeit des Profils bei den Sollleistungen, Abschnitt fuer Abschnitt der Einheit. */
    private fun rideSeconds(goal: DayRouteGoal): Double {
        val p = dayGeneratedProfile(goal)
        // Je Leg die Zeit; die Leistung ist die des Einheitsabschnitts, der auf diesem Leg liegt.
        var t = 0.0
        var legIdx = 0
        var leftInLeg = p.legs[0].lengthM
        for (s in goal.workout.steps) {
            var rest = s.durationS.toDouble()
            while (rest > 1e-6 && legIdx < p.legs.size) {
                val leg = p.legs[legIdx]
                val v = dayGeneratedSpeedMps(s.ftpFraction * rider.ftpW, leg.gradePct, rider)
                val dt = minOf(rest, leftInLeg / v)
                leftInLeg -= v * dt
                rest -= dt
                t += dt
                if (leftInLeg <= 1e-6) {
                    legIdx++
                    if (legIdx < p.legs.size) leftInLeg = p.legs[legIdx].lengthM
                }
            }
        }
        return t
    }

    @Test
    fun `Intervalle werden Anstiege, Pausen leichte Abfahrten, Ein- und Ausfahren flach`() {
        val p = dayGeneratedProfile(dayRouteGoal(intervals, rider))
        assertEquals(
            listOf(Kind.FLAT, Kind.CLIMB, Kind.DESCENT, Kind.CLIMB, Kind.DESCENT, Kind.CLIMB, Kind.FLAT),
            p.legs.map { it.kind },
        )
        assertEquals(6.0, p.legs[1].gradePct, 1e-9)
        assertEquals(DAY_GEN_RECOVER_GRADE_PCT, p.legs[2].gradePct, 1e-9)
        assertTrue(p.legs.all { it.lengthM > 0 })
    }

    @Test
    fun `jede Einheit endet am Ziel`() {
        for (w in listOf(intervals, workout(SessionIntensity.GRUNDLAGE, 90), workout(SessionIntensity.LOCKER, 40), workout(SessionIntensity.HART, 60))) {
            val goal = dayRouteGoal(w, rider)
            val t = rideSeconds(goal)
            assertTrue(abs(t - w.totalS) / w.totalS < 0.01, "Fahrzeit $t s statt ${w.totalS} s")
        }
        // Flache Einheit: Laenge = erwartete Distanz auf flacher Strecke.
        val easy = dayRouteGoal(workout(SessionIntensity.LOCKER, 40), rider)
        assertEquals(easy.expectedFlatM, dayGeneratedProfile(easy).lengthM, 1.0)
        assertTrue(dayGeneratedProfile(easy).legs.all { it.kind == Kind.FLAT })
        assertEquals(Kind.ROLLING, dayGeneratedProfile(dayRouteGoal(workout(SessionIntensity.GRUNDLAGE, 90), rider)).legs[1].kind)
    }

    @Test
    fun `gleiche Einheit gleicher Seed, andere Einheit anderer`() {
        assertEquals(dayGeneratedSeed(intervals), dayGeneratedSeed(workout(SessionIntensity.HART, 52, SessionTextKey.INTERVALS, listOf(10, 3, 8, 4, 10, 52, 0))))
        assertEquals(dayGeneratedProfile(dayRouteGoal(intervals, rider)), dayGeneratedProfile(dayRouteGoal(intervals, rider)))
        assertNotEquals(dayGeneratedSeed(intervals), dayGeneratedSeed(workout(SessionIntensity.HART, 52, SessionTextKey.INTERVALS, listOf(10, 4, 6, 3, 10, 52, 0))))
        assertEquals(7.0, climbGradePct(1.0), 1e-9)
        assertEquals(4.0, climbGradePct(0.5), 1e-9)
    }
}
