package de.trailscape.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max

/**
 * # Rundenguete — Stichstrecken, doppelt befahrene Wege, passender Untergrund
 *
 * Die Rundkurs-Generierung (`RouteGenerator.kt`) legt Via-Punkte auf einen
 * Kreis und laesst BRouter sie abfahren. Ein Via-Punkt ist aber nur eine
 * Koordinate: Liegt er hundert Meter neben der Strasse in einem Feldweg,
 * faehrt die Route **hinein, wendet und kommt denselben Weg zurueck**. Auf der
 * Karte ist das ein Stachel, unterwegs ein „bitte wenden" mitten im Nichts —
 * der haeufigste Grund, einer vorgeschlagenen Runde nicht zu trauen.
 *
 * Zwei Werkzeuge dagegen, beide reine Geometrie ohne Routing-Aufruf:
 *
 *  * [trimSpurs] schneidet kurze Stichstrecken heraus. Die Route beruehrt den
 *    Via-Punkt danach nicht mehr — er war nie ein Ziel, nur eine Richtung.
 *  * [retraceShare] misst, wie viel der Strecke doppelt befahren wird. Lange
 *    Hin-und-zurueck-Stuecke laesst [trimSpurs] bewusst stehen (wer sie
 *    herausschnitte, haette keine Runde mehr); sie kosten stattdessen
 *    Strafpunkte in der Bewertung ([retraceScore]).
 *
 * Dazu das absolute Urteil [routeQualityVerdict] (gut / brauchbar / schwach):
 * Strafpunkte ordnen nur, das Urteil sagt, ob die beste Runde ueberhaupt gut
 * genug ist — Grundlage der Nachsuche (`RouteGenerator.kt`, Schritt 9).
 *
 * ## Arbeitsweise
 * Beide Funktionen tasten die Route in festen Schritten ab ([sampleStepM]),
 * weil BRouter Punkte ungleichmaessig liefert (dicht in Kurven, auf Geraden
 * hunderte Meter auseinander), und rechnen in einer lokalen Ebene um den
 * ersten Punkt. Verglichen werden immer nur benachbarte Lagen, die Verzerrung
 * der Ebene spielt bei Rundenlaengen bis 200 km deshalb keine Rolle.
 */

/** Abtastschritt in Metern. */
internal const val sampleStepM: Double = 10.0

/** Zwei Lagen gelten als „derselbe Weg", wenn sie naeher beieinander liegen. */
internal const val sameWayToleranceM: Double = 15.0

/**
 * Hoehenunterschied, bis zu dem zwei Lagen noch derselbe Weg sein koennen.
 * Schuetzt Serpentinen: Deren Schenkel liegen im Grundriss oft keine 15 m
 * auseinander, aber auf verschiedener Hoehe — eine Stichstrecke dagegen
 * kommt auf derselben Strasse und damit auf derselben Hoehe zurueck.
 */
internal const val sameWayEleToleranceM: Double = 2.5

/**
 * Feinerer Abtastschritt fuer die Stichstrecken-Suche. Der Wendepunkt liegt
 * dann hoechstens 2,5 m neben einer Lage, gespiegelte Lagen derselben Strasse
 * also hoechstens 5 m auseinander.
 */
internal const val spurSampleStepM: Double = 5.0

/**
 * Hin- und Rueckweg einer Stichstrecke liegen enger aufeinander als diese
 * Schwelle — BRouter liefert fuer beide Richtungen dieselbe Weggeometrie.
 * Deutlich enger als [sameWayToleranceM], damit eine spitze Gabelung (zwei
 * verschiedene Wege, die auseinanderlaufen) nicht als Stichstrecke gilt: Bei
 * 8 m und [minSpurM] muessten die Schenkel unter 6° auseinanderlaufen.
 */
internal const val spurToleranceM: Double = 8.0

/** Kuerzere Wendestuecke sind Abtastrauschen an einer spitzen Kurve. */
internal const val minSpurM: Double = 80.0

