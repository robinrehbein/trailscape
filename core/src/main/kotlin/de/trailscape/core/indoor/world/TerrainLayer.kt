package de.trailscape.core.indoor.world

/**
 * # Das Gelaendenetz einer Kachel
 *
 * **Stufe 0:** ein regelmaessiges Gitter ueber die ganze Kachel, so fein, wie
 * [TileBudget.terrain] es erlaubt (MEDIUM 64 × 64 Zellen = 4 m). Feiner nahe
 * der Strecke wird bewusst **nicht**: [CarvedGround] legt neben der Fahrbahn
 * ein Bankett von einer Zellendiagonale an, damit sticht kein Dreieck durch
 * die Fahrbahn (Herleitung im KDoc von [CarvedGround]); eine lokale
 * Verfeinerung koestete bei MEDIUM rund ein Viertel des Gelaendebudgets.
 *
 * **Ab Stufe 1:** genau 16 Teilflaechen (`terrain_sub_i_j`), jede ein eigenes
 * Gitter mit eigener Schuerze — die Darstellung blendet sie einzeln aus.
 *
 * Ringsum haengt eine senkrechte **Schuerze** ([skirtDepthM]) mit Normale und
 * UV des Rands, die Spalten zu groeberen Stufen verdeckt. Alle Eckpunkte
 * entstehen aus Welt-Koordinaten (`minE + cellM · i / N`) und [TileContext.ground]:
 * Nachbarkacheln derselben Stufe haben identische Randpunkte. Normalen kommen
 * aus der Hangneigung der Flaeche ([normalAt]), nicht aus dem Netz — stetig
 * ueber Kachelgrenzen.
 *
 * Material [WorldNames.MAT_TERRAIN]: das Luftbild der Kachel
 * ([TileContext.aerialTexture], unveraendert, CLAMP) oder die erzeugte
 * Bodentextur ([groundTexture]) in `quality.texturePx(level)`. UV: `u` nach
 * Osten, `v` nach Sueden ueber die ganze Kachel, mit einem halben Texel Einzug.
 */
internal class TerrainLayer(
    /** Bodentextur fuer Kacheln ohne Luftbild; Vorgabe: [groundTexture], als Baseline-JPEG. */
    private val generatedTexture: (TileContext) -> EncodedTexture = ::defaultGroundTexture,
) : TileLayer {

    override fun build(ctx: TileContext, out: TileOutput) {
        val spec = ctx.spec
        val tex = ctx.aerialTexture ?: generatedTexture(ctx)
        val mat = TileMaterial(WorldNames.MAT_TERRAIN, texture = tex, repeat = false, roughness = 0.95f)
        val texPx = tex.width
        if (spec.level == 0) {
            val cells = terrainCells(ctx.budget.terrain, 1)
            patch(ctx, out, mat, texPx, spec.minE, spec.minN, spec.cellM, cells)
        } else {
            val f = WORLD_LEVEL_FACTOR
            val sub = spec.cellM / f
            val cells = terrainCells(ctx.budget.terrain, f * f)
            for (i in 0 until f) for (j in 0 until f) {
                patch(ctx, out, mat, texPx, spec.minE + i * sub, spec.minN + j * sub, sub, cells)
            }
        }
    }

    /** Ein Gitter von [cells]² Zellen ueber das Quadrat ab (`e0`, `n0`) mit Kante [size], samt Schuerze. */
    private fun patch(
        ctx: TileContext,
        out: TileOutput,
        mat: TileMaterial,
        texPx: Int,
        e0: Double,
        n0: Double,
        size: Double,
        cells: Int,
    ) {
        val spec = ctx.spec
        val mesh = out.mesh(WorldNames.NODE_TERRAIN, mat, e0 + size / 2, n0 + size / 2)
        val ground = ctx.ground
        val eps = normalEps(spec.level, size / cells)
        val row = cells + 1
        val base = mesh.vertexCount
        mesh.ensureVertexCapacity(base + row * row + 8 * cells)
        mesh.ensureTriangleCapacity(mesh.triangleCount + 2 * cells * cells + 8 * cells)
        val inset = 0.5 / texPx
        val scale = 1.0 - 2 * inset
        fun uOf(e: Double) = inset + (e - spec.minE) / spec.cellM * scale
        fun vOf(n: Double) = inset + (spec.maxN - n) / spec.cellM * scale
        // Gitterpunkte: j nach Norden, i nach Osten.
        for (j in 0..cells) {
            val n = if (j == cells) n0 + size else n0 + size * j / cells
            for (i in 0..cells) {
                val e = if (i == cells) e0 + size else e0 + size * i / cells
                val h = ground.heightAt(e, n)
                val nor = ground.normalAt(e, n, eps)
                mesh.vertex(ctx.x(e), ctx.y(h), ctx.z(n), nor.x, nor.y, nor.z, uOf(e), vOf(n))
            }
        }
        for (j in 0 until cells) for (i in 0 until cells) {
            val a = base + j * row + i
            // Gegen den Uhrzeigersinn von oben: Osten = +x, Norden = −z.
            // Diagonale abwechselnd, damit keine Vorzugsrichtung im Netz sichtbar wird.
            if ((i + j) and 1 == 0) {
                mesh.tri(a, a + 1, a + row + 1)
                mesh.tri(a, a + row + 1, a + row)
            } else {
                mesh.tri(a, a + 1, a + row)
                mesh.tri(a + 1, a + row + 1, a + row)
            }
        }
        // Schuerze: Randpunkte im Umlauf gegen den Uhrzeigersinn (von oben), aussen nach unten.
        val depth = skirtDepthM(spec.level)
        val ring = IntArray(4 * cells)
        var p = 0
        for (i in 0 until cells) ring[p++] = base + i // Suedrand nach Osten
        for (j in 0 until cells) ring[p++] = base + j * row + cells // Ostrand nach Norden
        for (i in cells downTo 1) ring[p++] = base + cells * row + i // Nordrand nach Westen
        for (j in cells downTo 1) ring[p++] = base + j * row // Westrand nach Sueden
        val top = IntArray(ring.size)
        val bottom = IntArray(ring.size)
        for (k in ring.indices) {
            val v = ring[k]
            val x = mesh.x(v).toDouble()
            val y = mesh.y(v).toDouble()
            val z = mesh.z(v).toDouble()
            val nx = mesh.normals[v * 3].toDouble()
            val ny = mesh.normals[v * 3 + 1].toDouble()
            val nz = mesh.normals[v * 3 + 2].toDouble()
            val u = mesh.uvs[v * 2].toDouble()
            val w = mesh.uvs[v * 2 + 1].toDouble()
            top[k] = mesh.vertex(x, y, z, nx, ny, nz, u, w)
            bottom[k] = mesh.vertex(x, y - depth, z, nx, ny, nz, u, w)
        }
        for (k in ring.indices) {
            val k2 = (k + 1) % ring.size
            // Von aussen gesehen gegen den Uhrzeigersinn: oben k → oben k2 laeuft
            // (von oben) gegen den Uhrzeigersinn, aussen also von rechts nach links.
            mesh.tri(top[k], bottom[k], bottom[k2])
            mesh.tri(top[k], bottom[k2], top[k2])
        }
    }

    companion object {
        /** Tiefe der Schuerze je Stufe: 5 m, 15 m, 30 m. */
        fun skirtDepthM(level: Int): Double = when (level) {
            0 -> 5.0
            1 -> 15.0
            else -> 30.0
        }

        /**
         * Zellen je Gitterkante, sodass [patches] Gitter samt Schuerze
         * (`2N² + 8N` Dreiecke je Gitter) in [budget] passen; mindestens 2.
         */
        fun terrainCells(budget: Int, patches: Int): Int {
            val per = budget / patches
            var n = 2
            while (2 * (n + 1) * (n + 1) + 8 * (n + 1) <= per) n++
            return n
        }

        /** Abstand der zentralen Differenzen fuer die Normale: eine halbe Zelle, mindestens 1 m. */
        fun normalEps(level: Int, cellM: Double): Double = if (level == 0) maxOf(1.0, cellM / 2) else cellM / 2
    }
}

