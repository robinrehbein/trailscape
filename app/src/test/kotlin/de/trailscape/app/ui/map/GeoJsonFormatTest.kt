package de.trailscape.app.ui.map

import de.trailscape.core.TrackPoint
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests der schnellen Koordinaten-Formatierung (`GeoJsonFormat.kt`). Der
 * Massstab ist immer `String.format(Locale.ROOT, "%.6f", …)` — genau das hat
 * die Funktion ersetzt, und jede Abweichung wuerde Linien um Zentimeter
 * verschieben oder, schlimmer, ungueltiges JSON erzeugen.
 */
class GeoJsonFormatTest {

    private fun reference(value: Double): String = String.format(Locale.ROOT, "%.6f", value)

    private fun assertLikeFormat(value: Double) {
        assertEquals(reference(value), formatCoordinate(value), "Wert $value")
    }

    @Test
    fun `Grenzfaelle entsprechen String format`() {
        listOf(
            0.0, -0.0, 1.0, -1.0, 180.0, -180.0, 90.0, -90.0,
            // Negative Werte, die auf null runden, behalten ihr Minus.
            -0.0000001, -0.0000004999, 0.0000004999,
            // x,5 in der 7. Stelle: Java rundet HALF_UP auf der kuerzesten
            // Dezimaldarstellung, nicht auf dem exakten Binaerwert.
            0.0000005, -0.0000005, 0.0000015, 0.0000025, 2.5e-7,
            1.0000005, 13.4050005, -13.4050005, 0.1234565, -179.9999995, 179.9999995,
            0.15, 52.520008, 13.404954, 9.999999, 9.9999995, -9.9999995, 99.9999996,
            // Ausserhalb des schnellen Wegs.
            1e6, -1e6, 1e7, 123456789.123456789, Double.MAX_VALUE, Double.MIN_VALUE,
            Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
        ).forEach(::assertLikeFormat)
    }

    @Test
    fun `zufaellige Koordinaten entsprechen String format`() {
        val random = Random(20260925)
        repeat(200_000) {
            assertLikeFormat(random.nextDouble(-180.0, 180.0))
        }
    }

    @Test
    fun `Werte genau auf und neben der Rundungsgrenze entsprechen String format`() {
        // Gezielt die heiklen Faelle: sechs Stellen plus eine 5 (und knapp
        // daneben), ueber den ganzen Koordinatenbereich verteilt.
        val random = Random(42)
        repeat(50_000) {
            val micro = random.nextLong(-180_000_000L, 180_000_000L)
            val tie = (micro * 10 + if (micro < 0) -5 else 5) / 1e7
            assertLikeFormat(tie)
            assertLikeFormat(Math.nextUp(tie))
            assertLikeFormat(Math.nextDown(tie))
            assertLikeFormat(micro / 1e6)
        }
    }

    @Test
    fun `kleine Betraege entsprechen String format`() {
        val random = Random(7)
        repeat(50_000) {
            assertLikeFormat(random.nextDouble(-0.001, 0.001))
        }
    }

    @Test
    fun `Formatierung haengt nicht vom Gebietsschema ab`() {
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("13.405000", formatCoordinate(13.405))
            assertEquals("-52.520000", formatCoordinate(-52.52))
        } finally {
            Locale.setDefault(before)
        }
    }

    @Test
    fun `unter zwei Punkten gibt es keine Linie`() {
        assertEquals(EMPTY_FEATURES, lineFeatureCollection(emptyList()))
        assertEquals(EMPTY_FEATURES, lineFeatureCollection(listOf(TrackPoint(52.5, 13.4))))
    }

    @Test
    fun `Linie ist gueltiges GeoJSON mit lon-lat-Reihenfolge`() {
        val json = lineFeatureCollection(
            listOf(TrackPoint(52.52, 13.405), TrackPoint(-33.8688, -151.2093)),
        )
        val coordinates = Json.parseToJsonElement(json).jsonObject["features"]!!.jsonArray[0]
            .jsonObject["geometry"]!!.jsonObject["coordinates"]!!.jsonArray
        assertEquals(2, coordinates.size)
        assertEquals("13.405000", coordinates[0].jsonArray[0].jsonPrimitive.content)
        assertEquals("52.520000", coordinates[0].jsonArray[1].jsonPrimitive.content)
        assertEquals("-151.209300", coordinates[1].jsonArray[0].jsonPrimitive.content)
        assertEquals("-33.868800", coordinates[1].jsonArray[1].jsonPrimitive.content)
    }
}
