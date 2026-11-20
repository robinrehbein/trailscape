package de.trailscape.core

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * „Neue Wege": Welche **Wege** ist die Nutzerin schon gefahren — feiner als
 * die Entdeckt-Kacheln (`ExplorerTiles.kt`, etwa 1,5 km), die schon nach einer
 * einzigen Durchfahrt als bekannt gelten, obwohl in der Kachel noch zehn
 * andere Feldwege liegen.
 *
 * ## Das Raster
 * Die Welt wird in Zellen von [RIDDEN_WAY_CELL_M] (20 m) Kantenlaenge
 * geteilt: Zeilen nach der Breite (gleich hoch ueberall), in jeder Zeile
 * Spalten nach der Laenge, deren Breite mit dem Kosinus der Zeilenmitte auf
 * dieselben 20 m kommt. Anders als bei Slippy-Map-Kacheln bleibt die Zelle
 * damit in Hamburg wie in Rom 20 m gross — die Toleranz in Metern bedeutet
 * ueberall dasselbe. (Am Datumsgrenzmeridian wird nicht umgebrochen; dort
 * faehrt niemand Rad.)
 *
 * ## Der Index
 * Aus jeder gefahrenen Tour werden die Zellen ihrer Punkte gesammelt —
 * ausgeduennt ([RIDDEN_WAY_MIN_SPACING_M]) und auf [RIDDEN_WAY_SAMPLE_STEP_M]
 * interpoliert, damit duenne Aufzeichnungen keine Zelle ueberspringen. Der
 * [RiddenWaysIndex] haelt die Vereinigung als **sortiertes `LongArray`**
 * (Spalte und Zeile in einem `Long` gepackt) und sucht binaer: 8 Byte je
 * Zelle, kein Boxing, keine Hash-Tabelle mit halbleeren Eimern.
 *
 * ## Bekannt oder neu
 * Ein Routenpunkt gilt als bekannt ([RiddenWaysIndex.isKnown]), wenn eine
 * gefahrene Zelle **als Rechteck** hoechstens [RIDDEN_WAY_TOLERANCE_M] (25 m)
 * von ihm entfernt liegt. Damit gilt garantiert: Ein gefahrener Punkt bis
 * 25 m daneben macht den Routenpunkt bekannt (GPS-Rauschen, die andere
 * Fahrbahnseite auf dem Rueckweg); einer weiter als 25 m + Zelldiagonale
 * (≈ 53 m) nie. Eine Parallelstrasse 60 m daneben ist also immer neu.
 *
 * ## GPS-Spruenge
 * Wie in `ExplorerTiles.kt`: Ab einer Segmentlaenge von
 * [RIDDEN_WAY_MAX_SEGMENT_M] wird nicht interpoliert, nur der Endpunkt
 * zaehlt. Die Schwelle ist viel kleiner als die 20 km der Kacheln, weil eine
 * Luftlinie auf Wegebene fast nie auf einem Weg liegt: Bei einer Funkluecke
 * von einem Kilometer waere jede gerade Linie eine erfundene Strecke. Wer
 * dagegen mit 60 km/h und 1-s-Punkten bergab faehrt, kommt auf 17 m — weit
 * darunter.
 *
 * ## Speicher und Laufzeit (10 000 km Bestand)
 * Eine Strecke kostet etwa 65 Zellen je km. 10 000 km gefahren, davon in der
 * Praxis vielleicht ein Drittel auf eigenen Wegen, ergibt 200 000 bis
 * 650 000 Zellen: 1,6 bis 5,2 MB als `LongArray`. Der Cache je Tour
 * ([writeRiddenWaysCache]) speichert die Zellen in Fahrtreihenfolge als
 * Differenzen (meist ±1) in Varints, rund 2 Byte je Zelle, also etwa 1,3 MB
 * Datei fuer den ganzen Bestand. Die Abfrage einer 100-km-Runde sind
 * 10 000 Stuetzstellen mit je hoechstens ~20 binaeren Suchen.
 *
 * Bewusst ohne Android-Import — getestet in `RiddenWaysTest.kt`.
 */

/** Kantenlaenge einer Zelle in Metern. */
const val RIDDEN_WAY_CELL_M: Double = 20.0

