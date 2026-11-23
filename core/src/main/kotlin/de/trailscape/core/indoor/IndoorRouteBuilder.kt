package de.trailscape.core.indoor

import de.trailscape.core.RouteWaySection
import de.trailscape.core.TrackPoint
import de.trailscape.core.Waypoint
import de.trailscape.core.haversineM
import de.trailscape.core.parseWayTags
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * # Strecke aus Punkten und Wegabschnitten
 *
 * Umsetzung von `buildIndoorRoute` und `surfaceRematchWaypoints`
 * (`IndoorCoreEntry.kt`, Vertrag docs/indoor.md Abschnitt 4). Rein, ohne
 * Zustand, ohne Uhr.
 *
 * ## Ablauf von [buildIndoorRouteImpl]
 *  1. **Bereinigen**: Punkte ohne endliche oder ausserhalb des WGS84-Bereichs
 *     liegende Lage fallen weg; ein Punkt naeher als [DUPLICATE_M] am zuletzt
 *     behaltenen wird mit ihm zusammengefasst (Stillstand, doppelte
 *     Nahtpunkte). Die Lage ausserhalb des Bereichs zu verwerfen geht ueber den
 *     Entwurf („endlich“) hinaus — ein Breitengrad 95 ist genauso unbrauchbar
 *     wie NaN.
 *  2. **Hoehen ergaenzen**: Fehlende oder nicht endliche Hoehen werden ueber
 *     die Laufmeter linear aus den Nachbarn mit Hoehe interpoliert, am Rand
 *     gilt der naechste bekannte Wert; eine Spur ganz ohne Hoehe liegt auf 0 m.
 *  3. **Neu abtasten**: `n = ceil(L / 10 m)` gleiche Schritte der Laenge `L/n`.
 *     Ab 200 m Laenge liegt der Schritt damit sicher in (5 m, 10 m] — innerhalb
 *     der zugesicherten 1–15 m. Lage und Hoehe linear entlang der Polylinie;
 *     die Laufmeter sind die Bogenlaenge der Originalspur (Ecken werden nicht
 *     eigens erhalten, bei 10 m Schritt ist der Fehler klein).
 *  4. **Glaetten**: gleitendes Mittel ueber rund [SMOOTH_WINDOW_M] Laufmeter
 *     (GPS- und DEM-Rauschen erzeugt sonst auf 10 m Stuecke Steigungen von
 *     20 % und mehr, die es nicht gibt); am Rand schrumpft das Fenster.
 *  5. **Steigung begrenzen**: vorwaerts kumulativ — jede Hoehe hoechstens
 *     `25 % × Schritt` ueber oder unter der vorigen. Der Grenzwert liegt einen
 *     Hauch ([GRADE_SAFETY]) unter 25 %, damit Rundungsfehler ihn beim
 *     Nachrechnen nicht reissen; eine zweite Runde prueft das ausdruecklich.
 *     Folge: Eine echte Wand von 30 % verschiebt die Hoehen dahinter um den
 *     abgeschnittenen Teil — fuer das Fahrgefuehl zaehlt die Steigung, nicht
 *     die absolute Hoehe.
 *  6. **Belag**: siehe [buildSurfaces].
 *  7. `isLoop`, `id` siehe dort.
 */

/** Punkte naeher als das gelten als derselbe Punkt. */
internal const val DUPLICATE_M: Double = 0.5

/** Kuerzeste fahrbare Strecke. */
internal const val MIN_ROUTE_LENGTH_M: Double = 200.0

/** Zielabstand der neu abgetasteten Punkte. */
internal const val RESAMPLE_TARGET_M: Double = 10.0

/** Breite des gleitenden Mittels der Hoehen in Laufmetern. */
internal const val SMOOTH_WINDOW_M: Double = 50.0

/** Abschnitte kuerzer als das (nach dem Strecken) entfallen. */
internal const val MIN_SURFACE_SECTION_M: Double = 0.01

/** Relativer Sicherheitsabstand zur Steigungsgrenze, siehe Datei-KDoc. */
private const val GRADE_SAFETY: Double = 1e-9

/**
 * Hoechstzahl der Stuetzpunkte fuer den Belagsabgleich.
 *
 * Weder unsere Anfrage-Planung ([de.trailscape.core.planRouteLegs]) noch die
 * BRouter-Engine (Server wie eingebettet) kennen eine harte Grenze fuer
 * Wegpunkte je Anfrage. Begrenzend sind die URL-Laenge (je Punkt rund 22
 * Zeichen, 150 Punkte ≈ 3,3 kB — weit unter den ueblichen 8 kB) und die
 * Rechenzeit, die mit jedem Via-Punkt einen eigenen Suchlauf bekommt. 150 ist
 * der Wert aus dem Entwurf; begruendete Annahme, auf dem Server noch zu pruefen.
 */
