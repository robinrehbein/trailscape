package de.trailscape.core.indoor

import de.trailscape.core.gravity
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Die Rechnung des geführten Abgleichs. Der „Trainer" ist hier ein Modell:
 * `wirksam` ist der Faktor, mit dem er den Rollwiderstandsanteil tatsächlich
 * bremst (1,0 = genau wie gerechnet; 1,2 = 20 % zu schwer).
 */
class FeelCalibrationTest {
    private val rider = IndoorTestRoutes.RIDER
    private val gear = 20
    private val ratio = VirtualGearTable.EVEN.ratios[gear - 1]
    private val cadence = 85.0

    private fun physics() = PhysicsRider.of(rider)
    private fun crr() = effectiveCrr(SurfaceKind.COMPACTED, rider.profileCrr, rider.surfaceStrength)
    private val vG get() = cadence / 60.0 * ratio * WHEEL_CIRCUMFERENCE_M

    /** Messleistung eines Trainers, dessen Rollanteil `wirksam`-fach wirkt. */
    private fun power(wirksam: Double): Double {
        val r = physics()
        val fAero = aeroCoefficient(r.cdaM2, 0.0) * vG * vG
        val fRoll = r.massKg * gravity * crr()
        return (fAero + wirksam * fRoll) * vG / r.efficiency
    }

    private fun samples(
        wirksam: Double,
        n: Int = FEEL_TARGET_WINDOW_S,
        noise: (Int) -> Double = { 0.0 },
        gearAt: (Int) -> Int = { gear },
        speedFactor: Double = 1.0,
    ): List<FeelSample> {
        val p0 = power(wirksam)
        val v = steadySpeedMps(p0, 0.0, crr(), 0.0, 0.0, rider) * speedFactor
        return List(n) { i -> FeelSample(p0 * (1 + noise(i)), cadence, v, gearAt(i), ratio) }
    }

    private fun suggest(r: FeelResult) = assertIs<FeelResult.Suggest>(r).suggestion

    @Test
    fun honestTrainerNeedsNoChange() {
        val s = suggest(evaluateFeel(samples(1.0), rider))
        assertEquals(1.0, s.suggestedResistance, 1e-9)
        assertEquals(1.0, s.powerRatio, 0.01)
        assertTrue(!s.worthChanging)
    }

    @Test
    fun heavyTrainerGetsLighterFactorLightTrainerHeavier() {
        // 25 % zu schwer: Rollanteil wirkt 1,25-fach -> Faktor ~0,8
        val heavy = suggest(evaluateFeel(samples(1.25), rider))
        assertEquals(0.8, heavy.suggestedResistance, 0.051)
        assertTrue(heavy.powerRatio < 1.0)
        // 20 % zu leicht -> Faktor ~1,25
        val light = suggest(evaluateFeel(samples(0.8), rider))
        assertEquals(1.25, light.suggestedResistance, 0.051)
        assertTrue(light.powerRatio > 1.0)
        assertTrue(light.worthChanging)
    }

    @Test
    fun suggestionAppliesOnTopOfCurrentScale() {
        val current = rider.copy(trainerScale = 1.1)
        // Der Trainer bekommt mit 1,1 schon 10 % mehr; wirksam 1,1 * 1,1 = 1,21 -> neu = 1,1/1,21 ~ 0,91
        val s = suggest(evaluateFeel(samples(1.21), current))
        assertEquals(0.9, s.suggestedResistance, 0.051)
        assertEquals(1.1, s.currentResistance, 1e-12)
    }

    @Test
    fun beyondLimitsIsClampedAndMarked() {
        val s = suggest(evaluateFeel(samples(0.66), rider)) // ~1,5 noetig, Grenze 1,3
        assertEquals(RideFeel.RESISTANCE_MAX, s.suggestedResistance, 1e-9)
        assertTrue(s.clamped)
    }

    @Test
    fun absurdFactorIsRejectedNotSuggested() {
        assertEquals(FeelResult.Reject(FeelRejection.IMPLAUSIBLE), evaluateFeel(samples(0.2), rider))
    }

    @Test
    fun tooFewOrTooLowOrBadCadenceIsRejected() {
        assertEquals(FeelResult.Reject(FeelRejection.TOO_FEW_SAMPLES), evaluateFeel(samples(1.0, n = FEEL_MIN_SAMPLES - 1), rider))
        assertEquals(FeelResult.Reject(FeelRejection.TOO_FEW_SAMPLES), evaluateFeel(emptyList(), rider))
        val low = samples(1.0).map { it.copy(powerW = 60.0) }
        assertEquals(FeelResult.Reject(FeelRejection.POWER_TOO_LOW), evaluateFeel(low, rider))
        val slow = samples(1.0).map { it.copy(cadenceRpm = 40.0) }
        assertEquals(FeelResult.Reject(FeelRejection.CADENCE_OUT_OF_RANGE), evaluateFeel(slow, rider))
    }

    @Test
    fun unsteadyPowerIsRejectedSmallNoiseIsNot() {
        // +-3 % Rauschen: ok
        val calm = evaluateFeel(samples(1.0, noise = { if (it % 2 == 0) 0.03 else -0.03 }), rider)
        assertIs<FeelResult.Suggest>(calm)
        // Intervalle (+-25 %): unruhig
        val wild = evaluateFeel(samples(1.0, noise = { if ((it / 10) % 2 == 0) 0.25 else -0.25 }), rider)
        assertEquals(FeelResult.Reject(FeelRejection.UNSTEADY), wild)
        // langsames Wandern (Ermuedung): 12 % Drift
        val drift = evaluateFeel(samples(1.0, noise = { -0.12 + 0.24 * it / FEEL_TARGET_WINDOW_S }), rider)
        assertEquals(FeelResult.Reject(FeelRejection.UNSTEADY), drift)
    }