/** Bis zu diesem Abstand (zum Zellrechteck) gilt ein Routenpunkt als schon gefahren. */
const val RIDDEN_WAY_TOLERANCE_M: Double = 25.0

/** Hoechstabstand der Stuetzstellen beim Interpolieren (Tracks und Routen). */
const val RIDDEN_WAY_SAMPLE_STEP_M: Double = 10.0

/**
 * Ausduennen: Ein Trackpunkt, der naeher als das am zuletzt behaltenen liegt,
 * faellt weg — das sind Ampelstopps und Pausen, deren Rauschen sonst eine
 * Wolke aus Zellen erzeugt.
 */
const val RIDDEN_WAY_MIN_SPACING_M: Double = 5.0

/** Ab dieser Segmentlaenge wird nicht interpoliert (GPS-Sprung, siehe Datei-KDoc). */
const val RIDDEN_WAY_MAX_SEGMENT_M: Double = 500.0

private const val RW_METERS_PER_DEG = 111_320.0

/** Ueber diese Breite hinaus wird geklemmt (der Kosinus der Zeile darf nie 0 werden). */
private const val RW_MAX_LATITUDE = 85.0

private fun clampLat(lat: Double): Double = lat.coerceIn(-RW_MAX_LATITUDE, RW_MAX_LATITUDE)

/** Nord-Sued-Lage in Metern (gleichabstaendig in der Breite). */
private fun northMeters(lat: Double): Double = clampLat(lat) * RW_METERS_PER_DEG

private fun rowAt(lat: Double): Int = floor(northMeters(lat) / RIDDEN_WAY_CELL_M).toInt()

/** Meter je Laengengrad in der Mitte der Zeile [row]. */
private fun eastMetersPerDeg(row: Int): Double {
    val centerLat = (row + 0.5) * RIDDEN_WAY_CELL_M / RW_METERS_PER_DEG
    return RW_METERS_PER_DEG * cos(clampLat(centerLat) * (PI / 180))
}

/** Ost-West-Lage in Metern, gemessen im Massstab der Zeile [row]. */
private fun eastMeters(lon: Double, row: Int): Double = lon.coerceIn(-180.0, 180.0) * eastMetersPerDeg(row)

private fun packCell(col: Int, row: Int): Long = (col.toLong() shl 32) or (row.toLong() and 0xffffffffL)

private fun cellCol(cell: Long): Int = (cell shr 32).toInt()

private fun cellRow(cell: Long): Int = cell.toInt()

/** Die Zelle, in der `(lat, lon)` liegt. */
internal fun riddenWayCellAt(lat: Double, lon: Double): Long {
    val row = rowAt(lat)
    return packCell(floor(eastMeters(lon, row) / RIDDEN_WAY_CELL_M).toInt(), row)
}

/** Lokale Naeherung in Metern (aequirektangular) — fuer kurze Abstaende genau genug. */
private fun metersBetween(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
    val dLat = (bLat - aLat) * RW_METERS_PER_DEG
    val dLon = (bLon - aLon) * RW_METERS_PER_DEG * cos((aLat + bLat) / 2 * (PI / 180))
    return hypot(dLat, dLon)
}

/**
 * Die gefahrenen Wege als sortierte, duplikatfreie Zellmenge.
 *
 * Unveraenderlich; das Feld wird nie nach aussen gereicht. [EMPTY] fuer
 * einen Bestand ohne gefahrene Tour.
 */
class RiddenWaysIndex private constructor(private val cells: LongArray) {

    /** Zahl der Zellen (je ~20 m × 20 m). */
    val size: Int get() = cells.size

    val isEmpty: Boolean get() = cells.isEmpty()

    /** Ungefaehrer Speicherbedarf des Index in Byte (8 je Zelle). */
    val approxBytes: Long get() = cells.size * 8L

    internal operator fun contains(cell: Long): Boolean =
        cells.isNotEmpty() && java.util.Arrays.binarySearch(cells, cell) >= 0