internal const val MAX_REMATCH_WAYPOINTS: Int = 150

/** Regelabstand der Stuetzpunkte fuer den Abgleich. */
internal const val REMATCH_SPACING_M: Double = 300.0

/** Ab diesem Richtungswechsel (Grad) bekommt eine Kurve einen eigenen Stuetzpunkt. */
internal const val REMATCH_TURN_DEG: Double = 60.0

/** Strecke vor und nach einem Punkt, ueber die die Richtung gemessen wird. */
internal const val REMATCH_TURN_ARM_M: Double = 30.0

// ---------------------------------------------------------------------------
// Bereinigte Spur
// ---------------------------------------------------------------------------

/** Ein Punkt der bereinigten Spur mit kumulierter Laenge. */
private class CleanPoint(val lat: Double, val lon: Double, var ele: Double?, val cumM: Double)

private fun validLatLon(p: TrackPoint): Boolean =
    p.lat.isFinite() && p.lon.isFinite() && abs(p.lat) <= 90.0 && abs(p.lon) <= 180.0

/**
 * Verwirft unbrauchbare Lagen, fasst Duplikate (< [DUPLICATE_M] zum zuletzt
 * behaltenen Punkt) zusammen und rechnet die kumulierte Laenge per
 * [haversineM]. Traegt der behaltene Punkt keine Hoehe, das Duplikat aber
 * schon, wird dessen Hoehe uebernommen.
 */
private fun cleanTrack(points: List<TrackPoint>): List<CleanPoint> {
    val out = ArrayList<CleanPoint>(points.size)
    var last: TrackPoint? = null
    for (p in points) {
        if (!validLatLon(p)) continue
        val ele = p.ele?.takeIf { it.isFinite() }
        val prev = last
        if (prev == null) {
            out.add(CleanPoint(p.lat, p.lon, ele, 0.0))
            last = p
            continue
        }
        val d = haversineM(prev, p)
        if (d < DUPLICATE_M) {
            val kept = out.last()
            if (kept.ele == null && ele != null) kept.ele = ele
            continue
        }
        out.add(CleanPoint(p.lat, p.lon, ele, out.last().cumM + d))
        last = p
    }
    return out
}

// ---------------------------------------------------------------------------
// buildIndoorRoute
// ---------------------------------------------------------------------------

/**
 * Umsetzung von `buildIndoorRoute` (Vertrag und Zusicherungen siehe
 * `IndoorCoreEntry.kt` und `IndoorRoute.kt`, Ablauf im Datei-KDoc).
 *
 * @return `null` bei weniger als zwei verschiedenen Punkten oder einer
 *   Laenge unter [MIN_ROUTE_LENGTH_M].
 */
internal fun buildIndoorRouteImpl(
    name: String,
    points: List<TrackPoint>,
    waySections: List<RouteWaySection>?,
    source: IndoorRouteSource,
): IndoorRoute? {
    val clean = cleanTrack(points)
    if (clean.size < 2) return null
    val lengthM = clean.last().cumM
    if (!lengthM.isFinite() || lengthM < MIN_ROUTE_LENGTH_M) return null

    fillElevations(clean)

    // Neu abtasten.
    val n = ceil(lengthM / RESAMPLE_TARGET_M).toInt().coerceAtLeast(1)
    val dists = DoubleArray(n + 1) { k -> if (k == n) lengthM else lengthM * k / n }
    val lats = DoubleArray(n + 1)
    val lons = DoubleArray(n + 1)
    val eles = DoubleArray(n + 1)
    var seg = 0
    for (k in 0..n) {
        val d = dists[k]
        while (seg < clean.size - 2 && clean[seg + 1].cumM <= d) seg++
        val a = clean[seg]
        val b = clean[seg + 1]
        val span = b.cumM - a.cumM
        val t = if (span > 0) ((d - a.cumM) / span).coerceIn(0.0, 1.0) else 0.0
        lats[k] = a.lat + (b.lat - a.lat) * t
        lons[k] = a.lon + (b.lon - a.lon) * t
        eles[k] = a.ele!! + (b.ele!! - a.ele!!) * t
    }
    // Erster und letzter Punkt exakt wie die Spur.
    lats[0] = clean.first().lat
    lons[0] = clean.first().lon
    lats[n] = clean.last().lat
    lons[n] = clean.last().lon

    val step = lengthM / n
    val smoothed = smoothElevations(eles, step)
    val clamped = clampGrades(smoothed, dists)

    val routePoints = List(n + 1) { k -> IndoorRoutePoint(dists[k], lats[k], lons[k], clamped[k]) }
    val (surfaces, effectiveSource) = buildSurfaces(lengthM, waySections, source)
    val isLoop = haversineM(
        TrackPoint(lat = lats[0], lon = lons[0]),
        TrackPoint(lat = lats[n], lon = lons[n]),
    ) <= IndoorRoute.LOOP_CLOSE_M

    return IndoorRoute(
        id = geometryId(lats, lons),
        name = name,
        points = routePoints,
        surfaces = surfaces,
        isLoop = isLoop,
        source = effectiveSource,
    )
}

