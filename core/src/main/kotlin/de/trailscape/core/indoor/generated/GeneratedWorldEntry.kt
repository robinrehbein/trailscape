package de.trailscape.core.indoor.generated

import de.trailscape.core.indoor.IndoorRoute
import de.trailscape.core.indoor.world.IndoorQuality
import de.trailscape.core.indoor.world.WorldBuildListener
import de.trailscape.core.indoor.world.WorldManifest
import de.trailscape.core.indoor.world.WorldPlan
import java.io.File

/**
 * # Einstieg: generierte Strecke und Welt
 *
 * Ohne Netz und ohne Server: aus einem [GeneratedProfile] entstehen die
 * Strecke ([IndoorRoute], wie `buildIndoorRoute` sie liefert) und die Welt
 * (dieselben Kacheln, Schichten und Budgets wie bei echten Strecken, nur aus
 * einer erfundenen Landschaft statt aus Geodaten).
 */

/** Die Strecke zum Profil — deterministisch aus [GeneratedProfile.seed]. Laenge = [GeneratedProfile.lengthM]. */
fun generatedIndoorRoute(profile: GeneratedProfile, name: String = ""): IndoorRoute =
    TODO("generierte Strecke")

/**
 * Voranschlag ohne Netzanfragen (`requests` leer, `hosts` leer). Die
 * `worldId` haengt nur von Profil und Stufe ab.
 */
fun generatedWorldPlan(profile: GeneratedProfile, quality: IndoorQuality, name: String = ""): WorldPlan =
    TODO("generierter Voranschlag")

/**
 * Baut die Welt nach [outDir] — wie `WorldBuilder.build`, fortsetzbar,
 * `manifest.json` zuletzt. `plan.route` ist die Strecke, die die Sitzung
 * faehrt (bzw. nach dem Bau `WorldTrack.toIndoorRoute`).
 *
 * @throws de.trailscape.core.indoor.world.WorldBuildCancelled nach Abbruch
 */
fun buildGeneratedWorld(plan: WorldPlan, profile: GeneratedProfile, outDir: File, listener: WorldBuildListener): WorldManifest =
    TODO("generierte Welt")
