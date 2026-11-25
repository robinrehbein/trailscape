package de.trailscape.core.indoor.tree

import de.trailscape.core.indoor.world.Argb
import de.trailscape.core.indoor.world.EncodedTexture
import de.trailscape.core.indoor.world.FoliageAtlas
import de.trailscape.core.indoor.world.GlbAlphaMode
import de.trailscape.core.indoor.world.Mat4
import de.trailscape.core.indoor.world.MeshBuilder
import de.trailscape.core.indoor.world.TextureCache
import de.trailscape.core.indoor.world.TileMaterial
import de.trailscape.core.indoor.world.TileOutput
import de.trailscape.core.indoor.world.TileSpec
import de.trailscape.core.indoor.world.TreeModels
import de.trailscape.core.indoor.world.TreesLayer
import de.trailscape.core.indoor.world.Vec3
import de.trailscape.core.indoor.world.WORLD_BUILDER_ID
import de.trailscape.core.indoor.world.WorldNames
import de.trailscape.core.indoor.world.lin
import java.io.File
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * # Vergleichsszene alt/neu (nur Entwicklerwerkzeug, `indoor-dev tree-proto`)
 *
 * Schreibt zwei Mini-Welten mit **gleicher Anordnung** (eine Kachel 256 m,
 * gerade Strecke von Westen nach Osten bei n = 40 m, ebenes Gras):
 * `alt/` mit den heutigen Baeumen ([TreeModels], Stufen wie `TreesLayer`:
 * nah bis 22 m, mittel bis 40 m, sonst fern) und `neu/` mit dem Prototyp (nah
 * bis [NEAR_M], mittel bis [MID_M], dahinter weiter die heutige Fernstufe).
 * Dazu `schau-alt/` und `schau-neu/` (alle Arten in Varianten 0..3 nebeneinander),
 * `cameras.json` (Kameras und Sonnenstaende fuer Betrachter und filarender),
 * `metrics.txt`/`metrics.json` (Dreiecke, Zeichenaufrufe, Groessen) und die
 * Bibliothek `library.glb`.
 *
 * Abschnitte: **Einzelbaum** (Eiche 4,5 m neben der Mitte), **Allee** (Buchen
 * beidseitig alle 9,5 m), **Waldrand** (Buche/Fichte/Birke/Eiche, davor
 * Straeucher), **mediterran** (Aleppo-Kiefern und Straeucher, Sa Calobra).
 * Die neuen Baeume sind hier ins Kachelnetz gebacken (wie heute); im Einbau
 * waeren sie Instanzen derselben Vorlagen — fuer das Bild gleichwertig.
 */
fun writeTreeComparison(dir: File) {
    dir.mkdirs()
    val trees = scenePlan()
    writeWorld(File(dir, "alt"), "wtreealt", trees, legacy = true)
    writeWorld(File(dir, "neu"), "wtreeneu", trees, legacy = false)
    val show = showPlan()
    writeWorld(File(dir, "schau-alt"), "wtreeschaualt", show, legacy = true)
    writeWorld(File(dir, "schau-neu"), "wtreeschauneu", show, legacy = false)
    File(dir, "library.glb").writeBytes(TreeLibrary.libraryGlb())
    File(dir, "cameras.json").writeText(camerasJson())
    val (txt, json) = metrics(trees)
    File(dir, "metrics.txt").writeText(txt)
    File(dir, "metrics.json").writeText(json)
}

/** Nahstufe des Prototyps bis zu diesem Abstand von der Streckenmitte. */
const val NEAR_M = 20.0

/** Mittlere Stufe bis hier, dahinter die heutige Fernstufe. */
const val MID_M = 45.0

private const val TRACK_N = 40.0

/** Ein Baum der Szene: Lage, Art, Variante, Hoehe, Same. */
internal class Placed(val e: Double, val n: Double, val kind: TreeKind, val variant: Int, val heightM: Double, val seed: Int, val section: String) {
    val dist: Double get() = kotlin.math.abs(n - TRACK_N)
}

