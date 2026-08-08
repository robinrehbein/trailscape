package de.trailscape.core

import java.time.LocalDateTime

/**
 * Gemeinsame Testhelfer fuer die Portierung von `test/training_load_test.dart`.
 *
 * Entspricht 1:1 den Helfern am Kopf der Dart-Testdatei, damit alle
 * Erwartungswerte unveraendert uebernommen werden koennen.
 */

/** Meter pro Breitengrad — passend zum Erdradius in `Stats.kt`. */
internal val metersPerDegLat: Double = 6371000 * Math.PI / 180

internal const val T0: Long = 1700000000000L

/**
 * Referenzprofil mit gemessenen Ankerwerten, damit alle Erwartungswerte
 * von Hand nachrechenbar sind: HFmax 190, HFruhe 50, LTHR 170, maennlich.
 */
internal val refProfile = TrainingProfile(
    ageYears = 40,
    sex = Sex.MAENNLICH,
    weightKg = 75.0,
    hrMaxOverride = 190.0,
    lthrOverride = 170.0,
    restingHrOverride = 50.0,
)

/**
 * Entspricht Darts `DateTime(year, month, day)`: Tageswerte ausserhalb des
 * Monats werden normalisiert (Tag 0 = letzter Tag des Vormonats usw.).
 */
internal fun dt(year: Int, month: Int, day: Int, hour: Int = 0): LocalDateTime =
    LocalDateTime.of(year, month, 1, hour, 0).plusDays((day - 1).toLong())

/**
 * Baut einen synthetischen Track: konstante Geschwindigkeit, konstante
 * Steigung, optional Hoehe und Herzfrequenz.
 */
internal fun track(
    pointCount: Int,
    speedMs: Double = 5.0,
    stepS: Int = 10,
    gradeTan: Double = 0.0,
    startEle: Double = 100.0,
    withElevation: Boolean = true,
    hr: ((Int) -> Int?)? = null,
    startMs: Long = T0,
): List<TrackPoint> {
    val points = mutableListOf<TrackPoint>()
    val stepM = speedMs * stepS
    for (i in 0 until pointCount) {
        val along = i * stepM
        points.add(
            TrackPoint(
                lat = 47 + along / metersPerDegLat,
                lon = 11.0,
                ele = if (withElevation) startEle + gradeTan * along else null,
                time = startMs + i.toLong() * stepS * 1000L,
                hr = hr?.invoke(i),
            ),
        )
    }
    return points
}

/** Tagesserie aus Werten, die auf [end] enden (ein Wert pro Kalendertag). */
internal fun daily(
    values: List<Double>,
    end: LocalDateTime = dt(2026, 8, 8),
): List<DailyValue> = values.indices.map { i ->
    val offset = values.size - 1 - i
    DailyValue(
        day = dt(end.year, end.monthValue, end.dayOfMonth - offset),
        value = values[i],
    )
}

internal fun constantLoads(
    days: Int,
    load: Double,
    end: LocalDateTime = dt(2026, 8, 8),
): List<DailyLoad> = List(days) { i ->
    val offset = days - 1 - i
    DailyLoad(
        day = dt(end.year, end.monthValue, end.dayOfMonth - offset),
        load = load,
    )
}

/** Kurzform fuer `List<Double>.filled(n, v)` aus Dart. */
internal fun filled(n: Int, value: Double): List<Double> = List(n) { value }
