package de.trailscape.core.indoor.world

import java.io.File

/**
 * # Baumschau (nur Entwicklerwerkzeug)
 *
 * Schreibt eine Mini-Welt (eine Kachel, gerade Strecke von 250 m) mit allen
 * Baumarten in allen Stufen auf ebenem Gras: je Art eine Spalte, in Zeilen
 * hintereinander `NEAR`, `MID`, `FAR` (14, 38 und 62 m neben der Strecke —
 * so weit, wie die Stufen im Weltbau vergeben werden). Gedacht fuer den
 * Betrachter (`shots.mjs … --cameras free`), um Modelle ohne Weltbau zu
 * vergleichen. Die Kachel gehoert zu keiner echten Welt.
 */
fun writeTreeSheet(dir: File) {
    val spec = TileSpec(0, 0, 0)
    val out = TileOutput(spec)
    val textures = TextureCache()
    fun x(e: Double) = e - spec.anchorE
    fun z(n: Double) = -(n - spec.anchorN)
    // Boden
    val ground = out.mesh(WorldNames.NODE_TERRAIN, TileMaterial(WorldNames.MAT_TERRAIN, roughness = 1f))
    val gc = lin(Argb.rgb(104, 122, 64))
    val a = ground.vertex(x(0.0), 0.0, z(0.0), 0.0, 1.0, 0.0, color = gc)
    val b = ground.vertex(x(256.0), 0.0, z(0.0), 0.0, 1.0, 0.0, color = gc)
    val c = ground.vertex(x(256.0), 0.0, z(256.0), 0.0, 1.0, 0.0, color = gc)
    val d = ground.vertex(x(0.0), 0.0, z(256.0), 0.0, 1.0, 0.0, color = gc)
    ground.quad(a, b, c, d)
    // Strecke als grauer Streifen
    val road = out.mesh(WorldNames.NODE_ROUTE, TileMaterial(WorldNames.MAT_ROUTE_PREFIX + "asphalt", roughness = 0.9f))
    val rc = lin(Argb.rgb(90, 90, 92))
    val r0 = road.vertex(x(0.0), 0.03, z(TRACK_N - 2.5), 0.0, 1.0, 0.0, color = rc)
    val r1 = road.vertex(x(256.0), 0.03, z(TRACK_N - 2.5), 0.0, 1.0, 0.0, color = rc)
    val r2 = road.vertex(x(256.0), 0.03, z(TRACK_N + 2.5), 0.0, 1.0, 0.0, color = rc)
    val r3 = road.vertex(x(0.0), 0.03, z(TRACK_N + 2.5), 0.0, 1.0, 0.0, color = rc)
    road.quad(r0, r1, r2, r3)

    val leaves = TileMaterial(
        TreesLayer.MAT_CARDS, texture = FoliageAtlas.atlasTexture, repeat = false, roughness = 0.95f,
        alphaMode = GlbAlphaMode.MASK, alphaCutoff = 0.5f, doubleSided = false, noSpecular = true,
    )
    val bark = textures.get("x_bark", jpegQuality = 84) { FoliageAtlas.barkImage() }
    val trunkMat = TileMaterial(WorldNames.MAT_TREE_TRUNK, texture = bark, repeat = true, roughness = 0.95f, noSpecular = true)
    val trunks = out.mesh(WorldNames.NODE_TREES, trunkMat)
    val crowns = out.mesh(WorldNames.NODE_TREES, leaves)
    val t = MeshBuilder(64, 64)
    val cr = MeshBuilder(256, 256)
    val report = StringBuilder()
    val specs = sheetSpecs()
    for ((k, s) in specs.withIndex()) {
        val e = 12.0 + k * (232.0 / max1(specs.size - 1))
        for ((row, lod) in listOf(TreeModels.Lod.NEAR, TreeModels.Lod.MID, TreeModels.Lod.FAR).withIndex()) {
            val n = TRACK_N + 14.0 + row * 60.0
            t.clear(); cr.clear()
            TreeModels.build(s, lod, t, cr, Vec3(0.0, 0.0, 1.0))
            val m = Mat4.translation(x(e), 0.0, z(n))
            trunks.append(t, m)
            crowns.append(cr, m)
            report.append("${s.species} ${"%.0f".format(s.heightM)} m $lod: ${t.triangleCount + cr.triangleCount} Dreiecke (cost ${TreeModels.cost(s, lod)})\n")
        }
    }
    dir.mkdirs()
    File(dir, "tiles").mkdirs()
    val glb = out.toGlb().toBytes()
    File(dir, "tiles/${spec.id}.glb").writeBytes(glb)
    val e = (0..50).joinToString(",") { "%.2f".format(java.util.Locale.ROOT, it * 5.0) }
    val n = (0..50).joinToString(",") { "%.2f".format(java.util.Locale.ROOT, TRACK_N) }
    val u = (0..50).joinToString(",") { "0.0" }
    File(dir, "manifest.json").writeText(
        """{"format":1,"worldId":"wtreesheet","builder":"$WORLD_BUILDER_ID","createdAtMs":0,"quality":"MEDIUM","routeId":"baumschau","routeName":"Baumschau",""" +
            """"origin":{"lat":51.0,"lon":7.25,"eleM":0.0},"bounds":{"minE":0.0,"minN":0.0,"maxE":256.0,"maxN":256.0,"minU":0.0,"maxU":40.0},""" +
            """"track":{"lengthM":250.0,"stepM":5.0,"loop":false,"e":[$e],"n":[$n],"u":[$u],"surfaces":[{"fromM":0.0,"toM":250.0,"kind":"ASPHALT","highway":"tertiary"}],"source":"BROUTER_WAY_TAGS"},""" +
            """"levels":[{"level":0,"cellM":256.0,"texturePx":1024}],""" +
            """"tiles":[{"id":"${spec.id}","level":0,"ix":0,"iy":0,"file":"tiles/${spec.id}.glb","bytes":${glb.size},"anchor":[128.0,128.0,0.0],"minU":0.0,"maxU":40.0,"show":[[0.0,250.0]],"textureBytes":4000000,"triangles":${out.triangleCount}}],""" +
            """"budget":{"maxTextureBytes":100000000,"maxTriangles":700000,"diskBytes":${glb.size}},"attribution":[]}""",
    )
    File(dir, "trees.txt").writeText(report.toString())
}

