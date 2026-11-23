package de.trailscape.core.indoor.world

/**
 * # Wasserflaechen (Knoten `water`)
 *
 * Seen, Teiche und breite Fluesse aus der Landnutzung ([WaterMask], aus den
 * Wasserflaechen der Karte gerastert) als Spiegel auf Uferhoehe — nach der
 * Regel aus [CarvedGround]: Die Oberflaeche liegt an jeder Stelle
 * 0,05 m unter dem trockenen Gelaende ([CarvedGround.dryHeightAt]), der
 * Boden darunter ist abgesenkt. So folgt der Spiegel dem Gefaelle eines
 * Flusses und wird am Ufer vom Gelaende verdeckt, ohne dass die Kanten der
 * Rasterzellen zu sehen sind.
 *
 * Gitter: Zellen von 4 m (Stufe 0) bzw. 16 m (Stufe 1), verdoppelt, bis
 * [TileBudget.water] reicht. Eine Zelle wird belegt, sobald einer ihrer vier
 * Eckpunkte Wasser beruehrt. Ab Stufe 1 haengt jede Zelle an ihrer
 * Teilflaeche. Material [WorldNames.MAT_WATER]: Vertexfarben (tiefer = dunkler),
 * raue Oberflaeche (es gibt keine Spiegelung).
 *
 * Schmale Wasserlaeufe (Linien der Karte) entstehen hier nicht — siehe
 * docs/indoor/world.md.
 */
internal class WaterLayer : TileLayer {
    override fun build(ctx: TileContext, out: TileOutput) {
        val budget = ctx.budget.water
        if (budget <= 0) return
        val mask = ctx.land as? WaterMask ?: return
        val s = ctx.spec
        var cell = if (ctx.level == 0) 4.0 else 16.0
        while (true) {
            val cells = (s.cellM / cell).toInt()
            val wet = BooleanArray(cells * cells)
            var count = 0
            val corner = Array(cells + 1) { j -> DoubleArray(cells + 1) { i -> mask.waterAt(s.minE + i * cell, s.minN + j * cell) } }
            for (j in 0 until cells) for (i in 0 until cells) {
                val w = maxOf(corner[j][i], corner[j][i + 1], corner[j + 1][i], corner[j + 1][i + 1])
                if (w > 0.01) {
                    wet[j * cells + i] = true
                    count++
                }
            }
            if (count == 0) return
            if (count * 2 > budget && cell < s.cellM / 8) {
                cell *= 2
                continue
            }
            if (count * 2 > budget) return
            emit(ctx, out, wet, corner, cells, cell)
            return
        }
    }

    private fun emit(ctx: TileContext, out: TileOutput, wet: BooleanArray, corner: Array<DoubleArray>, cells: Int, cell: Double) {
        val s = ctx.spec
        val carved = ctx.ground as? CarvedGround
        val mat = TileMaterial(WorldNames.MAT_WATER, baseColor = floatArrayOf(1f, 1f, 1f, 1f), roughness = 0.6f)
        fun surfaceAt(e: Double, n: Double): Double =
            if (carved != null) carved.dryHeightAt(e, n) - SURFACE_BELOW_SHORE_M else ctx.ground.heightAt(e, n) + 0.3
        for (j in 0 until cells) for (i in 0 until cells) {
            if (!wet[j * cells + i]) continue
            val e0 = s.minE + i * cell
            val n0 = s.minN + j * cell
            val m = out.mesh(WorldNames.NODE_WATER, mat, e0 + cell / 2, n0 + cell / 2)
            val ids = IntArray(4)
            val pts = arrayOf(intArrayOf(0, 0), intArrayOf(1, 0), intArrayOf(1, 1), intArrayOf(0, 1))
            for ((k, p) in pts.withIndex()) {
                val e = e0 + p[0] * cell
                val n = n0 + p[1] * cell
                val depth = corner[j + p[1]][i + p[0]]
                val color = lin(Argb.rgb((58 - 18 * depth).toInt(), (78 - 20 * depth).toInt(), (80 - 14 * depth).toInt()))
                ids[k] = m.vertex(ctx.x(e), ctx.y(surfaceAt(e, n)), ctx.z(n), 0.0, 1.0, 0.0, e / 8.0, -n / 8.0, color)
            }
            m.quad(ids[0], ids[1], ids[2], ids[3])
        }
    }

    private companion object {
        /** Abstand des Spiegels unter dem trockenen Ufer (Regel aus [CarvedGround]). */
        const val SURFACE_BELOW_SHORE_M = 0.05
    }
}
