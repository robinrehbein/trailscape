package de.trailscape.core.room

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoomStateTest {
    private val route = RoomRoute("katalog/probe", 5000.0)
    private val room = RoomInfo("AbCdEfGhIjKlMnOp", route, 10, 0L, 10_000_000L, null)
    private val t0 = 1_000_000L

    private fun welcome(states: List<RiderStateEntry> = emptyList()) = ServerMessage.Welcome(
        v = 1,
        serverTime = t0,
        you = ServerMessage.You("Ich_0001", "key", host = false),
        room = room,
        riders = listOf(
            RiderInfo("Ich_0001", "Ich"),
            RiderInfo("Anna0001", "Anna"),
            RiderInfo("Ben_0001", "Ben", sharePower = false),
        ),
        states = states,
        limits = RoomLimits(),
    )

    private fun state(id: String, at: Long, distM: Double, speed: Double, powerW: Int? = null, lane: Int? = null) =
        RiderStateEntry(id, at, distM, speed, powerW, null, lane)

    private fun start(): RoomState = RoomState().reduce(welcome())

    @Test
    fun `welcome setzt Raum, sich selbst und die anderen`() {
        val s = start()
        assertEquals(RoomPhase.IN_ROOM, s.phase)
        assertEquals("Ich_0001", s.selfId)
        assertEquals(setOf("Anna0001", "Ben_0001"), s.riders.keys, "man selbst fehlt in der Liste")
        assertEquals(room, s.room)
    }

    @Test
    fun `Fahrer kommen, aendern sich und gehen`() {
        var s = start()
        s = s.reduce(ServerMessage.Rider(RiderInfo("Cleo0001", "Cleo")))
        assertEquals(3, s.riders.size)
        s = s.reduce(ServerMessage.Snapshot(t0, listOf(state("Cleo0001", t0, 5.0, 5.0))))
        s = s.reduce(ServerMessage.Rider(RiderInfo("Cleo0001", "Cleo neu", sharePower = false)))
        assertEquals("Cleo neu", s.riders["Cleo0001"]!!.info.name)
        assertEquals(1, s.riders["Cleo0001"]!!.samples.size, "Meldungen bleiben bei Namensänderung")
        s = s.reduce(ServerMessage.Left("Cleo0001", "leave"))
        assertNull(s.riders["Cleo0001"])
        // Echo von sich selbst wird ignoriert.
        assertEquals(s, s.reduce(ServerMessage.Rider(RiderInfo("Ich_0001", "Ich"))))
    }

    @Test
    fun `Meldungen - neue anhaengen, alte und unbekannte verwerfen, hoechstens drei`() {
        var s = start()
        for (i in 0 until 5) s = s.reduce(ServerMessage.Snapshot(t0 + i * 250, listOf(state("Anna0001", t0 + i * 250, i * 2.0, 8.0))))
        assertEquals(listOf(4.0, 6.0, 8.0), s.riders["Anna0001"]!!.samples.map { it.distM })
        val before = s
        s = s.reduce(ServerMessage.Snapshot(t0, listOf(state("Anna0001", t0 + 250, 99.0, 8.0))))
        assertEquals(before, s, "älter als die letzte Meldung")
        s = s.reduce(ServerMessage.Snapshot(t0, listOf(state("Fremd001", t0 + 5000, 99.0, 8.0), state("Ich_0001", t0 + 5000, 1.0, 1.0))))
        assertEquals(before, s, "unbekannt oder man selbst")
    }

    @Test
    fun `Interpolation zwischen zwei Meldungen, Extrapolation danach mit Grenze`() {
        val samples = listOf(StateSample(1000, 100.0, 8.0), StateSample(1500, 104.0, 8.0))
        assertEquals(100.0, estimateDistance(samples, 900, 2000)!!, 1e-9)
        assertEquals(102.0, estimateDistance(samples, 1250, 2000)!!, 1e-9)
        assertEquals(104.0, estimateDistance(samples, 1500, 2000)!!, 1e-9)
        assertEquals(108.0, estimateDistance(samples, 2000, 2000)!!, 1e-9)
        assertEquals(120.0, estimateDistance(samples, 3500, 2000)!!, 1e-9)
        assertEquals(120.0, estimateDistance(samples, 60_000, 2000)!!, 1e-9, "nicht weiter als 2 s fortschreiben")
        assertNull(estimateDistance(emptyList(), 0, 2000))
    }

    @Test
    fun `Rangfolge, Abstaende und Zeitabstaende`() {
        var s = start()
        s = s.reduce(ServerMessage.Snapshot(t0, listOf(state("Anna0001", t0, 200.0, 10.0, powerW = 250), state("Ben_0001", t0, 90.0, 5.0, powerW = 150))))
        val me = SelfPosition(distM = 150.0, speedMps = 8.0, name = "Ich")
        val p = s.positions(t0 + 1000, me)
        assertEquals(listOf("Anna0001", "Ich_0001", "Ben_0001"), p.map { it.id })
        assertEquals(listOf(1, 2, 3), p.map { it.rank })
        val anna = p[0]
        assertEquals(210.0, anna.distM, 1e-9, "1 s fortgeschrieben")
        assertEquals(0.0, anna.gapToLeaderM, 1e-9)
        assertEquals(60.0, anna.gapToSelfM!!, 1e-9)
        assertEquals(60.0 / 8.0, anna.timeGapToSelfS!!, 1e-9, "ich fahre hinterher: mein Tempo")
        assertEquals(250, anna.powerW)
        val ben = p[2]
        assertEquals(95.0, ben.distM, 1e-9)
        assertEquals(-55.0, ben.gapToSelfM!!, 1e-9)
        assertEquals(-55.0 / 5.0, ben.timeGapToSelfS!!, 1e-9, "Ben fährt hinterher: sein Tempo")
        assertNull(ben.powerW, "Ben teilt keine Leistung")
        assertEquals(115.0, ben.gapToLeaderM, 1e-9)
        assertTrue(p[1].isSelf)
        assertEquals(60.0, p[1].gapToLeaderM, 1e-9)
    }

    @Test
    fun `Gleichstand wird stabil nach Kennung geordnet, ohne eigene Stelle keine Abstaende zu mir`() {
        var s = start()
        s = s.reduce(ServerMessage.Snapshot(t0, listOf(state("Ben_0001", t0, 100.0, 0.0), state("Anna0001", t0, 100.0, 0.0))))
        val p = s.positions(t0)
        assertEquals(listOf("Anna0001", "Ben_0001"), p.map { it.id })
        assertNull(p[0].gapToSelfM)
        assertNull(p[0].timeGapToSelfS)
    }

    @Test
    fun `stale nach 3 s, ganz weg nach 30 s, Tempo 0 nach Extrapolationsgrenze`() {
        var s = start()
        s = s.reduce(ServerMessage.Snapshot(t0, listOf(state("Anna0001", t0, 100.0, 10.0))))
        assertFalse(s.positions(t0 + 2_900).single().stale)
        val stale = s.positions(t0 + 3_100).single()
        assertTrue(stale.stale)
        assertEquals(0.0, stale.speedMps, 1e-9)
        assertEquals(120.0, stale.distM, 1e-9)
        assertEquals(3_100L, stale.ageMs)
        assertEquals(1, s.positions(t0 + 30_000).size)
        assertEquals(0, s.positions(t0 + 30_001).size)
    }

    @Test
    fun `Countdown, Ende, Fehler, Wiederbeitritt behaelt Meldungen`() {
        var s = start()
        s = s.reduce(ServerMessage.Countdown(t0 + 30_000, t0))
        assertEquals(t0 + 30_000, s.startAt)
        s = s.reduce(ServerMessage.Snapshot(t0, listOf(state("Anna0001", t0, 10.0, 1.0))))
        s = s.reduce(welcome())
        assertEquals(1, s.riders["Anna0001"]!!.samples.size)
        s = s.reduce(ServerMessage.Error("IMPLAUSIBLE", null))
        assertEquals("IMPLAUSIBLE", s.lastErrorCode)
        assertEquals(s, s.reduce(ServerMessage.Unknown("emote")))
        assertEquals(s, s.reduce(ServerMessage.Pong(1, 2)))
        s = s.reduce(ServerMessage.Ended("host", null))
        assertEquals(RoomPhase.ENDED, s.phase)
        assertEquals("host", s.endedReason)
    }

    @Test
    fun `welcome bringt die letzten Zustaende mit`() {
        val s = RoomState().reduce(welcome(listOf(state("Ben_0001", t0 - 100, 42.0, 0.0))))
        assertEquals(42.0, s.positions(t0).single().distM, 1e-9)
    }
}
