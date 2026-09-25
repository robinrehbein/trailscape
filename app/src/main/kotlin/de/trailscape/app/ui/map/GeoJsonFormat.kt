package de.trailscape.app.ui.map

import de.trailscape.core.TrackPoint
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor

/**
 * Das GeoJSON der Linien und seine Zahlen — bewusst ohne Android- oder
 * MapLibre-Import, damit es als JVM-Test pruefbar ist
 * (`app/src/test/.../GeoJsonFormatTest.kt`) und sich gefahrlos auf
 * `Dispatchers.Default` bauen laesst.
 *
 * ## Warum nicht einfach `String.format`
 * Die Live-Linie wird waehrend der Aufzeichnung jede Sekunde komplett neu
 * gebaut. Gemessen lagen dabei 85–90 % der Zeit in `String.format` (Formatter
 * anlegen, Formatstring parsen, Zwischen-Strings) — bei 20.000 Punkten rund
 * 15 ms je Aufbau. [appendCoordinate] schreibt die Ziffern stattdessen direkt
 * in den [StringBuilder] und liefert dabei Zeichen fuer Zeichen dasselbe wie
 * `String.format(Locale.ROOT, "%.6f", value)`.
 */

/** Leere GeoJSON-FeatureCollection — zeichnet nichts, laesst die Ebene aber stehen. */
internal const val EMPTY_FEATURES: String = """{"type":"FeatureCollection","features":[]}"""

/** Nachkommastellen jeder Koordinate: 6 Stellen sind rund 11 cm — genauer misst kein GPS. */
private const val COORDINATE_SCALE: Long = 1_000_000L

/**
 * Bis zu diesem Betrag ist `abs(value) * 1e6` auf deutlich besser als 0,01
 * genau (Double hat 53 Bit, 1e12 braucht 40). Koordinaten liegen weit
 * darunter; alles andere geht ueber den langsamen, aber exakten Weg.
 */
private const val FAST_PATH_LIMIT: Double = 1e6

/**
 * Wie nah der Nachkommarest an x,5 liegen darf, bevor der schnelle Weg der
 * eigenen Rundung nicht mehr traut. Grosszuegig bemessen: Die Rechenungenauigkeit
 * liegt bei [FAST_PATH_LIMIT] um 1e-4, echte Grenzfaelle sind trotzdem selten.
 */
private const val TIE_MARGIN: Double = 0.01

/**
 * Haengt [value] mit genau sechs Nachkommastellen an — Punkt als Trenner,
 * unabhaengig vom Gebietsschema (mit deutschem waere es ein Komma und das
 * JSON kaputt).
 *
 * Gleiches Ergebnis wie `String.format(Locale.ROOT, "%.6f", value)`, auch in
 * dessen Eigenheiten: Java rundet kaufmaennisch (HALF_UP) auf der *kuerzesten*
 * Dezimaldarstellung des Doubles (`Double.toString`), nicht auf seinem exakten
 * Binaerwert — `13.4050005` wird deshalb zu `13.405001`, obwohl der Double
 * knapp darunter liegt. Und negative Werte, die auf null runden, behalten ihr
 * Minus (`-0.000000`). Nahe an einem solchen x,5-Grenzfall rechnet die
 * Funktion deshalb ueber [BigDecimal] nach, statt selbst zu raten.
 */
internal fun StringBuilder.appendCoordinate(value: Double): StringBuilder {
    if (!value.isFinite() || abs(value) >= FAST_PATH_LIMIT) {
        return append(String.format(Locale.ROOT, "%.6f", value))
    }
    // Vorzeichen am Bit ablesen, nicht an `value < 0`: auch -0.0 bekommt bei
    // String.format sein Minus.
    val negative = value < 0.0 || (value == 0.0 && 1.0 / value < 0.0)
    val magnitude = abs(value)
    val scaled = magnitude * COORDINATE_SCALE
    val whole = floor(scaled)
    val rest = scaled - whole
    val units: Long = if (abs(rest - 0.5) < TIE_MARGIN) {
        BigDecimal(magnitude.toString())
            .movePointRight(6)
            .setScale(0, RoundingMode.HALF_UP)
            .longValueExact()
    } else if (rest > 0.5) {
        whole.toLong() + 1
    } else {
        whole.toLong()
    }
    if (negative) append('-')
    append(units / COORDINATE_SCALE)
    append('.')
    val fraction = units % COORDINATE_SCALE
    // Fuehrende Nullen der Nachkommastellen von Hand, damit kein
    // Zwischen-String entsteht.
    var divisor = COORDINATE_SCALE / 10
    while (divisor > 1 && fraction < divisor) {
        append('0')
        divisor /= 10
    }
    return append(fraction)
}

/** [appendCoordinate] als eigener String — fuer die Stellen, die keinen Builder haben. */
internal fun formatCoordinate(value: Double): String =
    StringBuilder(16).appendCoordinate(value).toString()

/**
 * Eine Linie als FeatureCollection mit genau einem LineString. Unter zwei
 * Punkten gibt es keine Linie — dann [EMPTY_FEATURES], damit die Ebene leer
 * wird statt einen ungueltigen LineString zu bekommen.
 */
internal fun lineFeatureCollection(points: List<TrackPoint>): String {
    if (points.size < 2) return EMPTY_FEATURES
    // ~22 Zeichen je Punkt ("[13.405000,52.520000],") plus Rahmen.
    val builder = StringBuilder(points.size * 24 + 128)
    builder.append("{\"type\":\"FeatureCollection\",\"features\":[")
    builder.append("{\"type\":\"Feature\",\"properties\":{},")
    builder.append("\"geometry\":{\"type\":\"LineString\",\"coordinates\":[")
    points.forEachIndexed { index, point ->
        if (index > 0) builder.append(',')
        builder.append('[').appendCoordinate(point.lon).append(',')
            .appendCoordinate(point.lat).append(']')
    }
    builder.append("]}}]}")
    return builder.toString()
}
