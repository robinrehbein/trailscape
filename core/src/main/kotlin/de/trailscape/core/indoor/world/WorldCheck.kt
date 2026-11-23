package de.trailscape.core.indoor.world

import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * # Pruefung einer fertigen Welt gegen den Vertrag (docs/indoor.md 7–9)
 *
 * Dieselbe Pruefung laeuft im Entwicklerwerkzeug nach jedem Bau und als
 * `:core`-Test ueber die Testwelt.
 *
 * ## Regel „lueckenlos" (an jeder Stelle in 10-m-Schritten)
 *  1. Unter dem Fahrer liegt eine gezeigte Stufe-0-Kachel.
 *  2. **Eltern-Regel:** Jede gezeigte Kachel der Stufe 0 oder 1 hat ihre
 *     Eltern-Kachel gezeigt — ausser es ist an dieser Stelle gar kein
 *     Vorfahr gezeigt. Begruendung: Die Darstellung blendet eine
 *     Teilflaeche nur aus, solange die **unmittelbar** feinere Kachel gezeigt
 *     wird. Fehlte die Eltern-Kachel, die Grosseltern-Kachel waere aber da,
 *     bliebe deren Teilflaeche sichtbar und laege grob unter bzw. ueber dem
 *     feinen Gelaende. Ein Loch entsteht durch das Ausblenden nie, denn
 *     ausgeblendet wird nur, was die feinere Kachel selbst bedeckt.
 *  3. **Abdeckung:** Jeder Punkt (Raster 256 m) im Umkreis von
 *     `min(3 km, Sichtweite/2)` um den Fahrer liegt in einer gezeigten
 *     Kachel irgendeiner Stufe — sonst saehe man dort ins Leere.
 *
 * Weitere Punkte: Manifest lesbar; Kachel-IDs, Dateinamen und Anker nach
 * [worldTileId] und Kachelmitte; Dateien vorhanden, Groesse = `bytes`;
 * [checkTileGlb] je Kachel; Dreiecke und Texturbytes wie in der GLB; ab
 * Stufe 1 genau 16 Teilflaechen; `show` sortiert, ueberlappungsfrei, in
 * `[0, lengthM]`; Budget an jeder Stelle ≤ Grenzen der Qualitaet und gleich
 * `manifest.budget` (Plattenplatz = Kacheln + Manifest); Streckenband
 * (Probenzahl, Runde geschlossen bis 5 m / 1 m, Steigungen in −25..+25 %,
 * Belaege lueckenlos); Quellenangaben vorhanden (und eine Luftbild-Angabe,
 * wenn eine Kachel ein Luftbild hat).
 *
 * @return die Verstoesse als deutsche Texte; leer = in Ordnung.
 */
