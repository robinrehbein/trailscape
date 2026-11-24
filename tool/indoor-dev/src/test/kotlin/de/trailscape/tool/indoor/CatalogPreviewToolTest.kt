package de.trailscape.tool.indoor

import de.trailscape.core.indoor.SurfaceKind
import de.trailscape.core.indoor.catalog.CatalogEntry
import de.trailscape.core.indoor.catalog.CatalogRideKind
import de.trailscape.core.indoor.catalog.CatalogText
import de.trailscape.core.indoor.catalog.IndoorCatalog
import de.trailscape.core.indoor.catalog.writeCatalogPackage
import de.trailscape.core.indoor.world.IndoorQuality
import de.trailscape.core.indoor.world.createWorldBuilder
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Timeout

/** `catalog-preview`: Welt aus dem Paket, Plan fuer den Betrachter, Profil und Bild in den Katalog. */
@Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class CatalogPreviewToolTest {
    private val tmp: File = Files.createTempDirectory("catalog-preview").toFile()

    @AfterTest
    fun cleanup() {
        tmp.deleteRecursively()
    }

    private fun jpeg(file: File, w: Int, h: Int) {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until w) for (y in 0 until h) img.setRGB(x, y, (x * 255 / w shl 16) or (y * 255 / h shl 8) or 0x40)
        file.parentFile.mkdirs()
        check(ImageIO.write(img, "jpg", file))
    }

    @Test
    fun vorschauAusDemPaketUndBildInDenKatalog() {
        val world = File(tmp, "bau")
        val m = createWorldBuilder().buildTestWorld(world, IndoorQuality.LOW)
        val out = File(tmp, "out")
        writeCatalogPackage(world, "testrunde", 3, File(out, "packages/testrunde-v3.zip"))
        val entry = CatalogEntry(
            id = "testrunde", version = 3, worldId = m.worldId,
            name = CatalogText("Testrunde", "Test loop"), region = CatalogText("", ""), country = "DE",
            kind = CatalogRideKind.GRAVEL, lengthM = m.track.lengthM, ascentM = 10.0,
            surfaces = mapOf(SurfaceKind.GRAVEL to 1.0), description = CatalogText("", ""),
            packageFile = "testrunde-v3.zip", packageBytes = 1, sha256 = "c".repeat(64), aerial = false,
        )
        val other = entry.copy(id = "ohne-welt", worldId = "w-anders", packageFile = "ohne-welt-v3.zip")
        File(out, "catalog.json").writeText(IndoorCatalog(IndoorCatalog.DEFAULT_BASE_URL, 1, listOf(entry, other)).toJson())

        // 1. Vorbereiten: Welt aus dem Paket, Plan fuer shots.mjs.
        assertEquals(0, catalogPreview(out, null, prepare = true, spots = mapOf("testrunde" to 0.5)))
        val plan = File(out, "previews/plan.tsv").readLines().filter { it.isNotBlank() }
        assertEquals(1, plan.size, "ohne Welt und Paket kein Bild")
        val cols = plan.single().split('\t')
        assertEquals(listOf("testrunde", "3"), cols.take(2))
        assertTrue(File(cols[2], "manifest.json").isFile, "Welt entpackt")
        assertEquals(500L, cols[3].toLong())

        // 2. Ohne Bild: Profil und Laser-Kennzeichen, aber keine Vorschau.
        assertEquals(0, catalogPreview(out, null, prepare = false))
        var c = IndoorCatalog.parse(File(out, "catalog.json").readText())
        val p = assertNotNull(c.entry("testrunde")!!.profile)
        assertEquals(m.track.lengthM, p.lengthM, 1e-6)
        assertEquals(m.track.u.first().toDouble(), p.elevM.first(), 0.06)
        assertFalse(c.entry("testrunde")!!.laser, "Testwelt ohne Laser-Quelle")
        assertNull(c.entry("testrunde")!!.preview)
        assertNull(c.entry("ohne-welt")!!.profile)

        // 3. Mit Bild (wie es shots.mjs ablegt): Groesse, Pruefsumme, Kopie zum Hochladen.
        jpeg(File(out, "previews/testrunde-v3.jpg"), 640, 360)
        assertEquals(0, catalogPreview(out, null, prepare = false))
        c = IndoorCatalog.parse(File(out, "catalog.json").readText())
        val v = assertNotNull(c.entry("testrunde")!!.preview)
        assertEquals("testrunde-v3.jpg", v.file)
        assertEquals(640 to 360, v.width to v.height)
        assertEquals(File(out, "previews/testrunde-v3.jpg").length(), v.bytes)
        assertTrue(File(out, "packages/testrunde-v3.jpg").isFile)
        // Bleibt bei einem spaeteren Lauf ohne Bild erhalten, solange die Version gleich ist.
        File(out, "previews/testrunde-v3.jpg").delete()
        assertEquals(0, catalogPreview(out, null, prepare = false))
        assertEquals("testrunde-v3.jpg", IndoorCatalog.parse(File(out, "catalog.json").readText()).entry("testrunde")!!.preview?.file)
    }

    @Test
    fun jpegGroesseUndAblehnung() {
        val f = File(tmp, "a.jpg")
        jpeg(f, 321, 123)
        assertEquals(321 to 123, jpegSize(f.readBytes()))
        assertNull(jpegSize(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())))
        assertEquals(3_000.0, previewDistM("x", 10_000.0, mapOf("x" to 3.0)))
        assertEquals(4_500.0, previewDistM("y", 10_000.0, emptyMap()))
        assertEquals(9_950.0, previewDistM("x", 10_000.0, mapOf("x" to 30.0)), "nie hinter dem Ziel")
    }

    @Test
    fun stellenDateiIstLesbar() {
        val spots = readPreviewSpots(File("../../$CATALOG_PREVIEWS"))
        assertEquals(7.6, spots["alpe-dhuez"])
        assertTrue(spots.size >= 10)
    }
}
