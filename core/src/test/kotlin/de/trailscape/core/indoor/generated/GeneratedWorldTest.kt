package de.trailscape.core.indoor.generated

import de.trailscape.core.indoor.IndoorRoute
import de.trailscape.core.indoor.generated.GeneratedProfile.Kind
import de.trailscape.core.indoor.generated.GeneratedProfile.Leg
import de.trailscape.core.indoor.world.IndoorQuality
import de.trailscape.core.indoor.world.WorldBuildListener
import de.trailscape.core.indoor.world.WorldBuildPhase
import de.trailscape.core.indoor.world.WorldManifest
import de.trailscape.core.indoor.world.aerialSources
import de.trailscape.core.indoor.world.checkWorld
import java.io.File
import java.nio.file.Files
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class GeneratedWorldTest {
    private val intervals = GeneratedProfile(
        seed = 4808,
        legs = buildList {
            add(Leg(3_000.0, 0.0, Kind.FLAT))
            repeat(3) {
                add(Leg(2_000.0, 6.0, Kind.CLIMB))
                add(Leg(1_200.0, -1.0, Kind.FLAT))
            }
            add(Leg(2_500.0, 0.0, Kind.FLAT))
        },
    )

    private fun checkInvariants(r: IndoorRoute, profile: GeneratedProfile) {
        assertEquals(profile.lengthM, r.lengthM, 1e-6)
        assertEquals(0.0, r.points.first().distM)
        for (i in 1 until r.points.size) {
            val ds = r.points[i].distM - r.points[i - 1].distM
            assertTrue(ds in 1.0..15.0 + 1e-9, "Abstand $ds bei $i")
            val g = (r.points[i].eleM - r.points[i - 1].eleM) / ds * 100
            assertTrue(g in IndoorRoute.MIN_GRADE_PCT..IndoorRoute.MAX_GRADE_PCT, "Steigung $g bei $i")
            assertTrue(r.points[i].eleM.isFinite())
        }
        assertEquals(0.0, r.surfaces.first().fromM)
        assertEquals(r.lengthM, r.surfaces.last().toM, 1e-6)
        for (i in 1 until r.surfaces.size) assertEquals(r.surfaces[i - 1].toM, r.surfaces[i].fromM)
        assertTrue(!r.isLoop)
        // keine echten Geodaten, kein Luftbild-Dienst
        assertTrue(aerialSources.none { s -> r.points.any { s.covers(it.lat, it.lon) } })
    }

    @Test
    fun `Strecke hat die Laenge des Profils und haelt die Zusicherungen ein`() {
        for (seed in listOf(1L, 2L, 3L, 77L)) {
            val p = intervals.copy(seed = seed)
            checkInvariants(generatedIndoorRoute(p), p)
        }
        val odd = GeneratedProfile(5, listOf(Leg(333.3, 0.0, Kind.FLAT), Leg(1234.5, 3.0, Kind.ROLLING)))
        checkInvariants(generatedIndoorRoute(odd), odd)
    }

    @Test
    fun `Hoehenverlauf folgt den Legs`() {
        val p = intervals
        val r = generatedIndoorRoute(p)
        var at = 0.0
        for (leg in p.legs) {
            // Mitte des Legs (ohne die weichen Uebergaenge an den Enden)
            val a = at + 150
            val b = at + leg.lengthM - 150
            val ea = r.points.minBy { abs(it.distM - a) }
            val eb = r.points.minBy { abs(it.distM - b) }
            val grade = (eb.eleM - ea.eleM) / (eb.distM - ea.distM) * 100
            assertEquals(leg.gradePct, grade, 0.8, "Leg ab ${at.toInt()} m")
            at += leg.lengthM
        }
    }

    @Test
    fun `unfahrbare Steigungen werden gekappt`() {
        val p = GeneratedProfile(9, listOf(Leg(1_000.0, 0.0, Kind.FLAT), Leg(1_500.0, 35.0, Kind.CLIMB), Leg(1_000.0, 0.0, Kind.FLAT)))
        val r = generatedIndoorRoute(p)
        val a = r.points.minBy { abs(it.distM - 1_200) }
        val b = r.points.minBy { abs(it.distM - 2_300) }
        val g = (b.eleM - a.eleM) / (b.distM - a.distM) * 100
        assertEquals(MAX_LEG_GRADE_PCT, g, 1.0)
        assertTrue(describeGeneratedProfile(p).any { "gekappt" in it })
    }

    @Test
    fun `gleicher Samen gleiche Strecke, anderer Samen andere`() {
        val a = generatedIndoorRoute(intervals)
        val b = generatedIndoorRoute(intervals)
        assertEquals(a, b)
        val c = generatedIndoorRoute(intervals.copy(seed = intervals.seed + 1))
        assertNotEquals(a.id, c.id)
        assertNotEquals(a.points.map { it.lat to it.lon }, c.points.map { it.lat to it.lon })
    }

    @Test
    fun `Linie ohne Selbstannaeherung und mit sinnvollen Radien`() {
        for (seed in 1L..12L) {
            val p = GeneratedProfile(seed, listOf(Leg(2_000.0, 0.0, Kind.FLAT), Leg(20_000.0, 0.0, Kind.ROLLING), Leg(4_000.0, 7.0, Kind.CLIMB), Leg(4_000.0, -7.0, Kind.DESCENT)))
            val plan = planGeneratedRoute(p)
            assertEquals(0, conflicts(plan.e, plan.n, plan.dist), "Samen $seed")
            // Kruemmung: ausserhalb von Serpentinen kein Radius unter 50 m (ueber 20 m gemessen)
            for (i in 4 until plan.count - 4 step 2) {
                val s = plan.dist[i]
                if (plan.serpentines.any { s > it[0] - 30 && s < it[1] + 30 }) continue
                val h1 = kotlin.math.atan2(plan.n[i] - plan.n[i - 4], plan.e[i] - plan.e[i - 4])
                val h2 = kotlin.math.atan2(plan.n[i + 4] - plan.n[i], plan.e[i + 4] - plan.e[i])
                val turn = abs(wrapAngle(h2 - h1))
                assertTrue(turn / 20.0 <= 1 / 50.0 + 1e-9, "Samen $seed: Radius ${20.0 / turn} m bei $s")
            }
        }
    }

    @Test
    fun `steile Anstiege im Mittelgebirge bekommen Serpentinen`() {
        var found = 0
        for (seed in 1L..20L) {
            val p = GeneratedProfile(seed, listOf(Leg(1_000.0, 0.0, Kind.FLAT), Leg(3_000.0, 8.0, Kind.CLIMB), Leg(1_000.0, 0.0, Kind.FLAT)))
            val plan = planGeneratedRoute(p)
            if (plan.landscape == GenLandscape.MITTELGEBIRGE && plan.serpentines.isNotEmpty()) found++
        }
        assertTrue(found >= 3, "nur $found Profile mit Serpentinen")
    }

    @Test
    fun `verschiedene Samen ergeben beide Landschaftstypen`() {
        val types = (1L..30L).map { landscapeOf(intervals.copy(seed = it)) }.toSet()
        assertEquals(GenLandscape.entries.toSet(), types)
    }

    @Test
    fun `Gelaende liegt auf der Strecke`() {
        val scene = GeneratedScene(intervals)
        val p = scene.plan
        var worst = 0.0
        for (i in 0 until p.count step 7) worst = maxOf(worst, abs(scene.terrain.heightAt(p.e[i], p.n[i]) - p.ele[i]))
        assertTrue(worst < 1.0, "Gelaende weicht bis $worst m von der Strecke ab")
    }

    @Test
    fun `Voranschlag ohne Netz`() {
        val plan = generatedWorldPlan(intervals, IndoorQuality.LOW, "Test")
        assertTrue(plan.requests.isEmpty())
        assertTrue(plan.hosts.isEmpty())
        assertEquals(0L, plan.downloadBytes)
        assertEquals("Test", plan.route.name)
        assertEquals(plan.worldId, generatedWorldPlan(intervals, IndoorQuality.LOW).worldId)
        assertNotEquals(plan.worldId, generatedWorldPlan(intervals.copy(seed = 1), IndoorQuality.LOW).worldId)
    }

    @Test
    fun `gleiches Profil ergibt bytegleiche Welt, die die Pruefung besteht`() {
        val p = GeneratedProfile(21, listOf(Leg(800.0, 0.0, Kind.FLAT), Leg(1_200.0, 5.0, Kind.CLIMB), Leg(1_000.0, 0.0, Kind.ROLLING)))
        val plan = generatedWorldPlan(p, IndoorQuality.LOW)
        val tmp = Files.createTempDirectory("gen").toFile()
        try {
            val a = File(tmp, "a")
            val b = File(tmp, "b")
            val quiet = object : WorldBuildListener {
                override fun onProgress(phase: WorldBuildPhase, done: Int, total: Int) = Unit
            }
            val m = buildGeneratedImpl(plan, p, a, quiet, createdAtMs = 0L, threads = 2)
            buildGeneratedImpl(plan, p, b, quiet, createdAtMs = 0L, threads = 1)
            assertEquals(emptyList(), checkWorld(a))
            assertEquals(File(a, WorldManifest.FILE_NAME).readText(), File(b, WorldManifest.FILE_NAME).readText())
            for (t in m.tiles) assertContentEquals(File(a, t.file).readBytes(), File(b, t.file).readBytes(), t.id)
            assertTrue(m.attribution.any { "keine Geodaten" in it.text })
            assertTrue(m.tiles.none { it.aerial })
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `Karte hat Orte, Wege, Wald und Wasser`() {
        val scene = GeneratedScene(GeneratedProfile(6001, listOf(Leg(3_000.0, 0.0, Kind.FLAT), Leg(24_000.0, 0.0, Kind.ROLLING), Leg(3_000.0, 0.0, Kind.FLAT))))
        val map = scene.map
        assertTrue(map.buildings.size > 50, "${map.buildings.size} Gebaeude")
        assertTrue(map.roads.count { it.cls == "track" } >= 5)
        val o = de.trailscape.core.indoor.world.GeoOrigin(GEN_DESIGN_ORIGIN.lat, GEN_DESIGN_ORIGIN.lon, 0.0)
        val data = map.mapData(o)
        val areas = data.areas(-3_000.0, -3_000.0, 3_000.0, 3_000.0)
        assertTrue(areas.any { it.kind == de.trailscape.core.indoor.world.LandClass.FOREST })
        assertTrue(areas.any { it.kind == de.trailscape.core.indoor.world.LandClass.FARMLAND })
        val v = scene.plan.villages.first()
        val vi = scene.plan.indexAt((v[0] + v[1]) / 2)
        val ve = scene.plan.e[vi]
        val vn = scene.plan.n[vi]
        assertTrue(data.areas(ve - 50, vn - 50, ve + 50, vn + 50).any { it.kind == de.trailscape.core.indoor.world.LandClass.RESIDENTIAL })
        // kein Haus auf der Strecke
        val p = scene.plan
        for (b in map.buildings) for (k in 0 until b.rings[0].size / 2) {
            assertTrue(scene.terrain.distanceToRoute(b.rings[0][2 * k], b.rings[0][2 * k + 1]) >= 7.0)
        }
        assertTrue(p.villages.isNotEmpty())
    }
}
