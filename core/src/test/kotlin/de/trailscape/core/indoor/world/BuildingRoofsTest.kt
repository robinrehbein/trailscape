package de.trailscape.core.indoor.world

import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BuildingRoofsTest {
    private fun square(vararg rings: DoubleArray): SquaredFootprint? {
        val outer = rings[0]
        val angle = FootprintShape.dominantAngle(outer)
        val c = FootprintShape.centroid(outer)
        var f = rings.map { FootprintShape.toFrame(it, c[0], c[1], angle) }
        if (ringArea(f[0]) < 0) f = listOf(FootprintShape.reversed(f[0])) + f.drop(1)
        return FootprintShape.square(f)
    }

    private fun isRectilinear(r: DoubleArray): Boolean {
        val n = r.size / 2
        return (0 until n).all { i ->
            val j = (i + 1) % n
            abs(r[2 * i] - r[2 * j]) < 1e-9 || abs(r[2 * i + 1] - r[2 * j + 1]) < 1e-9
        }
    }

    @Test
    fun `schiefe Rechtecke aus den Kacheln werden rechtwinklig`() {
        var rects = 0
        for (sh in BuildingsVillage.shapes.filter { it.ring.size == 8 }) {
            val turned = BuildingsVillage.place(sh.ring, 300.0, -120.0, 0.6)
            val sq = assertNotNull(square(turned), "Rechteck nicht ausgerichtet")
            val r = sq.rings.single()
            assertEquals(8, r.size)
            assertTrue(isRectilinear(r))
            assertTrue(ringArea(r) > 0, "Aussenring nicht gegen den Uhrzeigersinn")
            assertEquals(abs(ringArea(sh.ring)), ringArea(r), 0.12 * abs(ringArea(sh.ring)))
            rects++
        }
        assertTrue(rects >= 20)
    }

    @Test
    fun `die meisten echten Grundrisse lassen sich ausrichten und keiner wandert weit`() {
        var ok = 0
        for (sh in BuildingsVillage.shapes) {
            val sq = square(sh.ring) ?: continue
            ok++
            val r = sq.rings[0]
            assertTrue(isRectilinear(r) && FootprintShape.isSimple(r))
            val angle = FootprintShape.dominantAngle(sh.ring)
            val c = FootprintShape.centroid(sh.ring)
            val f = FootprintShape.toFrame(sh.ring, c[0], c[1], angle)
            for (i in 0 until f.size / 2) assertTrue(FootprintShape.distanceToRing(r, f[2 * i], f[2 * i + 1]) <= 1.6 + 1e-9)
            // Keine Kante kuerzer als die Schwelle, keine fast gleichen Fluchten.
            for (i in 0 until r.size / 2) {
                val j = (i + 1) % (r.size / 2)
                assertTrue(abs(r[2 * i] - r[2 * j]) + abs(r[2 * i + 1] - r[2 * j + 1]) >= 0.34, "Kante zu kurz")
            }
        }
        assertTrue(ok >= BuildingsVillage.shapes.size - 6, "nur $ok von ${BuildingsVillage.shapes.size} ausgerichtet")
        // Kein Rechteckraster: Dreieck, Kreis und Schleife bleiben, wie sie sind.
        assertNull(square(doubleArrayOf(0.0, 0.0, 14.0, 0.0, 3.0, 9.0)))
        assertNull(square(doubleArrayOf(0.0, 0.0, 10.0, 8.0, 10.0, 0.0, 0.0, 8.0)))
    }

    private val l = doubleArrayOf(-7.5, -5.65, 1.9, -5.65, 1.9, -1.95, 7.5, -1.95, 7.5, 5.65, -7.5, 5.65)

    @Test
    fun `Winkelhaus - Hauptbau mit Querfluegel, an der buendigen Ecke gewalmt`() {
        val wings = assertNotNull(RoofWings.mainAndAnnexes(square(l)!!))
        assertEquals(2, wings.size)
        val main = wings[0]
        assertEquals(WingRole.MAIN, main.role)
        assertEquals(15.0, main.length, 1e-6)
        assertEquals(7.6, 2 * main.halfWidth, 1e-6)
        val cross = wings[1]
        assertEquals(WingRole.CROSS, cross.role)
        assertTrue(cross.alongX != main.alongX, "Querfluegel muss quer zum Hauptfirst laufen")
        // Reicht bis zur Firstlinie des Hauptbaus und steckt dort im Dach.
        assertTrue(cross.buriedLo != cross.buriedHi)
        assertEquals(3.7 + 3.8, cross.length, 1e-6)
        // Buendig mit einem Giebel des Hauptbaus: dort Walm, am anderen Ende Giebel.
        assertTrue(main.hipLo != main.hipHi)
    }

    @Test
    fun `kleine Vorbauten werden niedrige Anbauten und der Hauptbau behaelt beide Giebel`() {
        val bump = doubleArrayOf(-6.0, -3.5, -2.0, -3.5, -2.0, -5.0, 3.0, -5.0, 3.0, -3.5, 6.0, -3.5, 6.0, 3.5, -6.0, 3.5)
        val wings = assertNotNull(RoofWings.mainAndAnnexes(square(bump)!!))
        assertEquals(2, wings.size)
        assertTrue(!wings[0].hipLo && !wings[0].hipHi)
        val annex = wings[1]
        assertEquals(WingRole.ANNEX, annex.role)
        assertEquals(5.0, annex.length, 1e-6)
        assertEquals(1.5, 2 * annex.halfWidth, 1e-6)
        // Pultdach faellt vom Hauptbau weg: hohe Seite oben (zum Hauptbau).
        assertEquals(1, annex.pent)
        assertTrue(annex.open[2] && !annex.open[3] && annex.open[0] && annex.open[1])
    }

    @Test
    fun `Innenhof - Walmdach-Ring aus den groessten Rechtecken deckt den ganzen Grundriss`() {
        val fp = square(BuildingsVillage.courtyard[0], BuildingsVillage.courtyard[1])!!
        assertEquals(2, fp.rings.size)
        assertTrue(ringArea(fp.rings[1]) < 0, "Loch nicht im Uhrzeigersinn")
        assertNull(RoofWings.mainAndAnnexes(fp))
        val wings = assertNotNull(RoofWings.decompose(fp, gables = true))
        assertEquals(4, wings.size)
        assertTrue(wings.all { it.hipLo && it.hipHi }, "im Ring gibt es keine freien Enden")
        for (w in wings) w.tan = 0.7
        for (i in 0 until 2000) {
            val x = -14 + 28 * Noise.hash01(i, 1, 5)
            val y = -11 + 22 * Noise.hash01(i, 2, 5)
            if (!pointInRings(fp.rings, x, y)) {
                // Kein Fluegel ragt in den Hof oder ueber den Rand.
                assertTrue(wings.none { it.contains(x, y, -1e-6) }, "Dach ueber ($x, $y) ausserhalb")
                continue
            }
            val h = wings.filter { it.contains(x, y) }.maxOf { it.topAt(x, y) }
            // Walmdach = Abstand zum Rand in der Maximumsnorm mal Neigung.
            val dOuter = minOf(x - fp.rings[0].minOf(0), fp.rings[0].maxOf(0) - x, y - fp.rings[0].minOf(1), fp.rings[0].maxOf(1) - y)
            val hx = maxOf(fp.rings[1].minOf(0) - x, x - fp.rings[1].maxOf(0))
            val hy = maxOf(fp.rings[1].minOf(1) - y, y - fp.rings[1].maxOf(1))
            val dHole = maxOf(hx, hy)
            assertEquals(minOf(dOuter, dHole) * 0.7, h, 1e-6)
        }
    }

    private fun DoubleArray.minOf(axis: Int) = (0 until size / 2).minOf { this[2 * it + axis] }
    private fun DoubleArray.maxOf(axis: Int) = (0 until size / 2).maxOf { this[2 * it + axis] }

    /** Jede Kante der Flaechen gehoert zu genau zwei Flaechen in gegenlaeufiger Richtung. */
    private fun assertClosed(faces: List<RoofFace>, what: String) {
        val count = HashMap<String, IntArray>()
        fun key(p: DoubleArray, i: Int) = "${(p[3 * i] * 1000).roundToLong()}/${(p[3 * i + 1] * 1000).roundToLong()}/${(p[3 * i + 2] * 1000).roundToLong()}"
        for (f in faces) {
            val n = f.pts.size / 3
            // Umlaufsinn an der verlangten Normalen ausrichten (Newell).
            var ax = 0.0
            var ay = 0.0
            var az = 0.0
            for (i in 0 until n) {
                val j = (i + 1) % n
                ax += (f.pts[3 * i + 1] - f.pts[3 * j + 1]) * (f.pts[3 * i + 2] + f.pts[3 * j + 2])
                ay += (f.pts[3 * i + 2] - f.pts[3 * j + 2]) * (f.pts[3 * i] + f.pts[3 * j])
                az += (f.pts[3 * i] - f.pts[3 * j]) * (f.pts[3 * i + 1] + f.pts[3 * j + 1])
            }
            val flip = ax * f.nx + ay * f.ny + az * f.nz < 0
            for (i in 0 until n) {
                val a = key(f.pts, if (flip) (i + 1) % n else i)
                val b = key(f.pts, if (flip) i else (i + 1) % n)
                if (a == b) continue
                val fwd = a < b
                count.getOrPut(if (fwd) "$a>$b" else "$b>$a") { IntArray(2) }[if (fwd) 0 else 1]++
            }
        }
        val open = count.filter { it.value[0] != 1 || it.value[1] != 1 }
        assertTrue(open.isEmpty(), "$what: offene Kanten ${open.keys.take(3)}")
    }

    @Test
    fun `Dachkoerper sind geschlossen - Sattel, Walm, Krueppel, Zelt und Pult`() {
        fun wing(w: Double, h: Double, hipLo: Boolean, hipHi: Boolean, pent: Int = 0) =
            RoofWing(-w / 2, -h / 2, w / 2, h / 2, alongX = w >= h, hipLo = hipLo, hipHi = hipHi, pent = pent).also { it.tan = 0.8 }
        assertClosed(RoofWings.faces(wing(12.0, 8.0, false, false), 0.45, 0.3, 0.2), "Sattel")
        assertClosed(RoofWings.faces(wing(12.0, 8.0, true, true), 0.45, 0.3, 0.2), "Walm")
        assertClosed(RoofWings.faces(wing(12.0, 8.0, true, false), 0.45, 0.3, 0.2), "halb gewalmt")
        assertClosed(RoofWings.faces(wing(8.0, 8.0, true, true), 0.45, 0.3, 0.2), "Zelt")
        assertClosed(RoofWings.faces(wing(6.0, 12.0, false, false), 0.45, 0.3, 0.2), "Sattel quer")
        assertClosed(RoofWings.faces(wing(6.0, 4.0, false, false, pent = 1), 0.2, 0.15, 0.14), "Pult")
        assertClosed(RoofWings.faces(wing(6.0, 4.0, false, false, pent = -1), 0.2, 0.15, 0.14), "Pult andersherum")
        // Ohne Dicke nur die Dachhaut, buendig mit dem Rechteck.
        val skin = RoofWings.faces(wing(12.0, 8.0, false, true), 0.45, 0.3, 0.0)
        assertTrue(skin.all { it.kind == RoofFaceKind.TOP })
        assertEquals(3, skin.size)
        for (f in skin) for (i in 0 until f.pts.size / 3) {
            assertTrue(abs(f.pts[3 * i]) <= 6.0 + 1e-9 && abs(f.pts[3 * i + 1]) <= 4.0 + 1e-9)
            assertTrue(f.pts[3 * i + 2] in -1e-9..3.2 + 1e-9)
        }
        // Dachhaut zeigt nach oben und stimmt mit topAt ueberein.
        val w = wing(12.0, 8.0, true, false)
        for (f in RoofWings.faces(w, 0.45, 0.3, 0.2).filter { it.kind == RoofFaceKind.TOP }) {
            assertTrue(f.nz > 0)
            for (i in 0 until f.pts.size / 3) assertEquals(w.topAt(f.pts[3 * i], f.pts[3 * i + 1]), f.pts[3 * i + 2], 1e-9)
        }
    }

    @Test
    fun `Schwerpunkt, Huelle und Einfachheit`() {
        val c = FootprintShape.centroid(doubleArrayOf(1000.0, 2000.0, 1010.0, 2000.0, 1010.0, 2004.0, 1000.0, 2004.0))
        assertEquals(1005.0, c[0], 1e-9)
        assertEquals(2002.0, c[1], 1e-9)
        assertEquals(40.0, c[2], 1e-9)
        assertTrue(FootprintShape.isSimple(l))
        assertTrue(!FootprintShape.isSimple(doubleArrayOf(0.0, 0.0, 10.0, 8.0, 10.0, 0.0, 0.0, 8.0)))
        val hull = FootprintShape.convexHull(l)
        assertEquals(10, hull.size)
        assertTrue(ringArea(hull) > abs(ringArea(l)))
        assertNull(FootprintShape.clean(doubleArrayOf(0.0, 0.0, 5.0, 0.0, 10.0, 0.0)))
        assertEquals(8, FootprintShape.clean(doubleArrayOf(0.0, 0.0, 5.0, 0.0, 10.0, 0.0, 10.0, 0.01, 10.0, 6.0, 0.0, 6.0, 0.0, 0.0))!!.size)
    }
}
