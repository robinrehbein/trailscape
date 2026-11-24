package de.trailscape.app.indoor

import de.trailscape.core.indoor.generated.GeneratedProfile
import de.trailscape.core.indoor.generated.buildGeneratedWorld
import de.trailscape.core.indoor.generated.generatedWorldPlan
import de.trailscape.core.indoor.world.IndoorQuality
import de.trailscape.core.indoor.world.WorldBuildListener
import de.trailscape.core.indoor.world.WorldManifest
import de.trailscape.core.indoor.world.WorldPlan
import java.io.File

/**
 * Bau der generierten Welt zur Einheit des Tages (docs/indoor/app.md,
 * „Einheit des Tages", Weg „Generierte Welt") — schmale Abstraktion ueber
 * `core/…/indoor/generated/GeneratedWorldEntry.kt` (Session „generierte Welt"),
 * damit Ablauf, Tests und Bilder ohne deren Umsetzung laufen.
 */
interface IndoorGeneratedWorlds {
    /** Voranschlag ohne Netz; `plan.route` ist die Strecke der Fahrt. */
    fun plan(profile: GeneratedProfile, quality: IndoorQuality, name: String): WorldPlan

    /** Baut die Welt nach [outDir] (fortsetzbar, ohne Netz). */
    fun build(plan: WorldPlan, profile: GeneratedProfile, outDir: File, listener: WorldBuildListener): WorldManifest
}

/** Die echten Einstiege aus `:core`. */
object DefaultIndoorGeneratedWorlds : IndoorGeneratedWorlds {
    override fun plan(profile: GeneratedProfile, quality: IndoorQuality, name: String): WorldPlan =
        generatedWorldPlan(profile, quality, name)

    override fun build(plan: WorldPlan, profile: GeneratedProfile, outDir: File, listener: WorldBuildListener): WorldManifest =
        buildGeneratedWorld(plan, profile, outDir, listener)
}

/** Kennung der Tour-Zuordnung einer generierten Welt (`WorldLibrary.writeSource`); nie verwaist. */
const val GENERATED_SOURCE_PREFIX: String = "generated:"
