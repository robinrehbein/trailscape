package de.trailscape.core.indoor

import de.trailscape.core.TrainingProfile
import de.trailscape.core.defaultCda
import de.trailscape.core.defaultCrr
import de.trailscape.core.defaultDriveEfficiency
import de.trailscape.core.defaultSetupMassKg
import kotlin.test.Test
import kotlin.test.assertEquals

/** riderSetupFrom: Uebernahme aus dem Profil und Ersatz unbrauchbarer Werte. */
class RiderSetupTest {
    @Test
    fun `Werte kommen aus dem Profil`() {
        val p = TrainingProfile(ageYears = 40, weightKg = 82.0, setupMassKg = 11.0, cda = 0.32, crr = 0.006, driveEfficiency = 0.96, eftpOverrideW = 270.0)
        assertEquals(
            RiderSetup(riderMassKg = 82.0, bikeMassKg = 11.0, cdaM2 = 0.32, profileCrr = 0.006, ftpW = 270.0, driveEfficiency = 0.96),
            riderSetupFromImpl(p),
        )
        assertEquals(p.eftpW, riderSetupFromImpl(p).ftpW)
    }

    @Test
    fun `FTP ohne Eintrag wie eftpW`() {
        for (w in listOf(30.0, 70.0, 100.0, 200.0)) {
            val p = TrainingProfile(ageYears = 30, weightKg = w)
            assertEquals(p.eftpW, riderSetupFromImpl(p).ftpW, 1e-12)
        }
        val high = TrainingProfile(ageYears = 30, eftpOverrideW = 900.0)
        assertEquals(400.0, riderSetupFromImpl(high).ftpW)
    }

    @Test
    fun `unbrauchbare Werte werden ersetzt`() {
        val p = TrainingProfile(
            ageYears = 30,
            weightKg = Double.NaN,
            setupMassKg = -3.0,
            cda = 0.0,
            crr = Double.POSITIVE_INFINITY,
            driveEfficiency = 1.5,
            eftpOverrideW = Double.NaN,
        )
        val r = riderSetupFromImpl(p)
        assertEquals(FALLBACK_RIDER_MASS_KG, r.riderMassKg)
        assertEquals(defaultSetupMassKg, r.bikeMassKg)
        assertEquals(defaultCda, r.cdaM2)
        assertEquals(defaultCrr, r.profileCrr)
        assertEquals(defaultDriveEfficiency, r.driveEfficiency)
        assertEquals(180.0, r.ftpW, 1e-9) // 2,4 W/kg × 75 kg
        assertEquals(1.0, r.difficulty)
        assertEquals(1.0, r.trainerScale)
        assertEquals(RiderSetup.DEFAULT_REAL_GEAR_RATIO, r.realGearRatio)
    }
}
