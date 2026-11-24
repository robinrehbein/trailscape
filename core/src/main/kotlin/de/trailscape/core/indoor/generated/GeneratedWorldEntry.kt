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
    generatedRouteImpl(profile, name)

/**
 * Voranschlag ohne Netzanfragen (`requests` leer, `hosts` leer). Die
 * `worldId` haengt nur von Profil und Stufe ab.
 */
fun generatedWorldPlan(profile: GeneratedProfile, quality: IndoorQuality, name: String = ""): WorldPlan =
    generatedPlanImpl(profile, quality, name)

/**
 * Baut die Welt nach [outDir] — wie `WorldBuilder.build`, fortsetzbar,
 * `manifest.json` zuletzt. `plan.route` ist die Strecke, die die Sitzung
 * faehrt (bzw. nach dem Bau `WorldTrack.toIndoorRoute`).
 *
 * @throws de.trailscape.core.indoor.world.WorldBuildCancelled nach Abbruch
 */
fun buildGeneratedWorld(plan: WorldPlan, profile: GeneratedProfile, outDir: File, listener: WorldBuildListener): WorldManifest =
    buildGeneratedImpl(plan, profile, outDir, listener)

/**
 * Beschreibung der erfundenen Landschaft zu [profile] fuer Werkzeug und
 * Fehlersuche (Landschaftstyp, Orte, Serpentinen, Seen, gekappte Legs) —
 * eine Zeile je Angabe, kein fester Aufbau.
 */
fun describeGeneratedProfile(profile: GeneratedProfile): List<String> = describeGeneratedImpl(profile)
