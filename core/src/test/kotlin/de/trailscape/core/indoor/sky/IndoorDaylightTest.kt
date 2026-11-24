package de.trailscape.core.indoor.sky

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Tageszeit-Wahl, Sonnenzeit aus Weltzeit und Ort, helle Daemmerung als Untergrenze. */
class IndoorDaylightTest {
    private val lat = 51.3
    private val lon = 9.5

    /** 24.06.2026 in Weltzeit, Stunde [h]. */
    private fun june24Utc(h: Double): Long = 1_782_259_200_000L + (h * 3_600_000).toLong()

    @Test
    fun `Tag im Jahr nach Kalender`() {
        assertEquals(1, IndoorDaylight.dayOfYear(0)) // 1.1.1970
        assertEquals(175, IndoorDaylight.dayOfYear(1_782_259_200_000L / 86_400_000L)) // 24.6.2026
        assertEquals(60, IndoorDaylight.dayOfYear(19_782)) // 29.2.2024 (Schaltjahr)
        assertEquals(366, IndoorDaylight.dayOfYear(20_088)) // 31.12.2024
    }

    @Test
    fun `Sonnenzeit ist Weltzeit plus Laenge`() {
        // 11:00 UTC bei 9,5° Ost: mittlere Ortszeit 11:38, Zeitgleichung im Juni etwa −2 min.
        val (hour, day) = IndoorDaylight.solarTime(june24Utc(11.0), lon)
        assertEquals(175, day)
        assertEquals(11.0 + 9.5 / 15.0 - 2.0 / 60.0, hour, 3.0 / 60.0)
        // Ueber Mitternacht: 23:30 UTC bei 30° Ost ist schon der naechste Tag.
        val (h2, d2) = IndoorDaylight.solarTime(june24Utc(23.5), 30.0)
        assertEquals(176, d2)
        assertTrue(h2 in 1.3..1.6, "$h2")
    }

    @Test
    fun `Automatisch am Tag folgt der Uhr`() {
        val s = IndoorDaylight.skySetting(IndoorTimeOfDay.AUTO, june24Utc(8.0), lat, lon)
        assertEquals(175, s.dayOfYear)
        assertEquals(8.0 + 9.5 / 15.0, s.hourOfDay, 0.1)
        assertTrue(IndoorDaylight.sunAltitudeDeg(s.hourOfDay, s.dayOfYear, lat) > 30.0)
    }

    @Test
    fun `nachts helle Daemmerung statt Dunkelheit`() {
        // 22:30 UTC = kurz nach Mitternacht Sonnenzeit: naechste Daemmerung ist die abendliche?
        for (utc in listOf(20.5, 22.0, 0.5, 2.0)) {
            val s = IndoorDaylight.skySetting(IndoorTimeOfDay.AUTO, june24Utc(utc), lat, lon)
            val alt = IndoorDaylight.sunAltitudeDeg(s.hourOfDay, s.dayOfYear, lat)
            assertEquals(IndoorDaylight.DUSK_FLOOR_DEG, alt, 0.05, "UTC $utc → ${s.hourOfDay}")
        }
        // Abends liegt die Daemmerung im Westen (nach Mittag), morgens im Osten.
        val evening = IndoorDaylight.skySetting(IndoorTimeOfDay.AUTO, june24Utc(20.5), lat, lon)
        assertTrue(evening.hourOfDay > 12.0)
        val morning = IndoorDaylight.skySetting(IndoorTimeOfDay.AUTO, june24Utc(2.0), lat, lon)
        assertTrue(morning.hourOfDay < 12.0)
    }

    @Test
    fun `Polarwinter nimmt den Mittag`() {
        // 21.12. bei 75° Nord: Die Sonne geht nicht auf.
        val dec21 = 1_797_811_200_000L + 3 * 3_600_000L
        val s = IndoorDaylight.skySetting(IndoorTimeOfDay.AUTO, dec21, 75.0, 20.0)
        assertEquals(12.0, s.hourOfDay, 1e-9)
    }

    @Test
    fun `feste Tageszeiten haben den versprochenen Sonnenstand`() {
        val m = IndoorDaylight.skySetting(IndoorTimeOfDay.MORNING, 0L, lat, lon)
        val n = IndoorDaylight.skySetting(IndoorTimeOfDay.NOON, 0L, lat, lon)
        val e = IndoorDaylight.skySetting(IndoorTimeOfDay.EVENING, 0L, lat, lon)
        for (s in listOf(m, n, e)) assertEquals(IndoorDaylight.PRESET_DAY_OF_YEAR, s.dayOfYear)
        assertEquals(IndoorDaylight.MORNING_ALTITUDE_DEG, IndoorDaylight.sunAltitudeDeg(m.hourOfDay, m.dayOfYear, lat), 0.01)
        assertEquals(IndoorDaylight.EVENING_ALTITUDE_DEG, IndoorDaylight.sunAltitudeDeg(e.hourOfDay, e.dayOfYear, lat), 0.01)
        assertTrue(m.hourOfDay < 12.0 && e.hourOfDay > 12.0)
        // Morgens im Osten, abends im Westen, mittags hoch im Sueden.
        assertTrue(IndoorDaylight.sunEnu(m.hourOfDay, m.dayOfYear, lat).first > 0.5)
        assertTrue(IndoorDaylight.sunEnu(e.hourOfDay, e.dayOfYear, lat).first < -0.5)
        assertTrue(IndoorDaylight.sunAltitudeDeg(n.hourOfDay, n.dayOfYear, lat) > 55.0)
        // Unabhaengig von der Uhr.
        assertEquals(m, IndoorDaylight.skySetting(IndoorTimeOfDay.MORNING, june24Utc(23.0), lat, lon))
    }

    @Test
    fun `gespeicherter Name`() {
        assertEquals(IndoorTimeOfDay.EVENING, IndoorTimeOfDay.fromName("EVENING"))
        assertEquals(IndoorTimeOfDay.AUTO, IndoorTimeOfDay.fromName(null))
        assertEquals(IndoorTimeOfDay.AUTO, IndoorTimeOfDay.fromName("quatsch"))
    }

    @Test
    fun `Mittagssonne im Sueden`() {
        val (e, n, u) = IndoorDaylight.sunEnu(12.0, 172, 51.0)
        assertTrue(abs(e) < 1e-6)
        assertTrue(n < 0.0)
        assertTrue(u > 0.85)
    }
}
