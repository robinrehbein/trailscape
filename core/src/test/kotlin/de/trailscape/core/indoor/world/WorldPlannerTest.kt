package de.trailscape.core.indoor.world

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class WorldPlannerTest {
    private val o = TerrainTestSupport.origin

    private fun path(line: DoubleArray, loop: Boolean): PlanPath = PlanPath.of(TerrainTestSupport.routeFromEnu(line, loop), o)

    private val straight by lazy { path(doubleArrayOf(0.0, 0.0, 14_000.0, 6_000.0), false) }
    private val loop30 by lazy { path(TerrainTestSupport.circle(0.0, 0.0, 30_000.0 / (2 * PI), 20.0), true) }
    private val eight by lazy {
        // Lemniskate: kreuzt sich in der Mitte.
        val n = 1200
        val a = 2500.0
        path(DoubleArray((n + 1) * 2) { k ->
            val t = 2 * PI * (k / 2) / n
            val den = 1 + sin(t) * sin(t)
            if (k % 2 == 0) a * cos(t) / den else a * sin(t) * cos(t) / den
        }, true)
    }
    private val serpentine by lazy {
        // Enge Serpentine: 30 Kehren, Aeste 25 m auseinander.
        val pts = ArrayList<Double>()
        for (k in 0 until 30) {
            val y = k * 25.0
            if (k % 2 == 0) { pts.add(0.0); pts.add(y); pts.add(400.0); pts.add(y) } else { pts.add(400.0); pts.add(y); pts.add(0.0); pts.add(y) }
        }
        path(pts.toDoubleArray(), false)
    }

    /** Lueckenlos: unter dem Fahrer eine Stufe-0-Kachel, jeder Punkt im Sichtkreis von irgendeiner Stufe gedeckt, Eltern-Regel. */
    private fun assertGapless(layout: TileLayout, p: PlanPath) {
        val byLevel = (0..2).map { lv -> layout.plans.filter { it.spec.level == lv }.associateBy { it.spec } }
        val pos = DoubleArray(2)
        var d = 0.0
        val step = maxOf(10.0, p.lengthM / 400)
        while (d <= p.lengthM) {
            p.at(d, pos)
            val fold = if (p.loop && d >= p.lengthM) 0.0 else d
            val under = byLevel[0][TileSpec.at(0, pos[0], pos[1])]
            assertTrue(under != null && under.neededAt(fold), "Keine Stufe-0-Kachel unter dem Fahrer bei $d m")
            // Sichtkreis in Polarraster.
            var r = 0.0
            while (r <= layout.shape.r2M) {
                for (k in 0 until 24) {
                    val a = 2 * PI * k / 24
                    val e = pos[0] + r * cos(a)
                    val n = pos[1] + r * sin(a)
                    val covered = (0..2).any { lv -> byLevel[lv][TileSpec.at(lv, e, n)]?.neededAt(fold) == true }
                    assertTrue(covered, "Loch bei $d m in ${r.toInt()} m Abstand")
                }
                r += 400.0
            }
            d += step
        }
        // Eltern-Regel: Stufe 1 deckt jede gezeigte Stufe-0-Kachel.
        for (c in byLevel[0].values) {
            val parent = byLevel[1][TileSpec(1, Math.floorDiv(c.spec.ix, 4), Math.floorDiv(c.spec.iy, 4))]!!
            for (iv in c.show) {
                assertTrue(parent.neededAt(iv.fromM) && parent.neededAt(iv.toM) && parent.neededAt((iv.fromM + iv.toM) / 2), "Eltern-Kachel fehlt fuer ${c.spec.id}")
            }
        }
    }

    private fun assertWellFormed(layout: TileLayout, p: PlanPath) {
        for (t in layout.plans) {
            assertTrue(t.show.isNotEmpty())
            for (iv in t.show) assertTrue(iv.fromM >= 0 && iv.toM <= p.lengthM && iv.fromM <= iv.toM, "${t.spec.id}: $iv")
            for (k in 1 until t.show.size) assertTrue(t.show[k - 1].toM < t.show[k].fromM, "${t.spec.id}: nicht zusammengefasst ${t.show}")
        }
    }

    @Test
    fun `lueckenlos fuer Gerade, Runde, Acht und Serpentine`() {
        for ((name, p) in listOf("Gerade" to straight, "Runde" to loop30, "Acht" to eight, "Serpentine" to serpentine)) {
            val layout = planTiles(p, IndoorQuality.MEDIUM)
            assertWellFormed(layout, p)
            assertGapless(layout, p)
            println("$name: ${layout.shape} passt=${layout.fits} L0=${layout.count(0)} L1=${layout.count(1)} L2=${layout.count(2)} schlimmst=${layout.worst}")
        }
    }

    @Test
    fun `Budget an jeder Stelle eingehalten, 30-km-Runde, alle Qualitaeten`() {
        for (q in IndoorQuality.entries) {
            val costs = TileCosts.upperBounds(q)
            val layout = planTiles(loop30, q, costs)
            assertTrue(layout.fits, "$q passt nicht: ${layout.shape} ${layout.worst}")
            assertTrue(layout.worst.maxTextureBytes <= q.maxTextureBytes)
            assertTrue(layout.worst.maxTriangles <= q.maxTriangles)
            // Unabhaengig nachgerechnet: alle 10 m.
            var d = 0.0
            var maxTex = 0L
            var maxTri = 0L
            while (d < loop30.lengthM) {
                var tex = 0L
                var tri = 0L
                for (t in layout.plans) if (t.neededAt(d)) {
                    tex += costs.textureBytes[t.spec.level]
                    tri += costs.triangles[t.spec.level]
                }
                maxTex = maxOf(maxTex, tex)
                maxTri = maxOf(maxTri, tri)
                d += 10.0
            }
            assertTrue(maxTex <= layout.worst.maxTextureBytes && maxTri <= layout.worst.maxTriangles)
            assertGapless(layout, loop30)
            val c = (0..2).map { layout.maxConcurrent(it, loop30.lengthM, true) }
            println(
                "$q: ${layout.shape} Kacheln gleichzeitig L0/L1/L2=$c, " +
                    "Textur ${layout.worst.maxTextureBytes / 1_048_576} MiB von ${q.maxTextureBytes / 1_048_576}, " +
                    "Dreiecke ${layout.worst.maxTriangles} von ${q.maxTriangles}; Kacheln gesamt ${layout.plans.size}",
            )
        }
    }

    @Test
    fun `Runde teilt Bereiche ueber den Start hinweg`() {
        val layout = planTiles(loop30, IndoorQuality.MEDIUM)
        val pos = DoubleArray(2)
        loop30.at(0.0, pos)
        val start = layout.plans.first { it.spec == TileSpec.at(0, pos[0], pos[1]) }
        assertTrue(start.show.first().fromM == 0.0 && start.show.last().toM == loop30.lengthM, "${start.show}")
        // Vorwaerts kurz vor Rundenende werden die Kacheln am Start gebraucht.
        assertTrue(start.neededAt(loop30.lengthM - 100.0))
    }

    @Test
    fun `worstCase rechnet exakt ueber Bereichsgrenzen`() {
        val a = TilePlan(TileSpec(0, 0, 0), listOf(ShowInterval(13.0, 17.0)))
        val b = TilePlan(TileSpec(0, 1, 0), listOf(ShowInterval(15.0, 30.0)))
        val c = TilePlan(TileSpec(0, 2, 0), listOf(ShowInterval(17.0, 20.0)))
        val w = worstCase(listOf(a, b, c), 100.0, false, { 10L }, { 1 })
        // Bei 17 m sind alle drei gleichzeitig noetig (geschlossene Bereiche).
        assertEquals(3, w.maxTriangles)
        assertEquals(30L, w.maxTextureBytes)
    }

    @Test
    fun `Bereiche werden geteilt, geklemmt und zusammengefasst`() {
        val loop = normalizeIntervals(listOf(doubleArrayOf(-50.0, 30.0), doubleArrayOf(20.0, 60.0), doubleArrayOf(950.0, 1020.0)), 1000.0, true)
        assertEquals(2, loop.size)
        assertEquals(ShowInterval(0.0, 60.0), loop[0])
        assertEquals(ShowInterval(950.0, 1000.0), loop[1])
        val open = normalizeIntervals(listOf(doubleArrayOf(-50.0, 30.0), doubleArrayOf(990.0, 1020.0)), 1000.0, false)
        assertEquals(listOf(ShowInterval(0.0, 30.0), ShowInterval(990.0, 1000.0)), open)
        assertEquals(listOf(ShowInterval(0.0, 1000.0)), normalizeIntervals(listOf(doubleArrayOf(-10.0, 1500.0)), 1000.0, true))
    }

    @Test
    fun `Welt-ID ist stabil und empfindlich`() {
        val route = TerrainTestSupport.routeFromEnu(TerrainTestSupport.circle(0.0, 0.0, 800.0), true)
        val id = worldIdOf(route, IndoorQuality.MEDIUM)
        assertEquals(id, worldIdOf(route.copy(id = "anders", name = "Anders"), IndoorQuality.MEDIUM))
        assertEquals(id, worldIdOf(TerrainTestSupport.routeFromEnu(TerrainTestSupport.circle(0.0, 0.0, 800.0), true), IndoorQuality.MEDIUM))
        assertNotEquals(id, worldIdOf(route, IndoorQuality.HIGH))
        val moved = route.copy(points = route.points.mapIndexed { i, p -> if (i == 40) p.copy(lat = p.lat + 2e-6) else p })
        assertNotEquals(id, worldIdOf(moved, IndoorQuality.MEDIUM))
        assertNotEquals(id, worldIdOf(route.copy(isLoop = false), IndoorQuality.MEDIUM))
        val surf = route.copy(surfaces = listOf(route.surfaces[0].copy(kind = de.trailscape.core.indoor.SurfaceKind.GRAVEL)))
        assertNotEquals(id, worldIdOf(surf, IndoorQuality.MEDIUM))
        assertTrue(id.matches(Regex("w[0-9a-f]{24}")))
    }

    @Test
    fun `Voranschlag zaehlt Anfragen je Quelle`() {
        val route = TerrainTestSupport.routeFromEnu(TerrainTestSupport.circle(0.0, 0.0, 1500.0), true)
        val layout = planTiles(PlanPath.of(route, o), IndoorQuality.MEDIUM)
        val all = estimatePlan(layout.plans, o, IndoorQuality.MEDIUM) { _, _ -> true }
        val none = estimatePlan(layout.plans, o, IndoorQuality.MEDIUM) { _, _ -> false }
        assertEquals(layout.plans.size, all.requests[GeoSourceKind.AERIAL])
        assertEquals(0, none.requests[GeoSourceKind.AERIAL])
        assertTrue(all.requests[GeoSourceKind.ELEVATION]!! >= 4, "${all.requests}")
        assertTrue(all.requests[GeoSourceKind.VECTOR]!! >= 10, "${all.requests}")
        assertTrue(all.downloadBytes > none.downloadBytes)
        assertTrue(all.diskBytes > 0)
        println("Voranschlag 9,4-km-Runde: ${all.requests}, ${all.downloadBytes / 1_048_576} MiB Download, ${all.diskBytes / 1_048_576} MiB Platte")
    }

    @Test
    fun `Mercator-Kachelnummern stimmen`() {
        assertEquals(8192, lonToTileX(0.0, 14))
        assertEquals(8192, latToTileY(0.0, 14))
        assertEquals(8508, lonToTileX(6.958, 14))
        assertTrue(latToTileY(50.941, 14) in 5485..5495)
        assertTrue(latToTileY(51.0, 14) < latToTileY(50.9, 14))
    }
}
