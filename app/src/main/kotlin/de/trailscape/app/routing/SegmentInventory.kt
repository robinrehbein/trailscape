package de.trailscape.app.routing

import de.trailscape.core.KeyValueStore
import de.trailscape.core.LocalSegment
import de.trailscape.core.SegmentTile
import de.trailscape.core.parseHttpDateMs
import de.trailscape.core.parseSegmentTile
import de.trailscape.core.segmentFileSuffix
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Der **Bestand** an Routing-Kacheln auf dem Geraet: was liegt da, wie gross
 * ist es, wie alt ist es — und das Loeschen.
 *
 * ## Wo die Dateien liegen
 * Flach in `filesDir/segments/`, festgelegt in
 * [de.trailscape.app.data.OfflineRoutingFiles.segmentDir]. Flach ist keine
 * Wahl, sondern Vorgabe der Engine (`NodesCache.fileForSegment` sucht die
 * Kachel direkt im uebergebenen Verzeichnis).
 *
 * ## Warum das Alter NICHT aus dem Dateidatum kommt
 * `File.lastModified()` sagt, wann **wir** die Datei geschrieben haben. Wer
 * eine drei Wochen alte Kachel heute herunterlaedt, saehe danach eine
 * „taufrische" Datei mit drei Wochen alten Wegen — und umgekehrt setzen
 * Kopiervorgaenge, Backups und Wiederherstellungen das Dateidatum neu, ohne
 * dass sich am Inhalt etwas aendert. Massgeblich ist deshalb allein die vom
 * Server gemeldete `Last-Modified`-Angabe, die der Downloader beim Holen
 * mitschreibt ([SegmentMetadata.lastModified]). Fehlt sie — etwa weil jemand
 * eine Kachel von Hand hineinkopiert hat —, ist das Alter schlicht
 * **unbekannt** ([InstalledSegment.ageDays] liefert `null`) statt geraten.
 *
 * ## Warum der [KeyValueStore] und keine eigene Datei
 * Die Metadaten sind je Kachel drei kurze Werte. Eine eigene JSON-Datei daneben
 * waere ein zweiter Speicher mit eigener Fehlerbehandlung, eigenem Aufraeumen
 * und der reizvollen Moeglichkeit, aus dem Tritt zu geraten, wenn jemand das
 * Verzeichnis leert. Der vorhandene [KeyValueStore]
 * (`data/PrefsStores.kt`) tut es genauso — mit dem einen Unterschied, dass er
 * die Schluessel nicht aufzaehlen kann. Genau deshalb ist **das Dateisystem
 * die Wahrheit** und der Speicher nur Beiwerk: [list] zaehlt Dateien auf und
 * schlaegt die Metadaten je Datei nach, nie umgekehrt. Ein verwaister Eintrag
 * ohne Datei stoert damit niemanden.
 */

/** Schluesselpraefix im [KeyValueStore], im `trailscape.*`-Namensraum wie alles andere. */
private const val METADATA_KEY_PREFIX = "trailscape.segment."

/** Endung der Teildatei eines laufenden Downloads. */
internal const val SEGMENT_PART_SUFFIX = ".part"

/** Endung des heruntergeladenen Deltas waehrend der Aktualisierung. */
internal const val SEGMENT_DELTA_TEMP_SUFFIX = ".df5"

/** Endung der aus einem Delta zusammengesetzten, noch nicht eingesetzten Kachel. */
internal const val SEGMENT_NEW_SUFFIX = ".new"

/**
 * Was beim Download ueber eine Kachel bekannt wurde.
 *
 * [eTag] und [lastModified] sind die **rohen Kopfzeilen** des Servers; sie
 * werden nur verglichen, nie ausgelegt (siehe
 * [de.trailscape.core.isSameSegmentVersion]). [downloadedAtMs] ist dagegen
 * unsere eigene Uhr und beantwortet die andere Frage: „wann habe ich das
 * zuletzt geholt?"
 */
