package de.trailscape.core.indoor.world

import de.trailscape.core.indoor.world.BuildingsTestSupport.building
import de.trailscape.core.indoor.world.BuildingsTestSupport.context
import de.trailscape.core.indoor.world.BuildingsTestSupport.rect
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Detailtreue: Dachbild aus dem Luftbild, Ortsliste im Manifest, echte Punktobjekte (docs/indoor/detailtreue.md). */
class DetailtreueTest {
    private val track = PreviewSupport.curvyTrack()

    /** Luftbild der Kachel: grau, im Rechteck um ([ce], [cn]) mit Kantenlaenge [size] in [color]. */
    private fun aerial(spec: TileSpec, ce: Double, cn: Double, size: Double, color: Int, px: Int = 256): RgbaImage {
        val img = RgbaImage(px, px)
        val mpp = spec.cellM / px
        for (y in 0 until px) for (x in 0 until px) {
            val e = spec.minE + (x + 0.5) * mpp
            val n = spec.maxN - (y + 0.5) * mpp
            img[x, y] = if (kotlin.math.abs(e - ce) < size / 2 && kotlin.math.abs(n - cn) < size / 2) color else Argb.rgb(128, 128, 126)
        }
        return img
    }

    private fun tile(roofColor: Int, withTexture: Boolean = true): Pair<TileContext, TileOutput> {
        // Haus weit genug von der Strecke, mitten in der Kachel
        val ce = 40.0
        val cn = -60.0
        val spec = TileSpec.at(0, ce, cn)
        val img = aerial(spec, ce, cn, 30.0, roofColor)
        val tex = EncodedTexture(encodeJpegBaseline(img), "image/jpeg", img.width, img.height)
        val map = ListMapData(listOf(building(rect(ce, cn, 12.0, 9.0, 15.0), 9.0)))
        val base = context(spec, map, track = track, aerial = img)
        val ctx = TileContext(
            base.spec, base.origin, base.quality, base.anchorU, base.ground, base.track, base.map, base.land,
            base.textures, base.budget, img, if (withTexture) tex else null,
        )
        val out = TileOutput(spec)
        BuildingsLayer().build(ctx, out)
        return ctx to out
    }

    @Test
    fun `Dach bekommt das Luftbild der Kachel als Textur ohne zweites Bild`() {
        val (_, out) = tile(Argb.rgb(196, 110, 52))
        val info = parseGlb(out.toGlb().toBytes())
        val names = info.materials.map { it.name }
        assertTrue(AerialRoofs.MAT_ROOF_AERIAL in names, "Material fehlt: $names")
        assertFalse(WorldNames.MAT_BUILDING_ROOF in names, "Atlasdach trotz Luftbild: $names")
        val roof = info.materials.first { it.name == AerialRoofs.MAT_ROOF_AERIAL }
        assertNotNull(roof.baseColorTexture)
        // Keine Materialerweiterung (Issue 106) und die Kachel besteht die Pruefung.
        assertTrue(info.extensionsUsed.isEmpty(), "Erweiterungen: ${info.extensionsUsed}")
        assertEquals(emptyList(), checkTileGlb(out.toGlb().toBytes()))
    }

    @Test
    fun `gruenes Dach im Luftbild (Baumkronen) behaelt die Atlastextur`() {
        val (_, out) = tile(Argb.rgb(60, 110, 40))
        val names = parseGlb(out.toGlb().toBytes()).materials.map { it.name }
        assertFalse(AerialRoofs.MAT_ROOF_AERIAL in names, "Kronen als Dach: $names")
        assertTrue(WorldNames.MAT_BUILDING_ROOF in names)
    }

    @Test
    fun `ohne Luftbild bleibt alles beim Alten`() {
        val (_, out) = tile(Argb.rgb(196, 110, 52), withTexture = false)
        val names = parseGlb(out.toGlb().toBytes()).materials.map { it.name }
        assertFalse(AerialRoofs.MAT_ROOF_AERIAL in names)
        assertTrue(WorldNames.MAT_BUILDING_ROOF in names)
    }

