package de.trailscape.core.indoor

import de.trailscape.core.RouteWaySection
import de.trailscape.core.TrackPoint
import de.trailscape.core.Waypoint
import de.trailscape.core.haversineM
import de.trailscape.core.parseWayTags
import java.security.MessageDigest
import java.util.Locale
import java.util.TreeSet
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.min
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
 *  3. **Neu abtasten**: Starke Ecken der Spur (Richtungswechsel ueber
 *     [SNAP_TURN_DEG], gemessen ueber je [SNAP_ARM_M] davor und danach — vor
 *     allem Wenden auf Stichwegen) werden als Abtastpunkte **eingerastet**.
 *     Ohne das koennte eine Wende zwischen zwei Abtastpunkten liegen: Beide
 *     laegen dann an derselben Stelle (Richtung undefiniert, Stueck der
 *     Laenge 0) und die Spitze waere abgeschnitten. Eingerastet wird jeder
 *     Spurpunkt ueber der Schwelle (an einer Wende ist der Winkel ueber die
 *     Arme auf mehreren Punkten gleich gross, der Gipfel allein traefe nicht
 *     sicher die Spitze); liegen zwei naeher als [MIN_ANCHOR_GAP_M]
 *     beieinander, gewinnt der schaerfere Knick direkt am Punkt. Zwischen zwei
 *     Ankern `ceil(Stueck / 10 m)` gleiche Schritte; Anker naeher als
 *     [MIN_ANCHOR_GAP_M] an Start/Ziel entfallen, so
 *     liegt jeder Schritt in [2 m, 10 m] — innerhalb der zugesicherten 1–15 m.
 *     Lage und Hoehe linear entlang der Polylinie; der Laengenunterschied
 *     wird ueber die Datumsgrenze hinweg auf ±180° genommen. Die Laufmeter
 *     sind die Bogenlaenge der Originalspur.
 *  4. **Glaetten**: gleitendes Mittel ueber [SMOOTH_WINDOW_M] Laufmeter
 *     (GPS- und DEM-Rauschen erzeugt sonst auf 10 m Stuecke Steigungen von
 *     20 % und mehr, die es nicht gibt), als Integral der stueckweise
 *     linearen Hoehe — damit bleibt eine gleichmaessige Steigung exakt
 *     erhalten, auch bei ungleichen Schritten. Offene Strecke: das Fenster
 *     schrumpft am Rand **symmetrisch** (Start und Ziel bleiben, die
 *     Steigung dort wird nicht halbiert). Runde: zyklisch ueber die
 *     Start-Ziel-Naht; Start- und Zielhoehe sind danach gleich (vorher auf
 *     ihr Mittel gesetzt), es gibt keinen Sprung an der Naht.
 *  5. **Steigung begrenzen**: die hoechste Hoehenkurve **unterhalb** der
 *     geglaetteten, deren Steigung ueberall in −25..+25 % liegt (Vorwaerts-
 *     und Rueckwaertsdurchlauf; auf einer Runde zyklisch). Steile Stuecke
 *     werden also von oben abgetragen: Kuppen und Plateaus neben einer Wand
 *     sinken, bis die Rampe mit 25 % hinauf- bzw. hinabfuehrt; Taeler und
 *     alles, was die Grenze schon einhaelt, bleiben unveraendert, und keine
 *     Hoehe faellt unter das Minimum der Eingabe. Der Grenzwert liegt einen
 *     Hauch ([GRADE_SAFETY]) unter 25 %, damit Rundungsfehler ihn beim
 *     Nachrechnen nicht reissen; eine zweite Runde prueft das ausdruecklich.
 *  6. **Belag**: siehe [buildSurfaces].
 *  7. `isLoop`, `id` siehe dort.
 */

/** Punkte naeher als das gelten als derselbe Punkt. */
internal const val DUPLICATE_M: Double = 0.5

/** Kuerzeste fahrbare Strecke. */
internal const val MIN_ROUTE_LENGTH_M: Double = 200.0

/** Zielabstand der neu abgetasteten Punkte. */
internal const val RESAMPLE_TARGET_M: Double = 10.0

/** Ab diesem Richtungswechsel (Grad) wird eine Ecke als Abtastpunkt eingerastet. */
internal const val SNAP_TURN_DEG: Double = 45.0

