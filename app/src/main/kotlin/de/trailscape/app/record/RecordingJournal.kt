package de.trailscape.app.record

import de.trailscape.core.TrackPoint
import java.io.File
import java.io.FileOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Absturzsicheres Aufzeichnungsjournal.
 *
 * Der Kern der Zusage „kein bestaetigter GPS-Punkt geht verloren": Jeder vom
 * [de.trailscape.core.PointFilter] angenommene Punkt wird SOFORT als eigene
 * JSON-Zeile an `<filesDir>/recording/active.jsonl` angehaengt, mit
 * anschliessendem `flush()` + `FileDescriptor.sync()`. Nach der Rueckkehr aus
 * [appendPoint] liegt der Punkt physisch auf dem Flash — ein Prozesstod,
 * ein Absturz oder ein leerer Akku im naechsten Moment kostet ihn nicht mehr.
 * Der RAM haelt die Punkte nur noch fuer die Anzeige.
 *
 * ## Zeilenformat (JSON Lines, UTF-8, `\n` als Trenner)
 *
 * Zeile 1 — Kopfzeile:
 * ```json
 * {"v":1,"type":"header","id":"1723118400000-1a2b3c","startedAt":1723118400000}
 * ```
 * Punktzeile (`point` ist exakt [TrackPoint.toJson], also dasselbe Format wie
 * in den Tour-Dateien und beim Sync-Server):
 * ```json
 * {"type":"point","point":{"lat":52.51,"lon":13.37,"ele":38.0,"time":1723118405000}}
 * ```
 * Pause/Fortsetzung:
 * ```json
 * {"type":"pause","at":1723118500000}
 * {"type":"resume","at":1723118560000}
 * ```
 *
 * Nicht parsebare Zeilen werden beim Lesen uebersprungen. Das deckt genau den
 * Fall ab, der bei einem Absturz mitten im Schreiben entstehen kann: eine
 * abgeschnittene letzte Zeile. Alle davor liegenden Punkte bleiben gueltig.
 *
 * ## Heartbeat
 *
 * Neben dem Journal liegt `<filesDir>/recording/active.lock` mit dem
 * Zeitstempel des letzten Lebenszeichens des Service. Daran unterscheidet
 * [RecordingService.recoverIfNeeded] ein verwaistes Journal (App abgestuertzt,
 * Aufzeichnung liegt herrenlos herum) von einem, das ein gerade vom System
 * neu gestarteter Service in den naechsten Sekunden selbst fortsetzen wird.
 * Das Format der Datei und die Auswertung stehen in [HeartbeatStamp] bzw.
 * [bewerteLebenszeichen].
 *
 * Alle oeffentlichen Methoden sind synchronisiert: Der Service schreibt aus
 * seinem Aufzeichnungs-Thread, [claimStale]/[readAll] koennen aus einem
 * beliebigen anderen Thread kommen.
 *
 * @param uhr Zeitquelle des Lebenszeichens. Hereingereicht statt fest
 *   verdrahtet, damit diese Klasse ohne einen einzigen Android-Import
 *   auskommt — `SystemClock.elapsedRealtime()` lebt in `android.os` und waere
 *   in einem reinen JVM-Test nicht zu haben. Die App reicht
 *   [AndroidHeartbeatClock] herein.
 */
