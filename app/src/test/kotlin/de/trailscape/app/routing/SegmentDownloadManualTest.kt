package de.trailscape.app.routing

import de.trailscape.core.checkSegmentIntegrity
import de.trailscape.core.segmentDeltaUrl
import de.trailscape.core.segmentMd5
import java.io.File
import java.io.RandomAccessFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okhttp3.Request

/**
 * Der Beweis am **echten** Server: eine vollstaendige Kachel von brouter.de,
 * mitten im Vorgang abgebrochen, fortgesetzt und am Ende gegen die Pruefsumme
 * des Servers gehalten.
 *
 * Uebersprungen, solange die Umgebungsvariable
 * `TRAILSCAPE_SEGMENT_DOWNLOAD_DIR` nicht gesetzt ist — es sind rund 119 MB
 * ueber die Leitung, das hat in einem Lauf bei jedem Push nichts zu suchen
 * (dieselbe Haltung wie beim Handtest in `core/.../OfflineRoutingTest.kt`).
 * Das Verzeichnis gehoert **ausserhalb** des Repositories; die Kachel wird
 * nicht eingecheckt.
 *
 * So von Hand fahren:
 * ```
 * TRAILSCAPE_SEGMENT_DOWNLOAD_DIR=/tmp/trailscape-kacheln \
 *   ./gradlew :app:testDebugUnitTest --tests '*SegmentDownloadManualTest*' -i
 * ```
 *
 * Geprueft wird die ganze Kette:
 *  1. `HEAD` — Groesse, `ETag`, `Last-Modified` ohne einen Byte Nutzlast.
 *  2. Abbruch nach rund 30 MB; die Teildatei bleibt liegen, die Kachel selbst
 *     entsteht **nicht** (die Engine darf nie eine halbe `*.rd5` sehen).
 *  3. Fortsetzen: nur der Rest geht ueber die Leitung.
 *  4. Nachweis der Vollstaendigkeit gegen den Server: Zum aktuellen Stand
 *     jeder Kachel legt brouter.de eine **leere** Datei
 *     `diff/<Kachel>/<md5-des-Stands>.df5` ab (`Rd5DiffManager.calcDiffs`).
 *     Antwortet ein `HEAD` auf die MD5-Summe unserer Datei mit `200` und
 *     `Content-Length: 0`, ist sie byteweise das, was der Server hat.
 *  5. Die eingebauten CRC-Summen des Kachelformats.
 *  6. Der Delta-Weg gegen den echten Server: Mit absichtlich veraltetem
 *     Vermerk muss `sync` ueber das Delta-Verzeichnis feststellen, dass die
 *     Datei bereits aktuell ist — ohne noch einmal 119 MB zu laden.
 */
class SegmentDownloadManualTest {

    private val fileName = "E10_N50.rd5"

