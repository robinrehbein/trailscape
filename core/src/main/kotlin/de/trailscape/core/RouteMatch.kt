package de.trailscape.core

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import java.io.InputStream
import java.io.OutputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.tan

/**
 * Wiedererkennen von Runden: Faehrt jemand seine Hausrunde zum x-ten Mal, soll
 * die App sagen koennen „zweitschnellste von 5, 3 Minuten schneller als beim
 * letzten Mal" — der direkteste Beleg fuer wachsende Fitness.
 *
 * Die Tourenliste kennt nur Zusammenfassungen (keine Punkte). Jede Tour
 * bekommt deshalb eine kompakte [RouteSignature]: die Menge der
 * ~200-m-Rasterzellen, durch die der Track fuehrte, plus Laenge, Start-
 * und Endzelle. Zwei Touren sind dieselbe Strecke ([sameRoute]), wenn Laenge
 * und Zellmenge zusammenpassen — ohne je wieder Punkte zu laden. Aufbau wie
 * `ExplorerTiles.kt`: abgeleiteter Wert je Tour, [StoredRouteSignature] mit
 * Fingerabdruck, Store-Schnittstelle, inkrementelles [collectRouteSignatures].
 *
 * Zellen liegen als sortiertes, duplikatfreies `LongArray` vor (kein Boxing):
 * Bei tausenden Touren im Speicher zaehlt jedes Byte je Zelle, und der
 * Vergleich zweier Signaturen ist ein linearer Merge ohne Hilfsmengen.
 *
 * Rein Kotlin: keine Android-Abhaengigkeit, keine Uhr, keine Zeitzone, keine
 * ausformulierten Saetze — nur Daten.
 */

/**
 * Zoomstufe des Zellrasters. Stufe 17 misst am Aequator ~300 m, bei 48° N
 * rund 200 m (40 075 km · cos 48° / 2^17): grob genug, dass GPS-Rauschen von
 * wenigen Metern kaum Zellen kippt und eine Tour nur ~6 Zellen je km kostet
 * (Stufe 18 waere doppelt so viel Speicher bei tausenden Touren), fein genug,
 * dass eine Parallelstrasse im Abstand einiger Hundert Meter eine andere
 * Zelle trifft. Laengenpruefung und Jaccard-Schwelle fangen den Rest ab.
 */
const val ROUTE_CELL_ZOOM: Int = 17

private const val ROUTE_CELL_COUNT: Int = 1 shl ROUTE_CELL_ZOOM

/** Grenzwert der Mercator-Projektion (wie in `ExplorerTiles.kt`). */
private const val ROUTE_MAX_LATITUDE = 85.05112878

/**
 * Hoechstabstand der Stuetzstellen beim Abtasten in Metern. Unter der halben
 * Zellkante, damit duenne GPS-Punkte (Tunnel, Abfahrt, Stromsparmodus) keine
 * durchfahrene Zelle ueberspringen.
 */
const val ROUTE_SAMPLE_STEP_M: Double = 50.0

/**
 * Ab dieser Segmentlaenge wird nicht interpoliert (GPS-Sprung, zusammengefuegte
 * Tour): nur die Endzelle zaehlt, und die Strecke geht auch nicht in die
 * Laenge ein — sonst erzeugte ein Ausreisser Phantomzellen.
 */
private const val ROUTE_MAX_SEGMENT_M = 20_000.0

/** Kuerzere Tracks (km) bekommen keine Signatur — Kurzstrecken sind keine „Runde". */
const val ROUTE_MIN_DISTANCE_KM: Double = 2.0

/** Erlaubte relative Laengenabweichung zweier gleicher Strecken (8 %). */
const val ROUTE_MAX_DISTANCE_DEVIATION: Double = 0.08

/** Mindest-Jaccard-Aehnlichkeit der Zellmengen fuer „gleiche Strecke". */
const val ROUTE_MIN_JACCARD: Double = 0.75

private const val ROUTE_METERS_PER_DEG = 111_320.0