internal class RecordingJournal(
    private val dir: File,
    private val uhr: HeartbeatClock = WallClockHeartbeatClock,
) {

    private val file = File(dir, ACTIVE_FILE_NAME)
    private val lockFile = File(dir, LOCK_FILE_NAME)

    private var stream: FileOutputStream? = null

    /** Zusammenfassung eines gelesenen Journals. */
    data class Snapshot(
        /** ID aus der Kopfzeile, oder eine neu erzeugte, falls die Kopfzeile fehlt. */
        val id: String,
        /** Startzeit der Aufzeichnung in ms seit Epoch. */
        val startedAtMs: Long,
        val points: List<TrackPoint>,
        /** Summe abgeschlossener Pausen in ms. */
        val pausedMs: Long,
        /** Beginn einer noch offenen Pause in ms seit Epoch, sonst `null`. */
        val pausedSinceMs: Long?,
        /** `true`, wenn mindestens eine Zeile nicht gelesen werden konnte. */
        val hadUnreadableLines: Boolean,
    )

    /** Ob ein (moeglicherweise verwaistes) Journal existiert. */
    fun exists(): Boolean = synchronized(this) { file.isFile && file.length() > 0 }

    /**
     * Beginnt ein neues Journal: legt das Verzeichnis an, verwirft eine evtl.
     * vorhandene Datei und schreibt die Kopfzeile.
     */
    fun begin(id: String, startedAtMs: Long) = synchronized(this) {
        closeStream()
        dir.mkdirs()
        file.delete()
        stream = FileOutputStream(file, /* append = */ true)
        writeLine(
            buildJsonObject {
                put("v", FORMAT_VERSION)
                put("type", TYPE_HEADER)
                put("id", id)
                put("startedAt", startedAtMs)
            },
        )
    }

    /**
     * Oeffnet ein bestehendes Journal zum Weiterschreiben (nach einem
     * Service-Neustart durch das System). Es wird keine neue Kopfzeile
     * geschrieben.
     */
    fun reopenForAppend() = synchronized(this) {
        closeStream()
        dir.mkdirs()
        stream = FileOutputStream(file, /* append = */ true)
    }

    /** Haengt einen angenommenen Punkt an und erzwingt das Schreiben auf den Datentraeger. */
    fun appendPoint(point: TrackPoint) = synchronized(this) {
        writeLine(
            buildJsonObject {
                put("type", TYPE_POINT)
                put("point", point.toJson())
            },
        )
    }

    /** Vermerkt den Beginn einer Pause. */
    fun appendPause(atMs: Long) = synchronized(this) {
        writeLine(
            buildJsonObject {
                put("type", TYPE_PAUSE)
                put("at", atMs)
            },
        )
    }

    /** Vermerkt das Ende einer Pause. */
    fun appendResume(atMs: Long) = synchronized(this) {
        writeLine(
            buildJsonObject {
                put("type", TYPE_RESUME)
                put("at", atMs)
            },
        )
    }

    /** Liest das aktive Journal, oder `null` wenn es fehlt bzw. keinen Inhalt hat. */
    fun read(): Snapshot? = synchronized(this) { parse(file) }

    /** Schliesst den Schreib-Stream, ohne das Journal zu loeschen. */
    fun close() = synchronized(this) { closeStream() }

    /** Schliesst und loescht Journal und Heartbeat — nach erfolgreichem Speichern der Tour. */
    fun discard() = synchronized(this) {
        closeStream()
        file.delete()
        lockFile.delete()
    }

    /**
     * Schreibt ein Lebenszeichen (siehe Klassendoc).
     *
     * @return `false`, wenn das Schreiben fehlgeschlagen ist. Der Rueckgabewert
     *   ist wichtig und darf nicht ignoriert werden: Frueher verschluckte diese
     *   Methode jede Exception mit der Begruendung, ein fehlendes Lebenszeichen
     *   koste hoechstens ein „(wiederhergestellt)" im Tournamen. Das stimmte
     *   nicht — ein fehlendes Lebenszeichen liess die Wiederherstellung
     *   `active.jsonl` umbenennen, waehrend dieser Dienst ueber seinen offenen
     *   Dateideskriptor weiterschrieb. Der Dienst muss davon erfahren und es
     *   melden; die Auswertung behandelt ein fehlendes Lebenszeichen seither
     *   ausserdem als „unbekannt" statt als „tot" (siehe [beurteileJournal]).
     */
    fun touchHeartbeat(nowMs: Long = uhr.wallClockMs()): Boolean = synchronized(this) {
        try {
            dir.mkdirs()
            val stempel = HeartbeatStamp(
                wallClockMs = nowMs,
                elapsedRealtimeMs = uhr.elapsedRealtimeMs(),
                bootId = uhr.bootId(),
            )
            lockFile.writeText(stempel.serialisiere(), Charsets.UTF_8)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Loescht das Lebenszeichen, ohne das Journal anzutasten — nach dem Ende
     * einer Aufzeichnung. Bleibt danach doch ein Journal liegen (z. B. weil
     * das Speichern der Tour fehlgeschlagen ist), gilt es sofort als verwaist
     * und die naechste Wiederherstellung nimmt sich seiner an, statt 30 s auf
     * das Verfallen des Lebenszeichens zu warten.
     */
    fun clearHeartbeat() = synchronized(this) {
        lockFile.delete()
        Unit
    }

    /**
     * Das gespeicherte Lebenszeichen, oder `null`, wenn es keines gibt bzw. es
     * sich nicht lesen laesst. Die Bewertung uebernimmt
     * [bewerteLebenszeichen] — hier wird nur gelesen.
     */
    fun lebenszeichen(): HeartbeatStamp? = synchronized(this) {
        try {
            if (!lockFile.isFile) return null
            HeartbeatStamp.parse(lockFile.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Alter der letzten Aenderung an `active.jsonl` in ms, oder `null`, wenn
     * das Dateisystem nichts darueber sagt.
     *
     * Die zweite Quelle fuer [beurteileJournal], wenn das Lebenszeichen
     * schweigt: Sie beantwortet die einzige Frage, auf die es ankommt — hat
     * ueberhaupt noch jemand geschrieben? Bezugsgroesse ist zwangslaeufig die
     * Wanduhr, denn mehr legt ein Dateisystem nicht ab.
     */
    fun journalAlterMs(nowWallMs: Long): Long? = synchronized(this) {
        val geaendert = try {
            file.lastModified()
        } catch (e: Exception) {
            0L
        }
        if (geaendert <= 0L) return null
        return (nowWallMs - geaendert).coerceAtLeast(0L)
    }

    /**
     * Schreibt eine Zeile und erzwingt sie auf den Datentraeger.
     *
     * Wirft bewusst, wenn kein Stream offen ist: Frueher stand hier
     * `val out = stream ?: return` — jede Zeile ohne offenes Journal ging damit
     * spurlos verloren, und der Aufrufer hielt sie fuer geschrieben. Genau
     * dieser stille Verlust ist das, wogegen das Journal antritt.
     */
    private fun writeLine(json: JsonObject) {
        val out = stream ?: throw IllegalStateException(
            "Journal ist nicht zum Schreiben geoeffnet (begin/reopenForAppend fehlt).",
        )
        out.write((json.toString() + "\n").toByteArray(Charsets.UTF_8))
        out.flush()
        // Das eigentliche Haltbarkeitsversprechen: erst nach sync() hat der
        // Kernel die Bytes wirklich abgegeben.
        try {
            out.fd.sync()
        } catch (e: Exception) {
            // Manche Dateisysteme lehnen sync() ab; flush() bleibt wirksam.
        }
    }

    private fun closeStream() {
        try {
            stream?.close()
        } catch (e: Exception) {
            // Beim Schliessen ist nichts mehr zu retten.
        }
        stream = null
    }

    companion object {
        const val ACTIVE_FILE_NAME = "active.jsonl"
        const val LOCK_FILE_NAME = "active.lock"
        const val FORMAT_VERSION = 1

        private const val TYPE_HEADER = "header"
        private const val TYPE_POINT = "point"
        private const val TYPE_PAUSE = "pause"
        private const val TYPE_RESUME = "resume"

        /** Verzeichnis des Journals; oeffentlich, damit die Recovery es scannen kann. */
        fun directory(filesDir: File): File = File(filesDir, "recording")

        /**
         * Prozessweite Sperre um das Beanspruchen bzw. Fortsetzen eines
         * Journals.
         *
         * `synchronized(this)` in den Instanzmethoden reicht dafuer nicht: Die
         * Wiederherstellung
         * ([de.trailscape.app.record.RecordingService.recoverIfNeeded], gerufen
         * aus `TrailscapeApplication` auf einem IO-Thread) und der Dienst, der
         * eine Aufzeichnung fortsetzt (`continueFromJournal` auf dem
         * Aufzeichnungs-Thread), arbeiten mit **verschiedenen**
         * [RecordingJournal]-Instanzen auf derselben Datei. Ohne diese
         * gemeinsame Sperre konnte [claimStale] genau zwischen `read()` und
         * `reopenForAppend()` zuschlagen: Der Dienst schrieb danach in ein
         * frisch angelegtes, kopfzeilenloses `active.jsonl` weiter, waehrend
         * die Wiederherstellung dieselben Punkte schon zu einer Tour machte —
         * aus einer Fahrt wurden zwei.
         */
        private val claimLock = Any()

        /**
         * Fuehrt [block] unter der prozessweiten Journal-Sperre aus (siehe
         * [claimLock]). Reentrant, wie `synchronized` allgemein.
         */
        fun <T> withClaimLock(block: () -> T): T = synchronized(claimLock, block)

        /**
         * Nimmt ein verwaistes Journal in Beschlag: benennt `active.jsonl` in
         * `recovering-<zeitstempel>.jsonl` um und liefert die neue Datei.
         *
         * Das Umbenennen ist der Schutz gegen ein Wettrennen mit einem
         * gleichzeitig hochfahrenden Service: Wer umbenannt hat, besitzt die
         * Daten; alle anderen finden kein `active.jsonl` mehr vor.
         */
        fun claimStale(dir: File, nowMs: Long): File? {
            val active = File(dir, ACTIVE_FILE_NAME)
            if (!active.isFile || active.length() == 0L) return null
            val claimed = File(dir, "recovering-$nowMs.jsonl")
            if (claimed.exists()) claimed.delete()
            return if (active.renameTo(claimed)) claimed else null
        }

        /**
         * Liefert alle bereits in Beschlag genommenen, aber noch nicht
         * abgeschlossenen Journale (Absturz waehrend der Wiederherstellung).
         */
        fun pendingClaimed(dir: File): List<File> =
            (dir.listFiles() ?: emptyArray())
                .filter { it.isFile && it.name.startsWith("recovering-") && it.name.endsWith(".jsonl") }
                .sortedBy { it.name }

        /**
         * Liest ein Journal (aktiv oder in Beschlag genommen) aus einer Datei.
         * Liefert `null`, wenn die Datei fehlt oder keine verwertbare Zeile
         * enthaelt.
         */
        fun parse(source: File): Snapshot? {
            if (!source.isFile) return null

            val lines = try {
                source.readLines(Charsets.UTF_8)
            } catch (e: Exception) {
                return null
            }
            if (lines.isEmpty()) return null

            var id: String? = null
            var startedAt: Long? = null
            val points = mutableListOf<TrackPoint>()
            var pausedMs = 0L
            var pausedSince: Long? = null
            var unreadable = false

            for (line in lines) {
                if (line.isBlank()) continue
                val obj = try {
                    Json.parseToJsonElement(line) as? JsonObject
                } catch (e: Exception) {
                    null
                }
                if (obj == null) {
                    // Abgeschnittene letzte Zeile nach einem Absturz — der Rest
                    // des Journals bleibt gueltig.
                    unreadable = true
                    continue
                }

                when (obj.string("type")) {
                    TYPE_HEADER -> {
                        id = obj.string("id") ?: id
                        startedAt = obj.long("startedAt") ?: startedAt
                    }

                    TYPE_POINT -> {
                        val pointObj = obj["point"] as? JsonObject
                        val point = if (pointObj == null) {
                            null
                        } else {
                            try {
                                TrackPoint.fromJson(pointObj)
                            } catch (e: Exception) {
                                null
                            }
                        }
                        if (point == null) unreadable = true else points.add(point)
                    }

                    TYPE_PAUSE -> {
                        if (pausedSince == null) pausedSince = obj.long("at")
                    }

                    TYPE_RESUME -> {
                        val since = pausedSince
                        val at = obj.long("at")
                        if (since != null && at != null && at > since) {
                            pausedMs += at - since
                        }
                        pausedSince = null
                    }

                    else -> unreadable = true
                }
            }

            val resolvedStart = startedAt
                ?: points.firstOrNull { it.time != null }?.time
                ?: return null

            return Snapshot(
                id = id ?: newRideId(resolvedStart),
                startedAtMs = resolvedStart,
                points = points,
                pausedMs = pausedMs,
                pausedSinceMs = pausedSince,
                hadUnreadableLines = unreadable,
            )
        }

        /**
         * ID-Schema wie `_newId()` in `lib/screens/map_screen.dart`:
         * `<ms seit Epoch>-<Zufallssuffix zur Basis 36>`.
         *
         * Abweichung zum Dart-Original nur beim Zeitpunkt der Vergabe: dort
         * entsteht die ID beim Speichern, hier beim Start der Aufzeichnung —
         * damit eine wiederhergestellte Tour dieselbe ID behaelt wie die, die
         * im Journal steht.
         */
        fun newRideId(nowMs: Long): String {
            val suffix = java.util.concurrent.ThreadLocalRandom.current().nextInt(0x1000000)
            return "$nowMs-${Integer.toString(suffix, 36)}"
        }

        private fun JsonObject.string(key: String): String? =
            (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull

        private fun JsonObject.long(key: String): Long? = try {
            this[key]?.jsonPrimitive?.longOrNull
        } catch (e: Exception) {
            null
        }
    }
}
