package de.trailscape.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests fuer die reinen Rechnungen der Navi-Kamera (`NavCamera.kt`):
 * Kurs-Glaettung mit Wraparound und Einfrieren, Tempo-Zoom mit Klemmen und
 * Monotonie, Zoom-Glaettung und der Anfangskurs zwischen zwei Positionen.
 */
class NavCameraTest {

    private companion object {
        /** Tempo klar oberhalb der Einfrier-Schwelle. */
        const val FAHREND_KMH = 20.0
        const val EPS = 1e-9
    }

    // --- daempfeKurs: Wraparound ---

    @Test
    fun `wraparound von 350 nach 10 dreht ueber Norden, nicht herum`() {
        // Kuerzeste Differenz +20 Grad; mit Faktor 0.5 also +10 -> 0 Grad.
        val ergebnis = daempfeKurs(350.0, 10.0, FAHREND_KMH, faktor = 0.5)
        assertEquals(0.0, ergebnis!!, EPS)
    }

    @Test
    fun `wraparound von 10 nach 350 dreht ueber Norden zurueck`() {
        // Kuerzeste Differenz -20 Grad; mit Faktor 0.5 also -10 -> 0 Grad.
        val ergebnis = daempfeKurs(10.0, 350.0, FAHREND_KMH, faktor = 0.5)
        assertEquals(0.0, ergebnis!!, EPS)
    }

    @Test
    fun `wraparound landet nie ausserhalb von 0 bis 360`() {
        val ergebnis = daempfeKurs(359.0, 359.9, FAHREND_KMH, faktor = 1.0)
        assertTrue(ergebnis!! >= 0.0 && ergebnis < 360.0)
    }

    @Test
    fun `gegenkurs 180 Grad kippt nicht ins Undefinierte`() {
        // Differenz exakt 180 ist mehrdeutig; die Formel entscheidet sich
        // fest fuer -180 (Drehung gegen den Uhrzeigersinn): mit Faktor 0.5
        // also -90 -> 270 Grad. Wichtig ist nur, dass das Ergebnis stabil
        // und im Kreis bleibt.
        val ergebnis = daempfeKurs(0.0, 180.0, FAHREND_KMH, faktor = 0.5)
        assertEquals(270.0, ergebnis!!, EPS)
    }

    // --- daempfeKurs: Glaettung und Randfaelle ---

    @Test
    fun `gewoehnliche Glaettung ohne Wraparound`() {
        val ergebnis = daempfeKurs(100.0, 120.0, FAHREND_KMH, faktor = 0.25)
        assertEquals(105.0, ergebnis!!, EPS)
    }

    @Test
    fun `erster Messwert wird unveraendert uebernommen`() {
        assertEquals(42.0, daempfeKurs(null, 42.0, FAHREND_KMH)!!, EPS)
    }

    @Test
    fun `ohne neuen Messwert bleibt der alte Kurs`() {
        assertEquals(42.0, daempfeKurs(42.0, null, FAHREND_KMH)!!, EPS)
        assertNull(daempfeKurs(null, null, FAHREND_KMH))
    }

    @Test
    fun `negativer Messwert wird normalisiert`() {
        assertEquals(350.0, daempfeKurs(null, -10.0, FAHREND_KMH)!!, EPS)
    }

    // --- daempfeKurs: Einfrieren im Stand ---

    @Test
    fun `unter der Schwelle bleibt der Kurs eingefroren`() {
        assertEquals(
            120.0,
            daempfeKurs(120.0, 200.0, tempoKmh = NAV_KURS_EINFRIER_KMH - 0.1)!!,
            EPS,
        )
        // Auch ganz im Stand.
        assertEquals(120.0, daempfeKurs(120.0, 300.0, tempoKmh = 0.0)!!, EPS)
    }

    @Test
    fun `an der Schwelle wird wieder gedreht`() {
        val ergebnis = daempfeKurs(120.0, 200.0, tempoKmh = NAV_KURS_EINFRIER_KMH, faktor = 1.0)
        assertEquals(200.0, ergebnis!!, EPS)
    }

    @Test
    fun `einfrieren ohne alten Kurs liefert weiterhin nichts`() {
        // Im Stand ohne je gefahrenen Kurs gibt es nichts festzuhalten — und
        // der Rausch-Messwert darf auch nicht als erster Kurs durchrutschen.
        assertNull(daempfeKurs(null, 200.0, tempoKmh = 0.0))
    }