/** Stichstrecken bis zu dieser einfachen Laenge werden immer geschnitten. */
internal const val maxSpurFloorM: Double = 300.0

/**
 * Darueber hinaus bis zu diesem Anteil der Rundenlaenge (einfache Laenge).
 * Laengere Hin-und-zurueck-Stuecke bleiben stehen und kosten [retraceScore].
 */
internal const val maxSpurShare: Double = 0.04

/**
 * Mindestabstand entlang der Strecke, ab dem eine nahe Lage als *zweite
 * Befahrung* zaehlt — darunter ist es schlicht der Punkt davor oder dahinter.
 */
internal const val retraceMinSeparationM: Double = 150.0

/**
 * Strafpunkte fuer eine vollstaendig doppelt befahrene Runde. 40 heisst: Ein
 * Viertel der Strecke doppelt kostet 10 Punkte — so viel wie 10 % Abweichung
 * von der Zieldistanz. Eine ehrliche Runde schlaegt damit eine
 * Hin-und-zurueck-Strecke, die die Distanz etwas besser trifft.
 */
const val retraceScoreWeight: Double = 40.0

/** Strafpunkte fuer den doppelt befahrenen Anteil [share] (0…1). */
fun retraceScore(share: Double): Double {
    val clamped = if (share.isFinite()) share.coerceIn(0.0, 1.0) else 0.0
    return retraceScoreWeight * clamped
}

// ---------------------------------------------------------------------------
// Untergrund
// ---------------------------------------------------------------------------

/**
 * Strafpunkte je vollstaendig unbefestigter Runde im Asphalt-Profil. 40 heisst:
 * 10 % Schotter kosten eine Rennrad-Runde 4 Punkte, ein Viertel Schotter 10 —
 * so viel wie 10 % Distanzabweichung. Wer Asphalt waehlt, faehrt schmale
 * Reifen; ein Feldweg ist dort kein Schoenheitsfehler, sondern ein Grund
 * umzudrehen.
 */
const val roadUnpavedPenaltyWeight: Double = 40.0

/**
 * Hoechster Bonus fuer unbefestigte Wege im echten Gravel-Profil
 * ([RouteProfile.SCHOTTER]), erreicht ab [gravelUnpavedSaturation]. Bewusst
 * schwaecher als die Distanz: Der Untergrund sortiert annaehernd gleich lange
 * Runden um, macht aber keine 15 % zu lange Runde zur Empfehlung.
 */
const val gravelUnpavedBonus: Double = 10.0

/** Anteil, ab dem mehr Schotter im Gravel-Profil nicht weiter belohnt wird. */
const val gravelUnpavedSaturation: Double = 0.7

/** Dasselbe fuer das gemischte Profil ([RouteProfile.GRAVEL], Trekking): halber Bonus, frueher satt. */
const val mixedUnpavedBonus: Double = 5.0

/** Saettigung des gemischten Profils. */
const val mixedUnpavedSaturation: Double = 0.4

/**
 * Strafpunkte (negativ = Bonus) dafuer, wie gut der Untergrund einer Runde zum
 * gewaehlten Fahrprofil passt.
 *
 * BRouter gewichtet den Belag schon beim Routen — aber nur *entlang* der
 * vorgegebenen Via-Punkte. Welche der drei Runden am Ende vorn steht,
 * entschied bisher allein Distanz und Hoehenprofil: Eine Gravel-Runde mit 5 %
 * Schotter schlug eine mit 60 %, wenn sie die Zieldistanz um einen Kilometer
 * besser traf. Genau dafuer hat man die App aber nicht geoeffnet.
 *
 * Ohne verlaessliche Belagsdaten ([unpavedPercent] ist `null`) ist das
 * Ergebnis 0 — eine Runde wird nie fuer fehlende Daten bestraft oder belohnt.
 * Fuer [RouteProfile.RADWEGE] und [RouteProfile.KUERZESTER] gibt es keine
 * Vorliebe.
 */
