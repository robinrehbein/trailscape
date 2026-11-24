package de.trailscape.core.indoor.server

import de.trailscape.core.indoor.IndoorRoute
import de.trailscape.core.indoor.world.LaserSource
import de.trailscape.core.indoor.world.laserSources
import kotlin.math.max

/**
 * # Was der Server-Bau an Laserdaten erwarten laesst (docs/indoor/serverbau.md)
 *
 * Laserdaten (DGM1, nDOM, LoD2) gibt es nur in Nordrhein-Westfalen und
 * Baden-Wuerttemberg. Der Weltbau waehlt **eine** Quelle: die, in deren
 * Rechteck die meisten von rund 50 Probepunkten der Strecke liegen — und nur,
 * wenn es mindestens die Haelfte sind (`loadLaserData`). Diese Vorschau rechnet
 * dieselbe Regel auf den Punkten der Strecke nach, ohne Netz, damit die App
 * **vorher** ehrlich sagt, was kommt.
 *
 * Grenzen: Die Rechtecke sind groeber als die Landesgrenze. Innerhalb des
 * Rechtecks, aber ausserhalb des Landes liefert der Dienst keine Daten; dort
 * bleibt das bisherige Gelaende (Terrarium, an den Rand des Laser-Korridors
 * angeglichen), Baeume und Gebaeude wie ohne Laser.
 */
data class LaserPreview(
    /** Die Quelle, die der Bau nehmen wird, `null` = Bau ohne Laser. */
    val source: LaserSource?,
    /** Anteil der Probepunkte im Rechteck von [source] (0..1). */
    val share: Double,
    /** Anteil im besten Rechteck, auch wenn er unter der Haelfte liegt (fuer „teilweise"). */
    val bestShare: Double,
    /** Die Quelle mit den meisten Punkten, auch unter der Haelfte. */
    val best: LaserSource? = null,
) {
    val kind: LaserExpectation get() = when {
        source != null && share >= 0.98 -> LaserExpectation.FULL
        source != null -> LaserExpectation.PARTIAL
        bestShare > 0.0 -> LaserExpectation.TOO_LITTLE
        else -> LaserExpectation.NONE
    }
}

enum class LaserExpectation {
    /** Ganze Strecke im Rechteck einer Laser-Quelle. */
    FULL,

    /** Mindestens die Haelfte: Laser im Land, ausserhalb das bisherige Gelaende. */
    PARTIAL,

    /** Ein Teil liegt im Land, aber weniger als die Haelfte: der Bau nimmt keine Laserdaten. */
    TOO_LITTLE,

    /** Kein Teil in NRW oder BW. */
    NONE,
}

fun laserPreviewOf(route: IndoorRoute, sources: List<LaserSource> = laserSources): LaserPreview =
    laserPreviewOfPoints(route.points.map { it.lat to it.lon }, sources)

/** Wie [laserPreviewOf], fuer eine Spur aus (Breite, Laenge), z. B. die Punkte einer Tour. */
fun laserPreviewOfPoints(pts: List<Pair<Double, Double>>, sources: List<LaserSource> = laserSources): LaserPreview {
    if (pts.isEmpty()) return LaserPreview(null, 0.0, 0.0)
    val probe = pts.indices step max(1, pts.size / 50)
    val n = probe.count()
    val counts = sources.map { s -> s to probe.count { i -> s.covers(pts[i].first, pts[i].second) } }
    val best = counts.maxByOrNull { it.second }
    val chosen = counts.filter { it.second * 2 >= n && it.second > 0 }.maxByOrNull { it.second }
    return LaserPreview(
        source = chosen?.first,
        share = (chosen?.second ?: 0).toDouble() / n,
        bestShare = (best?.second ?: 0).toDouble() / n,
        best = best?.takeIf { it.second > 0 }?.first,
    )
}
