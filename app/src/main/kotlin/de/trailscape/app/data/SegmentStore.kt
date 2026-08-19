package de.trailscape.app.data

import de.trailscape.core.SegmentRegistry
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Persistenz der Segment-Registry (siehe `:core`, `RideSegments.kt`) — eine
 * einzelne JSON-Datei `<filesDir>/rides/segmente.json`.
 *
 * ## Warum persistent
 * Die Registry entsteht aus den GPS-Punkten aller Touren. Ohne Datei muesste
 * die App nach jedem Start den kompletten Bestand einmal voll laden und neu
 * durchrechnen — genau der IO-Berg, den auch der Tourlast-Cache
 * ([RideLoadCacheStore]) vermeidet. Mit Datei laeuft der Punktdurchlauf je
 * Tour genau einmal; danach traegt `segmente.json` Segmente, Efforts und den
 * Merkzettel, welche Tour mit welchem `updatedAt` schon eingerechnet ist.
 *
 * ## Invalidierung
 * Uebernimmt die Registry selbst: `processed` (Tour-ID → `updatedAt`) sagt
 * der Pflege im `AppViewModel`, welche Touren (neu) einzurechnen sind;
 * geloeschte Touren raeumt `retainRidesInSegmentRegistry` aus. Diese Klasse
 * liest und schreibt nur.
 *
 * Geschrieben wird atomar per tmp + rename und ohne `fsync` — dasselbe
 * Muster wie [RideLoadCacheStore]: Eine im Absturz verlorene oder kaputte
 * Datei liest sich als leere Registry und kostet nur Neuberechnung, nie
 * Tourdaten. Alle Methoden sind synchron und `@Synchronized`; die Pflege
 * laeuft im Hintergrund (`Dispatchers.Default`/`IO`), wo der seltene kleine
 * Dateizugriff nicht auffaellt.
 */
class SegmentStore(private val ridesDir: File) {

    private fun storeFile(): File = File(ridesDir, FILE_NAME)

    /**
     * Liest die gespeicherte Registry; eine fehlende oder unlesbare Datei
     * ergibt [SegmentRegistry.EMPTY] — die Segmente werden dann beim
     * naechsten Pflege-Lauf neu aus den Touren aufgebaut.
     */
    @Synchronized
    fun read(): SegmentRegistry {
        val file = storeFile()
        if (!file.exists()) return SegmentRegistry.EMPTY
        return try {
            val root = Json.parseToJsonElement(file.readText(Charsets.UTF_8)) as JsonObject
            SegmentRegistry.fromJson(root)
        } catch (e: Exception) {
            // Kaputte Datei = leere Registry; der naechste write ersetzt sie.
            SegmentRegistry.EMPTY
        }
    }

    /** Schreibt die Registry atomar (tmp + rename, siehe Klassen-KDoc). */
    @Synchronized
    fun write(registry: SegmentRegistry) {
        try {
            if (!ridesDir.exists()) {
                ridesDir.mkdirs()
            }
            val json = registry.toJson().toString()
            val file = storeFile()
            val tmp = File(ridesDir, "${file.name}.tmp")
            tmp.writeText(json, Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                file.writeText(json, Charsets.UTF_8)
                tmp.delete()
            }
        } catch (e: Exception) {
            // Bewusst geschluckt: Eine nicht geschriebene Registry kostet nur
            // Neuberechnung beim naechsten Start, nie Tourdaten.
        }
    }

    companion object {
        /** Dateiname der Segment-Registry im Touren-Verzeichnis. */
        const val FILE_NAME: String = "segmente.json"
    }
}
