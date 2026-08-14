package de.trailscape.app.routing

import de.trailscape.core.RemoteSegment
import de.trailscape.core.SegmentUpdateAction
import de.trailscape.core.applySegmentDelta
import de.trailscape.core.brouterSegmentBaseUrl
import de.trailscape.core.checkSegmentIntegrity
import de.trailscape.core.parseSegmentTile
import de.trailscape.core.planSegmentUpdate
import de.trailscape.core.segmentDeltaIsDummy
import de.trailscape.core.segmentDeltaUrl
import de.trailscape.core.segmentDownloadUrl
import de.trailscape.core.segmentMd5
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * Holt Routing-Kacheln vom Server — **fortsetzbar**, **abbrechbar** und, wo es
 * geht, als **Delta** statt als Vollabzug.
 *
 * ## Warum OkHttp und nicht `:core`s HttpClient
 * `de.trailscape.core.HttpClient` liefert den Rumpf als `String` (siehe
 * `data/OkHttpClientAdapter.kt`). Bei 119 MB je Kachel waere das ein
 * 119-MB-Zeichenkettenpuffer im Heap — auf einem Telefon der sichere
 * `OutOfMemoryError`, und binaere Daten in einer `String` sind ohnehin falsch.
 * Hier wird deshalb direkt gegen OkHttp gestroemt: fester 64-KiB-Puffer,
 * gleichbleibender Speicherbedarf, egal wie gross die Kachel ist.
 *
 * ## Warum eine Teildatei
 * Geschrieben wird immer nach `<Kachel>.rd5.part`; erst wenn alle Bytes da
 * sind, wandert die Datei per `rename` an ihren Platz. Der Grund ist hart:
 * Die Engine erkennt eine halbe `*.rd5` nicht als halb — sie liest den
 * Dateiindex aus den ersten 200 Bytes und laeuft dann in Positionen, die es
 * nicht gibt. Eine abgebrochene Datei unter dem echten Namen wuerde also nicht
 * „fehlen", sondern **falsch** sein. Die Teildatei loest zugleich das
 * Fortsetzen: Sie ist der Fortschritt, der einen Abbruch ueberlebt.
 *
 * ## Warum das Fortsetzen sicher ist
 * Der Server liefert `Accept-Ranges: bytes` und einen `ETag`. Damit aus zwei
 * Staenden nie eine Datei wird, die vorn von gestern und hinten von heute ist,
 * sichern **zwei** Dinge das Fortsetzen ab:
 *
 *  1. Beim Anlegen der Teildatei wird gemerkt, zu welchem Serverstand sie
 *     gehoert ([SegmentMetadata.partValidator]). Meldet der `HEAD` beim
 *     naechsten Versuch ein anderes Kennzeichen, fliegt die Teildatei, bevor
 *     ueberhaupt eine Anfrage hinausgeht. Ohne diesen Vermerk waere die
 *     Pruefung wertlos — der Client kennt sonst nur den *aktuellen* Stand und
 *     wuerde ihn gegen sich selbst vergleichen.
 *  2. Die Fortsetzungsanfrage traegt `Range: bytes=<gehabt>-` **und**
 *     `If-Range: <ETag>`. Aendert sich die Kachel in den Sekunden zwischen
 *     `HEAD` und `GET`, verwirft der Server den `Range` von sich aus und
 *     antwortet mit `200` und der ganzen Datei; die Teildatei wird dann
 *     ueberschrieben statt fortgeschrieben.
 *
 * ## Zustaendigkeit
 * Diese Klasse kennt **kein Android**: kein `Context`, keine Notification,
 * kein WorkManager. Sie ist damit im JVM-Test pruefbar (siehe
 * `app/src/test/.../SegmentDownloaderTest.kt`, der einen kleinen HTTP-Server
 * aus dem JDK dagegen laufen laesst). Die Einbettung in den Hintergrundlauf
 * macht [SegmentDownloadWorker].
 */

/** Fehler beim Holen einer Kachel. [message] ist bereits eine deutsche Meldung. */
class SegmentDownloadException(message: String, cause: Throwable? = null) :
    IOException(message, cause)

/** Welcher Abschnitt der Arbeit gerade laeuft. */
enum class SegmentPhase {
    /** Vollabzug der Kachel. */
    DOWNLOAD,

