package de.trailscape.core.indoor.climb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClimbBestBookTest {
    private val climb = Climb(0, 1_000.0, 2_000.0, 60.0, 6.0, 7.0, 100.0, 160.0, ClimbCategory.UNCATEGORIZED)
    private fun best(time: Long, start: Double = 1_000.0, route: String = "ride:a") =
        ClimbBest(route, start, start + 1_000.0, time, List(11) { time * it / 10 }, 42L)

    @Test
    fun `nur schnellere Zeiten ersetzen, Zuordnung mit Toleranz`() {
        val b = ClimbBestBook().record(best(200_000)).record(best(250_000, start = 1_050.0))
        assertEquals(1, b.bests.size)
        assertEquals(200_000L, b.bestFor("ride:a", climb)!!.timeMs)
        val faster = b.record(best(150_000, start = 1_080.0))
        assertEquals(1, faster.bests.size)
        assertEquals(150_000L, faster.bestFor("ride:a", climb)!!.timeMs)
        // Andere Strecke, weit weg: eigener Eintrag
        assertNull(faster.bestFor("ride:b", climb))
        assertNull(faster.bestFor("ride:a", climb.copy(startM = 1_400.0, endM = 2_400.0)))
    }

    @Test
    fun `JSON hin und zurueck, kaputte Eintraege werden uebersprungen`() {
        val b = ClimbBestBook().record(best(200_000)).record(best(90_000, start = 5_000.0, route = "catalog:x"))
        val back = ClimbBestBook.fromJson(b.toJson())
        assertEquals(b.bests.toSet(), back.bests.toSet())
        assertTrue(b.toJson().startsWith("{\"format\":1,"))

        val messy = """{"format":7,"extra":true,"bests":[
            {"route":"ride:a","startM":1000.0,"endM":2000.0,"timeMs":123,"splitsMs":[1,2],"atMs":5,"neu":1},
            {"route":"","startM":1,"endM":2,"timeMs":1},
            {"route":"ride:a","startM":3000,"endM":2000,"timeMs":1},
            "quatsch"]}"""
        val m = ClimbBestBook.fromJson(messy)
        assertEquals(1, m.bests.size)
        assertEquals(emptyList(), m.bests[0].splitsMs)
        assertTrue(ClimbBestBook.fromJson("kein json").isEmpty())
        assertTrue(ClimbBestBook.fromJson(null).isEmpty())
    }

    @Test
    fun `Zusammenfuehren behaelt je Anstieg die schnellere Zeit`() {
        val mine = ClimbBestBook().record(best(200_000))
        val other = ClimbBestBook().record(best(180_000, start = 1_020.0)).record(best(99_000, start = 8_000.0))
        val merged = mine.merge(other)
        assertEquals(2, merged.bests.size)
        assertEquals(180_000L, merged.bestFor("ride:a", climb)!!.timeMs)
    }

    @Test
    fun `Zwischenzeiten`() {
        val b = best(100_000)
        assertEquals(50_000.0, b.splitAt(0.5), 1e-6)
        assertEquals(55_000.0, b.splitAt(0.55), 1e-6)
        assertEquals(100_000.0, b.splitAt(2.0), 1e-6)
        assertEquals(25_000.0, b.copy(splitsMs = emptyList()).splitAt(0.25), 1e-6)
    }
}
