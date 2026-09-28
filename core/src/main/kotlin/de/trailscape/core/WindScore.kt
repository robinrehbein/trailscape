package de.trailscape.core

import de.trailscape.core.i18n.CoreTexts
import de.trailscape.core.i18n.WindVerdict
import kotlin.math.cos
import kotlin.math.roundToInt

/**
 * Windrechnung der Rundkurs-Suche: „hin gegen den Wind, heim mit Rueckenwind".
 *
 * Wer eine Runde faehrt, will die schwere Haelfte frisch fahren und die
 * leichte muede — und nicht nach 60 km mit leeren Beinen gegen 25 km/h
 * Gegenwind heimkaempfen. Grosse Apps planen Runden ohne Wind; hier wird er
 * zu einem kleinen Zusatzkriterium der Bewertung (siehe `RouteGenerator.kt`,
 * Schritt 7).
 *
 * ## Konvention
 * Die Windrichtung ist **meteorologisch** angegeben, also die Richtung, **aus**
 * der der Wind kommt (270 = Westwind, so liefert es auch Open-Meteo). Faehrt
 * man mit Kurs 270 nach Westen, faehrt man also direkt in den Wind:
 * [headwindComponent] = cos(Kurs − Windherkunft) ist dann +1. Eine
 * Verwechslung mit „wohin der Wind weht" wuerde genau die falsche Runde
 * empfehlen; die Tests „Wind aus West + hin nach Westen = +1" sichern das ab.
 *
 * ## Form einer Runde
 * [windShape] vergleicht den laengengewichteten Gegenwind-Anteil der ersten
 * mit dem der zweiten Haelfte: `(H1 − H2) / 2`, +1 = hin voll gegen den Wind,
 * heim voll mit Rueckenwind. Getrennt wird bei der halben **Distanz**, nicht
 * beim halben Punktindex: Eine geroutete Strecke hat in der Stadt viele dicht
 * gesetzte Punkte und auf der Landstrasse wenige, der Punktindex sagt ueber
 * „die Haelfte der Fahrt" also nichts. Das Segment ueber der Mitte wird
 * anteilig auf beide Haelften verteilt.
 *
 * Eine ideale Kreisrunde erreicht dabei nur etwa 2/π ≈ 0,64, nicht 1 —
 * deshalb liegt die Schwelle fuer „Rueckenwind heim" bei
 * [windTailwindHomeMinShape] = 0,25.
 *
 * ## Staerke
 * Massgeblich ist der **Mittelwind**, nicht die Boe: Boeen sind kurz und
 * kommen aus allen Richtungen der Boeenwalze, den Heimweg macht der
 * Dauerwind schwer. Unter [windMinKmh] (10 km/h) wird gar nicht sortiert —
 * sonst entstuende Scheinpraezision aus einem Luefterl; ab [windFullKmh]
 * (25 km/h) zaehlt der Wind voll.
 *
 * ## Gewicht: warum 8 < 12 < 100
 * [windScore] = −[windScoreWeight] × Staerke × Form. Realistisch sind hoechstens
 * etwa 5 Strafpunkte, also 5 Prozentpunkte Distanzabweichung — deutlich
 * weniger als der Neu-Bonus ([noveltyScoreWeight] = 12) und weit weniger als
 * die Distanz selbst (100 Punkte je 100 % Abweichung). Der Wind sortiert also
 * nur annaehernd gleichwertige Runden um und erzeugt nie eine absurde Runde,
 * nur weil sie windguenstig liegt.
 */

/** Unter dieser Mittelwindgeschwindigkeit (km/h) spielt der Wind keine Rolle. */
const val windMinKmh: Double = 10.0

/** Ab dieser Mittelwindgeschwindigkeit (km/h) zaehlt der Wind voll. */
const val windFullKmh: Double = 25.0

/** Maximaler Windbonus in Strafpunkten (Begruendung siehe Datei-KDoc). */
const val windScoreWeight: Double = 8.0

/** Ab dieser [windShape] gilt eine Runde als „Rückenwind heim". */
const val windTailwindHomeMinShape: Double = 0.25

/** Pille an einem windguenstigen Vorschlag („Rückenwind heim"). */
fun windOptimisedLabel(texts: CoreTexts): String = texts.routing.windOptimisedLabel()

/**
 * Gegenwind-Anteil eines Abschnitts mit Kurs [bearingDeg] bei Wind aus
 * [windFromDeg]: +1 = voller Gegenwind, 0 = reiner Seitenwind, −1 = voller
 * Rueckenwind.
 */
fun headwindComponent(bearingDeg: Double, windFromDeg: Double): Double =
    cos(Math.toRadians(bearingDeg - windFromDeg))

/**
 * Laengengewichteter mittlerer Gegenwind-Anteil der ersten und der zweiten
 * Haelfte von [points] als `(H1, H2)`, getrennt bei der halben Distanz.
 *
 * Segmente der Laenge 0 werden uebersprungen (ihr Kurs ist undefiniert). Das
 * Segment ueber der Mitte zaehlt anteilig zu beiden Haelften. `null` bei
 * weniger als zwei Punkten, einer Gesamtlaenge von 0 oder einer nicht
 * endlichen Windrichtung.
 */