    @Test
    fun `Dach-UV zeigen auf die Stelle des Gebaeudes im Luftbild`() {
        val ce = 40.0
        val cn = -60.0
        val spec = TileSpec.at(0, ce, cn)
        val img = aerial(spec, ce, cn, 30.0, Argb.rgb(196, 110, 52))
        val tex = EncodedTexture(encodeJpegBaseline(img), "image/jpeg", img.width, img.height)
        val base = context(spec, MapData.EMPTY, track = track, aerial = img)
        val ctx = TileContext(base.spec, base.origin, base.quality, base.anchorU, base.ground, base.track, base.map, base.land, base.textures, base.budget, img, tex)
        val roofs = AerialRoofs.of(ctx)!!
        // Ein flaches Quadrat 8 × 8 m als „Dach“ um das Gebaeude
        val m = MeshBuilder(8, 8)
        val y = 10.0
        val pts = listOf(ce - 4 to cn - 4, ce + 4 to cn - 4, ce + 4 to cn + 4, ce - 4 to cn + 4)
        for ((e, n) in pts) m.vertex(ctx.x(e), y, ctx.z(n), 0.0, 1.0, 0.0)
        m.quad(0, 1, 2, 3)
        val target = MeshBuilder(8, 8)
        assertTrue(roofs.adopt(m, 9.0) { target })
        for (i in 0 until target.vertexCount) {
            val e = target.positions[3 * i] + spec.anchorE
            val n = spec.anchorN - target.positions[3 * i + 2]
            assertEquals((e - spec.minE) / spec.cellM, target.uvs[2 * i].toDouble(), 0.01)
            assertEquals((spec.maxN - n) / spec.cellM, target.uvs[2 * i + 1].toDouble(), 0.01)
        }
        // zu hoch (Kippen im Orthofoto) → abgelehnt
        assertFalse(roofs.adopt(m, 40.0) { target })
    }

    @Test
    fun `Ortsliste nennt durchfahrene Orte mit Laufmeter, Runde zweimal`() {
        // Gerade Strecke 3 km nach Osten und zurueck (Runde)
        val step = 5.0
        val out = (0..600).map { it * step }
        val back = (1..600).map { 3000.0 - it * step }
        val es = (out + back).map { it.toFloat() }.toFloatArray()
        val ns = FloatArray(es.size)
        val t = WorldTrack(
            lengthM = (es.size - 1) * step, stepM = step, loop = true, e = es, n = ns, u = FloatArray(es.size),
            surfaces = PreviewSupport.curvyTrack().surfaces.take(1).map { it.copy(fromM = 0.0, toM = (es.size - 1) * step) },
            source = PreviewSupport.curvyTrack().source,
        )
        val map = object : MapData by MapData.EMPTY {
            override fun places(minE: Double, minN: Double, maxE: Double, maxN: Double) = listOf(
                MapPlace(0.0, 150.0, "Startdorf", "village"),
                MapPlace(1500.0, 300.0, "Mittelweiler", "hamlet"),
                MapPlace(2900.0, 1200.0, "Fernstadt", "town"),
                MapPlace(1500.0, 3000.0, "Abseits", "village"),
            ).filter { it.e in minE..maxE && it.n in minN..maxN }
        }
        val places = WorldPlaces.of(map, t)
        assertEquals(listOf("Startdorf", "Mittelweiler", "Fernstadt", "Mittelweiler", "Startdorf"), places.map { it.name }, places.toString())
        assertEquals(0.0, places.first().distM, 1.0)
        assertEquals(1500.0, places[1].distM, 5.0)
        assertEquals(2900.0, places[2].distM, 5.0)
        assertEquals(4500.0, places[3].distM, 5.0)
        assertEquals(6000.0, places.last().distM, 5.0)
    }