fun surfaceScore(profile: RouteProfile, route: PlannedRoute): Double {
    val share = (route.unpavedPercent ?: return 0.0) / 100.0
    // Ohne Schotter gibt es weder Strafe noch Bonus — und kein „−0,0".
    if (share <= 0) return 0.0
    return when (profile) {
        RouteProfile.ASPHALT -> roadUnpavedPenaltyWeight * share
        RouteProfile.SCHOTTER -> -gravelUnpavedBonus * (share / gravelUnpavedSaturation).coerceAtMost(1.0)
        RouteProfile.GRAVEL -> -mixedUnpavedBonus * (share / mixedUnpavedSaturation).coerceAtMost(1.0)
        RouteProfile.RADWEGE, RouteProfile.KUERZESTER -> 0.0
    }
}

// ---------------------------------------------------------------------------
// Guete-Urteil
// ---------------------------------------------------------------------------

/**
 * Wie gut eine vorgeschlagene Runde ist — gemessen an der Frage „wuerde ich sie
 * ungesehen fahren?". Die Reihenfolge ist die Rangfolge: [GUT] vor
 * [BRAUCHBAR] vor [SCHWACH].
 *
 * Der [RouteCandidate.score] sortiert, sagt aber nichts Absolutes: Er stellt
 * immer *irgendeine* Runde nach vorn, auch wenn alle drei 18 % daneben liegen
 * oder zur Haelfte hin und zurueck fuehren. Das Urteil ist die fehlende
 * absolute Schwelle — ein Tor (darf die Liste „Beste" sagen, braucht es eine
 * Nachsuche?), **keine** zweite Rangfolge.
 */
enum class RouteQualityLevel {
    /** Ungesehen fahrbar: trifft die Laenge, ist eine echte Runde, passt zum Profil. */
    GUT,

    /** Fahrbar mit Abstrichen, die man vorher wissen sollte. */
    BRAUCHBAR,

    /** Erfuellt das Versprechen „passende Runde" nicht. */
    SCHWACH,
}

/** Das Kriterium, an dem eine Runde ihr Urteil verliert — fuer eine ehrliche Begruendung. */
enum class RouteQualityIssue {
    /** Die Zieldistanz wird deutlich verfehlt. */
    DISTANZ,

    /** Ein guter Teil der Strecke fuehrt hin und denselben Weg zurueck. */
    HIN_UND_ZURUECK,

    /** Zu viel unbefestigter Weg fuer das Asphalt-Profil. */
    BELAG,
}

/**
 * Urteil samt Ursache. [issue] ist `null` genau dann, wenn [level] [RouteQualityLevel.GUT] ist.
 */
data class RouteQualityVerdict(val level: RouteQualityLevel, val issue: RouteQualityIssue?)

/**
 * Bis zu dieser relativen Distanzabweichung ist eine Runde [RouteQualityLevel.GUT] —
 * dieselbe Schwelle wie die Toleranz der Radius-Iteration
 * ([routeToleranceRatio]): Was der Generator selbst als „getroffen" ansieht,
 * soll die Oberflaeche nicht anders nennen. 10 % sind bei 50 km fuenf
 * Kilometer, also etwa eine Viertelstunde — das plant man ein.
 */
const val qualityDistanceGoodMax: Double = 0.10

/**
 * Bis hier ist die Abweichung noch [RouteQualityLevel.BRAUCHBAR]. Jenseits von
 * 20 % ist aus der 2-Stunden-Einheit eine von zweieinhalb geworden (oder eine
 * von gut anderthalb) — das ist eine andere Trainingseinheit, nicht dieselbe
 * mit Abweichung.
 */
const val qualityDistanceUsableMax: Double = 0.20

/**
 * Bis zu diesem doppelt befahrenen Anteil ([retraceShare]) ist eine Runde
 * [RouteQualityLevel.GUT]. Einige Prozent sind normal und kein Makel: der
 * gemeinsame Weg aus dem Ort hinaus und wieder hinein, eine Bruecke, die
 * beide Richtungen benutzen. Das Mass zaehlt beide Befahrungen — 10 % heissen
 * also nur 5 % der Strecke als einfacher Weg.
 */
