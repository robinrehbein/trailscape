package de.trailscape.core.indoor

import de.trailscape.core.gravity
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Gangtabelle, Schalten und das Widerstandsmodell (Drehmomentgleichheit an der Kurbel). */
class VirtualGearsTest {
    private val rider = IndoorTestRoutes.RIDER
    private val real = RiderSetup.DEFAULT_REAL_GEAR_RATIO
    private val wheelRadiusM = 2.1 / (2 * Math.PI)

    @Test
    fun `24 Gaenge geometrisch von 0,75 bis 5,5`() {
        assertEquals(24, VIRTUAL_GEAR_RATIOS.size)
        assertEquals(0.75, VIRTUAL_GEAR_RATIOS.first(), 1e-12)
        assertEquals(5.5, VIRTUAL_GEAR_RATIOS.last(), 1e-12)
        val factor = (5.5 / 0.75).pow(1.0 / 23)
        assertEquals(1.0905, factor, 1e-4)
        for (i in 1 until 24) {
            assertEquals(factor, VIRTUAL_GEAR_RATIOS[i] / VIRTUAL_GEAR_RATIOS[i - 1], 1e-12)
        }
        assertEquals(2.75, VIRTUAL_GEAR_RATIOS[15], 1e-3)
        assertEquals(3.0, VIRTUAL_GEAR_RATIOS[16], 1e-3)
    }

    @Test
    fun `Startgang liegt der echten Uebersetzung am naechsten`() {
        assertEquals(16, startGearIndex(40.0 / 14.0)) // 2,857 → 2,75 (Gang 17 waere 3,0)
        assertEquals(17, startGearIndex(3.0))
        assertEquals(1, startGearIndex(0.2))
        assertEquals(24, startGearIndex(50.0))
        assertEquals(16, startGearIndex(Double.NaN))
        assertEquals(16, startGearIndex(-2.0))
        assertEquals(GearState(16, 24, VIRTUAL_GEAR_RATIOS[15]), gearState(16))
    }

    @Test
    fun `Tabelle wie am Rad - GRX 48-31 mit 11-34`() {
        val r = VirtualGearTable.BIKE_GRX_2X12.ratios
        assertEquals(24, r.size)
        assertEquals(r.sorted(), r)
        assertEquals(31.0 / 34, r.first(), 1e-12)
        assertEquals(48.0 / 11, r.last(), 1e-12)
        val all = listOf(48, 31).flatMap { a -> listOf(11, 12, 13, 14, 15, 17, 19, 21, 24, 27, 30, 34).map { a.toDouble() / it } }
        assertEquals(all.sorted(), r)
        // Doppelungen wie am Rad: 31/11 und 48/17 liegen dicht beieinander und bleiben beide.
        val i = r.indexOfFirst { abs(it - 31.0 / 11) < 1e-12 }
        assertEquals(48.0 / 17, r[i + 1], 1e-12)
        // Startgang: 48/14 genau (Gang 21); in der gleichmaessigen Tabelle Gang 19.
        assertEquals(21, startGearIndex(REAL_GEAR_RATIO_GRX_BIG, VirtualGearTable.BIKE_GRX_2X12))
        assertEquals(48.0 / 14, gearState(21, VirtualGearTable.BIKE_GRX_2X12).ratio, 1e-12)
        assertEquals(19, startGearIndex(REAL_GEAR_RATIO_GRX_BIG))
        assertEquals(VirtualGearTable.EVEN.ratios, VIRTUAL_GEAR_RATIOS)
        assertEquals(GearState(1, 24, 31.0 / 34), gearState(-3, VirtualGearTable.BIKE_GRX_2X12))
        // Auf dem kleinen Blatt 31/14 startet man in Gang 9 (31/14 = 2,214).
        assertEquals(31.0 / 14, gearState(startGearIndex(REAL_GEAR_RATIO_GRX_SMALL, VirtualGearTable.BIKE_GRX_2X12), VirtualGearTable.BIKE_GRX_2X12).ratio, 1e-12)
    }

    @Test
    fun `Drehmomentgleichheit auch mit der Radtabelle und 48-14 am Trainer`() {
        val r = rider.copy(realGearRatio = REAL_GEAR_RATIO_GRX_BIG, gearTable = VirtualGearTable.BIKE_GRX_2X12)
        for (gv in VirtualGearTable.BIKE_GRX_2X12.ratios) {
            val sim = simulationCommand(6.0, 0.008, 0.23, 2.0, gv, r)
            val vReal = 85.0 / 60 * REAL_GEAR_RATIO_GRX_BIG * 2 * Math.PI * wheelRadiusM
            val vVirt = vReal * gv / REAL_GEAR_RATIO_GRX_BIG
            val tv = virtualForce(6.0, 0.008, 0.23, 2.0, 1.0, vVirt) * gv * wheelRadiusM
            val tr = trainerForce(sim, rider.totalMassKg, vReal) * REAL_GEAR_RATIO_GRX_BIG * wheelRadiusM
            assertEquals(tv, tr, 1e-9 * max(1.0, abs(tv)))
        }
    }

