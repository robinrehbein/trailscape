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

    @Test
    fun einpassenUebernimmtDenRandVonMapLibre() {
        // Der Rand unten (vom Blatt verdeckt) muss mit in die Kamera, sonst
        // sitzt die Runde mittig auf der ganzen Karte statt ueber dem Blatt.
        assertArrayEquals(
            doubleArrayOf(48.0, 48.0, 48.0, 1100.0),
            fitCameraPadding(doubleArrayOf(48.0, 48.0, 48.0, 1100.0)),
            0.0,
        )
    }

    @Test
    fun einpassenOhneBrauchbarenRandNimmtKeinen() {
        val null4 = doubleArrayOf(0.0, 0.0, 0.0, 0.0)
        assertArrayEquals(null4, fitCameraPadding(null), 0.0)
        assertArrayEquals(null4, fitCameraPadding(doubleArrayOf(1.0, 2.0)), 0.0)
        assertArrayEquals(null4, fitCameraPadding(doubleArrayOf(0.0, Double.NaN, 0.0, 10.0)), 0.0)
    }

    /** Wo MapLibre das Ziel hinsetzt: Mitte des Streifens [oben, Hoehe - unten]. */
    private fun zielY(h: Int, pad: Pair<Double, Double>) = (pad.first + h - pad.second) / 2

    @Test
    fun naviPositionLiegtUeberDerLiveLeiste() {
        // Masse wie auf dem Geraet: Abbiegeschild oben, Live-Leiste + Kapsel unten.
        val h = 1900
        val oben = 380
        val unten = 625
        val y = zielY(h, navCameraPadding(h, oben, unten, versatz = true))
        assertTrue("Position $y muss ueber der Leiste (${h - unten}) liegen", y < h - unten - 100)
        assertTrue("Position $y muss unter dem Schild ($oben) liegen", y > oben)
        // Unteres Drittel des freien Bereichs, nicht die Mitte.
        assertEquals(oben + NAV_POSITION_ANTEIL * (h - oben - unten), y, 0.5)
    }

    @Test
    fun naviNordObenStehtMittigImFreienBereich() {
        val h = 1900
        val y = zielY(h, navCameraPadding(h, 380, 625, versatz = false))
        assertEquals(380 + (1900 - 380 - 625) / 2.0, y, 0.5)
    }

    @Test
    fun naviOhneFreienBereichKeinRand() {
        assertEquals(0.0 to 0.0, navCameraPadding(0, 100, 100, versatz = true))
        assertEquals(0.0 to 0.0, navCameraPadding(300, 200, 100, versatz = true))
    }
}
