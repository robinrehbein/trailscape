package de.trailscape.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Lokale Segment-Bestleistungen: Bestenlisten gegen sich selbst — ohne
 * Server, ohne Datenabfluss.
 *
 * ## Was ein Segment ist
 * Ein automatisch erkannter, markanter **Anstieg**, den mindestens zwei
 * eigene Touren geteilt haben. Aus jeder Tour werden Anstiegs-Kandidaten
 * abgeleitet ([detectSegmentCandidates]); teilen zwei Touren denselben
 * Kandidaten (Start/Ende nahe beieinander, Pfad korridorgleich, gleiche
 * Richtung), entsteht daraus ein [ClimbSegment] mit einem [SegmentEffort] je
 * Befahrung. Wiederholte Befahrungen in EINER Tour (Runden) ergeben mehrere
 * Efforts derselben Tour.
 *
 * ## Warum nur Anstiege, und warum konservativ
 * Ein Anstieg hat einen natuerlichen, wiedererkennbaren Anfang und ein Ende
 * (unten/oben) und eine Zeit, die etwas ueber die Form aussagt. Beliebige
 * Flachstuecke haben beides nicht — ihre Grenzen laegen im GPS-Rauschen.
 * Die Schwellen ([segmentClimbMinGainM] u. a.) sind bewusst so gewaehlt,
 * dass lieber wenige, markante Segmente entstehen als viele fragwuerdige:
 * eine Bestzeit auf einem 80-m-Huegelchen ist keine.
 *
 * ## Namensraum
 * „Segment" heisst in `RoutingSegments.kt`/`:app`-`routing/` etwas voellig
 * anderes (BRouter-Routing-**Kacheln**, `*.rd5`). Diese Datei benutzt
 * deshalb fuer ihre Typen das Praefix `ClimbSegment`/`Segment…` im Kontext
 * der Registry — Beruehrungspunkte gibt es keine.
 *
 * ## Kein Android, keine IO
 * Reine Rechnung und ein JSON-Codec (von Hand, siehe `JsonSupport.kt`).
 * Persistenz (Datei `rides/segmente.json`) und Pflege-Hooks liegen in `:app`
 * (`data/SegmentStore.kt`, `AppViewModel`).
 */

// ---------------------------------------------------------------------------
// Heuristik-Parameter
// ---------------------------------------------------------------------------

/**
 * Schrittweite der auf gleiche Abstaende umgetasteten Spur in Metern.
 *
 * 25 m ist grob genug, um GPS-Jitter zwischen zwei Befahrungen zu
 * verschlucken, und fein genug, dass ein 300-m-Anstieg noch ein Dutzend
 * Stuetzstellen hat. Dieselbe Groesse dient als Zellenmass des
 * Korridor-Vergleichs — die „gerasterte Punktfolge" der Referenzpfade.
 */
const val segmentResampleStepM: Double = 25.0

/** Mindest-Hoehengewinn eines Anstiegs-Kandidaten in Metern (konservativ). */
const val segmentClimbMinGainM: Double = 30.0

/** Mindestlaenge eines Anstiegs-Kandidaten in Metern. */
const val segmentClimbMinLengthM: Double = 300.0

/**
 * Mindest-Durchschnittssteigung eines Kandidaten (3 %). Haelt lange, kaum
 * spuerbare Flachrampen (40 Hm auf 4 km) aus der Liste — die Schwellen fuer
 * Gewinn und Laenge allein wuerden sie durchlassen.
 */
const val segmentClimbMinAvgGradient: Double = 0.03

/**
 * Glaettungsfenster der Hoehe in Metern (zentriertes Mittel entlang der
 * Strecke). Barometer- und GPS-Hoehen zappeln um wenige Meter; ungefiltert
 * zerfiele ein Anstieg an jedem Zacken in zwei. 75 m entsprechen bei
 * [segmentResampleStepM] ±3 Stuetzstellen.
 */
const val segmentSmoothingWindowM: Double = 75.0

/**
 * Wie viele Meter die geglaettete Hoehe unter das bisherige Maximum fallen
 * darf, ohne dass der Anstieg als beendet gilt — kurze Gegengefaelle und
 * Kehren gehoeren zum selben Anstieg.
 */
const val segmentClimbDipToleranceM: Double = 10.0

/**
 * Wie viele Meter Strecke ohne neues Hoehenmaximum den Anstieg beenden
 * (Flachstueck/Kuppe). Der Kandidat endet dann am zuletzt erreichten
 * Maximum, nicht am Ende des Flachstuecks.
 */
const val segmentClimbStallToleranceM: Double = 200.0

/** Maximale Distanz zwischen den Start- bzw. Endpunkten zweier Befahrungen. */
const val segmentEndpointToleranceM: Double = 50.0

