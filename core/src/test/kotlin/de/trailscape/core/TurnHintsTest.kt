package de.trailscape.core

import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests fuer die Abbiegehinweise aus der Routengeometrie (`TurnHints.kt`).
 *
 * Die Testrouten entstehen wie in [NavigationTest] in einer
 * aequirektangulaeren Naeherung um einen festen Basispunkt — bei den kurzen
 * Distanzen hier ist der Fehler gegenueber Haversine vernachlaessigbar.
 */
class TurnHintsTest {
    private companion object {
        const val LAT0 = 48.0
        const val LON0 = 11.0

        val M_PER_DEG_LON = 111320 * cos(LAT0 * Math.PI / 180)
        val DEG_LON_PER_M = 1 / M_PER_DEG_LON
        const val DEG_LAT_PER_M = 1.0 / 111320
    }

    /**
     * Baut eine Route aus Schenkeln (Kurs in Grad, Laenge in Metern) mit
     * Stuetzpunkten alle [stepM] Meter.
     */
    private fun route(vararg schenkel: Pair<Double, Double>, stepM: Double = 20.0): List<TrackPoint> {
        val points = mutableListOf(TrackPoint(lat = LAT0, lon = LON0))
        var lat = LAT0
        var lon = LON0
        for ((kursGrad, laengeM) in schenkel) {
            val rad = Math.toRadians(kursGrad)
            var rest = laengeM
            while (rest > 1e-9) {
                val schritt = minOf(stepM, rest)
                lat += cos(rad) * schritt * DEG_LAT_PER_M
                lon += sin(rad) * schritt * DEG_LON_PER_M
                points.add(TrackPoint(lat = lat, lon = lon))
                rest -= schritt
            }
        }
        return points
    }

    // --- extractTurnHints ---

    @Test
    fun `gerade Strecke liefert keine Hinweise`() {
        assertEquals(emptyList(), extractTurnHints(route(90.0 to 600.0)))
    }

    @Test
    fun `weniger als 3 Punkte liefern keine Hinweise`() {
        assertEquals(emptyList(), extractTurnHints(emptyList()))
        assertEquals(
            emptyList(),
            extractTurnHints(
                listOf(
                    TrackPoint(lat = LAT0, lon = LON0),
                    TrackPoint(lat = LAT0, lon = LON0 + 100 * DEG_LON_PER_M),
                ),
            ),
        )
    }

    @Test
    fun `90 Grad Rechtskurve liefert genau einen Hinweis RECHTS`() {
        // Ost, dann Sued: Kurs 90 -> 180, Differenz +90 (rechts herum).
        val hints = extractTurnHints(route(90.0 to 300.0, 180.0 to 300.0))
        assertEquals(1, hints.size)
        val hint = hints[0]
        assertEquals(TurnRichtung.RECHTS, hint.richtung)
        assertTrue(hint.winkelGrad in 80.0..100.0, "Winkel war ${hint.winkelGrad}")
        // Der Hinweis sitzt an der Einfahrt in die Kurve, also kurz vor dem
        // Scheitel bei 300 m (Fensterglaettung).
        assertTrue(hint.distanzM in 230.0..300.0, "Distanz war ${hint.distanzM}")
    }

    @Test
    fun `90 Grad Linkskurve liefert genau einen Hinweis LINKS`() {
        // Ost, dann Nord: Kurs 90 -> 0, Differenz -90 (links herum).
        val hints = extractTurnHints(route(90.0 to 300.0, 0.0 to 300.0))
        assertEquals(1, hints.size)
        assertEquals(TurnRichtung.LINKS, hints[0].richtung)
    }

    @Test
    fun `sanfter 30 Grad Knick bleibt unter der Schwelle`() {
        assertEquals(emptyList(), extractTurnHints(route(90.0 to 300.0, 120.0 to 300.0)))
    }

    @Test
    fun `150 Grad Kurve ist eine Kehre`() {
        val hints = extractTurnHints(route(90.0 to 300.0, 240.0 to 300.0))
        assertEquals(1, hints.size)
        assertEquals(TurnRichtung.KEHRE_RECHTS, hints[0].richtung)
        assertTrue(hints[0].winkelGrad >= KEHRE_SCHWELLE_GRAD, "Winkel war ${hints[0].winkelGrad}")
    }

