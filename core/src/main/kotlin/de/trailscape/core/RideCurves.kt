package de.trailscape.core

import kotlin.math.max

/**
 * Kurven einer aufgezeichneten Tour: Tempo und Herzfrequenz, aufgetragen ueber
 * die zurueckgelegte Distanz.
 *
 * ## Warum in `:core` und nicht im Screen
 * Die Detailansicht (`ui/rides/RideDetailScreen.kt`) *zeichnet* die Kurven nur.
 * Distanzachse, Glaettung und Ausduennung sind dagegen Rechnung — und Rechnung
 * gehoert in dieses Modul, wo sie ohne Emulator geprueft werden kann (siehe
 * `RideCurvesTest.kt`). Kein Dart-Vorbild: Die Flutter-App zeigte zu einer
 * gespeicherten Tour ueberhaupt keine Kurven.
 *
 * ## Warum `null` statt einer leeren Kurve
 * Importierte GPX-Dateien haben oft keine Zeitstempel und fast nie eine
 * Herzfrequenz. Ein Diagramm ohne Datengrundlage soll gar nicht erst
 * erscheinen; deshalb liefern die Funktionen hier `null`, und die Ansicht
 * laesst den ganzen Abschnitt weg, statt eine leere Flaeche zu zeichnen.
 *
 * ## Abgrenzung zum Hoehenprofil
 * Das Hoehenprofil hat mit `ui/map/ElevationProfile.kt` bereits eine
 * getestete Darstellung **samt** eigener Stuetzstellenrechnung; sie wird von
 * der Detailansicht unveraendert wiederverwendet und deshalb hier bewusst
 * **nicht** ein zweites Mal nachgebaut.
 */

/** Eine Stuetzstelle einer Kurve: ein Wert an einer Stelle der Strecke. */
data class RideCurveSample(
    /** Zurueckgelegte Strecke bis hierher, in Kilometern. */
    val distanceKm: Double,
    val value: Double,
)

/**
 * Eine ueber die Distanz aufgetragene Kurve. [minValue]/[maxValue] stehen mit
 * dabei, weil die Zeichenflaeche daraus ihre Y-Skala bildet und der Screen
 * dieselben Zahlen als Beschriftung braucht — zweimal ueber alle Stuetzstellen
 * zu laufen waere unnoetig.
 */
data class RideCurve(
    val samples: List<RideCurveSample>,
    val minValue: Double,
    val maxValue: Double,
) {
    /** Laenge der Kurve auf der Distanzachse. */
    val totalKm: Double get() = samples.lastOrNull()?.distanceKm ?: 0.0
}

/**
 * Standard-Aufloesung der Kurven. Eine lange Tour hat schnell zehntausende
 * Punkte; mehr Stuetzstellen als Bildschirmspalten bringen nichts — derselbe
 * Gedanke wie `MAX_SAMPLES` im Hoehenprofil.
 */
const val defaultRideCurveSamples: Int = 240

/**
 * Standard-Glaettungsfenster der Tempokurve in Sekunden.
 *
 * Rohe Punkt-zu-Punkt-Geschwindigkeiten aus GPS springen sekuendlich um
 * mehrere km/h; ungeglaettet waere die Kurve ein Rauschband ohne Aussage.
 * 30 s ist dasselbe Fenster, mit dem `TrainingLoad.kt` die Leistung mittelt.
 */
const val defaultSpeedSmoothingS: Double = 30.0

/**
 * Tempokurve in km/h ueber die Distanz.
 *
 * Gerechnet wird je Segment (Haversine-Distanz / Zeitdifferenz) und danach mit
 * einem zentrierten, zeitgewichteten Mittel ueber [smoothingWindowS] geglaettet.
 * Segmente ohne Zeitstempel, ohne Zeitfortschritt oder mit zurueckspringender
 * Uhr zaehlen zwar zur Distanz, liefern aber keinen Messwert — sonst entstuenden
 * aus kaputten Dateien Fantasiewerte.
 *
 * @return `null`, wenn die Tour keine zwei brauchbaren Segmente oder gar keine
 *   Streckenlaenge hat (dann gibt es keine Distanzachse).
 */