    @Test
    fun `unbekanntes Tempo friert nicht ein`() {
        val ergebnis = daempfeKurs(100.0, 120.0, tempoKmh = null, faktor = 1.0)
        assertEquals(120.0, ergebnis!!, EPS)
    }

    // --- zoomFuerTempo ---

    @Test
    fun `langsam faehrt nah heran`() {
        assertEquals(NAV_ZOOM_NAH, zoomFuerTempo(0.0), EPS)
        assertEquals(NAV_ZOOM_NAH, zoomFuerTempo(NAV_ZOOM_TEMPO_LANGSAM_KMH), EPS)
        assertEquals(NAV_ZOOM_NAH, zoomFuerTempo(null), EPS)
    }

    @Test
    fun `schnell klemmt auf die ferne Stufe`() {
        assertEquals(NAV_ZOOM_FERN, zoomFuerTempo(NAV_ZOOM_TEMPO_SCHNELL_KMH), EPS)
        assertEquals(NAV_ZOOM_FERN, zoomFuerTempo(80.0), EPS)
    }

    @Test
    fun `dazwischen linear`() {
        // Genau in der Mitte zwischen 15 und 35 km/h: Mitte zwischen 17,0 und 15,5.
        val mitte = (NAV_ZOOM_TEMPO_LANGSAM_KMH + NAV_ZOOM_TEMPO_SCHNELL_KMH) / 2
        assertEquals((NAV_ZOOM_NAH + NAV_ZOOM_FERN) / 2, zoomFuerTempo(mitte), EPS)
    }

    @Test
    fun `zoom faellt monoton mit dem Tempo`() {
        var vorher = zoomFuerTempo(0.0)
        var kmh = 1.0
        while (kmh <= 60.0) {
            val jetzt = zoomFuerTempo(kmh)
            assertTrue(jetzt <= vorher + EPS, "Zoom steigt bei $kmh km/h")
            vorher = jetzt
            kmh += 1.0
        }
    }

    // --- glaetteZoom ---

    @Test
    fun `erster Zoomwert wird direkt uebernommen`() {
        assertEquals(17.0, glaetteZoom(null, 17.0), EPS)
    }

    @Test
    fun `zoom naehert sich dem Ziel ohne es zu ueberschiessen`() {
        var zoom = 17.0
        repeat(60) { zoom = glaetteZoom(zoom, 15.5) }
        assertTrue(zoom > 15.5 - EPS && zoom < 17.0)
        // Nach genuegend Schritten praktisch am Ziel.
        repeat(200) { zoom = glaetteZoom(zoom, 15.5) }
        assertEquals(15.5, zoom, 0.01)
    }

    // --- klemmeOffRouteZoom ---

    @Test
    fun `abseits-Zoom wird beidseitig geklemmt`() {
        assertEquals(NAV_OFFROUTE_ZOOM_MAX, klemmeOffRouteZoom(18.0), EPS)
        assertEquals(NAV_OFFROUTE_ZOOM_MIN, klemmeOffRouteZoom(8.0), EPS)
        assertEquals(14.0, klemmeOffRouteZoom(14.0), EPS)
    }

    // --- kursZwischen ---

    @Test
    fun `kurs in die vier Himmelsrichtungen`() {
        val lat = 48.0
        val lon = 11.0
        assertEquals(0.0, kursZwischen(lat, lon, lat + 0.01, lon), 0.1)
        assertEquals(180.0, kursZwischen(lat, lon, lat - 0.01, lon), 0.1)
        assertEquals(90.0, kursZwischen(lat, lon, lat, lon + 0.01), 0.1)
        assertEquals(270.0, kursZwischen(lat, lon, lat, lon - 0.01), 0.1)
    }

    @Test
    fun `kurs nach Nordwesten liegt zwischen 270 und 360`() {
        val kurs = kursZwischen(48.0, 11.0, 48.01, 10.99)
        assertTrue(kurs > 270.0 && kurs < 360.0, "Kurs war $kurs")
    }

    // --- normalisiereKurs ---

    @Test
    fun `normalisierung bringt beliebige Winkel in den Kreis`() {
        assertEquals(0.0, normalisiereKurs(360.0), EPS)
        assertEquals(350.0, normalisiereKurs(-10.0), EPS)
        assertEquals(10.0, normalisiereKurs(730.0), EPS)
    }
}
