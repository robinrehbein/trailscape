package de.trailscape.app.data

import de.trailscape.core.Ride
import de.trailscape.core.RideSummary
import java.io.File
import java.io.FileOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Persistenz fuer aufgezeichnete Touren.
 *
 * Port von `lib/storage.dart`: Touren werden als einzelne JSON-Dateien unter
 * `<filesDir>/rides/<id>.json` abgelegt. Das JSON-Format entspricht exakt
 * [Ride.toJson]/[Ride.fromJson] aus `:core` und ist damit bytegetreu
 * kompatibel zum Selfhost-Sync-Server, zur Web-App-Referenz UND zu den
 * Dateien der bestehenden Flutter-App — das ist der geplante Umzugskanal
 * (Backup-Import), NICHT ein Format-Bruch. Ein `.json`-Backup aus der
 * Flutter-App laesst sich unveraendert in dieses Verzeichnis kopieren.
 *
 * ## Der Metadaten-Index (`index.json`)
 * Frueher parste `listRides()` bei jedem Aufruf JEDE Datei vollstaendig —
 * inklusive saemtlicher GPS-Punkte. Bei ~500 Touren × 4000 Punkten sind das
 * 200+ MB geboxter Nullable-Felder im Heap, nur um eine Liste zu zeigen.
 * Seitdem haelt `<filesDir>/rides/index.json` je Tour eine punktfreie
 * [RideSummary] plus einen Datei-Fingerabdruck (Groesse + mtime):
 *
 *  * [listSummaries] liest den Index und gleicht ihn gegen das Verzeichnis
 *    ab — nur neue/geaenderte Dateien werden nachgeparst, verschwundene
 *    fliegen heraus. Fehlt der Index oder ist er kaputt, wird er komplett
 *    aus den Tour-Dateien neu aufgebaut; er ist ein reiner **Cache**, nie
 *    die Wahrheit.
 *  * [saveRide]/[deleteRide] pflegen den Index inkrementell und schreiben
 *    ihn atomar (tmp + rename) — ohne `fsync`, denn ein im Absturz
 *    verlorener Index wird beim naechsten [listSummaries] neu aufgebaut.
 *  * [loadRide] liefert die volle Tour fuer den, der wirklich Punkte
 *    braucht (Detailansicht, Kartenzeichnung, GPX-Export, Sync-Push).
 *
 * ## Defekte Dateien: Quarantaene statt stilles Verschwinden
 * Frueher schluckte das Einlesen jede Exception — eine defekte Datei
 * verschwand lautlos aus der Liste UND aus jedem spaeteren Backup. Jetzt
 * wandert sie nach `<filesDir>/rides/defekt/`, und [listSummaries] meldet
 * die Anzahl im Ergebnis, damit die App es einmalig anzeigen kann. Die
 * Datei selbst bleibt erhalten (nichts wird geloescht), nur eben ausserhalb
 * des aktiven Bestands. `tombstones.json` und `index.json` selbst sind davon
 * ausgenommen — sie sind bekannte Nicht-Tour-Dateien im selben Verzeichnis
 * (siehe [TombstoneStore]).
 *
 * Anders als das Dart-Original sind alle Methoden synchron: Aufrufer
 * (ViewModels) sind fuer den `Dispatchers.IO`-Wechsel selbst verantwortlich,
 * siehe [AppServices.appScope]. Die Methoden sind `@Synchronized`, damit
 * parallele Aufrufe (Sync-Lauf neben Health-Import) den Index nicht
 * zerschreiben.
 *
 * @param ridesDir Wurzelverzeichnis fuer die Tour-Dateien. In der App
 *   `<filesDir>/rides`, siehe [AppServices]. Als Konstruktor-Parameter (statt
 *   fest verdrahtetem `Context`-Zugriff) gehalten, damit sich die Klasse ohne
 *   Android-Runtime instanziieren und als JVM-Unit-Test pruefen laesst
 *   (siehe `app/src/test/.../RideStorageTest.kt`).
 */