/**
 * Korridorbreite des Pfadvergleichs in Metern: Ein Punkt der einen Befahrung
 * gilt als „auf dem Pfad" der anderen, wenn dort in diesem Umkreis ein Punkt
 * liegt. Etwas breiter als das Raster ([segmentResampleStepM]), damit ein
 * Punkt, der zwischen zwei Stuetzstellen der Gegenseite faellt, nicht
 * durchrutscht.
 */
const val segmentCorridorToleranceM: Double = 30.0

/**
 * Mindestanteil der Punkte, die **beidseitig** im Korridor der Gegenseite
 * liegen muessen. Beidseitig, damit weder ein Teilstueck als das Ganze noch
 * das Ganze als ein Teilstueck durchgeht.
 */
const val segmentCorridorMinOverlap: Double = 0.8

// ---------------------------------------------------------------------------
// Datentypen
// ---------------------------------------------------------------------------

/**
 * Eine einzelne Befahrung eines Segments.
 *
 * [rideUpdatedAt] macht den Eintrag ungueltig, sobald sich die Tour aendert
 * (dasselbe Muster wie [StoredRideLoadFacts]): Die Pflege in `:app` erkennt
 * daran, dass die Tour neu durch [updateSegmentRegistry] muss.
 */
data class SegmentEffort(
    val rideId: String,
    /** `updatedAt` der Tour zum Zeitpunkt der Auswertung (ms seit Epoch). */
    val rideUpdatedAt: Long,
    /** Startzeitpunkt dieser Befahrung (ms seit Epoch) — ordnet Runden einer Tour. */
    val startedAt: Long,
    /** Fahrzeit der Befahrung in Sekunden. Bestleistung = kleinster Wert. */
    val timeS: Int,
    val distanceM: Double,
    val ascentM: Double,
    /** Durchschnittspuls der Befahrung, falls die Tour Herzfrequenz traegt. */
    val avgHr: Int? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("rideId", rideId)
        put("rideUpdatedAt", rideUpdatedAt)
        put("startedAt", startedAt)
        put("timeS", timeS)
        put("distanceM", distanceM)
        put("ascentM", ascentM)
        avgHr?.let { put("avgHr", it) }
    }

    companion object {
        fun fromJson(json: JsonObject): SegmentEffort = SegmentEffort(
            rideId = json.requiredString("rideId"),
            rideUpdatedAt = json.requiredLong("rideUpdatedAt"),
            startedAt = json.requiredLong("startedAt"),
            timeS = json.requiredInt("timeS"),
            distanceM = json.requiredDouble("distanceM"),
            ascentM = json.requiredDouble("ascentM"),
            avgHr = json.optionalInt("avgHr"),
        )
    }
}

/**
 * Ein etabliertes Segment: Referenzpfad plus alle bekannten Befahrungen.
 *
 * Der [path] ist die komprimierte Punktfolge der **ersten** Befahrung
 * (umgetastet auf [segmentResampleStepM], Koordinaten auf 5 Nachkommastellen
 * gerundet — ~1 m). Er bleibt stehen, auch wenn die urspruengliche Tour
 * geloescht wird; ein Segment ohne verbleibende Efforts verschwindet dagegen
 * ganz.
 */
data class ClimbSegment(
    /** Stabile ID, abgeleitet aus dem Referenzpfad (siehe [stableSegmentId]). */
    val id: String,
    /** Namensvorschlag, z. B. „Anstieg 4,2 km / 180 Hm" (siehe [suggestSegmentName]). */
    val name: String,
    /** Referenzpfad, komprimiert (nur lat/lon). */
    val path: List<TrackPoint>,
    val distanceM: Double,
    val ascentM: Double,
    /** Alle Befahrungen, aufsteigend nach [SegmentEffort.startedAt]. */
    val efforts: List<SegmentEffort>,
) {
    /** Kuerzeste Befahrungszeit in Sekunden, `null` ohne Efforts. */
    val bestTimeS: Int? get() = efforts.minOfOrNull { it.timeS }

    fun toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("name", name)
        put("path", encodeSegmentPath(path))
        put("distanceM", distanceM)
        put("ascentM", ascentM)
        put("efforts", buildJsonArray { efforts.forEach { add(it.toJson()) } })
    }

    companion object {
        fun fromJson(json: JsonObject): ClimbSegment = ClimbSegment(
            id = json.requiredString("id"),
            name = json.requiredString("name"),
            path = decodeSegmentPath(json.requiredArray("path")),
            distanceM = json.requiredDouble("distanceM"),
            ascentM = json.requiredDouble("ascentM"),
            efforts = json.requiredArray("efforts").map { SegmentEffort.fromJson(it.asRequiredObject()) },
        )
    }
}

/**
 * Ein noch nicht etablierter Anstiegs-Kandidat einer einzelnen Tour.
 *
 * Bleibt in der Registry liegen, bis eine **andere** Tour denselben
 * Kandidaten faehrt — erst dann entsteht ein [ClimbSegment]. Traegt deshalb
 * bereits alles, was der spaetere Effort braucht.
 */