/** Ergaenzt fehlende Hoehen (Schritt 2 im Datei-KDoc); danach traegt jeder Punkt eine endliche Hoehe. */
private fun fillElevations(clean: List<CleanPoint>) {
    val known = clean.indices.filter { clean[it].ele != null }
    if (known.isEmpty()) {
        clean.forEach { it.ele = 0.0 }
        return
    }
    var ki = 0
    for (i in clean.indices) {
        if (clean[i].ele != null) continue
        while (ki < known.size && known[ki] < i) ki++
        val next = known.getOrNull(ki)
        val prev = known.getOrNull(ki - 1)
        clean[i].ele = when {
            prev == null -> clean[next!!].ele
            next == null -> clean[prev].ele
            else -> {
                val a = clean[prev]
                val b = clean[next]
                val span = b.cumM - a.cumM
                val t = if (span > 0) (clean[i].cumM - a.cumM) / span else 0.0
                a.ele!! + (b.ele!! - a.ele!!) * t
            }
        }
    }
}

/**
 * Gleitendes Mittel ueber [SMOOTH_WINDOW_M] (halbe Breite je Seite, in
 * Punkten bei gleichem Schritt [step]); am Rand schrumpft das Fenster
 * einseitig.
 */
internal fun smoothElevations(eles: DoubleArray, step: Double): DoubleArray {
    val size = eles.size
    val half = if (step > 0) (SMOOTH_WINDOW_M / 2 / step).roundToInt().coerceAtLeast(1) else 1
    val prefix = DoubleArray(size + 1)
    for (i in 0 until size) prefix[i + 1] = prefix[i] + eles[i]
    return DoubleArray(size) { i ->
        val lo = max(0, i - half)
        val hi = min(size - 1, i + half)
        (prefix[hi + 1] - prefix[lo]) / (hi - lo + 1)
    }
}

/**
 * Begrenzt die Steigung jedes Stuecks auf [IndoorRoute.MIN_GRADE_PCT] ..
 * [IndoorRoute.MAX_GRADE_PCT] (Schritt 5 im Datei-KDoc). [dists] streng
 * steigend.
 */
internal fun clampGrades(eles: DoubleArray, dists: DoubleArray): DoubleArray {
    val out = eles.copyOf()
    val up = IndoorRoute.MAX_GRADE_PCT / 100.0 * (1 - GRADE_SAFETY)
    val down = IndoorRoute.MIN_GRADE_PCT / 100.0 * (1 - GRADE_SAFETY)
    for (i in 1 until out.size) {
        val span = dists[i] - dists[i - 1]
        out[i] = out[i].coerceIn(out[i - 1] + down * span, out[i - 1] + up * span)
    }
    // Nachpruefen: Was die Rundung trotzdem ueber die Grenze schiebt, wird
    // Ulp fuer Ulp zurueckgeholt (praktisch nie noetig).
    for (i in 1 until out.size) {
        val span = dists[i] - dists[i - 1]
        while ((out[i] - out[i - 1]) / span * 100.0 > IndoorRoute.MAX_GRADE_PCT) out[i] = Math.nextDown(out[i])
        while ((out[i] - out[i - 1]) / span * 100.0 < IndoorRoute.MIN_GRADE_PCT) out[i] = Math.nextUp(out[i])
    }
    return out
}