class RideStorage(private val ridesDir: File) {

    /**
     * Ergebnis von [listSummaries]: die Zusammenfassungen (neueste zuerst)
     * plus die Zahl der in diesem Lauf als defekt aussortierten Dateien.
     * [quarantinedCount] > 0 heisst: genau jetzt sind Dateien nach `defekt/`
     * verschoben worden — die App zeigt dann einmalig einen Hinweis.
     */
    data class SummaryListing(
        val summaries: List<RideSummary>,
        val quarantinedCount: Int,
    )

    /** Ein Index-Eintrag: Zusammenfassung plus Datei-Fingerabdruck. */
    private data class IndexEntry(
        val fileName: String,
        val fileSize: Long,
        val fileModifiedAt: Long,
        val summary: RideSummary,
    )

    /**
     * In-Memory-Spiegel des Index, Schluessel ist der Dateiname. `null` =
     * noch nicht geladen. Zugriff nur aus `@Synchronized`-Methoden.
     */
    private var indexCache: MutableMap<String, IndexEntry>? = null

    private fun ensureDir(): File {
        if (!ridesDir.exists()) {
            ridesDir.mkdirs()
        }
        return ridesDir
    }

    private fun rideFile(dir: File, id: String): File = File(dir, "$id.json")

    private fun indexFile(): File = File(ridesDir, INDEX_FILE_NAME)

    private fun quarantineDir(): File = File(ridesDir, QUARANTINE_DIR_NAME)

    // -------------------------------------------------------------- Lesen

    /**
     * Liefert die Zusammenfassungen aller gespeicherten Touren, neueste
     * zuerst (nach `createdAt` absteigend), ohne eine einzige Punktliste in
     * den Speicher zu heben — solange der Index aktuell ist.
     *
     * Der Index wird dabei gegen das Verzeichnis abgeglichen: neue oder
     * geaenderte Dateien (Fingerabdruck Groesse + mtime weicht ab) werden
     * nachgeparst, verschwundene entfernt, defekte in Quarantaene verschoben
     * (siehe Klassen-KDoc). Ein fehlender oder kaputter Index loest den
     * kompletten Neuaufbau aus.
     */
    @Synchronized
    fun listSummaries(): SummaryListing {
        val dir = ensureDir()
        val index = loadIndex()
        val files = dir.listFiles() ?: emptyArray()

        val fresh = LinkedHashMap<String, IndexEntry>()
        var quarantined = 0
        var dirty = false

        for (file in files) {
            if (!file.isFile) continue
            // `<id>.json.tmp` u. Ä. fallen schon hier heraus — es zaehlt nur
            // die Endung `.json` (der fruehere zusaetzliche `.tmp`-Check war
            // deshalb unerreichbarer Code und ist entfallen).
            if (!file.name.endsWith(".json")) continue
            if (file.name in RESERVED_FILE_NAMES) continue

            val known = index[file.name]
            if (known != null &&
                known.fileSize == file.length() &&
                known.fileModifiedAt == file.lastModified()
            ) {
                fresh[file.name] = known
                continue
            }

            dirty = true
            val ride = readRideFile(file)
            if (ride == null) {
                quarantineFile(file)
                quarantined++
                continue
            }
            fresh[file.name] = IndexEntry(
                fileName = file.name,
                fileSize = file.length(),
                fileModifiedAt = file.lastModified(),
                summary = ride.toSummary(),
            )
        }

        if (fresh.keys != index.keys) {
            dirty = true
        }
        indexCache = fresh
        if (dirty) {
            writeIndex(fresh)
        }

        return SummaryListing(
            summaries = fresh.values.map { it.summary }.sortedByDescending { it.createdAt },
            quarantinedCount = quarantined,
        )
    }

