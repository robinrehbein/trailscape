package de.trailscape.app.data

import de.trailscape.core.RouteSignatureStore
import de.trailscape.core.StoredRouteSignature
import de.trailscape.core.readRouteSignatureCache
import de.trailscape.core.writeRouteSignatureCache
import java.io.File

/**
 * Persistenter Cache der Strecken-Signaturen je Tour (siehe `:core`,
 * `RouteMatch.kt`) — eine einzelne JSON-Datei
 * `<filesDir>/route-cache/route-signatures.json`.
 *
 * ## Warum nicht in `rides/`
 * `RideStorage.listSummaries` liest jede `.json` im Touren-Verzeichnis als
 * Tour und verschiebt, was keine ist, nach `defekt/` — ausser den Namen auf
 * seiner Liste reservierter Dateien. Dieser Cache steht nicht darauf; im
 * Touren-Verzeichnis landete er nach jedem Neuladen in der Quarantaene (samt
 * Meldung „Tour-Datei unlesbar"). Deshalb ein eigenes Verzeichnis
 * ([DIR_NAME]).
 *
 * ## Warum persistent
 * Dieselbe Rechnung wie beim Kachel-Cache nebenan ([ExplorerTilesCacheStore]):
 * Durch welche ~200-m-Zellen eine Tour fuehrte, faellt erst beim Durchlaufen
 * ihrer GPS-Punkte ab. Ohne Cache muesste „Diese Runde bist du 5× gefahren"
 * bei jedem Oeffnen einer Tour den ganzen Bestand laden. Mit Cache passiert
 * der Punktdurchlauf je Tour genau einmal; danach genuegen die
 * Zusammenfassungen.
 *
 * ## Invalidierung
 * Die uebernimmt der Eintrag selbst ([StoredRouteSignature]): `updatedAt` der
 * Tour plus die Zahl ihrer Punkte. Diese Klasse haelt nur Eintraege vor und
 * wirft per [retainAll] weg, was zu geloeschten Touren gehoert. Eine Tour
 * ohne Signatur (zu kurz) steht mit `signature: null` drin — sonst wuerde sie
 * bei jedem Lauf erneut geladen.
 *
 * ## Dateiformat
 * `{"version":2,"entries":[{"id":"…","updatedAt":…,"pointCount":…,"signature":{…}}]}`;
 * Aufbau und Lesen/Schreiben stehen in `:core` (`readRouteSignatureCache`,
 * `writeRouteSignatureCache`) — dort ist das Serialisierungs-Plugin
 * angewandt, und es bleibt eine reine JVM-Sache. Die Zellen stehen als
 * sortiertes Zahlenfeld; bei einer 40-km-Runde ein paar hundert Zahlen.
 * Gelesen und geschrieben wird **gestreamt** (kein JSON-Baum, kein ganzer Text
 * im Speicher): Bei tausenden Touren waere das sonst ein Mehrfaches der Daten
 * und ein Speicherspitzenwert.
 *
 * Eine Datei mit anderer (oder fehlender) `version` — etwa die mit dem alten
 * Zellraster — gilt als leerer Cache und wird neu aufgebaut; alte und neue
 * Zellen werden nie gegeneinander verglichen. Dasselbe gilt fuer eine kaputte
 * Datei. Ein einzelner Eintrag mit unbrauchbaren Werten faellt weg, der Rest
 * bleibt.
 *
 * ## Speicher
 * Die Eintraege stehen nur **waehrend einer Benutzung** im Speicher: [flush]
 * (das der Aufrufer am Ende jedes Sammellaufs ruft, auch ohne Aenderung)
 * wirft sie wieder weg, die naechste Benutzung liest die Datei neu. Der
 * Store lebt im `AppServices`-Singleton, ein dauerhaft gehaltener Bestand
 * wuerde bei tausenden Touren dauerhaft Speicher belegen.
 *
 * Geschrieben wird nur bei Aenderungen, gesammelt in [flush], atomar per tmp +
 * rename und ohne `fsync`: Ein im Absturz verlorener Cache kostet nur
 * Neuberechnung, nie Daten.
 *
 * Alle Methoden sind synchron und `@Synchronized`; der Sammellauf laeuft auf
 * `Dispatchers.IO`.
 */
class RouteSignatureCacheStore(private val dir: File) : RouteSignatureStore {

    private var entriesCache: MutableMap<String, StoredRouteSignature>? = null
    private var dirty = false

    private fun cacheFile(): File = File(dir, FILE_NAME)

    private fun entries(): MutableMap<String, StoredRouteSignature> {
        entriesCache?.let { return it }
        val file = cacheFile()
        var loaded: MutableMap<String, StoredRouteSignature>? = null
        if (file.exists()) {
            loaded = try {
                file.inputStream().buffered().use { readRouteSignatureCache(it) }
            } catch (e: Exception) {
                null
            }
            // Kaputt oder andere Version = leerer Cache; die Datei wird beim naechsten flush ersetzt.
            if (loaded == null) dirty = true
        }
        return (loaded ?: LinkedHashMap()).also { entriesCache = it }
    }

    @Synchronized
    override fun read(rideId: String): StoredRouteSignature? = entries()[rideId]

    @Synchronized
    override fun write(rideId: String, value: StoredRouteSignature) {
        entries()[rideId] = value
        dirty = true
    }

    @Synchronized
    override fun remove(rideId: String) {
        if (entries().remove(rideId) != null) {
            dirty = true
        }
    }

    /** Wirft alle Eintraege weg, deren ID nicht in [ids] liegt (geloeschte oder geplante Touren). */
    @Synchronized
    fun retainAll(ids: Set<String>) {
        val map = entries()
        if (map.keys.retainAll(ids)) {
            dirty = true
        }
    }

    /**
     * Schreibt den Stand fest, falls sich etwas geaendert hat, und gibt die
     * Eintraege in jedem Fall frei (naechste Benutzung liest neu von der Platte).
     */
    @Synchronized
    fun flush() {
        val map = entriesCache
        try {
            if (map != null && dirty) {
                if (!dir.exists()) {
                    dir.mkdirs()
                }
                val file = cacheFile()
                val tmp = File(dir, "${file.name}.tmp")
                tmp.outputStream().buffered().use { writeRouteSignatureCache(map, it) }
                if (!tmp.renameTo(file)) {
                    tmp.copyTo(file, overwrite = true)
                    tmp.delete()
                }
            }
        } catch (e: Exception) {
            // Bewusst geschluckt: Ein nicht geschriebener Cache kostet nur
            // Neuberechnung beim naechsten Mal.
        } finally {
            // Auch nach einem Fehlschlag weg: Die Eintraege sind sonst die
            // einzige Kopie im Speicher, und ein haengender dirty-Zustand
            // wuerde sie dauerhaft halten.
            entriesCache = null
            dirty = false
        }
    }

    companion object {
        /** Dateiname des Signatur-Caches. */
        const val FILE_NAME: String = "route-signatures.json"

        /** Verzeichnis unter `filesDir` — bewusst nicht `rides/` (siehe oben). */
        const val DIR_NAME: String = "route-cache"
    }
}