/** Strecke vor und nach einem Spurpunkt, ueber die der Richtungswechsel fuers Einrasten gemessen wird. */
internal const val SNAP_ARM_M: Double = 5.0

/** Mindestabstand eingerasteter Ecken untereinander und zu Start/Ziel. */
internal const val MIN_ANCHOR_GAP_M: Double = 2.0

/** Breite des gleitenden Mittels der Hoehen in Laufmetern. */
internal const val SMOOTH_WINDOW_M: Double = 50.0

/** Abschnitte kuerzer als das (nach dem Strecken) entfallen. */
internal const val MIN_SURFACE_SECTION_M: Double = 0.01

/** Erlaubter Streckfaktor (Linie / Abschnitte) beim Abgleich, siehe [buildSurfaces]. */
internal const val REMATCH_MIN_STRETCH: Double = 0.8
internal const val REMATCH_MAX_STRETCH: Double = 1.25

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

/**
 * Hoechstzahl der gleichmaessig verteilten Regelpunkte (samt Start und Ziel)
 * im ersten Durchgang; der Rest des Budgets bleibt fuer Kurven reserviert.
 */
internal const val MAX_REMATCH_REGULAR: Int = 100

/** Regelabstand der Stuetzpunkte fuer den Abgleich. */
internal const val REMATCH_SPACING_M: Double = 300.0

/** Ab diesem Richtungswechsel (Grad) bekommt eine Kurve einen eigenen Stuetzpunkt. */
internal const val REMATCH_TURN_DEG: Double = 60.0

/** Strecke vor und nach einem Punkt, ueber die die Richtung gemessen wird. */
internal const val REMATCH_TURN_ARM_M: Double = 30.0

/** Aufeinanderfolgende Stuetzpunkte naeher als das werden zusammengefasst. */
internal const val REMATCH_MERGE_M: Double = 2.0

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

/** Laengenunterschied auf −180..180 gebracht (kurzer Weg ueber die Datumsgrenze). */
private fun lonDelta(from: Double, to: Double): Double {
    var d = (to - from) % 360.0
    if (d > 180.0) d -= 360.0
    if (d < -180.0) d += 360.0
    return d
}

/** Laenge zurueck in −180..180. */
private fun normalizeLon(lon: Double): Double {
    var l = lon % 360.0
    if (l > 180.0) l -= 360.0
    if (l < -180.0) l += 360.0
    return l
}

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
 * Richtungswechsel je Spurpunkt in Grad: Richtung vom Punkt [arm] davor zum
 * Punkt gegen die vom Punkt zum Punkt [arm] danach. Mit [fullArms] nur, wo
 * beide Arme die volle Laenge haben (sonst 0); ohne reicht ein kuerzerer Arm
 * bis zum Start bzw. Ziel. Start und Ziel selbst haben 0.
 */
private fun turnAngles(clean: List<CleanPoint>, arm: Double, fullArms: Boolean): DoubleArray {
    val turn = DoubleArray(clean.size)
    var back = 0
    var fwd = 0
    for (i in 1 until clean.size - 1) {
        while (back + 1 < i && clean[i].cumM - clean[back + 1].cumM >= arm) back++
        if (fwd <= i) fwd = i + 1
        while (fwd < clean.size - 1 && clean[fwd].cumM - clean[i].cumM < arm) fwd++
        if (fullArms && (clean[i].cumM - clean[back].cumM < arm || clean[fwd].cumM - clean[i].cumM < arm)) continue
        turn[i] = angleDiffDeg(bearingDeg(clean[back], clean[i]), bearingDeg(clean[i], clean[fwd]))
    }
    return turn
}