data class SegmentMetadata(
    val eTag: String? = null,
    val lastModified: String? = null,
    val downloadedAtMs: Long? = null,
    /**
     * Das Kennzeichen (`ETag`, ersatzweise `Last-Modified`) des Serverstands,
     * zu dem die **Teildatei** eines abgebrochenen Downloads gehoert.
     *
     * Ohne diesen Vermerk waere Fortsetzen gefaehrlich: Beim naechsten Versuch
     * kennt der Client nur den *aktuellen* Stand des Servers — schickt er den
     * als `If-Range` mit, stimmt er natuerlich immer, und die alten Bytes
     * wuerden mit den neuen zusammengeklebt. Hier steht deshalb, woher die
     * Teildatei wirklich stammt; passt das nicht mehr, fliegt sie.
     */
    val partValidator: String? = null,
)

/** Eine tatsaechlich vorhandene Kachel samt allem, was ueber sie bekannt ist. */
data class InstalledSegment(
    val tile: SegmentTile,
    val sizeBytes: Long,
    val metadata: SegmentMetadata,
) {
    /** Dateiname, z. B. `E10_N50.rd5`. */
    val fileName: String get() = tile.fileName

    /**
     * Stand der Kartendaten in Millisekunden seit 1970 — aus der gemerkten
     * `Last-Modified`-Angabe des Servers, **nicht** aus dem Dateidatum (siehe
     * Datei-KDoc). `null`, wenn nichts gemerkt wurde.
     */
    val dataTimestampMs: Long? get() = parseHttpDateMs(metadata.lastModified)

    /**
     * Wie viele Tage alt die **Kartendaten** sind. `null`, wenn der Stand
     * unbekannt ist — dann soll die Oberflaeche „unbekannt" schreiben und
     * keine Zahl erfinden.
     */
    fun ageDays(nowMs: Long = System.currentTimeMillis()): Long? {
        val stamp = dataTimestampMs ?: return null
        return ((nowMs - stamp) / 86_400_000L).coerceAtLeast(0L)
    }
}

/**
 * Liest und schreibt die Kachel-Metadaten im [KeyValueStore].
 *
 * Ein Eintrag ist ein kleines JSON-Objekt unter
 * `trailscape.segment.<Dateiname>`. JSON und nicht drei Einzelschluessel,
 * damit ein Eintrag als Ganzes entsteht und als Ganzes verschwindet — halb
 * geschriebene Metadaten waeren schlimmer als gar keine.
 */
class SegmentMetadataStore(private val store: KeyValueStore) {

    fun read(fileName: String): SegmentMetadata {
        val raw = store.getString(METADATA_KEY_PREFIX + fileName) ?: return SegmentMetadata()
        // Kaputtes JSON (von Hand veraendert, halb wiederhergestellt) darf die
        // Liste nicht sprengen: dann gilt der Stand eben als unbekannt.
        val obj = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
            ?: return SegmentMetadata()
        return SegmentMetadata(
            eTag = obj["etag"]?.jsonPrimitive?.contentOrNull,
            lastModified = obj["lastModified"]?.jsonPrimitive?.contentOrNull,
            downloadedAtMs = obj["downloadedAtMs"]?.jsonPrimitive?.longOrNull,
            partValidator = obj["partValidator"]?.jsonPrimitive?.contentOrNull,
        )
    }

    fun write(fileName: String, metadata: SegmentMetadata) {
        val obj = buildJsonObject {
            metadata.eTag?.let { put("etag", it) }
            metadata.lastModified?.let { put("lastModified", it) }
            metadata.downloadedAtMs?.let { put("downloadedAtMs", it) }
            metadata.partValidator?.let { put("partValidator", it) }
        }
        store.setString(METADATA_KEY_PREFIX + fileName, obj.toString())
    }

    fun remove(fileName: String) {
        store.remove(METADATA_KEY_PREFIX + fileName)
    }
}

