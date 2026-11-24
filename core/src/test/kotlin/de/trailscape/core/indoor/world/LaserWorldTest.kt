package de.trailscape.core.indoor.world

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Datenstufe „Laser" ohne Netz: adaptives Gelaendenetz, Baumerkennung,
 * Polygon-Hilfen des LoD2-Baus und ein ganzer Weltbau mit kuenstlichen
 * DGM-/DOM-Rastern (kleiner TIFF-Schreiber unten), der die Pruefung besteht.
 */
class LaserWorldTest {

    // -----------------------------------------------------------------------
    // Adaptives Gelaendenetz
    // -----------------------------------------------------------------------

    /** Ebene mit Damm (2 m hoch, 1:1,5) quer durch die Kachel und einem Graben. */
    private val damm = GroundSurface { e, n ->
        val x = e - 100.0
        val dam = when {
            abs(x) < 4 -> 2.0
            abs(x) < 7 -> 2.0 * (7 - abs(x)) / 3
            else -> 0.0
        }
        val ditch = if (abs(n - 180.0) < 1.5) -0.8 * (1.5 - abs(n - 180.0)) / 1.5 else 0.0
        0.01 * n + dam + ditch
    }

    @Test
    fun rtinBleibtImBudgetUndHaeltBoeschungskanten() {
        val m = LaserTerrainMesh.build(damm, 0.0, 0.0, 4000) { _, _ -> false }
        assertTrue(m.triangles <= 4000, "Dreiecke ${m.triangles}")
        // Ein regelmaessiges 1-m-Gitter haette 131 072; die Ebene wird zu grossen Dreiecken.
        var tris = 0
        m.forEachTriangle { _, _, _ -> tris++ }
        assertTrue(tris < 3000, "Netz $tris")
        // Kanten des Damms: etwa die gewaehlte Schwelle daneben (der Fehler wird an den Mittelpunkten
        // der Hypotenusen gemessen, nicht an jedem Gitterpunkt — Martini-Heuristik, hier hoechstens das 1,6-Fache).
        fun worst(mesh: LaserTerrainMesh): Double {
            var w = 0.0
            for (j in 0..256 step 3) for (i in 90..110) {
                w = max(w, abs(mesh.heightAt(i.toDouble(), j.toDouble()) - damm.heightAt(i.toDouble(), j.toDouble())))
            }
            return w
        }
        assertTrue(worst(m) <= 1.6 * m.threshold + 0.01, "Abweichung am Damm ${worst(m)} (Schwelle ${m.threshold})")
        // Mehr Budget, kleinere Schwelle, genauerer Damm.
        val fine = LaserTerrainMesh.build(damm, 0.0, 0.0, 12000) { _, _ -> false }
        assertTrue(fine.threshold < m.threshold && worst(fine) < worst(m), "fein ${fine.threshold}/${worst(fine)}")
    }

