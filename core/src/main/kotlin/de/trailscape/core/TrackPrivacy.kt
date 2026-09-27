package de.trailscape.core

/**
 * # Start und Ziel einer Spur ausblenden
 *
 * Ein geteiltes Tour-Bild zeigt die Form der Strecke. Wer die Gegend kennt,
 * erkennt daran die Haustuer — meist beginnt und endet eine Tour dort. Diese
 * Datei kuerzt die Spur deshalb an beiden Enden, bevor sie gezeichnet wird.
 *
 * ## Warum Luftlinie statt Strecke entlang der Spur
 * Gekuerzt wird bis zum ersten Verlassen eines Kreises mit [radiusM] um den
 * Start (und spiegelbildlich ab dem letzten Betreten des Kreises um das Ziel).
 * So liegt jedes neue Ende garantiert mindestens den Radius vom echten Start
 * bzw. Ziel entfernt. 300 m **entlang** der Spur waeren schwaecher: Wer vor
 * der Haustuer rangiert, im Zickzack durch die Siedlung faehrt oder erst ein
 * Stueck zurueck muss, haette das gekuerzte Ende dann womoeglich nur 80 m vom
 * Haus entfernt — die Form verriete den Ort trotzdem.
 *
 * ## Was bewusst sichtbar bleibt
 * Faehrt die Tour **unterwegs** noch einmal durch einen der beiden Kreise
 * (etwa auf dem Rueckweg am eigenen Haus vorbei, bevor sie woanders endet),
 * bleibt diese Passage stehen. Zu sehen ist dann eine Durchfahrt, kein
 * Endpunkt. Eine Rundtour (Start ≈ Ziel) braucht keinen Sonderfall: Der Kopf
 * endet am ersten Verlassen des Kreises, der Schwanz beginnt am letzten
 * Betreten.
 *
 * Plattformfrei, damit die Grenzfaelle als reine JVM-Tests pruefbar sind
 * (`TrackPrivacyTest`).
 */

/** Radius um Start und Ziel, den ein geteiltes Tour-Bild ab Werk ausspart. */
const val SHARE_END_RADIUS_M = 300.0

/** Schritte der Bisektion: 30 Halbierungen druecken den Fehler einer 100-km-Strecke unter 0,1 mm. */
private const val BISECTION_STEPS = 30

/**
 * Kuerzt [points] an beiden Enden um einen Kreis mit [radiusM] Luftlinie um
 * den ersten bzw. letzten Punkt. Die neuen Endpunkte liegen genau auf dem
 * Kreis (per Bisektion auf der Strecke interpoliert, die ihn schneidet).
 *
 *  - `radiusM <= 0`: [points] unveraendert.
 *  - Weniger als zwei Punkte, eine Gesamtlaenge unter `2 · radiusM` (sehr
 *    kurze Tour) oder eine Spur, die einen der Kreise nie verlaesst: leere
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
    val i = points.indices.firstOrNull { haversineM(first, points[it]) > radiusM } ?: return emptyList()
    val j = points.indices.lastOrNull { haversineM(last, points[it]) > radiusM } ?: return emptyList()
    if (i > j) return emptyList()

    // i >= 1, weil der erste Punkt Abstand 0 zu sich selbst hat; ebenso j <= size - 2.
    val head = crossing(points[i - 1], points[i], first, radiusM)
    val tail = crossing(points[j + 1], points[j], last, radiusM)
    return buildList(j - i + 3) {
        add(head)
        addAll(points.subList(i, j + 1))
        add(tail)
    }
}

/**
 * Der Punkt auf der Strecke [inside] → [outside], der genau [radiusM] von
 * [center] entfernt liegt. [inside] liegt im Kreis (Abstand <= r), [outside]
 * ausserhalb; der Abstand waechst auf einer so kurzen Strecke praktisch
 * monoton, die Bisektion findet also den Schnittpunkt.
 */
private fun crossing(inside: TrackPoint, outside: TrackPoint, center: TrackPoint, radiusM: Double): TrackPoint {
    var lo = 0.0
    var hi = 1.0
    repeat(BISECTION_STEPS) {
        val mid = (lo + hi) / 2
        if (haversineM(center, lerp(inside, outside, mid)) > radiusM) hi = mid else lo = mid
    }
    // Die aeussere Grenze: Der Punkt liegt damit sicher nicht innerhalb des Kreises.
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
