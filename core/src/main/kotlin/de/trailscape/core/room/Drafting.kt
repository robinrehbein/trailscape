package de.trailscape.core.room

import kotlin.math.exp

/**
 * # Windschatten — NÄHERUNG, noch nicht in der Fahrphysik
 *
 * Liefert einen Faktor auf den Luftwiderstand (`CdA` des eigenen Fahrers,
 * siehe `IndoorPhysics.kt`), abhängig vom Abstand zum nächsten Fahrer vor
 * einem in derselben Spur. **Nur gerechnet**: Diese Funktion ist bewusst
 * nirgends in `advancePhysics` eingebaut; das ist ein eigener Schritt, wenn
 * die Gruppenfahrt ohne Windschatten stabil läuft (docs/konzept-spiel-community.md, 5.2).
 *
 * ## Kurve
 *
 * Abstand = Lücke zwischen Hinterrad des Vordermanns und eigenem Vorderrad,
 * also Laufmeter-Unterschied minus eine Radlänge ([BIKE_LENGTH_M]).
 *
 * | Lücke | Abschlag auf CdA |
 * |---|---|
 * | 0 m   | 25 % |
 * | 1 m   | 22 % |
 * | 2 m   | 15 % |
 * | 3 m   |  8 % |
 * | ≥ 5 m |  0 % |
 *
 * dazwischen linear. **Herkunft**: keine eigene Herleitung und keine Messung.
 * Veröffentlichte Werte (Windkanal, Strömungsrechnung, Feldversuche) nennen
 * für einen Fahrer dicht am Hinterrad grob ein Viertel bis ein Drittel
 * weniger Luftwiderstand, mit deutlichem Abfall über die ersten Meter
 * (Zusammenfassung in docs/konzept-spiel-community.md, 5.2; Unsicherheit
 * dort in Abschnitt 8). Die Kurve bleibt **bewusst am unteren Rand**: Ein zu
 * starker Windschatten fühlt sich im Spiel wie Schummeln an und lässt
 * Gruppen zerfallen, ein zu schwacher fällt nur nicht auf.
 *
 * Nicht berücksichtigt: mehrere Fahrer hintereinander (Zug), seitlicher
 * Versatz innerhalb einer Spur, Seitenwind, der Vorteil des Vordermanns durch
 * den Hintermann, Tempoabhängigkeit (die steckt schon in `v²` der Physik).
 *
 * Wegen der Netzlaufzeit springt der Abstand an der Grenze; [smoothFactor]
 * glättet über rund eine Sekunde.
 */
object Drafting {
    /** Laufmeter-Unterschied, ab dem die Räder sich nicht mehr überlappen (Rad etwa 1,75 m). */
    const val BIKE_LENGTH_M: Double = 1.75

    /** Kleinster Faktor (größter Abschlag). */
    const val MIN_FACTOR: Double = 0.75

    /** Stützstellen (Lücke in m, Abschlag 0..1), aufsteigend. */
    val CURVE: List<Pair<Double, Double>> = listOf(
        0.0 to 0.25,
        1.0 to 0.22,
        2.0 to 0.15,
        3.0 to 0.08,
        5.0 to 0.0,
    )

    /** Abschlag 0..0,25 für eine Lücke in m (negativ zählt wie 0). */
    fun reductionForGap(wheelGapM: Double): Double {
        if (!wheelGapM.isFinite()) return 0.0
        val g = wheelGapM.coerceAtLeast(0.0)
        for (i in 1 until CURVE.size) {
            val (g0, r0) = CURVE[i - 1]
            val (g1, r1) = CURVE[i]
            if (g <= g1) return r0 + (r1 - r0) * (g - g0) / (g1 - g0)
        }
        return 0.0
    }

    /**
     * Faktor auf das eigene `CdA` aus den Stellen im Raum (`RoomState.positions`).
     *
     * Berücksichtigt nur Fahrer, die **vor** einem liegen, nicht `stale` sind
     * und in derselben Spur fahren (fehlende Spur zählt als 0). Liegt der
     * Vordermann weniger als eine halbe Radlänge vorn, fährt man praktisch
     * daneben: kein Windschatten.
     */
    fun factor(self: SelfPosition, others: List<RiderPosition>): DraftingResult {
        val myLane = self.lane ?: 0
        var best: RiderPosition? = null
        var bestDelta = Double.MAX_VALUE
        for (o in others) {
            if (o.isSelf || o.stale) continue
            if ((o.lane ?: 0) != myLane) continue
            val delta = o.distM - self.distM
            if (delta < BIKE_LENGTH_M / 2 || delta >= bestDelta) continue
            best = o
            bestDelta = delta
        }
        val leader = best ?: return DraftingResult.NONE
        val gap = (bestDelta - BIKE_LENGTH_M).coerceAtLeast(0.0)
        val factor = (1.0 - reductionForGap(gap)).coerceIn(MIN_FACTOR, 1.0)
        return DraftingResult(factor, leader.id, gap)
    }

    /**
     * Glättet den Faktor exponentiell mit Zeitkonstante [tauS] über den
     * Zeitschritt [dtS]; ohne gültigen Vorwert sofort das Ziel.
     */
    fun smoothFactor(previous: Double, target: Double, dtS: Double, tauS: Double = 1.0): Double {
        if (!previous.isFinite()) return target
        if (!(dtS > 0) || !dtS.isFinite()) return previous
        val a = 1.0 - exp(-dtS / tauS.coerceAtLeast(1e-3))
        return (previous + (target - previous) * a).coerceIn(MIN_FACTOR, 1.0)
    }
}

data class DraftingResult(
    /** Faktor auf CdA, [Drafting.MIN_FACTOR]..1 (1 = kein Windschatten). */
    val factor: Double,
    val leaderId: String?,
    /** Lücke zum Vordermann in m, `null` ohne Vordermann. */
    val wheelGapM: Double?,
) {
    companion object {
        val NONE = DraftingResult(1.0, null, null)
    }
}