    /**
     * Laedt eine einzelne Tour vollstaendig (mit Punkten), oder `null`, falls
     * sie nicht existiert oder nicht lesbar ist. Eine unlesbare Datei wandert
     * dabei in die Quarantaene und aus dem Index — der naechste
     * [listSummaries]-Lauf zeigt den Bestand dann ohne sie.
     */
    @Synchronized
    fun loadRide(id: String): Ride? {
        val dir = ensureDir()
        val file = rideFile(dir, id)
        if (!file.exists()) return null
        val ride = readRideFile(file)
        if (ride == null) {
            quarantineFile(file)
            val index = loadIndex()
            if (index.remove(file.name) != null) {
                writeIndex(index)
            }
        }
        return ride
    }

    /**
     * Liefert alle gespeicherten Touren VOLLSTAENDIG (inkl. Punkte), neueste
     * zuerst.
     *
     * Uebergangs-API: Im Normalbetrieb liest niemand mehr den Gesamtbestand —
     * Listen laufen ueber [listSummaries], Einzelzugriffe ueber [loadRide],
     * das Backup streamt Tour fuer Tour. Diese Methode bleibt fuer
     * Sonderfaelle, die wirklich alles brauchen, und haelt dann bewusst den
     * kompletten Bestand im Speicher. Defekte Dateien werden hier — anders
     * als frueher — ebenfalls in Quarantaene verschoben statt still
     * uebersprungen.
     */
    @Synchronized
    fun listRides(): List<Ride> {
        val listing = listSummaries()
        return listing.summaries.mapNotNull { loadRide(it.id) }
    }

    private fun readRideFile(file: File): Ride? = try {
        val raw = file.readText(Charsets.UTF_8)
        val json = Json.parseToJsonElement(raw) as JsonObject
        Ride.fromJson(json)
    } catch (e: Exception) {
        // Kaputtes JSON, falsches Format, IO-Fehler: Der Aufrufer entscheidet
        // ueber Quarantaene — hier nur das Signal.
        null
    }

    /**
     * Verschiebt eine unlesbare Datei nach `defekt/` statt sie still zu
     * ueberspringen. Schlaegt das Verschieben fehl (z. B. kein Platz), bleibt
     * die Datei liegen und faellt beim naechsten Lauf erneut auf — besser
     * eine wiederholte Meldung als ein stiller Verlust.
     */
    private fun quarantineFile(file: File) {
        try {
            val dir = quarantineDir()
            if (!dir.exists()) {
                dir.mkdirs()
            }
            var target = File(dir, file.name)
            if (target.exists()) {
                // Namenskollision (z. B. zweimal dieselbe ID defekt): eindeutig
                // machen statt die aeltere Quarantaene-Datei zu ueberschreiben.
                target = File(dir, "${file.name.removeSuffix(".json")}-${System.currentTimeMillis()}.json")
            }
            if (file.renameTo(target)) {
                println("RideStorage: unlesbare Tour-Datei ${file.name} nach ${QUARANTINE_DIR_NAME}/ verschoben.")
            } else {
                println("RideStorage: unlesbare Tour-Datei ${file.name} konnte nicht verschoben werden.")
            }
        } catch (e: Exception) {
            println("RideStorage: Quarantaene fuer ${file.name} fehlgeschlagen: $e")
        }
    }

    // ------------------------------------------------------------ Schreiben