    @Test
    fun rtinIstRissfreiUndUmlaufGegenUhrzeigersinn() {
        val m = LaserTerrainMesh.build(damm, 0.0, 0.0, 6000) { _, _ -> false }
        val edges = HashMap<Long, Int>()
        val g = LaserTerrainMesh.G
        var area = 0.0
        m.forEachTriangle { a, b, c ->
            val ax = a % g; val ay = a / g; val bx = b % g; val by = b / g; val cx = c % g; val cy = c / g
            val cross = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)
            assertTrue(cross > 0, "Umlauf")
            area += cross / 2.0
            for ((p, q) in listOf(a to b, b to c, c to a)) {
                // Gerichtete Kante: im rissfreien Netz kommt jede innere Kante genau einmal in jeder Richtung vor.
                edges[p.toLong() * g * g + q] = (edges[p.toLong() * g * g + q] ?: 0) + 1
            }
        }
        assertEquals(256.0 * 256.0, area, 1e-6, "Netz deckt die Kachel genau")
        for ((k, v) in edges) {
            assertEquals(1, v, "doppelte Kante")
            val p = (k / (g * g)).toInt()
            val q = (k % (g * g)).toInt()
            val reverse = edges.containsKey(q.toLong() * g * g + p)
            // T-Stoesse waeren Kanten ohne Gegenkante, die nicht auf dem Rand liegen.
            assertTrue(reverse || LaserTerrainMesh.onSameBorder(p, q), "offene Kante $p–$q")
        }
    }

    @Test
    fun rtinLiegtUnterDerFahrbahnNieUeberDemBoden() {
        // Fahrbahn als Mulde (Einschnitt) — dort wuerde ein grobes Dreieck von den Boeschungen her ueberstehen.
        val cut = GroundSurface { e, n ->
            val d = abs(n - 128.0)
            0.02 * e + when {
                d < 3 -> -0.25
                d < 5 -> -0.25 + (d - 3) * 1.2
                else -> 2.15 + 0.3 * sin(e / 9)
            }
        }
        val m = LaserTerrainMesh.build(cut, 0.0, 0.0, 1500) { _, n -> abs(n - 128.0) < 3.0 }
        for (i in 0..256) for (dn in -2..2) {
            val e = i.toDouble()
            val n = 128.0 + dn
            assertTrue(m.heightAt(e, n) - cut.heightAt(e, n) <= LaserTerrainMesh.ROUTE_MAX_RISE_M + 1e-6, "Netz ueber der Fahrbahn bei $e/$n")
        }
    }

    // -----------------------------------------------------------------------
    // Baumerkennung
    // -----------------------------------------------------------------------

    private fun laserWith(ndom: (Int, Int) -> Double): LaserData {
        val b = LaserBlock(0, 0)
        for (j in 0 until LaserBlock.N) for (i in 0 until LaserBlock.N) {
            val k = j * LaserBlock.N + i
            b.dgm[k] = 100f
            b.blended[k] = 100f
            b.weight[k] = 255.toByte()
            b.ndomQ[k] = (ndom(i, j) / 0.2).toInt().coerceIn(0, 255).toByte()
        }
        val stats = LaserStats(1, 0, 0, 0, 0, 0, 0, 0.0, 0.0)
        return LaserData(laserSources[0], GeoOrigin(51.0, 7.0, 0.0), mapOf(LaserData.key(0, 0) to b), 0.0, emptyList(), stats) { _, _ -> 99.0 }
    }

    @Test
    fun einzelbaeumeAusLokalenMaxima() {
        val trees = listOf(Triple(60.0, 60.0, 18.0), Triple(70.0, 62.0, 12.0), Triple(150.0, 120.0, 24.0))
        val laser = laserWith { x, y ->
            var h = 0.0
            for ((e, n, top) in trees) {
                val r = top * 0.25
                h = max(h, top * exp(-((x - e) * (x - e) + (y - n) * (y - n)) / (2 * r * r / 2.5)))
            }
            // Flachdach 6 m (muss als Gebaeude erkannt werden, nicht als Baum)
            if (x in 200..215 && y in 30..45) h = 6.0
            h
        }
        val found = laser.detectTrees(0.0, 0.0, 256.0, 256.0)
        for ((e, n, top) in trees) {
            val t = found.minByOrNull { hypot(it.e - e, it.n - n) }
            assertNotNull(t)
            assertTrue(hypot(t.e - e, t.n - n) <= 1.5, "Lage ${t.e}/${t.n} statt $e/$n")
            assertEquals(top, t.heightM, 0.8)
            assertTrue(t.crownRadiusM in 1.0..8.0, "Krone ${t.crownRadiusM}")
        }
        assertTrue(found.none { it.e in 199.0..216.0 && it.n in 29.0..46.0 }, "Flachdach als Baum: $found")
        assertEquals(trees.size, found.size, "keine Doppelspitzen: $found")
        // Kronenbedeckung und -hoehe
        assertTrue(laser.canopyAt(150.0, 120.0) > 0.5)
        assertTrue(laser.canopyAt(10.0, 230.0) == 0.0)
        assertEquals(laser.canopyHeightAt(10.0, 230.0).isNaN(), true)
    }

    @Test
    fun gebaeudeMaskeUndHoeheAusserhalbDesKorridors() {
        val laser = laserWith { x, y -> if (x in 20..30 && y in 20..30) 8.0 else 0.0 }
        laser.maskBuildings(listOf(doubleArrayOf(20.0, 20.0, 30.0, 20.0, 30.0, 30.0, 20.0, 30.0)), 1.0)
        assertEquals(0.0, laser.ndomAt(25, 25))
        // Ausserhalb der Bloecke: Terrarium + Versatz (hier 99 + 0)
        assertEquals(99.0, laser.heightAboveSea(-500.0, -500.0), 1e-9)
        assertEquals(100.0, laser.heightAboveSea(100.0, 100.0), 1e-6)
    }

    @Test
    fun baumartUndUeberhang() {
        val base = TreeModels.Spec(TreeModels.Species.BROAD, 10.0, 1.0, 1.0, 1, 0.0, false)
        // breite Krone → Laubbaum, Breite aus dem Radius
        val broad = laserTreeSpec(base, LaserTree(0.0, 0.0, 20.0, 6.4))!!
        assertEquals(TreeModels.Species.BROAD, broad.species)
        assertEquals(1.0, broad.width, 0.05)
        // schmale Krone im Nadelwald → Fichte, sonst Pappelform
        assertEquals(TreeModels.Species.CONIFER, laserTreeSpec(base, LaserTree(0.0, 0.0, 25.0, 2.5), conifer = 0.8)!!.species)
        assertEquals(TreeModels.Species.TALL, laserTreeSpec(base, LaserTree(0.0, 0.0, 25.0, 2.5), conifer = 0.1)!!.species)
        // Fichte direkt am Weg: Krone nicht ueber die Fahrbahn
        val c = laserTreeSpec(base, LaserTree(0.0, 0.0, 20.0, 2.0), roomM = 2.5, conifer = 0.9)!!
        assertTrue(0.22 * c.heightM * c.width <= 2.8 + 1e-9)
        assertNull(laserTreeSpec(base, LaserTree(0.0, 0.0, 30.0, 3.0), roomM = 0.2, conifer = 0.9))
        assertNull(laserTreeSpec(base, LaserTree(0.0, 0.0, 2.0, 1.0)))
    }

    // -----------------------------------------------------------------------
    // LoD2-Polygone
    // -----------------------------------------------------------------------

    @Test
    fun kollinearePunkteFallenWeg() {
        // Wand 10 m × 3 m mit Zwischenpunkten auf jedem Meter der Unterkante
        val pts = ArrayList<Double>()
        for (x in 0..10) pts.addAll(listOf(x.toDouble(), 0.0, 0.0))
        pts.addAll(listOf(10.0, 0.0, 3.0, 0.0, 0.0, 3.0))
        val s = Lod2Mesher.simplify(pts.toDoubleArray())
        assertEquals(4 * 3, s.size, s.toList().toString())
    }

    @Test
    fun streifenSchnittErhaeltDieFlaeche() {
        val ring = doubleArrayOf(0.0, 0.0, 0.0, 8.0, 0.0, 0.0, 8.0, 5.0, 3.0, 0.0, 5.0, 3.0)
        val dir = Vec3(0.0, 0.6, 0.8).normalized()
        var area = 0.0
        for (k in -2..10) {
            val piece = Lod2Mesher.clipSlab(ring, dir, k * 1.0, (k + 1) * 1.0)
            if (piece.size >= 9) area += Lod2Mesher.newell(piece).length()
        }
        assertEquals(Lod2Mesher.newell(ring).length(), area, 1e-6)
    }

    // -----------------------------------------------------------------------
    // Ganzer Bau ohne Netz
    // -----------------------------------------------------------------------

    @Test
    fun weltbauMitLaserBestehtDiePruefungUndLegtDasBandAufsDgm() {
        val quality = IndoorQuality.LOW
        val clock = { 1_700_000_000_000L }
        val fetcher = SyntheticLaser()
        val opts = LaserOptions(fetcher, sources = listOf(TEST_LASER), radiusM = 140.0, fadeM = 40.0, threads = 2)
        val laserBuilder = WorldBuilderImpl(FullWorldParts, aerialRegister = listOf(TEST_AERIAL), clock = clock, laser = opts)
        val plainBuilder = WorldBuilderImpl(FullWorldParts, aerialRegister = listOf(TEST_AERIAL), clock = clock)
        val route = shortRoute()
        val plan = laserBuilder.plan(route, quality)
        assertTrue("laser.example.org" in plan.hosts, "${plan.hosts}")
        assertTrue(plan.downloadBytes > plainBuilder.plan(route, quality).downloadBytes)
        assertEquals(0, fetcher.requests, "plan darf nichts abrufen")

        val dir = tempDir("welt-laser")
        val m = laserBuilder.build(plan, fakeDeps(FakeGeo()), dir, RecordingListener())
        assertEquals(emptyList(), checkWorld(dir))
        assertTrue(fetcher.requests > 0)
        assertTrue(m.attribution.any { it.text == TEST_LASER.attribution && it.license == TEST_LASER.license })
        assertTrue(m.budget.maxTriangles <= quality.maxTriangles)
        val laser = laserBuilder.lastLaser!!
        assertTrue(laser.blockCount > 0)
        // Das Band liegt auf dem DGM (bis auf die Glaettung): Mittelwert der Abweichung klein.
        var sum = 0.0
        var k = 0
        for (i in 10 until m.track.sampleCount - 10 step 7) {
            val e = m.track.e[i].toDouble()
            val n = m.track.n[i].toDouble()
            val dgm = laser.dgmAt(e, n)
            if (dgm.isNaN() || !laser.fullAt(e, n)) continue
            sum += abs(m.track.u[i] + m.origin.eleM - dgm)
            k++
        }
        assertTrue(k > 10)
        assertTrue(sum / k < 0.6, "Band ${sum / k} m neben dem DGM")

        // Keine neuen deckenden koplanaren Flaechen gegen den Bau ohne Laser
        val plainDir = tempDir("welt-ohne-laser")
        plainBuilder.build(plainBuilder.plan(route, quality), fakeDeps(FakeGeo()), plainDir, RecordingListener())
        fun opaque(d: File): Int {
            val mm = WorldManifest.parse(File(d, WorldManifest.FILE_NAME).readText())
            var c = 0
            for (t in mm.tiles.filter { it.level == 0 }) for ((key, v) in coplanarOverlaps(File(d, t.file).readBytes())) {
                if (listOf("_edge", "verge", "fringe", "water", "fade").none { it in key }) c += v
            }
            return c
        }
        val withLaser = opaque(dir)
        val without = opaque(plainDir)
        println("Koplanar deckend: mit Laser $withLaser, ohne $without")
        assertTrue(withLaser <= without, "mit Laser $withLaser, ohne $without")
    }
}