const val qualityRetraceGoodMax: Double = 0.10

/**
 * Bis hier ist der Anteil noch [RouteQualityLevel.BRAUCHBAR]. Darueber ist fast
 * ein Drittel der Fahrt Wiederholung — das ist keine Runde mehr, sondern eine
 * Pendelstrecke mit Schleife. Die Liste nennt den Anteil schon ab 15 % beim
 * Namen (`RETRACE_NOTICE_SHARE` im Blatt), also innerhalb von „brauchbar".
 */
const val qualityRetraceUsableMax: Double = 0.30

/**
 * Bis zu diesem unbefestigten Anteil in Prozent ([unpavedPercent]) ist eine
 * Runde im Asphalt-Profil [RouteQualityLevel.GUT]. Nicht 0, weil die
 * Belagsdaten Freiwilligendaten sind und bis zur Haelfte hochgerechnet
 * (siehe [unpavedPercent]): Ein falsch getaggter Wirtschaftsweg oder ein
 * gepflasterter Ortskern soll eine sonst gute Rennrad-Runde nicht kippen.
 * Im Score kosten 10 % genau 4 Punkte ([roadUnpavedPenaltyWeight]).
 */
const val qualityRoadUnpavedGoodMaxPercent: Int = 10

/**
 * Bis hier ist eine Asphalt-Runde noch [RouteQualityLevel.BRAUCHBAR]. Ein
 * Viertel Schotter kostet im Score 10 Punkte — so viel wie die
 * [qualityDistanceGoodMax]-Grenze der Distanz. Darueber ist es mit schmalen
 * Reifen kein Abschnitt mehr, den man durchrollt, sondern ein Grund
 * umzudrehen.
 */
const val qualityRoadUnpavedUsableMaxPercent: Int = 25

/** Stufe eines einzelnen Kriteriums: `≤ good` gut, `≤ usable` brauchbar, sonst schwach. */
private fun levelOf(value: Double, good: Double, usable: Double): RouteQualityLevel = when {
    !value.isFinite() -> RouteQualityLevel.SCHWACH
    value <= good -> RouteQualityLevel.GUT
    value <= usable -> RouteQualityLevel.BRAUCHBAR
    else -> RouteQualityLevel.SCHWACH
}

/**
 * Das Guete-Urteil einer Runde: Das **schlechteste** Kriterium entscheidet —
 * eine Runde, die die Laenge exakt trifft, aber zur Haelfte hin und zurueck
 * fuehrt, ist nicht „im Schnitt brauchbar", sondern schwach.
 *
 *  * Distanz: [distanceDeviation] gegen [qualityDistanceGoodMax] /
 *    [qualityDistanceUsableMax].
 *  * Hin und zurueck: [retraceShare] gegen [qualityRetraceGoodMax] /
 *    [qualityRetraceUsableMax].
 *  * Belag: **nur** im Asphalt-Profil und **nur** mit Belagsdaten
 *    ([unpavedPercent] nicht `null`) gegen [qualityRoadUnpavedGoodMaxPercent] /
 *    [qualityRoadUnpavedUsableMaxPercent]. Fehlende Daten machen eine Runde
 *    nie schlechter — dieselbe Regel wie bei [surfaceScore]. In den anderen
 *    Profilen ist Schotter erwuenscht oder egal.
 *
 * Bei gleich schlechten Kriterien nennt [RouteQualityVerdict.issue] das erste
 * in dieser Reihenfolge: Die Distanz ist das Hauptversprechen der Suche.
 */