/**
 * Der Bestand im Kachelverzeichnis [dir].
 *
 * Alle Methoden greifen auf das Dateisystem zu und gehoeren deshalb auf einen
 * Hintergrund-Dispatcher, nicht auf den Hauptthread.
 */
class SegmentInventory(
    val dir: File,
    val metadata: SegmentMetadataStore,
) {

    /**
     * Alle vollstaendig vorhandenen Kacheln, nach Kachelname sortiert.
     *
     * Was nicht wie eine Kachel heisst, wird uebergangen — Teildateien
     * (`*.rd5.part`), Deltas und alles Fremde. Die Liste ist damit genau das,
     * was die Engine auch sehen wuerde.
     */
    fun list(): List<InstalledSegment> {
        val files = dir.listFiles() ?: return emptyList()
        return files
            .asSequence()
            .filter { it.isFile && it.name.endsWith(segmentFileSuffix) }
            .mapNotNull { file ->
                val tile = parseSegmentTile(file.name) ?: return@mapNotNull null
                InstalledSegment(
                    tile = tile,
                    sizeBytes = file.length(),
                    metadata = metadata.read(file.name),
                )
            }
            .sortedBy { it.tile.name }
            .toList()
    }

    /** Summe aller Kacheln in Bytes — die Zahl fuer „so viel Platz belegt das". */
    fun totalBytes(): Long = list().sumOf { it.sizeBytes }

    /** Ist die Kachel vollstaendig vorhanden? */
    fun contains(fileName: String): Boolean = File(dir, fileName).isFile

    /**
     * Der lokale Stand fuer den Abgleich mit dem Server, oder `null`, wenn die
     * Kachel gar nicht da ist.
     */
    fun localSegment(fileName: String): LocalSegment? {
        val file = File(dir, fileName)
        if (!file.isFile) return null
        val meta = metadata.read(fileName)
        return LocalSegment(
            fileName = fileName,
            sizeBytes = file.length(),
            eTag = meta.eTag,
            lastModified = meta.lastModified,
        )
    }

    /**
     * Wie viele Bytes eines abgebrochenen Downloads schon auf der Platte
     * liegen — die Grundlage fuer „Fortsetzen (87 von 119 MB)".
     */
    fun partialBytes(fileName: String): Long {
        val part = File(dir, fileName + SEGMENT_PART_SUFFIX)
        return if (part.isFile) part.length() else 0L
    }

    /**
     * Loescht eine Kachel samt Metadaten und allen Zwischendateien.
     *
     * @return `true`, wenn danach nichts mehr da ist.
     */
    fun delete(fileName: String): Boolean {
        val ok = deleteQuietly(File(dir, fileName))
        deleteTemporaries(fileName)
        metadata.remove(fileName)
        return ok
    }

    /**
     * Raeumt die Zwischendateien einer Kachel weg — die angefangene
     * Teildatei, ein geladenes Delta und eine halb zusammengesetzte Kachel.
     *
     * Ausdruecklich **nicht** Teil des normalen Fehlerwegs: Eine `*.part` ist
     * wertvoll, weil der naechste Versuch auf ihr aufsetzt. Sie fliegt nur,
     * wenn die Nutzerin die Kachel loescht oder der Server sagt, dass sie
     * nicht mehr passt.
     */
    fun deleteTemporaries(fileName: String) {
        deleteQuietly(File(dir, fileName + SEGMENT_PART_SUFFIX))
        // Der Vermerk gehoert zur Teildatei und stirbt mit ihr.
        val meta = metadata.read(fileName)
        if (meta.partValidator != null) {
            metadata.write(fileName, meta.copy(partValidator = null))
        }
        deleteQuietly(File(dir, fileName + SEGMENT_DELTA_TEMP_SUFFIX))
        deleteQuietly(File(dir, fileName + SEGMENT_NEW_SUFFIX))
    }

    private fun deleteQuietly(file: File): Boolean = !file.exists() || file.delete()
}