private fun hash01(a: Int, b: Int, s: Int): Double {
    var k = a * 374761393 + b * 668265263 + s * 1442695041
    k = (k xor (k ushr 13)) * 1274126177
    k = k xor (k ushr 16)
    return (k and 0xFFFFFF) / 16777215.0
}

internal fun scenePlan(): List<Placed> {
    val out = ArrayList<Placed>()
    // Einzelbaum am Wegrand
    out += Placed(40.0, TRACK_N + 4.5, TreeKind.OAK, 0, 17.0, 3, "einzel")
    // Allee: Buchen beidseitig
    var k = 0
    var e = 95.0
    while (e <= 166.0) {
        for (side in listOf(-1, 1)) {
            out += Placed(e + 0.6 * hash01(k, side, 5), TRACK_N + side * 6.2, TreeKind.BEECH, k % 4, 19.0 + 2 * hash01(k, side, 6), 100 + k * 2 + (side + 1) / 2, "allee")
        }
        k++
        e += 9.5
    }
    // Waldrand (Norden): Saum aus Straeuchern, dahinter Bestand
    var i = 0
    var se = 178.0
    while (se < 252.0) {
        out += Placed(se + 1.5 * hash01(i, 0, 7), TRACK_N + 7.5 + 1.5 * hash01(i, 1, 7), TreeKind.SHRUB, i % 4, 2.4 + 1.2 * hash01(i, 2, 7), 300 + i, "waldrand")
        i++
        se += 4.0
    }
    for (gj in 0 until 9) for (gi in 0 until 12) {
        val ge = 178.0 + gi * 6.5 + 4.0 * hash01(gi, gj, 8)
        val gn = TRACK_N + 11.0 + gj * 6.5 + 4.0 * hash01(gi, gj, 9)
        if (ge > 252) continue
        val r = hash01(gi, gj, 10)
        val kind = when {
            r < 0.42 -> TreeKind.BEECH
            r < 0.72 -> TreeKind.SPRUCE
            r < 0.87 -> TreeKind.BIRCH
            else -> TreeKind.OAK
        }
        val h = when (kind) {
            TreeKind.SPRUCE -> 20.0 + 8 * hash01(gi, gj, 11)
            TreeKind.BIRCH -> 14.0 + 5 * hash01(gi, gj, 11)
            else -> 18.0 + 7 * hash01(gi, gj, 11)
        }
        out += Placed(ge, gn, kind, (gi + gj * 3) % 4, h, 500 + gi * 31 + gj, "waldrand")
    }
    // Mediterran (Sueden): Aleppo-Kiefern locker, Straeucher dazwischen
    var m = 0
    for (gj in 0 until 4) for (gi in 0 until 9) {
        val ge = 180.0 + gi * 8.5 + 4.0 * hash01(gi, gj, 12)
        val gn = TRACK_N - 6.5 - gj * 8.0 - 3.0 * hash01(gi, gj, 13)
        if (hash01(gi, gj, 14) < 0.22) continue
        val pine = hash01(gi, gj, 15) < 0.7
        out += if (pine) Placed(ge, gn, TreeKind.ALEPPO_PINE, m % 4, 9.0 + 6 * hash01(gi, gj, 16), 800 + m, "mediterran")
        else Placed(ge, gn, TreeKind.SHRUB, m % 4, 1.8 + 1.4 * hash01(gi, gj, 16), 800 + m, "mediterran")
        m++
    }
    // Kiefern, Pappeln, Obstbaeume am Westende (Ergaenzung, gleiche Abstaende)
    out += Placed(12.0, TRACK_N - 7.0, TreeKind.PINE, 0, 22.0, 900, "weitere")
    out += Placed(20.0, TRACK_N - 9.0, TreeKind.POPLAR, 1, 24.0, 901, "weitere")
    out += Placed(26.0, TRACK_N + 8.0, TreeKind.FRUIT, 2, 7.0, 902, "weitere")
    out += Placed(62.0, TRACK_N + 9.0, TreeKind.FRUIT, 0, 6.5, 903, "weitere")
    return out
}