fun speedCurveKmh(
    points: List<TrackPoint>,
    smoothingWindowS: Double = defaultSpeedSmoothingS,
    maxSamples: Int = defaultRideCurveSamples,
): RideCurve? {
    if (points.size < 2) {
        return null
    }

    val raw = mutableListOf<RawSpeed>()
    var distanceM = 0.0
    var lastTimeS = Double.NEGATIVE_INFINITY

    for (i in 1 until points.size) {
        val prev = points[i - 1]
        val curr = points[i]
        val segmentM = haversineM(prev, curr)
        distanceM += segmentM

        val t0 = prev.time ?: continue
        val t1 = curr.time ?: continue
        val dtS = (t1 - t0) / 1000.0
        if (dtS <= 0) {
            continue
        }
        val timeS = t1 / 1000.0
        if (timeS <= lastTimeS) {
            continue
        }
        lastTimeS = timeS

        raw += RawSpeed(
            timeS = timeS,
            dtS = dtS,
            distanceKm = distanceM / 1000,
            speedKmh = segmentM / dtS * 3.6,
        )
    }

    if (raw.size < 2 || raw.last().distanceKm <= 0.0) {
        return null
    }

    val smoothed = smoothByTime(raw, smoothingWindowS)
    val samples = raw.mapIndexed { index, entry ->
        RideCurveSample(distanceKm = entry.distanceKm, value = smoothed[index])
    }
    return curveOf(thinOut(samples, maxSamples))
}

/**
 * Pulskurve in bpm ueber die Distanz.
 *
 * Ohne Glaettung: Herzfrequenz kommt bereits als Sekundenwert vom Brustgurt
 * oder aus Health Connect und schwankt nicht wie eine GPS-Geschwindigkeit.
 *
 * @return `null`, wenn hoechstens ein Punkt eine Herzfrequenz traegt oder die
 *   Tour keine Streckenlaenge hat.
 */
fun heartRateCurve(
    points: List<TrackPoint>,
    maxSamples: Int = defaultRideCurveSamples,
): RideCurve? {
    if (points.size < 2) {
        return null
    }

    val samples = mutableListOf<RideCurveSample>()
    var distanceM = 0.0

    points.forEachIndexed { index, point ->
        if (index > 0) {
            distanceM += haversineM(points[index - 1], point)
        }
        val hr = point.hr ?: return@forEachIndexed
        samples += RideCurveSample(distanceKm = distanceM / 1000, value = hr.toDouble())
    }

    if (samples.size < 2 || samples.last().distanceKm <= 0.0) {
        return null
    }
    return curveOf(thinOut(samples, maxSamples))
}

/** Ein Rohwert der Tempokurve, bevor geglaettet und ausgeduennt wird. */
private data class RawSpeed(
    val timeS: Double,
    val dtS: Double,
    val distanceKm: Double,
    val speedKmh: Double,
)

private fun curveOf(samples: List<RideCurveSample>): RideCurve = RideCurve(
    samples = samples,
    minValue = samples.minOf { it.value },
    maxValue = samples.maxOf { it.value },
)

/**
 * Zentriertes, mit der Segmentdauer gewichtetes Mittel ueber [windowS]
 * Sekunden. Zwei Zeiger statt einer inneren Schleife: Die Eingabe ist nach
 * Zeit aufsteigend, damit bleibt die Glaettung linear in der Punktzahl.
 */
private fun smoothByTime(raw: List<RawSpeed>, windowS: Double): DoubleArray {
    val out = DoubleArray(raw.size)
    if (windowS <= 0) {
        raw.forEachIndexed { index, entry -> out[index] = entry.speedKmh }
        return out
    }

    val half = windowS / 2
    var lo = 0
    var hi = 0
    var sum = 0.0
    var weight = 0.0

    for (i in raw.indices) {
        val t = raw[i].timeS
        while (hi < raw.size && raw[hi].timeS <= t + half) {
            sum += raw[hi].speedKmh * raw[hi].dtS
            weight += raw[hi].dtS
            hi++
        }
        while (lo < hi && raw[lo].timeS < t - half) {
            sum -= raw[lo].speedKmh * raw[lo].dtS
            weight -= raw[lo].dtS
            lo++
        }
        out[i] = if (weight > 0) sum / weight else raw[i].speedKmh
    }
    return out
}

/**
 * Duennt gleichmaessig auf hoechstens [maxSamples] + 1 Stuetzstellen aus; der
 * letzte Punkt bleibt immer erhalten, damit die Kurve nicht vor dem Tourende
 * abbricht.
 */
private fun thinOut(samples: List<RideCurveSample>, maxSamples: Int): List<RideCurveSample> {
    val limit = max(2, maxSamples)
    if (samples.size <= limit) {
        return samples
    }
    val step = (samples.size + limit - 1) / limit
    return samples.filterIndexed { index, _ -> index % step == 0 || index == samples.lastIndex }
}
