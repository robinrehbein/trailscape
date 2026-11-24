package de.trailscape.app.indoor

import de.trailscape.app.data.RideStorage
import de.trailscape.app.indoor.catalog.CatalogStore
import de.trailscape.app.indoor.world.WorldLibrary
import de.trailscape.core.RouteTarget
import de.trailscape.core.TodayRoute
import de.trailscape.core.TrainingProfile
import de.trailscape.core.indoor.DayRouteAvailability
import de.trailscape.core.indoor.DayRouteCandidate
import de.trailscape.core.indoor.DayRouteGoal
import de.trailscape.core.indoor.DayRouteMatch
import de.trailscape.core.indoor.DayRouteProfile
import de.trailscape.core.indoor.DaySession
import de.trailscape.core.indoor.DaySessionKind
import de.trailscape.core.indoor.ErgWorkout
import de.trailscape.core.indoor.RiderSetup
import de.trailscape.core.indoor.dayPlanTarget
import de.trailscape.core.indoor.dayRouteGoal
import de.trailscape.core.indoor.daySessionFor
import de.trailscape.core.indoor.rankDayRoutes
import de.trailscape.core.indoor.world.WorldManifest
import java.util.concurrent.ConcurrentHashMap

/**
 * # Die Einheit des Tages auf der Rolle (docs/indoor/app.md, „Einheit des Tages")
 *
 * Verbindet die reine Rechnung aus `core/…/indoor/DayRoute.kt` mit dem, was auf
 * dem Geraet liegt. Alles hier ist **blockierend** (Dateien lesen) und gehoert
 * auf `Dispatchers.IO`; es gibt keinen Netzverkehr — Katalogstrecken werden aus
 * Laenge und Hoehenmetern des mitgelieferten Katalogs geschaetzt.
 *
 * Kandidaten in dieser Reihenfolge (die Reihenfolge entscheidet nur bei
 * Gleichstand, siehe [rankDayRoutes]):
 *  1. Strecken mit fertiger Welt auf dem Geraet: Testwelt, gebaute Welten
 *     eigener Strecken, geladene Katalogwelten — Profil aus dem Streckenband
 *     der Welt ([DayRouteAvailability.READY]);
 *  2. Katalogstrecken, die noch nicht geladen sind (geschaetztes Profil,
 *     [DayRouteAvailability.DOWNLOAD]);
 *  3. eigene gespeicherte Planungen und gefahrene Touren ohne Welt, die
 *     [MAX_SAVED_RIDES] juengsten (Profil aus den Punkten,
 *     [DayRouteAvailability.BUILD]);
 *  4. eine neu zu planende Runde ([DayRouteAvailability.PLAN]) ueber die
 *     vorhandene Routensuche.
 *
 * Profile werden je Strecke einmal gerechnet und gemerkt ([profileCache]):
 * „Heute" und der Streckenschirm fragen dieselben Strecken.
 */
class IndoorDayRouteFinder(
    private val rideStorage: RideStorage,
    private val worlds: WorldLibrary,
    private val catalogStore: CatalogStore?,
) {
    /**
     * Alle Kandidaten mit Profil (blockierend).
     *
     * @param testWorldName Anzeigename der Testwelt.
     * @param languageTag Sprache fuer die Namen der Katalogstrecken.
     * @param planName Anzeigename der neu zu planenden Runde; `null` = keine anbieten.
     */
    fun candidates(testWorldName: String, languageTag: String, planName: String?): List<DayRouteCandidate> {
        val out = mutableListOf<DayRouteCandidate>()
        val infos = runCatching { worlds.listWorlds() }.getOrDefault(emptyList())
        val summaries = runCatching { rideStorage.listSummaries().summaries }.getOrDefault(emptyList())
            .filter { !it.indoor && it.pointCount >= 2 }
            .sortedByDescending { it.createdAt }
        val names = summaries.associate { it.id to it.name }

        // 1. Fertige Welten.
        val worldRides = mutableSetOf<String>()
        for (info in infos) {
            val manifest = info.manifest ?: continue
            when {
                info.isTestWorld -> worldCandidate("testwelt", testWorldName, info.dirName, manifest)?.let(out::add)
                info.catalog != null -> Unit // unten mit dem Katalog
                info.source != null -> {
                    val rideId = info.source.rideId
                    val name = names[rideId] ?: continue // verwaiste Welt: keine Strecke mehr
                    if (!worldRides.add(rideId)) continue
                    worldCandidate("ride:$rideId", name, info.dirName, manifest)?.let(out::add)
                }
            }
        }
        // 1b und 2. Katalog: geladen = fertige Welt, sonst nur Laden.
        catalogStore?.let { store ->
            val installed = runCatching { store.installed() }.getOrDefault(emptyMap())
            val entries = runCatching { store.catalog().entries }.getOrDefault(emptyList())
            for (e in entries) {
                val name = e.name.pick(languageTag)
                val inst = installed[e.id]
                if (inst != null) {
                    worldCandidate("catalog:${e.id}", name, inst.worldId, inst.manifest)?.let(out::add)
                } else {
                    val profile = profileCache.getOrPut("catalog-estimate:${e.id}:${e.version}") {
                        Holder(DayRouteProfile.estimate(e.lengthM, e.ascentM, isLoop = false))
                    }.profile ?: continue
                    out += DayRouteCandidate("catalog:${e.id}", name, DayRouteAvailability.DOWNLOAD, profile)
                }
            }
        }
        // 3. Eigene Strecken ohne Welt.
        for (s in summaries.filter { it.id !in worldRides }.take(MAX_SAVED_RIDES)) {
            val profile = profileCache.getOrPut("ride:${s.id}:${s.updatedAt}:${s.pointCount}") {
                Holder(runCatching { rideStorage.loadRide(s.id)?.let { DayRouteProfile.ofTrack(it.points) } }.getOrNull())
            }.profile ?: continue
            out += DayRouteCandidate("ride:${s.id}", s.name, DayRouteAvailability.BUILD, profile)
        }
        // 4. Neue Runde.
        if (planName != null) out += DayRouteCandidate(PLAN_KEY, planName, DayRouteAvailability.PLAN, null)
        return out
    }

    private fun worldCandidate(key: String, name: String, worldId: String, manifest: WorldManifest): DayRouteCandidate? {
        val profile = profileCache.getOrPut("world:$worldId:${manifest.createdAtMs}") {
            Holder(runCatching { DayRouteProfile.of(worldRouteOf(manifest, name)) }.getOrNull())
        }.profile ?: return null
        return DayRouteCandidate(key, name, DayRouteAvailability.READY, profile)
    }

    /** `getOrPut` einer [ConcurrentHashMap] verlangt einen Wert ungleich `null`. */
    private class Holder(val profile: DayRouteProfile?)

    companion object {
        /** Schluessel der neu zu planenden Runde (keine Streckenwahl dahinter). */
        const val PLAN_KEY: String = "plan"

        /** So viele eigene Strecken ohne Welt werden hoechstens bewertet (die juengsten). */
        const val MAX_SAVED_RIDES: Int = 30

        /**
         * Grobe Bauzeit einer Welt auf dem Handy in Sekunden je km (mit
         * Netz). Gemessen ist nur der Entwicklerrechner (3–5 s/km mit warmem
         * Cache, docs/indoor/world.md und lidar.md); das Handy rechnet
         * langsamer und laedt zuerst — auf dem Geraet zu pruefen.
         */
        const val BUILD_SECONDS_PER_KM: Int = 20

        private val profileCache = ConcurrentHashMap<String, Holder>()
    }
}

