package de.trailscape.app.data

import de.trailscape.core.Ride
import java.io.File
import java.io.FileOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

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
 * Anders als das Dart-Original (das `dir.list()` gegen `Directory` nutzt und
 * damit async/Future-basiert ist) sind alle Methoden hier synchron: Aufrufer
 * (ViewModels in Phase 4) sind fuer den `Dispatchers.IO`-Wechsel selbst
 * verantwortlich, siehe [AppServices.appScope].
 *
 * @param ridesDir Wurzelverzeichnis fuer die Tour-Dateien. In der App
 *   `<filesDir>/rides`, siehe [AppServices]. Als Konstruktor-Parameter (statt
 *   fest verdrahtetem `Context`-Zugriff) gehalten, damit sich die Klasse ohne
 *   Android-Runtime instanziieren liesse — Tests dafuer fehlen in diesem
 *   Modul dennoch bewusst (siehe Klassendoc unten).
 */
class RideStorage(private val ridesDir: File) {

    private fun ensureDir(): File {
        if (!ridesDir.exists()) {
            ridesDir.mkdirs()
        }
        return ridesDir
    }

    private fun rideFile(dir: File, id: String): File = File(dir, "$id.json")

    /**
     * Liefert alle gespeicherten Touren, neueste zuerst (nach `createdAt`
     * absteigend sortiert). Dateien, die nicht als gueltige Tour gelesen
     * werden koennen (kaputtes JSON, falsches Format), werden uebersprungen
     * — genau wie im Dart-Original.
     */
    fun listRides(): List<Ride> {
        val dir = ensureDir()
        val files = dir.listFiles() ?: emptyArray()

        val rides = mutableListOf<Ride>()
        for (file in files) {
            if (!file.isFile) continue
            if (!file.name.endsWith(".json")) continue
            if (file.name.endsWith(".tmp")) continue
            readRideFile(file)?.let { rides.add(it) }
        }

        rides.sortByDescending { it.createdAt }
        return rides
    }

    /** Liefert eine einzelne Tour anhand ihrer ID, oder `null` falls sie nicht existiert oder nicht gelesen werden kann. */
    fun getRide(id: String): Ride? {
        val file = rideFile(ensureDir(), id)
        if (!file.exists()) return null
        return readRideFile(file)
    }

    private fun readRideFile(file: File): Ride? = try {
        val raw = file.readText(Charsets.UTF_8)
        val json = Json.parseToJsonElement(raw) as JsonObject
        Ride.fromJson(json)
    } catch (e: Exception) {
        // Defekte Datei ueberspringen — entspricht dem catch-all im
        // Dart-Original (kaputtes JSON, falsches Format, IO-Fehler).
        null
    }

    /**
     * Speichert eine Tour atomar: es wird zunaechst in eine `.tmp`-Datei
     * geschrieben, die anschliessend auf den endgueltigen Dateinamen
     * umbenannt wird. Dadurch bleibt bei einem Absturz waehrend des
     * Schreibens niemals eine halb geschriebene Tour-Datei zurueck.
     *
     * Der Dateiname ergibt sich allein aus [Ride.id] — die Funktion ist damit
     * zugleich das Update: eine bereits gespeicherte Tour mit derselben ID
     * wird vollstaendig ersetzt (z. B. wenn der Health-Import sie
     * nachtraeglich um Herzfrequenzdaten anreichert).
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
    fun saveRide(ride: Ride) {
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

    /** Speichert bzw. aktualisiert mehrere Touren nacheinander (siehe [saveRide]). */
    fun saveRides(rides: Iterable<Ride>) {
        rides.forEach { saveRide(it) }
    }

    /** Loescht eine Tour. Existiert sie nicht, passiert nichts. */
    fun deleteRide(id: String) {
        val file = rideFile(ensureDir(), id)
        if (file.exists()) {
            file.delete()
        }
    }
}
