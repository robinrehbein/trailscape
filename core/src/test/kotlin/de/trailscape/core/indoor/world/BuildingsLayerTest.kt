package de.trailscape.core.indoor.world

import de.trailscape.core.indoor.world.BuildingTextures.Band
import de.trailscape.core.indoor.world.BuildingTextures.Decal
import de.trailscape.core.indoor.world.BuildingsTestSupport.backFaceHits
import de.trailscape.core.indoor.world.BuildingsTestSupport.building
import de.trailscape.core.indoor.world.BuildingsTestSupport.context
import de.trailscape.core.indoor.world.BuildingsTestSupport.flatGround
import de.trailscape.core.indoor.world.BuildingsTestSupport.openEdges
import de.trailscape.core.indoor.world.BuildingsTestSupport.rect
import de.trailscape.core.indoor.world.BuildingsTestSupport.triangles
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BuildingsLayerTest {
    private val track = PreviewSupport.curvyTrack()
    private val line = TrackLine(track)

    private fun design(b: MapBuilding, ground: GroundSurface = flatGround, aerial: Int? = null): BuildingDesign? =
        BuildingDesign.of(b, ground, line, 0) { _, _, _ -> aerial }

    /** Ein Rechteckhaus mit Satteldach (kein Walmdach-Sonderfall) nahe dem Ursprung. */
    private fun gableHouse(ground: GroundSurface = flatGround, w: Double = 12.0, h: Double = 8.0): BuildingDesign {
        for (k in 0 until 40) {
            val d = design(building(rect(20.0 + 3.1 * k, 14.0, w, h, 20.0)), ground)!!
            if (d.form == RoofForm.WINGS && d.wings.size == 1 && !d.wings[0].hipLo && !d.wings[0].hipHi) return d
        }
        error("kein Satteldach gefunden")
    }

    private fun spec(d: BuildingDesign, level: Int = 0) = TileSpec.at(level, d.ce, d.cn)

    private fun mesh(d: BuildingDesign, detail: BuildingDetail, level: Int = 0, ground: GroundSurface = flatGround): BuildingMesh {
        val ctx = context(spec(d, level), MapData.EMPTY, ground, track)
        return BuildingsLayer.mesh(ctx, d, detail, textured = level == 0)
    }

    @Test
    fun `Rechteck bekommt ein Satteldach mit First entlang der Laengsachse`() {
        val d = gableHouse()
        assertEquals(BuildingKind.HOUSE, d.kind)
        val w = d.wings.single()
        assertTrue(w.alongX && w.length > 2 * w.halfWidth, "First nicht entlang der Laengsachse")
        assertEquals(12.0, w.length, 0.01)
        assertEquals(8.0, 2 * w.halfWidth, 0.01)
        assertTrue(roofPitchDeg(d.tan) in 35.0..45.5, "Dachneigung ${roofPitchDeg(d.tan)}")
        assertEquals(d.eaveTopU + 4.0 * d.tan, d.ridgeU, 1e-6)
        // Die hoechsten Punkte des Netzes liegen auf einer Linie laengs durch die Mitte.
        val ctx = context(spec(d), MapData.EMPTY)
        val m = BuildingsLayer.mesh(ctx, d, BuildingDetail.SIMPLE, textured = true)
        val top = triangles(m.roof).flatMap { t -> (0 until 3).map { doubleArrayOf(t.p[3 * it], t.p[3 * it + 1], t.p[3 * it + 2]) } }
        val maxY = top.maxOf { it[1] }
        assertEquals(d.ridgeU, ctx.anchorU + maxY, 1e-3)
        val ridge = top.filter { it[1] > maxY - 1e-3 }
        val c = cos(d.angle)
        val s = sin(d.angle)
        // zurueck ins Gebaeudesystem
        val along = ridge.map { p -> (p[0] + ctx.spec.anchorE - d.ce) * c + (-p[2] + ctx.spec.anchorN - d.cn) * s }
        val across = ridge.map { p -> -(p[0] + ctx.spec.anchorE - d.ce) * s + (-p[2] + ctx.spec.anchorN - d.cn) * c }
        assertEquals(12.0, along.max() - along.min(), 0.01)
        assertTrue(across.all { abs(it) < 0.01 }, "First nicht mittig: $across")
    }

    @Test
    fun `Rechteckhaus ist als Kubatur wasserdicht bis auf die Unterkante im Boden`() {
        val d = gableHouse()
        val m = mesh(d, BuildingDetail.SIMPLE, level = 1)
        val tris = triangles(m.wall, m.roof)
        val bottom = tris.minOf { t -> minOf(t.p[1], t.p[4], t.p[7]) }
        assertEquals(emptyList(), openEdges(tris, openBelowY = bottom))
        // Giebel geschlossen: Wandflaeche reicht bis zum First.
        val wallTop = triangles(m.wall).maxOf { t -> maxOf(t.p[1], t.p[4], t.p[7]) }
        val roofTop = triangles(m.roof).maxOf { t -> maxOf(t.p[1], t.p[4], t.p[7]) }
        assertEquals(roofTop, wallTop, 1e-3)
    }

    @Test
    fun `Dachkoerper mit Ueberstand ist rundum geschlossen und die Waende schliessen an seine Untersicht an`() {
        val d = gableHouse()
        val ctx = context(spec(d), MapData.EMPTY)
        val m = BuildingsLayer.mesh(ctx, d, BuildingDetail.MID, textured = true)
        assertEquals(emptyList(), openEdges(triangles(m.roof)))
        // Ueberstand: das Dach ist breiter und laenger als der Grundriss.
        val xs = triangles(m.roof).flatMap { t -> listOf(t.p[0], t.p[3], t.p[6]) }
        val zs = triangles(m.roof).flatMap { t -> listOf(t.p[2], t.p[5], t.p[8]) }
        val wx = triangles(m.wall).flatMap { t -> listOf(t.p[0], t.p[3], t.p[6]) }
        assertTrue(xs.max() - xs.min() > wx.max() - wx.min() + 0.3)
        assertTrue(zs.max() - zs.min() > 0.0)
        // Oberkante der Traufwand = Untersicht des Dachs an der Wandflucht.
        val eaveWallTop = d.eaveTopU - d.roofThickness
        val wallYs = triangles(m.wall).flatMap { t -> listOf(t.p[1], t.p[4], t.p[7]) }.map { it + ctx.anchorU }
        assertTrue(wallYs.any { abs(it - eaveWallTop) < 1e-3 }, "keine Wandkante an der Untersicht")
        for (detail in listOf(BuildingDetail.FULL, BuildingDetail.MID, BuildingDetail.SIMPLE, BuildingDetail.PLAIN, BuildingDetail.BLOCK)) {
            val all = BuildingsLayer.mesh(ctx, d, detail, textured = true)
            assertEquals(0, backFaceHits(triangles(all.wall, all.roof), fromY = d.groundMaxU - ctx.anchorU), "Rueckseite sichtbar bei $detail")
        }
    }

    private val lShape = doubleArrayOf(-7.5, -5.65, 1.9, -5.65, 1.9, -1.95, 7.5, -1.95, 7.5, 5.65, -7.5, 5.65)
    private val tShape = doubleArrayOf(-8.0, 2.0, -8.0, 7.0, 8.0, 7.0, 8.0, 2.0, 3.0, 2.0, 3.0, -7.0, -3.0, -7.0, -3.0, 2.0)
    private val uShape = doubleArrayOf(-9.0, -6.0, 9.0, -6.0, 9.0, 6.0, 4.0, 6.0, 4.0, -1.0, -4.0, -1.0, -4.0, 6.0, -9.0, 6.0)

    private fun shapes(): Map<String, MapBuilding> {
        val out = LinkedHashMap<String, MapBuilding>()
        fun put(name: String, ring: DoubleArray, vararg holes: DoubleArray) {
            val i = out.size
            val e = 30.0 + 37.0 * (i % 5)
            val n = 20.0 + 41.0 * (i / 5)
            out[name] = MapBuilding(i + 1L, (listOf(ring) + holes.toList()).map { BuildingsVillage.place(it, e, n, 0.3 + 0.2 * i) }, 0.0)
        }
        put("L", lShape)
        put("T", tShape)
        put("U", uShape)
        put("Innenhof", BuildingsVillage.courtyard[0], BuildingsVillage.courtyard[1])
        put("Kreuz", doubleArrayOf(-3.0, -9.0, 3.0, -9.0, 3.0, -3.0, 9.0, -3.0, 9.0, 3.0, 3.0, 3.0, 3.0, 9.0, -3.0, 9.0, -3.0, 3.0, -9.0, 3.0, -9.0, -3.0, -3.0, -3.0))
        put("Treppe", doubleArrayOf(0.0, 0.0, 12.0, 0.0, 12.0, 4.0, 8.0, 4.0, 8.0, 8.0, 4.0, 8.0, 4.0, 12.0, 0.0, 12.0))
        put("Dreieck", doubleArrayOf(0.0, 0.0, 14.0, 0.0, 3.0, 9.0))
        put("Trapez", doubleArrayOf(0.0, 0.0, 14.0, 0.0, 10.0, 8.0, 2.0, 8.0))
        put("Schleife", doubleArrayOf(0.0, 0.0, 10.0, 8.0, 10.0, 0.0, 0.0, 8.0))
        put("Beruehrung", doubleArrayOf(0.0, 0.0, 6.0, 0.0, 6.0, 6.0, 12.0, 6.0, 12.0, 12.0, 6.0, 12.0, 6.0, 6.0, 0.0, 6.0))
        put("Doppelpunkte", doubleArrayOf(0.0, 0.0, 0.0, 0.0, 9.0, 0.0, 9.0, 0.0, 9.0, 7.0, 4.5, 7.0, 0.0, 7.0, 0.0, 7.0))
        put("Rund", DoubleArray(32) { val a = it / 2 * Math.PI / 8; if (it % 2 == 0) 6 * cos(a) else 6 * sin(a) })
        put("Nadel", doubleArrayOf(0.0, 0.0, 30.0, 0.0, 30.0, 0.9, 0.0, 0.9))
        put("Riesig", doubleArrayOf(0.0, 0.0, 60.0, 0.0, 60.0, 35.0, 20.0, 35.0, 20.0, 20.0, 0.0, 20.0))
        put("Loch am Rand", doubleArrayOf(0.0, 0.0, 16.0, 0.0, 16.0, 14.0, 0.0, 14.0), doubleArrayOf(0.2, 3.0, 0.2, 9.0, 8.0, 9.0, 8.0, 3.0))
        for ((k, sh) in BuildingsVillage.shapes.withIndex()) put("Dorf $k ${sh.tag}", sh.ring)
        return out
    }

    @Test
    fun `verwinkelte und entartete Grundrisse werfen nicht und ergeben geschlossene Koerper`() {
        var built = 0
        for ((name, b) in shapes()) {
            val d = design(b) ?: continue
            val ctx = context(spec(d), MapData.EMPTY)
            for (detail in BuildingDetail.entries) {
                val m = BuildingsLayer.mesh(ctx, d, detail, textured = true)
                assertTrue(m.triangles > 0, "$name/$detail ohne Dreiecke")
                assertEquals(0, backFaceHits(triangles(m.wall, m.roof), fromY = d.groundMaxU - ctx.anchorU, rays = 900), "$name/$detail: Rueckseite sichtbar")
                for (mb in listOf(m.wall, m.roof)) {
                    for (i in 0 until mb.vertexCount * 3) assertTrue(mb.positions[i].isFinite() && mb.normals[i].isFinite(), "$name/$detail: nicht endlich")
                }
            }
            val far = BuildingsLayer.mesh(context(spec(d, 1), MapData.EMPTY), d, BuildingDetail.SIMPLE, textured = false)
            assertEquals(0, backFaceHits(triangles(far.wall, far.roof), fromY = d.groundMaxU - 100.0, rays = 900), "$name Stufe 1: Rueckseite sichtbar")
            built++
        }
        assertTrue(built >= 55, "nur $built Grundrisse gebaut")
        // Unbrauchbares ergibt kein Gebaeude statt eines Fehlers.
        assertNull(design(building(doubleArrayOf(0.0, 0.0, 1.0, 0.0, 1.0, 1.0, 0.0, 1.0))))
        assertNull(design(building(doubleArrayOf(0.0, 0.0, 5.0, 5.0))))
        assertNull(design(building(doubleArrayOf(0.0, 0.0, 5.0, 0.0, 10.0, 0.0, 20.0, 0.0))))
        assertNull(design(building(doubleArrayOf(0.0, 0.0, Double.NaN, 0.0, 10.0, 10.0, 0.0, 10.0))))
        assertNull(design(MapBuilding(1, emptyList(), 0.0)))
    }

    @Test
    fun `L T und U bekommen einen Hauptbau mit Giebeln und Fluegel oder Anbauten`() {
        val all = shapes()
        for (name in listOf("L", "T", "U")) {
            val d = design(all[name]!!)!!
            assertTrue(d.squared, "$name nicht ausgerichtet")
            if (d.form != RoofForm.WINGS) continue
            assertTrue(d.wings.size >= 2, "$name: nur ${d.wings.size} Fluegel")
            // Jede Stelle des Grundrisses liegt unter einem Dach.
            val ring = d.rings[0]
            for (i in 0 until 400) {
                val x = -10 + 20 * Noise.hash01(i, 1, 3)
                val y = -10 + 20 * Noise.hash01(i, 2, 3)
                if (!pointInRings(d.rings, x, y)) continue
                assertTrue(d.wings.any { it.contains(x, y) }, "$name: ($x, $y) ohne Dach")
                assertTrue(d.roofTopAt(x, y) >= d.floorU + 1.8, "$name: Dach zu tief")
            }
            assertTrue(ring.size >= 12)
        }
        val t = design(all["T"]!!)!!
        if (t.form == RoofForm.WINGS && t.wings[0].role == WingRole.MAIN) {
            assertEquals(16.0, t.wings[0].length, 0.01)
            assertTrue(t.wings.any { it.role == WingRole.CROSS && it.buriedHi != it.buriedLo }, "T ohne Querfluegel")
            assertTrue(t.wings.all { it.base + it.rise <= t.wings[0].rise + 1e-9 }, "Fluegel hoeher als der Hauptfirst")
        }
    }

    @Test
    fun `Gebaeude am Hang steht auf dem hoechsten Punkt und reicht ueberall unter den Boden`() {
        val slope = GroundSurface { e, n -> 100.0 + 0.28 * e - 0.11 * n + 0.6 * sin(e / 4) }
        for (b in listOf(building(rect(40.0, 30.0, 14.0, 9.0, 35.0)), building(BuildingsVillage.place(lShape, 70.0, 20.0, 1.1)))) {
            val d = design(b, slope)!!
            val ring = b.rings[0]
            var gMax = -1e9
            val samples = ArrayList<DoubleArray>()
            for (i in 0 until ring.size / 2) {
                val j = (i + 1) % (ring.size / 2)
                for (k in 0 until 20) {
                    val e = ring[2 * i] + (ring[2 * j] - ring[2 * i]) * k / 20.0
                    val n = ring[2 * i + 1] + (ring[2 * j + 1] - ring[2 * i + 1]) * k / 20.0
                    samples.add(doubleArrayOf(e, n))
                    gMax = maxOf(gMax, slope.heightAt(e, n))
                }
            }
            assertTrue(d.floorU >= gMax - 0.15, "Fussboden ${d.floorU} unter dem Gelaende $gMax")
            assertTrue(d.floorU <= gMax + 0.6, "Fussboden ${d.floorU} schwebt ueber $gMax")
            for (detail in listOf(BuildingDetail.FULL, BuildingDetail.SIMPLE, BuildingDetail.BLOCK)) {
                val ctx = context(spec(d), MapData.EMPTY, slope)
                val m = BuildingsLayer.mesh(ctx, d, detail, textured = true)
                val wallTris = triangles(m.wall)
                // Ueber jeder Stelle des (ausgerichteten) Umrisses reicht eine Wand unter das Gelaende.
                for (e in d.edges.filter { !it.upper }) {
                    for (k in 0..10) {
                        val x = e.x0 + (e.x1 - e.x0) * k / 10.0
                        val y = e.y0 + (e.y1 - e.y0) * k / 10.0
                        val pe = d.eOf(x, y)
                        val pn = d.nOf(x, y)
                        val gx = ctx.x(pe)
                        val gz = ctx.z(pn)
                        val low = wallTris.filter { t -> (0 until 3).any { hypot(t.p[3 * it] - gx, t.p[3 * it + 2] - gz) < 12.0 } }
                            .minOf { t -> minOf(t.p[1], t.p[4], t.p[7]) }
                        assertTrue(low + ctx.anchorU <= slope.heightAt(pe, pn) - 0.3, "$detail: Wand endet ueber dem Boden")
                    }
                }
                val bottom = wallTris.minOf { t -> minOf(t.p[1], t.p[4], t.p[7]) } + ctx.anchorU
                assertTrue(samples.all { bottom <= slope.heightAt(it[0], it[1]) - 0.3 }, "$detail: Unterkante $bottom nicht ueberall im Boden")
                assertEquals(0, backFaceHits(triangles(m.wall, m.roof), fromY = d.groundMaxU - ctx.anchorU), "$detail am Hang: Rueckseite sichtbar")
            }
        }
    }

    @Test
    fun `jedes Gebaeude gehoert genau der Kachel seines Schwerpunkts`() {
        val all = ArrayList<MapBuilding>()
        var id = 1L
        // Reihe quer ueber die Kachelgrenze bei e = 0 und n = 0.
        for (i in -6..6) for (j in -2..2) all.add(MapBuilding(id++, listOf(rect(i * 13.0 + 1.5, j * 14.0 + 2.0, 10.0, 8.0, 7.0 * i)), 0.0))
        val map = ListMapData(all)
        val layer = BuildingsLayer()
        val seen = ArrayList<Long>()
        for (ix in -1..0) for (iy in -1..0) {
            val s = TileSpec(0, ix, iy)
            val designs = layer.designs(context(s, map))
            for (d in designs) {
                assertTrue(s.contains(d.ce, d.cn))
                seen.add((d.ce * 100).toLong() * 100000 + (d.cn * 100).toLong())
            }
        }
        assertEquals(all.size, seen.size, "Gebaeude fehlen oder sind doppelt")
        assertEquals(all.size, seen.toSet().size)
        // Ein Gebaeude ueber der Grenze wird in seiner Kachel vollstaendig gebaut.
        val out = TileOutput(TileSpec(0, 0, 0))
        layer.build(context(TileSpec(0, 0, 0), map), out)
        val scene = parseGlb(out.toGlb().toBytes())
        assertTrue(scene.boundsMin!![0] < -128.0 - 2.0, "Gebaeude an der Kachelgrenze abgeschnitten")
    }

    @Test
    fun `Hoehen werden aus Flaeche und Form geschaetzt wenn die Daten nichts sagen`() {
        assertTrue(BuildingDesign.heightUnknown(0.0) && BuildingDesign.heightUnknown(5.0) && !BuildingDesign.heightUnknown(7.3))
        assertEquals(2, BuildingDesign.levelsOf(7.0))
        assertEquals(0, BuildingDesign.levelsOf(8.0))
        for (k in 0 until 30) {
            val shed = design(building(rect(10.0 + 9.0 * k, 5.0, 5.5, 3.2, 10.0)))!!
            assertTrue(shed.kind == BuildingKind.SHED || shed.kind == BuildingKind.GARAGE)
            assertTrue(shed.eaveTopU - shed.floorU in 1.9..3.0, "Schuppen ${shed.eaveTopU - shed.floorU}")
            assertTrue(shed.ridgeU - shed.eaveTopU < 2.2)
            val house = design(building(rect(10.0 + 9.0 * k, 40.0, 11.0, 9.0, 10.0)))!!
            assertEquals(BuildingKind.HOUSE, house.kind)
            assertTrue(house.eaveTopU - house.floorU in 3.6..6.5, "Haus ${house.eaveTopU - house.floorU}")
            assertTrue(roofPitchDeg(house.tan) in 35.0..45.5)
            assertTrue(house.storeys in 1..2)
            // Der Vorgabewert 5,0 der Kacheln zaehlt wie „unbekannt".
            val same = design(building(rect(10.0 + 9.0 * k, 40.0, 11.0, 9.0, 10.0), heightM = 5.0))!!
            assertEquals(house.eaveTopU, same.eaveTopU, 1e-9)
            assertEquals(house.ridgeU, same.ridgeU, 1e-9)
            val hall = design(building(rect(10.0 + 9.0 * k, 90.0, 45.0, 24.0, 10.0)))!!
            assertEquals(BuildingKind.HALL, hall.kind)
            assertTrue(roofPitchDeg(hall.tan) <= 25.0, "Halle zu steil: ${roofPitchDeg(hall.tan)}")
            // Echte Hoehe = Oberkante Dach.
            val known = design(building(rect(10.0 + 9.0 * k, 40.0, 11.0, 9.0, 10.0), heightM = 10.4))!!
            assertEquals(10.4, known.ridgeU - known.floorU, 0.05)
            // Geschosszahl (7 = zwei Geschosse) gibt die Traufe.
            val levels = design(building(rect(10.0 + 9.0 * k, 40.0, 11.0, 9.0, 10.0), heightM = 7.0))!!
            assertEquals(5.8, levels.eaveTopU - levels.floorU, 1e-6)
        }
    }

    @Test
    fun `Dachfarbe folgt dem Luftbild und die Wandfarbe der Angabe aus den Daten`() {
        val red = Argb.rgb(150, 80, 60)
        val dark = Argb.rgb(60, 62, 66)
        val green = Argb.rgb(60, 110, 50)
        assertNotNull(BuildingDesign.roofToneFromAerial(red))
        assertNull(BuildingDesign.roofToneFromAerial(green))
        var checked = 0
        for (k in 0 until 20) {
            val b = building(rect(10.0 + 9.0 * k, 40.0, 11.0, 9.0, 10.0))
            val r = design(b, aerial = red)!!
            val a = design(b, aerial = dark)!!
            if (r.cover != RoofCover.TILE) continue
            val rc = r.roofFlatColor
            val ac = a.roofFlatColor
            assertTrue(Argb.red(rc) > 1.8 * Argb.blue(rc), "rotes Dach nicht rot")
            assertTrue(abs(Argb.red(ac) - Argb.blue(ac)) < 12 && Argb.red(ac) < 40, "dunkles Dach nicht anthrazit")
            // Art, Form und Hoehen haengen nicht vom Luftbild ab.
            assertEquals(r.ridgeU, a.ridgeU, 1e-9)
            assertEquals(r.form, a.form)
            checked++
        }
        assertTrue(checked >= 15)
        val blue = (0 until 30).map { design(MapBuilding(1, listOf(rect(10.0 + 9.0 * it, 40.0, 11.0, 9.0)), 0.0, colour = "#4060c0"))!! }
            .first { it.style == FacadeStyle.PLASTER }
        assertTrue(Argb.blue(blue.wallFlatColor) > Argb.red(blue.wallFlatColor) * 1.5)
    }

    private fun crowd(count: Int, cell: Double, originE: Double = 4.0, originN: Double = 4.0): ListMapData {
        val all = ArrayList<MapBuilding>()
        var id = 1L
        for (i in 0 until count) for (j in 0 until count) {
            val w = 6.0 + 5.0 * Noise.hash01(i, j, 11)
            val h = 5.0 + 3.0 * Noise.hash01(i, j, 12)
            all.add(MapBuilding(id++, listOf(rect(originE + i * cell, originN + j * cell, w, h, 90 * Noise.hash01(i, j, 13))), 0.0))
        }
        return ListMapData(all)
    }

    @Test
    fun `das Dreiecksbudget der Kachel wird eingehalten`() {
        val dense = crowd(20, 12.5)
        for (q in IndoorQuality.entries) {
            val ctx = context(TileSpec(0, 0, 0), dense, quality = q)
            val out = TileOutput(ctx.spec)
            BuildingsLayer().build(ctx, out)
            val tris = out.triangles(WorldNames.NODE_BUILDINGS)
            assertTrue(tris in 1..ctx.budget.buildings, "$q: $tris Dreiecke bei Budget ${ctx.budget.buildings}")
            assertEquals(emptyList(), checkTileGlb(out.toGlb().toBytes()))
        }
        // Stufe 1: eine Kleinstadt in einer Kachel.
        val town = crowd(60, 16.5)
        for (q in IndoorQuality.entries) {
            val ctx = context(TileSpec(1, 0, 0), town, quality = q)
            val out = TileOutput(ctx.spec)
            BuildingsLayer().build(ctx, out)
            val tris = out.triangles(WorldNames.NODE_BUILDINGS)
            assertTrue(tris in 1..ctx.budget.buildings, "Stufe 1 $q: $tris Dreiecke bei Budget ${ctx.budget.buildings}")
            assertTrue(tris > ctx.budget.buildings * 0.8, "Budget kaum genutzt: $tris")
            assertEquals(emptyList(), checkTileGlb(out.toGlb().toBytes()))
        }
        // Stufe 2: keine Gebaeude.
        val far = TileOutput(TileSpec(2, 0, 0))
        BuildingsLayer().build(context(TileSpec(2, 0, 0), town), far)
        assertEquals(0, far.triangleCount)
    }

    @Test
    fun `Stufe 1 hat dieselben Trauf- und Firsthoehen wie Stufe 0`() {
        val village = ListMapData(BuildingsVillage.along(track))
        val hills = PreviewSupport.carved(PreviewSupport.hills, line)
        val coarse = TileSpec(1, 0, 0)
        val far = BuildingsLayer().designs(context(coarse, village, hills)).associateBy { it.seed }
        assertTrue(far.size >= 10)
        var compared = 0
        for (ix in 0 until 4) for (iy in 0 until 4) {
            val ctx0 = context(TileSpec(0, ix, iy), village, hills)
            for (d in BuildingsLayer().designs(ctx0)) {
                val f = far[d.seed] ?: error("Gebaeude fehlt in Stufe 1")
                assertEquals(d.eaveTopU, f.eaveTopU, 1e-9)
                assertEquals(d.ridgeU, f.ridgeU, 1e-9)
                assertEquals(d.floorU, f.floorU, 1e-9)
                assertEquals(d.wallFlatColor, f.wallFlatColor)
                assertEquals(d.roofFlatColor, f.roofFlatColor)
                assertContentEquals(d.rings[0], f.rings[0])
                // Auch das Netz: hoechster Punkt der Kubatur in beiden Stufen gleich.
                val m0 = BuildingsLayer.mesh(ctx0, d, BuildingDetail.SIMPLE, textured = true)
                val ctx1 = context(coarse, village, hills)
                val m1 = BuildingsLayer.mesh(ctx1, f, BuildingDetail.SIMPLE, textured = false)
                val top0 = triangles(m0.roof).maxOf { t -> maxOf(t.p[1], t.p[4], t.p[7]) } + ctx0.anchorU
                val top1 = triangles(m1.roof).maxOf { t -> maxOf(t.p[1], t.p[4], t.p[7]) } + ctx1.anchorU
                assertEquals(top0, top1, 2e-3)
                assertEquals(d.ridgeU, top0, 2e-3)
                compared++
            }
        }
        assertEquals(far.size, compared)
    }

    @Test
    fun `Texturkoordinaten bleiben innerhalb ihres Atlasbands`() {
        val village = ListMapData(BuildingsVillage.along(track))
        val hills = PreviewSupport.carved(PreviewSupport.hills, line)
        var checked = 0
        val used = HashSet<Band>()
        for (ix in -1 until 2) for (iy in -1 until 2) {
            val ctx = context(TileSpec(0, ix, iy), village, hills, quality = IndoorQuality.HIGH)
            for (d in BuildingsLayer().designs(ctx)) for (detail in BuildingDetail.entries) {
                val m = BuildingsLayer.mesh(ctx, d, detail, textured = true)
                for (mb in listOf(m.wall, m.roof)) {
                    for (t in 0 until mb.triangleCount) {
                        val i0 = mb.indices[3 * t]
                        val i1 = mb.indices[3 * t + 1]
                        val i2 = mb.indices[3 * t + 2]
                        val vs = doubleArrayOf(mb.uvs[2 * i0 + 1].toDouble(), mb.uvs[2 * i1 + 1].toDouble(), mb.uvs[2 * i2 + 1].toDouble())
                        val us = doubleArrayOf(mb.uvs[2 * i0].toDouble(), mb.uvs[2 * i1].toDouble(), mb.uvs[2 * i2].toDouble())
                        val band = Band.entries.firstOrNull { b -> vs.all { it >= b.vTop - 1e-5 && it <= b.vBottom + 1e-5 } }
                        assertNotNull(band, "Dreieck ueber mehrere Baender: v = ${vs.toList()} (${d.kind} ${d.style} $detail)")
                        used.add(band)
                        if (band == Band.SPECIAL) {
                            assertTrue(Decal.entries.any { dc -> us.all { it >= dc.u0 - 1e-5 && it <= dc.u1 + 1e-5 } }, "Aufsetzteil ueber mehrere Felder: u = ${us.toList()}")
                        }
                        checked++
                    }
                }
            }
        }
        assertTrue(checked > 5000, "nur $checked Dreiecke geprueft")
        assertTrue(used.containsAll(listOf(Band.PLASTER_WINDOWS, Band.PLASTER, Band.SOCLE, Band.PANTILE, Band.TRIM, Band.SPECIAL)), "benutzt: $used")
        // Halbes Pixel Einzug und vier Pixel wiederholter Rand je Band.
        for (b in Band.entries) {
            assertTrue(b.vTop * BuildingTextures.HEIGHT >= b.top + BuildingTextures.GUTTER + 0.5 - 1e-9)
            assertTrue(b.vBottom * BuildingTextures.HEIGHT <= b.top + b.pitch - BuildingTextures.GUTTER - 0.5 + 1e-9)
        }
    }

    @Test
    fun `Fensterachsen fuellen die Wand ohne halbe Fenster`() {
        for (len in listOf(1.0, 1.95, 2.6, 3.2, 4.1, 5.5, 6.9, 8.25, 9.5, 11.0, 13.7, 22.0)) {
            val lay = bayLayout(len, exact = false)
            if (lay == null) {
                assertTrue(len < 3.3, "Wand $len ohne Achsen")
                continue
            }
            assertEquals(len, 2 * lay.margin + lay.count * lay.width, 1e-9)
            assertTrue(lay.count >= 1 && lay.margin >= 0.0)
            assertTrue(abs(lay.width - BuildingTextures.BAY_M) <= 0.31, "Achse ${lay.width} bei Wand $len")
            // Das Fenster (1,1 m) bleibt ganz auf der Wand.
            assertTrue(lay.width / 2 > BuildingTextures.WINDOW_HALF_M * lay.width / BuildingTextures.BAY_M)
            val far = bayLayout(len, exact = true)!!
            assertEquals(0.0, far.margin)
            assertEquals(len, far.count * far.width, 1e-9)
        }
    }

    @Test
    fun `Kachel ist vertragstreu und wird zweimal gleich gebaut`() {
        val village = ListMapData(BuildingsVillage.along(track))
        val hills = PreviewSupport.carved(PreviewSupport.hills, line)
        fun bytes(level: Int, ix: Int, iy: Int): ByteArray {
            val ctx = context(TileSpec(level, ix, iy), village, hills)
            val out = TileOutput(ctx.spec)
            BuildingsLayer().build(ctx, out)
            return out.toGlb().toBytes()
        }
        var withBuildings = 0
        for (ix in -1 until 2) for (iy in -1 until 2) {
            val a = bytes(0, ix, iy)
            val scene = parseGlb(a)
            if (scene.triangleCount == 0) continue
            withBuildings++
            assertEquals(emptyList(), checkTileGlb(a))
            assertContentEquals(a, bytes(0, ix, iy))
            assertEquals(setOf(WorldNames.MAT_BUILDING_WALL, WorldNames.MAT_BUILDING_ROOF), scene.materials.map { it.name }.toSet())
            // Beide Materialien teilen sich den einen Atlas.
            assertEquals(textureGpuBytes(BuildingTextures.WIDTH, BuildingTextures.HEIGHT), scene.textureGpuBytes)
        }
        assertTrue(withBuildings >= 3)
        val coarse = bytes(1, 0, 0)
        assertEquals(emptyList(), checkTileGlb(coarse))
        assertContentEquals(coarse, bytes(1, 0, 0))
        val info = parseGlb(coarse)
        assertTrue(info.triangleCount > 100)
        assertEquals(0L, info.textureGpuBytes, "Stufe 1 soll ohne Textur auskommen")
    }
}
