package de.trailscape.core.indoor.generated

/**
 * # Zielprofil einer generierten Strecke
 *
 * Schnittstelle zwischen der Ableitung aus der Trainingseinheit (Auswahl und
 * Oberflaeche) und dem Bau von Strecke und Welt ([generatedIndoorRoute],
 * [buildGeneratedWorld] in `GeneratedWorldEntry.kt`). Verfahren und Grenzen:
 * `docs/indoor/generiert.md`.
 *
 * Gleicher [seed] und gleiche [legs] ergeben dieselbe Strecke und eine
 * bytegleiche Welt.
 *
 * @property legs Abschnitte in Fahrtrichtung; die Summe ihrer Laengen ist die
 *   Streckenlaenge.
 */
data class GeneratedProfile(val seed: Long, val legs: List<Leg>) {
    /**
     * Ein Abschnitt.
     *
     * @property lengthM Laenge in Metern (> 0).
     * @property gradePct mittlere Steigung in Prozent (positiv = bergauf).
     *   Unfahrbare Werte werden beim Bau gekappt (siehe Doku).
     * @property kind Charakter des Abschnitts: [Kind.FLAT] eben,
     *   [Kind.ROLLING] wellig um die mittlere Steigung, [Kind.CLIMB] Anstieg,
     *   [Kind.DESCENT] Abfahrt.
     */
    data class Leg(val lengthM: Double, val gradePct: Double, val kind: Kind)

    enum class Kind { FLAT, ROLLING, CLIMB, DESCENT }

    val lengthM: Double get() = legs.sumOf { it.lengthM }
}