/** Typenschau: alle Arten, Varianten 0..3 in einer Reihe 12 m hinter der Strecke. */
internal fun showPlan(): List<Placed> {
    val out = ArrayList<Placed>()
    var e = 8.0
    for (kind in TreeKind.entries) {
        for (v in 0..3) {
            val p = Species.of(kind)
            out += Placed(e, TRACK_N + 12.0, kind, v, p.heights[v], 1000 + kind.ordinal * 10 + v, "schau")
            e += when (kind) {
                TreeKind.SHRUB -> 3.5
                TreeKind.FRUIT -> 5.5
                TreeKind.POPLAR, TreeKind.BIRCH, TreeKind.SPRUCE -> 6.0
                else -> 8.0
            } * (if (v == 3) 1.4 else 1.0)
        }
    }
    return out
}

/** Die heutige Wuchsform zu einer Art des Prototyps. */
internal fun legacySpec(p: Placed): TreeModels.Spec {
    fun sp(s: TreeModels.Species, pale: Boolean = false, bare: Double = 0.06, variant: Int = 0) =
        TreeModels.Spec(s, p.heightM, 1.0, 1.0, p.seed, 0.4 * p.seed, pale, bare, 0.0, variant)
    return when (p.kind) {
        TreeKind.BEECH -> sp(TreeModels.Species.BROAD)
        TreeKind.OAK -> sp(TreeModels.Species.BROAD, variant = 1)
        TreeKind.BIRCH -> sp(TreeModels.Species.TALL, pale = true)
        TreeKind.POPLAR -> sp(TreeModels.Species.TALL, variant = 1)
        TreeKind.SPRUCE -> sp(TreeModels.Species.CONIFER, bare = if (p.section == "waldrand") 0.25 else 0.06)
        TreeKind.PINE, TreeKind.ALEPPO_PINE -> sp(TreeModels.Species.PINE)
        TreeKind.FRUIT -> sp(TreeModels.Species.SMALL, variant = 1)
        TreeKind.SHRUB -> sp(TreeModels.Species.BUSH)
    }
}

private fun legacyLod(dist: Double): TreeModels.Lod = when {
    dist <= TreesLayer.DETAIL_M -> TreeModels.Lod.NEAR
    dist <= TreesLayer.MID_M -> TreeModels.Lod.MID
    else -> TreeModels.Lod.FAR
}

private fun newLod(dist: Double): TreeLod? = when {
    dist <= NEAR_M -> TreeLod.NEAR
    dist <= MID_M -> TreeLod.MID
    else -> null
}

/** Vorlagen-Speicher: je (Art, Variante, Stufe) einmal gebaut, Instanzen skaliert auf die Zielhoehe. */
private class Templates {
    private val map = HashMap<Triple<TreeKind, Int, TreeLod>, TreeMeshes>()
    fun get(k: TreeKind, v: Int, l: TreeLod): TreeMeshes = map.getOrPut(Triple(k, v, l)) { TreeLibrary.template(k, v, l) }
}