    /** Das (kleine) Delta wird geholt. */
    DELTA_DOWNLOAD,

    /** Das Delta wird auf die vorhandene Kachel angewendet. */
    DELTA_APPLY,

    /** Das Ergebnis wird geprueft. */
    CHECK,
}

/**
 * Fortschritt einer Kachel.
 *
 * [done]/[total] sind **Bytes** — ausser in [SegmentPhase.DELTA_APPLY], wo die
 * Engine nur Prozent meldet (sie zaehlt Kachelbloecke, keine Bytes); dort ist
 * `total == 100`. [percent] gilt in jedem Fall.
 */
data class SegmentProgress(
    val fileName: String,
    val phase: SegmentPhase,
    val done: Long,
    val total: Long,
) {
    val percent: Int
        get() = if (total <= 0L) 0 else ((done * 100L) / total).coerceIn(0L, 100L).toInt()
}

/** Wie eine Kachel ausgegangen ist. */
sealed interface SegmentSyncResult {

    val fileName: String

    /** Nichts zu tun — der lokale Stand ist der aktuelle. */
    data class AlreadyCurrent(override val fileName: String) : SegmentSyncResult

    /**
     * Kachel ist jetzt aktuell.
     *
     * @param bytesTransferred tatsaechlich uebertragene Bytes (beim
     *   Fortsetzen also nur der Rest).
     * @param viaDelta ob der Delta-Weg genuegt hat.
     * @param deltaFallbackReason warum der Delta-Weg **nicht** genuegt hat —
     *   `null`, wenn er gar nicht in Frage kam oder geklappt hat. Nur fuer das
     *   Protokoll; die Oberflaeche interessiert es nicht.
     */
    data class Updated(
        override val fileName: String,
        val bytesTransferred: Long,
        val viaDelta: Boolean,
        val deltaFallbackReason: String? = null,
    ) : SegmentSyncResult

    /** Abgebrochen. Die Teildatei bleibt liegen, damit der naechste Lauf aufsetzt. */
    data class Cancelled(
        override val fileName: String,
        val bytesTransferred: Long,
    ) : SegmentSyncResult
}

/**
 * Der HTTP-Client fuer Kacheln.
 *
 * Bewusst ein eigener, nicht der aus [de.trailscape.app.data.OkHttpClientAdapter]:
 * Dessen 15 Sekunden Lesezeit sind fuer kurze JSON-Antworten gedacht. Hier
 * gilt: **kein** `callTimeout` (eine 119-MB-Kachel darf am Berg eine Stunde
 * brauchen), aber ein Lesezeitlimit je Block, damit eine tote Verbindung
 * auffaellt statt ewig zu haengen.
 */
internal fun segmentHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .writeTimeout(20, TimeUnit.SECONDS)
    .callTimeout(0, TimeUnit.MILLISECONDS)
    .retryOnConnectionFailure(true)
    .build()

/** Puffergroesse beim Stroemen. 64 KiB ist der uebliche Kompromiss. */
private const val COPY_BUFFER_BYTES = 64 * 1024

/** Erst nach so vielen neuen Bytes wird der Fortschritt gemeldet (rund 1 MB). */
private const val PROGRESS_STEP_BYTES = 1024L * 1024L

