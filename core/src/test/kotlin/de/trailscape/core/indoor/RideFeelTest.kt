package de.trailscape.core.indoor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Fahrgefuehl: Grenzen, Vorgabe = heutiges Verhalten, Spreizung der Crr-Tabelle. */
class RideFeelTest {
    private val rider = IndoorTestRoutes.RIDER

    @Test
    fun defaultChangesNothing() {
        assertTrue(RideFeel.DEFAULT.isDefault)
        assertEquals(rider.copy(trainerScale = 1.0, surfaceStrength = 1.0), RideFeel.DEFAULT.applyTo(rider))
        for (k in SurfaceKind.entries) {
            assertEquals(effectiveCrr(k, rider.profileCrr), effectiveCrr(k, rider.profileCrr, 1.0), 1e-12)
        }
    }

    @Test
    fun limitsClampAndGarbageFallsBack() {
        val f = RideFeel(5.0, -3.0).sanitized()
        assertEquals(RideFeel.RESISTANCE_MAX, f.resistance)
        assertEquals(RideFeel.SURFACE_MIN, f.surfaceStrength)
        assertEquals(RideFeel.DEFAULT, RideFeel(Double.NaN, Double.POSITIVE_INFINITY).sanitized().let { RideFeel(it.resistance, it.surfaceStrength) }.let {
            // NaN -> Vorgabe, +Inf ist nicht endlich -> Vorgabe
            RideFeel(it.resistance, it.surfaceStrength)
        })
        assertEquals(RideFeel.DEFAULT, RideFeel.fromStored(null, "abc"))
        assertEquals(0.8, RideFeel.fromStored(" 0.8 ", null).resistance, 1e-12)
        assertFalse(RideFeel(1.05, 1.0).isDefault)
    }

    @Test
    fun strengthSpreadsAroundMixedValue() {
        val mixed = SurfaceKind.UNKNOWN.crr
        // 0: jeder Belag wie Mischwert
        for (k in SurfaceKind.entries) assertEquals(mixed, effectiveCrr(k, 0.008, 0.0), 1e-12)
        // 2: Abstand zum Mischwert verdoppelt (Schotter 0,012 -> 0,016; Asphalt 0,0055 -> 0,003)
        assertEquals(0.016, effectiveCrr(SurfaceKind.GRAVEL, 0.008, 2.0), 1e-12)
        assertEquals(0.003, effectiveCrr(SurfaceKind.ASPHALT, 0.008, 2.0), 1e-12)
        // nie unter der Untergrenze, auch nicht bei Strecken mit sehr glattem Belag
        assertTrue(effectiveCrr(SurfaceKind.ASPHALT, 0.008, 2.0) >= MIN_EFFECTIVE_CRR)
        // Profil-Crr skaliert danach wie bisher
        assertEquals(2 * effectiveCrr(SurfaceKind.DIRT, 0.008, 1.5), effectiveCrr(SurfaceKind.DIRT, 0.016, 1.5), 1e-12)
    }

    @Test
    fun resistanceActsOnTheTrainerCommandOnly() {
        // trainerScale skaliert den massenabhaengigen Anteil der gesendeten Steigung (VirtualGears.kt).
        val base = simulationCommand(4.0, 0.008, 0.2, 0.0, rider.realGearRatio, rider)
        val heavier = simulationCommand(4.0, 0.008, 0.2, 0.0, rider.realGearRatio, RideFeel(1.2, 1.0).applyTo(rider))
        assertTrue(heavier.gradePct > base.gradePct)
        // das Tempo der virtuellen Fahrt haengt nicht daran
        val a = steadySpeedMps(180.0, 4.0, 0.008, 0.0, 0.0, rider)
        val b = steadySpeedMps(180.0, 4.0, 0.008, 0.0, 0.0, RideFeel(1.2, 1.0).applyTo(rider))
        assertEquals(a, b, 1e-9)
    }
}