private fun writeWorld(dir: File, worldId: String, trees: List<Placed>, legacy: Boolean) {
    val spec = TileSpec(0, 0, 0)
    val out = TileOutput(spec)
    fun x(e: Double) = e - spec.anchorE
    fun z(n: Double) = -(n - spec.anchorN)
    val ground = out.mesh(WorldNames.NODE_TERRAIN, TileMaterial(WorldNames.MAT_TERRAIN, roughness = 1f))
    // Boden: leicht gewelltes Raster mit Farbstreuung (Wiese), damit Schatten und Abstand lesbar sind
    val g = 32
    val idx = Array(g + 1) { IntArray(g + 1) }
    for (j in 0..g) for (i in 0..g) {
        val e = i * 256.0 / g
        val n = j * 256.0 / g
        val dry = if (n < TRACK_N - 3 && e > 172) 0.6 else 0.0
        val f = 0.9 + 0.2 * hash01(i, j, 77)
        val c = lin(Argb.rgb(((104 + 40 * dry) * f).toInt(), ((122 + 6 * dry) * f).toInt(), ((64 + 20 * dry) * f).toInt()))
        idx[j][i] = ground.vertex(x(e), 0.0, z(n), 0.0, 1.0, 0.0, color = c)
    }
    for (j in 0 until g) for (i in 0 until g) ground.quad(idx[j][i], idx[j][i + 1], idx[j + 1][i + 1], idx[j + 1][i])
    val road = out.mesh(WorldNames.NODE_ROUTE, TileMaterial(WorldNames.MAT_ROUTE_PREFIX + "asphalt", roughness = 0.9f))
    val rc = lin(Argb.rgb(88, 88, 90))
    val r0 = road.vertex(x(0.0), 0.03, z(TRACK_N - 2.5), 0.0, 1.0, 0.0, color = rc)
    val r1 = road.vertex(x(256.0), 0.03, z(TRACK_N - 2.5), 0.0, 1.0, 0.0, color = rc)
    val r2 = road.vertex(x(256.0), 0.03, z(TRACK_N + 2.5), 0.0, 1.0, 0.0, color = rc)
    val r3 = road.vertex(x(0.0), 0.03, z(TRACK_N + 2.5), 0.0, 1.0, 0.0, color = rc)
    road.quad(r0, r1, r2, r3)

    val textures = TextureCache()
    val oldLeaves = TileMaterial(
        TreesLayer.MAT_CARDS, texture = FoliageAtlas.atlasTexture, repeat = false, roughness = 0.95f,
        alphaMode = GlbAlphaMode.MASK, alphaCutoff = 0.5f, doubleSided = false, noSpecular = true,
    )
    val oldBark = textures.get("x_bark", jpegQuality = 84) { FoliageAtlas.barkImage() }
    val oldTrunkMat = TileMaterial(WorldNames.MAT_TREE_TRUNK, texture = oldBark, repeat = true, roughness = 0.95f, noSpecular = true)
    val newLeaves = TileMaterial(
        TreeLibrary.MAT_LEAVES, texture = EncodedTexture(TreeTextures.leafPng, "image/png", TreeTextures.LEAF_SIZE, TreeTextures.LEAF_SIZE),
        repeat = false, roughness = 0.95f, alphaMode = GlbAlphaMode.MASK, alphaCutoff = 0.5f, doubleSided = false, noSpecular = true,
    )
    val newTrunkMat = TileMaterial(
        TreeLibrary.MAT_TRUNK, texture = EncodedTexture(TreeTextures.barkJpeg, "image/jpeg", TreeTextures.BARK_W, TreeTextures.BARK_H),
        repeat = true, roughness = 0.95f, noSpecular = true,
    )
    // Fernstufe ist in beiden Welten die heutige; in "neu" braucht sie eigene Materialnamen (anderer
    // Atlas als das neue Laub, ein Name je Material und Kachel). Die Darstellung zeigt diese
    // x_-Materialien mit dem Ubershader statt dem Laubmaterial (kein Wind) — nur im Hintergrund.
    val trunks = out.mesh(WorldNames.NODE_TREES, if (legacy) oldTrunkMat else newTrunkMat)
    val crowns = out.mesh(WorldNames.NODE_TREES, if (legacy) oldLeaves else newLeaves)
    val farTrunks = if (legacy) trunks else out.mesh(WorldNames.NODE_TREES, TileMaterial("x_mat_tree_trunk_far", oldBark, true, roughness = 0.95f, noSpecular = true))
    val farCrowns = if (legacy) crowns else out.mesh(WorldNames.NODE_TREES, TileMaterial("x_mat_tree_cards_far", FoliageAtlas.atlasTexture, false, roughness = 0.95f, alphaMode = GlbAlphaMode.MASK, alphaCutoff = 0.5f, noSpecular = true))
    val t = MeshBuilder(64, 64)
    val cr = MeshBuilder(256, 256)
    val tpl = Templates()
    for (p in trees) {
        val m0 = Mat4.translation(x(p.e), 0.0, z(p.n))
        val lodNew = if (legacy) null else newLod(p.dist)
        if (lodNew == null) {
            t.clear(); cr.clear()
            val toward = Vec3(0.0, 0.0, if (p.n > TRACK_N) 1.0 else -1.0)
            TreeModels.build(legacySpec(p), legacyLod(p.dist), t, cr, toward)
            farTrunks.append(t, m0)
            farCrowns.append(cr, m0)
            continue
        }
        val tm = tpl.get(p.kind, p.variant, lodNew)
        val s = p.heightM / tm.skeleton.heightM
        val rot = 2 * Math.PI * hash01(p.seed, 0, 99)
        val m = m0 * Mat4.rotationY(rot) * Mat4.scale(s)
        trunks.append(tm.bark, m)
        crowns.append(tm.leaves, m)
    }
    dir.mkdirs()
    File(dir, "tiles").mkdirs()
    val glb = out.toGlb().toBytes()
    File(dir, "tiles/${spec.id}.glb").writeBytes(glb)
    val e = (0..50).joinToString(",") { "%.2f".format(Locale.ROOT, it * 5.0) }
    val n = (0..50).joinToString(",") { "%.2f".format(Locale.ROOT, TRACK_N) }
    val u = (0..50).joinToString(",") { "0.0" }
    File(dir, "manifest.json").writeText(
        """{"format":1,"worldId":"$worldId","builder":"$WORLD_BUILDER_ID","createdAtMs":0,"quality":"MEDIUM","routeId":"$worldId","routeName":"Baumvergleich",""" +
            """"origin":{"lat":51.0,"lon":7.25,"eleM":0.0},"bounds":{"minE":0.0,"minN":0.0,"maxE":256.0,"maxN":256.0,"minU":0.0,"maxU":40.0},""" +
            """"track":{"lengthM":250.0,"stepM":5.0,"loop":false,"e":[$e],"n":[$n],"u":[$u],"surfaces":[{"fromM":0.0,"toM":250.0,"kind":"ASPHALT","highway":"tertiary"}],"source":"BROUTER_WAY_TAGS"},""" +
            """"levels":[{"level":0,"cellM":256.0,"texturePx":1024}],""" +
            """"tiles":[{"id":"${spec.id}","level":0,"ix":0,"iy":0,"file":"tiles/${spec.id}.glb","bytes":${glb.size},"anchor":[128.0,128.0,0.0],"minU":0.0,"maxU":40.0,"show":[[0.0,250.0]],"textureBytes":4000000,"triangles":${out.triangleCount}}],""" +
            """"budget":{"maxTextureBytes":100000000,"maxTriangles":2000000,"diskBytes":${glb.size}},"attribution":[]}""",
    )
}

