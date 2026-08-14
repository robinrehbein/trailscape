package de.trailscape.app.record

import de.trailscape.core.TrackPoint
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests fuer das Aufzeichnungsjournal — die Absturzsicherung der Aufnahme.
 *
 * Laufen als normale JVM-Unit-Tests, weil [RecordingJournal] bewusst keinen
 * einzigen Android-Import hat (nur `java.io` + kotlinx-serialization + `:core`).
 */
class RecordingJournalTest {

    private lateinit var dir: File

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("trailscape-journal").toFile()
    }

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun journal() = RecordingJournal(dir)

    private fun activeFile() = File(dir, RecordingJournal.ACTIVE_FILE_NAME)

    private fun point(lat: Double, timeMs: Long, ele: Double? = 100.0) =
        TrackPoint(lat = lat, lon = 13.0, ele = ele, time = timeMs)

    // --- Zeilenformat ---

    @Test
    fun `Journal beginnt mit einer Kopfzeile und haengt Punkte als JSON-Zeilen an`() {
        val j = journal()
        j.begin("id-1", 1_000L)
        j.appendPoint(point(52.0, 1_005L))
        j.close()

        val lines = activeFile().readLines()
        assertEquals(2, lines.size)
        assertEquals(
            """{"v":1,"type":"header","id":"id-1","startedAt":1000}""",
            lines[0],
        )
        assertEquals(
            """{"type":"point","point":{"lat":52.0,"lon":13.0,"ele":100.0,"time":1005}}""",
            lines[1],
        )
    }

    @Test
    fun `Pause und Fortsetzung stehen als eigene Zeilen im Journal`() {
        val j = journal()
        j.begin("id-1", 1_000L)
        j.appendPause(2_000L)
        j.appendResume(5_000L)
        j.close()

        val lines = activeFile().readLines()
        assertEquals("""{"type":"pause","at":2000}""", lines[1])
        assertEquals("""{"type":"resume","at":5000}""", lines[2])
    }

    @Test
    fun `jeder Punkt liegt sofort auf dem Datentraeger`() {
        val j = journal()
        j.begin("id-1", 1_000L)
        j.appendPoint(point(52.0, 1_005L))

        // Ohne close(): die Datei muss den Punkt bereits enthalten.
        assertEquals(2, activeFile().readLines().size)

        j.appendPoint(point(52.1, 1_010L))
        assertEquals(3, activeFile().readLines().size)
        j.close()
    }

    // --- Lesen ---

    @Test
    fun `read liefert Kopfdaten und alle Punkte`() {
        val j = journal()
        j.begin("id-42", 1_000L)
        j.appendPoint(point(52.0, 1_005L))
        j.appendPoint(point(52.1, 1_010L))
        j.close()

        val snapshot = assertNotNull(journal().read())
        assertEquals("id-42", snapshot.id)
        assertEquals(1_000L, snapshot.startedAtMs)
        assertEquals(2, snapshot.points.size)
        assertEquals(52.1, snapshot.points[1].lat)
        assertEquals(1_010L, snapshot.points[1].time)
        assertEquals(0L, snapshot.pausedMs)
        assertNull(snapshot.pausedSinceMs)
        assertFalse(snapshot.hadUnreadableLines)
    }

    @Test
    fun `read summiert abgeschlossene Pausen`() {
        val j = journal()
        j.begin("id-1", 1_000L)
        j.appendPause(2_000L)
        j.appendResume(5_000L)
        j.appendPause(9_000L)
        j.appendResume(11_000L)
        j.close()

        val snapshot = assertNotNull(journal().read())
        assertEquals(5_000L, snapshot.pausedMs)
        assertNull(snapshot.pausedSinceMs)
    }

    @Test
    fun `eine offene Pause bleibt als pausedSince erhalten`() {
        val j = journal()
        j.begin("id-1", 1_000L)
        j.appendPause(2_000L)
        j.appendResume(5_000L)
        j.appendPause(9_000L)
        j.close()

        val snapshot = assertNotNull(journal().read())
        assertEquals(3_000L, snapshot.pausedMs)
        assertEquals(9_000L, snapshot.pausedSinceMs)
    }

    @Test
    fun `ohne Journal liefert read null`() {
        assertNull(journal().read())
        assertFalse(journal().exists())
    }

    // --- Absturzfestigkeit ---

    @Test
    fun `abgeschnittene letzte Zeile kostet nur diese Zeile`() {
        val j = journal()
        j.begin("id-1", 1_000L)
        j.appendPoint(point(52.0, 1_005L))
        j.appendPoint(point(52.1, 1_010L))
        j.close()

        // Absturz mitten im Schreiben der dritten Punktzeile nachstellen.
        activeFile().appendText("""{"type":"point","point":{"lat":52.2,"l""")

        val snapshot = assertNotNull(journal().read())
        assertEquals(2, snapshot.points.size)
        assertTrue(snapshot.hadUnreadableLines)
        assertEquals("id-1", snapshot.id)
    }

    @Test
    fun `ohne Kopfzeile wird die Startzeit aus dem ersten Punkt abgeleitet`() {
        dir.mkdirs()
        activeFile().writeText(
            """{"type":"point","point":{"lat":52.0,"lon":13.0,"time":7000}}""" + "\n",
        )

        val snapshot = assertNotNull(journal().read())
        assertEquals(7_000L, snapshot.startedAtMs)
        assertEquals(1, snapshot.points.size)
        assertTrue(snapshot.id.startsWith("7000-"))
    }

    @Test
    fun `Weiterschreiben nach Neustart haengt an ohne neue Kopfzeile`() {
        val first = journal()
        first.begin("id-1", 1_000L)
        first.appendPoint(point(52.0, 1_005L))
        first.close()

        val second = journal()
        second.reopenForAppend()
        second.appendPoint(point(52.1, 1_010L))
        second.close()

        val lines = activeFile().readLines()
        assertEquals(3, lines.size)
        assertEquals(1, lines.count { it.contains(""""type":"header"""") })
        assertEquals(2, assertNotNull(journal().read()).points.size)
    }

    @Test
    fun `discard entfernt Journal und Lebenszeichen`() {
        val j = journal()
        j.begin("id-1", 1_000L)
        j.appendPoint(point(52.0, 1_005L))
        j.touchHeartbeat(1_005L)

        j.discard()

        assertFalse(activeFile().exists())
        assertFalse(File(dir, RecordingJournal.LOCK_FILE_NAME).exists())
        assertNull(journal().read())
    }

    // --- Lebenszeichen ---

    @Test
    fun `Lebenszeichen liefert sein Alter`() {
        val j = journal()
        assertNull(j.heartbeatAgeMs(10_000L))

        j.touchHeartbeat(10_000L)
        assertEquals(2_000L, j.heartbeatAgeMs(12_000L))
    }

    // --- Inbesitznahme durch die Wiederherstellung ---

    @Test
    fun `claimStale benennt das Journal um und macht es einmalig`() {
        val j = journal()
        j.begin("id-1", 1_000L)
        j.appendPoint(point(52.0, 1_005L))
        j.close()

        val claimed = assertNotNull(RecordingJournal.claimStale(dir, 55_555L))
        assertEquals("recovering-55555.jsonl", claimed.name)
        assertFalse(activeFile().exists())

        // Ein zweiter Anlauf findet nichts mehr — Schutz gegen zwei
        // gleichzeitige Wiederherstellungen.
        assertNull(RecordingJournal.claimStale(dir, 55_556L))

        val snapshot = assertNotNull(RecordingJournal.parse(claimed))
        assertEquals("id-1", snapshot.id)
        assertEquals(1, snapshot.points.size)
    }

    @Test
    fun `pendingClaimed findet stehen gebliebene Wiederherstellungen`() {
        val j = journal()
        j.begin("id-1", 1_000L)
        j.appendPoint(point(52.0, 1_005L))
        j.close()
        RecordingJournal.claimStale(dir, 55_555L)

        val pending = RecordingJournal.pendingClaimed(dir)
        assertEquals(1, pending.size)
        assertEquals("recovering-55555.jsonl", pending[0].name)
    }

    /**
     * Bewusst ohne feste Wartezeiten: Eine fruehere Fassung verliess sich auf
     * `Thread.sleep(20)` in der Hoffnung, der andere Thread habe die Sperre bis
     * dahin uebernommen — unter Last stimmte das in etwa jedem dritten Lauf
     * nicht, und der Test fiel grundlos um. Gewartet wird deshalb auf die
     * Bedingungen selbst: eine Sperre, die nachweislich gehalten wird, und ein
     * zweiter Thread, der nachweislich daran haengt.
     */
    @Test
    fun `withClaimLock serialisiert konkurrierende Zugriffe`() {
        val order = mutableListOf<String>()
        val haeltSperre = CountDownLatch(1)
        val darfFreigeben = CountDownLatch(1)

        val wiederherstellung = Thread {
            RecordingJournal.withClaimLock {
                haeltSperre.countDown()
                darfFreigeben.await()
                synchronized(order) { order.add("wiederherstellung") }
            }
        }
        wiederherstellung.start()
        assertTrue(
            haeltSperre.await(5, TimeUnit.SECONDS),
            "Der erste Thread hat die Sperre nicht uebernommen.",
        )

        val dienst = Thread {
            RecordingJournal.withClaimLock {
                synchronized(order) { order.add("dienst") }
            }
        }
        dienst.start()

        // Erst weitermachen, wenn der zweite Thread wirklich an der Sperre
        // wartet — sonst pruefte der Test am Ende gar keine Serialisierung,
        // sondern nur die Reihenfolge zweier ohnehin getrennter Abschnitte.
        val frist = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (dienst.state !in wartend && System.nanoTime() < frist) {
            Thread.onSpinWait()
        }
        assertTrue(
            dienst.state in wartend,
            "Der zweite Zugriff haette an der Sperre warten muessen, war aber ${dienst.state}.",
        )

        darfFreigeben.countDown()
        wiederherstellung.join()
        dienst.join()

        assertEquals(listOf("wiederherstellung", "dienst"), order)
    }

    @Test
    fun `claimStale kann nicht zwischen read und reopenForAppend dazwischenfunken`() {
        val j = journal()
        j.begin("id-1", 1_000L)
        j.appendPoint(point(52.0, 1_005L))
        j.close()

        // Stellt die Wiederherstellung nach: ein anderer Thread will dasselbe
        // Journal beanspruchen, waehrend der Dienst es fortsetzt.
        var claimed: File? = null
        val recovery = Thread {
            RecordingJournal.withClaimLock {
                claimed = RecordingJournal.claimStale(dir, 55_555L)
            }
        }

        val service = journal()
        val snapshot = RecordingJournal.withClaimLock {
            val read = service.read()
            recovery.start()
            // Der andere Thread muss draussen bleiben, bis dieser Abschnitt
            // fertig ist — vorher darf das Journal nicht umbenannt sein.
            Thread.sleep(100)
            assertNull(claimed)
            assertTrue(activeFile().isFile)
            service.reopenForAppend()
            service.touchHeartbeat(2_000L)
            read
        }
        recovery.join()
        service.close()

        assertEquals("id-1", assertNotNull(snapshot).id)
        assertEquals(1, snapshot.points.size)
    }

    @Test
    fun `claimStale ignoriert ein leeres Journal`() {
        dir.mkdirs()
        activeFile().writeText("")

        assertNull(RecordingJournal.claimStale(dir, 1L))
    }

    // --- ID-Schema ---

    @Test
    fun `neue Tour-IDs folgen dem Schema aus map_screen dart`() {
        val id = RecordingJournal.newRideId(1_723_118_400_000L)

        assertTrue(id.startsWith("1723118400000-"), "war $id")
        val suffix = id.substringAfter('-')
        assertTrue(suffix.isNotEmpty())
        assertTrue(suffix.all { it.isDigit() || it in 'a'..'z' }, "war $suffix")
        // Zufallsanteil: zwei IDs sind praktisch nie gleich.
        assertTrue(
            (1..20).map { RecordingJournal.newRideId(1L) }.toSet().size > 1,
        )
    }
}

/**
 * Zustaende, in denen ein Thread an einer Sperre haengt. `synchronized` parkt
 * ihn als BLOCKED; kaeme die Sperre irgendwann aus `java.util.concurrent`,
 * waere es WAITING. Beides zaehlt hier als „wartet".
 */
private val wartend = setOf(Thread.State.BLOCKED, Thread.State.WAITING)
