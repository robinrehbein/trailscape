package de.trailscape.core.indoor.catalog

import de.trailscape.core.indoor.SurfaceKind
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Vorschau im Katalog (Laser-Kennzeichen, Hoehenprofil, Vorschaubild) und
 * der taegliche Abruf (docs/indoor/catalog.md, „Vorschau“).
 */
class CatalogPreviewFormatTest {

    private fun fixture(): String =
        requireNotNull(javaClass.getResource("/indoor/catalog/catalog-neue-felder.json")).readText()

    private fun entry(id: String = "alpe-dhuez") = CatalogEntry(
        id = id,
        version = 3,
        worldId = "w-$id",
        name = CatalogText("Alpe d'Huez", "Alpe d'Huez"),
        region = CatalogText("Isère", "Isère"),
        country = "FR",
        kind = CatalogRideKind.ROAD,
        lengthM = 15_000.0,
        ascentM = 1_100.0,
        surfaces = mapOf(SurfaceKind.ASPHALT to 1.0),
        description = CatalogText("21 Kehren", "21 hairpins"),
        packageFile = "$id-v3.zip",
        packageBytes = 1_000L,
        sha256 = null,
        aerial = true,
    )

    /**
     * Die neuen Felder und kuenftige, unbekannte Felder (oben, im Eintrag,
     * verschachtelt, als Liste, unbekannter Belag) duerfen keinen Eintrag
     * kosten — so liest auch eine aeltere App den neuen Katalog: Sie kennt
     * `laser`, `profile`, `preview` nicht und ueberspringt sie genauso.
     * (Gegen den Leser vor dieser Aenderung zusaetzlich einmal von Hand
     * geprueft, docs/indoor/catalog.md.)
     */
    @Test
    fun aelterLeserUeberspringtNeueFelder() {
        val c = IndoorCatalog.parse(fixture())
        assertEquals(listOf("kahler-asten", "monte-sante-marie"), c.entries.map { it.id })
        val k = c.entry("kahler-asten")!!
        assertEquals(10, k.version)
        assertEquals(110_983_979L, k.packageBytes)
        assertEquals(setOf(SurfaceKind.ASPHALT, SurfaceKind.GRAVEL, SurfaceKind.COMPACTED), k.surfaces.keys)
        assertTrue(k.laser)
        val p = assertNotNull(k.profile)
        assertEquals(6, p.elevM.size)
        assertEquals(13_200.0, p.lengthM, 1e-6)
        assertEquals(612.4, p.elevM.first())
        val v = assertNotNull(k.preview)
        assertEquals("kahler-asten-v10.jpg", v.file)
        assertEquals(640, v.width)
        assertEquals(IndoorCatalog.DEFAULT_BASE_URL + "kahler-asten-v10.jpg", c.previewUrl(k))

        // Kaputtes Profil und unsicherer Bildname kosten nur die Vorschau, nicht den Eintrag.
        val m = c.entry("monte-sante-marie")!!
        assertNull(m.profile)
        assertNull(m.preview)
        assertNull(c.previewUrl(m))
        assertFalse(m.laser)
        assertEquals(82_838_731L, m.packageBytes)
    }

    @Test
    fun neueFelderRundreiseUndOhneSieUnveraendert() {
        val plain = entry()
        // Ohne die neuen Felder schreibt toJson genau das bisherige Format.
        val plainJson = plain.toJson()
        assertFalse("laser" in plainJson || "profile" in plainJson || "preview" in plainJson)

        val rich = plain.copy(
            laser = true,
            profile = CatalogProfile(7_500.0, listOf(720.04, 1_234.56, 1_820.0)),
            preview = CatalogPreview("alpe-dhuez-v3.jpg", 87_654, "b".repeat(64), 640, 360),
        )
        val back = IndoorCatalog.parse(IndoorCatalog(IndoorCatalog.DEFAULT_BASE_URL, 1, listOf(rich)).toJson()).entries.single()
        assertTrue(back.laser)
        assertEquals(listOf(720.0, 1_234.6, 1_820.0), back.profile!!.elevM)
        assertEquals(rich.preview, back.preview)
        // Release-Katalog gewinnt, auch mit seinen Vorschaufeldern.
        val merged = IndoorCatalog(IndoorCatalog.DEFAULT_BASE_URL, 0, listOf(plain))
            .mergedWith(IndoorCatalog(IndoorCatalog.DEFAULT_BASE_URL, 2, listOf(back)))
        assertEquals("alpe-dhuez-v3.jpg", merged.entries.single().preview?.file)
    }

