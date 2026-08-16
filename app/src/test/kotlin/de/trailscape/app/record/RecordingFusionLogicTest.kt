package de.trailscape.app.record

import de.trailscape.core.LocationFusion
import de.trailscape.core.Quelle
import de.trailscape.core.TrackPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Tests der Handy-Bruecke-Entscheidungslogik aus `RecordingFusionLogic.kt`.
 *
 * Der wichtigste Fall steht zuerst: Ohne jemals eine Uhr-Probe einzuspeisen,
 * muss `waehlePunktZumAufzeichnen` fuer eine Reihe von Telefon-Punkten exakt
 * die Telefon-Punkte selbst liefern — byteidentisch, nicht nur „nah dran".
 * Sonst wuerde die Handy-Bruecke jede Aufzeichnung ohne Uhr veraendern, obwohl
 * niemand eine Uhr benutzt (siehe `RecordingFusionLogic.kt`, Klassendoc).
 */
class RecordingFusionLogicTest {

    private fun telefonPunkt(zeitMs: Long, lat: Double, lon: Double) =
        TrackPoint(lat = lat, lon = lon, ele = 120.0, time = zeitMs)

    @Test
    fun `nur Telefon-Quelle liefert exakt die Telefon-Punkte zurueck`() {
        val fusion = LocationFusion()
        val punkte = listOf(
            telefonPunkt(0L, 52.5200, 13.4050),
            telefonPunkt(5_000L, 52.5205, 13.4060),
            telefonPunkt(10_000L, 52.5210, 13.4070),
            telefonPunkt(15_000L, 52.5215, 13.4080),
        )

        for (punkt in punkte) {
            val fused = fusion.fuege(
                quelle = Quelle.TELEFON,
                zeitMs = punkt.time!!,
                lat = punkt.lat,
                lon = punkt.lon,
                hoeheM = punkt.ele,
                genauigkeitM = 8.0,
            )

            val aufgezeichnet = waehlePunktZumAufzeichnen(fused, punkt, letzteHf = null)

            // Byteidentisch, nicht nur naeherungsweise gleich: Ohne Uhr darf
            // sich am heutigen Verhalten NICHTS aendern.
            assertSame(punkt, aufgezeichnet)
        }
    }

    @Test
    fun `eine Uhr-Probe nach dem letzten Telefon-Fix liefert die fusionierte Position`() {
        val fusion = LocationFusion()
        val telefonPunkt = telefonPunkt(0L, 52.5200, 13.4050)
        fusion.fuege(
            quelle = Quelle.TELEFON,
            zeitMs = telefonPunkt.time!!,
            lat = telefonPunkt.lat,
            lon = telefonPunkt.lon,
            hoeheM = telefonPunkt.ele,
            genauigkeitM = 8.0,
        )

        // Die Uhr meldet kurz darauf eine eigene Position (das Telefon steckt
        // gerade ohne Fix in der Tasche).
        val fused = fusion.fuege(
            quelle = Quelle.UHR,
            zeitMs = 3_000L,
            lat = 52.5203,
            lon = 13.4055,
            hoeheM = 118.0,
            genauigkeitM = 12.0,
        )

        val aufgezeichnet = waehlePunktZumAufzeichnen(fused, telefonPunkt = null, letzteHf = null)

        requireNotNull(aufgezeichnet)
        requireNotNull(fused)
        assertEquals(fused.lat, aufgezeichnet.lat)
        assertEquals(fused.lon, aufgezeichnet.lon)
        assertEquals(fused.zeitMs, aufgezeichnet.time)
    }

    @Test
    fun `Herzfrequenz haengt unabhaengig von der Quelle an jedem aufgezeichneten Punkt`() {
        val fusion = LocationFusion()
        val telefonPunkt = telefonPunkt(0L, 52.5200, 13.4050)
        val fused = fusion.fuege(
            quelle = Quelle.TELEFON,
            zeitMs = telefonPunkt.time!!,
            lat = telefonPunkt.lat,
            lon = telefonPunkt.lon,
            hoeheM = telefonPunkt.ele,
            genauigkeitM = 8.0,
        )

        val aufgezeichnet = waehlePunktZumAufzeichnen(fused, telefonPunkt, letzteHf = 142)

        assertEquals(142, aufgezeichnet?.hr)
        // Position bleibt trotzdem exakt die des Telefons.
        assertEquals(telefonPunkt.lat, aufgezeichnet?.lat)
        assertEquals(telefonPunkt.lon, aufgezeichnet?.lon)
    }

    @Test
    fun `eine von der Fusion verworfene Probe faellt auf den Telefon-Punkt zurueck`() {
        val telefonPunkt = telefonPunkt(0L, 52.5200, 13.4050)

        val aufgezeichnet = waehlePunktZumAufzeichnen(fused = null, telefonPunkt = telefonPunkt, letzteHf = null)

        assertSame(telefonPunkt, aufgezeichnet)
    }

    @Test
    fun `ohne Telefon-Punkt und ohne fusionierte Position gibt es nichts aufzuzeichnen`() {
        assertNull(waehlePunktZumAufzeichnen(fused = null, telefonPunkt = null, letzteHf = null))
    }
}