/**
 * Die Einheit des Tages fuer die Rolle, fertig zum Anzeigen und Fahren.
 *
 * @property day welche Einheit ([daySessionFor]).
 * @property workout die ERG-Einheit; `null` bei Ruhetag und Zieltag.
 * @property goal Ziel der Bewertung; `null` ohne [workout].
 * @property planTarget Routenziel fuer eine neu zu planende Runde; `null` ohne [workout].
 */
data class IndoorDayPlan(
    val day: DaySession,
    val title: String?,
    val workout: ErgWorkout?,
    val goal: DayRouteGoal?,
    val planTarget: RouteTarget?,
)

/**
 * Leitet die Einheit des Tages aus der Tagesentscheidung [today] ab
 * (`decideTodayRoute`, dieselbe Daempfung durch die Tagesform wie das
 * Routenangebot auf „Heute"). Wirft nie: Scheitert der Kern, gibt es eben keine
 * Einheit ([DaySessionKind.REST] mit leerem Rest).
 *
 * @param plannedTitle Titel der Planeinheit (`sessionTitle`), `null` ohne.
 * @param easyTitle Titel der lockeren Runde an einem Tag ohne Planeinheit.
 */
fun indoorDayPlanOf(
    today: TodayRoute,
    parts: IndoorParts,
    profile: TrainingProfile,
    plannedTitle: (DaySession) -> String?,
    easyTitle: String,
): IndoorDayPlan {
    val day = runCatching { daySessionFor(today) }.getOrElse { DaySession(DaySessionKind.REST, null, today.session) }
    val session = day.session ?: return IndoorDayPlan(day, plannedTitle(day), null, null, null)
    val title = if (day.kind == DaySessionKind.EASY) easyTitle else plannedTitle(day) ?: session.title
    val workout = indoorCatching(IndoorPart.CORE) { parts.ergWorkout(session, title) }.getOrNull()
        ?: return IndoorDayPlan(day, title, null, null, null)
    val rider: RiderSetup? = indoorCatching(IndoorPart.CORE) { parts.riderSetup(profile) }.getOrNull()
    val goal = rider?.let { r -> runCatching { dayRouteGoal(workout, r) }.getOrNull() }
    val target = goal?.let { g -> today.target?.let { t -> runCatching { dayPlanTarget(g, session, t) }.getOrNull() } }
    return IndoorDayPlan(day, title, workout, goal, target)
}

/** Bewertet die Kandidaten fuer [plan] (blockierend); leer ohne Ziel. */
fun IndoorDayRouteFinder.rank(
    plan: IndoorDayPlan,
    testWorldName: String,
    languageTag: String,
    planName: String,
): List<DayRouteMatch> {
    val goal = plan.goal ?: return emptyList()
    val candidates = candidates(testWorldName, languageTag, planName.takeIf { plan.planTarget != null })
    return runCatching { rankDayRoutes(goal, candidates) }.getOrDefault(emptyList())
}
