package de.trailscape.core.indoor.world

import de.trailscape.core.indoor.IndoorRouteSource
import de.trailscape.core.indoor.SurfaceKind
import de.trailscape.core.indoor.SurfaceSection
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Timeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Streckenband (docs/indoor/luftbild-band.md): Felder, Aufteilung des Gelaendes, Manifestfeld. */
@Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class AerialBandTest {
    /** Gerade West → Ost bei n = 100 durch die Kachel (0, 0). */
    private fun straightTrack(): WorldTrack {
        val count = 61
        val e = FloatArray(count) { (-20 + it * 5).toFloat() }
        val n = FloatArray(count) { 100f }
        val u = FloatArray(count)
        val len = (count - 1) * 5.0
        return WorldTrack(len, 5.0, false, e, n, u, listOf(SurfaceSection(0.0, len, SurfaceKind.ASPHALT)), IndoorRouteSource.NONE)
    }

    @Test
    fun `Felder im Band liegen hoechstens bandM neben der Fahrlinie`() {
        val spec = TileSpec(0, 0, 0)
        val cells = AerialBand.cellsNear(spec, straightTrack(), bandM = 20.0, cellM = 32.0, limit = 100)
        // n = 100 ± 20 → Zeilen j = 2 (64..96) und 3 (96..128); 8 Spalten
        assertEquals(16, cells.size)
        assertTrue(cells.all { it.j == 2 || it.j == 3 })
        assertTrue(cells.all { it.distM <= 20.0 })
        // Obergrenze: die naechsten zuerst (Zeile 3 enthaelt die Fahrlinie, Abstand 0)
        val few = AerialBand.cellsNear(spec, straightTrack(), 20.0, 32.0, limit = 8)
        assertEquals(8, few.size)
        assertTrue(few.all { it.j == 3 && it.distM == 0.0 })
        // Kachel weit weg: nichts
        assertTrue(AerialBand.cellsNear(TileSpec(0, 5, 5), straightTrack(), 20.0, 32.0, 100).isEmpty())
    }

    @Test
    fun `Abstand Strecke zu Rechteck`() {
        assertEquals(0.0, AerialBand.segmentRectDistance(-10.0, 5.0, 50.0, 5.0, 0.0, 0.0, 10.0, 10.0))
        assertEquals(5.0, AerialBand.segmentRectDistance(-10.0, 15.0, 50.0, 15.0, 0.0, 0.0, 10.0, 10.0), 1e-9)
        assertEquals(Math.hypot(3.0, 4.0), AerialBand.segmentRectDistance(13.0, 14.0, 13.0, 14.0, 0.0, 0.0, 10.0, 10.0), 1e-9)
    }

    @Test
    fun `Gelaende wird ohne neue Eckpunkte aufgeteilt, UV laufen ueber das Feld`() {
        val spec = TileSpec(0, 0, 0)
        val out = TileOutput(spec)
        val baseMat = TileMaterial(WorldNames.MAT_TERRAIN, texture = null, repeat = false, roughness = 0.95f)
        val mesh = out.mesh(WorldNames.NODE_TERRAIN, baseMat)
        val cells = 64
        val step = spec.cellM / cells
        for (j in 0..cells) for (i in 0..cells) {
            val e = spec.minE + i * step
            val n = spec.minN + j * step
            mesh.vertex(e - spec.anchorE, 0.0, -(n - spec.anchorN), 0.0, 1.0, 0.0, i / cells.toDouble(), 1.0 - j / cells.toDouble())
        }
        for (j in 0 until cells) for (i in 0 until cells) {
            val a = j * (cells + 1) + i
            mesh.quad(a, a + 1, a + cells + 2, a + cells + 1)
        }
        val options = AerialBandOptions()
        val perSide = 8
        val dummy = EncodedTexture(ByteArray(4), "image/jpeg", 256, 256)
        val textures = arrayOfNulls<EncodedTexture>(perSide * perSide)
        textures[0] = dummy // Feld i = 0, j = 0 (Suedwestecke)
        textures[3 * perSide + 5] = dummy // Feld i = 5, j = 3
        val used = AerialBand.split(spec, out, mesh, options, perSide, textures)
        assertEquals(2, used)
        val rest = assertNotNull(out.level0Mesh(WorldNames.NODE_TERRAIN, WorldNames.MAT_TERRAIN))
        val a = assertNotNull(out.level0Mesh(WorldNames.NODE_TERRAIN, AerialBand.materialName(8, 0, 7)))
        val b = assertNotNull(out.level0Mesh(WorldNames.NODE_TERRAIN, AerialBand.materialName(8, 5, 4)))
        // 32 m / 4 m = 8 Zellen je Feldkante, 2 Dreiecke je Zelle
        assertEquals(128, a.triangleCount)
        assertEquals(128, b.triangleCount)
        assertEquals(cells * cells * 2 - 256, rest.triangleCount)
        assertEquals(81, a.vertexCount)
        val inset = 0.5 / options.px
        for (v in 0 until a.vertexCount) {
            val u = a.uvs[v * 2]
            val w = a.uvs[v * 2 + 1]
            assertTrue(u >= inset - 1e-6 && u <= 1 - inset + 1e-6, "u $u")
            assertTrue(w >= inset - 1e-6 && w <= 1 - inset + 1e-6, "v $w")
        }
        // Nordwestecke des Felds (0, 0) bei e = 0, n = 32 → UV (inset, inset)
        val nw = (0 until a.vertexCount).first { a.x(it) + spec.anchorE == 0.0 && spec.anchorN - a.z(it) == 32.0 }
        assertEquals(inset, a.uvs[nw * 2].toDouble(), 1e-6)
        assertEquals(inset, a.uvs[nw * 2 + 1].toDouble(), 1e-6)
    }

    @Test
    fun `Stufe und Manifestfeld`() {
        assertNull(AerialBandLevel.fromId("aus")!!.options)
        assertEquals(0.125, AerialBandLevel.fromId("band")!!.options!!.metersPerPixel)
        assertEquals(30.0, AerialBandLevel.fromId(" Band-Breit ")!!.options!!.bandM)
        assertNull(AerialBandLevel.fromId("20cm"))
        val f = WorldAerialBand(20.0, 32.0, 256)
        val back = WorldAerialBand.fromJson(Json.parseToJsonElement(f.toJson().toString()) as JsonObject)
        assertEquals(f, back)
        assertNull(WorldAerialBand.fromJson(Json.parseToJsonElement("{\"bandM\":1}") as JsonObject))
    }

    @Test
    fun `unscharfe Maske laesst Flaechen gleich und hebt Kanten an`() {
        val img = RgbaImage(8, 8)
        for (y in 0 until 8) for (x in 0 until 8) img[x, y] = if (x < 4) Argb.rgb(100, 100, 100) else Argb.rgb(160, 160, 160)
        val s = AerialBand.unsharp(img, 0.5)
        assertEquals(100, Argb.red(s[0, 3]))
        assertEquals(160, Argb.red(s[7, 3]))
        assertTrue(Argb.red(s[3, 3]) < 100, "dunkle Seite der Kante dunkler")
        assertTrue(Argb.red(s[4, 3]) > 160, "helle Seite der Kante heller")
    }
}