data class SegmentCandidate(
    val rideId: String,
    val rideUpdatedAt: Long,
    /** Umgetasteter, gerundeter Pfad des Kandidaten (nur lat/lon). */
    val path: List<TrackPoint>,
    val distanceM: Double,
    val ascentM: Double,
    val startedAt: Long,
    val timeS: Int,
    val avgHr: Int? = null,
) {
    internal fun toEffort(): SegmentEffort = SegmentEffort(
        rideId = rideId,
        rideUpdatedAt = rideUpdatedAt,
        startedAt = startedAt,
        timeS = timeS,
        distanceM = distanceM,
        ascentM = ascentM,
        avgHr = avgHr,
    )

    fun toJson(): JsonObject = buildJsonObject {
        put("rideId", rideId)
        put("rideUpdatedAt", rideUpdatedAt)
        put("startedAt", startedAt)
        put("timeS", timeS)
        put("distanceM", distanceM)
        put("ascentM", ascentM)
        avgHr?.let { put("avgHr", it) }
        put("path", encodeSegmentPath(path))
    }

    companion object {
        fun fromJson(json: JsonObject): SegmentCandidate = SegmentCandidate(
            rideId = json.requiredString("rideId"),
            rideUpdatedAt = json.requiredLong("rideUpdatedAt"),
            startedAt = json.requiredLong("startedAt"),
            timeS = json.requiredInt("timeS"),
            distanceM = json.requiredDouble("distanceM"),
            ascentM = json.requiredDouble("ascentM"),
            avgHr = json.optionalInt("avgHr"),
            path = decodeSegmentPath(json.requiredArray("path")),
        )
    }
}

/**
 * Der Gesamtbestand der Segment-Erkennung: etablierte Segmente, wartende
 * Kandidaten und der Merkzettel, welche Tour mit welchem `updatedAt` bereits
 * eingerechnet ist.
 *
 * [processed] existiert getrennt von Efforts/Kandidaten, weil auch eine Tour
 * **ohne** Anstieg als „gesehen" gelten muss — sonst wuerde jeder
 * Abgleichslauf saemtliche Flachtouren erneut laden und durchrechnen.
 */
data class SegmentRegistry(
    val segments: List<ClimbSegment> = emptyList(),
    val candidates: List<SegmentCandidate> = emptyList(),
    /** Tour-ID → `updatedAt` zum Zeitpunkt der Einrechnung. */
    val processed: Map<String, Long> = emptyMap(),
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("version", 1)
        put("segments", buildJsonArray { segments.forEach { add(it.toJson()) } })
        put("candidates", buildJsonArray { candidates.forEach { add(it.toJson()) } })
        put(
            "processed",
            buildJsonArray {
                processed.forEach { (id, updatedAt) ->
                    add(
                        buildJsonObject {
                            put("id", id)
                            put("updatedAt", updatedAt)
                        },
                    )
                }
            },
        )
    }

    companion object {
        val EMPTY = SegmentRegistry()

        fun fromJson(json: JsonObject): SegmentRegistry = SegmentRegistry(
            segments = json.requiredArray("segments").map { ClimbSegment.fromJson(it.asRequiredObject()) },
            candidates = json.requiredArray("candidates").map { SegmentCandidate.fromJson(it.asRequiredObject()) },
            processed = json.requiredArray("processed").associate {
                val obj = it.asRequiredObject()
                obj.requiredString("id") to obj.requiredLong("updatedAt")
            },
        )
    }
}

/** Ergebnis von [updateSegmentRegistry]: neuer Bestand plus erkannte Bestleistungen. */
data class SegmentRegistryUpdate(
    val registry: SegmentRegistry,
    /** Bestleistungen, die die eingerechnete Tour neu aufgestellt hat. */
    val newBests: List<SegmentNewBest>,
)

/** Eine von einer neuen Tour aufgestellte Bestleistung (fuer den Hinweis nach der Fahrt). */
data class SegmentNewBest(
    val segmentId: String,
    val segmentName: String,
    /** Die neue Bestzeit in Sekunden. */
    val timeS: Int,
    /** Um wie viele Sekunden die bisherige Bestzeit unterboten wurde. */
    val improvementS: Int,
)

/**
 * Eine Befahrung aus Sicht der Tourdetail-Ansicht: Zeit, Bestzeit, Platz und
 * Rueckstand — fertig gerechnet, die UI formatiert nur noch.
 */
