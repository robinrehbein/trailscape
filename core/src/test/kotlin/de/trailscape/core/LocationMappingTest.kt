package de.trailscape.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests fuer [toLocationSample].
 *
 * Der Schwerpunkt liegt bewusst nicht auf dem Durchreichen der Koordinaten,
 * sondern auf den drei Faellen, die rohes GPS gegenueber dem frueher
 * benutzten gebuendelten Standortdienst neu aufwirft: keine Hoehe, keine
 * Genauigkeit, keine Geschwindigkeit. Die letzten beiden Tests pruefen
 * ausserdem im Zusammenspiel mit [PointFilter] nach, dass die gewaehlten
 * Ersatzwerte dort auch wirklich das Gemeinte ausloesen — genau daran haengt
 * die Aufzeichnungsqualitaet.
 */
class LocationMappingTest {

    private companion object {
        const val EPS = 1e-9
        const val T0 = 1_700_000_000_000L
        const val NOW = 1_700_000_042_000L
    }

    private fun sample(
        lat: Double = 52.0,
        lon: Double = 13.0,
        altitudeM: Double? = 120.0,
        accuracyM: Double? = 8.0,
        speedMps: Double? = 5.0,
        timeMs: Long = T0,
        fallbackTimeMs: Long = NOW,
    ) = toLocationSample(
        lat = lat,
        lon = lon,
        altitudeM = altitudeM,
        accuracyM = accuracyM,
        speedMps = speedMps,
        timeMs = timeMs,
        fallbackTimeMs = fallbackTimeMs,
    )

    // --- Vollstaendige Meldung ---

    @Test
    fun `vollstaendige Meldung wird unveraendert durchgereicht`() {
        val result = sample()

        assertEquals(52.0, result.lat, EPS)
        assertEquals(13.0, result.lon, EPS)
        assertEquals(120.0, result.altitudeM, EPS)
        assertEquals(8.0, result.accuracyM, EPS)
        assertEquals(5.0, result.speedMps, EPS)
        assertEquals(T0, result.timeMs)
    }

    @Test
    fun `Geschwindigkeit null wird als echte Null uebernommen`() {
        // Stehen an der Ampel ist eine Messung, kein fehlender Wert.
        assertEquals(0.0, sample(speedMps = 0.0).speedMps, EPS)
    }

    @Test
    fun `Genauigkeit null wird als echte Null uebernommen`() {
        assertEquals(0.0, sample(accuracyM = 0.0).accuracyM, EPS)
    }

    // --- Fehlende Hoehe ---

    @Test
    fun `fehlende Hoehe wird nicht zu Meereshoehe`() {
        val result = sample(altitudeM = null)

        assertTrue(result.altitudeM.isNaN(), "Fehlende Hoehe muss NaN sein, nicht 0.0")
    }

    @Test
    fun `unendliche Hoehe gilt als fehlend`() {
        assertTrue(sample(altitudeM = Double.NEGATIVE_INFINITY).altitudeM.isNaN())
    }

    @Test
    fun `fehlende Hoehe fuehrt zu einem Punkt ohne Hoehe`() {
        val filter = PointFilter()

        val result = filter.offer(sample(altitudeM = null))

        val accepted = assertIs<PointFilterResult.Accepted>(result)
        assertNull(accepted.point.ele, "Ohne Hoehe darf keine 0.0 in die Tour geschrieben werden")
    }

    @Test
    fun `gemeldete Hoehe landet im Punkt`() {
        val filter = PointFilter()

        val result = filter.offer(sample(altitudeM = 331.5))

        val accepted = assertIs<PointFilterResult.Accepted>(result)
        assertEquals(331.5, assertNotNull(accepted.point.ele), EPS)
    }

    // --- Fehlende Genauigkeit ---

    @Test
    fun `fehlende Genauigkeit verwirft den Punkt nicht`() {
        val filter = PointFilter()

        val result = filter.offer(sample(accuracyM = null))

        assertEquals(0.0, sample(accuracyM = null).accuracyM, EPS)
        assertIs<PointFilterResult.Accepted>(result)
    }

    @Test
    fun `negative Genauigkeit gilt als fehlend`() {
        assertEquals(0.0, sample(accuracyM = -1.0).accuracyM, EPS)
    }

    @Test
    fun `schlechte Genauigkeit bleibt schlecht`() {
        val filter = PointFilter()

        val result = filter.offer(sample(accuracyM = 80.0))

        val rejected = assertIs<PointFilterResult.Rejected>(result)
        assertEquals(PointRejection.LOW_ACCURACY, rejected.reason)
    }

    // --- Fehlende Geschwindigkeit ---

    @Test
    fun `fehlende Geschwindigkeit wird negativ gemeldet`() {
        assertTrue(sample(speedMps = null).speedMps < 0.0)
    }

    @Test
    fun `fehlende Geschwindigkeit laesst den Ersatzweg des Filters greifen`() {
        // Ohne den negativen Ersatzwert wuerde PointFilter „0.0 m/s" als
        // gemessene Geschwindigkeit uebernehmen und 0 km/h anzeigen, obwohl
        // die beiden Punkte klar Fahrt zeigen.
        val filter = PointFilter()

        filter.offer(sample(lat = 52.0, lon = 13.0, speedMps = null, timeMs = T0))
        filter.offer(sample(lat = 52.0, lon = 13.001, speedMps = null, timeMs = T0 + 5_000))

        val speed = assertNotNull(filter.currentSpeedKmh, "Ersatzweg haette greifen muessen")
        assertTrue(speed > 40.0, "Aus 68 m in 5 s werden rund 49 km/h, war: $speed")
    }

    @Test
    fun `gemeldete Geschwindigkeit hat Vorrang vor dem Ersatzweg`() {
        val filter = PointFilter()

        filter.offer(sample(lat = 52.0, lon = 13.0, speedMps = 5.0, timeMs = T0))

        assertEquals(18.0, assertNotNull(filter.currentSpeedKmh), EPS)
    }

    // --- Zeitstempel ---

    @Test
    fun `fehlender Zeitstempel wird durch die Geraeteuhr ersetzt`() {
        assertEquals(NOW, sample(timeMs = 0L).timeMs)
        assertEquals(NOW, sample(timeMs = -1L).timeMs)
    }

    @Test
    fun `Satellitenzeit weit vor der Geraeteuhr bleibt stehen`() {
        // Bewusst KEIN Plausibilitaetsfenster: Bei einem Geraet ohne Netzzeit
        // ist die Satellitenzeit die genauere der beiden Quellen.
        val result = sample(timeMs = T0, fallbackTimeMs = T0 + 86_400_000L)

        assertEquals(T0, result.timeMs)
    }
}