/** Je zusammenhaengender Folge ueber [threshold] der Index mit dem groessten Wert. */
private fun turnPeaks(turn: DoubleArray, threshold: Double): List<Int> {
    val peaks = ArrayList<Int>()
    var i = 0
    while (i < turn.size) {
        if (turn[i] <= threshold) {
            i++
            continue
        }
        var best = i
        while (i < turn.size && turn[i] > threshold) {
            if (turn[i] > turn[best]) best = i
            i++
        }
        peaks.add(best)
    }
    return peaks
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

    val isLoop = haversineM(
        TrackPoint(lat = clean.first().lat, lon = clean.first().lon),
        TrackPoint(lat = clean.last().lat, lon = clean.last().lon),
    ) <= IndoorRoute.LOOP_CLOSE_M

    val dists = resampleDistances(clean, lengthM)
    val size = dists.size
    val lats = DoubleArray(size)
    val lons = DoubleArray(size)
    val eles = DoubleArray(size)
    var seg = 0
    for (k in 0 until size) {
        val d = dists[k]
        while (seg < clean.size - 2 && clean[seg + 1].cumM <= d) seg++
        val a = clean[seg]
        val b = clean[seg + 1]
        val span = b.cumM - a.cumM
        val t = if (span > 0) ((d - a.cumM) / span).coerceIn(0.0, 1.0) else 0.0
        lats[k] = a.lat + (b.lat - a.lat) * t
        lons[k] = normalizeLon(a.lon + lonDelta(a.lon, b.lon) * t)
        eles[k] = a.ele!! + (b.ele!! - a.ele!!) * t
    }
    // Erster und letzter Punkt exakt wie die Spur.
    lats[0] = clean.first().lat
    lons[0] = clean.first().lon
    lats[size - 1] = clean.last().lat
    lons[size - 1] = clean.last().lon

    val smoothed = smoothElevations(eles, dists, cyclic = isLoop)
    val clamped = clampGrades(smoothed, dists, cyclic = isLoop)

    val routePoints = List(size) { k -> IndoorRoutePoint(dists[k], lats[k], lons[k], clamped[k]) }
    val (surfaces, effectiveSource) = buildSurfaces(lengthM, waySections, source)

    return IndoorRoute(
        id = geometryId(lats, lons),
        name = name,
        points = routePoints,
        surfaces = surfaces,
        isLoop = isLoop,
        source = effectiveSource,
    )
}

/**
 * Laufmeter der neuen Punkte (Schritt 3 im Datei-KDoc): eingerastete Ecken
 * als Anker, dazwischen gleiche Schritte von hoechstens [RESAMPLE_TARGET_M].
 */
