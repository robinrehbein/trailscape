package de.trailscape.core.room

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RoomClockTest {
    @Test
    fun `Versatz aus der schnellsten Probe`() {
        // Server-Uhr = Geräte-Uhr + 1 000 000.
        var clock = RoomClock()
        assertNull(clock.offsetMs)
        clock = clock.add(ClockSample(clientSentMs = 0, serverMs = 1_000_400, clientReceivedMs = 600)) // rtt 600, asymmetrisch
        clock = clock.add(ClockSample(clientSentMs = 1000, serverMs = 1_001_050, clientReceivedMs = 1100)) // rtt 100
        clock = clock.add(ClockSample(clientSentMs = 2000, serverMs = 1_002_900, clientReceivedMs = 3000)) // rtt 1000
        assertEquals(100L, clock.best?.rttMs)
        assertEquals(1_000_000L, clock.offsetMs)
        assertEquals(50L, clock.uncertaintyMs)
        assertEquals(1_005_000L, clock.toServer(5_000))
        assertEquals(5_000L, clock.toClient(1_005_000))
    }

    @Test
    fun `grobe Schaetzung bis zur ersten Probe, unplausible Proben fallen weg`() {
        var clock = RoomClock().withServerTime(serverMs = 2_000_000, clientReceivedMs = 1_000)
        assertEquals(1_999_000L, clock.offsetMs)
        clock = clock.add(ClockSample(5_000, 9_999_999, 4_000)) // rtt negativ
        clock = clock.add(ClockSample(0, 9_999_999, 20_000)) // rtt 20 s
        assertEquals(1_999_000L, clock.offsetMs)
        assertNull(clock.uncertaintyMs)
    }

    @Test
    fun `nur die letzten Proben zaehlen`() {
        var clock = RoomClock(window = 3)
        clock = clock.add(ClockSample(0, 100, 10)) // rtt 10, Versatz 95
        for (i in 1..3) clock = clock.add(ClockSample(i * 1000L, i * 1000L + 500, i * 1000L + 200)) // rtt 200, Versatz 400
        assertEquals(400L, clock.offsetMs)
        assertEquals(3, clock.samples.size)
    }
}
