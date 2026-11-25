package de.trailscape.core.indoor.world

import kotlin.math.hypot

/**
 * # Ortsliste der Strecke (Manifest `places`)
 *
 * Welche Orte die Strecke durchfaehrt oder streift, mit dem Laufmeter, an dem sie dem Ortspunkt am
 * naechsten kommt — fuer Einblendungen waehrend der Fahrt („Marbach am Neckar“, Bergwertung) und fuer
 * die Wiedererkennung. Quelle: Ebene `place` der Vektorkacheln (OpenStreetMap-Ortspunkte, dieselben
 * Namen wie auf den Ortstafeln, [PropsLayer]). Keine neue Datenquelle.
 *
 * Regeln:
 *  * Ortsklassen mit eigenem Einzugsradius um den Ortspunkt ([radiusOf]): Stadt 2,5 km, Kleinstadt
 *    1,6 km, Dorf 900 m, Weiler 350 m, Stadtteil/Ortsteil 500 m, Wohnplatz 250 m, Einzelgehoeft 120 m.
 *    Ortspunkte liegen meist am Kirchplatz bzw. in der Ortsmitte; die Radien sind so gewaehlt, dass eine
 *    Ortsdurchfahrt sicher, eine Fahrt am Nachbarort vorbei nicht mitzaehlt.
 *  * Jede **Annaeherung** zaehlt einzeln (eine Runde, die im Ort beginnt und endet, nennt ihn zweimal):
 *    zusammenhaengende Abschnitte innerhalb des Radius, je Abschnitt der naechste Punkt.
 *  * Nach Laufmeter sortiert; derselbe Name innerhalb von [MERGE_M] nur einmal.
 */
internal object WorldPlaces {
    /** Gleicher Name innerhalb dieser Laufmeter gilt als ein Eintrag. */
    const val MERGE_M = 800.0

    fun radiusOf(cls: String): Double? = when (cls) {
        "city" -> 2500.0
        "town" -> 1600.0
        "village" -> 900.0
        "hamlet" -> 350.0
        "suburb", "quarter" -> 500.0
        "neighbourhood" -> 250.0
        "isolated_dwelling", "farm" -> 120.0
        else -> null
    }

    fun of(map: MapData, track: WorldTrack): List<WorldPlace> {
        val count = track.sampleCount
        if (count < 2) return emptyList()
        var minE = Double.MAX_VALUE; var minN = Double.MAX_VALUE
        var maxE = -Double.MAX_VALUE; var maxN = -Double.MAX_VALUE
        for (i in 0 until count) {
            val e = track.e[i].toDouble(); val n = track.n[i].toDouble()
            if (e < minE) minE = e; if (e > maxE) maxE = e
            if (n < minN) minN = n; if (n > maxN) maxN = n
        }
        val pad = 2600.0
        val found = ArrayList<WorldPlace>()
        for (p in map.places(minE - pad, minN - pad, maxE + pad, maxN + pad)) {
            val r = radiusOf(p.cls) ?: continue
            if (p.name.isBlank()) continue
            var inside = false
            var bestD = Double.MAX_VALUE
            var bestI = -1
            for (i in 0 until count) {
                val d = hypot(track.e[i] - p.e, track.n[i] - p.n)
                if (d <= r) {
                    inside = true
                    if (d < bestD) { bestD = d; bestI = i }
                } else if (inside) {
                    found += WorldPlace(p.name.trim(), round1(track.distAt(bestI)))
                    inside = false; bestD = Double.MAX_VALUE; bestI = -1
                }
            }
            if (inside && bestI >= 0) found += WorldPlace(p.name.trim(), round1(track.distAt(bestI)))
        }
        found.sortWith(compareBy({ it.distM }, { it.name }))
        val out = ArrayList<WorldPlace>()
        for (w in found) {
            if (out.any { it.name == w.name && kotlin.math.abs(it.distM - w.distM) < MERGE_M }) continue
            out += w
        }
        return out
    }

    private fun round1(d: Double) = Math.round(d * 10.0) / 10.0
}