fun checkWorld(dir: File): List<String> {
    val out = ArrayList<String>()
    val manifestFile = File(dir, WorldManifest.FILE_NAME)
    if (!manifestFile.isFile) return listOf("${WorldManifest.FILE_NAME} fehlt in $dir")
    val manifestText = try {
        manifestFile.readText()
    } catch (e: Exception) {
        return listOf("${WorldManifest.FILE_NAME} unlesbar: ${e.message}")
    }
    val m = try {
        WorldManifest.parse(manifestText)
    } catch (e: IllegalArgumentException) {
        return listOf(e.message ?: "Manifest unlesbar")
    }
    val track = m.track
    val len = track.lengthM

    // Stufen
    for (level in 0 until WORLD_LEVEL_COUNT) {
        val l = m.levels.firstOrNull { it.level == level }
        if (l == null) out += "Stufe $level fehlt in levels"
        else if (l.cellM != worldCellM(level)) out += "Stufe $level: cellM ${l.cellM} statt ${worldCellM(level)}"
    }

    // Kacheln
    val ids = HashSet<String>()
    var tileBytes = 0L
    for (t in m.tiles) {
        val label = "Kachel ${t.id}"
        if (!ids.add(t.id)) out += "$label kommt mehrfach vor"
        if (t.level !in 0 until WORLD_LEVEL_COUNT) {
            out += "$label: unbekannte Stufe ${t.level}"
            continue
        }
        if (t.id != worldTileId(t.level, t.ix, t.iy)) out += "$label: ID passt nicht zu Stufe/ix/iy (${worldTileId(t.level, t.ix, t.iy)})"
        val expectFile = "${WorldManifest.TILES_DIR}/${worldTileId(t.level, t.ix, t.iy)}.glb"
        if (t.file != expectFile) out += "$label: Datei ${t.file} statt $expectFile"
        val c = worldCellM(t.level)
        if (abs(t.anchorE - (t.ix + 0.5) * c) > 1e-6 || abs(t.anchorN - (t.iy + 0.5) * c) > 1e-6) {
            out += "$label: Anker (${t.anchorE}, ${t.anchorN}) ist nicht die Kachelmitte"
        }
        if (t.minU > t.maxU) out += "$label: minU ${t.minU} > maxU ${t.maxU}"
        checkShow(t, len, out)
        val f = File(dir, t.file)
        if (!f.isFile) {
            out += "$label: Datei ${t.file} fehlt"
            continue
        }
        tileBytes += f.length()
        if (f.length() != t.bytes) out += "$label: Datei hat ${f.length()} Bytes, Manifest sagt ${t.bytes}"
        val bytes = f.readBytes()
        checkTileGlb(bytes).forEach { out += "$label: $it" }
        val info = runCatching { parseGlb(bytes) }.getOrNull() ?: continue
        if (info.triangleCount != t.triangles) out += "$label: ${info.triangleCount} Dreiecke in der GLB, Manifest sagt ${t.triangles}"
        if (info.textureGpuBytes != t.textureBytes) out += "$label: ${info.textureGpuBytes} Texturbytes in der GLB, Manifest sagt ${t.textureBytes}"
        if (t.level >= 1) {
            val subs = info.nodes.mapNotNull { it.name }.filter { it.startsWith(WorldNames.NODE_TERRAIN_SUB_PREFIX) }.toSet()
            val expected = (0 until WORLD_LEVEL_FACTOR).flatMap { i -> (0 until WORLD_LEVEL_FACTOR).map { j -> WorldNames.terrainSubName(i, j) } }.toSet()
            if (subs != expected) out += "$label: ${subs.size} Teilflaechen statt genau ${expected.size} (${(expected - subs).take(3)} fehlen)"
        }
    }

    // Lueckenlos und Budget
    checkCoverage(m, out)
    val (tex, tri) = worldBudgetOf(m.tiles, len)
    if (tex > m.quality.maxTextureBytes) out += "Budget: $tex Texturbytes gleichzeitig, erlaubt ${m.quality.maxTextureBytes}"
    if (tri > m.quality.maxTriangles) out += "Budget: $tri Dreiecke gleichzeitig, erlaubt ${m.quality.maxTriangles}"
    if (tex != m.budget.maxTextureBytes) out += "Budget: maxTextureBytes ${m.budget.maxTextureBytes} im Manifest, nachgerechnet $tex"
    if (tri != m.budget.maxTriangles) out += "Budget: maxTriangles ${m.budget.maxTriangles} im Manifest, nachgerechnet $tri"
    val disk = tileBytes + manifestFile.length()
    if (disk != m.budget.diskBytes) out += "Budget: diskBytes ${m.budget.diskBytes} im Manifest, auf der Platte $disk"

    // Streckenband
    checkTrack(m, out)

    // Umgebungsquader
    for (t in m.tiles) {
        val c = worldCellM(t.level)
        if (t.ix * c < m.bounds.minE - 1e-6 || (t.ix + 1) * c > m.bounds.maxE + 1e-6 ||
            t.iy * c < m.bounds.minN - 1e-6 || (t.iy + 1) * c > m.bounds.maxN + 1e-6
        ) {
            out += "Kachel ${t.id} liegt ausserhalb von bounds"
        }
    }

    // Quellenangaben
    if (m.attribution.isEmpty()) out += "Keine Quellenangaben"
    m.attribution.filter { it.text.isBlank() }.forEach { out += "Leere Quellenangabe (${it.source})" }
    if (m.tiles.any { it.aerial } && m.attribution.none { it.source == GeoSourceKind.AERIAL }) {
        out += "Kacheln mit Luftbild, aber keine Quellenangabe fuer Luftbilder"
    }
    return out
}

