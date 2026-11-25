package de.trailscape.app.indoor.background

import de.trailscape.app.indoor.IndoorRideJournalSink
import de.trailscape.app.indoor.MIN_INDOOR_RIDE_S
import de.trailscape.core.KeyValueStore
import de.trailscape.core.Ride
import de.trailscape.core.TrackPoint
import de.trailscape.core.computeStats
import de.trailscape.core.indoor.IndoorMode
import de.trailscape.core.indoor.IndoorSessionState
import de.trailscape.core.indoor.IndoorStartPoints
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * # Sicherung einer laufenden Indoor-Fahrt (docs/indoor/hintergrund.md)
 *
 * Die Sitzung haelt ihre Punkte im Speicher; stirbt der Prozess (Speicherdruck,
 * Absturz, Akku leer), waeren sie weg. Deshalb schreibt die Laufzeit alle
 * [de.trailscape.app.indoor.IndoorRideRuntime.JOURNAL_INTERVAL_MS] (5 s), bei
 * jedem Phasenwechsel (Pause, Weiter) und beim Gang in den Hintergrund die
 * neuen Punkte hier an — zeilenweise, nur anhaengend, je Schreiben ein
 * `fsync`. Ein abgerissener letzter Satz wird beim Lesen uebergangen.
 *
 * Aufbau (eine Zeile je Satz, Felder durch Tabulator getrennt):
 * ```
 * H  {Kopf als JSON: Version, Startzeit, Name der Tour, Modus, Strecke}
 * P  Zeit  Breite  Laenge  Hoehe  Puls  Leistung  Trittfrequenz   (leer = unbekannt)
 * S  Stelle in der Runde (m)  Laufmeter  Fahrzeit (s)  Anstieg (m)
 * ```
 *
 * Gelöscht wird die Datei, sobald die Fahrt gespeichert oder verworfen ist
 * (bzw. zu kurz war). Liegt beim Start des Prozesses noch eine da, ist sie
 * verwaist: [recover] macht daraus eine Tour „… (wiederhergestellt)" und
 * merkt die Stelle, damit die naechste Fahrt auf dieser Strecke dort
 * weitermachen kann („Weiter bei km …").
 *
 * Nicht threadsicher: [begin] auf dem Hauptthread vor dem Start der Schleife,
 * [append] danach nur vom Takt-Thread, [discard] erst nach dem Ende der Schleife.
 */
class IndoorRideJournal(val file: File) : IndoorRideJournalSink {

    /**
     * Kopf der Sicherung — alles, was die Wiederherstellung ohne Oberflaeche braucht.
     *
     * @property rideName fertiger Tourname („Indoor · <Strecke>"), ohne den
     *   Zusatz „(wiederhergestellt)".
     * @property resumeKey Schluessel der gemerkten Stelle dieser Strecke, `null` = nicht merken.
     */
    data class Header(
        val startedAtEpochMs: Long,
        val rideName: String,
        val mode: IndoorMode,
        val resumeKey: String?,
        val routeLengthM: Double,
        val routeIsLoop: Boolean,
    )

    /** Was eine Sicherung beim Lesen hergibt. */
    data class Contents(
        val header: Header,
        val points: List<TrackPoint>,
        /** Letzter Stand: Stelle in der Runde, Laufmeter, Fahrzeit, Anstieg; `null` ohne S-Zeile. */
        val routeDistM: Double?,
        val distanceM: Double?,
        val movingS: Int?,
        val ascentM: Double?,
    )

    /**
     * Legt die Sicherung neu an. Liegt noch eine alte da (eine Fahrt, die
     * ungeplant endete und noch nicht wiederhergestellt ist), wird sie
     * beiseitegelegt ([orphanFiles]) statt ueberschrieben.
     */
    fun begin(header: Header) {
        file.parentFile?.mkdirs()
        setAside()
        FileOutputStream(file, false).use { out ->
            out.write(("H\t" + headerJson(header) + "\n").toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
    }

    override fun append(points: List<TrackPoint>, state: IndoorSessionState) {
        if (!file.exists()) return
        val sb = StringBuilder()
        for (p in points) sb.append(pointLine(p)).append('\n')
        sb.append("S\t").append(state.routeDistM).append('\t').append(state.totals.distanceM)
            .append('\t').append(state.totals.movingS).append('\t').append(state.totals.ascentM).append('\n')
        FileOutputStream(file, true).use { out ->
            out.write(sb.toString().toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
    }

    /** Legt eine vorhandene Sicherung unter neuem Namen beiseite ([orphanFiles]); ohne Datei nichts. */
    @Synchronized
    fun setAside() {
        if (!file.exists()) return
        var aside = File(file.parentFile, ORPHAN_PREFIX + System.currentTimeMillis() + ".txt")
        var n = 1
        while (aside.exists()) aside = File(file.parentFile, ORPHAN_PREFIX + System.currentTimeMillis() + "-" + n++ + ".txt")
        if (!file.renameTo(aside)) {
            runCatching { file.copyTo(aside, overwrite = false) }
            runCatching { file.delete() }
        }
    }

    /** Fahrt gespeichert, verworfen oder zu kurz: Die Sicherung wird nicht mehr gebraucht. */
    fun discard() {
        runCatching { file.delete() }
    }

    fun exists(): Boolean = file.exists()

    /** Liest die Sicherung; `null`, wenn sie fehlt oder keinen lesbaren Kopf hat. */
    fun read(): Contents? = if (file.exists()) parse(runCatching { file.readLines(Charsets.UTF_8) }.getOrNull() ?: return null) else null

    /** Ergebnis von [recover]. */
    sealed interface Recovery {
        /** Als Tour gespeichert. */
        data class Saved(val rideId: String, val name: String) : Recovery

        /** Zu kurz oder ohne Punkte — verworfen. */
        data object TooShort : Recovery

        /** Speichern scheiterte; die Sicherung bleibt fuer den naechsten Versuch liegen. */
        data object Failed : Recovery
    }

    /**
     * Macht aus einer verwaisten Sicherung eine Tour. Nur aufrufen, wenn
     * sicher keine Fahrt in diesem Prozess laeuft (beim Prozessstart).
     *
     * @param nameOf baut den sichtbaren Namen aus dem gemerkten Tournamen
     *   (Zusatz „(wiederhergestellt)").
     * @return `null`, wenn es nichts wiederherzustellen gab.
     */
    fun recover(
        save: (Ride) -> Unit,
        store: KeyValueStore?,
        nameOf: (String) -> String,
        nowEpochMs: Long,
    ): Recovery? {
        if (!file.exists()) return null
        val contents = read()
        if (contents == null) {
            discard()
            return Recovery.TooShort
        }
        val h = contents.header
        // Die Stelle merken, auch wenn die Fahrt zu kurz fuer eine Tour war.
        val key = h.resumeKey
        val at = contents.routeDistM
        if (store != null && key != null && at != null && h.routeLengthM > 0) {
            runCatching {
                val point = IndoorStartPoints.afterStop(at, h.routeLengthM, h.routeIsLoop, nowEpochMs)
                if (point == null) store.remove(key) else store.setString(key, point.toJson())
            }
        }
        val ride = rideOf(contents, nameOf(h.rideName))
        if (ride == null) {
            discard()
            return Recovery.TooShort
        }
        return if (runCatching { save(ride) }.isSuccess) {
            discard()
            Recovery.Saved(ride.id, ride.name)
        } else {
            Recovery.Failed
        }
    }

    companion object {
        /** Dateiname unter `<filesDir>/indoor/` (vom Backup ausgenommen). */
        const val FILE_NAME = "ride-journal.txt"

        private const val VERSION = 1
        private val json = Json { ignoreUnknownKeys = true }

        /** Vorsilbe beiseitegelegter Sicherungen (siehe [begin]). */
        const val ORPHAN_PREFIX = "ride-journal-"

        /** Beiseitegelegte Sicherungen neben [journal] — die Wiederherstellung nimmt sie immer mit. */
        fun orphanFiles(journal: IndoorRideJournal): List<File> =
            journal.file.parentFile?.listFiles { f -> f.isFile && f.name.startsWith(ORPHAN_PREFIX) && f.name.endsWith(".txt") }
                ?.sortedBy { it.name }
                .orEmpty()

        /** Die Sicherung der App. */
        fun inFilesDir(filesDir: File): IndoorRideJournal = IndoorRideJournal(File(File(filesDir, "indoor"), FILE_NAME))

        internal fun headerJson(h: Header): String = buildJsonObject {
            put("v", VERSION)
            put("startedAt", h.startedAtEpochMs)
            put("name", h.rideName)
            put("mode", h.mode.name)
            h.resumeKey?.let { put("resumeKey", it) }
            put("lengthM", h.routeLengthM)
            put("loop", h.routeIsLoop)
        }.toString()

        internal fun pointLine(p: TrackPoint): String = buildString {
            append("P\t").append(p.time ?: "").append('\t').append(p.lat).append('\t').append(p.lon)
            append('\t').append(p.ele ?: "").append('\t').append(p.hr ?: "")
            append('\t').append(p.power ?: "").append('\t').append(p.cad ?: "")
        }

        /** Liest die Zeilen einer Sicherung; abgerissene oder fremde Zeilen fallen heraus. */
        internal fun parse(lines: List<String>): Contents? {
            val first = lines.firstOrNull()?.takeIf { it.startsWith("H\t") } ?: return null
            val header = runCatching { headerOf(json.parseToJsonElement(first.substring(2)) as JsonObject) }.getOrNull() ?: return null
            val points = ArrayList<TrackPoint>()
            var routeDistM: Double? = null
            var distanceM: Double? = null
            var movingS: Int? = null
            var ascentM: Double? = null
            for (line in lines.drop(1)) {
                val f = line.split('\t')
                when (f.firstOrNull()) {
                    "P" -> pointOf(f)?.let(points::add)
                    "S" -> if (f.size >= 5) {
                        val r = f[1].toDoubleOrNull()
                        val d = f[2].toDoubleOrNull()
                        val m = f[3].toIntOrNull()
                        val a = f[4].toDoubleOrNull()
                        if (r != null && d != null && m != null && a != null) {
                            routeDistM = r
                            distanceM = d
                            movingS = m
                            ascentM = a
                        }
                    }
                }
            }
            return Contents(header, points, routeDistM, distanceM, movingS, ascentM)
        }

        private fun headerOf(o: JsonObject): Header? {
            val started = o["startedAt"]?.jsonPrimitive?.longOrNull ?: return null
            val name = o["name"]?.jsonPrimitive?.content ?: return null
            val mode = o["mode"]?.jsonPrimitive?.content?.let { m -> IndoorMode.entries.firstOrNull { it.name == m } } ?: IndoorMode.SIM
            return Header(
                startedAtEpochMs = started,
                rideName = name,
                mode = mode,
                resumeKey = o["resumeKey"]?.jsonPrimitive?.content,
                routeLengthM = o["lengthM"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                routeIsLoop = o["loop"]?.jsonPrimitive?.booleanOrNull ?: false,
            )
        }

        private fun pointOf(f: List<String>): TrackPoint? {
            if (f.size < 8) return null
            val lat = f[2].toDoubleOrNull() ?: return null
            val lon = f[3].toDoubleOrNull() ?: return null
            if (!lat.isFinite() || !lon.isFinite()) return null
            return TrackPoint(
                lat = lat,
                lon = lon,
                ele = f[4].toDoubleOrNull(),
                time = f[1].toLongOrNull(),
                hr = f[5].toIntOrNull(),
                power = f[6].toIntOrNull(),
                cad = f[7].toIntOrNull(),
            )
        }

        /**
         * Die Tour aus einer Sicherung — dieselben Regeln wie beim regulaeren
         * Speichern (`indoorRideToRide`): mindestens [MIN_INDOOR_RIDE_S] Fahrzeit
         * und zwei Punkte. Fahrzeit, Strecke und Anstieg aus dem letzten
         * Stand der Sitzung, sonst aus den Punkten.
         */
        internal fun rideOf(c: Contents, name: String, id: String = UUID.randomUUID().toString()): Ride? {
            if (c.points.size < 2) return null
            val fromPoints = computeStats(c.points)
            val movingS = c.movingS ?: fromPoints.movingTimeS ?: 0
            if (movingS < MIN_INDOOR_RIDE_S) return null
            val km = c.distanceM?.let { it / 1000.0 } ?: fromPoints.distanceKm
            val stats = fromPoints.copy(
                distanceKm = km,
                ascentM = c.ascentM ?: fromPoints.ascentM,
                movingTimeS = movingS,
                avgSpeedKmh = if (movingS > 0) km / (movingS / 3600.0) else fromPoints.avgSpeedKmh,
            )
            return Ride(
                id = id,
                name = name,
                createdAt = c.header.startedAtEpochMs,
                stats = stats,
                points = c.points,
                planned = false,
                indoor = true,
            )
        }
    }
}
