package de.trailscape.core.indoor

import kotlin.math.atan2
import kotlin.math.cos

/**
 * # Die Strecke einer Indoor-Fahrt
 *
 * VERTRAGSDATEI (docs/indoor.md, Abschnitt 4). Besitzer: Slice indoor-core.
 * Typen und Signaturen sind eingefroren.
 *
 * Eine [IndoorRoute] ist die Strecke, **wie die Sitzung und der Weltbau sie
 * brauchen**: gleichmaessig abgetastet, lueckenlos mit Hoehe und mit einer
 * Belagsklasse je Laufmeter. Sie entsteht aus einer geplanten Runde oder einer
 * gefahrenen Tour (`buildIndoorRoute` in `IndoorCoreEntry.kt`) — oder, sobald
 * eine Welt gebaut ist, aus deren Streckenband (`WorldTrack.toIndoorRoute`),
 * damit das gefuehlte Gefaelle dem gezeichneten entspricht.
 *
 * ## Zusicherungen (wer eine IndoorRoute baut, haelt sie ein)
 *  * mindestens zwei Punkte; `points[0].distM == 0.0`; `distM` streng
 *    steigend; der Abstand benachbarter Punkte liegt zwischen 1 m und 15 m;
 *  * jede Hoehe ist endlich (fehlende Hoehen sind interpoliert, eine Strecke
 *    ganz ohne Hoehen ist flach auf 0 m) und geglaettet — die Steigung
 *    zwischen zwei Punkten liegt in [MIN_GRADE_PCT]..[MAX_GRADE_PCT];
 *  * [surfaces] deckt `0.0 .. lengthM` lueckenlos und ueberlappungsfrei in
 *    Fahrtrichtung ab (`surfaces[i].toM == surfaces[i+1].fromM`); gibt es
 *    keine Belagsdaten, ist es ein einziger Abschnitt [SurfaceKind.UNKNOWN];
 *  * **Laufmeter sind unantastbar**: Wer die Linie glaettet oder in die Welt
 *    einpasst (Weltbau), aendert Lage und Hoehe der Punkte, nie ihr `distM`.
 *
 * @property id stabil fuer dieselbe Geometrie (Hash) — Teil der Welt-ID.
 * @property name Anzeigename (Name der Tour oder der Runde), darf leer sein.
 * @property isLoop Start und Ziel liegen hoechstens [LOOP_CLOSE_M] auseinander;
 *   die Sitzung faehrt dann nach dem Ziel in die naechste Runde.
 */
data class IndoorRoute(
    val id: String,
    val name: String,
    val points: List<IndoorRoutePoint>,
    val surfaces: List<SurfaceSection>,
    val isLoop: Boolean,
    val source: IndoorRouteSource,
) {
    /** Laenge einer Runde in Metern. */
    val lengthM: Double get() = points.last().distM

    companion object {
        const val MIN_GRADE_PCT: Double = -25.0
        const val MAX_GRADE_PCT: Double = 25.0

        /** Start–Ziel-Abstand, bis zu dem eine Strecke als Runde gilt. */
        const val LOOP_CLOSE_M: Double = 60.0
    }
}

/** Ein Punkt der Strecke: Laufmeter ab Start, Lage (WGS84) und Hoehe in Metern ueber dem Meer. */
data class IndoorRoutePoint(
    val distM: Double,
    val lat: Double,
    val lon: Double,
    val eleM: Double,
)

/**
 * Ein Wegabschnitt mit einheitlichem Belag, von [fromM] (einschliesslich) bis
 * [toM] (ausschliesslich) Laufmetern.
 *
 * @property highway der OSM-`highway`-Wert, wenn bekannt (`track`, `path`,
 *   `residential` …) — der Weltbau waehlt daraus Breite und Randgestaltung.
 * @property widthM gezeichnete Wegbreite; `null` = [SurfaceKind.defaultWidthM].
 */
data class SurfaceSection(
    val fromM: Double,
    val toM: Double,
    val kind: SurfaceKind,
    val highway: String? = null,
    val widthM: Double? = null,
) {
    val effectiveWidthM: Double get() = widthM ?: kind.defaultWidthM
}

/** Woher die Belagsklassen einer [IndoorRoute] stammen — fuer die Anzeige („Belag geschaetzt"). */
enum class IndoorRouteSource {
    /** BRouters Wegmerkmale der geplanten Route (`PlannedRoute.waySections`). */
    BROUTER_WAY_TAGS,

    /** Nachtraeglicher Abgleich einer gespeicherten Tour ueber BRouter (Route entlang der Spur). */
    BROUTER_REMATCH,

    /** Aus den Vektorkacheln der Welt geschaetzt (nur grob, siehe [surfaceKindFromVectorTile]). */
    VECTOR_TILES,

    /** Keine Belagsdaten: alles [SurfaceKind.UNKNOWN]. */
    NONE,
}

/**
 * Was an einer Stelle der Strecke gilt.
 *
 * @property gradePct Steigung in Prozent (positiv = bergauf) des Stuecks, in
 *   dem die Stelle liegt.
 * @property headingDeg Fahrtrichtung in Grad, 0 = Nord, 90 = Ost.
 */
data class RouteSample(
    val distM: Double,
    val lat: Double,
    val lon: Double,
    val eleM: Double,
    val gradePct: Double,
    val headingDeg: Double,
    val surface: SurfaceSection,
)

/**
 * Liest die Strecke an der Stelle [distM] ab (linear zwischen den Punkten).
 *
 * Auf einer Runde ([IndoorRoute.isLoop]) wird [distM] modulo der Rundenlaenge
 * genommen, sonst auf `0 .. lengthM` begrenzt. Das zurueckgegebene
 * [RouteSample.distM] ist die so gefaltete Stelle innerhalb der Runde.
 */
fun IndoorRoute.sampleAt(distM: Double): RouteSample {
    val len = lengthM
    val d = when {
        !distM.isFinite() -> 0.0
        isLoop && len > 0 -> ((distM % len) + len) % len
        else -> distM.coerceIn(0.0, len)
    }
    // Erster Punkt mit distM > d; das Stueck davor enthaelt d.
    var lo = 0
    var hi = points.size - 1
    while (hi - lo > 1) {
        val mid = (lo + hi) ushr 1
        if (points[mid].distM <= d) lo = mid else hi = mid
    }
    val a = points[lo]
    val b = points[hi]
    val span = b.distM - a.distM
    val t = if (span > 0) ((d - a.distM) / span).coerceIn(0.0, 1.0) else 0.0
    val grade = if (span > 0) (b.eleM - a.eleM) / span * 100.0 else 0.0
    val north = b.lat - a.lat
    val east = (b.lon - a.lon) * cos(Math.toRadians((a.lat + b.lat) / 2))
    val heading = (Math.toDegrees(atan2(east, north)) + 360.0) % 360.0
    return RouteSample(
        distM = d,
        lat = a.lat + (b.lat - a.lat) * t,
        lon = a.lon + (b.lon - a.lon) * t,
        eleM = a.eleM + (b.eleM - a.eleM) * t,
        gradePct = grade,
        headingDeg = heading,
        surface = surfaceAt(d),
    )
}

/** Der Belagsabschnitt an der (bereits gefalteten) Stelle [distM]; ausserhalb gilt der erste bzw. letzte. */
fun IndoorRoute.surfaceAt(distM: Double): SurfaceSection {
    var lo = 0
    var hi = surfaces.size - 1
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (surfaces[mid].toM <= distM) lo = mid + 1 else hi = mid
    }
    return surfaces[lo]
}
