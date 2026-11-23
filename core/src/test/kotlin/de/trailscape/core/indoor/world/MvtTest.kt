package de.trailscape.core.indoor.world

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import org.junit.jupiter.api.Timeout
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Harte Zeitgrenze je Test, damit ein Fehler nie die Gradle-Sperre haelt. */
@Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class MvtTest {
    /** Eine Kachel mit einer Ebene „test": Punkt, Linie, Polygon mit Loch; Werte aller Typen. */
    private fun handBuiltTile(): ByteArray {
        val values = listOf(
            ProtoWriter().string(1, "wood").toByteArray(),
            ProtoWriter().fixed32(2, java.lang.Float.floatToIntBits(2.5f)).toByteArray(),
            ProtoWriter().fixed64(3, java.lang.Double.doubleToLongBits(12.25)).toByteArray(),
            ProtoWriter().uint(4, 300).toByteArray(),
            ProtoWriter().uint(5, 7).toByteArray(),
            ProtoWriter().uint(6, zz(-3)).toByteArray(),
            ProtoWriter().uint(7, 1).toByteArray(),
        )
        val point = ProtoWriter().uint(1, 42).packed(2, listOf(0, 0, 1, 1, 2, 2, 3, 3)).uint(3, 1)
            .packed(4, listOf(cmd(1, 1), zz(25), zz(17))).toByteArray()
        val line = ProtoWriter().packed(2, listOf(4, 4, 5, 5, 6, 6)).uint(3, 2)
            .packed(4, listOf(cmd(1, 1), zz(2), zz(2), cmd(2, 2), zz(0), zz(8), zz(8), zz(0))).toByteArray()
        val poly = ProtoWriter().uint(3, 3)
            .packed(4, encodeGeometry(listOf(rectRing(0, 0, 100, 100), intArrayOf(20, 20, 20, 80, 80, 80, 80, 20)), true))
            .toByteArray()
        val layer = ProtoWriter().uint(15, 2).string(1, "test")
            .bytes(2, point).bytes(2, line).bytes(2, poly)
            .string(3, "class").string(3, "f").string(3, "d").string(3, "i").string(3, "u").string(3, "s").string(3, "b")
        values.forEach { layer.bytes(4, it) }
        layer.uint(5, 4096)
        // unbekanntes Feld 99 (Laengenfeld) muss uebersprungen werden
        layer.string(99, "egal")
        return ProtoWriter().bytes(3, layer.toByteArray()).uint(77, 5).toByteArray()
    }

    @Test
    fun `Varint, Zickzack und Geometriebefehle`() {
        assertEquals(listOf(0L, 1L, 2L, 3L), listOf(0, -1, 1, -2).map { zz(it) })
        val parts = decodeGeometry(longArrayOf(cmd(1, 1), zz(3), zz(6), cmd(2, 2), zz(5), zz(0), zz(-2), zz(-4)), MvtGeomType.LINESTRING)
        assertEquals(1, parts.size)
        assertContentEquals(intArrayOf(3, 6, 8, 6, 6, 2), parts[0])
        // MultiPoint: ein MoveTo mit zwei Punkten
        val pts = decodeGeometry(longArrayOf(cmd(1, 2), zz(5), zz(7), zz(3), zz(2)), MvtGeomType.POINT)
        assertEquals(2, pts.size)
        assertContentEquals(intArrayOf(8, 9), pts[1])
        // Befehl verlangt mehr Werte als vorhanden → Abbruch ohne Ausnahme
        assertTrue(decodeGeometry(longArrayOf(cmd(2, 1000000)), MvtGeomType.LINESTRING).isEmpty())
    }

    @Test
    fun `handgebaute Kachel mit allen Werttypen`() {
        val tile = decodeMvt(handBuiltTile())
        assertTrue(tile.ok, tile.diagnostics.toString())
        val layer = assertNotNull(tile.layer("test"))
        assertEquals(4096, layer.extent)
        assertEquals(2, layer.version)
        val (p, l, poly) = layer.features
        assertEquals(42L, p.id)
        assertEquals(MvtGeomType.POINT, p.type)
        assertEquals("wood", p.str("class"))
        assertEquals(2.5, p.num("f"))
        assertEquals(12.25, p.num("d"))
        assertEquals(300.0, p.num("i"))
        assertContentEquals(intArrayOf(25, 17), p.parts[0])
        assertEquals(7L, l.tags["u"])
        assertEquals(-3L, l.tags["s"])
        assertEquals(true, l.bool("b"))
        assertContentEquals(intArrayOf(2, 2, 2, 10, 10, 10), l.parts[0])
        val polys = poly.polygons()
        assertEquals(1, polys.size)
        assertEquals(1, polys[0].holes.size)
        assertTrue(ringArea2(polys[0].outer) > 0)
        assertTrue(ringArea2(polys[0].holes[0]) < 0)
    }

    @Test
    fun `gzip wird erkannt und entpackt`() {
        val raw = handBuiltTile()
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(raw) }
        val gz = bos.toByteArray()
        assertTrue(isGzip(gz))
        assertFalse(isGzip(raw))
        val tile = decodeMvt(gz)
        assertTrue(tile.ok)
        assertEquals(3, tile.layer("test")!!.features.size)
        // kaputtes gzip → leer mit Diagnose
        val broken = decodeMvt(gz.copyOf(gz.size / 2))
        assertTrue(broken.layers.isEmpty())
        assertFalse(broken.ok)
    }

    @Test
    fun `kaputte Bytes ergeben Diagnose statt Ausnahme`() {
        val raw = handBuiltTile()
        // abgeschnitten
        val cut = decodeMvt(raw.copyOf(raw.size - 7))
        assertFalse(cut.ok)
        // absurde Laenge (Feld 3, Laenge 2^40)
        val absurd = ProtoWriter().tag(3, 2).varint(1L shl 40).toByteArray()
        assertFalse(decodeMvt(absurd).ok)
        // endloser Varint
        assertFalse(decodeMvt(ByteArray(20) { 0xFF.toByte() }).ok)
        // Zufallsbytes laufen durch
        val rnd = java.util.Random(7)
        repeat(200) {
            val b = ByteArray(rnd.nextInt(300)).also { rnd.nextBytes(it) }
            decodeMvt(b)
        }
        // leere Kachel ist gueltig
        assertTrue(decodeMvt(ByteArray(0)).ok)
    }

    @Test
    fun `echte OpenFreeMap-Kachel Altenberg`() {
        val tile = decodeMvt(resourceBytes("vector_14_8516_5480.pbf"))
        assertTrue(tile.ok, tile.diagnostics.toString())
        for (name in listOf("building", "landcover", "landuse", "park", "water", "waterway", "transportation", "place", "poi")) {
            assertNotNull(tile.layer(name), "Ebene $name fehlt")
        }
        val buildings = tile.layer("building")!!
        assertEquals(4096, buildings.extent)
        val polys = buildings.features.flatMap { it.polygons() }
        assertTrue(polys.size in 900..1300, "Gebaeude: ${polys.size}")
        val heights = buildings.features.mapNotNull { it.num("render_height") }.toSet()
        assertTrue(heights.isNotEmpty() && heights.all { it in 3.0..60.0 }, heights.toString())
        val classes = tile.layer("landcover")!!.features.mapNotNull { it.str("class") }.toSet()
        assertTrue("wood" in classes && "grass" in classes, classes.toString())
        val roads = tile.layer("transportation")!!.features
        assertTrue(roads.any { it.str("class") == "track" })
        assertTrue(roads.any { it.str("surface") == "unpaved" })
        // Puffer: Koordinaten reichen bis 64 ueber den Rand
        val xs = buildings.features.flatMap { f -> f.parts.flatMap { p -> p.filterIndexed { i, _ -> i % 2 == 0 } } }
        assertTrue(xs.min() >= -64 && xs.max() <= 4160)
    }
}