fun routeQualityVerdict(
    distanceDeviation: Double,
    retraceShare: Double,
    unpavedPercent: Int?,
    profile: RouteProfile?,
): RouteQualityVerdict {
    val criteria = buildList {
        add(RouteQualityIssue.DISTANZ to levelOf(distanceDeviation, qualityDistanceGoodMax, qualityDistanceUsableMax))
        add(RouteQualityIssue.HIN_UND_ZURUECK to levelOf(retraceShare, qualityRetraceGoodMax, qualityRetraceUsableMax))
        if (profile == RouteProfile.ASPHALT && unpavedPercent != null) {
            add(
                RouteQualityIssue.BELAG to levelOf(
                    unpavedPercent.toDouble(),
                    qualityRoadUnpavedGoodMaxPercent.toDouble(),
                    qualityRoadUnpavedUsableMaxPercent.toDouble(),
                ),
            )
        }
    }
    // maxBy behaelt das erste Maximum — das ist der Tie-Break aus dem KDoc.
    val (issue, level) = criteria.maxBy { it.second.ordinal }
    return RouteQualityVerdict(level, if (level == RouteQualityLevel.GUT) null else issue)
}

private const val EARTH_RADIUS_M = 6371000.0

/** Die Route in gleichmaessigen Schritten, in Metern um den ersten Punkt. */
private class Samples(
    val x: DoubleArray,
    val y: DoubleArray,
    /** Hoehe je Lage; NaN ohne Hoehendaten. */
    val ele: DoubleArray,
    val step: Double,
) {
    val size: Int get() = x.size

    fun near(i: Int, j: Int, tolerance: Double = sameWayToleranceM): Boolean {
        val dx = x[i] - x[j]
        val dy = y[i] - y[j]
        if (dx * dx + dy * dy > tolerance * tolerance) return false
        val ei = ele[i]
        val ej = ele[j]
        return ei.isNaN() || ej.isNaN() || abs(ei - ej) <= sameWayEleToleranceM
    }
}

/** Kumulierte Distanz in Metern je Punkt ([0] = 0). */
private fun cumulativeM(points: List<TrackPoint>): DoubleArray {
    val cum = DoubleArray(points.size)
    for (i in 1 until points.size) {
        cum[i] = cum[i - 1] + haversineM(points[i - 1], points[i])
    }
    return cum
}

private fun sample(points: List<TrackPoint>, cum: DoubleArray, step: Double = sampleStepM): Samples {
    val total = cum.lastOrNull() ?: 0.0
    val count = if (points.size < 2 || total <= 0) 0 else floor(total / step).toInt() + 1
    val x = DoubleArray(count)
    val y = DoubleArray(count)
    val ele = DoubleArray(count)
    if (count == 0) return Samples(x, y, ele, step)

    val lat0 = points[0].lat
    val lon0 = points[0].lon
    val mPerDegLat = EARTH_RADIUS_M * PI / 180
    val mPerDegLon = mPerDegLat * cos(lat0 * PI / 180)

    var seg = 0
    for (k in 0 until count) {
        val s = k * step
        while (seg < points.size - 2 && cum[seg + 1] < s) seg += 1
        val a = points[seg]
        val b = points[seg + 1]
        val len = cum[seg + 1] - cum[seg]
        val t = if (len > 0) ((s - cum[seg]) / len).coerceIn(0.0, 1.0) else 0.0
        x[k] = ((a.lon + (b.lon - a.lon) * t) - lon0) * mPerDegLon
        y[k] = ((a.lat + (b.lat - a.lat) * t) - lat0) * mPerDegLat
        val ea = a.ele
        val eb = b.ele
        ele[k] = if (ea != null && eb != null) ea + (eb - ea) * t else Double.NaN
    }
    return Samples(x, y, ele, step)
}

// ---------------------------------------------------------------------------
// Doppelt befahrene Wege
// ---------------------------------------------------------------------------

/**
 * Anteil der Strecke (0…1), der auf Wegen liegt, die die Route **ein zweites
 * Mal** befaehrt — gleich in welcher Richtung. Beide Befahrungen zaehlen: Eine
 * reine Hin-und-zurueck-Strecke ergibt rund 1, eine saubere Runde 0.
 *
 * Blosse Kreuzungen (Achter-Schleife) fallen nicht ins Gewicht — sie treffen
 * nur die wenigen Lagen direkt am Kreuzungspunkt.
 */