/**
 * Kompakte Form eines gefahrenen Tracks.
 *
 * [cells] ist **aufsteigend sortiert und ohne Duplikate** (Vorbedingung von
 * [sameRoute], das die Mengen per Merge schneidet). Arrays vergleicht Kotlin
 * sonst nach Identitaet; deshalb stehen `equals`/`hashCode` hier von Hand
 * (Inhalt), sonst waeren zwei gleiche Signaturen nie gleich.
 */
data class RouteSignature(
    /** Rasterzellen (~200 m), durch die der Track fuehrte; x/y gepackt (`x shl 32 or y`), sortiert, eindeutig. */
    val cells: LongArray,
    val distanceKm: Double,
    val startCell: Long,
    val endCell: Long,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (
            other is RouteSignature &&
                distanceKm == other.distanceKm &&
                startCell == other.startCell &&
                endCell == other.endCell &&
                cells.contentEquals(other.cells)
            )

    override fun hashCode(): Int {
        var h = cells.contentHashCode()
        h = 31 * h + distanceKm.hashCode()
        h = 31 * h + startCell.hashCode()
        h = 31 * h + endCell.hashCode()
        return h
    }

    override fun toString(): String =
        "RouteSignature(cells=${cells.size}, distanceKm=$distanceKm, startCell=$startCell, endCell=$endCell)"
}

/** Sortiert und entdoppelt die ersten [size] Werte von [cells] (sie entstehen beim Abtasten ungeordnet). */
private fun sortedDistinct(cells: LongArray, size: Int): LongArray {
    java.util.Arrays.sort(cells, 0, size)
    var n = 0
    for (i in 0 until size) {
        if (n == 0 || cells[i] != cells[n - 1]) cells[n++] = cells[i]
    }
    return cells.copyOf(n)
}

private fun cellX(lon: Double): Int =
    floor((lon.coerceIn(-180.0, 180.0) + 180.0) / 360.0 * ROUTE_CELL_COUNT).toInt()
        .coerceIn(0, ROUTE_CELL_COUNT - 1)

private fun cellY(lat: Double): Int {
    val rad = lat.coerceIn(-ROUTE_MAX_LATITUDE, ROUTE_MAX_LATITUDE) * (PI / 180)
    return floor((1 - ln(tan(rad) + 1 / cos(rad)) / PI) / 2 * ROUTE_CELL_COUNT).toInt()
        .coerceIn(0, ROUTE_CELL_COUNT - 1)
}

private fun cellAt(lat: Double, lon: Double): Long = (cellX(lon).toLong() shl 32) or cellY(lat).toLong()

/** Lokale Naeherung in Metern (aequirektangular) — fuer Kurzstrecken zwischen Punkten genau genug. */
private fun segmentMeters(a: TrackPoint, b: TrackPoint): Double {
    val dLat = (b.lat - a.lat) * ROUTE_METERS_PER_DEG
    val dLon = (b.lon - a.lon) * ROUTE_METERS_PER_DEG * cos((a.lat + b.lat) / 2 * (PI / 180))
    return hypot(dLat, dLon)
}

/**
 * Signatur eines Tracks; `null` bei weniger als zwei Punkten oder weniger als
 * [ROUTE_MIN_DISTANCE_KM] Strecke (dort ist ein Vergleich nur Rauschen).
 *
 * Zwischen Punkten wird linear in Breite/Laenge auf Schritte von hoechstens
 * [ROUTE_SAMPLE_STEP_M] interpoliert (Segmente ueber 20 km nicht).
 */
