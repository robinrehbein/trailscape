package de.trailscape.core.indoor.world

import de.trailscape.core.indoor.SurfaceKind
import kotlin.math.abs
import kotlin.test.Test
import org.junit.jupiter.api.Timeout
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Harte Zeitgrenze je Test, damit ein Fehler nie die Gradle-Sperre haelt. */
@Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class VectorDataTest {
    private val e = VECTOR_GRID_EXTENT.toDouble()
    private val rectA = PxRect(0.0, 0.0, e, e)

    private fun area(rings: List<DoubleArray>): Double = rings.sumOf { ringArea2(it) } / 2

    @Test
    fun `Sutherland-Hodgman schneidet exakt auf das Rechteck`() {
        val ring = doubleArrayOf(-64.0, 100.0, 200.0, 100.0, 200.0, 300.0, -64.0, 300.0)
        val polys = clipRingsToRect(listOf(ring), rectA)
        assertEquals(1, polys.size)
        assertEquals(200.0 * 200.0, area(polys[0]), 1e-9)
        assertTrue(polys[0][0].filterIndexed { i, _ -> i % 2 == 0 }.all { it in 0.0..e })
        // ganz ausserhalb → nichts
        assertTrue(clipRingsToRect(listOf(doubleArrayOf(-60.0, 0.0, -10.0, 0.0, -10.0, 50.0)), rectA).isEmpty())
    }

    @Test
    fun `Stege eines konkaven Polygons am Rand werden entfernt`() {
        // U nach links offen, beide Arme ragen ueber x = 0 hinaus: geschnitten zwei getrennte Stuecke
        val u = doubleArrayOf(-50.0, 0.0, 100.0, 0.0, 100.0, 300.0, -50.0, 300.0, -50.0, 200.0, 50.0, 200.0, 50.0, 100.0, -50.0, 100.0)
        assertTrue(ringArea2(u) > 0)
        val polys = clipRingsToRect(listOf(u), PxRect(0.0, -10.0, e, e))
        assertEquals(1, polys.size, "Basis und Arme haengen ueber x = 50..100 zusammen")
        // anderes U: die Arme liegen rechts der Grenze in der Nachbarkachel
        val rectB = PxRect(e, 0.0, 2 * e, e)
        val shifted = DoubleArray(u.size) { if (it % 2 == 0) e - 60 + (100 - u[it]) else u[it] }.let { reverseRing(it) }
        val arms = clipRingsToRect(listOf(shifted), rectB)
        // Sutherland–Hodgman allein liefert einen Ring, dessen Steg entlang x = e die Arme verbindet
        assertEquals(2 * 90.0 * 100.0, ringArea2(clipRingToRect(shifted, rectB)) / 2, 1e-9)
        assertEquals(2, arms.size, "zwei Arme ohne Steg entlang der Grenze")
        assertEquals(2 * 90.0 * 100.0, arms.sumOf { area(it) }, 1e-9)
        assertTrue(arms.all { it[0].size / 2 == 4 })
    }

    private fun reverseRing(r: DoubleArray): DoubleArray {
        val n = r.size / 2
        return DoubleArray(r.size) { i -> val k = n - 1 - i / 2; if (i % 2 == 0) r[2 * k] else r[2 * k + 1] }
    }

    @Test
    fun `Loch am Rand wird zur Einbuchtung`() {
        val outer = doubleArrayOf(-64.0, 0.0, 400.0, 0.0, 400.0, 400.0, -64.0, 400.0)
        val hole = doubleArrayOf(-30.0, 100.0, -30.0, 300.0, 100.0, 300.0, 100.0, 100.0)
        assertTrue(ringArea2(hole) < 0)
        val polys = clipRingsToRect(listOf(outer, hole), rectA)
        assertEquals(1, polys.size)
        assertEquals(1, polys[0].size, "kein Loch mehr, nur ein Aussenring")
        assertEquals(400.0 * 400.0 - 100.0 * 200.0, area(polys[0]), 1e-9)
    }

    @Test
    fun `Liang-Barsky schneidet Linienzuege`() {
        val line = doubleArrayOf(-100.0, 10.0, 100.0, 10.0, 100.0, -50.0, 200.0, -50.0, 200.0, 20.0)
        val parts = clipPolylineToRect(line, rectA)
        assertEquals(2, parts.size)
        assertContentEquals(doubleArrayOf(0.0, 10.0, 100.0, 10.0, 100.0, 0.0), parts[0])
        assertContentEquals(doubleArrayOf(200.0, 0.0, 200.0, 20.0), parts[1])
        assertTrue(clipPolylineToRect(doubleArrayOf(-5.0, -5.0, -1.0, -1.0), rectA).isEmpty())
    }

    private val origin = GeoOrigin(TileMath.tileYToLat(5481.0, 14), TileMath.tileXToLon(8517.0, 14), 0.0)
    private val house = mapOf<String, Any>("render_height" to 5L, "render_min_height" to 0L)

    @Test
    fun `Gebaeude ueber die Kachelgrenze wird wieder ein Stueck`() {
        // Global: x 4000..4196 (relativ zu Kachel 8516), y 1000..1200; Puffer 64
        val a = tileOf(14, 8516, 5481, "building" to listOf(feature(MvtGeomType.POLYGON, house, rectRing(4000, 1000, 4160, 1200))))
        val b = tileOf(14, 8517, 5481, "building" to listOf(feature(MvtGeomType.POLYGON, house, rectRing(-64, 1000, 100, 1200))))
        val idx = VectorIndex(origin, listOf(a, b))
        val bs = idx.all("building")
        assertEquals(1, bs.size)
        assertEquals(1, bs[0].parts.size)
        assertEquals(4, bs[0].outer.size / 2, "Rechteck ohne Zwischenpunkte auf der Grenze")
        assertEquals(2, bs[0].tiles.size)
        assertTrue(ringArea2(bs[0].outer) > 0, "aussen gegen den Uhrzeigersinn in ENU")
        assertTrue(bs[0].minE < 0 && bs[0].maxE > 0, "liegt ueber der Grenze e = 0")
        // ohne Vereinigung: zwei aneinanderstossende Teile ohne Ueberlappung
        val plain = VectorIndex(origin, listOf(a, b), mergedPolygonLayers = emptySet()).all("building")
        assertEquals(2, plain.size)
        assertEquals(0.0, plain.minOf { it.maxE }, 1e-6)
        assertEquals(0.0, plain.maxOf { it.minE }, 1e-6)
        val total = plain.sumOf { ringArea2(it.outer) }
        assertEquals(total, ringArea2(bs[0].outer), total * 1e-9)
        // verschiedene Merkmale werden nicht vereinigt
        val c = tileOf(14, 8517, 5481, "building" to listOf(feature(MvtGeomType.POLYGON, house + ("render_height" to 9L), rectRing(-64, 1000, 100, 1200))))
        assertEquals(2, VectorIndex(origin, listOf(a, c)).all("building").size)
    }

    @Test
    fun `U-Gebaeude mit zwei Armen ueber die Grenze wird ein Stueck`() {
        // Basis in Kachel A (x 3900..4000), Arme bis x = 4196 in Kachel B
        val uA = intArrayOf(3900, 0 + 500, 4160, 500, 4160, 600, 4000, 600, 4000, 700, 4160, 700, 4160, 800, 3900, 800)
        val uB = IntArray(uA.size) { if (it % 2 == 0) uA[it] - 4096 else uA[it] }.also { arr ->
            for (i in arr.indices step 2) if (arr[i] == 4160 - 4096) arr[i] = 100
        }
        val tA = tileOf(14, 8516, 5481, "building" to listOf(feature(MvtGeomType.POLYGON, house, uA)))
        val tB = tileOf(14, 8517, 5481, "building" to listOf(feature(MvtGeomType.POLYGON, house, uB)))
        assertTrue(ringArea2(uA) > 0)
        val bs = VectorIndex(origin, listOf(tA, tB)).all("building")
        assertEquals(1, bs.size)
        assertEquals(8, bs[0].outer.size / 2)
    }

    @Test
    fun `Wege werden ueber Kachelgrenzen verkettet`() {
        val track = mapOf<String, Any>("class" to "track", "surface" to "unpaved")
        val a = tileOf(14, 8516, 5481, "transportation" to listOf(feature(MvtGeomType.LINESTRING, track, intArrayOf(1000, 500, 4160, 500))))
        val b = tileOf(14, 8517, 5481, "transportation" to listOf(feature(MvtGeomType.LINESTRING, track, intArrayOf(3000, 500, -64, 500))))
        val idx = VectorIndex(origin, listOf(a, b))
        val roads = idx.roads(-1e9, -1e9, 1e9, 1e9)
        assertEquals(1, roads.size)
        assertEquals(SurfaceKind.GRAVEL, roads[0].surfaceKind)
        val pts = roads[0].points
        val es = pts.filterIndexed { i, _ -> i % 2 == 0 }
        assertTrue(es.first() < -1000 && es.last() > 1000 || es.first() > 1000 && es.last() < -1000)
        // Einbahn in Gegenrichtung wird nicht verbunden
        val one = track + ("oneway" to 1L)
        val a1 = tileOf(14, 8516, 5481, "transportation" to listOf(feature(MvtGeomType.LINESTRING, one, intArrayOf(1000, 500, 4160, 500))))
        val b1 = tileOf(14, 8517, 5481, "transportation" to listOf(feature(MvtGeomType.LINESTRING, one, intArrayOf(3000, 500, -64, 500))))
        assertEquals(2, VectorIndex(origin, listOf(a1, b1)).all("transportation").size)
        val b2 = tileOf(14, 8517, 5481, "transportation" to listOf(feature(MvtGeomType.LINESTRING, one, intArrayOf(-64, 500, 3000, 500))))
        assertEquals(1, VectorIndex(origin, listOf(a1, b2)).all("transportation").size)
        // andere Klasse wird nicht verbunden
        val b3 = tileOf(14, 8517, 5481, "transportation" to listOf(feature(MvtGeomType.LINESTRING, mapOf("class" to "path"), intArrayOf(-64, 500, 3000, 500))))
        assertEquals(2, VectorIndex(origin, listOf(a, b3)).all("transportation").size)
    }

    @Test
    fun `Pufferpunkte gehoeren nur einer Kachel`() {
        val peak = mapOf<String, Any>("class" to "peak", "ele" to 312L, "name" to "Hoher Berg")
        val a = tileOf(14, 8516, 5481, "mountain_peak" to listOf(feature(MvtGeomType.POINT, peak, intArrayOf(4120, 10))))
        val b = tileOf(14, 8517, 5481, "mountain_peak" to listOf(feature(MvtGeomType.POINT, peak, intArrayOf(24, 10))))
        val peaks = VectorIndex(origin, listOf(a, b)).places(LAYER_MOUNTAIN_PEAK, -1e9, -1e9, 1e9, 1e9)
        assertEquals(1, peaks.size)
        assertEquals("Hoher Berg", peaks[0].name)
        assertEquals(312.0, peaks[0].eleM)
    }

    @Test
    fun `echte Kacheln Altenberg - Gebaeude ueber die Grenze vereinigt, Flaechen erhalten`() {
        val t80 = VectorTileData(TileId(14, 8516, 5480), decodeMvt(resourceBytes("vector_14_8516_5480.pbf")))
        val t81 = VectorTileData(TileId(14, 8516, 5481), decodeMvt(resourceBytes("vector_14_8516_5481.pbf")))
        val o = GeoOrigin(51.055, 7.133, 100.0)
        val merged = VectorIndex(o, listOf(t80, t81))
        val plain = VectorIndex(o, listOf(t80, t81), mergedPolygonLayers = emptySet(), chainedLineLayers = emptySet())
        assertTrue(merged.diagnostics.isEmpty(), merged.diagnostics.toString())
        val mb = merged.all(LAYER_BUILDING)
        val pb = plain.all(LAYER_BUILDING)
        assertTrue(mb.size < pb.size, "vereinigt ${mb.size}, Teile ${pb.size}")
        assertTrue(mb.size in 900..1400, "Gebaeude: ${mb.size}")
        val borderN = o.toNorth(TileMath.tileYToLat(5481.0, 14))
        val across = mb.filter { it.tiles.size == 2 }
        assertTrue(across.isNotEmpty())
        assertTrue(across.all { it.minN < borderN && it.maxN > borderN })
        // Flaeche bleibt erhalten (Vereinigung verschiebt nur Grenzkanten)
        val am = mb.sumOf { b -> b.parts.sumOf { ringArea2(it) } }
        val ap = pb.sumOf { b -> b.parts.sumOf { ringArea2(it) } }
        assertEquals(ap, am, ap * 1e-6)
        // typisierte Abfrage im Umkreis von 150 m um den Altenberger Dom
        val dE = o.toEast(7.1325)
        val dN = o.toNorth(51.0547)
        val near = merged.buildings(dE - 150, dN - 150, dE + 150, dN + 150)
        assertTrue(near.size >= 5, "Gebaeude am Dom: ${near.size}")
        assertTrue(near.all { it.heightM >= 3.0 })
        val classes = merged.areas(LAYER_LANDCOVER, -1e9, -1e9, 1e9, 1e9).mapNotNull { it.cls }.toSet()
        assertTrue(classes.containsAll(listOf("wood", "grass", "farmland")), classes.toString())
        // Wege: Verkettung verringert die Zahl der Stuecke
        assertTrue(merged.all(LAYER_TRANSPORTATION).size < plain.all(LAYER_TRANSPORTATION).size)
        assertTrue(merged.waterways(-1e9, -1e9, 1e9, 1e9).any { it.cls == "stream" })
        // alle Gebaeude liegen innerhalb der beiden Kacheln
        val box = TileMath.tileBounds(TileId(14, 8516, 5480))
        val maxN = o.toNorth(box.maxLat)
        assertTrue(mb.all { it.maxN <= maxN + 1e-6 })
        assertTrue(abs(merged.zoom - 14) == 0)
    }
}
