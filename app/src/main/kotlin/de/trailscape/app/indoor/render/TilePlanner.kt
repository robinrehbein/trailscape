package de.trailscape.app.indoor.render

import de.trailscape.core.indoor.world.WORLD_LEVEL_FACTOR
import de.trailscape.core.indoor.world.WorldNames
import de.trailscape.core.indoor.world.WorldTile
import de.trailscape.core.indoor.world.worldTileId

/**
 * # Welche Kacheln gerade gebraucht werden
 *
 * Reine Logik ueber `WorldTile.show` (docs/indoor.md, Abschnitt 7.2): Die
 * Darstellung braucht keine Raumlogik, der Weltbau hat fuer jede Kachel
 * ausgerechnet, bei welchen Laufmetern sie geladen sein soll.
 *
 *  * **Laden:** was an der Stelle des Fahrers gebraucht wird, zuerst; danach
 *    der Vorlauf in Fahrtrichtung ([lookaheadM]). Innerhalb davon die feine
 *    Stufe zuerst (was unter dem Fahrer liegt), dann die groberen.
 *  * **Entladen:** erst, wenn die Kachel im ganzen Fenster
 *    `[d − hysteresis, d + lookahead + hysteresis]` nicht mehr gebraucht wird
 *    — an Grenzen flattert dadurch nichts.
 *  * **Teilflaechen:** Eine Teilflaeche `terrain_sub_i_j` einer groben Kachel
 *    wird nur ausgeblendet, solange die feinere Kachel fertig geladen und
 *    sichtbar ist ([hiddenSubs]).
 *
 * @property lengthM Laenge des Streckenbands.
 * @property loop Runde: Laufmeter werden modulo [lengthM] gefaltet.
 */
class TilePlanner(
    private val tiles: List<WorldTile>,
    private val lengthM: Double,
    private val loop: Boolean,
    private val lookaheadM: Double = DEFAULT_LOOKAHEAD_M,
    private val hysteresisM: Double = DEFAULT_HYSTERESIS_M,
) {
    private val byId: Map<String, WorldTile> = tiles.associateBy { it.id }

    fun tile(id: String): WorldTile? = byId[id]

    /** Laufmeter auf das Band gefaltet. */
    fun fold(distM: Double): Double = when {
        !distM.isFinite() -> 0.0
        loop && lengthM > 0 -> ((distM % lengthM) + lengthM) % lengthM
        else -> distM.coerceIn(0.0, maxOf(0.0, lengthM))
    }

    /** Kacheln, die genau an der Stelle [distM] gebraucht werden (fuer „bereit“ und den Fortschritt). */
    fun neededNow(distM: Double): List<WorldTile> {
        val d = fold(distM)
        return tiles.filter { it.neededAt(d) }
    }

    /**
     * Was geladen sein soll, in Ladereihenfolge: zuerst die Kacheln der
     * aktuellen Stelle, dann die des Vorlaufs; je Gruppe feine Stufe zuerst.
     */
    fun wanted(distM: Double): List<WorldTile> {
        val now = neededNow(distM)
        val nowIds = now.mapTo(HashSet()) { it.id }
        val ahead = tiles.filter { it.id !in nowIds && neededInWindow(it, distM, distM + lookaheadM) }
        return now.sortedWith(ORDER) + ahead.sortedWith(ORDER)
    }

    /**
     * Ob eine geladene Kachel bleiben darf: Sie wird irgendwo im Fenster
     * `[d − hysteresis, d + lookahead + hysteresis]` gebraucht.
     */
    fun keep(tile: WorldTile, distM: Double): Boolean =
        neededInWindow(tile, distM - hysteresisM, distM + lookaheadM + hysteresisM)

    /** Die naechste Kachel, die zu laden ist — oder `null`. Hoechstens eine je Bild beginnen. */
    fun nextToLoad(distM: Double, loadedOrLoading: Set<String>, failed: Set<String>): WorldTile? =
        wanted(distM).firstOrNull { it.id !in loadedOrLoading && it.id !in failed }

    /** Geladene Kacheln, die entladen werden koennen. */
    fun toUnload(distM: Double, loaded: Collection<String>): List<String> =
        loaded.filter { id -> byId[id]?.let { !keep(it, distM) } ?: true }

    /**
     * Wird [tile] irgendwo in `[fromM, toM]` (fortlaufende Laufmeter) gebraucht?
     * Auf einer Runde wird das Fenster gefaltet und ggf. in zwei Teile zerlegt.
     */
    fun neededInWindow(tile: WorldTile, fromM: Double, toM: Double): Boolean {
        if (!fromM.isFinite() || !toM.isFinite()) return tile.neededAt(0.0)
        val lo = minOf(fromM, toM)
        val hi = maxOf(fromM, toM)
        val windows: List<Pair<Double, Double>> = if (loop && lengthM > 0) {
            if (hi - lo >= lengthM) {
                listOf(0.0 to lengthM)
            } else {
                val a = fold(lo)
                val b = a + (hi - lo)
                if (b <= lengthM) listOf(a to b) else listOf(a to lengthM, 0.0 to (b - lengthM))
            }
        } else {
            listOf(lo.coerceIn(0.0, maxOf(0.0, lengthM)) to hi.coerceIn(0.0, maxOf(0.0, lengthM)))
        }
        return tile.show.any { s -> windows.any { (a, b) -> s.fromM <= b && s.toM >= a } }
    }

    companion object {
        const val DEFAULT_LOOKAHEAD_M = 200.0
        const val DEFAULT_HYSTERESIS_M = 60.0

        private val ORDER = compareBy<WorldTile>({ it.level }, { it.id })

        /**
         * Die Teilflaechen von [tile], die auszublenden sind, weil die feinere
         * Kachel darunter in [shownFine] (fertig geladen und sichtbar) liegt.
         * Stufe 0 hat keine Teilflaechen. Namen wie `WorldNames.terrainSubName`.
         */
        fun hiddenSubs(tile: WorldTile, shownFine: Set<String>): Set<String> {
            if (tile.level < 1) return emptySet()
            val out = HashSet<String>()
            for (i in 0 until WORLD_LEVEL_FACTOR) {
                for (j in 0 until WORLD_LEVEL_FACTOR) {
                    val fine = worldTileId(tile.level - 1, tile.ix * WORLD_LEVEL_FACTOR + i, tile.iy * WORLD_LEVEL_FACTOR + j)
                    if (fine in shownFine) out += WorldNames.terrainSubName(i, j)
                }
            }
            return out
        }
    }
}
