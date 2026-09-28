package de.trailscape.core.i18n

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Zahlen fuer die Anzeige, abhaengig von der Sprache.
 *
 * Deutsch schreibt ein Dezimalkomma („42,3"), Englisch einen Punkt („42.3").
 * Beide ohne Tausendertrennzeichen: Kilometer, Watt und Hoehenmeter bleiben
 * in dieser App meist vierstellig oder kleiner, und ein „1.200" liest sich im
 * Englischen als „eins Komma zwei". Gerundet wird kaufmaennisch (HALF_UP) wie
 * bisher in `toStringAsFixed`, damit die Werte zu den bestehenden Tests passen.
 *
 * Maschinenformate (GPX, GeoJSON, Dateinamen) laufen NICHT hierueber, sondern
 * weiter ueber `Locale.ROOT`.
 */
fun formatDecimal(value: Double, digits: Int, language: AppLanguage): String {
    if (!value.isFinite()) return "–"
    var text = BigDecimal(value).setScale(digits, RoundingMode.HALF_UP).toPlainString()
    // −0,0 ist keine sinnvolle Anzeige: nach dem Runden auf null ohne Vorzeichen.
    if (text.startsWith("-") && text.drop(1).all { it == '0' || it == '.' }) {
        text = text.drop(1)
    }
    return if (language == AppLanguage.DE) text.replace('.', ',') else text
}

/**
 * Kilometer mit einer Nachkommastelle, z. B. „42,3" / „42.3" (ohne Einheit).
 *
 * Gerundet wird wie in [de.trailscape.core.formatKm] ueber die Dezimaldarstellung
 * (`BigDecimal.valueOf`), NICHT ueber den exakten Binaerwert: Sonst zeigte z. B.
 * 12,35 km an einer Stelle „12,3" und an einer anderen (noch ueber `formatKm`)
 * „12,4". Alle Kilometerzahlen der App sollen fuer dieselbe Strecke gleich sein.
 */
fun formatDistanceKm(km: Double, language: AppLanguage): String {
    if (!km.isFinite()) return "–"
    var text = BigDecimal.valueOf(km).setScale(1, RoundingMode.HALF_UP).toPlainString()
    if (text.startsWith("-") && text.drop(1).all { it == '0' || it == '.' }) {
        text = text.drop(1)
    }
    return if (language == AppLanguage.DE) text.replace('.', ',') else text
}

/** Ganze Zahl, kaufmaennisch gerundet, ohne Tausendertrennzeichen. */
fun formatInt(value: Double): String = formatDecimal(value, 0, AppLanguage.EN)

/**
 * Eine Zahl mit hoechstens einer Nachkommastelle: ganze Werte ohne Stelle
 * („5"), sonst eine Stelle („4,5" / „4.5"). Fuer Stunden- und Wochenangaben.
 */
fun formatCompactDecimal(value: Double, language: AppLanguage): String {
    if (!value.isFinite()) return "–"
    val rounded = BigDecimal(value).setScale(1, RoundingMode.HALF_UP)
    return if (rounded.stripTrailingZeros().scale() <= 0) {
        formatDecimal(rounded.toDouble(), 0, language)
    } else {
        formatDecimal(rounded.toDouble(), 1, language)
    }
}