data class SegmentEffortView(
    val segmentId: String,
    val name: String,
    val distanceM: Double,
    val ascentM: Double,
    /** Startzeitpunkt der Befahrung (ordnet Runden innerhalb der Tour). */
    val startedAt: Long,
    /** Zeit dieser Befahrung in Sekunden. */
    val timeS: Int,
    /** Persoenliche Bestzeit ueber alle Befahrungen in Sekunden. */
    val bestTimeS: Int,
    /** Anzahl aller Befahrungen des Segments. */
    val effortCount: Int,
    /** Platz dieser Befahrung, 1 = Bestzeit („2. von 7"). */
    val rank: Int,
    /** Rueckstand auf die Bestzeit in Sekunden; 0 bei der Bestzeit selbst. */
    val deltaToBestS: Int,
    /**
     * Ob diese Befahrung zum Zeitpunkt der Fahrt eine **neue** Bestzeit war:
     * schneller als alle frueheren Befahrungen — und es gab mindestens eine
     * fruehere. Die allererste Befahrung ist keine „neue Bestzeit", sie ist
     * schlicht die einzige.
     */
    val isNewBest: Boolean,
    val avgHr: Int? = null,
)

// ---------------------------------------------------------------------------
// Anstiegs-Erkennung
// ---------------------------------------------------------------------------

/**
 * Eine Stuetzstelle der auf [segmentResampleStepM] umgetasteten Spur.
 * Hoehe/Zeit sind linear zwischen den umgebenden Originalpunkten
 * interpoliert; fehlt eine Seite, bleibt der Wert `null`.
 */
internal data class ResampledPoint(
    val distM: Double,
    val lat: Double,
    val lon: Double,
    val eleM: Double?,
    val timeMs: Long?,
    val hr: Int?,
)

/**
 * Tastet die Spur auf gleichmaessige [stepM]-Abstaende um.
 *
 * Gleichmaessige Abstaende sind die Grundlage von allem Weiteren: Die
 * Hoehenglaettung bekommt ein festes Fenster in Metern, der Korridorvergleich
 * ein festes Raster, und zwei Befahrungen mit voellig verschiedenen
 * Aufzeichnungsraten (1 s GPS gegen 5 s Health-Import) werden vergleichbar.
 */
internal fun resampleTrack(points: List<TrackPoint>, stepM: Double = segmentResampleStepM): List<ResampledPoint> {
    if (points.size < 2) return emptyList()

    val cum = DoubleArray(points.size)
    for (i in 1 until points.size) {
        cum[i] = cum[i - 1] + haversineM(points[i - 1], points[i])
    }
    val totalM = cum.last()
    if (totalM < stepM) return emptyList()

    val out = ArrayList<ResampledPoint>((totalM / stepM).toInt() + 2)
    var seg = 0
    var d = 0.0
    while (d <= totalM) {
        while (seg < points.size - 2 && cum[seg + 1] < d) seg++
        val a = points[seg]
        val b = points[seg + 1]
        val span = cum[seg + 1] - cum[seg]
        val t = if (span > 0) ((d - cum[seg]) / span).coerceIn(0.0, 1.0) else 0.0
        out += ResampledPoint(
            distM = d,
            lat = a.lat + (b.lat - a.lat) * t,
            lon = a.lon + (b.lon - a.lon) * t,
            eleM = if (a.ele != null && b.ele != null) a.ele + (b.ele - a.ele) * t else a.ele ?: b.ele,
            timeMs = if (a.time != null && b.time != null) {
                a.time + ((b.time - a.time) * t).roundToLong()
            } else {
                null
            },
            hr = if (t < 0.5) a.hr ?: b.hr else b.hr ?: a.hr,
        )
        d += stepM
    }
    return out
}

/**
 * Glaettet die Hoehen der umgetasteten Spur mit einem zentrierten Mittel
 * ueber [segmentSmoothingWindowM]. Stuetzstellen ohne Hoehe bleiben `null`
 * und zaehlen nicht ins Mittel ihrer Nachbarn.
 */
internal fun smoothElevations(samples: List<ResampledPoint>, stepM: Double = segmentResampleStepM): DoubleArray? {
    if (samples.isEmpty()) return null
    val halfN = max(1, (segmentSmoothingWindowM / (2 * stepM)).roundToInt())
    val out = DoubleArray(samples.size) { Double.NaN }
    var any = false
    for (i in samples.indices) {
        var sum = 0.0
        var n = 0
        for (j in max(0, i - halfN)..min(samples.lastIndex, i + halfN)) {
            val ele = samples[j].eleM ?: continue
            sum += ele
            n++
        }
        if (n > 0 && samples[i].eleM != null) {
            out[i] = sum / n
            any = true
        }
    }
    return if (any) out else null
}

/**
 * Erkennt die Anstiegs-Kandidaten einer Tour.
 *
 * Ablauf: Spur umtasten ([resampleTrack]), Hoehe glaetten
 * ([smoothElevations]), dann entlang der Strecke laufen: Ein Anstieg beginnt,
 * wo die geglaettete Hoehe steigt, und endet, wenn sie mehr als
 * [segmentClimbDipToleranceM] unter ihr Maximum faellt oder laenger als
 * [segmentClimbStallToleranceM] kein neues Maximum kommt. Der Kandidat
 * reicht bis zum zuletzt erreichten Maximum. Angenommen wird er nur mit
 * [segmentClimbMinGainM] Hoehengewinn, [segmentClimbMinLengthM] Laenge und
 * [segmentClimbMinAvgGradient] Durchschnittssteigung.
 *
 * Befahrungen ohne Zeitstempel an Start **und** Ende werden verworfen — ohne
 * Zeit gibt es nichts zu vergleichen (haeufig bei importierten GPX-Dateien).
 * Eine Tour mit mehreren Anstiegen (oder Runden ueber denselben) liefert
 * entsprechend mehrere Kandidaten.
 */