    @Test
    fun `Klemmbereich der Ersatzsteigung ist einstellbar`() {
        // Gang 24 an +12 %: 23,6 % — das kommt beim Fahren vor.
        val g24 = simulationCommand(12.0, 0.008, 0.23, 0.0, 5.5, rider)
        assertEquals(23.6, g24.gradePct, 0.1)
        val kickr = rider.copy(minSimGradePct = -10.0, maxSimGradePct = 20.0)
        assertEquals(20.0, simulationCommand(12.0, 0.008, 0.23, 0.0, 5.5, kickr).gradePct)
        assertEquals(-10.0, simulationCommand(-15.0, 0.008, 0.23, 0.0, 3.0, kickr).gradePct)
        assertEquals(5.0, simulationCommand(5.0, 0.0, 0.23, 0.0, real, kickr).gradePct, 0.01)
        // Unbrauchbar → Vorgabe ±40.
        for (bad in listOf(rider.copy(minSimGradePct = 10.0, maxSimGradePct = 5.0), rider.copy(maxSimGradePct = Double.NaN))) {
            assertEquals(-40.0 to 40.0, simGradeRange(bad))
        }
        assertEquals(-SIM_ENCODABLE_GRADE_PCT to 100.0, simGradeRange(rider.copy(minSimGradePct = -1000.0, maxSimGradePct = 100.0)))
    }

    @Test
    fun `Schalten klemmt an den Enden`() {
        assertEquals(17, shiftedGearIndex(16, ShifterEvent.UP))
        assertEquals(15, shiftedGearIndex(16, ShifterEvent.DOWN))
        assertEquals(24, shiftedGearIndex(24, ShifterEvent.UP))
        assertEquals(1, shiftedGearIndex(1, ShifterEvent.DOWN))
        assertEquals(1, gearState(-5).index)
        assertEquals(24, gearState(99).index)
    }

    @Test
    fun `k gleich 1 sendet die Strecke`() {
        val crr = 0.005
        val cw = 0.23
        val sim = simulationCommand(5.0, crr, cw, 2.0, real, rider)
        val th = atan(0.05)
        assertEquals(100 * (sin(th) + crr * cos(th) - crr), sim.gradePct, 1e-12)
        assertEquals(5.0, sim.gradePct, 0.02) // ≈ Steigung der Strecke
        assertEquals(crr, sim.crr, 0.0)
        assertEquals(cw, sim.cwKgPerM, 1e-12)
        assertEquals(2.0, sim.windMps, 1e-12)

        val scaled = simulationCommand(5.0, crr, cw, 0.0, real, rider.copy(trainerScale = 1.2))
        assertEquals(100 * (1.2 * (sin(th) + crr * cos(th)) - crr), scaled.gradePct, 1e-12)
    }

    @Test
    fun `Schwierigkeit daempft nur den Hangabtrieb`() {
        val crr = 0.008
        val half = simulationCommand(10.0, crr, 0.2, 0.0, real, rider.copy(difficulty = 0.5))
        val th = atan(0.05)
        assertEquals(100 * (sin(th) + crr * cos(th) - crr), half.gradePct, 1e-12)
        val zero = simulationCommand(10.0, crr, 0.2, 0.0, real, rider.copy(difficulty = 0.0))
        assertEquals(0.0, zero.gradePct, 1e-12)
        val clampedHigh = simulationCommand(10.0, crr, 0.2, 0.0, real, rider.copy(difficulty = 3.0))
        assertEquals(simulationCommand(10.0, crr, 0.2, 0.0, real, rider).gradePct, clampedHigh.gradePct, 1e-12)
    }

    /** Kraft, die ein Trainer mit Masse m_t aus der Vorgabe rechnet (FTMS-Modell, Steigung linear). */
    private fun trainerForce(sim: TrainerCommand.SetSimulation, massKg: Double, vReal: Double): Double {
        val air = vReal + sim.windMps
        return massKg * gravity * (sim.gradePct / 100 + sim.crr) + sim.cwKgPerM * air * abs(air)
    }

    /** Fahrwiderstand der virtuellen Fahrt mit Schwierigkeit. */
    private fun virtualForce(grade: Double, crr: Double, cw: Double, wind: Double, difficulty: Double, v: Double): Double {
        val th = atan(difficulty * grade / 100)
        val air = v + wind
        return rider.totalMassKg * gravity * (sin(th) + crr * cos(th)) + cw * air * abs(air)
    }