    @Test
    fun `Manifest schreibt places nur wenn vorhanden und liest alte Manifeste`() {
        val m = WorldManifest(
            worldId = "w-test", builder = WORLD_BUILDER_ID, createdAtMs = 0, quality = IndoorQuality.MEDIUM,
            routeId = "r-test", routeName = "Test", origin = PreviewSupport.origin,
            bounds = WorldBounds(-256.0, -256.0, 256.0, 256.0, -10.0, 10.0), track = track,
            levels = (0 until WORLD_LEVEL_COUNT).map { WorldLevel(it, worldCellM(it), IndoorQuality.MEDIUM.texturePx(it)) },
            tiles = emptyList(), budget = WorldBudget(0, 0, 0), attribution = emptyList(),
        )
        assertFalse("\"places\"" in m.toJson())
        assertEquals(emptyList(), WorldManifest.parse(m.toJson()).places)
        val withPlaces = m.copy(places = listOf(WorldPlace("Marbach am Neckar", 120.5), WorldPlace("Affalterbach", 8450.0)))
        val back = WorldManifest.parse(withPlaces.toJson())
        assertEquals(withPlaces.places, back.places)
        // Kaputte Eintraege kosten nur den Eintrag
        val broken = withPlaces.toJson().replace("\"Affalterbach\"", "\"\"")
        assertEquals(listOf("Marbach am Neckar"), WorldManifest.parse(broken).places.map { it.name })
    }

    @Test
    fun `Bushaltestelle auf der Fahrbahn steht am Rand, Poller auf der Strecke entfaellt`() {
        val i = 40
        val e = track.e[i].toDouble()
        val n = track.n[i].toDouble()
        val spec = TileSpec.at(0, e, n)
        fun build(cls: String, sub: String?): Int {
            val map = object : MapData by MapData.EMPTY {
                override fun pois(minE: Double, minN: Double, maxE: Double, maxN: Double) =
                    listOf(MapPoi(e, n, cls, sub, null)).filter { it.e in minE..maxE && it.n in minN..maxN }
            }
            val ctx = context(spec, map, track = track)
            val out = TileOutput(spec)
            PoiLayer().build(ctx, out)
            return out.triangles(WorldNames.NODE_PROPS)
        }
        assertTrue(build("bus", "bus_stop") > 0)
        assertTrue(build("lift_gate", "lift_gate") > 0)
        assertTrue(build("cycle_barrier", "cycle_barrier") > 0)
        assertEquals(0, build("bollard", "bollard"))
        assertEquals(0, build("restaurant", "restaurant"))
        // Kein Teil der Bushaltestelle auf der Fahrbahn
        val map = object : MapData by MapData.EMPTY {
            override fun pois(minE: Double, minN: Double, maxE: Double, maxN: Double) =
                listOf(MapPoi(e, n, "bus", "bus_stop", null)).filter { it.e in minE..maxE && it.n in minN..maxN }
        }
        val ctx = context(spec, map, track = track)
        val out = TileOutput(spec)
        PoiLayer().build(ctx, out)
        val mesh = out.mesh(WorldNames.NODE_PROPS, TileMaterial(PropsLayer.MATERIAL, roughness = 0.85f))
        val line = TrackLine(track)
        for (v in 0 until mesh.vertexCount) {
            val ve = mesh.positions[3 * v] + spec.anchorE
            val vn = spec.anchorN - mesh.positions[3 * v + 2]
            val hit = line.nearest(ve, vn, 30.0)!!
            assertTrue(hit.distance > line.halfWidthAt(hit.distM) + 0.2, "Punkt auf der Fahrbahn: ${hypot(ve - e, vn - n)}")
        }
    }
}

/** Volle OSM-Attribute (Overpass-Auszug) und ihre Zeichenschicht. */
class OsmDetailTest {
    private val track = PreviewSupport.curvyTrack()

