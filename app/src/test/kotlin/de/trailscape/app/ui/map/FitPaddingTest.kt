package de.trailscape.app.ui.map

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Geraetebericht: Nach dem Laden der Rundkurs-Vorschlaege stand die Karte
 * schwarz. Mit hochgezogenem Blatt war der verdeckte Rand groesser als die
 * Karte, und das Einpassen rechnete mit einem unmoeglichen Rand.
 */
class FitPaddingTest {

    private val base = MapPadding(left = 48, top = 120, right = 48, bottom = 220)

    @Test
    fun kleinesBlattErgibtRandDarueber() {
        assertArrayEquals(intArrayOf(48, 120, 48, 848), fitPaddingPx(1080, 2400, base, obscuredBottomPx = 800))
    }

    @Test
    fun ohneBlattBleibtDerGrundrand() {
        assertArrayEquals(intArrayOf(48, 120, 48, 220), fitPaddingPx(1080, 2400, base, obscuredBottomPx = 0))
    }

    @Test
    fun blattHoeherAlsKarteLaesstKarteFrei() {
        val p = fitPaddingPx(1080, 2400, base, obscuredBottomPx = 5000)
        assertTrue("oben+unten ${p[1]}+${p[3]}", p[1] + p[3] <= 2400 - 96)
        assertTrue(p.all { it >= 0 })
    }

    @Test
    fun winzigeKarteErgibtKeinenNegativenRand() {
        val p = fitPaddingPx(80, 60, base, obscuredBottomPx = 500)
        assertArrayEquals(intArrayOf(0, 0, 0, 0), p)
    }

    @Test
    fun verdeckterRandHoechstensSechzigProzent() {
        assertEquals(1440, clampObscuredBottom(3000, 2400))
        assertEquals(500, clampObscuredBottom(500, 2400))
        assertEquals(0, clampObscuredBottom(500, 0))
    }

    // ------------------------------------------ gespeicherter Kamera-Rand

    @Test
    fun passenderRandBleibtUnveraendert() {
        assertNull(reclampedCameraPadding(doubleArrayOf(0.0, 0.0, 0.0, 600.0), 2400))
    }

    @Test
    fun randHoeherAlsGeschrumpfteKarteWirdBegrenzt() {
        // 1400 px Rand unten, Tastatur auf: Karte nur noch 1000 px hoch.
        val p = reclampedCameraPadding(doubleArrayOf(0.0, 0.0, 0.0, 1400.0), 1000)!!
        assertEquals(600.0, p[3], 0.0)
        assertEquals(0.0, p[1], 0.0)
    }

    @Test
    fun obenUndUntenLassenImmerKarteFrei() {
        // Navi-Versatz oben plus verdeckter Rand unten auf kleiner Karte.
        val p = reclampedCameraPadding(doubleArrayOf(0.0, 700.0, 0.0, 700.0), 1000)!!
        assertTrue("oben+unten ${p[1]}+${p[3]}", p[1] + p[3] <= 1000 - 96)
    }

    @Test
    fun ohneHoeheKeineAenderung() {
        assertNull(reclampedCameraPadding(doubleArrayOf(0.0, 0.0, 0.0, 1400.0), 0))
    }
}
