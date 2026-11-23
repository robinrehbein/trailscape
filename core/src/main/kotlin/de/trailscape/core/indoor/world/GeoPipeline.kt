package de.trailscape.core.indoor.world

import java.io.File
import java.io.IOException
import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

/**
 * # Die Huelle um den rohen Geodaten-Abruf (docs/indoor.md 8.2–8.4)
 *
 * Reihenfolge je Anfrage:
 *  1. **Positivliste** ([isAllowedGeoUrl]): Verstoss = [IllegalArgumentException],
 *     es wird nichts abgerufen (Programmfehler).
 *  2. **Plattencache** `<cacheDir>/indoor/geo/<art>/<2 Zeichen>/<sha256(url)>`
 *     plus `.meta` (URL, Zeit, Content-Type, Laenge). Frisch → sofort
 *     zurueck (`fromCache = true`). Haltbarkeit: Hoehen 365 d · TileJSON 24 h ·
 *     Vektor 30 d · Luftbild 180 d.
 *  3. **Drosselung**: hoechstens [MAX_PARALLEL] Anfragen insgesamt und
 *     [MAX_PARALLEL_PER_HOST] je Host; 429/503 → 2 s · 4 s · 8 s warten und
 *     wiederholen (ein `Retry-After` kommt ueber [GeoResponse] nicht an),
 *     danach die Fehlerantwort zurueckgeben.
 *  4. Gespeichert wird nur Status 200 mit **plausiblem** Inhalt (PNG-Signatur
 *     bei Hoehen, PNG/JPEG bei Luftbildern — eine WMS-Fehlermeldung mit
 *     Status 200 nicht —, nicht leere Protobuf-Daten bei Vektorkacheln,
 *     ein JSON-Objekt mit `tiles` beim TileJSON); atomar ueber Temp-Datei
 *     und Umbenennen.
 *  5. **Ohne Netz gilt auch Abgelaufenes**: Wirft der rohe Abruf eine
 *     [IOException], wird ein vorhandener abgelaufener Eintrag geliefert,
 *     sonst die Ausnahme weitergeworfen.
 *
 * 404 bei Vektor- oder Hoehenkacheln (Meer) ist eine normale Antwort und
 * wird unveraendert weitergegeben (nicht gespeichert).
 */
internal fun geoPipeline(
    network: GeoFetcher,
    cacheDir: File,
    aerial: List<AerialSource>,
    clock: () -> Long = System::currentTimeMillis,
    sleeper: (Long) -> Unit = Thread::sleep,
): GeoFetcher = GeoPipeline(network, cacheDir, aerial, clock, sleeper)

internal const val MAX_PARALLEL: Int = 4
internal const val MAX_PARALLEL_PER_HOST: Int = 2

/** Wartezeiten vor den Wiederholungen nach 429/503. */
internal val RETRY_DELAYS_MS: List<Long> = listOf(2_000L, 4_000L, 8_000L)

/** Cache-Arten mit Verzeichnisname und Haltbarkeit. */
internal enum class GeoCacheKind(val dir: String, val maxAgeMs: Long) {
    ELEVATION("elevation", 365L * DAY_MS),
    TILEJSON("tilejson", 24L * HOUR_MS),
    VECTOR("vector", 30L * DAY_MS),
    AERIAL("aerial", 180L * DAY_MS),
    ;

    companion object {
        fun of(request: GeoRequest): GeoCacheKind = when (request.kind) {
            GeoSourceKind.ELEVATION -> ELEVATION
            GeoSourceKind.AERIAL -> AERIAL
            GeoSourceKind.VECTOR -> {
                val path = runCatching { URI(request.url).path }.getOrNull().orEmpty()
                if (path.endsWith(".pbf") || path.endsWith(".mvt")) VECTOR else TILEJSON
            }
        }
    }
}

private const val HOUR_MS = 3_600_000L
private const val DAY_MS = 24L * HOUR_MS