/** Groesste Summe an Texturbytes und Dreiecken der an einer Stelle gebrauchten Kacheln (alle 10 m und am Ende). */
internal fun worldBudgetOf(tiles: List<WorldTile>, lengthM: Double): Pair<Long, Int> {
    var maxTex = 0L
    var maxTri = 0
    for (d in budgetPositions(lengthM)) {
        var tex = 0L
        var tri = 0
        for (t in tiles) {
            if (t.neededAt(d)) {
                tex += t.textureBytes
                tri += t.triangles
            }
        }
        maxTex = max(maxTex, tex)
        maxTri = max(maxTri, tri)
    }
    return maxTex to maxTri
}

private fun checkShow(t: WorldTile, len: Double, out: MutableList<String>) {
    val label = "Kachel ${t.id}"
    if (t.show.isEmpty()) out += "$label: show ist leer (wird nie gebraucht)"
    var prevTo = Double.NEGATIVE_INFINITY
    for (iv in t.show) {
        if (iv.fromM > iv.toM) out += "$label: show-Bereich ${iv.fromM}..${iv.toM} ist verkehrt herum"
        if (iv.fromM < -1e-6 || iv.toM > len + 1e-6) out += "$label: show-Bereich ${iv.fromM}..${iv.toM} liegt ausserhalb von 0..$len"
        if (iv.fromM < prevTo) out += "$label: show ist nicht sortiert oder ueberlappt bei ${iv.fromM}"
        prevTo = max(prevTo, iv.toM)
    }
}

/** Hoechstzahl gleichartiger Meldungen der Lueckenpruefung. */
private const val MAX_COVERAGE_MESSAGES = 20

private fun checkCoverage(m: WorldManifest, out: MutableList<String>) {
    val byKey = HashMap<String, WorldTile>()
    for (t in m.tiles) byKey[t.id] = t
    fun tileAt(level: Int, e: Double, n: Double): WorldTile? {
        val c = worldCellM(level)
        return byKey[worldTileId(level, floor(e / c).toInt(), floor(n / c).toInt())]
    }
    val radius = min(3_000.0, m.quality.viewDistanceM / 2)
    val gridStep = WORLD_LEVEL0_CELL_M
    val steps = ceil(radius / gridStep).toInt()
    var underRider = 0
    var parents = 0
    var holes = 0
    val reportedParents = HashSet<String>()
    for (d in budgetPositions(m.track.lengthM)) {
        val pose = m.track.poseAt(d)
        val here = tileAt(0, pose.e, pose.n)
        if (here == null || !here.neededAt(d)) {
            if (underRider++ < MAX_COVERAGE_MESSAGES) {
                out += "Bei ${"%.0f".format(d)} m liegt keine gezeigte Stufe-0-Kachel unter dem Fahrer (${here?.id ?: "keine Kachel"})"
            }
        }
        for (t in m.tiles) {
            if (t.level >= WORLD_LEVEL_COUNT - 1 || !t.neededAt(d)) continue
            val parentId = worldTileId(t.level + 1, Math.floorDiv(t.ix, WORLD_LEVEL_FACTOR), Math.floorDiv(t.iy, WORLD_LEVEL_FACTOR))
            if (byKey[parentId]?.neededAt(d) == true) continue
            // Ohne gezeigten Vorfahren liegt nichts darunter, das sichtbar bliebe.
            var anyAncestor = false
            var lv = t.level + 1
            var ax = Math.floorDiv(t.ix, WORLD_LEVEL_FACTOR)
            var ay = Math.floorDiv(t.iy, WORLD_LEVEL_FACTOR)
            while (lv < WORLD_LEVEL_COUNT) {
                if (byKey[worldTileId(lv, ax, ay)]?.neededAt(d) == true) anyAncestor = true
                lv++
                ax = Math.floorDiv(ax, WORLD_LEVEL_FACTOR)
                ay = Math.floorDiv(ay, WORLD_LEVEL_FACTOR)
            }
            if (anyAncestor && reportedParents.add(t.id) && parents++ < MAX_COVERAGE_MESSAGES) {
                out += "Bei ${"%.0f".format(d)} m ist ${t.id} gezeigt, die Eltern-Kachel $parentId aber nicht (Teilflaeche der Grosseltern bliebe sichtbar)"
            }
        }
        for (j in -steps..steps) for (i in -steps..steps) {
            val e = pose.e + i * gridStep
            val n = pose.n + j * gridStep
            if (hypot(i * gridStep, j * gridStep) > radius) continue
            var covered = false
            for (level in 0 until WORLD_LEVEL_COUNT) {
                if (tileAt(level, e, n)?.neededAt(d) == true) {
                    covered = true
                    break
                }
            }
            if (!covered) {
                if (holes++ < MAX_COVERAGE_MESSAGES) {
                    out += "Bei ${"%.0f".format(d)} m ist die Stelle (${"%.0f".format(e)}, ${"%.0f".format(n)}) von keiner gezeigten Kachel bedeckt"
                }
                break
            }
        }
    }
    if (underRider > MAX_COVERAGE_MESSAGES) out += "… insgesamt $underRider Stellen ohne Kachel unter dem Fahrer"
    if (holes > MAX_COVERAGE_MESSAGES) out += "… insgesamt $holes Stellen mit Loechern"
}

