package de.trailscape.core

/**
 * # Start und Ziel einer Spur ausblenden
 *
 * Ein geteiltes Tour-Bild zeigt die Form der Strecke. Wer die Gegend kennt,
 * erkennt daran die Haustuer — meist beginnt und endet eine Tour dort. Diese
 * Datei kuerzt die Spur deshalb an beiden Enden, bevor sie gezeichnet wird.
 *
 * ## Warum Luftlinie statt Strecke entlang der Spur
 * Um Start und Ziel liegt je ein Kreis mit [radiusM]. Gekuerzt wird bis zum
 * ersten Punkt, der ausserhalb **beider** Kreise liegt (und spiegelbildlich
 * ab dem letzten solchen Punkt). So liegt jedes neue Ende garantiert
 * mindestens den Radius vom echten Start **und** vom echten Ziel entfernt —
 * auch wenn beide nah beieinander, aber nicht am selben Ort liegen (die Fahrt
 * beginnt etwa am Treffpunkt und endet 250 m weiter zu Hause). Wuerde jedes
 * Ende nur gegen seinen eigenen Kreis geprueft, saesse der Kopf der Linie
 * dann womoeglich fast auf dem Ziel. 300 m **entlang** der Spur waeren schwaecher: Wer vor
 * der Haustuer rangiert, im Zickzack durch die Siedlung faehrt oder erst ein
 * Stueck zurueck muss, haette das gekuerzte Ende dann womoeglich nur 80 m vom
 * Haus entfernt — die Form verriete den Ort trotzdem.
 *
 * ## Was bewusst sichtbar bleibt
 * Faehrt die Tour **unterwegs** noch einmal durch einen der beiden Kreise
 * (etwa auf dem Rueckweg am eigenen Haus vorbei, bevor sie woanders endet),
 * bleibt diese Passage stehen. Zu sehen ist dann eine Durchfahrt, kein
 * Endpunkt. Eine Rundtour (Start ≈ Ziel) braucht keinen Sonderfall: Beide
 * Kreise fallen zusammen.
 *
 * Plattformfrei, damit die Grenzfaelle als reine JVM-Tests pruefbar sind
 * (`TrackPrivacyTest`).
 */

/** Radius um Start und Ziel, den ein geteiltes Tour-Bild ab Werk ausspart. */
const val SHARE_END_RADIUS_M = 300.0

/** Schritte der Bisektion: 30 Halbierungen druecken den Fehler einer 100-km-Strecke unter 0,1 mm. */
private const val BISECTION_STEPS = 30

/**
 * Kuerzt [points] an beiden Enden, sodass die Linie erst ausserhalb der
 * Kreise mit [radiusM] Luftlinie um den ersten **und** den letzten Punkt
 * beginnt und endet. Die neuen Endpunkte liegen auf dem Rand der beiden
 * Kreise (per Bisektion auf der Strecke interpoliert, die ihn schneidet),
 * also mindestens [radiusM] von Start und Ziel entfernt.
 *
 *  - `radiusM <= 0`: [points] unveraendert.
 *  - Weniger als zwei Punkte, eine Gesamtlaenge unter `2 · radiusM` (sehr
 *    kurze Tour) oder eine Spur, die die Kreise nie verlaesst: leere
 *    Liste — dann gibt es nichts, was sich zeigen liesse, ohne einen Ort zu
 *    verraten.
 *
 * Die interpolierten Randpunkte tragen Hoehe und Zeit anteilig (Zeit nur,
 * wenn beide Nachbarn eine haben), aber keinen Puls: Er gehoert zu keinem
 * gemessenen Moment.
 */
fun trimTrackEnds(points: List<TrackPoint>, radiusM: Double = SHARE_END_RADIUS_M): List<TrackPoint> {
    if (radiusM <= 0.0) return points
    if (points.size < 2) return emptyList()

    var length = 0.0
    for (k in 1 until points.size) length += haversineM(points[k - 1], points[k])
    if (length < 2 * radiusM) return emptyList()

    val first = points.first()
    val last = points.last()
    val outside = { p: TrackPoint -> outsideBoth(p, first, last, radiusM) }
    val i = points.indices.firstOrNull { outside(points[it]) } ?: return emptyList()
    val j = points.indices.lastOrNull { outside(points[it]) } ?: return emptyList()

    // i >= 1, weil der erste Punkt Abstand 0 zu sich selbst hat; ebenso j <= size - 2.
    // i <= j folgt daraus, dass es ueberhaupt einen Punkt ausserhalb gibt.
    val head = crossing(points[i - 1], points[i], outside)
    val tail = crossing(points[j + 1], points[j], outside)
    return buildList(j - i + 3) {
        add(head)
        addAll(points.subList(i, j + 1))
        add(tail)
    }
}

/** Liegt [p] mehr als [radiusM] von [first] **und** von [last] entfernt? */
private fun outsideBoth(p: TrackPoint, first: TrackPoint, last: TrackPoint, radiusM: Double): Boolean =
    haversineM(first, p) > radiusM && haversineM(last, p) > radiusM

/**
 * Ein Punkt auf der Strecke [inside] → [outside] am Rand des Bereichs, den
 * [isOutside] beschreibt: [inside] liegt drinnen (in mindestens einem der
 * Kreise), [outside] draussen. Die Bisektion haelt diese Invariante, der
 * zurueckgegebene Punkt liegt also sicher draussen und hoechstens eine
 * Rundungsbreite vom Rand entfernt.
 */
private fun crossing(inside: TrackPoint, outside: TrackPoint, isOutside: (TrackPoint) -> Boolean): TrackPoint {
    var lo = 0.0
    var hi = 1.0
    repeat(BISECTION_STEPS) {
        val mid = (lo + hi) / 2
        if (isOutside(lerp(inside, outside, mid))) hi = mid else lo = mid
    }
    // Die aeussere Grenze: Der Punkt liegt damit sicher nicht innerhalb eines Kreises.
    return lerp(inside, outside, hi)
}

private fun lerp(a: TrackPoint, b: TrackPoint, t: Double): TrackPoint {
    val ele = if (a.ele != null && b.ele != null) a.ele + (b.ele - a.ele) * t else a.ele ?: b.ele
    val time = if (a.time != null && b.time != null) a.time + ((b.time - a.time) * t).toLong() else null
    return TrackPoint(
        lat = a.lat + (b.lat - a.lat) * t,
        lon = a.lon + (b.lon - a.lon) * t,
        ele = ele,
        time = time,
        hr = null,
    )
}