fun retraceShare(points: List<TrackPoint>): Double {
    val samples = sample(points, cumulativeM(points))
    val n = samples.size
    if (n < 3) return 0.0

    val cell = sameWayToleranceM
    fun key(cx: Int, cy: Int): Long = (cx.toLong() shl 32) xor (cy.toLong() and 0xffffffffL)
    val grid = HashMap<Long, MutableList<Int>>(n)
    val cxs = IntArray(n)
    val cys = IntArray(n)
    for (i in 0 until n) {
        cxs[i] = floor(samples.x[i] / cell).toInt()
        cys[i] = floor(samples.y[i] / cell).toInt()
        grid.getOrPut(key(cxs[i], cys[i])) { mutableListOf() }.add(i)
    }

    val minGap = (retraceMinSeparationM / samples.step).toInt()
    // Eine Runde endet, wo sie beginnt: Die ersten und die letzten Lagen sind
    // dann Nachbarn, keine zweite Befahrung — der Abstand zaehlt im Kreis.
    val closed = samples.near(0, n - 1)
    fun gap(i: Int, j: Int): Int {
        val direct = abs(j - i)
        return if (closed) minOf(direct, n - direct) else direct
    }
    var retraced = 0
    for (i in 0 until n) {
        var hit = false
        var dx = -1
        while (dx <= 1 && !hit) {
            var dy = -1
            while (dy <= 1 && !hit) {
                val bucket = grid[key(cxs[i] + dx, cys[i] + dy)]
                if (bucket != null) {
                    for (j in bucket) {
                        if (gap(i, j) > minGap && samples.near(i, j)) {
                            hit = true
                            break
                        }
                    }
                }
                dy += 1
            }
            dx += 1
        }
        if (hit) retraced += 1
    }
    return retraced.toDouble() / n
}

// ---------------------------------------------------------------------------
// Stichstrecken
// ---------------------------------------------------------------------------

/**
 * Ergebnis von [trimSpurs].
 *
 * @property route die bereinigte Route; unveraendert (dieselbe Instanz), wenn
 *   nichts zu schneiden war.
 * @property spurs Anzahl herausgeschnittener Stichstrecken.
 * @property removedKm herausgeschnittene Strecke (Hin- und Rueckweg zusammen).
 */
data class SpurTrim(val route: PlannedRoute, val spurs: Int, val removedKm: Double)

/** Ein herauszuschneidendes Stueck, in Metern entlang der Route. */
private class Cut(val fromM: Double, val toM: Double)

/**
 * Sucht Wendepunkte: Lagen, um die herum die Route spiegelbildlich auf sich
 * selbst liegt (Lage `c − m` neben Lage `c + m`), und liefert die
 * zugehoerigen Schnitte.
 */
private fun findSpurs(samples: Samples, maxSpurM: Double): List<Cut> {
    val n = samples.size
    val minReach = (minSpurM / samples.step).toInt()
    val maxReach = (maxSpurM / samples.step).toInt()
    val cuts = mutableListOf<Cut>()
    var c = 1
    while (c < n - 1) {
        var reach = 0
        while (c - reach - 1 >= 0 && c + reach + 1 < n && samples.near(c - reach - 1, c + reach + 1, spurToleranceM)) {
            reach += 1
        }
        if (reach in minReach..maxReach) {
            cuts.add(Cut(fromM = (c - reach) * samples.step, toM = (c + reach) * samples.step))
            c += reach + 1
        } else {
            c += 1
        }
    }
    return cuts
}

private fun interpolate(points: List<TrackPoint>, cum: DoubleArray, atM: Double): TrackPoint {
    var seg = cum.indexOfFirst { it >= atM }.let { if (it < 0) points.size - 1 else it }
    if (seg == 0) return points[0]
    seg -= 1
    val a = points[seg]
    val b = points[seg + 1]
    val len = cum[seg + 1] - cum[seg]
    val t = if (len > 0) ((atM - cum[seg]) / len).coerceIn(0.0, 1.0) else 0.0
    val ea = a.ele
    val eb = b.ele
    return TrackPoint(
        lat = a.lat + (b.lat - a.lat) * t,
        lon = a.lon + (b.lon - a.lon) * t,
        ele = if (ea != null && eb != null) ea + (eb - ea) * t else ea ?: eb,
    )
}

