package de.trailscape.core.indoor

import de.trailscape.core.airDensity
import de.trailscape.core.gravity
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Fahrphysik: Gleichgewicht, Anfahren, Rollen, Stehenbleiben, Wind, Schrittweite. */
class IndoorPhysicsTest {
    private val rider = IndoorTestRoutes.RIDER // 87 kg gesamt, CdA 0,38
    private val crr = 0.008

    private fun run(v0: Double, power: Double, grade: Double, seconds: Double, dt: Double, wind: Double = 0.0): PhysicsStep {
        var v = v0
        var d = 0.0
        val n = Math.round(seconds / dt).toInt()
        repeat(n) {
            val s = advancePhysics(v, power, grade, crr, 0.0, wind, rider, dt)
            v = s.speedMps
            d += s.distanceM
        }
        return PhysicsStep(v, d)
    }

    @Test
    fun `200 W flach ergibt rund 30 km pro h`() {
        val v = steadySpeedMps(200.0, 0.0, crr, 0.0, 0.0, rider)
        // Von Hand: 194 W = v·(87·9,80665·0,008 + 0,5·1,225·0,38·v²) → v ≈ 8,38 m/s.
        assertEquals(8.38, v, 0.02)
        assertTrue(v * 3.6 in 30.0..33.0, "${v * 3.6} km/h")
        val force = 87 * gravity * crr + 0.5 * airDensity(0.0) * 0.38 * v * v
        assertEquals(200.0 * 0.97, v * force, 1e-6)
    }

    @Test
    fun `Anfahren aus dem Stand naehert sich dem Gleichgewicht`() {
        val target = steadySpeedMps(200.0, 0.0, crr, 0.0, 0.0, rider)
        val after10 = run(0.0, 200.0, 0.0, 10.0, 0.1)
        assertTrue(after10.speedMps in 4.0..target, "${after10.speedMps}")
        val after120 = run(0.0, 200.0, 0.0, 120.0, 0.1)
        assertEquals(target, after120.speedMps, target * 0.005)
    }

    @Test
    fun `Antriebskraft beim Anfahren ist endlich`() {
        // v = 0: Kraft wie bei 1 m/s → a = 300·0,97 / 88,5 kg − Rollen ≈ 3,2 m/s².
        val s = advancePhysics(0.0, 300.0, 0.0, crr, 0.0, 0.0, rider, 0.05)
        val a = s.speedMps / 0.05
        val expected = (300 * 0.97 - 87 * gravity * crr) / (87 + ROTATING_MASS_KG)
        assertEquals(expected, a, 1e-9)
    }

    @Test
    fun `Drehmasse zaehlt zur traegen Masse`() {
        val s = advancePhysics(5.0, 0.0, 0.0, 0.0, 0.0, -5.0, rider, 0.05) // Luft steht relativ, kein Rollen
        assertEquals(5.0, s.speedMps, 1e-12)
        val withPower = advancePhysics(5.0, 500.0, 0.0, 0.0, 0.0, -5.0, rider, 0.05)
        assertEquals(500 * 0.97 / 5.0 / (87 + 1.5) * 0.05, withPower.speedMps - 5.0, 1e-12)
    }

    @Test
    fun `bergab ohne Leistung rollt man bis zur Endgeschwindigkeit`() {
        val terminal = steadySpeedMps(0.0, -6.0, crr, 0.0, 0.0, rider)
        assertTrue(terminal > 10.0, "$terminal")
        // Gleichgewicht: Hangabtrieb = Rollen + Luft.
        val th = atan(-0.06)
        val f = 87 * gravity * (sin(th) + crr * cos(th)) + 0.5 * airDensity(0.0) * 0.38 * terminal * terminal
        assertEquals(0.0, f, 1e-6)
        val s = run(0.0, 0.0, -6.0, 300.0, 0.1)
        assertEquals(terminal, s.speedMps, terminal * 0.01)
    }