fun routeSignature(points: List<TrackPoint>): RouteSignature? {
    if (points.size < 2) return null

    // Zellen wachsen in einem Primitiv-Array (kein Boxing); am Ende einmal sortiert + entdoppelt.
    var buffer = LongArray(256)
    var size = 0
    fun add(cell: Long) {
        // Aufeinanderfolgende Stuetzstellen treffen meist dieselbe Zelle — gar nicht erst speichern.
        if (size > 0 && buffer[size - 1] == cell) return
        if (size == buffer.size) buffer = buffer.copyOf(size * 2)
        buffer[size++] = cell
    }
    add(cellAt(points[0].lat, points[0].lon))
    var meters = 0.0

    for (i in 1 until points.size) {
        val a = points[i - 1]
        val b = points[i]
        val d = segmentMeters(a, b)
        if (d > ROUTE_MAX_SEGMENT_M) {
            add(cellAt(b.lat, b.lon))
            continue
        }
        meters += d
        val steps = ceil(d / ROUTE_SAMPLE_STEP_M).toInt().coerceAtLeast(1)
        for (step in 1..steps) {
            val t = step.toDouble() / steps
            add(cellAt(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t))
        }
    }

    val distanceKm = meters / 1000.0
    if (distanceKm < ROUTE_MIN_DISTANCE_KM) return null
    return RouteSignature(
        cells = sortedDistinct(buffer, size),
        distanceKm = distanceKm,
        startCell = cellAt(points.first().lat, points.first().lon),
        endCell = cellAt(points.last().lat, points.last().lon),
    )
}

/**
 * Wie weit Start bzw. Ziel zweier Fahrten auseinanderliegen duerfen, in Zellen
 * je Achse: Wer dieselbe Strecke faehrt, startet nicht immer an derselben
 * Haustuer, aber im selben Viertel.
 *
 * Rechnung: Eine Zelle misst bei Stufe 17 und 48° N ~200 m. Zwei Zellen
 * Indexabstand heissen je nach Lage innerhalb der Zellen gut 200 bis 600 m
 * Luftlinie, im Mittel ≈ 400 m — dasselbe „400–600 m" wie frueher mit 4 Zellen
 * a ~100 m bei Stufe 18.
 */
const val ROUTE_ENDPOINT_TOLERANCE_CELLS: Int = 2

private fun cellsNear(a: Long, b: Long): Boolean {
    val dx = abs((a shr 32).toInt() - (b shr 32).toInt())
    val dy = abs(a.toInt() - b.toInt())
    return dx <= ROUTE_ENDPOINT_TOLERANCE_CELLS && dy <= ROUTE_ENDPOINT_TOLERANCE_CELLS
}

/** Eine Runde endet, wo sie beginnt. */
private fun RouteSignature.isLoop(): Boolean = cellsNear(startCell, endCell)

/**
 * Gleiche Strecke: Laenge innerhalb [ROUTE_MAX_DISTANCE_DEVIATION] (relativ
 * zur laengeren) UND Jaccard-Aehnlichkeit der Zellen mindestens
 * [ROUTE_MIN_JACCARD].
 *
 * ## Richtung
 * Bei einer **Runde** spielt die Richtung keine Rolle — dieselbe Runde
 * andersherum ist dieselbe Runde. Bei einer **Strecke von A nach B** dagegen
 * schon: Der Arbeitsweg bergab und der Heimweg bergauf beruehren dieselben
 * Zellen, aber ihre Zeiten zu vergleichen waere Unsinn. Sind beide Fahrten
 * keine Runde, muessen deshalb Start bei Start und Ziel bei Ziel liegen
 * ([ROUTE_ENDPOINT_TOLERANCE_CELLS]).
 *
 * Die Laengenpruefung faengt, was die Zellmenge allein nicht sieht (eine Runde
 * mit zusaetzlicher Schleife im selben Gebiet).
 */
fun sameRoute(a: RouteSignature, b: RouteSignature): Boolean {
    val longer = max(a.distanceKm, b.distanceKm)
    if (longer <= 0.0 || abs(a.distanceKm - b.distanceKm) / longer > ROUTE_MAX_DISTANCE_DEVIATION) return false
    if (!a.isLoop() && !b.isLoop() &&
        !(cellsNear(a.startCell, b.startCell) && cellsNear(a.endCell, b.endCell))
    ) {
        return false
    }
    val intersection = sortedIntersectionSize(a.cells, b.cells)
    val union = a.cells.size + b.cells.size - intersection
    if (union == 0) return false
    return intersection.toDouble() / union >= ROUTE_MIN_JACCARD
}