class SegmentDownloader(
    private val inventory: SegmentInventory,
    private val client: OkHttpClient = segmentHttpClient(),
    private val baseUrl: String = brouterSegmentBaseUrl,
) {

    /**
     * Fragt den Server nach dem aktuellen Stand einer Kachel — eine
     * `HEAD`-Anfrage, ein paar hundert Bytes.
     *
     * Das ist die billige Aktualitaetspruefung: Groesse, `ETag` und
     * `Last-Modified` reichen fuer die Entscheidung, ohne 119 MB anzufassen.
     */
    fun remoteSegment(fileName: String): RemoteSegment {
        requireSegmentName(fileName)
        val head = head(segmentDownloadUrl(fileName, baseUrl))
            ?: throw SegmentDownloadException(
                "Die Kachel $fileName gibt es auf dem Server nicht.",
            )
        return RemoteSegment(
            fileName = fileName,
            sizeBytes = head.sizeBytes,
            eTag = head.eTag,
            lastModified = head.lastModified,
        )
    }

    /**
     * Ist eine neuere Fassung da? Reine Auskunft, laedt nichts.
     */
    fun hasUpdate(fileName: String): Boolean =
        planSegmentUpdate(inventory.localSegment(fileName), remoteSegment(fileName)) !=
            SegmentUpdateAction.UP_TO_DATE

    /**
     * Bringt eine Kachel auf den aktuellen Stand — der eine Aufruf, den der
     * Hintergrundlauf braucht.
     *
     * Reihenfolge: `HEAD` → Entscheidung ([planSegmentUpdate]) → bei
     * Aktualisierung erst der Delta-Weg, und **nur wenn der scheitert**, der
     * Vollabzug. Das ist keine Feinheit: Das Delta ist rund hundertmal
     * kleiner (gemessen 0,25–1,6 MB gegen 119 MB), und die Kacheln werden
     * taeglich neu gebaut.
     *
     * @param isCancelled wird beim Stroemen laufend gefragt; bei `true` endet
     *   der Lauf mit [SegmentSyncResult.Cancelled] und die Teildatei bleibt
     *   liegen.
     * @param onProgress darf oft aufgerufen werden (rund einmal je MB).
     *
     * Blockiert den aufrufenden Thread und gehoert auf `Dispatchers.IO`.
     */
    fun sync(
        fileName: String,
        isCancelled: () -> Boolean = { false },
        onProgress: (SegmentProgress) -> Unit = {},
    ): SegmentSyncResult {
        requireSegmentName(fileName)
        inventory.dir.mkdirs()

        val remote = remoteSegment(fileName)
        val local = inventory.localSegment(fileName)

        return when (planSegmentUpdate(local, remote)) {
            SegmentUpdateAction.UP_TO_DATE -> {
                // Die Kopfzeilen koennen sich geaendert haben, ohne dass sich
                // der Inhalt aendert; dann bleibt der Vermerk aktuell.
                rememberMetadata(fileName, remote)
                SegmentSyncResult.AlreadyCurrent(fileName)
            }

            SegmentUpdateAction.DELTA -> {
                val viaDelta = runCatching { updateViaDelta(fileName, remote, isCancelled, onProgress) }
                val result = viaDelta.getOrNull()
                if (result != null) {
                    result
                } else {
                    val reason = viaDelta.exceptionOrNull()?.message ?: "kein passendes Delta"
                    downloadFull(fileName, remote, isCancelled, onProgress, reason)
                }
            }

            SegmentUpdateAction.FULL -> downloadFull(fileName, remote, isCancelled, onProgress, null)
        }
    }

    // -----------------------------------------------------------------------
    // Vollabzug
    // -----------------------------------------------------------------------

    /**
     * Laedt die ganze Kachel — falls moeglich, ab der Stelle, an der ein
     * frueherer Versuch stehen geblieben ist.
     */
    private fun downloadFull(
        fileName: String,
        remote: RemoteSegment,
        isCancelled: () -> Boolean,
        onProgress: (SegmentProgress) -> Unit,
        deltaFallbackReason: String?,
    ): SegmentSyncResult {
        val part = File(inventory.dir, fileName + SEGMENT_PART_SUFFIX)
        var have = if (part.isFile) part.length() else 0L
        // Eine Teildatei, die schon so gross ist wie die Zieldatei, kann nur
        // ein Rest von frueher sein — sonst waere sie umbenannt worden.
        if (remote.sizeBytes > 0 && have >= remote.sizeBytes) {
            part.delete()
            have = 0L
        }

        val validator = remote.eTag ?: remote.lastModified
        val stored = inventory.metadata.read(fileName)
        // Die Teildatei ist nur brauchbar, wenn sie zu **diesem** Serverstand
        // gehoert. Das steht im Vermerk, nicht in der Antwort von eben — sonst
        // waere der If-Range-Vergleich eine Selbstbestaetigung (siehe
        // SegmentMetadata.partValidator).
        if (have > 0L && (validator == null || stored.partValidator != validator)) {
            part.delete()
            have = 0L
        }
        if (have == 0L && validator != null) {
            inventory.metadata.write(fileName, stored.copy(partValidator = validator))
        }

        val builder = Request.Builder().url(segmentDownloadUrl(fileName, baseUrl))
        if (have > 0L && validator != null) {
            builder.header("Range", "bytes=$have-")
            // Zweite Sicherung: Aendert sich die Kachel zwischen unserer
            // HEAD-Anfrage und diesem GET, verwirft der Server den Range von
            // sich aus und liefert alles — statt zwei Staende zu verkleben.
            builder.header("If-Range", validator)
        }

        var transferred = 0L
        val cancelled = client.newCall(builder.build()).execute().use { response ->
            val append = when (response.code) {
                206 -> true
                200 -> false // Server hat den Range verworfen: von vorn.
                else -> throw SegmentDownloadException(
                    "Der Server hat die Kachel $fileName abgelehnt (HTTP ${response.code}).",
                )
            }
            val offset = if (append) have else 0L
            val body = response.body
                ?: throw SegmentDownloadException("Der Server hat $fileName ohne Inhalt geliefert.")
            val total = if (remote.sizeBytes > 0) {
                remote.sizeBytes
            } else {
                offset + body.contentLength().coerceAtLeast(0L)
            }
            onProgress(SegmentProgress(fileName, SegmentPhase.DOWNLOAD, offset, total))

            var written = offset
            var reported = offset
            var stopped = false
            body.byteStream().use { input ->
                FileOutputStream(part, append).use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        if (isCancelled()) {
                            stopped = true
                            break
                        }
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        transferred += read
                        if (written - reported >= PROGRESS_STEP_BYTES) {
                            reported = written
                            onProgress(SegmentProgress(fileName, SegmentPhase.DOWNLOAD, written, total))
                        }
                    }
                    output.flush()
                    // Auf die Platte zwingen, bevor umbenannt wird: Sonst kann
                    // ein Absturz eine Datei hinterlassen, die den richtigen
                    // Namen traegt, aber noch im Schreibpuffer haengt.
                    runCatching { output.fd.sync() }
                }
            }
            stopped
        }

        if (cancelled) {
            return SegmentSyncResult.Cancelled(fileName, transferred)
        }

        if (remote.sizeBytes > 0 && part.length() != remote.sizeBytes) {
            // Abgerissene Verbindung ohne Ausnahme (kommt vor). Die Teildatei
            // bleibt liegen — der naechste Lauf setzt darauf auf.
            throw SegmentDownloadException(
                "Die Kachel $fileName kam unvollständig an " +
                    "(${part.length()} von ${remote.sizeBytes} Bytes).",
            )
        }

        placeFile(part, File(inventory.dir, fileName))
        rememberMetadata(fileName, remote)
        return SegmentSyncResult.Updated(
            fileName = fileName,
            bytesTransferred = transferred,
            viaDelta = false,
            deltaFallbackReason = deltaFallbackReason,
        )
    }

    // -----------------------------------------------------------------------
    // Delta
    // -----------------------------------------------------------------------

    /**
     * Versucht die Aktualisierung ueber das inkrementelle Delta.
     *
     * Ablauf genau wie in `DownloadWorker.downloadSegment` des Submoduls: MD5
     * der lokalen Datei bilden, `diff/<Kachel>/<md5>.df5` holen, mit
     * `Rd5DiffTool.recoverFromDelta` anwenden, Ergebnis pruefen, einsetzen.
     *
     * @return `null`, wenn es kein Delta gibt oder es nicht passt — dann ist
     *   der Vollabzug dran. Ausnahmen bedeuten dasselbe, tragen aber eine
     *   Begruendung mit.
     */
    private fun updateViaDelta(
        fileName: String,
        remote: RemoteSegment,
        isCancelled: () -> Boolean,
        onProgress: (SegmentProgress) -> Unit,
    ): SegmentSyncResult? {
        val target = File(inventory.dir, fileName)
        if (!target.isFile) return null

        val md5 = segmentMd5(target)
        val deltaHead = head(segmentDeltaUrl(fileName, md5, baseUrl)) ?: return null

        if (segmentDeltaIsDummy(deltaHead.sizeBytes)) {
            // Der Server legt zu jedem neuen Stand eine leere Datei unter dem
            // MD5 **dieses** Stands ab. Wer sie bekommt, hat den aktuellen
            // Stand bereits — nur unsere Kopfzeilen waren veraltet.
            rememberMetadata(fileName, remote)
            return SegmentSyncResult.AlreadyCurrent(fileName)
        }

        val deltaFile = File(inventory.dir, fileName + SEGMENT_DELTA_TEMP_SUFFIX)
        val assembled = File(inventory.dir, fileName + SEGMENT_NEW_SUFFIX)
        try {
            val transferred = downloadSmall(
                url = segmentDeltaUrl(fileName, md5, baseUrl),
                target = deltaFile,
                expectedBytes = deltaHead.sizeBytes,
                isCancelled = isCancelled,
            ) { done, total ->
                onProgress(SegmentProgress(fileName, SegmentPhase.DELTA_DOWNLOAD, done, total))
            } ?: return SegmentSyncResult.Cancelled(fileName, 0L)

            val applied = applySegmentDelta(
                base = target,
                delta = deltaFile,
                out = assembled,
                onPercent = { percent ->
                    onProgress(
                        SegmentProgress(fileName, SegmentPhase.DELTA_APPLY, percent.toLong(), 100L),
                    )
                },
                isCancelled = isCancelled,
            )
            if (!applied) return SegmentSyncResult.Cancelled(fileName, transferred)

            onProgress(SegmentProgress(fileName, SegmentPhase.CHECK, 0L, 1L))
            verifyAssembled(fileName, assembled)

            placeFile(assembled, target)
            rememberMetadata(fileName, remote)
            return SegmentSyncResult.Updated(
                fileName = fileName,
                bytesTransferred = transferred,
                viaDelta = true,
            )
        } finally {
            deltaFile.delete()
            // Nach dem Umbenennen ist hier nichts mehr; im Fehlerfall schon.
            assembled.delete()
        }
    }

    /**
     * Prueft die aus einem Delta zusammengesetzte Kachel.
     *
     * Zwei Wege, der billigere zuerst:
     *
     * 1. **MD5 gegen den Server.** Zu jedem Kachelstand legt der Server eine
     *    leere `diff/<Kachel>/<md5-dieses-Stands>.df5` an
     *    (`Rd5DiffManager.calcDiffs`). Antwortet ein `HEAD` auf die MD5-Summe
     *    unseres Ergebnisses mit `200`, ist die Datei **byteweise** der
     *    aktuelle Serverstand — ein staerkerer Beleg geht nicht.
     * 2. **Eingebaute CRC-Summen.** Nur wenn Weg 1 nichts sagt (kein Netz,
     *    Server ohne Dummy-Datei): `PhysicalFile.checkFileIntegrity`, wie es
     *    auch der Upstream macht. Der Weg belegt, dass die Datei *in sich*
     *    stimmt — nicht, dass sie der aktuelle Stand ist.
     *
     * Die Reihenfolge folgt also der Beweiskraft, nicht dem Preis; beides ist
     * billig. Gemessen an E10_N50 (119 MB, Entwicklungsrechner): MD5 199 ms,
     * `checkFileIntegrity` 365 ms. Dass Letzteres wirklich anschlaegt, ist
     * geprueft — 4 kB mitten in der Datei ueberschrieben, Ergebnis
     * „checkum error" (siehe `SegmentDownloadManualTest`).
     *
     * @throws SegmentDownloadException wenn die Kachel nachweislich nicht
     *   stimmt. Der Aufrufer faellt dann auf den Vollabzug zurueck.
     */
    private fun verifyAssembled(fileName: String, assembled: File) {
        val md5 = segmentMd5(assembled)
        val dummy = runCatching { head(segmentDeltaUrl(fileName, md5, baseUrl)) }.getOrNull()
        if (dummy != null && segmentDeltaIsDummy(dummy.sizeBytes)) return

        checkSegmentIntegrity(assembled)?.let { throw SegmentDownloadException(it) }
    }

    // -----------------------------------------------------------------------
    // HTTP-Handwerk
    // -----------------------------------------------------------------------

    /** Antwort auf eine `HEAD`-Anfrage, auf das Noetige eingedampft. */
    private data class Head(val sizeBytes: Long, val eTag: String?, val lastModified: String?)

    /**
     * `HEAD` auf [url]. `null` bei `404` — das ist beim Delta der Normalfall
     * („so alt ist dein Stand nicht mehr im Angebot") und kein Fehler.
     */
    private fun head(url: String): Head? {
        val request = Request.Builder().url(url).head().build()
        client.newCall(request).execute().use { response ->
            if (response.code == 404) return null
            if (!response.isSuccessful) {
                throw SegmentDownloadException(
                    "Der Server antwortete auf die Nachfrage mit HTTP ${response.code}.",
                )
            }
            return response.toHead()
        }
    }

    private fun Response.toHead(): Head = Head(
        // Bewusst die Kopfzeile und nicht `body.contentLength()`: Bei einer
        // HEAD-Antwort hat OkHttp keinen Rumpf, aus dem es die Laenge nehmen
        // koennte.
        sizeBytes = header("Content-Length")?.toLongOrNull() ?: -1L,
        eTag = header("ETag"),
        lastModified = header("Last-Modified"),
    )

    /**
     * Laedt eine **kleine** Datei am Stueck (Deltas, ein bis zwei MB).
     *
     * Kein Fortsetzen: Bei dieser Groesse ist ein neuer Versuch billiger als
     * die Buchhaltung, und ein halbes Delta ist ohnehin wertlos.
     *
     * @return uebertragene Bytes, oder `null` bei Abbruch.
     */
    private fun downloadSmall(
        url: String,
        target: File,
        expectedBytes: Long,
        isCancelled: () -> Boolean,
        onProgress: (Long, Long) -> Unit,
    ): Long? {
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw SegmentDownloadException(
                    "Die Aktualisierung ließ sich nicht laden (HTTP ${response.code}).",
                )
            }
            val body = response.body
                ?: throw SegmentDownloadException("Die Aktualisierung kam ohne Inhalt an.")
            var written = 0L
            var stopped = false
            body.byteStream().use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        if (isCancelled()) {
                            stopped = true
                            break
                        }
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        onProgress(written, if (expectedBytes > 0) expectedBytes else written)
                    }
                    output.flush()
                }
            }
            if (stopped) {
                target.delete()
                return null
            }
            if (expectedBytes > 0 && written != expectedBytes) {
                throw SegmentDownloadException(
                    "Die Aktualisierung kam unvollständig an ($written von $expectedBytes Bytes).",
                )
            }
            return written
        }
    }

    // -----------------------------------------------------------------------
    // Kleinkram
    // -----------------------------------------------------------------------

    /**
     * Setzt [temp] an die Stelle von [target].
     *
     * Zuerst schlicht `rename`: Auf einem POSIX-Dateisystem — und darauf liegt
     * `filesDir` — ersetzt das eine vorhandene Datei in einem Zug, es gibt
     * also keinen Moment, in dem die Kachel fehlt. Erst wenn das scheitert,
     * wird die alte Datei aus dem Weg geraeumt und noch einmal versucht.
     */
    private fun placeFile(temp: File, target: File) {
        if (temp.renameTo(target)) return
        if (target.exists() && !target.delete()) {
            throw SegmentDownloadException(
                "Die alte Kachel ${target.name} ließ sich nicht ersetzen.",
            )
        }
        if (!temp.renameTo(target)) {
            throw SegmentDownloadException(
                "Die Kachel ${target.name} ließ sich nicht speichern.",
            )
        }
    }

    private fun rememberMetadata(fileName: String, remote: RemoteSegment) {
        inventory.metadata.write(
            fileName,
            SegmentMetadata(
                eTag = remote.eTag,
                lastModified = remote.lastModified,
                downloadedAtMs = System.currentTimeMillis(),
            ),
        )
    }

    /**
     * Laesst nur echte Kachelnamen durch.
     *
     * Der Name landet in einer URL und in einem Dateipfad; ein
     * durchgereichtes `../` waere ein Schreibzugriff ausserhalb des
     * Kachelverzeichnisses. [parseSegmentTile] laesst ausschliesslich das
     * Muster `E10_N50.rd5` zu und ist damit zugleich der Schutz davor.
     */
    private fun requireSegmentName(fileName: String) {
        if (parseSegmentTile(fileName) == null || !fileName.endsWith(".rd5")) {
            throw SegmentDownloadException("„$fileName“ ist kein Kachelname.")
        }
    }
}
