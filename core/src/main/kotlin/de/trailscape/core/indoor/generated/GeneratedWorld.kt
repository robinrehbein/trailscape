package de.trailscape.core.indoor.generated

import de.trailscape.core.indoor.IndoorRoute
import de.trailscape.core.indoor.IndoorRoutePoint
import de.trailscape.core.indoor.IndoorRouteSource
import de.trailscape.core.indoor.clampGrades
import de.trailscape.core.indoor.world.FullWorldParts
import de.trailscape.core.indoor.world.GeoSourceKind
import de.trailscape.core.indoor.world.IndoorQuality
import de.trailscape.core.indoor.world.WorldAttribution
import de.trailscape.core.indoor.world.WorldBuildCancelled
import de.trailscape.core.indoor.world.WorldBuildFailed
import de.trailscape.core.indoor.world.WorldBuildListener
import de.trailscape.core.indoor.world.WorldBuilderImpl
import de.trailscape.core.indoor.world.WorldManifest
import de.trailscape.core.indoor.world.WorldPlan
import de.trailscape.core.indoor.world.sha256Hex
import java.io.File

/**
 * Fassung des Generators. Geht in die Strecken-ID und damit in die Welt-ID
 * ein: Wer Strecke oder Landschaft anders erzeugt, zaehlt hoch, damit keine
 * alte Welt zu einer neuen Strecke geladen wird.
 */
internal const val GENERATOR_VERSION = 1

/** Quellenangabe im Manifest — ehrlich: hier stecken keine Geodaten drin. */
internal val GENERATED_ATTRIBUTION = WorldAttribution(
    GeoSourceKind.VECTOR,
    "Generierte Landschaft, keine Geodaten",
    "",
    "",
)

/** Alles, was aus einem Profil folgt: Linie, Gelaende, Karte. Deterministisch. */
internal class GeneratedScene(val profile: GeneratedProfile) {
    val plan: GenRoutePlan = planGeneratedRoute(profile)
    val terrain = GenTerrain(plan)
    val map = GenMap(plan, terrain)

    val routeId: String = run {
        val sb = StringBuilder("gen").append(GENERATOR_VERSION).append('|').append(profile.seed)
        for (l in profile.legs) sb.append('|').append(l.lengthM).append(',').append(l.gradePct).append(',').append(l.kind.name)
        "gen$GENERATOR_VERSION-" + sha256Hex(sb.toString()).take(20)
    }

    fun route(name: String): IndoorRoute {
        val o = GEN_DESIGN_ORIGIN
        val ele = clampGrades(plan.ele.copyOf(), plan.dist)
        val points = List(plan.count) { i -> IndoorRoutePoint(plan.dist[i], o.toLat(plan.n[i]), o.toLon(plan.e[i]), ele[i]) }
        return IndoorRoute(
            id = routeId,
            name = name,
            points = points,
            surfaces = map.surfaces,
            isLoop = false,
            // erfundene Wegmerkmale, so wie BRouter sie liefern wuerde (der Vertrag kennt keine eigene Quelle dafuer)
            source = IndoorRouteSource.BROUTER_WAY_TAGS,
        )
    }

    fun heightAtLatLon(lat: Double, lon: Double): Double {
        val o = GEN_DESIGN_ORIGIN
        return terrain.heightAt(o.toEast(lon), o.toNorth(lat))
    }
}

internal fun generatedRouteImpl(profile: GeneratedProfile, name: String): IndoorRoute = GeneratedScene(profile).route(name)

internal fun generatedPlanImpl(profile: GeneratedProfile, quality: IndoorQuality, name: String): WorldPlan {
    val route = generatedRouteImpl(profile, name)
    return WorldBuilderImpl(FullWorldParts).plan(route, quality).copy(
        requests = emptyMap(),
        downloadBytes = 0L,
        hosts = emptyList(),
        aerial = emptyList(),
    )
}

internal fun buildGeneratedImpl(
    plan: WorldPlan,
    profile: GeneratedProfile,
    outDir: File,
    listener: WorldBuildListener,
    createdAtMs: Long = System.currentTimeMillis(),
    threads: Int? = null,
): WorldManifest {
    try {
        if (listener.isCancelled()) throw WorldBuildCancelled()
        val scene = GeneratedScene(profile)
        if (scene.routeId != plan.route.id) {
            throw WorldBuildFailed(WorldBuildFailed.Reason.INTERNAL, "Plan gehoert nicht zu diesem Profil (${plan.route.id} ≠ ${scene.routeId})")
        }
        if (listener.isCancelled()) throw WorldBuildCancelled()
        val builder = if (threads != null) WorldBuilderImpl(FullWorldParts, threads = threads) else WorldBuilderImpl(FullWorldParts)
        return builder.buildSynthetic(
            route = plan.route,
            heightAtLatLon = scene::heightAtLatLon,
            isBridge = { false },
            mapOf = scene.map::mapData,
            attribution = listOf(GENERATED_ATTRIBUTION),
            worldId = plan.worldId,
            quality = plan.quality,
            outDir = outDir,
            listener = listener,
            createdAtMs = createdAtMs,
        )
    } catch (e: WorldBuildCancelled) {
        throw e
    } catch (e: WorldBuildFailed) {
        throw e
    } catch (e: Throwable) {
        throw WorldBuildFailed(WorldBuildFailed.Reason.INTERNAL, e.message ?: e.javaClass.simpleName, e)
    }
}

internal fun describeGeneratedImpl(profile: GeneratedProfile): List<String> {
    val scene = GeneratedScene(profile)
    val p = scene.plan
    val out = ArrayList<String>()
    fun km(m: Double) = "%.2f".format(java.util.Locale.ROOT, m / 1000)
    out += "Landschaft: ${p.landscape}"
    out += "Strecke: ${km(p.lengthM)} km, Hoehe ${"%.0f".format(java.util.Locale.ROOT, p.ele.min())}–${"%.0f".format(java.util.Locale.ROOT, p.ele.max())} m, Linienversuche ${p.attempts}"
    for (v in p.villages) out += "Ort: km ${km(v[0])}–${km(v[1])}"
    for (s in p.serpentines) out += "Serpentine: km ${km(s[0])}–${km(s[1])}"
    for (l in scene.terrain.lakes) out += "See: Radius ${"%.0f".format(java.util.Locale.ROOT, l.radius)} m, ${"%.0f".format(java.util.Locale.ROOT, scene.terrain.distanceToRoute(l.ce, l.cn))} m neben der Strecke"
    for (l in p.legs) l.cappedFromPct?.let { out += "Leg km ${km(l.fromM)}: Steigung $it % auf ${l.gradePct} % gekappt" }
    for (s in scene.map.surfaces) out += "Belag: km ${km(s.fromM)}–${km(s.toM)} ${s.kind} (${s.highway})"
    out += "Gebaeude: ${scene.map.buildings.size}, Strassen/Wege: ${scene.map.roads.size}"
    return out
}