/**
 * Belagsabschnitte zur Linie der Laenge [lengthM].
 *
 * Die [waySections] werden anteilig auf die Linienlaenge gestreckt (Faktor
 * `lengthM / Summe`): Bei einer geplanten Runde ist der Faktor ~1, beim
 * Abgleich einer gefahrenen Tour folgt der Router der Spur nur ungefaehr.
 * Je Abschnitt [surfaceKindFromTags] und `highway`; Abschnitte unter
 * [MIN_SURFACE_SECTION_M] entfallen (der naechste beginnt dann, wo der
 * vorige endete), gleichartige Nachbarn (gleiche Klasse **und** gleiche
 * Wegart) werden zusammengefasst; der erste beginnt bei 0, der letzte endet
 * exakt bei [lengthM].
 *
 * Ohne verwertbare Abschnitte — `null`, leer, Summe <= 0 oder **eine**
 * nicht endliche bzw. negative Laenge (alles oder nichts wie in
 * `RouteSurface.kt`; eine Annahme ueber den Entwurf hinaus, `waySectionsFromMessages`
 * liefert so etwas nie) — gibt es einen Abschnitt [SurfaceKind.UNKNOWN] und
 * die Quelle [IndoorRouteSource.NONE]. Sonst bleibt [source] wie uebergeben,
 * auch wenn das `NONE` ist.
 */
internal fun buildSurfaces(
    lengthM: Double,
    waySections: List<RouteWaySection>?,
    source: IndoorRouteSource,
): Pair<List<SurfaceSection>, IndoorRouteSource> {
    val none = listOf(SurfaceSection(0.0, lengthM, SurfaceKind.UNKNOWN)) to IndoorRouteSource.NONE
    if (waySections.isNullOrEmpty()) return none
    if (waySections.any { !it.lengthM.isFinite() || it.lengthM < 0 }) return none
    val sum = waySections.sumOf { it.lengthM }
    if (!sum.isFinite() || sum <= 0) return none
    val factor = lengthM / sum

    val out = ArrayList<SurfaceSection>()
    var cum = 0.0
    for (s in waySections) {
        cum += s.lengthM
        val to = min(cum * factor, lengthM)
        val from = out.lastOrNull()?.toM ?: 0.0
        if (to - from < MIN_SURFACE_SECTION_M) continue
        val tags = parseWayTags(s.wayTags)
        val kind = surfaceKindFromTags(tags)
        val highway = tags["highway"]
        val prev = out.lastOrNull()
        if (prev != null && prev.kind == kind && prev.highway == highway) {
            out[out.size - 1] = prev.copy(toM = to)
        } else {
            out.add(SurfaceSection(fromM = from, toM = to, kind = kind, highway = highway, widthM = null))
        }
    }
    if (out.isEmpty()) return none
    out[out.size - 1] = out.last().copy(toM = lengthM)
    return out to source
}

/**
 * Stabile Kennung der Geometrie: SHA-256 ueber die auf 5 Nachkommastellen
 * (rund 1 m) gerundeten Lagen der neu abgetasteten Punkte, die ersten 16
 * Hex-Zeichen mit Praefix `r`. Der Name geht bewusst nicht ein — eine
 * umbenannte Tour behaelt ihre Welt.
 */
private fun geometryId(lats: DoubleArray, lons: DoubleArray): String {
    val sb = StringBuilder(lats.size * 22)
    for (i in lats.indices) {
        sb.append(String.format(Locale.ROOT, "%.5f,%.5f;", lats[i], lons[i]))
    }
    val digest = MessageDigest.getInstance("SHA-256").digest(sb.toString().toByteArray(Charsets.UTF_8))
    return "r" + digest.joinToString("") { "%02x".format(it) }.take(16)
}

// ---------------------------------------------------------------------------
// surfaceRematchWaypoints
// ---------------------------------------------------------------------------

/** Anfangsrichtung (Grad, 0 = Nord) von [a] nach [b]. */
private fun bearingDeg(a: CleanPoint, b: CleanPoint): Double {
    val lat1 = Math.toRadians(a.lat)
    val lat2 = Math.toRadians(b.lat)
    val dLon = Math.toRadians(b.lon - a.lon)
    val y = sin(dLon) * cos(lat2)
    val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
    return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
}

/** Betrag des kleineren Winkels zwischen zwei Richtungen, 0..180. */
private fun angleDiffDeg(a: Double, b: Double): Double {
    val d = abs(a - b) % 360.0
    return if (d > 180.0) 360.0 - d else d
}

