package de.trailscape.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
 * ~100-150-m-Rasterzellen, durch die der Track fuehrte, plus Laenge, Start-
 * und Endzelle. Zwei Touren sind dieselbe Strecke ([sameRoute]), wenn Laenge
 * und Zellmenge zusammenpassen — ohne je wieder Punkte zu laden. Aufbau wie
 * `ExplorerTiles.kt`: abgeleiteter Wert je Tour, [StoredRouteSignature] mit
 * Fingerabdruck, Store-Schnittstelle, inkrementelles [collectRouteSignatures].
 *
 * Rein Kotlin: keine Android-Abhaengigkeit, keine Uhr, keine Zeitzone, keine
 * ausformulierten Saetze — nur Daten.
 */

/**
 * Zoomstufe des Zellrasters. Stufe 18 misst am Aequator ~150 m, bei 48° N
 * rund 100 m: fein genug, dass eine Parallelstrasse eine andere Zelle trifft,
 * grob genug, dass GPS-Rauschen von wenigen Metern kaum Zellen kippt.
 */
const val ROUTE_CELL_ZOOM: Int = 18

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

/** Kompakte Form eines gefahrenen Tracks. */
data class RouteSignature(
    /** Rasterzellen (~150 m), durch die der Track fuehrte; x/y gepackt (`x shl 32 or y`). */
    val cells: Set<Long>,
    val distanceKm: Double,
    val startCell: Long,
    val endCell: Long,
)

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

    val cells = LinkedHashSet<Long>()
    cells += cellAt(points[0].lat, points[0].lon)
    var meters = 0.0

    for (i in 1 until points.size) {
        val a = points[i - 1]
        val b = points[i]
        val d = segmentMeters(a, b)
        if (d > ROUTE_MAX_SEGMENT_M) {
            cells += cellAt(b.lat, b.lon)
            continue
        }
        meters += d
        val steps = ceil(d / ROUTE_SAMPLE_STEP_M).toInt().coerceAtLeast(1)
        for (step in 1..steps) {
            val t = step.toDouble() / steps
            cells += cellAt(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)
        }
    }

    val distanceKm = meters / 1000.0
    if (distanceKm < ROUTE_MIN_DISTANCE_KM) return null
    return RouteSignature(
        cells = cells,
        distanceKm = distanceKm,
        startCell = cellAt(points.first().lat, points.first().lon),
        endCell = cellAt(points.last().lat, points.last().lon),
    )
}

/**
 * Wie weit Start bzw. Ziel zweier Fahrten auseinanderliegen duerfen, in Zellen
 * je Achse (4 Zellen ≈ 400–600 m): Wer dieselbe Strecke faehrt, startet nicht
 * immer an derselben Haustuer, aber im selben Viertel.
 */
const val ROUTE_ENDPOINT_TOLERANCE_CELLS: Int = 4

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
    val small = if (a.cells.size <= b.cells.size) a.cells else b.cells
    val large = if (a.cells.size <= b.cells.size) b.cells else a.cells
    val intersection = small.count { it in large }
    val union = a.cells.size + b.cells.size - intersection
    if (union == 0) return false
    return intersection.toDouble() / union >= ROUTE_MIN_JACCARD
}

// ---------------------------------------------------------------- Cache

/**
 * Zwischenstand einer Tour im Signatur-Cache — Fingerabdruck aus [updatedAt] +
 * [pointCount] wie [StoredExplorerTiles]. [signature] ist `null`, wenn die
 * Tour keine ergibt (zu kurz): so wird sie nicht bei jedem Lauf neu geladen.
 */
data class StoredRouteSignature(val updatedAt: Long, val pointCount: Int, val signature: RouteSignature?) {
    /** Kompakt: Zellen als Zahlenfeld (sortiert, damit die Ausgabe stabil ist). */
    fun toJson(): JsonObject = buildJsonObject {
        put("updatedAt", updatedAt)
        put("pointCount", pointCount)
        val sig = signature
        if (sig == null) {
            put("signature", JsonNull)
        } else {
            put(
                "signature",
                buildJsonObject {
                    put("distanceKm", sig.distanceKm)
                    put("startCell", sig.startCell)
                    put("endCell", sig.endCell)
                    put("cells", JsonArray(sig.cells.sorted().map { JsonPrimitive(it) }))
                },
            )
        }
    }

    companion object {
        /** Tolerant: jedes kaputte oder unvollstaendige Element ergibt `null` (Eintrag wird neu berechnet). */
        fun fromJson(json: JsonElement?): StoredRouteSignature? {
            val obj = json as? JsonObject ?: return null
            return try {
                val signature = when (val sig = obj["signature"]) {
                    // Fehlender Schluessel = kaputt; explizites JsonNull = „keine Signatur".
                    null -> return null
                    is JsonNull -> null
                    is JsonObject -> RouteSignature(
                        cells = sig.requiredArray("cells").map {
                            (it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.content?.toLongOrNull()
                                ?: return null
                        }.toSet(),
                        distanceKm = sig.requiredDouble("distanceKm"),
                        startCell = sig.requiredLong("startCell"),
                        endCell = sig.requiredLong("endCell"),
                    )
                    else -> return null
                }
                StoredRouteSignature(
                    updatedAt = obj.requiredLong("updatedAt"),
                    pointCount = obj.requiredInt("pointCount"),
                    signature = signature,
                )
            } catch (_: MissingOrInvalidFieldException) {
                null
            }
        }

        /** Wie [fromJson], aber aus dem Rohtext; unlesbares JSON ergibt `null`. */
        fun fromJsonString(raw: String): StoredRouteSignature? =
            try {
                fromJson(Json.parseToJsonElement(raw))
            } catch (_: Exception) {
                null
            }
    }
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
    for (info in riddenRides(rides)) {
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
    val time = info.stats.movingTimeS ?: info.stats.durationS
    if (time == null || time <= 0) return null
    return RouteEffort(info.id, info.createdAt, time, signature.distanceKm / (time / 3600.0))
}

/**
 * Vergleicht [rideId] mit allen gefahrenen Touren, deren Signatur
 * [sameRoute] zu der dieser Tour ist (bezogen auf diese Tour, keine
 * transitive Gruppierung). `null`, wenn die Tour fehlt, geplant ist, keine
 * Signatur oder keine brauchbare Zeit hat oder keine weitere passende Tour
 * existiert. Zeit = `movingTimeS ?: durationS`; Touren ohne Zeit zaehlen
 * nicht als Fahrt.
 */
fun compareOnRoute(
    rideId: String,
    rides: List<RideInfo>,
    signatures: Map<String, RouteSignature>,
): RouteComparison? {
    val ridden = riddenRides(rides)
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