/** Summe der Anstiege aus den rohen Punkthoehen — nur als Verhaeltnismass. */
private fun rawAscentM(points: List<TrackPoint>): Double {
    var sum = 0.0
    var previous: Double? = null
    for (p in points) {
        val ele = p.ele ?: continue
        if (previous != null && ele > previous) sum += ele - previous
        previous = ele
    }
    return sum
}

/**
 * Schneidet kurze Stichstrecken aus [route] — Stuecke, auf denen die Route
 * einen Weg hinein- und denselben Weg wieder herausfaehrt.
 *
 * Geschnitten wird nur, was sicher eine Stichstrecke ist:
 *  * einfache Laenge zwischen [minSpurM] und `max(`[maxSpurFloorM]`,
 *    `[maxSpurShare]` × Rundenlaenge)`,
 *  * Hin- und Rueckweg liegen durchgehend innerhalb von
 *    [sameWayToleranceM] **und** [sameWayEleToleranceM] aufeinander.
 *
 * Start und Ziel bleiben unangetastet: Wer die Runde ueber dieselbe Strasse
 * verlaesst und beendet, hat dort keinen Wendepunkt *in* der Route.
 *
 * Distanz, Hoehenmeter und Belagskilometer werden anteilig nachgefuehrt
 * (Distanz und Belag nach Strecke, Hoehenmeter nach dem Anteil der rohen
 * Anstiege), damit sie zu den Werten passen, die das Routing fuer den Rest
 * geliefert hat.
 *
 * Bewusst **ein** Durchgang: Jeder Schnitt hinterlaesst an seiner Wurzel eine
 * neue Spitze. An einer echten Stichstrecke ist die harmlos (die Route geht
 * dort geradeaus weiter), an einer spitzen Gabelung wuerde ein zweiter
 * Durchgang aber weiter in die Gabel hineinschneiden.
 */
fun trimSpurs(route: PlannedRoute): SpurTrim {
    val original = route.points
    val cum = cumulativeM(original)
    val originalM = cum.lastOrNull() ?: 0.0
    if (original.size < 3 || originalM <= 0) return SpurTrim(route, 0, 0.0)
    val maxSpurM = max(maxSpurFloorM, maxSpurShare * originalM)

    val cuts = findSpurs(sample(original, cum, spurSampleStepM), maxSpurM)
    val spurs = cuts.size
    val points = ArrayList<TrackPoint>(original.size)
    var index = 0
    for (cut in cuts) {
        while (index < original.size && cum[index] <= cut.fromM) {
            points.add(original[index])
            index += 1
        }
        points.add(interpolate(original, cum, cut.fromM))
        while (index < original.size && cum[index] < cut.toM) index += 1
    }
    while (index < original.size) {
        points.add(original[index])
        index += 1
    }

    if (spurs == 0) return SpurTrim(route, 0, 0.0)

    val trimmedM = cumulativeM(points).last()
    val distanceRatio = (trimmedM / originalM).coerceIn(0.0, 1.0)
    val rawBefore = rawAscentM(route.points)
    val ascentRatio = if (rawBefore > 0) (rawAscentM(points) / rawBefore).coerceIn(0.0, 1.0) else distanceRatio
    return SpurTrim(
        route = route.copy(
            points = points,
            distanceKm = route.distanceKm * distanceRatio,
            ascentM = route.ascentM * ascentRatio,
            pavedKm = route.pavedKm?.let { it * distanceRatio },
            unpavedKm = route.unpavedKm?.let { it * distanceRatio },
        ),
        spurs = spurs,
        removedKm = route.distanceKm * (1 - distanceRatio),
    )
}