/** Groesse der Schnittmenge zweier aufsteigend sortierter, eindeutiger Arrays — linearer Merge. */
private fun sortedIntersectionSize(a: LongArray, b: LongArray): Int {
    var i = 0
    var j = 0
    var common = 0
    while (i < a.size && j < b.size) {
        val x = a[i]
        val y = b[j]
        when {
            x < y -> i++
            x > y -> j++
            else -> {
                common++
                i++
                j++
            }
        }
    }
    return common
}

// ---------------------------------------------------------------- Cache

/**
 * Zwischenstand einer Tour im Signatur-Cache — Fingerabdruck aus [updatedAt] +
 * [pointCount] wie [StoredExplorerTiles]. [signature] ist `null`, wenn die
 * Tour keine ergibt (zu kurz): so wird sie nicht bei jedem Lauf neu geladen.
 */
data class StoredRouteSignature(val updatedAt: Long, val pointCount: Int, val signature: RouteSignature?)

/**
 * Formatversion der Cache-Datei. **Hochzaehlen, sobald sich die Bedeutung der
 * Zellen aendert** (Zoomstufe, Abtastschritt, Packung): Eine Datei mit anderer
 * Version gilt als leer und wird neu aufgebaut — Zellen verschiedener Raster
 * duerfen nie gegeneinander verglichen werden. Version 1 war Zoom 18.
 */
const val ROUTE_SIGNATURE_CACHE_VERSION: Int = 2

/** Die Cache-Datei als Ganzes; bewusst ein flaches DTO, damit sie sich streamen laesst. */
@Serializable
private class RouteSignatureCacheFile(val version: Int, val entries: List<RouteSignatureCacheEntry>)

@Serializable
private class RouteSignatureCacheEntry(
    val id: String,
    val updatedAt: Long,
    val pointCount: Int,
    /** `null` = Tour ohne Signatur (zu kurz). */
    val signature: RouteSignatureCacheSignature?,
)

@Serializable
private class RouteSignatureCacheSignature(
    val distanceKm: Double,
    val startCell: Long,
    val endCell: Long,
    val cells: LongArray,
)

private val routeCacheJson = Json { ignoreUnknownKeys = true }

/** Aufsteigend und ohne Duplikate — sonst traegt der Merge in [sameRoute] nicht. */
private fun LongArray.isStrictlySorted(): Boolean {
    for (i in 1 until size) if (this[i - 1] >= this[i]) return false
    return true
}

/**
 * Schreibt [entries] als Cache-Datei auf [out] — gestreamt, ohne JSON-Baum und
 * ohne den ganzen Text im Speicher (bei tausenden Touren sonst ein Mehrfaches
 * der Daten). Schliesst [out] nicht.
 */
@OptIn(ExperimentalSerializationApi::class)
fun writeRouteSignatureCache(entries: Map<String, StoredRouteSignature>, out: OutputStream) {
    val file = RouteSignatureCacheFile(
        version = ROUTE_SIGNATURE_CACHE_VERSION,
        entries = entries.map { (id, e) ->
            RouteSignatureCacheEntry(
                id = id,
                updatedAt = e.updatedAt,
                pointCount = e.pointCount,
                signature = e.signature?.let {
                    RouteSignatureCacheSignature(it.distanceKm, it.startCell, it.endCell, it.cells)
                },
            )
        },
    )
    routeCacheJson.encodeToStream(file, out)
}

/**
 * Liest eine Cache-Datei von [input] (gestreamt). `null`, wenn sie unlesbar
 * ist oder eine andere [ROUTE_SIGNATURE_CACHE_VERSION] hat — der Aufrufer
 * behandelt das als leeren Cache. Einzelne Eintraege mit unbrauchbaren Werten
 * (Zellen nicht sortiert, Laenge nicht endlich) fallen weg und werden neu
 * berechnet; der Rest bleibt. Schliesst [input] nicht.
 */