// ---------------------------------------------------------------------------
// Kameras
// ---------------------------------------------------------------------------

/** Eine Kamera: Name, Auge und Ziel (e, n, u), Sonne (Azimut 0 = Nord, 90 = Ost; Hoehe). */
internal class Shot(val name: String, val eye: DoubleArray, val at: DoubleArray, val sunAz: Double, val sunEl: Double, val world: String = "")

internal fun shots(): List<Shot> {
    val out = ArrayList<Shot>()
    fun az(from: DoubleArray, to: DoubleArray): Double {
        val a = Math.toDegrees(atan2(to[0] - from[0], to[1] - from[1]))
        return (a + 360) % 360
    }
    // Einzelbaum (Eiche bei e 40, n 44,5): Kamera auf der Fahrbahn, 5 / 15 / 40 m vor dem Baum
    val tree = doubleArrayOf(40.0, TRACK_N + 4.5)
    for (d in listOf(5, 15, 40)) {
        val lat = 4.5 + 0.8 // Kamera 0,8 m rechts der Mitte
        val along = sqrt(kotlin.math.max(1.0, d * d - lat * lat))
        val eye = doubleArrayOf(tree[0] - along, TRACK_N - 0.8, 1.6)
        val aimH = if (d <= 5) 4.5 else if (d <= 15) 6.0 else 7.5
        val at = doubleArrayOf(tree[0], tree[1], aimH)
        val va = az(eye, at)
        out += Shot("einzel-${"%02d".format(d)}m-gegenlicht", eye, at, va, 22.0)
        out += Shot("einzel-${"%02d".format(d)}m-auflicht", eye, at, (va + 180) % 360, 42.0)
    }
    // Allee: Blick die Strasse entlang (Fahrerhoehe)
    run {
        val eye = doubleArrayOf(84.0, TRACK_N - 0.8, 1.7)
        val at = doubleArrayOf(130.0, TRACK_N + 0.5, 4.0)
        out += Shot("allee-gegenlicht", eye, at, 80.0, 20.0)
        out += Shot("allee-auflicht", eye, at, 235.0, 40.0)
    }
    // Waldrand: schraeg nach vorn links
    run {
        val eye = doubleArrayOf(168.0, TRACK_N - 1.0, 1.7)
        val at = doubleArrayOf(200.0, TRACK_N + 14.0, 6.0)
        out += Shot("waldrand-gegenlicht", eye, at, az(eye, at), 22.0)
        out += Shot("waldrand-auflicht", eye, at, (az(eye, at) + 180) % 360, 40.0)
    }
    // Mediterran: schraeg nach vorn rechts
    run {
        val eye = doubleArrayOf(170.0, TRACK_N + 1.0, 1.7)
        val at = doubleArrayOf(198.0, TRACK_N - 12.0, 4.0)
        out += Shot("mediterran-gegenlicht", eye, at, az(eye, at), 25.0)
        out += Shot("mediterran-auflicht", eye, at, (az(eye, at) + 180) % 360, 50.0)
    }
    // Typenschau (eigene Welt): von der Strecke aus, drei Ausschnitte
    for ((k, e0) in listOf(40.0, 110.0, 175.0).withIndex()) {
        val eye = doubleArrayOf(e0, TRACK_N - 14.0, 3.0)
        val at = doubleArrayOf(e0, TRACK_N + 12.0, 7.0)
        out += Shot("schau-${k + 1}", eye, at, 210.0, 40.0, world = "schau")
    }
    return out
}