    @Test
    fun `Serpentine wird zu einem Hinweis gebuendelt`() {
        // Drei Kehren im Abstand von je 60 m Schenkellaenge — einzeln
        // angesagt waere das eine Durchsage im Sekundentakt.
        val hints = extractTurnHints(
            route(
                90.0 to 300.0,
                240.0 to 60.0,
                90.0 to 60.0,
                240.0 to 60.0,
                90.0 to 300.0,
            ),
        )
        assertEquals(1, hints.size)
        // Die erste Kehre dreht rechts herum — sie bestimmt die Richtung.
        assertEquals(TurnRichtung.KEHRE_RECHTS, hints[0].richtung)
        assertTrue(hints[0].distanzM < 320.0, "Hinweis muss an der Einfahrt sitzen, war ${hints[0].distanzM}")
    }

    @Test
    fun `zwei weit auseinanderliegende Kurven bleiben zwei Hinweise`() {
        val hints = extractTurnHints(route(90.0 to 300.0, 180.0 to 300.0, 90.0 to 300.0))
        assertEquals(2, hints.size)
        assertEquals(TurnRichtung.RECHTS, hints[0].richtung)
        assertEquals(TurnRichtung.LINKS, hints[1].richtung)
        assertTrue(hints[1].distanzM > hints[0].distanzM)
    }

    @Test
    fun `deckungsgleiche Punkte bringen die Extraktion nicht durcheinander`() {
        val basis = route(90.0 to 300.0, 180.0 to 300.0).toMutableList()
        // Stillstand mitten auf der Geraden: derselbe Punkt dreimal.
        basis.add(5, basis[4])
        basis.add(5, basis[4])
        val hints = extractTurnHints(basis)
        assertEquals(1, hints.size)
        assertEquals(TurnRichtung.RECHTS, hints[0].richtung)
    }

    // --- TurnAnnouncer ---

    private fun hintBei(distanzM: Double, richtung: TurnRichtung = TurnRichtung.RECHTS) = TurnHint(
        index = 0,
        lat = LAT0,
        lon = LON0,
        richtung = richtung,
        winkelGrad = 90.0,
        distanzM = distanzM,
    )

    @Test
    fun `Ansage kommt erst innerhalb des Vorlaufs`() {
        val announcer = TurnAnnouncer(listOf(hintBei(500.0)))
        // 30 km/h -> Vorlauf 8 s Fahrzeit = 66,7 m.
        assertNull(announcer.melde(400.0, 30.0))
        assertEquals("In 50 Metern rechts.", announcer.melde(440.0, 30.0))
    }

    @Test
    fun `jeder Hinweis wird hoechstens einmal angesagt`() {
        val announcer = TurnAnnouncer(listOf(hintBei(500.0)))
        assertNotNull(announcer.melde(445.0, 30.0))
        assertNull(announcer.melde(450.0, 30.0))
        assertNull(announcer.melde(499.0, 30.0))
    }

    @Test
    fun `hoeheres Tempo verlaengert den Vorlauf`() {
        // Bei 60 km/h betraegt der Vorlauf 133 m — 100 m vor der Kurve ist
        // die Ansage also schon faellig, die bei 30 km/h noch schwieg.
        val announcer = TurnAnnouncer(listOf(hintBei(500.0)))
        assertEquals("In 100 Metern rechts.", announcer.melde(400.0, 60.0))
    }

    @Test
    fun `Vorlauf ist nach unten auf 60 m begrenzt`() {
        // 5 km/h ergaebe rechnerisch nur 11 m Vorlauf.
        val announcer = TurnAnnouncer(listOf(hintBei(500.0)))
        assertNull(announcer.melde(430.0, 5.0))
        assertNotNull(announcer.melde(441.0, 5.0))
    }

    @Test
    fun `Vorlauf ist nach oben auf 250 m begrenzt`() {
        // 200 km/h ergaebe rechnerisch 444 m Vorlauf.
        val announcer = TurnAnnouncer(listOf(hintBei(500.0)))
        assertNull(announcer.melde(240.0, 200.0))
        assertNotNull(announcer.melde(260.0, 200.0))
    }