    @Test
    fun manualDownloadWithResume() {
        val dirPath = System.getenv(ENV_DIR)
        if (dirPath.isNullOrBlank()) {
            println("SegmentDownloadManualTest: uebersprungen — $ENV_DIR nicht gesetzt.")
            return
        }

        val dir = File(dirPath)
        check(dir.isDirectory || dir.mkdirs()) { "Verzeichnis $dir nicht anlegbar" }
        val inventory = SegmentInventory(dir, SegmentMetadataStore(MemoryKeyValueStore()))
        // Bei null anfangen: Der Test will das Laden, Abbrechen und Fortsetzen
        // zeigen, nicht die Abkuerzung „ist ja schon da".
        inventory.delete(fileName)
        val client = segmentHttpClient()
        val downloader = SegmentDownloader(inventory, client)

        // 1 ------------------------------------------------------------- HEAD
        val remote = downloader.remoteSegment(fileName)
        println(
            "HEAD $fileName: ${remote.sizeBytes} Bytes " +
                "(${"%.1f".format(remote.sizeBytes / 1024.0 / 1024.0)} MiB), " +
                "ETag=${remote.eTag}, Last-Modified=${remote.lastModified}",
        )
        assertTrue(remote.sizeBytes > 100_000_000, "unerwartet klein: ${remote.sizeBytes}")

        // 2 ---------------------------------------------------------- Abbruch
        var seen = 0L
        val startedFirst = System.currentTimeMillis()
        val first = downloader.sync(
            fileName = fileName,
            isCancelled = { seen >= CUTOFF_BYTES },
        ) { progress -> seen = progress.done }
        assertIs<SegmentSyncResult.Cancelled>(first)

        val partial = inventory.partialBytes(fileName)
        println(
            "Abbruch nach ${partial / 1024 / 1024} MiB " +
                "in ${System.currentTimeMillis() - startedFirst} ms",
        )
        assertTrue(partial in 1 until remote.sizeBytes, "Teildatei: $partial")
        assertTrue(
            !File(dir, fileName).exists(),
            "die halbe Kachel darf nicht unter ihrem echten Namen liegen",
        )

        // 3 ------------------------------------------------------- Fortsetzen
        val startedSecond = System.currentTimeMillis()
        val second = assertIs<SegmentSyncResult.Updated>(downloader.sync(fileName))
        val elapsed = System.currentTimeMillis() - startedSecond
        println(
            "Fortgesetzt: ${second.bytesTransferred} Bytes nachgeladen " +
                "(statt ${remote.sizeBytes}) in $elapsed ms",
        )
        assertEquals(remote.sizeBytes - partial, second.bytesTransferred)

        val file = File(dir, fileName)
        assertEquals(remote.sizeBytes, file.length())
        assertTrue(!File(dir, fileName + SEGMENT_PART_SUFFIX).exists(), "Teildatei nicht aufgeraeumt")

        // 4 ------------------------------------------- Pruefsumme des Servers
        val startedMd5 = System.currentTimeMillis()
        val md5 = segmentMd5(file)
        println("MD5 ${md5} in ${System.currentTimeMillis() - startedMd5} ms")

        val head = client.newCall(
            Request.Builder().url(segmentDeltaUrl(fileName, md5)).head().build(),
        ).execute()
        head.use {
            println(
                "HEAD diff/${fileName.removeSuffix(".rd5")}/$md5.df5 → " +
                    "${it.code}, Content-Length=${it.header("Content-Length")}",
            )
            assertEquals(
                200,
                it.code,
                "Der Server kennt diese MD5 nicht — die Datei ist nicht sein aktueller Stand.",
            )
            assertEquals("0", it.header("Content-Length"))
        }

        // 5 ------------------------------------------------------ Integritaet
        val startedCheck = System.currentTimeMillis()
        val defect = checkSegmentIntegrity(file)
        println("checkSegmentIntegrity in ${System.currentTimeMillis() - startedCheck} ms")
        assertNull(defect)

        // 5b — und hat die Pruefung ueberhaupt Zaehne? Eine Kopie wird mitten
        // in den Daten beschaedigt; wird das nicht bemerkt, taugt sie als
        // Rueckfallweg nach einem Delta nichts.
        val broken = File(dir, "$fileName.kaputt")
        try {
            file.copyTo(broken, overwrite = true)
            RandomAccessFile(broken, "rw").use { raf ->
                raf.seek(raf.length() / 2)
                raf.write(ByteArray(4096) { 0x5A })
            }
            val brokenMessage = checkSegmentIntegrity(broken)
            println("checkSegmentIntegrity(beschaedigt) → $brokenMessage")
            assertNotNull(brokenMessage, "die Beschaedigung wurde nicht bemerkt")
        } finally {
            broken.delete()
        }

        // 6 --------------------------------------------------------- Delta-Weg
        // Vermerk absichtlich verfaelschen: Damit gilt die Kachel als veraltet
        // und `sync` muss ueber das Delta-Verzeichnis gehen.
        inventory.metadata.write(
            fileName,
            SegmentMetadata(eTag = "\"veraltet\"", lastModified = remote.lastModified),
        )
        val startedDelta = System.currentTimeMillis()
        val third = downloader.sync(fileName)
        println("Delta-Weg: $third in ${System.currentTimeMillis() - startedDelta} ms")
        assertIs<SegmentSyncResult.AlreadyCurrent>(third)
        assertEquals(remote.eTag, inventory.metadata.read(fileName).eTag)
        assertEquals(remote.sizeBytes, file.length())
    }

    private companion object {
        const val ENV_DIR = "TRAILSCAPE_SEGMENT_DOWNLOAD_DIR"

        /** Nach so vielen Bytes bricht der erste Anlauf ab. */
        const val CUTOFF_BYTES = 30L * 1024 * 1024
    }
}