    /**
     * Ob eine gefahrene Zelle hoechstens [RIDDEN_WAY_TOLERANCE_M] von
     * `(lat, lon)` entfernt liegt (Abstand Punkt–Zellrechteck). Die eigene
     * Zelle wird zuerst geprueft — auf bekannten Wegen der haeufige Treffer.
     */
    fun isKnown(lat: Double, lon: Double): Boolean {
        if (cells.isEmpty()) return false
        val ownRow = rowAt(lat)
        val ownCol = floor(eastMeters(lon, ownRow) / RIDDEN_WAY_CELL_M).toInt()
        if (packCell(ownCol, ownRow) in this) return true

        val y = northMeters(lat)
        val reach = ceil(RIDDEN_WAY_TOLERANCE_M / RIDDEN_WAY_CELL_M).toInt()
        for (row in ownRow - reach..ownRow + reach) {
            val bandSouth = row * RIDDEN_WAY_CELL_M
            val bandNorth = bandSouth + RIDDEN_WAY_CELL_M
            val dy = when {
                y < bandSouth -> bandSouth - y
                y > bandNorth -> y - bandNorth
                else -> 0.0
            }
            if (dy > RIDDEN_WAY_TOLERANCE_M) continue
            val halfWidth = sqrt(RIDDEN_WAY_TOLERANCE_M * RIDDEN_WAY_TOLERANCE_M - dy * dy)
            val x = eastMeters(lon, row)
            val first = floor((x - halfWidth) / RIDDEN_WAY_CELL_M).toInt()
            val last = floor((x + halfWidth) / RIDDEN_WAY_CELL_M).toInt()
            for (col in first..last) {
                if (row == ownRow && col == ownCol) continue
                if (packCell(col, row) in this) return true
            }
        }
        return false
    }

    /**
     * Vereinigung mit weiteren Zellen (beliebige Reihenfolge, Dubletten
     * erlaubt) — fuer das inkrementelle Nachziehen neuer Touren, ohne den
     * Bestand neu zu sortieren: ein linearer Merge.
     */
    fun plus(more: LongArray): RiddenWaysIndex {
        if (more.isEmpty()) return this
        val add = sortedDistinct(more.copyOf())
        val out = LongArray(cells.size + add.size)
        var i = 0
        var j = 0
        var n = 0
        while (i < cells.size || j < add.size) {
            val next = when {
                j >= add.size -> cells[i++]
                i >= cells.size -> add[j++]
                cells[i] < add[j] -> cells[i++]
                cells[i] > add[j] -> add[j++]
                else -> { j++; cells[i++] }
            }
            out[n++] = next
        }
        return RiddenWaysIndex(if (n == out.size) out else out.copyOf(n))
    }

    companion object {
        val EMPTY: RiddenWaysIndex = RiddenWaysIndex(LongArray(0))

        /** Index aus beliebigen Zellen (Reihenfolge egal, Dubletten erlaubt; [cells] wird umsortiert). */
        fun of(cells: LongArray): RiddenWaysIndex =
            if (cells.isEmpty()) EMPTY else RiddenWaysIndex(sortedDistinct(cells))

        /** Index direkt aus Tracks — fuer Tests und kleine Bestaende ohne Cache. */
        fun fromTracks(tracks: List<List<TrackPoint>>): RiddenWaysIndex =
            of(concatCells(tracks.map(::riddenWayCells)))
    }
}

/**
 * Haengt [parts] in einem einzigen Feld aneinander — einmal kopiert. Ein
 * `all += part` je Tour kopierte den wachsenden Bestand bei jeder Tour neu,
 * bei vielen Touren also quadratisch. Liefert immer ein neues Feld (auch bei
 * nur einem Teil): [RiddenWaysIndex.of] sortiert an Ort und Stelle, und die
 * Teile koennen die Zellen eines Cache-Eintrags in Fahrtreihenfolge sein.
 */
internal fun concatCells(parts: List<LongArray>): LongArray {
    var total = 0
    for (p in parts) total += p.size
    val all = LongArray(total)
    var at = 0
    for (p in parts) {
        p.copyInto(all, at)
        at += p.size
    }
    return all
}

/** Sortiert [cells] an Ort und Stelle und gibt die duplikatfreie Kopie zurueck. */
private fun sortedDistinct(cells: LongArray): LongArray {
    java.util.Arrays.sort(cells)
    var n = 0
    for (i in cells.indices) {
        if (n == 0 || cells[i] != cells[n - 1]) cells[n++] = cells[i]
    }
    return if (n == cells.size) cells else cells.copyOf(n)
}