/** Testquelle unter einem erfundenen Host; LoD2 gibt es dort nicht (404). */
internal val TEST_LASER = LaserSource(
    id = "test",
    label = "Testland",
    minLat = 50.0,
    minLon = 6.0,
    maxLat = 52.0,
    maxLon = 8.0,
    utmZone = 32,
    dgmWcsUrl = "https://laser.example.org/dgm",
    dgmCoverage = "dgm",
    domWcsUrl = "https://laser.example.org/dom",
    domCoverage = "dom",
    lod2UrlTemplate = "https://laser.example.org/lod2/LoD2_32_{e}_{n}_1_NW.gml",
    attribution = "Gelände: Testdienst",
    license = "CC0",
    licenseUrl = "https://example.org/lizenz",
)

/**
 * Kuenstliche WCS-Antworten: DGM = Terrarium-Hoehen der Testwelt
 * ([FakeGeo.syntheticHeight]) plus ein Graben und kleine Wellen, DOM = DGM
 * plus Baumkronen auf einem 9-m-Raster in einem Waldstreifen.
 */
internal class SyntheticLaser : LaserFetcher {
    @Volatile var requests = 0

    override fun fetch(url: String): GeoResponse {
        synchronized(this) { requests++ }
        if ("/lod2/" in url) return GeoResponse(404, null, ByteArray(0))
        val sx = Regex("SUBSET=x\\((-?\\d+),(-?\\d+)\\)").find(url)!!.groupValues
        val sy = Regex("SUBSET=y\\((-?\\d+),(-?\\d+)\\)").find(url)!!.groupValues
        val x0 = sx[1].toInt(); val x1 = sx[2].toInt(); val y0 = sy[1].toInt(); val y1 = sy[2].toInt()
        val dom = "/dom" in url
        val w = x1 - x0
        val h = y1 - y0
        val data = FloatArray(w * h)
        for (j in 0 until h) for (i in 0 until w) {
            val x = x0 + i + 0.5
            val y = y1 - j - 0.5
            val ll = Utm.inverse(x, y, 32)
            var g = FakeGeo.syntheticHeight(ll[0], ll[1]) + 0.4 * sin(x / 13.0) * sin(y / 17.0)
            if (abs(((x + y) % 400.0 + 400.0) % 400.0 - 200.0) < 2.0) g -= 1.0
            if (dom && (y % 300.0 + 300.0) % 300.0 < 90.0) {
                val cx = Math.floorDiv(x.toInt(), 9) * 9 + 4.5
                val cy = Math.floorDiv(y.toInt(), 9) * 9 + 4.5
                val d2 = (x - cx) * (x - cx) + (y - cy) * (y - cy)
                g += max(0.0, 16.0 - d2 * 0.9)
            }
            data[j * w + i] = g.toFloat()
        }
        return GeoResponse(200, "image/tiff", floatTiff(w, h, x0.toDouble(), y1.toDouble(), data))
    }