fun detectSegmentCandidates(ride: Ride, stepM: Double = segmentResampleStepM): List<SegmentCandidate> {
    val samples = resampleTrack(ride.points, stepM)
    if (samples.size < 3) return emptyList()
    val ele = smoothElevations(samples, stepM) ?: return emptyList()

    val out = mutableListOf<SegmentCandidate>()
    var i = 0
    val last = samples.lastIndex
    while (i < last) {
        // Nur auf durchgehend hoehenbehafteten Stuecken suchen.
        if (ele[i].isNaN() || ele[i + 1].isNaN()) {
            i++
            continue
        }
        if (ele[i + 1] <= ele[i]) {
            i++
            continue
        }

        // Aufstieg laeuft: Maximum verfolgen, Abbruch bei Dip oder Stillstand.
        val start = i
        var maxEle = ele[i]
        var maxIdx = i
        var j = i + 1
        while (j <= last && !ele[j].isNaN()) {
            if (ele[j] > maxEle) {
                maxEle = ele[j]
                maxIdx = j
            }
            if (ele[j] <= maxEle - segmentClimbDipToleranceM) break
            if ((j - maxIdx) * stepM > segmentClimbStallToleranceM) break
            j++
        }

        val gain = maxEle - ele[start]
        val lengthM = (maxIdx - start) * stepM
        if (gain >= segmentClimbMinGainM &&
            lengthM >= segmentClimbMinLengthM &&
            gain / lengthM >= segmentClimbMinAvgGradient
        ) {
            candidateFromRange(ride, samples, ele, start, maxIdx)?.let { out += it }
        }
        i = maxIdx + 1
    }
    return out
}

/**
 * Baut aus dem Stuetzstellen-Bereich `[start, end]` den Kandidaten — oder
 * `null`, wenn die Befahrung keine brauchbare Zeit hat (fehlende oder
 * ruecklaeufige Zeitstempel).
 */
private fun candidateFromRange(
    ride: Ride,
    samples: List<ResampledPoint>,
    ele: DoubleArray,
    start: Int,
    end: Int,
): SegmentCandidate? {
    val t0 = samples[start].timeMs ?: return null
    val t1 = samples[end].timeMs ?: return null
    val timeS = ((t1 - t0) / 1000.0).roundToInt()
    if (timeS <= 0) return null

    val hrValues = (start..end).mapNotNull { samples[it].hr }
    return SegmentCandidate(
        rideId = ride.id,
        rideUpdatedAt = ride.updatedAt,
        path = (start..end).map {
            TrackPoint(lat = roundCoordinate(samples[it].lat), lon = roundCoordinate(samples[it].lon))
        },
        distanceM = samples[end].distM - samples[start].distM,
        ascentM = ele[end] - ele[start],
        startedAt = t0,
        timeS = timeS,
        avgHr = if (hrValues.isEmpty()) null else (hrValues.sum().toDouble() / hrValues.size).roundToInt(),
    )
}

/** Rundet eine Koordinate auf 5 Nachkommastellen (~1 m) — die Kompression der Referenzpfade. */
private fun roundCoordinate(value: Double): Double = (value * 1e5).roundToLong() / 1e5

// ---------------------------------------------------------------------------
// Matching
// ---------------------------------------------------------------------------

/** Ein Breiten-/Laengengrad-Rechteck als billiger Vorfilter. */
internal data class GeoBounds(
    val minLat: Double,
    val maxLat: Double,
    val minLon: Double,
    val maxLon: Double,
) {
    /** Ob sich beide Rechtecke, um [marginM] Meter aufgeweitet, ueberlappen. */
    fun intersects(other: GeoBounds, marginM: Double): Boolean {
        val latMargin = marginM / METERS_PER_DEGREE_LAT
        val midLat = (minLat + maxLat) / 2
        val lonMargin = marginM / (METERS_PER_DEGREE_LAT * max(0.1, cos(Math.toRadians(midLat))))
        return minLat - latMargin <= other.maxLat && maxLat + latMargin >= other.minLat &&
            minLon - lonMargin <= other.maxLon && maxLon + lonMargin >= other.minLon
    }
}

internal fun boundsOf(path: List<TrackPoint>): GeoBounds = GeoBounds(
    minLat = path.minOf { it.lat },
    maxLat = path.maxOf { it.lat },
    minLon = path.minOf { it.lon },
    maxLon = path.maxOf { it.lon },
)

private const val METERS_PER_DEGREE_LAT = 111_320.0