/**
 * Die Zellen eines gefahrenen Tracks — **in Fahrtreihenfolge**, jede nur beim
 * ersten Auftreten (Hin- und Rueckweg derselben Strasse kosten nichts
 * doppelt). Die Reihenfolge macht den Cache klein: Aufeinanderfolgende Zellen
 * liegen fast immer nebeneinander (siehe [writeRiddenWaysCache]).
 *
 * Ausduennen, Interpolation und GPS-Spruenge wie im Datei-KDoc.
 */
fun riddenWayCells(points: List<TrackPoint>): LongArray {
    if (points.isEmpty()) return LongArray(0)
    var buffer = LongArray(256)
    var size = 0
    val seen = HashSet<Long>()
    var last = Long.MIN_VALUE
    fun add(cell: Long) {
        if (cell == last) return
        last = cell
        if (!seen.add(cell)) return
        if (size == buffer.size) buffer = buffer.copyOf(size * 2)
        buffer[size++] = cell
    }

    var prev = points[0]
    add(riddenWayCellAt(prev.lat, prev.lon))
    for (i in 1 until points.size) {
        val p = points[i]
        val d = metersBetween(prev.lat, prev.lon, p.lat, p.lon)
        // Ausduennen — der letzte Punkt zaehlt trotzdem, sonst fehlte das Ende.
        if (d < RIDDEN_WAY_MIN_SPACING_M && i < points.size - 1) continue
        if (d > RIDDEN_WAY_MAX_SEGMENT_M) {
            add(riddenWayCellAt(p.lat, p.lon))
        } else {
            val steps = ceil(d / RIDDEN_WAY_SAMPLE_STEP_M).toInt().coerceAtLeast(1)
            for (step in 1..steps) {
                val t = step.toDouble() / steps
                add(riddenWayCellAt(prev.lat + (p.lat - prev.lat) * t, prev.lon + (p.lon - prev.lon) * t))
            }
        }
        prev = p
    }
    return buffer.copyOf(size)
}

/** Strecke einer Route und wie viel davon ueber noch nie gefahrene Wege fuehrt. */
data class NewWayDistance(val totalKm: Double, val newKm: Double) {
    /** Anteil neuer Strecke (0…1); 0 ohne Strecke. */
    val share: Double get() = if (totalKm > 0) (newKm / totalKm).coerceIn(0.0, 1.0) else 0.0
}

/**
 * Wie viele Kilometer von [points] (einer geplanten Route) ueber Wege fuehren,
 * die in [index] nicht vorkommen.
 *
 * Jedes Segment wird in Stuecke von hoechstens [RIDDEN_WAY_SAMPLE_STEP_M]
 * geteilt; ein Stueck zaehlt als neu, wenn seine Mitte nicht
 * [RiddenWaysIndex.isKnown] ist. Anders als beim gefahrenen Track wird hier
 * jedes Segment interpoliert, auch ein langes: Eine Route aus dem Routing ist
 * echte Geometrie, eine lange gerade Strasse hat eben nur zwei Knoten.
 *
 * Leerer Index: alles neu.
 */
fun newWayDistance(points: List<TrackPoint>, index: RiddenWaysIndex): NewWayDistance {
    if (points.size < 2) return NewWayDistance(0.0, 0.0)
    var total = 0.0
    var fresh = 0.0
    for (i in 1 until points.size) {
        val a = points[i - 1]
        val b = points[i]
        val d = metersBetween(a.lat, a.lon, b.lat, b.lon)
        if (!d.isFinite() || d <= 0.0) continue
        total += d
        if (index.isEmpty) {
            fresh += d
            continue
        }
        val steps = ceil(d / RIDDEN_WAY_SAMPLE_STEP_M).toInt().coerceAtLeast(1)
        val piece = d / steps
        for (k in 0 until steps) {
            val t = (k + 0.5) / steps
            if (!index.isKnown(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)) fresh += piece
        }
    }
    return NewWayDistance(totalKm = total / 1000.0, newKm = fresh / 1000.0)
}

