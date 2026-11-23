package de.trailscape.core.indoor.world

import java.io.File
import java.io.IOException
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import org.junit.jupiter.api.Timeout
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Harte Zeitgrenze je Test, damit ein Fehler nie die Gradle-Sperre haelt. */
@Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class GeoPipelineTest {
    private val dir: File = createTempDirectory("geo-cache").toFile()

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3)
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 9)
    private val mvt = byteArrayOf(0x1A, 0x02, 0x0A, 0x00)
    private val tileJson = """{"tiles":["https://tiles.openfreemap.org/planet/v1/{z}/{x}/{y}.pbf"],"maxzoom":14}""".toByteArray()

    private val elevUrl = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/13/4258/2740.png"
    private val vecUrl = "https://tiles.openfreemap.org/planet/v1/14/8516/5481.pbf"
    private val nw = aerialSources.first { it.id == "nw" }
    private val aerialUrl = TileMath.wmsGetMapUrl(nw, LatLonBox(51.05, 7.13, 51.051, 7.131), 256, 256)

    /** Attrappe: zaehlt Aufrufe, Antwort nach Vorgabe. */
    private class FakeNet(var handler: (GeoRequest) -> GeoResponse) : GeoFetcher {
        val calls = AtomicInteger()
        override fun fetch(request: GeoRequest): GeoResponse {
            calls.incrementAndGet()
            return handler(request)
        }
    }

    private var now = 1_000_000_000_000L
    private val sleeps = ArrayList<Long>()

    private fun pipeline(net: GeoFetcher) = geoPipeline(net, dir, aerialSources, clock = { now }, sleeper = { sleeps += it })

    @Test
    fun `Positivliste wirft und ruft nicht ab`() {
        val net = FakeNet { GeoResponse(200, null, png) }
        val p = pipeline(net)
        for (bad in listOf(
            "https://evil.example/x.png",
            "http://s3.amazonaws.com/elevation-tiles-prod/terrarium/1/1/1.png",
            "https://s3.amazonaws.com/anderer-bucket/1/1/1.png",
            "https://www.wms.nrw.de/geobasis/anderer_dienst?REQUEST=GetMap",
        )) {
            assertFailsWith<IllegalArgumentException> { p.fetch(GeoRequest(GeoSourceKind.ELEVATION, bad)) }
        }
        assertEquals(0, net.calls.get())
    }

    @Test
    fun `Cache-Treffer ohne Netz und Ablage auf der Platte`() {
        val net = FakeNet { GeoResponse(200, "image/png", png) }
        val p = pipeline(net)
        val first = p.fetch(GeoRequest(GeoSourceKind.ELEVATION, elevUrl))
        assertFalse(first.fromCache)
        net.handler = { throw AssertionError("kein Netz erwartet") }
        val second = p.fetch(GeoRequest(GeoSourceKind.ELEVATION, elevUrl))
        assertTrue(second.fromCache)
        assertEquals(200, second.status)
        assertEquals("image/png", second.contentType)
        assertContentEquals(png, second.bytes)
        val hash = sha256Hex(elevUrl)
        assertTrue(File(dir, "indoor/geo/elevation/${hash.substring(0, 2)}/$hash").isFile)
        assertTrue(File(dir, "indoor/geo/elevation/${hash.substring(0, 2)}/$hash.meta").isFile)
        assertTrue(dir.walkTopDown().none { it.name.endsWith(".tmp") })
        // eine neue Huelle (neuer Prozess) liest denselben Cache
        assertTrue(pipeline(net).fetch(GeoRequest(GeoSourceKind.ELEVATION, elevUrl)).fromCache)
    }

    @Test
    fun `Haltbarkeit je Art`() {
        val cases = listOf(
            Triple(GeoRequest(GeoSourceKind.ELEVATION, elevUrl), png, 365L * 24),
            Triple(GeoRequest(GeoSourceKind.VECTOR, GeoEndpoints.VECTOR_TILEJSON_URL), tileJson, 24L),
            Triple(GeoRequest(GeoSourceKind.VECTOR, vecUrl), mvt, 30L * 24),
            Triple(GeoRequest(GeoSourceKind.AERIAL, aerialUrl), jpeg, 180L * 24),
        )
        for ((req, body, hours) in cases) {
            val start = now
            val net = FakeNet { GeoResponse(200, null, body) }
            val p = pipeline(net)
            p.fetch(req)
            now = start + (hours - 1) * 3_600_000L
            assertTrue(p.fetch(req).fromCache, "${req.url} sollte noch frisch sein")
            assertEquals(1, net.calls.get())
            now = start + (hours + 1) * 3_600_000L
            assertFalse(p.fetch(req).fromCache, "${req.url} sollte abgelaufen sein")
            assertEquals(2, net.calls.get())
        }
    }

    @Test
    fun `ohne Netz gilt auch Abgelaufenes`() {
        val net = FakeNet { GeoResponse(200, null, mvt) }
        val p = pipeline(net)
        p.fetch(GeoRequest(GeoSourceKind.VECTOR, vecUrl))
        now += 400L * 24 * 3_600_000L
        net.handler = { throw IOException("kein Netz") }
        val r = p.fetch(GeoRequest(GeoSourceKind.VECTOR, vecUrl))
        assertTrue(r.fromCache)
        assertContentEquals(mvt, r.bytes)
        // nichts im Cache → Ausnahme geht durch
        assertFailsWith<IOException> { p.fetch(GeoRequest(GeoSourceKind.ELEVATION, elevUrl)) }
    }

    @Test
    fun `429 und 503 werden mit 2, 4, 8 Sekunden wiederholt`() {
        var n = 0
        val net = FakeNet { if (++n <= 3) GeoResponse(if (n == 2) 503 else 429, null, ByteArray(0)) else GeoResponse(200, null, png) }
        val r = pipeline(net).fetch(GeoRequest(GeoSourceKind.ELEVATION, elevUrl))
        assertEquals(200, r.status)
        assertEquals(listOf(2_000L, 4_000L, 8_000L), sleeps)
        assertEquals(4, net.calls.get())
        // dauerhaft 429 → nach drei Wiederholungen die Fehlerantwort
        sleeps.clear()
        val always = FakeNet { GeoResponse(429, null, ByteArray(0)) }
        val r2 = pipeline(always).fetch(GeoRequest(GeoSourceKind.ELEVATION, elevUrl.replace("2740", "2741")))
        assertEquals(429, r2.status)
        assertEquals(4, always.calls.get())
        assertEquals(listOf(2_000L, 4_000L, 8_000L), sleeps)
    }

    @Test
    fun `kaputte Antworten und 404 werden nicht gespeichert`() {
        val xml = resourceBytes("wms_exception.xml")
        val net = FakeNet { req ->
            when (req.kind) {
                GeoSourceKind.AERIAL -> GeoResponse(200, "text/xml", xml)
                GeoSourceKind.ELEVATION -> GeoResponse(404, null, ByteArray(0))
                GeoSourceKind.VECTOR -> GeoResponse(200, null, ByteArray(0))
            }
        }
        val p = pipeline(net)
        repeat(2) {
            assertEquals(200, p.fetch(GeoRequest(GeoSourceKind.AERIAL, aerialUrl)).status)
            assertEquals(404, p.fetch(GeoRequest(GeoSourceKind.ELEVATION, elevUrl)).status)
            assertEquals(200, p.fetch(GeoRequest(GeoSourceKind.VECTOR, vecUrl)).status)
        }
        assertEquals(6, net.calls.get())
        assertTrue(dir.walkTopDown().none { it.isFile })
        assertFalse(isPlausible(GeoCacheKind.ELEVATION, jpeg))
        assertFalse(isPlausible(GeoCacheKind.TILEJSON, "<html>".toByteArray()))
        assertTrue(isPlausible(GeoCacheKind.VECTOR, resourceBytes("vector_14_8516_5481.pbf")))
        assertTrue(isPlausible(GeoCacheKind.AERIAL, resourceBytes("aerial_nw_256.jpg")))
    }

    @Test
    fun `hoechstens 4 gleichzeitig, 2 je Host`() {
        val current = AtomicInteger()
        val maxSeen = AtomicInteger()
        val perHost = ConcurrentHashMap<String, AtomicInteger>()
        val perHostMax = ConcurrentHashMap<String, AtomicInteger>()
        val net = GeoFetcher { req ->
            val host = URI(req.url).host
            val c = current.incrementAndGet()
            maxSeen.accumulateAndGet(c) { a, b -> maxOf(a, b) }
            val h = perHost.computeIfAbsent(host) { AtomicInteger() }.incrementAndGet()
            perHostMax.computeIfAbsent(host) { AtomicInteger() }.accumulateAndGet(h) { a, b -> maxOf(a, b) }
            Thread.sleep(15)
            perHost.getValue(host).decrementAndGet()
            current.decrementAndGet()
            GeoResponse(404, null, ByteArray(0))
        }
        val p = pipeline(net)
        val urls = (0 until 12).flatMap { i ->
            listOf(
                GeoRequest(GeoSourceKind.ELEVATION, "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/13/$i/1.png"),
                GeoRequest(GeoSourceKind.VECTOR, "https://tiles.openfreemap.org/planet/v1/14/$i/1.pbf"),
                GeoRequest(GeoSourceKind.AERIAL, TileMath.wmsGetMapUrl(nw, LatLonBox(51.0 + i * 0.01, 7.0, 51.005 + i * 0.01, 7.005), 64, 64)),
            )
        }
        val start = CountDownLatch(1)
        val threads = urls.map { req -> Thread { start.await(); p.fetch(req) }.also { it.start() } }
        start.countDown()
        threads.forEach { it.join() }
        assertTrue(maxSeen.get() <= MAX_PARALLEL, "gleichzeitig ${maxSeen.get()}")
        assertTrue(maxSeen.get() >= 3, "es wurde gar nicht parallel abgerufen (${maxSeen.get()})")
        assertTrue(perHostMax.values.all { it.get() <= MAX_PARALLEL_PER_HOST }, perHostMax.toString())
        assertEquals(3, perHostMax.size)
    }
}
