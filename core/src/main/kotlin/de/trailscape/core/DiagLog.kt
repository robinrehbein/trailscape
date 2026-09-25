package de.trailscape.core

import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Diagnose-Log: ein kleines, lokales Protokoll technischer Ereignisse, damit
 * Fehler aus dem Feld ueberhaupt sichtbar werden — Trailscape hat keine
 * Telemetrie, und „bei mir zeichnet es manchmal nichts auf" laesst sich ohne
 * jede Spur nicht nachvollziehen.
 *
 * ## Warum die API keinen Freitext annimmt
 * Das Log landet (wenn der Nutzer es nicht abwaehlt) in einem oeffentlichen
 * GitHub-Issue. Ein `log(String)` waere eine Einladung, irgendwann doch einen
 * Tourennamen, eine Server-URL oder einen Dateipfad hineinzuschreiben — die
 * Meldungen der Ausnahmen in dieser App enthalten genau das (siehe
 * `SyncClient.kt`: „Hochladen der Tour "<Name>" ..."). Deshalb gibt es hier
 * **keinen einzigen String-Parameter**:
 *  * das Ereignis ist ein [DiagEvent] (feste Konstante),
 *  * dazu hoechstens Zahlen ([code], [count], ein Zeitpunkt),
 *  * und von einer Ausnahme nur der **Klassenname** (samt Ursachen-Kette),
 *    niemals `message` — die kann beliebige Daten tragen.
 *
 * ## Speicherung
 * Jeder Eintrag wird synchron an eine [RollingDiagFile] angehaengt (zwei
 * Dateien a 64 KB, die aelteste faellt raus) und zusaetzlich in einem kleinen
 * Ringpuffer im Speicher gehalten. Der Ringpuffer ist fuer den Absturzpfad
 * da: [snapshotForCrash] kommt ohne Datei-I/O aus und nimmt die Sperre nur per
 * `tryLock` — ein Absturz, waehrend ein anderer Thread gerade schreibt, darf
 * den Absturzbericht nicht verklemmen.
 *
 * Synchron statt ueber einen Hintergrund-Thread, weil ein Eintrag knapp
 * hundert Bytes sind und nur an **seltenen** Stellen geschrieben wird
 * (Zustandswechsel, Fehler) — nie pro GPS-Punkt. Dafuer ist garantiert, dass
 * ein Eintrag vor einem Absturz auch wirklich auf der Platte steht.
 *
 * ## Wiederholungen
 * Manche Fehler kommen im Sekundentakt (Uhr nicht erreichbar, voller
 * Speicher). Dasselbe Ereignis mit demselben Code und derselben Fehlerklasse
 * wird innerhalb von [repeatWindowMs] nur einmal geschrieben; die
 * unterdrueckten Wiederholungen zaehlt der naechste geschriebene Eintrag als
 * `wdh=<n>` mit. Sonst wuerde ein einziger Dauerfehler die 128 KB in Minuten
 * fuellen und alles Interessante davor verdraengen.
 */
class DiagLog(
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val memoryCapacity: Int = DEFAULT_MEMORY_LINES,
    private val repeatWindowMs: Long = DEFAULT_REPEAT_WINDOW_MS,
) {
    private val lock = ReentrantLock()

    /** Die juengsten Zeilen dieses Prozesses, aelteste zuerst. Nur unter [lock]. */
    private val recent = ArrayDeque<String>()

    /** Nur unter [lock]. `null`, solange die App noch keinen Speicherort gesetzt hat. */
    private var storage: RollingDiagFile? = null

    /** Letzter Schreibzeitpunkt je Signatur, fuer die Wiederholungsbremse. Nur unter [lock]. */
    private val lastWrittenMs = HashMap<String, Long>()

    /** Unterdrueckte Wiederholungen je Signatur. Nur unter [lock]. */
    private val suppressed = HashMap<String, Int>()

    /**
     * Optionaler Spiegel jeder geschriebenen Zeile, z. B. nach Logcat in
     * Debug-Builds. Bekommt dieselbe fertige Zeile wie die Datei, also ebenso
     * frei von Nutzerdaten. Fehler darin werden verschluckt.
     */
    @Volatile
    var mirror: ((String) -> Unit)? = null

    /**
     * Setzt den Speicherort. Eintraege von davor (sehr frueh im Start) liegen
     * nur im Ringpuffer; sie werden nicht nachgetragen, weil die Reihenfolge
     * in der Datei sonst mit einer frueheren Sitzung verschraenkt waere.
     */
    fun attach(file: RollingDiagFile) {
        lock.withLock { storage = file }
    }

    /**
     * Protokolliert ein Ereignis.
     *
     * @param code ein technischer Zahlencode — HTTP-Status, ein Android-Grund
     *   (`ApplicationExitInfo.REASON_*`) o. ae.
     * @param count ein Zaehler — Anzahl Punkte, Touren, Sekunden Stille.
     * @param error nur dessen Klassenname (und die der Ursachen) wird notiert.
     * @param atMs Zeitpunkt eines *vergangenen* Ereignisses (etwa das Ende des
     *   letzten Prozesses), sonst `null`. Der Eintrag selbst traegt immer den
     *   Zeitpunkt des Schreibens.
     */
    fun log(
        event: DiagEvent,
        code: Int? = null,
        count: Long? = null,
        error: Throwable? = null,
        atMs: Long? = null,
    ) {
        // Protokollieren darf nie selbst zum Fehler werden — auch nicht bei
        // voller Platte oder kaputtem Dateisystem.
        runCatching { lock.withLock { writeLocked(event, code, count, error, atMs) } }
    }

    /**
     * Wie [log], aber fuer den Absturzpfad: Ist die Sperre nicht binnen
     * [timeoutMs] zu haben, wird der Eintrag verworfen statt zu warten.
     *
     * @return ob der Eintrag geschrieben wurde.
     */
    fun tryLog(event: DiagEvent, error: Throwable? = null, timeoutMs: Long = CRASH_LOCK_TIMEOUT_MS): Boolean =
        runCatching {
            if (!lock.tryLock(timeoutMs, TimeUnit.MILLISECONDS)) return@runCatching false
            try {
                writeLocked(event, code = null, count = null, error = error, atMs = null)
                true
            } finally {
                lock.unlock()
            }
        }.getOrDefault(false)

    /**
     * Die juengsten [maxLines] Zeilen dieses Prozesses (aelteste zuerst) —
     * ohne Datei-I/O und ohne auf die Sperre zu warten. `null`, wenn ein
     * anderer Thread sie laenger als [timeoutMs] haelt: Im Absturz lieber
     * ohne Diagnose als gar kein Bericht.
     */
    fun snapshotForCrash(
        maxLines: Int = CRASH_REPORT_MAX_LINES,
        timeoutMs: Long = CRASH_LOCK_TIMEOUT_MS,
    ): List<String>? = runCatching {
        if (!lock.tryLock(timeoutMs, TimeUnit.MILLISECONDS)) return@runCatching null
        try {
            recent.toList().takeLast(maxLines)
        } finally {
            lock.unlock()
        }
    }.getOrNull()

    /**
     * Alle gespeicherten Zeilen, aelteste zuerst — aus der Datei, wenn eine
     * gesetzt ist, sonst aus dem Ringpuffer. Blockiert (Datei-I/O).
     */
    fun readAll(): List<String> = lock.withLock {
        val file = storage
        if (file == null) {
            recent.toList()
        } else {
            runCatching { file.readLines() }.getOrElse { recent.toList() }
        }
    }

    /** Leert Datei und Ringpuffer. */
    fun clear() {
        lock.withLock {
            recent.clear()
            lastWrittenMs.clear()
            suppressed.clear()
            runCatching { storage?.clear() }
        }
    }

    private fun writeLocked(
        event: DiagEvent,
        code: Int?,
        count: Long?,
        error: Throwable?,
        atMs: Long?,
    ) {
        val now = clock()
        val errorName = error?.let { describeErrorClass(it) }
        // Der Zeitpunkt eines vergangenen Ereignisses gehoert zur Signatur:
        // Zwei verschiedene Prozess-Enden mit demselben Grund sind keine
        // Wiederholung, auch wenn sie beim selben Start protokolliert werden.
        val signature = "${event.name}|$code|$errorName|$atMs"

        val last = lastWrittenMs[signature]
        if (last != null && now - last in 0L until repeatWindowMs) {
            suppressed[signature] = (suppressed[signature] ?: 0) + 1
            return
        }
        if (lastWrittenMs.size >= MAX_TRACKED_SIGNATURES) {
            // Schutz gegen unbegrenztes Wachstum; die Bremse faengt danach
            // einfach von vorn an.
            lastWrittenMs.clear()
            suppressed.clear()
        }
        lastWrittenMs[signature] = now
        val repeats = suppressed.remove(signature) ?: 0

        val line = formatDiagLine(
            timestampMs = now,
            event = event,
            code = code,
            count = count,
            errorClass = errorName,
            atMs = atMs,
            repeats = repeats,
        )

        recent.addLast(line)
        while (recent.size > memoryCapacity) recent.removeFirst()

        storage?.let { file -> runCatching { file.append(line) } }
        mirror?.let { sink -> runCatching { sink(line) } }
    }

    companion object {
        /** Zeilen im Ringpuffer des laufenden Prozesses. */
        const val DEFAULT_MEMORY_LINES: Int = 200

        /** Zeitfenster der Wiederholungsbremse. */
        const val DEFAULT_REPEAT_WINDOW_MS: Long = 60_000L

        /** Wie viele Zeilen ein Absturzbericht mitnimmt. */
        const val CRASH_REPORT_MAX_LINES: Int = 40

        /** Wartezeit auf die Sperre im Absturzpfad. */
        const val CRASH_LOCK_TIMEOUT_MS: Long = 100L

        /** Hoechstlaenge einer Zeile — Schutz gegen entgleiste Klassennamen. */
        const val MAX_LINE_CHARS: Int = 240

        /** Hoechstlaenge eines einzelnen Klassennamens. */
        const val MAX_CLASS_NAME_CHARS: Int = 100

        /** Wie viele Glieder der Ursachen-Kette notiert werden. */
        const val MAX_CAUSE_DEPTH: Int = 3

        private const val MAX_TRACKED_SIGNATURES = 256

        /**
         * Die eine Instanz der App. Ein prozessweites Objekt, weil auch
         * `:core` (etwa der Sync-Client) protokollieren soll, ohne dass jede
         * Funktion einen Parameter mehr bekommt. Bis `:app` [attach] aufruft,
         * lebt es nur im Speicher — Unit-Tests schreiben also nirgendwohin.
         */
        val shared: DiagLog = DiagLog()
    }
}

/**
 * Die festen Ereignis-Schluessel. Neue Stellen bekommen hier eine neue
 * Konstante — **kein** generisches „OTHER" mit Text daneben, siehe
 * Klassendoc von [DiagLog].
 */
enum class DiagEvent {
    // App-Lebenszyklus
    APP_START,

    /**
     * Ein frueheres Prozessende laut `ApplicationExitInfo` (ab Android 11):
     * `code` = Grund (`REASON_*`), `n` = Wichtigkeit (`IMPORTANCE_*`),
     * `am` = Zeitpunkt des Endes.
     */
    APP_EXIT_INFO,
    CRASH,

    // Aufzeichnung / GPS
    GPS_START_OK,
    GPS_START_FAILED,
    GPS_PERMISSION_MISSING,
    GPS_PROVIDER_OFF,
    GPS_PROVIDER_ON,
    GPS_RESUBSCRIBE,
    JOURNAL_WRITE_FAILED,
    JOURNAL_CONTINUE_LOST,
    JOURNAL_RECOVERED,
    JOURNAL_RECOVERED_WITH_GAPS,
    JOURNAL_RECOVERY_EMPTY,
    JOURNAL_RECOVERY_SAVE_FAILED,
    JOURNAL_UNREADABLE,

    // Health Connect
    HEALTH_READ_FAILED,
    HEALTH_ACCESS_DENIED,
    HEALTH_CLIENT_FAILED,
    HEALTH_READ_ON_MAIN_THREAD,
    HEALTH_PERMISSION_ON_MAIN_THREAD,

    // Sync
    SYNC_LIST_FAILED,
    SYNC_PUSH_FAILED,
    SYNC_PULL_FAILED,
    SYNC_DELETE_FAILED,
    SYNC_DONE,

    // Daten
    BACKUP_IMPORT_OK,
    BACKUP_IMPORT_FAILED,
    RIDE_FILE_QUARANTINED,
    RIDE_FILE_QUARANTINE_FAILED,

    // Uhr
    WEAR_BAD_SENSOR_PACKET,
    WEAR_BAD_COMMAND,
    WEAR_BRIDGE_UNAVAILABLE,
    WEAR_CAPABILITY_FAILED,
    WEAR_SEND_FAILED,
}

/**
 * Klassenname einer Ausnahme samt Ursachen-Kette, z. B.
 * `de.trailscape.core.HealthSyncException<-java.lang.SecurityException`.
 *
 * Nur Zeichen, die in einem JVM-Klassennamen vorkommen, bleiben stehen —
 * so kann selbst ein exotischer (etwa dynamisch erzeugter) Klassenname keinen
 * Zeilenumbruch oder sonstigen Text einschleusen.
 */
fun describeErrorClass(error: Throwable): String {
    val names = mutableListOf<String>()
    var current: Throwable? = error
    val seen = HashSet<Throwable>()
    while (current != null && names.size < DiagLog.MAX_CAUSE_DEPTH && seen.add(current)) {
        names += sanitizeClassName(current.javaClass.name)
        current = current.cause
    }
    return names.joinToString("<-")
}

private fun sanitizeClassName(name: String): String =
    name.filter { it.isLetterOrDigit() || it == '.' || it == '_' || it == '$' }
        .take(DiagLog.MAX_CLASS_NAME_CHARS)
        .ifEmpty { "?" }

/**
 * Formatiert eine Log-Zeile. Zeitstempel in UTC, sekundengenau — das Log soll
 * sich mit dem Zeitpunkt eines Absturzberichts vergleichen lassen, ohne dass
 * es auf die Zeitzone des Geraets ankommt.
 */
fun formatDiagLine(
    timestampMs: Long,
    event: DiagEvent,
    code: Int? = null,
    count: Long? = null,
    errorClass: String? = null,
    atMs: Long? = null,
    repeats: Int = 0,
): String = buildString {
    append(formatDiagInstant(timestampMs))
    append(' ')
    append(event.name)
    if (code != null) append(" code=").append(code)
    if (count != null) append(" n=").append(count)
    if (atMs != null) append(" am=").append(formatDiagInstant(atMs))
    if (errorClass != null) append(" err=").append(errorClass)
    if (repeats > 0) append(" wdh=").append(repeats)
}.take(DiagLog.MAX_LINE_CHARS)

private fun formatDiagInstant(epochMs: Long): String =
    Instant.ofEpochMilli(epochMs).truncatedTo(ChronoUnit.SECONDS).toString()

/**
 * Zwei rollierende Dateien in [dir]: `diag.log` (aktuell) und `diag.1.log`
 * (Vorgaenger). Waechst die aktuelle ueber [maxBytesPerFile], wird sie zum
 * Vorgaenger und der alte Vorgaenger faellt weg — der Platzbedarf ist damit
 * hart auf `2 × maxBytesPerFile` begrenzt, egal wie lange die App laeuft.
 *
 * Nicht selbst threadsicher: [DiagLog] ruft sie nur unter seiner Sperre.
 * Trailscape laeuft in einem einzigen Prozess, eine Datei-Sperre ueber
 * Prozessgrenzen braucht es daher nicht.
 */
class RollingDiagFile(
    val dir: File,
    private val maxBytesPerFile: Long = DEFAULT_MAX_BYTES_PER_FILE,
) {
    val current: File get() = File(dir, CURRENT_FILE_NAME)
    val previous: File get() = File(dir, PREVIOUS_FILE_NAME)

    fun append(line: String) {
        val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
        if (!dir.isDirectory) dir.mkdirs()
        val cur = current
        if (cur.length() > 0 && cur.length() + bytes.size > maxBytesPerFile) {
            val prev = previous
            prev.delete()
            if (!cur.renameTo(prev)) {
                // Umbenennen gescheitert: dann eben neu anfangen, statt die
                // Obergrenze zu reissen.
                cur.delete()
            }
        }
        FileOutputStream(cur, true).use { it.write(bytes) }
    }

    /** Alle Zeilen, aelteste zuerst. */
    fun readLines(): List<String> = buildList {
        for (file in listOf(previous, current)) {
            if (file.isFile) {
                file.readLines(Charsets.UTF_8).filterTo(this) { it.isNotBlank() }
            }
        }
    }

    fun clear() {
        previous.delete()
        current.delete()
    }

    companion object {
        /** Unterverzeichnis in `filesDir` (siehe Backup-Regeln der App). */
        const val DIR_NAME: String = "diag"
        const val CURRENT_FILE_NAME: String = "diag.log"
        const val PREVIOUS_FILE_NAME: String = "diag.1.log"
        const val DEFAULT_MAX_BYTES_PER_FILE: Long = 64L * 1024L
    }
}

/**
 * Waechter gegen blockierende Aufrufe auf dem Main-Thread (etwa die
 * `runBlocking`-Bruecke in `HealthConnectGateway`).
 *
 * In Debug-Builds ([strict]) wirft er sofort, damit der Fehler beim
 * Entwickeln auffaellt und nicht erst als ANR beim Nutzer. In Release wirft
 * er **nicht** — ein Absturz waere dort schlimmer als ein kurzes Ruckeln —,
 * sondern hinterlaesst einen Eintrag, damit der Fall im naechsten
 * Problembericht auftaucht.
 */
object MainThreadGuard {
    fun check(
        onMainThread: Boolean,
        strict: Boolean,
        event: DiagEvent,
        log: DiagLog = DiagLog.shared,
    ) {
        if (!onMainThread) return
        if (strict) {
            throw IllegalStateException("Blockierender Aufruf auf dem Main-Thread: ${event.name}")
        }
        log.log(event)
    }
}
