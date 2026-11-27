package de.trailscape.core.indoor.world

import de.trailscape.core.indoor.sky.PhotoGrade
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Grafikstufen Standard/Hoch/Ultra ([RenderTiers]) und Weissabgleich ([PhotoGrade]). */
class RenderTiersTest {
    @Test
    fun `Standard ist unveraendert die Voreinstellung der Weltqualitaet`() {
        for (q in IndoorQuality.entries) {
            assertEquals(RenderSettings.forQuality(q), RenderTiers.settingsFor(IndoorGraphics.STANDARD, q))
        }
        // Neue Felder sind in den bisherigen Voreinstellungen aus.
        val m = RenderSettings.forQuality(IndoorQuality.MEDIUM)
        assertFalse(m.reflections || m.temporalAA || m.terrainShadows || m.whiteBalance || m.vignette)
        assertEquals(0, m.effectQuality)
        assertEquals(3, m.shadowCascades)
        assertEquals(300.0, m.shadowFarM)
        assertEquals(0.6, m.dynamicResolutionMinScale)
        // Standard zeigt die gebackenen Baeume wie bisher, keine Instanzen aus der Bibliothek.
        for (q in IndoorQuality.entries) assertEquals(0, RenderSettings.forQuality(q).treeDetail)
        assertEquals(0, RenderSettings.SAFE.treeDetail)
    }

    @Test
    fun `Hoch und Ultra bauen aufeinander auf`() {
        val h = RenderTiers.settingsFor(IndoorGraphics.HIGH, IndoorQuality.MEDIUM)
        val u = RenderTiers.settingsFor(IndoorGraphics.ULTRA, IndoorQuality.MEDIUM)
        assertTrue(h.ambientOcclusion && h.bloom && h.reflections && h.whiteBalance)
        assertFalse(h.temporalAA)
        assertTrue(u.reflections && u.terrainShadows && u.vignette)
        assertFalse(u.temporalAA, "TAA erzeugt auf Geraeten mit dyn. Aufloesung + Bloom einen Magenta-Rand rechts")
        assertFalse(u.stableShadows, "stabil + 800 m loescht die Schatten (Referenzrenderer)")
        assertEquals(4, u.shadowCascades)
        assertEquals(2, u.effectQuality)
        assertEquals(60, u.maxFps)
        assertEquals(RenderTiers.HIGH_DYNRES_MIN_SCALE, h.dynamicResolutionMinScale)
        assertEquals(RenderTiers.HIGH_DYNRES_MIN_SCALE, u.dynamicResolutionMinScale)
        assertTrue(u.shadowFarM > h.shadowFarM)
        assertEquals(1, h.treeDetail)
        assertEquals(2, u.treeDetail)
    }

    @Test
    fun `sicherer Modus nimmt alle Zusaetze weg`() {
        val s = RenderTiers.withoutTierExtras(RenderTiers.ultra())
        assertEquals(RenderSettings.forQuality(IndoorQuality.HIGH), s)
    }

    @Test
    fun `Kaskadengrenzen steigen und liegen im Bereich`() {
        for ((c, far) in listOf(3 to 300.0, 4 to 800.0, 4 to 300.0)) {
            val s = RenderTiers.cascadeSplits(c, far)
            assertEquals(3, s.size)
            assertTrue(s[0] > 0f && s[0] < s[1] && s[1] < s[2] && s[2] < 1f, s.joinToString())
        }
        assertEquals(8.0, RenderTiers.cascadeSplits(4, 800.0)[0] * 800.0, 1e-3)
    }

    @Test
    fun `Namen sind stabil fuer die Einstellungen`() {
        assertEquals(IndoorGraphics.ULTRA, IndoorGraphics.fromNameOrNull("ULTRA"))
        assertEquals(null, IndoorGraphics.fromNameOrNull("x"))
    }

    @Test
    fun `Weissabgleich mittags waermer als abends, immer dezent`() {
        val (noonT, tint) = PhotoGrade.whiteBalance(60.0)
        val (eveT, _) = PhotoGrade.whiteBalance(8.0)
        assertTrue(noonT > eveT)
        for (alt in listOf(-5.0, 8.0, 20.0, 45.0, 70.0)) {
            val (t, n) = PhotoGrade.whiteBalance(alt)
            assertTrue(kotlin.math.abs(t) <= 0.1 && kotlin.math.abs(n) <= 0.05, "dezent bei $alt°")
        }
        assertTrue(tint > 0.0)
    }
}
