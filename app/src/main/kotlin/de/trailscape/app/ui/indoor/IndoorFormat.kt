package de.trailscape.app.ui.indoor

import de.trailscape.app.i18n.AppFormats
import de.trailscape.core.formatDuration
import kotlin.math.roundToInt

/**
 * # Zahlen und Zeiten der Indoor-Oberflaeche
 *
 * Reine Formatierung ohne Einheiten: Die Einheit steht in der Kachel daneben
 * (eigener Text), nie im Zahlenstring. Zahlen laufen ueber [AppFormats]
 * (DE Komma, EN Punkt), nie ueber `Locale`.
 */

/** Platzhalter fuer „kein Wert" (Sensor fehlt). */
const val INDOOR_NO_VALUE: String = "–"

/** Leistung in Watt, ganzzahlig; `null` → [INDOOR_NO_VALUE]. */
fun formatIndoorInt(value: Int?): String = value?.toString() ?: INDOOR_NO_VALUE

/** Tempo in km/h mit einer Nachkommastelle aus m/s. */
fun AppFormats.indoorSpeedKmh(speedMps: Double): String = decimal((speedMps * 3.6).coerceAtLeast(0.0), 1)

/** Steigung mit einer Nachkommastelle und Vorzeichen bei Anstieg („+4,2"); ohne Prozentzeichen. */
fun AppFormats.indoorGrade(gradePct: Double): String {
    val text = decimal(gradePct, 1)
    return if (gradePct > 0.05 && !text.startsWith("-")) "+$text" else text
}

/** Strecke in Kilometern mit einer Nachkommastelle. */
fun AppFormats.indoorKm(distM: Double): String = km(distM / 1000.0)

/** Strecke in Kilometern mit zwei Nachkommastellen (kurze Teststrecken). */
fun AppFormats.indoorKm2(distM: Double): String = decimal(distM / 1000.0, 2)

/** Dauer als H:MM:SS bzw. M:SS (sprachneutral). */
fun formatIndoorClock(seconds: Int): String = formatDuration(seconds.coerceAtLeast(0))

/** Restzeit eines ERG-Abschnitts als M:SS bzw. H:MM:SS. */
fun formatIndoorRemaining(seconds: Int): String = formatDuration(seconds.coerceAtLeast(0))

/** Hoehenmeter ganzzahlig. */
fun AppFormats.indoorAscent(ascentM: Double): String = decimal(ascentM.coerceAtLeast(0.0), 0)

/** Betrag des Geist-Abstands in Sekunden als Dauer; positiv = Geist vorn. */
fun formatIndoorGapSeconds(gapS: Double): String = formatDuration(kotlin.math.abs(gapS).toInt())

/** Betrag des Geist-Abstands in ganzen Metern (fuer den Fall ohne Sekundenwert). */
fun indoorGapMeters(gapM: Double): Int = kotlin.math.abs(gapM).roundToInt()

/** Datenmenge im Format der Sprache; 0 oder unbekannt → „0 KB". */
fun AppFormats.indoorBytes(size: Long): String = bytes(size) ?: "0 KB"

/** Anteil 0..1 als ganze Prozent fuer den Fortschritt. */
fun indoorPercent(fraction: Float): Int = (fraction.coerceIn(0f, 1f) * 100f).toInt()