@OptIn(ExperimentalSerializationApi::class)
fun readRouteSignatureCache(input: InputStream): MutableMap<String, StoredRouteSignature>? {
    val file = try {
        routeCacheJson.decodeFromStream<RouteSignatureCacheFile>(input)
    } catch (_: Exception) {
        return null
    }
    if (file.version != ROUTE_SIGNATURE_CACHE_VERSION) return null
    val result = LinkedHashMap<String, StoredRouteSignature>(file.entries.size * 2)
    for (e in file.entries) {
        val sig = e.signature?.let {
            if (!it.distanceKm.isFinite() || it.distanceKm < 0.0 || !it.cells.isStrictlySorted()) continue
            RouteSignature(it.cells, it.distanceKm, it.startCell, it.endCell)
        }
        result[e.id] = StoredRouteSignature(e.updatedAt, e.pointCount, sig)
    }
    return result
}

/** Speicher fuer die Signaturen, je Tour-ID ein Eintrag. `:app` haengt eine Datei an, Tests eine Map. */
interface RouteSignatureStore {
    fun read(rideId: String): StoredRouteSignature?
    fun write(rideId: String, value: StoredRouteSignature)
    fun remove(rideId: String)
}

/** Einfache Map-Implementierung fuer Tests. */
class InMemoryRouteSignatureStore : RouteSignatureStore {
    private val entries = HashMap<String, StoredRouteSignature>()
    override fun read(rideId: String): StoredRouteSignature? = entries[rideId]
    override fun write(rideId: String, value: StoredRouteSignature) {
        entries[rideId] = value
    }
    override fun remove(rideId: String) {
        entries.remove(rideId)
    }
}

/**
 * Fuellt den [store] fuer gefahrene Touren, deren Eintrag fehlt oder veraltet
 * ist (`updatedAt` oder `pointCount` weicht von der Zusammenfassung ab), und
 * laedt dafuer nur diese ueber [loadRide]. Liefert das `null` (Datei
 * weg/Quarantaene), wird die Tour uebersprungen und nichts gespeichert. Eine
 * Tour ohne Signatur (zu kurz) wird mit `signature = null` gespeichert und
 * nicht erneut geladen.
 *
 * Raeumt nichts auf (kein [RouteSignatureStore.remove]) — das bleibt dem
 * Aufrufer. Ergebnis: Tour-ID -> Signatur fuer alle gefahrenen Touren, die
 * eine haben; geplante Touren fehlen.
 */
fun collectRouteSignatures(
    rides: List<RideInfo>,
    store: RouteSignatureStore,
    loadRide: (String) -> Ride?,
): Map<String, RouteSignature> {
    val result = LinkedHashMap<String, RouteSignature>()
    // Indoor-Fahrten ([RideInfo.indoor]) bekommen keine Signatur und keinen
    // Cache-Eintrag: Ihre Zeit ist virtuell (`docs/indoor.md` 10.2).
    for (info in outdoorRides(rides)) {
        val cached = store.read(info.id)
        val entry = if (cached != null && cached.updatedAt == info.updatedAt && cached.pointCount == info.pointCount) {
            cached
        } else {
            val ride = loadRide(info.id) ?: continue
            StoredRouteSignature(info.updatedAt, info.pointCount, routeSignature(ride.points))
                .also { store.write(info.id, it) }
        }
        entry.signature?.let { result[info.id] = it }
    }
    return result
}

// ---------------------------------------------------------------- Vergleich

/** Eine Fahrt auf einer wiederholten Strecke. */
data class RouteEffort(val rideId: String, val createdAt: Long, val timeS: Int, val avgSpeedKmh: Double)