/** Anteil der Routen-km ueber noch nie gefahrene Wege (0…1) — Kurzform von [newWayDistance]. */
fun newDistanceShare(points: List<TrackPoint>, index: RiddenWaysIndex): Double =
    newWayDistance(points, index).share

// ------------------------------------------------------------------ Cache

/**
 * Zwischenstand einer Tour im Wege-Cache; Fingerabdruck [updatedAt] +
 * [pointCount] wie [StoredExplorerTiles]. [cells] in Fahrtreihenfolge wie aus
 * [riddenWayCells].
 */
class StoredRiddenWays(val updatedAt: Long, val pointCount: Int, val cells: LongArray) {
    override fun equals(other: Any?): Boolean =
        this === other || (
            other is StoredRiddenWays &&
                updatedAt == other.updatedAt &&
                pointCount == other.pointCount &&
                cells.contentEquals(other.cells)
            )

    override fun hashCode(): Int = (updatedAt.hashCode() * 31 + pointCount) * 31 + cells.contentHashCode()

    override fun toString(): String = "StoredRiddenWays(updatedAt=$updatedAt, pointCount=$pointCount, cells=${cells.size})"
}

/** Speicher fuer die Zellen je Tour — Pendant zu [ExplorerTilesStore]. */
interface RiddenWaysStore {
    fun get(id: String): StoredRiddenWays?
    fun put(id: String, entry: StoredRiddenWays)

    /** Wirft alle Eintraege weg, deren ID nicht in [ids] liegt. */
    fun retainAll(ids: Set<String>)

    /** Persistiert den Stand, falls die Implementierung persistiert. */
    fun flush()
}

/** Einfache Map-Implementierung fuer Tests. */
class InMemoryRiddenWaysStore : RiddenWaysStore {
    val entries = LinkedHashMap<String, StoredRiddenWays>()
    var flushes = 0
        private set

    override fun get(id: String): StoredRiddenWays? = entries[id]
    override fun put(id: String, entry: StoredRiddenWays) {
        entries[id] = entry
    }
    override fun retainAll(ids: Set<String>) {
        entries.keys.retainAll(ids)
    }
    override fun flush() {
        flushes++
    }
}

/**
 * Ein gerechneter Index samt den Fingerabdruecken der Touren, aus denen er
 * besteht — damit [updateRiddenWays] beim naechsten Mal entscheiden kann, ob
 * es nur neue Touren dazumischt oder neu aufbaut.
 *
 * [failed] haelt die Touren, die sich beim Rechnen nicht laden liessen, mit
 * ihrem Fingerabdruck. Sie gehoeren zum Stand dazu: Solange sich ihr
 * Fingerabdruck nicht aendert, gilt der Snapshot als aktuell und eine
 * kaputte Tour loest nicht bei jeder Suche einen Lauf ueber den ganzen Cache
 * aus. Beide Maps sind disjunkt.
 */
class RiddenWaysSnapshot internal constructor(
    val index: RiddenWaysIndex,
    internal val fingerprints: Map<String, Pair<Long, Int>>,
    internal val failed: Map<String, Pair<Long, Int>> = emptyMap(),
) {
    /** Zahl der Touren im Index. */
    val rideCount: Int get() = fingerprints.size

    /** Zahl der Touren, die sich nicht laden liessen und erst nach einer Aenderung erneut versucht werden. */
    val failedCount: Int get() = failed.size

    /** Ob dieser Stand genau die Touren [current] abdeckt (im Index oder als nicht ladbar vermerkt). */
    internal fun covers(current: Map<String, Pair<Long, Int>>): Boolean =
        fingerprints.size + failed.size == current.size &&
            current.all { (id, fp) -> fingerprints[id] == fp || failed[id] == fp }
}