private fun camerasJson(): String = shots().joinToString(",\n", "[\n", "\n]\n") { s ->
    fun arr(a: DoubleArray) = a.joinToString(",", "[", "]") { "%.2f".format(Locale.ROOT, it) }
    """  {"name":"${s.name}","world":"${s.world}","eye":${arr(s.eye)},"at":${arr(s.at)},"sunAz":${"%.1f".format(Locale.ROOT, s.sunAz)},"sunEl":${"%.1f".format(Locale.ROOT, s.sunEl)}}"""
}

// ---------------------------------------------------------------------------
// Messung
// ---------------------------------------------------------------------------

private fun metrics(trees: List<Placed>): Pair<String, String> {
    val sb = StringBuilder()
    val js = StringBuilder("{\n  \"templates\": [\n")
    sb.append("Baum-Prototyp: Kennzahlen je Vorlage (Dreiecke inkl. beider Wicklungen der Laubkarten)\n\n")
    sb.append(String.format(Locale.ROOT, "%-12s %2s %-4s %7s %7s %5s %6s %6s %9s %7s\n", "Art", "V", "Stufe", "Dreieck", "Ecken", "Prim", "Hoehe", "Krone", "GLB-Netz", "Budget"))
    val rows = ArrayList<String>()
    for (k in TreeKind.entries) for (v in 0..3) for (l in TreeLod.entries) {
        val s = TreeLibrary.stats(k, v, l)
        sb.append(String.format(Locale.ROOT, "%-12s %2d %-4s %7d %7d %5d %6.1f %6.1f %9d %7d\n", k.label, v, l.name, s.triangles, s.vertices, s.primitives, s.heightM, s.crownWidthM, s.meshBytes, TreeBudget.triangles(k, l)))
        rows += """    {"kind":"${k.name}","variant":$v,"lod":"${l.name}","triangles":${s.triangles},"vertices":${s.vertices},"primitives":${s.primitives},"heightM":${"%.2f".format(Locale.ROOT, s.heightM)},"crownM":${"%.2f".format(Locale.ROOT, s.crownWidthM)},"meshBytes":${s.meshBytes},"budget":${TreeBudget.triangles(k, l)}}"""
    }
    js.append(rows.joinToString(",\n")).append("\n  ],\n")
    // heutige Baeume zum Vergleich (TreeModels, typische Hoehe)
    sb.append("\nHeute (TreeModels, gleiche Hoehen): Dreiecke nah/mittel/fern\n")
    for (k in TreeKind.entries) {
        val p = Placed(0.0, 0.0, k, 0, Species.of(k).heights[0], 1, "")
        val spec = legacySpec(p)
        val c = TreeModels.Lod.entries.reversed().joinToString(" / ") { TreeModels.cost(spec, it).toString() }
        sb.append(String.format(Locale.ROOT, "%-12s %s\n", k.label, c))
    }
    val lib = TreeLibrary.libraryGlb()
    val (texFile, texGpu) = TreeLibrary.textureBytes()
    sb.append(String.format(Locale.ROOT, "\nBibliothek (alle %d Vorlagen, eine GLB): %d Bytes; davon Atlanten %d Bytes (Grafikspeicher %d Bytes)\n", TreeKind.entries.size * 4 * 2, lib.size, texFile, texGpu))
    js.append("  \"libraryBytes\": ${lib.size},\n  \"textureFileBytes\": $texFile,\n  \"textureGpuBytes\": $texGpu,\n")
    // Szenen: Summe alt/neu je Abschnitt
    sb.append("\nVergleichsszene: Dreiecke je Abschnitt alt → neu (neu: nah ≤ ${NEAR_M.toInt()} m, mittel ≤ ${MID_M.toInt()} m, fern wie heute)\n")
    val tpl = Templates()
    val secs = ArrayList<String>()
    for (sec in trees.map { it.section }.distinct()) {
        var oldT = 0
        var newT = 0
        var near = 0
        var mid = 0
        for (p in trees.filter { it.section == sec }) {
            oldT += TreeModels.cost(legacySpec(p), legacyLod(p.dist))
            val l = newLod(p.dist)
            if (l == null) newT += TreeModels.cost(legacySpec(p), TreeModels.Lod.FAR) else {
                newT += tpl.get(p.kind, p.variant, l).triangles
                if (l == TreeLod.NEAR) near++ else mid++
            }
        }
        val count = trees.count { it.section == sec }
        sb.append(String.format(Locale.ROOT, "%-11s %3d Baeume (nah %d, mittel %d): %7d → %7d\n", sec, count, near, mid, oldT, newT))
        secs += """    {"section":"$sec","trees":$count,"near":$near,"mid":$mid,"oldTriangles":$oldT,"newTriangles":$newT}"""
    }
    js.append("  \"sections\": [\n").append(secs.joinToString(",\n")).append("\n  ]\n}\n")
    return sb.toString() to js.toString()
}