    @Test
    fun vorschauAblehnen() {
        fun bad(block: () -> Unit) = assertTrue(runCatching(block).isFailure)
        bad { CatalogPreview("x.png", 10, null) }
        bad { CatalogPreview("../x.jpg", 10, null) }
        bad { CatalogPreview("x.jpg", 0, null) }
        bad { CatalogPreview("x.jpg", CatalogPreview.MAX_BYTES + 1, null) }
        bad { CatalogPreview("x.jpg", 10, "nicht-hex") }
        bad { CatalogProfile(100.0, listOf(1.0)) }
        bad { CatalogProfile(0.0, listOf(1.0, 2.0)) }
        bad { CatalogProfile(100.0, listOf(1.0, Double.NaN)) }
    }

    @Test
    fun profilWirdGleichmaessigAusgeduennt() {
        // 10-m-Band ueber 15 115 m, lineare Steigung 8 % ab 720 m.
        val length = 15_115.0
        val count = (length / 10).toInt() + 2
        fun dist(i: Int) = minOf(i * 10.0, length)
        val p = assertNotNull(CatalogProfile.thinned(length, count, ::dist, { 720.0 + dist(it) * 0.08 }))
        assertEquals(CatalogProfile.DEFAULT_POINTS, p.elevM.size)
        assertEquals(length, p.lengthM, 1e-6)
        assertEquals(720.0, p.elevM.first(), 1e-9)
        assertEquals(720.0 + length * 0.08, p.elevM.last(), 1e-6)
        assertEquals(720.0 + p.stepM * 60 * 0.08, p.elevM[60], 1e-6)
        // Wenige Proben: nicht kuenstlich vermehren.
        assertEquals(3, CatalogProfile.thinned(200.0, 3, { it * 100.0 }, { it.toDouble() })!!.elevM.size)
        assertNull(CatalogProfile.thinned(0.0, 5, { 0.0 }, { 0.0 }))
        assertNull(CatalogProfile.thinned(100.0, 1, { 0.0 }, { 0.0 }))
    }

    private val berlin = ZoneId.of("Europe/Berlin")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) =
        LocalDateTime.of(y, mo, d, h, mi).atZone(berlin).toInstant().toEpochMilli()

    @Test
    fun abrufHoechstensEinmalAmTag() {
        val morning = at(2026, 11, 24, 8)
        assertTrue(CatalogAutoRefresh.isDue(null, morning, berlin), "noch nie abgerufen")
        assertTrue(CatalogAutoRefresh.isDue(0L, morning, berlin))
        // Derselbe Tag: nie ein zweites Mal.
        assertFalse(CatalogAutoRefresh.isDue(morning, at(2026, 11, 24, 8, 1), berlin))
        assertFalse(CatalogAutoRefresh.isDue(morning, at(2026, 11, 24, 23, 59), berlin))
        // Naechster Morgen, auch etwas frueher als gestern: wieder faellig.
        assertTrue(CatalogAutoRefresh.isDue(morning, at(2026, 11, 25, 7, 30), berlin))
        // Abends geschaut, kurz nach Mitternacht wieder: noch nicht (Mindestabstand).
        val evening = at(2026, 11, 24, 23, 30)
        assertFalse(CatalogAutoRefresh.isDue(evening, at(2026, 11, 25, 0, 30), berlin))
        assertTrue(CatalogAutoRefresh.isDue(evening, at(2026, 11, 25, 6, 30), berlin))
        // Spaetestens nach 24 Stunden, auch ueber die Zeitumstellung hinweg.
        assertTrue(CatalogAutoRefresh.isDue(morning, morning + CatalogAutoRefresh.INTERVAL_MS, berlin))
        assertTrue(CatalogAutoRefresh.isDue(at(2026, 3, 28, 12), at(2026, 3, 29, 12), berlin))
        // Uhr zurueckgestellt: nicht tagelang ohne Abruf.
        assertTrue(CatalogAutoRefresh.isDue(morning, morning - 2 * CatalogAutoRefresh.CLOCK_SKEW_MS, berlin))
        assertFalse(CatalogAutoRefresh.isDue(morning, morning - 60_000L, berlin), "kleine Ungenauigkeit der Uhr zaehlt nicht")
    }
}
