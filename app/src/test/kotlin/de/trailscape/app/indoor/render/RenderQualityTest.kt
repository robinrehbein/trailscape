package de.trailscape.app.indoor.render

import com.google.android.filament.View
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests für die Qualitätsstufen-Zuordnung in [RenderQuality] — insbesondere die
 * Begrenzung von Bloom und FSR auf MEDIUM, um Randartefakte bei Ultra zu vermeiden.
 */
class RenderQualityTest {
    @Test
    fun `level bildet effectQuality direkt auf LOW MEDIUM HIGH ab`() {
        assertEquals(View.QualityLevel.LOW, RenderQuality.level(0))
        assertEquals(View.QualityLevel.MEDIUM, RenderQuality.level(1))
        assertEquals(View.QualityLevel.HIGH, RenderQuality.level(2))
    }

    @Test
    fun `bloomQuality begrenzt Ultra auf MEDIUM`() {
        assertEquals(View.QualityLevel.LOW, RenderQuality.bloomQuality(0))
        assertEquals(View.QualityLevel.MEDIUM, RenderQuality.bloomQuality(1))
        assertEquals(View.QualityLevel.MEDIUM, RenderQuality.bloomQuality(2))
    }

    @Test
    fun `dynamicResolutionQuality begrenzt Ultra auf MEDIUM`() {
        assertEquals(View.QualityLevel.LOW, RenderQuality.dynamicResolutionQuality(0))
        assertEquals(View.QualityLevel.MEDIUM, RenderQuality.dynamicResolutionQuality(1))
        assertEquals(View.QualityLevel.MEDIUM, RenderQuality.dynamicResolutionQuality(2))
    }
}
