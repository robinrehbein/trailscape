package de.trailscape.app.record

import de.trailscape.core.FusedPoint
import de.trailscape.core.PunktSensorWerte
import de.trailscape.core.Quelle
import de.trailscape.core.TrackPoint

/**
 * Entscheidungslogik der Handy-Bruecke — herausgeloest aus [RecordingService],
 * damit sie ohne Android und ohne eine laufende Aufzeichnung pruefbar ist
 * (dasselbe Muster wie [RecordingLogic], siehe dessen Klassendoc).
 *
 * ## Die Kernentscheidung
 * [RecordingService] speist jede angenommene Telefon-Position UND jede
 * eintreffende Uhr-Position in dieselbe [de.trailscape.core.LocationFusion]
 * (siehe dort). Was am Ende ins Journal geschrieben wird, waehlt
 * [waehlePunktZumAufzeichnen]:
 *
 *  * Stammt die zuletzt verarbeitete Fusions-Probe vom **Telefon**
 *    ([Quelle.TELEFON]), wird exakt der vom [de.trailscape.core.PointFilter]
 *    angenommene Telefon-Punkt aufgezeichnet — unveraendert, byteidentisch zum
 *    Verhalten vor dieser Bruecke. Der Kalman-Zustand liefert bei einer
 *    Telefon-Probe zwar ebenfalls eine (leicht geglaettete) Schaetzung, die
 *    aber bewusst NICHT verwendet wird: Eine Aufzeichnung ohne gekoppelte Uhr
 *    soll sich in nichts von der heutigen unterscheiden, auch nicht in
 *    Zehntelmetern durch Filterrauschen. Genau das prueft
 *    `RecordingFusionLogicTest`.
 *  * Stammt sie von der **Uhr** ([Quelle.UHR]) — die Uhr hat also seit dem
 *    letzten Telefon-Fix eine neue Position gemeldet, etwa weil das Telefon
 *    gerade in der Tasche keinen Fix bekommt —, wird die fusionierte Position
 *    aufgezeichnet. Das ist der eigentliche Gewinn der Bruecke: eine Luecke
 *    im Telefon-GPS wird durch die Uhr geschlossen, statt die Tour
 *    unterbrechen zu lassen.
 *
 * Die Sensorwerte haengen unabhaengig von der Positionsquelle an jedem
 * aufgezeichneten Punkt — sie haben mit der Positionsfrage nichts zu tun:
 * die Herzfrequenz ([TrackPoint.hr], ein frischer Pulsgurt vor der Uhr), die
 * Leistung ([TrackPoint.power], Mittel seit dem vorigen Punkt) und die
 * Trittfrequenz ([TrackPoint.cad]). Welcher Wert gilt, entscheidet
 * `punktSensorWerte` in `:core` (ueber `BleSensors.punktWerte`).
 */

/**
 * Waehlt die Position, die fuer eine verarbeitete Fusionsprobe tatsaechlich
 * aufgezeichnet wird. Siehe Datei-KDoc fuer die Begruendung.
 *
 * @param fused Ergebnis von `LocationFusion.fuege(...)` fuer die gerade
 *   verarbeitete Probe, oder `null`, wenn die Fusion sie verworfen hat (zu
 *   ungenau oder zu weit ausser der Reihe) — dann gilt derselbe Vorrang wie
 *   ohne Uhr: der Telefon-Punkt zaehlt.
 * @param telefonPunkt der vom [de.trailscape.core.PointFilter] angenommene
 *   Telefon-Punkt dieses Aufrufs. `null`, wenn dieser Aufruf durch eine
 *   Uhr-Probe ausgeloest wurde (keine begleitende Telefon-Position).
 * @param werte die Sensorwerte fuer diesen Punkt; [PunktSensorWerte.LEER]
 *   laesst den Basispunkt unveraendert (auch ein etwaiges `hr` darin).
 * @return `null`, wenn weder ein Telefon-Punkt noch eine fusionierte Position
 *   vorliegt — es gibt dann nichts aufzuzeichnen (nur bei kaputten Aufrufen,
 *   in der Praxis unerreichbar).
 */
fun waehlePunktZumAufzeichnen(
    fused: FusedPoint?,
    telefonPunkt: TrackPoint?,
    werte: PunktSensorWerte,
): TrackPoint? {
    val basis = if (fused != null && fused.zuletzt == Quelle.UHR) {
        TrackPoint(lat = fused.lat, lon = fused.lon, ele = fused.hoeheM, time = fused.zeitMs)
    } else {
        telefonPunkt
    } ?: return null

    val mitWerten = basis.copy(hr = werte.hr ?: basis.hr, power = werte.powerW ?: basis.power, cad = werte.cadRpm ?: basis.cad)
    // Ohne neue Werte derselbe Punkt, nicht nur ein gleicher: Aufzeichnungen
    // ohne Sensor bleiben so exakt beim alten Verhalten.
    return if (mitWerten == basis) basis else mitWerten
}