private class GeoPipeline(
    private val network: GeoFetcher,
    private val cacheDir: File,
    private val aerial: List<AerialSource>,
    private val clock: () -> Long,
    private val sleeper: (Long) -> Unit,
) : GeoFetcher {
    private val global = Semaphore(MAX_PARALLEL, true)
    private val perHost = ConcurrentHashMap<String, Semaphore>()

    override fun fetch(request: GeoRequest): GeoResponse {
        require(isAllowedGeoUrl(request.url, aerial)) { "Geodaten-URL nicht auf der Positivliste: ${request.url}" }
        val kind = GeoCacheKind.of(request)
        val entry = CacheEntry(cacheDir, kind, request.url)
        val cached = entry.read()
        if (cached != null && clock() - cached.timeMs <= kind.maxAgeMs) {
            return GeoResponse(200, cached.contentType, cached.bytes, fromCache = true)
        }
        val resp = try {
            throttled(request)
        } catch (e: IOException) {
            if (cached != null) return GeoResponse(200, cached.contentType, cached.bytes, fromCache = true)
            throw e
        }
        if (resp.status == 200 && isPlausible(kind, resp.bytes)) {
            // Ein voller Datentraeger darf den Abruf nicht scheitern lassen: dann eben ohne Cache.
            runCatching { entry.write(resp.bytes, resp.contentType, clock()) }
        }
        return resp
    }

    private fun throttled(request: GeoRequest): GeoResponse {
        val host = URI(request.url).host.lowercase()
        val hostSem = perHost.computeIfAbsent(host) { Semaphore(MAX_PARALLEL_PER_HOST, true) }
        var attempt = 0
        while (true) {
            // Reihenfolge fest (erst Host, dann gesamt), damit niemand mit einem
            // Gesamtplatz auf einen Hostplatz wartet.
            hostSem.acquireUninterruptibly()
            val resp = try {
                global.acquireUninterruptibly()
                try {
                    network.fetch(request)
                } finally {
                    global.release()
                }
            } finally {
                hostSem.release()
            }
            if ((resp.status == 429 || resp.status == 503) && attempt < RETRY_DELAYS_MS.size) {
                // gewartet wird ohne belegten Platz
                sleeper(RETRY_DELAYS_MS[attempt])
                attempt++
                continue
            }
            return resp
        }
    }
}

/** Ist der Inhalt einer 200-Antwort speicherwuerdig? */
internal fun isPlausible(kind: GeoCacheKind, bytes: ByteArray): Boolean = when (kind) {
    GeoCacheKind.ELEVATION -> isPng(bytes)
    GeoCacheKind.AERIAL -> isPng(bytes) || isJpeg(bytes)
    GeoCacheKind.VECTOR -> bytes.isNotEmpty() && if (isGzip(bytes)) bytes.size > 18 else looksLikeMvt(bytes)
    GeoCacheKind.TILEJSON -> parseTileJson(String(bytes, Charsets.UTF_8)) != null
}

/** Eine Vektorkachel beginnt mit Feld 3 (Ebene, Laengenfeld) → Byte `0x1A`. */
private fun looksLikeMvt(bytes: ByteArray): Boolean = bytes[0] == 0x1A.toByte()

private class CachedData(val bytes: ByteArray, val contentType: String?, val timeMs: Long)

/** Ein Eintrag im Plattencache. */
private class CacheEntry(cacheDir: File, kind: GeoCacheKind, private val url: String) {
    private val dataFile: File
    private val metaFile: File

    init {
        val hash = sha256Hex(url)
        val dir = File(cacheDir, "indoor/geo/${kind.dir}/${hash.substring(0, 2)}")
        dataFile = File(dir, hash)
        metaFile = File(dir, "$hash.meta")
    }

    fun read(): CachedData? {
        if (!dataFile.isFile || !metaFile.isFile) return null
        return try {
            val props = Properties()
            metaFile.inputStream().use { props.load(it) }
            if (props.getProperty("url") != url) return null
            val time = props.getProperty("time")?.toLongOrNull() ?: return null
            val length = props.getProperty("length")?.toLongOrNull() ?: return null
            val bytes = dataFile.readBytes()
            if (bytes.size.toLong() != length) return null
            CachedData(bytes, props.getProperty("contentType")?.takeIf { it.isNotEmpty() }, time)
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun write(bytes: ByteArray, contentType: String?, timeMs: Long) {
        val dir = dataFile.parentFile
        dir.mkdirs()
        val dataTmp = File.createTempFile(dataFile.name, ".tmp", dir)
        val metaTmp = File.createTempFile(metaFile.name, ".tmp", dir)
        try {
            dataTmp.writeBytes(bytes)
            val props = Properties()
            props.setProperty("url", url)
            props.setProperty("time", timeMs.toString())
            props.setProperty("contentType", contentType.orEmpty())
            props.setProperty("length", bytes.size.toString())
            metaTmp.outputStream().use { props.store(it, null) }
            move(dataTmp, dataFile)
            move(metaTmp, metaFile)
        } finally {
            dataTmp.delete()
            metaTmp.delete()
        }
    }

    private fun move(from: File, to: File) {
        try {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

internal fun sha256Hex(s: String): String {
    val hex = "0123456789abcdef"
    val digest = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
    val sb = StringBuilder(digest.size * 2)
    for (b in digest) sb.append(hex[(b.toInt() ushr 4) and 0xF]).append(hex[b.toInt() and 0xF])
    return sb.toString()
}
