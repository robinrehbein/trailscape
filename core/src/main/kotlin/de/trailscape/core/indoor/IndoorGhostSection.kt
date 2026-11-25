package de.trailscape.core.indoor

import kotlin.math.abs

/**
 * # Vergleich mit dem Geist auf demselben Abschnitt
 *
 * „Draussen gefahren → drinnen nachfahren" (docs/indoor/app.md): Am Ende einer
 * Fahrt gegen die eigene Aussenfahrt soll klar dastehen, wie viele Sekunden
 * man vor oder hinter ihr war — und wie Zeit und Leistung sich unterscheiden.
 *
 * Verglichen wird genau der Abschnitt, den der Fahrer seit dem letzten
 * Ansetzen des Geists gefahren ist: von [startM] (Start, Startpunkt oder
 * letztes Versetzen) bis [endM] (wo die Fahrt endete). Die fruehere Fahrt
 * kennt ihre ganze Zeit-Weg-Kurve, also auch ihre Zeit fuer diesen Abschnitt
 * ([ghostS]) — selbst wenn sie am Ende noch hinter dem Fahrer lag. Gespeicherte
 * Pausen der Aussenfahrt sind dabei entfernt (wie beim Geist selbst), kurze
 * Halte (Ampel) zaehlen mit; drinnen zaehlt nur die Fahrzeit.
 *
 * @property startM / [endM] Laufmeter der Sitzung (ueber Runden fortlaufend).
 * @property riderS Fahrzeit des Fahrers fuer den Abschnitt in s.
 * @property ghostS Zeit des Geists fuer den Abschnitt; `null`, wenn sie nicht
 *   vorab bekannt ist (konstante Leistung) oder die fruehere Fahrt den
 *   Abschnitt nie ganz fuhr.
 * @property riderAvgPowerW mittlere Leistung des Fahrers; `null` ohne Messung.
 * @property ghostAvgPowerW mittlere Leistung des Geists; `null`, wenn die
 *   fruehere Fahrt keine Leistung kannte.
 */
data class GhostSection(
    val startM: Double,
    val endM: Double,
    val riderS: Double,
    val ghostS: Double?,
    val riderAvgPowerW: Double?,
    val ghostAvgPowerW: Double?,
) {
    /** Laenge des Abschnitts in m. */
    val lengthM: Double get() = endM - startM

    /**
     * Vorsprung des Fahrers in s (positiv = Fahrer war schneller, also vor
     * dem Geist); `null`, wenn [ghostS] fehlt.
     */
    val riderAheadS: Double? get() = ghostS?.let { it - riderS }

    /** Mittleres Tempo des Fahrers in km/h; `null` ohne Fahrzeit. */
    val riderAvgSpeedKmh: Double? get() = if (riderS > 0) lengthM / riderS * 3.6 else null

    /** Mittleres Tempo des Geists in km/h; `null` ohne bekannte Zeit. */
    val ghostAvgSpeedKmh: Double? get() = ghostS?.takeIf { it > 0 }?.let { lengthM / it * 3.6 }
}

/** Wie das Ergebnis gegen den Geist ausging. */
enum class GhostOutcome { AHEAD, BEHIND, EVEN }

/**
 * Das Ergebnis gegen den Geist: Ausgang und Betrag in s.
 *
 * @property seconds immer ≥ 0; bei [GhostOutcome.EVEN] der (kleine) Rest.
 */
data class GhostResult(val outcome: GhostOutcome, val seconds: Double)

/** Unter dieser Differenz gilt es als gleichauf. */
const val GHOST_EVEN_S: Double = 1.0

/**
 * Das Ergebnis der Fahrt [record] gegen den Geist; `null` ohne Geist oder ohne
 * bekannten Abstand.
 *
 * Vorrang hat die Zeitdifferenz auf demselben Abschnitt
 * ([GhostSection.riderAheadS], bei einer frueheren Fahrt immer bekannt, wenn
 * sie den Abschnitt ganz fuhr); sonst der Endabstand
 * [IndoorRideRecord.ghostFinalGapS] (Vorzeichen umgedreht: dort positiv =
 * Geist vorn).
 */
fun ghostResultOf(record: IndoorRideRecord): GhostResult? {
    val aheadS = record.ghostSection?.riderAheadS ?: record.ghostFinalGapS?.let { -it } ?: return null
    if (!aheadS.isFinite()) return null
    val outcome = when {
        abs(aheadS) < GHOST_EVEN_S -> GhostOutcome.EVEN
        aheadS > 0 -> GhostOutcome.AHEAD
        else -> GhostOutcome.BEHIND
    }
    return GhostResult(outcome, abs(aheadS))
}