/**
 * Vergleich der Tour [rideId] mit allen anderen auf derselben Strecke.
 *
 * Verglichen werden ganze Touren, nicht ein Abschnitt: Weil [sameRoute] die
 * Laenge auf 8 % begrenzt, sind die Zeiten in diesem Rahmen vergleichbar —
 * kein exakter Segmentvergleich.
 */
data class RouteComparison(
    /** Alle Fahrten auf dieser Strecke inkl. dieser Tour, aelteste zuerst. */
    val efforts: List<RouteEffort>,
    /** 1 = schnellste (nach Bewegungszeit; Gleichstand -> frueher gefahrene Tour ist besser). */
    val rank: Int,
    /** Juengste fruehere Fahrt; `null`, wenn dies die erste ist. */
    val previous: RouteEffort?,
    /** Schnellste Fahrt insgesamt (kann diese Tour sein). */
    val best: RouteEffort,
    /** Die Fahrt, um die es geht (Teil von [efforts]); Bezug fuer die Deltas. */
    val current: RouteEffort,
) {
    val count: Int get() = efforts.size
    val isBest: Boolean get() = best.rideId == current.rideId

    /** Negativ = schneller als beim letzten Mal. */
    val deltaToPreviousS: Int? get() = previous?.let { current.timeS - it.timeS }

    /** Gegen die schnellste ANDERE Fahrt; negativ = neue Bestzeit. */
    val deltaToBestOtherS: Int?
        get() = efforts.filter { it.rideId != current.rideId }
            .minWithOrNull(compareBy<RouteEffort>({ it.timeS }, { it.createdAt }))
            ?.let { current.timeS - it.timeS }
}

private fun effortOf(info: RideInfo, signature: RouteSignature): RouteEffort? {
    // Nur Bewegungszeit: Eine Gesamtdauer enthielte Pausen (Cafe) und waere
    // nicht mit den Bewegungszeiten der anderen Fahrten vergleichbar.
    val time = info.stats.movingTimeS
    if (time == null || time <= 0) return null
    return RouteEffort(info.id, info.createdAt, time, signature.distanceKm / (time / 3600.0))
}

/**
 * Vergleicht [rideId] mit allen gefahrenen Touren, deren Signatur
 * [sameRoute] zu der dieser Tour ist (bezogen auf diese Tour, keine
 * transitive Gruppierung). `null`, wenn die Tour fehlt, geplant ist, keine
 * Signatur oder keine brauchbare Zeit hat oder keine weitere passende Tour
 * existiert. Zeit = `movingTimeS`, nie die Gesamtdauer (Pausen
 * wuerden sonst mit Bewegungszeiten gemischt); Touren ohne Bewegungszeit
 * (fehlend oder 0) zaehlen nicht als Fahrt.
 */
fun compareOnRoute(
    rideId: String,
    rides: List<RideInfo>,
    signatures: Map<String, RouteSignature>,
): RouteComparison? {
    // Nur draussen gefahrene Touren — eine Indoor-Fahrt ist weder Vergleich
    // noch Gegenstand eines Vergleichs.
    val ridden = outdoorRides(rides)
    val own = ridden.firstOrNull { it.id == rideId } ?: return null
    val ownSignature = signatures[rideId] ?: return null
    val current = effortOf(own, ownSignature) ?: return null

    val efforts = ridden.mapNotNull { info ->
        val sig = signatures[info.id] ?: return@mapNotNull null
        if (info.id != rideId && !sameRoute(ownSignature, sig)) return@mapNotNull null
        effortOf(info, sig)
    }.distinctBy { it.rideId }.sortedWith(compareBy({ it.createdAt }, { it.rideId }))
    if (efforts.size < 2) return null

    val byTime = efforts.sortedWith(compareBy<RouteEffort>({ it.timeS }, { it.createdAt }, { it.rideId }))
    val index = efforts.indexOfFirst { it.rideId == rideId }
    return RouteComparison(
        efforts = efforts,
        rank = byTime.indexOfFirst { it.rideId == rideId } + 1,
        previous = efforts.getOrNull(index - 1),
        best = byTime.first(),
        current = current,
    )
}
