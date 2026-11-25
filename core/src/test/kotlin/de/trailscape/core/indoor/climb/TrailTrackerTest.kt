package de.trailscape.core.indoor.climb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrailTrackerTest {
    private val climb = Climb(
        index = 0, startM = 1_000.0, endM = 2_000.0, gainM = 60.0, avgGradePct = 6.0, maxGradePct = 7.0,
        startEleM = 100.0, topEleM = 160.0, category = ClimbCategory.UNCATEGORIZED,
    )

    private fun tracker(book: ClimbBestBook = ClimbBestBook(), isLoop: Boolean = false, length: Double = 5_000.0) =
        TrailTracker("ride:t", listOf(climb), length, isLoop, book)

    /** Faehrt von [from] bis [to] mit [mps] in Schritten von 1 s; liefert den letzten Zustand. */
    private class Ride(val t: TrailTracker) {
        var now = 0L
        var at = 0.0
        val seen = ArrayList<TrailLive>()
        fun go(to: Double, mps: Double, running: Boolean = true): TrailLive {
            var last = t.update(now, running, at, 6.0, 0L)
            while (at < to - 1e-9) {
                now += 1000
                at = minOf(to, at + mps)
                last = t.update(now, running, at, 6.0, 1_000_000L)
                seen.add(last)
            }
            return last
        }
        fun wait(ms: Long, running: Boolean = true): TrailLive { now += ms; return t.update(now, running, at, 0.0, 0L) }
        fun jump(to: Double): TrailLive { at = to; now += 1000; return t.update(now, false, at, 0.0, 0L) }
    }

    @Test
    fun `Ankuendigung, Einblendung und Ergebnis`() {
        val r = Ride(tracker())
        // 600 m vor dem Fuss: noch keine Ankuendigung (ANNOUNCE_M = 500 m)
        val before = r.go(400.0, 10.0)
        assertNull(before.climb)
        val ahead = r.go(700.0, 10.0).climb
        assertIs<ClimbLive.Ahead>(ahead)
        assertEquals(300.0, ahead.toStartM, 1e-6)
        assertNull(ahead.bestMs)

        val on = r.go(1_500.0, 5.0).climb
        assertIs<ClimbLive.On>(on)
        assertEquals(500.0, on.remainingM, 1e-6)
        assertEquals(30.0, on.remainingGainM, 1e-6)
        assertEquals(100_000L, on.elapsedMs)
        assertTrue(on.ranked)
        assertNull(on.deltaToBestMs)

        val done = r.go(2_010.0, 5.0).climb
        assertIs<ClimbLive.Done>(done)
        assertEquals(200_000L, done.effort.timeMs)
        assertTrue(done.effort.firstTime)
        assertTrue(done.effort.newBest)
        assertEquals("ride:t@1000-2000", done.effort.climbKey)

        // Das Ergebnis bleibt RESULT_SHOW_MS stehen, danach nichts mehr.
        assertIs<ClimbLive.Done>(r.wait(TrailTracker.RESULT_SHOW_MS - 4_000).climb)
        assertNull(r.wait(3_000).climb)

        val bests = r.t.drainNewBests()
        assertEquals(1, bests.size)
        assertEquals(200_000L, bests[0].timeMs)
        assertEquals(ClimbBest.SPLITS + 1, bests[0].splitsMs.size)
        assertEquals(0L, bests[0].splitsMs.first())
        assertEquals(100_000L, bests[0].splitsMs[5])
        assertTrue(r.t.drainNewBests().isEmpty())
        assertEquals(1, r.t.efforts().size)
    }

    @Test
    fun `Abstand zur Bestzeit und neue Bestzeit`() {
        val first = Ride(tracker())
        first.go(2_100.0, 5.0)
        val book = first.t.book()

        val second = Ride(tracker(book))
        val ahead = second.go(800.0, 10.0).climb
        assertIs<ClimbLive.Ahead>(ahead)
        assertEquals(200_000L, ahead.bestMs)
        // Schneller: 1500 m nach 50 s statt 100 s
        second.go(1_000.0, 10.0)
        val on = second.go(1_500.0, 10.0).climb
        assertIs<ClimbLive.On>(on)
        assertEquals(-50_000L, on.deltaToBestMs)
        assertEquals(200_000L, on.bestMs)
        val done = second.go(2_000.0, 10.0).climb
        assertIs<ClimbLive.Done>(done)
        assertEquals(100_000L, done.effort.timeMs)
        assertTrue(done.effort.newBest)
        assertFalse(done.effort.firstTime)
        assertEquals(100_000L, done.effort.improvementMs)
        assertEquals(100_000L, second.t.book().bestFor("ride:t", climb)!!.timeMs)

        // Langsamer: keine neue Bestzeit
        val third = Ride(tracker(second.t.book()))
        val slow = third.go(2_000.0, 4.0).climb
        assertIs<ClimbLive.Done>(slow)
        assertFalse(slow.effort.newBest)
        assertTrue(third.t.drainNewBests().isEmpty())
    }

    @Test
    fun `Pausen zaehlen nicht`() {
        val r = Ride(tracker())
        r.go(1_500.0, 10.0)
        r.wait(60_000, running = false)
        r.wait(1, running = false)
        val done = r.go(2_000.0, 10.0).climb
        assertIs<ClimbLive.Done>(done)
        assertEquals(100_000L, done.effort.timeMs)
    }

    @Test
    fun `Start mitten im Anstieg und Versetzen sind ohne Wertung`() {
        val r = Ride(tracker())
        r.at = 1_400.0
        val on = r.go(1_500.0, 10.0).climb
        assertIs<ClimbLive.On>(on)
        assertFalse(on.ranked)
        val done = r.go(2_000.0, 10.0).climb
        assertIs<ClimbLive.Done>(done)
        assertFalse(done.effort.ranked)
        assertFalse(done.effort.newBest)
        assertTrue(r.t.drainNewBests().isEmpty())

        // Versetzt: laufender Versuch verfaellt, im Anstieg gelandet = ohne Wertung
        val s = Ride(tracker())
        s.go(1_200.0, 10.0)
        val moved = s.jump(1_600.0).climb
        assertIs<ClimbLive.On>(moved)
        assertFalse(moved.ranked)
        // Versetzt vor den Anstieg: wieder gewertet
        s.jump(500.0)
        val done2 = s.go(2_000.0, 10.0).climb
        assertIs<ClimbLive.Done>(done2)
        assertTrue(done2.effort.ranked)
    }

    @Test
    fun `Start am Fuss wird gewertet`() {
        val r = Ride(tracker())
        r.at = 1_000.0
        val on = r.go(1_100.0, 10.0).climb
        assertIs<ClimbLive.On>(on)
        assertTrue(on.ranked)
    }

    @Test
    fun `Runde wertet jede Runde und kuendigt ueber die Ziellinie an`() {
        val t = TrailTracker("ride:t", listOf(climb.copy(startM = 200.0, endM = 1_200.0)), 3_000.0, true, ClimbBestBook())
        val r = Ride(t)
        r.go(2_900.0, 10.0)
        // 100 m vor dem Ziel, Anstieg 200 m nach der Linie: 300 m voraus
        val ahead = t.update(r.now + 1000, true, 2_900.0, 0.0, 0L).climb
        assertIs<ClimbLive.Ahead>(ahead)
        assertEquals(300.0, ahead.toStartM, 1e-6)
        // Ueber die Linie fahren (gefaltete Stelle)
        var now = r.now + 1000
        var d = 2_900.0
        var last: TrailLive = TrailLive.NONE
        repeat(200) {
            now += 1000
            d = (d + 10.0) % 3_000.0
            last = t.update(now, true, d, 5.0, 0L)
        }
        // Runde 1 und Runde 2, beide gewertet; die zweite vergleicht mit der ersten
        assertEquals(listOf(1, 2), t.efforts().map { it.lap })
        assertTrue(t.efforts().all { it.ranked })
        assertEquals(t.efforts()[0].timeMs, t.efforts()[1].previousBestMs)
        assertNotNull(last)
    }

    @Test
    fun `Kilometermarken`() {
        val t = TrailTracker("ride:t", emptyList(), 5_000.0, false, ClimbBestBook())
        val r = Ride(t)
        val m = r.go(1_005.0, 10.0).marker
        assertEquals(TrailMarker.Kilometer(1), m)
        assertNull(r.wait(TrailTracker.MARKER_SHOW_MS).marker)
        r.go(4_950.0, 10.0)
        // Keine Marke kurz vor dem Ziel (5 km)
        assertTrue(r.seen.mapNotNull { it.marker }.toSet() == setOf(TrailMarker.Kilometer(1), TrailMarker.Kilometer(2), TrailMarker.Kilometer(3), TrailMarker.Kilometer(4)))
        assertEquals(5_000.0, TrailTracker.kmMarkIntervalFor(80_000.0))
        assertEquals(1_000.0, TrailTracker.kmMarkIntervalFor(20_000.0))
    }
}