private fun checkTrack(m: WorldManifest, out: MutableList<String>) {
    val t = m.track
    val len = t.lengthM
    if (!(len > 0) || !(t.stepM > 0)) {
        out += "Streckenband: lengthM $len, stepM ${t.stepM}"
        return
    }
    val expected = ceil(len / t.stepM - 1e-9).toInt() + 1
    if (t.sampleCount != expected) out += "Streckenband: ${t.sampleCount} Proben, erwartet $expected (lengthM/stepM)"
    if (t.loop) {
        val last = t.sampleCount - 1
        val gap = hypot((t.e[last] - t.e[0]).toDouble(), (t.n[last] - t.n[0]).toDouble())
        val du = abs(t.u[last] - t.u[0])
        if (gap > 5.0 || du > 1.0) out += "Streckenband: Runde nicht geschlossen (Abstand ${"%.1f".format(gap)} m, Hoehe ${"%.2f".format(du)} m)"
    }
    var bad = 0
    for (i in 0 until t.sampleCount - 1) {
        val span = t.distAt(i + 1) - t.distAt(i)
        if (span <= 0) continue
        val du = abs((t.u[i + 1] - t.u[i]).toDouble())
        // 2 cm Spiel: das Manifest speichert zentimetergenau.
        if (du > 0.25 * span + 0.02) {
            if (bad++ < 5) out += "Streckenband: Steigung ${"%.1f".format(du / span * 100)} % bei ${"%.0f".format(t.distAt(i))} m"
        }
    }
    val s = t.surfaces
    if (s.isEmpty()) {
        out += "Streckenband: keine Belaege"
        return
    }
    if (abs(s.first().fromM) > 1e-6) out += "Belaege beginnen bei ${s.first().fromM} statt 0"
    if (abs(s.last().toM - len) > 0.01) out += "Belaege enden bei ${s.last().toM} statt $len"
    for (i in s.indices) {
        if (s[i].toM <= s[i].fromM) out += "Belagsabschnitt ${s[i].fromM}..${s[i].toM} ist leer"
        if (i > 0 && abs(s[i].fromM - s[i - 1].toM) > 1e-6) out += "Belaege nicht lueckenlos bei ${s[i - 1].toM} / ${s[i].fromM}"
    }
}
