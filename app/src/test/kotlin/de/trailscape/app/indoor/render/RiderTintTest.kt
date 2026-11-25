package de.trailscape.app.indoor.render

import de.trailscape.core.garage.JerseyPattern
import de.trailscape.core.garage.RiderAvatar
import de.trailscape.core.garage.RiderGlbColors
import de.trailscape.core.garage.RiderLook
import de.trailscape.core.garage.RiderZone
import de.trailscape.core.indoor.world.RiderModelFiles
import de.trailscape.core.indoor.world.WorldModels
import de.trailscape.core.indoor.world.checkModelGlb
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regeln des Einfaerbens ([RiderTint]) ohne Filament: Gesetzt wird nur, was das
 * Material deklariert (Issue 106), ein Fehler bleibt bei seiner Instanz.
 */
class RiderTintTest {
    private class Fake(
        override val materialName: String?,
        private val params: Set<String> = setOf(RiderTint.PARAM),
        private val failOnAsk: Boolean = false,
    ) : RiderTint.Target {
        val set = ArrayList<Pair<String, List<Float>>>()

        override fun declares(param: String): Boolean {
            if (failOnAsk) error("kaputt")
            return param in params
        }

        override fun setFloat4(param: String, r: Float, g: Float, b: Float, a: Float) {
            // Wie Filament: ein unbekannter Parameter waere ein harter Abbruch — hier ein Testfehler.
            check(param in params) { "Parameter $param nicht deklariert" }
            set += param to listOf(r, g, b, a)
        }
    }

    @Test
    fun `setzt nur deklarierte Parameter auf bekannten Zonen`() {
        val jersey = Fake(RiderZone.JERSEY_LOW.materialName)
        val noParam = Fake(RiderZone.FRAME.materialName, params = emptySet())
        val fixed = Fake("x_mat_bike")
        val unnamed = Fake(null)
        val broken = Fake(RiderZone.HELMET.materialName, failOnAsk = true)
        val skin = Fake(RiderZone.SKIN.materialName)
        val a = RiderAvatar(jersey = 0x2C5BB8, skin = 7)
        val n = RiderTint.applyAvatar(listOf(jersey, noParam, fixed, unnamed, broken, skin), a)
        assertEquals(2, n)
        assertEquals(RiderTint.PARAM, jersey.set.single().first)
        assertEquals(RiderLook.linearRgba(0x2C5BB8).toList(), jersey.set.single().second)
        assertTrue(noParam.set.isEmpty() && fixed.set.isEmpty() && unnamed.set.isEmpty() && broken.set.isEmpty())
        assertEquals(1, skin.set.size)
    }

    @Test
    fun `ungueltige Faktoren werden nie gesetzt`() {
        val t = Fake(RiderZone.FRAME.materialName)
        val bad = mapOf(RiderZone.FRAME.materialName to floatArrayOf(Float.NaN, 0f, 0f, 1f))
        assertEquals(0, RiderTint.applyTo(listOf(t), bad))
        assertEquals(0, RiderTint.applyTo(listOf(t), mapOf(RiderZone.FRAME.materialName to floatArrayOf(1f, 1f, 1f))))
        assertEquals(0, RiderTint.applyTo(listOf(t), mapOf(RiderZone.FRAME.materialName to floatArrayOf(1.5f, 0f, 0f, 1f))))
        assertTrue(t.set.isEmpty())
    }

    @Test
    fun `Ersatzfahrer ohne Zonen bekommt wenigstens die Trikotfarbe`() {
        val kit = Fake("x_mat_kit")
        val other = Fake("x_mat_bike")
        assertEquals(1, RiderTint.applyAvatar(listOf(kit, other), RiderAvatar(jersey = 0xE8B923)))
        assertEquals(RiderLook.linearRgba(0xE8B923).toList(), kit.set.single().second)
        assertTrue(other.set.isEmpty())
        // Mit Zonen bleibt x_mat_kit (Socken, Handschuhe) unberuehrt.
        val kit2 = Fake("x_mat_kit")
        RiderTint.applyAvatar(listOf(kit2, Fake(RiderZone.BIBS.materialName)), RiderAvatar.DEFAULT)
        assertTrue(kit2.set.isEmpty())
    }

    @Test
    fun `alle Zonen des eingecheckten Modells werden gesetzt`() {
        val targets = RiderZone.entries.map { Fake(it.materialName) } + listOf("x_mat_hair", "x_mat_kit", "x_mat_gear", "x_mat_bike", "x_mat_tire").map { Fake(it) }
        assertEquals(RiderZone.entries.size, RiderTint.applyAvatar(targets, RiderAvatar(pattern = JerseyPattern.FADE)))
    }

    @Test
    fun `sicherer Modus schreibt die Farben in die Datei, sonst bleiben die Bytes`() {
        val src = File("src/main/assets/${WorldModels.ASSET_DIR}/${WorldModels.RIDER}").readBytes()
        val a = RiderAvatar(jersey = 0x1F7A4D, frame = 0xE8B923)
        assertContentEquals(src, RiderTint.prepare(src, a, safeMode = false))
        val safe = RiderTint.prepare(src, a, safeMode = true)
        assertContentEquals(RiderGlbColors.apply(src, RiderLook.materialFactors(a)), safe)
        assertEquals(emptyList(), checkModelGlb(safe))
        assertTrue(!safe.contentEquals(src))
    }

    @Test
    fun `Detailstufe liegt neben dem Fahrer in den Assets`() {
        val lod = File("src/main/assets/${WorldModels.ASSET_DIR}/${RiderModelFiles.RIDER_LOD}")
        assertTrue(lod.isFile && lod.length() in 50_000L..300_000L, "rider_lod.glb fehlt oder hat ${lod.length()} Bytes")
        assertEquals(emptyList(), checkModelGlb(lod.readBytes()))
    }

    @Test
    fun `Mitfahrer wechseln ab 30 m zur Detailstufe, vor dem Schattenende`() {
        assertTrue(LifeLayer.LOD_M in 20.0..LifeLayer.SHADOW_M)
        assertTrue(LifeLayer.LOD_M < LifeLayer.VISIBLE_M)
    }
}