/**
 * Raster ueber den Punkten eines Pfads, Zellgroesse
 * [segmentCorridorToleranceM]. Nachbarschaftssuche in 3×3 Zellen plus
 * Haversine-Feinpruefung — damit bleibt der Korridorvergleich linear in der
 * Punktzahl statt O(n²).
 */
private class PathGrid(path: List<TrackPoint>) {
    private val cellLat = segmentCorridorToleranceM / METERS_PER_DEGREE_LAT
    private val cellLon: Double
    private val cells = HashMap<Long, MutableList<TrackPoint>>()

    init {
        val midLat = (path.minOf { it.lat } + path.maxOf { it.lat }) / 2
        cellLon = segmentCorridorToleranceM / (METERS_PER_DEGREE_LAT * max(0.1, cos(Math.toRadians(midLat))))
        for (p in path) {
            cells.getOrPut(key(p.lat, p.lon)) { mutableListOf() }.add(p)
        }
    }

    private fun key(lat: Double, lon: Double): Long {
        val x = floor(lat / cellLat).toLong()
        val y = floor(lon / cellLon).toLong()
        // 32 Bit je Achse reichen weltweit bei 30-m-Zellen.
        return (x shl 32) xor (y and 0xFFFFFFFFL)
    }

    /** Ob im Umkreis von [segmentCorridorToleranceM] ein Pfadpunkt liegt. */
    fun hasPointNear(point: TrackPoint): Boolean {
        val x = floor(point.lat / cellLat).toLong()
        val y = floor(point.lon / cellLon).toLong()
        for (dx in -1..1) {
            for (dy in -1..1) {
                val list = cells[((x + dx) shl 32) xor ((y + dy) and 0xFFFFFFFFL)] ?: continue
                if (list.any { haversineM(it, point) <= segmentCorridorToleranceM }) return true
            }
        }
        return false
    }
}

/** Anteil der Punkte von [path], die im Korridor von [reference] liegen. */
private fun corridorOverlap(path: List<TrackPoint>, reference: PathGrid): Double {
    if (path.isEmpty()) return 0.0
    val inside = path.count { reference.hasPointNear(it) }
    return inside.toDouble() / path.size
}

/**
 * Ob zwei Befahrungspfade dasselbe Segment meinen: Start und Ende jeweils
 * hoechstens [segmentEndpointToleranceM] auseinander (das erzwingt zugleich
 * die **gleiche Richtung** — eine Gegenrichtung hat Start und Ende
 * vertauscht) und beidseitige Korridorueberlappung von mindestens
 * [segmentCorridorMinOverlap].
 */
internal fun segmentPathsMatch(a: List<TrackPoint>, b: List<TrackPoint>): Boolean {
    if (a.size < 2 || b.size < 2) return false
    if (haversineM(a.first(), b.first()) > segmentEndpointToleranceM) return false
    if (haversineM(a.last(), b.last()) > segmentEndpointToleranceM) return false
    if (!boundsOf(a).intersects(boundsOf(b), segmentCorridorToleranceM)) return false
    if (corridorOverlap(a, PathGrid(b)) < segmentCorridorMinOverlap) return false
    return corridorOverlap(b, PathGrid(a)) >= segmentCorridorMinOverlap
}

// ---------------------------------------------------------------------------
// Registry-Pflege
// ---------------------------------------------------------------------------

/**
 * Rechnet EINE Tour (neu) in die Registry ein — der eine Schreibweg.
 *
 * Ablauf je Kandidat der Tour, nach Bounding-Box vorgefiltert:
 *  1. Passt er zu einem bestehenden Segment → neuer [SegmentEffort] dort.
 *  2. Passt er zu einem wartenden Kandidaten einer **anderen** Tour → neues
 *     Segment; alle wartenden Kandidaten, die ebenfalls passen (z. B. die
 *     zweite Runde derselben alten Tour), werden mit zu Efforts.
 *  3. Sonst wartet er selbst als Kandidat.
 *
 * Die Reihenfolge macht Runden von selbst richtig: Die zweite Runde der
 * neuen Tour trifft in Schritt 1 auf das gerade in Schritt 2 entstandene
 * Segment. Vorher werden alle Spuren derselben Tour-ID entfernt — der Aufruf
 * ist damit idempotent und deckt auch die **geaenderte** Tour ab (neues
 * `updatedAt`, z. B. nach HF-Anreicherung). Segmente ohne verbleibende
 * Efforts verschwinden.
 *
 * Geplante Touren ([Ride.planned]) werden nur als verarbeitet vermerkt —
 * niemand ist sie gefahren.
 */