private fun resampleDistances(clean: List<CleanPoint>, lengthM: Double): DoubleArray {
    val turn = turnAngles(clean, SNAP_ARM_M, fullArms = false)
    // Knickwinkel direkt am Spurpunkt — entscheidet, welcher von mehreren
    // dicht beieinanderliegenden Kandidaten die eigentliche Spitze ist.
    fun localTurn(i: Int): Double =
        angleDiffDeg(bearingDeg(clean[i - 1], clean[i]), bearingDeg(clean[i], clean[i + 1]))

    // Alle Spurpunkte einer Ecke sind Kandidaten (bei Wenden ist der Winkel
    // ueber die Arme auf mehreren Punkten gleich gross); naeher als
    // MIN_ANCHOR_GAP_M beieinander gewinnt der schaerfere Knick.
    val anchorIdx = ArrayList<Int>()
    for (i in 1 until clean.size - 1) {
        if (turn[i] <= SNAP_TURN_DEG) continue
        val d = clean[i].cumM
        if (d < MIN_ANCHOR_GAP_M || lengthM - d < MIN_ANCHOR_GAP_M) continue
        val last = anchorIdx.lastOrNull()
        if (last == null || d - clean[last].cumM >= MIN_ANCHOR_GAP_M) {
            anchorIdx.add(i)
        } else if (localTurn(i) > localTurn(last)) {
            val before = anchorIdx.getOrNull(anchorIdx.size - 2)?.let { clean[it].cumM } ?: 0.0
            if (d - before >= MIN_ANCHOR_GAP_M) anchorIdx[anchorIdx.size - 1] = i
        }
    }
    val anchors = ArrayList<Double>()
    anchors.add(0.0)
    anchorIdx.forEach { anchors.add(clean[it].cumM) }
    anchors.add(lengthM)
    val out = ArrayList<Double>()
    for (j in 1 until anchors.size) {
        val a = anchors[j - 1]
        val b = anchors[j]
        val n = ceil((b - a) / RESAMPLE_TARGET_M).toInt().coerceAtLeast(1)
        for (k in 0 until n) out.add(a + (b - a) * k / n)
    }
    out.add(lengthM)
    return out.toDoubleArray()
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
 * Gleitendes Mittel ueber [SMOOTH_WINDOW_M] (Schritt 4 im Datei-KDoc):
 * Mittelwert der stueckweise linearen Hoehe ueber `[d − w, d + w]`, mit
 * `w = SMOOTH_WINDOW_M / 2`. Offen schrumpft `w` am Rand symmetrisch auf
 * `min(w, d, L − d)`; [cyclic] laeuft das Fenster ueber die Naht (erste und
 * letzte Hoehe werden vorher auf ihr Mittel gesetzt und sind danach gleich).
 * [dists] streng steigend, beginnt bei 0.
 */
internal fun smoothElevations(eles: DoubleArray, dists: DoubleArray, cyclic: Boolean): DoubleArray {
    val size = eles.size
    val e = eles.copyOf()
    if (cyclic) {
        val mean = (e[0] + e[size - 1]) / 2
        e[0] = mean
        e[size - 1] = mean
    }
    val len = dists[size - 1]
    // Stammfunktion an den Punkten.
    val prim = DoubleArray(size)
    for (i in 1 until size) prim[i] = prim[i - 1] + (dists[i] - dists[i - 1]) * (e[i] + e[i - 1]) / 2
    val total = prim[size - 1]

    // Stammfunktion an beliebiger Stelle 0..len.
    fun primAt(x: Double): Double {
        if (x <= 0.0) return 0.0
        if (x >= len) return total
        var lo = 0
        var hi = size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (dists[mid] <= x) lo = mid else hi = mid
        }
        val span = dists[hi] - dists[lo]
        val t = if (span > 0) (x - dists[lo]) / span else 0.0
        val ex = e[lo] + (e[hi] - e[lo]) * t
        return prim[lo] + (x - dists[lo]) * (e[lo] + ex) / 2
    }

    // Periodisch fortgesetzt (nur fuer die Runde).
    fun primCyclic(x: Double): Double {
        val k = Math.floor(x / len)
        return primAt(x - k * len) + k * total
    }

    val half = SMOOTH_WINDOW_M / 2
    val out = DoubleArray(size)
    for (i in 0 until size) {
        val d = dists[i]
        if (cyclic) {
            val w = min(half, len / 2)
            out[i] = (primCyclic(d + w) - primCyclic(d - w)) / (2 * w)
        } else {
            val w = min(half, min(d, len - d))
            out[i] = if (w <= 0.0) e[i] else (primAt(d + w) - primAt(d - w)) / (2 * w)
        }
    }
    if (cyclic) out[size - 1] = out[0]
    return out
}

/**
 * Begrenzt die Steigung jedes Stuecks auf [IndoorRoute.MIN_GRADE_PCT] ..
 * [IndoorRoute.MAX_GRADE_PCT] (Schritt 5 im Datei-KDoc): die groesste Kurve
 * `f <= eles` mit `|f[i] − f[j]| <= g · |d[i] − d[j]|`, berechnet als
 * Vorwaerts- und Rueckwaertsdurchlauf. [cyclic]: der letzte Punkt ist
 * derselbe wie der erste (gleiche Hoehe vorausgesetzt); die Durchlaeufe
 * laufen ueber drei aneinandergehaengte Runden, die mittlere ist exakt.
 * [dists] streng steigend.
 */