    @Test
    fun `ohne Tempo gilt die Annahme von 15 kmh`() {
        // 15 km/h -> 33 m, angehoben auf das Minimum von 60 m.
        val announcer = TurnAnnouncer(listOf(hintBei(500.0)))
        assertNull(announcer.melde(430.0, null))
        assertNotNull(announcer.melde(445.0, null))
    }

    @Test
    fun `im Nahbereich heisst es Gleich`() {
        val announcer = TurnAnnouncer(listOf(hintBei(500.0, TurnRichtung.KEHRE_LINKS)))
        assertEquals("Gleich scharf links.", announcer.melde(470.0, 30.0))
    }

    @Test
    fun `ueberfahrene Hinweise verfallen stumm`() {
        val announcer = TurnAnnouncer(listOf(hintBei(500.0), hintBei(900.0, TurnRichtung.LINKS)))
        // Wiedereinstieg hinter der ersten Kurve: sie wird nie angesagt,
        // die zweite ganz normal.
        assertNull(announcer.melde(600.0, 30.0))
        assertEquals("In 50 Metern links.", announcer.melde(840.0, 30.0))
    }

    @Test
    fun `reset beginnt wieder am Routenanfang`() {
        val announcer = TurnAnnouncer(listOf(hintBei(500.0)))
        assertNotNull(announcer.melde(445.0, 30.0))
        announcer.reset()
        assertNotNull(announcer.melde(445.0, 30.0))
    }

    // --- naechsteKurve ---

    @Test
    fun `vor der ersten Kurve zeigt naechsteKurve auf sie`() {
        val hints = listOf(hintBei(500.0), hintBei(900.0, TurnRichtung.LINKS))
        val (hint, restM) = naechsteKurve(hints, 100.0)!!
        assertEquals(500.0, hint.distanzM)
        assertEquals(400.0, restM, 1e-9)
    }

    @Test
    fun `zwischen zwei Kurven zaehlt die zweite`() {
        val hints = listOf(hintBei(500.0), hintBei(900.0, TurnRichtung.LINKS))
        val (hint, restM) = naechsteKurve(hints, 600.0)!!
        assertEquals(TurnRichtung.LINKS, hint.richtung)
        assertEquals(300.0, restM, 1e-9)
    }

    @Test
    fun `nach der letzten Kurve gibt es nichts mehr`() {
        val hints = listOf(hintBei(500.0), hintBei(900.0))
        assertNull(naechsteKurve(hints, 950.0))
        assertNull(naechsteKurve(emptyList(), 0.0))
    }

    @Test
    fun `exakt auf dem Kurvenpunkt gilt die Kurve als ueberfahren`() {
        // Dieselbe Grenzziehung wie im TurnAnnouncer (distanzM <= doneM):
        // Wer im Scheitel steht, bekommt schon die naechste Kurve gezeigt.
        val hints = listOf(hintBei(500.0), hintBei(900.0, TurnRichtung.LINKS))
        val (hint, restM) = naechsteKurve(hints, 500.0)!!
        assertEquals(900.0, hint.distanzM)
        assertEquals(400.0, restM, 1e-9)
    }

    // --- Ansagetexte ---

    @Test
    fun `Ansagetexte sind deutsch und auf 50er gerundet`() {
        assertEquals("In 100 Metern links.", turnAnsageText(TurnRichtung.LINKS, 100.0))
        assertEquals("In 150 Metern rechts.", turnAnsageText(TurnRichtung.RECHTS, 137.0))
        assertEquals("In 50 Metern rechts.", turnAnsageText(TurnRichtung.RECHTS, 44.0))
        assertEquals("In 250 Metern scharf rechts.", turnAnsageText(TurnRichtung.KEHRE_RECHTS, 250.0))
        assertEquals("Gleich links.", turnAnsageText(TurnRichtung.LINKS, 39.9))
        assertEquals("Gleich scharf links.", turnAnsageText(TurnRichtung.KEHRE_LINKS, 10.0))
    }

    @Test
    fun `richtungsWort deckt alle Richtungen ab`() {
        assertEquals("links", richtungsWort(TurnRichtung.LINKS))
        assertEquals("rechts", richtungsWort(TurnRichtung.RECHTS))
        assertEquals("scharf links", richtungsWort(TurnRichtung.KEHRE_LINKS))
        assertEquals("scharf rechts", richtungsWort(TurnRichtung.KEHRE_RECHTS))
    }
}
