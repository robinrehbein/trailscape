package de.trailscape.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Massenimport aus Archiven — gedacht fuer den Strava-Datenexport
 * („Deine Daten herunterladen"), der alle Aktivitaeten als
 * `activities/<id>.gpx.gz` bzw. `activities/<id>.fit.gz` in einem ZIP
 * ausliefert. Genauso funktionieren beliebige selbstgepackte Archive mit
 * `.gpx`/`.fit`-Dateien (Garmin-/Wahoo-Exporte).
 *
 * Der Kern ist bewusst plattformfrei: Ein- und Ausgabe sind [InputStream] bzw.
 * [ByteArray] und [Ride]s — kein Dateisystem, keine URIs, kein Android. Die
 * aufrufende UI oeffnet den Stream (Storage Access Framework) und speichert
 * das Ergebnis.
 *
 * ## Streaming
 * Gelesen wird mit [ZipInputStream], also Eintrag fuer Eintrag; nie liegt das
 * ganze Archiv im Speicher. Der **einzelne** Eintrag wird komplett in ein
 * [ByteArray] gelesen — Aktivitaetsdateien sind einige zehn bis wenige hundert
 * Kilobyte, und sowohl [parseGpx] (DOM) als auch [parseFit] brauchen wahlfreien
 * Zugriff.
 *
 * ## Grenzen
 *  * Keine verschachtelten Archive (ZIP im ZIP) — Strava liefert flach.
 *  * Kein TCX, kein FIT-Encoder, keine Strava-`activities.csv` (Titel, Notizen,
 *    Ausruestung bleiben also aussen vor).
 */

/** Dateiart eines gefundenen Archiv-Eintrags. */
enum class ArchiveEntryKind { GPX, FIT }

/** Ein importierbarer Eintrag im Archiv (Ergebnis von [scanArchive]). */
data class ArchiveEntry(
    /** Pfad im Archiv, z. B. `activities/1234567890.fit.gz`. */
    val path: String,
    val kind: ArchiveEntryKind,
    /** true bei `.gpx.gz`/`.fit.gz` — wird beim Import transparent entpackt. */
    val gzipped: Boolean,
) {
    /** Dateiname ohne Verzeichnis und ohne (auch doppelte) Endung — Fallback-Tourname. */
    val baseName: String get() = archiveBaseName(path)
}

/** Eine Datei, die sich nicht lesen liess — der Rest des Archivs laeuft weiter. */
data class BulkImportError(val path: String, val message: String)

/** Ergebnis von [importArchive]. */
data class BulkImportResult(
    /** Erfolgreich gelesene, noch nicht gespeicherte Touren — in Archiv-Reihenfolge. */
    val rides: List<Ride> = emptyList(),
    /** Pfade, die als Duplikat (Bestand oder frueher im selben Archiv) uebersprungen wurden. */
    val duplicates: List<String> = emptyList(),
    /** Pfade mit Lesefehler samt deutscher Meldung. */
    val errors: List<BulkImportError> = emptyList(),
) {
    val importedCount: Int get() = rides.size
    val duplicateCount: Int get() = duplicates.size
    val errorCount: Int get() = errors.size

    /** Anzahl aller betrachteten Aktivitaetsdateien. */
    val totalCount: Int get() = importedCount + duplicateCount + errorCount

    /** true, wenn keine einzige Datei gelesen werden konnte. */
    val isEmpty: Boolean get() = rides.isEmpty()
}

// ---------------------------------------------------------------------------
// Erkennung
// ---------------------------------------------------------------------------

/**
 * Ordnet einen Archiv-Pfad einer Dateiart zu; null fuer alles, was nicht
 * importierbar ist (Verzeichnisse, `activities.csv`, macOS-Metadaten, ...).
 */
internal fun classifyArchivePath(path: String): ArchiveEntry? {
    if (path.endsWith("/")) return null
    val fileName = path.substringAfterLast('/')
    // macOS-Beipack und versteckte Dateien ignorieren.
    if (fileName.isEmpty() || fileName.startsWith("._") || fileName.startsWith(".")) return null
    if (path.startsWith("__MACOSX/") || path.contains("/__MACOSX/")) return null

    val lower = fileName.lowercase()
    val gzipped = lower.endsWith(".gz")
    val stem = if (gzipped) lower.removeSuffix(".gz") else lower

    val kind = when {
        stem.endsWith(".gpx") -> ArchiveEntryKind.GPX
        stem.endsWith(".fit") -> ArchiveEntryKind.FIT
        else -> return null
    }
    return ArchiveEntry(path = path, kind = kind, gzipped = gzipped)
}

/** Dateiname ohne Verzeichnis und ohne Endung(en) — `activities/42.fit.gz` → `42`. */
internal fun archiveBaseName(path: String): String {
    var name = path.substringAfterLast('/')
    if (name.lowercase().endsWith(".gz")) name = name.dropLast(3)
    val dot = name.lastIndexOf('.')
    if (dot > 0) name = name.substring(0, dot)
    return name.ifEmpty { "Tour" }
}

// ---------------------------------------------------------------------------
// Scannen
// ---------------------------------------------------------------------------

/**
 * Listet alle importierbaren Aktivitaetsdateien eines ZIP-Archivs, inklusive
 * Unterordnern. Liest die Eintragsdaten nicht — nur die Namen.
 *
 * Der Stream wird **nicht** geschlossen; das uebernimmt der Aufrufer.
 */
fun scanArchive(input: InputStream): List<ArchiveEntry> {
    val found = mutableListOf<ArchiveEntry>()
    ZipInputStream(requireZip(input)).use { zip ->
        var entry = try {
            zip.nextEntry
        } catch (e: Exception) {
            throw FormatException("Das Archiv konnte nicht gelesen werden.")
        }
        while (entry != null) {
            if (!entry.isDirectory) classifyArchivePath(entry.name)?.let { found.add(it) }
            entry = try {
                zip.closeEntry()
                zip.nextEntry
            } catch (e: Exception) {
                null // abgeschnittenes Archiv: das bisher Gefundene behalten
            }
        }
    }
    return found
}

/** [scanArchive] fuer ein bereits vollstaendig geladenes Archiv. */
fun scanArchive(zipBytes: ByteArray): List<ArchiveEntry> =
    scanArchive(ByteArrayInputStream(zipBytes))

// ---------------------------------------------------------------------------
// Importieren
// ---------------------------------------------------------------------------

/**
 * Liest alle Aktivitaetsdateien eines ZIP-Archivs zu [Ride]s.
 *
 * Fehler einzelner Dateien brechen den Lauf **nicht** ab, sondern landen in
 * [BulkImportResult.errors] — bei einem Strava-Export mit tausend Aktivitaeten
 * darf eine kaputte Datei nicht den ganzen Import kosten.
 *
 * Duplikate werden gegen [existing] **und** gegen die im selben Lauf bereits
 * gelesenen Touren geprueft ([findDuplicateRide]).
 *
 * [total] ist die erwartete Anzahl Aktivitaetsdateien (z. B. aus einem
 * vorherigen [scanArchive]) und wird nur an [onProgress] durchgereicht; ohne
 * Angabe meldet der Fortschritt `total == done`, weil die Gesamtzahl beim
 * Streamen erst am Ende feststeht.
 *
 * Der Stream wird **nicht** geschlossen; das uebernimmt der Aufrufer.
 */
fun importArchive(
    input: InputStream,
    existing: List<RideInfo> = emptyList(),
    total: Int? = null,
    onProgress: ((done: Int, total: Int) -> Unit)? = null,
): BulkImportResult {
    val rides = mutableListOf<Ride>()
    val duplicates = mutableListOf<String>()
    val errors = mutableListOf<BulkImportError>()
    // Bestand + bereits im Archiv Gefundenes: erkennt auch Doppelte innerhalb des ZIPs.
    val seen = ArrayList<RideInfo>(existing)
    var done = 0
    // Fortlaufende IDs: rideFromGpx/rideFromFit wuerden sonst allen Touren
    // desselben Millisekunden-Ticks dieselbe ID geben — und die
    // Duplikatpruefung schlaegt schon bei ID-Gleichheit an.
    val idBase = System.currentTimeMillis()
    var idIndex = 0

    ZipInputStream(requireZip(input)).use { zip ->
        var zipEntry = try {
            zip.nextEntry
        } catch (e: Exception) {
            throw FormatException("Das Archiv konnte nicht gelesen werden.")
        }

        while (zipEntry != null) {
            val entry = if (zipEntry.isDirectory) null else classifyArchivePath(zipEntry.name)
            if (entry != null) {
                val path = entry.path
                try {
                    val bytes = zip.readAllBytesCompat()
                    val ride = rideFromArchiveEntry(entry, bytes, id = (idBase + idIndex).toString())
                    idIndex++
                    if (findDuplicateRide(seen, ride) != null) {
                        duplicates.add(path)
                    } else {
                        rides.add(ride)
                        seen.add(ride)
                    }
                } catch (e: FormatException) {
                    errors.add(BulkImportError(path, e.message ?: "Die Datei konnte nicht gelesen werden."))
                } catch (e: Exception) {
                    errors.add(BulkImportError(path, "Die Datei konnte nicht gelesen werden."))
                }
                done++
                onProgress?.invoke(done, total ?: done)
            }

            zipEntry = try {
                zip.closeEntry()
                zip.nextEntry
            } catch (e: Exception) {
                // Abgeschnittenes/kaputtes Archiv: das bisher Gelesene behalten.
                null
            }
        }
    }

    return BulkImportResult(rides = rides, duplicates = duplicates, errors = errors)
}

/**
 * [importArchive] fuer ein bereits vollstaendig geladenes Archiv. Ermittelt
 * die Gesamtzahl vorab selbst, damit [onProgress] einen echten Nenner hat.
 */
fun importArchive(
    zipBytes: ByteArray,
    existing: List<RideInfo> = emptyList(),
    onProgress: ((done: Int, total: Int) -> Unit)? = null,
): BulkImportResult {
    val total = scanArchive(zipBytes).size
    return importArchive(ByteArrayInputStream(zipBytes), existing, total, onProgress)
}

/**
 * Baut aus dem Rohinhalt eines Archiv-Eintrags eine Tour — GPX ueber
 * [rideFromGpx], FIT ueber [rideFromFit], `.gz` wird transparent entpackt.
 * Der Dateiname ohne Endung dient als Fallback-Name.
 */
fun rideFromArchiveEntry(entry: ArchiveEntry, rawBytes: ByteArray, id: String? = null): Ride {
    val bytes = if (entry.gzipped) gunzipIfNeeded(rawBytes) else rawBytes
    return when (entry.kind) {
        ArchiveEntryKind.GPX -> rideFromGpx(bytes.toString(Charsets.UTF_8), entry.baseName, id)
        ArchiveEntryKind.FIT -> rideFromFit(bytes, entry.baseName, id)
    }
}

// ---------------------------------------------------------------------------
// Hilfsmittel
// ---------------------------------------------------------------------------

/**
 * Verhindert, dass [ZipInputStream.close] den uebergebenen Stream mitschliesst
 * — der gehoert dem Aufrufer (der ihn z. B. noch fuer einen zweiten Durchlauf
 * braucht).
 */
/**
 * Prueft die ZIP-Signatur (`PK..`), ohne den Stream zu verbrauchen, und packt
 * ihn so ein, dass [ZipInputStream.close] ihn nicht mitschliesst.
 *
 * Ohne diese Vorpruefung liefert [ZipInputStream] fuer beliebige Nicht-ZIP-
 * Daten einfach „keine Eintraege" — der Nutzer bekaeme dann statt einer
 * Fehlermeldung ein leeres Ergebnis.
 */
private fun requireZip(input: InputStream): InputStream {
    val head = ByteArray(2)
    var read = 0
    while (read < head.size) {
        val n = input.read(head, read, head.size - read)
        if (n <= 0) break
        read += n
    }
    if (read < 2 || head[0] != 'P'.code.toByte() || head[1] != 'K'.code.toByte()) {
        throw FormatException("Die Datei ist kein gültiges ZIP-Archiv.")
    }
    val rewound = java.io.SequenceInputStream(ByteArrayInputStream(head), input)
    return NonClosingInputStream(rewound)
}

private class NonClosingInputStream(private val delegate: InputStream) : InputStream() {
    override fun read(): Int = delegate.read()
    override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, len)
    override fun available(): Int = delegate.available()
    override fun skip(n: Long): Long = delegate.skip(n)
    override fun close() { /* bewusst leer */ }
}

/**
 * `InputStream.readBytes()` fuer den aktuellen ZIP-Eintrag. Bewusst nicht
 * `readAllBytes()` (Java 9) — die App laeuft auf aelteren Android-Runtimes.
 */
private fun InputStream.readAllBytesCompat(): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    while (true) {
        val read = read(buffer)
        if (read <= 0) break
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}