fun updateSegmentRegistry(registry: SegmentRegistry, ride: Ride): SegmentRegistryUpdate {
    val segments = registry.segments
        .map { seg -> seg.copy(efforts = seg.efforts.filterNot { it.rideId == ride.id }) }
        .toMutableList()
    val waiting = registry.candidates.filterNot { it.rideId == ride.id }.toMutableList()

    val fresh = if (ride.planned) emptyList() else detectSegmentCandidates(ride)
    for (candidate in fresh) {
        val candidateBounds = boundsOf(candidate.path)

        // 1. Bestehende Segmente.
        val segmentIdx = segments.indexOfFirst {
            boundsOf(it.path).intersects(candidateBounds, segmentEndpointToleranceM) &&
                segmentPathsMatch(candidate.path, it.path)
        }
        if (segmentIdx >= 0) {
            val segment = segments[segmentIdx]
            segments[segmentIdx] = segment.copy(
                efforts = (segment.efforts + candidate.toEffort()).sortedBy { it.startedAt },
            )
            continue
        }

        // 2. Wartende Kandidaten anderer Touren.
        val partner = waiting.firstOrNull {
            it.rideId != candidate.rideId &&
                boundsOf(it.path).intersects(candidateBounds, segmentEndpointToleranceM) &&
                segmentPathsMatch(candidate.path, it.path)
        }
        if (partner != null) {
            // Referenz ist der aeltere Kandidat; alle weiteren Wartenden auf
            // demselben Pfad (Runden!) wandern mit in die Efforts.
            val members = waiting.filter { it === partner || segmentPathsMatch(it.path, partner.path) }
            waiting.removeAll { member -> members.any { it === member } }
            segments += ClimbSegment(
                id = uniqueSegmentId(partner.path, segments.mapTo(HashSet()) { it.id }),
                name = suggestSegmentName(partner.distanceM, partner.ascentM),
                path = partner.path,
                distanceM = partner.distanceM,
                ascentM = partner.ascentM,
                efforts = (members + candidate).map { it.toEffort() }.sortedBy { it.startedAt },
            )
            continue
        }

        // 3. Warten auf eine zweite Tour.
        waiting += candidate
    }

    val result = SegmentRegistry(
        segments = segments.filter { it.efforts.isNotEmpty() },
        candidates = waiting.toList(),
        processed = registry.processed + (ride.id to ride.updatedAt),
    )
    return SegmentRegistryUpdate(
        registry = result,
        newBests = collectNewBests(result, ride.id),
    )
}

/**
 * Bestleistungen, die die Tour [rideId] im (fertig aktualisierten) Bestand
 * aufgestellt hat: Je Segment zaehlt die schnellste eigene Befahrung, und sie
 * muss **alle chronologisch frueheren** Befahrungen unterbieten. Eine erste
 * Befahrung ohne Vergleichswert ist keine Bestleistung — sonst feierte jede
 * neue Strecke sich selbst.
 */
private fun collectNewBests(registry: SegmentRegistry, rideId: String): List<SegmentNewBest> {
    val out = mutableListOf<SegmentNewBest>()
    for (segment in registry.segments) {
        val own = segment.efforts.filter { it.rideId == rideId }
        if (own.isEmpty()) continue
        val best = own.minWith(compareBy({ it.timeS }, { it.startedAt }))
        val earlierBest = segment.efforts
            .filter { it !== best && it.startedAt < best.startedAt }
            .minOfOrNull { it.timeS }
            ?: continue
        if (best.timeS < earlierBest) {
            out += SegmentNewBest(
                segmentId = segment.id,
                segmentName = segment.name,
                timeS = best.timeS,
                improvementS = earlierBest - best.timeS,
            )
        }
    }
    return out
}

/**
 * Wirft alles weg, was zu Touren ausserhalb von [rideIds] gehoert (Efforts,
 * wartende Kandidaten, Verarbeitungs-Merker) — der Loeschpfad. Segmente ohne
 * verbleibende Efforts verschwinden ganz. Liefert bei Nichtstun **dieselbe**
 * Instanz zurueck, damit Aufrufer billig erkennen, ob zu speichern ist.
 */
fun retainRidesInSegmentRegistry(registry: SegmentRegistry, rideIds: Set<String>): SegmentRegistry {
    var changed = false
    val segments = registry.segments.mapNotNull { segment ->
        val kept = segment.efforts.filter { it.rideId in rideIds }
        when {
            kept.size == segment.efforts.size -> segment
            kept.isEmpty() -> {
                changed = true
                null
            }
            else -> {
                changed = true
                segment.copy(efforts = kept)
            }
        }
    }
    val candidates = registry.candidates.filter { it.rideId in rideIds }
    if (candidates.size != registry.candidates.size) changed = true
    val processed = registry.processed.filterKeys { it in rideIds }
    if (processed.size != registry.processed.size) changed = true

    return if (changed) SegmentRegistry(segments, candidates, processed) else registry
}

/**
 * Welche Touren (neu) eingerechnet werden muessen: gefahrene Touren mit
 * Punkten, deren `updatedAt` die Registry nicht (mehr) kennt — aufsteigend
 * nach Aufnahmezeit, damit „neue Bestzeit" auch beim Nachrechnen eines
 * Bestands chronologisch stimmt.
 */