    @Test
    fun `Drehmomentgleichheit an der Kurbel bei gleicher Kadenz`() {
        var checked = 0
        for (gear in listOf(1, 5, 12, 16, 20, 24)) {
            for (grade in listOf(-8.0, -2.0, 0.0, 3.0, 7.5, 12.0)) {
                for (crr in listOf(0.0055, 0.012, 0.035)) { // 0,035 > 0,0254: Rest in die Steigung
                    for (wind in listOf(-4.0, 0.0, 5.0)) {
                        for (difficulty in listOf(1.0, 0.5)) {
                            for (scale in listOf(1.0, 1.15)) {
                                val r = rider.copy(difficulty = difficulty, trainerScale = scale)
                                val gv = VIRTUAL_GEAR_RATIOS[gear - 1]
                                val cw = 0.23
                                val sim = simulationCommand(grade, crr, cw, wind, gv, r)
                                assertTrue(abs(sim.gradePct) < SIM_MAX_ABS_GRADE_PCT && sim.cwKgPerM < SIM_MAX_CW)
                                val trainerMass = rider.totalMassKg / scale
                                for (cadence in listOf(50.0, 75.0, 95.0)) {
                                    val c = cadence / 60
                                    val vReal = c * real * 2 * Math.PI * wheelRadiusM
                                    val vVirt = c * gv * 2 * Math.PI * wheelRadiusM
                                    val torqueVirt = virtualForce(grade, crr, cw, wind, difficulty, vVirt) * gv * wheelRadiusM
                                    val torqueReal = trainerForce(sim, trainerMass, vReal) * real * wheelRadiusM
                                    assertEquals(torqueVirt, torqueReal, 1e-9 * max(1.0, abs(torqueVirt)), "Gang $gear, $grade %, crr $crr, Wind $wind, Kadenz $cadence")
                                    // Gleiche Kadenz, gleiches Moment → gleiche Leistung.
                                    val pVirt = virtualForce(grade, crr, cw, wind, difficulty, vVirt) * vVirt
                                    val pReal = trainerForce(sim, trainerMass, vReal) * vReal
                                    assertEquals(pVirt, pReal, 1e-9 * max(1.0, abs(pVirt)))
                                    checked++
                                }
                            }
                        }
                    }
                }
            }
        }
        assertEquals(6 * 6 * 3 * 3 * 2 * 2 * 3, checked)
    }

    @Test
    fun `schwerer Gang macht die Kurbel schwerer`() {
        // 5 % bei 80 U/min: Moment waechst mit der Uebersetzung.
        val c = 80.0 / 60
        val vReal = c * real * 2 * Math.PI * wheelRadiusM
        val torques = listOf(4, 16, 24).map { gear ->
            val sim = simulationCommand(5.0, 0.008, 0.23, 0.0, VIRTUAL_GEAR_RATIOS[gear - 1], rider)
            trainerForce(sim, rider.totalMassKg, vReal) * real * wheelRadiusM
        }
        assertTrue(torques[0] < torques[1] && torques[1] < torques[2], "$torques")
    }

    @Test
    fun `Kodierungsbereiche werden geklemmt`() {
        val steep = simulationCommand(25.0, 0.035, 0.23, 0.0, 5.5, rider)
        assertEquals(SIM_MAX_ABS_GRADE_PCT, steep.gradePct)
        assertEquals(SIM_MAX_CRR, steep.crr)
        val down = simulationCommand(-25.0, 0.008, 0.23, 0.0, 5.5, rider)
        assertEquals(-SIM_MAX_ABS_GRADE_PCT, down.gradePct)
        val windy = simulationCommand(0.0, 0.008, 0.6, 40.0, 0.75, rider)
        assertEquals(SIM_MAX_ABS_WIND_MPS, windy.windMps) // 40 / 0,2625
        assertEquals(0.6 * (0.75 / real).pow(3), windy.cwKgPerM, 1e-12)
        val tail = simulationCommand(0.0, 0.008, 0.6, -40.0, 0.75, rider)
        assertEquals(-SIM_MAX_ABS_WIND_MPS, tail.windMps)
        val aero = simulationCommand(0.0, 0.008, 0.6, 0.0, 5.5, rider) // 0,6 · 1,925³ ≈ 4,3
        assertEquals(SIM_MAX_CW, aero.cwKgPerM)
        val broken = simulationCommand(Double.NaN, -1.0, Double.NaN, Double.NaN, Double.NaN, rider.copy(trainerScale = Double.NaN))
        assertEquals(TrainerCommand.SetSimulation(0.0, 0.0, 0.0, 0.0), broken)
    }
}