/**
 * Umsetzung von `surfaceRematchWaypoints`.
 *
 * ## Warum so
 * Der Router soll der Spur folgen, nicht den kuerzesten Weg zwischen Start
 * und Ziel suchen. Zwischen zwei Stuetzpunkten im Abstand von rund
 * [REMATCH_SPACING_M] gibt es selten eine kuerzere Alternative als den
 * gefahrenen Weg; viel enger braucht es nicht und kostet Rechenzeit je
 * Via-Punkt. Deshalb:
 *  * Start, Ziel und dazwischen gleichmaessig verteilt rund alle 300 m ein
 *    Punkt, jeweils auf den **echten** Spurpunkt mit der naechstliegenden
 *    kumulierten Laenge gesetzt (ein interpolierter Punkt laege in Kurven
 *    neben dem Weg).
 *  * Ist die Spur so lang, dass das mehr als [MAX_REMATCH_WAYPOINTS] ergaebe,
 *    waechst der Abstand gleichmaessig (`L / (MAX − 1)`).
 *  * Zusaetzlich an **starken Richtungswechseln** (mehr als
 *    [REMATCH_TURN_DEG] zwischen der Richtung der [REMATCH_TURN_ARM_M] davor
 *    und danach): Kehren, Stichwege und Wendepunkte, die zwischen zwei
 *    Regelpunkten laegen, wuerde der Router sonst abkuerzen. Je Kurve nur der
 *    schaerfste Punkt; nicht naeher als [REMATCH_TURN_ARM_M] an einem schon
 *    gesetzten Punkt; die schaerfsten zuerst, solange die Obergrenze haelt.
 *
 * @return leer bei weniger als zwei verschiedenen Punkten oder einer Laenge
 *   unter [MIN_ROUTE_LENGTH_M].
 */
internal fun surfaceRematchWaypointsImpl(points: List<TrackPoint>): List<Waypoint> {
    val clean = cleanTrack(points)
    if (clean.size < 2) return emptyList()
    val lengthM = clean.last().cumM
    if (!lengthM.isFinite() || lengthM < MIN_ROUTE_LENGTH_M) return emptyList()

    // Regelpunkte: n Stuecke, n + 1 Punkte <= MAX.
    val n = ceil(lengthM / REMATCH_SPACING_M).toInt().coerceIn(1, MAX_REMATCH_WAYPOINTS - 1)
    val chosen = sortedSetOf(0, clean.size - 1)
    var idx = 0
    for (k in 1 until n) {
        val target = lengthM * k / n
        while (idx < clean.size - 1 && clean[idx + 1].cumM <= target) idx++
        val best = if (idx + 1 < clean.size &&
            abs(clean[idx + 1].cumM - target) < abs(clean[idx].cumM - target)
        ) {
            idx + 1
        } else {
            idx
        }
        chosen.add(best)
    }

    // Kurven: Richtungswechsel je Punkt.
    val turn = DoubleArray(clean.size)
    var back = 0
    var fwd = 0
    for (i in clean.indices) {
        while (back + 1 < i && clean[i].cumM - clean[back + 1].cumM >= REMATCH_TURN_ARM_M) back++
        if (fwd < i) fwd = i
        while (fwd < clean.size - 1 && clean[fwd].cumM - clean[i].cumM < REMATCH_TURN_ARM_M) fwd++
        val armBack = clean[i].cumM - clean[back].cumM
        val armFwd = clean[fwd].cumM - clean[i].cumM
        if (back >= i || fwd <= i || armBack < REMATCH_TURN_ARM_M || armFwd < REMATCH_TURN_ARM_M) continue
        turn[i] = angleDiffDeg(bearingDeg(clean[back], clean[i]), bearingDeg(clean[i], clean[fwd]))
    }
    // Je Kurve (zusammenhaengende Folge ueber der Schwelle) der schaerfste Punkt.
    val peaks = ArrayList<Int>()
    var i = 0
    while (i < clean.size) {
        if (turn[i] <= REMATCH_TURN_DEG) {
            i++
            continue
        }
        var best = i
        while (i < clean.size && turn[i] > REMATCH_TURN_DEG) {
            if (turn[i] > turn[best]) best = i
            i++
        }
        peaks.add(best)
    }
    peaks.sortByDescending { turn[it] }
    for (p in peaks) {
        if (chosen.size >= MAX_REMATCH_WAYPOINTS) break
        val near = chosen.any { abs(clean[it].cumM - clean[p].cumM) < REMATCH_TURN_ARM_M }
        if (!near) chosen.add(p)
    }

    return chosen.map { Waypoint(lat = clean[it].lat, lon = clean[it].lon) }
}