    /**
     * Speichert eine Tour atomar: es wird zunaechst in eine `.tmp`-Datei
     * geschrieben, die anschliessend auf den endgueltigen Dateinamen
     * umbenannt wird. Dadurch bleibt bei einem Absturz waehrend des
     * Schreibens niemals eine halb geschriebene Tour-Datei zurueck.
     *
     * Der Dateiname ergibt sich allein aus [Ride.id] — die Funktion ist damit
     * zugleich das Update: eine bereits gespeicherte Tour mit derselben ID
     * wird vollstaendig ersetzt (z. B. wenn der Health-Import sie
     * nachtraeglich um Herzfrequenzdaten anreichert). Der Index-Eintrag wird
     * im selben Zug aktualisiert — kein erneutes Einlesen des Bestands.
     *
     * ## Warum `fd.sync()` und nicht nur `writeText`
     * Diese Methode steht am Ende der Absturzsicherung: Unmittelbar nachdem sie
     * zurueckkehrt, verwirft der Aufzeichnungsdienst das Journal — die einzige
     * andere Kopie der Fahrt. `writeText` + `renameTo` geben die Bytes aber nur
     * an den Seitencache des Kernels ab; ein leerer Akku oder ein
     * Kernel-Absturz in den Sekunden danach hinterlaesst eine leere oder halbe
     * Datei, waehrend das Journal bereits geloescht ist. Das Journal betreibt
     * fuer genau dieses Versprechen `flush()` + `FileDescriptor.sync()` bei
     * *jedem* Punkt (siehe
     * [de.trailscape.app.record.RecordingJournal]); die Datei, die es ersetzt,
     * muss dieselbe Zusage geben, sonst ist die ganze Kette nur so stark wie
     * ihr letztes Glied.
     *
     * Der Preis ist ein erzwungener Flash-Schreibvorgang je gespeicherter Tour
     * — bei einer Handvoll Touren pro Woche und einem Massenimport, der ohnehin
     * IO-gebunden ist, nicht messbar.
     */
    @Synchronized
    fun saveRide(ride: Ride) {
        saveRideInternal(ride)
        writeIndex(loadIndex())
    }

    /**
     * Speichert bzw. aktualisiert mehrere Touren nacheinander (siehe
     * [saveRide]) — der Index wird dabei nur EINMAL am Ende geschrieben.
     */
    @Synchronized
    fun saveRides(rides: Iterable<Ride>) {
        var any = false
        rides.forEach {
            saveRideInternal(it)
            any = true
        }
        if (any) {
            writeIndex(loadIndex())
        }
    }

    /** Schreibt die Datei und pflegt den In-Memory-Index; persistiert ihn NICHT. */
    private fun saveRideInternal(ride: Ride) {
        val dir = ensureDir()
        val file = rideFile(dir, ride.id)
        val tmpFile = File(dir, "${file.name}.tmp")

        val json = ride.toJson().toString()
        writeAndSync(tmpFile, json)
        if (!tmpFile.renameTo(file)) {
            // Fallback fuer Dateisysteme/Umstaende, in denen rename fehlschlaegt
            // (z. B. Ziel liegt auf einem anderen Mount). Kopieren+Loeschen ist
            // nicht atomar, aber besser als eine verlorene Aufzeichnung.
            writeAndSync(file, json)
            tmpFile.delete()
        }

        loadIndex()[file.name] = IndexEntry(
            fileName = file.name,
            fileSize = file.length(),
            fileModifiedAt = file.lastModified(),
            summary = ride.toSummary(),
        )
    }