/**
 * Hoehe des **gezeichneten** Gelaendenetzes der Stufe 0 an (`e`, `n`): dieselben Gitterpunkte
 * (Raster [cellM] am Ursprung) und dieselbe Diagonalenwahl wie [TerrainLayer], linear im
 * Dreieck. Zwischen den Gitterpunkten weicht das Netz vom wahren Boden ab — wer etwas knapp
 * ueber den Boden legt (Strassen, Wegraender), nimmt das Maximum aus beidem, sonst sticht das
 * Netz durch und flimmert.
 */
internal fun terrainMeshHeightAt(ground: GroundSurface, e: Double, n: Double, cellM: Double): Double {
    val gi = kotlin.math.floor(e / cellM)
    val gj = kotlin.math.floor(n / cellM)
    val fx = e / cellM - gi
    val fy = n / cellM - gj
    val e0 = gi * cellM
    val n0 = gj * cellM
    val h00 = ground.heightAt(e0, n0)
    val h10 = ground.heightAt(e0 + cellM, n0)
    val h01 = ground.heightAt(e0, n0 + cellM)
    val h11 = ground.heightAt(e0 + cellM, n0 + cellM)
    val even = ((gi.toLong() + gj.toLong()) and 1L) == 0L
    return if (even) {
        // Diagonale (0,0)–(1,1)
        if (fx >= fy) h00 + fx * (h10 - h00) + fy * (h11 - h10) else h00 + fy * (h01 - h00) + fx * (h11 - h01)
    } else {
        // Diagonale (1,0)–(0,1)
        if (fx + fy <= 1.0) h00 + fx * (h10 - h00) + fy * (h01 - h00) else h11 + (1 - fx) * (h01 - h11) + (1 - fy) * (h10 - h11)
    }
}

/** Zellenkante des Gelaendenetzes der Stufe 0 bei [quality] — dieselbe Zahl braucht [CarvedGround]. */
internal fun level0TerrainCellM(quality: IndoorQuality): Double =
    WORLD_LEVEL0_CELL_M / TerrainLayer.terrainCells(TileBudget.of(quality, 0).terrain, 1)

/** Die erzeugte Bodentextur einer Kachel als Baseline-JPEG (je Kachel einmalig, nicht im [TextureCache]). */
internal fun defaultGroundTexture(ctx: TileContext): EncodedTexture {
    val px = ctx.quality.texturePx(ctx.level)
    val img = groundTexture(ctx.spec, ctx.land, ctx.map, ctx.track, px)
    return EncodedTexture(encodeJpegBaseline(img, 86), "image/jpeg", px, px)
}