    @Test
    fun `bergauf ohne Leistung bleibt man stehen statt rueckwaerts zu rollen`() {
        val s = run(5.0, 0.0, 8.0, 10.0, 0.1)
        assertEquals(0.0, s.speedMps)
        assertTrue(s.distanceM in 0.1..20.0, "${s.distanceM}")
        val still = advancePhysics(0.0, 0.0, 8.0, crr, 0.0, 0.0, rider, 2.0)
        assertEquals(0.0, still.speedMps)
        assertEquals(0.0, still.distanceM)
        assertEquals(0.0, steadySpeedMps(0.0, 8.0, crr, 0.0, 0.0, rider))
        // Ganz flach ohne Leistung: Rollen haelt an, nichts schiebt.
        assertEquals(0.0, steadySpeedMps(0.0, 0.0, crr, 0.0, 0.0, rider))
    }

    @Test
    fun `zwei Sekunden am Stueck rechnen wie zwanzig Zehntel`() {
        val coarse = run(3.0, 250.0, 2.0, 60.0, 2.0)
        val fine = run(3.0, 250.0, 2.0, 60.0, 0.1)
        assertEquals(fine.speedMps, coarse.speedMps, 1e-9)
        assertEquals(fine.distanceM, coarse.distanceM, 1e-6)
        // Auch extreme Werte bleiben endlich und stabil.
        val wild = run(0.0, 2000.0, -25.0, 60.0, 2.0)
        assertTrue(wild.speedMps.isFinite() && wild.speedMps in 0.0..60.0, "${wild.speedMps}")
    }

    @Test
    fun `Gegenwind bremst, Rueckenwind schiebt`() {
        val calm = steadySpeedMps(200.0, 0.0, crr, 0.0, 0.0, rider)
        val head = steadySpeedMps(200.0, 0.0, crr, 0.0, 5.0, rider)
        val tail = steadySpeedMps(200.0, 0.0, crr, 0.0, -5.0, rider)
        assertTrue(head < calm - 1.0 && tail > calm + 1.0, "$head $calm $tail")
        // Starker Rueckenwind schiebt sogar ohne Leistung: cw·(15 − v)² = Rollen.
        val pushed = steadySpeedMps(0.0, 0.0, crr, 0.0, -15.0, rider)
        val cw = 0.5 * airDensity(0.0) * 0.38
        assertEquals(87 * gravity * crr, cw * (15 - pushed) * (15 - pushed), 1e-6)
        assertTrue(run(0.0, 0.0, 0.0, 120.0, 0.1, wind = -15.0).speedMps > 8.0)
    }

    @Test
    fun `Gegenwindanteil aus Windrichtung und Fahrtrichtung`() {
        val wind = IndoorWind(speedMps = 6.0, fromDeg = 270.0) // Westwind
        assertEquals(6.0, headwindMps(wind, 270.0), 1e-9) // nach Westen: voll dagegen
        assertEquals(-6.0, headwindMps(wind, 90.0), 1e-9) // nach Osten: voll von hinten
        assertEquals(0.0, headwindMps(wind, 0.0), 1e-9) // nach Norden: quer
        assertEquals(6.0 * cos(Math.toRadians(45.0)), headwindMps(wind, 315.0), 1e-9)
        assertEquals(0.0, headwindMps(IndoorWind.CALM, 123.0), 0.0)
        assertEquals(0.0, headwindMps(IndoorWind(Double.NaN, 0.0), 0.0), 0.0)
    }

    @Test
    fun `Hoehe senkt die Luftdichte und hebt das Tempo`() {
        val low = steadySpeedMps(200.0, 0.0, crr, 0.0, 0.0, rider)
        val high = steadySpeedMps(200.0, 0.0, crr, 2000.0, 0.0, rider)
        assertTrue(high > low + 0.2, "$low $high")
    }

    @Test
    fun `kaputte Eingaben ergeben kein NaN`() {
        val s = advancePhysics(Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, rider, 1.0)
        assertTrue(s.speedMps.isFinite() && s.distanceM.isFinite())
        val broken = rider.copy(riderMassKg = Double.NaN, cdaM2 = -1.0, driveEfficiency = 3.0)
        val b = advancePhysics(5.0, 200.0, 0.0, crr, 0.0, 0.0, broken, 1.0)
        assertTrue(b.speedMps.isFinite() && b.speedMps > 0)
        assertEquals(PhysicsStep(4.0, 0.0), advancePhysics(4.0, 200.0, 0.0, crr, 0.0, 0.0, rider, 0.0))
        assertEquals(PhysicsStep(4.0, 0.0), advancePhysics(4.0, 200.0, 0.0, crr, 0.0, 0.0, rider, -1.0))
    }
}