    @Test
    fun `Overpass-Antwort wird gelesen, unbekannte Objekte uebersprungen`() {
        val json = """
            {"version":0.6,"elements":[
              {"type":"way","id":1,"tags":{"barrier":"hedge"},"geometry":[{"lat":50.0,"lon":7.0},{"lat":50.0001,"lon":7.0002}]},
              {"type":"node","id":2,"lat":50.001,"lon":7.001,"tags":{"power":"generator","generator:source":"wind","height":"140"}},
              {"type":"node","id":3,"lat":50.002,"lon":7.002,"tags":{"amenity":"bench"}},
              {"type":"way","id":4,"tags":{"man_made":"silo"},"geometry":[{"lat":50.0,"lon":7.0},{"lat":50.0,"lon":7.0001},{"lat":50.0001,"lon":7.0001}]}
            ]}
        """.trimIndent()
        val d = OsmDetail.parseOverpass(json)
        assertEquals(3, d.size)
        assertEquals(mapOf("hedge" to 1, "silo" to 1, "wind_turbine" to 1), d.counts())
        val f = d.project(GeoOrigin(50.0, 7.0, 0.0))
        val wind = f.first { it.kind == OsmKind.WIND_TURBINE }
        assertEquals(140.0, wind.heightM()!!, 1e-9)
        assertTrue(f.first { it.kind == OsmKind.SILO }.pts.size == 2, "Silo als Punkt")
        assertTrue(OsmDetail.overpassQuery(doubleArrayOf(50.0, 7.0, 50.01, 7.01)).contains("around:120,50,7,50.01,7.01"))
    }

    @Test
    fun `Hecke neben der Strecke und Windrad werden gezeichnet, nichts auf der Fahrbahn`() {
        val i = 40
        val e = track.e[i].toDouble()
        val n = track.n[i].toDouble()
        val spec = TileSpec.at(0, e, n)
        val line = TrackLine(track)
        val pose = line.poseAt(i * track.stepM)
        val h = Math.toRadians(pose.headingDeg)
        // Hecke laengs, 6 m rechts; eine zweite genau auf der Mittellinie (muss an den Rand)
        fun along(off: Double) = doubleArrayOf(
            e + kotlin.math.cos(h) * off - kotlin.math.sin(h) * 10, n - kotlin.math.sin(h) * off - kotlin.math.cos(h) * 10,
            e + kotlin.math.cos(h) * off + kotlin.math.sin(h) * 10, n - kotlin.math.sin(h) * off + kotlin.math.cos(h) * 10,
        )
        val feats = listOf(
            OsmFeature(OsmKind.HEDGE, along(6.0), emptyMap()),
            OsmFeature(OsmKind.GUARD_RAIL, along(0.0), emptyMap()),
            OsmFeature(OsmKind.WIND_TURBINE, doubleArrayOf(spec.minE + 30, spec.minN + 30), mapOf("height" to "100")),
        )
        val map = OsmDetailMap(MapData.EMPTY, feats)
        val ctx = BuildingsTestSupport.context(spec, map, track = track)
        val out = TileOutput(spec)
        OsmDetailLayer().build(ctx, out)
        assertTrue(out.triangles(WorldNames.NODE_PROPS) > 100)
        val mesh = out.mesh(WorldNames.NODE_PROPS, TileMaterial(PropsLayer.MATERIAL, roughness = 0.85f))
        var high = 0.0
        for (v in 0 until mesh.vertexCount) {
            val ve = mesh.positions[3 * v] + spec.anchorE
            val vn = spec.anchorN - mesh.positions[3 * v + 2]
            high = maxOf(high, mesh.positions[3 * v + 1].toDouble())
            val hit = line.nearest(ve, vn, 30.0) ?: continue
            assertTrue(hit.distance > line.halfWidthAt(hit.distM) + 0.2, "Teil auf der Fahrbahn")
        }
        assertTrue(high > 90.0, "Windrad fehlt (hoechster Punkt $high)")
        assertEquals(emptyList(), checkTileGlb(out.toGlb().toBytes()))
    }
}