    @Test
    fun gearChangeAndSpeedMismatchAreRejected() {
        assertEquals(
            FeelResult.Reject(FeelRejection.GEAR_CHANGED),
            evaluateFeel(samples(1.0, gearAt = { if (it < 100) gear else gear + 1 }), rider),
        )
        assertEquals(
            FeelResult.Reject(FeelRejection.INCONSISTENT_SPEED),
            evaluateFeel(samples(1.0, speedFactor = 0.6), rider),
        )
    }

    @Test
    fun nonFiniteSamplesAreIgnoredNotPropagated() {
        val bad = samples(1.0).toMutableList()
        for (i in 0 until 10) bad[i] = bad[i].copy(powerW = Double.NaN)
        val r = evaluateFeel(bad, rider)
        assertIs<FeelResult.Suggest>(r)
        assertTrue(suggest(r).suggestedResistance.isFinite())
        assertEquals(FEEL_TARGET_WINDOW_S - 10, suggest(r).samples)
    }

    @Test
    fun uncertaintyGrowsWithNoise() {
        val calm = suggest(evaluateFeel(samples(1.1, noise = { if (it % 2 == 0) 0.005 else -0.005 }), rider))
        val rough = suggest(evaluateFeel(samples(1.1, noise = { if (it % 2 == 0) 0.05 else -0.05 }), rider))
        assertTrue(rough.uncertaintyPct > calm.uncertaintyPct)
        assertTrue(calm.uncertaintyPct > 0)
    }

    @Test
    fun calibratorWalksThroughPhasesAndRestartsOnGaps() {
        val c = FeelCalibrator(rider)
        val ok = samples(1.0, n = 1).first()
        assertEquals(FeelPhase.WAITING, c.phase)
        c.feed(ok)
        assertEquals(FeelPhase.WARMUP, c.phase)
        repeat(FEEL_WARMUP_S) { c.feed(ok) }
        assertEquals(FeelPhase.MEASURING, c.phase)
        // Aussetzer (kein Tritt) -> von vorn
        c.feed(ok.copy(powerW = 0.0))
        assertEquals(FeelPhase.WAITING, c.phase)
        // Gangwechsel -> von vorn, der neue Gang beginnt als Einlaufen
        c.feed(ok)
        c.feed(ok.copy(gearIndex = gear + 1))
        assertEquals(FeelPhase.WARMUP, c.phase)
        // volle Messung
        val d = FeelCalibrator(rider)
        repeat(1 + FEEL_WARMUP_S + 1 + FEEL_TARGET_WINDOW_S) { d.feed(ok) }
        assertEquals(FeelPhase.DONE, d.phase)
        assertIs<FeelResult.Suggest>(d.result)
        d.feed(null) // nach dem Ende wirkungslos
        assertEquals(FeelPhase.DONE, d.phase)
    }

    @Test
    fun finishNowWithTooLittleIsRejected() {
        val c = FeelCalibrator(rider)
        val ok = samples(1.0, n = 1).first()
        repeat(FEEL_WARMUP_S + 20) { c.feed(ok) }
        c.finishNow()
        assertEquals(FeelResult.Reject(FeelRejection.TOO_FEW_SAMPLES), c.result)
    }

    @Test
    fun flatRouteIsFlatAndLoopsAndRides() {
        val route = flatCalibrationRoute()
        assertTrue(route.isLoop)
        assertEquals(3000.0, route.lengthM, 1.0)
        assertTrue(route.points.all { it.eleM == 100.0 })
        val session = IndoorSessionImpl(
            IndoorSessionConfig(route, IndoorMode.SIM, rider, capabilities = TrainerCapabilities(simulation = true, targetPower = true), startedAtEpochMs = 0),
        )
        var now = 0L
        var last = session.step(now, SessionInput(null))
        repeat(600) {
            now += 100
            last = session.step(now, SessionInput(TrainerTelemetry(180, 85.0, null, now)))
        }
        assertTrue(abs(last.state.gradePct) < 1e-6)
        assertTrue(last.state.speedMps > 5.0)
        assertEquals(SurfaceKind.COMPACTED, last.state.surface)
    }
}

class CalibrationGearTest {
    @Test
    fun picksAGearForModeratePowerAt85Rpm() {
        val rider = IndoorTestRoutes.RIDER
        val shift = calibrationGearShift(rider)
        val index = (startGearIndex(rider.realGearRatio, rider.gearTable) + shift)
        assertTrue(index in 1..VIRTUAL_GEAR_COUNT)
        val ratio = rider.gearTable.ratios[index - 1]
        val v = 85.0 / 60 * ratio * WHEEL_CIRCUMFERENCE_M
        val p = steadyPowerForTest(rider, v)
        assertTrue(p in 100.0..260.0, "Leistung $p")
    }

    private fun steadyPowerForTest(rider: RiderSetup, v: Double): Double {
        val r = PhysicsRider.of(rider)
        val crr = effectiveCrr(SurfaceKind.COMPACTED, rider.profileCrr, rider.surfaceStrength)
        return (aeroCoefficient(r.cdaM2, 0.0) * v * v + r.massKg * gravity * crr) * v / r.efficiency
    }

    @Test
    fun strongerRiderGetsHarderGearButBoundedPower() {
        val weak = calibrationGearShift(IndoorTestRoutes.RIDER.copy(ftpW = 100.0))
        val strong = calibrationGearShift(IndoorTestRoutes.RIDER.copy(ftpW = 400.0))
        assertTrue(strong >= weak)
    }
}
