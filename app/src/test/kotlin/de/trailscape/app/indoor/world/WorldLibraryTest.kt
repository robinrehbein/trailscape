package de.trailscape.app.indoor.world

import de.trailscape.core.indoor.IndoorRouteSource
import de.trailscape.core.indoor.SurfaceKind
import de.trailscape.core.indoor.SurfaceSection
import de.trailscape.core.indoor.world.GeoOrigin
import de.trailscape.core.indoor.world.GeoSourceKind
import de.trailscape.core.indoor.world.IndoorQuality
import de.trailscape.core.indoor.world.WorldAttribution
import de.trailscape.core.indoor.world.WorldBounds
import de.trailscape.core.indoor.world.WorldBudget
import de.trailscape.core.indoor.world.WorldLevel
import de.trailscape.core.indoor.world.WorldManifest
import de.trailscape.core.indoor.world.WorldTrack
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorldLibraryTest {
    private lateinit var root: File
    private lateinit var worlds: File
    private lateinit var cache: File
    private lateinit var library: WorldLibrary

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("trailscape-worlds").toFile()
        worlds = File(root, "files/indoor/worlds").also { it.mkdirs() }
        cache = File(root, "cache/indoor/geo")
        library = WorldLibrary(worlds, cache)
    }

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun manifest(worldId: String, quality: IndoorQuality = IndoorQuality.MEDIUM, createdAtMs: Long = 1_000L) = WorldManifest(
        worldId = worldId,
        builder = "indoor-world/1",
        createdAtMs = createdAtMs,
        quality = quality,
        routeId = "route-hash",
        routeName = "Runde $worldId",
        origin = GeoOrigin(51.0, 7.0, 100.0),
        bounds = WorldBounds(-500.0, -500.0, 500.0, 500.0, -10.0, 40.0),
        track = WorldTrack(
            lengthM = 5.0,
            stepM = 5.0,
            loop = false,
            e = floatArrayOf(0f, 5f),
            n = floatArrayOf(0f, 0f),
            u = floatArrayOf(0f, 0.2f),
            surfaces = listOf(SurfaceSection(0.0, 5.0, SurfaceKind.UNKNOWN)),
            source = IndoorRouteSource.NONE,
        ),
        levels = listOf(WorldLevel(0, 256.0, 1024)),
        tiles = emptyList(),
        budget = WorldBudget(0L, 0, 0L),
        attribution = listOf(WorldAttribution(GeoSourceKind.VECTOR, "© OpenStreetMap", "ODbL", "https://osm.example/copyright")),
    )

    /** Legt eine fertige Welt mit [payload] Bytes Kachelinhalt an. */
    private fun completeWorld(
        id: String,
        rideId: String? = null,
        payload: Int = 100,
        quality: IndoorQuality = IndoorQuality.MEDIUM,
        createdAtMs: Long = 1_000L,
    ): File {
        val dir = File(worlds, id).also { File(it, "tiles").mkdirs() }
        File(dir, WorldManifest.FILE_NAME).writeText(manifest(id, quality, createdAtMs).toJson())
        File(dir, "tiles/L0_0_0.glb").writeBytes(ByteArray(payload))
        if (rideId != null) WorldLibrary.writeSource(dir, rideId, "Runde $id")
        return dir
    }

    @Test
    fun `leere Verzeichnisse ergeben leere Liste und Groesse 0`() {
        assertTrue(library.listWorlds().isEmpty())
        assertEquals(0L, library.totalBytes())
        assertEquals(0L, library.cacheBytes())
        assertTrue(WorldLibrary(File(root, "gibts-nicht"), File(root, "auch-nicht")).listWorlds().isEmpty())
    }

    @Test
    fun `fertige Welt wird mit Manifest, Groesse und Quelle gelistet`() {
        val dir = completeWorld("w1", rideId = "ride-1", payload = 1000)

        val info = assertNotNull(library.listWorlds().singleOrNull())

        assertEquals("w1", info.dirName)
        assertTrue(info.complete)
        assertEquals(IndoorQuality.MEDIUM, info.manifest?.quality)
        assertEquals(WorldLibrary.WorldSource("ride-1", "Runde w1"), info.source)
        val expected = File(dir, "manifest.json").length() + 1000 + File(dir, "app-source.json").length()
        assertEquals(expected, info.bytes)
        assertEquals(expected, library.sizeOf("w1"))
        assertEquals(expected, library.totalBytes())
    }

    @Test
    fun `Gesamtgroesse summiert alle Welten`() {
        completeWorld("a", payload = 500)
        completeWorld("b", payload = 700)

        val sum = library.listWorlds().sumOf { it.bytes }

        assertEquals(sum, library.totalBytes())
        assertTrue(sum > 1200)
    }

    @Test
    fun `angefangene Welt ohne Manifest wird erkannt`() {
        val dir = File(worlds, "halb").also { File(it, "tiles").mkdirs() }
        File(dir, "tiles/L0_0_0.glb").writeBytes(ByteArray(10))
        WorldLibrary.writeSource(dir, "ride-9", "Halbe Runde")

        val info = assertNotNull(library.get("halb"))

        assertFalse(info.complete)
        assertNull(info.manifest)
        assertEquals("ride-9", info.source?.rideId)
        val ui = library.toEntryUi(info, knownRideIds = setOf("ride-9"))
        assertFalse(ui.complete)
        assertEquals("Halbe Runde", ui.routeName)
        assertNull(ui.quality)
        assertNull(ui.createdAt)
        assertNull(library.findWorldForRide("ride-9"), "angefangene Welten sind nicht fahrbar")
    }

    @Test
    fun `unlesbares Manifest gilt als angefangen`() {
        val dir = File(worlds, "kaputt").also { it.mkdirs() }
        File(dir, WorldManifest.FILE_NAME).writeText("{ kein json")

        val info = assertNotNull(library.get("kaputt"))

        assertFalse(info.complete)
    }

    @Test
    fun `verwaist ist eine Welt, deren Tour fehlt`() {
        completeWorld("mit-tour", rideId = "ride-1")
        completeWorld("ohne-tour", rideId = "ride-weg")
        completeWorld("ohne-quelle")
        val known = setOf("ride-1")

        val byId = library.listWorlds().associateBy { it.dirName }

        assertFalse(library.isOrphaned(byId.getValue("mit-tour"), known))
        assertTrue(library.isOrphaned(byId.getValue("ohne-tour"), known))
        assertFalse(library.isOrphaned(byId.getValue("ohne-quelle"), known), "ohne Zuordnung nie verwaist")
        assertTrue(library.toEntryUi(byId.getValue("ohne-tour"), known).orphaned)
    }

    @Test
    fun `Testwelt ist nie verwaist`() {
        completeWorld(WorldLibrary.TEST_WORLD_ID, rideId = "irgendwas")

        val info = assertNotNull(library.get(WorldLibrary.TEST_WORLD_ID))

        assertTrue(info.isTestWorld)
        assertFalse(library.isOrphaned(info, emptySet()))
        assertEquals(File(worlds, "testwelt"), library.testWorldDir())
    }

    @Test
    fun `Eintrag der Oberflaeche traegt Manifest-Daten`() {
        completeWorld("w", rideId = "r", payload = 50, quality = IndoorQuality.HIGH, createdAtMs = 42_000L)
        val info = assertNotNull(library.get("w"))

        val ui = library.toEntryUi(info, setOf("r"))

        assertEquals("w", ui.worldId)
        assertEquals("Runde w", ui.routeName)
        assertEquals(IndoorQuality.HIGH, ui.quality)
        assertEquals(42_000L, ui.createdAt)
        assertEquals(info.bytes, ui.bytes)
        assertEquals(1, ui.attribution.size)
        assertTrue(ui.complete)
        assertFalse(ui.orphaned)
    }

    @Test
    fun `loeschen entfernt nur die gewaehlte Welt`() {
        completeWorld("a")
        completeWorld("b")

        assertTrue(library.delete("a"))

        assertFalse(File(worlds, "a").exists())
        assertTrue(File(worlds, "b").exists())
        assertTrue(library.delete("a"), "bereits Geloeschtes gilt als erledigt")
    }

    @Test
    fun `loeschen verlaesst das Weltverzeichnis nie`() {
        val outside = File(root, "files/indoor/wichtig").also { it.mkdirs() }
        File(outside, "datei.txt").writeText("bleibt")
        completeWorld("a")

        assertFalse(library.delete(".."))
        assertFalse(library.delete("../wichtig"))
        assertFalse(library.delete("a/../../wichtig"))
        assertFalse(library.delete(""))
        assertFalse(library.delete("."))

        assertTrue(File(outside, "datei.txt").exists())
        assertTrue(File(worlds, "a").exists())
        assertFailsWith<IllegalArgumentException> { library.worldDirFor("../wichtig") }
    }

    @Test
    fun `ein Symlink im Weltverzeichnis loescht sein Ziel nicht`() {
        val outside = File(root, "ziel").also { it.mkdirs() }
        File(outside, "datei.txt").writeText("bleibt")
        val link = File(worlds, "link")
        val created = runCatching { Files.createSymbolicLink(link.toPath(), outside.toPath()) }.isSuccess
        if (!created) return // Dateisystem ohne Symlinks

        library.delete("link")

        assertTrue(File(outside, "datei.txt").exists())
    }

    @Test
    fun `Zwischenspeicher wird gemessen und geleert`() {
        File(cache, "elevation/ab").mkdirs()
        File(cache, "elevation/ab/tile").writeBytes(ByteArray(300))
        File(cache, "vector").mkdirs()
        File(cache, "vector/x").writeBytes(ByteArray(200))
        assertEquals(500L, library.cacheBytes())

        assertTrue(library.clearCache())

        assertEquals(0L, library.cacheBytes())
        assertTrue(cache.exists(), "das Verzeichnis selbst bleibt")
    }

    @Test
    fun `Zwischenspeicher leeren beruehrt die Welten nicht`() {
        completeWorld("w")
        File(cache, "x").also { it.parentFile.mkdirs() }.writeBytes(ByteArray(10))

        library.clearCache()

        assertTrue(File(worlds, "w/manifest.json").exists())
    }

    @Test
    fun `worldDirFor liegt unter dem Weltverzeichnis`() {
        assertEquals(File(worlds, "abc123"), library.worldDirFor("abc123"))
    }

    @Test
    fun `Quelle schreiben und lesen`() {
        val dir = File(worlds, "q")

        WorldLibrary.writeSource(dir, "ride-7", "Elbrunde \"schön\"")

        assertEquals(WorldLibrary.WorldSource("ride-7", "Elbrunde \"schön\""), WorldLibrary.readSource(dir))
        assertNull(WorldLibrary.readSource(File(worlds, "nichts")))
        File(dir, WorldLibrary.SOURCE_FILE).writeText("kaputt")
        assertNull(WorldLibrary.readSource(dir))
    }

    @Test
    fun `findWorldForRide findet die neueste fertige Welt, optional je Qualitaet`() {
        completeWorld("alt", rideId = "r1", quality = IndoorQuality.MEDIUM, createdAtMs = 1_000L)
        completeWorld("neu", rideId = "r1", quality = IndoorQuality.MEDIUM, createdAtMs = 2_000L)
        completeWorld("hoch", rideId = "r1", quality = IndoorQuality.HIGH, createdAtMs = 500L)
        completeWorld("fremd", rideId = "r2")

        assertEquals("neu", library.findWorldForRide("r1")?.dirName)
        assertEquals("neu", library.findWorldForRide("r1", IndoorQuality.MEDIUM)?.dirName)
        assertEquals("hoch", library.findWorldForRide("r1", IndoorQuality.HIGH)?.dirName)
        assertNull(library.findWorldForRide("r1", IndoorQuality.LOW))
        assertNull(library.findWorldForRide("gibts-nicht"))
    }
}