private fun max1(v: Int) = if (v < 1) 1 else v

private const val TRACK_N = 40.0

/** Arten der Baumschau in typischer Hoehe (die Reihenfolge ist die der Spalten). */
internal fun sheetSpecs(): List<TreeModels.Spec> {
    fun sp(s: TreeModels.Species, h: Double, seed: Int, pale: Boolean = false, bare: Double = 0.06, variant: Int = 0) =
        TreeModels.Spec(s, h, 1.0, 1.0, seed, 0.4 * seed, pale, bare, 0.0, variant)
    return listOf(
        sp(TreeModels.Species.BROAD, 22.0, 11),
        sp(TreeModels.Species.BROAD, 20.0, 12, variant = 1),
        sp(TreeModels.Species.TALL, 18.0, 13, pale = true),
        sp(TreeModels.Species.TALL, 22.0, 14),
        sp(TreeModels.Species.TALL, 24.0, 20, variant = 1),
        sp(TreeModels.Species.SMALL, 7.0, 15, variant = 1),
        sp(TreeModels.Species.SMALL, 8.0, 21),
        sp(TreeModels.Species.CONIFER, 24.0, 16, bare = 0.25),
        sp(TreeModels.Species.CONIFER, 14.0, 17),
        sp(TreeModels.Species.PINE, 21.0, 18),
        sp(TreeModels.Species.BUSH, 2.6, 19),
    )
}