    companion object {
        /** Unkomprimiertes einkanaliges Float32-GeoTIFF (ein Streifen, little-endian). */
        fun floatTiff(w: Int, h: Int, originX: Double, originY: Double, data: FloatArray): ByteArray {
            val tags = 12
            val ifd = 8
            val scaleOff = ifd + 2 + 12 * tags + 4
            val tieOff = scaleOff + 24
            val dataOff = tieOff + 48
            val bb = ByteBuffer.allocate(dataOff + 4 * w * h).order(ByteOrder.LITTLE_ENDIAN)
            bb.put('I'.code.toByte()).put('I'.code.toByte()).putShort(42).putInt(ifd)
            bb.putShort(tags.toShort())
            fun tag(t: Int, type: Int, count: Int, value: Int) {
                bb.putShort(t.toShort()).putShort(type.toShort()).putInt(count)
                if (type == 3 && count == 1) bb.putShort(value.toShort()).putShort(0) else bb.putInt(value)
            }
            tag(256, 3, 1, w); tag(257, 3, 1, h); tag(258, 3, 1, 32); tag(259, 3, 1, 1); tag(262, 3, 1, 1)
            tag(273, 4, 1, dataOff); tag(277, 3, 1, 1); tag(278, 3, 1, h); tag(279, 4, 1, 4 * w * h); tag(339, 3, 1, 3)
            tag(33550, 12, 3, scaleOff)
            tag(33922, 12, 6, tieOff)
            bb.putInt(0)
            bb.putDouble(1.0).putDouble(1.0).putDouble(0.0)
            bb.putDouble(0.0).putDouble(0.0).putDouble(0.0).putDouble(originX).putDouble(originY).putDouble(0.0)
            for (v in data) bb.putFloat(v)
            return bb.array()
        }
    }
}
