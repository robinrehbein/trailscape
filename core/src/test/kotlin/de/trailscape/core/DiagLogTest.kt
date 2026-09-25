package de.trailscape.core

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiagLogTest {

    private val tempDirs = mutableListOf<File>()

    private fun tempDir(): File = Files.createTempDirectory("diag").toFile().also { tempDirs += it }

    @AfterTest
    fun cleanup() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    /** Uhr, die bei jedem Aufruf um [stepMs] weiterlaeuft. */
    private class StepClock(start: Long = 1_700_000_000_000L, private val stepMs: Long = 0L) {
        var now = start
        fun tick(): Long = now.also { now += stepMs }
    }

    @Test
    fun `Zeile enthaelt nur Schluessel, Zahlen und Klassennamen`() {
        val line = formatDiagLine(
            timestampMs = 0L,
            event = DiagEvent.SYNC_PUSH_FAILED,
            code = 503,
            count = 12,
            errorClass = "java.io.IOException",
            atMs = 1_000L,
            repeats = 2,
        )
        assertEquals(
            "1970-01-01T00:00:00Z SYNC_PUSH_FAILED code=503 n=12 am=1970-01-01T00:00:01Z " +
                "err=java.io.IOException wdh=2",
            line,
        )
    }

    @Test
    fun `Meldung einer Ausnahme landet nie im Log`() {
        val clock = StepClock()
        val log = DiagLog(clock = clock::tick)
        val secret = "Tour \"Heimweg Musterstrasse\" /data/user/0/x https://mein.server"
        log.log(DiagEvent.SYNC_PUSH_FAILED, error = IllegalStateException(secret, RuntimeException(secret)))

        val lines = log.readAll()
        assertEquals(1, lines.size)
        assertFalse(lines.single().contains("Heimweg"))
        assertFalse(lines.single().contains("/data"))
        assertFalse(lines.single().contains("https"))
        assertTrue(lines.single().endsWith("err=java.lang.IllegalStateException<-java.lang.RuntimeException"))
    }

    @Test
    fun `Ursachen-Kette wird gekuerzt und Klassennamen bereinigt`() {
        val deep = Exception(Exception(Exception(Exception(Exception()))))
        assertEquals(DiagLog.MAX_CAUSE_DEPTH, describeErrorClass(deep).split("<-").size)
        // Anonyme Klassen tragen `$1` im Namen — das bleibt lesbar stehen.
        val anonymous = object : RuntimeException() {}
        val name = describeErrorClass(anonymous)
        assertTrue(name.matches(Regex("""[A-Za-z0-9_.$]+""")))
        assertTrue(name.length <= DiagLog.MAX_CLASS_NAME_CHARS)
    }

    @Test
    fun `sehr lange Zeilen werden gekuerzt`() {
        val line = formatDiagLine(0L, DiagEvent.CRASH, errorClass = "a".repeat(1000))
        assertEquals(DiagLog.MAX_LINE_CHARS, line.length)
    }

    @Test
    fun `Ringpuffer im Speicher haelt nur die juengsten Zeilen`() {
        val clock = StepClock(stepMs = 1_000L)
        val log = DiagLog(clock = clock::tick, memoryCapacity = 5, repeatWindowMs = 0L)
        repeat(12) { log.log(DiagEvent.GPS_RESUBSCRIBE, count = it.toLong()) }

        val lines = log.readAll()
        assertEquals(5, lines.size)
        assertTrue(lines.first().endsWith("n=7"))
        assertTrue(lines.last().endsWith("n=11"))
        assertEquals(lines.takeLast(3), log.snapshotForCrash(maxLines = 3))
    }

    @Test
    fun `Datei rolliert bei Ueberlauf und bleibt unter der Obergrenze`() {
        val dir = tempDir()
        val maxBytes = 1_000L
        val file = RollingDiagFile(dir, maxBytesPerFile = maxBytes)
        val clock = StepClock(stepMs = 1_000L)
        val log = DiagLog(clock = clock::tick, repeatWindowMs = 0L)
        log.attach(file)

        repeat(200) { log.log(DiagEvent.GPS_RESUBSCRIBE, count = it.toLong()) }

        assertTrue(file.current.length() <= maxBytes)
        assertTrue(file.previous.length() <= maxBytes)
        assertTrue(file.previous.isFile)
        val lines = log.readAll()
        // Aelteste sind weggefallen, die juengste ist da, Reihenfolge stimmt.
        assertTrue(lines.size < 200)
        assertTrue(lines.last().endsWith("n=199"))
        val counts = lines.map { it.substringAfter("n=").toInt() }
        assertEquals(counts.sorted(), counts)
        assertEquals((counts.first()..199).toList(), counts)
    }

    @Test
    fun `clear leert Datei und Speicher`() {
        val dir = tempDir()
        val file = RollingDiagFile(dir)
        val log = DiagLog()
        log.attach(file)
        log.log(DiagEvent.APP_START)
        assertTrue(file.current.isFile)

        log.clear()
        assertFalse(file.current.exists())
        assertTrue(log.readAll().isEmpty())
    }

    @Test
    fun `Wiederholungen im Zeitfenster werden gezaehlt statt geschrieben`() {
        val clock = StepClock(stepMs = 1_000L)
        val log = DiagLog(clock = clock::tick, repeatWindowMs = 10_000L)
        repeat(5) { log.log(DiagEvent.WEAR_SEND_FAILED, error = IllegalStateException()) }
        // Anderes Ereignis wird nicht gebremst.
        log.log(DiagEvent.GPS_START_OK)
        clock.now += 20_000L
        log.log(DiagEvent.WEAR_SEND_FAILED, error = IllegalStateException())

        val lines = log.readAll()
        assertEquals(3, lines.size)
        assertFalse(lines[0].contains("wdh="))
        assertTrue(lines[1].contains("GPS_START_OK"))
        assertTrue(lines[2].endsWith("wdh=4"))
    }

    @Test
    fun `nebenlaeufiges Schreiben verliert und zerreisst keine Zeilen`() {
        val dir = tempDir()
        val log = DiagLog(repeatWindowMs = 0L, memoryCapacity = 10_000)
        log.attach(RollingDiagFile(dir, maxBytesPerFile = 10L * 1024 * 1024))

        val threads = 8
        val perThread = 250
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        repeat(threads) { t ->
            pool.execute {
                start.await()
                repeat(perThread) { i -> log.log(DiagEvent.HEALTH_READ_FAILED, code = t, count = i.toLong()) }
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS))

        val lines = log.readAll()
        assertEquals(threads * perThread, lines.size)
        val pattern = Regex("""^\S+Z HEALTH_READ_FAILED code=\d+ n=\d+$""")
        assertTrue(lines.all { pattern.matches(it) })
        // Je Thread in der geschriebenen Reihenfolge.
        for (t in 0 until threads) {
            val mine = lines.filter { it.contains("code=$t ") }.map { it.substringAfter("n=").toInt() }
            assertEquals((0 until perThread).toList(), mine)
        }
    }

    @Test
    fun `Absturz-Schnappschuss wartet nicht auf eine gehaltene Sperre`() {
        val log = DiagLog()
        log.log(DiagEvent.APP_START)

        val holding = CountDownLatch(1)
        val release = CountDownLatch(1)
        // Ein anderer Thread blockiert mitten im Schreiben (Spiegel haengt).
        log.mirror = {
            holding.countDown()
            release.await(5, TimeUnit.SECONDS)
        }
        val writer = Thread { log.log(DiagEvent.GPS_START_OK) }.apply { start() }
        assertTrue(holding.await(5, TimeUnit.SECONDS))

        val startNs = System.nanoTime()
        assertNull(log.snapshotForCrash(timeoutMs = 50))
        assertFalse(log.tryLog(DiagEvent.CRASH, timeoutMs = 50))
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs) < 2_000)

        release.countDown()
        writer.join(5_000)
        log.mirror = null
        val snapshot = assertNotNull(log.snapshotForCrash())
        assertEquals(2, snapshot.size)
        assertTrue(log.tryLog(DiagEvent.CRASH, error = OutOfMemoryError()))
        assertTrue(log.readAll().last().contains("CRASH err=java.lang.OutOfMemoryError"))
    }

    @Test
    fun `Guard wirft im Debug-Build auf dem Main-Thread`() {
        val log = DiagLog()
        assertFailsWith<IllegalStateException> {
            MainThreadGuard.check(onMainThread = true, strict = true, event = DiagEvent.HEALTH_READ_ON_MAIN_THREAD, log = log)
        }
        assertTrue(log.readAll().isEmpty())
    }

    @Test
    fun `Guard protokolliert im Release-Build statt zu werfen`() {
        val log = DiagLog()
        MainThreadGuard.check(onMainThread = true, strict = false, event = DiagEvent.HEALTH_READ_ON_MAIN_THREAD, log = log)
        assertTrue(log.readAll().single().endsWith("HEALTH_READ_ON_MAIN_THREAD"))
    }

    @Test
    fun `Guard schweigt abseits des Main-Threads`() {
        val log = DiagLog()
        MainThreadGuard.check(onMainThread = false, strict = true, event = DiagEvent.HEALTH_READ_ON_MAIN_THREAD, log = log)
        assertTrue(log.readAll().isEmpty())
    }
}