    /**
     * Schreibt [text] nach [target] und erzwingt die Bytes auf den
     * Datentraeger. Siehe die Begruendung an [saveRide].
     *
     * `sync()` selbst darf scheitern (manche Dateisysteme lehnen es ab); dann
     * bleibt es beim Verhalten von `writeText`, statt das Speichern der Tour an
     * einer Nebensaechlichkeit scheitern zu lassen. Ein Fehler beim *Schreiben*
     * wird dagegen weitergereicht — der Aufrufer haelt dann das Journal fest.
     */
    private fun writeAndSync(target: File, text: String) {
        FileOutputStream(target).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.flush()
            try {
                out.fd.sync()
            } catch (e: Exception) {
                // Kein Grund, die Tour zu verlieren.
            }
        }
    }

    /** Loescht eine Tour (Datei + Index-Eintrag). Existiert sie nicht, passiert nichts. */
    @Synchronized
    fun deleteRide(id: String) {
        val dir = ensureDir()
        val file = rideFile(dir, id)
        if (file.exists()) {
            file.delete()
        }
        val index = loadIndex()
        if (index.remove(file.name) != null) {
            writeIndex(index)
        }
    }

    // ---------------------------------------------------------------- Index

    /**
     * Laedt den Index in den Speicher (einmalig). Fehlende oder kaputte Datei
     * ergibt eine leere Map — [listSummaries] baut dann alles neu auf.
     */
    private fun loadIndex(): MutableMap<String, IndexEntry> {
        indexCache?.let { return it }
        val loaded = LinkedHashMap<String, IndexEntry>()
        val file = indexFile()
        if (file.exists()) {
            try {
                val root = Json.parseToJsonElement(file.readText(Charsets.UTF_8)) as JsonObject
                val entries = root["entries"] as? JsonArray ?: JsonArray(emptyList())
                for (element in entries) {
                    val obj = element as? JsonObject ?: continue
                    val entry = readIndexEntry(obj) ?: continue
                    loaded[entry.fileName] = entry
                }
            } catch (e: Exception) {
                // Kaputter Index: Cache leer lassen — der naechste
                // listSummaries-Lauf parst die Tour-Dateien neu und schreibt
                // einen frischen Index. Kein Datenverlust, der Index ist nur
                // ein Cache.
                loaded.clear()
            }
        }
        indexCache = loaded
        return loaded
    }

    private fun readIndexEntry(obj: JsonObject): IndexEntry? {
        val fileName = (obj["file"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        val size = (obj["size"] as? JsonPrimitive)?.content?.toLongOrNull() ?: return null
        val mtime = (obj["mtime"] as? JsonPrimitive)?.content?.toLongOrNull() ?: return null
        val summary = try {
            RideSummary.fromJson(obj["summary"] as? JsonObject ?: return null)
        } catch (e: Exception) {
            return null
        }
        return IndexEntry(fileName = fileName, fileSize = size, fileModifiedAt = mtime, summary = summary)
    }

    /**
     * Schreibt den Index atomar (tmp + rename). Scheitern ist unkritisch —
     * der Index wird beim naechsten Lauf neu aufgebaut; deshalb (anders als
     * bei den Tour-Dateien) auch kein `fsync`.
     */
    private fun writeIndex(index: Map<String, IndexEntry>) {
        try {
            val dir = ensureDir()
            val json = buildJsonObject {
                put("version", 1)
                put(
                    "entries",
                    buildJsonArray {
                        index.values.forEach { entry ->
                            add(
                                buildJsonObject {
                                    put("file", entry.fileName)
                                    put("size", entry.fileSize)
                                    put("mtime", entry.fileModifiedAt)
                                    put("summary", entry.summary.toJson())
                                },
                            )
                        }
                    },
                )
            }
            val file = indexFile()
            val tmp = File(dir, "${file.name}.tmp")
            tmp.writeText(json.toString(), Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                file.writeText(json.toString(), Charsets.UTF_8)
                tmp.delete()
            }
        } catch (e: Exception) {
            // Bewusst geschluckt: Ein nicht geschriebener Index kostet nur den
            // Neuaufbau beim naechsten Start, nie Tourdaten.
        }
    }

    companion object {
        /** Dateiname des Metadaten-Index im Touren-Verzeichnis. */
        const val INDEX_FILE_NAME: String = "index.json"

        /** Unterverzeichnis fuer unlesbare Tour-Dateien (Quarantaene). */
        const val QUARANTINE_DIR_NAME: String = "defekt"

        /**
         * Bekannte Nicht-Tour-Dateien im Touren-Verzeichnis: der Index
         * selbst, der Loesch-Merkzettel des Syncs (siehe [TombstoneStore])
         * und der Tourlast-Cache (siehe [RideLoadCacheStore]). Sie duerfen
         * weder als Tour gelesen noch in Quarantaene verschoben werden.
         */
        private val RESERVED_FILE_NAMES = setOf(INDEX_FILE_NAME, "tombstones.json", "last-cache.json")
    }
}