/**
 * Der Wege-Index ueber alle **gefahrenen** Touren, inkrementell:
 *
 *  * Hat sich gegenueber [previous] nichts geaendert, kommt [previous]
 *    unveraendert zurueck — ohne den [store] auch nur anzufassen.
 *  * Sind nur Touren **dazugekommen** (alle bisherigen unveraendert da),
 *    werden nur deren Zellen geholt (aus dem Cache oder per [loadRide]) und
 *    in den bestehenden Index gemischt.
 *  * Sonst (geloescht, geaendert, zur Planung geworden, oder kein [previous])
 *    wird aus dem [store] neu zusammengesetzt; geladen werden auch dann nur
 *    Touren ohne gueltigen Cache-Eintrag. Eine Zelle laesst sich nicht
 *    „abziehen", weil sie mehreren Touren gehoeren kann — der Neuaufbau aus
 *    dem Cache ist aber nur Sortieren, kein Laden.
 *
 * Liefert [loadRide] `null` (Datei weg, Quarantaene), wird die Tour
 * uebersprungen und samt Fingerabdruck im Snapshot vermerkt
 * ([RiddenWaysSnapshot.failed]). Erneut versucht wird sie erst, wenn sich ihr
 * Fingerabdruck aendert (oder ohne [previous], etwa nach einem Neustart) —
 * eine dauerhaft kaputte Datei kostet also nicht jede Suche einen Lauf ueber
 * den Cache. Am Ende eines Laufs mit Store-Zugriff raeumt
 * [RiddenWaysStore.retainAll] ab und [RiddenWaysStore.flush] schreibt fest.
 *
 * ## Abbruch
 * [isCancelled] wird vor jeder Tour gefragt. Meldet es `true`, wirft der
 * Aufruf eine [CancellationException] — ohne `retainAll`, ohne `flush` und
 * ohne Snapshot: Der Aufrufer behaelt seinen alten Stand. Im [store] liegen
 * dann hoechstens die Eintraege der schon gerechneten Touren, jeder fuer sich
 * vollstaendig; den Store freizugeben ([RiddenWaysStore.flush]) ist Sache des
 * Aufrufers, wie nach jedem anderen Fehler.
 */
fun updateRiddenWays(
    previous: RiddenWaysSnapshot?,
    summaries: List<RideInfo>,
    store: RiddenWaysStore,
    loadRide: (String) -> Ride?,
    isCancelled: () -> Boolean = { false },
): RiddenWaysSnapshot {
    val ridden = riddenRides(summaries)
    val current = LinkedHashMap<String, Pair<Long, Int>>(ridden.size * 2)
    for (r in ridden) current[r.id] = r.updatedAt to r.pointCount
    if (previous != null && previous.covers(current)) return previous

    val onlyAdditions = previous != null &&
        previous.fingerprints.all { (id, fp) -> current[id] == fp }

    val failed = LinkedHashMap<String, Pair<Long, Int>>()

    fun cellsOf(ride: RideInfo): LongArray? {
        if (isCancelled()) throw CancellationException("Wege-Index abgebrochen")
        val fingerprint = ride.updatedAt to ride.pointCount
        // Schon beim letzten Mal nicht ladbar, seitdem unveraendert: nicht
        // erneut im Store nachsehen (der ihn dafuer ganz lesen muesste) und
        // nicht erneut laden.
        if (previous != null && previous.failed[ride.id] == fingerprint) {
            failed[ride.id] = fingerprint
            return null
        }
        val cached = store.get(ride.id)
        if (cached != null && cached.updatedAt == ride.updatedAt && cached.pointCount == ride.pointCount) {
            return cached.cells
        }
        val loaded = loadRide(ride.id)
        if (loaded == null) {
            failed[ride.id] = fingerprint
            return null
        }
        val cells = riddenWayCells(loaded.points)
        store.put(ride.id, StoredRiddenWays(ride.updatedAt, ride.pointCount, cells))
        return cells
    }

    val included = LinkedHashMap<String, Pair<Long, Int>>()
    val parts = ArrayList<LongArray>()
    val index: RiddenWaysIndex
    // Beide Zweige sammeln die Zellen je Tour und kopieren sie einmal
    // ([concatCells]) — nie den wachsenden Bestand je Tour neu.
    if (onlyAdditions && previous != null) {
        included.putAll(previous.fingerprints)
        for (ride in ridden) {
            if (ride.id in previous.fingerprints) continue
            val cells = cellsOf(ride) ?: continue
            parts += cells
            included[ride.id] = ride.updatedAt to ride.pointCount
        }
        index = previous.index.plus(concatCells(parts))
    } else {
        for (ride in ridden) {
            val cells = cellsOf(ride) ?: continue
            parts += cells
            included[ride.id] = ride.updatedAt to ride.pointCount
        }
        index = RiddenWaysIndex.of(concatCells(parts))
    }

    store.retainAll(current.keys)
    store.flush()
    return RiddenWaysSnapshot(index, included, failed)
}

