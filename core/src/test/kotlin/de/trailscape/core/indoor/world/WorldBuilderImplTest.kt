package de.trailscape.core.indoor.world

import de.trailscape.core.indoor.IndoorRoutePoint
import org.junit.jupiter.api.Timeout
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Harte Zeitgrenze je Test, damit ein Fehler nie die Gradle-Sperre haelt. */
@Timeout(value = 240, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class WorldBuilderImplTest {
    private val builder = WorldBuilderImpl(SimpleWorldParts, aerialRegister = listOf(TEST_AERIAL), clock = { 1_700_000_000_000L })
    private val quality = IndoorQuality.LOW

    @Test
    fun `baut eine kurze Welt mit Luftbild, die die Pruefung besteht, Fortschritt monoton je Phase`() {
        val geo = FakeGeo()
        val plan = builder.plan(shortRoute(), quality)
        assertEquals(listOf(TEST_AERIAL), plan.aerial)
        assertTrue(GeoEndpoints.ELEVATION_HOST in plan.hosts && GeoEndpoints.VECTOR_HOST in plan.hosts && "aerial.example.org" in plan.hosts, "${plan.hosts}")
        assertTrue(plan.tileCount > 20 && plan.downloadBytes > 0 && plan.diskBytes > 0)
        assertEquals(0, geo.count(GeoSourceKind.ELEVATION), "plan darf nichts abrufen")

        val dir = tempDir("welt-kurz")
        val listener = RecordingListener()
        val m = builder.build(plan, fakeDeps(geo), dir, listener)
        assertEquals(emptyList(), checkWorld(dir))
        assertEquals(plan.worldId, m.worldId)
        assertEquals(WORLD_BUILDER_ID, m.builder)
        assertEquals(1_700_000_000_000L, m.createdAtMs)
        assertFalse(File(dir, WorldBuilderImpl.STATE_DIR).exists(), "build/ muss nach dem Bau weg sein")
        assertTrue(m.tiles.all { it.aerial }, "alle Kacheln mit Luftbild")
        assertTrue(m.tiles.none { it.degraded })
        assertEquals(m.tiles.size, geo.count(GeoSourceKind.AERIAL))
        val sources = m.attribution.map { it.source }.toSet()
        assertEquals(setOf(GeoSourceKind.ELEVATION, GeoSourceKind.VECTOR, GeoSourceKind.AERIAL), sources)
        assertTrue(m.attribution.any { it.text == TEST_AERIAL.attribution && it.license == TEST_AERIAL.license })
        // Belag aus den Vektorkacheln geschaetzt
        assertEquals(de.trailscape.core.indoor.IndoorRouteSource.VECTOR_TILES, m.track.source)
        assertTrue(m.track.surfaces.any { it.kind != de.trailscape.core.indoor.SurfaceKind.UNKNOWN })
        // Laufmeter unangetastet
        assertEquals(plan.route.lengthM, m.track.lengthM)
        // Anker auf 1 cm gerundet
        assertTrue(m.tiles.all { Math.abs(it.anchorU * 100 - Math.round(it.anchorU * 100)) < 1e-6 })

        val phases = listener.events.map { it.first }.distinct()
        assertEquals(WorldBuildPhase.entries.toList(), phases, "Phasen in Reihenfolge")
        for (phase in WorldBuildPhase.entries) {
            val ev = listener.events.filter { it.first == phase }
            for (k in 1 until ev.size) assertTrue(ev[k].second >= ev[k - 1].second, "$phase: ${ev[k - 1]} → ${ev[k]}")
            assertTrue(ev.all { it.second <= it.third }, "$phase: done ≤ total")
            assertEquals(ev.last().third, ev.last().second, "$phase endet vollstaendig")
        }
    }

    @Test
    fun `gleicher Plan ergibt dieselbe worldId`() {
        val a = builder.plan(shortRoute(), quality)
        val b = builder.plan(shortRoute(), quality)
        assertEquals(a.worldId, b.worldId)
        assertEquals(a.tileCount, b.tileCount)
        assertNotEquals(a.worldId, builder.plan(shortRoute(), IndoorQuality.MEDIUM).worldId)
        assertNotEquals(a.worldId, builder.plan(shortRoute(1_200.0), quality).worldId)
    }

    @Test
    fun `Abbruch hinterlaesst kein Manifest, der zweite Lauf setzt fort`() {
        val geo = FakeGeo()
        val plan = builder.plan(shortRoute(), quality)
        val dir = tempDir("welt-abbruch")
        val first = RecordingListener { phase, done, _ -> phase == WorldBuildPhase.MESHING && done >= 6 }
        assertFailsWith<WorldBuildCancelled> { builder.build(plan, fakeDeps(geo), dir, first) }
        assertFalse(File(dir, WorldManifest.FILE_NAME).exists())
        val finished = File(dir, "${WorldBuilderImpl.STATE_DIR}/tiles").listFiles { f -> f.name.endsWith(".json") }!!.size
        assertTrue(finished >= 6, "fertige Kacheln: $finished")
        val glbs = File(dir, WorldManifest.TILES_DIR).listFiles { f -> f.name.endsWith(".glb") }!!.size
        assertEquals(finished, glbs, "jede fertige Kachel hat GLB und Kennzahlen")

        geo.reset()
        val second = RecordingListener()
        val m = builder.build(plan, fakeDeps(geo), dir, second)
        assertEquals(emptyList(), checkWorld(dir))
        assertEquals(m.tiles.size - finished, geo.count(GeoSourceKind.AERIAL), "fertige Kacheln holen kein Luftbild mehr")
        val aerialStart = second.events.first { it.first == WorldBuildPhase.AERIAL }
        assertEquals(finished, aerialStart.second, "Fortschritt beginnt bei den fertigen Kacheln")
    }

    @Test
    fun `fehlende Hoehen ergeben OFFLINE bzw SOURCE_ERROR`() {
        val plan = builder.plan(shortRoute(), quality)
        val offline = assertFailsWith<WorldBuildFailed> {
            builder.build(plan, fakeDeps(FakeGeo(elevation = FakeGeo.Mode.IO_ERROR)), tempDir("welt-offline"), RecordingListener())
        }
        assertEquals(WorldBuildFailed.Reason.OFFLINE, offline.reason)
        val broken = assertFailsWith<WorldBuildFailed> {
            builder.build(plan, fakeDeps(FakeGeo(elevation = FakeGeo.Mode.HTTP_ERROR)), tempDir("welt-quelle"), RecordingListener())
        }
        assertEquals(WorldBuildFailed.Reason.SOURCE_ERROR, broken.reason)
    }

    @Test
    fun `fehlende Vektorkachel macht betroffene Kacheln degraded, ohne Luftbild ist aerial false`() {
        val geo = FakeGeo(aerial = FakeGeo.Mode.BLANK, missingVector = setOf("14/8516/5480"))
        val plan = builder.plan(shortRoute(), quality)
        val dir = tempDir("welt-luecken")
        val m = builder.build(plan, fakeDeps(geo), dir, RecordingListener())
        assertEquals(emptyList(), checkWorld(dir))
        assertTrue(m.tiles.none { it.aerial }, "Leerbild = kein Luftbild")
        assertTrue(m.attribution.none { it.source == GeoSourceKind.AERIAL })
        val degraded = m.tiles.filter { it.degraded }
        assertTrue(degraded.isNotEmpty(), "Kacheln ueber 14/8516/5480 sind unvollstaendig")
        assertTrue(degraded.all { it.level <= 1 }, "Stufe 2 nutzt z12")
        assertTrue(m.tiles.any { !it.degraded && it.level <= 1 })

        // Plan ohne Luftbild-Dienste: kein einziger Luftbild-Abruf
        val geo2 = FakeGeo()
        val m2 = builder.build(plan.copy(aerial = emptyList()), fakeDeps(geo2), tempDir("welt-ohne"), RecordingListener())
        assertEquals(0, geo2.count(GeoSourceKind.AERIAL))
        assertTrue(m2.tiles.none { it.aerial })
    }

    @Test
    fun `zu lange und zu kurze Strecken werden abgelehnt`() {
        val long = shortRoute().let { r ->
            r.copy(points = listOf(r.points.first(), IndoorRoutePoint(MAX_WORLD_ROUTE_M + 1, r.points.last().lat, r.points.last().lon, 100.0)))
        }
        assertEquals(WorldBuildFailed.Reason.ROUTE_UNSUPPORTED, assertFailsWith<WorldBuildFailed> { builder.plan(long, quality) }.reason)
        val short = shortRoute(150.0)
        assertEquals(WorldBuildFailed.Reason.ROUTE_UNSUPPORTED, assertFailsWith<WorldBuildFailed> { builder.plan(short, quality) }.reason)
        val single = shortRoute().let { it.copy(points = it.points.take(1)) }
        assertEquals(WorldBuildFailed.Reason.ROUTE_UNSUPPORTED, assertFailsWith<WorldBuildFailed> { builder.plan(single, quality) }.reason)
    }
}