internal fun clampGrades(eles: DoubleArray, dists: DoubleArray, cyclic: Boolean = false): DoubleArray {
    val size = eles.size
    val g = IndoorRoute.MAX_GRADE_PCT / 100.0 * (1 - GRADE_SAFETY)
    val gDown = -IndoorRoute.MIN_GRADE_PCT / 100.0 * (1 - GRADE_SAFETY)
    val out: DoubleArray
    if (!cyclic || size < 3) {
        out = eles.copyOf()
        for (i in 1 until size) {
            out[i] = min(out[i], out[i - 1] + g * (dists[i] - dists[i - 1]))
        }
        for (i in size - 2 downTo 0) {
            out[i] = min(out[i], out[i + 1] + gDown * (dists[i + 1] - dists[i]))
        }
    } else {
        val m = size - 1 // Punkte einer Runde ohne den doppelten Schlusspunkt
        val step = DoubleArray(m) { dists[it + 1] - dists[it] } // step[k]: k → k+1 (zyklisch)
        val ext = DoubleArray(3 * m) { eles[it % m] }
        for (k in 1 until 3 * m) ext[k] = min(ext[k], ext[k - 1] + g * step[(k - 1) % m])
        for (k in 3 * m - 2 downTo 0) ext[k] = min(ext[k], ext[k + 1] + gDown * step[k % m])
        out = DoubleArray(size) { if (it < m) ext[m + it] else ext[m] }
    }
    // Nachpruefen: Was die Rundung trotzdem ueber die Grenze schiebt, wird
    // Ulp fuer Ulp zurueckgeholt (praktisch nie noetig; der Sicherheits-
    // abstand ist um Groessenordnungen groesser als die Rundung).
    val last = if (cyclic && size >= 3) size - 2 else size - 1
    for (i in 1..last) {
        val span = dists[i] - dists[i - 1]
        while ((out[i] - out[i - 1]) / span * 100.0 > IndoorRoute.MAX_GRADE_PCT) out[i] = Math.nextDown(out[i])
        while ((out[i] - out[i - 1]) / span * 100.0 < IndoorRoute.MIN_GRADE_PCT) out[i] = Math.nextUp(out[i])
    }
    if (cyclic && size >= 3) {
        // Naht: der Schlusspunkt bleibt gleich dem Start, notfalls rueckt der vorletzte.
        val i = size - 1
        val span = dists[i] - dists[i - 1]
        while ((out[i] - out[i - 1]) / span * 100.0 > IndoorRoute.MAX_GRADE_PCT) out[i - 1] = Math.nextUp(out[i - 1])
        while ((out[i] - out[i - 1]) / span * 100.0 < IndoorRoute.MIN_GRADE_PCT) out[i - 1] = Math.nextDown(out[i - 1])
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
 * **Abgleich nur mit plausibler Laenge**: Bei [IndoorRouteSource.BROUTER_REMATCH]
 * muss der Faktor in [REMATCH_MIN_STRETCH]..[REMATCH_MAX_STRETCH] liegen.
 * Weicht die Antwort des Routers um mehr als ein Viertel von der Spur ab, ist
 * er ihr nicht gefolgt (Umweg um eine fehlende Verbindung, Abkuerzung,
 * Spur abseits des Wegenetzes) — anteilig gestreckt laegen die Beläge dann an
 * falschen Stellen. Dann lieber keine Angabe: Die Quelle wird
 * [IndoorRouteSource.NONE], und die App kann auf die Vektorkacheln
 * zurueckfallen. Fuer [IndoorRouteSource.BROUTER_WAY_TAGS] gehoeren Linie und
 * Abschnitte zur selben Antwort; dort wird nicht geprueft.
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
    if (source == IndoorRouteSource.BROUTER_REMATCH && factor !in REMATCH_MIN_STRETCH..REMATCH_MAX_STRETCH) return none

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

/**
 * Umsetzung von `surfaceRematchWaypoints`.
 *
 * ## Warum so
 * Der Router soll der Spur folgen, nicht den kuerzesten Weg zwischen Start
 * und Ziel suchen. Zwischen zwei Stuetzpunkten im Abstand von rund
 * [REMATCH_SPACING_M] gibt es selten eine kuerzere Alternative als den
 * gefahrenen Weg; viel enger braucht es nicht und kostet Rechenzeit je
 * Via-Punkt. In drei Durchgaengen, damit Kurven auch auf langen Spuren
 * ihren Platz bekommen:
 *  1. **Regelpunkte**: Start, Ziel und dazwischen gleichmaessig verteilt rund
 *     alle 300 m ein Punkt, hoechstens [MAX_REMATCH_REGULAR] (mindestens ein
 *     Zwischenpunkt, damit eine kurze Runde nicht nur aus Start = Ziel
 *     besteht), jeweils auf den **echten** Spurpunkt mit der
 *     naechstliegenden kumulierten Laenge gesetzt (ein interpolierter Punkt
 *     laege in Kurven neben dem Weg).
 *  2. **Kurven**: An starken Richtungswechseln (mehr als
 *     [REMATCH_TURN_DEG] zwischen der Richtung der [REMATCH_TURN_ARM_M] davor
 *     und danach) — Kehren, Stichwege und Wendepunkte, die zwischen zwei
 *     Regelpunkten laegen, wuerde der Router sonst abkuerzen. Je Kurve nur der
 *     schaerfste Punkt; nicht naeher als [REMATCH_TURN_ARM_M] an einem schon
 *     gesetzten Punkt; die schaerfsten zuerst, solange
 *     [MAX_REMATCH_WAYPOINTS] haelt. Die Regelpunkte lassen ihnen auf jeder
 *     Spurlaenge mindestens 50 Plaetze.
 *  3. **Auffuellen**: Bleibt Platz, wird die jeweils groesste Luecke in der
 *     Mitte geteilt, solange sie laenger als das 1,5-Fache des Regelabstands
 *     ist (kuerzer gaebe es Luecken unter dem Regelabstand ohne Nutzen).
 *
 * Zum Schluss werden aufeinanderfolgende Stuetzpunkte naeher als
 * [REMATCH_MERGE_M] zusammengefasst (Start und Ziel bleiben).
 *
 * @return leer bei weniger als zwei verschiedenen Punkten oder einer Laenge
 *   unter [MIN_ROUTE_LENGTH_M].
 */
internal fun surfaceRematchWaypointsImpl(points: List<TrackPoint>): List<Waypoint> {
    val clean = cleanTrack(points)
    if (clean.size < 2) return emptyList()
    val lengthM = clean.last().cumM
    if (!lengthM.isFinite() || lengthM < MIN_ROUTE_LENGTH_M) return emptyList()

    /** Spurpunkt mit der kumulierten Laenge am naechsten an [target]. */
    fun nearestIndex(target: Double): Int {
        var lo = 0
        var hi = clean.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (clean[mid].cumM <= target) lo = mid else hi = mid
        }
        return if (abs(clean[hi].cumM - target) < abs(clean[lo].cumM - target)) hi else lo
    }

    // 1. Regelpunkte: n Stuecke, n + 1 Punkte <= MAX_REMATCH_REGULAR.
    val n = ceil(lengthM / REMATCH_SPACING_M).toInt().coerceIn(2, MAX_REMATCH_REGULAR - 1)
    val chosen = TreeSet<Int>()
    chosen.add(0)
    chosen.add(clean.size - 1)
    for (k in 1 until n) chosen.add(nearestIndex(lengthM * k / n))

    // 2. Kurven, die schaerfsten zuerst.
    val turn = turnAngles(clean, REMATCH_TURN_ARM_M, fullArms = true)
    val peaks = turnPeaks(turn, REMATCH_TURN_DEG).sortedByDescending { turn[it] }
    for (p in peaks) {
        if (chosen.size >= MAX_REMATCH_WAYPOINTS) break
        val near = chosen.any { abs(clean[it].cumM - clean[p].cumM) < REMATCH_TURN_ARM_M }
        if (!near) chosen.add(p)
    }

    // 3. Auffuellen: groesste Luecke ueber dem Regelabstand halbieren.
    while (chosen.size < MAX_REMATCH_WAYPOINTS) {
        var bestGap = 0.0
        var bestFrom = -1
        var prev = -1
        for (c in chosen) {
            if (prev >= 0) {
                val gap = clean[c].cumM - clean[prev].cumM
                if (gap > bestGap) {
                    bestGap = gap
                    bestFrom = prev
                }
            }
            prev = c
        }
        if (bestFrom < 0 || bestGap <= 1.5 * REMATCH_SPACING_M) break
        val mid = nearestIndex(clean[bestFrom].cumM + bestGap / 2)
        if (!chosen.add(mid)) break
    }

    // Aufeinanderfolgende Fast-Duplikate zusammenfassen; das Ziel bleibt.
    val ordered = chosen.toList()
    val out = ArrayList<CleanPoint>(ordered.size)
    for ((pos, idx) in ordered.withIndex()) {
        val p = clean[idx]
        val prev = out.lastOrNull()
        val close = prev != null && haversineM(
            TrackPoint(lat = prev.lat, lon = prev.lon),
            TrackPoint(lat = p.lat, lon = p.lon),
        ) < REMATCH_MERGE_M
        when {
            !close -> out.add(p)
            pos == ordered.size - 1 && out.size > 1 -> out[out.size - 1] = p
            pos == ordered.size - 1 -> out.add(p) // nur Start und Ziel: beide behalten
        }
    }
    return out.map { Waypoint(lat = it.lat, lon = it.lon) }
}
