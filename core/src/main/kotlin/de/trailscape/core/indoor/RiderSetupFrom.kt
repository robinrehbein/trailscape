package de.trailscape.core.indoor

import de.trailscape.core.TrainingProfile
import de.trailscape.core.defaultCda
import de.trailscape.core.defaultCrr
import de.trailscape.core.defaultDriveEfficiency
import de.trailscape.core.defaultEftpWPerKg
import de.trailscape.core.defaultSetupMassKg
import de.trailscape.core.maxEftpW
import de.trailscape.core.minEftpW

/** Fahrergewicht, wenn das Profil keinen brauchbaren Wert hat — der Vorgabewert von [TrainingProfile.weightKg]. */
internal const val FALLBACK_RIDER_MASS_KG: Double = 75.0

/**
 * Fahrer und Rad aus dem Trainingsprofil: Gewicht, Setup-Masse, CdA, Crr,
 * FTP (wie `eftpW`: Eintrag oder 2,4 W/kg, auf 100..400 W geklemmt) und Wirkungsgrad.
 *
 * Unbrauchbare Werte (nicht endlich, nicht positiv; Wirkungsgrad ueber 1)
 * werden durch die Vorgaben aus `TrainingLoad.kt` ersetzt — die Physik soll nie
 * mit NaN oder einer Masse von 0 rechnen. Die FTP wird bei kaputtem Gewicht aus
 * dem Ersatzgewicht neu abgeleitet.
 *
 * `realGearRatio` ist das grosse Kettenblatt des Gruenderrads auf dem Cog
 * ([REAL_GEAR_RATIO_GRX_BIG], 48/14 — Shimano GRX 820 2×12, 48/31): Das Profil
 * kennt das Rad nicht, und auf dem Cog faehrt man fast immer auf dem grossen
 * Blatt. Die eingefrorene Vertragsvorgabe `RiderSetup.DEFAULT_REAL_GEAR_RATIO`
 * (40/14) bleibt unberuehrt — sie gilt fuer jeden, der `RiderSetup` selbst baut.
 * `difficulty`, `trainerScale`, `gearTable` und der Klemmbereich der
 * Ersatzsteigung bleiben auf ihren Vorgaben; sie stellt die App ein.
 */
internal fun riderSetupFromImpl(profile: TrainingProfile): RiderSetup {
    fun Double.orDefault(default: Double) = if (isFinite() && this > 0) this else default
    val weight = profile.weightKg.orDefault(FALLBACK_RIDER_MASS_KG)
    // Wie TrainingProfile.eftpW, nur mit dem geprueften Gewicht und ohne kaputten Eintrag.
    val override = profile.eftpOverrideW?.takeIf { it.isFinite() && it > 0 }
    val ftp = (override ?: (defaultEftpWPerKg * weight)).coerceIn(minEftpW, maxEftpW)
    return RiderSetup(
        riderMassKg = weight,
        bikeMassKg = profile.setupMassKg.orDefault(defaultSetupMassKg),
        cdaM2 = profile.cda.orDefault(defaultCda),
        profileCrr = profile.crr.orDefault(defaultCrr),
        ftpW = ftp,
        driveEfficiency = profile.driveEfficiency.takeIf { it.isFinite() && it > 0 && it <= 1.0 }
            ?: defaultDriveEfficiency,
        realGearRatio = REAL_GEAR_RATIO_GRX_BIG,
    )
}