/** Formatversion des Wege-Caches. Hochzaehlen, sobald sich Raster oder Kodierung aendern. */
const val RIDDEN_WAYS_CACHE_VERSION: Int = 1

/** Dateikennung „TSRW" — eine fremde Datei wird sofort als leer erkannt. */
private const val RIDDEN_WAYS_MAGIC: Int = 0x54535257

private fun zigzag(v: Int): Int = (v shl 1) xor (v shr 31)

private fun unzigzag(v: Int): Int = (v ushr 1) xor -(v and 1)

private fun DataOutputStream.writeVarInt(value: Int) {
    var v = value
    while (v and 0x7f.inv() != 0) {
        writeByte((v and 0x7f) or 0x80)
        v = v ushr 7
    }
    writeByte(v)
}

private fun DataInputStream.readVarInt(): Int {
    var result = 0
    var shift = 0
    while (shift < 35) {
        val b = readUnsignedByte()
        result = result or ((b and 0x7f) shl shift)
        if (b and 0x80 == 0) return result
        shift += 7
    }
    throw IllegalStateException("Varint zu lang")
}

/**
 * Schreibt [entries] binaer auf [out]: Kennung, Version, Eintragszahl; je
 * Eintrag ID, `updatedAt`, `pointCount`, Zellzahl und die Zellen als
 * Differenzen von Spalte und Zeile zur vorigen Zelle (Zickzack-Varint, die
 * erste gegen 0/0). Weil [riddenWayCells] in Fahrtreihenfolge liefert, sind
 * die Differenzen fast immer −1, 0 oder 1 — ein Byte je Achse. Schliesst
 * [out] nicht.
 */
fun writeRiddenWaysCache(entries: Map<String, StoredRiddenWays>, out: OutputStream) {
    val data = DataOutputStream(out)
    data.writeInt(RIDDEN_WAYS_MAGIC)
    data.writeInt(RIDDEN_WAYS_CACHE_VERSION)
    data.writeInt(entries.size)
    for ((id, e) in entries) {
        data.writeUTF(id)
        data.writeLong(e.updatedAt)
        data.writeInt(e.pointCount)
        data.writeInt(e.cells.size)
        var col = 0
        var row = 0
        for (cell in e.cells) {
            val c = cellCol(cell)
            val r = cellRow(cell)
            data.writeVarInt(zigzag(c - col))
            data.writeVarInt(zigzag(r - row))
            col = c
            row = r
        }
    }
    data.flush()
}

/**
 * Liest einen Cache von [input]. `null` bei fremder Kennung, anderer
 * [RIDDEN_WAYS_CACHE_VERSION] oder kaputter Datei — der Aufrufer behandelt
 * das als leeren Cache und baut neu auf. Schliesst [input] nicht.
 */
fun readRiddenWaysCache(input: InputStream): MutableMap<String, StoredRiddenWays>? = try {
    val data = DataInputStream(input)
    if (data.readInt() != RIDDEN_WAYS_MAGIC || data.readInt() != RIDDEN_WAYS_CACHE_VERSION) {
        null
    } else {
        val count = data.readInt()
        require(count >= 0)
        val result = LinkedHashMap<String, StoredRiddenWays>()
        repeat(count) {
            val id = data.readUTF()
            val updatedAt = data.readLong()
            val pointCount = data.readInt()
            val size = data.readInt()
            require(size >= 0)
            // Wachsend statt vorab `LongArray(size)`: Eine kaputte Groesse
            // darf keinen riesigen Speicherblock anfordern.
            var cells = LongArray(minOf(size, 4096))
            var col = 0
            var row = 0
            for (i in 0 until size) {
                col += unzigzag(data.readVarInt())
                row += unzigzag(data.readVarInt())
                if (i == cells.size) cells = cells.copyOf(minOf(size, cells.size * 2))
                cells[i] = packCell(col, row)
            }
            result[id] = StoredRiddenWays(updatedAt, pointCount, cells)
        }
        result
    }
} catch (_: EOFException) {
    null
} catch (_: Exception) {
    null
}
