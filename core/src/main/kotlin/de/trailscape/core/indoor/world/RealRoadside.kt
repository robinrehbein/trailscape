package de.trailscape.core.indoor.world

/**
 * # Echte Straßenausstattung aus vollen Kartendaten (Schnittstelle)
 *
 * Die Straßenausstattung der Strecke ([RoadMarkings], [RoadDecals], [RoadsideLayer]) entsteht nach
 * Regeln aus Wegklasse, Breite, Landnutzung und Geometrie, weil die Vektorkacheln (OpenMapTiles)
 * weder Leitplanken noch Markierung, Haltelinien oder Radfahrstreifen kennen. Wo eine Quelle mit
 * vollen OSM-Attributen vorliegt (Katalog-/Serverbau, Detailtreue: `OsmDetail`), sollen die
 * echten Objekte gelten und die Regeln an genau diesen Stellen zurücktreten — ohne dass zwei
 * Schichten dasselbe Objekt doppelt zeichnen.
 *
 * Dazu liefert [MapData.roadside] ein [RealRoadside]. Ohne volle Daten ist das [RealRoadside.NONE]
 * (alles leer): Die Regeln gelten überall. Eine Kartenquelle mit vollen Daten überschreibt
 * [MapData.roadside] und gibt zurück, was sie kennt:
 *
 * | Methode | OSM | Wirkung |
 * |---|---|---|
 * | [guardRails] | `barrier=guard_rail` | Regel-Schutzplanken und Leitpfosten entfallen auf dieser Seite, wo eine echte Planke ≤ [GUARD_RAIL_MATCH_M] neben ihrer Linie liegt. Zeichnen tut die echte Planke die Datenschicht selbst |
 * | [controls] | `highway=stop`, `highway=give_way`, `highway=traffic_signals` | Haltelinie (Z 294) bzw. Wartelinie (Z 341) an der echten Stelle statt der Rangregel |
 * | [cycleLanes] | `cycleway:right/left/both=lane` (+ `cycleway:lane=advisory`) | Schutzstreifen (unterbrochen) bzw. Radfahrstreifen (Breitstrich) |
 * | [authoritative] | — | `true`: Die Quelle deckt den Korridor vollständig ab; dann entfallen Regel-Schutzplanken ganz (nicht die Leitpfosten — die führt OSM fast nie) |
 *
 * Alle Lagen in ENU der Strecke (wie [MapData]). Die Methoden müssen threadsicher und für dieselben
 * Eingaben gleich sein (Kacheln werden parallel und in beliebiger Reihenfolge gebaut).
 */
internal interface RealRoadside {
    /** Die Quelle deckt den Korridor um die Strecke vollständig ab (fehlende Planke = keine Planke). */
    val authoritative: Boolean get() = false

    /** Echte Schutzplanken im Rechteck: je Linie `e0, n0, e1, n1, …`. */
    fun guardRails(minE: Double, minN: Double, maxE: Double, maxN: Double): List<DoubleArray> = emptyList()

    /** Halt, Vorfahrt achten, Lichtzeichen als Punkte im Rechteck. */
    fun controls(minE: Double, minN: Double, maxE: Double, maxN: Double): List<RoadControl> = emptyList()

    /** Radfahr- und Schutzstreifen: Linie der Straße und Seite in Linienrichtung. */
    fun cycleLanes(minE: Double, minN: Double, maxE: Double, maxN: Double): List<CycleLane> = emptyList()

    companion object {
        /** Keine vollen Daten: Die Regeln gelten überall. */
        val NONE: RealRoadside = object : RealRoadside {}

        /** So nah muss eine echte Planke an der geplanten Regel-Planke liegen, damit sie sie ersetzt. */
        const val GUARD_RAIL_MATCH_M = 4.0
    }
}

internal enum class RoadControlKind { STOP, GIVE_WAY, SIGNALS }

/**
 * Verkehrsregelung an einem Knoten. [forward]: `true` = gilt in Richtung der OSM-Linie,
 * `false` = entgegen, `null` = unbekannt (dann für die Zufahrt, die auf den nächsten Knoten zuläuft).
 */
internal class RoadControl(val e: Double, val n: Double, val kind: RoadControlKind, val forward: Boolean? = null)

internal enum class CycleLaneKind {
    /** Schutzstreifen (Z 340, unterbrochen 1 m / 1 m), `cycleway:lane=advisory`. */
    ADVISORY,

    /** Radfahrstreifen (Z 295, Breitstrich durchgezogen), `cycleway:lane=exclusive` oder ohne Angabe. */
    EXCLUSIVE,
}

/**
 * Radfahr-/Schutzstreifen entlang der Straße [line] (`e0, n0, …`, die Mittellinie der Straße).
 * [side]: +1 rechts der Linienrichtung, −1 links, 0 beidseits.
 */
internal class CycleLane(val line: DoubleArray, val side: Int, val kind: CycleLaneKind)