internal fun windHalves(points: List<TrackPoint>, windFromDeg: Double): Pair<Double, Double>? {
    if (points.size < 2 || !windFromDeg.isFinite()) return null

    val lengths = DoubleArray(points.size - 1)
    val headwinds = DoubleArray(points.size - 1)
    var total = 0.0
    for (i in 1 until points.size) {
        val len = haversineM(points[i - 1], points[i])
        if (!len.isFinite() || len <= 0.0) continue
        lengths[i - 1] = len
        headwinds[i - 1] = headwindComponent(
            kursZwischen(points[i - 1].lat, points[i - 1].lon, points[i].lat, points[i].lon),
            windFromDeg,
        )
        total += len
    }
    if (total <= 0.0) return null

    val half = total / 2
    var walked = 0.0
    var first = 0.0
    var second = 0.0
    for (i in lengths.indices) {
        val len = lengths[i]
        if (len <= 0.0) continue
        val inFirst = (half - walked).coerceIn(0.0, len)
        first += inFirst * headwinds[i]
        second += (len - inFirst) * headwinds[i]
        walked += len
    }
    return (first / half) to (second / half)
}

/**
 * Windform einer Strecke, −1…1: +1 = hin gegen den Wind, heim mit
 * Rueckenwind; −1 = umgekehrt; um 0 = Seitenwind oder kein klares Muster.
 * `null` fuer entartete Eingaben (siehe [windHalves]).
 */
fun windShape(points: List<TrackPoint>, windFromDeg: Double): Double? {
    val (h1, h2) = windHalves(points, windFromDeg) ?: return null
    val shape = (h1 - h2) / 2
    return if (shape.isFinite()) shape.coerceIn(-1.0, 1.0) else null
}

/**
 * Wie stark der Wind zaehlt, 0…1: linear von [windMinKmh] (0) bis
 * [windFullKmh] (1). Nicht endliche oder negative Werte ergeben 0.
 */
fun windStrengthFactor(speedKmh: Double): Double {
    if (!speedKmh.isFinite() || speedKmh <= windMinKmh) return 0.0
    return ((speedKmh - windMinKmh) / (windFullKmh - windMinKmh)).coerceIn(0.0, 1.0)
}

/**
 * Windanteil der Bewertung in Strafpunkten (negativ = Bonus):
 * `−`[windScoreWeight]` × Staerke × Form`. 0 bei schwachem Wind oder einer
 * Strecke ohne bestimmbare Form. Symmetrisch: Rueckenwind hin und Gegenwind
 * heim kostet Punkte.
 */
fun windScore(points: List<TrackPoint>, wind: WindConditions): Double {
    val strength = windStrengthFactor(wind.speedKmh)
    if (strength <= 0.0) return 0.0
    val shape = windShape(points, wind.fromDeg) ?: return 0.0
    return -windScoreWeight * strength * shape
}

/** Ob eine Runde mit dieser [windShape] als „Rückenwind heim" gilt. */
fun isTailwindHome(shape: Double): Boolean = shape.isFinite() && shape >= windTailwindHomeMinShape

/** Zahl der Richtungsstufen der Windanzeige (acht, je 45°). */
private const val WIND_DIRECTION_STEPS = 8

/**
 * Himmelsrichtung, **aus** der der Wind kommt, in acht Stufen (je ±22,5° um
 * die Mitte). Passt hinter „aus": „aus West". Nicht endlich ergibt
 * „wechselnder Richtung".
 */
fun windDirectionLabel(fromDeg: Double, texts: CoreTexts): String {
    if (!fromDeg.isFinite()) return texts.routing.windDirectionVariable()
    val index = ((normalisiereKurs(fromDeg) + 22.5) / 45.0).toInt() % WIND_DIRECTION_STEPS
    return texts.routing.windDirection(index)
}

/**
 * Die Windzeile im Generierungsblatt, z. B. „Wind 18 km/h aus West –
 * Rückenwind auf dem Heimweg".
 *
 * Verspricht bewusst nichts ueber die Zukunft: Das ist der aktuelle Wind, und
 * bei einer mehrstuendigen Runde kann er drehen. Boeen werden nur genannt,
 * wenn sie mindestens 10 km/h ueber dem Mittelwind liegen — sonst ist die
 * Angabe Rauschen.
 *
 * @param shape [windShape] des ausgewaehlten Vorschlags; `null`, wenn keine
 *   Form bekannt ist.
 */
fun windLine(wind: WindConditions, shape: Double?, texts: CoreTexts): String {
    val speed = if (wind.speedKmh.isFinite() && wind.speedKmh > 0) wind.speedKmh else 0.0
    val gusts = wind.gustsKmh
        ?.takeIf { it.isFinite() && it >= speed + GUST_MENTION_MARGIN_KMH }
        ?.roundToInt()
    val verdict = when {
        windStrengthFactor(speed) <= 0.0 -> WindVerdict.TOO_WEAK
        shape != null && isTailwindHome(shape) -> WindVerdict.TAILWIND_HOME
        shape != null && shape.isFinite() && shape <= -windTailwindHomeMinShape -> WindVerdict.HEADWIND_HOME
        else -> WindVerdict.CROSSWIND
    }
    return texts.routing.windLine(speed.roundToInt(), windDirectionLabel(wind.fromDeg, texts), gusts, verdict)
}

/** Ab so viel ueber dem Mittelwind werden Boeen in der Windzeile genannt. */
private const val GUST_MENTION_MARGIN_KMH = 10.0