fun <T : RideInfo> ridesNeedingSegmentUpdate(registry: SegmentRegistry, rides: List<T>): List<T> =
    riddenRides(rides)
        .filter { it.pointCount >= 2 && registry.processed[it.id] != it.updatedAt }
        .sortedBy { it.createdAt }

// ---------------------------------------------------------------------------
// Sichten fuer die UI
// ---------------------------------------------------------------------------

/**
 * Alle Befahrungen der Tour [rideId], je eine Sicht pro Runde, in
 * Fahrreihenfolge. Platz ([SegmentEffortView.rank]) zaehlt ueber **alle**
 * Befahrungen des Segments (Gleichstand: die fruehere Fahrt liegt vorn).
 */
fun segmentEffortsForRide(registry: SegmentRegistry, rideId: String): List<SegmentEffortView> {
    val views = mutableListOf<SegmentEffortView>()
    for (segment in registry.segments) {
        val own = segment.efforts.filter { it.rideId == rideId }
        if (own.isEmpty()) continue
        val ranking = segment.efforts.sortedWith(compareBy({ it.timeS }, { it.startedAt }))
        val bestTimeS = ranking.first().timeS
        for (effort in own) {
            val earlierBest = segment.efforts
                .filter { it !== effort && it.startedAt < effort.startedAt }
                .minOfOrNull { it.timeS }
            views += SegmentEffortView(
                segmentId = segment.id,
                name = segment.name,
                distanceM = segment.distanceM,
                ascentM = segment.ascentM,
                startedAt = effort.startedAt,
                timeS = effort.timeS,
                bestTimeS = bestTimeS,
                effortCount = segment.efforts.size,
                rank = ranking.indexOfFirst { it === effort } + 1,
                deltaToBestS = effort.timeS - bestTimeS,
                isNewBest = earlierBest != null && effort.timeS < earlierBest,
                avgHr = effort.avgHr,
            )
        }
    }
    return views.sortedBy { it.startedAt }
}

/**
 * Namensvorschlag eines neuen Segments: „Anstieg 4,2 km / 180 Hm" bzw.
 * „Anstieg 800 m / 45 Hm" unterhalb eines Kilometers. Deutsche
 * Dezimalschreibweise wie ueberall in der UI; umbenennen laesst sich in v1
 * nicht — der Vorschlag muss allein tragen.
 */
fun suggestSegmentName(distanceM: Double, ascentM: Double): String {
    val hm = ascentM.roundToInt()
    return if (distanceM >= 1000.0) {
        val km = java.math.BigDecimal.valueOf(distanceM / 1000)
            .setScale(1, java.math.RoundingMode.HALF_UP)
            .toPlainString()
            .replace('.', ',')
        "Anstieg $km km / $hm Hm"
    } else {
        val m = (distanceM / 10).roundToInt() * 10
        "Anstieg $m m / $hm Hm"
    }
}

// ---------------------------------------------------------------------------
// Hilfen
// ---------------------------------------------------------------------------

/**
 * Stabile Segment-ID aus dem gerundeten Referenzpfad — deterministisch, damit
 * derselbe Pfad auf jedem Geraet dieselbe ID ergibt. Kollisionen im Bestand
 * (theoretisch) loest ein Zaehler-Suffix.
 */
internal fun stableSegmentId(path: List<TrackPoint>): String {
    var h = 1125899906842597L
    for (p in path) {
        h = 31 * h + (p.lat * 1e5).roundToLong()
        h = 31 * h + (p.lon * 1e5).roundToLong()
    }
    return "seg-${java.lang.Long.toHexString(h)}"
}

private fun uniqueSegmentId(path: List<TrackPoint>, taken: Set<String>): String {
    val base = stableSegmentId(path)
    if (base !in taken) return base
    var n = 2
    while ("$base-$n" in taken) n++
    return "$base-$n"
}

/**
 * Referenzpfad als flaches JSON-Zahlenfeld `[lat0, lon0, lat1, lon1, …]` —
 * kompakter als ein Objekt je Punkt, und die Punkte tragen ohnehin nur
 * Koordinaten.
 */
private fun encodeSegmentPath(path: List<TrackPoint>): JsonArray = buildJsonArray {
    path.forEach {
        add(JsonPrimitive(roundCoordinate(it.lat)))
        add(JsonPrimitive(roundCoordinate(it.lon)))
    }
}

private fun decodeSegmentPath(array: JsonArray): List<TrackPoint> {
    val out = ArrayList<TrackPoint>(array.size / 2)
    var i = 0
    while (i + 1 < array.size) {
        val lat = (array[i] as? JsonPrimitive)?.doubleOrNull
        val lon = (array[i + 1] as? JsonPrimitive)?.doubleOrNull
        if (lat == null || lon == null) {
            throw MissingOrInvalidFieldException("Segmentpfad enthaelt ungueltige Koordinaten")
        }
        out += TrackPoint(lat = lat, lon = lon)
        i += 2
    }
    return out
}
