package de.trailscape.core.indoor.catalog

import de.trailscape.core.indoor.IndoorRouteSource
import de.trailscape.core.indoor.SurfaceKind
import de.trailscape.core.indoor.SurfaceSection
import de.trailscape.core.indoor.world.GeoOrigin
import de.trailscape.core.indoor.world.GeoSourceKind
import de.trailscape.core.indoor.world.IndoorQuality
import de.trailscape.core.indoor.world.ShowInterval
import de.trailscape.core.indoor.world.WorldAttribution
import de.trailscape.core.indoor.world.WorldBounds
import de.trailscape.core.indoor.world.WorldBudget
import de.trailscape.core.indoor.world.WorldLevel
import de.trailscape.core.indoor.world.WorldManifest
import de.trailscape.core.indoor.world.WorldTile
import de.trailscape.core.indoor.world.WorldTrack
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IndoorCatalogTest {
    private val tmp: File = Files.createTempDirectory("catalog-test").toFile()

    @AfterTest
    fun cleanup() {
        tmp.deleteRecursively()
    }

    private fun entry(id: String = "alpe-dhuez", version: Int = 1, sha: String? = "a".repeat(64)) = CatalogEntry(
        id = id,
        version = version,
        worldId = "w-$id",
        name = CatalogText("Alpe d'Huez", "Alpe d'Huez"),
        region = CatalogText("Isère, Französische Alpen", "Isère, French Alps"),
        country = "FR",
        kind = CatalogRideKind.ROAD,
        lengthM = 13_812.4,
        ascentM = 1_101.6,
        surfaces = mapOf(SurfaceKind.ASPHALT to 0.97, SurfaceKind.UNKNOWN to 0.03),
        description = CatalogText("21 Kehren", "21 hairpins"),
        packageFile = "$id-v$version.zip",
        packageBytes = 123_456_789L,
        sha256 = sha,
        aerial = true,
        aerialSource = "IGN",
        quality = IndoorQuality.MEDIUM,
    )

    @Test
    fun katalogRundreise() {
        val c = IndoorCatalog(IndoorCatalog.DEFAULT_BASE_URL, 42L, listOf(entry(), entry("sa-calobra", sha = null)))
        val back = IndoorCatalog.parse(c.toJson())
        assertEquals(2, back.entries.size)
        val e = back.entry("alpe-dhuez")!!
        assertEquals(13_812.0, e.lengthM)
        assertEquals(1_102.0, e.ascentM)
        assertEquals("21 hairpins", e.description.pick("en-GB"))
        assertEquals("21 Kehren", e.description.pick("de"))
        assertEquals(0.97, e.surfaces[SurfaceKind.ASPHALT])
        assertEquals(123_456_789L, e.packageBytes)
        assertNull(back.entry("sa-calobra")!!.sha256)
        assertEquals(IndoorCatalog.DEFAULT_BASE_URL + "alpe-dhuez-v1.zip", back.packageUrl(e))
        assertEquals(IndoorCatalog.DEFAULT_BASE_URL + "catalog.json", back.catalogUrl())
    }

    @Test
    fun kaputteEintraegeWerdenUebersprungenKaputterKatalogWirft() {
        val text = """{"format":1,"entries":[{"id":"../x","version":1,"worldId":"w","lengthM":1,"packageFile":"x.zip"},
            |{"id":"ok","version":2,"worldId":"w-ok","lengthM":5000,"packageFile":"ok-v2.zip","future":"egal"}]}""".trimMargin()
        val c = IndoorCatalog.parse(text)
        assertEquals(listOf("ok"), c.entries.map { it.id })
        assertEquals(IndoorCatalog.DEFAULT_BASE_URL, c.baseUrl)
        assertFailsWith<IllegalArgumentException> { IndoorCatalog.parse("nicht json") }
        assertFailsWith<IllegalArgumentException> { IndoorCatalog.parse("""{"format":9,"entries":[]}""") }
        assertFailsWith<IllegalArgumentException> { IndoorCatalog.parse("""{"format":1,"baseUrl":"https://evil.example/","entries":[]}""") }
    }

    @Test
    fun releaseKatalogGewinntAdresseBleibt() {
        val bundled = IndoorCatalog(IndoorCatalog.DEFAULT_BASE_URL, 1, listOf(entry(sha = null), entry("arenberg", sha = null)))
        val remote = IndoorCatalog(IndoorCatalog.DEFAULT_BASE_URL, 5, listOf(entry(version = 2), entry("neu")))
        val m = bundled.mergedWith(remote)
        assertEquals(listOf("alpe-dhuez", "arenberg", "neu"), m.entries.map { it.id })
        assertEquals(2, m.entry("alpe-dhuez")!!.version)
        assertEquals("a".repeat(64), m.entry("alpe-dhuez")!!.sha256)
        assertNull(m.entry("arenberg")!!.sha256)
        assertEquals(5, m.generatedAtMs)
    }

    @Test
    fun positivlisteDesKatalogs() {
        assertTrue(isAllowedCatalogUrl("https://github.com/robinrehbein/trailscape/releases/download/indoor-worlds-v1/a.zip"))
        assertTrue(isAllowedCatalogUrl("https://release-assets.githubusercontent.com/github-production-release-asset/1/2?sig=x"))
        assertFalse(isAllowedCatalogUrl("http://github.com/robinrehbein/trailscape/releases/download/x"))
        assertFalse(isAllowedCatalogUrl("https://github.com/someone/else/releases/download/x"))
        assertFalse(isAllowedCatalogUrl("https://github.com:8443/robinrehbein/trailscape/releases/download/x"))
        assertFalse(isAllowedCatalogUrl("https://user@github.com/robinrehbein/trailscape/releases/download/x"))
        assertFalse(isAllowedCatalogUrl("https://example.com/robinrehbein/trailscape/releases/download/x"))
    }

    // -----------------------------------------------------------------------
    // Paket
    // -----------------------------------------------------------------------

    private fun fakeWorld(dir: File, worldId: String = "w-alpe-dhuez"): File {
        File(dir, "tiles").mkdirs()
        val tiles = listOf("l0_0_0", "l0_1_0").mapIndexed { i, name ->
            val f = File(dir, "tiles/$name.glb")
            f.writeBytes(ByteArray(5000 + i * 777) { (it * 31 + i).toByte() })
            WorldTile(name, 0, i, 0, "tiles/$name.glb", f.length(), 0.0, 0.0, 0.0, 0.0, 1.0, listOf(ShowInterval(0.0, 1000.0)), 10, 10)
        }
        File(dir, "build").mkdirs()
        File(dir, "build/zwischenstand.json").writeText("{}")
        val manifest = WorldManifest(
            worldId = worldId, builder = "test", createdAtMs = 0, quality = IndoorQuality.MEDIUM,
            routeId = "r", routeName = "Alpe d'Huez", origin = GeoOrigin(45.0, 6.0, 700.0),
            bounds = WorldBounds(-10.0, -10.0, 10.0, 10.0, 0.0, 1.0),
            track = WorldTrack(
                10.0, 5.0, false, floatArrayOf(0f, 5f, 10f), floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 0f, 0f),
                listOf(SurfaceSection(0.0, 10.0, SurfaceKind.ASPHALT)), IndoorRouteSource.BROUTER_WAY_TAGS,
            ),
            levels = listOf(WorldLevel(0, 256.0, 512)),
            tiles = tiles,
            budget = WorldBudget(20, 20, 10_000),
            attribution = listOf(WorldAttribution(GeoSourceKind.VECTOR, "Test", "–", "https://example.invalid")),
        )
        File(dir, WorldManifest.FILE_NAME).writeText(manifest.toJson())
        return dir
    }

    @Test
    fun paketRundreiseUndStabil() {
        val world = fakeWorld(File(tmp, "welt"))
        val a = File(tmp, "a.zip")
        val b = File(tmp, "b.zip")
        val info = writeCatalogPackage(world, "alpe-dhuez", 3, a)
        Thread.sleep(20)
        writeCatalogPackage(world, "alpe-dhuez", 3, b)
        assertContentEquals(a.readBytes(), b.readBytes(), "gleiche Welt → bytegleiches Paket")
        assertEquals(sha256Hex(a), sha256Hex(b))
        ZipFile(a).use { z ->
            assertEquals(listOf("package.json", "manifest.json", "tiles/l0_0_0.glb", "tiles/l0_1_0.glb"), z.entries().asSequence().map { it.name }.toList())
        }
        assertEquals("w-alpe-dhuez", info.worldId)

        val out = File(tmp, "entpackt")
        val read = extractCatalogPackage(a, out, "alpe-dhuez", 3, "w-alpe-dhuez")
        assertEquals(info, read)
        assertContentEquals(File(world, "tiles/l0_1_0.glb").readBytes(), File(out, "tiles/l0_1_0.glb").readBytes())
        assertFalse(File(out, "build").exists())
        assertEquals("w-alpe-dhuez", WorldManifest.parse(File(out, "manifest.json").readText()).worldId)
    }

    @Test
    fun falscheKennungVersionOderWelt() {
        val a = File(tmp, "a.zip")
        writeCatalogPackage(fakeWorld(File(tmp, "welt")), "alpe-dhuez", 1, a)
        val out = File(tmp, "x")
        assertEquals(CatalogPackageException.Reason.INVALID, assertFailsWith<CatalogPackageException> { extractCatalogPackage(a, out, "arenberg") }.reason)
        assertFailsWith<CatalogPackageException> { extractCatalogPackage(a, out, "alpe-dhuez", expectedVersion = 2) }
        assertFailsWith<CatalogPackageException> { extractCatalogPackage(a, out, "alpe-dhuez", expectedWorldId = "anders") }
        assertFalse(out.exists(), "nach einem Fehler bleibt nichts liegen")
    }

    /** Schreibt ein ZIP von Hand (fuer Faelschungen). */
    private fun zipOf(file: File, entries: List<Pair<String, ByteArray>>) {
        ZipOutputStream(file.outputStream()).use { z ->
            entries.forEach { (n, b) ->
                z.putNextEntry(ZipEntry(n))
                z.write(b)
                z.closeEntry()
            }
        }
    }

    private fun entriesOf(zip: File): List<Pair<String, ByteArray>> = ZipFile(zip).use { z ->
        z.entries().asSequence().map { it.name to z.getInputStream(it).readBytes() }.toList()
    }

    @Test
    fun manipulierteKachelFaelltAufDiePruefsumme() {
        val a = File(tmp, "a.zip")
        writeCatalogPackage(fakeWorld(File(tmp, "welt")), "alpe-dhuez", 1, a)
        val tampered = entriesOf(a).map { (n, b) -> if (n == "tiles/l0_0_0.glb") n to b.copyOf().also { it[10] = (it[10] + 1).toByte() } else n to b }
        val bad = File(tmp, "bad.zip")
        zipOf(bad, tampered)
        val out = File(tmp, "x")
        val e = assertFailsWith<CatalogPackageException> { extractCatalogPackage(bad, out, "alpe-dhuez") }
        assertEquals(CatalogPackageException.Reason.CHECKSUM, e.reason)
        assertFalse(out.exists())
    }

    @Test
    fun fremdeOderUnsicherePfadeWerdenAbgelehnt() {
        val a = File(tmp, "a.zip")
        writeCatalogPackage(fakeWorld(File(tmp, "welt")), "alpe-dhuez", 1, a)
        val original = entriesOf(a)
        for (extra in listOf("../boese.glb", "tiles/../../x.glb", "/abs.glb", "lib/x.so", "tiles/extra.glb")) {
            val f = File(tmp, "e.zip")
            zipOf(f, original + (extra to byteArrayOf(1, 2, 3)))
            val e = assertFailsWith<CatalogPackageException>(extra) { extractCatalogPackage(f, File(tmp, "x"), "alpe-dhuez") }
            assertEquals(CatalogPackageException.Reason.INVALID, e.reason, extra)
        }
        assertFalse(File(tmp.parentFile, "boese.glb").exists())
        val noManifest = File(tmp, "n.zip")
        zipOf(noManifest, original.filter { it.first != "tiles/l0_1_0.glb" })
        assertFailsWith<CatalogPackageException> { extractCatalogPackage(noManifest, File(tmp, "x"), "alpe-dhuez") }
        val notZip = File(tmp, "kein.zip").also { it.writeText("hallo") }
        assertEquals(CatalogPackageException.Reason.INVALID, assertFailsWith<CatalogPackageException> { extractCatalogPackage(notZip, File(tmp, "x"), "alpe-dhuez") }.reason)
    }

    @Test
    fun sichereKennungen() {
        assertTrue(isSafeCatalogId("alpe-dhuez"))
        assertTrue(isSafeCatalogId("alpe-dhuez-v1"))
        assertFalse(isSafeCatalogId("Alpe"))
        assertFalse(isSafeCatalogId("a/b"))
        assertFalse(isSafeCatalogId("a..b"))
        assertFalse(isSafeCatalogId(""))
    }
}
